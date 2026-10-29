package com.jiaoay.easy_dev_toolbox.page.logcat

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.widget.AppButton
import com.jiaoay.easy_dev_toolbox.widget.AppButtonKind
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.LogViewerDialog
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.TonalCard
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverTooltipBox
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor
import com.jiaoay.toolbelt.device.DeviceStateSource

/** 贴底跟随像素容差：判定「最后一可见项底边接近视口底边」时吸收亚像素/微偏移 */
private const val BOTTOM_FOLLOW_TOLERANCE_PX = 8

/** 级别 chip 档：null = 全部级别（不限），其余按严重度升序 */
private val LEVEL_CHIPS: List<LogcatLevel?> =
    listOf(null) + LogcatLevel.entries

/**
 * logcat 实时抓取面板：选设备（复用页面设备栏）→ 可选过滤（查询框 + 级别 chips，
 * 两输入面写同一过滤模型；无算子裸词按旧语义，含算子按查询语法——文法见
 * [LogcatQueryParser]）→ 开始实时滚动
 * → 停止即按行落盘 .txt（产物路径回显 + 应用内查看 + 打开所在文件夹 + 送往 Tag 过滤
 * ——产物即过滤页输入类型，一键衔接免出应用拖拽；送工作台分析——深链直投研判；
 * 两类衔接钮均宿主未接线时不出现；送飞书——终态产物直发桥接接收会话，桥接未运行
 * 置灰明示原因——与接线门控缺席的其余两钮不同形态）。过滤在抓取进行中可改——即时生效且只影响
 * 后续行（原地收窄，免停止重抓丢已有流）；抓取中可「清屏」——只清滚动显示窗
 * （清显示不清落盘），配合改过滤实现「改过滤 → 清屏 → 只看新行」的盯屏闭环；
 * 抓取中可「标记」——复现瞬间把屏上位置记成锚点（单锚点，后标记覆盖前标记），
 * 停止后「查看」带锚点直达标记处，免肉眼回找；抓取中可「武装」——设特征正则
 * 进入等待态，命中行自动标记锚点（复现瞬间免手点；武装眼看过滤挡下的行，落盘口径
 * 零变化），命中即收网、解除武装可撤销；武装输入带常见特征预设行——点击只填入
 * 可改后再武装（预填不自动执行），预设与渲染层崩溃判定两语义两通道不合流。
 * 实时滚动台对崩溃堆栈做**折叠**（收集线程增量维护、随 [LogcatWindow] 快照发布——
 * 长堆栈默认折叠为可点开的一行，落盘契约零触碰）与**崩溃行高亮**
 * （判定与折叠同源，error 语义色——色值契约见 [isCrashLine]）；查询解析失败即时回执
 * （输入框错误态 + 去抖 toast），引擎保持上一有效过滤；查询框 supportingText 带输入
 * 提示（空输入算子概览 / 待操作数算子值域——口径见 [logcatQueryHint]，错误回执优先）。
 *
 * 设备/adb 路径检测态全部经 [devices]（[DeviceStateSource] 三流——:core 设备域
 * App 级单源）注入：宿主观测空间页传设备域单源，宿主未接线消费方传窄接口即可；
 * 提示态不静默（无设备 / 检测失败 /
 * 多设备未选定 → banner + 对应动作入口）。视觉层为保守默认（控制台深底 + 等宽行），
 * 真机手感由维护者终裁。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogcatPanel(
    devices: DeviceStateSource,
    onGoSettings: (() -> Unit)? = null,
    sharedViewModel: LogcatViewModel? = null,
) {
    // 宿主编排行（设备观测页复现记录编排）与面板须共享同一 VM 实例：编排转发
    // startCapture 落的 hint/回执与面板同源，不共享即出现两份互不相见的实例
    val viewModel = sharedViewModel ?: viewModel(key = "logcat-capture") { LogcatViewModel(devices) }
    val tagQuery by viewModel.tagQuery.collectAsState()
    val minLevel by viewModel.minLevel.collectAsState()
    val queryError by viewModel.queryError.collectAsState()
    val hint by viewModel.hint.collectAsState()
    val status by viewModel.status.collectAsState()
    val window by viewModel.window.collectAsState()
    val anchor by viewModel.anchor.collectAsState()
    val armPattern by viewModel.armPattern.collectAsState()
    val armed by viewModel.armed.collectAsState()
    val armDelaySeconds by viewModel.armDelaySeconds.collectAsState()
    // 产物送飞书就绪态（:core 中立槽——桥接宿主接线后随运行态发布；未接线/未运行
    // 恒 false，置灰文案由 logcatLarkSendDisabledReason 承担）
    val larkDeliveryReady by ReportDelivery.ready.collectAsState()

    val running = status is LogcatCaptureStatus.Running
    // 输入提示随查询串纯函数派生（口径与解析器文法同源，见 logcatQueryHint KDoc）
    val queryHint = logcatQueryHint(tagQuery)
    var expanded by remember { mutableStateOf(false) }
    // 应用内查看器挂载态：回执行「查看」置入的产物路径；null = 无查看器在台。
    // pendingViewTimestamp = 点击时刻的标记锚点含年完整时间串（anchor.fullTimestamp——
    // 派生口径同「送工作台分析」载荷；null = 未标记不定位，现行为原样）
    var pendingViewPath by remember { mutableStateOf<String?>(null) }
    var pendingViewTimestamp by remember { mutableStateOf<String?>(null) }

    pendingViewPath?.let { path ->
        LogViewerDialog(
            filePath = path,
            onDismiss = { pendingViewPath = null },
            initialTimestamp = pendingViewTimestamp,
        )
    }

    // 抓取进行中自动展开（实时滚动可见）；结束后保持展开态由用户手动收起
    LaunchedEffect(running) {
        if (running) expanded = true
    }

    TonalCard(
        contentPaddingVertical = Dimens.space12,
        contentPaddingHorizontal = Dimens.space16,
        contentSpacing = Dimens.space8,
    ) {
        // 头部行：图标 + 标题 + 运行中行数 + 展开/收起
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSymbolIcon(
                icon = MaterialSymbols.GRAPHIC_EQ,
                contentDescription = null,
                size = Dimens.iconMedium,
                tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(Dimens.space8))
            Text(
                text = if (running) "logcat 实时抓取 · 已捕获 ${window.totalCount} 行" else "logcat 实时抓取",
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
                    contentDescription = if (expanded) "收起抓取面板" else "展开抓取面板",
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
                // 过滤参数行：查询框 + 级别 chips（两输入面写同一过滤模型，抓取进行中
                // 可改——即时生效且只影响后续行；解析失败即时回执、引擎保持上一有效过滤）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    OutlinedTextField(
                        value = tagQuery,
                        onValueChange = viewModel::onTagQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = DesktopTypography.mono,
                        isError = queryError != null,
                        placeholder = {
                            Text(
                                "过滤查询：裸词 = tag 包含；支持 tag: level: & | - ( ) ~/正则/",
                                style = DesktopTypography.mono,
                            )
                        },
                        // supportingText 提示形态（无 popup、无可点面——不抢焦点不挡输入）：
                        // 错误回执优先占槽（错误态的提示拼接其后补值域指引，不改错误通道时序），
                        // 无错误时的空输入/待操作数提示用中性色，与错误色区分。
                        supportingText = when {
                            queryError != null -> {
                                {
                                    Text(
                                        text = if (queryHint != null) {
                                            "查询无效（$queryError）——$queryHint"
                                        } else {
                                            "查询无效（$queryError）——已保持上一有效过滤"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            queryHint != null -> {
                                val hint = queryHint
                                {
                                    Text(
                                        text = hint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            else -> null
                        },
                    )
                }
                // 窄窗 chip 换行兜底：普通 Row 行尾 chip 会被裁出视口不可达
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    Text(
                        "级别",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LEVEL_CHIPS.forEach { level ->
                        FilterChip(
                            selected = minLevel == level,
                            onClick = { viewModel.onMinLevelChange(if (minLevel == level) null else level) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary
                                    .copy(alpha = AppAlpha.hotWash),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                            shape = AppShapes.capsule,
                            label = {
                                Text(
                                    text = level?.letter ?: "全部",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }

                // 提示态（无设备 / 检测失败 / 多设备未选定）：banner + 对应动作入口，不静默
                val currentHint = hint
                if (currentHint != null) {
                    StatusBanner(
                        status = StatusBannerStatus.Error,
                        text = currentHint.message,
                        trailing = {
                            when (currentHint.action) {
                                LogcatHint.Action.GO_SETTINGS -> onGoSettings?.let { go ->
                                    TextButton(onClick = go) { Text("去设置配置 adb 路径") }
                                }

                                LogcatHint.Action.SELECT_DEVICE ->
                                    TextButton(onClick = { viewModel.dismissHint() }) { Text("已选择") }

                                LogcatHint.Action.REFRESH_DEVICES,
                                null -> TextButton(onClick = { viewModel.dismissHint() }) { Text("知道了") }
                            }
                        },
                    )
                }

                // 动作行：停止并落盘 + 标记 + 清屏（只清滚动显示窗，不清落盘——落盘文件与全量计数不受影响）。
                // 主次钮统一过 AppButton 单源（fbtn 胶囊几何 + busy/disabledReason 通道）：
                // 停止类 = Destructive（error 实底胶囊）、其余盯屏动作 = Secondary
                if (running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        AppButton(
                            label = "停止并落盘",
                            busy = false,
                            onClick = viewModel::stopCapture,
                            kind = AppButtonKind.Destructive,
                            leadingIcon = MaterialSymbols.STOP_CIRCLE,
                        )
                        // 标记 = 复现瞬间留锚点（对齐清屏钮的盯屏动作形态）：
                        // 记屏上最新带时间戳行，停止后「查看」直达——回执经 toast 即时确认
                        AppButton(
                            label = "标记",
                            busy = false,
                            onClick = viewModel::markAnchor,
                            kind = AppButtonKind.Secondary,
                            leadingIcon = MaterialSymbols.PUSH_PIN,
                        )
                        // 清屏与执行队列输出台的「清空」语义不同：这边清的是实时滚动窗，
                        // 队列那边清的是历史记录（故文案用「清屏」）
                        AppButton(
                            label = "清屏",
                            busy = false,
                            onClick = viewModel::clearDisplay,
                            kind = AppButtonKind.Secondary,
                            leadingIcon = MaterialSymbols.DELETE_SWEEP,
                        )
                    }
                } else {
                    AppButton(
                        label = "开始抓取",
                        busy = false,
                        onClick = viewModel::startCapture,
                        leadingIcon = MaterialSymbols.GRAPHIC_EQ,
                    )
                }

                // 武装捕获行（Running 态可用，武装钮对齐清屏钮的盯屏动作形态）：等待复现
                // 期间埋伏特征正则，命中行自动标记锚点（复现瞬间免手点）；已武装时输入
                // 锁定（改特征先解除——埋伏中途换语义易自惑），状态行明示「等待特征」；
                // 延迟档 = 武装时刻起倒计时到点自动停止（时间盒埋伏：布好雷区走人，
                // 到点回来产物已封口），默认不延迟——武装纯等待语义
                if (running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    ) {
                        OutlinedTextField(
                            value = armPattern,
                            onValueChange = viewModel::onArmPatternChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = DesktopTypography.mono,
                            readOnly = armed,
                            placeholder = {
                                Text("特征正则（武装捕获：命中自动标记）", style = DesktopTypography.mono)
                            },
                        )
                        if (armed) {
                            AppButton(
                                label = "解除武装",
                                busy = false,
                                onClick = viewModel::disarmCapture,
                                leadingIcon = MaterialSymbols.RADAR,
                            )
                        } else {
                            AppButton(
                                label = "武装",
                                busy = false,
                                onClick = viewModel::armCapture,
                                kind = AppButtonKind.Secondary,
                                enabled = armPattern.isNotBlank(),
                                // 置灰原因明示（禁静默置灰——特征正则未填）
                                disabledReason = if (armPattern.isBlank()) "先填特征正则再武装" else null,
                                leadingIcon = MaterialSymbols.RADAR,
                            )
                        }
                    }
                    // 常见特征预设行（武装输入行的同款邻居配方）：点击只填入特征
                    // 输入框、可改后再武装——预填不自动执行，武装仍是用户显式动作；
                    // 已武装时与特征输入框同口径锁定（改特征先解除）；预设无选中态
                    // （填入动作非档位选择，选中态恒 false，填入反馈即输入框内容变化）
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space6),
                        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                    ) {
                        Text(
                            text = "常见特征：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LogcatViewModel.ARM_FEATURE_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.onArmPatternChange(preset.pattern) },
                                enabled = !armed,
                                // 单选档位唯一形态（与级别 chips 同配方）：capsule +
                                // primary×hotWash 选中色——M3 默认圆角/高亮不混用
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary
                                        .copy(alpha = AppAlpha.hotWash),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                                shape = AppShapes.capsule,
                                label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                    // 延迟自停预设档（改档与改特征同口径：已武装时锁定，先解除再改）
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                        verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                    ) {
                        Text(
                            text = "武装后自动停止：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LogcatViewModel.ARM_DELAY_PRESET_SECONDS.forEach { preset ->
                            FilterChip(
                                selected = armDelaySeconds == preset,
                                onClick = { viewModel.onArmDelayChange(preset) },
                                enabled = !armed,
                                // 单选档位唯一形态（与级别 chips 同配方）：capsule +
                                // primary×hotWash 选中色
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary
                                        .copy(alpha = AppAlpha.hotWash),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                                shape = AppShapes.capsule,
                                label = { Text(armDelayLabel(preset)) },
                            )
                        }
                    }
                    if (armed) {
                        Text(
                            text = if (armDelaySeconds > 0) {
                                "已武装 · 等待特征命中（命中行自动标记，${armDelayLabel(armDelaySeconds)}后自动停止）"
                            } else {
                                "已武装 · 等待特征命中（命中行自动标记）"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // 溢出提示：滚动窗超上限丢最旧——屏里不是全量须显式告知，否则往回滚
                // 找不到开头几行会被误读成「没抓到」；清屏不清溢出账，清屏后照常呈现
                logcatOverflowHint(window)?.let { overflow ->
                    Text(
                        text = overflow,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                when (val s = status) {
                    is LogcatCaptureStatus.Running -> {
                        LogcatConsole(window = window)
                    }

                    is LogcatCaptureStatus.Stopped -> {
                        // 产物回显：路径可见可拖拽（文件管理器侧拖拽 + 一键衔接双通道）
                        val file = s.outputFile
                        Text(
                            text = if (file != null) {
                                "${s.message}：共 ${s.totalCount} 行，已保存 ${file.absolutePath}"
                            } else {
                                "${s.message}：共 ${s.totalCount} 行"
                            },
                            style = DesktopTypography.mono,
                            color = if (s.totalCount > 0) {
                                LocalEasyToolboxColors.current.consoleSuccess
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (file != null) {
                            // 标记回显：有锚点时明示「查看」的直达语义（无标记零呈现——现行为原样）
                            anchor?.let {
                                Text(
                                    text = "已标记 ${it.clockText}——点「查看」直达标记处",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 应用内查看落盘产物（出应用前先一眼核对；抓取中点过「标记」
                                // 则带锚点直达标记处——透传 anchor.fullTimestamp 含年完整串：
                                // 查看器 jumpToTimestamp 解析式匹配兼容裸月日/含年两形态，
                                // 深度分析预填须完整年格式，标记年补全只能在此生产）；
                                // 其余动作仍走系统侧
                                TextButton(onClick = {
                                    pendingViewPath = file.absolutePath
                                    pendingViewTimestamp = anchor?.fullTimestamp
                                }) { Text("查看") }
                                TextButton(onClick = viewModel::openOutputFolder) { Text("打开所在文件夹") }
                                // 管线衔接条目：宿主接线 + 产物有映射两门控，缺一即条目缺席
                                if (TaskPipelineHandoff.isWired) {
                                    TaskPipelineHandoff.nextToolTargetOf(
                                        TaskPipelineHandoff.SourcePageId.LOGCAT_CAPTURE,
                                    )?.let { target ->
                                        TextButton(onClick = viewModel::sendToNextTool) {
                                            Text(target.label)
                                        }
                                    }
                                }
                                // 送工作台分析（深链直投）：宿主接线门控，未接线时按钮缺席
                                if (DeepAnalyzeHook.isWired) {
                                    TextButton(onClick = viewModel::sendToWorkbench) {
                                        Text("送工作台分析")
                                    }
                                }
                                // 一键送飞书（终态产物文件消息直发桥接接收会话）；置灰判据 =
                                // [logcatLarkSendDisabledReason]（真源 = ReportDelivery.ready，
                                // 未接线/未运行恒 false），禁用态 hover 明示原因（disabled 按钮
                                // 不发 Hover 事件，hoverable 挂外层——契约见 HoverTooltipBox KDoc）
                                val larkSendDisabledReason = logcatLarkSendDisabledReason(larkDeliveryReady)
                                if (larkSendDisabledReason == null) {
                                    TextButton(onClick = viewModel::sendOutputToLark) {
                                        Text("送飞书")
                                    }
                                } else {
                                    HoverTooltipBox(
                                        tooltip = {
                                            Text(
                                                text = larkSendDisabledReason,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                    ) { interactionSource ->
                                        TextButton(
                                            onClick = viewModel::sendOutputToLark,
                                            enabled = false,
                                            modifier = Modifier.hoverable(interactionSource),
                                        ) {
                                            Text("送飞书")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    is LogcatCaptureStatus.Idle -> {
                        // Idle 深底占位槽（与 ScreencastPanel 无帧占位同配方——设备在台、
                        // 未开始抓取时不再是空白断崖）；文案四要素口径（ui-style-guide.md
                        // §5.7：说清现在能做什么）
                        Surface(
                            color = LocalEasyToolboxColors.current.consoleBg,
                            shape = AppShapes.medium,
                        ) {
                            Text(
                                text = "配置过滤后点「开始抓取」，实时行流在此滚动；崩溃堆栈自动折叠高亮",
                                style = DesktopTypography.mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = Dimens.consoleHeightMin)
                                    .padding(Dimens.space12),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 滚动窗溢出提示文案；null = 无溢出不呈现（零溢出不打扰）。
 *
 * 契约：[LogcatWindow.droppedCount] 是滚动窗溢出累计（只影响回看，落盘不受影响），
 * 计数与「落盘完整」口径句必须同句呈现——否则屏里看不到最旧行会被误读成
 * 「没抓到」；[LogcatCaptureEngine.clearDisplay] 清屏不清溢出账，清屏后本提示
 * 照常呈现。
 */
