// M4-T7 测试：路由快照与「数组顺序 == 优先级」（RouteOrderTest）+ 快照原子热替换（PipelineSnapshotTest）。
// 🔴 路由替身 = CommandRoute + ScriptedHandler 单表（REPLY 槽已退役；本套断言只依赖「数组序即优先级」）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.RouteRule
import com.jiaoay.lark.bridge.config.SystemRoute
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers

/** 造一个「按首别名精确匹配、回复 route.promptTemplate」的 kinds 单表，并统计工厂调用次数。 */
internal class CommandKinds {
    val creates = AtomicInteger(0)
    val handlers = mutableListOf<ScriptedHandler>()

    fun kinds(): HandlerKinds = HandlerKinds.build(
        command = HandlerFactory { route, _ ->
            creates.incrementAndGet()
            val r = route as CommandRoute
            ScriptedHandler(
                kind = RouteKind.COMMAND,
                weight = HandlerWeight.LIGHT,
                matcher = { ctx -> ctx.text == r.aliases.firstOrNull() },
                body = { HandlerOutcome.success(TaskKind.COMMAND, r.promptTemplate) },
            ).also { handlers.add(it) }
        },
    )
}

/** [PipelineRouter] / [PipelineSnapshot]：🔴 routes 数组顺序即优先级，不再有硬编码装配链。 */
class RouteOrderTest {

    private fun harnessWith(routes: List<RouteRule>, kinds: HandlerKinds, config: BridgeConfig? = null) = Harness(
        dispatcher = Dispatchers.Default,
        initialConfig = config ?: testConfig(routes = routes),
        kinds = kinds,
    )

