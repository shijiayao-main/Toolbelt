// M4 收尾（task 18 项 2 · G-02）：桥接门面反复 start/stop 的**资源零泄漏**回归。
// 🔴 场景：宿主（App/CLI）在进程生命周期内可能多次启停桥接（切账号、改 RESTART 字段、崩溃重连）。
//    若任一周期的收尾漏了子进程 / 卡片流 / 协程作用域树 / 网关 / 单实例锁，20 次循环后会线性堆积成资源泄漏。
//    本测试用 `repeat(20) { start(); stop() }` 把「单次看不出的泄漏」放大 20 倍后断言收敛。
//
// 🔴 四个泄漏维度（对齐 G-02 清单）：
//   ① 线程数（threadCount）——**本轮不实现**：JVM 线程数受测试框架/协程共享池/GC 线程噪声干扰，进程级 threadCount
//      断言在单测里天然 flaky（无法把「桥接泄漏的线程」从共享 Dispatchers.Default 池线程里区分出来）。改由
//      ④ supervisor 子 Job 收敛 0 间接钉死「桥接不泄漏协程作用域树」——协程不漏则其挂载的线程资源不漏。
//      真正的 SDK 线程/连接池释放由 stop 第⑥步 gateway.close() 承担，已在下方「每网关 close 恰一次」断言覆盖。
//   ② 在途子进程（[Bridge.liveProcessCount]）——stop 第③步 killAll 后必须为 0；
//   ③ 开放卡片流（[SyntheticGateway.recordedStreams]）——stop 第④步 closeOpenStreams 后不得残留 isOpen 的流；
//   ④ supervisor 作用域树子 Job（[Bridge.supervisorChildCount]）——stop 第⑩步 runtime.cancel 后必须收敛为 0。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.awaitUntil
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.gateway.GatewayConnectResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("bridge-leak-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

private fun leakConfig(appId: String = "cli_leak_app"): BridgeConfig =
    testConfig(routes = listOf(SystemRoute(), AgentRoute()))
        .copy(connection = ConnectionConfig(appId = appId, appSecret = "secret", region = "feishu"))

class BridgeStartStopLeakTest {

    @Test
    fun `项2 G-02 repeat(20) start-stop ⇒ 零泄漏：live进程0 + 无开放卡片流 + supervisor子Job收敛0 + 每周期网关各建连-收尾一次 + 锁不泄漏`() {
        val root = tempRoot("leak")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val bridge = BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = {
                SyntheticGateway().also { g ->
                    g.connectResult = GatewayConnectResult.Connected("ou_bot", "Bot")
                    gateways.add(g)
                }
            },
            defaultConfig = leakConfig(),
            consoleLog = false,
        )
        try {
            repeat(20) { i ->
                // 🔴 若单实例锁在上一周期泄漏（stop 第⑩步未 release），第 2 次起会被 BlockedByPeerInstance 拒 ⇒
                //    「20 次全部 Started」本身就是锁零泄漏的强断言。
                val started = runBlocking { bridge.start() }
                assertEquals(
                    BridgeStartResult.Started,
                    started,
                    "第 $i 次 start 必须成功（若锁泄漏，第 2 次起会被拒 BlockedByPeerInstance），实际=$started",
                )
                // 每周期灌一条 ping，让 intake→route→reply→gateway 真正跑一轮（产生入站/出站/审计活动）。
                val gw = gateways.last()
                gw.emitInbound(msg(messageId = "om_leak_$i", text = "ping"))
                assertTrue(
                    awaitUntil { gw.outboundTexts.any { it.contains("pong") } },
                    "第 $i 次 ping 应经管线回复 pong，实际出站=${gw.outboundTexts}",
                )
                runBlocking { bridge.stop() }
            }

            // ② 在途子进程：killAll 后无残留（门面级不 spawn 真进程 ⇒ 恒 0；seam 真实，若 killAll/登记表泄漏会 >0）。
            assertEquals(0, bridge.liveProcessCount(), "② liveProcesses 必须为 0（无子进程泄漏）")

            // ③ 开放卡片流：20 周期产出的所有替身流均不得仍开放（stop 第④步 closeOpenStreams 必须收尾）。
            //    🔴 ping/pong 走 SYSTEM 路由不产流式卡 ⇒ 本清单通常为空，此断言是「若有流则必被关」的结构性护栏；
            //    卡片流 close 幂等与终态补发的**非空**覆盖在 SyntheticGatewayTest / ReplySink 单元层，此处只钉零残留。
            val allStreams = gateways.flatMap { it.recordedStreams }
            assertTrue(
                allStreams.none { it.isOpen },
                "③ 不得残留任何开放卡片流（stop 第④步必须收尾），残留=${allStreams.count { it.isOpen }}",
            )

            // ④ supervisor 作用域树：runtime.cancel 后子 Job 异步移除，awaitUntil 收敛为 0。
            //    🔴 若每周期泄漏一棵运行时树，20 次后此处会是 20 ⇒ 这是最强的协程作用域泄漏探针。
            assertTrue(
                awaitUntil { bridge.supervisorChildCount() == 0 },
                "④ supervisor 子 Job 必须收敛为 0（否则每周期泄漏一棵运行时树），实际=${bridge.supervisorChildCount()}",
            )

            // 附加护栏：每周期恰造一个网关、各建连一次、各 disconnect+close 一次（无网关泄漏，第⑤⑥步逐周期落实）。
            assertEquals(20, gateways.size, "20 次 start 各造一个网关（幂等 start 不会在同一周期内重复造）")
            assertTrue(gateways.all { it.connectCount == 1 }, "每网关恰建连一次，实际=${gateways.map { it.connectCount }}")
            assertTrue(
                gateways.all { it.disconnectCount == 1 && it.closeCount == 1 },
                "每网关恰 disconnect+close 一次（无网关泄漏）：disconnect=${gateways.map { it.disconnectCount }} close=${gateways.map { it.closeCount }}",
            )
        } finally {
            bridge.close()
        }
    }
}