internal fun logcatOverflowHint(window: LogcatWindow): String? =
    window.droppedCount.takeIf { it > 0 }
        ?.let { "已丢弃最早 $it 行（仅影响回看，落盘完整）" }

/**
 * logcat 产物「送飞书」置灰判据（纯函数：null = 可发送；非 null = 置灰原因——按钮
 * 禁用态 tooltip 直接消费，禁静默置灰）。产物行仅 Stopped + 产物存在时组合（终态
 * 产物语义，非 Running 中途），本判据只剩桥接运行态一闸。
 */
internal fun logcatLarkSendDisabledReason(larkDeliveryReady: Boolean): String? =
    if (larkDeliveryReady) null else "飞书桥接未运行，请先在飞书桥接页启动桥接"

/**
 * 武装延迟自动停止档的回显文案（秒 → 人话档名）：0 = 不延迟；整分钟档以分钟呈现。
 * internal 供单测锚。
 */
internal fun armDelayLabel(seconds: Int): String = when {
    seconds <= 0 -> "不延迟"
    seconds >= 60 && seconds % 60 == 0 -> "${seconds / 60} 分钟"
    else -> "$seconds 秒"
}

/** 堆栈帧行形态：行首（允许缩进）或 threadtime 头（`MM-DD hh:mm:ss.mmm pid tid L TAG:`）
 *  后以 `at` + 空白起头的帧行（Java/Kotlin 崩溃堆栈帧的两种呈现形态）。
 *  internal：引擎侧折叠增量追踪（[LogcatConsoleFoldTracker]）同源消费——高亮 / 折叠 /
 *  增量维护三面共用单一真源，防帧行判定口径漂移出第二套正则 */
