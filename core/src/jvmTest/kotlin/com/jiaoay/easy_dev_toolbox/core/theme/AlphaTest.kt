package com.jiaoay.easy_dev_toolbox.core.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Alpha token 常量断言（visual-polish design D2）：取值被误改即测试失败，
 * 作为透明度命名收口（token 值 = 现状 21 处 copy(alpha) 精确值、近值不归并）的视觉回归防线。
 */
class AlphaTest {

    @Test
    fun `alpha tokens equal current literal values`() {
        assertEquals(0.3f, AppAlpha.bgSubtle, "弱背景应为 0.3")
        assertEquals(0.35f, AppAlpha.bgSubtleAlt, "次弱背景应为 0.35")
        assertEquals(0.38f, AppAlpha.contentDisabled, "禁用态内容应为 0.38")
        assertEquals(0.4f, AppAlpha.borderFaint, "弱描边应为 0.4")
        assertEquals(0.5f, AppAlpha.bgTask, "任务卡背景应为 0.5")
        assertEquals(0.5f, AppAlpha.scrimSoft, "浮层遮罩应为 0.5（Compact 侧栏浮层，语义独立于 bgTask）")
        assertEquals(0.6f, AppAlpha.contentFaint, "弱内容（背景/装饰档）应为 0.6")
        assertEquals(0.7f, AppAlpha.contentSoft, "软内容应为 0.7")
    }

    // ── R5 对比度批（2026-09-25）：弱内容文字档明暗分治（暗 0.72/亮 0.8）──
    // 旧恒 0.6 档暗色 4.48:1 / 浅色 2.94:1 均低于 WCAG AA 4.5:1（GLM 20 页走查 12 页
    // 复现弱字不可读 + PIL 量化）；分治后暗 5.9:1 / 亮 4.7:1。被误改即全线弱字回潮。

    @Test
    fun `content faint text tiers follow light-dark contrast split`() {
        // 2026-10-03 对稿重锚（DS 对拍）：稿 tokens.css --on-surface-faint 实色
        // #5B6070（亮 5.95:1）/ #8A90A3（暗 5.99:1）双面达 AA——反解 alpha
        // 亮 onSurface×0.72 / 暗 onSurfaceVariant×0.89。旧 0.72 暗档实算 4.04~4.31:1
        // 不达 AA（旧 KDoc「=5.9:1」宣称与实算不符，DS 对拍定罪）。
        assertEquals(0.89f, AppAlpha.contentFaintTextDark, "弱内容·文字·暗档 0.89（稿 #8A90A3 反解，5.99:1 ≥ AA）")
        assertEquals(0.72f, AppAlpha.contentFaintTextLight, "弱内容·文字·亮档 0.72（稿 #5B6070 反解，5.95:1 ≥ AA）")
        // 文字档必须亮于背景/装饰档（0.6）——归并回 0.6 即 AA 失守
        assertNotEquals(AppAlpha.contentFaint, AppAlpha.contentFaintTextDark, "文字档与背景档必须分离")
        assertNotEquals(AppAlpha.contentFaint, AppAlpha.contentFaintTextLight, "文字档与背景档必须分离")
    }

    @Test
    fun `nearby alphas stay unmerged`() {
        // 近值不归并（design D2）：0.3 / 0.35 / 0.4 保持独立三档，归并会引入观感漂移
        assertNotEquals(AppAlpha.bgSubtle, AppAlpha.bgSubtleAlt, "0.3 与 0.35 必须保持独立 token")
        assertNotEquals(AppAlpha.bgSubtleAlt, AppAlpha.borderFaint, "0.35 与 0.4 必须保持独立 token")
    }

    // ── 2026-09-23 液态玻璃批 1：材质透明度档 ──
    // R2 材质诚实化（2026-09-25）：chrome/panel 玻璃档退役（恒实底）——
    // glassTintChrome*/glassTintPanel*/glassScrim 断言随常量删除。

