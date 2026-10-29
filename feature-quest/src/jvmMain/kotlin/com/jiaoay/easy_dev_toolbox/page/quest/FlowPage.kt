package com.jiaoay.easy_dev_toolbox.page.quest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskField
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskRequest
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskUserTool
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.data.db.QuestStore
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.RenameDialog
import com.jiaoay.easy_dev_toolbox.widget.ChoiceField
import com.jiaoay.easy_dev_toolbox.widget.FileDropZone
import com.jiaoay.easy_dev_toolbox.widget.GateConfirmOverlay
import com.jiaoay.easy_dev_toolbox.widget.GhostCard
import com.jiaoay.easy_dev_toolbox.widget.MultiChoiceField
import com.jiaoay.easy_dev_toolbox.widget.PageDensity
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.chat.ChatComposer
import com.jiaoay.easy_dev_toolbox.widget.chat.ChatMessageList
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import com.jiaoay.easy_dev_toolbox.widget.interactions.onEnterKey
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.io.File

/** 主区「最近会话」卡可见行数（最近 5 条） */
private const val RECENT_FLOWS_VISIBLE = 5

@Composable
fun FlowPage() {
    // 骨架 = PageScaffold(density = Dense)（padding space16 / 间距 space12）；
    // 两栏密度页不启用限宽。
    PageScaffold(
        // 称谓单源 = ToolPageLabels.Flow「会话」（composeApp 域，feature-quest
        // 不可 import 故直写字面量），改称谓双改。
        title = "会话",
        density = PageDensity.Dense,
    ) {
        FlowPageContent()
    }
}

@Composable
fun FlowPageContent() {
    val vm: FlowViewModel = viewModel { FlowViewModel() }
    val flows by vm.flows.collectAsState()
    val abilityOptions = remember { vm.abilityOptions }
    val selectedCaps by vm.selectedCapabilities.collectAsState()
    val goal by vm.goalInput.collectAsState()
    val activeFlow by vm.activeFlow.collectAsState()
    val entries by vm.entries.collectAsState()
    val running by vm.running.collectAsState()
    val startInFlight by vm.startInFlight.collectAsState()
    val pendingQueue by vm.pendingQueue.collectAsState()
    val pendingQuestion by vm.pendingQuestion.collectAsState()
    val pendingGate by vm.pendingGate.collectAsState()
    val aiEnabled by vm.aiEnabled.collectAsState()

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
    ) {
        // 左栏（历史流程列表）移入 Start 侧栏槽位（FlowContributions 注册）；
        // 主区保留新建表单 / 会话流两态。

        // ── 主区：两态（新建表单 / 会话流） ──
        val active = activeFlow
        if (active == null) {
            NewFlowForm(
                modifier = Modifier.weight(1f),
                abilityOptions = abilityOptions,
                selectedCaps = selectedCaps,
                goal = goal,
                aiEnabled = aiEnabled,
                creating = startInFlight,
                // 最近会话卡随表单同滚（卡进 NewFlowForm 内部——外包 Column
                // 会动 weight 链破坏滚动契约）；数据源 vm.flows 零 VM 改动。
                recentFlows = flows,
                onOpenFlow = vm::openFlow,
                onRenameFlow = vm::renameFlow,
                onDeleteFlow = vm::deleteFlow,
                onToggleCap = vm::toggleCapability,
                onGoalChange = vm::setGoalInput,
                onStart = vm::startFlow,
            )
        } else {
            FlowChat(
                modifier = Modifier.weight(1f),
                title = active.title,
                entries = entries,
                running = running,
                pendingCount = pendingQueue.size,
                pendingQueueFull = pendingQueue.size >= QuestSession.PENDING_QUEUE_MAX,
                pendingQuestion = pendingQuestion,
                pendingGate = pendingGate,
                onSend = vm::sendMessage,
                onCancel = vm::cancelRun,
                onResolveAsk = vm::resolveAsk,
                onSkipAsk = vm::skipAsk,
                onApproveGate = vm::approveGate,
                onDenyGate = vm::denyGate,
                onFilesSelected = vm::registerFiles,
                onMarkActive = vm::markActive,
            )
        }
    }
}

