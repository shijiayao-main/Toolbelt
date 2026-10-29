package com.jiaoay.easy_dev_toolbox.page.shelltools

import dev.vicart.compose.material.symbols.MaterialSymbols

import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.entity.JResult
import com.jiaoay.easy_dev_toolbox.page.shelltools.config.ToolNode
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.ShellToolsList
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom.CustomGroupNameDialog
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom.ImportCustomJsonDialog
import com.jiaoay.easy_dev_toolbox.page.shelltools.list.custom.ShellCustomEditorDialog
import com.jiaoay.easy_dev_toolbox.page.shelltools.output.OutputHeightDivider
import com.jiaoay.easy_dev_toolbox.page.shelltools.output.ShellOutputPanelContent
import com.jiaoay.easy_dev_toolbox.widget.device.DeviceSelectDialog
import com.jiaoay.easy_dev_toolbox.widget.device.DeviceStatusBar
import com.jiaoay.easy_dev_toolbox.core.navigation.GoSettingsHook
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.EmptyStateView
import com.jiaoay.easy_dev_toolbox.widget.PageDensity
import com.jiaoay.easy_dev_toolbox.widget.PageHeader
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SearchField
import com.jiaoay.easy_dev_toolbox.widget.StatusBanner
import com.jiaoay.easy_dev_toolbox.widget.StatusBannerStatus
import com.jiaoay.easy_dev_toolbox.widget.interactions.TooltipIconButton
import kotlinx.coroutines.launch
import com.jiaoay.toolbelt.device.DeviceState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShellToolsPage(
    viewModel: ShellToolsViewModel = ShellToolsViewModel.getViewModel(),
    onGoSettings: (() -> Unit)? = null,
) {
    // 「去设置配置 adb」CTA：默认经 :core 中立槽 GoSettingsHook
    //（宿主 AppViewModel wire 切设置页——ToolPageRegistration.content 零参工厂
    // 无法传回调实参，DeepAnalyzeHook 同款槽配方）；显式传参优先（测试缝）。
    val goSettings: (() -> Unit)? = onGoSettings ?: remember {
        {
            GoSettingsHook.offer()
            Unit
        }
    }
    // 页面可见性门控接线（VM 窗口级驻留、切页不销毁，页面状态在宿主侧不可达，
    // 故经本组合生命周期信号注入）：进入组合置 true、切页 onDispose 置 false——
    // 设备检测 5s 轮询在页面不可见期间静默跳过，不 spawn 登录 shell + adb 子进程。
    DisposableEffect(viewModel) {
        viewModel.setPageVisible(true)
        onDispose { viewModel.setPageVisible(false) }
    }
    // 自定义命令编辑器状态
    var showCustomEditor by remember { mutableStateOf(false) }

    // 标题区五钮 + 分组/导入/设备对话框状态随 PageHeader 一并上提外壳
    //（PageScaffold 接管标题后 actions 槽在外壳层）
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var createGroupError by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    // 管理区内联展开态（页头「更多」切换——popup-free，见 actions 注释）
    // 初值 true——首见即展开（首次用户直接看见四动作，点「更多」即收起）；
    // 进程级持有（ShellToolsRuntime）：PageHomeContent 即时切换分发无
    // SaveableStateHolder，组合级保存切页即丢
    val manageExpanded by ShellToolsRuntime.manageExpanded.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()

    // 转无线回执经既有 toast 通道落地（回执/拒绝各恰一条）：eventSeq 为事件去重键
    // ——同文本的重复事件不丢条。与设备观测页同款配方：回执流是设备域单源
    // StateFlow，两页回执 effect 各自组合，页面单组合（一次只组合当前页）保证
    // 不双发 toast。
    val wirelessState by viewModel.wirelessState.collectAsState()
    LaunchedEffect(wirelessState.eventSeq) {
        wirelessState.lastReceipt?.let { StatusSignals.show(it.message) }
        wirelessState.rejectError?.let { StatusSignals.show(it) }
    }

    // 打开配置目录（系统文件管理器）。目录真源 = FileUtils.getAppDataDir()（路径单源纪律）
    fun openConfigDir() {
        runCatching {
            java.awt.Desktop.getDesktop().open(
                com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getAppDataDir(),
            )
        }
    }

    // 骨架 = PageScaffold(density = Dense)（padding space16 / 间距 space12，值与
    // 密度页布局档一致）；多栏密度页不启用限宽。
        PageScaffold(
            title = "Shell 工具",
            density = PageDensity.Dense,
            actions = {
                // 管理四钮收进「更多」（5 常驻 icon 钮视觉 chrome 过载）；
                // 刷新配置为高频动作保留直钮。popup-free：M3 DropdownMenu 与自绘 Popup 在
                // PageScaffold 页内子组合三连败（错位→裁剪不可见→不开，tour6-8 实拍定罪；
                // 同族疑云殃及 AppContextMenu 右键菜单，已登记复核）——管理动作改 content
                // 顶部内联展开行承载
                IconButton(
                    onClick = {
                        viewModel.viewModelScope.launch {
                            viewModel.fetchShellToolsConfig()
                        }
                    },
                    modifier = Modifier.size(Dimens.minimumInteractiveSize),
                ) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.REFRESH,
                        contentDescription = "刷新配置",
                        size = Dimens.iconLarge,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 「更多」带 hover 提示（「管理自定义命令」——图标语义泛化致管理
                // 能力发现率趋近零）；气泡配方单源 = widget/interactions/HoverTooltipBox
                // （常驻不自动消失 + 延迟防划过闪现 + 手动驱动显隐）
                ManageMenuTooltipButton(
                    manageExpanded = manageExpanded,
                    onToggle = { ShellToolsRuntime.manageExpanded.value = !manageExpanded },
                )
            },
        ) {
            // 管理区内联展开行（页头「更多」切换；popup-free——三连败定罪后
            // 改流内组件承载，动效 = fadeIn+expandVertically standard 档状态过渡）
            AnimatedVisibility(
                visible = manageExpanded,
                enter = fadeIn(animationSpec = AppMotion.standard()) +
                    expandVertically(animationSpec = AppMotion.standard()),
            ) {
                // 管理动作行：4 钮各 widthIn(min=164dp) 合计超可用宽——窄窗 FlowRow
                // 换行兜底（普通 Row 会把行尾钮裁出视口不可达）
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space4),
                ) {
                    // 四动作加前导图标（扫读性——裸文字钮视觉
                    // 均质无锚点；图标 = 动作语义的首扫锚点）
                    OutlinedButton(onClick = {
                        ShellToolsRuntime.manageExpanded.value = false
                        showCustomEditor = true
                    }, modifier = Modifier.widthIn(min = Dimens.manageActionMinWidth)) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.ADD,
                            contentDescription = null,
                            size = Dimens.iconSmall,
                        )
                        Text("新增自定义命令")
                    }
                    OutlinedButton(onClick = {
                        ShellToolsRuntime.manageExpanded.value = false
                        createGroupError = null
                        showCreateGroupDialog = true
                    }, modifier = Modifier.widthIn(min = Dimens.manageActionMinWidth)) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.GROUPS,
                            contentDescription = null,
                            size = Dimens.iconSmall,
                        )
                        Text("新建自定义分组")
                    }
                    // 钮名「导入自定义命令」（剪贴板已自动化
                    // 预填、动线一步，去「从剪贴板」限定——对齐对话框标题）
                    OutlinedButton(onClick = {
                        ShellToolsRuntime.manageExpanded.value = false
                        showImportDialog = true
                    }, modifier = Modifier.widthIn(min = Dimens.manageActionMinWidth)) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.CONTENT_PASTE,
                            contentDescription = null,
                            size = Dimens.iconSmall,
                        )
                        Text("导入自定义命令")
                    }
                    OutlinedButton(onClick = {
                        ShellToolsRuntime.manageExpanded.value = false
                        openConfigDir()
                    }, modifier = Modifier.widthIn(min = Dimens.manageActionMinWidth)) {
                        AppSymbolIcon(
                            icon = MaterialSymbols.FOLDER_OPEN,
                            contentDescription = null,
                            size = Dimens.iconSmall,
                        )
                        Text("打开配置目录")
                    }
                }
            }

            // 布局语序：工具树在上（weight 1f 主区，设备栏/搜索/分组卡优先占屏），输出台沉底
            // （空闲紧裹 ~150px；有输出随内容增长、heightIn 封顶内滚）。
            // 历史面板仍在 End 侧栏槽位不变。
            // 封顶高度进程级记忆：切页保持、重启随进程回默认 420dp（不持久化）；
            // 未手动拖拽时随可用高双向跟随（放大恢复/缩小收缩），拖拽后保留手调值
            val outputMaxHeight by ShellToolsRuntime.consoleCapHeight.collectAsState()
            // 收起/展开开关（进程级会话态）：收起态下拖拽无意义，分隔条随收起隐藏
            val consoleExpanded by ShellToolsRuntime.consoleExpanded.collectAsState()
            // 拖拽上限的容器基准：content Column 实际可用高度（px），onSizeChanged 采集
            var contentHeightPx by remember { mutableStateOf(0) }
            // 分隔条当次拖拽净位移（onDragEnd 判定用；分隔条随收起消失即重置）
            var dividerNetDelta by remember { mutableStateOf(0.dp) }
            // hasOutput 上提判定（与 ShellOutputPanelContent 同源 engine.output）：
            // 空闲紧裹语义下拖拽无意义且分隔条裸露突兀——有输出才渲染分隔条
            val outputState by ShellToolsRuntime.engine.output.collectAsState()
            val hasOutput = outputState.items.isNotEmpty()
            val density = LocalDensity.current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { size ->
                        contentHeightPx = size.height
                        // 未手拖：封顶随可用高双向跟随（放大恢复全程/缩小收缩，上界
                        // 钳 consoleHeightMax）；手拖后只收不放（尊重用户拖拽值）。
                        // 可用高装不下「树硬底+输出台满封顶」时固定子项会挤破树硬底
                        if (size.height > 0) {
                            val bound = consoleCapUpperBound(with(density) { size.height.toDp() })
                                .coerceAtMost(Dimens.consoleHeightMax)
                            val target = if (ShellToolsRuntime.consoleCapUserAdjusted.value) {
                                minOf(outputMaxHeight, bound)
                            } else {
                                bound
                            }
                            if (outputMaxHeight != target) {
                                ShellToolsRuntime.consoleCapHeight.value = target
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(Dimens.space12),
            ) {
                ShellToolsListSection(
                    viewModel = viewModel,
                    onSelectDevice = { showDeviceDialog = true },
                    onGoSettings = goSettings,
                    // 空树直达按钮 → 外壳层编辑器状态（showCustomEditor 在
                    // ShellToolsPage 顶层——section 经回调打开，不开新对话框栈）
                    onOpenCustomEditor = { showCustomEditor = true },
                    // 树区硬底：输出台封顶经 consoleCapUpperBound 收敛后，固定子项
                    //（分隔条 + 输出台）最多吃掉「可用高 − 硬底」，weight 树区实得
                    // ≥ shellTreeHeightMin；heightIn(min) 是残值防线（防未来固定子项
                    // 再膨胀挤树），树高保障由封顶钳制与硬底两层共同成立
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.shellTreeHeightMin),
                )
                if (hasOutput && consoleExpanded) {
                    // 行程预算：有效行程 < splitterMinTravel 即死 affordance（默认窗
                    // 633px → [240,253] 仅 13dp）——把手诚实化隐藏，窗口放大行程恢复自动回归
                    val splitterTravel = consoleCapUpperBound(with(density) { contentHeightPx.toDp() }) -
                        Dimens.consoleHeightMin
                    OutputHeightDivider(
                        interactive = splitterTravel >= Dimens.splitterMinTravel,
                        onDragDelta = { delta ->
                            // 拖拽语义：分隔条 = 输出台上缘、跟手——向下拖 delta>0 时
                            // 上缘下移，封顶取负累加（输出台收窄、树区变高）；
                            // 下限 consoleHeightMin；上限与初始封顶收缩共用
                            // consoleCapUpperBound（树硬底 + 两道 spacedBy 间隙 +
                            // 分隔条命中区 + 输出台纵向 padding 全额扣除——
                            // 少扣任何一项，树区实得都会跌破硬底），两处口径必须一致
                            dividerNetDelta += delta
                            ShellToolsRuntime.consoleCapHeight.value = (outputMaxHeight - delta).coerceIn(
                                Dimens.consoleHeightMin,
                                consoleCapUpperBound(with(density) { contentHeightPx.toDp() }),
                            )
                        },
                        // 只有净位移非零的真实拖拽才关闭封顶自动跟随——零位移试拖（视觉
                        // 无变化）置位会让「放大窗口封顶不回升」静默生效且无 UI 复位
                        onDragEnd = {
                            if (with(density) { dividerNetDelta.roundToPx() } != 0) {
                                ShellToolsRuntime.consoleCapUserAdjusted.value = true
                            }
                            dividerNetDelta = 0.dp
                        },
                    )
                }
                ShellOutputPanelContent(
                    viewModel = viewModel,
                    maxHeight = outputMaxHeight,
                )
            }
        }

        // 自定义命令编辑器
        if (showCustomEditor) {
            ShellCustomEditorDialog(
                viewModel = viewModel,
                editingNode = null,
                onDismiss = { showCustomEditor = false },
            )
        }

        // ── 新建分组（名称占用时对话框保持打开并提示）──
        if (showCreateGroupDialog) {
            CustomGroupNameDialog(
                title = "新建自定义分组",
                confirmLabel = "创建",
                errorMessage = createGroupError,
                onDismiss = { showCreateGroupDialog = false },
                onConfirm = { name ->
                    viewModel.createCustomGroup(name) { ok ->
                        if (ok) {
                            showCreateGroupDialog = false
                        } else {
                            createGroupError = "名称已被占用"
                        }
                    }
                },
            )
        }

        // ── 导入自定义命令（自动读剪贴板预填）──
        if (showImportDialog) {
            ImportCustomJsonDialog(
                viewModel = viewModel,
                onDismiss = { showImportDialog = false },
            )
        }

        // ── 多设备选择对话框（>3 台降级路径——≤3 台常选走内联直选；
        // 取消后经设备状态栏「重新选择」重新打开）──
        val multiState = deviceState as? DeviceState.Multi
        if (showDeviceDialog && multiState != null) {
            DeviceSelectDialog(
                devices = multiState.devices,
                onConfirm = { serial ->
                    viewModel.selectDevice(serial)
                    showDeviceDialog = false
                },
                onDismiss = { showDeviceDialog = false },
            )
        }
}

