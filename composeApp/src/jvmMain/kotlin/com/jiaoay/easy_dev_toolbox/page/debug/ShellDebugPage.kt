package com.jiaoay.easy_dev_toolbox.page.debug

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import dev.vicart.compose.material.symbols.MaterialSymbols
import com.jiaoay.easy_dev_toolbox.widget.VerticalScrollbarSlot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellOutputSource
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.EasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.onEnterKey

/** 贴底跟随像素容差：判定「最后一可见项底边接近视口底边」时吸收亚像素/微偏移 */
private const val BOTTOM_FOLLOW_TOLERANCE_PX = 8

@Composable
fun ShellDebugPage(
    // onBack 参数已随页头返回钮退役（单开系统页导航对齐——dock/palette 切走即离开）
    viewModel: ShellDebugViewModel = ShellDebugViewModel.getViewModel(),
) {
    val currentMode by viewModel.currentMode.collectAsState()
    // 🔴 贴底跟随：列表封顶后 size 恒为上限 ⇒ LaunchedEffect(size) 永不重启。
    //    改用追加序号 version 做 key（每次追加必变）。
    val messagesVersion by viewModel.messagesVersion.collectAsState()
    val simpleMessages by viewModel.simpleMessages.collectAsState()
    val interactiveMessages by viewModel.interactiveMessages.collectAsState()
    val isSendEnabled by viewModel.isSendEnabled.collectAsState()
    val isSessionActive by viewModel.isSessionActive.collectAsState()
    val switchBlockedMessage by viewModel.switchBlockedMessage.collectAsState()
    // 控制台语义色（consoleBg/consoleText/logError）——恒深底日志台正源
    val toolboxColors = LocalEasyToolboxColors.current

    val messages = when (currentMode) {
        DebugShellMode.Simple -> simpleMessages
        DebugShellMode.Interactive -> interactiveMessages
    }

    var commandInput by remember { mutableStateOf("") }
    var startCommandInput by remember { mutableStateOf("adb shell") }
    val listState = rememberLazyListState()

    // 追加时自动滚底（scrollToItem 即时跳转，零动画铁律）。
    // 本 effect 执行时 layoutInfo 仍是旧布局快照（新追加条目尚未布局），索引容差判定
    // （lastVisible == last - 1 || lastVisible == last）在单批追加 ≥2 条时恒假，跟随会永久停摆；
    // 改用稳健贴底判定：
    // 1) canScrollForward == false：旧快照上用户确实贴底（不能再向前滚），批量追加也跟随；
    // 2) 像素判定补充：最后一可见项底边接近视口底边（px 容差吸收亚像素/微偏移）。
    // 用户上翻查看历史（两判定均不满足）或手动滚动中不跟随，避免劫持视点。
    LaunchedEffect(messagesVersion) {
        val last = messages.lastIndex
        if (last >= 0 && !listState.isScrollInProgress) {
            // 贴底判定：不能继续向前滚（旧布局快照上用户处于底部），
            // 或最后一可见项底边接近视口底边（容差吸收亚像素/微偏移）
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            val atBottom = !listState.canScrollForward ||
                (lastVisible != null &&
                    lastVisible.offset + lastVisible.size >= info.viewportEndOffset - BOTTOM_FOLLOW_TOLERANCE_PX)
            if (atBottom) listState.scrollToItem(last)
        }
    }

    // 模式切换（Simple↔Interactive）后显式跳底：切换瞬间跟随判定基于另一模式列表的
    // 旧布局快照，可能不满足贴底条件而不跳底；以 currentMode 为 key 重启 effect，
    // 直接跳到新列表末尾（scrollToItem 零动画）
    LaunchedEffect(currentMode) {
        val last = messages.lastIndex
        if (last >= 0) listState.scrollToItem(last)
    }

    LaunchedEffect(switchBlockedMessage) {
        val msg = switchBlockedMessage
        if (msg != null) {
            // Snackbar 统一走 StatusSignals 信号面（全仓唯一 Snackbar 入口，勿自建 SnackbarHost）
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(msg)
            viewModel.clearSwitchBlockedMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 页头 Switch/文字对已退役——二选一模式互斥语义不用开/关设置态
        // 控件表达，双段胶囊分段控件迁 content 顶部（actions 清空）
        PageScaffold(
            title = "Shell 调试",
        ) {
            // 模式切换：双选项胶囊分段——index→枚举映射后调 switchMode，
            // 会话活跃拒绝链零动（switchBlockedMessage → StatusSignals 现链）
            SegmentedControl(
                options = listOf("单次执行", "保持会话"),
                selectedIndex = if (currentMode == DebugShellMode.Interactive) 1 else 0,
                onSelect = { index ->
                    val target =
                        if (index == 1) DebugShellMode.Interactive else DebugShellMode.Simple
                    viewModel.switchMode(target)
                },
            )

            // InteractiveShell session controls
            if (currentMode == DebugShellMode.Interactive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    OutlinedTextField(
                        value = startCommandInput,
                        onValueChange = { startCommandInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("启动命令") },
                        singleLine = true,
                        enabled = !isSessionActive,
                    )
                    if (isSessionActive) {
                        Button(
                            onClick = { viewModel.closeSession() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                        ) {
                            AppSymbolIcon(icon = MaterialSymbols.STOP, contentDescription = "关闭")
                            Spacer(modifier = Modifier.width(Dimens.space4))
                            Text("关闭")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startSession(startCommandInput) },
                            enabled = startCommandInput.isNotBlank(),
                        ) {
                            AppSymbolIcon(icon = MaterialSymbols.PLAY_ARROW, contentDescription = "启动")
                            Spacer(modifier = Modifier.width(Dimens.space4))
                            Text("启动")
                        }
                    }
                }
            }

            // 输出台工具行：挂输出容器卡头——清空只清当前模式列表，
            // 复制全部惰性 join（lineText 单源：source 标签 + content，id 不入文本）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                Text(
                    text = "输出台",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (messages.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearMessages) {
                        Text("清空")
                    }
                    // 复制钮带可见文字（icon-only 是最轻实现偏差，规格钉带字形态）；
                    // 惰性取值重载：流式输出每条新行/每次重组不得重拼 5000 行全量串
                    CopyButton(
                        textProvider = { messages.joinToString("\n") { it.lineText() } },
                        enabled = messages.isNotEmpty(),
                    )
                }
            }

            // Chat messages（右缘桌面滚动条）
            // 输出区是日志/控制台语义面——恒深底 + mono。
            // 深底 background 以 messages.isNotEmpty() 门控：空态 item 也在 LazyColumn
            //    内，容器恒深底会把降级空态语言混进控制台底——空态保持页面浅底语言。
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (messages.isNotEmpty()) {
                                Modifier.background(
                                    color = toolboxColors.consoleBg,
                                    shape = AppShapes.medium,
                                )
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    if (messages.isEmpty()) {
                        item(key = "dbg-empty") {
                            // LazyColumn 内空态撑满可视高（悬顶即坏形态）
                            EmptyStateView(
                                text = "暂无输出",
                                learningHint = "输入命令后回车执行；切到「保持会话」可先启动 adb shell 再逐条发送",
                                fillParent = false,
                                modifier = Modifier.fillParentMaxHeight(),
                            )
                        }
                    }
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(message = message, toolboxColors = toolboxColors)
                    }
                }
                VerticalScrollbarSlot(listState = listState)
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dimens.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                val canSend = isSendEnabled && commandInput.isNotBlank() &&
                        (currentMode == DebugShellMode.Simple || isSessionActive)

                fun sendCommand() {
                    if (!canSend) return
                    val cmd = commandInput
                    commandInput = ""
                    when (currentMode) {
                        DebugShellMode.Simple -> viewModel.executeSimpleCommand(cmd)
                        DebugShellMode.Interactive -> viewModel.sendInteractiveCommand(cmd)
                    }
                }

                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    modifier = Modifier
                        .weight(1f)
                        // P4 · Enter 语义唯一配方（onPreviewKeyEvent + KeyDown；废弃旧
                        // onKeyEvent + KeyUp 变体——KeyUp 时 IME 组合可能未提交）
                        .onEnterKey { sendCommand() },
                    placeholder = { Text("输入命令...") },
                    singleLine = true,
                )
                // 「发送」= ic-btn rec 带字小钮（.ic-btn.rec：
                // primary 实底 + on-primary 字 + fs-11/600 +
                // r-sm + 图标 14px）；禁用态 on-primary×
                // contentDisabled(0.38)
                IcBtnRec(
                    label = "发送",
                    icon = MaterialSymbols.SEND,
                    contentDescription = "发送",
                    enabled = canSend,
                    onClick = { sendCommand() },
                )
                // Simple 模式执行中（发送钮置灰）渲染停止钮——中止当前执行并即时恢复输入
                //（同 ic-btn rec 带字形态）
                if (currentMode == DebugShellMode.Simple && !isSendEnabled) {
                    IcBtnRec(
                        label = "停止",
                        icon = MaterialSymbols.STOP,
                        contentDescription = "停止",
                        enabled = true,
                        onClick = viewModel::stopSimple,
                    )
                }
            }
        }

    }
}

