package com.jiaoay.easy_dev_toolbox.page.ai.distill

import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.data.db.KnowledgeRepository
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.BottomFollowScrollEffect
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.DotState
import com.jiaoay.easy_dev_toolbox.widget.FormRow
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.StatusDot
import com.jiaoay.easy_dev_toolbox.widget.chat.ToolDisplayNames
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TagsField
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 知识蒸馏页（四段闭环：入口表单 → 计划卡审批 → 运行面板 → 审阅卡）。
 *
 * ## 形态学（「确定性基座 + AI 增强层」叙事）
 * - **入口表单**：源码目录显示（配置真源在 AI 设置页——本页只读显示 + 引导跳转）；
 *   功能模块描述必填；entry hints chips 可留空（agent 自搜兜底——兜底文案明示）；
 * - **计划卡审批**（plan mode 模式）：候选文件清单 + 选取理由 + 勾选态 → 三选一
 *   （批准执行 / 重新探索 / 作废本次计划——作废显式出口，回表单且内容保留）；
 * - **运行面板**：report_step 结构化进度条（subject/activeForm/stage）+ 工具调用卡流
 *   （ChatEntry 渲染——LazyColumn 由外部滚动容器承载，此处为纵向滚动降级形态）；
 * - **审阅卡**：候选逐条 接受/驳回 + sourceRefs citation 展示（kind 徽章 + 标题 + 正文）；
 * - **验证面**（REVIEW 阶段尾部）：收尾总结 + 「已产 N 候选 / 已接受 M」提示。
 */
@Composable
fun DistillPage() {
    PageScaffold(title = "知识蒸馏") {
        DistillPageContent()
    }
}

/**
 * 无头内容变体：五阶段主体原样，无 PageScaffold——
 * 供宿主 AgentCenterPage 子页签内嵌（聚合页自有页头，消除双页头/双 padding）。
 * VM 经 viewModel{} 窗口级驻留——双形态组合同实例。
 */
@Composable
fun DistillPageContent() {
    val vm: DistillViewModel = androidx.lifecycle.viewmodel.compose.viewModel { DistillViewModel() }
    val phase by vm.phase.collectAsState()
    val running by vm.running.collectAsState()
    val featureDesc by vm.featureDesc.collectAsState()
    val entryHints by vm.entryHints.collectAsState()
    val planItems by vm.planItems.collectAsState()
    val planRepo by vm.planRepo.collectAsState()
    val reviewRows by vm.reviewRows.collectAsState()
    val executedFiles by vm.executedPlanFiles.collectAsState()
    val ingestError by vm.ingestError.collectAsState()
    val progress by vm.progress.collectAsState()
    val entries by vm.entries.collectAsState()
    val phaseResult by vm.phaseResult.collectAsState()
    // 作废回执横幅
    val abandonReceiptVisible by vm.abandonReceiptVisible.collectAsState()

    // 删内层 padding(space16)——PageScaffold 已带
    // screenPadding 24，双重叠加 24+16 属样板漂移；verticalScroll 保留（页内滚动
    // 责任在此 Column，非骨架）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.space12),
    ) {
        if (phase == DistillViewModel.Phase.FORM) {
            // 作废回执横幅（banner.i 形态）：作废动作置位、
            // 重新探索清除；仅 FORM 阶段渲染（approvePlan 后 phase 切走自然不显示）
            if (abandonReceiptVisible) {
                StatusBanner(
                    status = StatusBannerStatus.Info,
                    text = "已作废上轮计划——表单内容已保留，可直接修改后重新探索。",
                )
            }
            DistillForm(
                vm = vm,
                featureDesc = featureDesc,
                entryHints = entryHints,
                onStart = vm::startExplore,
            )
            KbManagePanelContent(
                vm = vm,
            )
        }

        if (phase == DistillViewModel.Phase.EXPLORING) {
            DistillRunning(
                phaseLabel = "只读探索",
                progress = progress,
                entries = entries,
                running = running,
                onCancel = vm::cancel,
                onExitStuck = vm::exitStuckPhase,
            )
        }

        if (phase == DistillViewModel.Phase.PLAN_REVIEW) {
            DistillPlanReview(
                repo = planRepo,
                items = planItems,
                onToggle = vm::togglePlanItem,
                onApprove = vm::approvePlan,
                onReexplore = vm::startExplore,
                onAbandon = vm::abandonPlan,
            )
        }

        if (phase == DistillViewModel.Phase.DISTILLING) {
            DistillRunning(
                phaseLabel = "执行蒸馏",
                progress = progress,
                entries = entries,
                running = running,
                onCancel = vm::cancel,
                onExitStuck = vm::exitStuckPhase,
            )
        }

        if (phase == DistillViewModel.Phase.REVIEW) {
            DistillReview(
                vm = vm,
                rows = reviewRows,
                summary = phaseResult,
                executedFiles = executedFiles,
                entryHints = entryHints,
                ingestError = ingestError,
                onRestart = vm::restart,
            )
            // 成品查看闭环（add-distill-output-viewer）：REVIEW 下方即渲染管理面 +
            // 自动展开本次蒸馏 repo（planRepo 真源）——「跑完即可看」零跳转
            if (planRepo != null && reviewRows.isNotEmpty()) {
                AppButton(
                    label = "查看入库结果",
                    busy = false,
                    kind = AppButtonKind.Secondary,
                    onClick = { vm.viewDistillOutput() },
                )
            }
            KbManagePanelContent(
                vm = vm,
            )
        }
    }
}


