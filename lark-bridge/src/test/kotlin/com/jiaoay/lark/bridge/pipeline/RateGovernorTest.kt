// M4-T7 测试清单 ⑩：RateGovernorTest（🔴 behavior-contract §7 的 NF-05 归本轮）。
// 覆盖：每 chat 4rps / 全局 40rps 令牌桶（虚拟时间）、超限**挂起而非丢弃**、429/11232 退避阶梯、
//       `x-ogw-ratelimit-reset` 头感知、chat 桶 drain 与 LRU 逐出、🔴「不阻塞 intake」端到端断言。
// 🔴 消解 D2：旧实现出站零频控零退避；网关侧 `RetryConfig.maxAttempts=1` 已关掉 SDK 盲退避 ⇒ 退避全在本类。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.gateway.GatewayLimits
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * 虚拟时钟 + 记录型 sleeper。
 *
 * 🔴 `sleeper` 把「等待」翻译成「虚拟时间前进」，于是令牌桶的补充完全由测试掌控：
 *    真实耗时 O(微秒)，断言却是精确到毫秒的桶行为。`yield()` 保留挂起点语义
 *    （acquire 确实是**挂起**而不是忙等自旋）。
 */
private class VirtualTime {
    val now = AtomicLong(0L)
    val sleeps = CopyOnWriteArrayList<Long>()

    fun clock(): Long = now.get()

    suspend fun sleep(millis: Long) {
        sleeps.add(millis)
        now.addAndGet(millis)
        yield()
    }

    fun advance(millis: Long) {
        now.addAndGet(millis)
    }
}

class RateGovernorTest {

    private fun governor(
        vt: VirtualTime,
        perChatRps: Int = GatewayLimits.perChatRps,
        globalRps: Int = GatewayLimits.globalRps,
    ): RateGovernor = RateGovernor(
        perChatRps = perChatRps,
        globalRps = globalRps,
        clock = vt::clock,
        sleeper = { vt.sleep(it) },
    )

    // ------------------------------------------------------------------ 阈值来源（🔴 不复制数值）

    @Test
    fun `🔴 阈值直接读 GatewayLimits 公开常量（每 chat 4 与全局 40，不复制数值）`() {
        // 🔴 gateway-contract §7 / §15 裁定 10：阈值只存在于 :lark-gateway 的 GatewayLimits，本模块不得复制。
        assertEquals(4, GatewayLimits.perChatRps)
        assertEquals(40, GatewayLimits.globalRps)

        val vt = VirtualTime()
        val governor = RateGovernor(clock = vt::clock, sleeper = { vt.sleep(it) }) // 全默认 ⇒ 读 GatewayLimits

        // 每 chat：前 perChatRps 次是「1 秒突发额度」，立即放行
        repeat(GatewayLimits.perChatRps) { runBlocking { governor.acquire("oc_a") } }
        assertEquals(0L, governor.waits, "默认桶容量 = 1 秒突发量 ⇒ 前 4 次不该等")
        assertEquals(GatewayLimits.perChatRps.toLong(), governor.acquired)

        // 第 perChatRps+1 次 ⇒ 透支 1 个令牌，需等 1000/perChatRps 毫秒
        runBlocking { governor.acquire("oc_a") }
        assertEquals(1L, governor.waits)
        assertEquals(listOf(1000L / GatewayLimits.perChatRps), vt.sleeps, "4rps ⇒ 每 250ms 补 1 个令牌")
        assertEquals(1000L / GatewayLimits.perChatRps, governor.waitMillisTotal)

        // 全局：另起一个 governor，前 globalRps 次立即、第 +1 次等 1000/globalRps
        val vt2 = VirtualTime()
        val global = RateGovernor(clock = vt2::clock, sleeper = { vt2.sleep(it) })
        repeat(GatewayLimits.globalRps) { runBlocking { global.acquire("oc_$it") } }
        assertEquals(0L, global.waits, "40 个不同 chat 各 1 条 ⇒ 只吃全局桶的突发额度")
        runBlocking { global.acquire("oc_overflow") }
        assertEquals(1L, global.waits)
        assertEquals(listOf(1000L / GatewayLimits.globalRps), vt2.sleeps, "40rps ⇒ 每 25ms 补 1 个令牌")
    }

    // ------------------------------------------------------------------ 令牌桶行为（虚拟时间）

