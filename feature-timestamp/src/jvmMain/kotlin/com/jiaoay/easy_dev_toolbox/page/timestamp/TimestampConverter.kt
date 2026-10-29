package com.jiaoay.easy_dev_toolbox.page.timestamp

import com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult
import com.jiaoay.easy_dev_toolbox.core.extensions.Timestamps
import java.util.Locale

/**
 * 时间戳→日期时间方向的输出格式预设。
 *
 * [LOG] 为默认值，保证对既有固定输出格式的向后兼容。
 */
enum class TimestampFormat(val displayName: String, val pattern: String) {
    /** 日志格式（既有默认） */
    LOG("日志", "yyyy-MM-dd_HH:mm:ss.SSS"),

    /** 通用可读格式 */
    STANDARD("标准", "yyyy-MM-dd HH:mm:ss.SSS"),

    /** ISO-8601 带本地时区偏移 */
    ISO("ISO-8601", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
}

/**
 * 区间差值结果（三态）。
 *
 * - [Empty]：两端 trim 后均空（页面清空结果区，不报错）
 * - [Diff]：[millis] = 结束 - 起始（带符号，结束早于起始为负），[humanized] 人性化文案
 * - [Invalid]：任一端无法解析（文案 = 端别前缀「起始/结束」+ 既有单行 Invalid 文案）
 */
sealed class DiffResult {
    data object Empty : DiffResult()

    /** 毫秒差值与人性化文案（负值文案带「-」前缀） */
    data class Diff(val millis: Long, val humanized: String) : DiffResult()

    data class Invalid(val message: String) : DiffResult()
}

/**
 * 批量转换的单行产物：[number] 为输入中的 1 起始行号（空行占位，保持行对齐），
 * [input] 原始行文本，[result] 与单行 [TimestampConverter.convert] 完全一致的结果。
 */
data class BatchLine(
    val number: Int,
    val input: String,
    val result: ConversionResult,
)

/**
 * 时间戳双向转换（薄委托 :core 的 [Timestamps]——
 * agent 工具 `convert_timestamp` 复用同一实现，消除复制漂移；本文件保留包内
 * 类型出口，消费点 import 零改动）。
 *
 * 判定语义/输入格式/时区行为见 [Timestamps] KDoc（单一真源）。
 */
object TimestampConverter {

    fun convert(input: String, outputPattern: String): ConversionResult =
        Timestamps.convert(input, outputPattern)

    fun formatRelative(millis: Long, nowMillis: Long): String =
        Timestamps.formatRelative(millis, nowMillis)

    /**
     * 区间差值：两端各自解析为毫秒后作差（结束 - 起始，带符号）。
     *
     * 混算口径：每端独立解析，可用不同输入形态混算——判定语义与单行/批量完全同源
     * （:core [Timestamps] 单一真源：10/13 位纯数字、标准/ISO 日期时间、仅日期、
     * 日志格式「yyyy-MM-dd_HH:mm:ss(.SSS)」及其形状守卫「宁拒不猜」）。
     * 日志行复制的时间戳是差值主场景；差值侧不设独立解析路径，防两套形态清单漂移。
     *
     * 一端为空且另一端非空 → [DiffResult.Invalid]（提示补输入，不静默按 0 起算）。
     */
    fun diff(start: String, end: String): DiffResult {
        val startTrimmed = start.trim()
        val endTrimmed = end.trim()
        if (startTrimmed.isEmpty() && endTrimmed.isEmpty()) return DiffResult.Empty
        if (startTrimmed.isEmpty()) {
            return DiffResult.Invalid("起始未输入——请输入时间戳或日期时间")
        }
        if (endTrimmed.isEmpty()) {
            return DiffResult.Invalid("结束未输入——请输入时间戳或日期时间")
        }
        val startMillis = parseSideMillis(startTrimmed)
            ?: return DiffResult.Invalid("起始：${unrecognizedMessage(startTrimmed)}")
        val endMillis = parseSideMillis(endTrimmed)
            ?: return DiffResult.Invalid("结束：${unrecognizedMessage(endTrimmed)}")
        val delta = endMillis - startMillis
        return DiffResult.Diff(delta, humanizeDuration(delta))
    }

    /**
     * 批量转换：多行输入逐行走既有单行 [convert]（每行结果与单行完全一致，含位数
     * 判定与 Invalid 文案）；[BatchLine.number] 1 起始、空行占位，输出与输入行严格对齐。
     */
    fun convertBatch(input: String, outputPattern: String): List<BatchLine> =
        input.lines().mapIndexed { index, line ->
            BatchLine(number = index + 1, input = line, result = convert(line, outputPattern))
        }

    /**
     * 批量结果的可复制文本列（对账回贴场景）：每输入行恰一行输出——时间戳输入行给
     * 格式化日期时间、日期时间输入行给毫秒；空行与无法识别行输出空行占位，
     * 与输入列保持行对齐（copy 产物行数恒等于输入行数）。
     */
    fun batchOutputText(batch: List<BatchLine>): String =
        batch.joinToString("\n") { line ->
            when (val result = line.result) {
                is ConversionResult.FromTimestamp -> result.formatted
                is ConversionResult.FromDate -> result.millis.toString()
                is ConversionResult.Empty, is ConversionResult.Invalid -> ""
            }
        }

    /** 差值单端解析为毫秒：完全委托单行 [convert]（同源语义，无独立形态清单）；
     *  全失败返回 null（文案由 [unrecognizedMessage] 补齐） */
    private fun parseSideMillis(trimmed: String): Long? =
        when (val result = convert(trimmed, TimestampFormat.LOG.pattern)) {
            is ConversionResult.FromTimestamp -> result.millis
            is ConversionResult.FromDate -> result.millis
            else -> null
        }

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /**
     * 单端解析失败文案：复用既有单行 Invalid 文案（Validation 路径单源）。
     * [convert] 对已到这里的非空输入只会返回 Invalid，else 分支为防御性兜底。
     */
    private fun unrecognizedMessage(trimmed: String): String =
        when (val result = convert(trimmed, TimestampFormat.LOG.pattern)) {
            is ConversionResult.Invalid -> result.message
            else -> "无法识别的输入——请输入纯数字时间戳（10/13 位）或日期时间"
        }

    /**
     * 毫秒时长人性化：天/小时/分 + 秒（毫秒恒保留三位小数），高位零单位省略，
     * 负值加「-」前缀；零值渲染「0.000 秒」。毫秒段用 Locale.ROOT 格式化防本地化数字变形。
     */
    private fun humanizeDuration(millis: Long): String {
        val sign = if (millis < 0) "-" else ""
        var rest = kotlin.math.abs(millis)
        val days = rest / DAY_MS
        rest %= DAY_MS
        val hours = rest / HOUR_MS
        rest %= HOUR_MS
        val minutes = rest / MINUTE_MS
        rest %= MINUTE_MS
        val seconds = rest / 1000
        val millisPart = rest % 1000
        return buildString {
            append(sign)
            if (days > 0) append("$days 天 ")
            if (hours > 0) append("$hours 小时 ")
            if (minutes > 0) append("$minutes 分 ")
            append(seconds)
            append('.')
            append(String.format(Locale.ROOT, "%03d", millisPart))
            append(" 秒")
        }
    }
}
