// 🔴 唯一装配点：assemble(...) 是 App 与 CLI 唯一共用的装配点，管线各件构造顺序与
//    PipelineTestSupport.Harness 逐字同形（Harness 是接线的测试背书，本文件是生产实现）——
//    调整构造顺序须两处同步。
// 🔴 装配纪律：零 ProcessBuilder（程序调用一律经 ProgramLauncher）、
//    零 synchronized/Mutex、零 @Volatile var（一律 AtomicReference / CopyOnWriteArrayList）、
//    零 com.jiaoay.easy_dev_toolbox、审计只 offer SinkRecord（编码在 JsonlSink）、
//    不吞 CancellationException；发送者统计经 suspend SenderStatsRecorder，统计路径零 runBlocking。
//    违反即出错：ProcessBuilder 走 shell 语义、Windows ANSI charset 会破坏 agent 的 UTF-8 流解析；
//    锁/阻塞原语会卡死 intake 单消费者协程；写路径 runBlocking 会让 intake 挂起在统计上；吞 CE 任务永不结束。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.agent.NoAgentRunner
import com.jiaoay.lark.bridge.agent.ProgressAccumulator
import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.ConfigLoadResult
import com.jiaoay.lark.bridge.config.ConfigPaths
import com.jiaoay.lark.bridge.config.ConfigRepository
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.confirm.AskUserBus
import com.jiaoay.lark.bridge.confirm.ConfirmNotifier
import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import com.jiaoay.lark.bridge.confirm.ConfirmService
import com.jiaoay.lark.bridge.confirm.ToolConfirmGate
import com.jiaoay.lark.bridge.dispatch.BridgeDispatchers
import com.jiaoay.lark.bridge.dispatch.BridgeRuntime
import com.jiaoay.lark.bridge.dispatch.BridgeSupervisor
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.files.CardReplyLedger
import com.jiaoay.lark.bridge.files.DownloadStore
import com.jiaoay.lark.bridge.files.FileRegistry
import com.jiaoay.lark.bridge.files.PreprocessPipeline
import com.jiaoay.lark.bridge.files.ProgramRunner
import com.jiaoay.lark.bridge.files.ReferencedMessageResolver
import com.jiaoay.lark.bridge.handler.AgentHandler
import com.jiaoay.lark.bridge.handler.AskAnswerHandler
import com.jiaoay.lark.bridge.handler.AudioHandler
import com.jiaoay.lark.bridge.handler.AudioTranscriberSeam
import com.jiaoay.lark.bridge.handler.CommandHandler
import com.jiaoay.lark.bridge.handler.CompositeHandler
import com.jiaoay.lark.bridge.handler.DispatchAgentHandler
import com.jiaoay.lark.bridge.handler.FileHandler
import com.jiaoay.lark.bridge.handler.InteractionHandler
import com.jiaoay.lark.bridge.handler.StopHandler
import com.jiaoay.lark.bridge.handler.SystemHandler
import com.jiaoay.lark.bridge.observe.AuditRecords
import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.observe.JsonlSink
import com.jiaoay.lark.bridge.observe.LogBus
import com.jiaoay.lark.bridge.observe.MetricsSampler
import com.jiaoay.lark.bridge.observe.ObservingBridgeLog
import com.jiaoay.lark.bridge.observe.RingLogBus
import com.jiaoay.lark.bridge.observe.SenderStatsRecorder
import com.jiaoay.lark.bridge.observe.SinkChannels
import com.jiaoay.lark.bridge.observe.SinkRecord
import com.jiaoay.lark.bridge.observe.StartupProbeSeam
import com.jiaoay.lark.bridge.observe.UserNameResolver
import com.jiaoay.lark.bridge.observe.toBridgeConnectionStatus
import com.jiaoay.lark.bridge.pipeline.AccessControl
import com.jiaoay.lark.bridge.pipeline.Admission
import com.jiaoay.lark.bridge.pipeline.CardJson
import com.jiaoay.lark.bridge.pipeline.ChatLanes
import com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink
import com.jiaoay.lark.bridge.pipeline.EventIntake
import com.jiaoay.lark.bridge.pipeline.GatewayNativeChatSurface
import com.jiaoay.lark.bridge.pipeline.GatewayReplySink
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.HandlerKinds
import com.jiaoay.lark.bridge.pipeline.InboundQueue
import com.jiaoay.lark.bridge.pipeline.MessageDeduper
import com.jiaoay.lark.bridge.pipeline.MessageHandler
import com.jiaoay.lark.bridge.pipeline.PermitGate
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import com.jiaoay.lark.bridge.pipeline.PipelineRouter
import com.jiaoay.lark.bridge.pipeline.PipelineSnapshotFactory
import com.jiaoay.lark.bridge.pipeline.PolicyGate
import com.jiaoay.lark.bridge.pipeline.RateGovernor
import com.jiaoay.lark.bridge.pipeline.ReplySink
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.RequestContextFactory
import com.jiaoay.lark.bridge.pipeline.SingleInstanceLock
import com.jiaoay.lark.bridge.pipeline.StaleFilter
import com.jiaoay.lark.bridge.pipeline.TimeoutPolicy
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.CardStreamSink
import com.jiaoay.lark.gateway.GatewayLimits
import com.jiaoay.lark.gateway.GatewaySdkSettings
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.Outbound
import com.jiaoay.lark.gateway.SendResult
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次 start/stop 周期的**运行时束**：五阶段管线的全部活件 + 落盘/指标/确认总线 + 开放卡片流登记。
 *
 * ## 为什么按周期成束（而非 Bridge 常驻字段）
 * 每次 [Bridge.start] 都要一棵**全新的**协程作用域树（[BridgeRuntime]）与全新的网关连接；stop 时整束关停。
 * 把「本周期活件」收进一个不可变束，[Bridge.teardownBundle] 的十步关闭因此只需对着一个对象逐步收尾，
 * 不会漏掉某个散落字段（TeardownCompletenessTest 的断言面）。
 *
 * ## 与 PipelineTestSupport.Harness 同形
 * [create] 的构造顺序与 Harness 逐字一致（intake→admission→contexts→router→permits→…），
 * 差别仅在于把 Harness 的假件（SyntheticGateway/FakeAgentRunner/RecordingReplySink）
 * 换成生产件（gatewayFactory()/注入的 AgentRunner/TrackingReplySink+GatewayReplySink）。
 */
