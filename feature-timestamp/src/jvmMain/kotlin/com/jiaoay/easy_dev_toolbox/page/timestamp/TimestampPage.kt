package com.jiaoay.easy_dev_toolbox.page.timestamp

import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import androidx.compose.material3.FilterChipDefaults
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.jiaoay.easy_dev_toolbox.core.navigation.SmartPrefillHook
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SectionCard
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.onEnterKey
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 时间戳双向转换页：方向自动识别（纯数字 → 日期时间；日期时间字符串 → 时间戳），
 * 三档输出格式预设（切换即持久化并重算），结果一键复制（含"已复制"反馈）。
 *
 * 「当前时间」卡：500ms 心跳常显 秒/毫秒/日志格式 三种时间（各配独立复制按钮，
 *   打 log 做标记 / 报 bug 填时间点最高频微操作一步到位）；
 * - 结果附注补相对时间解读（「N 分钟前」），随心跳持续刷新。
 *
 * 内容体拆分为无头 [TimestampPageContent]——「工具」
 * 聚合页页内子签复用；页面级 [TimestampPage] 保留原 PageScaffold 骨架不变。
 *
 * 结果区上方有最近转换记录 chips 行
 * （cap 8 会话内存）——点 chip 回填输入框并立即 convert，排查日志反复对时
 * 无需重输；chips 空（会话内尚无成功转换）不渲染该行。
 *
 * 「当前时间」卡折叠化——默认单行
 * （headerTrailing 承载秒值+复制），点开展开三行明细；切签回保持展开
 * （rememberSaveable 会话内记忆）。心跳不动（VM 单例恒跑，折叠只是渲染层省行）。
 *
 * 「区间差值」卡：起始/结束两端输入即时求值（ms 差值 + 人性化两行，保守默认
 *   文案；两端格式可不同混算）。
 *
 * 「批量转换」卡：多行粘贴逐行走单行转换路径，结果带 1 起始行号与输入行对齐；
 *   输出格式与单行共享同一预设；可整列复制（失败/空行留空保持行对齐）。
 */
@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
fun TimestampPage() {
    PageScaffold(title = "时间戳") {
        TimestampPageContent()
    }
}

