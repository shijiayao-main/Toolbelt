// M4-T7 测试：① Intake 的第 3/4 步（sender 准入 + 策略门控）与未命中兜底。
// 🔴 准入白名单已退役：AccessControl 恒放行；高风险授权改由 TrustPolicy 按信任名单判定。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.AccessConfig
import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.GatewaySignal
import com.jiaoay.lark.gateway.InboundKind
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/** [AccessControl]：准入恒放行（sender 级 allow/block 已退役，服务面收口归飞书后台可用范围）。 */
class AccessControlTest {

    private fun control(access: AccessConfig) = AccessControl { access }

    @Test
    fun `恒放行——无论配置如何、发送者是谁`() {
        val ac = control(AccessConfig())
        assertEquals(AccessDecision.ALLOWED, ac.decide("ou_anyone"))
        assertEquals(AccessDecision.ALLOWED, ac.decide(""))
        assertTrue(ac.isAllowed("ou_stranger"))
    }

    @Test
    fun `计数面——放行计数递增、拒绝计数恒零`() {
        val ac = control(AccessConfig(trustedUsers = listOf("ou_me")))
        repeat(3) { ac.decide("ou_someone") }
        assertEquals(3L, ac.allowedCount)
        assertEquals(0L, ac.allowlistRejections)
        assertEquals(0L, ac.blocklistRejections)
        assertEquals(0L, ac.unknownModeFallbackCount)
    }

    @Test
    fun `拒绝文案恒静默`() {
        assertNull(control(AccessConfig()).rejectionReply("ou_x"))
        assertNull(control(AccessConfig(trustedUsers = listOf("ou_x"))).rejectionReply("ou_x"))
    }

    @Test
    fun `配置热切换后仍恒放行（HOT 语义：每次 decide 都重读配置）`() {
        val ref = AtomicReference(AccessConfig())
        val ac = AccessControl { ref.get() }
        assertTrue(ac.isAllowed("ou_alice"))
        ref.set(AccessConfig(trustedUsers = listOf("ou_alice")))
        assertTrue(ac.isAllowed("ou_alice"))
        ref.set(AccessConfig(trustedUsers = emptyList()))
        assertTrue(ac.isAllowed("ou_stranger"))
    }

    @Test
    fun `🔴 无拒绝路径：任意发送者都能进管线（准入不再拦人）`() {
        val config = testConfig(
            routes = listOf(SystemRoute()),
            maxConcurrentAgents = 2,
        )
        Harness(dispatcher = Dispatchers.Default, initialConfig = config, kinds = systemKinds()).use { h ->
            h.process(msg(messageId = "om_any", senderId = "ou_mallory"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 }, "任意发送者都应进入管线并产出 outcome")
            assertEquals(0L, h.counters.accessRejected, "准入不再产生拒绝")
        }
    }
}

/** [PolicyGate]：群@门控 + 拒绝事件可见性（D 段新增能力）。 */
class PolicyGateTest {

    /** 群@门控真源注入：`groupMode = "mention"`（仅@唤起，默认）或 `"all"`（全放行，即旧「关门控」）。 */
    private fun gate(
        groupMode: String = "mention",
        sink: MutableList<GatewaySignal.Rejected>? = null,
    ) =
        // P2：agentGroupModeProvider 参数插在 onRejected 前 ⇒ trailing lambda 会错位绑到新参数，
        //    拒绝回调改具名传（PolicyGate KDoc 的参数序纪律）；P3 起第三参必传。
        PolicyGate({ AccessConfig() }, onRejected = { sink?.add(it) }, agentGroupModeProvider = { groupMode })

    @Test
    fun `群消息开启门控且未@机器人 ⇒ 拒绝`() {
        val g = gate(groupMode = "mention")
        assertFalse(g.isAllowed(msg(chatType = ChatType.GROUP, mentionedBot = false, mentionAll = false)))
        assertEquals(1L, g.groupEvaluatedCount)
    }

    @Test
    fun `群消息@了机器人 ⇒ 放行`() {
        val g = gate(groupMode = "mention")
        assertTrue(g.isAllowed(msg(chatType = ChatType.GROUP, mentionedBot = true)))
    }

    @Test
    fun `群消息@所有人 ⇒ 放行（mentionAll 等价于@机器人）`() {
        val g = gate(groupMode = "mention")
        assertTrue(g.isAllowed(msg(chatType = ChatType.GROUP, mentionAll = true, mentionedBot = false)))
    }

    @Test
    fun `群聊唤起模式 all ⇒ 全部放行且不评估@（旧「关闭门控」语义的 P3 载体）`() {
        val g = gate(groupMode = "all")
        assertTrue(g.isAllowed(msg(chatType = ChatType.GROUP, mentionedBot = false)))
        assertTrue(g.isAllowed(msg(chatType = ChatType.GROUP, text = "随便聊聊")))
        assertEquals(0L, g.rejectedCount, "groupMode=all ⇒ 零拒绝")
        // 🔴 `groupEvaluatedCount` 统计的是「群消息进入门控」的次数（**不看**门控开没开），
        //    目的是让「为 0」唯一意味着「消息全走了私聊/卡片回传分支」（排障语义）。
        assertEquals(2L, g.groupEvaluatedCount)
    }