// ── 新建表单（ListMode 主区） ──

/**
 * Start 侧栏槽位内容（历史流程列表——从页内左栏迁出）。
 * 槽位是页面作用域（切页销毁重建，VM 经 viewModel{} 与页内同实例）。
 */
@Composable
internal fun FlowSidePanelContent() {
    val vm: FlowViewModel = viewModel { FlowViewModel() }
    val flows by vm.flows.collectAsState()
    val activeFlow by vm.activeFlow.collectAsState()
    val running by vm.running.collectAsState()
    val pendingQueue by vm.pendingQueue.collectAsState()

    // 持久化实体删除一律确认：待删流程 id + 展示名
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Q2 · 运行中退出确认：exitFlow 对 running 直接 cancel（排队插话一并弃）——
    // 与同页 deleteFlow 的确认卡对齐（一紧一松收口）；空闲时直退零摩擦
    var pendingExit by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        if (activeFlow != null) {
            OutlinedButton(
                onClick = {
                    if (running) pendingExit = true else vm.exitFlow()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("返回新建表单")
            }
        }
        if (flows.isEmpty()) {
            // 空态四要素唯一语言 EmptyStateView（ui-style-guide.md §6.2）；
            // fillParent=false：侧栏 verticalScroll Column 内行内紧凑（非整屏居中）。
            EmptyStateView(
                text = "尚无会话",
                learningHint = "开启新会话后，agent 会主动向你索要所需信息",
                icon = MaterialSymbols.FORUM,
                fillParent = false,
                // 稿空态 = card dashed 虚线降级盒（空态语义容器变体）
                dashed = true,
            )
        }
        flows.forEach { flow ->
            var renaming by remember(flow.id) { mutableStateOf(false) }
            HoverableRow(
                shape = AppShapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    // hover 反馈归 HoverableRow 洗底独占，抑制默认矩形 indication
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { vm.openFlow(flow.id) },
                actionContent = { isHovered ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                        TextButton(onClick = { renaming = true }, enabled = isHovered) { Text("改名") }
                        TextButton(
                            // 删流程含 SQLite 记录不可逆 → 先确认
                            onClick = { pendingDelete = flow.id to flow.title },
                            enabled = isHovered,
                        ) {
                            Text("删", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        flow.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        color = if (flow.id == activeFlow?.id) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        if (flow.capabilities.isEmpty()) "通用对话" else flow.capabilities.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (renaming) {
                RenameDialog(
                    title = "重命名会话",
                    initial = flow.title,
                    onSave = { vm.renameFlow(flow.id, it) },
                    onDismiss = { renaming = false },
                )
            }
        }
    }

    // 删除确认对话框（AppConfirmDialog isDangerous）
    pendingDelete?.let { (id, title) ->
        AppConfirmDialog(
            title = "删除会话",
            text = "将删除会话「$title」及其全部消息记录，不可恢复。",
            confirmLabel = "删除",
            isDangerous = true,
            onConfirm = {
                vm.deleteFlow(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // 运行中退出确认卡（Q2——文案必须点明排队插话一并弃，防「暂离一会」误操作）
    if (pendingExit) {
        AppConfirmDialog(
            title = "返回新建表单",
            text = "当前会话正在运行：返回将中止本轮运行，已排队的 ${pendingQueue.size} 条插话一并丢弃（不可恢复）。",
            confirmLabel = "中止并返回",
            isDangerous = true,
            onConfirm = {
                vm.exitFlow()
                pendingExit = false
            },
            onDismiss = { pendingExit = false },
        )
    }
}

@Composable
private fun NewFlowForm(
    modifier: Modifier,
    abilityOptions: List<Pair<String, String>>,
    selectedCaps: Set<String>,
    goal: String,
    aiEnabled: Boolean,
    /** 创建在途（busy 锁——双击双建会话的 UI 半边，VM 侧另有 in-flight 守卫） */
    creating: Boolean,
    // 最近会话卡数据源与动作接线（调用点唯一 = FlowPageContent，带必传非默认）
    recentFlows: List<QuestStore.FlowSummary>,
    onOpenFlow: (String) -> Unit,
    onRenameFlow: (String, String) -> Unit,
    onDeleteFlow: (String) -> Unit,
    onToggleCap: (String) -> Unit,
    onGoalChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        // 聚合页子页密度档统一——会话签顶层区块间距对齐 Dense 档同值 12
        //（Distill/Voice 两签已 12；SectionCard 内部间距不动）。
        verticalArrangement = Arrangement.spacedBy(Dimens.space12),
    ) {
        // 页题档 fs-20（headlineMedium，与 PageHeader 全局页题档统一）
        Text("开启新会话", style = MaterialTheme.typography.headlineMedium)
        SectionCard(title = "能力挑选") {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space12)) {
                // 接实际能力注册面；本版空目录 = 通用对话隐式能力引导。
                // 描述行不重复「全不选=通用对话」——该说明已在 MultiChoiceField 的 label 里
                Text(
                    if (abilityOptions.isEmpty()) {
                        "描述你的目标，agent 会主动向你索要所需信息后执行。\n" +
                            "目前可用：通用对话（日志分析 / 语音纪要能力即将接线）。"
                    } else {
                        "描述你的目标，agent 会主动向你索要所需信息后执行。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MultiChoiceField(
                    label = if (abilityOptions.isEmpty()) {
                        "能力（暂无注册能力——通用对话）"
                    } else {
                        "能力（可多选；全不选 = 通用对话）"
                    },
                    options = abilityOptions.map { it.first },
                    selected = selectedCaps,
                    onToggle = onToggleCap,
                    // chips 传中文 labels（能力 id 裸露对用户不可读）
                    // 英文 id（"log-analysis"/"voice-minutes"），目录中文 title 字段零消费
                    labels = abilityOptions.toMap(),
                )
            }
        }
        SectionCard(title = "目标") {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space12)) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = onGoalChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("描述本次会话的目标，例如「分析这份日志里相机启动失败的原因」") },
                    minLines = 2,
                )
                // 置灰引导（spec：AI 关闭时置灰不隐藏——流程页无确定性管线兜底）；
                // 主操作统一 AppButton（置灰必带原因，禁静默置灰）
                AppButton(
                    label = "开始",
                    // busy 接创建在途（硬编码 false 可双击双建——VM in-flight 守卫静默吞，
                    // busy 锁让第二次点击不可达）
                    busy = creating,
                    busyLabel = "创建中…",
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = aiEnabled && goal.isNotBlank(),
                    disabledReason = when {
                        !aiEnabled -> "AI 功能未开启——请先到「AI 设置」页开启"
                        goal.isBlank() -> "先描述本次会话的目标"
                        else -> null
                    },
                )
                if (!aiEnabled) {
                    Text(
                        "AI 功能未开启——请先到「AI 设置」页开启后再开始会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 第二张 SectionCard「最近会话」——历史可达性脱离 inspector
        // 开合状态（inspector 收起/窄窗轨时的新建表单态兜底）；卡随表单同滚。
        RecentFlowsCard(
            flows = recentFlows,
            onOpenFlow = onOpenFlow,
            onRenameFlow = onRenameFlow,
            onDeleteFlow = onDeleteFlow,
        )
    }
}

/**
 * ListMode 主区「最近会话」卡（会话历史入口脱离 inspector 的兜底面）。
 *
 * [SectionCard] 默认收起（不抢新建表单视觉）；[flows] 取最近 [RECENT_FLOWS_VISIBLE] 条
 * （vm.flows = [QuestStore.listFlows] updated_at 倒序）经 HoverableRow 渲染，行配方与
 * [FlowSidePanelContent] 同构；空 flows 渲染 [GhostCard] 提示卡（全量历史在侧栏）。
 * 改名/删除各自持独立局部态（onSave 同一 [QuestStore] 入口幂等，与侧栏双开零冲突）；
 * 删除走 [AppConfirmDialog] isDangerous。本卡只在 NewFlowForm 分支组合——活跃会话
 * 已占屏（ChatMode）时零渲染，结构天然保证。
 */
@Composable
private fun RecentFlowsCard(
    flows: List<QuestStore.FlowSummary>,
    onOpenFlow: (String) -> Unit,
    onRenameFlow: (String, String) -> Unit,
    onDeleteFlow: (String) -> Unit,
) {
    // 持久化实体删除一律确认：待删会话 id + 展示名
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }

    SectionCard(
        title = "最近会话",
        description = "inspector 收起 / 窄窗时的历史兜底——取最近 5 条",
        collapsible = true,
        initialExpanded = false,
    ) {
        if (flows.isEmpty()) {
            // 空 flows：GhostCard 提示卡（历史会话全量在侧栏 inspector）
            GhostCard {
                Text(
                    "尚无历史会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "历史会话在右侧栏（inspector）可查全部",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            flows.take(RECENT_FLOWS_VISIBLE).forEach { flow ->
                var renaming by remember(flow.id) { mutableStateOf(false) }
                HoverableRow(
                    shape = AppShapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        // hover 反馈归 HoverableRow 洗底独占，抑制默认矩形 indication
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onOpenFlow(flow.id) },
                    actionContent = { isHovered ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                            TextButton(onClick = { renaming = true }, enabled = isHovered) { Text("改名") }
                            TextButton(
                                // 删会话含 SQLite 记录不可逆 → 先确认
                                onClick = { pendingDelete = flow.id to flow.title },
                                enabled = isHovered,
                            ) {
                                Text("删", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            flow.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (flow.capabilities.isEmpty()) "通用对话" else flow.capabilities.joinToString("、"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (renaming) {
                    RenameDialog(
                        title = "重命名会话",
                        initial = flow.title,
                        onSave = { onRenameFlow(flow.id, it) },
                        onDismiss = { renaming = false },
                    )
                }
            }
            Text(
                "……历史会话在右侧栏（inspector）可查全部",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 删除确认对话框（AppConfirmDialog isDangerous）——主区卡独立局部态
    pendingDelete?.let { (id, title) ->
        AppConfirmDialog(
            title = "删除会话",
            text = "将删除会话「$title」及其全部消息记录，不可恢复。",
            confirmLabel = "删除",
            isDangerous = true,
            onConfirm = {
                onDeleteFlow(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

// ── 会话流（ChatMode 主区） ──

@Composable
private fun FlowChat(
    modifier: Modifier,
    title: String,
    entries: List<ChatEntry>,
    running: Boolean,
    pendingCount: Int,
    pendingQueueFull: Boolean,
    pendingQuestion: AskRequest?,
    pendingGate: File?,
    onSend: (String) -> Boolean,
    onCancel: () -> Unit,
    onResolveAsk: (Map<String, String>) -> Unit,
    onSkipAsk: () -> Unit,
    onApproveGate: () -> Unit,
    onDenyGate: () -> Unit,
    onFilesSelected: (List<File>) -> Unit,
    onMarkActive: (Boolean) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    // S5b · 可见性回写（组合 = true / 离场 = false）——完成通知「当前 tab ≠ 会话页」判据
    DisposableEffect(Unit) {
        onMarkActive(true)
        onDispose { onMarkActive(false) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        ChatMessageList(
            entries = entries,
            modifier = Modifier.weight(1f),
            // 贴底跟随 contentVersion（BottomFollowScroll 契约——封顶后 itemCount 不再
            // 变化，流式增量只有内容变化信号能触发跟随；hashCode 覆盖尾条目文本增量）
            contentVersion = entries.hashCode().toLong() + entries.size,
            // S5d · 产物 chip 点击 → 深链跳工作台（DeepAnalyzeHook :core 中立槽，宿主
            // AppViewModel 已 wire 到 LogWorkbenchDeepLink.offer + setCurrentPage——零新管线）
            onOpenArtifact = { path ->
                DeepAnalyzeHook.offer(listOf(path), null)
            },
        )
        // ── S5c · Steer 排队提示行（running 期 Enter 入队的可见回执）──
        if (pendingCount > 0) {
            // 行首 .dotrun 状态点——排队回执走「点+文字」状态语言（与活动点同族）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(Dimens.dotIndicatorSize)
                        .background(
                            // .dotrun = var(--info)；「进行中」点全仓语义源 = info 蓝；
                            // 昼战坐 infoContainer 档（design-doc.md §5.3，dayBattleTint 图形 3.0 线）
                            color = dayBattleTint(
                                LocalEasyToolboxColors.current.info,
                                LocalEasyToolboxColors.current.infoContainer,
                            ),
                            shape = androidx.compose.foundation.shape.CircleShape,
                        ),
                )
                Text(
                    if (pendingQueueFull) {
                        "已排队 $pendingCount 条（队列已满——本轮结束后再插话）"
                    } else {
                        "已排队 $pendingCount 条（本轮结束后自动发送）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Dimens.space6),
                )
            }
        }
        // ── AskCard / HITL 确认卡：固定在 Composer 上方（非时间线内非模态） ──
        pendingQuestion?.let { request ->
            AskCard(
                request = request,
                onResolve = onResolveAsk,
                onSkip = onSkipAsk,
            )
        }
        // 过渡/末值缓存/抢焦装配内聚在共享 GateConfirmOverlay（不传
        // focusRequester 则卡体不挂 focusable——焦点仍驻 ChatComposer，Enter
        // 被输入框吞掉把草稿当插话入队、Esc 死键，卡面「Enter 允许 · Esc 拒绝」
        // 键帽提示成假承诺）；本页落位保持 Composer 上方、不迁右下
        //（右下落位只限工作台页——零 modifier 注入）。
        val gateFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        GateConfirmOverlay(
            visible = pendingGate != null,
            path = pendingGate,
            onApprove = onApproveGate,
            onDeny = onDenyGate,
            focusRequester = gateFocus,
        )
        FileDropZone(
            title = "拖入文件作为附件",
            dragTitle = "松手添加附件",
            desc = "日志 / 转写文本等，拖入即登记进围栏",
            onFilesSelected = onFilesSelected,
            height = Dimens.questDropZoneHeight,
        )
        ChatComposer(
            text = input,
            onTextChange = { input = it },
            running = running,
            onSend = {
                if (input.isNotBlank()) {
                    // 队满被拒（send 回执 false）保留输入——本轮结束后重提，不吞用户文字
                    if (onSend(input.trim())) input = ""
                }
            },
            onCancel = onCancel,
            placeholder = "回答 / 追问（Enter 发送，Shift+Enter 换行）",
            // S5c · running 期 Enter = 插话入队（send 内部排队；收尾 drain 自动续发）
            onQueue = {
                if (input.isNotBlank()) {
                    if (onSend(input.trim())) input = ""
                }
            },
        )
    }
}

// ── AskCard：agent 主动索要信息的表单卡 ──

/** 多选字段值的本地分隔符（MultiChoiceField 选中集 → 单值字符串回传） */
private const val MULTI_CHOICE_SEPARATOR = "、"

@Composable
private fun AskCard(
    request: AskRequest,
    onResolve: (Map<String, String>) -> Unit,
    onSkip: () -> Unit,
) {
    // 字段本地态（id → 值；request 每次 remember 重置——一次提问一份草稿）
    var values by remember(request) { mutableStateOf(mapOf<String, String>()) }

    fun currentAnswer(): Map<String, String> = if (request.fields.isEmpty()) {
        mapOf(AskUserTool.PROMPT_ANSWER_KEY to (values[AskUserTool.PROMPT_ANSWER_KEY] ?: ""))
    } else {
        values
    }

    // 键盘裁决：进入抢焦（首字段/无字段输入框）——非模态卡进场即获键盘通道
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(request) { runCatching { focusRequester.requestFocus() } }

    // 多字段 Enter 逐字段下移（防任一字段回车误提交整卡）；
    // 仅最后一个可输入字段（Text/FilePath）的 Enter 提交整卡
    val focusManager = LocalFocusManager.current
    val lastEnterableField = request.fields.lastOrNull { it is AskField.Text || it is AskField.FilePath }
    fun enterBehavior(field: AskField) {
        if (field === lastEnterableField) onResolve(currentAnswer()) else focusManager.moveFocus(FocusDirection.Down)
    }

    TonalCard(modifier = Modifier.fillMaxWidth()) {
        // 卡头「Agent 需要补充信息」+ 行尾 pill ai「AskCard · 主动提问」（AG 域形态）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text(
                "Agent 需要补充信息",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            PillLabel(
                text = "AskCard · 主动提问",
                container = LocalEasyToolboxColors.current.aiAccentContainer,
                contentColor = LocalEasyToolboxColors.current.aiAccent,
            )
        }
        Text(request.question, style = MaterialTheme.typography.bodyMedium)
        if (request.fields.isEmpty()) {
            // 无字段自由文本：单输入框，回传约定键 PROMPT_ANSWER_KEY
            OutlinedTextField(
                value = values[AskUserTool.PROMPT_ANSWER_KEY] ?: "",
                onValueChange = { values = values + (AskUserTool.PROMPT_ANSWER_KEY to it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    // Enter 提交（单行 Enter 语义唯一配方）
                    .onEnterKey { onResolve(currentAnswer()) },
                placeholder = { Text("输入你的回答") },
                singleLine = true,
            )
        } else {
            request.fields.forEach { field ->
                when (field) {
                    is AskField.Text -> OutlinedTextField(
                        value = values[field.id] ?: "",
                        onValueChange = { values = values + (field.id to it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            // 首个字段抢焦（进入卡内 Tab 流即达）
                            .then(
                                if (field === request.fields.first()) {
                                    Modifier.focusRequester(focusRequester)
                                } else {
                                    Modifier
                                },
                            )
                            .onEnterKey { enterBehavior(field) },
                        placeholder = { Text(field.placeholder ?: field.label) },
                        singleLine = true,
                    )
                    is AskField.Choice -> ChoiceField(
                        label = field.label,
                        options = field.options,
                        selected = values[field.id] ?: "",
                        onSelect = { values = values + (field.id to it) },
                    )
                    is AskField.MultiChoice -> MultiChoiceField(
                        label = field.label,
                        options = field.options,
                        selected = values[field.id]?.split(MULTI_CHOICE_SEPARATOR)?.toSet() ?: emptySet(),
                        onToggle = { option ->
                            val cur = values[field.id]?.split(MULTI_CHOICE_SEPARATOR)?.toSet() ?: emptySet()
                            val next = if (option in cur) cur - option else cur + option
                            values = values + (field.id to next.joinToString(MULTI_CHOICE_SEPARATOR))
                        },
                    )
                    is AskField.FilePath -> OutlinedTextField(
                        value = values[field.id] ?: "",
                        onValueChange = { values = values + (field.id to it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onEnterKey { enterBehavior(field) },
                        placeholder = { Text(field.placeholder ?: "文件或目录的绝对路径") },
                        singleLine = true,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 提交 = AppButton Primary 实底主钮 + 暂无补充 = Secondary
            AppButton(label = "提交", busy = false, onClick = { onResolve(currentAnswer()) })
            if (request.allowSkip) {
                AppButton(label = "暂无补充", busy = false, kind = AppButtonKind.Secondary, onClick = onSkip)
            }
            // 键面提示行：「Enter 逐字段下移，末字段提交整卡」（多字段）
            // /「Enter 提交」（单字段）
            Text(
                if (request.fields.size > 1) {
                    "Enter 逐字段下移，末字段提交整卡"
                } else {
                    "Enter 提交"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.space8),
            )
        }
    }
}

