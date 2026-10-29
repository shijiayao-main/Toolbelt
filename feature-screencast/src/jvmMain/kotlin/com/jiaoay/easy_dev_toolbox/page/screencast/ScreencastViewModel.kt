package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.jiaoay.toolbelt.device.DeviceState
import com.jiaoay.toolbelt.device.DeviceStateSource

/**
 * 投屏前置检查的可操作提示：message + 建议动作（面板据此渲染对应入口钮；
 * null = 无前置问题）。无设备 / adb 路径失效时**不静默**——开始按钮照常可点，
 * 点击后落提示态指路（logcat 抓取面板同款口径）。
 */
data class ScreencastHint(
    val message: String,
    val action: Action?,
) {
    /** [precheckHint] 产出的动作面——枚举成员必须与前置检查的产出集合一致，死成员即红（见测试） */
    enum class Action { SELECT_DEVICE, GO_SETTINGS }
}

/**
 * 画质档位档存（App 级单例，[ScreencastRuntime] 同层口径——投屏状态脱离页面生命周期）：
 * 档位是「下次会话起点生效」的候补参数组，改档消费面 = 独立窗顶栏「画质」弹层，
 * start 时刻快照消费面 = [ScreencastViewModel.currentOptions]。两消费面各自组合
 * 互不可达（窗组合在宿主 main.kt，VM 组合在设备观测页），App 级单例是唯一交点。
 * 会话级不落盘（临场取舍，进程重启回默认档）。
 *
 * [clearForTest] 供单测复位——进程级单例跨用例残留，与 ReportDelivery.clearForTest
 * 同纪律，用例收尾复位保断言确定性。
 */
object ScreencastQualityTierStore {

    /**
     * 码率档（bits/s）：null = 不追加 key=value——server 端默认码率即 8M（上游
     * scrcpy cli.c 默认文案锚），与显式 8000000 wire 语义等价，选零追加形态（默认档）。
     * 档位清单单一真源 [VIDEO_BITRATE_TIERS_BITS]。
     */
    private val _bitrateTierBits = MutableStateFlow<Long?>(null)
    val bitrateTierBits: StateFlow<Long?> = _bitrateTierBits.asStateFlow()

    /** 帧率档（fps）：默认 [ScreencastEngine.DEFAULT_MAX_FPS] = 现行为 */
    private val _fpsTier = MutableStateFlow(ScreencastEngine.DEFAULT_MAX_FPS)
    val fpsTier: StateFlow<Float> = _fpsTier.asStateFlow()

    /**
     * 画面上限档（0 = 原始分辨率不追加 key=value）。默认 1080（预览档）：观测页画面
     * 区上限 420dp、2x 密度下满宽只需 ~840px——1080 已过采样；默认原始档时
     * 2880×1620@30fps 的解码→位图→纹理上传链每帧双份 ~18MB 分配（≈1.1GB/s churn）
     * 是观测页交互 jank 的头号杠杆，预览档将该链成本降到约 1/3.5。原始档保留可手选
     * （看清全分辨率细节的场景）。
     */
    private val _maxSizeTier = MutableStateFlow(1080)
    val maxSizeTier: StateFlow<Int> = _maxSizeTier.asStateFlow()

    /** 码率档切换（起点生效——下次 start 才落命令） */
    fun onBitrateTierChange(bits: Long?) {
        _bitrateTierBits.value = bits
    }

    /** 帧率档切换（起点生效同上） */
    fun onFpsTierChange(fps: Float) {
        _fpsTier.value = fps
    }

    /** 画面上限档切换（0 = 原始分辨率；起点生效同上） */
    fun onMaxSizeTierChange(size: Int) {
        _maxSizeTier.value = size
    }

    /** 单测复位（三档回默认——进程级单例不跨用例残留） */
    internal fun clearForTest() {
        _bitrateTierBits.value = null
        _fpsTier.value = ScreencastEngine.DEFAULT_MAX_FPS
        _maxSizeTier.value = 1080
    }

