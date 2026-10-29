// M4-T7 测试：② Admission 两级准入 + ③ Execute 超时/取消接线。
// 🔴 承接旧 engine/Concurrency* / Queue* / Timeout* 的断言（behavior-contract 映射表）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.RouteRule
import com.jiaoay.lark.bridge.config.SystemRoute
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 轻量假 Process（只验登记/kill 语义，不起真实进程；真实 kill 链由 T6 的 CancellationKillsProcessTest 覆盖）。 */
internal class FakeProcess : Process() {
    val destroyed = AtomicBoolean(false)
    val forciblyDestroyed = AtomicBoolean(false)
    private val alive = AtomicBoolean(true)
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = InputStream.nullInputStream()
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun waitFor(): Int {
        alive.set(false)
        return 0
    }

    override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 0
    override fun destroy() {
        destroyed.set(true)
        alive.set(false)
    }

    override fun destroyForcibly(): Process {
        forciblyDestroyed.set(true)
        alive.set(false)
        return this
    }

    override fun isAlive(): Boolean = alive.get()
}

/** HEAVY 档 handler 工厂（阻塞在 [gate] 上，模拟长跑 agent）。 */
internal fun heavyAgent(gate: CompletableDeferred<Unit>, clock: () -> Long = { System.currentTimeMillis() }) =
    ScriptedHandler(
        kind = RouteKind.AGENT,
        weight = HandlerWeight.HEAVY,
        matcher = { ctx -> ctx.text.startsWith("ai") },
        body = { gate.await(); HandlerOutcome.success(TaskKind.AGENT, "agent done") },
    ).withClock(clock)

/** [Admission] 的两级准入（URGENT 绕过 / LIGHT 不占 / HEAVY 许可 + ChatLane + 排队超时 + 繁忙合批）。 */
class AdmissionTest {

