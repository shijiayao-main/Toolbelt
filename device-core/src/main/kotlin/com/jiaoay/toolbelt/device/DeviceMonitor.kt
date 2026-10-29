package com.jiaoay.toolbelt.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * 设备三流窄接口（观测面）：设备连接态 / 检测失败态 / 会话级选定串号的只读订阅。
 * 观测型消费方（logcat 抓取、投屏、设备观测空间）只依赖本接口，不触碰检测链路与轮询。
 */
interface DeviceStateSource {
    /** 设备连接状态（None / Single / Multi） */
    val deviceState: StateFlow<DeviceState>

    /**
     * 最近一次设备检测失败信息（null = 检测成功或从未检测）：
     * 进程启动真失败、adb 命令非零退出（含 stderr 首行）都写这里；
     * 任何成功检测（含成功后无设备）清空——「检测成功但无设备」是 [DeviceState.None]
     * 正常态，不得与「检测失败」混同静默坍缩。
     */
    val detectError: StateFlow<String?>

    /** 会话级选定目标设备串号（null = 未选定；不持久化，进程内有效） */
    val selectedSerial: StateFlow<String?>
}

/**
 * 设备检测域核心：设备检测 / 可见性门控轮询 / 会话级选定态的唯一持有者。
 *
 * ## 职责
 *
 * 三流状态 + [detecting] busy 态 + [endpoint] 端点缓存全部收敛于此。手动入口
 * （[refreshDevices]，置 busy）与轮询路径（轮询循环内，恒不置 busy——轮询置 busy
 * 会让状态栏每 5 秒闪一次「检测中…」）共用同一检测链路，busy 在协程 finally
 * 单点复位（异常路径也必须复位，防「永久检测中」）。
 *
 * ## 端点与失败分流
 *
 * 每次检测重读端点（用户改配置无需重启即生效）。检测失败分三类，各自给人看的原因：
 * - 进程启动真失败（adb 不存在 / 无执行权限）；
 * - 命令非零退出（端口被占、内置二进制损坏）——以前会被当成「没有设备」，现在走
 *   [detectError] 并把 stderr 首行带上，否则「我们的 server 起不来」会伪装成
 *   「用户没插线」；
 * - 设备存在但状态不是 `device`（未授权 / offline / 无权限）——由 [DeviceIssue]
 *   承载，不属于检测失败。
 *
 * ## 轮询门控
 *
 * 构造即启动常驻循环，每 [DEVICE_POLL_INTERVAL_MS] 一拍；[setPageVisible] 的计数为 0
 * 时该拍静默跳过（不 spawn 登录 shell + adb 子进程）。计数而非末位写入：同帧切页时
 * 新旧两页的接线回调次序不保证，末位写入在多消费方下会静默失效。
 *
 * ## 依赖注入
 *
 * [scope] 由持有方注入（宿主从 App 级根 scope 派生，退出随根收束；测试注入测试 scope
 * 控制虚拟时钟）。本类不自建作用域，也不读任何全局单例。
 */
