package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 会话级设备选择状态流转回归：选定 → 刷新失效（串号消失 / 退化为单设备 / 无设备）
 * 自动清除、清除选择入口、多设备仍含已选串号时保留。设备域核心升位 :core 后
 * 本文件为选择/失败态语义回归真源：设备列表经 [DeviceMonitor.applyDeviceList]
 * 注入（不执行真实 adb），adbPathReader 注入空读避免触碰真实持久层。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceMonitorSelectionTest {

    private val dispatcher = StandardTestDispatcher()
    private val createdScopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        // 轮询循环随 scope cancel 收束（ monitors 不跨用例滞留）
        createdScopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    private fun monitor(): DeviceMonitor {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { createdScopes += it }
        return DeviceMonitor(
            scope = scope,
            runner = AdbProcessRunner { Result.success(ShellResult(0, "", "")) },
            endpointReader = { AdbEndpoint("") },
        )
    }

    @Test
    fun `selection cleared when selected serial disappears from multi list`() {
        val monitor = monitor()
        // 两台设备 → Multi，选定 S1
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        monitor.selectDevice("S1")
        assertEquals("S1", monitor.selectedSerial.value)

        // 拔掉 S1 后刷新：S1 不在新列表 → 自动清除
        monitor.applyDeviceList(listOf(DeviceInfo("S2", "M2")))

        assertNull(monitor.selectedSerial.value, "已选串号从设备列表消失后应自动清除")
        assertIs<DeviceState.Single>(monitor.deviceState.value)
    }

    @Test
    fun `selection kept when refresh still contains selected serial`() {
        val monitor = monitor()
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        monitor.selectDevice("S1")

        // 刷新后 S1 仍在多设备列表 → 保留选择
        monitor.applyDeviceList(listOf(DeviceInfo("S2", "M2"), DeviceInfo("S1", "M1")))

        assertEquals("S1", monitor.selectedSerial.value)
        val state = assertIs<DeviceState.Multi>(monitor.deviceState.value)
        assertEquals(2, state.count)
    }

    @Test
    fun `selection cleared when state degrades to none`() {
        val monitor = monitor()
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        monitor.selectDevice("S1")

        monitor.applyDeviceList(emptyList())

        assertNull(monitor.selectedSerial.value, "无设备（None）时应清除选择")
        assertIs<DeviceState.None>(monitor.deviceState.value)
    }

    @Test
    fun `clearDeviceSelection resets to unselected without touching device state`() {
        val monitor = monitor()
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        monitor.selectDevice("S1")

        monitor.clearDeviceSelection()

        assertNull(monitor.selectedSerial.value)
        assertIs<DeviceState.Multi>(monitor.deviceState.value, "清除选择不影响设备连接状态")
    }

    @Test
    fun `single device state keeps selection null by default`() {
        val monitor = monitor()
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1")))

        assertNull(monitor.selectedSerial.value, "单设备保持现状：默认不选定不注入")
        assertIs<DeviceState.Single>(monitor.deviceState.value)
    }

    @Test
    fun `single state carries parsed model`() {
        val monitor = monitor()
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "Pixel_6")))

        val state = assertIs<DeviceState.Single>(monitor.deviceState.value)
        assertEquals("S1", state.serial)
        assertEquals("Pixel_6", state.model)
    }

    @Test
    fun `multi state count derives from devices size`() {
        val monitor = monitor()
        monitor.applyDeviceList(listOf(DeviceInfo("S1", ""), DeviceInfo("S2", ""), DeviceInfo("S3", "")))

        val state = assertIs<DeviceState.Multi>(monitor.deviceState.value)
        assertEquals(3, state.count)
        kotlin.test.assertTrue(state.devices.all { it.model.isEmpty() })
    }

    // ── detectError 态转换（三分渲染数据源）──

    @Test
    fun `detect error exposes failure message and collapses to none`() {
        val monitor = monitor()
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        monitor.selectDevice("S1")

        // 检测失败：错误信息非空 + 状态坍缩 None + 陈旧选择清除（不静默冒充「未连接」）
        monitor.applyDetectError("设备检测失败：adb 未找到")

        assertEquals("设备检测失败：adb 未找到", monitor.detectError.value, "失败分支必须暴露含异常信息的错误文案")
        assertIs<DeviceState.None>(monitor.deviceState.value)
        assertNull(monitor.selectedSerial.value, "检测失败设备状态未知，陈旧选择必须清除")
    }

    @Test
    fun `successful detection clears detect error`() {
        val monitor = monitor()
        monitor.applyDetectError("设备检测失败：adb 未找到")

        // 任何成功检测（此处单设备）都清空错误态
        monitor.applyDeviceList(listOf(DeviceInfo("S1", "M1")))

        assertNull(monitor.detectError.value, "成功检测必须清空 detectError")
        assertIs<DeviceState.Single>(monitor.deviceState.value)
    }

    @Test
    fun `successful detection with zero devices clears error and stays none`() {
        val monitor = monitor()
        monitor.applyDetectError("设备检测失败：adb 未找到")

        // 成功但无设备 = 正常空态（None），不是失败——错误态必须清空，不冒充
        monitor.applyDeviceList(emptyList())

        assertNull(monitor.detectError.value, "检测成功但无设备是正常态：detectError 必须清空")
        assertIs<DeviceState.None>(monitor.deviceState.value)
    }

    @Test
    fun `detect error defaults to null before any detection`() {
        val monitor = monitor()
        assertNull(monitor.detectError.value, "初始未检测：detectError 应为 null（非失败态）")
    }
}
