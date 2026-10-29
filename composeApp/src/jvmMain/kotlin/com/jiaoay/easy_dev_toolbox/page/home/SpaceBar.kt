package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.KNOWLEDGE_URI_PREFIX
import com.jiaoay.easy_dev_toolbox.core.data.db.SpaceStore
import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.RenameDialog
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 调查空间栏：空间列表，空间 = 一次调查的
 * 聚合容器（文件 + 会话 + 产物引用集，SQLite spaces 表）。
 *
 * 宿主渲染点 = AgentCenterPage 第四签（width 由调用方 modifier 决定）；
 * 本文件 UI/状态面能力原样保留。
 *
 * - 列表 = active 空间倒序（updated_at 触达排序）+ 归档区折叠（点击展开）；
 * - 新建空间 / 归档 / **删除**（删聚合根行不级联成员，危险态 AppConfirmDialog
 *   确认）三动作；
 * - 选中态 = [SpaceBarState.currentSpaceId]（宿主真源
 *   [AppViewModel.currentSpaceId]：select/create/默认落位经 onSpaceSelected
 *   回写宿主；宿主侧变更（palette 最近空间选择）经 syncFromHost 回流本栏——
 *   双向闭环）。
 * - 标题行 info 钮 hover 出 [SPACE_INFO_TOOLTIP] 详细说明（气泡配方单源 =
 *   widget/interactions/HoverTooltipBox）；锚点
 *   用 **Below**——info 钮贴窗口顶缘，Right 定位垂直方向无翻转兜底、
 *   tooltip 顶到窗外被裁切；belowPositioning 自带垂直翻转 + 水平钳制，
 *   贴顶锚点 tooltip 下落恒在窗内。
 *   空态文案补「怎么聚东西进来」引导行（[SPACE_EMPTY_HINT]）。
 *
 * 数据面：[SpaceStore] 直连；IO 经 Dispatchers.IO + LaunchedEffect 拉取，
 * 写后局部 refresh（无全局 reactive 源——SQLite 无观察者，改动点自刷本栏足矣，
 * 与 QuestStore 左栏历史列表同款模式）。
 */
