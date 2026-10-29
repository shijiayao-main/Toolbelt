package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.widget.glass.appFloatShadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.buffer.FileBuffer
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.evidence.LogLine
import com.jiaoay.easy_dev_toolbox.core.evidence.ReadLogLinesResult
import com.jiaoay.easy_dev_toolbox.core.evidence.findEvidenceFileOf
import com.jiaoay.easy_dev_toolbox.core.evidence.readLogLinesResult
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.DotState
import com.jiaoay.easy_dev_toolbox.widget.StatusDot
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.currentWindowSizeClass
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.CommitNumberField
import com.jiaoay.easy_dev_toolbox.widget.CommitTextField
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.FileDropZone
import com.jiaoay.easy_dev_toolbox.widget.GateConfirmOverlay
import com.jiaoay.easy_dev_toolbox.widget.GhostChipButton
import com.jiaoay.easy_dev_toolbox.widget.PageDensity
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.RenameDialog
import com.jiaoay.easy_dev_toolbox.widget.evidence.EvidenceTimeline
import com.jiaoay.easy_dev_toolbox.widget.evidence.evidenceTimelineLineItemOffset
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * 日志工作台三栏页（调查工作台 S4：中栏证据流块化——渲染层上移 :ui/widget/evidence）。
 *
 * 布局：[PageScaffold]（density = [PageDensity.Dense]，三栏密度页
 * 不启用限宽）+ 整页 Box（HITL 确认卡固定右下叠加域）内 Row(weight 1f)——
 * 左栏 [Dimens.workbenchSidePanelWidth]
 * 输入表单与 run 列表（回归页内左栏，滚动归
 * [WorkbenchSidePanelContent] 内部既有 Column，外层只定宽不重复包滚动）、
 * 中栏 weight(1f) 块化证据流、右栏 [Dimens.workbenchReportPanelWidth]（Wide
 * 屏 400dp 加宽档）报告。
 * 固定宽全走 token（零白名单目标）；引用跳转 scrollToItem（animateScrollToItem 被 UiGuard 拦）。
 *
 * 流式零动画铁律：日志行/条目列表不做逐行动画；直方图一次 Canvas 入场
 * （AppMotion.standard 单值动画）；±N 上下文展开走 AnimatedVisibility(softSpring)
 * ——交互件非流式件（ToolCallCard 折叠先例）。
 *
 * S4 块化：中栏 = 琥珀块（工具产物行流，块头 = 阶段名+产物+迷你直方图+「设为当前」）
 * + 紫块（AI 研判时间线）——渲染在 :ui/widget/evidence；jumpToTimestamp 跨块定位
 * 经 evidenceTimelineLineItemOffset 修正块头 item 前置偏移。
 */
