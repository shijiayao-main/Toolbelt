package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.utils.time.KoogClock
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * P3-C 桥接会话持久化单测：SessionHistoryProvider 的写穿透/读升级/reset 双清/
 * LRU 只逐内存四语义（persistStore 用内存 fake——SQLite 实现由
 * SqliteChatHistoryProvider 的官方 schema 语义背书，不重复直测）。
 */
class SessionHistoryPersistenceTest {

    /** 内存 fake 持久层（语义同 SQLite：整包 store/load + deleteHistory） */
    private class FakePersistedHistory : PersistedHistory {
        val store = LinkedHashMap<String, List<Message>>()
        override suspend fun store(conversationId: String, messages: List<Message>) {
            store[conversationId] = messages
        }

        override suspend fun load(conversationId: String): List<Message> = store[conversationId] ?: emptyList()

        override suspend fun deleteHistory(conversationId: String): Boolean {
            return store.remove(conversationId) != null
        }
    }

    private fun userMsg(text: String): Message = Message.User(
        id = null,
        parts = listOf(MessagePart.Text(text)),
        metaInfo = RequestMetaInfo.create(KoogClock.System),
    )

    private fun systemMsg(): Message = Message.System(
        id = null,
        parts = listOf(MessagePart.Text("sys")),
        metaInfo = RequestMetaInfo.create(KoogClock.System),
    )

    /** 从消息提取文本（User 消息首个 Text part） */
    private fun Message.userText(): String =
        (this as Message.User).parts.filterIsInstance<MessagePart.Text>().firstOrNull()?.text ?: ""

    // ── 写穿透：store 同步落持久层 ──

    @Test
    fun `store writes through to persistence`() = runTest {
        val persisted = FakePersistedHistory()
        val provider = SessionHistoryProvider(capacity = 4, windowSize = 10, persistStore = persisted)

        provider.store("conv-1", listOf(systemMsg(), userMsg("问题")))

        // 持久层滤 System 同款语义（store 时滤——provider 内先滤后写穿透）
        assertTrue(persisted.store.containsKey("conv-1"), "store 写穿透到持久层")
        assertTrue(persisted.store["conv-1"]!!.none { it is Message.System }, "持久层同款滤 System")
    }

    // ── 读升级：重启后（内存空）从持久层回填 ──

    @Test
    fun `load upgrades from persistence after restart`() = runTest {
        val persisted = FakePersistedHistory()
        persisted.store("conv-1", listOf(userMsg("重启前的消息")))

        // 模拟重启：新 provider 实例（内存空）+ 同一持久层
        val provider = SessionHistoryProvider(capacity = 4, windowSize = 10, persistStore = persisted)
        val loaded = provider.load("conv-1")

        assertEquals(1, loaded.size, "内存 miss 时从持久层回填")
        assertEquals("重启前的消息", loaded.first().userText())
    }

    // ── reset 双清 ──

    @Test
    fun `reset clears both memory and persistence`() = runTest {
        val persisted = FakePersistedHistory()
        val provider = SessionHistoryProvider(capacity = 4, windowSize = 10, persistStore = persisted)
        provider.store("conv-1", listOf(userMsg("消息")))

        val existed = provider.reset("conv-1")

        assertTrue(existed, "确实丢弃了存在的会话")
        assertTrue(persisted.store.isEmpty(), "持久层同步清")
        assertTrue(provider.load("conv-1").isEmpty(), "内存与持久层双清后 load 为空")
    }

    @Test
    fun `reset on persisted only session reports existed`() = runTest {
        val persisted = FakePersistedHistory()
        persisted.store("conv-only-persist", listOf(userMsg("只在持久层")))

        val provider = SessionHistoryProvider(capacity = 4, windowSize = 10, persistStore = persisted)
        val existed = provider.reset("conv-only-persist")

        assertTrue(existed, "持久层有历史时 reset 也报 true（新对话语义对齐）")
    }

    // ── LRU 只逐内存：被逐会话经读升级自动回归 ──

    @Test
    fun `lru evicts memory only and read upgrades back`() = runTest {
        val persisted = FakePersistedHistory()
        val provider = SessionHistoryProvider(capacity = 2, windowSize = 10, persistStore = persisted)

        provider.store("conv-1", listOf(userMsg("一")))
        provider.store("conv-2", listOf(userMsg("二")))
        provider.store("conv-3", listOf(userMsg("三"))) // 容量 2：conv-1 被逐出内存

        assertTrue(persisted.store.containsKey("conv-1"), "LRU 只逐内存——持久层保留全量")

        // 被逐会话再次引用：读升级路径自动回归
        val loaded = provider.load("conv-1")
        assertEquals(1, loaded.size, "被 LRU 逐出的会话经读升级回归")
    }

    // ── 窗口语义保留：写穿透收同款窗口视图 ──

    @Test
    fun `window size applies to persisted view`() = runTest {
        val persisted = FakePersistedHistory()
        val provider = SessionHistoryProvider(capacity = 4, windowSize = 2, persistStore = persisted)

        provider.store("conv-w", listOf(userMsg("m1"), userMsg("m2"), userMsg("m3"), userMsg("m4")))

        assertEquals(2, persisted.store["conv-w"]!!.size, "持久层收窗口视图（末 2 条）")
        assertEquals("m3", persisted.store["conv-w"]!![0].userText())
    }
}