@Composable
fun SpaceBar(
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    // 选中态构造注入宿主回写（select/create/默认落位 → AppViewModel.currentSpaceId）
    val state = remember { SpaceBarState(onSpaceSelected = { id -> appViewModel.setCurrentSpace(id) }) }
    LaunchedEffect(Unit) { state.refresh() }
    // 订阅宿主选中态真源——palette 最近空间选择等宿主侧变更回写本栏高亮
    val hostCurrentSpaceId by appViewModel.currentSpaceId.collectAsState()
    LaunchedEffect(hostCurrentSpaceId) { state.syncFromHost(hostCurrentSpaceId) }
    // 写意图消费：UI 回调入队 → 本协程逐条消费（IO 承接，无自建 scope——AppHostGuard 合规；
    // 队列保序——快速连续操作不丢意图）。单条失败只记日志不终止循环——消费协程一旦
    // 退出，队列后续意图将全部静默积压（旧行为靠 key 重启自愈，Channel 形态必须就地兜）
    LaunchedEffect(state) {
        for (intent in state.pendingIntents) {
            try {
                state.consume(intent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                JLog.e("SpaceBar", e) { "写意图消费失败：$intent" }
            }
        }
    }

    val surfaceColors = LocalToolboxSurfaceColors.current
    val currentSpaceId by state.currentSpaceId.collectAsState()
    val spaces by state.spaces.collectAsState()
    val archived by state.archived.collectAsState()
    val archivedExpanded by state.archivedExpanded.collectAsState()
    // 展开态空间 id——展开行下方渲染成员明细面板
    val expandedSpaceId by state.expandedSpaceId.collectAsState()

    // 删除确认：P3 危险操作三级纪律——持久化实体
    // 删除必须确认；待删 id + 展示名（FlowSidePanelContent 同款模式）
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 待重命名 id + 当前名——RenameDialog 复用 :ui 共享件
    // （FlowPage.RenameFlowDialog 同款调用形态；Enter 提交同批承接）
    var pendingRename by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(
        // 宿主渲染点在智能体中心页第四签：宽度不再自持固定窄轨（spaceBarWidth 随主布局
        // 常驻轨退役），由调用方 modifier 决定（第四签 fillMaxWidth 页形态展开）
        modifier = modifier
            .fillMaxHeight()
            .background(surfaceColors.surfaceRaised),
    ) {
        // 标题行：调查空间 + info 说明 + 新建
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.space8, vertical = Dimens.space4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "调查空间",
                    // chead .t：fs-16/650（SemiBold）——非 titleSmall(14/500)
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                SpaceInfoButton()
            }
            // chipbtn hot 带字胶囊：primary 字 + primary×0.10 洗底 + r-pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                modifier = Modifier
                    .clip(AppShapes.capsule)
                    .background(MaterialTheme.colorScheme.primary.copy(AppAlpha.hotWash), AppShapes.capsule)
                    .clickable(onClick = { state.createSpace() })
                    .padding(horizontal = Dimens.space14, vertical = Dimens.space6),
            ) {
                AppSymbolIcon(
                    icon = MaterialSymbols.ADD,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "新建空间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 活跃空间列表
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(spaces, key = { it.id }) { space ->
                Column {
                    SpaceRow(
                        title = space.title,
                        meta = spaceMeta(space),
                        selected = space.id == currentSpaceId,
                        onClick = { state.select(space.id) },
                        onArchive = { state.archive(space.id) },
                        onDelete = { pendingDelete = space.id to space.title },
                        // 行尾展开/收起钮——展开即见成员明细面
                        onToggleExpand = { state.toggleExpand(space.id) },
                        expanded = space.id == expandedSpaceId,
                        // 行尾重命名钮——点击弹 RenameDialog
                        onRename = { pendingRename = space.id to space.title },
                    )
                    if (space.id == expandedSpaceId) {
                        SpaceMemberPanel(
                            space = space,
                            onRemoveMember = { kind, value ->
                                state.removeMember(space.id, kind, value)
                            },
                        )
                    }
                }
            }
            if (spaces.isEmpty()) {
                item {
                    // 空态走 EmptyStateView（ui-style-guide.md §6.2 空态唯一语言——
                    // 四要素：图标+文案+学习提示）。主文案保留全句（卡面 proposal
                    // 剥成「暂无调查」会丢动作引导——裁量上报）；学习提示挂
                    // SPACE_EMPTY_HINT 常量（SpaceBarCopyTest 锚零破坏）；窄栏
                    // LazyColumn item 内 fillParent 不撑满（居中）。
                    EmptyStateView(
                        text = "暂无调查——Ctrl+K 召唤工具，或点右上 + 新建空间",
                        learningHint = SPACE_EMPTY_HINT,
                        fillParent = false,
                    )
                }
            }
        }

        // 归档区（折叠态）
        if (archived.isNotEmpty()) {
            HorizontalDivider(color = surfaceColors.borderSubtle)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.toggleArchived() }
                    .padding(horizontal = Dimens.space8, vertical = Dimens.space6),
            ) {
                AppSymbolIcon(
                    icon = if (archivedExpanded) MaterialSymbols.EXPAND_MORE else MaterialSymbols.CHEVRON_RIGHT,
                    contentDescription = if (archivedExpanded) "收起归档" else "展开归档",
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "归档（${archived.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = surfaceColors.onSurfaceMid,
                    modifier = Modifier.padding(start = Dimens.space4),
                )
                // 折叠条副注「收起旧调查 · 点击行取消归档」
                Text(
                    text = "收起旧调查 · 点击行取消归档",
                    style = MaterialTheme.typography.labelSmall,
                    color = surfaceColors.onSurfaceFaint,
                    modifier = Modifier.padding(start = Dimens.space8),
                )
            }
            if (archivedExpanded) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(archived, key = { it.id }) { space ->
                        SpaceRow(
                            title = space.title,
                            meta = spaceMeta(space),
                            selected = false,
                            onClick = { state.unarchive(space.id) },
                            onArchive = null,
                            onDelete = { pendingDelete = space.id to space.title },
                            // 归档区行也挂重命名——归档空间可改名
                            onRename = { pendingRename = space.id to space.title },
                        )
                    }
                }
            }
        }

        // 重命名对话框：复用 :ui 共享 RenameDialog（保存后列表行标题即时
        // 更新——consume 分支 refreshList 全量拉取；持久化走 store.rename 落 SQLite）
        pendingRename?.let { (id, title) ->
            RenameDialog(
                title = "重命名空间",
                initial = title,
                onSave = { state.rename(id, it) },
                onDismiss = { pendingRename = null },
            )
        }

        // 删除确认对话框（危险态）：删聚合根行不级联成员（SpaceStore.delete 契约）
        pendingDelete?.let { (id, title) ->
            AppConfirmDialog(
                title = "删除调查空间",
                text = "将删除「$title」（仅移除聚合引用，不删除成员文件/会话/产物本体）。此操作不可撤销。",
                isDangerous = true,
                confirmLabel = "删除",
                onConfirm = {
                    state.delete(id)
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null },
            )
        }

        // 底注：命令入口提示（简短版——与 info Tooltip 详细版分层）
        Text(
            text = SPACE_FOOTER,
            style = MaterialTheme.typography.labelSmall,
            color = surfaceColors.onSurfaceFaint,
            modifier = Modifier.padding(Dimens.space8),
        )
    }
}

