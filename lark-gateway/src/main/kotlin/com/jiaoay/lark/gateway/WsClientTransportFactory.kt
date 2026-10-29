// :lark-gateway —— 自研 WS 传输生产实装。
// 把 :lark-ws 的 WsClient 适配成 [WsInboundTransport]（注入缝的生产实现）：
// WsInboundTransportFactory → 每次 create 新建 WsClient（close 是终态，连接不跨 connect 复用）；
// WsState stateFlow → WsStateListener 回调（Reconnecting/Reconnected 两态，其余态由网关直管）。
// 置于 :lark-gateway 而非 :lark-bridge：装配属网关消费面——:lark-bridge 无需依赖 :lark-ws
//    （LarkSdkIsolationTest 的 D 组方向守护与依赖图均不变）。
package com.jiaoay.lark.gateway

import com.jiaoay.lark.ws.JdkWsConnection
import com.jiaoay.lark.ws.WsClient
import com.jiaoay.lark.ws.WsConnection
import com.jiaoay.lark.ws.WsInboundEvent
import com.jiaoay.lark.ws.WsLogLevel
import com.jiaoay.lark.ws.WsState
import java.net.http.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * WsClient → WsInboundTransport 的生产适配器工厂。
 *
 * @param dispatcher WsClient 内部协程调度器（生产 = [kotlinx.coroutines.Dispatchers.IO]；
 *   🔴 不复用引擎注入的 bridge-net 池——ws 传输的 bootstrap/心跳/分发与 SDK 阻塞调用是两类负载，
 *   各自隔离防互相饿死；与「网关不自建 net 池」不冲突：Dispatchers.IO 是 JDK 公共池非自建池）
 */
class WsClientTransportFactory(
    private val dispatcher: CoroutineDispatcher,
) : WsInboundTransportFactory {

    override fun create(
        credential: LarkCredential,
        eventHandler: suspend (WsInboundEvent) -> String?,
        stateListener: WsStateListener,
        domain: String,
    ): WsInboundTransport = WsClientTransport(
        WsClient(
            appId = credential.appId,
            appSecret = credential.appSecret,
            domain = domain,
            dispatcher = dispatcher,
            connectionFactory = wsConnectionFactory(),
            bootstrapHttp = ::bootstrapHttpPost,
            eventHandler = eventHandler,
            // 必须传 log：漏传 ⇒ WsClient 默认空操作，协议层（bootstrap/握手/断连/重连/帧丢弃）
            //    零日志——断连静默不可见，「已失联但对外仍 Connected」时无任何线索可查。
            //    接 slf4j（gateway 域唯一日志门面）。
            log = ::wsLog,
        ),
        stateListener,
        dispatcher,
    )

    /**
     * 🔴 bootstrap 复用单例 HttpClient（不用 `HttpClient.newHttpClient()`）：
     * 1. **连接超时可设**——`newHttpClient()` 无 connectTimeout（JDK 默认无限），
     *    代理/防火墙静默 drop 时 `send()` 永久挂起 ⇒ 重连循环永不进入。设 15s 后
     *    超时抛 HttpConnectTimeoutException，正常进 WsClient 的重连循环。
     * 2. **消除每次重连的线程重建**——HttpClientImpl 自带 selector 线程 + executor；
     *    HttpClient 本就设计为长生命周期、线程安全、可共享。
     * 🔴 该实例与 [wsConnectionFactory] 的连接复用**同一个** client——bootstrap 与握手
     *    共享连接池与超时配置（一次重连不再新建两个 HttpClient；各建一套则每次重连多泄漏一份 selector/executor 线程）。
     */
    private val sharedHttpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .build()

    /** JDK HttpClient 生产注入（bootstrapHttp 缝的生产端）：POST JSON、读响应体。 */
    private suspend fun bootstrapHttpPost(url: String, jsonBody: String): String {
        val req = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody, Charsets.UTF_8))
            .build()
        val resp = sharedHttpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            throw WsBootstrapHttpFailed(resp.statusCode(), resp.body().take(200))
        }
        return resp.body()
    }

    private fun wsConnectionFactory(): () -> WsConnection = {
        JdkWsConnection(sharedHttpClient)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(WsClientTransportFactory::class.java)

        /** 连接超时（15s：弱网容忍 + 黑洞场景可自愈的折中值；与握手超时同口径）。 */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** WsLogLevel → slf4j 翻译（:lark-ws 零日志依赖——经缝由 gateway 侧落地）。 */
        private fun wsLog(level: WsLogLevel, message: String) {
            when (level) {
                WsLogLevel.DEBUG -> log.debug("WsClient: {}", message)
                WsLogLevel.INFO -> log.info("WsClient: {}", message)
                WsLogLevel.WARN -> log.warn("WsClient: {}", message)
                WsLogLevel.ERROR -> log.error("WsClient: {}", message)
            }
        }
    }
}

/** bootstrap HTTP 非 200（端点不可达/网关 5xx 等——WsClient 会转重连循环重试）。 */
class WsBootstrapHttpFailed(val statusCode: Int, val body: String) :
    Exception("bootstrap http $statusCode: $body")

/**
 * WsClient → WsInboundTransport 适配器：start/awaitConnected/close 的窄投影 +
 * WsState stateFlow → stateListener 的回调翻译（Reconnecting/Reconnected）。
 */
internal class WsClientTransport(
    private val client: WsClient,
    private val stateListener: WsStateListener,
    private val dispatcher: CoroutineDispatcher,
) : WsInboundTransport {

    private var watcher: Job? = null

    override fun start() {
        // 状态观察协程：WsState 变化 → stateListener 回调。独立小 scope（非 WsClient 内部 scope——
        // 那是 private）：close() 先 cancel 本 watcher 再关 client，顺序明确、无悬挂回调。
        val watchScope = CoroutineScope(SupervisorJob() + dispatcher)
        watcher = watchScope.launch {
            var seenConnected = false
            client.stateFlow.collect { s ->
                when {
                    s is WsState.Reconnecting -> stateListener.onReconnecting()
                    // 首连由 awaitConnected 判定（不走信号——connect 流程已同步等待）；
                    // 已连过一次之后的 Connected = 重连回来 ⇒ onReconnected
                    s is WsState.Connected && seenConnected -> stateListener.onReconnected()
                    else -> Unit
                }
                if (s is WsState.Connected) seenConnected = true
            }
        }
        client.start()
    }

    override suspend fun awaitConnected(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (client.stateFlow.value) {
                is WsState.Connected -> return true
                is WsState.Closed -> return false
                else -> delay(50)
            }
        }
        return client.stateFlow.value is WsState.Connected
    }

    override fun close() {
        runCatching { watcher?.cancel() }
        client.close()
    }
}
