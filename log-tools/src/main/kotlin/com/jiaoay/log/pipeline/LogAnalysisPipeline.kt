package com.jiaoay.log.pipeline

import com.jiaoay.log.LogTagFilter
import com.jiaoay.log.LogTimeFilter
import com.jiaoay.log.ToolsResult
import com.jiaoay.xlog.LogDecode
import com.jiaoay.xlog.LogProbe
import java.io.File

/**
 * 日志分析确定性管线（log-helper skill 固化第一步）。
 *
 * ## 定位（G1 调研裁定：中间结果不穿模型）
 * skill 时代 Step 1~3/5 是「prompt 定义的概率性流程」——每次执行靠 LLM 记住步骤，
 * 产物路径靠猜、空结果靠肉眼、失败靠运气。本管线把 Stage A~D 全部落成**确定性代码**：
 * - Stage A~D 逐级执行、每级产物**精确路径**（不再「兄弟目录找同前缀新文件」）；
 * - 空结果/失败**显式中止**（带阶段标记与原因——LLM 只收到「哪一步、为什么停」，
 *   不会拿着半截上下文继续幻觉）；
 * - 语义研判（Step 6 十铁律）**不在本管线**——归 P4-B 的根因研判 agent 节点。
 *
 * ## 阶段映射（skill → 管线）
 * | skill 步骤 | 管线阶段 | 载体 |
 * |---|---|---|
 * | Step 1 日志文件定位 | [Request] 输入参数 | 调用方/agent 现场定位（目录结构知识归知识库，不做死规则） |
 * | Step 2 解密 | [Stage.DECRYPT] | [LogDecode]（复用产物） |
 * | Step 3 时间过滤 + 空结果校验 | [Stage.TIME_FILTER] + 空结果校验 | [LogTimeFilter] |
 * | Step 3.5 环境画像 | [Stage.ENV_SCAN] | 调试特征 grep（知识库 TAG 驱动，可关） |
 * | Step 4 Tag 获取 | [Stage.TAG_FILTER] 入参 | agent 经 knowledge.* 反查合并 |
 * | Step 5 Tag 过滤 | [Stage.TAG_FILTER] | [LogTagFilter] |
 * | Step 6 深度分析 | ❌ 不进管线 | 根因研判 agent（报告走 ToolResult.Ok.artifacts） |
 * | Step 7 报告生成 | ❌ 不进管线 | agent 生成 |
 *
 * ## 用法形态
 * - **一次性全跑**：[run]（标准五级管线）；
 * - **逐级跑**：[runDecrypt]/[runTimeFilter]/[runEnvScan]/[runTagFilter]（agent 工具
 *   `analyze_log` 分步调用——把每级结果回传 LLM 决定下一步）；
 * - CLI：`log-tools --type pipeline`（P4-A 收尾接线）。
 *
 * ## 产物落点纪律（skill Step 7.1 铁律平移）
 * 中间产物与源文件同目录（LogTimeFilter/LogTagFilter 既有语义）；**报告产物禁止
 * 落在源码仓库内**——报告由 agent 生成，落点由 agent 节点决定（本管线不产报告）。
 */
object LogAnalysisPipeline {

    /** 管线入参（Step 0 已校验完整——不完整在调用方中止，管线不做二次猜） */
    data class Request(
        /** 原始日志文件（.alog/.xlog 加密 或 .txt 明文） */
        val logFile: File,
        /** xlog 私钥（.alog/.xlog 必填；.txt 无视） */
        val privateKey: String,
        /** 目标时间点（格式 yyyy-MM-dd_HH:mm:ss.SSS） */
        val targetTime: String,
        /** 时间窗口（分钟，对称窗口 ±range） */
        val rangeMinutes: Long,
        /** Tag 正则（合并式，如 "TagA|TagB"；null/blank = 跳过 Tag 过滤级） */
        val tagRegex: String? = null,
        /** Tag 名（产物文件名标记；tagRegex 非空时必填） */
        val tagName: String? = null,
        /** 环境画像特征正则（知识库驱动；null = 跳过环境扫描级） */
        val envPattern: String? = null,
    )

    /** 单级执行结果（逐级 agent 分步调用的反馈面） */
    sealed class StageResult {
        /** 成功：级名 + 产物路径 + 附注（行数/命中数等观测值） */
        data class Ok(
            val stage: Stage,
            val artifact: File?,
            val note: String,
        ) : StageResult()

