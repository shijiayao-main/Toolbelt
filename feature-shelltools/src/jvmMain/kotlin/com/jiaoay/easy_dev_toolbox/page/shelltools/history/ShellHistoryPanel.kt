package com.jiaoay.easy_dev_toolbox.page.shelltools.history

import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.hoverable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellHistorySortMode
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsViewModel
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.GhostChipButton
import com.jiaoay.easy_dev_toolbox.widget.VerticalScrollbarSlot
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow

@Composable
fun ShellHistoryPanel(
    viewModel: ShellToolsViewModel,
    modifier: Modifier = Modifier,
) {
    val historyItems by viewModel.displayHistory.collectAsState()
    val sortMode by viewModel.historySortMode.collectAsState()
    val selectionMode by viewModel.historySelectionMode.collectAsState()
    val selectedIds by viewModel.historySelectedIds.collectAsState()

    // 清空全部确认对话框（空历史时入口禁用，无需弹出）
    var showClearAllConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxHeight(),
    ) {
        // 标题行 + 清空全部 + 多选切换
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "历史记录",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { showClearAllConfirm = true },
                enabled = historyItems.isNotEmpty(),
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
            ) {
                AppSymbolIcon(
                    icon = MaterialSymbols.DELETE_SWEEP,
                    contentDescription = "清空全部历史",
                    size = Dimens.iconMedium,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { viewModel.toggleHistorySelectionMode() },
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
            ) {
                AppSymbolIcon(
                    icon = if (selectionMode) MaterialSymbols.CLOSE else MaterialSymbols.CHECKLIST,
                    contentDescription = if (selectionMode) "退出多选" else "多选批量执行",
                    size = Dimens.iconMedium,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 排序切换（多选模式下禁用——勾选集合锚定在当前展示序上，切排序
        // 会令执行序静默改变，故勾选与排序两个意图互斥；置灰经 hover tooltip 说明
        // 原因与解除方式，非静默哑按钮。禁用态配 disabledReason 说明是 AppButton 纪律，
        // SegmentedControl 无该参数，用 hover 气泡承接同语义）
        SortModeSegmentedRow(
            sortMode = sortMode,
            selectionMode = selectionMode,
            onSelect = { viewModel.setHistorySortMode(it) },
        )

        if (historyItems.isEmpty()) {
            // 空状态占位（与 StatusView Empty 态同构：图标 + 引导文案居中）
            EmptyStateView(text = "暂无历史记录", icon = MaterialSymbols.HISTORY)
        } else {
            // 历史列表
            HistoryList(
                viewModel = viewModel,
                historyItems = historyItems,
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                modifier = Modifier.weight(1f),
            )

            // 多选操作条
            if (selectionMode) {
                SelectionActionBar(
                    selectedCount = selectedIds.size,
                    onExecute = { viewModel.executeSelectedHistory() },
                    onCancel = { viewModel.toggleHistorySelectionMode() },
                )
            }
        }
    }

    // ── 清空全部（二次确认，复用自定义命令删除确认的既有交互模式） ──
    if (showClearAllConfirm) {
        AppConfirmDialog(
            title = "清空历史记录",
            text = "确定清空全部 ${historyItems.size} 条历史记录吗？该操作不可恢复。",
            isDangerous = true,
            confirmLabel = "清空",
            onConfirm = {
                viewModel.clearAllHistory()
                showClearAllConfirm = false
            },
            onDismiss = { showClearAllConfirm = false },
        )
    }
}

/**
 * 历史排序切换段（多选模式下整段禁用 + 置灰原因 tooltip）。
 *
 * 勾选集合锚定在当前展示序上（[ShellToolsViewModel.executeSelectedHistory] 按
 * displayHistory 顺序展开命令），多选中切排序会让执行序静默改变——排序入口
 * 在多选期间禁用，VM 的 setHistorySortMode 同时兜底清空勾选（纵深防御）。
 *
 * 置灰说明走 hover 气泡（配方单源 = widget/interactions/HoverTooltipBox，
 * `showWhen = selectionMode` 门控——仅多选态展示；hover/延迟/常驻语义由
 * 共享件承载）——AppButton 的 disabledReason 纪律（禁止静默置灰）在无该参数的
 * SegmentedControl 上的承接形态。
 *
 * interactionSource 必须挂到控件容器的 modifier（`.hoverable(interactionSource)`）
 * ——置灰降透明度不阻断 hover 命中，但 hoverable 挂在容器上才覆盖整个控件面
 * （容器级挂载配方），否则 hovered 恒 false、tooltip 永不显示。
 */
@Composable
private fun SortModeSegmentedRow(
    sortMode: ShellHistorySortMode,
    selectionMode: Boolean,
    onSelect: (ShellHistorySortMode) -> Unit,
) {
    HoverTooltipBox(
        showWhen = selectionMode,
        tooltip = {
            Text(
                text = "多选中切换排序将清空已选（先退出多选或执行后再切换）",
                style = MaterialTheme.typography.labelSmall,
            )
        },
    ) { interactionSource ->
        SegmentedControl(
            options = ShellHistorySortMode.entries.map { it.label },
            selectedIndex = ShellHistorySortMode.entries.indexOf(sortMode),
            onSelect = { onSelect(ShellHistorySortMode.entries[it]) },
            enabled = !selectionMode,
            // hover 检测挂控件容器（禁用态 hover 命中不受影响，tooltip 解释禁用原因）
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interactionSource)
                .padding(bottom = Dimens.space8),
        )
    }
}

