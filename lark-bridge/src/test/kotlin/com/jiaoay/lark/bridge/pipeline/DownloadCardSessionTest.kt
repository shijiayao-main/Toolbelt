// 下载进度卡测试（2026-09-12 真机反馈修复）——覆盖 DownloadCardSession 的两阶段生命周期、
// 线性阶段表、心跳（看门狗误杀修复）与收尾纪律；以及端到端「群文件不再被 SDK 门控拦截」。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeAssembler
import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.agent.ProgressAccumulator
import com.jiaoay.lark.bridge.agent.ProgressEvent
import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.GatewayConnectResult
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundResource
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.SendResult
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// 🔴 测试专用快心跳（生产 10s；测试等不起，且等 10s×多个用例会让整个测试类慢到没人愿意跑）。
private const val FAST_HEARTBEAT_MILLIS: Long = 20L

/** 单用例超时上限（远大于任何正常路径耗时，只为把「挂死」变成「失败」）。 */
private const val CASE_TIMEOUT_MILLIS: Long = 20_000L

/**
 * 下载卡单元测试：直连 [DownloadCardSession]，用 `RecordingReplySink` 观察开卡/上卡/收尾。
 *
 * 🔴 闸门要求 `ctx.progress is ProgressAccumulator` ⇒ 每个测试都传真实 [ProgressAccumulator]。
 */
class DownloadCardSessionTest {

    // ==================== ① 两阶段生命周期：「真的需要下载才开卡」====================

    @Test
    fun `未开卡时 append 进 pending 且一个 API 都不发`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc_test"))
        val card = DownloadCardSession.of(ctxOf(reply, ProgressAccumulator()))!!
        // 开卡前的阶段文案（resolver 的「已找回被引用文件 x，开始下载…」就在此时发生）。
        card.append("已找回被引用文件 crash.zip，开始下载…")

