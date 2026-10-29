// M4-T9 补强（任务 21 / G-06 裁定走 A 部分）：装配点唯一性与四槽接线形态的**结构性**守护。
// 🔴 本文件只读装配点（反射只取 private 字段，绝不修改 BridgeAssembler.kt / Bridge.kt 一行源码）。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.RouteRule
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.handler.CompositeHandler
import com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink
import com.jiaoay.lark.bridge.pipeline.FakeAgentRunner
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.HandlerKinds
import com.jiaoay.lark.bridge.pipeline.HandlerWeight
import com.jiaoay.lark.bridge.pipeline.Harness
import com.jiaoay.lark.bridge.pipeline.MessageHandler
import com.jiaoay.lark.bridge.pipeline.PipelineRouter
import com.jiaoay.lark.bridge.pipeline.PipelineSnapshot
import com.jiaoay.lark.bridge.pipeline.PipelineSnapshotFactory
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.bridge.pipeline.routeKind
import com.jiaoay.lark.bridge.pipeline.stripCommentsAndStrings
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.bridge.pipeline.testRepoRoot
import com.jiaoay.lark.gateway.LarkTarget
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * 🔴 **装配点级**守护测试（BC 里被引用 16 次的 `AssemblerParityTest` 的实际落地形态）。
 *
 * ## 为什么必须有它（不能只靠纪律）
 * [BridgeAssembler] 是**全仓唯一装配点**（App 与 CLI 共用），`behavior-contract.md` 有 16 处
 * （:231/:233/:255/:395-406/:641/:658/:1216/:1261/:1318/:1421）把基线断言挂在一个**全仓并不存在**的
 * `AssemblerParityTest` 上。审计（`build/audit-bc-mapping.md` §A.4.3）核实结论是「**装配点级零覆盖**」：
 * [BridgeAssembler] 里 `HandlerKinds.build` 的四槽具体接线**没有任何测试断言其组合形态**——
 * `InteractionHandler` 的 LIGHT 档只在 handler 单元测试里对着**独立构造**的实例钉死，与装配点解耦。
 * ⇒ 若有人误改四槽接线（例如把 `InteractionHandler` 从 COMMAND 槽的 Composite 里删掉、或调换
 * `InteractionHandler`/`CommandHandler` 的先后），**现有 1142 条测试一条都不会 fail**。
 *
 * D16「删除静态全局可变状态」的消解必须是**结构性**的（BC :1216：「把 D16 从『靠纪律』变成
 * 『结构上不可能出错』」），因此本文件从**真实生产入口**观测装配结果，而不是重新搭一套假装配。
 *
 * ## 🔴 观测手法（审计 §A.5 明确认可「可通过反射或暴露 internal 探针」）
 * [BridgeAssembler.assemble] → [Bridge.createBundle]（`internal`，同模块 test 可见）→ [RuntimeBundle]
 * → 反射读 private `snapshotFactory` / `router` → 反射读 [PipelineSnapshotFactory] 的 private `kinds` / `deps`
 * → 反射读 [CompositeHandler] 的 private `children`。🔴 **反射只读**，装配点源码一行未改。
 *
 * ## 五组断言与 BC 对应
 * | 组 | 覆盖 | 断言 |
 * |---|---|---|
 * | ① | NF-18① / BC-135 / BC-279..288 废弃理由段(:406) | 同一 config 多次 assemble ⇒ handler 结构投影 `equals`（顺序与内容都相同） |
 * | ② | NF-18② / BC-135(:231) | main 源里装配点唯一（源码扫描）+ CLI 形态与 App 宿主形态产出等价 `BridgeConfig` 与等价 handler 列表 |
 * | ③ | §6.4 D16(:1216) / §8 D16 台账(:1318) | 四槽精确形态：SYSTEM=Composite[SystemHandler,StopHandler]、COMMAND=Composite[**InteractionHandler**,AskAnswerHandler,CommandHandler]、AGENT/FILE 各一个 |
 * | ④ | BC-280(:396)/BC-281(:397)/BC-282(:398)/BC-285(:401) | 四种配置变体下四槽形态不变、`CommandHandler` 始终被装配（内置技能开箱可用） |
 * | ⑤ | BC-152(:255) 装配点级另一半 / BC-279(:395) / BC-288(:404) | `routes` 数组顺序真正落到 handler 匹配顺序；前缀遮蔽在装配点级成立；COMMAND 槽内裁决 handler 恒在技能之前 |
 */
class BridgeAssemblerParityTest {

    // ==================================================================================
    // ① 装配确定性（NF-18① / BC-135 / BC-279..288 废弃理由段 :406）
    // ==================================================================================

    /**
     * 🔴 NF-18①：「同一 config 装配出的 handler 集合与顺序**确定且唯一**」——多次 assemble 结果 `equals`，
     * 无隐藏状态、无时间依赖、无随机顺序。
     */
    @Test
    fun `① 同一份 BridgeConfig 连续三次 assemble 产出的 handler 结构投影逐次 equals`() {
        val config = testConfig(routes = DefaultBridgeConfig.DEFAULT_ROUTES)
        val projections = (1..3).map { i ->
            assembleWith(config, "determinism$i").use { it.snapshot.structuralProjection() }
        }
        // 🔴 三次装配两两 equals（顺序与内容都相同）
        assertEquals(projections[0], projections[1], "第 1/2 次 assemble 产出不同 ⇒ 装配存在隐藏状态或随机顺序")
        assertEquals(projections[1], projections[2], "第 2/3 次 assemble 产出不同 ⇒ 装配存在时间依赖")
        // 🔴 并把投影钉死成字面值（顺序 + 内容 + 档位 + Composite 子 handler 类型序）。
        //    P3 配置删除批（2026-09-26）：AgentRoute 匹配键已退役（真源 = EngineReservedWords），
        //    槽位保留为 CLI 形态前缀直跑的装配载体（App 形态恒被 koog 短路）⇒ 默认三槽。
        assertEquals(
            listOf(
                "system:builtin:LIGHT:Composite[SystemHandler,StopHandler]",
                "agent:default:HEAVY:AgentHandler",
                "file:file-analysis:LIGHT:FileHandler",
            ),
            projections[0],
        )
    }

    /** 🔴 「handler **集合**与**顺序**确定且唯一」的另一半：`HandlerKinds` 表的登记顺序与快照 describe 也逐次一致。 */
    @Test
    fun `① 同一份 BridgeConfig 装配出的 HandlerKinds 登记顺序与快照 describe 确定且唯一`() {
        val config = testConfig(routes = DefaultBridgeConfig.DEFAULT_ROUTES)
        val first = assembleWith(config, "kindsOrderA")
        val second = assembleWith(config, "kindsOrderB")
        first.use { a ->
            second.use { b ->
                // 🔴 表是不可变 LinkedHashMap ⇒ 登记顺序恒为 SYSTEM→COMMAND→AGENT→FILE（无随机序）
                assertEquals(RouteKind.entries.toList(), a.kinds.registeredKinds.toList())
                assertEquals(a.kinds.registeredKinds.toList(), b.kinds.registeredKinds.toList())
                assertEquals(a.kinds.size, b.kinds.size)
                assertEquals(4, a.kinds.size)
                assertTrue(a.kinds.missingKinds().isEmpty())
                // 🔴 快照 describe 逐次一致（P3 后默认 routes 为 SYSTEM+AGENT+FILE ⇒ 快照三槽）
                assertEquals(a.snapshot.describe(), b.snapshot.describe())
                assertEquals(
                    listOf(
                        "system:builtin:LIGHT",
                        "agent:default:HEAVY",
                        "file:file-analysis:LIGHT",
                    ),
                    a.snapshot.describe(),
                )
            }
        }
    }

    // ==================================================================================
    // ② App↔CLI 装配等价 + 装配点唯一性（NF-18② / BC-135 :231）
    // ==================================================================================

