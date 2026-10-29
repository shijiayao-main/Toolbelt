package com.jiaoay.easy_dev_toolbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlin.system.exitProcess
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.UiPreloadState
import com.jiaoay.easy_dev_toolbox.core.initializer.InitResult
import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializerManager
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.AppTheme
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.host.AppTitleBar
import com.jiaoay.easy_dev_toolbox.host.UndecoratedResizeOverlay
import com.jiaoay.easy_dev_toolbox.host.EasyDataManager
import com.jiaoay.easy_dev_toolbox.host.HotkeyHub
import com.jiaoay.easy_dev_toolbox.host.rememberHotkeyActions
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.page.home.PageHome
import com.jiaoay.easy_dev_toolbox.page.home.DOCK_ENTRIES
import com.jiaoay.easy_dev_toolbox.page.home.ToolPageLabels
import com.jiaoay.easy_dev_toolbox.page.home.ToolPageRegistry
import com.jiaoay.easy_dev_toolbox.page.home.rememberHiddenNavPages
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import com.jiaoay.easy_dev_toolbox.widget.glass.LocalToolboxGlassRoot
import com.jiaoay.easy_dev_toolbox.widget.glass.rememberToolboxGlassRoot
import dev.vicart.compose.material.symbols.MaterialSymbols
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

