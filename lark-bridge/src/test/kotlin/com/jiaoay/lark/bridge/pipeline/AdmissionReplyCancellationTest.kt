// M4-T8 授权改动 E-1 的承接测试：`Admission.deliver` 出站回复阶段的取消语义。
// 🔴 本文件是**新增**测试（不修改任何 T5/T6/T7 已交付文件）；直接装配 `Admission` + throwing `RecordingReplySink`
//    驱动 `deliver`，逐条钉住三个分支：取消（rethrow，不计失败）/ ok=false（真失败计数）/ 非取消异常（降级 null）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.dispatch.BridgeDispatchers
import com.jiaoay.lark.bridge.dispatch.BridgeSupervisor
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.SendResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking

/**
 * 授权改动 E-1（M4-T8）：`Admission.deliver` 出站回复阶段的 [CancellationException] 必须**显式 rethrow**。
 *
 * 旧写法 `runCatching { ctx.reply.markdown(..) }.getOrElse { null }` 会把取消降级成 `null`：
 * ① `replyFailures` 被误 +1（取消被当成一次「回复发送失败」）；
 * ② 取消信号不再向上传播 ⇒ 任务协程「该死不死」（退化成旧 D9）。
 *
 * 本测试用 throwing [RecordingReplySink] 直接驱动 `deliver`，逐条钉住三个分支：
 * - 取消 ⇒ 不计 `replyFailures`、原样上抛被 `runGuarded` 记为 `cancelledTasks`、不交接 Observe；
 * - `ok=false` ⇒ 仍计入 `replyFailures`（E-1 未破坏正常失败计数）、正常交接 Observe；
 * - 非取消异常 ⇒ 降级为 `null`、不计 `replyFailures`、不取消、仍交接 Observe（保留旧降级语义）。
 */
class AdmissionReplyCancellationTest {

    private val clock: () -> Long = { System.currentTimeMillis() }

    private class Run(
        val counters: PipelineCounters,
        val handler: ScriptedHandler,
        val outcomes: List<RecordedOutcome>,
    )

    /**
     * 用给定出站行为（[sendResultProvider]）的 [RecordingReplySink] 跑一条 LIGHT 系统消息。
     * 🔴 直接装配 `Admission`（不经 [Harness]），因此不依赖任何 T7 脚手架改动。
     */
    private fun runLightWithReply(sendResultProvider: () -> SendResult): Run {
        val dispatcher = Dispatchers.Default
        val config = testConfig(routes = listOf(SystemRoute()))
        val supervisor = BridgeSupervisor.create(dispatcher)
        val runtime = supervisor.newRuntime(BridgeDispatchers.all(dispatcher))
        try {
            val counters = PipelineCounters()
            val lanes = ChatLanes(clock = clock)
            val permits = PermitGate(config.runtime.maxConcurrentAgents, runtime.admissionScope)
            val timeouts = TimeoutPolicy { config }
            val outcomes = CopyOnWriteArrayList<RecordedOutcome>()
            val admission = Admission(
                runtime = runtime,
                configProvider = { config },
                lanes = lanes,
                timeouts = timeouts,
                counters = counters,
                permits = permits,
                log = BridgeLog.Silent,
                clock = clock,
                onOutcome = { ctx, o -> outcomes.add(RecordedOutcome(ctx, o)) },
            )
            val message = msg(text = "ping")
            val reply = RecordingReplySink(LarkTarget.ofInbound(message), sendResultProvider)
            val ctx = RequestContext(
                message = message,
                config = config,
                route = SystemRoute(),
                reply = reply,
                progress = ConflatedProgressSink(clock),
                log = BridgeLog.Silent,
                agent = FakeAgentRunner(),
                processes = ProcessRegistry(1),
            )
            val handler = ScriptedHandler(
                kind = RouteKind.SYSTEM,
                weight = HandlerWeight.LIGHT,
                body = { HandlerOutcome.success(TaskKind.COMMAND, "done") },
            )
            val job = admission.submit(ctx, MatchableHandler(SystemRoute(), handler))
            // 🔴 任务协程要么正常完成、要么以取消收场；join 对两者都返回（取消不抛）。
            runBlocking { joinAll(job) }
            return Run(counters, handler, outcomes.toList())
        } finally {
            runCatching { runtime.cancel() }
            runCatching { supervisor.close() }
        }
    }