    /**
     * 🔴 NF-18②「App 与 CLI 共用同一个 `assemble`」的**源码扫描**守护（形态参照 `LarkSdkIsolationTest`）。
     *
     * 断言写成「不存在 `:lark-bridge` 装配点之外的第二套装配逻辑」而非硬编码文件数：
     * M6 之后 `:feature-larkbridge` 会新增一个宿主入口，它**必须**调同一个 [BridgeAssembler.assemble]，
     * 而**绝不能**自己再拼一套 `HandlerKinds.build` / `PipelineSnapshotFactory` / `RuntimeBundle`。
     */
    @Test
    fun `② main 源里四槽接线与运行时束构造的落点集合精确且各只出现一次（禁止第二装配点）`() {
        val sources = normalizedMainSources()
        // 🔴 扫描自检 1：扫描面必须够大，否则「零命中」是假绿
        assertTrue(sources.size > 100, "main 源扫描面异常：只找到 ${sources.size} 个 .kt（路径过滤写错会造成假绿）")
        // 🔴 扫描自检 2：被扫描的集合里必须**包含**唯一装配点本身
        assertTrue(sources.any { it.first == ASSEMBLER_PATH }, "扫描面里没有 $ASSEMBLER_PATH ⇒ 本组断言全是假绿")

        // ① 四槽接线（HandlerKinds.build 的四个具名参数）全仓**有且仅有一处**
        assertEquals(
            listOf(ASSEMBLER_PATH),
            sources.filter { it.second.contains("HandlerKinds.build(") }.map { it.first },
            "🔴 出现第二处 HandlerKinds.build 接线 == 出现第二装配点（BC-135 / NF-18② 被破坏）",
        )
        assertEquals(1, totalOccurrences(sources, "HandlerKinds.build("))

        // ② 路由快照工厂的**构造点**唯一（`class PipelineSnapshotFactory(` 是声明处，故匹配赋值形态）
        val snapshotCtor = Regex("""=\s*PipelineSnapshotFactory\s*\(""")
        assertEquals(
            listOf(ASSEMBLER_PATH),
            sources.filter { snapshotCtor.containsMatchIn(it.second) }.map { it.first },
            "🔴 出现第二处 PipelineSnapshotFactory 构造 == 出现第二套路由装配",
        )

        // ③ 运行时束只在 Bridge.createBundle 一处构造
        assertEquals(
            listOf(BRIDGE_PATH),
            sources.filter { it.second.contains("RuntimeBundle.create(") }.map { it.first },
            "🔴 RuntimeBundle 只允许由 Bridge.createBundle 构造",
        )
        assertEquals(1, totalOccurrences(sources, "RuntimeBundle.create("))

        // ④ Bridge 实例只在装配点内构造（Bridge 的构造函数是 internal，18 个参数 ⇒ 绕过装配点必然显眼）
        val bridgeCtor = Regex("""return\s+Bridge\s*\(""")
        assertEquals(
            listOf(ASSEMBLER_PATH),
            sources.filter { bridgeCtor.containsMatchIn(it.second) }.map { it.first },
            "🔴 出现第二处 Bridge 构造 == 出现第二装配点",
        )
    }

    /**
     * 🔴 [BridgeAssembler.assemble] 的**调用点**白名单：只允许 CLI 与（M6 之后的）`:feature-larkbridge` 宿主。
     * 用**路径前缀白名单**而非硬编码文件数 ⇒ M6 新增宿主入口时本测试不用改，而任何其它模块
     * （`:composeApp` / `:core` / `:ui` / 第二个 CLI）新增装配入口会立刻红。
     */
    @Test
    fun `② main 源里 BridgeAssembler_assemble 的调用点只允许在 CLI 与 feature-larkbridge 宿主内`() {
        val sources = normalizedMainSources()
        val callSites = sources.filter { it.second.contains("BridgeAssembler.assemble(") }.map { it.first }
        // 🔴 扫描自检：CLI 至少有一处 assemble 调用（否则白名单断言是空的假绿）
        assertTrue(callSites.isNotEmpty(), "扫描面里一处 BridgeAssembler.assemble( 都没有 ⇒ 扫描本身失效")
        val outside = callSites.filter { path -> ASSEMBLE_CALL_SITE_ALLOWLIST.none { path.contains(it) } }
        assertEquals(
            emptyList(),
            outside,
            "🔴 在 CLI 与 :feature-larkbridge 之外发现第二套装配入口（BC-135「唯一装配点」被破坏）",
        )
        // 🔴 已知的两条 CLI 入口必须在（防止有人把它们删掉改成别的装配方式后白名单空转）
        assertTrue(callSites.any { it.endsWith("cli/ServeCommand.kt") }, "ServeCommand 不再调唯一装配点")
        assertTrue(callSites.any { it.endsWith("cli/HandleEventCommand.kt") }, "HandleEventCommand 不再调唯一装配点")
        // 🔴 声明处（object BridgeAssembler 内 `fun assemble(`）不算调用点
        assertFalse(callSites.any { it.endsWith("BridgeAssembler.kt") })
    }

    /**
     * 🔴 BC-135 的字面语义：「CLI 与宿主直接构造，对同一配置产出**等价** `BridgeConfig`」+
     * NF-18②「断言两条入口拿到等价 handler 列表」。
     *
     * CLI serve 形态 = `parentJob = null`（独立根）；App 宿主形态 = `parentJob = 宿主生命周期 Job`。
     * 二者只差 [BridgeAssembler.assemble] 的**入参**，装配代码是同一份 ⇒ 产物必须等价。
     */
    @Test
    fun `② BC-135 CLI 形态与 App 宿主形态经同一 assemble 入口产出等价 BridgeConfig 与等价 handler 列表`() {
        val config = testConfig(routes = DefaultBridgeConfig.DEFAULT_ROUTES)
        val hostJob = Job()
        try {
            assembleWith(config, "cliShape", parentJob = null).use { cli ->
                assembleWith(config, "appShape", parentJob = hostJob).use { app ->
                    // 🔴 对同一配置产出**等价** BridgeConfig（BC-135）
                    assertEquals(config, cli.bridge.config, "CLI 形态的生效配置与传入配置不等价")
                    assertEquals(config, app.bridge.config, "App 宿主形态的生效配置与传入配置不等价")
                    assertEquals(cli.bridge.config, app.bridge.config, "两条入口产出的 BridgeConfig 不等价")
                    // 🔴 NF-18②：两条入口拿到**等价 handler 列表**
                    assertEquals(cli.snapshot.structuralProjection(), app.snapshot.structuralProjection())
                    assertEquals(cli.snapshot.describe(), app.snapshot.describe())
                    assertEquals(cli.kinds.registeredKinds.toList(), app.kinds.registeredKinds.toList())
                    // 🔴 四槽逐槽形态也等价
                    for (kind in RouteKind.entries) {
                        assertEquals(
                            cli.kinds.slotShape(kind, SLOT_SAMPLES.getValue(kind), cli.deps),
                            app.kinds.slotShape(kind, SLOT_SAMPLES.getValue(kind), app.deps),
                            "$kind 槽在两条入口下形态不等价",
                        )
                    }
                }
            }
        } finally {
            hostJob.cancel()
        }
    }

    // ==================================================================================
    // ③ 四槽 Composite 组合形态（§6.4 D16 :1216 / §8 D16 台账 :1318）
    // ==================================================================================

    /**
     * 🔴 装配点产出的 [HandlerKinds] **四槽**精确形态（断言到 handler 的**类型与顺序**，不只断言数量）。
     * `SYSTEM = Composite[SystemHandler, StopHandler]`、`COMMAND = Composite[InteractionHandler, AskAnswerHandler, CommandHandler]`
     * （🔴 **InteractionHandler 必须在前**）、`AGENT`/`FILE` 各一个。
     */
    @Test
    fun `③ HandlerKinds 四槽精确形态 - SYSTEM 与 COMMAND 是 Composite 且子 handler 类型与顺序钉死`() {
        assembleWith(testConfig(routes = DefaultBridgeConfig.DEFAULT_ROUTES), "fourSlots").use { a ->
            // 四槽全登记、顺序确定、无缺失
            assertEquals(RouteKind.entries.toSet(), a.kinds.registeredKinds)
            assertEquals(RouteKind.entries.toList(), a.kinds.registeredKinds.toList())
            assertEquals(4, a.kinds.size)
            assertTrue(a.kinds.missingKinds().isEmpty(), "四槽有缺：${a.kinds.missingKinds()}")
            // 🔴 逐槽类型与顺序
            assertEquals("Composite[SystemHandler,StopHandler]", a.kinds.slotShape(RouteKind.SYSTEM, SystemRoute(), a.deps))
            assertEquals(
                "Composite[InteractionHandler,AskAnswerHandler,CommandHandler]",
                a.kinds.slotShape(RouteKind.COMMAND, CommandRoute(id = "s", aliases = listOf("/s"), promptTemplate = "/s {args}"), a.deps),
                "🔴 COMMAND 槽必须是 Composite[InteractionHandler, AskAnswerHandler, CommandHandler]（裁决在前、ask_user 回答居中）",
            )
            assertEquals("AgentHandler", a.kinds.slotShape(RouteKind.AGENT, AgentRoute(), a.deps))
            assertEquals("FileHandler", a.kinds.slotShape(RouteKind.FILE, FileRoute(), a.deps))
        }
    }

