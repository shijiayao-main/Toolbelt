package com.jiaoay.easy_dev_toolbox.page.deviceobs

import androidx.compose.material3.MaterialTheme
import com.jiaoay.easy_dev_toolbox.arch.assertBandsNotOverlapping
import com.jiaoay.easy_dev_toolbox.arch.contentRows
import com.jiaoay.easy_dev_toolbox.arch.countColor
import com.jiaoay.easy_dev_toolbox.arch.probeSchemeColor
import com.jiaoay.easy_dev_toolbox.arch.renderPixels
import com.jiaoay.toolbelt.device.ShellResult
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.jiaoay.toolbelt.device.AdbProcessRunner
import com.jiaoay.toolbelt.device.DeviceApkExporter
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DevicePackageBrowser
import com.jiaoay.toolbelt.device.DevicePackageToggler
import com.jiaoay.toolbelt.device.DevicePerfSample
import com.jiaoay.toolbelt.device.MemoryStats
import com.jiaoay.toolbelt.device.PerfField
import com.jiaoay.toolbelt.device.PerfSamplerState
import com.jiaoay.toolbelt.device.PerfSessionState
import com.jiaoay.toolbelt.device.ProcessEntry
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.device.DeviceFilePuller

/**
 * 应用面板采样徽标/引导 hint 场景渲染像素带守护（[SceneGuardSupport] 单源配方）：
 * 状态注入走生产链——真实
 * [DevicePackageBrowser] + fake 网关（包列举真解析管线跑通，免真 adb）+ fake
 * [PerfSamplerState] StateFlow 经 VM 构造参数直注（活跃会话 + 最近一拍进程排行，
 * 运行态投影真管线全跑通）。
 *
 * 验证面（两态回归网；徽标/hint 审美面归维护者真机终裁）：
 * - 徽标在场（活跃采样态）：匹配包行内的运行态徽标（primary 着色）进入渲染面
 *   ——与无采样同尺寸渲染做差分计数，增量只能来自徽标 pill 文本；
 * - hint 在场（无采样态）：列表已装载但零投影时采样引导 hint 行作为新增内容带
 *   进入渲染面；
 * - 两态互斥差分锚：无采样帧恰比活跃采样帧多一条内容带（hint 行）——徽标 pill
 *   留在行内不增带、hint 行有投影即隐藏，任何一侧泄漏进另一态即带数等式红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicePackageBadgeSceneGuardTest {

    private val homeIsolation = UserHomeIsolation("edt-pkg-badge-scene")
    private val createdScopes = mutableListOf<CoroutineScope>()

    /** VM init 挂起体（viewModelScope Main 档）的推进调度器 */
    private val dispatcher = StandardTestDispatcher()

    /** 设备串号（fake 网关回单台设备与采样态会话锚共用同一台） */
    private val serial = "SN-TEST-0001"

    /** 包列表全部包列举 fixture（五包：四系统 + 一非系统，集合差后 userapp 为非系统） */
    private val pkgAllFixture = """
        package:com.demo.media versionCode:60400000 uid:10061
        package:com.demo.oem.launcher versionCode:30100000 uid:10062
        package:com.android.systemui versionCode:31 uid:10213
        package:com.android.cts.priv.ctsshim versionCode:31 uid:10022
        package:com.example.userapp versionCode:7 uid:10234
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        createdScopes.forEach { it.cancel() }
        homeIsolation.restoreOnly()
        Dispatchers.resetMain()
    }

    /**
     * 设备域 fake 底座：adb 检测回单台设备、pm 列举回五包 fixture（真解析管线
     * 全跑通——系统包列举命令缺席时系统标记走空集，全部包视角不受影响）。
     */
    private inner class RecordingGateway : AdbProcessRunner {
        override suspend fun execute(command: String): Result<ShellResult> =
            Result.success(
                when {
                    command.contains("devices") -> ShellResult(
                        0,
                        "List of devices attached\n$serial\tdevice product:demo model:demo1 device:demo\n",
                        "",
                    )

                    command.contains("pm list packages") -> ShellResult(0, pkgAllFixture, "")

                    else -> ShellResult(0, "", "")
                },
            )
    }

    private fun deviceFakes(): Pair<DeviceMonitor, RecordingGateway> {
        val gateway = RecordingGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { createdScopes += it }
        val monitor = DeviceMonitor(
            scope = scope,
            runner = gateway,
            endpointReader = { AdbEndpoint("") },
        )
        // 页级设备栏进台 LaunchedEffect 同款：预置单台设备（VM init 装载锚定该台）
        monitor.applyDeviceList(listOf(DeviceInfo(serial, "Demo-1")))
        return monitor to gateway
    }

    /**
     * 活跃采样假态：单台会话 Active + 最近一拍进程排行（四包匹配——含
     * `包名:service` 子进程归并形态）经 [DevicePackageViewModel] 运行态投影
     * 真管线产出徽标数据（[PerfField.ok] 六域齐值，进程域是徽标投影唯一消费域）。
     */
    private fun activePerfFlow(): StateFlow<PerfSamplerState> = MutableStateFlow(
        PerfSamplerState(
            primarySerial = serial,
            sessions = mapOf(serial to PerfSessionState.Active(serial)),
            latestBySerial = mapOf(
                serial to DevicePerfSample(
                    timestampMs = 0L,
                    cpuPercent = PerfField.ok(12f),
                    memory = PerfField.ok(MemoryStats(totalKb = 8_000_000L, availableKb = 4_000_000L, freeKb = 3_000_000L)),
                    temperatureC = PerfField.ok(45f),
                    batteryPercent = PerfField.ok(80),
                    processes = PerfField.ok(
                        listOf(
                            ProcessEntry(101, "root", 12.5f, 3.1f, 123_456L, "com.demo.media"),
                            ProcessEntry(102, "root", 7.5f, 2.2f, 65_536L, "com.demo.oem.launcher"),
                            ProcessEntry(103, "root", 5.0f, 1.5f, 32_768L, "com.android.systemui"),
                            ProcessEntry(104, "u0_a234", 15.0f, 4.0f, 210_000L, "com.example.userapp"),
                            ProcessEntry(105, "u0_a234", 30.0f, 6.0f, 126_936L, "com.example.userapp:service"),
                        ),
                    ),
                    fps = PerfField.ok(60f),
                ),
            ),
        ),
    ).asStateFlow()

    private fun vmWith(
        monitor: DeviceMonitor,
        gateway: RecordingGateway,
        perfFlow: StateFlow<PerfSamplerState>,
    ): DevicePackageViewModel {
        val browser = DevicePackageBrowser(runner = gateway, endpointReader = { AdbEndpoint("") })
        return DevicePackageViewModel(
            monitor,
            browser::listPackages,
            browser::packageDetails,
            exporter = DeviceApkExporter(runner = gateway, puller = DeviceFilePuller(runner = gateway, endpointReader = { AdbEndpoint("") }), statEntry = { _, _ -> Result.failure(IllegalStateException("场景测试不触导出")) }, endpointReader = { AdbEndpoint("") }),
            toggler = DevicePackageToggler(runner = gateway, endpointReader = { AdbEndpoint("") }),
            perfState = perfFlow,
        )
    }

    // ── 场景渲染底座（[SceneGuardSupport] 单源） ───────────────

    /**
     * 单源渲染的 Main 档任务推进（onFrame 钩子实参）：VM 的装载请求挂起在
     * viewModelScope Main 档，逐帧推进令装载链与重组交替收敛（不推进则 VM init
     * 永挂起、装载链与渲染面断流）。
     */
    private fun pumpMainTasks() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    // ── 用例 ──────────────────────────────────────────────────────────────

    /**
     * 徽标/hint 两态互斥：同一 fake 设备源 + 同一包列表 fixture，仅采样态不同
     * （活跃会话有最近一拍 vs 空快照）各渲染一帧——徽标着色差分计数 + hint 带
     * 数差分等式双锚。
     */
    @Test
    fun `active sampling renders badges while idle renders hint with mutual exclusion`() {
        val (samplingMonitor, samplingGateway) = deviceFakes()
        val samplingVm = vmWith(samplingMonitor, samplingGateway, activePerfFlow())
        val (idleMonitor, idleGateway) = deviceFakes()
        val idleVm = vmWith(idleMonitor, idleGateway, MutableStateFlow(PerfSamplerState()).asStateFlow())
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(samplingVm.state.value.entries != null, "前置：采样帧包列表经 fake 网关真解析管线装载")
        assertTrue(idleVm.state.value.entries != null, "前置：无采样帧包列表装载")
        val runtime = samplingVm.state.value.runtimeByPackage
        assertEquals(
            setOf(
                "com.demo.media",
                "com.demo.oem.launcher",
                "com.android.systemui",
                "com.example.userapp",
            ),
            runtime.keys,
            "前置：活跃会话最近一拍投影到四个包（含 :service 子进程归并形态）",
        )
        assertEquals(2, runtime.getValue("com.example.userapp").processCount, "前置：子进程归并计数（主+子进程求和聚合）")
        assertTrue(idleVm.state.value.runtimeByPackage.isEmpty(), "前置：无采样帧零投影（hint 判据态）")

        val primaryColor = probeSchemeColor { MaterialTheme.colorScheme.primary }
        val samplingPixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            DevicePackagePanel(devices = samplingMonitor, sharedViewModel = samplingVm, onGoSettings = {})
        }
        val idlePixels = renderPixels(SCENE_W, SCENE_H, onFrame = ::pumpMainTasks) {
            DevicePackagePanel(devices = idleMonitor, sharedViewModel = idleVm, onGoSettings = {})
        }
        val samplingBands = contentRows(samplingPixels, SCENE_W)
        val idleBands = contentRows(idlePixels, SCENE_W)
        assertBandsNotOverlapping(samplingBands)
        assertBandsNotOverlapping(idleBands)

        val midY = samplingPixels.indices.last / 2
        assertTrue(
            samplingBands.any { it.first > midY },
            "采样帧下半区应有包条目行（列表装载链断裂即空壳红），实际 bands=$samplingBands",
        )
        assertTrue(
            idleBands.any { it.first > midY },
            "无采样帧下半区应有包条目行，实际 bands=$idleBands",
        )
        assertTrue(
            countColor(samplingPixels, primaryColor) >= countColor(idlePixels, primaryColor) + BADGE_PRIMARY_GAIN_MIN,
            "活跃采样帧应新增 primary 着色徽标像素（差分增量 < $BADGE_PRIMARY_GAIN_MIN 即徽标缺席）——" +
                "sampling=${countColor(samplingPixels, primaryColor)} idle=${countColor(idlePixels, primaryColor)}",
        )
        assertEquals(
            samplingBands.size + 1,
            idleBands.size,
            "两态互斥：无采样帧应恰比活跃采样帧多一条内容带（采样引导 hint 行）——" +
                "sampling=${samplingBands.size} 带 idle=${idleBands.size} 带（hint 泄漏进采样帧或徽标增带均红）",
        )
    }

    companion object {
        private const val SCENE_W = 1096
        private const val SCENE_H = 900

        /** 徽标 pill 文本（labelSmall 着 primary）四枚的差分计数下限 */
        private const val BADGE_PRIMARY_GAIN_MIN = 10
    }
}
