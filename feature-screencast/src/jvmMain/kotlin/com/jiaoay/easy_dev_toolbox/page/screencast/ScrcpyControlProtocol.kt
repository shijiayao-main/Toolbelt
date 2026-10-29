package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/**
 * scrcpy 控制通道 wire 契约常量。
 *
 * 值真源为 scrcpy v5.0.1 源码（可行性裁定：rts/ 仓内 scrcpy-integration-feasibility.md
 * §4.4 逐条锚定）：
 * - 消息型别与字段序：`server/.../control/ControlMessage.java`（TYPE_*）与
 *   `control/ControlMessageReader.java`（服务端消费读序——客户端编码序的镜像）；
 * - 定点小数编码：`server/.../util/Binary.java`（u16/i16 定点）与官方客户端
 *   `app/src/control_msg.c`（sc_control_msg_serialize——本编码器的字节镜像真源）；
 * - 设备端回执：`control/DeviceMessage.java` 与 `control/DeviceMessageWriter.java`
 *   （剪贴板 / ack_clipboard / uhid_output 三型）。
 *
 * 全部多字节字段为大端（java DataOutput 语义）。客户端→服务端消息上限 256K
 * （`MESSAGE_MAX_SIZE`，服务端读缓冲上限——超限即断连，编码器必须先截断再写长度）。
 */
object ScrcpyControlProtocol {

    // ── 客户端 → 服务端消息型别（ControlMessage.TYPE_*） ──

    const val TYPE_INJECT_KEYCODE: Int = 0
    const val TYPE_INJECT_TEXT: Int = 1
    const val TYPE_INJECT_TOUCH_EVENT: Int = 2
    const val TYPE_INJECT_SCROLL_EVENT: Int = 3
    const val TYPE_GET_CLIPBOARD: Int = 8
    const val TYPE_SET_CLIPBOARD: Int = 9

    /**
     * 重置视频编码（type=17，无载荷——server `ControlMessage.TYPE_RESET_VIDEO`，经
     * `Controller.resetVideo` → `CaptureControl.reset` 令 MediaCodec 整体重配置）：
     * server 随后重发会话包 + config 包（新 SPS/PPS）并紧跟 IDR 帧。录像起点即时化用——
     * 编码器 I 帧间隔固定 10 秒不可配，不重置则录像起点最长要等一个 GOP 的自然关键帧。
     */
    const val TYPE_RESET_VIDEO: Int = 17

    /**
     * get_clipboard 的 copyKey：NONE = 只读回不按键（COPY/CUT 会向设备端焦点注入
     * 复制/剪切键——读回通道恒用 NONE，避免旁效）。
     */
    const val COPY_KEY_NONE: Int = 0

    /** 服务端读缓冲上限（`MESSAGE_MAX_SIZE = 1 << 18`）：截断在编码器内先于长度字段完成 */
    const val MESSAGE_MAX_SIZE: Int = 1 shl 18

    /**
     * 文本注入载荷上限（UTF-8 字节数，`INJECT_TEXT_MAX_LENGTH = 300`）：
     * 服务端 IME 注入按 300 截断，超长部分在编码器内按 UTF-8 边界截断丢弃。
     */
    const val INJECT_TEXT_MAX_BYTES: Int = 300

    /** 剪贴板载荷上限：MESSAGE_MAX_SIZE −（型别 1B + sequence 8B + paste 1B + 长度 4B） */
    const val CLIPBOARD_TEXT_MAX_BYTES: Int = MESSAGE_MAX_SIZE - 14

    // ── 按键事件 action（KeyEvent.ACTION_*） ──

    const val KEY_ACTION_DOWN: Int = 0
    const val KEY_ACTION_UP: Int = 1

    // ── 触摸事件 action（MotionEvent.ACTION_*） ──

    const val MOTION_ACTION_DOWN: Int = 0
    const val MOTION_ACTION_UP: Int = 1
    const val MOTION_ACTION_MOVE: Int = 2

    /**
     * 通用手指 pointerId（官方客户端 `SC_POINTER_ID_GENERIC_FINGER = UINT64_C(-2)`）：
     * u64 载荷——i64 写入 -2 与其位形态逐字节一致。面板触摸注入恒用手指语义
     * （不用 mouse 的 -1——那会走滚轮/右键分支）。
     *
     * 虚拟指 id 全表（-1 与 -2 真源 = 官方客户端常量；其余为本端多指手势的固定分配）：
     * -2 主单指、-3/-4 双指对（[POINTER_ID_PINCH_FIRST]/[POINTER_ID_PINCH_SECOND]）、
     * -11..-13 泊指（右键单击泊入的常驻按住指位，见 [POINTER_ID_PARKED_FIRST]）、
     * -1 禁用（mouse 分支——服务端按 mouse id 走滚轮/右键语义，触摸注入不得使用）。
     */
    const val POINTER_ID_GENERIC_FINGER: Long = -2L

