package com.jiaoay.lark.bridge.exec

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [方案 D9/R10 · BC §4.13] TimeoutKillsProcessTest：真实 `sleep 60` + 短总超时 ⇒ `timedOut == true`、`killed == true`、
 * 进程被杀（总超时 taskTimeoutSeconds 分支）。真实进程测 kill 语义，给足余量避免 flaky。
 */
class TimeoutKillsProcessTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("timeout-$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    @Test
    fun `总超时触发后子进程被杀（timedOut true, killed true, isAlive false）`() = runBlocking {
        val root = tempDir("total")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        val procRef = AtomicReference<Process?>(null)
        try {
            val lines = ProgramLauncher.lineChannel()
            val reader = launch { for (l in lines) { /* drain */ } }
            val result = launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "exec sleep 60"), workingDir = root),
                lines,
                totalTimeoutMillis = 800, // 远短于 sleep 60
                idleTimeoutMillis = 60_000, // 空闲看门狗刻意放宽，隔离总超时分支
                onStart = { procRef.set(it) },
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }

            assertTrue(result.timedOut, "总超时应置位 timedOut")
            assertTrue(result.killed, "总超时应置位 killed")
            assertTrue(!result.idleTimedOut, "本例应命中总超时而非空闲超时")
            val p = procRef.get()
            assertNotNull(p)
            val dead = withTimeoutOrNull(5_000) { while (p.isAlive) delay(50); true } ?: !p.isAlive
            assertTrue(dead, "总超时后子进程必须被杀")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `超时被杀后登记表清空（无泄漏）`() = runBlocking {
        val root = tempDir("total2")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        try {
            val lines: Channel<AgentLine> = ProgramLauncher.lineChannel()
            val reader = launch { for (l in lines) { /* drain */ } }
            launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "exec sleep 30"), workingDir = root),
                lines, totalTimeoutMillis = 500, idleTimeoutMillis = 60_000,
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }
            assertTrue(registry.liveCount == 0, "超时收尾后 liveCount 应回 0")
        } finally {
            registry.close()
        }
    }
}
