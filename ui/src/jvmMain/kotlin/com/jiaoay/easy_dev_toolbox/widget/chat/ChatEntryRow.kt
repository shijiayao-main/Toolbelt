package com.jiaoay.easy_dev_toolbox.widget.chat

import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ErrorKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.TodoChipStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.evidence.TodoBoardPanel

/**
 * 消息流条目行（无气泡平铺 + 左侧 2px 角色色条；桌面工具语言）：
 *
 * - User：左 primary 色条 + 文本 + 附件文件名 chips（等宽小字）；
 * - Assistant：左 surface 色条 + 文本（streaming 时尾注「▍」光标符）；
 * - ToolCall：左 primaryVariant 色条 + [ToolCallCard]；
 * - Activity：无色条，直接 [ActivityChip]（左对齐内缩）；
 * - ErrorBanner：error/outline 色条 + 标题 + 可折叠详情；
 * - TodoBoardSnapshot：计划板快照（[TodoBoardPanel] 工作台同件——双件统一）。
 *
 * 路径/参数/observation 一律 Monospace（桌面工具可读性语言）。
 */
@Composable
fun ChatEntryRow(
    entry: ChatEntry,
    onOpenArtifact: (String) -> Unit,
) {
    when (entry) {
        is ChatEntry.User -> UserRow(entry)
        is ChatEntry.Assistant -> AssistantRow(entry)
        is ChatEntry.ToolCall -> ToolCallRow(entry, onOpenArtifact)
        is ChatEntry.Activity -> Row(modifier = Modifier.padding(start = Dimens.space16)) {
            ActivityChip(entry = entry)
        }

        is ChatEntry.ErrorBanner -> ErrorRow(entry)
        is ChatEntry.TodoBoardSnapshot -> TodoBoardSnapshotRow(entry)
    }
}

/** 左色条 + 内容的统一行壳（色条 = 角色身份的低饱和标识） */
@Composable
private fun BarRow(
    barColor: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    val surface = LocalToolboxSurfaceColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.space2),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Spacer(
            modifier = Modifier
                .width(Dimens.space2)
                .height(Dimens.space24)
                .background(color = barColor, shape = RectangleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun UserRow(entry: ChatEntry.User) {
    val surface = LocalToolboxSurfaceColors.current
    BarRow(barColor = MaterialTheme.colorScheme.primary) {
        if (entry.text.isNotBlank()) {
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = surface.onSurfaceStrong,
            )
        }
        if (entry.attachments.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                entry.attachments.forEach { name ->
                    Text(
                        // 附件名直读（禁 emoji 代图标——DL 条款；附件名 monoTag）
                        text = pathFileName(name),
                        style = DesktopTypography.monoTag,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantRow(entry: ChatEntry.Assistant) {
    val surface = LocalToolboxSurfaceColors.current
    BarRow(barColor = surface.borderSubtle) {
        Text(
            text = if (entry.streaming) "${entry.text}▍" else entry.text,
            style = MaterialTheme.typography.bodyMedium,
            color = surface.onSurfaceStrong,
        )
    }
}

@Composable
private fun ToolCallRow(entry: ChatEntry.ToolCall, onOpenArtifact: (String) -> Unit) {
    BarRow(barColor = MaterialTheme.colorScheme.primaryContainer) {
        ToolCallCard(entry = entry, onOpenArtifact = onOpenArtifact)
    }
}

@Composable
private fun ErrorRow(entry: ChatEntry.ErrorBanner) {
    val surface = LocalToolboxSurfaceColors.current
    val color = when (entry.kind) {
        ErrorKind.TIMEOUT, ErrorKind.FAILED -> MaterialTheme.colorScheme.error
        ErrorKind.CANCELLED -> surface.onSurfaceFaint
    }
    BarRow(barColor = color) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        entry.detail?.let { detail ->
            Text(
                text = detail,
                style = DesktopTypography.mono,
                color = surface.onSurfaceMid,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 会话内计划板快照行（与工作台 TodoBoardPanel 双件统一）：
 * 渲染全权委托 [com.jiaoay.easy_dev_toolbox.widget.evidence.TodoBoardPanel]
 * （工作台 TodoBoard 计划投影同一件——会话内 todo 投影与工作台同件同形态）；
 * 原同文件私有件（raised 底 + 6dp 状态点形态）退役。
 *
 * 类型映射：[ChatEntry.TodoBoardSnapshot.items] 是 [TodoChip]（TodoChipStatus——
 * 消息流快照投影），TodoBoardPanel 吃 [com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoItem]
 * （TodoStatus——计划板 state 面）；三枚状态语义一一对应，消费点 1:1 换字面映射。
 */
@Composable
private fun TodoBoardSnapshotRow(entry: ChatEntry.TodoBoardSnapshot) {
    TodoBoardPanel(
        items = entry.items.map { chip ->
            com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoItem(
                content = chip.content,
                status = when (chip.status) {
                    TodoChipStatus.PENDING -> com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoStatus.PENDING
                    TodoChipStatus.IN_PROGRESS -> com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoStatus.IN_PROGRESS
                    TodoChipStatus.DONE -> com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoStatus.DONE
                },
            )
        },
    )
}