    @Test
    fun `每 chat 令牌耗尽后按 250ms 步长挂起（虚拟时间，4rps）`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt)

        repeat(4) { governor.acquire("oc_a") }
        assertEquals(0L, governor.waits)
        assertEquals(0L, vt.now.get(), "突发额度内零等待 ⇒ 虚拟时间不前进")

        // 稳态：每来一条都要等一个令牌的补充时间（250ms）
        repeat(3) { governor.acquire("oc_a") }
        assertEquals(listOf(250L, 250L, 250L), vt.sleeps)
        assertEquals(3L, governor.waits)
        assertEquals(750L, governor.waitMillisTotal)
        assertEquals(750L, vt.now.get())
        assertEquals(7L, governor.acquired, "🔴 挂起之后**照样放行**，一条都没丢")

        // 另一个 chat 有自己独立的桶 ⇒ 不受 oc_a 透支影响
        governor.acquire("oc_b")
        assertEquals(3L, governor.waits, "chat 桶彼此独立")
        assertEquals(2, governor.chatBucketCount)
    }

    @Test
    fun `全局桶跨 chat 共享（40 次突发后第 41 次等 25ms）`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt)

        // 10 个 chat × 4 条 = 40 条：每个 chat 桶刚好用尽但不透支，全局桶也刚好用尽
        repeat(10) { c -> repeat(4) { governor.acquire("oc_$c") } }
        assertEquals(0L, governor.waits, "40 条 = 全局 1 秒突发额度 ⇒ 全部立即放行")
        assertEquals(40L, governor.acquired)

        // 第 41 条（新 chat，chat 桶是满的）⇒ 瓶颈只可能是全局桶
        governor.acquire("oc_fresh")
        assertEquals(1L, governor.waits)
        assertEquals(listOf(25L), vt.sleeps, "全局 40rps ⇒ 25ms 补 1 个令牌")
        assertEquals(11, governor.chatBucketCount)
    }

    @Test
    fun `chatId 为 null 时只受全局桶约束且不建 chat 桶`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt)

        repeat(GatewayLimits.globalRps) { governor.acquire(null) }
        assertEquals(0L, governor.waits)
        assertEquals(0, governor.chatBucketCount, "null chatId 不得污染 chat 桶表（防无界增长）")

        governor.acquire(null)
        assertEquals(listOf(25L), vt.sleeps)
        assertEquals(0, governor.chatBucketCount)
    }

    @Test
    fun `🔴 超限是挂起不是丢弃（20 次调用最终全部放行）`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt)

        repeat(20) { governor.acquire("oc_a") }

        assertEquals(20L, governor.acquired, "20 次调用 ⇒ 20 次放行，零丢弃零异常")
        assertEquals(16L, governor.waits, "前 4 次吃突发额度，后 16 次各等一个令牌")
        assertEquals(16L * 250L, governor.waitMillisTotal)
        assertEquals(16L * 250L, vt.now.get(), "虚拟时间被推迟了 4 秒 ⇒ 出站只是被推后，不是被扔掉")
    }

    // ------------------------------------------------------------------ guard（放行后才发起 SDK 调用）

    @Test
    fun `guard 保证 block 在 acquire 之后执行且返回值透传`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt)
        val order = CopyOnWriteArrayList<String>()

        // 先把 chat 桶打空，让下一次 guard 必然经历一次挂起
        repeat(4) { governor.acquire("oc_a") }

        val result = governor.guard(chatId = "oc_a", block = {
            order.add("block")
            "sent"
        })

        assertEquals("sent", result, "guard 必须把 block 的返回值原样透出")
        assertEquals(listOf("block"), order)
        assertEquals(1L, governor.waits, "block 之前必须先经历 acquire 的挂起")
        assertEquals(5L, governor.acquired)
        // 🔴 guard 默认 onRateLimited 返回 null ⇒ 视为成功 ⇒ 退避阶梯被清零
        assertEquals(0L, governor.backoffs)
        assertFalse(governor.isBackingOff)
    }

    @Test
    fun `guard 的 onRateLimited 返回头值则进入退避，返回 null 则视为成功`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt)

        // ① 返回 resetSec=7 ⇒ 按头值退避 7000ms
        governor.guard(chatId = "oc_a", block = { "limited" }, onRateLimited = { 7 })
        assertEquals(1L, governor.backoffs)
        assertEquals(7_000L, governor.backoffRemainingMillis)
        assertTrue(governor.isBackingOff)

        // ② 返回 null ⇒ notifySuccess ⇒ 退避窗口与阶梯一起清零
        governor.guard(chatId = "oc_a", block = { "ok" }, onRateLimited = { null })
        assertEquals(1L, governor.backoffs, "成功不增加 backoffs")
        assertEquals(0L, governor.backoffRemainingMillis)
        assertFalse(governor.isBackingOff)

        // ③ 阶梯确实被 ② 清零了：再限流一次 ⇒ 回到 1s 而不是接着往上翻
        governor.notifyRateLimited(null, null)
        assertEquals(1_000L, governor.backoffRemainingMillis, "指数退避必须从 1s 重新开始")
    }

    // ------------------------------------------------------------------ 退避阶梯（D2 的另一半）

    @Test
    fun `固定退避阶梯 1s→2s→4s→8s→16s→上限 30s`() {
        val vt = VirtualTime()
        val governor = governor(vt)
        assertEquals(1_000L, RateGovernor.BACKOFF_BASE_MILLIS)
        assertEquals(30_000L, RateGovernor.BACKOFF_MAX_MILLIS)

        val remaining = mutableListOf<Long>()
        repeat(7) {
            governor.notifyRateLimited(null, null)
            remaining += governor.backoffRemainingMillis
        }
        // 🔴 gateway-contract §11.8：`1s → 2s → 4s`，上限 30s（32s 被夹到 30s）
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), remaining)
        assertEquals(7L, governor.backoffs)
        assertTrue(governor.isBackingOff)
    }

    @Test
    fun `reset 头存在则按头值精确退避且不消耗阶梯`() {
        val vt = VirtualTime()
        val governor = governor(vt)

        // ① 头存在（SDK 完全不读该头 ⇒ 由本网关侧透传成 GatewaySignal.RateLimited.resetSec，读本类）
        governor.notifyRateLimited("oc_a", 7)
        assertEquals(7_000L, governor.backoffRemainingMillis)
        assertEquals(1L, governor.backoffs)

        // ② 头值不推进阶梯：紧接着一次「无头」限流仍是 step0 = 1s
        governor.notifyRateLimited(null, null)
        assertEquals(1_000L, governor.backoffRemainingMillis, "有头退避不参与指数阶梯")

        // ③ 头值非法（0 / 负数）⇒ 退回固定阶梯（🔴 S6 真机已确认 x-ogw-ratelimit-reset 头不存在 ⇒ 无头即走这里）
        governor.notifyRateLimited(null, 0)
        assertEquals(2_000L, governor.backoffRemainingMillis)
        governor.notifyRateLimited(null, -3)
        assertEquals(4_000L, governor.backoffRemainingMillis)
        assertEquals(4L, governor.backoffs)
    }

    // 🔴 S6 真机实测固化（§16 / build/s6-ratelimit.log）：30 条文本 @约 2.3QPS，x-ogw-ratelimit-reset 全程缺失（headerSeen=false）
    //    ⇒ resetHeaderSeconds 恒为 null ⇒ **固定窗口阶梯退避是实际生效路径**。防御性保留「有头按头值退避」分支（未来飞书加回该头仍能用）。
    @Test
    fun `S6 实测头缺失 headerSeen false 时走固定窗口退避`() {
        val vt = VirtualTime()
        val governor = governor(vt)
        // 模拟 S6 真机：每次限流回传的 resetHeaderSeconds 都是 null（头不存在）
        val headerSeen = false
        val resetHeaderSeconds: Int? = if (headerSeen) 7 else null
        val remaining = mutableListOf<Long>()
        repeat(4) {
            governor.notifyRateLimited("oc_a", resetHeaderSeconds)
            remaining += governor.backoffRemainingMillis
        }
        // 走固定阶梯 1s→2s→4s→8s（而非按头值精确退避），证明「头缺失 ⇒ 固定窗口退避」是实际生效路径
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), remaining)
        assertEquals(4L, governor.backoffs)
        assertTrue(governor.isBackingOff)
    }

    @Test
    fun `退避窗口内 acquire 会挂起到窗口结束`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt)

        governor.notifyRateLimited(null, 5) // 头值 5s
        assertEquals(5_000L, governor.backoffRemainingMillis)

        governor.acquire("oc_a") // 令牌充足，唯一的阻塞源是退避窗口

        assertEquals(listOf(5_000L), vt.sleeps, "退避窗口必须整段等完，不能提前发")
        assertEquals(5_000L, governor.waitMillisTotal)
        assertEquals(1L, governor.acquired)
        assertEquals(0L, governor.backoffRemainingMillis, "虚拟时间已越过窗口 ⇒ 退避自然结束")
        assertFalse(governor.isBackingOff)
    }

    @Test
    fun `notifySuccess 与 resetBackoff 清空退避窗口与阶梯`() {
        val vt = VirtualTime()
        val governor = governor(vt)

        repeat(3) { governor.notifyRateLimited(null, null) }
        assertEquals(4_000L, governor.backoffRemainingMillis)

        governor.notifySuccess()
        assertEquals(0L, governor.backoffRemainingMillis)
        assertFalse(governor.isBackingOff)
        governor.notifyRateLimited(null, null)
        assertEquals(1_000L, governor.backoffRemainingMillis, "阶梯归零")

        repeat(3) { governor.notifyRateLimited(null, null) }
        governor.resetBackoff() // stop() 与重连后调用，避免旧窗口拖慢新连接
        assertEquals(0L, governor.backoffRemainingMillis)
        governor.notifyRateLimited(null, null)
        assertEquals(1_000L, governor.backoffRemainingMillis)
    }

    @Test
    fun `notifyRateLimited 会 drain 对应 chat 桶（消除存量突发）`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt, perChatRps = 4, globalRps = 1_000)

        governor.acquire("oc_d") // 桶里还剩 3 个存量令牌
        governor.notifyRateLimited("oc_d", null) // drain + 退避 1s
        // 🔴 立刻 notifySuccess 把退避窗口清掉，只留下「桶已被 drain」这一个可观测差异：
        //    退避窗口 ≥1s 而桶补充 1s 恰好等于容量，故窗口结束时两种实现的桶都是满的，drain 不可观测。
        governor.notifySuccess()

        governor.acquire("oc_d")
        assertEquals(listOf(250L), vt.sleeps, "drain 后存量突发被清空 ⇒ 必须等一个令牌的补充时间")
        assertEquals(1L, governor.waits)

        // 对照组：没被 drain 过的 chat 桶仍有存量突发
        governor.acquire("oc_e")
        assertEquals(1L, governor.waits, "oc_e 桶是满的 ⇒ 不增加 waits")
    }

    // ------------------------------------------------------------------ chat 桶规模治理

    @Test
    fun `pruneIdleBuckets 逐出最久未用的 chat 桶且幂等`() = runBlocking {
        val vt = VirtualTime()
        val governor = governor(vt)
        assertEquals(512, RateGovernor.MAX_CHAT_BUCKETS)

        repeat(10) { i ->
            vt.advance(1_000L) // 让每个桶的 lastTouch 严格递增，逐出顺序才可判定
            governor.acquire("oc_$i")
        }
        assertEquals(10, governor.chatBucketCount)

        governor.pruneIdleBuckets(maxBuckets = 100) // size <= max ⇒ 不动作
        assertEquals(10, governor.chatBucketCount, "未超上限时不得逐出（会话数远小于活跃数时零开销）")

        governor.pruneIdleBuckets(maxBuckets = 4)
        assertEquals(4, governor.chatBucketCount, "超出上限 ⇒ 逐出最久未用的 6 个")
        governor.pruneIdleBuckets(maxBuckets = 4)
        assertEquals(4, governor.chatBucketCount, "幂等")

        // 逐出后功能不受影响：新 chat 重建桶，老 chat 也能继续用
        governor.acquire("oc_new")
        assertEquals(5, governor.chatBucketCount)
        governor.acquire("oc_0")
        assertEquals(6, governor.chatBucketCount)
        assertEquals(12L, governor.acquired)

        // 默认参数 = MAX_CHAT_BUCKETS ⇒ 6 个桶时不动作
        governor.pruneIdleBuckets()
        assertEquals(6, governor.chatBucketCount)
    }

    // ------------------------------------------------------------------ 零锁与并发不超发

    @Test
    fun `🔴 并发 acquire 不超发（预订式令牌桶零锁下的公平性）`() = runBlocking {
        val governor = RateGovernor(perChatRps = 20, globalRps = 1_000) // 真实时钟 + 真实 delay

        val started = System.nanoTime()
        val jobs = (1..4).map {
            launch(Dispatchers.Default) { repeat(10) { governor.acquire("oc_hot") } }
        }
        jobs.forEach { it.join() }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L

        assertEquals(40L, governor.acquired, "40 次调用全部放行（挂起而非丢弃）")
        assertTrue(governor.waits >= 1L, "容量 20 ⇒ 后 20 次必然挂起，实际 waits=${governor.waits}")
        // 🔴 不超发的硬证据：桶容量 20、补充 20 个每秒 ⇒ 40 条最少要 (40-20)/20 = 1 秒。
        //    若 CAS 预订失效（并发下重复放行），耗时会塌到几十毫秒。
        assertTrue(elapsedMillis >= 700L, "40 条在 20rps 下不可能快于 1 秒，实测 ${elapsedMillis}ms")
        assertTrue(governor.waitMillisTotal >= 700L, "累计挂起量与真实耗时同量级：${governor.waitMillisTotal}")
    }

    @Test
    fun `🔴 RateGovernor 源文件零锁（归一化后无 synchronized 无 Mutex 无 Volatile var）`() {
        val src = java.io.File(testRepoRoot(), "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/RateGovernor.kt")
        assertTrue(src.isFile, "源文件必须存在：$src")
        val normalized = stripCommentsAndStrings(src.readText())

        assertEquals(0, Regex("\\bsynchronized\\b").findAll(normalized).count(), "出站频控不得用监视器锁")
        assertEquals(0, Regex("\\bMutex\\b").findAll(normalized).count(), "出站频控不得用协程互斥量")
        assertEquals(0, Regex("@Volatile\\s+var").findAll(normalized).count(), "CDS §14-4：状态一律 AtomicReference CAS")
        assertTrue(normalized.contains("AtomicReference"), "预订式令牌桶必须用 CAS")
        assertTrue(normalized.contains("ConcurrentHashMap"), "chat 桶表必须用并发 map 而不是加锁 map")
        // 🔴 阈值来源唯一：只从 GatewayLimits 读，文件里不得出现复制的裸数值 4 / 40 作为默认值
        assertTrue(normalized.contains("GatewayLimits.perChatRps"))
        assertTrue(normalized.contains("GatewayLimits.globalRps"))
    }

    // ------------------------------------------------------------------ 🔴 NF-05 端到端：不阻塞 intake

    @Test
    fun `🔴 NF-05 不阻塞 intake：出站被频控挂起时 intake 单协程仍推进`() {
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(SystemRoute())),
            kinds = systemKinds(),
            replyMode = ReplyMode.GATEWAY, // 真 GatewayReplySink + 真 RateGovernor + SyntheticGateway
        ).use { h ->
            h.startIntake()

            // 40 条同 chat 的 ping ⇒ 每 chat 4rps ⇒ 出站至少要 9 秒才发得完
            val sent = (1..40).map { h.queue.trySend(msg(messageId = "om_rg$it", chatId = "oc_rate", text = "ping")) }
            assertEquals(40, sent.count { it }, "队列容量 512 ⇒ 40 条不该被挤掉")

            // 🔴 核心断言 ①：intake 单协程先把 40 条全部消费掉（此时出站还堵在令牌桶里）
            assertTrue(
                h.awaitUntil(8_000L) { h.intake.processedCount >= 40L },
                "intake 单协程必须照常推进（processed=${h.intake.processedCount}）",
            )
            assertEquals(40L, h.counters.intakeReceived)
            assertEquals(40L, h.counters.submitted, "40 条 LIGHT 全部进了 ② 准入")
            assertEquals(0L, h.queue.pending, "队列已排空 ⇒ intake 没有卡在出站上")

            // 🔴 核心断言 ②：出站确实被频控挂起了，而**此刻 intake 早已收工** ⇒ 挂起没有回压到 intake
            assertTrue(
                h.awaitUntil(8_000L) { h.rateGovernor.waits >= 1L },
                "40 条同 chat 出站必须触发每 chat 4rps 的频控挂起：waits=${h.rateGovernor.waits}",
            )
            assertEquals(40L, h.intake.processedCount, "🔴 出站挂起期间 intake 不回压、不等待，40 条已处理完")

            // 🔴 核心断言 ③：频控是**推迟**不是丢弃 —— 放行数远小于 40，且没有任何入站丢弃
            assertTrue(
                h.rateGovernor.acquired < 40L,
                "4rps 下 40 条不可能瞬间全部放行，实际放行 ${h.rateGovernor.acquired}",
            )
            assertEquals(0L, h.counters.intakeQueueDropped, "频控不得造成任何入站丢弃")
            assertEquals(0L, h.counters.replyFailures, "频控挂起不是失败")

            // 🔴 再等一会：出站确实在继续放行（单调递增），证明是「推迟」而不是「卡死」
            val before = h.gateway.recordedSends.size
            assertTrue(h.awaitUntil(3_000L) { h.gateway.recordedSends.size > before }, "令牌补充后出站必须继续")
            assertTrue(h.rateGovernor.acquired >= h.gateway.recordedSends.size.toLong())

            // close() 取消 runtime ⇒ 在途出站协程被取消，测试不会等满 9 秒
            assertFalse(h.runtime.job.isCompleted, "收尾前 runtime 仍在跑（由 close 取消，不是自然结束）")
        }
    }
}
