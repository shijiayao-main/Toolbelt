// observe：桥接整体状态（合并网关连接状态机 + 新增 Blocked / Draining）。
// 网关状态与桥接状态合并成一个**lib 侧可读**的状态面：
//    UI 与 CLI 只读 [BridgeStatusSnapshot]，不再直接依赖网关类型。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import com.jiaoay.lark.gateway.GatewayConnectionState
import com.jiaoay.lark.gateway.GatewayErrorCode

/** [BridgeConnectionStatus.Blocked] 的原因（本地互斥与网关互斥是**两个不同的阻断源**，必须可区分）。 */
enum class BlockedReason {
    /** 网关报告：同一 appId 已有另一条长连接在线（`GatewayConnectionState.BlockedByPeerInstance`）。 */
    PEER_INSTANCE,

    /** 本地报告：`SingleInstanceLock` 的 `FileChannel.tryLock` 拿不到锁（同机同 appId 第二个进程）。 */
    SINGLE_INSTANCE_LOCK,
}

/**
 * 桥接连接状态（合并 [GatewayConnectionState] 的 6 子类 + 新增 [Blocked] / [Draining]）。
 *
 * ## 为什么新增两态
 * - [Blocked]：仅靠「未连接」无法区分「凭据错」与「被另一实例挡住」——用户看到「连接失败」
 *   却不知道该关哪个进程；单实例互斥需要 UI 落点。
 * - [Draining]：stop 需要「已停收新消息、在途任务还在跑」的中间态——否则用户点停止后
 *   UI 仍显示「运行中」，或在途 agent 进程被硬杀。
 *
 * [Disconnected] 保留 [GatewayErrorCode]（SPI 类型，非 SDK 类型 ⇒ 不违反隔离纪律）；
 * UI 侧要显示中文原因时用 `config/GatewayErrorMapping` 映射到 `BridgeErrorCode`（16 值）。
 */
sealed interface BridgeConnectionStatus {

    /** 未启动 / 已停止（无在途任务）。 */
    data object Idle : BridgeConnectionStatus

    /** 正在建连（含 `RegisterApp` 阻塞轮询期）。 */
    data object Connecting : BridgeConnectionStatus

    /** 长连接已建立。 */
    data class Connected(val botOpenId: String, val botName: String? = null) : BridgeConnectionStatus

    /** 断线重连中（SDK 内部退避）。 */
    data object Reconnecting : BridgeConnectionStatus

    /** 已断开（[code] 给出可重试性判据）。 */
    data class Disconnected(val code: GatewayErrorCode, val msg: String) : BridgeConnectionStatus

    /** 新增：被互斥挡住（原因见 [BlockedReason]），**不会自愈**，必须用户干预。 */
    data class Blocked(val reason: BlockedReason, val detail: String = "") : BridgeConnectionStatus

    /** 优雅排空中——已停止接收新消息，在途任务仍在跑（`stop()` 十步关闭的中间态）。 */
    data class Draining(val activeTasks: Int = 0) : BridgeConnectionStatus
}

/** 是否处于「可正常收发消息」的状态（UI 的启用/禁用判据）。 */
val BridgeConnectionStatus.isServing: Boolean
    get() = this is BridgeConnectionStatus.Connected

/**
 * 是否需要用户干预（不可自愈）。
 * 🔴 穷尽 `when` 无 `else`：新增第 8 个连接态时本文件编译失败。
 * [BridgeConnectionStatus.Disconnected] 中**仅** [GatewayErrorCode.ConnectFatal] 算——可重试码会自愈。
 */
val BridgeConnectionStatus.needsAttention: Boolean
    get() = when (this) {
        is BridgeConnectionStatus.Blocked -> true
        is BridgeConnectionStatus.Disconnected -> code == GatewayErrorCode.ConnectFatal
        BridgeConnectionStatus.Idle -> false
        BridgeConnectionStatus.Connecting -> false
        BridgeConnectionStatus.Reconnecting -> false
        is BridgeConnectionStatus.Draining -> false
        is BridgeConnectionStatus.Connected -> false
    }

/**
 * [GatewayConnectionState] → [BridgeConnectionStatus]（🔴 **穷尽 `when` 无 `else`**：
 * 网关新增第 7 个连接态时本文件编译失败，强制可见）。
 */
fun GatewayConnectionState.toBridgeConnectionStatus(): BridgeConnectionStatus = when (this) {
    GatewayConnectionState.Idle -> BridgeConnectionStatus.Idle
    GatewayConnectionState.Connecting -> BridgeConnectionStatus.Connecting
    is GatewayConnectionState.Connected -> BridgeConnectionStatus.Connected(botOpenId, botName)
    GatewayConnectionState.Reconnecting -> BridgeConnectionStatus.Reconnecting
    is GatewayConnectionState.Disconnected -> BridgeConnectionStatus.Disconnected(code, msg)
    // 网关的「被同 appId 的另一条长连接挡住」映射到新增的 Blocked（原因是 PEER_INSTANCE）
    GatewayConnectionState.BlockedByPeerInstance ->
        BridgeConnectionStatus.Blocked(BlockedReason.PEER_INSTANCE, "同一 appId 已有另一条长连接在线")
}