    /**
     * 码率档（bits/s）：null 档 = 不追加 key=value——server 端默认码率即 8M
     * （上游 scrcpy cli.c `--video-bit-rate` 默认文案锚），与显式 8000000 wire
     * 语义等价，选零追加形态。列表序即弹层档位渲染序（null 档标签「8M·默认」）。
     */
    internal val VIDEO_BITRATE_TIERS_BITS: List<Long?> =
        listOf(2_000_000L, null, 16_000_000L, 40_000_000L)

    /** 帧率档（fps）；默认档 30 = [ScreencastEngine.DEFAULT_MAX_FPS] 现行为 */
    internal val MAX_FPS_TIERS: List<Float> = listOf(15f, 30f, 60f)

    /**
     * 画面上限档：0 = 原始分辨率不追加 key=value；其余 = 设备端长边缩放上限
     * （scrcpy max_size 语义，只缩不放）。列表序即弹层档位渲染序；默认档 =
     * 1080 预览档（理由见 [maxSizeTier]）。
     */
    internal val MAX_SIZE_TIERS: List<Int> = listOf(0, 1080, 720)
}

/**
 * 连接段预算默认值（毫秒）：[ScreencastViewModel.startCast] 受理即开窗后连接段占位的
 * 等待上限——窗内连接段占位文案（「最长等待约 N 秒」）的读数同源。
 */
internal const val SCREENCAST_CONNECT_BUDGET_MS = 20_000L

/**
 * 档位三元组 → 引擎参数组（归一化单源：null/0 档归一为不追加 key=value）。
 * 消费面 = [ScreencastViewModel.currentOptions]（主窗 startCast 起点）与独立窗
 * 「停投并按新档重开」（改档快照）——两消费面同源归一，不各写一份。
 */
internal fun screencastOptionsFromTiers(
    bitrateTierBits: Long?,
    fps: Float,
    maxSize: Int,
): ScreencastOptions = ScreencastOptions(
    videoBitRate = bitrateTierBits,
    maxFps = fps,
    maxSize = maxSize.takeIf { it != 0 },
)

/**
 * 投屏面板 ViewModel：前置检查 / 开始停止转发 / 运行态转发 / 控制回传转发。
 *
 * 设备与 adb 路径复用 :core 设备域既有检测态（[DeviceStateSource] 三流——App 级单源），
 * 本 VM 不自起检测链路；投屏引擎复用 App 级单实例 [ScreencastRuntime.engine]
 * （切页不中断，退出序随 Runtime 收口）。无持久化设置项——开始/停止是即时动作，
 * 运行态与提示态均会话级（引擎重启后恒 Idle）；画质档位真源在 App 级
 * [ScreencastQualityTierStore]（会话级不落盘，重启回默认档）。
 *
 * 控制回传转发（零加工透传——协议收口在引擎）：注入目标 = 会话绑定的串号
 * （[startCast] 经 [resolveTargetSerial] 锁定），与按键面板的 adb 注入是两条语义
 * 通道（`input keyevent` vs 控制协议消息），本面板只走控制协议通道。
 */
