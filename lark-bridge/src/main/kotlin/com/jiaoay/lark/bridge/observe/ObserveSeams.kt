// ⑤ Observe 的 seam 定义：契约与通道（实现 JsonlSink / LogConsole / Metrics 同包）。
// 有界内存链路第 4 行「审计/统计 → 磁盘」= Channel<SinkRecord>×2，8192，DROP_OLDEST + droppedRecords。
// 有界内存链路第 5 行「事件 → UI 日志台」= LogBus 环形 ArrayDeque + version，1000，覆盖最旧 + droppedTotal。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * 一条待落盘的观测记录（🔒 字段与存量 `AuditEntry` / `SenderStatsStore` 的 JSONL schema 完全一致）。
 *
 * ## 为什么 schema 不能动
 * 用户 `~/.toolbelt/lark/audit.jsonl` 里**已有真实历史数据**；改字段名会让
 * 旧行与新行无法用同一套工具解析（`jq`/pandas 全废）。故 [Audit] 的 11 个字段名逐字沿用。
 */
sealed interface SinkRecord {

    /**
     * 审计记录（→ `audit.jsonl`）。🔒 **前 11 字段**逐字沿用旧 schema，另有「后加可空字段」：
     * `backend` / `kind` / `attachments`（产物回传）+ `rejectedArtifacts`（取证补齐）⇒ **磁盘共 13 字段**。
     * 后加字段一律可空并有默认值：旧格式行无此键 decode 为 null，双向兼容不破坏。
     *
     * @param ts 事件时刻（epoch millis；旧 schema 里就是 `ts`，不是 `timestamp`）。
     * @param prompt 用户原文摘要，**已在产出侧截断到 [PROMPT_SUMMARY_CHARS]=200**（审计不落全文，防 PII 膨胀）。
     * @param command 实际执行的 argv 摘要，**已截断到 [CMD_SUMMARY_CHARS]=60**。
     * @param status 🔒 `SUCCESS` / `FAILURE` / `SKIPPED` 的字面值（= `TaskStatus.name`，由 [statusLiteral] 映射）。
     * @param kind 🔴 `TaskKind.name` 的字面值（`COMMAND` / `SKILL` / `AGENT`）——**不是** `TaskKind.label`。
     *   实测依据：用户存量 `~/.toolbelt/lark/audit.jsonl` 里是 `"kind":"SKILL"` / `"kind":"AGENT"`。
     *   🔴 **可空**：`backend`/`kind` 是后加字段，存量旧行**无此键**，读回必须为 null。
     */
    data class Audit(
        val ts: Long,
        val chatId: String,
        val senderOpenId: String,
        val prompt: String,
        val command: String,
        val status: String,
        val errorCode: String?,
        val exitCode: Int?,
        val elapsedMillis: Long,
        val backend: String?,
        val kind: String? = null,
        /** 产物回传成功清单：`"<文件名> <- <原路径>"`；无产物为 null（不落键）。 */
        val attachments: List<String>? = null,
        /** 产物回传被拒清单：含路径与拒绝原因；无被拒为 null（不落键）。 */
        val rejectedArtifacts: List<String>? = null,
        /**
         * 发送者用户名：只读 `ctx.senderName`（Admission→Execute 边界已解析填充；
         * 拒绝路径 peek 降级可能空串）。空串落 null 不产空键——与 attachments 同款后加字段惯例，
         * 旧行 decode null 双向兼容。
         */
        val userName: String? = null,
        /**
         * 用户原消息 id：最近任务表情回显的查询锚点（`listReactions(messageId)`）。
         * 只读 `ctx.message.messageId`（[com.jiaoay.lark.gateway.InboundMessage] 非空 String，但归一路径
         * 可能空串）——空串落 null 不产空键（[userName] 同款后加字段惯例，旧行无此键 decode null）。
         */
        val userMessageId: String? = null,
        /**
         * 委托发起方 backend id（嵌套标记）：本条记录由该 backend 把请求委托给 [backend] 执行。
         * 非委托记录为 null；空串落 null 不产空键（[userName] 同款后加字段惯例，旧行无此键 decode null）。
         */
        val delegatedFromBackend: String? = null,
    ) : SinkRecord

