package com.jiaoay.easy_dev_toolbox.page.home

import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.navigation.SideBarContribution
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavItem
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavSection
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.agentcenter.AgentCenterPage
import com.jiaoay.easy_dev_toolbox.page.ai.AiContributions
import com.jiaoay.easy_dev_toolbox.page.ai.AiToolsToggle
import com.jiaoay.easy_dev_toolbox.page.archive.ArchiveContributions
import com.jiaoay.easy_dev_toolbox.page.debug.ShellDebugPage
import com.jiaoay.easy_dev_toolbox.page.deviceobs.DeviceObsPage
import com.jiaoay.easy_dev_toolbox.page.larkbridge.LarkBridgeContributions
import com.jiaoay.easy_dev_toolbox.page.logkit.LogKitContributions
import com.jiaoay.easy_dev_toolbox.page.logworkbench.LogWorkbenchContributions
import com.jiaoay.easy_dev_toolbox.page.logworkbench.LogWorkbenchDeepLink
import com.jiaoay.easy_dev_toolbox.page.logworkbench.WorkbenchSession as LogWorkbenchSessionActivity
import com.jiaoay.easy_dev_toolbox.page.mcp.McpContributions
import com.jiaoay.easy_dev_toolbox.page.quest.FlowContributions
import com.jiaoay.easy_dev_toolbox.page.quest.QuestSession
import com.jiaoay.easy_dev_toolbox.page.settings.PageSettings
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsContributions
import com.jiaoay.easy_dev_toolbox.page.timestamp.TimestampContributions
import com.jiaoay.easy_dev_toolbox.page.tools.ToolsPage
import com.jiaoay.easy_dev_toolbox.page.voice.VoiceContributions
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 页面注册装配点（宿主单点）：feature 模块的 contributions + 宿主自有页
 * （设置 / 报告 / 调试，navItem = null 不进工具分组）在此拼接。
 * Default 欢迎页注册项已删（枚举值同删——启动直达工作台家）。
 * 应用中心页退役——浏览/搜索/直达全部经命令面板
 * （Ctrl+K）承接；ToolPage.AppCenter 枚举值三段式保留（注册项已删，页不可达）。
 * AI 聚合批：Flow/Distill/VoiceTranscribe navItem 退役（三段式），
 * AI 三页聚合为智能体中心（AgentCenter——宿主自有聚合页，**破例**带 navItem
 * 进 featureRegistrations 拼接序以自动获得全部消费链）。
 *
 * 三个消费方：
 * - [registrations] → PageHomeContent 的 map 分发（组合上下文调用）；
 * - [navItems] → Ctrl+Tab 顺序表 / palette 导航候选（纯静态，任意上下文）；
 * - [navSections] → 分组导航数据（按域分组；状态栏面包屑不再消费——
 *   保留为未来分组卡/OnboardingCard 分组回归位，NavTest 分组不变量断言）。
 *
 * 新增 feature 配方：settings include → ToolPage 枚举加值 → 本表追加一行
 * （navItem 由 feature 的 Contributions 提供，分组与提示卡自动同步）。
 */
object ToolPageRegistry {

    /**
     * 日志工作台注册面：feature 互不依赖铁律下 aiEnabled 不由 feature 上送
     * （AiToolsToggle 真源在 feature-ai）——宿主拼接点 copy 注入（开关真源唯一；
     * 契约先例：「aiEnabled 流经契约上送，宿主不 import feature 内部」）。
     * navActivity 同点接线：WorkbenchSession.activity（研判运行态镜像）。
     */
    private val logWorkbenchRegistrations: List<ToolPageRegistration> =
        LogWorkbenchContributions.registrations.map { registration ->
            if (registration.page == ToolPage.LogWorkbench) {
                registration.copy(
                    aiEnabled = AiToolsToggle.enabled,
                    navActivity = LogWorkbenchSessionActivity.activity,
                )
            } else {
                registration
            }
        }

