// M4-T9 测试：桥接门面 Bridge 的生命周期契约（幂等 start/stop、失败归一 Refused、单实例锁 D19、HOT/RESTART 分流）
// + 唯一装配点 BridgeAssembler 的端到端接通性 + stop 十步优雅关闭的可观测断言（TeardownCompletenessTest）。
// 🔴 全部经 BridgeAssembler.assemble 装配（App 与 CLI 唯一共用装配点），网关用 SyntheticGateway 替身，
//    不连真飞书、不 spawn 真进程；断言面 = 门面可观测状态（BridgeStartResult / status / 网关计数 / 落盘文件）。
// 🔴 Bridge.start()/applyConfig()/stop() 均已 suspend（设计裁定 2026-09-08，task 18）：每个挂起调用各包一层
//    **顺序**（非嵌套）runBlocking；`.use{}` 触发的 close()（内含唯一一层 runBlocking）留在最外层，绝不与这些 runBlocking 嵌套。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.BridgeJson
import com.jiaoay.lark.bridge.config.ConfigPaths
import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.config.WorkspaceConfig
import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import com.jiaoay.lark.bridge.observe.BlockedReason
import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.awaitUntil
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.gateway.GatewayConnectResult
import com.jiaoay.lark.gateway.GatewayErrorCode
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundResource
import com.jiaoay.lark.bridge.config.NativeToolGrant
import com.jiaoay.lark.gateway.ChatHistoryPage
import com.jiaoay.lark.gateway.ChatInfo
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.ReadReceiptPage
import com.jiaoay.lark.gateway.ReactionBrief
import com.jiaoay.lark.gateway.SendResult
import com.jiaoay.lark.gateway.WikiNodeRef
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("bridge-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

/** 带凭据的生命周期配置（appId 非空 ⇒ 通过预检；routes 含 SYSTEM+AGENT+FILE ⇒ ping/文件/引用全链可路由）。 */
private fun lifecycleConfig(appId: String = "cli_lifecycle_app"): BridgeConfig =
    testConfig(routes = listOf(SystemRoute(), AgentRoute(), FileRoute()))
        .copy(connection = ConnectionConfig(appId = appId, appSecret = "secret", region = "feishu"))

/** 经唯一装配点组装一座 Bridge；每次 start 造的 SyntheticGateway 记进 [gateways]（可编程 connectResult）。 */
private fun assemble(
    dataRoot: Path,
    config: BridgeConfig,
    gateways: CopyOnWriteArrayList<SyntheticGateway>,
    connectResult: GatewayConnectResult = GatewayConnectResult.Connected("ou_bot", "Bot"),
): Bridge = BridgeAssembler.assemble(
    dataRoot = dataRoot,
    gatewayFactory = { SyntheticGateway().also { g -> g.connectResult = connectResult; gateways.add(g) } },
    defaultConfig = config,
    consoleLog = false,
)

class BridgeLifecycleTest {

    @Test
    fun `🔴 缺 appId ⇒ Refused(ConfigInvalid)，不建连、不创建网关、状态 Idle（绝不抛异常）`() {
        val root = tempRoot("noappid")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val config = testConfig(routes = listOf(SystemRoute()))
            .copy(connection = ConnectionConfig(appId = "", appSecret = ""))
        assemble(root, config, gateways).use { bridge ->
            val result = runBlocking { bridge.start() }
            assertTrue(result is BridgeStartResult.Refused, "缺凭据必须归一为 Refused，实际=$result")
            assertEquals(BridgeErrorCode.ConfigInvalid, (result as BridgeStartResult.Refused).code)
            assertFalse(bridge.isRunning)
            assertEquals(0, gateways.size, "预检失败在 createBundle 之前 ⇒ 不得创建网关")
            assertTrue(bridge.status.value.connection is BridgeConnectionStatus.Idle)
        }
    }

    @Test
    fun `正常 start ⇒ Started，建连一次，状态 Connected，isRunning=true`() {
        val root = tempRoot("start")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            assertTrue(bridge.isRunning)
            assertEquals(1, gateways.size, "一次 start 造一个网关")
            assertEquals(1, gateways[0].connectCount)
            assertTrue(bridge.status.value.running)
            assertTrue(bridge.status.value.connection is BridgeConnectionStatus.Connected)
        }
    }

    @Test
    fun `🔴 幂等 start ⇒ 第二次 AlreadyRunning，不重复建连、不造第二个网关`() {
        val root = tempRoot("idem")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            assertEquals(BridgeStartResult.AlreadyRunning, runBlocking { bridge.start() })
            assertEquals(1, gateways.size)
            assertEquals(1, gateways[0].connectCount)
        }
    }

    @Test
    fun `🔴 端到端：灌一条 ping ⇒ 经唯一装配点接通的管线回复 pong（证明装配不是空壳）`() {
        val root = tempRoot("e2e")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val gw = gateways[0]
            gw.emitInbound(msg(messageId = "om_ping_e2e", text = "ping"))
            assertTrue(
                awaitUntil { gw.outboundTexts.any { t -> t.contains("pong") } },
                "ping 应经 intake→route→SystemHandler→reply→gateway 回复 pong，实际出站=${gw.outboundTexts}",
            )
        }
    }

    @Test
    fun `stop ⇒ 网关 disconnect+close 各一次，状态回 Idle，isRunning=false`() {
        val root = tempRoot("stop")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            runBlocking { bridge.stop() }
            assertFalse(bridge.isRunning)
            assertEquals(1, gateways[0].disconnectCount)
            assertEquals(1, gateways[0].closeCount)
            assertTrue(bridge.status.value.connection is BridgeConnectionStatus.Idle)
        }
    }

    @Test
    fun `🔴 幂等 stop ⇒ 第二次 no-op，不再动网关`() {
        val root = tempRoot("idemstop")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            runBlocking { bridge.stop() }
            runBlocking { bridge.stop() }
            assertEquals(1, gateways[0].disconnectCount, "第二次 stop 不得再 disconnect")
            assertEquals(1, gateways[0].closeCount, "第二次 stop 不得再 close")
        }
    }

    @Test
    fun `🔴 连接失败 ⇒ Refused(ConnectFailed)，teardown 已跑（close），锁已释放，状态 Disconnected`() {
        val root = tempRoot("connfail")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val bridge = assemble(
            root,
            lifecycleConfig(),
            gateways,
            GatewayConnectResult.Failed(GatewayErrorCode.ConnectFatal, larkMsg = "凭据无效"),
        )
        bridge.use {
            val result = runBlocking { it.start() }
            assertTrue(result is BridgeStartResult.Refused, "连接失败必须归一为 Refused，实际=$result")
            assertEquals(BridgeErrorCode.ConnectFailed, (result as BridgeStartResult.Refused).code)
            assertFalse(it.isRunning)
            assertTrue(it.status.value.connection is BridgeConnectionStatus.Disconnected)
            assertEquals(1, gateways[0].closeCount, "失败路径必须跑 teardown（释放网关）")
        }
        // 🔴 锁已释放：同 dataRoot + appId 的新实例可正常 start（否则失败会永久占锁）
        val gateways2 = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways2).use { bridge2 ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge2.start() })
        }
    }

    @Test
    fun `🔴 D19 单实例互斥：同 dataRoot+appId 第二实例被拒 BlockedByPeerInstance；第一个释放后第二个可 start`() {
        val root = tempRoot("d19")
        val g1 = CopyOnWriteArrayList<SyntheticGateway>()
        val g2 = CopyOnWriteArrayList<SyntheticGateway>()
        val b1 = assemble(root, lifecycleConfig(), g1)
        val b2 = assemble(root, lifecycleConfig(), g2)
        try {
            assertEquals(BridgeStartResult.Started, runBlocking { b1.start() })

            val refused = runBlocking { b2.start() }
            assertTrue(refused is BridgeStartResult.Refused, "第二个实例必须被单实例锁挡下，实际=$refused")
            assertEquals(BridgeErrorCode.BlockedByPeerInstance, (refused as BridgeStartResult.Refused).code)
            assertEquals(0, g2.size, "被锁挡下在 createBundle 之前 ⇒ 不造网关")
            val blocked = b2.status.value.connection
            assertTrue(blocked is BridgeConnectionStatus.Blocked)
            assertEquals(BlockedReason.PEER_INSTANCE, (blocked as BridgeConnectionStatus.Blocked).reason)

            // 第一个释放锁后，第二个可正常启动
            runBlocking { b1.stop() }
            assertEquals(BridgeStartResult.Started, runBlocking { b2.start() })
        } finally {
            runCatching { b2.close() }
            runCatching { b1.close() }
        }
    }

    @Test
    fun `applyConfig HOT 字段 ⇒ HotApplied，不断连，配置生效且策略恒全放行`() {
        val root = tempRoot("hot")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val connectBefore = gateways[0].connectCount
            val next = bridge.config.copy(agent = bridge.config.agent.copy(groupMode = "all"))
            assertEquals(ConfigApplyResult.HotApplied, runBlocking { bridge.applyConfig(next) })
            assertEquals(connectBefore, gateways[0].connectCount, "HOT 不得重连")
            // 🔴 配置域照常生效（HOT 语义不变；群@门控真源 = agent.groupMode，P3 起 access 域无门控字段）……
            assertTrue(bridge.config.agent.groupMode == "all")
            // ……但**不再下推给 SDK**（2026-09-12 真机 bug 修复，见 BridgeGateways.policy KDoc）：
            // SDK 的 safety.PolicyGate 判定颗粒度是会话级、不区分消息类型——群文件消息物理上无法带 @，
            // requireMention=true 时会被 SDK 拦在 "message" 事件之前，桥接侧针对 FILE 的豁免永远执行不到。
            // 故 SDK 侧**恒全放行**，群@门控完全由桥接侧 PolicyGate 承担。
            assertTrue(
                gateways[0].recordedPolicies.isNotEmpty(),
                "applyConfig 仍须把策略送到网关（连接建立期 + HOT 期各一次）",
            )
            assertTrue(
                gateways[0].recordedPolicies.all { !it.requireMention },
                "SDK 侧恒全放行：requireMention 必须恒为 false，实际=${gateways[0].recordedPolicies.map { it.requireMention }}",
            )
        }
    }

    @Test
    fun `applyConfig 含 RESTART 字段 ⇒ RestartRequired（列出字段），门面不自动重启`() {
        val root = tempRoot("restart")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val connectBefore = gateways[0].connectCount
            val next = bridge.config.copy(workspace = WorkspaceConfig(root = "/data/other-ws"))
            val result = runBlocking { bridge.applyConfig(next) }
            assertTrue(result is ConfigApplyResult.RestartRequired, "workspace.root 是 RESTART 字段，实际=$result")
            assertTrue((result as ConfigApplyResult.RestartRequired).restartFields.contains("workspace.root"))
            assertEquals(connectBefore, gateways[0].connectCount, "门面不自动重启 ⇒ 连接数不变")
        }
    }

    @Test
    fun `applyConfig 无实质变更 ⇒ Unchanged`() {
        val root = tempRoot("unchanged")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            assertEquals(ConfigApplyResult.Unchanged, runBlocking { bridge.applyConfig(bridge.config) })
        }
    }
}