internal val STACK_FRAME_LINE = Regex(
    """^\s*(?:(?:\d{4}-\d{2}-\d{2}|\d{2}-\d{2}) \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s+\S+\s*:\s*)?at\s""",
)

/** 折叠阈值：连续帧行达到本数才折叠成组（短栈不折叠——两三帧的调用痕迹折叠反而多一次点击） */
internal const val MIN_FOLDABLE_STACK_FRAMES = 5

/**
 * 行级崩溃形态判定（渲染层纯函数）：与折叠检测共用帧行真源 [STACK_FRAME_LINE]——
 * 高亮与折叠两渲染面对同一行集判定，单一正则防两套崩溃口径漂移；唯一差异是阈值面：
 * 折叠有 [MIN_FOLDABLE_STACK_FRAMES] 连续行门槛，高亮无门槛（短帧行串不折叠但仍
 * 着色）。着色槽 = [com.jiaoay.easy_dev_toolbox.core.theme.EasyToolboxColors.logError]
 * 恒深值（主题派生 error 浅色档对 consoleBg 深底 2.55:1 不达 AA，禁直接取
 * colorScheme.error）。
 */
internal fun isCrashLine(line: String): Boolean = STACK_FRAME_LINE.containsMatchIn(line)

/**
 * 控制台渲染行组：普通行 / 折叠堆栈组（纯检测产物，渲染态见 [LogcatConsole]）。
 * public 数据载体：经 [LogcatWindow] 的 rows 字段随窗口快照发布（纯显示派生面，
 * 消费面只读）。
 */
