package com.jiaoay.easy_dev_toolbox.core.llm.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * distill.* 两件工具契约测试（P4-B 任务 3.1/3.2，零 LLM/零网络）：
 * - report_step：sink 旁路 DistillProgressPayload（面板数据源）+ render 确认文案；
 * - propose_knowledge：候选**只暂存不落库**（buffer 会话态）+ kind 校验 + sourceRefs
 *   必填（幻觉拦截承重面）+ 序号累计；
 * - catalog 装配：DISTILL_NAMES 点名注册两件、buffer null 时 propose 不注册
 *   （桥接/桌面旧链恒不装）。
 */
class DistillToolsTest {

    @Test
    fun `report step bypasses progress payload to sink`() = kotlinx.coroutines.test.runTest {
        val received = mutableListOf<ToolResult>()
        val sink = ToolResultSink.concurrentOf { _, result -> received.add(result) }
        val tool = DistillTools.ReportStepTool(sink)

        val result = tool.executeTyped(
            DistillTools.ReportStepTool.Args(
                subject = "src/A.kt",
                activeForm = "正在读取 A.kt",
                stage = "explore",
                progressHint = "已扫描 3 个文件",
            ),
        )
        assertTrue(result is ToolResult.Ok, "上报恒成功：$result")
        assertEquals(1, received.size, "sink 收到一次旁路")
        val payload = (received.first() as ToolResult.Ok).data
        assertTrue(payload is ToolPayload.DistillProgressPayload, "旁路负载是进度快照")
        assertEquals("explore", (payload as ToolPayload.DistillProgressPayload).stage)
        assertEquals("正在读取 A.kt", payload.activeForm)
    }

    @Test
    fun `propose knowledge buffers proposal without db write`() = kotlinx.coroutines.test.runTest {
        val buffer = DistillTools.ProposalBuffer()
        val received = mutableListOf<ToolResult>()
        val sink = ToolResultSink.concurrentOf { _, result -> received.add(result) }
        val tool = DistillTools.ProposeKnowledgeTool(buffer, sink)

        val ok = tool.executeTyped(
            DistillTools.ProposeKnowledgeTool.Args(
                kind = "TAG",
                title = "AlphaService",
                body = "日志模式：onStart / onStop",
                sourceRefs = listOf("src/com/example/AlphaService.kt:42"),
            ),
        )
        assertTrue(ok is ToolResult.Ok, "合法候选暂存成功")
        assertEquals(1, buffer.size)
        val proposal = buffer.snapshot().single()
        assertEquals(DistillTools.DistillProposal.Kind.TAG, proposal.kind)
        assertEquals(1, proposal.seq, "序号从 1 起")
        assertEquals(listOf("src/com/example/AlphaService.kt:42"), proposal.sourceRefs)
        // sink 旁路在 execute 三段式（executeTyped 直测不触发）——生产路径验证：
        tool.execute(
            DistillTools.ProposeKnowledgeTool.Args(
                kind = "CONSTRAINT",
                title = "门控约束",
                body = "startRenderIfNeeded 四道门控",
                sourceRefs = listOf("src/Render.kt:88"),
            ),
        )
        assertTrue(received.isNotEmpty(), "sink 收到候选旁路（面板实时流）")
        assertEquals(2, buffer.size, "execute 路径也入暂存集")
    }

    @Test
    fun `propose knowledge rejects empty source refs and bad kind`() = kotlinx.coroutines.test.runTest {
        val buffer = DistillTools.ProposalBuffer()
        val tool = DistillTools.ProposeKnowledgeTool(buffer)

        val noRefs = tool.executeTyped(
            DistillTools.ProposeKnowledgeTool.Args(
                kind = "TAG", title = "X", body = "b", sourceRefs = emptyList(),
            ),
        )
        assertTrue(noRefs is ToolResult.Err, "sourceRefs 空 = BAD_ARGS（citation 承重面）")
        assertEquals(0, buffer.size, "拒绝的候选不入暂存集")

        val badKind = tool.executeTyped(
            DistillTools.ProposeKnowledgeTool.Args(
                kind = "WHATEVER", title = "X", body = "b", sourceRefs = listOf("a.kt:1"),
            ),
        )
        assertTrue(badKind is ToolResult.Err, "未知 kind = BAD_ARGS")
        assertEquals(0, buffer.size)
    }

    @Test
    fun `catalog assembles distill tools only by explicit names with buffer`() {
        val buffer = DistillTools.ProposalBuffer()
        val context = ToolExecutionContext.withAllowedRoots(listOf(java.io.File("/tmp")))
        val registry = AgentToolCatalog.buildRegistry(
            names = DistillTools.DISTILL_NAMES,
            context = context,
            config = AgentToolCatalog.ToolContextConfig(distillBuffer = buffer),
        )
        // registry 含两件（经 execute 面接验——names 注册面由 ToolRegistry 内部形态决定，
        // 这里走 catalog 选择的可观察面：propose 工具经 buffer 注入可执行）
        assertTrue(registry.tools.size >= 2, "两件 distill 工具已注册：${registry.tools.map { it.name }}")
        assertTrue(registry.tools.any { it.name == "distill.report_step" })
        assertTrue(registry.tools.any { it.name == "distill.propose_knowledge" })

        // buffer null（桥接/旧链形态）：propose 不注册
        val registryNoBuffer = AgentToolCatalog.buildRegistry(
            names = DistillTools.DISTILL_NAMES,
            context = context,
            config = AgentToolCatalog.ToolContextConfig(distillBuffer = null),
        )
        assertTrue(registryNoBuffer.tools.none { it.name == "distill.propose_knowledge" }, "buffer null ⇒ propose 不注册（防误装）")
        assertTrue(registryNoBuffer.tools.any { it.name == "distill.report_step" }, "report_step 无 buffer 依赖恒可装")
    }

    @Test
    fun `base name sets never include distill tools`() {
        // 🔴 出站/旧链防线：distill.* 不进任何既有装配选择集——
        // 桥接与桌面旧链经 buildToolRegistry 的 names 永远选不到蒸馏件
        assertTrue("distill.report_step" !in DecryptTools.MEMORY_NAMES)
        assertTrue("distill.propose_knowledge" !in DecryptTools.MEMORY_NAMES)
        assertTrue("distill.report_step" !in KnowledgeTools.KNOWLEDGE_NAMES)
        assertTrue("distill.propose_knowledge" !in KnowledgeTools.KNOWLEDGE_NAMES)
        // 蒸馏名集自身只含两件（防止未来误并）
        assertEquals(setOf("distill.report_step", "distill.propose_knowledge"), DistillTools.DISTILL_NAMES)
    }
}
