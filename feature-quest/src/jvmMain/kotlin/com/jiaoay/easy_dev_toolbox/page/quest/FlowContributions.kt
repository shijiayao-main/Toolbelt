package com.jiaoay.easy_dev_toolbox.page.quest

import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.navigation.SideBarContribution
import com.jiaoay.easy_dev_toolbox.core.navigation.SideBarSlot
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.flow.StateFlow

/**
 * 流程域导航注册出口（Quest 域 change 2——openspec add-flow-page 任务 1.1）。
 *
 * 聚合页改道（AppCenter 先例同款）：
 * - **navItem 退役**——会话不再作为独立导航页出现（palette 导航候选/分组卡不展示），
 *   子页签经宿主 AgentCenterPage（智能体中心第一签）渲染；
 * - **content 注册保留**——枚举值保留（深入模式仍以页形态渲染）；
 * - **sideBar 槽位平移**——历史流程侧栏槽位迁往宿主 agentCenterRegistration
 *   （AgentCenter 是独立页，宿主 sideBarOf(Flow) 查不到本域槽位会丢侧栏）；
 *   本域注册项不再自带 sideBar（避免双份），槽位经 [flowSideBarContribution]
 *   公开上送宿主拼装（槽位内容 FlowSidePanelContent 是 internal，同模块直调，
 *   宿主只消费贡献对象不 import 内部 composable）；
 * - **存量兜底由宿主承接**：retiredPageLabel 中文兜底与 last_page 改道
 *   （组 B 同步）不在本域处理。
 *
 * S5a · dock RUN 徽标：navActivity 直引 [QuestSession.activity] 保留（宿主侧活动归集
 * 以 ToolPage.Flow 为 key 的消费面仍可查表——深入模式动态 tab 深达 Flow 时活动点亮；
 * 聚合入口 AgentCenter 的活动指示由宿主 agentCenterRegistration 同源直引）。
 */
object FlowContributions {

    init {
        // 任务 3.4：user.ask_user 时间线紧凑态可视化（全仓唯一装配点——register 幂等）
        QuestToolVisualizers.registerAll()
    }

    /** S5a · 会话运行态镜像（QuestSession companion attach——FlowViewModel enterFlow 挂接） */
    val flowNavActivity: StateFlow<NavActivity> = QuestSession.activity

    /**
     * 历史会话侧栏槽位贡献：本域注册项不再自带 sideBar——由宿主
     * agentCenterRegistration 消费。
     */
    val flowSideBarContribution: SideBarContribution = SideBarContribution(
        slot = SideBarSlot.Start,
        icon = MaterialSymbols.FORUM,
        label = "历史会话",
    ) { FlowSidePanelContent() }

    val registrations: List<ToolPageRegistration> = listOf(
        ToolPageRegistration(
            page = ToolPage.Flow,
            // navItem 退役——会话子页签经宿主
            // AgentCenterPage 渲染；navActivity 保留（dock「会话」槽位活动点）。
            navItem = null,
            group = ToolPageGroup.AGENTS,
            navActivity = flowNavActivity,
        ) {
            FlowPage()
        },
    )
}
