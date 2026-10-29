package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 单个注入按键：[keyCode] 为 Android KeyEvent 键值（`input keyevent` 直收整数），
 * [label] 为面板展示文案。高频子集见 [DeviceKeys]；任意整数码走自定义兜底
 * （[DeviceKeys.customKey]）。
 */
data class DeviceKey(val keyCode: Int, val label: String)

/**
 * 设备高频按键目录（Android KeyEvent 高频子集——本目录只收录遥控高频动作，
 * 全量键值查询走 [AndroidKeyCodes]，任意码兜底走 [customKey] 数字输入）。键值为
 * Android `KeyEvent.KEYCODE_*` 稳定常量（framework/base KeyEvent.java 公开值），
 * 与 [AndroidKeyCodes] 同源（子集常量名须与全量表一致，数据完整性断言钉死，
 * 防两套口径）：导航（主页/返回/菜单/最近任务）、系统（通知/电源/亮屏/熄屏——
 * 电源键是 toggle 且部分 ROM 对注入形态在熄屏态不响应，熄屏唤醒的确定性由幂等的
 * 亮屏/熄屏键承载）、媒体（音量±/静音）、背光调节（系统亮度档）与方向十字
 * （确认 = DPAD_CENTER）。
 */
object DeviceKeys {
    val HOME = DeviceKey(3, "主页")
    val BACK = DeviceKey(4, "返回")
    val MENU = DeviceKey(82, "菜单")
    val RECENTS = DeviceKey(187, "最近任务")
    val NOTIFICATION = DeviceKey(83, "通知")
    val POWER = DeviceKey(26, "电源")
    val WAKEUP = DeviceKey(224, "亮屏")
    val SLEEP = DeviceKey(223, "熄屏")
    val VOLUME_UP = DeviceKey(24, "音量+")
    val VOLUME_DOWN = DeviceKey(25, "音量−")
    val VOLUME_MUTE = DeviceKey(164, "静音")
    val BRIGHTNESS_UP = DeviceKey(221, "背光+")
    val BRIGHTNESS_DOWN = DeviceKey(220, "背光−")

    val DPAD_UP = DeviceKey(19, "上")
    val DPAD_DOWN = DeviceKey(20, "下")
    val DPAD_LEFT = DeviceKey(21, "左")
    val DPAD_RIGHT = DeviceKey(22, "右")
    val DPAD_CENTER = DeviceKey(23, "确认")

    /** 常用键组（面板网格呈现），呈现顺序即目录序 */
    val COMMON_KEYS: List<DeviceKey> = listOf(
        HOME, BACK, MENU, RECENTS,
        NOTIFICATION, POWER, WAKEUP, SLEEP,
        VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE,
        BRIGHTNESS_UP, BRIGHTNESS_DOWN,
    )

    /** 方向十字键组（面板十字呈现：上/下/左/右 + 中央确认） */
    val DPAD_KEYS: List<DeviceKey> = listOf(DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER)

    /**
     * 自定义键值上限（`input keyevent` 对未知码不报错但零效果——上限守门防
     * 手滑输入超大数后的「注入成功却无反应」误导，坏输入显式拒绝不静默）。
     */
    const val MAX_CUSTOM_KEYCODE = 999

    /** 自定义 keycode 解析：十进制正整数且 ≤ [MAX_CUSTOM_KEYCODE] 才成键，否则 null（人话拒绝由消费面呈现） */
    fun customKey(text: String): DeviceKey? {
        val code = text.trim().toIntOrNull() ?: return null
        if (code <= 0 || code > MAX_CUSTOM_KEYCODE) return null
        return DeviceKey(code, "自定义 $code")
    }
}

/** 单台设备一次注入的回执：[success] 与 [message] 互斥语义——成功 message 为 null，失败为人话原因 */
data class KeyInjectionReceipt(
    val serial: String,
    val key: DeviceKey,
    val success: Boolean,
    val message: String?,
)

/** 一次注入动作的回执集：目标集内逐台各一条（部分失败不拖垮其余台——逐台独立成败） */
data class KeyInjectionBatch(
    val key: DeviceKey,
    val receipts: List<KeyInjectionReceipt>,
)

/**
 * 注入器对外快照（StateFlow 值）：[injecting] 为批量在途 busy 态；
 * [repeatingKey] 为长按连发在途键（null = 无连发；连发与 busy 互斥——两者不同时
 * 为真）；[lastBatch] 为最近一次已执行批量（跨动作保留，成功/失败均更新——连发
 * 期每拍刷新为该拍的回执集）；[rejectError] 为最近一次未执行拒绝原因（无可用
 * 目标/勾选集为空/连发中单击——零 spawn 路径的人话回执，不得静默吞点击）。
 */