class DeviceMonitor(
    private val scope: CoroutineScope,
    private val runner: AdbProcessRunner,
    /** 端点读取缝：每次检测重读（配置改动即生效） */
    private val endpointReader: () -> AdbEndpoint,
    private val log: AdbLog = AdbLog.NONE,
    initialPageVisible: Boolean = false,
) : DeviceStateSource {

    private val _deviceState = MutableStateFlow<DeviceState>(DeviceState.None)
    override val deviceState: StateFlow<DeviceState> = _deviceState.asStateFlow()

    private val _detectError = MutableStateFlow<String?>(null)
    override val detectError: StateFlow<String?> = _detectError.asStateFlow()

    /**
     * 设备检测进行中（busy 态）：仅**用户可感知**的检测入口（手动刷新钮 + 进页即刷）
     * 置位、检测协程 finally 复位；轮询路径恒不置位。消费面：设备状态栏主行
     * 「（检测中…）」后缀 + 刷新钮禁用（连点防抖）。
     */
    private val _detecting = MutableStateFlow(false)
    val detecting: StateFlow<Boolean> = _detecting.asStateFlow()

    /**
     * 当前 adb 端点缓存（检测时快照；命令重写与 argv 通道读它）。
     *
     * 初值指向一个不存在的路径：首次检测前不该有命令被执行，任何提前读取都应显式失败
     * 而不是退回某个隐式搜索路径。
     */
    @Volatile
    var endpoint: AdbEndpoint = AdbEndpoint(executablePath = "")
        private set

    /**
     * 设备连接但不可用的条目（未授权 / offline / 无 USB 权限等）。
     *
     * 与 [deviceState] 分开：这些设备不能执行任何命令，混进设备列表会让「选定设备」
     * 指向一个用不了的目标；但它们是用户最需要看到的信息——「插上了却连不上」的
     * 原因几乎都在这里。
     */
    private val _issues = MutableStateFlow<List<DeviceIssue>>(emptyList())
    val issues: StateFlow<List<DeviceIssue>> = _issues.asStateFlow()

    /** 页面可见性门控计数（多消费方归并，任一消费页在台即轮询） */
    private val pageVisibleCount =
        java.util.concurrent.atomic.AtomicInteger(if (initialPageVisible) 1 else 0)

    private val _pageVisible = MutableStateFlow(initialPageVisible)

    private val _selectedSerial = MutableStateFlow<String?>(null)
    override val selectedSerial: StateFlow<String?> = _selectedSerial.asStateFlow()

    init {
        // 端点缓存冷启动恢复：构造即读，首轮命令重写前大概率已就绪
        scope.launch { endpoint = endpointReader() }
        // 设备检测自动轮询——插拔即更新，无需手动刷新。首拍先等一个间隔：进页即刷
        // 由页面承担，等式避免了「进页即刷 + 轮询首拍」双发。
        scope.launch {
            while (isActive) {
                delay(DEVICE_POLL_INTERVAL_MS)
                if (_pageVisible.value) refreshDevicesFromPoll()
            }
        }
    }

    // ── 会话级设备选择（多设备场景，不持久化） ──

    /** 选定目标设备（设备选择弹窗确认后写入），此后命令注入 `-s <serial>` */
    fun selectDevice(serial: String) {
        _selectedSerial.value = serial
    }

    /** 清除设备选择：命令不再注入 `-s`，状态栏回到「选择设备」入口 */
    fun clearDeviceSelection() {
        _selectedSerial.value = null
    }

    /**
     * 页面可见性门控接线（计数语义见类头）：true = 消费页进入组合（+1）、
     * false = 消费页退出组合（−1）；计数归零 = 全部消费页离台。
     */
    fun setPageVisible(visible: Boolean) {
        val next = if (visible) {
            pageVisibleCount.incrementAndGet()
        } else {
            pageVisibleCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        _pageVisible.value = next > 0
    }

    // ── 检测入口 ──

    /** 手动刷新设备（刷新钮 + 进页即刷——用户可感知入口，置 busy） */
    fun refreshDevices() {
        refreshDevicesInternal(manual = true)
    }

    /** 轮询静默刷新（轮询循环专用）：同一检测链路但不置 busy */
    internal fun refreshDevicesFromPoll() {
        refreshDevicesInternal(manual = false)
    }

    private fun refreshDevicesInternal(manual: Boolean) {
        if (manual) _detecting.value = true
        // 是显式 IO 还是继承 scope 的调度器，由持有方决定：宿主注入 Main/Default 派生的
        // scope 时检测会占用 UI 线程，故宿主侧 scope 应含 Dispatchers.IO；测试注入虚拟
        // 时钟调度器时检测必须落在同一调度器上才能被推进——本类不自选 dispatcher。
        scope.launch {
            try {
                detectOnce()
            } finally {
                if (manual) _detecting.value = false
            }
        }
    }

    /**
     * 单次设备检测：手动刷新 / 轮询 / agent 设备工具三路共用。
     *
     * 返回本次处于 `device` 态的设备列表；检测失败或只有不可用设备时返回空列表
     * （原因分别落在 [detectError] 与 [issues]）。
     */
    suspend fun detectOnce(): List<DeviceInfo> {
        val current = endpointReader()
        endpoint = current
        val command = AdbCommandRewriter.rewrite("adb devices -l", current, serial = null)
        return runner.execute(command).fold(
            onSuccess = { result ->
                if (result.exitCode != 0) {
                    // 非零退出不是「没有设备」：端口被占 / 二进制损坏 / adb 版本冲突都会落到这里
                    val detail = shellErrorText(result, emptyFallback = "").ifEmpty { "退出码 ${result.exitCode}" }
                    log.debug(TAG) { "detect failed (exit ${result.exitCode}): $detail" }
                    applyDetectError("设备检测失败：$detail")
                    return@fold emptyList()
                }
                val parsed = DeviceMonitorParser.parse(result.stdout)
                applyDeviceList(parsed.devices, parsed.issues)
                log.debug(TAG) { "Devices: ${parsed.devices}, issues: ${parsed.issues}" }
                parsed.devices
            },
            onFailure = { e ->
                log.debug(TAG) { "detect spawn failed: ${e.message}" }
                applyDetectError("设备检测失败：${e.message ?: "未知异常"}")
                emptyList()
            },
        )
    }

    /**
     * 设备列表应用到状态：更新 [deviceState] 与 [issues]，并维护会话级选择——非 Multi
     * （未连接 / 单设备不需选择）或已选串号不在新设备列表时自动清除。成功路径
     * （含空列表）同步清空 [detectError]。
     */
    fun applyDeviceList(devices: List<DeviceInfo>, issues: List<DeviceIssue> = emptyList()) {
        val newState = when {
            devices.isEmpty() -> DeviceState.None
            devices.size == 1 -> DeviceState.Single(devices.first().serial, devices.first().model)
            else -> DeviceState.Multi(devices)
        }
        _deviceState.value = newState
        _issues.value = issues
        _detectError.value = null

        val selected = _selectedSerial.value
        if (selected != null) {
            val stillValid = newState is DeviceState.Multi && newState.devices.any { it.serial == selected }
            if (!stillValid) {
                _selectedSerial.value = null
                log.debug(TAG) { "Selected serial $selected no longer valid, cleared" }
            }
        }
    }

    /**
     * 检测失败态应用到状态（与 [applyDeviceList] 对称）：写入 [detectError]、坍缩
     * [DeviceState.None] 并清除陈旧选择避免误注入——检测失败时设备状态未知。
     */
    fun applyDetectError(message: String) {
        _detectError.value = message
        _deviceState.value = DeviceState.None
        _issues.value = emptyList()
        _selectedSerial.value = null
    }

    companion object {
        private const val TAG = "DeviceMonitor"

        /** 设备检测自动轮询间隔（`adb devices -l` 轻量无害；插拔即更新） */
        const val DEVICE_POLL_INTERVAL_MS: Long = 5_000L
    }
}
