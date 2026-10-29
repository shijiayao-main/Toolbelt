package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import org.junit.Assume.assumeTrue

/**
 * 投屏显示链延迟分解真机探测 runner（门控：`SCRCPY_LATENCY` 未设置时 assume 跳过，
 * 本地/CI 恒绿）。只读测量面，不改生产行为——吃生产解码器（[JavaCvScreencastDecoder]，
 * 修复前后自动跟随生产真值）与引擎同款组件逐段打点：
 *
 * demux pump（socket 收包时刻 t_recv + scrcpy 帧自带 ptsUs）→ 生产解码器（pump 直连
 * onPacket，引擎同款接线）→ 逐帧计时。报告量（免设备时钟口径——包/帧计数与 pts 差分，
 * 宿主单调钟只用于耗时）：
 * - Δpts p50 = 设备端出帧节奏（max_fps 档位量化）；Δrecv p50/p95 = 到包节奏；
 * - **AU 持帧 lag**（核心，稳态显示滞后的帧数口径）：解码出第 j 帧（1-based）时已收到
 *   多少个码流包——lag = 已收包数 − 已出帧数；min = 解码器固有持帧（解析器 hold-back /
 *   重排缓冲证据），p50 = 运动期稳态积压（×Δpts 即预览体感滞后）；
 * - 单帧 grab 总耗时 p50/p95（解码+转换+位图，含阻塞等包）；
 * - 纯算力面：录制码流从内存全速喂生产解码器逐 grab 计时（无网络节奏与阻塞干扰）。
 *
 * 诊断臂（env `SCRCPY_LATENCY_MIRROR`）：FFmpegFrameGrabber 镜像解码（生产旧构造同款），
 * 逐段 grab/convert/bitmap 计时 + video_c.has_b_frames 反射读——解码器内部机制对照。
 *
 * 环境变量：`SCRCPY_SERIAL`、`SCRCPY_ADB`、`SCRCPY_LATENCY_SECONDS`（默认 14）、
 * `SCRCPY_MAX_FPS`（默认 30）、`SCRCPY_MAX_SIZE`（默认 1080）、`SCRCPY_MOTION`（默认
 * 1 = adb 滑动造帧流）。
 */
class ScreencastLatencyProbeRunner {

    @Test
    fun `device latency probe - display chain segment decomposition`() = runBlocking {
        assumeTrue(
            "SCRCPY_LATENCY 未设置——真机延迟探测按门控执行，本地默认跳过",
            System.getenv("SCRCPY_LATENCY") != null,
        )
        if (System.getenv("SCRCPY_LATENCY_MIRROR") != null) {
            runMirrorArm()
        } else {
            runProductionArm()
        }
    }

    // ── 生产链主臂 ────────────────────────────────────────────────────────