    /**
     * 🔴 默认 4 条 routes 经装配点建出的快照**逐位**是四槽产物，且两个 Composite 的子 handler 档位序也钉死：
     * SYSTEM = [LIGHT, URGENT]（SystemHandler / StopHandler——2026-09-11 引用流重设计后 PendingFileGateHandler 已删）、COMMAND = [LIGHT, HEAVY]
     * （🔴 首位 `InteractionHandler` 的**静态**档位是 **LIGHT** 而非 URGENT——「URGENT 的 cancelAll 会杀掉正在等
     * 这次裁决的 agent」，裁定理由逐字记在 `HandlerGateAndWeightTest.kt:313-331`；
     * 次位 `CommandHandler` 的**静态** `weight` 是 HEAVY（它可能委托 agent 长跑），其 LIGHT 档只在
     * `weightFor(ctx)` 的动态判定里出现（清单分支），故 `childWeights` 恒为 [LIGHT, HEAVY]）。
     * 🔴 默认 routes 已无 CommandRoute（产品裁定去业务化）⇒ COMMAND 槽断言移到 ④c 显式构造变体里；
     *    P3 配置删除批（2026-09-26）：AgentRoute 匹配键退役但槽位保留（CLI 前缀直跑装配载体）⇒
     *    默认仍是三槽。本条钉死默认三槽形态。
     */
    @Test
    fun `③ 默认 routes 的快照逐位对应三槽且 SYSTEM Composite 的子 handler 类型序与档位序钉死`() {
        assembleWith(testConfig(routes = DefaultBridgeConfig.DEFAULT_ROUTES), "snapshotSlots").use { a ->
            val entries = a.snapshot.entries
            assertEquals(3, entries.size)
            assertEquals(
                listOf("system:builtin", "agent:default", "file:file-analysis"),
                entries.map { "${it.route?.routeKind?.jsonValue}:${it.routeId}" },
            )
            // SYSTEM 槽
            val system = entries[0].handler
            assertTrue(system is CompositeHandler, "SYSTEM 槽不是 CompositeHandler：${system.shape()}")
            assertEquals(RouteKind.SYSTEM, system.kind)
            assertEquals(listOf("SystemHandler", "StopHandler"), system.childNames())
            assertEquals(listOf(HandlerWeight.LIGHT, HandlerWeight.URGENT), system.childWeights)
            // AGENT 槽（CLI 前缀直跑装配载体；App 形态 P2 起前缀恒短路）
            assertEquals("AgentHandler", entries[1].handler.shape())
            assertEquals(HandlerWeight.HEAVY, entries[1].handler.weight)
            // FILE 槽（单个）
            assertEquals("FileHandler", entries[2].handler.shape())
            assertEquals(HandlerWeight.LIGHT, entries[2].handler.weight, "🔴 2026-09-11 引用流重设计：FileHandler 仅登记+静默")
        }
    }

    // ==================================================================================
    // ④ 装配与 routes 配置解耦（BC-280 :396 / BC-281 :397 / BC-282 :398 / BC-285 :401）
    // ==================================================================================

    /** (a) BC-280：`routes` 里没有任何 `kind=command` 条目 ⇒ 四槽形态不变、`CommandHandler` 仍被装配（内置技能开箱可用）。 */
    @Test
    fun `④a routes 里没有任何 command 条目时四槽形态不变且 CommandHandler 仍被装配（BC-280）`() {
        val cfg = testConfig(
            routes = listOf(
                SystemRoute(),
                AgentRoute(),
                FileRoute(),
            ),
        )
        assertTrue(cfg.routes.none { it is CommandRoute }, "本变体前提：routes 里不能有 command 条目")
        assembleWith(cfg, "variantNoCommandRoute").use { a ->
            assertFourSlotsInvariant(a, "无 command route")
            // 🔴 配置层确实没有 COMMAND 条目，但**装配层**四槽照旧 ⇒ 「装配与 routes 配置解耦」成立
            assertTrue(a.snapshot.entries.none { it.route?.routeKind == RouteKind.COMMAND })
            assertEquals(3, a.snapshot.entries.size)
        }
    }

    /** (b) BC-281：全部 command route 都 `enabled=false` ⇒ 四槽形态不变、`CommandHandler` 仍被装配。 */
    @Test
    fun `④b 全部 command route 都 enabled=false 时四槽形态不变且 CommandHandler 仍被装配（BC-281）`() {
        val cfg = testConfig(
            routes = listOf(
                SystemRoute(),
                CommandRoute(id = "deploy", enabled = false, aliases = listOf("/deploy"), promptTemplate = "/deploy {args}"),
                CommandRoute(id = "extra", enabled = false, aliases = listOf("/e"), promptTemplate = "/e {args}"),
                AgentRoute(),
                FileRoute(),
            ),
        )
        assertEquals(2, cfg.routes.filterIsInstance<CommandRoute>().size)
        assertTrue(cfg.routes.filterIsInstance<CommandRoute>().none { it.enabled }, "本变体前提：command route 全禁用")
        assembleWith(cfg, "variantAllCommandDisabled").use { a ->
            assertFourSlotsInvariant(a, "全部 command route enabled=false")
            // 🔴 快照层因 enabled=false 跳过（BC-152），装配层四槽不受影响
            assertTrue(a.snapshot.entries.none { it.route?.routeKind == RouteKind.COMMAND })
            assertEquals(2L, a.snapshotFactory.disabledSkippedTotal)
        }
    }

    /** (c) BC-282：command route 的 `aliases` 为空 ⇒ 四槽形态不变、`CommandHandler` 仍被装配。 */
    @Test
    fun `④c command route 的 aliases 为空时四槽形态不变且 CommandHandler 仍被装配（BC-282）`() {
        // 🔴 默认 routes 已无 CommandRoute（产品裁定去业务化）⇒ 显式构造一条来验「aliases 为空」变体
        val cfg = testConfig(
            routes = DefaultBridgeConfig.DEFAULT_ROUTES + CommandRoute(
                id = "deploy",
                enabled = true,
                name = "部署",
                aliases = emptyList(),
                promptTemplate = "/deploy {args}",
            ),
        )
        assertTrue(cfg.routes.filterIsInstance<CommandRoute>().all { it.aliases.isEmpty() }, "本变体前提：aliases 全空")
        assembleWith(cfg, "variantEmptyAliases").use { a ->
            assertFourSlotsInvariant(a, "command route aliases 为空")
            // 🔴 别名为空 ⇒ route 仍 enabled ⇒ 快照里仍有 COMMAND 条目，装配点照旧给它同一个 Composite
            val entry = a.snapshot.entries.single { it.route?.routeKind == RouteKind.COMMAND }
            assertEquals("deploy", entry.routeId)
            assertEquals("Composite[InteractionHandler,AskAnswerHandler,CommandHandler]", entry.handler.shape())
            // 🔴 COMMAND 槽子 handler 类型序与静态档位序钉死（InteractionHandler 必须在前且为 LIGHT）
            val command = entry.handler as CompositeHandler
            assertEquals(listOf("InteractionHandler", "AskAnswerHandler", "CommandHandler"), command.childNames())
            assertEquals(listOf(HandlerWeight.LIGHT, HandlerWeight.LIGHT, HandlerWeight.HEAVY), command.childWeights)
        }
    }

