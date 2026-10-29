package com.jiaoay.easy_dev_toolbox.widget.evidence

import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.evidence.LogLine
import com.jiaoay.easy_dev_toolbox.core.evidence.ReadLogLinesResult
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoItem
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.BottomFollowScrollEffect
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.GhostChipButton
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.VerticalScrollbarSlot
import com.jiaoay.easy_dev_toolbox.widget.chat.ChatEntryRow
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 证据流视图（调查工作台 S4 上移 :ui/widget/evidence——原 LogWorkbenchPage 私有件
 * 升格 public；块化形态：琥珀块=确定性工具产物、紫块=AI 研判）。
 *
 * ## 块化结构（融合案 D 裁决 3：证据流块化，双语义色裁决 7）
 * 单 LazyColumn 保持单 listState（贴底跟随与引用跳转的滚动配方依赖单一滚动位）：
 * - **空态提示项**（两流皆空时）；
 * - **琥珀块**（工具产物行流）：块头 item + [evidenceLines] 行流——块头 = 阶段名 +
 *   产物文件名 + 迷你直方图 + 「设为当前」动作；底色 surfaceVariant（工具色跟 scheme）、
 *   左侧 primary 色条（阶段锚点条点击切换产物 = 琥珀块内容整体切换）；
 * - **紫块**（AI 研判时间线）：块头 item + [entries] 行流——块头底色 aiAccentContainer、
 *   左侧 aiAccent 色条 + run 标题；行流本体复用 [ChatEntryRow]。
 *
 * 引用跳转索引修正：块头 item 前置后行流在 LazyColumn 中的物理 index 产生偏移
 *    ——[lineItemOffset]/[entryItemOffset] 由本组件单点换算（偏移按「块头实际
 *    渲染与否」动态计，对齐条件渲染布局序；页面
 *    jumpToTimestamp 与贴底跟随 itemCount 都经此修正，跳转链原语义不变）。
 *
 * 流式零动画铁律：日志行/条目列表不做逐行动画；直方图一次 Canvas 入场；
 * ±N 上下文展开走 AnimatedVisibility(softSpring)——交互件非流式件。
 */
