// :lark-gateway —— 全仓唯一允许 import com.lark.oapi.* 的模块（AGENTS.md 第 8 条 / SDK 隔离三重强制）。
// 本文件：MessageGateway 的唯一 SDK 实现 SdkLarkGateway（gateway-contract.md §3）。另两个允许 import SDK 的文件：
//   SdkCardStreamPort.kt（顶层协作类 SdkCardStreamPort 把 cardkit/im 响应翻译成 CardCallOutcome）、RegisterAppFlow.kt（gateway-contract.md §8）。
// 🔴 F 组硬门禁：本文件含 4 个类级 @Volatile var（channel/botOpenId/botName/regionHint），
//    故全文禁止出现任何 object 声明（companion / data / 匿名 / 顶层；object 是进程级单例，其可变状态会被多个网关实例共享串改）——常量放 Tuning.kt，工厂用私有函数，SAM 用 lambda，
//    工具函数用顶层声明（同包 per-concern 单文件：SdkCardStreamPort.kt / QuotedContentParsers.kt / ContactDiagnostics.kt /
//    ConnectDiagnostics.kt / DownloadRangeParsers.kt / HistoryContentParsers.kt）。
//    既有 4 个类级 @Volatile var 合法；新增可变状态一律 AtomicReference。
// 🔴 网关内绝不出现 exitProcess / System.exit（库模块调进程级 API 会让单测终止 JVM）。
// 🔴 SDK 回调线程内只允许 capture + trySend（不日志 / 不 JSON / 不挂起 / 不 launch / 不加锁——一旦阻塞或加锁，SDK 分发线程卡死、整条入站停摆）。
// 网关不自建 net 池，由引擎注入；本文件零 Dispatchers.IO / 零 limitedParallelism（唯一例外在 RegisterAppFlow.kt）。
package com.jiaoay.lark.gateway

import com.jiaoay.lark.gateway.internal.CardActionRecord
import com.jiaoay.lark.gateway.internal.NormalizedMessageRecord
import com.jiaoay.lark.ws.WsInboundEvent
import com.lark.oapi.Client
import com.lark.oapi.core.Config
import com.lark.oapi.channel.LarkChannel
import com.lark.oapi.channel.LarkChannelFactory
import com.lark.oapi.channel.config.LarkChannelOptions
import com.lark.oapi.channel.exception.LarkChannelException
import com.lark.oapi.channel.model.SendInput
import com.lark.oapi.channel.model.SendOptions
import com.lark.oapi.core.Transport
import com.lark.oapi.core.enums.AppType
import com.lark.oapi.core.enums.BaseUrlEnum
import com.lark.oapi.core.request.RequestOptions
import com.lark.oapi.core.token.AccessTokenType
import com.lark.oapi.core.response.BaseResponse
import com.lark.oapi.service.contact.v3.model.GetUserReq
import com.lark.oapi.service.docx.v1.model.RawContentDocumentReq
import com.lark.oapi.service.im.v1.model.CreateChatMembersReq
import com.lark.oapi.service.im.v1.model.CreateChatMembersReqBody
import com.lark.oapi.service.im.v1.model.CreateChatReq
import com.lark.oapi.service.im.v1.model.CreateChatReqBody
import com.lark.oapi.service.im.v1.model.CreateFileReq
import com.lark.oapi.service.im.v1.model.CreateFileReqBody
import com.lark.oapi.service.im.v1.model.CreateImageReq
import com.lark.oapi.service.im.v1.model.CreateImageReqBody
import com.lark.oapi.service.im.v1.model.CreateMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody
import com.lark.oapi.service.im.v1.model.DeleteMessageReq
import com.lark.oapi.service.im.v1.model.GetChatMembersReq
import com.lark.oapi.service.im.v1.model.GetChatReq
import com.lark.oapi.service.im.v1.model.GetMessageReq
import com.lark.oapi.service.im.v1.model.ListMessageReactionReq
import com.lark.oapi.service.im.v1.model.ListMessageReq
import com.lark.oapi.service.im.v1.model.PatchMessageReq
import com.lark.oapi.service.im.v1.model.PatchMessageReqBody
import com.lark.oapi.service.im.v1.model.ReadUsersMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq
import com.lark.oapi.service.wiki.v2.model.GetNodeSpaceReq
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * [MessageGateway] 的唯一 SDK 实现（gateway-contract.md §3.1）。SDK 类型绝不泄漏到任何契约签名（隔离三重强制之三：可测性）。
 *
 * @param netDispatcher 由引擎注入 `BridgeDispatchers.net`（`IO.limitedParallelism(8,"bridge-net")`）；所有阻塞 SDK 调用包在此。
 * @param scope 由引擎注入 `BridgeRuntime` 的 childJob 作用域；网关内部再派生私有 [gatewayScope]（gateway-contract.md §9.2）。
 * @param appIdLockProbe 同 appId 单实例互斥探针，默认 null。
 * @param settings 由 [LarkPolicyMapper] 产出 + 引擎注入 allowedFileDirs（gateway-contract.md §5 / gateway-contract.md §15.2 M-5）。
 * @param clock 可注入时钟，供虚拟时间测试。
 * @param credentialOverride **测试专用缝**：生产恒为 null（凭据由 [connect] 留存），
 *   非 null 时 [doDownload] 直接取它而**不必真连一次**。存在理由：下载改走 `Transport.send` 后需要自建 `Config`，
 *   而 `connect()` 需要真实 SDK 长连接（无凭据环境跑不了）——没有这个缝，下载路径就又一次「零真实覆盖」，
 *   正是本次真机 `DownloadFailed` 得以藏身的原因。
 *   它**不放宽任何生产行为**：凭据来源仍然是 `LarkCredential`，且 `Config` 的构造与生产分支逐字相同。
 * @param httpTransportOverride **测试专用缝**（同上）：非 null 时作为自建 `Config` 的 HTTP 传输（注入假 transport
 *   以驱动 200 / 非 200 两条真实分支）。生产恒为 null ⇒ 走 SDK 默认 `OkHttpTransport`。
 * @param wsInboundFactory **必传**：入站事件流走自研 `:lark-ws` 传输——
 *   SDK 侧 LarkChannel 恒定 `transport("webhook")`（🔴 硬约束：不切则同 appId 出现 SDK ws + 自研 ws
 *   两条长连接，服务端互踢（同 appId 单实例互斥探针防的形态）；webhook 模式下 SDK 的 rawWsClient 为 null，
 *   LarkChannel 退化为纯出站/identity facade）；出站（send/上传/下载/补查/流式卡）与 identity 仍走
 *   LarkChannel。生产实装 [WsClientTransportFactory]，测试注入 Fake 工厂。
 *   工厂语义：每次 connect 新建传输实例（WsClient.close 是终态，连接不跨 connect 复用）。
 */
