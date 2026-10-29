// 2026-09-12 在线状态批次（方案A+C）测试：
// - SystemHandler「状态」/「/status」指令：match 精确匹配、渲染真值、快照缺失回退、清单收录；
// - PipelineSnapshotFactory 撞名守卫：用户 route 与「状态」撞名时 WARN 可观测。
// （Bridge.onGatewayConnectionSignal 守卫的测试在 BridgeLifecycleTest 侧——见同批次补充。）
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink
import com.jiaoay.lark.bridge.pipeline.FakeAgentRunner
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.HandlerKinds
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import com.jiaoay.lark.bridge.pipeline.PipelineSnapshotFactory
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 在线快照 fixture（renderStatus 的数据源）。 */
private fun statusSnapshot(
    running: Boolean = true,
    connection: BridgeConnectionStatus = BridgeConnectionStatus.Connected("ou_bot", "桥接机器人"),
    connectedSince: Long? = 1_000L,
    sampledAt: Long = 60_000L,
    liveProcesses: Int = 1,
): BridgeStatusSnapshot = BridgeStatusSnapshot(
    running = running,
    connection = connection,
    confirm = ConfirmRuntimeStatus.Disabled,
    counters = PipelineCounters.CounterSnapshot(
        intakeReceived = 42, intakeQueueDropped = 0, staleDropped = 1, duplicatesDropped = 0,
        blankIdsDropped = 0, accessRejected = 0, policyRejected = 0, unmatched = 0,
        submitted = 40, queueTimeouts = 0, chatBusy = 0, busyRepliesSuppressed = 0,
        urgentCancellations = 0, totalTimeouts = 0, idleTimeouts = 0, cancelledTasks = 0,
        handlerErrors = 0, completed = 38, failed = 2, skipped = 0, replyFailures = 0,
        sinkDropped = 0, filesRegistered = 0, filesReferenced = 0, downloadsStored = 0,
        quotaEvictions = 0,
    ),
    liveProcesses = liveProcesses,
    sampledAtMillis = sampledAt,
    connectedSinceMillis = connectedSince,
)

private fun depsWith(status: BridgeStatusSnapshot?): HandlerDeps =
    HandlerDeps({ testConfig() }, BridgeLog.Silent, { status })

private fun ctxOf(message: InboundMessage, config: BridgeConfig = testConfig()): RequestContext = RequestContext(
    message = message,
    config = config,
    route = null,
    reply = RecordingReplySink(LarkTarget.ofInbound(message)),
    progress = ConflatedProgressSink(),
    log = BridgeLog.Silent,
    agent = FakeAgentRunner(),
    processes = ProcessRegistry(config.runtime.maxConcurrentAgents),
)

private fun textMsg(text: String): InboundMessage = msg(text = text)

class SystemHandlerStatusTest {

    @Test
    fun `状态与 status 别名精确匹配且带参数不命中`() {
        val system = SystemHandler(depsWith(statusSnapshot()))
        assertTrue(system.match(ctxOf(textMsg("状态"))))
        assertTrue(system.match(ctxOf(textMsg("/status"))))
        assertTrue(system.match(ctxOf(textMsg("  状态  "))))
        assertTrue(system.match(ctxOf(textMsg("/Status"))))
        assertFalse(system.match(ctxOf(textMsg("状态 怎么样"))))   // 带参数不命中（精确匹配语义）
        assertFalse(system.match(ctxOf(textMsg("查状态"))))
        // 注：「ping 状态」按既有 ping 前缀语义命中（payload=状态），不属状态指令——此为既有设计，不测。
    }

    @Test
    fun `在线快照渲染真值`() = runBlocking {
        val system = SystemHandler(depsWith(statusSnapshot()))
        val outcome = system.handle(ctxOf(textMsg("状态")))
        assertEquals(TaskStatus.SUCCESS, outcome.status)
        val reply = outcome.summary
        assertTrue(reply.contains("🟢 在线"), "应含在线标记：$reply")
        assertTrue(reply.contains("桥接机器人"), "应含 botName：$reply")
        assertTrue(reply.contains("累计处理"), "应含累计口径（无日界分桶数据源，禁「今日」）：$reply")
        assertTrue(reply.contains("存活 agent 进程：1"), "应含存活进程数：$reply")
        assertFalse(reply.contains("今日"), "禁「今日」口径（无数据源）：$reply")
        assertFalse(reply.contains("心跳"), "禁伪造心跳字样：$reply")
    }