    @Test
    fun `🔴 两条同触发词的 route ⇒ routes 数组前者胜（顺序即优先级）`() {
        val rk = CommandKinds()
        val routes = listOf(
            CommandRoute(id = "first", aliases = listOf("ping"), promptTemplate = "pong-from-first"),
            CommandRoute(id = "second", aliases = listOf("ping"), promptTemplate = "pong-from-second"),
        )
        harnessWith(routes, rk.kinds()).use { h ->
            h.process(msg(messageId = "om_r1", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("pong-from-first"), h.allReplyBodies())
            assertEquals("first", h.outcomes[0].ctx.route?.id)
            assertEquals(listOf("first", "second"), h.router.snapshot.describe().map { it.split(":")[1] })
        }
    }

    @Test
    fun `🔴 数组顺序在快照里逐位保序（describe 与配置同序）`() {
        val rk = CommandKinds()
        val routes = listOf(
            CommandRoute(id = "c", aliases = listOf("c"), promptTemplate = "C"),
            CommandRoute(id = "a", aliases = listOf("a"), promptTemplate = "A"),
            CommandRoute(id = "b", aliases = listOf("b"), promptTemplate = "B"),
        )
        harnessWith(routes, rk.kinds()).use { h ->
            assertEquals(
                listOf("command:c:LIGHT", "command:a:LIGHT", "command:b:LIGHT"),
                h.router.describe(),
            )
            assertEquals(3, h.router.snapshot.size)
        }
    }

    @Test
    fun `后位 route 在前位不匹配时命中（顺序不等于「只看第一条」）`() {
        val rk = CommandKinds()
        val routes = listOf(
            CommandRoute(id = "ping", aliases = listOf("ping"), promptTemplate = "pong"),
            CommandRoute(id = "help", aliases = listOf("help"), promptTemplate = "here-is-help"),
        )
        harnessWith(routes, rk.kinds()).use { h ->
            h.process(msg(messageId = "om_r2", text = "help"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("here-is-help"), h.allReplyBodies())
            assertEquals("help", h.outcomes[0].ctx.route?.id)
        }
    }

    @Test
    fun `🔴 enabled=false 的 route 被跳过且不占优先级位次（BC-152）`() {
        val rk = CommandKinds()
        val routes = listOf(
            CommandRoute(id = "disabled", enabled = false, aliases = listOf("ping"), promptTemplate = "should-not-win"),
            CommandRoute(id = "enabled", aliases = listOf("ping"), promptTemplate = "pong-from-enabled"),
        )
        harnessWith(routes, rk.kinds()).use { h ->
            assertEquals(1, h.router.snapshot.size, "disabled 的 route 不进快照")
            assertEquals(1L, h.snapshotFactory.disabledSkippedTotal)
            h.process(msg(messageId = "om_r3", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("pong-from-enabled"), h.allReplyBodies())
            assertEquals("enabled", h.outcomes[0].ctx.route?.id)
        }
    }

    @Test
    fun `全部 route 都 disabled ⇒ 快照为空 ⇒ 走未命中兜底（TEXT 恒静默）`() {
        val rk = CommandKinds()
        val routes = listOf(CommandRoute(id = "d1", enabled = false, aliases = listOf("ping")))
        harnessWith(routes, rk.kinds(), testConfig(routes = routes)).use { h ->
            assertEquals(0, h.router.snapshot.size)
            h.process(msg(messageId = "om_r4", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(emptyList(), h.allReplyBodies(), "TEXT 未命中恒静默（P3：模板已删）")
            assertEquals(1L, h.counters.unmatched)
        }
    }

    @Test
    fun `🔴 未知 kind（HandlerKinds 未登记）⇒ 该 route 被跳过 ⇒ 未命中走 runUnhandled 兜底`() {
        val routes = listOf(SystemRoute(), CommandRoute(id = "r", aliases = listOf("ping"), promptTemplate = "pong"))
        // 只登记 COMMAND ⇒ SYSTEM 属「未知 kind」
        val rk = CommandKinds()
        harnessWith(routes, rk.kinds()).use { h ->
            assertEquals(1, h.router.snapshot.size)
            assertEquals(0L, h.snapshotFactory.disabledSkippedTotal, "它不是 disabled，是未登记的 kind")
            assertEquals(1L, h.snapshotFactory.unknownKindSkippedTotal, "SYSTEM 未登记 ⇒ 计入未知 kind 跳过")

            h.process(msg(messageId = "om_r5", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("pong"), h.allReplyBodies())
        }
    }

    @Test
    fun `kind 全部未登记 ⇒ 未命中且 unmatched 计数`() {
        harnessWith(listOf(SystemRoute()), HandlerKinds.EMPTY).use { h ->
            assertNull(h.router.route(h.contexts.create(msg(messageId = "om_r6"))))
            assertEquals(1L, h.router.unmatchedCount)
            assertEquals(0L, h.router.matchedCount)
            assertEquals(0L, h.router.publishedCount)
        }
    }

    @Test
    fun `🔴 routeKind 扩展是穷尽 when：四个 sealed 子类各自映射到正确的 RouteKind`() {
        assertEquals(RouteKind.SYSTEM, SystemRoute().routeKind)
        assertEquals(RouteKind.COMMAND, CommandRoute().routeKind)
        assertEquals(RouteKind.AGENT, AgentRoute().routeKind)
        assertEquals(RouteKind.FILE, FileRoute().routeKind)
        assertEquals(4, RouteKind.entries.size)
        assertEquals(listOf("system", "command", "agent", "file"), RouteKind.entries.map { it.jsonValue })
    }

    @Test
    fun `Admission taskKindOf 也是穷尽 when（四值全覆盖，无 else）`() {
        assertEquals(TaskKind.COMMAND, Admission.taskKindOf(RouteKind.SYSTEM))
        assertEquals(TaskKind.COMMAND, Admission.taskKindOf(RouteKind.COMMAND))
        assertEquals(TaskKind.AGENT, Admission.taskKindOf(RouteKind.AGENT))
        assertEquals(TaskKind.AGENT, Admission.taskKindOf(RouteKind.FILE))
    }

    @Test
    fun `HandlerKinds build 只登记非 null 的 kind（新增一类能力只加一行）`() {
        val empty = HandlerKinds.build()
        assertEquals(0, empty.size)
        assertEquals(RouteKind.entries.toSet(), empty.missingKinds())

        val two = HandlerKinds.build(
            system = HandlerFactory { _, _ -> ScriptedHandler(kind = RouteKind.SYSTEM) },
            agent = HandlerFactory { _, _ -> ScriptedHandler(kind = RouteKind.AGENT) },
        )
        assertEquals(2, two.size)
        assertEquals(setOf(RouteKind.SYSTEM, RouteKind.AGENT), two.registeredKinds)
        assertEquals(setOf(RouteKind.COMMAND, RouteKind.FILE), two.missingKinds())
        assertTrue(two.contains(RouteKind.SYSTEM))
        assertFalse(two.contains(RouteKind.FILE))
        assertNull(two.factoryFor(RouteKind.FILE))
    }
}

/** [PipelineSnapshot] 的预计算 + [AtomicReference] 无锁热替换（HOT 应用落点）。 */
class PipelineSnapshotTest {

    @Test
    fun `快照预计算：构建期一次性 create handler，热路径 resolve 零工厂调用`() {
        val rk = CommandKinds()
        val routes = listOf(
            CommandRoute(id = "a", aliases = listOf("a"), promptTemplate = "A"),
            CommandRoute(id = "b", aliases = listOf("b"), promptTemplate = "B"),
        )
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = routes), kinds = rk.kinds()).use { h ->
            assertEquals(2, rk.creates.get(), "构建快照时每条 route 各 create 一次")
            assertEquals(1L, h.snapshotFactory.buildCount)

            // 🔴 热路径：1 万次 resolve 不得再调用工厂、不得重建快照、不得换 handler 实例
            val ctxs = (1..10_000).map { h.contexts.create(msg(messageId = "om_$it", text = if (it % 2 == 0) "a" else "b")) }
            val entries = ctxs.map { h.router.route(it) }
            assertEquals(2, rk.creates.get(), "resolve 不得触发工厂")
            assertEquals(1L, h.snapshotFactory.buildCount, "resolve 不得重建快照")
            assertEquals(2, entries.map { System.identityHashCode(it) }.distinct().size, "只允许命中 2 个预计算实例")
            assertSame(h.router.snapshot.entries[0], entries.first { it?.routeId == "a" })
            assertSame(h.router.snapshot.entries[1], entries.first { it?.routeId == "b" })
            assertSame(rk.handlers[0], h.router.snapshot.entries[0].handler)
        }
    }

    @Test
    fun `resolve 把命中 route 定格进 ctx，未命中置 null`() {
        val rk = CommandKinds()
        val routes = listOf(CommandRoute(id = "a", aliases = listOf("a"), promptTemplate = "A"))
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = routes), kinds = rk.kinds()).use { h ->
            val hit = h.contexts.create(msg(text = "a"))
            val entry = h.router.route(hit)
            assertEquals("a", entry?.routeId)
            assertEquals("a", hit.route?.id)
            // 🔴 typedRoute<T>() 是 ctx.route 的类型安全读取（handler 靠它拿到自己的 route 字段）
            assertEquals(listOf("a"), hit.typedRoute<CommandRoute>()?.aliases)
            assertEquals("A", hit.typedRoute<CommandRoute>()?.promptTemplate)

            val miss = h.contexts.create(msg(text = "zzz"))
            assertNull(h.router.route(miss))
            assertNull(miss.route, "未命中必须把 ctx.route 置回 null（否则会带着上一条的 route 进 Observe）")
        }
    }

    @Test
    fun `🔴 配置变更时快照原子替换：publish 返回旧快照，router 立即读到新快照`() {
        val rk = CommandKinds()
        val routesV1 = listOf(CommandRoute(id = "v1", aliases = listOf("ping"), promptTemplate = "pong-v1"))
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = routesV1), kinds = rk.kinds()).use { h ->
            val v1 = h.router.snapshot
            assertEquals(0L, h.router.publishedCount, "构建初始快照不算 publish")

            // 🔴 applyRoutes 返回**新发布的那张**快照（内部：build 新快照 → router.publish → 返回 built）
            val v2 = h.applyRoutes(listOf(CommandRoute(id = "v2", aliases = listOf("ping"), promptTemplate = "pong-v2")))
            assertEquals(1L, h.router.publishedCount)
            assertSame(v2, h.router.snapshot, "publish 后 router 立即读到新快照")
            assertFalse(v1 === h.router.snapshot)
            assertEquals("v2", h.router.snapshot.entries[0].routeId)

            // 🔴 publish 的返回值是**被替换掉的旧快照**（T9 可用它做「旧 handler 优雅收尾」编排）：
            //    此处再 publish 同一张 v3 ⇒ 被替换掉的就是 v3 自己（幂等替换，快照内容不变）。
            val v3 = h.applyRoutes(listOf(CommandRoute(id = "v3", aliases = listOf("ping"), promptTemplate = "pong-v3")))
            val replaced = h.router.publish(v3)
            assertSame(v3, replaced, "publish 返回被替换掉的旧快照（这里就是刚发布的 v3）")
            assertNotSame(v2, replaced, "旧快照必须真的被换掉")
            assertEquals(3L, h.router.publishedCount)

            h.process(msg(messageId = "om_hot1", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("pong-v3"), h.allReplyBodies())
            assertEquals("v3", h.outcomes[0].ctx.route?.id)
        }
    }

    @Test
    fun `HOT 替换后新消息立即走新快照，旧快照不再被读到`() {
        val rk = CommandKinds()
        val v1 = listOf(CommandRoute(id = "v1", aliases = listOf("ping"), promptTemplate = "pong-v1"))
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = v1), kinds = rk.kinds()).use { h ->
            h.process(msg(messageId = "om_hot2", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("pong-v1"), h.allReplyBodies())

            h.applyRoutes(listOf(CommandRoute(id = "v2", aliases = listOf("ping"), promptTemplate = "pong-v2")))
            h.process(msg(messageId = "om_hot3", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 2 })
            assertEquals(listOf("pong-v1", "pong-v2"), h.outcomes.map { it.summary })
            assertEquals(2L, h.snapshotFactory.buildCount)
        }
    }

