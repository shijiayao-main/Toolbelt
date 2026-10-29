// 评审 N-2 回归：桥接运行中关窗不得冻结 EDT/UI 线程（旧实现最坏 ~18s ⇒ 修复后 ≤ APP_EXIT_EDT_BUDGET_MILLIS）。
// 🔴 缺陷因果链（评审实读）：main.kt onDispose → LarkBridgeManager.onAppExit → Bridge.onAppExit → close()
//    → runBlocking { stop() } → teardownBundle 十步中 ②join(5s)+④closeOpenStreams(3s)+⑤disconnect(5s)+⑥close(5s)
//    四步超时预算**在 EDT 上串行叠加** ⇒ 最坏冻结约 18 秒（相对旧非阻塞 LarkBridge.stop() 的明确回归）。
// 🔴 修复：onAppExit 把整个 close()（含十步 teardown）挪到独立**非守护**后台线程，EDT 侧只 Thread.join(预算) 后返回；
//    ⑧ 配置落盘仍在 EDT 上同步先做（数据安全关键步绝不后台化）。本文件用「人为很慢的网关」钉死两条语义：
//    (1) onAppExit() 在预算内返回（EDT 不被慢网络步骤冻结）；
//    (2) 后台非守护线程仍把十步跑完 —— ③killAll / ⑤disconnect / ⑥close / ⑩锁释放+runtime.cancel 全部落实，
//        且 ⑧flushNow 的待写配置在 onAppExit 返回**当下**已落盘（EDT 同步完成，非后台补做）。
// 🔴 绝不真的睡 18s：慢网关 disconnect/close 各睡 STEP_DELAY_MILLIS(2s)，合计 ~4s，远小于旧实现的 18s，
//    也远大于 EDT 预算 1.5s ⇒ 足以证明「EDT 提前返回、后台继续收尾」。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeJson
import com.jiaoay.lark.bridge.config.ConfigPaths
import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.awaitUntil
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.gateway.GatewayConnectResult
import com.jiaoay.lark.gateway.MessageGateway
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("bridge-exit-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

private fun exitConfig(appId: String = "cli_exit_app"): BridgeConfig =
    testConfig(routes = listOf(SystemRoute(), AgentRoute()))
        .copy(connection = ConnectionConfig(appId = appId, appSecret = "secret", region = "feishu"))

/**
 * 慢收尾网关：`disconnect()`（第⑤步）挂起 [stepDelayMillis]、`close()`（第⑥步）阻塞睡 [stepDelayMillis]。
 * 用 `MessageGateway by synth` 委托复用 [SyntheticGateway] 全部记录面，只覆写这两个慢步骤。
 * 🔴 close() 非 suspend ⇒ 只能 Thread.sleep（阻塞在后台线程上，正是真实 SDK close 释放连接池/线程的耗时形态）。
 */
private class SlowTeardownGateway(
    private val synth: SyntheticGateway,
    private val stepDelayMillis: Long,
) : MessageGateway by synth {
    override suspend fun disconnect() {
        delay(stepDelayMillis)
        synth.disconnect()
    }

    override fun close() {
        Thread.sleep(stepDelayMillis)
        synth.close()
    }
}

class BridgeAppExitEdtBudgetTest {

    private companion object {
        /** 慢网关每步睡 2s（disconnect+close 合计 ~4s）：远大于 EDT 预算 1.5s，远小于旧实现最坏 18s。 */
        const val STEP_DELAY_MILLIS = 2_000L
    }

    @Test
    fun `N-2 桥接运行中 onAppExit ⇒ EDT 在预算内返回不被慢网关冻结，且后台非守护线程仍跑完十步（③⑤⑥⑩+⑧同步落盘）`() {
        val root = tempRoot("edt")
        val synth = SyntheticGateway().also { it.connectResult = GatewayConnectResult.Connected("ou_bot", "Bot") }
        val slow = SlowTeardownGateway(synth, STEP_DELAY_MILLIS)
        val bridge = BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = { slow },
            defaultConfig = exitConfig(),
            consoleLog = false,
        )
        val configFile = ConfigPaths(root).configFile.toFile()
        try {
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() }, "前置：start 应成功")
            assertEquals(1, synth.connectCount, "前置：start 应已建连一次")

            // ⑧ 前置：热应用一处 HOT 字段（persist=true ⇒ 提交待落盘 draft，400ms debounce 尚未触发）。
            //   onAppExit 首行 EDT 同步 flushNow 必须把它落盘 ⇒ 事后可证明「数据安全关键步在 EDT 上完成、未经预算截断」。
            //   P3 agent-first：groupRequireMention 已删——HOT 载体改 agent.groupMode（同批唯一真源）。
            val next = bridge.config.copy(agent = bridge.config.agent.copy(groupMode = "all"))
            assertEquals(com.jiaoay.lark.bridge.ConfigApplyResult.HotApplied, runBlocking { bridge.applyConfig(next, persist = true) })

            // 🔴 核心：测量 onAppExit 在 EDT（此处即测试线程）上的墙钟耗时。慢网关 ⑤⑥ 合计 ~4s，
            //   若像旧实现在 EDT 上串行 close()，本调用会阻塞 ~4s（真实场景最坏 ~18s）；修复后应 ≤ 预算(~1.5s)+flushNow。
            val t0 = System.nanoTime()
            bridge.onAppExit()
            val elapsedMillis = (System.nanoTime() - t0) / 1_000_000L

            assertTrue(
                elapsedMillis < 2_500L,
                "🔴 onAppExit 必须在 EDT 预算内返回（慢网关 ⑤disconnect+⑥close 各睡 ${STEP_DELAY_MILLIS}ms ⇒ 旧实现会冻结 ~${2 * STEP_DELAY_MILLIS}ms+），" +
                    "实际=${elapsedMillis}ms；预算=${Bridge.APP_EXIT_EDT_BUDGET_MILLIS}ms",
            )
            assertFalse(bridge.isRunning, "stop() 入口 CAS running=false 应已在后台线程完成（预算窗口内）")

            // ⑧ 数据安全关键步：待写配置在 onAppExit 返回**当下**即已落盘（EDT 同步 flushNow，非等后台十步补做）。
            assertTrue(configFile.exists(), "⑧ 配置文件应已由 onAppExit 首行 EDT 同步 flushNow 落盘：${configFile.absolutePath}")
            val persisted = BridgeJson.decodeFromString(BridgeConfig.serializer(), configFile.readText())
            assertTrue(
                persisted.agent.groupMode == "all",
                "⑧ EDT 同步 flushNow 必须把待写 draft（groupMode=all）落盘（数据安全步绝不经预算截断）",
            )

            // 🔴 此刻慢网关的 ⑤disconnect 仍在后台睡（尚未 close）⇒ 证明 EDT 确实提前返回、后台继续收尾。
            assertEquals(0, synth.closeCount, "EDT 返回瞬间第⑥步 close 尚未完成（慢网关仍在后台跑），证明 EDT 未被冻结等待")

            // 后台非守护线程必须把十步跑完：⑤disconnect + ⑥close 各一次、⑩ runtime.cancel（supervisor 子 Job 收敛 0）。
            assertTrue(
                awaitUntil(10_000L) { synth.disconnectCount == 1 && synth.closeCount == 1 && bridge.supervisorChildCount() == 0 },
                "🔴 后台非守护线程必须跑完十步（EDT 提前返回后仍收尾）：" +
                    "disconnect=${synth.disconnectCount} close=${synth.closeCount} supervisorChildren=${bridge.supervisorChildCount()}",
            )
            // ③ killAll：无子进程残留（seam 真实；门面级不 spawn 真进程 ⇒ 恒 0，killAll/登记表泄漏会 >0）。
            assertEquals(0, bridge.liveProcessCount(), "③ 后台第③步 killAll 后 live 子进程必须为 0")
        } finally {
            // 幂等：onAppExit 已在后台触发 close()，此处再 close() 对已停桥是 no-op（stop CAS 失败即返回）。
            runCatching { bridge.close() }
        }
    }
}
