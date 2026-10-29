// 出站频控。
// 网关侧 RetryConfig.maxAttempts=1 已关掉 SDK 盲退避，
//     ⇒ 退避**全部由本类承担**，且必须在 net 池挂起，🔴 绝不阻塞 intake。
// 阈值直接读 :lark-gateway 的 GatewayLimits 公开常量，不复制数值。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.GatewayLimits
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.delay

/**
 * 令牌桶频控器：每 chat [GatewayLimits.perChatRps]（4）rps + 全局 [GatewayLimits.globalRps]（40）rps。
 *
 * ## 语义（挂起等待，不是丢弃）
 * [acquire] 在令牌不足时**挂起**到令牌可用为止（`sleeper` 默认 `delay`，跑在调用方的 `net` dispatcher 上）
 * ⇒ 出站永不因频控被丢弃，只是被推迟；intake 单协程**不参与**出站，故不受影响。
 *
 * ## 负令牌桶（预订式）
 * 采用「允许透支、按透支量计算等待」的经典令牌桶变体：`reserve()` 用 CAS 把桶推到 `tokens-1`
 * （可为负），返回值 = 让桶回到 0 所需的毫秒数。这样并发调用者各自拿到**互不重叠**的等待区间，
 * 既不会超发（透支必须偿还），也不需要任何锁。
 * 🔴 零监视器锁、零协程互斥量：状态用 [AtomicReference] CAS，chat 桶表用 [ConcurrentHashMap]
 *    （intake 零锁纪律）。
 *
 * ## 退避与令牌必须分步等（`RateGovernorTest` 钉住的死循环缺陷）
 * 「预订 + 偿还」意味着**一次出站只预订一次**。若把退避窗口与令牌预订塞进同一个 `while(true)`
 * 重试循环，每轮重试都会再 `reserve()` 一次：补充量恰好只够偿还上一轮的透支，桶永远回不到 0
 * ⇒ [acquire] 永不返回（实测：单条 chat 第 5 次出站即死循环，出站链路整体卡死）。
 * 故本实现是「① 等退避 → ② 预订一次令牌并偿还 → ③ 偿还期间若又限流则补等退避」，绝无重复预订。
 *
 * ## 429 / 11232 退避
 * [notifyRateLimited] 由调用方（`GatewayReplySink`）在收到 `GatewaySignal.RateLimited` 或
 * `SendResult.error == RateLimited` 时调用：
 * - `x-ogw-ratelimit-reset` 头**存在** ⇒ 按头值精确退避（秒）；
 * - 头**缺失** ⇒ 固定阶梯 `1s → 2s → 4s → …`，上限 [BACKOFF_MAX_MILLIS]（30s）。
 * **真机实测**：30 条文本 @约 2.3QPS，`x-ogw-ratelimit-reset` **全程缺失**
 *    （headerSeen=false，零 429 / 零 11232）⇒ **固定窗口阶梯退避是实际生效路径**。SDK 完全不读该头，网关侧只把它透传成
 *    `GatewaySignal.RateLimited.resetSec`（当前恒为 null），读与退避在本类。
 * 保留 `resetHeaderSeconds` 参数与「头存在则按头值退避」分支属**防御性设计**：若飞书未来加回该头仍能用，无需改代码。
 *    退避算法本身不因实测结论改动（`RateGovernorTest` 已钉住「resetHeaderSeconds=null 走固定阶梯」）。
 *
 * @param clock 可注入（测试用 `scheduler.currentTime`，虚拟时间驱动）。
 * @param sleeper 可注入（测试断言「挂起而非丢弃」时记录调用）。
 */