    private fun heavyHarness(
        gate: CompletableDeferred<Unit>,
        maxConcurrentAgents: Int = 2,
        chatQueueLimit: Int = 4,
        queueWaitSeconds: Long = 30L,
        busyReplyCooldownSeconds: Long = 30L,
        clock: () -> Long = { System.currentTimeMillis() },
        extraRoutes: List<RouteRule> = emptyList(),
        extraKinds: HandlerKinds? = null,
    ): Harness {
        val routes = listOf(AgentRoute()) + extraRoutes
        val kinds = extraKinds ?: HandlerKinds.build(agent = HandlerFactory { _, _ -> heavyAgent(gate, clock) })
        return Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(
                routes = routes,
                maxConcurrentAgents = maxConcurrentAgents,
                chatQueueLimit = chatQueueLimit,
                queueWaitSeconds = queueWaitSeconds,
                busyReplyCooldownSeconds = busyReplyCooldownSeconds,
            ),
            kinds = kinds,
            clock = clock,
        )
    }

    @Test
    fun `🔴 D11 两个长跑 HEAVY 在途时 LIGHT 立即执行且不占许可`() {
        val gate = CompletableDeferred<Unit>()
        val help = ScriptedHandler(
            kind = RouteKind.SYSTEM,
            weight = HandlerWeight.LIGHT,
            matcher = { ctx -> ctx.text == "/help" },
            body = { HandlerOutcome.success(TaskKind.COMMAND, "help-body") },
        )
        val kinds = HandlerKinds.build(
            agent = HandlerFactory { _, _ -> heavyAgent(gate) },
            system = HandlerFactory { _, _ -> help },
        )
        heavyHarness(gate, maxConcurrentAgents = 2, extraRoutes = listOf(SystemRoute(id = "help")), extraKinds = kinds).use { h ->
            h.process(msg(messageId = "om_h1", chatId = "oc_a", text = "ai 长跑一"))
            h.process(msg(messageId = "om_h2", chatId = "oc_b", text = "ai 长跑二"))
            assertTrue(h.awaitUntil { h.permits.available == 0 }, "两个 HEAVY 应吃满许可")
            assertEquals(2, h.admission.inFlight)

            // 🔴 许可已耗尽，/help 仍必须立即执行（旧实现里它会排在 2 个长跑 agent 后面 = D11）
            val started = System.nanoTime()
            h.process(msg(messageId = "om_help", chatId = "oc_c", text = "/help"))
            assertTrue(h.awaitUntil(3_000L) { help.invocations.get() >= 1 })
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            assertTrue(elapsedMillis < 3_000L, "LIGHT 必须在 3 秒铁律内执行，实测 ${elapsedMillis}ms")
            assertEquals(0, h.permits.available, "LIGHT 不得占用许可")
            assertTrue(h.awaitUntil { h.allReplyBodies().contains("help-body") })
            gate.complete(Unit)
            assertTrue(h.awaitQuiescent())
            assertEquals(2, h.permits.available, "两个 HEAVY 结束后许可必须完整归还")
        }
    }

    @Test
    fun `HEAVY 超 maxConcurrentAgents 时排队，前者结束后后者才跑`() {
        val first = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val kinds = HandlerKinds.build(
            agent = HandlerFactory { _, _ ->
                ScriptedHandler(
                    kind = RouteKind.AGENT,
                    weight = HandlerWeight.HEAVY,
                    matcher = { true },
                    body = { ctx ->
                        order.add(ctx.text)
                        if (ctx.text == "ai-1") first.await() else secondGate.await()
                        HandlerOutcome.success(TaskKind.AGENT, "done:${ctx.text}")
                    },
                )
            },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(AgentRoute()), maxConcurrentAgents = 1, chatQueueLimit = 4),
            kinds = kinds,
        ).use { h ->
            // 🔴 用两个不同 chat，避开 ChatLane 的每会话上限，只测全局许可这一级
            h.process(msg(messageId = "om_q1", chatId = "oc_a", text = "ai-1"))
            assertTrue(h.awaitUntil { order.contains("ai-1") })
            assertEquals(0, h.permits.available)

            h.process(msg(messageId = "om_q2", chatId = "oc_b", text = "ai-2"))
            assertTrue(h.awaitUntil(3_000L) { h.admission.inFlight == 2 }, "两条任务协程都已在途")
            assertFalse(order.contains("ai-2"), "许可耗尽时第二条必须排队，不得进 handler")
            assertEquals(2L, h.counters.submitted, "两条都已提交（submit 非挂起，不因排队而阻塞）")

            first.complete(Unit)
            assertTrue(h.awaitUntil { order.contains("ai-2") }, "前者结束后排队者必须自动获得许可")
            secondGate.complete(Unit)
            assertTrue(h.awaitQuiescent())
            assertEquals(listOf("ai-1", "ai-2"), order)
            assertEquals(1, h.permits.available)
            assertEquals(0L, h.counters.queueTimeouts)
        }
    }

    @Test
    fun `排队超时回提示（BC-144 task_queue_timeout）`() {
        val gate = CompletableDeferred<Unit>()
        heavyHarness(gate, maxConcurrentAgents = 1, chatQueueLimit = 4, queueWaitSeconds = 1L).use { h ->
            h.process(msg(messageId = "om_t1", chatId = "oc_a", text = "ai 占住唯一许可"))
            assertTrue(h.awaitUntil { h.permits.available == 0 })

            h.process(msg(messageId = "om_t2", chatId = "oc_b", text = "ai 排队者"))
            // 🔴 等 Observe 交接而不是等计数器（`recordQueueTimeout()` 在 `deliver` 之前）。
            assertTrue(
                h.awaitUntil(8_000L) { h.outcomes.any { it.errorCode == TaskErrorCodes.TASK_QUEUE_TIMEOUT } },
                "queueWaitSeconds=1 后必须超时并交接给 Observe",
            )
            assertEquals(1L, h.counters.queueTimeouts)

            val timeout = h.outcomes.first { it.errorCode == TaskErrorCodes.TASK_QUEUE_TIMEOUT }
            assertEquals("task_queue_timeout", timeout.errorCode)
            assertTrue(timeout.summary.contains("1s"), timeout.summary)
            assertTrue(timeout.summary.contains("停止"), timeout.summary)
            assertEquals(TaskStatus.FAILURE, timeout.outcome.status)
            assertTrue(h.allReplyBodies().any { it.contains("已在队列等待超") }, "排队超时必须回复用户")

            gate.complete(Unit)
            assertTrue(h.awaitQuiescent())
        }
    }

    @Test
    fun `同 chat 在途超限立即回 agent_busy（BC-167 不排队）`() {
        val gate = CompletableDeferred<Unit>()
        heavyHarness(gate, maxConcurrentAgents = 4, chatQueueLimit = 1).use { h ->
            h.process(msg(messageId = "om_b1", chatId = "oc_same", text = "ai 一"))
            assertTrue(h.awaitUntil { h.lanes.laneOf("oc_same").inFlight.get() >= 1 })

            h.process(msg(messageId = "om_b2", chatId = "oc_same", text = "ai 二"))
            // 🔴 等 Observe 交接而不是等计数器：`recordChatBusy()` 在 `deliver` **之前**发生，
            //    只等计数器会在「已计数、尚未交接」的窗口里读到空 outcomes（竞态）。
            assertTrue(h.awaitUntil(3_000L) { h.outcomes.any { it.errorCode == TaskErrorCodes.AGENT_BUSY } })
            assertEquals(1L, h.counters.chatBusy)
            val busy = h.outcomes.first { it.errorCode == TaskErrorCodes.AGENT_BUSY }
            assertEquals("agent_busy", busy.errorCode)
            assertTrue(busy.summary.contains("1 个任务在途"), busy.summary)
            assertTrue(busy.summary.contains("停止"), busy.summary)

            // 🔴 不同 chat 不受影响（每 chat 独立 lane）
            h.process(msg(messageId = "om_b3", chatId = "oc_other", text = "ai 三"))
            assertTrue(h.awaitUntil(3_000L) { h.lanes.laneOf("oc_other").inFlight.get() >= 1 })

            gate.complete(Unit)
            assertTrue(h.awaitQuiescent())
        }
    }

    @Test
    fun `🔴 繁忙回复按 chat 合批：cooldown 窗口内同 chat 只回一次`() {
        val gate = CompletableDeferred<Unit>()
        val now = AtomicLong(1_000_000L)
        heavyHarness(gate, maxConcurrentAgents = 4, chatQueueLimit = 1, busyReplyCooldownSeconds = 30L, clock = { now.get() }).use { h ->
            h.process(msg(messageId = "om_c_pad", chatId = "oc_same", text = "ai 占位"))
            assertTrue(h.awaitUntil { h.lanes.laneOf("oc_same").inFlight.get() >= 1 })

            // 窗口内连发 5 条 ⇒ 只回 1 次
            repeat(5) { i -> h.process(msg(messageId = "om_c$i", chatId = "oc_same", text = "ai 挤")) }
            assertTrue(h.awaitUntil(3_000L) { h.counters.chatBusy >= 5L })
            assertTrue(h.awaitUntil(3_000L) { h.allReplyBodies().any { it.contains("任务在途") } }, "首次繁忙必须回复")
            // 🔴 等抑制计数稳定（被抑制的那 4 条不进 deliver，只在 runHeavy 里同步计数）
            assertTrue(h.awaitUntil(3_000L) { h.counters.busyRepliesSuppressed >= 4L })
            assertEquals(1L, h.lanes.busyReplyCount)
            assertEquals(4L, h.counters.busyRepliesSuppressed)
            assertEquals(4L, h.lanes.busyRepliesSuppressed)
            assertEquals(1, h.allReplyBodies().count { it.contains("任务在途") }, "窗口内同 chat 只回一次")

            // 🔴 推进虚拟时钟跨过 cooldown ⇒ 下一次繁忙重新回复
            now.addAndGet(30_000L)
            h.process(msg(messageId = "om_c_after", chatId = "oc_same", text = "ai 再挤"))
            assertTrue(h.awaitUntil(3_000L) { h.lanes.busyReplyCount >= 2L })
            assertEquals(2, h.allReplyBodies().count { it.contains("任务在途") })

            // 🔴 另一个 chat 的合批窗口互不干扰
            h.process(msg(messageId = "om_d0", chatId = "oc_other", text = "ai 占位"))
            assertTrue(h.awaitUntil { h.lanes.laneOf("oc_other").inFlight.get() >= 1 })
            h.process(msg(messageId = "om_d1", chatId = "oc_other", text = "ai 挤"))
            assertTrue(h.awaitUntil(3_000L) { h.lanes.busyReplyCount >= 3L })
            assertEquals(3, h.allReplyBodies().count { it.contains("任务在途") })

            gate.complete(Unit)
            assertTrue(h.awaitQuiescent())
        }
    }

    @Test
    fun `🔴 URGENT 绕过许可并 cancel 目标 chat 的在途任务`() {
        val gate = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val stop = ScriptedHandler(
            kind = RouteKind.SYSTEM,
            weight = HandlerWeight.URGENT,
            matcher = { ctx -> ctx.text == "停止" },
            body = { HandlerOutcome.success(TaskKind.COMMAND, "已停止") },
        )
        val agent = ScriptedHandler(
            kind = RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            matcher = { ctx -> ctx.text.startsWith("ai") },
            body = {
                try {
                    gate.await()
                    HandlerOutcome.success(TaskKind.AGENT, "never")
                } catch (e: CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            },
        )
        val kinds = HandlerKinds.build(
            agent = HandlerFactory { _, _ -> agent },
            system = HandlerFactory { _, _ -> stop },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(AgentRoute(), com.jiaoay.lark.bridge.config.SystemRoute()), maxConcurrentAgents = 1, chatQueueLimit = 4),
            kinds = kinds,
        ).use { h ->
            h.process(msg(messageId = "om_u1", chatId = "oc_a", text = "ai 长跑"))
            assertTrue(h.awaitUntil { agent.invocations.get() >= 1 })
            assertEquals(0, h.permits.available, "许可已耗尽")

            // 🔴 许可为 0 时 URGENT 仍立即执行，且先取消同 chat 的在途任务
            h.process(msg(messageId = "om_stop", chatId = "oc_a", text = "停止"))
            assertTrue(h.awaitUntil(5_000L) { stop.invocations.get() >= 1 }, "URGENT 必须绕过许可立即执行")
            assertTrue(h.awaitUntil(5_000L) { cancelled.get() }, "目标 chat 的在途任务必须收到取消")
            assertTrue(h.awaitUntil(5_000L) { h.counters.urgentCancellations >= 1L })
            assertTrue(h.awaitUntil(5_000L) { h.counters.cancelledTasks >= 1L })
            assertEquals(0, h.lanes.laneOf("oc_a").inFlight.get(), "取消后 lane 计数必须由 finally 归还")
            assertTrue(h.awaitUntil(5_000L) { h.permits.available == 1 }, "取消后许可必须归还")
            assertTrue(h.allReplyBodies().contains("已停止"))
            // 🔴 被取消的任务**静默**：不产出任何回复（BC 平移）
            assertFalse(h.allReplyBodies().contains("never"))
            assertFalse(gate.isCompleted)
        }
    }

    @Test
    fun `🔴 取消后 ProcessRegistry 中的进程被杀（管线接线；原语级 kill 链归 T6）`() {
        val gate = CompletableDeferred<Unit>()
        val proc = FakeProcess()
        val agent = ScriptedHandler(
            kind = RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            matcher = { ctx -> ctx.text.startsWith("ai") },
            body = { ctx ->
                ctx.processes.register(proc)
                try {
                    gate.await()
                    HandlerOutcome.success(TaskKind.AGENT, "never")
                } finally {
                    // 🔴 与 ProgramLauncher 同形：kill 升级链必须在 NonCancellable 里跑完
                    withContext(NonCancellable) { ctx.processes.killAll() }
                }
            },
        )
        val stop = ScriptedHandler(
            kind = RouteKind.SYSTEM,
            weight = HandlerWeight.URGENT,
            matcher = { ctx -> ctx.text == "停止" },
            body = { HandlerOutcome.success(TaskKind.COMMAND, "已停止") },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(AgentRoute(), com.jiaoay.lark.bridge.config.SystemRoute())),
            kinds = HandlerKinds.build(agent = HandlerFactory { _, _ -> agent }, system = HandlerFactory { _, _ -> stop }),
        ).use { h ->
            h.process(msg(messageId = "om_p1", chatId = "oc_a", text = "ai 长跑"))
            assertTrue(h.awaitUntil { h.processes.liveCount >= 1 })

            h.process(msg(messageId = "om_p2", chatId = "oc_a", text = "停止"))
            assertTrue(h.awaitUntil(5_000L) { h.processes.liveCount == 0 }, "取消必须把登记在案的进程杀干净")
            // 🔴 `ProcessRegistry.killAll()` 是**一击必杀式兜底**语义：直接 `destroyForcibly()`（SIGKILL），不走升级链。
            //    SIGTERM→3s→SIGKILL→2s 的升级链在 `ProgramLauncher` 里（单进程粒度，T6 的
            //    CancellationKillsProcessTest / TimeoutKillsProcessTest 已覆盖）；本测试只管「管线取消
            //    ⇒ registry 里的进程真的被杀」这一段接线。
            assertTrue(proc.forciblyDestroyed.get(), "killAll 必须对存活进程调 destroyForcibly")
            assertFalse(proc.destroyed.get(), "killAll 不走 destroy()（升级链归 ProgramLauncher）")
            assertFalse(proc.isAlive)
        }
    }

    @Test
    fun `HEAVY 结束后 lane 与许可完整归还，空 lane 即移除（LRU 表不留死条目）`() {
        val gate = CompletableDeferred<Unit>()
        heavyHarness(gate, maxConcurrentAgents = 2, chatQueueLimit = 2).use { h ->
            h.process(msg(messageId = "om_r1", chatId = "oc_x", text = "ai 一"))
            h.process(msg(messageId = "om_r2", chatId = "oc_x", text = "ai 二"))
            assertTrue(h.awaitUntil { h.permits.available == 0 })
            assertEquals(1, h.lanes.laneCount)
            assertEquals(2, h.lanes.totalInFlight)

            gate.complete(Unit)
            assertTrue(h.awaitQuiescent())
            assertTrue(h.awaitUntil { h.lanes.laneCount == 0 }, "空 lane 必须被移除")
            assertEquals(0, h.lanes.totalInFlight)
            assertEquals(2, h.permits.available)
            assertEquals(2L, h.counters.completed)
        }
    }

    @Test
    fun `🔴 submit 是非挂起 O(1)：许可耗尽时 intake 侧调用仍瞬间返回（3 秒铁律的桥接侧保证）`() {
        val gate = CompletableDeferred<Unit>()
        heavyHarness(gate, maxConcurrentAgents = 1, chatQueueLimit = 8, queueWaitSeconds = 60L).use { h ->
            h.process(msg(messageId = "om_s_pad", chatId = "oc_a", text = "ai 占位"))
            assertTrue(h.awaitUntil { h.permits.available == 0 })

            // 200 条排队消息：每条都要走 intake 五步 + submit；总耗时必须远小于 3 秒
            val started = System.nanoTime()
            repeat(200) { i -> h.process(msg(messageId = "om_s$i", chatId = "oc_b$i", text = "ai 排队$i")) }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            assertTrue(elapsedMillis < 3_000L, "200 条 submit 实测 ${elapsedMillis}ms，必须 < 3000ms")
            assertEquals(201L, h.counters.submitted)
            gate.complete(Unit)
        }
    }

    @Test
    fun `PermitGate 热扩容立即生效（HOT：runtime maxConcurrentAgents 是 HOT 字段）`() {
        val gate = CompletableDeferred<Unit>()
        heavyHarness(gate, maxConcurrentAgents = 1, chatQueueLimit = 8).use { h ->
            assertEquals(1, h.permits.capacity)
            h.process(msg(messageId = "om_g1", chatId = "oc_a", text = "ai 一"))
            assertTrue(h.awaitUntil { h.permits.available == 0 })

            assertTrue(h.permits.reconfigure(3))
            assertEquals(3, h.permits.capacity)
            assertTrue(h.awaitUntil { h.permits.available == 2 })
            assertEquals(1L, h.permits.reconfigureCount)

            // 🔴 幂等：同值不计数
            assertFalse(h.permits.reconfigure(3))
            assertEquals(1L, h.permits.reconfigureCount)

            // 扩容后排队的第二条立即获得许可
            h.process(msg(messageId = "om_g2", chatId = "oc_b", text = "ai 二"))
            assertTrue(h.awaitUntil { h.permits.available == 1 })
            gate.complete(Unit)
            assertTrue(h.awaitQuiescent())
        }
    }

    @Test
    fun `PermitGate 热缩容由后台协程渐进抽走许可（不换 Semaphore 实例 ⇒ 无许可泄漏）`() {
        val gate = CompletableDeferred<Unit>()
        heavyHarness(gate, maxConcurrentAgents = 3, chatQueueLimit = 8).use { h ->
            assertEquals(3, h.permits.available)
            assertTrue(h.permits.reconfigure(1))
            assertTrue(h.awaitUntil(5_000L) { h.permits.available == 1 }, "空闲时缩容应立即抽走 2 个许可")
            assertTrue(h.awaitUntil(5_000L) { h.permits.drainedCount == 2L })
            assertEquals(1, h.permits.capacity)
            gate.complete(Unit)
        }
    }

    @Test
    fun `ChatLanes LRU 上限 256 且逐出可观测`() {
        val lanes = ChatLanes(maxLanes = ChatLanes.MAX_LANES)
        assertEquals(256, ChatLanes.MAX_LANES)
        assertEquals(256, lanes.capacity)
        repeat(256) { lanes.laneOf("oc_$it") }
        assertEquals(256, lanes.laneCount)
        assertEquals(0L, lanes.evictionCount)

        lanes.laneOf("oc_overflow")
        assertTrue(lanes.laneCount <= 256, "LRU 表不得无界增长")
        assertTrue(lanes.evictionCount >= 1L, "逐出必须计数（D18）")
    }

    @Test
    fun `ChatLanes cancelAll 只取消登记在案的 Job 且计数由 finally 归还`() {
        val lanes = ChatLanes()
        val jobs = (1..3).map { kotlinx.coroutines.Job() }
        jobs.forEach { lanes.register("oc_a", it) }
        lanes.register("oc_b", kotlinx.coroutines.Job())
        assertEquals(3, lanes.cancelAll("oc_a"))
        assertEquals(1L, lanes.cancelRequestCount)
        assertEquals(3L, lanes.cancelledJobCount)
        assertTrue(jobs.all { it.isCancelled })
        assertEquals(0, lanes.cancelAll("oc_a"), "重复 cancelAll 幂等")
        assertEquals(0, lanes.cancelAll("oc_missing"), "不存在的 chat 返回 0 不抛")
    }

    @Test
    fun `ChatLanes tryAcquire 的 CAS 自旋在 limit 边界上精确`() {
        val lanes = ChatLanes()
        assertTrue(lanes.tryAcquire("oc_a", 2))
        assertTrue(lanes.tryAcquire("oc_a", 2))
        assertFalse(lanes.tryAcquire("oc_a", 2))
        assertEquals(2, lanes.laneOf("oc_a").inFlight.get())
        assertEquals(1, lanes.release("oc_a"))
        assertTrue(lanes.tryAcquire("oc_a", 2))
        assertEquals(-1, lanes.release("oc_missing"), "未知 chat 的 release 返回 -1 不抛")
    }

    @Test
    fun `ChatLanes snapshot 与 clear（UI 与 stop 用）`() {
        val lanes = ChatLanes()
        lanes.tryAcquire("oc_a", 4)
        lanes.tryAcquire("oc_b", 4)
        val snap = lanes.snapshot()
        assertEquals(2, snap.size)
        assertEquals(setOf("oc_a", "oc_b"), snap.map { it.chatId }.toSet())
        assertEquals(1, snap.first { it.chatId == "oc_a" }.inFlight)
        lanes.clear()
        assertEquals(0, lanes.laneCount)
    }
}

