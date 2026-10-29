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
 * 设备检测/刷新 busy 态验收：manual-only 口径（busy 只服务进页即刷与手动刷新，
 * 轮询恒不置位）。
 *
 * 验收锚：
 * - 手动刷新期间状态栏显「（检测中…）」且刷新钮禁用（`detecting == true`）
 *   ——本测试断数据源 busy 流（置位即时性 + 在途驻留）；「检测中…」后缀 +
 *   `enabled = !detecting` 的装配面由 feature-shelltools 的
 *   ShellToolsViewModelDetectingWiringGuardTest 源码锚钉死（设备状态栏是 :ui
 *   渲染件，无场景测试接缝）；
 * - 检测完成（含失败形态——adb 缺席/网关异常）恢复 `detecting == false`
 *   （finally 单点收口：成功/失败/异常任何路径均复位，防「永久检测中」）；
 * - 轮询路径全链路不置 busy（轮询置 busy 会让状态栏每 5s 闪烁）；
 * - 手动 busy 在途时轮询静默落地不叠加、不清他方 busy（两路径互斥共处）；
 * - 网关注入缝：fake 网关失败走 onFailure 分支（detectError 落地 + busy 复位）。
 *
 * 测试配方：user.home 隔离 + 隔离 DeviceConfigStore + Main dispatcher 替换 +
 * 假 adb 脚本（真子进程走 SimpleShell 全链路）；IO 子进程真异步故用阻塞短轮询
 * 等待（runTest 无阻塞检测，主协程阻塞期间虚拟时钟静止、轮询不 tick——轮询用例
 * 因此不受扰）。
 *
 * 🔴 轮询 scope 的 cancel 必须发生在 runTest 体内（finally）——runTest 收尾阶段
 * 会泵共享 scheduler 至静默，活的轮询环会让虚拟时间无限推进、每拍 spawn 真子进程
 * （process reaper 风暴 OOM）； tearDown 的兜底 cancel 来不及收束。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceMonitorDetectingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 直构 monitor + 配套 scope：scope 须由用例在 runTest 体内 finally cancel（见类 KDoc） */
    private fun newMonitor(
        gateway: AdbProcessRunner = AdbProcessRunner { Result.success(ShellResult(0, "", "")) },
    ): Pair<CoroutineScope, DeviceMonitor> {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return scope to DeviceMonitor(
            scope = scope,
            runner = gateway,
            endpointReader = fixedEndpoint(),
            initialPageVisible = true,
        )
    }

    // ── 假 adb 脚本基建 ──

    /** `adb devices -l` 输出体（标题行 + 每台一行 `serial\tdevice model:<model>`） */
    private fun deviceList(vararg devices: Pair<String, String>): String = buildString {
        appendLine("List of devices attached")
        devices.forEach { (serial, model) -> appendLine("$serial\tdevice model:$model") }
    }

    /**
     * 可控检测耗时的执行通道：先挂起 [delayMs]（制造 busy 驻留窗）再返回给定输出。
     *
     * 虚拟时钟下 delay 由 runTest 推进——busy 驻留与复位断言不依赖真子进程时序。
     */
    private fun slowRunner(stdout: String, delayMs: Long = 0): AdbProcessRunner =
        AdbProcessRunner {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            Result.success(ShellResult(0, stdout, ""))
        }

    /** 阻塞短轮询等待谓词成立 */
    private fun awaitUntil(timeoutMs: Long, description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!predicate()) {
            assertTrue(
                System.nanoTime() < deadline,
                "等待超时（${timeoutMs}ms；$description；当前 detecting 等待态未达成）",
            )
            Thread.sleep(20)
        }
    }

    // ── 基线 ──

    @Test
    fun `detecting defaults to false before any detection`() {
        val (scope, monitor) = newMonitor()
        scope.cancel() // 本用例不进 runTest，无轮询 tick；直接收束
        assertFalse(
            monitor.detecting.value,
            "初始未检测：detecting 应为 false（非 busy 态）",
        )
    }

    // ── 手动路径：置位 → 驻留 → 完成复位（成功） ──

    @Test
    fun `manual refresh sets detecting immediately`() = runTest(dispatcher.scheduler) {
        val (scope, monitor) = newMonitor(slowRunner(deviceList("S1" to "Pixel_6")))
        try {
            // 置位同步发生在 launch 之前：refreshDevices 返回即 busy（钮已禁用，
            // 用户无「点了没反应」的连点窗口）
            assertFalse(monitor.detecting.value, "刷新前非 busy")
            monitor.refreshDevices()
            assertTrue(monitor.detecting.value, "手动刷新入口必须同步置 detecting（刷新钮即时禁用）")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `manual refresh busy persists during detection and clears on success`() = runTest(dispatcher.scheduler) {
        // sleep 1s 制造检测耗时窗：断言 busy 态在窗内驻留（「检测中…」持续可见，
        // 而非闪现即逝）且完成后复位、设备态落地
        val (scope, monitor) = newMonitor(slowRunner(deviceList("S1" to "Pixel_6"), delayMs = 1_000))
        try {
            monitor.refreshDevices()
            assertTrue(monitor.detecting.value, "手动刷新入口必须置 busy")

            // 检测窗内（子进程 sleep 1s）busy 必须仍驻留——250ms 处子进程必然未完
            Thread.sleep(250)
            assertTrue(
                monitor.detecting.value,
                "检测在途（假 adb sleep 1s 未吐 stdout）busy 必须驻留——状态栏「（检测中…）」持续显示的据点",
            )

            // 完成复位 + 设备态落地（finally 收口）
            advanceUntil { !monitor.detecting.value }
            awaitUntil(timeoutMs = 15_000, description = "成功检测设备态落地") {
                monitor.deviceState.value is DeviceState.Single
            }
            assertEquals("S1", assertIs<DeviceState.Single>(monitor.deviceState.value).serial)
            assertNull(monitor.detectError.value, "成功检测必须清 detectError（既有语义回归）")
        } finally {
            scope.cancel()
        }
    }

    // ── 手动路径：失败也必须复位（finally 单点收口） ──

    @Test
    fun `manual refresh clears detecting on failure path`() = runTest(dispatcher.scheduler) {
        // 实况语义：SimpleShell 经登录 shell 包裹执行，adb 路径无效时 bash 自身启动
        // 成功、目标命令 127 退出码——runCatching 产 Success(ShellResult(127)) 走
        // onSuccess + applyDeviceList(空) 落 None 态，**不进 onFailure**。onFailure 仅
        // 进程启动真抛（fork 失败）可达，而两条路径的 busy 复位同走一个 finally
        //（单点收口）——故「检测失败恢复可点」以用户可复现的 adb 缺席形态验收即钉死
        // finally 收口；onFailure 分支自身的 detectError 语义由网关注入缝用例覆盖
        //（见 gateway failure surfaces detect error）。
        val (scope, monitor) = newMonitor(AdbProcessRunner { Result.success(ShellResult(127, "", "bash: adb: command not found")) })
        try {
            monitor.refreshDevices()
            assertTrue(monitor.detecting.value, "手动刷新入口必须置 busy")

            // 检测落地（adb 缺席 = 用户视角的检测失败）：busy 必须复位——
            // finally 单点收口，防「永久检测中」
            advanceUntil { !monitor.detecting.value }
            assertFalse(
                monitor.detecting.value,
                "检测失败（含 adb 缺席 127 形态）也必须复位 busy——finally 单点收口",
            )
            assertIs<DeviceState.None>(
                monitor.deviceState.value,
                "adb 缺席 127 走 onSuccess+空列表落 None（实况语义；fork 失败的 onFailure 才走 applyDetectError）",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `gateway failure surfaces detect error and resets busy`() = runTest(dispatcher.scheduler) {
        // 网关注入缝直测 onFailure 分支（fork 失败形态）：detectError 落地 + 设备态
        // 坍缩 None + busy 复位（finally 单点收口对异常路径同样成立）
        val (scope, monitor) = newMonitor(gateway = { Result.failure(IllegalStateException("adb fork failed")) })
        try {
            monitor.refreshDevices()
            assertTrue(monitor.detecting.value, "手动刷新入口必须置 busy")

            advanceUntil { !monitor.detecting.value }
            assertEquals(
                "设备检测失败：adb fork failed",
                monitor.detectError.value,
                "onFailure 分支必须如实写 detectError（含异常信息，与「无设备」语义二分）",
            )
            assertIs<DeviceState.None>(monitor.deviceState.value, "检测失败设备状态未知，坍缩 None")
            assertNull(monitor.selectedSerial.value, "检测失败陈旧选择清除（防误注入）")
        } finally {
            scope.cancel()
        }
    }

    // ── 轮询路径：恒不置 busy（互斥裁定） ──

    @Test
    fun `polling never sets detecting`() = runTest(dispatcher.scheduler) {
        val (scope, monitor) = newMonitor(slowRunner(deviceList("S1" to "Pixel_6")))
        try {
            // 首拍先等一个间隔再 tick（进页即刷由页面承担）
            advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
            runCurrent() // t=5s：轮询首轮检测（manual = false）

            // 轮询真实跑通（设备态落地 = 轮询链路确实执行了检测）
            awaitUntil(timeoutMs = 15_000, description = "轮询首轮设备态落地") {
                monitor.deviceState.value is DeviceState.Single
            }

            // 轮询全程不置 busy：busy 只服务进页即刷与手动刷新（轮询置 busy 会让
            // 状态栏每 5s 闪「检测中…」）
            assertFalse(
                monitor.detecting.value,
                "轮询路径必须恒不置 busy",
            )

            // 再推进一轮轮询：检测完成后 busy 仍恒 false（无时序竞态侥幸）
            advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
            runCurrent()
            awaitUntil(timeoutMs = 15_000, description = "轮询第二轮落地") {
                monitor.deviceState.value is DeviceState.Single
            }
            assertFalse(
                monitor.detecting.value,
                "轮询多轮后 busy 仍须恒 false（无置位泄漏）",
            )
        } finally {
            scope.cancel()
        }
    }

    // ── 互斥共处：手动 busy 在途时轮询静默落地不叠 busy、不清他方 busy ──

    @Test
    fun `polling during manual busy lands silently without touching busy`() = runTest(dispatcher.scheduler) {
        // 手动检测用延迟 1s 的执行通道制造 busy 窗 → 轮询同链路静默跑（同通道）：
        // busy 由手动方独持、轮询落地不清（busy 归属 manual 方 finally，轮询不清他方）
        val (scope, monitor) = newMonitor(slowRunner(deviceList("S1" to "Pixel_6"), delayMs = 1_000))
        try {
            monitor.refreshDevices()
            assertTrue(monitor.detecting.value, "手动刷新置 busy")

            // busy 窗内只推进 500ms（不足手动方的 1s 延迟）：轮询尚未到拍，手动检测仍在途
            advanceTimeBy(500)
            runCurrent()
            assertTrue(
                monitor.detecting.value,
                "手动 busy 在途时轮询不得清他方 busy（busy 归属 manual 方 finally 收口）",
            )

            // 再推进到轮询拍：轮询静默执行（不置 busy、也不清手动方的 busy）
            advanceTimeBy(DeviceMonitor.DEVICE_POLL_INTERVAL_MS)
            runCurrent()

            // 手动方完成后复位 + 设备态已由（手动或轮询其一）静默落地
            advanceUntil { !monitor.detecting.value }
            advanceUntil { monitor.deviceState.value is DeviceState.Single }
        } finally {
            scope.cancel()
        }
    }
}
