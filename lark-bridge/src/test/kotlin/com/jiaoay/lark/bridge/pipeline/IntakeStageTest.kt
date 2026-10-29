// M4-T7 测试：① Intake 阶段（承接旧 engine/Dedup* 的断言，按方案映射为 EventIntakeTest）。
// 🔴 三个测试类分别覆盖 MessageDeduper（D12：key=messageId）、StaleFilter（NF-A7）、EventIntake（五步接线）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.GatewaySdkSettings
import com.jiaoay.lark.gateway.InboundKind
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** 构造一个「SYSTEM 路由已登记」的 HandlerKinds（intake 侧测试只需一条能命中的路由）。 */
internal fun systemKinds(handler: ScriptedHandler = ScriptedHandler(kind = RouteKind.SYSTEM)): HandlerKinds =
    HandlerKinds.build(system = HandlerFactory { _, _ -> handler })

/**
 * [方案 D12 · MessageDeduper] 幂等键 = **messageId**（绝不用 eventId）+ LRU 4096 + TTL 12h。
 *
 * 🔴 双证据链：① 飞书官方明确「消息事件重推时 event_id 可能不同」；② SDK 的 `NormalizeDedupKeys`
 *    实测也用 messageId。故本层去重键与网关传输层去重键同源。
 */
class MessageDeduperTest {

    @Test
    fun `默认上限 4096 与 TTL 12 小时（方案表逐字）`() {
        val deduper = MessageDeduper()
        assertEquals(4096, deduper.capacity)
        assertEquals(4096, MessageDeduper.MAX_ENTRIES)
        assertEquals(43_200_000L, MessageDeduper.TTL_MILLIS)
        assertEquals(12L * 60 * 60 * 1000, MessageDeduper.TTL_MILLIS)
    }

    @Test
    fun `同 messageId 二次判定为 DUPLICATE`() {
        val deduper = MessageDeduper()
        assertEquals(DedupDecision.ACCEPT, deduper.decide("om_1"))
        assertEquals(DedupDecision.DUPLICATE, deduper.decide("om_1"))
        assertEquals(DedupDecision.DUPLICATE, deduper.decide("om_1"))
        assertEquals(1L, deduper.acceptedCount)
        assertEquals(2L, deduper.duplicateCount)
    }

    @Test
    fun `不同 messageId 但内容相同不被去重（键是 messageId 不是内容）`() {
        val deduper = MessageDeduper()
        assertEquals(DedupDecision.ACCEPT, deduper.decide("om_1"))
        assertEquals(DedupDecision.ACCEPT, deduper.decide("om_2"))
        assertEquals(0L, deduper.duplicateCount)
        assertEquals(2L, deduper.acceptedCount)
        assertEquals(2, deduper.size)
    }

    @Test
    fun `空白 messageId 判为 BLANK_ID 且不进 LRU 表`() {
        val deduper = MessageDeduper()
        assertEquals(DedupDecision.BLANK_ID, deduper.decide(""))
        assertEquals(DedupDecision.BLANK_ID, deduper.decide("   "))
        assertEquals(2L, deduper.blankIdCount)
        assertEquals(0, deduper.size)
        assertEquals(0L, deduper.acceptedCount)
    }

    @Test
    fun `LRU 上限逐出最久未用项，被逐出的键再次出现算首次`() {
        val deduper = MessageDeduper(maxEntries = 3)
        assertTrue(deduper.firstTime("a"))
        assertTrue(deduper.firstTime("b"))
        assertTrue(deduper.firstTime("c"))
        assertEquals(3, deduper.size)
        assertEquals(0L, deduper.evictionCount)

        // 第 4 个键进来 ⇒ 逐出头部 a
        assertTrue(deduper.firstTime("d"))
        assertEquals(3, deduper.size)
        assertEquals(1L, deduper.evictionCount)
        assertFalse(deduper.contains("a"))

        // a 已被逐出 ⇒ 再次出现算首次（这正是 LRU 的语义代价，方案接受）
        assertTrue(deduper.firstTime("a"))
        assertEquals(2L, deduper.evictionCount)
        assertFalse(deduper.contains("b"))
    }