data class KeyEventInjectorState(
    val injecting: Boolean = false,
    val repeatingKey: DeviceKey? = null,
    val lastBatch: KeyInjectionBatch? = null,
    val rejectError: String? = null,
)

/**
 * 按键注入器（设备观测域）：把 GUI 按键动作分发为 `adb shell input keyevent <code>`
 * 命令，按目标设备集逐台循环分发。
 *
 * 通道纪律（三条 adb 链同过 [AdbCommandRewriter] 的口径）：
 * - 命令全文经 [keyEventCommand] 构造——payload 按 shell 语义包裹后经
 *   [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`，与 [PerfSampler] 同链；
 * - 执行走 [AdbProcessRunner]（由宿主机注入），fake 注入即可单测，
 *   免真子进程。
 *
 * 多设备分发语义：
 * - [injectKey]（单键钮默认路径）目标集 = 当前选定设备（未选定时单设备回退），
 *   保守默认只对一台注入；批量多台必须显式走 [injectToTargets]
 *   （面板多选勾选集），不做隐式全设备广播；
 * - 目标集解析失败（无设备/检测失败/多设备未选定/勾选集为空）零 spawn，
 *   写 [KeyEventInjectorState.rejectError] 人话回执；
 * - 批量逐台顺序执行，逐台独立成败（一台失败不中断其余台），回执集整体入
 *   [KeyEventInjectorState.lastBatch]；
 * - busy 期（批量在途）新动作直接忽略（连点防抖——按键注入无排队语义，
 *   在途批量完成后用户可重点）。
 *
 * 长按连发通道（[startRepeat]/[startRepeatToTargets]/[stopRepeat]）：
 * - 连发 = 可取消循环 job（注入器 [scope] 内）：每拍对目标集逐台注入一次并刷新
 *   [KeyEventInjectorState.lastBatch]，拍间等待 [REPEAT_INTERVAL_MS]——节流间隔
 *   常量单源（全通道唯一节流阀，防 adb 通道风暴：每拍每台至多一次 exec、
 *   拍间隔不得小于该常量默认档）；
 * - 松手即停三路取消纪律：① UI 抬起（[stopRepeat] 幂等，任意时刻可调）；
 *   ② 再武装覆盖（连发中 [startRepeat] 新键 = 旧 job 先取消再起新）；③ 会话停止
 *   （持有方 [scope] cancel 时 job 随会话消亡——连发 job 不自建 scope）；
 * - 互斥语义（保守拒绝）：连发中单击（[injectKey]/[injectToTargets]）直接拒绝
 *   并写 [KeyEventInjectorState.rejectError] 人话回执（不排队）；busy 期起新连发
 *   同样拒绝；连发不置 busy（[KeyEventInjectorState.injecting] 恒 false——
 *   连发是持续动作非批量在途）。
 *
 * 零 spawn 门控：本类无常驻轮询环——命令只在用户点击按键时 spawn 一次一次性
 * exec，页面不可见期间按钮不可达，与「不可见零 spawn」语义由构造保证
 * （[DeviceMonitor]/[PerfSampler] 的 setPageVisible 门控只服务常驻环，此处无环
 * 可门控，不引入死接线）。
 *
 * scope 由持有方注入（App 形态从 AppHost.scope 派生，见 [DeviceHub]；测试注入
 * 测试 scope 用 runTest 虚拟时钟驱动）。[applyBatch] 是宿主/测试预置回执的
 * 注入缝（同 [DeviceMonitor.applyDeviceList] 语义）。
 */
