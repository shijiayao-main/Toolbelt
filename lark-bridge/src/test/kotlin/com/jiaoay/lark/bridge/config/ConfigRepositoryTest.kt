package com.jiaoay.lark.bridge.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CDS §13.4 · NF-10] ConfigRepository：首次写创建文件、400ms debounce 合并、损坏文件回退 Failed(ConfigParseFailed)、
 * 写入中途异常旧文件完好、flushNow 完成落盘。
 * 🔴 用真实文件系统 + 小 debounce 窗口（测试提速），真实 sleep 观察 trailing 合并。
 */
class ConfigRepositoryTest {

    private val debounce = 120L

    private fun tempRoot(): Path = Files.createTempDirectory("cfg-repo-${System.nanoTime()}")

    private fun repo(root: Path, scope: CoroutineScope) =
        ConfigRepository(ConfigPaths(root), scope, debounceMillis = debounce)

    @Test
    fun `文件不存在时 load 返回 Failed-ConfigNotFound`() {
        val root = tempRoot()
        val result = repo(root, CoroutineScope(Dispatchers.IO)).load()
        assertTrue(result is ConfigLoadResult.Failed)
        assertEquals(BridgeErrorCode.ConfigNotFound, (result as ConfigLoadResult.Failed).code)
    }

    @Test
    fun `首次写入创建文件且内容可回读`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        val config = DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "cli_first"))
        r.save(config)
        r.flushNow()
        val file = root.resolve("bridge-config.json").toFile()
        assertTrue(file.exists())
        val reloaded = r.load()
        assertTrue(reloaded is ConfigLoadResult.Loaded)
        assertEquals("cli_first", (reloaded as ConfigLoadResult.Loaded).config.connection.appId)
        scope.cancel()
    }

    @Test
    fun `debounce 合并 - 窗口内连续 save 只落一次盘（无 bak 佐证单次物理写）`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        // 窗口内快速三连 save（后一次取消前一次的 debounce job）
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "A")))
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "B")))
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "C")))
        // debounce 未到期：文件尚未创建
        Thread.sleep(debounce / 2)
        assertFalse(root.resolve("bridge-config.json").toFile().exists(), "debounce 窗口内不应落盘")
        // 越过窗口：只落最后一次 C
        Thread.sleep(debounce + 150)
        val file = root.resolve("bridge-config.json").toFile()
        assertTrue(file.exists())
        val loaded = (r.load() as ConfigLoadResult.Loaded).config
        assertEquals("C", loaded.connection.appId)
        // 单次物理写 ⇒ 首次写无既存文件 ⇒ 不产生 .bak（若发生多次写，第 2 次起会生成 .bak）
        assertFalse(root.resolve("bridge-config.json.bak").toFile().exists(), "合并后应只有一次物理写，无 .bak")
        scope.cancel()
    }

    @Test
    fun `损坏文件 load 回退 Failed-ConfigParseFailed（不抛不静默覆盖）`() {
        val root = tempRoot()
        val file = root.resolve("bridge-config.json").toFile()
        file.writeText("{ this is not valid json ]", Charsets.UTF_8)
        val result = repo(root, CoroutineScope(Dispatchers.IO)).load()
        assertTrue(result is ConfigLoadResult.Failed)
        assertEquals(BridgeErrorCode.ConfigParseFailed, (result as ConfigLoadResult.Failed).code)
        // 损坏文件未被覆盖
        assertEquals("{ this is not valid json ]", file.readText(Charsets.UTF_8))
    }

    @Test
    fun `写入中途异常旧文件完好（父目录只读注入 IO 失败）`() = runBlocking {
        // root 用户可绕过文件权限，跳过故障注入断言（CI 通常非 root）
        if ("root" == System.getProperty("user.name")) return@runBlocking
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        // 先落一份有效文件
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "OLD")))
        r.flushNow()
        val file = root.resolve("bridge-config.json").toFile()
        val before = file.readText(Charsets.UTF_8)
        assertTrue(before.contains("OLD"))

        // 注入故障：父目录改只读 → 无法创建 tmp/.bak → 写入抛异常
        val perms = Files.getPosixFilePermissions(root)
        Files.setPosixFilePermissions(root, perms.filter { it.name != "OWNER_WRITE" && it.name != "GROUP_WRITE" && it.name != "OTHERS_WRITE" }.toSet())
        try {
            r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "NEW")))
            r.flushNow() // flushPending 内 runCatching 吞异常并回置 pending，不抛
            // 旧文件完好无损
            assertEquals(before, file.readText(Charsets.UTF_8))
            assertTrue(file.readText(Charsets.UTF_8).contains("OLD"))
            assertFalse(file.readText(Charsets.UTF_8).contains("NEW"))
        } finally {
            Files.setPosixFilePermissions(root, perms) // 恢复以便清理
        }
        scope.cancel()
    }

    @Test
    fun `flushNow 越过 debounce 立即落盘`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "FLUSH")))
        // 不 sleep，直接 flushNow
        r.flushNow()
        assertTrue(root.resolve("bridge-config.json").toFile().exists())
        assertEquals("FLUSH", (r.load() as ConfigLoadResult.Loaded).config.connection.appId)
        scope.cancel()
    }

    @Test
    fun `save 后不 flushNow 但等待 debounce 到期自动落盘`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val r = repo(root, scope)
        r.save(DefaultBridgeConfig.default().copy(connection = ConnectionConfig(appId = "AUTO")))
        Thread.sleep(debounce + 200)
        assertEquals("AUTO", (r.load() as ConfigLoadResult.Loaded).config.connection.appId)
        scope.cancel()
    }
}
