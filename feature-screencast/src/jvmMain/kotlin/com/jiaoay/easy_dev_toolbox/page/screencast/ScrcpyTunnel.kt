package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.easy_dev_toolbox.core.extensions.ShellResult
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.IOException
import java.net.ServerSocket
import kotlinx.coroutines.CancellationException

/**
 * 隧道建立结果：形态决定 server 端 `tunnel_forward` 参数与握手分型（探活字节有无），
 * 二者必须同源——reverse 结果配 `tunnel_forward=false` 的 serve 命令与无探活握手，
 * forward 结果反之。
 */
sealed interface ScrcpyTunnel {

    /**
     * reverse 隧道（官方默认优先路径）：[listener] 已绑定本地 [port]，adb reverse 规则
     * 已建立；server 启动后主动反连，[listener].accept() 即得视频连接（socket 归调用方所有，
     * listener 本体由 [ScrcpyTunnelOpener.close] 收尾）。
     */
    class Reverse(val port: Int, val listener: ServerSocket) : ScrcpyTunnel

    /** forward 隧道：adb 规则已建立；客户端向 127.0.0.1:[port] 发起连接 + 探活重试段接续 */
    class Forward(val port: Int) : ScrcpyTunnel
}

/**
 * 隧道路径偏好（[ScrcpyTunnelOpener] 构造缝参数）：
 * - [ReverseFirst]：官方默认（reverse 优先 fallback forward）——生产现行为；
 * - [ForwardOnly]：跳过 reverse 段直走 forward，**零 reverse 尝试**——E2E 驱动 forward
 *   隧道形态的门控缝（生产面无消费者：reverse 不通的设备由 [ReverseFirst] 的
 *   fallback 链自动落 forward，无需人工偏好）。
 */
enum class TunnelPreference { ReverseFirst, ForwardOnly }

/**
 * 隧道建立策略（对齐官方 adb_tunnel.c 的 sc_adb_tunnel_open 与 enable_tunnel_*_any_port）：
 *
 * 1. **reverse 优先**（官方默认）：端口范围内逐口尝试 `adb reverse` + 本地 bind；
 *    `adb reverse` 命令本身失败（典型为无线 adb connect 场景）→ 整体转 forward；
 *    命令成功但 bind 失败（端口被占）→ remove 规则后下一端口（官方同款次序）；
 * 2. **forward 逐试**：端口范围内逐口 `adb forward`，命令成功即选定——设备端 server 未就绪
 *    的连通性问题不在此判，由握手段的探活重试循环兜底（官方同款分层）；
 * 3. **forward tcp:0 自动分配**：spike 待验项（官方客户端从不使用 tcp:0）——
 *    [probeForwardAutoPort] 建规则后经 `adb forward --list` 回读解析实际端口，
 *    回显字面量 `tcp:0` 即解析断裂返回 null；真机行为未验前不得作为主路径引用。
 *
 * 端口范围默认官方值 27183..27199；全部命令经 [ScrcpyAdbGateway]（生产实现注入
 * AdbCommandRewriter + SimpleShell），本类型不感知 adb 路径与串号。
 */
