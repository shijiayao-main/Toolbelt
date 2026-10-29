package com.jiaoay.easy_dev_toolbox.page.tools.ulid

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ULID 引擎验收：公开规范（GitHub `ulid/spec` README）示例向量断言时间戳反解
 * 与随机部分展开；确定性编码用注入字节做往返；非法字母（规格字母表排除
 * I/L/O/U）/连字符/长度/越界逐条显式拒绝；生成唯一性冒烟。
 *
 * 时间戳期望值由规格向量独立换算（Crockford Base32 逐字符 5 位累乘）得出，
 * 与引擎实现不共源。
 */
class UlidEngineTest {

    private val zone = ZoneId.of("UTC")

    /** 规范 README 首页示例（`ulid()` 返回值）——时间戳 1469922850259 ms */
    private val specSample = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

    /** 规范 README 单调性示例三连（同一毫秒内随机部分 +1——顺序性向量） */
    private val monotonicTrio = listOf(
        "01BX5ZZKBKACTAV9WEVGEMMVRY",
        "01BX5ZZKBKACTAV9WEVGEMMVRZ",
        "01BX5ZZKBKACTAV9WEVGEMMVS0",
    )

    private fun assertOk(input: String): UlidParseResult.Ok {
        val result = parseUlid(input, zone)
        assertTrue(result is UlidParseResult.Ok, "「$input」必须解析成功：$result")
        return result as UlidParseResult.Ok
    }

    private fun assertInvalid(input: String, vararg expectedInReason: String): UlidParseResult.Invalid {
        val result = parseUlid(input, zone)
        assertTrue(result is UlidParseResult.Invalid, "「$input」必须显式拒绝：$result")
        val invalid = result as UlidParseResult.Invalid
        expectedInReason.forEach {
            assertTrue(it in invalid.reason, "回执须含「$it」：${invalid.reason}")
        }
        return invalid
    }

    // ── 公开规范向量：时间戳反解 ──

    @Test
    fun `parses spec sample into embedded timestamp and human time`() {
        val ok = assertOk(specSample)
        assertEquals(1_469_922_850_259L, ok.timestampMillis, "规范示例内嵌毫秒时间戳")
        assertEquals("2016-07-30 23:54:10.259（UTC）", ok.humanTime, "时间戳反解人类时间（UTC 定点，毫秒粒度）")
        assertEquals("d6764c61efb99302bd5b", ok.randomHex, "80 位随机部分 hex 展开（20 位小写）")
        assertEquals(specSample, ok.canonical, "标准形态原样（大写输入）")
    }

    @Test
    fun `parses spec monotonic trio sharing one timestamp in lexical order`() {
        val results = monotonicTrio.map { assertOk(it) }
        results.forEach {
            assertEquals(1_508_808_576_371L, it.timestampMillis, "同一毫秒的时间戳部分恒同（规范单调性示例）")
        }
        // 规范单调性 = 同毫秒随机部分最低位 +1 进位：hex 值随字符串字典序递增
        val hexes = results.map { it.randomHex }
        assertTrue(hexes == hexes.sorted(), "随机部分 hex 随字典序递增：$hexes")
        assertEquals("5334ada78edc1d4a6f1e", hexes[0], "VRY 向量随机 hex")
        assertEquals("5334ada78edc1d4a6f20", hexes[2], "VS0 向量随机 hex（跨 Z 进位）")
    }

    @Test
    fun `parses all-zero and max ulids at boundary values`() {
        val zero = assertOk("00000000000000000000000000")
        assertEquals(0L, zero.timestampMillis, "全零 ULID 时间戳 = 0")
        assertEquals("1970-01-01 00:00:00.000（UTC）", zero.humanTime, "epoch 零点人类时间展开")
        assertEquals("00".repeat(10), zero.randomHex, "全零随机部分")

        // 规范最大合法 ULID：2^48-1 毫秒边界值须被接受
        val max = assertOk("7ZZZZZZZZZZZZZZZZZZZZZZZZZ")
        assertEquals(MAX_ULID_TIMESTAMP_MILLIS, max.timestampMillis, "最大合法时间戳 = 2^48 - 1")
        assertEquals("ff".repeat(10), max.randomHex, "最大随机部分 = 2^80 - 1")
    }

    // ── 大小写与空白 ──

    @Test
    fun `lowercase input parses case-insensitively into uppercase canonical`() {
        val ok = assertOk(specSample.lowercase())
        assertEquals(1_469_922_850_259L, ok.timestampMillis, "小写形态同值（规范 Case insensitive 口径）")
        assertEquals(specSample, ok.canonical, "canonical 恒大写标准形态")
    }

    @Test
    fun `blank input yields guide state instead of error`() {
        assertEquals(UlidParseResult.Empty, parseUlid("", zone), "空串回引导态")
        assertEquals(UlidParseResult.Empty, parseUlid("   ", zone), "纯空白回引导态")
        // 首尾空白容差：粘贴带尾随换行/空格是常态
        val ok = assertOk(" $specSample\n")
        assertEquals(1_469_922_850_259L, ok.timestampMillis, "首尾空白 trim 后照常解析")
    }