@Composable
fun LogWorkbenchPage() {
    val vm: WorkbenchViewModel = viewModel { WorkbenchViewModel() }
    val logFiles by vm.logFiles.collectAsState()
    val targetTime by vm.targetTime.collectAsState()
    val rangeMinutes by vm.rangeMinutes.collectAsState()
    val symptom by vm.symptom.collectAsState()
    val tagRegex by vm.tagRegex.collectAsState()
    val runs by vm.runs.collectAsState()
    // entries 不在根作用域收集（重组下钻——契约见 WorkbenchEvidenceStream KDoc）：
    // 流式 delta 发布（合并窗后节奏）只重组中栏消息列表子组件，不整页重组。
    val running by vm.running.collectAsState()
    val pipelineRunning by vm.pipelineRunning.collectAsState()
    // busy = AI 研判 ∥ 管线直跑（中止钮/报告面板态在直跑期间同样可见）
    val busy = running || pipelineRunning
    val reportText by vm.reportText.collectAsState()
    val exportedReportPath by vm.exportedReportPath.collectAsState()
    // 导出脱敏开关态（内存态不持久化——默认关 = 逐字保真现状）
    val anonymizeExport by vm.anonymizeExport.collectAsState()
    // 回看第三态：报告文件读取失败路径（右栏错误态渲染依据）
    val reportReadErrorPath by vm.reportReadError.collectAsState()
    val caseDraft by vm.caseDraft.collectAsState()
    val evidenceFiles by vm.evidenceFiles.collectAsState()
    val pendingGate by vm.pendingGate.collectAsState()
    val aiEnabled by vm.aiEnabled.collectAsState()
    // TodoBoard 计划投影：todoBoard 是 TodoBoard 非 StateFlow，
    // collect 面是 .items（session 级实例——newSession 重建即清空，切换 run 不清板）
    val todos by vm.todoBoard.items.collectAsState()

    // 证据文件选择态（中栏行流数据源——默认末位产物 = 最完整产物）
    var activeEvidence by remember { mutableStateOf<String?>(null) }
    var evidenceLines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    // 截断探测结果：null = 无产物零提示；truncated=false 时行数徽标零渲染
    var evidenceTruncation by remember { mutableStateOf<ReadLogLinesResult?>(null) }
    // 读取中指示：三处读取协程（初载/重读定位/证据卡点击）
    // 期间行流保持旧值换血在途——用户可见「正在读取」即知行流待换，连点多卡无
    // 旧内容残留错觉。置位约定：协程前置 true，「readLogLinesResult 返回即置
    // false」对称三处（滚动定位是 UI 线程后续动作，非协程尾）。
    var evidenceLoading by remember { mutableStateOf(false) }
    LaunchedEffect(evidenceFiles) {
        val target = activeEvidence?.takeIf { it in evidenceFiles } ?: evidenceFiles.lastOrNull()
        activeEvidence = target
        // 产物文件读取（2000 行解包）下放 Dispatchers.IO——同步读大文件会卡主线程
        evidenceLoading = true
        val result = target?.let {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readLogLinesResult(File(it)) }
        }
        evidenceLoading = false
        evidenceLines = result?.lines ?: emptyList()
        evidenceTruncation = result
    }

    // ── 深链消费（pending 槽一次性取走——工具页产物「深度分析」预填；不启动分析）。
    // input.spaceId 非 null 时经 SpaceStore.addFiles 把产物写入空间成员集
    // （「拖进会话才授权」之外的主动聚合路径——深链 offer 宿主补 spaceId）。
    // input.runId 非 null 时走回看语义（vm.openRun——restore
    // 时间线 + 报告回填，VM 公有方法现成）；null 保持「预填不回看」。
    // input.targetTime 非 null 时随 prefill 归一写入目标时间框（logcat 标记锚点
    // 派生——标记过则自动聚焦复现时刻）；null 不触碰时间域。
    // 消费成功后 toast 回执「已带入 N 个文件（并预填症状/并聚焦复现时刻）」；
    // logFiles 为空不 toast（回看深链 offerRunReview 的
    // logFiles 默认空列表——「已带入 0 个文件」是噪音，只对非空回执）。
    //
    // 消费形态 = 双 effect（ToolsPage 预选槽同款配方）：组合期 LaunchedEffect(Unit)
    // 一次性 consume 承担首次载荷；下方订阅 effect 承担「页面已组合后的新载荷」。
    // 载荷落地分流（预填/回看/空间归属/toast 回执）两路径共用同一本地函数——
    // 语义单一来源，不另造第二套分支。
    fun applyDeepLinkInput(input: LogWorkbenchDeepLink.PendingInput) {
        when (input.runId) {
            null -> {
                vm.prefill(input.logFiles, input.prefillNote, input.targetTime)
                input.spaceId?.let { vm.attachFilesToSpace(it, input.logFiles) }
            }
            else -> {
                // 回看深链：openRun 落空（run 已删）时以 logFiles/targetTime 预填兜底
                vm.openRun(input.runId)
                vm.prefill(input.logFiles, input.prefillNote, input.targetTime)
            }
        }
        if (input.logFiles.isNotEmpty()) {
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                "已带入 ${input.logFiles.size} 个文件" +
                    (if (input.prefillNote != null) "并预填症状" else "") +
                    (if (input.targetTime != null) "并聚焦复现时刻" else ""),
            )
        }
    }
    LaunchedEffect(Unit) {
        LogWorkbenchDeepLink.consume()?.let { input ->
            applyDeepLinkInput(input)
        }
    }

    // 在页响应缝（ToolsPage 预选槽同款）：查看器 Dialog 挂 PageHome 层悬于任何页
    // 上方——工作台保持组合期间也能打开查看器/证据流复现时刻超窗点击并点
    // 「深度分析」，此时宿主 wire 的 setCurrentPage(LogWorkbench) 是同值不发射
    // （AnimatedContent 不重组），上面 LaunchedEffect(Unit) 组合期一次性消费
    // 不会重跑，新载荷将静默 no-op 并滞留槽内（离页返页时才被错误上下文延迟
    // 消费）。订阅槽流（StateFlow 非空即消费）保证在页期间新载荷即时落地；
    // 初值消费序不变（首次仍由上面 LaunchedEffect(Unit) 承担——槽被初值消费后
    // 已清空，本 effect 只接「页面已组合后的新载荷」，天然不双发）。
    LaunchedEffect(LogWorkbenchDeepLink) {
        LogWorkbenchDeepLink.pending
            .filterNotNull()
            .collect { input ->
                // 读即清（consume 同款语义）：collect 直接用收到的载荷，consume
                // 只负责清槽——防离页返页时 LaunchedEffect(Unit) 初值消费重跑
                // 读到已消费过的旧载荷。
                LogWorkbenchDeepLink.consume()
                applyDeepLinkInput(input)
            }
    }

    // ── FileBuffer 暂存区消费面 ──
    // palette「暂存文件到 Buffer」执行后文件在此可见可消费：chip 点击 = 载入本页
    // 分析输入（addTargetFile——即时生效）；× = 移除（FileBuffer.remove）。
    // 消费语义对偶：载入即出暂存（registerFiles 的「拖进会话才授权」同款收口——
    // 本页消费后 FileBuffer.remove 对偶移除，不留在暂存区）。
    val bufferedFiles by FileBuffer.files.collectAsState()
    fun consumeBufferedFile(path: String) {
        vm.addTargetFile(path)
        FileBuffer.remove(path)
        // 点击消费回执——「点击即出暂存」强语义对用户可感知
        com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show("已载入分析输入")
    }

    // 引用跳转目标（右栏报告点击 → 切证据文件 + 滚动到该行 + 高亮）
    var highlightTimestamp by remember { mutableStateOf<String?>(null) }
    // 行内搜索过滤词：页面局部态（evidenceLines 数据面
    // 全量保留——过滤只在 EvidenceTimeline 渲染面内聚）
    var searchQuery by remember { mutableStateOf("") }
    val evidenceListState = rememberLazyListState()
    val jumpScope = rememberCoroutineScope()
    fun jumpToTimestamp(timestamp: String) {
        // 定位含该时间戳的产物文件（找不到就在当前文件里试）
        val file = findEvidenceFileOf(timestamp, evidenceFiles) ?: activeEvidence ?: return
        activeEvidence = file
        highlightTimestamp = timestamp
        // offset 契约：过滤态渲染行 ≠ 全量索引——scrollToItem 在过滤渲染面上
        // 定位会错位（行不在过滤集则落空被钳制尾部）；过滤非空时跳过滚动定位、
        // 高亮仍设（行在过滤集内即显高亮）
        if (searchQuery.isNotBlank()) return
        // P4：同款 IO 下放——先本地索引当前已载行（快路径零等待），再 IO 重读换行
        // entries 事件时直读 .value（本函数是点击/协程事件面非组合面——
        // 根作用域已不收集 entries，事件时值比组合快照更新鲜）
        val currentLines = evidenceLines
        val idx = currentLines.indexOfFirst { it.timestamp == timestamp }
        if (idx >= 0 && currentLines.isNotEmpty() && file == activeEvidence) {
            val physical = idx + evidenceTimelineLineItemOffset(currentLines, vm.entries.value)
            jumpScope.launch { evidenceListState.scrollToItem(physical) }
        }
        jumpScope.launch {
            evidenceLoading = true
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                readLogLinesResult(File(file))
            }
            evidenceLoading = false
            evidenceLines = result.lines
            evidenceTruncation = result
            // 快路径未命中（目标在别的文件）——重读后定位并滚动
            // （scrollToItem 非 animateScrollToItem——UiGuard 禁令 + 零动画铁律）
            val freshIdx = evidenceLines.indexOfFirst { it.timestamp == timestamp }
            if (freshIdx >= 0 && idx < 0) {
                val physical = freshIdx + evidenceTimelineLineItemOffset(evidenceLines, vm.entries.value)
                evidenceListState.scrollToItem(physical)
            } else if (freshIdx < 0 && result.truncated) {
                // 目标行不在前窗且文件已截断——大概率在窗口外（非「不存在」）。
                // 带时间戳直达路由：宿主挂载时经 :core 查看器槽发起带时间戳的
                // 打开请求（请求路径 = 本跳转解析出的产物文件 file——时间戳应
                // 所在的产物单源；宿主打开就绪后引擎 seek 定位到该行，点击→
                // 定位一步完成，用户不再进查看器手动输一遍时间戳）；toast 兼作
                // 回执（页内未跳的原因 + 查看器已开已定位的动作）。
                // 宿主未挂载时降级保持指路 toast（指路对象 = 截断条「查看完整
                // 产物」钮——该钮自身有 hostMounted 降级，指路恒有效可达），
                // 不静默 no-op。
                if (LogViewerRequest.hostMounted.value) {
                    LogViewerRequest.offer(file, initialTimestamp = timestamp, runId = vm.viewerBookmarkRunId())
                    com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                        "该时间戳超出预览范围（前 ${result.lines.size} 行内未命中）——已在应用内查看器打开并定位到该时间戳",
                    )
                } else {
                    com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                        "该时间戳超出预览范围（前 ${result.lines.size} 行内未命中）——可点截断条「查看完整产物」在应用内查看器打开",
                    )
                }
            }
        }
    }

    // 证据 chip 直达查看器（右栏报告 Verified 引用 chip 点击）：引用标记只写裸
    // 产物文件名，此处对会话证据产物集精确匹配解析成路径（引用校验同款口径）；
    // 解析失败 toast 明示不静默 no-op。offer 行号档前查宿主挂载态（initialLine
    // 1 基——查看器窗口分页到该行为窗口首行；与时间戳档并存时行号优先），
    // 未挂载降级指路 toast 不静默（报告依据超窗点击先例同款降级形态）。
    fun citeToViewer(fileName: String, startLine: Int) {
        val file = evidenceFiles.firstOrNull { candidate ->
            candidate.substringAfterLast('/').substringAfterLast('\\') == fileName
        }
        if (file == null) {
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                "证据产物「$fileName」不在当前会话产物集，无法直达查看器",
            )
            return
        }
        if (LogViewerRequest.hostMounted.value) {
            LogViewerRequest.offer(file, initialLine = startLine, runId = vm.viewerBookmarkRunId())
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                "已在应用内查看器打开并定位到第 $startLine 行",
            )
        } else {
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                "应用内查看器未挂载——可按引用时间戳在中栏证据流定位",
            )
        }
    }

    // 整页包 Box(fillMaxSize)——HITL 确认卡固定右下 BottomEnd 叠加
    //（非模态不遮内容流）。
    Box(modifier = Modifier.fillMaxSize()) {
    PageScaffold(
        title = "工作台",
        density = PageDensity.Dense,
        // pill mute「家页 · 启动落点」——唯一页头徽标（工作台特有语义）
        actions = {
            PillLabel(
                text = "家页 · 启动落点",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        // ── FileBuffer 暂存区消费面 ──
        // palette「暂存文件到 Buffer」执行后文件在此可见可消费：chip 点击 = 载入本页
        // 分析输入（addTargetFile 即时生效）+ 出暂存；× = 移除（FileBuffer.remove）。
        // 空 Buffer 零渲染（不占常驻空间）。
        if (bufferedFiles.isNotEmpty()) {
            FileBufferRow(
                files = bufferedFiles,
                onConsume = { path -> consumeBufferedFile(path) },
                onRemove = { path -> FileBuffer.remove(path) },
            )
        }

        // ── Compact 档（<1024dp）三栏降级 ──
        // 左右死宽（280/320dp）+ 中栏 weight 在窄内容区会把中栏证据流压至 0 宽
        // （整页核心区不可见）；Compact 下左右改 weight 等分——三栏全可见可滚，
        // 不触碰栏内滚动/高度契约（RowScope weight 原位保留，无堆叠无界高度风险）
        val compactLayout = currentWindowSizeClass() == WindowSizeClass.Compact

        Row(
            modifier = Modifier.weight(1f),
            // 三栏横向列距 = space8（.wb-cols gap:var(--sp-8) 明定）；
            // 纵向卡距 space14 有据不动
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            // ── 左栏：输入表单 + run 列表（分析主流程起点，与「输入→证据流→报告」
            //    左→右动线一致；WorkbenchSidePanelReturnGuardTest 以本区注为切片锚）──
            // 滚动归 WorkbenchSidePanelContent 内部既有 Column
            //    （fillMaxWidth().verticalScroll）——外层再包 verticalScroll 即双嵌套；
            //    内部 fillMaxWidth 在 width(280dp) 父约束下天然满宽。
            Column(
                modifier = Modifier
                    .then(
                        if (compactLayout) Modifier.weight(1f)
                        else Modifier.width(Dimens.workbenchSidePanelWidth),
                    )
                    .fillMaxHeight(),
                // 左栏卡列卡距走全局卡距档 space14
                verticalArrangement = Arrangement.spacedBy(Dimens.space14),
            ) {
                WorkbenchSidePanelContent()
            }

            // ── 中栏：块化证据流（琥珀块=工具产物 / 紫块=AI 研判）──
            // entries 收敛进 WorkbenchEvidenceStream（子组件内收集——见其 KDoc）
            WorkbenchEvidenceStream(
                evidenceFiles = evidenceFiles,
                activeEvidence = activeEvidence,
                evidenceLines = evidenceLines,
                listState = evidenceListState,
                highlightTimestamp = highlightTimestamp,
                truncation = evidenceTruncation,
                todos = todos,
                // 空证据流动作锚——pickOpenFiles 共享挂起选择器
                //（FileDropZone 同款消费配方 + 同款 accepts 过滤），选择后进表单
                // 文件列表（addTargetFile——与拖入一致）。pickOpenFiles 是 suspend
                // fun，页面侧 jumpScope.launch 包装（复用既有 scope 无泄漏面）。
                onPickFiles = {
                    jumpScope.launch {
                        com.jiaoay.easy_dev_toolbox.widget.pickOpenFiles(
                            title = "选择日志文件（.alog/.xlog/.txt/.diff）",
                            filter = { it.extension in setOf("alog", "xlog", "txt", "diff") },
                        ).forEach { vm.addTargetFile(it.absolutePath) }
                    }
                },
                // 直方图桶点击跳时段——页面侧复用 jumpToTimestamp
                // 的滚动+高亮链（60 桶直方图是天然时段导航位）
                onPickTimestamp = ::jumpToTimestamp,
                // 高亮自清——高亮行被点击（展开）即视为引用
                // 已消费，清残留防「这行还在被引用」误判
                onHighlightConsumed = { highlightTimestamp = null },
                // 三处读取协程期间的行流换血在途指示
                loading = evidenceLoading,
                // 行内搜索——过滤词页面态，组件内聚过滤渲染面
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                // HITL 等待指示——pendingGate 非 null 时紫块头
                // 副文案换「等待路径授权」（agent 挂起等裁决可见化）
                gateWaiting = pendingGate != null,
                onPickEvidence = { path ->
                    activeEvidence = path
                    // P4：产物读取 IO 下放（点击证据卡不卡主线程）
                    jumpScope.launch {
                        evidenceLoading = true
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            readLogLinesResult(File(path))
                        }
                        evidenceLoading = false
                        evidenceLines = result.lines
                        evidenceTruncation = result
                    }
                },
                // TAG chip 双通道语义：就地过滤（写入行内搜索过滤词——当前证据流即时
                // contains 过滤，chip 点击即刻可见）+ 写左栏正则（下次研判生效——原语义保留）
                onTagFilter = { tag ->
                    vm.setTagRegex(tag)
                    searchQuery = tag
                },
                // 截断提示行尾动作：在应用内查看器打开当前产物完整内容（请求路径 =
                // activeEvidence 路径单源；查看器按行偏移索引全量直达，行号跳转/
                // 级别导航/文本搜索不出应用）。宿主未挂载查看器时降级打开产物所在
                // 目录并 toast 明示原因，不静默 no-op（hostMounted = :core 槽挂载信号）。
                onViewFullArtifact = {
                    activeEvidence?.let { path ->
                        if (LogViewerRequest.hostMounted.value) {
                            LogViewerRequest.offer(path, runId = vm.viewerBookmarkRunId())
                            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show("已在应用内查看器打开完整产物")
                        } else {
                            DesktopUtils.openInFolder(path)
                            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show("应用内查看器未挂载，已打开产物所在目录")
                        }
                    }
                },
                onCancel = vm::cancel,
                running = busy,
            )

            // 三栏纯 gap 无分隔线（.wb-cols 形态）

            // ── 右栏：报告面板（Wide 屏加宽档——报告表格最吃
            //    宽度，>1760dp 用 400dp 死档 token；中栏 weight(1f) 自动让宽；
            //    Compact 档随左栏同降级 weight 等分） ──
            Column(
                modifier = Modifier
                    .then(
                        if (compactLayout) Modifier.weight(1f)
                        else Modifier.width(
                            if (currentWindowSizeClass() == WindowSizeClass.Wide) {
                                Dimens.workbenchReportPanelWidthWide
                            } else {
                                Dimens.workbenchReportPanelWidth
                            },
                        ),
                    )
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                // 右栏卡列卡距走全局卡距档 space14
                verticalArrangement = Arrangement.spacedBy(Dimens.space14),
            ) {
                ReportPanel(
                    reportText = reportText,
                    running = busy,
                    aiEnabled = aiEnabled,
                    evidenceFiles = evidenceFiles,
                    // 运行期产物落点提示（报告未落盘前给可预期路径——截断/生成期
                    // 「打开报告文件」钮不渲染时的指路替身）
                    reportPathDuringRun = if (busy && reportText == null) vm.currentReportPathHint() else null,
                    exportedReportPath = exportedReportPath,
                    reportReadErrorPath = reportReadErrorPath,
                    // 报告外发就绪态（:core 中立槽——桥接宿主接线后随运行态发布；
                    // 未接线/未运行恒 false，置灰文案由 larkSendDisabledReason 承担）
                    larkDeliveryReady = com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery.ready.collectAsState().value,
                    anonymizeExport = anonymizeExport,
                    onAnonymizeExportChange = vm::setAnonymizeExport,
                    onFollowUp = vm::followUp,
                    onOpenReport = vm::openCurrentReport,
                    onExportReport = vm::exportCurrentReport,
                    onSendToLark = vm::sendCurrentReportToLark,
                    onArchiveCase = vm::openCaseDraft,
                    onOpenExportFolder = { exportedReportPath?.let { DesktopUtils.openInFolder(it) } },
                    onOpenErrorFolder = { reportReadErrorPath?.let { DesktopUtils.openInFolder(it) } },
                    onJumpToTimestamp = ::jumpToTimestamp,
                    onCiteToViewer = ::citeToViewer,
                )

                // 存案编辑对话框（caseDraft 非空即渲染——四要素预填可改可补，
                // 保存动作 = VM saveCaseArchive 落库专用案例仓）
                caseDraft?.let { draft ->
                    CaseArchiveDialog(
                        draft = draft,
                        onSave = vm::saveCaseArchive,
                        onDismiss = vm::dismissCaseDraft,
                    )
                }
            }
        }
    }

    // ── HITL 围栏确认卡（approve/deny——CompletableDeferred 挂起侧等裁决）：
    //    共享 GateConfirmOverlay 内聚过渡/末值缓存/组合期抢焦（契约见该件 KDoc）；
    //    落位固定右下 BottomEnd 叠加 + 限宽 + 浮层阴影 + 状态栏玻璃带让位，
    //    由本侧 modifier 注入 ──
    val gateFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    GateConfirmOverlay(
        visible = pendingGate != null,
        path = pendingGate,
        onApprove = vm::approveGate,
        onDeny = vm::denyGate,
        focusRequester = gateFocus,
        // 底部让位：整页 Box(fillMaxSize) 在 PageScaffold 外层，BottomEnd+space16
        // 从窗口全域起算——须显式让出状态栏玻璃带（bottom =
        // contentViewportPaddingBottom(50) + space16(16)），否则卡底 18dp 区与
        // 状态栏带几何叠压（右段 Ctrl+K 提示与卡底压带）。
        // 限宽：卡内 fillMaxWidth + align(BottomEnd) 会通栏贴底——右下角卡须限宽
        //（360dp 独立档）；真浮层享 shadow-float。
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(start = Dimens.space16, top = Dimens.space16, end = Dimens.space16)
            .padding(bottom = Dimens.contentViewportPaddingBottom + Dimens.space16)
            .width(Dimens.hitlCardWidth)
            .appFloatShadow(cornerRadius = Dimens.glassSheetRadius),
    )
    }
}

