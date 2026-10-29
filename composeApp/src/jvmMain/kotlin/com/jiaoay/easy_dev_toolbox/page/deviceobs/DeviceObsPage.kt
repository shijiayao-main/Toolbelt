package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ScrollState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.toolbelt.device.DeviceFileBrowser
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.toolbelt.device.DeviceLayoutInspector
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DevicePackageBrowser
import dev.vicart.compose.material.symbols.MaterialSymbols

import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource
import com.jiaoay.toolbelt.device.DeviceWirelessConnector
import com.jiaoay.toolbelt.device.DeviceWindowViewsInspector
import com.jiaoay.toolbelt.device.KeyEventInjector
import com.jiaoay.toolbelt.device.LayoutTreeNode
import com.jiaoay.toolbelt.device.PerfSampler
import com.jiaoay.easy_dev_toolbox.core.navigation.GoSettingsHook
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.page.logcat.LogcatPanel
import com.jiaoay.easy_dev_toolbox.page.logcat.LogcatViewModel
import com.jiaoay.easy_dev_toolbox.page.screencast.ScrcpyControlProtocol
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastDetachedWindowController
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastLayoutHighlight
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastLayoutHighlightFrame
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastPanel
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastStatus
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastViewModel
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.PageScaffold
import com.jiaoay.easy_dev_toolbox.widget.SegmentedControl
import com.jiaoay.easy_dev_toolbox.widget.device.DeviceSelectDialog
import com.jiaoay.easy_dev_toolbox.widget.device.DeviceStatusBar
import kotlinx.coroutines.launch

/** 子页索引：0 = 观测台（编排行 + 四成员面板）/ 1 = 设备文件浏览 / 2 = 设备应用 / 3 = 窗口布局 */
internal const val OBS_SUBPAGE_CONSOLE = 0

/** 子页索引：设备文件浏览（[DeviceFileBrowserPanel]） */
internal const val OBS_SUBPAGE_FILES = 1

/** 子页索引：设备应用（[DevicePackagePanel]） */
internal const val OBS_SUBPAGE_APPS = 2

/** 子页索引：窗口布局（[DeviceLayoutPanel]，全窗口视图层级树 + uiautomator 降级） */
internal const val OBS_SUBPAGE_LAYOUT = 3

