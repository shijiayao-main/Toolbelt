package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.data.pathFileName
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/**
 * 报告页聚焦槽（palette 未读产物行点名具体 run → 报告卡直达定位的一次性载荷）。
 *
 * object 门面单例 + pending 槽（LogWorkbenchDeepLink / KbManagePanelHook 先例同构）：
 * palette 三处 ReportRun 行点击 `offer(runId)` → PageReport 双 effect 消费（组合期
 * 初值一次性取走 + 在页订阅 collect 读即清——ToolsPage 预选槽四面同款配方）→
 * 目标卡展开 + 滚动定位 + 2s 微洗底。两端同在本包（composeApp 单页载荷），
 * 不升 :core。
 */
internal object ReportFocusRequest {
    private val _pending = MutableStateFlow<String?>(null)

    /** 待消费的聚焦载荷（null = 无；非 null = 目标 runId） */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** palette 未读产物行点击投递（行携带的 runId——报告卡直达锚） */
    fun offer(runId: String) {
        _pending.value = runId
    }

    /** 页面侧消费（取走即清空——一次性语义） */
    fun consume(): String? {
        val value = _pending.value
        _pending.value = null
        return value
    }

    /** 测试缝：清空槽（单测隔离用） */
    fun clearForTest() {
        _pending.value = null
    }
}

/**
 * 报告页（tab 集第四成员）。
 * ## 内容形态
 * - 列表 = 最近 run 报告卡（[RunStore.listRuns] 中 reportPath 非空者，倒序截前
 *   [MAX_REPORT_CARDS] 件）：报告文件名 + run 标题 + meta 行两 pill（状态/相对时间）+
 *   两动作钮——
 *   「查看」经系统默认程序打开报告文件本体（[DesktopUtils.openFile]——失败
 *   JLog 落盘 + 降级打开所在目录）、「去工作台」跳 LogWorkbench 回看 run；
 * - **整卡点击 = 展开/收起正文只读区**：markdown 源文本态 mono
 *   渲染（不做 md 完整解析，占位期最小闭环）+ 有界滚动 + 惰性拉取（首次展开才读，
 *   runCatching 失败显错误文案不静默）；折叠/展开态 [rememberSaveable] 随卡重组保持；
 * - 空/超 [MAX_REPORT_CARDS] 截断时列表尾补说明行（[truncationNotice]——第 51 份起
 *   不再静默消失，旧报告经工作台分析历史回看）；
 * - palette 未读产物行点名 run 经 [ReportFocusRequest] 槽直达该卡（展开 + 滚动定位 +
 *   2s 微洗底——用户点名具体报告不再二次肉眼定位）；
 * - 空态 = 引导文案 + 「去工作台」按钮（研判报告尚未产出时的可达性锚）；
 * - 跨 run 对比/导出仍挂起（本卡范围外）。
 *
 * ## 纪律
 * - 读面只读不写：RunStore 直连 + LaunchedEffect(Dispatchers.IO) 拉取
 *   （SpaceBarState 同款模式——无自建 scope，AppHostGuard 合规；本页只读，
 *   进页重拉即最新，无需全局 reactive 源）；
 * - 正文拉取 = [readReportBody]（WorkbenchViewModel.readReportFile 同款三行配方
 *   复刻——feature-logworkbench 私有不可见，composeApp 侧不跨模块引用）；
 * - 导航经注入 [onGoWorkbench]（双参：报告卡传 runId——
 *   宿主装配点 offer 回看深链 + setCurrentPage 组合，工作台进入即 restore 该 run；
 *   空态引导传 null 保持「只切页」语义）。
 */
