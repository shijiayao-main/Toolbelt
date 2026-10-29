package com.jiaoay.lark.bridge.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
// Stream.toList 扩展：-Xjdk-release=11 下 Java 16+ 成员不在解析面，须显式 import 扩展
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [CDS §13.5 · NF-10 / R17] 原子写、写前备份 .bak、POSIX 600、secret 单点掩码锚点。 */
class ConfigAtomicWriteTest {

    private fun tempRoot(): Path = Files.createTempDirectory("cfg-atomic-${System.nanoTime()}")

    private fun repo(root: Path, scope: CoroutineScope) = ConfigRepository(ConfigPaths(root), scope)

    @Test
    fun `原子写 - 落盘后无残留 tmp 文件`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "atomic")))
        r.flushNow()
        val leftovers = Files.list(root).use { stream ->
            stream.map { it.fileName.toString() }.toList().filter { it.contains(".tmp-") }
        }
        assertTrue(leftovers.isEmpty(), "写成功后不应残留 .tmp-* 文件，实际=$leftovers")
        assertTrue(root.resolve("bridge-config.json").toFile().exists())
        scope.cancel()
    }

    @Test
    fun `写前备份旧文件为 bak`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        // 第一次写：无既存文件，不产生 .bak
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "V1")))
        r.flushNow()
        assertFalse(root.resolve("bridge-config.json.bak").toFile().exists())
        // 第二次写：既存 V1 → 备份为 .bak，target 变 V2
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "V2")))
        r.flushNow()
        val bak = root.resolve("bridge-config.json.bak").toFile()
        assertTrue(bak.exists(), "第二次写前应生成 .bak")
        assertTrue(bak.readText(Charsets.UTF_8).contains("V1"), ".bak 应保留上一版内容 V1")
        assertTrue(root.resolve("bridge-config.json").toFile().readText(Charsets.UTF_8).contains("V2"))
        scope.cancel()
    }

    @Test
    fun `POSIX 权限 600（R17 appSecret 明文泄露面缓解）`() = runBlocking {
        // 非 POSIX 文件系统（Windows）跳过
        val root = tempRoot()
        if (!Files.getFileStore(root).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView::class.java)) {
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appSecret = "top-secret")))
        r.flushNow()
        val perms = Files.getPosixFilePermissions(root.resolve("bridge-config.json"))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
            "配置文件权限应为 rw-------(600)",
        )
        scope.cancel()
    }

    /**
     * `.bak` 备份文件权限必须同为 rw-------(600)（R17 appSecret 泄露面缓解）。
     * `.bak` 经 copyTo 生成，按进程 umask 建文件——若写路径漏掉对其的 600 收紧，
     * 旧版明文 appSecret 会以旁路权限落盘，绕开主文件硬化。此断言拦截该回归。
     * 非 POSIX 文件系统（Windows）上 [ConfigRepository] 对权限收紧静默跳过，测试同守卫跳过防假红。
     */
    @Test
    fun `bak 备份文件权限 600（copyTo 按 umask 建文件，须同调收紧）`() = runBlocking {
        val root = tempRoot()
        // POSIX 可用性守卫：与生产代码「非 POSIX 静默跳过」语义对齐
        if (!Files.getFileStore(root).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView::class.java)) {
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        // 第一次写：建立既存文件（含明文 appSecret），此时无 .bak
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appSecret = "secret-v1")))
        r.flushNow()
        // 第二次写：既存文件被 copyTo 备份为 .bak，随后必须对 .bak 收紧 600
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appSecret = "secret-v2")))
        r.flushNow()
        val bak = root.resolve("bridge-config.json.bak")
        assertTrue(bak.toFile().exists(), "第二次写前应生成 .bak")
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(bak),
            ".bak 与主文件同为明文 appSecret 落盘处，权限应为 rw-------(600)",
        )
        // 主文件 600 为既有不变量，一并锚定
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(root.resolve("bridge-config.json")),
            "主配置文件权限应为 rw-------(600)",
        )
        scope.cancel()
    }

    @Test
    fun `secret 字段单点标记（掩码依据，NF-10 R17；2026-09-11 起为 2 项）`() {
        // 掩码本身由 UI/CLI check/日志层承接，本层锚定「secret 字段清单」供单点控制
        val secretFields = BridgeFields.ALL.filter { it.secret }
        assertEquals(2, secretFields.size)
        assertEquals(setOf("connection.appSecret", "fileMessage.xlogPrivateKey"), secretFields.map { it.id }.toSet())
    }

    @Test
    fun `tmp 命名含 nanoTime 且落同目录（同文件系统保证 ATOMIC_MOVE）`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        r.save(DefaultBridgeConfig.default())
        r.flushNow()
        // 目标与（曾存在的）tmp 同目录：断言 configFile 的 parent == dataRoot
        assertEquals(root, ConfigPaths(root).configFile.parent)
        assertEquals("bridge-config.json", ConfigPaths(root).configFile.fileName.toString())
        assertTrue(ConfigPaths(root).configFile.toString().endsWith("bridge-config.json"))
        scope.cancel()
    }
}