/**
 * 设备观测空间页（宿主聚合页，智能体中心先例同配方）：feature 模块贡献内容
 * 组件（:feature-logcat 的 [LogcatPanel]），宿主自有页承载页头与设备栏。
 *
 * - 页内子页切换（智能体中心页内多页签同配方）：四子页共用同一目标设备单源，
 *   设备栏并入页头行（[PageScaffold] actions 槽——页级常驻语义与子页无关，吸顶
 *   页头使滚动后设备选择仍可见，且不再独占一行压缩纵向空间），[SegmentedControl]
 *   + [AnimatedContent] 分发「观测台」「文件」「应用」「布局」四子页——观测台子页 =
 *   编排行 + 四成员面板原序（子页内 [verticalScroll]：四成员 + 420dp 帧的容量账
 *   必超视口，无滚动即超窗裁切、底部成员不可达；滚动只包本分支——上提容器会使
 *   文件/应用分支的 LazyColumn 无界化，智能体中心页滚动缺口纪律同款）；文件子页
 *   = [DeviceFileBrowserPanel]；应用子页 = [DevicePackagePanel]；布局子页 =
 *   [DeviceLayoutPanel]（三工作区子页各自独立承载全幅，不与观测台成员争同屏
 *   空间）。子页选择经 [DeviceObsConfigStore] 跨启动持久（工具页/智能体中心
 *   子签先例同款口径——初值合成见函数内注释）；
 * - 切到文件子页 = 观测台成员离台（AnimatedContent 非活跃分支离组合）：三采集
 *   引擎是 App 级单实例，进行中的会话不中断；性能采样门控随成员面板离台解除
 *   （与整页离台同口径），回观测台自动恢复接线；
 * - 设备栏消费 :core 设备域 App 级单源（[DeviceHub.monitor]）：检测/轮询/选定态
 *   与 Shell 工具页共享同一单源——切页不丢选择，两页各挂同一 :ui 渲染件
 *   （非两份实现）；
 * - 可见性门控接线（进入组合 +1、退出 −1，计数归并语义见 DeviceMonitor：
 *   任一消费页在台即轮询）——观测页可独立于 Shell 页打开，单源门控不可只由
 *   Shell 页接线；
 * - logcat 抓取引擎是 App 级单实例（:feature-logcat LogcatRuntime）：在本页与
 *   Shell 页之间切页抓取不中断；
 * - 「去设置配置 adb 路径」CTA 经 :core 中立槽 [GoSettingsHook]（宿主 AppViewModel
 *   wire 切设置页——零参 content 工厂无法传回调实参，ShellToolsPage 同款槽配方）；
 * - 复现记录编排行（[ReproRecordRow]）：logcat/性能/投屏 一键同起同停的聚合钮行
 *   （编排器零新状态、回执聚合经 StatusSignals 同通道，契约见其文件头）。
 *
 * @param monitor 设备域单源（场景守护注入缝：生产默认 [DeviceHub.monitor]，
 *   场景测试注入 fake 隔离真 adb）
 * @param sampler 性能采样器（注入缝同 [monitor]，场景测试注入 fake gateway 实例）
 * @param keyInjector 按键注入器（注入缝同 [monitor]）
 * @param wirelessConnector 一键转无线连接器（注入缝同 [monitor]：设备栏「转无线」动作的
 *   三步编排内核，回执/拒绝经 [StatusSignals] toast 通道由本页落地）
 * @param fileBrowser 设备文件浏览器内核（注入缝同 [monitor]）
 * @param packageBrowser 设备应用浏览器内核（注入缝同 [monitor]：应用子页 VM 的
 *   挂起函数源）
 * @param windowViewsInspector 全窗口视图检查内核（注入缝同 [monitor]：布局子页 VM
 *   的主源挂起函数——多窗口 View 内部层级；无状态请求-回执件，非 App 级单源形态
 *   也零常驻开销）
 * @param layoutInspector 窗口布局降级检查内核（注入缝同 [monitor]：布局子页 VM
 *   的降级源挂起函数——全窗口视图不可用时显式降级 uiautomator dump；无状态
 *   请求-回执件，非 App 级单源形态也零常驻开销）
 * @param initialSubpage 初始子页（注入缝同 [monitor]：生产恒 [OBS_SUBPAGE_CONSOLE]
 *   ——初值合成回落持久值；场景测试预置文件/应用/布局子页渲染面，缝值优先于持久值）
 * @param consoleScrollState 观测台子页滚动状态（场景守护注入缝：生产 null =
 *   [rememberScrollState] 自建；场景测试注入预置状态驱动滚动断言）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceObsPage(
    monitor: DeviceMonitor = DeviceHub.monitor,
    sampler: PerfSampler = DeviceHub.perfSampler,
    keyInjector: KeyEventInjector = DeviceHub.keyInjector,
    wirelessConnector: DeviceWirelessConnector = DeviceHub.wirelessConnector,
    fileBrowser: DeviceFileBrowser = DeviceHub.fileBrowser,
    packageBrowser: DevicePackageBrowser = DeviceHub.packageBrowser,
    windowViewsInspector: DeviceWindowViewsInspector = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub.windowViewsInspector,
    layoutInspector: DeviceLayoutInspector = com.jiaoay.easy_dev_toolbox.core.device.DeviceHub.layoutInspector,
    initialSubpage: Int = OBS_SUBPAGE_CONSOLE,
    consoleScrollState: ScrollState? = null,
) {
    DisposableEffect(monitor) {
        monitor.setPageVisible(true)
        onDispose { monitor.setPageVisible(false) }
    }
    // 进页即刷（用户可感知入口，置 busy）：PageHomeContent 为即时切换分发，
    // 切页即销毁本组合、回页重新组合——LaunchedEffect(monitor) 天然以可见性为 key
    // （ShellToolsPage 同款口径），设备态回页自动重试。
    LaunchedEffect(monitor) {
        monitor.refreshDevices()
    }

    val deviceState by monitor.deviceState.collectAsState()
    val detectError by monitor.detectError.collectAsState()
    val detecting by monitor.detecting.collectAsState()
    val selectedSerial by monitor.selectedSerial.collectAsState()
    val issues by monitor.issues.collectAsState()
    var showDeviceDialog by remember { mutableStateOf(false) }
    var subpage by remember { mutableStateOf(initialSubpage) }

    // 子页初值合成 + 持久回写（ToolsPage 预选槽→持久值同款配方，本页预选缝 =
    // initialSubpage 注入参数）：
    // ① 注入缝非默认值（场景测试预置文件/应用/布局子页）→ 缝值优先；
    // ② 生产恒观测台 → 初值回落持久值（跨启动记忆）；坏值（不在
    //    0..DeviceObsConfigStore.SUBPAGE_MAX_INDEX，如手工改 9）回观测台——
    //    脏数据回默认而非就近夹取（刻意不用 coerceIn）。
    // 切子页低频直写（无 debounce，last_page 同款口径）——scope 承接挂起 IO。
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (initialSubpage == OBS_SUBPAGE_CONSOLE) {
            subpage = DeviceObsConfigStore.getSelectedSubpage().let { v ->
                if (v in 0..DeviceObsConfigStore.SUBPAGE_MAX_INDEX) v else OBS_SUBPAGE_CONSOLE
            }
        }
    }
    val onSelectSubpage: (Int) -> Unit = { index ->
        subpage = index
        scope.launch { DeviceObsConfigStore.setSelectedSubpage(index) }
    }

    // logcat/投屏 VM 上提本页持有（与面板内建 viewModel() 同 key 同 ViewModelStore
    // 实例），传值入面板供其直用——复现记录编排行（ReproRecordRow）与面板共享
    // 同一 VM：编排转发与面板回执/hint 同源，防两份实例互不相见。
    // 文件浏览 VM 同配方上提页级持有：切子页（AnimatedContent 销毁非活跃分支）
    // 不丢目录导航栈。
    val logcatViewModel = viewModel(key = "logcat-capture") { LogcatViewModel(monitor) }
    val screencastViewModel = viewModel(key = "screencast") { ScreencastViewModel(monitor) }
    val fileViewModel = viewModel(key = "device-file-browser") {
        DeviceFileBrowserViewModel(monitor, fileBrowser::listDirectory)
    }
    // 应用面板 VM 同配方上提页级持有：切子页（AnimatedContent 销毁非活跃分支）不丢
    // 包列表读数与详情缓存；采样器 StateFlow 接页级注入的 sampler（与 PerfPanel
    // 同一单源——运行态融合只读消费）
    val packageViewModel = viewModel(key = "device-package-browser") {
        DevicePackageViewModel(
            monitor,
            packageBrowser::listPackages,
            packageBrowser::packageDetails,
            perfState = sampler.state,
        )
    }
    // 布局子页 VM 同配方上提页级持有：切子页不丢层级快照与展开/选中态；层级
    // 抓取是显式动作（VM 侧不随进页/换目标自动 spawn）；主源 = 全窗口视图内核，
    // 降级源 = uiautomator dump 内核（降级编排在 VM，失败人话不静默）
    val layoutViewModel = viewModel(key = "device-layout-inspector") {
        DeviceLayoutViewModel(
            monitor,
            windowViewsInspector::dumpWindowViews,
            layoutInspector::dumpHierarchy,
        )
    }

    val goSettings: () -> Unit = remember {
        {
            GoSettingsHook.offer()
            Unit
        }
    }

    // 转无线回执经既有 toast 通道落地（回执/拒绝各恰一条）：eventSeq 为事件去重键
    // ——同文本的重复事件不丢条（回执正文自带步骤定位与去向指引）。
    val wirelessState by wirelessConnector.state.collectAsState()
    LaunchedEffect(wirelessState.eventSeq) {
        wirelessState.lastReceipt?.let { StatusSignals.show(it.message) }
        wirelessState.rejectError?.let { StatusSignals.show(it) }
    }

    PageScaffold(
        title = "设备观测",
        headerSticky = true,
        // 设备栏并入页头行（纵向空间收益：不再独占一行；吸顶页头使观测台子页滚动
        // 后设备选择仍可见）——组件本体零改动，宿主侧只挪挂载位置
        actions = {
            DeviceStatusBar(
                deviceState = deviceState,
                detectError = detectError,
                detecting = detecting,
                selectedSerial = selectedSerial,
                onRefresh = { monitor.refreshDevices() },
                onSelectDevice = { showDeviceDialog = true },
                onPickDevice = { serial -> monitor.selectDevice(serial) },
                onClearSelection = { monitor.clearDeviceSelection() },
                onGoSettings = goSettings,
                onGoWireless = { wirelessConnector.startWirelessConnect() },
                wirelessConnecting = wirelessState.connecting,
                issues = issues,
            )
            // 跨子页聚焦入口：投屏 Streaming 期任意子页（文件/应用/布局）一键回窗——
            // 控制器 KDoc 明文预留的合法点选面通道（openOrFocus 唯一入口）；非
            // Streaming 态整块缺席（无窗可聚焦，不占页头宽度）
            val screencastStatus by screencastViewModel.status.collectAsState()
            if (screencastStatus is ScreencastStatus.Streaming) {
                Spacer(modifier = Modifier.width(Dimens.space8))
                TextButton(onClick = { ScreencastDetachedWindowController.openOrFocus() }) {
                    AppSymbolIcon(
                        icon = MaterialSymbols.MONITOR,
                        contentDescription = null,
                        size = Dimens.iconSmall,
                    )
                    Text("聚焦投屏窗")
                }
            }
        },
    ) {
        SegmentedControl(
            options = listOf("观测台", "文件", "应用", "布局"),
            selectedIndex = subpage,
            onSelect = onSelectSubpage,
        )

        // 子页切换动效（智能体中心页签同款配方——fadeIn standard + 温和上浮 +
        // fadeOut fast）；PageScaffold 吸顶分支给 content 有界高度，文件子页的
        // 条目列表得以 weight(1f) 独立承载全幅。
        AnimatedContent(
            targetState = subpage,
            transitionSpec = {
                (fadeIn(animationSpec = AppMotion.standard()) +
                    slideInVertically(animationSpec = AppMotion.standard()) { it / 16 })
                    .togetherWith(fadeOut(animationSpec = AppMotion.fast()))
            },
            label = "deviceObsSubpage",
        ) { current ->
            when (current) {
                OBS_SUBPAGE_CONSOLE -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(consoleScrollState ?: rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Dimens.space12),
                ) {
                    // 复现记录编排行：三成员一键同起同停（聚合回执经 StatusSignals，与成员
                    // 回执同通道）；成员状态徽标各读各的既有 StateFlow，编排器零镜像状态
                    ReproRecordRow(
                        devices = monitor,
                        logcat = logcatViewModel,
                        screencast = screencastViewModel,
                        sampler = sampler,
                    )

                    LogcatPanel(
                        devices = monitor,
                        onGoSettings = goSettings,
                        sharedViewModel = logcatViewModel,
                    )

                    ScreencastPanel(
                        devices = monitor,
                        onGoSettings = goSettings,
                        sharedViewModel = screencastViewModel,
                        // 独立视口窗桥接（面板参数注入——面板不直读控制器单例）。行尾主钮
                        // Streaming 态恒为「聚焦窗口」，共用 controller.openOrFocus 唯一入口
                        // （防重窗收口，见其 KDoc）；关窗路径收口在控制器（onCloseRequest/
                        // 停投自关/死窗重稳汇入 recall——窗关即停投），面板无收回钮
                        onOpenDetached = ScreencastDetachedWindowController::openOrFocus,
                    )

                    PerfPanel(
                        devices = monitor,
                        sampler = sampler,
                        onGoSettings = goSettings,
                    )

                    KeyPadPanel(
                        devices = monitor,
                        injector = keyInjector,
                        onGoSettings = goSettings,
                    )
                }

                OBS_SUBPAGE_FILES -> DeviceFileBrowserPanel(
                    devices = monitor,
                    sharedViewModel = fileViewModel,
                    onGoSettings = goSettings,
                )

                OBS_SUBPAGE_APPS -> DevicePackagePanel(
                    devices = monitor,
                    sharedViewModel = packageViewModel,
                    onGoSettings = goSettings,
                )

                else -> {
                    val panelState by layoutViewModel.state.collectAsState()
                    val screencastStatusState by screencastViewModel.status.collectAsState()
                    // 选中节点上投屏帧画框联动（显示型动作）：门控与「投屏点亮」同口径
                    // （Streaming + 串号一致 + dumpSerial 非 null）但拒绝时静默不上帧
                    // 零 toast——显式人话由「投屏点亮」既有拒绝文案承担。帧侧另有
                    // 独立串号守卫（ScreencastFrameView），双保险防跨会话残留。
                    LaunchedEffect(
                        panelState.selectedNode,
                        panelState.dumpSerial,
                        panelState.diagramLinked,
                        screencastStatusState,
                    ) {
                        val node = panelState.selectedNode
                        if (!panelState.diagramLinked || node == null ||
                            !layoutFrameHighlightEligible(screencastStatusState, panelState.dumpSerial)
                        ) {
                            ScreencastLayoutHighlight.clear()
                        } else {
                            val streaming = screencastStatusState as ScreencastStatus.Streaming
                            ScreencastLayoutHighlight.set(
                                ScreencastLayoutHighlightFrame(
                                    serial = panelState.dumpSerial,
                                    left = node.globalBounds.left,
                                    top = node.globalBounds.top,
                                    right = node.globalBounds.right,
                                    bottom = node.globalBounds.bottom,
                                    screenWidth = streaming.width,
                                    screenHeight = streaming.height,
                                ),
                            )
                        }
                    }
                    // 布局子页离开组合即清帧上高亮（切子页/切页不残留）
                    DisposableEffect(Unit) { onDispose { ScreencastLayoutHighlight.clear() } }
                    DeviceLayoutPanel(
                        devices = monitor,
                        sharedViewModel = layoutViewModel,
                        onGoSettings = goSettings,
                        // 投屏点亮：布局子页详情卡 → 页级持有的投屏 VM + 布局 VM + 设备域单源
                        // （跨子页动线；跨串号门控比对快照来源与投屏会话串号，判定与注入
                        // 收口在 [lightUpLayoutNode]）
                        onLightUp = { node -> lightUpLayoutNode(screencastViewModel, layoutViewModel, monitor, node) },
                    )
                }
            }
        }

        val multiState = deviceState as? DeviceState.Multi
        if (showDeviceDialog && multiState != null) {
            DeviceSelectDialog(
                devices = multiState.devices,
                onConfirm = { serial ->
                    monitor.selectDevice(serial)
                    showDeviceDialog = false
                },
                onDismiss = { showDeviceDialog = false },
            )
        }
    }
}

/** 「投屏点亮」判定产物：[Inject] = 可注入载荷；[Reject] = 拒绝人话（零注入）。 */
internal sealed interface LayoutLightUpDecision {