/**
 * 标题行 info 说明钮的 Tooltip 详文：
 * 「空间是什么 + 归档语义」。internal 常量 = 扫描面测试锚（SpaceBarCopyTest
 * 钉死三要件：聚合容器定义 / 归档收起语义 / 自动记入承诺）。
 *
 * 「自动记入」承诺由会话创建/run 产物自动归属当前空间
 * （addFlows/addRuns）兑现——文案与接线终态同步，接线失败即文案失实。
 */
internal val SPACE_INFO_TOOLTIP: String = buildString {
    append("调查空间 = 一次调查的聚合容器：文件、会话、产物自动记入当前空间：")
    append("深度分析带入文件，发起会话/研判自动归属。")
    append("归档收起旧调查，新调查一键新建")
}

/**
 * 空态引导行（怎么聚东西进来）：
 * 深度分析路径（DeepAnalyzeHook → 宿主补当前 spaceId → LogWorkbenchDeepLink →
 * SpaceStore.addFiles）+ 会话/研判自动归属（addFlows/addRuns 接线）——
 * 引导行与接线终态同步。
 */
internal const val SPACE_EMPTY_HINT: String =
    "文件经工具产物卡的「深度分析」带入当前空间；发起会话或研判也会自动归入"

/**
 * 底注简短版（与 Tooltip 详细版分层——一行讲清「空间是什么」）。
 * internal 常量 = SpaceBarCopyTest 分层断言锚。
 */
internal const val SPACE_FOOTER: String = "空间 = 一次调查：文件、会话、产物聚在同一个名字下"

/**
 * info 说明钮：hover 气泡配方单源 = widget/interactions/HoverTooltipBox
 * （延迟/isPersistent/手动驱动统一由共享件承载），唯锚点定位不同：**Below**
 * （info 钮贴窗口顶缘，Right 定位垂直无兜底会把 tooltip 顶出窗外；
 * Below 自带垂直翻转 + 水平钳制，贴顶锚点下落恒在窗内）。
 * 纯展示钮：onClick 空实现（气泡容器要求 clickable 面板语义，IconButton
 * 是最省的载体——焦点/无障碍仍可用）。
 */
