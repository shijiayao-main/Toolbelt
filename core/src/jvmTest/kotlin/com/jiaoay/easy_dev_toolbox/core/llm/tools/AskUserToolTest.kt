package com.jiaoay.easy_dev_toolbox.core.llm.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runTest

/**
 * AskUserTool 单测（Quest 域 change 1 任务 1.4——openspec add-flow-ask-user-and-flows-schema）。
 *
 * 覆盖：软反馈三态（已答/跳过/超时形态）+ BAD_ARGS 校验 + 取消穿透 + sink 旁路。
 * fake gate 直测 executeTyped（AgentToolMiddlewareTest 配方——不触 Koog 装配面）。
 */
class AskUserToolTest {

    private fun tool(gate: UserQuestionGate, sink: ToolResultSink = ToolResultSink.NOOP) =
        AskUserTool(gate, sink)

    private fun args(
        question: String = "开始分析前我还需要哪些信息？",
        fields: List<AskUserTool.FieldArg> = emptyList(),
        allowSkip: Boolean = false,
    ) = AskUserTool.Args(question = question, fields = fields, allowSkip = allowSkip)

    @Test
    fun `answered form fields render as label value list`() = runTest {
        val gate = UserQuestionGate { request ->
            assertEquals(2, request.fields.size)
            AskAnswer(
                mapOf(
                    "log_file" to "/tmp/app.log",
                    "time_point" to "2026-09-20_10:00:00.000",
                ),
            )
        }
        val result = tool(gate).executeTyped(
            args(
                fields = listOf(
                    AskUserTool.FieldArg("log_file", "日志文件路径", "FILE_PATH"),
                    AskUserTool.FieldArg("time_point", "问题时间点", "TEXT"),
                ),
            ),
        )
        val ok = result as ToolResult.Ok
        assertEquals(
            "用户回答：\n- 日志文件路径: /tmp/app.log\n- 问题时间点: 2026-09-20_10:00:00.000",
            ok.summary,
        )
    }

    @Test
    fun `answered free text flows back verbatim`() = runTest {
        val gate = UserQuestionGate { _ ->
            AskAnswer(mapOf(AskUserTool.PROMPT_ANSWER_KEY to "症状是相机拉起失败"))
        }
        val result = tool(gate).executeTyped(args())
        assertEquals("用户回答：症状是相机拉起失败", (result as ToolResult.Ok).summary)
    }

    @Test
    fun `skip renders soft feedback`() = runTest {
        val gate = UserQuestionGate { _ -> AskAnswer(emptyMap(), skipped = true) }
        val result = tool(gate).executeTyped(args(allowSkip = true))
        assertEquals("用户表示暂无更多信息，请基于现有信息继续", (result as ToolResult.Ok).summary)
    }

    @Test
    fun `timeout-like skip on allowSkip false renders timeout soft feedback`() = runTest {
        // gate 实现侧软超时回 skipped=true（见 AskUserTool 类头红线注）——allowSkip=false
        // 时文案必须是超时形态而非「暂无补充」
        val gate = UserQuestionGate { _ -> AskAnswer(emptyMap(), skipped = true) }
        val result = tool(gate).executeTyped(args(allowSkip = false))
        assertEquals("用户未在限定时间内回答，请基于现有信息继续或先收尾", (result as ToolResult.Ok).summary)
    }

    @Test
    fun `blank question rejected as bad args`() = runTest {
        val gate = UserQuestionGate { _ -> AskAnswer(emptyMap()) }
        val result = tool(gate).executeTyped(args(question = "  "))
        assertEquals(ToolResult.ErrCode.BAD_ARGS, (result as ToolResult.Err).code)
    }

    @Test
    fun `illegal field type rejected as bad args`() = runTest {
        val gate = UserQuestionGate { _ -> AskAnswer(emptyMap()) }
        val result = tool(gate).executeTyped(
            args(fields = listOf(AskUserTool.FieldArg("x", "标签", "DATE"))),
        )
        assertEquals(ToolResult.ErrCode.BAD_ARGS, (result as ToolResult.Err).code)
    }

