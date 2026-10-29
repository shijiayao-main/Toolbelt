// 批 6 守护：Admission 的未命中兜底调度 agent seam（fallbackHandlerProvider）三态语义。
//
// 契约（见 Admission.fallbackHandlerProvider KDoc）：
// 1. seam 未注入（null）⇒ 未命中消息走 runUnhandled（P3：TEXT 恒静默留痕——模板已删）——既有行为延续；
// 2. seam 注入且返回 handler ⇒ 未命中消息改走 **HEAVY 同款闸**（占许可 + per-chat 上限），
//    handler 自报 kind/weight 透传审计；
// 3. seam 注入但返回 null（非文本 / 空白文本）⇒ 回落 runUnhandled 原语义。
//
// 🔴 数组序优先级不受影响：路由 resolve 命中的消息**不问 fallback**（fallback 只在 entry == null 分支求值）。
// 🔴 路由替身 = CommandRoute + ScriptedHandler（REPLY 槽已退役；本类只守 seam 机制，不依赖具体 handler）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.RouteKind

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

class FallbackDispatchTest {

    /** 造一个按 alias 精确匹配的 command 单表（REPLY 槽退役后的最小替身变体）。 */
    private fun kindsFor(): HandlerKinds = HandlerKinds.build(
        command = HandlerFactory { route, _ ->
            val r = route as com.jiaoay.lark.bridge.config.CommandRoute
            ScriptedHandler(
                kind = RouteKind.COMMAND,
                weight = HandlerWeight.LIGHT,
                matcher = { ctx -> ctx.text == r.aliases.firstOrNull() },
                body = { HandlerOutcome.success(TaskKind.COMMAND, r.promptTemplate) },
            )
        },
    )

    /** 路由替身：alias "ping" → 直答 "pong"（REPLY 槽退役后用 CommandRoute 承担同形命中语义）。 */
    private fun pingRoute() = com.jiaoay.lark.bridge.config.CommandRoute(
        id = "ping",
        aliases = listOf("ping"),
        promptTemplate = "pong",
    )

    @Test
    fun `seam 未注入时未命中走静默兜底（行为延续）`() {
        val routes = listOf(pingRoute())
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes),
            kinds = kindsFor(),
        ).use { h ->
            h.process(msg(messageId = "om_fb1", text = "随便聊聊"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(emptyList(), h.allReplyBodies(), "未注入 seam ⇒ TEXT 兜底恒静默（P3：模板已删）")
            assertEquals(TaskKind.COMMAND, h.outcomes[0].outcome.kind, "兜底 outcome 的 kind 是 COMMAND（旧语义）")
            assertEquals(Admission.UNHANDLED_SILENT_SUMMARY, h.outcomes[0].outcome.summary)
            assertEquals(1L, h.counters.unmatched, "计入 unmatched 计数")
        }
    }

    @Test
    fun `seam 返回 handler 时未命中消息改走 fallback（kind 与回复来自 fallback handler）`() {
        val routes = listOf(pingRoute())
        val asked = AtomicInteger(0)
        val fallback = ScriptedHandler(
            kind = RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            matcher = { false }, // 🔴 真实 DispatchAgentHandler 恒 false：fallback 不参与路由匹配
            body = { ctx -> HandlerOutcome.success(TaskKind.AGENT, "dispatched:${ctx.text}") },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes),
            kinds = kindsFor(),
            fallbackHandlerProvider = { asked.incrementAndGet(); fallback },
        ).use { h ->
            h.process(msg(messageId = "om_fb2", text = "随便聊聊"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("dispatched:随便聊聊"), h.allReplyBodies(), "未命中消息由 fallback handler 处理")
            assertEquals(TaskKind.AGENT, h.outcomes[0].outcome.kind, "fallback outcome 的 kind 由 handler 自报（AGENT）")
            assertEquals(0L, h.counters.unmatched, "fallback 命中不计 unmatched")
            assertEquals(1, asked.get(), "seam 恰好被问一次")
        }
    }

    @Test
    fun `seam 返回 null 时回落未命中兜底（fallback 自身放弃）`() {
        val routes = listOf(pingRoute())
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes),
            kinds = kindsFor(),
            fallbackHandlerProvider = { null },
        ).use { h ->
            h.process(msg(messageId = "om_fb3", text = "随便聊聊"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(emptyList(), h.allReplyBodies(), "seam 返回 null ⇒ 原兜底语义（TEXT 恒静默，P3）")
            assertEquals(1L, h.counters.unmatched)
        }
    }

    @Test
    fun `路由命中不问 seam（数组序优先级不受 fallback 影响）`() {
        val routes = listOf(pingRoute())
        val asked = AtomicInteger(0)
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes),
            kinds = kindsFor(),
            fallbackHandlerProvider = { asked.incrementAndGet(); ScriptedHandler() },
        ).use { h ->
            h.process(msg(messageId = "om_fb4", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("pong"), h.allReplyBodies(), "命中的消息按数组序走原 handler")
            assertEquals(0, asked.get(), "🔴 命中消息绝不问 fallback（未命中分支才求值）")
        }
    }
}
