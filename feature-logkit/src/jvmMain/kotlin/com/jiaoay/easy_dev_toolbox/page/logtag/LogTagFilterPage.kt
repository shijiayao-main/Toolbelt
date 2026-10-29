package com.jiaoay.easy_dev_toolbox.page.logtag

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.task.FileDropZoneSpec
import com.jiaoay.easy_dev_toolbox.widget.task.TaskPageTemplate

/**
 * Log Tag 过滤页（骨架收敛至 TaskPageTemplate）：参数区 = Tag 正则 +
 * 输出标记双输入框（paramsSlot）；
 * inputError 提示与任务列表由模板统一承载。
 *
 * [embedded] 参数——「工具」聚合页页内子签复用无头形态。
 *
 * 正则框即时校验——regexValid false 且非空时 isError +
 * supportingText「正则语法有误」（非法正则未拖入即可见，拖入拦截文案走模板
 * inputError 横幅）。OutlinedTextField 原生 supportingText 槽，零新组件。
 *
 * 成功卡「深度分析」情境动作——过滤产物（.txt）恰是
 * 工作台分析主料，经 [DeepAnalyzeHook] 中立槽投递跳工作台预填（XLog 页同款
 * 配方；宿主 AppViewModel init 恒接线，feature 互不依赖铁律下本页只触槽）。
 *
 * 正则命中预演区（参数区底部，[LogTagFilterViewModel.regexPreview] 非空时出现）：
 * 对最近拖入文件的前 [LogTagFilterViewModel.PREVIEW_MAX_LINES] 行即时扫描出命中块数
 * 与首个命中块——正则改了立刻见效。预演仅前 N 行非全量（该语义同时写进
 * 预演区标题文案，防把预演计数误读为全文件结果）；预演与全量跑任务卡共存不互斥。
 */