    /** 双指对第一指（离散档捏合缩放的上指）——与 [POINTER_ID_PINCH_SECOND] 成对使用，单只用即失配 */
    const val POINTER_ID_PINCH_FIRST: Long = -3L

    /** 双指对第二指（离散档捏合缩放的下指）——与 [POINTER_ID_PINCH_FIRST] 成对使用，单只用即失配 */
    const val POINTER_ID_PINCH_SECOND: Long = -4L

    /**
     * 泊指首号（-11）：右键单击泊入的常驻按住虚拟指，依序 -11/-12/-13 共
     * [PARKED_FINGERS_MAX] 根——发一次 DOWN 后保持按住（不发 UP），与主单指/双指对
     * 互不撞面，构成「一指固定一指动」的多指姿态。
     */
    const val POINTER_ID_PARKED_FIRST: Long = -11L

    /** 泊指容量上限（3 根：-11/-12/-13；超限泊入拒绝并显式提示，不静默） */
    const val PARKED_FINGERS_MAX: Int = 3

    /**
     * 滚动量的 wire 值域（官方客户端注释 "Accept values in the range [-16, 16]"）：
     * 编码时先除以本值归一到 [-1, 1] 再走 i16 定点（服务端解码后乘回）。
     */
    const val SCROLL_WIRE_RANGE: Float = 16f

    // ── 服务端 → 客户端设备消息型别（DeviceMessage.TYPE_*） ──

    const val DEVICE_MSG_CLIPBOARD: Int = 0
    const val DEVICE_MSG_ACK_CLIPBOARD: Int = 1
    const val DEVICE_MSG_UHID_OUTPUT: Int = 2

    /**
     * float [0, 1] → u16 定点（`Binary.u16FixedPointToFloat` 的逆；1.0 收口到 0xffff
     * ——对端把 0xffff 特判回 1.0）。触摸压力值编码用。
     */
    fun floatToU16FixedPoint(value: Float): Int {
        val scaled = (value.coerceIn(0f, 1f) * U16_FIXED_POINT_SCALE).toInt()
        return scaled.coerceAtMost(0xffff)
    }

    /**
     * float [-1, 1] → i16 定点（`Binary.i16FixedPointToFloat` 的逆；±1.0 收口到
     * 0x7fff / -0x8000）。滚动量编码用。
     */
    fun floatToI16FixedPoint(value: Float): Int {
        val scaled = (value.coerceIn(-1f, 1f) * I16_FIXED_POINT_SCALE).toInt()
        return scaled.coerceIn(-0x8000, 0x7fff)
    }

    private const val U16_FIXED_POINT_SCALE = 65_536f // 2^16

    private const val I16_FIXED_POINT_SCALE = 32_768f // 2^15
}

/**
 * 控制消息编码器（客户端 → 服务端方向；纯函数——只产字节不持连接，逐字节可测）。
 *
 * 字段序镜像官方客户端 `app/src/control_msg.c` 的 `sc_control_msg_serialize`——
 * 服务端 `ControlMessageReader` 按同序逐字段读，错序即静默失效（服务端对编码错误
 * 无逐消息回执，唯 [ScrcpyControlEncoder.setClipboard] 有 ack 回执），故编码正确性
 * 由逐字节单测钉死。
 */
object ScrcpyControlEncoder {

    /** keycode(0)：action u8 + keycode i32 + repeat i32 + metaState i32（14B） */
    fun injectKeycode(
        action: Int,
        keycode: Int,
        repeat: Int = 0,
        metaState: Int = 0,
    ): ByteArray = build { out ->
        out.writeByte(ScrcpyControlProtocol.TYPE_INJECT_KEYCODE)
        out.writeByte(action)
        out.writeInt(keycode)
        out.writeInt(repeat)
        out.writeInt(metaState)
    }

