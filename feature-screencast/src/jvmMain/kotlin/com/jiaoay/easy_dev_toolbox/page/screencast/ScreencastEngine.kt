package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 投屏状态机：Idle → Connecting → Streaming → Stopped（Stopped 后可再 [start] 起新会话；
 * Connecting/Streaming 中重入由 [ScreencastEngine.start] 的状态锁拒绝）。
 *
 * [Stopped.failed] = true 时 [Stopped.message] 为显式失败原因（UI 走错误呈现，不静默）；
 * false 为正常收场（用户停止）。
 *
 * 终态不可逆契约：[Stopped] 一经落定，本会话任何迟到的发布（pump 在途事件/僵尸解码
 * 协程醒来）都被 [ScreencastEngine.isPublishable] 闸丢弃——Streaming/帧/终态三面共用
 * 同一闸，不存在把 Stopped 回退成 Streaming 的窗口。
 */
sealed class ScreencastStatus {
    data object Idle : ScreencastStatus()
    data object Connecting : ScreencastStatus()

    /**
     * 投屏中。[serial] = 会话绑定的设备串号（[ScreencastEngine.start] 时刻锁定值透传：
     * API 层可空——生产面前置检查拦截后实际非 null。消费面跨串号门控在宿主
     * DeviceObsPage.layoutLightUpDecision，按「会话串号 null 不证伪」处理）。
     */
    data class Streaming(
        val deviceName: String,
        val serial: String?,
        val width: Int,
        val height: Int,
    ) : ScreencastStatus()

    data class Stopped(val message: String, val failed: Boolean) : ScreencastStatus()
}

/**
 * 投屏帧载荷：[generation] = 发布它的会话世代号（防旧世代帧复活，见 [ScreencastEngine]）。
 */
data class ScreencastFrame(
    val generation: Long,
    val image: ImageBitmap,
)

/**
 * 快照落盘回执（[ScreencastEngine.snapshot]）：无帧/会话已收口/目录不可用/编码写盘
 * 失败一律显式 [Failed] 回执，不静默成功也不抛给 UI。
 */
sealed interface ScreencastSnapshotOutcome {

    /** 落盘成功：[file] = 已写入的 PNG 文件（设备名 + 时间戳命名，多设备不互覆） */
    data class Saved(val file: File) : ScreencastSnapshotOutcome

    /** 显式失败：[reason] 为人话原因 */
    data class Failed(val reason: String) : ScreencastSnapshotOutcome
}

/**
 * 画质参数组（[ScreencastEngine.start] 的可选参数——data class 单点扩，防签名膨胀）。
 *
 * 起点生效契约：start 时刻快照进会话，会话中途改档不热更（server 端无中途改档通道，
 * 重开会话才生效）。
 *
 * null 语义 = 不追加 key=value（server 端默认）：[videoBitRate] null = 默认码率 8M
 * （上游 scrcpy cli.c `--video-bit-rate` 默认文案锚）；[maxSize] null = 原始分辨率；
 * [maxFps] null = server 端无上限。**全默认值与扩参前现行为逐字节一致**（max_fps
 * 恒 30 追加、其余零追加）——既有调用点零漂移的兼容面。
 */
data class ScreencastOptions(
    val videoBitRate: Long? = null,
    val maxFps: Float? = ScreencastEngine.DEFAULT_MAX_FPS,
    val maxSize: Int? = null,
)

/**
 * 投屏引擎（画面回传 + 控制回传）。
 *
 * 全链路：server jar 就位（目标路径已有文件直接用——用户自置优先；缺失时经
 * [ScrcpyServerJarReleaser] 从随包资源释放；版本残留冲突/释放失败为会话级显式失败）
 * → push server jar → 隧道（reverse 优先 fallback forward，[ScrcpyTunnelOpener]）
 * → `adb shell app_process` 拉起（长跑流，[ScrcpyServerProcessRunner]）→ 握手（探活/设备名/
 * codec id，[ScrcpyHandshakeReader]）→ demuxer（[ScrcpyDemuxer]）→ 解码
 * （[ScreencastDecoder]）→ 帧流发布；控制面 = video socket 之外的第二条 socket
 * （[ScrcpyControlChannel]，无探活字节——首条 socket 的探活后 server 不再发）。
 *
 * 状态机串行收口：[start]/[stop] 的检查与状态写入在 [stateLock] 下原子完成——双并发
 * start 不可能双双通过重入检查（否则两会话互踩同一隧道规则），世代号分配同锁原子递增。
 * 会话可解锁句柄经 [SessionHandles] 按会话打包：[stop] 只关「当前会话」的句柄，会话
 * 收尾只关**本会话局部**句柄（不关引擎共享字段——旧会话的 finally 不得误杀新会话资源）。
 *
 * 控制回传收口（与画面面同收口纪律）：注入经 [controlChannel] 引用——只在会话内
 * 非空（握手通过后置位，收尾 finally 内按 === 判定清空），停止/断开后注入返回 false
 * 不抛不静默复活；控制通道的建立失败是会话级显式失败（server 按 control=true 必然
 * 建第二条 socket，第二条不到即握手异常）。注入目标设备 = 会话绑定的串号（[start]
 * 时锁定选定设备语义——多设备注入复用设备观测页的选定态，不广播）。
 *
 * 帧流发布契约（实时抓取引擎通道语义的对偶——文本流不丢行，视频流**合帧保最新**）：
 * [frame] StateFlow 即合帧通道——StateFlow 等值合并 + 只保最新，慢消费方（Compose 重组
 * 按帧率上限驱动）自动丢中间帧，引擎不排队不背压、内存恒 O(1)。帧位图为解码器池化槽
 * （`ScreencastFrameBitmapPool`，同槽在其后 2 次发布后重装覆写）——消费面按「只读最新
 * 一拍」消费（面板重组绘制与快照的预览帧回退路径均满足），跨多帧持有得到被覆写的陈旧画面。
 *
 * 录像面（demux 旁路，边看边录）：pump 内 config/帧事件在解码之外同源分叉给录像 muxer
 * （[ScreencastRecorder]，H.264 stream copy 封装 mp4 零重编码）；起录向 server 发
 * reset_video 令编码器即时重配（新 config + 关键帧随到随录）。录像面故障只失败录像并落
 * [recordingResult] 回执，不进会话失败单槽（控制面只读回退的对偶纪律）；会话任一终态
 * 收尾时在录的录像随会话落盘。
 *
 * 显示触点面（会话期临时开关）：[setShowTouches] 经 [ScreencastShowTouches] 改写设备端
 * `show_touches` 系统设置（恢复语义三件与崩溃安全序见其 KDoc）；收口恢复挂会话协程
 * finally 的 NonCancellable 段单点——全部会话终态路径（停投/关窗/断连/退出序）经此恢复
 * 设备原值，不散点。
 *
 * 世代号（防复活，实时抓取引擎 displayGeneration 配方同款）：每次 [start] 在状态锁下
 * 递增 [activeGeneration]，会话内全部发布（帧/Streaming 态/Stopped 终态）经
 * [isPublishable] 三重闸——世代失配（新会话已起）、停止已落、终态已定三者任一命中即丢弃：
 * 停止后仍阻塞在原生读里的旧会话协程醒来时的发布不得复活进 [frame]/[status]；旧会话的
 * Stopped 终态同样不得覆盖新会话的 Connecting/Streaming 态。
 *
 * 失败通道（真因必进终态，不进全局未处理协程通道）：会话体与 pump 协程的全部异常
 * （含解码器坏流/写管道失败/平台解码组件缺失的 `LinkageError`）收敛进会话局部失败单槽，
 * 终态 [Stopped.message] 承载真因（带链段语境——段名前缀 + message，message 为 null
 * 时保留类名但仍有段名定位，不落无链段的类名裸串）——拔线/坏流是可预期故障，
 * 弹崩溃报告属于故障呈现错档。
 * **控制面故障不进失败单槽**：设备消息 pump（[ScrcpyDeviceMessageReader]）的读异常只
 * 结束控制回执（画面面继续——只读回退），注入失败（写异常）按调用返回 false。
 *
 * 终止语义：**用户停止按钮（[stop]）唯一终止**——长跑流不入任何串行执行队列、无超时
 * 看门狗。[stop] 解锁四类原生阻塞（video socket 读 → demuxer 返回 null、listener accept
 * （soTimeout 兜底）、decoder 管道读（signalEnd）、控制 socket 读 → 设备消息 pump 退出）
 * 后收割 runner；收尾（remove 隧道规则/关句柄）在会话协程 finally 的 [NonCancellable]
 * 段执行——取消态下 suspend 清理会被协程取消短路，adb remove 规则将残留（spike runbook §1 判据 9）。
 *
 * 线程模型：会话体经 [Dispatchers.IO] 跑（全程长阻塞——accept/握手读/管道读，
 * [ScrcpyHandshakeReader] 自述「调用方负责放到 IO 调度器」）；pump/server 长跑流/设备
 * 消息 pump 协程同在 IO；注入入口由 UI 线程直调（[ScrcpyControlChannel.send] 锁内小笔
 * 写，无长阻塞面）。
 *
 * 超时纪律（全显式失败不静默）：
 * - reverse accept：`withTimeout(connectTimeoutMs)` 管「server 反连慢但会来」的正常超时
 *   （TimeoutCancellationException 显式落失败终态，不冒充「已停止」）；listener 的
 *   `soTimeout = acceptSoTimeoutMs` 是 native 兜底——阻塞 accept 对协程取消免疫，
 *   soTimeout 是唯一能唤醒阻塞线程的通道（取值必须大于 connectTimeoutMs，正常路径仍
 *   由 withTimeout 先触发）；控制通道第二条 accept 同款双闸；
 * - 握手段（探活/设备名/codec id）：socket `soTimeout = handshakeSoTimeoutMs`——server
 *   就绪后立即写完这三段，读不到即失败；控制通道无握手段（无探活字节/无设备名），
 *   建连成功即通道就绪；forward 探活重试段（[ScrcpyHandshakeReader.connectForward]）
 *   整段经 `withTimeout(connectTimeoutMs)` 总闸——与 reverse accept 同源参数，无总闸时
 *   重试预算按 attempts × 间隔无界展开；
 * - 流态读**无超时**（soTimeout 置 0）：静止画面协议上无新数据（server 只在内容变化时
 *   发帧），读超时会把「画面静止」误判为断流；终止 = [stop] 关 socket（demuxer 把
 *   SocketException 归类为终止信号返回 null）或对端断开（EOF）。
 */