    /**
     * (d) BC-285：agent 配置的恒定形态下装配不变——`enabled` 总闸已退役（agent 恒为硬依赖，
     * 不存在「关闸」配置态），装配形态恒定：四槽不变、`CommandHandler` 仍被装配（清单命令可用）。
     */
    @Test
    fun `④d agent 硬依赖恒定形态下四槽不变且 CommandHandler 仍被装配（BC-285）`() {
        val cfg = testConfig(routes = DefaultBridgeConfig.DEFAULT_ROUTES)
        assembleWith(cfg, "variantAgent").use { a ->
            assertFourSlotsInvariant(a, "agent 硬依赖（enabled 已退役）")
        }
    }

    // ==================================================================================
    // ⑤ 顺序与遮蔽语义（BC-152 :255 装配点级另一半 / BC-279 :395 / BC-288 :404）
    // ==================================================================================

    /**
     * 🔴 `routes` **数组顺序真正落到 handler 匹配顺序**（装配点级）：用一份**刻意打乱** kind 顺序的 routes
     * （reply→system→command→file→agent→reply）证明驱动匹配顺序的是**数组下标**而不是 kind 的枚举序，
     * 也不是默认 routes 的 `system→reply→command→agent→file` 惯例序。
     */
    @Test
    fun `⑤ routes 数组顺序在装配点级逐位落到快照的 handler 匹配顺序（而非 kind 枚举序）`() {
        val routes = listOf(
            CommandRoute(id = "r3", aliases = listOf("/three"), promptTemplate = "3"),
            SystemRoute(id = "builtin"),
            CommandRoute(id = "c1", aliases = listOf("/one"), promptTemplate = "/one {args}"),
            FileRoute(id = "f1"),
            AgentRoute(id = "a1"),
            CommandRoute(id = "r1", aliases = listOf("/one"), promptTemplate = "1"),
        )
        val cfg = testConfig(routes = routes)
        assembleWith(cfg, "arrayOrder").use { a ->
            assertEquals(listOf("r3", "builtin", "c1", "f1", "a1", "r1"), a.snapshot.entries.map { it.routeId })
            assertEquals(
                listOf("command", "system", "command", "file", "agent", "command"),
                a.snapshot.entries.map { it.route?.routeKind?.jsonValue },
            )
            assertEquals(
                listOf(
                    "command:r3:LIGHT",
                    "system:builtin:LIGHT",
                    "command:c1:LIGHT",
                    "file:f1:LIGHT",
                    "agent:a1:HEAVY",
                    "command:r1:LIGHT",
                ),
                a.snapshot.describe(),
            )
        }
    }

    /**
     * 🔴 BC-152 装配点级另一半：两条**同触发词**的 route ⇒ 数组前者胜。
     * 与 `RouteOrderTest` 的差别：那里的 COMMAND 槽是**假件** `CommandKinds`，这里跑的是**装配点真实产出**的
     * `HandlerKinds` ⇒ 真实 `CommandHandler` ⇒ 端到端（① intake → ② 准入 → ③ 执行 → ④ 回复）都是生产接线。
     * 🔴 路由替身 = 模板空白的 CommandRoute（清单分支直答 name，REPLY 槽已退役）。
     */
    @Test
    fun `⑤ BC-152 装配点级 - 两条同触发词 route 经真实 CommandHandler 端到端由数组前者胜`() {
        val routes = listOf(
            CommandRoute(id = "first", name = "pong-from-first", aliases = listOf("/dup"), promptTemplate = ""),
            CommandRoute(id = "second", name = "pong-from-second", aliases = listOf("/dup"), promptTemplate = ""),
        )
        val cfg = testConfig(routes = routes)
        assembleWith(cfg, "bc152").use { a ->
            assertEquals(listOf("first", "second"), a.snapshot.entries.map { it.routeId })
            // 🔴 把装配点**真实产出**的 kinds 接进全链路 Harness（与 T9 同形），跑一条真消息
            Harness(dispatcher = Dispatchers.Default, initialConfig = cfg, kinds = a.kinds).use { h ->
                h.process(msg(messageId = "om_parity_152", text = "/dup"))
                assertTrue(h.awaitUntil { h.outcomes.size >= 1 }, "等不到 ⑤ Observe 交接")
                assertEquals("first", h.outcomes[0].ctx.route?.id)
                // 🔴 端到端出站正文：真实 `CommandHandler` 清单分支经 `Responder.decorate` 排版后发出**前者**的直答
                // 🔴 CommandRoute 命中 ⇒ decorate 按「快捷指令」排版（原 REPLY 槽是「⚡ 指令」档，同形不同饰）
                assertEquals(listOf("**🧩 快捷指令 · ✅ 任务完成**\npong-from-first"), h.allReplyBodies())
                assertFalse(
                    h.allReplyBodies().any { it.contains("pong-from-second") },
                    "🔴 后者的 reply 被发出 ⇒ `routes` 数组顺序没有落到 handler 匹配顺序（BC-152 被破坏）",
                )
                assertEquals(listOf("first", "second"), h.router.snapshot.entries.map { it.routeId })
            }
        }
    }

    /**
     * 🔴 BC-279：「command/skill route 排在 agent route 前时的**前缀遮蔽**」在装配点级成立。
     * 旧断言是硬编码索引比较（`staticIdx < skillIdx < agentIdx`），BC :406 明确要求改写为
     * 「断言 `routes` 数组顺序即优先级、技能 route 排在 agent route 前（前缀遮蔽语义保留）」。
     * 🔴 两条路径都在 handler 内早期返回（执行器不可用即报错，**不起任何子进程**）。
     */
    @Test
    fun `⑤ BC-279 command route 排在 agent route 前时对 agent 触发前缀的遮蔽在装配点级端到端成立`() {
        val skill = CommandRoute(id = "skill", name = "技能", aliases = listOf("ai"), promptTemplate = "/s {args}")
        // P3 agent-first：AgentRoute 匹配键退役——前缀数据源 = EngineReservedWords（常量 "ai"/"/ai" 仍在），
        // 故本用例的「ai 前缀」命中语义不变，只需删掉已删参数。
        val agent = AgentRoute(id = "default")
        val cfg = testConfig(routes = listOf(skill, agent))
        assembleWith(cfg, "bc279").use { a ->
            // 装配点级：command 条目确实坐在 agent 条目之前
            assertEquals(listOf("skill", "default"), a.snapshot.entries.map { it.routeId })
            Harness(dispatcher = Dispatchers.Default, initialConfig = cfg, kinds = a.kinds).use { h ->
                h.process(msg(messageId = "om_parity_279_a", text = "ai 帮我分析这段日志"))
                assertTrue(h.awaitUntil { h.outcomes.size >= 1 }, "等不到第 1 条 ⑤ Observe 交接")
                // 🔴 前缀遮蔽成立：命中的是 command route（TaskKind.SKILL），不是 agent route
                assertEquals("skill", h.outcomes[0].ctx.route?.id)
                assertEquals(TaskKind.SKILL, h.outcomes[0].outcome.kind)

                // 🔴 反序（agent 在前）⇒ 同一条消息改由 agent route 命中 ⇒ 证明「数组顺序 == 优先级」在装配点级真的生效
                h.applyRoutes(listOf(agent, skill))
                assertEquals(listOf("default", "skill"), h.router.snapshot.entries.map { it.routeId })
                h.process(msg(messageId = "om_parity_279_b", text = "ai 帮我分析这段日志"))
                assertTrue(h.awaitUntil { h.outcomes.size >= 2 }, "等不到第 2 条 ⑤ Observe 交接")
                assertEquals("default", h.outcomes[1].ctx.route?.id)
                assertEquals(TaskKind.AGENT, h.outcomes[1].outcome.kind)
            }
        }
    }

