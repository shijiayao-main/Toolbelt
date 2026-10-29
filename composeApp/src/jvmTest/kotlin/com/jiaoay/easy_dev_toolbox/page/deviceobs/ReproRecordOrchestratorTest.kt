package com.jiaoay.easy_dev_toolbox.page.deviceobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.jiaoay.toolbelt.device.DeviceInfo
import com.jiaoay.toolbelt.device.DeviceState

/**
 * 复现记录编排器单测（fake 成员，端口级契约——三引擎真机行为由各模块既有
 * 测试自证，本文件只钉编排语义）：
 * - 齐起：全部空闲时逐成员各起一次，一条聚合回执；
 * - 部分在跑：在跑成员跳过且单列明示（不重复起不静默吞），空闲成员照起；
 * - 停止聚合：只收在跑成员、空闲成员如实分列（幂等不被冒领「已停止」）；
 * - 单成员失败不拖累：同步异常进失败行，其余成员照常转发；
 * - 启动门拦截：门消息原样回执，零成员转发。
 */
class ReproRecordOrchestratorTest {

    /** fake 成员：起停计数 + 可编排的抛异常钩子；start 落在跑、stop 落空闲（引擎异步终态的即时近似） */
    private class FakeMember(
        override val label: String,
        override val skipReceipt: String = "$label 已在跑，跳过",
        private val startHook: () -> Unit = {},
        private val stopHook: () -> Unit = {},
    ) : ReproRecordOrchestrator.Member {
        var running = false
        var startCalls = 0
        var stopCalls = 0

        override fun isRunning(): Boolean = running

        override fun start() {
            startCalls++
            startHook()
            running = true
        }

        override fun stop() {
            stopCalls++
            stopHook()
            running = false
        }
    }

    /** 被测编排器 + 回执捕获槽 */
    private class Harness(
        members: List<ReproRecordOrchestrator.Member>,
        gate: () -> String? = { null },
    ) {
        val receipts = mutableListOf<String>()
        val orchestrator = ReproRecordOrchestrator(
            members = members,
            showReceipt = { receipts += it },
            startGate = gate,
        )
    }

    private val memberLabels = listOf("logcat", "性能", "投屏")

    private fun trio(
        startHooks: Map<String, () -> Unit> = emptyMap(),
        stopHooks: Map<String, () -> Unit> = emptyMap(),
    ): List<FakeMember> = memberLabels.map { label ->
        FakeMember(
            label = label,
            skipReceipt = when (label) {
                "logcat" -> "logcat 已在抓取，跳过"
                "性能" -> "性能已在采样，跳过"
                else -> "投屏已在进行，跳过"
            },
            startHook = startHooks[label] ?: {},
            stopHook = stopHooks[label] ?: {},
        )
    }

    @Test
    fun `齐起_全部空闲_逐成员各起一次单条聚合回执`() {
        val members = trio()
        val harness = Harness(members)

        harness.orchestrator.startAll()

        members.forEach { assertEquals(1, it.startCalls, "${it.label} 应各起一次") }
        assertEquals(listOf("复现记录已开始：logcat、性能、投屏"), harness.receipts)
    }

    @Test
    fun `部分在跑_在跑成员跳过单列明示_空闲成员照起`() {
        val members = trio()
        members.first { it.label == "logcat" }.running = true
        val harness = Harness(members)

        harness.orchestrator.startAll()

        assertEquals(0, members.first { it.label == "logcat" }.startCalls, "在跑成员不得重复起")
        assertEquals(1, members.first { it.label == "性能" }.startCalls)
        assertEquals(1, members.first { it.label == "投屏" }.startCalls)
        assertEquals(
            listOf("复现记录已开始：性能、投屏；logcat 已在抓取，跳过"),
            harness.receipts,
        )
    }

    @Test
    fun `全部在跑_零启动_回执只单列跳过行`() {
        val members = trio().onEach { it.running = true }
        val harness = Harness(members)

        harness.orchestrator.startAll()

        members.forEach { assertEquals(0, it.startCalls) }
        assertEquals(
            listOf("logcat 已在抓取，跳过；性能已在采样，跳过；投屏已在进行，跳过"),
            harness.receipts,
        )
    }

    @Test
    fun `停止聚合_全部在跑_逐成员全停单条回执`() {
        val members = trio().onEach { it.running = true }
        val harness = Harness(members)

        harness.orchestrator.stopAll()

        members.forEach { assertEquals(1, it.stopCalls, "${it.label} 应各停一次") }
        assertEquals(listOf("复现记录已停止：logcat、性能、投屏"), harness.receipts)
    }

    @Test
    fun `停止聚合_部分空闲_只收在跑成员_空闲如实分列不调stop`() {
        val members = trio()
        members.first { it.label == "logcat" }.running = true
        val harness = Harness(members)

        harness.orchestrator.stopAll()

        assertEquals(1, members.first { it.label == "logcat" }.stopCalls)
        members.filter { it.label != "logcat" }.forEach {
            assertEquals(0, it.stopCalls, "空闲成员 stop 不被调用（幂等语义不冒领「已停止」）")
        }
        assertEquals(listOf("复现记录已停止：logcat；性能、投屏 本就空闲"), harness.receipts)
    }

