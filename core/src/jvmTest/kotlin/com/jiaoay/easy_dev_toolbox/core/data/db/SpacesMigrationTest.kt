package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * spaces 表 v5 迁移验收（调查工作台重设计 S1——FlowsMigrationTest 同款配方）：
 * - v4 存量库（sessions/messages/runs/flows + 知识库四表）升 v5 不丢既有行、spaces 可写可读；
 * - user_version 回写 5（migrate 不回写坑由 Database.forFile 统一修复——验收端到端）；
 * - 二次打开零重复迁移（重复回放 4.sqm 撞「table already exists」）。
 * 另含 [SpaceStore] CRUD/JSON 编解码/归档过滤/聚合根非级联语义直测。
 */
class SpacesMigrationTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-spaces-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    /** 模拟 v4 存量库：手动建 v1~v4 全表 + 写入既有数据 + user_version=4，再经 forFile 升 v5 */
    private fun v4DbWithLegacyData(): File {
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
                // v3 增量（runs 表）
                st.execute("CREATE TABLE runs (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, log_files TEXT NOT NULL, target_time TEXT, range_minutes INTEGER, status TEXT NOT NULL, report_path TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                st.execute("INSERT INTO runs VALUES ('run-1', '设置黑屏分析', '[]', NULL, NULL, 'completed', NULL, 1, 1)")
                // v4 增量（flows 表——上一版）
                st.execute("CREATE TABLE flows (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, capabilities TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                st.execute("INSERT INTO flows VALUES ('flow-legacy', '既有流程', '[]', 1, 1)")
                st.execute("PRAGMA user_version = 4")
            }
        }
        return file
    }

    @Test
    fun `v4 库升级 v5 不丢既有数据且 spaces 可写`() {
        val file = v4DbWithLegacyData()
        val db = Database.forFile(file)

        // 既有数据原样在位（sessions/messages/runs/flows 四面）
        assertEquals("既有 run 时间线", db.sessionsQueries.sessionById("run-legacy").executeAsOne().title)
        assertEquals(1L, db.messagesQueries.countMessagesForSession("run-legacy").executeAsOne())
        assertEquals("设置黑屏分析", db.runsQueries.runById("run-1").executeAsOne().title)
        assertEquals("既有流程", db.flowsQueries.flowById("flow-legacy").executeAsOne().title)

        // spaces 可写可读（迁移真实建表）
        db.spacesQueries.insertSpace(
            id = "space-1",
            title = "设备X · ANR 调查",
            status = "active",
            file_paths = """["/logs/ANR_034122.xlog"]""",
            flow_ids = "[]",
            run_ids = "[]",
            created_at = 1L,
            updated_at = 1L,
        )
        assertEquals(1L, db.spacesQueries.countSpaces().executeAsOne())
        assertEquals("设备X · ANR 调查", db.spacesQueries.spaceById("space-1").executeAsOne().title)
    }

    @Test
    fun `升级后 user_version 回写当前 schema 版本-二次打开零重复迁移`() {
        val file = v4DbWithLegacyData()
        Database.forFile(file)
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            val rs = conn.createStatement().executeQuery("PRAGMA user_version")
            // 🔴 不写死版本数字：断「回写当前 schema 版本」（runs 书签表起 schema=v6，
            //    本测试的 v4 库会连升 4.sqm+5.sqm 两级——v5 断言已被合法顶掉）
            assertTrue(
                rs.next() && rs.getLong(1) == Database.schemaVersionForTest(),
                "migrate 后 user_version 必须回写当前 schema 版本",
            )
        }
        // 二次打开：版本相等 ⇒ 不再触发迁移分支（重复回放 4.sqm 会撞 table already exists）
        Database.forFile(file)
    }

    @Test
    fun `全新库直接建 v5-spaces 表随建随写`() {
        val db = Database.forFile(tempDbFile())
        db.spacesQueries.insertSpace(
            id = "space-new",
            title = "新库空间",
            status = "active",
            file_paths = "[]",
            flow_ids = "[]",
            run_ids = "[]",
            created_at = 2L,
            updated_at = 2L,
        )
        val row = db.spacesQueries.spaceById("space-new").executeAsOne()
        assertEquals("新库空间", row.title)
        assertNotNull(row.file_paths)
    }

    // ── SpaceStore 直测 ──

    private fun store(db: ToolbeltDb = Database.forFile(tempDbFile())) = SpaceStore(db)

    @Test
    fun `SpaceStore CRUD-标题重命名-活跃触达排序`() {
        val s = store()
        s.create(id = "space-a", title = "空间A")
        s.create(id = "space-b", title = "空间B")
        Thread.sleep(2) // updated_at 毫秒粒度保序
        s.touch("space-a")

        // 倒序：touch 后 a 在前
        assertEquals(listOf("space-a", "space-b"), s.listSpaces().map { it.id })

        s.rename("space-b", "空间B改")
        assertEquals("空间B改", s.spaceById("space-b")?.title)
    }

    @Test
    fun `成员增删去重保序-removeMember 幂等`() {
        val s = store()
        s.create(id = "space-1", title = "调查1")
        s.addFiles("space-1", listOf("/logs/a.xlog", "/logs/b.xlog"))
        s.addFiles("space-1", listOf("/logs/a.xlog", "/logs/c.xlog")) // a 重复 offer
        assertEquals(listOf("/logs/a.xlog", "/logs/b.xlog", "/logs/c.xlog"), s.spaceById("space-1")?.filePaths)

        s.addFlows("space-1", listOf("flow-1"))
        s.addRuns("space-1", listOf("run-1", "run-2"))
        assertEquals(listOf("flow-1"), s.spaceById("space-1")?.flowIds)
        assertEquals(listOf("run-1", "run-2"), s.spaceById("space-1")?.runIds)

        // addFlows/addRuns 去重保序（空间归属接线批 2026-09-23 补锚：重复 offer 幂等
        // ——自动归属链（SpaceAttribution）重放/竞态不产生重复成员）
        s.addFlows("space-1", listOf("flow-1", "flow-3"))
        s.addRuns("space-1", listOf("run-2", "run-4"))
        assertEquals(listOf("flow-1", "flow-3"), s.spaceById("space-1")?.flowIds)
        assertEquals(listOf("run-1", "run-2", "run-4"), s.spaceById("space-1")?.runIds)

        s.removeMember("space-1", SpaceStore.MemberKind.FILE, "/logs/b.xlog")
        s.removeMember("space-1", SpaceStore.MemberKind.FILE, "/logs/不存在") // 幂等
        assertEquals(listOf("/logs/a.xlog", "/logs/c.xlog"), s.spaceById("space-1")?.filePaths)
    }

    @Test
    fun `归档过滤与状态解码兜底`() {
        val s = store()
        s.create(id = "space-live", title = "进行中")
        s.create(id = "space-done", title = "已结案")
        s.archive("space-done", archived = true)

        // 默认滤归档；includeArchived 见全集
        assertEquals(listOf("space-live"), s.listSpaces().map { it.id })
        assertEquals(2, s.listSpaces(includeArchived = true).size)
        assertEquals(SpaceStore.SpaceStatus.ARCHIVED, s.spaceById("space-done")?.status)

        // 取消归档回路
        s.archive("space-done", archived = false)
        assertEquals(SpaceStore.SpaceStatus.ACTIVE, s.spaceById("space-done")?.status)

        // 解码兜底（纯函数直测——前向兼容）：未知 status 落 ACTIVE
        assertEquals(SpaceStore.SpaceStatus.ACTIVE, SpaceStore.SpaceStatus.decode("future-unknown"))
        assertEquals(SpaceStore.SpaceStatus.ACTIVE, SpaceStore.SpaceStatus.decode(null))
        assertEquals(SpaceStore.SpaceStatus.ARCHIVED, SpaceStore.SpaceStatus.decode("archived"))
    }

    @Test
    fun `删除空间不级联删成员-flows 行在位`() {
        val db = Database.forFile(tempDbFile())
        val s = SpaceStore(db)
        db.flowsQueries.insertFlow(id = "flow-keep", title = "成员流程", capabilities = "[]", created_at = 1, updated_at = 1)
        s.create(id = "space-x", title = "引用集")
        s.addFlows("space-x", listOf("flow-keep"))

        s.delete("space-x")
        assertNull(s.spaceById("space-x"))
        // 🔴 聚合根非级联：flows 成员行仍健在
        assertEquals("成员流程", db.flowsQueries.flowById("flow-keep").executeAsOne().title)
    }
}