        assertTrue(reply.streams.isEmpty(), "未开卡 ⇒ 不得有任何 reply.stream 调用（纯文本引用零噪音）")
        // 从未开卡就收尾 ⇒ 完全 no-op（不发终态空卡）。
        cardTest { card.flushAndClose(DownloadCardSession.TerminalState.DONE) }
        assertTrue(reply.streams.isEmpty(), "从未开卡时收尾必须完全 no-op，实际 streams=${reply.streams.size}")
    }

    @Test
    fun `onStarted 才真开卡，且 pending 缓冲的阶段文案不丢`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc_test"))
        val card = DownloadCardSession.of(ctxOf(reply, ProgressAccumulator()))!!
        card.append("已找回被引用文件 crash.zip，开始下载…")
        cardTest {
            card.onStarted()
            // 给泵一次消费快照的机会（泵是独立协程）。
            delay(100L)
            val streamed = reply.streams.single()
            assertTrue(streamed.replaceCount >= 1, "泵必须把快照推上卡，实际帧数=${streamed.replaceCount}")
            assertTrue(
                streamed.latest().contains("已找回被引用文件 crash.zip"),
                "pending 缓冲必须随开卡一并上卡，实际=\n${streamed.latest()}",
            )
            card.flushAndClose(DownloadCardSession.TerminalState.DONE)
        }

        assertEquals(1, reply.streams.size, "onStarted 应恰好开一张卡")
        val sink = reply.streams.single()
        assertEquals(DownloadCardSession.TITLE, sink.initial.title)
        assertTrue(sink.initial.streaming, "下载卡必须是流式卡（无按钮）")
        // 终态卡也用同一份线性阶段表全量渲染。
        val md = assertNotNull(sink.terminal).markdown
        assertTrue(md.contains("已找回被引用文件 crash.zip"), "终态卡须含开卡前的阶段，实际=\n$md")
        assertTrue(md.contains(DownloadCardSession.STAGE_STARTED), "终态卡须含开卡时的首行阶段")
    }

    @Test
    fun `onStarted 幂等——多文件只开一张卡`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc_test"))
        val card = DownloadCardSession.of(ctxOf(reply, ProgressAccumulator()))!!
        cardTest {
            card.onStarted()
            card.onStarted()
            card.onStarted()
            // 🔴 必须收尾：泵协程挂在当前（runBlocking）Job 上，槽不关就永不退出 ⇒ 测试挂死。
            card.flushAndClose(DownloadCardSession.TerminalState.DONE)
        }
        assertEquals(1, reply.streams.size, "多资源不得开多张卡（一次开卡覆盖全部资源）")
    }

    // ==================== ② 闸门：任一开关关闭即不开卡 ====================

    @Test
    fun `闸门关闭时不返回会话`() {
        val base = testConfig0()
        assertNotNull(
            DownloadCardSession.of(ctxOf(RecordingReplySink(LarkTarget.Chat("oc")), ProgressAccumulator(), base)),
        )
        // agent.progress.enabled=false ⇒ 整条卡片链路跳过
        assertNull(
            DownloadCardSession.of(
                ctxOf(
                    RecordingReplySink(LarkTarget.Chat("oc")),
                    ProgressAccumulator(),
                    base.copy(agent = base.agent.copy(progress = base.agent.progress.copy(enabled = false))),
                ),
            ),
        )
        // fileMessage.enabled=false ⇒ 引用流关闭，不可能出现下载卡
        assertNull(
            DownloadCardSession.of(
                ctxOf(
                    RecordingReplySink(LarkTarget.Chat("oc")),
                    ProgressAccumulator(),
                    base.copy(fileMessage = base.fileMessage.copy(enabled = false)),
                ),
            ),
        )
    }

    @Test
    fun `progress 不是 ProgressAccumulator 时不开卡（能力未装配）`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc"))
        val ctx = ctxOf(reply, ConflatedProgressSink())
        assertNull(DownloadCardSession.of(ctx), "progressOverride 未装配 ⇒ 不开卡")
    }

    // ==================== ③ 线性阶段表：早期阶段不被挤掉 ====================

    @Test
    fun `线性阶段表保留早期阶段——对比滚动窗口会挤掉最早那条`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc"))
        // 刻意把窗口压到 2 条：滚动窗口语义下「准备引用文件」必被挤掉。
        val acc = ProgressAccumulator(maxEntries = 2)
        val card = DownloadCardSession.of(ctxOf(reply, acc))!!
        cardTest {
            card.onStarted()
            card.append("准备引用文件 crash.zip…")
            // 🔴 刻意喂**远超** accumulator 窗口容量（maxEntries=2）的阶段数：
            //    若下载卡退化成滚动窗口，最早那条必被挤掉——这正是本用例要钉死的语义差异。
            card.append("已下载 crash.zip，预处理中…")
            card.append("预处理产物 1/3…")
            card.append("预处理产物 2/3…")
            card.append("预处理产物 3/3…")
            delay(100L)
            card.flushAndClose(DownloadCardSession.TerminalState.DONE)
        }
        val sink = reply.streams.single()
        val body = assertNotNull(sink.terminal).markdown
        // 🔴 下载卡线性累积：**最早那条必须仍在卡上**（滚动窗口会把它挤掉）。
        assertTrue(body.contains("准备引用文件 crash.zip…"), "线性阶段表必须保留早期阶段，实际=\n$body")
        assertTrue(body.contains("已下载 crash.zip，预处理中…"), "应包含 已下载 crash.zip，预处理中…")
        assertTrue(body.contains("预处理产物 3/3…"), "最新阶段也必须在卡上")

        // 对照：喂给 ctx.progress（滚动窗口 2 条）的同一批文案里，最早那条已被挤出窗口。
        acc.append("准备引用文件 crash.zip…")
        acc.append("已下载 crash.zip，预处理中…")
        acc.append("预处理产物 1/3…")
        acc.append("预处理产物 2/3…")
        acc.append("预处理产物 3/3…")
        assertFalse(
            acc.snapshot().events.any { it is ProgressEvent.AssistantText && it.text.contains("准备引用文件") },
            "对照断言：滚动窗口应已挤掉最早那条（这正是下载卡不共用窗口的理由）",
        )
    }

    @Test
    fun `render 受 cardMaxChars 硬截断兜底`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc"))
        // 🔴 cardMaxChars 的真源是**配置**（`agent.progress.cardMaxChars`），不是 accumulator 的构造参——
        //    DownloadCardSession 从 ctx.liveConfig 读它。
        val base = testConfig()
        val config = base.copy(
            agent = base.agent.copy(progress = base.agent.progress.copy(cardMaxChars = 100)),
        )
        val card = DownloadCardSession.of(ctxOf(reply, ProgressAccumulator(), config))!!
        cardTest {
            card.onStarted()
            repeat(40) { card.append("阶段 $it ${"x".repeat(20)}") }
            card.flushAndClose(DownloadCardSession.TerminalState.DONE)
        }
        assertTrue(card.render().length <= 100, "必须遵守 cardMaxChars 硬截断，实际=${card.render().length}")
        val md = assertNotNull(reply.streams.single().terminal).markdown
        assertTrue(md.length <= 100, "终态卡同样受截断约束，实际=${md.length}")
    }

    // ==================== ④ 心跳：修空闲看门狗误杀长下载 ====================

    @Test
    fun `心跳周期性 touch，使 lastActivity 不被看门狗判死`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc"))
        val clockValue = AtomicLong(0L)
        // 🔴 虚拟时钟：ProgressAccumulator.touch 委托给 delegate，delegate 的 clock 才是真源。
        val acc = ProgressAccumulator(delegate = ConflatedProgressSink({ clockValue.get() }))
        val card = fastHeartbeat(ctxOf(reply, acc))!!

        cardTest {
            card.onStarted() // 此刻起心跳在跑
            val startedAt = acc.lastActivityMillis
            // 模拟「下载挂了很久」：只推时钟，不调任何 append（真实下载期间 resolver 也不上报）。
            clockValue.addAndGet(FAST_HEARTBEAT_MILLIS * 3 + 100)
            // 等心跳至少跑过一轮（超时即断言失败）。
            withTimeout(5_000L) {
                while (acc.lastActivityMillis == startedAt) delay(1L)
            }
            assertEquals(
                clockValue.get(),
                acc.lastActivityMillis,
                "心跳 touch 后 lastActivity 应等于当前时钟——看门狗据此判活",
            )
            card.flushAndClose(DownloadCardSession.TerminalState.DONE)
        }
    }

    @Test
    fun `心跳不阻止取消，且收尾后心跳协程终止`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc"))
        val clockValue = AtomicLong(0L)
        val acc = ProgressAccumulator(delegate = ConflatedProgressSink({ clockValue.get() }))
        val card = fastHeartbeat(ctxOf(reply, acc))!!

        cardTest {
            card.onStarted()
            // 收尾必须能立刻完成（心跳若挂着不响应取消，这里就超时）。
            withTimeout(5_000L) { card.flushAndClose(DownloadCardSession.TerminalState.FAILED) }
            val afterClose = acc.lastActivityMillis
            // 收尾后再推时钟并等待远超一个心跳周期：心跳已终止 ⇒ lastActivity 不再变化。
            // 🔴 用**测试快周期**（FAST_HEARTBEAT_MILLIS）而非生产常量，否则这里真的睡 30 秒。
            clockValue.addAndGet(FAST_HEARTBEAT_MILLIS * 5)
            delay(FAST_HEARTBEAT_MILLIS * 5)
            assertEquals(afterClose, acc.lastActivityMillis, "收尾后心跳必须已终止（不再续命 lastActivity）")
        }
    }

    // ==================== ⑤ 收尾纪律：终态卡 + 关槽（结构化并发必须能完成）====================

    @Test
    fun `收尾发终态卡、关流，且关闭 conflate 槽使泵退出`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc"))
        val card = DownloadCardSession.of(ctxOf(reply, ProgressAccumulator()))!!
        cardTest {
            card.onStarted()
            card.append("已下载 crash.zip，预处理中…")
            // 🔴 这行若挂起不返回，即复现第一版的「泵永久挂任务 Job ⇒ 任务永不完成」缺陷。
            withTimeout(5_000L) { card.flushAndClose(DownloadCardSession.TerminalState.DONE) }
        }
        val sink = reply.streams.single()
        val terminal = assertNotNull(sink.terminal, "必须发终态卡")
        assertEquals(DownloadCardSession.TerminalState.DONE.title, terminal.title)
        assertFalse(terminal.streaming, "终态卡必须非流式")
        assertTrue(terminal.markdown.contains("已下载 crash.zip"), "应包含 已下载 crash.zip" )
        assertFalse(sink.isOpen, "收尾后流必须已关闭")
    }

    @Test
    fun `收尾幂等——重复 flushAndClose 无副作用`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc"))
        val card = DownloadCardSession.of(ctxOf(reply, ProgressAccumulator()))!!
        cardTest {
            card.onStarted()
            card.flushAndClose(DownloadCardSession.TerminalState.DONE)
            card.flushAndClose(DownloadCardSession.TerminalState.FAILED)
        }
        assertEquals(1, reply.streams.single().closedTimes, "close 只应生效一次")
        assertEquals(
            DownloadCardSession.TerminalState.DONE.title,
            reply.streams.single().terminal?.title,
            "第二次收尾不得改写终态（幂等）",
        )
    }

    @Test
    fun `收尾后 append 被丢弃——不造出终态后又变动的矛盾观感`() {
        val reply = RecordingReplySink(LarkTarget.Chat("oc"))
        val card = DownloadCardSession.of(ctxOf(reply, ProgressAccumulator()))!!
        cardTest {
            card.onStarted()
            card.flushAndClose(DownloadCardSession.TerminalState.DONE)
            card.append("迟到的阶段文案")
        }
        assertFalse(card.render().contains("迟到的阶段文案"), "收尾后 append 必须被丢弃")
    }

    // ==================== ⑥ 开卡失败降级：绝不阻断下载主链路 ====================

    @Test
    fun `建卡抛异常时降级为无下载卡，不向调用方抛出`() {
        val reply = ThrowingReplySink(LarkTarget.Chat("oc"))
        val card = DownloadCardSession.of(ctxOf(reply, ProgressAccumulator()))!!
        cardTest {
            // 🔴 不得抛出——卡片是纯观测能力，绝不能让建卡失败冒泡打断下载。
            card.onStarted()
            card.append("准备引用文件 crash.zip…")
            card.flushAndClose(DownloadCardSession.TerminalState.DONE)
        }
        assertNull(card.render().ifBlank { null }, "降级后阶段表应为空（pending 已清）")
    }

    // ==================== 辅助 ====================

    private fun testConfig0() = testConfig()

    /**
     * 用**测试专用快心跳**开会话：生产是 10s，测试等不起。
     * 🔴 不这么做就只能给测试挂 20s+ 超时，跑一次的成本远超收益。
     */
    private fun fastHeartbeat(ctx: RequestContext): DownloadCardSession? =
        DownloadCardSession.of(ctx, heartbeatMillis = FAST_HEARTBEAT_MILLIS)

    private fun ctxOf(
        reply: com.jiaoay.lark.bridge.pipeline.ReplySink,
        progress: ProgressSink,
        config: com.jiaoay.lark.bridge.config.BridgeConfig = testConfig(),
    ): RequestContext = RequestContext(
        message = msg(kind = InboundKind.TEXT),
        config = config,
        route = null,
        reply = reply,
        progress = progress,
        log = BridgeLog.Silent,
        agent = FakeAgentRunner(),
        processes = ProcessRegistry(2),
    )
}