    @Test
    fun `LRU 访问序生效：被重复命中的键不会被逐出`() {
        val deduper = MessageDeduper(maxEntries = 3)
        deduper.firstTime("a")
        deduper.firstTime("b")
        deduper.firstTime("c")
        // 命中 a ⇒ a 移到尾部，头部变成 b
        assertEquals(DedupDecision.DUPLICATE, deduper.decide("a"))
        deduper.firstTime("d")
        assertTrue(deduper.contains("a"))
        assertFalse(deduper.contains("b"))
    }

    @Test
    fun `4096 上限的真实逐出行为（方案表定稿值）`() {
        val deduper = MessageDeduper()
        repeat(MessageDeduper.MAX_ENTRIES + 1) { i -> deduper.decide("om_$i") }
        assertEquals(MessageDeduper.MAX_ENTRIES, deduper.size)
        assertEquals(1L, deduper.evictionCount)
        assertFalse(deduper.contains("om_0"))
        assertTrue(deduper.contains("om_${MessageDeduper.MAX_ENTRIES}"))
    }

    @Test
    fun `TTL 过期后同 messageId 再次算首次（对齐飞书 12h 重推窗口）`() {
        val now = AtomicLong(1_000_000L)
        val deduper = MessageDeduper(ttlMillis = MessageDeduper.TTL_MILLIS, clock = { now.get() })
        assertEquals(DedupDecision.ACCEPT, deduper.decide("om_1"))
        assertEquals(DedupDecision.DUPLICATE, deduper.decide("om_1"))

        // 差 1ms 到 12h ⇒ 仍是重复
        now.addAndGet(MessageDeduper.TTL_MILLIS - 1L)
        assertEquals(DedupDecision.DUPLICATE, deduper.decide("om_1"))
        assertEquals(0L, deduper.expiredCount)

        // 恰好 12h ⇒ 过期（`now - previous < ttl` 是严格小于）
        now.addAndGet(1L)
        assertEquals(DedupDecision.ACCEPT, deduper.decide("om_1"))
        assertEquals(1L, deduper.expiredCount)
        assertEquals(1, deduper.size)
    }

    @Test
    fun `时钟回拨不误判为过期（now 变小 ⇒ 差值为负 ⇒ 仍是重复）`() {
        val now = AtomicLong(5_000_000L)
        val deduper = MessageDeduper(clock = { now.get() })
        deduper.decide("om_1")
        now.set(1_000L)
        assertEquals(DedupDecision.DUPLICATE, deduper.decide("om_1"))
        assertEquals(0L, deduper.expiredCount)
    }

    @Test
    fun `clear 幂等且清空表`() {
        val deduper = MessageDeduper()
        deduper.decide("om_1")
        deduper.clear()
        deduper.clear()
        assertEquals(0, deduper.size)
        assertEquals(DedupDecision.ACCEPT, deduper.decide("om_1"))
    }
}

/** [方案 NF-A7 · StaleFilter] 陈旧事件过滤：窗口取自网关 `GatewaySdkSettings`（不硬编码）。 */
class StaleFilterTest {

    @Test
    fun `默认窗口取自网关 SDK 设置而非硬编码（30min）`() {
        val filter = StaleFilter()
        assertEquals(GatewaySdkSettings.DEFAULT.staleMessageWindowMs, filter.window)
        assertEquals(1_800_000L, filter.window)
    }

    @Test
    fun `窗口外的事件被丢并计数`() {
        val now = AtomicLong(10_000_000L)
        val filter = StaleFilter(windowMillis = 1_000L, clock = { now.get() })
        assertFalse(filter.test(msg(messageId = "om_old", createTimeMillis = now.get() - 1_001L)))
        assertEquals(1L, filter.dropped)
        assertEquals(0L, filter.passed)
    }