@Composable
fun RowScope.EvidenceTimeline(
    entries: List<ChatEntry>,
    evidenceFiles: List<String>,
    activeEvidence: String?,
    evidenceLines: List<LogLine>,
    listState: LazyListState,
    highlightTimestamp: String?,
    onPickEvidence: (String) -> Unit,
    onTagFilter: (String) -> Unit,
    onCancel: () -> Unit,
    running: Boolean,
    /** 截断探测结果（null = 未探测/零提示——≤2000 行常规产物零回归） */
    truncation: ReadLogLinesResult? = null,
    todos: List<TodoItem> = emptyList(),
    onPickFiles: (() -> Unit)? = null,
    onPickTimestamp: (String) -> Unit = {},
    onHighlightConsumed: () -> Unit = {},
    loading: Boolean = false,
    searchQuery: String = "",
    /** 过滤词变更回调（页面侧持有态，组件回写） */
    onSearchQueryChange: (String) -> Unit = {},
    gateWaiting: Boolean = false,
    /** 截断提示行尾「查看完整产物」动作（null = 不渲染行尾钮——调用方未接查看能力） */
    onViewFullArtifact: (() -> Unit)? = null,
) {
    val colors = LocalEasyToolboxColors.current
    // 截断信息：null/未截断零渲染——判据经 [evidenceTruncationVisible] 纯函数单源
    val truncationInfo = truncation?.takeIf { it.truncated }
    // 块化后的行流物理 index 偏移（块头 item 数——三项均条件渲染，按「实际渲染与否」
    // 动态计：空态提示占 1 位当且仅当两流皆空；琥珀/紫块头各占 1 位当且仅当对应流非空。
    // 🔴 偏移契约须与真实 item 数一致——静态恒计会让单块场景 scrollToItem(last) 越界。
    val emptyHintCount = if (evidenceLines.isEmpty() && entries.isEmpty()) 1 else 0
    val toolBlockHeaderCount = if (evidenceLines.isNotEmpty()) 1 else 0
    val aiBlockHeaderCount = if (entries.isNotEmpty()) 1 else 0
    // 琥珀行流前置偏移（空态提示项[若有] + 琥珀块头[若有]）——全量口径
    //（jumpToTimestamp 页面侧消费全量索引；过滤态渲染行 ≠ 全量索引——过滤态
    // 滚动定位由页面侧跳过）
    val lineItemOffset = emptyHintCount + toolBlockHeaderCount
    // 紫块行流前置偏移（lineItemOffset + evidenceLines + 紫块头[若有]）——全量口径
    val entryItemOffset = lineItemOffset + evidenceLines.size + aiBlockHeaderCount
    // 渲染面行流：过滤命中行——数据面 evidenceLines 全量
    // 保留（jumpToTimestamp/highlight/offset 函数消费全量索引零改），仅渲染与
    // 贴底跟随/块头判定吃 shownLines。空 query 时 shownLines === evidenceLines
    //（零过滤开销路径——isBlank 短路）。
    val shownLines = remember(evidenceLines, searchQuery) { filterEvidenceLines(evidenceLines, searchQuery) }
    // 过滤态下块结构随渲染面重计：空态提示/块头按 shownLines 实际渲染与否动态计
    //（过滤命中零行 = 琥珀块整体不渲染，offset 函数仍按全量计——见 jumpToTimestamp
    // 错位裁量，页面侧过滤态跳过滚动定位）
    val shownEmptyHintCount = if (shownLines.isEmpty() && entries.isEmpty()) 1 else 0
    val shownToolBlockHeaderCount = if (shownLines.isNotEmpty()) 1 else 0
    val shownLineItemOffset = shownEmptyHintCount + shownToolBlockHeaderCount
    val shownEntryItemOffset = shownLineItemOffset + shownLines.size + aiBlockHeaderCount

    Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        // 计划投影卡：agent 经 write_todos 写入的任务规划在此
        // 逐条状态直渲——「AI 在干什么」的可解释面。🔴 落位在 LazyColumn 之外的
        // Column 区（插 LazyColumn 内会破坏 evidenceTimelineLineItemOffset 物理索引
        // 契约）；items 空 = 零渲染（不占常驻空间）。DOM 序：TodoBoard 在
        // StageAnchorBar **之上**。
        if (todos.isNotEmpty()) {
            TodoBoardPanel(items = todos)
        }

        // 阶段锚点条（管线产物即阶段锚——点击切换证据文件 = 跳阶段区段/琥珀块切换）
        Box(modifier = Modifier.padding(bottom = Dimens.space10)) {
            StageAnchorBar(
                evidenceFiles = evidenceFiles,
                activeEvidence = activeEvidence,
                onPickEvidence = onPickEvidence,
                running = running,
            )
        }

        // 时间直方图（有日志行才渲染——一次 Canvas 入场；桶点击跳时段）
        if (evidenceLines.isNotEmpty()) {
            Box(modifier = Modifier.padding(bottom = Dimens.space10)) {
                TimeHistogram(
                    lines = evidenceLines,
                    onPickTimestamp = onPickTimestamp,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.space4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text("证据流", style = MaterialTheme.typography.titleSmall)
            // 行内搜索：「找一条日志」最高频操作的行内工具——
            // SearchField 统一件（capsule + controlStandard 34dp），
            // 占标题位；空串零过滤（默认态不占宽）。
            if (evidenceLines.isNotEmpty()) {
                SearchField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = "在当前产物中搜索",
                    textStyle = DesktopTypography.mono, /* 日志行 mono 语域 */
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                AppSymbolIcon(
                                    icon = MaterialSymbols.CLOSE,
                                    contentDescription = "清除搜索",
                                    size = Dimens.iconSmall,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            // 命中数徽标：过滤态显「N/M 行」——渲染命中数/全量数；
            // 未过滤零徽标（不占常驻位；截断徽标独立并存）。形态 = pill mute 中性弱底
            // + 「清除」chipbtn 外挂
            if (searchQuery.isNotBlank()) {
                Text(
                    "命中 ${shownLines.size}/${evidenceLines.size} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            LocalToolboxSurfaceColors.current.surfaceDeep,
                            AppShapes.capsule,
                        )
                        .padding(horizontal = Dimens.space8, vertical = Dimens.space2),
                )
                // 「清除」= GhostChipButton 单源（与同块头「中止」同形——原透明裸文字形态收口）
                GhostChipButton(
                    label = "清除",
                    onClick = { onSearchQueryChange("") },
                )
            }
            // 总行数徽标：截断时恒显「N 行」——窗口数字（lines.size）与真值
            // （totalLines）之间的差就是被截掉的部分，徽标给真值；未截断不占位
            if (truncationInfo != null) {
                Text(
                    "${truncationInfo.totalLines} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(AppAlpha.contentFaint),
                            AppShapes.small,
                        )
                        .padding(horizontal = Dimens.space8, vertical = Dimens.space2),
                )
            }
            if (running) {
                // 中止钮 = 块头内嵌小钮档（widget/ChipButton.kt GhostChipButton：
                // small 圆角 + raised 底 + hairline 边 + labelSmall）
                GhostChipButton(
                    label = "中止",
                    onClick = onCancel,
                )
            }
        }

        // 读取中指示行：行流换血在途的进行中反馈——单点
        // 指示非流式内容动画（零动画铁律不受影响）。🔴 落位在 LazyColumn 之外的
        // Column 区（标题行下方视觉等效「行流顶部」）——插 LazyColumn 内会破坏
        // evidenceTimelineLineItemOffset 物理索引契约（OffsetTest 四用例钉死）。
        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.progressInline),
                    strokeWidth = Dimens.strokeThin,
                )
                Text(
                    "正在读取产物…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 截断提示条：产物超 2000 行窗口——行流只载前窗，指路对象 = 应用内查看器
        //（行偏移索引全量直达）+ 行尾「查看完整产物」直达钮。
        if (truncationInfo != null) {
            // 行尾动作闭包显式定型（null = 不渲染行尾钮——调用方未接查看能力）
            val viewFull = onViewFullArtifact
            val trailingAction: (@Composable () -> Unit)? = if (viewFull != null) {
                { TextButton(onClick = viewFull) { Text("查看完整产物") } }
            } else {
                null
            }
            StatusBanner(
                status = StatusBannerStatus.Warning,
                text = "已截断：显示前 ${truncationInfo.lines.size} 行 / 共 ${truncationInfo.totalLines} 行" +
                    "——完整内容在产物文件本体，可点「查看完整产物」在应用内查看器打开",
                trailing = trailingAction,
            )
        }

        // 块化行流（单 LazyColumn：琥珀块[工具产物] → 紫块[AI 研判]）；
        // SelectionContainer = 行流文本可选中复制（「找到一行 → 带走」产出动线，
        // ShellOutputPanel 控制台同款配方）——LazyColumn 滚动回收下跨屏选区有瑕疵，
        // 验收判据 = 视口内拖选复制
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space2),
                ) {
                    if (shownEmptyHintCount == 1) {
                        item(key = "ev-empty") {
                            // 空态经 fillParentMaxHeight 撑满 LazyColumn 可视高——
                            // LazyItemScope 修饰符在滚动容器内等价「占满视口居中」。
                            // 过滤命中零行（两流皆空渲染面）= 同款空态提示。
                            EmptyStateView(
                                text = if (searchQuery.isNotBlank()) "无匹配行" else "证据流为空",
                                learningHint = if (searchQuery.isNotBlank()) {
                                    "当前过滤词在产物中无命中——清除后恢复全量行流"
                                } else {
                                    "分析开始后，各阶段产物与研判时间线在此回显"
                                },
                                fillParent = false,
                                // workbench 域空态图标 40 覆写（尺寸参数化承载）
                                iconSize = Dimens.workbenchEmptyIcon,
                                // 空态动作锚——「选择日志文件」
                                // 直达路径（新手不必先发现左栏输入表单）。
                                // actionLabel 与 onPickFiles 成对契约（null 时空态零
                                // 动作钮——防渲染残钮）。
                                actionLabel = if (onPickFiles != null && searchQuery.isBlank()) "选择日志文件" else null,
                                onAction = onPickFiles,
                                modifier = Modifier
                                    .fillParentMaxHeight()
                                    .padding(Dimens.space12),
                            )
                        }
                    }
                    if (shownLines.isNotEmpty()) {
                        item(key = "ev-block-tool") {
                            ToolBlockHeader(
                                lines = shownLines,
                                activeEvidence = activeEvidence,
                                onPickEvidence = onPickEvidence,
                            )
                        }
                        items(shownLines, key = { "line-${it.seq}" }) { line ->
                            EvidenceLineRow(
                                line = line,
                                evidenceFiles = evidenceFiles,
                                activeEvidence = activeEvidence,
                                highlighted = line.timestamp != null && line.timestamp == highlightTimestamp,
                                onTagFilter = onTagFilter,
                                onHighlightConsumed = onHighlightConsumed,
                            )
                        }
                    }
                    if (entries.isNotEmpty()) {
                        item(key = "ev-block-ai") {
                            AiBlockHeader(lines = shownLines, gateWaiting = gateWaiting)
                        }
                        items(entries, key = { it.id }) { entry ->
                            ChatEntryRow(entry = entry, onOpenArtifact = { path -> onPickEvidence(path) })
                        }
                    }
                }
                }
                // 贴底跟随（LogConsolePanel 配方——流式追加时保持贴底；零动画 scrollToItem）
                // 过滤态以渲染面为准（itemCount/contentVersion 吃 shownLines——
                // 过滤词变更同样仅在贴底时跟随）
                BottomFollowScrollEffect(
                    listState = listState,
                    itemCount = shownEntryItemOffset + entries.size,
                    contentVersion = (shownLines.size + entries.size).toLong(),
                )
            }
            VerticalScrollbarSlot(listState = listState)
        }
    }
}

