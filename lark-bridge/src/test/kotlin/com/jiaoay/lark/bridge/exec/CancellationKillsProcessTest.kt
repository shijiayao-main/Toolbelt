package com.jiaoay.lark.bridge.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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
 * [方案 D9/R10 · 取消联动] CancellationKillsProcessTest：真实 `sleep 60` 子进程，外层协程取消后
 * launch 的 finally 在 [kotlinx.coroutines.NonCancellable] 内跑完 kill 升级链，5s 内 `isAlive == false`（无孤儿进程）。
 * 🔴 真实进程测 kill 语义（不用虚拟时间：Process.isAlive 是真实阻塞探测，虚拟时间会瞬间空转烧穿超时）。
 */
class CancellationKillsProcessTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("cancel-$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    @Test
    fun `取消外层协程后真实子进程 5s 内被杀（isAlive false）`() = runBlocking {
        val root = tempDir("cancel")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        val procRef = AtomicReference<Process?>(null)
        try {
            val lines = ProgramLauncher.lineChannel()
            // 子协程跑一个长命进程（exec 使 sh 被 sleep 取代，句柄即 sleep 本体，直接可杀）
            val job = launch(Dispatchers.IO) {
                launcher.launch(
                    ProgramSpec(command = listOf("/bin/sh", "-c", "exec sleep 60"), workingDir = root),
                    lines,
                    totalTimeoutMillis = 120_000,
                    idleTimeoutMillis = 120_000,
                    onStart = { procRef.set(it) },
                )
            }
            // 等子进程真正起来
            val started = withTimeoutOrNull(8_000) { while (procRef.get() == null) delay(20); true } ?: false
            assertTrue(started, "子进程应在 8s 内启动")
            val p = procRef.get()
            assertNotNull(p)
            assertTrue(p.isAlive, "取消前 sleep 60 应存活")

            // 取消 → launch 的 finally(NonCancellable) 跑 kill 升级链
            job.cancelAndJoin()
            lines.close()

            // 5s 内进程应已终止（cancelAndJoin 已等完 finally，此处再给宽限确认）
            val dead = withTimeoutOrNull(5_000) { while (p.isAlive) delay(50); true } ?: !p.isAlive
            assertTrue(dead, "取消后子进程必须在 5s 内 isAlive==false，不得泄漏孤儿进程")
        } finally {
            registry.close()
        }
    }
}