    @Test
    fun `私聊一律放行（门控只管群）`() {
        val g = gate(groupMode = "mention")
        assertTrue(g.isAllowed(msg(chatType = ChatType.P2P, mentionedBot = false)))
        assertEquals(0L, g.groupEvaluatedCount)
    }

    @Test
    fun `UNKNOWN 会话类型放行（不误杀：解析不出类型不等于群）`() {
        val g = gate(groupMode = "mention")
        assertTrue(g.isAllowed(msg(chatType = ChatType.UNKNOWN, mentionedBot = false)))
        assertEquals(0L, g.groupEvaluatedCount)
    }

    @Test
    fun `CARD_ACTION 一律放行（卡片回传不是闲聊）`() {
        val g = gate(groupMode = "mention")
        assertTrue(g.isAllowed(msg(chatType = ChatType.GROUP, kind = InboundKind.CARD_ACTION, mentionedBot = false)))
        assertEquals(0L, g.groupEvaluatedCount)
    }

    @Test
    fun `🔴 拒绝时产出 Rejected 信号 + 计数 + 原因字面值（拒绝事件可见性）`() {
        val sink = mutableListOf<GatewaySignal.Rejected>()
        val g = gate(groupMode = "mention", sink)
        assertFalse(g.test(msg(messageId = "om_g1", chatId = "oc_g", senderId = "ou_bob", chatType = ChatType.GROUP)))
        assertEquals(1, sink.size)
        val signal = sink[0]
        assertEquals(PolicyGate.REASON_GROUP_REQUIRE_MENTION, signal.reason)
        assertEquals("group_require_mention", signal.reason)
        assertEquals("om_g1", signal.messageId)
        assertEquals("oc_g", signal.chatId)
        assertEquals("ou_bob", signal.senderId)
        assertEquals(1L, g.rejectedCount)
        assertEquals(0L, g.passedCount)
        assertEquals(PolicyGate.REASON_GROUP_REQUIRE_MENTION, g.lastRejectionReason)
    }

    @Test
    fun `放行时计数且不产出信号`() {
        val sink = mutableListOf<GatewaySignal.Rejected>()
        val g = gate(groupMode = "mention", sink)
        assertTrue(g.test(msg(chatType = ChatType.GROUP, mentionedBot = true)))
        assertTrue(g.test(msg(chatType = ChatType.P2P)))
        assertEquals(2L, g.passedCount)
        assertEquals(0L, g.rejectedCount)
        assertEquals(0, sink.size)
        assertNull(g.lastRejectionReason)
    }

    @Test
    fun `isAllowed 是纯判定不动计数不发信号（与 test 的分工）`() {
        val sink = mutableListOf<GatewaySignal.Rejected>()
        val g = gate(groupMode = "mention", sink)
        repeat(3) { g.isAllowed(msg(chatType = ChatType.GROUP, mentionedBot = false)) }
        assertEquals(0L, g.rejectedCount)
        assertEquals(0L, g.passedCount)
        assertEquals(0, sink.size)
        assertEquals(3L, g.groupEvaluatedCount, "群评估计数在 isAllowed 内累加（排障用）")
    }

    @Test
    fun `🔴 分工断言：PolicyGate 不看准入名单（sender 准入恒放行）`() {
        // gateway-contract §15 裁定：SDK 侧策略全放行，sender 级准入由 AccessControl 承担
        // （该层当前恒放行）。PolicyGate 只管群@门控，永不读信任名单。
        val g = PolicyGate(
            { AccessConfig(trustedUsers = listOf("ou_other")) },
            agentGroupModeProvider = { "all" },
        )
        assertTrue(g.isAllowed(msg(senderId = "ou_bob", chatType = ChatType.P2P)))
        assertTrue(g.isAllowed(msg(senderId = "ou_bob", chatType = ChatType.GROUP)))
    }

    @Test
    fun `经 intake 的群门控拒绝不占许可且仍进 Observe`() {
        val config = testConfig(routes = listOf(SystemRoute()), groupMode = "mention")
        Harness(dispatcher = Dispatchers.Default, initialConfig = config, kinds = systemKinds()).use { h ->
            h.process(msg(messageId = "om_g", chatType = ChatType.GROUP, mentionedBot = false))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            Thread.sleep(80L)
            assertEquals(1L, h.counters.policyRejected)
            assertEquals(1, h.rejectedSignals.size)
            assertEquals("group_require_mention", h.rejectedSignals[0].reason)
            assertEquals(emptyList(), h.allReplyBodies(), "群@门控拒绝不回文案（P3 恒静默，那是噪音）")
            assertEquals(Admission.REJECTION_SILENT_SUMMARY, h.outcomes[0].summary)
            assertEquals(2, h.permits.available, "拒绝不得占用许可")
        }
    }
}

