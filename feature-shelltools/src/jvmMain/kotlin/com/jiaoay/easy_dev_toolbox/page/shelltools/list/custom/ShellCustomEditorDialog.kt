package com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.entity.JResult
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsConfigRepository
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsViewModel
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ChildBody
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolNodeConfig
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ShellToolsConfigParser
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode

/** 编辑类型（与 body.type 整型鉴别一一对应） */
private enum class EditorType(val label: String) {
    Normal("普通"),
    MultiSelect("多选"),
    Input("输入"),
    Chain("链式"),
}

/** 多选选项编辑行 */
private data class MultiItemRow(val name: String, val value: String, val desc: String)

/** 输入参数编辑行 */
private data class InputParamRow(val name: String, val desc: String)

/**
 * 自定义命令编辑器（新增 / 编辑共用），支持全部四种子节点类型：
 * 普通（单命令）/ 多选（选项列表）/ 输入（模板 + 参数）/ 链式（多命令串行）。
 *
 * - 编辑已有节点时按 [ChildBody] 实际类型回填（多选回填选项、输入回填模板与参数）
 * - 保存前校验：名称必填、命令内容必填、多选 ≥1 个有效选项、输入 ≥1 个参数且模板必填
 * - 保存按 [editingNode.id] 定位：编辑保留 id（改名不重建），新建由 Repository 生成 UUID
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellCustomEditorDialog(
    viewModel: ShellToolsViewModel,
    editingNode: ToolNode.Child?,
    onDismiss: () -> Unit,
    /** 可选目标分组（id to name），新建模式下展示分组选择；编辑模式保持原分组 */
    availableGroups: List<Pair<String, String>> = emptyList(),
    /** 新建模式下预选的分组 id（如从某分组头部"新增"进入） */
    initialGroupId: String? = null,
) {
    val editingBody = editingNode?.body

    // ── 可用目标分组：显式传参优先；否则从状态流收集自定义分组（默认分组始终兜底在列） ──
    val resolvedGroups = if (availableGroups.isNotEmpty()) {
        availableGroups
    } else {
        val nodeResult by viewModel.toolNodeStateFlow.collectAsState()
        val customGroups = (nodeResult as? JResult.Success<List<ToolNode>>)
            ?.data
            ?.filterIsInstance<ToolNode.Parent>()
            ?.filter { it.isEditable }
            ?.map { it.id to it.name }
            .orEmpty()
        if (customGroups.any { it.first == ShellToolsConfigRepository.DEFAULT_GROUP_ID }) {
            customGroups
        } else {
            customGroups + (ShellToolsConfigRepository.DEFAULT_GROUP_ID to ShellToolsConfigRepository.DEFAULT_GROUP_NAME)
        }
    }

    var name by remember { mutableStateOf(editingNode?.name ?: "") }
    var editorType by remember {
        mutableStateOf(
            when (editingBody) {
                is ChildBody.MultiSelect -> EditorType.MultiSelect
                is ChildBody.Input -> EditorType.Input
                is ChildBody.Chain -> EditorType.Chain
                else -> EditorType.Normal
            }
        )
    }

    // 普通命令
    var command by remember { mutableStateOf((editingBody as? ChildBody.Normal)?.value ?: "") }
    var desc by remember { mutableStateOf((editingBody as? ChildBody.Normal)?.desc ?: "") }

    // 多选选项
    val multiItems = remember {
        mutableStateListOf<MultiItemRow>().apply {
            (editingBody as? ChildBody.MultiSelect)?.items?.forEach { add(MultiItemRow(it.name, it.value, it.desc)) }
            if (isEmpty()) add(MultiItemRow("", "", ""))
        }
    }

    // 输入模板与参数
    var inputTemplate by remember { mutableStateOf((editingBody as? ChildBody.Input)?.value ?: "") }
    val inputParams = remember {
        mutableStateListOf<InputParamRow>().apply {
            (editingBody as? ChildBody.Input)?.params?.forEach { add(InputParamRow(it.name, it.desc)) }
            if (isEmpty()) add(InputParamRow("", ""))
        }
    }

    // 链式命令
    var chainCommands by remember {
        mutableStateOf(
            when (val b = editingBody) {
                is ChildBody.Chain -> b.commands.joinToString("\n")
                is ChildBody.Normal -> b.value
                else -> ""
            }
        )
    }

    // 新建时的目标分组选择
    var targetGroupId by remember {
        mutableStateOf(
            initialGroupId
                ?: ShellToolsConfigRepository.DEFAULT_GROUP_ID.takeIf { resolvedGroups.isNotEmpty() }
        )
    }
    var groupMenuExpanded by remember { mutableStateOf(false) }

    // 名称框焦点初始化（对话框打开即聚焦——与 DeviceSelectDialog 双发配方同源，
    // 单发在 CMP popup 可聚焦窗异步落成时会稳定落空）
    val nameFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { nameFocusRequester.requestFocus() }
        androidx.compose.runtime.withFrameNanos { }
        runCatching { nameFocusRequester.requestFocus() }
    }

    // ── 脏态离开确认：丢弃的是表单输入（非持久化实体），确认卡走非危险色 ──
    // 指纹 = 初始表单值拼接（remember 首组合捕获初值）；Esc 与误点遮罩同走
    // onDismissRequest，一并被拦
    val initialFormSignature = remember(editingNode, editingBody, initialGroupId) {
        listOf(
            name,
            editorType.label,
            command,
            desc,
            inputTemplate,
            chainCommands,
            targetGroupId ?: "",
            multiItems.joinToString("|") { "${it.name}\u0001${it.value}\u0001${it.desc}" },
            inputParams.joinToString("|") { "${it.name}\u0001${it.desc}" },
        ).joinToString("\u0002")
    }
    val currentFormSignature = listOf(
        name,
        editorType.label,
        command,
        desc,
        inputTemplate,
        chainCommands,
        targetGroupId ?: "",
        multiItems.joinToString("|") { "${it.name}\u0001${it.value}\u0001${it.desc}" },
        inputParams.joinToString("|") { "${it.name}\u0001${it.desc}" },
    ).joinToString("\u0002")
    val formDirty = currentFormSignature != initialFormSignature
    var confirmDiscard by remember { mutableStateOf(false) }
    fun requestDismiss() {
        if (formDirty) confirmDiscard = true else onDismiss()
    }

    // ── 保存前校验 ──
    val validMultiItems = multiItems.filter { it.name.isNotBlank() && it.value.isNotBlank() }
    val validInputParams = inputParams.filter { it.name.isNotBlank() }
    val commandFilled = when (editorType) {
        EditorType.Normal -> command.isNotBlank()
        EditorType.MultiSelect -> validMultiItems.isNotEmpty()
        EditorType.Input -> inputTemplate.isNotBlank() && validInputParams.isNotEmpty()
        EditorType.Chain -> chainCommands.lines().any { it.isNotBlank() }
    }

    AlertDialog(
        onDismissRequest = { requestDismiss() },
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = {
            Text(
                text = if (editingNode == null) "新增自定义命令" else "编辑自定义命令",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.space12),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester),
                    label = { Text("名称") },
                    supportingText = { Text("名称必填") },
                    singleLine = true,
                )

                SegmentedControl(
                    options = EditorType.entries.map { it.label },
                    selectedIndex = EditorType.entries.indexOf(editorType),
                    onSelect = { editorType = EditorType.entries[it] },
                    modifier = Modifier.fillMaxWidth(),
                )

                when (editorType) {
                    EditorType.Normal -> {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("命令") },
                            singleLine = true,
                            textStyle = DesktopTypography.mono,
                        )
                        OutlinedTextField(
                            value = desc,
                            onValueChange = { desc = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("描述（可选）") },
                            singleLine = true,
                        )
                    }

                    EditorType.MultiSelect -> MultiSelectItemsEditor(items = multiItems)

                    EditorType.Input -> InputParamsEditor(
                        template = inputTemplate,
                        onTemplateChange = { inputTemplate = it },
                        params = inputParams,
                    )

                    EditorType.Chain -> OutlinedTextField(
                        value = chainCommands,
                        onValueChange = { chainCommands = it },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        label = { Text("多条命令（每行一条，串行执行）") },
                        textStyle = DesktopTypography.mono,
                    )
                }

                // 新建模式：目标分组选择（无分组可选时落默认分组）
                if (editingNode == null && resolvedGroups.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = groupMenuExpanded,
                        onExpandedChange = { groupMenuExpanded = it },
                    ) {
                        val selectedName = resolvedGroups
                            .firstOrNull { it.first == targetGroupId }?.second ?: "我的自定义"
                        OutlinedTextField(
                            value = selectedName,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            label = { Text("保存到分组") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupMenuExpanded) },
                            singleLine = true,
                        )
                        ExposedDropdownMenu(
                            expanded = groupMenuExpanded,
                            onDismissRequest = { groupMenuExpanded = false },
                        ) {
                            resolvedGroups.forEach { (groupId, groupName) ->
                                DropdownMenuItem(
                                    text = { Text(groupName) },
                                    onClick = {
                                        targetGroupId = groupId
                                        groupMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 保存钮走 AppButton disabledReason——AppButton 的
            // disabledReason 非 null 即置灰（effectiveEnabled = enabled && !busy &&
            // disabledReason == null），canSave 判断必须折进 when（填齐时返回 null 才可点）；
            // 不能保留 enabled 双轨（双轨下 disabledReason 恒非空 = 永不可点）
            AppButton(
                label = "保存",
                busy = false,
                kind = AppButtonKind.Primary,
                disabledReason = when {
                    name.isBlank() -> "先填写命令名称"
                    !commandFilled -> "命令内容未填齐（多选需 ≥1 个有效选项 / 输入需模板与参数完整）"
                    else -> null
                },
                onClick = {
                    val body = when (editorType) {
                        EditorType.Normal -> ChildBody.Normal(
                            value = command.trim(),
                            desc = desc.trim(),
                        )

                        EditorType.MultiSelect -> ChildBody.MultiSelect(
                            items = validMultiItems.map {
                                ChildBody.MultiSelect.MultiSelectItem(
                                    name = it.name.trim(),
                                    value = it.value.trim(),
                                    desc = it.desc.trim(),
                                )
                            },
                        )

                        EditorType.Input -> ChildBody.Input(
                            value = inputTemplate.trim(),
                            params = validInputParams.map {
                                ChildBody.Input.InputParam(
                                    name = it.name.trim(),
                                    desc = it.desc.trim(),
                                )
                            },
                        )

                        EditorType.Chain -> ChildBody.Chain(
                            commands = chainCommands.lines().map { it.trim() }.filter { it.isNotEmpty() },
                        )
                    }
                    val config = ShellToolNodeConfig(
                        type = 1,
                        name = name.trim(),
                        body = ShellToolsConfigParser.encodeChildBody(body),
                        // 编辑保留原 id（改名不重建）；新建传 null 由 Repository 生成 UUID
                        id = editingNode?.id,
                    )
                    viewModel.upsertCustomTool(
                        node = config,
                        groupId = if (editingNode == null) targetGroupId else null,
                    )
                    onDismiss()
                },
            )
        },
        dismissButton = {
            TextButton(onClick = { requestDismiss() }) {
                Text("取消")
            }
        },
    )

    // 脏态离开确认（Esc/误点遮罩/取消钮统一经 requestDismiss 拦截后走到这里）
    if (confirmDiscard) {
        AppConfirmDialog(
            title = "放弃未保存的修改？",
            text = "表单内容尚未保存，确认放弃并关闭编辑器。",
            isDangerous = false,
            confirmLabel = "放弃修改",
            onConfirm = {
                confirmDiscard = false
                onDismiss()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

// ═══════════════════════════════════════════
// 多选选项编辑器：动态增删行（名称 / 命令 / 描述）
// ═══════════════════════════════════════════

@Composable
private fun MultiSelectItemsEditor(items: MutableList<MultiItemRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        Text(
            text = "选项列表（至少 1 个有效选项：名称与命令必填）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        items.forEachIndexed { index, item ->
            Surface(
                shape = AppShapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AppAlpha.bgSubtleAlt),
            ) {
                Column(
                    modifier = Modifier.padding(Dimens.space8),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space6),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "选项 ${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { items.removeAt(index) },
                            modifier = Modifier.size(20.dp),
                        ) {
                            AppSymbolIcon(
                                icon = MaterialSymbols.CLOSE,
                                contentDescription = "删除选项",
                                size = 14.dp,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = item.name,
                        onValueChange = { items[index] = item.copy(name = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = item.value,
                        onValueChange = { items[index] = item.copy(value = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("命令") },
                        singleLine = true,
                        textStyle = DesktopTypography.mono,
                    )
                    OutlinedTextField(
                        value = item.desc,
                        onValueChange = { items[index] = item.copy(desc = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("描述（可选）") },
                        singleLine = true,
                    )
                }
            }
        }
        TextButton(onClick = { items.add(MultiItemRow("", "", "")) }) {
            AppSymbolIcon(
                icon = MaterialSymbols.ADD,
                contentDescription = null,
                size = Dimens.iconSmall,
            )
            Spacer(modifier = Modifier.width(Dimens.space4))
            Text("添加选项")
        }
    }
}

// ═══════════════════════════════════════════
// 输入参数编辑器：命令模板（{参数名} 占位）+ 动态增删参数行（参数名 / 描述）
// ═══════════════════════════════════════════

@Composable
private fun InputParamsEditor(
    template: String,
    onTemplateChange: (String) -> Unit,
    params: MutableList<InputParamRow>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        OutlinedTextField(
            value = template,
            onValueChange = onTemplateChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("命令模板") },
            supportingText = { Text("用 {参数名} 作占位符，如 am start -n {package}/.MainActivity") },
            singleLine = true,
            textStyle = DesktopTypography.mono,
        )
        Text(
            text = "参数定义（至少 1 个：参数名必填，需与模板占位符一致）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        params.forEachIndexed { index, param ->
            Surface(
                shape = AppShapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AppAlpha.bgSubtleAlt),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Dimens.space8, vertical = Dimens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                ) {
                    OutlinedTextField(
                        value = param.name,
                        onValueChange = { params[index] = param.copy(name = it) },
                        modifier = Modifier.weight(1f),
                        label = { Text("参数名") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = param.desc,
                        onValueChange = { params[index] = param.copy(desc = it) },
                        modifier = Modifier.weight(1f),
                        label = { Text("描述（可选）") },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = { params.removeAt(index) },
                        modifier = Modifier.size(Dimens.minimumInteractiveSize),
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.CLOSE,
                            contentDescription = "删除参数",
                            size = 14.dp,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        TextButton(onClick = { params.add(InputParamRow("", "")) }) {
            AppSymbolIcon(
                icon = MaterialSymbols.ADD,
                contentDescription = null,
                size = Dimens.iconSmall,
            )
            Spacer(modifier = Modifier.width(Dimens.space4))
            Text("添加参数")
        }
    }
}
