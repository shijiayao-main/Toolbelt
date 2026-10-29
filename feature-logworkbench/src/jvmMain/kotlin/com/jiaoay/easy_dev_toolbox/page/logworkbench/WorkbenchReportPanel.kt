package com.jiaoay.easy_dev_toolbox.page.logworkbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.evidence.EvidenceCitationCheck
import com.jiaoay.easy_dev_toolbox.core.evidence.EvidenceRef
import com.jiaoay.easy_dev_toolbox.core.evidence.ReportSections
import com.jiaoay.easy_dev_toolbox.core.evidence.evidenceSnippetOf
import com.jiaoay.easy_dev_toolbox.core.evidence.findTimestampLineIn
import com.jiaoay.easy_dev_toolbox.core.evidence.parseReport
import com.jiaoay.easy_dev_toolbox.core.evidence.validateEvidenceCitation
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppDialogDefaults
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.GhostChipButton
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.appDialogRim
import com.jiaoay.easy_dev_toolbox.widget.evidence.MarkdownTableBlock
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor
import java.io.File

/**
 * 工作台右栏报告面板渲染簇（[ReportPanel] + 假设三态件族 + 增量卡）：
 * 纯渲染窄接口——全部状态经参数注入（页面态 activeEvidence/highlightTimestamp
 * 留在 LogWorkbenchPage，本簇零页面态共享）；引用回读与校验的文件 IO 经
 * LaunchedEffect withContext(IO) 承担（parseReport/validateEvidenceCitation 纯函数无文件访问）。
 *
 * 引用两态 chip：带行区间引用且硬门禁校验 Verified 才可点（直达查看器行号档）；
 * 校验失败/未验/回读在途降级为现状时间戳行 + faint「行号未验」注记；旧格式引用
 * （无标记字段）纯现状——连注记都不出。视觉保守默认（chip 微洗底 + 既有排版令牌），
 * 视觉层待真机终裁。
 */

// ── 右栏：报告面板（因果链三段卡 + 三态徽章 + 依据引用 + 追问） ──

