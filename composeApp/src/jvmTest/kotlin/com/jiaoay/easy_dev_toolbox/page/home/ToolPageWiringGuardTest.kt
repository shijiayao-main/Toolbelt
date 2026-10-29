package com.jiaoay.easy_dev_toolbox.page.home

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.arch.locateProjectRoot
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 导航接线源码锚守护（自 ToolPageRegistryNavTest 并出的源码锚子族）：
 * dock/palette/工具页/智能体中心/家页的图标字形、深链路由、子签槽订阅、
 * 文案单源等**接线面**契约按源码切片钉死；注册表的纯数据断言（navItems/
 * navSections/registrations）仍留 ToolPageRegistryNavTest——例外：全量注册表
 * page 值唯一性在本守护（撞 page = 消费面静默蒸发，接线级失效面）。
 */
class ToolPageWiringGuardTest {

    // ── GLB-02 · 全表图标去重（2026-10-02）──

    @Test
    fun `dock entries and system zone icons are pairwise distinct`() {
        // 图标=页面指纹（GLB-02）：dock 全表十一枚（空间集六 + 命令入口一 + 系统区四
        // AI 设置/MCP/调试/设置）互异。撞标 = 用户在 dock 上靠图标无法区分目标页
        //（GLB-02 改① SMART_TOY 撞标教训的全表面收紧——原断言只钉单枚，本断言
        // 钉全表 pairwise；数量钉死 = 切片静默失败不空转）。
        val allIcons = buildList {
            DOCK_ENTRIES.forEach { add(it.icon) }
            // 命令入口 + 系统区四枚（源码切片——硬编码字面量不在 DOCK_ENTRIES 数据面，
            // AI 设置切片先例同款）
            val src = pageHomeMenuSource()
            listOf("命令", "AI 设置", "MCP 服务", "Shell 调试", "设置").forEach { label ->
                val block = src.substringBefore("label = \"$label\"").substringAfterLast("DockIconButton(")
                Regex("""icon = (MaterialSymbols\.[A-Z_]+)""").find(block)?.let { add(it.groupValues[1]) }
            }
        }
        assertEquals(
            11,
            allIcons.size,
            "dock 图标切片必须集齐十一枚（空间六 + 命令一 + 系统四）——切片失败即红防空转（GLB-02）",
        )
        assertEquals(
            allIcons.size,
            allIcons.distinct().size,
            "dock 图标集必须两两互异（GLB-02：图标=页面指纹；重复字形：" +
                "${allIcons.groupingBy { it }.eachCount().filterValues { it > 1 }}）",
        )
        // 数据面下限锚：DOCK_ENTRIES 六空间自身互异（切片逻辑漂移时不静默放过）
        val dockIconsOnly = DOCK_ENTRIES.map { it.icon }
        assertEquals(
            dockIconsOnly.size,
            dockIconsOnly.distinct().size,
            "DOCK_ENTRIES 六空间图标自身必须互异（GLB-02）",
        )
    }

    @Test
    fun `ai settings icon is glyph-consistent across dock nav item and tab fallback`() {
        // GLB-02 改①：AI 设置三面（dock 系统区/navItem/tab 兜底）统一 PSYCHOLOGY
        //（AGENTS 组语义，agent 大脑）；SMART_TOY 保留给智能体中心（§4.3 唯一锚）——
        // dock 内两枚 SMART_TOY 撞标清零。
        val aiSettingsNav = ToolPageRegistry.navItems().first { it.page == ToolPage.AiSettings }
        assertEquals(
            MaterialSymbols.PSYCHOLOGY,
            aiSettingsNav.icon,
            "AI 设置 navItem.icon 必须为 PSYCHOLOGY（GLB-02 改①：三面同字形）",
        )
        assertEquals(
            MaterialSymbols.PSYCHOLOGY,
            hostPageIcon(ToolPage.AiSettings),
            "AI 设置 tab 兜底必须为 PSYCHOLOGY（GLB-02 改①：navItem=null 时兜底同字形）",
        )
        // dock 系统区 AI 设置钮（源码断言：硬编码字面量不在 DOCK_ENTRIES 数据面）
        val aiSettingsDockBlock = pageHomeMenuSource()
            .substringBefore("label = \"AI 设置\"")
            .substringAfterLast("DockIconButton(")
        assertTrue(
            "icon = MaterialSymbols.PSYCHOLOGY" in aiSettingsDockBlock,
            "dock 系统区 AI 设置钮必须为 PSYCHOLOGY（GLB-02 改①：dock 面）",
        )
        // 撞标清零：智能体中心（DOCK_ENTRIES 唯一 SMART_TOY）之外不得再消费 SMART_TOY
        assertTrue(
            DOCK_ENTRIES.none { it.icon == MaterialSymbols.SMART_TOY && it.page != ToolPage.AgentCenter },
            "SMART_TOY 保留给智能体中心入口级聚合（§4.3 唯一锚）——dock 空间集撞标清零",
        )
    }