class RateGovernor(
    private val perChatRps: Int = GatewayLimits.perChatRps,
    private val globalRps: Int = GatewayLimits.globalRps,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    private val globalBucket = TokenBucket(globalRps.toDouble(), clock)
    private val chatBuckets = ConcurrentHashMap<String, TokenBucket>()

    /** 全局限流退避截止时刻（毫秒）；0 表示无退避。 */
    private val globalBackoffUntil = AtomicLong(0L)

    /** 连续限流次数（决定固定阶梯的指数）；成功出站后归零。 */
    private val globalBackoffStep = AtomicInteger(0)

    private val waitCount = AtomicLong(0)
    private val waitMillisSum = AtomicLong(0)
    private val backoffCount = AtomicLong(0)
    private val acquiredCount = AtomicLong(0)

    // ------------------------------------------------------------------ 可观测计数

    /** 因令牌不足而挂起的次数（0 ⇒ 从未触发频控）。 */
    val waits: Long get() = waitCount.get()

    /** 累计挂起毫秒数（虚拟时间下即 scheduler 推进量）。 */
    val waitMillisTotal: Long get() = waitMillisSum.get()

    /** 因 429/11232 进入退避的次数。 */
    val backoffs: Long get() = backoffCount.get()

    /** 成功放行的次数（= 出站发起次数；NF-05「RateGovernor 放行后才发起 SDK 调用」的分母）。 */
    val acquired: Long get() = acquiredCount.get()

    /** 当前是否处于退避窗口内。 */
    val isBackingOff: Boolean get() = clock() < globalBackoffUntil.get()

    /** 退避窗口剩余毫秒（无退避为 0）。 */
    val backoffRemainingMillis: Long get() = (globalBackoffUntil.get() - clock()).coerceAtLeast(0L)

    // ------------------------------------------------------------------ 主入口

    /**
     * 取得一次出站许可（全局 + 该 chat 双桶）。挂起直到两桶都放行且不在退避窗口内。
     * @param chatId null ⇒ 只受全局桶约束（如非会话级出站）。
     */
    suspend fun acquire(chatId: String?) {
        // ① 先等退避窗口过去（这一步绝不预订令牌，理由见类 KDoc）。
        awaitBackoff()

        // ② 预订**一次**令牌：全局桶 + 该 chat 桶，取两者偿还时间的较大值。
        val globalWait = globalBucket.reserve()
        val chatWait = chatId?.let { bucketFor(it).reserve() } ?: 0L
        val wait = maxOf(globalWait, chatWait)
        if (wait > 0L) {
            waitCount.incrementAndGet()
            waitMillisSum.addAndGet(wait)
            sleeper(wait)
            // ③ 偿还期间可能又来了新的限流通知 ⇒ 补等退避（仍然不预订令牌）。
            awaitBackoff()
        }
        acquiredCount.incrementAndGet()
    }

    /**
     * 等退避窗口过去。🔴 **绝不预订令牌**：退避（服务端 429/11232 惩罚）与令牌桶（本地平滑）
     * 是两套独立约束，必须分步等待，否则每轮重试都会多扣一个令牌而永远等不到放行。
     */
    private suspend fun awaitBackoff() {
        while (true) {
            val backoffWait = (globalBackoffUntil.get() - clock()).coerceAtLeast(0L)
            if (backoffWait <= 0L) return
            waitCount.incrementAndGet()
            waitMillisSum.addAndGet(backoffWait)
            sleeper(backoffWait)
        }
    }

    /**
     * 🔴 **「RateGovernor 放行后才发起 SDK 调用」**（NF-05 追加断言）的唯一合法形态：
     * 把 SDK 调用包进 [block]，本方法保证 `block` 一定在 `acquire` 之后执行。
     * 失败（`SendResult.error == RateLimited` 或收到 429/11232）时由调用方回调 [onRateLimited]。
     */
    suspend fun <T> guard(
        chatId: String?,
        block: suspend () -> T,
        onRateLimited: (T) -> Int? = { null },
    ): T {
        acquire(chatId)
        val result = block()
        val resetSec = onRateLimited(result)
        if (resetSec != null) notifyRateLimited(chatId, resetSec) else notifySuccess()
        return result
    }

    /**
     * 进入退避。
     * @param resetHeaderSeconds `x-ogw-ratelimit-reset` 头值（秒）；null/≤0 ⇒ 走固定阶梯。
     */
    fun notifyRateLimited(chatId: String?, resetHeaderSeconds: Int?) {
        val now = clock()
        val millis = if (resetHeaderSeconds != null && resetHeaderSeconds > 0) {
            resetHeaderSeconds * 1000L
        } else {
            val step = globalBackoffStep.getAndIncrement()
            min(BACKOFF_BASE_MILLIS * 2.0.pow(step.toDouble()), BACKOFF_MAX_MILLIS.toDouble()).toLong()
        }
        globalBackoffUntil.set(now + millis)
        backoffCount.incrementAndGet()
        chatId?.let { bucketFor(it).drain() }
    }

    /** 出站成功 ⇒ 重置退避阶梯（指数退避必须从 1s 重新开始）。 */
    fun notifySuccess() {
        globalBackoffStep.set(0)
        globalBackoffUntil.set(0L)
    }

    /** 手动清空退避（stop()/重连后调用，避免旧退避窗口拖慢新连接）。 */
    fun resetBackoff() = notifySuccess()

    private fun bucketFor(chatId: String): TokenBucket =
        chatBuckets.computeIfAbsent(chatId) { TokenBucket(perChatRps.toDouble(), clock) }

    /** chat 桶数量（LRU 逐出由 `ConcurrentHashMap` 的规模上限约束，见 [pruneIdleBuckets]）。 */
    val chatBucketCount: Int get() = chatBuckets.size

    /** 清理长期未用的 chat 桶（会话数远大于活跃数时防无界增长）；由 metrics 协程 1Hz 调用。 */
    fun pruneIdleBuckets(maxBuckets: Int = MAX_CHAT_BUCKETS) {
        if (chatBuckets.size <= maxBuckets) return
        val now = clock()
        val oldest = chatBuckets.entries.sortedBy { it.value.lastTouchMillis(now) }.take(chatBuckets.size - maxBuckets)
        oldest.forEach { chatBuckets.remove(it.key, it.value) }
    }

    companion object {
        /** 固定退避阶梯基数（gateway-contract.md §11.8：`1s → 2s → 4s`）。 */
        const val BACKOFF_BASE_MILLIS: Long = 1_000L

        /** 退避上限（gateway-contract.md §11.8：30s）。 */
        const val BACKOFF_MAX_MILLIS: Long = 30_000L

        /** chat 桶上限（超出按最久未用逐出）。 */
        const val MAX_CHAT_BUCKETS: Int = 512
    }
}

