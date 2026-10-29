package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolAction
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-B1 omnibar：frecency 排序纯函数单测。
 * S3 扩：ParamCommand 第三分支（与 Action 同档恒排导航后 / 三类混排注册序稳定 / 空表透传）。
 */
class CommandPaletteFrecencyTest {

    private fun nav(page: ToolPage) = PaletteEntry.Navigate(page, "icon", page.name, "")
    private fun action(id: String) = PaletteEntry.Action(
        action = ToolAction(id = id, label = id, desc = "", icon = "icon", perform = {}),
        icon = "icon",
        label = id,
        desc = "",
        keywords = emptyList(),
    )

    /** S3 带参命令测试件：恒成功空结果（frecency 只看类型不看执行体） */
    private fun param(id: String) = PaletteEntry.ParamCommand(
        spec = ParamCommandSpec(
            id = id,
            label = id,
            desc = "",
            icon = "icon",
            performArgs = { ParamCommandResult(summary = "") },
        ),
    )

    @Test
    fun `high usage page floats to front`() {
        val entries = listOf(nav(ToolPage.XLogDecoder), nav(ToolPage.ShellTools), nav(ToolPage.Timestamp))
        val sorted = sortEntriesByFrecency(entries, mapOf(ToolPage.ShellTools.name to 5, ToolPage.Timestamp.name to 2))
        // ShellTools(5) > Timestamp(2) > XLogDecoder(0)
        assertEquals(listOf(ToolPage.ShellTools, ToolPage.Timestamp, ToolPage.XLogDecoder), sorted.map { (it as PaletteEntry.Navigate).page })
    }

    @Test
    fun `same count keeps registration order`() {
        val entries = listOf(nav(ToolPage.XLogDecoder), nav(ToolPage.ShellTools))
        val sorted = sortEntriesByFrecency(entries, mapOf(ToolPage.XLogDecoder.name to 3, ToolPage.ShellTools.name to 3))
        assertEquals(listOf(ToolPage.XLogDecoder, ToolPage.ShellTools), sorted.map { (it as PaletteEntry.Navigate).page })
    }

    @Test
    fun `actions always sort after navigate entries`() {
        val entries = listOf(action("ai.stop"), nav(ToolPage.XLogDecoder), nav(ToolPage.ShellTools))
        val sorted = sortEntriesByFrecency(entries, emptyMap())
        // 动作项恒排导航项之后（含 0 计数页），无计数时导航保持注册序
        assertEquals("ai.stop", (sorted.last() as PaletteEntry.Action).action.id)
        assertEquals(
            listOf(ToolPage.XLogDecoder, ToolPage.ShellTools),
            sorted.take(2).map { (it as PaletteEntry.Navigate).page },
        )
    }

    @Test
    fun `empty entries and counts pass through`() {
        assertEquals(emptyList(), sortEntriesByFrecency(emptyList(), mapOf("x" to 1)))
        val entries = listOf(nav(ToolPage.Timestamp))
        assertEquals(entries, sortEntriesByFrecency(entries, emptyMap()))
    }

    // ── S3 · ParamCommand 第三分支 ──

    @Test
    fun `param commands sort after navigate entries same as actions`() {
        val entries = listOf(
            param("logkit.xlog-decrypt"),
            nav(ToolPage.XLogDecoder),
            action("shell.stop-current"),
            nav(ToolPage.ShellTools),
        )
        val sorted = sortEntriesByFrecency(entries, emptyMap())
        // 带参命令与动作同档恒排导航后：前两席是导航（注册序），后两席是命令面（注册序）
        assertEquals(
            listOf(ToolPage.XLogDecoder, ToolPage.ShellTools),
            sorted.take(2).map { (it as PaletteEntry.Navigate).page },
        )
        assertEquals(
            listOf("logkit.xlog-decrypt", "shell.stop-current"),
            sorted.drop(2).map {
                when (it) {
                    is PaletteEntry.ParamCommand -> it.spec.id
                    is PaletteEntry.Action -> it.action.id
                    else -> error("后两席应为命令面：$it")
                }
            },
        )
    }

    @Test
    fun `param commands keep registration order across three kinds mixed`() {
        // 三类混排 + 同计数注册序稳定：命令面注册序 [param-a, action-b, param-c] 不被计数重排
        val entries = listOf(
            param("param-c"),
            action("action-b"),
            param("param-a"),
            nav(ToolPage.ShellTools), // 计数 0——不与命令面抢位（命令恒后）
        )
        val sorted = sortEntriesByFrecency(entries, mapOf(ToolPage.ShellTools.name to 1))
        assertEquals(ToolPage.ShellTools, (sorted.first() as PaletteEntry.Navigate).page)
        assertEquals(
            listOf("param-c", "action-b", "param-a"),
            sorted.drop(1).map {
                when (it) {
                    is PaletteEntry.ParamCommand -> it.spec.id
                    is PaletteEntry.Action -> it.action.id
                    else -> error("后三席应为命令面：$it")
                }
            },
        )
    }

    @Test
    fun `param entries empty list passes through with key prefix isolation`() {
        val single = param("x")
        assertEquals(listOf(single), sortEntriesByFrecency(listOf(single), emptyMap()))
        // key 前缀隔离：三类 key 空间经前缀天然区分（LazyColumn key 无撞面）
        assertEquals("param:x", single.key)
        assertEquals("act:shell.stop", action("shell.stop").key)
        assertEquals("nav:ShellTools", nav(ToolPage.ShellTools).key)
    }