/**
 * 桥接整体状态的一次**不可变快照**（UI 状态页 / CLI `status` 子命令 / metrics 采样的唯一数据源）。
 *
 * 设计要点：本类是纯数据（全 `val`），由 [MetricsSampler] 以 1Hz 产出；UI 侧只做「读快照 → 渲染」，
 * 不持有任何 lib 对象引用 ⇒ 符合「lib 侧不涉 UI 类型」（jvmTarget 11）与「零动画渲染」纪律。
 *
 * 🔴 **单一可读指标**：[droppedTotal] 把三处丢弃面（管线计数、落盘通道、日志台环形覆盖）加总，
 * 任何非 0 都必须在 UI 上看得见。
 *
 * @param counters 管线 26 项计数（`PipelineCounters.CounterSnapshot`，与 PipelineCounters.kt KDoc 同源）
 * @param sinkDropped 审计/统计两条通道 DROP_OLDEST 溢出总数
 * @param logDropped 日志台环形缓冲覆盖总数
 */
data class BridgeStatusSnapshot(
    val running: Boolean,
    val connection: BridgeConnectionStatus,
    val confirm: ConfirmRuntimeStatus,
    val counters: PipelineCounters.CounterSnapshot,
    val sinkDropped: Long = 0L,
    val auditWritten: Long = 0L,
    val statsWritten: Long = 0L,
    val auditSinkDropped: Long = 0L,
    val logVersion: Long = 0L,
    val logSize: Int = 0,
    val logDropped: Long = 0L,
    val liveProcesses: Int = 0,
    val sampledAtMillis: Long = 0L,
    // 状态区补 3 个**有真实来源**的字段（旧 UI 的 appId / 最近异常 / 停止原因）。
    //    「重启次数」在新架构无自然计数点（重连由 SDK 内部管理、RESTART 字段变更由宿主 stop→start 决策，
    //    门面既不自动重启也不计数），故不伪造该字段。
    /** 当前生效配置的 `connection.appId`（来自 [com.jiaoay.lark.bridge.Bridge] 的 configRef，真实来源）。 */
    val appId: String = "",
    /** 最近一次异常/失败的人类可读原因（来自 start 拒绝 / 连接失败等状态迁移；成功后不清空，保留「最近异常」语义）。 */
    val lastError: String? = null,
    /** 当前不在运行的原因（来自 stop / start 拒绝的状态迁移；运行中为 null）。 */
    val stopReason: String? = null,
    // ---- 在线状态：连接时刻 + 最近信号时刻 ----
    /** 本次长连接的建立时刻（epoch millis；start 连上或 Reconnected 成功时重置）。null = 从未连上。 */
    val connectedSinceMillis: Long? = null,
    /** 最近一次网关生命周期信号（Reconnecting/Reconnected/Disconnected 等）到达时刻（epoch millis）。
     *  语义诚实：不是「心跳」——SDK 内部 90s ping 无我方可观测钩子（Tuning.kt 未声明、无信号），
     *  能展示的真实量只有「连接建立时刻」与「最近状态信号时刻」。null = 尚无信号。 */
    val lastSignalAtMillis: Long? = null,
) {
    /** 全部丢弃点之和（管线 + 落盘通道 + 落盘 sink + 日志台）。 */
    val droppedTotal: Long
        get() = counters.droppedTotal + sinkDropped + auditSinkDropped + logDropped

    /** 是否处于可服务状态。 */
    val serving: Boolean get() = connection.isServing

    /** 是否需要用户干预。 */
    val needsAttention: Boolean get() = connection.needsAttention
}

/**
 * 环境预检结果（「环境预检」的 lib 侧产物）。
 *
 * 复用 [com.jiaoay.lark.bridge.agent.AgentRunner.isAvailable]（后端可执行文件是否可解析）与 backendId，
 * 加上凭据是否就绪（appId 非空）。**零网络调用**：只读原子量与本地 PATH 探测，不建连、不触网。
 *
 * @param agentBackend 当前配置的 agent 后端 id（如 `claude`）。
 * @param agentAvailable 后端可执行文件是否可用（`AgentRunner.isAvailable`）。
 * @param connectionAppId 当前配置的 `connection.appId`（空 ⇒ 凭据未就绪）。
 * @param running 桥接是否正在运行。
 */
data class EnvironmentProbe(
    val agentBackend: String,
    val agentAvailable: Boolean,
    val connectionAppId: String,
    val running: Boolean,
) {
    /** 凭据是否就绪（appId 非空）。 */
    val credentialReady: Boolean get() = connectionAppId.isNotBlank()
}