    @Test
    fun `🔴 出站回复被取消 不计 replyFailures 且原样上抛记为 cancelled（授权改动 E-1）`() {
        val run = runLightWithReply { throw CancellationException("reply send cancelled") }
        assertEquals(1, run.handler.invocations.get(), "handler 必须已执行到出站阶段")
        assertEquals(0L, run.counters.replyFailures, "🔴 取消不是回复失败：replyFailures 必须为 0")
        assertEquals(1L, run.counters.cancelledTasks, "🔴 取消必须原样上抛，被 runGuarded 记为 cancelledTasks")
        assertTrue(run.outcomes.isEmpty(), "deliver 在 recordOutcome 之前 rethrow ⇒ 被取消的那次不交接 Observe")
    }

    @Test
    fun `出站回复返回 ok=false 仍计入 replyFailures（E-1 未破坏正常失败计数）`() {
        val run = runLightWithReply { SendResult(ok = false, messageId = null) }
        assertEquals(1L, run.counters.replyFailures, "真失败（SendResult.ok=false）必须计入 replyFailures")
        assertEquals(0L, run.counters.cancelledTasks, "真失败不是取消")
        assertEquals(1, run.outcomes.size, "失败仍要交接 Observe 留审计痕迹")
    }

    @Test
    fun `出站回复抛非取消异常 降级为 null 不计失败不取消仍交接 Observe`() {
        val run = runLightWithReply { throw RuntimeException("gateway 500") }
        assertEquals(0L, run.counters.replyFailures, "非取消异常降级为 null，不计回复失败（保留旧降级语义）")
        assertEquals(0L, run.counters.cancelledTasks, "非取消异常不触发取消")
        assertEquals(1, run.outcomes.size, "降级后仍交接 Observe")
    }
}

// =====================================================================================
// onDelivered seam（2026-09-15 回执别名批）：deliver 的 SendResult 回调——回执 messageId
// 只在这里可被消费（handler 只产出 outcome，出站归 deliver；此前 messageId 在此被丢弃）。
// =====================================================================================
class AdmissionOnDeliveredTest {

    private val clock: () -> Long = { System.currentTimeMillis() }

    /** 跑一条 LIGHT 消息并收集 onDelivered 回调；sendResultProvider 控制出站结果。 */
    private fun runWithDelivered(sendResultProvider: () -> SendResult): Pair<List<Pair<String, SendResult>>, PipelineCounters> {
        val dispatcher = Dispatchers.Default
        val config = testConfig(routes = listOf(SystemRoute()))
        val supervisor = BridgeSupervisor.create(dispatcher)
        val runtime = supervisor.newRuntime(BridgeDispatchers.all(dispatcher))
        try {
            val counters = PipelineCounters()
            val lanes = ChatLanes(clock = clock)
            val permits = PermitGate(config.runtime.maxConcurrentAgents, runtime.admissionScope)
            val timeouts = TimeoutPolicy { config }
            val delivered = CopyOnWriteArrayList<Pair<String, SendResult>>()
            val admission = Admission(
                runtime = runtime,
                configProvider = { config },
                lanes = lanes,
                timeouts = timeouts,
                counters = counters,
                permits = permits,
                log = BridgeLog.Silent,
                clock = clock,
                onDelivered = { ctx, result -> delivered.add(ctx.inboundKind.name to result) },
            )
            val message = msg(text = "ping")
            val reply = RecordingReplySink(LarkTarget.ofInbound(message), sendResultProvider)
            val ctx = RequestContext(
                message = message,
                config = config,
                route = SystemRoute(),
                reply = reply,
                progress = ConflatedProgressSink(clock),
                log = BridgeLog.Silent,
                agent = FakeAgentRunner(),
                processes = ProcessRegistry(1),
            )
            val handler = ScriptedHandler(
                kind = RouteKind.SYSTEM,
                weight = HandlerWeight.LIGHT,
                body = { HandlerOutcome.success(TaskKind.COMMAND, "done") },
            )
            val job = admission.submit(ctx, MatchableHandler(SystemRoute(), handler))
            runBlocking { joinAll(job) }
            return delivered.toList() to counters
        } finally {
            runCatching { runtime.cancel() }
            runCatching { supervisor.close() }
        }
    }

