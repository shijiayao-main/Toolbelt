package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dimens token 常量断言（design D1 / D7）：取值被误改即测试失败，
 * 作为 token 值等价迁移的视觉回归防线。
 */
class DimensTest {

    @Test
    fun `spacing ladder matches numeric names`() {
        assertEquals(2.dp, Dimens.space2)
        assertEquals(4.dp, Dimens.space4)
        assertEquals(6.dp, Dimens.space6)
        assertEquals(8.dp, Dimens.space8)
        assertEquals(10.dp, Dimens.space10)
        assertEquals(12.dp, Dimens.space12)
        assertEquals(14.dp, Dimens.space14) // DS-04 · 重设计批 0 扩档：卡距档
        assertEquals(16.dp, Dimens.space16)
        assertEquals(20.dp, Dimens.space20) // DS-04 · 重设计批 0 扩档：卡内边距纵向档
        assertEquals(24.dp, Dimens.space24)
    }

    @Test
    fun `semantic aliases map to ladder values`() {
        assertEquals(Dimens.space24, Dimens.screenPadding, "页面统一内边距应为 24dp")
        assertEquals(Dimens.space16, Dimens.screenSpacing, "页面顶层元素间距应为 16dp")
        assertEquals(24.dp, Dimens.screenPadding)
        assertEquals(16.dp, Dimens.screenSpacing)
    }

    @Test
    fun `icon size tiers match current component values`() {
        assertEquals(16.dp, Dimens.iconSmall, "列表/状态小图标应为 16dp")
        assertEquals(18.dp, Dimens.iconMedium, "任务卡状态中图标应为 18dp")
        assertEquals(20.dp, Dimens.iconLarge, "按钮内大图标应为 20dp")
        assertEquals(40.dp, Dimens.iconHero, "拖放区主图标应为 40dp")
    }

    // ── 2026-09-23 批一：MICS 桌面紧凑档（批二 0.dp 全关实验真机副作用回正定档） ──

    @Test
    fun `minimum interactive size is compact desktop tier`() {
        assertEquals(32.dp, Dimens.minimumInteractiveSize, "MICS 应为 32dp 桌面紧凑档（非 0 关闭、非 48 触屏档）")
    }

    // ── 交互重设计 P0（三舱骨架·单点收敛）新增 token 断言 ──

    @Test
    fun `side bar slot widths form min-default-max ladder`() {
        assertEquals(220.dp, Dimens.sideBarWidthMin, "侧栏槽位宽下限应为 220dp")
        // 域侧栏宽锚：.side width:340px（域稿值，胜全局稿 280——收纳上限优先）
        assertEquals(340.dp, Dimens.sideBarWidthDefault, "侧栏槽位默认宽应为 340dp（域稿逐字胜全局稿 280）")
        assertEquals(360.dp, Dimens.sideBarWidthMax, "侧栏槽位宽上限应为 360dp")
    }

    @Test
    fun `splitter hit area exceeds visual width`() {
        assertEquals(4.dp, Dimens.splitterWidth, "分隔条视觉宽应为 4dp")
        assertEquals(12.dp, Dimens.splitterHitWidth, "分隔条命中区应为 12dp（Fitts：命中面大于可见面）")
    }

    @Test
    fun `shell tree height floor matches S2 redline`() {
        // SH-17 · 重设计批 5：树区硬底 token——S2 事故红线「树区不可饿成 0」
        assertEquals(320.dp, Dimens.shellTreeHeightMin, "Shell 页工具树最小高度应为 320dp（SH-17 拖拽上限同源引用）")
    }

    @Test
    fun `help card token matches design`() {
        // （评审修复批 2026-09-23：panelHandleHeight/panelGrabberWidth 断言随 S2 底面板
        //  退役删除——token 本体已删，生产消费为零）
        assertEquals(480.dp, Dimens.helpCardMaxHeight, "F1 参考卡最大高度应为 480dp")
    }

    @Test
    fun `focus ring and content width tokens match design`() {
        // （Wide 限宽居中已废除：maxContentWidth 断言随 token 删除——
        //  生产消费为零，PageScaffold 内容区恒满宽）
        assertEquals(1.dp, Dimens.focusRingOffset, "焦点环偏移应为 1dp")
        assertEquals(16.dp, Dimens.screenPaddingCompact, "Compact 档主区内边距应为 16dp")
    }