@Composable
fun PageReport(
    onGoWorkbench: (runId: String?) -> Unit = {},
) {
    var reports by remember { mutableStateOf<List<RunStore.RunSummary>?>(null) }
    // 报告总量（take 后原始量保留——>MAX 时驱动列表尾截断说明行）
    var totalCount by remember { mutableStateOf(0) }
    // 报告卡读面（null = 拉取中——首帧空态不闪「暂无报告」误判）
    LaunchedEffect(Unit) {
        val all = withContext(Dispatchers.IO) {
            RunStore(Database.database()).listRuns().filter { it.reportPath != null }
        }
        totalCount = all.size
        reports = all.take(MAX_REPORT_CARDS)
    }

    // 聚焦槽消费（palette 未读产物行点名 run → 目标卡展开 + 滚动定位 + 2s 微洗底）：
    // 双 effect 配方（ToolsPage 预选槽四面同款——报告页已是当前页时 palette offer 后
    // setCurrentPage 同值不发射，组合期一次性消费不重跑，订阅 effect 承担在页新载荷）
    var focusRunId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        ReportFocusRequest.consume()?.let { focusRunId = it }
    }
    LaunchedEffect(ReportFocusRequest) {
        ReportFocusRequest.pending
            .filterNotNull()
            .collect { runId ->
                // 读即清（与组合期初值承载同款收口——防重开重读旧载荷）
                ReportFocusRequest.consume()
                focusRunId = runId
            }
    }
    // 微洗底 2s 自清（展开在卡内是一次性闩——洗底退场不回折收起）
    LaunchedEffect(focusRunId) {
        if (focusRunId != null) {
            delay(2_000)
            focusRunId = null
        }
    }

    PageScaffold(
        title = "报告",
        // 页头恒显徽标：pill mute「最近 50 份 · 倒序」
        // ——全 50 屏页头徽标语言（MAX_REPORT_CARDS 数据驱动）
        actions = {
            PillLabel(
                text = "最近 $MAX_REPORT_CARDS 份 · 倒序",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        val loaded = reports
        if (loaded == null || loaded.isEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.space12),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = Dimens.space24),
            ) {
                if (loaded == null) {
                    // 拉取中给 spinner + 文案（loading-states——反馈匹配等待预期）
                    CircularProgressIndicator(modifier = Modifier.size(Dimens.iconHero))
                }
                Text(
                    if (loaded == null) "正在加载…" else "暂无报告——在工作台跑一次研判后，报告出现在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 空态引导：无 run 可回看——null 保持「只切页」语义
                OutlinedButton(onClick = { onGoWorkbench(null) }) { Text("去工作台") }
            }
            return@PageScaffold
        }

        // 卡列表 LazyColumn（原 Column+verticalScroll 收编——聚焦定位需按卡下标
        // scrollToItem，滚动容器即本 LazyColumn 不另起容器；key = run.id 防聚焦
        // 重排后状态错卡）。拉取完成前不组合（上方空态分支已承接）。
        val loadedList = loaded
        val listState = rememberLazyListState()
        // 聚焦定位：点名卡滚动到视口（目标卡可能折叠在视口下方——palette 点名
        // 不做二次肉眼定位；即时 scrollToItem 滚动零动画配方）
        LaunchedEffect(focusRunId, loadedList) {
            val id = focusRunId ?: return@LaunchedEffect
            val index = loadedList.indexOfFirst { it.id == id }
            if (index >= 0) listState.scrollToItem(index)
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(loadedList, key = { it.id }) { run ->
                ReportCard(
                    run = run,
                    focused = run.id == focusRunId,
                    onGoWorkbench = onGoWorkbench,
                )
            }
            // 第 51 份起不再静默消失——列表尾补截断说明行（labelSmall onSurfaceFaint）
            if (totalCount > MAX_REPORT_CARDS) {
                item(key = "truncation-notice") {
                    Text(
                        truncationNotice(totalCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                        modifier = Modifier.padding(vertical = Dimens.space4),
                    )
                }
            }
        }
    }
}

/**
 * 报告卡（最近 run 产物）。
 *
 * 整卡点击 = 展开/收起正文只读区；展开态
 * [rememberSaveable] 随卡重组保持。
 *
 * 聚焦深链（[focused]）：palette 未读产物行点名本卡时首组即展开（一次性闩——
 * 洗底 2s 退场不回折收起）+ 卡面微洗底高亮。
 *
 * 信息层级（chead/kv 结构）：
 * 卡头行 = 状态 pill（四态色对）+ run 标题·绝对时间 + 时间 pill（mute 档）；
 * kv 行 = 文件名·大小·类型（labelSmall onSurfaceVariant）。
 *
 * 动作钮（主次分层 + 顺序 + 文案）：
 * - pri 主钮「查看报告」带图标 = openFile 打开本体（失败降级 openInFolder）；
 * - gh 次钮「去工作台回看分析记录」（与工作台侧译名联动）= runId 深链；
 * - gh 次钮「打开所在目录」= openInFolder 弱动作独立钮。
 */