    // ── 非法输入显式拒绝 ──

    @Test
    fun `excluded crockford letters are rejected with explicit reason`() {
        // 规格字母表排除 I/L/O/U（防混淆）——大小写两形皆非法且回执逐字符定位
        assertInvalid(specSample.dropLast(1) + "I", "第 26 位", "I/L/O/U")
        assertInvalid(specSample.dropLast(1) + "l", "第 26 位", "I/L/O/U")
        assertInvalid(specSample.dropLast(1) + "O", "第 26 位", "I/L/O/U")
        assertInvalid(specSample.dropLast(1) + "u", "第 26 位", "I/L/O/U")
        // 时间戳段的排除字母同样定位到字符位（第 7 位 = 0 基下标 6，落在前 10 位时间戳内）
        assertInvalid("01ARZ3LDEKTSV4RRFFQ69G5FAV", "第 7 位", "I/L/O/U")
    }

    @Test
    fun `hyphenated form is rejected as non standard`() {
        assertInvalid("01ARZ3NDEK-TSV4RRFFQ69G5FAV", "连字符", "26 位无分隔")
    }

    @Test
    fun `wrong length and foreign characters are rejected with position`() {
        assertInvalid(specSample.dropLast(1), "26 位", "25 位")
        assertInvalid("${specSample}0", "26 位", "27 位")
        assertInvalid("01ARZ3NDEKTSV4RRFFQ69G5FA!", "第 26 位", "不在 ULID 字母表")
    }

    @Test
    fun `timestamp overflow beyond 48 bits is rejected`() {
        // 首字符 > 7 即时间戳超出 2^48-1——规范要求所有实现拒绝越界值
        assertInvalid("8000000000000000000000000" + "0", "48 位上限", "7ZZZZZZZZZZZZZZZZZZZZZZZZZ")
    }

    // ── 确定性编码与往返 ──

    @Test
    fun `encode deterministically maps injected bytes to canonical vectors`() {
        assertEquals(
            "00000000000000000000000000",
            encodeUlid(0L, ByteArray(10)),
            "零时间戳 + 零随机 = 全零 ULID",
        )
        assertEquals(
            "7ZZZZZZZZZZZZZZZZZZZZZZZZZ",
            encodeUlid(MAX_ULID_TIMESTAMP_MILLIS, ByteArray(10) { 0xFF.toByte() }),
            "最大时间戳 + 全 1 随机 = 最大合法 ULID",
        )
    }

    @Test
    fun `encode round trips through parse back to the injected values`() {
        val randomBytes = byteArrayOf(
            0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte(), 0xde.toByte(), 0x0f, 0x1e, 0x2d,
        )
        val encoded = encodeUlid(1_234_567_890_123L, randomBytes)
        val ok = assertOk(encoded)
        assertEquals(1_234_567_890_123L, ok.timestampMillis, "往返时间戳一致")
        assertEquals("123456789abcde0f1e2d", ok.randomHex, "往返随机部分一致")
        assertEquals(encoded, ok.canonical, "往返标准形态一致")
    }

    @Test
    fun `encode rejects out of contract injections`() {
        assertFailsWith<IllegalArgumentException>("负时间戳须 require 拒绝") {
            encodeUlid(-1L, ByteArray(10))
        }
        assertFailsWith<IllegalArgumentException>("超 48 位时间戳须 require 拒绝") {
            encodeUlid(MAX_ULID_TIMESTAMP_MILLIS + 1, ByteArray(10))
        }
        assertFailsWith<IllegalArgumentException>("随机字节数 ≠ 10 须 require 拒绝") {
            encodeUlid(0L, ByteArray(9))
        }
    }

    // ── 生成 ──

    @Test
    fun `generated batch is unique well formed and timestamped`() {
        val fixed = 1_500_000_000_000L
        val generated = generateUlids(100, fixed)
        assertEquals(100, generated.size, "批量数量")
        assertEquals(100, generated.toSet().size, "生成唯一性冒烟（80 位随机碰撞概率可忽略）")
        generated.forEach {
            assertEquals(26, it.length, "标准 26 位形态")
            assertTrue(it.all { c -> c in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" }, "恒大写 Crockford 字母表：$it")
            val ok = assertOk(it)
            assertEquals(fixed, ok.timestampMillis, "注入时间戳进每个生成值")
        }
    }

    @Test
    fun `generation count is bounded by hard limit`() {
        assertFailsWith<IllegalArgumentException>("零数量须 require 拒绝") {
            generateUlids(0)
        }
        assertFailsWith<IllegalArgumentException>("超上限数量须 require 拒绝") {
            generateUlids(MAX_ULID_GENERATE_COUNT + 1)
        }
    }
}
