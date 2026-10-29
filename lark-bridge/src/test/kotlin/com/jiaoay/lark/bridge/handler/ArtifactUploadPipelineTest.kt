// 产物回传管线级测试（2026-09-12 agent-artifact-return）：runAgentPipeline 内接线——
// 成功回合解析标记 → 上传 → summary 剥离；关闭开关 / 失败回合 / 无标记 ⇒ 原样。
// 与 ArtifactsTest（单元级）互补：本文件走真实 withArtifactsDelivered 接线（含 ctx 装配）。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.agent.AgentRequest
import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.agent.AgentRunResult
import com.jiaoay.lark.bridge.agent.ProgressAccumulator
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.exec.AgentLine
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.exec.SensitiveFilePolicy
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ArtifactUploadPipelineTest {

    private fun textMsg(): InboundMessage = msg(messageId = "om_artifact", chatId = "oc_artifact", text = "ai 分析这个日志")

    /** agent 假件：固定 summary（可含产物标记行）。 */
    private class FixedAgentRunner(private val summary: String) : AgentRunner {
        override val backendId: String = "stub"
        override val isAvailable: Boolean = true
        override suspend fun run(request: AgentRequest, onLine: (AgentLine) -> Unit): AgentRunResult =
            AgentRunResult(exitCode = 0, summary = summary)
        override suspend fun resetSession(sessionKey: String): Boolean = true
    }

    private fun ctxOf(
        config: BridgeConfig,
        agent: AgentRunner,
        reply: RecordingReplySink,
        progress: ProgressAccumulator,
    ): RequestContext = RequestContext(
        message = textMsg(),
        config = config,
        route = null,
        reply = reply,
        progress = progress,
        log = BridgeLog.Silent,
        agent = agent,
        processes = ProcessRegistry(config.runtime.maxConcurrentAgents),
    )

    private fun workspaceConfig(root: File, artifactsEnabled: Boolean = true): BridgeConfig = testConfig().let {
        it.copy(
            workspace = it.workspace.copy(root = root.absolutePath),
            agent = it.agent.copy(
                artifacts = it.agent.artifacts.copy(enabled = artifactsEnabled),
            ),
        )
    }

    @Test
    fun `成功回合的标记行触发文件上传且 summary 剥离`() = runBlocking {
        val ws = Files.createTempDirectory("artifact-pipeline").toFile()
        val report = File(ws, "analysis_result_demo_20260912.md").apply { writeText("# 报告正文") }
        val agent = FixedAgentRunner("分析完成，结论见产物。\n[产物] ${report.absolutePath}")
        val reply = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val ctx = ctxOf(workspaceConfig(ws), agent, reply, ProgressAccumulator())

        val outcome = withTimeout(5_000) { runAgentPipeline(ctx, "ai 分析这个日志", TaskKind.AGENT) }

        assertEquals(1, reply.files.size, "恰一次文件出站")
        assertEquals(report.toPath(), reply.files[0].first)
        assertTrue(!outcome.summary.contains("[产物]"), "summary 须剥离标记行：${outcome.summary}")
        assertTrue(outcome.summary.contains("结论见产物"), "正文保留：${outcome.summary}")
        assertEquals(listOf("analysis_result_demo_20260912.md <- ${report.absolutePath}"), outcome.attachments)
    }

    @Test
    fun `关闭开关后零上传且正文原样`() = runBlocking {
        val ws = Files.createTempDirectory("artifact-off").toFile()
        val report = File(ws, "a.md").apply { writeText("x") }
        val agent = FixedAgentRunner("结论。\n[产物] ${report.absolutePath}")
        val reply = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val ctx = ctxOf(workspaceConfig(ws, artifactsEnabled = false), agent, reply, ProgressAccumulator())

        val outcome = withTimeout(5_000) { runAgentPipeline(ctx, "prompt", TaskKind.AGENT) }

        assertTrue(reply.files.isEmpty(), "关闭开关 ⇒ 不上传")
        assertTrue(outcome.summary.contains("[产物]"), "关闭开关 ⇒ 标记行原样保留：${outcome.summary}")
        assertTrue(outcome.attachments.isEmpty())
    }

    @Test
    fun `失败回合不处理产物`() = runBlocking {
        val ws = Files.createTempDirectory("artifact-fail").toFile()
        val report = File(ws, "a.md").apply { writeText("x") }
        val agent = object : AgentRunner {
            override val backendId: String = "stub"
            override val isAvailable: Boolean = true
            override suspend fun run(request: AgentRequest, onLine: (AgentLine) -> Unit): AgentRunResult =
                AgentRunResult(exitCode = 1, summary = "boom\n[产物] ${report.absolutePath}", errorCode = "agent_exit_1")
            override suspend fun resetSession(sessionKey: String): Boolean = true
        }
        val reply = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val ctx = ctxOf(workspaceConfig(ws), agent, reply, ProgressAccumulator())

        val outcome = withTimeout(5_000) { runAgentPipeline(ctx, "prompt", TaskKind.AGENT) }

        assertTrue(reply.files.isEmpty(), "失败回合不上传产物")
        assertTrue(outcome.summary.contains("[产物]"), "失败回合标记行原样：${outcome.summary}")
    }

    @Test
    fun `无标记行时零副作用`() = runBlocking {
        val ws = Files.createTempDirectory("artifact-plain").toFile()
        val agent = FixedAgentRunner("普通回复")
        val reply = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val ctx = ctxOf(workspaceConfig(ws), agent, reply, ProgressAccumulator())

        val outcome = withTimeout(5_000) { runAgentPipeline(ctx, "prompt", TaskKind.AGENT) }

        assertTrue(reply.files.isEmpty())
        assertEquals("普通回复", outcome.summary.trim())
        assertTrue(outcome.attachments.isEmpty())
    }

    @Test
    fun `沙箱外路径不还原正文之外的内容也不上传`() = runBlocking {
        val ws = Files.createTempDirectory("artifact-sandbox").toFile()
        val outside = File(Files.createTempDirectory("outside-ws").toFile(), "evil.md").apply { writeText("x") }
        val agent = FixedAgentRunner("结论。\n[产物] ${outside.absolutePath}")
        val reply = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val ctx = ctxOf(workspaceConfig(ws), agent, reply, ProgressAccumulator())

        val outcome = withTimeout(5_000) { runAgentPipeline(ctx, "prompt", TaskKind.AGENT) }

        assertTrue(reply.files.isEmpty(), "沙箱外不得上传")
        assertTrue(outcome.summary.contains("未能回传"), "失败条目还原：${outcome.summary}")
        assertTrue(outcome.summary.contains(outside.absolutePath), "用户至少能看到绝对路径")
        // 被拒清单落审计接线（2026-09-12 取证盲区补齐）：拒绝原因须写进 outcome 供审计 rejectedArtifacts 落盘
        assertEquals(1, outcome.rejectedArtifacts.size, "被拒条目数")
        assertTrue(
            outcome.rejectedArtifacts[0].contains(outside.absolutePath) &&
                outcome.rejectedArtifacts[0].contains(SensitiveFilePolicy.OPAQUE_DENIED_HINT),
            "被拒条目含路径与统一 OPAQUE 拒绝文案：${outcome.rejectedArtifacts[0]}",
        )
    }

    @Test
    fun `数据根内的敏感文件即使在工作区内也被拒（管线级接线锚）`() = runBlocking {
        // 布景：工作区根 = 数据根（用户把 workspace.root 指到 ~/.toolbelt 的常见形态）。
        // 🔴 文件名必须**不在凭据族名单**里（如 audit.jsonl）——若用 bridge-config.json，
        //    判据 2 会独立兜住它，断言恒真、判据 1/透传链删掉也照样绿（首版就是这么写错的）。
        val ws = Files.createTempDirectory("artifact-secret").toFile()
        val inside = File(ws, "audit.jsonl").apply { writeText("""{"prompt":"运维内部记录"}""") }
        val agent = FixedAgentRunner("结论。\n[产物] ${inside.absolutePath}")
        val reply = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val ctx = ctxOf(workspaceConfig(ws), agent, reply, ProgressAccumulator())

        // 🔴 homeDataRoot 经 runAgentPipeline → withArtifactsDelivered → deliverArtifacts 透传；
        //    沙箱本身放行该文件（普通 .jsonl 名），**只有**数据根判据能拦 ⇒ 透传链断即红。
        val outcome = withTimeout(5_000) {
            runAgentPipeline(ctx, "prompt", TaskKind.AGENT, homeDataRoot = ws)
        }

        assertTrue(reply.files.isEmpty(), "数据根内文件不得经标记链外发（同宽旁路）")
        assertTrue(outcome.summary.contains("敏感"), "拒绝原因须可见：${outcome.summary}")
        assertEquals(1, outcome.rejectedArtifacts.size)
        assertTrue(outcome.rejectedArtifacts[0].contains("敏感"), "被拒条目写入审计：${outcome.rejectedArtifacts[0]}")
    }

    @Test
    fun `homeDataRoot 缺省为 null 时普通产物照常上传（默认参数不误伤）`() = runBlocking {
        val ws = Files.createTempDirectory("artifact-noroot").toFile()
        val report = File(ws, "report.md").apply { writeText("# 正常产物") }
        val agent = FixedAgentRunner("结论。\n[产物] ${report.absolutePath}")
        val reply = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val ctx = ctxOf(workspaceConfig(ws), agent, reply, ProgressAccumulator())

        // 不传 homeDataRoot（存量调用方形态）
        withTimeout(5_000) { runAgentPipeline(ctx, "prompt", TaskKind.AGENT) }

        assertEquals(1, reply.files.size, "根未知时普通产物不受影响")
    }
}
