package com.jiaoay.log

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 行首时间戳契约单源（isTimeLine 判定与 LogTimeFilter 解析共用同一正则，防两者漂移）。
 * 兼容两种行首格式：
 * - 裸格式 `MM-dd HH:mm:ss.SSS`（xlog 自产）：年份由调用方以目标时间锚定
 * - 年份格式 `yyyy-MM-dd HH:mm:ss.SSS`（外部解码器产物，如 alog_decoder.py）
 */
internal object LogTimestamp {

    private val LINE_TIME: Pattern =
        Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}|\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}\\.\\d{3})")

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss.SSS")

    /** 裸日期目标形态（`MM-dd_` 前缀）——parseTarget 无年份锚定判据 */
    private val BARE_DATE_HEAD: Pattern = Pattern.compile("^\\d{2}-\\d{2}_")

    /** 行首是否为可识别的时间戳（仅判定形态；数值合法性由 [parseLine] 兜底） */
    fun isTimeLine(line: String): Boolean = LINE_TIME.matcher(line).find()

    /**
     * 解析行首时间戳为 [LocalDateTime]。
     * @return 形态不命中或数值非法（如 99 月）返回 null——调用方按解析失败处理
     */
    fun parseLine(line: String, fallbackYear: String): LocalDateTime? {
        val matcher = LINE_TIME.matcher(line)
        if (!matcher.find()) return null
        val date = matcher.group(1)
        val dated = if (date.length == 10) date else "$fallbackYear-$date"
        return runCatching { LocalDateTime.parse("${dated}_${matcher.group(2)}", FORMATTER) }.getOrNull()
    }

    /**
     * 把行首时间戳（[LINE_TIME] 契约的两种格式）整体替换为占位符；行首无时间戳原样返回。
     * 消费方：日志 diff 的「时间戳归一化对齐」——比对前抹去行首时钟差异，
     * 仅时间戳不同的行视为相等；不做数值合法性校验（形态命中即替换，与 [isTimeLine] 同口径）。
     */
    fun maskLineTimestamp(line: String, placeholder: String): String {
        val matcher = LINE_TIME.matcher(line)
        if (!matcher.find()) return line
        return matcher.replaceFirst(Matcher.quoteReplacement(placeholder))
    }

    /**
     * 解析过滤目标时间。除规范格式 `yyyy-MM-dd_HH:mm:ss.SSS` 外容忍：
     * 空格分隔（`yyyy-MM-dd HH:mm:ss.SSS`）、省略毫秒（自动补 `.000`）与
     * 裸日期 `MM-dd HH:mm:ss.SSS`（xlog 自产行首同款——无年份，以当前年锚定
     * 补全；比较侧（nextTimestampLine / LogTimeFilter）以目标年份重新锚定
     * 行时间戳，故锚定年份取值不影响单格式文件内的相对先后）。
     * 裸形态仅对裸格式日志保证正确：跨年段裸文件相对先后在边界处倒置，区间
     * 可能被拒（end<start）或头部多收；年格式文件裸目标恒不命中（转零输出
     * 守护拒绝）。不命中返回 null——宁拒不猜，调用方按格式错回执。
     */
    fun parseTarget(target: String): LocalDateTime? {
        val normalized = target.trim().replace(' ', '_')
        val withMillis = if (normalized.contains('.')) normalized else "$normalized.000"
        val anchored = if (BARE_DATE_HEAD.matcher(withMillis).lookingAt()) {
            "${java.time.Year.now().value}-$withMillis"
        } else {
            withMillis
        }
        return runCatching { LocalDateTime.parse(anchored, FORMATTER) }.getOrNull()
    }
}