/**
 * 中栏证据流消息列表子组件（entries 消费下钻面）：
 * [WorkbenchViewModel.entries] 只在本组件收集——流式 delta 的 entries 发布
 * 仅重组本子组件，家页根作用域不再随打字机节奏整页重组
 * （Assistant 节流契约的 UI 侧对应物；参数集与 [EvidenceTimeline] 尾随对齐，
 * 唯一差异 = entries 由内部收集）。
 * VM 经 viewModel{} 同 key 解析（WorkbenchSidePanelContent 同款——与页内同实例）。
 * 引用跳转的 offset 换算在页侧事件面直读 vm.entries.value，不经本组件。
 */
@Composable
private fun RowScope.WorkbenchEvidenceStream(
    evidenceFiles: List<String>,
    activeEvidence: String?,
    evidenceLines: List<LogLine>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    highlightTimestamp: String?,
    truncation: ReadLogLinesResult?,
    todos: List<com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoItem>,
    onPickFiles: (() -> Unit)?,
    onPickTimestamp: (String) -> Unit,
    onHighlightConsumed: () -> Unit,
    loading: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    gateWaiting: Boolean,
    onPickEvidence: (String) -> Unit,
    onTagFilter: (String) -> Unit,
    onViewFullArtifact: (() -> Unit)?,
    onCancel: () -> Unit,
    running: Boolean,
) {
    val vm: WorkbenchViewModel = viewModel { WorkbenchViewModel() }
    val entries by vm.entries.collectAsState()
    EvidenceTimeline(
        entries = entries,
        evidenceFiles = evidenceFiles,
        activeEvidence = activeEvidence,
        evidenceLines = evidenceLines,
        listState = listState,
        highlightTimestamp = highlightTimestamp,
        truncation = truncation,
        todos = todos,
        onPickFiles = onPickFiles,
        onPickTimestamp = onPickTimestamp,
        onHighlightConsumed = onHighlightConsumed,
        loading = loading,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        gateWaiting = gateWaiting,
        onPickEvidence = onPickEvidence,
        onTagFilter = onTagFilter,
        onViewFullArtifact = onViewFullArtifact,
        onCancel = onCancel,
        running = running,
    )
}

