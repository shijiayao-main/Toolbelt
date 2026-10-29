package com.jiaoay.log.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.jiaoay.log.LogDiff
import com.jiaoay.log.LogSplitter
import com.jiaoay.log.LogStatsAnalyzer
import com.jiaoay.log.LogStatsResult
import com.jiaoay.log.LogTagFilter
import com.jiaoay.log.LogTimeFilter
import com.jiaoay.log.ToolsResult
import com.jiaoay.xlog.LogDecode
import com.jiaoay.xlog.LogFormatDetector
import java.io.File
import java.time.format.DateTimeFormatter

/**
 * 平铺参数单命令：--type 选择子功能，全部旗标平铺挂同一命令。
 * 旧 groupChoice/OptionGroup 形态在 pipeline 类型并入后旗标跨组重名（-k/--key/
 * --targetTime/--range/--tagRegex/--tagName），clikt 启动即炸——平铺后单域天然无重名。
 * 既有旗标名全部保持（skill 侧命令模板零回归）；pipeline 私钥旗标 --key 与 xlog 共用。
 */
class LogUtilsCommand : CliktCommand() {

    private val TYPES = listOf(
        "xlog",
        "filter_by_tag",
        "filter_by_time",
        "filter_by_range",
        "split_by_size",
        "pipeline",
        "diff",
        "stats",
    )

    private val type: String by option("--type", help = "子功能类型: ${TYPES.joinToString("|")}")
        .required()
        .validate {
            require(it in TYPES) { "invalid --type: $it (choose from ${TYPES.joinToString(",")})" }
        }

    private val targetFile: File by option("-f", "--file", help = "目标文件")
        .file(mustExist = true, canBeDir = false)
        .required()
        .validate {
            require(value = it.exists()) {
                "Target file does not exist: $it"
            }
        }

    // xlog / pipeline 共用
    private val privateKey: String? by option("-k", "--key", help = "xlog私钥（xlog 必填；pipeline .txt 明文可省）")

    // filter_by_tag / pipeline 共用
    private val tagRegex: String? by option("--tagRegex", help = "tag正则")
    private val tagName: String? by option("--tagName", help = "tag名称（产物文件名标记）")

    // filter_by_time / pipeline 共用
    private val targetTime: String? by option(
        "--targetTime",
        help = "过滤目标时间 yyyy-MM-dd_HH:mm:ss.SSS，亦容忍裸 MM-dd HH:mm:ss.SSS（xlog 明文行首同款，仅裸格式日志正确）",
    )
    private val range: Long? by option("--range", help = "过滤时间范围, 单位分钟")
        .long()

    // filter_by_range：起止时刻容忍空格分隔、省略毫秒与裸 MM-dd HH:mm:ss.SSS
    // （xlog 自产明文行首同款，从日志直接复制即可；裸形态仅对裸格式日志保证
    // 正确，年份格式/跨年日志请补全年份）——口径与 UI 侧 tools.slice-time 一致（宁拒不猜）
    private val startTime: String? by option(
        "--startTime",
        help = "区间起始时间 yyyy-MM-dd_HH:mm:ss.SSS，亦容忍裸 MM-dd HH:mm:ss.SSS（xlog 明文行首同款，仅裸格式日志正确）",
    )
    private val endTime: String? by option(
        "--endTime",
        help = "区间结束时间 yyyy-MM-dd_HH:mm:ss.SSS，亦容忍裸 MM-dd HH:mm:ss.SSS（xlog 明文行首同款，仅裸格式日志正确）",
    )

    // split_by_size
    private val sizeMb: Int? by option("--size", help = "每个分片的大小上限，单位 MB")
        .int()

    // pipeline
    private val envPattern: String? by option("--envPattern", help = "环境画像特征正则（可省——跳过环境扫描级）")

    // diff
    private val otherFile: File? by option("--other", help = "对比的另一份日志文件（diff 必填）")
        .file(mustExist = true, canBeDir = false)
        .validate {
            require(value = it.exists()) {
                "Other file does not exist: $it"
            }
        }
    private val normalizeTimestamps: Boolean by option(
        "--normalizeTimestamps",
        help = "时间戳归一化对齐：仅行首时间戳不同的行视为相同（比行为不比时钟）",
    ).flag()