    /**
     * 🔴 BC-288：「无技能配置时**宿主执行器仍排在技能之后**」（旧 ids ==
     * `["ping", CardActionExecutor.EXECUTOR_ID, QuickCommandExecutor.EXECUTOR_ID, "host-custom"]`）
     * 在新架构的对应语义 = **COMMAND 槽 Composite 内 `InteractionHandler`（承接宿主裁决/卡片回传的执行器）
     * 恒排在 `CommandHandler`（技能）之前** + `routes` 数组序即优先级。
     *
     * 🔴 判别手法（零协程、零副作用、可单独失败）：[CompositeHandler.weightFor] 委托给
     * `children.firstOrNull { it.match(ctx) }` ⇒ 观测**动态档位**即可反推 select 命中了哪个子 handler：
     * - 裁决形态文本 `确认 abcd1234`：两个子 handler **都** match，但 `InteractionHandler`(LIGHT) 在前 ⇒ **LIGHT**；
     *   若有人调换顺序，同一 ctx 会得到 `CommandHandler.weightFor` 的 **HEAVY**（别名 `确认` 命中 + 模板非空）。
     * - 纯技能文本 `确认 一下这个日志`：只有 `CommandHandler` match ⇒ **HEAVY**。
     */
    @Test
    fun `⑤ BC-288 COMMAND 槽内裁决 handler 恒排在技能 handler 之前（weightFor 反推 select 命中序）`() {
        val commandRoute = CommandRoute(id = "decide", name = "裁决别名", aliases = listOf("确认"), promptTemplate = "/c {args}")
        val cfg = testConfig(routes = listOf(SystemRoute(), commandRoute, AgentRoute(), FileRoute()))
        assembleWith(cfg, "bc288").use { a ->
            val composite = a.snapshot.entries.single { it.route?.routeKind == RouteKind.COMMAND }.handler
            assertTrue(composite is CompositeHandler)
            // 🔴 结构面：裁决执行器在技能之前
            assertEquals(listOf("InteractionHandler", "AskAnswerHandler", "CommandHandler"), composite.childNames())
            assertEquals(listOf(HandlerWeight.LIGHT, HandlerWeight.LIGHT, HandlerWeight.HEAVY), composite.childWeights)

            val registry = ProcessRegistry(2)
            try {
                // 🔴 行为面 1：裁决形态文本（两个子 handler 都 match）⇒ 命中**前者** InteractionHandler ⇒ LIGHT
                val decisionCtx = ctxOf("确认 abcd1234", commandRoute, cfg, registry)
                assertTrue(composite.match(decisionCtx))
                assertEquals(
                    HandlerWeight.LIGHT,
                    composite.weightFor(decisionCtx),
                    "🔴 裁决文本拿到 HEAVY ⇒ COMMAND 槽里 CommandHandler 被排到了 InteractionHandler 之前（BC-288 被破坏）",
                )
                // 🔴 行为面 2：纯技能文本（只有 CommandHandler match）⇒ HEAVY，证明上面的 LIGHT 不是常量退化
                val skillCtx = ctxOf("确认 一下这个日志", commandRoute, cfg, registry)
                assertTrue(composite.match(skillCtx))
                assertEquals(HandlerWeight.HEAVY, composite.weightFor(skillCtx))
                // 🔴 行为面 3：两者都不 match 的文本 ⇒ 回落到 Composite 的静态 weight（= children.first().weight = LIGHT）
                val neitherCtx = ctxOf("ping", commandRoute, cfg, registry)
                assertFalse(composite.match(neitherCtx))
                assertEquals(HandlerWeight.LIGHT, composite.weightFor(neitherCtx))
            } finally {
                runCatching { registry.close() }
            }
        }
    }

    // ==================================================================================
    // ⑥ 网关工厂唯一化（G-F / NF-18②「App 与 CLI 装配等价」的网关侧延伸）
    // ==================================================================================

    /**
     * 🔴 G-F：全仓**只有一处**构造 `SdkLarkGateway`（在 `BridgeGateways.sdk`），App 宿主与 CLI 都经
     * [BridgeGateways] 取网关。修法前 `cli/ServeCommand.buildSdkGateway`（internal）与
     * `LarkBridgeManager.buildGateway`（feature 侧）各拼一份 12 行同构实现 ⇒ lib 改网关装配入参时 App 侧不会红构建。
     * 形态参照本文件 ② 组的源码扫描断言（归一化源 + 路径集合相等）。
     */
    @Test
    fun `⑥ main 源里构造 SdkLarkGateway 的落点唯一且只在 BridgeGateways（G-F 网关工厂去重）`() {
        val sources = normalizedMainSources()
        // 🔴 扫描自检 1：扫描面够大（否则「唯一命中」是假绿）
        assertTrue(sources.size > 100, "main 源扫描面异常：只找到 ${sources.size} 个 .kt（路径过滤写错会造成假绿）")
        // 🔴 扫描自检 2：扫描面里必须**包含**唯一网关工厂本身
        assertTrue(sources.any { it.first == GATEWAYS_PATH }, "扫描面里没有 $GATEWAYS_PATH ⇒ 本断言是假绿")

        // ① 构造点唯一：匹配 `SdkLarkGateway(`，用负向后顾排除 `class SdkLarkGateway(` 声明（在 :lark-gateway）
        val ctor = Regex("""(?<!class )SdkLarkGateway\s*\(""")
        val ctorFiles = sources.filter { ctor.containsMatchIn(it.second) }.map { it.first }
        assertEquals(
            listOf(GATEWAYS_PATH),
            ctorFiles,
            "🔴 出现第二处 SdkLarkGateway 构造 == App/CLI 网关装配又分叉了（G-F 缺口复发）",
        )
        assertEquals(1, totalOccurrences(sources, "SdkLarkGateway(") - countOccurrences(
            sources.first { it.first == "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/SdkLarkGateway.kt" }.second,
            "SdkLarkGateway(",
        ), "扣除 :lark-gateway 的类声明后，SdkLarkGateway( 构造应恰好一处")

        // ② CLI 与 App 宿主都必须经唯一工厂取网关（防止绕过 BridgeGateways 直接注入自拼 SdkLarkGateway）
        assertTrue(
            sources.any { it.first.endsWith("cli/ServeCommand.kt") && it.second.contains("BridgeGateways.resolve(") },
            "ServeCommand 不再经 BridgeGateways.resolve 取网关",
        )
        assertTrue(
            sources.any { it.first.endsWith("cli/SendCommand.kt") && it.second.contains("BridgeGateways.resolve(") },
            "SendCommand 不再经 BridgeGateways.resolve 取网关",
        )
        assertTrue(
            sources.any { it.first.endsWith("page/larkbridge/LarkBridgeManager.kt") && it.second.contains("BridgeGateways.resolve(") },
            "App 宿主 LarkBridgeManager 不再经 BridgeGateways.resolve 取网关",
        )
    }

    /**
     * 🔴 审计透传与引用锚定单源的结构性守护（2026-09-12 对抗评审补测）。
     *
     * 两条接线此前**测试覆盖为零**——它们被删掉时全仓测试一条都不会红，故障却是静默的：
     * 1. `onOutcome` 里 `attachments/rejectedArtifacts = outcome.…takeIf { it.isNotEmpty() }`：删掉后
     *    `audit.jsonl` 静默缺这两列，而「被拒清单落审计」正是本轮取证修复的全部价值。
     * 2. `replyToMessageId = RequestContextFactory.replyAnchorOf(message)`：此处曾是内联副本，与 companion
     *    双写。漂移的表现是「测试背书的判定 ≠ 生产运行的判定」——ReplySinkAnchoringTest 全绿而生产行为不动。
     * 形态参照本文件既有的源码扫描守卫（同 `LarkSdkIsolationTest` 惯例：用 `normalizedMainSources()` 扫描）。
     */
    @Test
    fun `⑧ onOutcome 审计透传与锚定单源接线不可被静默删除`() {
        val sources = normalizedMainSources()
        val assembler = sources.single { it.first == ASSEMBLER_PATH }.second

        assertTrue(
            assembler.contains("attachments = outcome.attachments.takeIf"),
            "onOutcome 不再把产物清单透传给审计 seam ⇒ audit.jsonl 静默缺 attachments 列",
        )
        assertTrue(
            assembler.contains("rejectedArtifacts = outcome.rejectedArtifacts.takeIf"),
            "🔴 onOutcome 不再透传被拒清单 ⇒ 「被拒条目落审计」这一取证修复静默失效（用户只能翻飞书聊天记录）",
        )
        assertTrue(
            assembler.contains("replyToMessageId = RequestContextFactory.replyAnchorOf("),
            "🔴 生产构造点不再调用 replyAnchorOf（单源判定）——改回内联副本会让锚定测试与生产行为静默分叉",
        )
        assertFalse(
            assembler.contains("if (message.chatType == com.jiaoay.lark.gateway.ChatType.P2P) null else message.messageId"),
            "🔴 生产构造点出现内联的 P2P 判定副本 == 判定双源漂移（应只经 replyAnchorOf）",
        )
    }

