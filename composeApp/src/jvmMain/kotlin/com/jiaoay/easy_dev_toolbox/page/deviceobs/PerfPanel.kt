package com.jiaoay.easy_dev_toolbox.page.deviceobs

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.PerfCsvExporter
import com.jiaoay.toolbelt.device.PerfField
import com.jiaoay.toolbelt.device.PerfSamplerState
import com.jiaoay.toolbelt.device.PerfSessionState
import com.jiaoay.toolbelt.device.PerfSampler
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** 进程排行表前 N（top 全量解析、面板只呈现头部） */
internal const val PROCESS_TOP_N = 10

/** 进程排行排序档（CPU% / RSS 两列切换） */
internal enum class PerfSortMode(val label: String) { CPU("按 CPU"), RSS("按内存") }

/**
 * 面板提示（无设备 / 检测失败 / 多设备未选定）：message 人话 + 对应动作入口，
 * 不静默置灰「开始采样」而无解释。
 */
internal data class PerfPanelHint(val message: String, val action: PerfPanelHintAction)

internal enum class PerfPanelHintAction { GO_SETTINGS, SELECT_DEVICE, REFRESH }

internal fun perfPanelHint(
    deviceState: DeviceState,
    detectError: String?,
    selectedSerial: String?,
): PerfPanelHint? = when {
    detectError != null -> PerfPanelHint("设备检测失败：$detectError", PerfPanelHintAction.GO_SETTINGS)
    deviceState is DeviceState.None ->
        PerfPanelHint("未检测到设备，请连接设备（或刷新检测）", PerfPanelHintAction.REFRESH)

    deviceState is DeviceState.Multi && selectedSerial == null ->
        PerfPanelHint("多设备连接，请先在上方设备栏选择目标设备", PerfPanelHintAction.SELECT_DEVICE)

    else -> null
}

/** CPU 读数文本：值 1 位小数百分比；失败拍显示「—」（明细行承载原因） */
internal fun cpuValueText(cpu: PerfField<Float>): String =
    cpu.value?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "—"

/** 内存读数文本：used 百分比（总量/可用进明细行） */
internal fun memValueText(memory: PerfField<com.jiaoay.toolbelt.device.MemoryStats>): String =
    memory.value?.let { String.format(Locale.ROOT, "%.1f%%", it.usedPercent) } ?: "—"

internal fun tempValueText(temperature: PerfField<Float>): String =
    temperature.value?.let { String.format(Locale.ROOT, "%.1f°C", it) } ?: "—"

internal fun batteryValueText(battery: PerfField<Int>): String =
    battery.value?.let { "$it%" } ?: "—"

/**
 * FPS 读数文本：1 位小数。fps 是全屏合成帧率——非单应用帧率（含系统各显示层的
 * 合成帧；防误读口径与取数语义见 core 域 PerfParsers 的 flips 解析 KDoc）；
 * 失败拍显示「—」（明细行承载原因）。
 */
internal fun fpsValueText(fps: PerfField<Float>): String =
    fps.value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"

/** 内存明细（总量/可用，kB 缩写显示） */
internal fun memDetailText(
    memory: PerfField<com.jiaoay.toolbelt.device.MemoryStats>,
): String = memory.value?.let { "总 ${rssHumanText(it.totalKb)} · 可用 ${rssHumanText(it.availableKb)}" } ?: ""

/**
 * 域级失败明细行文本：拼出全部失败域的人话原因（逐域显式失败不静默的面板侧落地）；
 * 全部域成功 → null 不呈现。
 */
internal fun perfErrorsLine(
    sample: com.jiaoay.toolbelt.device.DevicePerfSample,
): String? {
    val parts = listOf(
        sample.cpuPercent.error?.let { "CPU：$it" },
        sample.memory.error?.let { "内存：$it" },
        sample.temperatureC.error?.let { "温度：$it" },
        sample.batteryPercent.error?.let { "电池：$it" },
        sample.processes.error?.let { "进程：$it" },
        sample.fps.error?.let { "FPS：$it" },
    ).filterNotNull()
    return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
}

/** 多会话图例行五域读数文本（尾号由色标列承载，行内只拼五域值） */
internal fun perfMultiReadingsText(
    sample: com.jiaoay.toolbelt.device.DevicePerfSample,
): String = "CPU ${cpuValueText(sample.cpuPercent)} · 内存 ${memValueText(sample.memory)} · " +
    "温度 ${tempValueText(sample.temperatureC)} · 电池 ${batteryValueText(sample.batteryPercent)} · " +
    "FPS ${fpsValueText(sample.fps)}"

