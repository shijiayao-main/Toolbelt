package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.data.db.SqliteSessionStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 数据面程序化端到端（P4-C2 openspec log-workbench 任务 7.2）：
 * 合成日志样本 → 管线全跑 → 产物挂载 → run 落库 → replay 时间线 → 报告文件生成——
 * 单测背书全链（真机 LLM 链待维护者端点，本测只验确定性数据面）。
 *
 * 🔴 全部使用注入文件路径（Database.forFile 临时库）——不触 Database.database()
 * 真单例（VM 直测纪律同款）。
 */
class WorkbenchEndToEndTest {

    @Test
    fun `合成样本管线全跑到 run 落库与时间线回放`() {
        val dir = kotlin.io.path.createTempDirectory("wb-e2e").toFile()

        // ── 1. 合成日志样本（明文 .txt——管线 Stage A 直过；行格式对齐 LogTimeFilter） ──
        val logFile = File(dir, "09-19_10-29-50.alog.txt").apply {
            // 注：管线 runDecrypt 只认 .alog/.xlog/.txt 后缀；用 .txt 明文直过解密级
            delete()
            writeText(
                buildString {
                    (0..30).forEach { i ->
                        appendLine("09-19 10:${(29 + i / 60).toString().padStart(2, '0')}:${(i % 60).toString().padStart(2, '0')}.000 D/FloorCenterManager: init step $i")
                    }
                    appendLine("09-19 10:30:05.000 E/CameraManager: camera route failed")
                    appendLine("09-19 10:30:10.000 W/CameraManager: retry 1")
                    appendLine("09-19 10:30:15.000 W/CameraManager: retry 2")
                    appendLine("09-19 10:31:00.000 D/CameraManager: done")
                },
            )
        }

        // ── 2. 管线全跑（确定性——时间过滤 ±1 分钟窗口） ──
        val pipelineResult = com.jiaoay.log.pipeline.LogAnalysisPipeline.run(
            com.jiaoay.log.pipeline.LogAnalysisPipeline.Request(
                logFile = logFile,
                privateKey = "",
                targetTime = "2026-09-19_10:30:05.000",
                rangeMinutes = 1,
                tagRegex = "CameraManager",
                tagName = "camera",
            ),
        )
        assertTrue(
            pipelineResult is com.jiaoay.log.pipeline.LogAnalysisPipeline.StageResult.Ok,
            "管线应全绿完成，实际：$pipelineResult",
        )
        val finalArtifact = (pipelineResult as com.jiaoay.log.pipeline.LogAnalysisPipeline.StageResult.Ok).artifact
        assertNotNull(finalArtifact, "Tag 过滤产物应存在")

        // ── 3. run 落库（RunStore 生命周期） ──
        val dbFile = File(dir, "e2e.db")
        val db = Database.forFile(dbFile)
        val store = RunStore(db)
        val runId = "run-e2e-1"
        store.create(
            id = runId,
            title = "e2e",
            logFiles = listOf(logFile.absolutePath),
            targetTime = "2026-09-19_10:30:05.000",
            rangeMinutes = 1,
            status = RunStore.RunStatus.COMPLETED,
        )
        store.updateReportPath(runId, File(dir, "$runId-report.md").absolutePath)
        val summary = store.runById(runId)
        assertNotNull(summary, "run 应可查回")
        assertEquals(RunStore.RunStatus.COMPLETED, summary.status)

        // ── 4. 时间线落库 + replay（sessionId = runId 关联） ──
        val timeline = SqliteSessionStore(db)
        val entry = com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry.ToolCall(
            id = "e2e-entry-1",
            toolCallId = "call-1",
            toolName = "analyze_log",
            argsSummary = "管线直跑",
            state = com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState.COMPLETED,
            observation = "Tag 过滤完成，4 行",
            artifacts = listOf(
                com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChip(
                    path = finalArtifact!!.absolutePath,
                    kind = com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChipKind.FILE,
                ),
            ),
        )
        timeline.append(runId, entry)
        val replayed = timeline.replay(runId)
        assertEquals(1, replayed.size, "replay 应回放 1 条时间线")
        val replayedToolCall = replayed.first() as com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry.ToolCall
        assertEquals(finalArtifact.absolutePath, replayedToolCall.artifacts.first().path, "产物路径经时间线回放恒等")

        // ── 5. 报告文件生成（reportFileOf 落点 + 内容可读回） ──
        val reportFile = WorkbenchViewModel.reportFileOf(runId)
        // run 专属目录在应用数据目录下（FileUtils.getAppDataDir 域——e2e 断言路径结构即可）
        assertTrue(
            reportFile.absolutePath.contains("logworkbench${File.separator}runs${File.separator}$runId"),
            "报告须落 run 专属目录：${reportFile.absolutePath}",
        )
        reportFile.parentFile?.mkdirs()
        reportFile.writeText("# 日志分析报告\n## 结论\n——e2e 端到端样本\n")
        assertTrue(reportFile.exists() && reportFile.readText().contains("e2e 端到端样本"))

        // ── 6. run 删除级联（时间线行一并清——RunStore.delete 事务） ──
        store.delete(runId)
        assertEquals(0, store.listRuns().size, "run 列表应清空")
        assertEquals(0, timeline.replay(runId).size, "时间线应级联清空")
    }
}
