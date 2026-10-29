package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * run 级查看器书签表 v6 迁移验收 + 仓储门面契约单测
 * （RunsMigrationTest / FlowsMigrationTest 同款配方——user_version 迁移纪律钉死）：
 * - v5 存量库升 v6 不丢既有行、run_bookmarks 可写可读（迁移真实建表）；
 * - user_version 回写当前 schema 版本 + 二次打开零重复迁移；
 * - 快照覆写语义（先清后写——书签是全量集，无增量合并面）；
 * - run 删除级联清书签（[RunStore.delete] 事务三步——孤儿书签复用面为零）。
 *
 * 夹具隔离：全部走 java.io.tmpdir 临时库文件，零触碰用户数据目录。
 */
class RunBookmarkStoreTest {

    private val tempFiles = mutableListOf<File>()

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-run-bm-${System.nanoTime()}.db")
            .apply { deleteOnExit(); tempFiles += this }

    /** 模拟 v5 存量库：手动建 sessions/runs 表 + 写入既有数据 + user_version=5，再经 forFile 升 v6 */
    private fun v5DbWithLegacyData(): File {
        val file = tempDbFile()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, tombstone INTEGER NOT NULL DEFAULT 0)")
                st.execute("CREATE TABLE runs (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, log_files TEXT NOT NULL, target_time TEXT, range_minutes INTEGER, status TEXT NOT NULL, report_path TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                st.execute("INSERT INTO runs VALUES ('run-legacy', '既有 run', '[]', NULL, NULL, 'completed', NULL, 1, 1)")
                st.execute("PRAGMA user_version = 5")
            }
        }
        return file
    }

    @Test
    fun `v5 库升级 v6 不丢既有数据且 run_bookmarks 可写可读`() {
        val file = v5DbWithLegacyData()
        val db = Database.forFile(file)

        // 既有 run 行原样在位
        assertEquals("既有 run", db.runsQueries.runById("run-legacy").executeAsOne().title)

        // 迁移真实建表：快照写入 → 回读逐字段一致（读写闭环）
        val store = RunBookmarkStore(db)
        store.saveSnapshot(
            runId = "run-legacy",
            filePath = "/tmp/artifact.txt",
            bookmarks = listOf(
                RunBookmarkStore.StoredBookmark(lineNumber = 10, lineFingerprint = "2026-09-19T10:29:50"),
                RunBookmarkStore.StoredBookmark(lineNumber = 12, lineFingerprint = "2026-09-19T10:30:05"),
            ),
        )
        assertEquals(
            listOf(
                RunBookmarkStore.StoredBookmark(10, "2026-09-19T10:29:50"),
                RunBookmarkStore.StoredBookmark(12, "2026-09-19T10:30:05"),
            ),
            store.bookmarksFor("run-legacy", "/tmp/artifact.txt"),
            "回读须行号升序且指纹逐字段一致",
        )
        assertEquals(emptyList(), store.bookmarksFor("run-legacy", "/tmp/别的文件.txt"), "其他文件键域不串读")

        // user_version 回写当前 schema 版本（migrate 自身不回写——Database.forFile 统一修复点）
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            val rs = conn.createStatement().executeQuery("PRAGMA user_version")
            assertTrue(rs.next() && rs.getLong(1) == Database.schemaVersionForTest())
        }
        // 二次打开：版本相等 ⇒ 不再触发迁移分支（重复回放 .sqm 撞 table already exists）
        Database.forFile(file)
    }

    @Test
    fun `快照覆写——二次保存全量替换且空快照清空`() {
        val store = RunBookmarkStore(Database.forFile(tempDbFile()))
        val one = listOf(RunBookmarkStore.StoredBookmark(3, "t3"))
        val three = listOf(
            RunBookmarkStore.StoredBookmark(1, "t1"),
            RunBookmarkStore.StoredBookmark(2, "t2"),
            RunBookmarkStore.StoredBookmark(3, "t3"),
        )
        store.saveSnapshot("run-1", "/tmp/a.txt", three)
        assertEquals(3, store.bookmarksFor("run-1", "/tmp/a.txt").size)
        store.saveSnapshot("run-1", "/tmp/a.txt", one)
        assertEquals(
            one,
            store.bookmarksFor("run-1", "/tmp/a.txt"),
            "快照覆写 = 先清后写，旧行不得残留（书签是全量集语义）",
        )
        store.saveSnapshot("run-1", "/tmp/a.txt", emptyList())
        assertEquals(emptyList(), store.bookmarksFor("run-1", "/tmp/a.txt"), "空快照 = 清空该文件书签（清空动作随快照持久化）")
        // run 维度隔离：另一 run 同文件书签不受本 run 覆写影响
        store.saveSnapshot("run-2", "/tmp/a.txt", three)
        store.saveSnapshot("run-1", "/tmp/a.txt", one)
        assertEquals(3, store.bookmarksFor("run-2", "/tmp/a.txt").size)
    }

    @Test
    fun `run 删除级联清书签且他 run 书签不动`() {
        val db = Database.forFile(tempDbFile())
        val runs = RunStore(db)
        val bookmarks = RunBookmarkStore(db)
        runs.create(id = "run-1", title = "被删 run", logFiles = listOf("/tmp/in.log"))
        runs.create(id = "run-2", title = "幸存 run", logFiles = listOf("/tmp/in.log"))
        bookmarks.saveSnapshot("run-1", "/tmp/a.txt", listOf(RunBookmarkStore.StoredBookmark(5, "t5")))
        bookmarks.saveSnapshot("run-2", "/tmp/a.txt", listOf(RunBookmarkStore.StoredBookmark(6, "t6")))

        runs.delete("run-1")

        assertEquals(emptyList(), bookmarks.bookmarksFor("run-1", "/tmp/a.txt"), "run 删除须级联清 run 级书签（孤儿书签复用面为零）")
        assertEquals(1, bookmarks.bookmarksFor("run-2", "/tmp/a.txt").size, "级联删除不得误伤他 run 书签")
        assertNull(runs.runById("run-1"))
    }
}