@Composable
private fun ReportCard(
    run: RunStore.RunSummary,
    focused: Boolean,
    onGoWorkbench: (runId: String?) -> Unit,
) {
    val path = run.reportPath ?: return
    // 折叠/展开态（卡面 acceptance：随卡重组保持——rememberSaveable 承载）
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    // 正文只读区（null = 未拉取；三态由 body 区自渲染——loading/body/error 文案）
    var body by remember(run.id) { mutableStateOf<String?>(null) }
    var bodyError by remember(run.id) { mutableStateOf<String?>(null) }
    // 聚焦深链：palette 点名本卡即展开（一次性闩——洗底自清后 expanded 保持）
    LaunchedEffect(focused) {
        if (focused) expanded = true
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        TonalCard {
        // 卡头行：状态 pill + run 标题·绝对时间 + 时间 pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            modifier = Modifier
                .fillMaxWidth()
                // 整卡点击 = 展开/收起（openInFolder 归「查看报告」钮）
                .clickable { expanded = !expanded },
        ) {
            val (pillContainer, pillContent) = statusPillColors(run.status)
            PillLabel(
                text = statusText(run.status),
                container = pillContainer,
                contentColor = pillContent,
            )
            Text(
                run.title + " · " + formatTime(run.updatedAt),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            PillLabel(
                text = formatRelativeTime(run.updatedAt),
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // kv 行：文件名·大小·类型（.kv 三段结构）
        Text(
            text = runKvLine(path),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = Dimens.space2),
        )
        // 按钮行：pri 主钮带图标 + gh 次钮×2（.btnrow 三钮序）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space8),
        ) {
            // pri 主钮：打开报告文件本体（系统默认程序——openFile 失败
            // JLog + 降级 openInFolder，无关联环境不全哑）
            AppButton(
                label = "查看报告",
                busy = false,
                onClick = { DesktopUtils.openFile(path) },
                leadingIcon = MaterialSymbols.DESCRIPTION,
            )
            // gh 次钮：带 runId 深链——宿主装配点据此 offer 回看 + 切页
            AppButton(
                label = "去工作台回看分析记录",
                busy = false,
                kind = AppButtonKind.Secondary,
                onClick = { onGoWorkbench(run.id) },
            )
            // gh 次钮：文件管理器诉求独立弱动作
            AppButton(
                label = "打开所在目录",
                busy = false,
                kind = AppButtonKind.Secondary,
                onClick = { DesktopUtils.openInFolder(path) },
            )
        }

        // ── 正文只读区（markdown 源文本态——mono 纯文本渲染，不做 md 解析）──
        if (expanded) {
            // 惰性拉取：首次展开才读文件（runCatching 失败显错误文案不静默）
            LaunchedEffect(path) {
                if (body == null && bodyError == null) {
                    val loaded = readReportBody(path)
                    if (loaded != null) body = loaded else bodyError = "正文读取失败——可经「查看」用系统默认程序打开，或「打开所在目录」用文件管理器查看"
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = Dimens.consoleHeightMax)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (val content = body) {
                    null -> if (bodyError != null) {
                        Text(
                            bodyError.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "正在读取报告正文…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Text(
                        content,
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        }
        // 聚焦微洗底（覆盖层——无 pointer 输入不拦点击，卡交互零遮挡；
        // 形状随卡 AppShapes.large 同源裁角）
        if (focused) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(AppShapes.large)
                    .background(MaterialTheme.colorScheme.primary.copy(AppAlpha.selectedWash)),
            )
        }
    }
}

// ── 纯函数（同包直测缝——NavTest 侧新用例钉死）──

/** 报告卡列表上限（占位第一版：最近 N 件——超量滚动承载，不做分页） */
internal const val MAX_REPORT_CARDS = 50

/**
 * 截断说明行文案（报告量 >[MAX_REPORT_CARDS] 时列表尾呈现——第 51 份起
 * 不再静默消失；附真实总量，旧报告经工作台分析历史回看）。
 *
 * 「分析历史」译名与工作台侧
 * 单源联动（LogWorkbenchPage RunHistoryList 标题同名），防两页两名。
 */
internal fun truncationNotice(totalCount: Int): String =
    "仅显示最近 $MAX_REPORT_CARDS 份（共 $totalCount 份）——旧报告经工作台分析历史回看"

/**
 * 报告卡 kv 行文案（`.kv` 结构：
 * 「文件名 · 大小 · 类型」三段）。文件缺失/大小读取失败时只出文件名段，不哑。
 */
internal fun runKvLine(path: String): String {
    val file = File(path)
    val name = pathFileName(path)
    val size = runCatching { file.length() }.getOrNull()
    return if (size != null && size > 0) {
        val kb = size / 1024.0
        val sizeText = if (kb >= 1024) {
            String.format(Locale.ROOT, "%.1f MB", kb / 1024)
        } else {
            String.format(Locale.ROOT, "%.1f KB", kb)
        }
        "$name · $sizeText · 研判"
    } else {
        name
    }
}

/**
 * 报告正文只读拉取（WorkbenchViewModel.readReportFile 同款三行配方复刻——
 * feature-logworkbench 私有方法跨模块不可见，composeApp 侧不引用；suspend 供
 * Composable LaunchedEffect 页面作用域消费，无新 VM）。
 */
internal suspend fun readReportBody(path: String): String? =
    withContext(Dispatchers.IO) {
        runCatching { File(path).readText() }.getOrNull()
    }

/** 时间显示（绝对时刻——正文展开区截断说明等需要精确时间的消费点保留） */
internal fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

/**
 * 相对时间显示（「3 小时前」相对语言——meta pill 同款口径；
 * <1min「刚刚」/ <60min「N 分钟前」/ <24h「N 小时前」/ <7d「N 天前」/ 其余回落绝对日期）。
 */
internal fun formatRelativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val delta = nowMillis - epochMillis
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        delta < minute -> "刚刚"
        delta < hour -> "${delta / minute} 分钟前"
        delta < day -> "${delta / hour} 小时前"
        delta < 7 * day -> "${delta / day} 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMillis))
    }
}

/** run 状态显示名（单源委托 RunStore.RunStatus.label——与 LogWorkbench 左栏列表口径一致） */
internal fun statusText(status: RunStore.RunStatus): String = RunStore.RunStatus.label(status)

/**
 * run 状态 pill 取色对：四态语义映射——
 * RUNNING=primary / COMPLETED=success / FAILED=error / ABORTED=onSurfaceVariant。
 *
 * pill 状态色以 container/content 色对呈现：
 * - RUNNING：primary×selectedWash 微洗底（selected 档——「进行中」进行时态，
 *   ConfigFieldRenderer「已改 N」徽标/CockpitPanel 剩余秒非超时态同配方）；
 * - COMPLETED：successContainer×success（语义 container 直出）；
 * - FAILED：errorContainer×error（语义 container 直出）；
 * - ABORTED：surfaceDeep×onSurfaceVariant（mute 档——用户主动中止非异常，弱化呈现）。
 *
 * 四态色对为本页私有 when——Color 是 @Composable MaterialTheme 取值不可下沉
 *    :core 常量；本函数是 run 状态取色的唯一消费点（PageReportTest 全分支锚钉死）。
 *    success 走 [LocalEasyToolboxColors] 语义对（EasyToolboxColors.success——亮暗两态
 *    装配值背书可辨）。
 */
@Composable
private fun statusPillColors(status: RunStore.RunStatus): Pair<Color, Color> = when (status) {
    RunStore.RunStatus.RUNNING -> MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.selectedWash) to
        MaterialTheme.colorScheme.primary
    RunStore.RunStatus.COMPLETED -> LocalEasyToolboxColors.current.successContainer to
        LocalEasyToolboxColors.current.success
    RunStore.RunStatus.ABORTED -> LocalToolboxSurfaceColors.current.surfaceDeep to
        MaterialTheme.colorScheme.onSurfaceVariant
    RunStore.RunStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to
        MaterialTheme.colorScheme.error
}
