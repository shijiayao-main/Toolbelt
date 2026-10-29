package com.jiaoay.easy_dev_toolbox.core.navigation

import androidx.compose.runtime.Composable

/**
 * 侧栏槽位贡献（P2 交互重设计「三舱骨架」；R 批右侧化重构）：页面把自己的
 * 「历史私有侧栏」（Shell 命令树 / Workbench run 列表与报告 / Quest 历史 / Distill 仓库 /
 * MCP 清单）经契约上提为应用级槽位——宿主统一渲染壳承载（右侧轨 + 展开面板，
 * 可拖宽/可折叠为窄轨），杜绝各页自写第四种实现。
 *
 * 生命周期语义：[content] 是**页面作用域**的组合内容——随页面 AnimatedContent
 *    切换销毁/重建（与 ToolPageRegistration.content 同生命周期），页面 VM 数据
 *    天然可达（S2 底面板退役后，跨页存续内容归 tab 容器/工作台块承载，勿混）。
 *
 * 形态定版：槽位统一渲染在窗口**最右缘**——
 *    [slot] 不再决定左/右位置，仅保留「同页多贡献的纵排顺序」语义。
 *
 * @param slot 同页多贡献时的纵排顺序（Start 在前 / End 在后）；不再决定渲染位置
 * @param icon 窄轨 icon（Material Symbol 蛇形名；空 = 不进窄轨、面板无小节标题）
 * @param label 窄轨 tooltip 文案（展开面板标题由内容自带小节标题，壳不渲染）
 * @param content 槽位内容（页面作用域组合）
 */
data class SideBarContribution(
    val slot: SideBarSlot = SideBarSlot.Start,
    val icon: String = "",
    val label: String = "",
    val content: @Composable () -> Unit,
)

/** 同页多贡献纵排序（R 批后不再决定渲染位置——锁两值，不提供第三席） */
enum class SideBarSlot { Start, End }