/**
 * 左栏槽位内容（输入表单 + run 列表）。
 * 内部自带 `fillMaxWidth().verticalScroll` 滚动 Column（外层左栏只定宽
 * [Dimens.workbenchSidePanelWidth]，勿再包滚动——双嵌套冲突）。
 * 页面作用域 VM：viewModel{} 同 key 解析，与页内同实例。
 */
@Composable
internal fun WorkbenchSidePanelContent() {
    val vm: WorkbenchViewModel = viewModel { WorkbenchViewModel() }
    val logFiles by vm.logFiles.collectAsState()
    val targetTime by vm.targetTime.collectAsState()
    val rangeMinutes by vm.rangeMinutes.collectAsState()
    val symptom by vm.symptom.collectAsState()
    val tagRegex by vm.tagRegex.collectAsState()
    val runs by vm.runs.collectAsState()
    val running by vm.running.collectAsState()
    val pipelineRunning by vm.pipelineRunning.collectAsState()
    val aiEnabled by vm.aiEnabled.collectAsState()

    // ── 换 run 视图/发起再分析前轻确认 ──
    // 当前会话有未收口内容（证据流非空且非占位 run）且目标不同 ⇒ 先出确认卡
    //（RunHistoryList pendingDelete 同款局部态配方）；pendingOpen = 待切 run 的
    // (id, 标题)；表单覆盖提示并入同一确认文案（「切换后
    // 表单将由 run X 的记录回填」语义已被「视图取代」提示覆盖）。
    var pendingOpen by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingStartAnalysis by remember { mutableStateOf(false) }

    /** openRun 前置拦截：运行中明示先中止不弹卡（确认卡照弹后「切换」被静默拒是零回执断链）；有未收口内容且目标 run ≠ 当前 run 时弹确认 */
    fun openRunWithConfirm(id: String, title: String) {
        if (running || pipelineRunning) {
            com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show("研判运行中，请先中止当前分析")
            return
        }
        if (vm.hasUncommittedContent() && id != vm.currentRunIdForUi()) {
            pendingOpen = id to title
        } else {
            vm.openRun(id)
        }
    }

    /** startAnalysis 前置拦截：连发新分析会静默替换当前视图——同款确认 */
    fun startAnalysisWithConfirm() {
        if (vm.hasUncommittedContent()) {
            pendingStartAnalysis = true
        } else {
            vm.startAnalysis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        // 卡距走全局卡距档 space14
        verticalArrangement = Arrangement.spacedBy(Dimens.space14),
    ) {
        // 「全部报告」：历史 subhead 行尾 chipbtn（fs-11 chipbtn + subhead flex:1 同行）
        InputForm(
            vm = vm,
            logFiles = logFiles,
            targetTime = targetTime,
            rangeMinutes = rangeMinutes,
            symptom = symptom,
            tagRegex = tagRegex,
            running = running,
            pipelineRunning = pipelineRunning,
            aiEnabled = aiEnabled,
            onStartAnalysis = ::startAnalysisWithConfirm,
        )
        RunHistoryList(
            runs = runs,
            currentRunId = vm.currentRunIdForUi(),
            onOpen = ::openRunWithConfirm,
            onRename = vm::renameRun,
            onDelete = vm::deleteRun,
        )
    }

    // 换 run 确认卡（非危险——原 run 记录保留在历史，可随时切回）
    pendingOpen?.let { (id, title) ->
        com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog(
            title = "切换分析记录",
            // 文案承诺与实现对齐：openRun 只回填文件/时间/范围三字段（症状不回填）——
            // 「表单回填」全量措辞是无处可兑的承诺
            text = "切换后当前视图将由分析记录「$title」的记录取代（原记录保留在分析历史，" +
                "可随时切回；表单将回填该记录的日志文件/时间/范围）。",
            confirmLabel = "切换",
            onConfirm = {
                vm.openRun(id)
                pendingOpen = null
            },
            onDismiss = { pendingOpen = null },
        )
    }

    // 再分析确认卡（非危险——当前 run 保留在历史）
    if (pendingStartAnalysis) {
        com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog(
            title = "发起新分析",
            text = "新分析将替换当前视图（原分析记录保留在分析历史，可随时切回）。",
            confirmLabel = "继续",
            onConfirm = {
                vm.startAnalysis()
                pendingStartAnalysis = false
            },
            onDismiss = { pendingStartAnalysis = false },
        )
    }
}

// ── 左栏：结构化输入表单（非自由文本 prompt） ──

@Composable
internal fun InputForm(
    vm: WorkbenchViewModel,
    logFiles: List<String>,
    targetTime: String,
    rangeMinutes: Long,
    symptom: String,
    tagRegex: String,
    running: Boolean,
    /** 管线直跑在途（session.running 不覆盖直跑——busy 渲染独立消费） */
    pipelineRunning: Boolean,
    aiEnabled: Boolean,
    /** 开始分析入口：经 WorkbenchSidePanelContent 的确认拦截缝（直调 VM 时无确认） */
    onStartAnalysis: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        Text("分析输入", style = MaterialTheme.typography.titleSmall)

        FileDropZone(
            title = "拖入日志文件",
            dragTitle = "松手添加",
            // .diff（日志对比产物——unified 纯文本，AI 研判主料）与三原生格式同收
            desc = ".alog / .xlog / .txt / .diff，可多选",
            onFilesSelected = { files -> files.forEach { vm.addTargetFile(it.absolutePath) } },
            accepts = { it.extension in setOf("alog", "xlog", "txt", "diff") },
        )

        logFiles.forEach { path ->
            HoverableRow(
                modifier = Modifier
                    .fillMaxWidth()
                    // 行体 focusable：键盘 Tab 进行 → 行获焦 → revealed=true → 移除钮
                    // enabled 可接收焦点（HoverableRow 契约——缺行焦点分支 = 键盘误加
                    // 文件后零移除通路）
                    .focusable(),
                shape = AppShapes.small,
                actionContent = { isHovered ->
                    TextButton(onClick = { vm.removeTargetFile(path) }, enabled = isHovered) {
                        // 行尾文字钮走 primary（reset 语义是「移出列表」非危险破坏）
                    Text("移除", color = MaterialTheme.colorScheme.primary)
                    }
                },
            ) {
                AppSymbolIcon(icon = MaterialSymbols.DESCRIPTION, contentDescription = null, size = Dimens.iconSmall)
                Text(
                    pathFileName(path),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Dimens.space8),
                    style = DesktopTypography.mono,
                    maxLines = 1,
                )
            }
        }

        // 问题时间点「现在」一键取值 + 宽松格式归一（空格/T 分隔、缺毫秒自动补）——
        // 设备排查最高频输入是「现在出问题了」
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            CommitTextField(
                label = "问题时间点（yyyy-MM-dd_HH:mm:ss.SSS）",
                value = targetTime,
                onCommit = vm::setTargetTime,
                // 空值后果前置提示——空串传管线 = 不做时间过滤
                // 全量跑（Request.targetTime 空语义），时长与噪音不可控须先告知
                supportingText = if (targetTime.isBlank()) {
                    "未填时间点——将不过滤时间范围，全量分析（较慢）"
                } else {
                    "支持空格/T 分隔与省略毫秒，自动归一"
                },
                modifier = Modifier.weight(1f),
            )
            // 「现在」= GhostChipButton 单源（small + labelSmall + raised 底 + hairline）
            GhostChipButton(
                label = "现在",
                onClick = vm::setTargetTimeToNow,
            )
        }

        // 范围用数字专用档（CommitNumberField）：非数字不进编辑态、1..1440 之外
        // 不提交并回退显示值——旧 CommitTextField toLongOrNull 对 -5/0 直通，
        // 显示与生效背离（1440 远小于 Int 上限，Int 变体足够）
        CommitNumberField(
            label = "时间范围（±分钟）",
            value = rangeMinutes.toInt(),
            onCommit = { vm.setRangeMinutes(it.toLong()) },
            isValid = { it in 1..1440 },
            supportingText = "有效范围 1–1440 分钟",
        )

        OutlinedTextField(
            value = symptom,
            onValueChange = vm::setSymptom,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("症状描述（必填）", style = MaterialTheme.typography.bodySmall) },
            placeholder = { Text("例：设置页面黑屏无响应", style = MaterialTheme.typography.bodySmall) },
            minLines = 2,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        CommitTextField(
            label = "Tag 正则（可选——留空时 agent 经知识库反查自定）",
            value = tagRegex,
            onCommit = vm::setTagRegex,
        )

        // AI 开关降级统一形态：主操作恒可见——未开启时置灰 + 原因
        // （禁静默置灰），确定性管线以 Secondary 常驻（非 AI 能力照常可用）
        AppButton(
            label = "开始分析",
            busy = running && aiEnabled,
            busyLabel = "研判运行中…",
            onClick = onStartAnalysis,
            modifier = Modifier.fillMaxWidth(),
            enabled = aiEnabled && logFiles.isNotEmpty() && symptom.isNotBlank(),
            disabledReason = when {
                !aiEnabled -> "未配置模型服务——根因研判不可用，可先只跑确定性管线"
                logFiles.isEmpty() -> "先拖入日志文件"
                symptom.isBlank() -> "先填写症状描述"
                else -> null
            },
        )
        Text(
            if (aiEnabled) {
                "研判 = 确定性管线 + AI 根因分析；产物落在源文件同目录。"
            } else {
                "AI 功能未开启——根因研判不可用；可先只跑确定性管线（解密→时间过滤→Tag 过滤），或到「AI 设置」开启。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!aiEnabled) {
            AppButton(
                label = "只跑确定性管线",
                busy = pipelineRunning,
                busyLabel = "管线运行中…",
                onClick = vm::runPipelineOnly,
                modifier = Modifier.fillMaxWidth(),
                kind = AppButtonKind.Secondary,
                enabled = logFiles.isNotEmpty(),
                disabledReason = if (logFiles.isEmpty()) "先拖入日志文件" else null,
            )
        }
    }
}