    // ── 评审修复批（2026-09-23）· 推荐首页判定（「空态即首页」裁决的可达性锚） ──

    @Test
    fun `recommendation home shows for mixed mode with blank query`() {
        // Mixed + 空查询（面板初始态/退格清空态）= 推荐首页（三路推荐渲染）
        assertTrue(shouldShowRecommendationHome(PalettePrefix.Mixed, ""), "Mixed 空查询必须进推荐首页态")
        assertTrue(shouldShowRecommendationHome(PalettePrefix.Mixed, "   "), "Mixed 纯空白查询进推荐首页态（isBlank 语义）")
    }

    @Test
    fun `recommendation home hidden for prefixed domains and nonblank query`() {
        // 前缀域：裸 `>` / `@`（effectiveQuery 空）不进推荐首页——前缀语义精确
        assertFalse(
            shouldShowRecommendationHome(PalettePrefix.CommandsOnly, ""),
            "`>` 命令域空态走精确文案，不进推荐首页",
        )
        assertFalse(
            shouldShowRecommendationHome(PalettePrefix.PagesOnly, ""),
            "`@` 页面域空态走精确文案，不进推荐首页",
        )
        // Mixed 带查询：匹配失败时走「没有匹配」文案，不进推荐首页
        assertFalse(
            shouldShowRecommendationHome(PalettePrefix.Mixed, "xyz"),
            "Mixed 带查询不进推荐首页（匹配失败文案语义精确）",
        )
    }

    @Test
    fun `recommendation home decision is decoupled from filtered emptiness`() {
        // 🔴 旧实现不可达复盘锚：曾要求「filtered.isEmpty() 才渲染推荐」，但候选集
        // buildList 末尾无条件 add Navigate(Setting)（前缀域也恒非空）——互斥恒假。
        // 本判定只看 prefixMode/effectiveQuery，与 filtered 无关：Mixed 空查询即使
        // filtered 非空（恒然）也进推荐首页态。
        val allItems = listOf(nav(ToolPage.Setting), param("logkit.xlog-decrypt"))
        assertTrue(allItems.isNotEmpty(), "前提：候选集恒非空（设置项无条件 add）")
        assertTrue(
            shouldShowRecommendationHome(PalettePrefix.of(""), ""),
            "Mixed 空查询即推荐首页——不受 filtered 非空否决（旧互斥条件已摘除）",
        )
    }

    // ── GLB-12 · palette 检索态分组头（终验蜂群修复批 7.5 · 2026-10-02）──

    @Test
    fun `search result list renders section headers for commands and pages`() {
        // 检索态结果列表按三段语义加分段头（.pgrp 三段结构同构
        // ——labelSmall faint 无图标纯文本行）；空查询零变化（推荐首页走
        // PaletteEmptyHint 分支，本守护只锚列表态源码形态）。分段顺序 = 带参命令
        //（Action 同档并入命令段）→ 页面 → 未读产物（Mixed 域恒显——数据源
        // recentReports 检索态作用域可读）。
        // 分组头/候选行定义随尾部卫星迁至 PaletteEntries.kt；分组槽表与 key 前缀留在
        // CommandPalette.kt 状态机（列表态装配段）
        val palette = File(locateProjectRoot(), COMMAND_PALETTE_SOURCE_PATH).readText()
        val entries = File(locateProjectRoot(), PALETTE_ENTRIES_SOURCE_PATH).readText()
        val listBlock = entries.substringAfter("internal fun PaletteSectionHeader")
            .substringBefore("internal fun PaletteRow(")
        assertTrue(
            "typography.labelSmall" in listBlock,
            "PaletteSectionHeader 必须走 labelSmall（GLB-12：稿面 .pgrp fs-11 同构）。",
        )
        assertTrue(
            "onCardFaint" in listBlock,
            "PaletteSectionHeader 必须走 faint 弱色（GLB-12：稿面 on-surface-faint 同构；" +
                "面板 sheet 取源井槽——文字链走卡域 onCardFaint，非覆写套回落 onSurfaceFaint 同色）。",
        )
        val slotsBlock = palette.substringAfter("val entriesWithHeaders = buildList")
            .substringBefore("LazyColumn(")
        assertEquals(
            3,
            Regex("""ListSlot\.Header\("([^"]+)"\)""").findAll(slotsBlock).count(),
            "检索态列表必须恰有三个分组头槽（带参命令/页面/未读产物）。",
        )
        assertTrue(
            "ListSlot.Header(\"带参命令\")" in slotsBlock && "ListSlot.Header(\"页面\")" in slotsBlock,
            "分组头文案必须为「带参命令」「页面」（GLB-12 卡面三段名前两段）。",
        )
        assertTrue(
            "\"hdr:" in palette,
            "Header 槽 key 必须带 hdr: 前缀（与三类 entry key 空间隔离——LazyColumn key 无撞面）。",
        )
    }

    private companion object {
        const val COMMAND_PALETTE_SOURCE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/CommandPalette.kt"
        const val PALETTE_ENTRIES_SOURCE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteEntries.kt"
    }
}
