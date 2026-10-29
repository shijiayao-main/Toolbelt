// :lark-ws —— 测试共用：假连接 + 内存传输（WsClient 全逻辑离线可测的关键件）。
// 模拟：连接生命周期回调注入 / 已发送帧记录 / 按需触发 onBinary（含合包分片场景）。
package com.jiaoay.lark.ws

import java.util.concurrent.ConcurrentLinkedQueue

/** 测试用假连接：回调与发送记录全在内存，由用例手动驱动。 */
class FakeWsConnection : WsConnection {
    val sentFrames = ConcurrentLinkedQueue<ByteArray>()

    /** close 调用计数（幂等断言用——真实现二次 close 不应重复触发连接关闭）。 */
    val closeCalls = java.util.concurrent.atomic.AtomicInteger(0)

    /** 用例注入的监听（connect 时挂接）。 */
    var listener: WsConnectionListener? = null

    /** connect() 将要抛的异常（非 null 时模拟握手失败）。 */
    var connectError: Throwable? = null

    @Volatile private var open = false

    override val isOpen: Boolean get() = open

    override fun connect(url: String, listener: WsConnectionListener) {
        this.listener = listener
        connectError?.let { throw it }
        open = true
        listener.onOpen()
    }

    override fun send(message: ByteArray): Boolean {
        if (!open) return false
        sentFrames.add(message)
        return true
    }

    override fun close(code: Int, reason: String) {
        closeCalls.incrementAndGet()
        open = false
    }

    // ---------- 用例驱动辅助 ----------

    /** 模拟服务端推送一整条二进制消息。 */
    fun serverPush(bytes: ByteArray) {
        listener?.onBinary(bytes)
    }

    /** 模拟服务端关闭（触发重连路径）。 */
    fun serverClose(code: Int = 1000, reason: String = "server closed") {
        open = false
        listener?.onClosed(code, reason)
    }

    /** 模拟连接异常（触发重连路径）。 */
    fun serverFail(t: Throwable = RuntimeException("connection reset")) {
        open = false
        listener?.onFailure(t)
    }

    /** 解码已发送帧（断言辅助）。 */
    fun decodedSentFrames(): List<WsFrame> = sentFrames.map { WsFrameCodec().decode(it) }
}
