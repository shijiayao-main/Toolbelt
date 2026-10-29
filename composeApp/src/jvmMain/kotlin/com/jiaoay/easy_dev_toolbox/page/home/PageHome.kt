package com.jiaoay.easy_dev_toolbox.page.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalScaffoldGlassInsets
import com.jiaoay.easy_dev_toolbox.core.theme.LocalWindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.WindowSizeClass
import com.jiaoay.easy_dev_toolbox.core.theme.classifyWindowSize
import androidx.compose.foundation.layout.PaddingValues
import com.jiaoay.easy_dev_toolbox.host.AppStatusBar
import com.jiaoay.easy_dev_toolbox.host.StatusHostOverlay
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jiaoay.easy_dev_toolbox.widget.LogViewerDialog
import com.jiaoay.easy_dev_toolbox.widget.SidePanelHost
import com.jiaoay.easy_dev_toolbox.widget.glass.AuroraBackdrop
import com.jiaoay.easy_dev_toolbox.widget.glass.LocalToolboxGlassRoot
import com.jiaoay.easy_dev_toolbox.widget.glass.appContentFade
import com.jiaoay.easy_dev_toolbox.widget.glass.appHazeSource

/**
 * 根布局（五舱形态轻量版全屏铺重排）：
 * 叠层结构 `Box { ContentViewport(+inspector Row) → WorkspaceTabBar → dock → 状态栏 }`
 * + StatusHost/palette 浮层（全屏铺后五舱收敛为「内容视口 + 三浮动 chrome」）。
 *
 * - 内容视口全屏铺：滚动视口延伸到玻璃 chrome 之下——玻璃才有
 *   东西可折射（`.content{inset:0}` 同构）；可见区经四边 contentViewport
 *   padding 让出玻璃带 + [appContentFade] 渐隐边带；
 * - dock 为「家 + 智能体 + 设备 + 桥接 + 命令入口」（PageHomeMenu）；
 * - 左侧 SpaceBar 迁入智能体中心页第四签（空间与 AI 强相关，主区不再常驻挂 view）；
 * - tab 条 = WorkspaceTabBar 浮动胶囊（TopStart 对齐，左缘与内容视口同 80dp 对齐线）；
 * - 右缘 inspector：现 SidePanelHost 壳直接承载（受控状态从 per-page 改全局单键）——
 *   可交互工作面不浮层化，保持 Row 布局槽。
 *
 * 全局快捷键（Ctrl+K 命令面板 / Ctrl+Tab 循环切页）由 main.kt 的
 * Window 级 onPreviewKeyEvent 承载（上移原因）：Window 级 preview
 * 在 Compose 焦点派发之前执行、不依赖焦点，启动首屏（无 Compose 焦点态）
 * 也恒可达；palette 开关状态在 application 作用域持有，经参数传入本层渲染。
 */