/**
 * 🔴 stop 十步优雅关闭的**可观测断言清单**（方案 line 239 十步）。
 * 门面级只能观测到部分步骤的直接产物，逐步对应如下：
 * - ① 关入站队列 + 停 intake ⇒ **stop 后再灌入站不再产生任何回复**；
 * - ⑤ gateway.disconnect ⇒ `disconnectCount == 1`；
 * - ⑥ gateway.close ⇒ `closeCount == 1`；
 * - ⑦ audit/stats flush+close ⇒ **audit.jsonl 落盘非空**（在途审计不丢，D20 + D4）；
 * - ⑩ 释放 appId 锁 + scope.cancel ⇒ `isRunning == false` + 状态 Idle + **新实例可重新取得同 appId 锁**。
 * 步骤 ②③④⑧⑨ 的产物（在途任务排空 / killAll / 卡片流终态 / 配置落盘 / 删脚本）由**第二条用例**
 * [TeardownCompletenessTest.`🔴 stop 十步补强`] 各钉一条可单独失败的断言（task 18 项 3 · G-04）：
 * ⑧⑨ 借「热应用待写配置 + 确认脚本」做到**非空**观测（flushNow 落盘、deleteScript 删脚本）；
 * ②③④ 借门面 seam（supervisorChildCount / liveProcessCount）+ 网关 recordedStreams 钉零残留
 * （卡片流的 close 幂等/终态补发的非空语义另由 SyntheticGatewayTest / ReplySink 单元覆盖）。
 */
class TeardownCompletenessTest {

