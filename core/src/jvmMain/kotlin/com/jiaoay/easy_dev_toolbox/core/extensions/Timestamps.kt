package com.jiaoay.easy_dev_toolbox.core.extensions

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间戳双向转换结果（四态）。
 *
 * - [Empty]：输入 trim 后为空（页面清空输出，不报错）
 * - [FromTimestamp]：纯数字时间戳输入，转出按当前输出 pattern 格式化的日期时间字符串
 * - [FromDate]：日期时间字符串输入，转出毫秒（主结果）与秒（附注）时间戳
 * - [Invalid]：无法识别的输入（错误提示文案）
 */
sealed class ConversionResult {
    data object Empty : ConversionResult()

    /** [millis] 规范化后的毫秒值；[formatted] 按输出 pattern 格式化的结果 */
    data class FromTimestamp(val millis: Long, val formatted: String) : ConversionResult()

    /** 主结果为毫秒时间戳 [millis]，附注秒级时间戳 [seconds] */
    data class FromDate(val millis: Long, val seconds: Long) : ConversionResult()

    data class Invalid(val message: String) : ConversionResult()
}

/**
 * 时间戳 <-> 日期时间 双向转换纯函数（无 Compose 依赖，供 ViewModel/agent 工具/单测共用；
 * 自 `feature-timestamp` 下沉 :core——agent 工具
 * `convert_timestamp` 复用同一实现，消除复制漂移；feature-timestamp 改薄委托，
 * 包内消费点零改动）。
 *
 * 判定优先级（顺序固定，可测试）：
 * 1. trim 后为空 → [ConversionResult.Empty]
 * 2. 纯数字（`\d+`）：
 *    - 13 位 → 毫秒时间戳；
 *    - 10 位 → 秒时间戳 ×1000 转毫秒（修复现状秒级误判毫秒导致 1970 附近日期的 bug）；
 *    - 其他位数 → [ConversionResult.Invalid]（宁拒不猜：9 位多为漏输入，错误提示比错误结果安全）。
 * 3. 非纯数字 → 依次尝试 [INPUT_PATTERNS] 输入格式（isLenient = false 严格解析，
 *    杜绝月日越界值滚动溢出）；任一成功 → [ConversionResult.FromDate]，全失败 → Invalid。
 *    日志格式「yyyy-MM-dd_HH:mm:ss(.SSS)」（日期与时间以下划线相连，从日志行
 *    直接复制所得形态）也在候选之列，且排于仅日期之前（前缀解析顺序）——
 *    单行转换 / 批量转换 / agent 工具 `convert_timestamp` 三个输入面同一语义：
 *    下划线时间戳解析出正确时刻而非零点值。
 *    形状守卫（宁拒不猜）：输入命中下划线日志形态但完整形态全部解析失败
 *    （时间部分不完整/非法）→ 明确 [ConversionResult.Invalid]，不回退仅日期
 *    前缀——前缀解析会静默丢弃时间部分得零点假值，静默错值比报错更危险。
 *
 * 时区：一律使用系统默认时区（延续现状行为）；ISO 输出 pattern `XXX` 自动带本地偏移。
 * formatter 在函数内局部创建（不复用字段，规避 SimpleDateFormat 与 Date 共享可变状态）。
 */
object Timestamps {

    private const val DATE_ONLY_PATTERN = "yyyy-MM-dd"

    /** 日期时间字符串输入的候选解析格式（依次尝试，命中即返回）；仅日期兜底排最末 */
    private val INPUT_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd_HH:mm:ss.SSS",
        "yyyy-MM-dd_HH:mm:ss",
        DATE_ONLY_PATTERN,
    )

    /** 下划线日志形态探测（日期_时间数字）：命中即触发形状守卫（见 [convert]） */
    private val UNDERSCORE_LOG_SHAPE = Regex("""\d{4}-\d{2}-\d{2}_\d""")

    private val DIGITS = Regex("\\d+")

    /**
     * 识别输入方向并转换：纯数字 → 时间戳转日期时间（按 [outputPattern] 格式化输出）；
     * 日期时间字符串 → 输出毫秒/秒时间戳。无法识别时返回 [ConversionResult.Invalid]。
     */
    fun convert(input: String, outputPattern: String): ConversionResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ConversionResult.Empty

        if (DIGITS.matches(trimmed)) {
            return when (trimmed.length) {
                13 -> {
                    val millis = trimmed.toLong()
                    ConversionResult.FromTimestamp(millis, format(millis, outputPattern))
                }

                10 -> {
                    val millis = trimmed.toLong() * 1000
                    ConversionResult.FromTimestamp(millis, format(millis, outputPattern))
                }

                else -> ConversionResult.Invalid("仅支持 10 位秒或 13 位毫秒时间戳")
            }
        }

        for (pattern in INPUT_PATTERNS) {
            if (pattern == DATE_ONLY_PATTERN && UNDERSCORE_LOG_SHAPE.containsMatchIn(trimmed)) {
                // 形状守卫：走到仅日期兜底仍带下划线日志形态 = 完整形态全部解析失败
                // （时间部分不完整/非法）。仅日期是前缀解析，放行会静默丢弃时间部分
                // 得零点假值——宁拒不猜
                break
            }
            // Locale.ROOT：解析/格式化数字恒 ASCII（ar 等区域本地化数字与日志检索/回填面失配）
            val parser = SimpleDateFormat(pattern, Locale.ROOT)
            parser.isLenient = false
            try {
                val millis = parser.parse(trimmed)?.time ?: continue
                return ConversionResult.FromDate(millis = millis, seconds = millis / 1000)
            } catch (_: ParseException) {
                // 尝试下一个候选格式
            }
        }
        // 错误文案四要素：说明 + 能接受的输入形态（修正指引）
        return ConversionResult.Invalid(
            "无法识别的输入——请输入纯数字时间戳（10/13 位）或日期时间" +
                "（标准「yyyy-MM-dd HH:mm:ss」/ 日志「yyyy-MM-dd_HH:mm:ss」格式）",
        )
    }

    private fun format(millis: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.ROOT).format(Date(millis))

    /**
     * 相对时间解读（纯函数）：距今 [nowMillis] 的差值渲染为人类可读文案。
     * 1 分钟内按秒、1 小时内按分钟、1 天内按小时、更长按天（去尾不四舍五入）；
     * 负差值渲染「来自未来」（时钟漂移/测试场景），语义明确不误报。
     */
    fun formatRelative(millis: Long, nowMillis: Long): String {
        val diff = millis - nowMillis
        if (diff >= 0) {
            val (value, unit) = relativeParts(diff)
            return "${value} ${unit}前"
        }
        val (value, unit) = relativeParts(-diff)
        return "${value} ${unit}后（来自未来）"
    }

    private fun relativeParts(diff: Long): Pair<Long, String> = when {
        diff < 60_000 -> diff / 1000 to "秒"
        diff < 3_600_000 -> diff / 60_000 to "分钟"
        diff < 86_400_000 -> diff / 3_600_000 to "小时"
        else -> diff / 86_400_000 to "天"
    }
}
