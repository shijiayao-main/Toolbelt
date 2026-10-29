// 桥接门面（唯一生命周期编排点）。
// 优雅关闭 stop(graceMillis=5000) 十步；幂等 start/stop（AtomicBoolean CAS）；
//    配置预检（缺 appId ⇒ Stopped(reason) 不抛异常）；确认链路 start 才启用 + 降级 fail-safe deny；
//    close() 释放锁与进程登记表；HOT/RESTART 分流（applyConfig 依 BridgeFields.applyMode 判定）。
// 纪律：门面内零 ProcessBuilder（agent 子进程经 ProgramLauncher），
//    零 synchronized/Mutex（intake 路径）、零 @Volatile var（一律 AtomicReference）、零 com.jiaoay.easy_dev_toolbox、
//    审计经 AuditJson（不在此写）、不吞 CancellationException。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.config.ApplyMode
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.BridgeFields
import com.jiaoay.lark.bridge.config.ConfigPaths
import com.jiaoay.lark.bridge.config.ConfigRepository
import com.jiaoay.lark.bridge.config.NativeToolGrant
import com.jiaoay.lark.bridge.confirm.AskUserBus
import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import com.jiaoay.lark.bridge.confirm.ConfirmService
import com.jiaoay.lark.bridge.confirm.PendingConfirmView
import com.jiaoay.lark.bridge.dispatch.BridgeDispatchers
import com.jiaoay.lark.bridge.dispatch.BridgeRuntime
import com.jiaoay.lark.bridge.dispatch.BridgeSupervisor
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.files.CardReplyLedger
import com.jiaoay.lark.bridge.files.DownloadStore
import com.jiaoay.lark.bridge.files.FileRegistry
import com.jiaoay.lark.bridge.handler.AudioTranscriberSeam
import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.observe.EnvironmentProbe
import com.jiaoay.lark.bridge.observe.JsonlSink
import com.jiaoay.lark.bridge.observe.MetricsSampler
import com.jiaoay.lark.bridge.observe.SinkChannels
import com.jiaoay.lark.bridge.observe.StartupProbeSeam
import com.jiaoay.lark.bridge.observe.toBridgeConnectionStatus
import com.jiaoay.lark.bridge.pipeline.CardJson
import com.jiaoay.lark.bridge.pipeline.GatewayReplySink
import com.jiaoay.lark.bridge.pipeline.MessageHandler
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.SingleInstanceLock
import com.jiaoay.lark.gateway.CardStreamSink
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.LarkCredential
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.Outbound
import com.jiaoay.lark.gateway.ReactionBrief
import com.jiaoay.lark.gateway.SendResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [Bridge.start] 的结果（🔴 绝不抛异常；一切失败都归一为 [Refused] + 可观测状态）。 */
sealed interface BridgeStartResult {
    /** 长连接已建立、intake/落盘/指标/确认链路已启动。 */
    data object Started : BridgeStartResult

    /** 已在运行（幂等：重复 start 是 no-op）。 */
    data object AlreadyRunning : BridgeStartResult

    /**
     * 拒绝启动并已回到 Stopped。
     * @param code 归一错误码（缺凭据 = [BridgeErrorCode.ConfigInvalid]，被同实例占用 = [BridgeErrorCode.BlockedByPeerInstance]，
     *        连接失败 = [BridgeErrorCode.ConnectFailed]）；null 表示纯配置态（如 dryRun 无凭据）。
     */
    data class Refused(val code: BridgeErrorCode?, val reason: String) : BridgeStartResult
}

/**
 * [Bridge.sendFile] 的结果（桥接未运行时方法本身返回 null，与 [Bridge.sendTest] 同契约）。
 * sealed 分流供宿主逐态渲染人话原因；拒绝态（[InvalidInput]/[Denied]）不消耗上传配额。
 */
sealed interface FileSendOutcome {
    /** 已送出（messageId = 飞书消息 id）。 */
    data class Sent(val messageId: String?) : FileSendOutcome

    /** 输入不合法：chat_id 为空或文件不存在。 */
    data object InvalidInput : FileSendOutcome

    /** 命中出站排除表（与 `send_file` 工具同一张表——出站通道不设旁路）。 */
    data object Denied : FileSendOutcome

    /** 传输层失败（上传或发送；error/larkMsg 供归一人话，详情见日志）。 */
    data class Failed(
        val error: com.jiaoay.lark.gateway.GatewayErrorCode?,
        val larkMsg: String?,
    ) : FileSendOutcome
}

/**
 * [Bridge.registerApp] 的结果。
 * 与 [BridgeStartResult] 同纪律：绝不抛业务异常，一切失败归一为 sealed 结果供 UI 分发。
 */
sealed interface RegisterAppResult {
    /** 扫码完成，拿到新凭据（appId = client_id、appSecret = client_secret）。 */
    data class Success(val credential: com.jiaoay.lark.gateway.LarkCredential) : RegisterAppResult

    /** 二维码已过期（SDK ExpiredException）——用户重新发起即可。 */
    data class QrExpired(val message: String) : RegisterAppResult

    /** SDK 参数不合法（RegisterAppException ERROR_INVALID_ARGUMENT）。 */
    data class InvalidArgument(val message: String) : RegisterAppResult

    /** 其它失败（网络/构造网关失败等）。 */
    data class Failed(val message: String) : RegisterAppResult
}

/** [Bridge.applyConfig] 的分流结果（CDS §4.4）。 */
sealed interface ConfigApplyResult {
    /** 变更字段全为 HOT：已无锁热替换快照 + 下推策略，不断连、在途任务不受影响。 */
    data object HotApplied : ConfigApplyResult

    /** 变更含 RESTART 字段：调用方须 [Bridge.stop] 后再 [Bridge.start]（门面不自动重启，交宿主决策）。 */
    data class RestartRequired(val restartFields: List<String>) : ConfigApplyResult

    /** 无实质变更。 */
    data object Unchanged : ConfigApplyResult
}

/**
 * 桥接门面：一次 start/stop 周期的唯一编排点（App 与 CLI 共用，由 [BridgeAssembler.assemble] 构造）。
 *
 * ## 生命周期
 * - [start]：配置预检 → 单实例锁 → 建长连接 → 启动 intake/落盘/指标 → 确认链路。
 *   任何一步失败都归一为 [BridgeStartResult.Refused] 并把状态落回 Stopped/Blocked，**绝不抛**。
 * - [stop]：十步优雅关闭（每步都有超时上限，`graceMillis` 是在途任务的排空预算）。
 * - [close]：[stop] + 释放单实例锁 + 取消 supervisor 全树（进程退出 / 宿主销毁时调用）。
 *
 * ## 状态可观测
 * [status] 是 1Hz 采样的 [BridgeStatusSnapshot]（UI 状态页 / CLI status 的唯一数据源）；
 * [logBus] 是环形日志台；[confirmStatus] 是确认链路可用性（含 Probing 态）。
 */
