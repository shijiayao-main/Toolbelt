package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 套级覆写通道的机制契约测试（可构造面）：onCard 三档回落语义、onSurfaceFaint/selectedContainer
 * 覆写位、语义扩展槽合并、consoleBg 解析链的基值腿与派生腿。
 *
 * sealed 继承限制下 colorSet 传参路径（pageBase 三槽直采/井槽分家/直配腿）不经本文件覆盖——
 * 由首个传参套落地时的全目录守护实跑承接（G13 域拆/G14 双轨退出/G16 按有参数就校验生效）。
 */
class ThemeSlotOverridesChannelTest {

    private companion object {
        val OVERRIDES = ThemeSlotOverrides(
            onCardStrong = Color(0xFFFFFFFF),
            onSurfaceFaint = Color(0xFF0A0028),
            selectedContainer = Color(0xFF09F955),
            aiAccent = Color(0xFF09F955),
            chartPalette = listOf(Color(0xFF112233)),
            platingGreen = Color(0xFF50B850),
        )
    }

    @Test
    fun `on card tiers fall back to onSurface family and honor overrides`() {
        // 无覆写套（回落语义，逐位 = 拆分前行为）
        val scheme = AppThemeSpec.CheckerEmerald.colorSet(dark = false)
        val fallback = toolboxSurfaceColors(scheme, dark = false, overrides = null)
        assertEquals(scheme.onSurface, fallback.onCardStrong, "无覆写套 onCardStrong 回落 onSurface")
        assertEquals(scheme.onSurfaceVariant, fallback.onCardMid, "无覆写套 onCardMid 回落 onSurfaceVariant")
        assertEquals(fallback.onSurfaceFaint, fallback.onCardFaint, "无覆写套 onCardFaint 回落 onSurfaceFaint 实效值")
        assertEquals(scheme.surfaceContainerHigh, fallback.selectedContainer, "无覆写套 selectedContainer 派生不变")
        // 覆写套（直配生效 + faint 直配传导 onCardFaint 回落链）
        val overridden = toolboxSurfaceColors(scheme, dark = false, overrides = OVERRIDES)
        assertEquals(Color(0xFFFFFFFF), overridden.onCardStrong, "onCardStrong 套级直配生效")
        assertEquals(Color(0xFF0A0028), overridden.onSurfaceFaint, "onSurfaceFaint 套级覆写生效（覆盖固定实色）")
        assertEquals(Color(0xFF0A0028), overridden.onCardFaint, "onCardFaint 回落对象 = onSurfaceFaint 实效值")
        assertEquals(Color(0xFF09F955), overridden.selectedContainer, "selectedContainer 套级覆写生效")
    }

    @Test
    fun `resolved console bg falls back through derivation then static base`() {
        // 基值腿：豁免/无染色套亮暗均回落静态基值（对拍锚 = 静态表本值）
        assertEquals(DarkEasyToolboxColors.consoleBg, resolvedConsoleBg(AppThemeSpec.Default, dark = true))
        assertEquals(LightEasyToolboxColors.consoleBg, resolvedConsoleBg(AppThemeSpec.Default, dark = false))
        // 派生腿：非豁免染色套暗态走骨架染色收敛（直配腿在覆写表之上，无套传参时不触发；
        // 样本套 = 带暗态覆写表但不直配 consoleBg 的非豁免染色套——派生腿与直配腿并存时的优先序）
        assertEquals(stainedDarkConsoleBg(AppThemeSpec.Matrix), resolvedConsoleBg(AppThemeSpec.Matrix, dark = true))
        assertEquals(LightEasyToolboxColors.consoleBg, resolvedConsoleBg(AppThemeSpec.Matrix, dark = false))
    }

    @Test
    fun `semantic slot overrides merge over static tables`() {
        val merged = LightEasyToolboxColors.withSlotOverrides(OVERRIDES)
        assertEquals(Color(0xFF09F955), merged.aiAccent, "aiAccent 套级覆写合并")
        assertEquals(listOf(Color(0xFF112233)), merged.chartPalette, "chartPalette 套级覆写合并")
        assertEquals(Color(0xFF50B850), merged.platingGreen, "platingGreen 套级覆写合并")
        assertEquals(LightEasyToolboxColors.consoleBg, merged.consoleBg, "consoleBg 不在合并位（单源解析走 resolvedConsoleBg）")
        assertSame(LightEasyToolboxColors, LightEasyToolboxColors.withSlotOverrides(null), "null 表 = 原样（零漂移）")
    }
}