sealed interface LogcatConsoleRow {

    /** 组首行在窗口行列表中的下标（渲染 key = 滚动窗首行绝对序号 + 本下标） */
    val firstIndex: Int

    data class Plain(val line: String, override val firstIndex: Int) : LogcatConsoleRow

    /** 连续堆栈帧行组：默认折叠为可点开的一行（展开态由渲染层记账） */
    data class Stack(val frames: List<String>, override val firstIndex: Int) : LogcatConsoleRow
}

/**
 * 崩溃堆栈折叠检测（纯函数）：滚动窗行列表 → 渲染行组列表。连续
 * [MIN_FOLDABLE_STACK_FRAMES] 行以上的 `at` 帧行折叠为一个 [LogcatConsoleRow.Stack]
 * 组，其余行原样平铺。引擎 / 落盘 / 滚动窗 String 契约零触碰——只改渲染面。
 *
 * 本函数同时是增量折叠 [LogcatConsoleFoldTracker] 的全量参考实现与等价性锚
 * （单测对同一窗口行列表逐组对照）；主路径消费引擎预折叠快照，不再全窗重扫。
 */
internal fun foldConsoleRows(lines: List<String>): List<LogcatConsoleRow> {
    val rows = mutableListOf<LogcatConsoleRow>()
    var i = 0
    while (i < lines.size) {
        if (!STACK_FRAME_LINE.containsMatchIn(lines[i])) {
            rows.add(LogcatConsoleRow.Plain(lines[i], i))
            i++
            continue
        }
        val start = i
        while (i < lines.size && STACK_FRAME_LINE.containsMatchIn(lines[i])) i++
        val run = lines.subList(start, i)
        if (run.size >= MIN_FOLDABLE_STACK_FRAMES) {
            rows.add(LogcatConsoleRow.Stack(run.toList(), start))
        } else {
            run.forEachIndexed { offset, line ->
                rows.add(LogcatConsoleRow.Plain(line, start + offset))
            }
        }
    }
    return rows
}