@Composable
fun App(
    hostWindow: ComposeWindow? = null,
    uiPreload: UiPreloadState? = null,
    windowState: WindowState? = null,
    onClose: () -> Unit = {},
    paletteVisible: Boolean = false,
    onDismissPalette: () -> Unit = {},
    paletteCommandMode: Boolean = false,
) {
    val appViewModel = AppViewModel.getViewModel(uiPreload)
    val colorScheme by appViewModel.colorScheme.collectAsState()
    val themeMode by appViewModel.themeMode.collectAsState()
    val initState by JInitializerManager.initStateFlow.collectAsState()

    // 窗口标题随当前页动态化：任务栏/Alt-Tab 可辨页面。
    // 标题同时喂两处：AWT window.title（任务栏）+ 自绘 AppTitleBar（窗内标题栏）。
    // 页名单源：pageName 经 ToolPageLabels 单源取（与 tab 条/
    // 面包屑/dock tooltip 同源）——when 兜底链 + retiredPageLabel 全部删除。
    // 僵尸枚举（AppCenter/Default）恒不可达落 null → 窗口名 "Toolbelt"。
    // 词序归一 titlebar 口径——「Toolbelt — 页名」品牌先行；
    // 分隔符 em dash（.t 同款）。
    val currentPage by appViewModel.currentPage.collectAsState()
    val windowTitle = remember(currentPage) {
        val pageName = ToolPageLabels.label(currentPage)
        pageName?.let { "Toolbelt — $it" } ?: "Toolbelt"
    }
    LaunchedEffect(windowTitle, hostWindow) {
        hostWindow?.title = windowTitle
    }

    // 热键动作注册（host/HotkeyHub.kt）：Ctrl+Tab 环形切页直接持有
    // appViewModel（组合内可得，不经 main.kt 导航桥绕行）。
    // order 供给 = openTabs 现值（Ctrl+Tab 只在已打开 tab 间循环）。
    rememberHotkeyActions(
        currentPage = { appViewModel.currentPage.value },
        navigate = { appViewModel.setCurrentPage(it) },
        openTabs = { appViewModel.openTabs.value },
    )

    // 导航热键扩表接线（组合内可得 VM；析构注销）
    DisposableEffect(appViewModel) {
        HotkeyHub.openSettings = {
            appViewModel.setCurrentPage(ToolPage.Setting)
            true
        }
        HotkeyHub.jumpDockItem = { index ->
            // 跳转序同源化——直接消费
            // PageHomeMenu.DOCK_ENTRIES 单点数据（App.kt 手抄清单曾是双源漂移面，
            // dock 视觉序改版时 Ctrl+N 会跟旧清单走）。ShellTools 不在固定 tab 集，
            // 经 openTabs 折叠语义（setCurrentPage 追加动态 tab）承接渲染。
            val target = DOCK_ENTRIES.getOrNull(index - 1)?.page
            if (target != null) {
                appViewModel.setCurrentPage(target)
                true
            } else {
                false
            }
        }
        // Ctrl+W 关当前文档 tab（桌面惯例键盘通路）：当前页在 openTabs 才可关；
        // 空间页/单开系统页不占文档 tab——toast 反馈（cyclePage 空集分支同款），
        // 恒 consumed（放行会把 W 键漏进焦点文本框打出字母）
        HotkeyHub.closeCurrentTab = {
            val current = appViewModel.currentPage.value
            if (current in appViewModel.openTabs.value) {
                appViewModel.closeTab(current)
            } else {
                StatusSignals.show("当前页不占文档 tab——Ctrl+W 仅关闭已打开文档")
            }
            true
        }
        onDispose {
            HotkeyHub.openSettings = null
            HotkeyHub.jumpDockItem = null
            HotkeyHub.closeCurrentTab = null
        }
    }

    // 当前页兜底：若用户正停在那一页，会滞留在
    //    已隐藏的页面上。检测到当前页被隐藏即回退工作台家（家 = LogWorkbench，
    //    欢迎页废除）。
    val hiddenPages = rememberHiddenNavPages()
    LaunchedEffect(hiddenPages, currentPage) {
        if (currentPage in hiddenPages) {
            appViewModel.setCurrentPage(ToolPage.LogWorkbench)
        }
    }

    // 窗口几何记忆（EDT 阻塞修复见下）：
    // 普通态位置/尺寸变化时防抖写回 DataStore；最大化/图标化期间不写
    // （此时 bounds 是铺满/最小化几何）。写回在独立 IO 协程执行——
    // ComponentAdapter 回调在 AWT EDT 上派发，EDT 上 runBlocking 同步落盘
    // 会在拖拽/缩放期间造成周期性卡顿；退出兜底（onDispose）仅一次，保留阻塞写。
    DisposableEffect(hostWindow) {
        val frame = hostWindow ?: return@DisposableEffect onDispose { }
        val writeScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
        )
        var lastWrite = System.currentTimeMillis()
        val debounceMs = 800L

        /** 收集写回任务（IO 协程执行，EDT 只做状态判定与提交） */
        fun writeGeometryIfNormal() {
            val state = frame.extendedState
            if (state and java.awt.Frame.MAXIMIZED_BOTH != 0 || state and java.awt.Frame.ICONIFIED != 0) return
            val now = System.currentTimeMillis()
            if (now - lastWrite < debounceMs) return
            lastWrite = now
            val b = frame.bounds
            writeScope.launch {
                EasyDataManager.setWindowGeometry(b.x, b.y, b.width, b.height)
            }
        }

        val listener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = writeGeometryIfNormal()
            override fun componentResized(e: ComponentEvent) = writeGeometryIfNormal()
        }
        frame.addComponentListener(listener)
        onDispose {
            frame.removeComponentListener(listener)
            // 销毁兜底（非最大化/图标化时）：防抖可能吃掉最后一次变化；进程即将退出，
            // 同步阻塞写保证落盘完成后才继续退出
            val state = frame.extendedState
            if (state and java.awt.Frame.MAXIMIZED_BOTH == 0 && state and java.awt.Frame.ICONIFIED == 0) {
                val b = frame.bounds
                kotlinx.coroutines.runBlocking {
                    EasyDataManager.setWindowGeometry(b.x, b.y, b.width, b.height)
                }
            }
            writeScope.cancel()
        }
    }

    // 窗口形态——浮动态 = 16dp 圆角窗（AppShapes.glassPanel 复用，窗口即最大玻璃件）；
    // 最大化回正（null = 不裁剪满屏）。placement 由 mutableStateOf 支撑，读值即响应。
    val windowShape = if (windowState?.placement == WindowPlacement.Maximized) null else AppShapes.glassPanel

    AppTheme(scheme = colorScheme, themeMode = themeMode, windowShape = windowShape) {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 自绘标题栏（edge-to-edge）：windowState 缺席 = 非窗口宿主，退回无标题栏形态
            if (windowState != null) {
                AppTitleBar(
                    windowState = windowState,
                    title = windowTitle,
                    onClose = onClose,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when (val state = initState) {
                    is InitResult.Failed -> {
                        InitFailedView(state)
                    }

                    is InitResult.Success -> {
                        // 玻璃根上提本层（PageHome 内挂不到——快捷键参考卡浮层
                        // 读不到 root 被迫用 opaque 厚板——两个 Ctrl 键瞬态浮层
                        // 「同族不同质」不诚实）。根的职责 = 只服务浮层（palette/快捷键参考卡/
                        // toast/导览卡），全部浮层同 sheet 玻璃语言；挂 Success 分支内与
                        // 窗口内容共存亡，无卸载空引用（「App 级不挂」纪律针对的是
                        // application 作用域，非组合内）。
                        val glassRoot = rememberToolboxGlassRoot()
                        CompositionLocalProvider(LocalToolboxGlassRoot provides glassRoot) {
                            PageHome(
                                appViewModel = appViewModel,
                                paletteVisible = paletteVisible,
                                onDismissPalette = onDismissPalette,
                                paletteCommandMode = paletteCommandMode,
                                // palette 深入模式路由归并聚合页——
                                // 第二参 = 原散页枚举（palette openPageOf 经 ToolPageLabels.hostOf
                                // 归并后的预选载荷），宿主换算聚合页子签 index 写入
                                // AppViewModel 预选槽（ToolsPage 首次组合读一次设 tab 初值）。
                                // 双宿主分流：deep 落 Tools 槽（五件套→
                                // hostIndexOf）或 AgentCenter 槽（三退役 AI 页→agentCenterIndexOf）
                                // ——两槽互斥（deep 只落一宿主），槽消费即清无跨页残留。
                                onPaletteNavigate = { page, deep ->
                                    appViewModel.setCurrentPage(page)
                                    deep?.let { d ->
                                        when (ToolPageLabels.hostOf(d)) {
                                            ToolPage.AgentCenter -> appViewModel.setPendingAgentCenterTab(
                                                ToolPageLabels.agentCenterIndexOf(d),
                                            )
                                            ToolPage.Tools -> appViewModel.setPendingToolsTab(
                                                ToolPageLabels.hostIndexOf(d),
                                            )
                                            else -> {}
                                        }
                                    }
                                },
                            )
                            // 快捷键参考卡（HOTKEY_TABLE 渲染面，visible false 零渲染）
                            com.jiaoay.easy_dev_toolbox.host.HotkeyHelpCard()
                        }
                    }

                    null -> {
                        // 启动态入场淡入（全应用唯一允许的入场动画——切页过渡已退役）：首帧 visible=false，组合后淡入
                        var visible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { visible = true }
                        // 全限定：外层 Column 的 ColumnScope.AnimatedVisibility 扩展在隐式接收者
                        // 上下文里不可调用——此处语义是顶层 AnimatedVisibility（Box 内独立淡入）
                        androidx.compose.animation.AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(animationSpec = AppMotion.standard()),
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.size(Dimens.iconHero))
                                    Spacer(modifier = Modifier.height(Dimens.space12))
                                    Text(
                                        text = "正在初始化…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // 自绘缩放边条 overlay（最后兄弟 = 顶层；最大化退出渲染）——
        // 内建 resizer 真机「剧烈抖动」（两轮真机均输），自绘冻结快照版胜出
        if (hostWindow != null && windowState != null) {
            UndecoratedResizeOverlay(window = hostWindow, windowState = windowState)
        }
        }
    }
}

/** 高优初始化失败：列出失败项并提示重启（无重试能力，initializer 为一次性启动流程） */
@Composable
private fun InitFailedView(state: InitResult.Failed) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppSymbolIcon(
                icon = MaterialSymbols.ERROR_OUTLINE,
                contentDescription = null,
                size = Dimens.iconDisplay,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(Dimens.space12))
            Text(
                text = "初始化失败",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error
            )
            state.failedList.forEach {
                Text(
                    text = it.simpleName ?: it.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Button(onClick = { exitProcess(0) }) {
                Text("退出应用")
            }
        }
    }
}
