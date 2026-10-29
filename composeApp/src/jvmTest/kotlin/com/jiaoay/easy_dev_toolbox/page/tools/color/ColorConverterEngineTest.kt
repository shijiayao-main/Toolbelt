package com.jiaoay.easy_dev_toolbox.page.tools.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 颜色三向转换验收：以 `0xFF1B6EF3` ↔ `#FF1B6EF3` ↔ `(27, 110, 243)` 为基准向量
 * 做三向往返，边界档（全 0 / 全 FF / alpha 非 FF）与各解析入口的失败面逐条
 * 人话回执断言。
 */
class ColorConverterEngineTest {

    // ── 基准向量三向一致 ──

    @Test
    fun `argb int hex and rgb triple agree on base vector`() {
        val byInt = parseArgbIntText("0xFF1B6EF3")
        val byHex = parseHexText("#FF1B6EF3")
        val byRgb = parseRgbText("(27, 110, 243)")
        assertTrue(byInt is ColorParseResult.Ok && byInt.components.red == 27 && byInt.components.green == 110 && byInt.components.blue == 243 && byInt.components.alpha == 255, "0xFF1B6EF3 四分量：${(byInt as ColorParseResult.Ok).components}")
        assertEquals((byInt as ColorParseResult.Ok).components, (byHex as ColorParseResult.Ok).components, "int ↔ #hex 同色")
        assertEquals(byHex.components, (byRgb as ColorParseResult.Ok).components, "#hex ↔ (r,g,b) 同色（RGB 三分量 alpha 默认 FF）")
    }

    @Test
    fun `format outputs match base vector`() {
        val components = ColorComponents(alpha = 255, red = 27, green = 110, blue = 243)
        assertEquals("0xFF1B6EF3", toHexLiteral(components), "代码字面量口径")
        assertEquals("#FF1B6EF3", toHexHash(components), "设计稿口径")
        assertEquals("(27, 110, 243)", toRgbTriple(components), "RGB 三分量口径")
        assertEquals("(255, 27, 110, 243)", toArgbTriple(components), "ARGB 四分量口径")
        assertEquals(0xFF1B6EF3.toInt(), toSignedInt(components), "logcat 带符号 int 口径")
    }

    // ── 输入口径覆盖 ──

    @Test
    fun `argb int accepts hex prefixes hex letters and signed decimal`() {
        // 0xFF1B6EF3 的带符号 int = -14979341；无符号十进制 = 4279987955
        val expected = ColorComponents(alpha = 255, red = 27, green = 110, blue = 243)
        for (input in listOf("0xFF1B6EF3", "0xff1b6ef3", "#FF1B6EF3", "FF1B6EF3", "-14979341", "4279987955")) {
            val parsed = parseArgbIntText(input)
            assertTrue(parsed is ColorParseResult.Ok, "「$input」应解析成功：$parsed")
            assertEquals(expected, (parsed as ColorParseResult.Ok).components, "「$input」与基准向量同色")
        }
    }

    @Test
    fun `hex accepts 6 digit with implicit ff alpha and case insensitive`() {
        val six = parseHexText("#1b6ef3")
        assertTrue(six is ColorParseResult.Ok && six.components.alpha == 255 && six.components.red == 27, "6 位 hex alpha 默认 FF（大小写不敏感）")
        val bare = parseHexText("FF1B6EF3")
        assertTrue(bare is ColorParseResult.Ok && bare.components.blue == 243, "# 前缀可省")
    }

    @Test
    fun `rgb accepts brackets spaces and four component alpha`() {
        val bracketless = parseRgbText("27, 110, 243")
        assertTrue(bracketless is ColorParseResult.Ok && bracketless.components.alpha == 255, "括号可省 + alpha 默认 FF")
        val four = parseRgbText("(200, 27, 110, 243)")
        assertTrue(four is ColorParseResult.Ok && four.components.alpha == 200, "四分量形态 a 在首位")
    }

    // ── 边界档 ──

    @Test
    fun `boundary colors round trip`() {
        val black = ColorComponents(alpha = 0, red = 0, green = 0, blue = 0)
        assertEquals("0x00000000", toHexLiteral(black), "全 0 边界")
        assertEquals(0, toSignedInt(black))
        val white = ColorComponents(alpha = 255, red = 255, green = 255, blue = 255)
        assertEquals("0xFFFFFFFF", toHexLiteral(white), "全 FF 边界")
        assertEquals(-1, toSignedInt(white), "全 FF 带符号 int = -1（logcat 口径）")
        val translucent = ColorComponents(alpha = 128, red = 27, green = 110, blue = 243)
        assertEquals("0x801B6EF3", toHexLiteral(translucent), "alpha 非 FF 档")
        assertEquals("(128, 27, 110, 243)", toArgbTriple(translucent))
        // 带符号负数 → 唯一往返：解析回同分量
        val negative = parseArgbIntText(toSignedInt(translucent).toString())
        assertTrue(negative is ColorParseResult.Ok && negative.components == translucent, "带符号 int 文本往返无损")
    }

    // ── 失败面人话回执 ──

    @Test
    fun `invalid inputs get explicit human readable reasons`() {
        val hexBadChar = parseHexText("#1B6EF3G")
        assertTrue(hexBadChar is ColorParseResult.Invalid && hexBadChar.reason.contains("十六进制"), "hex 非法字符回执说清合法字符集")

        val hexBadLength = parseHexText("#1B6EF")
        assertTrue(hexBadLength is ColorParseResult.Invalid && hexBadLength.reason.contains("6 位") && hexBadLength.reason.contains("8 位"), "hex 长度回执说清合法长度")

        val rgbOutOfRange = parseRgbText("(27, 110, 300)")
        assertTrue(rgbOutOfRange is ColorParseResult.Invalid && rgbOutOfRange.reason.contains("越界"), "RGB 分量越界回执定位到分量")

        val rgbBadCount = parseRgbText("(27, 110)")
        assertTrue(rgbBadCount is ColorParseResult.Invalid && rgbBadCount.reason.contains("分量数"), "分量数不足回执")

        val intOutOfRange = parseArgbIntText("99999999999")
        assertTrue(intOutOfRange is ColorParseResult.Invalid && intOutOfRange.reason.contains("值域"), "int 超值域回执")

        val intGarbage = parseArgbIntText("12g45")
        assertTrue(intGarbage is ColorParseResult.Invalid, "非十六进制字母且非纯数字的串不得猜口径")

        val hexPrefixGarbage = parseArgbIntText("0xZZ")
        assertTrue(hexPrefixGarbage is ColorParseResult.Invalid, "0x 前缀后非十六进制字符显式拒绝")
    }

    @Test
    fun `blank inputs return empty guidance state`() {
        for (input in listOf("", "   ")) {
            assertEquals(ColorParseResult.Empty, parseArgbIntText(input), "ARGB int 空白回 Empty")
            assertEquals(ColorParseResult.Empty, parseHexText(input), "hex 空白回 Empty")
            assertEquals(ColorParseResult.Empty, parseRgbText(input), "RGB 空白回 Empty")
        }
    }
}
