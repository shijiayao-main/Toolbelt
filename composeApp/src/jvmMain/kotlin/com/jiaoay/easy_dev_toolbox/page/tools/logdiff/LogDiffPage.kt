package com.jiaoay.easy_dev_toolbox.page.tools.logdiff

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppSwitchRow
import com.jiaoay.easy_dev_toolbox.widget.FileDropZone
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.log.LogDiff
import com.jiaoay.log.LogStatsAnalyzer
import com.jiaoay.log.LogStatsCompare
import dev.vicart.compose.material.symbols.MaterialSymbols
import java.io.File

/**
 * 日志对比页内容体（「工具」聚合页第 6 签，无头形态——宿主 [com.jiaoay.easy_dev_toolbox.page.tools.ToolsPage]
 * 自带 PageScaffold + 子签栏）。
 *
 * 结构：双文件拖入参数区（基准/对比两槽 [FileDropZone]，一次拖入两份即填满两槽）
 * → 两向模式选择器（行级/分布）→ 时间戳归一化开关（**仅行级模式可见**——归一化
 * 是行级比对键开关，对块级分布无意义）→ 「开始对比」按钮（按模式分流执行路径）
 * → 结果区按模式分流渲染。
 *
 * ## 两向模式口径
 * - 行级 = 逐行内容对比（hunk 分组 + 行级增删着色，差异行可点——跳应用内查看器）；
 * - 分布 = 基线对照「新鲜度视图」：新冒 Tag / 共有 Tag 频次增减 / 级别块数差值
 *   （E/F 单列）的高亮面，分布对照 ≠ 行级内容差异——「哪几行不一样」归行级
 *   模式，两者正交，文案不得混述；
 * - 两向结果分立存储在 VM，切模式不丢在台结果。
 *
 * ## 视觉层保守默认（待真机终裁）
 * - 删行（REMOVED）着 [androidx.compose.material3.MaterialTheme] error 色、
 *   增行（ADDED）着 primary 色——语义色对保守取值，具体色相与色强待真机手感终裁；
 * - 分布对照面板的新 Tag / 增减行全部平文呈现（无高亮浮出色、无双侧分栏），高亮
 *   浮出形态待真机终裁；
 * - 双槽横排布局（Row weight 均分）在窄窗下的观感未验证；
 * - 差异行点击 affordance（hover 提示/光标形态）未做，可点性靠按压反馈兜底；
 * - hunk 巡检钮的文案（上一处/下一处）、与摘要同行的排布、边界置灰态观感未验证。
 */
@Composable
fun LogDiffPageContent() {
    val viewModel = LogDiffViewModel.getViewModel()
    val oldFile by viewModel.oldFile.collectAsState()
    val newFile by viewModel.newFile.collectAsState()
    val normalize by viewModel.normalize.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val inputError by viewModel.inputError.collectAsState()
    val diffState by viewModel.diffState.collectAsState()
    val compareState by viewModel.compareState.collectAsState()
    val exportReceipt by viewModel.exportReceipt.collectAsState()
    // 两模式共用同一双槽门禁：任一向在跑即两槽拒收换文件（busy 由 FileDropZone 承接）
    val running = diffState is LogDiffViewModel.DiffUiState.Running ||
        compareState is LogDiffViewModel.CompareUiState.Running

    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space12)) {
        DiffSlotDropZone(
            slot = LogDiffViewModel.Slot.OLD,
            file = oldFile,
            busy = running,
            onFilesSelected = viewModel::onFilesSelected,
        )
        DiffSlotDropZone(
            slot = LogDiffViewModel.Slot.NEW,
            file = newFile,
            busy = running,
            onFilesSelected = viewModel::onFilesSelected,
        )
    }

    SegmentedControl(
        options = listOf("行级对比", "分布对照"),
        selectedIndex = if (mode == LogDiffViewModel.DiffMode.LINE) 0 else 1,
        onSelect = { index ->
            viewModel.setMode(if (index == 0) LogDiffViewModel.DiffMode.LINE else LogDiffViewModel.DiffMode.DISTRIBUTION)
        },
    )

    // 归一化开关仅行级模式可见（分布不比行——比对键开关对块级统计无意义）
    if (normalizeSwitchVisible(mode)) {
        AppSwitchRow(
            label = "时间戳归一化对齐",
            supportingText = "仅行首时间戳不同的行视为同一行（两次复现/两个版本比行为不比时钟）",
            checked = normalize,
            onCheckedChange = viewModel::setNormalize,
        )
    }

    AppButton(
        label = "开始对比",
        busy = running,
        busyLabel = "正在对比…",
        onClick = viewModel::startDiff,
        modifier = Modifier.fillMaxWidth(),
    )

    // 缺一拒收横幅（可手动关闭；重新选择文件或再发起时由 VM 侧清除）
    inputError?.let { error ->
        StatusBanner(
            status = StatusBannerStatus.Error,
            text = error,
            trailing = {
                TextButton(onClick = viewModel::dismissInputError) { Text("清除该提示") }
            },
        )
    }

    // 结果区按模式分流：两向分立存储，切模式不丢对侧在台结果
    when (mode) {
        LogDiffViewModel.DiffMode.LINE -> LineResultArea(
            diffState = diffState,
            normalize = normalize,
            exportReceipt = exportReceipt,
            viewModel = viewModel,
        )
        LogDiffViewModel.DiffMode.DISTRIBUTION -> DistributionCompareArea(compareState)
    }
}

