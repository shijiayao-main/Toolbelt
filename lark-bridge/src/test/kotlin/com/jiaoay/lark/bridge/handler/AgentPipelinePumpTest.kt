// 死锁回归测试（2026-09-09 真机事故）：
// runAgentPipeline 曾用 `supervisorScope { launch { pump… } }` 启动卡片泵——supervisorScope 是
// 挂起函数，会等块内子协程结束才返回；而泵要等 finally 里 slot.close() 才退出 ⇒ try 块进不去、
// agent 进程永远不被拉起 ⇒ 120s 后引擎看门狗判死（audit elapsedMillis=0 的 agent_timeout）。
// 旧测试全绿的原因：fake sink isOpen=false 时 pump 立即退出，supervisorScope 立即返回。
// 本文件用 **isOpen=true 的流式 sink**（真机同形态）+ 挂起的 agent 假件，钉死「泵在后台、
// handler 不被泵阻塞、终态收尾必达」三条真机形态语义。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.agent.AgentRequest
import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.agent.AgentRunResult
import com.jiaoay.lark.bridge.agent.ProgressAccumulator
import com.jiaoay.lark.bridge.agent.ProgressEvent
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.exec.AgentLine
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.SyntheticCardStream
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AgentPipelinePumpTest {

    private fun textMsg(): InboundMessage = msg(messageId = "om_pump", chatId = "oc_pump", text = "ai 分析这个日志")

    /**
     * 🔴 真机同形态的录制假件：stream() 返回 **isOpen=true** 的卡流
     * （旧 bug 场景下 fake sink 泵即退、supervisorScope 立即返回，踩不到死锁）。
     * RecordingReplySink 是 final，直接组合而非继承。
     */
    private class OpenReplySink(private val inner: RecordingReplySink) : com.jiaoay.lark.bridge.pipeline.ReplySink by inner {
        val cardStreams = mutableListOf<com.jiaoay.lark.gateway.CardStreamSink>()
        override fun stream(initial: CardSpec): com.jiaoay.lark.gateway.CardStreamSink =
            SyntheticCardStream(inner.target, initial).also { cardStreams.add(it) }
    }

    /** agent 假件：body 可编程挂起，模拟真机「agent 长跑」。
     *  P2 受理卡延迟触发后 stub 须喂**首个进度事件**（覆写 5 参重载——真机同形态：
     *  进度事件由 runner 的 consume 链喂入，默认转调 2 参重载的旧 stub 不会触发开卡）。
     *  批 ④：body 签名扩为 (AgentRequest, ProgressAccumulator)——状态带用例需在 runner 收尾
     *  markStatus（真机 runner 职责，见各用例注释）。 */
    private class StubAgentRunner : AgentRunner {
        var body: (suspend (AgentRequest, ProgressAccumulator?) -> AgentRunResult)? = null
        override val backendId: String = "stub"
        override val isAvailable: Boolean = true
        override suspend fun run(request: AgentRequest, onLine: (AgentLine) -> Unit): AgentRunResult =
            body?.invoke(request, null) ?: AgentRunResult(exitCode = 0, summary = "stub ok")
        override suspend fun run(
            request: AgentRequest,
            chatId: String,
            senderOpenId: String,
            progress: ProgressAccumulator?,
            onLine: (AgentLine) -> Unit,
        ): AgentRunResult {
            // 首个进度事件（模拟 LLM 思考）——P2 延迟开卡的触发信号
            progress?.onEvent(ProgressEvent.Thinking("正在分析任务"))
            return body?.invoke(request, progress) ?: run(request, onLine)
        }
        override suspend fun resetSession(sessionKey: String): Boolean = true
    }

    private fun ctxOf(
        config: BridgeConfig,
        agent: StubAgentRunner,
        reply: com.jiaoay.lark.bridge.pipeline.ReplySink,
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

    private fun config(): BridgeConfig = testConfig()

    @Test
    fun `泵挂后台不阻塞 handler——agent 长跑期间 handle 不被卡死`() = runBlocking {
        val agent = StubAgentRunner()
        // agent 执行体：挂起 500ms（模拟真机「agent 跑数十秒」）。
        // 死锁形态下 handle 永不返回（泵等 close、close 在 finally、finally 在 run 之后），
        // withTimeout(5s) 必超时；修复后 handle 应在 agent 结束后正常返回。
        agent.body = { _, _ ->
            delay(500)
            AgentRunResult(exitCode = 0, summary = "done")
        }
        val reply = OpenReplySink(RecordingReplySink(LarkTarget.ofInbound(textMsg())))
        val acc = ProgressAccumulator()
        val ctx = ctxOf(config(), agent, reply, acc)

        val outcome = withTimeout(5_000) { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }

        assertEquals(TaskKind.COMMAND, outcome.kind)
        assertTrue(outcome.summary.contains("done"))
    }

    @Test
    fun `终态收尾必达——agent 返回后 sink 被关闭且补发终态卡`() = runBlocking {
        val agent = StubAgentRunner()
        val reply = OpenReplySink(RecordingReplySink(LarkTarget.ofInbound(textMsg())))
        val acc = ProgressAccumulator()
        val ctx = ctxOf(config(), agent, reply, acc)

        withTimeout(5_000) { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }

        val sink = reply.cardStreams.single() as SyntheticCardStream
        assertFalse(sink.isOpen, "任务结束后 sink 必须 closed（否则 9min 后 TimeDeadline 轮换发幽灵卡）")
        assertTrue(sink.terminal != null, "终态卡必须已发出")
    }

    @Test
    fun `进度卡关闭时不发受理卡也不起泵`() = runBlocking {
        val agent = StubAgentRunner()
        val reply = OpenReplySink(RecordingReplySink(LarkTarget.ofInbound(textMsg())))
        val acc = ProgressAccumulator()
        val config = config().let { it.copy(agent = it.agent.copy(progress = it.agent.progress.copy(enabled = false))) }
        val ctx = ctxOf(config, agent, reply, acc)

        withTimeout(5_000) { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }

        assertEquals(0, reply.cardStreams.size, "进度卡关闭 ⇒ 不开流")
    }

    // =====================================================================================
    // 3.4 表情信号（2026-09-09 产品新增）：接单 OnIt → 终态 THUMBSUP/CROSS_MARK；取消撤回 OnIt
    // =====================================================================================

    @Test
    fun `表情信号——成功任务先加 OnIt 再加完成表情`() = runBlocking {
        val agent = StubAgentRunner()
        agent.body = { _, _ -> AgentRunResult(exitCode = 0, summary = "ok") }
        val inner = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val reply = OpenReplySink(inner)
        val ctx = ctxOf(config(), agent, reply, ProgressAccumulator())

        withTimeout(5_000) { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }

        // 两条表情：接单 OnIt(add=true) + 终态 THUMBSUP(add=true)，目标都是用户消息 om_pump
        assertEquals(2, inner.reactions.size, "成功任务须恰有接单+完成两条表情，实际 ${inner.reactions}")
        assertTrue(inner.reactions[0] == "om_pump:OnIt:true", "第一条须为接单 OnIt，实际 ${inner.reactions[0]}")
        assertTrue(inner.reactions[1] == "om_pump:THUMBSUP:true", "第二条须为完成 THUMBSUP，实际 ${inner.reactions[1]}")
    }

    @Test
    fun `表情信号——失败任务终态发 CROSS_MARK`() = runBlocking {
        val agent = StubAgentRunner()
        agent.body = { _, _ -> AgentRunResult(exitCode = 1, summary = "boom", errorCode = "agent_exit_1") }
        val inner = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val reply = OpenReplySink(inner)
        val ctx = ctxOf(config(), agent, reply, ProgressAccumulator())

        val outcome = withTimeout(5_000) { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }

        assertEquals(com.jiaoay.lark.bridge.pipeline.TaskStatus.FAILURE, outcome.status)
        assertTrue(
            inner.reactions.any { it == "om_pump:CROSS_MARK:true" },
            "失败任务须发 CROSS_MARK，实际 ${inner.reactions}",
        )
    }

    /** 🔴 批 ④（状态带）：失败回合终态卡状态带拼 errorText 纯码形态（errorCode（exit=N））。
     *  🔴 markStatus 是 runner 的职责（同下条用例注释）。 */
    @Test
    fun `🔴 批④ 失败回合终态卡状态带拼错误码`() = runBlocking {
        val agent = StubAgentRunner()
        agent.body = { _, acc ->
            acc!!.markStatus(com.jiaoay.lark.bridge.agent.RunStatus.FAILED)
            AgentRunResult(exitCode = 1, summary = "boom", errorCode = "agent_exit_1")
        }
        val inner = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val reply = OpenReplySink(inner)
        val acc = ProgressAccumulator()
        val ctx = ctxOf(config(), agent, reply, acc)

        withTimeout(5_000) { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }

        val terminal = (reply.cardStreams.single() as SyntheticCardStream).terminal
        assertTrue(terminal != null, "终态卡必须已发出")
        assertTrue(
            terminal!!.markdown.startsWith("❌ 失败 · agent_exit_1（exit=1）"),
            "🔴 失败状态带须拼纯码 errorText：${terminal.markdown}",
        )
    }

    /** 🔴 批 ④：成功回合终态卡状态带「✅ 已完成」（elapsed=0 不拼用时——耗时以文字回复为准）。
     *  🔴 markStatus 是 runner 的职责（BackendAgentRunner/KoogAgentRunner 收尾都写）——Stub 也必须写，
     *     否则 accumulator 状态滞留 RUNNING、经 toTaskRunStatus() 兜底成「失败」（测试假红）。 */
    @Test
    fun `🔴 批④ 成功回合终态卡状态带已完成且不拼用时`() = runBlocking {
        val agent = StubAgentRunner()
        agent.body = { _, acc ->
            acc!!.markStatus(com.jiaoay.lark.bridge.agent.RunStatus.DONE)
            AgentRunResult(exitCode = 0, summary = "ok")
        }
        val inner = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val reply = OpenReplySink(inner)
        val acc = ProgressAccumulator()
        val ctx = ctxOf(config(), agent, reply, acc)

        withTimeout(5_000) { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }

        val terminal = (reply.cardStreams.single() as SyntheticCardStream).terminal
        assertTrue(terminal != null, "终态卡必须已发出")
        assertEquals("✅ 已完成", terminal!!.markdown.substringBefore("\n"), "elapsed=0 ⇒ 只出短语：${terminal.markdown}")
    }

    @Test
    fun `表情信号——进度卡关闭时接单与终态表情仍发（表情独立于卡片链路）`() = runBlocking {
        val agent = StubAgentRunner()
        agent.body = { _, _ -> AgentRunResult(exitCode = 0, summary = "ok") }
        val inner = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val reply = OpenReplySink(inner)
        val config = config().let { it.copy(agent = it.agent.copy(progress = it.agent.progress.copy(enabled = false))) }
        val ctx = ctxOf(config, agent, reply, ProgressAccumulator())

        withTimeout(5_000) { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }

        assertEquals(0, reply.cardStreams.size, "进度卡关闭 ⇒ 不开流")
        assertEquals(2, inner.reactions.size, "表情链路独立于卡片：仍须接单+完成两条，实际 ${inner.reactions}")
    }

    @Test
    fun `表情信号——取消路径撤回 OnIt 且不发失败表情`() = runBlocking {
        val agent = StubAgentRunner()
        val never = CompletableDeferred<Unit>()
        agent.body = { _, _ ->
            never.await()   // 永不完成：等待外部取消
            AgentRunResult(exitCode = 0, summary = "unreachable")
        }
        val inner = RecordingReplySink(LarkTarget.ofInbound(textMsg()))
        val reply = OpenReplySink(inner)
        val ctx = ctxOf(config(), agent, reply, ProgressAccumulator())

        val job: kotlinx.coroutines.Job = launch { runAgentPipeline(ctx, "/log-helper ping", TaskKind.COMMAND) }
        // 等接单表情落账后取消任务（模拟 URGENT 停止 / 超时）
        withTimeout(5_000) {
            while (inner.reactions.isEmpty()) delay(10)
            job.cancelAndJoin()
        }

        assertTrue(inner.reactions[0] == "om_pump:OnIt:true", "取消前须已发接单表情")
        assertTrue(
            inner.reactions.none { it == "om_pump:THUMBSUP:true" || it == "om_pump:CROSS_MARK:true" },
            "取消路径不得发终态表情，实际 ${inner.reactions}",
        )
        assertTrue(
            inner.reactions.any { it == "om_pump:OnIt:false" },
            "取消路径须撤回 OnIt（NonCancellable 兜底），实际 ${inner.reactions}",
        )
    }

    // ── 滚动窗口泵语义直测（2026-09-12 shrink-progress-card-window，兑现 design.md Migration Plan 承诺）──

    /** 🔴 P2 评审补测：pumpSnapshotsToCard 是全量替换直通——喂变短快照时卡上内容必须跟着变短（不拼接、不丢帧）。
     *    ConflatedSlot 默认 1 槽 ⇒ 连续 offer 会 conflate 丢旧——用容量 2 的槽保住两帧（真实泵消费端更慢，
     *    此处直测 pump 本体的转发语义：收到的每帧原样 tryReplace，零 substring 拼接、零 isNotEmpty 过滤）。 */
    @Test
    fun `pump 直通全量替换 - 变短快照原样上卡且无拼接`() = runBlocking {
        val sink = SyntheticCardStream(LarkTarget.Chat("oc_pump_replace"), CardSpec(title = "", markdown = "", streaming = true))
        val slot = com.jiaoay.lark.bridge.pipeline.ConflatedSlot<String>(slotCapacity = 2)
        slot.offer("第 1 帧很长的内容 abcdefg")
        slot.offer("短") // 🔴 变短反例：append 语义会拼成「…abcdefg短」，replace 语义卡上必须只剩「短」
        slot.close()

        val frames = withTimeout(5_000) { com.jiaoay.lark.bridge.pipeline.pumpSnapshotsToCard(slot, sink) }

        assertEquals(2L, frames, "收到的两帧都必须转发（无 isNotEmpty 过滤回退）")
        assertEquals(listOf("第 1 帧很长的内容 abcdefg", "短"), sink.frames, "帧序原样、零 substring 拼接")
        assertEquals("短", sink.latest(), "最终卡内容 = 最后快照（latest-wins）")
    }
}