@Composable
internal fun ReportPanel(
    reportText: String?,
    running: Boolean,
    aiEnabled: Boolean,
    /** 证据产物文件集（引用摘要回读的查找域） */
    evidenceFiles: List<String>,
    /** 运行期报告产物落点（生成完成为 null——「打开报告文件」钮接管指路） */
    reportPathDuringRun: String?,
    /** 导出产物路径回显（null = 本会话尚未导出——产物行不渲染） */
    exportedReportPath: String?,
    /**
     * 报告文件读取失败态（回看第三态）：null = 无；非 null = 读失败的 reportPath——
     * 右栏渲染错误态给路径出路，不折叠成「尚无报告」空态（run 记录仍在）。
     */
    reportReadErrorPath: String?,
    /** 报告外发通道就绪态（桥接运行态镜像；false ⇒「送飞书」置灰并明示原因） */
    larkDeliveryReady: Boolean,
    /** 导出脱敏开关态（关 = 现状逐字保真；开 = 外发产物内容层同值恒同替） */
    anonymizeExport: Boolean,
    /** 脱敏开关回写（checkbox 勾选态回传 VM——导出与送飞书共用） */
    onAnonymizeExportChange: (Boolean) -> Unit,
    onFollowUp: (String) -> Unit,
    onOpenReport: () -> Unit,
    /** 导出当前报告为可分享 Markdown（用户交付物落 `~/Documents/Toolbelt/reports/`） */
    onExportReport: () -> Unit,
    /** 一键送飞书：导出（恒最新报告文本）→ 文件消息直发桥接接收会话 */
    onSendToLark: () -> Unit,
    /** 根因存案：打开存案编辑对话框（四要素预填——落库后供 knowledge.search_cases 检索复用） */
    onArchiveCase: () -> Unit,
    /** 打开导出产物所在文件夹（文件管理器——拖拽分享的物理出口） */
    onOpenExportFolder: () -> Unit,
    /** 打开读取失败的报告文件所在文件夹（核查/找回出路） */
    onOpenErrorFolder: () -> Unit,
    onJumpToTimestamp: (String) -> Unit,
    /**
     * 证据 chip 直达查看器（仅 Verified 引用可点——硬门禁四态里唯一放行态）：
     * fileName = 引用标记的裸产物文件名（页面侧对会话产物集精确匹配解析成路径），
     * startLine = 引用行区间起（1 基，查看器窗口分页到该行为窗口首行）。
     */
    onCiteToViewer: (fileName: String, startLine: Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var followUpText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        Text("分析报告", style = MaterialTheme.typography.titleSmall)
        if (reportText == null) {
            when {
                running -> {
                    Text(
                        // 生成期指路：产物落点可预期（旧文案「报告生成中…」零路径信息，
                        // 「打开报告文件」钮在生成期不存在，路径须由本行自足给出）
                        "报告生成中，完整产物将落盘至：" + (reportPathDuringRun ?: "（路径待定）"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 回看第三态：reportPath 在而文件读取失败——错误态给路径出路
                //（PageReport bodyError 文案口径：error 色正文 + 打开所在目录动作；
                // 不折叠成「尚无报告」空态误导重跑）
                reportReadErrorPath != null -> {
                    Text(
                        "报告文件读取失败（run 记录仍在）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "原文件：$reportReadErrorPath",
                        style = DesktopTypography.monoTag,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GhostChipButton(label = "打开所在目录", onClick = onOpenErrorFolder)
                }
                else -> {
                    EmptyStateView(
                        text = "尚无报告",
                        learningHint = "完成一次分析后，五部分结构报告在此呈现；可对报告继续追问",
                        fillParent = false,
                    )
                }
            }
            // 空报告态不再渲染追问框——无报告可追问，追问框
            // 在空态可输入可点击但静默无效（VM PENDING_RUN_ID 直接 return），
            // 引导语义并入上方 learningHint。
            return@Column
        }

        val sections = remember(reportText) { parseReport(reportText) }

        // 引用回读 + 硬门禁校验——文件 IO 经 LaunchedEffect withContext(IO) 承担
        //（parseReport/validateEvidenceCitation/evidenceSnippetOf 纯函数无文件访问，
        // linesOf 行读取由回读处注入）。回读后拼装 tag/snippet + 校验四态；
        // 找不到回退纯时间戳行（现状语义）。回读完成前引用按「未验」降级呈现
        //（永不提前给 chip——行号漂移防线的保守侧）。
        var evidenceViews by remember(reportText) {
            mutableStateOf(sections.evidenceRefs.map { EvidenceRefView(ref = it, check = null) })
        }
        LaunchedEffect(sections, evidenceFiles) {
            if (sections.evidenceRefs.isEmpty()) return@LaunchedEffect
            evidenceViews = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sections.evidenceRefs.map { ref -> readEvidenceRef(ref, evidenceFiles) }
            }
        }

        // 结论一句话
        if (sections.conclusion.isNotBlank()) {
            Text(sections.conclusion, style = MaterialTheme.typography.bodyMedium)
        }

        // 因果链三段卡（TonalCard——根因→直接故障点→影响面）
        if (sections.chain.isNotEmpty()) {
            TonalCard {
                sections.chain.forEachIndexed { index, (label, text) ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (index != sections.chain.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = Dimens.space4))
                    }
                }
            }
        }

        // 假设三态徽章（✓ 已确认 / ? 怀疑点 / ✗ 已排除——失败假设不抹除）
        HypothesisBadges(sections = sections)

        // 三态列表（已确认/怀疑点/已排除逐条呈现）
        HypothesisList(sections = sections)

        // 追问增量段成卡（`## 增量研判（追问）` 每段一卡，
        // 序号 = 追问次序；徽章/三态/因果链保持主报告口径不动，增量不进主结论）
        sections.increments.forEachIndexed { index, increment ->
            IncrementCard(index = index, text = increment)
        }

        // 关键依据引用（每条两行——时间戳行（两态：Verified = 可点 chip 直达查看器；
        // 其余 = mono primary 可点跳中栏现状语义）+ TAG/snippet 摘要（labelSmall
        // onSurfaceVariant——点击行为仅时间戳行/chip 承担））
        if (evidenceViews.isNotEmpty()) {
            Text("关键依据", style = MaterialTheme.typography.titleSmall)
            // 整块 border-left 2px hairline + padding-left 10px（左缘参考线）
            Column(
                modifier = Modifier
                    .let { m ->
                        val lineColor = LocalToolboxSurfaceColors.current.borderHairline
                        m.drawBehind {
                            drawLine(
                                color = lineColor,
                                start = androidx.compose.ui.geometry.Offset(
                                    Dimens.glassStrokeHairline.toPx() / 2,
                                    0f,
                                ),
                                end = androidx.compose.ui.geometry.Offset(
                                    Dimens.glassStrokeHairline.toPx() / 2,
                                    size.height,
                                ),
                                strokeWidth = Dimens.strokeThin.toPx(),
                            )
                        }
                    }
                    .padding(start = Dimens.space10),
            ) {
                evidenceViews.forEach { view ->
                    val ref = view.ref
                    // 两态判定走纯函数单一决策源：Verified 才产 chip 目标（直达查看器），
                    // 失败/未验/回读在途恒 null（行号漂移防线——校验不过永不 offer 行号跳转）
                    val chipTarget = evidenceChipTargetOf(ref, view.check)
                    if (chipTarget != null) {
                        val (citeFile, citeLine) = chipTarget
                        // hover 洗底（chip 两态同配方：selectionHoverWashColor 叠加于
                        // 常驻 selectedWash 微洗底之上）+ indication=null
                        val citeSource = remember { MutableInteractionSource() }
                        val citeHovered by citeSource.collectIsHoveredAsState()
                        Text(
                            ref.timestamp,
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = citeSource,
                                    indication = null,
                                ) { onCiteToViewer(citeFile, citeLine) }
                                // 微洗底 chip（FileBufferRow chip 同款配方——primary selectedWash）
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(AppAlpha.selectedWash),
                                    AppShapes.small,
                                )
                                .background(selectionHoverWashColor(hovered = citeHovered), AppShapes.small)
                                .padding(horizontal = Dimens.space8, vertical = Dimens.space2),
                        )
                    } else {
                        // 未验时间戳行：hover 洗底 + indication=null（裸可点零反馈修复）
                        val jumpSource = remember { MutableInteractionSource() }
                        val jumpHovered by jumpSource.collectIsHoveredAsState()
                        Text(
                            ref.timestamp,
                            style = DesktopTypography.mono,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(selectionHoverWashColor(hovered = jumpHovered), AppShapes.small)
                                .clickable(
                                    interactionSource = jumpSource,
                                    indication = null,
                                ) { onJumpToTimestamp(ref.timestamp) }
                                .padding(vertical = Dimens.space2),
                        )
                        // 带区间但未验/失败（含回读在途）→ faint 注记；旧格式 ref
                        // 无区间字段连注记都不出（纯现状零注记）
                        if (ref.fileName != null && ref.startLine != null) {
                            Text(
                                "行号未验",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                            )
                        }
                    }
                    val summary = listOfNotNull(ref.tag, ref.snippet).joinToString("：")
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.labelSmall,
                            // 摘要行 faint 档（hint 语义）
                            color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                        )
                    }
                }
            }
        }

        // 完整时间线表格（`|` 管道符行结构化渲染，行时间戳
        // 点击复用 onJumpToTimestamp 跳转链；独立块不参与 600 字截断）
        if (sections.timelineRows.isNotEmpty()) {
            Text("完整时间线", style = MaterialTheme.typography.titleSmall)
            MarkdownTableBlock(
                rows = sections.timelineRows,
                onJumpToTimestamp = onJumpToTimestamp,
            )
        }

        // 横幅三级展开（摘要 → 全文——StatusBanner details 槽语义；600 截断只
        // 作用于摘要态，表格已独立成块不受截断）
        // mono 摘要前补「报告全文」小节头
        Text(
            "报告全文",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (expanded) reportText else reportText.take(600) + if (reportText.length > 600) "…" else "",
            style = DesktopTypography.mono,
        )
        // 右栏两钮胶囊化（.abtn secondary 制式——AppButton Secondary=描边胶囊）
        AppButton(
            label = if (expanded) "收起全文" else "展开全文",
            busy = false,
            kind = AppButtonKind.Secondary,
            onClick = { expanded = !expanded },
        )

        // 通栏钮（flex:1）+ 无上方分隔线（btnrow 形态）。「打开报告文件」= Primary
        // （拿到报告后的最高频下一步——动作区五钮不再同权堆叠，主操作有层级锚点）
        AppButton(
            label = "打开报告文件",
            busy = false,
            onClick = onOpenReport,
            modifier = Modifier.fillMaxWidth(),
        )

        // 导出脱敏开关（导出/送飞书共用——外发产物内容层前置变换；默认关 =
        // 逐字保真现状，fail-closed：脱敏须用户显式开启）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAnonymizeExportChange(!anonymizeExport) },
        ) {
            Checkbox(checked = anonymizeExport, onCheckedChange = onAnonymizeExportChange)
            Text(
                "导出脱敏（VIN/IMEI/MAC/IP/用户名路径同值替换）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 导出交付物（run 元信息 + 报告全文 + 证据行原文的可分享 Markdown；
        // 运行中置灰带原因——半成品报告导出即废稿）
        AppButton(
            label = "导出 Markdown",
            busy = false,
            kind = AppButtonKind.Secondary,
            onClick = onExportReport,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            disabledReason = if (running) "研判运行中，结束后可导出" else null,
        )

        // 一键送飞书（文件消息直发桥接接收会话——导出 + 发送一气呵成，
        // 发送前恒重新导出即恒最新报告文本）；置灰判据 = [larkSendDisabledReason]
        AppButton(
            label = "送飞书",
            busy = false,
            kind = AppButtonKind.Secondary,
            onClick = onSendToLark,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running && larkDeliveryReady,
            disabledReason = larkSendDisabledReason(running = running, larkDeliveryReady = larkDeliveryReady),
        )

        // 根因存案（导出/送飞书同排先例——动作区次序：交付物 → 外发 → 记忆）。
        // HITL 契约：点存案即「根因确认」判定，落库前经对话框四要素可改可补；
        // 不自动存案防误案入库
        AppButton(
            label = "存入案例库",
            busy = false,
            kind = AppButtonKind.Secondary,
            onClick = onArchiveCase,
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            disabledReason = caseArchiveDisabledReason(running = running),
        )

        // 导出产物行（TaskCard 成功产物行同款：可拖选路径 + 行尾复制钮——
        // weight(1f) 吃满剩余宽，复制钮恒贴行尾；路径可见可复制 = 分享动线的可带走面）
        if (exportedReportPath != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        exportedReportPath,
                        style = DesktopTypography.monoTag,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CopyIconButton(
                    textProvider = { exportedReportPath },
                    contentDescription = "复制导出路径",
                )
            }
            GhostChipButton(label = "打开所在文件夹", onClick = onOpenExportFolder)
        }

        // 追问（AI 可用时——增量研判，原结论不改写）
        if (aiEnabled) {
            OutlinedTextField(
                value = followUpText,
                onValueChange = { followUpText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("对报告追问（增量研判）", style = MaterialTheme.typography.bodySmall) },
                placeholder = { Text("例：XPU 回传频率正常吗？", style = MaterialTheme.typography.bodySmall) },
                minLines = 2,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            // 追问钮 AppButton + disabledReason——置灰原因可感知（禁静默置灰）
            AppButton(
                label = "追问",
                busy = false,
                onClick = { onFollowUp(followUpText); followUpText = "" },
                modifier = Modifier.fillMaxWidth(),
                kind = AppButtonKind.Secondary,
                enabled = !running && followUpText.isNotBlank(),
                disabledReason = when {
                    running -> "研判运行中，结束后可追问"
                    followUpText.isBlank() -> "先输入追问内容"
                    else -> null
                },
            )
        } else {
            Text(
                "配置模型服务后可对报告追问（增量研判）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「送飞书」置灰判据（纯函数：判据序即文案序——研判运行中先判，桥接态后判）。
 * null = 可发送；非 null = 置灰原因（按钮 disabledReason 直接消费，禁静默置灰）。
 */
internal fun larkSendDisabledReason(running: Boolean, larkDeliveryReady: Boolean): String? = when {
    running -> "研判运行中，结束后可发送"
    !larkDeliveryReady -> "飞书桥接未运行，请先在飞书桥接页启动桥接"
    else -> null
}

/**
 * 「存案」按钮置灰判据（纯函数——运行中半成品报告存案即废案）。
 * null = 可存案；非 null = 置灰原因。
 */
internal fun caseArchiveDisabledReason(running: Boolean): String? =
    if (running) "研判运行中，结束后可存案" else null

// ── 存案编辑对话框（四要素预填可改可补——HITL：保存动作即「根因确认」） ──

/** tags 输入串分隔符（中英逗号 + 顿号三形态——预填串按「，」join 的对偶端） */
private val CASE_TAGS_SEPARATOR = Regex("[，,、]")

/**
 * 存案编辑对话框：标题/症状/根因/修法/证据日志/tags 六字段预填可改可补
 * （预填载荷由 VM 的 `buildCaseDraft` 从报告解析与证据行回读生成——本件纯编辑面，
 * 零业务判定）。视觉保守默认（AppDialogDefaults 统一制式），视觉层待真机终裁。
 */
@Composable
internal fun CaseArchiveDialog(
    draft: WorkbenchViewModel.CaseDraft,
    onSave: (WorkbenchViewModel.CaseDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(draft) { mutableStateOf(draft.title) }
    var symptom by remember(draft) { mutableStateOf(draft.symptom) }
    var rootCause by remember(draft) { mutableStateOf(draft.rootCause) }
    var fixSummary by remember(draft) { mutableStateOf(draft.fixSummary) }
    var evidenceLog by remember(draft) { mutableStateOf(draft.evidenceLog) }
    var tagsText by remember(draft) { mutableStateOf(draft.tags.joinToString("，")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // 与 RenameDialog/AppConfirmDialog 同族同档——统一 shape/containerColor/发丝定缘
        shape = AppDialogDefaults.shape,
        containerColor = AppDialogDefaults.containerColor(),
        modifier = Modifier.appDialogRim(),
        title = { Text("存入历史案例库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                Text(
                    "存案即确认本报告根因；案例入库后，下次遇到相似症状时 AI 研判会先检索历史案例、从既有结论起步。标题与症状必填，其余可补。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("案例标题（必填）", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = symptom,
                    onValueChange = { symptom = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("症状（必填）", style = MaterialTheme.typography.bodySmall) },
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = rootCause,
                    onValueChange = { rootCause = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("根因", style = MaterialTheme.typography.bodySmall) },
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = fixSummary,
                    onValueChange = { fixSummary = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("修复要点（报告不产修法——此处补写）", style = MaterialTheme.typography.bodySmall) },
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = evidenceLog,
                    onValueChange = { evidenceLog = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("证据日志（原文回读预填）", style = MaterialTheme.typography.bodySmall) },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("相关 TAG（逗号分隔——预填证据高频词）", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    WorkbenchViewModel.CaseDraft(
                        title = title,
                        symptom = symptom,
                        rootCause = rootCause,
                        fixSummary = fixSummary,
                        evidenceLog = evidenceLog,
                        tags = tagsText.split(CASE_TAGS_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() },
                    ),
                )
            }) { Text("存案") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ── 引用两态 chip：回读 + 硬门禁校验 + 可点判定（internal 顶层 = jvmTest 直测缝） ──

/**
 * 引用渲染态：ref + 硬门禁校验结果。
 * 带区间 ref 的 check 恒为 [EvidenceCitationCheck] 四态之一；check = null 覆盖两情形：
 * 旧格式 ref（无引用标记字段，永不校验）与带区间 ref 的回读在途窗口——渲染面对
 * 「有区间字段但非 Verified」一律按未验降级呈现，永不提前给 chip。
 */
internal data class EvidenceRefView(
    val ref: EvidenceRef,
    val check: EvidenceCitationCheck?,
)

/**
 * 单条引用回读 + 硬门禁校验（调用方在 IO 线程执行——本函数做文件读）。
 *
 * - 旧格式 ref（标记三字段任一为空）：纯时间戳语义现状——[evidenceSnippetOf]
 *   全量回读摘要，不校验（check = null，渲染无 chip 无注记）；
 * - 带区间 ref：引用行区间一次流读（[validateEvidenceCitation] 的 linesOf 注入处
 *   惰性读窗），校验与摘要共用同一窗口——省全量 readLines（净 IO 较旧全量扫描持平或降）。
 *   硬门禁未过不产摘要（窗口无命中行——失败引用不展示可能错位的 snippet）；
 *   Verified 时命中行摘要从区间窗内取（evidenceSnippetOf 纯函数收窗，单文件域 =
 *   引用标记的产物）。
 */
internal fun readEvidenceRef(ref: EvidenceRef, evidenceFiles: List<String>): EvidenceRefView {
    // 标记三字段先落局部量（:core 跨模块 val 属性无智能收窄——null 守卫后直接用）
    val fileName = ref.fileName
    val startLine = ref.startLine
    val endLine = ref.endLine
    if (fileName == null || startLine == null || endLine == null) {
        // 旧格式引用（无行区间）：先流式定位命中行（只驻留一行），再交给摘要提取——
        // 全量 readLines 在大日志上会把整份文件摊成 List<String> 压垮堆
        val snippet = evidenceSnippetOf(
            timestamp = ref.timestamp,
            linesOf = { path ->
                findTimestampLineIn(File(path), ref.timestamp)?.let { listOf(it) }.orEmpty()
            },
            evidenceFiles = evidenceFiles,
        )
        return EvidenceRefView(
            ref = if (snippet != null) ref.copy(tag = snippet.first, snippet = snippet.second) else ref,
            check = null,
        )
    }
    // 惰性窗口缓存：validateEvidenceCitation 内部解析产物路径后经 linesOf 取窗，
    // 首次调用读盘、窗口驻留供摘要提取复用（同一次 IO 双消费）
    var window: List<String>? = null
    val check = validateEvidenceCitation(
        ref = ref,
        evidenceFiles = evidenceFiles,
        linesOf = { path, start, end ->
            window ?: File(path).useLines { lines ->
                lines.drop(start - 1).take(end - start + 1).toList()
            }.also { window = it }
        },
    )
    val windowLines = when (check) {
        EvidenceCitationCheck.Verified -> window
        else -> null
    }
    if (windowLines == null) return EvidenceRefView(ref = ref, check = check)
    val snippet = evidenceSnippetOf(
        timestamp = ref.timestamp,
        linesOf = { windowLines },
        evidenceFiles = listOf(fileName),
    )
    return EvidenceRefView(
        ref = if (snippet != null) ref.copy(tag = snippet.first, snippet = snippet.second) else ref,
        check = check,
    )
}

/**
 * 引用两态判定（纯函数——渲染面 chip/降级注记的单一决策源）：
 * 仅 [EvidenceCitationCheck.Verified] 产 chip 直达目标 `(引用文件名, 1 基起始行)`；
 * 失败/未验/回读在途恒 null——时间戳行维持现状跳转 + faint「行号未验」注记
 * （行号漂移防线：校验不过永不放行行号直达）。
 */
internal fun evidenceChipTargetOf(ref: EvidenceRef, check: EvidenceCitationCheck?): Pair<String, Int>? {
    if (check != EvidenceCitationCheck.Verified) return null
    val fileName = ref.fileName ?: return null
    val startLine = ref.startLine ?: return null
    return fileName to startLine
}

/** 假设三态徽章行（报告文本派生计数呈现） */
@Composable
private fun HypothesisBadges(sections: ReportSections) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        BadgeChip(label = "已确认 ${sections.confirmed.size}", color = MaterialTheme.colorScheme.primary)
        BadgeChip(label = "怀疑 ${sections.suspected.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        BadgeChip(label = "已排除 ${sections.excluded.size}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun BadgeChip(label: String, color: Color) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            // 微洗底 + 全色字（selectedWash 档）——contentFaint(0.6) 洗底上全色字
            // 对比度结构性不可达 4.5:1（PageReport RUNNING pill 同款微洗底配方）
            .background(color.copy(AppAlpha.selectedWash), AppShapes.small)
            .padding(horizontal = Dimens.space8, vertical = Dimens.space4),
    )
}

/** 三态假设逐条列表（已确认 ✓ / 怀疑 ? / 已排除 ✗ 前缀呈现——失败假设保留） */
@Composable
private fun HypothesisList(sections: ReportSections) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
        sections.confirmed.forEach { item ->
            HypothesisRow(prefix = "✓", text = item, color = MaterialTheme.colorScheme.primary)
        }
        sections.suspected.forEach { item ->
            HypothesisRow(prefix = "?", text = item, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        sections.excluded.forEach { item ->
            HypothesisRow(prefix = "✗", text = item, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun HypothesisRow(prefix: String, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        Text(prefix, style = MaterialTheme.typography.bodySmall, color = color)
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/**
 * 追问增量研判卡：每次追问的 `## 增量研判（追问）` 段独立成卡（TonalCard + 卡头
 * 时间序号「增量研判 · 追问 N」+ 该段文本）——增量结论不再只藏在「展开全文」尾部。
 * 主报告口径（徽章/三态/因果链）不动——增量不改写原结论。
 */
@Composable
private fun IncrementCard(index: Int, text: String) {
    // .card border-left:2px var(--ai)——语义条挂**卡左侧**（Row 首列 = 左缘条，
    // 卡体居右）；条列必须排在卡体列**之前**，调换即方向反转。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 语义条随卡整高——verticalScroll 列内 fillMaxHeight 无效，
            // 须 IntrinsicSize.Min 先撑出行高
            .height(IntrinsicSize.Min),
    ) {
        // 2dp aiAccent 左条（border-left:2px var(--ai)——configModifiedBarWidth=3dp
        // 是配置行档，增量卡 2px 档，语义独立不混用）
        Column(
            modifier = Modifier
                .width(Dimens.space2)
                .fillMaxHeight()
                .background(
                    color = LocalEasyToolboxColors.current.aiAccent,
                    shape = AppShapes.check,
                ),
        ) {}
        Column(
            modifier = Modifier
                .weight(1f)
                .background(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = AppShapes.medium,
                ),
        ) {
            TonalCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    Text(
                        "增量研判 · 追问 ${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    PillLabel(
                        text = "增量",
                        container = LocalEasyToolboxColors.current.aiAccentContainer,
                        contentColor = LocalEasyToolboxColors.current.aiAccent,
                    )
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Dimens.space4),
                )
            }
        }
    }
}