@Composable
fun PageHome(
    appViewModel: AppViewModel,
    paletteVisible: Boolean = false,
    onDismissPalette: () -> Unit = {},
    paletteCommandMode: Boolean = false,
    onPaletteNavigate: (com.jiaoay.easy_dev_toolbox.page.ToolPage, com.jiaoay.easy_dev_toolbox.page.ToolPage?) -> Unit = { _, _ -> },
) {
    val debugEnabled by appViewModel.debugPageEnabled.collectAsState()
    // ── aurora 开关（blur 只属浮层且恒真玻璃；本开关只控制 AuroraBackdrop 光斑绘制）──
    val auroraEnabled by appViewModel.auroraEnabled.collectAsState()
    // 玻璃根（App 级 provides——快捷键参考卡浮层同 palette/toast 一语言）：
    // PageHome 只消费 LocalToolboxGlassRoot 现值（appHazeSource 挂内容包装层）。
    val glassRoot = LocalToolboxGlassRoot.current
    // 运行时隐藏页与 AI 置灰页——单一产出点，向 dock 与命令面板分发
    val hiddenPages = rememberHiddenNavPages()
    val aiDisabledPages = rememberAiDisabledPages()
    // 侧栏活动指示（单一产出点同上）：page → Running/Attention
    val navActivity = rememberNavActivity()
    // palette frecency 排序供数（usage counts）
    val usageCounts by appViewModel.pageUsageCounts.collectAsState()
    val currentPage by appViewModel.currentPage.collectAsState()
    // tab Auto-hide 顶部让位联动：openTabs 空集（tab 条隐藏）
    // 时顶让位收敛 8dp，首个文档 tab 打开扩回 50dp——insets 提供点改响应式，
    // PageScaffold scrollable 页的 contentPadding 随重组自然过渡。
    val openTabs by appViewModel.openTabs.collectAsState()

    // inspector 槽位数据：当前页的侧栏贡献（页面作用域——tab 容器内主视图即
    // 页面 content，语义未断）
    val sideBarContributions = remember(currentPage) { ToolPageRegistry.sideBarOf(currentPage) }

    // inspector 受控状态：**全局单键**（per-page sidebar_* 键已退役；
    // 恢复/回写经 EasyDataManager 全局键，宽度/窄轨态全空间共享）
    var inspectorWidth by remember { mutableFloatStateOf(Dimens.sideBarWidthDefault.value) }
    var inspectorRailMode by remember { mutableStateOf(false) }

    // 持久化装配（注入式——:ui/:core 不认识 EasyDataManager）：
    // 回写 scope：与宿主 UI 同生命周期（DisposableEffect onDispose 一并取消）。
    val persistScope = remember { CoroutineScope(Dispatchers.Default) }
    // 防抖落盘：拖宽每帧触发，落盘合并到拖停后 500ms（App.kt 窗口几何防抖同配方）
    var inspectorPersistJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) {
        inspectorWidth = com.jiaoay.easy_dev_toolbox.host.EasyDataManager.getInspectorWidth()
        inspectorRailMode = com.jiaoay.easy_dev_toolbox.host.EasyDataManager.getInspectorRailMode()
    }
    val persistInspector: (Float, Boolean) -> Unit = { width, rail ->
        inspectorPersistJob?.cancel()
        inspectorPersistJob = persistScope.launch {
            delay(PANEL_PERSIST_DEBOUNCE_MS)
            com.jiaoay.easy_dev_toolbox.host.EasyDataManager.setInspectorState(width, rail)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // 关卸兜底（App.kt 窗口几何销毁写同配方）：防抖可能吃掉最后一次变化，
            // 同步阻塞写保证终值落盘后再取消 scope（值未变时为幂等空翻写，代价一次落盘）
            val width = inspectorWidth
            val rail = inspectorRailMode
            kotlinx.coroutines.runBlocking {
                com.jiaoay.easy_dev_toolbox.host.EasyDataManager.setInspectorState(width, rail)
            }
            persistScope.cancel()
        }
    }

    // ── 响应式接线：classifyWindowSize——Compact 档侧栏强制窄视图。
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowSizeClass = classifyWindowSize(
            widthDp = constraints.maxWidth.toFloat() / LocalDensity.current.density,
            compactMaxDp = Dimens.breakpointCompactMax.value,
            mediumMaxDp = Dimens.breakpointMediumMax.value,
            wideMaxDp = Dimens.breakpointWideMax.value,
        )
        CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
        // ── 玻璃采样 source 标记（根由 App 级 provides，本层只挂 source）──
        // AuroraBackdrop 铺根（玻璃折射底料），appHazeSource 挂 aurora+主内容包装层
        // （Desktop 无原生 backdrop 捕获，HazeInput.Backdrop 靠 source-capture 回退——
        //  背景须挂 source 才可靠；source 组合先于全部 glass sink）。
        Box(modifier = Modifier.fillMaxSize().appHazeSource(glassRoot)) {
            AuroraBackdrop(enabled = auroraEnabled)

            // ── 内容视口（真全屏铺 + 让位下沉）──
            // 包裹层 Modifier.padding 是布局硬隔离——内容永不进
            // 玻璃带，blur 无物可折射；且 fade 带锚在 padding 内侧（可见区 y 50..84）
            // 导致每页页头常态处于淡出斜坡（~0.7 发虚）。修正 = 视口全屏铺 +
            // 让位经 LocalScaffoldGlassInsets 下发给 PageScaffold（scrollable 页 =
            // 滚动 contentPadding 语义，内容滚出时穿过玻璃带被折射——CSS
            // .content{padding} 行为等价物）；appContentFade 直接挂全屏视口，
            // mask 带锚回窗口玻璃带边缘（0..34 / H-34..H）。
            // 顶让位响应式——openTabs 空集（tab 条 Auto-hide
            // 整体隐藏）时收敛窗缘隙 8dp，非空扩回 50dp；dock/inspector 让位不动
            //（inspectorTopClearance 是独立 padding token，不消费本 insets）。
            // auroraMaskTopBegin=52dp 是常量遮罩锚——openTabs 空时让位 8dp 后
            //    遮罩起点悬空 44dp（光斑提前照到页头区）：视觉层微瑕疵，留走查核
            //    （mask 动态化超 VM 档）。
            CompositionLocalProvider(
                LocalScaffoldGlassInsets provides PaddingValues(
                    top = if (openTabs.isEmpty()) {
                        Dimens.contentViewportPaddingTopNoTabs
                    } else {
                        Dimens.contentViewportPaddingTop
                    },
                    start = Dimens.contentViewportPaddingStart,
                    end = Dimens.contentViewportPaddingEnd,
                    bottom = Dimens.contentViewportPaddingBottom,
                ),
            ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .appContentFade(),
                ) {
                    // 主内容（PageHomeContent 即时切换分发——原 WorkspaceTabs 内容位迁入）
                    PageHomeContent(appViewModel = appViewModel)
                    // 首启导览卡（随内容迁入视口——OnboardingCard 迁移回归专验项）：
                    // 家页且三空判定成立时叠内容层之上，判定转非空自动消失。
                    if (currentPage == ToolPage.LogWorkbench) {
                        OnboardingCard(
                            usageCounts = usageCounts,
                            onNavigate = { appViewModel.setCurrentPage(it) },
                        )
                    }
                }

                // ── inspector（侧栏槽位壳平移；Start/End 纵排合并渲染）──
                // 架构师裁定：可交互工作面不浮层化——保持 Row 布局槽（J1 拖宽语义零改动）
                if (sideBarContributions.isNotEmpty()) {
                    SidePanelHost(
                        contributions = sideBarContributions,
                        widthDp = inspectorWidth,
                        railMode = inspectorRailMode || windowSizeClass == WindowSizeClass.Compact,
                        // J1 配方：增量累加进活状态（同帧多 event 正确收敛）
                        onWidthDelta = { delta ->
                            inspectorWidth = (inspectorWidth + delta).coerceIn(
                                Dimens.sideBarWidthMin.value,
                                Dimens.sideBarWidthMax.value,
                            )
                            persistInspector(inspectorWidth, inspectorRailMode)
                        },
                        onRailModeChange = { rail ->
                            inspectorRailMode = rail
                            persistInspector(inspectorWidth, rail)
                        },
                    )
                }
            }
            }

            // ── 浮动叠层一：tab 条（TopStart 对齐——左缘与内容视口同 80dp 对齐线）──
            WorkspaceTabBar(
                appViewModel = appViewModel,
                aiDisabledPages = aiDisabledPages,
                navActivity = navActivity,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = Dimens.contentViewportPaddingStart,
                        top = Dimens.glassFloatInset,
                        end = Dimens.glassFloatInset,
                    )
                    .fillMaxWidth(),
            )

            // ── 浮动叠层二：dock（左缘，贴边整列）──
            // 让位语义（.rail 全顶排，无沉底系统区）：dock 与
            // 状态栏（bottom 8 横贯全窗）无槽位重叠；内容侧让位经
            // contentViewportPaddingBottom(50dp) 承担，dock 自身无让位消费点。
            PageHomeMenu(
                appViewModel = appViewModel,
                hiddenPages = hiddenPages,
                aiDisabledPages = aiDisabledPages,
                navActivity = navActivity,
            )

            // ── 浮动叠层三：状态栏（BottomCenter——左右下 8dp 窗缘间隙）──
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.glassFloatInset,
                        end = Dimens.glassFloatInset,
                        bottom = Dimens.glassFloatInset,
                    ),
            ) {
                AppStatusBar(appViewModel = appViewModel)
            }
        }

    // 全局浮层反馈通道（状态栏上方右下角；空 toast 零渲染）——浮层恒真玻璃
    StatusHostOverlay()

    CommandPalette(
        visible = paletteVisible,
        onDismiss = onDismissPalette,
        onNavigate = onPaletteNavigate,
        debugEnabled = debugEnabled,
        // palette 浮层恒 sheet 玻璃档（无开关、不随设置变化）
        usageCounts = usageCounts,
        hiddenPages = hiddenPages,
        aiDisabledPages = aiDisabledPages,
        commandMode = paletteCommandMode,
        // 推荐首页「最近空间」行回写：与 SpaceBar select 同款
        // AppViewModel.setCurrentSpace 回写（App 级 currentSpaceId 真源——深链泛化
        // 与 FileBuffer→空间写入的判定源）
        onSelectSpace = { id -> appViewModel.setCurrentSpace(id) },
        // 剪贴板识别推荐行预选通道：宿主预选槽写入（AppViewModel.pendingToolsTab
        // ——ToolsPage 既有双 effect 配方消费，与 palette 深入模式共用同一槽，
        // 推荐行走本参数单通道、不走 deepLink 第二参）
        onPreselectToolsTab = { index -> appViewModel.setPendingToolsTab(index) },
    )

    // 应用内查看器挂载面（palette 命令/日志对比差异行跳转/工作台证据流截断动作
    // 三生产面的共享消费口）：生产侧 offer 写槽、本层 collect 挂 Dialog（Dialog 是
    // 独立窗口，挂载点不影响布局）。漏 collect = 各生产动作静默 no-op，由
    // LogViewerPaletteWiringGuardTest 源码锚钉死。markHostMounted 置位挂载信号——
    // 生产面据此降级（未挂载时可见回退动作，不静默）。
    LaunchedEffect(Unit) { LogViewerRequest.markHostMounted() }
    val pendingLogView by LogViewerRequest.pending.collectAsState()
    pendingLogView?.let { request ->
        LogViewerDialog(
            filePath = request.path,
            initialLine = request.initialLine,
            // 初始定位时间戳透传（行号与时间戳并存时行号优先——裁决单源在
            // LogViewerDialog 的初始定位决策，本层只透传）
            initialTimestamp = request.initialTimestamp,
            // run 级书签持久化锚透传（null = 会话级书签现状；工作台 run 产物
            // 打开请求携带 runId——本层只透传不裁决）
            bookmarkRunId = request.runId,
            onDismiss = { LogViewerRequest.consume() },
        )
    }
    }
    }
}

/** inspector 持久化防抖窗口（拖拽每帧写，落盘合并到拖停后） */
private const val PANEL_PERSIST_DEBOUNCE_MS = 500L
