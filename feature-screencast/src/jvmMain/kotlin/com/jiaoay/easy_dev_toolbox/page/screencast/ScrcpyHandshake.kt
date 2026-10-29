package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * forward 模式握手产物：探活成功的连接本体 + 握手结果（[ScrcpyHandshakeReader.connectForward]）。
 * 连接所有权移交调用方（用毕关闭）；[input] 已消费探活字节，后续读序从 64B 设备名后接续。
 */
class ScrcpyForwardConnection(
    val input: InputStream,
    val handshake: ScrcpyHandshake,
)

/**
 * 握手结果：设备名（UI 显示用）+ codec id（FourCC 或禁流码）。
 *
 * codec id 的三种消费分支（demuxer.c `sc_demuxer_recv_codec_id` 同款语义）：
 * - [ScrcpyProtocol.CODEC_H264] 等合法 FourCC → 进入流解析；
 * - [ScrcpyProtocol.STREAM_DISABLED] → 流显式禁用，会话继续（本期 video 恒开不会出现，防御保留）；
 * - [ScrcpyProtocol.STREAM_CONFIG_ERROR] → 配置错误，必须停止。
 */
data class ScrcpyHandshake(
    val deviceName: String,
    val codecId: Int,
) {
    /** 流被设备端显式禁用（code 0）：非致命，会话继续 */
    val streamDisabled: Boolean get() = codecId == ScrcpyProtocol.STREAM_DISABLED

    /** 配置错误（code 1）：必须停止，重试无意义 */
    val streamConfigError: Boolean get() = codecId == ScrcpyProtocol.STREAM_CONFIG_ERROR
}

/**
 * 首条 socket 握手 reader——server 侧 `DesktopConnection.open` + `sendDeviceMeta` +
 * `Streamer.writeVideoHeader` 的客户端读序，与官方客户端（server.c `connect_to_server` /
 * `device_read_info` → 4B codec id）逐段对齐。
 *
 * 读序按隧道模式分型（两分支在 `DesktopConnection.open` 内差异明确，不允许混用）：
 * - **forward**（tunnel_forward=true，server 在设备端监听）：连接成功不代表 server 就绪
 *   （adb forward 只建端口转发），server accept 后先写 1B 探活字节，客户端读探活确认
 *   "the server behind the adb tunnel is listening"——读不到即关连接重试（官方默认
 *   100 次 × 100ms，[ProbeRetryPolicy] 可注入）；探活到达后接 64B 设备名 → 4B codec id；
 * - **reverse**（tunnel_forward=false，server 主动反连客户端监听口）：连接建立即代表 server
 *   就绪，**无探活字节**——accept 后直接读 64B 设备名 → 4B codec id。
 *
 * 线程模型：[connectForward] 挂起函数（重试间隔经 delay 让出）；[read] 纯阻塞读，
 * 调用方负责放到 IO 调度器。连接的打开与关闭归调用方/连接工厂所有，reader 不持有 socket。
 */
