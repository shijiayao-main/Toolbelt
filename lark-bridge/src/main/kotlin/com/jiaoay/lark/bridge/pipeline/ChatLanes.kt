// ② Admission 的每会话在途账本（ChatLane 表）。
// 🔴 本文件属 intake 零锁清单（IntakeGuardTest 守护）：ConcurrentHashMap + java.util.concurrent.atomic CAS，零监视器锁、零协程互斥量——违反即挂死 intake 单消费者协程、全链路停摆。
package com.jiaoay.lark.bridge.pipeline

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job

/**
 * 单个会话（chat）的在途账本。
 *
 * 一个 lane 只承载三样东西：**在途计数**、**在途 Job 集合**（URGENT 取消用）、**繁忙回复时间戳**（合批用）。
 * 全部字段都是 CAS 原子量 ⇒ 跨线程（intake 单协程 acquire / task 协程 release）无竞态、零锁。
 */
class ChatLane internal constructor(val chatId: String) {

    /** 当前在途 HEAVY 任务数。 */
    val inFlight: AtomicInteger = AtomicInteger(0)

    /** 最近一次被触碰的时刻（LRU 逐出依据）。 */
    val lastTouchMillis: AtomicLong = AtomicLong(0L)

    /**
     * 最近一次「繁忙回复」发出的时刻（按 chat 合批的依据）。
     * 初值 0 ⇒ 首次繁忙必然放行回复（0 与任何 now 的差都远超 cooldown）。
     */
    val lastBusyReplyMillis: AtomicLong = AtomicLong(0L)

    /**
     * 在途任务 Job 集合（URGENT 档位 `cancelAll` 的目标）。
     * 用 `ConcurrentHashMap` 背书的 Set：task 协程自行 remove，intake 协程遍历 cancel，零锁。
     */
    internal val jobs: MutableSet<Job> = Collections.newSetFromMap(ConcurrentHashMap())

    override fun toString(): String = "ChatLane($chatId, inFlight=${inFlight.get()}, jobs=${jobs.size})"
}

/**
 * ChatLane 表：**每 chat 在途上限 + LRU [MAX_LANES]=256 + 空 lane 即移除**。
 *
 * ## 为什么需要每 chat 上限（而不是只有全局许可）
 * 全局 `Semaphore(maxConcurrentAgents)` 只管总量；一个群里的用户连发 10 条就能吃满全部许可，
 * 让其它会话全部排队。ChatLane 是**公平性**闸门：单会话最多 `runtime.chatQueueLimit`(=2) 个在途，
 * 第 3 条立即回 `agent_busy`（🔒）而不排队 ⇒ 用户立刻知道该等，而不是干等 `queueWaitSeconds`。
 *
 * ## LRU 与「空 lane 即移除」
 * lane 是**按 chat 惰性创建**的：飞书群可能有成千上万个，常驻会无界增长。
 * - 每次 [tryAcquire] / [register] 都刷新 `lastTouchMillis`；
 * - 表大小达到 [MAX_LANES] 时，逐出**最久未触碰且 inFlight==0** 的 lane（`removeIfIdle`）；
 * - 🔴 **在途 lane 绝不逐出**（逐出会导致 release 找不到 lane ⇒ 计数永久泄漏）。若全部 lane 都在途
 *   （极端场景：256 个会话同时跑任务），本次**允许超额**创建并计数 [overflowCreates]，不拒绝真实消息。
 *
 * ## 繁忙回复合批（`busyReplyCooldownSeconds`）
 * 同一个 chat 在 cooldown 窗口内只回**一次** `agent_busy`（[claimBusyReply]）；
 * 后续被拒的消息静默丢弃并累加 [busyRepliesSuppressed]。理由：用户连点 5 次时，
 * 5 条「我很忙」比 1 条更让人烦躁，且会撞飞书 4rps 频控（NF-05）。
 *
 * @param maxLanes lane 表上限（方案表逐字：256）。
 * @param clock 可注入（测试用虚拟时间驱动合批窗口）。
 */