/**
 * 归一化开关可见性判据（纯函数：仅行级模式可见——归一化是行级比对键布尔开关，
 * 块级分布对照不比行，开关在场即语义自污）。internal：WiringGuard 直测 + 源码锚。
 */
internal fun normalizeSwitchVisible(mode: LogDiffViewModel.DiffMode): Boolean =
    mode == LogDiffViewModel.DiffMode.LINE

/** 行级结果区（模式分流入口）：行级单发状态机的四态渲染 */
@Composable
private fun LineResultArea(
    diffState: LogDiffViewModel.DiffUiState,
    normalize: Boolean,
    exportReceipt: String?,
    viewModel: LogDiffViewModel,
) {
    when (diffState) {
        LogDiffViewModel.DiffUiState.Idle,
        LogDiffViewModel.DiffUiState.Running,
        -> Unit
        is LogDiffViewModel.DiffUiState.Failed ->
            StatusBanner(status = StatusBannerStatus.Error, text = "对比失败：${diffState.reason}")
        is LogDiffViewModel.DiffUiState.Done ->
            DiffResultSection(state = diffState, normalize = normalize, exportReceipt = exportReceipt, viewModel = viewModel)
    }
}

/** 分布对照结果区（模式分流入口）：分布单发状态机的四态渲染（Idle/Running 不渲染） */
@Composable
private fun DistributionCompareArea(state: LogDiffViewModel.CompareUiState) {
    when (state) {
        LogDiffViewModel.CompareUiState.Idle,
        LogDiffViewModel.CompareUiState.Running,
        -> Unit
        is LogDiffViewModel.CompareUiState.Failed ->
            StatusBanner(status = StatusBannerStatus.Error, text = "分布对照失败：${state.reason}")
        is LogDiffViewModel.CompareUiState.Done ->
            DistributionComparePanel(compare = state.compare, oldLabel = state.oldLabel, newLabel = state.newLabel)
    }
}

/** 差值带符号呈现（正 = 变多、负 = 变少；零平文呈现不猜方向）。internal：WiringGuard 直测锚 */
internal fun formatDelta(delta: Int): String = if (delta > 0) "+$delta" else delta.toString()

/**
 * 分布对照结果面板（基线对照「新鲜度视图」；StatsOverviewPanel 同款排版配方：
 * TonalCard + labelMedium 分节标题 + bodySmall 说明行 + mono 数据行，零白名单令牌）。
 *
 * 新 Tag / 增减行全部平文呈现（高亮浮出形态待真机终裁）；未识别块差值**两侧分列
 * 原样呈现、不归因**（占位 Tag 造假，口径见 [LogStatsCompare] KDoc）。
 */