    /**
     * 发送者统计（→ `sender_stats.jsonl`）。
     * @param type [TYPE_MESSAGE] / [TYPE_REJECTED_ALLOWLIST] / [TYPE_REJECTED_BLOCKLIST]（🔒 旧字面值，见下方常量说明）。
     * @param userName 🔴 落盘键名必须是 **`userName`**——存量 `sender_stats.jsonl` 里就是这个键，
     *   改名会让旧行读回后用户名全丢。
     * @param avatarUrl 发送者头像 URL：空串=无头像（无权限/未拉取）。
     *   后加字段带默认值——旧格式行 decode 为默认值，双向兼容（与 [Audit.userName] 同惯例）。
     */
    data class SenderStat(
        val ts: Long,
        val senderOpenId: String,
        val userName: String,
        val chatId: String,
        val type: String,
        val contentSummary: String,
        val avatarUrl: String = "",
    ) : SinkRecord

    companion object {
        /** 🔒 实测定稿文件名，不可改。 */
        const val AUDIT_FILE_NAME: String = "audit.jsonl"

        /** 🔒 实测定稿文件名，不可改。 */
        const val SENDER_STATS_FILE_NAME: String = "sender_stats.jsonl"

        /** 🔒 审计 prompt 摘要上限（实测定稿 200）。 */
        const val PROMPT_SUMMARY_CHARS: Int = 200

        /** 🔒 审计 argv 摘要上限（实测定稿 60）。 */
        const val CMD_SUMMARY_CHARS: Int = 60

        /** 🔒 统计 content 摘要上限（实测定稿 120）。 */
        const val CONTENT_SUMMARY_CHARS: Int = 120

        /**
         * 🔒 旧字面值 **`MESSAGE`**（大写）。
         * 实测依据：存量 `sender_stats.jsonl` 真实数据里全部是 `"type":"MESSAGE"`。
         */
        const val TYPE_MESSAGE: String = "MESSAGE"

        /**
         * 🔒 旧字面值 **`REJECTED_WHITELIST`**（大写 + 刻意保留旧拼写 whitelist）。
         * ⚠️ **刻意保留旧拼写**：这是**已落盘的历史数据**里的字面值，改成 `REJECTED_ALLOWLIST`
         * 会让用户的历史统计文件出现两种 type 取值、分组统计直接错。
         * 新配置域的 `access.mode` 用 `allowlist`（CDS §3.2.2），两者是**不同层**的字面值，不冲突。
         */
        const val TYPE_REJECTED_ALLOWLIST: String = "REJECTED_WHITELIST"

        /** 🔒 旧字面值 **`REJECTED_BLACKLIST`**（同上，刻意保留旧拼写）。 */
        const val TYPE_REJECTED_BLOCKLIST: String = "REJECTED_BLACKLIST"

        /**
         * [TaskStatus] → 审计 `status` 字面值（🔴 穷尽 `when` 无 `else`）。
         *
         * 🔒 **大写**（= `TaskStatus.name`）。实测依据：存量 `audit.jsonl` 里是
         * `"status":"SUCCESS"` / `"status":"FAILURE"`。小写会让存量行与新行分桶分裂
         * （宿主侧 `未知` 桶逻辑失效）。
         */
        fun statusLiteral(status: TaskStatus): String = when (status) {
            TaskStatus.SUCCESS -> "SUCCESS"
            TaskStatus.FAILURE -> "FAILURE"
            TaskStatus.SKIPPED -> "SKIPPED"
        }
    }
}

