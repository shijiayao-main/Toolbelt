package com.jiaoay.easy_dev_toolbox.page.shelltools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 执行链路健壮性集成回归（design D5 / spec shell-execution-engine）：
 * - 看门狗超时触发 kill 后，僵死命令经升级链在有限时间判定失败，队列继续执行后续任务
 * - engine.stop() 后状态回到 Idle 且可立即提交新任务，旧任务收尾不覆盖状态
 */
class ShellExecutionEngineRobustnessTest {

    private lateinit var scope: CoroutineScope
    private lateinit var engine: ShellExecutionEngine

    @AfterTest
    fun tearDown() {
        if (::scope.isInitialized) {
            engine.stop()
            scope.cancel()
        }
    }

    private fun newEngine(): ShellExecutionEngine {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = ShellExecutionEngine(scope)
        return engine
    }

    @Test
    fun `watchdog timeout on unresponsive command does not block queue`() = runBlocking {
        val engine = newEngine()
        // 任务 A：忽略 SIGTERM 的僵死命令 + 1s 看门狗超时（kill → 升级链约 5s 内放行）
        engine.submit(
            QueuedCommand(label = "A", commands = listOf("trap '' TERM; sleep 300"), timeoutMs = 1_000)
        )
        // 任务 B：正常命令，须在 A 失败后正常执行
        engine.submit(
            QueuedCommand(label = "B", commands = listOf("echo done-b"), timeoutMs = 10_000)
        )

        withTimeout(60_000) {
            engine.status.first { it == ExecStatus.Completed }
        }

        val items = engine.output.value.items
        assertTrue(
            items.any { it.content.contains("已达超时上限") },
            "watchdog 超时应产生终止提示行: ${items.map { it.content }}",
        )
        assertTrue(
            items.any { it.content == "done-b" },
            "任务 B 应在 A 失败放行后正常执行: ${items.map { it.content }}",
        )
        Unit
    }

    @Test
    fun `shutdownNow kills running process and drains queue`() = runBlocking {
        val engine = newEngine()
        // 任务 A：忽略 SIGTERM 的僵死命令（kill → 升级链强杀，验证句柄确实被请求终止）
        engine.submit(
            QueuedCommand(label = "A", commands = listOf("trap '' TERM; sleep 300"), timeoutMs = 60_000)
        )
        // 任务 B：排队任务，shutdownNow 后不得再执行
        engine.submit(
            QueuedCommand(label = "B", commands = listOf("echo should-not-run"), timeoutMs = 10_000)
        )
        withTimeout(10_000) {
            engine.status.first { it is ExecStatus.Running }
        }
        delay(200)

        engine.shutdownNow()
        assertEquals(0, engine.queuedCount.value, "shutdownNow 应清空排队计数")
        // 僵死进程被 kill 的可观察证据：trap 忽略 SIGTERM、命令自身永不退出——
        // 任何 exitCode 行出现都只能来自升级链强杀（SIGKILL，Linux/macOS = 137）
        withTimeout(30_000) {
            engine.output.first { state ->
                state.items.any { it.content.startsWith("exitCode:") }
            }
        }
        delay(500)
        assertTrue(
            engine.output.value.items.none { it.content == "should-not-run" },
            "shutdownNow 后排队任务不得执行",
        )
        Unit
    }

    @Test
    fun `engine recovers after stop and accepts new task immediately`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(label = "long", commands = listOf("sleep 300"), timeoutMs = 60_000)
        )
        withTimeout(10_000) {
            engine.status.first { it is ExecStatus.Running }
        }
        delay(300) // 确保进程已启动

        engine.stop()
        // stop 状态置位即时：回到 Idle
        assertEquals(ExecStatus.Idle, engine.status.value)
        // "已手动停止"行经 stop 的异步冲刷在窗口内上屏
        withTimeout(2_000) {
            engine.output.first { state -> state.items.any { it.content == "已手动停止" } }
        }

        // stop 后可立即提交新任务并正常执行完成
        engine.submit(
            QueuedCommand(label = "after-stop", commands = listOf("echo recovered"), timeoutMs = 10_000)
        )
        withTimeout(30_000) {
            engine.status.first { it == ExecStatus.Completed }
        }
        assertTrue(
            engine.output.value.items.any { it.content == "recovered" },
            "stop 后新任务应正常执行: ${engine.output.value.items.map { it.content }}",
        )
        // 旧任务收尾不覆盖状态（manualStopRequested 语义）：最终状态为 Completed 而非被旧任务改写后残留异常
        assertEquals(ExecStatus.Completed, engine.status.value)
        Unit
    }
}