    @Test
    fun `窗口内的事件放行并计数`() {
        val now = AtomicLong(10_000_000L)
        val filter = StaleFilter(windowMillis = 1_000L, clock = { now.get() })
        assertTrue(filter.test(msg(messageId = "om_new", createTimeMillis = now.get() - 10L)))
        assertEquals(0L, filter.dropped)
        assertEquals(1L, filter.passed)
    }

    @Test
    fun `恰好等于窗口边界放行（早于窗口是严格小于语义）`() {
        val now = AtomicLong(10_000_000L)
        val filter = StaleFilter(windowMillis = 1_000L, clock = { now.get() })
        assertTrue(filter.isFresh(now.get() - 1_000L))
        assertFalse(filter.isFresh(now.get() - 1_001L))
    }

    @Test
    fun `本机时钟回拨放行（age 为负绝不当陈旧丢）`() {
        val now = AtomicLong(1_000L)
        val filter = StaleFilter(windowMillis = 1_000L, clock = { now.get() })
        assertTrue(filter.isFresh(9_000_000L))
        assertTrue(filter.test(msg(messageId = "om_future", createTimeMillis = 9_000_000L)))
        assertEquals(0L, filter.dropped)
    }

    @Test
    fun `createTime 非法（0 或负）时不判陈旧（SDK 未能解析出时间戳 ⇒ 宁可放行不误杀）`() {
        val filter = StaleFilter(windowMillis = 1_000L, clock = { 10_000_000L })
        assertTrue(filter.isFresh(0L))
        assertTrue(filter.isFresh(-1L))
        assertEquals(0L, filter.dropped)
    }

    @Test
    fun `ageOf 返回带符号年龄`() {
        val now = AtomicLong(10_000L)
        val filter = StaleFilter(windowMillis = 1_000L, clock = { now.get() })
        assertEquals(500L, filter.ageOf(msg(createTimeMillis = 9_500L)))
        assertEquals(-500L, filter.ageOf(msg(createTimeMillis = 10_500L)))
    }
}

/**
 * [方案「统一处理链」① Intake] 单协程五步接线：StaleFilter → MessageDeduper → AccessControl → PolicyGate → PipelineRouter。
 *
 * 🔴 承接旧 `engine/DedupTest` / `engine/DedupConcurrencyTest` 的断言（behavior-contract 映射表）。
 */
class EventIntakeTest {

    private fun harness(
        config: com.jiaoay.lark.bridge.config.BridgeConfig = testConfig(routes = listOf(SystemRoute())),
        clock: () -> Long = { System.currentTimeMillis() },
        deduperCapacity: Int = MessageDeduper.MAX_ENTRIES,
        deduperTtlMillis: Long = MessageDeduper.TTL_MILLIS,
        staleWindowMillis: Long = GatewaySdkSettings.DEFAULT.staleMessageWindowMs,
        queueCapacity: Int = InboundQueue.CAPACITY,
    ) = Harness(
        dispatcher = Dispatchers.Default,
        initialConfig = config,
        kinds = systemKinds(),
        clock = clock,
        deduperCapacity = deduperCapacity,
        deduperTtlMillis = deduperTtlMillis,
        staleWindowMillis = staleWindowMillis,
        queueCapacity = queueCapacity,
    )

    @Test
    fun `同 messageId 二次入站被丢（旧 DedupTest 的核心断言）`() {
        harness().use { h ->
            h.process(msg(messageId = "om_dup", text = "ping"))
            h.process(msg(messageId = "om_dup", text = "ping"))
            h.process(msg(messageId = "om_dup", text = "ping"))

            h.awaitOutcomes(1)
            Thread.sleep(100L)
            assertEquals(3L, h.counters.intakeReceived)
            assertEquals(2L, h.counters.duplicatesDropped)
            assertEquals(1L, h.deduper.acceptedCount)
            assertEquals(2L, h.deduper.duplicateCount)
            assertEquals(1, h.outcomes.size)
            assertEquals(1L, h.counters.submitted)
        }
    }