    override fun run() {
        checkRequired(type)
        val failed: Boolean = when (type) {
            "xlog" -> {
                decodeFile(); false
            }
            "filter_by_tag" -> filterByTag()
            "filter_by_time" -> filterByTime()
            "filter_by_range" -> filterByRange()
            "split_by_size" -> splitBySize()
            "pipeline" -> runPipeline()
            "diff" -> runDiff()
            "stats" -> runStats()
            else -> true
        }
        // ToolsResult.Failure 语义化退出码 1（脚本化消费/降级协议判定依据；clikt 参数错误自身已非零）
        if (failed) throw com.github.ajalt.clikt.core.CliktError("operation failed")
    }

    /** 按类型校验独占必填参数（--type 决定哪组必填；平铺后由这里承担原 OptionGroup.required 语义） */
    private fun checkRequired(t: String) {
        fun req(name: String, value: Any?) {
            require(value != null) { "--$name is required for --type $t" }
        }
        val ext = targetFile.extension.lowercase()
        when (t) {
            "xlog" -> {
                req("key", privateKey)
                require(ext in listOf("xlog", "alog")) { "extension is: $ext, only support xlog,alog" }
            }
            "filter_by_tag" -> {
                req("tagRegex", tagRegex)
                req("tagName", tagName)
                require(ext in listOf("txt", "log")) { "extension is: $ext, only support txt,log" }
            }
            "filter_by_time" -> {
                req("targetTime", targetTime)
                req("range", range)
                require(ext in listOf("txt", "log")) { "extension is: $ext, only support txt,log" }
            }
            "filter_by_range" -> {
                req("startTime", startTime)
                req("endTime", endTime)
                require(ext in listOf("txt", "log")) { "extension is: $ext, only support txt,log" }
            }
            "split_by_size" -> {
                req("size", sizeMb)
                require(ext in listOf("txt", "log")) { "extension is: $ext, only support txt,log" }
            }
            "pipeline" -> {
                req("targetTime", targetTime)
                req("range", range)
                require(ext in listOf("xlog", "alog", "txt", "log")) {
                    "extension is: $ext, only support xlog,alog,txt,log"
                }
            }
            "diff" -> {
                req("other", otherFile)
                require(ext in listOf("txt", "log")) { "extension is: $ext, only support txt,log" }
                val otherExt = otherFile!!.extension.lowercase()
                require(otherExt in listOf("txt", "log")) { "extension is: $otherExt, only support txt,log" }
            }
            "stats" -> {
                require(ext in listOf("txt", "log")) { "extension is: $ext, only support txt,log" }
            }
        }
    }

    private fun filterByTag(): Boolean {
        return when (val result = LogTagFilter.filterByTag(
            targetFile,
            tagRegex = tagRegex!!,
            tagName = tagName!!,
        )) {
            is ToolsResult.Failure -> {
                echo(result.reason); true
            }
            is ToolsResult.Success -> {
                // 降级形态提示先于成功锚输出（工具诚实性：调用方须知道块口径已变）
                result.warning?.let { echo("warning: $it") }
                echo("success! outputFile: ${result.outputFile.absolutePath}"); false
            }
        }
    }

    private fun filterByTime(): Boolean {
        return when (val result = LogTimeFilter.filterByTime(
            targetFile,
            target = targetTime!!,
            rangeMinute = range!!,
        )) {
            is ToolsResult.Failure -> {
                echo(result.reason); true
            }
            is ToolsResult.Success -> {
                echo("success! outputFile: ${result.outputFile.absolutePath}"); false
            }
        }
    }

    /**
     * 起止区间截取（引擎 = [LogTimeFilter.filterByRange]）：与 filter_by_time 的时间点
     * ±N 分钟对称窗口语义互不替代——奇数分钟区间（如 13:00~13:35）只有本型能精确
     * 表达。时间参数口径与 UI 侧 tools.slice-time 一致：省略毫秒/空格分隔/裸
     * MM-dd HH:mm:ss.SSS（xlog 自产明文行首同款，从日志直接复制即可——仅对裸格式
     * 日志保证正确，年份格式/跨年日志请补全年份）由引擎容错解析，无法解析/起止
     * 倒挂由引擎拒绝（宁拒不猜）。产物名与对称窗口同款 `filter-time_HHmm-HHmm`。
     */
    private fun filterByRange(): Boolean {
        return when (val result = LogTimeFilter.filterByRange(
            targetFile,
            startTime = startTime!!,
            endTime = endTime!!,
        )) {
            is ToolsResult.Failure -> {
                echo(result.reason); true
            }
            is ToolsResult.Success -> {
                echo("success! outputFile: ${result.outputFile.absolutePath}"); false
            }
        }
    }