internal class RuntimeBundle private constructor(
    val gateway: MessageGateway,
    val runtime: BridgeRuntime,
    val queue: InboundQueue,
    val processes: ProcessRegistry,
    val permits: PermitGate,
    val metrics: MetricsSampler,
    /** 引用流稳定件（跨 start/stop 存活；stop 无需专门收尾——rescan 幂等、evict 无在途协程）。 */
    val downloadStore: DownloadStore,
    /** 机器人回复账本：handleSignal 收 StreamTerminal 写入；resolver 查询。 */
    val cardReplyLedger: CardReplyLedger,
    private val admission: Admission,
    private val intake: EventIntake,
    private val router: PipelineRouter,
    private val snapshotFactory: PipelineSnapshotFactory,
    private val rateGovernor: RateGovernor,
    /** 开放卡片流登记（stop 第 ④ 步遍历发终态卡 + close）。 */
    private val openStreams: CopyOnWriteArrayList<CardStreamSink>,
    private val auditSinkJob: Job,
    private val statsSinkJob: Job,
    private val log: BridgeLog,
    // ---- 在线状态接线（connectionUpdater / onMetricsTick）----
    /** 方案C：重连/恢复信号 → Bridge 写 connectionRef（含状态机守卫）+ publishStatus 补帧。null = 测试窄面。 */
    private val connectionUpdater: ((BridgeConnectionStatus) -> Unit)? = null,
    /** 方案A 数据源 + 方案C 刷新：1Hz metrics tick → Bridge.publishStatus()（修 UI 计数冻结帧欠账）。null = 测试窄面。 */
    private val onMetricsTick: (() -> Unit)? = null,
) {

    /** 当前在途任务数（Admission 计数；stop 时投影到 `Draining(activeTasks)`）。 */
    fun admissionInFlight(): Int = admission.inFlight

    /**
     * 启动本周期的活协程：① intake 单消费者 + gateway.inbound 收集 + gateway.signals 收集 + 1Hz 指标采样。
     * audit/stats 两条落盘协程在 [create] 时即已 launch（消费 [SinkChannels]，空闲时零唤醒挂起）。
     */
    fun startLoops() {
        intake.start(runtime.intakeScope)
        runtime.gatewayScope.launch {
            gateway.inbound.collect { message ->
                // 🔴 SDK 回调侧已 trySend 进网关内部 channel；此处把网关 inbound 流转投引擎入站队列（同样只 trySend——
 //    阻塞 send 会在队列满时挂死网关回调侧）。
                runCatching { queue.trySend(message) }
                    .onFailure { log.warn("入站转投失败 messageId=${message.messageId}: ${it.message}") }
            }
        }
        runtime.signalScope.launch {
            gateway.signals.collect { signal -> handleSignal(signal) }
        }
        runtime.metricsScope.launch { metrics.runLoop() }
        // 1Hz tick → Bridge.publishStatus()：把 metrics 真实 1Hz 快照汇入 _status（runLoop 只写
        //    自己的 latestRef，不触达 publishStatus；无此回调时 UI/CLI 计数在两次生命周期跳变之间恒为冻结帧）。
        //    publishStatus 内部会 bundle.metrics.sample() 采新帧 ⇒ 每 tick 即全量快照。null 时跳过（测试窄面）。
        if (onMetricsTick != null) {
            runtime.metricsScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(METRICS_INTERVAL_MILLIS)
                    runCatching { onMetricsTick.invoke() }
                        .onFailure { log.warn("metrics tick 回调失败（已忽略）:${it.javaClass.simpleName} ${it.message}") }
                }
            }
        }
        // 🔴 启动重建下载账本（崩溃残留 + 上次运行存量 + 清 legacy tmp-*）。
        //    放 metricsScope 旁路协程——IO 有界、失败只 warn，绝不占 intake 单协程。
        runtime.metricsScope.launch {
            runCatching { downloadStore.rescan() }
                .onFailure { log.warn("下载账本 rescan 失败（已忽略，下次 store 后 evict 仍兜底）：${it.message}") }
        }
    }

    /** 网关信号旁路处理：限流信号驱动 [RateGovernor] 精确退避，其余仅记日志（🔴 非挂起、O(1)——挂起/重判定会卡死 signals 收集协程）。 */
    private fun handleSignal(signal: GatewaySignal) {
        when (signal) {
            is GatewaySignal.RateLimited -> rateGovernor.notifyRateLimited(signal.chatId, signal.resetSec)
            is GatewaySignal.Dropped -> log.warn("网关丢弃事件 where=${signal.where} total=${signal.droppedTotal}")
            // SendFailed 的 chatId 槽位在 setReaction 路径装的是 messageId——
            //    字段名中性化为 target，消除「chat=om_xxx」的误导（发消息路径仍是 chatId，语义不变）。
            is GatewaySignal.SendFailed -> log.warn("网关出站失败 code=${signal.code} target=${signal.chatId} ${signal.msg}")
            // 机器人回复落账：流式卡终态全文入账本，供「引用这条回复」本地兜底
            //       （飞书无按 card_id 读卡片实体的 API——正文只在我们自己手里）。失败不阻断信号流。
            is GatewaySignal.StreamTerminal -> runCatching { cardReplyLedger.record(signal.messageId, signal.chatId, signal.markdown) }
                .onFailure { log.warn("机器人回复落账失败 messageId=${signal.messageId}：${it.message}") }
            // ---- 重连迁移具名分支：log.info 而非 debug——CLI serve 的控制台日志是纯 CLI 用户
            // 重连迁移的唯一可见通道。Reconnected 经 gateway.connectionState 现值 + toBridgeConnectionStatus
            // 映射，保住 botOpenId/botName identity——信号本身无载体，直接 Connected() 会丢身份。
            GatewaySignal.Reconnecting -> {
                connectionUpdater?.invoke(BridgeConnectionStatus.Reconnecting)
                log.info("长连接断开，SDK 自动重连中")
            }
            GatewaySignal.Reconnected -> {
                connectionUpdater?.invoke(gateway.connectionState.value.toBridgeConnectionStatus())
                log.info("长连接已恢复")
            }
            else -> log.debug("网关信号：$signal")
        }
    }

    /** HOT 配置热替换：重建路由快照并原子发布（在途任务持旧快照引用继续跑完，不断连）。 */
    fun republishSnapshot(next: BridgeConfig) {
        router.publish(snapshotFactory.build(next))
    }

    /**
     * stop 第 ⑦ 步：优雅收尾两条落盘协程。
     * 🔴 [JsonlSink.run] 的 `finally` 在 [kotlinx.coroutines.NonCancellable] 内 flush + close 写入器，
     *    故 cancel + join 即等价「flushAndClose」——绝不丢在途审计。
     */
    suspend fun flushSinks() {
        auditSinkJob.cancel()
        statsSinkJob.cancel()
        withTimeoutOrNull(SINK_FLUSH_MILLIS) {
            auditSinkJob.join()
            statsSinkJob.join()
        }
    }

    /**
     * stop 第 ④ 步：遍历开放卡片流收尾。
     * [CardStreamSink.close] 幂等：若尚未发终态卡则用当前快照补发一次终态（网关侧另有兜底），
     *    因此这里只需对仍 `isOpen` 的流调 close 即满足「发终态卡 + close」。
     */
    suspend fun closeOpenStreams() {
        val streams = openStreams.toList()
        openStreams.clear()
        streams.forEach { sink ->
            runCatching { if (sink.isOpen) sink.close() }
                .onFailure { log.warn("卡片流收尾失败：${it.message}") }
        }
    }

    companion object {
        /** 落盘协程收尾上限（flush + close 写入器）。 */
        const val SINK_FLUSH_MILLIS: Long = 3_000L

        /** 指标采样间隔（1Hz，对齐 `MetricsSampler.DEFAULT_INTERVAL_MILLIS`）。 */
        const val METRICS_INTERVAL_MILLIS: Long = 1_000L

        /**
         * 装配一束运行时（与 Harness 同形的构造顺序）。由 [Bridge.createBundle] 在每次 start 调用一次。
         *
         * @param configProvider 读**当前生效**配置（HOT：每次执行现读，禁装配期缓存，CDS §16.4）。
         * @param connectionProvider / confirmProvider / runningProvider 供 [MetricsSampler] 采 [Bridge] 的活状态。
         */
        fun create(
            cfg: BridgeConfig,
            paths: ConfigPaths,
            log: BridgeLog,
            logBus: LogBus,
            gatewayFactory: () -> MessageGateway,
            dispatchers: BridgeDispatchers,
            supervisor: BridgeSupervisor,
            counters: PipelineCounters,
            processRegistry: ProcessRegistry,
            confirmService: ConfirmService,
            /**
             * user.ask_user 挂起配对总线（稳定件，由 Bridge 构造期透传——AskAnswerHandler
             * 的 COMMAND 槽接线依赖）。收参必传纪律：漏传即 ask_user 链路死线且编译期零报警。
             */
            askUserBus: AskUserBus,
            /**
             * 确认超时/催办接线稳定槽：本方法在 gateway/rateGovernor 就绪后
             * 写入**本周期**的接线束（已超时卡更新 + 已读查询 + 文字催办）。槽语义见
             * [Bridge.confirmTimedOutSlot] KDoc 与 [ConfirmService.TimedOutWiring]。
             */
            confirmTimedOutSlot: AtomicReference<ConfirmService.TimedOutWiring>,
            sinkChannels: SinkChannels,
            agentRunner: AgentRunner,
            // 🔴 引用流稳定件：由 Bridge 构造期创建并注入，
            //    本方法只接线不 new——stop→start 不再重建（登记表跨启动周期存活）。
            fileRegistry: FileRegistry,
            downloadStore: DownloadStore,
            cardReplyLedger: CardReplyLedger,
            configProvider: () -> BridgeConfig,
            connectionProvider: () -> BridgeConnectionStatus,
            confirmProvider: () -> ConfirmRuntimeStatus,
            runningProvider: () -> Boolean,
            clock: () -> Long,
            // ---- 在线状态接线（均带默认值 ⇒ Harness/测试零改动）----
            /** 方案C：重连/恢复信号回调进 Bridge（状态机守卫在 Bridge 侧）。null = 不接线（Harness 形态）。 */
            connectionUpdater: ((BridgeConnectionStatus) -> Unit)? = null,
            /** 方案A 数据源 + 方案C 刷新：1Hz metrics tick → Bridge.publishStatus()。null = 不接线（Harness 形态）。 */
            onMetricsTick: (() -> Unit)? = null,
            /**
             * 宿主机密根（敏感文件排除判据）——见 [assemble] 同名参数的说明。
             * 缺省 null 时回落 `paths.dataRoot`（`lark/`，覆盖桥接自身配置与审计）。
             */
            sensitiveRoot: java.io.File? = null,
            /** 方案A：「状态」指令的快照 provider（SystemHandler.renderStatus 数据源）。null = 不接线（Harness 形态）。 */
            statusProvider: () -> com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot? = { null },
            /** 未命中兜底调度 agent seam（App 侧注入非 null 即激活判定闭包——恒激活、真源 = Koog 在位与 isAvailable）；null = CLI 形态旧行为。 */
            fallbackHandlerProvider: ((RequestContext) -> MessageHandler?)? = null,
            /**
             * 宿主语音转写缝（App 形态注入；null = CLI/测试形态——AUDIO 消息维持既有降级文案，行为逐字不变）。
             * 收参必传纪律：必须一路透传到未命中兜底判定闭包
             * （漏传即 App 侧语音链路死线且编译期零报警，透传守护锚 BridgeAssemblerParityTest ⑩ 组钉死）。
             */
            audioTranscriber: AudioTranscriberSeam? = null,
        ): RuntimeBundle {
            val gateway = gatewayFactory()
            val runtime = supervisor.newRuntime(dispatchers)
            val openStreams = CopyOnWriteArrayList<CardStreamSink>()

            // ---- ① Intake 五步的活件（与 Harness 逐字同形）----
            val lanes = ChatLanes(clock = clock)
            val deduper = MessageDeduper(clock = clock)
            val staleFilter = StaleFilter(GatewaySdkSettings.DEFAULT.staleMessageWindowMs, clock)
            val accessControl = AccessControl(configProvider = { configProvider().access })
            // 🔴 拒绝诊断文本经 PolicyGate.rejectDiagnostics 纯函数渲染（chatId/senderId 已掩码），
            //    --verbose 下这条 log.debug 会真实打印 ⇒ 绝不允许泄漏完整标识符。
            val policyGate = PolicyGate(
                { configProvider().access },
                onRejected = { rejected -> log.debug(PolicyGate.rejectDiagnostics(rejected)) },
                // App 装配注入群聊唤起模式真源（agent.groupMode）：默认 mention 仅@唤起（成本闸），
                // 群内闲聊不应触发 agent。
                agentGroupModeProvider = { configProvider().agent.groupMode },
            )
            val rateGovernor = RateGovernor(GatewayLimits.perChatRps, GatewayLimits.globalRps, clock)
            val permits = PermitGate(cfg.runtime.maxConcurrentAgents, runtime.admissionScope)
            val timeouts = TimeoutPolicy(configProvider)

            // ---- ⑤ Observe：发送者统计（suspend + UserNameResolver，统计路径零 runBlocking）----
            // InboundMessage.senderName 恒为空 ⇒ 名字一律经 UserNameResolver 补拉：
            // resolver 接入 gateway.resolveSenderName（contact.v3.user.get），
            // 正向 TTL 12h / 负向 TTL 5min / LRU 1024；无 scope 时优雅降级返回 null，不刷日志。
            val userNameResolver = UserNameResolver(
                // 单路化：fetch 关闭（恒 null）——名字/头像全部经
                //    profileFetch（gateway.resolveSenderProfile 一次 API 双产出），防双链路重复打 API。
                fetch = { null },
                profileFetch = { gateway.resolveSenderProfile(it) },
                clock = clock,
            )
            val senderStats = SenderStatsRecorder(
                channels = sinkChannels,
                userNameResolver = { openId -> userNameResolver.nameOf(openId) },
                // 主路：完整身份（名字+头像）与 nameOf 共享同一缓存条目 ⇒ 统计/详情页零重复网络
                profileResolver = { openId -> userNameResolver.profileOf(openId) },
                enabled = cfg.observability.senderStatsEnabled,
                clock = clock,
            )
            // 统计的 suspend 补拉跑在 task 池的旁路协程（不占 intake 单协程、不在写路径 runBlocking）。
            val observeScope = runtime.confirmBusScope

            // ---- 引用流接线件（在 Admission 之前声明：fallback 判定闭包构造 DispatchAgentHandler 需要
            //    referencedFileResolver，而 Kotlin 局部变量捕获要求源码序先声明；本段依赖（gateway/
            //    processRegistry/稳定件/时钟）在上方全部就绪，纯位置交换无前向引用）----
            // 🔴 fileRegistry/downloadStore 为 **Bridge 稳定件**经参数注入
            //    （跨 start/stop 存活——若随周期重建会导致登记表丢失、引用落空）；
            //    preprocess/resolver 是无状态接线件，随快照重建无害。
            val preprocessPipeline = PreprocessPipeline(
                configProvider = configProvider,
                log = log,
                runProgram = ProgramRunner { argv, workDir, totalTimeoutMs, idleTimeoutMs ->
                    // 沙箱/白名单/UTF-8 配方与 agent 执行器一致，
                    //    共享本周期 processRegistry ⇒ 取消/看门狗/登记语义免费获得。
                    val cfg = configProvider()
                    val launcher = com.jiaoay.lark.bridge.exec.ProgramLauncher(
                        policy = com.jiaoay.lark.bridge.exec.WorkspacePolicy.fromConfig(
                            workspaceDir = cfg.workspace.root,
                            workspaceWhitelist = cfg.workspace.extraRoots,
                        ),
                        registry = processRegistry,
                        charset = Charsets.UTF_8,
                        isWindows = com.jiaoay.lark.bridge.exec.EnvAllowlist.isWindows(),
                    )
                    val lines = com.jiaoay.lark.bridge.exec.ProgramLauncher.lineChannel()
                    val result = launcher.launch(
                        spec = com.jiaoay.lark.bridge.exec.ProgramSpec(
                            command = com.jiaoay.lark.bridge.exec.LaunchCommand.spawnCommand(
                                executableFile = java.io.File(argv.first()),
                                args = argv.drop(1),
                                isWindows = com.jiaoay.lark.bridge.exec.EnvAllowlist.isWindows(),
                                fallbackExecutable = argv.first(),
                            ),
                            workingDir = workDir,
                        ),
                        lines = lines,
                        totalTimeoutMillis = totalTimeoutMs,
                        idleTimeoutMillis = idleTimeoutMs,
                    )
                    lines.close()
                    result
                },
            )
            val downloader: suspend (String, String, java.io.File) -> com.jiaoay.lark.gateway.DownloadResult =
                { messageId, fileKey, dest -> gateway.downloadResource(messageId, fileKey, dest.toPath()) }
            // 引用补查 seam：lookup miss 时经 im.v1.message.get 现查——
            // 文件补登记救回旧气泡；文本取回原文作上下文（引用即上下文）。
            val quotedFetcher: suspend (String) -> com.jiaoay.lark.gateway.QuotedMessage? =
                { messageId -> gateway.fetchQuotedMessage(messageId) }
            val referencedFileResolver = ReferencedMessageResolver(
                registry = fileRegistry,
                store = downloadStore,
                preprocess = preprocessPipeline,
                downloader = downloader,
                configProvider = configProvider,
                counters = counters,
                log = log,
                cardReplyLedger = cardReplyLedger,
                quotedFetcher = quotedFetcher,
            )

            // ---- ② Admission（onOutcome = ⑤ Observe 的唯一交接点，🔴 O(1) 非挂起、只 trySend/launch——阻塞即卡死 admission 路径）----
            // statusProvider 注入 HandlerDeps（SystemHandler「状态」指令的数据源）；
            //    默认值 { null } ⇒ Harness/既有测试零改动。（deps 在此声明：fallback 判定闭包要用。）
            val deps = HandlerDeps(
                configProvider,
                log,
                statusProvider,
                // 未显式注入时回落 paths.dataRoot（至少覆盖 lark/ 内的配置与审计）
                dataRootProvider = { (sensitiveRoot ?: paths.dataRoot.toFile()) },
                // 🔴 账本接线：replyLedgerProvider 必须接 cardReplyLedger——漏接则生产链
                //    DispatchPayload.replyLedger 恒 null，fetch_quoted_context /
                //    update_own_card / recall_own_message 的账本守护在生产装配上全部不可用。
                //    稳定件 provider 晚绑定，Harness/测试默认 { null } 零改动。
                replyLedgerProvider = { cardReplyLedger },
                // 能力一级化：L2 两工厂（每消息现造——chatId 绑定，见各自 KDoc）。
                // nativeSurface：gateway + ctx.chatId 捕获进闭包；chatId 空白（罕见测试窄面）⇒ null = L2 不注册。
                // confirmGate：confirmService + 确认卡 notifier + 防替答快照 + 超时（HOT 现读钳制）。
                nativeSurfaceFactory = { ctx ->
                    if (ctx.chatId.isBlank()) null else GatewayNativeChatSurface(gateway, ctx.chatId)
                },
                confirmGateFactory = { ctx ->
                    if (ctx.chatId.isBlank() || ctx.message.senderId.isBlank()) {
                        null
                    } else {
                        ToolConfirmGate(
                            confirmService = confirmService,
                            notifier = GatewayConfirmNotifier(gateway, log),
                            chatId = ctx.chatId,
                            senderOpenId = ctx.message.senderId,
                            timeoutMillis = ConfirmService.clampedConfirmTimeoutMillis(
                                configProvider().agent.confirm.timeoutSeconds,
                            ),
                            log = log,
                        )
                    }
                },
            )
            val admission = Admission(
                runtime = runtime,
                configProvider = configProvider,
                lanes = lanes,
                timeouts = timeouts,
                counters = counters,
                permits = permits,
                log = log,
                clock = clock,
                onOutcome = { ctx, outcome ->
                    if (configProvider().observability.auditEnabled) {
                        sinkChannels.offerAudit(
                            SinkRecord.Audit(
                                ts = clock(),
                                chatId = ctx.chatId,
                                senderOpenId = ctx.message.senderId,
                                prompt = AuditRecords.summarizePrompt(ctx.text),
                                // argv 摘要在 agent 执行侧才有；门面级 onOutcome 无 argv 载体，留空串
                                //    （与旧 audit.jsonl 里非 agent 行 command 缺省形态一致，属已知降级）。
                                command = "",
                                status = SinkRecord.statusLiteral(outcome.status),
                                errorCode = outcome.errorCode,
                                exitCode = outcome.exitCode,
                                elapsedMillis = outcome.elapsedMillis,
                                backend = outcome.backend,
                                kind = outcome.kind.name,
                                // 产物回传成功清单：空清单落 null（不产空键，与 backend/kind 后加字段惯例一致）
                                attachments = outcome.attachments.takeIf { it.isNotEmpty() },
                                // 被拒清单：空清单落 null（不产空键，与 attachments 惯例一致）
                                rejectedArtifacts = outcome.rejectedArtifacts.takeIf { it.isNotEmpty() },
                                // 发送者用户名：只读 ctx.senderName（Admission→Execute
                                // 边界已解析填充；拒绝路径 peek 降级可能空串）——零新增挂起调用，O(1) 纪律不破；
                                // 空串落 null 不产空键（attachments 同款后加字段惯例）。
                                userName = ctx.senderName.takeIf { it.isNotBlank() },
                                // 用户原消息 id：最近任务表情回显的查询锚点
                                // （listReactions 按它查）。ctx.message.messageId 非空 String 但可能空串
                                // （InboundMessage 归一路径），空串落 null 不产空键（userName 同款惯例）。
                                userMessageId = ctx.message.messageId.takeIf { it.isNotBlank() },
                            ),
                        )
                    }
                    observeScope.launch {
                        runCatching { senderStats.onMessage(ctx.message.senderId, ctx.chatId, ctx.text) }
                            .onFailure { log.warn("发送者统计失败（已忽略）：${it.message}") }
                    }
                },
                // ⑮ senderName 解析接线：执行路径与拒绝回复路径走 nameOf（挂起，调网关 API）；
                //    静默拒绝路径走 peek（非挂起、只读缓存，未命中则 senderName 为空串——已知差异）。
                resolveSenderName = { ctx ->
                    ctx.senderName = userNameResolver.nameOf(ctx.message.senderId).orEmpty()
                },
                peekSenderName = { ctx ->
                    ctx.senderName = userNameResolver.peek(ctx.message.senderId).orEmpty()
                },
                // 未命中兜底调度 agent seam：注入判定闭包则「全部 route 未命中」的消息改走
                // DispatchAgentHandler（HEAVY 闸）；null / 返回 null ⇒ runUnhandled 原语义。
                // App 形态「未命中 TEXT 恒进调度 agent」是恒定语义（App 侧恒注入 provider）：
                //    判定只剩 TEXT 形态与非空白（空白不值得占 HEAVY 闸，仍走 runUnhandled 静默计数）。
                //    agent 恒为硬依赖，可用性闸统一收口在 DispatchAgentHandler 内（现读
                //    isAvailable，不可用产出 AGENT_CONFIG_INVALID 显式报错卡，不再静默回落）。
                //    CLI 形态不注入 fallbackHandlerProvider ⇒ agent-first 天然不可达。
                // 🔴 引用流接线：DispatchAgentHandler 注入 referencedFileResolver——
                //    有引用能力的 AgentHandler/CommandHandler 被短路，「引用文件+命令」
                //    只能从本兜底入口进，必须同样接上懒下载/预处理/路径拼 prompt 的完整引用链。
                fallbackHandlerProvider = fallbackHandlerProvider?.let { provider ->
                    { ctx ->
                        if (ctx.inboundKind == InboundKind.TEXT &&
                            ctx.text.isNotBlank()
                        ) {
                            provider(ctx) ?: DispatchAgentHandler(deps, referencedFileResolver)
                        } else if (ctx.inboundKind == InboundKind.AUDIO && audioTranscriber != null) {
                            // 语音消息同 seam 承接：下载走本周期网关（downloader 闭包），
                            //    转码+转写经宿主注入缝，转写文本走 TEXT 同款 agent 管线（AudioHandler 内）；
                            //    seam 为 null（CLI/测试形态）⇒ 本分支不命中 ⇒ AUDIO 回落 runUnhandled 降级文案。
                            AudioHandler(deps, audioTranscriber, downloader, referencedFileResolver)
                        } else {
                            null
                        }
                    }
                },
                // 回执别名 seam：FILE 消息的 ack 回执发出后，把回执 messageId
                //    登记为原文件消息的别名——用户「引用回执」与「引用原文件消息」两条路都命中文件
                //    （回执文案歧义的机制消解，非仅文案修正）。判定面：
                //    - 仅 FILE 入站回合（其他 handler 的出站回执不登记——别名指向的 record 必须是文件）；
                //    - 仅发送成功且有 messageId（失败/静默无回执，自然回落现状）；
                //    - lookup 原文件消息登记表拿 record（FileHandler 已登记——deliver 晚于 handle，同回合恒命中；
                //      TTL 传 0 = 永不过期取回原 record，别名的 TTL 由 alias 内重新盖章）。
                onDelivered = { ctx, sendResult ->
                    val ackMessageId = sendResult.messageId
                    if (ctx.inboundKind == InboundKind.FILE &&
                        sendResult.ok &&
                        ackMessageId != null
                    ) {
                        val record = fileRegistry.lookup(ctx.message.messageId, ctx.chatId, ttlMillis = 0)
                        if (record != null) {
                            fileRegistry.alias(ackMessageId, record)
                        }
                    }
                },
            )

            // ---- handler 单表（HandlerKinds.build 四槽；同槽多能力用 CompositeHandler 合成）----
            // 引用流接线件（preprocessPipeline/downloader/quotedFetcher/referencedFileResolver）已上移至
            // Admission 之前（fallback 判定闭包要构造 DispatchAgentHandler 注入 resolver）。
            val kinds = HandlerKinds.build(
                // SYSTEM 槽 = SystemHandler(LIGHT，ping / `/commands` / `/whoami`) + StopHandler(URGENT，停止指令)
                system = { _, d ->
                    CompositeHandler(RouteKind.SYSTEM, listOf(SystemHandler(d), StopHandler(confirmService, d)))
                },
                // COMMAND 槽 = InteractionHandler(LIGHT，裁决) + AskAnswerHandler(LIGHT，ask_user 回答) + CommandHandler
                command = { route, d ->
                    CompositeHandler(
                        RouteKind.COMMAND,
                        listOf(
                            InteractionHandler(confirmService, d),
                            AskAnswerHandler(askUserBus, d),
                            CommandHandler(route as CommandRoute, d, referencedFileResolver),
                        ),
                    )
                },
                // AGENT 槽 = AgentHandler(HEAVY + 引用流)
                agent = { route, d -> AgentHandler(route as AgentRoute, d, referencedFileResolver) },
                // FILE 槽 = FileHandler(LIGHT：仅登记 + 回执，不下载不跑 agent)
                file = { route, d -> FileHandler(route as FileRoute, d, fileRegistry, counters) },
            )
            val snapshotFactory = PipelineSnapshotFactory(kinds, deps, log)
            val router = PipelineRouter(snapshotFactory.build(cfg), log)

            // ---- 入站队列（丢弃 ⇒ 计数 + WARN）----
            val queue = InboundQueue(InboundQueue.CAPACITY, log, InboundQueue.WHERE) { where, total ->
                counters.recordIntakeQueueDropped()
                log.warn("入站队列溢出丢弃 where=$where droppedTotal=$total")
            }

            // ---- 每消息 ctx 工厂（replyOverride 包一层 TrackingReplySink 以登记开放卡片流，供 stop 第 ④ 步收尾）----
            val contexts = RequestContextFactory(
                configProvider = configProvider,
                gateway = gateway,
                rateGovernor = rateGovernor,
                dispatchers = dispatchers,
                agent = agentRunner,
                processes = processRegistry,
                log = log,
                onRateLimited = { chatId, resetSec -> rateGovernor.notifyRateLimited(chatId, resetSec) },
                // reply 文本出站面敏感过滤的机密根注入（与文件通道 homeDataRoot 同源）。
                homeDataRoot = deps.dataRootProvider(),
                replyOverride = { message ->
                    TrackingReplySink(
                        GatewayReplySink(
                            gateway = gateway,
                            target = LarkTarget.ofInbound(message),
                            rateGovernor = rateGovernor,
                            netDispatcher = dispatchers.net,
                            log = log,
                            onRateLimited = { chatId, resetSec -> rateGovernor.notifyRateLimited(chatId, resetSec) },
                            // 引用锚定：🔴 必须调用**同一**判定函数（RequestContextFactory.replyAnchorOf）——
                            // 内联副本双写会让测试背书的判定与生产运行的判定静默分叉。
                            replyToMessageId = RequestContextFactory.replyAnchorOf(message),
                            // reply 文本出站面敏感过滤的机密根注入（与文件通道 homeDataRoot 同源）。
                            homeDataRoot = deps.dataRootProvider(),
                        ),
                        openStreams,
                        // 回复账本扩容：四出口（text/markdown/card/file）
                        // 成功落账。目的：① recall_own_message 工具的守护数据前提（账本里没有就不许撤）；
                        // ② update_own_card 的 messageId 发现缺口（流式卡经 StreamTerminal 信号入账，
                        //    非流式回复 LLM 拿不到自己发过的 messageId）。chatId = 账本「本会话校验」维度。
                        replyLedger = cardReplyLedger,
                        chatId = message.chatId,
                        log = log,
                    )
                },
                // 进度卡接线（重写收尾修复）：每消息构造 ProgressAccumulator（装饰 1 槽 conflate）。
                //    HEAVY agent 任务在 runAgentPipeline 里把它的 snapshots 接给卡片泵（pumpSnapshotsToCard
                //    → reply.stream 的 CardStreamSink）；LIGHT 任务零 agent 输出 ⇒ 槽恒空、泵不启动、零开销。
                //    配置逐项读 agent.progress（maxEntries/entryMaxChars/cardMaxChars，HOT 语义下每消息现读，禁装配期缓存）。
                progressOverride = {
                    ProgressAccumulator(
                        maxEntries = configProvider().agent.progress.maxEntries,
                        entryMaxChars = configProvider().agent.progress.entryMaxChars,
                        cardMaxChars = configProvider().agent.progress.cardMaxChars,
                        clock = clock,
                    )
                },
            )

            val intake = EventIntake(
                queue, staleFilter, deduper, accessControl, policyGate, router, admission, contexts, counters, log,
            )

            // ---- ⑤ Observe：两条落盘协程（消费 SinkChannels，空闲零唤醒；cancel 时 finally 在 NonCancellable 内 flush+close）----
            val auditDir = paths.auditFile.parent?.toString().orEmpty()
            val statsDir = paths.senderStatsFile.parent?.toString().orEmpty()
            val auditSink = JsonlSink.audit(sinkChannels, auditDir, cfg.observability.auditRotationMb, cfg.observability.auditKeepFiles, log)
            val statsSink = JsonlSink.stats(sinkChannels, statsDir, cfg.observability.auditRotationMb, cfg.observability.auditKeepFiles, log)
            val auditSinkJob = runtime.auditSinkScope.launch { auditSink.run() }
            val statsSinkJob = runtime.statsSinkScope.launch { statsSink.run() }

            // ---- 1Hz 指标采样（UI 状态页 / CLI status 的唯一数据源）----
            val metrics = MetricsSampler(
                counters = counters,
                sinkChannels = sinkChannels,
                logBus = logBus,
                registry = processRegistry,
                auditSink = auditSink,
                statsSink = statsSink,
                connectionProvider = connectionProvider,
                confirmProvider = confirmProvider,
                runningProvider = runningProvider,
                intervalMillis = METRICS_INTERVAL_MILLIS,
                log = log,
                clock = clock,
            )

            // ---- 确认超时/催办接线（本周期，整束写槽） ----
            // 稳定槽写入：超时/催办点 ConfirmService 经构造回调读槽现调 ⇒ 本束闭包跑在 awaitDecision
            // 协程内，捕获的是**本周期** gateway/rateGovernor/netDispatcher（stop 时 Bridge.teardownBundle
            // 清回默认束，旧周期闭包绝不触达已关网关）。
            // 不预造单 chatId 的 sink：chatId 每请求不同（ReplySink 是每消息绑定一个 target 的形态，
            //    超时/催办回调没有消息回合上下文）——闭包内按 snapshot.request.chatId **现造** GatewayReplySink
            //    （:594 replyOverride 同款参数；replyToMessageId=null——超时卡/催办不是对某条提问的回复，不锚定）。
            //    超时是低频路径，每次一个 sink 实例构造成本可忽略。
            // cardMessageId null（未发卡/发送失败/SyntheticGateway 默认）⇒ 跳过更新，行为同旧版；
            //    更新失败只 log 不阻断（已答态是增强面，降级即回到现状「超时静默」——BuiltinHandlers 已答态
            //    降级语义同款；ConfirmService.awaitDecision 内还有 runCatching 双保险，TIMEOUT 返回恒不受影响）。
            // markdown 骨架对齐 BuiltinHandlers 已答态先例（**结果** + 工具行 + 参数行 + 单据末行）；
            //    内联组装而非共享纯函数：超时态文案（已超时拒绝 ⏱）与裁决路径（已拒绝 ✗）语义不同段，
            //    抽共享函数会把两处文案耦成参数化模板——可读性损大于双写风险（两处均各有测试锚定，
            //    改动会在各自测试红）。短标识走 [ConfirmService.shortId] 单源（防手抄双写漂移）。
            val timedOutCardSink: suspend (ConfirmService.TimedOutConfirm) -> Unit = { snapshot ->
                val cardMessageId = snapshot.cardMessageId
                if (cardMessageId != null) {
                    runCatching {
                        val sink = GatewayReplySink(
                            gateway = gateway,
                            target = LarkTarget.Chat(snapshot.request.chatId),
                            rateGovernor = rateGovernor,
                            netDispatcher = dispatchers.net,
                            log = log,
                            onRateLimited = { chatId, resetSec ->
                                rateGovernor.notifyRateLimited(chatId, resetSec)
                            },
                            // reply 文本出站面敏感过滤的机密根注入（与文件通道 homeDataRoot 同源）。
                            homeDataRoot = deps.dataRootProvider(),
                        )
                        sink.updateExistingCard(
                            messageId = cardMessageId,
                            spec = CardJson.timedOutConfirm(
                                toolName = snapshot.request.toolName,
                                markdown = buildString {
                                    append("**已超时拒绝** ⏱")
                                    append("\n**工具**：`").append(snapshot.request.toolName).append("`")
                                    if (snapshot.request.inputSummary.isNotBlank()) {
                                        append("\n**参数**：").append(snapshot.request.inputSummary)
                                    }
                                    // 单据：cf-<shortId>（事后对账短标识）——BuiltinHandlers 已答态末行同构；
                                    // shortId 不带 cf- 前缀故手写（对账用非唯一键，不发明新格式）。
                                    append("\n单据：cf-")
                                        .append(ConfirmService.shortId(snapshot.request.requestId))
                                        .append("（事后对账短标识）")
                                },
                            ),
                        )
                    }.onFailure {
                        log.warn("确认卡已超时态更新失败 id=$cardMessageId（已降级为超时静默）：${it.message}")
                    }
                }
            }
            // 已读查询：装配闭包（createBundle 内）早于 Bridge 实例构造，够不到 Bridge.messageReadCount
            //    门面——闭包内直接经本周期 gateway.readReceipts **首页判零**（翻页聚合是 UI 已读数展示需求，
            //    催办判零看首页即足）；页尺寸复用 [Bridge.READ_RECEIPT_PAGE_SIZE] 单源；null（能力缺席/
            //    查询失败）由 service 层折叠为 fail-safe 静默。
            val readCountOf: suspend (String) -> Int? = { messageId ->
                gateway.readReceipts(messageId = messageId, pageSize = Bridge.READ_RECEIPT_PAGE_SIZE, pageToken = null)
                    ?.readOpenIds?.size
            }
            // 文字催办：复用同一现造 sink 出 text（频控/退避全合规）；失败只 log（催办是增强面，
            // 顺延已先行生效——service 层记好了账，此处失败不回滚：宁可白延半窗也不刷屏重试）。
            // 催办文案对齐卡面 proposal 直译；「还剩 N 秒」用载荷捎带的顺延段毫秒（service 层按窗口比例
            // 算好——闭包拿不到 timeoutMillis，秒数单源在 service 侧常量）；短码走 shortId 单源。
            val reminderSink: suspend (ConfirmService.ReminderNotice) -> Unit = { notice ->
                runCatching {
                    val sink = GatewayReplySink(
                        gateway = gateway,
                        target = LarkTarget.Chat(notice.request.chatId),
                        rateGovernor = rateGovernor,
                        netDispatcher = dispatchers.net,
                        log = log,
                        onRateLimited = { chatId, resetSec ->
                            rateGovernor.notifyRateLimited(chatId, resetSec)
                        },
                        // reply 文本出站面敏感过滤的机密根注入（与文件通道 homeDataRoot 同源）。
                        homeDataRoot = deps.dataRootProvider(),
                    )
                    val token = ConfirmService.shortId(notice.request.requestId)
                    val remainingSec = (notice.remainingMillis / 1000).coerceAtLeast(1)
                    sink.text(
                        "⏱ 确认 $token 即将超时（还剩约 $remainingSec 秒），回复 确认/拒绝 $token 可在最后窗口裁决",
                    )
                }.onFailure {
                    log.warn("确认催办发送失败 id=${notice.request.requestId}（已降级为不催）：${it.message}")
                }
            }
            confirmTimedOutSlot.set(
                ConfirmService.TimedOutWiring(
                    onTimedOut = timedOutCardSink,
                    readCount = readCountOf,
                    onReminder = reminderSink,
                ),
            )

            return RuntimeBundle(
                gateway = gateway,
                runtime = runtime,
                queue = queue,
                processes = processRegistry,
                permits = permits,
                metrics = metrics,
                downloadStore = downloadStore,
                cardReplyLedger = cardReplyLedger,
                admission = admission,
                intake = intake,
                router = router,
                snapshotFactory = snapshotFactory,
                rateGovernor = rateGovernor,
                openStreams = openStreams,
                auditSinkJob = auditSinkJob,
                statsSinkJob = statsSinkJob,
                log = log,
                connectionUpdater = connectionUpdater,
                onMetricsTick = onMetricsTick,
            )
        }
    }
}