    @Test
    fun `report tab fallback and palette unread row use insights glyph`() {
        // GLB-02 改④：报告统一 INSIGHTS——tab 兜底 DESCRIPTION→INSIGHTS（与 palette
        // 报告候选 :232 同字形），palette 未读产物行 DESCRIPTION→INSIGHTS（行文案是
        // 报告文件名，图标=报告指纹）。DESCRIPTION 收敛为工作台家 dock 专属。
        assertEquals(
            MaterialSymbols.INSIGHTS,
            hostPageIcon(ToolPage.Report),
            "报告 tab 兜底必须为 INSIGHTS（GLB-02 改④：与 palette 报告候选同字形）",
        )
        // 未读产物行定义随 palette 尾部卫星迁至 PaletteEntries.kt
        val unreadBlock = paletteEntriesSource()
            .substringAfter("未读产物（RunStore reportPath 非 null 最近 3）")
            .substringBefore("if (!emptyState.loaded)")
        assertTrue(
            "MaterialSymbols.INSIGHTS" in unreadBlock,
            "palette 未读产物行必须渲染 INSIGHTS（GLB-02 改④：报告行=报告指纹）",
        )
        assertFalse(
            "MaterialSymbols.DESCRIPTION" in unreadBlock,
            "palette 未读产物行不得再用 DESCRIPTION（GLB-02：收敛为工作台家专属）",
        )
    }

    // ── 批 2 复核顺修 · palette 硬编码候选 ↔ hostPageIcon 对照守护（2026-10-01）──

    @Test
    fun `palette hardcoded setting and debug candidates match hostPageIcon glyphs`() {
        // 宿主页两枚硬编码 Navigate 候选（Setting/Debug）与 tab 兜底 hostPageIcon 是
        // 两个独立字面量源——当前零漂移但双源无守护，改一面即「同页两图标」
        //（palette 候选 vs tab 条 icon）。本断言把两面各自钉死到同一 MaterialSymbols
        // 常量，互检防未来漂移：
        //   - palette 侧：含 PaletteEntry.Navigate(ToolPage.Setting 的候选行必须
        //     同时含 MaterialSymbols.SETTINGS（行级切片——整行同语句，候选被移走/
        //     换字形即红）；
        //   - tab 兜底侧：hostPageIcon(Setting/Debug) 必须逐字等于同一常量。
        val paletteSource = commandPaletteSource()
        val settingCandidateLine = paletteSource.lines().firstOrNull { "PaletteEntry.Navigate(ToolPage.Setting" in it }
        assertNotNull(
            settingCandidateLine,
            "palette 必须保留设置页硬编码 Navigate 候选（应用中心退役后 palette 是全量直达面）",
        )
        assertTrue(
            "MaterialSymbols.SETTINGS" in (settingCandidateLine ?: ""),
            "palette 设置候选必须用 MaterialSymbols.SETTINGS 字形（与 tab 兜底 hostPageIcon 同源互检）",
        )
        val debugCandidateLine = paletteSource.lines().firstOrNull { "PaletteEntry.Navigate(ToolPage.Debug" in it }
        assertNotNull(
            debugCandidateLine,
            "palette 必须保留 Shell 调试页硬编码 Navigate 候选（debugEnabled 门控内）",
        )
        assertTrue(
            "MaterialSymbols.BUG_REPORT" in (debugCandidateLine ?: ""),
            "palette Shell 调试候选必须用 MaterialSymbols.BUG_REPORT 字形（与 tab 兜底 hostPageIcon 同源互检）",
        )
        assertEquals(
            MaterialSymbols.SETTINGS,
            hostPageIcon(ToolPage.Setting),
            "设置 tab 兜底必须为 SETTINGS（与 palette 硬编码候选互检——改一面即红）",
        )
        assertEquals(
            MaterialSymbols.BUG_REPORT,
            hostPageIcon(ToolPage.Debug),
            "Shell 调试 tab 兜底必须为 BUG_REPORT（与 palette 硬编码候选互检——改一面即红）",
        )
    }

    // ── AG-15 · 聚合页子签深链（三退役 AI 散页）──