// ═══════════════════════════════════════════
// 左栏：工具列表区域（收窄为内容区——标题/操作钮/对话框已上提外壳）
// ═══════════════════════════════════════════

@Composable
private fun ShellToolsListSection(
    viewModel: ShellToolsViewModel,
    onSelectDevice: () -> Unit,
    modifier: Modifier = Modifier,
    onGoSettings: (() -> Unit)? = null,
    onOpenCustomEditor: () -> Unit = {},
) {
    val deviceState by viewModel.deviceState.collectAsState()
    val detectError by viewModel.detectError.collectAsState()
    // 检测 busy 态（manual-only——轮询不置位），驱动状态栏后缀 + 刷新钮禁用
    val detecting by viewModel.detecting.collectAsState()
    val selectedSerial by viewModel.selectedSerial.collectAsState()
    // 转无线在态（动作钮连点防抖），与页级回执 effect 同流
    val wirelessState by viewModel.wirelessState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val toolNodeState by viewModel.toolNodeStateFlow.collectAsState()
    val mainConfigError by viewModel.mainConfigError.collectAsState()

    // 主配置错误 banner 可手动关闭；错误内容变化时重新展示
    var errorBannerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(mainConfigError) { errorBannerDismissed = false }

    // 首次加载刷新设备与配置。
    // 进页即刷：PageHomeContent 为即时切换分发
    //（无 AnimatedContent 过渡），切页即销毁本组合、回页重新组合——
    // LaunchedEffect(Unit) 天然以可见性为 key：每次进入本页都重跑，设备态
    // 回页自动重试；手动刷新钮保留。零改动钉死语义，防后续加过渡动画破语义。
    LaunchedEffect(Unit) {
        viewModel.fetchShellToolsConfig()
        viewModel.refreshDevices()
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.space12),
    ) {
        // 设备状态栏 + 搜索框同行：状态胶囊 inline-flex 内容宽（文字列 weight
        // fill=false 只吃到自身所需，常态不铺满整行），搜索框 weight(1f) 补足行尾
        // ——设备连接态不再独占一行。三分渲染/刷新/去设置 CTA/直选 chips 形态不变；
        // Multi ≤3 台 chips 态胶囊最宽，搜索框随之让宽（长报错文本由胶囊内单行
        // ellipsis 兜底，搜索框不会被挤没——胶囊宽度上限 = 行剩余宽）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceStatusBar(
                deviceState = deviceState,
                detectError = detectError,
                detecting = detecting,
                selectedSerial = selectedSerial,
                onRefresh = { viewModel.refreshDevices() },
                onSelectDevice = onSelectDevice,
                onPickDevice = { serial -> viewModel.selectDevice(serial) },
                onClearSelection = { viewModel.clearDeviceSelection() },
                onGoSettings = onGoSettings,
                // 转无线两参非缺省传递（null/false 缺省 = 钮不渲染）——设备栏是
                // 共享渲染件两页两处挂载，此处缺参即「观测页有钮、Shell 页无钮」
                // 的用户可见不一致
                onGoWireless = { viewModel.startWirelessConnect() },
                wirelessConnecting = wirelessState.connecting,
            )
            SearchField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = "搜索工具名或命令内容...",
            )
        }

        // 主配置错误 banner：主配置读失败/解析失败时回退内置默认配置并提示（可关闭）
        if (mainConfigError != null && !errorBannerDismissed) {
            StatusBanner(
                status = StatusBannerStatus.Error,
                text = mainConfigError ?: "",
                trailing = {
                    TextButton(onClick = { errorBannerDismissed = true }) {
                        Text("清除该提示")
                    }
                },
            )
        }

        // 工具树状态拆解（本页是 StatusView 全仓唯一生产消费点，Failed/Loading
        // 在消费点对齐同页状态语言——错误 = StatusBanner(Error) + trailing 重试钮
        // （fetchShellToolsConfig 幂等可重入）；加载 = 行内圈 + 指路文案
        // （EvidenceTimeline 读取中同款 progressInline 配方，替代 hero 大圈孤岛）。
        // StatusView 组件本体留防御不动（shared 件）。
        // 空态四要素化（NN/g 模板）。前提：JResult.Empty 全仓零构造点（JResult.kt
        // 注释明示现网不可达，shelltools 链只产 Success/Failed/null）——Empty 分支
        // 与 Success(空列表) 同形兜底，空树真实形态 = Success(空列表)。
        // 主配置错误 banner 与空树同现时，直达按钮给出自救路径。
        when (val result = toolNodeState) {
            is JResult.Failed -> StatusBanner(
                status = StatusBannerStatus.Error,
                text = result.msg ?: result.code.desc,
                trailing = {
                    TextButton(onClick = { viewModel.fetchShellToolsConfig() }) {
                        Text("重试")
                    }
                },
            )

            is JResult.Success -> ToolTreeOrEmpty(
                toolNodes = result.data,
                viewModel = viewModel,
                onOpenCustomEditor = onOpenCustomEditor,
            )

            is JResult.Empty -> ToolTreeOrEmpty(
                toolNodes = emptyList(),
                viewModel = viewModel,
                onOpenCustomEditor = onOpenCustomEditor,
            )

            else -> {
                // Loading 与 null（数据未就绪）：行内指示非 hero 圈
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.space8),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Dimens.progressInline),
                        strokeWidth = Dimens.strokeThin,
                    )
                    Text(
                        "正在加载工具配置…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════
// 页头管理钮
// ═══════════════════════════════════════════

/**
 * 工具树有据/空树双形渲染：空树走 EmptyStateView 四要素（含「新增自定义命令」
 * 直达动作），有据走 [ShellToolsList]。Success(空列表) 与防御性 Empty 共用
 * （Empty 全仓零构造点，见上方状态拆解注释）。
 */
@Composable
private fun ToolTreeOrEmpty(
    toolNodes: List<ToolNode>,
    viewModel: ShellToolsViewModel,
    onOpenCustomEditor: () -> Unit,
) {
    if (toolNodes.isEmpty()) {
        EmptyStateView(
            text = "暂无工具",
            icon = MaterialSymbols.HANDYMAN,
            learningHint = "在配置目录放置 shell_tools.json 或新增自定义命令",
            actionLabel = "新增自定义命令",
            onAction = onOpenCustomEditor,
            fillParent = false,
        )
    } else {
        ShellToolsList(
            viewModel = viewModel,
            toolNodes = toolNodes,
        )
    }
}

/**
 * 页头「更多」管理钮：MORE_VERT 图标 + hover 气泡提示
 * （widget/interactions/TooltipIconButton——配方/契约详见该件 KDoc：
 * 显示延迟防划过闪现、isPersistent 常驻、enableUserInput=false 关自动驱动、
 * 显隐互斥由官方全局 MutatorMutex 保证。tooltip 与 contentDescription 并存
 * ——无障碍双通道）。
 */
@Composable
private fun ManageMenuTooltipButton(
    manageExpanded: Boolean,
    onToggle: () -> Unit,
) {
    TooltipIconButton(
        icon = MaterialSymbols.MORE_VERT,
        contentDescription = if (manageExpanded) "收起管理区" else "展开管理区",
        tip = "管理自定义命令",
        onClick = onToggle,
        modifier = Modifier.size(Dimens.minimumInteractiveSize),
        iconSize = Dimens.iconLarge,
        tint = if (manageExpanded) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * 输出台封顶高度的可用高上界：全额扣除树区硬底（weight 树实得的保底基准）、
 * 树↔分隔条与分隔条↔输出台两道 spacedBy 间隙、分隔条命中区、输出台纵向
 * padding（与两道间隙合计 space12×4）。封顶约束含台内边距（面板 heightIn
 * 挂在其根 Column padding 外侧），钳制到此值时树区实得 = 硬底 + 台内
 * padding 余量，不跌破。供拖拽钳制与初始封顶随可用高收缩共用，两处口径必须一致。
 *
 * internal 供 jvmTest 直测单源公式（复制式值级用例对结构漂移全盲）。
 */
internal fun consoleCapUpperBound(available: Dp): Dp =
    (available -
        Dimens.shellTreeHeightMin -
        Dimens.space12 * 4 -
        Dimens.splitterHitWidth)
        .coerceAtLeast(Dimens.consoleHeightMin)
