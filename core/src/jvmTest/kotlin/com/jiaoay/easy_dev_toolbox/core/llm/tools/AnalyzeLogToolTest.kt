// P4-A · analyze_log 工具化契约验收（2026-09-19）：
// 围栏拒绝 / 明文全跑产物登记 / 空结果中止带回捞 hint / RETAG 单级重入。
// （管线本体语义由 log-tools 的 LogAnalysisPipelineTest 背书，此处只测工具面契约。）
package com.jiaoay.easy_dev_toolbox.core.llm.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AnalyzeLogToolTest {

    private fun tempLog(content: String): File =
        kotlin.io.path.createTempDirectory("edt-analyze").resolve("main_test.txt").toFile()
            .apply { writeText(content) }

    private fun contextOf(vararg files: File): ToolExecutionContext =
        ToolExecutionContext(files.toList())

    @Test
    fun `围栏外路径拒绝-DENIED 文案`() = runBlocking {
        val tool = AnalyzeLogTool(contextOf(tempLog("x\n")), privateKeyProvider = { "" })
        val result = tool.executeTyped(
            AnalyzeLogTool.Args(
                log_path = "/etc/passwd",
                target_time = "2026-09-19_10:30:00.000",
                range_minutes = 5,
            ),
        )
        assertTrue(result is ToolResult.Err, "围栏外路径必须拒绝: $result")
        assertEquals(ToolResult.ErrCode.DENIED, (result as ToolResult.Err).code)
    }

    @Test
    fun `明文全跑-成功产物登记围栏`() = runBlocking {
        val log = tempLog(
            "09-19 10:30:01.000 D/CameraManager: a\n" +
                "09-19 10:30:02.000 D/CameraManager: b\n" +
                "09-19 10:30:03.000 D/CameraManager: c\n" +
                "09-19 10:30:04.000 D/CameraManager: d\n" +
                "09-19 10:30:05.000 D/CameraManager: e\n",
        )
        val context = contextOf(log)
        val tool = AnalyzeLogTool(context, privateKeyProvider = { "" })
        val result = tool.executeTyped(
            AnalyzeLogTool.Args(
                log_path = log.absolutePath,
                target_time = "2026-09-19_10:30:00.000",
                range_minutes = 5,
                tag_regex = "CameraManager",
                tag_name = "camera",
            ),
        )
        assertTrue(result is ToolResult.Ok, "全跑应成功: $result")
        result as ToolResult.Ok
        // 产物精确引用 + 围栏登记（后续 preview/read 可访问）
        assertEquals(1, result.artifacts.size)
        val artifact = result.artifacts.first()
        assertTrue(artifact.path.endsWith("filter-tag_camera.txt"), "最终产物为 Tag 过滤文件: ${artifact.path}")
        assertTrue(context.isAllowed(File(artifact.path)), "产物登记进围栏")
        assertTrue(result.summary.contains("TAG_FILTER"), "附注含阶段标记: ${result.summary}")
    }

    @Test
    fun `空结果中止-hint 指向回捞协议`() = runBlocking {
        val log = tempLog("09-19 12:00:01.000 D/Some: far away\n09-19 12:00:02.000 D/Some: x\n")
        val tool = AnalyzeLogTool(contextOf(log), privateKeyProvider = { "" })
        val result = tool.executeTyped(
            AnalyzeLogTool.Args(
                log_path = log.absolutePath,
                target_time = "2026-09-19_10:30:00.000",
                range_minutes = 1,
            ),
        )
        assertTrue(result is ToolResult.Err, "空结果必须中止: $result")
        result as ToolResult.Err
        assertEquals(ToolResult.ErrCode.FAILED, result.code)
        assertTrue(result.message!!.contains("TIME_FILTER"), "中止消息带阶段标记")
        assertTrue(result.renderText().contains("回捞"), "hint 指向回捞协议: ${result.renderText()}")
    }

    @Test
    fun `RETAG 单级重入-空 tag_regex 拒绝`() = runBlocking {
        val timeFiltered = tempLog("09-19 10:30:01.000 D/A: x\n")
        val tool = AnalyzeLogTool(contextOf(timeFiltered), privateKeyProvider = { "" })
        val result = tool.executeTyped(
            AnalyzeLogTool.Args(
                log_path = timeFiltered.absolutePath,
                target_time = "2026-09-19_10:30:00.000",
                range_minutes = 5,
                stage = AnalyzeLogTool.STAGE_RETAG,
                tag_regex = "", // 空——必须 BAD_ARGS 拒绝
            ),
        )
        assertTrue(result is ToolResult.Err)
        assertEquals(ToolResult.ErrCode.BAD_ARGS, (result as ToolResult.Err).code)
    }

    @Test
    fun `RETAG 单级重入-成功路径`() = runBlocking {
        val timeFiltered = tempLog(
            "09-19 10:30:01.000 D/CameraManager: a\n" +
                "09-19 10:30:02.000 D/CameraManager: b\n" +
                "09-19 10:30:03.000 D/CameraManager: c\n" +
                "09-19 10:30:04.000 D/CameraManager: d\n" +
                "09-19 10:30:05.000 D/CameraManager: e\n",
        )
        val context = contextOf(timeFiltered)
        val tool = AnalyzeLogTool(context, privateKeyProvider = { "" })
        val result = tool.executeTyped(
            AnalyzeLogTool.Args(
                log_path = timeFiltered.absolutePath,
                target_time = "2026-09-19_10:30:00.000",
                range_minutes = 5,
                stage = AnalyzeLogTool.STAGE_RETAG,
                tag_regex = "CameraManager",
                tag_name = "camera-retry",
            ),
        )
        assertTrue(result is ToolResult.Ok, "RETAG 应成功: $result")
        result as ToolResult.Ok
        assertTrue(result.artifacts.first().path.endsWith("filter-tag_camera-retry.txt"))
    }
}
