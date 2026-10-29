package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 三块构成样张取色守护（底/第二声部/强调，取色契约见 [AppThemeSpec.specimenTriad]）：
 * - 亮/暗两态全预设三块逐位互异——非涂装套第一触点「并块零信息量」回潮即红；
 * - 亮态相邻块（底|声部、声部|强调）眯眼可辨——同源近亲 tertiary 未经判据直取回潮即红；
 *   暗态锁 底|声部（刻意单色相暗态的声部|强调不锁，见取色契约「状态域」节）；
 * - 两态样张底逐位互异——dark 参数被忽略、双态样张退回单亮条（暗态主权套的暗态身份
 *   在橱窗零像素在场）即红；
 * - 抽验快照锚：样张底吃 pageBaseLight/pageBaseDark 主权漆；tertiarySeed 声明套的强调
 *   取 tertiaryContainer 实值；单声部套的强调不落与 secondary 同源的派生 tertiary。
 */
class ThemeSpecimenTriadGuardTest {

    @Test
    fun `specimen triad blocks are pairwise distinct for every preset in both modes`() {
        for (dark in listOf(false, true)) {
            AppThemeSpec.presets.forEach { spec ->
                val triad = spec.specimenTriad(dark = dark)
                assertNotEquals(triad.base, triad.secondVoice, "${spec.id}(dark=$dark): 底与第二声部逐位同色")
                assertNotEquals(triad.secondVoice, triad.accent, "${spec.id}(dark=$dark): 第二声部与强调逐位同色")
                assertNotEquals(triad.base, triad.accent, "${spec.id}(dark=$dark): 底与强调逐位同色")
            }
        }
    }

    @Test
    fun `specimen light adjacent blocks stay squint separable`() {
        AppThemeSpec.presets.forEach { spec ->
            val triad = spec.specimenTriad(dark = false)
            assertTrue(
                isSquintSeparable(triad.base, triad.secondVoice),
                "${spec.id}(dark=false): 底|第二声部相邻块眯眼不可辨（${triad.base}/${triad.secondVoice}）",
            )
            assertTrue(
                isSquintSeparable(triad.secondVoice, triad.accent),
                "${spec.id}(dark=false): 第二声部|强调相邻块眯眼不可辨（${triad.secondVoice}/${triad.accent}）",
            )
        }
    }

    @Test
    fun `specimen dark base and voice stay squint separable`() {
        // 暗态可辨域 = 底|声部（暗底近黑对声部中高 tone，结构性分离）；声部|强调不锁——
        // 刻意单色相的暗态（如 绯红夜城 夜幕派暗态全绯红族）声部/强调/primaryContainer 同相
        // tone 距可 <10，逐位互异 + 暗底承载分离（取色契约「眯眼可辨判据的状态域」节）
        AppThemeSpec.presets.forEach { spec ->
            val triad = spec.specimenTriad(dark = true)
            assertTrue(
                isSquintSeparable(triad.base, triad.secondVoice),
                "${spec.id}(dark=true): 底|第二声部相邻块眯眼不可辨（${triad.base}/${triad.secondVoice}）",
            )
        }
    }

    @Test
    fun `specimen dark base differs from light base for every preset`() {
        AppThemeSpec.presets.forEach { spec ->
            val light = spec.specimenTriad(dark = false)
            val dark = spec.specimenTriad(dark = true)
            assertNotEquals(
                light.base,
                dark.base,
                "${spec.id}: 暗态样张底与亮态逐位同色（dark 参数被忽略 = 双态样张退回单亮条）",
            )
        }
    }

    @Test
    fun `specimen base honors page base sovereignty in both modes`() {
        // 电光紫 昼战横幅本漆 #9946FA / 夜战装甲本漆 #5940A6 直采（colorSet
        // pageBaseLight/pageBaseDark 主权链）——样张底落回派生纸即主权链断裂
        assertEquals(Color(0xFF9946FA), AppThemeSpec.ElectricViolet.specimenTriad(dark = false).base)
        assertEquals(Color(0xFF5940A6), AppThemeSpec.ElectricViolet.specimenTriad(dark = true).base)
    }

    @Test
    fun `specimen accent uses declared tertiary container`() {
        // tertiarySeed 声明套的强调 = tertiaryContainer 实值（电光紫 徽章橙域 #F07830）
        assertEquals(Color(0xFFF07830), AppThemeSpec.ElectricViolet.specimenTriad().accent)
    }

    @Test
    fun `specimen accent never takes near twin tertiary of single voice presets`() {
        // 单声部套抽验：Gruvbox 派生 tertiary（#626200）与 secondary 逐位同色，
        // 强调必须落到候选链下一档 primaryContainer 锈橙 #C35100——苔绿近亲回潮即红
        val gruvbox = AppThemeSpec.GruvboxWarmPaper.specimenTriad()
        assertEquals(Color(0xFFC35100), gruvbox.accent)
        assertNotEquals(gruvbox.secondVoice, gruvbox.accent)
    }
}