/**
 * 单个令牌桶（负令牌 / 预订式）。
 * 零锁：[reserve] 全程 CAS 自旋；[state] 是不可变快照，故 CAS 失败即重读重试。
 */
private class TokenBucket(
    /** 每秒补充的令牌数（= rps）。 */
    private val ratePerSecond: Double,
    private val clock: () -> Long,
) {
    /** 桶容量 = 1 秒的突发量（4 rps ⇒ 允许瞬时 4 条，与飞书「每秒 4 条」的实际判定一致）。 */
    private val capacity: Double = ratePerSecond.coerceAtLeast(1.0)

    /** 每毫秒补充量。 */
    private val refillPerMillis: Double = ratePerSecond / 1000.0

    private data class State(val tokens: Double, val atMillis: Long)

    private val state = AtomicReference(State(capacity, clock()))

    /**
     * 预订一个令牌。
     * @return 0 ⇒ 立即可发；>0 ⇒ 需等待的毫秒数（透支偿还时间）。
     */
    fun reserve(): Long {
        while (true) {
            val cur = state.get()
            val now = clock()
            val elapsed = (now - cur.atMillis).coerceAtLeast(0L)
            val refilled = (cur.tokens + elapsed * refillPerMillis).coerceAtMost(capacity)
            val next = State(refilled - 1.0, now)
            if (state.compareAndSet(cur, next)) {
                if (next.tokens >= 0.0) return 0L
                // 透支 ⇒ 等待「桶回到 0」所需时间；至少 1ms，避免忙等。
                return ceil((-next.tokens) / refillPerMillis).toLong().coerceAtLeast(1L)
            }
        }
    }

    /** 退避时清空桶（避免退避结束后瞬时突发把限流再次触发）。 */
    fun drain() {
        while (true) {
            val cur = state.get()
            if (state.compareAndSet(cur, State(0.0, clock()))) return
        }
    }

    /** 最近一次活动时刻（用于 LRU 逐出）。 */
    fun lastTouchMillis(now: Long): Long = state.get().atMillis.coerceAtMost(now)
}
