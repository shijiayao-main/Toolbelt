// ① Intake 阶段本体——单协程、Dispatchers.Default、全程零锁。
// 🔴 本文件属 intake 零锁清单（IntakeGuardTest 守护）：processOne 非挂起、零锁、零 IO——违反即挂死 intake 单消费者协程、全链路停摆。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.dispatch.BridgeDispatchers
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.MessageGateway
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [RequestContext] 的每消息工厂（把「跨消息稳定」的依赖收敛成一处构造注入）。
 *
 * **依赖全部构造注入**：无任何 object/companion 级可变状态；
 *    测试可用 `FakeAgentRunner` / `RecordingReplySink` 整体替换（见 `PipelineTestSupport`）。
 * 每消息**只**构造一次 ctx（[EventIntake.processOne] 里按需惰性创建），热路径不再复制。
 *
 * @param replyOverride 测试钩子：非 null 时用它替代 [GatewayReplySink]（断言出站内容而不真连飞书）。
 * @param homeDataRoot 宿主机密根（reply 文本出站面敏感过滤的判据输入；null = 机密根判据跳过，
 *    既有测试替身零改动——装配点与文件通道 `deps.dataRootProvider()` 同源注入）。
 */
class RequestContextFactory(
    private val configProvider: () -> BridgeConfig,
    private val gateway: MessageGateway,
    private val rateGovernor: RateGovernor,
    private val dispatchers: BridgeDispatchers,
    private val agent: AgentRunner,
    private val processes: ProcessRegistry,
    private val log: BridgeLog = BridgeLog.Silent,
    private val onRateLimited: ((chatId: String?, resetSec: Int?) -> Unit)? = null,
    private val replyOverride: ((InboundMessage) -> ReplySink)? = null,
    private val progressOverride: ((InboundMessage) -> ProgressSink)? = null,
    private val homeDataRoot: File? = null,
) {

    /** 出站目标推导：一律用 `LarkTarget.ofInbound`（p2p/group 通用，chatId 由 SDK 给出）。 */
    fun targetOf(message: InboundMessage): LarkTarget = LarkTarget.ofInbound(message)

    companion object {
        /**
         * 引用锚定目标：群聊锚定提问消息、私聊豁免（P2P 引用样式冗余）。
         * 与表情链同源（BuiltinHandlers 的 react 也用 ctx.message.messageId）。
         * 🔴 单一真源：BridgeAssembler 的 replyOverride 闭包与 [create] 都调用本函数（曾内联复制过判定，已收敛——
         *    双写会让测试背书的判定与生产运行的判定静默分叉）。
         * 🔴 卡片回传豁免：`fromCardAction` 硬编码 `chatType = UNKNOWN`（卡片回传事件
         *    不提供 chatType）⇒ P2P 判定对整条 cardAction 路径恒失效，私聊里点确认按钮的裁决回复会被错误锚定，
         *    违背「私聊豁免」。锚定语义是「锚到用户的**提问**」，卡片回传不是提问（它是用户对机器人的交互），
         *    一律不锚定。
         */
        fun replyAnchorOf(message: InboundMessage): String? = when {
            message.kind == InboundKind.CARD_ACTION -> null
            message.chatType == com.jiaoay.lark.gateway.ChatType.P2P -> null
            else -> message.messageId
        }
    }

    /**
     * 造一个 ctx。纯构造、零 IO、零挂起、零锁。
     * [RequestContext.route] 初值为 null，由 [PipelineRouter.route] 逐个候选写入并在命中时定格。
     */
    fun create(message: InboundMessage): RequestContext {
        val reply = replyOverride?.invoke(message)
            ?: GatewayReplySink(
                gateway = gateway,
                target = targetOf(message),
                rateGovernor = rateGovernor,
                netDispatcher = dispatchers.net,
                log = log,
                onRateLimited = onRateLimited,
                // 引用锚定：群聊回复提问消息（引用气泡定位「谁的提问」）；
                // 私聊豁免（P2P 引用样式冗余）。锚定 id 与表情链同源（ctx.message.messageId）。
                replyToMessageId = replyAnchorOf(message),
                homeDataRoot = homeDataRoot,
            )
        val progress = progressOverride?.invoke(message) ?: ConflatedProgressSink()
        return RequestContext(
            message = message,
            config = configProvider(),
            route = null,
            reply = reply,
            progress = progress,
            log = log,
            agent = agent,
            processes = processes,
        )
    }
}

/**
 * ① Intake：**单协程、`Dispatchers.Default`、全程零锁**。
 *
 * ## 六步顺序（固定管线序）
 * `StaleFilter` → `MessageDeduper` → `AccessControl.decide` → `PolicyGate` → `PipelineRouter` → ② [Admission]
 *
 * 🔴 **访问控制前置于路由**：未授权发送者**绝不**触发任何 handler，
 *    连路由匹配都不做 ⇒ 不产生任何 agent 进程、不占任何许可。
 *
 * ## 「Intake 单消费者本身就是并发原语」
 * 全部状态（去重 LRU、路由快照读、ChatLane 表的 acquire、计数器）**只在这一个协程里被串行访问**，
 * 因此不需要任何互斥：
 * - [MessageDeduper] 的 `LinkedHashMap` 是普通非线程安全集合 ⇒ 足够；
 * - [PipelineRouter] 的读侧只有一次 `AtomicReference.get()`；
 * - [ChatLanes.tryAcquire] 用 CAS 而非锁（因为它还要被 task 协程的 `release` 跨线程调用）。
 * 计数器用 `AtomicLong` 只为**跨线程可见性**（metrics 协程 / UI 要读），不是互斥需求。
 * 🔴 这条纪律由 `IntakeGuardTest` 以 grep 断言固化（intake 路径零锁）。
 *
 * ## [processOne] 为什么是**非挂起**的
 * intake 是全链路的**唯一串行点**：它一旦挂起（等许可、等出站、等频控），所有会话全部停摆，
 * 且 SDK 回调侧的 `Channel(512, DROP_OLDEST)` 会开始丢消息。因此：
 * - 许可等待 / 排队超时 / 出站 / 频控挂起 **全部**下放到 ② [Admission] 用 `launchTask` 出来的任务协程里；
 * - [processOne] 只做纯 CPU 判定 + 一次 `launch`，O(1) 返回（`IntakeLatencyTest` 用 1 万条合成消息实测）。
 *
 * ## 单条消息异常绝不拖垮 intake
 * [processOne] 被 `runCatching` 包裹：任何 handler 工厂/配置读/出站构造抛异常，只记 ERROR + 计数，
 * 循环继续取下一条。intake 协程**唯一**的正常退出条件是 [InboundQueue.receive] 返回 null（队列关闭且排空）。
 *
 * @param scope 传 `BridgeRuntime.intakeScope`（`intakeJob + Dispatchers.Default`）。
 */