    private fun decodeFile() {
        val outFile = siblingOutput("${targetFile.name}.txt")
        decodeAndEcho(targetFile, outFile, privateKey!!)
    }

    /**
     * 解密统一入口（内容判型路由）：xlog 段结构 → 真解密；明文形态（已解密文件误挂
     * .alog 后缀 / 明文 mmap）→ 跳过解密按产物语义复制并说明；UNKNOWN → 按后缀意图
     * 照常尝试解密（失败如实报错）。
     */
    private fun decodeAndEcho(input: File, outFile: File, key: String) {
        when (LogFormatDetector.detect(input)) {
            LogFormatDetector.Format.PLAINTEXT -> {
                // 明文误挂加密后缀：产物语义 = 明文副本（对齐页面/agent 工具「同目录产物」约定）
                input.copyTo(outFile, overwrite = true)
                echo("skip-decrypt: 内容已是明文（无需解密），已按产物语义输出副本")
            }
            else -> {
                LogDecode().decodeFile(privateKey = key, logFile = input, outFile = outFile)
                if (outFile.isFile && outFile.length() == 0L) {
                    // 0 字节产物双可能性：明文 mmap（无加密内容可解）或密钥/数据不匹配。
                    // 给出判别指引，避免下游拿空文件继续过滤得出「日志不存在」的假结论
                    echo(
                        "warning: 解密产物 0 字节——若源文件首行为 'begin of mmap' 或行首已有可读时间戳，" +
                            "属明文 mmap 形态（无需解密，直接使用源文件即可）；否则请核对私钥是否正确"
                    )
                }
            }
        }
        echo("success! outputFile: ${outFile.absolutePath}")
    }

    private fun siblingOutput(name: String): File {
        val parent = targetFile.parent
        return if (parent.isNullOrEmpty()) File(name) else File(parent, name)
    }

    private fun splitBySize(): Boolean {
        return when (val result = LogSplitter.splitBySize(targetFile, sizeMb = sizeMb!!)) {
            is ToolsResult.Failure -> {
                echo(result.reason); true
            }
            is ToolsResult.Success -> {
                echo("success! first outputFile: ${result.outputFile.absolutePath}"); false
            }
        }
    }

    /**
     * P4-A 确定性管线 CLI 形态：逐级打印阶段产物/附注；中止打印阶段标记 + 原因
     * （非零退出码——脚本化消费；agent 形态经 `analyze_log` 工具拿同款结构化结果）。
     */
    private fun runPipeline(): Boolean {
        val request = com.jiaoay.log.pipeline.LogAnalysisPipeline.Request(
            logFile = targetFile,
            privateKey = privateKey ?: "",
            targetTime = targetTime!!,
            rangeMinutes = range!!,
            tagRegex = tagRegex,
            tagName = tagName,
            envPattern = envPattern,
        )
        return when (val result = com.jiaoay.log.pipeline.LogAnalysisPipeline.run(request)) {
            is com.jiaoay.log.pipeline.LogAnalysisPipeline.StageResult.Ok -> {
                echo("success! stage=${result.stage}, outputFile: ${result.artifact?.absolutePath ?: "-"}, note: ${result.note}")
                false
            }

        is com.jiaoay.log.pipeline.LogAnalysisPipeline.StageResult.Abort -> throw com.github.ajalt.clikt.core.CliktError(
            "aborted at stage=${result.stage}: ${result.reason}",
        )
        }
    }

