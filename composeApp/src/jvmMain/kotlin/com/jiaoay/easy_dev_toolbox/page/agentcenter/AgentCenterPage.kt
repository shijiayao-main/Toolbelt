package com.jiaoay.easy_dev_toolbox.page.agentcenter

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.page.ai.distill.DistillPageContent
import com.jiaoay.easy_dev_toolbox.page.home.SpaceBar
import com.jiaoay.easy_dev_toolbox.page.quest.FlowPageContent
import com.jiaoay.easy_dev_toolbox.page.voice.VoicePageContent
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import kotlinx.coroutines.flow.filterNotNull

/**
 * 智能体中心聚合页：AI 功能不再分散多页——
 * 会话 / 知识蒸馏 / 语音转写 / 调查空间四页收拢为本页内的**子页签**切换
 * （AI 设置页独立保留——控制台性质，随时可进开开关，不入聚合；
 * 空间栏撤出主布局常驻轨，迁入本页第四签——空间与 AI 强相关，主区不再挂常驻 view）。
 *
 * ## 结构（LarkBridgePage 页内多页签先例同配方）
 * - [PageScaffold] 标题「智能体中心」（headerSticky 吸顶——content 获有界高度）+
 *   [SegmentedControl] 四签（页内域切换语言全应用统一）+ when 分发：前三签渲染
 *   三 feature 的**无头内容变体**
 *   （[FlowPageContent] / [DistillPageContent] / [VoicePageContent]——聚合页
 *   自有页头与 padding，子页不再自带 PageScaffold，消除双页头/双 padding）；
 *   第四签渲染 [SpaceBar]（调查空间栏迁入——状态面
 *   [com.jiaoay.easy_dev_toolbox.page.home.SpaceBarState] 自持 + 宿主
 *   currentSpaceId 回写闭环原样平移；页内形态宽轨由 weight 展开，非主布局左侧轨形态）；
 * - 子页签选中态 remember + AppViewModel 预选槽订阅合成（palette 深链候选/存量 last_page 恢复带签落子签；
 *   🔴 勿用 rememberSaveable——其进程内恢复态与槽订阅有竞态（恢复覆盖新载荷）；手动切签即清槽零残留）；
 */
@Composable
fun AgentCenterPage(
    appViewModel: AppViewModel = AppViewModel.getViewModel(),
) {
    // 子页签 index：0=会话（Flow）/ 1=知识蒸馏（Distill）/ 2=语音转写（Voice）/
    // 3=调查空间（迁入自主布局常驻轨）
    // rememberSaveable → remember——子签初值/新载荷改由
    // AppViewModel 预选槽订阅合成（ToolsPage 同因），saveable 的进程内恢复态
    // 与槽订阅竞态（恢复覆盖新载荷，预选语义失效）。
    var tab by remember { mutableStateOf(0) }

    // 在页响应缝（ToolsPage 同族）：palette 是浮层，用户停在
    //    聚合页时 AgentCenterPage 保持组合——此时经 palette 深链候选写入的预选槽值
    //    必须被订阅 effect 即时消费（手动切签不受深链残留影响：消费即清槽，槽空时
    //    collect 不发射，用户切签态原样保留）。订阅槽流读即清——离页返页时旧载荷
    //    不复活；越界值（不在 0..3，如手工注入）回 0（第 1 签防御）。
    LaunchedEffect(appViewModel) {
        appViewModel.pendingAgentCenterTab
            .filterNotNull()
            .collect { index ->
                // 读即清（consume 同款语义）：槽载荷只消费一次，手动切签零残留。
                appViewModel.consumePendingAgentCenterTab()
                tab = if (index in 0..3) index else 0
            }
    }

    PageScaffold(title = "智能体中心", headerSticky = true) {
        SegmentedControl(
            options = listOf("会话", "知识蒸馏", "语音转写", "调查空间"),
            selectedIndex = tab,
            onSelect = { tab = it },
        )
        // 出口指路（能力本体 ⇄ 能力出口 对称叙事）：本页聚合智能体能力本体，
        // 飞书桥接/MCP 服务是把能力递给外部消费者的出口。宿主页直调 setCurrentPage
        // （PageHomeMenu 同款直调先例；宿主页不触 feature 互不依赖约束，无需 :core hook）。
        AgentCenterExitLinks(onGo = { appViewModel.setCurrentPage(it) })
        // 子签切换动效（LarkBridgePage 同款配方——fadeIn standard + 温和上浮 +
        // fadeOut fast）；weight(1f) 有界高度（headerSticky 骨架配方），子页内容变体
        // 自带滚动/填充不受影响。
        // 🔴 滚动缺口纪律：verticalScroll **不上提容器**（会使 AnimatedContent 全分支无界化——
        //    Flow 签活跃流程时 ChatMessageList 的 LazyColumn、空间签 SpaceBar 的
        //    LazyColumn 均无 heightIn，无界测量直接 IllegalStateException 崩溃，
        //    TaskListSection.kt 注释钉死的同类崩溃先例）。滚动只包 voice 分支：
        //    唯一声明「滚动责任上移」的 embedded VoicePageContent 获得滚动，
        //    Flow/Distill（自带滚动结构）/SpaceBar（fillMaxHeight 有界）契约不破。
        Column(modifier = Modifier.weight(1f)) {
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(animationSpec = AppMotion.standard()) +
                        androidx.compose.animation.slideInVertically(animationSpec = AppMotion.standard()) { it / 16 })
                        .togetherWith(androidx.compose.animation.fadeOut(animationSpec = AppMotion.fast()))
                },
                label = "agentCenterTab",
            ) { currentTab ->
                when (currentTab) {
                    0 -> FlowPageContent()
                    1 -> DistillPageContent()
                    // voice 分支滚动责任在此（embedded 无头形态无自滚动）——
                    // 包在分支内 = 只该分支无界，其余分支有界契约保住
                    2 -> Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        VoicePageContent(embedded = true)
                    }
                    // 空间栏迁入：SpaceBar 原左轨形态 fillMaxHeight+spaceBarWidth，
                    // 页内第四签放开宽度 weight(1f) 展开为页形态（列表+归档+说明全能力）
                    else -> SpaceBar(appViewModel = appViewModel, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * 智能体中心页头出口指路行（弱化引导）：本页四签是能力本体，
 * 飞书桥接（聊天触达）/ MCP 服务（协议触达）是能力的对外出口——
 * 「本体/出口」从属关系在导航层可见（导航归组仍守 ENTRY 判据，不搬组）。
 */
@Composable
private fun AgentCenterExitLinks(
    onGo: (ToolPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Text(
            text = "这些能力也能从外部触达：",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AgentCenterExitLink(text = "飞书桥接（聊天）", page = ToolPage.LarkBridge, onGo = onGo)
        AgentCenterExitLink(text = "MCP 服务（协议）", page = ToolPage.McpExpose, onGo = onGo)
    }
}

/** 出口链接单件：文本链（CockpitPanel「全部记录 →」同款 indication=null 先例）。 */
@Composable
private fun AgentCenterExitLink(
    text: String,
    page: ToolPage,
    onGo: (ToolPage) -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onGo(page) },
    )
}