    @Test
    fun `停止聚合_全部空闲_回执无在跑成员不刷屏`() {
        val members = trio()
        val harness = Harness(members)

        harness.orchestrator.stopAll()

        members.forEach { assertEquals(0, it.stopCalls) }
        assertEquals(listOf("无在跑成员，无需停止"), harness.receipts)
    }

    @Test
    fun `单成员启动失败不拖累_失败进聚合行其余照起`() {
        val members = trio(
            startHooks = mapOf("性能" to { throw IllegalStateException("adb 不可用") }),
        )
        val harness = Harness(members)

        harness.orchestrator.startAll()

        assertEquals(1, members.first { it.label == "logcat" }.startCalls)
        assertEquals(1, members.first { it.label == "性能" }.startCalls, "失败成员也被转发过（失败在其内部发生）")
        assertEquals(1, members.first { it.label == "投屏" }.startCalls)
        assertEquals(
            listOf("复现记录已开始：logcat、投屏；启动失败：性能（adb 不可用）"),
            harness.receipts,
        )
    }

    @Test
    fun `全部启动失败_升格动作级失败头`() {
        val members = trio(
            startHooks = memberLabels.associateWith { _: String ->
                { throw IllegalStateException("boom") }
            },
        )
        val harness = Harness(members)

        harness.orchestrator.startAll()

        assertEquals(
            listOf("复现记录启动失败：logcat（boom）、性能（boom）、投屏（boom）"),
            harness.receipts,
        )
    }

    @Test
    fun `单成员停止失败不拖累_其余成员照常收割`() {
        val members = trio(
            stopHooks = mapOf("投屏" to { throw IllegalStateException("句柄泄漏") }),
        ).onEach { it.running = true }
        val harness = Harness(members)

        harness.orchestrator.stopAll()

        assertEquals(1, members.first { it.label == "logcat" }.stopCalls)
        assertEquals(1, members.first { it.label == "性能" }.stopCalls)
        assertEquals(
            listOf("复现记录已停止：logcat、性能；停止失败：投屏（句柄泄漏）"),
            harness.receipts,
        )
    }

    @Test
    fun `启动门拦截_门消息原样回执_零成员转发`() {
        val members = trio()
        val harness = Harness(members, gate = { "未检测到设备——连接设备后将自动检测" })

        harness.orchestrator.startAll()

        members.forEach { assertEquals(0, it.startCalls) }
        assertEquals(listOf("未检测到设备——连接设备后将自动检测"), harness.receipts)
    }

    @Test
    fun `启动门放行时成员正常转发`() {
        val members = trio()
        val harness = Harness(members, gate = { null })

        harness.orchestrator.startAll()

        members.forEach { assertEquals(1, it.startCalls) }
    }

    @Test
    fun `回执纯函数_零成员零事件不产回执`() {
        assertNull(ReproRecordOrchestrator.startReceipt(emptyList(), emptyList(), emptyList()))
        assertNull(ReproRecordOrchestrator.stopReceipt(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `回执纯函数_停止失败无成功停止_只留失败行`() {
        assertEquals(
            "停止失败：投屏（超时）",
            ReproRecordOrchestrator.stopReceipt(
                stopped = emptyList(),
                idle = listOf("logcat", "性能"),
                failed = listOf("投屏（超时）"),
            ),
        )
    }
}

/** 编排启动门（设备域公共前置检查）纯函数契约：三拦截形态 + 两放行形态 */
class ReproRecordGateTest {

    @Test
    fun `检测失败_回执指路设置页`() {
        assertEquals(
            "adb 路径无效——请检查设置页 ADB 路径",
            reproRecordGateMessage(DeviceState.None, "adb 路径无效", null),
        )
    }

    @Test
    fun `无设备_回执指路连接设备`() {
        assertEquals(
            "未检测到设备——连接设备后将自动检测",
            reproRecordGateMessage(DeviceState.None, null, null),
        )
    }

    @Test
    fun `多设备未选定_回执指路设备栏`() {
        val multi = DeviceState.Multi(listOf(DeviceInfo("serial-1", "demo"), DeviceInfo("serial-2", "demo")))
        assertEquals(
            "检测到多台设备，请先在上方设备栏选择目标设备",
            reproRecordGateMessage(multi, null, null),
        )
    }

    @Test
    fun `单设备与多设备已选定_放行`() {
        assertNull(reproRecordGateMessage(DeviceState.Single("serial-1", "demo"), null, null))
        val multi = DeviceState.Multi(listOf(DeviceInfo("serial-1", "demo"), DeviceInfo("serial-2", "demo")))
        assertNull(reproRecordGateMessage(multi, null, "serial-1"))
    }
}