    @Test
    fun `出站成功-onDelivered 恰好一次且回传 messageId`() {
        val (delivered, counters) = runWithDelivered { SendResult(ok = true, messageId = "om_ack_reply_1") }
        assertEquals(1, delivered.size, "出站一次 ⇒ 回调恰好一次")
        val (kind, result) = delivered.single()
        assertEquals("TEXT", kind, "回调应携带入站 kind（接线方按 FILE 判定）")
        assertEquals("om_ack_reply_1", result.messageId, "🔴 messageId 必须回传——回执别名登记的定位依据")
        assertEquals(0L, counters.replyFailures)
    }

    @Test
    fun `出站失败-onDelivered 同样回调（判定归接线方）`() {
        val (delivered, _) = runWithDelivered { SendResult(ok = false, messageId = null, error = com.jiaoay.lark.gateway.GatewayErrorCode.SendFailed) }
        assertEquals(1, delivered.size, "失败也回调——接线方按 ok 位自行跳过")
        assertEquals(false, delivered.single().second.ok)
    }

    @Test
    fun `静默指令-zero 出站-zero 回调`() {
        val dispatcher = Dispatchers.Default
        val config = testConfig(routes = listOf(SystemRoute()))
        val supervisor = BridgeSupervisor.create(dispatcher)
        val runtime = supervisor.newRuntime(BridgeDispatchers.all(dispatcher))
        try {
            val counters = PipelineCounters()
            val lanes = ChatLanes(clock = clock)
            val permits = PermitGate(config.runtime.maxConcurrentAgents, runtime.admissionScope)
            val timeouts = TimeoutPolicy { config }
            val delivered = CopyOnWriteArrayList<Pair<String, SendResult>>()
            val admission = Admission(
                runtime = runtime, configProvider = { config }, lanes = lanes, timeouts = timeouts,
                counters = counters, permits = permits, log = BridgeLog.Silent, clock = clock,
                onDelivered = { ctx, result -> delivered.add(ctx.inboundKind.name to result) },
            )
            val message = msg(text = "ping")
            val ctx = RequestContext(
                message = message, config = config, route = SystemRoute(),
                reply = RecordingReplySink(LarkTarget.ofInbound(message)),
                progress = ConflatedProgressSink(clock), log = BridgeLog.Silent,
                agent = FakeAgentRunner(), processes = ProcessRegistry(1),
            )
            // SILENT 指令 ⇒ deliver 不出站
            val handler = ScriptedHandler(
                kind = RouteKind.SYSTEM, weight = HandlerWeight.LIGHT,
                body = { HandlerOutcome.skipped(TaskKind.COMMAND, "静默", silent = true) },
            )
            val job = admission.submit(ctx, MatchableHandler(SystemRoute(), handler))
            runBlocking { joinAll(job) }
            assertEquals(0, delivered.size, "🔴 静默不出站 ⇒ onDelivered 不得回调（否则会登记幽灵别名）")
        } finally {
            runCatching { runtime.cancel() }
            runCatching { supervisor.close() }
        }
    }
}