class ScreencastEngine(
    private val scope: CoroutineScope,
    private val runnerFactory: () -> ScrcpyServerProcessRunner = ::StreamingShellScrcpyRunner,
    private val adbGatewayFactory: (adbPath: String, serial: String?) -> ScrcpyAdbGateway =
        ::SimpleShellScrcpyAdbGateway,
    private val listenerFactory: ScrcpyTunnelOpener.LocalListenerFactory =
        ScrcpyTunnelOpener.ServerSocketListenerFactory,
    private val decoderFactory: ScreencastDecoderFactory = JavaCvScreencastDecoderFactory,
    private val recorderFactory: ScreencastRecorderFactory = AvformatScreencastRecorderFactory,
    private val serverJarFile: File = defaultServerJar(),
    private val serverJarReleaser: (File) -> ScrcpyServerJarReleaser.Outcome =
        ScrcpyServerJarReleaser::release,
    private val portRange: IntRange =
        ScrcpyServerDeployer.PORT_RANGE_FIRST..ScrcpyServerDeployer.PORT_RANGE_LAST,
    private val probeRetry: ScrcpyHandshakeReader.ProbeRetryPolicy = ScrcpyHandshakeReader.ProbeRetryPolicy(),
    private val connectTimeoutMs: Long = 20_000L,
    private val acceptSoTimeoutMs: Int = 25_000,
    private val handshakeSoTimeoutMs: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 本会话可解锁句柄包：[stop] 经 [currentHandles] 只触达「当前会话」；会话收尾只关
     * 自己的局部引用（字段写入仅供 stop 解锁读），旧会话不存在误杀新会话资源的通道。
     */
    private class SessionHandles {
        @Volatile
        var runner: ScrcpyServerProcessRunner? = null

        @Volatile
        var tunnel: ScrcpyTunnel? = null

        @Volatile
        var socket: Socket? = null

        @Volatile
        var decoder: ScreencastDecoder? = null

        /** 控制通道 socket（video 之外的第二条连接）；关流即解锁设备消息 pump 的阻塞读 */
        @Volatile
        var controlSocket: Socket? = null
    }

    private val _status = MutableStateFlow<ScreencastStatus>(ScreencastStatus.Idle)
    val status: StateFlow<ScreencastStatus> = _status.asStateFlow()

    /** 最新帧（合帧保最新；null = 当前无画面） */
    private val _frame = MutableStateFlow<ScreencastFrame?>(null)
    val frame: StateFlow<ScreencastFrame?> = _frame.asStateFlow()

    /** 近窗帧率（fps，[FPS_WINDOW_MS] 窗口内帧数折算；静止画面窗口自然回落） */
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    /**
     * 最近一次 set_clipboard 的落地回执 sequence（0 = 尚无回执；每会话重置）。
     * 请求侧序号由 [clipboardSequence] 自增（恒 ≥ 1——0 是协议「无回执」哨兵），
     * 消费方（面板/真机验收）以「请求序号 == 回执值」闭环实证剪贴板已落设备端。
     */
    private val _controlAck = MutableStateFlow(0L)
    val controlAck: StateFlow<Long> = _controlAck.asStateFlow()

    /**
     * 设备端剪贴板读回/推送内容（null = 本会话尚未收到；每会话重置）。写路径 =
     * 设备消息 pump 的 TYPE_CLIPBOARD 消费面：[controlRequestClipboard] 显式请求的
     * 回推（serve 命令恒带 `clipboard_autosync=false`——server 据此不做变更监听推送、
     * 只响应显式 get_clipboard 读回，本流内容恒来自读回响应）。
     */
    private val _deviceClipboard = MutableStateFlow<String?>(null)
    val deviceClipboard: StateFlow<String?> = _deviceClipboard.asStateFlow()

    /** start/stop 检查与状态写入/世代分配的单一串行收口（双击并发起点的原子面） */
    private val stateLock = Any()

    /**
     * 当前会话显示触点控制器（start 时刻以会话 adbPath/serial 建造；会话收口 finally
     * 按 === 判定清空——切换入口只认当前会话的控制器，旧会话控制器不得残留）。
     */
    @Volatile
    private var showTouchesSession: ScreencastShowTouches? = null

    /**
     * 显示触点开关态（会话级：[start] 重置 false，收口恢复后回落 false）。真值由
     * [ScreencastShowTouches.enabled] 承载——本流实例跨会话复用，会话控制器以它为
     * 开关态宿主（收口回落 false 经控制器写入，无第二真源）。
     */
    private val _showTouchesEnabled = MutableStateFlow(false)
    val showTouchesEnabled: StateFlow<Boolean> = _showTouchesEnabled.asStateFlow()

    /** 当前会话世代号（仅 [stateLock] 下写；发布方经 [isPublishable] 读它丢弃旧世代在途产物） */
    @Volatile
    private var activeGeneration = 0L

    @Volatile
    private var stopRequested = false

    @Volatile
    private var sessionJob: Job? = null

    /** 当前会话句柄包（仅最新会话在位；旧会话收尾经 === 判定只清自己的） */
    @Volatile
    private var currentHandles: SessionHandles? = null

    /**
     * 当前会话控制通道（握手通过后置位；收尾按 === 判定清空——旧会话的通道不得
     * 残留给新会话，注入入口只认「当前会话」的通道）。null = 无可用控制面，
     * 注入入口返回 false。
     */
    @Volatile
    private var controlChannel: ScrcpyControlChannel? = null

    /** set_clipboard 请求序号（进程内单调自增；0 保留为协议「无回执」哨兵） */
    private val clipboardSequence = AtomicLong(0)

    /**
     * 上次受理会话的启动参数（[restart] 真源）：连接段真重试/停投重开按原参重启。
     * 在 [start] 受理点覆写（被拒的 start 不覆盖——重启不得拾起从未启动过的参数）。
     */
    @Volatile
    private var lastStartParams: ScreencastStartParams? = null

    /** [start] 受理参数的留存载体（[restart] 按原参重启的单一真源） */
    private class ScreencastStartParams(
        val adbPath: String,
        val serial: String?,
        val options: ScreencastOptions,
        val tunnelPreference: TunnelPreference,
    )

    /**
     * 开始一次投屏会话。检查+状态写入+世代分配在 [stateLock] 下原子完成——并发双击只有
     * 一个返回 true（另一路见到已落定的 Connecting 即拒）；每次开始重置帧/帧率（旧会话
     * 残留帧由世代号防线丢弃）。终态（含失败）由会话协程异步落定，调用方经 [status] 订阅。
     * [options] 画质参数组在 start 时刻快照（起点生效），默认值 = 现行为。
     * [tunnelPreference] 隧道路径偏好透传 [ScrcpyTunnelOpener]（起点生效语义同 [options]），
     * 默认 [TunnelPreference.ReverseFirst] = 现行为零漂移；
     * [TunnelPreference.ForwardOnly] 供 E2E 驱动 forward 隧道形态
     * （生产面无消费者——reverse 不通设备经默认偏好 fallback 链自动落 forward）。
     */
    fun start(
        adbPath: String,
        serial: String?,
        options: ScreencastOptions = ScreencastOptions(),
        tunnelPreference: TunnelPreference = TunnelPreference.ReverseFirst,
    ): Boolean {
        synchronized(stateLock) {
            val current = _status.value
            if (current is ScreencastStatus.Connecting || current is ScreencastStatus.Streaming) return false
            activeGeneration += 1
            val generation = activeGeneration
            stopRequested = false
            _frame.value = null
            _fps.value = 0
            _controlAck.value = 0L
            _deviceClipboard.value = null
            // 快照回执清于新会话起点（与录像回执同口径——收尾回执恰在上一会话终态前
            // 落流，能走到本方法即上一会话回执已完成，无时序竞态）；Stopped 终态不清
            _snapshotResult.value = null
            // 录像回执清于新会话起点：收尾回执在上一会话终态前发出（teardown 先落盘后
            // 置 Stopped），能走到本方法即上一会话回执已完成——无时序竞态
            _recordingResult.value = null
            // 显示触点开关态清于新会话起点；会话控制器以会话 adbPath/serial 建造
            // （与 push/隧道链同一网关工厂同源），收口恢复挂会话协程 finally 单点
            _showTouchesEnabled.value = false
            val showTouches = ScreencastShowTouches(
                adb = adbGatewayFactory(adbPath, serial),
                serial = serial,
                enabledState = _showTouchesEnabled,
            ).also { showTouchesSession = it }
            _status.value = ScreencastStatus.Connecting
            lastStartParams = ScreencastStartParams(adbPath, serial, options, tunnelPreference)
            JLog.d(TAG) { "screencast session start: serial=$serial generation=$generation" }
            sessionJob = scope.launch(Dispatchers.IO) {
                runSession(adbPath, serial, generation, options, tunnelPreference, showTouches)
            }
            return true
        }
    }

    /** 请求停止当前会话（幂等；无会话在跑时无副作用）。终态由会话协程 finally 异步落定。 */
    fun stop() {
        val handles: SessionHandles?
        synchronized(stateLock) {
            stopRequested = true
            handles = currentHandles
        }
        // 解锁四类原生阻塞（video socket 读/listener accept/decoder 管道读/控制 socket 读）后收割 runner
        handles?.let { h ->
            runCatching { h.socket?.close() }
            runCatching { h.controlSocket?.close() }
            (h.tunnel as? ScrcpyTunnel.Reverse)?.let { reverse ->
                runCatching { reverse.listener.close() }
            }
            h.decoder?.let { runCatching { it.signalEnd() } }
            h.runner?.stop()
        }
        sessionJob?.cancel()
    }

    /**
     * 停投重开/连接段真重试：停当前会话（幂等）→ **等终态**（带超时上限——终态由会话
     * 协程 finally 异步落定，不 await 即重启必吃 [start] 的重入拒绝，即复现「点了没
     * 反应」的假动作）→ 按上次受理参数重启；[options] 非 null 时覆盖原参数（改档
     * 快照消费）。返回 false = 无历史参数可重启（进程内从未受理过会话）或重启被
     * [start] 拒绝（等终态超时后会话仍悬在连接/投屏中）。
     */
    suspend fun restart(options: ScreencastOptions? = null): Boolean {
        val params = lastStartParams ?: return false
        stop()
        withTimeoutOrNull(RESTART_TERMINAL_AWAIT_MS) {
            status.first { it !is ScreencastStatus.Connecting && it !is ScreencastStatus.Streaming }
        } ?: return false
        return start(params.adbPath, params.serial, options ?: params.options, params.tunnelPreference)
    }

    // ── 显示触点（会话期临时开关，设备系统「显示点按操作」） ────────────────────

    /**
     * 切换会话期显示触点（[ScreencastShowTouches] 恢复语义三件与崩溃安全序见其 KDoc）。
     * 返回 null = 成功（开关态经 [showTouchesEnabled] 流推进）；非 null = 失败原因
     * （无会话/adb 命令失败——不静默）。无会话/会话已收口时开关面不存在，此入口恒拒。
     * 线程模型：调用方须在 IO 调度器（adb 子进程往返）。
     */
    suspend fun setShowTouches(enabled: Boolean): String? {
        val session = showTouchesSession ?: return "仅投屏会话中可切换显示触点——先开始投屏"
        return if (enabled) session.enable() else session.disable()
    }

    // ── 画面快照（设备端实拍 → PNG 落盘，实拍失败回退预览帧；证据面） ──────────

    /**
     * 快照回执单源（Saved/Failed 全形态落流——窗内回执行与主窗面板消费同一持态，
     * 无双写分裂面）：null = 本会话尚无回执；新会话起点（[start]）清，Stopped 终态
     * 不清——拍→停→打开/送出动线不因停投失去回执行。
     */
    private val _snapshotResult = MutableStateFlow<ScreencastSnapshotOutcome?>(null)
    val snapshotResult: StateFlow<ScreencastSnapshotOutcome?> = _snapshotResult.asStateFlow()

    /**
     * 把设备当前屏幕落盘 PNG 到 [outputDir]（宿主传数据根 screenshots/，测试注入
     * 临时目录——路径参数化，不写死）。
     *
     * 快照语义 = 「按下时刻设备当前屏幕」：真源 = 设备端实拍
     * （[ScreencastDeviceSnapshot]——adb screencap/pull，内容为按下瞬间设备画面且恒
     * 原生分辨率）。实拍不可用（screencap 失败/无 adb 会话参数）回退「面板所见最新
     * 帧」——预览帧经 [frame] 合帧通道只保最新，相对设备真实屏幕恒滞后解码管线一拍
     * 且分辨率跟随预览 maxSize 档，回退产物如实承载该语义；无帧/按下即过气的窗口由
     * 世代闸如实收口为 [ScreencastSnapshotOutcome.Failed]。
     *
     * 世代闸与帧流发布共用 [isPublishable]：无帧、停止已落、世代失配、终态已定
     * 任一命中即显式失败——停止后旧帧不复活落盘（世代号语义沿用）。
     *
     * 回执经 [snapshotResult] 持态流出（[snapshotOutcome] 全部返回路径单出口统一
     * 落流——Saved/Failed 全形态，无旁路落点），返回值与流同源。窗内回执行与面板
     * 回执行消费同一流；不落流的成功/失败即窗内静默，违「显式回执不静默」域纪律。
     *
     * 线程模型：挂起函数——设备实拍含 adb 往返（秒级），回退路径编码 PNG 是整帧像素
     * 遍历（1080p 量级百毫秒档），调用方须在 IO 调度器调用，不阻塞 UI 线程。
     */
    suspend fun snapshot(outputDir: File): ScreencastSnapshotOutcome {
        val outcome = snapshotOutcome(outputDir)
        _snapshotResult.value = outcome
        return outcome
    }

    /**
     * 快照判定体（[snapshot] 的落流前段）：无帧/世代闸/非 Streaming/目录创建失败/
     * 落盘异常/Saved 六路返回值全经 [snapshot] 单出口落 [snapshotResult]。
     * 「非 Streaming」分支守「帧在但状态机已离 Streaming」的跨线程缝——start 清帧
     * 与状态写入同在 [stateLock] 内原子完成，常规调用面不可达，属防御分支。
     */
    private suspend fun snapshotOutcome(outputDir: File): ScreencastSnapshotOutcome {
        val frame = _frame.value
            ?: return ScreencastSnapshotOutcome.Failed("当前无画面帧——投屏尚未出图或已停止")
        if (!isPublishable(frame.generation)) {
            return ScreencastSnapshotOutcome.Failed("投屏已收口——旧帧不复活，重开投屏出图后再快照")
        }
        val streaming = _status.value as? ScreencastStatus.Streaming
            ?: return ScreencastSnapshotOutcome.Failed("投屏画面尚未就绪，稍候再快照")
        return try {
            if (!outputDir.isDirectory && !outputDir.mkdirs()) {
                return ScreencastSnapshotOutcome.Failed("截图目录创建失败：${outputDir.absolutePath}")
            }
            val target = productTargetFile(outputDir, streaming.deviceName, EXTENSION_PNG)
            // 设备端实拍为真源（按下瞬间设备画面 + 原生分辨率）；实拍失败回退预览帧
            val captured = lastStartParams?.let { params ->
                ScreencastDeviceSnapshot(adbGatewayFactory(params.adbPath, params.serial), clock).capture(target)
            } ?: false
            if (!captured) {
                writeFramePng(frame.image, target)
            }
            JLog.d(TAG) {
                "screencast snapshot saved: ${target.absolutePath} " +
                    "source=${if (captured) "device" else "preview-frame"}"
            }
            ScreencastSnapshotOutcome.Saved(target)
        } catch (e: Exception) {
            ScreencastSnapshotOutcome.Failed("快照落盘失败：${e.message ?: e::class.simpleName ?: "未知错误"}")
        }
    }

    /**
     * 产物文件名：设备名 + 本地时区毫秒时间戳（多设备并存不互覆；同毫秒撞名序号避让）。
     * 设备名中的文件系统不友好字符（分隔符/空白/控制符）收口为 `_`。
     */
    private fun productTargetFile(dir: File, deviceName: String, extension: String): File {
        val safeName = deviceName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val stamp = SNAPSHOT_TIMESTAMP_FORMAT.format(
            Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()),
        )
        var candidate = File(dir, "${safeName}_$stamp.$extension")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${safeName}_${stamp}-$suffix.$extension")
            suffix++
        }
        return candidate
    }

    /**
     * 帧位图 → PNG 落盘（快照的预览帧回退路径——实拍失败时的兜底产物）。走
     * [ImageBitmap.toPixelMap] 取像素（回退时一次转换，不占解码缝每帧开销——
     * Java2DFrameConverter 的 BufferedImage 是原地复用缓冲，不可跨帧保留引用）；
     * 像素读取与解码线程装帧同锁（池槽装帧覆写窗互斥，见 ScreencastFrameBitmapPool
     * 并发契约——裸读与覆写窗相交即读到半帧内容）；[ImageIO.write] 返回 false
     * （无可用 PNG writer）按失败抛。
     */
    private fun writeFramePng(image: ImageBitmap, target: File) {
        val pixels = synchronized(image.asSkiaBitmap()) { image.toPixelMap() }
        val buffered = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                buffered.setRGB(x, y, pixels[x, y].toArgb())
            }
        }
        if (!ImageIO.write(buffered, "png", target)) {
            throw IOException("无可用 PNG 编码器")
        }
    }

    // ── 录像面（demux 旁路 stream copy，边看边录） ────────────────────────

    /**
     * 录像会话状态（面板消费）：Idle / Pending（已请求等起点帧）/ Active（muxing 中）。
     * 终态落 [recordingResult] 回执并回 Idle。
     */
    private val _recordingState = MutableStateFlow<ScreencastRecordingState>(ScreencastRecordingState.Idle)
    val recordingState: StateFlow<ScreencastRecordingState> = _recordingState.asStateFlow()

    /**
     * 录像回执（停止/中断收尾的一次性落定，会话级持态——toast 消退后回执行仍可动作）。
     * null = 本会话尚无回执；新会话起点（[start]）清上一会话回执（收尾回执恒先于
     * Stopped 发出，起点清无竞态）。
     */
    private val _recordingResult = MutableStateFlow<ScreencastRecordingOutcome?>(null)
    val recordingResult: StateFlow<ScreencastRecordingOutcome?> = _recordingResult.asStateFlow()

    /**
     * 录像面单一串行收口：demux pump 的写帧/起步与控制面（起录/停录/会话收尾）共用
     * 一把锁——录像器实例非线程安全（avformat 上下文），所有触达必须在本锁内。本锁
     * 不得反向获取 [stateLock]（起录只在 stateLock 外读状态流快照，无锁序交叉）。
     */
    private val recorderLock = Any()

    /** 在录会话（null = 无）；[recorderLock] 下读写 */
    private var recordingSession: RecordingSession? = null

    /** 流上最新 config 载荷（SPS/PPS Annex-B）——录像起点的 extradata 素材；[recorderLock] 下读写 */
    private var cachedConfig: ByteArray? = null

    /** 一段录像的在录状态（[recorderLock] 下触达 [ScreencastRecorder]） */
    private class RecordingSession(
        val file: File,
        val width: Int,
        val height: Int,
    ) {
        /** muxing 实例（null = Pending 未起步——等起点 config + 关键帧） */
        var recorder: ScreencastRecorder? = null

        /** 起步 config（分辨率变化检测基准——字节不等即分段收尾） */
        var startedConfig: ByteArray? = null
    }

    /**
     * 请求开始录像（旁路形态——投屏不中断）：产物落 [outputDir]（宿主传 Toolbelt
     * 产物树 recordings/，测试注入临时目录）。非 null 返回 = 立即失败回执（未在投屏/
     * 已在录像/目录不可用），否则已受理并经 [recordingState] 推进：起点 = 下一个
     * config + 关键帧（本方法向 server 发 reset_video 令其即时重配编码器——否则最长
     * 等一个 10s I 帧 GOP；控制通道不可用发送失败不回滚，Pending 语义仍成立）。
     *
     * 静止画面协议上不发帧（server 只在内容变化时出帧）——受理后画面不变则一直
     * Pending，[stopRecording] 以「未捕获到任何画面帧」显式失败回执收口。
     *
     * 线程模型：调用方须在 IO 调度器（文件命名/目录创建非 UI 面）。
     */
    fun startRecording(outputDir: File): ScreencastRecordingOutcome.Failed? {
        val streaming = _status.value as? ScreencastStatus.Streaming
            ?: return ScreencastRecordingOutcome.Failed("仅投屏进行中可录像——先开始投屏")
        if (!outputDir.isDirectory && !outputDir.mkdirs()) {
            return ScreencastRecordingOutcome.Failed("录像目录创建失败：${outputDir.absolutePath}")
        }
        synchronized(recorderLock) {
            if (recordingSession != null) {
                return ScreencastRecordingOutcome.Failed("已有录像在进行中——先停止当前录像")
            }
            val target = productTargetFile(outputDir, streaming.deviceName, EXTENSION_MP4)
            recordingSession = RecordingSession(target, streaming.width, streaming.height)
            _recordingState.value = ScreencastRecordingState.Pending(target)
        }
        if (!sendControl(ScrcpyControlEncoder.resetVideo())) {
            JLog.d(TAG) { "recording start: reset video not sent (control channel unavailable)" }
        }
        return null
    }

    /**
     * 停止并落盘当前录像：写容器收尾后文件即完整可播，回执经 [recordingResult] 持态
     * （本方法返回值与回执同源）。无录像在录返回 null（no-op）。
     * 线程模型：调用方须在 IO 调度器（trailer 写盘不阻塞 UI）。
     */
    fun stopRecording(): ScreencastRecordingOutcome? = finalizeRecording()

    /**
     * 录像收口（单出口）：取走在录会话（无则 null no-op），按起步状态落回执——
     * 未起步 = 失败回执（无帧可存）；已起步 = 写 trailer 落 [ScreencastRecordingOutcome.Saved]
     * （[interruptNote] 非空 = 非正常走完的如实注记）；收尾写失败 = 删除不可播残件转
     * Failed（不给用户打不开的文件）。
     */
    private fun finalizeRecording(interruptNote: String? = null): ScreencastRecordingOutcome? {
        val session = synchronized(recorderLock) {
            val taken = recordingSession ?: return null
            recordingSession = null
            _recordingState.value = ScreencastRecordingState.Idle
            taken
        }
        val recorder = session.recorder
        val outcome: ScreencastRecordingOutcome = if (recorder == null) {
            ScreencastRecordingOutcome.Failed("未捕获到任何画面帧——录像期间投屏画面无变化或过早停止")
        } else {
            try {
                ScreencastRecordingOutcome.Saved(session.file, recorder.finish(), interruptNote)
            } catch (e: IOException) {
                runCatching { recorder.close() }
                runCatching { session.file.delete() }
                ScreencastRecordingOutcome.Failed(
                    "录像收尾失败：${e.message ?: e::class.simpleName ?: "未知错误"}（不可播残件已清理）",
                )
            }
        }
        _recordingResult.value = outcome
        JLog.d(TAG) { "screencast recording finalized: ${outcome::class.simpleName}" }
        return outcome
    }

    /** demux pump 的 config 事件录像旁路（[recorderLock] 内同步收口——锁可重入） */
    private fun onStreamConfigForRecording(payload: ByteArray) {
        synchronized(recorderLock) {
            cachedConfig = payload
            val session = recordingSession ?: return
            val recorder = session.recorder ?: return
            if (!payload.contentEquals(session.startedConfig)) {
                // 新 config = 编码器重配（分辨率/参数变化）：旧文件 avcC 已定型，续写必
                // 产解码错乱——分段收尾，单文件语义如实回执
                finalizeRecording("投屏分辨率变化，录像在此分段结束")
            }
        }
    }

    /** demux pump 的帧事件录像旁路：Pending 等起点关键帧起步，Active 逐帧写入 */
    private fun onStreamFrameForRecording(ptsUs: Long, keyFrame: Boolean, payload: ByteArray) {
        synchronized(recorderLock) {
            val session = recordingSession ?: return
            val recorder = session.recorder
            if (recorder == null) {
                if (!keyFrame) return
                val config = cachedConfig ?: return
                val started = try {
                    recorderFactory.create().also {
                        it.start(config, session.width, session.height, session.file)
                    }
                } catch (e: Exception) {
                    failRecordingSession("录像启动失败：${e.message ?: e::class.simpleName ?: "未知错误"}")
                    return
                }
                session.recorder = started
                session.startedConfig = config
                _recordingState.value = ScreencastRecordingState.Active(session.file)
                writeRecordingFrame(started, ptsUs, keyFrame, payload)
            } else {
                writeRecordingFrame(recorder, ptsUs, keyFrame, payload)
            }
        }
    }

    /**
     * 单帧写入收口：写失败只失败录像面——收口尝试 trailer 抢救已录部分（成功 = Saved
     * 带中断注记），画面面继续（控制面故障不拖垮画面面的对偶纪律）。
     */
    private fun writeRecordingFrame(recorder: ScreencastRecorder, ptsUs: Long, keyFrame: Boolean, payload: ByteArray) {
        try {
            recorder.onFrame(ptsUs, keyFrame, payload)
        } catch (e: IOException) {
            JLog.e(TAG) { "screencast recording frame write failed: ${e.message}" }
            finalizeRecording("录像写入中断，已保存中断前可播的已录部分")
        }
    }

    /** 起步失败的显式收口（调用方持 [recorderLock]）：Failed 回执 + 状态落 Idle */
    private fun failRecordingSession(reason: String) {
        recordingSession = null
        _recordingState.value = ScreencastRecordingState.Idle
        _recordingResult.value = ScreencastRecordingOutcome.Failed(reason)
    }

    // ── 控制回传入口（面板交互 → 控制通道） ─────────────────────────────

    /**
     * 注入单键（down + up 成对一笔发送）。keycode 为 Android `KeyEvent.KEYCODE_*`
     * 整数值（:core 设备按键目录 [com.jiaoay.toolbelt.device.DeviceKeys]
     * 的语义真源）；本通道是 scrcpy 控制协议注入，与 `adb shell input keyevent` 是
     * 两条语义通道，不得混用。无会话/会话未就绪/通道已断返回 false（不抛不静默重试）。
     */
    fun controlInjectKey(keycode: Int, metaState: Int = 0): Boolean {
        val bytes = ScrcpyControlEncoder.injectKeycode(
            ScrcpyControlProtocol.KEY_ACTION_DOWN,
            keycode,
            metaState = metaState,
        ) + ScrcpyControlEncoder.injectKeycode(
            ScrcpyControlProtocol.KEY_ACTION_UP,
            keycode,
            metaState = metaState,
        )
        return sendControl(bytes)
    }

    /** 注入文本（设备端 IME 语义；UTF-8，超长按协议上限截断）。返回 false = 通道不可用 */
    fun controlInjectText(text: String): Boolean = sendControl(ScrcpyControlEncoder.injectText(text))

    /**
     * 注入触摸事件。[action] 为 [ScrcpyControlProtocol.MOTION_ACTION_DOWN/MOVE/UP]，
     * 坐标为设备屏幕像素坐标（面板侧完成控件坐标→屏幕坐标换算）；压力协议同官方客户端
     * （down/move 恒 1.0、up 恒 0.0）。
     *
     * [pointerId] 为虚拟指 id，默认 [ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER]
     * = 既有单指行为逐位恒等（缺省调用方零感知）；多指手势按全表分配：
     * -2 主单指、-3/-4 双指对（[ScrcpyControlProtocol.POINTER_ID_PINCH_FIRST/SECOND]）、
     * -11..-13 泊指（[ScrcpyControlProtocol.POINTER_ID_PARKED_FIRST]）、-1 禁用
     * （mouse 分支，见 [ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER]）。
     */
    fun controlInjectTouch(
        action: Int,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pointerId: Long = ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER,
    ): Boolean {
        val pressure = if (action == ScrcpyControlProtocol.MOTION_ACTION_UP) 0f else 1f
        return sendControl(
            ScrcpyControlEncoder.injectTouch(
                action,
                pointerId,
                x,
                y,
                screenWidth,
                screenHeight,
                pressure,
            ),
        )
    }

    /**
     * 注入滚动（值语义同官方客户端：正 vScroll = 滚轮向上 = 设备端内容向前滚动；
     * 值域 ±[ScrcpyControlProtocol.SCROLL_WIRE_RANGE] 由编码器收口）。返回 false = 通道不可用
     */
    fun controlInjectScroll(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        hScroll: Float,
        vScroll: Float,
    ): Boolean = sendControl(
        ScrcpyControlEncoder.injectScroll(x, y, screenWidth, screenHeight, hScroll, vScroll),
    )

    /**
     * 设置设备端剪贴板（paste=false——只写剪贴板，不向设备端焦点输入框粘贴；粘贴由
     * 用户后续动作触发）。落地回执经 [controlAck] 异步回读（请求序号自增、恒 ≥ 1）。
     */
    fun controlSetClipboard(text: String): Boolean {
        val sequence = clipboardSequence.incrementAndGet()
        return sendControl(ScrcpyControlEncoder.setClipboard(sequence, text, paste = false))
    }

    /**
     * 请求读回设备端剪贴板（copyKey=NONE——不向设备端注入复制/剪切键）：server 以
     * TYPE_CLIPBOARD 设备消息响应，内容异步落 [deviceClipboard]（消费方订阅该流闭环）。
     * 返回 false = 通道不可用（请求未发出）。
     */
    fun controlRequestClipboard(): Boolean =
        sendControl(ScrcpyControlEncoder.getClipboard())

    /**
     * 控制通道单笔发送收口：无通道（未会话/已收口）直接 false；写失败（通道已关/对端
     * 断开）落日志后 false——注入失败不炸 UI 也不进会话失败单槽（画面面不受控面牵连）。
     */
    private fun sendControl(bytes: ByteArray): Boolean {
        val channel = controlChannel ?: return false
        return try {
            channel.send(bytes)
            true
        } catch (e: IOException) {
            JLog.e(TAG) { "control message send failed: ${e.message}" }
            false
        }
    }

    private suspend fun runSession(
        adbPath: String,
        serial: String?,
        generation: Long,
        options: ScreencastOptions,
        tunnelPreference: TunnelPreference,
        showTouches: ScreencastShowTouches,
    ) {
        val handles = SessionHandles()
        synchronized(stateLock) { currentHandles = handles }
        val adb = adbGatewayFactory(adbPath, serial)
        val runner = runnerFactory().also { handles.runner = it }
        val scid = scidRandom.nextInt().toLong() and 0x7FFF_FFFFL
        // 链段语境（失败真因的段名前缀）：EOFException/FFmpeg natives 异常 message 常为
        // null，无段名时终态只剩类名裸串、无链段定位——段名随会话推进更新，catch 点前缀进终态
        var stage = "部署"
        val socketName = ScrcpyProtocol.deviceSocketName(scid)
        val opener = ScrcpyTunnelOpener(adb, socketName, portRange, listenerFactory, tunnelPreference)
        // 失败真因单槽（会话体与 pump 协程并发写，首因优先——后到异常多为首因的连锁）
        val sessionFailure = AtomicReference<String?>(null)
        // 控制通道局部引用（try 内置位，finally 按 === 判定收口——声明在外供两段共享）
        var control: ScrcpyControlChannel? = null
        var cancelled = false
        try {
            // server jar 路径解析顺序（真源 KDoc 见 [ScrcpyServerJarReleaser]）：
            // 目标路径已有文件直接用（用户自置优先）→ 缺失则随包释放 → 同目录版本
            // 残留 = 冲突人话提示（不覆盖用户文件）；释放失败（资源缺失/写失败）显式
            // 落会话失败终态，不静默
            when (val outcome = serverJarReleaser(serverJarFile)) {
                ScrcpyServerJarReleaser.Outcome.Released,
                ScrcpyServerJarReleaser.Outcome.AlreadyPresent,
                -> Unit

                is ScrcpyServerJarReleaser.Outcome.Conflict ->
                    throw IOException(outcome.hint)

                is ScrcpyServerJarReleaser.Outcome.Failed ->
                    throw IOException(outcome.reason)
            }
            val push = adb.execute(ScrcpyServerDeployer.buildPushCommand(serverJarFile.absolutePath))
            if (push.isFailure) {
                throw IOException("adb push 失败：${push.exceptionOrNull()?.message ?: "未知原因"}")
            }
            val tunnel = opener.open().also { handles.tunnel = it }
            stage = "握手"
            // serve 命令同 push/隧道链一样必须过 AdbCommandRewriter——三条 adb 命令链
            // 注入路径与串号同一单源（runner 是 StreamingShell，自身不重写）
            val serveCommand = AdbCommandRewriter.rewrite(
                ScrcpyServerDeployer.buildServeCommand(
                    scid = scid,
                    tunnelForward = tunnel is ScrcpyTunnel.Forward,
                    maxFps = options.maxFps,
                    maxSize = options.maxSize,
                    videoBitRate = options.videoBitRate,
                ),
                adbPath,
                serial,
            )
            scope.launch(Dispatchers.IO) {
                runner.run(serveCommand) { line -> JLog.d(TAG) { "[scrcpy-server] $line" } }
            }
            val (socket, controlSocket, handshake) = when (val opened = tunnel) {
                is ScrcpyTunnel.Reverse -> {
                    opened.listener.soTimeout = acceptSoTimeoutMs
                    val accepted = try {
                        withTimeout(connectTimeoutMs) { opened.listener.accept() }
                    } catch (e: SocketTimeoutException) {
                        // soTimeout 兜底触发（native accept 对协程取消免疫）——同显式失败
                        throw IOException(
                            "设备端在 ${acceptSoTimeoutMs}ms 内未反连（server 未就绪或 reverse 隧道不通）",
                            e,
                        )
                    }
                    accepted.also {
                        handles.socket = it
                        // 与 spike 验证配置同源：视频流按帧到达，Nagle 合批会在小帧/低码率
                        // 场景拖慢首帧与帧间隔
                        it.tcpNoDelay = true
                    }
                    // 控制通道 = 第二条反连（server 在 open 内 video→control 背靠背两次 connect，
                    // 第二 accept 必然先于设备名写入）；与首 accept 同款双闸
                    val controlAccepted = try {
                        withTimeout(connectTimeoutMs) { opened.listener.accept() }
                    } catch (e: SocketTimeoutException) {
                        throw IOException(
                            "设备端在 ${acceptSoTimeoutMs}ms 内未反连控制通道（control socket 第二连接未到）",
                            e,
                        )
                    }
                    controlAccepted.also {
                        handles.controlSocket = it
                        it.tcpNoDelay = true
                    }
                    accepted.soTimeout = handshakeSoTimeoutMs
                    Triple(accepted, controlAccepted, ScrcpyHandshakeReader().read(accepted.getInputStream()))
                }

                is ScrcpyTunnel.Forward -> {
                    val connection = ScrcpyHandshakeReader(probeRetry).connectForward(
                        connect = {
                            runCatching {
                                Socket().apply {
                                    connect(InetSocketAddress("127.0.0.1", opened.port), connectTimeoutMs.toInt())
                                    soTimeout = handshakeSoTimeoutMs
                                    // 与 spike 验证配置同源（Nagle 合批拖慢探活字节与首帧）
                                    tcpNoDelay = true
                                }.also { handles.socket = it }.getInputStream()
                            }.getOrNull()
                        },
                        // 探活重试总闸：无总闸时重试预算 = attempts × (connect 超时 + 间隔)，
                        // 可展开到远超连接超时的量级；与 reverse accept 同用 connectTimeoutMs
                        totalTimeoutMs = connectTimeoutMs,
                    )
                    // 控制通道 = 第二条连接：无探活字节（server 的 sendDummyByte 在首条
                    // socket 写后即置 false），连接成功即通道就绪；探活已成功意味着 server
                    // 已进入 control accept（背靠背序），单次连接即达
                    val controlConnected = runCatching {
                        Socket().apply {
                            connect(InetSocketAddress("127.0.0.1", opened.port), connectTimeoutMs.toInt())
                            tcpNoDelay = true
                        }
                    }.getOrElse { e ->
                        throw IOException("控制通道第二条连接失败（server 未在隧道口 accept 控制连接）", e)
                    }.also { handles.controlSocket = it }
                    Triple(handles.socket!!, controlConnected, connection.handshake)
                }
            }
            when {
                handshake.streamConfigError ->
                    throw IOException("设备端视频配置错误（禁流码 1），无法投屏")

                handshake.streamDisabled ->
                    throw IOException("设备端禁用了视频流（禁流码 0）")

                handshake.codecId != ScrcpyProtocol.CODEC_H264 ->
                    throw IOException(
                        "不支持的 codec id 0x%08x（本期只支持 H264）".format(handshake.codecId),
                    )
            }
            // 流态无读超时：静止画面协议上无新数据，超时会把「画面静止」误判为断流
            socket.soTimeout = 0
            stage = "解码"
            val decoder = decoderFactory.create().also { handles.decoder = it }
            val demuxer = ScrcpyDemuxer(socket.getInputStream())
            val deviceName = handshake.deviceName
            // 控制通道就绪置位（注入入口自此可用；收尾 finally 按 === 清空）
            control = ScrcpyControlChannel(controlSocket).also { controlChannel = it }
            // 设备消息 pump：消费服务端回执/剪贴板推送；读失败只结束控制回执面——
            // 控制面故障不拖垮画面面（只读回退），不进会话失败单槽
            val devicePump = scope.launch(Dispatchers.IO) {
                try {
                    val reader = ScrcpyDeviceMessageReader(controlSocket.getInputStream())
                    while (true) {
                        when (val event = reader.next() ?: break) {
                            is ScrcpyDeviceEvent.AckClipboard -> _controlAck.value = event.sequence
                            is ScrcpyDeviceEvent.Clipboard -> _deviceClipboard.value = event.text
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SocketException) {
                    // 停止/收尾关控制 socket = 终止信号，与 demuxer 的 SocketException 同口径
                } catch (e: Exception) {
                    JLog.e(TAG) { "device message stream ended: ${e.message ?: e::class.simpleName}" }
                }
            }
            val pump = scope.launch(Dispatchers.IO) {
                try {
                    while (true) {
                        when (val event = demuxer.next() ?: break) {
                            is ScrcpyStreamEvent.SessionMeta ->
                                publishStreaming(generation, serial, deviceName, event.width, event.height)

                            is ScrcpyStreamEvent.Config -> {
                                decoder.onPacket(event.payload)
                                onStreamConfigForRecording(event.payload)
                            }

                            is ScrcpyStreamEvent.Frame -> {
                                decoder.onPacket(event.payload)
                                onStreamFrameForRecording(event.ptsUs, event.keyFrame, event.payload)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 坏流/写管道失败也是可预期故障——真因进终态（带链段语境），不进全局未处理通道
                    sessionFailure.compareAndSet(null, "流解析：${e.message ?: e::class.simpleName ?: "未知错误"}")
                } finally {
                    runCatching { decoder.signalEnd() }
                }
            }
            var windowStartMs = clock()
            var windowFrames = 0L
            while (true) {
                val decoded = decoder.grab() ?: break
                // fps 折算与帧/Streaming 共用同一闸且闸在其前：闸外的迟到帧只消费不折算
                // ——停止已落后不得再发布瞬态帧率（闸前折算会在终态清零前开一个脏值窗口）
                if (!isPublishable(generation)) break
                windowFrames++
                val nowMs = clock()
                if (nowMs - windowStartMs >= FPS_WINDOW_MS) {
                    _fps.value = ((windowFrames * FPS_WINDOW_MS) / (nowMs - windowStartMs)).toInt()
                    windowStartMs = nowMs
                    windowFrames = 0
                }
                _frame.value = ScreencastFrame(generation, decoded.image)
                if (_status.value !is ScreencastStatus.Streaming) {
                    // 首帧早于 SessionMeta 的兜底（协议上恒先到，防御路径同源发布）
                    publishStreaming(generation, serial, deviceName, decoded.width, decoded.height)
                }
            }
            pump.cancel()
            devicePump.cancel()
        } catch (e: TimeoutCancellationException) {
            // connect 阶段超时 = 显式失败（server 未就绪/未反连），不冒充「已停止」
            sessionFailure.compareAndSet(null, "连接超时（${connectTimeoutMs}ms 内设备端未就绪）：${e.message}")
            JLog.e(TAG) { "screencast session connect timeout: generation=$generation" }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: LinkageError) {
            // 平台解码组件缺失（native .so 不在包内）：Error 不属 Exception 通道，显式收口
            sessionFailure.compareAndSet(null, "$stage：${e.message ?: e::class.simpleName ?: "解码组件加载失败"}")
            JLog.e(TAG) { "screencast decoder linkage failed: ${sessionFailure.get()}" }
        } catch (e: Exception) {
            sessionFailure.compareAndSet(null, "$stage：${e.message ?: e::class.simpleName ?: "未知错误"}")
            JLog.e(TAG) { "screencast session failed: ${sessionFailure.get()}" }
        } finally {
            withContext(NonCancellable) {
                // 显示触点收口（全收口路径单点——停投/关窗/断连/退出序全部经引擎 stop
                // 或会话自灭汇入本 finally）：先摘会话引用再恢复——摘引用后迟来的切换
                // 请求在 setShowTouches 入口恒拒，收口与切换的竞态在 API 门禁关闭；
                // 恢复先于终态落定，Runtime.shutdown 的终态等待窗覆盖本恢复
                if (showTouchesSession === showTouches) showTouchesSession = null
                runCatching { showTouches.restoreOnSessionEnd() }
                    .onFailure { JLog.e(TAG) { "show touches restore on session end failed: ${it.message}" } }
                // 录像面先于句柄收口：会话终态（含用户停止/断流）时在录的录像随会话
                // 落盘并回执——回执只在会话收尾发出一次，新会话起点由消费方清持态
                runCatching { finalizeRecording() }
                    .onFailure { JLog.e(TAG) { "screencast recording finalize failed: ${it.message}" } }
                // 只关本会话句柄（局部量）——currentHandles 指向不动（新会话已起时它属新会话）
                runCatching { control?.close() }
                if (controlChannel === control) controlChannel = null
                handles.controlSocket?.let { runCatching { it.close() } }
                handles.decoder?.let { runCatching { it.close() } }
                handles.socket?.let { runCatching { it.close() } }
                (handles.tunnel as? ScrcpyTunnel.Reverse)?.let { reverse ->
                    runCatching { reverse.listener.close() }
                }
                // 隧道规则摘除（收尾尽力语义；残留由 adb forward/reverse --list 核对暴露）。
                // tunnel 为 null = 会话死于开链中段：已发过的建规则命令结果未观测（规则可能在档），
                // 挂账面摘除仍必达——不得因未持隧道而跳过
                runCatching { opener.close(handles.tunnel) }
                    .onFailure { JLog.e(TAG) { "tunnel rule remove failed: ${it.message}" } }
                runner.stop()
                synchronized(stateLock) {
                    if (currentHandles === handles) currentHandles = null
                }
                if (activeGeneration == generation) {
                    _frame.value = null
                    _fps.value = 0
                    val failure = sessionFailure.get()
                    val message: String
                    val failed: Boolean
                    if (stopRequested || cancelled) {
                        message = "已停止"
                        failed = false
                    } else if (failure != null) {
                        message = failure
                        failed = true
                    } else {
                        message = "投屏流已结束（设备端连接断开）"
                        failed = true
                    }
                    _status.value = ScreencastStatus.Stopped(message, failed)
                    JLog.d(TAG) { "screencast session end: generation=$generation failed=$failed" }
                }
            }
        }
    }

    /**
     * 发布闸（单一决策源，世代防复活 + 终态不可逆）：世代失配（新会话已起）、停止已落、
     * 终态已定三者任一命中即拒绝发布——帧与 Streaming 态共用本闸。
     */
    private fun isPublishable(generation: Long): Boolean =
        activeGeneration == generation &&
            !stopRequested &&
            _status.value !is ScreencastStatus.Stopped

    /** Streaming 态发布（经 [isPublishable] 同一闸——Stopped 不可被在途事件回退；serial = 会话绑定串号透传） */
    private fun publishStreaming(
        generation: Long,
        serial: String?,
        deviceName: String,
        width: Int,
        height: Int,
    ) {
        if (!isPublishable(generation)) return
        _status.value = ScreencastStatus.Streaming(deviceName, serial, width, height)
    }

    companion object {
        private const val TAG = "ScreencastEngine"

        /** [restart] 的终态等待上限（毫秒）：stop 后会话协程 finally 段（含显示触点恢复）的挂起余量 */
        private const val RESTART_TERMINAL_AWAIT_MS = 10_000L

        /**
         * scid 随机源：scid 进 socket 名与 serve 命令两处，可预测即会话可被指名抢占——
         * 本地 adb 场景威胁低，仍取安全随机源（[SecureRandom]，替换可预测的 kotlin.random）。
         * 值域 [0, 2^31) 与 [ScrcpyServerDeployer.SCID_MAX_EXCLUSIVE] 同源（nextInt 高位屏蔽）。
         */
        private val scidRandom = SecureRandom()

        /** 快照文件名时间戳档（本地时区 + 毫秒位；DateTimeFormatter 不可变线程安全） */
        private val SNAPSHOT_TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

        /** 快照产物扩展名（[productTargetFile] 用） */
        private const val EXTENSION_PNG = "png"

        /** 录像产物扩展名（[productTargetFile] 用） */
        private const val EXTENSION_MP4 = "mp4"

        /** 帧率统计窗口（ms） */
        internal const val FPS_WINDOW_MS = 1_000L

        /**
         * 生产默认帧率上限（server 端抽帧，[ScreencastOptions.maxFps] 的默认值）：
         * 观测页是「看状态」的阅读档位非操控档，30fps 视觉充分；上限同时把解码→位图
         * 的双份分配压半（2880×1620 满帧率时分配 churn 约 2.2GB/s 全靠 GC 硬扛）
         * ——降负载杠杆，升档走画质档位（面板帧率档）。
         */
        internal const val DEFAULT_MAX_FPS = 30f

        /**
         * server jar 默认落点：`~/.toolbelt/scrcpy/scrcpy-server-v<版本>`
         * （路径解析顺序真源 KDoc 见 [ScrcpyServerJarReleaser]：本路径已有文件直接用
         * ——用户自置优先；缺失时释放随包资源到本路径，这是引擎链路对宿主数据根的
         * 唯一写点；同目录版本残留不自动清理）。
         */
        fun defaultServerJar(): File = File(
            FileUtils.getScrcpyDir(),
            "scrcpy-server-v${ScrcpyServerDeployer.SERVER_VERSION}",
        )
    }
}
