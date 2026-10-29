package com.jiaoay.easy_dev_toolbox.page.shelltools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ShellToolsRuntime 单例与引擎 submit/stop 行为验收（task 4.2）。
 *
 * 引擎行为用独立实例验证（真实进程），避免污染全局单例状态；
 * 单例测试仅断言 scope 存活与 engine 引用稳定。
 */
class ShellToolsRuntimeTest {

    @Test
    fun `runtime singleton exposes alive scope and stable engine reference`() {
        assertTrue(ShellToolsRuntime.scope.isActive, "App 级 scope 应始终存活")
        assertSame(ShellToolsRuntime.engine, ShellToolsRuntime.engine, "engine 应为全局唯一实例")
    }

    /**
     * 🔴 2026-09-17 复核修复回归（§2.2·二次）：**退出落盘必须有确定性**。
     *
     * 修复前 VM `onCleared` 只是 `ShellToolsRuntime.scope.launch { flush() }`——投递即返回、无人等待，
     * 而该 scope 不是 AppHost 的根 scope（根 cancel 到不了）、flush 又跑在守护线程上，
     * 应用正常退出时 JVM 不等它 ⇒ 防抖窗口内关窗会**静默丢失**历史（旧的 runBlocking 版本反而有保证）。
     *
     * 修法 = `requestExitFlush` 登记 + `shutdown()` 同步 join。本用例断言：
     * `shutdown()` 返回时，登记的落盘任务**必然已完成**（不是「已投递」）。
     */
    @Test
    fun `shutdown 等待在途退出落盘完成`() {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        ShellToolsRuntime.requestExitFlush {
            Thread.sleep(300) // 模拟一次真实落盘耗时
            done.set(true)
        }
        ShellToolsRuntime.shutdown()
        assertTrue(
            done.get(),
            "🔴 shutdown() 返回时落盘必须已完成——否则应用退出会静默丢历史" +
                "（修复前是 fire-and-forget 投递，此断言必挂）",
        )
    }

    @Test
    fun `engine submits command and completes with output`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ShellExecutionEngine(scope)
        try {
            engine.submit(
                QueuedCommand(
                    label = "test-echo",
                    commands = listOf("echo runtime-ok"),
                    trackingKey = "rt-key",
                    timeoutMs = 10_000,
                )
            )
            val final = withTimeout(20_000) {
                engine.status.first { it is ExecStatus.Completed || it is ExecStatus.Failed }
            }
            assertIs<ExecStatus.Completed>(final, "echo 命令应正常完成: $final")
            val items = engine.output.value.items
            assertTrue(
                items.any { it.content.contains("runtime-ok") },
                "输出应包含命令 stdout: ${items.map { it.content }}",
            )
            assertEquals(emptySet(), engine.activeKeys.value, "完成后 trackingKey 应清除")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `engine stop interrupts long running command and resets state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ShellExecutionEngine(scope)
        try {
            engine.submit(
                QueuedCommand(
                    label = "test-sleep",
                    commands = listOf("sleep 30"),
                    trackingKey = "sleep-key",
                    timeoutMs = 300_000,
                )
            )
            withTimeout(10_000) {
                engine.status.first { it is ExecStatus.Running }
            }
            assertEquals(setOf("sleep-key"), engine.activeKeys.value)

            engine.stop()
            // stop() 同步重置状态与 tracking（不等被杀进程收尾）
            assertIs<ExecStatus.Idle>(engine.status.value, "stop 后状态应回 Idle")
            assertEquals(emptySet(), engine.activeKeys.value, "stop 后 activeKeys 应清空")
        } finally {
            scope.cancel()
        }
    }
}