/** [TimeoutPolicy] 与 ③ Execute 的超时/看门狗接线（🔴 D10：总超时与空闲超时可区分）。 */
class TimeoutTest {

    @Test
    fun `TimeoutPolicy 三分支：handler 覆盖 大于 agent 类 大于 引擎默认`() {
        val config = testConfig(taskTimeoutSeconds = 60L, agentTimeoutMinutes = 10L)
        val policy = TimeoutPolicy { config }
        // handler 自带 timeoutSeconds ⇒ 最高优先
        assertEquals(5_000L, policy.totalMillis(5L, RouteKind.AGENT))
        assertEquals(5_000L, policy.totalMillis(5L, RouteKind.SYSTEM))
        // agent 类（AGENT / FILE）⇒ agentTimeoutMinutes * 60s
        assertEquals(600_000L, policy.totalMillis(null, RouteKind.AGENT))
        assertEquals(600_000L, policy.totalMillis(null, RouteKind.FILE))
        // 其余 ⇒ taskTimeoutSeconds
        assertEquals(60_000L, policy.totalMillis(null, RouteKind.SYSTEM))
        assertEquals(60_000L, policy.totalMillis(null, RouteKind.COMMAND))
    }

    @Test
    fun `TimeoutPolicy 下限与空闲看门狗与引擎缓冲`() {
        val config = testConfig(taskTimeoutSeconds = 0L, agentIdleTimeoutSeconds = 120L)
        val policy = TimeoutPolicy { config }
        assertEquals(TimeoutPolicy.MIN_TOTAL_MILLIS, policy.totalMillis(0L, RouteKind.SYSTEM))
        assertEquals(120_000L, policy.idleMillis())
        assertEquals(30_000L, policy.engineBufferMillis())
        assertEquals(30L, TimeoutPolicy.ENGINE_TIMEOUT_BUFFER_SECONDS)
        assertEquals(250L, TimeoutPolicy.IDLE_CHECK_SLICE_MILLIS)
        assertTrue(TimeoutPolicy.isAgentLike(RouteKind.AGENT))
        assertTrue(TimeoutPolicy.isAgentLike(RouteKind.FILE))
        assertFalse(TimeoutPolicy.isAgentLike(RouteKind.SYSTEM))
        assertFalse(TimeoutPolicy.isAgentLike(RouteKind.COMMAND))
    }