/**
 * 消息行文本（渲染 sourceTag 前缀与「复制全部」的双消费单源）：
 * id 不入文本（复制给同事/喂 AI 的只是 source 标签 + 内容）。
 */
internal fun DebugChatMessage.lineText(): String = "${sourceTagPrefix()}$content"

private fun DebugChatMessage.sourceTagPrefix(): String = when (source) {
    ShellOutputSource.STDOUT -> "[STDOUT] "
    ShellOutputSource.STDERR -> "[STDERR] "
    null -> ""
}

/**
 * ic-btn rec 带字小钮（`.ic-btn.rec` 规格）：
 * fs-11/600 + padding 5px 12px（headerBtnPaddingV/space12——.ic-btn 配方正源档）+ r-sm
 * 圆角 + primary 实底 + on-primary 字（rec 档 box-shadow:none = 无描边）+ 图标 14px
 * （`.ic-btn .ico` 14px = Dimens.progressInline 档）。
 *
 * 消费面：Shell 调试台工具行「停止/发送」两钮——
 * 执行中可见的停止钮与主行动作发送钮（svg + 文字同排）。禁用态整钮
 * on-primary/primary×contentDisabled(0.38)。
 */
@Composable
private fun IcBtnRec(
    label: String,
    icon: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
        modifier = Modifier
            .clip(AppShapes.small)
            .background(
                // r-sm = AppShapes.small（--r-sm:6px 沿用 AppShapes.small 档）
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.contentDisabled),
                shape = AppShapes.small,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.space12, vertical = Dimens.headerBtnPaddingV)
            .semantics { this.contentDescription = contentDescription },
    ) {
        AppSymbolIcon(
            icon = icon,
            contentDescription = null,
            size = Dimens.progressInline,
            tint = if (enabled) contentColor
            else contentColor.copy(alpha = AppAlpha.contentDisabled),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) contentColor
            else contentColor.copy(alpha = AppAlpha.contentDisabled),
        )
    }
}

@Composable
private fun ChatMessageItem(message: DebugChatMessage, toolboxColors: EasyToolboxColors) {
    when (message.type) {
        DebugMessageType.INPUT -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = AppShapes.medium,
                        )
                        .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
                )
            }
        }

        DebugMessageType.OUTPUT -> {
            // 输出行控制台语言——容器深底由 LazyColumn 统一铺
            //（行内不再画气泡底），正文 mono 等宽；STDERR 行字色走 logError
            //（深底日志四色正源——Material error 底对深底下不可读，弃用）+ 前缀保留。
            val textColor = when (message.source) {
                ShellOutputSource.STDERR -> toolboxColors.logError
                else -> toolboxColors.consoleText
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = message.lineText(),
                    style = DesktopTypography.mono,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = Dimens.space4, vertical = Dimens.space2),
                )
            }
        }
    }
}