/**
 * 设备性能监控面板（设备观测空间第三成员）：开始采样后有会话态 = 读数行 +
 * CPU/内存/FPS 折线（30 分钟滚动窗）+ 进程排行表；无会话态 = 提示与开始入口。
 * 会话中换设备/停止采样在观测页设备栏操作（选定态与设备域单源共享）。
 *
 * - 采样器消费 :core 设备域 [PerfSampler]（App 级单源，设备三流与选定态同源）；
 *   可见性门控接线（进入组合 +1、退出 −1，计数归并语义见 PerfSampler）；
 * - 采样节奏/门控/失败分层语义全在 [PerfSampler] 头注释，面板只做呈现与
 *   会话开关——整拍错误（tickError）与域级失败明细（[perfErrorsLine]）分层
 *   呈现，禁吞错；
 * - 无会话且无可采目标时按 [perfPanelHint] 提示并解释动作（去设置 / 选设备 /
 *   刷新），「开始采样」缺目标时置灰但原因明示（禁静默无效钮）；
 * - 目标集交互（保守默认 + 显式多选，与按键遥控面板批量注入同口径）：默认只
 *   采当前选定设备（未选定时单设备回退，解析在采样器）；「批量采样」开（仅
 *   多设备态呈现）后目标集 = 手动勾选设备集，开关不隐式广播到全部设备——
 *   进入批量态时预勾选当前选定台，其余台须用户显式勾选；
 * - 多会话视图（批量目标集起多台时）：每 serial 一条图例行（色标 + 尾号 +
 *   五域读数 + 单台停止钮）+ 多曲线画布（每 serial 一色，CPU 实线 / 内存同色
 *   淡化 / FPS 同色点列，色槽见 [PerfChartMulti]）；进程排行表是单台深看工具，多会话对比态
 *   不呈现（选哪台排行属未定义偏好，不猜——单会话视图照常呈现）；
 * - 多会话残留收束：其它 serial 的会话收束原因逐台横幅呈现（单会话投影只覆
 *   盖主 serial，投影外收束不吞）；
 * - 曲线导出：运行态与停止态残留曲线都可导出 CSV（epoch 毫秒 + 三域、失败拍
 *   空字段如实）落 `~/Documents/Toolbelt/perf/` 可见产物树，并可送飞书直发桥接接收会话；
 *   采样中允许导出（发布态是不可变值语义快照，只读导出与采样环无竞态）。
 *
 * 视觉层为保守默认（token 化曲线配色 + 等宽排行行），真机手感由维护者终裁。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PerfPanel(
    devices: DeviceStateSource,
    sampler: PerfSampler,
    onGoSettings: (() -> Unit)? = null,
) {
    val deviceState by devices.deviceState.collectAsState()
    val detectError by devices.detectError.collectAsState()
    val selectedSerial by devices.selectedSerial.collectAsState()
    val samplerState by sampler.state.collectAsState()

    // 可见性门控接线：本页在台才采样（计数归并，与 logcat/设备栏接线同配方）
    DisposableEffect(sampler) {
        sampler.setPageVisible(true)
        onDispose { sampler.setPageVisible(false) }
    }

    val session = samplerState.session
    val running = session is PerfSessionState.Active
    val activeCount = samplerState.sessions.values.count { it is PerfSessionState.Active }
    var expanded by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(PerfSortMode.CPU) }

    val multi = deviceState as? DeviceState.Multi
    var batchMode by remember { mutableStateOf(false) }
    val batchTargets = remember { mutableStateListOf<String>() }

    // 批量目标集与设备态对齐：设备消失即从勾选集剔除（防对空串号起会话）
    multi?.devices?.map { it.serial }?.let { serials ->
        batchTargets.removeAll { it !in serials }
    }

    // 会话启动自动展开（曲线可见）；结束后保持展开由用户收起（同 logcat 配方）
    LaunchedEffect(running) {
        if (running) expanded = true
    }

    val hint = perfPanelHint(deviceState, detectError, selectedSerial)
    val latest = samplerState.latest

    // 曲线导出/送飞书动作：采样中与停止态都允许导出——发布态是不可变值语义快照，
    // 只读导出与采样环无竞态；发起时读 sampler 当前态（不食重组快照的陈旧值）
    val panelScope = rememberCoroutineScope()
    val larkDeliveryReady by ReportDelivery.ready.collectAsState()
    val csvHasPoints = perfHistoryHasPoints(samplerState)
    val onExportCsv: () -> Unit = {
        panelScope.launch(Dispatchers.IO) {
            writePerfCsvExport(sampler.state.value).fold(
                onSuccess = { file -> StatusSignals.show(perfExportReceipt(file)) },
                onFailure = { e -> StatusSignals.show(perfExportFailureText(e)) },
            )
        }
    }
    val onSendCsvToLark: () -> Unit = {
        panelScope.launch(Dispatchers.IO) {
            writePerfCsvExport(sampler.state.value).fold(
                onSuccess = { file ->
                    val reason = ReportDelivery.deliver(file)
                    StatusSignals.show(
                        if (reason == null) "已送出至飞书会话：${file.name}" else reason,
                    )
                },
                onFailure = { e ->
                    StatusSignals.show("导出失败，无法送飞书：${e.message ?: e.javaClass.simpleName}")
                },
            )
        }
    }

    TonalCard(
        contentPaddingVertical = Dimens.space12,
        contentPaddingHorizontal = Dimens.space16,
        contentSpacing = Dimens.space8,
    ) {
        // 头部行：图标 + 标题 + 会话状态 + 展开/收起
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSymbolIcon(
                icon = MaterialSymbols.MONITOR,
                contentDescription = null,
                size = Dimens.iconMedium,
                tint = if (running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            val statusSuffix = when {
                activeCount > 1 -> " · 采样中 $activeCount 台"
                session is PerfSessionState.Active -> " · 采样中 ${session.serial}"
                session is PerfSessionState.Ended -> " · 已停止"
                else -> ""
            }
            Text(
                text = "性能监控$statusSuffix",
                style = MaterialTheme.typography.titleSmall,
                // 标题必须吃满剩余配额（weight 默认 fill）：fill=false 时未用配额悬空在
                // 行尾，会把展开钮推离右缘（窗口越大/标题越短越明显）
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(Dimens.minimumInteractiveSize),
            ) {
                AppSymbolIcon(
                    icon = if (expanded) MaterialSymbols.EXPAND_LESS else MaterialSymbols.EXPAND_MORE,
                    contentDescription = if (expanded) "收起性能面板" else "展开性能面板",
                    size = Dimens.iconLarge,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = AppMotion.standard()) +
                expandVertically(animationSpec = AppMotion.standard()),
            exit = fadeOut(animationSpec = AppMotion.standard()) +
                shrinkVertically(animationSpec = AppMotion.standard()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                // 会话自动收束原因（设备消失/连续失败）：人话呈现不静默回空态
                (session as? PerfSessionState.Ended)?.let { ended ->
                    StatusBanner(status = StatusBannerStatus.Warning, text = ended.reason)
                }

                // 多会话残留收束横幅：主 serial 之外的收束原因逐台呈现（禁吞错）
                if (samplerState.sessions.size > 1) {
                    samplerState.sessions.entries.forEach { (serial, s) ->
                        (s as? PerfSessionState.Ended)?.let { ended ->
                            StatusBanner(
                                status = StatusBannerStatus.Warning,
                                text = "「${serialTailLabel(serial)}」采样已结束：${ended.reason}",
                            )
                        }
                    }
                }

                // 无可采目标提示：动作入口与面板外资源对齐（设置页经中立槽接线；
                // 批量态目标集自足不出提示——勾选集即目标）
                if (!batchMode || running) {
                    hint?.let { h ->
                        StatusBanner(
                            status = StatusBannerStatus.Error,
                            text = h.message,
                            trailing = {
                                when (h.action) {
                                    PerfPanelHintAction.GO_SETTINGS -> onGoSettings?.let { go ->
                                        TextButton(onClick = go) { Text("去设置配置 adb 路径") }
                                    }

                                    // 选设备/刷新的动作在观测页设备栏（面板外资源，消息文案自指引）
                                    PerfPanelHintAction.SELECT_DEVICE,
                                    PerfPanelHintAction.REFRESH -> Unit
                                }
                            },
                        )
                    }
                }

                // 整拍失败（连续未达收会话阈值时）：逐拍可见，不清曲线不断会话
                samplerState.tickError?.let { tick ->
                    Text(
                        text = tick,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        Button(
                            onClick = { sampler.stopSampling() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            AppSymbolIcon(
                                icon = MaterialSymbols.STOP_CIRCLE,
                                contentDescription = null,
                                size = Dimens.iconSmall,
                            )
                            Text("停止采样")
                        }
                        OutlinedButton(onClick = sampler::clearHistory) {
                            AppSymbolIcon(
                                icon = MaterialSymbols.DELETE_SWEEP,
                                contentDescription = null,
                                size = Dimens.iconSmall,
                            )
                            Text("清空曲线")
                        }
                    }

                    // 曲线导出/送飞书（采样中可导出——快照只读；单/多会话视图共用）
                    PerfCsvExportActions(
                        hasPoints = csvHasPoints,
                        larkDeliveryReady = larkDeliveryReady,
                        onExportCsv = onExportCsv,
                        onSendCsvToLark = onSendCsvToLark,
                    )

                    if (activeCount > 1) {
                        MultiSessionBody(samplerState = samplerState, sampler = sampler)
                    } else {
                        if (latest != null) {
                            // 读数行：值或「—」（失败明细在下一行逐域人话呈现）
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space24)) {
                                ReadingColumn(label = "CPU", value = latest.cpuPercent.let(::cpuValueText))
                                ReadingColumn(
                                    label = "内存",
                                    value = latest.memory.let(::memValueText),
                                    detail = memDetailText(latest.memory),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space24)) {
                                ReadingColumn(label = "温度", value = latest.temperatureC.let(::tempValueText))
                                ReadingColumn(label = "FPS", value = latest.fps.let(::fpsValueText))
                                ReadingColumn(label = "电池", value = latest.batteryPercent.let(::batteryValueText))
                            }
                            perfErrorsLine(latest)?.let { errors ->
                                Text(
                                    text = errors,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // 曲线：CPU primary 实线 / 内存 tertiary 实线 / FPS secondary 点列，
                        // 30 分钟磁带窗
                        PerfChart(history = samplerState.history)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "曲线",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(Dimens.space8))
                            Text(
                                "CPU",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(Dimens.space8))
                            Text(
                                "内存",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(modifier = Modifier.width(Dimens.space8))
                            Text(
                                "FPS（全屏合成）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                            if (samplerState.history.overflowed) {
                                Spacer(modifier = Modifier.width(Dimens.space8))
                                Text(
                                    "· 窗口 30 分钟，更早已滚出",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // 进程排行表（前 N）：排序档 CPU/RSS 切换
                        ProcessTable(
                            processes = latest?.processes?.value,
                            sortMode = sortMode,
                            onSortModeChange = { sortMode = it },
                        )
                    }
                } else {
                    // 批量采样（仅多设备态呈现；目标集 = 手动勾选集，不隐式广播）
                    if (multi != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        ) {
                            FilterChip(
                                selected = batchMode,
                                onClick = {
                                    batchMode = !batchMode
                                    batchTargets.clear()
                                    // 批量态起步勾选 = 当前选定台（未选定为空集，其余台显式勾选）
                                    if (batchMode) {
                                        selectedSerial?.let { selected ->
                                            if (multi.devices.any { it.serial == selected }) {
                                                batchTargets.add(selected)
                                            }
                                        }
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary
                                        .copy(alpha = AppAlpha.hotWash),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                                shape = AppShapes.capsule,
                                label = {
                                    Text("批量采样", style = MaterialTheme.typography.labelMedium)
                                },
                            )
                            if (batchMode) {
                                Text(
                                    if (batchTargets.isEmpty()) "勾选目标设备后开始" else "目标 ${batchTargets.size} 台",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (batchMode) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                                verticalArrangement = Arrangement.spacedBy(Dimens.space6),
                            ) {
                                multi.devices.forEach { device ->
                                    FilterChip(
                                        selected = device.serial in batchTargets,
                                        onClick = {
                                            if (device.serial in batchTargets) {
                                                batchTargets.remove(device.serial)
                                            } else {
                                                batchTargets.add(device.serial)
                                            }
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primary
                                                .copy(alpha = AppAlpha.hotWash),
                                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                                        ),
                                        shape = AppShapes.capsule,
                                        label = {
                                            Text(device.displayLabel, style = MaterialTheme.typography.labelMedium)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        Button(
                            onClick = {
                                if (batchMode) {
                                    sampler.startSamplingToTargets(batchTargets.toList())
                                } else {
                                    sampler.startSampling()
                                }
                            },
                            enabled = if (batchMode) batchTargets.isNotEmpty() else hint == null,
                        ) {
                            AppSymbolIcon(
                                icon = MaterialSymbols.PLAY_ARROW,
                                contentDescription = null,
                                size = Dimens.iconSmall,
                            )
                            Text("开始采样")
                        }
                        if (samplerState.history.points.isNotEmpty()) {
                            Text(
                                text = "上次会话曲线保留中（${samplerState.history.points.size} 点）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 停止态残留曲线同样可导出/送飞书（跨会话保留的历史是主要导出对象）
                        PerfCsvExportActions(
                            hasPoints = csvHasPoints,
                            larkDeliveryReady = larkDeliveryReady,
                            onExportCsv = onExportCsv,
                            onSendCsvToLark = onSendCsvToLark,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 多会话视图体：每 serial 一条图例行（色标 + 尾号 + 五域读数 + 单台停止钮）+
 * 多曲线画布。色标槽位与曲线画布同源（[perfColorSlot]），图例与曲线逐台对色。
 * 单台读数失败拍显示「—」；域级失败明细行不进多会话视图（单台深看走单会话
 * 视图——收掉批量里的单台会话即回到该台单会话视图）。
 */
