package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.graphics.ImageBitmap
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource

/**
 * 引擎/VM/场景测试共用 fakes 与本地 socket 承载的 reverse 隧道 harness（jvmTest 内部件）。
 * adb / runner / decoder 三接缝全 fake，server 反连角色由测试侧 socket 扮演——不触真 adb。
 */

/**
 * 一次性 adb 命令 fake：记录全部命令，[failOn] 命中的命令显式失败（IOException），
 * [cancelOn] 命中的命令返回承载 [CancellationException] 的 Failure——模拟生产执行通道
 * 把协程取消并进 Failure 的吞没形态（结果未观测）。
 */
internal class FakeAdbGateway(
    private val failOn: Regex? = null,
    private val cancelOn: Regex? = null,
) : ScrcpyAdbGateway {
    val commands = LinkedBlockingQueue<String>()

    /**
     * pull 模拟器（`adb pull <remote> <local>` 形态命中时以 (remote, local) 调用——
     * 写本地产物的责任在测试侧；null = pull 不落文件，设备实拍路径因产物校验失败
     * 回退预览帧）。failOn/cancelOn 优先级在本钩子之上。
     */
    var pullSimulator: ((String, String) -> Unit)? = null

    override suspend fun execute(command: String): Result<ShellResult> {
        commands.add(command)
        return when {
            cancelOn?.containsMatchIn(command) == true ->
                Result.failure(CancellationException("cancelled: $command"))

            failOn?.containsMatchIn(command) == true ->
                Result.failure(IOException("adb 拒绝执行: $command"))

            else -> {
                pullSimulator?.let { simulate ->
                    val parts = command.trim().split(WHITESPACE)
                    if (parts.size == 4 && parts[0] == "adb" && parts[1] == "pull") {
                        simulate(parts[2], parts[3])
                    }
                }
                Result.success(ShellResult(0, "", ""))
            }
        }
    }

    private companion object {
        val WHITESPACE = "\\s+".toRegex()
    }
}

/** server 长跑流 fake：[run] 挂起至 [stop]，记录 serve 命令 */
internal class FakeRunner : ScrcpyServerProcessRunner {
    val commands = LinkedBlockingQueue<String>()
    private val stopSignal = CompletableDeferred<Unit>()

    override suspend fun run(command: String, onLine: suspend (String) -> Unit): Int? {
        commands.add(command)
        stopSignal.await()
        return 0
    }

    override fun stop() {
        stopSignal.complete(Unit)
    }
}

/**
 * 解码器 fake：[offer] 入帧、[endStream]/signalEnd 置终结位；[armLateFrame] 模拟
 * 「停止解锁后僵尸解码协程醒来带旧帧」的迟到产物路径（世代号防线用例）；
 * [failWith] 注入坏流（解码失败路径用例——grab 抛该异常）。
 */
internal class FakeDecoder : ScreencastDecoder {
    val packets = LinkedBlockingQueue<ByteArray>()
    private val pending = LinkedBlockingQueue<ScreencastDecodedFrame>()

    @Volatile
    private var end = false

    @Volatile
    private var lateFrame: ScreencastDecodedFrame? = null

    /** 坏流注入：置位后下一次 grab 抛该异常 */
    @Volatile
    var failWith: IOException? = null

    override fun onPacket(payload: ByteArray) {
        packets.add(payload)
    }

    override fun signalEnd() {
        end = true
    }

    fun offer(frame: ScreencastDecodedFrame) {
        pending.offer(frame)
    }

    fun endStream() {
        end = true
    }

    fun armLateFrame(frame: ScreencastDecodedFrame) {
        lateFrame = frame
    }

    override fun grab(): ScreencastDecodedFrame? {
        while (true) {
            failWith?.let { throw it }
            val frame = pending.poll(100, TimeUnit.MILLISECONDS)
            if (frame != null) return frame
            if (end) {
                val late = lateFrame
                lateFrame = null
                if (late != null) return late
                return null
            }
        }
    }

