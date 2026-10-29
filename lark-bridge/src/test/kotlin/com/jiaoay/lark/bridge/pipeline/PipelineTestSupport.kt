// M4-T7：处理链测试的公共脚手架（假件 + 合成消息 + 全链路装配 Harness + 源码归一化工具）。
// 🔴 本文件只在 test 源集；LarkSdkIsolationTest 的 D/E/F 组只扫 src/main/kotlin，故不受生产纪律约束，
//    但仍刻意保持「零 SDK import、零静态可变状态」以便与生产源同形。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.agent.AgentRequest
import com.jiaoay.lark.bridge.agent.AgentRunResult
import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.RouteRule
import com.jiaoay.lark.bridge.config.RuntimeConfig
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.dispatch.BridgeDispatchers
import com.jiaoay.lark.bridge.dispatch.BridgeRuntime
import com.jiaoay.lark.bridge.dispatch.BridgeSupervisor
import com.jiaoay.lark.bridge.exec.AgentLine
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.CardStreamSink
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.GatewayLimits
import com.jiaoay.lark.gateway.GatewaySdkSettings
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.SendResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay

// =====================================================================================
// 源码归一化工具（grep 型守护断言用；手法对齐 lark-gateway 的 CardStreamTestSupport）
// =====================================================================================

/** 从 `user.dir` 逐级上溯到含 `settings.gradle.kts` 的仓库根。 */
internal fun testRepoRoot(): File {
    var dir = File(System.getProperty("user.dir"))
    while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
    return dir
}