    @Test
    fun `重连中渲染迁移文案`() = runBlocking {
        val system = SystemHandler(depsWith(statusSnapshot(connection = BridgeConnectionStatus.Reconnecting)))
        val outcome = system.handle(ctxOf(textMsg("/status")))
        val reply = outcome.summary
        assertTrue(reply.contains("重连中"), "应含重连提示：$reply")
    }

    @Test
    fun `快照缺失或未运行回退兜底文案`() = runBlocking {
        val nullDeps = SystemHandler(depsWith(null))
        val outcome1 = nullDeps.handle(ctxOf(textMsg("状态")))
        assertEquals(TaskStatus.SUCCESS, outcome1.status)
        assertTrue(outcome1.summary.contains("暂不可知"))

        val stopped = SystemHandler(depsWith(statusSnapshot(running = false)))
        val outcome2 = stopped.handle(ctxOf(textMsg("状态")))
        assertTrue(outcome2.summary.contains("暂不可知"))
    }

    @Test
    fun `commands 清单包含状态指令`() = runBlocking {
        val system = SystemHandler(depsWith(statusSnapshot()))
        val outcome = system.handle(ctxOf(textMsg("/commands")))
        val reply = outcome.summary
        assertTrue(reply.contains("`状态` / `/status`"), "清单应含状态指令：$reply")
    }
}

/**
 * 撞名守卫测试：PipelineSnapshotFactory.build 对「状态」/「/status」撞名的用户 route 发 WARN。
 */
class BuiltinStatusCollisionTest {

    /** 捕获 WARN 行的测试 BridgeLog。 */
    private class RecordingBridgeLog : BridgeLog {
        val warns = mutableListOf<String>()
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String, error: Throwable?) { warns += message }
        override fun error(message: String, error: Throwable?) = Unit
    }

    private fun kindsWith(deps: HandlerDeps): HandlerKinds {
        var holder: HandlerDeps? = deps
        return HandlerKinds.build(
            system = { _, d -> SystemHandler(d) },
            command = { _, d -> SystemHandler(d) },
            agent = { _, d -> SystemHandler(d) },
            file = { _, d -> SystemHandler(d) },
        ).also { holder = null }
    }

    @Test
    fun `用户 route 触发词撞状态且排在 SystemRoute 前 ⇒ WARN`() {
        val log = RecordingBridgeLog()
        val deps = HandlerDeps({ testConfig() }, log)
        val factory = PipelineSnapshotFactory(kindsWith(deps), deps, log)
        val config = testConfig().copy(
            routes = listOf(
                CommandRoute(id = "user_cmd", name = "状态"),
                SystemRoute(id = "builtin"),
            ),
        )
        factory.build(config)
        assertTrue(log.warns.any { it.contains("撞名") && it.contains("user_cmd") }, "应 WARN 撞名：${log.warns}")
    }

    @Test
    fun `无 SystemRoute 时撞名也 WARN`() {
        val log = RecordingBridgeLog()
        val deps = HandlerDeps({ testConfig() }, log)
        val factory = PipelineSnapshotFactory(kindsWith(deps), deps, log)
        val config = testConfig().copy(
            routes = listOf(
                CommandRoute(id = "only", name = "ok", aliases = listOf("/status")),
            ),
        )
        factory.build(config)
        assertTrue(log.warns.any { it.contains("撞名") && it.contains("无 SystemRoute") }, "应 WARN 无 SystemRoute：${log.warns}")
    }

    @Test
    fun `不撞名零 WARN`() {
        val log = RecordingBridgeLog()
        val deps = HandlerDeps({ testConfig() }, log)
        val factory = PipelineSnapshotFactory(kindsWith(deps), deps, log)
        val config = testConfig().copy(
            routes = listOf(
                SystemRoute(id = "builtin"),
                CommandRoute(id = "normal", name = "查日志"),
            ),
        )
        factory.build(config)
        assertFalse(log.warns.any { it.contains("撞名") }, "不撞名不应 WARN：${log.warns}")
    }
}
