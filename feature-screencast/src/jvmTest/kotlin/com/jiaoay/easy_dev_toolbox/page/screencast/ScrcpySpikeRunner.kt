package com.jiaoay.easy_dev_toolbox.page.screencast

import java.awt.image.BufferedImage
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.Test
import org.junit.Assume.assumeTrue
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter

/**
 * scrcpy spike 真机跑批 runner（可丢弃面，段 2 毕业后归档弃用）。
 *
 * 门控：未设 `SCRCPY_SPIKE` 环境变量时 assume 跳过（本地/CI 恒绿）；真机判据集中在
 * [runFull]；leak / verify9 两模式配合完成强杀恢复验收（测试进程死亡即 fd 全关，
 * 与 kill -9 的 socket 生命周期等价，设备端退出行为可真实验证）。
 *
 * 模式（env `SCRCPY_SPIKE`）：
 * - `full`：push → tcp:0 探针（Major-2 待验项，独立跑跑完即清）→ 隧道（reverse 优先
 *   fallback forward）→ serve 拉起 → 握手三段判据 → demuxer 形态判据 → JavaCV 解码
 *   （start(false) 首帧、帧率与单帧延迟分位、PNG 落盘）→ 停止 → Major-3 验收点
 *   1/2（进程消失 / 规则清空）；验收点 3 由 leak → verify9 两模式接力完成；
 * - `leak`：拉起并验流后不做任何收尾，挂起等外部 kill -9 本测试进程（窗口 30s）；
 * - `verify9`：核验 leak 后旧 server 已自然退出（ps 无 scrcpy.Server）+ 新 scid 复连成功
 *   + 收尾后再次清点。
 *
 * 环境变量：`SCRCPY_SERIAL`（默认取 adb devices 首个）、`SCRCPY_ADB`（默认裸 adb）、
 * `SCRCPY_SERVER_JAR`（默认 ~/.toolbelt/scrcpy/scrcpy-server-v5.0.1）、
 * `SCRCPY_SPIKE_OUT`（PNG 落盘目录，默认 build/scrcpy-spike）、`SCRCPY_MAX_FRAMES`（默认 300）、
 * `SCRCPY_SECONDS`（采样秒数，默认 10）、`SCRCPY_MAX_SIZE`/`SCRCPY_MAX_FPS`（可选降负载参数）。
 * 命令序列与判据：握手三段（device/mode/ack）→ 视频流 demux 判型 → 首帧 PNG 落盘核对；
 * 真机跑批门控与验收点判据以本 KDoc 为真源（runbook 不随仓库分发）。
 */
class ScrcpySpikeRunner {

    @Test
    fun `device spike - deploy handshake demux decode and lifecycle`() = runBlocking {
        val mode = System.getenv("SCRCPY_SPIKE")
        assumeTrue(
            "SCRCPY_SPIKE 未设置——真机跑批按 runbook 门控执行，本地默认跳过",
            mode != null,
        )
        when (mode) {
            MODE_FULL -> runFull(this)
            MODE_LEAK -> runLeak(this)
            MODE_VERIFY9 -> runVerify9(this)
            else -> throw IllegalArgumentException("未知 SCRCPY_SPIKE 模式: $mode（full | leak | verify9）")
        }
    }

