package com.jiaoay.easy_dev_toolbox.core.extensions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * kill 升级链回归（design D5）：destroy → 3s 宽限 → destroyForcibly → 2s → 放行。
 * 前置约束：升级收割以 killRequested 为前提——正常命令不受影响；
 * 忽略 SIGTERM 的僵死命令在 kill 后被强杀且 executeStreaming 有限时间返回。
 */
class StreamingShellKillTest {

    @Test
    fun `normal short command completes without kill path`() = runBlocking {
        val result = StreamingShell.executeStreaming(
            command = "echo ok",
            onLine = { },
        )
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().exitCode)
        Unit
    }

    @Test
    fun `unresponsive process is forcibly killed and executeStreaming returns in bounded time`() = runBlocking {
        val handleRef = AtomicReference<StreamingShellHandle?>(null)
        val started = CompletableDeferred<Unit>()

        // `trap '' TERM; sleep 300`：shell 忽略 SIGTERM，只有 SIGKILL 能杀掉
        val job = launch {
            StreamingShell.executeStreaming(
                command = "trap '' TERM; sleep 300",
                onStarted = {
                    handleRef.set(it)
                    started.complete(Unit)
                },
                onLine = { },
            )
        }

        withTimeout(10_000) { started.await() }
        // 确保 trap 已安装（shell 已进入 sleep）后再请求终止
        Thread.sleep(300)
        val handle = handleRef.get()
        assertTrue(handle != null, "onStarted 应交付 handle")
        handle.kill()
        assertEquals(true, handle.killRequested, "kill 应置位 killRequested")

        // 升级链总时长上界：200ms 轮询感知 + 3s 宽限 + 2s 强杀等待 + 1.5s drain 兜底
        val upperBoundMs = StreamingShell.HARVEST_POLL_MS +
            StreamingShell.TERMINATION_GRACE_MS +
            StreamingShell.FORCE_KILL_WAIT_MS +
            1500L + 2000L // STREAM_DRAIN_TIMEOUT_MS + 进程启动余量
        withTimeout(upperBoundMs) { job.join() }

        // 僵死进程经 SIGKILL 终止后收割循环应返回真实退出码（137），且不为放行值 -1
        // （若 SIGKILL 也失败则放行为 -1，两者均在有限时间内返回——上界已被 withTimeout 断言）
        Unit
    }

    /**
     * 🔴 2026-09-17 审计修复的回归用例：孙进程持有管道时 1.5s 排干兜底不被架空。
     *
     * 场景：`sh -c 'echo hi; sleep 300 &'` ——直接子进程（shell）立即退出，
     * 但后台孙进程（sleep 300）继承了 stdout 管道写端 ⇒ 管道无 EOF，读协程永不结束。
     * 修复前：withTimeoutOrNull 只放弃 join，coroutineScope 仍等读协程完成 ⇒ 永不返回。
     * 修复后：读协程在独立 scope，join 超时后 executeStreaming 正常返回真实 exitCode。
     */
    @Test
    fun `grandchild holding pipe does not hang executeStreaming`() = runBlocking {
        val handleRef = AtomicReference<StreamingShellHandle?>(null)
        val started = CompletableDeferred<Unit>()
        val lines = java.util.concurrent.ConcurrentLinkedQueue<String>()

        val job = launch {
            StreamingShell.executeStreaming(
                command = "echo hi; sleep 300 &",
                onStarted = {
                    handleRef.set(it)
                    started.complete(Unit)
                },
                onLine = { lines.add(it.line) },
            )
        }

        withTimeout(10_000) { started.await() }
        // shell（直接子进程）立即退出，孙进程 sleep 300 持有管道
        // 上界：进程退出即收割 + 1.5s drain 兜底 + 1s finally 收尾 + 余量
        val upperBoundMs = StreamingShell.STREAM_DRAIN_TIMEOUT_MS + 1_000L + 2_000L
        withTimeout(upperBoundMs) { job.join() }

        assertTrue(lines.contains("hi"), "应先收到 echo hi 的输出行：$lines")
        Unit
    }

    @Test
    fun `graceful process responds to sigterm within grace window`() = runBlocking {
        // sleep 响应 SIGTERM：kill 后应在宽限窗口内退出（非强杀路径）
        val handleRef = AtomicReference<StreamingShellHandle?>(null)
        val started = CompletableDeferred<Unit>()

        val job = launch {
            StreamingShell.executeStreaming(
                command = "sleep 300",
                onStarted = {
                    handleRef.set(it)
                    started.complete(Unit)
                },
                onLine = { },
            )
        }

        withTimeout(10_000) { started.await() }
        Thread.sleep(100)
        handleRef.get()!!.kill()

        // SIGTERM 生效路径：远小于宽限 + 强杀总时长
        withTimeout(StreamingShell.TERMINATION_GRACE_MS + 2000L) { job.join() }
        Unit
    }

    /**
     * onStarted 抛异常不得泄漏已 spawn 的进程：异常路径的 destroy 收尾回归。
     *
     * 判据（真短命进程验证收尾）：`sleep 1 && touch <marker>` 仅在 shell 存活满 1s
     * 后才落 marker——回调抛异常时 executeStreaming 必须及时 destroy（SIGTERM），
     * shell 在执行任何命令前死亡、marker 永不出现；若进程裸奔（无收尾），
     * 1s 后 marker 落盘即红。返回值契约：异常原样以 Failure 上抛。
     */
    @Test
    fun `onStarted throwing destroys spawned process instead of leaking it`() = runBlocking {
        val marker = File.createTempFile("streaming-shell-leak", ".mark").apply { delete() }
        val result = StreamingShell.executeStreaming(
            command = "sleep 1 && touch ${marker.absolutePath}",
            onStarted = { throw IllegalStateException("consumer callback boom") },
            onLine = { },
        )
        assertTrue(result.isFailure, "onStarted 抛异常应以 Failure 返回（实际：$result）")
        assertTrue(
            result.exceptionOrNull() is IllegalStateException,
            "回调异常须原样上抛（实际：${result.exceptionOrNull()}）",
        )
        // 宽限等待覆盖 shell 全程 1s sleep：marker 仍缺席 = 进程未活到 touch（已被 destroy 收尾）
        delay(2_000)
        assertFalse(marker.isFile, "onStarted 异常路径的进程必须被 destroy 收尾，不得裸奔落 marker")
        Unit
    }
}
