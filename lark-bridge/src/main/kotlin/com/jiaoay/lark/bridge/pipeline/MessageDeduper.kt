// ① Intake 第 2 步——幂等去重。
// 🔴 键 = messageId，**绝不用 eventId**。
// 🔴 本文件属 intake 零锁清单（IntakeGuardTest 守护）：LRU 表只在 intake 单协程里被读写 ⇒ 普通 LinkedHashMap 即可，零监视器锁。
package com.jiaoay.lark.bridge.pipeline

import java.util.concurrent.atomic.AtomicLong

/**
 * 去重判定结果。
 * - [ACCEPT]：首次出现，放行。
 * - [DUPLICATE]：LRU/TTL 窗口内重复出现（飞书重推），丢弃。
 * - [BLANK_ID]：🔴 NF-A1 ④——`messageId` 为空的事件**丢弃**（无幂等键 ⇒ 无法去重 ⇒ 重复处理风险不可控）。
 */
enum class DedupDecision { ACCEPT, DUPLICATE, BLANK_ID }

/**
 * 消息去重器：**LRU [MAX_ENTRIES]=4096 + TTL [TTL_MILLIS]=12h，key = `messageId`**。
 *
 * ## 为什么是 messageId 而不是 eventId（双证据链）
 * 1. **飞书官方**明确：消息事件重推时 `event_id` **可能不同**（同一 `message_id` 会被推多次，
 *    每次带新的 event_id）⇒ 用 eventId 去重等于不去重。
 * 2. **SDK 实测**：`SafetyPipeline` 的 `NormalizeDedupKeys` 也用 messageId 作规范化键。
 * ⇒ 本网关契约 `InboundMessage` **根本不含 eventId 字段**（编译期就杜绝误用），本类只接受 messageId。
 *
 * ## 并发纪律
 * [decide] 只在 ① intake 的**单协程**里被调用 ⇒ LRU 表用普通 `LinkedHashMap` 就够，
 * 零监视器锁、零协程互斥量。「Intake 单消费者本身就是并发原语」在此落地。
 * 计数器用 `AtomicLong` 只是因为它们要被**别的线程**（metrics 协程 / UI）读取，属跨线程可见性需求，非互斥需求。
 *
 * @param maxEntries LRU 上限（超出逐出最久未访问项）。
 * @param ttlMillis 条目存活期；超期视为首次出现（允许重新处理，与飞书重推窗口 12h 对齐）。
 * @param clock 可注入（测试用虚拟时间驱动 TTL 过期）。
 */
class MessageDeduper(
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMillis: Long = TTL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * LRU 表：`messageId → 首次见到的时刻`。
     * `accessOrder = true` ⇒ get/put 都把条目移到尾部，头部即最久未用；[evictIfNeeded] 从头部逐出。
     */
    private val seen = LinkedHashMap<String, Long>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    private val duplicates = AtomicLong(0)
    private val blankIds = AtomicLong(0)
    private val evictions = AtomicLong(0)
    private val expired = AtomicLong(0)
    private val accepted = AtomicLong(0)

    /** 生效 LRU 上限。 */
    val capacity: Int get() = maxEntries

    /** 当前表内条目数（`MessageDeduperTest` 断言 LRU 上限用）。 */
    val size: Int get() = seen.size

    /** 累计重复丢弃数。 */
    val duplicateCount: Long get() = duplicates.get()

    /** 累计空 messageId 丢弃数（NF-A1 ④）。 */
    val blankIdCount: Long get() = blankIds.get()

    /** 累计 LRU 逐出数（容量压力可观测）。 */
    val evictionCount: Long get() = evictions.get()

    /** 累计 TTL 过期数（过期后同一 messageId 可被重新处理）。 */
    val expiredCount: Long get() = expired.get()

    /** 累计放行数。 */
    val acceptedCount: Long get() = accepted.get()

    /**
     * 判定一个 messageId 是否首次出现。
     * 纯 CPU、不挂起、零锁；调用方（intake）据返回值决定丢弃或继续。
     */
    fun decide(messageId: String): DedupDecision {
        if (messageId.isBlank()) {
            blankIds.incrementAndGet()
            return DedupDecision.BLANK_ID
        }
        val now = clock()
        val previous = seen[messageId]
        if (previous != null) {
            if (now - previous < ttlMillis) {
                duplicates.incrementAndGet()
                return DedupDecision.DUPLICATE
            }
            // TTL 过期：当作首次出现（并刷新时间戳）
            expired.incrementAndGet()
        }
        seen[messageId] = now
        evictIfNeeded()
        accepted.incrementAndGet()
        return DedupDecision.ACCEPT
    }

    /** 便捷布尔形态（`true` ⇒ 首次出现，放行）。 */
    fun firstTime(messageId: String): Boolean = decide(messageId) == DedupDecision.ACCEPT

    /** 表内是否已有该键（不改变 LRU 顺序、不计数；测试与排障用）。 */
    fun contains(messageId: String): Boolean = seen.containsKey(messageId)

    /** 清空（stop()/切换 appId 时；幂等）。 */
    fun clear() {
        seen.clear()
    }

    /** 从头部（最久未用）逐出直到 `size <= maxEntries`。 */
    private fun evictIfNeeded() {
        if (seen.size <= maxEntries) return
        val it = seen.entries.iterator()
        while (it.hasNext() && seen.size > maxEntries) {
            it.next()
            it.remove()
            evictions.incrementAndGet()
        }
    }

    companion object {
        /** LRU 上限。与网关 SDK 侧 `dedupMaxEntries=5000` 刻意不同值：
         *  SDK 那层是**传输级**去重（防重推风暴），本层是**业务级**幂等（防重复起 agent）。 */
        const val MAX_ENTRIES: Int = 4096

        /** TTL 12h（= 43_200_000L，与网关 `GatewaySdkSettings.dedupTtlMs` 同值）。 */
        const val TTL_MILLIS: Long = 43_200_000L

        private const val INITIAL_CAPACITY: Int = 256
        private const val LOAD_FACTOR: Float = 0.75f
    }
}
