package com.jiaoay.easy_dev_toolbox.core.data.db

import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * RunStore 生命周期单测（P4-C2 openspec log-workbench 任务 1.2/1.3）：
 * - CRUD 全回环（create 幂等覆写/listRuns 倒序/rename/updateStatus/updateReportPath）；
 * - delete 级联（时间线 messages 同删——事务两步）；
 * - run ↔ 时间线关联完整性（sessionId=runId；replay 可回放该 run 的时间线）；
 * - observation 截断纪律钉死（2000 上限不放宽——证据原文走产物文件）。
 */
class RunStoreTest {

    private fun tempDbFile(): File =
        File(System.getProperty("java.io.tmpdir"), "toolbelt-runstore-${System.nanoTime()}.db")
            .apply { deleteOnExit() }

    private fun newStore() = RunStore(Database.forFile(tempDbFile()))

    @Test
    fun `create list rename update roundtrip`() {
        val store = newStore()
        store.create(id = "r1", title = "设置黑屏", logFiles = listOf("/a/main.alog.txt"), targetTime = "2026-09-19_12:00:00.000", rangeMinutes = 5, now = 100)
        store.create(id = "r2", title = "相机拉起", logFiles = listOf("/b/main.alog.txt"), now = 200)

        val runs = store.listRuns()
        assertEquals(listOf("r2", "r1"), runs.map { it.id }, "updated_at 倒序（r2 后建在前）")
        assertEquals(listOf("/a/main.alog.txt"), runs[1].logFiles, "JSON 列解码回列表")
        assertEquals(RunStore.RunStatus.RUNNING, runs[0].status)

        // rename + status + report 回写
        store.rename("r1", "设置黑屏（重命名）", now = 300)
        store.updateStatus("r1", RunStore.RunStatus.COMPLETED, now = 300)
        store.updateReportPath("r1", "/data/runs/r1/report.md", now = 300)
        val r1 = store.runById("r1")!!
        assertEquals("设置黑屏（重命名）", r1.title)
        assertEquals(RunStore.RunStatus.COMPLETED, r1.status)
        assertEquals("/data/runs/r1/report.md", r1.reportPath)
        assertEquals(300L, r1.updatedAt)

        // create 同 id 覆写（幂等 upsert）
        store.create(id = "r1", title = "覆写后", logFiles = listOf("/c.txt"), now = 400)
        assertEquals("覆写后", store.runById("r1")!!.title)
        assertEquals(2, store.listRuns().size, "覆写不增行")
    }

    @Test
    fun `delete cascades timeline messages in transaction`() {
        val file = tempDbFile()
        val db = Database.forFile(file)
        val store = RunStore(db)
        store.create(id = "r1", title = "t", logFiles = listOf("/a.txt"), now = 1)

        // 时间线行（runId = sessionId）经 SessionStore 落 messages
        val timeline = SqliteSessionStore(db)
        timeline.append("r1", ChatEntry.Assistant(id = "m1", text = "阶段完成"))
        timeline.append("r1", ChatEntry.Assistant(id = "m2", text = "报告生成"))
        assertEquals(2L, db.messagesQueries.countMessagesForSession("r1").executeAsOne())

        store.delete("r1")
        assertNull(store.runById("r1"), "run 行已删")
        assertEquals(0L, db.messagesQueries.countMessagesForSession("r1").executeAsOne(), "时间线级联同删")
        assertEquals(0L, db.messagesQueries.countMessagesForSession("r1").executeAsOne())
        assertTrue(timeline.replay("r1").isEmpty(), "replay 空（会话行也级联——messages 先删防外键阻止）")
    }

    @Test
    fun `run timeline replay association`() {
        // 任务 1.3：listRuns 每项可 replay 时间线（run ↔ 时间线一一对应）
        val db = Database.forFile(tempDbFile())
        val store = RunStore(db)
        val timeline = SqliteSessionStore(db)
        store.create(id = "run-α", title = "t", logFiles = listOf("/a.txt"), now = 1)
        timeline.append("run-α", ChatEntry.Assistant(id = "m1", text = "管线完成"))
        timeline.append("run-α", ChatEntry.ToolCall(id = "m2", toolCallId = "tc-1", toolName = "analyze_log", argsSummary = "…", state = com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState.COMPLETED))

        val runs = store.listRuns()
        assertEquals(1, runs.size)
        val entries = timeline.replay(runs.single().id)
        assertEquals(2, entries.size, "runId=replay key：时间线可回放（第二条解码恒 COMPLETED 终态——SessionStore 契约）")
        assertTrue(entries.any { it is ChatEntry.Assistant })
    }

    @Test
    fun `observation truncation discipline is not relaxed`() {
        // design D2 纪律钉：observation 2000 截断不放宽——长证据原文走产物文件路径
        assertEquals(2000, SqliteSessionStore.OBSERVATION_LIMIT, "截断上限维持 2000（证据原文不进 DB）")
    }

    @Test
    fun `bad status decodes to FAILED`() {
        // 前向兼容：未知 status 字符串（降级/手改库）解码兜底 FAILED 而非炸
        val db = Database.forFile(tempDbFile())
        db.runsQueries.insertRun(
            id = "r-x", title = "t", log_files = "[]", target_time = null, range_minutes = null,
            status = "weird-future-status", report_path = null, created_at = 1, updated_at = 1,
        )
        assertEquals(RunStore.RunStatus.FAILED, RunStore(db).runById("r-x")!!.status)
        assertNotNull(RunStore(db).runById("r-x"))
    }
}