/**
 * 块头 item → 行流 item 的物理 index 偏移换算（jumpToTimestamp scrollToItem 修正用）。
 *
 * 布局序：空态提示项（两流皆空时占 1 位）→ 琥珀块头（lines 非空时占 1 位）→
 * evidenceLines → 紫块头（entries 非空时占 1 位）→ entries——三项均条件渲染。
 * 偏移按「块头实际渲染与否」动态计（恒 +1 在单块场景
 * 过计 1）；本函数只服务琥珀行流（调用前提 lines 非空——空态提示与之互斥，
 * 故恒 = 1），紫块跳转走 [evidenceTimelineEntryItemOffset]。
 */
fun evidenceTimelineLineItemOffset(evidenceLines: List<LogLine>, entries: List<ChatEntry>): Int {
    val emptyHint = if (evidenceLines.isEmpty() && entries.isEmpty()) 1 else 0
    val toolHeader = if (evidenceLines.isNotEmpty()) 1 else 0
    return emptyHint + toolHeader
}

/**
 * 截断提示可见判据（纯函数直测缝）：
 * null（未探测/无产物）或 truncated=false（≤2000 行常规产物）⇒ 提示条与
 * 总行数徽标双双零渲染——既有行为零回归；仅真截断（超窗）才可见。
 */
