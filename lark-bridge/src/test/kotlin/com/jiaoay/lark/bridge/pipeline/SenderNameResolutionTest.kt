// 任务 15：senderName 解析路径测试——验证执行路径与拒绝路径都能拿到名字。
// 🔴 全部用假件，绝不发真实网络请求。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.dispatch.BridgeDispatchers
import com.jiaoay.lark.bridge.dispatch.BridgeSupervisor
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.observe.UserNameResolver
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 验证 Admission 的两个 senderName seam 在执行路径与拒绝路径都被正确调用。
 *
 * 🔴 关键约束验证：
 * - 执行路径：resolveSenderName 在 handler.handle(ctx) 之前调用 ⇒ handler 和 Observe 都能拿到名字
 * - 拒绝路径（有回复）：resolveSenderName 被调用 ⇒ 被拒消息的审计记录有名字
 * - 拒绝路径（静默）：peekSenderName 被调用 ⇒ 只读缓存降级（已知差异）
 */
class SenderNameResolutionTest {

    private val supervisor = BridgeSupervisor.create(Dispatchers.Default)
    private val dispatchers = BridgeDispatchers.all(Dispatchers.Default)
    private val runtime = supervisor.newRuntime(dispatchers)
    private val resolvedNames = CopyOnWriteArrayList<String>()
    private val peekedNames = CopyOnWriteArrayList<String>()

    private val fetchCalls = AtomicInteger(0)
    private val resolver = UserNameResolver(fetch = { openId ->
        fetchCalls.incrementAndGet()
        "User_$openId"
    })

    private val counters = PipelineCounters()
    private val configProvider: () -> com.jiaoay.lark.bridge.config.BridgeConfig = { testConfig() }
    private val lanes = ChatLanes()
    private val permits = PermitGate(4, runtime.admissionScope)
    private val timeouts = TimeoutPolicy(configProvider)

    private val admission = Admission(
        runtime = runtime,
        configProvider = configProvider,
        lanes = lanes,
        timeouts = timeouts,
        counters = counters,
        permits = permits,
        log = BridgeLog.Silent,
        resolveSenderName = { ctx ->
            ctx.senderName = resolver.nameOf(ctx.message.senderId).orEmpty()
            resolvedNames.add(ctx.senderName)
        },
        peekSenderName = { ctx ->
            ctx.senderName = resolver.peek(ctx.message.senderId).orEmpty()
            peekedNames.add(ctx.senderName)
        },
    )

    @AfterTest
    fun tearDown() {
        runCatching { runtime.cancel() }
        runCatching { supervisor.close() }
    }

    @Test
    fun `执行路径：ctx senderName 在 handler 之前被解析`() {
        val capturedNames = CopyOnWriteArrayList<String>()
        // 构造一个能捕获 ctx.senderName 的 handler
        val handler = object : MessageHandler {
            override val kind = RouteKind.SYSTEM
            override val weight = HandlerWeight.LIGHT
            override val timeoutSeconds = 30L
            override fun match(ctx: RequestContext) = true
            override suspend fun handle(ctx: RequestContext): HandlerOutcome {
                capturedNames.add(ctx.senderName)
                return HandlerOutcome.success(kind = TaskKind.COMMAND, summary = "ok")
            }
        }
        val entry = MatchableHandler(SystemRoute(), handler)

        val ctx = buildCtx(msg(senderId = "ou_exec"))
        val job = admission.submit(ctx, entry)
        runBlocking { job.join() }

        assertEquals(1, resolvedNames.size, "resolveSenderName 应被调用一次")
        assertEquals("User_ou_exec", resolvedNames[0])
        assertEquals(1, capturedNames.size, "handler 应能看到已解析的名字")
        assertEquals("User_ou_exec", capturedNames[0], "handler 内 ctx.senderName 应已被填充")
    }

    @Test
    fun `拒绝路径（有回复）：ctx senderName 被 resolveSenderName 解析`() {
        val ctx = buildCtx(msg(senderId = "ou_reject"))
        val job = admission.submitRejection(ctx, "你没有权限")
        runBlocking { job.join() }

        assertEquals(1, resolvedNames.size, "有回复的拒绝路径应调用 resolveSenderName")
        assertEquals("User_ou_reject", resolvedNames[0])
        assertTrue(peekedNames.isEmpty(), "有回复时不应走 peek 降级")
    }

    @Test
    fun `拒绝路径（静默）：ctx senderName 走 peekSenderName 降级`() {
        // 先通过 nameOf 预热缓存（模拟之前某条消息已解析过该 openId）
        runBlocking { resolver.nameOf("ou_silent") }
        val callsBefore = fetchCalls.get()

        val ctx = buildCtx(msg(senderId = "ou_silent"))
        val job = admission.submitRejection(ctx, null)
        runBlocking { job.join() }

        assertEquals(1, peekedNames.size, "静默拒绝路径应调用 peekSenderName")
        assertEquals("User_ou_silent", peekedNames[0], "peek 应命中缓存")
        assertTrue(resolvedNames.isEmpty(), "静默时不应走 resolveSenderName")
        assertEquals(callsBefore, fetchCalls.get(), "peek 不应触发额外 API 调用")
    }

    @Test
    fun `拒绝路径（静默、缓存未命中）：senderName 为空串——已知差异`() {
        val ctx = buildCtx(msg(senderId = "ou_cold"))
        val job = admission.submitRejection(ctx, "")  // 空串等同 null（isNotBlank 过滤）
        runBlocking { job.join() }

        assertEquals(1, peekedNames.size, "应走 peek 降级")
        assertEquals("", peekedNames[0], "缓存未命中时 peek 返回 null → orEmpty() = 空串（已知差异）")
    }

    // ==================== 辅助 ====================

    private fun buildCtx(message: InboundMessage): RequestContext {
        val reply = RecordingReplySink(LarkTarget.ofInbound(message))
        return RequestContext(
            message = message,
            config = testConfig(),
            route = null,
            reply = reply,
            progress = ConflatedProgressSink(),
            log = BridgeLog.Silent,
            agent = FakeAgentRunner(),
            processes = ProcessRegistry(2),
        )
    }
}
