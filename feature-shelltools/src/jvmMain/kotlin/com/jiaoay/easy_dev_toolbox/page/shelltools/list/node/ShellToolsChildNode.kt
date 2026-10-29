package com.jiaoay.easy_dev_toolbox.page.shelltools.list.node

import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import androidx.compose.ui.draw.clip
import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsViewModel
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ChildBody
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import com.jiaoay.easy_dev_toolbox.widget.interactions.rememberCopyFeedbackState


@Composable
fun ShellToolsChildNode(
    child: ToolNode.Child,
    viewModel: ShellToolsViewModel,
) {
    val executingNodeIds by viewModel.executingNodeIds.collectAsState()
    // 分享导出的 JSON 经回调异步产出，剪贴板写入与「已复制」反馈统一走 CopyFeedbackState
    val copyFeedback = rememberCopyFeedbackState()

    val nodeContent: @Composable () -> Unit = when (val body = child.body) {
        is ChildBody.Normal -> {
            {
                NormalChildNode(
                    name = child.name,
                    desc = body.desc,
                    isLoading = child.id in executingNodeIds,
                    badge = child.useCount,
                    onClick = {
                        viewModel.executeNormal(
                            child.id,
                            child.name,
                            body.value
                        )
                    },
                )
            }
        }

        is ChildBody.MultiSelect -> {
            {
                MultiSelectChildNode(
                    nodeId = child.id,
                    name = child.name,
                    items = body.items,
                    loadingKey = executingNodeIds.firstOrNull { it.startsWith("${child.id}:") },
                    onItemClick = { item ->
                        viewModel.executeMultiSelectItem(
                            executingKey = "${child.id}:${item.name}",
                            nodeName = item.name,
                            command = item.value,
                        )
                    },
                )
            }
        }

        is ChildBody.Input -> {
            {
                InputChildNode(
                    name = child.name,
                    body = body,
                    isLoading = child.id in executingNodeIds,
                    onExecute = { params ->
                        viewModel.executeInput(child.id, child.name, body.value, params)
                    },
                )
            }
        }

        is ChildBody.Chain -> {
            {
                ChainChildNode(
                    name = child.name,
                    commandCount = body.commands.size,
                    isLoading = child.id in executingNodeIds,
                    badge = child.useCount,
                    onClick = {
                        viewModel.executeChain(
                            child.id,
                            child.name,
                            body.commands
                        )
                    },
                    commands = body.commands,
                )
            }
        }
    }

    if (child.isEditable) {
        // 自定义节点：操作按钮行（4 常驻 icon 钮改 hover 渐显：行尾操作区
        // 固定占位 + alpha 渐显 + 不可见即不可点（HoverableRow 契约），消解长列表的
        // 视觉 chrome 过载；分享/移动/编辑/删除语义不变）
        var showEditDialog by remember(child.id) { mutableStateOf(false) }
        var showDeleteConfirm by remember(child.id) { mutableStateOf(false) }
        var showMoveDialog by remember(child.id) { mutableStateOf(false) }

        HoverableRow(
            modifier = Modifier.fillMaxWidth(),
            actionContent = { revealed ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    IconButton(
                        onClick = {
                            viewModel.exportCustomJson(listOf(child.id)) { json ->
                                copyFeedback.copy(json)
                            }
                        },
                        enabled = revealed,
                        modifier = Modifier.size(Dimens.minimumInteractiveSize),
                    ) {
                        AppSymbolIcon(
                            icon = if (copyFeedback.isCopied) MaterialSymbols.CHECK else MaterialSymbols.SHARE,
                            contentDescription = if (copyFeedback.isCopied) "已复制" else "分享（复制 JSON 到剪贴板）",
                            size = Dimens.iconSmall,
                            tint = if (copyFeedback.isCopied) {
                                LocalEasyToolboxColors.current.success
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    IconButton(
                        onClick = { showMoveDialog = true },
                        enabled = revealed,
                        modifier = Modifier.size(Dimens.minimumInteractiveSize),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.DRIVE_FILE_MOVE,
                            contentDescription = "移动到分组",
                            size = Dimens.iconSmall,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { showEditDialog = true },
                        enabled = revealed,
                        modifier = Modifier.size(Dimens.minimumInteractiveSize),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.EDIT,
                            contentDescription = "编辑",
                            size = Dimens.iconSmall,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = revealed,
                        modifier = Modifier.size(Dimens.minimumInteractiveSize),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.DELETE,
                            contentDescription = "删除",
                            size = Dimens.iconSmall,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        ) {
            Box(modifier = Modifier.weight(1f)) { nodeContent() }
        }

        if (showEditDialog) {
            com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom.ShellCustomEditorDialog(
                viewModel = viewModel,
                editingNode = child,
                onDismiss = { showEditDialog = false },
            )
        }
        if (showMoveDialog) {
            com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom.MoveToolToGroupDialog(
                toolId = child.id,
                toolName = child.name,
                viewModel = viewModel,
                onDismiss = { showMoveDialog = false },
            )
        }
        if (showDeleteConfirm) {
            AppConfirmDialog(
                title = "删除自定义命令",
                text = "确定删除「${child.name}」吗？",
                isDangerous = true,
                confirmLabel = "删除",
                onConfirm = {
                    viewModel.deleteCustomNode(child.id)
                    showDeleteConfirm = false
                },
                onDismiss = { showDeleteConfirm = false },
            )
        }
    } else {
        nodeContent()
    }
}


@Composable
fun NormalChildNode(
    name: String,
    desc: String,
    isLoading: Boolean,
    onClick: () -> Unit,
    badge: Int? = null,
) {
    // .frow 行容器：行容器（HoverableRow hover 洗底）+ 行首 .btn-pri 小胶囊执行钮
    //（primary 实底 pill「执行」+ play icon 14dp）+ 名称 + hint（mut 弱色）+ 行尾
    // pill mute 计数；行容器 padding 10×12 + r-md 12 圆角。
    HoverableRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.medium)
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(horizontal = Dimens.space12, vertical = Dimens.space10),
        shape = AppShapes.medium,
    ) {
        // .btn-pri 小胶囊执行钮（稿 :38 逐字：primary 实底 pill、fs-12 600、padding 6×14）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            modifier = Modifier
                .clip(AppShapes.capsule)
                .background(MaterialTheme.colorScheme.primary, AppShapes.capsule)
                .padding(horizontal = Dimens.space14, vertical = Dimens.space6),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.progressInline),
                    strokeWidth = Dimens.glassStrokeHairline,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                AppSymbolIcon(
                    icon = MaterialSymbols.PLAY_ARROW,
                    contentDescription = null,
                    size = Dimens.progressInline,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                "执行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(modifier = Modifier.width(Dimens.space8))
        // 行文本单行 ellipsis：LazyColumn 无横向滚动，长文案行内折行撑高行体、破行高节奏
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (desc.isNotEmpty()) {
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (badge != null) {
            PillLabel(
                text = "×$badge",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MultiSelectChildNode(
    nodeId: String,
    name: String,
    items: List<ChildBody.MultiSelect.MultiSelectItem>,
    loadingKey: String?,
    onItemClick: (ChildBody.MultiSelect.MultiSelectItem) -> Unit,
) {
    val anyLoading = loadingKey != null
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = Dimens.space12, vertical = Dimens.space8)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            ) {
                AppSymbolIcon(
                    icon = MaterialSymbols.TUNE,
                    contentDescription = null,
                    size = 13.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(Dimens.space6))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space6)) {
                items.forEach { item ->
                    val itemKey = "$nodeId:${item.name}"
                    val isThisLoading = loadingKey == itemKey
                    OutlinedButton(
                        onClick = { onItemClick(item) },
                        modifier = Modifier.weight(1f),
                        // chipbtn = r-pill 全圆胶囊（pillflow 内 chipbtn）
                        shape = AppShapes.capsule,
                        enabled = !anyLoading,
                    ) {
                        if (isThisLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(Dimens.space6))
                        }
                        Text(text = item.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// 4.6 输入节点
@Composable
private fun InputChildNode(
    name: String,
    body: ChildBody.Input,
    isLoading: Boolean,
    onExecute: (Map<String, String>) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    FilledTonalButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.small,
        enabled = !isLoading,
    ) {
        // 图标 ↔ 进度圈 Crossfade 渐变切换（fast 档），图标尺寸 token 档位不变
        Crossfade(
            targetState = isLoading,
            animationSpec = AppMotion.fast(),
            label = "inputLoadingIcon",
        ) { loading ->
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.iconSmall),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                AppSymbolIcon(
                    icon = MaterialSymbols.EDIT,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                )
            }
        }
        Spacer(modifier = Modifier.width(Dimens.space8))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${body.params.size} 个参数",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AppAlpha.contentFaint),
        )
    }

    if (showDialog) {
        InputParamDialog(
            name = name,
            params = body.params,
            onDismiss = { showDialog = false },
            onExecute = { params ->
                showDialog = false
                onExecute(params)
            },
        )
    }
}


@Composable
private fun InputParamDialog(
    name: String,
    params: List<ChildBody.Input.InputParam>,
    onDismiss: () -> Unit,
    onExecute: (Map<String, String>) -> Unit,
) {
    val paramValues = remember { mutableStateMapOf<String, String>() }

    // 所有参数都已填写才可执行
    val allFilled = params.all { paramValues[it.name]?.isNotBlank() == true }

    // 首参数框焦点初始化（双发配方——DeviceSelectDialog 同款）
    val firstParamFocus = remember { FocusRequester() }
    LaunchedEffect(params) {
        runCatching { firstParamFocus.requestFocus() }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstParamFocus.requestFocus() }
    }

    // 外壳走 AppDialogDefaults 标准配方（与编辑器/分组命名/跨组移动/导入/设备
    // 选择五座标准对话框一致）；执行钮换 AppButton disabledReason（非 null
    // 即置灰——allFilled 折进 disabledReason when，填齐返回 null 才可点，
    // 不能 enabled 与 disabledReason 双轨并存）
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.space12),
            ) {
                params.forEachIndexed { index, param ->
                    OutlinedTextField(
                        value = paramValues[param.name] ?: "",
                        onValueChange = { paramValues[param.name] = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(firstParamFocus) else Modifier)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    // Enter = 全部填齐时执行（未填齐走确认钮 disabledReason
                                    // 置灰语义，Enter 不触发执行）
                                    Key.Enter, Key.NumPadEnter -> {
                                        if (allFilled) {
                                            onExecute(params.associate { p -> p.name to (paramValues[p.name] ?: "") })
                                        }
                                        true
                                    }
                                    Key.Escape -> {
                                        onDismiss()
                                        true
                                    }
                                    else -> false
                                }
                            },
                        label = {
                            Text(
                                if (param.desc.isNotEmpty()) "${param.name} (${param.desc})"
                                else param.name
                            )
                        },
                        singleLine = true,
                    )
                }
                Text(
                    text = "全部填写完成后 Enter 执行 · Esc 取消",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            AppButton(
                label = "执行",
                busy = false,
                disabledReason = when {
                    !allFilled -> "请填写全部参数"
                    else -> null
                },
                onClick = {
                    val result =
                        params.associate { it.name to (paramValues[it.name] ?: "") }
                    onExecute(result)
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}


// 4.7 链式节点
/**
 * 链式节点卡（容器结构）：
 * - 主区（图标 + 名称 + 步数 + badge）clickable 执行——语义与旧单 Button 一致；
 * - 行尾展开钮**独立 clickable**（hover 渐显，与自定义节点操作钮同配方）：
 *   旧结构是单个 Button，Button 内不可嵌独立可点钮（嵌套 clickable 双响应），
 *   故本卡从 Button 重构为 Surface 容器 + Row 布局；展开态显 commands 逐行
 *   mono 预览（执行前可确认链内命令——链式是高风险批量操作）；
 * - 展开尺寸过渡 animateContentSize(softSpring)，挂外层 Column
 *   （须挂组合稳定的容器——动画容器随内容重建会中断过渡）。
 *
 * @param commands 链内命令列表（预览数据源；commandCount 保留并存以兼容既有调用方）
 */
@Composable
fun ChainChildNode(
    name: String,
    commandCount: Int,
    isLoading: Boolean,
    onClick: () -> Unit,
    commands: List<String> = emptyList(),
    badge: Int? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // 行焦点感知：展开钮 hover 门控的键盘补充（行内 clickable 获焦冒泡到行 → 键盘可首次展开）
    var rowFocused by remember { mutableStateOf(false) }

    // .frow 行容器——Chain 与 Normal 同构：行容器 + .btn-pri 小胶囊执行钮 +
    // 名称 + hint + pill mute 计数（链式节点与单命令节点同行语言）。
    // 展开钮/展开命令预览保留（规格外交互增强件，登记 docs/core/theme.md「有据保留」）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 守护锚：展开态尺寸过渡 animateContentSize(softSpring)（ParentNode 先例）
            .animateContentSize(animationSpec = AppMotion.softSpring()),
    ) {
        // 同 Normal 行容器 10×12 + r-md（.frow）
        HoverableRow(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusEvent { rowFocused = it.hasFocus }
                .clip(AppShapes.medium)
                .clickable(enabled = !isLoading, onClick = onClick)
                .padding(horizontal = Dimens.space12, vertical = Dimens.space10),
            shape = AppShapes.medium,
        ) {
            // 链式钮 = btn-tonal 形态（primary-c 底 primary 字胶囊，fs-12/600、6×14）
            // + 文字「链式执行 · N 步」进钮（稿 :483 s3 Chain 卡）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                modifier = Modifier
                    .clip(AppShapes.capsule)
                    .background(MaterialTheme.colorScheme.primaryContainer, AppShapes.capsule)
                    .padding(horizontal = Dimens.space14, vertical = Dimens.space6),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.progressInline),
                        strokeWidth = Dimens.glassStrokeHairline,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    AppSymbolIcon(
                        icon = MaterialSymbols.PLAYLIST_PLAY,
                        contentDescription = null,
                        size = Dimens.progressInline,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "链式执行 · $commandCount 步",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(Dimens.space8))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (badge != null) {
                PillLabel(
                    text = "×$badge",
                    container = LocalToolboxSurfaceColors.current.surfaceDeep,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 行尾展开钮：独立 clickable（不嵌主区 clickable——嵌套双响应禁）；hover/行获焦/
            // 已展开 三态渐显（键盘首次展开通路——collapsed 态 enabled=hovered||expanded
            // 时键盘无法首次展开）；单命令链不渲染（预览无意义）
            if (commands.size > 1) {
                val interactionSource = remember { MutableInteractionSource() }
                val hovered by interactionSource.collectIsHoveredAsState()
                val expandAlpha by animateFloatAsState(
                    targetValue = if (hovered || expanded || rowFocused) 1f else 0f,
                    animationSpec = AppMotion.fast(),
                    label = "chainExpandAlpha",
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    enabled = hovered || expanded || rowFocused,
                    modifier = Modifier
                        .size(Dimens.minimumInteractiveSize)
                        .hoverable(interactionSource),
                ) {
                    AppSymbolIcon(
                        icon = if (expanded) MaterialSymbols.EXPAND_LESS else MaterialSymbols.EXPAND_MORE,
                        contentDescription = if (expanded) "收起步骤预览" else "展开步骤预览",
                        size = Dimens.iconSmall,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 展开态：commands 逐行 mono 预览（执行前确认链内命令）——左缘 2px
        // border-subtle 竖条 + margin-left 8 + 每行「$ cmd」前缀
        if (expanded && commands.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.space8, end = Dimens.space16, bottom = Dimens.space8)
                    .let { m ->
                        val lineColor = LocalToolboxSurfaceColors.current.borderSubtle
                        m.drawBehind {
                            drawLine(
                                color = lineColor,
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = Dimens.strokeThin.toPx(),
                            )
                        }
                    }
                    .padding(start = Dimens.space8),
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                commands.forEach { command ->
                    Text(
                        text = "$ $command",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    )
                }
            }
        }
    }
}