    /** 注入载荷：坐标 = 节点全局中心（设备屏幕像素）；屏幕尺寸 = Streaming 快照自带 width/height */
    data class Inject(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
    ) : LayoutLightUpDecision

    /** 拒绝人话回执（非 Streaming 各态与跨串号失配的指路文案，由 wiring 落 StatusSignals） */
    data class Reject(val message: String) : LayoutLightUpDecision
}

/**
 * 「投屏点亮」判定纯函数（投屏状态 × 快照来源串号 × 层级节点 → 注入载荷 | 拒绝人话）：
 * Streaming 且快照与投屏会话同串号 → 节点全局中心坐标（[LayoutTreeNode.globalBounds]
 * ——a11y 源恒等原生 bounds；全窗口视图源 = 父相对 bounds + 祖先链累加偏移 +
 * window:left/top 散键偏移的合成面）+ Streaming 快照屏幕尺寸的注入载荷（屏幕尺寸真源 = Streaming 状态自带
 * width/height）；Streaming 且串号失配 → 拒绝人话零注入（快照坐标属于另一台设备，
 * 注入必点错屏——多设备换选分叉的门控面）；Idle/Connecting/Stopped → 各态指路人话，
 * 零注入。
 *
 * 门控 null 语义（双侧不对称）：[dumpSerial] null = 快照来源不明——拒绝指路不静默
 * （生产面 refresh 成功恒落值，此分支为防御面）；Streaming 会话串号 null = 会话来源
 * 不可证——不拦截维持现回执（引擎 API 层可空，生产面前置检查后实际非 null）。
 * [dumpDeviceLabel] 仅作失配人话的快照侧设备标识（[deviceSerialLabel] 解析产物），
 * null 时回退串号原文。多显示设备上全窗口偏移归属节点所在 display，投屏帧跨
 * display 落点的真机校准属门禁欠账（调研报告 §5）。
 */
