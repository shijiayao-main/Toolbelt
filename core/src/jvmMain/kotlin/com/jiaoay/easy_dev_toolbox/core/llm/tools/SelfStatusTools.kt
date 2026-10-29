package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditAggregator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

/**
 * 桥接自观测工具：`self.status`——桥接自身运行健康的只读聚合快照（自观测域）。
 *
 * ## 数据面（全部进程内只读，零文件 I/O 零子进程）
 * 工具本体不做任何文件/进程访问——四块数据全部经 [SelfStatusSource] 缝注入（App 装配侧
 * 适配桥接内部态，与状态页 BridgeStatusPanel 同一数据面 BridgeStatusSnapshot，不是读 UI）：
 * ①连接态 + 重连计数 + 最近异常时间；②流水线 12 行计数（与状态页「流水线计数」同款行集）；
 * ③配置校验进程内摘要（ConfigValidator.validate 纯函数——CLI check 同源）；
 * ④sys-audit 按日聚合计数（[SysAuditAggregator] 折叠产物——聚合由注入源完成，工具只渲染）。
 *
 * ## 审计拒外发纪律（不变量，违反即审计明细出围栏）
 * 回执**只含聚合计数**，绝不包含审计记录原文/命令内容/输出摘要/触发者——聚合数据类
 * [SysAuditAggregator.DailyAggregate] 结构上没有原文字段。审计文件本体在宿主机密根整树
 * 拒发射程内，本工具不读审计文件、不产生任何可外发的明细面。
 *
 * ## 装配纪律（dev.* 同款配方）
 * 目录注册不入任何 base names；装配门控三条件（缺一不注册，fail-closed）：配置域
 * `agent.selfStatus.enabled` 总开关（HOT 现读）+ 状态源缝在位 + 桥接配置接线；桌面链
 * 默认不装配（config 槽缺省 null ⇒ 工厂返 null）。自观测是零授权面（只读聚合、无文件
 * 无设备无进程），故无会话门控条件——与 sys/dev 的信任用户域准入不同源，不虚设。
 * 工具层兜底 fail-closed：[SelfStatusTool] 以 null 源直造即恒 DENIED（装配缺陷拒绝）。
 */
object SelfStatusTools {

    const val STATUS_NAME = "self.status"

    /** 家族名集——消费方 names 选择集单源引用（DevTools.DEV_NAMES 先例）；单件域 */
    val STATUS_NAMES: Set<String> = setOf(STATUS_NAME)

    /** 回执长度结构性上界（字符）——真门禁，与 dev./sys. 两域同档 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** 流水线计数行集（与状态页「流水线计数」小节同款 12 行中的 10 个计数器行） */
    data class CounterRows(
        val intakeReceived: Long,
        val submitted: Long,
        val completed: Long,
        val failed: Long,
        val accessRejected: Long,
        val unmatched: Long,
        val policyRejected: Long,
        val duplicatesDropped: Long,
        val queueTimeouts: Long,
        val droppedTotal: Long,
    )

    /**
     * 桥接内部态的一次只读聚合快照（纯数据；时刻均为 epoch millis，渲染在工具侧）。
     *
     * @param running 桥接是否在运行（装配但停止 = false，是正常答案非错误）
     * @param connectionState 连接态人话（装配侧单源渲染；状态页 label 同款语义）
     * @param reconnectCount 本次进程内观察到的重连次数（装配侧派生计数——快照域无自然
     *   计数点，计数语义与窗口见装配侧适配器 KDoc）
     * @param lastError 最近一次异常/失败原因（状态域「最近异常」同款语义：成功后不清空）
     * @param lastErrorAtMillis 最近异常的观察时刻（装配侧在异常文本变化时打点——异常发生
     *   早于采集回路挂载时，此值为首次观察到它的时刻，如实近似）
     * @param counters 流水线计数 10 行
     * @param liveProcesses 存活子进程数（状态页「接收」小节同行）
     * @param logSize 日志台环形缓冲当前行数（状态页「尾行」）
     * @param configIssues 配置校验问题（fieldId + message；不含字段值——凭据值永不进回执）
     * @param audit sys-audit 当日聚合；null = 聚合暂不可得（读失败，如实报不编零）
     */
    data class Snapshot(
        val running: Boolean,
        val connectionState: String,
        val connectedSinceMillis: Long?,
        val lastSignalAtMillis: Long?,
        val sampledAtMillis: Long,
        val reconnectCount: Long,
        val lastError: String?,
        val lastErrorAtMillis: Long?,
        val counters: CounterRows,
        val liveProcesses: Int,
        val logSize: Int,
        val configIssues: List<ConfigIssueLine>,
        val audit: SysAuditAggregator.DailyAggregate?,
    )

