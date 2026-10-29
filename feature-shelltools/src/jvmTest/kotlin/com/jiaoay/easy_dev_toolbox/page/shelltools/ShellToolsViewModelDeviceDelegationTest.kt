package com.jiaoay.easy_dev_toolbox.page.shelltools

import androidx.lifecycle.viewModelScope
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceMonitor
import com.jiaoay.toolbelt.device.DeviceState

/**
 * ShellToolsViewModel 设备域委托契约：设备三流/busy 面与操作转发必须单源到
 * :core [DeviceMonitor]（构造注入的隔离实例）——VM 是页面作用域消费壳，不得
 * 自持第二份设备状态。检测/轮询/失效选定清除的行为语义回归真源在
 * :core 设备域单测（DeviceMonitor*Test 三件）；本文件钉壳面零漂移：
 * - 四流同帧同引用（assertSame——委托断线即状态双源）；
 * - 选定/清除/应用注入经壳转发落在同一单源实例；
 * - setPageVisible / refreshDevices 转发不抛（门控与 busy 语义由 :core 单测背书）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShellToolsViewModelDeviceDelegationTest {

    private val dispatcher = StandardTestDispatcher()
    private val createdScopes = mutableListOf<CoroutineScope>()
    private val createdVms = mutableListOf<ShellToolsViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        createdVms.forEach { it.viewModelScope.cancel() }
        createdScopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    /** 隔离 monitor：注入恒空回执 channel + 空端点，门控默认关（不 spawn 真实 adb） */
    private fun isolatedMonitor(): DeviceMonitor {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { createdScopes += it }
        return DeviceMonitor(
            scope = scope,
            runner = com.jiaoay.toolbelt.device.AdbProcessRunner {
                Result.success(com.jiaoay.toolbelt.device.ShellResult(0, "", ""))
            },
            endpointReader = { AdbEndpoint("") },
        )
    }

    @Test
    fun `device flows are the monitor single source`() {
        val monitor = isolatedMonitor()
        val viewModel = ShellToolsViewModel(monitor).also { createdVms += it }

        assertSame(monitor.deviceState, viewModel.deviceState, "deviceState 必须同源同流（双源即漂移）")
        assertSame(monitor.detectError, viewModel.detectError, "detectError 必须同源同流")
        assertSame(monitor.detecting, viewModel.detecting, "detecting 必须同源同流")
        assertSame(monitor.selectedSerial, viewModel.selectedSerial, "selectedSerial 必须同源同流")
        assertSame(monitor, viewModel.devices, "devices 窄接口必须透出同一 monitor 实例")
    }

    @Test
    fun `selection operations forward to the single source`() {
        val monitor = isolatedMonitor()
        val viewModel = ShellToolsViewModel(monitor).also { createdVms += it }
        viewModel.applyDeviceList(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))

        assertIs<DeviceState.Multi>(monitor.deviceState.value, "applyDeviceList 注入落在单源")
        viewModel.selectDevice("S1")
        assertEquals("S1", monitor.selectedSerial.value, "selectDevice 转发单源")

        viewModel.clearDeviceSelection()
        assertNull(monitor.selectedSerial.value, "clearDeviceSelection 转发单源")
    }

    @Test
    fun `detect error seam forwards to the single source`() {
        val monitor = isolatedMonitor()
        val viewModel = ShellToolsViewModel(monitor).also { createdVms += it }

        viewModel.applyDetectError("设备检测失败：adb 未找到")

        assertEquals("设备检测失败：adb 未找到", monitor.detectError.value)
        assertIs<DeviceState.None>(monitor.deviceState.value)
    }

    @Test
    fun `refresh and visibility gate forward without touching real adb`() {
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { createdScopes += it }
        // 挂起网关（永不返回）：busy 置位后驻留，断言无「finally 先于断言复位」竞态
        val monitor = DeviceMonitor(
            scope = scope,
            runner = com.jiaoay.toolbelt.device.AdbProcessRunner {
                kotlinx.coroutines.CompletableDeferred<Unit>().await()
                Result.success(com.jiaoay.toolbelt.device.ShellResult(0, "", ""))
            },
            endpointReader = { AdbEndpoint("") },
        )
        val viewModel = ShellToolsViewModel(monitor).also { createdVms += it }

        // 门控接线 + 手动刷新：转发不抛且 busy 即时置位（真实检测语义由
        // :core DeviceMonitorDetectingTest 背书）
        viewModel.setPageVisible(true)
        viewModel.refreshDevices()
        assertTrue(monitor.detecting.value, "refreshDevices 转发后 busy 同帧置位（manual 语义）")
    }
}
