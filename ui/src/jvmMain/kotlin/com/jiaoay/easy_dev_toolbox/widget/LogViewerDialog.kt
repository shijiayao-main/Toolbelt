package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunBookmarkStore
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.interactions.onEnterKey
import com.jiaoay.log.LogBookmarkSet
import com.jiaoay.log.LogStatsAnalyzer
import com.jiaoay.log.LogTimeDiff
import com.jiaoay.log.LogViewSession
import com.jiaoay.log.LogViewer
import com.jiaoay.log.ViewerLine
import com.jiaoay.log.parseLogLineTimestamp
import com.jiaoay.log.parseLogTimestampTarget
import java.io.File
import java.time.Year
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用内大文件日志查看对话框（共享消费面：任务卡「查看」与 logcat 抓取回执行共用）。
 *
 * 引擎为 [LogViewer]（行偏移索引 + 窗口 seek，内容不整文件驻留）；本件只做
 * 窗口编排：打开首窗 → 滚动触底/触顶续读（窗口行集有界，超界裁远端）→
 * 行号跳转直达 / 时间戳跳转（[LogViewSession.nextTimestampLine] 逐窗 seek 首个
 * 行首时间戳 ≥ 目标的行，复用行号跳转窗口分页链路）/ 级别导航跳转
 * （[LogViewSession.nextLevelLine] / [LogViewSession.prevLevelLine] 逐窗 seek
 * 找下一处/上一处 E|F / W，跳转复用行号跳转窗口分页链路）/ 文本搜索
 * （[LogViewSession.searchLines] 逐窗扫描收集匹配行集，回车触发搜索、同关键词
 * 再回车跳下一处、「上一处」按钮反向回退，跳转同样复用行号跳转分页链路）。
 * 滚动一律 `scrollToItem` 即时跳（流式输出零动画铁律同款）。
 *
 * 书签（会话级导航原语；宿主传 [bookmarkRunId] 时升 run 级持久化）：「标记」翻转
 * 窗口首行标记态（「当前行」= 窗口首行——跳转落点恒窗口首行，标记与跳转同锚）；
 * 上一签/下一签双向跳转与搜索匹配集同起止口径（连点推进不重复命中当前窗口首行）；
 * 「两签时间差」测量最近两签（标记序）的行首时间戳差值（引擎单行回读 +
 * [LogTimeDiff] 纯函数），负差值明示倒流、无时间戳行人话拒绝。右侧命中小地图
 * （[LogViewerMinimap]）按行号比例绘命中/书签刻度，点击按比例跳行（复用行号跳转
 * 链路）。书签集/命中集均锚定本次会话索引，漂移重开（[LogViewerStateHolder.reopen]）
 * 后清零——run 级持久化档恢复同样按行文指纹逐签重新校验（漂移拒绝，不复活旧签）。
 *
 * 级别密度刻度（小地图级别刻度）：打开就绪后一次全文件级别扫描（引擎 readWindow
 * 逐窗回读，级别判定单源 = 引擎行集自带级别，无第二套判定），E/F 与 W 各持
 * 「每密度槽首个命中行」入小地图（error / warning 语义色，与行流级别着色同槽）。
 * 刻度数上界与搜索命中集同档（引擎 LogViewer.MAX_SEARCH_RESULTS 真源对齐）。
 *
 * 并行命名高亮通道（会话级，最多 [LogViewerStateHolder.MAX_HIGHLIGHT_CHANNELS]
 * 个，不持久化）：每通道一次引擎 searchLines 全文件扫描（与搜索同上界同口径——
 * 大小写不敏感子串匹配），命中行着通道语义色。行流着色层序：通道命中色优先于
 * 级别语义色；多通道命中同一行先建通道持色——层序单源 [buildHighlightLayers]
 * （纯函数可单测），行流与小地图通道刻度共用其产出。通道色槽按通道序固定
 * （success → info → aiAccent），移除中间通道会使后继通道色槽前移（通道状态
 * 文案着通道色，界面自证映射）。
 *
 * 「深度分析」动作（底部动作区）：当前文件经 [DeepAnalyzeHook] 中立槽直投 AI
 * 研判链（宿主 AppViewModel 单点 wire → 跳工作台并预填症状备注）。仅 Ready 态
 * 渲染（打开成功 = 文件已可读；Failed 态由回执引导或「送 XLog 解密」衔接承接，
 * Drifted 态索引失效不投），宿主未接线时按钮缺席。接法取直投而非
 * 参数注入回调：查看器的文件无生产页身份（palette / 日志对比页 / 证据流打开的
 * 外来文件），各挂载面（TaskListSection / LogcatPanel / PageHome）注入内容零
 * 差异，注入形态只增透传不改语义；直投先例 = LogcatViewModel.sendToWorkbench
 * 与 FlowPage 产物 chip。带定位打开时 [initialTimestamp] 原样透传 offer 第三参
 * `prefillTargetTime`（null = 无定位不预填）——行号档（[initialLine]）不透传
 * （行号不是时间点）；透传不含年份补全逻辑，标记年补全在生产侧（logcat 锚点）。
 *
 * 「送往 Tag 过滤」动作（底部动作区，与深度分析同区）：读面读到可疑 Tag 后一键
 * 投递进命令面板参数直填态（[TaskPipelineHandoff] 通用投递面，接法 = logcat 产物
 * 同款直调 offer——目标查其单张映射表，本件不自持映射，参数直填态由用户补齐
 * 正则与输出标记）。门控同深度分析：仅 Ready 态渲染（Failed 态加密件由「送
 * XLog 解密」衔接承接，非加密失败由失败回执引导；Drifted 态索引失效不投），
 * 宿主未接线时按钮缺席。
 *
 * 「送时间截取」动作（底部动作区，与上两动作相邻同配方）：横向直投——目标 =
 * [TaskPipelineHandoff.VIEWER_TIME_SLICE] 表外常量（切出物是本文件的时间子集，
 * 非下一级管线段，故不查映射表），额外预填经 offer 扩参承载：带时间戳定位打开
 * 时 [initialTimestamp] 预填 `start_time`（原样透传不解析不改写——年份补全等
 * 归目标命令口径），`end_time` 恒不预填（留白走目标命令既有必填未填齐回执）；
 * 行号档同无定位只预填路径（行号不是时间点）。按钮恒渲染不按有无定位隐藏
 * （定位档只决定预填集）。门控同上两动作：仅 Ready 态渲染，宿主未接线时按钮
 * 缺席。
 *
 * 「送 XLog 解密」动作（底部动作区，Failed 态加密件专属）：打开被判型为加密段
 * 结构拒绝时（引擎 [LogViewer.OpenResult.Failure.encrypted] typed 档位，判型
 * 单源不做文案匹配），当前文件一键投递进 XLog 解密命令参数直填态（目标 =
 * [TaskPipelineHandoff.SourcePageId.LOG_VIEWER_ENCRYPTED] 专属来源 key 查
 * 映射表，接法与 Ready 态两动作同款直调 offer）。门控：仅加密档 Failed 态渲染
 * （非加密失败不渲染维持回执引导现状），宿主未接线或映射表缺行时按钮缺席。
 *
 * 级别着色按日志对比页 LineKind → colorScheme 语义色先例：E/F = error、
 * W = warning、I = primary、V/D = onSurfaceVariant、null（续行/形态不命中）=
 * onSurface 中性——零新色值 token，视觉深浅待真机终裁。
 *
 * [initialLine] / [initialTimestamp]：初始定位两档（并存时行号优先——行号零
 * 歧义，时间戳是语义定位降级档；决策单源 [jumpInitial]）。null = 不定位，
 * 任务卡/logcat 入口零变化。打开就绪后窗口分页到目标行为窗口首行（复用
 * 行号跳转 / 时间戳跳转链路，越界与无命中由各链路钳制或回执）；打开失败/
 * 漂移不定位（跳转随 Ready 就绪挂起，Dialog 关闭即随组合离开取消）。
 */