/**
 * [ReplySink] 的装饰器：把 [GatewayReplySink.stream] 开出的卡片流登记进 [openStreams]，
 * 供 [RuntimeBundle.closeOpenStreams]（stop 第 ④ 步）遍历收尾。其余出口全部委托。
 * 非静态、每消息一个实例（由 `RequestContextFactory.replyOverride` 造），无跨消息可变状态。
 *
 * ## 回复账本扩容
 * 账本不只收「流式卡终态」（StreamTerminal 信号）——text/markdown/card/file 四出口
 * 也全部落账，支撑：
 * 1. `recall_own_message` 工具的守护数据（撤回须先证明「这条消息是机器人自己发的」）；
 * 2. `update_own_card` 的 messageId 发现（非流式回复 LLM 也能拿到自己发过的 messageId）。
 * 本装饰器 override 四出口：`ok && messageId != null` 即记（同 messageId 覆写 = 幂等）。
 * 分片取舍：`SendResult.chunkMessageIds`（文本超 3500 字分片）**只记主 messageId**，分片 id
 *    逐个入账的收益（每片都可引用/撤回）不抵复杂度——用户引用任一分片时，飞书引用的是那条
 *    分片消息本身、账本 miss 属既有降级路径（quotedFetcher 现查兜底），不为它扩账本结构。
 * 🔴 落账失败**只 log 不抛**——账本是增强面（守护 + 引用兜底），绝不阻塞出站结果返回。
 *
 * @param replyLedger 机器人回复账本（null = 不落账——测试窄面/Harness 形态零改动）
 * @param chatId 本消息所属会话（账本「本会话校验」维度；null 记 ""——与 [CardReplyLedger.record] 口径一致）
 */