    private suspend fun runFull(scope: CoroutineScope) {
        val env = SpikeEnv.resolve()
        val scid = newScid()
        val socketName = ScrcpyProtocol.deviceSocketName(scid)
        val adb = SimpleShellScrcpyAdbGateway(adbPath = env.adbPath, serial = env.serial)
        val runner = StreamingShellScrcpyRunner()

        println("[spike] === 部署与隧道 ===")
        println("[spike] server jar: ${env.serverJar.absolutePath}")
        println("[spike] server jar sha256 = ${env.serverJar.sha256()}（与 release SHA256SUMS.txt.asc 比对）")
        println("[spike] scid=%08x socket=%s serial=%s".format(scid, socketName, env.serial))

        val push = adb.execute(ScrcpyServerDeployer.buildPushCommand(env.serverJar.absolutePath))
        assertTrue(push.isSuccess, "adb push 失败: ${push.exceptionOrNull()?.message}")
        println("[spike] 判据: push 完成")

        // Major-2 待验项：tcp:0 自动分配是否可解析（独立探针，结论写进回报）
        val autoPort = ScrcpyTunnelOpener(adb, socketName).probeForwardAutoPort()
        println(
            "[spike] Major-2 tcp:0 探针: " + if (autoPort != null) {
                "--list 回显实际端口 $autoPort（可解析）"
            } else {
                "解析断裂（回显字面量 tcp:0 或命令失败）→ 端口范围逐试为主路径"
            },
        )

        val opener = ScrcpyTunnelOpener(adb, socketName)
        val tunnel = opener.open()
        println("[spike] 判据（Major-2）: 隧道成立 = ${tunnel::class.simpleName} port=${when (tunnel) { is ScrcpyTunnel.Reverse -> tunnel.port; is ScrcpyTunnel.Forward -> tunnel.port }}")

        val serveCommand = ScrcpyServerDeployer.buildServeCommand(
            scid = scid,
            tunnelForward = tunnel is ScrcpyTunnel.Forward,
            maxSize = env.maxSize,
            maxFps = env.maxFps,
        )
        val rewroteServe = com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite(
            serveCommand, env.adbPath, env.serial,
        )
        println("[spike] serve: $rewroteServe")
        val serverLogs = mutableListOf<String>()
        val serverLogFile = File(env.outDir).apply { mkdirs() }.resolve("server.log")
        val serverJob = scope.launch(Dispatchers.IO) {
            runner.run(rewroteServe) { line ->
                synchronized(serverLogs) {
                    serverLogs.add(line)
                    serverLogFile.appendText(line + "\n")
                }
                if (serverLogs.size <= 40) println("[server] $line")
            }
        }
        delay(1_000) // server 拉起到 accept/反连的窗口

        val (videoSocket, handshake) = when (tunnel) {
            is ScrcpyTunnel.Reverse -> {
                val socket = acceptReverseWithHardTimeout(tunnel)
                println("[spike] reverse 模式无探活字节（DesktopConnection 仅 tunnelForward 分支写）")
                socket to ScrcpyHandshakeReader().read(socket.getInputStream())
            }
            is ScrcpyTunnel.Forward -> {
                println("[spike] forward 模式：探活字节 0x00 已到达并消费（判据 1）")
                connectForwardSocket(tunnel.port)
            }
        }
        videoSocket.tcpNoDelay = true
        videoSocket.soTimeout = 90_000

        try {
            println("[spike] 判据 2: 64B 设备名 = '${handshake.deviceName}'")
            assertEquals(ScrcpyProtocol.CODEC_H264, handshake.codecId, "codec id 必须为 'h264' FourCC")
            println("[spike] 判据 3: codec id = 0x%08x ('h264')".format(handshake.codecId))

            // —— demuxer 形态判据（会话/config 事件打点）+ 解码管线（载荷经管道喂 FFmpeg）——
            val pipeIn = PipedInputStream(1024 * 1024)
            val pipeOut = PipedOutputStream(pipeIn)
            val demuxer = ScrcpyDemuxer(videoSocket.getInputStream())
            val pump = scope.launch(Dispatchers.IO) {
                try {
                    while (true) {
                        when (val event = demuxer.next() ?: break) {
                            is ScrcpyStreamEvent.SessionMeta ->
                                println("[spike] 判据 5: 会话包 ${event.width}x${event.height} clientResize=${event.isClientResize}")
                            is ScrcpyStreamEvent.Config -> {
                                println("[spike] 判据 4: config(csd) 包先于首帧到达（${event.payload.size}B）")
                                pipeOut.write(event.payload)
                            }
                            is ScrcpyStreamEvent.Frame -> pipeOut.write(event.payload)
                        }
                    }
                } finally {
                    runCatching { pipeOut.flush() }
                    runCatching { pipeOut.close() }
                }
            }

            // —— JavaCV 解码（spike 2 判据）——
            val findStreamInfo = System.getenv("SCRCPY_START_TRUE") == "1"
            val grabber = FFmpegFrameGrabber(pipeIn, 0).apply { setFormat("h264") }
            val startNanos = System.nanoTime()
            grabber.start(findStreamInfo) // false = 跳过 avformat_find_stream_info 降首帧延迟（spike 2 对照项）
            println("[spike] spike 2: start($findStreamInfo) 启动+首帧就绪 ≈ ${(System.nanoTime() - startNanos) / 1_000_000}ms")
            println("[spike] grabber 解析分辨率: ${grabber.imageWidth}x${grabber.imageHeight}")

            val outDir = File(env.outDir).apply { mkdirs() }
            val converter = Java2DFrameConverter()
            val grabNanos = mutableListOf<Long>()
            var frames = 0
            val loopStart = System.currentTimeMillis()
            val deadline = loopStart + env.seconds * 1000
            var windowStart = loopStart
            while (frames < env.maxFrames && System.currentTimeMillis() < deadline) {
                val t0 = System.nanoTime()
                val frame = grabber.grab() ?: break
                grabNanos.add(System.nanoTime() - t0)
                frames++
                if (frames <= 3) {
                    val buffered: BufferedImage = converter.convert(frame)
                    val png = File(outDir, "frame-%03d.png".format(frames))
                    ImageIO.write(buffered, "png", png)
                    println("[spike] PNG 落盘: ${png.absolutePath} (${buffered.width}x${buffered.height})")
                }
                if (frames % 60 == 0) {
                    val elapsed = (System.currentTimeMillis() - windowStart) / 1000.0
                    println("[spike] 累计 $frames 帧，近段 ≈ %.1f fps".format(60.0 / elapsed))
                    windowStart = System.currentTimeMillis()
                }
            }
            val totalSeconds = (System.currentTimeMillis() - loopStart) / 1000.0
            println("[spike] spike 2: 共 $frames 帧，平均 %.1f fps（判据 720p30 ≥27fps）".format(frames / totalSeconds))
            println("[spike] spike 2: 单帧 grab 延迟 ms p50=%.1f p95=%.1f p99=%.1f（判据 p95≤25 或 p99≤33）".format(*percentiles(grabNanos)))
            grabber.stop()
            grabber.release()
            converter.close()
            pump.cancel()
            println("[spike] start(true) 对照：按 runbook 步骤 7 以 SCRCPY_START_TRUE=1 复跑本模式（新连接）")
        } finally {
            // —— 停止与收尾（Major-3 验收点 1/2）——
            // NonCancellable：测试失败路径协程已取消，suspend 清理必须脱离取消态执行，
            // 否则 adb remove 全被短路（真机首跑实测：规则残留的直接根因）
            println("[spike] === 停止与生命周期收尾 ===")
            withContext(NonCancellable) {
                runner.stop()
                serverJob.cancel()
                runCatching { videoSocket.close() }
                (tunnel as? ScrcpyTunnel.Reverse)?.let { runCatching { it.listener.close() } }
                opener.close(tunnel)
                assertServerProcessGone(adb)
                assertTunnelRulesRemoved(adb, socketName)
                println("[spike] Major-3 验收点 1/2 通过：app_process 进程消失、隧道规则清空")
            }
        }
        println("[spike] Major-3 验收点 3（强杀恢复）：按 runbook 步骤 8/9 依次跑 leak → verify9")

    }

