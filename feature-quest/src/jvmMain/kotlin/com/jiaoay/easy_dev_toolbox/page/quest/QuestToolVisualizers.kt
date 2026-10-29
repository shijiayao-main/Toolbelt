package com.jiaoay.easy_dev_toolbox.page.quest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskUserTool
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.chat.ToolVisualizerRegistry
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * user.ask_user 时间线紧凑态可视化（Quest 域 change 2——openspec add-flow-page 任务 3.4）。
 *
 * 与表单卡（FlowPage AskCard——固定 Composer 上方的交互面）分工：时间线内只留**摘要**
 * ——RUNNING = 「等待补充」提示（真表单在下方），终态 = 问答摘要（observation 软反馈
 * 文案——AskUserTool.renderAnswer 产物）。零 ChatEntry 新类型。
 *
 * 注册点：[FlowContributions] object init（全仓唯一装配点纪律——register 是 map put
 * 幂等，多次装配无副作用）。
 */
object QuestToolVisualizers {

    fun registerAll() {
        ToolVisualizerRegistry.register(AskUserTool.NAME, ToolVisualizerRegistry.ToolVisualizer { entry ->
            AskUserCompactCard(entry)
        })
    }
}

/** 紧凑态：图标 + 状态文案 + 终态 observation 摘要（单行截断）。 */
@Composable
private fun AskUserCompactCard(entry: ChatEntry.ToolCall) {
    TonalCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.space2),
        shape = AppShapes.small,
        showBorder = false,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSymbolIcon(
                icon = when (entry.state) {
                    ToolState.RUNNING -> MaterialSymbols.NOTIFICATIONS_ACTIVE
                    else -> MaterialSymbols.FORUM
                },
                contentDescription = null,
                size = Dimens.iconSmall,
            )
            Column(modifier = Modifier.padding(start = Dimens.space8)) {
                Text(
                    text = when (entry.state) {
                        ToolState.RUNNING -> "向你提问中——请在下方表单补充"
                        ToolState.COMPLETED -> entry.observation ?: "已收到你的回答"
                        ToolState.FAILED -> "提问失败：${entry.observation ?: ""}"
                        ToolState.CANCELLED -> "已中止提问"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.state) {
                        ToolState.RUNNING -> MaterialTheme.colorScheme.primary
                        ToolState.FAILED, ToolState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                )
            }
        }
    }
}