// ── ① 入口表单 ──

/** 源码目录只读显示（配置真源在 AI 设置页——本页拉取快照经 collectAsState 驱动重组） */
@Composable
private fun rememberSourceRoots(): List<String> {
    val roots = androidx.compose.runtime.remember { MutableStateFlow<List<String>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            roots.value = com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore.getSourceRoots()
        }
    }
    return roots.collectAsState().value
}

@Composable
private fun DistillForm(
    vm: DistillViewModel,
    featureDesc: String,
    entryHints: List<String>,
    onStart: () -> Unit,
) {
    val sourceRoots = rememberSourceRoots()
    SectionCard(title = "蒸馏对象", description = "从源码蒸馏日志分析知识（TAG/约束/决策树/黄金样本），经审阅入库供 agent 检索") {
        if (sourceRoots.isEmpty()) {
            // trailing 内联「去 AI 设置」钮（横幅指路文字配就地动作；
            // :core 中立槽 GoAiSettingsHook，宿主未接线时点击静默 no-op——槽位契约）
            StatusBanner(
                status = StatusBannerStatus.Error,
                text = "未配置源码目录——请到「AI 设置 → 源码目录」添加（蒸馏只读取已授权目录内的文件）",
                trailing = {
                    AppButton(
                        label = "去 AI 设置",
                        busy = false,
                        kind = AppButtonKind.Secondary,
                        onClick = { com.jiaoay.easy_dev_toolbox.core.navigation.GoAiSettingsHook.offer() },
                    )
                },
            )
        } else {
            // 源码目录 = frow 三段行
            //（lab「源码目录（只读）」+ hint「真源在 AI 设置页」中段 + ctl 右缘
            // kv mono 键值条流）；kv = mono 档 + surface-deep 底 + r-sm
            //（纯文本段不取三处漂移形态）
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                FormRow(
                    label = "源码目录（只读）",
                    hint = "真源在 AI 设置页",
                ) {
                    // ctl 段：多目录纵向 kv 条流（稿两枚 kv 竖排 margin-top sp-4 直译）
                    Column(horizontalAlignment = Alignment.End) {
                        sourceRoots.forEach { root ->
                            Text(
                                text = root,
                                style = DesktopTypography.mono,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .background(
                                        LocalToolboxSurfaceColors.current.surfaceDeep,
                                        AppShapes.small,
                                    )
                                    .padding(horizontal = Dimens.space8, vertical = Dimens.space6),
                            )
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = featureDesc,
            onValueChange = vm::setFeatureDesc,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("功能模块描述（必填）") },
            placeholder = { Text("例：设置中心（SettingsPresenter / 主题状态机 / 配置持久化）") },
            minLines = 2,
        )
        // entry hints 用 TagsField 逐行编辑——OutlinedTextField 受控 join/split 回写
        // （joinToString("、") 渲染 + split 回写）会导致输入顿号瞬间整框重排、光标跳尾；
        // TagsField 逐行 hover 删除 + 回车追加，onTagsChange List<String> 直通
        // vm::setEntryHints（VM 签名本就是 List<String>；DistillSession prompt 拼接
        // joinToString("、") 对 List 直通零感知）。TagsField 无 placeholder 参数——
        // 占位语义由 supportingText 承担。
        TagsField(
            label = "入口提示（可选）",
            tags = entryHints,
            onTagsChange = vm::setEntryHints,
            supportingText = "类名/方法名/TAG，逐条回车添加；留空时 agent 从功能描述自行探索（自搜兜底）",
        )

        // 主操作统一 AppButton——置灰必带原因（禁静默置灰铁律）
        AppButton(
            label = "开始探索（只读）",
            busy = false,
            kind = AppButtonKind.Primary,
            onClick = onStart,
            disabledReason = when {
                featureDesc.isBlank() -> "先填写功能模块描述"
                sourceRoots.isEmpty() -> "先在 AI 设置页配置源码目录"
                else -> null
            },
        )
    }
}

// ── ② 计划卡审批 ──

@Composable
private fun DistillPlanReview(
    repo: String?,
    items: List<DistillViewModel.PlanRow>,
    onToggle: (Int) -> Unit,
    onApprove: () -> Unit,
    onReexplore: () -> Unit,
    // 作废显式出口——回 FORM 且表单内容保留
    onAbandon: () -> Unit,
) {
    SectionCard(
        title = "执行计划审批",
        description = "agent 找到的相关文件（勾选进执行清单——不勾不读）。批准后开始执行蒸馏",
    ) {
        if (repo != null) {
            Text(
                text = "仓库标识：$repo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (items.isEmpty()) {
            StatusBanner(
                status = StatusBannerStatus.Error,
                text = "计划为空——agent 未找到相关文件。可改写功能描述或补充入口提示后重探",
            )
        }
        items.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.medium)
                    .clickable { onToggle(index) }
                    .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = row.checked, onCheckedChange = null)
                Column(modifier = Modifier.padding(start = Dimens.space12)) {
                    // 路径类 mono 语言（fs-12 mono 档）
                    Text(
                        row.path,
                        style = DesktopTypography.monoTag,
                    )
                    Text(
                        row.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
            // 批准主操作统一 AppButton——未勾选任何项时置灰带原因
            AppButton(
                label = "批准并执行蒸馏",
                busy = false,
                onClick = onApprove,
                disabledReason = if (items.none { it.checked }) "先在清单中勾选要蒸馏的文件" else null,
            )
            AppButton(
                label = "重新探索",
                busy = false,
                kind = AppButtonKind.Secondary,
                onClick = onReexplore,
            )
            // 作废显式出口——回 FORM 且表单内容保留（低危不挂确认框）
            AppButton(
                label = "作废本次计划",
                busy = false,
                kind = AppButtonKind.Secondary,
                onClick = onAbandon,
            )
        }
        Text(
            // VM 窗口级驻留（切签/关 tab 不作废）——「作废」由上钮显式承载，文案须含出口尾巴
            "勾改清单后「批准并执行蒸馏」，或「重新探索」另起一轮；「作废本次计划」回 FORM 并保留表单内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── ③ 运行面板 ──

@Composable
private fun DistillRunning(
    phaseLabel: String,
    progress: DistillSession.DistillProgressState?,
    entries: List<ChatEntry>,
    running: Boolean,
    onCancel: () -> Unit,
    /** 相位滞留出口（running 已落但相位未切——失败收口瞬态兜底） */
    onExitStuck: () -> Unit,
) {
    SectionCard(title = phaseLabel, description = "agent 正在工作——进度实时上报，产出的知识候选先暂存待审") {
        progress?.let { p ->
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Dimens.space12), verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                    Text(p.activeForm, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${p.stage} · ${p.subject}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(p.progressHint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (running) {
            // 中止钮 AppButton(Destructive)——与语音页同档，跨页不分叉
            AppButton(
                label = "中止",
                busy = false,
                kind = AppButtonKind.Destructive,
                onClick = onCancel,
            )
        } else {
            // 出口钮不随 running 消失：running 已落而相位未切（失败收口瞬态/意外
            // 滞留）= 相位无出口死端——常驻兜底出路（探索回表单 / 执行回审阅）
            AppButton(
                label = if (phaseLabel == "执行蒸馏") "返回审阅面" else "返回表单",
                busy = false,
                kind = AppButtonKind.Secondary,
                onClick = onExitStuck,
            )
        }
        // 工具调用卡流：定高窗内 LazyColumn 自滚 + 贴底跟随（页级 verticalScroll
        // 外层下全量 forEach 组合会让新卡恒落在视口外；heightIn(max) 给出界约束）。
        // 零动画铁律照旧（消息流动画禁令）。
        val entryListState = rememberLazyListState()
        BottomFollowScrollEffect(
            listState = entryListState,
            itemCount = entries.size,
        )
        LazyColumn(
            state = entryListState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = Dimens.consoleHeightMax),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            items(entries, key = { it.id }) { entry ->
                DistillEntryRow(entry)
            }
        }
    }
}
// ── ④ 审阅卡 ──

@Composable
private fun DistillReview(
    vm: DistillViewModel,
    rows: List<DistillViewModel.ReviewRow>,
    summary: String?,
    executedFiles: List<String>,
    entryHints: List<String>,
    ingestError: String?,
    onRestart: () -> Unit,
) {
    val acceptedCount = rows.count { it.state == DistillViewModel.ReviewState.ACCEPTED }
    SectionCard(
        title = "候选审阅",
        description = "逐条核验依据（file:line）后决定接受/驳回——接受的候选直接写入知识库",
    ) {
        if (rows.isEmpty()) {
            EmptyStateView(
                text = "无候选产出（可能被中止或未蒸馏出知识）",
                actionLabel = "重新开始",
                onAction = onRestart,
                fillParent = false,
            )
            return@SectionCard
        }
        Text(
            "共 ${rows.size} 条候选 · 已接受 $acceptedCount",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ingestError != null) {
            StatusBanner(
                status = StatusBannerStatus.Error,
                text = "入库失败：$ingestError（该候选保持待审——可重试或驳回）",
            )
        }
        rows.forEach { row ->
            ReviewCard(row = row, onAccept = { vm.acceptProposal(row.proposal.seq) }, onReject = { vm.rejectProposal(row.proposal.seq) })
        }
        if (summary != null) {
            // 收尾总结 = 蒸馏报告正文面——可选中复制（ShellOutputPanel 控制台同款配方）
            SelectionContainer {
                Text(
                    "收尾总结：$summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AppButton(
            label = "重新开始",
            busy = false,
            kind = AppButtonKind.Secondary,
            onClick = onRestart,
        )
    }

    // ── 验证面（tasks 4.4：实读清单 + hints 命中标注 + 补 hints 提示） ──

    SectionCard(title = "验证面", description = "本轮实读文件与入口提示命中标注——未命中多说明探索靠自搜，补 hints 可收敛下一轮计划") {
        if (executedFiles.isEmpty()) {
            Text("无实读清单（探索中止于批准前——无文件被读取）")
        } else {
            val hitFlags = vm.hintHitFlags(executedFiles, entryHints)
            Text(
                "实读 ${executedFiles.size} 个文件（hints 命中 ${hitFlags.count { it }} / ${hitFlags.size}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            executedFiles.forEachIndexed { index, path ->
                Text(
                    text = (if (hitFlags.getOrElse(index) { false }) "✓ " else "· ") + path,
                    style = DesktopTypography.monoTag,
                    color = if (hitFlags.getOrElse(index) { false }) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        // 未命中是提示性弱档非错误语义（hint 档 onSurfaceFaint）
                        com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current.onSurfaceFaint
                    },
                )
            }
            if (entryHints.isNotEmpty() && hitFlags.none { it }) {
                Text(
                    "全部 hints 未命中——探索是 agent 自搜完成的。补 hints 可让下一轮计划更收敛（也可不补）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── 单条审阅卡 ──

@Composable
private fun ReviewCard(
    row: DistillViewModel.ReviewRow,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors.current
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Dimens.space12), verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                AppSymbolIcon(icon = MaterialSymbols.SCHOOL, contentDescription = null)
                Text(
                    "[${row.proposal.kind}] ${row.proposal.title}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // 候选状态标签 pill 化（container 直出）
                PillLabel(
                    text = when (row.state) {
                        DistillViewModel.ReviewState.PENDING -> "待审"
                        DistillViewModel.ReviewState.ACCEPTED -> "已接受"
                        DistillViewModel.ReviewState.REJECTED -> "已驳回"
                    },
                    container = when (row.state) {
                        DistillViewModel.ReviewState.PENDING -> com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current.surfaceDeep
                        DistillViewModel.ReviewState.ACCEPTED -> colors.successContainer
                        DistillViewModel.ReviewState.REJECTED -> colors.errorContainerFixed
                    },
                    contentColor = when (row.state) {
                        DistillViewModel.ReviewState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        DistillViewModel.ReviewState.ACCEPTED -> colors.success
                        DistillViewModel.ReviewState.REJECTED -> MaterialTheme.colorScheme.error
                    },
                )
            }
            // 候选正文可选中复制（蒸馏产出「带走入档」动线——ShellOutputPanel 控制台同款配方）
            SelectionContainer {
                Text(row.proposal.body, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "来源：${row.proposal.sourceRefs.joinToString("、")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.state == DistillViewModel.ReviewState.PENDING) {
                // 审阅卡操作走 AppButton Primary/Secondary（busy 全 false——入库重试
                // pending 态后续卡接）
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                    AppButton(
                        label = "接受",
                        busy = false,
                        kind = AppButtonKind.Primary,
                        onClick = onAccept,
                    )
                    AppButton(
                        label = "驳回",
                        busy = false,
                        kind = AppButtonKind.Secondary,
                        onClick = onReject,
                    )
                }
            }
        }
    }
}

// ── ChatEntry 渲染降级（ChatMessageList 降级兼容——tasks 4.4） ──

@Composable
private fun DistillEntryRow(entry: ChatEntry) {
    // 工具行流行首 6dp 三态状态点（.dotok=已完成 / .dotrun=运行中·已取消 /
    // .doterr=失败，srow 结构）
    when (entry) {
        is ChatEntry.Activity -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space6)) {
            StatusDot(DotState.OK)
            Text(
                entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ChatEntry.Assistant -> Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        is ChatEntry.ToolCall -> Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space6)) {
            StatusDot(
                when (entry.state) {
                    ToolState.RUNNING -> DotState.RUN
                    ToolState.COMPLETED -> DotState.OK
                    ToolState.FAILED, ToolState.CANCELLED -> DotState.ERR
                },
            )
            Text(
                // E1 命名单源：工具名经 ToolDisplayNames 中文化；状态枚举同步中文化
                "${ToolDisplayNames.display(entry.toolName) ?: entry.toolName}（${entry.state.displayText()}）" +
                    "${entry.observation?.let { " → $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = when (entry.state) {
                    ToolState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        is ChatEntry.ErrorBanner -> Text(
            "⛔ ${entry.title}${entry.detail?.let { "：$it" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> {}
    }
}
