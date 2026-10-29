package com.jiaoay.easy_dev_toolbox.widget.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 会话输入区（消息流组件族）：
 *
 * - 多行输入（Enter 发送 / Shift+Enter 换行——桌面聊天惯例）；
 * - 附件槽（本批只读显示——文件经拖入区进会话，Composer 文本 = 追问）；
 * - running 时发送钮翻「停止」钮（中止/取消需求 E 的 UI 入口）；
 * - S5c Steer 排队：running 时 Enter 走 [onQueue]（轮边界排队续发——null =
 *   静默吞键，非会话消费方零变化）。
 *
 * @param running 会话运行中（true = 停止钮，false = 发送钮）
 * @param enabled 输入可用（LLM 未配置 + 无本地能力时禁用）
 * @param onQueue S5c running 期 Enter 入队回调（null = 吞键——存量消费方零变化）
 */
@Composable
fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "输入消息，Enter 发送（Shift+Enter 换行）",
    onQueue: (() -> Unit)? = null,
) {
    val surface = LocalToolboxSurfaceColors.current

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.consoleHeightMin / 4, max = Dimens.composerHeightMax)
                .onPreviewKeyEvent { event ->
                    // 仅 KeyDown 生效：KeyUp 重入会双触发（发送双发/插话双条——被拒路径
                    // 尤甚；handler 首行 event.type 过滤是全仓键盘 handler 统一口径）
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Enter 发送（Shift+Enter 换行走默认行为）
                    if (event.key == Key.Enter && !event.isShiftPressed) {
                        if (text.isNotBlank() && !running && enabled) {
                            onSend()
                        } else if (text.isNotBlank() && running && enabled) {
                            // S5c Steer 排队：running 期 Enter = 插话入队（onQueue null 保持旧吞键形态）
                            onQueue?.invoke()
                        }
                        true // Enter 一律吞掉：空文本不产生换行
                    } else {
                        false
                    }
                },
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = surface.onSurfaceFaint,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            enabled = enabled,
            shape = com.jiaoay.easy_dev_toolbox.core.theme.AppShapes.medium,
            trailingIcon = {
                if (running) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(Dimens.iconDefault)) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.STOP_CIRCLE,
                            contentDescription = "停止",
                            size = Dimens.iconLarge,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = enabled && text.isNotBlank(),
                        modifier = Modifier.size(Dimens.iconDefault),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.SEND,
                            contentDescription = "发送",
                            size = Dimens.iconLarge,
                            tint = if (text.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                surface.onSurfaceFaint
                            },
                        )
                    }
                }
            },
        )
    }
}

/** 附件只读行（拖入文件后的名字回显——Composer 文本与文件是两个通道） */
@Composable
fun PendingAttachmentsRow(
    attachments: List<String>,
    onRemove: (String) -> Unit,
) {
    if (attachments.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.space4),
    ) {
        attachments.forEach { name ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                Text(
                    // 附件名直读（禁 emoji 代图标——DL 条款；附件名 monoTag）
                    text = pathFileName(name),
                    style = DesktopTypography.monoTag,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 命中区 = minimumInteractiveSize（32dp ≥ WCAG 2.2 目标 24px；图标仍
                // iconSmall 视觉档居中——16dp 命中区低于可达性下限不可入焦点链点击）
                IconButton(onClick = { onRemove(name) }, modifier = Modifier.size(Dimens.minimumInteractiveSize)) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.CLOSE,
                        contentDescription = "移除 ${pathFileName(name)}",
                        size = Dimens.iconSmall,
                        tint = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                    )
                }
            }
        }
    }
}
