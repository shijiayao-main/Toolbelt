package com.jiaoay.xlog.postprocess

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 行首时区毫秒时间戳归一化:
 * "[Asia/Shanghai]-1787812910686 " → "08-27 14:41:50.686 "。
 * 输出格式与 LogTimeFilter/LogExts.isTimeLine 期望的 "MM-dd HH:mm:ss.SSS" 行首契约一致。
 */
internal class TimestampNormalizer(
    private val fallbackZone: ZoneId = ZoneId.systemDefault(),
) : LogPostProcessor {

    override val requiredContentPrefix: String
        get() = "["

    // 单槽时区缓存：行首时区标签整文件恒同——百 MB 日志数百万行各自 ZoneId.of 字符串
    // 解析是解码热路径上的重复开销；跨行换标签时槽位失效重解析
    private var cachedZoneLabel: String? = null
    private var cachedZoneId: ZoneId = fallbackZone

    override fun processLine(line: String, context: SegmentContext): String {
        if (!line.startsWith("[")) return line
        val matcher = TIMEZONE_TIMESTAMP_PATTERN.matcher(line)
        if (!matcher.find()) return line
        val label = matcher.group(1)
        val zoneId = if (label == cachedZoneLabel) {
            cachedZoneId
        } else {
            // 按 [时区] 标签解析目标时区, 解析失败时回退到 [fallbackZone]
            val parsed = try {
                ZoneId.of(label)
            } catch (e: Exception) {
                fallbackZone
            }
            cachedZoneLabel = label
            cachedZoneId = parsed
            parsed
        }
        val epochMilli = matcher.group(2).toLong()
        val formatted = Instant.ofEpochMilli(epochMilli)
            .atZone(zoneId)
            .format(READABLE_TIME_FORMATTER)
        // 将 "[Asia/Shanghai]-1787812910686 " 替换为 "08-27 14:41:50.686 "
        return matcher.replaceFirst(Matcher.quoteReplacement("$formatted "))
    }

    companion object {
        // 匹配 "[Asia/Shanghai]-1787812910686 " 这类行首时区毫秒时间戳
        private val TIMEZONE_TIMESTAMP_PATTERN: Pattern = Pattern.compile("^\\[([^]]+)]-(\\d{13})\\s")
        // 归一化后的可读时间格式, 与 LogTimeFilter 期望的 "MM-dd HH:mm:ss.SSS" 一致
        private val READABLE_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}