class KeyEventInjector(
    private val scope: CoroutineScope,
    private val devices: DeviceStateSource,
    private val runner: AdbProcessRunner,
    private val endpointReader: () -> AdbEndpoint,
    private val log: AdbLog = AdbLog.NONE,
) {

    private val _state = MutableStateFlow(KeyEventInjectorState())
    val state: StateFlow<KeyEventInjectorState> = _state.asStateFlow()

    /**
     * 连发在途 job（null = 无连发）。写点在调用方线程（App 形态 = UI 侧），读点在
     * [scope] 协程（AppHost.scope = Dispatchers.Default）的 finally 兜底。跨线程
     * **非原子**：覆盖再武装与旧 job finally 交错存在旧值误判窄窗，由
     * 「repeatJob === self」identity 守卫收窄（旧 job 只清自己持的键，不误清覆盖后的
     * 新连发键）——守卫收窄非根除，线程收口（单协程化/可见性）为登记后续项。
     * job 挂起在 [scope]。
     */
    private var repeatJob: Job? = null

    // ── 注入入口 ──

    /**
     * 注入单键到默认目标（保守默认：当前选定设备，未选定时单设备回退）。
     * 无可用目标零 spawn，人话拒绝进 [KeyEventInjectorState.rejectError]。
     * 「为什么没有目标」的详细指引（去设置/选设备/刷新）由消费面经
     * [DeviceStateSource] 三流自判（同 [PerfSampler] 口径），本类不复制该文案。
     */
    fun injectKey(key: DeviceKey) {
        val target = resolveDefaultTarget()
        if (target == null) {
            _state.value = _state.value.copy(rejectError = "无可用目标设备（未连接/未选定），未注入「${key.label}」")
            return
        }
        dispatch(key, listOf(target))
    }

    /**
     * 批量注入到显式目标集（面板多选勾选路径——目标集由调用方显式给出，
     * 本方法不做任何隐式扩集）。空集零 spawn，人话拒绝。
     */
    fun injectToTargets(key: DeviceKey, serials: List<String>) {
        if (serials.isEmpty()) {
            _state.value = _state.value.copy(rejectError = "批量目标集为空，未注入「${key.label}」")
            return
        }
        dispatch(key, serials)
    }

    /**
     * 回执唯一写路径（注入缝）：批量分发协程写入，宿主/测试预置「有回执」状态
     * 免真注入（同 [DeviceMonitor.applyDeviceList] 注入缝语义）。
     */
    fun applyBatch(batch: KeyInjectionBatch) {
        _state.value = _state.value.copy(lastBatch = batch, rejectError = null)
    }

    // ── 长按连发 ──

    /**
     * 长按连发（默认目标路径）：目标解析与 [injectKey] 同口径（选定优先/单设备回退），
     * 无可用目标零 spawn 人话拒绝。逐拍注入默认目标，拍间隔 [intervalMs]
     * （默认 [REPEAT_INTERVAL_MS] 单源）。停止由 UI 抬起调 [stopRepeat]。
     *
     * @return true = 连发已起；false = 零 spawn 拒绝（人话原因在
     *   [KeyEventInjectorState.rejectError]，UI 长按手势以本返回值决定是否吞掉
     *   松手后的那一次单击）
     */
    fun startRepeat(key: DeviceKey, intervalMs: Long = REPEAT_INTERVAL_MS): Boolean {
        val target = resolveDefaultTarget()
        if (target == null) {
            _state.value = _state.value.copy(rejectError = "无可用目标设备（未连接/未选定），未开始「${key.label}」长按连发")
            return false
        }
        return startRepeatToTargets(key, listOf(target), intervalMs)
    }

    /**
     * 长按连发（显式目标集路径，与 [injectToTargets] 对偶）：逐拍对目标集逐台注入。
     * 空集/非法间隔零 spawn 人话拒绝；busy 期拒绝；连发中再起 = 覆盖再武装（旧 job
     * 先取消）。首拍立即注入（长按成立时用户已按住一段时间），后续每拍间隔
     * [intervalMs]。
     *
     * @return 同 [startRepeat]（true = 连发已起）
     */
    fun startRepeatToTargets(key: DeviceKey, serials: List<String>, intervalMs: Long = REPEAT_INTERVAL_MS): Boolean {
        if (intervalMs <= 0) {
            _state.value = _state.value.copy(rejectError = "连发间隔非法（$intervalMs ms），未开始「${key.label}」长按连发")
            return false
        }
        if (_state.value.injecting) {
            _state.value = _state.value.copy(rejectError = "「${key.label}」有注入在途，未开始长按连发（完成后可再长按）")
            return false
        }
        if (serials.isEmpty()) {
            _state.value = _state.value.copy(rejectError = "批量目标集为空，未开始「${key.label}」长按连发")
            return false
        }
        // 覆盖再武装：在途连发让位（旧 job 先取消，finally 不清新键的 repeatingKey）
        repeatJob?.cancel()
        _state.value = _state.value.copy(repeatingKey = key, rejectError = null)
        log.debug(TAG) { "Repeat start ${key.label}(${key.keyCode}) -> ${serials.size} target(s), interval ${intervalMs}ms" }
        repeatJob = scope.launch {
            val self = coroutineContext[Job]
            try {
                val endpoint = endpointReader()
                while (isActive) {
                    val receipts = serials.map { serial -> injectOne(key, serial, endpoint.executablePath) }
                    _state.value = _state.value.copy(lastBatch = KeyInjectionBatch(key = key, receipts = receipts))
                    delay(intervalMs)
                }
            } finally {
                // 会话停止/覆盖路径下 job 被动消亡时兜底清连发键；[stopRepeat] 已同步清，
                // 此处仅兜「scope cancel / 异常」路径，且不误清覆盖后新连发的键
                if (repeatJob === self) {
                    _state.value = _state.value.copy(repeatingKey = null)
                }
            }
        }
        return true
    }

    /**
     * 停止长按连发（UI 抬起路径）：幂等——无在途连发时为无害 no-op。同步清
     * [KeyEventInjectorState.repeatingKey]（抬起即停的人话即时性：不等取消协程调度）。
     */
    fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        if (_state.value.repeatingKey != null) {
            log.debug(TAG) { "Repeat stop" }
            _state.value = _state.value.copy(repeatingKey = null)
        }
    }

    // ── 分发内核 ──

    /**
     * 批量分发：逐台顺序执行（一次一台的 `input keyevent`，无并发竞态面），
     * busy 态协程 finally 单点复位（异常路径也必须复位，防「永久注入中」——
     * 与 [DeviceMonitor] 检测 busy 同口径）。busy 期新动作忽略。
     */
    private fun dispatch(key: DeviceKey, serials: List<String>) {
        if (_state.value.injecting) return
        if (_state.value.repeatingKey != null) {
            // 连发与单击互斥（保守拒绝不排队）：人话回执不静默吞点击
            _state.value = _state.value.copy(rejectError = "「${key.label}」长按连发进行中，忽略单击（松开后再点）")
            return
        }
        _state.value = _state.value.copy(injecting = true, rejectError = null)
        scope.launch {
            try {
                val endpoint = endpointReader()
                val receipts = serials.map { serial -> injectOne(key, serial, endpoint.executablePath) }
                val batch = KeyInjectionBatch(key = key, receipts = receipts)
                _state.value = _state.value.copy(lastBatch = batch)
                log.debug(TAG) {
                    "Key ${key.label}(${key.keyCode}) -> " +
                        receipts.joinToString(";") { "${it.serial}:${if (it.success) "ok" else it.message}" }
                }
            } finally {
                _state.value = _state.value.copy(injecting = false)
            }
        }
    }

    /** 单台注入：执行通道 onFailure 与非零退出码（设备拔出时 adb 报错退出）都落人话失败回执，不抛出（不拖垮同批其余台） */
    private suspend fun injectOne(key: DeviceKey, serial: String, adbPath: String): KeyInjectionReceipt {
        val command = keyEventCommand(key.keyCode, adbPath, serial)
        return runner.execute(command).fold(
            onSuccess = { shellResult ->
                if (shellResult.exitCode == 0) {
                    KeyInjectionReceipt(serial, key, success = true, message = null)
                } else {
                    val stderrText = shellResult.stderr.trim().take(120).ifEmpty { "无错误输出" }
                    KeyInjectionReceipt(serial, key, success = false, message = "adb 退出码 ${shellResult.exitCode}：$stderrText")
                }
            },
            onFailure = { e ->
                KeyInjectionReceipt(serial, key, success = false, message = "adb 执行失败：${e.message ?: "未知异常"}")
            },
        )
    }

    /**
     * 默认目标解析（与 [PerfSampler] 单目标解析同口径）：选定优先（须与当前
     * 设备态对得上——选定残留而设备已消失按未选定处理，防对空串号注入 `-s`），
     * 未选定时单设备回退；无设备/检测失败/多设备未选定 → null（零 spawn）。
     */
    private fun resolveDefaultTarget(): String? {
        if (devices.detectError.value != null) return null
        val selected = devices.selectedSerial.value
        if (selected != null) {
            return when (val s = devices.deviceState.value) {
                is DeviceState.Single -> s.serial
                is DeviceState.Multi ->
                    if (s.devices.any { it.serial == selected }) selected else null

                DeviceState.None -> null
            }
        }
        val single = devices.deviceState.value as? DeviceState.Single ?: return null
        return single.serial
    }

    companion object {
        private const val TAG = "KeyEventInjector"

        /**
         * 长按连发默认拍间隔（毫秒）——节流间隔唯一真源：连发通道全形态（默认目标/
         * 批量目标）共用此默认档，调用方不得另设更小档（防 adb 通道风暴：每拍每台
         * 至多一次 exec，此常量定拍频上界）。UI 回执文案「长按连发中，间隔 N ms」
         * 亦读此值，防文案与实际节流失联。
         */
        const val REPEAT_INTERVAL_MS = 250L

        /**
         * 单台注入命令全文（internal 供单测断言通道纪律）：payload 按 shell 语义包裹
         * （Unix 单引号 / Windows 双引号——cmd 不解析单引号，与 [AdbCommandRewriter]
         * 的路径包裹同规则，[PerfSampler] 同配方），再经 [AdbCommandRewriter] 注入
         * adb 路径与 `-s <serial>`。
         */
        internal fun keyEventCommand(keyCode: Int, adbPath: String, serial: String): String {
            val payload = "input keyevent $keyCode"
            return AdbCommandRewriter.rewrite(
                "adb shell ${shellQuote(payload)}",
                adbPath,
                serial,
            )
        }

        private fun shellQuote(payload: String): String =
            DeviceShellSupport.quotePayload(payload)
    }
}