    @Test
    fun `🔴 stop 十步：入站停收 + disconnect 与 close + 审计落盘 + 锁释放（新实例可复得）`() {
        val root = tempRoot("teardown")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val config = lifecycleConfig()
        assemble(root, config, gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val gw = gateways[0]

            // 先跑通一条 ping，让 ⑤ Observe 产出一条审计记录（落盘协程消费）
            gw.emitInbound(msg(messageId = "om_td_ping", text = "ping"))
            assertTrue(awaitUntil { gw.outboundTexts.any { it.contains("pong") } }, "前置：ping 应被回复")

            runBlocking { bridge.stop(graceMillis = 5_000L) }

            // ⑤⑥ 网关收尾
            assertEquals(1, gw.disconnectCount, "⑤ disconnect 恰一次")
            assertEquals(1, gw.closeCount, "⑥ close 恰一次")
            // ⑩ 状态归位
            assertFalse(bridge.isRunning)
            assertTrue(bridge.status.value.connection is BridgeConnectionStatus.Idle)

            // ① 入站停收：stop 后再灌入站不再产生任何新回复
            val repliesAfterStop = gw.outboundTexts.size
            gw.emitInbound(msg(messageId = "om_td_after", text = "ping"))
            Thread.sleep(300L)
            assertEquals(repliesAfterStop, gw.outboundTexts.size, "① 队列已关 + intake 已停 ⇒ 不得再回复")

            // ⑦ 审计落盘（在途审计不丢）：audit.jsonl 存在且非空
            val auditFile = ConfigPaths(root).auditFile.toFile()
            assertTrue(auditFile.exists(), "⑦ 审计文件应已落盘：${auditFile.absolutePath}")
            assertTrue(auditFile.readLines().any { it.isNotBlank() }, "⑦ 审计文件应含 ping 的记录")
        }

        // ⑩ 锁释放：同 dataRoot + appId 的新实例可重新取得锁并 start
        val gateways2 = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, config, gateways2).use { bridge2 ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge2.start() }, "⑩ stop 已释放 appId 锁 ⇒ 新实例可启动")
        }
    }

    @Test
    fun `🔴 stop 十步补强 ②③④⑧：任务树收敛0 + live进程0 + 无开放卡片流 + ⑧flushNow落盘待写配置`() {
        val root = tempRoot("teardown2")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val confirmConfig = lifecycleConfig(appId = "cli_td9_app").copy(
            workspace = WorkspaceConfig(root = root.toString()),
        )
        val configFile = ConfigPaths(root).configFile.toFile()

        assemble(root, confirmConfig, gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val gw = gateways[0]

            // 确认链路随机密根 MCP 服务退役：CLI 形态恒 Disabled（无脚本生成面）。
            assertTrue(
                bridge.confirmStatus is ConfirmRuntimeStatus.Disabled,
                "CLI 形态确认链路恒 Disabled，实际=${bridge.confirmStatus}",
            )

            // ⑧ 前置：热应用一处 HOT 字段（persist=true ⇒ 提交待落盘 draft，400ms debounce 尚未触发）。
            //    P3 agent-first：groupRequireMention 已删——HOT 载体改 agent.groupMode（同批唯一真源）。
            val next = bridge.config.copy(agent = bridge.config.agent.copy(groupMode = "all"))
            assertEquals(ConfigApplyResult.HotApplied, runBlocking { bridge.applyConfig(next, persist = true) })
            assertTrue(bridge.config.agent.groupMode == "all", "内存快照应即时生效（applyConfig 先换 configRef）")

            // 立即 stop（抢在 400ms debounce 之前）⇒ 第⑧步 flushNow 必须把待写 draft 落盘。
            runBlocking { bridge.stop(graceMillis = 5_000L) }

            // ② 在途任务排空 + ⑩ 运行时树取消：supervisor 子 Job 收敛 0（异步移除，awaitUntil 收敛）。
            assertTrue(
                awaitUntil { bridge.supervisorChildCount() == 0 },
                "② 在途任务树必须排空/取消 ⇒ supervisor 子 Job 收敛 0，实际=${bridge.supervisorChildCount()}",
            )
            // ③ killAll：无子进程残留（seam 真实；门面级不 spawn 真进程 ⇒ 恒 0，killAll/登记表泄漏会 >0）。
            assertEquals(0, bridge.liveProcessCount(), "③ 第③步 killAll 后 live 子进程必须为 0")
            // ④ 开放卡片流：无残留 isOpen 流（第④步 closeOpenStreams 收尾）。
            assertTrue(gw.recordedStreams.none { it.isOpen }, "④ 第④步 closeOpenStreams 后不得残留开放卡片流")
            // ⑧ flushNow：待写配置已落盘（debounce 未触发 ⇒ 写盘只能由第⑧步完成 ⇒ 非空断言）。
            assertTrue(configFile.exists(), "⑧ 配置文件应已由 flushNow 落盘：${configFile.absolutePath}")
            val persisted = BridgeJson.decodeFromString(BridgeConfig.serializer(), configFile.readText())
            assertTrue(persisted.agent.groupMode == "all", "⑧ flushNow 必须把待写 draft（groupMode=all）落盘")
        }
    }
}

/**
 * 🔴 M7 G-A / G-B 的门面级契约（复用本文件的 SyntheticGateway 装配 harness，零真实网络）。
 * - **G-A**：[com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot] 的 appId / lastError / stopReason
 *   三字段由门面状态迁移真实接线（「重启次数」无自然计数点，不伪造）。
 * - **G-B**：[Bridge.sendTest]（转发 gateway.send）与 [Bridge.probeEnvironment]（复用 AgentRunner.isAvailable）。
 */
class BridgeGaGbTest {

