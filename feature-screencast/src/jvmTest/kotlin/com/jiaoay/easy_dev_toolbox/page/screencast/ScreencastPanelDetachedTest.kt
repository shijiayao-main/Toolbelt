package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.runtime.CompositionLocalProvider
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
import kotlin.math.abs
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import com.jiaoay.toolbelt.device.DeviceState

/**
 * 面板 Streaming 态行尾入口守护（参数注入面——面板不直读控制器单例）：窗关即停投
 * 契约下 Streaming 期窗恒在组合中，行尾主钮恒为「聚焦窗口」（onOpenDetached =
 * openOrFocus——窗口管理入口，最小化窗恢复聚焦的唯一通道），「打开窗口」文案
 * 已随窗关即停投消失（回潮即红）；帧不进入主窗渲染（品红帧锚零命中——主窗帧
 * 管线整体裁撤的回归背书）。
 */
class ScreencastPanelDetachedTest {

    /** 注入帧的纯色锚（品红 0xFF00FF——主题任何槽位不含此色，帧区检测零歧义） */
    private val frameColor = intArrayOf(255, 0, 255)

    /** 场景专用单线程（渲染期引擎/解码协程持续写状态流——底座钉扎配方） */
    private val sceneExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screencast-panel-detached").apply { isDaemon = true }
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
        sceneExecutor.shutdownNow()
        Dispatchers.resetMain()
    }

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

    /** Streaming 态 VM + 已发布帧（两形态用例共用的最小前置；devices 与 VM 同源） */
    private fun streamingVmWithFrame(): Pair<ScreencastViewModel, FakeDeviceSource> {
        val harness = ScreencastHarness()
        harness.startAndHandshake()
        runBlocking {
            withTimeout(5_000) { harness.engine.status.first { it is ScreencastStatus.Streaming } }
            harness.decoder.offer(solidFrame(size = 64, color = 0xFF00FF))
            withTimeout(5_000) { harness.engine.frame.first { it != null } }
        }
        val devices = FakeDeviceSource(state = DeviceState.Single("S1", "M1"))
        val vm = ScreencastViewModel(devices, harness.engine, adbPathReader = { "" }).also { createdVms += it }
        return vm to devices
    }

    private fun nodeText(node: SemanticsNode): String = with(node.config) {
        getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
    }

    private fun nodesContaining(handle: SceneGuardHandle, snippet: String) =
        handle.semanticsNodes { node -> nodeText(node).contains(snippet) }

    @Test
    fun `streaming row entry always focuses window and keeps frame out of main window`() {
        val (vm, devices) = streamingVmWithFrame()

        val handle = SceneGuardHandle(SCENE_W, SCENE_H, dispatcher = sceneDispatcher).also { handles += it }
        handle.setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides seededOwner(vm, key = "screencast"),
            ) {
                ScreencastPanel(
                    devices = devices,
                    onOpenDetached = {},
                )
            }
        }
        val pixels = handle.renderPixels()

        val bands = contentRows(pixels, SCENE_W)
        assertTrue(bands.isNotEmpty(), "Streaming 态入口行必须进入渲染面")
        assertTrue(
            nodesContaining(handle, "聚焦窗口").isNotEmpty(),
            "Streaming 态行尾主钮须恒为「聚焦窗口」（窗口管理入口——窗关即停投后行尾只剩聚焦面）",
        )
        assertTrue(
            nodesContaining(handle, "打开窗口").isEmpty(),
            "「打开窗口」文案须随窗关即停投消失（窗关即停投下不存在投着屏而窗不在的常驻形态）",
        )
        assertEquals(0, countFramePixels(pixels), "帧不得在主窗面板渲染（主窗帧管线整体裁撤）")
    }

    private companion object {
        const val SCENE_W = 800
        const val SCENE_H = 1_400
    }
}