class SdkLarkGateway(
    private val netDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val wsInboundFactory: WsInboundTransportFactory,
    private val appIdLockProbe: (() -> Boolean)? = null,
    private val settings: GatewaySdkSettings = GatewaySdkSettings.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val credentialOverride: LarkCredential? = null,
    private val httpTransportOverride: com.lark.oapi.core.httpclient.IHttpTransport? = null,
) : MessageGateway {

    private val log = LoggerFactory.getLogger(SdkLarkGateway::class.java)

    /**
     * settings 可变承载：构造参数 [settings] 固化进 AtomicReference，
     * [updateDownloadSettings] 热更时原子替换——**download 消费点现读引用**（`settingsRef.get()`），
     * 不直读构造快照（`fileMessage.maxFileSizeBytes` 标 HOT 但 applyConfig 只下推 policy/permits、
     * 不下推 settings——无此引用则热更形同虚设，网关仍按构造期旧值拒下载）。
     * 只热更 download 域字段（其余字段 connect 时与 policy 合并使用，改它们本就要求重启语义）。
     */
    private val settingsRef = java.util.concurrent.atomic.AtomicReference(settings)

    /** download 路径的 settings 现读（热更后立即生效；其余路径维持构造快照语义）。 */
    private val dlSettings: GatewaySdkSettings get() = settingsRef.get()

    /** 网关私有子作用域（gateway-contract.md §9.2）：SupervisorJob(parent=注入 scope 的 Job) + Dispatchers.Default，只跑非阻塞逻辑。 */
    private val gatewayScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)

    private val _connectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
    override val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    // 入站 512 / 信号 256，DROP_OLDEST。溢出 ⇒ GatewaySignal.Dropped（所有丢弃点必须可观测）。
    private val inboundChannel = Channel<InboundMessage>(Tuning.inboundChannelCapacity, BufferOverflow.DROP_OLDEST)
    private val signalChannel = Channel<GatewaySignal>(Tuning.signalChannelCapacity, BufferOverflow.DROP_OLDEST)

    // 入站占用计数：DROP_OLDEST 的 trySend 恒成功，无法从返回值判丢弃 ⇒ 用占用计数在 offerInbound 里推断（gateway-contract.md §15.2 M-16）。
    private val inboundOccupancy = AtomicInteger(0)
    private val inboundDropTotal = AtomicLong(0)

    @Volatile private var channel: LarkChannel? = null
    @Volatile private var botOpenId: String = ""
    @Volatile private var botName: String? = null
    @Volatile private var regionHint: String = "feishu"   // registerApp 的 region 来源（gateway-contract.md §8.3 规格 6）

    /**
     * 本次连接的凭据：[doDownload] 走 `Transport.send` 时需要自建 `Config`，
     * 而 `Client.config` 是私有且**无 getter** ⇒ 凭据必须在此留存。
     *
     * 为什么不用 `@Volatile var`：本类既有 4 个 `@Volatile var` 是 F 组硬门禁基线，新增状态一律走
     * `AtomicReference`（与 `currentPolicy`/`closed` 同纪律），避免扩大门禁面。
     *
     * 🔴 生命周期：`connect()` 写入、`close()` 清空（防 appSecret 长驻内存）。`LarkCredential` 已覆写
     * `toString()` 为掩码形态（见 `GatewayModel.kt`），任何日志/异常路径都不会泄漏 secret。
     */
    private val credentialRef = AtomicReference<LarkCredential?>(null)

    private val currentPolicy = AtomicReference(LarkPolicy())
    private var connectJob: Job? = null

    /**
     * 本次 connect 的自研入站传输实例。null = 尚未 connect 或已 close/断连（SDK webhook
     * facade 无入站连接）；非 null = 入站走 :lark-ws（工厂每次 connect 新建——WsClient.close
     * 是终态，不跨 connect 复用）。
     * close()/断连时关闭并清空。新增可变状态一律 AtomicReference（F 组纪律，零 @Volatile var 新增）。
     */
    private val wsInboundTransportRef = AtomicReference<WsInboundTransport?>(null)

    private val closed = AtomicBoolean(false)

    private val inflightDownloads: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val downloadSemaphore = Semaphore(settings.downloadSingleFlight)  // 并发下载上限（默认 2）
    private val openSinks: MutableSet<LarkCardStreamSink> = ConcurrentHashMap.newKeySet()

    /** 告警限流标志：contact scope 缺失/权限不足只 WARN 一次（每进程生命周期）。类级实例字段，非 object/companion。 */
    private val contactScopeWarned = AtomicBoolean(false)

    // 自定义 flow 而非 receiveAsFlow：接收时递减占用计数，与 offerInbound 的递增配对（丢弃可观测）。
    override val inbound: Flow<InboundMessage> = flow {
        for (m in inboundChannel) {
            inboundOccupancy.decrementAndGet()
            emit(m)
        }
    }
    override val signals: Flow<GatewaySignal> = signalChannel.receiveAsFlow()

    // ==================== 第 4 成员：connect（gateway-contract.md §3.6 六步）====================

    override suspend fun connect(cred: LarkCredential, policy: LarkPolicy): GatewayConnectResult {
        // 步 0：单实例互斥探针（不触碰 SDK）
        if (appIdLockProbe?.invoke() == true) {
            _connectionState.value = GatewayConnectionState.BlockedByPeerInstance
            return GatewayConnectResult.BlockedByPeerInstance
        }
        // 步 1
        _connectionState.value = GatewayConnectionState.Connecting
        regionHint = cred.region
        currentPolicy.set(policy)
        connectJob?.cancel()   // 防重连累积
        return withContext(netDispatcher) {
            // 步 2：把「引擎注入的 settings」与「本次 policy 的 5 字段」合并后翻译成 SDK 选项（gateway-contract.md §15.2 M-14）。
            // 🔴 入站恒自研：SDK 选项恒定 transport("webhook")——SDK 侧不再建 ws 长连接
            //    （同 appId 双长连接会被服务端互踢）；webhook 模式下 rawWsClient==null，
            //    LarkChannel 退化为纯出站/identity facade（入站事件由 :lark-ws 承载）。
            val ch = try {
                val merged = settings.copy(
                    groupAllowlist = policy.groupAllowlist,
                    dmMode = policy.dmMode,
                    dmAllowlist = policy.dmAllowlist,
                    requireMention = policy.requireMention,
                    respondToMentionAll = policy.respondToMentionAll,
                )
                LarkChannelFactory.createLarkChannel(buildOptions(cred, merged, inboundWebhook = true))
            } catch (t: Throwable) {
                val msg = t.message ?: "buildOptions/createLarkChannel failed"
                _connectionState.value = GatewayConnectionState.Disconnected(GatewayErrorCode.ConnectFatal, msg)
                return@withContext GatewayConnectResult.Failed(GatewayErrorCode.ConnectFatal, larkMsg = msg, retryable = false)
            }
            // 步 3：🔴 connectSync()（同步，跑在 net 池），不用 connect()（supplyAsync 无 Executor ⇒ commonPool）
            //    webhook 模式下 SDK connectSync 只承担 identity fetch（纯 HTTP）；入站传输在步 3.5 另行启动。
            var connectError: Throwable? = null
            val identity = withTimeoutOrNull(Tuning.connectTimeoutMs) {
                runCatching { ch.connectSync() }.getOrElse { connectError = it; null }
            }
            if (identity == null) {
                // 失败：写排障四项。webhook 模式 rawWsClient 恒 null，无 ws 线程池可泄漏（反射路径已删除）
                runCatching { ch.disconnectSync() }
                val err = connectError
                val code = if (err != null) mapThrowable(err) else GatewayErrorCode.ConnectRetryable
                val msg = err?.message ?: CONNECT_TROUBLESHOOTING
                _connectionState.value = GatewayConnectionState.Disconnected(code, msg)
                return@withContext GatewayConnectResult.Failed(code, larkMsg = err?.message, retryable = code == GatewayErrorCode.ConnectRetryable)
            }
            // 步 3.5：入站传输启动 + 首连超时判定。WsClient 首连失败不立刻终态（转重连循环，
            //    与 SDK start 一致）⇒ 超时判定职责在网关侧：withTimeout 内 awaitConnected，超时/终态即 close
            //    本实例（工厂语义：close 是终态，下次 connect 由工厂新建）+ 断开 facade + 判定 connect 失败。
            val wsTransport = wsInboundFactory.create(
                credential = cred,
                eventHandler = ::handleWsInboundEvent,
                stateListener = wsStateListener(),
                domain = domainOf(cred.region),
            )
            wsTransport.start()
            val wsOk = withTimeoutOrNull(Tuning.connectTimeoutMs) { wsTransport.awaitConnected(Tuning.connectTimeoutMs) } == true
            if (!wsOk) {
                runCatching { wsTransport.close() }
                runCatching { ch.disconnectSync() }
                _connectionState.value = GatewayConnectionState.Disconnected(GatewayErrorCode.ConnectRetryable, CONNECT_TROUBLESHOOTING)
                return@withContext GatewayConnectResult.Failed(GatewayErrorCode.ConnectRetryable, larkMsg = null, retryable = true)
            }
            // 步 4：保存 identity
            channel = ch
            botOpenId = identity.openId
            botName = identity.name
            wsInboundTransportRef.set(wsTransport)
            // 留存凭据供 [doDownload] 自建 `Transport.send` 的 Config（`Client.config` 私有且无 getter）。
            //    🔴 **必须在连接成功后**（不是步 1）：连接失败/未连上时凭据非空会让 `downloadResource`
            //    真的发出一次带凭据的 HTTP 请求，而网关对外状态还是 Disconnected。
            credentialRef.set(cred)
            _connectionState.value = GatewayConnectionState.Connected(botOpenId, botName)
            // 步 5：connect 成功后才挂断连（入站回调在 :lark-ws 传输内，无 SDK handler 注册）
            attachDisconnectOnCancel(ch)
            // 步 6
            GatewayConnectResult.Connected(botOpenId, botName)
        }
    }

    // ==================== 第 5 成员：disconnect（gateway-contract.md §3.8，不反射 shutdown）====================

    override suspend fun disconnect() {
        withContext(netDispatcher) {
            val ch = channel ?: return@withContext
            withTimeoutOrNull(Tuning.disconnectTimeoutMs) { runCatching { ch.disconnectSync() } }
                ?: run { log.warn("disconnectSync 超时 {}ms", Tuning.disconnectTimeoutMs) }
            // 自研 ws 分支：入站传输随 disconnect 一并关闭（close 是终态——下次 connect 由工厂新建）
            runCatching { wsInboundTransportRef.getAndSet(null)?.close() }
            _connectionState.value = GatewayConnectionState.Disconnected(GatewayErrorCode.Unknown, "client requested")
        }
    }

    // ==================== 第 6 成员：updatePolicy（gateway-contract.md §5.3，全量覆盖那 5 字段而非增量 merge）====================

    override suspend fun updatePolicy(policy: LarkPolicy) {
        // 🔴 先落当前完整 policy 再整体替换 SDK PolicyConfig——绝不构造部分填充的 PolicyConfig（否则清空 groupAllowlist）。
        currentPolicy.set(policy)
        withContext(netDispatcher) {
            val ch = channel ?: return@withContext
            runCatching {
                ch.updatePolicy(
                    policyConfig(
                        policy.groupAllowlist, policy.dmMode, policy.dmAllowlist,
                        policy.requireMention, policy.respondToMentionAll,
                    )
                )
            }.onFailure { log.warn("updatePolicy 失败：{}", it.toString()) }
        }
    }

    // ==================== updateDownloadSettings ====================

    /**
     * 热更 download 域 settings（原子替换 [settingsRef]——download 消费点现读 [dlSettings] 即刻生效）。
     *
     * 必须热更的背景：`fileMessage.maxFileSizeBytes` 在 BridgeFields 标 ApplyMode.HOT，
     *    但网关 settings 在 `BridgeGateways.resolve` 构造期固化、applyConfig 的 HOT 分支只下推
     *    policy/permits——无此热更则改上限保存后网关仍按构造期旧值拒下载。
     * 只热更 download 域字段：settings 的其余字段（allowedFileDirs 等）在 connect 时与 policy 合并使用，
     *    热更它们会与「在途通道的既有 options」产生分裂——改它们本就是 RESTART 语义。
     * 非挂起消费即完成（AtomicReference.set），无网络调用、不需要 channel 在位——未连接时调用也安全
     *    （下次 connect 的 LarkChannel 用构造 settings 建通道，但 download 走 raw client 不经通道，
     *    download 消费点恒读 settingsRef ⇒ 未连接期热更同样生效）。
     */
    override fun updateDownloadSettings(downloadMaxBytes: Long) {
        settingsRef.set(settingsRef.get().copy(downloadMaxBytes = downloadMaxBytes))
        log.info("download settings 已热更：downloadMaxBytes={}（原子替换，即刻生效）", downloadMaxBytes)
    }

    // ==================== 第 7 成员：send（gateway-contract.md §3.9，不自动重试）====================

    override suspend fun send(target: LarkTarget, out: Outbound): SendResult = withContext(netDispatcher) {
        val ch = channel ?: return@withContext SendResult(false, null, GatewayErrorCode.NotConnected)
        // 🔴 FILE **不得**走 ch.send(SendInput.file(...))——SDK 语义里
        //    SendInput.file(source, fileName) 的首参是「待上传的本地路径/URL」，不是 file_key（OutboundSender.send
        //    → sendUploadedMedia → MediaUploader.toFile 对非 http 值执行 Paths.get(...).toRealPath()，file_key
        //    必抛 UPLOAD_FAILED）。file_key 发送只有一条正确路径：raw client 直发 msg_type="file" +
        //    content={"file_key":…}（同 sendCardByReference 的 raw 模式；飞书侧 file_key 由第一步 uploadFile 真实上传获得）。
        if (out.kind == OutboundKind.FILE) {
            return@withContext sendFileByKey(target, out)
        }
        if (out.kind == OutboundKind.IMAGE) {
            return@withContext sendImageByKey(target, out)
        }
        val opts = SendOptions.newBuilder().apply {
            out.replyToMessageId?.let { replyTo(it) }
            if (out.replyInThread) replyInThread(true)
            if (out.mentionOpenIds.isNotEmpty()) mentions(out.mentionOpenIds)
        }.build()
        // gateway-contract.md §15.2 M-10：gateway-contract.md §3.9 引用的 LarkOutboundMapper.toPayload 不存在 ⇒ 直接按 kind 构造 SendInput（text/markdown/card）。
        // SendInput.card 签名是 Map<String,Object>（Kotlin Map<String,Any>），toCardJson 产出 Map<String,Any?> ⇒ 显式 cast（值恒非空，安全）。
        @Suppress("UNCHECKED_CAST")
        val input = when (out.kind) {
            OutboundKind.TEXT -> SendInput.text(out.text.orEmpty())
            OutboundKind.MARKDOWN -> SendInput.markdown(out.text.orEmpty())
            OutboundKind.CARD -> SendInput.card(LarkOutboundMapper.toCardJson(out.card!!) as Map<String, Any>)  // 非流式卡；流式卡走 gateway-contract.md §4 raw client
            // FILE/IMAGE 已在方法入口拦截走 raw client 直发——此处永不可达（编译器穷尽要求列出）
            OutboundKind.FILE -> throw IllegalStateException("unreachable: FILE handled by sendFileByKey")
            OutboundKind.IMAGE -> throw IllegalStateException("unreachable: IMAGE handled by sendImageByKey")
        }
        // 🔴 await() 是挂起调用，不能放进非挂起的 runCatching ⇒ 用 try/catch。send 不重试（非幂等——自动重试即同一条消息双投递）。
        try {
            val r = ch.send(target.receiveId, input, opts).await()
            SendResult(true, r.messageId, chunkMessageIds = r.chunkIds ?: emptyList())
        } catch (t: Throwable) {
            val code = mapThrowable(t)
            offerSignal(GatewaySignal.SendFailed(code, t.message ?: t.toString(), target.receiveId))
            SendResult(false, null, code, null, t.message, null)
        }
    }

    /**
     * FILE 出站：raw client `im().v1().message().create` 直发
     * msg_type="file" + content={"file_key":…}——file_key 已由 [uploadFile] 第一步真实上传获得，
     * 本调用零文件 IO。与 [sendCardByReference] 同款 raw 模式（msgType/content 显式构造、逐次查响应码）。
     */
    private suspend fun sendFileByKey(target: LarkTarget, out: Outbound): SendResult {
        val client = rawClient() ?: return SendResult(false, null, GatewayErrorCode.NotConnected)
        // 引用锚定：replyToMessageId 非空 ⇒ 走 reply endpoint
        //    （飞书回复端点支持 msg_type="file" + content={"file_key":…}，SDK javadoc 实证）；为空保持 create。
        val anchor = out.replyToMessageId
        if (anchor != null) {
            val replied = replyFileByKey(client, target, out, anchor)
            if (replied != null) return replied
            // 回复目标消失类失败（230020 撤回/230017 删除/404）⇒ 降级 create 重发（镜像 SDK sendOneWithFallback，产物不丢）；
            // 其余失败（权限/结构错误）原样返回。
            val fallthrough = sendFileByKeyCreate(client, target, out)
            if (!fallthrough.ok) {
                offerSignal(GatewaySignal.SendFailed(fallthrough.error ?: GatewayErrorCode.SendFailed, "file reply anchor=$anchor fallthrough create also failed", target.receiveId))
            }
            return fallthrough
        }
        return sendFileByKeyCreate(client, target, out)
    }

    /** reply endpoint 直发文件消息；返回 null 表示「应降级 create 重发」，非 null 为最终结果。 */
    private suspend fun replyFileByKey(client: Client, target: LarkTarget, out: Outbound, anchor: String): SendResult? =
        try {
            val body = ReplyMessageReqBody.newBuilder()
                .msgType("file")
                .content(LarkOutboundMapper.contentJsonOf(out))
                .build()
            val req = ReplyMessageReq.newBuilder()
                .messageId(anchor)
                .replyMessageReqBody(body)
                .build()
            val resp = client.im().v1().message().reply(req)
            if (resp.success()) {
                SendResult(true, resp.data?.messageId)
            } else {
                val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                offerSignal(GatewaySignal.SendFailed(code, "file reply code=${resp.code} msg=${resp.msg} anchor=$anchor", target.receiveId))
                // 回复目标消失（230020 已撤回 / 230017 不存在 / http 404）⇒ 降级 create；其余失败终态返回
                if (resp.code == 230020 || resp.code == 230017 || resp.rawResponse?.statusCode == 404) null
                else SendResult(false, null, code, null, resp.msg)
            }
        } catch (t: Throwable) {
            val code = mapThrowable(t)
            offerSignal(GatewaySignal.SendFailed(code, "file reply exception anchor=$anchor: ${t.message}", target.receiveId))
            SendResult(false, null, code, null, t.message, null)
        }

    private suspend fun sendFileByKeyCreate(client: Client, target: LarkTarget, out: Outbound): SendResult =
        try {
            val body = CreateMessageReqBody.newBuilder()
                .receiveId(target.receiveId)
                .msgType("file")
                .content(LarkOutboundMapper.contentJsonOf(out))
                // 🔴 idempotencyKey 非空 ⇒ 喂 SDK uuid（相同 uuid 1 小时内
                //    至多成功发一条）——「memo 复用发送失败 ⇒ 回退重发」两连发防双投递。
                .apply { out.idempotencyKey?.let { uuid(it) } }
                .build()
            val req = CreateMessageReq.newBuilder()
                .receiveIdType(target.receiveIdType)
                .createMessageReqBody(body)
                .build()
            val resp = client.im().v1().message().create(req)
            if (resp.success()) {
                SendResult(true, resp.data?.messageId)
            } else {
                val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                offerSignal(GatewaySignal.SendFailed(code, "file send code=${resp.code} msg=${resp.msg}", target.receiveId))
                // 飞书业务码透传 larkCode 位——memo 回退决策按原始码观测。
                SendResult(false, null, code, resp.code, resp.msg)
            }
        } catch (t: Throwable) {
            val code = mapThrowable(t)
            offerSignal(GatewaySignal.SendFailed(code, t.message ?: t.toString(), target.receiveId))
            SendResult(false, null, code, null, t.message, null)
        }

    /**
     * IMAGE 出站：raw client `im().v1().message().create` 直发
     * msg_type="image" + content={"image_key":…}——image_key 已由 [uploadImage] 第一步真实上传获得，
     * 本调用零文件 IO。与 [sendFileByKey] 同款 raw 模式（msgType/content 显式构造、逐次查响应码、
     * rawClient 空即 NotConnected），但 image 消息场景更简单（不涉及锚定降级的产物保全），create 一条路径即可。
     */
    private suspend fun sendImageByKey(target: LarkTarget, out: Outbound): SendResult {
        val client = rawClient() ?: return SendResult(false, null, GatewayErrorCode.NotConnected)
        return try {
            val body = CreateMessageReqBody.newBuilder()
                .receiveId(target.receiveId)
                .msgType("image")
                .content(LarkOutboundMapper.contentJsonOf(out))
                .apply { out.idempotencyKey?.let { uuid(it) } }
                .build()
            val req = CreateMessageReq.newBuilder()
                .receiveIdType(target.receiveIdType)
                .createMessageReqBody(body)
                .build()
            val resp = client.im().v1().message().create(req)
            if (resp.success()) {
                SendResult(true, resp.data?.messageId)
            } else {
                val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                if (code == GatewayErrorCode.RateLimited) {
                    offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, target.receiveId))
                }
                offerSignal(GatewaySignal.SendFailed(code, "image send code=${resp.code} msg=${resp.msg}", target.receiveId))
                SendResult(false, null, code, resp.code, resp.msg)
            }
        } catch (t: Throwable) {
            val code = mapThrowable(t)
            offerSignal(GatewaySignal.SendFailed(code, t.message ?: t.toString(), target.receiveId))
            SendResult(false, null, code, null, t.message, null)
        }
    }

    // ==================== 第 8 成员：openCardStream（gateway-contract.md §4，非挂起同步返回 sink）====================

    override fun openCardStream(target: LarkTarget, initial: CardSpec): CardStreamSink {
        // cardkit 调用收在 SdkCardStreamPort 后面，使三条降级分支（patch→content→updateCard）只改实现不改契约。
        val port = SdkCardStreamPort(clientProvider = { channel?.rawClient }, netDispatcher = netDispatcher)
        val sink = LarkCardStreamSink(
            port = port,
            target = target,
            initial = initial,
            netDispatcher = netDispatcher,
            scope = gatewayScope,               // 网关私有 scope 的子作用域，close() 时随 gatewayScope.cancel() 一并取消
            onSignal = { offerSignal(it) },
            clock = clock,
        )
        openSinks.add(sink)
        sink.job.invokeOnCompletion { openSinks.remove(sink) }
        return sink
    }

    // ==================== 第 9 成员：downloadResource（三重防护）====================

    override suspend fun downloadResource(messageId: String, fileKey: String, dest: Path): DownloadResult {
        // 防护 ①：上限钳制（downloadMaxBytes<=0 直接拒）
        if (dlSettings.downloadMaxBytes <= 0L) {
            return DownloadResult(false, GatewayErrorCode.DownloadTooLarge, null, "downloadMaxBytes<=0")
        }
        // 防护 ②：并发单飞（同 messageId+fileKey 只允许一个在途）
        val key = "$messageId|$fileKey"
        if (!inflightDownloads.add(key)) {
            offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.DownloadFailed, "duplicate inflight: $key"))
            return DownloadResult(false, GatewayErrorCode.DownloadFailed, null, "duplicate inflight: $key")
        }
        return try {
            // 🔴 Semaphore(downloadSingleFlight=2) 并发上限（堆峰值 = 并发下载数 × 单文件大小）。
            downloadSemaphore.withPermit { doDownload(messageId, fileKey, dest, key) }
        } finally {
            inflightDownloads.remove(key)
        }
    }

    /**
     * 单个资源的下载实现（Range 分片循环 + 200 降级保底 + 临时文件 → 原子移动 → 失败清理）。
     *
     * ## 为什么**不用** SDK 的 `client.im().v1().messageResource().get(req)`
     * 该 API 的非 200 分支经 Gson 反序列化 `BaseResponse<ByteArrayOutputStream>`，在 JDK 16+
     * 默认强封装下 `setAccessible` 必炸（JsonIOException），**把真实 HTTP 状态码彻底吞掉**；
     * 且 `GetFileResp`/`GetImageResp` 同款形态，换 SDK API 绕不开（oapi-sdk 2.8.5 源码核实）。
     *
     * ## 直连方案
     * `com.lark.oapi.core.Transport.send` 是 public，经 `GetMessageResourceReq` 的 `@Path`/`@Query`
     * 注解完成拼装。token 走 SDK 的全局静态 `GlobalTokenManager` + 传入 config——自建 Config
     * 与 `LarkChannel` 命中**同一份 token 缓存**（进程级单例），不产生额外 token 请求；跨片
     * token 过期由缓存 miss 自动刷新。
     *
     * ## Range 分片
     * 飞书官方 message-resource 文档支持 `Range: bytes=<start>-<end>` 分片下载（单片 ≤ 32MB），
     * 分片本地合并即完整文件——**突破平台 100MB 完整下载限**。实现要点：
     * - 请求头注入走 `RequestOptions.setHeaders(Map)`；🔴 SDK 会 **mutate 外部传入的 map**
     *   （往里 put Authorization）⇒ **每片必须新建 headers map**。
     * - 分片成功响应 = HTTP 206 + `Content-Range: bytes <start>-<end>/<total>`；**首片即得 total**
     *   ⇒ `downloadMaxBytes` 校验从「下载后拒绝」升级为「首片预检」（超限零字节落盘即拒）。
     * - 200 降级保底：服务器忽略 Range 返回 200 整文件 ⇒ 收编既有「下载后拒绝 + 一次落盘」路径，
     *   行为退回现状——既有测试全兼容。
     * - 响应头键大小写不保证（OkHttp `toMultimap` / HTTP/2）⇒ `headerValueOf` 大小写不敏感取值。
     * - 每片头部 `ensureActive()`（取消观察点，配对 catch 内 CancellationException rethrow——不吞 CE 纪律）；
     *   片数上限 `downloadMaxChunks` 防「1 字节片」病态服务器；每片累计 `written` 兜底防服务器谎报 total。
     * - **内存上界 = 并发下载数 × 单片大小**（分片前 = × 单文件大小）——分片的核心收益；
     *   200 降级路径仍是单文件全量（降级形态下平台限也还在，受 downloadMaxBytes 兜底）。
     *
     * 覆盖：`SdkDownloadTransportTest`（注入假 `IHttpTransport`，无凭据驱动 206 分片 / 200 降级 / 非 200 三条真实分支）。
     *
     * 既有防护逐条保留（不得弱化）：上限钳制 / 并发单飞 / 临时文件 + `ATOMIC_MOVE` + 失败清理 /
     * `createDirectories` 必须在写文件之前。
     *
     * 返回值是 [DownloadResult]（非 Boolean）——调用方（resolver）
     *    需要 error/larkCode 渲染用户可读失败原因（如超限「重试无用」）。**双轨**：所有
     *    offerSignal 原样保留（信号服务运维日志 + RateGovernor），本返回值服务调用方决策。
     */
    private suspend fun doDownload(messageId: String, fileKey: String, dest: Path, key: String): DownloadResult =
        withContext(netDispatcher) {
            val tmp = dest.resolveSibling(dest.fileName.toString() + ".part-" + clock())
            try {
                val cred = credentialOverride ?: credentialRef.get()
                if (cred == null) {
                    offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.DownloadFailed, "not connected: $key"))
                    return@withContext DownloadResult(false, GatewayErrorCode.DownloadFailed, null, "not connected: $key")
                }
                val injected = httpTransportOverride
                // 自建 Config：Client.config 私有且无 getter，故凭据由 connect() 留存（credentialRef）。
                //    与现状**严格一致**：LarkChannelOptions 未设 domain ⇒ ChannelClientFactory 落 BaseUrlEnum.FeiShu；
                //    不要按 cred.region 切 LarkSuite——region 当前根本没接进 baseUrl（regionHint 只服务 registerApp）。
                val cfg = Config().apply {
                    appId = cred.appId
                    appSecret = cred.appSecret
                    baseUrl = BaseUrlEnum.FeiShu.url
                    appType = AppType.SELF_BUILT
                    // 测试专用缝：非 null 时注入假 transport；生产恒 null ⇒ SDK 用默认 OkHttpTransport。
                    injected?.let { setHttpTransport(it) }
                    // 测试专用缝（同上）：假 transport 无法应答 token 往返——`ReqTranslator.getToken` 在缓存
                    //    未命中时会**再打一次** `POST /open-apis/auth/v3/tenant_access_token/internal`，那次也走
                    //    同一个注入的 transport（首跑实测：下载请求被 token 请求顶掉，信号退化成了
                    //    `exception=JsonSyntaxException` 的兜底分支）。
                    //    关缓存 + 手动 token ⇒ `determineTokenType` 与 `getToken` 都直接返回手动值，
                    //    **假 transport 恰好只被下载请求调用**（分片循环下 = 每片恰好一次，不混入 token 请求）。
                    //    生产恒 null ⇒ 走真实 token 缓存（LocalCache 进程级单例，与 `LarkChannel` 共用同一份）。
                    if (injected != null) setDisableTokenCache(true)
                }
                // req 无状态（@Path/@Query 注解驱动），跨片复用同一实例。
                val req = GetMessageResourceReq.newBuilder()
                    .messageId(messageId).fileKey(fileKey).type(dlSettings.downloadResourceType).build()
                // 分片循环状态全为函数局部变量（非类状态，F 组安全）。
                var offset = 0L
                var total: Long? = null
                var written = 0L
                var chunks = 0
                // 🔴 失败统一收口：失败点只置 failure + break（信号在置值点发），循环外统一清理 tmp——
                //    分片中途失败时 tmp 可能已写入若干片，散布 early return 会漏清理留 .part- 残渣。
                var failure: DownloadResult? = null
                while (failure == null) {
                    // 取消观察点：每片发送前检查（配对 catch 内 CE rethrow——不吞 CE 纪律）。
                    coroutineContext.ensureActive()
                    // 片数上限：防「1 字节片」病态服务器空转（诚实分片数 = maxBytes/chunkBytes）。
                    if (++chunks > dlSettings.downloadMaxChunks) {
                        offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.DownloadFailed, "download chunk limit ${dlSettings.downloadMaxChunks} exceeded: $key"))
                        failure = DownloadResult(
                            false, GatewayErrorCode.DownloadFailed, null,
                            "chunk limit ${dlSettings.downloadMaxChunks} exceeded (offset=$offset total=$total)",
                        )
                        break
                    }
                    val end = if (total != null) {
                        minOf(offset + dlSettings.downloadChunkBytes - 1, total - 1)
                    } else {
                        offset + dlSettings.downloadChunkBytes - 1
                    }
                    // 🔴 每片必须新建 RequestOptions 与 headers map：ReqTranslator 会 mutate 外部传入 map
                    //    （往里 put Authorization）——复用实例会被前片污染 + 不可变 map 会抛 UnsupportedOperationException。
                    val reqOptions = RequestOptions().apply {
                        setSupportDownLoad(true)
                        // 🔴 SDK 笔误规避：ReqTranslator 把 RawRequest.supportDownLoad 写成读
                        //    requestOptions.isSupportUpload()——只设 download 不设 upload 则恒 false。两个都设。
                        setSupportUpload(true)
                        // 测试专用缝：手动 token 让假 transport 只被下载请求调用（见 Config 段注释）。
                        if (injected != null) setTenantAccessToken(Tuning.testAccessToken)
                        // 🔴 mutableMapOf：ReqTranslator 会往此 map put Authorization——不可变 map 会抛
                        //    UnsupportedOperationException（mapOf 单键 = Collections.SingletonMap）。
                        setHeaders(mutableMapOf("Range" to listOf("bytes=$offset-$end")))
                    }
                    val raw = Transport.send(
                        cfg,
                        reqOptions,
                        "GET",
                        Tuning.messageResourcePath,
                        setOf(AccessTokenType.Tenant, AccessTokenType.User),
                        req,
                    )
                    when (raw.statusCode) {
                        Tuning.httpOk -> {
                            // 200 降级：服务器忽略 Range 返回整文件——收编既有「下载后拒绝 + 一次落盘」路径。
                            // （首片请求恒带 Range 头；服务器支持 Range 时不会进此分支。自带落位收口，无残留。）
                            val bytes = raw.body?.size?.toLong() ?: 0L
                            // 防护 ①'：下载后拒绝阈值（与分片前语义严格一致：超限不写盘，larkCode=null 我方限）。
                            if (bytes > dlSettings.downloadMaxBytes) {
                                offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.DownloadTooLarge, "downloaded $bytes > ${dlSettings.downloadMaxBytes}: $key"))
                                failure = DownloadResult(
                                    false, GatewayErrorCode.DownloadTooLarge, null,
                                    "downloaded $bytes > ${dlSettings.downloadMaxBytes}",
                                )
                                break
                            }
                            // 🔴 createDirectories 必须在写文件 **之前**——tmp 的父目录
                            //    由调用方按需命名、从不预建；原顺序先写文件后建目录，首次下载必抛
                            //    FileNotFoundException（真机 C7 复现 file_download_failed）。
                            dest.parent?.let { Files.createDirectories(it) }
                            Files.write(tmp, raw.body ?: ByteArray(0), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                            runCatching { Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE) }
                                .getOrElse { Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING); Unit }  // 跨文件系统降级
                            return@withContext DownloadResult.OK
                        }
                        Tuning.httpPartialContent -> {
                            // 206 分片：解析 Content-Range（大小写不敏感——OkHttp toMultimap 键不保证大小写）。
                            val contentRange = headerValueOf(raw.headers, "Content-Range")?.let { parseContentRange(it) }
                            if (contentRange == null || contentRange.start != offset ||
                                raw.body?.size?.toLong() != contentRange.end - contentRange.start + 1
                            ) {
                                offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.DownloadFailed, "download partial content malformed at offset=$offset: $key"))
                                failure = DownloadResult(
                                    false, GatewayErrorCode.DownloadFailed, null,
                                    "malformed partial content at offset=$offset total=$total",
                                )
                                break
                            }
                            total = contentRange.total
                            // 首片预检（核心升级）：Content-Range 首片即得 total ⇒ 超限零字节落盘即拒（larkCode=null 我方限）。
                            if (contentRange.total > dlSettings.downloadMaxBytes) {
                                offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.DownloadTooLarge, "download total ${contentRange.total} > ${dlSettings.downloadMaxBytes}: $key"))
                                failure = DownloadResult(
                                    false, GatewayErrorCode.DownloadTooLarge, null,
                                    "total ${contentRange.total} > ${dlSettings.downloadMaxBytes}",
                                )
                                break
                            }
                            // 首片 CREATE+TRUNCATE / 后续 APPEND（分片只改写入方式，tmp→move 契约不变）。
                            if (chunks == 1) {
                                dest.parent?.let { Files.createDirectories(it) }
                                Files.write(tmp, raw.body ?: ByteArray(0), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                            } else {
                                Files.write(tmp, raw.body ?: ByteArray(0), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                            }
                            written += raw.body?.size?.toLong() ?: 0L
                            // 累计兜底：防服务器谎报 total（预检已过但实际数据超限）。
                            if (written > dlSettings.downloadMaxBytes) {
                                offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.DownloadTooLarge, "download written $written > ${dlSettings.downloadMaxBytes}: $key"))
                                failure = DownloadResult(
                                    false, GatewayErrorCode.DownloadTooLarge, null,
                                    "written $written > ${dlSettings.downloadMaxBytes}",
                                )
                                break
                            }
                            offset = contentRange.end + 1
                            if (contentRange.total > 0 && offset >= contentRange.total) break  // 齐包（成功收口）
                        }
                        else -> {
                            // 错误可观测性：真实 HTTP 状态码与响应体必须进信号——
                            //    修复前这里恒 DownloadFailed + 异常 toString，看不出是权限/已删除/限流。
                            //    mapper 上提为局部 val：同一结果既进信号又进返回值（resolver 据此渲染用户文案）。
                            val larkCode = larkCodeOf(raw.body)
                            val code = LarkErrorMapper.fromLarkCode(larkCode, raw.statusCode)
                            // 限流双信号（与 uploadFile 对齐的防御性对称）：分片中途 429 同样驱动 RateGovernor。
                            //    resetSec=null ⇒ 走固定阶梯；退避作用面是**后续出站发送**而非下载本身。
                            if (code == GatewayErrorCode.RateLimited) {
                                offerSignal(GatewaySignal.RateLimited(null, larkCode, null))
                            }
                            offerSignal(
                                GatewaySignal.SendFailed(
                                    code,
                                    "download http=${raw.statusCode} body=${bodySnippet(raw.body)} key=$key chunk@offset=$written/$total",
                                ),
                            )
                            failure = DownloadResult(
                                false, code, larkCode,
                                "http=${raw.statusCode} ${bodySnippet(raw.body)}",
                            )
                            break
                        }
                    }
                }
                failure?.let {
                    // 分片中途失败统一清理：tmp 可能已写入若干片（预检/首片失败时可能未创建——deleteIfExists 幂等）。
                    runCatching { Files.deleteIfExists(tmp) }
                    return@withContext it
                }
                // 齐包收口：分片合并完毕 → 原子落位（与 200 降级路径同一契约）。
                runCatching { Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE) }
                    .getOrElse { Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING); Unit }  // 跨文件系统降级
                return@withContext DownloadResult.OK
            } catch (c: CancellationException) {
                runCatching { Files.deleteIfExists(tmp) }
                throw c  // 🔴 不吞 CE：取消是调用方语义，rethrow 交上层接管（resolver 同款纪律）
            } catch (t: Throwable) {
                runCatching { Files.deleteIfExists(tmp) }
                // 异常兜底（网络中断等）：带上异常类型便于区分于上面的 HTTP 分支。
                offerSignal(
                    GatewaySignal.SendFailed(
                        GatewayErrorCode.DownloadFailed,
                        "download exception=${t::class.java.simpleName} ${t.message ?: ""} key=$key",
                    ),
                )
                DownloadResult(
                    false, GatewayErrorCode.DownloadFailed, null,
                    "exception=${t::class.java.simpleName} ${t.message ?: ""}",
                )
            }
        }

    /** 非 200 响应体的 JSON `code` 提取（飞书错误体形如 `{"code":99991672,"msg":"…"}`）；非 JSON / 缺字段返回 null。 */
    private fun larkCodeOf(body: ByteArray?): Int? {
        val text = body?.toString(Charsets.UTF_8) ?: return null
        if (text.isBlank()) return null
        return runCatching {
            com.google.gson.JsonParser.parseString(text).asJsonObject.get("code")?.asInt
        }.getOrNull()
    }

    /** 响应体片段（截断防日志膨胀；非 200 时是 JSON 错误体）。 */
    private fun bodySnippet(body: ByteArray?): String =
        (body?.toString(Charsets.UTF_8) ?: "").take(Tuning.bodySnippetMax)

    // ==================== uploadFile ====================

    /**
     * 上传文件到飞书素材库（raw client `im().v1().file().create`）。
     * 与 [downloadResource] 同纪律：走 raw client（非 ch 短语 API）、withContext(netDispatcher)、
     *    失败 offerSignal + 返回（绝不抛）；前置检查（存在性）在调用方（ReplySink.file）完成。
     * 真机待确认项：`im:resource` 上传权限、fileType="stream" 取值、30MB 上限（S 阶段验收）。
     */
    override suspend fun uploadFile(source: Path, displayName: String?): String? =
        withContext(netDispatcher) {
            try {
                val client = rawClient()
                if (client == null) {
                    offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.UploadFailed, "not connected: $source"))
                    return@withContext null
                }
                val file = source.toFile()
                if (!file.isFile) {
                    offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.UploadFailed, "not a file: $source"))
                    return@withContext null
                }
                val name = displayName ?: file.name
                val req = CreateFileReq.newBuilder()
                    .createFileReqBody(
                        CreateFileReqBody.newBuilder()
                            .fileType("stream") // 飞书文档：opus/mp4/pdf/xls/doc/ppt/stream；通用文件用 stream
                            .fileName(name)
                            .file(file)
                            .build(),
                    )
                    .build()
                val resp = client.im().v1().file().create(req)   // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    // 限流双信号（镜像 LarkCardStreamSink.check）：
                    //    SendFailed 之外**同发** GatewaySignal.RateLimited，驱动 BridgeAssembler.handleSignal
                    //    → RateGovernor.notifyRateLimited——否则上传步被 429/11232 限流时永不进退避。
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "upload code=${resp.code} msg=${resp.msg} file=$name"))
                    return@withContext null
                }
                resp.data?.fileKey
            } catch (t: Throwable) {
                offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.UploadFailed, t.toString()))
                null
            }
        }

    // ==================== updateCard ====================

    /**
     * 原位更新一条已发出的卡片消息（raw client `im().v1().message().patch`）。
     *
     * 与 [uploadFile] 同纪律：raw client、withContext(netDispatcher)、失败 offerSignal + 返回（绝不抛）。
     *    官方约束（PatchMessageReq javadoc）：**仅支持 interactive 消息**（本仓全部卡片出站 msg_type 恒为
     *    "interactive"，形态恒匹配）、整卡替换、≤ 30KB。同路径线上实证成立。
     * content 形态：整卡 JSON 序列化字符串（与 create 的 content 同构，[LarkOutboundMapper.toCardJson]
     *    + encodeJson 产出）——patch 只换 content 不换 msg_type。
     * 更新已有消息**不是新出站**：不占 RateGovernor 令牌桶（同 react 先例），失败只 offerSignal。
     * `streaming==true` 的卡（cardkit 实体流式卡）不在本接口面——它们的更新走
     *    [LarkCardStreamSink] 的 patchElement 流；本接口只服务经 `im.message.create` 发出的**普通卡**。
     */
    override suspend fun updateCard(messageId: String, spec: CardSpec): SendResult =
        withContext(netDispatcher) {
            try {
                val client = rawClient()
                    ?: return@withContext SendResult(false, null, GatewayErrorCode.NotConnected)
                val body = PatchMessageReqBody.newBuilder()
                    .content(LarkOutboundMapper.encodeJson(LarkOutboundMapper.toCardJson(spec)))
                    .build()
                val req = PatchMessageReq.newBuilder()
                    .messageId(messageId)
                    .patchMessageReqBody(body)
                    .build()
                val resp = client.im().v1().message().patch(req) // 同步、throws Exception
                if (resp.success()) {
                    SendResult(true, messageId)
                } else {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "patch card code=${resp.code} msg=${resp.msg} id=$messageId"))
                    SendResult(false, null, code, resp.code, resp.msg)
                }
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "patch card exception id=$messageId: ${t.message}"))
                SendResult(false, null, code, null, t.message, null)
            }
        }

    // ==================== 能力一级化：9 个新能力的 SDK 实现 ====================
    // 全部同款纪律：raw client（非 ch 短语 API）、withContext(netDispatcher)、失败 offerSignal +
    // 返回 null/false（绝不抛）、429/限流双信号（RateLimited + SendFailed，镜像 uploadFile）、
    // 未连接（rawClient 为空）直接返回 null/false。scope 前提见各成员接口 KDoc（MessageGateway）。

    /**
     * 撤回机器人自己的消息（raw client `im().v1().message().delete`——DELETE 语义即撤回）。
     * 与 [updateCard] 同纪律；scope 归 send_as_bot 族（真机待确认）。
     */
    override suspend fun recallMessage(messageId: String): Boolean =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext false
                val req = DeleteMessageReq.newBuilder().messageId(messageId).build()
                val resp = client.im().v1().message().delete(req) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "recall code=${resp.code} msg=${resp.msg} id=${maskMessageId(messageId)}"))
                    false
                } else {
                    true
                }
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "recall exception id=${maskMessageId(messageId)}: ${t.message}"))
                false
            }
        }

    /**
     * 上传图片到飞书图片素材库（raw client `im().v1().image().create`，imageType="message"）。
     * 与 [uploadFile] 完全镜像的实现纪律（存在性前置检查在调用方）；返回 image_key。
     */
    override suspend fun uploadImage(source: java.nio.file.Path): String? =
        withContext(netDispatcher) {
            try {
                val client = rawClient()
                if (client == null) {
                    offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.UploadFailed, "not connected: $source"))
                    return@withContext null
                }
                val file = source.toFile()
                if (!file.isFile) {
                    offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.UploadFailed, "not a file: $source"))
                    return@withContext null
                }
                val req = CreateImageReq.newBuilder()
                    .createImageReqBody(
                        CreateImageReqBody.newBuilder()
                            .imageType("message") // 飞书文档：图片用途固定 message（消息图片）
                            .image(file)
                            .build(),
                    )
                    .build()
                val resp = client.im().v1().image().create(req)   // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    // 限流双信号（镜像 uploadFile 的 429 处理）：SendFailed 之外同发 RateLimited 驱动 RateGovernor。
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "upload image code=${resp.code} msg=${resp.msg} file=${file.name}"))
                    return@withContext null
                }
                resp.data?.imageKey
            } catch (t: Throwable) {
                offerSignal(GatewaySignal.SendFailed(GatewayErrorCode.UploadFailed, t.toString()))
                null
            }
        }

    /**
     * 拉取会话历史消息（raw client `im().v1().message().list`，container_id_type=chat）。
     * 正文摘要解析走顶层纯函数 [parseHistoryTextBrief]（坏 JSON 不炸）；scope：
     * `im:message.history:readonly`（需后台申请+发版，未授权按 code 落 WARN + null）。
     */
    override suspend fun listMessages(chatId: String, pageSize: Int, pageToken: String?): ChatHistoryPage? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val builder = ListMessageReq.newBuilder()
                    .containerIdType("chat")
                    .containerId(chatId)
                    .pageSize(pageSize)
                    .sortType("ByCreateTimeAsc")
                pageToken?.let { builder.pageToken(it) }
                val resp = client.im().v1().message().list(builder.build()) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, chatId))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "list messages code=${resp.code} msg=${resp.msg} chat=${maskMessageId(chatId)}"))
                    return@withContext null
                }
                val items = resp.data?.items.orEmpty().map { m ->
                    ChatHistoryItem(
                        messageId = m.messageId ?: "",
                        senderOpenId = m.sender?.id ?: "",
                        createTimeMillis = m.createTime?.toLongOrNull() ?: 0L,
                        msgType = m.msgType ?: "",
                        textBrief = parseHistoryTextBrief(m.msgType ?: "", m.body?.content ?: ""),
                    )
                }
                ChatHistoryPage(
                    items = items,
                    hasMore = resp.data?.hasMore == true,
                    nextPageToken = resp.data?.pageToken,
                )
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "list messages exception chat=${maskMessageId(chatId)}: ${t.message}"))
                null
            }
        }

    /**
     * 查询群信息（raw client `im().v1().chat().get`，userIdType=open_id）。
     * userCount 是 String 形态数字（SDK GetChatRespBody），toLongOrNull 容错非数字形态。
     */
    override suspend fun getChatInfo(chatId: String): ChatInfo? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val req = GetChatReq.newBuilder().chatId(chatId).userIdType("open_id").build()
                val resp = client.im().v1().chat().get(req) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, chatId))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "chat get code=${resp.code} msg=${resp.msg} chat=${maskMessageId(chatId)}"))
                    return@withContext null
                }
                val body = resp.data ?: return@withContext null
                ChatInfo(
                    chatId = chatId,
                    name = body.name ?: "",
                    description = body.description ?: "",
                    ownerOpenId = body.ownerId ?: "",
                    memberCount = body.userCount?.toLongOrNull()?.toInt() ?: -1,
                    chatType = body.chatType ?: "",
                )
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "chat get exception chat=${maskMessageId(chatId)}: ${t.message}"))
                null
            }
        }

    /**
     * 分页拉取群成员（raw client `im().v1().chatMembers().get`，member_id_type=open_id）。
     * 🔴 PII 最小化：只回 open_id + name，不透传 tenantKey 等其余字段——多透传一个字段即多一分敏感信息泄漏面。
     */
    override suspend fun listChatMembers(chatId: String, pageSize: Int, pageToken: String?): ChatMembersPage? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val builder = GetChatMembersReq.newBuilder()
                    .chatId(chatId)
                    .memberIdType("open_id")
                    .pageSize(pageSize)
                pageToken?.let { builder.pageToken(it) }
                val resp = client.im().v1().chatMembers().get(builder.build()) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, chatId))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "chat members code=${resp.code} msg=${resp.msg} chat=${maskMessageId(chatId)}"))
                    return@withContext null
                }
                ChatMembersPage(
                    items = resp.data?.items.orEmpty().map { m ->
                        ChatMemberBrief(openId = m.memberId ?: "", name = m.name ?: "")
                    },
                    hasMore = resp.data?.hasMore == true,
                    nextPageToken = resp.data?.pageToken,
                    memberTotal = resp.data?.memberTotal ?: -1,
                )
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "chat members exception chat=${maskMessageId(chatId)}: ${t.message}"))
                null
            }
        }

    /**
     * 创建群聊（raw client `im().v1().chat().create`）。不显式设 ownerId——CreateChatReqBody 无
     * ownerId 之外的机器人 owner 直填路径（SDK Builder 语义：tenant_token 调用时飞书侧把 owner 恒落
     * 在发起调用的机器人上）；[CreateChatReq.Builder.setBotManager](true)
     * 把机器人设为管理员，保证建群后的群管理动作（拉人等）可续。
     */
    override suspend fun createChat(name: String, memberOpenIds: List<String>): ChatCreation? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val body = CreateChatReqBody.newBuilder()
                    .name(name)
                    .chatMode("group")
                    .chatType("private") // 私有群（仅受邀成员可见），机器人建群的默认形态
                    .apply {
                        if (memberOpenIds.isNotEmpty()) userIdList(memberOpenIds.toTypedArray())
                    }
                    .build()
                val req = CreateChatReq.newBuilder()
                    .userIdType("open_id")
                    .setBotManager(true) // 机器人设为群管理员：tenant_token 调用下 owner 恒落机器人自己，且后续群管理操作可续
                    .createChatReqBody(body)
                    .build()
                val resp = client.im().v1().chat().create(req) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "chat create code=${resp.code} msg=${resp.msg} name=$name"))
                    return@withContext null
                }
                ChatCreation(chatId = resp.data?.chatId ?: "", failedOpenIds = emptyList())
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "chat create exception name=$name: ${t.message}"))
                null
            }
        }

    /**
     * 向群拉入成员（raw client `im().v1().chatMembers().create`，member_id_type=open_id）。
     * 飞书把「无效 / 不存在 / 待审批」三张名单分开返回——统一合并进 failedOpenIds
     * （上层只关心「谁没进来」），ok = 三名单全空。
     */
    override suspend fun inviteChatMembers(chatId: String, openIds: List<String>): ChatMemberOpResult? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val body = CreateChatMembersReqBody.newBuilder()
                    .idList(openIds.toTypedArray())
                    .build()
                val req = CreateChatMembersReq.newBuilder()
                    .chatId(chatId)
                    .memberIdType("open_id")
                    .createChatMembersReqBody(body)
                    .build()
                val resp = client.im().v1().chatMembers().create(req) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, chatId))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "chat members invite code=${resp.code} msg=${resp.msg} chat=${maskMessageId(chatId)}"))
                    return@withContext null
                }
                val data = resp.data
                val failed = buildList {
                    data?.invalidIdList?.let { addAll(it) }
                    data?.notExistedIdList?.let { addAll(it) }
                    data?.pendingApprovalIdList?.let { addAll(it) }
                }
                ChatMemberOpResult(ok = failed.isEmpty(), failedOpenIds = failed)
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "chat members invite exception chat=${maskMessageId(chatId)}: ${t.message}"))
                null
            }
        }

    /**
     * 列出消息的表情回应（raw client `im().v1().messageReaction().list`，userIdType=open_id）。
     * 内部循环翻页取齐（单页上限 50，循环受 maxIterations 上界防病态服务器）；卡片交互专项预留，本轮零消费者。
     */
    override suspend fun listReactions(messageId: String): List<ReactionBrief>? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val all = mutableListOf<ReactionBrief>()
                var token: String? = null
                var pages = 0
                do {
                    val builder = ListMessageReactionReq.newBuilder()
                        .messageId(messageId)
                        .userIdType("open_id")
                        .pageSize(Tuning.reactionsPageSize)
                    token?.let { builder.pageToken(it) }
                    val resp = client.im().v1().messageReaction().list(builder.build()) // 同步、throws Exception
                    if (!resp.success()) {
                        val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                        if (code == GatewayErrorCode.RateLimited) {
                            offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                        }
                        offerSignal(GatewaySignal.SendFailed(code, "reactions list code=${resp.code} msg=${resp.msg} id=${maskMessageId(messageId)}"))
                        return@withContext null
                    }
                    resp.data?.items.orEmpty().forEach { r ->
                        all.add(
                            ReactionBrief(
                                emojiKey = r.reactionType?.emojiType ?: "",
                                operatorOpenId = r.operator?.operatorId ?: "",
                            ),
                        )
                    }
                    token = resp.data?.pageToken?.takeIf { resp.data?.hasMore == true }
                    pages++
                } while (token != null && pages < Tuning.reactionsMaxPages)
                all
            } catch (c: CancellationException) {
                throw c // 🔴 不吞 CE（doDownload 同款纪律）——取消折叠 null 会虚假烧调用方退避额度
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "reactions list exception id=${maskMessageId(messageId)}: ${t.message}"))
                null
            }
        }

    /**
     * 拉取消息的已读回执（raw client `im().v1().message().readUsers`，user_id_type=open_id）。
     * 单页语义（不循环翻页——调用方按 nextPageToken 续拉）；卡片交互专项预留，本轮零消费者。
     */
    override suspend fun readReceipts(messageId: String, pageSize: Int, pageToken: String?): ReadReceiptPage? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val builder = ReadUsersMessageReq.newBuilder()
                    .messageId(messageId)
                    .userIdType("open_id")
                    .pageSize(pageSize)
                pageToken?.let { builder.pageToken(it) }
                val resp = client.im().v1().message().readUsers(builder.build()) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "read users code=${resp.code} msg=${resp.msg} id=${maskMessageId(messageId)}"))
                    return@withContext null
                }
                ReadReceiptPage(
                    readOpenIds = resp.data?.items.orEmpty().mapNotNull { it.userId },
                    hasMore = resp.data?.hasMore == true,
                    nextPageToken = resp.data?.pageToken,
                )
            } catch (c: CancellationException) {
                throw c // 🔴 不吞 CE（doDownload 同款纪律）——取消折叠 null 会虚假烧调用方退避额度
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "read users exception id=${maskMessageId(messageId)}: ${t.message}"))
                null
            }
        }

    // ==================== 能力一级化：wiki/docx 两成员的 SDK 实现 ====================
    // 纪律同上九成员：raw client、withContext(netDispatcher)、失败 offerSignal + 返回 null（绝不抛）、
    // 429/限流双信号、未连接（rawClient 为空）直接返回 null。

    /**
     * 解析知识库节点（`wiki().v2().space().getNode`，token 为 @Query）。
     * objType 不传 = 按 wiki 节点类型查询（任务单口径：工具面只服务 `/wiki/` 链接分流）。
     */
    override suspend fun resolveWikiNode(token: String): WikiNodeRef? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val req = GetNodeSpaceReq.newBuilder().token(token).build()
                val resp = client.wiki().v2().space().getNode(req) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "wiki node code=${resp.code} msg=${resp.msg} token=${maskMessageId(token)}"))
                    return@withContext null
                }
                val node = resp.data?.node ?: return@withContext null
                WikiNodeRef(
                    objToken = node.objToken ?: "",
                    objType = node.objType ?: "",
                    title = node.title ?: "",
                )
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "wiki node exception token=${maskMessageId(token)}: ${t.message}"))
                null
            }
        }

    /** 读文档纯文本正文（`docx().v1().document().rawContent`，documentId 为 @Path）。 */
    override suspend fun readDocumentText(documentId: String): String? =
        withContext(netDispatcher) {
            try {
                val client = rawClient() ?: return@withContext null
                val req = RawContentDocumentReq.newBuilder().documentId(documentId).build()
                val resp = client.docx().v1().document().rawContent(req) // 同步、throws Exception
                if (!resp.success()) {
                    val code = LarkErrorMapper.fromLarkCode(resp.code, resp.rawResponse?.statusCode)
                    if (code == GatewayErrorCode.RateLimited) {
                        offerSignal(GatewaySignal.RateLimited(findRateLimitReset(resp), resp.code, null))
                    }
                    offerSignal(GatewaySignal.SendFailed(code, "docx raw content code=${resp.code} msg=${resp.msg} doc=${maskMessageId(documentId)}"))
                    return@withContext null
                }
                resp.data?.content
            } catch (t: Throwable) {
                val code = mapThrowable(t)
                offerSignal(GatewaySignal.SendFailed(code, "docx raw content exception doc=${maskMessageId(documentId)}: ${t.message}"))
                null
            }
        }

    // ==================== 第 10 成员：setReaction（无状态撤销）====================

    override suspend fun setReaction(messageId: String, emojiKey: String, add: Boolean): Boolean =
        withContext(netDispatcher) {
            val ch = channel ?: return@withContext false
            // await() 挂起 ⇒ try/catch（gateway-contract.md §15.2 M-11）。add=false 走 removeReactionByEmoji（无状态，不保存 reactionId）。
            try {
                if (add) ch.addReaction(messageId, emojiKey).await() != null       // CompletableFuture<String> = reactionId
                else ch.removeReactionByEmoji(messageId, emojiKey).await() == true // CompletableFuture<Boolean>
            } catch (t: Throwable) {
                // 表情回应是装饰性信号（BuiltinHandlers runCatching 吞失败，
                //    不影响任务状态）——「响应成功但 data.reaction_id 为空」是 SDK 对部分 emoji key 的
                //    正常返回形态（OutboundLowLevelApi 抛 UNKNOWN 异常），不再上抛 SendFailed 刷
                //    「网关出站失败」WARN；其余异常按原路径上抛（保持可观测）。
                val msg = t.message ?: t.toString()
                if (msg.contains("reaction_id")) {
                    log.debug("addReaction 响应无 reaction_id（emojiKey={}）：视为成功，不上抛信号", emojiKey)
                    true
                } else {
                    val code = mapThrowable(t)
                    offerSignal(GatewaySignal.SendFailed(code, msg, messageId))
                    false
                }
            }
        }

    // ==================== 第 11 成员：registerApp（gateway-contract.md §8，专用 bridge-register dispatcher，唯一允许抛异常）====================

    override suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): LarkCredential =
        RegisterAppFlow.run(regionHint, onQr)

    // ==================== 第 12 成员：close（gateway-contract.md §3.11 / gateway-contract.md §9.3，幂等收口）====================

    /**
     * 幂等关闭网关（网关侧关闭六步）。
     *
     * ## 线程收口契约
     * 入站恒走自研 `:lark-ws`（`WsClient.close` = scope.cancel，心跳/清扫/分发协程一次全回收，
     * **零反射**——SDK ws 线程池泄漏路径随 webhook 恒定整体消失）。
     * **但残留的非 daemon 线程是 OkHttp Dispatcher + main**：出站仍走 SDK Client（webhook facade），
     * OkHttp 的 Dispatcher 线程池 SDK 不暴露、无法反射关闭。
     *    因此 [close] **不能让 JVM 自然退出**——线程阈值**必须容忍残留非 daemon 线程，不能要求归零**。
     *
     * ## 🔴 消费者（:lark-bridge CLI `serve` 与宿主 `onAppExit()`）的头号契约
     * 进程退出**必须由宿主显式 `exitProcess`**。网关内**绝不** `exitProcess` / `System.exit` / `Runtime.halt`
     * （由 `GatewayCloseReleasesThreadsTest` 的 grep 断言钉死）。调用方在 [close] 返回后需自行 `exitProcess(0)` 才能确保 JVM 退出。
     */
    override fun close() {
        // 步 0：CAS 幂等（十步关闭第 ⑥ 步可能被 onAppExit + shutdown hook 各调一次）
        if (!closed.compareAndSet(false, true)) return
        val ch = channel
        // 步 1：断连（非挂起，runCatching 兜底）——webhook 模式下 SDK 侧无 ws 连接，只剩 safetyPipeline.dispose
        runCatching { ch?.disconnectSync() }
        // 步 1.5：关闭自研入站传输——WsClient.close = scope.cancel，心跳/清扫/分发协程一次全回收（零反射收口）。
        runCatching { wsInboundTransportRef.getAndSet(null)?.close() }
        // 步 2：cancel 内部协程（inbound/signals 收集、connectJob、sink 主循环）
        runCatching { gatewayScope.cancel() }
        // 步 3：兜底关闭仍开放的 sink（非挂起 abortForClose，不发终态卡——网关不知文案，gateway-contract.md §9.3 步 3 分工）
        openSinks.forEach { runCatching { it.abortForClose() } }
        openSinks.clear()
        // 步 3.5：🔴 清空留存的凭据（appSecret 不长驻内存；下载已不可能再发生，close 是终态）
        credentialRef.set(null)
        // 步 4（反射 shutdown ws 线程池）已删除：入站恒 webhook + 自研 ws，
        //    SDK 侧 rawWsClient 恒 null（无 ws 线程池存在），反射路径无对象可关。
        // 步 5：关闭内部 channel（让 inbound/signals 的 collector 正常结束）
        runCatching { inboundChannel.close() }
        runCatching { signalChannel.close() }
        // 步 6：状态归位（gateway-contract.md §9.3：若原为 Idle 则保持 Idle）
        if (_connectionState.value !is GatewayConnectionState.Idle) {
            _connectionState.value = GatewayConnectionState.Disconnected(GatewayErrorCode.Unknown, "closed")
        }
        channel = null
    }

    // ==================== 第 13 成员：resolveSenderName（contact.v3.user.get，旁路语义）====================

    /**
     * 通过 open_id 解析飞书用户名（`GET /contact/v3/users/{open_id}?user_id_type=open_id`）。
     *
     * 前置条件：应用需在飞书后台授予 `contact:contact.base:readonly` scope（旧名
     *    contact:user.base:readonly 已更名）**且通讯录授权范围覆盖该用户**。
     *    失败时返回 null 且**仅首次 WARN 一次**（告警限流，不刷日志）；WARN 携带飞书返回的真实
     *    code/msg 原文与四种可能原因的排查清单（猜测性告警无法区分
     *    scope 未加 / 未发版 / 通讯录授权范围未覆盖 / token 缓存早于发版，四种处置完全不同）。
     * 全程包在 `withContext(netDispatcher)` 内（NF-A4：所有 SDK 调用必须在 bridge-net 池）。
     * `runCatching` 兜底 ⇒ 绝不抛异常（旁路语义：失败不影响消息主链路）。
     * 🔴 隐私：WARN 内 openId 一律经 [maskOpenId] 掩码；异常分支只取 class 简单名 + message，不打堆栈、不打 toString——明文 openId 落日志即 PII 泄漏。
     */
    override suspend fun resolveSenderName(openId: String): String? {
        if (openId.isBlank()) return null
        return withContext(netDispatcher) {
            runCatching {
                val client = rawClient() ?: return@runCatching null
                val req = GetUserReq.newBuilder().userId(openId).userIdType("open_id").build()
                val resp = client.contact().v3().user().get(req)
                if (!resp.success()) {
                    warnContactScopeOnce(contactFailureDiagnostics(openId, resp.code, resp.msg))
                    return@runCatching null
                }
                resp.data?.user?.name?.takeIf { it.isNotBlank() }
            }.getOrElse {
                warnContactScopeOnce(contactExceptionDiagnostics(openId, it))
                null
            }
        }
    }

    /**
     * 第 15 成员：resolveSenderProfile——与 [resolveSenderName]
     * 同一 API（contact.v3.user.get）取名字 + 头像 URL（avatar_240 → avatar_72 → avatar_640 择优，
     * 240px 是统计页头像显示的尺寸档）。
     *
     * 旁路纪律与 [resolveSenderName] 完全一致：withContext(netDispatcher) + runCatching 绝不抛、
     *    失败 WARN 一次限流复用 [warnContactScopeOnce]（同一 CAS 限流器——两个成员共享首警，
     *    不重复刷诊断）、openId 掩码同款。scope/授权范围前置条件同 resolveSenderName。
     */
    override suspend fun resolveSenderProfile(openId: String): SenderProfile? {
        if (openId.isBlank()) return null
        return withContext(netDispatcher) {
            runCatching {
                val client = rawClient() ?: return@runCatching null
                val req = GetUserReq.newBuilder().userId(openId).userIdType("open_id").build()
                val resp = client.contact().v3().user().get(req)
                if (!resp.success()) {
                    warnContactScopeOnce(contactFailureDiagnostics(openId, resp.code, resp.msg))
                    return@runCatching null
                }
                val user = resp.data?.user ?: return@runCatching null
                SenderProfile(
                    name = user.name.orEmpty(),
                    avatarUrl = user.avatar?.avatar240
                        ?: user.avatar?.avatar72
                        ?: user.avatar?.avatar640
                        ?: "",
                )
            }.getOrElse {
                warnContactScopeOnce(contactExceptionDiagnostics(openId, it))
                null
            }
        }
    }

    /**
     * 告警限流：contact 解析失败只 WARN 一次（每进程生命周期），但**首条必须携带真实诊断**（[detail]
     * 由顶层纯函数 [contactFailureDiagnostics] / [contactExceptionDiagnostics] 格式化）。
     * 限流判据抽到顶层纯函数 [shouldWarnContactScope]（CAS 语义逐字不变，可单测）；行为契约不变：
     *    每进程只 WARN 一次（真机实证：9 条消息只告警 1 次）。
     */
    private fun warnContactScopeOnce(detail: String) {
        if (shouldWarnContactScope(contactScopeWarned)) {
            log.warn(detail)
        }
    }

    // ==================== 第 14 成员：fetchQuotedMessage（im.v1.message.get，引用即上下文） ====================

    /** 引用补查失败告警限流（每进程只 WARN 一次，携带真实 code 供 scope 排查；仿 [contactScopeWarned]）。 */
    private val quotedFetchWarned = AtomicBoolean(false)

    /**
     * 现查被引用的消息（`GET /im/v1/messages/{message_id}`）：文件消息 → [QuotedMessage.File]，
     * 文本消息 → [QuotedMessage.Text]，其余消息类型一律 null。
     *
     * 场景：引用流 lookup miss（进程重启清空登记表 / TTL 过期 / 用户引用了旧气泡 / 引用纯文本）时兜底。
     * 全程 `withContext(netDispatcher)` + `runCatching`（NF-A4 / 旁路语义：失败不影响主链路，
     *    返回 null 由调用方回落 hint 路径）；未连接 / API 失败 / 不支持类型 / 已删除一律 null。
     * scope 前提：读单条消息可能需 `im:message:readonly`（收消息用的 `im:message.p2p_msg:readonly`
     *    未必覆盖）——失败 WARN 一次携带飞书真实 code/msg，供用户后台核对 scope。
     * 文本 content 是 JSON 字符串（`{"text":"…"}`，S4 真机形态 + 飞书文档实证），用 SDK 传递引入的
     *    Gson 解析（Gson 只允许出现在本模块——LarkSdkIsolationTest 黑名单目录排除制；桥侧零 JSON 依赖）。
     */
    override suspend fun fetchQuotedMessage(messageId: String): QuotedMessage? {
        if (messageId.isBlank()) return null
        return withContext(netDispatcher) {
            runCatching {
                val client = rawClient() ?: run {
                    // 连接态下 rawClient 意外为空是异常态，用 WARN 而非静默 null（否则真机排障
                    //    无法回答「补查为什么没救回」）。
                    log.warn("引用补查不可用 messageId=${maskMessageId(messageId)}：已请求但 rawClient 为空（未连接？）")
                    return@runCatching null
                }
                val req = GetMessageReq.newBuilder().messageId(messageId).userIdType("open_id").build()
                val resp = client.im().v1().message().get(req)
                if (!resp.success()) {
                    warnQuotedFetchOnce(
                        "引用补查失败 messageId=${maskMessageId(messageId)}：飞书返回 code=${resp.code} msg=\"${resp.msg}\"" +
                            "（若为 99991672 权限类错误：请到飞书后台为应用开通 im:message:readonly scope 并发版）",
                    )
                    return@runCatching null
                }
                // 以下 4 条静默路径补分类 INFO：它们是「API 成功但取不到
                //    可用正文」的**正常降级**，不是告警——不受 warn-once 限流（限流只留给 API 级失败）。
                //    每条带 maskMessageId 供真机日志对账（哪条消息、为什么 null）。
                val msg = resp.data?.items?.firstOrNull() ?: run {
                    log.info("引用补查空结果 messageId=${maskMessageId(messageId)}：API 成功但 items 为空")
                    return@runCatching null
                }
                if (msg.deleted == true) {
                    log.info("引用补查空结果 messageId=${maskMessageId(messageId)}：消息已删除（deleted=true）")
                    return@runCatching null
                }
                val content = msg.body?.content ?: run {
                    log.info("引用补查空结果 messageId=${maskMessageId(messageId)}：body.content 为空")
                    return@runCatching null
                }
                parseQuotedContent(messageId, msg.chatId ?: "", msg.sender?.id ?: "", msg.msgType ?: "", content)
                    ?: run {
                        log.info(
                            "引用补查未解析 messageId=${maskMessageId(messageId)}：msgType=${msg.msgType}" +
                                " 不支持引用上下文或 content 形态不识别（content 前 80 字：${content.take(80)}）",
                        )
                        null
                    }
            }.getOrElse {
                warnQuotedFetchOnce("引用补查异常 messageId=${maskMessageId(messageId)}：${it.javaClass.simpleName} ${it.message}")
                null
            }
        }
    }

    /** 告警限流：引用补查失败只 WARN 一次（首条带真实诊断）。 */
    private fun warnQuotedFetchOnce(detail: String) {
        if (quotedFetchWarned.compareAndSet(false, true)) {
            log.warn(detail)
        }
    }

    // ==================== 私有：SDK 选项构造（gateway-contract.md §3.3，全仓唯一把纯数据翻译成 SDK 类型的地方）====================

    /**
     * @param inboundWebhook 🔴 true ⇒ SDK 入站 transport 切 "webhook"（Builder 默认 "websocket"，
     *   LarkChannelOptions.java:136）——SDK 侧不再建第二条 ws 长连接（同 appId 双长连接被服务端互踢），
     *   LarkChannel 退化为纯出站/identity facade（rawWsClient==null）。false 分支 build 链逐字不变。
     */
    private fun buildOptions(cred: LarkCredential, s: GatewaySdkSettings, inboundWebhook: Boolean = false): LarkChannelOptions {
        val safety = LarkChannelOptions.SafetyConfig().apply {
            // 🔴 真实 Kotlin 属性名带 is 前缀（isChatQueueEnabled()/setChatQueueEnabled(boolean)）。
            //    写成 chatQueueEnabled= 不生效 ⇒ 静默用默认 true ⇒ 同 chat 600ms 窗口内多条消息被合并、只留最后一条 messageId。
            isChatQueueEnabled = s.chatQueueEnabled          // 恒 false（gateway-contract.md §5.2）
            dedupTtlMs = s.dedupTtlMs                        // 12h
            dedupMaxEntries = s.dedupMaxEntries              // 5000
            dedupSweepMs = s.dedupSweepMs                    // 5min
            staleMessageWindowMs = s.staleMessageWindowMs    // 30min
            processingLockTtlMs = s.processingLockTtlMs      // 5min
        }
        val outbound = LarkChannelOptions.OutboundConfig().apply {
            textChunkLimit = s.textChunkLimit                // 3500（出站分片属 OutboundConfig）
            streamThrottleMs = s.streamThrottleMs            // 250
            streamThrottleChars = s.streamThrottleChars      // 4000（Throttle 是「或」关系，必须调大）
            streamInitialText = s.streamInitialText          // 我方自建卡 JSON 不用它，保留默认
            // gateway-contract.md §15.2 M-18：真实属性名 isSsrfGuardEnabled（gateway-contract.md §3.3 写 ssrfGuardEnabled 是 BUG，无该属性 ⇒ 编译失败）
            isSsrfGuardEnabled = s.ssrfGuardEnabled          // true
            ssrfAllowlist = s.ssrfAllowlist
            allowedFileDirs = s.allowedFileDirs              // 🔴 默认空 ⇒ 必须由引擎注入（[SF:916]：不注入则本地路径全被 SSRF 防护拦截）
            retry = LarkChannelOptions.RetryConfig().apply {
                maxAttempts = s.retryMaxAttempts             // 🔴 恒 1，关掉盲指数退避（Thread.sleep 占 commonPool）
                baseDelayMs = s.retryBaseDelayMs.toLong()    // setBaseDelayMs(long)
            }
        }
        return LarkChannelOptions.newBuilder(cred.appId, cred.appSecret)
            .safety(safety)
            .policy(policyConfig(s.groupAllowlist, s.dmMode, s.dmAllowlist, s.requireMention, s.respondToMentionAll))
            .outbound(outbound)
            .apply { if (inboundWebhook) transport("webhook") }   // 自研 ws 分支：入站切自研，SDK 只留出站/identity
            // 不调用 includeRawInMessage / includeRawEvent（已 deprecated）
            .build()
    }

    /**
     * 构造 SDK PolicyConfig（gateway-contract.md §5.3）。用显式 setter 而非 Kotlin 合成属性：setGroupAllowlist/setDmAllowlist 有
     * List 与 varargs 两个重载，属性合成有歧义；显式 setter 传 List 精确命中 List 重载（已编译验证）。
     * 🔴 恒设置全部 5 字段（全量覆盖，非增量 merge——部分填充即清空 groupAllowlist）。
     */
    private fun policyConfig(
        groupAllowlist: List<String>,
        dmMode: String,
        dmAllowlist: List<String>,
        requireMention: Boolean,
        respondToMentionAll: Boolean,
    ): LarkChannelOptions.PolicyConfig = LarkChannelOptions.PolicyConfig().apply {
        setGroupAllowlist(groupAllowlist)
        setDmMode(dmMode)
        setDmAllowlist(dmAllowlist)
        setRequireMention(requireMention)
        setRespondToMentionAll(respondToMentionAll)
    }

    // ==================== 私有：自研 ws 入站回调（纪律同 SDK 路径）====================

    /**
     * WsInboundTransport 的统一事件回调（工厂注入缝的 eventHandler）。
     * 回调纪律（只 capture + 纯 mapper + offerInbound）在自研路径同样成立——区别只是
     *    回调本体是挂起函数（WsClient 分发协程内执行），翻译层 [WsInboundTranslator] 是纯函数
     *    语义（同输入同产出、绝不抛），offerInbound O(1) 非阻塞，不做重活。
     * 回执载荷恒 null：卡片/消息事件的处理结果由桥接处理链异步产出（与 SDK 路径的
     *    ChannelEventHandler 返回 void 等价——WsClient 回执 200 + 无 data 字段）。
     */
    private suspend fun handleWsInboundEvent(ev: WsInboundEvent): String? {
        when (ev) {
            is WsInboundEvent.MessageReceived -> {
                runCatching {
                    offerInbound(LarkInboundMapper.fromMessage(wsTranslator.get().translate(ev), clock()))
                }
            }
            is WsInboundEvent.CardAction -> {
                // 闸门（messageId/chatId/operatorId 任一 blank 丢弃）在翻译层内，null 即静默丢
                runCatching {
                    wsTranslator.get().translateCardAction(ev.payload)?.let {
                        offerInbound(LarkInboundMapper.fromCardAction(it, clock()))
                    }
                }
            }
            is WsInboundEvent.Unknown -> Unit // 未订阅类型：透传零消费（SDK 路径同理不注册）
        }
        return null
    }

    /** WsStateListener 实现（每次 connect 新建 lambda）：WsState → GatewayConnectionState + 信号，照 ch.on("reconnecting"/"reconnected") 现状纪律。 */
    private fun wsStateListener(): WsStateListener = object : WsStateListener {
        override fun onReconnecting() {
            _connectionState.value = GatewayConnectionState.Reconnecting
            runCatching { offerSignal(GatewaySignal.Reconnecting) }
        }

        override fun onReconnected() {
            _connectionState.value = GatewayConnectionState.Connected(botOpenId, botName)  // 沿用原 identity
            runCatching { offerSignal(GatewaySignal.Reconnected) }
        }
    }

    /** region → 飞书域（与 SDK BaseUrlEnum 同源：feishu → open.feishu.cn，lark → open.larksuite.com）。 */
    private fun domainOf(region: String): String =
        if (region == "lark") "https://open.larksuite.com" else "https://open.feishu.cn"

    private val wsTranslator = AtomicReference(
        WsInboundTranslator(botOpenIdProvider = { botOpenId.takeIf { it.isNotEmpty() } })
    )

    // ==================== 私有：SDK 事件注册（已整体删除）====================
    // 原 registerHandlers 注册 message/cardAction/reject/reconnecting/reconnected/error 六个事件回调，
    // 只服务于 SDK websocket 入站路径——入站恒自研后 webhook 模式的 ChannelEventBus 无事件来源，全部删除：
    // - message/cardAction → handleWsInboundEvent（:lark-ws 事件回调，翻译层 + offerInbound 同形）
    // - reconnecting/reconnected → wsStateListener()（WsState stateFlow 观察，状态机 + 信号同形）
    // - reject/error → 无自研对应事件（SDK SafetyPipeline 专属），不再产出。

    /**
     * 把「生命周期结束 → 断连」语义挂在 [gatewayScope] 上（gateway-contract.md §3.4 定稿形态，替代 callbackFlow 的 awaitClose）。
     * 🔴 用 gatewayScope（不是注入的 scope）：close() 只 cancel gatewayScope ⇒ connectJob 取消 ⇒ 触发 disconnectSync——改用注入 scope 则 close() 收不到 connectJob，断连永不触发（连接泄漏）。
     */
    private fun attachDisconnectOnCancel(ch: LarkChannel) {
        connectJob = gatewayScope.launch(netDispatcher) { awaitCancellation() }.also {
            it.invokeOnCompletion { runCatching { ch.disconnectSync() } }
        }
    }

    // ==================== 私有：入站 / 信号投递（丢弃可观测）====================

    /**
     * DROP_OLDEST 的 trySend 恒成功（挤掉最旧），无法从返回值判丢弃 ⇒ 用占用计数推断（gateway-contract.md §15.2 M-16）：
     * getAndIncrement 的前值 >= 容量 ⇒ 本次挤掉了最旧一条 ⇒ 净占用回落到容量 + 计数 + 发 Dropped("inbound")。
     * 全程 O(1) 非阻塞、无锁（AtomicInteger CAS），满足回调纪律。
     */
    private fun offerInbound(msg: InboundMessage) {
        val prev = inboundOccupancy.getAndIncrement()
        if (prev >= Tuning.inboundChannelCapacity) {
            inboundOccupancy.decrementAndGet()
            val total = inboundDropTotal.incrementAndGet()
            runCatching { signalChannel.trySend(GatewaySignal.Dropped("inbound", total)) }
        }
        runCatching { inboundChannel.trySend(msg) }
    }

    /** 信号投递。🔴 gateway-contract.md §15.2 M-17：signal 通道丢弃计数归 M4 metrics 域（低频事件，避免 Dropped 自身递归）。 */
    private fun offerSignal(sig: GatewaySignal) {
        runCatching { signalChannel.trySend(sig) }
    }

    private fun rawClient(): Client? = channel?.rawClient

    /** gateway-contract.md §15.2 M-11：gateway-contract.md §3.9/gateway-contract.md §3.10 引用的 LarkErrorMapper.fromThrowable(4 元组) 不存在 ⇒ 本 helper 统一异常→码映射。 */
    private fun mapThrowable(t: Throwable): GatewayErrorCode = when (t) {
        is LarkChannelException -> LarkErrorMapper.fromLarkChannelCode(t.code)
        else -> LarkErrorMapper.fromThrowableName(t.javaClass.name, t.message)
    }

    // （反射 shutdown ws 线程池路径已删除）：入站恒 webhook + 自研 ws，
    //    SDK 侧 rawWsClient 恒 null——无 ws 线程池存在，反射路径无对象可关。

}
