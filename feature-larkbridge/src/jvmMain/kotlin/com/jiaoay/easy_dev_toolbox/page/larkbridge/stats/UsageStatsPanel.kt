package com.jiaoay.easy_dev_toolbox.page.larkbridge.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalDayBattle
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.dayBattleTint
import com.jiaoay.easy_dev_toolbox.widget.AvatarImage
import com.jiaoay.easy_dev_toolbox.widget.CardHeader
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.observe.AuditEntryJson
import com.jiaoay.lark.bridge.observe.JsonlTailReader
import com.jiaoay.lark.bridge.observe.SenderStatsEntry
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用统计面板（O2-O12 各卡 + 二级详情页）。
 *
 * ## 数据流（方案 :325 规格）
 * 进页 + 手动「刷新」时，在 [Dispatchers.IO] 用 `JsonlTailReader.readAuditAll/readStatsAll` 反向分块读
 * `audit.jsonl` / `sender_stats.jsonl`（**只读**，绝不写 `~/.easy_dev_toolbox/`），聚合全部走 [UsageStats] 纯函数，
 * 数据量在聚合层截断（柱 ≤31 / 类型 ≤8 / 发送者 ≤10）。**不轮询**——进页各读一次。
 *
 * ## 零白名单 / 图表入场动画
 * 全部尺寸走 `Dimens.chart*` token（chartHeight / chartStroke / chartBarWidth / chartBarGap /
 * chartMiniBarHeight / chartMiniBarWidth）；颜色走 [LocalEasyToolboxColors] 与 `MaterialTheme.colorScheme`；
 * 透明度走 [AppAlpha] token，不含任何裸 `.dp` / 裸 `alpha = 0.x` / 裸 `tween(`/`spring(`。
 * 绘制坐标全在 `DrawScope` 内以 `toPx()` 换算，不出现 `.dp` 字面量。
 *
 * O6 柱状图与 O7 环形图共用 [rememberChartProgress]（[AppMotion.standard] 180ms 单值动画，0f→1f）
 * 做入场缩放——属「数据已刷新」的**状态认知**动效，一次性播放、无逐柱/逐扇编排，
 * 与流式输出/日志行零动画铁律不冲突（方案 :325）。
 *
 * ## 二级页
 * 列表 ↔ 详情在**页签内**切换（`selectedOpenId` + [AnimatedContent]，零导航改动）；
 * 详情四卡 = 用户信息 / 回复性格（per-user 档）/ 访问状态（白黑快捷动作）/ 对话记录（摘要级，原文不落盘）
 * ——详情页实现归同包 `SenderDetailPanel.kt`，图表件归同包 `UsageCharts.kt`。
 *
 * @param dataRoot 桥接数据根（`~/.easy_dev_toolbox/lark`），审计/统计文件所在目录
 * @param access 生效白黑名单配置（快捷动作的读侧）
 * @param onAccessChange 快捷动作产出新配置的写回（draft→debounce→落盘→HOT 现成链路零新写路径）
 */