    @Test
    fun `choice field without options rejected`() = runTest {
        val gate = UserQuestionGate { _ -> AskAnswer(emptyMap()) }
        val result = tool(gate).executeTyped(
            args(fields = listOf(AskUserTool.FieldArg("x", "标签", "CHOICE", options = emptyList()))),
        )
        assertEquals(ToolResult.ErrCode.BAD_ARGS, (result as ToolResult.Err).code)
    }

    @Test
    fun `ask request reaches sink before answer flows back`() = runTest {
        val received = CompletableDeferred<AskRequest>()
        val sink = ToolResultSink.concurrentOf { _, result ->
            (result as? ToolResult.Ok)?.data?.let { payload ->
                (payload as? ToolPayload.AskRequestPayload)?.let { received.complete(it.request) }
            }
        }
        val gate = UserQuestionGate { _ -> AskAnswer(mapOf(AskUserTool.PROMPT_ANSWER_KEY to "答")) }
        tool(gate, sink).executeTyped(args(question = "缺什么？"))
        val request = received.await()
        assertEquals("缺什么？", request.question)
        assertTrue(request.fields.isEmpty())
        assertNull(request.toolCallId) // Koog 上下文无公开 API——恒 null（时间线配对经事件流）
    }

    @Test
    fun `cancellation propagates while gate is suspended`() = runTest {
        // 挂起中 cancel：executeTyped 协程体收 CE 原样传播（不吞不译——骨架纪律）
        val started = CompletableDeferred<Unit>()
        val gate = UserQuestionGate { _ ->
            started.complete(Unit)
            kotlinx.coroutines.delay(60_000) // 挂住等人
            AskAnswer(emptyMap())
        }
        val job = async { tool(gate).executeTyped(args()) }
        started.await()
        job.cancel()
        assertTrue(job.isCancelled, "取消必须穿透 gate 挂起点")
        coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
    }

    @Test
    fun `ask cap clamps to bounds and stays below overall budget`() {
        // 钳域：极小预算 → 下限 1min；极大预算 → 上限 30min；中位 → 0.8 系数
        assertEquals(60_000L, AskCaps.resolveAskCapMillis(10_000L))
        assertEquals(30 * 60_000L, AskCaps.resolveAskCapMillis(10_000_000L))
        assertEquals(240_000L, AskCaps.resolveAskCapMillis(300_000L)) // 5min × 0.8 = 4min
        assertEquals(60_000L, AskCaps.resolveAskCapMillis(0L)) // 非正输入回落下限
    }

    // ── catalog 门控（任务 1.4——distillBuffer 同款反向门控配方） ──

    @Test
    fun `catalog gates ask user by explicit names with gate`() {
        val context = ToolExecutionContext.withAllowedRoots(listOf(java.io.File("/tmp")))
        val gate = UserQuestionGate { _ -> AskAnswer(emptyMap()) }

        // gate 非 null + 点名：注册
        val registry = AgentToolCatalog.buildRegistry(
            names = setOf(AskUserTool.NAME),
            context = context,
            config = AgentToolCatalog.ToolContextConfig(userQuestionGate = gate),
        )
        assertTrue(registry.tools.any { it.name == AskUserTool.NAME }, "gate 注入 + 点名 ⇒ 注册")

        // gate null（桥接/工作台/蒸馏/语音旧链形态）：点名也不注册
        val registryNoGate = AgentToolCatalog.buildRegistry(
            names = setOf(AskUserTool.NAME),
            context = context,
            config = AgentToolCatalog.ToolContextConfig(userQuestionGate = null),
        )
        assertTrue(
            registryNoGate.tools.none { it.name == AskUserTool.NAME },
            "gate null ⇒ 点名也不注册（防误装）",
        )
    }

    @Test
    fun `base name sets never include ask user`() {
        // 🔴 旧链防线：user.ask_user 不进任何既有装配选择集——
        // 桥接/工作台/蒸馏/语音链的 names 永远选不到提问件
        val baseNameSets = mapOf(
            "MemoryTools" to DecryptTools.MEMORY_NAMES,
            "DistillTools" to DistillTools.DISTILL_NAMES,
        )
        baseNameSets.forEach { (label, names) ->
            assertTrue(AskUserTool.NAME !in names, "$label 的名集不含 user.ask_user（防泄漏进旧链）")
        }
    }
}