@Composable
private fun DistributionComparePanel(
    compare: LogStatsCompare.CompareResult,
    oldLabel: String,
    newLabel: String,
) {
    val noChange = compare.newTags.isEmpty() &&
        compare.sharedTagDeltas.isEmpty() &&
        compare.levelDeltas.values.all { it == 0 }
    if (noChange) {
        StatusBanner(
            status = StatusBannerStatus.Success,
            text = "两份日志的 Tag/级别分布一致（基准 ${compare.baselineTotalBlocks} 块 / 对比 ${compare.currentTotalBlocks} 块）",
        )
        return
    }
    TonalCard {
        Text("分布对照（块级，基线 vs 对比）", style = MaterialTheme.typography.labelMedium)
        Text(
            "「$oldLabel」共 ${compare.baselineTotalBlocks} 块 vs 「$newLabel」共 ${compare.currentTotalBlocks} 块",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (compare.newTags.isNotEmpty()) {
            Text(
                "新增 Tag（基线未出现，${compare.newTags.size} 个，频次降序）",
                style = MaterialTheme.typography.labelMedium,
            )
            compare.newTags.forEach { (tag, count) ->
                Text(
                    "$tag  $count",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "相对基线无新增 Tag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (compare.sharedTagDeltas.isNotEmpty()) {
            Text(
                "共有 Tag 频次增减（对比 − 基线，按差值大小降序）",
                style = MaterialTheme.typography.labelMedium,
            )
            compare.sharedTagDeltas.forEach { delta ->
                Text(
                    "${delta.tag}  ${formatDelta(delta.delta)}（${delta.baselineCount} → ${delta.currentCount}）",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // E/F 增量单列：「环境变坏」的第一信号不与其余档混排行
        Text(
            "E/F 增量（对比 − 基线）：E ${formatDelta(compare.errorDelta)} · F ${formatDelta(compare.fatalDelta)}",
            style = MaterialTheme.typography.labelMedium,
        )
        val otherLevels = compare.levelDeltas.filterKeys {
            it != LogStatsAnalyzer.Level.ERROR && it != LogStatsAnalyzer.Level.FATAL
        }
        Text(
            "其余级别块数差值：" + otherLevels.entries.joinToString(" · ") { (level, delta) ->
                "${level.letter} ${formatDelta(delta)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 未识别差值两侧如实分列呈现，禁归因（未识别块无 Tag 可归属）
        Text(
            "未识别 Tag 头行：基线 ${compare.baselineUnparsedTagBlocks} 块 / 对比 ${compare.currentUnparsedTagBlocks} 块（不归因）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "未识别级别头行：基线 ${compare.baselineUnparsedLevelBlocks} 块 / 对比 ${compare.currentUnparsedLevelBlocks} 块",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 对比槽拖放区（双槽共用的参数化差异点：槽名/文案/已选回显）。
 * 已选文件回显走标题行（拖放区无独立回显槽，标题动态化最小面）。
 */
@Composable
private fun RowScope.DiffSlotDropZone(
    slot: LogDiffViewModel.Slot,
    file: File?,
    busy: Boolean,
    onFilesSelected: (LogDiffViewModel.Slot, List<File>) -> Unit,
) {
    val label = if (slot == LogDiffViewModel.Slot.OLD) "基准" else "对比"
    FileDropZone(
        title = if (file != null) "$label：${file.name}" else "拖入或点击选择${label}文件",
        dragTitle = "松开以设为${label}文件",
        desc = if (file != null) "已就位——点击可重新选择，一次拖入两份可同时填两槽"
        else if (slot == LogDiffViewModel.Slot.OLD) "对比基准（改动前）"
        else "被对比文件（改动后）",
        onFilesSelected = { files -> onFilesSelected(slot, files) },
        modifier = Modifier.weight(1f),
        icon = MaterialSymbols.DIFFERENCE,
        // multiple = true：点击路径允许多选，拖入路径双份手势即填满两槽
        multiple = true,
        accepts = DIFF_ACCEPTS,
        busy = busy,
        busyText = "对比进行中，请等待完成后再更换文件",
    )
}

/**
 * 渲染行（LazyColumn 扁平数据流）：hunk 头 + hunk 内逐行（行级着色按 [LogDiff.LineKind]）。
 * internal：行号推进语义的单测锚（[rowsOf] 直接断言双侧行号）。
 */
internal sealed interface DiffRow {
    data class Header(val text: String) : DiffRow

    /**
     * [oldLineNumber]/[newLineNumber]：该行在基准/对比**原文件**中的 1 基行号
     * （引擎 Hunk 窗口起点恒为原文件 0 基下标——归一化只变换比对键不改行号坐标系，
     * 见 [LogDiffViewModel.openDiffLineInViewer] 行号语义）。增行不在基准文件、
     * 删行不在对比文件，对应侧行号为 null。
     */
    data class Line(
        val prefix: String,
        val text: String,
        val kind: LogDiff.LineKind,
        val oldLineNumber: Int?,
        val newLineNumber: Int?,
    ) : DiffRow
}

/**
 * 对比结果区：degraded 明示横幅 + hunk 分组渲染 + hunk 巡检钮 + 导出按钮与回显。
 *
 * hunk 行集扁平化后进 LazyColumn（heightIn 有界——本页处于宿主 verticalScroll 容器内，
 * 无界 LazyColumn 直接抛异常；上界取控制台同档 token；滚动容器即本 LazyColumn，
 * 不另起容器）。hunks 为空表 = 全等，以 Success 横幅呈现不渲染空卡（hunkNav 恒
 * null，巡检钮不渲染）。
 */
@Composable
private fun DiffResultSection(
    state: LogDiffViewModel.DiffUiState.Done,
    normalize: Boolean,
    exportReceipt: String?,
    viewModel: LogDiffViewModel,
) {
    val result = state.result
    val hunkNav by viewModel.hunkNav.collectAsState()
    // diff 外发就绪态（:core 中立槽——桥接宿主接线后随运行态发布；未接线/未运行
    // 恒 false，置灰文案由 diffLarkSendDisabledReason 承担）
    val larkDeliveryReady = ReportDelivery.ready.collectAsState().value

    if (result.degraded) {
        // 引擎超对齐上限退化契约：必须明示整体替换语义 + 给出下一步指引
        val advice = if (normalize) {
            "已开启归一化仍超限，建议缩小两份文件的对比范围后重新对比"
        } else {
            "建议开启「时间戳归一化对齐」后重新对比"
        }
        StatusBanner(
            status = StatusBannerStatus.Warning,
            text = "差异超过对齐上限（${LogDiff.MAX_ALIGN_EDITS}），中间区域已整体替换展示、未逐行对齐——$advice",
        )
    }

    if (result.hunks.isEmpty()) {
        StatusBanner(
            status = StatusBannerStatus.Success,
            text = "两份文件内容一致（基准 ${result.oldLineCount} 行 / 对比 ${result.newLineCount} 行）",
        )
        return
    }

    TonalCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${result.hunks.size} 处差异 · 基准 ${result.oldLineCount} 行 / 对比 ${result.newLineCount} 行" +
                    "（${state.oldLabel} → ${state.newLabel}）",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            hunkNav?.let { nav ->
                nav.positionLabel?.let { label ->
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = viewModel::goToPrevHunk, enabled = nav.canGoPrev) {
                    Text("上一处")
                }
                TextButton(onClick = viewModel::goToNextHunk, enabled = nav.canGoNext) {
                    Text("下一处")
                }
            }
        }
        val rows = remember(result) { result.hunks.flatMap(::rowsOf) }
        val hunkFirstRows = remember(result) { hunkFirstRowIndices(result.hunks) }
        val listState = rememberLazyListState()
        // 巡检定位走即时 scrollToItem（滚动零动画配方）——定位目标 = hunk 首行在
        // 扁平行集中的下标（头行），未定位态不滚动
        LaunchedEffect(hunkNav?.index) {
            val index = hunkNav?.index ?: return@LaunchedEffect
            if (index >= 0) listState.scrollToItem(hunkFirstRows[index])
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = Dimens.consoleHeightMax),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            items(rows) { row ->
                when (row) {
                    is DiffRow.Header -> Text(
                        text = row.text,
                        style = DesktopTypography.mono,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                    )
                    is DiffRow.Line -> DiffLineText(row, onClick = { onDiffLineClick(row, viewModel) })
                }
            }
        }
        AppButton(
            label = "导出 diff 文件",
            busy = false,
            onClick = viewModel::exportDiff,
            kind = AppButtonKind.Secondary,
        )
        // 送工作台分析（导出 + 深链投递一气呵成，恒最新 diff——Tag 过滤/XLog 页
        // 产物直投同构配方；.diff 是纯文本可读产物，两份日志的差异是 AI 研判主料）
        AppButton(
            label = "送工作台分析",
            busy = false,
            onClick = viewModel::sendDiffToWorkbench,
            kind = AppButtonKind.Secondary,
        )
        // 一键送飞书（发送前恒重新导出即恒最新 diff，直发桥接接收会话）；置灰判据 =
        // [diffLarkSendDisabledReason]，就绪态真源 = ReportDelivery.ready（:core 中立槽）
        AppButton(
            label = "送飞书",
            busy = false,
            onClick = viewModel::sendDiffToLark,
            kind = AppButtonKind.Secondary,
            enabled = larkDeliveryReady,
            disabledReason = diffLarkSendDisabledReason(larkDeliveryReady),
        )
        exportReceipt?.let { receipt ->
            Text(
                text = receipt,
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * diff「送飞书」置灰判据（纯函数：null = 可发送；非 null = 置灰原因——按钮
 * disabledReason 直接消费，禁静默置灰）。结果区仅 Done 态组合（与导出钮同款
 * 渲染门），本判据只剩桥接运行态一闸。
 */
internal fun diffLarkSendDisabledReason(larkDeliveryReady: Boolean): String? =
    if (larkDeliveryReady) null else "飞书桥接未运行，请先在飞书桥接页启动桥接"

/**
 * 行级着色保守默认：删除 = error、新增 = primary、上下文 = onSurfaceVariant（真机终裁面）。
 * 整行命中区可点（clickable 自带按压反馈）——点击跳应用内查看器看该行前后文，
 * 行级点击 affordance（hover 提示等）待真机终裁。
 */
@Composable
private fun DiffLineText(row: DiffRow.Line, onClick: () -> Unit) {
    val color = when (row.kind) {
        LogDiff.LineKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
        LogDiff.LineKind.REMOVED -> MaterialTheme.colorScheme.error
        LogDiff.LineKind.ADDED -> MaterialTheme.colorScheme.primary
    }
    Text(
        text = row.prefix + row.text,
        style = DesktopTypography.mono,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

/**
 * 差异行点击 → 查看器跳转的侧别解析：删行与上下文行跳**基准侧**、增行跳**对比侧**
 * （唯一性原则：跳到该行渲染原文真正所在的文件——引擎对上下文行恒取基准侧原文）。
 */
private fun onDiffLineClick(row: DiffRow.Line, viewModel: LogDiffViewModel) {
    val oldLine = row.oldLineNumber
    if (oldLine != null) {
        viewModel.openDiffLineInViewer(LogDiffViewModel.Slot.OLD, oldLine)
    } else {
        row.newLineNumber?.let { viewModel.openDiffLineInViewer(LogDiffViewModel.Slot.NEW, it) }
    }
}

/**
 * 单 hunk → 渲染行集（unified 头 + 逐行，行前缀与导出产物同源对齐）。
 * 行号推进：自窗口 0 基起点起，上下文行双侧行号同增、删行只增基准侧、增行只增
 * 对比侧（unified 双坐标推进语义）。internal：双侧行号推进的单测锚。
 */
internal fun rowsOf(hunk: LogDiff.Hunk): List<DiffRow> = buildList {
    add(
        DiffRow.Header(
            "@@ -${hunk.oldStart + 1},${hunk.oldLines} +${hunk.newStart + 1},${hunk.newLines} @@",
        ),
    )
    var oldIndex = hunk.oldStart
    var newIndex = hunk.newStart
    for (line in hunk.lines) {
        val prefix = when (line.kind) {
            LogDiff.LineKind.CONTEXT -> " "
            LogDiff.LineKind.REMOVED -> "-"
            LogDiff.LineKind.ADDED -> "+"
        }
        add(
            DiffRow.Line(
                prefix = prefix,
                text = line.text,
                kind = line.kind,
                oldLineNumber = if (line.kind == LogDiff.LineKind.ADDED) null else oldIndex + 1,
                newLineNumber = if (line.kind == LogDiff.LineKind.REMOVED) null else newIndex + 1,
            ),
        )
        when (line.kind) {
            LogDiff.LineKind.CONTEXT -> {
                oldIndex++
                newIndex++
            }
            LogDiff.LineKind.REMOVED -> oldIndex++
            LogDiff.LineKind.ADDED -> newIndex++
        }
    }
}

/**
 * hunk 首行在 [rowsOf] 扁平行集中的下标表——巡检定位（scrollToItem）的目标锚。
 * 每个 hunk 恒展成「1 行头 + hunk.lines.size 行」，故 hunk i 的首行下标 = 前序
 * hunk 展开行数的累计和；返回表与入参 hunks 逐项对应。internal：扁平映射规律的
 * 单测锚（规律漂移即 [hunkFirstRowIndices] 与扁平行集错位、巡检滚到错行）。
 */
internal fun hunkFirstRowIndices(hunks: List<LogDiff.Hunk>): List<Int> = buildList {
    var index = 0
    for (hunk in hunks) {
        add(index)
        index += rowsOf(hunk).size
    }
}

/** 接收面：解密产物 txt + 原生 log 后缀（其余类型拖放区自带拒绝态回执） */
private val DIFF_ACCEPTS: (File) -> Boolean = { file ->
    file.extension.equals("txt", ignoreCase = true) ||
        file.extension.equals("log", ignoreCase = true)
}