@Composable
fun LogViewerDialog(
    filePath: String,
    onDismiss: () -> Unit,
    initialLine: Int? = null,
    initialTimestamp: String? = null,
    /**
     * run 级书签持久化锚（null = 会话级书签现状，零持久化行为）：非空时书签随
     * 标记/清空动作按 (runId, 文件路径) 快照落 [RunBookmarkStore]，打开时按
     * (行号, 行文指纹) 双锚恢复。run 产物文件才传（工作台查看器请求经
     * [com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest.runId] 透传）。
     */
    bookmarkRunId: String? = null,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(filePath, bookmarkRunId) {
        LogViewerStateHolder(
            file = File(filePath),
            scope = scope,
            bookmarkRunId = bookmarkRunId,
            // 持久化启用才建仓储（Database 进程唯一实例——AppDataStore 同款纪律）
            bookmarkStore = bookmarkRunId?.let { RunBookmarkStore(Database.database()) },
        )
    }
    val uiState by holder.state.collectAsState()
    val lines by holder.lines.collectAsState()
    val totalLines by holder.totalLines.collectAsState()
    val searchStatus by holder.searchStatus.collectAsState()
    val timestampJumpStatus by holder.timestampJumpStatus.collectAsState()
    val bookmarkLines by holder.bookmarkLineIndexes.collectAsState()
    val bookmarkReceipt by holder.bookmarkDiffReceipt.collectAsState()
    val matchedLines by holder.matchedLineIndexes.collectAsState()
    val levelTicks by holder.levelTicks.collectAsState()
    val highlightChannels by holder.highlightChannels.collectAsState()
    val highlightLayers = remember(highlightChannels) { buildHighlightLayers(highlightChannels) }
    val listState = rememberLazyListState()
    var jumpText by remember { mutableStateOf("") }
    var searchText by remember { mutableStateOf("") }
    var timestampText by remember { mutableStateOf("") }
    // 通道输入草稿（回车提交才触发扫描——与搜索框回车触发同配方）；未编辑时回显通道关键词
    val highlightDrafts = remember { mutableStateMapOf<Int, String>() }

    // 触底续读：canScrollForward 由可滚翻转为不可滚（滚到底边）时加载下一窗；
    // 续读后远端裁剪时按裁剪量即时校正滚动位置，视口内容保持原位。
    // 初始发射（prev == null）不触发——打开中空列表恒不可滚，待首窗就位后翻转再续。
    LaunchedEffect(listState) {
        var prev: Boolean? = null
        snapshotFlow { listState.canScrollForward }.collect { canForward ->
            if (prev == true && !canForward && holder.canLoadMoreForward()) {
                val firstIndex = listState.firstVisibleItemIndex
                val firstOffset = listState.firstVisibleItemScrollOffset
                val change = holder.loadNextPage()
                if (change.trimmedHead > 0) {
                    withFrameNanos { }
                    listState.scrollToItem((firstIndex - change.trimmedHead).coerceAtLeast(0), firstOffset)
                }
            }
            prev = canForward
        }
    }
    // 触顶前读：canScrollBackward 由可滚翻转为不可滚（滚回顶边）时前读上一窗，
    // 前读量即时补进滚动位置——用户视口停在原内容上，新上下文出现在上方。
    // 跳转落点（触顶态无翻转）不自动前读：跳到的行恒落在窗口顶。
    LaunchedEffect(listState) {
        var prev: Boolean? = null
        snapshotFlow { listState.canScrollBackward }.collect { canBackward ->
            if (prev == true && !canBackward && holder.canLoadMoreBackward()) {
                val firstIndex = listState.firstVisibleItemIndex
                val firstOffset = listState.firstVisibleItemScrollOffset
                val change = holder.loadPreviousPage()
                if (change.prepended > 0) {
                    withFrameNanos { }
                    listState.scrollToItem(firstIndex + change.prepended, firstOffset)
                }
            }
            prev = canBackward
        }
    }

    // 初始定位：Ready 就绪后按定位档窗口分页到目标行（行号优先、缺位取时间戳
    // ——决策单源 [jumpInitial]，落点恒窗口顶——与手动跳转同链路同落点语义）；
    // 两档均 null 不挂起零行为。Failed/Drifted 下挂起等待就绪，Dialog 关闭即随
    // 组合离开取消。时间戳档失败回执经 timestampJumpStatus 呈现（回执面即
    // 手动跳转的既有回执，不另造第二套）。
    LaunchedEffect(filePath, initialLine, initialTimestamp) {
        if (initialLine == null && initialTimestamp == null) return@LaunchedEffect
        holder.state.first { it == ViewerUiState.Ready }
        if (holder.jumpInitial(initialLine, initialTimestamp)) {
            withFrameNanos { }
            listState.scrollToItem(0)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 对话框家族统一默认值（AppConfirmDialog / RenameDialog 同族同档）
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier
            .fillMaxWidth()
            .appDialogRim(),
        title = { Text("查看日志") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                Text(
                    text = filePath,
                    style = DesktopTypography.monoTag,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when (val s = uiState) {
                    ViewerUiState.Opening -> Text(
                        text = "正在建立行索引…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is ViewerUiState.Failed -> StatusBanner(
                        status = StatusBannerStatus.Error,
                        text = s.reason,
                    )

                    is ViewerUiState.Drifted -> Column(
                        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        // 漂移不静默丢已载入内容：失效明示 + 原地重开，冻结内容保持可见
                        StatusBanner(
                            status = StatusBannerStatus.Error,
                            text = s.reason,
                            trailing = {
                                TextButton(onClick = { scope.launch { holder.reopen() } }) { Text("重新打开") }
                            },
                        )
                        if (lines.isNotEmpty()) {
                            ViewerLineList(
                                lines = lines,
                                totalLines = totalLines,
                                listState = listState,
                                bookmarkLineIndexes = bookmarkLines,
                                highlightHits = highlightLayers.hits,
                            )
                        }
                    }

                    ViewerUiState.Ready -> Column(
                        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        // 级别导航与行号跳转同区；无命中不动窗口。回退起点 = 窗口首行之前
                        // （不含），连点回退不重复命中当前行。文案与排布待真机终裁
                        val jumpLevel: (Set<LogStatsAnalyzer.Level>, Boolean) -> Unit = { levels, backward ->
                            scope.launch {
                                val jumped = if (backward) holder.jumpToPrevLevel(levels) else holder.jumpToNextLevel(levels)
                                if (jumped) {
                                    withFrameNanos { }
                                    listState.scrollToItem(0)
                                }
                            }
                        }
                        // 搜索框回车触发：同关键词再回车 = 跳下一处匹配（连击推进）。
                        // 形态与文案待真机终裁
                        val searchOrNextMatch: () -> Unit = {
                            val keyword = searchText.trim()
                            if (keyword.isNotEmpty()) {
                                scope.launch {
                                    if (holder.searchOrNext(keyword)) {
                                        withFrameNanos { }
                                        listState.scrollToItem(0)
                                    }
                                }
                            }
                        }
                        // 时间戳跳转：空输入静默忽略（与搜索空关键词同配方），其余
                        // 格式错经回执呈现。形态与文案待真机终裁
                        val jumpByTimestamp: () -> Unit = {
                            val target = timestampText.trim()
                            if (target.isNotEmpty()) {
                                scope.launch {
                                    if (holder.jumpToTimestamp(target)) {
                                        withFrameNanos { }
                                        listState.scrollToItem(0)
                                    }
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                singleLine = true,
                                textStyle = DesktopTypography.mono,
                                placeholder = {
                                    Text("关键词，回车搜索/下一处", style = DesktopTypography.mono)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .onEnterKey { searchOrNextMatch() },
                            )
                            searchStatus?.let {
                                Text(
                                    text = searchStatusText(it),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 匹配集反向入口：起点 = 窗口首行之前（不含）。文案与排布待真机终裁
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (holder.searchPrev()) {
                                            withFrameNanos { }
                                            listState.scrollToItem(0)
                                        }
                                    }
                                },
                                enabled = (searchStatus?.matchCount ?: 0) > 0,
                            ) { Text("上一处") }
                        }
                        // 时间戳跳转入口：跳转区首行（时间轴为日志定位主轴），形态对齐
                        // 搜索行（输入框 + 回执 + 按钮）。排布与文案待真机终裁
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            OutlinedTextField(
                                value = timestampText,
                                onValueChange = { timestampText = it },
                                singleLine = true,
                                textStyle = DesktopTypography.mono,
                                placeholder = {
                                    Text("时间戳 yyyy-MM-dd HH:mm:ss，回车跳转", style = DesktopTypography.mono)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .onEnterKey { jumpByTimestamp() },
                            )
                            timestampJumpStatus?.let {
                                Text(
                                    text = timestampJumpStatusText(it),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = jumpByTimestamp,
                                enabled = lines.isNotEmpty(),
                            ) { Text("跳转") }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            Text(
                                text = loadedRangeText(lines, totalLines),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = jumpText,
                                onValueChange = { jumpText = it },
                                singleLine = true,
                                textStyle = DesktopTypography.mono,
                                placeholder = {
                                    Text("行号，回车跳转", style = DesktopTypography.mono)
                                },
                                modifier = Modifier
                                    .width(Dimens.lineJumpFieldWidth)
                                    .onEnterKey {
                                        val target = jumpText.toIntOrNull() ?: return@onEnterKey
                                        scope.launch {
                                            if (holder.jumpTo(target)) {
                                                withFrameNanos { }
                                                listState.scrollToItem(0)
                                            }
                                        }
                                    },
                            )
                            TextButton(
                                onClick = { jumpLevel(NAV_ERROR_LEVELS, true) },
                                enabled = lines.isNotEmpty(),
                            ) { Text("上一处错误") }
                            TextButton(
                                onClick = { jumpLevel(NAV_ERROR_LEVELS, false) },
                                enabled = lines.isNotEmpty(),
                            ) { Text("下一处错误") }
                            TextButton(
                                onClick = { jumpLevel(NAV_WARN_LEVELS, true) },
                                enabled = lines.isNotEmpty(),
                            ) { Text("上一处警告") }
                            TextButton(
                                onClick = { jumpLevel(NAV_WARN_LEVELS, false) },
                                enabled = lines.isNotEmpty(),
                            ) { Text("下一处警告") }
                        }
                        // 书签动作行：「当前行」= 窗口首行（标记与跳转同锚）；两签时间差
                        // = 最近两签（标记序），无时间戳行人话拒绝不猜差值。
                        // 排布与文案待真机终裁
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                        ) {
                            Text(
                                text = bookmarkReceipt ?: "书签 ${bookmarkLines.size} 处",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            val headMarked =
                                bookmarkLines.contains((lines.firstOrNull()?.number ?: 0) - 1)
                            TextButton(
                                onClick = { holder.toggleBookmarkAtWindowHead() },
                                enabled = lines.isNotEmpty(),
                            ) { Text(if (headMarked) "取消标记" else "标记首行") }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (holder.jumpToPrevBookmark()) {
                                            withFrameNanos { }
                                            listState.scrollToItem(0)
                                        }
                                    }
                                },
                                enabled = bookmarkLines.isNotEmpty(),
                            ) { Text("上一签") }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        if (holder.jumpToNextBookmark()) {
                                            withFrameNanos { }
                                            listState.scrollToItem(0)
                                        }
                                    }
                                },
                                enabled = bookmarkLines.isNotEmpty(),
                            ) { Text("下一签") }
                            TextButton(
                                onClick = { scope.launch { holder.measureBookmarkDiff() } },
                                enabled = bookmarkLines.size >= 2,
                            ) { Text("两签时间差") }
                            TextButton(
                                onClick = { holder.clearBookmarks() },
                                enabled = bookmarkLines.isNotEmpty(),
                            ) { Text("清空书签") }
                        }
                        // 并行高亮通道管理面（会话级，回车提交才扫描；通道色槽按通道序
                        // 固定）。排布与文案待真机终裁
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                            ) {
                                Text(
                                    text = highlightChannelsHeaderText(highlightChannels.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { holder.addHighlightChannel() },
                                    enabled = lines.isNotEmpty() &&
                                        highlightChannels.size < LogViewerStateHolder.MAX_HIGHLIGHT_CHANNELS,
                                ) { Text("添加通道") }
                            }
                            highlightChannels.forEachIndexed { slot, channel ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                                ) {
                                    OutlinedTextField(
                                        value = highlightDrafts[slot] ?: channel.keyword,
                                        onValueChange = { highlightDrafts[slot] = it },
                                        singleLine = true,
                                        textStyle = DesktopTypography.mono,
                                        placeholder = {
                                            Text("通道 ${slot + 1} 关键词，回车高亮", style = DesktopTypography.mono)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .onEnterKey {
                                                val keyword = (highlightDrafts[slot] ?: channel.keyword).trim()
                                                if (keyword.isEmpty()) {
                                                    highlightDrafts.remove(slot)
                                                } else {
                                                    scope.launch {
                                                        if (holder.applyHighlightKeyword(slot, keyword)) {
                                                            highlightDrafts.remove(slot)
                                                        }
                                                    }
                                                }
                                            },
                                    )
                                    Text(
                                        text = highlightChannelStatusText(channel),
                                        style = MaterialTheme.typography.labelMedium,
                                        // 通道色自证映射：状态文案着通道色槽（停用态褪回中性色）
                                        color = if (channel.enabled) {
                                            highlightChannelColor(slot)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(onClick = { holder.toggleHighlightChannel(slot) }) {
                                        Text(if (channel.enabled) "停用" else "启用")
                                    }
                                    TextButton(
                                        onClick = {
                                            // 移除中间通道会使后继通道序位前移：草稿按序位
                                            // 键控，整体清空防陈旧草稿错挂到前移后的通道
                                            highlightDrafts.clear()
                                            holder.removeHighlightChannel(slot)
                                        },
                                    ) { Text("移除") }
                                }
                            }
                        }
                        // 行流与小地图同排：小地图刻度 = 会话级命中集/书签集，点击按比例
                        // 跳行（复用行号跳转链路）。排布形态待真机终裁
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (lines.isEmpty()) {
                                    Text(
                                        text = "文件为空",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    ViewerLineList(
                                        lines = lines,
                                        totalLines = totalLines,
                                        listState = listState,
                                        bookmarkLineIndexes = bookmarkLines,
                                        highlightHits = highlightLayers.hits,
                                    )
                                }
                            }
                            if (totalLines > 1) {
                                LogViewerMinimap(
                                    totalLines = totalLines,
                                    matchedLineIndexes = matchedLines,
                                    bookmarkLineIndexes = bookmarkLines,
                                    levelTicks = levelTicks,
                                    highlightTicks = highlightLayers.tickGroups,
                                    onSeekLine = { lineIndex ->
                                        scope.launch {
                                            if (holder.jumpTo(lineIndex + 1)) {
                                                withFrameNanos { }
                                                listState.scrollToItem(0)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // offer 成功即关闭：宿主 wire 内切页，Dialog 随页面卸载；显式关闭防任何
            // 接线变化下的浮层残留。返回 false（未接线）不动作——按钮缺席已由
            // isWired 门控，此处为防御兜底
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                // Failed 态加密件档位（引擎 typed 判型，非字符串匹配）：delegated
                // 属性禁 smart cast，先落局部值
                val failedEncrypted = (uiState as? ViewerUiState.Failed)?.encrypted == true
                if (uiState == ViewerUiState.Ready && DeepAnalyzeHook.isWired) {
                    TextButton(
                        onClick = {
                            // 第三参透传 initialTimestamp：带定位打开时复现时刻直达
                            // 工作台时间域；无定位/行号档 null = 不预填（行号不是时间点）
                            if (DeepAnalyzeHook.offer(listOf(filePath), "查看器打开的日志深度分析", initialTimestamp)) {
                                onDismiss()
                            }
                        },
                    ) { Text("深度分析") }
                }
                // 「送往 Tag 过滤」：目标查映射表取条目文案，offer 成功即关闭
                // （配方同上深度分析——宿主 wire 内打开命令面板，显式关闭防浮层残留）
                if (uiState == ViewerUiState.Ready && TaskPipelineHandoff.isWired) {
                    TaskPipelineHandoff.nextToolTargetOf(TaskPipelineHandoff.SourcePageId.LOG_VIEWER)
                        ?.let { target ->
                            TextButton(
                                onClick = {
                                    if (TaskPipelineHandoff.offer(target, filePath)) {
                                        onDismiss()
                                    }
                                },
                            ) { Text(target.label) }
                        }
                }
                // 「送时间截取」：横向直投（表外常量——切出物是本文件时间子集，
                // 非下一级管线段，不查映射表）。offer 扩参预填 start_time：仅时间
                // 戳定位档透传（行号不是时间点，行号档同无定位只预填路径）；end_time
                // 恒不传（留白走必填未填齐回执）。offer 成功即关闭——配方同上
                if (uiState == ViewerUiState.Ready && TaskPipelineHandoff.isWired) {
                    val sliceTarget = TaskPipelineHandoff.VIEWER_TIME_SLICE
                    TextButton(
                        onClick = {
                            val extraArgs = if (initialTimestamp != null) {
                                mapOf("start_time" to initialTimestamp)
                            } else {
                                emptyMap()
                            }
                            if (TaskPipelineHandoff.offer(sliceTarget, filePath, extraArgs)) {
                                onDismiss()
                            }
                        },
                    ) { Text(sliceTarget.label) }
                }
                // 「送 XLog 解密」：Failed 态加密件专属（非加密失败不渲染），offer
                // 成功即关闭——配方同上两动作
                if (failedEncrypted && TaskPipelineHandoff.isWired) {
                    TaskPipelineHandoff.nextToolTargetOf(TaskPipelineHandoff.SourcePageId.LOG_VIEWER_ENCRYPTED)
                        ?.let { target ->
                            TextButton(
                                onClick = {
                                    if (TaskPipelineHandoff.offer(target, filePath)) {
                                        onDismiss()
                                    }
                                },
                            ) { Text(target.label) }
                        }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/**
 * 窗口行集渲染：行号按总行数位宽补齐对齐（等宽字体下稳定列位）；key = 绝对行号。
 * 书签行行首前缀 ◆、非书签行等宽占位两字符——行号列位不漂移（视觉形态待真机终裁）。
 * 行文本可选中复制（「找到一行 → 带走」产出动线）：整容器粒度包 SelectionContainer
 * ——EvidenceTimeline 行流 / ChatMessageList 同款配方（同为整 LazyColumn 包裹，
 * 先例规模 2000 行与本行流同数量级）。行 Text 零 clickable（行点击跳转在日志
 * 对比页，不在查看器），拖选与跳转控件零交互冲突。LazyColumn 滚动回收下跨屏
 * 选区有瑕疵，验收判据 = 视口内拖选复制。
 *
 * 行着色层序契约（多通道命中同一行的裁决在 [buildHighlightLayers]，本件只消费
 * 合并结果）：并行高亮命中通道色 > 级别语义色（[levelColor]）——命中行整行持
 * 通道色（无命中背景高亮形态），未命中行维持级别语义色。
 */
@Composable
private fun ViewerLineList(
    lines: List<ViewerLine>,
    totalLines: Int,
    listState: LazyListState,
    bookmarkLineIndexes: List<Int>,
    highlightHits: Map<Int, Int>,
) {
    val numberWidth = totalLines.toString().length
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = Dimens.consoleHeightMax),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            items(lines, key = { it.number }) { line ->
                val bookmarkPrefix =
                    if (bookmarkLineIndexes.contains(line.number - 1)) "◆ " else "  "
                val lineIndex = line.number - 1
                Text(
                    text = bookmarkPrefix + line.number.toString().padStart(numberWidth) + "  " + line.text,
                    style = DesktopTypography.mono,
                    color = highlightHits[lineIndex]?.let { highlightChannelColor(it) }
                        ?: levelColor(line.level),
                )
            }
        }
    }
}

/**
 * 并行高亮通道语义色槽（按通道序固定）：0 = success、1 = info、其余 = aiAccent
 * ——与级别色（error / warning）、书签色（primary）、搜索命中色（error）均不
 * 撞槽。色槽绑定通道在列表中的序位，移除中间通道会使后继通道色槽前移（界面以
 * 通道状态文案着通道色自证映射）。超出 [LogViewerStateHolder.MAX_HIGHLIGHT_CHANNELS]
 * 的序位不出现（通道数在持有面钳制）。
 */
@Composable
internal fun highlightChannelColor(slot: Int): Color = when (slot) {
    0 -> LocalEasyToolboxColors.current.success
    1 -> LocalEasyToolboxColors.current.info
    else -> LocalEasyToolboxColors.current.aiAccent
}

/** 级别语义色映射（日志对比页 LineKind 先例同族；null = 续行/形态不命中按中性） */
@Composable
private fun levelColor(level: LogStatsAnalyzer.Level?): Color =
    when (level) {
        LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL -> MaterialTheme.colorScheme.error
        LogStatsAnalyzer.Level.WARN -> LocalEasyToolboxColors.current.warning
        LogStatsAnalyzer.Level.INFO -> MaterialTheme.colorScheme.primary
        LogStatsAnalyzer.Level.VERBOSE, LogStatsAnalyzer.Level.DEBUG ->
            MaterialTheme.colorScheme.onSurfaceVariant

        null -> MaterialTheme.colorScheme.onSurface
    }

private fun loadedRangeText(lines: List<ViewerLine>, totalLines: Int): String {
    val first = lines.firstOrNull()?.number
    val last = lines.lastOrNull()?.number
    return when {
        first == null || last == null -> "共 $totalLines 行"
        else -> "共 $totalLines 行 · 当前 $first-$last 行"
    }
}

/** 搜索状态呈现文案：截断须明示上界（不得把上界当作匹配总数） */
private fun searchStatusText(status: SearchStatus): String = when {
    status.matchCount == 0 -> "无匹配"
    status.truncated -> "超 ${LogViewer.MAX_SEARCH_RESULTS} 处"
    else -> "${status.matchCount} 处匹配"
}

/** 通道区头文案：满员时明示上限（超出不新增，不给静默禁用） */
private fun highlightChannelsHeaderText(count: Int): String =
    if (count >= LogViewerStateHolder.MAX_HIGHLIGHT_CHANNELS) {
        "并行高亮 $count/${LogViewerStateHolder.MAX_HIGHLIGHT_CHANNELS}（已达通道上限）"
    } else {
        "并行高亮 $count/${LogViewerStateHolder.MAX_HIGHLIGHT_CHANNELS}"
    }

/** 通道状态呈现文案：截断口径与 [searchStatusText] 同款（上界明示） */
private fun highlightChannelStatusText(channel: HighlightChannel): String = when {
    channel.keyword.isEmpty() -> "待输入关键词"
    channel.matchCount == 0 -> "无匹配"
    channel.truncated -> "超 ${LogViewer.MAX_SEARCH_RESULTS} 处"
    else -> "${channel.matchCount} 处"
}

/** 时间戳跳转回执呈现文案（成功跳转以 null 清零，不呈现） */
private fun timestampJumpStatusText(status: TimestampJumpStatus): String = when (status) {
    TimestampJumpStatus.InvalidFormat -> "时间格式无效，期望 yyyy-MM-dd HH:mm:ss.SSS（可空格分隔、省略毫秒）"
    TimestampJumpStatus.NoTimestampAfterTarget -> "该时间之后无日志行"
}

/**
 * 时间戳跳转回执：输入格式无效 / 目标之后无时间戳行（均不动窗口）；
 * 成功跳转清零为 null 不呈现
 */
internal enum class TimestampJumpStatus { InvalidFormat, NoTimestampAfterTarget }

/** 搜索状态：匹配数 + 截断标记（截断时匹配数即上界值，呈现须明示超限） */
internal data class SearchStatus(
    val keyword: String,
    val matchCount: Int,
    val truncated: Boolean,
)

/**
 * 并行高亮通道状态（会话级；命中集锚定本次会话索引，重开清零）。
 *
 * @property keyword 提交后的关键词（trim 后；空 = 通道待输入，无命中集）
 * @property enabled 启停位——停用只改可见面（行流着色与小地图刻度整体缺席），
 *   不清命中集（再启用零重扫）
 * @property matchCount 命中数（截断时即上界值，呈现须明示超限）
 * @property truncated true = 命中数达引擎扫描上界即止（文件中可能还有更多）
 * @property matchedLineIndexes 命中行 0 基下标集（升序；引擎 searchLines 单次扫描）
 */
internal data class HighlightChannel(
    val keyword: String,
    val enabled: Boolean = true,
    val matchCount: Int = 0,
    val truncated: Boolean = false,
    val matchedLineIndexes: List<Int> = emptyList(),
)

/** 并行高亮合并层（[buildHighlightLayers] 的产出；行流着色与小地图刻度共用） */
internal data class HighlightLayers(
    /** 命中行下标 → 持色通道序（层序裁决已定——多通道同行小序通道持色） */
    val hits: Map<Int, Int>,
    /** 小地图通道刻度组（仅启用通道；每行只入其持色通道的组） */
    val tickGroups: List<HighlightTickGroup>,
)

/**
 * 并行高亮层合并单源（纯函数，可单测）。层序契约：多通道命中同一行时**先建通道**
 * （列表小序）持色——大序后写被小序覆盖；停用通道整条排除（启停只改可见面，
 * 命中集保留）。行流着色与小地图通道刻度均消费本产出，两层不得各持一套层序。
 */
internal fun buildHighlightLayers(channels: List<HighlightChannel>): HighlightLayers {
    val hits = HashMap<Int, Int>()
    for (slot in channels.indices.reversed()) {
        val channel = channels[slot]
        if (!channel.enabled) continue
        for (lineIndex in channel.matchedLineIndexes) hits[lineIndex] = slot
    }
    val tickGroups = channels.mapIndexedNotNull { slot, channel ->
        if (!channel.enabled) return@mapIndexedNotNull null
        // 只入本通道持色的行（被小序通道覆盖的行归小序通道的组）
        val owned = channel.matchedLineIndexes.filter { hits[it] == slot }
        if (owned.isEmpty()) null else HighlightTickGroup(owned, slot)
    }
    return HighlightLayers(hits, tickGroups)
}

/** 级别导航错误档位：E|F 两级（「上一处/下一处错误」双向共用的单源定义处） */
private val NAV_ERROR_LEVELS: Set<LogStatsAnalyzer.Level> =
    setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)

/** 级别导航警告档位：W 单级（「上一处/下一处警告」双向共用） */
private val NAV_WARN_LEVELS: Set<LogStatsAnalyzer.Level> =
    setOf(LogStatsAnalyzer.Level.WARN)

/**
 * 查看器初始定位决策 + 执行（决策单源：行号与时间戳并存时**行号优先**——行号
 * 零歧义，时间戳是语义定位降级档）。时间戳档复用 [LogViewerStateHolder.jumpToTimestamp]
 * 引擎 seek 链路（与用户手动输入跳转同一条定位实现，无第二套）；失败回执同样经
 * [LogViewerStateHolder.timestampJumpStatus] 上报。两档均 null 返回 false 不动窗口。
 */
internal suspend fun LogViewerStateHolder.jumpInitial(
    initialLine: Int?,
    initialTimestamp: String?,
): Boolean = when {
    initialLine != null -> jumpTo(initialLine)
    initialTimestamp != null -> jumpToTimestamp(initialTimestamp)
    else -> false
}

/** 查看器状态机的对外呈现面：打开中 / 就绪 / 打开失败（原因内联明示）/ 漂移失效 */
internal sealed interface ViewerUiState {
    data object Opening : ViewerUiState

    data object Ready : ViewerUiState

    /**
     * 打开失败。@param encrypted true = 加密段结构被拒（引擎 typed 判型单源直传，
     * 消费方以此渲染「送 XLog 解密」衔接，不做失败文案字符串匹配）
     */
    data class Failed(val reason: String, val encrypted: Boolean = false) : ViewerUiState

    data class Drifted(val reason: String) : ViewerUiState
}

/** 窗口续读位移量：UI 滚动位置校正所需的三个量（各方向只填其一） */
internal data class PageChange(
    /** 触底续读追加的行数 */
    val appended: Int = 0,
    /** 触顶前读插入的行数 */
    val prepended: Int = 0,
    /** 追加后为守内存上界从头部裁掉的行数（滚动位置按此前移） */
    val trimmedHead: Int = 0,
)

/**
 * 查看器窗口编排状态（[LogViewerDialog] 的 VM 面，纯 Kotlin 可单测）：
 * 打开（[LogViewer.open] 包 IO）→ 首窗载入 → 触边续读 / 行号跳转。
 *
 * ## 内存上界（与引擎单窗口钳制同档）
 * 驻留行集 ≤ [LogViewer.MAX_WINDOW_LINES]（5000 行，续读超界裁远端）；
 * 行偏移索引驻留在引擎会话侧（8 字节/行）。
 *
 * ## 线程模型
 * 非线程安全：[scope] 上单调用方串行驱动（UI = 主线程协程域；测试可注入
 * Unconfined 测试调度器同步驱动）。阻塞 IO（引擎 open/readWindow）一律经
 * [ioDispatcher]。
 *
 * ## 漂移语义
 * 查看期间文件长度变化：readWindow 抛 IllegalStateException → 折叠为
 * [ViewerUiState.Drifted]（不静默、不崩溃），漂移期间续读/跳转均拒绝，
 * [reopen] 原地重建会话。
 *
 * ## 级别密度刻度与并行高亮通道（均为会话级，[open]/[reopen] 清零）
 * 级别刻度 = 就绪后一次全文件扫描的 E/F、W 槽首命中行（见 [scanLevelTicks]）；
 * 高亮通道命中扫描复用引擎 [LogViewSession.searchLines]（与搜索同上界同口径）。
 * 层序合并（多通道同行裁决）单源在顶层 [buildHighlightLayers]，本类只持通道集
 * 不做层序裁决。
 *
 * ## run 级书签持久化（[bookmarkRunId] 非空档）
 * 会话索引锚换 run 锚：标记/清空动作后当前书签集随 (runId, 文件路径) 快照落
 * [RunBookmarkStore]（行首无时间戳的行不持久化——指纹不可造，宁缺勿假）；
 * [open]/[reopen] 就绪后按 (1 基行号, 行首时间戳指纹) 双锚恢复——指纹不一致
 * （行文漂移）拒绝复活该签，拒绝签数入人话回执（[bookmarkDiffReceipt] 通道）。
 * 持久化无 run 锚（null）时零行为——会话级书签现状不变。
 */
internal class LogViewerStateHolder(
    private val file: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** run 级书签持久化锚（null = 会话级书签现状，零持久化行为） */
    private val bookmarkRunId: String? = null,
    /** run 级书签仓储（[bookmarkRunId] 非空时由调用方注入；测试注入隔离库） */
    private val bookmarkStore: RunBookmarkStore? = null,
) {
    private var session: LogViewSession? = null

    private val _state = MutableStateFlow<ViewerUiState>(ViewerUiState.Opening)
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _lines = MutableStateFlow<List<ViewerLine>>(emptyList())
    val lines: StateFlow<List<ViewerLine>> = _lines.asStateFlow()

    private val _totalLines = MutableStateFlow(0)
    val totalLines: StateFlow<Int> = _totalLines.asStateFlow()

    /** 最近一次搜索的匹配行下标集（0 基，有界；引擎截断时即上界值） */
    private var searchMatches: List<Int> = emptyList()

    private val _searchStatus = MutableStateFlow<SearchStatus?>(null)

    /** 最近一次搜索状态（null = 尚未搜索）；[reopen] 后清零 */
    val searchStatus: StateFlow<SearchStatus?> = _searchStatus.asStateFlow()

    private val _timestampJumpStatus = MutableStateFlow<TimestampJumpStatus?>(null)

    /** 最近一次时间戳跳转回执（null = 无待呈现回执；成功跳转与 [reopen] 均清零） */
    val timestampJumpStatus: StateFlow<TimestampJumpStatus?> = _timestampJumpStatus.asStateFlow()

    private val _matchedLineIndexes = MutableStateFlow<List<Int>>(emptyList())

    /** 最近一次搜索匹配行集（0 基升序；小地图命中刻度数据源）；[reopen] 后清零 */
    val matchedLineIndexes: StateFlow<List<Int>> = _matchedLineIndexes.asStateFlow()

    private val _levelTicks = MutableStateFlow(LevelTicks())

    /**
     * 级别密度刻度集（E/F 与 W 每槽首命中行，0 基升序；小地图级别刻度数据源）。
     * 打开就绪后由一次全文件级别扫描发布（见 [open]）；[reopen] 重扫。
     */
    val levelTicks: StateFlow<LevelTicks> = _levelTicks.asStateFlow()

    private val _highlightChannels = MutableStateFlow<List<HighlightChannel>>(emptyList())

    /** 并行高亮通道集（会话级；[reopen] 清零——命中集锚定本次会话索引） */
    val highlightChannels: StateFlow<List<HighlightChannel>> = _highlightChannels.asStateFlow()

    /** 会话级书签集（0 基行下标；随会话重建，见 [open]） */
    private var bookmarks = LogBookmarkSet(0)

    private val _bookmarkLineIndexes = MutableStateFlow<List<Int>>(emptyList())

    /** 书签行集快照（0 基升序；行首标记渲染、上一签/下一签跳转与小地图书签刻度的数据源） */
    val bookmarkLineIndexes: StateFlow<List<Int>> = _bookmarkLineIndexes.asStateFlow()

    private val _bookmarkDiffReceipt = MutableStateFlow<String?>(null)

    /** 最近一次两签时间差回执（null = 无待呈现；[clearBookmarks] 与 [reopen] 均清零） */
    val bookmarkDiffReceipt: StateFlow<String?> = _bookmarkDiffReceipt.asStateFlow()

    init {
        scope.launch { open() }
    }

    /** 窗口尾已到文件末行（true = 触底无更多可读） */
    fun canLoadMoreForward(): Boolean {
        val last = _lines.value.lastOrNull() ?: return false
        return _state.value == ViewerUiState.Ready && last.number < _totalLines.value
    }

    /** 窗口头已是文件首行（true = 触顶无更多可前读） */
    fun canLoadMoreBackward(): Boolean {
        val first = _lines.value.firstOrNull() ?: return false
        return _state.value == ViewerUiState.Ready && first.number > 1
    }

    /** 触底续读：从窗口尾读下一窗追加；超驻留上界时裁头部并上报裁剪量 */
    suspend fun loadNextPage(): PageChange {
        val s = session ?: return PageChange()
        if (!canLoadMoreForward()) return PageChange()
        val current = _lines.value
        val page = readWindowOrDrift(s, current.last().number, PAGE_LINES) ?: return PageChange()
        val merged = current + page
        val trimmedHead = (merged.size - LogViewer.MAX_WINDOW_LINES).coerceAtLeast(0)
        _lines.value = if (trimmedHead > 0) merged.drop(trimmedHead) else merged
        return PageChange(appended = page.size, trimmedHead = trimmedHead)
    }

    /** 触顶前读：窗口头之前读一窗插入头部；超驻留上界时裁尾部（头部索引不受影响） */
    suspend fun loadPreviousPage(): PageChange {
        val s = session ?: return PageChange()
        if (!canLoadMoreBackward()) return PageChange()
        val current = _lines.value
        val start = (current.first().number - 1 - PAGE_LINES).coerceAtLeast(0)
        val page = readWindowOrDrift(s, start, PAGE_LINES) ?: return PageChange()
        val merged = page + current
        val overflow = (merged.size - LogViewer.MAX_WINDOW_LINES).coerceAtLeast(0)
        _lines.value = if (overflow > 0) merged.dropLast(overflow) else merged
        return PageChange(prepended = page.size)
    }

    /** 行号跳转：窗口整体替换为以 [lineNumber]（1 基，越界钳制到 [totalLines]）为起点的窗口 */
    suspend fun jumpTo(lineNumber: Int): Boolean {
        val s = session ?: return false
        if (_state.value != ViewerUiState.Ready || _totalLines.value <= 0) return false
        val target = lineNumber.coerceIn(1, _totalLines.value)
        val window = readWindowOrDrift(s, target - 1, PAGE_LINES) ?: return false
        if (window.isEmpty()) return false
        _lines.value = window
        return true
    }

    /**
     * 级别导航跳转：自当前窗口首行之后找首个级别命中行（引擎逐窗 seek 扫描），
     * 命中则窗口分页到目标行（落点恒为窗口首行）；无命中不动窗口返回 false。
     * 起点取窗口首行之后——连点行为 = 逐错误依次后移，不重复命中当前窗口首行。
     */
    suspend fun jumpToNextLevel(levels: Set<LogStatsAnalyzer.Level>): Boolean {
        val s = session ?: return false
        if (_state.value != ViewerUiState.Ready || _totalLines.value <= 0) return false
        val firstLoaded = _lines.value.firstOrNull()?.number ?: return false
        // 窗口首行 1 基行号 = 其后一行的 0 基行下标，即引擎扫描起点（含）
        val next = ioOrDrift { s.nextLevelLine(firstLoaded, levels) } ?: return false
        return jumpTo(next + 1)
    }

    /**
     * 级别导航反向跳转：自当前窗口首行**之前**（不含，引擎 [LogViewSession.prevLevelLine]
     * 逐窗反向 seek）找最近的级别命中行，命中则窗口分页到目标行（落点恒为窗口首行）；
     * 无命中不动窗口返回 false。起点口径与 [jumpToNextLevel] 成对：next 自窗口首行
     * 之后（含）推进，本函数取窗口首行之前（不含）——连点回退逐错误依次前移，
     * 不重复命中当前窗口首行。
     */
    suspend fun jumpToPrevLevel(levels: Set<LogStatsAnalyzer.Level>): Boolean {
        val s = session ?: return false
        if (_state.value != ViewerUiState.Ready || _totalLines.value <= 0) return false
        val firstLoaded = _lines.value.firstOrNull()?.number ?: return false
        // 窗口首行的 0 基行下标 = firstLoaded - 1；不含起点即严格在窗口首行之前
        val prev = ioOrDrift { s.prevLevelLine(firstLoaded - 1, levels) } ?: return false
        return jumpTo(prev + 1)
    }

    /**
     * 搜索框回车入口：关键词与最近一次搜索相同则跳下一处匹配（连击推进）；
     * 否则全文件搜索（引擎逐窗扫描，结果集有界）后分页到首处匹配。结果经
     * [searchStatus] 上报（匹配数/截断）；无匹配与空白关键词不动窗口返回
     * false。漂移经 [ioOrDrift] 折叠（搜索态不更新，旧匹配集随 reopen 清零）。
     */
    suspend fun searchOrNext(keyword: String): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return false
        val status = _searchStatus.value
        if (status != null && status.keyword == trimmed && searchMatches.isNotEmpty()) {
            return jumpToNextMatch()
        }
        val s = session ?: return false
        if (_state.value != ViewerUiState.Ready || _totalLines.value <= 0) return false
        val result = ioOrDrift { s.searchLines(0, trimmed) } ?: return false
        searchMatches = result.matchedLineIndexes
        // 命中集发布（小地图数据源；同关键词连击推进不重搜，集不变）
        _matchedLineIndexes.value = searchMatches
        _searchStatus.value = SearchStatus(trimmed, searchMatches.size, result.truncated)
        if (searchMatches.isEmpty()) return false
        return jumpTo(searchMatches.first() + 1)
    }

    /**
     * 跳上一处匹配（[searchOrNext] 的反向入口）：取当前窗口首行之前（不含）的最近
     * 匹配行，命中则窗口分页到目标行；首处之前无环绕返回 false。起点口径与
     * [jumpToPrevLevel] 一致（连点回退不重复命中当前窗口首行）。未搜索过、匹配集
     * 为空或漂移后旧集已清零时恒无操作返回 false；纯内存操作（匹配集已持有，
     * 零引擎调用）。
     */
    suspend fun searchPrev(): Boolean {
        if (searchMatches.isEmpty()) return false
        if (_state.value != ViewerUiState.Ready) return false
        val firstLoaded = _lines.value.firstOrNull()?.number ?: return false
        // 严格在窗口首行之前 = 0 基下标 < 窗口首行的 0 基下标（firstLoaded - 1）
        val prev = searchMatches.lastOrNull { it < firstLoaded - 1 } ?: return false
        return jumpTo(prev + 1)
    }

    /**
     * 新增空高亮通道（关键词待输入；命中扫描经 [applyHighlightKeyword] 提交通道）。
     * 满 [MAX_HIGHLIGHT_CHANNELS] 拒绝返回 false（呈现面以上限提示承接）。
     * 纯内存操作（零引擎调用）；非 Ready 态拒绝。
     */
    fun addHighlightChannel(): Boolean {
        if (_state.value != ViewerUiState.Ready) return false
        if (_highlightChannels.value.size >= MAX_HIGHLIGHT_CHANNELS) return false
        _highlightChannels.value = _highlightChannels.value + HighlightChannel(keyword = "")
        return true
    }

    /**
     * 提交通道关键词：空关键词清空该通道命中集（通道保留待再输入）；非空经引擎
     * [LogViewSession.searchLines] 单次全文件扫描（与搜索同上界同口径——匹配集
     * 达 [LogViewer.MAX_SEARCH_RESULTS] 即止并置截断），命中集替入通道。漂移经
     * [ioOrDrift] 折叠（通道旧命中集保留至 [reopen] 清零）。
     *
     * @return true = 提交生效（含空关键词清空档）；通道缺席/非 Ready/漂移返回 false
     */
    suspend fun applyHighlightKeyword(slot: Int, keyword: String): Boolean {
        if (slot !in _highlightChannels.value.indices) return false
        if (_state.value != ViewerUiState.Ready) return false
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            updateChannel(slot) {
                it.copy(keyword = "", matchCount = 0, truncated = false, matchedLineIndexes = emptyList())
            }
            return true
        }
        val s = session ?: return false
        val result = ioOrDrift { s.searchLines(0, trimmed) } ?: return false
        updateChannel(slot) {
            it.copy(
                keyword = trimmed,
                matchCount = result.matchedLineIndexes.size,
                truncated = result.truncated,
                matchedLineIndexes = result.matchedLineIndexes,
            )
        }
        return true
    }

    /** 通道启停（纯内存操作：停用只改可见面，命中集保留——再启用零重扫） */
    fun toggleHighlightChannel(slot: Int) {
        updateChannel(slot) { it.copy(enabled = !it.enabled) }
    }

    /** 移除通道（后继通道序位前移——色槽随序位绑定，见 [highlightChannelColor]） */
    fun removeHighlightChannel(slot: Int) {
        if (slot !in _highlightChannels.value.indices) return
        _highlightChannels.value = _highlightChannels.value.filterIndexed { i, _ -> i != slot }
    }

    private fun updateChannel(slot: Int, transform: (HighlightChannel) -> HighlightChannel) {
        val channels = _highlightChannels.value
        if (slot !in channels.indices) return
        _highlightChannels.value = channels.toMutableList().also { it[slot] = transform(it[slot]) }
    }

    /**
     * 翻转窗口首行书签（「当前行」= 窗口首行——跳转落点恒窗口首行，标记与跳转
     * 同锚）。翻转后书签行集经 [bookmarkLineIndexes] 发布。非 Ready 态拒绝（书签
     * 锚定本次会话索引，漂移/打开中不标记）。纯内存操作（零引擎调用）。
     *
     * @return 翻转后处于已标记态为 true；空窗口/非 Ready 态返回 false
     */
    fun toggleBookmarkAtWindowHead(): Boolean {
        if (_state.value != ViewerUiState.Ready) return false
        val first = _lines.value.firstOrNull() ?: return false
        val toggled = bookmarks.toggle(first.number - 1)
        publishBookmarks()
        scheduleBookmarkPersist()
        return toggled
    }

    /**
     * 上一签跳转：取当前窗口首行之前（不含）的最近书签行，命中则窗口分页到目标行
     * （落点恒窗口首行）；首签之前无环绕返回 false。起点口径与 [searchPrev] 一致
     * （连点回退不重复命中当前窗口首行）；纯内存操作（书签集已持有，零引擎调用）。
     */
    suspend fun jumpToPrevBookmark(): Boolean {
        if (_state.value != ViewerUiState.Ready) return false
        if (bookmarks.count == 0) return false
        val firstLoaded = _lines.value.firstOrNull()?.number ?: return false
        val prev = _bookmarkLineIndexes.value.lastOrNull { it < firstLoaded - 1 } ?: return false
        return jumpTo(prev + 1)
    }

    /**
     * 下一签跳转：自当前窗口首行之后（0 基含后继行口径，同 [jumpToNextMatch]——
     * 连点推进不重复命中当前窗口首行）取首个书签行，命中则窗口分页到目标行；
     * 末签之后无环绕返回 false。纯内存操作。
     */
    suspend fun jumpToNextBookmark(): Boolean {
        if (_state.value != ViewerUiState.Ready) return false
        if (bookmarks.count == 0) return false
        val firstLoaded = _lines.value.firstOrNull()?.number ?: return false
        val next = _bookmarkLineIndexes.value.firstOrNull { it >= firstLoaded } ?: return false
        return jumpTo(next + 1)
    }

    /**
     * 两签时间差：测量最近两签（标记序尾两签，前签 → 后签）的行首时间戳差值。
     * 行原文经引擎单行回读（漂移折叠为 Drifted，回执不变）；差值经 [LogTimeDiff]
     * 纯函数计算（裸格式行锚定年份取当前年——单格式文件内相对先后不受影响），
     * 人话回执（行号 + 差值；负差值倒流明示；无时间戳行拒绝不猜差值）经
     * [bookmarkDiffReceipt] 发布。书签不足两签无操作返回 false（回执不变）。
     *
     * @return true = 测量成功（回执已发布）；无时间戳拒绝返回 false（拒绝回执同样发布）
     */
    suspend fun measureBookmarkDiff(): Boolean {
        if (_state.value != ViewerUiState.Ready) return false
        val inOrder = bookmarks.markedInOrder()
        if (inOrder.size < 2) return false
        val s = session ?: return false
        val fromIndex = inOrder[inOrder.size - 2]
        val toIndex = inOrder[inOrder.size - 1]
        val fromText = readWindowOrDrift(s, fromIndex, 1)?.firstOrNull()?.text ?: return false
        val toText = readWindowOrDrift(s, toIndex, 1)?.firstOrNull()?.text ?: return false
        val result = LogTimeDiff.measure(fromText, toText, Year.now().value.toString())
        _bookmarkDiffReceipt.value = when (result) {
            is LogTimeDiff.Result.Ok ->
                "第 ${fromIndex + 1} 行 → 第 ${toIndex + 1} 行：${result.text}"

            is LogTimeDiff.Result.NoTimestamp -> {
                val lineNumber = (if (result.end == LogTimeDiff.End.FROM) fromIndex else toIndex) + 1
                "第 $lineNumber 行无行首时间戳，无法测量（书签须打在带时间戳的行上）"
            }
        }
        return result is LogTimeDiff.Result.Ok
    }

    /** 清空书签（行集快照与时间差回执一并清零；空快照随持久化档落库 = 清掉存档） */
    fun clearBookmarks() {
        bookmarks.clear()
        publishBookmarks()
        _bookmarkDiffReceipt.value = null
        scheduleBookmarkPersist()
    }

    /** 书签行集变更发布（行序快照，升序） */
    private fun publishBookmarks() {
        _bookmarkLineIndexes.value = bookmarks.sortedMarks()
    }

    /**
     * run 级书签快照持久化调度（标记/清空动作后异步落库——动作是用户节拍的
     * 低频操作，快照全量覆写无增量合并面）。持久化档缺席时恒零行为。
     */
    private fun scheduleBookmarkPersist() {
        val runId = bookmarkRunId ?: return
        val store = bookmarkStore ?: return
        scope.launch { persistRunBookmarks(runId, store) }
    }

    /**
     * 当前书签集快照落库：逐签回读行文造指纹（行首时间戳，解析单源
     * `parseLogLineTimestamp`；无时间戳行不入库）。读行期间漂移则放弃本次快照
     * ——快照是全量覆写语义，半截覆写会静默丢未漂移的签，宁可不写。
     */
    private suspend fun persistRunBookmarks(runId: String, store: RunBookmarkStore) {
        val s = session ?: return
        if (_state.value != ViewerUiState.Ready) return
        val snapshot = ArrayList<RunBookmarkStore.StoredBookmark>(bookmarks.count)
        for (index in bookmarks.sortedMarks()) {
            val text = readWindowOrDrift(s, index, 1)?.firstOrNull()?.text
            val fingerprint = text?.parseLogLineTimestamp(bookmarkFingerprintYear)
            if (fingerprint != null) {
                snapshot += RunBookmarkStore.StoredBookmark(index + 1, fingerprint.toString())
            }
        }
        if (_state.value != ViewerUiState.Ready) return
        withContext(ioDispatcher) {
            runCatching { store.saveSnapshot(runId, file.absolutePath, snapshot) }
        }
    }

    /**
     * run 级书签恢复（[open]/[reopen] 就绪后调用——换会话后按行文指纹逐签重新
     * 校验）。双锚复用判定：1 基行号在界内 + 该行行首时间戳指纹与存档一致；
     * 任一不满足拒绝复活该签（行文漂移/行号越界），恢复与拒绝签数入人话回执
     * （复用 [bookmarkDiffReceipt] 既有回执通道——无存档零回执零行为）。
     */
    private suspend fun restoreRunBookmarks(s: LogViewSession) {
        val runId = bookmarkRunId ?: return
        val store = bookmarkStore ?: return
        val stored = withContext(ioDispatcher) {
            runCatching { store.bookmarksFor(runId, file.absolutePath) }.getOrDefault(emptyList())
        }
        if (stored.isEmpty()) return
        var restored = 0
        var rejected = 0
        for (bookmark in stored) {
            val index = bookmark.lineNumber - 1
            val text = if (index in 0 until s.totalLines) {
                readWindowOrDrift(s, index, 1)?.firstOrNull()?.text
            } else {
                null
            }
            val fingerprint = text?.parseLogLineTimestamp(bookmarkFingerprintYear)
            if (fingerprint != null && fingerprint.toString() == bookmark.lineFingerprint) {
                bookmarks.toggle(index)
                restored++
            } else {
                rejected++
            }
        }
        publishBookmarks()
        _bookmarkDiffReceipt.value = if (rejected == 0) {
            "已恢复上次书签 $restored 处"
        } else {
            "已恢复上次书签 $restored 处，$rejected 处因文件已变化丢弃"
        }
    }

    /**
     * 裸格式 `MM-dd` 行的指纹锚定年份（保存/恢复两测同锚，口径同
     * [measureBookmarkDiff]；跨年重开按指纹漂移拒绝处理——保守不复活）。
     */
    private val bookmarkFingerprintYear: String get() = Year.now().value.toString()

    /**
     * 跳下一处匹配：自当前窗口首行之后取首个匹配行（连点依次后移，不重复命中
     * 当前窗口首行——起点口径与 [jumpToNextLevel] 一致），末处之后无环绕返回 false。
     */
    private suspend fun jumpToNextMatch(): Boolean {
        if (searchMatches.isEmpty()) return false
        if (_state.value != ViewerUiState.Ready) return false
        val firstLoaded = _lines.value.firstOrNull()?.number ?: return false
        // 窗口首行 1 基行号 = 其后一行的 0 基行下标，即下一处匹配的搜索起点（含）
        val next = searchMatches.firstOrNull { it >= firstLoaded } ?: return false
        return jumpTo(next + 1)
    }

    /**
     * 时间戳跳转入口：解析 [target]（复用目标时间解析契约——容忍空格分隔与省略
     * 毫秒，格式单源在 log-tools，无第二套解析口径）后，全文件 seek 首个行首
     * 时间戳 ≥ 目标的行（引擎逐窗扫描；起点恒为文件首行——用户自报告/工单拿到的
     * 是时间戳，无「自当前位置推进」语义），命中则复用 [jumpTo] 窗口分页链路
     * 落到目标行（落点恒为窗口首行）并清零回执；解析失败与无命中均不动窗口
     * 返回 false，回执经 [timestampJumpStatus] 上报。漂移经 [ioOrDrift] 折叠
     * （回执不更新，随 reopen 清零）。
     */
    suspend fun jumpToTimestamp(target: String): Boolean {
        val parsed = target.parseLogTimestampTarget()
        if (parsed == null) {
            _timestampJumpStatus.value = TimestampJumpStatus.InvalidFormat
            return false
        }
        val s = session ?: return false
        if (_state.value != ViewerUiState.Ready || _totalLines.value <= 0) return false
        val next = ioOrDrift { s.nextTimestampLine(0, parsed) } ?: run {
            _timestampJumpStatus.value = TimestampJumpStatus.NoTimestampAfterTarget
            return false
        }
        _timestampJumpStatus.value = null
        return jumpTo(next + 1)
    }

    /** 漂移失效后的原地重开：重建会话与首窗，恢复 Ready */
    suspend fun reopen() {
        session = null
        open()
    }

    private suspend fun open() {
        _state.value = ViewerUiState.Opening
        _lines.value = emptyList()
        // 旧匹配集锚定旧索引：重开/首开一律清零（文件可能已变化，旧集不可复用）；
        // 时间戳跳转回执、命中集（小地图数据源）、级别刻度集、高亮通道集、书签集
        // 与时间差回执同理随重开失效
        searchMatches = emptyList()
        _searchStatus.value = null
        _timestampJumpStatus.value = null
        _matchedLineIndexes.value = emptyList()
        _levelTicks.value = LevelTicks()
        _highlightChannels.value = emptyList()
        bookmarks = LogBookmarkSet(0)
        publishBookmarks()
        _bookmarkDiffReceipt.value = null
        when (val result = withContext(ioDispatcher) { LogViewer.open(file) }) {
            is LogViewer.OpenResult.Failure -> {
                _state.value = ViewerUiState.Failed(result.reason, result.encrypted)
                return
            }

            is LogViewer.OpenResult.Ok -> session = result.session
        }
        val s = session ?: return
        _totalLines.value = s.totalLines
        // 书签行集锚定本次会话索引（总行数作域上界），随会话重建
        bookmarks = LogBookmarkSet(s.totalLines)
        val first = readWindowOrDrift(s, 0, PAGE_LINES) ?: return
        _lines.value = first
        _state.value = ViewerUiState.Ready
        // run 级存档恢复（持久化档缺席恒零行为；恢复行读漂移则折叠 Drifted 同续读）
        restoreRunBookmarks(s)
        // 级别密度刻度扫描（就绪已发布，本扫描不阻塞 Ready 呈现；漂移折叠 Drifted）
        scanLevelTicks(s)
    }

    /**
     * 全文件级别扫描（一次，随 [open]/[reopen]）：引擎 [LogViewSession.readWindow]
     * 逐窗回读，级别判定单源 = 行集自带级别（引擎 parseHead 解析，无第二套判定）。
     * E/F 与 W 各自按密度槽（[levelSlotOf]）记**每槽首个命中行**——刻度数以槽位数
     * 为上界（槽位数与搜索命中集同档，真源 [LogViewer.MAX_SEARCH_RESULTS]），刻度
     * 锚仍是行下标（绘制走既有行位比例映射）。行读漂移折叠 Drifted（刻度集维持
     * 清零态，不发布半截扫描）。
     */
    private suspend fun scanLevelTicks(s: LogViewSession) {
        val total = s.totalLines
        if (total <= 0) return
        val slotCount = LogViewer.MAX_SEARCH_RESULTS
        val errorFirstHit = IntArray(slotCount) { -1 }
        val warnFirstHit = IntArray(slotCount) { -1 }
        var from = 0
        while (from < total) {
            val window = readWindowOrDrift(s, from, LogViewer.MAX_WINDOW_LINES) ?: return
            for (i in window.indices) {
                val lineIndex = from + i
                when (window[i].level) {
                    LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL ->
                        markLevelTick(errorFirstHit, lineIndex, total, slotCount)

                    LogStatsAnalyzer.Level.WARN ->
                        markLevelTick(warnFirstHit, lineIndex, total, slotCount)

                    else -> {}
                }
            }
            from += window.size
        }
        _levelTicks.value = LevelTicks(
            errorLineIndexes = errorFirstHit.filter { it >= 0 },
            warnLineIndexes = warnFirstHit.filter { it >= 0 },
        )
    }

    /** 槽首命中登记：升序扫描下首个落入该槽的行即槽锚（后继行不覆盖） */
    private fun markLevelTick(
        firstHit: IntArray,
        lineIndex: Int,
        totalLines: Int,
        slotCount: Int,
    ) {
        val slot = levelSlotOf(lineIndex, totalLines, slotCount) ?: return
        if (firstHit[slot] < 0) firstHit[slot] = lineIndex
    }

    /** 单源续读通道：漂移（IllegalStateException）折叠为 Drifted 态并返回 null */
    private suspend fun readWindowOrDrift(
        s: LogViewSession,
        startLine: Int,
        maxLines: Int,
    ): List<ViewerLine>? = ioOrDrift { s.readWindow(startLine, maxLines) }

    /** 单源引擎 IO 通道：漂移折叠为 Drifted 态并返回 null（引擎调用方不得裸调引擎） */
    private suspend fun <T> ioOrDrift(block: () -> T): T? = try {
        withContext(ioDispatcher) { block() }
    } catch (e: IllegalStateException) {
        session = null
        _state.value = ViewerUiState.Drifted(e.message ?: "文件在查看期间已变化，索引已失效")
        null
    }

    companion object {
        /**
         * 单窗续读行数（值依据：对话框视口高 consoleHeightMax 420dp 约二十余行，
         * 500 行 = 数十屏缓冲，10 次触边续读才达驻留上界 5000 行）。
         */
        const val PAGE_LINES = 500

        /**
         * 并行高亮通道数上界（真门禁：满员即拒绝新增）。值依据：通道色槽按通道序
         * 固定且须与级别色/书签色/搜索命中色零撞槽，3 槽（success/info/aiAccent）
         * 已用尽零撞槽色位；更多通道的区分度收益不抵色槽与认知负担。
         */
        const val MAX_HIGHLIGHT_CHANNELS = 3
    }
}
