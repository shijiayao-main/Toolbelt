package com.jiaoay.xlog.postprocess

import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimestampNormalizerTest {

    private val normalizer = TimestampNormalizer(
        fallbackZone = ZoneId.of("Asia/Shanghai")
    )
    private val context = SegmentContext(0, com.jiaoay.xlog.parser.MagicStartMark.NoCompressStart, 0, 8, 9)

    /** 1700000000000 = 2023-11-14 22:13:20 UTC = 2023-11-15 06:13:20 上海 */
    @Test
    fun `normalizes timezone timestamp with segment timezone`() {
        val line = "[Asia/Shanghai]-1700000000000 connect success"
        assertEquals("11-15 06:13:20.000 connect success", normalizer.processLine(line, context))
    }

    @Test
    fun `normalizes with non-utc timezone`() {
        // 2023-11-14 22:13:20 UTC = 2023-11-14 17:13:20 New_York(EST, UTC-5)
        val line = "[America/New_York]-1700000000000 log line"
        assertEquals("11-14 17:13:20.000 log line", normalizer.processLine(line, context))
    }

    @Test
    fun `invalid timezone falls back to injected zone`() {
        val line = "[Not/AZone]-1700000000000 log line"
        assertEquals("11-15 06:13:20.000 log line", normalizer.processLine(line, context))
    }

    @Test
    fun `non timestamp line passes through unchanged`() {
        val line = "[TAG] plain content"
        assertEquals(line, normalizer.processLine(line, context))
    }

    @Test
    fun `line not starting with bracket passes through`() {
        val line = "plain line [x]-123"
        assertEquals(line, normalizer.processLine(line, context))
    }

    @Test
    fun `12-digit epoch does not match`() {
        val line = "[Asia/Shanghai]-17000000000 too short"
        assertEquals(line, normalizer.processLine(line, context))
    }

    @Test
    fun `14-digit epoch does not match`() {
        val line = "[Asia/Shanghai]-17000000000000 too long"
        assertEquals(line, normalizer.processLine(line, context))
    }

    /** 输出格式契约: 行首必须能被 LogExts.isTimeLine 识别 */
    @Test
    fun `output line start matches LogTimeFilter contract`() {
        val line = "[Asia/Shanghai]-1700000000000 anything"
        val normalized = normalizer.processLine(line, context)
        assertTrue(
            Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}").containsMatchIn(normalized),
            "normalized line must start with MM-dd HH:mm:ss.SSS, was: $normalized"
        )
    }

    @Test
    fun `required content prefix is bracket`() {
        assertEquals("[", normalizer.requiredContentPrefix)
    }
}