    /** 一条配置校验问题（fieldId + 人话；**不含字段值**——校验消息本身不含凭据，字段值永不进回执） */
    data class ConfigIssueLine(val fieldId: String, val message: String)

    /**
     * 状态源缝：每次调用现拉一帧桥接内部态（App 装配侧适配 BridgeStatusSnapshot +
     * ConfigValidator + sys-audit 聚合；测试注入 fake 免真桥接）。
     *
     * @return null = 桥接未装配（状态数据面不存在——CLI 形态/桥接页从未打开），
     *   工具如实报「桥接未装配」；装配但未运行不是 null（running=false 是正常答案）。
     */
    fun interface SelfStatusSource {
        suspend fun snapshot(): Snapshot?
    }

    // ── 工具：self.status ──

    /** 只读自观测工具：状态源现拉一帧 → 四段聚合回执（capAt 收口 ≤8K） */
    class SelfStatusTool(
        sink: ToolResultSink = ToolResultSink.NOOP,
        /** 状态源缝（null = 未接线 ⇒ 恒 DENIED，fail-closed——装配缺陷兜底拒绝） */
        private val source: SelfStatusSource? = null,
    ) : StructuredSimpleTool<SelfStatusTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = STATUS_NAME,
        description = "Read this bridge bot's own runtime health (READ-ONLY aggregated snapshot; " +
            "zero file I/O, zero subprocess). Returns: connection state + reconnect count + last " +
            "error, the 12 pipeline counters, an in-process config validation summary (same " +
            "validator as the CLI check command), and TODAY's sys-audit aggregate counts (counts " +
            "only — never any audit record content). Use it when the user asks whether the bot or " +
            "the bridge is working, or when you need to confirm the outbound channel is alive.",
    ) {

        @Serializable
        class Args

        override suspend fun executeTyped(args: Args): ToolResult {
            val src = source ?: return ToolResult.Err(
                ToolResult.ErrCode.DENIED,
                "self.status 状态源未装配（fail-closed 拒绝）——本链不支持自观测。",
            )
            val snapshot = src.snapshot() ?: return ToolResult.Err(
                ToolResult.ErrCode.FAILED,
                "桥接未装配，自观测数据面不存在——请提示用户先打开飞书桥接页完成装配并启动。",
            )
            return render(snapshot)
        }

        /** 四段聚合回执：连接 / 流水线计数 / 配置校验 / 审计聚合（渲染面恒零原文，只计数） */
        private fun render(s: Snapshot): ToolResult {
            // sampledAtMillis=0 = 桥接未运行（metrics 束缺席的冻结帧）——不渲染 epoch 0 时刻
            val sampleTag = if (s.sampledAtMillis > 0) "采样 ${formatTime(s.sampledAtMillis)}" else "未运行，无实时采样"
            val body = buildString {
                appendLine("桥接自观测（只读聚合快照；$sampleTag）：")
                appendLine("[连接]")
                appendLine("running=${s.running}")
                appendLine("连接态=${s.connectionState}（重连 ${s.reconnectCount} 次）")
                s.connectedSinceMillis?.let {
                    appendLine("连接自=${formatTime(it)}（已 ${formatUptime(s.sampledAtMillis - it)}）")
                }
                s.lastSignalAtMillis?.let { appendLine("最近信号=${formatTime(it)}") }
                appendLine(
                    s.lastError?.let { err ->
                        val at = s.lastErrorAtMillis?.let { t -> "（记于 ${formatTime(t)}）" } ?: ""
                        "最近异常=$err$at"
                    } ?: "最近异常=无",
                )
                appendLine("存活进程=${s.liveProcesses}")
                appendLine()
                appendLine("[流水线计数（与桥接状态页同款行集）]")
                appendLine("接收消息=${s.counters.intakeReceived}")
                appendLine("已提交任务=${s.counters.submitted}")
                appendLine("完成=${s.counters.completed}")
                appendLine("失败=${s.counters.failed}")
                appendLine("访问拒绝=${s.counters.accessRejected}")
                appendLine("未匹配消息=${s.counters.unmatched}")
                appendLine("策略拒绝=${s.counters.policyRejected}")
                appendLine("去重丢弃=${s.counters.duplicatesDropped}")
                appendLine("队列超时=${s.counters.queueTimeouts}")
                appendLine("丢弃合计=${s.counters.droppedTotal}")
                appendLine("日志台行数=${s.logSize}")
                appendLine()
                appendLine("[配置校验（进程内 validate，与 CLI check 同源；只报问题不报字段值）]")
                if (s.configIssues.isEmpty()) {
                    appendLine("通过（0 处问题）")
                } else {
                    appendLine("未过（${s.configIssues.size} 处）：")
                    s.configIssues.forEach { appendLine("- ${it.fieldId}: ${it.message}") }
                }
                appendLine()
                appendLine("[sys-audit 聚合（只计数，绝不含审计记录原文/命令内容）]")
                appendLine(renderAudit(s.audit))
            }
            val capped = capAt(body.trim())
            return ToolResult.Ok(
                summary = capped,
                data = ToolPayload.SelfStatusPayload(
                    running = s.running,
                    connectionState = s.connectionState,
                    reconnectCount = s.reconnectCount,
                    completed = s.counters.completed,
                    failed = s.counters.failed,
                    configIssueCount = s.configIssues.size,
                    auditTotal = s.audit?.total ?: -1,
                    auditDenied = s.audit?.denied ?: -1,
                    truncated = capped != body.trim(),
                ),
            )
        }

        /** 审计聚合行：只计数——工具名聚合计数 + 拒绝次数（工具名非敏感面；无原文） */
        private fun renderAudit(audit: SysAuditAggregator.DailyAggregate?): String = when (audit) {
            null -> "今日聚合暂不可得（审计读取失败——如实告知用户，禁止编造计数）"
            else ->
                if (audit.total == 0) {
                    "今日无审计记录（0 条）"
                } else {
                    val tools = audit.byTool.entries.sortedByDescending { it.value }
                        .joinToString(" / ") { "${it.key}=${it.value}" }
                    "今日共 ${audit.total} 条：$tools；拒绝 ${audit.denied} 次"
                }
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `self.status` 目录描述符。uiHint = null：纯 agent 工具（自观测链自用），不进命令面板。
     * risk = READ_ONLY（零文件 I/O 零子进程零授权面）。装配门控 = config.selfStatusSource
     * 非 null 才注册（工厂返 null，fail-closed）；配置域总开关与桥接接线三条件核对在
     * 桥接装配侧（BridgeToolRegistry），桌面链缺省不装配。
     */
    val SELF_STATUS: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = STATUS_NAME,
        description = "Read the bridge bot's own runtime health (read-only aggregated snapshot: " +
            "connection state, reconnect count, pipeline counters, config validation summary, " +
            "today's sys-audit aggregate counts — counts only, never record contents)",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        factory = { _, sink, config ->
            config.selfStatusSource?.let { SelfStatusTool(sink, source = it) }
        },
    )

    // ── 渲染辅助（与状态页同款口径） ──

    /** epoch millis → HH:mm:ss（系统时区；状态页「连接自」同款格式） */
    internal fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    /** millis → `1h 23m` / `2d 3h`（连接时长人工可读格式；负值按 0 处理——状态页同款） */
    internal fun formatUptime(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }
}