    /** text(1)：4B 字节长 + UTF-8 载荷（按 [ScrcpyControlProtocol.INJECT_TEXT_MAX_BYTES] 截断） */
    fun injectText(text: String): ByteArray = build { out ->
        out.writeByte(ScrcpyControlProtocol.TYPE_INJECT_TEXT)
        val bytes = utf8Truncated(text, ScrcpyControlProtocol.INJECT_TEXT_MAX_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    /**
     * touch(2)：action u8 + pointerId i64 + position(x i32, y i32, 屏宽 u16, 屏高 u16)
     * + 压力 u16 定点 + actionButton i32 + buttons i32（32B）。坐标为设备屏幕像素坐标。
     */
    fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float,
        actionButton: Int = 0,
        buttons: Int = 0,
    ): ByteArray = build { out ->
        out.writeByte(ScrcpyControlProtocol.TYPE_INJECT_TOUCH_EVENT)
        out.writeByte(action)
        out.writeLong(pointerId)
        out.writeInt(x)
        out.writeInt(y)
        out.writeShort(screenWidth)
        out.writeShort(screenHeight)
        out.writeShort(ScrcpyControlProtocol.floatToU16FixedPoint(pressure))
        out.writeInt(actionButton)
        out.writeInt(buttons)
    }

    /**
     * scroll(3)：position 12B + hScroll/vScroll i16 定点（值域
     * [ScrcpyControlProtocol.SCROLL_WIRE_RANGE] ±，编码先归一）+ buttons i32（21B）。
     * 值语义对齐官方客户端：正 vScroll = 滚轮向上（设备端内容向前滚动）。
     */
    fun injectScroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        hScroll: Float,
        vScroll: Float,
        buttons: Int = 0,
    ): ByteArray = build { out ->
        out.writeByte(ScrcpyControlProtocol.TYPE_INJECT_SCROLL_EVENT)
        out.writeInt(x)
        out.writeInt(y)
        out.writeShort(screenWidth)
        out.writeShort(screenHeight)
        out.writeShort(
            ScrcpyControlProtocol.floatToI16FixedPoint(hScroll / ScrcpyControlProtocol.SCROLL_WIRE_RANGE),
        )
        out.writeShort(
            ScrcpyControlProtocol.floatToI16FixedPoint(vScroll / ScrcpyControlProtocol.SCROLL_WIRE_RANGE),
        )
        out.writeInt(buttons)
    }

    /**
     * get_clipboard(8)：copyKey u8（2B）——请求服务端回推设备剪贴板内容（TYPE_CLIPBOARD
     * 设备消息）。copyKey 恒 [ScrcpyControlProtocol.COPY_KEY_NONE]（不向设备端注入
     * 复制/剪切键）。注意：server 侧仅 `clipboard_autosync=false` 时才响应回推
     * （autosync 开启时变更监听已覆盖同步语义）——serve 命令恒带 autosync=false
     * 与本读回通道配套（[ScrcpyServerDeployer.buildServeCommand]）。
     */
    fun getClipboard(copyKey: Int = ScrcpyControlProtocol.COPY_KEY_NONE): ByteArray = build { out ->
        out.writeByte(ScrcpyControlProtocol.TYPE_GET_CLIPBOARD)
        out.writeByte(copyKey)
    }