/** 实时滚动控制台：深底等宽行 + 贴底跟随（scrollToItem 即时跳转，零动画铁律）
 *  + 崩溃堆栈折叠（默认折叠为可点开的一行）+ 崩溃行高亮（[isCrashLine] 同源判定） */
@Composable
private fun LogcatConsole(window: LogcatWindow) {
    val listState = rememberLazyListState()
    // 折叠行组随窗口快照到达（引擎收集线程增量维护、与全量参考实现恒等）；
    // 贴底跟随与 key 都以行组下标为准。面板零正则重扫——查询框/特征框逐键等
    // 兄弟状态触发的重组不再重算折叠
    val rows = window.rows
    // 贴底判定与 Shell 输出台同配方：追加时若用户贴底（canScrollForward == false 或
    // 末可见项底边贴近视口底）则即时跳底；上翻查看历史时不劫持视点。
    // key 取窗口末行组（滚动窗封顶后末组恒在，末行原文变化即触发重估）。
    LaunchedEffect(window.lines.lastOrNull()) {
        val last = rows.lastIndex
        if (last >= 0 && !listState.isScrollInProgress) {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            val atBottom = !listState.canScrollForward ||
                (lastVisible != null &&
                    lastVisible.offset + lastVisible.size >= info.viewportEndOffset - BOTTOM_FOLLOW_TOLERANCE_PX)
            if (atBottom) listState.scrollToItem(last)
        }
    }
    // 展开态按组首行绝对序号（Long，与 totalCount 同型）记账：滚动窗滚动后不复位到错位的组
    val expandedStacks = remember { mutableStateMapOf<Long, Boolean>() }
    Surface(
        shape = AppShapes.medium,
        color = LocalEasyToolboxColors.current.consoleBg,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = Dimens.mcpLogHeightMax),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(
                    horizontal = Dimens.space12,
                    vertical = Dimens.space8,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.space2),
            ) {
                val firstAbsoluteIndex = (window.totalCount - window.lines.size).coerceAtLeast(0)
                itemsIndexed(
                    rows,
                    key = { _, row -> firstAbsoluteIndex + row.firstIndex },
                ) { _, row ->
                    when (row) {
                        is LogcatConsoleRow.Plain -> ConsoleLineText(row.line, crash = isCrashLine(row.line))

                        is LogcatConsoleRow.Stack -> {
                            val stackKey = firstAbsoluteIndex + row.firstIndex
                            if (expandedStacks[stackKey] == true) {
                                Column(verticalArrangement = Arrangement.spacedBy(Dimens.space2)) {
                                    // 组内恒为帧行（折叠即按同一判定分组）——展开帧全部着崩溃色
                                    row.frames.forEach { frame -> ConsoleLineText(frame, crash = true) }
                                }
                            } else {
                                // 折叠行 hover 洗底（深底语境登记档：primary hoverWash，
                                // 与 ShellOutputPanel 重试行同档）+ indication=null
                                val stackSource = remember { MutableInteractionSource() }
                                val stackHovered by stackSource.collectIsHoveredAsState()
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(Dimens.space2),
                                    modifier = Modifier
                                        .background(
                                            selectionHoverWashColor(hovered = stackHovered),
                                            AppShapes.small,
                                        )
                                        .clickable(interactionSource = stackSource, indication = null) {
                                            expandedStacks[stackKey] = true
                                        },
                                ) {
                                    // 折叠态首帧行着崩溃色：折叠后的「哪里炸了」一行可见
                                    ConsoleLineText(row.frames.first(), crash = true)
                                    Text(
                                        text = "… 崩溃堆栈共 ${row.frames.size} 帧，点击展开",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalEasyToolboxColors.current.consoleText,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleLineText(line: String, modifier: Modifier = Modifier, crash: Boolean = false) {
    Text(
        text = line,
        style = DesktopTypography.monoConsole,
        color = if (crash) {
            LocalEasyToolboxColors.current.logError
        } else {
            LocalEasyToolboxColors.current.consoleText
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
