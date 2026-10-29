package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChip
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChipKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3-A2 会话管理面单测（2026-09-19 P4-C1 随 SqliteSessionStore 迁 :core）：
 * SqliteSessionStore 管理面查询——listSummaries/rename/delete 级联 + 墓碑收口。
 * （原会话 reducer 侧的 switchSession/reset/abandon 测试随万能解密移除批消亡。）
 *
 * S5d（2026-09-22 调查工作台）：artifacts kind 编解码往返——`path|kind` 对编码
 * 落盘 + 纯 path 旧数据回落 FILE 的向后兼容分支。
 */
class SqliteSessionStoreManagementTest {

    private fun userEntry(text: String) = ChatEntry.User(id = ChatEntry.nextId(), text = text)

    @Test
    fun `sqlite store list rename delete roundtrip`() {
        val db = Database.inMemory()
        val store = SqliteSessionStore(db)

        store.load() // SQLite 实现恒 no-op——此处钉死接口面调用不炸
        val sid = "sqlite-session-1"
        store.append(sid, userEntry("SQLite 会话第一条"))
        store.append(sid, ChatEntry.Assistant(id = ChatEntry.nextId(), text = "回答"))

        val summaries = store.listSummaries()
        assertEquals(1, summaries.size)
        assertEquals("SQLite 会话第一条", summaries.first().title.take(20))
        assertEquals(2, summaries.first().messageCount)

        store.rename(sid, "重命名后的标题")
        assertEquals("重命名后的标题", store.listSummaries().first().title)

        store.delete(sid)
        assertTrue(store.listSummaries().isEmpty(), "删除后会话列表为空")
        assertEquals(0, db.messagesQueries.countMessagesForSession(sid).executeAsOne(), "消息级联删除")
    }

    @Test
    fun `tombstone hides session from management list`() {
        val db = Database.inMemory()
        val store = SqliteSessionStore(db)
        val sid = "sqlite-session-2"
        store.append(sid, userEntry("废弃前"))
        store.tombstone(sid)

        assertTrue(store.listSummaries().isEmpty(), "墓碑会话不进管理面列表")
        assertNull(store.latestSessionId(), "墓碑会话不作为恢复目标")
    }

    // ── S5d · artifacts kind 编解码往返（回放保真） ──

    @Test
    fun `artifact kind survives append-replay roundtrip`() {
        val db = Database.inMemory()
        val store = SqliteSessionStore(db)
        val sid = "sqlite-session-artifacts"
        store.append(sid, userEntry("分析"))
        store.append(
            sid,
            ChatEntry.ToolCall(
                id = ChatEntry.nextId(),
                toolCallId = "tc-1",
                toolName = "log.analyze_log",
                argsSummary = "file=/a.log",
                state = ToolState.COMPLETED,
                artifacts = listOf(
                    ArtifactChip("/tmp/report.md", ArtifactChipKind.REPORT),
                    ArtifactChip("/tmp/extracted.txt", ArtifactChipKind.EXTRACTED),
                ),
            ),
        )

        val replayed = store.replay(sid).filterIsInstance<ChatEntry.ToolCall>().single()
        assertEquals(
            listOf(
                ArtifactChip("/tmp/report.md", ArtifactChipKind.REPORT),
                ArtifactChip("/tmp/extracted.txt", ArtifactChipKind.EXTRACTED),
            ),
            replayed.artifacts,
            "S5d：REPORT/EXTRACTED 细分经 append-replay 往返不丢",
        )
    }

    @Test
    fun `legacy plain-path artifacts fall back to FILE kind on decode`() {
        // 旧数据形态：artifacts JSON 数组存纯 path（无 '|kind' 后缀）——S5d 前存量行零迁移直读
        val db = Database.inMemory()
        val store = SqliteSessionStore(db)
        val sid = "sqlite-session-legacy-art"
        db.sessionsQueries.upsertSession(sid, "旧会话", 0L, 0L)
        db.messagesQueries.insertMessage(
            session_id = sid,
            seq = 1L,
            role = "tool",
            content = "file=/a.log",
            tool_name = "log.analyze_log",
            tool_observation = null,
            entry_id = null,
            state = "COMPLETED",
            attachments = null,
            artifacts = """["/tmp/legacy-report.md"]""",
            error_kind = null,
            title = null,
            detail = null,
            created_at = 0L,
        )

        val replayed = store.replay(sid).filterIsInstance<ChatEntry.ToolCall>().single()
        assertEquals(
            listOf(ArtifactChip("/tmp/legacy-report.md", ArtifactChipKind.FILE)),
            replayed.artifacts,
            "纯 path 旧数据回落 FILE（向后兼容分支）",
        )
    }
}