/**
 * 🔴 **所有用例统一经此**：给 `runBlocking` 加超时 + 兜底取消遗留的泵/心跳子协程。
 *
 * 没有它，一旦 `DownloadCardSession` 漏关 conflate 槽（正是第一版修掉的缺陷），泵协程会永久挂在
 * `runBlocking` 的 Job 上 ⇒ 测试**挂死**而非失败。变异测试实测过这个失败模式：
 * 摘掉 `slot.close()` 后整个测试类是无限等待，CI 只能靠外部超时杀。
 * 有了它，同一变异表现为「若干用例超时失败」，定位信息完整。
 */
private fun cardTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking {
    try {
        withTimeout(CASE_TIMEOUT_MILLIS) { block() }
    } finally {
        kotlin.coroutines.coroutineContext.cancelChildren()
    }
}

/** `stream()` 恒抛的 sink——验「建卡失败降级」。 */
private class ThrowingReplySink(override val target: LarkTarget) : com.jiaoay.lark.bridge.pipeline.ReplySink {
    override val chatId: String? get() = (target as? LarkTarget.Chat)?.chatId
    override suspend fun text(body: String): SendResult = SendResult(ok = true, messageId = "om_t")
    override suspend fun markdown(body: String): SendResult = SendResult(ok = true, messageId = "om_m")
    override suspend fun card(spec: CardSpec): SendResult = SendResult(ok = true, messageId = "om_c")
    override fun stream(initial: CardSpec): com.jiaoay.lark.gateway.CardStreamSink =
        throw IllegalStateException("模拟建卡失败")
    override suspend fun file(file: Path, displayName: String?): SendResult =
        SendResult(ok = true, messageId = "om_f")
    override suspend fun react(messageId: String, emojiKey: String, add: Boolean): Boolean = false
}

