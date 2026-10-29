package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.semanticsNodes
import com.jiaoay.easy_dev_toolbox.core.theme.LightEasyToolboxColors
import kotlin.math.abs
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import com.jiaoay.toolbelt.device.DeviceState

/**
 * 投屏面板场景渲染像素带守护（:feature-logcat 场景守护同配方）：面板是纯启动器单行卡
 * （投屏展示与操作全部在独立窗，窗关即停投）——主窗不渲染帧（品红帧锚零命中即主窗
 * 帧管线整体裁撤的回归背书）也不渲染状态展示与画质控制（fps 文案/「画质」入口零命中
 * ——纯启动器越界面即红）；Streaming 态行尾恒有「聚焦窗口」入口（最小化窗抢救面）；
 * Idle 行内「开始投屏」入口；Connecting busy 禁点如实回显；失败终态与开窗等待超时的
 * 回执细行恒显（不依赖任何展开动作）。
 *
 * 状态注入：[ScreencastPanel] 经 viewModel(key = "screencast") 自建 VM（缺省引擎 =
 * App 级单例，测试不可驱动），测试侧把经全 fake harness 驱动到 Streaming + 帧已发布的
 * VM 预置进独立 ViewModelStore 同 key 槽位，组合期缓存命中即返回该 VM（不改生产面）。
 *
 * 渲染底座单源消费（arch 包 SceneGuardSupport，宿主 :test-support 模块）：渲染期
 * 引擎/解码协程持续写状态流，场景经 sceneDispatcher 单线程钉扎（底座 dispatcher
 * 参数——组合/重组/measure/draw 全串行，消除 SnapshotStateObserver 跨线程误报）。
 */
class ScreencastPanelSceneGuardTest {

    /** 注入帧的纯色锚（品红 0xFF00FF——主题任何槽位不含此色，帧区检测零歧义） */
    private val frameColor = intArrayOf(255, 0, 255)

