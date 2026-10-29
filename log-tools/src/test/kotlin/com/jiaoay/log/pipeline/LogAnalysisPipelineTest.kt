// P4-A · 确定性管线验收（2026-09-19）：
//
// 1. 明文 .txt 全管线（时间过滤 → 环境扫描 → Tag 过滤）逐级产物断言；
// 2. 空结果守护（< 5 行中止 + 阶段标记）；
// 3. Tag 过滤 0 行中止；
// 4. 坏正则不炸管线（环境扫描附注观测语义）；
// 5. countLines 流式计数（含末行无换行符形态）；
// 6. 环境污染命中附注（不中止）。
package com.jiaoay.log.pipeline

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogAnalysisPipelineTest {

    private fun tempLog(content: String, name: String = "main_test.txt"): File =
        kotlin.io.path.createTempDirectory("edt-pipeline").resolve(name).toFile()
            .apply { writeText(content) }

    /** 造三行带时间戳的明文日志（格式对齐 LogTimeFilter 的 isTimeLine 判定） */
    private fun logLines(vararg lines: Pair<String, String>): String =
        lines.joinToString("\n") { (time, body) -> "$time $body" }

    private val targetTime = "2026-09-19_10:30:00.000"

    @Test
    fun `明文全管线-逐级产物精确路径`() {
        val log = tempLog(
            logLines(
                "09-19 10:29:50.000" to "D/FloorCenterManager: init ok",
                "09-19 10:30:05.000" to "E/CameraManager: camera route failed",
                "09-19 10:30:10.000" to "W/CameraManager: retry 1",
                "09-19 10:30:15.000" to "W/CameraManager: retry 2",
                "09-19 10:30:20.000" to "W/CameraManager: retry 3",
                "09-19 10:30:25.000" to "D/CameraManager: done",
            ) + "\n",
        )
        val result = LogAnalysisPipeline.run(
            LogAnalysisPipeline.Request(
                logFile = log,
                privateKey = "",
                targetTime = targetTime,
                rangeMinutes = 5,
                tagRegex = "CameraManager|FloorCenterManager",
                tagName = "camera",
                envPattern = "mock|debug_signal",
            ),
        )
        assertTrue(result is LogAnalysisPipeline.StageResult.Ok, "全管线应成功: $result")
        // 最终产物 = Tag 过滤文件（.filter-tag_camera.txt）
        assertTrue(
            (result as LogAnalysisPipeline.StageResult.Ok).artifact!!.name.endsWith(".filter-tag_camera.txt"),
            "产物应为 Tag 过滤文件，实际: ${result.artifact!!.name}",
        )
        assertTrue(result.note.contains("Tag 过滤完成"), "最终附注含 Tag 过滤结果: ${result.note}")
    }

    @Test
    fun `时间过滤空结果守护-少行中止带回捞提示`() {
        // 窗口命中但产物 < 5 行（2 行命中）——管线少行中止口径
        val log = tempLog(
            logLines(
                "09-19 12:00:01.000" to "D/SomeTag: daytime log",
                "09-19 12:00:02.000" to "D/SomeTag: another",
            ) + "\n",
        )
        val result = LogAnalysisPipeline.run(
            LogAnalysisPipeline.Request(
                logFile = log,
                privateKey = "",
                targetTime = "2026-09-19_12:00:30.000",
                rangeMinutes = 1,
                tagRegex = "SomeTag",
                tagName = "t",
            ),
        )
        assertTrue(result is LogAnalysisPipeline.StageResult.Abort, "空结果应中止: $result")
        result as LogAnalysisPipeline.StageResult.Abort
        assertEquals(LogAnalysisPipeline.Stage.TIME_FILTER, result.stage, "中止在时间过滤级")
        assertTrue(result.reason.contains("回捞"), "中止原因须含回捞指引: ${result.reason}")
    }

    @Test
    fun `时间过滤零命中-引擎守护先于少行守护中止`() {
        // 目标时间远离日志内容（10:30 vs 日志在 12:xx）——引擎零命中守护报失败
        // （空产物假成功在引擎侧即拒绝），管线以 Abort 透传
        val log = tempLog(
            logLines(
                "09-19 12:00:01.000" to "D/SomeTag: daytime log",
                "09-19 12:00:02.000" to "D/SomeTag: another",
            ) + "\n",
        )
        val result = LogAnalysisPipeline.run(
            LogAnalysisPipeline.Request(
                logFile = log,
                privateKey = "",
                targetTime = targetTime,
                rangeMinutes = 1,
                tagRegex = "SomeTag",
                tagName = "t",
            ),
        )
        assertTrue(result is LogAnalysisPipeline.StageResult.Abort, "零命中应中止: $result")
        result as LogAnalysisPipeline.StageResult.Abort
        assertEquals(LogAnalysisPipeline.Stage.TIME_FILTER, result.stage, "中止在时间过滤级")
        assertTrue(result.reason.contains("区间未命中任何时间行"), "中止原因须指明零命中: ${result.reason}")
    }

    @Test
    fun `Tag 过滤 0 行中止-提示反查落盘形式`() {
        val log = tempLog(
            logLines(
                "09-19 10:30:01.000" to "D/RealTag: real content",
                "09-19 10:30:02.000" to "D/RealTag: more",
                "09-19 10:30:03.000" to "D/RealTag: even more",
                "09-19 10:30:04.000" to "D/RealTag: lines",
                "09-19 10:30:05.000" to "D/RealTag: to pass",
                "09-19 10:30:06.000" to "D/RealTag: guard",
            ) + "\n",
        )
        val result = LogAnalysisPipeline.run(
            LogAnalysisPipeline.Request(
                logFile = log,
                privateKey = "",
                targetTime = targetTime,
                rangeMinutes = 5,
                tagRegex = "NoSuchTagInLog", // 日志里不存在的 TAG
                tagName = "missing",
            ),
        )
        assertTrue(result is LogAnalysisPipeline.StageResult.Abort, "0 行应中止: $result")
        result as LogAnalysisPipeline.StageResult.Abort
        assertEquals(LogAnalysisPipeline.Stage.TAG_FILTER, result.stage)
        assertTrue(result.reason.contains("lookup_tag"), "中止原因须指向 knowledge.lookup_tag: ${result.reason}")
    }

    @Test
    fun `环境污染命中-附注不中止`() {
        val timeFiltered = tempLog(
            logLines(
                "09-19 10:30:01.000" to "D/MockSignal: onReceive: action=xxx.mock.yyy",
                "09-19 10:30:02.000" to "D/Other: normal",
            ) + "\n",
        )
        val result = LogAnalysisPipeline.runEnvScan(timeFiltered, "\\.mock\\.")
        assertTrue(result is LogAnalysisPipeline.StageResult.Ok, "环境扫描永不中止: $result")
        result as LogAnalysisPipeline.StageResult.Ok
        assertTrue(result.note.contains("环境污染模式"), "命中时附注须含污染模式: ${result.note}")
    }

    @Test
    fun `坏正则不炸管线-按零命中处理`() {
        val timeFiltered = tempLog("09-19 10:30:01.000 D/A: content\n")
        val result = LogAnalysisPipeline.runEnvScan(timeFiltered, "([unclosed")
        assertTrue(result is LogAnalysisPipeline.StageResult.Ok, "坏正则按 0 命中不中止")
        result as LogAnalysisPipeline.StageResult.Ok
        assertTrue(result.note.contains("干净"), "坏正则按零命中=干净通过: ${result.note}")
    }

    @Test
    fun `countLines-末行无换行符补一`() {
        val f = tempLog("line1\nline2\nline3") // 无尾换行
        assertEquals(3L, LogAnalysisPipeline.countLines(f))
        val g = tempLog("line1\nline2\nline3\n")
        assertEquals(3L, LogAnalysisPipeline.countLines(g))
        val empty = tempLog("")
        assertEquals(0L, LogAnalysisPipeline.countLines(empty))
    }

    @Test
    fun `tagRegex 可省-跳过 Tag 过滤级`() {
        val log = tempLog(
            logLines(
                "09-19 10:29:59.000" to "D/A: x",
                "09-19 10:30:01.000" to "D/A: y",
                "09-19 10:30:02.000" to "D/A: z",
                "09-19 10:30:03.000" to "D/A: w",
                "09-19 10:30:04.000" to "D/A: v",
            ) + "\n",
        )
        val result = LogAnalysisPipeline.run(
            LogAnalysisPipeline.Request(
                logFile = log,
                privateKey = "",
                targetTime = targetTime,
                rangeMinutes = 5,
                // tagRegex = null —— 跳过 Tag 过滤
            ),
        )
        assertTrue(result is LogAnalysisPipeline.StageResult.Ok)
        result as LogAnalysisPipeline.StageResult.Ok
        assertTrue(
            result.artifact!!.name.contains("filter-time"),
            "无 tagRegex 时最终产物 = 时间过滤文件: ${result.artifact!!.name}",
        )
    }
}