    /**
     * 🔴 批 6 死线事故守护（2026-09-14 真机静默根因）：`assemble` 收下 `fallbackHandlerProvider`
     * 参数后，构造 `Bridge(...)` 时漏传——Bridge 构造参数默认 null ⇒ `createBundle` →
     * `RuntimeBundle.create` → `Admission` 拿到 null ⇒ **App 侧整条未命中兜底调度链路从未接通**，
     * 消息静默落 `unhandledReply`。既有 `FallbackDispatchTest` 在 RuntimeBundle 层直注 seam，
     * 装配链的透传断裂对它不可见（全绿假象）——本断言把透传钉死在源码扫描层。
     */
    @Test
    fun `⑧ assemble 到 Bridge 构造的 fallbackHandlerProvider 透传不可被静默删除`() {
        val sources = normalizedMainSources()
        val assembler = sources.single { it.first == ASSEMBLER_PATH }.second
        val bridge = sources.single { it.first == BRIDGE_PATH }.second

        // 🔴 透传点必须存在（= 形态出现在 Bridge(...) 构造调用里；2026-09-14 修复前此断言为红）
        assertTrue(
            assembler.contains("fallbackHandlerProvider = fallbackHandlerProvider"),
            "🔴 assemble 收下 fallbackHandlerProvider 却没透传给 Bridge 构造 ⇒ App 侧兜底调度死线复发" +
                "（真机表现：开调度后未命中消息静默无回复）",
        )
        // 🔴 消费点必须存在（Bridge.createBundle 把字段递给 RuntimeBundle.create——透传链的另一半）
        assertTrue(
            bridge.contains("fallbackHandlerProvider = fallbackHandlerProvider"),
            "🔴 Bridge.createBundle 不再把 seam 递给 RuntimeBundle.create ⇒ 兜底调度链路在运行时束断线",
        )
        // 🔴 扫描自检：fallbackHandlerProvider 在装配点必须「收参 + 透传」成对出现（恰好 2 处
        //    具名实参 + KDoc 提及不计——归一化已剥注释）
        assertEquals(
            2,
            countOccurrences(assembler, "fallbackHandlerProvider = "),
            "🔴 装配点里 fallbackHandlerProvider 具名实参出现 ${countOccurrences(assembler, "fallbackHandlerProvider = ")} 次（期望 2：RuntimeBundle.create 判定包装 + Bridge 构造透传）",
        )
    }

    /**
     * ⑨ xlogKeyFallback 透传守护（2026-09-15 私钥装配级统一批）。
     *
     * 🔴 与 ⑧ 组同型事故防线：`assemble` 收下 `xlogKeyFallback` 后若构造 `Bridge(...)` 时漏传，
     * 构造参数默认 null ⇒ App 侧宿主回落缝**静默死线**（decrypt_log/自动解密永远读不到
     * XLog 解密页私钥，单测直注 enrichXlogKey 纯函数对此不可见）——把透传钉死在源码扫描层。
     */
    @Test
    fun `⑨ assemble 到 Bridge 构造的 xlogKeyFallback 透传不可被静默删除`() {
        val sources = normalizedMainSources()
        val assembler = sources.single { it.first == ASSEMBLER_PATH }.second
        val bridge = sources.single { it.first == BRIDGE_PATH }.second

        // 🔴 透传点必须存在（assemble → Bridge 构造）
        assertTrue(
            assembler.contains("xlogKeyFallback = xlogKeyFallback"),
            "🔴 assemble 收下 xlogKeyFallback 却没透传给 Bridge 构造 ⇒ App 侧宿主回落缝死线" +
                "（真机表现：桥接私钥留空时自动解密/decrypt_log 拿不到 XLog 解密页私钥）",
        )
        // 🔴 消费点必须存在（Bridge.createBundle 把回落缝接进 configProvider 富化——透传链的另一半）
        assertTrue(
            bridge.contains("enrichXlogKey(configRef.get(), xlogKeyFallback)"),
            "🔴 Bridge.createBundle 不再把回落缝接进 configProvider 富化 ⇒ 富化链在运行时束断线",
        )
        // 🔴 扫描自检：装配点透传恰 1 处具名实参（KDoc 提及不计——归一化已剥注释；
        //    ⑧ 组同款针法：`xxx = ` 后缀把「收参声明」与「KDoc 提及」都排除在计数外）
        assertEquals(
            1,
            countOccurrences(assembler, "xlogKeyFallback = "),
            "🔴 装配点里 xlogKeyFallback 具名透传出现 ${countOccurrences(assembler, "xlogKeyFallback = ")} 次（期望 1：Bridge 构造透传）",
        )
    }

    /**
     * ⑩ audioTranscriber 透传守护（语音消息转写链）。
     *
     * 🔴 与 ⑧/⑨ 组同型事故防线：`assemble` 收下 `audioTranscriber`（宿主语音转写缝）后若
     * 构造 `Bridge(...)` 时漏传，构造参数默认 null ⇒ App 侧语音链路**静默死线**（语音消息
     * 永远走未支持降级文案，宿主闭包从未接通），单测直注 AudioHandler 对此不可见——把
     * 透传链钉死在源码扫描层。
     */
    @Test
    fun `⑩ assemble 到 RuntimeBundle 的 audioTranscriber 透传与 AUDIO 承接不可被静默删除`() {
        val sources = normalizedMainSources()
        val assembler = sources.single { it.first == ASSEMBLER_PATH }.second
        val bridge = sources.single { it.first == BRIDGE_PATH }.second

        // 🔴 透传点必须存在（assemble → Bridge 构造）
        assertTrue(
            assembler.contains("audioTranscriber = audioTranscriber"),
            "🔴 assemble 收下 audioTranscriber 却没透传给 Bridge 构造 ⇒ App 侧语音转写链死线" +
                "（真机表现：发语音永远收到「暂不支持」降级文案）",
        )
        // 🔴 消费点必须存在（Bridge.createBundle 把缝递给 RuntimeBundle.create——透传链的另一半）
        assertTrue(
            bridge.contains("audioTranscriber = audioTranscriber"),
            "🔴 Bridge.createBundle 不再把转写缝递给 RuntimeBundle.create ⇒ 语音链路在运行时束断线",
        )
        // 🔴 扫描自检：装配点透传恰 1 处具名实参（KDoc 提及不计——归一化已剥注释；⑧/⑨ 组同款针法）
        assertEquals(
            1,
            countOccurrences(assembler, "audioTranscriber = "),
            "🔴 装配点里 audioTranscriber 具名透传出现 ${countOccurrences(assembler, "audioTranscriber = ")} 次（期望 1：Bridge 构造透传）",
        )
        // 🔴 承接点必须存在：未命中兜底判定闭包必须对 AUDIO kind 构造 AudioHandler
        //    （删掉本分支 ⇒ AUDIO 消息回落 runUnhandled 降级文案，宿主闭包静默旁置）
        assertTrue(
            assembler.contains("AudioHandler(deps, audioTranscriber, downloader, referencedFileResolver)"),
            "🔴 兜底判定闭包不再对 AUDIO 构造 AudioHandler ⇒ 语音消息不再进转写链",
        )
    }

