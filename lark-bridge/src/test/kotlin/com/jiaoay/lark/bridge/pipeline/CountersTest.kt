// M4-T7 测试清单 ⑦：CountersTest（承接旧 engine/Counters*，🔴 D18「所有丢弃点都必须可观测」）。
// 另含有界内存链路第 4 行「审计/统计 → 磁盘」的 SinkChannels seam（8192 / DROP_OLDEST / droppedRecords）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.observe.SinkChannels
import com.jiaoay.lark.bridge.observe.SinkRecord
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.bridge.config.SystemRoute
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** [PipelineCounters] 的 22 个计数器 + [PipelineCounters.CounterSnapshot] + [SinkChannels] 丢弃面。 */
class CountersTest {

    private fun audit(ts: Long = 1L) = SinkRecord.Audit(
        ts = ts,
        chatId = "oc_a",
        senderOpenId = "ou_alice",
        prompt = "ping",
        command = "echo",
        status = "SUCCESS",
        errorCode = null,
        exitCode = 0,
        elapsedMillis = 12L,
        backend = "fake",
        kind = "COMMAND",
    )

    // ------------------------------------------------------------------ 单元面：计数器本身

    @Test
    fun `🔴 D18 每个丢弃点都有独立的 record 方法与可读 getter`() {
        val c = PipelineCounters()
        c.recordReceived()
        c.recordIntakeQueueDropped()
        c.recordStaleDropped()
        c.recordDuplicateDropped()
        c.recordBlankIdDropped()
        c.recordAccessRejected()
        c.recordPolicyRejected()
        c.recordUnmatched()
        c.recordSubmitted()
        c.recordQueueTimeout()
        c.recordChatBusy()
        c.recordBusyReplySuppressed()
        c.recordUrgentCancellations(3)
        c.recordTotalTimeout()
        c.recordIdleTimeout()
        c.recordCancelled()
        c.recordHandlerError()
        c.recordReplyFailure()
        c.recordSinkDropped(7L)

        assertEquals(1L, c.intakeReceived)
        assertEquals(1L, c.intakeQueueDropped, "有界链路第 1 行：intake DROP_OLDEST")
        assertEquals(1L, c.staleDropped, "StaleFilter 窗口外丢弃")
        assertEquals(1L, c.duplicatesDropped, "MessageDeduper 命中丢弃")
        assertEquals(1L, c.blankIdsDropped, "空 messageId 丢弃")
        assertEquals(1L, c.accessRejected, "AccessControl 拒绝")
        assertEquals(1L, c.policyRejected, "PolicyGate 拒绝")
        assertEquals(1L, c.unmatched, "路由未命中")
        assertEquals(1L, c.submitted)
        assertEquals(1L, c.queueTimeouts, "② 准入排队超时")
        assertEquals(1L, c.chatBusy)
        assertEquals(1L, c.busyRepliesSuppressed)
        assertEquals(3L, c.urgentCancellations, "URGENT 一次可取消多个在途任务 ⇒ 按个数累加")
        assertEquals(1L, c.totalTimeouts)
        assertEquals(1L, c.idleTimeouts)
        assertEquals(1L, c.cancelledTasks)
        assertEquals(1L, c.handlerErrors)
        assertEquals(1L, c.replyFailures)
        assertEquals(7L, c.sinkDropped, "有界链路第 4 行：sink DROP_OLDEST")
    }

    @Test
    fun `recordOutcome 按 TaskStatus 分类：SUCCESS 到 completed、FAILURE 到 failed、SKIPPED 到 skipped`() {
        val c = PipelineCounters()
        c.recordOutcome(HandlerOutcome.success(TaskKind.AGENT, "ok"))
        c.recordOutcome(HandlerOutcome.success(TaskKind.COMMAND, "ok2"))
        c.recordOutcome(HandlerOutcome.failure(TaskKind.AGENT, "boom", TaskErrorCodes.TASK_ERROR))
        c.recordOutcome(HandlerOutcome.skipped(summary = "rejected_silent", silent = true))

        assertEquals(2L, c.completed)
        assertEquals(1L, c.failed)
        assertEquals(1L, c.skipped)
        // 🔴 BC-142/143：SKIPPED **不计入**完成/失败（旧 BridgeEngine 语义平移）
        assertEquals(3L, c.completed + c.failed, "completed + failed 不含 SKIPPED")
        assertFalse(HandlerOutcome.skipped().countsForMetrics)
        assertTrue(HandlerOutcome.success(TaskKind.AGENT, "x").countsForMetrics)
        assertTrue(HandlerOutcome.failure(TaskKind.AGENT, "x", "e").countsForMetrics)
    }

