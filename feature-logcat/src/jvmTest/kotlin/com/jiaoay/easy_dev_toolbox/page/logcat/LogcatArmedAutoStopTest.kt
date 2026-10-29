package com.jiaoay.easy_dev_toolbox.page.logcat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 武装延迟自动停止回归（虚拟时间——引擎协程挂共享测试调度器的前台域，
 * 倒计时以虚拟时间推进不真等。🔴 不得用 runTest 的 backgroundScope 挂引擎：
 * 背景域事件被 advanceUntilIdle 排除（TestCoroutineScheduler.advanceUntilIdle 只跑
 * 前台事件），引擎协程会整批不被推进）：
 * - 到点自动停止：终态落定 + 产物句柄关闭 + 回执人话区分（「武装延迟到点」）；
 * - 三路取消零迟到停止：disarm / 手动停止 / 流终止——迟到的倒计时不得触达
 *   下一轮抓取（可观测断言 = 第二轮抓取跑过旧到点仍 Running）；
 * - 再武装以新延迟档覆盖旧倒计时；命中不取消倒计时（倒计时与命中两语义独立，
 *   命中只管标记收网）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogcatArmedAutoStopTest {

    /**
     * fake 运行器：发射预置行后阻塞至 stop；stop 先于 run 到达亦正确放行
     * （Running 态在捕获协程首跑前置位的窗口）。[reset] 供同一引擎连跑两轮抓取。
     */
    private class AutoStopFakeRunner : LogcatStreamRunner {
        var programmed: List<String> = emptyList()
        var blockAfterEmit = true

        @Volatile private var stopRequested = false
        private var stopSignal = CompletableDeferred<Unit>()

        override suspend fun run(command: String, onLine: suspend (String) -> Unit): Int? {
            programmed.forEach { onLine(it) }
            if (blockAfterEmit && !stopRequested) stopSignal.await()
            return 0
        }

        override fun stop() {
            stopRequested = true
            stopSignal.complete(Unit)
        }

        fun reset() {
            stopRequested = false
            stopSignal = CompletableDeferred()
        }
    }

    /**
     * 测试驱动骨架：引擎挂共享测试调度器的**前台** StandardTestDispatcher 域
     * （delay 虚拟化 + advanceUntilIdle 可推进），收尾兜底停止并撤销域。
     */
    private fun runEngineTest(block: suspend TestScope.(AutoStopFakeRunner, LogcatCaptureEngine) -> Unit) = runTest {
        val runner = AutoStopFakeRunner()
        val engineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val engine = LogcatCaptureEngine(engineScope, runner)
        try {
            block(runner, engine)
        } finally {
            engine.stop()
            engineScope.cancel()
        }
    }

    private val matcher = LogcatLineMatcher(LogcatFilterSpec("", null))

    @Test
    fun `armed delay auto-stops capture with terminal state closed handle and dedicated receipt`() = runEngineTest { runner, engine ->
        val out = tempOutFile()
        runner.programmed = listOf(
            "10-11 12:00:00.001  100  200 I Tag: line-1",
            "10-11 12:00:00.002  100  200 E Crash: FATAL EXCEPTION",
        )
        assertTrue(engine.start("adb logcat", out, matcher))
        assertTrue(engine.arm(Regex("FATAL"), autoStopDelaySeconds = 3), "Running 态带延迟武装须生效")

        // 虚拟时间推进过 3s 到点：自动停止 → 流终止收尾 → 终态落定（句柄已关闭）
        advanceUntilIdle()

        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value, "到点自动停止终态须落定")
        assertEquals(2L, stopped.totalCount, "到点前已发行全量落盘")
        assertTrue("自动停止" in stopped.message, "自动停止回执人话区分（防误读为手动停止）：${stopped.message}")
        assertEquals(runner.programmed, out.contentLines(), "落盘行序与流序一致")
        assertNoOpenFd(out, "自动停止终态落定后产物句柄必须已关闭")
        assertFalse(engine.armed.value, "终态撤销武装（延迟倒计时随终态收口）")
    }

    @Test
    fun `disarm cancels pending auto-stop countdown`() = runEngineTest { runner, engine ->
        val out = tempOutFile()
        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: only")
        assertTrue(engine.start("adb logcat", out, matcher))
        assertTrue(engine.arm(Regex("FATAL"), autoStopDelaySeconds = 3))

        engine.disarm()
        // 推进远超到点：倒计时已被解除武装连带撤销，抓取保持 Running 零迟到停止
        advanceTimeBy(60_000)
        runCurrent()
        assertIs<LogcatCaptureStatus.Running>(engine.status.value, "解除武装取消倒计时——到点无迟到停止")

        engine.stop()
        advanceUntilIdle()
        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertTrue("自动停止" !in stopped.message, "手动停止收尾不得冒用自动停止回执")
    }

    @Test
    fun `manual stop cancels pending auto-stop so late fire never reaches next capture`() = runEngineTest { runner, engine ->
        val out1 = tempOutFile()
        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: first")
        assertTrue(engine.start("adb logcat", out1, matcher))
        assertTrue(engine.arm(Regex("FATAL"), autoStopDelaySeconds = 3))

        // 到点前手动停止（人工停止即终局）
        engine.stop()
        advanceUntilIdle()
        val first = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
        assertTrue("自动停止" !in first.message, "手动停止回执不得冒用自动停止口径")

        // 第二轮抓取跑过旧到点：迟到的倒计时若未被取消会在旧到点误停新一轮抓取
        runner.reset()
        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: second")
        val out2 = tempOutFile()
        assertTrue(engine.start("adb logcat", out2, matcher))
        advanceUntilIdle()
        assertIs<LogcatCaptureStatus.Running>(engine.status.value, "迟到的自动停止不得触达下一轮抓取（零迟到停止）")

        engine.stop()
        advanceUntilIdle()
        assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
    }

    @Test
    fun `natural stream termination cancels pending auto-stop so late fire never reaches next capture`() = runEngineTest { runner, engine ->
        val out1 = tempOutFile()
        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: short-lived")
        runner.blockAfterEmit = false // 流自然终止（进程自行退出）
        assertTrue(engine.start("adb logcat", out1, matcher))
        assertTrue(engine.arm(Regex("FATAL"), autoStopDelaySeconds = 3))

        advanceUntilIdle() // 流自然终止 → 终态收尾连带取消倒计时
        val first = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value, "自然终止终态落定")
        assertTrue("自动停止" !in first.message)

        // 第二轮抓取跑过旧到点：流终止取消路的迟到防线
        runner.reset()
        runner.blockAfterEmit = true
        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: second")
        val out2 = tempOutFile()
        assertTrue(engine.start("adb logcat", out2, matcher))
        advanceUntilIdle()
        assertIs<LogcatCaptureStatus.Running>(engine.status.value, "流终止取消倒计时——到点无迟到停止触达新一轮")

        engine.stop()
        advanceUntilIdle()
        assertIs<LogcatCaptureStatus.Stopped>(engine.status.value)
    }

    @Test
    fun `re-arm replaces old countdown with new delay tier`() = runEngineTest { runner, engine ->
        val out = tempOutFile()
        runner.programmed = listOf("10-11 12:00:00.001  100  200 I Tag: only")
        assertTrue(engine.start("adb logcat", out, matcher))
        assertTrue(engine.arm(Regex("A"), autoStopDelaySeconds = 300))
        assertTrue(engine.arm(Regex("B"), autoStopDelaySeconds = 60), "已武装时再武装以新正则 + 新延迟档覆盖")

        // 新档 60s 到点即停；旧档 300s 若仍存活会推迟到 300s 才停
        advanceTimeBy(60_000)
        runCurrent()
        val stopped = assertIs<LogcatCaptureStatus.Stopped>(
            engine.status.value,
            "倒计时以最新武装的延迟档为准（60s 到点落定，非旧档 300s）",
        )
        assertTrue("自动停止" in stopped.message)
    }

    @Test
    fun `armed hit marks anchor but does not cancel countdown`() = runEngineTest { runner, engine ->
        val out = tempOutFile()
        runner.programmed = listOf("10-11 12:00:00.001  100  200 E Crash: FATAL EXCEPTION")
        assertTrue(engine.start("adb logcat", out, matcher))
        assertTrue(engine.arm(Regex("FATAL"), autoStopDelaySeconds = 60))

        // 命中先于到点：锚点已标、埋伏已收网，倒计时照常走（两语义独立）
        runCurrent()
        assertNotNull(engine.anchor.value, "命中行自动标记锚点（延迟档不改变命中语义）")
        assertFalse(engine.armed.value, "命中即收网 one-shot 不变")
        assertIs<LogcatCaptureStatus.Running>(engine.status.value, "命中不提前停止——倒计时与命中两语义独立")

        advanceTimeBy(60_000)
        runCurrent()
        val stopped = assertIs<LogcatCaptureStatus.Stopped>(engine.status.value, "到点照常自动停止")
        assertTrue("自动停止" in stopped.message)
        assertNoOpenFd(out, "自动停止终态产物句柄关闭")
    }

    // ── 测试基建 ──────────────────────────────────────────────

    private fun tempOutFile(): File =
        Files.createTempDirectory("logcat-autostop-test").resolve("capture-${System.nanoTime()}.txt").toFile()

    /** 行内容读取：按平台行分隔符拆行，去掉末行终止符产生的尾部空元素 */
    private fun File.contentLines(): List<String> {
        val text = readText()
        if (text.isEmpty()) return emptyList()
        return text.split(System.lineSeparator()).dropLast(1)
    }
}

/** Linux 句柄泄漏检查：/proc/self/fd 下不得再有指向 [file] 的打开句柄（非 Linux 平台跳过） */
private fun assertNoOpenFd(file: File, message: String) {
    val fdDir = File("/proc/self/fd")
    if (!fdDir.isDirectory) return
    val target = file.absolutePath
    val leaked = fdDir.listFiles()
        .orEmpty()
        .mapNotNull { runCatching { it.canonicalFile.absolutePath }.getOrNull() }
        .any { it == target }
    assertTrue(!leaked, "$message：/proc/self/fd 仍持有 $target")
}
