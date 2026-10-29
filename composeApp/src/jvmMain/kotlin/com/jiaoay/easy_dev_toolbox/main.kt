@file:OptIn(ExperimentalComposeUiApi::class)

package com.jiaoay.easy_dev_toolbox

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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.jiaoay.easy_dev_toolbox.core.AppViewModel
import com.jiaoay.easy_dev_toolbox.core.data.loadEnum
import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializerManager
import com.jiaoay.easy_dev_toolbox.core.runtime.AppHost
import com.jiaoay.easy_dev_toolbox.core.theme.AppThemeSpec
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeMode
import com.jiaoay.easy_dev_toolbox.core.UiPreloadState
import com.jiaoay.easy_dev_toolbox.page.ToolPage
import com.jiaoay.easy_dev_toolbox.host.LocalAppWindowScope
import com.jiaoay.easy_dev_toolbox.host.EasyDataManager
import com.jiaoay.easy_dev_toolbox.host.HotkeyHub
import com.jiaoay.easy_dev_toolbox.host.SavedWindowGeometry
import com.jiaoay.easy_dev_toolbox.host.validatedWindowGeometryOrNull
import com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastDetachedInitialGeometry
import com.jiaoay.easy_dev_toolbox.host.initializer.AiToolsRestoreInitializer
import com.jiaoay.easy_dev_toolbox.host.initializer.BundledAdbWarmupInitializer
import com.jiaoay.easy_dev_toolbox.host.initializer.LogFileRestoreInitializer
import com.jiaoay.easy_dev_toolbox.host.initializer.VisibleExportRootRestoreInitializer
import com.jiaoay.easy_dev_toolbox.host.initializer.SecretFilePermHardeningInitializer
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.media.AudioTranscoder
import com.jiaoay.easy_dev_toolbox.core.runtime.VoiceTranscriptionHub
import com.jiaoay.easy_dev_toolbox.page.larkbridge.LarkBridgeManager
import com.jiaoay.easy_dev_toolbox.page.voice.ModelStore
import com.jiaoay.easy_dev_toolbox.page.voice.SherpaTranscriber
import com.jiaoay.easy_dev_toolbox.page.voice.TranscriberNotReadyException
import com.jiaoay.easy_dev_toolbox.core.log.GlobalUncaughtExceptionHandler
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.log.LogbackHelper
import com.jiaoay.easy_dev_toolbox.core.system.NativeAllocatorTuner
import toolbelt.composeapp.generated.resources.Res
import toolbelt.composeapp.generated.resources.app
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.painterResource

