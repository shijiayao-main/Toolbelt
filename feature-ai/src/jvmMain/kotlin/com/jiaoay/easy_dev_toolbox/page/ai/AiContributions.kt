package com.jiaoay.easy_dev_toolbox.page.ai

import dev.vicart.compose.material.symbols.MaterialSymbols

import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolNavItem
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageRegistration
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolPageGroup
import com.jiaoay.easy_dev_toolbox.core.navigation.ALWAYS_VISIBLE
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.ai.distill.DistillPage
import com.jiaoay.easy_dev_toolbox.page.ai.distill.DistillSession
import kotlinx.coroutines.flow.StateFlow

/**
 * AI 能力域的导航注册出口：宿主 ToolPageRegistry 单点拼接（模块化注册契约）。
 *
 * 本域注册 **AI 设置 + 知识蒸馏**（智能体组两成员）：
 * - AI 设置 = agent 域控制台（模型端点/记忆面板/源码目录/性格/安全约束）——恒可见，
 *   用户须随时可进来重新开启 AI 开关，故不上送 aiEnabled；
 * - 知识蒸馏 = P4-B 蒸馏引擎入口（两阶段：只读探索产计划卡 → 执行蒸馏审阅入库）。
 *
 * 聚合页改道（AppCenter 先例同款）：
 * - **知识蒸馏 navItem 退役**——不再作为独立导航页出现（palette 导航候选/分组卡
 *   不展示），子页签经宿主 AgentCenterPage（智能体中心第二签）渲染；
 * - **content 注册保留**（聚合页子页签与深入模式仍渲染 DistillPage）；
 * - aiEnabled / navActivity 上送**保留不动**（聚合入口不置灰前提下，
 *   aiEnabled 的置灰语义由子页与宿主 copy 注入面自持，本注册项契约面不收窄）；
 * - AI 设置注册项**完全不动**（独立页语义——控制台性质随时可进开开关）；
 * - **存量兜底由宿主承接**：retiredPageLabel 中文兜底与 last_page 改道（组 B 同步）
 *   不在本域处理。
 */
object AiContributions {

    private val settingsNavItem = ToolNavItem(
        page = ToolPage.AiSettings,
        // PSYCHOLOGY——与 dock/tab 兜底三面同字形（图标=页面指纹）；
        // SMART_TOY 保留给智能体中心（design-language.md §4.3 唯一锚，撞标清零）。
        icon = MaterialSymbols.PSYCHOLOGY,
        label = "AI 设置",
        desc = "配置 OpenAI 兼容端点与 agent 记忆，供飞书智能调度等 AI 能力调用",
    )

    /** 蒸馏运行态（S3 侧栏活动指示——DistillViewModel attach 镜像；首版无运行前恒 Idle） */
    val distillNavActivity: StateFlow<NavActivity> = DistillSession.activity

    val registrations: List<ToolPageRegistration> = listOf(
        ToolPageRegistration(
            page = ToolPage.AiSettings,
            navItem = settingsNavItem,
            group = ToolPageGroup.AGENTS,
        ) {
            AiSettingsPage()
        },
        ToolPageRegistration(
            page = ToolPage.Distill,
            // navItem 退役——知识蒸馏子页签
            // 经宿主 AgentCenterPage 渲染；aiEnabled/navActivity 上送保留（守恒最小改）。
            navItem = null,
            group = ToolPageGroup.AGENTS,
            aiEnabled = AiToolsToggle.enabled,
            navActivity = distillNavActivity,
        ) {
            DistillPage()
        },
    )
}