    @Test
    fun `agent center page options and tab state consume the pending slot`() {
        // AG-15 消费面源码锚：AgentCenterPage 必须 ① options 四签 size 守护（映射值域
        // 0..2 与四签对齐的运行时前提）② rememberSaveable 退役（saveable 恢复态与槽
        // 订阅竞态——ToolsPage TL-02 同因）③ 订阅槽流读即清（在页响应缝 + 手动切签
        // 零残留——批 4 复核 P1-1 双 effect 配方照抄）。
        val file = File(locateProjectRoot(), AGENT_CENTER_PAGE_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$AGENT_CENTER_PAGE_PATH（重命名/移动后请同步本守护）")
        val source = file.readText()
        assertTrue(
            "options = listOf(\"会话\", \"知识蒸馏\", \"语音转写\", \"调查空间\")" in source,
            "AgentCenterPage options 四签序守护（会话/知识蒸馏/语音转写/调查空间）——" +
                "签增删时须同步 agentCenterIndexOf 映射与本断言",
        )
        assertFalse(
            "rememberSaveable { mutableStateOf" in source,
            "AgentCenterPage 子签态不得用 rememberSaveable（恢复态覆盖槽载荷——AG-15/TL-02 竞态）",
        )
        assertTrue(
            "var tab by remember { mutableStateOf(0) }" in source,
            "AgentCenterPage 子签态必须 remember（槽订阅合成——AG-15）",
        )
        assertTrue(
            "appViewModel.pendingAgentCenterTab" in source &&
                ".filterNotNull()" in source,
            "AgentCenterPage 必须订阅聚合页预选槽流（AG-15：palette 在页触发时槽载荷即时响应）",
        )
        assertTrue(
            "appViewModel.consumePendingAgentCenterTab()" in source,
            "AgentCenterPage 槽消费必须读即清（AG-15：手动切签不受深链残留影响）",
        )
    }

    @Test
    fun `palette registers three retired ai deep-link candidates and merges row clicks through hostOf`() {
        // AG-15 硬缝①验收锚：三退役 AI 散页此前零 palette 候选（navItem 退役不进
        // navItems()），搜「知识蒸馏」只能经 AgentCenter desc 子串间接命中——本卡补
        // 三 Navigate 候选（page=原散页枚举，行点击归并进聚合页 + 预选子签）。
        val paletteSource = commandPaletteSource()
        // 三候选存在性（page 锚——候选被移走即红）
        assertTrue(
            "PaletteEntry.Navigate(ToolPage.Distill" in paletteSource,
            "palette 必须有「知识蒸馏」深链候选（AG-15：散页整页形态退役，深链直达聚合页第二签）",
        )
        assertTrue(
            "PaletteEntry.Navigate(ToolPage.VoiceTranscribe" in paletteSource,
            "palette 必须有「语音转写」深链候选（AG-15）",
        )
        assertTrue(
            "PaletteEntry.Navigate(ToolPage.Flow" in paletteSource,
            "palette 必须有「会话」深链候选（AG-15）",
        )
        // 两处 Navigate 行点击分支归并（Enter 分支 + 行点击分支——openPageOf :297 有
        // 归并而 Navigate 行没有的语义不一致是本卡要补的缝）：退役散页直落
        // setCurrentPage(Distill) 会复活散页整页形态（PageHomeContent 兜底虽已扩容
        // AgentCenter，归并收口在前防散页落定）。
        val navigateClickMerges = paletteSource.lines().count {
            "onNavigate(" in it && "entry.page" in it && "entry.aiDisabled" !in it
        }
        assertTrue(
            navigateClickMerges == 0,
            "palette Navigate 行点击不得再裸传 entry.page（AG-15：须 hostOf 归并 + 第二参捎带原枚举）",
        )
        val mergedBranchCount = paletteSource.lines().count {
            "ToolPageLabels.hostOf(entry.page) ?: entry.page" in it
        }
        assertTrue(
            mergedBranchCount >= 2,
            "palette Enter/行点击两分支必须 hostOf 归并路由（AG-15：两处分支语义一致，count=$mergedBranchCount < 2）",
        )
    }

    @Test
    fun `page home content fallback redirects both retired page families to host pages`() {
        // AG-15 兜底闸扩容源码锚：PageHomeContent 对散页残余恢复态的改道必须覆盖
        // 两个聚合宿主（Tools + AgentCenter）——散页整页形态的渲染可达面清零。
        val file = File(locateProjectRoot(), PAGE_HOME_CONTENT_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$PAGE_HOME_CONTENT_PATH（重命名/移动后请同步本守护）")
        val source = file.readText()
        assertTrue(
            "it == ToolPage.Tools || it == ToolPage.AgentCenter" in source,
            "PageHomeContent 兜底必须同时接管 Tools 与 AgentCenter 两宿主（AG-15：三 AI 散页残余态改道聚合页）",
        )
    }

    @Test
    fun `palette openPageOf routes five retired pages through hostOf aggregation`() {
        // TL-02 路由归并源码锚：openPageOf 必须经 ToolPageLabels.hostOf 归并（散页
        // 恒落聚合页）+ 第二参捎带原散页枚举（宿主换算预选子签）。openPageOf 是
        // 五条带参命令 + HUD 链接的共用收口——一处钉死全链生效。
        val openPageOfBlock = commandPaletteSource()
            .substringAfter("fun openPageOf(spec: ParamCommandSpec)")
            .substringBefore("Popup(")
        assertTrue(
            "onNavigate(ToolPageLabels.hostOf(page) ?: page, page)" in openPageOfBlock,
            "openPageOf 必须经 hostOf 归并路由（TL-02：五件套散页恒落聚合页，第二参捎带原枚举）",
        )
        assertFalse(
            "onNavigate(page)" in openPageOfBlock,
            "openPageOf 不得再直接跳散页（散页永不复活——深入模式唯一改点）",
        )
    }

    @Test
    fun `palette deep mode copy says tools page across three surfaces`() {
        // TL-02 文案三处同步（勘误扩展：HUD + 参数直填态行尾钮 + 结果行 contentDescription
        // ——卡面只点 HUD，三处同语义全改防并存）：深入模式钮文案对齐「落聚合页」语义。
        // 参数直填态行尾钮在 CommandPalette.kt 状态机、结果行 contentDescription 随
        // 候选行迁至 PaletteEntries.kt——两面各自断言防单源漂移。
        val paletteSource = commandPaletteSource()
        assertFalse(
            "打开页面" in paletteSource,
            "CommandPalette 深入模式文案不得再写「打开页面」（TL-02：三处同步改「打开工具页」）",
        )
        assertTrue(
            "打开工具页（深入模式）" in paletteSource,
            "CommandPalette 参数直填态行尾深入模式钮文案必须为「打开工具页（深入模式）」",
        )
        val entriesSource = paletteEntriesSource()
        assertFalse(
            "打开页面" in entriesSource,
            "palette 候选行深入模式文案不得再写「打开页面」（TL-02）",
        )
        assertTrue(
            "打开工具页（深入模式）" in entriesSource,
            "palette 候选行深入模式钮 contentDescription 必须为「打开工具页（深入模式）」",
        )
        val hudSource = File(locateProjectRoot(), PALETTE_HUD_PATH).readText()
        assertFalse(
            "打开页面" in hudSource,
            "PaletteHud 深入模式钮文案不得再写「打开页面」（TL-02）",
        )
        assertTrue(
            "打开工具页" in hudSource,
            "PaletteHud 深入模式钮文案必须为「打开工具页」（TL-02）",
        )
    }

    // ── TL-08 · 子签选择跨启动持久（消费侧源码锚）──

    @Test
    fun `tools page composes initial tab from pending slot then persisted value`() {
        // ToolsPage 初值合成序（TL-02 预选槽优先 → TL-08 持久值回落）+ 越界回 0 的
        // 源码锚：🔴 勘误口径——坏值（9）回 0（第 1 签）而非 coerceIn(0,4)（=4 第 5
        // 签）；rememberSaveable 已退位（saveable 恢复态会覆盖合成初值，预选/持久
        // 语义失效）。往返/透传断言见 ToolsConfigStoreTest（同 commit）。
        val file = File(locateProjectRoot(), TOOLS_PAGE_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$TOOLS_PAGE_PATH（重命名/移动后请同步本守护）")
        val source = file.readText()
        assertTrue(
            "appViewModel.consumePendingToolsTab()" in source,
            "ToolsPage 初值合成必须先消费预选槽（TL-02 槽值优先）",
        )
        assertTrue(
            "ToolsConfigStore.getSelectedTab()" in source,
            "ToolsPage 初值合成必须回落持久值（TL-08 跨启动记忆）",
        )
        assertTrue(
            "ToolsConfigStore.setSelectedTab(index)" in source,
            "ToolsPage 切签必须回写持久值（TL-08 onSelect 直写）",
        )
        assertFalse(
            "rememberSaveable { mutableStateOf" in source,
            "ToolsPage 子签态不得再用 rememberSaveable（恢复态覆盖合成初值——TL-02/TL-08 竞态）",
        )
        assertTrue(
            "if (v in 0..ToolsConfigStore.TAB_MAX_INDEX) v else 0" in source,
            "持久值越界必须回 0（TL-08 勘误口径：坏值回第 1 签，非 coerceIn 就近夹取）",
        )
        // 批 4 复核 P1-1 在页响应缝守护：palette 是浮层，工具页组合期写入的预选槽
        // 载荷必须被订阅 effect 即时消费（LaunchedEffect(Unit) 初值合成只跑一次，
        // 在页新载荷静默失效 + 滞留离页误跳是 P1 行为缺口）。
        assertTrue(
            "appViewModel.pendingToolsTab" in source &&
                ".filterNotNull()" in source,
            "ToolsPage 必须订阅预选槽流（批 4 复核 P1-1：palette 在页触发时槽载荷即时响应）",
        )
    }

    // ── 在页响应缝 · 工作台深链消费双 effect（批 4 复核 P1-1 配方外延）──

    @Test
    fun `log workbench page consumes deep link through dual effects for in-page arrivals`() {
        // 批 4 复核 P1-1 同款双 effect 配方照抄（ToolsPage 预选槽先例）：查看器
        // Dialog 挂 PageHome 层悬于任何页上方——工作台已是当前页时深链 offer 后
        // setCurrentPage 同值不发射（AnimatedContent 不重组），组合期
        // LaunchedEffect(Unit) 一次性消费不重跑，在页新载荷静默失效 + 滞留离页
        // 误投递（旧载荷错误上下文迟发上屏）是 P1 行为缺口。本锚钉死
        // LogWorkbenchPage 的双 effect 消费形态 + 回看分支零回归。
        val file = File(locateProjectRoot(), LOG_WORKBENCH_PAGE_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$LOG_WORKBENCH_PAGE_PATH（重命名/移动后请同步本守护）")
        val source = file.readText()
        assertTrue(
            "LogWorkbenchDeepLink.pending" in source &&
                ".filterNotNull()" in source,
            "LogWorkbenchPage 必须订阅深链槽流（批 4 复核 P1-1：工作台在页时深链载荷即时响应，不静默 no-op 滞留）",
        )
        assertTrue(
            source.lines().count { "LogWorkbenchDeepLink.consume()" in it } >= 2,
            "LogWorkbenchPage 深链消费必须双 consume 点：组合期初值一次性取走 + collect 内读即清" +
                "（缺一即回到单 effect 形态——初值消费丢则首进无预填，collect 内不清则离页返页重读旧载荷）",
        )
        assertTrue(
            "LaunchedEffect(Unit)" in source,
            "LogWorkbenchPage 组合期初值消费 LaunchedEffect(Unit) 必须保留（首次进页深链仍由一次性取走承担）",
        )
        // runId 回看分支零回归负锚：订阅路径共用同一落地分流，回看语义不得被
        // 双 effect 重构蒸发（报告卡「去工作台」唯一通道）。
        assertTrue(
            "vm.openRun(input.runId)" in source,
            "深链消费分流必须保留 runId 回看分支（openRun——报告卡回看深链唯一落点）",
        )
    }

    // ── 在页响应缝 · palette 衔接 seed 消费双 effect（ToolsPage 预选槽 /
    //    LogWorkbenchPage 深链同款配方的 palette 槽面）──

    @Test
    fun `command palette consumes handoff seed through dual effects for in-panel arrivals`() {
        // palette 是 focusable 浮层且函数体以 visible 早退：面板保持组合期间唯一
        // 悬于其上的生产面 = 查看器独立窗口（HUD chip 查看 / tools.view-log 打开
        // 的查看器三衔接动作）。其 offer 后宿主 open 同值写回 paletteVisible
        // （关→开重组不触发），LaunchedEffect(visible) 一次性消费不重跑，在页
        // 新 seed 静默失效 + 滞留槽内迟发旧参数表单（旧产物路径预填）是行为缺口。
        // 本锚钉死 CommandPalette 的双 effect 消费形态 + 打开瞬间路径零回归。
        val source = commandPaletteSource()
        assertTrue(
            "TaskPipelineHandoff.pendingSeed" in source &&
                ".filterNotNull()" in source,
            "CommandPalette 必须订阅衔接 seed 槽流（在页响应缝：面板已开时查看器窗口 offer 的 seed 即时消费，不滞留迟发）",
        )
        assertTrue(
            source.lines().count { "TaskPipelineHandoff.consumeSeed()" in it } >= 2,
            "CommandPalette 衔接消费必须双 consumeSeed 点：打开瞬间初值一次性取走 + collect 内读即清" +
                "（缺一即回到单 effect 形态——初值丢则首开无预填，collect 内不清则重开重读旧载荷）",
        )
        assertTrue(
            "LaunchedEffect(visible)" in source,
            "CommandPalette 打开瞬间初值消费 LaunchedEffect(visible) 必须保留（关→开全新组合仍由初值承载承担）",
        )
        // 落地分流共用负锚：双消费路径必须共用同一 applyHandoffSeed 分流
        //（订阅路径不得另造第二套进表单分支——双分支漂移即行为分叉）。
        assertEquals(
            2,
            source.lines().count { "applyHandoffSeed(seed)" in it },
            "CommandPalette 双消费路径必须各经一次 applyHandoffSeed(seed) 共用落地分流（打开瞬间承载 + 订阅 collect）",
        )
        // id 解析失败回落列表态负锚：分流内目录解析须为 firstOrNull 静默回落
        //（目标命令改名/退役不炸面板）。
        assertTrue(
            "ParamCommandCatalog.all().firstOrNull { it.id == seed.commandId }" in source,
            "applyHandoffSeed 分流必须经 firstOrNull 静默回落列表态（id 漂移不炸面板——契约零回归）",
        )
    }

    // ── 在页响应缝 · 库面板深链载荷消费双 effect（ToolsPage 预选槽 /
    //    LogWorkbenchPage 深链 / palette 衔接 seed 同款配方的库面板槽面）──

    @Test
    fun `kb manage panel consumes deep link payload through dual effects for in-tab arrivals`() {
        // 用户已停在蒸馏签（KbManagePanelContent 保持组合）时点 palette「知识库
        // 管理」候选行：KbManagePanelHook.offer 后宿主 setCurrentPage /
        // pendingAgentCenterTab 双同值不发射（AnimatedContent 不重组），组合期
        // LaunchedEffect(Unit) 一次性消费不重跑，在签新载荷静默失效 + 滞留槽内
        // 切签返签迟发旧展开是行为缺口。本锚钉死 KbManagePanelContent 的双
        // effect 消费形态 + 落签初值承载零回归。
        val source = anchorSource(KB_MANAGE_PANEL_PATH)
        assertTrue(
            "KbManagePanelHook.pending" in source &&
                ".filter { it }" in source,
            "KbManagePanelContent 必须订阅深链载荷槽流（在签响应缝：蒸馏签组合期间 palette 候选 offer 的载荷即时消费，不滞留迟发）",
        )
        assertTrue(
            source.lines().count { "KbManagePanelHook.consume()" in it } >= 2,
            "KbManagePanelContent 深链消费必须双 consume 点：组合期初值一次性取走 + collect 内读即清" +
                "（缺一即回到单 effect 形态——初值丢则落签无展开，collect 内不清则切签返签重读旧载荷）",
        )
        assertTrue(
            "androidx.compose.runtime.LaunchedEffect(Unit)" in source,
            "KbManagePanelContent 组合期初值消费 LaunchedEffect(Unit) 必须保留（落签首组合深链仍由初值承载承担）",
        )
        // 展开落地分流共用负锚：双消费路径只置位同一 kbPanelDeepLink 标记，落地
        // 等待 kbStats 就绪由单一 effect 统一承担（订阅路径不得另造第二套展开分支
        // ——双分支漂移即行为分叉）。
        assertTrue(
            "androidx.compose.runtime.LaunchedEffect(kbPanelDeepLink, stats)" in source,
            "双消费路径必须共用 kbPanelDeepLink 展开落地分流（等 kbStats 就绪单一 effect——语义单一来源）",
        )
    }

    // ── 在页响应缝 · 报告页聚焦槽消费双 effect（ToolsPage 预选槽 / LogWorkbenchPage
    //    深链 / palette 衔接 seed / 库面板载荷同款配方的报告卡直达槽面）──

    @Test
    fun `page report consumes focus request through dual effects for in-page arrivals`() {
        // palette 未读产物行点名具体 run → 报告卡直达定位：PageReport 双 effect 消费
        //（组合期初值 + 在页订阅读即清——四面同款配方）+ palette 三处投递点同语义 +
        // 点名卡展开/滚动定位/微洗底 2s 自清。
        val source = anchorSource(PAGE_REPORT_PATH)
        assertTrue(
            "ReportFocusRequest.pending" in source && ".filterNotNull()" in source,
            "PageReport 必须订阅聚焦槽流（在页响应缝：报告页已组合时 palette 未读行 offer 即时消费，不滞留迟发）",
        )
        assertTrue(
            source.lines().count { "ReportFocusRequest.consume()" in it } >= 2,
            "PageReport 聚焦消费必须双 consume 点：组合期初值一次性取走 + collect 内读即清" +
                "（缺一即回到单 effect 形态——初值丢则首进无定位，collect 内不清则重开重读旧载荷）",
        )
        assertTrue(
            "LaunchedEffect(Unit)" in source,
            "PageReport 组合期初值消费 LaunchedEffect(Unit) 必须保留（首进页聚焦仍由一次性取走承担）",
        )
        // 投递面：palette 检索态 Enter/行点击 + 空态首页行三处同语义（漏一处 = 该入口点名失效）
        assertEquals(
            2,
            commandPaletteSource().lines().count { "ReportFocusRequest.offer(entry.run.id)" in it },
            "palette Enter/行点击两分支必须各投递聚焦槽（报告卡直达锚）",
        )
        assertTrue(
            "ReportFocusRequest.offer(run.id)" in paletteEntriesSource(),
            "空态首页未读产物行必须投递聚焦槽（推荐行点名与检索态行同语义）",
        )
        // 聚焦落地：点名卡展开 + 滚动定位 + 微洗底 2s 自清
        assertTrue(
            "LaunchedEffect(focused)" in source,
            "点名卡必须首组展开（focused 一次性闩——洗底退场不回折）",
        )
        assertTrue(
            "listState.scrollToItem(index)" in source,
            "聚焦定位必须滚动到点名卡（用户点名不做二次肉眼定位）",
        )
        assertTrue(
            "delay(2_000)" in source,
            "微洗底必须 2s 自清（高亮残留即误导）",
        )
    }

    // ── GLB-05 · tab Auto-hide 顶部让位联动回收 ──

    @Test
    fun `page home insets top padding reacts to open tabs emptiness`() {
        // GLB-05 源码锚：PageHome 的 LocalScaffoldGlassInsets 提供点必须响应式——
        // openTabs 空集（tab 条 Auto-hide 隐藏）→ contentViewportPaddingTopNoTabs(8dp)
        // / 非空 → contentViewportPaddingTop(50dp)；openTabs 状态机由
        // AppViewModelOpenTabsTest 实跑背书（空集常态/文档页追加/关 tab 回空）。
        // 两 accept 均几何态（页头紧贴 8dp 起 / 首开 tab 平滑扩回），视觉验证留终验。
        val file = File(locateProjectRoot(), PAGE_HOME_PATH)
        assertTrue(file.isFile, "源码锚文件缺失：$PAGE_HOME_PATH（重命名/移动后请同步本守护）")
        val source = file.readText()
        assertTrue(
            "appViewModel.openTabs.collectAsState()" in source,
            "PageHome 必须订阅 openTabs（GLB-05 响应式接线——此前 insets 顶让位算死）",
        )
        assertTrue(
            "if (openTabs.isEmpty())" in source,
            "PageHome 顶让位必须按 openTabs 空集分流（GLB-05：空 → 8dp / 非空 → 50dp）",
        )
        assertTrue(
            "Dimens.contentViewportPaddingTopNoTabs" in source,
            "空集分支必须引用 contentViewportPaddingTopNoTabs token（GLB-05 新 token）",
        )
        assertFalse(
            "top = Dimens.contentViewportPaddingTop," in source,
            "顶让位不得再恒算死 50dp（GLB-05：恒让位在 tab 条隐藏后是 42dp 空气带）",
        )
    }

    // ── GLB-06 · palette 未读产物行改跳报告页 ──

    @Test
    fun `palette unread report row navigates to the report page`() {
        // 行文案是报告文件名（报告: <file>.md），点击却落工作台家是语义欺骗——
        // 落点改 ToolPage.Report（报告卡列表 = 此行落地页；palette onNavigate 无参
        // 载荷，runId 深链由 DOC-01 报告卡侧单点承接，不扩本通道）。
        // TL-02：onNavigate 签名扩双参（第二参 = 深入模式预选载荷），
        // 报告行走 (ToolPage.Report, null)——无预选、落点不变。
        // 未读产物行定义随 palette 尾部卫星迁至 PaletteEntries.kt。
        val unreadBlock = paletteEntriesSource()
            .substringAfter("未读产物（RunStore reportPath 非 null 最近 3）")
            .substringBefore("if (!emptyState.loaded)")
        assertTrue(
            "onNavigate(ToolPage.Report, null)" in unreadBlock,
            "palette 未读产物行必须跳报告页（GLB-06：报告卡列表语义即此行落地页）",
        )
        assertFalse(
            "ToolPage.LogWorkbench" in unreadBlock,
            "palette 未读产物行不得再跳工作台家（GLB-06：点「报告 X.md」得工作台是语义欺骗）",
        )
    }

    // ── 页注册唯一性（按 page 查找的消费面恒静默取首）──

    @Test
    fun `all registered pages are unique across the full registration table`() {
        // 消费面按 page 查找一律 firstOrNull 静默取首（content 分发 = PageHomeContent、
        // sideBar = ToolPageRegistry.sideBarOf、navItem = WorkspaceTabs/PaletteEntries/
        // AppStatusBar 三面）：两个注册项撞 page 时，后一份的 content/sideBar/navItem
        // 不报错、不合并、直接蒸发。唯一性必须在入口处拦截——断言取全量注册表
        // （feature 拼接段 + 宿主结构页），防只断言子集时新增拼接段静默漏检。
        assertRegisteredPagesUnique(fullRegistrationPages())
    }

    @Test
    fun `page uniqueness assertion has teeth`() {
        // 人造撞 page 样例走与真实注册面完全相同的断言路径必须红——
        // 防断言逻辑漂移后吞重复静默放过
        assertFailsWith<AssertionError>("撞 page 样例必须走红断言路径") {
            assertRegisteredPagesUnique(
                listOf(ToolPage.Setting, ToolPage.Setting, ToolPage.Report),
            )
        }
    }

    /** 全量注册表 page 唯一断言的收敛点（真实注册面与牙测试共用同一路径） */
    private fun assertRegisteredPagesUnique(pages: List<ToolPage>) {
        val duplicates = pages.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(
            duplicates.isEmpty(),
            "全量注册表 page 值必须唯一：按 page 查找的消费面静默取首，撞 page 的第二份" +
                "注册项（content/sideBar/navItem）静默蒸发。重复 page：$duplicates",
        )
    }

    /**
     * 全量注册表（ToolPageRegistry.registrations 公开入口 = feature 拼接段 +
     * 宿主 Setting/Report/Debug）取 page 列表后立即 cancel VM。
     *
     * 契约：AppViewModel 构造期订阅全局信号流，测试不 cancel 会滞留订阅者、
     * 把后续测试的信号重复落盘到彼时全局 store（跨测试污染）——
     * 配方与 ToolPageRegistryNavTest.registrationsAndCancel 同款。
     */
    private fun fullRegistrationPages(): List<ToolPage> = AppViewModel().let { vm ->
        try {
            ToolPageRegistry.registrations(appViewModel = vm).map { it.page }
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ── 源码锚工具（缺失即红——重命名/移动后须同步本守护路径） ──

    private fun pageHomeMenuSource(): String = anchorSource(PAGE_HOME_MENU_PATH)

    private fun commandPaletteSource(): String = anchorSource(COMMAND_PALETTE_PATH)

    /** palette 候选行/空态卫星源（未读产物行等尾部件所在） */
    private fun paletteEntriesSource(): String = anchorSource(PALETTE_ENTRIES_PATH)

    private fun anchorSource(relPath: String): String {
        val file = File(locateProjectRoot(), relPath)
        assertTrue(file.isFile, "源码锚文件缺失：$relPath（重命名/移动后请同步本守护）")
        return file.readText()
    }

    private companion object {
        const val PAGE_HOME_MENU_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageHomeMenu.kt"
        const val COMMAND_PALETTE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/CommandPalette.kt"
        const val PALETTE_ENTRIES_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteEntries.kt"
        const val PALETTE_HUD_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PaletteHud.kt"
        const val TOOLS_PAGE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/tools/ToolsPage.kt"
        const val PAGE_HOME_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageHome.kt"
        const val AGENT_CENTER_PAGE_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/agentcenter/AgentCenterPage.kt"
        const val LOG_WORKBENCH_PAGE_PATH =
            "feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logworkbench/LogWorkbenchPage.kt"
        const val KB_MANAGE_PANEL_PATH =
            "feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/distill/KbManagePanel.kt"
        const val PAGE_HOME_CONTENT_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageHomeContent.kt"
        const val PAGE_REPORT_PATH =
            "composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/home/PageReport.kt"
    }
}