internal fun layoutLightUpDecision(
    status: ScreencastStatus,
    dumpSerial: String?,
    dumpDeviceLabel: String?,
    node: LayoutTreeNode,
): LayoutLightUpDecision = when (status) {
    is ScreencastStatus.Streaming -> when {
        dumpSerial == null ->
            LayoutLightUpDecision.Reject("布局快照来源设备不明——重新抓取层级后再点亮")

        status.serial != null && dumpSerial != status.serial ->
            LayoutLightUpDecision.Reject(
                "布局快照来自设备 ${dumpDeviceLabel ?: dumpSerial}——当前投屏的是设备 ${status.deviceName}；" +
                    "重抓层级或切回投屏设备后再点亮",
            )

        else -> LayoutLightUpDecision.Inject(
            x = node.globalBounds.centerX,
            y = node.globalBounds.centerY,
            screenWidth = status.width,
            screenHeight = status.height,
        )
    }

    ScreencastStatus.Idle ->
        LayoutLightUpDecision.Reject("投屏未在运行——回观测台开始投屏后可点亮")

    ScreencastStatus.Connecting ->
        LayoutLightUpDecision.Reject("投屏正在连接——出画后可点亮")

    is ScreencastStatus.Stopped ->
        LayoutLightUpDecision.Reject("投屏已停止——回观测台重新开始投屏后可点亮")
}

