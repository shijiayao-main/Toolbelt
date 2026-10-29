package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 语义槽对对比度守护（WCAG 2.x 相对亮度公式，AA 普通文本 4.5:1 硬线）：
 * 四个语义槽（success/warning/info/errorFixed）的文字色对其容器色，深浅两态共 8 对
 * 全部实算断言——pill/横幅文字可读性在 token 单源处钉死，消费点漂移不再逐点复算。
 *
 * 落码凭据（python3 同公式实算）：旧暗值 successContainer #1B5E20 / warningContainer
 * #9A6B00 对各自文字色仅 3.91:1 / 2.88:1（不可读），加深至 #16421A / #6E4D00 后
 * 5.71:1 / 4.73:1；亮侧四对与暗侧 info/error 两对为存量 PASS 值，一并入守护防回退。
 */
class SlotContrastGuardTest {

    /** WCAG 2.x 相对亮度（sRGB 线性化） */
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(fg: Color, bg: Color): Double {
        val hi = maxOf(luminance(fg), luminance(bg))
        val lo = minOf(luminance(fg), luminance(bg))
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertPair(name: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue(
            ratio >= AA_TEXT_RATIO,
            "$name 槽对对比度 ${"%.2f".format(ratio)}:1 低于 AA 4.5:1 硬线——" +
                "调 token 须先重算全仓 pill 文字配对（core/theme 单源，改值守护同批）",
        )
    }

    @Test
    fun `all semantic slot pairs meet AA text contrast in light theme`() {
        val c = LightEasyToolboxColors
        assertPair("亮 success/successContainer", c.success, c.successContainer)
        assertPair("亮 warning/warningContainer", c.warning, c.warningContainer)
        assertPair("亮 info/infoContainer", c.info, c.infoContainer)
        assertPair("亮 errorFixed/errorContainerFixed", c.errorFixed, c.errorContainerFixed)
    }

    @Test
    fun `all semantic slot pairs meet AA text contrast in dark theme`() {
        val c = DarkEasyToolboxColors
        assertPair("暗 success/successContainer", c.success, c.successContainer)
        assertPair("暗 warning/warningContainer", c.warning, c.warningContainer)
        assertPair("暗 info/infoContainer", c.info, c.infoContainer)
        assertPair("暗 errorFixed/errorContainerFixed", c.errorFixed, c.errorContainerFixed)
    }

    private companion object {
        /** AA 普通文本硬线（pill/横幅文字字号 < 18pt，非 3:1 大字档） */
        const val AA_TEXT_RATIO = 4.5
    }
}