    /**
     * ⑪ startupProbe 透传守护（启动自检推送链）。
     *
     * 🔴 与 ⑧/⑨/⑩ 组同型事故防线：`assemble` 收下 `startupProbe`（宿主启动自检缝）后若
     * 构造 `Bridge(...)` 时漏传，构造参数默认 null ⇒ App 侧启动自检推送**静默死线**（config
     * 开了也永远不探查不推送，config 门控单测对此不可见）——把透传链钉死在源码扫描层。
     */
    @Test
    fun `⑪ assemble 到 Bridge 构造的 startupProbe 透传与启动触发消费不可被静默删除`() {
        val sources = normalizedMainSources()
        val assembler = sources.single { it.first == ASSEMBLER_PATH }.second
        val bridge = sources.single { it.first == BRIDGE_PATH }.second

        // 🔴 透传点必须存在（assemble → Bridge 构造）
        assertTrue(
            assembler.contains("startupProbe = startupProbe"),
            "🔴 assemble 收下 startupProbe 却没透传给 Bridge 构造 ⇒ App 侧启动自检推送死线" +
                "（真机表现：config 开了自检也永远收不到推送）",
        )
        // 🔴 消费点必须存在（start 成功路径调用 launchStartupProbe——透传链的另一半）
        assertTrue(
            bridge.contains("launchStartupProbe(bundle, cfg)"),
            "🔴 Bridge.start 不再调用 launchStartupProbe ⇒ 启动触发链在门面断线（自检永不触发）",
        )
        // 🔴 缝缺席 fail-closed 消费形态必须存在（null = CLI/测试形态恒不探查）
        assertTrue(
            bridge.contains("val probe = startupProbe ?: return"),
            "🔴 launchStartupProbe 不再判缝缺席即返回 ⇒ CLI/测试形态会被误触发探查（fail-closed 破口）",
        )
        // 🔴 扫描自检：装配点透传恰 1 处具名实参（KDoc 提及不计——归一化已剥注释；⑧/⑨/⑩ 组同款针法）
        assertEquals(
            1,
            countOccurrences(assembler, "startupProbe = "),
            "🔴 装配点里 startupProbe 具名透传出现 ${countOccurrences(assembler, "startupProbe = ")} 次（期望 1：Bridge 构造透传）",
        )
    }

    // ==================================================================================
    // ⑦ 生产闭包行为集成（R9：「未命中 TEXT 恒进调度 agent」的端到端语义——
    //    FallbackDispatchTest 等经 Harness 直注 seam 测不到装配点闭包，此处补 assemble 级守护）
    // ==================================================================================

    /**
     * 🔴 R9：经**真实生产装配**（BridgeAssembler.assemble + 注入 host 式 `fallbackHandlerProvider = { null }`）
     * 钉死未命中 TEXT 的新语义——判定闭包只剩 TEXT 形态与非空白（不再读 agent.enabled / isAvailable）：
     * 未命中 TEXT **恒收编**进 DispatchAgentHandler；调度 agent 不可用时由其内闸产出
     * `AGENT_CONFIG_INVALID` **显式报错卡出站**（非静默、非 unmatched 计数——「运行中不可用显式失败」
     * 是硬依赖的运行时载体，静默回落分支已拆除）。
     */
    @Test
    fun `⑦ R9 未命中 TEXT 恒收编进调度 agent - 不可用产出 AGENT_CONFIG_INVALID 报错卡（非静默非 unmatched）`() {
        val cfg = testConfig(routes = DefaultBridgeConfig.DEFAULT_ROUTES)
        val unavailableKoog = FakeAgentRunner(backendId = "koog", isAvailable = false)
        assembleWith(
            cfg,
            "r9Fallback",
            agentRunnerOverride = unavailableKoog,
            fallbackHandlerProvider = { null }, // App 形态宿主注入形态：全权交给装配点的调度 handler 兜底
        ).use { a ->
            // 🔴 装配点**真实产出**的 kinds 与**真实兜底闭包**接进全链路 Harness——
            // 闭包不随 kinds 走（assemble 内独立缝进 Admission），经 bundle.admission 反射取回
            // （privateField 失手即抛，防假绿）；ctx.agent 用不可用 Koog 替身
            // （DispatchAgentHandler 内闸现读 ctx.agent.isAvailable ⇒ 报错卡路径）
            val productionFallback = a.bundle.privateField("admission")?.privateField("fallbackHandlerProvider")
                as? ((RequestContext) -> MessageHandler?)
                ?: error("装配产物未携带 fallbackHandlerProvider（装配契约变更——请同步本守护测试）")
            Harness(
                dispatcher = Dispatchers.Default,
                initialConfig = cfg,
                kinds = a.kinds,
                agentOverride = unavailableKoog,
                fallbackHandlerProvider = productionFallback,
            ).use { h ->
                h.process(msg(messageId = "om_parity_r9", text = "随便聊聊"))
                assertTrue(h.awaitUntil { h.outcomes.size >= 1 }, "等不到 ⑦ Observe 交接")
                val outcome = h.outcomes[0].outcome
                assertEquals(TaskErrorCodes.AGENT_CONFIG_INVALID, outcome.errorCode, "不可用 ⇒ 显式报错卡（errorCode 面向审计分桶）")
                assertEquals(TaskStatus.FAILURE, outcome.status, "FAILURE 而非 SKIPPED——未命中不再走静默分支")
                assertEquals(0L, h.counters.unmatched, "未命中 TEXT 被闭包收编 ⇒ 不计 unmatched（静默分支不再触达）")
                // 路由级未命中照常计数——fallback 收编发生在路由判定之后，不改写路由统计
                assertEquals(1L, h.router.unmatchedCount)
                assertTrue(
                    h.allReplyBodies().any { it.contains("本地 AI 未就绪") },
                    "报错卡必须出站且含可读指引（非静默），实际出站=${h.allReplyBodies()}",
                )
            }
        }
    }
}

// ======================================================================================
// 顶层私有工具（🔴 纪律：工具函数用顶层声明，禁 object/companion）
// ======================================================================================

/** 唯一装配点的仓库相对路径（源码扫描断言的期望值）。 */
private const val ASSEMBLER_PATH = "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/BridgeAssembler.kt"

/** 🔴 唯一网关工厂的仓库相对路径（⑥ 组 G-F 源码扫描断言的期望值：全仓只此一处构造 SdkLarkGateway）。 */
private const val GATEWAYS_PATH = "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/BridgeGateways.kt"

/** [Bridge.createBundle] 所在文件（`RuntimeBundle.create` 的唯一合法调用点）。 */
private const val BRIDGE_PATH = "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/Bridge.kt"

/**
 * [BridgeAssembler.assemble] 调用点的**路径前缀白名单**（🔴 刻意不硬编码文件数）：
 * - `:lark-bridge` 的 CLI（现有 `ServeCommand` / `HandleEventCommand`）；
 * - `:feature-larkbridge`（M6-T12 之后的 App 宿主入口）。
 * 任何其它模块出现调用点 ⇒ 存在第二套装配逻辑 ⇒ 红。
 */
private val ASSEMBLE_CALL_SITE_ALLOWLIST: List<String> = listOf(
    "lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/cli/",
    "feature-larkbridge/src/",
)

/** ④ 逐槽形态断言用的样本 route（每个 kind 一条最小可用条目）。 */
private val SLOT_SAMPLES: Map<RouteKind, RouteRule> = mapOf(
    RouteKind.SYSTEM to SystemRoute(id = "builtin"),
    RouteKind.COMMAND to CommandRoute(id = "sample", aliases = listOf("/sample"), promptTemplate = "/sample {args}"),
    RouteKind.AGENT to AgentRoute(id = "sample"),
    RouteKind.FILE to FileRoute(id = "sample"),
)

/**
 * 一次**真实装配**的可观测投影。
 * 🔴 [RuntimeBundle] 的 `router`/`snapshotFactory`、[PipelineSnapshotFactory] 的 `kinds`/`deps`、
 *    [CompositeHandler] 的 `children` 都是 private ⇒ 反射只读（审计 §A.5 认可的手法）。
 */
private class Assembled(val bridge: Bridge, val bundle: RuntimeBundle) : AutoCloseable {

    val snapshotFactory: PipelineSnapshotFactory = bundle.privateField("snapshotFactory") as PipelineSnapshotFactory
    val router: PipelineRouter = bundle.privateField("router") as PipelineRouter
    val kinds: HandlerKinds = snapshotFactory.privateField("kinds") as HandlerKinds
    val deps: HandlerDeps = snapshotFactory.privateField("deps") as HandlerDeps

    /** 装配点按传入配置建出的**首张**路由快照。 */
    val snapshot: PipelineSnapshot get() = router.snapshot

    override fun close() {
        bridge.close()
    }
}

/**
 * 经**真实生产入口** [BridgeAssembler.assemble] 装配一个 [Bridge]，再用 [Bridge.createBundle] 取出本周期运行时束。
 * 🔴 `dataRoot` 一律是新建的临时目录（绝不触碰 `~/.toolbelt`）；`gatewayFactory` 注入 [SyntheticGateway]
 * （NF-18：引擎侧开发与测试完全不依赖 SDK 可用性）；`consoleLog=false` 保持测试输出干净。
 * 🔴 不 `start()`：只观测装配产物，不连网、不绑端口、不起子进程。
 */