/**
 * 串号 → 设备展示标识（型号优先，缺失回退串号——[com.jiaoay.toolbelt.device.DeviceInfo.displayLabel]
 * 同口径）。设备注册表无此串号（已断开/不在场）返回 null——消费面自行回退串号原文。
 */
internal fun deviceSerialLabel(devices: DeviceStateSource, serial: String?): String? =
    when (val state = devices.deviceState.value) {
        is DeviceState.Single -> state.model.ifEmpty { state.serial }.takeIf { state.serial == serial }

        is DeviceState.Multi -> state.devices.firstOrNull { it.serial == serial }?.displayLabel

        DeviceState.None -> null
    }

/**
 * 选中节点上投屏帧画框的资格判定纯函数（internal 供单测，语义 = 能否上帧）：门控面
 * 与 [layoutLightUpDecision] 的 Inject 前置条件同口径（Streaming + dumpSerial 非
 * null + 会话串号一致）。
 *
 * serial null 不对称（有意登记，非疏漏）：[ScreencastStatus.Streaming.serial] null
 * 时点亮走「不证伪即放行注入」契约，本判定恒 false 不上帧——注入是用户显式动作、
 * 事后回执已标注坐标口径；帧上框是持续显示态，「来源不可证仍持续画框」的保守面
 * 更严。生产面 serial 恒非 null，此不对称仅在引擎 API 层可空防御面出现。
 */
