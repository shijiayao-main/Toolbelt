package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文档 tab 投影测试锚（R1 导航重构 2026-09-25 · 空间/文档分离 · 维护者拍板模型：
 * **dock 是空间，tab 是你打开的东西**）：
 *
 * - **零固定栏**：旧「证据流/报告/桥接」三固定 base 投影已删——投影 = openTabs
 *   全量直映（摸查实锤固定栏 2/3 与 dock 完全重复，属「导航过剩」反模式）；
 * - **全部可关**：文档 tab 恒显 ✕（closable 字段随固定概念删除——渲染层恒可关）；
 * - **label 单源**：经 ToolPageLabels 取（与窗口标题/面包屑/dock tooltip 同源），
 *   不再有本文件私有 when 兜底链；
 * - **空间页不进投影**：setCurrentPage 分流语义保证空间页不进 openTabs——投影层
 *   对混入的空间页（异常输入防御）剔除，不渲染空间 tab。
 */
class WorkspaceTabsTest {

    // ── 1 · 投影 = openTabs 直映（零固定栏） ──

    @Test
    fun `projection is a direct image of open tabs`() {
        val openTabs = listOf(ToolPage.Report, ToolPage.Setting, ToolPage.Debug)
        val tabs = tabsProjection(openTabs)
        assertEquals(openTabs, tabs.map { it.page }, "投影 = openTabs 全量直映（保序，无固定 base 拼接）")
    }

    @Test
    fun `empty open tabs renders nothing`() {
        // 新模型：空集是常态（无文档打开）——tab 条 Auto-hide，投影层为空
        val tabs = tabsProjection(emptyList())
        assertTrue(tabs.isEmpty(), "空 openTabs 投影为空（tab 条 Auto-hide 的判定输入）")
    }

    // ── 2 · 固定栏退役锚（回潮即红） ──

    @Test
    fun `fixed structural tabs are gone`() {
        // R1：旧三固定 tab（LogWorkbench/Report/LarkBridge）不再无条件渲染——
        // 空间页（LogWorkbench/LarkBridge）不进 tab 集；Report 降级为按需文档
        val tabs = tabsProjection(listOf(ToolPage.Report))
        assertEquals(1, tabs.size, "只有显式打开的文档出现在投影中")
        assertEquals(ToolPage.Report, tabs[0].page, "报告页是文档（显式打开才出现）")
    }

    @Test
    fun `space pages never render as tabs`() {
        // 异常输入防御：空间页混入 openTabs（VM 分流语义被绕过时）——投影层剔除
        val openTabs = listOf(ToolPage.LogWorkbench, ToolPage.AgentCenter, ToolPage.ShellTools, ToolPage.Report)
        val tabs = tabsProjection(openTabs)
        assertEquals(
            listOf(ToolPage.Report),
            tabs.map { it.page },
            "空间页（工作台/智能体中心/Shell 工具）不得渲染为 tab——dock 是唯一空间导航",
        )
    }

    // ── 3 · label 单源（ToolPageLabels） ──

    @Test
    fun `labels come from single source`() {
        val openTabs = listOf(ToolPage.Report, ToolPage.Setting, ToolPage.Debug, ToolPage.AiSettings)
        val tabs = tabsProjection(openTabs)
        assertEquals(
            listOf("报告", "设置", "Shell 调试", "AI 设置"),
            tabs.map { it.label },
            "tab label 经 ToolPageLabels 单源（与窗口标题/面包屑同源；Debug 规范名 = Shell 调试）",
        )
    }

    @Test
    fun `retired deep-link pages fall back to chinese labels`() {
        // palette 深入模式可打开退役散页（五件套带参命令）——label 中文兜底不露枚举名
        val openTabs = listOf(ToolPage.XLogDecoder, ToolPage.Distill, ToolPage.VoiceTranscribe)
        val tabs = tabsProjection(openTabs)
        assertEquals(
            listOf("XLog 解密", "知识蒸馏", "语音转写"),
            tabs.map { it.label },
            "深入模式散页 label 中文（ToolPageLabels 单源）",
        )
    }

    // ── 4 · 去重防御 ──