@Composable
fun UsageStatsPanel(
    dataRoot: Path,
    modifier: Modifier = Modifier,
    access: AccessConfig? = null,
    onAccessChange: (AccessConfig) -> Unit = {},
    /**
     * 「任务明细」卡展开票据：驾驶舱最近任务「全部记录 →」递入
     * （usageStatsFocusTicket，configFocusTicket 同款先例）——票据 >0 时展开明细卡。
     * rememberSaveable 会话级展开态，App 重启归零（收起态）。
     */
    detailExpandTicket: Int = 0,
) {
    var auditEntries by remember { mutableStateOf<List<AuditEntryJson>>(emptyList()) }
    var senderEntries by remember { mutableStateOf<List<SenderStatsEntry>>(emptyList()) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var loaded by remember { mutableStateOf(false) }
    // 读取失败可见化——「读到 0 条」≠「读取抛异常」，runCatching{...}.getOrDefault(emptyList())
    // 会把两者折叠成同一假空态（「暂无记录」）。
    // 失败态单独记 message，在空态分支之前渲染 StatusBanner(Error) + 重试钮。
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var selectedOpenId by rememberSaveable { mutableStateOf<String?>(null) }
    // 「任务明细」卡展开态（会话级；默认收起——明细可达 200 行，列表态默认轻量）。
    // state 上提本层后经 expandedOverride 直接驱动 SectionCard 壳
    // （单级受控）——票据 >0 时壳直接展开，不再有「壳自持收起但内容受控展开」的双级死结。
    var detailExpanded by rememberSaveable { mutableStateOf(false) }
    val dir = dataRoot.toString()

    LaunchedEffect(detailExpandTicket) {
        // 票据消费——驾驶舱「全部记录 →」跳转即展开（首次组合前票据已 >0 也生效：
        // LaunchedEffect 首跑即判）；expandedOverride 直接翻壳，一键直达成立。
        if (detailExpandTicket > 0) detailExpanded = true
    }

    LaunchedEffect(refreshTick) {
        // 两文件分别 runCatching——首个失败的异常信息进 loadError（不吞）；成功侧照常入列。
        // 失败保留旧数据不清空（诚实显示「读取失败 + 旧快照」优于假空态）；audit 侧失败优先记因。
        val audit = withContext(Dispatchers.IO) {
            runCatching { JsonlTailReader.readAuditAll(dir).records }
        }
        val sender = withContext(Dispatchers.IO) {
            runCatching { JsonlTailReader.readStatsAll(dir).records }
        }
        audit.fold(
            onSuccess = { auditEntries = it },
            onFailure = { },
        )
        sender.fold(
            onSuccess = { senderEntries = it },
            onFailure = { },
        )
        loadError = jsonlReadFailureMessage(audit, sender)
        nowMillis = System.currentTimeMillis()
        loaded = true
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        // 根 Column 段距走页级卡距档 space14（与其他页级卡距档统一，design-language.md §2.10 --sp-14 卡距档）
        verticalArrangement = Arrangement.spacedBy(Dimens.space14),
    ) {
        // O3 标题 + O2 刷新（CardHeader 统一配方，titleSmall 级）；
        // 卡头 hint「进页自动读取 · 只读 audit.jsonl」（chead 行尾 hint 形态）
        CardHeader(
            title = "使用统计（agent 任务审计聚合）",
            style = MaterialTheme.typography.titleSmall,
            trailing = {
                // chead 行尾序：刷新 chipbtn 在前、hint 在后
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "刷新",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(AppShapes.capsule)
                            .clickable { refreshTick++ }
                            .padding(horizontal = Dimens.space14, vertical = Dimens.space6),
                    )
                    Text(
                        text = "进页自动读取 · 只读 audit.jsonl",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                    )
                }
            },
        )

        // 读取失败 ≠ 暂无记录——空态分支之前先判 loadError；trailing 重试钮 = 点「刷新」
        if (loadError != null) {
            StatusBanner(
                status = StatusBannerStatus.Error,
                text = "任务记录读取失败：$loadError（检查数据目录权限后点「刷新」）",
                trailing = {
                    TextButton(onClick = { refreshTick++ }) { Text("重试") }
                },
            )
        }

        when {
            // 加载态：hero 尺寸进度圈居中（标准组合，非裸文字）；
            // 圈下补说明文字「正在读取产物…」——纯圈无语境
            !loaded -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(Dimens.iconHero))
                Text(
                    text = "正在读取产物…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.space8),
                )
            }

            auditEntries.isEmpty() && senderEntries.isEmpty() -> EmptyStateView(
                text = "暂无 agent 任务记录（agent 收到消息并执行后自动记录）",
                modifier = Modifier.fillMaxWidth(),
                fillParent = false,
            )

            else -> AnimatedContent(
                targetState = selectedOpenId,
                transitionSpec = {
                    // 页签切换配方同构（LarkBridgePage TabRow 先例）：standard 进 / fast 出
                    (fadeIn(animationSpec = AppMotion.standard()) +
                        slideInVertically(animationSpec = AppMotion.standard()) { it / 16 }) togetherWith
                        fadeOut(animationSpec = AppMotion.fast())
                },
                label = "senderDetailLevel",
            ) { openId ->
                // 🔴 AnimatedContent 的 content 必须是**单个布局根**（内部 Layout 假设单根，多根会被叠放
                //    ⇒ 列表态多个 SectionCard 全叠在一处）。
                if (openId == null) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space14)) {
                        // 「任务明细」卡——列表态顶部（AnimatedContent 单根约束在案：
                        // 加进列表态 Column 内部合法）。
                        // 🔴 auditEntries 可达 20000 条（JsonlTailReader.MAX_RECORDS）——
                        //    take(200) 截断防一签渲染两万行卡死。
                        RecentTaskDetailCard(
                            auditEntries = auditEntries,
                            expanded = detailExpanded,
                            onExpandedChange = { detailExpanded = it },
                        )
                        UsageSections(
                            auditEntries = auditEntries,
                            senderEntries = senderEntries,
                            nowMillis = nowMillis,
                            onOpenSender = { selectedOpenId = it },
                        )
                    }
                } else {
                    SenderDetailPanel(
                        openId = openId,
                        senderEntries = senderEntries,
                        auditEntries = auditEntries,
                        access = access,
                        onAccessChange = onAccessChange,
                        onBack = { selectedOpenId = null },
                    )
                }
            }
        }
    }
}