internal fun layoutFrameHighlightEligible(
    status: ScreencastStatus,
    dumpSerial: String?,
): Boolean {
    val streaming = status as? ScreencastStatus.Streaming ?: return false
    return dumpSerial != null && streaming.serial != null && streaming.serial == dumpSerial
}

/**
 * 点亮动作 wiring（布局子页详情卡 → 页级持有的投屏 VM）：判定通过 → DOWN+UP
 * 成对注入节点全局中心（原地轻点手势，零 MOVE）；拒绝 → 人话回执经 [StatusSignals]。
 *
 * 回执只报注入事实非「点击成功」——控制通道对触摸注入无逐消息回执，设备端是否
 * 响应以设备自身屏幕为反馈面；注入坐标取自布局 dump 快照（dump 后界面已变则
 * 点错位，重新抓取即刷新），回执文案如实标注不冒充实时。
 *
 * 跨串号门控：快照来源串号（[DeviceLayoutViewModel] 面板态）与投屏会话串号
 * （Streaming 状态）失配即拒绝零注入——多设备换选后快照与会话分属两台设备时，
 * 快照坐标不得打进在投设备的屏。
 */
private fun lightUpLayoutNode(
    screencast: ScreencastViewModel,
    layout: DeviceLayoutViewModel,
    devices: DeviceStateSource,
    node: LayoutTreeNode,
) {
    val panel = layout.state.value
    when (
        val decision = layoutLightUpDecision(
            status = screencast.status.value,
            dumpSerial = panel.dumpSerial,
            dumpDeviceLabel = deviceSerialLabel(devices, panel.dumpSerial),
            node = node,
        )
    ) {
        is LayoutLightUpDecision.Inject -> {
            screencast.injectTouch(
                ScrcpyControlProtocol.MOTION_ACTION_DOWN,
                decision.x,
                decision.y,
                decision.screenWidth,
                decision.screenHeight,
            )
            screencast.injectTouch(
                ScrcpyControlProtocol.MOTION_ACTION_UP,
                decision.x,
                decision.y,
                decision.screenWidth,
                decision.screenHeight,
            )
            StatusSignals.show(
                "已注入点击 (${decision.x},${decision.y})——按布局快照坐标注入，设备屏幕即反馈面",
            )
        }

        is LayoutLightUpDecision.Reject -> StatusSignals.show(decision.message)
    }
}