    /** 场景专用单线程（组合/重组/measure/draw 全串行，消除 SnapshotStateObserver 跨线程误报） */
    private val sceneExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screencast-panel-scene").apply { isDaemon = true }
    }
    private val sceneDispatcher = sceneExecutor.asCoroutineDispatcher()

    private val handles = mutableListOf<SceneGuardHandle>()
    private val createdVms = mutableListOf<ViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        createdVms.forEach { it.viewModelScope.cancel() }
        handles.forEach { it.close() }
        // 控制器进程级状态复位（超时用例置位/受理用例翻真不跨用例残留——先清窗关
        // 即停投外呼再 recall，复位路径不驱动 fake 外呼）
        ScreencastDetachedWindowController.onSessionEnd = null
        ScreencastDetachedWindowController.recall()
        ScreencastDetachedWindowController.connectionTimedOut = false
        sceneExecutor.shutdownNow()
        Dispatchers.resetMain()
    }

    // ── 本面板专属像素锚（品红帧计数——非底座通用配方） ─────────────────────

    /** 品红帧像素计数（±8/通道——Image 双线性缩放边缘仍命中核心区） */
    private fun countFramePixels(pixels: Array<IntArray>): Int {
        var cnt = 0
        for (row in pixels) {
            for (p in row) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (abs(r - frameColor[0]) <= 8 && abs(g - frameColor[1]) <= 8 && abs(b - frameColor[2]) <= 8) cnt++
            }
        }
        return cnt
    }

    // ── VM 状态注入底座（:feature-logcat 场景守护同配方） ────────────────────

    private fun seededOwner(vm: ScreencastViewModel, key: String): ViewModelStoreOwner {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = vm as T
        }
        ViewModelProvider.create(owner, factory, CreationExtras.Empty).get(key, ScreencastViewModel::class)
        return owner
    }

    /**
     * 面板整景句柄：预驱动 VM 经同 key 槽位注入，ScreencastPanel 原样组合
     * （onOpenDetached 桥接非空——开窗/聚焦入口的真实形态）。
     * 句柄经 [handles] 统一收口（tearDown 关闭）——渲染后引擎/解码协程仍活跃，
     * 场景关闭时序须由本测试掌控，不走一次性渲染。
     */
    private fun panelHandle(vm: ScreencastViewModel, devices: FakeDeviceSource): SceneGuardHandle {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H, dispatcher = sceneDispatcher).also { handles += it }
        handle.setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides seededOwner(vm, key = "screencast"),
            ) {
                ScreencastPanel(
                    devices = devices,
                    onOpenDetached = {},
                    connectionTimedOut = ScreencastDetachedWindowController.connectionTimedOut,
                )
            }
        }
        return handle
    }

    private fun nodeText(node: SemanticsNode): String = with(node.config) {
        getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    }

    private fun nodesContaining(handle: SceneGuardHandle, snippet: String): List<SemanticsNode> =
        handle.semanticsNodes { node -> nodeText(node).contains(snippet) }

    private fun assertRowInRenderFace(pixels: Array<IntArray>, bandsLabel: String) {
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(bands.isNotEmpty(), "$bandsLabel：入口行必须进入渲染面（标题/主钮回显）")
    }

    // ── 用例 ──────────────────────────────────────────────────────────────

    @Test
    fun `streaming row keeps window entry reachable without frame rendering`() {
        val harness = ScreencastHarness()
        harness.startAndHandshake()
        runBlocking {
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Streaming } }
            harness.decoder.offer(solidFrame(size = 64, color = 0xFF00FF))
            withTimeout(5_000) { harness.engine.frame.first { it != null } }
        }
        val devices = FakeDeviceSource(state = DeviceState.Single("S1", "M1"))
        val vm = ScreencastViewModel(devices, harness.engine, adbPathReader = { "" }).also { createdVms += it }

        val handle = panelHandle(vm, devices)
        val pixels = handle.renderPixels()

        assertRowInRenderFace(pixels, "Streaming 态")
        // 主窗帧管线整体裁撤：帧不得在主窗面板渲染（品红锚零命中）
        assertTrue(
            countFramePixels(pixels) == 0,
            "主窗面板不应渲染帧内容（帧管线零画面——主窗帧区回潮即红）",
        )
        // Streaming 态行尾恒有聚焦窗口入口（窗关即停投——窗是唯一展示面，行尾入口
        // 只管把窗带回前台，最小化窗抢救面缺位即红）
        assertTrue(
            nodesContaining(handle, "聚焦窗口").isNotEmpty(),
            "Streaming 态行尾须渲染「聚焦窗口」入口（最小化窗抢救面缺位即红）",
        )
        // 纯启动器：状态展示与画质控制不回潮行内（消费面在独立窗）
        assertTrue(
            nodesContaining(handle, "画质").isEmpty(),
            "行内不得渲染画质档位入口（档位弹层在独立窗顶栏——越界面回潮即红）",
        )
        assertTrue(
            nodesContaining(handle, "fps").isEmpty(),
            "行内不得渲染帧率状态展示（状态条在独立窗——行内展示回潮即红）",
        )
        harness.close()
    }

    @Test
    fun `idle row renders start entry without composition error`() {
        val harness = ScreencastHarness()
        val devices = FakeDeviceSource(state = DeviceState.None)
        val vm = ScreencastViewModel(devices, harness.engine, adbPathReader = { "" }).also { createdVms += it }

        val handle = panelHandle(vm, devices)
        val pixels = handle.renderPixels()

        assertRowInRenderFace(pixels, "Idle 态")
        assertTrue(countFramePixels(pixels) == 0, "Idle 态不应渲染任何帧内容")
        assertTrue(
            nodesContaining(handle, "开始投屏").isNotEmpty(),
            "Idle 态行内须渲染「开始投屏」入口",
        )
        harness.close()
    }

    @Test
    fun `connecting row renders busy state without actionable entry`() {
        // connect 预算放大到 60s：Connecting 态须撑满整个渲染期（默认 5s 会被渲染时长吃掉）
        val harness = ScreencastHarness(connectTimeoutMs = 60_000, acceptSoTimeoutMs = 65_000)
        // 不做 server 角色握手——会话停在 Connecting 占住渲染窗口
        harness.engine.start("", "TEST-SERIAL")
        runBlocking {
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Connecting } }
        }
        val devices = FakeDeviceSource(state = DeviceState.Single("S1", "M1"))
        val vm = ScreencastViewModel(devices, harness.engine, adbPathReader = { "" }).also { createdVms += it }

        val handle = panelHandle(vm, devices)
        val pixels = handle.renderPixels()

        assertRowInRenderFace(pixels, "Connecting 态")
        assertTrue(countFramePixels(pixels) == 0, "Connecting 态不应渲染任何帧内容")
        // 受理即开窗后连接段窗已在（窗内占位承接连接反馈）——行尾入口同为聚焦面；
        // 连接中 busy 回显退位窗内，行内不得再渲染开始入口（会话已在跑）
        assertTrue(
            nodesContaining(handle, "聚焦窗口").isNotEmpty(),
            "Connecting 态行尾须渲染「聚焦窗口」入口（受理即开窗后行尾即聚焦面）",
        )
        assertTrue(
            nodesContaining(handle, "开始投屏").isEmpty(),
            "Connecting 态行内不得渲染「开始投屏」（会话已在跑——重入入口即误导）",
        )
        harness.close()
    }

    @Test
    fun `failed terminal renders persistent failure receipt and retry entry`() {
        // 短 connect 预算：会话快速落失败终态（连接超时显式失败）
        val harness = ScreencastHarness(connectTimeoutMs = 500, acceptSoTimeoutMs = 1_000)
        harness.engine.start("", "TEST-SERIAL")
        runBlocking {
            withTimeout(5_000) {
                harness.engine.status.first { it is ScreencastStatus.Stopped && it.failed }
            }
        }
        val devices = FakeDeviceSource(state = DeviceState.Single("S1", "M1"))
        val vm = ScreencastViewModel(devices, harness.engine, adbPathReader = { "" }).also { createdVms += it }

        val handle = panelHandle(vm, devices)
        val pixels = handle.renderPixels()

        // 失败回执细行恒显（不依赖展开）：Error 槽 errorContainerFixed 像素锚
        val bannerPixels = countColorPixels(
            pixels,
            rgbOf(LightEasyToolboxColors.errorContainerFixed.toArgb()),
        )
        assertTrue(
            bannerPixels >= 2_000,
            "失败终态须恒显失败细行（errorContainerFixed 色锚 $bannerPixels < 2000）" +
                "——banner 不渲染即红（失败回执不得依赖任何展开动作）",
        )
        // 细行原因 + 主钮「重试」指路（四要素「现在能做什么」）
        assertTrue(
            nodesContaining(handle, "投屏失败").isNotEmpty(),
            "失败细行须承载失败原因（不静默）",
        )
        assertTrue(
            nodesContaining(handle, "重试").isNotEmpty(),
            "失败终态行内主钮须变「重试」（指路：现在能做什么）",
        )
        harness.close()
    }

    @Test
    fun `window open wait timeout renders explicit receipt with retry entry`() {
        // 开窗等窗预算收短（注入 200ms）：会话停在 60s 连接段，等窗预算走完落超时回执
        val harness = ScreencastHarness(connectTimeoutMs = 60_000, acceptSoTimeoutMs = 65_000)
        val devices = FakeDeviceSource(state = DeviceState.Single("S1", "M1"))
        val vm = ScreencastViewModel(
            devices,
            harness.engine,
            adbPathReader = { "" },
            detachedWindowOpenTimeoutMs = 200,
        ).also { createdVms += it }
        try {
            vm.startCast()
            runBlocking {
                withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Connecting } }
                withTimeout(5_000) {
                    while (!ScreencastDetachedWindowController.connectionTimedOut) delay(50)
                }
            }

            val handle = panelHandle(vm, devices)
            val pixels = handle.renderPixels()

            assertRowInRenderFace(pixels, "开窗超时态")
            val bannerPixels = countColorPixels(
                pixels,
                rgbOf(LightEasyToolboxColors.errorContainerFixed.toArgb()),
            )
            assertTrue(
                bannerPixels >= 2_000,
                "等窗超时须恒显超时细行（errorContainerFixed 色锚 $bannerPixels < 2000）" +
                    "——超时静默零回执即红",
            )
            assertTrue(
                nodesContaining(handle, "连接超时").isNotEmpty(),
                "超时细行须明示连接超时（不静默等终态）",
            )
            assertTrue(
                nodesContaining(handle, "重试").isNotEmpty(),
                "超时细行须带「重试」入口（指路：现在能做什么）",
            )
        } finally {
            harness.close()
        }
    }

    /** ARGB 整型 → RGB 行数组（像素锚与 countColorPixels 同形态） */
    private fun rgbOf(argb: Int): IntArray =
        intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    /** 目标色像素计数（±8/通道——Compose/Skia 渲染管线色值微偏仍命中核心区） */
    private fun countColorPixels(pixels: Array<IntArray>, rgb: IntArray): Int {
        var cnt = 0
        for (row in pixels) {
            for (p in row) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (abs(r - rgb[0]) <= 8 && abs(g - rgb[1]) <= 8 && abs(b - rgb[2]) <= 8) cnt++
            }
        }
        return cnt
    }

    private companion object {
        const val SCENE_W = 800
        const val SCENE_H = 1_400
    }
}