    @Test
    fun `window breakpoints are compact-medium-wide ladder`() {
        assertEquals(1024.dp, Dimens.breakpointCompactMax, "Compact 档上限应为 1024dp")
        assertEquals(1440.dp, Dimens.breakpointMediumMax, "Medium 档上限应为 1440dp")
        assertEquals(1760.dp, Dimens.breakpointWideMax, "Wide 档上限应为 1760dp")
    }

    @Test
    fun `toast and undo bar tokens match design`() {
        assertEquals(360.dp, Dimens.toastWidth, "浮层提示条宽应为 360dp")
        assertEquals(46.dp, Dimens.toastBottomOffset, "浮层提示条偏移应为 46dp（批 3.7-b 勘误：预览稿 .toast{bottom:46px} 直译，不压状态栏胶囊）")
        assertEquals(40.dp, Dimens.undoBarHeight, "撤销条高度应为 40dp")
        // DS-15 · 重设计批 4：toast 堆叠槽位数（Int 容量档非 dp——预览稿 .toastcol 三槽直译）
        assertEquals(3, Dimens.toastMaxStack, "toast 堆叠槽位数应为 3（第 4 条挤掉最旧）")
    }

    @Test
    fun `list capacity trio matches migrated scatter values`() {
        assertEquals(5000, Dimens.listBoundedMax, "有界封顶容量应为 5000（原 Shell 输出 MAX_OUTPUT_ITEMS）")
        assertEquals(1000, Dimens.listRingMax, "环形缓冲容量应为 1000（原桥接日志台环形缓冲）")
        assertEquals(30, Dimens.listDomainMax, "领域列表容量应为 30（原 Workbench run 列表）")
    }

    @Test
    fun `onboarding card tokens match design`() {
        // 反馈批二 2026-09-23：首启导览卡宽度上限——与 paletteWidth 同值但语义独立
        // （一次性引导面 vs 常驻 omnibar，日后单独调宽不牵连面板）
        assertEquals(560.dp, Dimens.onboardingCardMaxWidth, "首启导览卡宽度上限应为 560dp")
    }

    // ── 2026-09-23 液态玻璃批 1：材质尺寸档（HTML 预览稿 v10 视觉基线直译）──
    // R2 材质诚实化（2026-09-25）：chrome 档玻璃退役——glassBlurChrome 断言删除。

    @Test
    fun `glass blur tiers match preview baseline`() {
        assertEquals(30.dp, Dimens.glassBlurSheet, "sheet 玻璃 blur 应为 30dp（浮层深 blur）")
        assertEquals(18.dp, Dimens.glassBlurThin, "thin 玻璃 blur 应为 18dp（小件档）")
    }

    @Test
    fun `glass hairline and float inset match preview baseline`() {
        assertEquals(1.dp, Dimens.glassStrokeHairline, "玻璃发丝描边应为 1dp（禁渐变带终修）")
        assertEquals(8.dp, Dimens.glassFloatInset, "浮动玻璃距窗缘应为 8dp")
        assertEquals(12.dp, Dimens.glassShadowElevation, "浮层玻璃投影应为 12dp（chrome 恒零投影）")
    }

    @Test
    fun `title bar tokens match preview baseline`() {
        // 2026-09-24 批 6 edge-to-edge：自绘标题栏（预览稿 .titlebar 直译，38px 收敛 4dp 网格）
        assertEquals(40.dp, Dimens.titleBarHeight, "自绘标题栏高应为 40dp")
        assertEquals(12.dp, Dimens.trafficLightDot, "窗口控制圆点直径应为 12dp（mock-base .titlebar .dot 12px 逐字直译——2026-10-03 对稿由 11 改 12）")
        assertEquals(20.dp, Dimens.titleBarPaddingH, "标题栏水平内边距应为 20dp（mock-base .titlebar padding 0 var(--sp-20) 逐字——2026-10-03 对稿由 14 改 20）")
        assertEquals(22.dp, Dimens.dockIconSize, "dock 图标字形 22dp（mock-base .rail .ico 22px 逐字——蜂群清欠 GLB-D1）")
    }

