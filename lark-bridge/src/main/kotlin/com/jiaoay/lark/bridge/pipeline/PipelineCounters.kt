// 五阶段管线的统一计数面（🔴 所有丢弃/拒绝/超时点都必须有计数器且可读）。
// 🔴 本文件属 intake 路径（计数在 intake 单协程内累加）：全部 java.util.concurrent.atomic CAS，零监视器锁。
package com.jiaoay.lark.bridge.pipeline

import java.util.concurrent.atomic.AtomicLong

/**
 * 管线计数面（「丢弃必须可观测」的唯一落点）。
 *
 * ## 为什么是一个实例而不是散落各处的 AtomicLong
 * 各阶段（[InboundQueue] / [StaleFilter] / [MessageDeduper] / [AccessControl] / [PolicyGate] /
 * [PipelineRouter] / [Admission]）都自带自己的计数器，但它们**分散在不同对象上**，metrics 协程要
 * 逐个持有引用才能采样。本类做**汇聚**：各阶段在自己的丢弃点顺手调一次 `record*`（O(1) CAS），
 * metrics 协程只读 [snapshot] 一个方法即可拿到全量，UI 也只需一个数据源。
 * 两处计数刻意并存（阶段自身的 + 本类的）：阶段自身计数供**单元测试**精确断言，
 *    本类计数供**运行时观测**（1Hz 采样）；两者都无锁，重复计数的代价是 1 次 CAS，可忽略。
 *
 * ## 计数器一览（与「有界内存链路 8 行表」+ 五阶段一一对应）
 * | 阶段 | 计数器 | 触发点 |
 * |---|---|---|
 * | ① Intake | [intakeQueueDropped] | `Channel(512, DROP_OLDEST)` 溢出 |
 * | ① Intake | [staleDropped] | `createTime` 早于窗口 |
 * | ① Intake | [duplicatesDropped] | messageId 在 LRU/TTL 窗口内重复 |
 * | ① Intake | [blankIdsDropped] | messageId 为空（无幂等键） |
 * | ① Intake | [accessRejected] | sender 未命中 allowlist / 命中 blocklist |
 * | ① Intake | [policyRejected] | 群@门控拒绝 |
 * | ① Intake | [unmatched] | 未命中任何 handler（走 runUnhandled 兜底） |
 * | ② Admission | [queueTimeouts] | 等许可超 `queueWaitSeconds` |
 * | ② Admission | [chatBusy] | 同 chat 在途超 `chatQueueLimit` |
 * | ② Admission | [busyRepliesSuppressed] | 繁忙回复被 cooldown 合批抑制 |
 * | ② Admission | [urgentCancellations] | URGENT 指令取消的在途任务数 |
 * | ③ Execute | [totalTimeouts] / [idleTimeouts] | 总超时 / 空闲看门狗 |
 * | ③ Execute | [cancelled] | 外部取消（停止指令 / stop()） |
 * | ③ Execute | [handlerErrors] | handler 抛未预期异常 |
 * | ③/④ | [completed] / [failed] / [skipped] | 终态分布 |
 * | ④ Reply | [replyFailures] | 出站 SendResult.ok == false |
 * | ⑤ Observe | [sinkDropped] | `Channel(8192, DROP_OLDEST)` 溢出（审计/统计两条通道合计） |
 */
class PipelineCounters {

    private val receivedRef = AtomicLong(0)
    private val queueDroppedRef = AtomicLong(0)
    private val staleRef = AtomicLong(0)
    private val duplicatesRef = AtomicLong(0)
    private val blankIdsRef = AtomicLong(0)
    private val accessRef = AtomicLong(0)
    private val policyRef = AtomicLong(0)
    private val unmatchedRef = AtomicLong(0)
    private val submittedRef = AtomicLong(0)
    private val queueTimeoutRef = AtomicLong(0)
    private val busyRef = AtomicLong(0)
    private val busySuppressedRef = AtomicLong(0)
    private val urgentCancelledRef = AtomicLong(0)
    private val totalTimeoutRef = AtomicLong(0)
    private val idleTimeoutRef = AtomicLong(0)
    private val cancelledRef = AtomicLong(0)
    private val handlerErrorRef = AtomicLong(0)
    private val completedTotalRef = AtomicLong(0)
    private val failedTotalRef = AtomicLong(0)
    private val skippedTotalRef = AtomicLong(0)
    private val replyFailureRef = AtomicLong(0)
    private val sinkDroppedTotalRef = AtomicLong(0)

    // —— 引用流：文件登记 / 引用解析 / 落盘 / 配额逐出 ——
    private val filesRegisteredRef = AtomicLong(0)
    private val filesReferencedRef = AtomicLong(0)
    private val downloadsStoredRef = AtomicLong(0)
    private val quotaEvictionsRef = AtomicLong(0)

    /** intake 累计收到的消息条数（= `InboundQueue.offeredCount`，此处独立计一份便于单点断言）。 */
    val intakeReceived: Long get() = receivedRef.get()

