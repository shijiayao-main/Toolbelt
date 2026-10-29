// P2 agent-first 守护（2026-09-26 重写）：前缀短路的**形态判定**语义（dispatchEnabled 开关已删）。
//
// 契约（见 AgentHandler.match KDoc）：
// 1. 🔴 App 形态（生效执行器是 Koog 且可用）⇒ triggers 前缀**恒不命中**——消息落未命中
//    分支，由装配点判定闭包转 DispatchAgentHandler（本地 AI 调度）——「大脑接管后屏蔽
//    小脑直跑入口」；开关判定已随 P2 agent-first 删除（原 dispatchEnabled 升格默认）；
// 2. 🔴 Koog 不可用（isAvailable=false，端点未配）⇒ 前缀**照旧命中**——死区回落：
//    用户得到明确的 agent_backend_unavailable 失败回复，而非「前缀被屏蔽 + 兜底不接 +
//    无兜底文案」的三重静默死区（真机事故防线，语义保留）；
// 3. 🔴 CLI 形态（ctx.agent 恒 NoAgentRunner：backendId="none"、isAvailable=false）⇒ 永不短路，
//    ai 前缀照旧命中并产出显式 AGENT_BACKEND_UNAVAILABLE（fail-closed，不静默）；
// 4. 🔴 resetCommand（新对话）**不受短路影响**：App 形态下 ctx.agent 是 Koog runner，
//    reset 清的正是它的多轮上下文——该指令被吞会连「重置调度会话」都做不了；
// 5. 数组序在前槽（SYSTEM 槽指令替身）不受短路影响（数组序优先级天然保留）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.agent.NoAgentRunner
import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.SystemRoute

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/** Koog 调度层 backendId（App 形态注入的执行器标识；短路判定的现读判据）。 */
private const val KOOG_BACKEND_ID = "koog"

class DispatchAgentRouteTest {

    /** system + agent 两槽的最小 kinds（真实 AgentHandler——短路语义正是被测对象）。 */
    private fun kindsFor(): HandlerKinds = HandlerKinds.build(
        system = HandlerFactory { _, _ ->
            ScriptedHandler(
                kind = com.jiaoay.lark.bridge.config.RouteKind.SYSTEM,
                weight = HandlerWeight.LIGHT,
                matcher = { ctx -> ctx.text == "/help" },
                body = { HandlerOutcome.success(TaskKind.COMMAND, "帮助内容") },
            )
        },
        agent = HandlerFactory { route, deps ->
            com.jiaoay.lark.bridge.handler.AgentHandler(route as AgentRoute, deps)
        },
    )

    private fun routes(): List<com.jiaoay.lark.bridge.config.RouteRule> = listOf(
        SystemRoute(),
        // P3：AgentRoute 匹配键退役——前缀数据源 = EngineReservedWords（常量 "ai"/"/ai" 仍在）。
        AgentRoute(id = "default"),
    )