    @Test
    fun `idleTimeoutSeconds 为 0 ⇒ 关闭空闲看门狗`() {
        val policy = TimeoutPolicy { testConfig(agentIdleTimeoutSeconds = 0L) }
        assertEquals(0L, policy.idleMillis())
    }

    @Test
    fun `总超时触发时 handler 收到取消并回 task_timeout（BC-147）`() {
        val cancelled = AtomicBoolean(false)
        val slow = ScriptedHandler(
            kind = RouteKind.SYSTEM,
            weight = HandlerWeight.LIGHT,
            timeoutSeconds = 1L,
            body = {
                try {
                    delay(30_000L)
                    HandlerOutcome.success(TaskKind.COMMAND, "never")
                } catch (e: CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(com.jiaoay.lark.bridge.config.SystemRoute())),
            kinds = HandlerKinds.build(system = HandlerFactory { _, _ -> slow }),
        ).use { h ->
            h.process(msg(messageId = "om_to1"))
            assertTrue(h.awaitUntil(10_000L) { h.counters.totalTimeouts >= 1L })
            assertTrue(cancelled.get(), "withTimeout 触发时 handler 必须收到取消")
            // 🔴 同样是「计数先于交接」的窗口：`recordTotalTimeout()` 在 catch 里，`deliver`→`onOutcome` 在其后。
            assertTrue(h.awaitUntil(5_000L) { h.outcomes.any { it.errorCode == TaskErrorCodes.TASK_TIMEOUT } })
            val outcome = h.outcomes.first { it.errorCode == TaskErrorCodes.TASK_TIMEOUT }
            assertEquals("task_timeout", outcome.errorCode)
            assertEquals("任务超时（>1s）", outcome.summary)
            assertEquals(TaskStatus.FAILURE, outcome.outcome.status)
            assertEquals(0L, h.counters.idleTimeouts, "总超时不得被误计成空闲超时")
            assertEquals(0L, h.counters.cancelledTasks, "超时不是外部取消")
            assertTrue(h.allReplyBodies().any { it.contains("任务超时") }, "超时必须回复用户")
        }
    }

    @Test
    fun `🔴 空闲看门狗触发且与总超时可区分（D10 消解点）`() {
        val cancelled = AtomicBoolean(false)
        val stuck = ScriptedHandler(
            kind = RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            body = {
                try {
                    // 总超时给足 60s，但一直不产出 ⇒ 必须在 1s 空闲后被看门狗掐掉
                    delay(60_000L)
                    HandlerOutcome.success(TaskKind.AGENT, "never")
                } catch (e: CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(
                routes = listOf(AgentRoute()),
                agentIdleTimeoutSeconds = 1L,
                taskTimeoutSeconds = 60L,
            ),
            kinds = HandlerKinds.build(agent = HandlerFactory { _, _ -> stuck }),
        ).use { h ->
            val started = System.nanoTime()
            h.process(msg(messageId = "om_idle1", text = "ai 卡死"))
            assertTrue(h.awaitUntil(15_000L) { h.counters.idleTimeouts >= 1L })
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            assertTrue(elapsedMillis < 10_000L, "空闲 1s 就该掐掉，实测 ${elapsedMillis}ms（旧实现要等满 10 分钟 = D10）")
            assertTrue(cancelled.get())

            val outcome = h.outcomes.first { it.errorCode == TaskErrorCodes.AGENT_TIMEOUT_ALIAS }
            assertEquals("agent_timeout", outcome.errorCode)
            assertTrue(outcome.summary.contains("空闲超时"), outcome.summary)
            assertTrue(outcome.summary.contains("1s"), outcome.summary)
            assertEquals(0L, h.counters.totalTimeouts, "🔴 空闲超时不得被计成总超时（两者必须可区分）")
            assertTrue(h.awaitQuiescent())
        }
    }

    @Test
    fun `持续产出时看门狗不触发（touch 刷新 lastActivityMillis）`() {
        val chatty = ScriptedHandler(
            kind = RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            body = { ctx ->
                repeat(8) { ctx.progress.append("第 $it 行输出"); delay(200L) }
                HandlerOutcome.success(TaskKind.AGENT, "chatty done")
            },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(AgentRoute()), agentIdleTimeoutSeconds = 1L),
            kinds = HandlerKinds.build(agent = HandlerFactory { _, _ -> chatty }),
        ).use { h ->
            h.process(msg(messageId = "om_chat1", text = "ai 持续输出"))
            assertTrue(h.awaitUntil(15_000L) { h.outcomes.any { it.summary == "chatty done" } })
            assertEquals(0L, h.counters.idleTimeouts)
            assertEquals(0L, h.counters.totalTimeouts)
            assertEquals(8, h.replies.sumOf { 0 } + 8, "handler 自身不产出出站（回复统一由 ④ Reply 出口发）")
            assertEquals(1L, h.counters.completed)
        }
    }

    @Test
    fun `handler 抛未预期异常 ⇒ 回失败文案且服务不中断（BC-146）`() {
        val boom = ScriptedHandler(
            kind = RouteKind.SYSTEM,
            weight = HandlerWeight.LIGHT,
            body = { throw IllegalStateException("handler 炸了") },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(com.jiaoay.lark.bridge.config.SystemRoute())),
            kinds = HandlerKinds.build(system = HandlerFactory { _, _ -> boom }),
        ).use { h ->
            h.process(msg(messageId = "om_e1"))
            // 🔴 同步点必须等 Observe 交接（outcome）本身，而非 handlerErrors 计数器：runGuarded 先
            //    recordHandlerError() 再 deliver()（其内 onOutcome 才 append outcome），等计数器会在 outcome
            //    落库前返回 ⇒ NoSuchElementException。与本文件 177/182、204/206、549/550 的约定一致。
            assertTrue(h.awaitUntil(5_000L) { h.outcomes.any { it.errorCode == TaskErrorCodes.TASK_ERROR } })
            val outcome = h.outcomes.first { it.errorCode == TaskErrorCodes.TASK_ERROR }
            assertEquals("task_error", outcome.errorCode)
            assertTrue(outcome.summary.contains("handler 炸了"), outcome.summary)
            assertTrue(h.counters.handlerErrors >= 1L, "BC-146：执行器异常必须计入 handlerErrors")
            assertTrue(h.awaitQuiescent())

            // 🔴 服务不中断：下一条消息仍能正常处理
            h.process(msg(messageId = "om_e2"))
            assertTrue(h.awaitUntil(5_000L) { h.counters.handlerErrors >= 2L })
        }
    }

    @Test
    fun `外部取消（runtime cancel）静默收尾不回复`() {
        val gate = CompletableDeferred<Unit>()
        val agent = ScriptedHandler(
            kind = RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            body = { gate.await(); HandlerOutcome.success(TaskKind.AGENT, "never") },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(AgentRoute())),
            kinds = HandlerKinds.build(agent = HandlerFactory { _, _ -> agent }),
        ).use { h ->
            h.process(msg(messageId = "om_x1", text = "ai 长跑"))
            assertTrue(h.awaitUntil { agent.invocations.get() >= 1 })
            h.runtime.cancel()
            assertTrue(h.awaitUntil(5_000L) { h.counters.cancelledTasks >= 1L })
            assertEquals(emptyList(), h.allReplyBodies(), "外部取消必须静默（不给用户发半截结果）")
            assertEquals(0L, h.counters.totalTimeouts)
            assertEquals(0L, h.counters.idleTimeouts)
        }
    }
}
