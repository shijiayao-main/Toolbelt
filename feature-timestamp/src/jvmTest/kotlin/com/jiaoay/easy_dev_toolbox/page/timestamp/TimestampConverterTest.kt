package com.jiaoay.easy_dev_toolbox.page.timestamp

import com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [TimestampConverter] 纯函数单测：固定 GMT+8 时区，期望值全部手算锁定。
 *
 * 基准：2025-09-02 13:20:00.000 +08:00 = 1756790400000 ms（秒级 1756790400）。
 */
class TimestampConverterTest {

    companion object {
        init {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"))
        }
    }

    // ── 时间戳 → 日期时间（10 位秒 / 13 位毫秒） ──

    @Test
    fun tenDigitsParsedAsSecondsNotMillenniumBug() {
        val result = TimestampConverter.convert("1756790400", TimestampFormat.LOG.pattern)
        val fromTs = assertIs<ConversionResult.FromTimestamp>(result)
        assertEquals(1756790400000L, fromTs.millis)
        assertEquals("2025-09-02_13:20:00.000", fromTs.formatted)
    }

    @Test
    fun thirteenDigitsParsedAsMillis() {
        val result = TimestampConverter.convert("1756790400123", TimestampFormat.STANDARD.pattern)
        val fromTs = assertIs<ConversionResult.FromTimestamp>(result)
        assertEquals(1756790400123L, fromTs.millis)
        assertEquals("2025-09-02 13:20:00.123", fromTs.formatted)
    }

    @Test
    fun isoPatternContainsLocalOffset() {
        val result = TimestampConverter.convert("1756790400000", TimestampFormat.ISO.pattern)
        val fromTs = assertIs<ConversionResult.FromTimestamp>(result)
        assertEquals("2025-09-02T13:20:00.000+08:00", fromTs.formatted)
    }

    @Test
    fun wrongDigitCountRejectedInsteadOfGuessing() {
        // 9 位（漏输入）/ 11 位 / 14 位均拒绝，宁拒不猜
        for (input in listOf("175679040", "17567904001", "17567904001234")) {
            val result = TimestampConverter.convert(input, TimestampFormat.LOG.pattern)
            val invalid = assertIs<ConversionResult.Invalid>(result)
            assertEquals("仅支持 10 位秒或 13 位毫秒时间戳", invalid.message)
        }
    }

    @Test
    fun surroundingWhitespaceTrimmed() {
        val result = TimestampConverter.convert("  1756790400  ", TimestampFormat.LOG.pattern)
        val fromTs = assertIs<ConversionResult.FromTimestamp>(result)
        assertEquals("2025-09-02_13:20:00.000", fromTs.formatted)
    }

    // ── 日期时间 → 时间戳（五种输入格式） ──

    @Test
    fun dateTimeWithMillis() {
        val result = TimestampConverter.convert("2025-09-02 13:20:00.123", TimestampFormat.LOG.pattern)
        val fromDate = assertIs<ConversionResult.FromDate>(result)
        assertEquals(1756790400123L, fromDate.millis)
        assertEquals(1756790400L, fromDate.seconds)
    }

    @Test
    fun dateTimeWithoutMillis() {
        val result = TimestampConverter.convert("2025-09-02 13:20:00", TimestampFormat.LOG.pattern)
        val fromDate = assertIs<ConversionResult.FromDate>(result)
        assertEquals(1756790400000L, fromDate.millis)
        assertEquals(1756790400L, fromDate.seconds)
    }

    @Test
    fun isoLikeInputWithMillis() {
        val result = TimestampConverter.convert("2025-09-02T13:20:00.123", TimestampFormat.LOG.pattern)
        val fromDate = assertIs<ConversionResult.FromDate>(result)
        assertEquals(1756790400123L, fromDate.millis)
    }

    @Test
    fun isoLikeInputWithoutMillis() {
        val result = TimestampConverter.convert("2025-09-02T13:20:00", TimestampFormat.LOG.pattern)
        val fromDate = assertIs<ConversionResult.FromDate>(result)
        assertEquals(1756790400000L, fromDate.millis)
    }

    @Test
    fun dateOnlyZeroPadded() {
        val result = TimestampConverter.convert("2025-09-02", TimestampFormat.LOG.pattern)
        val fromDate = assertIs<ConversionResult.FromDate>(result)
        assertEquals(1756742400000L, fromDate.millis)
        assertEquals(1756742400L, fromDate.seconds)
    }

    // ── 非法输入 ──

    @Test
    fun garbageRejected() {
        val result = TimestampConverter.convert("abc", TimestampFormat.LOG.pattern)
        val invalid = assertIs<ConversionResult.Invalid>(result)
        // 终验 P3：错误四要素补修正指引（前缀断言——指引尾部格式清单可演进）
        assertTrue(
            // 逐字文案锚
            invalid.message.startsWith("无法识别的输入"),
            "Invalid 文案须保留「无法识别的输入」前缀 + 修正指引（稿 :823 逐字），实际：${invalid.message}",
        )
        // 2026-10-04 TL-D3③ 重锚：修正指引随稿面文案改「（10/13 位）」——四要素语义保留
        assertTrue(
            "10/13 位" in invalid.message,
            "Invalid 文案必须带输入格式修正指引（终验 P3：错误四要素「现在能做什么」）",
        )
    }

    @Test
    fun lenientOverflowRejected() {
        // isLenient=false：13 月 45 日不允许滚动溢出成"下一年"
        val result = TimestampConverter.convert("2026-13-45 00:00:00", TimestampFormat.LOG.pattern)
        assertIs<ConversionResult.Invalid>(result)
    }

    @Test
    fun blankInputIsEmpty() {
        for (input in listOf("", "   ")) {
            assertIs<ConversionResult.Empty>(TimestampConverter.convert(input, TimestampFormat.LOG.pattern))
        }
    }
}