fun main() {
    // glibc malloc 调参：必须先于一切 skia/JavaCV 原生分配（后到无效），否则投屏等
    // 多 MB 级 native 分配面的 RSS 高水位无修复——契约见 NativeAllocatorTuner 与
    // docs/core/native-memory.md
    NativeAllocatorTuner.install()
    // 启动埋点：JVM uptime 三段打点——main 入口 / 预载完 / 首帧就绪。
    // RuntimeMXBean.uptime 不受时钟回拨影响且进程内单调；输出经 JLog（开发者日志域可查）。
    val bootUptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime
    com.jiaoay.easy_dev_toolbox.core.log.JLog.i("StartupPerf") {
        "boot: main() 入口（JVM uptime=${bootUptimeMs}ms）"
    }
    // JVM 全局未捕获异常兜底：须在窗口组合前生效，初始化前的异常回落 DefaultLogImpl（stdout）
    GlobalUncaughtExceptionHandler.install()
    // 旧数据根改名迁移（~/.easy_dev_toolbox → ~/.toolbelt）：必须在一切数据根访问之前
    // 同步执行（下方的窗口几何/视觉态预载 runBlocking DataStore 读已会触盘建目录）
    com.jiaoay.easy_dev_toolbox.core.data.LegacyDataRootMigration.migrateIfNeeded()
    // 宿主初始化器注册（经 AppHost 单点；保序契约不变：注册必须在 startInit() 之前）：
    // 文件日志恢复（level 2，读宿主设置域）随 :core 日志引导（level 1）调度；
    // AI 工具总开关恢复（level 2）：首帧前回填，否则已关闭的用户会看到隐藏项闪现；
    // 密钥文件权限硬化（level 2）：preferences_pb 明文密钥落盘处补 POSIX 600，失败不阻断；
    // 产物目录配置恢复（level 2）：可见产物树根的 DataStore 配置读入 FileUtils 单源
    AppHost.registerInitializers(
        listOf(
            LogFileRestoreInitializer(),
            AiToolsRestoreInitializer(),
            SecretFilePermHardeningInitializer(),
            VisibleExportRootRestoreInitializer(),
            BundledAdbWarmupInitializer(),
        ),
    )
    // App 生命周期参与者注册（startAll 在 startInit 后的同一 LaunchedEffect 内 await）
    AppHost.register(LarkBridgeManager)
    // 语音转写端口接线（feature 间不直连——契约在 :core，宿主接线）：
    // 把 feature-voice 转写内核适配成中立端口注入 VoiceTranscriptionHub，
    // feature-larkbridge 桥接装配时经槽取用；接线点在 main 装配根 ⇒ 恒先于桥接装配。
    VoiceTranscriptionHub.wire(VoiceTranscriptionHub.Port { audio -> transcribeVoiceMessage(audio) })
    AppHost.register(com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsRuntime)
    // logcat 抓取引擎的退出收口（停止在跑抓取——StreamingShell 升级收割链，AppHost 退出序内完成）
    AppHost.register(com.jiaoay.easy_dev_toolbox.page.logcat.LogcatRuntime)
    // logcat「标记复现点」全局热键（Ctrl+M）：复现瞬间抢时间动作的键盘通路。
    // 仅抓取中消费（Running 门控在 handler——未抓取返回 false 落空不抢键）；
    // 转发引擎 markAnchor，回执 toast 与面板钮同句（标记无锚行的拒绝回执同款透出）。
    // 注册在 main 装配根：热键在 Window 级派发、拿不到 ViewModel，App 级单例引擎直取
    HotkeyHub.markLogcatAnchor = {
        val engine = com.jiaoay.easy_dev_toolbox.page.logcat.LogcatRuntime.engine
        if (engine.status.value !is com.jiaoay.easy_dev_toolbox.page.logcat.LogcatCaptureStatus.Running) {
            false
        } else {
            when (val marked = engine.markAnchor()) {
                null -> com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    "暂无可标记的带时间戳行（清屏后等新日志到达再标记）",
                )
                else -> com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals.show(
                    "已标记 ${marked.clockText}，停止后点「查看」直达标记处",
                )
            }
            true
        }
    }
    // 投屏引擎的退出收口（停止在跑投屏——解锁原生阻塞 + 收割 server 长跑流，AppHost 退出序内完成）
    AppHost.register(com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastRuntime)
    // P5 · MCP 外放服务参与者（start 只记 scope；起不起 server 由 mcp_server_enabled 配置 +
    // 页面开关决定——恒不自动起；shutdown 停 server + 取消在途 run，不残留监听）
    AppHost.register(com.jiaoay.easy_dev_toolbox.page.mcp.McpServerManager)
    // 内置 adb server 的退出收口：注册在投屏/抓取之后（它们收尾要用 server——隧道清理、
    // 显示触点恢复；server 先死会让那些收尾以失败告终）。只杀本应用专用端口的 server，
    // 用户默认端口上的既有 server 不受影响。
    AppHost.register(com.jiaoay.easy_dev_toolbox.core.device.AdbServerShutdownParticipant())
    application {
        // 窗口几何记忆：启动时同步读回上次几何（单 key 读取，桌面启动可接受），
        // rememberWindowState 参数只在首组合求值，异步回填不生效，故须在创建前就绪；
        // runCatching 兜底读链异常（CorruptionException 已由 LocalDataManager 的
        // ReplaceFileCorruptionHandler 收口——此处防的是几何值解析失败等非损坏类异常）——
        // 回退默认居中，保持「读失败仅记日志」的降级语义
        val savedGeometry = runBlocking { runCatching { readSavedWindowGeometry() }.getOrNull() }

        // 启动视觉态预载（防「启动亮色闪图」）：主题/深浅/材质开关/恢复页
        // 首帧前就绪（runBlocking 同步读 = 窗口几何同款纪律）；读失败回退 null = 全默认
        // （异步恢复块仍是权威重读，幂等）
        val uiPreload = runBlocking { runCatching { readUiPreloadState() }.getOrNull() }
        // 埋点：预载完（含窗口几何 + 视觉态两次 runBlocking DataStore 读）
        com.jiaoay.easy_dev_toolbox.core.log.JLog.i("StartupPerf") {
            "preload: 预载完成（JVM uptime=${java.lang.management.ManagementFactory.getRuntimeMXBean().uptime}ms）"
        }

        val windowState = rememberWindowState(
            size = savedGeometry?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(1280.dp, 860.dp),
            position = savedGeometry?.let { WindowPosition(it.x.dp, it.y.dp) }
                ?: WindowPosition.Aligned(Alignment.Center),
        )

        // 命令面板开关（Window 级 preview 恒可达）：Ctrl+K 处理在 Window 级 preview（恒可达），
        // 状态须与之同级；App/PageHome 经回调消费。Ctrl+Shift+P 预填 `>` 命令模式。
        var paletteVisible by remember { mutableStateOf(false) }
        var paletteCommandMode by remember { mutableStateOf(false) }

        // 热键单点分发表：Window 级 handler 只调 HotkeyHub.dispatch，
        // 键匹配与动作桥全部收口 host/HotkeyHub.kt（热键表唯一真源）
        HotkeyHub.openCommandPalette = {
            // toggle 语义：面板开着 Ctrl+K = 关闭
            paletteVisible = !paletteVisible
            paletteCommandMode = false
            true
        }
        HotkeyHub.openCommandPaletteCommandMode = {
            paletteVisible = true
            paletteCommandMode = true
            true
        }
        // 工具页产物「送往下一步」衔接接线（管线衔接契约的宿主侧）：投递即打开
        // 命令面板（palette 参数直填态消费 seed——CommandPalette 打开瞬间 consumeSeed）。
        // wire 在本组合点而非 AppViewModel：paletteVisible/paletteCommandMode 是本组
        // 合态，AppViewModel 不持有（DeepAnalyzeHook 直连 setCurrentPage 的对等面在此不可得）。
        com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff.wire {
            paletteVisible = true
            paletteCommandMode = true
        }
        HotkeyHub.closeOverlays = {
            // Esc 覆盖层栈（LIFO）：快捷键参考卡 → 命令面板（后进先关）
            when {
                com.jiaoay.easy_dev_toolbox.host.HotkeyHelp.visible.value -> {
                    com.jiaoay.easy_dev_toolbox.host.HotkeyHelp.dismiss()
                    true
                }
                paletteVisible -> {
                    paletteVisible = false
                    // 模式标记随面板关闭复位（下次 Ctrl+K 从混排开始）
                    paletteCommandMode = false
                    true
                }
                else -> false
            }
        }
        // 快捷键参考卡（HotkeyHelpCard 渲染在 App() 组合内）
        HotkeyHub.openHotkeyHelp = {
            com.jiaoay.easy_dev_toolbox.host.HotkeyHelp.open()
            true
        }

        // dock-first 布局：菜单栏撤除——左侧 dock
        // 窄轨本意就是替代菜单栏，不再引入第二导航层

        // 托盘（Linux SNI / Windows / macOS）：GNOME 无 SNI 扩展时 isTraySupported
        // 返回 false——降级跳过不阻断（dock 导航 + 全局热键已覆盖导航面），蓝图红线。
        // 语义克制（从需求出发）：托盘只做「常驻标识 + 退出」；「关闭到托盘/
        // 最小化隐藏」涉及关闭按钮语义变更（产品决策），等真实需求再上。
        if (isTraySupported) {
            val trayState = rememberTrayState()
            Tray(
                icon = painterResource(Res.drawable.app),
                state = trayState,
                tooltip = "Toolbelt",
            ) {
                // 托盘菜单（dock-first 裁定后收敛）：常驻标识 + 退出——不做导航项。
                // 禁传 mnemonic：Linux AWT Menu 无助记符概念，Compose（CMP 1.12 AwtMenuScope）
                // 对非空 mnemonic 抛 UnsupportedOperationException——托盘菜单组合期即崩
                Menu("Toolbelt") {
                    Item("退出", onClick = { exitApplication() })
                }
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Toolbelt", // 动态页面名由 App() 内经 AWT window.title 覆写（见 App.kt）
            icon = painterResource(Res.drawable.app),
            // edge-to-edge：undecorated（CMP 1.12 经 windowDecorationFromFlag 自动接
            // WindowDecoration.Undecorated——内建 8dp 边缘缩放）+ transparent（仅 undecorated
            // 合法）= 透明圆角窗；圆角裁剪与定缘在 AppTheme(windowShape) 根层。
            // Window 级 onPreviewKeyEvent（自 PageHome 上移）：Compose 焦点派发
            // 链从聚焦节点沿祖先遍历，无焦点（启动首屏/欢迎页）时 Row 级 handler 不在派发
            // 路径上——Window 级 preview 在 scene.sendKeyEvent 之前执行、不依赖 Compose 焦点
            undecorated = true,
            transparent = true,
            // 内建 resizer A/B 实测（应用驱动逐事件 setLocation+setSize 两次
            // 异步调用）真机「剧烈抖动」——resizable=false 关闭之，缩放由
            // host/UndecoratedResizeOverlay 自绘边条接管（冻结快照算法，单次原子 setBounds）
            resizable = false,
            onPreviewKeyEvent = { event -> HotkeyHub.dispatch(event) },
        ) {
            // 窗口最小尺寸，防止 Shell 页输出面板/历史栏挤压破版
            window.minimumSize = java.awt.Dimension(960, 640)

            // 启动初始化只执行一次（LaunchedEffect 重组安全），异步执行不阻塞首帧组合；
            // 初始化完成前 App() 依 initStateFlow 呈现 Loading 态
            LaunchedEffect(Unit) {
                JInitializerManager.startInit()
                JLog.d("main") { "after startInit" }
                // 参与者启动（1Hz Koog 回路等 App 级后台任务上挂 AppHost.scope；异常隔离）
                AppHost.startAll()
            }

            // 窗面空帧自愈看门狗（主窗为无边框透明窗=逐像素半透明面，窗面被窗口
            // 管理事件清空且无失效时整窗透桌面——透底症状的本体防线，契约见件 KDoc）
            com.jiaoay.easy_dev_toolbox.widget.SurfacePaintKeepAlive()

            // WindowScope 下发（WindowDraggableArea 需要接收者；AppTitleBar 消费）
            CompositionLocalProvider(LocalAppWindowScope provides this) {
                // 埋点：首内容帧就绪（App 首次组合 = 窗口可交互的强信号）
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    com.jiaoay.easy_dev_toolbox.core.log.JLog.i("StartupPerf") {
                        "first-frame: App 首帧组合（JVM uptime=" +
                            "${java.lang.management.ManagementFactory.getRuntimeMXBean().uptime}ms）"
                    }
                }
                App(
                    hostWindow = window,
                    uiPreload = uiPreload,
                    windowState = windowState,
                    onClose = ::exitApplication,
                    paletteVisible = paletteVisible,
                    onDismissPalette = {
                        paletteVisible = false
                        paletteCommandMode = false
                    },
                    paletteCommandMode = paletteCommandMode,
                )

                // 投屏独立视口窗（恒组合挂载——存在性由 ScreencastDetachedWindowController
                // .visible 唯一驱动，防重窗结构性收口；全仓 Window 组合点锚=2 由 arch
                // 守护 WindowCompositionAnchorGuardTest 钉死。组合进主窗内容树：主窗首组合
                // 期尚无聚焦窗（U29 建窗时机不变），且同 ViewModelStore 可达 AppViewModel
                // ——主题 CompositionLocal 不跨 Window 传播，独立窗主题经参注入实时值，
                // 随设置页/系统明暗翻转。icon 经参传入：composeApp 资源对 feature 域不可达）
                val hostAppViewModel = com.jiaoay.easy_dev_toolbox.core.AppViewModel.getViewModel(uiPreload)
                val hostScheme by hostAppViewModel.colorScheme.collectAsState()
                val hostThemeMode by hostAppViewModel.themeMode.collectAsState()
                com.jiaoay.easy_dev_toolbox.page.screencast.ScreencastDetachedWindowHost(
                    icon = painterResource(Res.drawable.app),
                    // 几何记忆经参进 Host（feature 不感知持久化与屏外防呆）：读 lambda 在
                    // 开窗当帧同步执行（rememberWindowState 参数只在首组合求值，异步回填
                    // 不生效——主窗启动读回同款纪律）；写回为同步外呼，落盘阻塞语义在此
                    // 承载（关窗离散动作 + onDispose 兜底双路，见 Host 内容挂载点）
                    initialGeometry = {
                        runBlocking { runCatching { readSavedScreencastDetachedGeometry() }.getOrNull() }
                            ?.let {
                                ScreencastDetachedInitialGeometry(
                                    x = it.x,
                                    y = it.y,
                                    width = it.width,
                                    height = it.height,
                                )
                            }
                    },
                    onPersistGeometry = { x, y, width, height ->
                        runBlocking {
                            runCatching { EasyDataManager.setScreencastDetachedWindowGeometry(x, y, width, height) }
                        }
                    },
                    scheme = hostScheme,
                    themeMode = hostThemeMode,
                )
            }
        }

        // 应用退出清理：窗口关闭、application 作用域结束时执行
        DisposableEffect(Unit) {
            onDispose {
                // 退出序收口 AppHost（顺序即契约：participants.shutdown（桥接先停）→ scope.cancel
                //    → 宿主收尾 executor 缓存 + 日志后端最后关——与旧三连逐字同序）。
                AppHost.shutdown(
                    bridgeExit = { LarkBridgeManager.onAppExit() },
                    hostTeardown = {
                        LlmClientFactory.invalidate() // AI executor 缓存收口（卫生性；JVM 退出本会兜底）
                        LogbackHelper.onDestroy()
                    },
                )
            }
        }
    }
}