    override fun close() {
        end = true
    }
}

/**
 * 录像器 fake：记录 start 参数与逐帧载荷（帧数对账/起点素材断言用）；
 * [failOnFrame]/[failOnFinish] 注入录像面失败路径（收口/抢救语义用例）。
 * 全部方法由引擎录像锁串行化调用（与生产实现同一线程契约），无须自持锁。
 */
internal class FakeRecorder : ScreencastRecorder {

    /** 起步参数快照（null = start 未被调） */
    var startedConfig: ByteArray? = null
        private set
    var startedWidth = 0
        private set
    var startedHeight = 0
        private set
    var startedTarget: File? = null
        private set

    val frames = LinkedBlockingQueue<Pair<Long, ByteArray>>()
    var keyFrameCount = 0
        private set

    /** 写帧失败注入：置位后下一次 onFrame 抛该异常 */
    @Volatile
    var failOnFrame: IOException? = null

    /** 收尾失败注入：finish 抛该异常（残件清理语义用例） */
    @Volatile
    var failOnFinish: IOException? = null

    var finishCalled = false
        private set

    var closed = false
        private set

    override fun start(configAnnexb: ByteArray, width: Int, height: Int, target: File) {
        startedConfig = configAnnexb
        startedWidth = width
        startedHeight = height
        startedTarget = target
    }

    override fun onFrame(ptsUs: Long, keyFrame: Boolean, payload: ByteArray) {
        failOnFrame?.let { throw it }
        frames.add(ptsUs to payload)
        if (keyFrame) keyFrameCount++
    }

    override fun finish(): Long {
        finishCalled = true
        failOnFinish?.let { throw it }
        return frames.size.toLong()
    }

    override fun close() {
        closed = true
    }
}

/** 设备三流 fake（[ScreencastViewModel] 前置检查消费面；不触 :core 检测链路） */
internal class FakeDeviceSource(
    state: DeviceState = DeviceState.None,
    error: String? = null,
    selected: String? = null,
) : DeviceStateSource {
    override val deviceState: StateFlow<DeviceState> = MutableStateFlow(state).asStateFlow()
    override val detectError: StateFlow<String?> = MutableStateFlow(error).asStateFlow()
    override val selectedSerial: StateFlow<String?> = MutableStateFlow(selected).asStateFlow()
}

/** 纯色帧（场景渲染像素锚用）：[color] 0xRRGGBB 整型（恒置不透明 alpha），[size] 见方 */
internal fun solidFrame(size: Int = 64, color: Int): ScreencastDecodedFrame {
    val image = ImageBitmap(size, size)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    val paint = androidx.compose.ui.graphics.Paint().apply {
        // 🔴 color 须 OR 上不透明 alpha——Color(Int) 是 ARGB 整型，6 位色值即全透明帧
        this.color = androidx.compose.ui.graphics.Color((0xFF shl 24) or color)
    }
    canvas.drawRect(
        androidx.compose.ui.geometry.Rect(0f, 0f, size.toFloat(), size.toFloat()),
        paint,
    )
    return ScreencastDecodedFrame(image = image, width = size, height = size)
}

/**
 * reverse 隧道 + 握手全真链路 harness（本地 socket 扮演 server 反连角色）：
 * [startAndHandshake] 启动引擎会话并写完握手三段 + 会话包 + config + 首帧包，
 * 之后测试经 [decoder] 注入解码帧驱动 Streaming/帧发布。
 */
