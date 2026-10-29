// :lark-ws 测试 —— 🔴 2026-09-13 真机失联事故的根因回归防线：
// JdkWsConnection 曾覆写 onOpen/onBinary 时丢失 JDK 默认实现的 webSocket.request(1)——
// JDK WebSocket 是**计数制投递**，不 request 则 onOpen 之后零回调（握手成功但事件/关闭全静默，
// 服务端因无回执断连后重连也不触发——「已失联但对外 Connected」）。
// FakeWsConnection 测不到这条（它不经过 JDK）；必须用真 JDK WebSocket 走一次完整握手 + 多帧投递。
// 零第三方依赖：服务端用裸 ServerSocket 手写 RFC6455 升级握手 + 帧发送（本测试内联实现；
// com.sun.net.httpserver 的 101 之后响应流会被框架关闭，不适合承载 WS——首版实测踩坑）。
package com.jiaoay.lark.ws

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JdkWsConnection 对真 JDK WebSocket 的投递语义测试（多帧 + 关闭回调）。
 * 🔴 变异即红：把 onOpen/onBinary 里的 webSocket.request(1) 删掉，本测试必红
 *    （首帧都收不到——latch 超时）。
 */
class JdkWsConnectionDeliveryTest {

    @Test
    fun `多帧投递与关闭回调真实生效（request 计数制回归防线）`() {
        val server = MiniWssServer()
        server.start()
        try {
            val conn = JdkWsConnection(java.net.http.HttpClient.newHttpClient())
            val opened = CountDownLatch(1)
            val frames = java.util.Collections.synchronizedList(ArrayList<ByteArray>())
            val closed = CountDownLatch(1)
            conn.connect(
                "ws://127.0.0.1:${server.port}/ws",
                object : WsConnectionListener {
                    override fun onOpen() { opened.countDown() }

                    override fun onBinary(message: ByteArray) { frames.add(message) }

                    override fun onClosed(code: Int, reason: String) { closed.countDown() }

                    override fun onFailure(error: Throwable) { /* 服务端主动关闭走 onClosed */ }
                },
            )
            assertTrue(opened.await(5, TimeUnit.SECONDS), "onOpen 必须投递（JDK 初始计数只够 onOpen）")

            // 服务端连发 3 条二进制消息（多帧——每条一个独立 ws 帧）。
            // 修复前：客户端一个 onBinary 都收不到（request 计数在 onOpen 后归零）。
            server.sendBinary(byteArrayOf(1, 2, 3))
            server.sendBinary(byteArrayOf(4, 5))
            server.sendBinary(byteArrayOf(6))
            assertTrue(
                awaitFrameCount(frames, 3, 5),
                "3 条二进制消息必须全部投递——request(1) 缺失时首帧即丢（真机失联根因），实际收到=${frames.size}",
            )
            assertTrue(frames[0].contentEquals(byteArrayOf(1, 2, 3)), "首帧内容一致")
            assertTrue(frames[1].contentEquals(byteArrayOf(4, 5)), "第二帧内容一致")
            assertTrue(frames[2].contentEquals(byteArrayOf(6)), "第三帧内容一致")

            // 关闭回调也走投递计数——修复前服务端关闭后客户端 onClose 永不触发（重连死锁的另一半）。
            server.sendClose(1000)
            assertTrue(closed.await(5, TimeUnit.SECONDS), "onClose 必须投递（缺失则断连静默、重连永不触发）")
        } finally {
            server.stop()
        }
    }

    private fun awaitFrameCount(frames: List<ByteArray>, n: Int, timeoutSec: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            if (frames.size >= n) return true
            Thread.sleep(20)
        }
        return frames.size >= n
    }

    /**
     * 极简 RFC6455 服务端（只够本测试用）：裸 ServerSocket 读 HTTP 升级请求 → 回 101 → 保留原始流发帧。
     * 纯 JDK，零第三方依赖；不做掩码解析（客户端回写只 drain 不解析）。
     */
    private class MiniWssServer {
        private val serverSocket = ServerSocket(0, 1, InetSocketAddress("127.0.0.1", 0).address)
        val port: Int get() = serverSocket.localPort
        private var socket: Socket? = null
        private var out: OutputStream? = null

        fun start() {
            val acceptThread = Thread {
                runCatching {
                    val sock = serverSocket.accept()
                    socket = sock
                    // 读 HTTP 升级请求头（到空行）
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                    var key: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val lower = line.lowercase()
                        if (lower.startsWith("sec-websocket-key:")) key = line.substringAfter(':').trim()
                    }
                    val accept = java.util.Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-1")
                            .digest(("${key ?: ""}258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8)),
                    )
                    val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    val o = sock.getOutputStream()
                    o.write(response.toByteArray(Charsets.UTF_8))
                    o.flush()
                    out = o
                    // 客户端可能回写（close 帧）——drain 防 TCP 缓冲顶满；掩码帧不解析
                    Thread {
                        try {
                            val buf = ByteArray(4096)
                            val input = sock.getInputStream()
                            while (input.read(buf) != -1) { /* drain */ }
                        } catch (_: Throwable) { /* 客户端断开 */ }
                    }.apply { isDaemon = true }.start()
                }
            }
            acceptThread.isDaemon = true
            acceptThread.start()
            // 不忙等：JDK 客户端 buildAsync 会重试连接；ServerSocket 已 listen，accept 阻塞等待即可。
        }

        fun sendBinary(payload: ByteArray) {
            val o = out ?: return
            synchronized(this) {
                o.write(0x82) // FIN=1 | opcode=2（binary）
                writeLength(o, payload.size)
                o.write(payload)
                o.flush()
            }
        }

        fun sendClose(statusCode: Int) {
            val o = out ?: return
            synchronized(this) {
                o.write(0x88) // FIN=1 | opcode=8（close）
                val body = byteArrayOf((statusCode shr 8).toByte(), statusCode.toByte())
                writeLength(o, body.size)
                o.write(body)
                o.flush()
            }
        }

        private fun writeLength(o: OutputStream, len: Int) {
            when {
                len < 126 -> o.write(len)
                len < 65536 -> {
                    o.write(126)
                    o.write(len shr 8)
                    o.write(len and 0xFF)
                }
                else -> {
                    o.write(127)
                    val b = java.nio.ByteBuffer.allocate(8).putLong(len.toLong()).array()
                    o.write(b)
                }
            }
        }

        fun stop() {
            runCatching { socket?.close() }
            runCatching { serverSocket.close() }
        }
    }
}