class ScrcpyTunnelOpener(
    private val adb: ScrcpyAdbGateway,
    private val socketName: String,
    private val portRange: IntRange = ScrcpyServerDeployer.PORT_RANGE_FIRST..ScrcpyServerDeployer.PORT_RANGE_LAST,
    private val listenerFactory: LocalListenerFactory = ServerSocketListenerFactory,
    private val tunnelPreference: TunnelPreference = TunnelPreference.ReverseFirst,
) {

    /** 本地监听口绑定接缝：返回 null = bind 失败（端口被占）；单测注入 fake 不占真实端口 */
    fun interface LocalListenerFactory {
        fun bind(port: Int): ServerSocket?
    }

    /** 生产实现：真实 [ServerSocket] 绑定 */
    object ServerSocketListenerFactory : LocalListenerFactory {
        override fun bind(port: Int): ServerSocket? =
            runCatching { ServerSocket(port) }.getOrNull()
    }

    /**
     * reverse 规则挂账：已发建规则命令且未在可观测处核销（命令回执失败 = 规则必不在档；
     * remove 回执成功 = 规则已摘）。挂账覆盖「命令已发但结果被取消吞没」的开链夭折形态
     * ——此类结果未观测，规则可能在档，收口必须按挂账补发摘除。
     * 仅会话协程触达（open 与 close 同协程串行），无须并发防护。
     */
    private var reverseRuleOutstanding = false

    /** forward 规则挂账（按宿主端口），核销口径同 [reverseRuleOutstanding] */
    private val forwardPortsOutstanding = mutableSetOf<Int>()

    /**
     * 按构造缝的 [TunnelPreference] 开隧道：[TunnelPreference.ReverseFirst]（默认）=
     * reverse 优先、失败 fallback forward；[TunnelPreference.ForwardOnly] 零 reverse
     * 尝试直走 forward。两路全败抛 [IOException]；端口范围耗尽同样走完偏好链后抛出。
     */
    suspend fun open(): ScrcpyTunnel {
        if (tunnelPreference != TunnelPreference.ForwardOnly) {
            openReverse()?.let { return it }
        }
        openForwardAnyPort()?.let { return it }
        throw IOException(
            "no scrcpy tunnel established in port range ${portRange.first}..${portRange.last}",
        )
    }

    /**
     * reverse 路径逐口尝试；adb reverse 命令失败或端口耗尽返回 null（调用方转 forward）。
     * 取消不落「命令失败」分支：网关契约（[ScrcpyAdbGateway]）下取消原样上抛——误读为
     * 失败会触发 fallback 链（取消后的盲走），令会话按取消收口。
     */
    suspend fun openReverse(): ScrcpyTunnel.Reverse? {
        for (port in portRange) {
            reverseRuleOutstanding = true
            val rule = adb.execute(ScrcpyServerDeployer.buildReverseCommand(port, socketName))
            rethrowIfCancelled(rule)
            if (rule.isFailure) {
                // adb reverse 不可用（无线 adb connect 等场景）对本范围内任意端口同样不可用，
                // 官方即整体转 forward，不逐口重试命令；命令回执失败 = 规则必不在档，核销
                reverseRuleOutstanding = false
                return null
            }
            val listener = listenerFactory.bind(port)
            if (listener != null) return ScrcpyTunnel.Reverse(port, listener)
            // bind 失败：规则已挂到不可用端口，显式 remove 再右移（官方 enable_tunnel_reverse_any_port 同款）
            val removal = adb.execute(ScrcpyServerDeployer.buildRemoveReverseCommand(socketName))
            rethrowIfCancelled(removal)
            if (removal.isSuccess) reverseRuleOutstanding = false
        }
        return null
    }

    /**
     * forward 路径逐口尝试；全口命令失败返回 null。取消语义同 [openReverse]：
     * 不落「命令失败」分支（下一个端口的重试属于取消后的盲走）。
     */
    suspend fun openForwardAnyPort(): ScrcpyTunnel.Forward? {
        for (port in portRange) {
            forwardPortsOutstanding += port
            val rule = adb.execute(ScrcpyServerDeployer.buildForwardCommand(port, socketName))
            rethrowIfCancelled(rule)
            if (rule.isSuccess) return ScrcpyTunnel.Forward(port)
            forwardPortsOutstanding -= port
        }
        return null
    }

    /**
     * spike 待验项：tcp:0 自动分配端口的解析路径（不在 [open] 主链内，真机验证成立与否后
     * 再决定是否进主链）。建立 `adb forward tcp:0` 规则后经 `adb forward --list` 回读，
     * 回显形如 `<serial> tcp:<port> localabstract:<socketName>`，取含本 socket 名行的宿主端口；
     * 正端口即按该端口 remove 后返回（探针自清理：探针只做解析验证，不作为隧道持有——
     * 需要以 tcp:0 作为正式隧道的策略应自建规则并自行管理生命周期）。
     *
     * 解析断裂的 remove 契约：解析得数值端口但非正端口（回显字面量 `tcp:0`）→ 按解析出的
     * 该值 remove（建规则时的宿主键就是 tcp:0，键已知）；行格式不可读、解析不出任何数值
     * 端口 → 规则键不可知，按建规则时的 `tcp:0` **尽力 remove**（命中与否不保证，残留由
     * `adb forward --list` 核对暴露）；两种形态均返回 null。
     */
    suspend fun probeForwardAutoPort(): Int? {
        val established = adb.execute(ScrcpyServerDeployer.buildForwardCommand(0, socketName))
        if (established.isFailure) return null
        val listing = adb.execute(FORWARD_LIST_COMMAND)
        val stdout = listing.getOrNull()?.stdout.orEmpty()
        var parsedNonPositivePort: Int? = null
        for (line in stdout.lines()) {
            if (socketName !in line) continue
            val hostSpec = line.trim().split(WHITESPACE).getOrNull(1) ?: continue
            val port = hostSpec.removePrefix("tcp:").toIntOrNull() ?: continue
            if (port > 0) {
                adb.execute(ScrcpyServerDeployer.buildRemoveForwardCommand(port))
                return port
            }
            if (parsedNonPositivePort == null) parsedNonPositivePort = port
        }
        adb.execute(ScrcpyServerDeployer.buildRemoveForwardCommand(parsedNonPositivePort ?: 0))
        return null
    }

    /**
     * 隧道收尾（Major-3 验收点之一：规则残留必须清）——reverse 关 listener，规则按挂账面
     * best-effort 摘除：reverse 按 socket 名、forward 按已发建规则命令的端口全集（含
     * 「命令已发但结果被取消吞没」的开链夭折口）。挂账在可观测处核销，故确定性路径的
     * 摘除命令序列与按 tunnel 单点摘除逐条一致（回执失败的口不补发摘除，无 adb 报错噪音）。
     *
     * [tunnel] 为 null = 会话死于开链中段（未持隧道）——挂账面摘除照发，不得因无隧道
     * 而静默跳过。adb remove 命令失败不抛（收尾尽力语义，失败面经日志暴露，残留由
     * `adb forward/reverse --list` 核对点暴露），listener 关闭失败同理。
     */
    suspend fun close(tunnel: ScrcpyTunnel?) {
        if (tunnel is ScrcpyTunnel.Reverse) {
            runCatching { tunnel.listener.close() }
        }
        if (reverseRuleOutstanding) {
            removeRuleBestEffort(ScrcpyServerDeployer.buildRemoveReverseCommand(socketName))
        }
        forwardPortsOutstanding.forEach { port ->
            removeRuleBestEffort(ScrcpyServerDeployer.buildRemoveForwardCommand(port))
        }
    }

    /**
     * 摘除命令失败不抛（尽力语义）：进程级失败与 adb 回执失败（exitCode != 0）都落日志
     * ——静默吞掉即失去「规则为何残留」的唯一线索。
     */
    private suspend fun removeRuleBestEffort(command: String) {
        val result = adb.execute(command)
        result.exceptionOrNull()?.let { failure ->
            JLog.e(TAG) { "tunnel rule remove failed: $command (${failure.message})" }
            return
        }
        result.getOrNull()?.takeIf { it.exitCode != 0 }?.let { shell ->
            JLog.e(TAG) { "tunnel rule remove rejected: $command (stderr=${shell.stderr.trim()})" }
        }
    }

    /**
     * 取消不得落「命令失败」分支——本类是挂账收口与「不盲走 fallback」语义的守门点。
     * [ScrcpyAdbGateway] 的取消契约（取消原样上抛、不并入 Failure）由生产实现
     * SimpleShell 在 :core 收口；但网关是注入缝（测试 fake / 替代实现不受 :core 约束），
     * 违约把取消装进 Failure 时此处还原为上抛——两行成本换缝级防御，交会话按取消收口。
     */
    private fun rethrowIfCancelled(result: Result<ShellResult>) {
        val cancelled = result.exceptionOrNull() as? CancellationException ?: return
        throw cancelled
    }

    private companion object {
        const val TAG = "ScrcpyTunnel"

        const val FORWARD_LIST_COMMAND = "adb forward --list"
        val WHITESPACE = Regex("\\s+")
    }
}
