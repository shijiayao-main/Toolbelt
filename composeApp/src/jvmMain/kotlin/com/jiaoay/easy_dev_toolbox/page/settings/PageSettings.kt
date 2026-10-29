package com.jiaoay.easy_dev_toolbox.page.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellCommandBuilder
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellType
import com.jiaoay.easy_dev_toolbox.core.initializer.InitializerTiming
import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializerManager
import com.jiaoay.easy_dev_toolbox.core.log.LogbackHelper
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.host.EasyDataManager
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeMode
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.currentWindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import dev.vicart.compose.material.symbols.MaterialSymbols
import com.jiaoay.easy_dev_toolbox.widget.AppSwitchRow
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.SettingsPaneSection
import com.jiaoay.easy_dev_toolbox.widget.SettingsTwoPane
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.CopyIconButton
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
@Composable
fun PageSettings(appViewModel: AppViewModel = AppViewModel.getViewModel()) {
    val currentScheme by appViewModel.colorScheme.collectAsState()
    val currentThemeMode by appViewModel.themeMode.collectAsState()
    val currentShellType by appViewModel.shellType.collectAsState()
    // 材质口径：chrome 恒实底、浮层恒真玻璃（glassEnabled 开关已退役）
    val auroraEnabled by appViewModel.auroraEnabled.collectAsState()

    val scrollState = rememberScrollState()
    val enableWriteFile by LogbackHelper.enableWriteFile.collectAsState()
    val debugPageEnabled by appViewModel.debugPageEnabled.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 图片提取状态
    // 状态机化（ExtractThemeState）——失败原因对 UI 可见（失败只进 JLog 会令
    // 用户拖入坏图后无任何提示）
    var extractState by remember { mutableStateOf<ExtractThemeState>(ExtractThemeState.Idle) }
    // 色轮选择器
    var showColorPicker by remember { mutableStateOf(false) }

    // 设置页双栏化（左分类导航 + 右内容舱）。
    // 三分类目：外观（外观模式/环境背景/配色方案）/
    // 环境（Shell 类型/ADB 路径）/ 高级（日志存储/开发者选项）——七组 SettingsSection
    // 组卡结构保留（Shell 类型 Windows 条件不渲染 = Windows 下六组可见），原样平移进右舱。
    // 窄窗回落分支 = sections 依序全量单列。
    // Shell 类型 Windows 条件分支平移进「环境」分类内部，条件语义不变。
    val sections = listOf(
        SettingsPaneSection(
            title = "外观",
            icon = MaterialSymbols.PALETTE,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space14)) {
                // ── 外观模式 ──
                SettingsSection(title = "外观模式", desc = "跟随系统或手动指定深浅色，更改即时生效") {
                    SegmentedControl(
                        options = ThemeMode.entries.map { it.displayName },
                        selectedIndex = ThemeMode.entries.indexOf(currentThemeMode),
                        onSelect = { appViewModel.setThemeMode(ThemeMode.entries[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ── 环境背景（玻璃开关已退役：blur 只属浮层且恒真玻璃，无「全局玻璃」
                //     语义可关；aurora 光斑独立成节）──
                SettingsSection(
                    title = "环境背景",
                    desc = "内容区环境光斑（aurora）——关闭后停绘光斑回纯色背景"
                ) {
                    // AppSwitchRow（frow+switch）三段形态：
                    // lab 190 定宽 + hint 中段 + switch 右缘（frow=true 内部自排三段）
                    AppSwitchRow(
                        label = "环境背景",
                        checked = auroraEnabled,
                        onCheckedChange = { checked -> appViewModel.setAuroraEnabled(checked) },
                        frow = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.medium)
                            .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
                        hoverable = true,
                        hoverShape = AppShapes.medium,
                    )
                }

                // ── 配色方案 ──
                SettingsSection(title = "配色方案", desc = "预设主题 / 自定义种子色 / 从图片提取，更改即时生效") {
                    // 预设按题材分组（AppThemeSpec.groupedPresets 单源——UI 不自持清单，
                    // 任意增删预设自动跟随）；非 Compact 分组双列网格 / Compact 按组单列行式，
                    // 分组导航语义两态保留。预设选择是「横向比较色板」心智，双列卡纵高约减半。
                    // 门槛不锁 Wide 的宽度依据：Medium 下限 1024dp 扣让位/边距后单卡仍有
                    // ~400dp 可用（ThemeCard 最小内容 ~170dp），双列放得下。
                    if (currentWindowSizeClass() != WindowSizeClass.Compact) {
                        // 组间 space14（与「外观」分类区块间距同档）；组内标题 space8、
                        // 卡行 space10——.theme-grid 行列均 10px：
                        // 网格纵距 10——横距走 10、纵距若用 content space8 即漂移
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.space14)) {
                            AppThemeSpec.groupedPresets.forEach { (group, specs) ->
                                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                                    ThemeGroupLabel(group.label)
                                    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space10)) {
                                        specs.chunked(2).forEach { pair ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space10)) {
                                                ThemeCard(
                                                    spec = pair.first(),
                                                    selected = currentScheme == pair.first(),
                                                    onSelected = { appViewModel.setColorScheme(pair.first()) },
                                                    modifier = Modifier.weight(1f),
                                                )
                                                // 孤行兜底配方组内化：奇数组末行仅剩左卡时独占整行宽
                                                // （weight(1f) 无右伴即满幅）、不渲染占位卡（引用判重，不依赖总数奇偶）
                                                pair.getOrElse(1) { pair.first() }.let { second ->
                                                    if (second !== pair.first()) {
                                                        ThemeCard(
                                                            spec = second,
                                                            selected = currentScheme == second,
                                                            onSelected = { appViewModel.setColorScheme(second) },
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Compact 回落：按组重复组标题 + 单列行式（复用 ThemeOptionItem hover 配方）
                        AppThemeSpec.groupedPresets.forEach { (group, specs) ->
                            ThemeGroupLabel(group.label)
                            specs.forEach { spec ->
                                ThemeOptionItem(
                                    name = spec.displayName,
                                    selected = currentScheme == spec,
                                    onSelected = { appViewModel.setColorScheme(spec) },
                                    leading = { ThemeColorDots(spec) },
                                )
                            }
                        }
                    }
                    // 自定义项：选中或未设置均可点击打开色轮
                    CustomThemeItem(
                        currentScheme = currentScheme,
                        onClick = { showColorPicker = true },
                    )
                    // 从图片提取（失败经状态机反馈——StatusBanner 收口）
                    ImageThemeCard(
                        extracting = extractState is ExtractThemeState.Extracting,
                        onImageSelected = { file ->
                            extractState = ExtractThemeState.Extracting
                            scope.launch(Dispatchers.IO) {
                                extractState = extractSeedFromImage(file).fold(
                                    onSuccess = { ExtractThemeState.Done(it) },
                                    onFailure = { ExtractThemeState.Failed(it.message ?: "未知错误") },
                                )
                            }
                        },
                    )
                    (extractState as? ExtractThemeState.Failed)?.let { failed ->
                        // 安抚句「已拖入文件不受影响，可再次拖放」（稿四要素尾句）——
                        // 补「现在能做什么」的确定性（现有配置不受影响）
                        StatusBanner(
                            status = StatusBannerStatus.Error,
                            text = "图片提取失败：${failed.message}——换一张 png/jpg 图片重试。" +
                                "已拖入文件不受影响，可再次拖放。",
                        )
                    }
                }
            }
        },
        SettingsPaneSection(
            title = "环境",
            icon = MaterialSymbols.TERMINAL,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space14)) {
                // ── Shell 类型 ──
                if (!ShellCommandBuilder.isWindows) {
                    SettingsSection(
                        title = "Shell 类型",
                        desc = "选择执行 Shell 命令时使用的 Shell，需与你配置 PATH 的 Shell 一致"
                    ) {
                        SegmentedControl(
                            options = ShellType.entries.map { it.displayName },
                            selectedIndex = ShellType.entries.indexOf(currentShellType),
                            onSelect = { appViewModel.setShellType(ShellType.entries[it]) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // ── ADB 路径 ──
                SettingsSection(
                    title = "ADB 路径",
                    desc = "Shell 工具页命令与设备检测使用的 adb 可执行文件路径，留空使用系统 PATH 中的 adb"
                ) {
                    AdbPathSetting()
                }
            }
        },
        SettingsPaneSection(
            title = "高级",
            icon = MaterialSymbols.TUNE,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.space14)) {
                // ── 日志存储 ──
                // desc 无 markdown 字面量直出 + 路径独立 mono 行 + 一键复制
                //（McpExposePage 端点行同款配方）。
                SettingsSection(
                    title = "开启日志存储",
                    desc = "开启后在下方目录生成日志文件"
                ) {
                    // 开关行（frow+switch）同款三段形态：
                    // lab 190 定宽 + hint 中段 + switch 右缘（frow=true 内部自排三段）
                    AppSwitchRow(
                        // label 恒定宾语——状态已由 switch 表达，随态翻转「开启/关闭」双重冗余
                        label = "记录到日志文件",
                        checked = enableWriteFile,
                        onCheckedChange = { checked ->
                            if (checked) LogbackHelper.enableFileLog() else LogbackHelper.disableFileLog()
                            // 持久化开关状态，跨重启保留（与 setDebugPageEnabled 等设置项模式一致）
                            scope.launch(Dispatchers.IO) {
                                EasyDataManager.setLogFileEnabled(checked)
                            }
                        },
                        frow = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.medium)
                            .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
                        hoverable = true,
                        hoverShape = AppShapes.medium,
                    )
                    // 日志目录路径独立行——mono 数据字 + CopyIconButton 一键复制
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        Text(
                            text = FileUtils.getLogDir().absolutePath,
                            style = DesktopTypography.mono,
                            color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
                            modifier = Modifier.weight(1f),
                        )
                        CopyIconButton(
                            textProvider = { FileUtils.getLogDir().absolutePath },
                            contentDescription = "复制日志目录",
                        )
                    }
                }

                // ── 产物目录 ──
                // 可见产物树根（报告/录屏/快照/性能/logcat 等交付物）：留空走默认
                // ~/Documents/Toolbelt；改后即时生效无需重启，存量产物不迁移。
                SettingsSection(
                    title = "产物目录",
                    desc = "报告、录屏、快照、性能、logcat 等交付物的落盘根目录，留空使用默认 ~/Documents/Toolbelt"
                ) {
                    ExportRootSetting(appViewModel = appViewModel)
                }

                // ── 开发者 ──
                //     desc 对齐 dock 词汇 + 开关开启后组内条件直达行（改完即时可验证，不用自己找入口）。
                SettingsSection(
                    title = "开发者选项",
                    desc = "开启后在左侧 dock 系统区与命令面板显示 Shell 调试页"
                ) {
                    // 开关行（frow+switch）同款三段形态：
                    // lab 190 定宽 + hint 中段 + switch 右缘（frow=true 内部自排三段）
                    AppSwitchRow(
                        label = "显示调试入口",
                        checked = debugPageEnabled,
                        onCheckedChange = { checked -> appViewModel.setDebugPageEnabled(checked) },
                        frow = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.medium)
                            .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
                        hoverable = true,
                        hoverShape = AppShapes.medium,
                    )
                    if (debugPageEnabled) {
                        // 直达行：ThemeOptionItem 同款 HoverableRow + modifier.clickable 组装
                        // （HoverableRow 无 onClick 参数——纯 hover 壳，行点击经 modifier 链承担）
                        HoverableRow(
                            modifier = Modifier
                                .clip(AppShapes.medium)
                                .clickable { appViewModel.setCurrentPage(ToolPage.Debug) }
                                .padding(horizontal = Dimens.space16, vertical = Dimens.space10),
                            shape = AppShapes.medium,
                        ) {
                            Text(
                                text = "前往 Shell 调试 →",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // 启动耗时子区与直达行同门控（呈现口径见 StartupTimingsPanel KDoc）
                        StartupTimingsPanel()
                    }
                }
            }
        },
    )

    // 顶层间距经 PageScaffold 统一为 screenSpacing（16dp，骨架统一对齐）
    // 滚动责任迁移——双栏（Wide）时走吸顶模式（headerSticky=true 给 content
    // 有界高度），右舱 Column(verticalScroll) 局部滚（SettingsTwoPane 内置）；
    // 窄窗回落保持现状整页滚（headerSticky=false + modifier verticalScroll——
    // PageScaffold 契约：headerSticky 与 modifier verticalScroll 互斥）。
    // 卡面 proposal 写「headerSticky=false」与右舱局部滚矛盾（非吸顶 content
    // 无界高度，verticalScroll 永不滚动、长分类内容不可达）——按源码实况修。
    val wideWindow = currentWindowSizeClass() == WindowSizeClass.Wide
    PageScaffold(
        title = "设置",
        headerSticky = wideWindow,
        modifier = if (wideWindow) Modifier else Modifier.verticalScroll(state = scrollState),
    ) {
        SettingsTwoPane(sections = sections)
    }

    // ── 对话框 ──
    if (showColorPicker) {
        ColorPickerDialog(
            // 硬编码兜底以 Default.seed 为真源——初始色随 seed 调整自动跟随，不残留陈旧值
            initialColor = (currentScheme as? AppThemeSpec.Custom)?.seed ?: AppThemeSpec.Default.seed,
            onConfirm = { seed ->
                showColorPicker = false
                appViewModel.setCustomColorScheme(seed)
            },
            onDismiss = { showColorPicker = false },
        )
    }
    // Done 走既有确认对话框；确认/取消均回 Idle
    // （回 Idle 而非留 Done——留态会让下次拖入跳过 Extracting 直闪旧对话框）
    (extractState as? ExtractThemeState.Done)?.let { done ->
        ImageThemeConfirmDialog(
            seed = done.seed,
            onConfirm = {
                appViewModel.setCustomColorScheme(done.seed)
                extractState = ExtractThemeState.Idle
            },
            onDismiss = { extractState = ExtractThemeState.Idle },
        )
    }
}

/**
 * 设置分组卡（TonalCard + .chead 卡题）：
 * 组卡（TonalCard 统一卡片语言）内标题行 + 描述 + 内容行。
 * 卡题 .chead .t：fs-16（16/SemiBold）；卡题不取 labelSmall(11) iOS 弱字距语言
 * （字重 650 取 SemiBold(600) 近档——FieldRowLabel 550→600 同款裁量）。
 */
@Composable
private fun SettingsSection(
    title: String,
    desc: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.space8),
        ) {
            Column {
                Text(
                    text = title,
                    // .chead .t：fs-16/650；字重 650 取 SemiBold(600) 近档
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalToolboxSurfaceColors.current.onSurfaceFaint
                )
            }
            content()
        }
    }
}

