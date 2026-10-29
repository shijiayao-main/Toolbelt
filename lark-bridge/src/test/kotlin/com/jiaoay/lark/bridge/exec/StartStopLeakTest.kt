package com.jiaoay.lark.bridge.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [方案 D9/R10 · TeardownCompleteness] StartStopLeakTest —— **进程侧断言**：任何启停路径（正常完成 / 取消 / 超时 / killAll）
 * 收尾后 `ProcessRegistry.liveCount == 0`，无孤儿子进程、无登记泄漏。
 * 🔴 线程侧断言（旧实现「每进程 2 条裸守护线程」的线程回收）归 M4-T9 门面装配点，本文件只覆盖进程侧。
 */
class StartStopLeakTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("leak-$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    @Test
    fun `多个进程正常完成后 liveCount 归零`() = runBlocking {
        val root = tempDir("normal")
        val registry = ProcessRegistry(maxConcurrent = 8)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        try {
            repeat(5) {
                val lines = ProgramLauncher.lineChannel()
                val reader = launch { for (l in lines) { /* drain */ } }
                val result = launcher.launch(
                    ProgramSpec(command = listOf("/bin/sh", "-c", "echo ok"), workingDir = root),
                    lines, totalTimeoutMillis = 15_000, idleTimeoutMillis = 10_000,
                )
                lines.close()
                withTimeoutOrNull(5_000) { reader.join() }
                assertEquals(0, result.exitCode)
            }
            assertEquals(0, registry.liveCount, "全部完成后不得残留登记")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `并发进程取消后 liveCount 归零（无孤儿）`() = runBlocking {
        val root = tempDir("cancel")
        val registry = ProcessRegistry(maxConcurrent = 8)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        try {
            val jobs = List(3) {
                launch(Dispatchers.IO) {
                    val lines = ProgramLauncher.lineChannel()
                    launcher.launch(
                        ProgramSpec(command = listOf("/bin/sh", "-c", "exec sleep 60"), workingDir = root),
                        lines, totalTimeoutMillis = 120_000, idleTimeoutMillis = 120_000,
                    )
                }
            }
            delay(800) // 让三个子进程都起来并登记
            assertTrue(registry.liveCount > 0, "启动后应有在途登记")
            jobs.forEach { it.cancelAndJoin() }
            val drained = withTimeoutOrNull(6_000) { while (registry.liveCount != 0) delay(50); true } ?: false
            assertTrue(drained, "取消后 liveCount 必须在宽限内归零")
            assertEquals(0, registry.liveCount)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `killAll 兜底清空在途进程（stop 语义）`() = runBlocking {
        val root = tempDir("killall")
        val registry = ProcessRegistry(maxConcurrent = 8)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        try {
            val jobs = List(2) {
                launch(Dispatchers.IO) {
                    val lines = ProgramLauncher.lineChannel()
                    launcher.launch(
                        ProgramSpec(command = listOf("/bin/sh", "-c", "exec sleep 60"), workingDir = root),
                        lines, totalTimeoutMillis = 120_000, idleTimeoutMillis = 120_000,
                    )
                }
            }
            delay(800)
            assertTrue(registry.liveCount > 0)
            registry.killAll() // 兜底强杀全部在途
            assertEquals(0, registry.liveCount, "killAll 后 liveCount 立即归零")
            jobs.forEach { it.cancelAndJoin() }
            assertEquals(0, registry.liveCount)
        } finally {
            registry.close()
        }
    }
}