    @Test
    fun `duplicate pages never render twice`() {
        val openTabs = listOf(ToolPage.Report, ToolPage.Report, ToolPage.Setting)
        val tabs = tabsProjection(openTabs)
        assertEquals(
            tabs.size,
            tabs.map { it.page }.distinct().size,
            "投影结果不得出现重复页（openTabs 异常输入防御）",
        )
    }

    // ── 5 · DS-16 选中底胶囊化源码锚（重设计批 3 · 2026-10-01）──

    @Test
    fun `selected tab background is clipped to capsule shape`() {
        // tab 选中态胶囊底（AppShapes.capsule）——与 dock 选中圆底
        // （PageHomeMenu）/SegmentedControl 选中胶囊同族。本断言锚 shape 形态：
        // background 调用必须带 `shape = AppShapes.capsule` 参——矩形满宽底回潮即红。
        // Auto-hide 出入场（AnimatedVisibility 外层）不在本守护面（既有投影断言
        // 背书动画输入零变化）。
        val tabRowBlock = workspaceTabsSource()
            .substringBefore(".padding(horizontal = Dimens.space12, vertical = Dimens.space4)")
            .substringAfterLast("WorkbenchTabRow(")
        assertTrue(
            "shape = AppShapes.capsule" in tabRowBlock,
            "tab 选中底必须胶囊化（WorkbenchTabRow 选中 background 须带 " +
                "shape = AppShapes.capsule，与 dock/SegmentedControl 选中语言同族）——" +
                "矩形满宽底回潮即红。",
        )
        // 选中底/字走选中容器身份链（绿平衡批接线）：选中底 = surfaceColors.selectedContainer
        // 实底（与 SpaceBar 选中行/ThemeCard 选中卡同源），选中字/图标 =
        // surfaceColors.onSelectedContainer 配套字链——primary 系洗底/primary 字回潮即红
        //（primary 字对实底选中面无配套保证，底色主权套夜战表会落两态异值坑）。
        // 字/图标取色在 Row modifier 链之后的 lambda 体（capsule background 的
        // padding 截取面之外），扩锚到整个 WorkbenchTabRow 定义块。
        assertTrue(
            "surfaceColors.selectedContainer" in tabRowBlock,
            "tab 选中底必须走 surfaceColors.selectedContainer（选中容器身份槽，" +
                "与 SpaceBar/ThemeCard 选中面同源）——primary 系 selectedWash 洗底回潮即红。",
        )
        val tabRowBody = workspaceTabsSource().substringAfter("private fun WorkbenchTabRow(")
        assertTrue(
            "surfaceColors.onSelectedContainer" in tabRowBody,
            "tab 选中字/图标必须走 surfaceColors.onSelectedContainer（选中面配套字链）" +
                "——primary 字直取回潮即红。",
        )
    }

    // ── 6 · GLB-13 tab 字号字重归一源码锚（终验蜂群修复批 7.5 · 2026-10-02）──

    @Test
    fun `tab label typography is 12sp semibold via tabLabel slot`() {
        // GLB-13：tab 文字 14sp Medium（labelLarge）→ 12sp SemiBold（稿面 .tab
        // fs-12/600 归一恒定，选中/未选中两态字形一致）。DesktopTypography.tabLabel
        // 新档为唯一载体（裸 TextStyle 散写禁）；labelLarge 消费回潮即红。
        // 行高/胶囊形态（tabBarHeight 34dp + DS-16 capsule）由既有守护面背书，
        // 本断言只锚字档。
        val source = workspaceTabsSource()
        val tabRowBlock = source.substringAfter("private fun WorkbenchTabRow(")
        assertFalse(
            "typography.labelLarge" in tabRowBlock,
            "tab 文字不得再走 labelLarge（14sp Medium）——GLB-13 归一 12sp SemiBold，" +
                "旧档消费回潮即红。",
        )
        assertTrue(
            "style = DesktopTypography.tabLabel" in tabRowBlock,
            "tab 文字必须 style = DesktopTypography.tabLabel（GLB-13 新档——12sp/SemiBold" +
                " 按 tokens.css body 档对齐）。",
        )
    }

    /** 守护锚文件（缺失即红——重命名/移动后须同步本守护路径） */
    private fun workspaceTabsSource(): String {
        val file = File(locateProjectRoot(), WORKSPACE_TABS_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$WORKSPACE_TABS_PATH（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private companion object {
        const val WORKSPACE_TABS_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/WorkspaceTabs.kt"
    }
}