    /** ① 入站队列 DROP_OLDEST 丢弃数（有界内存链路第 1 行）。 */
    val intakeQueueDropped: Long get() = queueDroppedRef.get()

    /** ① 陈旧消息丢弃数。 */
    val staleDropped: Long get() = staleRef.get()

    /** ① 重复消息丢弃数（key = messageId）。 */
    val duplicatesDropped: Long get() = duplicatesRef.get()

    /** ① 空 messageId 丢弃数（NF-A1 ④）。 */
    val blankIdsDropped: Long get() = blankIdsRef.get()

    /** ① sender 准入拒绝数（allowlist 未命中 + blocklist 命中之和）。 */
    val accessRejected: Long get() = accessRef.get()

    /** ① 策略门控拒绝数（群@门控）。 */
    val policyRejected: Long get() = policyRef.get()

    /** ① 未命中任何 handler 的消息数（走 `runUnhandled` 兜底；App 形态未命中 TEXT 恒进调度 agent，本计数应趋零）。 */
    val unmatched: Long get() = unmatchedRef.get()

    /** ② 提交给 Admission 的任务数。 */
    val submitted: Long get() = submittedRef.get()

    /** ② 排队等许可超时数（🔒 `task_queue_timeout`）。 */
    val queueTimeouts: Long get() = queueTimeoutRef.get()

    /** ② 同 chat 在途超限数（🔒 `agent_busy`）。 */
    val chatBusy: Long get() = busyRef.get()

    /** ② 繁忙回复被合批抑制的次数（抑制本身也必须可观测，否则用户以为「机器人没反应」）。 */
    val busyRepliesSuppressed: Long get() = busySuppressedRef.get()

    /** ② URGENT（停止指令）取消的在途任务累计数。 */
    val urgentCancellations: Long get() = urgentCancelledRef.get()

    /** ③ 总超时次数（🔒 `task_timeout`）。 */
    val totalTimeouts: Long get() = totalTimeoutRef.get()

    /** ③ 空闲看门狗触发次数。 */
    val idleTimeouts: Long get() = idleTimeoutRef.get()

    /** ③ 外部取消次数（停止指令 / stop()）。 */
    val cancelledTasks: Long get() = cancelledRef.get()

    /** ③ handler 抛未预期异常次数（异常不中断服务）。 */
    val handlerErrors: Long get() = handlerErrorRef.get()

    /** ④ 终态 SUCCESS 计数。 */
    val completed: Long get() = completedTotalRef.get()

    /** ④ 终态 FAILURE 计数。 */
    val failed: Long get() = failedTotalRef.get()

    /** ④ 终态 SKIPPED 计数（不计入 metrics 的那类，见 [HandlerOutcome.countsForMetrics]）。 */
    val skipped: Long get() = skippedTotalRef.get()

    /** ④ 出站失败次数（`SendResult.ok == false`）。 */
    val replyFailures: Long get() = replyFailureRef.get()

    /** ⑤ 审计/统计通道 DROP_OLDEST 丢弃数（有界内存链路第 4 行，两条通道合计）。 */
    val sinkDropped: Long get() = sinkDroppedTotalRef.get()

    /** 文件登记条数（FileHandler.handle 收到 FILE 消息即登记）。 */
    val filesRegistered: Long get() = filesRegisteredRef.get()

    /** 引用命中并开始解析的次数（replyToMessageId 查表命中）。 */
    val filesReferenced: Long get() = filesReferencedRef.get()

    /** 引用文件成功落盘次数（下载 + 预处理完成入账本）。 */
    val downloadsStored: Long get() = downloadsStoredRef.get()

    /** 配额逐出的 per-message 目录数（每用户 + 全局合计；丢弃必须可观测）。 */
    val quotaEvictions: Long get() = quotaEvictionsRef.get()

    fun recordReceived() {
        receivedRef.incrementAndGet()
    }

    fun recordIntakeQueueDropped() {
        queueDroppedRef.incrementAndGet()
    }

    fun recordStaleDropped() {
        staleRef.incrementAndGet()
    }

    fun recordDuplicateDropped() {
        duplicatesRef.incrementAndGet()
    }

    fun recordBlankIdDropped() {
        blankIdsRef.incrementAndGet()
    }

    fun recordAccessRejected() {
        accessRef.incrementAndGet()
    }

    fun recordPolicyRejected() {
        policyRef.incrementAndGet()
    }

    fun recordUnmatched() {
        unmatchedRef.incrementAndGet()
    }

    fun recordSubmitted() {
        submittedRef.incrementAndGet()
    }

    fun recordQueueTimeout() {
        queueTimeoutRef.incrementAndGet()
    }

    fun recordChatBusy() {
        busyRef.incrementAndGet()
    }

    fun recordBusyReplySuppressed() {
        busySuppressedRef.incrementAndGet()
    }

    fun recordUrgentCancellations(count: Int) {
        if (count > 0) urgentCancelledRef.addAndGet(count.toLong())
    }