internal class TrackingReplySink(
    private val delegate: GatewayReplySink,
    private val openStreams: CopyOnWriteArrayList<CardStreamSink>,
    private val replyLedger: CardReplyLedger? = null,
    /** 本消息所属会话（🔴 override [ReplySink.chatId]——delegate 已暴露同名属性，须覆写回同值）。 */
    override val chatId: String? = null,
    private val log: BridgeLog = BridgeLog.Silent,
) : ReplySink by delegate {

    override suspend fun text(body: String): SendResult =
        delegate.text(body).also { recordReply(it, body.take(LEDGER_BODY_MAX_CHARS)) }

    override suspend fun markdown(body: String): SendResult =
        delegate.markdown(body).also { recordReply(it, body.take(LEDGER_BODY_MAX_CHARS)) }

    override suspend fun card(spec: CardSpec): SendResult = delegate.card(spec).also {
        recordReply(it, ("[卡片] " + spec.title + "\n" + spec.markdown).take(LEDGER_BODY_MAX_CHARS))
    }

    override suspend fun file(file: Path, displayName: String?): SendResult = delegate.file(file, displayName).also {
        // 正文供 fetch_quoted_context 兜底引用说明；recall 守护只要「在账本里」的存在性
        val name = displayName ?: file.fileName?.toString().orEmpty()
        recordReply(it, ("[文件消息] " + name).take(LEDGER_BODY_MAX_CHARS))
    }

    override suspend fun image(source: Path): SendResult = delegate.image(source).also {
        val name = source.fileName?.toString().orEmpty()
        recordReply(it, ("[图片消息] " + name).take(LEDGER_BODY_MAX_CHARS))
    }

    override suspend fun replyInThread(body: String): SendResult =
        delegate.replyInThread(body).also { recordReply(it, body.take(LEDGER_BODY_MAX_CHARS)) }

    /** 出站成功且拿到 messageId 时落账（runCatching 包裹：失败只 log，不阻塞结果返回）。 */
    private fun recordReply(result: SendResult, body: String) {
        val messageId = result.messageId
        // 局部变量捕获：runCatching lambda 跨捕获可变属性无法智能转换（private val 构造属性在
        // open/委托场景下编译器不给稳定保证）。
        val ledger = replyLedger ?: return
        if (!result.ok || messageId == null) return
        runCatching { ledger.record(messageId, chatId, body) }
            .onFailure { log.warn("机器人回复落账失败 messageId=$messageId：${it.message}") }
    }

    private companion object {
        /** 落账正文截断上限（超长卡/长文本占账本容量无意义——fetch 兜底只要摘要可读）。 */
        const val LEDGER_BODY_MAX_CHARS: Int = 2_000
    }

    override fun stream(initial: CardSpec): CardStreamSink =
        delegate.stream(initial).also {
            openStreams.add(it)
            // 🔴 openStreams 摘除：只增不减会让长跑期间「消息数 × sink」恒定增长（每个 sink 持整段
            //    卡片快照 + Job），且 closeOpenStreams() 逐流收尾的开销随存活数线性上涨。摘除走
            //    onFinished 回调面：装饰器拿不到具体实现类的 job，摘除时机延迟到流终结（close /
            //    建卡失败 / 主循环退出），closeOpenStreams() 自身仍先 clear() 再遍历，摘除不与之竞争。
            it.onFinished { openStreams.remove(it) }
        }
}

