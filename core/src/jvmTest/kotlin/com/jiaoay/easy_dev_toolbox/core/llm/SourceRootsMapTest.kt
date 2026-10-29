package com.jiaoay.easy_dev_toolbox.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 源码目录 allowlist 编解码（P4-B 任务 1.3，零 IO 直测纯函数）：
 * - 回环：encode → decode 保序保内容；
 * - 坏值防御：坏 JSON 回落空表不抛（DataStore 无事务，半写残留不该炸装配链）；
 * - 空白/去重：encode 去空白项 + 后写胜去重；
 * - add/remove 列表纯函数（最近添加移末位）。
 */
class SourceRootsMapTest {

    @Test
    fun `round trip preserves order and content`() {
        val roots = listOf("/home/u/projA", "/home/u/projB", "/work/mono")
        val decoded = SourceRootsMap.decode(SourceRootsMap.encode(roots))
        assertEquals(roots, decoded, "编解码回环保序")
    }

    @Test
    fun `corrupted value falls back to empty list`() {
        assertTrue(SourceRootsMap.decode("{broken json").isEmpty(), "坏值回落空表不抛")
        assertTrue(SourceRootsMap.decode("").isEmpty(), "空串 = 未配置")
    }

    @Test
    fun `encode trims blanks and dedupes`() {
        assertEquals(
            listOf("/a", "/b"),
            SourceRootsMap.decode(SourceRootsMap.encode(listOf(" /a ", "", "/b", "/a", "  "))),
            "去空白项 + 后写胜去重",
        )
    }

    @Test
    fun `add moves existing to tail and remove filters`() {
        val base = listOf("/a", "/b")
        assertEquals(listOf("/a", "/b", "/c"), SourceRootsMap.add(base, "/c"), "新条目追加末位")
        assertEquals(listOf("/a", "/c"), SourceRootsMap.add(SourceRootsMap.remove(base, "/b"), "/c"), "删除后追加")
        assertEquals(listOf("/b", "/a"), SourceRootsMap.add(base, "/a"), "已存在条目移到末位（最近添加优先）")
        assertEquals(listOf("/a"), SourceRootsMap.remove(base, "/b"), "删除单条")
    }

    @Test
    fun `store round trip via LlmConfigStore key`() = kotlinx.coroutines.runBlocking {
        // DataStore 接缝：LlmConfigStoreTest 同款临时目录 + LocalDataManager 隔离配方
        val home = com.jiaoay.testfixtures.UserHomeIsolation("edt-roots-test")
        home.redirect()
        val original = LlmConfigStore.store
        LlmConfigStore.store = com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager(
            dataStoreName = "test_roots_${java.util.UUID.randomUUID()}",
        )
        try {
            LlmConfigStore.setSourceRoots(listOf("/home/u/projA", "/home/u/projB"))
            assertEquals(listOf("/home/u/projA", "/home/u/projB"), LlmConfigStore.getSourceRoots(), "key 读写回环")
            LlmConfigStore.setSourceRoots(emptyList())
            assertTrue(LlmConfigStore.getSourceRoots().isEmpty(), "清空回环")

            LlmConfigStore.setDistillMaxIterations(90)
            assertEquals(90, LlmConfigStore.getDistillMaxIterations(), "蒸馏预算 key 回环")
            assertEquals(60, LlmConfigStore.DEFAULT_DISTILL_MAX_ITERATIONS, "缺省 60 节点")
        } finally {
            LlmConfigStore.store = original
            home.restore()
        }
    }
}