// ── 左栏：run 历史列表（listRuns 倒序 + 回看/重命名/删除） ──

@Composable
internal fun RunHistoryList(
    runs: List<RunStore.RunSummary>,
    currentRunId: String,
    /** 行点击回调节带 run 标题（确认卡文案用）——拦截缝在 WorkbenchSidePanelContent */
    onOpen: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // 持久化实体删除一律确认：待删 run id + 展示名
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        // subhead 行尾「全部报告」chipbtn（桥接动线——GoReportHook.offer）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("分析历史", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            // 「全部报告」= GhostChipButton 单源（同面板 chip 同形——原透明裸文字形态收口）
            GhostChipButton(
                label = "全部报告",
                onClick = { com.jiaoay.easy_dev_toolbox.core.navigation.GoReportHook.offer() },
            )
        }
        if (runs.isEmpty()) {
            EmptyStateView(
                text = "尚无分析记录",
                learningHint = "填入日志与症状后开始第一次分析",
                fillParent = false,
            )
        }
        runs.forEach { run ->
            var renaming by remember(run.id) { mutableStateOf(false) }
            HoverableRow(
                shape = AppShapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    // hover 反馈归 HoverableRow 洗底独占，抑制默认矩形 indication
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpen(run.id, run.title) },
                actionContent = { isHovered ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                        TextButton(onClick = { renaming = true }, enabled = isHovered) { Text("改名") }
                        TextButton(
                            // 删 run 含 SQLite 记录不可逆 → 先确认
                            onClick = { pendingDelete = run.id to run.title },
                            enabled = isHovered,
                        ) {
                            Text("删", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        run.title,
                         // 标题 font-weight:600
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        color = if (run.id == currentRunId) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                    ) {
                        // 状态点进 meta 行——StatusDot 共享件三态映射
                        StatusDot(
                            when (run.status) {
                                RunStore.RunStatus.RUNNING -> DotState.RUN
                                RunStore.RunStatus.COMPLETED -> DotState.OK
                                RunStore.RunStatus.ABORTED, RunStore.RunStatus.FAILED -> DotState.ERR
                            },
                        )
                        Text(
                            // 历史行 meta 补全——状态 + 相对时间 + 文件数
                            //（多 run 可分辨时间与规模）；targetTime 非空追加时间点段。
                             // meta 恒 faint 色（meta hint 语义）——状态语义由行首状态点承担
                            listOfNotNull(
                                statusLabel(run.status),
                                relativeTime(run.updatedAt),
                                "${run.logFiles.size} 个文件",
                                run.targetTime?.let { "@ $it" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                        )
                    }
                }
            }
            if (renaming) {
                RenameDialog(
                    title = "重命名分析记录",
                    initial = run.title,
                    onSave = { title -> onRename(run.id, title) },
                    onDismiss = { renaming = false },
                )
            }
        }
    }

    // 删除确认对话框（含 SQLite 会话与证据流，不可恢复）
    pendingDelete?.let { (id, title) ->
        com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog(
            title = "删除分析记录",
            text = "将删除分析记录「$title」的会话记录与证据流，不可恢复。",
            confirmLabel = "删除",
            isDangerous = true,
            onConfirm = {
                onDelete(id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}



private fun statusLabel(status: RunStore.RunStatus): String = RunStore.RunStatus.label(status)

/**
 * 相对时间：历史 run 行 meta 用——epochMillis → 「刚刚 /
 * N 分钟前 / N 小时前 / 昨天 / MM-dd」五档。
 *
 * 档位语义：<1 分钟「刚刚」；<1 小时「N 分钟前」；今日更早「N 小时前」；
 * 昨日跨日「昨天」（日历日比较，非 24h 滚动窗——25h 前的 23:xx 仍是「昨天」）；
 * 更早 SimpleDateFormat("MM-dd")（同年省年份，跨年也省——历史行 meta 档）。
 * 顶层 internal fun：feature-logworkbench jvmTest 同包直测缝。
 */
internal fun relativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val diff = nowMillis - epochMillis
    if (diff < MINUTE_MS) return "刚刚"
    if (diff < HOUR_MS) return "${diff / MINUTE_MS} 分钟前"
    val zone = java.time.ZoneId.systemDefault()
    val nowDay = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val thenDay = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    if (thenDay == nowDay) return "${diff / HOUR_MS} 小时前"
    if (thenDay == nowDay.minusDays(1)) return "昨天"
    return java.text.SimpleDateFormat("MM-dd").format(java.util.Date(epochMillis))
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L


// ── FileBuffer 暂存区消费面 ──

/**
 * 暂存区 chips 行（[FileBuffer] 的页面消费面）：palette「暂存到 Buffer」的产物
 * 在此可见可安置——chip 主体点击 = 消费（[onConsume]：载入分析输入 + 出暂存），
 * × 钮 = 移除（[onRemove]：显式丢弃）；hover Tooltip 显全路径（chip 只显文件名）。
 *
 * 空 Buffer 零渲染（调用方 `isNotEmpty()` 门控——不占常驻空间）。
 */
@Composable
internal fun FileBufferRow(
    files: List<FileBuffer.BufferedFile>,
    onConsume: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Text("暂存区", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        // 「点击即消费出暂存」强语义前置说明（防误触——
        // 点一下文件就消失进表单）
        Text(
            "点击载入并移出暂存",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        files.forEach { file ->
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip(
                        shape = AppShapes.small,
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ) {
                        Text(file.path, style = MaterialTheme.typography.labelSmall)
                    }
                },
                state = rememberTooltipState(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space2),
                    modifier = Modifier
                        .clip(AppShapes.small)
                        .background(
                            // 微洗底（selectedWash）——contentFaint(0.6) 重洗底压暗
                            // mono 文字不可读（PageReport RUNNING pill 同款微洗底配方）
                            MaterialTheme.colorScheme.primary.copy(AppAlpha.selectedWash),
                            AppShapes.small,
                        )
                        .clickable { onConsume(file.path) }
                        .padding(horizontal = Dimens.space8, vertical = Dimens.space2),
                ) {
                    Text(
                        pathFileName(file.path),
                        style = DesktopTypography.mono,
                        maxLines = 1,
                    )
                    // 「×」移除钮：命中区 = minimumInteractiveSize（MICS 档——原纯文本
                    // 可点区仅约一字符宽，小目标 + 强语义邻接误触代价不对称）+ CLOSE
                    // 小档图标；hover 洗底 + indication=null（chip 内嵌钮）
                    val removeSource = remember(file.path) { MutableInteractionSource() }
                    val removeHovered by removeSource.collectIsHoveredAsState()
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(Dimens.minimumInteractiveSize)
                            .background(
                                selectionHoverWashColor(hovered = removeHovered),
                                AppShapes.small,
                            )
                            .clickable(
                                interactionSource = removeSource,
                                indication = null,
                            ) { onRemove(file.path) },
                    ) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.CLOSE,
                            contentDescription = "移除暂存文件",
                            size = Dimens.iconSmall,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