class ChatLanes(
    private val maxLanes: Int = MAX_LANES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val lanes = ConcurrentHashMap<String, ChatLane>()

    private val evictions = AtomicLong(0)
    private val overflowCreates = AtomicLong(0)
    private val busyClaims = AtomicLong(0)
    private val busySuppressed = AtomicLong(0)
    private val cancelRequests = AtomicLong(0)
    private val cancelledJobs = AtomicLong(0)

    /** 表上限。 */
    val capacity: Int get() = maxLanes

    /** 当前 lane 数（`AdmissionTest` 断言「空 lane 即移除」用）。 */
    val laneCount: Int get() = lanes.size

    /** 累计逐出的 lane 数。 */
    val evictionCount: Long get() = evictions.get()

    /** 累计「全表在途 ⇒ 允许超额创建」次数（排障：非 0 说明并发会话数超过 [maxLanes]）。 */
    val overflowCreateCount: Long get() = overflowCreates.get()

    /** 累计**发出**的繁忙回复次数。 */
    val busyReplyCount: Long get() = busyClaims.get()

    /** 累计**被合批抑制**的繁忙回复次数（抑制也必须可观测）。 */
    val busyRepliesSuppressed: Long get() = busySuppressed.get()

    /** 累计 `cancelAll` 调用次数。 */
    val cancelRequestCount: Long get() = cancelRequests.get()

    /** 累计被 `cancelAll` 取消的 Job 数。 */
    val cancelledJobCount: Long get() = cancelledJobs.get()

    /** 全表在途总数（metrics 1Hz 采样用）。 */
    val totalInFlight: Int get() = lanes.values.sumOf { it.inFlight.get() }

    /**
     * 取（或创建）一个 lane。O(1) 摊还；创建时可能触发一次 LRU 逐出扫描。
     */
    fun laneOf(chatId: String): ChatLane {
        lanes[chatId]?.let { it.lastTouchMillis.set(clock()); return it }
        val created = ChatLane(chatId)
        created.lastTouchMillis.set(clock())
        val existing = lanes.putIfAbsent(chatId, created)
        if (existing != null) {
            existing.lastTouchMillis.set(clock())
            return existing
        }
        evictIfNeeded()
        return created
    }

    /**
     * 尝试占一个在途名额。
     * @param limit `runtime.chatQueueLimit`（HOT：每次调用读最新值）。
     * @return true ⇒ 占到（调用方**必须**配对 [release]）；false ⇒ 该 chat 已满（走繁忙回复）。
     * CAS 自旋，零锁；`inFlight` 只在 [0, limit] 内变动。
     */
    fun tryAcquire(chatId: String, limit: Int): Boolean {
        val lane = laneOf(chatId)
        val cap = limit.coerceAtLeast(1)
        while (true) {
            val current = lane.inFlight.get()
            if (current >= cap) return false
            if (lane.inFlight.compareAndSet(current, current + 1)) return true
        }
    }

    /**
     * 释放在途名额（🔴 与 [tryAcquire] 严格配对，放 `finally` 里——漏配对即在途计数永久泄漏、后续任务恒被拒）。
     * 释放后若 lane 变空 ⇒ **立即移除**（方案「空 lane 即移除」）。
     * @return 释放后的在途数；-1 表示 lane 已不存在（重复释放或已被逐出，幂等无副作用）。
     */
    fun release(chatId: String): Int {
        val lane = lanes[chatId] ?: return -1
        val remaining = lane.inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (remaining == 0 && lane.jobs.isEmpty()) {
            // 🔴 只在「确实空了」时移除；remove(key, value) 双参形态保证不会误删别人刚建的同名 lane。
            lanes.remove(chatId, lane)
        }
        return remaining
    }

    /** 登记一个在途 Job（URGENT `cancelAll` 的目标；task 协程启动后立即调用）。 */
    fun register(chatId: String, job: Job) {
        val lane = laneOf(chatId)
        lane.jobs.add(job)
        lane.lastTouchMillis.set(clock())
    }

    /** 注销一个在途 Job（task 协程 `finally` 里调用；幂等）。 */
    fun unregister(chatId: String, job: Job) {
        lanes[chatId]?.jobs?.remove(job)
    }

    /**
     * 取消某 chat 的全部在途任务（URGENT 档位「停止」指令的落点）。
     *
     * 语义：**只 cancel Job，不动在途计数**——计数由各 task 协程自己的 `finally { release() }` 归还，
     * 这样不会出现「cancel 后计数被减两次」的泄漏。cancel 会触发协程取消 ⇒ handler 收到
     * `CancellationException` ⇒ `ProgramLauncher` 的三段式 kill 链在 `NonCancellable` 内跑完。
     *
     * @return 本次 cancel 的 Job 数（0 ⇒ 该 chat 没有在途任务，「停止」指令由 handler 自行回「当前无任务」）。
     */
    fun cancelAll(chatId: String): Int {
        cancelRequests.incrementAndGet()
        val lane = lanes[chatId] ?: return 0
        // 🔴 先拷一份再逐个 cancel：cancel 会同步触发 task 协程的 finally ⇒ unregister ⇒ 并发修改集合。
        val targets = lane.jobs.toList()
        var n = 0
        for (job in targets) {
            lane.jobs.remove(job)
            job.cancel()
            n++
            cancelledJobs.incrementAndGet()
        }
        return n
    }

    /**
     * 繁忙回复合批裁决（CAS 抢占，全表只会有一个赢家）。
     * @param cooldownMillis `runtime.busyReplyCooldownSeconds * 1000`。
     * @return true ⇒ 本次**应该**回 `agent_busy`；false ⇒ 窗口内已回过，静默抑制。
     */
    fun claimBusyReply(chatId: String, cooldownMillis: Long): Boolean {
        val lane = laneOf(chatId)
        val now = clock()
        val window = cooldownMillis.coerceAtLeast(0L)
        while (true) {
            val last = lane.lastBusyReplyMillis.get()
            if (last != 0L && now - last < window) {
                busySuppressed.incrementAndGet()
                return false
            }
            if (lane.lastBusyReplyMillis.compareAndSet(last, now)) {
                busyClaims.incrementAndGet()
                return true
            }
        }
    }

    /** 只读快照（UI「会话」页 / 测试断言；不暴露内部可变对象）。 */
    fun snapshot(): List<LaneSnapshot> =
        lanes.values.map { LaneSnapshot(it.chatId, it.inFlight.get(), it.jobs.size, it.lastBusyReplyMillis.get()) }

    /** 清空全部 lane（stop() 时；幂等。在途计数由 cancelAll 后的 task finally 归还，这里直接丢弃）。 */
    fun clear() {
        lanes.clear()
    }

    /**
     * LRU 逐出：表满时移除**最久未触碰且 inFlight==0** 的 lane，直到 `size <= maxLanes`。
     * 🔴 全表在途时不逐出任何 lane，改为累加 [overflowCreates]（宁可短暂超额，不可拒绝真实消息）。
     */
    private fun evictIfNeeded() {
        if (lanes.size <= maxLanes) return
        while (lanes.size > maxLanes) {
            var oldest: ChatLane? = null
            var oldestTouch = Long.MAX_VALUE
            for (candidate in lanes.values) {
                if (candidate.inFlight.get() != 0 || candidate.jobs.isNotEmpty()) continue
                val touch = candidate.lastTouchMillis.get()
                if (touch < oldestTouch) {
                    oldestTouch = touch
                    oldest = candidate
                }
            }
            val victim = oldest
            if (victim == null) {
                overflowCreates.incrementAndGet()
                return
            }
            // 双参 remove：只有当映射仍指向同一实例时才移除（避免与并发 laneOf 抢建的新实例互踩）。
            if (lanes.remove(victim.chatId, victim)) evictions.incrementAndGet()
        }
    }

    /** lane 的只读投影。 */
    data class LaneSnapshot(val chatId: String, val inFlight: Int, val jobCount: Int, val lastBusyReplyMillis: Long)

    companion object {
        /** lane 表上限（方案表逐字：256）。 */
        const val MAX_LANES: Int = 256
    }
}
