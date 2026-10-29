package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.device.DeviceKeys.VOLUME_UP
import com.jiaoay.toolbelt.device.DeviceKeys.HOME
import com.jiaoay.toolbelt.device.DeviceKeys.BACK
import com.jiaoay.toolbelt.adb.AdbEndpoint

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 按键注入器守护（fake runner + runTest 虚拟时钟，零真子进程）：
 * - 通道纪律：命令全文经 AdbCommandRewriter 注入 adb 路径与 `-s <serial>`，
 *   payload 按 shell 语义包裹（Unix 单引号），fake runner 侧断言命令全文；
 * - 默认目标解析：选定优先/单设备回退/无目标零 spawn 人话拒绝；
 * - 批量分发：逐台顺序执行、目标集显式不隐式扩集、逐台独立成败
 *  （一台失败不拖垮其余台）、busy 在 finally 单点复位、busy 期连点防抖；
 * - 长按连发（虚拟时间）：首拍即注 + 节流间隔生效、松手即停（stop 幂等）+
 *   三路取消（抬起/覆盖再武装/scope cancel）+ 连发中单击保守拒绝零排队。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyEventInjectorTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope

    /** 设备三流 fake：直接驱动三个 StateFlow（免 DeviceMonitor 轮询链） */
    private class FakeDevices : DeviceStateSource {
        override val deviceState = MutableStateFlow<DeviceState>(DeviceState.None)
        override val detectError = MutableStateFlow<String?>(null)
        override val selectedSerial = MutableStateFlow<String?>(null)
    }

    /** runner fake：记录命令全文（通道纪律断言锚），按内容路由回执 */
    private class FakeGateway(private val respond: suspend (String) -> ShellResult) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return Result.success(respond(command))
        }
    }

    private val devices = FakeDevices()

    private fun okGateway() = FakeGateway { ShellResult(0, "", "") }

    private fun injector(runner: AdbProcessRunner = okGateway()): KeyEventInjector = KeyEventInjector(
        scope = scope,
        devices = devices,
        runner = runner,
        endpointReader = { AdbEndpoint("") },
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    // ── 通道纪律与默认目标解析 ─────────────────────────────────────

    @Test
    fun `injectKey to single device builds rewritten adb command`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val runner = okGateway()
        val injector = injector(runner)
        injector.injectKey(VOLUME_UP)
        runCurrent()

        assertEquals(
            listOf("adb -s S1 shell 'input keyevent 24'"),
            runner.commands,
            "命令须为 adb -s <serial> shell 'input keyevent <code>'（payload 整体包裹）",
        )
        val receipt = injector.state.value.lastBatch!!.receipts.single()
        assertEquals("S1", receipt.serial)
        assertTrue(receipt.success, "退出码 0 须成功回执")
        assertNull(receipt.message, "成功回执 message 为 null")
    }

    @Test
    fun `injectKey prefers selected serial and rewrites custom adb path`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        devices.selectedSerial.value = "S2"
        val runner = okGateway()
        val customPathInjector = KeyEventInjector(
            scope = scope,
            devices = devices,
            runner = runner,
            endpointReader = { AdbEndpoint("/opt/my adb") },
        )
        customPathInjector.injectKey(BACK)
        runCurrent()

        assertEquals(
            listOf("'/opt/my adb' -s S2 shell 'input keyevent 4'"),
            runner.commands,
            "选定设备优先注入 -s，自定义 adb 路径（含空白）按 Unix 单引号包裹替换",
        )
    }

    @Test
    fun `injectKey rejects without spawn when no target resolvable`() = runTest(dispatcher.scheduler) {
        // 形态一：多设备未选定
        devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        devices.selectedSerial.value = null
        val runner = okGateway()
        val injector = injector(runner)
        injector.injectKey(VOLUME_UP)
        runCurrent()

        assertTrue(runner.commands.isEmpty(), "多设备未选定不得 spawn")
        assertTrue(
            injector.state.value.rejectError?.contains("无可用目标设备") == true,
            "拒绝须人话回执（实际：${injector.state.value.rejectError}）",
        )
        assertNull(injector.state.value.lastBatch, "拒绝路径不产回执集")

        // 形态二：无设备
        devices.deviceState.value = DeviceState.None
        val g2 = okGateway()
        injector(g2).injectKey(VOLUME_UP)
        runCurrent()
        assertTrue(g2.commands.isEmpty(), "无设备不得 spawn")

        // 形态三：检测失败（设备态未知）
        devices.detectError.value = "设备检测失败：adb 路径无效"
        val g3 = okGateway()
        injector(g3).injectKey(VOLUME_UP)
        runCurrent()
        assertTrue(g3.commands.isEmpty(), "检测失败不得 spawn")
        devices.detectError.value = null

        // 形态四：选定残留但设备已消失
        devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S2", "M2")))
        devices.selectedSerial.value = "S1"
        val g4 = okGateway()
        injector(g4).injectKey(VOLUME_UP)
        runCurrent()
        assertTrue(g4.commands.isEmpty(), "选定失效（设备已消失）不得 spawn，防对空串号注入 -s")
    }

    // ── 批量分发 ──────────────────────────────────────────────────

    @Test
    fun `injectToTargets dispatches sequentially to every serial with independent receipts`() =
        runTest(dispatcher.scheduler) {
            devices.deviceState.value = DeviceState.Multi(
                listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2"), DeviceInfo("S3", "M3")),
            )
            // S2 注入失败（adb 退出码 1），S1/S3 成功——逐台独立成败不互相拖垮
            val runner = FakeGateway { command ->
                if (command.contains("-s S2")) {
                    ShellResult(1, "", "error: device S2 offline")
                } else {
                    ShellResult(0, "", "")
                }
            }
            val injector = injector(runner)
            injector.injectToTargets(VOLUME_UP, listOf("S1", "S2", "S3"))
            runCurrent()

            assertEquals(
                listOf(
                    "adb -s S1 shell 'input keyevent 24'",
                    "adb -s S2 shell 'input keyevent 24'",
                    "adb -s S3 shell 'input keyevent 24'",
                ),
                runner.commands,
                "批量按目标集顺序逐台分发，每台命令独立携带各自 serial",
            )
            val batch = injector.state.value.lastBatch!!
            assertEquals(listOf("S1", "S2", "S3"), batch.receipts.map { it.serial }, "回执集与目标集同序")
            assertTrue(batch.receipts[0].success, "S1 成功")
            assertFalse(batch.receipts[1].success, "S2 须失败")
            assertTrue(
                batch.receipts[1].message!!.contains("退出码 1") && batch.receipts[1].message!!.contains("offline"),
                "失败回执须含退出码与 stderr 人话（实际：${batch.receipts[1].message}）",
            )
            assertTrue(batch.receipts[2].success, "S3 不受 S2 失败拖累")
            assertFalse(injector.state.value.injecting, "批量完成后 busy 复位")
            assertNull(injector.state.value.rejectError, "执行路径不产拒绝回执")
        }

    @Test
    fun `injectToTargets rejects empty set without spawn`() = runTest(dispatcher.scheduler) {
        val runner = okGateway()
        val injector = injector(runner)
        injector.injectToTargets(BACK, emptyList())
        runCurrent()

        assertTrue(runner.commands.isEmpty(), "空目标集不得 spawn")
        assertTrue(
            injector.state.value.rejectError?.contains("批量目标集为空") == true,
            "空集拒绝须人话回执（实际：${injector.state.value.rejectError}）",
        )
    }

    @Test
    fun `runner spawn failure lands as per-device receipt not crash`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val boom = AdbProcessRunner { Result.failure(IllegalStateException("adb not found")) }
        val injector = injector(boom)
        injector.injectKey(BACK)
        runCurrent()

        val receipt = injector.state.value.lastBatch!!.receipts.single()
        assertFalse(receipt.success)
        assertTrue(
            receipt.message!!.contains("adb not found"),
            "进程启动真失败须落人话回执（实际：${receipt.message}）",
        )
    }

    @Test
    fun `busy period drops new injections and resets in finally`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val gate = CompletableDeferred<Unit>()
        val gated = FakeGateway {
            gate.await()
            ShellResult(0, "", "")
        }
        val injector = injector(gated)
        injector.injectKey(VOLUME_UP)
        runCurrent()
        assertTrue(injector.state.value.injecting, "批量在途 busy 态")

        // busy 期连点：直接忽略（无排队语义），不报错不排队
        injector.injectKey(BACK)
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        val batch = injector.state.value.lastBatch!!
        assertEquals(VOLUME_UP, batch.key, "busy 期动作被丢弃，未替换在途批量")
        assertEquals(1, gated.commands.size, "busy 期不得追加 exec")
        assertFalse(injector.state.value.injecting, "gate 放行后 busy 在 finally 复位")
    }

    // ── 自定义键值与注入缝 ─────────────────────────────────────────

    @Test
    fun `customKey parses decimal in range and rejects garbage`() {
        assertEquals(DeviceKey(123, "自定义 123"), DeviceKeys.customKey("123"))
        assertNull(DeviceKeys.customKey("abc"), "非数字拒绝")
        assertNull(DeviceKeys.customKey("-4"), "负数拒绝")
        assertNull(DeviceKeys.customKey("0"), "0 非法（无 0 号键）")
        assertNull(DeviceKeys.customKey("1000"), "超上限拒绝（上限 ${DeviceKeys.MAX_CUSTOM_KEYCODE}）")
        assertEquals(DeviceKey(999, "自定义 999"), DeviceKeys.customKey(" 999 "), "首尾空白容忍")
    }

    @Test
    fun `applyBatch is the single write path for host preset`() = runTest(dispatcher.scheduler) {
        val injector = injector()
        val batch = KeyInjectionBatch(
            key = DeviceKeys.HOME,
            receipts = listOf(KeyInjectionReceipt("S1", DeviceKeys.HOME, success = true, message = null)),
        )
        injector.applyBatch(batch)
        assertEquals(batch, injector.state.value.lastBatch)
        assertNull(injector.state.value.rejectError, "回执落地须清拒绝回执")
    }

    // ── 长按连发（虚拟时间） ─────────────────────────────────────

    @Test
    fun `repeat injects first tick immediately then throttles at interval`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val runner = okGateway()
        val injector = injector(runner)

        assertTrue(injector.startRepeat(VOLUME_UP, intervalMs = 100), "默认目标可解析，连发须起")
        assertEquals(VOLUME_UP, injector.state.value.repeatingKey, "连发在途键同步入态")
        assertNull(injector.state.value.rejectError, "起动路径不产拒绝回执")
        runCurrent()
        assertEquals(1, runner.commands.size, "首拍立即注入（长按成立时用户已按住）")

        advanceTimeBy(99)
        assertEquals(1, runner.commands.size, "拍间隔内零追加（节流间隔生效）")
        advanceTimeBy(2) // t=101：第二拍落点已过
        assertEquals(2, runner.commands.size, "间隔到期追加一拍")
        advanceTimeBy(300) // t=401：拍到 t=200/300/400
        assertEquals(5, runner.commands.size, "连发按间隔持续（t=0/100/200/300/400 共 5 拍）")
        // 每拍刷新回执集
        val batch = injector.state.value.lastBatch!!
        assertEquals(VOLUME_UP, batch.key)
        assertEquals("S1", batch.receipts.single().serial)

        injector.stopRepeat()
        assertEquals(5, runner.commands.size, "松手即停：stop 后零迟到拍")
    }

    @Test
    fun `stopRepeat is idempotent and clears repeating key synchronously`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val runner = okGateway()
        val injector = injector(runner)

        injector.startRepeat(VOLUME_UP, intervalMs = 100)
        runCurrent()
        injector.stopRepeat()
        assertNull(injector.state.value.repeatingKey, "停止同步清在途键（不等取消协程调度）")
        advanceTimeBy(10_000)
        assertEquals(1, runner.commands.size, "停止后推进任意时长零追加")

        // 幂等：无在途连发时 stop = 无害 no-op
        injector.stopRepeat()
        assertNull(injector.state.value.repeatingKey)
        advanceTimeBy(10_000)
        assertEquals(1, runner.commands.size)
    }

    @Test
    fun `repeat during repeat replaces the running key with no late ticks from old key`() =
        runTest(dispatcher.scheduler) {
            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            val runner = okGateway()
            val injector = injector(runner)

            injector.startRepeat(VOLUME_UP, intervalMs = 100)
            runCurrent()
            assertEquals(1, runner.commands.size)
            advanceTimeBy(101) // 虚拟时钟推进为「过点才执行」——t=100 的拍须推进到 >100 才落
            assertEquals(2, runner.commands.size)

            // 覆盖再武装：新键顶掉旧键
            assertTrue(injector.startRepeat(BACK, intervalMs = 100), "覆盖再武装须起新连发")
            assertEquals(BACK, injector.state.value.repeatingKey)
            advanceTimeBy(10_000)
            assertTrue(runner.commands.drop(2).all { it.contains("keyevent 4") }, "旧键零迟到拍，后续全为新键（实际：${runner.commands}）")
            injector.stopRepeat()
        }

    @Test
    fun `scope cancel kills the repeat job and finally clears repeating key`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val runner = okGateway()
        val injector = injector(runner)

        injector.startRepeat(VOLUME_UP, intervalMs = 100)
        runCurrent()
        assertEquals(1, runner.commands.size)

        // 会话停止路径：持有方 scope cancel，连发 job 随会话消亡
        scope.cancel()
        runCurrent()
        assertNull(injector.state.value.repeatingKey, "job 被动消亡时 finally 兜底清在途键")
        advanceTimeBy(10_000)
        assertEquals(1, runner.commands.size, "会话停止后零追加")
    }

    @Test
    fun `single click during repeat is rejected with receipt not queued`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val runner = okGateway()
        val injector = injector(runner)

        injector.startRepeat(VOLUME_UP, intervalMs = 100)
        runCurrent()

        injector.injectKey(BACK)
        runCurrent()
        assertTrue(
            injector.state.value.rejectError?.contains("长按连发进行中") == true,
            "连发中单击须人话拒绝回执（实际：${injector.state.value.rejectError}）",
        )
        assertEquals(VOLUME_UP, injector.state.value.lastBatch?.key, "拒绝单击不顶掉连发回执")

        injector.injectToTargets(BACK, listOf("S1"))
        runCurrent()
        assertTrue(runner.commands.none { it.contains("keyevent 4") }, "连发中单击零 spawn（拒绝不排队）")

        injector.stopRepeat()
        advanceTimeBy(10_000)
        assertTrue(runner.commands.none { it.contains("keyevent 4") }, "松手后旧拒绝不复活为单击")
    }

    @Test
    fun `startRepeat rejects while batch is in flight`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val gate = CompletableDeferred<Unit>()
        val gated = FakeGateway {
            gate.await()
            ShellResult(0, "", "")
        }
        val injector = injector(gated)
        injector.injectKey(VOLUME_UP)
        runCurrent()
        assertTrue(injector.state.value.injecting, "批量在途 busy 态")

        assertFalse(injector.startRepeat(BACK), "busy 期起连发须拒绝")
        assertTrue(
            injector.state.value.rejectError?.contains("注入在途") == true,
            "busy 拒绝须人话回执（实际：${injector.state.value.rejectError}）",
        )
        assertFalse(gated.commands.any { it.contains("keyevent 4") }, "busy 期拒绝零 spawn")

        gate.complete(Unit)
        runCurrent()
        assertFalse(injector.state.value.injecting, "gate 放行后 busy 复位")

        assertTrue(injector.startRepeat(BACK, intervalMs = 100), "busy 复位后连发可起")
        injector.stopRepeat()
    }

    @Test
    fun `repeat to explicit targets ticks every serial per beat sequentially`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S1", "M1"), DeviceInfo("S2", "M2")))
        val runner = okGateway()
        val injector = injector(runner)

        assertTrue(injector.startRepeatToTargets(VOLUME_UP, listOf("S1", "S2"), intervalMs = 100))
        runCurrent()
        assertEquals(
            listOf(
                "adb -s S1 shell 'input keyevent 24'",
                "adb -s S2 shell 'input keyevent 24'",
            ),
            runner.commands,
            "首拍对目标集逐台顺序注入",
        )
        advanceTimeBy(101)
        assertEquals(4, runner.commands.size, "第二拍仍逐台两发（t=0 两发 + t=100 两发）")
        assertEquals(listOf("S1", "S2"), injector.state.value.lastBatch!!.receipts.map { it.serial }, "每拍刷新整批回执")

        injector.stopRepeat()
        advanceTimeBy(10_000)
        assertEquals(4, runner.commands.size)
    }

    @Test
    fun `startRepeat rejects without spawn for unresolvable target, empty set and bad interval`() =
        runTest(dispatcher.scheduler) {
            devices.deviceState.value = DeviceState.None
            val runner = okGateway()
            val injector = injector(runner)

            assertFalse(injector.startRepeat(HOME), "无设备零 spawn 拒绝")
            assertTrue(injector.state.value.rejectError?.contains("无可用目标设备") == true)

            assertFalse(injector.startRepeatToTargets(HOME, emptyList()), "空目标集零 spawn 拒绝")
            assertTrue(injector.state.value.rejectError?.contains("批量目标集为空") == true)

            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            assertFalse(injector.startRepeatToTargets(HOME, listOf("S1"), intervalMs = 0), "非正间隔零 spawn 拒绝")
            assertTrue(injector.state.value.rejectError?.contains("连发间隔非法") == true)

            assertNull(injector.state.value.repeatingKey, "拒绝路径不得置在途键")
            assertTrue(runner.commands.isEmpty(), "三形态拒绝全部零 spawn")
        }

    @Test
    fun `repeat uses single-source default interval constant`() = runTest(dispatcher.scheduler) {
        assertEquals(250L, KeyEventInjector.REPEAT_INTERVAL_MS, "节流间隔单源常量默认档——变更须连同面板回执文案与防风暴口径一起评审")
    }
}