// ── 导航桥已拆除 ──────────────────────────────────────────────
// Window 级热键不再在 main.kt 匹配（收口 HotkeyHub.dispatch）；切页动作在
// App() 组合内经 rememberHotkeyActions 注册、直接调 appViewModel.setCurrentPage，
// application 作用域与组合之间不再有导航桥。App() 的 onNavigate 参数已删除。

/**
 * 读回持久化窗口几何，无记录或几何非法/屏外时返回 null（回退默认居中）。
 * 校验语义单源在 [validatedWindowGeometryOrNull]（宿主域，与投屏独立窗读链共用）；
 * runCatching 兜底读链异常（CorruptionException 已由 LocalDataManager 的
 * ReplaceFileCorruptionHandler 收口——此处防的是几何值解析失败等非损坏类异常）。
 */
private suspend fun readSavedWindowGeometry(): SavedWindowGeometry? =
    validatedWindowGeometryOrNull(
        x = EasyDataManager.getWindowX(),
        y = EasyDataManager.getWindowY(),
        width = EasyDataManager.getWindowWidth(),
        height = EasyDataManager.getWindowHeight(),
    )

/**
 * 读回投屏独立窗上次几何（校验同主窗读链单源）：null = 无记录/非法/屏外 →
 * 计算位兜底。读 lambda 在开窗首组合内同步执行（调用点 runBlocking）。
 */