        /** 中止：级名 + 原因（LLM 收到后决定修正重跑还是报告用户） */
        data class Abort(
            val stage: Stage,
            val reason: String,
        ) : StageResult()
    }

    /** 管线阶段标识（中止标记 + agent 可见反馈） */
    enum class Stage { DECRYPT, TIME_FILTER, ENV_SCAN, TAG_FILTER }

    /** 时间过滤空结果守护（skill Step 3 空结果校验【强制】——< 5 行即中止） */
    internal const val TIME_FILTER_MIN_LINES = 5

    // ── 一次性全跑 ──

    /**
     * 标准五级管线（解密 → 时间过滤 + 空结果守护 → 环境扫描 → Tag 过滤）。
     * 任一级中止即整体中止（后续级不跑）——部分产物保留（调用方可从产物级重跑）。
     */
    fun run(request: Request): StageResult {
        val decrypted = runDecrypt(request.logFile, request.privateKey)
        if (decrypted !is StageResult.Ok) return decrypted

        val timeFiltered = runTimeFilter(decrypted.artifact!!, request.targetTime, request.rangeMinutes)
        if (timeFiltered !is StageResult.Ok) return timeFiltered

        // 环境扫描不中止管线（命中只置「环境污染模式」——报告元数据注明，不阻断分析）
        val envScan = if (request.envPattern.isNullOrBlank()) {
            null
        } else {
            runEnvScan(timeFiltered.artifact!!, request.envPattern)
        }

        // Tag 过滤可跳（tagRegex 空 = 只做时间维度——agent 自判是否需要）
        return if (request.tagRegex.isNullOrBlank()) {
            timeFiltered
        } else {
            val tagFiltered = runTagFilter(timeFiltered.artifact!!, request.tagRegex, request.tagName)
            if (tagFiltered is StageResult.Ok && envScan is StageResult.Ok) {
                // 合成观测面：Tag 过滤产物 + 环境命中附注（环境污染模式不丢）
                tagFiltered.copy(note = tagFiltered.note + "\n" + envScan.note)
            } else {
                tagFiltered
            }
        }
    }

    // ── 逐级执行（agent 分步调用面） ──