/**
 * 统计签「任务明细」卡——audit 全量明细的 UI 浏览面（倒序、
 * 状态图标 + kind 人话 + 正文摘要 40 字 + 耗时，复用驾驶舱 [RecentTaskRow] 行配方——
 * internal 共享零传徽标参）。默认收起（[expanded] 受控态上提 UsageStatsPanel——票据
 * 可直接展开）；🔴 take(200) 截断（auditEntries 可达 20000 条，直渲染卡死；卡头 badge 报截断口径）。
 *
 * 结构说明：SectionCard 走 **单级受控**壳——
 * `expandedOverride = expanded` + `onExpandedChange` 回写（SectionCard 受控 seam），
 * 壳的展开/收起完全由受控态驱动（rememberSaveable 会话级）：驾驶舱「全部记录 →」票据
 * 置 true 后壳直接展开（不再有「壳收起但内容受控展开」的双级结构——双级下票据只翻内层
 * 受控态、壳仍收起导致明细永远翻不开）；用户点壳标题经 onExpandedChange 同步回写，
 * 手动收起后再点「全部记录」仍可重新展开。内层 AnimatedVisibility 二次门控与
 * 「点击此处展开」引导行不得回潮（引导行的唯一职责 = 兜底双级结构，单级后无存在理由）。
 */