/** 内容体（无头形态——「工具」聚合页页内子签复用；VM 窗口级单例，两形态共享状态） */
@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimestampPageContent() {
    val viewModel = TimestampViewModel.getViewModel()

    val input by viewModel.timestamp.collectAsState()
    val format by viewModel.format.collectAsState()
    val output by viewModel.output.collectAsState()
    val nowMillis by viewModel.nowMillis.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val diffStart by viewModel.diffStart.collectAsState()
    val diffEnd by viewModel.diffEnd.collectAsState()
    val diffResult by viewModel.diffResult.collectAsState()
    val batchInput by viewModel.batchInput.collectAsState()
    val batchOutput by viewModel.batchOutput.collectAsState()

    // 「当前时间」卡折叠态会话内记忆（默认折叠单行；切签回保持——
    // AnimatedContent SaveableStateHolder 承接 rememberSaveable）
    var nowCardExpanded by rememberSaveable { mutableStateOf(false) }

    NowTimeCard(
        nowMillis = nowMillis,
        nowLogText = viewModel::nowLogText,
        expanded = nowCardExpanded,
        onExpandedChange = { nowCardExpanded = it },
    )

    // 稿主意图卡：平铺四段收进一张「双向转换」SectionCard（卡题 + pill mute
    //「方向自动识别 · 300ms 防抖」）
    SectionCard(
        title = "双向转换",
        // 内容卡卡题档 fs-16/650（.zone 分组卡题 14 与内容卡 16 两档并存）
        titleStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        headerTrailing = {
            PillLabel(
                text = "方向自动识别 · 300ms 防抖",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = viewModel::onTimestampChange,
            label = { Text("时间戳 / 日期时间") },
            placeholder = { Text("如 1756790400、1756790400123 或 2026-09-02 15:30:00") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                // Enter 立即求值（SubmitKey 单行语义默认档，含 NumPadEnter）
                // ——粘贴长串后 Enter 即得结果，不等 300ms debounce（docs/ui-style-guide.md 例外登记语义补齐成真）
                .onEnterKey { viewModel.convert(input) },
            textStyle = DesktopTypography.monoInput
        )

        // 输出格式预设（切换即持久化并按新格式重算）
        // 说明 pill mute 行——「输出格式（切换即持久化并重算）」标题性 pill 在段控上方
        com.jiaoay.easy_dev_toolbox.widget.PillLabel(
            text = "输出格式（切换即持久化并重算）",
            container = com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors.current.surfaceDeep,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SegmentedControl(
            options = TimestampFormat.entries.map { it.displayName },
            selectedIndex = TimestampFormat.entries.indexOf(format),
            onSelect = { viewModel.onFormatChange(TimestampFormat.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
        )

        // 最近转换记录 chips：结果区上方一行，点 chip = 回填输入框并立即
        // convert（防抖流随后会对同值再触发一次，幂等无害）；会话内尚无成功转换则整行不渲染
        if (recent.isNotEmpty()) {
            RecentChipsRow(recent = recent, onChipClick = { value ->
                viewModel.onTimestampChange(value)
                viewModel.convert(value)
            })
        }

        // 结果区（按 ConversionResult 分支渲染）
        when (val result = output) {
            is ConversionResult.Empty -> Unit

            is ConversionResult.FromTimestamp -> ResultRow(
                main = result.formatted,
                note = buildString {
                    append(if (input.trim().length == 10) "按秒解析" else "按毫秒解析")
                    append(" · ")
                    append(TimestampConverter.formatRelative(result.millis, nowMillis))
                },
            )

            is ConversionResult.FromDate -> ResultRow(
                main = result.millis.toString(),
                // 「按日期时间解析 · 」方向前缀
            note = "按日期时间解析 · 秒级时间戳：${result.seconds} · ${TimestampConverter.formatRelative(result.millis, nowMillis)}",
            )

            is ConversionResult.Invalid -> {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    // 区间差值卡：分析启动耗时/两次事件间隔——两端输入同步求值（纯函数，无防抖）
    SectionCard(
        title = "区间差值",
        titleStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        headerTrailing = {
            PillLabel(
                text = "结束 - 起始 · 两端格式可不同",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
            OutlinedTextField(
                value = diffStart,
                onValueChange = viewModel::onDiffStartChange,
                label = { Text("起始") },
                placeholder = { Text("如 1756790400 或 2025-09-02 13:20:00") },
                singleLine = true,
                textStyle = DesktopTypography.monoInput,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = diffEnd,
                onValueChange = viewModel::onDiffEndChange,
                label = { Text("结束") },
                placeholder = { Text("如 1756790400123 或 2025-09-02T13:20:00") },
                singleLine = true,
                textStyle = DesktopTypography.monoInput,
                modifier = Modifier.weight(1f),
            )
        }

        when (val diffOutput = diffResult) {
            is DiffResult.Empty -> Unit

            is DiffResult.Diff -> ResultRow(
                main = diffOutput.millis.toString(),
                note = buildString {
                    append("毫秒差值 · ")
                    append(diffOutput.humanized)
                    if (diffOutput.millis < 0) append("（结束早于起始）")
                },
            )

            is DiffResult.Invalid -> Text(
                text = diffOutput.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    // 批量转换卡：多行粘贴逐行转换（对账场景——一列时间戳整列回贴，行号对齐）
    SectionCard(
        title = "批量转换",
        titleStyle = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        headerTrailing = {
            PillLabel(
                text = "逐行转换 · 300ms 防抖",
                container = LocalToolboxSurfaceColors.current.surfaceDeep,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        OutlinedTextField(
            value = batchInput,
            onValueChange = viewModel::onBatchInputChange,
            label = { Text("多行时间戳 / 日期时间（每行一个）") },
            placeholder = { Text("粘贴一列时间戳或日期时间，逐行按当前输出格式转换") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            textStyle = DesktopTypography.monoInput,
        )

        if (batchOutput.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                Text(
                    text = "结果列（${batchOutput.size} 行 · 失败/空行留空保持对齐）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                CopyButton(text = viewModel.batchOutputText())
            }
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                batchOutput.forEach { line ->
                    BatchLineRow(line)
                }
            }
        }
    }

    // 防抖转换：空输入也触发（清空输出，不报错），移除 isNotBlank 过滤
    LaunchedEffect(Unit) {
        viewModel.timestamp
            .debounce(300.milliseconds)
            .collect { inputText ->
                viewModel.convert(inputText)
            }
    }

    // 批量防抖：与单行同一防抖档（空白输入清空结果区，不报错）
    LaunchedEffect(Unit) {
        viewModel.batchInput
            .debounce(300.milliseconds)
            .collect { inputText ->
                viewModel.convertBatch(inputText)
            }
    }

    // ── palette 剪贴板预填消费（epoch → 输入框）：双 effect 配方——组合期初值
    // 承担「跳页后首次落页」，在页订阅承担「本页保持组合时 palette 再投递」
    //（配方族纪律见 docs/pages/navigation.md「一次性消费槽与页面驻留」节）。
    // 预填只写输入框（onTimestampChange），不调用 convert——转换结果由上方既有
    // 300ms 防抖流按既有输入语义显示（纯显示计算，无自动执行面）。
    LaunchedEffect(Unit) {
        SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.EpochValue>()
            ?.let { viewModel.onTimestampChange(it.value) }
    }
    LaunchedEffect(Unit) {
        SmartPrefillHook.pending.filterNotNull().collect {
            SmartPrefillHook.consumeIfType<SmartPrefillHook.Payload.EpochValue>()
                ?.let { payload -> viewModel.onTimestampChange(payload.value) }
        }
    }
}

/**
 * 批量结果单行：行号 + 该行结果（时间戳方向给格式化日期时间、日期时间方向给毫秒；
 * 失败行给既有 Invalid 文案——与单行转换同话术，空行弱化占位）。
 */
@Composable
private fun BatchLineRow(line: BatchLine) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(
            text = line.number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val result = line.result) {
            is ConversionResult.FromTimestamp -> Text(
                text = result.formatted,
                style = DesktopTypography.monoInput,
                color = MaterialTheme.colorScheme.onSurface,
            )

            is ConversionResult.FromDate -> Text(
                text = result.millis.toString(),
                style = DesktopTypography.monoInput,
                color = MaterialTheme.colorScheme.onSurface,
            )

            is ConversionResult.Empty -> Text(
                text = "空行",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is ConversionResult.Invalid -> Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 「当前时间」卡（折叠化）：500ms 心跳，折叠态单行 = headerTrailing
 * 承载秒值 + CopyIconButton（进签主意图是「转换」——三行常显把首屏推到下方，默认
 * 单行收纳，秒值+复制保持可取）；点标题行展开三行（秒/毫秒/日志格式，NowTimeRow
 * 原样复用）。展开态 headerTrailing 秒值与首行重复故去重（trailing 恒显，展开后
 * 首行即秒值——判定走 [expanded] 显式传参，SectionCard 内部态不外泄）。
 * 切签回保持展开：受控态挂页面 rememberSaveable（会话内记忆）。
 */
@Composable
private fun NowTimeCard(
    nowMillis: Long,
    nowLogText: (Long) -> String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    // 折叠态 = 无卡题单行卡（mono 秒值 flex:1 为行主体 + hint「秒（10 位）」+
    // ⧉ 复制 + ▾ 展开锚）；展开态回卡题（三行常规）。
    SectionCard(
        title = if (expanded) "当前时间" else "",
        collapsible = true,
        expandedOverride = expanded,
        onExpandedChange = onExpandedChange,
        headerTrailing = {
            if (!expanded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                    // 秒值主体（.mono flex:1 fs-14——monoInput 升 14 档）
                    Text(
                        text = (nowMillis / 1000).toString(),
                        style = DesktopTypography.mono,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    // 折叠行档位尾标「秒（10 位）」（.hint 弱档）
                    Text(
                        text = "秒（10 位）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CopyIconButton(
                        textProvider = { (nowMillis / 1000).toString() },
                        contentDescription = "复制秒值",
                    )
                }
            }
        },
    ) {
        NowTimeRow(
            label = "秒（10 位）",
            textProvider = { (nowMillis / 1000).toString() },
        )
        NowTimeRow(
            label = "毫秒（13 位）",
            textProvider = { nowMillis.toString() },
        )
        NowTimeRow(
            label = "日志格式",
            // 格式化已收进 VM：Composable 内零内联 SimpleDateFormat
            textProvider = { nowLogText(nowMillis) },
        )
    }
}

@Composable
private fun NowTimeRow(
    label: String,
    textProvider: () -> String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(
            text = textProvider(),
            style = DesktopTypography.monoInput,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CopyIconButton(textProvider = textProvider, contentDescription = "复制$label")
    }
}

/**
 * 最近转换记录 chips 行（.pillflow）：弱说明标签 +
 * FlowRow 胶囊钮流（.chipbtn 语义——FilterChip 承载，选中态语义不需要恒 false）。
 * 时间戳/日期时间值等宽呈现（mono 档，与输入框/结果行同语域）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecentChipsRow(
    recent: List<String>,
    onChipClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        Text(
            text = "最近转换（cap 8）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            recent.forEachIndexed { chipIndex, value ->
                FilterChip(
                    selected = false,
                    onClick = { onChipClick(value) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary
                            .copy(alpha = AppAlpha.hotWash),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                        // 最近一次转换 chip = primary×hotWash 底 + primary 字（.chipbtn.hot）
                        containerColor = if (chipIndex == 0) {
                            MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hotWash)
                        } else {
                            androidx.compose.ui.graphics.Color.Unspecified
                        },
                        labelColor = if (chipIndex == 0) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                    ),
                    shape = AppShapes.capsule, // 轻标签胶囊档（M3 默认 8dp 角显式归一）
                    label = {
                        Text(
                            text = value,
                            // fs-12/600 正文字族（mono 是 kv/日志语域，chipbtn 正文族）
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** 转换结果行：主结果（等宽字体）+ 复制按钮（含 1.5s「已复制」反馈）+ 附注说明 */
@Composable
private fun ResultRow(
    main: String,
    note: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text(
                text = main,
                style = DesktopTypography.monoInput,
                color = MaterialTheme.colorScheme.onSurface,
            )
            CopyButton(text = main)
        }
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
