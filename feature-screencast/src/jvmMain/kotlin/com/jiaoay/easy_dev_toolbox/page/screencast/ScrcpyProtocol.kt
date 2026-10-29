package com.jiaoay.easy_dev_toolbox.page.screencast

/**
 * scrcpy 视频流 wire 契约常量。
 *
 * 值真源为 scrcpy v5.0.1 源码（可行性裁定：easibility.md
 * §4.2/§9.1 逐条锚定）：
 * - 帧头 / flags 位 / 会话包 / 禁流码：`server/.../device/Streamer.java`
 *   （writeFrameMeta / writeSessionMeta / writeDisableStream）；
 * - 探活字节与 64B 设备名：`server/.../device/DesktopConnection.java`
 *   （open 的 tunnelForward 分支 / sendDeviceMeta / DEVICE_NAME_FIELD_LENGTH）；
 * - codec FourCC 与禁流码消费语义：`app/src/demuxer.c`（sc_demuxer_recv_codec_id：0 = 显式禁用继续、
 *   1 = 配置错误必须停）。
 *
 * 全部多字节字段为大端（java DataInput 语义，与 server 端 ByteBuffer 默认 BIG_ENDIAN 一致）。
 */
object ScrcpyProtocol {

    /** codec id：H264 FourCC（`VideoCodec.java` `H264(0x68_32_36_34)` = 'h264' ASCII） */
    const val CODEC_H264: Int = 0x68323634

    /**
     * 4B codec id 槽位的禁流码：server 用它替代 codec id 通知客户端流不可用——
     * 0 = 流显式禁用（会话继续，如 API<30 的音频捕获失败），1 = 配置错误（必须停止）。
     */
    const val STREAM_DISABLED: Int = 0
    const val STREAM_CONFIG_ERROR: Int = 1

    /** 设备名定长字段：64B UTF-8、NUL 填充、超长截断（`DesktopConnection.sendDeviceMeta`） */
    const val DEVICE_NAME_FIELD_LENGTH: Int = 64

    /** forward 模式探活字节：server accept 后写 1 字节 0x00，仅首条 socket 有（写后置 false） */
    const val PROBE_BYTE: Int = 0x00

    /** 帧头定长：8B 大端 pts_and_flags + 4B 大端 packet size（`Streamer.writeFrameMeta`） */
    const val FRAME_HEADER_SIZE: Int = 12

    /** pts_and_flags 高位 flags：bit63 会话包、bit62 config(csd)、bit61 关键帧 */
    const val FLAG_SESSION: Long = 1L shl 63
    const val FLAG_CONFIG: Long = 1L shl 62
    const val FLAG_KEY_FRAME: Long = 1L shl 61

    /** pts 位掩码：key frame 以下全为 pts（demuxer.c `SC_PACKET_PTS_MASK = KEY_FRAME - 1`） */
    const val PTS_MASK: Long = FLAG_KEY_FRAME - 1

    /** 会话包 client_resized 标记位：会话 flags 低 32 位的 bit0（`writeSessionMeta` `flags |= 1`） */
    const val SESSION_FLAG_CLIENT_RESIZE: Int = 1

    /**
     * 设备侧 abstract socket 名（`DesktopConnection.getSocketName`）：
     * `scrcpy_` + scid 十六进制 8 位小写补零；scid 为 -1（未指定）时裸名 `scrcpy`。
     * forward/reverse 的隧道目标名都由它派生，两端必须同源。
     */
    fun deviceSocketName(scid: Long): String =
        if (scid < 0L) "scrcpy" else "scrcpy_%08x".format(scid)
}
