package com.jiaoay.easy_dev_toolbox.page.logcat

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.toolbelt.device.ShellResult
import com.jiaoay.testfixtures.UserHomeIsolation
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * 武装捕获常见特征预设守护：
 * - 可编译性 + 样例行命中矩阵：每条预设对样例崩溃行命中、对无关行（含同词小写
 *   变体）不命中——正则误宽即红；预设是武装通道的原始行正则，与渲染层崩溃
 *   判定（[isCrashLine] 堆栈帧行形态）两语义两通道不合流，本测试矩阵只锚
 *   武装命中语义，不锚渲染折叠/高亮；
 * - 只填入不武装（预填不自动执行）：Running 态下逐条预设填入只写输入态——
 *   引擎等待态零置位、锚点零落、零回执；武装仍由显式 armCapture 动作置位
 *   （填入不得吞显式路径）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogcatArmFeaturePresetTest {

    private val homeIsolation = UserHomeIsolation("edt-logcat-arm-preset")
    private val dispatcher = StandardTestDispatcher()
    private val createdScopes = mutableListOf<CoroutineScope>()
    private val createdVms = mutableListOf<LogcatViewModel>()
    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
        originalStore = LogcatConfigStore.store
        LogcatConfigStore.store = LocalDataManager(
            dataStoreName = "test_logcat_arm_preset_${UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
        )
    }

    @AfterTest
    fun tearDown() {
        // 防跨类污染（坑）：未撤的 viewModelScope 与 Main 队列未消费任务会跨类污染
        // 后续测试类的 setMain/resetMain（并发读写 Main 即 IllegalStateException）
        // ——先撤 VM 域、排空队列、再复位
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        createdScopes.forEach { it.cancel() }
        homeIsolation.restoreOnly()
        LogcatConfigStore.store = originalStore
        Dispatchers.resetMain()
    }

    /** 发射预置行后阻塞至 stop 的运行器（武装等待态需要 Running 引擎） */
    private class PresetFakeRunner : LogcatStreamRunner {
        var emitLines: List<String> = emptyList()

        /** run() 入口信号：Running 置位先于捕获协程首跑，断言须先等本信号 */
        val started = CompletableDeferred<Unit>()
        private var stopSignal = CompletableDeferred<Unit>()

        override suspend fun run(command: String, onLine: suspend (String) -> Unit): Int? {
            emitLines.forEach { onLine(it) }
            started.complete(Unit)
            stopSignal.await()
            return 0
        }

        override fun stop() {
            stopSignal.complete(Unit)
        }
    }

    private fun newPresetVm(): Pair<PresetFakeRunner, LogcatViewModel> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val monitor = DeviceMonitor(
            scope = scope,
            runner = { Result.success(ShellResult(0, "", "")) },
            endpointReader = { AdbEndpoint("") },
        ).also { monitor ->
            monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1")))
        }
        val runner = PresetFakeRunner()
        val engine = LogcatCaptureEngine(
            CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it },
            runner,
        )
        return runner to LogcatViewModel(monitor, engine, adbPathReader = { "" }).also { createdVms += it }
    }

    // ── 命中矩阵 ──────────────────────────────────────────────────────

    /** 每条预设的样例行命中矩阵：应命中行与不应命中行（小写变体/无关噪音） */
    private val hitMatrix: Map<String, Pair<List<String>, List<String>>> = mapOf(
        "FATAL EXCEPTION" to (
            listOf(
                "10-11 09:00:00.000  100  200 E AndroidRuntime: FATAL EXCEPTION: main",
                "10-11 09:00:00.001  100  200 E AndroidRuntime: FATAL EXCEPTION in thread main",
            ) to listOf(
                // 同词小写变体不命中（正则大小写敏感——命中精度）
                "10-11 09:00:00.000  100  200 W DemoTag: fatal exception talk in review",
                "10-11 09:00:00.000  100  200 E DemoTag: crashed with exception",
            )
            ),
        "ANR in " to (
            listOf(
                "10-11 09:00:00.000  400  500 E ActivityManager: ANR in com.demo.app (com.demo.app/.MainActivity)",
                "10-11 09:00:00.000  400  500 E ActivityManager: ANR in com.demo.app on thread main",
            ) to listOf(
                "10-11 09:00:00.000  400  500 I ActivityManager: anr in old build reported", // 小写不命中
                "10-11 09:00:00.000  400  500 I ActivityManager: ANR threshold check passed", // 无后缀空格
            )
            ),
        "SIGSEGV|Fatal signal" to (
            listOf(
                "10-11 09:00:00.000  100  200 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 1234",
                "10-11 09:00:00.001  100  300 F DEBUG   : Crash dump done, SIGSEGV",
            ) to listOf(
                "10-11 09:00:00.000  100  200 F libc    : signal 11 received", // 有 signal 无两特征词
                "10-11 09:00:00.000  100  200 W DemoTag: fatal signal simulated in text", // 小写不命中
            )
            ),
        "am_kill|Force stopping" to (
            listOf(
                "10-11 09:00:00.000 1000 1000 I am_kill: [0,1234,com.demo.app,1049]",
                "10-11 09:00:00.000 1000 1000 I ActivityManager: Force stopping com.demo.app",
            ) to listOf(
                "10-11 09:00:00.000 1000 1000 I ActivityManager: killing background service", // 无两特征词
                "10-11 09:00:00.000 1000 1000 W DemoTag: force stopping simulation only", // 小写不命中
            )
            ),
    )

    @Test
    fun `presets compile and hit crash sample lines but miss noise lines`() {
        val labels = LogcatViewModel.ARM_FEATURE_PRESETS.map { it.label }
        assertTrue(labels.size >= 4, "预设清单保守起步四条（实际：$labels）")
        LogcatViewModel.ARM_FEATURE_PRESETS.forEach { preset ->
            val regex = try {
                Regex(preset.pattern)
            } catch (e: Exception) {
                error("预设「${preset.label}」正则不可编译（${preset.pattern}）：${e.message}")
            }
            val (hits, misses) = hitMatrix.getValue(preset.pattern)
            hits.forEach { line ->
                assertTrue(
                    regex.containsMatchIn(line),
                    "预设「${preset.label}」应命中样例行：$line",
                )
            }
            misses.forEach { line ->
                assertFalse(
                    regex.containsMatchIn(line),
                    "预设「${preset.label}」不得误命中噪音行（命中精度）：$line",
                )
            }
        }
    }

    // ── 只填入不武装 ──────────────────────────────────────────────────

    @Test
    fun `preset fill only writes input state and never arms capture`() = runBlocking {
        val (runner, vm) = newPresetVm()
        runner.emitLines = listOf("10-11 09:00:00.000  100  200 I DemoTag: warmup")
        vm.startCapture()
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Running } }
        withTimeout(5_000) { runner.started.await() }

        // Running 态逐条预设填入：输入态得到特征串，武装等待态零置位、锚点零落
        LogcatViewModel.ARM_FEATURE_PRESETS.forEach { preset ->
            vm.onArmPatternChange(preset.pattern)
            assertEquals(preset.pattern, vm.armPattern.value, "填入只写输入态（${preset.label}）")
            assertFalse(vm.armed.value, "预填不得自动武装（${preset.label}）——武装须显式动作")
            assertNull(vm.anchor.value, "预填不落锚点（${preset.label}）")
        }

        // 填入不吞显式路径：显式武装仍照常置位等待态
        vm.armCapture()
        assertTrue(vm.armed.value, "填入后显式武装仍须生效")
        vm.disarmCapture()
        assertFalse(vm.armed.value, "解除武装回落")

        vm.stopCapture()
        withTimeout(5_000) { vm.status.first { it is LogcatCaptureStatus.Stopped } }
        Unit
    }
}
