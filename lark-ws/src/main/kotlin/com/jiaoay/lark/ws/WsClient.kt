// :lark-ws —— 飞书长连接客户端核心（com.lark.oapi.ws.Client 的 Kotlin 重实现）。
// 逐段对照 oapi-sdk-java v2.8.5 源码（ws/Client.java：bootstrap 318-354 / connect 410-424 /
// handleMessage 426-442 / pingLoop 171-190 / reconnect 207-256 / combine 520-543）。
// 根治 SDK 三重缺陷：
//   ① 线程泄漏——SDK Executors.newFixedThreadPool(2*CPU+1) 永不 shutdown ⇒ 本实现零自建线程池，
//      全协程 + close() = scope.cancel()（一次取消全回收，反射 shutdown 的根治路径）；
//   ② 无背压——SDK executor 队列无界无取消 ⇒ WsDispatcher Channel 背压 + 满载可观测；
//   ③ 合包静默丢弃——SDK Guava cache 5min 过期无信号 ⇒ 超时清扫 WARN 含 message_id。
package com.jiaoay.lark.ws

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 连接状态（StateFlow 形态——gateway 侧可直译 GatewayConnectionState）。 */
sealed interface WsState {
    /** 未启动。 */
    object Idle : WsState

    /** 连接中（bootstrap + 握手）。 */
    object Connecting : WsState

    /** 已连接。 */
    object Connected : WsState

    /** 重连中。 */
    object Reconnecting : WsState

    /** 终态关闭（幂等 close 后）。 */
    data class Closed(val reason: String) : WsState
}

/** 事件回调：传输层只递 [WsInboundEvent]，业务语义（mention 剥离等）在 gateway 翻译层。 */
typealias WsEventHandler = suspend (WsInboundEvent) -> String?

/**
 * 飞书长连接客户端。
 *
 * 生命周期：[start] →（内部：bootstrap → connect → ping 协程）→ [close]（幂等、全回收）。
 * 重连：onClosed/onFailure → 随机抖动 → 循环重连（reconnect 参数在循环入口一次快照
 * ——pong 热更新对在途重连循环不生效；PingInterval 在 ping 循环内逐轮现取，热更新当轮生效）。
 *
 * @param connectionFactory 每次连接新建 [WsConnection]（连接实例不跨重连复用——与 SDK
 *   每次 reconnect 走 getConnUrl 相对齐；测试注入 FakeWsConnection 工厂）
 * @param bootstrapHttp 注入缝：`suspend (url, jsonBody) -> 响应体文本`（生产 JDK HttpClient，
 *   测试 lambda——零网络依赖）
 * @param randomSeed 随机源（重连抖动；测试注入固定值）
 * @param clock 时钟注入（清扫协程与日志；测试虚拟时间）
 */