/**
 * [ConfirmNotifier] 的网关实现：把一条确认请求渲染成**非流式确认卡**发回请求所属会话。
 * 卡片按钮 value 形如 `<token>:allow` / `<token>:deny`（见 [CardJson.confirm]），token 取 requestId 短标识；
 *    裁决回传由 [InteractionHandler] 承接。🔴 恒不抛（发送失败只降级记 WARN，绝不阻断 agent 挂起等待）。
 * 发送成功即回填 messageId 进 [ConfirmService.recordCardMessageId]——
 *    裁决时 `DecideResult.Ok.cardMessageId` 快照捎带给 InteractionHandler 做已答态整卡更新。
 *
 * ## 确认卡 markdown 骨架四件
 * ① 风险徽标行（`request.riskLevel` 非空白才渲染——LOW/FORBIDDEN 不发确认卡，理论不至）；
 * ② 工具名行；③ 参数摘要行（INPUT_SUMMARY_CHARS=120 现状）；④ 信任行「仅发起本次操作的用户可裁决」
 * + 短码兜底行「无法点按钮？回复 确认 a3f2 或 拒绝 a3f2」（DECISION_REGEX 语义的文字裁决通道
 * 显式写进卡——card.action.trigger 未配置场景仍可裁决）。
 * 🔴 短码单源：token 与兜底行短码统一走 [ConfirmService.shortId]（手抄 substringAfterLast('-')
 * 会与 shortId 漂移）。
 */