// =========================================================================================
// 端到端：群文件不再被 SDK 门控拦截（本次真机根因的结构性回归防线）
// =========================================================================================

/**
 * 真机 bug 回归测试（2026-09-12）：群@门控开着（P3 语义 = `agent.groupMode="mention"`）时，
 * 群里发的**文件消息**必须抵达 `FileHandler` 并登记——而不是被 SDK 的会话级 PolicyGate 拦在
 * `"message"` 事件之前（文件消息物理上无法带 @，桥接侧 PolicyGate 对 FILE 无条件豁免）。
 *
 * 🔴 移回「把门控下推给 SDK」即红：那时本用例的文件消息永远登记不上，
 * `fileRegistry.lookup` 返回 null，`filesRegistered` 计数恒 0。
 */
class GroupFilePolicyRegressionTest {

    @Test
    fun `群门控开着时群文件消息仍抵达 FileHandler 并登记`() {
        val root = kotlin.io.path.createTempDirectory("dlcard-groupfile")
        val gateways = CopyOnWriteArrayList<com.jiaoay.lark.bridge.pipeline.SyntheticGateway>()
        val config = testConfig(
            routes = listOf(SystemRoute(), AgentRoute(), FileRoute()),
            groupMode = "mention", // 🔴 关键：门控开着（默认即 mention）
        ).copy(connection = com.jiaoay.lark.bridge.config.ConnectionConfig(appId = "cli_app", appSecret = "s"))

        BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = {
                SyntheticGateway().also { g ->
                    g.connectResult = GatewayConnectResult.Connected("ou_bot", "Bot")
                    gateways.add(g)
                }
            },
            defaultConfig = config,
            consoleLog = false,
        ).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways[0]
            // 群里发的文件消息：chatType=GROUP、未 @机器人（文件消息物理上无法带 @）。
            gw.emitInbound(
                msg(
                    messageId = "om_group_file",
                    chatId = "oc_group",
                    chatType = ChatType.GROUP,
                    kind = InboundKind.FILE,
                    mentionedBot = false,
                    text = "<file key=\"fk_1\" name=\"crash.zip\"/>",
                ).copy(
                    rawContentType = "file",
                    resources = listOf(
                        InboundResource(type = "file", fileKey = "fk_1", fileName = "crash.zip", durationMs = 0L),
                    ),
                ),
            )
            val registered = awaitUntil(10_000L) {
                bridge.fileRegistry.lookup("om_group_file", "oc_group", 24L * 3_600_000L) != null
            }
            assertTrue(
                registered,
                "🔴 群文件消息必须登记成功——被 SDK 门控拦截是本用例要防的回归。" +
                    "policies=${gw.recordedPolicies.map { it.requireMention }}",
            )
            // 并且确实没有走 SDK 的拒绝路径。
            assertEquals(
                0L,
                gw.signalEmitCount,
                "不得出现 SDK Rejected 信号（SDK 侧应全放行）",
            )
            runBlocking { bridge.stop() }
        }
    }

    @Test
    fun `启动与 HOT 更新后下推给 SDK 的策略恒为全放行`() {
        val root = kotlin.io.path.createTempDirectory("dlcard-policy")
        val gateways = CopyOnWriteArrayList<com.jiaoay.lark.bridge.pipeline.SyntheticGateway>()
        val config = testConfig(
            routes = listOf(SystemRoute(), AgentRoute(), FileRoute()),
            groupMode = "mention",
        ).copy(connection = com.jiaoay.lark.bridge.config.ConnectionConfig(appId = "cli_app", appSecret = "s"))

        BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = {
                SyntheticGateway().also { g ->
                    g.connectResult = GatewayConnectResult.Connected("ou_bot", "Bot")
                    gateways.add(g)
                }
            },
            defaultConfig = config,
            consoleLog = false,
        ).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways[0]
            assertTrue(gw.recordedPolicies.isNotEmpty(), "启动必须下推策略")
            assertTrue(
                gw.recordedPolicies.all { !it.requireMention },
                "启动期 SDK 侧须全放行，实际=${gw.recordedPolicies.map { it.requireMention }}",
            )
            // HOT 更新（groupMode 反向切换）后仍须全放行（P3：门控真源 = agent.groupMode）。
            val next = bridge.config.copy(
                agent = bridge.config.agent.copy(groupMode = "all"),
            )
            runBlocking { bridge.applyConfig(next) }
            assertTrue(
                gw.recordedPolicies.all { !it.requireMention },
                "HOT 后 SDK 侧仍须全放行，实际=${gw.recordedPolicies.map { it.requireMention }}",
            )
            runBlocking { bridge.stop() }
        }
    }

    private fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5L)
        }
        return condition()
    }
}
