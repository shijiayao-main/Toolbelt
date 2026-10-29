package com.jiaoay.easy_dev_toolbox.page.timestamp

import com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [TimestampConverter] 区间差值与批量转换纯函数单测：固定 GMT+8 时区，
 * 期望值全部手算锁定（基准沿用 TimestampConverterTest：2025-09-02 13:20:00.000
 * +08:00 = 1756790400000 ms）。
 *
 * 混算口径：两端各自解析，可任意混搭输入形态（10/13 位纯数字、标准/ISO 日期
 * 时间、仅日期、日志格式「yyyy-MM-dd_HH:mm:ss(.SSS)」）——判定语义与单行/批量
 * 同源（:core Timestamps 单一真源，含下划线形态形状守卫「宁拒不猜」）。
 */
class TimestampConverterDiffBatchTest {

    companion object {
        init {
            // 与 TimestampConverterTest 同款时区锚：固定防环境漂
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+8"))
        }
    }

    // ── 区间差值：跨格式混算 ──

    @Test
    fun diffMixesSecondsTimestampAndIsoInput() {
        // 起始 = 10 位秒（1756790400），结束 = ISO 日期时间（晚 1 秒）
        val result = TimestampConverter.diff("1756790400", "2025-09-02T13:20:01.000")
        val diff = assertIs<DiffResult.Diff>(result)
        assertEquals(1000L, diff.millis)
        assertEquals("1.000 秒", diff.humanized)
    }

    @Test
    fun diffMixesStandardInputAndMillisTimestamp() {
        // 起始 = 标准日期时间，结束 = 13 位毫秒（晚 1.5 秒）
        val result = TimestampConverter.diff("2025-09-02 13:20:00.000", "1756790401500")
        val diff = assertIs<DiffResult.Diff>(result)
        assertEquals(1500L, diff.millis)
        assertEquals("1.500 秒", diff.humanized)
    }

    @Test
    fun diffAcceptsLogFormatUnderscoreInput() {
        // 日志格式「yyyy-MM-dd_HH:mm:ss.SSS」——三面同源放行（日志行复制时间戳主场景）
        val result = TimestampConverter.diff("2025-09-02_13:20:00.000", "2025-09-02_13:20:01.500")
        val diff = assertIs<DiffResult.Diff>(result)
        assertEquals(1500L, diff.millis)

        // 同形态与既有输入形态混算
        val mixed = TimestampConverter.diff("2025-09-02_13:20:00", "1756790402000")
        val mixedDiff = assertIs<DiffResult.Diff>(mixed)
        assertEquals(2000L, mixedDiff.millis)
    }

    @Test
    fun logFormatUnderscoreParsesFullTimeOnSingleAndBatchPaths() {
        // 日志格式「yyyy-MM-dd_HH:mm:ss.SSS」：单行解析出完整时刻——不得按仅日期
        // 前缀命中而静默丢弃时间部分得零点值；批量与单行严格一致故同语义
        val single = TimestampConverter.convert("2025-09-02_13:20:00.000", TimestampFormat.LOG.pattern)
        val fromDate = assertIs<ConversionResult.FromDate>(single)
        assertEquals(1756790400000L, fromDate.millis)

        val batch = TimestampConverter.convertBatch(
            "2025-09-02_13:20:00.000",
            TimestampFormat.LOG.pattern,
        )
        assertEquals(listOf(single), batch.map { it.result })
    }

    @Test
    fun underscoreIncompleteTimeRejectedOnSingleAndBatchPaths() {
        // 形状守卫（宁拒不猜）：时间部分不完整——明确拒绝，不落仅日期零点值；
        // 批量与单行严格一致
        val single = TimestampConverter.convert("2025-09-02_13:20", TimestampFormat.LOG.pattern)
        assertIs<ConversionResult.Invalid>(single)

        val batch = TimestampConverter.convertBatch(
            "2025-09-02_13:20",
            TimestampFormat.LOG.pattern,
        )
        assertEquals(listOf(single), batch.map { it.result })
    }

    @Test
    fun diffRejectsMalformedLogFormatInsteadOfMidnightFallback() {
        // 日志格式形态命中但时间越界：拒绝而非按仅日期前缀得零点假值
        val result = TimestampConverter.diff("2025-09-02_25:00:00", "1756790401000")
        val invalid = assertIs<DiffResult.Invalid>(result)
        assertTrue(invalid.message.startsWith("起始：无法识别的输入"), "实际：${invalid.message}")
    }

    @Test
    fun diffMixesDateOnlyAndStandardInput() {
        // 仅日期（零点）→ 当日 13:20:00 = 48,000,000 ms
        val result = TimestampConverter.diff("2025-09-02", "2025-09-02 13:20:00.000")
        val diff = assertIs<DiffResult.Diff>(result)
        assertEquals(48_000_000L, diff.millis)
        assertEquals("13 小时 20 分 0.000 秒", diff.humanized)
    }

