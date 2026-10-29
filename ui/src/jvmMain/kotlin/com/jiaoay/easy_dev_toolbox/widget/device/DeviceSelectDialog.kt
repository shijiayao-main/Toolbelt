package com.jiaoay.easy_dev_toolbox.widget.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow

/**
 * 设备选择对话框（多设备场景）：RadioButton 单选列表展示串号与型号
 * （型号优先为主标识，缺失时以串号展示），确认后定向命令到选定设备。
 * 本对话框降级为 >3 台路径（≤3 台走状态栏内联直选）——流程与触发入口见
 * docs/pages/shell-tools.md「多设备选择流程」节。
 *
 * 共享渲染件（:ui widget 层）：消费 :core 设备域模型（[DeviceInfo]），与
 * [DeviceStatusBar] 同批供设备域单源的各消费页面共用。
 *
 * 键盘面与焦点初始化：Enter（有选中）= 确认、Esc = 关闭（KeyDown 过滤 +
 * Enter/NumPadEnter 双通道——`Modifier.onEnterKey` 只管 Enter 无 Esc 故自写双键）；
 * 焦点初始化双发 requestFocus（立即一发 + 下一帧补一发，runCatching 吞未挂载态）——
 * CMP popup 可聚焦窗异步落成，单发稳定落空；无此初始化时对话框内无焦点组件，
 * 第一下 Enter/Esc 被丢弃。
 */
@Composable
fun DeviceSelectDialog(
    devices: List<DeviceInfo>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(devices) { mutableStateOf(devices.firstOrNull()?.serial) }

    // 键盘面焦点宿主（挂 text Column——onPreviewKeyEvent 同节点，preview 链可达）
    val dialogFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { dialogFocusRequester.requestFocus() }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { dialogFocusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text("选择目标设备", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .focusRequester(dialogFocusRequester)
                    .focusable()
                    .verticalScroll(rememberScrollState())
                    // 键盘裁决（预览阶段拦截——RadioButton 聚焦时不吞 Enter 语义）
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                selected?.let(onConfirm)
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                Text(
                    text = "检测到多台设备，请选择命令执行的目标设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                devices.forEach { device ->
                    // 行 hover 背景渐入（shape 与行裁剪一致）；选中态仍由 RadioButton 表达
                    HoverableRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.small)
                            .clickable { selected = device.serial }
                            .padding(horizontal = Dimens.space8, vertical = Dimens.space4),
                        shape = AppShapes.small,
                    ) {
                        RadioButton(
                            selected = selected == device.serial,
                            onClick = { selected = device.serial },
                        )
                        Column(modifier = Modifier.padding(start = Dimens.space4)) {
                            Text(
                                text = device.displayLabel,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (device.model.isNotEmpty()) {
                                // 有型号时以小字补充串号，便于区分同型号设备
                                Text(
                                    text = device.serial,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // 键面提示行（PathConfirmCard 同款配方——labelSmall + onSurfaceVariant）
                Text(
                    text = "Enter 确认 · Esc 取消",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) { Text("使用此设备") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