    @Test
    fun `snapshot 是 26 项一致快照，droppedTotal 聚合全部丢弃点`() {
        val c = PipelineCounters()
        val empty = c.snapshot()
        // 🔴 26 个计数器逐项进快照（22 项 + 2026-09-11 引用流 4 项；字段数由 componentN 数量证明）
        assertEquals(26, empty::class.java.declaredMethods.count { it.name.startsWith("component") }, "CounterSnapshot 必须是 26 项")
        assertEquals(0L, empty.filesRegistered)
        assertEquals(0L, empty.filesReferenced)
        assertEquals(0L, empty.downloadsStored)
        assertEquals(0L, empty.quotaEvictions)
        assertEquals(0L, empty.droppedTotal)

        c.recordIntakeQueueDropped()
        c.recordStaleDropped()
        c.recordDuplicateDropped()
        c.recordBlankIdDropped()
        c.recordAccessRejected()
        c.recordPolicyRejected()
        c.recordQueueTimeout()
        c.recordChatBusy()
        c.recordBusyReplySuppressed()
        c.recordSinkDropped(2L)
        val s = c.snapshot()
        assertEquals(1L, s.intakeQueueDropped)
        assertEquals(2L, s.sinkDropped)
        // 🔴 droppedTotal = 10 个丢弃点之和（1×9 + 2）
        assertEquals(11L, s.droppedTotal)
        assertEquals(
            s.intakeQueueDropped + s.staleDropped + s.duplicatesDropped + s.blankIdsDropped +
                s.accessRejected + s.policyRejected + s.queueTimeouts + s.chatBusy +
                s.busyRepliesSuppressed + s.sinkDropped,
            s.droppedTotal,
        )
        // 🔴 快照是**值拷贝**：拍完之后再计数不会改动已拍的那张
        c.recordStaleDropped()
        assertEquals(1L, s.staleDropped)
        assertEquals(2L, c.staleDropped)
    }

    @Test
    fun `reset 把全部计数器归零（切换 appId 时用；幂等）`() {
        val c = PipelineCounters()
        c.recordReceived()
        c.recordSubmitted()
        c.recordSinkDropped(9L)
        c.recordOutcome(HandlerOutcome.success(TaskKind.AGENT, "ok"))
        // 🔴 reset 前先确认确实脏了（否则本测试会假绿）
        assertEquals(9L, c.snapshot().droppedTotal)
        assertEquals(1L, c.completed)
        c.reset()
        val s = c.snapshot()
        assertEquals(0L, s.intakeReceived)
        assertEquals(0L, s.submitted)
        assertEquals(0L, s.sinkDropped)
        assertEquals(0L, s.completed)
        assertEquals(0L, s.droppedTotal)
        c.reset()
        assertEquals(0L, c.intakeReceived, "reset 幂等")
    }

    @Test
    fun `计数器并发安全：多线程累加不丢计数（AtomicLong，非 ++ 裸字段）`() {
        val c = PipelineCounters()
        val threads = (1..8).map {
            Thread { repeat(2_000) { c.recordReceived(); c.recordSubmitted() } }.apply { start() }
        }
        threads.forEach { it.join() }
        assertEquals(16_000L, c.intakeReceived)
        assertEquals(16_000L, c.submitted)
    }

    // ------------------------------------------------------------------ 端到端面：经 intake 的真实丢弃