/**
 * 剥离 Kotlin 源里的注释（行 `//` 与嵌套块 `/* */`）与字符串字面量（`"..."` 与 `"""..."""`）。
 *
 * 🔴 「禁止出现 X」型断言**必须**用归一化源：生产源的抬头注释与 KDoc 里对这些 token 的**提及**
 *    （例如写「本路径全程无锁，不使用任何监视器锁」）会造成假红。做法对齐
 *    `LarkSdkIsolationTest.normalizeKotlinSource` 与 `lark-gateway` 的同名工具。
 * 🔴 「必须出现 X」的存在性断言仍用**原始源**。
 */
internal fun stripCommentsAndStrings(src: String): String {
    val sb = StringBuilder(src.length)
    var i = 0
    val n = src.length
    var blockDepth = 0
    while (i < n) {
        val c = src[i]
        val next = if (i + 1 < n) src[i + 1] else '\u0000'
        when {
            blockDepth > 0 -> {
                if (c == '/' && next == '*') {
                    blockDepth++
                    i += 2
                } else if (c == '*' && next == '/') {
                    blockDepth--
                    i += 2
                } else {
                    i++
                }
            }

            c == '/' && next == '/' -> while (i < n && src[i] != '\n') i++
            c == '/' && next == '*' -> {
                blockDepth++
                i += 2
            }

            c == '"' && next == '"' && i + 2 < n && src[i + 2] == '"' -> {
                i += 3
                while (i < n && !(src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"')) i++
                i = minOf(i + 3, n)
            }

            c == '"' -> {
                i++
                while (i < n && src[i] != '"') {
                    if (src[i] == '\\') i++
                    i++
                }
                i = minOf(i + 1, n)
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

// =====================================================================================
// 合成入站消息 / 合成配置
// =====================================================================================

/** 合成一条入站消息（默认私聊 TEXT，`createTimeMillis` 取「现在」⇒ 不被 StaleFilter 丢）。 */
internal fun msg(
    messageId: String = "om_${System.nanoTime()}",
    chatId: String = "oc_test",
    chatType: ChatType = ChatType.P2P,
    senderId: String = "ou_alice",
    senderName: String = "Alice",
    text: String = "ping",
    kind: InboundKind = InboundKind.TEXT,
    mentionAll: Boolean = false,
    mentionedBot: Boolean = false,
    rootId: String? = null,
    threadId: String? = null,
    createTimeMillis: Long = System.currentTimeMillis(),
): InboundMessage = InboundMessage(
    messageId = messageId,
    chatId = chatId,
    chatType = chatType,
    senderId = senderId,
    senderName = senderName,
    text = text,
    kind = kind,
    rawContentType = "text",
    resources = emptyList(),
    mentions = emptyList(),
    mentionAll = mentionAll,
    mentionedBot = mentionedBot,
    rootId = rootId,
    threadId = threadId,
    replyToMessageId = null,
    createTimeMillis = createTimeMillis,
)

/**
 * 合成一份配置。🔴 默认 `mode="blocklist"` 且黑名单为空 ⇒ sender 准入全放行；
 * 默认 `agent.groupMode="mention"`（P3 agent-first 唯一真源）⇒ 群@门控开着——群聊未@即拒，
 * 测试要放行群消息时显式传 `groupMode = "all"` 或让消息 @机器人。
 */
internal fun testConfig(
    routes: List<RouteRule> = listOf(SystemRoute()),
    trustedUsers: List<String> = emptyList(),
    groupMode: String = "mention",
    maxConcurrentAgents: Int = 2,
    chatQueueLimit: Int = 2,
    queueWaitSeconds: Long = 30L,
    busyReplyCooldownSeconds: Long = 30L,
    taskTimeoutSeconds: Long = 60L,
    agentTimeoutMinutes: Long = 10L,
    agentIdleTimeoutSeconds: Long = 120L,
    // P2 agent-first（2026-09-26）：dispatchEnabled 字段已删——App 形态（koog 在位）恒前缀短路，
    // 本函数不再合成该开关；测试里「非 Koog 执行器」即 CLI 形态替身（前缀照旧命中）。
    // P3（2026-09-26）：rejectionReply / groupRequireMention / unhandledReply 三模板字段已删
    // （拒绝恒静默 / 群门控真源=agent.groupMode / TEXT 兜底归调度 agent）——helper 不再合成。
): BridgeConfig = BridgeConfig(
    access = AccessConfig(trustedUsers = trustedUsers),
    runtime = RuntimeConfig(
        maxConcurrentAgents = maxConcurrentAgents,
        chatQueueLimit = chatQueueLimit,
        queueWaitSeconds = queueWaitSeconds,
        busyReplyCooldownSeconds = busyReplyCooldownSeconds,
        taskTimeoutSeconds = taskTimeoutSeconds,
        agentTimeoutMinutes = agentTimeoutMinutes,
        agentIdleTimeoutSeconds = agentIdleTimeoutSeconds,
    ),
    agent = com.jiaoay.lark.bridge.config.AgentConfig(groupMode = groupMode),
    routes = routes,
)

// =====================================================================================
// 假件
// =====================================================================================

/** [AgentRunner] 的假件（T8 才实现真件；本轮只需要 seam 可编译可断言）。 */
internal class FakeAgentRunner(
    override val backendId: String = "fake",
    override val isAvailable: Boolean = true,
) : AgentRunner {

    val requests = CopyOnWriteArrayList<AgentRequest>()
    val lines = CopyOnWriteArrayList<AgentLine>()
    val resetKeys = CopyOnWriteArrayList<String>()
    private val resultRef = AtomicReference(AgentRunResult(exitCode = 0, summary = "fake ok"))
    private val bodyRef = AtomicReference<(suspend (AgentRequest) -> AgentRunResult)?>(null)

    var result: AgentRunResult
        get() = resultRef.get()
        set(value) = resultRef.set(value)

    /** 自定义执行体（测「agent 长跑」「agent 卡死不产出」时用）。 */
    var body: (suspend (AgentRequest) -> AgentRunResult)?
        get() = bodyRef.get()
        set(value) = bodyRef.set(value)

    override suspend fun run(request: AgentRequest, onLine: (AgentLine) -> Unit): AgentRunResult {
        requests.add(request)
        val custom = bodyRef.get()
        if (custom != null) return custom(request)
        lines.forEach { onLine(it) }
        return resultRef.get()
    }

    override suspend fun resetSession(sessionKey: String): Boolean {
        resetKeys.add(sessionKey)
        return true
    }
}

/** [ReplySink] 的录制假件：记录每一次出站，可编程返回值与挂起时长。 */
internal class RecordingReplySink(
    override val target: LarkTarget,
    private val sendResultProvider: () -> SendResult = { SendResult(ok = true, messageId = "om_reply") },
) : ReplySink {

    val texts = CopyOnWriteArrayList<String>()
    val markdowns = CopyOnWriteArrayList<String>()
    val cards = CopyOnWriteArrayList<CardSpec>()
    val reactions = CopyOnWriteArrayList<String>()
    val streams = CopyOnWriteArrayList<SyntheticCardStream>()
    val calls = AtomicInteger(0)
    private val delayMillis = AtomicLong(0L)

    /** 出站挂起时长（测「回复慢不冻结 intake」用）。 */
    var replyDelayMillis: Long
        get() = delayMillis.get()
        set(value) = delayMillis.set(value)

    override val chatId: String? get() = (target as? LarkTarget.Chat)?.chatId

    override suspend fun text(body: String): SendResult {
        calls.incrementAndGet()
        texts.add(body)
        waitIfNeeded()
        return sendResultProvider()
    }

    override suspend fun markdown(body: String): SendResult {
        calls.incrementAndGet()
        markdowns.add(body)
        waitIfNeeded()
        return sendResultProvider()
    }

    override suspend fun card(spec: CardSpec): SendResult {
        calls.incrementAndGet()
        cards.add(spec)
        waitIfNeeded()
        return sendResultProvider()
    }

    override fun stream(initial: CardSpec): CardStreamSink =
        SyntheticCardStream(target, initial).also { streams.add(it) }

    /** 文件出站记录（2026-09-12 产物回传）；默认成功，可注入 sendResultProvider 的返回。 */
    val files = CopyOnWriteArrayList<Pair<java.nio.file.Path, String?>>()

    override suspend fun file(file: java.nio.file.Path, displayName: String?): SendResult {
        calls.incrementAndGet()
        files.add(file to displayName)
        waitIfNeeded()
        return sendResultProvider()
    }

    override suspend fun react(messageId: String, emojiKey: String, add: Boolean): Boolean {
        calls.incrementAndGet()
        reactions.add("$messageId:$emojiKey:$add")
        return true
    }

    /** 已答态卡更新记录（2026-09-15 确认卡已答态批）；默认成功（测试可改 sendResultProvider）。 */
    val cardUpdates = CopyOnWriteArrayList<Pair<String, CardSpec>>()

    override suspend fun updateExistingCard(messageId: String, spec: CardSpec): SendResult {
        calls.incrementAndGet()
        cardUpdates.add(messageId to spec)
        return sendResultProvider()
    }

    /** 全部出站正文（text + markdown 合流，断言最常用）。 */
    fun allBodies(): List<String> = texts + markdowns

    private suspend fun waitIfNeeded() {
        val wait = delayMillis.get()
        if (wait > 0L) delay(wait)
    }
}

/** 可编程 [MessageHandler]：weight / match / handle 三处全部可注入。 */
internal class ScriptedHandler(
    override val kind: RouteKind = RouteKind.SYSTEM,
    override val weight: HandlerWeight = HandlerWeight.LIGHT,
    override val timeoutSeconds: Long? = null,
    private val matcher: (RequestContext) -> Boolean = { true },
    private val body: suspend (RequestContext) -> HandlerOutcome = { ctx ->
        HandlerOutcome.success(Admission.taskKindOf(kind), "handled:${ctx.text}")
    },
) : MessageHandler {

    val invocations = AtomicInteger(0)
    val seenTexts = CopyOnWriteArrayList<String>()
    val startedAt = CopyOnWriteArrayList<Long>()
    private val clockRef = AtomicReference<() -> Long>({ System.currentTimeMillis() })

    /** 注入时钟（记录 handle 进入时刻，测并发窗口用）。 */
    fun withClock(clock: () -> Long): ScriptedHandler = also { clockRef.set(clock) }

    override fun match(ctx: RequestContext): Boolean = matcher(ctx)

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        invocations.incrementAndGet()
        seenTexts.add(ctx.text)
        startedAt.add(clockRef.get()())
        return body(ctx)
    }
}

/** 一次 ⑤ Observe 交接的记录。 */
internal data class RecordedOutcome(val ctx: RequestContext, val outcome: HandlerOutcome) {
    val summary: String get() = outcome.summary
    val errorCode: String? get() = outcome.errorCode
    val text: String get() = ctx.text
    val chatId: String get() = ctx.chatId
}

// =====================================================================================
// 全链路装配 Harness
// =====================================================================================

/** 回复出口的两种形态。 */
internal enum class ReplyMode {
    /** 用 [RecordingReplySink]（不经网关/频控；大多数管线语义测试用这个，隔离度最高）。 */
    RECORDING,

    /** 用真 [GatewayReplySink] + [SyntheticGateway] + [RateGovernor]（测频控与出站链路时用）。 */
    GATEWAY,
}

/**
 * 把 ①→⑤ 全链路按 T9 装配点的同形方式接起来（🔴 装配顺序与生产一致，测试因此是「接线的回归」而不只是单元回归）。
 *
 * @param dispatcher 六个具名 dispatcher 统一替换成它（[BridgeDispatchers.all]）；
 *        虚拟时间测试传 `StandardTestDispatcher(scheduler)`，真并发测试传 `Dispatchers.Default`。
 * @param clock 全链路统一时钟（StaleFilter / MessageDeduper / ChatLanes / TimeoutPolicy / RateGovernor 共用）。
 */
internal class Harness(
    dispatcher: CoroutineDispatcher,
    initialConfig: BridgeConfig = testConfig(),
    kinds: HandlerKinds = HandlerKinds.EMPTY,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: BridgeLog = BridgeLog.Silent,
    replyMode: ReplyMode = ReplyMode.RECORDING,
    perChatRps: Int = GatewayLimits.perChatRps,
    globalRps: Int = GatewayLimits.globalRps,
    sleeper: suspend (Long) -> Unit = { delay(it) },
    deduperCapacity: Int = MessageDeduper.MAX_ENTRIES,
    deduperTtlMillis: Long = MessageDeduper.TTL_MILLIS,
    staleWindowMillis: Long = GatewaySdkSettings.DEFAULT.staleMessageWindowMs,
    maxLanes: Int = ChatLanes.MAX_LANES,
    queueCapacity: Int = InboundQueue.CAPACITY,
    /** 批 6：未命中兜底调度 agent seam（null = 既有测试零改动；注入即测 fallback 三态）。 */
    fallbackHandlerProvider: ((RequestContext) -> MessageHandler?)? = null,
    /**
     * 2026-09-14 闸门对齐批：生效执行器注入缝（null = 既有 FakeAgentRunner，存量测试零改动）。
     * 注入 Koog 语义 runner（backendId="koog"）即测 AgentHandler 前缀短路的三联判定；
     * 注入 isAvailable=false 即测「调度 agent 不可用回落直跑」死区修复。
     */
    agentOverride: AgentRunner? = null,
) : AutoCloseable {

    /** 生效配置（HOT：可运行时替换，`configProvider` 每次都读到最新值）。 */
    private val configRef = AtomicReference(initialConfig)

    val configProvider: () -> BridgeConfig = { configRef.get() }

    var config: BridgeConfig
        get() = configRef.get()
        set(value) = configRef.set(value)

    val counters = PipelineCounters()
    val lanes = ChatLanes(maxLanes, clock)
    val deduper = MessageDeduper(deduperCapacity, deduperTtlMillis, clock)
    val staleFilter = StaleFilter(staleWindowMillis, clock)
    val accessControl = AccessControl(configProvider = { configRef.get().access })

    /** PolicyGate 拒绝时产出的 `GatewaySignal.Rejected`（T9 会把它并进网关 signals）。 */
    val rejectedSignals = CopyOnWriteArrayList<GatewaySignal.Rejected>()
    // P2：agentGroupModeProvider 参数插在 onRejected 前 ⇒ trailing lambda 会错位，改具名传。
    // P3（2026-09-26）：第三参必传——群@门控真源 = agent.groupMode，现读（HOT）。
    val policyGate = PolicyGate(
        { configRef.get().access },
        onRejected = { rejectedSignals.add(it) },
        agentGroupModeProvider = { configRef.get().agent.groupMode },
    )

    val gateway = SyntheticGateway(clock = clock)
    val rateGovernor = RateGovernor(perChatRps, globalRps, clock, sleeper)
    val dispatchers = BridgeDispatchers.all(dispatcher)
    /** 生效执行器（2026-09-14：agentOverride 注入缝——默认假件零改动，注入即测 Koog 短路语义）。 */
    val agent: AgentRunner = agentOverride ?: FakeAgentRunner()
    val processes = ProcessRegistry(initialConfig.runtime.maxConcurrentAgents)

    /** 每次 `RequestContextFactory.create` 产出的 [RecordingReplySink]（按创建序）。 */
    val replies = CopyOnWriteArrayList<RecordingReplySink>()

    /** ⑤ Observe 的交接记录（`onOutcome`）。 */
    val outcomes = CopyOnWriteArrayList<RecordedOutcome>()

    val snapshotFactory = PipelineSnapshotFactory(kinds, HandlerDeps(configProvider, log), log)
    val router = PipelineRouter(snapshotFactory.build(initialConfig), log)

    val supervisor: BridgeSupervisor = BridgeSupervisor.create(dispatcher)
    val runtime: BridgeRuntime = supervisor.newRuntime(dispatchers)

    val permits = PermitGate(initialConfig.runtime.maxConcurrentAgents, runtime.admissionScope)
    val timeouts = TimeoutPolicy(configProvider)

    val admission = Admission(
        runtime = runtime,
        configProvider = configProvider,
        lanes = lanes,
        timeouts = timeouts,
        counters = counters,
        permits = permits,
        log = log,
        clock = clock,
        onOutcome = { ctx, outcome -> outcomes.add(RecordedOutcome(ctx, outcome)) },
        fallbackHandlerProvider = fallbackHandlerProvider,
    )

    /** intake 队列丢弃回调 ⇒ 计入 [PipelineCounters] 并转发成网关 `Dropped` 信号（D18）。 */
    val queue = InboundQueue(queueCapacity, log, InboundQueue.WHERE) { where, total ->
        counters.recordIntakeQueueDropped()
        gateway.emitSignal(GatewaySignal.Dropped(where, total))
    }

    val contexts = RequestContextFactory(
        configProvider = configProvider,
        gateway = gateway,
        rateGovernor = rateGovernor,
        dispatchers = dispatchers,
        agent = agent,
        processes = processes,
        log = log,
        onRateLimited = { chatId, resetSec -> rateGovernor.notifyRateLimited(chatId, resetSec) },
        replyOverride = if (replyMode == ReplyMode.RECORDING) {
            { message -> RecordingReplySink(LarkTarget.ofInbound(message)).also { replies.add(it) } }
        } else {
            null
        },
        progressOverride = { ConflatedProgressSink(clock) },
    )

    val intake = EventIntake(
        queue, staleFilter, deduper, accessControl, policyGate, router, admission, contexts, counters, log,
    )

    /** 启动 ① intake 单消费者协程（挂在 `runtime.intakeScope` 上，与生产同形）。 */
    fun startIntake(): kotlinx.coroutines.Job = intake.start(runtime.intakeScope)

    /** 直接跑一条消息（不经队列协程；🔴 非挂起，与生产 `processOne` 同一个函数）。 */
    fun process(message: InboundMessage) = intake.processOne(message)

    /**
     * 替换配置并重建 + 原子发布路由快照（模拟 T9 的 HOT 订阅回调）。
     * @return **新发布的那张快照**（🔴 不是 `router.publish` 的返回值——后者按契约返回**被替换掉的旧快照**）。
     */
    fun applyConfig(next: BridgeConfig): PipelineSnapshot {
        configRef.set(next)
        val built = snapshotFactory.build(next)
        router.publish(built)
        return built
    }

    /** 只替换 routes 后重建快照。 */
    fun applyRoutes(routes: List<RouteRule>): PipelineSnapshot = applyConfig(config.copy(routes = routes))

    /** 最近一条 Observe 交接（无则 null）。 */
    fun lastOutcome(): RecordedOutcome? = outcomes.lastOrNull()

    /**
     * 轮询直到 [condition] 成立。
     * 🔴 Harness 一律用**真实** dispatcher（`Dispatchers.Default`）而非虚拟时间：任务协程、许可等待、
     *    空闲看门狗都跑真并发，断言因此覆盖真实的竞态面。收敛用轮询而非 `Thread.sleep(固定值)`。
     */
    fun awaitUntil(timeoutMillis: Long = 10_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(2L)
        }
        return condition()
    }

    /** 等待 ⑤ Observe 至少交接 [count] 条，返回当时的快照。 */
    fun awaitOutcomes(count: Int, timeoutMillis: Long = 10_000L): List<RecordedOutcome> {
        awaitUntil(timeoutMillis) { outcomes.size >= count }
        return outcomes.toList()
    }

    /** 等待在途任务归零。 */
    fun awaitQuiescent(timeoutMillis: Long = 10_000L): Boolean = awaitUntil(timeoutMillis) { admission.inFlight == 0 }

    /** 全部出站正文（RECORDING 模式下汇总所有 sink）。 */
    fun allReplyBodies(): List<String> = replies.flatMap { it.allBodies() }

    override fun close() {
        runCatching { queue.close() }
        runCatching { gateway.close() }
        runCatching { runtime.cancel() }
        runCatching { supervisor.close() }
        runCatching { processes.close() }
    }
}
