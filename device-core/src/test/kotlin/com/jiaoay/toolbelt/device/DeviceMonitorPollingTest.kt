package com.jiaoay.toolbelt.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备检测自动轮询守护：消费页驻留期间插拔设备即更新；页面不可见（setPageVisible
 * 门控）期间静默跳过、回页即恢复；轮询链路保持失效选定自动清除。
 *
 * 验收形态：虚拟时钟驱动轮询 tick（`advanceTimeBy(DEVICE_POLL_INTERVAL_MS)`
 * 钉死 5s 间隔生效），真实 adb 全链路经**假 adb 脚本**跑通
 * （DeviceConfigStore 注入 adb 路径 → AdbCommandRewriter 路径替换 → SimpleShell 登录
 * shell 子进程 → parseAdbDevices → applyDeviceList）。IO 子进程是真异步，
 * 挂起式等待会让 runTest 快进虚拟时钟与无限轮询互相风暴，故用阻塞短轮询
 * 等待落地（runTest 无阻塞检测，主协程阻塞期间虚拟时钟静止、轮询不再 tick）。
 *
 * 🔴 轮询 scope 的 cancel 必须发生在 runTest 体内（finally）——runTest 收尾阶段
 * 会泵共享 scheduler 至静默，活的轮询环会让虚拟时间无限推进、每拍 spawn 真子进程
 * （process reaper 风暴 OOM）； tearDown 的兜底 cancel 来不及收束。
 * DeviceMonitor 直构配方：user.home 隔离 + 隔离 DeviceConfigStore + Main dispatcher
 * 替换；轮询需显式 initialPageVisible = true（默认 false 是 App 级单源的门控等价语义）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceMonitorPollingTest {

    private val dispatcher = StandardTestDispatcher()

    /** 轮询每拍用的执行通道：返回当前 [devicesOutput] 的设备列表 */
    @Volatile
    private var devicesOutput: String = "List of devices attached\n"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        devicesOutput = "List of devices attached\n"
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newMonitorScope(): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private fun monitor(initialPageVisible: Boolean): Pair<CoroutineScope, DeviceMonitor> {
        val scope = newMonitorScope()
        return scope to DeviceMonitor(
            scope = scope,
            runner = AdbProcessRunner { Result.success(ShellResult(0, devicesOutput, "")) },
            endpointReader = fixedEndpoint(),
            initialPageVisible = initialPageVisible,
        )
    }

    // ── 假 adb 脚本基建 ──

    /** `adb devices -l` 输出体（标题行 + 每台一行 `serial\tdevice model:<model>`） */
    private fun deviceList(vararg devices: Pair<String, String>): String = buildString {
        appendLine("List of devices attached")
        devices.forEach { (serial, model) -> appendLine("$serial\tdevice model:$model") }
    }

    /** 阻塞短轮询等待设备态（IO 子进程是真异步；主协程阻塞期间虚拟时钟静止——见类 KDoc） */
    private fun awaitDeviceState(
        monitor: DeviceMonitor,
        timeoutMs: Long,
        predicate: (DeviceState) -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!predicate(monitor.deviceState.value)) {
            assertTrue(
                System.nanoTime() < deadline,
                "等待设备态超时（${timeoutMs}ms；当前=${monitor.deviceState.value}）",
            )
            Thread.sleep(50)
        }
    }

    // ── 用例 ──

    @Test
    fun `poll interval pinned to 5 seconds in companion`() {
        assertEquals(
            5_000L,
            DeviceMonitor.DEVICE_POLL_INTERVAL_MS,
            "设备域单源钉死 5s 轮询间隔，常量入 DeviceMonitor companion（internal 供本断言锚）",
        )
    }

    @Test
    fun `polling picks up hotplug without manual refresh`() = runTest(dispatcher.scheduler) {
        devicesOutput = deviceList("S1" to "Pixel_6")
        val (scope, monitor) = monitor(initialPageVisible = true)
        try {
            // 首拍先等一个间隔（进页即刷由页面 LaunchedEffect 承担，轮询不双发）
            advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
            runCurrent() // t=5s：轮询首轮检测（真子进程）

            advanceUntil { monitor.deviceState.value is DeviceState.Single }
            assertEquals("S1", assertIs<DeviceState.Single>(monitor.deviceState.value).serial)

            // 热插入第二台：不触碰任何手动入口（refreshDevices/页面层），仅推进下一轮轮询
            devicesOutput = deviceList("S1" to "Pixel_6", "S2" to "Pixel_7")
            advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
            runCurrent() // t=10s：轮询第二轮拾取新设备列表

            advanceUntil { monitor.deviceState.value is DeviceState.Multi && (monitor.deviceState.value as DeviceState.Multi).count == 2 }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `polling suppressed while page invisible and resumes when visible again`() = runTest(dispatcher.scheduler) {
        devicesOutput = deviceList("S1" to "Pixel_6")
        val (scope, monitor) = monitor(initialPageVisible = false)
        try {
            // 切页（onDispose 置 false）：不可见期间推进两拍，轮询必须静默跳过——
            // 不 spawn adb 子进程即无异步落地，None 态断言即时成立（无等待窗口）
            monitor.setPageVisible(false)
            repeat(2) {
                advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
                runCurrent()
            }
            assertEquals(
                DeviceState.None,
                monitor.deviceState.value,
                "页面不可见期间轮询不得执行检测（每拍静默跳过）",
            )

            // 回页（DisposableEffect 置 true）：下一拍恢复轮询，无需补拍
            monitor.setPageVisible(true)
            advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
            runCurrent()
            advanceUntil { monitor.deviceState.value is DeviceState.Single }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `polling clears stale selection when device unplugged`() = runTest(dispatcher.scheduler) {
        devicesOutput = deviceList("S1" to "Pixel_6", "S2" to "Pixel_7")
        val (scope, monitor) = monitor(initialPageVisible = true)
        try {
            advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
            runCurrent()
            advanceUntil { monitor.deviceState.value is DeviceState.Multi }

            // 多设备下选定 S1
            monitor.selectDevice("S1")
            assertEquals("S1", monitor.selectedSerial.value)

            // 拔出 S1（新列表只剩 S2）：轮询链路须保持 applyDeviceList 既有
            // 「陈旧选择自动清除」安全语义（防对已消失设备注入 -s）
            devicesOutput = deviceList("S2" to "Pixel_7")
            advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
            runCurrent()

            advanceUntil { monitor.deviceState.value is DeviceState.Single }
            assertEquals("S2", assertIs<DeviceState.Single>(monitor.deviceState.value).serial)
            assertNull(
                monitor.selectedSerial.value,
                "轮询发现拔出后陈旧选择必须清除（applyDeviceList 安全语义经轮询链路保持）",
            )
        } finally {
            scope.cancel()
        }
    }
}