    @Test
    fun `🔴 G-A 成功 start ⇒ 快照带 appId（来自 config），stopReason 清空为 null`() {
        val root = tempRoot("ga-ok")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(appId = "cli_ga_app"), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            assertEquals("cli_ga_app", bridge.status.value.appId, "G-A：appId 须来自 config.connection.appId")
            assertEquals(null, bridge.status.value.stopReason, "G-A：运行中 stopReason 必须为 null")
        }
    }

    @Test
    fun `🔴 G-A 缺 appId 拒绝 ⇒ stopReason 记录拒绝原因，appId 为空`() {
        val root = tempRoot("ga-noappid")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val config = testConfig(routes = listOf(SystemRoute()))
            .copy(connection = ConnectionConfig(appId = "", appSecret = ""))
        assemble(root, config, gateways).use { bridge ->
            assertTrue(runBlocking { bridge.start() } is BridgeStartResult.Refused)
            assertEquals("", bridge.status.value.appId)
            val reason = bridge.status.value.stopReason
            assertTrue(reason != null && reason.contains("appId"), "G-A：拒绝原因须落 stopReason，实际=$reason")
        }
    }

    @Test
    fun `🔴 G-A 连接失败 ⇒ lastError 记录长连接失败，stopReason 记录被拒`() {
        val root = tempRoot("ga-connfail")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val bridge = assemble(
            root,
            lifecycleConfig(),
            gateways,
            GatewayConnectResult.Failed(GatewayErrorCode.ConnectFatal, larkMsg = "凭据无效"),
        )
        bridge.use {
            assertTrue(runBlocking { it.start() } is BridgeStartResult.Refused)
            val lastError = it.status.value.lastError
            assertTrue(lastError != null && lastError.contains("长连接失败"), "G-A：lastError 须记录连接失败，实际=$lastError")
            assertTrue(it.status.value.stopReason != null, "G-A：拒绝须落 stopReason")
        }
    }

    @Test
    fun `🔴 G-A 优雅 stop ⇒ stopReason 记录「已优雅停止」`() {
        val root = tempRoot("ga-stop")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            assertEquals(null, bridge.status.value.stopReason, "运行中 stopReason 为 null")
            runBlocking { bridge.stop() }
            assertEquals("已优雅停止", bridge.status.value.stopReason, "G-A：stop 后须记录停止原因")
        }
    }

    @Test
    fun `🔴 G-B sendTest 未运行 ⇒ null（无 gateway 可用）`() {
        val root = tempRoot("gb-notrunning")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            assertEquals(null, runBlocking { bridge.sendTest("oc_x", "hi") }, "G-B：未运行时无 gateway ⇒ null")
        }
    }

    @Test
    fun `🔴 G-B sendTest 运行中（SyntheticGateway）⇒ ok=true 回显 messageId，正文经网关出站，零真实网络`() {
        val root = tempRoot("gb-send")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val result = runBlocking { bridge.sendTest("oc_test_chat", "桥接测试") }
            assertTrue(result != null && result.ok, "G-B：dryRun→SyntheticGateway 应发送成功，实际=$result")
            assertTrue(result!!.messageId != null, "G-B：成功须回显 messageId")
            assertTrue(gateways[0].outboundTexts.contains("桥接测试"), "G-B：正文须经 gateway.send 出站")
        }
    }

    @Test
    fun `🔴 G-B sendTest chatId 空 ⇒ ok=false（FormatError），不出站`() {
        val root = tempRoot("gb-blank")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val outboundBefore = gateways[0].outboundTexts.size
            val result = runBlocking { bridge.sendTest("", "x") }
            assertTrue(result != null && !result.ok, "G-B：空 chatId 须失败，实际=$result")
            assertEquals(GatewayErrorCode.FormatError, result!!.error)
            assertEquals(outboundBefore, gateways[0].outboundTexts.size, "G-B：空 chatId 不得出站")
        }
    }

    @Test
    fun `🔴 G-B probeEnvironment ⇒ 汇总 backend、available、appId、running，零网络`() {
        val root = tempRoot("gb-probe")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(appId = "cli_probe_app"), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val probe = bridge.probeEnvironment()
            assertEquals(
                com.jiaoay.lark.bridge.agent.NoAgentRunner.BACKEND_ID,
                probe.agentBackend,
                "G-B：CLI 形态装配占位执行器（backendId=none）",
            )
            assertFalse(probe.agentAvailable, "G-B：CLI 形态无应答引擎 ⇒ available=false")
            assertEquals("cli_probe_app", probe.connectionAppId, "G-B：appId 须来自配置")
            assertTrue(probe.credentialReady, "G-B：appId 非空 ⇒ 凭据就绪")
            assertTrue(probe.running, "G-B：start 后 running=true")
            assertEquals(0, gateways[0].connectCount - 1, "G-B：probeEnvironment 不得额外建连（仅 start 的 1 次）")
        }
    }

    // ==================================================================================
    // 引用流稳定件（2026-09-11 真机修复批）：fileRegistry/downloadStore 跨 start/stop 存活
    // 真机事故：用户「发文件登记 → 按黄条提示重启桥接 → 引用回复」lookup miss ⇒ prompt 落回纯文本。
    // ==================================================================================

    /** 文件消息（FILE kind + 资源；对齐 S4 真机形态 content=类 XML）。 */
    private fun fileMsg(messageId: String, chatId: String = "oc_test", fileName: String = "crash.zip") =
        msg(messageId = messageId, chatId = chatId, text = """<file key="fk_$messageId" name="$fileName"/>""", kind = InboundKind.FILE)
            .copy(
                rawContentType = "file",
                resources = listOf(InboundResource(type = "file", fileKey = "fk_$messageId", fileName = fileName, durationMs = 0L)),
            )

    /** 引用回复文件消息的文本消息。 */
    private fun quoteMsg(messageId: String, replyTo: String, chatId: String = "oc_test", text: String = "ai 分析这个") =
        msg(messageId = messageId, chatId = chatId, text = text)
            .copy(replyToMessageId = replyTo)

    @Test
    fun `🔴 引用流稳定件-stop→start 后 FileRegistry 登记仍存活（真机事故回归）`() {
        val root = tempRoot("registry-survive")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val gw = gateways[0]
            // ① 发文件 → 登记（P3 2026-09-26 起恒静默——ackTemplate 已退役，修正 6）
            gw.emitInbound(fileMsg("om_file_survive"))
            assertTrue(awaitUntil { bridge.fileRegistry.size >= 1 }, "文件应已登记")
            assertTrue(
                gw.outboundTexts.none { it.contains("已收到文件") },
                "P3 起文件登记静默（回执卡是重复噪音）：实际出站=${gw.outboundTexts}",
            )
            assertTrue(bridge.fileRegistry.size >= 1, "登记表应有 1 条")

            // ② stop → start（用户「按黄条提示重启桥接」的动作）
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
            assertFalse(bridge.isRunning)
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })

            // ③ stop→start 后登记仍在（🔴 稳定件语义；旧实现此处 size=0）
            val record = bridge.fileRegistry.lookup("om_file_survive", "oc_test", Long.MAX_VALUE)
            assertTrue(record != null, "🔴 stop→start 后登记表必须仍命中（真机事故根因：旧实现重建空表）")
            assertEquals("fk_om_file_survive", record!!.resources.single().fileKey)
        }
    }

    @Test
    fun `🔴 引用流稳定件-两次 start 间 registry 同一实例（装配期单例语义钉死）`() {
        val root = tempRoot("registry-identity")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            val before = bridge.fileRegistry
            runBlocking { bridge.start() }
            val inCycle = bridge.fileRegistry
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
            runBlocking { bridge.start() }
            assertTrue(before === inCycle, "Bridge 字段即稳定件（同引用）")
            assertTrue(inCycle === bridge.fileRegistry, "stop→start 后引用不变（未重建）")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

}