/**
 * 「启动耗时」子区（开发者选项门控内呈现，设置页开发者排障「启动慢」的直观露出面）：
 * 订阅 [JInitializerManager.initTimingsFlow]，把耗时记录呈现为
 * 「组标题（含组合计）+ 逐条耗时行」。
 *
 * 呈现口径：
 * - 类名取 Class.simpleName 简名——包前缀对定位初始化器无增量信息，
 *   全限定名见 JLog「init timing」打点；匿名类 simpleName 为 null 时回落全限定名；
 * - 毫秒 = durationNanos / 1_000_000.0，一位小数（[formatInitMillis]，Locale.ROOT
 *   固定小数点形态，不随系统区域变逗号）；
 * - 组合计 = 组内各记录求和——低优组相邻级别的错峰等待不计入 durationNanos
 *   （耗时只度量 init() 本体），求和即组级初始化开销，无需另计调度等待；
 * - 失败行尾注「失败」标记（error 着色）；启动失败短路时记录可能不完整
 *   （发射契约见 [JInitializerManager.initTimingsFlow]）。
 *
 * internal 可见性 = 渲染单测接缝（门控面由源码锚守护，见 StartupTimingsPanelTest）。
 */
@Composable
internal fun StartupTimingsPanel() {
    val timings by JInitializerManager.initTimingsFlow.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        Text(
            text = "启动耗时",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "启动时各初始化器的耗时分布（毫秒）",
            style = MaterialTheme.typography.bodySmall,
            color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
        )
        if (timings.isEmpty()) {
            Text(
                text = "暂无耗时记录",
                style = MaterialTheme.typography.bodySmall,
                color = LocalToolboxSurfaceColors.current.onSurfaceFaint,
            )
        } else {
            // 组序恒定：高优组先于低优组（与执行模型同序——高优组先执行）
            listOf(true, false).forEach { isHigh ->
                val group = timings.filter { it.isHighLevel == isHigh }
                if (group.isNotEmpty()) {
                    TimingGroupRows(
                        groupTitle = if (isHigh) "高优组" else "低优组",
                        group = group,
                    )
                }
            }
        }
    }
}

/**
 * 单组耗时行（组标题含组合计）。组内行序 = 观测面发射序（组内按执行完成序）。
 */
@Composable
private fun TimingGroupRows(groupTitle: String, group: List<InitializerTiming>) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        Text(
            text = "$groupTitle · 合计 ${formatInitMillis(group.sumOf { it.durationNanos })}",
            style = MaterialTheme.typography.labelMedium,
        )
        group.forEach { timing ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
            ) {
                Text(
                    text = timing.initializerClass.simpleName ?: timing.initializerClass.name,
                    style = DesktopTypography.mono,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatInitMillis(timing.durationNanos),
                    style = DesktopTypography.mono,
                )
                if (timing.isSuccess.not()) {
                    Text(
                        text = "失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 耗时毫秒文案（一位小数）。Locale.ROOT 固定小数点形态——区域敏感格式会把小数点
 * 渲染成逗号，耗时数据行必须保持机器可读形态。internal 供单测直测格式契约。
 */
internal fun formatInitMillis(durationNanos: Long): String =
    String.format(Locale.ROOT, "%.1f ms", durationNanos / 1_000_000.0)