/**
 * ⑤ Observe 的两条落盘通道（有界内存链路第 4 行）。
 *
 * ## 为什么是两条独立通道而不是一条
 * 审计（`audit.jsonl`）与统计（`sender_stats.jsonl`）写的是**两个文件**。合成一条通道意味着
 * 消费者要按 `when(record)` 分支切文件句柄 ⇒ 要么频繁开关文件，要么长期持有两个句柄却又串行写
 * （一个文件的慢 IO 会拖住另一个）。两条通道 + 两个消费者协程（`BridgeRuntime.auditSinkScope` /
 * `statsSinkScope`，都跑在 `sink` 单写者 dispatcher 上）⇒ 各自有序、互不阻塞。
 *
 * ## 为什么容量是 8192 且 DROP_OLDEST
 * - 审计**可以丢**（丢一条审计不影响业务正确性），但**绝不可以阻塞** intake/task 协程；
 * - 8192 条 ≈ 单条 300B ⇒ 约 2.5MB 常驻上限，可接受；
 * - 🔴 DROP_OLDEST 而非 SUSPEND：SUSPEND 会让磁盘卡顿时反压到任务协程，用户的 agent 回复会因为
 *   「审计写不动」而卡住——这是错误的优先级。
 * - 🔴 **丢弃必须可观测** ⇒ [auditDropped] / [statsDropped] + `droppedRecords` 指标 + WARN。
 *
 * 计数手法沿用 gateway-contract.md §15.2 M-16（DROP_OLDEST 下 `trySend` 恒成功，用 offered/consumed 差值推断）。
 *
 * @param capacity 单条通道容量（方案表逐字：8192）。
 */
class SinkChannels(
    capacity: Int = CAPACITY,
    private val log: BridgeLog = BridgeLog.Silent,
) {

    /** 单条通道容量。 */
    val capacity: Int = capacity.coerceAtLeast(1)

    private val auditChannel = Channel<SinkRecord>(this.capacity, BufferOverflow.DROP_OLDEST)
    private val statsChannel = Channel<SinkRecord>(this.capacity, BufferOverflow.DROP_OLDEST)

    private val auditOffered = AtomicLong(0)
    private val auditConsumed = AtomicLong(0)
    private val auditDroppedTotal = AtomicLong(0)
    private val statsOffered = AtomicLong(0)
    private val statsConsumed = AtomicLong(0)
    private val statsDroppedTotal = AtomicLong(0)

    /** 审计通道累计丢弃条数。 */
    val auditDropped: Long get() = auditDroppedTotal.get()

    /** 统计通道累计丢弃条数。 */
    val statsDropped: Long get() = statsDroppedTotal.get()

    /** 两条通道合计丢弃条数（方案表里的 `droppedRecords` 指标）。 */
    val droppedRecords: Long get() = auditDroppedTotal.get() + statsDroppedTotal.get()

    /** 审计通道累计投递条数。 */
    val auditOfferedCount: Long get() = auditOffered.get()

    /** 统计通道累计投递条数。 */
    val statsOfferedCount: Long get() = statsOffered.get()

    /** 审计通道当前在途条数。 */
    val auditPending: Long get() = (auditOffered.get() - auditConsumed.get() - auditDroppedTotal.get()).coerceAtLeast(0L)

    /** 统计通道当前在途条数。 */
    val statsPending: Long get() = (statsOffered.get() - statsConsumed.get() - statsDroppedTotal.get()).coerceAtLeast(0L)

    /** 审计通道只读视图（T8 的 `JsonlSink` 消费者从这里 receive）。 */
    val audit: Channel<SinkRecord> get() = auditChannel

    /** 统计通道只读视图。 */
    val stats: Channel<SinkRecord> get() = statsChannel

    /**
     * 投递一条审计记录。🔴 **O(1) 非挂起恒不阻塞**（可在 task 协程、甚至 intake 协程里直接调）。
     * @return true ⇒ 未挤掉旧记录；false ⇒ 本次触发 DROP_OLDEST（已计数 + WARN）。
     */
    fun offerAudit(record: SinkRecord): Boolean =
        offer(auditChannel, record, auditOffered, auditConsumed, auditDroppedTotal, AUDIT_WHERE)

    /** 投递一条统计记录（语义同 [offerAudit]）。 */
    fun offerStats(record: SinkRecord): Boolean =
        offer(statsChannel, record, statsOffered, statsConsumed, statsDroppedTotal, STATS_WHERE)

    /** 消费者取一条审计记录；通道关闭且排空后返回 null（消费者协程据此正常收尾）。 */
    suspend fun receiveAudit(): SinkRecord? =
        auditChannel.receiveCatching().getOrNull()?.also { auditConsumed.incrementAndGet() }

    /** 消费者取一条统计记录。 */
    suspend fun receiveStats(): SinkRecord? =
        statsChannel.receiveCatching().getOrNull()?.also { statsConsumed.incrementAndGet() }

    /** 关闭两条通道（幂等）：之后消费者排空即收尾。 */
    fun close() {
        auditChannel.close()
        statsChannel.close()
    }

    private fun offer(
        channel: Channel<SinkRecord>,
        record: SinkRecord,
        offered: AtomicLong,
        consumed: AtomicLong,
        dropped: AtomicLong,
        where: String,
    ): Boolean {
        val before = offered.getAndIncrement()
        val overflow = before - consumed.get() >= capacity
        channel.trySend(record)
        if (overflow) {
            val total = dropped.incrementAndGet()
            log.warn("观测记录通道溢出，已丢弃最旧记录：where=$where capacity=$capacity droppedTotal=$total")
            return false
        }
        return true
    }

    companion object {
        /** 单条通道容量（方案「有界内存链路 8 行表」第 4 行逐字：8192）。 */
        const val CAPACITY: Int = 8192

        /** `GatewaySignal.Dropped.where` 的字面值（审计通道）。 */
        const val AUDIT_WHERE: String = "audit-sink"

        /** `GatewaySignal.Dropped.where` 的字面值（统计通道）。 */
        const val STATS_WHERE: String = "stats-sink"
    }
}