    @Test
    fun `🔴 在途任务不受快照替换影响（HOT 语义：正在跑的 handler 继续用旧实例跑完）`() {
        val gate = CompletableDeferred<Unit>()
        val oldHandler = ScriptedHandler(
            kind = RouteKind.AGENT,
            weight = HandlerWeight.HEAVY,
            matcher = { true },
            body = { gate.await(); HandlerOutcome.success(TaskKind.AGENT, "old-handler-done") },
        )
        val oldCreates = AtomicInteger(0)
        val newHandler = ScriptedHandler(kind = RouteKind.AGENT, weight = HandlerWeight.HEAVY, body = { HandlerOutcome.success(TaskKind.AGENT, "new") })
        val kindsV1 = HandlerKinds.build(agent = HandlerFactory { _, _ -> oldCreates.incrementAndGet(); oldHandler })
        val kindsV2 = HandlerKinds.build(agent = HandlerFactory { _, _ -> newHandler })

        Harness(
            dispatcher = Dispatchers.Default,
            initialConfig = testConfig(routes = listOf(AgentRoute())),
            kinds = kindsV1,
        ).use { h ->
            h.process(msg(messageId = "om_inflight", text = "ai 干活"))
            assertTrue(h.awaitUntil { oldHandler.invocations.get() >= 1 })
            assertEquals(1, h.admission.inFlight)

            // 🔴 在途时替换 handler 表（模拟 T9 的「配置变更 ⇒ 重建快照」）
            h.router.publish(PipelineSnapshotFactory(kindsV2, HandlerDeps(h.configProvider, com.jiaoay.lark.bridge.BridgeLog.Silent)).build(h.config))

            gate.complete(Unit)
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals("old-handler-done", h.outcomes[0].summary, "在途任务必须用旧 handler 跑完")
            assertEquals(0, newHandler.invocations.get(), "新 handler 不得被在途任务调用")
            assertSame(newHandler, h.router.snapshot.entries[0].handler, "快照本身已换成新表")
        }
    }