    /**
     * set_clipboard(9)：sequence i64 + paste u8 + 4B 字节长 + UTF-8（[ScrcpyControlProtocol.CLIPBOARD_TEXT_MAX_BYTES] 截断）。
     * sequence = 0 是「无回执」哨兵（`SEQUENCE_INVALID`）——需要 ack 实证时必须传非零值。
     */
    fun setClipboard(sequence: Long, text: String, paste: Boolean): ByteArray = build { out ->
        out.writeByte(ScrcpyControlProtocol.TYPE_SET_CLIPBOARD)
        out.writeLong(sequence)
        out.writeByte(if (paste) 1 else 0)
        val bytes = utf8Truncated(text, ScrcpyControlProtocol.CLIPBOARD_TEXT_MAX_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    /**
     * reset_video(17)：单字节型别、无载荷（server 无逐消息回执——生效表象 = 流上新
     * config 包 + 关键帧，消费方以录像闸观察到 config/关键帧到达为准）。
     */
    fun resetVideo(): ByteArray = build { out ->
        out.writeByte(ScrcpyControlProtocol.TYPE_RESET_VIDEO)
    }

    private inline fun build(block: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(block)
        return buffer.toByteArray()
    }

    /**
     * UTF-8 边界安全截断：多字节字符不拦腰截断（对端按 UTF-8 解码，拦腰即产替换符）。
     * 逐字符回退最简（截断是罕见路径，无热点）；空串与全 ASCII 短串零回退。
     */
    private fun utf8Truncated(text: String, maxBytes: Int): ByteArray {
        var end = text.length
        var bytes = text.toByteArray(Charsets.UTF_8)
        while (bytes.size > maxBytes && end > 0) {
            end -= 1
            bytes = text.substring(0, end).toByteArray(Charsets.UTF_8)
        }
        return bytes
    }
}

/**
 * 设备端回执事件（服务端 → 客户端方向，`DeviceMessage` 消费面）：
 * - [AckClipboard]：set_clipboard 的落地回执（sequence 与请求侧自增序号同源）；
 * - [Clipboard]：设备端剪贴板内容推送（启动初始同步与设备端变更均触发）。
 */
sealed interface ScrcpyDeviceEvent {
    data class AckClipboard(val sequence: Long) : ScrcpyDeviceEvent
    data class Clipboard(val text: String) : ScrcpyDeviceEvent
}

/**
 * 设备消息 reader（服务端 `DeviceMessageWriter` 写序的镜像读）：
 * clipboard(0) = 4B 字节长 + UTF-8；ack_clipboard(1) = 8B sequence；
 * uhid_output(2) = u16 id + u16 长 + 裸数据——本端从不创建 uhid 设备，防御消费后继续。
 *
 * 干净 EOF（型别字节位即 EOF）返回 null；型别字节读到后载荷截断抛 `EOFException`；
 * 未知型别抛 [IOException]（服务端只发三型，未知即协议失配——消费方按控制面故障
 * 处置，不拖垮画面面）。
 */
class ScrcpyDeviceMessageReader(input: InputStream) {

    private val data = DataInputStream(input)

    fun next(): ScrcpyDeviceEvent? {
        var type = data.read()
        while (type == ScrcpyControlProtocol.DEVICE_MSG_UHID_OUTPUT) {
            data.readUnsignedShort() // id：本端无 uhid 设备，消费丢弃
            val length = data.readUnsignedShort()
            skipFully(length)
            type = data.read()
        }
        if (type == -1) return null
        return when (type) {
            ScrcpyControlProtocol.DEVICE_MSG_CLIPBOARD -> {
                val length = data.readInt()
                if (length < 0 || length > ScrcpyControlProtocol.MESSAGE_MAX_SIZE) {
                    throw IOException("Invalid clipboard length: $length")
                }
                val bytes = ByteArray(length)
                data.readFully(bytes)
                ScrcpyDeviceEvent.Clipboard(String(bytes, Charsets.UTF_8))
            }

            ScrcpyControlProtocol.DEVICE_MSG_ACK_CLIPBOARD -> ScrcpyDeviceEvent.AckClipboard(data.readLong())

            else -> throw IOException("Unknown device message type: $type")
        }
    }

    private fun skipFully(count: Int) {
        var remaining = count
        val buffer = ByteArray(SKIP_CHUNK_BYTES)
        while (remaining > 0) {
            val read = data.read(buffer, 0, minOf(remaining, buffer.size))
            if (read == -1) throw java.io.EOFException("device message truncated in uhid payload")
            remaining -= read
        }
    }

    private companion object {
        const val SKIP_CHUNK_BYTES = 4096
    }
}

/**
 * 控制通道发送端：单 socket 双向通道的写侧收口——写串行化（[send] 锁内单笔写 + flush，
 * 面板任意线程的并发注入不交错），读侧归 [ScrcpyDeviceMessageReader] 消费方（设备消息
 * pump），两侧互不共享流对象。socket 生命周期归引擎句柄包（停止/收尾关 socket 即令
 * 在途 [send] 抛 [IOException]——调用方按「注入失败」收口，不炸 UI）。
 *
 * 控制通道禁 Nagle（`tcpNoDelay`，官方客户端同款）：单笔 14-32B 的小消息若在
 * send buffer 里聚合，按键/触摸的注入延迟会被放大到交互不可用。
 */
class ScrcpyControlChannel(socket: Socket) : AutoCloseable {

    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    private val writeLock = Any()

    /**
     * 写一笔编码完的消息并冲刷。失败（通道已关/对端断开）抛 [IOException]——
     * 不吞不静默，由调用方决定呈现；本类不做重试（控制通道断开随会话终态重建）。
     */
    fun send(bytes: ByteArray) {
        synchronized(writeLock) {
            out.write(bytes)
            out.flush()
        }
    }

    override fun close() {
        synchronized(writeLock) {
            runCatching { out.close() }
        }
    }
}