class EventIntake(
    private val queue: InboundQueue,
    private val staleFilter: StaleFilter,
    private val deduper: MessageDeduper,
    private val accessControl: AccessControl,
    private val policyGate: PolicyGate,
    private val router: PipelineRouter,
    private val admission: Admission,
    private val contexts: RequestContextFactory,
    private val counters: PipelineCounters,
    private val log: BridgeLog = BridgeLog.Silent,
) {

    /** 已处理条数（= [PipelineCounters.intakeReceived]，此处独立一份便于「intake 协程确实在推进」的断言）。 */
    private val processed = java.util.concurrent.atomic.AtomicLong(0)

    /** 累计处理条数（`Handler3SecondTest` / `IntakeLatencyTest` 断言 intake 仍在推进）。 */
    val processedCount: Long get() = processed.get()

    /**
     * 启动 intake 单消费者协程（一个 [Job]，全生命周期只有这一个消费者）。
     * 队列 [InboundQueue.close] 后 [InboundQueue.receive] 排空即返回 null ⇒ 协程正常收尾（优雅排空）。
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        log.info("intake 单消费者协程已启动（capacity=${queue.capacity}）")
        while (isActive) {
            val message = queue.receive() ?: break
            runCatching { processOne(message) }.onFailure { error ->
                counters.recordHandlerError()
                log.error("intake 处理单条消息失败（已跳过，循环继续）messageId=${message.messageId}", error)
            }
        }
        log.info("intake 单消费者协程已收尾（processed=${processed.get()}）")
    }

    /**
     * 处理一条消息（① Intake 的五步 + 交给 ② [Admission]）。
     * 🔴 **非挂起、O(1)、零锁、零 IO**；任何一步判否都直接 return（短路），不做多余分配。
     */
    fun processOne(message: InboundMessage) {
        counters.recordReceived()
        // 先计数再判定：丢弃路径也算「intake 处理过一条」（`IntakeLatencyTest` 的 1 万条均值分母）。
        processed.incrementAndGet()

        // 第 1 步：陈旧过滤（NF-A7 的三条边界见 StaleFilter）
        if (!staleFilter.test(message)) {
            counters.recordStaleDropped()
            log.debug("丢弃陈旧消息 messageId=${message.messageId} age=${staleFilter.ageOf(message)}ms")
            return
        }

        // 第 2 步：幂等去重（🔴 key = messageId，绝不用 eventId——key 选错即重投消息漏去重或异消息被误杀）
        when (deduper.decide(message.messageId)) {
            DedupDecision.DUPLICATE -> {
                counters.recordDuplicateDropped()
                log.debug("丢弃重复消息 messageId=${message.messageId}")
                return
            }

            DedupDecision.BLANK_ID -> {
                counters.recordBlankIdDropped()
                log.warn("丢弃空 messageId 的事件（无幂等键 ⇒ 无法去重）chatId=${message.chatId}")
                return
            }

            DedupDecision.ACCEPT -> Unit
        }

        // 第 3 步：sender 准入——当前恒放行（sender 级 allow/block 已退役）。
        //     保留判定点是为了守住既有五步链结构（IntakeGuardTest 锚）与计数位；
        //     高风险授权不在此层，而是由 TrustPolicy 在工具调用处按用户判定。
        accessControl.decide(message.senderId)

        // 第 4 步：策略门控（群@门控；D 段「拒绝事件可见性」——PolicyGate 已发 Rejected 信号 + 计数）
        if (!policyGate.test(message)) {
            counters.recordPolicyRejected()
            // 群@门控拒绝**不回** rejectionReply：那是给「未授权用户」的文案；群里没@机器人属于正常闲聊，
            //    回复反而是噪音。只发 Rejected 信号供 UI/审计可见。
            admission.submitRejection(contexts.create(message), null)
            return
        }

        // 第 5 步：路由（顺序即优先级）+ 交给 ② Admission
        val ctx = contexts.create(message)
        val entry = router.route(ctx)
        admission.submit(ctx, entry)
    }

    /** 各阶段计数的一次性投影（排障 / 测试）。 */
    fun describe(): String =
        "EventIntake(processed=$processedCount, stale=${staleFilter.dropped}, dup=${deduper.duplicateCount}, " +
            "access=${accessControl.allowlistRejections + accessControl.blocklistRejections}, " +
            "policy=${policyGate.rejectedCount}, unmatched=${router.unmatchedCount}, queueDropped=${queue.dropped})"
}