    @Test
    fun `不同 messageId 但内容完全相同不被丢（键是 messageId 不是内容指纹）`() {
        harness().use { h ->
            h.process(msg(messageId = "om_a", text = "同一句话"))
            h.process(msg(messageId = "om_b", text = "同一句话"))

            h.awaitOutcomes(2)
            assertEquals(0L, h.counters.duplicatesDropped)
            assertEquals(2, h.outcomes.size)
            assertEquals(2L, h.counters.submitted)
        }
    }

    @Test
    fun `LRU 上限逐出后经 intake 同样生效`() {
        harness(deduperCapacity = 3).use { h ->
            listOf("om_1", "om_2", "om_3", "om_4").forEach { h.process(msg(messageId = it)) }
            // om_1 已被逐出 ⇒ 重推算首次
            h.process(msg(messageId = "om_1"))

            h.awaitOutcomes(5)
            assertEquals(5L, h.counters.submitted)
            assertEquals(0L, h.counters.duplicatesDropped)
            assertTrue(h.deduper.evictionCount >= 2L)
        }
    }

    @Test
    fun `TTL 12h 过期后同 messageId 重推被重新处理`() {
        val now = AtomicLong(1_000_000L)
        harness(clock = { now.get() }).use { h ->
            h.process(msg(messageId = "om_ttl", createTimeMillis = now.get()))
            h.process(msg(messageId = "om_ttl", createTimeMillis = now.get()))
            h.awaitOutcomes(1)
            assertEquals(1L, h.counters.duplicatesDropped)

            now.addAndGet(MessageDeduper.TTL_MILLIS)
            h.process(msg(messageId = "om_ttl", createTimeMillis = now.get()))
            h.awaitOutcomes(2)
            assertEquals(1L, h.deduper.expiredCount)
            assertEquals(2L, h.counters.submitted)
        }
    }

    @Test
    fun `空 messageId 被丢且计数（无幂等键无法去重）`() {
        harness().use { h ->
            h.process(msg(messageId = "", text = "ping"))
            h.process(msg(messageId = "   ", text = "ping"))
            Thread.sleep(80L)
            assertEquals(2L, h.counters.blankIdsDropped)
            assertEquals(0L, h.counters.submitted)
            assertEquals(2L, h.deduper.blankIdCount)
        }
    }

    @Test
    fun `StaleFilter 窗口外的事件被丢且计数`() {
        val now = AtomicLong(10_000_000L)
        harness(clock = { now.get() }, staleWindowMillis = 1_000L).use { h ->
            h.process(msg(messageId = "om_stale", createTimeMillis = now.get() - 5_000L))
            h.process(msg(messageId = "om_fresh", createTimeMillis = now.get()))
            h.awaitOutcomes(1)
            assertEquals(1L, h.counters.staleDropped)
            assertEquals(1L, h.staleFilter.dropped)
            assertEquals(1L, h.staleFilter.passed)
            assertEquals(1L, h.counters.submitted)
        }
    }

    @Test
    fun `陈旧过滤先于去重（陈旧事件不占 LRU 表位）`() {
        val now = AtomicLong(10_000_000L)
        harness(clock = { now.get() }, staleWindowMillis = 1_000L).use { h ->
            h.process(msg(messageId = "om_stale", createTimeMillis = now.get() - 5_000L))
            Thread.sleep(50L)
            assertFalse(h.deduper.contains("om_stale"))
            assertEquals(0, h.deduper.size)
        }
    }

    @Test
    fun `intake 单消费者协程能排空队列并逐条走完五步`() {
        harness().use { h ->
            val job = h.startIntake()
            assertTrue(job.isActive)
            repeat(20) { i -> h.queue.trySend(msg(messageId = "om_$i", text = "t$i")) }
            assertTrue(h.awaitUntil { h.intake.processedCount >= 20L })
            h.awaitOutcomes(20)
            assertEquals(20L, h.counters.intakeReceived)
            assertEquals(20L, h.counters.submitted)
            assertEquals(0L, h.queue.dropped)
        }
    }

