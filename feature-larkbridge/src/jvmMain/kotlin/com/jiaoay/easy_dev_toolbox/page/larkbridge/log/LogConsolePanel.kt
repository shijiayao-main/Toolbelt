package com.jiaoay.easy_dev_toolbox.page.larkbridge.log

import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.BottomFollowScrollEffect
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.ScrollableListRow
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.lark.bridge.config.LogConsoleConfig
import com.jiaoay.lark.bridge.observe.LogBus
import com.jiaoay.lark.bridge.observe.LogLine
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日志台面板：级别过滤 + 文本搜索 + 桌面滚动条。
 *
 * ## 零动画铁律
 * 流式输出与日志行零动画：不施加任何内容尺寸动画、列表滚动动画或逐行动画。
 * 贴底跟随用 [BottomFollowScrollEffect]（其内部即时 `scrollToItem`，天然合规，
 * `BOTTOM_FOLLOW_TOLERANCE_PX=8` 是 Int 像素值不命中 RAW_DP；`enabled` 直接对接 `logConsole.followBottom`）。
 *
 * ## 有界内存 + 增量追加（不做 List 全量复制）
 * [lines] 是 `SnapshotStateList`，上限 = `logConsole.capacity`（默认 1000）；每 tick 只追加
 * `seq > lastSeq` 的新行（增量），溢出时一次 `removeRange(0, size - capacity)`。`clear()` 不重置 seq
 * （LogBus 契约 #2），故 lastSeq 追踪始终有效。
 *
 * ## 过滤是显示层派生（不动 LogBus 增量链路）
 * 级别多选（FilterChip）+ 搜索子串（大小写不敏感）只经 [derivedStateOf] 对 [lines] 派生
 * `filtered`——环形缓冲的增量追加与淘汰语义零变化；贴底跟随的 itemCount 切换为
 * filtered.size（过滤态下贴底跟随过滤结果）。清空操作的对象仍为全量 lines。
 *
 * ## 复制所见即所得（复制对象恒为过滤结果——不得回退为「复制恒全量」）
 * 复制对象 = 当前过滤结果 [filtered]（所见即所得——排障贴给他人/喂 AI 时时间戳与
 * 内容双全，过滤态误解风险由披露头消解）。三段语义：
 * 1. **真过滤态**（有 chip/搜索词生效，`isFiltered && filtered.size < lines.size`）：
 *    首行带披露头 `过滤态 N/M`（N=过滤后行数，M=缓冲总行数）——操作者与接收方双知悉
 *    所见非全量；
 * 2. **全量态**（全不选 chip 且无搜索词 = 默认兜底，或过滤后恰为全量）：无披露头，
 *    行为与全量复制一致（能力不丢）；
 * 3. 每行格式恒 `HH:mm:ss\tLEVEL\tTAG\tmessage`（时间列 [LOG_TIME_FORMAT] 单源）。
 *
 * ## ticker 挂在本 Composable 的 [LaunchedEffect]（非 VM init，方案 line 323）
 * 切走 tab 立即停止收集；频率 = `logConsole.tickerHz`（默认 10Hz）。
 * `logConsole.enabled=false` 时不订阅、显示关闭态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogConsolePanel(
    logs: LogBus?,
    console: LogConsoleConfig,
    modifier: Modifier = Modifier,
) {
    if (!console.enabled) {
        // 父容器是 Box(weight(1f)) 全尺寸槽——空态 fillParent=true 垂直居中占满
        EmptyStateView(
            text = "日志台已关闭",
            learningHint = "可在「配置」页签「可观测性」分区重新开启",
            modifier = modifier,
            fillParent = true,
        )
        return
    }
    if (logs == null) {
        EmptyStateView(
            text = "桥接未运行，暂无日志",
            modifier = modifier,
            fillParent = true,
        )
        return
    }

    val colors = LocalEasyToolboxColors.current
    val lines = remember { mutableStateListOf<LogLine>() }
    val listState = rememberLazyListState()
    var lastSeq by remember { mutableStateOf(-1L) }
    val capacity = console.capacity.coerceAtLeast(1)

    // 过滤状态（显示层，不落盘——会话内即时性优先）
    // 默认选中 ERROR/WARN/INFO——只折叠 DEBUG 噪音，避免首开像"缺行"；全不选 = 显示全部
    val selectedLevels = remember { mutableStateListOf("ERROR", "WARN", "INFO") }
    var searchText by remember { mutableStateOf("") }

    // 过滤结果：级别多选 + 搜索子串（大小写不敏感，匹配 tag 或 message）
    val filtered by remember(lines, selectedLevels, searchText) {
        derivedStateOf {
            if (selectedLevels.isEmpty() && searchText.isBlank()) {
                lines
            } else {
                lines.filter { line ->
                    (selectedLevels.isEmpty() || line.level in selectedLevels) &&
                        (searchText.isBlank() ||
                            line.tag.contains(searchText, ignoreCase = true) ||
                            line.message.contains(searchText, ignoreCase = true))
                }
            }
        }
    }

    // ticker：按 tickerHz 增量拉取（挂在本 Composable，切走即停）。
    // snapshotVersion 短路（性能热路径）：空闲期（无新行）不再
    //    做全量收集 + 排序（1000 槽 + sortBy 按 10Hz 白跑）；版本未动直接 continue。
    LaunchedEffect(logs, console.tickerHz) {
        val period = (1000L / console.tickerHz.coerceAtLeast(1)).coerceAtLeast(1L)
        var lastVersion = -1L
        while (true) {
            delay(period)
            if (logs.snapshotVersion() == lastVersion) continue   // 无新行：跳过全量快照
            lastVersion = logs.snapshotVersion()
            val snap = logs.snapshot()
            if (snap.isNotEmpty() && snap.last().seq > lastSeq) {
                for (line in snap) {
                    if (line.seq > lastSeq) lines.add(line)
                }
                lastSeq = snap.last().seq
                val overflow = lines.size - capacity
                if (overflow > 0) lines.removeRange(0, overflow)
            }
        }
    }

    // 贴底跟随：未过滤时贴全量底，过滤时贴过滤结果底（itemCount 语义对齐）。
    // 🔴 contentVersion 传最新一条的 seq——环形缓冲封顶后
    //    lines.size 恒等于 capacity，itemCount 不再变化 ⇒ 跟随停摆；seq 单调递增才是「有新行」信号。
    BottomFollowScrollEffect(
        listState = listState,
        itemCount = if (selectedLevels.isEmpty() && searchText.isBlank()) lines.size else filtered.size,
        enabled = console.followBottom,
        contentVersion = lines.lastOrNull()?.seq,
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text(
                text = "日志台（${filtered.size}/${lines.size}）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            // G-D（O15）：显式「立即清空」按钮——环形缓冲的自动淘汰（每 tick removeRange）不等价于
            //    用户「立即清空」意图，两者并存。clear() 不重置 seq（LogBus 契约 #2），故 lastSeq 追踪仍有效：
            //    清空本地快照 + LogBus 缓冲后，后续 tick 仍按 seq > lastSeq 增量追加新行。
            TextButton(onClick = {
                lines.clear()
                logs.clear()
            }) {
                Text("清空", style = MaterialTheme.typography.labelLarge)
            }
            // 复制所见即所得：复制对象 = 当前过滤结果。
            // 真过滤态（N<M）首行带披露头「过滤态 N/M」；全不选 chip + 空搜索 = 全量
            // 复制（与显示态判定同源——能力不丢）。文案随态动态。
            val isFiltered = selectedLevels.isNotEmpty() || searchText.isNotBlank()
            CopyIconButton(
                textProvider = { buildLogCopyText(lines = lines, filtered = filtered, isFiltered = isFiltered) },
                contentDescription = if (isFiltered) "复制当前过滤结果" else "复制全部日志",
            )
        }

        // 过滤行：级别多选 chip + 搜索输入框；默认勾选 ERROR/WARN（排错第一诉求）。
        // 4 chip 与搜索框同一
        // .toolbar 同排一行（chip 左、search 右缘），搜索框 flex:none 定宽 320px——旧两行
        // 拆排 + 满宽 search 是结构漂移。窄窗 FlowRow 兜底：chip + 320dp 搜索框合计
        // 超可用宽时搜索框整体换行（宽窗单行形态零变化，定宽语义保留）
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            LEVELS.forEach { level ->
                FilterChip(
                    selected = level in selectedLevels,
                    onClick = {
                        if (level in selectedLevels) selectedLevels.remove(level)
                        else selectedLevels.add(level)
                    },
                                        colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary
                            .copy(alpha = AppAlpha.hotWash),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
shape = AppShapes.capsule, // 轻标签胶囊档（M3 默认 8dp 角显式归一）
                    label = { Text(level, style = MaterialTheme.typography.labelSmall) },
                )
            }
            SearchField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = "搜索 tag 或消息内容…",
                textStyle = DesktopTypography.mono, /* mono 语域保持 */
                modifier = Modifier.width(Dimens.consoleSearchWidth), // 稿 :786 flex:none;width:320px（token consoleSearchWidth）
            )
        }

        // 列表 + 右缘桌面滚动条（ScrollableListRow 收敛壳）。
        // 🔴 高度语义：heightIn 施加在 Row 外壳上时，滚动条的 fillMaxHeight 会把
        // Row 交叉轴钉到 max=420dp（fillMax* 在上界弹性容器内钉死交叉轴——NavigationRail fillMaxWidth
        // 同型机制），且内容少时 LazyColumn 只包内容高、深色块与滚动条视觉割裂。故 LazyColumn
        // 补 fillMaxHeight：显式接受「日志台恒占 min(可用高, consoleHeightMax)」，深底铺满
        // 与滚动条同高（10Hz 流式区恒定高度可预期，放弃旧的 240~420 弹性收缩语义）。
        ScrollableListRow(
            listState = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.consoleHeightMin, max = Dimens.consoleHeightMax),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(color = colors.consoleBg, shape = AppShapes.medium)
                    .padding(Dimens.space8),
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                if (filtered.isEmpty()) {
                    item(key = "empty-filter") {
                        Text(
                            text = if (lines.isEmpty()) "暂无日志" else "当前过滤条件下无匹配日志",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.consoleText,
                        )
                    }
                }
                // contentType 复用同一组合缓存槽（7.2 #9；高频 10Hz 追加下降低重组开销）
                items(filtered, key = { it.seq }, contentType = { "lark-log-line" }) { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                    ) {
                        // 时间列：行首 HH:mm:ss——排障时刻锚点，与复制文本四段格式同源
                        // （[LOG_TIME_FORMAT] 单源）。consoleText 非 onSurfaceVariant：
                        // 行底是 consoleBg 恒深底，onSurfaceVariant 深底对比不足（裁量如实上报）。
                        Text(
                            text = line.timeText(),
                            style = DesktopTypography.monoTag,
                            color = colors.consoleText,
                        )
                        Text(
                            text = line.level,
                            style = DesktopTypography.monoTag,
                            color = line.levelColor(colors),
                        )
                        Text(
                            text = line.tag,
                            style = DesktopTypography.monoTag,
                            color = colors.consoleText,
                        )
                        Text(
                            text = line.message,
                            style = DesktopTypography.mono,
                            color = colors.consoleText,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** 日志级别全集（过滤 chip 的展示顺序：从严重到宽松） */
private val LEVELS = listOf("ERROR", "WARN", "INFO", "DEBUG")

/**
 * 日志行时间 formatter（单源）：`HH:mm:ss`。
 *
 * 行渲染时间列与复制文本四段格式共用本实例（[DateTimeFormatter] 线程安全，可共享）；
 * 本地时区（[ZoneId.systemDefault]）——日志时刻以操作者本机钟面呈现，与 Shell 输出台
 * 时间线语言一致（Shell 输出台时间列已另行登记）。
 */
private val LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 行首时间列文本（[LOG_TIME_FORMAT] 单源派生；atMillis → 本机时区 HH:mm:ss）。 */
internal fun LogLine.timeText(): String =
    LOG_TIME_FORMAT.format(Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()))

/** 复制文本单行四段：`HH:mm:ss\tLEVEL\tTAG\tmessage`（首段为时间列）。 */
internal fun LogLine.copyLineText(): String = "${timeText()}\t${level}\t${tag}\t${message}"

/**
 * 复制文本拼装（纯函数，直测锚）：
 *
 * - 真过滤态（[isFiltered] 且 [filtered] 少于 [lines]）：首行披露头「过滤态 N/M」；
 * - 全量态（全不选 chip + 空搜索，或过滤后恰为全量）：无披露头——能力不丢。
 */
internal fun buildLogCopyText(
    lines: List<LogLine>,
    filtered: List<LogLine>,
    isFiltered: Boolean,
): String {
    val body = filtered.joinToString("\n") { it.copyLineText() }
    return if (isFiltered && filtered.size < lines.size) {
        "过滤态 ${filtered.size}/${lines.size}\n$body"
    } else {
        body
    }
}

@Composable
private fun LogLine.levelColor(colors: com.jiaoay.easy_dev_toolbox.core.theme.EasyToolboxColors): Color =
    when (level) {
        "ERROR" -> colors.logError
        "WARN" -> colors.logWarn
        "DEBUG" -> colors.logDebug
        else -> colors.logInfo
    }