private fun assembleWith(
    config: BridgeConfig,
    tag: String,
    parentJob: Job? = null,
    agentRunnerOverride: com.jiaoay.lark.bridge.agent.AgentRunner? = null,
    fallbackHandlerProvider: ((RequestContext) -> MessageHandler?)? = null,
): Assembled {
    val dataRoot = Files.createTempDirectory("bridge-parity-$tag-${System.nanoTime()}")
    dataRoot.toFile().deleteOnExit()
    val bridge = BridgeAssembler.assemble(
        dataRoot = dataRoot,
        gatewayFactory = { SyntheticGateway() },
        defaultConfig = config,
        parentJob = parentJob,
        consoleLog = false,
        agentRunnerOverride = agentRunnerOverride,
        fallbackHandlerProvider = fallbackHandlerProvider,
    )
    return Assembled(bridge, bridge.createBundle(config))
}

/** 沿继承链反射读 private 字段（只读；找不到即抛，避免「反射失手 ⇒ 假绿」）。 */
private fun Any.privateField(name: String): Any? {
    var klass: Class<*>? = this::class.java
    while (klass != null) {
        val field = klass.declaredFields.firstOrNull { it.name == name }
        if (field != null) {
            field.isAccessible = true
            return field.get(this)
        }
        klass = klass.superclass
    }
    throw AssertionError("反射失败：${this::class.java.name} 无字段 '$name'（装配点结构已变，请同步更新本守护测试）")
}

/** [CompositeHandler] 的子 handler 列表（private `children`，反射只读）。 */
@Suppress("UNCHECKED_CAST")
private fun CompositeHandler.compositeChildren(): List<MessageHandler> = privateField("children") as List<MessageHandler>

/** 子 handler 的类名序（🔴 断言类型与顺序，不只断言数量）。 */
private fun CompositeHandler.childNames(): List<String> = compositeChildren().map { it::class.simpleName ?: "?" }

/** handler 的**结构名**：CompositeHandler 展开成 `Composite[子...]`（递归），其余取类的 simpleName。 */
private fun MessageHandler.shape(): String =
    if (this is CompositeHandler) {
        "Composite[" + compositeChildren().joinToString(",") { it.shape() } + "]"
    } else {
        this::class.simpleName ?: "?"
    }

/**
 * 一张快照的**结构投影**：逐条 `kind:routeId:weight:handler 结构名`。
 * 🔴 [MessageHandler] 不实现 `equals`（也不该为了测试去实现）⇒ NF-18① 的「多次 assemble 结果 equals」
 *    只能对**结构投影**成立：它同时钉住了顺序、route 归属、动态档位与 Composite 子 handler 类型序。
 */
private fun PipelineSnapshot.structuralProjection(): List<String> =
    entries.map { "${it.route?.routeKind?.jsonValue}:${it.routeId}:${it.weight}:${it.handler.shape()}" }

/** 从**真实的** [HandlerKinds] 表取出某槽工厂，用 [sample] 造一个 handler，返回其结构名。 */
private fun HandlerKinds.slotShape(kind: RouteKind, sample: RouteRule, deps: HandlerDeps): String {
    val factory = factoryFor(kind) ?: throw AssertionError("四槽缺一：$kind 未登记 handler 工厂")
    return factory.create(sample, deps).shape()
}

/**
 * ④ 的公共断言体：四槽形态不变 + `CommandHandler` 始终被装配。
 * 🔴 断言在 **HandlerKinds 表**层面（不是快照层面）：`PipelineSnapshotFactory.build` 会跳过 `enabled=false`
 *    与未出现在 `routes` 里的 kind，所以「装配与 routes 配置解耦」只能在表层面观测——这正是 BC-280/281/282/285
 *    「无技能配置 / 技能全禁用 / 别名为空时执行器仍被装配，使内置技能开箱可用」的新架构对应物。
 */
private fun assertFourSlotsInvariant(a: Assembled, variant: String) {
    assertEquals(RouteKind.entries.toSet(), a.kinds.registeredKinds, "[$variant] 四槽登记集合变了")
    assertEquals(RouteKind.entries.toList(), a.kinds.registeredKinds.toList(), "[$variant] 四槽登记顺序变了")
    assertEquals(4, a.kinds.size, "[$variant] 四槽条目数变了")
    assertTrue(a.kinds.missingKinds().isEmpty(), "[$variant] 有 kind 未登记：${a.kinds.missingKinds()}")
    for (kind in RouteKind.entries) {
        assertEquals(
            EXPECTED_SLOT_SHAPES.getValue(kind),
            a.kinds.slotShape(kind, SLOT_SAMPLES.getValue(kind), a.deps),
            "[$variant] $kind 槽的 handler 组合形态变了",
        )
    }
    // 🔴 CommandHandler 始终被装配，且仍在 InteractionHandler 之后
    val commandChildren = (a.kinds.factoryFor(RouteKind.COMMAND)
        ?.create(SLOT_SAMPLES.getValue(RouteKind.COMMAND), a.deps) as? CompositeHandler)
        ?.childNames()
    assertEquals(
        listOf("InteractionHandler", "AskAnswerHandler", "CommandHandler"),
        commandChildren,
        "[$variant] COMMAND 槽子 handler 的类型或顺序变了",
    )
}

/** 四槽的期望形态（🔴 与 ③ 的字面断言同源，避免两处漂移）。 */
private val EXPECTED_SLOT_SHAPES: Map<RouteKind, String> = mapOf(
    RouteKind.SYSTEM to "Composite[SystemHandler,StopHandler]",
    RouteKind.COMMAND to "Composite[InteractionHandler,AskAnswerHandler,CommandHandler]",
    RouteKind.AGENT to "AgentHandler",
    RouteKind.FILE to "FileHandler",
)

/** 全仓 main 源（`src/main/kotlin` 与 `src/jvmMain/kotlin`，排除 `build/`）下的 `.kt` 文件。 */
private fun mainKotlinSources(): List<File> {
    val out = ArrayList<File>()
    testRepoRoot().walkTopDown()
        .onEnter { dir -> dir.name != "build" && dir.name != ".git" && dir.name != ".gradle" && dir.name != ".kotlin" }
        .filter { it.isFile && it.extension == "kt" }
        .filter { f ->
            val p = f.absolutePath.replace(File.separatorChar, '/')
            p.contains("/src/main/kotlin/") || p.contains("/src/jvmMain/kotlin/")
        }
        .forEach { out += it }
    return out.sortedBy { it.absolutePath }
}

/**
 * 归一化后的 main 源：`仓库相对路径` to `剥离注释与字符串字面量后的源码`。
 * 🔴 「禁止出现 X」型断言必须用归一化源（KDoc 里对 `HandlerKinds.build` 的**提及**会造成假红），
 *    手法对齐 `LarkSdkIsolationTest.normalizeKotlinSource`。
 */
private fun normalizedMainSources(): List<Pair<String, String>> {
    val root = testRepoRoot()
    return mainKotlinSources().map { f ->
        f.relativeTo(root).path.replace(File.separatorChar, '/') to stripCommentsAndStrings(f.readText())
    }
}

/** 字面量在全部 main 源里的**总出现次数**（不只是文件数）。 */
private fun totalOccurrences(sources: List<Pair<String, String>>, literal: String): Int =
    sources.sumOf { (_, src) -> countOccurrences(src, literal) }

private fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var idx = haystack.indexOf(needle)
    while (idx >= 0) {
        count++
        idx = haystack.indexOf(needle, idx + needle.length)
    }
    return count
}

/** 造一个 [RequestContext]（⑤ BC-288 的 `weightFor` 判别用；不跑协程、不出站）。 */
private fun ctxOf(text: String, route: RouteRule, config: BridgeConfig, registry: ProcessRegistry): RequestContext {
    val message = msg(text = text)
    return RequestContext(
        message = message,
        config = config,
        route = route,
        reply = RecordingReplySink(LarkTarget.ofInbound(message)),
        progress = ConflatedProgressSink(),
        log = BridgeLog.Silent,
        agent = FakeAgentRunner(),
        processes = registry,
    )
}
