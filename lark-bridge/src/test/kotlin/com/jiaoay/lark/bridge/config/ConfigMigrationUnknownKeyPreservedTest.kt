package com.jiaoay.lark.bridge.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CDS §13.7 · NF-11] 未知键端到端保留：写入含未知顶层键的文档 → load → save → 再 load，
 * 未知键始终存活于 experimental 段，且 experimental 内容不丢。
 */
class ConfigMigrationUnknownKeyPreservedTest {

    private fun tempRoot() = Files.createTempDirectory("cfg-unknown-${System.nanoTime()}")

    @Test
    fun `未知顶层键写入后 load 折入 experimental 仍在`() {
        val root = tempRoot()
        val file = root.resolve("bridge-config.json").toFile()
        file.writeText(
            """{"schemaVersion":1,"connection":{"appId":"a"},"nextGenFeature":{"enabled":true,"level":9}}""",
            Charsets.UTF_8,
        )
        val repo = ConfigRepository(ConfigPaths(root), CoroutineScope(Dispatchers.IO))
        val loaded = repo.load()
        assertTrue(loaded is ConfigLoadResult.Loaded)
        val experimental = (loaded as ConfigLoadResult.Loaded).config.experimental
        assertTrue(experimental.containsKey("nextGenFeature"))
        val feature = experimental["nextGenFeature"]!!.jsonObject
        assertEquals(9, feature["level"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `save-再 load 未知键随 experimental 回写不丢`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = ConfigRepository(ConfigPaths(root), scope)
        // 先落一份含未知键的原始文档
        root.resolve("bridge-config.json").toFile().writeText(
            """{"schemaVersion":1,"experimental":{"keepMe":"yes"},"orphanTop":"z"}""",
            Charsets.UTF_8,
        )
        val first = (repo.load() as ConfigLoadResult.Loaded).config
        // orphanTop 折入 experimental，keepMe 原样在 experimental
        assertTrue(first.experimental.containsKey("orphanTop"))
        assertEquals("z", first.experimental["orphanTop"]!!.jsonPrimitive.content)
        assertEquals("yes", first.experimental["keepMe"]!!.jsonPrimitive.content)

        // 改一个正常字段后 save + flushNow
        repo.save(first.copy(connection = first.connection.copy(appId = "changed")))
        repo.flushNow()

        val second = (repo.load() as ConfigLoadResult.Loaded).config
        assertEquals("changed", second.connection.appId)
        // 未知键与 experimental 内容都还在
        assertEquals("z", second.experimental["orphanTop"]!!.jsonPrimitive.content)
        assertEquals("yes", second.experimental["keepMe"]!!.jsonPrimitive.content)
        scope.cancel()
    }

    @Test
    fun `experimental 段嵌套对象内容不丢`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val repo = ConfigRepository(ConfigPaths(root), scope)
        root.resolve("bridge-config.json").toFile().writeText(
            """{"schemaVersion":1,"experimental":{"nested":{"deep":{"k":"v"}}}}""",
            Charsets.UTF_8,
        )
        val cfg = (repo.load() as ConfigLoadResult.Loaded).config
        repo.save(cfg)
        repo.flushNow()
        val reloaded = (repo.load() as ConfigLoadResult.Loaded).config
        val deep = reloaded.experimental["nested"]!!.jsonObject["deep"]!!.jsonObject
        assertEquals("v", deep["k"]!!.jsonPrimitive.content)
        scope.cancel()
    }
}
