package com.jiaoay.easy_dev_toolbox.widget.evidence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoItem
import com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoStatus
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * TodoBoard 计划投影卡：agent 经 `write_todos` 写入的
 * 任务级规划板在证据流中栏的可视面——「AI 在干什么」的核心可解释件。
 *
 * 数据源 [TodoItem]/[TodoStatus]（:core TodoTools——计划放 state 不放消息流）；
 * 全量覆盖语义（write_todos 每次提交完整清单），本卡逐条状态随写即时更新
 * （StateFlow collect 直渲）。
 *
 * 配方（.todoregion + .chead 骨架）：
 * - **TonalCard 壳** + 头行（「计划」labelMedium + 计数 pill「N 项 · M 进行中」）；
 * - 每行 = 状态图标（iconSmall）+ content 文本（mono——日志语域）；
 *   - IN_PROGRESS = PROGRESS_ACTIVITY（primary 色）；
 *   - DONE = CHECK_CIRCLE（success 色）；
 *   - PENDING = CIRCLE（onSurfaceFaint——「Outlined 收件箱风格空圈」= circle
 *     字形自带描边语义，非实心）。
 *
 * **items 空 = 调用方门控零渲染**（LogWorkbenchPage `todos.isNotEmpty()`）；
 * 组件内部同款兜底（`return` 双保险——防御未来消费点漏门控）。
 */
@Composable
fun TodoBoardPanel(
    items: List<TodoItem>,
    modifier: Modifier = Modifier,
) {
    // 空 = 零渲染（组件内兜底——与调用方门控双保险）
    if (items.isEmpty()) return

    val colors = LocalEasyToolboxColors.current
    val inProgress = items.count { it.status == TodoStatus.IN_PROGRESS }

    TonalCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            // 卡题 fs-16/650
            Text(
                "计划",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
            )
            // chead 头行规格：头行补 pill ai「write_todos」工具源标识 +
            // 计数 PillLabel mute 档
            PillLabel(
                text = "write_todos",
                container = colors.aiAccentContainer,
                contentColor = colors.aiAccent,
            )
            Spacer(modifier = Modifier.weight(1f))
            PillLabel(
                text = "${items.size} 项 · $inProgress 进行中",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
            items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    modifier = Modifier.padding(top = Dimens.space2),
                ) {
                    AppSymbolIcon(
                        icon = when (item.status) {
                            TodoStatus.IN_PROGRESS -> MaterialSymbols.PROGRESS_ACTIVITY
                            TodoStatus.DONE -> MaterialSymbols.CHECK_CIRCLE
                            TodoStatus.PENDING -> MaterialSymbols.CIRCLE
                        },
                        contentDescription = null,
                        // .ico.tiny 13px
                        size = Dimens.iconTiny,
                        // 三态图标色统一 --info（三态 SVG 均 color:var(--info)）——
                        // IN_PROGRESS=primary/DONE=success 分色是规格外发挥，勿回填；
                        // 昼战坐 infoContainer 档（design-doc.md §5.3，dayBattleTint 图形 3.0 线）
                        tint = dayBattleTint(colors.info, colors.infoContainer),
                    )
                    Text(
                        item.content,
                        // fs-13 常规字（bodyMedium）非 mono——mono 是 kv/日志语域专用
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // 行尾状态 pill
                    // （DONE=success / IN_PROGRESS=info / PENDING=mute 灰）
                    PillLabel(
                        text = when (item.status) {
                            TodoStatus.IN_PROGRESS -> "IN_PROGRESS"
                            TodoStatus.DONE -> "DONE"
                            TodoStatus.PENDING -> "PENDING"
                        },
                        container = when (item.status) {
                            TodoStatus.DONE -> colors.successContainer
                            TodoStatus.IN_PROGRESS -> colors.infoContainer
                            TodoStatus.PENDING -> LocalToolboxSurfaceColors.current.surfaceDeep
                        },
                        contentColor = when (item.status) {
                            TodoStatus.DONE -> colors.success
                            TodoStatus.IN_PROGRESS -> colors.info
                            TodoStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