    /**
     * 语音转写注册面：feature 互不依赖铁律下 aiEnabled 不由 feature 上送
     * ——宿主拼接点 copy 注入（开关真源唯一；log-workbench 同款先例）。
     */
    private val voiceRegistrations: List<ToolPageRegistration> =
        VoiceContributions.registrations.map { registration ->
            if (registration.page == ToolPage.VoiceTranscribe) {
                registration.copy(aiEnabled = AiToolsToggle.enabled)
            } else {
                registration
            }
        }

    /**
     * 流程注册面：aiEnabled 宿主 copy 注入（voice 同款先例——
     * 流程页是 agent 会话页，AI 关闭时置灰引导）。
     * Flow navItem 已退役（注册项仍在——flowSideBarContribution
     * 槽位平移到 agentCenterRegistration，navActivity 保留供 Flow key 消费面查活动点）。
     */
    private val flowRegistrations: List<ToolPageRegistration> =
        FlowContributions.registrations.map { registration ->
            if (registration.page == ToolPage.Flow) {
                registration.copy(aiEnabled = AiToolsToggle.enabled)
            } else {
                registration
            }
        }

    /**
     * 设备观测空间注册面（宿主聚合页——AgentCenter 先例同配方）：:feature-logcat
     * 贡献 public 内容组件（LogcatPanel），本页承载页头与设备栏（:core 设备域
     * 单源 + :ui 共享渲染件）。desc 单源消费（PageHomeMenu.DEVICE_OBS_DESC——
     * dock tooltip 与 palette 候选同文）。
     */
    private val deviceObsRegistration: ToolPageRegistration = ToolPageRegistration(
        page = ToolPage.DeviceObservation,
        navItem = ToolNavItem(
            page = ToolPage.DeviceObservation,
            icon = MaterialSymbols.MONITOR,
            label = "设备观测",
            desc = DEVICE_OBS_DESC,
        ),
        group = com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup.TOOLS,
    ) {
        DeviceObsPage()
    }

    private val toolsRegistration: ToolPageRegistration = ToolPageRegistration(
        page = ToolPage.Tools,
        navItem = ToolNavItem(
            page = ToolPage.Tools,
            icon = MaterialSymbols.HANDYMAN,
            label = "工具",
            // desc 单源消费（PageHomeMenu.TOOLS_DESC）——palette 候选与 dock tooltip
            // 同文，勿各持字面量（desc 全列子签标签保 palette 搜索直达）
            desc = TOOLS_DESC,
        ),
        group = com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup.TOOLS,
    ) {
        ToolsPage()
    }

    private val agentCenterRegistration: ToolPageRegistration = ToolPageRegistration(
        page = ToolPage.AgentCenter,
        navItem = ToolNavItem(
            page = ToolPage.AgentCenter,
            icon = MaterialSymbols.SMART_TOY,
            label = "智能体中心",
            // desc 单源消费（PageHomeMenu.AGENT_CENTER_DESC）——
            // palette 候选与 dock tooltip 同文，勿各持字面量
            desc = AGENT_CENTER_DESC,
        ),
        navActivity = QuestSession.activity,
        group = com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup.AGENTS,
        sideBar = listOf(FlowContributions.flowSideBarContribution),
    ) {
        AgentCenterPage()
    }

    /** 注册顺序 = Ctrl+Tab 顺序表与 palette 导航候选的顺序（与原 HomeNavRegistry 分组序一致） */
    private val featureRegistrations: List<ToolPageRegistration> =
        LogKitContributions.registrations +
            toolsRegistration +
            ShellToolsContributions.registrations +
            deviceObsRegistration +
            TimestampContributions.registrations +
            ArchiveContributions.registrations +
            logWorkbenchRegistrations +
            AiContributions.registrations +
            voiceRegistrations +
            flowRegistrations +
            agentCenterRegistration +
            LarkBridgeContributions.registrations +
            McpContributions.registrations