    @Test
    fun `快照构建期的三个丢弃计数（D18：所有丢弃点可观测）`() {
        val throwing = HandlerKinds.build(
            command = HandlerFactory { _, _ -> throw IllegalStateException("坏 route") },
            system = HandlerFactory { _, _ -> ScriptedHandler(kind = RouteKind.SYSTEM) },
        )
        val routes = listOf(
            CommandRoute(id = "boom", aliases = listOf("x")),
            CommandRoute(id = "off", enabled = false, aliases = listOf("y")),
            AgentRoute(id = "unknown-kind"),
            SystemRoute(id = "ok"),
        )
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = routes), kinds = throwing).use { h ->
            assertEquals(1, h.router.snapshot.size, "只有 SystemRoute 进快照")
            assertEquals("ok", h.router.snapshot.entries[0].routeId)
            assertEquals(1L, h.snapshotFactory.factoryFailureTotal)
            assertEquals(1L, h.snapshotFactory.disabledSkippedTotal)
            assertEquals(1L, h.snapshotFactory.unknownKindSkippedTotal)
            assertEquals(1L, h.snapshotFactory.buildCount)
        }
    }

    @Test
    fun `一条坏 route 不拖垮整张快照（工厂抛异常只跳过该条）`() {
        val rk = CommandKinds()
        val mixed = HandlerKinds.build(
            command = HandlerFactory { route, deps ->
                if ((route as CommandRoute).id == "boom") throw IllegalStateException("坏 route") else rk.kinds().let { it.factoryFor(RouteKind.COMMAND)!!.create(route, deps) }
            },
        )
        val routes = listOf(
            CommandRoute(id = "boom", aliases = listOf("ping")),
            CommandRoute(id = "good", aliases = listOf("ping"), promptTemplate = "pong"),
        )
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = routes), kinds = mixed).use { h ->
            assertEquals(1, h.router.snapshot.size)
            assertEquals("good", h.router.snapshot.entries[0].routeId)
            h.process(msg(messageId = "om_bad", text = "ping"))
            assertTrue(h.awaitUntil { h.outcomes.size >= 1 })
            assertEquals(listOf("pong"), h.allReplyBodies())
        }
    }

    @Test
    fun `空 routes ⇒ 空快照且不抛`() {
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = emptyList()), kinds = HandlerKinds.EMPTY).use { h ->
            assertEquals(0, h.router.snapshot.size)
            assertEquals(emptyList(), h.router.describe())
            assertEquals(1L, h.snapshotFactory.buildCount, "初始快照构建一次")
            assertNull(h.router.route(h.contexts.create(msg())))
        }
    }

    @Test
    fun `toString 便于排障（含 entry 数与 route 数）`() {
        val rk = CommandKinds()
        val routes = listOf(CommandRoute(id = "a", aliases = listOf("a")))
        Harness(dispatcher = Dispatchers.Default, initialConfig = testConfig(routes = routes), kinds = rk.kinds()).use { h ->
            val text = h.router.snapshot.toString()
            assertTrue(text.contains("entries=1"), text)
            assertTrue(text.contains("routes=1"), text)
            // 🔴 MatchableHandler.toString = "MatchableHandler(<RouteKind.name>/<routeId>, <weight>)"
            assertTrue(h.router.snapshot.entries[0].toString().contains("COMMAND/a"), h.router.snapshot.entries[0].toString())
            assertTrue(h.router.snapshot.entries[0].toString().contains("LIGHT"), h.router.snapshot.entries[0].toString())
        }
    }
}
