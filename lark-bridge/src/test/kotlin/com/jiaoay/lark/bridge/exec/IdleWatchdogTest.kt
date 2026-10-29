package com.jiaoay.lark.bridge.exec

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [方案 D9/R10 · BC §4.13] IdleWatchdogTest：空闲看门狗与总超时**相互独立**——
 * 输出停滞超 idleTimeout 即杀（idleTimedOut=true）；持续有输出则不误杀。
 * 🔴 空闲看门狗对应 agent 挂起无输出场景（与 taskTimeoutSeconds 总超时区分 errorCode）。
 * 真实进程测 kill 语义；idleTimeout 给足余量避免 flaky。
 */
class IdleWatchdogTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("idle-$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    @Test
    fun `输出停滞后空闲看门狗触发杀进程（idleTimedOut true）`() = runBlocking {
        val root = tempDir("idle")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        val procRef = AtomicReference<Process?>(null)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            val result = launcher.launch(
                // echo 一行后 exec sleep 60 静默：看门狗应在 idleTimeout 后杀之
                ProgramSpec(command = listOf("/bin/sh", "-c", "echo start; exec sleep 60"), workingDir = root),
                lines,
                totalTimeoutMillis = 60_000, // 总超时放宽，隔离空闲看门狗分支
                idleTimeoutMillis = 700,
                onStart = { procRef.set(it) },
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }

            assertTrue(result.idleTimedOut, "输出停滞应触发空闲看门狗 idleTimedOut")
            assertTrue(result.killed, "空闲超时应置位 killed")
            assertTrue(!result.timedOut, "本例应命中空闲超时而非总超时")
            assertEquals(listOf("start"), collected.filter { it.source == AgentLine.Source.STDOUT }.map { it.text })
            val p = procRef.get()
            assertNotNull(p)
            val dead = withTimeoutOrNull(5_000) { while (p.isAlive) delay(50); true } ?: !p.isAlive
            assertTrue(dead, "空闲超时后子进程必须被杀")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `持续有输出时不误杀（idleTimedOut false, 正常退出）`() = runBlocking {
        val root = tempDir("active")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            val result = launcher.launch(
                // 每 200ms 输出一行，共 4 行 ≈ 800ms，均 < idleTimeout=3000 → 不触发看门狗
                ProgramSpec(command = listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 4 ]; do echo tick\$i; i=\$((i+1)); sleep 0.2; done"), workingDir = root),
                lines,
                totalTimeoutMillis = 30_000,
                idleTimeoutMillis = 3_000,
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }

            assertEquals(0, result.exitCode)
            assertTrue(!result.idleTimedOut, "持续输出不应触发空闲看门狗")
            assertTrue(!result.timedOut)
            assertTrue(!result.killed)
            assertEquals(listOf("tick0", "tick1", "tick2", "tick3"), collected.filter { it.source == AgentLine.Source.STDOUT }.map { it.text })
        } finally {
            registry.close()
        }
    }

    // 🔴 P0-2 反证钉死：agentIdleTimeoutSeconds<=0 是「关闭看门狗」契约（Admission.idleMillis KDoc「<=0 ⇒ 关闭看门狗」
    //    + Admission 执行路径用 if(idleMillis>0L) 守护；行号历史锚 :53/:426 已因注释治理批漂移——以符号搜索定位）。
    //    ProgramLauncher 的独立看门狗曾无此守护：withTimeoutOrNull(0) 立即返回 null ⇒ idleTimedOut=true ⇒ killChain 对刚启动的进程发 SIGTERM。
    //    本例：idleTimeoutMillis=0 + 进程本可正常退出（sleep 1）⇒ 看门狗**不得启动**、进程**不得被杀**、idleTimedOut 保持 false、exitCode==0。
    @Test
    fun `idleTimeout 为零时关闭看门狗不杀进程（idleTimedOut false, 正常退出）`() = runBlocking {
        val root = tempDir("zero")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            val result = launcher.launch(
                // 进程输出一行后 sleep 1 再自然退出：若看门狗被错误启动，idleTimeout=0 会在启动瞬间杀之
                ProgramSpec(command = listOf("/bin/sh", "-c", "echo start; sleep 1"), workingDir = root),
                lines,
                totalTimeoutMillis = 30_000, // 总超时放宽，隔离空闲看门狗分支
                idleTimeoutMillis = 0,       // 🔴 0 = 关闭看门狗（TimeoutPolicy 语义），绝非「立即超时」
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }

            assertTrue(!result.idleTimedOut, "idleTimeoutMillis=0 必须关闭看门狗，不得置位 idleTimedOut（否则刚启动即被杀）")
            assertTrue(!result.killed, "idleTimeoutMillis=0 不得杀进程")
            assertTrue(!result.timedOut, "本例不应命中总超时")
            assertEquals(0, result.exitCode, "看门狗关闭 ⇒ 进程应自然退出 exitCode=0，而非被 SIGTERM 杀掉")
            assertEquals(listOf("start"), collected.filter { it.source == AgentLine.Source.STDOUT }.map { it.text })
        } finally {
            registry.close()
        }
    }

    // 🔴 P0-2 反证钉死（负数同构）：idleTimeoutMillis<0 同样关闭看门狗（withTimeoutOrNull(负) 亦立即返回 null）。
    @Test
    fun `idleTimeout 为负时关闭看门狗不杀进程（idleTimedOut false, 正常退出）`() = runBlocking {
        val root = tempDir("neg")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            val result = launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "echo start; sleep 1"), workingDir = root),
                lines,
                totalTimeoutMillis = 30_000,
                idleTimeoutMillis = -1,      // 🔴 负数 = 关闭看门狗
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }

            assertTrue(!result.idleTimedOut, "idleTimeoutMillis<0 必须关闭看门狗，不得置位 idleTimedOut")
            assertTrue(!result.killed, "idleTimeoutMillis<0 不得杀进程")
            assertEquals(0, result.exitCode, "看门狗关闭 ⇒ 进程应自然退出 exitCode=0")
            assertEquals(listOf("start"), collected.filter { it.source == AgentLine.Source.STDOUT }.map { it.text })
        } finally {
            registry.close()
        }
    }
}