fun evidenceTruncationVisible(truncation: ReadLogLinesResult?): Boolean =
    truncation != null && truncation.truncated

/**
 * 证据流行内搜索过滤（纯函数直测缝）：
 * 按 [query] 子串匹配行全文（[LogLine.raw] 全含 TAG/message/时间戳三段——单域
 * substring 即全覆盖，无需多域拆分）；**空/空白 query 返回原列表引用**
 * （零过滤开销路径——isBlank 短路不拷贝）。
 *
 * offset 契约裁量（如实上报）：过滤态渲染行 ≠ evidenceTimelineLineItemOffset
 * 全量索引——过滤态下 jumpToTimestamp 的 scrollToItem 定位在渲染面落空被钳制，
 * 页面侧按「过滤非空时跳过滚动、高亮仍设」裁量处理（行在过滤集内即显）。
 */
fun filterEvidenceLines(lines: List<LogLine>, query: String): List<LogLine> =
    if (query.isBlank()) lines else lines.filter { query in it.raw }

/**
 * AI 块头的行流偏移（紫块跳转场景；琥珀块末位 + 紫块头[实际渲染与否动态计]）。
 * 调用前提 entries 非空——紫块头恒渲染。
 */
fun evidenceTimelineEntryItemOffset(evidenceLines: List<LogLine>, entries: List<ChatEntry>): Int {
    val aiHeader = if (entries.isNotEmpty()) 1 else 0
    return evidenceTimelineLineItemOffset(evidenceLines, entries) + evidenceLines.size + aiHeader
}

