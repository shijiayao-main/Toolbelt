package com.jiaoay.easy_dev_toolbox.page.deviceobs

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.AppConfirmDialog
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.PillLabel
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import java.util.Locale
import com.jiaoay.toolbelt.device.DevicePackageDetails
import com.jiaoay.toolbelt.device.DevicePackageEntry
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource

/**
 * 设备应用面板（设备观测空间「应用」子页承载，宿主层内容组件——只服务观测页，
 * 不入 feature 模块）：过滤行（包名搜索 + 系统过滤三态）+ 包列表（包名等宽 +
 * versionCode + uid + 系统标记 pill）+ 选中行展开详情卡（懒加载，按当前设备
 * 会话级缓存）+ 失败显式回执。
 *
 * - VM/面板分离（文件浏览面板同配方）：列表/过滤/详情状态在
 *   [DevicePackageViewModel]（请求-回执模型无轮询环，契约见其类头），本面板只做
 *   呈现与动作转发；
 * - 子页独立承载全幅：包列表 weight(1f) 占满子页剩余高度（工作区型面板——
 *   全量包列表持续可视，不与观测台成员争同屏空间，有界高度由页面骨架吸顶分支
 *   供给）；
 * - 目标设备 = 设备域选定串号（与文件浏览同源）；无可采目标时按 [perfPanelHint]
 *   横幅提示（动作入口在页级设备栏，与文件面板同口径）；
 * - 包列表是全部包视角（预装/后装判别走系统标记 pill，非「第三方」假设——
 *   模型契约见 [DevicePackageEntry]）；
 * - 详情按选中再拉取（展开卡内 busy 进度位 → 字段面/失败回执），重复展开命中
 *   会话级缓存零二次 spawn；同包再点收起；
 * - 快照对比（会话级内存快照不落盘）：保存快照定格当前装载列表，与快照对比出
 *   三分类回执（新增/移除/版本变更，跨串号对比先确认——两台设备预装差异场景）；
 * - 列表条目数规模为单命令全量输出（百条量级），过滤是纯投影零内核请求。
 *
 * 视觉层为保守默认（等宽包名行 + 令牌化 pill/深底详情卡），真机手感由维护者终裁。
 */