@Composable
private fun MultiSessionBody(
    samplerState: com.jiaoay.toolbelt.device.PerfSamplerState,
    sampler: PerfSampler,
) {
    val palette = LocalEasyToolboxColors.current.chartPalette
    val overflowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val activeEntries = samplerState.sessions.entries
        .filter { it.value is PerfSessionState.Active }

    /** 图例色标与曲线同槽取色（超色板档位折叠灰，与 [PerfChartMulti] 同口径） */
    fun curveColor(serial: String): Color {
        val slot = perfColorSlot(activeEntries.map { it.key }, serial)
        return if (slot < palette.size) palette[slot] else overflowColor
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        activeEntries.forEach { (serial, _) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                Box(
                    modifier = Modifier
                        .size(Dimens.iconSmall)
                        .clip(AppShapes.small)
                        .background(curveColor(serial)),
                )
                Text(
                    text = serialTailLabel(serial),
                    style = DesktopTypography.mono,
                )
                val serialLatest = samplerState.latestBySerial[serial]
                Text(
                    text = serialLatest?.let(::perfMultiReadingsText) ?: "等待首拍…",
                    style = DesktopTypography.mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { sampler.stopSampling(serial) },
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.STOP_CIRCLE,
                        contentDescription = "停止 ${serialTailLabel(serial)} 的采样",
                        size = Dimens.iconLarge,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 单台整拍失败回执（逐拍可见，不清曲线不断该台会话）
            samplerState.tickErrorBySerial[serial]?.let { tick ->
                Text(
                    text = tick,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PerfChartMulti(
            series = activeEntries.map { (serial, _) ->
                val snapshot = samplerState.historyBySerial[serial]
                PerfCurveSeries(
                    serial = serial,
                    cpu = snapshot?.points?.map { it.cpuPercent } ?: emptyList(),
                    mem = snapshot?.points?.map { it.memUsedPercent } ?: emptyList(),
                    fps = snapshot?.points?.map { it.fps } ?: emptyList(),
                    capacity = snapshot?.capacity ?: 0,
                )
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "曲线",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            Text(
                "每台一色，CPU 实线 · 内存淡化 · FPS 点列（全屏合成）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 单读数列：label + 等宽值 + 可选明细（总量/可用） */
@Composable
private fun ReadingColumn(label: String, value: String, detail: String? = null) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = DesktopTypography.mono)
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 进程排行表：排序 chips + 前 N 行（进程域失败时呈现原因行，禁静默空表） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProcessTable(
    processes: List<com.jiaoay.toolbelt.device.ProcessEntry>?,
    sortMode: PerfSortMode,
    onSortModeChange: (PerfSortMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
        // 窄窗 chip 换行兜底（普通 Row 行尾 chip 被裁出视口不可达）
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
            verticalArrangement = Arrangement.spacedBy(Dimens.space4),
        ) {
            Text(
                "进程排行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PerfSortMode.entries.forEach { mode ->
                FilterChip(
                    selected = sortMode == mode,
                    onClick = { onSortModeChange(mode) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary
                            .copy(alpha = AppAlpha.hotWash),
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = AppShapes.capsule,
                    label = {
                        Text(text = mode.label, style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
        if (processes == null) {
            Text(
                "进程读数不可用（见上方失败明细）",
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            sortProcesses(processes, byCpu = sortMode == PerfSortMode.CPU)
                .take(PROCESS_TOP_N)
                .forEach { entry ->
                    Text(
                        text = processRowText(entry),
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
    }
}

/**
 * 曲线导出数据判据（导出/送飞书钮的组合门控真源）：任一 serial 的保留历史有
 * 采样点即可导出——曲线历史跨会话按 serial 续存（会话结束后保留），判据读全表
 * 而非单会话投影（多会话/残留曲线同为导出对象）。
 */
internal fun perfHistoryHasPoints(state: PerfSamplerState): Boolean =
    state.historyBySerial.values.any { it.points.isNotEmpty() }

/**
 * 曲线「送飞书」置灰判据（纯函数：null = 可发送；非 null = 置灰原因——按钮禁用态
 * tooltip 直接消费，禁静默置灰）。真源 = ReportDelivery.ready 单源（桥接未接线/
 * 未运行恒 false）。
 */
internal fun perfLarkSendDisabledReason(larkDeliveryReady: Boolean): String? =
    if (larkDeliveryReady) null else "飞书桥接未运行，请先在飞书桥接页启动桥接"

/**
 * 曲线历史导出写盘（导出/送飞书共用入口）：全 serial 保留历史 → CSV →
 * `<home>/Toolbelt/perf/`（行结构见 [PerfCsvExporter] 对象 KDoc）。多会话导出
 * 粒度 = 单 CSV 承载全部有数据的 serial（serial 列分组）——单次点击捕获全部
 * 可见曲线，单/多会话视图共用同一动作面。
 *
 * - 空数据集拒绝并给失败人话（面板按钮缺席的兜底守卫，禁产出只有头行的壳文件）；
 * - 目录创建失败/写盘异常归 [Result.failure]，人话由 [perfExportFailureText]
 *   归一回执（不静默）。[home]/[now] 参数化供单测注入。
 */
internal fun writePerfCsvExport(
    state: PerfSamplerState,
    home: File = File(System.getProperty("user.home")),
    now: Long = System.currentTimeMillis(),
): Result<File> = runCatching {
    val histories = state.historyBySerial.filterValues { it.points.isNotEmpty() }
    check(histories.isNotEmpty()) { "无可导出的曲线数据（无采样历史或曲线已清空）" }
    val csv = PerfCsvExporter.toCsv(histories)
    val dir = PerfCsvExporter.exportDir(home)
    check(dir.isDirectory || dir.mkdirs()) { "产物目录创建失败：${dir.absolutePath}" }
    val file = PerfCsvExporter.resolveExportFile(dir, now)
    file.writeText(csv, Charsets.UTF_8)
    file
}

/** 导出成功回执：带产物完整路径（可复制直开/拖进工单） */
internal fun perfExportReceipt(file: File): String = "已导出性能曲线：${file.absolutePath}"

/** 导出失败回执：人话原因归一（异常 message 缺席时退类名，禁空回执） */
internal fun perfExportFailureText(e: Throwable): String =
    "导出失败：${e.message ?: e.javaClass.simpleName}"

/**
 * 曲线导出动作行（运行态与停止态残留曲线两处共用）：导出 CSV + 送飞书（导出后
 * 以文件消息直发桥接接收会话——发送前恒重新导出即恒最新曲线）。无数据时整行
 * 缺席（无可导出内容不出现死按钮）；送飞书置灰判据 =
 * [perfLarkSendDisabledReason]（禁用态 hover tooltip 明示原因，disabled 按钮
 * 不发 Hover 事件，hoverable 挂外层——契约见 HoverTooltipBox KDoc）。
 */
@Composable
private fun PerfCsvExportActions(
    hasPoints: Boolean,
    larkDeliveryReady: Boolean,
    onExportCsv: () -> Unit,
    onSendCsvToLark: () -> Unit,
) {
    if (!hasPoints) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        OutlinedButton(onClick = onExportCsv) {
            AppSymbolIcon(
                icon = MaterialSymbols.FILE_DOWNLOAD,
                contentDescription = null,
                size = Dimens.iconSmall,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("导出 CSV")
        }
        val disabledReason = perfLarkSendDisabledReason(larkDeliveryReady)
        if (disabledReason == null) {
            OutlinedButton(onClick = onSendCsvToLark) {
                AppSymbolIcon(
                    icon = MaterialSymbols.SEND,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("送飞书")
            }
        } else {
            HoverTooltipBox(
                tooltip = {
                    Text(
                        text = disabledReason,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            ) { interactionSource ->
                OutlinedButton(
                    onClick = onSendCsvToLark,
                    enabled = false,
                    modifier = Modifier.hoverable(interactionSource),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.SEND,
                        contentDescription = null,
                        size = Dimens.iconSmall,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("送飞书")
                }
            }
        }
    }
}