    @Test
    fun diffHumanizesMultiUnitDurations() {
        // 3,723,456 ms = 1 小时 2 分 3.456 秒
        val base = 1_756_000_000_000L
        val result = TimestampConverter.diff(base.toString(), (base + 3_723_456).toString())
        val diff = assertIs<DiffResult.Diff>(result)
        assertEquals("1 小时 2 分 3.456 秒", diff.humanized)

        // 90,061,000 ms = 1 天 1 小时 1 分 1.000 秒
        val dayResult = TimestampConverter.diff(base.toString(), (base + 90_061_000).toString())
        assertEquals("1 天 1 小时 1 分 1.000 秒", assertIs<DiffResult.Diff>(dayResult).humanized)

        // 500 ms：秒段毫秒恒三位小数
        val subSecond = TimestampConverter.diff(base.toString(), (base + 500).toString())
        assertEquals("0.500 秒", assertIs<DiffResult.Diff>(subSecond).humanized)

        // 0：两端同值
        val zero = TimestampConverter.diff(base.toString(), base.toString())
        assertEquals("0.000 秒", assertIs<DiffResult.Diff>(zero).humanized)
    }

    @Test
    fun diffNegativeWhenEndBeforeStart() {
        // 结束早于起始 → 负差值（带符号人性化，页面据此提示方向）
        val result = TimestampConverter.diff("1756790401000", "2025-09-02 13:20:00.000")
        val diff = assertIs<DiffResult.Diff>(result)
        assertEquals(-1000L, diff.millis)
        assertEquals("-1.000 秒", diff.humanized)
    }

    @Test
    fun diffTrimsSurroundingWhitespace() {
        val result = TimestampConverter.diff("  1756790400  ", "  1756790401000 ")
        val diff = assertIs<DiffResult.Diff>(result)
        assertEquals(1000L, diff.millis)
    }

    // ── 区间差值：Validation 路径 ──

    @Test
    fun diffBothBlankIsEmpty() {
        for (start in listOf("", "   ")) {
            for (end in listOf("", "  ")) {
                assertIs<DiffResult.Empty>(TimestampConverter.diff(start, end))
            }
        }
    }

    @Test
    fun diffOneBlankSideIsInvalidWithHint() {
        val startBlank = TimestampConverter.diff("", "1756790400")
        val startInvalid = assertIs<DiffResult.Invalid>(startBlank)
        assertEquals("起始未输入——请输入时间戳或日期时间", startInvalid.message)

        val endBlank = TimestampConverter.diff("1756790400", "   ")
        assertEquals("结束未输入——请输入时间戳或日期时间", assertIs<DiffResult.Invalid>(endBlank).message)
    }

    @Test
    fun diffInvalidSideReusesSinglePathInvalidMessage() {
        // 垃圾输入：复用既有单行 Invalid 文案 + 端别前缀
        val garbage = TimestampConverter.diff("abc", "1756790400")
        val garbageInvalid = assertIs<DiffResult.Invalid>(garbage)
        assertTrue(garbageInvalid.message.startsWith("起始：无法识别的输入"), "实际：${garbageInvalid.message}")

        // 位数非法（9 位）：复用既有位数判定文案
        val wrongDigits = TimestampConverter.diff("1756790400", "175679040")
        val digitsInvalid = assertIs<DiffResult.Invalid>(wrongDigits)
        assertEquals("结束：仅支持 10 位秒或 13 位毫秒时间戳", digitsInvalid.message)
    }

    // ── 批量转换：逐行与单行一致 ──

    @Test
    fun batchPerLineMatchesSingleConversion() {
        val lines = listOf(
            "1756790400",
            "1756790400123",
            "2025-09-02 13:20:00.123",
            "2025-09-02T13:20:00",
            "2025-09-02",
            "abc",
            "175679040",
            "  1756790400123  ",
            "",
        )
        val input = lines.joinToString("\n")
        val batch = TimestampConverter.convertBatch(input, TimestampFormat.LOG.pattern)

        assertEquals(lines.size, batch.size, "行数与输出严格对齐（空行占位）")
        batch.forEachIndexed { index, line ->
            assertEquals(index + 1, line.number)
            assertEquals(lines[index], line.input)
            assertEquals(
                TimestampConverter.convert(lines[index], TimestampFormat.LOG.pattern),
                line.result,
                "第 ${index + 1} 行结果必须与单行转换完全一致",
            )
        }
    }

    @Test
    fun batchHandlesCrLfLineBreaks() {
        // Windows 换行按行分隔符正确切分（\r 不残留进行内容）；行尾换行产出末尾空行占位
        val batch = TimestampConverter.convertBatch("1756790400\r\nabc\r\n", TimestampFormat.LOG.pattern)
        assertEquals(3, batch.size)
        assertEquals("abc", batch[1].input)
        assertIs<ConversionResult.FromTimestamp>(batch[0].result)
        assertIs<ConversionResult.Invalid>(batch[1].result)
        assertIs<ConversionResult.Empty>(batch[2].result)
    }

    @Test
    fun batchOutputTextKeepsOneOutputLinePerInputLine() {
        val batch = TimestampConverter.convertBatch(
            "1756790400\n2025-09-02 13:20:00\nabc\n\n1756790400123",
            TimestampFormat.STANDARD.pattern,
        )
        val text = TimestampConverter.batchOutputText(batch)

        val outputLines = text.lines()
        assertEquals(batch.size, outputLines.size, "复制列行数 = 输入行数（失败/空行留空占位）")
        assertEquals("2025-09-02 13:20:00.000", outputLines[0], "时间戳行给格式化日期时间")
        assertEquals("1756790400000", outputLines[1], "日期时间行给毫秒")
        assertEquals("", outputLines[2], "失败行留空")
        assertEquals("", outputLines[3], "空行留空")
        assertEquals("2025-09-02 13:20:00.123", outputLines[4])
    }
}
