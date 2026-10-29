package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 一次转无线动作的回执（目标串号 + 人话结果；成功附无线新串号）。
 * [message] 恒非空：成功含新串号与后续指引，失败含步骤定位与原因，供消费面直出 toast。
 */
data class WirelessConnectReceipt(
    val serial: String,
    val success: Boolean,
    val message: String,
    /** 成功时的无线新串号（`<ip>:<port>` 形态，adb devices 检测面直接可见）；失败为 null */
    val newSerial: String? = null,
)

/**
 * 连接器对外快照（StateFlow 值）：
 * - [connecting] = 三步编排在途（连点防抖用）；
 * - [lastReceipt] = 最近一次已执行编排的回执（成功/失败均更新，跨动作保留）；
 * - [rejectError] = 最近一次未执行拒绝原因（无可用目标等零 spawn 路径的人话回执，
 *   不得静默吞点击）；
 * - [eventSeq] = 回执/拒绝事件的递增序号（每事件恰好 +1）：两类回执都经同一 toast
 *   通道呈现，消费方以本值为 toast 去重键（同文本重复事件不丢条）。
 */
data class WirelessConnectorState(
    val connecting: Boolean = false,
    val lastReceipt: WirelessConnectReceipt? = null,
    val rejectError: String? = null,
    val eventSeq: Long = 0,
)

/**
 * 一键转无线调试连接器（设备观测域）：把「查设备 IP → adb tcpip → adb connect」
 * 三步压成一钮，供设备栏动作钮消费。
 *
 * 编排顺序契约（先探后切）：**IP 探测必须排在 tcpip 之前**——`adb tcpip` 会把设备侧
 * adbd 切到 TCP 监听并停掉 USB 通道，切完探测通道即失（设备已失联而 IP 未知，用户
 * 只能重插 USB 解套）。先探后切保证探测失败时 USB 完好、设备零损伤退出。
 *
 * 三步与失败语义（每步显式人话回执，不静默）：
 * 1. 设备侧 IP 探测：`ip route`（default 行 src 优先）与 `ip addr`（全局 IPv4，
 *    wlan 接口优先、跳过 secondary 副地址）双形态逐级兜底；两形态零命中 → 失败回执
 *    引导走 Shell 工具页「连接无线设备」手动命令面（host/port 手填）兜底，**不盲连**；
 * 2. `adb tcpip 5555`：失败（进程失败或非零退出码）即**中止**——不再 spawn connect，
 *    回执含退出码与错误输出摘录；USB 通道未切、可重试；
 * 3. 固定等待 [CONNECT_SETTLE_DELAY_MS] 后 `adb connect <ip>:5555`：adbd 重启为
 *    TCP 监听需要片刻，立即 connect 会撞上监听未起。connect 是**主机侧命令**，
 *    serial 恒传 null——经 [AdbCommandRewriter] 注入 `-s` 会使命令失效。成功判定 =
 *    退出码 0 且输出含 `connected to`（「already connected to」同视为成功——adb 旧版
 *    connect 失败也回 0 退出码，仅靠退出码判定会把失败误报成功）。
 *
 * 同设备双条目契约：connect 成功后检测面会出现 USB 串号与 `<ip>:5555` 并存（USB
 * 通道断开前）乃至仅剩无线条目（断开后）的形态，检测面按 `adb devices -l` 原样呈现，
 * 本域不做任何去重合并（去重属后续裁决）。
 *
 * 通道纪律（三条 adb 链同过 [AdbCommandRewriter] 的口径，与 KeyEventInjector 同链）：
 * 命令全文经本伴生对象构造器拼装，执行统一走 [AdbProcessRunner]（宿主机注入的执行通道），fake 注入即可单测编排链路，免真子进程。
 *
 * 目标解析（与 KeyEventInjector 的默认目标解析同口径）：选定优先（须与当前设备态对
 * 得上，残留选定按未选定处理）、未选定时单设备回退；无设备/检测失败/多设备未选定/
 * 空串号 → 零 spawn 人话拒绝进 [WirelessConnectorState.rejectError]。
 *
 * 零 spawn 门控：无常驻轮询环，命令只在用户点击动作钮时 spawn；busy 期（编排在途）
 * 新动作直接忽略（连点防抖，busy 在协程 finally 单点复位——异常路径也必须复位，防
 * 「永久转无线中」）。scope 由持有方注入（App 形态从 AppHost.scope 派生，见
 * [DeviceHub]；测试注入测试 scope 用 runTest 虚拟时钟驱动）。
 */
