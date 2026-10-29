package com.jiaoay.easy_dev_toolbox.core.extensions

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [Timestamps] 纯函数单测：固定 GMT+8 时区，期望值全部手算锁定。
 *
 * 基准：2025-09-02 13:20:00.000 +08:00 = 1756790400000 ms（当日零点 = 1756742400000 ms）。
 */
class TimestampsTest {

    companion object {
        init {
            // 固定时区锚：防环境漂
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"))
        }
    }

    // ── 下划线日志格式：解析出正确时刻（不得截断时间部分） ──

    @Test
    fun underscoreLogFormatWithMillisParsesFullTime() {
        val result = Timestamps.convert("2025-09-02_13:20:00.123", "yyyy-MM-dd HH:mm:ss.SSS")
        val fromDate = assertIs<ConversionResult.FromDate>(result)
        assertEquals(1756790400123L, fromDate.millis)
        assertEquals(1756790400L, fromDate.seconds)
    }

    @Test
    fun underscoreLogFormatWithoutMillisParsesFullTime() {
        val result = Timestamps.convert("2025-09-02_13:20:00", "yyyy-MM-dd HH:mm:ss.SSS")
        val fromDate = assertIs<ConversionResult.FromDate>(result)
        assertEquals(1756790400000L, fromDate.millis)
    }

    // ── 形状守卫：不完整/非法时间明确拒绝，不落零点值 ──

    @Test
    fun underscoreLogFormatIncompleteTimeRejectedNotMidnight() {
        // 时间缺秒段：完整形态解析失败——拒绝而非按仅日期前缀得当日零点
        val result = Timestamps.convert("2025-09-02_13:20", "yyyy-MM-dd HH:mm:ss.SSS")
        val invalid = assertIs<ConversionResult.Invalid>(result)
        assertTrue(invalid.message.startsWith("无法识别的输入"), "实际：${invalid.message}")
    }

    @Test
    fun underscoreLogFormatHourOnlyRejectedNotMidnight() {
        // 仅小时段同样命中下划线形态——拒绝不猜
        assertIs<ConversionResult.Invalid>(
            Timestamps.convert("2025-09-02_15", "yyyy-MM-dd HH:mm:ss.SSS"),
        )
    }

    @Test
    fun underscoreLogFormatOutOfRangeTimeRejected() {
        // 小时越界：isLenient=false 下完整形态解析失败——拒绝而非回退零点
        assertIs<ConversionResult.Invalid>(
            Timestamps.convert("2025-09-02_25:00:00", "yyyy-MM-dd HH:mm:ss.SSS"),
        )
    }

    // ── 既有输入形态零回归 ──

    @Test
    fun dateOnlyStillParsesAsMidnight() {
        // 无下划线尾巴的纯日期输入维持零点语义（守卫不误伤）
        val result = Timestamps.convert("2025-09-02", "yyyy-MM-dd HH:mm:ss.SSS")
        val fromDate = assertIs<ConversionResult.FromDate>(result)
        assertEquals(1756742400000L, fromDate.millis)
    }

    @Test
    fun standardAndIsoFormsUnchanged() {
        val standard = Timestamps.convert("2025-09-02 13:20:00.123", "yyyy-MM-dd HH:mm:ss.SSS")
        assertEquals(1756790400123L, assertIs<ConversionResult.FromDate>(standard).millis)

        val iso = Timestamps.convert("2025-09-02T13:20:00", "yyyy-MM-dd HH:mm:ss.SSS")
        assertEquals(1756790400000L, assertIs<ConversionResult.FromDate>(iso).millis)
    }

    @Test
    fun digitInputsUnchanged() {
        val seconds = Timestamps.convert("1756790400", "yyyy-MM-dd HH:mm:ss.SSS")
        val fromTs = assertIs<ConversionResult.FromTimestamp>(seconds)
        assertEquals(1756790400000L, fromTs.millis)

        assertIs<ConversionResult.Invalid>(Timestamps.convert("175679040", "yyyy-MM-dd HH:mm:ss.SSS"))
        assertIs<ConversionResult.Empty>(Timestamps.convert("   ", "yyyy-MM-dd HH:mm:ss.SSS"))
    }
}