class ScreencastViewModel(
    private val devices: DeviceStateSource,
    private val engine: ScreencastEngine = ScreencastRuntime.engine,
    private val adbPathReader: suspend () -> String = { DeviceHub.currentEndpoint().executablePath },
    /**
     * 连接段预算（毫秒）：受理即开窗后连接段占位（窗内「连接中…」态）的等待上限——
     * 预算走完仍悬在连接段（未出画也未落终态）时置单源超时回执
     * [ScreencastDetachedWindowController.connectionTimedOut]，不静默等终态兜底；
     * 构造注入供单测换短窗。窗内连接段占位文案的秒数读数同源（本常量）。
     */
    private val detachedWindowOpenTimeoutMs: Long = SCREENCAST_CONNECT_BUDGET_MS,
) : ViewModel() {

    private val _hint = MutableStateFlow<ScreencastHint?>(null)
    val hint: StateFlow<ScreencastHint?> = _hint.asStateFlow()

    /**
     * 失败终态的已读标记（已读消息文本；null = 未读或无失败终态）。**按会话重置**：
     * 新会话起点（状态离开 Stopped，即 Connecting/Streaming/Idle）即清零——旧会话的
     * 已读不得吞掉新会话的同文案失败（同因失败须再次显式回执）。
     */
    private val _dismissedFailure = MutableStateFlow<String?>(null)
    val dismissedFailure: StateFlow<String?> = _dismissedFailure.asStateFlow()

    val status: StateFlow<ScreencastStatus> = engine.status

    init {
        viewModelScope.launch {
            status.collect {
                // 状态离开 Stopped（新会话起点）即清零——新会话不残留旧会话的已读标记
                // （已读消息文本是 VM 级 UI 态，无引擎侧载体，起点清只能在本面）；
                // 快照回执的起点清在引擎（[ScreencastEngine.start] 清帧区同点），Stopped
                // 终态两者都不清（拍→停→打开/送出的动线不因停投失去回执行）
                if (it !is ScreencastStatus.Stopped) {
                    _dismissedFailure.value = null
                }
            }
        }
    }

    // ── 画质档位（真源 = [ScreencastQualityTierStore]，起点生效——start 时刻经
    // [currentOptions] 快照进引擎） ─────────────────────────────────────

    /**
     * 档位 → 引擎参数组（start 时刻快照，档位真源 = [ScreencastQualityTierStore]）：
     * null/0 档归一为不追加 key=value——唯一带非 null 默认值的是 maxSize（1080
     * 预览档，理由见 [ScreencastQualityTierStore.maxSizeTier]）；码率/帧率默认档
     * 产物与 [ScreencastOptions] 默认值相等（零追加形态）。
     */
    internal fun currentOptions(): ScreencastOptions = screencastOptionsFromTiers(
        bitrateTierBits = ScreencastQualityTierStore.bitrateTierBits.value,
        fps = ScreencastQualityTierStore.fpsTier.value,
        maxSize = ScreencastQualityTierStore.maxSizeTier.value,
    )

    /** 清除提示态（前置问题被用户自行解决后手动收起） */
    fun dismissHint() {
        _hint.value = null
    }

    /** 收读当前失败终态（幂等；无失败终态时无副作用） */
    fun dismissFailure() {
        val stopped = engine.status.value as? ScreencastStatus.Stopped ?: return
        if (stopped.failed) _dismissedFailure.value = stopped.message
    }

    /** 停止当前投屏（无投屏在跑时无副作用）；终止原因随终态在面板回显 */
    fun stopCast() {
        _hint.value = null
        engine.stop()
    }

    /**
     * 开始投屏：前置检查不通过时落 [hint] 提示态并返回（不启动）；
     * 通过后按当前 adb 路径 + 目标串号起会话（串号解析与 logcat 抓取同口径——
     * Multi 用会话级选择、Single 注入其串号）。
     *
     * **受理即开窗（先窗后画）**：会话被受理即 [ScreencastDetachedWindowController
     * .openOrFocus] 显示独立窗，不等 Streaming 落定——窗对象应用启动期已建好
     * （恒组合，纯显示位翻转），窗内连接段占位（连接中…/超时态）承接预算期反馈；
     * 主窗行卡在 Connecting 期的行尾入口同为聚焦窗面。连接预算（[detachedWindowOpenTimeoutMs]）
     * 走完仍悬在连接段（未出画也未落终态）时置 [ScreencastDetachedWindowController
     * .connectionTimedOut] 单源超时回执（窗内超时态为主呈现面，主窗超时细行为兜底），
     * 不静默等终态兜底；已落 Stopped 终态时失败回执由终态通道承载，不叠加超时行。
     */
    fun startCast() {
        val precheck = precheckHint()
        if (precheck != null) {
            _hint.value = precheck
            return
        }
        _hint.value = null
        viewModelScope.launch(Dispatchers.IO) {
            // 每次启动重读 adb 路径（设置页变更后回到本页即生效，与设备检测同口径）；
            // 画质档位同在 start 时刻快照（起点生效）
            val adbPath = adbPathReader()
            val started = engine.start(adbPath, resolveTargetSerial(), currentOptions())
            if (!started) {
                // 措辞如实：重入拒绝命中的是自己刚起的会话（Connecting/Streaming 占位），
                // 非外部冲突——不写「已有投屏在进行中」误导用户去找别处会话
                StatusSignals.show("正在开始投屏，请稍候")
            } else {
                // 新会话被受理（重试起点）：清上一会话残留的连接超时回执
                ScreencastDetachedWindowController.connectionTimedOut = false
                // 受理即开窗：纯显示位翻转（窗对象启动期已建好），连接段反馈窗内承载
                ScreencastDetachedWindowController.openOrFocus()
                // 等出画而非「离开 Connecting」：终态与预算窗的落定时序是竞态（失败可
                // 恰在预算到期后落定），「等 Streaming + 到期后判 Connecting」形态对
                // 全时序确定性——已落终态时本分支恒不置位，终态回执由终态通道承载
                withTimeoutOrNull(detachedWindowOpenTimeoutMs) {
                    status.first { it is ScreencastStatus.Streaming }
                } ?: run {
                    if (engine.status.value is ScreencastStatus.Connecting) {
                        // 预算走完仍悬在连接段——超时回执单源（窗内超时态为主呈现面）
                        ScreencastDetachedWindowController.connectionTimedOut = true
                    }
                }
            }
        }
    }

    /**
     * 前置检查（纯同步读 :core 设备域三流；null = 可启动）——
     * logcat 抓取 [com.jiaoay.easy_dev_toolbox.page.logcat.LogcatViewModel] 同款口径。
     */
    internal fun precheckHint(): ScreencastHint? {
        val detectError = devices.detectError.value
        if (detectError != null) {
            return ScreencastHint("$detectError——请检查设置页 ADB 路径", ScreencastHint.Action.GO_SETTINGS)
        }
        return when (val device = devices.deviceState.value) {
            is DeviceState.None ->
                ScreencastHint("未检测到设备——连接设备后将自动检测", ScreencastHint.Action.GO_SETTINGS)

            is DeviceState.Multi ->
                if (devices.selectedSerial.value == null) {
                    ScreencastHint("检测到多台设备，请先在上方设备栏选择目标设备", ScreencastHint.Action.SELECT_DEVICE)
                } else {
                    null
                }

            is DeviceState.Single -> null
        }
    }

    /** 目标串号解析：Multi 用会话级选择；Single 注入其串号；None 已被前置检查拦截（null 兜底） */
    private fun resolveTargetSerial(): String? = when (val device = devices.deviceState.value) {
        is DeviceState.Single -> device.serial
        is DeviceState.Multi -> devices.selectedSerial.value
        is DeviceState.None -> null
    }

    // ── 控制回传转发（面板交互 → 引擎控制通道；零加工透传） ─────────────

    /**
     * 注入触摸事件（坐标 = 设备屏幕像素坐标，换算见 [mapPointerToScreen]）。
     * [pointerId] 默认 [ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER] = 既有单指
     * 行为恒等（缺省调用方零感知）；多指手势的 id 分配表见
     * [ScreencastEngine.controlInjectTouch]。
     */
    fun injectTouch(
        action: Int,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pointerId: Long = ScrcpyControlProtocol.POINTER_ID_GENERIC_FINGER,
    ) {
        engine.controlInjectTouch(action, x, y, screenWidth, screenHeight, pointerId)
    }
}

/**
 * 帧画面控件坐标 → 设备屏幕像素坐标（纯函数）：等比换算（画面节点纵横比与设备屏
 * 一致——面板侧画面区为精确贴合测量，节点无黑边带）+ 越界收口（指针掠出画面边缘
 * 不得产屏外坐标）。
 */
internal fun mapPointerToScreen(
    offsetX: Float,
    offsetY: Float,
    viewWidth: Int,
    viewHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): Pair<Int, Int> {
    if (viewWidth <= 0 || viewHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
        return 0 to 0
    }
    val x = ((offsetX / viewWidth) * screenWidth).toInt().coerceIn(0, screenWidth - 1)
    val y = ((offsetY / viewHeight) * screenHeight).toInt().coerceIn(0, screenHeight - 1)
    return x to y
}
