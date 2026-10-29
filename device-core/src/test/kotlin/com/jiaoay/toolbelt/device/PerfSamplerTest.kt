package com.jiaoay.toolbelt.device

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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 采样调度层守护（fake runner + runTest 虚拟时钟，零真子进程）：
 * - 采样间隔与 CPU 差值窗口全参数化，时钟虚拟推进一拍一拍钉（间隔生效/门控
 *   静默跳过/回页恢复）；
 * - 通道纪律：命令经 AdbCommandRewriter 注入 `-s <serial>` + shell 语义包裹
 *   payload（fake runner 侧断言命令全文）；
 * - 失败分层：域级坏输出只落该域 [PerfField.error] 不断会话；整拍失败
 *   （exec 异常/非零退出码）连续 [PerfSampler.MAX_CONSECUTIVE_FAILURES] 次收会话；
 * - 会话面：无目标不启动、换选 retarget、设备消失收会话带人话 reason。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerfSamplerTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var scope: CoroutineScope

    /** 设备三流 fake：直接驱动三个 StateFlow（免 DeviceMonitor 轮询链） */
    private class FakeDevices : DeviceStateSource {
        override val deviceState = MutableStateFlow<DeviceState>(DeviceState.None)
        override val detectError = MutableStateFlow<String?>(null)
        override val selectedSerial = MutableStateFlow<String?>(null)
    }

    /** runner fake：记录命令全文（通道纪律断言锚），按内容路由回显 */
    private class FakeGateway(private val respond: (String) -> ShellResult) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return Result.success(respond(command))
        }
    }

    private val devices = FakeDevices()
    private val clockTicks = AtomicLong(0)

    /** /proc/stat 读序号：相邻两次读取差值钉住 Δtotal=300、Δidle=100 → busy 200/300 */
    private val statReads = AtomicLong(0)

    /** SurfaceFlinger flips 读序号：同一拍内基线/组合两次读取 Δflips=30 → fps 随时钟间隔换算 */
    private val flipsReads = AtomicLong(0)

    private fun statLine(): String {
        val n = statReads.getAndIncrement()
        return "cpu  ${1000 + 200 * n} 0 0 ${2000 + 100 * n} 0 0 0 0 0 0\ncpu0 1 0 0 1 0 0 0 0 0 0"
    }

    /** flips 段合成输出：每次读取翻 30 帧（与 [flipsReads] 序号联动） */
    private fun sfSection(): String = "SurfaceFlinger global state:\nflips=${1000 + 30 * flipsReads.getAndIncrement()}\n"

    private val memSection = "MemTotal:       13797032 kB\nMemAvailable:    6521736 kB\nMemFree:         3965080 kB\n"

    private val thermalSection = """
        Current temperatures from HAL:
        Temperature{mValue=30.8, mType=3, mName=skin, mStatus=0}
    """.trimIndent() + "\n"

    private val topSection = """
        Tasks: 3 total
          PID USER      PR  NI VIRT RES SHR S[%CPU] %MEM TIME+ ARGS
        100 system      20   0 7.1G 266M 136M S 61.2   1.9 170:55.54 com.demo.app
    """.trimIndent() + "\n"

    /** 正常路由：组合段电池服务缺席（设备常电形态）→ 回退链 dumpsys power 取数 */
    private val runner = FakeGateway { command ->
        when {
            command.contains("dumpsys power") -> ShellResult(0, "mBatteryLevel=100\nmWakefulness=Awake\n", "")
            command.contains(PerfParsers.MARK_MEM) -> ShellResult(
                0,
                statLine() + "\n" + PerfParsers.MARK_MEM + "\n" + memSection +
                    PerfParsers.MARK_THERMAL + "\n" + thermalSection +
                    PerfParsers.MARK_BATTERY + "\nCan't find service: battery\n" +
                    PerfParsers.MARK_TOP + "\n" + topSection +
                    PerfParsers.MARK_SF + "\n" + sfSection(),
                "",
            )

            // 差值基线拍：CPU 计数基线 + flips 基线（同拍组合拍各读一次 flips）
            command.contains(PerfParsers.BASELINE_PAYLOAD) -> ShellResult(
                0,
                statLine() + "\n" + PerfParsers.MARK_SF + "\n" + sfSection(),
                "",
            )

            else -> ShellResult(0, "", "")
        }
    }

    private val sampleIntervalMs = 100L
    private val cpuWindowMs = 10L

    private fun sampler(
        runner: AdbProcessRunner = this.runner,
        initialPageVisible: Boolean = true,
    ): PerfSampler = PerfSampler(
        scope = scope,
        devices = devices,
        runner = runner,
        endpointReader = { AdbEndpoint("") },
        // 时钟步进 500ms：sampleOnce 的 fps 差值窗口 = 窗口起止两次读表间隔 500ms，
        // 配合 sfSection 的 Δflips=30 → fps 恒 60.0（确定性断言锚）
        clock = { clockTicks.addAndGet(500) },
        sampleIntervalMs = sampleIntervalMs,
        cpuWindowMs = cpuWindowMs,
        initialPageVisible = initialPageVisible,
    )

    /** 推进并结算恰好一个采样拍（结算后停在下一拍 CPU 窗口 delay 处） */
    private fun pumpSample() {
        dispatcher.scheduler.advanceTimeBy(cpuWindowMs)
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(sampleIntervalMs)
        dispatcher.scheduler.runCurrent()
    }

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

    // ── 单拍解析与通道纪律 ─────────────────────────────────────────────

    @Test
    fun `sampleOnce parses six domains with battery fallback chain and flips delta fps`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val sampler = sampler()
        val sample = sampler.sampleOnce("S1", adbPath = "")

        // CPU 差值：Δtotal=300、Δidle=100 → 200/300
        assertEquals(200f / 300f * 100f, sample.cpuPercent.value!!, 0.001f)
        assertEquals(13_797_032L, sample.memory.value!!.totalKb)
        assertEquals(30.8f, sample.temperatureC.value!!)
        // 电池主链缺席 → 回退链 dumpsys power 取到 level
        assertEquals(100, sample.batteryPercent.value)
        val procs = sample.processes.value!!
        assertEquals(1, procs.size)
        assertEquals("com.demo.app", procs[0].name)
        // FPS 差值：Δflips=30 / 实际间隔 500ms → 60 fps（全屏合成帧率口径）
        assertEquals(60f, sample.fps.value!!, 0.001f)

        // 通道纪律：命令注入 -s serial + payload 按 shell 语义包裹（Unix 单引号）
        assertTrue(
            runner.commands.any { it == "adb -s S1 shell '${PerfParsers.BASELINE_PAYLOAD}'" },
            "差值基线命令须为 adb -s S1 shell '<BASELINE_PAYLOAD>' 整体包裹" +
                "（实际：${runner.commands}）",
        )
        assertTrue(
            runner.commands.any { it.contains("adb -s S1 shell '") && it.contains(PerfParsers.MARK_MEM) },
            "组合命令须注入 serial 且 payload 整体包裹（实际：${runner.commands}）",
        )
        assertTrue(
            runner.commands.any { it == "adb -s S1 shell 'dumpsys power'" },
            "电池回退链命令须独立 exec（实际：${runner.commands}）",
        )
    }

    @Test
    fun `sampleOnce fails every field explicitly on garbage output without throwing`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val garbage = FakeGateway { command ->
            when {
                command.contains("cat /proc/stat") -> ShellResult(0, "cpu  1 1 1 1 1 1 1 0 0 0\n", "")
                else -> ShellResult(0, "total garbage no markers\n", "")
            }
        }
        val sample = sampler(runner = garbage).sampleOnce("S1", adbPath = "")

        assertNull(sample.cpuPercent.value, "坏输出禁静默给值")
        assertNull(sample.memory.value)
        assertNull(sample.temperatureC.value)
        assertNull(sample.batteryPercent.value)
        assertNull(sample.processes.value)
        assertNull(sample.fps.value, "flips 缺席禁静默给 0 帧率")
        listOf(
            sample.cpuPercent.error,
            sample.memory.error,
            sample.temperatureC.error,
            sample.batteryPercent.error,
            sample.processes.error,
            sample.fps.error,
        ).forEach {
            assertTrue(it != null && it.isNotEmpty(), "坏输出须逐域显式失败原因（禁静默 0）")
        }
        assertTrue(sample.fps.error!!.contains("flips"), "fps 失败原因须指向 flips 采样点（实际：${sample.fps.error}）")
    }

    @Test
    fun `sampleOnce surfaces explicit fps error when flips counter regresses`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        // 基线拍 flips=2000、组合拍 flips=1000：计数回退须显式落 fps 域 error，
        // 不得给负值/假值，也不拖垮其余域
        val regress = FakeGateway { command ->
            when {
                command.contains(PerfParsers.MARK_MEM) -> ShellResult(
                    0,
                    statLine() + "\n" + PerfParsers.MARK_MEM + "\n" + memSection +
                        PerfParsers.MARK_THERMAL + "\n" + thermalSection +
                        PerfParsers.MARK_BATTERY + "\nCan't find service: battery\n" +
                        PerfParsers.MARK_TOP + "\n" + topSection +
                        PerfParsers.MARK_SF + "\nSurfaceFlinger global state:\nflips=1000\n",
                    "",
                )

                command.contains(PerfParsers.BASELINE_PAYLOAD) -> ShellResult(
                    0,
                    statLine() + "\n" + PerfParsers.MARK_SF + "\nSurfaceFlinger global state:\nflips=2000\n",
                    "",
                )

                else -> ShellResult(0, "", "")
            }
        }
        val sample = sampler(runner = regress).sampleOnce("S1", adbPath = "")

        assertNull(sample.fps.value, "flips 计数回退禁给值")
        assertTrue(sample.fps.error!!.contains("回退"), "回退须人话显式失败（实际：${sample.fps.error}）")
        assertEquals(30.8f, sample.temperatureC.value!!, "fps 域失败不拖垮其余域")
    }

    @Test
    fun `sampleOnce fails whole tick on non-zero adb exit code`() = runTest(dispatcher.scheduler) {
        devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
        val offline = FakeGateway { ShellResult(1, "", "error: device offline") }
        val e = kotlin.test.assertFailsWith<IllegalStateException> {
            sampler(runner = offline).sampleOnce("S1", adbPath = "")
        }
        assertTrue(e.message!!.contains("退出码 1"), "非零退出码须整拍显式失败（实际：${e.message}）")
    }

    // ── 调度环：间隔 / 门控 / 会话 ─────────────────────────────────────

    @Test
    fun `sampling loop produces one sample per interval and gates on visibility`() = runTest(dispatcher.scheduler) {try {

            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            val sampler = sampler()
            sampler.startSampling()
            dispatcher.scheduler.runCurrent() // 首拍基线落地，停在 CPU 窗口 delay

            pumpSample()
            assertEquals(1, sampler.state.value.history.points.size, "首拍立即采样（不等首间隔）")

            pumpSample()
            pumpSample()
            assertEquals(3, sampler.state.value.history.points.size, "每间隔恰好一拍")

            // 不可见门控：已起拍的拍允许完成，此后静默跳过（零 exec），会话保持
            sampler.setPageVisible(false)
            pumpSample()
            assertEquals(4, sampler.state.value.history.points.size, "门控只拦下一拍，在途拍允许完成")

            val gatedCallCount = runner.commands.size
            repeat(3) { pumpSample() }
            assertEquals(4, sampler.state.value.history.points.size, "页面不可见期间不得采样")
            assertEquals(gatedCallCount, runner.commands.size, "门控静默跳过 = 零 exec")
            assertIs<PerfSessionState.Active>(sampler.state.value.session)

            // 回页恢复
            sampler.setPageVisible(true)
            pumpSample()
            assertEquals(5, sampler.state.value.history.points.size, "回页下一拍恢复采样")
            val first = sampler.state.value.history.points.first()
            val last = sampler.state.value.history.points.last()
            assertTrue(last.timestampMs > first.timestampMs)
            assertEquals(200f / 300f * 100f, last.cpuPercent!!, 0.001f, "曲线点取自采样域值")
            assertEquals(60f, last.fps!!, 0.001f, "fps 曲线点取自采样域值（flips 差值换算）")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `startSampling without target device stays idle and spawns nothing`() = runTest(dispatcher.scheduler) {
        // 无设备 / 检测失败 / 多设备未选定三形态都不得启动
        val sampler = sampler()
        sampler.startSampling()
        pumpSample()
        assertIs<PerfSessionState.Idle>(sampler.state.value.session)

        devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S1", "A"), DeviceInfo("S2", "B")))
        sampler.startSampling()
        pumpSample()
        assertIs<PerfSessionState.Idle>(sampler.state.value.session, "多设备未选定不得启动")

        devices.detectError.value = "设备检测失败"
        devices.selectedSerial.value = "S1"
        sampler.startSampling()
        pumpSample()
        assertIs<PerfSessionState.Idle>(sampler.state.value.session, "检测失败态不得启动")

        assertTrue(runner.commands.isEmpty(), "未启动会话即零 exec")
    }

    @Test
    fun `multi selection starts session and retargets on selection switch`() = runTest(dispatcher.scheduler) {try {

            devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S1", "A"), DeviceInfo("S2", "B")))
            val sampler = sampler()
            devices.selectedSerial.value = "S2"
            sampler.startSampling()
            dispatcher.scheduler.runCurrent()
            pumpSample()
            assertEquals("S2", assertIs<PerfSessionState.Active>(sampler.state.value.session).serial)

            devices.selectedSerial.value = "S1"
            pumpSample() // 在途拍以旧 serial 完成
            pumpSample() // 下一拍起 retarget 生效
            assertEquals("S1", assertIs<PerfSessionState.Active>(sampler.state.value.session).serial, "换选无缝 retarget")
            assertTrue(
                runner.commands.any { it.contains("-s S1") },
                "retarget 后命令须注入新 serial（实际：${runner.commands.takeLast(3)}）",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `device disappearance ends session with human readable reason`() = runTest(dispatcher.scheduler) {try {

            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            val sampler = sampler()
            sampler.startSampling()
            dispatcher.scheduler.runCurrent()
            pumpSample()
            assertEquals(1, sampler.state.value.history.points.size)

            devices.deviceState.value = DeviceState.None
            pumpSample()
            val session = sampler.state.value.session
            assertTrue(
                session is PerfSessionState.Ended && session.reason.contains("断开"),
                "设备消失须收会话带人话 reason（实际：$session）",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `consecutive whole-tick failures end session but field errors do not`() = runTest(dispatcher.scheduler) {try {

            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            val flaky = FakeGateway { ShellResult(1, "", "error: device offline") }
            val sampler = sampler(runner = flaky)
            sampler.startSampling()
            dispatcher.scheduler.runCurrent() // 第 1 次整拍失败
            repeat(PerfSampler.MAX_CONSECUTIVE_FAILURES - 2) { pumpSample() } // 累计到阈值 −1
            assertIs<PerfSessionState.Active>(sampler.state.value.session, "失败未达阈值会话保持（瞬时抖动容差）")
            assertTrue(sampler.state.value.tickError!!.contains("device offline"))

            pumpSample() // 达到阈值
            val session = sampler.state.value.session
            assertTrue(
                session is PerfSessionState.Ended && session.reason.contains("连续"),
                "连续整拍失败须收会话（实际：$session）",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `repeat startSampling while active is idempotent`() = runTest(dispatcher.scheduler) {try {

            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            val sampler = sampler()
            sampler.startSampling()
            dispatcher.scheduler.runCurrent()
            sampler.startSampling() // 会话中重复启动须幂等（不得双环双速采样）
            pumpSample()
            assertEquals(1, sampler.state.value.history.points.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stopSampling returns to idle and keeps history`() = runTest(dispatcher.scheduler) {try {

            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            val sampler = sampler()
            sampler.startSampling()
            dispatcher.scheduler.runCurrent()
            pumpSample()
            val before = sampler.state.value.history.points.size
            assertTrue(before > 0)

            sampler.stopSampling()
            assertIs<PerfSessionState.Idle>(sampler.state.value.session)
            assertEquals(before, sampler.state.value.history.points.size, "用户停止保留历史（跨会话续存）")

            // 停止后不再采样
            repeat(2) { pumpSample() }
            assertEquals(before, sampler.state.value.history.points.size)
        } finally {
            scope.cancel()
        }
    }

    // ── 历史环形缓冲 ───────────────────────────────────────────────────

    @Test
    fun `history ring drops oldest beyond window capacity`() = runTest(dispatcher.scheduler) {
        // 间隔 60s → 容量 = 30 分钟 / 60s = 30 点；applySample 注入缝直接驱动入环
        val sampler = PerfSampler(
            scope = scope,
            devices = devices,
            runner = runner,
            endpointReader = { AdbEndpoint("") },
            clock = { clockTicks.incrementAndGet() },
            sampleIntervalMs = 60_000L,
            cpuWindowMs = 10L,
        )
        val okCpu = PerfField.ok(50f)
        val okMem = PerfField.ok(MemoryStats(1000, 400, 100))
        repeat(35) { n ->
            sampler.applySample(
                DevicePerfSample(
                    timestampMs = n * 60_000L,
                    cpuPercent = okCpu,
                    memory = okMem,
                    temperatureC = PerfField.ok(30f),
                    batteryPercent = PerfField.ok(100),
                    processes = PerfField.ok(emptyList()),
                    fps = PerfField.ok(60f),
                ),
            )
        }
        val snapshot = sampler.state.value.history
        assertEquals(30, snapshot.capacity, "容量 = 30 分钟窗 / 采样间隔")
        assertEquals(30, snapshot.points.size, "满环后追加丢最旧")
        assertEquals(5L * 60_000L, snapshot.points.first().timestampMs, "最旧 5 点被滚出")
        assertEquals(34L * 60_000L, snapshot.points.last().timestampMs)
        assertTrue(snapshot.overflowed, "滚出须显式置 overflowed（消费面明示 30 分钟档截断）")

        sampler.clearHistory()
        assertTrue(sampler.state.value.history.points.isEmpty())
        assertEquals(30, sampler.state.value.history.capacity, "清空不清容量档")
    }

    @Test
    fun `failed domains leave null holes in history points instead of fake zero`() = runTest(dispatcher.scheduler) {
        val sampler = PerfSampler(
            scope = scope,
            devices = devices,
            runner = runner,
            endpointReader = { AdbEndpoint("") },
            clock = { clockTicks.incrementAndGet() },
            sampleIntervalMs = 60_000L,
        )
        sampler.applySample(
            DevicePerfSample(
                timestampMs = 0,
                cpuPercent = PerfField.fail("坏输出"),
                memory = PerfField.ok(MemoryStats(1000, 400, 100)),
                temperatureC = PerfField.ok(30f),
                batteryPercent = PerfField.ok(100),
                processes = PerfField.ok(emptyList()),
                fps = PerfField.fail("未见 flips 行"),
            ),
        )
        val point = sampler.state.value.history.points.single()
        assertNull(point.cpuPercent, "曲线点在失败拍留洞（折线断段），禁伪装成 0")
        assertEquals(60f, point.memUsedPercent)
        assertNull(point.fps, "fps 失败拍同样留洞，禁伪装成 0 帧率")
    }

    // ── 多会话竞态族：停 A 不影响 B / 旧会话不误杀新会话 / 迟到样本不复活 ────

    @Test
    fun `batch sessions sample independently and stopping one leaves the other running`() = runTest(dispatcher.scheduler) {
        try {
            devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("A", "a"), DeviceInfo("B", "b")))
            val sampler = sampler()
            sampler.startSamplingToTargets(listOf("A", "B"))
            dispatcher.scheduler.runCurrent() // 两环首拍基线各自落地
            pumpSample()
            assertEquals(
                setOf("A", "B"),
                sampler.state.value.sessions.keys,
                "批量目标集须逐 serial 各起一个会话（实际：${sampler.state.value.sessions.keys}）",
            )
            assertEquals(1, sampler.state.value.historyBySerial["A"]!!.points.size)
            assertEquals(1, sampler.state.value.historyBySerial["B"]!!.points.size)

            // 单台停止：只收 A 的会话，B 不受任何影响（环、会话态照常生长）
            val commandMark = runner.commands.size
            sampler.stopSampling("A")
            assertNull(sampler.state.value.sessions["A"], "用户停止的 serial 移出会话表（Idle）")
            assertIs<PerfSessionState.Active>(sampler.state.value.sessions["B"])
            val aFrozen = sampler.state.value.historyBySerial["A"]!!.points.size

            pumpSample()
            pumpSample()
            assertEquals(aFrozen, sampler.state.value.historyBySerial["A"]!!.points.size, "已停会话曲线冻结")
            assertTrue(
                sampler.state.value.historyBySerial["B"]!!.points.size > 1,
                "未停会话曲线照常生长（停 A 不得影响 B）",
            )
            assertIs<PerfSessionState.Active>(sampler.state.value.sessions["B"])
            val afterStop = runner.commands.drop(commandMark)
            assertTrue(
                afterStop.isNotEmpty() && afterStop.all { !it.contains("-s A") },
                "停止后不得再对已停 serial spawn（实际：${afterStop.takeLast(3)}）",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `batch session ends only its own serial when that device disappears`() = runTest(dispatcher.scheduler) {
        try {
            devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("A", "a"), DeviceInfo("B", "b")))
            val sampler = sampler()
            sampler.startSamplingToTargets(listOf("A", "B"))
            dispatcher.scheduler.runCurrent()
            pumpSample()

            devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("B", "b")))
            pumpSample() // 在途拍以旧设备态完成；下一拍起 A 会话在场检查失败收束
            pumpSample() // B 继续生长，A 冻结
            val aSession = sampler.state.value.sessions["A"]
            assertTrue(
                aSession is PerfSessionState.Ended && aSession.reason.contains("断开"),
                "消失台只收自己的会话带人话 reason（实际：$aSession）",
            )
            assertIs<PerfSessionState.Active>(sampler.state.value.sessions["B"])
            assertTrue(
                sampler.state.value.historyBySerial["B"]!!.points.size >
                    sampler.state.value.historyBySerial["A"]!!.points.size,
                "B 会话不受 A 收束影响继续采样",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stopped session late tick cannot resurrect or clobber its replacement`() = runTest(dispatcher.scheduler) {
        try {
            devices.deviceState.value = DeviceState.Single("S1", "Demo-1")
            // 首条基线命令挂起在测试控制的闸上：构造「用户停止落在在途拍中间」的交错
            val enteredBaseline = CompletableDeferred<Unit>()
            val releaseBaseline = CompletableDeferred<Unit>()
            val suspendedOnce = AtomicBoolean(false)
            val gated = object : AdbProcessRunner {
                override suspend fun execute(command: String): Result<ShellResult> {
                    if (!suspendedOnce.getAndSet(true)) {
                        enteredBaseline.complete(Unit)
                        releaseBaseline.await()
                    }
                    return runner.execute(command)
                }
            }
            val sampler = sampler(runner = gated)
            sampler.startSampling()
            dispatcher.scheduler.runCurrent()
            assertTrue(enteredBaseline.isCompleted, "首拍基线须已进入挂起闸（交错就位）")

            // 停止旧会话后立即按同 serial 起新会话（新世代替换旧世代）
            sampler.stopSampling()
            sampler.startSampling()
            assertIs<PerfSessionState.Active>(sampler.state.value.session)

            // 旧会话在途拍醒来：整笔写入经世代闸丢弃（不写环、不落 tickError、不复活）
            releaseBaseline.complete(Unit)
            dispatcher.scheduler.runCurrent()
            assertNull(sampler.state.value.tickErrorBySerial["S1"], "旧会话迟到回执不得落表")
            assertIs<PerfSessionState.Active>(sampler.state.value.session, "迟到样本不得改变会话态")

            pumpSample() // 新会话首拍完成
            assertEquals(
                1,
                sampler.state.value.historyBySerial["S1"]!!.points.size,
                "曲线只含新会话的点（旧世代在途拍整笔丢弃，实际：" +
                    "${sampler.state.value.historyBySerial["S1"]!!.points.size}）",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `default session migration yields to an existing session on the new target`() = runTest(dispatcher.scheduler) {
        try {
            devices.deviceState.value = DeviceState.Multi(listOf(DeviceInfo("S1", "A"), DeviceInfo("S2", "B")))
            val sampler = sampler()
            sampler.startSamplingToTargets(listOf("S1")) // S1 批量会话（不跟随选定）
            devices.selectedSerial.value = "S2"
            sampler.startSampling() // 默认会话起在 S2（跟随选定）
            dispatcher.scheduler.runCurrent()
            pumpSample()
            assertEquals(setOf("S1", "S2"), sampler.state.value.sessions.keys)

            // 换选 S1：默认会话迁移时发现新目标已有活跃会话——只收自己，不顶替不重复起环
            devices.selectedSerial.value = "S1"
            pumpSample() // 默认会话下一拍检测到换选并迁移
            pumpSample()
            assertNull(sampler.state.value.sessions["S2"], "迁移的旧会话让位收束")
            assertIs<PerfSessionState.Active>(sampler.state.value.sessions["S1"], "S1 既有会话不得被误杀")
            assertEquals(
                setOf("S1"),
                sampler.state.value.sessions.keys,
                "迁移不得在已有活跃会话的 serial 上重复起环（实际：${sampler.state.value.sessions.keys}）",
            )
            val s1Points = sampler.state.value.historyBySerial["S1"]!!.points.size
            pumpSample()
            assertTrue(
                sampler.state.value.historyBySerial["S1"]!!.points.size > s1Points,
                "既有 S1 会话换选后照常采样",
            )
        } finally {
            scope.cancel()
        }
    }
}
