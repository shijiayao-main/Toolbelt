package com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.entity.JResult
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsViewModel

/**
 * 自定义分组命名对话框（新建 / 重命名共用）。
 *
 * 名称占用等失败信息由调用方经 [errorMessage] 回填展示（提交后对话框保持打开）。
 *
 * [confirmLabel] 无默认值（必传）——调用点动词各异
 * （新建「创建」/ 重命名「重命名」），无公共动词可作默认；必传是编译期强制，
 * 防新调用点静默回归泛化词。
 */
@Composable
fun CustomGroupNameDialog(
    title: String,
    initialName: String = "",
    confirmLabel: String,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    // 焦点初始化双发（DeviceSelectDialog 配方——单发在 CMP popup 可聚焦窗异步落成时落空）
    val nameFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { nameFocusRequester.requestFocus() }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { nameFocusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.Enter, Key.NumPadEnter -> {
                                    // 校验语义与确认钮 enabled 同源（空名不提交）
                                    if (name.isNotBlank()) onConfirm(name.trim())
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        },
                    label = { Text("分组名称") },
                    singleLine = true,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "Enter 确认 · Esc 取消",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 跨组移动对话框：列出全部自定义分组，标注当前所在分组；点击目标分组即执行移动。
 */
@Composable
fun MoveToolToGroupDialog(
    toolId: String,
    toolName: String,
    viewModel: ShellToolsViewModel,
    onDismiss: () -> Unit,
) {
    val nodeResult by viewModel.toolNodeStateFlow.collectAsState()
    val customGroups = (nodeResult as? JResult.Success<List<ToolNode>>)
        ?.data
        ?.filterIsInstance<ToolNode.Parent>()
        ?.filter { it.isEditable }
        .orEmpty()
    val currentGroupId = customGroups
        .firstOrNull { group -> group.children.any { it.id == toolId } }
        ?.id
    // 列表容器焦点初始化（Esc 通路依赖焦点在对话框内）
    val listFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { listFocusRequester.requestFocus() }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { listFocusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text("移动「$toolName」到分组", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .focusRequester(listFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                if (customGroups.isEmpty()) {
                    Text("暂无可用的自定义分组")
                }
                customGroups.forEach { group ->
                    val isCurrent = group.id == currentGroupId
                    Surface(
                        shape = AppShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(
                            alpha = if (isCurrent) AppAlpha.contentFaint else AppAlpha.bgSubtle
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (!isCurrent) {
                                viewModel.moveCustomTool(toolId, group.id)
                                onDismiss()
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.space12, vertical = Dimens.space8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isCurrent) "${group.name}（当前）" else group.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (!isCurrent) {
                                AppSymbolIcon(
                                    icon = MaterialSymbols.ARROW_FORWARD,
                                    contentDescription = null,
                                    modifier = Modifier.padding(Dimens.space2),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 从剪贴板导入自定义命令对话框：粘贴分享 JSON → 复制语义导入（一律生成新 id）。
 *
 * 打开即自动读剪贴板预填（剪贴板非空 → 文本框预填、
 * 可直接点「导入」一键完成——「从剪贴板导入」钮名暗示的动线不再两步）；
 * 空剪贴板预填空串，行为与旧版一致。「从剪贴板粘贴」钮保留（覆盖打开后
 * 中途再复制的场景）。
 *
 * 解析失败时在对话框内提示错误并保持打开；成功（导入 N 个）后关闭。
 */
@Composable
fun ImportCustomJsonDialog(
    viewModel: ShellToolsViewModel,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // 初始态自动读剪贴板（trim + 非空白才预填——空剪贴板/纯空白不预填）
    var json by remember {
        mutableStateOf(
            clipboard.getText()?.text?.trim()?.takeIf { it.isNotBlank() } ?: "",
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    // 焦点初始化（文本域不绑 Enter——JSON 多行语义，Enter 是换行；Esc 通路依赖焦点在对话框内）
    val jsonFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { jsonFocusRequester.requestFocus() }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { jsonFocusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text("导入自定义命令", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                Text(
                    text = "粘贴他人分享的自定义命令 JSON（可先复制到剪贴板再点下方粘贴），导入后生成新副本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = json,
                    onValueChange = {
                        json = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .focusRequester(jsonFocusRequester),
                    label = { Text("JSON 内容") },
                    textStyle = DesktopTypography.mono,
                )
                Row {
                    OutlinedButton(
                        onClick = {
                            clipboard.getText()?.text?.let { pasted ->
                                json = pasted.trim()
                                error = null
                            }
                        },
                        enabled = clipboard.getText()?.text?.isNotBlank() == true,
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.CONTENT_PASTE,
                            contentDescription = null,
                            modifier = Modifier.padding(end = Dimens.space4),
                        )
                        Text("从剪贴板粘贴")
                    }
                }
                if (error != null) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.importCustomJson(json) { count ->
                        if (count >= 0) {
                            onDismiss()
                        } else {
                            error = "JSON 解析失败，请检查内容格式"
                        }
                    }
                },
                enabled = json.isNotBlank(),
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