    fun recordTotalTimeout() {
        totalTimeoutRef.incrementAndGet()
    }

    fun recordIdleTimeout() {
        idleTimeoutRef.incrementAndGet()
    }

    fun recordCancelled() {
        cancelledRef.incrementAndGet()
    }

    fun recordHandlerError() {
        handlerErrorRef.incrementAndGet()
    }

    fun recordReplyFailure() {
        replyFailureRef.incrementAndGet()
    }

    fun recordSinkDropped(count: Long = 1L) {
        sinkDroppedTotalRef.addAndGet(count)
    }

    fun recordFileRegistered() {
        filesRegisteredRef.incrementAndGet()
    }

    fun recordFileReferenced() {
        filesReferencedRef.incrementAndGet()
    }

    fun recordDownloadStored() {
        downloadsStoredRef.incrementAndGet()
    }

    fun recordQuotaEviction() {
        quotaEvictionsRef.incrementAndGet()
    }

    /** 按终态归档（④ Reply 完成后调用一次）。 */
    fun recordOutcome(outcome: HandlerOutcome) {
        when (outcome.status) {
            TaskStatus.SUCCESS -> completedTotalRef.incrementAndGet()
            TaskStatus.FAILURE -> failedTotalRef.incrementAndGet()
            TaskStatus.SKIPPED -> skippedTotalRef.incrementAndGet()
        }
    }

    /** 全量只读投影（metrics 1Hz 采样 / UI 状态页 / `CountersTest` 断言）。 */
    fun snapshot(): CounterSnapshot = CounterSnapshot(
        intakeReceived = receivedRef.get(),
        intakeQueueDropped = queueDroppedRef.get(),
        staleDropped = staleRef.get(),
        duplicatesDropped = duplicatesRef.get(),
        blankIdsDropped = blankIdsRef.get(),
        accessRejected = accessRef.get(),
        policyRejected = policyRef.get(),
        unmatched = unmatchedRef.get(),
        submitted = submittedRef.get(),
        queueTimeouts = queueTimeoutRef.get(),
        chatBusy = busyRef.get(),
        busyRepliesSuppressed = busySuppressedRef.get(),
        urgentCancellations = urgentCancelledRef.get(),
        totalTimeouts = totalTimeoutRef.get(),
        idleTimeouts = idleTimeoutRef.get(),
        cancelledTasks = cancelledRef.get(),
        handlerErrors = handlerErrorRef.get(),
        completed = completedTotalRef.get(),
        failed = failedTotalRef.get(),
        skipped = skippedTotalRef.get(),
        replyFailures = replyFailureRef.get(),
        sinkDropped = sinkDroppedTotalRef.get(),
        filesRegistered = filesRegisteredRef.get(),
        filesReferenced = filesReferencedRef.get(),
        downloadsStored = downloadsStoredRef.get(),
        quotaEvictions = quotaEvictionsRef.get(),
    )

    /** 归零（切换 appId / 重新 start 时；幂等）。 */
    fun reset() {
        listOf(
            receivedRef, queueDroppedRef, staleRef, duplicatesRef, blankIdsRef, accessRef, policyRef, unmatchedRef, submittedRef,
            queueTimeoutRef, busyRef, busySuppressedRef, urgentCancelledRef, totalTimeoutRef, idleTimeoutRef, cancelledRef,
            handlerErrorRef, completedTotalRef, failedTotalRef, skippedTotalRef, replyFailureRef, sinkDroppedTotalRef,
            filesRegisteredRef, filesReferencedRef, downloadsStoredRef, quotaEvictionsRef,
        ).forEach { it.set(0L) }
    }

    override fun toString(): String = snapshot().toString()

    /** 计数面的一次不可变投影（26 项；`CountersTest` 逐项断言）。 */
    data class CounterSnapshot(
        val intakeReceived: Long,
        val intakeQueueDropped: Long,
        val staleDropped: Long,
        val duplicatesDropped: Long,
        val blankIdsDropped: Long,
        val accessRejected: Long,
        val policyRejected: Long,
        val unmatched: Long,
        val submitted: Long,
        val queueTimeouts: Long,
        val chatBusy: Long,
        val busyRepliesSuppressed: Long,
        val urgentCancellations: Long,
        val totalTimeouts: Long,
        val idleTimeouts: Long,
        val cancelledTasks: Long,
        val handlerErrors: Long,
        val completed: Long,
        val failed: Long,
        val skipped: Long,
        val replyFailures: Long,
        val sinkDropped: Long,
        val filesRegistered: Long,
        val filesReferenced: Long,
        val downloadsStored: Long,
        val quotaEvictions: Long,
    ) {
        /** 全部丢弃点之和（单一可读指标：任何非 0 都必须在 UI 上看得见）。 */
        val droppedTotal: Long
            get() = intakeQueueDropped + staleDropped + duplicatesDropped + blankIdsDropped +
                accessRejected + policyRejected + queueTimeouts + chatBusy + busyRepliesSuppressed + sinkDropped
    }
}