class DeviceWirelessConnector(
    private val scope: CoroutineScope,
    private val devices: DeviceStateSource,
    private val runner: AdbProcessRunner,
    private val endpointReader: () -> AdbEndpoint,
    private val log: AdbLog = AdbLog.NONE,
) {

    private val _state = MutableStateFlow(WirelessConnectorState())
    val state: StateFlow<WirelessConnectorState> = _state.asStateFlow()

    /**
     * 发起一键转无线（动作钮入口）：目标解析失败零 spawn 人话拒绝；busy 期新动作
     * 忽略。回执/拒绝经 [WirelessConnectorState] 落地，toast 由消费面接线。
     */
    fun startWirelessConnect() {
        if (_state.value.connecting) return
        val target = resolveDefaultTarget()
        if (target == null) {
            val current = _state.value
            _state.value = current.copy(
                rejectError = "无可用目标设备（未连接/多设备未选定），未切无线",
                eventSeq = current.eventSeq + 1,
            )
            return
        }
        _state.value = _state.value.copy(connecting = true)
        scope.launch {
            try {
                val endpoint = endpointReader()
                val receipt = connectWireless(target, endpointReader().executablePath)
                val current = _state.value
                _state.value = current.copy(
                    lastReceipt = receipt,
                    rejectError = null,
                    eventSeq = current.eventSeq + 1,
                )
                log.debug(TAG) {
                    "Wireless connect ${if (receipt.success) "ok" else "fail"}: ${receipt.message}"
                }
            } finally {
                // 三路收口单点复位：成功/失败/异常路径都必须复位，防「永久转无线中」
                _state.value = _state.value.copy(connecting = false)
            }
        }
    }

    // ── 三步编排内核 ──

    /**
     * 编排主体：步骤顺序与失败语义见类头注释（先探后切/失败即止/不盲连）。
     * 每条 执行回执要么是最终回执要么携带人话原因，中间态不落 state。
     */
    private suspend fun connectWireless(serial: String, adbPath: String): WirelessConnectReceipt {
        // 步一：设备侧 IP 探测（趁 USB 通道在位——tcpip 之后探针即失）
        val (ip, probeIssue) = probeDeviceIp(serial, adbPath)
        if (ip == null) {
            return WirelessConnectReceipt(
                serial = serial,
                success = false,
                message = "设备侧 IP 探测失败（$probeIssue）。USB 通道未动——" +
                    "可在 Shell 工具页「连接无线设备」手动输入设备 IP 与端口连接",
            )
        }

        // 步二：tcpip 切换（失败即中止，不 spawn connect）
        val tcpip = runner.execute(tcpipCommand(adbPath, serial))
        val tcpipOk = tcpip.fold(onSuccess = { it.exitCode == 0 }, onFailure = { false })
        if (!tcpipOk) {
            return WirelessConnectReceipt(
                serial = serial,
                success = false,
                message = "tcpip $WIRELESS_ADB_PORT 失败（${failureReason(tcpip)}）——已中止，未盲连",
            )
        }

        // 步二点五：settle——adbd 重启为 TCP 监听需要片刻（延迟常量契约见类头）
        delay(CONNECT_SETTLE_DELAY_MS)

        // 步三：connect（主机侧命令，serial 恒不注入）
        val connect = runner.execute(connectCommand(adbPath, ip))
        val connectOk = connect.fold(
            onSuccess = { it.exitCode == 0 && (it.stdout + it.stderr).contains("connected to") },
            onFailure = { false },
        )
        if (!connectOk) {
            return WirelessConnectReceipt(
                serial = serial,
                success = false,
                message = "无线 connect $ip:$WIRELESS_ADB_PORT 失败（${failureReason(connect)}）——" +
                    "设备已切 TCP 模式，确认主机与设备网络互通后重试",
            )
        }
        return WirelessConnectReceipt(
            serial = serial,
            success = true,
            message = "已切无线：$ip:$WIRELESS_ADB_PORT。设备列表稍候将新增 $ip:$WIRELESS_ADB_PORT 条目" +
                "（USB 条目断开前两者并存，如实呈现）",
            newSerial = "$ip:$WIRELESS_ADB_PORT",
        )
    }

    /**
     * 设备侧 IP 双形态探测：`ip route`（default 行 src 优先，无 default 时任意带 src
     * 的路由行）→ `ip addr`（全局 IPv4，wlan 优先、跳过 secondary）逐级兜底。
     * 探测命令单条失败（非零退出码/进程失败）不构成编排失败——另一形态继续兜底；
     * 双形态全无命中才返回 null，且附未命中的原因描述（探测命令也全失败时优先报
     * 命令失败原因，比「零命中」更接近真实根因）。
     */
    private suspend fun probeDeviceIp(serial: String, adbPath: String): Pair<String?, String> {
        var issue = "ip route 与 ip addr 输出中未解析到可用 IPv4"
        val route = runner.execute(ipRouteCommand(adbPath, serial))
        route.onSuccess {
            if (it.exitCode != 0) {
                issue = "ip route 退出码 ${it.exitCode}：${it.stderr.trim().take(80).ifEmpty { "无错误输出" }}"
            }
        }
        route.onFailure { issue = "ip route 执行失败：${it.message ?: "未知异常"}" }
        route.getOrNull()?.takeIf { it.exitCode == 0 }?.let { parsed ->
            parseRouteSrc(parsed.stdout)?.let { return it to issue }
        }

        val addr = runner.execute(ipAddrCommand(adbPath, serial))
        addr.onSuccess {
            if (it.exitCode != 0) {
                issue = "ip addr 退出码 ${it.exitCode}：${it.stderr.trim().take(80).ifEmpty { "无错误输出" }}"
            }
        }
        addr.onFailure { issue = "ip addr 执行失败：${it.message ?: "未知异常"}" }
        addr.getOrNull()?.takeIf { it.exitCode == 0 }?.let { parsed ->
            parseAddrIpv4(parsed.stdout)?.let { return it to issue }
        }
        return null to issue
    }

    /** 人话失败原因：进程失败报异常信息；退出码失败报退出码 + 错误输出摘录（stdout/stderr 取非空者） */
    private fun failureReason(result: Result<ShellResult>): String =
        result.fold(
            onSuccess = {
                val output = (it.stderr.ifBlank { it.stdout }).trim().take(120)
                "退出码 ${it.exitCode}：${output.ifEmpty { "无错误输出" }}"
            },
            onFailure = { "adb 执行失败：${it.message ?: "未知异常"}" },
        )

    /**
     * 默认目标解析（与 KeyEventInjector 的默认目标解析同口径）：选定优先（须与当前
     * 设备态对得上——选定残留而设备已消失按未选定处理，防对空串号注入 `-s`）、
     * 未选定时单设备回退；空串号按无目标处理（检测面串号非空，此处守门防注入缝
     * 预置出空目标后的空 spawn）。
     */
    private fun resolveDefaultTarget(): String? {
        if (devices.detectError.value != null) return null
        val selected = devices.selectedSerial.value
        val raw: String? = if (selected != null) {
            when (val s = devices.deviceState.value) {
                is DeviceState.Single -> s.serial
                is DeviceState.Multi ->
                    if (s.devices.any { it.serial == selected }) selected else null

                DeviceState.None -> null
            }
        } else {
            (devices.deviceState.value as? DeviceState.Single)?.serial
        }
        return raw?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "DeviceWirelessConnector"

        /** 无线调试端口（tcpip 与 connect 共用同端口——切换与回连不一致即连不上，单一真源） */
        const val WIRELESS_ADB_PORT = 5555

        /**
         * tcpip 与 connect 之间的固定等待（毫秒）：adbd 重启为 TCP 监听需要片刻，
         * 立即 connect 会撞上监听未起。经验档（参考实现 aya 同位 500ms 实测档），
         * 改小须连同真机端到端验收一起评审。
         */
        const val CONNECT_SETTLE_DELAY_MS = 500L

        /** `ip route` 输出中的 `src <ipv4>`（路由源地址 = 该网卡的可达 IPv4） */
        private val ROUTE_SRC = Regex("""\bsrc\s+(\d{1,3}(?:\.\d{1,3}){3})\b""")

        /** `ip addr` 输出中的 `inet <ipv4>/<prefix>`（`inet6` 行不含 `inet ` 空格词形，天然排除 IPv6） */
        private val ADDR_INET = Regex("""\binet\s+(\d{1,3}(?:\.\d{1,3}){3})/\d+""")

        /**
         * `ip route` 输出解析：default 行的 src 优先（默认路由源 = 实际可达出口 IP），
         * 无 default 或 default 无 src 时按行序取首个带 src 的路由行。零命中 null。
         * internal 供单测断言解析矩阵。
         */
        internal fun parseRouteSrc(output: String): String? {
            val lines = output.lines().filter { it.isNotBlank() }
            val defaults = lines.filter { it.trim().startsWith("default") }
            return (defaults + lines.filter { it !in defaults }).firstNotNullOfOrNull { line ->
                ROUTE_SRC.find(line)?.groupValues?.get(1)
            }
        }

        /**
         * `ip addr` 输出解析：只认 `scope global` 行（lo 的 `scope host` 与链路本地
         * `scope link` 天然排除），跳过 secondary 副地址（主地址优先——同接口多 IP 时
         * 副地址多为历史配置），wlan 接口优先于其余接口（无 wlan 的有线形态回退
         * 接口序首个）。零命中 null。internal 供单测断言解析矩阵。
         */
        internal fun parseAddrIpv4(output: String): String? {
            val wlanFirst = mutableListOf<Pair<String, String>>()
            val others = mutableListOf<Pair<String, String>>()
            for (line in output.lines()) {
                if ("scope global" !in line) continue
                val match = ADDR_INET.find(line) ?: continue
                val ip = match.groupValues[1]
                if (ip.startsWith("127.")) continue
                if (" secondary" in line) continue
                val device = line.trim().split(Regex("\\s+")).last()
                if (device.startsWith("wlan")) wlanFirst += ip to device else others += ip to device
            }
            return (wlanFirst + others).firstOrNull()?.first
        }

        /**
         * tcpip 命令全文（internal 供单测断言通道纪律）：经 [AdbCommandRewriter] 注入
         * adb 路径与 `-s <serial>`。
         */
        internal fun tcpipCommand(adbPath: String, serial: String): String =
            AdbCommandRewriter.rewrite("adb tcpip $WIRELESS_ADB_PORT", adbPath, serial)

        /** 设备侧 IP 探测命令（route 形态）：payload 按 shell 语义包裹后经 [AdbCommandRewriter] 注入路径与 serial */
        internal fun ipRouteCommand(adbPath: String, serial: String): String =
            AdbCommandRewriter.rewrite("adb shell ${shellQuote("ip route")}", adbPath, serial)

        /** 设备侧 IP 探测命令（addr 形态兜底）：同 [ipRouteCommand] 配方 */
        internal fun ipAddrCommand(adbPath: String, serial: String): String =
            AdbCommandRewriter.rewrite("adb shell ${shellQuote("ip addr")}", adbPath, serial)

        /**
         * connect 命令全文：主机侧命令，serial 恒传 null——注入 `-s` 会使 `adb connect`
         * 失效（connect 的目标是 `<ip>:<port>` 串号本身）。
         */
        internal fun connectCommand(adbPath: String, ip: String): String =
            AdbCommandRewriter.rewrite("adb connect $ip:$WIRELESS_ADB_PORT", adbPath, serial = null)

        /** payload 按 shell 语义包裹（Unix 单引号 / Windows 双引号，与 KeyEventInjector 同配方） */
        private fun shellQuote(payload: String): String =
            DeviceShellSupport.quotePayload(payload)
    }
}

// OPEN(无线调试): 端到端真机激活（tcpip 实切 + connect 回连手感）属用户门禁项，待登记验收