    @Test
    fun `CLI 形态（NoAgentRunner）ai 前缀照旧命中 agent 路由并显式报不可用`() {
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            // CLI 形态真件：NoAgentRunner（backendId="none"、isAvailable=false，fail-closed）
            agentOverride = NoAgentRunner(),
        ).use { h ->
            h.process(msg(messageId = "om_dr1", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            // AGENT kind 即代表前缀命中进了 agent 管线（CLI 形态永不短路）
            assertEquals(TaskKind.AGENT, h.outcomes[0].outcome.kind, "CLI 形态 ⇒ ai 前缀直走 agent 路由")
            assertEquals(
                TaskErrorCodes.AGENT_BACKEND_UNAVAILABLE,
                h.outcomes[0].outcome.errorCode,
                "CLI 形态无应答引擎 ⇒ 显式失败（不静默假成功）",
            )
        }
    }

    @Test
    fun `调度开启且 Koog 可用时 ai 前缀不再命中 agent 路由（消息转未命中分支）`() {
        Harness(
            dispatcher = Dispatchers.Default,
            // P3：unhandledReply 模板已删——未命中 TEXT 恒静默留痕，出站断言只看 kind 与计数。
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            // Koog 侧注入（端点就绪形态）——App 形态替身
            agentOverride = FakeAgentRunner(backendId = KOOG_BACKEND_ID, isAvailable = true),
        ).use { h ->
            h.process(msg(messageId = "om_dr2", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(
                TaskKind.COMMAND,
                h.outcomes[0].outcome.kind,
                "App 形态（Koog 可用）⇒ 前缀恒被短路，消息落未命中（生产中由判定闭包转本地 AI 调度）",
            )
            assertEquals(1L, h.counters.unmatched, "未命中计数如实（fallback 生产链路不计，Harness 无闭包）")
        }
    }

    /**
     * 🔴 死区回落的核心用例：调度开启但 Koog 端点未配置（isAvailable=false）⇒
     * 前缀**照旧命中** AgentHandler。防三重静默：这条消息若既被前缀屏蔽、
     * 又不被兜底接（兜底闸同样要求 isAvailable）、又无兜底文案 ⇒ 完全静默。
     */
    @Test
    fun `调度开启但 Koog 不可用时 ai 前缀照旧命中（死区回落，不再三重静默）`() {
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            agentOverride = FakeAgentRunner(backendId = KOOG_BACKEND_ID, isAvailable = false),
        ).use { h ->
            h.process(msg(messageId = "om_dr6", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(
                TaskKind.AGENT,
                h.outcomes[0].outcome.kind,
                "Koog 不可用 ⇒ 前缀不屏蔽，ai xxx 落回 AgentHandler（生产中产出 agent_backend_unavailable 明确报错）",
            )
        }
    }

    /**
     * 🔴 CLI 形态回归钉：生效执行器不是 Koog（CLI 形态恒注入 NoAgentRunner）⇒ 永不短路。
     */
    @Test
    fun `执行器非 Koog 时前缀照旧命中（CLI 形态永不短路）`() {
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            agentOverride = NoAgentRunner(),
        ).use { h ->
            h.process(msg(messageId = "om_dr7", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(TaskKind.AGENT, h.outcomes[0].outcome.kind, "非 Koog 执行器 ⇒ 前缀永不屏蔽")
        }
    }

    @Test
    fun `调度开启时 resetCommand 新对话仍命中（不受短路影响）`() {
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            agentOverride = FakeAgentRunner(backendId = KOOG_BACKEND_ID, isAvailable = true),
        ).use { h ->
            h.process(msg(messageId = "om_dr3", text = "新对话"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(TaskKind.AGENT, h.outcomes[0].outcome.kind, "新对话指令必须放行（清调度会话的入口）")
        }
    }

    @Test
    fun `调度开启时数组序在前槽指令照旧命中（数组序优先级不变）`() {
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            agentOverride = FakeAgentRunner(backendId = KOOG_BACKEND_ID, isAvailable = true),
        ).use { h ->
            h.process(msg(messageId = "om_dr4", text = "/help"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("帮助内容"), h.allReplyBodies(), "数组序在前的槽指令不受调度短路影响")
        }
    }

    @Test
    fun `调度开启时 seam 网关收编前缀消息（ai xxx 与未命中消息同走 fallback）`() {
        // 组合语义：短路（AgentHandler.match=false）+ seam（fallbackHandlerProvider）双管齐下——
        // ai xxx 最终由 fallback 处理，证明「前缀消息 = 调度消息」的统一入口成立。
        val asked = AtomicInteger(0)
        val fallback = ScriptedHandler(
            kind = com.jiaoay.lark.bridge.config.RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            matcher = { false },
            body = { ctx -> HandlerOutcome.success(TaskKind.AGENT, "dispatched:${ctx.text}") },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            agentOverride = FakeAgentRunner(backendId = KOOG_BACKEND_ID, isAvailable = true),
            fallbackHandlerProvider = { asked.incrementAndGet(); fallback },
        ).use { h ->
            h.process(msg(messageId = "om_dr5", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(
                listOf("dispatched:ai 帮我看看"),
                h.allReplyBodies(),
                "前缀消息被 fallback 收编（prompt 保留原文，剥前缀由调度 agent 自理）",
            )
        }
    }

    /**
     * 🔴 热化契约的 P2 语义继承者（原 dispatchEnabled RESTART→HOT 防回归钉，2026-09-26 开关删除）：
     * 短路判定每消息现读 `ctx.agent`（runner 形态 + isAvailable）——执行器形态切换**即时生效**，
     * 无需重启桥接。防未来有人把判定缓存进路由快照时测试红（快照缓存 = 「需重启」误导回潮）。
     */
    @Test
    fun `执行器形态热切换-短路即时生效（HOT 语义契约）`() {
        // backendId 可翻转替身（Harness 的 agent 是构造期 val——形态切换经 runner 属性翻转模拟）
        class TogglingBackendRunner : com.jiaoay.lark.bridge.agent.AgentRunner {
            var backend: String = "claude"
            override val backendId: String get() = backend
            override val isAvailable: Boolean get() = true
            override suspend fun run(
                request: com.jiaoay.lark.bridge.agent.AgentRequest,
                onLine: (com.jiaoay.lark.bridge.exec.AgentLine) -> Unit,
            ): com.jiaoay.lark.bridge.agent.AgentRunResult =
                com.jiaoay.lark.bridge.agent.AgentRunResult(exitCode = 0, summary = "toggling ok")
            override suspend fun resetSession(sessionKey: String): Boolean = false
        }
        val toggling = TogglingBackendRunner()
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            agentOverride = toggling,
        ).use { h ->
            // CLI 形态（backendId=claude）：ai 前缀照旧命中 AgentHandler。
            h.process(msg(messageId = "om_hot1", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(TaskKind.AGENT, h.outcomes[0].outcome.kind, "CLI 形态 ⇒ ai 前缀直走 agent 路由")

            // 热切为 App 形态（backendId=koog，同一 Harness 同一 runner）⇒ 前缀立即短路。
            toggling.backend = KOOG_BACKEND_ID
            h.process(msg(messageId = "om_hot2", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 2 })
            assertEquals(
                TaskKind.COMMAND,
                h.outcomes[1].outcome.kind,
                "切到 Koog 执行器后前缀即时短路落未命中（生产中由判定闭包转本地 AI 调度）——HOT 语义契约",
            )
        }
    }

    /**
     * 🔴 2026-09-14 真机批（可用性死锁修复契约）：isAvailable 是唯一卡点——端点装配后才配置时，
     * availability 翻真后前缀短路与兜底**同批激活**，无需重启桥接。
     * 生产对应：LarkBridgeManager 的 1Hz 端点刷新回路把 endpointRef 快照刷真 ⇒ 三个消费点
     * （判定闭包 / AgentHandler.match / DispatchAgentHandler.handle 第二闸）全部现读、即时生效。
     * lib 的 FakeAgentRunner isAvailable 是不可变 val ⇒ 本文件内声明可翻转替身。
     */
    @Test
    fun `调度开启且端点装配后才配-availability 翻真后短路与兜底同批激活（免重启）`() {
        class TogglingKoogRunner : com.jiaoay.lark.bridge.agent.AgentRunner {
            var available = false
            val requests = java.util.concurrent.CopyOnWriteArrayList<com.jiaoay.lark.bridge.agent.AgentRequest>()
            override val backendId: String get() = KOOG_BACKEND_ID
            override val isAvailable: Boolean get() = available
            override suspend fun run(
                request: com.jiaoay.lark.bridge.agent.AgentRequest,
                onLine: (com.jiaoay.lark.bridge.exec.AgentLine) -> Unit,
            ): com.jiaoay.lark.bridge.agent.AgentRunResult {
                requests += request
                return com.jiaoay.lark.bridge.agent.AgentRunResult(exitCode = 0, summary = "koog ok")
            }

            override suspend fun resetSession(sessionKey: String): Boolean = false
        }
        val toggling = TogglingKoogRunner()
        val asked = AtomicInteger(0)
        val fallback = ScriptedHandler(
            kind = com.jiaoay.lark.bridge.config.RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            matcher = { false },
            body = { ctx -> HandlerOutcome.success(TaskKind.AGENT, "dispatched:${ctx.text}") },
        )
        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = routes()),
            kinds = kindsFor(),
            agentOverride = toggling,
            fallbackHandlerProvider = { asked.incrementAndGet(); fallback },
        ).use { h ->
            // 第一发：端点未配（isAvailable=false）——死区回落：ai 前缀照旧命中 AgentHandler。
            h.process(msg(messageId = "om_tg1", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(TaskKind.AGENT, h.outcomes[0].outcome.kind, "不可用时 ai 前缀落回 AgentHandler（既有死区回落语义）")

            // 第二发（端点已配、刷新回路翻真 available）：前缀短路 + 兜底接住。
            toggling.available = true
            asked.set(0)
            h.process(msg(messageId = "om_tg2", text = "ai 帮我看看"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 2 })
            assertEquals(
                listOf("dispatched:ai 帮我看看"),
                h.allReplyBodies().takeLast(1),
                "availability 翻真后 ai 前缀被短路并落兜底（免重启激活）",
            )
            assertTrue(asked.get() >= 1, "兜底判定闭包应被询问")
        }
    }
}
