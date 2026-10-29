package com.jiaoay.easy_dev_toolbox.core.extensions

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 取消契约回归：协程取消原样上抛、不并入 Result.Failure——Failure 仅承载进程级
 * 真失败；取消误读为「命令失败」会错误触发调用方的失败分支（错误回执/fallback 链）。
 */
class ShellCancellationTest {

    @Test
    fun `execute rethrows mid-flight cancellation instead of packing into failure`() = runBlocking {
        var rethrown = false
        val job = launch {
            try {
                SimpleShell.execute("sleep", "2")
            } catch (e: CancellationException) {
                rethrown = true
            }
        }
        // 命令已 spawn（waitFor 阻塞不响应取消），取消落在执行中段；
        // 极端慢机下取消早于 spawn 时同样在 withContext 入口上抛——断言两侧一致
        delay(500)
        job.cancel()
        job.join()
        assertTrue(rethrown, "取消应原样上抛，不得并入 Failure")
    }

    @Test
    fun `executeStreaming rethrows mid-flight cancellation instead of packing into failure`() = runBlocking {
        var rethrown = false
        var handle: StreamingShellHandle? = null
        val job = launch {
            try {
                StreamingShell.executeStreaming(
                    command = "sleep 30",
                    onStarted = { handle = it },
                    onLine = {},
                )
            } catch (e: CancellationException) {
                rethrown = true
            }
        }
        withTimeout(10_000) { while (handle == null) delay(20) }
        delay(100) // 收割循环已进入轮询，取消落在执行中段
        job.cancel()
        // 解除收割阻塞（不 kill 则 sleep 30 跑满才到达首个挂起点）
        handle?.kill()
        job.join()
        assertTrue(rethrown, "取消应原样上抛，不得并入 Failure")
    }

    @Test
    fun `execute result parity - success with output`() = runBlocking {
        val result = SimpleShell.execute("printf", "ok")
        assertTrue(result.isSuccess, "命令应执行成功: ${result.exceptionOrNull()?.message}")
        val shell = result.getOrThrow()
        assertEquals(0, shell.exitCode)
        assertEquals("ok", shell.stdout.trim())
    }

    @Test
    fun `execute result parity - nonzero exit code is Success not Failure`() = runBlocking {
        val result = SimpleShell.execute("exit", "7")
        assertTrue(result.isSuccess, "非零退出码是 Success（进程级失败才是 Failure）")
        assertEquals(7, result.getOrThrow().exitCode)
    }
}