    /** Stage 2 · 解密（.txt/.log 明文直接过；.alog/.xlog 解密产物已存在则复用——skill Step 2 同语义） */
    fun runDecrypt(logFile: File, privateKey: String): StageResult {
        if (!logFile.isFile) return StageResult.Abort(Stage.DECRYPT, "日志文件不存在: ${logFile.absolutePath}")
        val lowerName = logFile.name.lowercase()
        if (!lowerName.endsWith(".alog") && !lowerName.endsWith(".xlog") &&
            !lowerName.endsWith(".txt") && !lowerName.endsWith(".log")
        ) {
            return StageResult.Abort(Stage.DECRYPT, "不支持的日志格式: ${logFile.name}（支持 .alog/.xlog/.txt/.log）")
        }
        if (lowerName.endsWith(".txt") || lowerName.endsWith(".log")) {
            return StageResult.Ok(Stage.DECRYPT, logFile, "明文日志，跳过解密")
        }
        val probe = LogProbe.probe(logFile)
        if (!probe.isXLog) {
            return StageResult.Abort(Stage.DECRYPT, "文件不是有效的 xlog/alog 格式（探测 0 段）")
        }
        if (probe.needsPrivateKey && privateKey.isBlank()) {
            return StageResult.Abort(Stage.DECRYPT, "日志段需要私钥解密但私钥为空——请先在「XLog 解密」页配置")
        }
        val output = File(logFile.parentFile, "${logFile.name}.txt")
        if (output.isFile) {
            return StageResult.Ok(Stage.DECRYPT, output, "解密产物已存在，直接复用")
        }
        return try {
            LogDecode().decodeFile(privateKey = privateKey, logFile = logFile, outFile = output)
            StageResult.Ok(Stage.DECRYPT, output, "解密成功")
        } catch (e: Throwable) {
            StageResult.Abort(Stage.DECRYPT, "解密失败: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Stage 3 · 时间过滤 + 空结果守护。
     * 空结果（< 5 行）返回 [StageResult.Abort]——skill Step 3 铁律：不得立即判定日志丢失，
     *    调用方（agent）须先执行 Stage D 定位校验回捞（在日志目录内低成本探测目标时间戳
     *    是否真实存在）再决定「选错文件修正重跑」还是「轮换丢失，中止报告用户」。
     */
    fun runTimeFilter(decodedFile: File, targetTime: String, rangeMinutes: Long): StageResult {
        return when (val result = LogTimeFilter.filterByTime(decodedFile, targetTime, rangeMinutes)) {
            is ToolsResult.Success -> {
                val lines = countLines(result.outputFile)
                if (lines < TIME_FILTER_MIN_LINES) {
                    StageResult.Abort(
                        Stage.TIME_FILTER,
                        "时间过滤产物仅 $lines 行（< $TIME_FILTER_MIN_LINES）——先做定位校验回捞" +
                            "（在同目录相邻小时文件探测目标时间戳）再决定修正文件或中止",
                    )
                } else {
                    StageResult.Ok(Stage.TIME_FILTER, result.outputFile, "时间过滤完成，$lines 行")
                }
            }
            is ToolsResult.Failure -> StageResult.Abort(Stage.TIME_FILTER, result.reason)
        }
    }

    /**
     * Stage 3.5 · 环境画像（非常规环境扫描）。**永不中止**——命中只置污染模式附注
     * （skill 语义：报告元数据注明 + 归因前先排除调试写入），未命中静默通过。
     */
    fun runEnvScan(timeFilteredFile: File, envPattern: String): StageResult {
        if (envPattern.isBlank()) return StageResult.Ok(Stage.ENV_SCAN, null, "环境扫描未启用（无特征正则）")
        val hits = grepCount(timeFilteredFile, envPattern)
        return if (hits > 0) {
            StageResult.Ok(
                Stage.ENV_SCAN, null,
                "⚠️ 环境污染模式：命中 $hits 处调试/测试注入特征（归因前先排除调试写入；报告须注明）",
            )
        } else {
            StageResult.Ok(Stage.ENV_SCAN, null, "环境画像干净（未命中调试注入特征）")
        }
    }

    /** Stage 5 · Tag 过滤（tagRegex 空 = 调用方本就不该调这级） */
    fun runTagFilter(timeFilteredFile: File, tagRegex: String, tagName: String?): StageResult {
        if (tagRegex.isBlank()) return StageResult.Abort(Stage.TAG_FILTER, "tagRegex 为空——先经知识库反查合并 Tag")
        val name = tagName?.takeIf { it.isNotBlank() } ?: tagRegex.take(40)
        return when (val result = LogTagFilter.filterByTag(timeFilteredFile, tagRegex, name)) {
            is ToolsResult.Success -> {
                val lines = countLines(result.outputFile)
                if (lines == 0L) {
                    StageResult.Abort(
                        Stage.TAG_FILTER,
                        "Tag 过滤产物 0 行——检查 tagRegex（可能 TAG 截断/前缀形态不匹配，" +
                            "先用 knowledge.lookup_tag 反查实际落盘形式）",
                    )
                } else {
                    // 降级形态提示并入阶段附注（管道附注直达 agent——形态不可静默）
                    StageResult.Ok(
                        Stage.TAG_FILTER, result.outputFile,
                        "Tag 过滤完成，$lines 行" + (result.warning?.let { "；$it" } ?: ""),
                    )
                }
            }
            is ToolsResult.Failure -> StageResult.Abort(Stage.TAG_FILTER, result.reason)
        }
    }

    // ── 辅助 ──

    /** 行数统计（空结果校验/产物观测；流式计数——末行无换行符补 1） */
    internal fun countLines(file: File): Long {
        var count = 0L
        var lastByte = -1
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = buf[i]
                    if (b == '\n'.code.toByte()) count++
                    lastByte = b.toInt()
                }
            }
        }
        return if (file.length() > 0 && lastByte != '\n'.code) count + 1 else count
    }

    /** 特征计数（环境画像——一次读盘；坏正则按 0 命中处理：附注观测不炸管线） */
    internal fun grepCount(file: File, pattern: String): Int {
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return 0
        var hits = 0
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (regex.containsMatchIn(line!!)) hits++
            }
        }
        return hits
    }
}