/**
 * 🔴 IA 重设计批 ② G-C：[Bridge.probeNativeGrant] 门面契约（经运行中网关的只读探测窄口）。
 * - 经 bundleRef 的**运行中**网关探测（复用 harness 的 SyntheticGateway 可编程面）；
 * - 未运行时返回「桥接未运行」人话（UI 前置闸的双保险语义）；
 * - 探测只读：不触 send/connect——SyntheticGateway 记录面断言零出站零建连；
 * - CHAT_INFO/CHAT_MANAGE 同源（共享 im:chat scope）；
 * - READ_WIKI/READ_DOC 按 probeDocUrl 分流探测（目标由用户粘贴链接指定；链接空/形态不匹配回落默认失败文案）。
 */
class BridgeProbeNativeGrantTest {

    @Test
    fun `🔴 G-C 未运行时探测返回未运行人话（不触网关）`() {
        val root = tempRoot("gnc-notrun")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            // 未 start ⇒ bundleRef 空 ⇒ 门面防御文案
            val result = runBlocking {
                bridge.probeNativeGrant(NativeToolGrant.CHAT_HISTORY, "oc_t", "", "默认失败")
            }
            assertTrue(result != null && result.contains("未运行"), "未运行须返回引导人话，实际=$result")
        }
    }

    @Test
    fun `🔴 G-C CHAT_HISTORY 探测命中运行中网关（只读零出站）`() {
        val root = tempRoot("gnc-history")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            gw.listMessagesResult = ChatHistoryPage(items = emptyList(), hasMore = false, nextPageToken = null)
            val result = runBlocking {
                bridge.probeNativeGrant(NativeToolGrant.CHAT_HISTORY, "oc_t", "", "默认失败")
            }
            assertEquals(null, result, "可编程返回非 null ⇒ 探测通过")
            assertEquals(1, gw.recordedHistoryPulls.size, "探测恰好调一次 listMessages（只读动作）")
            assertEquals(0, gw.recordedSends.size, "探测绝不出站（send 零调用）")
            assertEquals(1, gw.connectCount, "探测期间零新建连接（connectCount 仍为 start 那一次）")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    @Test
    fun `🔴 G-C CHAT_INFO 与 CHAT_MANAGE 同源（一次探测两行同绿的 lib 侧语义）`() {
        val root = tempRoot("gnc-info")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            gw.getChatInfoResult = ChatInfo(chatId = "oc_t", name = "群", description = "", ownerOpenId = "", memberCount = -1, chatType = "group")
            val r1 = runBlocking { bridge.probeNativeGrant(NativeToolGrant.CHAT_INFO, "oc_t", "", "失败A") }
            val r2 = runBlocking { bridge.probeNativeGrant(NativeToolGrant.CHAT_MANAGE, "oc_t", "", "失败B") }
            assertEquals(null, r1)
            assertEquals(null, r2, "CHAT_MANAGE 共享 im:chat——CHAT_INFO 通过即同绿")
            assertEquals(2, gw.recordedChatInfoLookups.size, "两枚举各自发一次探测，同走 getChatInfo")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    @Test
    fun `🔴 G-C 能力缺席（null）回落默认失败文案；READ_WIKI 链接形态不匹配回落默认文案`() {
        val root = tempRoot("gnc-null")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            // SyntheticGateway.listMessagesResult 默认 null = scope 未开通知场景
            val miss = runBlocking { bridge.probeNativeGrant(NativeToolGrant.CHAT_HISTORY, "oc_t", "", "默认失败文案") }
            assertEquals("默认失败文案", miss, "null 能力缺席回落调用方默认文案")
            // READ_WIKI 探测链接空 / 形态不匹配（无 /wiki/ 段）⇒ 不发网络，回落默认文案
            val wikiNoUrl = runBlocking { bridge.probeNativeGrant(NativeToolGrant.READ_WIKI, "oc_t", "", "默认失败文案") }
            assertEquals("默认失败文案", wikiNoUrl, "READ_WIKI 无探测链接 ⇒ 引导文案不发网络")
            val wikiBadUrl = runBlocking {
                bridge.probeNativeGrant(NativeToolGrant.READ_WIKI, "oc_t", "", "默认失败文案", probeDocUrl = "https://x.feishu.cn/docx/tok123")
            }
            assertEquals("默认失败文案", wikiBadUrl, "READ_WIKI 链接形态不匹配（/docx/）⇒ 引导文案")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    @Test
    fun `🔴 G-C READ_WIKI 探测走 resolveWikiNode、READ_DOC 走 readDocumentText（只读零出站）`() {
        val root = tempRoot("gnc-doc")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            // READ_WIKI：resolveWikiNode 可编程返回非 null ⇒ 探测通过（验证 wiki scope + 节点可见）
            gw.wikiNodeResult = WikiNodeRef("dox_real", "docx", "周报文档")
            val wikiOk = runBlocking {
                bridge.probeNativeGrant(NativeToolGrant.READ_WIKI, "oc_t", "", "默认失败", probeDocUrl = "https://x.feishu.cn/wiki/wiktok123")
            }
            assertEquals(null, wikiOk, "resolveWikiNode 非 null ⇒ READ_WIKI 探测通过")
            assertEquals(listOf("wiktok123"), gw.recordedWikiNodeLookups, "探测恰好调一次 resolveWikiNode（token 从 /wiki/ 段提取）")
            assertEquals(0, gw.recordedDocumentTextLookups.size, "READ_WIKI 探测不读正文（wiki scope 与 docx scope 分离）")
            // READ_DOC：readDocumentText 可编程返回非空 ⇒ 探测通过
            gw.documentTextResult = "文档正文"
            val docOk = runBlocking {
                bridge.probeNativeGrant(NativeToolGrant.READ_DOC, "oc_t", "", "默认失败", probeDocUrl = "https://x.feishu.cn/docx/doxtok456")
            }
            assertEquals(null, docOk, "readDocumentText 非 null ⇒ READ_DOC 探测通过")
            assertEquals(listOf("doxtok456"), gw.recordedDocumentTextLookups, "探测恰好调一次 readDocumentText（token 从 /docx/ 段提取）")
            // 只读纪律：零出站零建连
            assertEquals(0, gw.recordedSends.size, "探测绝不出站（send 零调用）")
            assertEquals(1, gw.connectCount, "探测期间零新建连接（connectCount 仍为 start 那一次）")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }
}

/**
 * 🔴 IA 重设计批 ⑤（2026-09-27 屏⑤）：驾驶舱「待你确认」区块 + 最近任务表情回显的门面窄口契约。
 * - [Bridge.pendingConfirmations]：纯内存快照（零网络），未运行防御空表；
 * - [Bridge.listMessageReactions] / [Bridge.messageReadCount]：经**运行中**网关只读——
 *   🔴 D19 红线「零新建连接」断言照 [BridgeProbeNativeGrantTest] 组配方（connectCount 恒 start 那一次）；
 * - messageReadCount 的有界翻页聚合（readReceiptsResult 注入两页 hasMore 页）。
 */
class BridgePendingConfirmationsTest {

    @Test
    fun `🔴 批⑤ 未运行时 pendingConfirmations 防御空表（绝不抛）`() {
        val root = tempRoot("pc-notrun")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            assertTrue(bridge.pendingConfirmations().isEmpty(), "未运行 ⇒ 空表（区块整块缺席形态）")
            val reactions = runBlocking { bridge.listMessageReactions("om_x") }
            assertEquals(null, reactions, "未运行 ⇒ null（诚实缺席）")
            val count = runBlocking { bridge.messageReadCount("om_x") }
            assertEquals(null, count, "未运行 ⇒ null（UI 显示「已读 —」）")
        }
    }

    @Test
    fun `🔴 批⑤ 运行中 ToolConfirmGate 发起确认后 pendingViews 可见且零建连`() {
        val root = tempRoot("pc-running")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val service = bridge.confirmServiceForTest()
            val request = service.newRequest(
                "oc_t", "ou_a", "create_group_chat", "建群「项目群」",
                riskLevel = "HIGH",
                effectiveTimeoutMillis = 120_000L,
            )
            val views = bridge.pendingConfirmations()
            assertEquals(1, views.size, "一条在途确认 ⇒ 一条视图")
            val view = views.single()
            assertEquals(request.requestId, view.requestId)
            assertEquals("oc_t", view.chatId)
            assertEquals("ou_a", view.senderOpenId)
            assertEquals("create_group_chat", view.toolName)
            assertEquals("HIGH", view.riskLevel)
            assertEquals(120_000L, view.effectiveTimeoutMillis, "生效超时记账值透传")
            assertEquals(null, view.cardMessageId, "未发卡 ⇒ 卡锚点 null")
            assertEquals(1, gateways.single().connectCount, "🔴 D19：pendingConfirmations 绝不新建连接")
            service.cancelAll()
        }
    }

    @Test
    fun `🔴 批⑤ listMessageReactions 走运行中网关（注入断言 + 零出站零建连）`() {
        val root = tempRoot("pc-reactions")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            gw.listReactionsResult = listOf(
                com.jiaoay.lark.gateway.ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_bot"),
                com.jiaoay.lark.gateway.ReactionBrief(emojiKey = "THUMBSUP", operatorOpenId = "ou_user"),
            )
            val reactions = runBlocking { bridge.listMessageReactions("om_user_msg") }
            assertEquals(2, reactions?.size, "可编程返回非 null ⇒ 原样透传（过滤在 UI 层做）")
            assertEquals(0, gw.recordedSends.size, "只读查询绝不出站")
            assertEquals(1, gw.connectCount, "🔴 D19：零新建连接（connectCount 仍为 start 那一次）")
            // 能力缺席（null）⇒ 透传 null（诚实缺席）
            gw.listReactionsResult = null
            assertEquals(null, runBlocking { bridge.listMessageReactions("om_x") }, "能力缺席 ⇒ null")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    @Test
    fun `🔴 批⑤ messageReadCount 单页终止聚合 + 恒 hasMore 触发翻页上界（零 gateway 改动变通）`() {
        val root = tempRoot("pc-readcount")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            // 路径① 单页终止：hasMore=false ⇒ 聚合 = 该页人数（readOpenIds.size 累计语义）
            gw.readReceiptsResult = com.jiaoay.lark.gateway.ReadReceiptPage(
                readOpenIds = List(37) { "ou_$it" },
                hasMore = false,
                nextPageToken = null,
            )
            val single = runBlocking { bridge.messageReadCount("om_confirm_card") }
            assertEquals(37, single, "单页 hasMore=false ⇒ 聚合 = 该页人数")
            // 路径② 恒 hasMore（可编程面单值 var 只能注入恒同页）⇒ 5 页上界封顶：37 × 5 = 185
            // 🔴 零 gateway 改动变通（红线 7）：SyntheticGateway.readReceiptsResult 是单值 var，
            //    无法逐页编程——「两页不同页终止」路径由聚合循环的 do-while 结构与路径①/②组合覆盖，
            //    与「两页注入」的语义差异在此如实注明（恒同页 + 上界封顶 = 分页失控防御的直接证据）。
            gw.readReceiptsResult = com.jiaoay.lark.gateway.ReadReceiptPage(
                readOpenIds = List(37) { "ou_$it" },
                hasMore = true,
                nextPageToken = "token-next",
            )
            val capped = runBlocking { bridge.messageReadCount("om_confirm_card") }
            assertEquals(
                37 * Bridge.READ_RECEIPT_MAX_PAGES,
                capped,
                "恒 hasMore ⇒ 5 页上界封顶（37×5=185，防病态服务器分页失控）",
            )
            assertEquals(1, gw.connectCount, "🔴 D19：零新建连接")
            // 能力缺席（null）⇒ null（UI 显示「已读 —」）
            gw.readReceiptsResult = null
            assertEquals(null, runBlocking { bridge.messageReadCount("om_x") }, "能力缺席 ⇒ null")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    /**
     * 🔴 LB-03 复核修复（重设计批 1）：门面查询口不吞 CancellationException。
     * 生产路径根因：UI 侧 `LaunchedEffect(pendingViews)` 随 pending 集结构变化（新确认请求/他卡回填/裁决）
     * 重启并取消旧协程，在途 `messageReadCount` 的翻页/`listMessageReactions` 的查询收到 CE——旧
     * `runCatching{}.getOrNull()` 把 CE 折叠成 null ⇒ 面板退避计数虚假 +1（READ_QUERY_MAX_FAILURES=3
     * 下两次重启竞态即让健康卡永久「已读 —」）。CE 必须穿透门面原样重抛（门面头纪律「不吞
     * CancellationException」本处曾失守）；普通 Throwable 仍折叠 null（退避烧额度语义保留）。
     *
     * 网关替身在挂起点直接抛 CE——与 withContext 取消机制在挂起边界的实际表现形态同构
     * （探针实证：取消的 CE 从 suspend 调用点抛出，落在门面的 runCatching 内被吞）。
     */
    @Test
    fun `🔴 LB-03 复核 门面查询口在途取消 CE 原样重抛不折叠 null`() {
        val root = tempRoot("pc-ce-rethrow")
        val throwing = ThrowingReadQueryGateway(SyntheticGateway())
        BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = { throwing },
            defaultConfig = lifecycleConfig(),
            consoleLog = false,
        ).use { bridge ->
            runBlocking { bridge.start() }

            // messageReadCount：CE ⇒ 必须穿透门面重抛（旧形态折叠 null = 面板虚假烧退避额度）
            var seen: Throwable? = null
            runBlocking {
                try {
                    bridge.messageReadCount("om_confirm_card")
                } catch (t: Throwable) {
                    seen = t
                }
            }
            assertIs<CancellationException>(seen, "🔴 messageReadCount 必须重抛 CE——旧 runCatching 把取消折叠成 null")

            // listMessageReactions 同款纪律（兄弟查询口，同批次引入同款失守）
            var seenReactions: Throwable? = null
            runBlocking {
                try {
                    bridge.listMessageReactions("om_user_msg")
                } catch (t: Throwable) {
                    seenReactions = t
                }
            }
            assertIs<CancellationException>(seenReactions, "🔴 listMessageReactions 同款必须重抛 CE")

            // 对照组：普通 Throwable 仍折叠 null（LB-03 退避「烧额度」语义在门面层保留不变）
            throwing.mode = ThrowingReadQueryGateway.Mode.ORDINARY
            assertEquals(null, runBlocking { bridge.messageReadCount("om_x") }, "普通异常 ⇒ null（诚实缺席）")
            assertEquals(null, runBlocking { bridge.listMessageReactions("om_x") }, "普通异常 ⇒ null（同上）")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    /**
     * 🔴 LB-06（重设计批 5）：装配链级死线守护——超时回调经唯一装配点接线后，把原确认卡 patch 为
     * 已超时态打到**运行中**网关（SyntheticGateway.recordedCardUpdates 可观测）。与 service 层单测
     * （ConfirmServiceTest）互补：本用例走真实 assemble→start→createBundle 全链，装配链透传断裂
     * （稳定槽没接线/没透传——fallbackHandlerProvider 事故同型）在此红，service 层单测对此不可见。
     * 卡面 acceptance：超时后原卡在飞书侧被 patch 为「已超时拒绝」无按钮态。
     */
    @Test
    fun `🔴 LB-06 超时回调经装配链把原确认卡 patch 为已超时态`() {
        val root = tempRoot("lb06-timedout")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            val service = bridge.confirmServiceForTest()
            val request = service.newRequest(
                "oc_lb06", "ou_a", "Bash", "rm -rf build",
                riskLevel = "HIGH",
                effectiveTimeoutMillis = 300_000L,
            )
            service.recordCardMessageId(request.requestId, "om_confirm_card_lb06")
            val decision = runBlocking { service.awaitDecision(request, 100) }
            assertEquals(com.jiaoay.lark.bridge.confirm.ConfirmService.Decision.TIMEOUT, decision)
            // 轮询收敛：回调闭包在 awaitDecision 协程内跑，updateExistingCard 走 netDispatcher（异步可见）。
            assertTrue(
                awaitUntil { gw.recordedCardUpdates.isNotEmpty() },
                "🔴 超时回调必须经装配链把已超时态卡更新打到运行中网关（装配死线 = 槽未接线/未透传）",
            )
            val update = gw.recordedCardUpdates.single()
            assertEquals("om_confirm_card_lb06", update.messageId, "更新目标必须是原确认卡")
            assertEquals("⏱ 已超时拒绝", update.spec.title)
            assertEquals(0, update.spec.buttons.size, "已超时态必须无按钮（消灭滞留按钮可点）")
            assertTrue(update.spec.markdown.contains("**已超时拒绝**"), "正文标注超时结果：${update.spec.markdown}")
            assertTrue(update.spec.markdown.contains("`Bash`"), "正文保留原工具名（原问题上下文）")
            assertTrue(
                update.spec.markdown.contains(
                    "单据：cf-${com.jiaoay.lark.bridge.confirm.ConfirmService.shortId(request.requestId)}",
                ),
                "正文末行单据短标识（事后对账）：${update.spec.markdown}",
            )
            assertFalse(update.spec.streaming, "非流式（200810：已超时卡不进流式通道）")
            assertEquals(1, gw.connectCount, "🔴 回调走运行中网关（updateCard 不建连——connectCount 仍为 start 那一次）")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    /** 🔴 LB-06：更新失败不阻断主流程（装配闭包 runCatching 只 log 降级）+ 未回填卡零更新（行为同旧版）。 */
    @Test
    fun `🔴 LB-06 卡更新失败不阻断超时返回且未回填卡零更新`() {
        val root = tempRoot("lb06-fail")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            // 网关侧模拟「卡片更新失败」（SyntheticGateway.updateCardResult 可编程——已答态降级路径先例同款）
            gw.updateCardResult = SendResult(ok = false, messageId = null, error = GatewayErrorCode.SendFailed)
            val service = bridge.confirmServiceForTest()
            val r1 = service.newRequest("oc_lb06", "ou_a", "Bash", "x", effectiveTimeoutMillis = 300_000L)
            service.recordCardMessageId(r1.requestId, "om_card_fail")
            // ① 更新失败路径：TIMEOUT 照常返回（增强面降级 = 回到旧版「超时静默」），且更新尝试确已发生
            val d1 = runBlocking { service.awaitDecision(r1, 100) }
            assertEquals(com.jiaoay.lark.bridge.confirm.ConfirmService.Decision.TIMEOUT, d1, "🔴 更新失败不得阻断 TIMEOUT 语义")
            assertTrue(awaitUntil { gw.recordedCardUpdates.isNotEmpty() }, "更新尝试确已发生（失败被降级 log）")
            // ② 未回填卡路径：cardMessageId null ⇒ 跳过更新，零调用（行为同旧版）
            val r2 = service.newRequest("oc_lb06", "ou_a", "Write", "y", effectiveTimeoutMillis = 300_000L)
            val d2 = runBlocking { service.awaitDecision(r2, 100) }
            assertEquals(com.jiaoay.lark.bridge.confirm.ConfirmService.Decision.TIMEOUT, d2)
            assertEquals(1, gw.recordedCardUpdates.size, "未回填 ⇒ 不发起更新（只 ① 一条记录）")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    /**
     * 🔴 LB-20（重设计批 5）：装配链级——未读确认（readReceipts 首页 0 人）经装配接线收一次文字催办
     * （recordedSends 出站可观测）+ 窗口顺延后已超时态卡更新照发（LB-06 与 LB-20 在同一超时终点汇合）。
     * 与 service 层单测互补：本用例走真实 assemble→start 全链，readReceipts 接线（闭包内直接经
     * gateway.readReceipts 首页判零）与催办 sink.text 接线的装配死线在此红。
     */
    @Test
    fun `🔴 LB-20 未读确认经装配链收一次催办且顺延后续走`() {
        val root = tempRoot("lb20-reminder")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            // 未读：readReceipts 首页返回 0 人（hasMore=false 终止——催办判定看首页 readOpenIds.size）
            gw.readReceiptsResult = ReadReceiptPage(readOpenIds = emptyList(), hasMore = false, nextPageToken = null)
            val service = bridge.confirmServiceForTest()
            val request = service.newRequest("oc_lb20", "ou_a", "Bash", "rm -rf build", effectiveTimeoutMillis = 400L)
            service.recordCardMessageId(request.requestId, "om_confirm_card_lb20")
            val decision = runBlocking { service.awaitDecision(request, 400) }
            assertEquals(com.jiaoay.lark.bridge.confirm.ConfirmService.Decision.TIMEOUT, decision, "顺延后仍超时")
            // 催办文字已出站（GatewayReplySink.text → gateway.send TEXT）
            assertTrue(
                awaitUntil { gw.recordedSends.any { it.out.kind == com.jiaoay.lark.gateway.OutboundKind.TEXT } },
                "🔴 未读确认必须经装配链发出一次文字催办（催办接线死线）",
            )
            val reminderSends = gw.recordedSends.filter { it.out.kind == com.jiaoay.lark.gateway.OutboundKind.TEXT }
            assertEquals(1, reminderSends.size, "催办恰发一次（每请求至多 1 次——service 层 CAS + 装配层只发不重试）")
            val reminderBody = reminderSends.single().out.text
            val token = com.jiaoay.lark.bridge.confirm.ConfirmService.shortId(request.requestId)
            assertTrue(reminderBody != null && reminderBody.contains("确认 $token"), "催办文案含短码单源：$reminderBody")
            assertTrue(reminderBody != null && reminderBody.contains("还剩约"), "催办文案含剩余秒（顺延段捎带）：$reminderBody")
            // 已超时态卡更新照发（LB-06 与 LB-20 在超时终点汇合——两增强互不干扰）
            assertTrue(awaitUntil { gw.recordedCardUpdates.isNotEmpty() }, "顺延窗口到期 ⇒ 已超时态卡更新照发")
            assertEquals(1, gw.connectCount, "🔴 催办/超时均走运行中网关（不建连）")
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }

    /** 🔴 LB-20：已读未决（readReceipts 首页 ≥1 人）⇒ 装配链零催办出站（静默到期），已超时态卡更新仍照发。 */
    @Test
    fun `🔴 LB-20 已读未决经装配链零催办`() {
        val root = tempRoot("lb20-read")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assemble(root, lifecycleConfig(), gateways).use { bridge ->
            runBlocking { bridge.start() }
            val gw = gateways.single()
            gw.readReceiptsResult = ReadReceiptPage(readOpenIds = listOf("ou_reader"), hasMore = false, nextPageToken = null)
            val service = bridge.confirmServiceForTest()
            val request = service.newRequest("oc_lb20", "ou_a", "Bash", "x", effectiveTimeoutMillis = 400L)
            service.recordCardMessageId(request.requestId, "om_confirm_card_read")
            val decision = runBlocking { service.awaitDecision(request, 400) }
            assertEquals(com.jiaoay.lark.bridge.confirm.ConfirmService.Decision.TIMEOUT, decision, "已读未决静默到期")
            assertTrue(awaitUntil { gw.recordedCardUpdates.isNotEmpty() }, "已超时态卡更新照发（LB-06 增强不受催办缺席影响）")
            assertEquals(
                0,
                gw.recordedSends.count { it.out.kind == com.jiaoay.lark.gateway.OutboundKind.TEXT },
                "已读 ⇒ 零催办出站（不刷屏）",
            )
            runBlocking { bridge.stop(Bridge.GRACE_MILLIS) }
        }
    }
}

/**
 * 抛异常读查询网关（LB-03 复核修复测试专用）：readReceipts / listReactions 按可编程 [mode] 抛
 * CE / 普通 Throwable——by-delegation 同 GatedDisconnectGateway（BridgeConcurrencyContractTest 先例）。
 */
private class ThrowingReadQueryGateway(
    private val synth: SyntheticGateway,
) : MessageGateway by synth {
    enum class Mode { CANCEL, ORDINARY }

    var mode: Mode = Mode.CANCEL

    private fun throwAsProgrammed() {
        when (mode) {
            Mode.CANCEL -> throw CancellationException("read query cancelled (effect restart race)")
            Mode.ORDINARY -> throw IllegalStateException("network unreachable")
        }
    }

    override suspend fun readReceipts(messageId: String, pageSize: Int, pageToken: String?): ReadReceiptPage? {
        throwAsProgrammed()
        return synth.readReceipts(messageId, pageSize, pageToken)
    }

    override suspend fun listReactions(messageId: String): List<ReactionBrief>? {
        throwAsProgrammed()
        return synth.listReactions(messageId)
    }
}