/** [Admission.runUnhandled]：未命中任何 handler 的兜底（P3：TEXT 恒静默留痕、非 TEXT kind 降级文案）。 */
class UnhandledReplyTest {

    @Test
    fun `未命中任何 handler 的 TEXT ⇒ 恒静默留痕（P3：模板已删，App 形态归调度 agent）`() {
        // 🔴 kinds 为空表 ⇒ SystemRoute 的 kind 未登记 ⇒ 快照里没有任何 entry ⇒ 必然未命中
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = listOf(SystemRoute())), kinds = HandlerKinds.EMPTY).use { h ->
            h.process(msg(messageId = "om_u1", text = "随便说点什么"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            Thread.sleep(80L)
            assertEquals(emptyList(), h.allReplyBodies(), "TEXT 未命中恒静默（P3 修正 7）")
            assertEquals(1L, h.counters.unmatched)
            assertEquals(1L, h.router.unmatchedCount)
            assertEquals(0L, h.router.matchedCount)
            val outcome = h.outcomes[0].outcome
            assertEquals(TaskStatus.SKIPPED, outcome.status)
            assertEquals(Admission.UNHANDLED_SILENT_SUMMARY, outcome.summary)
            assertEquals(ReplyDirective.SILENT, outcome.directive)
            assertEquals(0L, h.counters.completed)
            assertEquals(1L, h.counters.skipped)
        }
    }

    @Test
    fun `未命中兜底留痕在 Observe（BC-150 BC-194 静默语义延续）`() {
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = listOf(SystemRoute())), kinds = HandlerKinds.EMPTY).use { h ->
            h.process(msg(messageId = "om_u2"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            Thread.sleep(80L)
            assertEquals(emptyList(), h.allReplyBodies())
            assertEquals(0, h.replies.sumOf { it.calls.get() }, "静默路径不得有任何出站调用")
            val outcome = h.outcomes[0].outcome
            assertEquals(TaskStatus.SKIPPED, outcome.status)
            assertEquals(Admission.UNHANDLED_SILENT_SUMMARY, outcome.summary)
            assertEquals("unmatched_silent", outcome.summary)
            assertEquals(ReplyDirective.SILENT, outcome.directive)
        }
    }

    @Test
    fun `handler 全部 match 失败 ⇒ 也走未命中兜底（未命中不等于无路由）`() {
        val never = ScriptedHandler(kind = RouteKind.COMMAND, matcher = { false })
        val config = testConfig(routes = listOf(CommandRoute(id = "r1", aliases = listOf("ping"), promptTemplate = "pong")))
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = config,
            kinds = HandlerKinds.build(command = HandlerFactory { _, _ -> never }),
        ).use { h ->
            h.process(msg(messageId = "om_u4", text = "别的话"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(emptyList(), h.allReplyBodies(), "TEXT 未命中恒静默")
            assertEquals(1L, h.counters.unmatched)
            assertEquals(0, never.invocations.get())
            assertEquals(1, h.router.snapshot.size, "快照里有 entry，只是 match 失败")
        }
    }

    @Test
    fun `未命中兜底不占许可（2 个 HEAVY 在途时依然立即落 Observe）`() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val heavy = ScriptedHandler(
            kind = RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            // 🔴 必须限定触发词：默认 matcher 是 `{ true }`，那样「完全无关的话」也会命中 agent 路由
            //    ⇒ 变成 HEAVY 排队而不是未命中兜底，本测试就测不到东西了。
            matcher = { ctx -> ctx.text.startsWith("ai") },
            body = { gate.await(); HandlerOutcome.success(TaskKind.AGENT, "done") },
        )
        val config = testConfig(routes = listOf(AgentRoute()), maxConcurrentAgents = 1)
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = config,
            kinds = HandlerKinds.build(agent = HandlerFactory { _, _ -> heavy }),
        ).use { h ->
            h.process(msg(messageId = "om_h", text = "ai 干活"))
            assertTrue(h.awaitUntil { heavy.invocations.get() >= 1 })
            assertEquals(0, h.permits.available)

            // 🔴 触发词不匹配 ⇒ 未命中 ⇒ 兜底走 LIGHT 分支，许可为 0 也立即留痕（恒静默不出站）。
            //    heavy 还挂在 gate 上未完成 ⇒ 它的 outcome 尚未落账——这里等的是**未命中这条**的
            //    silent outcome（ob 不占许可立即完成，不等 HEAVY）。
            h.process(msg(messageId = "om_u5", text = "完全无关的话"))
            assertTrue(h.awaitUntil { h.outcomes.any { it.outcome.summary == "unmatched_silent" } })
            assertEquals(emptyList(), h.allReplyBodies(), "未命中兜底 TEXT 恒静默（P3）")
            assertEquals(0, h.permits.available)
            gate.complete(Unit)
            assertTrue(h.awaitQuiescent())
        }
    }
}