@Composable
private fun RecentTaskDetailCard(
    auditEntries: List<AuditEntryJson>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    if (auditEntries.isEmpty()) return
    // 倒序 + 截断一次算好（remember 按 entries 身份缓存——1Hz 无重算）
    val detailEntries = remember(auditEntries) {
        auditEntries.sortedByDescending { it.ts }.take(RECENT_TASK_DETAIL_MAX_ROWS)
    }
    SectionCard(
        title = "任务明细",
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expandedOverride = expanded,
        onExpandedChange = onExpandedChange,
        headerTrailing = {
            PillLabel(
                text = if (auditEntries.size > detailEntries.size) {
                    "最近 ${detailEntries.size} / 共 ${auditEntries.size} 条"
                } else {
                    "共 ${auditEntries.size} 条"
                },
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            detailEntries.forEach { entry ->
                com.jiaoay.easy_dev_toolbox.page.larkbridge.cockpit.RecentTaskRow(entry = entry)
            }
        }
    }
}

/** 统计各小节（仅在已加载且有数据时渲染；[onOpenSender] 点击用户行进详情二级页）。 */
@Composable
private fun UsageSections(
    auditEntries: List<AuditEntryJson>,
    senderEntries: List<SenderStatsEntry>,
    nowMillis: Long,
    onOpenSender: (String) -> Unit,
) {
    val colors = LocalEasyToolboxColors.current
    val summary = UsageStats.summarize(auditEntries)
    val buckets = UsageStats.aggregateByDay(auditEntries, nowMillis)
    val kinds = UsageStats.aggregateByKind(auditEntries)
    val backends = UsageStats.aggregateByBackend(auditEntries)
    val today = UsageStats.summarizeToday(auditEntries, nowMillis)
    // 「… 其余 N 位发送者」省略行 + MAX_SENDERS=10 截断——详情态走 SenderDetailPanel
    // 独立全量，列表态恒 ≤10（本组件是列表态，二级页全量在详情）：
    val senders = UsageStats.aggregateSenders(senderEntries)
    // totalSenders 须取**未截断全量**——
    // senders 已被 MAX_SENDERS=10 截断，size 恒 ≤10 ⇒ 省略行判定恒 false 永不可达
    val totalSenders = UsageStats.aggregateSenders(senderEntries, limit = Int.MAX_VALUE).size

    // O5 任务总览 / O6 近 7 天 / O7 按类型 —— 左列；任务明细 + 发送者统计 —— 右列
    // （.grid2 = 1.15fr/1fr 双列网格。窄窗回落：按统计签最小可读宽 600dp，
    // <600dp 回落单列防挤压——最小窗下双列不可读）。
    val isWide = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width /
        LocalDensity.current.density >= 600f

    // O5 任务总览
    if (auditEntries.isNotEmpty()) {
        val overviewCard: @Composable ColumnScope.() -> Unit = {
            // 三卡卡头 trailing pill mute（surfaceDeep 底 + onSurfaceVariant 字）——容器语义注记
            SectionCard(
                title = "任务总览",
                modifier = Modifier.fillMaxWidth(),
                headerTrailing = {
                    PillLabel(
                        text = "全部窗口",
                        container = LocalToolboxSurfaceColors.current.surfaceDeep,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    StatLine("总数", summary.total.toString())
                    StatLine("成功", summary.success.toString(), colors.success, colors.successContainer)
                    StatLine(
                        "失败",
                        summary.failure.toString(),
                        if (summary.failure > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        if (summary.failure > 0) colors.errorContainerFixed else null,
                    )
                    StatLine("跳过", summary.skipped.toString())
                    StatLine("成功率", formatSuccessRate(summary.successRate))
                    StatLine("平均耗时", formatElapsed(summary.avgElapsedMillis))
                }
            }

            // O6 近 7 天堆叠柱状图（抽至 UsageCharts 共享——驾驶舱今日统计卡同语言）
            SectionCard(
                title = "近 7 天",
                modifier = Modifier.fillMaxWidth(),
                headerTrailing = {
                    PillLabel(
                        text = "堆叠柱状图",
                        container = LocalToolboxSurfaceColors.current.surfaceDeep,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                    UsageBarChart(buckets = buckets, modifier = Modifier.fillMaxWidth())
                    // 日期轴：dd 标签行与柱几何同宽对齐（同一布局算法含整体居中，
                    // Canvas 下方 Text 行铺排，不用 drawText）——看图能回答「哪根柱是哪天」
                    BarChartDateAxis(buckets = buckets)
                    ChartLegend()
                }
            }

            // O7 按类型（环形图 + 右侧图例，扇数已在聚合层截断 ≤ MAX_KINDS）
            if (kinds.isNotEmpty()) {
                SectionCard(
                    title = "按类型",
                    modifier = Modifier.fillMaxWidth(),
                    headerTrailing = {
                        PillLabel(
                            // ≤8 = UsageStats.MAX_KINDS 截断口径（「环形 ≤8 扇」）
                            text = "环形 ≤${UsageStats.MAX_KINDS} 扇",
                            container = LocalToolboxSurfaceColors.current.surfaceDeep,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                ) {
                    UsageDonutChart(usages = kinds)
                }
            }

            // O8 按后端 / O9 今日 vs 累计（照旧保留，落左列尾）
            if (backends.isNotEmpty()) {
                SectionCard(title = "按后端", modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                        backends.forEach { b ->
                            Text(
                                text = "${b.label}：共 ${b.total} · 成功 ${b.success} · 失败 ${b.failure} · 跳过 ${b.skipped}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            SectionCard(title = "今日 vs 累计", modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                    StatLine("今日", today.total.toString())
                    StatLine("今日成功", today.success.toString(), colors.success, colors.successContainer)
                    StatLine("今日失败", today.failure.toString())
                    StatLine("今日跳过", today.skipped.toString())
                    StatLine("累计", summary.total.toString())
                }
            }
        }

        val rightCard: @Composable ColumnScope.() -> Unit = {
            // O10-O12 发送者统计
            // 卡题「发送者统计」+
            // chead 尾 hint「点击行进详情 · hover 复制 openId」——hint 语义（弱档说明）归位 headerTrailing
            SectionCard(
                title = "发送者统计",
                modifier = Modifier.fillMaxWidth(),
                headerTrailing = {
                    Text(
                        text = "点击行进详情 · hover 复制 openId",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalToolboxSurfaceColors.current.onSurfaceFaint, // .hint on-surface-faint 档
                    )
                },
            ) {
                if (senders.isEmpty()) {
                    EmptyStateView(
                        text = "暂无统计记录（agent 收到消息后自动记录）",
                        modifier = Modifier.fillMaxWidth(),
                        fillParent = false,
                    )
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                        senders.forEach { usage -> SenderRow(usage = usage, onOpen = { onOpenSender(usage.senderOpenId) }) }
                        // >10 位时省略行（… 其余 N 位发送者——详情页动线兜底可达性）
                        if (totalSenders > UsageStats.MAX_SENDERS) {
                            Text(
                                text = "… 其余 ${totalSenders - UsageStats.MAX_SENDERS} 位发送者",
                                style = MaterialTheme.typography.labelSmall,
                                // .faint = on-surface-faint 档
                                color = com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current.onSurfaceFaint,
                            )
                        }
                    }
                }
            }
        }

        if (isWide) {
            // 双列网格（.grid2 1.15fr/1fr 直译——Row + weight 比）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.space14),
            ) {
                Column(modifier = Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(Dimens.space14), content = overviewCard)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.space14), content = rightCard)
            }
        } else {
            // 窄窗回落单列
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.space14)) {
                overviewCard(this)
                rightCard(this)
            }
        }
    }
}

/** O12 发送者行：头像 + 用户名 + 消息/被拒计数 + 行内迷你条（chartMiniBarWidth × chartMiniBarHeight）。
 *  hover 渐显复制按钮（openId 是配白名单要粘贴的值；enabled = hovered 防误点约定）；整行 clickable 进详情。 */
@Composable
private fun SenderRow(usage: SenderUsage, onOpen: () -> Unit) {
    val colors = LocalEasyToolboxColors.current
    HoverableRow(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .clickable(onClick = onOpen)
            .padding(vertical = Dimens.space2),
        shape = AppShapes.small,
        actionContent = { hovered ->
            CopyIconButton(
                textProvider = { usage.senderOpenId },
                contentDescription = "复制发送者 openId",
                enabled = hovered,
            )
        },
    ) {
        AvatarImage(
            url = usage.avatarUrl,
            fallbackName = usage.userName.ifBlank { usage.senderOpenId },
            size = Dimens.avatarInline,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Dimens.space8),
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = usage.userName.ifBlank { usage.senderOpenId },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                // 被拒警示段昼战坐 warningContainer 面（design-doc.md §5.3#4，warning×warningContainer
                // 4.90 槽对呈现）；非昼战保持「 · 被拒 N」同串裸字现状。「消息 N」主段的
                // 昼战卡域取色属卡域文字接线（MC0-e/S3-①）范畴，本点不动。
                if (LocalDayBattle.current && usage.rejectedCount > 0) {
                    PillLabel(
                        text = "被拒 ${usage.rejectedCount}",
                        container = colors.warningContainer,
                        contentColor = colors.warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = buildString {
                            append("消息 ${usage.messageCount}")
                            if (usage.rejectedCount > 0) append(" · 被拒 ${usage.rejectedCount}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        // 被拒次数 warn 着色（与迷你条 rejectedColor 同语言——
                        // 灰色会弱化「被拒」的警示语义）
                        color = if (usage.rejectedCount > 0) {
                            colors.warning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            // 昼战迷你条段坐语义 container 档（design-doc.md §5.3，dayBattleTint 图形 3.0 线）
            SenderMiniBar(
                usage = usage,
                messageColor = dayBattleTint(colors.success, colors.successContainer),
                rejectedColor = dayBattleTint(colors.warning, colors.warningContainer),
            )
        }
    }
}

/** 发送者迷你条：消息段（success）+ 被拒段（warning），固定宽 chartMiniBarWidth。 */
@Composable
private fun SenderMiniBar(usage: SenderUsage, messageColor: Color, rejectedColor: Color) {
    val total = usage.total.coerceAtLeast(1)
    // 迷你条 r-sm 6px 圆角：drawRoundRect 圆角化
    Canvas(modifier = Modifier.width(Dimens.chartMiniBarWidth).height(Dimens.chartMiniBarHeight)) {
        val msgW = size.width * usage.messageCount / total
        val r = Dimens.space6.toPx() /* r-sm 6px 逐字 */
        if (msgW > 0f) drawRoundRect(color = messageColor, topLeft = Offset.Zero, size = Size(msgW, size.height), cornerRadius = CornerRadius(r))
        if (usage.rejectedCount > 0) {
            drawRoundRect(color = rejectedColor, topLeft = Offset(msgW, 0f), size = Size(size.width - msgW, size.height), cornerRadius = CornerRadius(r))
        }
    }
}

/**
 * 统计行（label 左 / value 右）。internal：主面板（UsageSections）与二级页
 * （`SenderDetailPanel.kt`）双侧消费，归属主面板文件共享，不落二级页文件。
 */
@Composable
internal fun StatLine(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    /** 值的语义承载面（昼战 design-doc.md §5.3：与 [valueColor] 同源成对传，昼战坐该面的胶囊；非昼战不消费） */
    valueContainer: Color? = null,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (LocalDayBattle.current && valueContainer != null) {
            PillLabel(
                text = value,
                container = valueContainer,
                contentColor = valueColor,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(text = value, style = MaterialTheme.typography.bodySmall, color = valueColor)
        }
    }
}

/** 成功率百分比（O5）。 */
private fun formatSuccessRate(rate: Double): String = "${(rate * 100).roundToInt()}%"

/**
 * audit / stats 两文件读取 Result → 失败文案（null = 全部成功）。
 * 「读到 0 条」与「读取抛异常」两态区分的聚合判定——audit 侧失败优先记因；
 * 纯函数由 `UsageStatsReadFailureTest` 直测（message 缺失时落异常类简单名，不产空因）。
 */
internal fun jsonlReadFailureMessage(
    audit: Result<List<AuditEntryJson>>,
    sender: Result<List<SenderStatsEntry>>,
): String? = when {
    audit.isFailure -> "audit.jsonl 读取失败：${failureCause(audit)}"
    sender.isFailure -> "sender_stats.jsonl 读取失败：${failureCause(sender)}"
    else -> null
}

/** 失败原因文案：message 优先，缺失落异常类简单名（不产空因）。 */
private fun failureCause(result: Result<*>): String =
    result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
        ?: result.exceptionOrNull()?.javaClass?.simpleName
        ?: "未知错误"

/**
 * 平均耗时人类可读（O5；委托 [UsageStats.formatElapsed] 共享单源）。
 * internal：主面板与二级页（`SenderDetailPanel.kt`）双侧消费，归属主面板文件共享。
 */
internal fun formatElapsed(millis: Long): String = UsageStats.formatElapsed(millis)

/** 任务明细卡渲染上限（auditEntries 可达 20000——直渲染卡死；卡头 badge 报截断口径）。 */
internal const val RECENT_TASK_DETAIL_MAX_ROWS = 200