/** 琥珀块头（确定性工具产物——surfaceVariant 底 + primary 左色条 + 迷你直方图 + 设为当前） */
@Composable
private fun ToolBlockHeader(
    lines: List<LogLine>,
    activeEvidence: String?,
    onPickEvidence: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                // 块头底 = surfaceDeep×blockHeadWashStrong（surfaceDeep=--surface-deep 映射档）
                LocalToolboxSurfaceColors.current.surfaceDeep.copy(AppAlpha.blockHeadWashStrong),
                AppShapes.small,
            )
            .padding(horizontal = Dimens.space8, vertical = Dimens.space4),
    ) {
        Spacer(
            modifier = Modifier
                .width(Dimens.evidenceBlockBarWidth)
                .height(Dimens.space24)
                .background(MaterialTheme.colorScheme.primary, AppShapes.small),
        )
        AppSymbolIcon(
            icon = MaterialSymbols.LOCK_OPEN,
            contentDescription = null,
            size = Dimens.iconSmall,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "工具产物 · ${stageNameOf(activeEvidence ?: "")}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (activeEvidence != null) {
                Text(
                    pathFileName(activeEvidence),
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        MiniHistogram(lines = lines)
        if (activeEvidence != null) {
            // 「设为当前」= 同「中止」的块头内嵌小钮档（GhostChipButton 配方单源）
            GhostChipButton(
                label = "设为当前",
                onClick = { onPickEvidence(activeEvidence) },
            )
        }
    }
}

/**
 * 紫块头（AI 研判——aiAccentContainer 底 + aiAccent 左色条 + run 标题）。
 *
 * [gateWaiting] true（pendingGate 非 null——agent 挂起等
 * 围栏裁决）时副文案换「等待路径授权——请在确认卡裁决」（HITL 断流可见化——
 * 等待授权期间 agent 挂死无任何「在等你」指示是最常见断流根因）；false 时
 * 常驻教学文案「AI 产物 · 恒紫标识」。
 */
@Composable
private fun AiBlockHeader(lines: List<LogLine>, gateWaiting: Boolean) {
    val colors = LocalEasyToolboxColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        modifier = Modifier
            .fillMaxWidth()
            // AI 块头底 = aiAccentContainer×blockHeadWash（恰是 --ai-c 原值档）
            .background(colors.aiAccentContainer.copy(AppAlpha.blockHeadWash), AppShapes.small)
            .padding(horizontal = Dimens.space8, vertical = Dimens.space4),
    ) {
        Spacer(
            modifier = Modifier
                .width(Dimens.evidenceBlockBarWidth)
                .height(Dimens.space24)
                .background(colors.aiAccent, AppShapes.small),
        )
        AppSymbolIcon(
            icon = MaterialSymbols.PSYCHOLOGY,
            contentDescription = null,
            size = Dimens.iconSmall,
            tint = colors.aiAccent,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "AI 研判",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                if (gateWaiting) {
                    "等待路径授权——请在确认卡裁决"
                } else {
                    // 副文案恒显但保持 8 字短句——恒显长句成噪音，紫色本身即语义
                    //（恒紫标识语言，DL 条款）；教学职责归首启导览
                    //（OnboardingCard，composeApp 域——越本组件 scope 不承载）。
                    "AI 产物 · 恒紫标识"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 阶段锚点条（GitHub Actions 阶段模型的文件面投影——产物文件名即阶段锚） */
@Composable
fun StageAnchorBar(
    evidenceFiles: List<String>,
    activeEvidence: String?,
    onPickEvidence: (String) -> Unit,
    running: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.workbenchStageBarHeight)
            // 阶段条底 = surfaceDeep×blockHeadWash
            .background(
                LocalToolboxSurfaceColors.current.surfaceDeep.copy(AppAlpha.blockHeadWash),
                AppShapes.medium,
            )
            .padding(horizontal = Dimens.space12)
            // 定高条溢出裁剪防线：产物文件名多/长时阶段 chip 会被硬裁——滚动区
            // 挂 padding 内侧（底色/圆角/内边距恒定，仅 chip 列横滚可探达）
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text("管线阶段：", style = MaterialTheme.typography.labelMedium)
        if (evidenceFiles.isEmpty()) {
            Text(
                if (running) "运行中…" else "尚未分析",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            evidenceFiles.forEach { path ->
                val active = path == activeEvidence
                Text(
                    stageNameOf(path),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(AppShapes.small)
                        .background(
                            // 选中阶段 = primary×selectedWash 微洗底（交互微洗底恒 primary 系，AppAlpha.selectedWash）
                            if (active) MaterialTheme.colorScheme.primary.copy(AppAlpha.selectedWash) else Color.Transparent,
                            AppShapes.small,
                        )
                        .clickable { onPickEvidence(path) }
                        .padding(horizontal = Dimens.space8, vertical = Dimens.space4),
                )
            }
        }
    }
}

/** 产物文件名 → 阶段显示名（管线产物命名规则——LogTimeFilter/LogTagFilter/LogAnalysisPipeline） */
fun stageNameOf(path: String): String {
    val name = pathFileName(path)
    return when {
        ".filter-tag_" in name -> "Tag过滤"
        ".filter-time_" in name -> "时间过滤"
        name.endsWith(".alog.txt") || name.endsWith(".xlog.txt") -> "解密"
        else -> name.take(24)
    }
}
