package com.jiaoay.easy_dev_toolbox.page.logcat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.arch.SceneGuardHandle
import com.jiaoay.easy_dev_toolbox.arch.assertBandsSeparated
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.countColor
import com.jiaoay.easy_dev_toolbox.arch.probeSchemeColor
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.navigation.TaskPipelineHandoff
import com.jiaoay.toolbelt.device.ShellResult
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.math.abs
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.util.UUID
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * logcat 抓取面板场景渲染像素带守护：ImageComposeScene 零显示器配方扩到本面板
 * （composeApp 侧 arch 场景守护同配方；本面板 internal 于本模块，守护随组件落位）。
 *
 * 验证面（面板布局/展开动画回归网）：
 * - Running 态自动展开（[LogcatPanel] 内 LaunchedEffect(running) → expanded）：
 *   面板内容（过滤行/级别 chips/停止按钮/控制台）必须进入渲染面——展开链断裂
 *   或 AnimatedVisibility enter 动画结算失败即红；
 * - 深底控制台带（consoleBg 恒 #1E1E1E——EasyToolboxColors 亮档定值）与
 *   捕获行文字（consoleText #B0BEC5）进入渲染面——控制台缺失/行渲染断裂即红；
 * - 崩溃堆栈折叠：≥5 连续 at 帧行折叠为「首帧 + 展开提示」两行——折叠态控制台
 *   带高较同形非帧行对照组显著收窄（折叠失效、逐帧行平铺即红）；
 * - 崩溃行高亮：短帧行串（< 折叠阈值）不折叠但逐行着崩溃色（logError 恒
 *   #EF5350——consoleBg 深底上的恒深值契约）进入渲染面，对照组非帧行零崩溃
 *   着色（着色判定泄漏到普通行即红）；
 * - 查询错误回执：待操作数查询置错误态，错误着色 supportingText 进入渲染面
 *   （与有效查询同 VM 同尺寸渲染做差分——错误色增量即回执在场）；
 * - 内容带互斥：各带间背景间隙 > 3px（叠放/粘连回归）。
 *
 * 状态注入：[LogcatPanel] 经 viewModel(key = "logcat-capture") 自建 VM（缺省引擎 =
 * App 级单例，测试不可驱动），测试侧把经 FakeRunner 驱动到 Running + 滚动窗非空的
 * VM（引擎注入接缝同 [LogcatViewModelTest]）预置进独立 ViewModelStore 同 key 槽位，
 * 组合期缓存命中即返回该 VM（不改生产面）。
 *
 * 渲染底座单源消费（arch 包 SceneGuardSupport，宿主 :test-support 模块）：本用例
 * 渲染期引擎收集协程持续写状态流，场景经 sceneDispatcher 单线程钉扎（底座 dispatcher
 * 参数——组合/重组/measure/draw 全串行，消除 SnapshotStateObserver 跨线程误报）。
 *
 * 提示态（无设备/检测失败/多设备未选定）与 Stopped 产物回显态均在展开区内、
 * 由 VM 级测试钉文案与动作，场景层不为它们单设渲染用例（像素带边际价值低）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogcatPanelSceneGuardTest {

    /** 控制台深底/文字色（EasyToolboxColors 亮档定值——consoleBg 恒 #1E1E1E 契约） */
    private val consoleBg = intArrayOf(30, 30, 30)
    private val consoleText = intArrayOf(176, 190, 197)

    /** 崩溃行着色（EasyToolboxColors 亮档定值——logError 恒 #EF5350，consoleBg 深底上的恒深值契约） */
    private val logError = intArrayOf(239, 83, 80)

    private val homeIsolation = UserHomeIsolation("edt-logcat-scene")
    private val dispatcher = StandardTestDispatcher()
    private val handles = mutableListOf<SceneGuardHandle>()
    private val createdVms = mutableListOf<ViewModel>()
    private val createdScopes = mutableListOf<CoroutineScope>()
    private lateinit var originalStore: LocalDataManager

    /** 场景专用单线程（组合/重组/measure/draw 全串行——见 SceneGuardSupport dispatcher 钉扎契约） */
    private val sceneExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "logcat-panel-scene").apply { isDaemon = true }
    }
    private val sceneDispatcher = sceneExecutor.asCoroutineDispatcher()

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
        originalStore = LogcatConfigStore.store
        LogcatConfigStore.store = LocalDataManager(
            dataStoreName = "test_logcat_scene_${UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
        )
    }

    @AfterTest
    fun tearDown() {
        createdVms.forEach { it.viewModelScope.cancel() }
        createdScopes.forEach { it.cancel() }
        handles.forEach { it.close() }
        sceneExecutor.shutdownNow()
        LogcatConfigStore.store = originalStore
        TaskPipelineHandoff.clearForTest()
        homeIsolation.restoreOnly()
        Dispatchers.resetMain()
    }

    // ── 本面板专属像素锚（consoleBg 深底带检测——非底座通用配方） ────────────

    /**
     * 控制台深底带检测：行内过半采样像素命中 consoleBg（±6/通道）的行聚类；
     * 返回最高的 ≥20px 连续带，无则 null。阈值取真过半（采样步进 3 → 366 样本，
     * cnt*6 > 行宽即 >50%）——捕获行文字占位后暗底仍约 2/3，纯文字页背景行暗底占比为 0。
     */
    private fun consoleBand(pixels: Array<IntArray>): IntRange? {
        val hits = mutableListOf<Int>()
        for (y in pixels.indices) {
            val row = pixels[y]
            var cnt = 0
            for (x in row.indices step 3) {
                val r = (row[x] shr 16) and 0xFF
                val g = (row[x] shr 8) and 0xFF
                val b = row[x] and 0xFF
                if (abs(r - consoleBg[0]) <= 6 && abs(g - consoleBg[1]) <= 6 && abs(b - consoleBg[2]) <= 6) cnt++
            }
            if (cnt * 6 > row.size) hits.add(y)
        }
        if (hits.isEmpty()) return null
        val groups = mutableListOf<IntRange>()
        var start = hits[0]
        var prev = hits[0]
        for (y in hits.drop(1)) {
            if (y - prev > 3) {
                groups.add(start..prev)
                start = y
            }
            prev = y
        }
        groups.add(start..prev)
        return groups.filter { it.last - it.first + 1 >= 20 }.maxByOrNull { it.last - it.first }
    }

    /** 带内近色像素计数（±12/通道——glyph 抗锯齿核心像素命中） */
    private fun countNear(pixels: Array<IntArray>, band: IntRange, target: IntArray): Int {
        var cnt = 0
        for (y in band) {
            for (p in pixels[y]) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (abs(r - target[0]) <= 12 && abs(g - target[1]) <= 12 && abs(b - target[2]) <= 12) cnt++
            }
        }
        return cnt
    }

    // ── VM 状态注入底座 ────────────────────────────────────────────────────

    /**
     * 预驱动 VM 注入独立 ViewModelStore：组合期 viewModel(key) 与本函数走同一
     * ViewModelProvider.get(key, KClass) 通道（同 key 即缓存命中），返回该 owner
     * 经 CompositionLocalProvider 提供给场景内容。store 不 clear（clear 会触发
     * onCleared 并关闭 viewModelScope），tearDown 统一 cancel 兜底。
     */
    private fun seededOwner(vm: LogcatViewModel, key: String): ViewModelStoreOwner {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = vm as T
        }
        ViewModelProvider.create(owner, factory, CreationExtras.Empty).get(key, LogcatViewModel::class)
        return owner
    }

    /**
     * 驱动一个 Running 态 VM（隔离 :core 设备域单源——空网关 + 空路径读，不 spawn 真实
     * adb）并等待滚动窗收满给定行。每次调用自建独立 monitor/engine/VM——同测试多场景
     * 互不串台（引擎单例Running 门禁拒绝二次 start）。
     */
    private fun runningVm(lines: List<String>): Pair<LogcatViewModel, DeviceMonitor> {
        val monitor = CoroutineScope(SupervisorJob() + Dispatchers.Default).let { scope ->
            createdScopes += scope
            DeviceMonitor(
                scope = scope,
                runner = { Result.success(ShellResult(0, "", "")) },
                endpointReader = { AdbEndpoint("") },
            )
        }
        val runner = SceneLogcatRunner()
        val engine = LogcatCaptureEngine(
            CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it },
            runner,
        )
        val vm = LogcatViewModel(monitor, engine, adbPathReader = { "" }).also { createdVms += it }
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        runner.blockAfterEmit = true
        runner.emitLines = lines
        vm.startCapture()
        // 主档任务推进到 IO 挂起点（隔离 monitor 门控关、轮询在注入 scope 上不走本调度器）
        dispatcher.scheduler.runCurrent()
        runBlocking {
            withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
            withTimeout(5_000) { runner.started.await() }
            withTimeout(5_000) { vm.window.first { it.lines.size >= lines.size } }
        }
        return vm to monitor
    }

    /**
     * 面板整景渲染：预驱动 VM 经同 key 槽位注入，LogcatPanel 原样组合。
     * 句柄经 [handles] 统一收口（tearDown 关闭）——渲染后引擎收集协程仍活跃，
     * 场景关闭时序须由本测试掌控，不走一次性渲染。
     */
    private fun renderPanel(vm: LogcatViewModel, devices: DeviceMonitor): Array<IntArray> {
        val handle = SceneGuardHandle(SCENE_W, SCENE_H, dispatcher = sceneDispatcher).also { handles += it }
        handle.setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides seededOwner(vm, key = "logcat-capture"),
            ) {
                LogcatPanel(devices = devices)
            }
        }
        return handle.renderPixels()
    }

    // ── 用例 ──────────────────────────────────────────────────────────────

    @Test
    fun `running capture auto expands panel and renders console band with lines`() {
        // 滚动窗提交在引擎收集协程的真实 100ms 批量窗口后落定（runningVm 内等待收满）
        val (vm, monitor) = runningVm(
            listOf(
                "10-11 09:00:00.000  100  200 I DemoTag  : captured line one",
                "10-11 09:00:00.100  100  200 I DemoTag  : captured line two",
                "10-11 09:00:00.200  100  200 E DemoTag  : captured line three",
            ),
        )
        val pixels = renderPanel(vm, monitor)
        val bands = contentRows(pixels, SCENE_W)
        assertTrue(
            bands.size >= 3,
            "Running 展开态应渲染 ≥3 内容带（头部行/过滤参数区/控制台），实际 ${bands.size}：$bands" +
                "——面板未自动展开（running→expanded 链断裂）或内容未进渲染面",
        )
        assertBandsSeparated(bands)
        val console = consoleBand(pixels)
        assertTrue(
            console != null,
            "Running 态应渲染深底控制台带（consoleBg #1E1E1E）——控制台缺席即展开链或渲染断裂",
        )
        assertTrue(
            console!!.last - console.first + 1 >= 20,
            "控制台带高度 ${console.last - console.first + 1} px < 20——控制台空壳或行渲染退化",
        )
        val linePixels = countNear(pixels, console, consoleText)
        assertTrue(
            linePixels >= 30,
            "控制台带内应渲染捕获行文字（consoleText #B0BEC5），计数 $linePixels < 30" +
                "——滚动窗行未进渲染面",
        )
    }

    @Test
    fun `long crash stack folds into collapsed single line in console band`() {
        // 折叠门槛：6 连续 at 帧行（≥5 才折叠）→ 渲染为「首帧 + 展开提示」两行；
        // 对照组同形行把帧前缀换成非帧行形态，逐行平铺——两场景行内容同量级，
        // 控制台带高差只能来自折叠
        val crashLines = listOf("10-11 09:00:00.000  100  200 E DemoTag  : FATAL EXCEPTION: main") +
            (1..6).map { i -> "at com.example.demo.Frame$i.render(Frame$i.kt:$i)" } +
            listOf("10-11 09:00:00.900  100  200 I DemoTag  : after crash")
        val plainLines = crashLines.map { line ->
            if (line.startsWith("at ")) "bt " + line.removePrefix("at ") else line
        }
        val (foldedVm, foldedMonitor) = runningVm(crashLines)
        val (plainVm, plainMonitor) = runningVm(plainLines)
        val foldedPixels = renderPanel(foldedVm, foldedMonitor)
        val plainPixels = renderPanel(plainVm, plainMonitor)
        val foldedConsole = consoleBand(foldedPixels)
        val plainConsole = consoleBand(plainPixels)
        assertTrue(
            foldedConsole != null && plainConsole != null,
            "折叠/对照两场景均应渲染深底控制台带——缺席即展开链或渲染断裂",
        )
        val foldedHeight = foldedConsole!!.last - foldedConsole.first + 1
        val plainHeight = plainConsole!!.last - plainConsole.first + 1
        assertTrue(
            plainHeight - foldedHeight >= 30,
            "≥5 连续帧行应折叠为首帧 + 展开提示两行：折叠带高 $foldedHeight vs 平铺带高 $plainHeight，" +
                "差 ${plainHeight - foldedHeight} < 30——折叠失效（逐帧行平铺即红）",
        )
    }

    @Test
    fun `short crash stack lines render highlighted without folding`() {
        // 高亮与折叠同源判定、阈值各异：2 帧行（< 折叠门槛）不折叠但仍逐行着崩溃色；
        // 对照组同形非帧行零崩溃着色——着色判定泄漏到普通行即红
        val crashLines = listOf(
            "10-11 09:00:00.000  100  200 E DemoTag  : boom happened",
            "at Short.one(One.kt:1)",
            "at Short.two(Two.kt:2)",
            "10-11 09:00:00.500  100  200 I DemoTag  : recovered",
        )
        val plainLines = crashLines.map { line ->
            if (line.startsWith("at ")) "bt " + line.removePrefix("at ") else line
        }
        val (crashVm, crashMonitor) = runningVm(crashLines)
        val (plainVm, plainMonitor) = runningVm(plainLines)
        val crashPixels = renderPanel(crashVm, crashMonitor)
        val plainPixels = renderPanel(plainVm, plainMonitor)
        val crashConsole = consoleBand(crashPixels)
        val plainConsole = consoleBand(plainPixels)
        assertTrue(
            crashConsole != null && plainConsole != null,
            "高亮/对照两场景均应渲染深底控制台带——缺席即展开链或渲染断裂",
        )
        val crashColorPixels = countNear(crashPixels, crashConsole!!, logError)
        val plainColorPixels = countNear(plainPixels, plainConsole!!, logError)
        assertTrue(
            crashColorPixels >= 30,
            "短帧行（< 折叠门槛）应逐行着崩溃色（logError #EF5350）进入渲染面，计数 $crashColorPixels < 30" +
                "——崩溃行高亮缺失",
        )
        assertTrue(
            plainColorPixels <= 5,
            "对照场景（非帧行）控制台带内不应有崩溃着色，计数 $plainColorPixels > 5" +
                "——着色判定泄漏到普通行",
        )
        // 短栈不折叠：同形行数两场景控制台带同高（折叠门槛回归即带高收窄）
        val crashHeight = crashConsole.last - crashConsole.first + 1
        val plainHeight = plainConsole.last - plainConsole.first + 1
        assertTrue(
            abs(crashHeight - plainHeight) <= 3,
            "短栈（< 折叠门槛）不应折叠：高亮场景带高 $crashHeight vs 对照带高 $plainHeight" +
                "——折叠门槛漂移（短栈被折叠即红）",
        )
    }

    @Test
    fun `invalid query renders error supporting text into pixel plane`() {
        val (vm, monitor) = runningVm(
            listOf("10-11 09:00:00.000  100  200 I DemoTag  : captured line one"),
        )
        vm.onTagQueryChange("DemoTag")
        assertTrue(vm.queryError.value == null, "前置：有效查询（裸词回退语义）不得置错误态")
        val validPixels = renderPanel(vm, monitor)
        // 待操作数 key 算子：解析失败置错误态，supportingText 错误回执优先占槽（拼接值域提示）
        vm.onTagQueryChange("level:")
        assertTrue(vm.queryError.value != null, "前置：待操作数查询须置错误态（否则 VM 契约回归）")
        val invalidPixels = renderPanel(vm, monitor)

        val errorColor = probeSchemeColor { MaterialTheme.colorScheme.error }
        val gain = countColor(invalidPixels, errorColor) - countColor(validPixels, errorColor)
        assertTrue(
            gain >= 30,
            "查询错误态的 supportingText（错误着色 + isError 边框）应进入渲染面，" +
                "错误色增量 $gain < 30——错误回执缺席或未着错误色",
        )
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 700
    }

    /** 引擎接缝 fake：发完全部行后挂起至 stop（真实长跑流语义），不真跑 adb */
    private class SceneLogcatRunner : LogcatStreamRunner {
        var blockAfterEmit = false
        var emitLines: List<String> = emptyList()

        /** run() 入口信号：Running 置位先于捕获协程首跑，断言须先等本信号（同 VM 测试口径） */
        val started = CompletableDeferred<Unit>()
        private var stopSignal = CompletableDeferred<Unit>()

        override suspend fun run(command: String, onLine: suspend (String) -> Unit): Int? {
            emitLines.forEach { onLine(it) }
            started.complete(Unit)
            if (blockAfterEmit) stopSignal.await()
            return 0
        }

        override fun stop() {
            stopSignal.complete(Unit)
        }
    }
}