internal class GatewayConfirmNotifier(
    private val gateway: MessageGateway,
    private val log: BridgeLog,
    /** 确认卡 messageId 回填口（装配处传 confirmService；null = 不回填，行为同旧版——测试窄面）。 */
    private val cardMessageIdSink: ((requestId: String, messageId: String) -> Unit)? = null,
) : ConfirmNotifier {

    override suspend fun notify(request: ConfirmService.ConfirmRequest) {
        runCatching {
            // token 走 [ConfirmService.shortId] 单源
            //    （后者 = removePrefix("cf-").split('-').last().take(4)，对 `cf-<seq>-<hex4>` 形态两者同值）。
            val token = ConfirmService.shortId(request.requestId)
            val markdown = buildString {
                // 风险徽标行：MEDIUM/HIGH pill（RiskLevel 非空才渲染；LOW/FORBIDDEN 不发确认卡，理论不至）
                val level = request.riskLevel
                if (level.isNotBlank()) {
                    append("**风险**：").append(level).append("（高风险操作，请仔细核对参数）")
                    append("\n")
                }
                append("**工具**：`").append(request.toolName).append("`")
                if (request.inputSummary.isNotBlank()) {
                    append("\n**参数**：").append(request.inputSummary)
                }
                append("\n\n仅发起本次操作的用户可裁决（他人回复无效）")
                append("\n无法点按钮？回复 **确认 ").append(token)
                    .append("** 或 **拒绝 ").append(token).append("**")
            }
            val card = CardJson.confirm(title = "🔐 需要你的确认", markdown = markdown, token = token)
            val result = gateway.send(LarkTarget.Chat(request.chatId), Outbound.card(card))
            // 发送成功 ⇒ 回填 messageId（裁决时已答态更新据此定位原卡；失败 ⇒ 旧文案行为照旧）
            val sentMessageId = result.messageId
            if (result.ok && sentMessageId != null) {
                cardMessageIdSink?.invoke(request.requestId, sentMessageId)
            }
        }.onFailure { log.warn("确认卡发送失败 id=${request.requestId}: ${it.message}") }
    }
}

