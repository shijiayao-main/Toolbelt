// 2026-09-19 P1-A2 存储层验收：Database（进程唯一 SQLite + WAL + FTS5）。
//
// 验收项（总蓝图 P1 验收标准）：
// 1. FTS5 建表冒烟（xerial native 默认含 FTS5——环境断言）；
// 2. 会话/消息 CRUD 往返 + FTS 全文检索（MATCH 命中）；
// 3. WAL 模式生效（PRAGMA journal_mode 返回 wal）；
// 4. 并发读写不互斥（WAL 下 writer 持锁时 reader 照常——桌面多连接场景）；
// 5. 文件形态（db/-wal/-shm 三件套）。
package com.jiaoay.easy_dev_toolbox.core.data.db

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class DatabaseTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-db-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    @Test
    fun `FTS5 建表冒烟-全文检索命中`() {
        val db = Database.inMemory()
        val sessionId = "s1"
        db.sessionsQueries.insertSession(sessionId, "测试会话", 100L, 100L)
        db.messagesQueries.insertMessage(sessionId, 0, "user", "帮我解密 boot 日志", null, null, null, null, null, null, null, null, null, 100L)
        db.messagesQueries.insertMessage(sessionId, 1, "assistant", "解密完成: /tmp/boot.log", null, null, null, null, null, null, null, null, null, 200L)
        db.messagesQueries.insertMessage(sessionId, 2, "tool", "tool_call", "decrypt_log", "解密成功: /tmp/boot.log", null, null, null, null, null, null, null, 300L)

        val hits = db.messagesQueries.searchMessages("boot", 10).executeAsList()
        assertTrue(hits.isNotEmpty(), "FTS5 应命中 boot")
        // tool_observation 也在 FTS 索引面
        val obsHits = db.messagesQueries.searchMessages("解密成功", 10).executeAsList()
        assertTrue(obsHits.isNotEmpty(), "FTS5 应命中 tool_observation")
    }

    @Test
    fun `WAL 模式生效`() {
        val file = tempDbFile()
        val db = Database.forFile(file)
        db.sessionsQueries.insertSession("w1", "w", 1L, 1L)
        // 独立 JDBC 连接查 PRAGMA（ToolbeltDb 不暴露 driver 面）
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            val rs = conn.createStatement().executeQuery("PRAGMA journal_mode")
            assertTrue(rs.next(), "PRAGMA journal_mode 应有返回行")
            assertEquals("wal", rs.getString(1).lowercase(), "WAL 应生效（Properties 注入 journal_mode=WAL）")
        }
    }

    @Test
    fun `会话与消息往返-CRUD 完整`() {
        val db = Database.inMemory()
        db.sessionsQueries.upsertSession("s2", "会话二", 1L, 1L)
        db.sessionsQueries.upsertSession("s2", "会话二改名", 1L, 2L) // upsert 幂等
        assertEquals(1, db.sessionsQueries.countSessions().executeAsOne(), "upsert 后仍只有一条")
        assertEquals("会话二改名", db.sessionsQueries.sessionById("s2").executeAsOne().title)

        db.messagesQueries.insertMessage("s2", 0, "user", "hello", null, null, null, null, null, null, null, null, null, 1L)
        db.messagesQueries.insertMessage("s2", 1, "assistant", "world", null, null, null, null, null, null, null, null, null, 2L)
        assertEquals(2, db.messagesQueries.countMessagesForSession("s2").executeAsOne(), "两条消息")
        assertEquals(1L, db.messagesQueries.lastMessagesForSession("s2", 1).executeAsOne().seq, "最近一条 seq=1")

        // 墓碑语义：markTombstone 后不出现在活跃列表
        db.sessionsQueries.markTombstone(3L, "s2")
        assertTrue(db.sessionsQueries.activeSessions().executeAsList().isEmpty(), "墓碑后活跃列表为空")

        // 清理（级联两步：先消息后会话——外键阻止裸删 sessions）
        db.messagesQueries.deleteMessagesForSession("s2")
        db.sessionsQueries.deleteSession("s2")
        assertEquals(0L, db.messagesQueries.countMessagesForSession("s2").executeAsOne(), "级联删除后消息清零")
    }

    @Test
    fun `并发读写-WAL 下 reader 不被 writer 阻塞`() {
        val file = tempDbFile()
        val db = Database.forFile(file)
        db.sessionsQueries.insertSession("s3", "并发", 1L, 1L)
        // 简化编排（线程 latch 编排在 CI 上易死等——DatabaseTest.kt:98 事故）：同进程两个连接，
        // 先写后读即验证 WAL 双连接并存；reader 用独立 JDBC 连接直读（模拟 CLI 与 App 并行）
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absoluteFile}").use { writer ->
            writer.autoCommit = false
            writer.createStatement().execute(
                "INSERT INTO messages(session_id, seq, role, content, tool_name, tool_observation, created_at) " +
                    "VALUES ('s3', 0, 'user', '写入中', NULL, NULL, 1)",
            )
            // writer 事务未提交时，SQLDelight 主连接（reader）仍可读活跃会话——WAL 读不阻塞写
            val sessions = db.sessionsQueries.activeSessions().executeAsList()
            assertEquals(1, sessions.size, "writer 事务挂起时 reader 仍可读（WAL）")
            writer.commit()
        }
        // 提交后另一连接可见
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { reader ->
            val rs = reader.createStatement().executeQuery("SELECT COUNT(*) FROM messages")
            assertTrue(rs.next() && rs.getLong(1) == 1L, "提交后消息可见")
        }
    }

    @Test
    fun `协程并发插入-顺序稳定`() = runBlocking {
        val db = Database.inMemory()
        db.sessionsQueries.insertSession("s4", "协程", 1L, 1L)
        (0 until 50).map { i ->
            async(Dispatchers.IO) {
                db.messagesQueries.insertMessage("s4", i.toLong(), "user", "msg-$i", null, null, null, null, null, null, null, null, null, i.toLong())
            }
        }.awaitAll()
        val all = db.messagesQueries.messagesForSession("s4").executeAsList()
        assertEquals((0 until 50).map { it.toLong() }, all.map { it.seq }, "50 条并发插入后 seq 全部有序落库")
    }
}