class Bridge internal constructor(
    private val paths: ConfigPaths,
    private val configRepository: ConfigRepository,
    private val logBus: com.jiaoay.lark.bridge.observe.LogBus,
    private val log: BridgeLog,
    private val gatewayFactory: () -> MessageGateway,
    private val dispatchers: BridgeDispatchers,
    private val supervisor: BridgeSupervisor,
    private val counters: PipelineCounters,
    private val processRegistry: ProcessRegistry,
    private val confirmService: ConfirmService,
    /**
     * 确认超时/催办接线**稳定槽**：ConfirmService 的
     * [ConfirmService.awaitDecision] 超时/催办点经构造回调读槽现调——回调需要**运行中周期**的
     * gateway/rateGovernor/netDispatcher（每周期件，本对象构造期不存在），故 [createBundle] 每周期把
     * [RuntimeBundle.create] 的本周期接线束写入，[teardownBundle] 清回默认束（旧周期不得
     * 再产生副作用——旧周期网关已 close）。默认束三闭包全空（未运行/测试直造）⇒ 超时与催办链路整体缺席。
     */
    private val confirmTimedOutSlot: AtomicReference<ConfirmService.TimedOutWiring>,
    /**
     * user.ask_user 挂起等待配对总线（稳定件，与 [confirmService] 同生命周期）：
     * feature 侧提问 gate 经它 register/awaitAnswer，AskAnswerHandler 经它 decide。
     * 稳定件语义：跨 start/stop 存活（在途提问不因桥接重启丢配对——重启后旧回答按
     * 「已不在途」静默跳过，bus 空转零副作用）。
     */
    private val _askUserBus: AskUserBus,
    private val sinkChannels: SinkChannels,
    private val singleInstanceLock: SingleInstanceLock,
    private val agentRunner: AgentRunner,
    /** 未命中兜底调度 agent seam（透传给 [createBundle] → [RuntimeBundle.create] 的 Admission）。null = 行为逐字不变。 */
    private val fallbackHandlerProvider: ((RequestContext) -> MessageHandler?)? = null,
    /**
     * 宿主语音转写缝（App 形态由 [BridgeAssembler.assemble] 从宿主注入；null = CLI/测试形态，
     * AUDIO 消息维持既有降级文案，行为逐字不变）。
     * 透传纪律：本参数必须一路传到 [createBundle] → [RuntimeBundle.create] 的未命中兜底
     * 判定闭包（收参必传——此链漏传即 App 侧语音链路死线且编译期零报警，透传守护锚
     * BridgeAssemblerParityTest ⑩ 组钉死）。
     */
    private val audioTranscriber: AudioTranscriberSeam? = null,
    /** 配置真源（装配期由 [BridgeAssembler] 创建，与 `agentRunner` 的 configProvider 共享同一引用）。 */
    private val configRef: AtomicReference<BridgeConfig>,
    /**
     * 宿主 xlog 私钥回落缝（App 形态由 [BridgeAssembler.assemble]
     * 从宿主注入；null = CLI/测试形态，行为逐字不变）。
     *
     * 契约：同步、非挂起、非抛、快速返回（宿主侧是 AtomicReference 缓存读取）。
     * 🔴 只影响 [createBundle] 的 configProvider **读取层**富化（[enrichXlogKey]）——
     *    [config]/[applyConfig]/[repository] 与 UI draft 全链仍读文档原始值，
     *    回落私钥**绝不写回 bridge-config.json**。
     */
    private val xlogKeyFallback: (() -> String)? = null,
    /**
     * 启动自检宿主缝（App 形态由 [BridgeAssembler.assemble] 注入；null = CLI/测试形态，
     * 恒不探查）。桥接长连接建立后一次性触发（启动触发非定时任务），见 [launchStartupProbe]。
     */
    private val startupProbe: StartupProbeSeam? = null,
    /**
     * 宿主机密根（敏感文件排除判据）——`~/.easy_dev_toolbox` 这类**宿主应用数据根**。
     *
     * 🔴 用于出站文件交付的敏感判据（`[产物]` 标记链 + Koog `send_file`）：落在本根内的文件
     *    一律不外发（桥接配置含明文 appSecret、宿主 `.datastore` 含明文 LLM API Key）。
     * 缺省 null 时回落 `paths.dataRoot`（`~/.toolbelt/lark`）——CLI/测试形态行为不变，
     *    保守覆盖桥接自身配置与审计；App 侧显式传应用数据根以堵住 `.datastore` 同级缺口。
     * 🔴 透传纪律：本参数必须一路传到 `RuntimeBundle.create`
     *    （收参必传——此链漏传致整条链路死线且编译期零报警）。
     */
    private val sensitiveRoot: java.io.File? = null,
    // ---- 引用流稳定件：跨 start/stop 存活 ----
    // 🔴 两件若随 RuntimeBundle 周期重建，stop→start 后登记表即丢：
    //    用户「发文件登记 → 按黄条提示重启桥接 → 引用回复」必然 lookup miss（prompt 落回纯文本，
    //    agent 拿不到文件路径）。提升为 Bridge 稳定件后登记表跨 start/stop 存活（与 counters 同位）。
    //    ⚠️ 进程级重启仍丢（内存表限界，FileRegistry KDoc 已文档化）。
    /** 引用文件登记表（稳定件：登记随 Bridge 生命周期，不随 start/stop 周期）。 */
    internal val fileRegistry: FileRegistry,
    /** 持久下载区账本（稳定件：与 [fileRegistry] 同位提升，防 stop→start 间账本/逐出计数闪断）。 */
    internal val downloadStore: DownloadStore,
    /** 机器人回复账本（稳定件：流式卡终态落账，供「引用机器人回复」本地兜底——飞书无读卡片实体 API）。 */
    internal val cardReplyLedger: CardReplyLedger,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    /** 运行标志（🔴 幂等 start/stop 的唯一判据，CAS）。 */
    private val running = AtomicBoolean(false)

    /** 当前周期的运行时束（未运行时为 null）。 */
    private val bundleRef = AtomicReference<RuntimeBundle?>(null)

    private val confirmStatusRef = AtomicReference<ConfirmRuntimeStatus>(ConfirmRuntimeStatus.Disabled)
    private val connectionRef = AtomicReference<BridgeConnectionStatus>(BridgeConnectionStatus.Idle)

    /** 最近一次异常/失败原因（start 拒绝 / 连接失败等状态迁移时写；成功后保留，体现「最近异常」语义）。 */
    private val lastErrorRef = AtomicReference<String?>(null)

    /** 当前不在运行的原因（stop / start 拒绝时写；成功 start 时清空为 null）。 */
    private val stopReasonRef = AtomicReference<String?>(null)

    // ---- 在线状态：连接时刻 + 最近信号时刻 ----
    /** 本次长连接的建立时刻（start 连上或 Reconnected 成功时写；stop/未运行时无意义——快照按 running 消费）。 */
    private val connectedSinceRef = AtomicReference<Long?>(null)

    /** 最近一次网关生命周期信号到达时刻（Reconnecting/Reconnected/Disconnected 等）。 */
    private val lastSignalAtRef = AtomicReference<Long?>(null)

    private val _status = MutableStateFlow(
        BridgeStatusSnapshot(
            running = false,
            connection = BridgeConnectionStatus.Idle,
            confirm = ConfirmRuntimeStatus.Disabled,
            counters = counters.snapshot(),
            appId = configRef.get().connection.appId,
        ),
    )

    /** 桥接整体状态快照（UI/CLI 只读；由 metrics 协程 1Hz 刷新，start/stop 时立即刷一帧）。 */
    val status: StateFlow<BridgeStatusSnapshot> get() = _status

    /** 环形日志台（UI 日志页订阅）。 */
    val logs: com.jiaoay.lark.bridge.observe.LogBus get() = logBus

    /** 当前生效配置。 */
    val config: BridgeConfig get() = configRef.get()

    /** 配置仓库（宿主保存/加载用）。 */
    val repository: ConfigRepository get() = configRepository

    /** 确认链路可用性（Probing 视为不可用，fail-safe deny）。 */
    val confirmStatus: ConfirmRuntimeStatus get() = confirmStatusRef.get()

    /** 是否正在运行。 */
    val isRunning: Boolean get() = running.get()

    // ==================================================================================
    // start
    // ==================================================================================

    /**
     * 启动桥接。🔴 **恒不抛**：一切失败归一为 [BridgeStartResult.Refused]。幂等（重复调用返回 [BridgeStartResult.AlreadyRunning]）。
     *
     * 🔴 **本方法是 `suspend`**（同理 [applyConfig]）：start()/applyConfig() 是 suspend：其内部是
     * gateway.connect()/updatePolicy() 挂起网络调用。若在非 suspend 公共 API 内用 runBlocking 包住，
     * Compose ViewModel 从主线程调用会冻结 UI（连接握手秒级）。close()/onAppExit() 保持非 suspend，
     * 是 AutoCloseable 与 JVM shutdown hook 的必需桥接，各保留唯一一层 runBlocking。
     */
    suspend fun start(): BridgeStartResult {
        if (!running.compareAndSet(false, true)) {
            return BridgeStartResult.AlreadyRunning
        }
        val cfg = configRef.get()

        // 配置预检：缺 appId ⇒ 不建连、不抛，落 Stopped(reason)。
        if (cfg.connection.appId.isBlank()) {
            running.set(false)
            connectionRef.set(BridgeConnectionStatus.Idle)
            stopReasonRef.set("启动被拒：connection.appId 为空")
            publishStatus()
            log.warn("启动被拒：connection.appId 为空（请在配置中填写飞书应用凭据）")
            return BridgeStartResult.Refused(BridgeErrorCode.ConfigInvalid, "connection.appId 为空")
        }

        // 单实例互斥：App 与 CLI 共享同一把锁。
        when (val lock = singleInstanceLock.tryAcquire(cfg.connection.appId)) {
            is com.jiaoay.lark.bridge.pipeline.LockAcquisition.Acquired -> Unit
            com.jiaoay.lark.bridge.pipeline.LockAcquisition.BlockedByPeerInstance -> {
                running.set(false)
                connectionRef.set(BridgeConnectionStatus.Blocked(com.jiaoay.lark.bridge.observe.BlockedReason.PEER_INSTANCE, "同 appId 已有实例持锁"))
                stopReasonRef.set("启动被拒：同 appId 已有实例持锁")
                publishStatus()
                log.warn("启动被拒：同 appId 已有实例在运行（单实例锁被占）")
                return BridgeStartResult.Refused(BridgeErrorCode.BlockedByPeerInstance, "同 App ID 已有实例运行")
            }
            is com.jiaoay.lark.bridge.pipeline.LockAcquisition.Failed -> {
                running.set(false)
                connectionRef.set(BridgeConnectionStatus.Idle)
                lastErrorRef.set("单实例锁获取失败：${lock.message}")
                stopReasonRef.set("启动被拒：单实例锁获取失败")
                publishStatus()
                log.warn("启动被拒：单实例锁获取失败 ${lock.message}")
                return BridgeStartResult.Refused(lock.error, lock.message)
            }
        }

        connectionRef.set(BridgeConnectionStatus.Connecting)
        publishStatus()

        val bundle = createBundle(cfg)
        bundleRef.set(bundle)

        // 建长连接（挂起网络调用，直接 await；start() 已是 suspend，不再阻塞调用方线程）。
        val cred = LarkCredential(cfg.connection.appId, cfg.connection.appSecret, cfg.connection.region)
        // 🔴 策略经单一构造点（BridgeGateways.policy）：requireMention 恒 false ⇒ SDK 侧全放行，
        //    群@门控归桥接侧 PolicyGate（它才能按消息类型豁免文件消息）。详见 policy() KDoc。
        val policy = BridgeGateways.policy()
        val connect = bundle.gateway.connect(cred, policy)
        when (connect) {
            is com.jiaoay.lark.gateway.GatewayConnectResult.Connected -> {
                connectionRef.set(BridgeConnectionStatus.Connected(connect.botOpenId, connect.botName))
                connectedSinceRef.set(clock())
            }
            com.jiaoay.lark.gateway.GatewayConnectResult.BlockedByPeerInstance -> {
                // start() 已是 suspend ⇒ teardownBundle 直接挂起调用（不折叠、不留残余阻塞块）。
                teardownBundle(bundle, GRACE_MILLIS)
                bundleRef.set(null)
                running.set(false)
                connectionRef.set(BridgeConnectionStatus.Blocked(com.jiaoay.lark.bridge.observe.BlockedReason.PEER_INSTANCE, "对端长连接在线"))
                stopReasonRef.set("启动被拒：同一 appId 已有另一条长连接在线")
                publishStatus()
                return BridgeStartResult.Refused(BridgeErrorCode.BlockedByPeerInstance, "同一 appId 已有另一条长连接在线")
            }
            is com.jiaoay.lark.gateway.GatewayConnectResult.Failed -> {
                teardownBundle(bundle, GRACE_MILLIS)
                bundleRef.set(null)
                running.set(false)
                singleInstanceLock.release()
                connectionRef.set(BridgeConnectionStatus.Disconnected(connect.error, connect.larkMsg ?: "连接失败"))
                lastErrorRef.set("长连接失败：${connect.larkMsg ?: connect.error.name}")
                stopReasonRef.set("启动被拒：长连接失败")
                publishStatus()
                log.warn("启动被拒：长连接失败 code=${connect.error} lark=${connect.larkCode} ${connect.larkMsg}")
                return BridgeStartResult.Refused(BridgeErrorCode.ConnectFailed, connect.larkMsg ?: connect.error.name)
            }
        }

        // 启动五阶段管线 + 落盘 + 指标。
        bundle.startLoops()

        // 成功启动 ⇒ 清空停止原因（正在运行）；最近异常保留（体现「最近一次异常」语义）。
        stopReasonRef.set(null)
        publishStatus()
        log.info("桥接已启动 appId=${cfg.connection.appId.take(6)}*** bot=${connectionRef.get()}")
        // 启动自检推送（健康观测面）：start 成功路径一次性触发（启动触发非定时任务），
        //    两闸（宿主缝 + testChatId 已配置）缺一当次跳过（开关闸已随 v13→v14 退役）。
        launchStartupProbe(bundle, cfg)
        return BridgeStartResult.Started
    }

    /**
     * 启动自检推送：长连接建立后跑**一次**确定性机器健康探查，异常态/探查失败的模板消息
     * 经既有出站面 [sendTest] 发默认会话（connection.testChatId——与「发送测试消息」/
     * 报告外发同源的唯一直发会话配置），正常态静默零消息。
     *
     * - 启动触发非定时任务：只在 start 成功路径调用一次，无任何重试/周期/重连再触发面
     *   （定时自检属能力负面清单的无人值守面，恒不进本方法）；
     * - 两闸：宿主缝缺席（CLI/测试形态）/ testChatId 空白 ⇒ 当次跳过（开关闸已随
     *   v13→v14 退役——自检恒启用，只由接收会话是否配置决定）；
     * - 探查在 [RuntimeBundle] 周期作用域的旁路协程执行（confirmBusScope——发送者统计旁路的
     *   同款挂载点），不阻塞 start 返回；stop 十步随周期取消收口；
     * - 缝实现契约见 [com.jiaoay.lark.bridge.observe.StartupProbeSeam]（业务异常由实现
     *   折叠进返回文本；此处仅防御性兜底，取消原样上抛不吞）。
     */
    private fun launchStartupProbe(bundle: RuntimeBundle, cfg: BridgeConfig) {
        val probe = startupProbe ?: return
        val chatId = cfg.connection.testChatId
        if (chatId.isBlank()) {
            log.info("启动自检跳过：未配置接收会话（connection.testChatId 为空）——填写后下次启动生效")
            return
        }
        bundle.runtime.confirmBusScope.launch {
            val message = try {
                probe.runStartupProbe()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.warn("启动自检执行异常（已放弃本次推送）：${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (message == null) {
                log.info("启动自检完成：未见异常（正常态静默零消息）")
                return@launch
            }
            val result = sendTest(chatId, message)
            if (result?.ok == true) {
                log.info("启动自检报告已推送（接收会话尾号 …${chatId.takeLast(4)}）")
            } else {
                log.warn("启动自检报告推送失败：${result?.error?.name ?: "桥接未运行"}")
            }
        }
    }

    // ==================================================================================
    // stop（十步优雅关闭）
    // ==================================================================================

    /**
     * 优雅关闭（方案十步，每步都有超时上限）。幂等（未运行时 no-op）。
     * @param graceMillis 在途任务的排空预算（默认 5000；超时后强杀）。
     */
    suspend fun stop(graceMillis: Long = GRACE_MILLIS) {
        if (!running.compareAndSet(true, false)) return
        val bundle = bundleRef.getAndSet(null) ?: run {
            singleInstanceLock.release()
            connectionRef.set(BridgeConnectionStatus.Idle)
            // 记录停止原因（bundle 已为 null 的早返回路径）。
            stopReasonRef.set("已优雅停止")
            publishStatus()
            return
        }
        connectionRef.set(BridgeConnectionStatus.Draining(bundle.admissionInFlight()))
        publishStatus()
        teardownBundle(bundle, graceMillis)
        connectionRef.set(BridgeConnectionStatus.Idle)
        // 连接周期结束，connectedSince 清空（快照不再显示「连接自」）。
        connectedSinceRef.set(null)
        confirmStatusRef.set(ConfirmRuntimeStatus.Disabled)
        // 十步关闭完成 ⇒ 记录停止原因（UI 状态区「停止原因」行的真实来源）。
        stopReasonRef.set("已优雅停止")
        publishStatus()
        log.info("桥接已停止")
    }

    /**
     * 🔴 十步优雅关闭（方案 line 239 逐字）。全体十步统一包一层 [withContext]([NonCancellable])：
     * 即便调用 [stop] 的 job 被取消，优雅关闭也必须**跑完每一步**（在途任务排空 / killAll / 网关 disconnect+close /
     * 落盘 flush 一步都不能少，否则漏线程、漏子进程、丢在途审计）。
     * 每步都用 [withTimeoutOrNull] 兜上限（超时是 [withTimeoutOrNull] 自己的 TimeoutCancellationException，
     * 与 NonCancellable 正交，仍能正常兜住），任一步超时/异常都不阻断后续步。
     * 步骤：① 关入站队列并计数残留 → ② 取消在途任务并等排空 → ③ killAll 子进程 → ④ 开放卡片流发终态卡并关闭 →
     * ⑤ gateway.disconnect → ⑥ gateway.close 释放 SDK 线程/连接池 → ⑦ audit/stats flushAndClose →
     * ⑧ configWriter.flushNow → ⑨ 释放 appId 锁 → scope.cancel → Stopped。
     */
    private suspend fun teardownBundle(bundle: RuntimeBundle, graceMillis: Long) = withContext(NonCancellable) {
        // ① 关入站队列（停止接收新事件），计数残留。
        runCatching {
            val residual = bundle.queue.drainNow()
            if (residual > 0) log.info("关闭①：入站队列残留 $residual 条已丢弃")
            bundle.queue.close()
        }.onFailure { log.warn("关闭①：入站队列关闭异常 ${it.message}") }

        // ② 取消在途任务并等排空（grace 预算）。
        runCatching {
            bundle.runtime.cancelTasksOnly()
            withTimeoutOrNull(graceMillis) { bundle.runtime.job.children.forEach { it.join() } }
        }.onFailure { log.warn("关闭②：在途任务排空异常 ${it.message}") }

        // ③ 强杀仍存活的子进程（②超时后的兜底）。
        runCatching { bundle.processes.killAll() }
            .onFailure { log.warn("关闭③：killAll 异常 ${it.message}") }

        // ④ 遍历开放卡片流发终态卡 + close（3s；已在外层 NonCancellable 内，无需再嵌一层）。
        runCatching {
            withTimeoutOrNull(STREAM_CLOSE_MILLIS) { bundle.closeOpenStreams() }
        }.onFailure { log.warn("关闭④：卡片流收尾异常 ${it.message}") }

        // ⑤ gateway.disconnect（5s）。
        runCatching { withTimeoutOrNull(GATEWAY_STEP_MILLIS) { bundle.gateway.disconnect() } }
            .onFailure { log.warn("关闭⑤：disconnect 异常 ${it.message}") }

        // ⑥ gateway.close 释放 SDK 线程/连接池（5s）。
        runCatching { withTimeoutOrNull(GATEWAY_STEP_MILLIS) { bundle.gateway.close() } }
            .onFailure { log.warn("关闭⑥：gateway.close 异常 ${it.message}") }

        // ⑦ audit/stats 落盘收尾（cancel + join ⇒ JsonlSink.run 的 finally 在 NonCancellable 内 flush+close，绝不丢在途审计）。
        // 🔴 FLUSH_STEP_MILLIS 上限（与④⑤⑥同量级）——flushSinks 是 cancel+join，文件系统 hang 时会卡死整个关闭。
        //    超时记 warn 后**继续**后续步骤（不因一步超时放弃剩下的关闭）；仍在外层 withContext(NonCancellable) 内：
        //    加超时 ≠ 加可取消性（withTimeoutOrNull 的 TimeoutCancellationException 自兜，外层 job 取消时本步仍跑完）。
        runCatching {
            if (withTimeoutOrNull(FLUSH_STEP_MILLIS) { bundle.flushSinks() } == null) {
                log.warn("关闭⑦：落盘收尾超时（>${FLUSH_STEP_MILLIS}ms），继续后续关闭步骤")
            }
        }.onFailure { log.warn("关闭⑦：落盘收尾异常 ${it.message}") }

        // ⑧ configWriter.flushNow（落盘最后一次配置；已在外层 NonCancellable 内，flushNow 内再包一层是双保险）。
        // 🔴 FLUSH_STEP_MILLIS 上限——flushNow 在 Dispatchers.IO 上做 writeAtomic，文件系统 hang 时会卡死关闭；超时记 warn 后继续。
        runCatching {
            if (withTimeoutOrNull(FLUSH_STEP_MILLIS) { configRepository.flushNow() } == null) {
                log.warn("关闭⑧：配置落盘超时（>${FLUSH_STEP_MILLIS}ms），继续后续关闭步骤")
            }
        }.onFailure { log.warn("关闭⑧：配置落盘异常 ${it.message}") }

        // 🔴 清超时/催办接线槽（旧周期网关已 close，槽若残留
        // 会在 stop→start 间隙把超时回调路由到已关网关上）。新周期 createBundle 时重新接线。
        confirmTimedOutSlot.set(ConfirmService.TimedOutWiring())

        // ⑩ 释放 appId 锁 → scope.cancel → Stopped。
        runCatching { singleInstanceLock.release() }
            .onFailure { log.warn("关闭⑩：释放单实例锁异常 ${it.message}") }
        runCatching { bundle.runtime.cancel() }
            .onFailure { log.warn("关闭⑩：runtime.cancel 异常 ${it.message}") }
    }

    /**
     * [close]：[stop] + 释放锁 + 取消 supervisor 全树 + 关闭进程登记表（进程退出 / 宿主销毁）。
     * 🔴 保持**非 suspend**（[AutoCloseable] 契约 + `use{}` 语法要求），故此处保留**唯一一层**阻塞桥接
     * （阻塞世界 → 挂起世界，另见 [onAppExit]）。全类仅 [close]/[onAppExit] 两处这样的阻塞桥接。
     */
    override fun close() {
        kotlinx.coroutines.runBlocking { stop(GRACE_MILLIS) }
        runCatching { singleInstanceLock.close() }
        runCatching { sinkChannels.close() }
        runCatching { processRegistry.close() }
        runCatching { supervisor.close() }
    }

    /**
     * 宿主退出钩子（`onAppExit()` / JVM shutdown hook / Compose `onDispose`）：立即落盘配置 + 优雅停止。
     * 🔴 保持**非 suspend**（JVM shutdown hook 是 `Thread`，无法 suspend），故保留**唯一一层**阻塞桥接。
     *
     * ## EDT 冻结修复（关窗最坏 ~18s ⇒ EDT 阻塞 ≤ [APP_EXIT_EDT_BUDGET_MILLIS]）
     * 十步 [teardownBundle] 中 ②join(GRACE 5s) + ④closeOpenStreams(3s) + ⑤disconnect(5s) + ⑥gateway.close(5s)
     * 四步各带超时预算，若在调用线程（Compose App 是 EDT/UI 线程）上串行 `close()`，最坏会把 EDT
     * 冻结约 18 秒（相对旧非阻塞 `LarkBridge.stop()` 的明确回归）。本方法改为三段式：
     *  1. **⑧ 配置落盘在 EDT 上同步先做**（`flushNow`，几十 ms，数据安全关键步，绝不后台化、绝不经预算截断）；
     *  2. 其余整个 [close]（含十步 teardown）挪到独立**非守护**后台线程执行；
     *  3. EDT 侧只 `Thread.join([APP_EXIT_EDT_BUDGET_MILLIS])` 等它，到点即返回让窗口关闭，后台线程继续跑完十步。
     *
     * 🔴 **为何用 `Thread.join(预算)` 而非 `withTimeoutOrNull` 包 teardown**：teardown 全体在 `withContext(NonCancellable)`
     *    内，NonCancellable 子块对外层取消免疫 —— `withTimeoutOrNull` 会**等它跑完**才返回，压不住时间；`Thread.join(ms)`
     *    是硬墙钟上限，与被等待线程内部是否 NonCancellable 无关，到点必返回。
     * 🔴 **为何后台线程设为非守护**：保证 JVM 存活至十步收尾跑完 —— ③killAll / ⑦audit+stats flush / ⑧flushNow / ⑩锁释放
     *    都在后台**必然完成**，不因 EDT 提前返回而丢失（数据安全要求）；而十步每步都有超时上限（最坏 ~18s），
     *    故非守护线程**有界终止**：第⑥步 `gateway.close()` 释放 SDK 的 2 个非守护线程后 JVM 随即退出，**不会永久挂住**。
     *
     * ⚠️ **残余风险登记（EDT 提前返回、慢网络步骤仍在后台时）**：窗口已关但 JVM 可能再存活最多 ~18s；
     *    此期间飞书侧可能残留「在线」状态一小段时间（⑤disconnect 尚未完成），某张仍在流式的卡片可能短暂停在
     *    「Generating...」（④closeOpenStreams 尚未发终态卡）。这些都会在后台十步跑完后收敛，属可接受的关闭延迟。
     */
    fun onAppExit() {
        // ⑧ 数据安全关键步：EDT 上同步落盘配置（快，几十 ms），绝不后台化、绝不经预算截断。
        runCatching { kotlinx.coroutines.runBlocking { withContext(NonCancellable) { configRepository.flushNow() } } }
        // 慢收尾（十步 teardown，最坏 ~18s）挪到独立非守护后台线程；EDT 只 join 预算时长后返回（详见 onAppExit KDoc）。
        // 复用 close()（其内已是唯一一层 runBlocking），不新增阻塞桥接 ⇒ 全类 runBlocking 真实调用点仍为 2。
        val exitThread = Thread({ runCatching { close() } }, "lark-bridge-exit").apply { isDaemon = false }
        exitThread.start()
        runCatching { exitThread.join(APP_EXIT_EDT_BUDGET_MILLIS) }
    }

    // ==================================================================================
    // HOT / RESTART 分流
    // ==================================================================================

    /**
     * 应用新配置（CDS §4.4 热应用/需重启二分）。
     * - 变更字段全 HOT ⇒ 无锁替换 [configRef] + `gateway.updatePolicy()` + 重建并原子发布路由快照，**不断连**；
     * - 变更含 RESTART 字段 ⇒ 只更新 [configRef]，返回 [ConfigApplyResult.RestartRequired]，由宿主决定 stop→start。
     * 用 [BridgeFields.ALL] 的 `applyMode` 做结构化判定（不硬编码字段名清单）。
     *
     * **本方法是 `suspend`**（内部 `gateway.updatePolicy()` 是挂起网络调用，见 [start] KDoc 的裁定原文）。
     * @param persist 是否把 [next] 落盘（`configRepository.save`）。🔴 CLI `--set` 走 **ephemeral** 语义传 `false`
     *        （覆写只在本进程内存生效，**任何情况都不写 bridge-config.json**，见 [com.jiaoay.lark.bridge.cli.BridgeConfigCommand.resolveConfig]）；
     *        App UI 编辑器保存走默认 `true`。传 `false` 时 stop() 第⑧步 flushNow 因无 pending draft 而天然 no-op。
     */
    suspend fun applyConfig(next: BridgeConfig, persist: Boolean = true): ConfigApplyResult {
        val current = configRef.get()
        if (current == next) return ConfigApplyResult.Unchanged
        val changedRestart = BridgeFields.ALL.filter { field ->
            field.applyMode == ApplyMode.RESTART && runCatching { field.get(current) != field.get(next) }.getOrDefault(false)
        }.map { it.id }
        configRef.set(next)
        // 🔴 ephemeral 覆写（persist=false）绝不落盘：只换内存快照，stop()⑧ flushNow 无 pending draft ⇒ no-op。
        if (persist) configRepository.save(next)
        val bundle = bundleRef.get()
        if (changedRestart.isNotEmpty()) {
            log.info("配置变更含 RESTART 字段 $changedRestart：已记录，需重启生效")
            return ConfigApplyResult.RestartRequired(changedRestart)
        }
        if (bundle != null) {
            // HOT：重建并原子发布快照（在途任务持旧引用继续跑完）。
            // 策略全放行（requireMention 恒 false，经单一构造点）⇒ 群聊唤起模式（agent.groupMode，
            //    唯一真源）变化由桥接侧 PolicyGate 每消息现读配置生效（HOT 语义不丢）。
            val policy = BridgeGateways.policy()
            runCatching { bundle.gateway.updatePolicy(policy) }
                .onFailure { log.warn("HOT 下推策略失败 ${it.message}") }
            // 🔴 downloadMaxBytes 热更（fileMessage.maxFileSizeBytes，ApplyMode.HOT）
            //    此前只在网关构造期固化——HOT 分支不下推 ⇒ 改上限保存后网关仍按旧值拒下载
            //    （真机取证：500MB 配置被 100MB 构造快照拦下 335MB 文件）。经网关热更口原子替换，即刻生效。
            runCatching { bundle.gateway.updateDownloadSettings(next.fileMessage.maxFileSizeBytes) }
                .onFailure { log.warn("HOT 下推下载上限失败 ${it.message}") }
            bundle.republishSnapshot(next)
            // 并发上限是 HOT 字段：同步调整许可闸门。
            bundle.permits.reconfigure(next.runtime.maxConcurrentAgents)
            // 🔴 ProcessRegistry 容量同步热更（同 maxConcurrentAgents）——
            //    此前装配期固化：HOT 扩容后 PermitGate 放行、registry 按旧容量拒收新进程。
            bundle.processes.reconfigureCapacity(next.runtime.maxConcurrentAgents)
        }
        publishStatus()
        log.info("配置已热应用（HOT）")
        return ConfigApplyResult.HotApplied
    }

    // ==================================================================================
    // 状态刷新
    // ==================================================================================

    internal fun publishStatus() {
        val bundle = bundleRef.get()
        // 立即采一帧（不等 1Hz 周期）：start/stop 的状态跳变要即时反映到 UI/CLI。
        val snapshot = bundle?.metrics?.sample() ?: BridgeStatusSnapshot(
            running = running.get(),
            connection = connectionRef.get(),
            confirm = confirmStatusRef.get(),
            counters = counters.snapshot(),
        )
        // MetricsSampler.sample() 只感知管线运行时指标，不感知门面的 appId/lastError/stopReason；
        //    publishStatus() 是 _status 唯一写入漏斗，故在此用 copy() 注入这 3 个有真实来源的门面级字段。
        //    「重启次数」在新架构无自然计数点（重连由 SDK 内部管理、RESTART 字段变更由宿主 stop→start 决策），不伪造。
        _status.value = snapshot.copy(
            appId = configRef.get().connection.appId,
            lastError = lastErrorRef.get(),
            stopReason = stopReasonRef.get(),
            // 连接时刻/最近信号时刻（快照字段的真实来源）。
            connectedSinceMillis = connectedSinceRef.get(),
            lastSignalAtMillis = lastSignalAtRef.get(),
        )
    }

    // ==================================================================================
    // 发测试消息 + 环境预检（门面转发 gateway / agentRunner；零真实网络靠 dryRun→SyntheticGateway 保证）
    // ==================================================================================

    /**
     * 发送一条测试消息到指定会话。
     * 未运行时返回 null（无 gateway 可用）；调用方据此提示「请先启动桥接」。
     * 错误以 [SendResult] 返回值表达，不抛业务异常（与网关同纪律）；宿主经 `BridgeErrorMapping` 归一错误码。
     */
    suspend fun sendTest(chatId: String, text: String): SendResult? {
        val gateway = bundleRef.get()?.gateway ?: return null
        if (chatId.isBlank()) return SendResult(ok = false, messageId = null, error = com.jiaoay.lark.gateway.GatewayErrorCode.FormatError)
        return runCatching { gateway.send(LarkTarget.Chat(chatId), Outbound.text(text)) }
            .getOrElse { SendResult(ok = false, messageId = null, error = com.jiaoay.lark.gateway.GatewayErrorCode.SendFailed) }
    }

    /**
     * 发送一个本地文件到指定会话（文件消息两步式：上传得 file_key → 发 msg_type=file，
     * 与产物回传通道同款传输形态）。
     *
     * 未运行时返回 null（与 [sendTest] 同契约）；已运行时的结果见 [FileSendOutcome]——
     * 输入校验与出站排除表在传输前判，拒绝不消耗上传配额。
     *
     * 出站排除表判据（[com.jiaoay.lark.bridge.exec.SensitiveFilePolicy.isForbidden]）与
     * `send_file` 工具、`[产物]` 标记链**同一张表**：出站文件通道只装两张表中的
     * 一张即同宽旁路——本门面是第三条出站通道，必须共用；判据 1 的机密根取装配期
     * [sensitiveRoot]（宿主应用数据根；缺省回落 `paths.dataRoot`，与产物回传通道一致）。
     * 调用方是宿主 UI（用户主动点按），路径非 LLM 可控，但 fail-closed 语义不因
     * 信任来源放宽——排除表对任何调用方形态恒一致。
     */
    suspend fun sendFile(chatId: String, file: java.nio.file.Path): FileSendOutcome? {
        val gateway = bundleRef.get()?.gateway ?: return null
        val fileObj = file.toFile()
        if (chatId.isBlank() || !fileObj.isFile) return FileSendOutcome.InvalidInput
        val homeRoot = sensitiveRoot ?: paths.dataRoot.toFile()
        if (com.jiaoay.lark.bridge.exec.SensitiveFilePolicy.isForbidden(fileObj, homeRoot)) {
            return FileSendOutcome.Denied
        }
        return runCatching {
            val fileKey = gateway.uploadFile(file, fileObj.name)
                ?: return@runCatching FileSendOutcome.Failed(
                    error = com.jiaoay.lark.gateway.GatewayErrorCode.UploadFailed,
                    larkMsg = null,
                )
            val send = gateway.send(LarkTarget.Chat(chatId), Outbound.file(fileKey, fileObj.name))
            if (send.ok) {
                FileSendOutcome.Sent(messageId = send.messageId)
            } else {
                FileSendOutcome.Failed(error = send.error, larkMsg = send.larkMsg)
            }
        }.getOrElse {
            FileSendOutcome.Failed(error = com.jiaoay.lark.gateway.GatewayErrorCode.SendFailed, larkMsg = null)
        }
    }

    /**
     * 环境预检：同步汇总 agent 后端可用性 + 凭据就绪 + 运行态，零网络调用。
     * agent 可用性复用 [AgentRunner.isAvailable]（后端配置有效 + 可执行文件可解析）。
     */
    fun probeEnvironment(): EnvironmentProbe = EnvironmentProbe(
        agentBackend = agentRunner.backendId,
        agentAvailable = agentRunner.isAvailable,
        connectionAppId = configRef.get().connection.appId,
        running = running.get(),
    )

    /**
     * L2 授权验证探测：对单个 [NativeToolGrant] 做**只读**探测——
     * 经**运行中**网关（[bundleRef] 同一实例，复用既有长连接），🔴 绝不另造网关绝不 connect：
     * 另造一次性网关会开第二条 `:lark-ws` 入站长连接，触发「同 appId 双长连接被服务端互踢」
     * （docs/pages/lark-bridge.md「自研长连接客户端」节）——桥接运行中点「验证」会把自己踢下线。
     * 探测动作收口 lib 门面（与 [sendTest] 同形态），feature 侧不接触 gateway 实例。
     *
     * 探测动作（只读，与 [com.jiaoay.lark.gateway.MessageGateway] 语义一一对应）：
     * - [NativeToolGrant.CHAT_HISTORY]：listMessages(testChatId, 1) 非 null
     * - [NativeToolGrant.CHAT_INFO] / [NativeToolGrant.CHAT_MANAGE]：getChatInfo(testChatId) 非 null
     *   （二者共享 im:chat scope，探测同源）
     * - [NativeToolGrant.LOOKUP_USER]：resolveSenderProfile(lookupOpenId) 非 null
     * - [NativeToolGrant.READ_WIKI] / [NativeToolGrant.READ_DOC]：按 [probeDocUrl] 分流探测
     *   （详见参数说明——目标由用户粘贴的链接指定）。
     *
     * 失败人话来源：探测期间订阅网关 [com.jiaoay.lark.gateway.GatewaySignal] 流抓
     *    SendFailed 信号取 [com.jiaoay.lark.gateway.GatewayErrorCode]（超时/取消正常收口不泄漏）；
     *    scope 不足等静默 null 场景回落调用方传入的默认失败文案。
     *
     * READ_WIKI / READ_DOC 的探测目标由调用方提供 [probeDocUrl]（用户粘贴的一条 wiki/docx 链接——
     * 自建应用无保证可读的公共节点，探测目标必须由用户指定）：
     * - [NativeToolGrant.READ_WIKI]：`/wiki/` 链接两步走（resolveWikiNode 成功即通过——验证 wiki scope
     *   与节点可见性；后续 readDocumentText 失败属 docx scope 缺席，不归 wiki 探测管辖）；
     * - [NativeToolGrant.READ_DOC]：`/docx/` 链接直读（readDocumentText 非 null 即通过）；
     * - [probeDocUrl] 空 / 链接形态与 grant 不匹配 ⇒ 返回引导文案（不发网络）。
     *
     * @return null = 通过；非 null = 失败人话。不抛业务异常（与 [sendTest] 同纪律）。
     */
    suspend fun probeNativeGrant(
        grant: NativeToolGrant,
        testChatId: String,
        lookupOpenId: String,
        defaultFailText: String,
        timeoutMillis: Long = PROBE_GRANT_TIMEOUT_MILLIS,
        probeDocUrl: String = "",
    ): String? {
        val gateway = bundleRef.get()?.gateway ?: return "桥接未运行（请先启动后再验证）"
        val errorTextRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
        return kotlinx.coroutines.coroutineScope {
            val signalJob = launch {
                runCatching {
                    gateway.signals.first { it is GatewaySignal.SendFailed }.let {
                        errorTextRef.set((it as GatewaySignal.SendFailed).code.name)
                    }
                }
            }
            try {
                val ok = when (grant) {
                    NativeToolGrant.CHAT_HISTORY ->
                        testChatId.isNotBlank() &&
                            kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                                gateway.listMessages(testChatId, pageSize = 1)
                            } != null
                    NativeToolGrant.CHAT_INFO, NativeToolGrant.CHAT_MANAGE ->
                        testChatId.isNotBlank() &&
                            kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                                gateway.getChatInfo(testChatId)
                            } != null
                    NativeToolGrant.LOOKUP_USER ->
                        lookupOpenId.isNotBlank() &&
                            kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                                gateway.resolveSenderProfile(lookupOpenId)
                            } != null
                    // 读文档探测：URL 分流在 NativeChatSurfaceKt.splitWikiUrl 同款口径——
                    // 此处内联最小判定（lib 内不引用 pipeline internal 件），形态不匹配返回引导文案
                    NativeToolGrant.READ_WIKI -> {
                        val token = probeDocUrl.substringAfter("/wiki/", "")
                            .split('/', '?', '&')
                            .firstOrNull { it.isNotBlank() }
                        token != null &&
                            kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                                gateway.resolveWikiNode(token)
                            } != null
                    }
                    NativeToolGrant.READ_DOC -> {
                        val token = probeDocUrl.substringAfter("/docx/", "")
                            .split('/', '?', '&')
                            .firstOrNull { it.isNotBlank() }
                        token != null &&
                            kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
                                gateway.readDocumentText(token)
                            } != null
                    }
                }
                // SendFailed 信号带回的是 GatewayErrorCode.name（枚举名稳定）——人话由宿主层映射
                if (ok) null else errorTextRef.get()?.let { "网关错误码：$it" } ?: defaultFailText
            } finally {
                signalJob.cancel()
            }
        }
    }

    /**
     * 进行中确认的只读视图（驾驶舱「待你确认」区块数据面）。
     *
     * 读 [confirmService.pendingViews]（纯内存快照映射，🔴 非挂起、零网络——配合 1Hz 状态快照节奏
     * 拉取；挂起网络调用绝不进采样循环——进了会被网络往返卡住，1Hz 状态快照全量停更）。未运行/未装配（bundleRef 空）时**返回空表**——与
     * [probeNativeGrant] 的「未运行人话」不同：空表是本区块「PENDING 非空才渲染」的自然缺席形态。
     * 🔴 恒不抛（runCatching 兜底）；恒经运行中 [ConfirmService] 实例，绝不另造（防「同 appId
     * 同 [probeNativeGrant] KDoc）。
     */
    fun pendingConfirmations(): List<PendingConfirmView> =
        runCatching { confirmService.pendingViews() }.getOrDefault(emptyList())

    /**
     * 消息表情回应（最近任务行的「👍 ×N」徽标数据面）。
     *
     * 经**运行中**网关（[bundleRef] 同一实例，复用既有长连接），🔴 绝不另造网关绝不 connect
     * （互踢防复发纪律同 [probeNativeGrant] KDoc）。
     *
     * 🔴 协程取消原样重抛（门面头纪律「不吞 CancellationException」）。
     * UI 侧 `LaunchedEffect(pendingViews)` 在 pending 集结构变化时重启并取消旧协程，在途查询的 CE
     * 若在此折叠成 null，调用方（已读/徽标退避计数）会虚假记失败一次——健康卡被烧退避额度。
     *
     * @return 表情回应简报（含机器人自点——调用方过滤 [BridgeConnectionStatus.Connected.botOpenId]）；
     *   null = 未运行/能力缺席/查询失败（诚实缺席，调用方不渲染徽标）。
     */
    suspend fun listMessageReactions(messageId: String): List<ReactionBrief>? {
        val gateway = bundleRef.get()?.gateway ?: return null
        return try {
            gateway.listReactions(messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 消息已读人数（「待你确认」区块的「已读 N 人」）。
     *
     * 经**运行中**网关只读（🔴 恒经运行中网关绝不另造——互踢防复发纪律同 [probeNativeGrant] KDoc）；
     * 🔴 **有界翻页聚合**：单页 [READ_RECEIPT_PAGE_SIZE]=100，`hasMore && nextPageToken != null` 循环
     * 累计 readOpenIds 数，上限 [READ_RECEIPT_MAX_PAGES]=5 页（500 人封顶）后返回累计值——
     * 防病态服务器分页失控（与 SdkLarkGateway.listReactions 的 maxPages 上界同款纪律）。
     *
     * 🔴 协程取消原样重抛（门面头纪律「不吞 CancellationException」）。
     * 面板侧 `LaunchedEffect(pendingViews)` 重启竞态取消在途翻页时，CE 被旧 `runCatching` 折叠成
     * null ⇒ 调用方退避计数虚假 +1（READ_QUERY_MAX_FAILURES=3 下两次重启即让健康卡永久「已读 —」）。
     *
     * @return 已读人数（跨页聚合）；null = 未运行/能力缺席/查询失败（UI 显示「已读 —」诚实缺席）。
     */
    suspend fun messageReadCount(messageId: String): Int? {
        val gateway = bundleRef.get()?.gateway ?: return null
        return try {
            var total = 0
            var token: String? = null
            var pages = 0
            do {
                val page = gateway.readReceipts(
                    messageId = messageId,
                    pageSize = READ_RECEIPT_PAGE_SIZE,
                    pageToken = token,
                ) ?: return if (pages == 0) null else total
                total += page.readOpenIds.size
                token = page.nextPageToken?.takeIf { page.hasMore }
                pages++
            } while (token != null && pages < READ_RECEIPT_MAX_PAGES)
            total
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * user.ask_user 挂起配对总线（稳定件只读透出口）——feature 侧提问 gate 经此 register/
     * awaitAnswer，AskAnswerHandler 经此 decide。CLI 形态/测试直造同样在（未装配运行期
     * 时 pending 恒空 = ask_user 链路缺席，fail-closed 语义由 feature 侧 gate 缺席注册门控承载）。
     */
    val askUserBus get() = _askUserBus

    /**
     * 扫码创建自建应用（产品转正）。**未装配也可用**：不进 [bundleRef]、
     * 不 connect——直接经 [gatewayFactory] 构造**一次性**网关调 `registerApp`，用完 `close`。
     * region 从当前配置的 [com.jiaoay.lark.bridge.config.BridgeConfig.connection] 透传
     * （RegisterAppFlow 不返回 region，gateway-contract.md §8.3 规格 6）。
     * 错误以 [RegisterAppResult] 返回值表达，不抛业务异常（与 [sendTest] 同纪律）——
     * [com.jiaoay.lark.gateway.GatewayException] 在此归一，宿主拿 sealed 结果分发 UI。
     * 🔴 流程阻塞最长约 600s 且不可中断（SDK 无 cancel 钩子）：调用方须在**独立协程**里调用，
     * 不要挂页面作用域。
     */
    suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): RegisterAppResult {
        val region = configRef.get().connection.region
        val gateway = runCatching { gatewayFactory() }.getOrElse {
            return RegisterAppResult.Failed("网关构造失败：${it.message}")
        }
        try {
            val credential = runCatching { gateway.registerApp(onQr) }
                .getOrElse { e ->
                    return when {
                        e is com.jiaoay.lark.gateway.GatewayException && e.code == com.jiaoay.lark.gateway.GatewayErrorCode.RegisterQrExpired ->
                            RegisterAppResult.QrExpired(e.message ?: "二维码已过期")
                        e is com.jiaoay.lark.gateway.GatewayException && e.code == com.jiaoay.lark.gateway.GatewayErrorCode.RegisterInvalidArgument ->
                            RegisterAppResult.InvalidArgument(e.message ?: "参数不合法")
                        else -> RegisterAppResult.Failed(e.message ?: "扫码建应用失败")
                    }
                }
            return RegisterAppResult.Success(credential)
        } finally {
            runCatching { gateway.close() }
        }
    }

    internal fun connectionStatus(): BridgeConnectionStatus = connectionRef.get()

    /**
     * 网关重连/恢复信号的**守卫写入点**（由 RuntimeBundle.handleSignal 回调）。
     *
     * 🔴 **状态机守卫**（评审修正）：仅当 `running==true` 且当前态 ∈ {Connected, Reconnecting} 时才写
     *    connectionRef——Blocked/Draining/Idle/Disconnected 等态由 Bridge 的 start/stop/拒绝路径**独占**，
     *    重连信号绝不允许覆盖它们（否则 stop 序列中一个迟到的 Reconnected 会把已 Draining 的态拉回 Connected）。
     *    Reconnected 时重置 connectedSince（新连接周期）并清空该路径上的 lastError 语义不变（保守不动）。
     * 写后立即 publishStatus() 补帧（不等 1Hz tick——重连迁移是用户关心的瞬时事件）。
     */
    private fun onGatewayConnectionSignal(next: BridgeConnectionStatus) {
        lastSignalAtRef.set(clock())
        if (!running.get()) return
        val current = connectionRef.get()
        val allowed = current is BridgeConnectionStatus.Connected || current is BridgeConnectionStatus.Reconnecting
        if (!allowed) return
        connectionRef.set(next)
        if (next is BridgeConnectionStatus.Connected) {
            // Reconnected：恢复的 Connected 带不上 bot identity（信号无载体）——沿用 GatewayConnectionState.Connected
            // 映射惯例置 null（BridgeStatusPanel.label() 对 null botName 已有降级「已连接」）。
            connectedSinceRef.set(clock())
        }
        publishStatus()
    }

    /**
     * 装配本周期的运行时束（把门面的私有稳定件 + 活状态 provider 交给 [RuntimeBundle.create]）。
     * 只有本方法能同时读到 Bridge 的私有字段，故装配的「桥接侧接线」落在这里，
     *    而「管线侧接线」落在 [RuntimeBundle.create]（与 Harness 同形）——职责清晰、无字段外泄。
     */
    internal fun createBundle(cfg: BridgeConfig): RuntimeBundle = RuntimeBundle.create(
        cfg = cfg,
        paths = paths,
        log = log,
        logBus = logBus,
        gatewayFactory = gatewayFactory,
        dispatchers = dispatchers,
        supervisor = supervisor,
        counters = counters,
        processRegistry = processRegistry,
        confirmService = confirmService,
        // 🔴 稳定槽透传给 RuntimeBundle.create——每周期接线本周期 gateway
        //    （fallbackHandlerProvider 漏传死线事故同型防线：收参必传）。
        confirmTimedOutSlot = confirmTimedOutSlot,
        // ask_user 挂起配对总线透传（稳定件，AskAnswerHandler 的 COMMAND 槽接线依赖）。
        askUserBus = _askUserBus,
        sinkChannels = sinkChannels,
        agentRunner = agentRunner,
        // 未命中兜底调度 agent seam 透传。
        fallbackHandlerProvider = fallbackHandlerProvider,
        // 宿主语音转写缝透传（收参必传——漏传即 App 侧语音链路死线）。
        audioTranscriber = audioTranscriber,
        // 引用流稳定件：Bridge 构造期创建，跨 start/stop 传递
        //    （RegistryBundle.create 只接线、不 new——stop→start 登记表存活）。
        fileRegistry = fileRegistry,
        downloadStore = downloadStore,
        cardReplyLedger = cardReplyLedger,
        // configProvider 读取层富化——文档私钥空时回落宿主注入源。
        // 🔴 只包在 RuntimeBundle 的 configProvider 上（configRef / config / applyConfig 保持原始值——
        //    UI 编辑器保存链绝不能把回落私钥写回 bridge-config.json）。
        configProvider = { enrichXlogKey(configRef.get(), xlogKeyFallback) },
        connectionProvider = { connectionRef.get() },
        confirmProvider = { confirmStatusRef.get() },
        runningProvider = { running.get() },
        clock = clock,
        // ---- 在线状态接线 ----
        // 方案C：重连/恢复信号 → 守卫写入 connectionRef + publishStatus 补帧。
        connectionUpdater = ::onGatewayConnectionSignal,
        // 方案A 数据源 + 方案C 刷新：1Hz metrics tick → publishStatus（修 UI 计数冻结帧欠账）。
        onMetricsTick = ::publishStatus,
        // 方案A：「状态」指令快照 provider——晚绑定闭包（评审修正）：metrics.latest 才是真正的 1Hz 全量快照，
        //    _status.value 在两次生命周期跳变之间可能是旧帧；bundle 未装配（未运行）时回落 _status（含 running=false）。
        statusProvider = { bundleRef.get()?.metrics?.latest ?: _status.value },
        // 敏感文件排除表：宿主机密根透传（同 fallbackHandlerProvider 漏传防复发纪律）。
        sensitiveRoot = sensitiveRoot,
    )

    internal fun confirmStatusInternal(): ConfirmRuntimeStatus = confirmStatusRef.get()

    /** 测试 seam（BridgePendingConfirmationsTest：直接对 ConfirmService 灌在途确认断言窄口投影）。 */
    internal fun confirmServiceForTest(): ConfirmService = confirmService

    internal fun runningFlag(): Boolean = running.get()

    /** 测试 seam（泄漏与十步断言）：当前在途子进程数（stop 第③步 killAll 后应收敛为 0）。 */
    internal fun liveProcessCount(): Int = processRegistry.liveCount

    /** 测试 seam（泄漏断言）：supervisor 作用域树的直接子 Job 数（stop 第⑩步 runtime.cancel 后应收敛为 0）。 */
    internal fun supervisorChildCount(): Int = supervisor.job.children.count()

    companion object {
        /**
         * xlog 私钥读取层富化（internal 纯函数，三态直测缝）。
         *
         * 三态语义：
         * - 文档私钥非空 ⇒ 原样返回（桥接显式配置恒优先）；
         * - 文档私钥空 且回落非空 ⇒ copy 富化（App 形态回落 XLog 解密页私钥）；
         * - 两处都空 ⇒ 原样返回（未配置）。
         *
         * 🔴 fallback=null（CLI/测试形态）时恒等返回——行为逐字不变；
         *    stamp 指纹经此链取**有效**私钥，回落变化 ⇒ stamp 变 ⇒ 预处理重跑，无陈旧产物。
         */
        internal fun enrichXlogKey(cfg: BridgeConfig, fallback: (() -> String)?): BridgeConfig {
            if (cfg.fileMessage.xlogPrivateKey.isNotBlank()) return cfg
            val key = fallback?.invoke().orEmpty()
            if (key.isBlank()) return cfg
            return cfg.copy(fileMessage = cfg.fileMessage.copy(xlogPrivateKey = key))
        }

        /** 在途任务排空预算（方案 stop(graceMillis=5000)）。 */
        const val GRACE_MILLIS: Long = 5_000L

        /** L2 授权验证探测的单次网络超时上界（秒级——scope 未开通的拒绝通常远快于此）。 */
        const val PROBE_GRANT_TIMEOUT_MILLIS: Long = 8_000L

        /** 已读回执单页人数上限（[messageReadCount]；飞书单页上限 100）。 */
        const val READ_RECEIPT_PAGE_SIZE: Int = 100

        /** 已读回执翻页上界（[messageReadCount]；5 页 = 500 人封顶，防病态服务器分页失控）。 */
        const val READ_RECEIPT_MAX_PAGES: Int = 5

        /** 卡片流收尾上限（方案第④步 3s）。 */
        const val STREAM_CLOSE_MILLIS: Long = 3_000L

        /** 网关 disconnect/close 单步上限（方案第⑤⑥步各 5s）。 */
        const val GATEWAY_STEP_MILLIS: Long = 5_000L

        /** 🔴 落盘收尾单步上限（第⑦步 flushSinks / 第⑧步 flushNow 各 3s，与第④步同量级；超时记 warn 后继续后续步骤）。 */
        const val FLUSH_STEP_MILLIS: Long = 3_000L

        /**
         * 🔴 宿主退出时 EDT/UI 线程等待后台收尾的墙钟预算上限（[onAppExit]）。
         * 超过即返回让窗口关闭；十步 teardown 在独立**非守护**后台线程上继续跑完（数据安全步骤必然完成）。
         * 取 1500ms：足够快网关（无在途任务/无开放流）在此窗口内正常收尾，且远小于最坏情况的 EDT 冻结。
         */
        const val APP_EXIT_EDT_BUDGET_MILLIS: Long = 1_500L

    }
}
