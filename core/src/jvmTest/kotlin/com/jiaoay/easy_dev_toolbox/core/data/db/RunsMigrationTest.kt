package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * runs 表 v3 迁移验收（P4-C2 openspec log-workbench 任务 1.1——design D2 迁移纪律钉死）：
 * - v2 存量库（sessions/messages + 知识库四表）升 v3 不丢既有行、runs 可写可读；
 * - user_version 回写 3（migrate 不回写坑由 Database.forFile 统一修复——这里验收端到端）；
 * - 二次打开零重复迁移（重复回放撞「table already exists」）；
 * - 全新库直接建 v3 全表。
 */
class RunsMigrationTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-runs-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    /** 模拟 v2 存量库：手动建 v1/v2 全表 + 写入既有数据 + user_version=2，再经 forFile 升 v3 */
    private fun v2DbWithLegacyData(): File {
        val file = tempDbFile()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                // v1 schema（sessions/messages + FTS5 触发器——与 KnowledgeEngineTest 同款）
                st.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, tombstone INTEGER NOT NULL DEFAULT 0)")
                st.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id), seq INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, tool_name TEXT, tool_observation TEXT, entry_id TEXT, state TEXT, attachments TEXT, artifacts TEXT, error_kind TEXT, title TEXT, detail TEXT, created_at INTEGER NOT NULL, UNIQUE(session_id, seq))")
                st.execute("CREATE VIRTUAL TABLE messages_fts USING fts5(content, tool_observation, content='messages', content_rowid='id')")
                st.execute("INSERT INTO sessions VALUES ('bridge-1', '桥接会话', 100, 100, 0)")
                st.execute("INSERT INTO messages(session_id, seq, role, content, created_at) VALUES ('bridge-1', 0, 'user', '升级前的消息', 100)")
                // v2 增量（知识库四表）
                st.execute("CREATE TABLE tag_registry (id INTEGER PRIMARY KEY AUTOINCREMENT, tag TEXT NOT NULL, repo TEXT NOT NULL, doc_name TEXT NOT NULL, module TEXT, aliases TEXT, truncated_tag TEXT, note TEXT, UNIQUE(tag, repo, doc_name))")
                st.execute("CREATE TABLE knowledge_docs (id INTEGER PRIMARY KEY AUTOINCREMENT, repo TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, body TEXT NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(repo, name))")
                st.execute("INSERT INTO knowledge_docs(repo, name, title, summary, body, updated_at) VALUES ('settings', 'doc.md', '既有文档', '摘要', '正文', 1)")
                st.execute("PRAGMA user_version = 2")
            }
        }
        return file
    }

    @Test
    fun `v2 库升级 v3 不丢既有数据且 runs 可写`() {
        val file = v2DbWithLegacyData()
        // forFile 走 migrate 分支（user_version=2 < schema.version=3）
        val db = Database.forFile(file)

        // 既有数据原样在位（sessions/messages/知识库三面）
        assertEquals("桥接会话", db.sessionsQueries.sessionById("bridge-1").executeAsOne().title)
        assertEquals(1L, db.messagesQueries.countMessagesForSession("bridge-1").executeAsOne())
        assertEquals(1L, db.knowledgeQueries.countDocs().executeAsOne())

        // runs 可写可读（迁移真实建表）
        db.runsQueries.insertRun(
            id = "run-1",
            title = "设置黑屏分析",
            log_files = """["/tmp/main.alog.txt"]""",
            target_time = "2026-09-19_12:00:00.000",
            range_minutes = 5L,
            status = "running",
            report_path = null,
            created_at = 1L,
            updated_at = 1L,
        )
        assertEquals(1L, db.runsQueries.countRuns().executeAsOne())
        assertEquals("设置黑屏分析", db.runsQueries.runById("run-1").executeAsOne().title)
    }

    @Test
    fun `升级后 user_version 回写当前 schema 版本-二次打开零重复迁移`() {
        val file = v2DbWithLegacyData()
        Database.forFile(file)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            val rs = conn.createStatement().executeQuery("PRAGMA user_version")
            // 🔴 不写死版本数字：断「回写当前 schema 版本」（Quest 域 change 1 起 schema=v4，
            //    本测试的 v2 库会连升 2.sqm+3.sqm 两级——v3 断言已被合法顶掉）
            assertTrue(
                rs.next() && rs.getLong(1) == Database.schemaVersionForTest(),
                "migrate 后 user_version 必须回写当前 schema 版本（migrate 自身不回写——Database.forFile 统一修复点）",
            )
        }
        // 二次打开：版本相等 ⇒ 不再触发迁移分支（重复回放 .sqm 会撞 table already exists）
        Database.forFile(file)
    }

    @Test
    fun `全新库直接建 v3-runs 表随建随写`() {
        val db = Database.forFile(tempDbFile())
        db.runsQueries.insertRun(
            id = "run-new",
            title = "新库 run",
            log_files = "[]",
            target_time = null,
            range_minutes = null,
            status = "completed",
            report_path = null,
            created_at = 2L,
            updated_at = 2L,
        )
        val row = db.runsQueries.runById("run-new").executeAsOne()
        assertEquals("completed", row.status)
        assertNull(row.target_time)
        assertNotNull(row.log_files)
    }
}