private suspend fun readSavedScreencastDetachedGeometry(): SavedWindowGeometry? =
    validatedWindowGeometryOrNull(
        x = EasyDataManager.getScreencastDetachedWindowX(),
        y = EasyDataManager.getScreencastDetachedWindowY(),
        width = EasyDataManager.getScreencastDetachedWindowWidth(),
        height = EasyDataManager.getScreencastDetachedWindowHeight(),
    )

/** 启动视觉态预载读取（值全为已解析终值；供 AppViewModel 首帧前种子化，防亮色闪图） */
private suspend fun readUiPreloadState(): UiPreloadState {
    val customSeed = EasyDataManager.getCustomSeedColor().takeIf { it != -1L }?.let { Color(it.toInt()) }
    return UiPreloadState(
        colorScheme = AppThemeSpec.fromId(EasyDataManager.getColorSchemeName(), customSeed),
        themeMode = loadEnum(EasyDataManager.getThemeModeName(), ThemeMode.System),
        auroraEnabled = EasyDataManager.getAuroraEnabled(),
        restoredPage = AppViewModel.restoredPageTarget(
            loadEnum(EasyDataManager.getLastPageName(), ToolPage.LogWorkbench),
        ),
    )
}

/**
 * 飞书语音消息的宿主转写适配器（宿主聚合点装配——feature 间不直连，经
 * VoiceTranscriptionHub 中立槽供 feature-larkbridge 桥接装配取用）。
 * 飞书语音资源是 opus 封装：ffmpeg 解码为 16kHz mono wav → SenseVoice 推理；
 * 解码与推理产物均一次性消费，收尾即删。
 *
 * 三态映射（槽契约见 VoiceTranscriptionHub.Port）：
 * - 模型未下载 / ffmpeg 缺席 ⇒ Unavailable（引导文案，非失败）；
 * - 解码或推理失败 ⇒ Failed（人话原因）；
 * - 成功 ⇒ Success（转写稿 + 截断标注如有——取证场景静默截断零容忍，语音转写页同款纪律）。
 * 模型就绪前置在解码之前（模型缺席时省掉整条 ffmpeg 解码开销）；
 * 取消信号原样上抛（AudioTranscoder 与推理调用链均不吞 CancellationException）。
 */
