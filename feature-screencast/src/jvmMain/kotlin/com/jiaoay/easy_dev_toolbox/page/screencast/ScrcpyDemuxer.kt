package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * demuxer 事件：12B 帧头槽位判型后的一次读取产物。
 */
sealed interface ScrcpyStreamEvent {

    /**
     * 会话包：占用 12B 帧头槽位本身（bit63 SESSION 标记，无独立载荷）——
     * byte3 bit0 = client_resized、byte4-7 = width、byte8-11 = height。
     * server 在编码启动与分辨率变化时发出（`SurfaceEncoder` 每次 reset 后写），
     * 首个会话包给出画面初始尺寸。
     */
    data class SessionMeta(
        val width: Int,
        val height: Int,
        val isClientResize: Boolean,
    ) : ScrcpyStreamEvent

    /**
     * config 包（bit62 CONFIG）：MediaCodec csd（H.264 为 SPS/PPS Annex-B），
     * 解码器初始化必需，恒先于首个视频帧。
     */
    class Config(val payload: ByteArray) : ScrcpyStreamEvent

    /** 视频帧：裸 MediaCodec 码流包（Annex-B），[ptsUs] 为微秒展示时间戳 */
    class Frame(
        val ptsUs: Long,
        val keyFrame: Boolean,
        val payload: ByteArray,
    ) : ScrcpyStreamEvent
}

/**
 * 视频流 demuxer：握手（含 4B codec id）之后的逐包解析，读序与官方 C 端
 * `app/src/demuxer.c` sc_demuxer 逐段对齐：
 *
 * 1. 读 12B 帧头；首字节 bit7（= 64 位读的 bit63）置位为会话包——帧头槽位即会话包本身，
 *    解析 client_resized / width / height 后返回，不另读载荷；
 * 2. 否则为普通包：8B 大端 pts_and_flags（bit62 = CONFIG、bit61 = KEY_FRAME、低 61 位 pts）
 *    + 4B 大端 packet size，再读 size 字节裸码流载荷。
 *
 * 防御契约（坏流必须显式失败，不得产半包）：
 * - packet size ≤ 0 抛 [IOException]（C 端 "Invalid packet length: 0" 同款口径）；
 * - 流在包中途截断由 readFully 抛 `EOFException`；仅「干净 EOF」（帧头首字节即 EOF）返回 null。
 *
 * 线程模型：单线程顺序消费（一个连接一个 demuxer 实例），实例不可多线程共用。
 */
class ScrcpyDemuxer(input: InputStream) {

    private val data = DataInputStream(input)
    private val header = ByteArray(ScrcpyProtocol.FRAME_HEADER_SIZE)

    /**
     * 读下一个事件；干净 EOF 返回 null，坏流抛 [IOException] / `EOFException`。
     *
     * 停止方关闭 socket（用户 stop / server 消亡的解除阻塞手法）会令阻塞中的读抛
     * [SocketException]——这是**终止信号**而非坏流，与干净 EOF 同归 null：消费方
     * （引擎）在自身层区分「主动停止」与「连接断开」，demuxer 不掺语义。其他
     * IOException（真正的流损坏）仍照抛。
     */
    fun next(): ScrcpyStreamEvent? {
        // 帧头首字节单独读以区分干净 EOF 与包中途截断（后者 readFully 抛 EOFException）
        val first = try {
            data.read()
        } catch (e: SocketException) {
            return null
        }
        if (first == -1) return null
        header[0] = first.toByte()
        try {
            data.readFully(header, 1, ScrcpyProtocol.FRAME_HEADER_SIZE - 1)
        } catch (e: SocketException) {
            return null
        }

        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val sessionMarker = (header[0].toInt() and 0x80) != 0
        if (sessionMarker) {
            val flags = buf.getInt(0)
            val width = buf.getInt(4)
            val height = buf.getInt(8)
            val isClientResize = (flags and ScrcpyProtocol.SESSION_FLAG_CLIENT_RESIZE) != 0
            return ScrcpyStreamEvent.SessionMeta(width, height, isClientResize)
        }

        val ptsAndFlags = buf.getLong(0)
        val size = buf.getInt(8)
        if (size <= 0) {
            throw IOException("Invalid packet size: $size")
        }
        val payload = ByteArray(size)
        data.readFully(payload)
        return when {
            ptsAndFlags and ScrcpyProtocol.FLAG_CONFIG != 0L -> ScrcpyStreamEvent.Config(payload)
            else -> ScrcpyStreamEvent.Frame(
                ptsUs = ptsAndFlags and ScrcpyProtocol.PTS_MASK,
                keyFrame = ptsAndFlags and ScrcpyProtocol.FLAG_KEY_FRAME != 0L,
                payload = payload,
            )
        }
    }
}