    /**
     * diff 形态：产物名 `<基准>.diff_<对比>.txt`，落在基准文件同目录；产物覆盖旧次输出
     * （对比常反复重跑——与 filter/split 的存在性拒绝不同，对齐解密产物覆盖语义）。
     * 退化标记（编辑距离超对齐上限）以 warning 行提示，不改变退出码。
     */
    private fun runDiff(): Boolean {
        val result = LogDiff.diff(targetFile, otherFile!!, normalizeTimestamps = normalizeTimestamps)
        return when (result) {
            is LogDiff.DiffResult.Failure -> {
                echo(result.reason); true
            }

            is LogDiff.DiffResult.Ok -> {
                if (result.degraded) {
                    echo("warning: 差异规模超过逐行对齐上限，中间区域按整体替换展示（建议加 --normalizeTimestamps 归一化后再比）")
                }
                val outFile = siblingOutput(
                    "${targetFile.nameWithoutExtension}.diff_${otherFile!!.nameWithoutExtension}.txt",
                )
                when (val written = LogDiff.writeUnified(result, targetFile.name, otherFile!!.name, outFile)) {
                    is ToolsResult.Failure -> {
                        echo(written.reason); true
                    }

                    is ToolsResult.Success -> {
                        echo("success! outputFile: ${written.outputFile.absolutePath}, hunks: ${result.hunks.size}"); false
                    }
                }
            }
        }
    }

    /**
     * stats 形态：分布统计**纯输出型**（零产物文件）——Tag 频次 / 级别计数 / 时间跨度
     * echo 到 stdout，供写过滤正则前先看分布免盲试。呈现语义与桌面统计面板、agent 工具
     * log.stats 同核同口径：引擎 = [LogStatsAnalyzer.analyze] 单源；未识别块数按引擎
     * KDoc 口径（totalBlocks 减对应合计）如实呈现，不猜零值。
     */
    private fun runStats(): Boolean {
        return when (val result = LogStatsAnalyzer.analyze(targetFile)) {
            is LogStatsResult.Failure -> {
                echo(result.reason); true
            }

            is LogStatsResult.Ok -> {
                renderStatsLines(result, targetFile.name).forEach(::echo)
                echo("success! stats: ${targetFile.absolutePath}（纯统计，无产物文件）"); false
            }
        }
    }

    private fun renderStatsLines(result: LogStatsResult.Ok, fileName: String): List<String> = buildList {
        add("统计概览（$fileName）：")
        add("- 扫描行数: ${result.scannedLines}")
        add("- 日志块总数: ${result.totalBlocks}")
        if (result.firstTimestamp != null && result.lastTimestamp != null) {
            add(
                "- 时间跨度: ${STATS_TIME_FORMAT.format(result.firstTimestamp)} ~ " +
                    "${STATS_TIME_FORMAT.format(result.lastTimestamp)}（共 ${result.spanMillis} 毫秒）",
            )
        } else {
            add("- 时间跨度: 无可识别时间行")
        }
        val levels = LogStatsAnalyzer.Level.entries.joinToString(" ") {
            "${it.letter}=${result.levelCounts[it] ?: 0}"
        }
        add("- 级别计数（块）: $levels（未识别级别块数: ${result.totalBlocks - result.levelCounts.values.sum()}）")
        if (result.tagCounts.isEmpty()) {
            add("- Tag 频次: 无可识别 Tag")
        } else {
            val top = result.tagCounts.take(STATS_TAG_TOP_N)
            add(
                "- Tag 频次 Top ${top.size}/${result.tagCounts.size}" +
                    "（未识别 Tag 块数: ${result.totalBlocks - result.tagCounts.sumOf { it.count }}）：",
            )
            top.forEach { add("  - ${it.tag}: ${it.count}") }
            val rest = result.tagCounts.size - top.size
            if (rest > 0) {
                add("  （其余 $rest 个 Tag 未列出——可用 --type filter_by_tag 按正则下钻）")
            }
        }
    }

    private companion object {
        /** stats：Tag 频次最多列出的条数（Top 截断防 stdout 刷屏；与 agent 工具 log.stats 的 Top 截断同档，见 core/llm/tools/LogStatsDiffTools.kt） */
        const val STATS_TAG_TOP_N = 30

        /** stats：时间戳呈现格式（与 --targetTime 入参口径 yyyy-MM-dd_HH:mm:ss.SSS 一致） */
        val STATS_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss.SSS")
    }
}
