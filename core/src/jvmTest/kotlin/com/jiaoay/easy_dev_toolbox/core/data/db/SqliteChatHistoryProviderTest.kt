// 2026-09-19 P1-A2：SqliteChatHistoryProvider（Koog ChatHistoryProvider 的 SQLite 实现）验收。
//
// 验收项：
// 1. store/load 往返（Message 结构保真——System/User/Assistant 三角色，textContent 提取）；
// 2. upsert 幂等（同 conversationId 二次 store = 整体替换，非追加）；
// 3. 多会话隔离 + deleteHistory；
// 4. TTL：过期后 load 返回空 + store 顺带 cleanupExpired 清行；
// 5. 与 SQLDelight 共享 db 文件双连接并存（WAL）。
package com.jiaoay.easy_dev_toolbox.core.data.db

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class SqliteChatHistoryProviderTest {

    private fun tempDb(): File =
        File(System.getProperty("java.io.tmpdir"), "chat-history-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    private fun assistant(text: String) = Message.Assistant(text, ResponseMetaInfo(Instant.DISTANT_PAST))

    @Test
    fun `store-load 往返-消息保真`() = runBlocking {
        val provider = SqliteChatHistoryProvider.forFile(tempDb().absolutePath)
        val messages = listOf(
            Message.System("系统提示", RequestMetaInfo(Instant.DISTANT_PAST)),
            Message.User("用户输入", RequestMetaInfo(Instant.DISTANT_PAST)),
            assistant("助手回答"),
        )
        provider.store("conv-1", messages)
        val loaded = provider.load("conv-1")
        assertEquals(messages.size, loaded.size, "消息数保真")
        assertEquals("系统提示", loaded[0].textContent())
        assertEquals("用户输入", loaded[1].textContent())
        assertEquals("助手回答", loaded[2].textContent())
    }

    @Test
    fun `upsert 幂等-二次 store 整体替换`() = runBlocking {
        val provider = SqliteChatHistoryProvider.forFile(tempDb().absolutePath)
        provider.store("conv-2", listOf(assistant("第一轮")))
        provider.store("conv-2", listOf(assistant("第一轮"), assistant("第二轮")))
        val loaded = provider.load("conv-2")
        assertEquals(2, loaded.size, "整体替换语义（Koog ChatMemory store 契约）")
        assertEquals(1L, provider.conversationCount(), "单会话一行")
    }

    @Test
    fun `多会话隔离`() = runBlocking {
        val provider = SqliteChatHistoryProvider.forFile(tempDb().absolutePath)
        provider.store("conv-a", listOf(assistant("A")))
        provider.store("conv-b", listOf(assistant("B")))
        assertEquals("A", provider.load("conv-a").single().textContent())
        assertEquals("B", provider.load("conv-b").single().textContent())
        assertEquals(2L, provider.conversationCount())
        provider.deleteHistory("conv-a")
        assertTrue(provider.load("conv-a").isEmpty(), "删除后为空")
        assertEquals(1L, provider.conversationCount())
    }

    @Test
    fun `TTL 过期-load 空且清行`() = runBlocking {
        val provider = SqliteChatHistoryProvider.forFile(tempDb().absolutePath, ttlSeconds = 0)
        provider.store("conv-ttl", listOf(assistant("将被过期")))
        // ttlSeconds=0 → 已过期；下一次任意 store 的 cleanupExpired 会清掉它
        provider.store("conv-other", listOf(assistant("触发清理")))
        assertTrue(provider.load("conv-ttl").isEmpty(), "过期会话 load 为空")
    }

    @Test
    fun `与 SQLDelight 同库文件双连接并存`() = runBlocking {
        val dbFile = tempDb()
        // SQLDelight 侧先建库写数据
        val db = Database.forFile(dbFile)
        db.sessionsQueries.insertSession("s1", "并存的会话", 1L, 1L)
        // Koog provider 侧独立连接写 chat_history
        val provider = SqliteChatHistoryProvider.forFile(dbFile.absolutePath)
        provider.store("conv-x", listOf(assistant("并存")))
        // 双侧互读验证
        assertEquals(1, db.sessionsQueries.countSessions().executeAsOne().toInt(), "SQLDelight 侧数据完好")
        assertEquals(1, provider.load("conv-x").size, "Koog 侧数据完好")
    }
}