@Composable
fun LogTagFilterPage(embedded: Boolean = false) {
    val viewModel = LogTagFilterViewModel.getViewModel()
    val tagRegex by viewModel.tagRegex.collectAsState()
    val tagName by viewModel.tagName.collectAsState()
    val regexValid by viewModel.regexValid.collectAsState()
    val regexErrorReason by viewModel.regexErrorReason.collectAsState()

    TaskPageTemplate(
        title = "Tag 过滤",
        viewModel = viewModel,
        taskTitle = "过滤任务",
        emptyHint = "还没有过滤任务", // 页别空态文案
        embedded = embedded,
        // 成功卡「深度分析」→ 工作台预填（XLog 页同款配方）
        onDeepAnalyze = { outputPath ->
            DeepAnalyzeHook.offer(listOf(outputPath), "Tag 过滤产物深度分析")
        },
        paramsSlot = {
            // Tag regex input
            OutlinedTextField(
                value = tagRegex,
                onValueChange = viewModel::onTagRegexChange,
                label = { Text("Tag 正则") },
                placeholder = { Text("输入 tag 正则表达式，例如: MyTag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = DesktopTypography.monoInput,
                isError = !regexValid && tagRegex.isNotBlank(),
                // when 分支体双层大括号：外层 = 分支 block、内层 = Composable lambda
                // （M3 supportingText: @Composable (() -> Unit)?——PaletteParamForm 同款）
                supportingText = when {
                    !regexValid && tagRegex.isNotBlank() -> {
                        // isError 态 hint 整句（错误四要素）：补修正指引段——
                        // 拖入拦截 inputError（VM validate 已含）与本槽并存不互斥
                        { Text("Tag 正则语法有误：$regexErrorReason · 修正正则后重新拖入（已输入内容保留）") }
                    }
                    else -> null
                },
            )

            // 输出标记 input（实际用途是输出文件命名标记，与「Tag 正则」并排
            // 不再歧义；三面同词：页面/命令面 LogKitContributions/校验面 LogTagFilterViewModel）
            OutlinedTextField(
                value = tagName,
                onValueChange = viewModel::onTagNameChange,
                label = { Text("输出标记") },
                placeholder = { Text("用于输出文件命名，如 MyTag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 正则命中预演区：锚点文件（最近拖入批首文件）在位且正则合法时出现。
            // 标题恒带「仅前 N 行非全量」语义——预演计数不是全文件结果
            RegexPreviewSection(viewModel)

            // 分布统计区：「看分布」对锚点文件全文件统计（Tag 频次/级别/时间跨度），
            // 写正则前先看分布再决定过滤哪个 Tag；手动触发（全量 IO 不自动跑）。
            // Tag 频次行点击回填正则输入框——「看分布 → 决定过滤 → 手抄 Tag」闭环收口
            StatsOverviewSection(viewModel)
        },
        dropZone = FileDropZoneSpec(
            icon = MaterialSymbols.FILE_UPLOAD,
            title = "拖入或点击选择 .txt 日志文件（支持多选与目录）",
            dragTitle = "松开以开始过滤",
            desc = "支持同时拖入多个文件，或拖入目录枚举内部 .txt 日志",
            accepts = { it.extension.equals("txt", ignoreCase = true) },   // .TXT 等大写扩展名不拒
            // 目录一等输入：解密产物/解压产物目录整目录拖入即批量入任务
            acceptsDirectories = true,
        ),
    )
}

/**
 * 正则命中预演结果区（保守默认样式，TonalCard 既有令牌配方）。
 *
 * 标题恒带「仅前 N 行，非全量」语义——预演计数仅基于锚点文件前
 * [LogTagFilterViewModel.PREVIEW_MAX_LINES] 行的完整块（与 [TagRegexPreviewState] KDoc
 * 双写同一契约），截断尾块不参与命中统计、在摘要行如实呈现为「已跳过」。预演与
 * 全量跑任务卡共存不互斥；锚点文件缺席或正则非法/为空时本区整体不出现。
 */
@Composable
private fun RegexPreviewSection(viewModel: LogTagFilterViewModel) {
    // collectAsState 委托属性不可 smart cast，先落局部再判空
    val previewState = viewModel.regexPreview.collectAsState().value ?: return

    TonalCard {
        Text(
            "正则预演（仅前 ${LogTagFilterViewModel.PREVIEW_MAX_LINES} 行，非全量）",
            style = MaterialTheme.typography.labelMedium,
        )
        val summary = buildString {
            append("「${previewState.fileName}」命中 ${previewState.matchedBlocks} / ${previewState.totalBlocks} 个完整块")
            if (previewState.skippedTailBlocks > 0) append(" · 截断尾块已跳过 ${previewState.skippedTailBlocks} 个")
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (previewState.fallbackMode) {
            Text(
                "此文件没有标准时间戳行首——已按空行分段切分，上方「块」为段落口径",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (previewState.firstMatchedBlockPreview.isNotEmpty()) {
            val totalLines = previewState.firstMatchedBlockLineCount
            val shownLines = previewState.firstMatchedBlockPreview.size
            Text(
                if (totalLines > shownLines) "首个命中块（共 $totalLines 行，预览前 $shownLines 行）"
                else "首个命中块（共 $totalLines 行）",
                style = MaterialTheme.typography.labelMedium,
            )
            previewState.firstMatchedBlockPreview.forEach { line ->
                Text(
                    line,
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 日志分布统计区：「看分布」按钮（[AppButton] Secondary + busy 态承载进行中反馈）
 * 手动触发对锚点文件的全文件统计——写正则前先看 Tag/级别/时间分布，免盲试。
 *
 * 与预演（仅前 N 行）相对，统计是全文件口径，面板标题写明「全文件」；锚点文件
 * 缺席时触发置 Failed 给可操作提示（不静默）。视觉层保守默认，待真机手感终裁。
 */
@Composable
private fun StatsOverviewSection(viewModel: LogTagFilterViewModel) {
    val stats = viewModel.statsState.collectAsState().value
    val exportedStatsPath by viewModel.exportedStatsPath.collectAsState()
    // 统计外发就绪态（:core 中立槽——桥接宿主接线后随运行态发布；未接线/未运行
    // 恒 false，置灰文案由 statsLarkSendDisabledReason 承担）
    val larkDeliveryReady = ReportDelivery.ready.collectAsState().value

    AppButton(
        label = "看分布",
        busy = stats is LogTagStatsState.Running,
        busyLabel = "统计中…",
        kind = AppButtonKind.Secondary,
        onClick = viewModel::requestStats,
    )
    when (stats) {
        is LogTagStatsState.Ready -> StatsOverviewPanel(
            stats,
            exportedStatsPath,
            larkDeliveryReady,
            onTagClick = viewModel::backfillTagRegex,
            onExport = viewModel::exportStats,
            onSendToLark = viewModel::sendStatsToLark,
        )
        is LogTagStatsState.Failed -> Text(
            stats.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> {}
    }
}

/**
 * 统计「送飞书」置灰判据（纯函数：null = 可发送；非 null = 置灰原因——按钮
 * disabledReason 直接消费，禁静默置灰）。面板仅 Ready 态组合，统计未生成时
 * 按钮不渲染——本判据只剩桥接运行态一闸。
 */
internal fun statsLarkSendDisabledReason(larkDeliveryReady: Boolean): String? =
    if (larkDeliveryReady) null else "飞书桥接未运行，请先在飞书桥接页启动桥接"

/**
 * 统计结果面板（保守默认样式，TonalCard 既有令牌配方；与预演区同款排版）。
 *
 * Tag 频次行可点——点击经 [onTagClick] 回填 Tag 正则输入框（转义 + 覆盖语义见
 * [LogTagFilterViewModel.backfillTagRegex] KDoc）。可点 affordance 保守默认 =
 * primary 色整行命中区（clickable 自带 ripple），视觉层待真机手感终裁。
 *
 * 「导出 Markdown」把面板所呈快照落为可分享交付物（[onExport]——VM 持同一
 * [LogTagStatsState.Ready] 快照，导出与面板同源单数据）；「送飞书」在导出之上
 * 直发桥接接收会话（[onSendToLark]——发送前恒重新导出即恒最新快照，置灰判据 =
 * [statsLarkSendDisabledReason]）。产物行回显最近一次导出路径（可拖选 + 复制钮
 * ——日志工作台报告面板产物行同款配方）。
 */
@Composable
private fun StatsOverviewPanel(
    stats: LogTagStatsState.Ready,
    exportedStatsPath: String?,
    larkDeliveryReady: Boolean,
    onTagClick: (String) -> Unit,
    onExport: () -> Unit,
    onSendToLark: () -> Unit,
) {
    TonalCard {
        Text("分布概览（全文件）", style = MaterialTheme.typography.labelMedium)
        Text(
            "「${stats.fileName}」共 ${stats.totalBlocks} 块",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 时间跨度：两文本缺席（全文无时间行）如实呈现不可得，不猜零值
        if (stats.firstTimestampText != null && stats.lastTimestampText != null) {
            Text(
                "跨度 ${stats.firstTimestampText} → ${stats.lastTimestampText}" +
                    "（${StatsMarkdownExporter.formatSpanMillis(stats.spanMillis)}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "未识别到行首时间戳，跨度不可得",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val levelSummary = buildString {
            append(stats.levelCounts.joinToString(" · ") { (level, count) -> "$level $count" })
            if (stats.unparsedLevelHeads > 0) append(" · 未识别 ${stats.unparsedLevelHeads}")
        }
        Text(
            "级别块数：$levelSummary",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (stats.topTags.isNotEmpty()) {
            Text(
                "Tag 频次前 ${stats.topTags.size}（共 ${stats.totalTagCount} 个）· 点击回填正则",
                style = MaterialTheme.typography.labelMedium,
            )
            stats.topTags.forEach { (tag, count) ->
                Text(
                    "$tag  $count",
                    style = DesktopTypography.mono,
                    // primary 色 = 可点 affordance（与不可点行文的 onSurfaceVariant 相对）
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTagClick(tag) },
                )
            }
            if (stats.unparsedTagHeads > 0) {
                Text(
                    "另有 ${stats.unparsedTagHeads} 块头行未识别出 Tag",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "未识别出任何 Tag（头行形态须为 E/Tag: 或 threadtime 形态）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 导出交付物：面板仅 Ready 态组合，按钮恒可用（导出失败回执走全局 toast）。
        // Hot 形态（洗底胶囊、无描边）——描边钮的边框带与文字带会被像素带守护
        // 判成间隙 ≤3px 的相邻内容带；洗底胶囊为单一连续内容带
        AppButton(
            label = "导出 Markdown",
            busy = false,
            kind = AppButtonKind.Hot,
            onClick = onExport,
        )
        // 一键送飞书（文件消息直发桥接接收会话——导出 + 发送一气呵成，发送前恒
        // 重新导出即恒最新统计快照）；置灰判据 = [statsLarkSendDisabledReason]，
        // 就绪态真源 = ReportDelivery.ready（:core 中立槽）。Hot 形态理由同上
        AppButton(
            label = "送飞书",
            busy = false,
            kind = AppButtonKind.Hot,
            onClick = onSendToLark,
            enabled = larkDeliveryReady,
            disabledReason = statsLarkSendDisabledReason(larkDeliveryReady),
        )
        // 导出产物行（可拖选路径 + 行尾复制钮——weight(1f) 吃满剩余宽、复制钮恒贴
        // 行尾；路径可见可复制 = 分享动线的可带走面）
        if (exportedStatsPath != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        exportedStatsPath,
                        style = DesktopTypography.monoTag,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CopyIconButton(
                    textProvider = { exportedStatsPath },
                    contentDescription = "复制统计导出路径",
                )
            }
        }
    }
}