    /** forward 模式：探活重试建连 + 同一连接上读完握手（探活后的流数据全在这条连接上） */
    private suspend fun connectForwardSocket(port: Int): Pair<Socket, ScrcpyHandshake> {
        var socket: Socket? = null
        val connection = ScrcpyHandshakeReader().connectForward {
            runCatching {
                Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 2_000) }
            }.getOrNull()?.also { socket = it }?.getInputStream()
        }
        return requireNotNull(socket) { "探活成功但 socket 丢失" } to connection.handshake
    }

    /**
     * leak 模式：验流成立后不做任何收尾，挂起 30s 等外部 `kill -9 <PID>`；
     * 窗口内未杀则以可读报错提示重跑（测试进程退出即 fd 关闭，与强杀等价）。
     */
    private suspend fun runLeak(scope: CoroutineScope) {
        val env = SpikeEnv.resolve()
        val scid = newScid()
        val socketName = ScrcpyProtocol.deviceSocketName(scid)
        val adb = SimpleShellScrcpyAdbGateway(adbPath = env.adbPath, serial = env.serial)
        adb.execute(ScrcpyServerDeployer.buildPushCommand(env.serverJar.absolutePath))
        val opener = ScrcpyTunnelOpener(adb, socketName)
        val tunnel = opener.open()
        val runner = StreamingShellScrcpyRunner()
        val serverJob = scope.launch(Dispatchers.IO) {
            runner.run(
                com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite(
                    ScrcpyServerDeployer.buildServeCommand(scid, tunnelForward = tunnel is ScrcpyTunnel.Forward),
                    env.adbPath,
                    env.serial,
                ),
            ) { }
        }
        delay(1_000)
        val (videoSocket, handshake) = when (tunnel) {
            is ScrcpyTunnel.Reverse -> {
                val socket = acceptReverseWithHardTimeout(tunnel)
                socket to ScrcpyHandshakeReader().read(socket.getInputStream())
            }
            is ScrcpyTunnel.Forward -> connectForwardSocket(tunnel.port)
        }
        println("[spike][leak] 流已验证（设备名 ${handshake.deviceName} codec=0x%08x）".format(handshake.codecId))
        println("[spike][leak] 本测试进程 PID=${ProcessHandle.current().pid()}——30s 内 kill -9 之，然后跑 verify9")
        try {
            withTimeout(30_000) { awaitCancellation() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("leak 窗口内未执行 kill -9（PID 见上）；kill 后再跑 SCRCPY_SPIKE=verify9", e)
        }
    }

    /** verify9 模式：核验强杀后旧 server 自然退出 + 新 scid 复连成功 + 复连收尾干净 */
    private suspend fun runVerify9(scope: CoroutineScope) {
        val env = SpikeEnv.resolve()
        val adb = SimpleShellScrcpyAdbGateway(adbPath = env.adbPath, serial = env.serial)
        assertServerProcessGone(adb)
        println("[spike] Major-3 验收点 3 通过：kill -9 后旧 server 已自然退出（EOF→Completion→Looper 退出链成立）")

        val scid = newScid()
        val socketName = ScrcpyProtocol.deviceSocketName(scid)
        val opener = ScrcpyTunnelOpener(adb, socketName)
        val tunnel = opener.open()
        val runner = StreamingShellScrcpyRunner()
        val serverJob = scope.launch(Dispatchers.IO) {
            runner.run(
                com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite(
                    ScrcpyServerDeployer.buildServeCommand(scid, tunnelForward = tunnel is ScrcpyTunnel.Forward),
                    env.adbPath,
                    env.serial,
                ),
            ) { }
        }
        delay(1_000)
        val (videoSocket, handshake) = when (tunnel) {
            is ScrcpyTunnel.Reverse -> {
                val socket = acceptReverseWithHardTimeout(tunnel)
                socket to ScrcpyHandshakeReader().read(socket.getInputStream())
            }
            is ScrcpyTunnel.Forward -> connectForwardSocket(tunnel.port)
        }
        assertEquals(ScrcpyProtocol.CODEC_H264, handshake.codecId)
        println("[spike] 复连成功（新 scid=%08x 设备名 ${handshake.deviceName}）".format(scid))
        withContext(NonCancellable) {
            runner.stop()
            serverJob.cancel()
            runCatching { videoSocket.close() }
            (tunnel as? ScrcpyTunnel.Reverse)?.let { runCatching { it.listener.close() } }
            opener.close(tunnel)
            assertServerProcessGone(adb)
            assertTunnelRulesRemoved(adb, socketName)
            println("[spike] verify9 收尾核对通过")
        }
    }

    /** Major-3 验收点 1：设备端 app_process 必须消失（15 次 × 1s 轮询，残留即失败） */
    private suspend fun assertServerProcessGone(adb: ScrcpyAdbGateway) {
        var alive: List<String> = emptyList()
        for (attempt in 1..15) {
            val result = adb.execute("adb shell ps -A")
            alive = result.getOrNull()?.stdout.orEmpty()
                .lineSequence()
                .filter { "com.genymobile.scrcpy.Server" in it }
                .toList()
            if (alive.isEmpty()) return
            println("[spike] 等 server 退出（第 $attempt 次核对，仍 ${alive.size} 条）: $alive")
            delay(1_000)
        }
        throw AssertionError("Major-3 验收失败：设备端 app_process 15s 未退出: $alive")
    }

    /** Major-3 验收点 2：forward/reverse 规则必须清空 */
    private suspend fun assertTunnelRulesRemoved(adb: ScrcpyAdbGateway, socketName: String) {
        val forwardList = adb.execute("adb forward --list").getOrNull()?.stdout.orEmpty()
        val reverseList = adb.execute("adb reverse --list").getOrNull()?.stdout.orEmpty()
        assertTrue(socketName !in forwardList, "Major-3 验收失败：forward 规则残留: $forwardList")
        assertTrue(socketName !in reverseList, "Major-3 验收失败：reverse 规则残留: $reverseList")
    }

    private fun percentiles(nanos: List<Long>): Array<Double> {
        if (nanos.isEmpty()) return arrayOf(Double.NaN, Double.NaN, Double.NaN)
        val sorted = nanos.sorted()
        fun p(q: Double): Double =
            sorted[(q * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)] / 1_000_000.0
        return arrayOf(p(0.50), p(0.95), p(0.99))
    }

    private fun newScid(): Long = (0L until ScrcpyServerDeployer.SCID_MAX_EXCLUSIVE).random()

    private fun File.sha256(): String = inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 环境装配：serial 默认经 `adb devices` 探测（走 AdbCommandRewriter + SimpleShell 合规通道） */
    private class SpikeEnv(
        val adbPath: String,
        val serial: String,
        val serverJar: File,
        val outDir: String,
        val maxFrames: Int,
        val seconds: Int,
        val maxSize: Int?,
        val maxFps: Float?,
    ) {
        companion object {
            suspend fun resolve(): SpikeEnv {
                val adbPath = System.getenv("SCRCPY_ADB") ?: ""
                val serial = System.getenv("SCRCPY_SERIAL") ?: firstDeviceSerial(adbPath)
                val serverJar = File(
                    System.getenv("SCRCPY_SERVER_JAR")
                        ?: (System.getProperty("user.home") + "/.toolbelt/scrcpy/scrcpy-server-v5.0.1"),
                )
                check(serverJar.isFile) {
                    "server jar 不存在: ${serverJar.absolutePath}（按 runbook 步骤 2 下载官方 release）"
                }
                return SpikeEnv(
                    adbPath = adbPath,
                    serial = serial,
                    serverJar = serverJar,
                    outDir = System.getenv("SCRCPY_SPIKE_OUT") ?: "build/scrcpy-spike",
                    maxFrames = System.getenv("SCRCPY_MAX_FRAMES")?.toIntOrNull() ?: 300,
                    seconds = System.getenv("SCRCPY_SECONDS")?.toIntOrNull() ?: 10,
                    maxSize = System.getenv("SCRCPY_MAX_SIZE")?.toIntOrNull(),
                    maxFps = System.getenv("SCRCPY_MAX_FPS")?.toFloatOrNull(),
                )
            }

            private suspend fun firstDeviceSerial(adbPath: String): String {
                val command = com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter.rewrite("adb devices", adbPath, serial = null)
                val result = com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell.execute(command)
                val stdout = result.getOrNull()?.stdout.orEmpty()
                return stdout.lineSequence()
                    .filter { "\tdevice" in it }
                    .map { it.substringBefore('\t').trim() }
                    .firstOrNull()
                    ?: throw IllegalStateException("adb devices 无在线设备——真机判据待设备在线（维护者配合项）")
            }
        }
    }

    /**
     * reverse 隧道 accept（双保险等待）：withTimeout(20s) 管「server 反连慢但会来」的
     * 正常超时；[ScrcpyTunnel.Reverse.listener] 的 soTimeout 管「server 永不反连」的
     * native 阻塞兜底（withTimeout 取消唤不醒 accept，见
     * [REVERSE_ACCEPT_SO_TIMEOUT_MS]）。
     */
    private suspend fun acceptReverseWithHardTimeout(tunnel: ScrcpyTunnel.Reverse): Socket {
        tunnel.listener.soTimeout = REVERSE_ACCEPT_SO_TIMEOUT_MS
        return withTimeout(20_000) { tunnel.listener.accept() }
    }

    private companion object {
        const val MODE_FULL = "full"
        const val MODE_LEAK = "leak"
        const val MODE_VERIFY9 = "verify9"

        /**
         * reverse 隧道 accept 等待上限（ms）：⚠️ [ServerSocket.accept] 是阻塞 native
         * 调用，外层 withTimeout 对它**不可取消**——真机侧 server 拉起失败/未反连时
         * 协程取消无法唤醒 accept，Gradle worker 挂死直到被 kill -9。soTimeout 是
         * 唯一兜底：超时抛 SocketTimeoutException 显式失败，不静默挂死。取值须
         * 大于 withTimeout 的 20s 正常路径上限（soTimeout 只兜 withTimeout 醒不过来的
         * native 阻塞，正常路径仍由 withTimeout 先触发）。
         */
        const val REVERSE_ACCEPT_SO_TIMEOUT_MS = 25_000
    }
}
