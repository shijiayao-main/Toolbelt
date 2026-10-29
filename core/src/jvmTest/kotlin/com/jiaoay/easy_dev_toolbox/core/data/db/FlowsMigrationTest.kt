package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * flows 表 v4 迁移验收（Quest 域 change 1——openspec add-flow-ask-user-and-flows-schema
 * 任务 2.3；RunsMigrationTest 同款配方）：
 * - v3 存量库（sessions/messages/runs + 知识库四表）升 v4 不丢既有行、flows 可写可读；
 * - user_version 回写 4（migrate 不回写坑由 Database.forFile 统一修复——验收端到端）；
 * - 二次打开零重复迁移（重复回放 3.sqm 撞「table already exists」）。
 */
class FlowsMigrationTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-flows-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    /** 模拟 v3 存量库：手动建 v1/v2/v3 全表 + 写入既有数据 + user_version=3，再经 forFile 升 v4 */
    private fun v3DbWithLegacyData(): File {
        val file = tempDbFile()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                // v1 schema（sessions/messages + FTS5 触发器）
                st.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, tombstone INTEGER NOT NULL DEFAULT 0)")
                st.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id), seq INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, tool_name TEXT, tool_observation TEXT, entry_id TEXT, state TEXT, attachments TEXT, artifacts TEXT, error_kind TEXT, title TEXT, detail TEXT, created_at INTEGER NOT NULL, UNIQUE(session_id, seq))")
                st.execute("CREATE VIRTUAL TABLE messages_fts USING fts5(content, tool_observation, content='messages', content_rowid='id')")
                st.execute("INSERT INTO sessions VALUES ('run-legacy', '既有 run 时间线', 100, 100, 0)")
                st.execute("INSERT INTO messages(session_id, seq, role, content, created_at) VALUES ('run-legacy', 0, 'user', '升级前的消息', 100)")
                // v2 增量（知识库四表）
                st.execute("CREATE TABLE tag_registry (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL, repo TEXT NOT NULL, doc_name TEXT NOT NULL, module TEXT, aliases TEXT, truncated_tag TEXT, note TEXT, UNIQUE(tag, repo, doc_name))")
                st.execute("CREATE TABLE knowledge_docs (id INTEGER PRIMARY KEY AUTOINCREMENT, repo TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, body TEXT NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(repo, name))")
                // v3 增量（runs 表——本批的前一版）
                st.execute("CREATE TABLE runs (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, log_files TEXT NOT NULL, target_time TEXT, range_minutes INTEGER, status TEXT NOT NULL, report_path TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                st.execute("INSERT INTO runs VALUES ('run-1', '设置黑屏分析', '[]', NULL, NULL, 'completed', NULL, 1, 1)")
                st.execute("PRAGMA user_version = 3")
            }
        }
        return file
    }

    @Test
    fun `v3 库升级 v4 不丢既有数据且 flows 可写`() {
        val file = v3DbWithLegacyData()
        val db = Database.forFile(file)

        // 既有数据原样在位（sessions/messages/runs 三面）
        assertEquals("既有 run 时间线", db.sessionsQueries.sessionById("run-legacy").executeAsOne().title)
        assertEquals(1L, db.messagesQueries.countMessagesForSession("run-legacy").executeAsOne())
        assertEquals("设置黑屏分析", db.runsQueries.runById("run-1").executeAsOne().title)

        // flows 可写可读（迁移真实建表）
        db.flowsQueries.insertFlow(
            id = "flow-1",
            title = "相机拉起失败排查",
            capabilities = """["log-analysis"]""",
            created_at = 1L,
            updated_at = 1L,
        )
        assertEquals(1L, db.flowsQueries.countFlows().executeAsOne())
        assertEquals("相机拉起失败排查", db.flowsQueries.flowById("flow-1").executeAsOne().title)
    }

    @Test
    fun `升级后 user_version 落到当前 schema 版本-二次打开零重复迁移`() {
        val file = v3DbWithLegacyData()
        Database.forFile(file)
        // 2026-09-22 S1 起 schema 已演进到 v5（+spaces）：v3 存量库经 migrate 一路升到
        // 当前版本——断言跟随 schemaVersionForTest() 免写死数字（Database 现成测试缝），
        // 后续每加一个 .sqm 本用例零维护。
        val expected = Database.schemaVersionForTest()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            val rs = conn.createStatement().executeQuery("PRAGMA user_version")
            assertTrue(rs.next() && rs.getLong(1) == expected, "migrate 后 user_version 必须回写 $expected")
        }
        // 二次打开：版本相等 ⇒ 不再触发迁移分支（重复回放 .sqm 会撞 table already exists）
        Database.forFile(file)
    }

    @Test
    fun `全新库直接建 v4-flows 表随建随写`() {
        val db = Database.forFile(tempDbFile())
        db.flowsQueries.insertFlow(
            id = "flow-new",
            title = "新库 flow",
            capabilities = "[]",
            created_at = 2L,
            updated_at = 2L,
        )
        val row = db.flowsQueries.flowById("flow-new").executeAsOne()
        assertEquals("新库 flow", row.title)
        assertNotNull(row.capabilities)
    }
}