class ScrcpyHandshakeReader(
    private val retryPolicy: ProbeRetryPolicy = ProbeRetryPolicy(),
) {

    /**
     * forward 模式探活重试契约：上限与间隔对齐官方 connect_to_server 默认值
     * （attempts=100 / delay=100ms）；单测与 spike 可注入更小值加速失败反馈。
     */
    data class ProbeRetryPolicy(val maxAttempts: Int = 100, val retryDelayMs: Long = 100)

    /**
     * forward 模式握手：[connect] 每次尝试打开一条新连接（返回 null = 建连失败，计入重试）。
     * 探活读的四种结果全走重试契约——建连失败、探活字节缺失（EOF）、非 0x00、**读抛异常**
     * （连接被对端重置等：异常入 lastFailure 后关该连接继续，不得把异常折成 String 走
     * 字节格式化分支）——均不计入成功，关闭该连接后按 [ProbeRetryPolicy] 重试；重试耗尽抛
     * [IOException]（附最后一次失败原因，供诊断 adb 隧道与 server 拉起时序）。
     *
     * [totalTimeoutMs] = 探活重试段总闸：整段重试循环（含全部 [ProbeRetryPolicy.retryDelayMs]
     * 间隔）的挂钟预算，到点抛 [TimeoutCancellationException]，由调用方收口为显式失败——
     * null = 无总闸（仅单测/极短预算场景使用；生产路径必须传，否则重试预算按
     * attempts × 间隔无界展开）。[IOException]（重试耗尽）与 [TimeoutCancellationException]
     * （总闸到点）谁先触发即收哪种。
     *
     * 成功返回的 [ScrcpyForwardConnection.input] 即视频连接本体（server 只 accept 一条 socket，
     * 探活字节之后的设备名 / codec id / 全部流数据都在同一连接上），流已消费掉探活字节，
     * 调用方接续握手判读与 demuxer 时不得重开连接。
     */
    suspend fun connectForward(
        totalTimeoutMs: Long? = null,
        connect: () -> InputStream?,
    ): ScrcpyForwardConnection =
        if (totalTimeoutMs == null) {
            probeRetryLoop(connect)
        } else {
            withTimeout(totalTimeoutMs) { probeRetryLoop(connect) }
        }

    private suspend fun probeRetryLoop(connect: () -> InputStream?): ScrcpyForwardConnection {
        var lastFailure: String? = null
        repeat(retryPolicy.maxAttempts) { attempt ->
            val isLastAttempt = attempt == retryPolicy.maxAttempts - 1
            val input = connect()
            if (input == null) {
                lastFailure = "connect failed"
            } else {
                val probe = runCatching { input.read() }
                val failure: String? = when {
                    probe.isFailure -> "probe read error: ${probe.exceptionOrNull()?.message}"
                    probe.getOrThrow() == ScrcpyProtocol.PROBE_BYTE -> null
                    probe.getOrThrow() == -1 -> "probe EOF (server not listening yet)"
                    else -> "probe byte 0x%02x != 0x00".format(probe.getOrThrow())
                }
                if (failure == null) {
                    return ScrcpyForwardConnection(input, read(input))
                }
                lastFailure = failure
                runCatching { input.close() }
            }
            if (!isLastAttempt) delay(retryPolicy.retryDelayMs)
        }
        throw IOException(
            "scrcpy server probe failed after ${retryPolicy.maxAttempts} attempts: $lastFailure",
        )
    }

    /**
     * reverse 模式握手：连接已由 accept 建立（server 主动反连），无探活段，直接读设备名 + codec id。
     * 亦可复用于 forward 模式探活字节之后的剩余读序（[connectForward] 内部即走本函数）。
     */
    fun read(input: InputStream): ScrcpyHandshake {
        val data = DataInputStream(input)
        val name = readDeviceName(data)
        val codecId = readCodecId(data)
        return ScrcpyHandshake(name, codecId)
    }

    /**
     * 64B 定长设备名：UTF-8、NUL 填充、超长按 UTF-8 边界截断（`sendDeviceMeta` 写入侧语义）。
     * 读侧防御与官方 device_read_info 同款：末字节强制置 NUL（防对端发垃圾无 NUL 时越界），
     * 解析取首个 NUL 前缀。
     */
    fun readDeviceName(input: InputStream): String {
        val data = DataInputStream(input)
        val buf = ByteArray(ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH)
        data.readFully(buf) // 截断流抛 EOFException（readFully 读满前遇 EOF 即抛）
        buf[ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH - 1] = 0
        val end = buf.indexOf(0)
        val len = if (end == -1) buf.size - 1 else end
        return String(buf, 0, len, Charsets.UTF_8)
    }

    /** 4B 大端 codec id：合法 FourCC 或禁流双码（0/1），语义判定见 [ScrcpyHandshake] */
    fun readCodecId(input: InputStream): Int {
        val data = DataInputStream(input)
        return data.readInt()
    }
}