@Composable
private fun SpaceInfoButton() {
    HoverTooltipBox(
        positioning = TooltipAnchorPosition.Below,
        tooltip = {
            Column {
                Text(
                    text = "调查空间",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = SPACE_INFO_TOOLTIP,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = AppAlpha.contentSoft),
                )
            }
        },
    ) { interactionSource ->
        IconButton(
            onClick = {},
            interactionSource = interactionSource,
            modifier = Modifier
                .size(Dimens.iconMedium)
                .hoverable(interactionSource),
        ) {
            AppSymbolIcon(
                icon = MaterialSymbols.INFO,
                contentDescription = "调查空间说明",
                size = Dimens.iconSmall,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空间行 meta 文案：成员计数 + 更新时间 */
private fun spaceMeta(space: SpaceStore.SpaceSummary): String {
    val members = space.filePaths.size + space.flowIds.size + space.runIds.size
    val date = SimpleDateFormat("M/d", Locale.getDefault()).format(Date(space.updatedAt))
    return "$members 项成员 · $date"
}

@Composable
private fun SpaceRow(
    title: String,
    meta: String,
    selected: Boolean,
    onClick: () -> Unit,
    onArchive: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
    // 展开/收起钮（null = 无展开面——归档区行不挂）
    onToggleExpand: (() -> Unit)? = null,
    expanded: Boolean = false,
    // 重命名钮（null = 不挂——与删除钮同款可选语义）
    onRename: (() -> Unit)? = null,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    // 行 hover 洗底（.frow:hover = primary×hoverWash）：hover 洗底经
    // interactionSource（clickable 的 source——enabled 恒真发射有效）；
    // 选中实底档与优先序单源 = selectionHoverWashColor
    val hoverSource = remember { MutableInteractionSource() }
    val rowHovered by hoverSource.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = hoverSource, indication = null, onClick = onClick)
            .background(
                selectionHoverWashColor(
                    hovered = rowHovered,
                    selected = selected,
                    selectedColor = surfaceColors.selectedContainer,
                ),
            )
            .padding(horizontal = Dimens.space8, vertical = Dimens.space6),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = surfaceColors.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 展开/收起钮（行尾首位——成员明细面入口）
        if (onToggleExpand != null) {
            IconButton(
                onClick = onToggleExpand,
                modifier = Modifier.size(Dimens.iconSmall),
            ) {
                AppSymbolIcon(
                    icon = if (expanded) MaterialSymbols.EXPAND_MORE else MaterialSymbols.CHEVRON_RIGHT,
                    contentDescription = if (expanded) "收起成员明细" else "展开成员明细",
                    size = Dimens.iconSmall,
                    tint = surfaceColors.onSurfaceFaint,
                )
            }
        }
        // 重命名钮（展开后、归档前——ShellToolsParentNode 分组行钮序先例）
        // ic-btn 带字小钮：「改名」「归档」「删」——IconButton 无字漂移形态不取
        if (onRename != null) {
            IcBtnAction(label = "改名", contentDescription = "重命名空间", onClick = onRename)
        }
        if (onArchive != null) {
            IcBtnAction(label = "归档", contentDescription = "归档空间", onClick = onArchive)
        }
        // 删除钮：归档钮旁常显小钮——删除是三级危险操作，先经确认对话框
        if (onDelete != null) {
            IcBtnAction(label = "删", contentDescription = "删除空间", onClick = onDelete)
        }
    }
}

// ── 空间成员明细面 ──

/**
 * 成员类别标签（面板小节标题 + 移除动作 kind 真源）：文件 / 会话 / 产物。
 * knowledge:// 成员（蒸馏知识入库归属）物理上在 filePaths 列表里，
 * 面板按 URI 前缀拆成独立小节「知识库」显示（剥前缀——面板不显示裸串）。
 */
internal data class SpaceMemberSection(
    val label: String,
    val entries: List<String>,
    /** 行尾移除钮回传的成员类别（移除时按原始存储类别定位——知识成员物理在 FILE 列表） */
    val kind: SpaceStore.MemberKind,
)

internal fun memberSectionsOf(space: SpaceStore.SpaceSummary): List<SpaceMemberSection> =
    buildList {
        val plainFiles = space.filePaths.filterNot { it.startsWith(KNOWLEDGE_URI_PREFIX) }
        val knowledge = space.filePaths.filter { it.startsWith(KNOWLEDGE_URI_PREFIX) }
        if (plainFiles.isNotEmpty()) {
            add(SpaceMemberSection("文件", plainFiles, SpaceStore.MemberKind.FILE))
        }
        if (knowledge.isNotEmpty()) {
            // 知识成员物理在 FILE 列表——移除按 FILE 类别定位，仅显示剥前缀
            add(SpaceMemberSection("知识库", knowledge, SpaceStore.MemberKind.FILE))
        }
        if (space.flowIds.isNotEmpty()) {
            add(SpaceMemberSection("会话", space.flowIds, SpaceStore.MemberKind.FLOW))
        }
        if (space.runIds.isNotEmpty()) {
            add(SpaceMemberSection("产物", space.runIds, SpaceStore.MemberKind.RUN))
        }
    }

internal fun memberDisplayName(value: String): String = when {
    value.startsWith(KNOWLEDGE_URI_PREFIX) -> value.removePrefix(KNOWLEDGE_URI_PREFIX)
    else -> pathFileName(value)
}

@Composable
private fun SpaceMemberPanel(
    space: SpaceStore.SpaceSummary,
    onRemoveMember: (SpaceStore.MemberKind, String) -> Unit,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    Column(
        // 成员面板容器左缘 1dp 发丝竖线（.detail border-left
        // hairline——明细面从主行缩进的层级视觉语言；drawBehind 单侧线，Compose
        // border 无 side-only 形态）
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.space12, end = Dimens.space8, bottom = Dimens.space6)
            .drawBehind {
                drawLine(
                    // var(--border-hairline) → borderHairline 槽（发丝色单源——
                    // 勿用 borderSubtle，与 SectionCard 描边保持同色源）
                    color = surfaceColors.borderHairline,
                    start = Offset.Zero,
                    end = Offset(0f, size.height),
                    strokeWidth = Dimens.glassStrokeHairline.toPx(),
                )
            },
    ) {
        memberSectionsOf(space).forEach { section ->
            Text(
                text = "${section.label} ${section.entries.size}",
                style = MaterialTheme.typography.labelSmall,
                color = surfaceColors.onSurfaceMid,
                modifier = Modifier.padding(top = Dimens.space4, bottom = Dimens.space2),
            )
            section.entries.forEach { value ->
                // 成员行 hover 洗底（.frow:hover primary×hoverWash）
                // ——行本身不可点（删除钮才有动作），纯 hoverable 通道
                val memberHover = remember { MutableInteractionSource() }
                val memberHovered by memberHover.collectIsHoveredAsState()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 成员行 padding sp-6×sp-8（space4 过密）
                    modifier = Modifier
                        .fillMaxWidth()
                        .hoverable(memberHover)
                        .background(
                            selectionHoverWashColor(hovered = memberHovered),
                            shape = AppShapes.small,
                        )
                        .padding(horizontal = Dimens.space8, vertical = Dimens.space6),
                ) {
                    Text(
                        text = memberDisplayName(value),
                        style = MaterialTheme.typography.labelSmall,
                        color = surfaceColors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 删除钮 hover 变 error 色（成员行 hover 态规格）。
                    // hover 通道 = IconButton 自身 interactionSource（enabled 恒 true 不受
                    // foundation Clickable.kt:1927 `if (enabled)` 门控）。
                    // 命中区 = minimumInteractiveSize（32dp ≥ WCAG 2.2 目标 24px）——
                    // 按钮 16dp 视觉档的图标居中其上，命中区不再低于可达性下限
                    val removeInteraction = remember { MutableInteractionSource() }
                    val removeHovered by removeInteraction.collectIsHoveredAsState()
                    IconButton(
                        onClick = { onRemoveMember(section.kind, value) },
                        modifier = Modifier.size(Dimens.minimumInteractiveSize),
                        interactionSource = removeInteraction,
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.DELETE,
                            contentDescription = "移除该成员",
                            size = Dimens.iconSmall,
                            tint = if (removeHovered) {
                                MaterialTheme.colorScheme.error
                            } else {
                                surfaceColors.onSurfaceFaint
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 空间栏状态面：自持 StateFlow + SpaceStore 直连。
 * 🔴 线程纪律：**无自建 scope**（AppHostGuardTest 守护）——写动作走「意图入队 +
 * Composable LaunchedEffect 消费」模式（[pendingIntents] 无界队列），refresh 为挂起函数
 * 由消费方协程承接；选中态变更纯内存即时生效。
 *
 * 选中态变更同步回写宿主 [AppViewModel.setCurrentSpace]（App 级
 * currentSpaceId 真源——深链泛化/FileBuffer→空间写入消费）——[onSpaceSelected]
 * 构造注入（Composable 侧传入 `{ appViewModel.setCurrentSpace(it) }`）。
 */
class SpaceBarState(
    private val onSpaceSelected: (String?) -> Unit = {},
) {
    private val store = SpaceStore(Database.database())

    private val _spaces = MutableStateFlow<List<SpaceStore.SpaceSummary>>(emptyList())
    val spaces: StateFlow<List<SpaceStore.SpaceSummary>> = _spaces.asStateFlow()

    private val _archived = MutableStateFlow<List<SpaceStore.SpaceSummary>>(emptyList())
    val archived: StateFlow<List<SpaceStore.SpaceSummary>> = _archived.asStateFlow()

    private val _archivedExpanded = MutableStateFlow(false)
    val archivedExpanded: StateFlow<Boolean> = _archivedExpanded.asStateFlow()

    private val _currentSpaceId = MutableStateFlow<String?>(null)
    val currentSpaceId: StateFlow<String?> = _currentSpaceId.asStateFlow()

    /** 成员明细面展开态——展开的空间 id（null = 全收起；单展开） */
    private val _expandedSpaceId = MutableStateFlow<String?>(null)
    val expandedSpaceId: StateFlow<String?> = _expandedSpaceId.asStateFlow()

    /** 选中态单点写（自持流 + 宿主 currentSpaceId 真源同步回写） */
    private fun selectSpace(id: String?) {
        _currentSpaceId.value = id
        onSpaceSelected(id)
    }

    fun syncFromHost(id: String?) {
        _currentSpaceId.value = id
    }

    /**
     * 待消费的写意图队列（create/archive/unarchive/delete/rename/removeMember——
     * UI 回调入队，LaunchedEffect 逐条消费）。🔴 无界 Channel 而非单值 StateFlow：
     * 单槽会被后到意图覆写——快速连续两个写操作（如连发两次移除成员）前一个静默丢失；
     * 每个用户明确操作意图都必须到达。
     */
    private val _pendingIntents = Channel<SpaceIntent>(Channel.UNLIMITED)
    val pendingIntents: ReceiveChannel<SpaceIntent> = _pendingIntents

    sealed interface SpaceIntent {
        data object Create : SpaceIntent
        data class Archive(val id: String) : SpaceIntent
        data class Unarchive(val id: String) : SpaceIntent

        /** 删除空间：删聚合根行不级联成员。 */
        data class Delete(val id: String) : SpaceIntent

        data class Rename(val id: String, val title: String) : SpaceIntent

        data class RemoveMember(
            val spaceId: String,
            val kind: SpaceStore.MemberKind,
            val value: String,
        ) : SpaceIntent
    }

    private fun enqueue(intent: SpaceIntent) {
        _pendingIntents.trySend(intent)
    }

    /** 消费写意图（LaunchedEffect 调用——挂起面，IO 承接）+ 全量刷新列表。 */
    suspend fun consume(intent: SpaceIntent) {
        withContext(Dispatchers.IO) {
            when (intent) {
                is SpaceIntent.Create -> {
                    // id 用 UUID：毫秒时间戳在快速连续创建场景可撞主键（flowId/runId 同形态）
                    val id = "space-${java.util.UUID.randomUUID()}"
                    store.create(id = id, title = "新调查 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}")
                    selectSpace(id)
                }
                is SpaceIntent.Archive -> store.archive(intent.id, archived = true)
                is SpaceIntent.Unarchive -> store.archive(intent.id, archived = false)
                is SpaceIntent.Rename -> {
                    // 重命名（store.rename 落 SQLite updatedAt 同触达——
                    // 持久化走 store，重启后仍为新名；空名防呆不落库）
                    if (intent.title.isNotBlank()) {
                        store.rename(intent.id, title = intent.title)
                    }
                }
                is SpaceIntent.Delete -> {
                    store.delete(intent.id)
                    // 删的是当前选中空间时选中态失效——回落列表首个（空则 null）
                    if (_currentSpaceId.value == intent.id) {
                        selectSpace(null)
                    }
                    // 展开态随行消失收起（防展开态指向已删空间）
                    if (_expandedSpaceId.value == intent.id) {
                        _expandedSpaceId.value = null
                    }
                }
                is SpaceIntent.RemoveMember -> {
                    // 单成员移除（幂等——路径/id 不存在时无操作）；展开态保持
                    store.removeMember(intent.spaceId, intent.kind, intent.value)
                }
            }
            refreshList()
            // 选中态回落：refreshList 后若当前选中已被删且列表非空，落首个
            //（refreshList 内只处理「从未选中」的默认落位，不处理「选中被删」）
            if (_currentSpaceId.value == null && _spaces.value.isNotEmpty()) {
                selectSpace(_spaces.value.first().id)
            }
        }
    }

    /** 全量拉取刷新两列表（挂起面；意图消费后/首次进入调用）。 */
    private suspend fun refreshList() {
        withContext(Dispatchers.IO) {
            val all = store.listSpaces(includeArchived = true)
            _spaces.value = all.filter { it.status == SpaceStore.SpaceStatus.ACTIVE }
            _archived.value = all.filter { it.status == SpaceStore.SpaceStatus.ARCHIVED }
            if (_currentSpaceId.value == null && _spaces.value.isNotEmpty()) {
                selectSpace(_spaces.value.first().id)
            }
        }
    }

    /** 拉取全量（首次进入——挂起面）。 */
    suspend fun refresh() = refreshList()

    /** UI 动作：入队（真正执行由 LaunchedEffect 对 [pendingIntents] 的逐条消费承接）。 */
    fun createSpace() = enqueue(SpaceIntent.Create)
    fun archive(id: String) = enqueue(SpaceIntent.Archive(id))
    fun unarchive(id: String) = enqueue(SpaceIntent.Unarchive(id))
    fun delete(id: String) = enqueue(SpaceIntent.Delete(id))

    /** 重命名空间（SpaceRow 行尾 EDIT 钮消费——意图入队，IO 由消费协程承接）。 */
    fun rename(id: String, title: String) = enqueue(SpaceIntent.Rename(id, title))

    /** 移除单成员（成员明细面行尾钮消费——意图入队，IO 由消费协程承接）。 */
    fun removeMember(id: String, kind: SpaceStore.MemberKind, value: String) =
        enqueue(SpaceIntent.RemoveMember(id, kind, value))

    /** 展开/收起成员明细面（纯内存态——再次点击同 id 即收起）。 */
    fun toggleExpand(id: String) {
        _expandedSpaceId.value = if (_expandedSpaceId.value == id) null else id
    }

    fun select(id: String) {
        selectSpace(id)
    }

    fun toggleArchived() {
        _archivedExpanded.value = !_archivedExpanded.value
    }
}

/** SpaceRow 行尾 ic-btn 带字小钮（.ic-btn 配方——fs-11 + 5×12 +
 *  r-sm + surface-raised 底 + hairline 边） */
@Composable
private fun IcBtnAction(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(AppShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimens.space12, vertical = Dimens.space4)
            .semantics { this.contentDescription = contentDescription },
    )
}