internal class ScreencastHarness(
    failOn: Regex? = null,
    cancelOn: Regex? = null,
    private val connectTimeoutMs: Long = 5_000,
    private val acceptSoTimeoutMs: Int = 8_000,
    private val handshakeSoTimeoutMs: Int = 3_000,
) : AutoCloseable {
    /** 端口占位监听（首次 bind 复用本体；后续会话在前次收尾关掉后同口重绑） */
    private val reservedListener = ServerSocket(0)
    val port: Int get() = reservedListener.localPort
    private var firstBind = true
    val adb = FakeAdbGateway(failOn, cancelOn)
    val runner = FakeRunner()
    /** 每会话一个解码器实例（按创建序记录；[decoder] 恒指最新会话的） */
    val decoders = mutableListOf<FakeDecoder>()
    val decoder: FakeDecoder get() = decoders.last()
    /** 每段录像一个录像器实例（按创建序记录；[recorder] 恒指最新一段的） */
    val recorders = mutableListOf<FakeRecorder>()
    val recorder: FakeRecorder get() = recorders.last()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var nowMs = 0L
    // 随包 jar 的隔离字节副本（临时目录，不触用户数据根）：释放器对已在位文件做 SHA256
    // 校验，空临时文件会在会话起点被校验拦下、走不到 push/隧道段
    val serverJar = File.createTempFile("scrcpy-server", ".jar").apply {
        ScrcpyServerJarReleaser::class.java
            .getResourceAsStream(ScrcpyServerJarReleaser.RESOURCE_PATH)!!
            .use { writeBytes(it.readBytes()) }
        deleteOnExit()
    }

    /** adb 工厂捕获调用参数（adbPath/serial 透传断言用） */
    val gatewayCalls = LinkedBlockingQueue<Pair<String, String?>>()

    val engine = ScreencastEngine(
        scope = scope,
        runnerFactory = { runner },
        adbGatewayFactory = { adbPath, serial ->
            gatewayCalls.add(adbPath to serial)
            adb
        },
        listenerFactory = { candidate ->
            when {
                candidate != port -> null
                firstBind -> reservedListener.also { firstBind = false }
                // 首次 bind 的监听已随前次会话收尾关闭——同口重绑承载后续会话
                else -> ServerSocket(port)
            }
        },
        decoderFactory = { FakeDecoder().also { decoders += it } },
        recorderFactory = { FakeRecorder().also { recorders += it } },
        serverJarFile = serverJar,
        portRange = port..port,
        connectTimeoutMs = connectTimeoutMs,
        acceptSoTimeoutMs = acceptSoTimeoutMs,
        handshakeSoTimeoutMs = handshakeSoTimeoutMs,
        clock = { nowMs },
    )
    /** 全部测试侧「server 角色」连接（拔线用例逐个 close） */
    val clients = mutableListOf<Socket>()
    var client: Socket? = null

    /** 测试侧「server 角色」的控制通道连接（注入字节回读面） */
    var controlClient: Socket? = null

    /**
     * 启动会话并以「server 角色」写完握手 + 会话包 + config + 首帧包（连接保持打开——提前关流即 EOF 断会话）。
     * 控制通道连接先于握手字节写入（引擎 accept 序 = video → control → 握手读）；
     * [connectControl] = false 时模拟 server 永不反连第二条（控制通道超时失败用例）；
     * [options] 画质参数组（默认值 = 现行为，档位用例注入）。
     */
    fun startAndHandshake(
        width: Int = 800,
        height: Int = 600,
        connectControl: Boolean = true,
        options: ScreencastOptions = ScreencastOptions(),
    ) {
        engine.start("", "TEST-SERIAL", options)
        serverRoleHandshake(width, height, connectControl)
    }

    /**
     * 测试侧扮演「server 角色」：连入引擎监听口并写完握手三段 + 会话包 + config +
     * 首帧包（连接保持打开——提前关流即 EOF 断会话）。供会话起点不经 harness 直启的
     * 用例（如 VM 转发面经 [ScreencastViewModel.startCast] 异步起会话后）复用。
     */
    fun serverRoleHandshake(width: Int = 800, height: Int = 600, connectControl: Boolean = true) {
        // 客户端建连等监听就绪（引擎会话异步起——reverse bind 可能晚于本连接尝试）
        val socket = run {
            var last: Exception? = null
            repeat(100) {
                try {
                    return@run Socket("127.0.0.1", port)
                } catch (e: Exception) {
                    last = e
                    Thread.sleep(20)
                }
            }
            throw IllegalStateException("server 监听口 2s 内未就绪（会话未达 bind）", last)
        }.also {
            clients += it
            client = it
        }
        if (connectControl) {
            Socket("127.0.0.1", port).also {
                clients += it
                controlClient = it
            }
        }
        val out = socket.getOutputStream()
        out.write(deviceNameBytes("TestDevice"))
        out.write(intBytes(ScrcpyProtocol.CODEC_H264))
        out.write(sessionMetaBytes(width, height))
        out.write(packetBytes(ScrcpyProtocol.FLAG_CONFIG, byteArrayOf(1, 2, 3, 4)))
        out.write(packetBytes(ScrcpyProtocol.FLAG_KEY_FRAME, byteArrayOf(5, 6, 7, 8)))
        out.flush()
    }

    /**
     * 精确回读 [expected] 字节的引擎注入控制字节（轮询 available，只取缺口量——多笔
     * 注入连发时不吞后续消息的字节；超时返回已到部分，断言面据此显式失败不挂死）。
     */
    fun readControlBytes(expected: Int, timeoutMs: Long = 3_000): ByteArray {
        val input = controlClient?.getInputStream() ?: error("控制通道连接未建立")
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = ByteArrayOutputStream()
        while (System.currentTimeMillis() < deadline && buffer.size() < expected) {
            while (input.available() > 0 && buffer.size() < expected) {
                buffer.write(input.read())
            }
            if (buffer.size() < expected) Thread.sleep(10)
        }
        return buffer.toByteArray()
    }

    /**
     * 等待并返回第 [sessionCount] 条 serve 命令（runner.run 经 scope.launch 异步落账——
     * Streaming 先到时命令可能尚未入队，轮询等账齐；会话序 = startAndHandshake 调用序）。
     */
    fun serveCommandOfSession(sessionCount: Int, timeoutMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        // LinkedBlockingQueue 实现 Collection——Kotlin 映射 size() 为 size 属性
        while (System.currentTimeMillis() < deadline && runner.commands.size < sessionCount) {
            Thread.sleep(10)
        }
        return runner.commands.toList().getOrNull(sessionCount - 1)
            ?: error("serve 命令未落账（期望第 $sessionCount 条，实际 ${runner.commands.size} 条）")
    }

    override fun close() {
        engine.stop()
        clients.forEach { runCatching { it.close() } }
        runCatching { reservedListener.close() }
        scope.cancel()
    }
}

// ---------- 流字节构造（与 wire 契约 ScrcpyProtocol 同源） ----------

internal fun deviceNameBytes(name: String): ByteArray {
    val buf = ByteArray(ScrcpyProtocol.DEVICE_NAME_FIELD_LENGTH)
    val bytes = name.toByteArray(Charsets.UTF_8)
    System.arraycopy(bytes, 0, buf, 0, minOf(bytes.size, buf.size - 1))
    return buf
}

internal fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

internal fun sessionMetaBytes(width: Int, height: Int): ByteArray {
    val buf = ByteBuffer.allocate(ScrcpyProtocol.FRAME_HEADER_SIZE)
    buf.put(0, 0x80.toByte()) // bit63 会话包标记（flags int 低 bit0 未置 = 非 clientResize）
    buf.putInt(4, width)
    buf.putInt(8, height)
    return buf.array()
}

internal fun packetBytes(flags: Long, payload: ByteArray): ByteArray {
    val buf = ByteBuffer.allocate(ScrcpyProtocol.FRAME_HEADER_SIZE)
    buf.putLong(0, flags)
    buf.putInt(8, payload.size)
    return buf.array() + payload
}
