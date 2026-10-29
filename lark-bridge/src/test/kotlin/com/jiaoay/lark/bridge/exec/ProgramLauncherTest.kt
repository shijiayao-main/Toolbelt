package com.jiaoay.lark.bridge.exec

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [方案 D9/R10 · BC §4.8/§4.9] ProgramLauncher 真实进程行为（Linux CI 用 /bin/sh、env、printf、cat 等 POSIX 工具，
 * 给足超时余量避免 flaky）：cwd 生效、env 只含白名单键、UTF-8 不乱码、越界拒绝、which 探测、stdin 立即关闭、onStart 回调。
 * 🔴 真实进程测行为语义；kill/超时/看门狗/背压逻辑见同包 CancellationKillsProcessTest / TimeoutKillsProcessTest /
 *    IdleWatchdogTest / PipeBackpressureTest。
 * 🔴 断言块尾一律 Unit（assertTrue/assertEquals）；异常断言用 try/catch 而非 assertFailsWith（后者返回 Throwable 会使
 *    JUnit 判定「should be void」），且避免嵌套 runBlocking。
 */
class ProgramLauncherTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("pl-$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    private fun launcher(
        root: File,
        allowlist: Collection<String> = EnvAllowlist.BASE_ENV_KEYS,
    ): Pair<ProgramLauncher, ProcessRegistry> {
        val registry = ProcessRegistry(maxConcurrent = 4)
        val policy = WorkspacePolicy(listOf(root))
        return ProgramLauncher(policy, registry, userEnvAllowlist = allowlist, isWindows = false) to registry
    }

    private fun List<AgentLine>.stdoutTexts() = filter { it.source == AgentLine.Source.STDOUT }.map { it.text }

    @Test
    fun `cwd 生效 - 子进程 pwd 输出工作目录`() = runBlocking {
        val root = tempDir("cwd")
        val (launcher, registry) = launcher(root)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            val result = launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "pwd"), workingDir = root),
                lines, totalTimeoutMillis = 15_000, idleTimeoutMillis = 10_000,
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }
            assertEquals(0, result.exitCode)
            assertEquals(root.canonicalPath, collected.stdoutTexts().first().trimEnd())
        } finally {
            registry.close()
        }
    }

    @Test
    fun `env 只含白名单键 + extraEnv（凭据类变量被沙箱过滤）`() = runBlocking {
        val root = tempDir("env")
        val allow = listOf("PATH", "HOME", "LANG")
        val (launcher, registry) = launcher(root, allow)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            val result = launcher.launch(
                ProgramSpec(
                    command = listOf("/usr/bin/env"), // argv 直调，不经 shell（避免 sh 自行注入 PWD/SHLVL）
                    workingDir = root,
                    extraEnv = mapOf("BRIDGE_INJECTED" to "yes"),
                ),
                lines, totalTimeoutMillis = 15_000, idleTimeoutMillis = 10_000,
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }
            assertEquals(0, result.exitCode)
            val keys = collected.stdoutTexts()
                .filter { it.contains('=') }
                .map { it.substringBefore('=') }
                .filter { it.isNotEmpty() }
                .toSet()
            val permitted = allow.toSet() + "BRIDGE_INJECTED"
            assertTrue(keys.all { it in permitted }, "env 键必须 ⊆ 白名单∪extraEnv，实际越界键=${keys - permitted}")
            assertTrue(keys.contains("BRIDGE_INJECTED"), "extraEnv 应在白名单过滤后注入")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `UTF-8 输出不乱码（agent 子进程与 MCP stdio 一律 UTF-8）`() = runBlocking {
        val root = tempDir("utf8")
        val (launcher, registry) = launcher(root)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            val result = launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "printf '%s\\n' '中文-测试-🚀-ok'"), workingDir = root),
                lines, totalTimeoutMillis = 15_000, idleTimeoutMillis = 10_000,
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }
            assertEquals(0, result.exitCode)
            assertEquals("中文-测试-🚀-ok", collected.stdoutTexts().first())
        } finally {
            registry.close()
        }
    }

    @Test
    fun `stderr 与 stdout 分流不合并（不用 redirectErrorStream）`() = runBlocking {
        val root = tempDir("split")
        val (launcher, registry) = launcher(root)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "echo OUT; echo ERR 1>&2"), workingDir = root),
                lines, totalTimeoutMillis = 15_000, idleTimeoutMillis = 10_000,
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }
            assertTrue(collected.any { it.source == AgentLine.Source.STDOUT && it.text == "OUT" })
            assertTrue(collected.any { it.source == AgentLine.Source.STDERR && it.text == "ERR" })
        } finally {
            registry.close()
        }
    }

    @Test
    fun `启动后立即关 stdin - 子进程 cat 读到 EOF 立即退出（不等输入挂死）`() = runBlocking {
        val root = tempDir("stdin")
        val (launcher, registry) = launcher(root)
        try {
            val lines = ProgramLauncher.lineChannel()
            val reader = launch { for (l in lines) { /* drain */ } }
            val result = launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "cat > /dev/null; echo done"), workingDir = root),
                lines, totalTimeoutMillis = 10_000, idleTimeoutMillis = 8_000,
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }
            assertEquals(0, result.exitCode, "stdin 应立即关闭使 cat 读到 EOF")
            assertTrue(!result.timedOut && !result.killed)
        } finally {
            registry.close()
        }
    }

    @Test
    fun `workingDir 越界抛 IOException（多根沙箱拒绝）`() = runBlocking {
        val root = tempDir("sandbox")
        val (launcher, registry) = launcher(root)
        try {
            var thrown: Throwable? = null
            try {
                launcher.launch(
                    ProgramSpec(command = listOf("/bin/sh", "-c", "pwd"), workingDir = File("/etc")),
                    ProgramLauncher.lineChannel(), totalTimeoutMillis = 5_000, idleTimeoutMillis = 4_000,
                )
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue(thrown is IOException, "越界 workingDir 应抛 IOException，实际=$thrown")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `空白名单工作区拒绝任何 workingDir（fail-safe）`() = runBlocking {
        val registry = ProcessRegistry(maxConcurrent = 2)
        val launcher = ProgramLauncher(WorkspacePolicy(emptyList()), registry, isWindows = false)
        try {
            var thrown: Throwable? = null
            try {
                launcher.launch(
                    ProgramSpec(command = listOf("/bin/sh", "-c", "pwd"), workingDir = File("/tmp")),
                    ProgramLauncher.lineChannel(), totalTimeoutMillis = 5_000, idleTimeoutMillis = 4_000,
                )
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue(thrown is IOException, "空白名单应 fail-safe 抛 IOException，实际=$thrown")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `workingDir 为 null 时不做沙箱校验（env 白名单仍生效）`() = runBlocking {
        val root = tempDir("nullcwd")
        val (launcher, registry) = launcher(root)
        try {
            val lines = ProgramLauncher.lineChannel()
            val collected = mutableListOf<AgentLine>()
            val reader = launch { for (l in lines) collected.add(l) }
            val result = launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "echo ok"), workingDir = null),
                lines, totalTimeoutMillis = 10_000, idleTimeoutMillis = 8_000,
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }
            assertEquals(0, result.exitCode)
            assertEquals(listOf("ok"), collected.stdoutTexts())
        } finally {
            registry.close()
        }
    }

    @Test
    fun `resolveOnPath - which 语义探测到 sh`() {
        val root = tempDir("which")
        val (launcher, registry) = launcher(root)
        try {
            val found = launcher.resolveOnPath("sh")
            assertNotNull(found, "PATH 上应能探测到 sh")
            assertTrue(found.canExecute())
        } finally {
            registry.close()
        }
    }

    @Test
    fun `resolveOnPath - 不存在的可执行名返回 null`() {
        val root = tempDir("which2")
        val (launcher, registry) = launcher(root)
        try {
            assertEquals(null, launcher.resolveOnPath("definitely-not-a-real-binary-xyz-123"))
        } finally {
            registry.close()
        }
    }

    @Test
    fun `onStart 回调捕获已启动 Process 句柄`() = runBlocking {
        val root = tempDir("onstart")
        val (launcher, registry) = launcher(root)
        val captured = AtomicReference<Process?>(null)
        try {
            val lines = ProgramLauncher.lineChannel()
            val reader = launch { for (l in lines) { /* drain */ } }
            launcher.launch(
                ProgramSpec(command = listOf("/bin/sh", "-c", "echo hi"), workingDir = root),
                lines, totalTimeoutMillis = 10_000, idleTimeoutMillis = 8_000,
                onStart = { captured.set(it) },
            )
            lines.close()
            withTimeoutOrNull(5_000) { reader.join() }
            assertTrue(captured.get() != null, "onStart 应捕获到 Process 句柄")
        } finally {
            registry.close()
        }
    }

    @Test
    fun `登记达上限抛 IllegalStateException（有界拒绝，调用方映射 AgentUnavailable）`() = runBlocking {
        val root = tempDir("bounded")
        val registry = ProcessRegistry(maxConcurrent = 0) // capacity = 1
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        val blocker = ProcessBuilder("/bin/sh", "-c", "sleep 5").apply { directory(root) }.start()
        try {
            assertTrue(registry.register(blocker)) // 占满唯一容量
            var thrown: Throwable? = null
            try {
                launcher.launch(
                    ProgramSpec(command = listOf("/bin/sh", "-c", "echo x"), workingDir = root),
                    ProgramLauncher.lineChannel(), totalTimeoutMillis = 5_000, idleTimeoutMillis = 4_000,
                )
            } catch (e: Throwable) {
                thrown = e
            }
            assertTrue(thrown is IllegalStateException, "登记失败应抛 IllegalStateException，实际=$thrown")
        } finally {
            registry.unregister(blocker)
            blocker.destroyForcibly()
            blocker.waitFor()
            registry.close()
        }
    }
}