/**
 * 🔴 **App 与 CLI 唯一共用的装配点**（方案 line 275）。
 *
 * ## 为什么它能保证「结构上不可能不一致」
 * 五阶段管线的全部接线只此一处；App（Compose）与 CLI（serve 子命令）都调 [assemble] 得到同一个 [Bridge]，
 * 不存在「App 一套接线、CLI 另一套」的可能——差异被收敛成 [assemble] 的**入参**（数据根、网关工厂、父 Job、
 * 是否输出控制台日志），而非两份各写一遍的装配代码。
 *
 * @param dataRoot 数据根（`~/.toolbelt/lark`）——🔴 由**宿主注入**（lib 是 jvmTarget 11，不依赖 :core FileUtils）。
 * @param gatewayFactory 网关工厂（App/CLI 注入 `::SdkLarkGateway`；测试注入 `SyntheticGateway`）——SDK 类型不越过 :lark-gateway。
 * @param defaultConfig 配置加载失败时的兜底（宿主传入 `DefaultBridgeConfig` 的产物）。
 * @param parentJob 宿主父 Job（App 传生命周期 Job；CLI serve 传 null ⇒ 独立根）。
 * @param consoleMinLevel `consoleLog=true` 时控制台委托（[PrintStreamBridgeLog]）的输出下限。
 *   缺省 [PrintStreamBridgeLog.Level.INFO]（不加 `--verbose` 行为逐字不变）；CLI `--verbose` 传 DEBUG，
 *   使既有拒绝路径 `log.debug` 浮现。`consoleLog=false`（App 侧）时本参数无效（无控制台委托）。
 * @param agentRunnerOverride agent 执行器注入缝——null = [NoAgentRunner]（CLI serve 形态无应答引擎，
 *   agent 类消息显式失败）；非空则整条 agent 执行链（AgentRoute 触发 + CommandHandler 委托）改用它。
 *   App 侧恒注入 Koog 调度 agent（进程内直跑，Koog 字节码 Java 17
 *   只能进宿主侧 jvmTarget 25 模块——故 lib 只持 [AgentRunner] 接口）。
 * @param fallbackHandlerProvider 未命中兜底调度 agent seam——非空时「全部 route 未命中」的
 *   非空白文本消息改走调度 agent（provider 返回 null 时由装配点补 [com.jiaoay.lark.bridge.pipeline.DispatchAgentHandler]，
 *   HEAVY 闸）；null = 不注入兜底（CLI 形态，未命中走 runUnhandled 静默留痕）。
 *   agent 恒为硬依赖：可用性闸收口在 DispatchAgentHandler 内（不可用产出显式报错卡，不静默回落）。
 */
object BridgeAssembler {

