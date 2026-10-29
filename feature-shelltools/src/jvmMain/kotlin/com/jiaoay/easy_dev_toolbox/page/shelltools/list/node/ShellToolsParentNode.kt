package com.jiaoay.easy_dev_toolbox.page.shelltools.list.node

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsConfigRepository
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsViewModel
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ChildBody
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom.CustomGroupNameDialog
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom.ShellCustomEditorDialog
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.interactions.rememberCopyFeedbackState

// ═══════════════════════════════════════════
// 父节点 Section
// ═══════════════════════════════════════════

@Composable
fun ShellToolsParentNode(
    parent: ToolNode.Parent,
    viewModel: ShellToolsViewModel,
) {
    // 分享导出的 JSON 经回调异步产出，剪贴板写入与「已复制」反馈统一走 CopyFeedbackState
    val copyFeedback = rememberCopyFeedbackState()

    // 分组级 CRUD 对话框状态（仅自定义分组使用）
    var showAddToolDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }

    // zone 分组卡直用 SectionCard(collapsible)（.zone：r-lg 16 + surface-raised
    // 实底 + inset hairline + panel-rim + padding 14/20）——自绘容器不入（四维漂移已定罪）。
    SectionCard(
        title = parent.name,
        // 分组副标「内置/自定义 · N 项」（fs-11 onSurfaceVar）
        description = (if (parent.isDefaultGroup) "内置" else "自定义") + " · ${parent.children.size} 项",
        collapsible = true,
        expandedOverride = parent.isExpanded,
        onExpandedChange = { viewModel.toggleParentExpanded(parent.id) },
        headerTrailing = if (parent.isEditable) {
            // ── 自定义分组：新增到本组 / 分享 / 重命名 / 删除 ──
            // zrow 行尾 faint 文字钮（＋/分享/编辑/删除，fs-11 onSurfaceFaint；
            // 已复制态 success 色沿用）
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space10),
                ) {
                    FaintTextAction("＋", "新增命令到本分组") { showAddToolDialog = true }
                    FaintTextAction(
                        label = if (copyFeedback.isCopied) "已复制" else "分享",
                        contentDescription = if (copyFeedback.isCopied) "已复制" else "分享全部（复制 JSON 到剪贴板）",
                        color = if (copyFeedback.isCopied) LocalEasyToolboxColors.current.success else null,
                    ) {
                        viewModel.exportCustomJson(emptyList()) { json ->
                            copyFeedback.copy(json)
                        }
                    }
                    FaintTextAction("编辑", "重命名分组") {
                        renameError = null
                        showRenameDialog = true
                    }
                    // 默认分组不可删除（工具迁移兜底目标）
                    if (!parent.isDefaultGroup) {
                        FaintTextAction("删除", "删除分组") { showDeleteConfirm = true }
                    }
                }
            }
        } else {
            null
        },
    ) {
        // 展开的子节点（搜索过滤时仅渲染 isVisible 的子节点）
        // 计数 pill 数据源：useCountByNodeId 按 id 查表——
        // Normal 节点键 = body.value（历史 id = 命令串）、Chain/其余节点键 = node.id
        val useCounts by viewModel.useCountByNodeId.collectAsState()
        // 条目节奏：SectionCard 内容区无行距，裸排相邻节点 0 间距贴死（hover 高亮
        // 矩形相互贴合）——节奏在页内补，不动共享卡本体
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space6)) {
            parent.children
                .filterIsInstance<ToolNode.Child>()
                .filter { it.isVisible }
                .forEach { child ->
                    ShellToolsChildNode(
                        child = child.copy(
                            // Normal 历史 id = 命令串（executeNormal 写 command）——优先按命令查；
                            // Chain 历史 id = 节点 id
                            useCount = useCounts[(child.body as? ChildBody.Normal)?.value] ?: useCounts[child.id],
                        ),
                        viewModel = viewModel,
                    )
                }
        }
    }

    // ── 新增命令到本分组（编辑器预选该分组） ──
    if (showAddToolDialog) {
        ShellCustomEditorDialog(
            viewModel = viewModel,
            editingNode = null,
            onDismiss = { showAddToolDialog = false },
            initialGroupId = parent.id,
        )
    }

    // ── 重命名分组（名称占用时对话框保持打开并提示） ──
    if (showRenameDialog) {
        CustomGroupNameDialog(
            title = "重命名分组",
            initialName = parent.name,
            confirmLabel = "重命名",
            errorMessage = renameError,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                if (newName == parent.name) {
                    // 名称未变化，直接关闭
                    showRenameDialog = false
                } else {
                    viewModel.renameCustomGroup(parent.id, newName) { ok ->
                        if (ok) {
                            showRenameDialog = false
                        } else {
                            renameError = "名称已被占用"
                        }
                    }
                }
            },
        )
    }

    // ── 删除分组（组内工具迁移至默认分组，二次确认） ──
    if (showDeleteConfirm) {
        AppConfirmDialog(
            title = "删除分组",
            text = "确定删除分组「${parent.name}」吗？组内 ${parent.children.size} 个命令将移动到「${ShellToolsConfigRepository.DEFAULT_GROUP_NAME}」。",
            isDangerous = true,
            confirmLabel = "删除",
            onConfirm = {
                viewModel.deleteCustomGroup(parent.id)
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/** zrow 行尾 faint 文字钮（fs-11 onSurfaceFaint；点区含 padding）。
 *  热区外扩 32dp（视觉零变化）：clickable 挂外层 Box（minSize = MICS），Text 原样式原 padding——
 *  labelSmall 裸文本命中区约 20px，低于全页 32dp 纪律且行尾含「删除分组」破坏性入口 */
@Composable
private fun FaintTextAction(
    label: String,
    contentDescription: String,
    color: Color? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = Dimens.minimumInteractiveSize, minHeight = Dimens.minimumInteractiveSize)
            .clip(AppShapes.small)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = Dimens.space4, vertical = Dimens.space2)
                .semantics { this.contentDescription = contentDescription },
        )
    }
}