    @Test
    fun `🔴 D18 端到端：五阶段每个丢弃点都在计数器上留痕（单条测试跑遍全链路）`() {
        val config = testConfig(
            routes = listOf(SystemRoute()),
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = config,
            kinds = systemKinds(),
            queueCapacity = 4,
        ).use { h ->
            // ① 陈旧：窗口外（默认 30min）
            h.process(msg(messageId = "om_old", createTimeMillis = System.currentTimeMillis() - 3_600_000L))
            assertEquals(1L, h.counters.staleDropped)

            // ② 重复：同 messageId 二次入站
            h.process(msg(messageId = "om_twice", text = "ping"))
            h.process(msg(messageId = "om_twice", text = "ping"))
            assertEquals(1L, h.counters.duplicatesDropped)

            // ③ 空 messageId
            h.process(msg(messageId = "   "))
            assertEquals(1L, h.counters.blankIdsDropped)

            // ④ sender 准入：恒放行，不再产生拒绝计数
            h.process(msg(messageId = "om_mal", senderId = "ou_mallory"))
            assertEquals(0L, h.counters.accessRejected, "准入无拒绝路径")

            // ⑤ 群@门控拒绝（群内未@机器人）
            h.process(msg(messageId = "om_grp", chatType = ChatType.GROUP, mentionedBot = false))
            assertEquals(1L, h.counters.policyRejected)
            assertEquals(1, h.rejectedSignals.size, "拒绝必须同时产出 GatewaySignal.Rejected")

            // ⑥ intake 队列 DROP_OLDEST（🔴 不启动 intake 协程 ⇒ 队列只进不出）
            val accepted = (1..6).map { h.queue.trySend(msg(messageId = "om_q$it")) }
            assertEquals(4, accepted.count { it }, "容量 4 ⇒ 前 4 条不挤掉旧消息")
            assertEquals(2, accepted.count { !it }, "后 2 条各挤掉一条最旧的")
            assertEquals(2L, h.queue.dropped)
            assertEquals(2L, h.counters.intakeQueueDropped, "队列丢弃必须转记到 PipelineCounters")
            assertEquals(2, h.gateway.signalEmitCount, "队列丢弃必须转发成网关 Dropped 信号（D18）")
            // 🔴 信号体本身也要对：where = `intake`（与网关侧同一张 where 表）、droppedTotal 单调递增
            val dropped = runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3_000L) { h.gateway.signals.take(2).toList() }
            }
            assertEquals(2, dropped?.size, "两个 Dropped 信号必须真的进了网关信号流")
            assertTrue(dropped!!.all { it is GatewaySignal.Dropped && it.where == InboundQueue.WHERE }, dropped.toString())
            assertEquals(listOf(1L, 2L), dropped.map { (it as GatewaySignal.Dropped).droppedTotal })