    fun assemble(
        dataRoot: Path,
        gatewayFactory: () -> MessageGateway,
        defaultConfig: BridgeConfig,
        parentJob: Job? = null,
        logBus: LogBus = RingLogBus(),
        consoleLog: Boolean = true,
        consoleMinLevel: PrintStreamBridgeLog.Level = PrintStreamBridgeLog.Level.INFO,
        clock: () -> Long = { System.currentTimeMillis() },
        logDelegate: BridgeLog = BridgeLog.Silent,
        agentRunnerOverride: AgentRunner? = null,
        fallbackHandlerProvider: ((RequestContext) -> MessageHandler?)? = null,
        /**
         * 宿主机密根（敏感文件排除判据）——`~/.easy_dev_toolbox` 整棵子树。
         *
         * 🔴 为什么不是 [dataRoot]（`~/.toolbelt/lark`）：两者是**兄弟目录**，
         *    而 `.datastore/` 下存着**明文 LLM API Key** 与 xlog 私钥——只防 `lark/` 会留同级缺口。
         * 缺省 null 时**回落 [dataRoot]**（保守：至少覆盖桥接自身配置与审计）——
         *    CLI/测试形态不传即为该语义，行为不变；App 侧显式传宿主应用数据根。
         */
        sensitiveRoot: Path? = null,
        /**
         * 宿主 xlog 私钥回落源（App 形态注入，null = CLI/测试形态）。
         * 同步闭包、非挂起、非抛；仅在 `fileMessage.xlogPrivateKey` 为空时被读取，
         * 用于 configProvider **读取层**富化（Bridge.enrichXlogKey）——绝不写回文档。
         */
        xlogKeyFallback: (() -> String)? = null,
        /**
         * 宿主语音转写缝（App 形态注入；null = CLI/测试形态，AUDIO 消息维持既有降级文案）。
         * 契约见 [AudioTranscriberSeam] KDoc：挂起、失败映射为三态结果、CancellationException 原样上抛。
         * 收参必传纪律：必须透传给 Bridge 构造（透传守护锚 BridgeAssemblerParityTest ⑩ 组）。
         */
        audioTranscriber: AudioTranscriberSeam? = null,
        /**
         * 启动自检宿主缝（App 形态注入；null = CLI/测试形态，恒不探查）。
         * 收参必传纪律：漏传则 App 侧启动自检推送整条链路死线且编译期零报警
         * （透传守护锚 BridgeAssemblerParityTest ⑪ 组）。
         */
        startupProbe: StartupProbeSeam? = null,
    ): Bridge {
        val paths = ConfigPaths(dataRoot)
        val supervisor = if (parentJob != null) {
            BridgeSupervisor.withParent(parentJob, Dispatchers.Default)
        } else {
            BridgeSupervisor.create(Dispatchers.Default)
        }

        // 配置真源前置：logConsoleEnabled provider 需要读 configRef，故 configRef 必须在 log 之前创建。
        //    初值 = defaultConfig（配置加载完成后 .set(cfg) 更新为真实配置）；provider 自动 HOT-reactive
        //    （Bridge.configRef.set(next) 是 HOT 更新点，provider 每次调用读最新值）。
        val configRef = AtomicReference(defaultConfig)
        val logConsoleEnabledProvider: () -> Boolean = { configRef.get().observability.logConsole.enabled }
        val log: BridgeLog = if (consoleLog) {
            // consoleMinLevel 由 CLI 的 --verbose 显式传入（DEBUG），缺省 INFO ⇒ 不加 --verbose 行为不变。
            ObservingBridgeLog.withConsole(
                logBus,
                consoleMinLevel = consoleMinLevel,
                logConsoleEnabled = logConsoleEnabledProvider,
            )
        } else {
            // 🔴 logDelegate：App 侧（consoleLog=false）经此缝注入 JLog 适配器实现双写——桥接运行日志
            //    （重连/队列丢弃/回复失败）不落 app.log 就会重启即丢（lib 不得 import JLog——jvmTarget 隔离）。
            //    CLI/测试缺省 Silent，行为逐字不变。delegate 侧输出不受 logConsoleEnabled 门控
            //    （ObservingBridgeLog.emit 的既有分工：门控只管 LogBus 喂入）。
            ObservingBridgeLog(logBus, delegate = logDelegate, logConsoleEnabled = logConsoleEnabledProvider)
        }

        val configRepository = ConfigRepository(paths, supervisor.scope, log = log)
        val cfg = runCatching {
            when (val loaded = configRepository.load()) {
                is ConfigLoadResult.Loaded -> loaded.config
                else -> defaultConfig
            }
        }.getOrDefault(defaultConfig)
        configRef.set(cfg)   // 加载完成后更新为真实配置（此后 provider 读到真实值）
        val dispatchers = BridgeDispatchers.build(cfg.runtime.maxConcurrentAgents)
        val counters = PipelineCounters()
        val processRegistry = ProcessRegistry(cfg.runtime.maxConcurrentAgents)
        // 🔴 确认超时/催办回调接线：超时点把原确认卡 patch 为
        //    「已超时拒绝」无按钮态；未读确认在窗口 3/4 处收一次文字催办且窗口顺延一半。
        //    回调需要**运行中周期**的 gateway/rateGovernor/netDispatcher（每周期件，assemble 期不存在）——
        //    故用稳定槽晚绑定：槽是稳定件（随 Bridge 生命周期），RuntimeBundle.create 在每周期件就绪后
        //    整束写入（Bridge.createBundle 传入），teardownBundle 清回默认束（旧周期不得
        //    再产生副作用）。槽未接线（未运行/测试直造）⇒ 默认束三闭包全空 = 行为同旧版。
        val confirmTimedOutSlot = AtomicReference<ConfirmService.TimedOutWiring>(ConfirmService.TimedOutWiring())
        val confirmService = ConfirmService(
            onTimedOut = { snapshot -> confirmTimedOutSlot.get().onTimedOut(snapshot) },
            readCountProvider = { messageId -> confirmTimedOutSlot.get().readCount(messageId) },
            onReminder = { snapshot -> confirmTimedOutSlot.get().onReminder(snapshot) },
        )
        // user.ask_user 挂起等待的配对总线（稳定件——跨 start/stop 与 ConfirmService 同生命周期）：
        // 每周期网关就绪后由 Bridge 侧缝使用（提问卡发出→register；引用回复→decide）。
        // 未运行/测试直造形态下 pending 恒空 = ask_user 链路缺席（fail-closed 语义由 feature 侧
        // gate 缺席注册门控承载，bus 空转零副作用）。
        val askUserBus = AskUserBus(log)
        val sinkChannels = SinkChannels(log = log)
        val singleInstanceLock = SingleInstanceLock(paths, log)

        // agent 执行器注入缝：override 非空 ⇒ 整条 agent 执行链改用它（App 侧恒注入
        // Koog 调度 agent）；null（CLI 形态）⇒ NoAgentRunner 占位——CLI 委托后端退役后
        // serve 形态无应答引擎，未命中 agent 消息经 NoAgentRunner 显式失败（fail-closed
        // 语义见 agent/NoAgentRunner.kt）。
        val agentRunner: AgentRunner = agentRunnerOverride ?: NoAgentRunner()

        // 引用流稳定件：Bridge 构造期创建一次，跨 start/stop 存活。
        val fileRegistry = FileRegistry(clock)
        val downloadStore = DownloadStore(configProvider = { configRef.get() }, log = log, clock = clock)
        // 机器人回复账本：流式卡终态落账——飞书无读卡片实体 API，正文只在自己手里。
        // 🔴 持久化：纯内存重启即丢 ⇒ 重启后引用机器人回复必 miss，落盘到 cardReplyLedgerFile。
        val cardReplyLedger = CardReplyLedger(persistenceFile = paths.cardReplyLedgerFile, log = log).apply { load() }

        return Bridge(
            paths = paths,
            configRepository = configRepository,
            logBus = logBus,
            log = log,
            gatewayFactory = gatewayFactory,
            dispatchers = dispatchers,
            supervisor = supervisor,
            counters = counters,
            processRegistry = processRegistry,
            confirmService = confirmService,
            // 🔴 确认超时回调稳定槽透传——createBundle 每周期经 RuntimeBundle.create
            //    接线本周期 gateway（收参必传纪律：漏传即回调死线，编译期零报警）。
            confirmTimedOutSlot = confirmTimedOutSlot,
            // ask_user 挂起配对总线透传（收参必传纪律——漏传即 ask_user 链路死线，编译期零报警）。
            _askUserBus = askUserBus,
            // 落盘通道透传（审计/发送者统计两条 JSONL 通道）。
            sinkChannels = sinkChannels,
            singleInstanceLock = singleInstanceLock,
            agentRunner = agentRunner,
            // 未命中兜底调度 agent seam 透传（🔴 收参必传纪律：漏传则
            //    Bridge 构造参数默认 null ⇒ createBundle→RuntimeBundle.create→Admission 拿到 null，
            //    App 侧整条「未命中兜底调度」链路从未接通，未命中 TEXT 会静默留痕而非进调度 agent。
            //    测试直注 seam（FallbackDispatchTest）测的是 RuntimeBundle 层，装配链的透传断裂编译期零报警）。
            fallbackHandlerProvider = fallbackHandlerProvider,
            // 宿主语音转写缝透传（收参必传纪律——与 fallbackHandlerProvider 漏传事故同型，
            // 漏传即 App 侧语音链路死线，透传守护锚 BridgeAssemblerParityTest ⑩ 组）。
            audioTranscriber = audioTranscriber,
            // 启动自检缝透传（收参必传纪律——透传守护锚 BridgeAssemblerParityTest ⑪ 组）。
            startupProbe = startupProbe,
            configRef = configRef,
            // 宿主 xlog 私钥回落缝透传（🔴 收参必传纪律——与 fallbackHandlerProvider
            // 漏传事故同型，漏传即整条链路死线）。
            xlogKeyFallback = xlogKeyFallback,
            fileRegistry = fileRegistry,
            downloadStore = downloadStore,
            cardReplyLedger = cardReplyLedger,
            clock = clock,
            // 宿主机密根透传（Path → File）。
            // 缺省 null ⇒ Bridge 内回落 paths.dataRoot（CLI/测试形态行为不变）。
            sensitiveRoot = sensitiveRoot?.toFile(),
        )
    }
}