    fun registrations(appViewModel: AppViewModel): List<ToolPageRegistration> =
        featureRegistrations + listOf(
            // Default 欢迎页注册项已删（枚举值同删）——启动直达工作台家
            ToolPageRegistration(page = ToolPage.Setting, navItem = null) { PageSettings(appViewModel = appViewModel) },
            // 应用中心页退役：注册项删除，枚举值三段式保留——
            // 浏览/搜索/直达全部经命令面板（palette）承接
            // 报告页（tab 集第四成员——宿主自有页
            // navItem = null 不进导航面，只经 WorkspaceTabs tab 集可达）
            // 「去工作台」双参回调——runId 非 null（报告卡）
            // 时 offer 回看深链（进工作台即 restore 该 run）+ setCurrentPage 组合；
            // null（空态引导）保持只切页语义。
            ToolPageRegistration(page = ToolPage.Report, navItem = null) {
                PageReport(
                    onGoWorkbench = { runId ->
                        if (runId != null) {
                            LogWorkbenchDeepLink.offerRunReview(runId)
                        }
                        appViewModel.setCurrentPage(ToolPage.LogWorkbench)
                    },
                )
            },
            // 页头返回钮已退役——单开系统页（SINGLE_SESSION_PAGES）
            // 对齐设置/AI 设置/MCP 导航语义，dock/palette 切走即离开；onBack 注入链已删
            ToolPageRegistration(page = ToolPage.Debug, navItem = null) { ShellDebugPage() },
        )

    /**
     * 工具导航项（Ctrl+Tab 顺序表与 palette 导航候选共用；不含宿主自有页）。
     *
     * @param hiddenPages 运行时隐藏的页面集（来自各注册项的 [ToolPageRegistration.navVisible]，
     *        由宿主侧 [hiddenPagesOf] 归集）。默认空集 = 全部可见，故存量调用方行为不变。
     */
    fun navItems(hiddenPages: Set<ToolPage> = emptySet()): List<ToolNavItem> =
        featureRegistrations
            .filter { it.page !in hiddenPages }
            .mapNotNull { it.navItem }

    /**
     * 从注册项归集「当前应隐藏的页面」（[ToolPageRegistration.navVisible] 为 false 者）。
     *
     * 纯数据函数（不涉组合）：宿主侧在 Composition 内以 remember 缓存调用即可。
     */
    fun hiddenPagesOf(): Set<ToolPage> =
        featureRegistrations
            .filter { !it.navVisible.value }
            .map { it.page }
            .toSet()

    /** 参与可见性控制的注册项（供宿主侧订阅，见 [hiddenPagesOf]） */
    fun navVisibilitySources(): List<ToolPageRegistration> = featureRegistrations

    /** 带 AI 角标的注册项（aiEnabled 非 null 者；宿主侧置灰归集用） */
    fun aiEnabledSources(): List<ToolPageRegistration> =
        featureRegistrations.filter { it.aiEnabled != null }

    /** 带侧栏活动指示的注册项（navActivity 非 null 者；宿主侧活动归集用） */
    fun navActivitySources(): List<ToolPageRegistration> =
        featureRegistrations.filter { it.navActivity != null }

    // ── 槽位查询（宿主结构页无槽位/面板贡献——只查 feature 注册面）──

    /** 当前页的侧栏槽位贡献（页面作用域；空表 = 无槽位，宿主不渲染） */
    fun sideBarOf(page: ToolPage): List<SideBarContribution> =
        featureRegistrations.firstOrNull { it.page == page }?.sideBar ?: emptyList()

    fun toolActions(): List<com.jiaoay.easy_dev_toolbox.core.navigation.ToolAction> =
        featureRegistrations.flatMap { it.actions }

    fun paramCommands(): List<com.jiaoay.easy_dev_toolbox.core.navigation.ParamCommandSpec> =
        featureRegistrations.flatMap { it.paramCommands }


    fun navSections(hiddenPages: Set<ToolPage> = emptySet()): List<ToolNavSection> {
        val visible = featureRegistrations
            .filter { it.page !in hiddenPages }
        // 分组显示名（宿主单点）；null group（结构页）自动不进分组
        val groupTitles = mapOf(
            com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup.TOOLS to "工具",
            com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup.AGENTS to "智能体",
            com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup.ENTRY to "入口",
        )
        return com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup.entries.mapNotNull { group ->
            val sectionItems = visible.filter { it.group == group }.mapNotNull { it.navItem }
            if (sectionItems.isEmpty()) return@mapNotNull null
            ToolNavSection(title = groupTitles.getValue(group), items = sectionItems)
        }
    }
}