    private fun runProductionArm() = runBlocking {
        val adbPath = System.getenv("SCRCPY_ADB") ?: ""
        val serial = System.getenv("SCRCPY_SERIAL") ?: firstDeviceSerial(adbPath)
        val seconds = System.getenv("SCRCPY_LATENCY_SECONDS")?.toLongOrNull() ?: 14L
        val maxFps = System.getenv("SCRCPY_MAX_FPS")?.toFloatOrNull() ?: 30f
        val maxSize = System.getenv("SCRCPY_MAX_SIZE")?.toIntOrNull() ?: 1080

        val session = openSession(adbPath, serial, maxFps, maxSize)
        try {
            val packets = java.util.Collections.synchronizedList(mutableListOf<PacketRecord>())
            val frames = java.util.Collections.synchronizedList(mutableListOf<FrameRecord>())
            val payloadBudget = java.util.concurrent.atomic.AtomicLong(0)
            val sessionStart = System.nanoTime()

            // 生产拓扑：pump 直连生产解码器 onPacket（引擎同款接线，解码器内部自带管道）
            val decoder = JavaCvScreencastDecoderFactory.create()
            val pump = launch(Dispatchers.IO) {
                val demuxer = ScrcpyDemuxer(session.videoSocket.getInputStream())
                try {
                    while (true) {
                        val tRecv = System.nanoTime()
                        when (val event = demuxer.next() ?: break) {
                            is ScrcpyStreamEvent.SessionMeta ->
                                println("[probe] 会话包 ${event.width}x${event.height}")

                            is ScrcpyStreamEvent.Config -> {
                                packets.add(
                                    PacketRecord(
                                        tRecv, 0L, event.payload.size, isConfig = true, keyFrame = false,
                                        payload = takePayload(payloadBudget, event.payload),
                                    ),
                                )
                                decoder.onPacket(event.payload)
                            }

                            is ScrcpyStreamEvent.Frame -> {
                                packets.add(
                                    PacketRecord(
                                        tRecv, event.ptsUs, event.payload.size, isConfig = false, event.keyFrame,
                                        payload = takePayload(payloadBudget, event.payload),
                                    ),
                                )
                                decoder.onPacket(event.payload)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // 停止关 socket 的解除阻塞路径——与生产 demuxer 终止信号同口径
                } finally {
                    runCatching { decoder.signalEnd() }
                }
            }

            val decodeJob = launch(Dispatchers.IO) {
                try {
                    while (true) {
                        val t0 = System.nanoTime()
                        val decoded = decoder.grab() ?: break
                        val t1 = System.nanoTime()
                        frames.add(
                            FrameRecord(tOutNanos = t1, grabNanos = t1 - t0, width = decoded.width, height = decoded.height),
                        )
                        if (frames.size == 10) {
                            runCatching {
                                val field = JavaCvScreencastDecoder::class.java.getDeclaredField("codecContext")
                                field.isAccessible = true
                                val ctx = field.get(decoder) as AVCodecContext
                                println(
                                    "[probe] 生产解码器上下文：has_b_frames=%d low_delay=%s flags=0x%x thread_count=%d".format(
                                        ctx.has_b_frames(),
                                        (ctx.flags() and AV_CODEC_FLAG_LOW_DELAY) != 0,
                                        ctx.flags(),
                                        ctx.thread_count(),
                                    ),
                                )
                            }.onFailure { println("[probe] codecContext 反射读取失败: ${it.message}") }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { decoder.close() }
                }
            }

            val motionJob = launch(Dispatchers.IO) {
                if (System.getenv("SCRCPY_MOTION") != "0") {
                    // 造流注入 = 音量上/下成对（弹窗即大视觉变化，音量净变化为零——真机日常授权形态）
                    val pairs = listOf(
                        "adb shell input keyevent 24",
                        "adb shell input keyevent 25",
                    )
                    var i = 0
                    while (true) {
                        runCatching {
                            com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell.execute(
                                com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite(pairs[i % pairs.size], adbPath, serial),
                            )
                        }
                        i++
                        delay(500)
                    }
                }
            }

            delay(seconds * 1_000)
            val wallSeconds = (System.nanoTime() - sessionStart) / 1_000_000_000.0
            runCatching { session.videoSocket.close() }
            motionJob.cancel()
            withTimeout(10_000) {
                while (decodeJob.isActive) delay(100)
            }
            pump.cancel()
            session.runner.stop()

            report(packets, frames, wallSeconds, maxFps, maxSize)
            runComputePass(packets)
        } finally {
            closeSession(session)
        }
    }

    // ── 诊断臂（grabber 镜像：逐段计时 + has_b_frames 反射） ───────────────────

    private fun runMirrorArm() = runBlocking {
        val adbPath = System.getenv("SCRCPY_ADB") ?: ""
        val serial = System.getenv("SCRCPY_SERIAL") ?: firstDeviceSerial(adbPath)
        val seconds = System.getenv("SCRCPY_LATENCY_SECONDS")?.toLongOrNull() ?: 14L
        val maxFps = System.getenv("SCRCPY_MAX_FPS")?.toFloatOrNull() ?: 30f
        val maxSize = System.getenv("SCRCPY_MAX_SIZE")?.toIntOrNull() ?: 1080

        val session = openSession(adbPath, serial, maxFps, maxSize)
        try {
            val packets = java.util.Collections.synchronizedList(mutableListOf<PacketRecord>())
            val frames = java.util.Collections.synchronizedList(mutableListOf<FrameRecord>())
            val hasBFrames = java.util.concurrent.atomic.AtomicInteger(-1)
            val pipeIn = PipedInputStream(1024 * 1024)
            val pipeOut = PipedOutputStream(pipeIn)
            val sessionStart = System.nanoTime()

            val pump = launch(Dispatchers.IO) {
                val demuxer = ScrcpyDemuxer(session.videoSocket.getInputStream())
                try {
                    while (true) {
                        val tRecv = System.nanoTime()
                        when (val event = demuxer.next() ?: break) {
                            is ScrcpyStreamEvent.SessionMeta -> Unit
                            is ScrcpyStreamEvent.Config -> {
                                packets.add(PacketRecord(tRecv, 0L, event.payload.size, isConfig = true, keyFrame = false))
                                pipeOut.write(event.payload)
                                pipeOut.flush()
                            }

                            is ScrcpyStreamEvent.Frame -> {
                                packets.add(PacketRecord(tRecv, event.ptsUs, event.payload.size, isConfig = false, event.keyFrame))
                                pipeOut.write(event.payload)
                                pipeOut.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { pipeOut.flush() }
                    runCatching { pipeOut.close() }
                }
            }

            val decodeJob = launch(Dispatchers.IO) {
                val grabber = FFmpegFrameGrabber(pipeIn, 0).apply { setFormat("h264") }
                val converter = Java2DFrameConverter()
                var started = false
                try {
                    while (true) {
                        val t0 = System.nanoTime()
                        val frame = try {
                            if (!started) {
                                grabber.start(false)
                                started = true
                            }
                            grabber.grab() ?: break
                        } catch (_: Exception) {
                            break
                        }
                        val t1 = System.nanoTime()
                        val buffered = converter.convert(frame) ?: continue
                        val t2 = System.nanoTime()
                        val bitmap = buffered.toComposeImageBitmap()
                        val t3 = System.nanoTime()
                        frames.add(
                            FrameRecord(
                                tOutNanos = t3,
                                grabNanos = t1 - t0,
                                convertNanos = t2 - t1,
                                bitmapNanos = t3 - t2,
                                timestampUs = frame.timestamp,
                                width = buffered.width,
                                height = buffered.height,
                            ),
                        )
                        if (frames.size == 10) readHasBFrames(grabber, hasBFrames)
                        check(bitmap.width > 0)
                    }
                } finally {
                    runCatching { grabber.stop() }
                    runCatching { grabber.release() }
                    converter.close()
                }
            }

            launch(Dispatchers.IO) {
                val swipe = "adb shell input swipe 400 600 900 610 300"
                while (true) {
                    runCatching {
                        com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell.execute(
                            com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite(swipe, adbPath, serial),
                        )
                    }
                    delay(420)
                }
            }

            delay(seconds * 1_000)
            val wallSeconds = (System.nanoTime() - sessionStart) / 1_000_000_000.0
            runCatching { session.videoSocket.close() }
            withTimeout(10_000) {
                while (decodeJob.isActive) delay(100)
            }
            pump.cancel()
            session.runner.stop()

            reportMirror(packets, frames, wallSeconds, maxFps, maxSize, hasBFrames.get())
        } finally {
            closeSession(session)
        }
    }

    private fun readHasBFrames(grabber: FFmpegFrameGrabber, sink: java.util.concurrent.atomic.AtomicInteger) {
        runCatching {
            val field = FFmpegFrameGrabber::class.java.getDeclaredField("video_c")
            field.isAccessible = true
            val ctx = field.get(grabber) as? AVCodecContext ?: return
            sink.set(ctx.has_b_frames())
            println(
                "[probe] 解码器上下文：has_b_frames=%d low_delay=%s flags=0x%x thread_count=%d".format(
                    ctx.has_b_frames(),
                    (ctx.flags() and AV_CODEC_FLAG_LOW_DELAY) != 0,
                    ctx.flags(),
                    ctx.thread_count(),
                ),
            )
        }.onFailure { println("[probe] video_c 反射读取失败: ${it.message}") }
    }

    private fun reportMirror(
        packets: List<PacketRecord>,
        frames: List<FrameRecord>,
        wallSeconds: Double,
        maxFps: Float,
        maxSize: Int,
        hasBFrames: Int,
    ) {
        val framePackets = packets.filter { !it.isConfig }.sortedBy { it.tNanos }
        println("[probe] ===== 镜像臂分解（maxFps=$maxFps maxSize=$maxSize 实测 %.1fs）=====".format(wallSeconds))
        println("[probe] 码流包 ${framePackets.size} 个 / 解码出帧 ${frames.size} 帧 / 分辨率 ${frames.lastOrNull()?.width}x${frames.lastOrNull()?.height}")
        if (framePackets.size > 2) {
            val dPts = framePackets.zipWithNext().map { (a, b) -> (b.ptsUs - a.ptsUs) / 1000.0 }.filter { it > 0 && it < 1000 }
            val dRecv = framePackets.zipWithNext().map { (a, b) -> (b.tNanos - a.tNanos) / 1_000_000.0 }.filter { it > 0 && it < 1000 }
            println("[probe] 设备出帧节奏 Δpts ms %s".format(percentiles(dPts)))
            println("[probe] 到包节奏 Δrecv ms %s".format(percentiles(dRecv)))
        }
        if (frames.size > 8) {
            val lags = mutableListOf<Int>()
            var packetIdx = 0
            frames.forEachIndexed { j, frame ->
                while (packetIdx < framePackets.size && framePackets[packetIdx].tNanos < frame.tOutNanos) packetIdx++
                lags.add(packetIdx - (j + 1))
            }
            val steady = lags.drop(3).dropLast(1)
            println("[probe] AU 持帧 lag：min=%d p50=%d".format(steady.minOf { it }, median(steady)))
            val steadyFrames = frames.drop(3).dropLast(1)
            println("[probe] 单帧 grab(含阻塞等包) ms %s".format(percentiles(steadyFrames.map { it.grabNanos / 1e6 })))
            println("[probe] 单帧 convert ms %s".format(percentiles(steadyFrames.map { it.convertNanos / 1e6 })))
            println("[probe] 单帧 toComposeImageBitmap ms %s".format(percentiles(steadyFrames.map { it.bitmapNanos / 1e6 })))
        }
        println("[probe] has_b_frames=$hasBFrames")
    }

    // ── 通用报告 ──────────────────────────────────────────────────────────

    private fun report(packets: List<PacketRecord>, frames: List<FrameRecord>, wallSeconds: Double, maxFps: Float, maxSize: Int) {
        val framePackets = packets.filter { !it.isConfig }.sortedBy { it.tNanos }
        println("[probe] ===== 显示链延迟分解（maxFps=$maxFps maxSize=$maxSize 实测 %.1fs）=====".format(wallSeconds))
        println("[probe] 码流包 ${framePackets.size} 个 / 解码出帧 ${frames.size} 帧 / 分辨率 ${frames.lastOrNull()?.width}x${frames.lastOrNull()?.height}")

        if (framePackets.size > 2) {
            val dPts = framePackets.zipWithNext().map { (a, b) -> (b.ptsUs - a.ptsUs) / 1000.0 }
                .filter { it > 0 && it < 1000 }
            val dRecv = framePackets.zipWithNext().map { (a, b) -> (b.tNanos - a.tNanos) / 1_000_000.0 }
                .filter { it > 0 && it < 1000 }
            println("[probe] 设备出帧节奏 Δpts ms %s".format(percentiles(dPts)))
            println("[probe] 到包节奏 Δrecv ms %s".format(percentiles(dRecv)))
            val totalBytes = framePackets.sumOf { it.bytes.toLong() }
            println("[probe] 平均码率 ≈ %.1f Mbps".format(totalBytes * 8 / wallSeconds / 1_000_000.0))
        }

        if (frames.size > 8) {
            val lags = mutableListOf<Int>()
            var packetIdx = 0
            frames.forEachIndexed { j, frame ->
                while (packetIdx < framePackets.size && framePackets[packetIdx].tNanos < frame.tOutNanos) packetIdx++
                lags.add(packetIdx - (j + 1))
            }
            val steady = lags.drop(3).dropLast(1)
            println("[probe] AU 持帧 lag（已收包−已出帧）：min=%d p50=%d（头部序列 %s）".format(
                steady.minOf { it },
                median(steady),
                steady.take(20).joinToString(",") { it.toString() },
            ))
            val steadyFrames = frames.drop(3).dropLast(1)
            println("[probe] 单帧 grab(含阻塞等包) ms %s".format(percentiles(steadyFrames.map { it.grabNanos / 1e6 })))
        }
    }

    /**
     * 纯算力面：录制码流（config + 帧，字节预算内）从内存全速喂**生产解码器实例**
     * （[JavaCvScreencastDecoderFactory] 同款构造），消费者逐 grab 计时——单帧算力上界
     * （无网络节奏与阻塞干扰）。
     */
    private fun runComputePass(packets: List<PacketRecord>) {
        val configPayload = packets.firstOrNull { it.isConfig }?.payload
        val framePayloads = packets.filter { !it.isConfig }.mapNotNull { it.payload }
        if (configPayload == null || framePayloads.size < 30) {
            println("[probe] 纯算力面跳过：完整录制载荷不足（帧 ${framePayloads.size}）")
            return
        }
        val durations = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val decoder = JavaCvScreencastDecoderFactory.create()
        val feeder = Thread {
            try {
                decoder.onPacket(configPayload)
                framePayloads.forEach { decoder.onPacket(it) }
                decoder.signalEnd()
            } catch (_: Exception) {
            }
        }
        val consumer = Thread {
            try {
                var first = true
                while (true) {
                    val t0 = System.nanoTime()
                    val decoded = decoder.grab() ?: break
                    if (!first) durations.add(System.nanoTime() - t0)
                    first = false
                    check(decoded.width > 0)
                }
            } catch (_: Exception) {
            }
        }
        consumer.start()
        feeder.start()
        feeder.join(30_000)
        consumer.join(30_000)
        runCatching { decoder.close() }
        if (durations.size > 8) {
            println(
                "[probe] 纯算力面（生产解码器，无网络节奏）单帧 grab ms %s（n=%d）".format(
                    percentiles(durations.map { it / 1e6 }),
                    durations.size,
                ),
            )
        }
    }

    // ── 会话装配（push → 隧道 → serve → 双 socket accept → 握手） ─────────────

    private class ProbeSession(
        val videoSocket: Socket,
        val control: ScrcpyControlChannel,
        val opener: ScrcpyTunnelOpener,
        val tunnel: ScrcpyTunnel,
        val runner: StreamingShellScrcpyRunner,
    )

    private suspend fun CoroutineScope.openSession(adbPath: String, serial: String, maxFps: Float, maxSize: Int): ProbeSession {
        val scid = (0L until ScrcpyServerDeployer.SCID_MAX_EXCLUSIVE).random()
        val socketName = ScrcpyProtocol.deviceSocketName(scid)
        val adb = SimpleShellScrcpyAdbGateway(adbPath = adbPath, serial = serial)
        val runner = StreamingShellScrcpyRunner()
        val serverJar = java.io.File(
            System.getProperty("user.home") + "/.toolbelt/scrcpy/scrcpy-server-v" +
                ScrcpyServerDeployer.SERVER_VERSION,
        )
        adb.execute(ScrcpyServerDeployer.buildPushCommand(serverJar.absolutePath))
        val opener = ScrcpyTunnelOpener(adb, socketName)
        val tunnel = opener.open()
        val serveCommand = com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite(
            ScrcpyServerDeployer.buildServeCommand(
                scid = scid,
                tunnelForward = tunnel is ScrcpyTunnel.Forward,
                maxFps = maxFps,
                maxSize = maxSize,
            ),
            adbPath,
            serial,
        )
        launch(Dispatchers.IO) { runner.run(serveCommand) { } }
        delay(1_000)

        val videoSocket: Socket = when (val opened = tunnel) {
            is ScrcpyTunnel.Reverse -> {
                opened.listener.soTimeout = 25_000
                try {
                    withTimeout(20_000) { opened.listener.accept() }
                } catch (e: SocketTimeoutException) {
                    throw AssertionError("设备端 20s 未反连（server 未就绪或 reverse 不通）", e)
                }
            }

            is ScrcpyTunnel.Forward -> throw AssertionError("本探测按 reverse 形态跑（生产默认），forward 未接")
        }
        videoSocket.tcpNoDelay = true
        val handshake = ScrcpyHandshakeReader().read(videoSocket.getInputStream())
        // 控制通道 = 第二条反连（server 背靠背两次 connect，第二 accept 必到；探测面只收口不使用）
        val reverse = tunnel as ScrcpyTunnel.Reverse
        reverse.listener.soTimeout = 25_000
        val controlSocket = withTimeout(20_000) { reverse.listener.accept() }
        controlSocket.tcpNoDelay = true
        println("[probe] 握手：设备='${handshake.deviceName}' codec=0x%08x maxFps=$maxFps maxSize=$maxSize".format(handshake.codecId))
        return ProbeSession(videoSocket, ScrcpyControlChannel(controlSocket), opener, tunnel, runner)
    }

    private suspend fun closeSession(session: ProbeSession) {
        withContext(NonCancellable) {
            runCatching { session.control.close() }
            runCatching { session.videoSocket.close() }
            session.opener.close(session.tunnel)
            session.runner.stop()
            for (attempt in 1..15) {
                val adb = SimpleShellScrcpyAdbGateway(adbPath = System.getenv("SCRCPY_ADB") ?: "", serial = null)
                val ps = adb.execute("adb shell ps -A").getOrNull()?.stdout.orEmpty()
                if ("com.genymobile.scrcpy.Server" !in ps) break
                delay(1_000)
            }
            val reverseList = SimpleShellScrcpyAdbGateway(adbPath = System.getenv("SCRCPY_ADB") ?: "", serial = null)
                .execute("adb reverse --list").getOrNull()?.stdout.orEmpty()
            println("[probe] 收尾：reverse 规则残留=%s".format(reverseList.contains("scrcpy_")))
        }
    }

    /** 载荷预算记账：预算内返回载荷副本（供纯算力面重放），超预算返回 null（打点仍完整） */
    private fun takePayload(budget: java.util.concurrent.atomic.AtomicLong, payload: ByteArray): ByteArray? =
        if (budget.addAndGet(payload.size.toLong()) <= RECORD_PAYLOAD_BUDGET_BYTES) payload.copyOf() else null

    private class PacketRecord(
        val tNanos: Long,
        val ptsUs: Long,
        val bytes: Int,
        val isConfig: Boolean,
        val keyFrame: Boolean,
        val payload: ByteArray? = null,
    )

    private class FrameRecord(
        val tOutNanos: Long,
        val grabNanos: Long,
        val convertNanos: Long = 0,
        val bitmapNanos: Long = 0,
        val timestampUs: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
    )

    private fun percentiles(values: List<Double>): String {
        if (values.isEmpty()) return "n/a"
        val sorted = values.sorted()
        fun p(q: Double) = sorted[(q * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)]
        return "p50=%.1f p95=%.1f p99=%.1f".format(p(0.50), p(0.95), p(0.99))
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return -1
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private suspend fun firstDeviceSerial(adbPath: String): String {
        val command = com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite("adb devices", adbPath, serial = null)
        val stdout = com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell.execute(command).getOrNull()?.stdout.orEmpty()
        return stdout.lineSequence()
            .filter { "\tdevice" in it }
            .map { it.substringBefore('\t').trim() }
            .firstOrNull()
            ?: throw IllegalStateException("adb devices 无在线设备——延迟探测需设备在线")
    }

    private companion object {
        /** 录制载荷预算（纯算力面重放素材上界，超预算只丢载荷不丢打点） */
        const val RECORD_PAYLOAD_BUDGET_BYTES = 128L * 1024 * 1024
    }
}