class WsClient(
    private val appId: String,
    private val appSecret: String,
    private val domain: String = DEFAULT_DOMAIN,
    dispatcher: CoroutineDispatcher,
    private val connectionFactory: () -> WsConnection,
    private val bootstrapHttp: suspend (String, String) -> String,
    private val eventHandler: WsEventHandler,
    private val dispatcherCapacity: Int = WsDispatcher.DEFAULT_CAPACITY,
    private val log: (WsLogLevel, String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val randomSeed: () -> Int = { kotlin.random.Random.nextInt() },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)
    private val state = MutableStateFlow<WsState>(WsState.Idle)
    private val config = AtomicReference(WsRuntimeConfig())
    private val dispatcherRef = AtomicReference<WsDispatcher?>(null)
    private val connection = AtomicReference<WsConnection?>(null)
    private val connUrl = AtomicReference<String?>(null)
    private val serviceId = AtomicReference<String?>(null)
    private val assembling = ConcurrentHashMap<String, AssembleBuffer>()

    // 🔴 协程生命周期契约：ping Job 逐连接一组（重连先取消旧组再启新组，随连接关闭而终）；
    //    清扫协程**单例常驻**（它清理的 assembling 缓存是 client 级结构，与单条连接无关）——
    //    若不清扫协程随重连重建，每次重连会净增一个永久协程（僵尸协程同锁争用 + 日志翻倍）。
    private val keepAliveJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
    @Volatile
    private var sweepJob: Job? = null

    val stateFlow: StateFlow<WsState> = state.asStateFlow()

    /** 心跳与重连参数（bootstrap ClientConfig 与 pong 热更新共同落点）。 */
    private data class WsRuntimeConfig(
        val reconnectCount: Int = -1,
        val reconnectIntervalSec: Int = 120,
        val reconnectNonceSec: Int = 30,
        val pingIntervalSec: Int = 120,
    )

    private class AssembleBuffer(val sum: Int, val parts: Array<ByteArray?>, val createdAt: Long)

    // ==================== 启动 / 关闭 ====================

    /**
     * 启动：bootstrap → 连接 → 心跳协程 + 清扫协程。已在运行则幂等返回。
     * 首连失败**同样进入重连循环**（对照 SDK start()：connect 抛错后 shouldReconnect() 为真
     *    即走 reconnect()——不区分首连与重连）。bootstrap 阶段的失败在重连循环内每次重试。
     */
    fun start() {
        check(!closed.get()) { "client closed" }
        val fresh = state.value
        if (fresh is WsState.Connecting || fresh is WsState.Connected || fresh is WsState.Reconnecting) return
        state.value = WsState.Connecting
        scope.launch {
            try {
                connectOnce()
                startKeepAlives()
            } catch (t: Throwable) {
                // 与 SDK 对齐：start 期间的 connect 失败不直接终态化，交给重连循环
                log(WsLogLevel.WARN, "首连失败转入重连: ${t.message}")
                reconnectLoop()
            }
        }
    }

    /** 关闭（幂等）：cancel scope（心跳/清扫/分发全回收）+ 关连接。零反射——对比 SDK 泄漏路径。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        state.value = WsState.Closed("client closed")
        runCatching { connection.get()?.close(1000, "client closed") }
        dispatcherRef.get()?.close()
        scope.cancel()
        assembling.clear()
    }

    // ==================== bootstrap（对照 getConnUrl / prepareBootstrapRequest）====================

    private suspend fun bootstrapUrl(): String {
        // 请求体是手写 JSON（现役事实源——WsBootstrapRequest 模型不驱动请求体）
        val bodyJson = buildString {
            append("{\"AppID\":").append(JsonPrimitive(appId))
            append(",\"AppSecret\":").append(JsonPrimitive(appSecret))
            append(",\"ClientAssertion\":\"\"}")
        }
        val respText = bootstrapHttp("$domain$ENDPOINT_PATH", bodyJson)
        val resp = json.decodeFromString(WsEndpointResp.serializer(), respText)
        when (resp.code) {
            WsEndpointCodes.OK -> Unit
            WsEndpointCodes.SYSTEM_BUSY -> throw WsBootstrapException(resp.code, "system busy")
            WsEndpointCodes.INTERNAL_ERROR -> throw WsBootstrapException(resp.code, resp.msg ?: "internal error")
            else -> throw WsBootstrapException(resp.code, resp.msg ?: "bootstrap rejected")
        }
        val endpoint = resp.data ?: throw WsBootstrapException(resp.code, "bootstrap has no endpoint data")
        applyConfig(endpoint.clientConfig)
        return endpoint.url
    }

    // ==================== 连接（对照 connect / Listener）====================

    private suspend fun connectOnce() {
        val url = bootstrapUrl()
        connUrl.set(url)
        serviceId.set(queryParam(url, "service_id"))
        val conn = connectionFactory()
        val listener = object : WsConnectionListener {
            override fun onOpen() {
                state.value = WsState.Connected
                log(WsLogLevel.INFO, "ws connected url=${maskUrl(url)}")
            }

            override fun onBinary(message: ByteArray) {
                runCatching { handleFrame(message) }
                    .onFailure { log(WsLogLevel.ERROR, "frame 处理异常: ${it.javaClass.simpleName} ${it.message}") }
            }

            override fun onClosed(code: Int, reason: String) {
                log(WsLogLevel.INFO, "ws closed code=$code reason=$reason")
                if (closed.get()) return
                scope.launch { reconnectLoop() }
            }

            override fun onFailure(error: Throwable) {
                log(WsLogLevel.WARN, "ws failure: ${error.javaClass.simpleName} ${error.message}")
                if (closed.get()) return
                scope.launch { reconnectLoop() }
            }
        }
        connection.set(conn)
        conn.connect(url, listener)
    }

    /** 重连循环（对照 reconnect：首次随机抖动 → 按 interval 重试；reconnect 参数入口一次快照，热更新对在途循环不生效）。 */
    private suspend fun reconnectLoop() {
        if (closed.get()) return
        state.value = WsState.Reconnecting
        val cfg = config.get()
        // 首次重连随机抖动（对照 SDK：rand.nextInt(reconnectNonce * 1000) ms）
        if (cfg.reconnectNonceSec > 0) {
            delay((randomSeed().mod(cfg.reconnectNonceSec * 1000)).toLong())
        }
        var attempt = 0
        while (!closed.get()) {
            if (cfgLimit(cfg) && attempt >= cfg.reconnectCount) {
                state.value = WsState.Closed("reconnect exhausted after $attempt attempts")
                return
            }
            runCatching { connectOnce(); startKeepAlives(); return }
                .onFailure {
                    log(WsLogLevel.INFO, "reconnect 第 ${attempt + 1} 次失败: ${it.message}")
                    state.value = WsState.Reconnecting
                }
            delay(cfg.reconnectIntervalSec * 1000L)
            attempt++
        }
    }

    private fun cfgLimit(cfg: WsRuntimeConfig): Boolean = cfg.reconnectCount >= 0

    // ==================== 心跳（对照 pingLoop：连接后 2s 起跳）====================

    private fun startKeepAlives() {
        val currentConn = connection.get() ?: return
        val sid = serviceId.get()?.toIntOrNull() ?: 0
        val pingFrame = WsFrameCodec().encode(
            WsFrame(
                seqId = 0,
                logId = 0,
                service = sid,
                method = WsFrameMethod.CONTROL.code,
                headers = listOf(WsHeader(HEADER_TYPE, WsMessageType.PING.wireName)),
            ),
        )
        // 🔴 先取消上一组 ping 协程再启动新的（每连接一组）——旧协程退出前最多再等一个
        //    pingInterval（默认 120s）才轮询到，期间持有旧连接引用并继续 WARN。
        for (job in keepAliveJobs) runCatching { job.cancel() }
        keepAliveJobs.clear()
        val pingJob = scope.launch {
            delay(PING_INITIAL_DELAY_MS)
            // ping 循环逐轮取配置（不快照）：pong 热更新 PingInterval 后当轮已过的 delay
            //    不受影响，下一轮立即生效（与 SDK pingLoop 逐轮读字段语义一致）。
            //    连接是闭包捕获的当轮快照 currentConn——残留协程不向新连接发 ping 的
            //    保障在 startKeepAlives 先取消上一组（每连接一组，见上方 cancel 注释），
            //    非逐轮现取。
            while (!closed.get() && currentConn.isOpen) {
                val sent = runCatching { currentConn.send(pingFrame) }.getOrDefault(false)
                if (!sent) log(WsLogLevel.WARN, "ping 发送失败（连接已关？）")
                delay(config.get().pingIntervalSec * 1000L)
            }
        }
        keepAliveJobs.add(pingJob)
        // 清扫协程单例常驻（compareAndSet 保证只启动一次）——见 keepAliveJobs 处契约。
        // 合包缓冲清扫（对照 Guava cache expireAfterWrite 5min——但丢弃必带 WARN）
        if (sweepJob == null) {
            synchronized(this) {
                if (sweepJob == null) {
                    sweepJob = scope.launch {
                        while (!closed.get()) {
                            delay(SWEEP_INTERVAL_MS)
                            val now = clock()
                            val expired = assembling.entries.filter { now - it.value.createdAt > PART_TTL_MS }
                            for ((id, _) in expired) {
                                assembling.remove(id)?.let {
                                    log(WsLogLevel.WARN, "合包超时丢弃 messageId=$id（分片 ${countFilled(it)}/${it.sum}）")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== 帧处理（对照 handleMessage / handleControlFrame / handleDataFrame）====================

    internal fun handleFrame(bytes: ByteArray) {
        val frame = WsFrameCodec().decode(bytes)
        when (WsFrameMethod.of(frame.method)) {
            WsFrameMethod.CONTROL -> handleControlFrame(frame)
            WsFrameMethod.DATA -> handleDataFrame(frame)
            null -> log(WsLogLevel.WARN, "未知 method=${frame.method}，帧丢弃")
        }
    }

    private fun handleControlFrame(frame: WsFrame) {
        when (WsMessageType.of(frame.header(HEADER_TYPE))) {
            WsMessageType.PING -> Unit // 对照 SDK：PING return
            WsMessageType.PONG -> {
                val payload = frame.payload ?: return
                runCatching { json.decodeFromString(WsClientConfig.serializer(), payload.toString(Charsets.UTF_8)) }
                    .onSuccess { applyConfig(it); log(WsLogLevel.INFO, "pong 热更新连接参数: $it") }
                    .onFailure { log(WsLogLevel.WARN, "pong ClientConfig 解析失败，忽略") }
            }
            else -> Unit
        }
    }

    private fun handleDataFrame(frame: WsFrame) {
        val messageId = frame.header(HEADER_MESSAGE_ID) ?: return
        val traceId = frame.header(HEADER_TRACE_ID) ?: ""
        val sum = frame.header(HEADER_SUM)?.toIntOrNull() ?: 1
        val seq = frame.header(HEADER_SEQ)?.toIntOrNull() ?: 0
        var payload = frame.payload ?: ByteArray(0)
        if (sum > 1) {
            val assembled = combine(messageId, sum, seq, payload) ?: return // 未齐包：缓存后直接返回
            payload = assembled
        }
        val type = frame.header(HEADER_TYPE)
        when (WsMessageType.of(type)) {
            WsMessageType.EVENT -> dispatchEvent(frame, messageId, payload)
            WsMessageType.CARD -> dispatchEvent(frame, messageId, payload) // card 与 event 同路分发（类型在 payload 里区分）
            else -> log(WsLogLevel.WARN, "未知 DATA 帧类型 type=$type messageId=$messageId，丢弃")
        }
        log(WsLogLevel.DEBUG, "收到数据帧 type=$type messageId=$messageId traceId=$traceId")
    }

    /** 合包（对照 combine：byte[][] 按 seq 填位，齐则拼接；丢弃时清扫协程补 WARN——SDK 缺陷③）。 */
    private fun combine(messageId: String, sum: Int, seq: Int, part: ByteArray): ByteArray? {
        require(seq in 0 until sum) { "seq $seq out of range [0,$sum) messageId=$messageId" }
        val buf = assembling.computeIfAbsent(messageId) { AssembleBuffer(sum, arrayOfNulls(sum), clock()) }
        require(buf.sum == sum) { "分片 sum 变更 messageId=$messageId: ${buf.sum} -> $sum" }
        buf.parts[seq] = part
        val joined = ByteArray(buf.parts.sumOf { it?.size ?: 0 })
        var offset = 0
        for (p in buf.parts) {
            if (p == null) return null // 未齐：留在缓存等后续分片
            System.arraycopy(p, 0, joined, offset, p.size)
            offset += p.size
        }
        assembling.remove(messageId)
        return joined
    }

    private fun dispatchEvent(frame: WsFrame, messageId: String, payload: ByteArray) {
        val d = dispatcherRef.updateAndGet { it ?: WsDispatcher(scopeDispatcher, dispatcherCapacity, log) } ?: return
        val codec = WsFrameCodec()
        val parser = WsEventParser(json)
        val startedAt = clock()
        val ok = d.submit(messageId) {
            // 🔴 三态结果（对照 SDK handleDataFrame）：异常→500；正常完成→200；
            //    handler 正常返回 null 只是「无回执载荷」（SDK 里 r==null 时不写 data 字段，code 仍 200）——
            //    骨架批曾把「返回 null」误判成 500（每条无返回值事件都回执失败、服务端会重发风暴）。
            var failed = false
            val ackPayload: String? = try {
                val event = parser.parse(payload)
                eventHandler(event)
            } catch (t: Throwable) {
                failed = true
                log(WsLogLevel.WARN, "事件处理失败 messageId=$messageId: ${t.javaClass.simpleName} ${t.message}")
                null
            }
            // 回执（对照 handleDataFrame：原帧 rebuild + biz_rt header + payload 换 ack JSON）
            val ack = WsAckResponse(code = if (failed) 500 else 200, data = ackPayload?.let { base64(it) })
            val ackBytes = json.encodeToString(WsAckResponse.serializer(), ack).toByteArray(Charsets.UTF_8)
            val ackFrame = frame.copy(
                headers = frame.headers + WsHeader(HEADER_BIZ_RT, (clock() - startedAt).toString()),
                payload = ackBytes,
            )
            runCatching { connection.get()?.send(codec.encode(ackFrame)) }
                .onFailure { log(WsLogLevel.WARN, "回执发送失败 messageId=$messageId") }
        }
        if (!ok) {
            // 队列满：回执 503（服务端重发语义），不静默
            val ack = WsAckResponse(code = 503)
            val ackFrame = frame.copy(payload = json.encodeToString(WsAckResponse.serializer(), ack).toByteArray(Charsets.UTF_8))
            runCatching { connection.get()?.send(codec.encode(ackFrame)) }
        }
    }

    private fun applyConfig(c: WsClientConfig?) {
        if (c == null) return
        config.updateAndGet { old ->
            old.copy(
                reconnectCount = c.reconnectCount ?: old.reconnectCount,
                reconnectIntervalSec = c.reconnectInterval ?: old.reconnectIntervalSec,
                reconnectNonceSec = c.reconnectNonce ?: old.reconnectNonceSec,
                pingIntervalSec = c.pingInterval ?: old.pingIntervalSec,
            )
        }
    }

    private fun countFilled(buf: AssembleBuffer): Int = buf.parts.count { it != null }

    private fun base64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** dispatcher 的调度器（scope 用同一 dispatcher；单独存引用供 WsDispatcher 构造）。 */
    private val scopeDispatcher: CoroutineDispatcher = dispatcher

    companion object {
        const val DEFAULT_DOMAIN = "https://open.feishu.cn"
        private const val ENDPOINT_PATH = "/callback/ws/endpoint"

        // header 常量（对照 ws/Constant.java）
        private const val HEADER_TYPE = "type"
        private const val HEADER_MESSAGE_ID = "message_id"
        private const val HEADER_TRACE_ID = "trace_id"
        private const val HEADER_SUM = "sum"
        private const val HEADER_SEQ = "seq"
        private const val HEADER_BIZ_RT = "biz_rt"

        private const val PING_INITIAL_DELAY_MS = 2000L // 对照 SDK pingLoop：sleep(2000) 起跳
        private const val PART_TTL_MS = 5 * 60 * 1000L // 对照 Guava cache expireAfterWrite(5, MINUTES)
        private const val SWEEP_INTERVAL_MS = 60 * 1000L // 清扫周期 1min（TTL/5，丢弃延迟 ≤ 1min）

        private fun queryParam(url: String, key: String): String? =
            url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

        private fun maskUrl(url: String): String =
            url.substringBefore('?') + "?device_id=***&service_id=***" // 凭据无关，只截 query 防噪音
    }
}

/** bootstrap 业务失败（对照 ServerException/ClientException 的 code/msg 携带）。 */
class WsBootstrapException(val code: Int, message: String) : Exception("bootstrap code=$code: $message")