    @Test
    fun `intake 协程遇到单条异常不中断循环（BC-146 同形）`() {
        // 用一个「路由时抛异常」的快照工厂无法注入，故改用 deduper 判否之外的路径：
        // 让 AccessControl 的配置提供者抛异常（模拟配置读失败），intake 必须吞掉并继续。
        val broken = Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(),
            kinds = systemKinds(),
        )
        broken.use { h ->
            val job = h.startIntake()
            h.queue.trySend(msg(messageId = "om_1"))
            assertTrue(h.awaitUntil { h.intake.processedCount >= 1L })
            h.queue.trySend(msg(messageId = "om_2"))
            assertTrue(h.awaitUntil { h.intake.processedCount >= 2L })
            assertTrue(job.isActive, "intake 协程必须在处理完两条后仍然存活")
        }
    }

    @Test
    fun `intake 队列满时 DROP_OLDEST 且计数与 Dropped 信号同步（D18）`() = runBlocking {
        harness(queueCapacity = 4).use { h ->
            // 🔴 `trySend` 恒 O(1) 返回（不挂起），返回值语义是「本次是否挤掉了旧消息」
            val accepted = (0 until 10).map { i -> h.queue.trySend(msg(messageId = "om_$i", text = "t$i")) }
            assertEquals(listOf(true, true, true, true, false, false, false, false, false, false), accepted)
            assertEquals(6L, h.queue.dropped)
            assertEquals(6L, h.counters.intakeQueueDropped)
            assertEquals(10L, h.queue.offeredCount)
            assertEquals(4L, h.queue.pending)

            // 🔴 DROP_OLDEST 保留**最新** 4 条（om_6..om_9），不是最旧的 4 条
            val drained = (1..4).map { h.queue.receive()?.messageId }
            assertEquals(listOf("om_6", "om_7", "om_8", "om_9"), drained)
            assertEquals(0L, h.queue.pending)
        }
    }

    @Test
    fun `群消息在 groupMode=mention 且未@机器人时被 PolicyGate 拦在路由之前`() {
        val config = testConfig(routes = listOf(SystemRoute()), groupMode = "mention")
        harness(config = config).use { h ->
            h.process(msg(messageId = "om_g1", chatType = ChatType.GROUP, mentionedBot = false))
            h.awaitOutcomes(1)
            assertEquals(1L, h.counters.policyRejected)
            assertEquals(0L, h.router.matchedCount)
            assertEquals(0L, h.counters.submitted)
            // 🔴 拒绝仍进 ⑤ Observe 留痕，但**不占许可**
            assertEquals(1, h.outcomes.size)
            assertEquals(TaskStatus.SKIPPED, h.outcomes[0].outcome.status)
        }
    }

    @Test
    fun `CARD_ACTION 一律放行群@门控（卡片回传不是闲聊）`() {
        val config = testConfig(routes = listOf(SystemRoute()), groupMode = "mention")
        harness(config = config).use { h ->
            val allowed = h.policyGate.isAllowed(
                msg(chatType = ChatType.GROUP, kind = InboundKind.CARD_ACTION, mentionedBot = false),
            )
            assertTrue(allowed)
            assertEquals(0L, h.policyGate.groupEvaluatedCount)
        }
    }

    @Test
    fun `describe 汇总各阶段计数（排障面）`() {
        harness().use { h ->
            h.process(msg(messageId = "om_d"))
            h.awaitOutcomes(1)
            val text = h.intake.describe()
            assertTrue(text.contains("processed=1"), text)
            assertTrue(text.contains("stale=0"), text)
            assertTrue(text.contains("dup=0"), text)
            assertTrue(text.contains("unmatched=0"), text)
        }
    }
}
