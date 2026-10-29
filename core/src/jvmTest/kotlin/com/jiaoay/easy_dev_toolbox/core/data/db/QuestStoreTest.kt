package com.jiaoay.easy_dev_toolbox.core.data.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QuestStore 门面单测（Quest 域 change 1 任务 2.3——RunStoreTest 同款配方，inMemory db）。
 * 覆盖：create 幂等/listFlows 倒序/flowById/rename/touch/三店级联删除/title 派生。
 */
class QuestStoreTest {

    private fun store(): Triple<ToolbeltDb, SessionStore, QuestStore> {
        val db = Database.inMemory()
        return Triple(db, SqliteSessionStore(db), QuestStore(db))
    }

    @Test
    fun `create is idempotent upsert and list is reverse chronological`() {
        val (db, _, quest) = store()
        quest.create("f1", "第一个", listOf("log-analysis"), now = 100)
        quest.create("f2", "第二个", emptyList(), now = 200)
        // 同 id 覆写不新增行
        quest.create("f1", "第一个（改）", listOf("log-analysis"), now = 300)
        val flows = quest.listFlows()
        assertEquals(2, flows.size, "同 id 覆写幂等——恒 2 行")
        assertEquals("f1", flows.first().id, "updated_at=300 的 f1 排最前（倒序）")
        assertEquals("第一个（改）", flows.first().title)
        assertEquals(listOf("log-analysis"), flows.first().capabilities)
        assertEquals(emptyList<String>(), flows[1].capabilities, "空能力 = 通用对话（空数组解码）")
    }

    @Test
    fun `flowById returns null for unknown id`() {
        val (_, _, quest) = store()
        assertNull(quest.flowById("nope"))
    }

    @Test
    fun `rename and touch update title and recency`() {
        val (db, _, quest) = store()
        quest.create("f1", "旧标题", emptyList(), now = 100)
        quest.rename("f1", "新标题", now = 500)
        assertEquals("新标题", quest.flowById("f1")?.title)

        quest.create("f2", "第二个", emptyList(), now = 300)
        quest.touch("f1", now = 999) // f1 变最活跃
        assertEquals("f1", quest.listFlows().first().id)
    }

    @Test
    fun `delete cascades flows messages and sessions`() {
        val (db, sessions, quest) = store()
        // 造三层数据：flows 行 + sessions 行（append 首条自动登记）+ messages 行（id 同 key）
        sessions.append(
            "f1",
            com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry.User(
                id = "m1",
                text = "第一条消息",
                attachments = emptyList(),
            ),
        )
        quest.create("f1", "带历史的流程", listOf("voice-memo"), now = 100)
        assertEquals(1L, db.messagesQueries.countMessagesForSession("f1").executeAsOne())

        quest.delete("f1")

        assertNull(quest.flowById("f1"))
        assertEquals(0L, db.messagesQueries.countMessagesForSession("f1").executeAsOne(), "messages 级联清")
        assertNull(db.sessionsQueries.sessionById("f1").executeAsOneOrNull(), "sessions 级联清")
    }

    @Test
    fun `deriveTitle truncates to 80 chars and falls back on blank`() {
        val (_, _, quest) = store()
        val long = "测".repeat(120)
        assertEquals(80, quest.deriveTitle(long).length)
        assertEquals("新流程", quest.deriveTitle("   \n  "))
        assertEquals("单行 标题", quest.deriveTitle("单行\n标题")) // 换行折空格
    }
}
