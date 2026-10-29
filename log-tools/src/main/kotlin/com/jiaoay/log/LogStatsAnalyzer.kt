package com.jiaoay.log

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 日志分布统计（纯函数、单遍流式读、内存有界——状态只有计数表，行不驻留）：
 * 块级 Tag 频次 / 级别计数 / 首末时间戳跨度。供用户写过滤正则前先看分布，免盲试。
 *
 * 块识别复用 [LogTagFilter.scanBlocks] 共享内核（与 Tag 过滤/预演同一套块规则，
 * 不另造第二套解析器）；Tag 与级别从每个完整块的**头行**提取。头行解析不出的块
 * 不计入对应维度——[LogStatsResult.Ok.tagCounts] 与 [LogStatsResult.Ok.levelCounts]
 * 各自缺席的块数由 totalBlocks 减去对应合计可得，消费方按「未识别」呈现。
 *
 * 头行级别/Tag 双形态（与 logcat threadtime / brief 主流形态对齐）：
 * - brief/time 形态：时间戳前缀后 `E/Tag: msg` 或 `E/Tag(pid): msg`
 * - threadtime 形态：时间戳前缀后 `pid tid E Tag: msg`（独立单字母级别 token，
 *   后随 Tag token；Tag token 缺席时级别照常入账、Tag 计未识别）
 *
 * 时间戳解析走 [LogTimestamp] 契约单源。裸格式 `MM-dd` 行无年份，以**当前年份**
 * 锚定——同文件内的相对先后与跨度不受影响；跨年边界的日志首末绝对时刻可能偏差，
 * 消费方呈现首末时刻时须知该口径。
 */
object LogStatsAnalyzer {

    /** 单字母级别档，ordinal 即严重度序：VERBOSE < DEBUG < INFO < WARN < ERROR < FATAL */
    enum class Level(val letter: String) {
        VERBOSE("V"),
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),
        FATAL("F"),
    }

    /**
     * 对整个文件做分布统计（全量单遍读，非截断预演）。
     * @throws 无——读取异常均折叠为 [LogStatsResult.Failure]
     */
    fun analyze(file: File): LogStatsResult {
        return try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 1024 * 1024).use { reader ->
                val fallbackYear = LocalDate.now().year.toString()
                val tagCounts = HashMap<String, Int>()
                val levelCounts = HashMap<Level, Int>()
                var first: LocalDateTime? = null
                var last: LocalDateTime? = null

                val summary = LogTagFilter.scanBlocks(
                    reader = reader,
                    tagPattern = null,
                    maxLines = null,
                    onMatchedBlock = {},
                ) { block, _ ->
                    val head = block.first()
                    parseHead(head).let { info ->
                        info.tag?.let { tagCounts.merge(it, 1, Int::plus) }
                        info.level?.let { levelCounts.merge(it, 1, Int::plus) }
                    }
                    // 非单调日志按值取 min/max（后出现的更早时间须修正 first）
                    LogTimestamp.parseLine(head, fallbackYear)?.let { ts ->
                        val curFirst = first
                        val curLast = last
                        if (curFirst == null || ts.isBefore(curFirst)) first = ts
                        if (curLast == null || ts.isAfter(curLast)) last = ts
                    }
                }

                LogStatsResult.Ok(
                    scannedLines = summary.scannedLines,
                    totalBlocks = summary.totalBlocks,
                    tagCounts = tagCounts.map { (tag, count) -> TagCount(tag, count) }
                        .sortedWith(compareByDescending<TagCount> { it.count }.thenBy { it.tag }),
                    levelCounts = levelCounts,
                    firstTimestamp = first,
                    lastTimestamp = last,
                    spanMillis = if (first != null && last != null) {
                        ChronoUnit.MILLIS.between(first, last)
                    } else {
                        null
                    },
                )
            }
        } catch (e: Throwable) {
            // 零日志门面依赖（fat jar 纪律）：统一走 System.err 单行结构化输出，异常链逐层展开
            ToolsLog.reportFailure("LogStatsAnalyzer", e)
            LogStatsResult.Failure("统计失败, message: ${e.message}")
        }
    }

    /** 头行解析结果：级别/Tag 均可缺席（形态不命中时两者皆 null） */
    internal data class HeadInfo(val level: Level?, val tag: String?)

    /** brief/time 形态头：`L/Tag` 连写；Tag 不得含空白/冒号/括号，后随 `:` 或 `(` 边界 */
    private val LEVEL_SLASH_TAG = Regex("""(?:^|\s)([VDIWEF])/([^\s:()]+)(?=[(:])""")

    /** threadtime 形态头 token 切分空白（parseHead 每次调用现场构造 = 逐行重复编译） */
    private val WHITESPACE_SPLIT = Regex("\\s+")

    /**
     * 解析块头行的级别与 Tag。时间戳前缀经 [LogTimestamp.maskLineTimestamp] 抹去后
     * 按剩余部分识别双形态；两种形态都不命中返回 (null, null)（计入未识别）。
     */
    internal fun parseHead(headLine: String): HeadInfo {
        val payload = LogTimestamp.maskLineTimestamp(headLine, "").trim()
        if (payload.isEmpty()) return HeadInfo(null, null)
        LEVEL_SLASH_TAG.find(payload)?.let { m ->
            val level = levelOf(m.groupValues[1]) ?: return HeadInfo(null, null)
            return HeadInfo(level, m.groupValues[2])
        }
        // threadtime 形态：首个独立单字母级别 token，后随 Tag token（`: msg` 余部并入 Tag token）
        val tokens = payload.split(WHITESPACE_SPLIT)
        for (i in tokens.indices) {
            val level = levelOf(tokens[i]) ?: continue
            val tag = tokens.getOrNull(i + 1)
                ?.substringBefore('(')
                ?.substringBefore(':')
                .orEmpty()
            return HeadInfo(level, tag.ifEmpty { null })
        }
        return HeadInfo(null, null)
    }

    private fun levelOf(token: String): Level? = Level.entries.firstOrNull { it.letter == token }
}

/**
 * 分布统计结果（[LogStatsAnalyzer.analyze] 专用）。
 *
 * [Ok] 的全部计数基于**整个文件**（与截断预演 [TagPreviewResult] 相对）。
 */
sealed class LogStatsResult {

    /**
     * @property scannedLines 扫描的总行数
     * @property totalBlocks 完整块总数
     * @property tagCounts Tag 频次降序（并列按 Tag 字典序）；头行解析不出 Tag 的块缺席，
     *   缺席块数 = totalBlocks - 各项 count 合计
     * @property levelCounts 级别 → 块数（仅非零档）；头行解析不出级别的块缺席，
     *   缺席块数 = totalBlocks - 各值合计
     * @property firstTimestamp 首时间戳（按值取 min，非首个时间行）；全文无时间行为 null
     * @property lastTimestamp 末时间戳（按值取 max）；全文无时间行为 null
     * @property spanMillis 首末时间戳跨度毫秒数；任一端缺席为 null
     */
    data class Ok(
        val scannedLines: Int,
        val totalBlocks: Int,
        val tagCounts: List<TagCount>,
        val levelCounts: Map<LogStatsAnalyzer.Level, Int>,
        val firstTimestamp: LocalDateTime?,
        val lastTimestamp: LocalDateTime?,
        val spanMillis: Long?,
    ) : LogStatsResult()

    data class Failure(val reason: String) : LogStatsResult()
}

/** Tag 频次行（[LogStatsResult.Ok.tagCounts] 的元素） */
data class TagCount(val tag: String, val count: Int)