@Composable
private fun HistoryList(
    viewModel: ShellToolsViewModel,
    historyItems: List<ShellHistoryItem>,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    // 列表 + 右缘桌面滚动条：无上限历史列表的位置/总量反馈
    val listState = rememberLazyListState()
    // 历史经 DataStore 持久化 → 单条删除与「清空全部」
    // 同判据走 AppConfirmDialog（删除语义不静默）
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.Row(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.space6),
        ) {
            items(items = historyItems, key = { it.id }) { item ->
            if (selectionMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // M3 Checkbox 无 shape 参数（r-check 5px 微档在 CMP material3
                    // 无缝可用——登记 docs/core/theme.md「有据保留」）
                    Checkbox(
                        checked = item.id in selectedIds,
                        onCheckedChange = { viewModel.toggleHistorySelection(item.id) },
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        HistoryCard(
                            item = item,
                            viewModel = viewModel,
                            onClick = { viewModel.toggleHistorySelection(item.id) },
                        )
                    }
                }
            } else {
                // 非多选模式：hover 或行获焦时行尾渐显单条删除按钮（确认语义——持久化实体
                // 与清空全部同判据）；enabled = revealed 保持「不可见即不可点」的防误点语义
                HoverableRow(
                    modifier = Modifier.fillMaxWidth(),
                    actionContent = { revealed ->
                        IconButton(
                            onClick = { pendingDeleteId = item.id },
                            enabled = revealed,
                            modifier = Modifier.size(Dimens.minimumInteractiveSize),
                        ) {
                            AppSymbolIcon(
                                // 删除（破坏性）语义归一 DELETE + error 色，
                                // 对齐工具树删除钮（design-language.md §4.3 图标语义表）；此
                                // 图标是删除钮，多选「退出」钮的 CLOSE 语义合法不动
                                icon = MaterialSymbols.DELETE,
                                contentDescription = "删除该条历史",
                                size = Dimens.iconSmall,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        HistoryCard(
                            item = item,
                            viewModel = viewModel,
                            onClick = { executeHistoryItem(viewModel, item) },
                        )
                    }
                }
            }
        }
        }
        VerticalScrollbarSlot(listState = listState)
    }

    // 单条删除确认（判据 = 持久化实体一律确认——同清空全部/删 run/删会话家族）
    pendingDeleteId?.let { id ->
        AppConfirmDialog(
            title = "删除历史记录",
            text = "将删除该条执行历史，不可恢复。",
            isDangerous = true,
            confirmLabel = "删除",
            onConfirm = {
                viewModel.removeHistoryItem(id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
        )
    }
}

@Composable
private fun HistoryCard(
    item: ShellHistoryItem,
    viewModel: ShellToolsViewModel,
    onClick: () -> Unit,
) {
    when (item) {
        is ShellHistoryItem.Normal -> NormalHistoryCard(item, viewModel, onClick)
        is ShellHistoryItem.MultiSelect -> NormalHistoryCard(
            item = ShellHistoryItem.Normal(
                id = item.id,
                displayLabel = item.displayLabel,
                command = item.command,
                useCount = item.useCount,
                lastUsedAt = item.lastUsedAt,
            ),
            viewModel = viewModel,
            onClick = onClick,
        )

        is ShellHistoryItem.Input -> NormalHistoryCard(
            item = ShellHistoryItem.Normal(
                id = item.id,
                displayLabel = item.displayLabel,
                command = item.command,
                useCount = item.useCount,
                lastUsedAt = item.lastUsedAt,
            ),
            viewModel = viewModel,
            onClick = onClick,
        )

        is ShellHistoryItem.Chain -> ChainHistoryCard(item, viewModel, onClick)
    }
}

/** 非多选模式下的执行入口 */
private fun executeHistoryItem(viewModel: ShellToolsViewModel, item: ShellHistoryItem) {
    when (item) {
        is ShellHistoryItem.Normal -> viewModel.executeHistoryNormal(item)
        is ShellHistoryItem.MultiSelect -> viewModel.executeHistoryNormal(
            ShellHistoryItem.Normal(
                id = item.id,
                displayLabel = item.displayLabel,
                command = item.command,
            )
        )

        is ShellHistoryItem.Input -> viewModel.executeHistoryNormal(
            ShellHistoryItem.Normal(
                id = item.id,
                displayLabel = item.displayLabel,
                command = item.command,
            )
        )

        is ShellHistoryItem.Chain -> viewModel.executeChain(item.nodeId, item.displayLabel, item.commands)
    }
}

@Composable
private fun NormalHistoryCard(
    item: ShellHistoryItem.Normal,
    viewModel: ShellToolsViewModel,
    onClick: () -> Unit,
) {
    // 历史卡与树内子行**分家**——历史行 = .frow + hairline 描边 + b 名称 +
    // hint mono 命令 + hover 删除 error（**无** btn-pri 执行钮/级别 pill——树内
    // 子行语言不进历史卡，不直引 NormalChildNode）。
    val surfaceColors = com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .background(surfaceColors.surfaceRaised, AppShapes.small)
            .border(Dimens.glassStrokeHairline, surfaceColors.borderHairline, AppShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.displayLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.command,
                style = DesktopTypography.monoTag,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.useCount > 0) {
            PillLabel(
                text = "×${item.useCount}",
                container = surfaceColors.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Chain 类型的链式样式卡片
 */
@Composable
private fun ChainHistoryCard(
    item: ShellHistoryItem.Chain,
    viewModel: ShellToolsViewModel,
    onClick: () -> Unit,
) {
    // Chain 历史卡同 .frow 常态行；展开预览保留——mono 步骤预览。
    var expanded by remember { mutableStateOf(false) }
    val surfaceColors = com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShapes.small)
                .background(surfaceColors.surfaceRaised, AppShapes.small)
                .border(Dimens.glassStrokeHairline, surfaceColors.borderHairline, AppShapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = Dimens.space12, vertical = Dimens.space8),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.displayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "链式执行 · ${item.commands.size} 步",
                    style = DesktopTypography.monoTag,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (item.useCount > 0) {
                PillLabel(
                    text = "×${item.useCount}",
                    container = surfaceColors.surfaceDeep,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 展开钮（多命令链）
            if (item.commands.size > 1) {
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(Dimens.iconMedium)) {
                    AppSymbolIcon(
                        icon = if (expanded) MaterialSymbols.EXPAND_LESS else MaterialSymbols.EXPAND_MORE,
                        contentDescription = if (expanded) "收起步骤预览" else "展开步骤预览",
                        size = Dimens.iconSmall,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 展开态 mono 步骤预览
        if (expanded && item.commands.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.space16, end = Dimens.space16, bottom = Dimens.space8),
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                item.commands.forEachIndexed { index, command ->
                    Text(
                        text = "[${index + 1}/${item.commands.size}] $command",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.space8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已选 $selectedCount 项",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            // 「取消」= GhostChipButton 单源（次级动作 chip）
            GhostChipButton(
                label = "取消",
                onClick = onCancel,
            )
            // 「串行执行选中项（N）」= AppButton(Primary) 单源（primary 实底胶囊，
            // 数量常显；selectedCount = 0 时经 enabled 走 M3 disabled 降灰）
            AppButton(
                label = "串行执行选中项（$selectedCount）",
                busy = false,
                onClick = onExecute,
                kind = AppButtonKind.Primary,
                enabled = selectedCount > 0,
            )
        }
    }
}