@Composable
fun DevicePackagePanel(
    devices: DeviceStateSource,
    sharedViewModel: DevicePackageViewModel? = null,
    onGoSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 观测页页级持有并传入（与文件浏览 VM 同配方）；独立组合形态下自建同 key 实例
    // （采样器 StateFlow 同接页级单源——运行态融合只读消费）
    val viewModel = sharedViewModel ?: viewModel(key = "device-package-browser") {
        DevicePackageViewModel(
            devices,
            DeviceHub.packageBrowser::listPackages,
            DeviceHub.packageBrowser::packageDetails,
            perfState = DeviceHub.perfSampler.state,
        )
    }
    val state by viewModel.state.collectAsState()
    val deviceState by devices.deviceState.collectAsState()
    val detectError by devices.detectError.collectAsState()
    val selectedSerial by devices.selectedSerial.collectAsState()

    val hint = perfPanelHint(deviceState, detectError, selectedSerial)
    val targetSerial = when (val ds = deviceState) {
        is DeviceState.Single -> ds.serial
        is DeviceState.Multi -> selectedSerial
        is DeviceState.None -> null
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        TonalCard(
            contentPaddingVertical = Dimens.space12,
            contentPaddingHorizontal = Dimens.space16,
            contentSpacing = Dimens.space8,
        ) {
            // 头部行：图标 + 标题 + 条目计数 + 目标尾号 + 刷新
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppSymbolIcon(
                    icon = MaterialSymbols.APPS,
                    contentDescription = null,
                    size = Dimens.iconMedium,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(Dimens.space8))
                // 标题恒内容宽——勿与行尾 Spacer 同挂 weight：双 weight 配额平分后
                // 尾件堆在行中部、右缘悬空
                Text(
                    text = "设备应用",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.entries != null) {
                    Spacer(modifier = Modifier.width(Dimens.space8))
                    Text(
                        // 过滤投影计数（命中/全量）：全量包视角下的过滤命中面即时可读
                        text = "${state.filtered.size}/${state.entries!!.size}",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                targetSerial?.let { serial ->
                    Text(
                        text = "目标 ${serialTailLabel(serial)}",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(Dimens.space8))
                }
                IconButton(
                    onClick = { viewModel.refresh() },
                    enabled = !state.loading && targetSerial != null,
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.REFRESH,
                        contentDescription = "刷新包列表",
                        size = Dimens.iconLarge,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 无可采目标提示（动作入口与面板外资源对齐——去设置经中立槽接线，
            // 选设备/刷新在页级设备栏，消息文案自指引）
            hint?.let { h ->
                StatusBanner(
                    status = StatusBannerStatus.Error,
                    text = h.message,
                    trailing = {
                        if (h.action == PerfPanelHintAction.GO_SETTINGS) {
                            onGoSettings?.let { go ->
                                TextButton(onClick = go) { Text("去设置配置 adb 路径") }
                            }
                        }
                    },
                )
            }

            // 失败回执：设备侧错误原文 + 原地重试
            state.error?.let { error ->
                StatusBanner(
                    status = StatusBannerStatus.Warning,
                    text = error,
                    trailing = {
                        TextButton(
                            onClick = { viewModel.refresh() },
                            enabled = targetSerial != null && !state.loading,
                        ) { Text("重试") }
                    },
                )
            }

            // 采样引导 hint（派生态零新增状态字段，判据见 [samplingHintText]）：
            // 轻量提示一行，投影出现即隐藏
            samplingHintText(entriesLoaded = state.entries != null, runtimeByPackage = state.runtimeByPackage)
                ?.let { text ->
                    Text(
                        text = text,
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            // 过滤行：包名搜索 + 系统过滤三态（纯投影零内核请求）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                SearchField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "按包名过滤（如 com.example）",
                    enabled = state.entries != null,
                    modifier = Modifier.weight(1f),
                )
                SegmentedControl(
                    options = DevicePackageSystemFilter.entries.map { it.label },
                    selectedIndex = state.systemFilter.ordinal,
                    onSelect = { index -> viewModel.setSystemFilter(DevicePackageSystemFilter.entries[index]) },
                    enabled = state.entries != null,
                )
            }

            // 排序档（性能面板进程排行排序档同款形态）：按包名 = 既有列举序；
            // 按 CPU / 按内存消费运行态投影（未采样时各档等价——无读数的包沉底保原序）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
            ) {
                Text(
                    "排序",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DevicePackageSortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.sortMode == mode,
                        onClick = { viewModel.setSortMode(mode) },
                        enabled = state.entries != null,
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

            // 快照对比行（会话级内存快照不落盘）：保存快照 = 当前装载列表定格为
            // 对比基线；与快照对比 = 三分类 diff（新增/移除/版本变更）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                OutlinedButton(
                    onClick = viewModel::saveSnapshot,
                    enabled = state.entries != null && !state.loading,
                ) { Text("保存快照") }
                OutlinedButton(
                    onClick = viewModel::requestCompare,
                    enabled = state.snapshot != null && state.entries != null && !state.loading,
                ) { Text("与快照对比") }
                state.snapshot?.let { snap ->
                    Text(
                        text = "快照 ${serialTailLabel(snap.serial)} · ${snapshotTimeText(snap.timestampMillis)} · ${snap.entries.size} 包",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 对比回执区（静态产物——对比时刻的两份列表差，不随列表后续变更自动重算）
            val diffResult = state.diff as? DevicePackageDiffUiState.Result
            diffResult?.let { result ->
                DevicePackageDiffSection(
                    result = result,
                    onClose = viewModel::dismissDiff,
                )
            }

            // 局部锚定防委托属性 smart cast 失效（state 是 collectAsState 委托值）
            val entries = state.entries
            when {
                entries == null -> if (state.loading) {
                    Text(
                        "读取中…",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                entries.isEmpty() -> Text(
                    "（设备零包）",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.filtered.isEmpty() -> Text(
                    "（无匹配包——调整过滤词或系统过滤档）",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> LazyColumn(
                    // 工作区型占满子页剩余高度（weight 链由吸顶骨架供有界高度）
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    items(state.filtered, key = { it.packageName }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space4)) {
                            DevicePackageEntryRow(
                                entry = entry,
                                selected = state.selected == entry.packageName,
                                runtime = state.runtimeByPackage[entry.packageName],
                                onSelect = viewModel::selectPackage,
                            )
                            if (state.selected == entry.packageName) {
                                DevicePackageDetailCard(
                                    detailsState = state.details,
                                    packageName = entry.packageName,
                                    exportState = state.export,
                                    toggleState = state.toggle,
                                    selectedDisabled = state.selectedDisabled,
                                    onExport = viewModel::requestExport,
                                    onCancelExport = viewModel::cancelExport,
                                    onDisable = viewModel::requestDisable,
                                    onEnable = viewModel::requestEnable,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 禁启高危动作确认对话（内核被调用即执行，确认分层在 VM）：确认才执行，
    // 取消零动作零 spawn——与推送覆盖确认同款分层
    val toggleState = state.toggle
    when (toggleState) {
        is DevicePackageToggleUiState.DisableConfirmPending -> AppConfirmDialog(
            title = "禁用系统应用确认",
            text = "禁用系统应用可能导致设备功能异常，确认继续？（${toggleState.packageName}）",
            onConfirm = viewModel::confirmToggle,
            onDismiss = viewModel::dismissToggleConfirm,
            confirmLabel = "禁用",
            isDangerous = true,
        )

        is DevicePackageToggleUiState.EnableConfirmPending -> AppConfirmDialog(
            title = "启用系统应用确认",
            text = "启用系统应用将改变设备运行状态，确认继续？（${toggleState.packageName}）",
            onConfirm = viewModel::confirmToggle,
            onDismiss = viewModel::dismissToggleConfirm,
            confirmLabel = "启用",
            isDangerous = true,
        )

        else -> Unit
    }

    // 跨串号对比确认（快照与当前列表来自不同设备——两台设备预装差异的场景本体，
    // 但串号供用户判断是否拿错了基线）：确认才对比，取消零动作
    val diffPending = state.diff as? DevicePackageDiffUiState.CrossSerialConfirmPending
    diffPending?.let { pending ->
        AppConfirmDialog(
            title = "跨设备对比确认",
            text = "快照来自设备 ${pending.snapshotSerial}，当前列表来自设备 ${pending.currentSerial}" +
                "——不同设备的预装差异对比，确认继续？",
            onConfirm = viewModel::confirmCompare,
            onDismiss = viewModel::dismissDiff,
            confirmLabel = "对比",
        )
    }
}

/**
 * 运行态徽标文本：CPU 主数据（1 位小数百分比聚合值）+ 内存副数据（RSS 聚合
 * 人话文本，与进程排行表 [rssHumanText] 同口径）；多进程应用附 ×N（进程数旁证）。
 */
internal fun runtimeBadgeText(runtime: DevicePackageRuntime): String =
    String.format(Locale.ROOT, "CPU %.1f%%", runtime.cpuPercent) +
        " · 内存 ${rssHumanText(runtime.memKb)}" +
        if (runtime.processCount > 1) " ×${runtime.processCount}" else ""

/**
 * 采样引导 hint 文本（internal 供单测）：应用面板的运行态徽标只在性能面板采样
 * 活跃时出现，投影为空（未采样/串号不匹配/会话收束/进程域失败）时首次用户
 * 无从得知徽标从何而来——列表已装载但零投影时提示一行；有投影（null = 隐藏）
 * 或列表未装载（无可采目标另有 [perfPanelHint] 横幅，不叠加）时不提示。
 */
internal fun samplingHintText(
    entriesLoaded: Boolean,
    runtimeByPackage: Map<String, DevicePackageRuntime>,
): String? =
    if (entriesLoaded && runtimeByPackage.isEmpty()) {
        "性能面板开始采样后，此处列表将显示各应用的 CPU 与内存占用徽标"
    } else {
        null
    }

/** 快照时刻呈现（本机时区 MM-dd HH:mm:ss——会话级快照短跨度，月日 + 时刻足够定位） */
internal fun snapshotTimeText(timestampMillis: Long): String =
    SNAPSHOT_TIME_FORMATTER.format(java.time.Instant.ofEpochMilli(timestampMillis))

private val SNAPSHOT_TIME_FORMATTER = java.time.format.DateTimeFormatter
    .ofPattern("MM-dd HH:mm:ss")
    .withZone(java.time.ZoneId.systemDefault())

/**
 * 快照对比回执区（过滤行与列表之间的深底内嵌区，与详情卡同形态）：三分类计数行
 * （新增/移除/版本变更）+ 三段等宽清单（diff 已按包名稳定排序）；全空三分类呈现
 * 「无差异」；关闭钮收起回执（快照保留——收起不是丢弃基线）。
 *
 * 高度封顶 + 内部自滚：本区夹在头部行与 weight(1f) 包列表之间，清单无界全量渲染
 * 会把包列表挤出视口——封顶后本区自滚、列表恒留可视面。档位复用
 * [Dimens.mcpLogHeightMax]（240dp：同族「封顶后列表内部自滚」语义的既有档）；
 * padding 在 verticalScroll 之前——内容裁切于背景内缘 12dp，不越过圆角。
 */
@Composable
private fun DevicePackageDiffSection(
    result: DevicePackageDiffUiState.Result,
    onClose: () -> Unit,
) {
    val diff = result.diff
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.mcpLogHeightMax)
            .background(
                color = LocalToolboxSurfaceColors.current.surfaceDeep,
                shape = AppShapes.medium,
            )
            .padding(Dimens.space12)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text(
                text = "对比快照 ${serialTailLabel(result.snapshot.serial)} · 当前 ${serialTailLabel(result.currentSerial)}" +
                    " · 新增 ${diff.added.size} · 移除 ${diff.removed.size} · 版本变更 ${diff.versionChanged.size}",
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("关闭") }
        }
        if (diff.added.isEmpty() && diff.removed.isEmpty() && diff.versionChanged.isEmpty()) {
            Text(
                text = "无差异——两份列表逐包一致（同包名同 versionCode）",
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (diff.added.isNotEmpty()) {
                Text(
                    text = "新增（${diff.added.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diff.added.forEach { added ->
                    Text(
                        text = added.packageName,
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (diff.removed.isNotEmpty()) {
                Text(
                    text = "移除（${diff.removed.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diff.removed.forEach { removed ->
                    Text(
                        text = removed.packageName,
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (diff.versionChanged.isNotEmpty()) {
                Text(
                    text = "版本变更（${diff.versionChanged.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                diff.versionChanged.forEach { change ->
                    Text(
                        text = "${change.packageName}: vCode ${change.snapshotVersionCode} → ${change.currentVersionCode}",
                        style = DesktopTypography.mono,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 单包条目行：包名等宽主体 + versionCode + uid 右缘列 + 系统包 pill 徽标 +
 * 运行态徽标（[runtime] 非空 = 采样器最近一拍有该包的匹配进程——CPU 与内存
 * 聚合读数；null = 未采样/未匹配/串号不匹配，按缺席呈现不猜）；整行可点（选中
 * 展开详情卡，再点收起——切换语义在 VM）。
 */
@Composable
private fun DevicePackageEntryRow(
    entry: DevicePackageEntry,
    selected: Boolean,
    runtime: DevicePackageRuntime?,
    onSelect: (DevicePackageEntry) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(entry) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        AppSymbolIcon(
            icon = MaterialSymbols.APPS,
            contentDescription = null,
            size = Dimens.iconSmall,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // 头部簇 = 包名 + 邻接徽标，包一层 weight(1f) 吃满配额（尾件恒贴行尾）；
        // 包名内层 fill=false 保持徽标邻接、长包名在簇内配额 ellipsis。
        // 勿回退「包名 fill=false + 尾部 Spacer(weight)」双 weight 形态：配额平分
        // 后尾件堆在行中部、右缘悬空
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Text(
                text = entry.packageName,
                style = DesktopTypography.mono,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // 运行态徽标：CPU 主数据 + 内存副数据（主+子进程聚合求和），主色轻底与
            // 排序档选中态同族
            runtime?.let { r ->
                PillLabel(
                    text = runtimeBadgeText(r),
                    container = MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.hotWash),
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            }
            if (entry.isSystem) {
                PillLabel(
                    text = "系统",
                    container = LocalToolboxSurfaceColors.current.surfaceDeep,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "vCode ${entry.versionCode}",
            style = DesktopTypography.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "uid ${entry.uid}",
            style = DesktopTypography.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 选中包的详情展开卡（选中行正下方的深底内嵌区）：busy 进度位 →
 * [DevicePackageDetails] 最小字段面（版本名/安装时间/更新时间/代码路径/声明权限数）
 * + 动作区（导出 APK / 禁启——禁启钮按探测的当前禁用态显其一，探测未知不出）
 * / 失败回执。字段面是解析层刻意收窄的最小集（扩字段须先有消费面——模型契约见
 * [DevicePackageDetails]）；versionName null 语义 = manifest 未设置，呈现
 * 「未设置」不编默认值。
 */
@Composable
private fun DevicePackageDetailCard(
    detailsState: DevicePackageDetailsUiState,
    packageName: String,
    exportState: DevicePackageExportUiState,
    toggleState: DevicePackageToggleUiState,
    selectedDisabled: Boolean?,
    onExport: (String) -> Unit,
    onCancelExport: () -> Unit,
    onDisable: (String) -> Unit,
    onEnable: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = LocalToolboxSurfaceColors.current.surfaceDeep,
                shape = AppShapes.medium,
            )
            .padding(Dimens.space12),
        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
    ) {
        when (detailsState) {
            DevicePackageDetailsUiState.Idle -> Unit

            is DevicePackageDetailsUiState.Busy -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(Dimens.iconSmall))
                Text(
                    text = "详情读取中 ${packageName}…",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            is DevicePackageDetailsUiState.Ready -> {
                DevicePackageDetailFields(detailsState.details)
                DevicePackageDetailActions(
                    packageName = packageName,
                    exportState = exportState,
                    toggleState = toggleState,
                    selectedDisabled = selectedDisabled,
                    onExport = onExport,
                    onCancelExport = onCancelExport,
                    onDisable = onDisable,
                    onEnable = onEnable,
                )
            }

            is DevicePackageDetailsUiState.Failed -> StatusBanner(
                status = StatusBannerStatus.Warning,
                text = detailsState.message,
            )
        }
    }
}

/**
 * 详情卡动作区：导出 APK（多 split 回执逐文件列）+ 禁启钮（按探测的当前禁用态
 * 显其一，null = 探测未完成/失败不出钮——状态未知不猜）+ 动作回执（busy 进度
 * 位 / 完成人话 / 失败原文）。动作态由 VM 复位（换选中/收起/换设备），本区只呈现
 * 当前选中包的动作语境。
 */
@Composable
private fun DevicePackageDetailActions(
    packageName: String,
    exportState: DevicePackageExportUiState,
    toggleState: DevicePackageToggleUiState,
    selectedDisabled: Boolean?,
    onExport: (String) -> Unit,
    onCancelExport: () -> Unit,
    onDisable: (String) -> Unit,
    onEnable: (String) -> Unit,
) {
    val actionBusy = toggleState is DevicePackageToggleUiState.Busy ||
        exportState is DevicePackageExportUiState.Busy
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
    ) {
        OutlinedButton(
            onClick = { onExport(packageName) },
            enabled = !actionBusy,
        ) {
            AppSymbolIcon(
                icon = MaterialSymbols.FILE_DOWNLOAD,
                contentDescription = null,
                size = Dimens.iconSmall,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("导出 APK")
        }
        selectedDisabled?.let { disabled ->
            val label = if (disabled) "启用" else "禁用"
            OutlinedButton(
                onClick = { if (disabled) onEnable(packageName) else onDisable(packageName) },
                enabled = !actionBusy,
            ) {
                AppSymbolIcon(
                    icon = if (disabled) MaterialSymbols.POWER_SETTINGS_CIRCLE else MaterialSymbols.BLOCK,
                    contentDescription = null,
                    size = Dimens.iconSmall,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(label)
            }
        }
    }

    when (exportState) {
        DevicePackageExportUiState.Idle -> Unit

        is DevicePackageExportUiState.Busy -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(Dimens.iconSmall))
            Text(
                text = if (exportState.fileCount == 0) {
                    "APK 路径解析中 ${packageName}…"
                } else {
                    "导出中 ${exportState.fileIndex + 1}/${exportState.fileCount} ${exportState.fileName}" +
                        "（期望 ${deviceFileSizeText(exportState.expectedSizeBytes)}）…"
                },
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancelExport) { Text("取消") }
        }

        is DevicePackageExportUiState.Done -> Column(
            verticalArrangement = Arrangement.spacedBy(Dimens.space2),
        ) {
            Text(
                text = "已导出 ${exportState.receipt.files.size} 个文件到下载目录：",
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurface,
            )
            exportState.receipt.files.forEach { file ->
                Text(
                    text = "${file.localPath}（${deviceFileSizeText(file.sizeBytes)}）",
                    style = DesktopTypography.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row {
                TextButton(
                    onClick = { DesktopUtils.openInFolder(exportState.receipt.files.first().localPath) },
                ) { Text("打开所在文件夹") }
            }
        }

        is DevicePackageExportUiState.Failed -> StatusBanner(
            status = StatusBannerStatus.Warning,
            text = exportState.message,
        )
    }

    when (toggleState) {
        DevicePackageToggleUiState.Idle -> Unit

        is DevicePackageToggleUiState.Busy -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(Dimens.iconSmall))
            Text(
                text = "${toggleState.actionText}中 ${toggleState.packageName}…",
                style = DesktopTypography.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is DevicePackageToggleUiState.Done -> StatusBanner(
            status = StatusBannerStatus.Success,
            text = toggleState.message,
        )

        is DevicePackageToggleUiState.Failed -> StatusBanner(
            status = StatusBannerStatus.Warning,
            text = toggleState.message,
        )

        else -> Unit
    }
}

/** 详情字段面（label 前缀 + 等宽值整行，时间原文即设备本地时区读数——模型不换算） */
@Composable
private fun DevicePackageDetailFields(details: DevicePackageDetails) {
    DetailFieldRow("版本名", details.versionName ?: "未设置")
    DetailFieldRow("安装时间", details.firstInstallTime)
    DetailFieldRow("更新时间", details.lastUpdateTime)
    DetailFieldRow("代码路径", details.codePath)
    DetailFieldRow("声明权限", "${details.requestedPermissionsCount} 项")
}

@Composable
private fun DetailFieldRow(label: String, value: String) {
    Text(
        text = "$label  $value",
        style = DesktopTypography.mono,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