private suspend fun transcribeVoiceMessage(audio: java.io.File): VoiceTranscriptionHub.Result {
    if (!ModelStore.isReady()) {
        return VoiceTranscriptionHub.Result.Unavailable(
            "语音转写模型还没下载～ 请先在桌面应用「智能体中心 → 语音转写」页下载转写模型，之后发语音我就能听懂了",
        )
    }
    val decoded = AudioTranscoder.decodeToPcm16kMono(audio)
    val wav = decoded.getOrElse { failure ->
        return if (!AudioTranscoder.isFfmpegAvailable()) {
            VoiceTranscriptionHub.Result.Unavailable(
                "系统未安装 ffmpeg——语音转写需要它解码音频格式，安装 ffmpeg 后重试～",
            )
        } else {
            VoiceTranscriptionHub.Result.Failed("音频解码失败（${failure.message ?: "未知原因"}）")
        }
    }
    return try {
        SherpaTranscriber.transcribe(wav).fold(
            onSuccess = { t ->
                val text = buildString {
                    append(t.text)
                    t.truncationNote?.let { append("\n（语音转写注：").append(it).append("）") }
                }
                VoiceTranscriptionHub.Result.Success(text)
            },
            onFailure = { e ->
                if (e is TranscriberNotReadyException) {
                    VoiceTranscriptionHub.Result.Unavailable(e.message ?: "语音转写模型未就绪")
                } else {
                    VoiceTranscriptionHub.Result.Failed("转写失败（${e.message ?: "未知原因"}）")
                }
            },
        )
    } finally {
        runCatching { wav.delete() }
    }
}
