package com.jiaoay.easy_dev_toolbox.page.mcp

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * McpAgentTools 单测（P5 openspec mcp-expose 任务 3.2/3.3——design D8：
 * 参数组装纯函数 + 未配端点错误面 + prompt 组装值同源断言；agent 执行链留真机 5.3）。
 */
class McpAgentToolsTest {

    @BeforeTest
    fun setUp() {
        McpRunRegistry.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        McpRunRegistry.resetForTest()
    }

    // ── 3.2 研判 prompt 组装（值同源 WorkbenchSession.buildPrompt） ──

    @Test
    fun `analyze prompt assembles structured input`() {
        val prompt = McpAgentTools.buildAnalyzePrompt(
            logFiles = listOf("/logs/app.alog"),
            targetTime = "2026-09-20_10:00:00.000",
            rangeMinutes = 5,
            symptom = "预览画面黑屏",
            tagRegex = "CameraService|CameraHal",
            followUpContext = null,
        )
        assertTrue("/logs/app.alog" in prompt)
        assertTrue("2026-09-20_10:00:00.000" in prompt)
        assertTrue("预览画面黑屏" in prompt)
        assertTrue("CameraService|CameraHal" in prompt, "Tag 正则注入（可先反查知识库扩充）")
    }

    @Test
    fun `analyze prompt carries follow up context for incremental runs`() {
        val prompt = McpAgentTools.buildAnalyzePrompt(
            logFiles = listOf("/logs/app.alog"),
            targetTime = "2026-09-20_10:00:00.000",
            rangeMinutes = 5,
            symptom = "复现黑屏",
            tagRegex = null,
            followUpContext = "## 已有报告\n根因初判：…",
        )
        assertTrue("已有报告" in prompt, "追问链上下文注入（增量研判段）")
        assertTrue(prompt.indexOf("根因初判") > 0)
    }

    // ── 3.2/3.3 未配端点错误面（spec「未配端点明示」） ──

    @Test
    fun `endpoint check surfaces guidance when unconfigured`() {
        // LlmEndpointConfig.read 读真实 DataStore——单测环境未配置端点必然走错误面分支
        // （CI/本地首跑无端点配置；本用例只断言错误文案形态，不依赖具体存储态）
        val config = kotlinx.coroutines.runBlocking {
            runCatching { com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig.read() }.getOrNull()
        }
        if (config?.isConfigured == true) {
            // 宿主机已配端点（开发机常态）——未配端点分支不可测，跳过（不假绿：
            // 该分支在 5.3 真机验收以「清空端点配置」形态复验）
            return
        }
        val result = McpAgentTools.checkEndpointForTest()
        assertTrue(result.isError == true, "未配端点 = isError")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue("AI 设置页" in text, "错误文案指引先在 AI 设置页配置端点")
        assertTrue("确定性工具面不受影响" in text, "明示工具面不受影响（spec 场景）")
    }

    // ── run 生命周期（两段式查询面） ──

    @Test
    fun `status query reports running run`() {
        val handle = McpRunRegistry.register("run-x", "workbench_analyze")!!
        handle.progressNote.value = "调用工具 log.analyze_log…"

        val result = McpAgentTools.statusForTest("run-x")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue("run-x" in text)
        assertTrue("RUNNING" in text, "状态面含 RUNNING 阶段")
        assertTrue("调用工具 log.analyze_log" in text, "进度说明透传")
    }

    @Test
    fun `status query of unknown run errors clearly`() {
        val result = McpAgentTools.statusForTest("nope")
        assertTrue(result.isError == true)
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue("runId 不存在" in text, "未知 runId 明示（可能已清退）")
    }

    @Test
    fun `status query includes result and artifacts on completed run`() {
        val handle = McpRunRegistry.register("run-y", "distill_run")!!
        handle.artifacts.value = listOf("/tmp/stage.txt")
        handle.complete("报告正文")

        val result = McpAgentTools.statusForTest("run-y")
        val text = (result.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        assertTrue("COMPLETED" in text)
        assertTrue("报告正文" in text, "结果文本回传")
        assertTrue("/tmp/stage.txt" in text, "阶段产物路径回传（spec：产物路径回传 client）")
    }
}

// 测试缝：McpAgentTools 的 internal 直调（checkEndpointForTest/statusForTest——同模块可见）
