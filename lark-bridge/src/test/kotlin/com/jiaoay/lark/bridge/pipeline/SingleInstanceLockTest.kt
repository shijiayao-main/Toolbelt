// M4-T7 测试清单 ⑨：SingleInstanceLockTest（🔴 D19 / R16 新增能力）。
// 覆盖：锁文件路径派生、二次获取 BlockedByPeerInstance、释放后可重取、释放幂等、空白 appId、
//       锁文件不可写的降级、不同 appId 互不影响、🔴 App 与 CLI 共享同一把锁、
//       🔴 进程崩溃后锁由 OS（内核 fcntl record lock）自动释放——用真实 JVM 子进程持锁并被 SIGKILL 验证。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.ConfigPaths
import com.jiaoay.lark.bridge.exec.AgentLine
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.exec.ProgramLauncher
import com.jiaoay.lark.bridge.exec.ProgramSpec
import com.jiaoay.lark.bridge.exec.WorkspacePolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class SingleInstanceLockTest {

    private val appId = "cli_app_id_0001"

    private fun tempRoot(tag: String): Path =
        Files.createTempDirectory("sil-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

    /** 独立实现的 sha256（🔴 故意不复用被测代码，才能真正交叉校验路径派生规则）。 */
    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    // ------------------------------------------------------------------ 锁文件路径

    @Test
    fun `锁文件路径是 dataRoot-locks-sha256(appId) 前 16 位加 lock 后缀`() {
        val root = tempRoot("path")
        val paths = ConfigPaths(root)

        assertEquals(root.resolve("locks"), paths.locksDir)
        val file = paths.lockFile(appId)
        assertEquals(paths.locksDir, file.parent)

        val name = file.fileName.toString()
        assertTrue(name.endsWith(".lock"), "后缀必须是 .lock，实际 $name")
        val stem = name.removeSuffix(".lock")
        assertEquals(16, stem.length, "取 sha256 前 16 位")
        assertEquals(sha256Hex(appId).take(16), stem, "🔴 派生规则必须是 sha256(appId) 的前 16 位十六进制")
        assertTrue(stem.all { it in '0'..'9' || it in 'a'..'f' }, "必须是小写十六进制：$stem")
        // 🔴 R17 泄露面缓解：appId 明文不得出现在文件名里
        assertFalse(name.contains(appId), "文件名不得含 appId 明文")
        assertFalse(name.contains("cli_app_id"), "文件名不得含 appId 片段")
        // 确定性：同 appId 两次派生同一路径（App 与 CLI 各自构造 ConfigPaths 也必须撞上同一把锁）
        assertEquals(file, paths.lockFile(appId))
        assertEquals(file, ConfigPaths(root).lockFile(appId))
        assertFalse(file == paths.lockFile("another_app"), "不同 appId 必须派生不同锁文件")
    }

    // ------------------------------------------------------------------ 互斥语义

    @Test
    fun `🔴 二次获取返回 BlockedByPeerInstance（同 appId 单实例互斥）`() {
        val paths = ConfigPaths(tempRoot("mutex"))
        val first = SingleInstanceLock(paths)
        val second = SingleInstanceLock(paths)
        try {
            val acquired = first.tryAcquire(appId)
            assertTrue(acquired is LockAcquisition.Acquired, "首次获取必须成功：$acquired")
            assertEquals(appId, acquired.appId)
            assertEquals(paths.lockFile(appId), acquired.lockFile)
            assertTrue(acquired.lock.isValid)
            assertTrue(first.isHeld)
            assertEquals(paths.lockFile(appId), first.lockFile)
            assertTrue(Files.exists(acquired.lockFile), "锁文件必须被创建")

            // 🔴 第二个实例（另一个进程/另一个装配点）⇒ BlockedByPeerInstance，不是异常也不是等待
            assertEquals(LockAcquisition.BlockedByPeerInstance, second.tryAcquire(appId))
            assertFalse(second.isHeld, "被阻塞的实例不得声称持锁")
            assertNull(second.lockFile, "被阻塞的实例不得留下锁文件引用")
            assertTrue(first.isHeld, "第一个实例的锁不受影响")
        } finally {
            second.close()
            first.close()
        }
    }

    @Test
    fun `释放后可重新获取（含先前被阻塞的那个实例）`() {
        val paths = ConfigPaths(tempRoot("release"))
        val first = SingleInstanceLock(paths)
        val second = SingleInstanceLock(paths)
        try {
            assertTrue(first.tryAcquire(appId) is LockAcquisition.Acquired)
            assertEquals(LockAcquisition.BlockedByPeerInstance, second.tryAcquire(appId))

            assertTrue(first.releaseLock(), "首次释放必须真的做了事")
            assertFalse(first.isHeld)
            assertNull(first.lockFile)

            // 🔴 释放后**另一个**实例立刻能拿到（stop→start 与 App/CLI 交接都靠这条）
            val reacquired = second.tryAcquire(appId)
            assertTrue(reacquired is LockAcquisition.Acquired, "释放后必须可重新获取：$reacquired")
            assertEquals(paths.lockFile(appId), reacquired.lockFile)
            assertTrue(second.isHeld)
            // 现在轮到第一个实例被挡住
            assertEquals(LockAcquisition.BlockedByPeerInstance, first.tryAcquire(appId))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `同一实例重复 tryAcquire 幂等（返回同一把锁，不重新加锁）`() {
        val paths = ConfigPaths(tempRoot("idempotent"))
        SingleInstanceLock(paths).use { lock ->
            val first = lock.tryAcquire(appId)
            assertTrue(first is LockAcquisition.Acquired)
            val second = lock.tryAcquire(appId)
            assertTrue(second is LockAcquisition.Acquired, "🔴 同实例重复获取必须是 Acquired 而不是 Blocked")
            assertSame(first.lock, second.lock, "必须是同一把 FileLock（没有二次加锁）")
            assertEquals(first.lockFile, second.lockFile)
            assertTrue(lock.isHeld)
            assertTrue(lock.releaseLock(), "只释放一次就够")
        }
    }

    @Test
    fun `释放幂等：close 与 releaseLock 可被调用任意多次（D20 优雅关停）`() {
        val paths = ConfigPaths(tempRoot("close"))
        val lock = SingleInstanceLock(paths)

        assertFalse(lock.releaseLock(), "未持锁时释放是 no-op 且回报 false")
        assertFalse(lock.isHeld)

        assertTrue(lock.tryAcquire(appId) is LockAcquisition.Acquired)
        assertTrue(lock.releaseLock(), "第一次释放真的做了事")
        assertFalse(lock.releaseLock(), "第二次是 no-op")
        lock.close() // AutoCloseable 形态
        lock.close() // 幂等
        lock.release() // Unit 别名，也幂等
        assertFalse(lock.isHeld)
        assertNull(lock.lockFile)

        // 🔴 释放后还能再拿（stop→start 循环不必新建实例）
        assertTrue(lock.tryAcquire(appId) is LockAcquisition.Acquired)
        lock.close()
    }

    @Test
    fun `空白 appId 返回 Failed ConfigInvalid 且不派生锁文件`() {
        val root = tempRoot("blank")
        val paths = ConfigPaths(root)
        SingleInstanceLock(paths).use { lock ->
            listOf("", "   ", "\t\n").forEach { blank ->
                val result = lock.tryAcquire(blank)
                assertTrue(result is LockAcquisition.Failed, "空白 appId 必须是 Failed，实际 $result")
                assertEquals(BridgeErrorCode.ConfigInvalid, result.error)
                assertTrue(result.message.contains("appId"), "失败原因要点名 appId：${result.message}")
            }
            assertFalse(lock.isHeld)
            assertNull(lock.lockFile)
            // 🔴 不得因为一次非法调用就在数据根下留下垃圾目录
            assertTrue(
                Files.notExists(paths.locksDir) || Files.list(paths.locksDir).use { it.count() == 0L },
                "空白 appId 不得创建任何锁文件",
            )
        }
    }

    @Test
    fun `不同 appId 各自可获取（锁按 appId 派生，多 bot 互不影响）`() {
        val paths = ConfigPaths(tempRoot("multi"))
        val first = SingleInstanceLock(paths)
        val second = SingleInstanceLock(paths)
        val third = SingleInstanceLock(paths)
        try {
            val a = first.tryAcquire("app_alpha")
            val b = second.tryAcquire("app_beta")
            assertTrue(a is LockAcquisition.Acquired, "$a")
            assertTrue(b is LockAcquisition.Acquired, "$b")
            assertFalse(a.lockFile == b.lockFile, "不同 appId 必须是不同锁文件")
            assertTrue(Files.exists(a.lockFile))
            assertTrue(Files.exists(b.lockFile))

            // 同 appId 的第三者仍被挡
            assertEquals(LockAcquisition.BlockedByPeerInstance, third.tryAcquire("app_alpha"))
            assertEquals(LockAcquisition.BlockedByPeerInstance, third.tryAcquire("app_beta"))
            assertFalse(third.isHeld)
        } finally {
            third.close()
            second.close()
            first.close()
        }
    }

    @Test
    fun `锁目录不可写时降级为 Failed ConfigWriteFailed 而不是抛异常`() {
        val root = tempRoot("unwritable")
        // 🔴 把 locks 目录位置占成一个普通文件 ⇒ createDirectories 抛 IOException，必须被归一成 Failed
        Files.createDirectories(root)
        Files.write(root.resolve("locks"), "not a directory".toByteArray(Charsets.UTF_8))

        val paths = ConfigPaths(root)
        SingleInstanceLock(paths).use { lock ->
            val result = lock.tryAcquire(appId)
            assertTrue(result is LockAcquisition.Failed, "IO 故障必须是 Failed，实际 $result")
            assertEquals(BridgeErrorCode.ConfigWriteFailed, result.error)
            assertTrue(result.message.contains("锁文件"), "失败原因要能排障：${result.message}")
            assertFalse(lock.isHeld)
        }
    }

    @Test
    fun `🔴 App 与 CLI 共享同一把锁（同 appId 同 dataRoot，两个独立装配点互斥）`() {
        val root = tempRoot("shared")
        // 模拟：App 侧与 CLI 侧各自构造 ConfigPaths（同 dataRoot）与各自的 SingleInstanceLock
        val appPaths = ConfigPaths(root)
        val cliPaths = ConfigPaths(root)
        assertEquals(appPaths.lockFile(appId), cliPaths.lockFile(appId), "路径必须逐字相同 ⇒ 天然互斥，无需 IPC")

        val appLock = SingleInstanceLock(appPaths)
        val cliLock = SingleInstanceLock(cliPaths)
        try {
            assertTrue(appLock.tryAcquire(appId) is LockAcquisition.Acquired, "App 先启动 ⇒ 拿到锁")
            assertEquals(LockAcquisition.BlockedByPeerInstance, cliLock.tryAcquire(appId), "CLI 后启动 ⇒ 必须被挡")

            appLock.close() // App 退出
            val cli = cliLock.tryAcquire(appId)
            assertTrue(cli is LockAcquisition.Acquired, "App 退出后 CLI 必须能接手：$cli")
            assertEquals(appPaths.lockFile(appId), cli.lockFile)

            cliLock.close() // CLI 退出
            assertTrue(appLock.tryAcquire(appId) is LockAcquisition.Acquired, "CLI 退出后 App 又能接手")
        } finally {
            cliLock.close()
            appLock.close()
        }
    }

    // ------------------------------------------------------------------ 🔴 崩溃恢复（内核行为，零清理代码）

    /**
     * 持锁子进程的源码（Java 单文件源码模式，JDK 11+；🔴 只依赖 java.nio，故无需任何 classpath）。
     * 打印 `LOCKED` 后挂住，等父进程 SIGKILL 它 —— 不跑任何 shutdown hook，等价于「进程崩溃」。
     */
    private val holderSource = """
        import java.nio.channels.FileChannel;
        import java.nio.channels.FileLock;
        import java.nio.file.Paths;
        import java.nio.file.StandardOpenOption;

        public class LockHolder {
            public static void main(String[] args) throws Exception {
                FileChannel channel = FileChannel.open(
                    Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    System.out.println("FAILED_TO_LOCK");
                    System.out.flush();
                    return;
                }
                System.out.println("LOCKED");
                System.out.flush();
                Thread.sleep(600000L);
            }
        }
    """.trimIndent()

    @Test
    fun `🔴 进程崩溃后锁由 OS 释放（真实 JVM 子进程持锁并被 SIGKILL）`() = runBlocking {
        val root = tempRoot("crash")
        val paths = ConfigPaths(root)
        val lockFile = paths.lockFile(appId)
        Files.createDirectories(lockFile.parent)

        val holder = File(root.toFile(), "LockHolder.java")
        holder.writeText(holderSource, Charsets.UTF_8)
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java")
        assertTrue(javaBin.canExecute(), "测试 JVM 的 java 可执行文件必须存在：$javaBin")

        val registry = ProcessRegistry(maxConcurrent = 4)
        // 🔴 起子进程一律经 exec/ProgramLauncher：本模块不得出现第二个 ProcessBuilder（E 组守护）
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root.toFile())), registry, isWindows = false)
        val procRef = AtomicReference<Process?>(null)
        val locked = CompletableDeferred<String>()
        val lines = ProgramLauncher.lineChannel()
        try {
            val reader = launch {
                for (line in lines) {
                    val text = line.text.trim()
                    if (text == "LOCKED" || text == "FAILED_TO_LOCK") locked.complete(text)
                }
            }
            val running = async {
                launcher.launch(
                    ProgramSpec(
                        command = listOf(javaBin.absolutePath, holder.absolutePath, lockFile.toString()),
                        workingDir = root.toFile(),
                    ),
                    lines,
                    totalTimeoutMillis = 120_000L,
                    idleTimeoutMillis = 120_000L,
                    onStart = { procRef.set(it) },
                )
            }

            // ① 等子进程真的持住锁（单文件源码模式要先编译，给足余量）
            val marker = withTimeoutOrNull(90_000L) { locked.await() }
            assertEquals("LOCKED", marker, "子进程必须成功持锁（否则本用例没有前提）")
            val proc = procRef.get()
            assertNotNull(proc)
            assertTrue(proc.isAlive)

            // ② 🔴 跨进程互斥成立：父进程此时拿不到锁
            val parent = SingleInstanceLock(paths)
            try {
                assertEquals(
                    LockAcquisition.BlockedByPeerInstance,
                    parent.tryAcquire(appId),
                    "子进程持锁期间父进程必须被挡（证明 fcntl record lock 跨进程生效）",
                )
                assertFalse(parent.isHeld)

                // ③ 🔴 模拟崩溃：SIGKILL，不给子进程任何清理机会（不跑 shutdown hook、不 release）
                proc.destroyForcibly()
                assertTrue(awaitUntil(15_000L) { !proc.isAlive }, "子进程必须已被杀死")

                // ④ 🔴 内核已回收锁 ⇒ 父进程无需任何「陈旧锁清理代码」即可拿到
                assertTrue(
                    awaitUntil(15_000L) { parent.tryAcquire(appId) is LockAcquisition.Acquired },
                    "进程崩溃后锁必须由 OS 自动释放（这正是选 FileLock 而非 pid 文件的理由）",
                )
                assertTrue(parent.isHeld)
                assertEquals(lockFile, parent.lockFile)
            } finally {
                parent.close()
            }

            // ⑤ 收尾：launcher 的 kill 升级链与登记表也要正常收口（不得留下僵尸登记）
            lines.close()
            val result = withTimeoutOrNull(20_000L) { running.await() }
            assertNotNull(result, "子进程被杀后 launch 必须返回，不得挂住")
            assertFalse(result.exitCode == 0, "被 SIGKILL 的子进程退出码必须非 0，实际 ${result.exitCode}")
            withTimeoutOrNull(5_000L) { reader.join() }
            assertTrue(awaitUntil(5_000L) { registry.liveCount == 0 }, "登记表必须清空（无泄漏）")
        } finally {
            runCatching { lines.close() }
            registry.close()
        }
    }

    @Test
    fun `子进程正常退出后锁同样被 OS 释放（非崩溃路径的对照）`() = runBlocking {
        val root = tempRoot("exit")
        val paths = ConfigPaths(root)
        val lockFile = paths.lockFile(appId)
        Files.createDirectories(lockFile.parent)

        // 🔴 这条用「拿了锁立刻退出」的子进程：不需要 kill，靠进程自然退出回收锁
        val holder = File(root.toFile(), "LockExit.java")
        holder.writeText(
            """
            import java.nio.channels.FileChannel;
            import java.nio.file.Paths;
            import java.nio.file.StandardOpenOption;

            public class LockExit {
                public static void main(String[] args) throws Exception {
                    FileChannel channel = FileChannel.open(
                        Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    System.out.println(channel.tryLock() == null ? "FAILED_TO_LOCK" : "LOCKED");
                }
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val javaBin = File(File(System.getProperty("java.home"), "bin"), "java")
        val registry = ProcessRegistry(maxConcurrent = 2)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root.toFile())), registry, isWindows = false)
        val lines = ProgramLauncher.lineChannel()
        try {
            val reader = launch { for (line: AgentLine in lines) { /* drain */ } }
            val result = launcher.launch(
                ProgramSpec(
                    command = listOf(javaBin.absolutePath, holder.absolutePath, lockFile.toString()),
                    workingDir = root.toFile(),
                ),
                lines,
                totalTimeoutMillis = 90_000L,
                idleTimeoutMillis = 90_000L,
            )
            lines.close()
            withTimeoutOrNull(5_000L) { reader.join() }

            assertEquals(0, result.exitCode, "子进程必须正常退出")
            assertFalse(result.timedOut)
            assertFalse(result.killed)
            // 🔴 进程退出即释放 ⇒ 父进程立刻能拿
            SingleInstanceLock(paths).use { parent ->
                assertTrue(
                    awaitUntil(10_000L) { parent.tryAcquire(appId) is LockAcquisition.Acquired },
                    "子进程退出后锁必须可用",
                )
            }
        } finally {
            runCatching { lines.close() }
            registry.close()
        }
    }
}
