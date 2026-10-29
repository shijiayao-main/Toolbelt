// :lark-ws —— 连接抽象：WsClient 全部逻辑（重连/合包/心跳/回执）离线可测的关键缝。
// 生产实现 [JdkWsConnection]（java.net.http.WebSocket）；测试用 FakeWsConnection。
package com.jiaoay.lark.ws

import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** 连接事件（WsConnection → WsClient）。 */
interface WsConnectionListener {
    /** 握手完成（等价 SDK Listener.onOpen）。 */
    fun onOpen()

    /** 收到一整条二进制消息（已拼片；等价 SDK Listener.onMessage(ByteString)）。 */
    fun onBinary(message: ByteArray)

    /** 服务端关闭（等价 SDK Listener.onClosed；SDK 在此触发重连）。 */
    fun onClosed(code: Int, reason: String)

    /** 异常/失败（等价 SDK Listener.onFailure；含握手失败——[WsConnectionOpenException]）。 */
    fun onFailure(error: Throwable)
}

/**
 * WebSocket 连接抽象：`connect` / `send` / `close` + 监听回调挂接。
 * JDK WebSocket 的 `onBinary(ByteBuffer, last)` 分片语义由实现内部拼装完毕再上抛整条——
 *    [WsConnectionListener.onBinary] 只见完整消息（与 SDK 的 ByteString 语义等价）。
 */
interface WsConnection {
    /** 发起连接（同步等待握手结果；失败抛 [WsConnectionOpenException] 或其他异常）。 */
    fun connect(url: String, listener: WsConnectionListener)

    /** 发送一条二进制消息（阻塞至入队；连接关闭后返回 false）。 */
    fun send(message: ByteArray): Boolean

    /** 主动关闭（code 1000）；幂等。 */
    fun close(code: Int, reason: String)

    /** 是否仍打开。 */
    val isOpen: Boolean
}

/** 握手失败（携带握手响应——`handshake-status`/`handshake-msg` header 在其中，供 514/403 分类）。 */
class WsConnectionOpenException(
    val statusCode: Int?,
    val headers: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * JDK 标准库 WebSocket 生产实现（jvmTarget 11 直接可用，零第三方依赖）。
 * 一个实例一次连接生命周期（重连由 WsClient 新建实例——与 SDK ws.Client 每次重连
 *    `new OkHttpClient` 复用不同，这里每次新连接新实例，旧实例随 close 回收）。
 */
class JdkWsConnection(private val httpClient: HttpClient) : WsConnection {

    // 🔴 @Volatile 必须：JDK 回调线程写（onClose/onError）+ 心跳协程在 IO 线程读
    //    （ping 循环 isOpen 判定）——无 happens-before 是真实数据竞争。
    @Volatile
    override var isOpen: Boolean = false
        private set

    private var webSocket: WebSocket? = null
    private val sendLock = Any()

    /** 分片拼装缓冲（JDK onBinary 的 last 分片语义；一条消息结束即清空）。 */
    private val fragments = java.util.ArrayDeque<ByteArray>()

    override fun connect(url: String, listener: WsConnectionListener) {
        val jdkListener = object : WebSocket.Listener {
            // 🔴 分片拼装：JDK 语义是一条消息可能多片，按 last 拼齐再上抛整条——逐片上抛即协议帧被截断、解码错乱
            override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletableFuture<*> {
                val copy = ByteArray(data.remaining()).also { data.get(it) }
                synchronized(this@JdkWsConnection) { fragments.addLast(copy) }
                if (last) {
                    val whole = synchronized(this@JdkWsConnection) { assemble() }
                    listener.onBinary(whole)
                }
                // 🔴 JDK WebSocket 是**计数制投递**——每个 receive 回调消耗一次计数，不 request
                //    就一个回调都拿不到（默认 onOpen/onBinary 实现内含 request(1)，覆写时丢了它
                //    ⇒ onOpen 后零投递：握手成功但事件/关闭全静默，重连循环也不触发——
                //    见 JBR src.zip WebSocket.Listener）。
                webSocket.request(1)
                @Suppress("NULLABILITY_ON_PLATFORM_TYPE")
                return CompletableFuture.completedFuture<Any>(null)
            }

            override fun onOpen(webSocket: WebSocket) {
                // 🔴 同上：默认 onOpen = request(1)（投递启动器）——覆写必须补
                webSocket.request(1)
                listener.onOpen()
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*> {
                isOpen = false
                listener.onClosed(statusCode, reason)
                @Suppress("NULLABILITY_ON_PLATFORM_TYPE")
                return CompletableFuture.completedFuture<Any>(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                isOpen = false
                listener.onFailure(error)
            }
        }
        try {
            // 🔴 握手必须显式设超时——JDK 的 newWebSocketBuilder() **不继承** HttpClient 的
            //    connectTimeout，未调用 connectTimeout() 时 javadoc 明言「假定无限超时」。
            //    企业代理/防火墙静默 drop SYN 时，buildAsync().join() 永久挂起 ⇒ onClosed/
            //    onFailure 都不触发 ⇒ 重连循环永不进入，UI 停在「重连中」。超时后抛
            //    HttpConnectTimeoutException/CompletionException，
            //    被 toOpenException 归一为 WsConnectionOpenException，正常进重连循环。
            val ws = httpClient.newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .buildAsync(java.net.URI.create(url), jdkListener)
                .join()
            webSocket = ws
            isOpen = true
        } catch (e: CompletionException) {
            val cause = e.cause ?: e
            throw toOpenException(cause)
        } catch (e: Throwable) {
            throw toOpenException(e)
        }
    }

    override fun send(message: ByteArray): Boolean {
        val ws = webSocket ?: return false
        synchronized(sendLock) {
            return try {
                ws.sendBinary(java.nio.ByteBuffer.wrap(message), true).join()
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    override fun close(code: Int, reason: String) {
        isOpen = false
        runCatching { webSocket?.sendClose(code, reason) }
        webSocket = null
    }

    private fun assemble(): ByteArray {
        if (fragments.size == 1) return fragments.removeFirst()
        var total = 0
        for (f in fragments) total += f.size
        val out = ByteArray(total)
        var offset = 0
        while (fragments.isNotEmpty()) {
            val f = fragments.removeFirst()
            System.arraycopy(f, 0, out, offset, f.size)
            offset += f.size
        }
        return out
    }

    companion object {
        /** 握手超时（15s：弱网容忍 + 黑洞场景可自愈的折中值）。 */
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private fun toOpenException(t: Throwable): Throwable = when (t) {
        is WsConnectionOpenException -> t
        is WebSocketHandshakeException -> WsConnectionOpenException(
            statusCode = t.response?.statusCode(),
            headers = t.response?.headers()?.map()?.mapKeys { it.key.lowercase() }?.mapValues { it.value.firstOrNull() ?: "" }
                ?: emptyMap(),
            message = "websocket handshake failed: ${t.message}",
            cause = t,
        )
        else -> WsConnectionOpenException(
            statusCode = null,
            headers = emptyMap(),
            message = "websocket connect failed: ${t.message}",
            cause = t,
        )
    }
}