/** 日志台的一行（`LogBus` 的元素；UI 侧零动画渲染）。 */
data class LogLine(
    val seq: Long,
    val level: String,
    val tag: String,
    val message: String,
    val atMillis: Long,
)

/**
 * 事件 → UI 日志台的环形缓冲 seam（有界内存链路第 5 行）。
 *
 * ## 本轮只定接口，实现归 T8 observe 组
 * 契约要点（T8 实现时**必须**逐条满足，否则有界链路第 5 行不成立）：
 * 1. **环形 `ArrayDeque` + version**：容量 [capacity]=1000，满了**覆盖最旧**（不是丢最新——用户要看的是最近的事），
 *    每次覆盖累加 [droppedTotal]（🔴 覆盖也是丢弃，必须可观测）；
 * 2. **version 单调递增**：Compose 侧用 version 做「有新行」的判据，避免每行都触发重组
 *    （UI 的 `derivedStateOf` 依赖它）；
 * 3. 🔴 **`publish` 必须 O(1) 非挂起**：它在 task 协程与 intake 协程里被调用，任何阻塞都会拖垮管线；
 * 4. 🔴 **零锁**：实现侧用单写者（sink/metrics 协程）+ `AtomicReference<List<LogLine>>` 快照发布，
 *    或 `AtomicLong` version + 环形数组；**不得**用监视器锁/协程互斥量（CDS §14.3 纪律的延伸）；
 * 5. [snapshot] 返回**不可变**副本，供 Compose 直接读（避免 UI 侧读到正在被覆盖的行）。
 *
 * 与 VM 的分工：`LogBus` 是 lib 侧的**数据面**；「VM 日志台 → Compose」的链路归 feature 侧
 * （feature 侧 VM 订阅 [snapshot]/version 并映射成 Compose state），本接口不涉 UI 类型（jvmTarget 11）。
 */
interface LogBus {

    /** 环形容量（默认 1000，来自 `observability.logConsole.capacity`）。 */
    val capacity: Int

    /** 单调递增版本号（每次 [publish] +1；Compose 侧据此判断「有新行」）。 */
    val version: Long

    /** 累计被覆盖（丢弃）的行数。 */
    val droppedTotal: Long

    /** 累计发布行数。 */
    val publishedTotal: Long

    /** 当前缓冲行数（`<= capacity`）。 */
    val size: Int

    /** 发布一行。🔴 O(1) 非挂起、恒不阻塞、恒不抛。 */
    fun publish(line: LogLine)

    /** 便捷发布（内部造 [LogLine]，seq 由实现自增）。 */
    fun publish(level: String, tag: String, message: String)

    /** 当前缓冲的不可变快照（按 seq 升序；UI 直接渲染）。 */
    fun snapshot(): List<LogLine>

    /**
     * 快照版本号：轮询侧的「有无新行」短路信号——
     * 与上次读取相同时可跳过 [snapshot] 的全量收集 + 排序（空闲期省 CPU）。
     */
    fun snapshotVersion(): Long

    /** 清空（切换 appId / stop 时；幂等。version 与 droppedTotal **不**归零，保证 UI 能察觉「清空过」）。 */
    fun clear()
}