    @Test
    fun `aurora mask bands match preview baseline`() {
        // 2026-09-24 批 3.5-b：预览稿 L148-161 四边 linear-gradient mask 直译
        assertEquals(52.dp, Dimens.auroraMaskTopBegin, "顶遮罩透明起点 52dp（tab 条带下）")
        assertEquals(76.dp, Dimens.auroraMaskTopEnd, "顶遮罩不透明终点 76dp")
        assertEquals(36.dp, Dimens.auroraMaskBottomBegin, "底遮罩透明起点 36dp（状态栏带上）")
        assertEquals(60.dp, Dimens.auroraMaskBottomEnd, "底遮罩不透明终点 60dp")
        assertEquals(80.dp, Dimens.auroraMaskLeftBegin, "左遮罩透明起点 80dp（dock 带右，09-27 定稿 rail 68dp 贴边）")
        assertEquals(104.dp, Dimens.auroraMaskLeftEnd, "左遮罩不透明终点 104dp")
        assertEquals(8.dp, Dimens.auroraMaskRightBegin, "右遮罩透明起点 8dp")
        assertEquals(32.dp, Dimens.auroraMaskRightEnd, "右遮罩不透明终点 32dp")
    }

    // ── 2026-09-24 批 3.5-c：P0-1/3 dock×状态栏几何重叠修复 token ──

    @Test
    fun `status slot height is dock clearance baseline`() {
        // 预览稿 L243 `.dock{bottom:calc(8px+42px)}` 直译：42 = 胶囊 26 + 底隙 8 + 顶隙 8
        assertEquals(42.dp, Dimens.statusSlotHeight, "状态栏悬浮槽高应为 42dp（dock 底部让位 50dp 的组成部分）")
        assertEquals(
            Dimens.glassFloatInset + Dimens.statusSlotHeight,
            50.dp,
            "dock 底部让位应为 glassFloatInset + statusSlotHeight = 50dp（PageHomeMenu 消费面）",
        )
    }

    // ── 2026-09-24 批 3.6：轻量版全屏铺内容视口 token（预览稿 L237/L186 直译） ──

    @Test
    fun `content viewport padding matches preview baseline`() {
        // Top = 8（tab 条顶隙）+ 34（tab 条高）+ 8（tab 条底隙）
        assertEquals(
            Dimens.glassFloatInset + Dimens.tabBarHeight + Dimens.glassFloatInset,
            Dimens.contentViewportPaddingTop,
            "内容视口顶让位应 = 玻璃带(tab 条)整带 50dp",
        )
        assertEquals(50.dp, Dimens.contentViewportPaddingTop)
        // Start = dock 让位（68 rail 宽贴边整列 + 12 呼吸隙）——与 tab 条左缘同一对齐线
        assertEquals(
            Dimens.railWidthCompact + Dimens.space12,
            Dimens.contentViewportPaddingStart,
            "内容视口左让位应与 tab 条/dock 让位一致（80dp 对齐线）",
        )
        assertEquals(80.dp, Dimens.contentViewportPaddingStart)
        assertEquals(8.dp, Dimens.contentViewportPaddingEnd, "右让位应与窗缘语言统一 8dp")
        // Bottom = 状态栏占位 42（.body--botspace 计算值：calc 尾距+状态栏高）
        // calc(sp-8+26px+sp-8)=42px 逐字；旧 50 系 42+8 玻璃带推算非稿面直译）
        assertEquals(42.dp, Dimens.contentViewportPaddingBottom)
    }

    @Test
    fun `content viewport top padding has a no-tabs tier`() {
        // GLB-05（重设计批 4）：tab Auto-hide 顶部让位联动——openTabs 空集时顶让位
        // 收敛窗缘隙 8dp（与 glassFloatInset 同值但语义独立登记：日后单独调值不牵连）
        assertEquals(8.dp, Dimens.contentViewportPaddingTopNoTabs, "无 tab 带顶让位应为 8dp（窗缘隙）")
    }

    @Test
    fun `content fade band matches preview baseline`() {
        // 预览稿 .content-fade mask-image 34px 直译
        assertEquals(34.dp, Dimens.contentFadeBand, "内容渐隐边带应为 34dp（预览稿 v11 基线）")
    }

    // ── 2026-09-24 批 3.7-b：玻璃叠玻璃让位 token（预览稿 L346/L285 直译） ──

    @Test
    fun `inspector and toast clearances match preview baseline`() {
        // inspector：预览稿 .inspector{top:calc(8px+46px);bottom:calc(8px+42px)}
        assertEquals(54.dp, Dimens.inspectorTopClearance, "inspector 顶让位应为 54dp（顶让 tab 条带）")
        assertEquals(50.dp, Dimens.inspectorBottomClearance, "inspector 底让位应为 50dp（底让状态栏带）")
        // toast：预览稿 .toast{bottom:46px}——底缘在状态栏胶囊顶缘之上 12px
        assertEquals(46.dp, Dimens.toastBottomOffset)
    }
}