            // ⑦ 聚合：droppedTotal 覆盖上面全部丢弃点
            val s = h.counters.snapshot()
            // 4 个 intake 侧丢弃点（陈旧/重复/空 id/群@门控——准入层已无拒绝点）+ 2 次队列丢弃
            assertEquals(1L + 1L + 1L + 1L + 2L, s.droppedTotal, "4 个 intake 侧丢弃点 + 2 次队列丢弃")
            assertEquals(6L, h.counters.intakeReceived, "🔴 先计数再判定 ⇒ 丢弃路径也算 received")
            assertEquals(6L, h.intake.processedCount)
            // 准入层已无拒绝点：om_mal 与过五步的那条都进 ② Admission ⇒ 2 条
            assertEquals(2L, h.counters.submitted, "准入恒放行 ⇒ 2 条进② Admission")
            assertEquals(0L, h.counters.unmatched)
        }
    }

    @Test
    fun `准入超时与繁忙抑制也计入 droppedTotal（② 阶段的两个丢弃点）`() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(
                routes = listOf(com.jiaoay.lark.bridge.config.AgentRoute()),
                maxConcurrentAgents = 1,
                chatQueueLimit = 1,
                queueWaitSeconds = 1L,
            ),
            kinds = HandlerKinds.build(agent = HandlerFactory { _, _ -> heavyAgent(gate) }),
        ).use { h ->
            h.process(msg(messageId = "om_k1", chatId = "oc_a", text = "ai 占位"))
            assertTrue(h.awaitUntil { h.permits.available == 0 })

            // 同 chat 第二条 ⇒ chatBusy（且首次繁忙会回复）
            h.process(msg(messageId = "om_k2", chatId = "oc_a", text = "ai 挤"))
            assertTrue(h.awaitUntil(3_000L) { h.counters.chatBusy >= 1L })

            // 另一个 chat ⇒ 排队 1s 后超时
            h.process(msg(messageId = "om_k3", chatId = "oc_b", text = "ai 排队"))
            assertTrue(h.awaitUntil(8_000L) { h.counters.queueTimeouts >= 1L })

            val s = h.counters.snapshot()
            assertEquals(1L, s.chatBusy)
            assertEquals(1L, s.queueTimeouts)
            assertTrue(s.droppedTotal >= 2L, "准入的两个丢弃点必须进聚合：${s.droppedTotal}")
            gate.complete(Unit)
            assertTrue(h.awaitQuiescent())
        }
    }

    // ------------------------------------------------------------------ 有界链路第 4 行：SinkChannels

    @Test
    fun `SinkChannels 默认容量 8192（方案「有界内存链路 8 行表」第 4 行逐字）`() {
        assertEquals(8192, SinkChannels.CAPACITY)
        assertEquals(8192, SinkChannels().capacity)
        assertEquals("audit-sink", SinkChannels.AUDIT_WHERE)
        assertEquals("stats-sink", SinkChannels.STATS_WHERE)
        // 🔴 容量下限保护：非法值被夹到 1（不得抛，装配期不允许因为配置写错而起不来）
        assertEquals(1, SinkChannels(capacity = 0).capacity)
        assertEquals(1, SinkChannels(capacity = -5).capacity)
    }

    @Test
    fun `🔴 SinkChannels DROP_OLDEST：溢出时 offer 返回 false 且 droppedRecords 递增`() {
        val sinks = SinkChannels(capacity = 3)
        try {
            assertTrue(sinks.offerAudit(audit(1L)))
            assertTrue(sinks.offerAudit(audit(2L)))
            assertTrue(sinks.offerAudit(audit(3L)))
            // 🔴 第 4 条挤掉最旧的一条（M-16 计数手法：offered - consumed >= capacity ⇒ 本次溢出）
            assertFalse(sinks.offerAudit(audit(4L)), "溢出时 offer 必须返回 false")
            assertFalse(sinks.offerAudit(audit(5L)))
            assertEquals(2L, sinks.auditDropped)
            assertEquals(2L, sinks.droppedRecords)
            assertEquals(0L, sinks.statsDropped, "统计通道独立计数")
            assertEquals(5L, sinks.auditOfferedCount)
            assertEquals(3L, sinks.auditPending, "在途 = offered - consumed - dropped")

            // 🔴 保留的是**最新** 3 条（ts=3,4,5），最旧的被挤掉
            val drained = runBlocking { listOfNotNull(sinks.receiveAudit(), sinks.receiveAudit(), sinks.receiveAudit()) }
            assertEquals(listOf(3L, 4L, 5L), drained.map { (it as SinkRecord.Audit).ts })
            assertEquals(0L, sinks.auditPending)
            assertEquals(2L, sinks.auditDropped, "消费不得改动丢弃计数")
        } finally {
            sinks.close()
        }
    }

    @Test
    fun `SinkChannels 两条通道互不干扰且 close 幂等、排空后 receive 返回 null`() = runBlocking {
        val sinks = SinkChannels(capacity = 2)
        sinks.offerAudit(audit(1L))
        sinks.offerStats(
            SinkRecord.SenderStat(
                ts = 2L,
                senderOpenId = "ou_alice",
                userName = "Alice",
                chatId = "oc_a",
                type = SinkRecord.TYPE_MESSAGE,
                contentSummary = "ping",
            ),
        )
        assertEquals(1L, sinks.auditOfferedCount)
        assertEquals(1L, sinks.statsOfferedCount)
        assertEquals(1L, sinks.auditPending)
        assertEquals(1L, sinks.statsPending)

        sinks.close()
        sinks.close() // 🔴 幂等
        val a = sinks.receiveAudit()
        val s = sinks.receiveStats()
        assertTrue(a is SinkRecord.Audit)
        assertTrue(s is SinkRecord.SenderStat)
        assertEquals(SinkRecord.TYPE_MESSAGE, s.type)
        // 🔴 D20：通道关闭且排空后 receive 返回 null ⇒ 消费者协程据此正常收尾（不抛、不空转）
        assertEquals(null, sinks.receiveAudit())
        assertEquals(null, sinks.receiveStats())
        assertEquals(0L, sinks.auditPending)
    }

    @Test
    fun `SinkChannels 8192 满容量下真实溢出（不是只在玩具容量上成立）`() {
        val sinks = SinkChannels()
        try {
            val total = SinkChannels.CAPACITY + 10
            var accepted = 0
            repeat(total) { i -> if (sinks.offerAudit(audit(i.toLong()))) accepted++ }
            assertEquals(SinkChannels.CAPACITY, accepted, "前 8192 条不触发丢弃")
            assertEquals(10L, sinks.auditDropped)
            assertEquals(10L, sinks.droppedRecords)
            assertEquals(total.toLong(), sinks.auditOfferedCount)
            assertEquals(SinkChannels.CAPACITY.toLong(), sinks.auditPending)
        } finally {
            sinks.close()
        }
    }

    @Test
    fun `SinkRecord 字面值常量（🔒 BC §4-11 实测定稿，T8 的 JsonlSink 直接消费）`() {
        assertEquals("audit.jsonl", SinkRecord.AUDIT_FILE_NAME)
        assertEquals("sender_stats.jsonl", SinkRecord.SENDER_STATS_FILE_NAME)
        assertEquals(200, SinkRecord.PROMPT_SUMMARY_CHARS)
        assertEquals(60, SinkRecord.CMD_SUMMARY_CHARS)
        assertEquals(120, SinkRecord.CONTENT_SUMMARY_CHARS)
        // 🔴 M4-T8 校正：以下四个字面值以**用户存量 JSONL 实测**为准（旧实现写的是大写）。
        // 证据：`grep -o '"type":"[^"]*"' ~/.toolbelt/lark/sender_stats.jsonl | sort | uniq -c`
        //        → 10 × `"type":"MESSAGE"`；
        //       `grep -o '"status":"[^"]*"' ~/.toolbelt/lark/audit.jsonl | sort | uniq -c`
        //        → 7 × `"status":"FAILURE"`、3 × `"status":"SUCCESS"`；
        //       `grep -o '"kind":"[^"]*"' …/audit.jsonl` → 9 × `"kind":"SKILL"`、1 × `"kind":"AGENT"`。
        assertEquals("MESSAGE", SinkRecord.TYPE_MESSAGE)
        // 🔴 刻意保留旧拼写：已落盘的历史统计里就是这个字面值，改成 REJECTED_ALLOWLIST 会让分组统计出错
        assertEquals("REJECTED_WHITELIST", SinkRecord.TYPE_REJECTED_ALLOWLIST)
        assertEquals("REJECTED_BLACKLIST", SinkRecord.TYPE_REJECTED_BLOCKLIST)
        assertEquals("SUCCESS", SinkRecord.statusLiteral(TaskStatus.SUCCESS))
        assertEquals("FAILURE", SinkRecord.statusLiteral(TaskStatus.FAILURE))
        assertEquals("SKIPPED", SinkRecord.statusLiteral(TaskStatus.SKIPPED))
    }

    @Test
    fun `计数手法 M-16 的可观测性：offered 与 consumed 都用 AtomicLong（并发投递不丢计数）`() {
        val sinks = SinkChannels(capacity = 64)
        val done = AtomicLong(0)
        try {
            val threads = (1..6).map { t ->
                Thread {
                    repeat(500) { i -> sinks.offerAudit(audit((t * 1000 + i).toLong())) }
                    done.incrementAndGet()
                }.apply { start() }
            }
            threads.forEach { it.join() }
            assertEquals(6L, done.get())
            assertEquals(3_000L, sinks.auditOfferedCount, "3000 次投递必须一次不少地记进 offered")
            assertEquals(3_000L - 64L, sinks.auditDropped, "容量 64 ⇒ 溢出 2936 次")
            assertEquals(64L, sinks.auditPending)
        } finally {
            sinks.close()
        }
    }
}