    @Test
    fun `glass tint tiers follow light-opaque principle`() {
        assertEquals(0.72f, AppAlpha.glassTintSheetDark, "暗 sheet tint 0.72")
        assertEquals(0.92f, AppAlpha.glassTintSheetLight, "亮 sheet tint 0.92")
        assertEquals(0.85f, AppAlpha.glassTintThinDark, "thin tint 暗 0.85（明暗分治——批 3.5-a 拆分）")
        assertEquals(0.94f, AppAlpha.glassTintThinLight, "thin tint 亮 0.94（亮色近实底档）")
    }

    @Test
    fun `glass rim border and aurora tiers match preview baseline`() {
        // 2026-10-03 对稿重锚（DS 对拍）：新稿 tokens.css §2.7 rim 四档直用不乘换算系数
        // ——旧 0.10/0.22 系 v10 旧稿「×.45」产物（受光缘强度只有稿面一半）。
        assertEquals(0.22f, AppAlpha.glassRimDark, "暗 rim·chrome/sheet/thin 0.22（稿 --chrome-rim 逐字）")
        assertEquals(0.20f, AppAlpha.glassRimPanelDark, "暗 rim·panel 0.20（稿 --panel-rim 逐字）")
        assertEquals(0.50f, AppAlpha.glassRimLight, "亮 rim·chrome/sheet/thin 0.50（稿逐字）")
        assertEquals(0.45f, AppAlpha.glassRimPanelLight, "亮 rim·panel 0.45（稿逐字）")
        assertEquals(0.10f, AppAlpha.glassBorderDark, "暗外描边 0.10（稿 --glass-brd 逐字）")
        assertEquals(0.08f, AppAlpha.glassBorderLight, "亮外描边 0.08（稿 --glass-brd 逐字——旧 0.10 与稿不符）")
        // 2026-09-24 批 3.5-a 勘误回正：aurora 暗档 = 计划对比度封顶验算值（原 0.16/0.12 演示档误用）
        assertEquals(0.10f, AppAlpha.auroraPrimaryDark, "暗 aurora 主光斑 0.10")
        assertEquals(0.07f, AppAlpha.auroraPrimaryLight, "亮 aurora 主光斑 0.07")
        assertEquals(0.08f, AppAlpha.auroraTertiaryDark, "暗 aurora 副光斑 0.08")
        assertEquals(0.05f, AppAlpha.auroraTertiaryLight, "亮 aurora 副光斑 0.05")
    }

    // ── 重设计批 0（DS-02/DS-03）：交互微洗底三档（DL-F20 近值不归并）──

    @Test
    fun `interactive wash tiers follow DL-F20 unmerged`() {
        assertEquals(0.04f, AppAlpha.hoverWash, "hover 微洗底 0.04（DS-03，× primary）")
        assertEquals(0.08f, AppAlpha.selectedWash, "selected 微洗底 0.08（DS-03）")
        assertEquals(0.10f, AppAlpha.hotWash, "hot 微洗底 0.10（DS-03）")
    }

    // ── 重设计批 3（DS-08 · 2026-10-01）：ghost 弱化卡预混 alpha 两档（DL-M21 固定值）──

    @Test
    fun `ghost card bg tiers follow DL-M21 fixed values`() {
        // DL-M21：ghost 实底预混档——亮 rgba(255,255,255,.38) / 暗 rgba(255,255,255,.05)。
        // 取值被误改即 GhostCard 弱化层次失衡（暗档超 0.05 会与 surfaceRaised 抢层次）。
        assertEquals(0.38f, AppAlpha.ghostBgLight, "ghost 卡底·亮档 0.38（DL-M21 固定值，预混 White 系）")
        assertEquals(0.05f, AppAlpha.ghostBgDark, "ghost 卡底·暗档 0.05（DL-M21 固定值，近无层次残影）")
        assertNotEquals(AppAlpha.ghostBgLight, AppAlpha.ghostBgDark, "明暗两档必须分离（明暗分治纪律）")
    }
}
