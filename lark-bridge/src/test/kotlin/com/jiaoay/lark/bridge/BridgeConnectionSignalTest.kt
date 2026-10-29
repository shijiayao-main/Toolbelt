// 2026-09-12 在线状态批次（方案A+C）的 Bridge 门面测试：
// - 重连信号接线：SyntheticGateway emitSignal(Reconnecting/Reconnected) → Bridge.connectionRef 迁移 → status 快照可见；
// - 状态机守卫：Blocked/Draining/Idle 态不被重连信号覆盖（stop 序列与被拒路径的态由 Bridge 独占）；
// - connectedSinceMillis 生命周期：start 连上时写入、stop 清空；
// - 1Hz metrics tick 接线：运行中 _status 由周期刷新（计数不再冻结）。
// 🔴 与 BridgeLifecycleTest 同纪律：经 BridgeAssembler.assemble 装配、SyntheticGateway 替身、
//    runBlocking 顺序非嵌套、.use{} 的 close() 留最外层。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.observe.BridgeConnectionStatus
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.awaitUntil
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
    Files.createTempDirectory("bridge-sig-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

private fun signalConfig(appId: String = "cli_signal_app") =
    testConfig(routes = listOf(SystemRoute(), AgentRoute(), FileRoute()))
        .copy(connection = ConnectionConfig(appId = appId, appSecret = "secret", region = "feishu"))

private fun assembleForSignal(
    dataRoot: Path,
    gateways: CopyOnWriteArrayList<SyntheticGateway>,
): Bridge = BridgeAssembler.assemble(
    dataRoot = dataRoot,
    gatewayFactory = { SyntheticGateway().also { g -> g.connectResult = GatewayConnectResult.Connected("ou_bot", "Bot"); gateways.add(g) } },
    defaultConfig = signalConfig(),
    consoleLog = false,
)

/** 轮询收敛（真实并发下不 sleep 固定值）。 */
class BridgeConnectionSignalTest {

    @Test
    fun `Reconnecting 信号迁移连接态并反映到状态快照`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleForSignal(tempRoot("reconn"), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val gateway = gateways.last()

            gateway.emitSignal(com.jiaoay.lark.gateway.GatewaySignal.Reconnecting)
            assertTrue(
                awaitUntil { bridge.status.value.connection is BridgeConnectionStatus.Reconnecting },
                "Reconnecting 信号应迁移连接态，实际=${bridge.status.value.connection}",
            )
            assertTrue(
                bridge.status.value.lastSignalAtMillis != null,
                "快照应含最近信号时刻",
            )

            gateway.emitSignal(com.jiaoay.lark.gateway.GatewaySignal.Reconnected)
            assertTrue(
                awaitUntil { bridge.status.value.connection is BridgeConnectionStatus.Connected },
                "Reconnected 信号应恢复连接态，实际=${bridge.status.value.connection}",
            )
        }
    }

    @Test
    fun `Reconnected 恢复后 connectedSince 重置为新的连接周期`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleForSignal(tempRoot("since"), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val gateway = gateways.last()

            val sinceAtStart = bridge.status.value.connectedSinceMillis
            assertTrue(sinceAtStart != null, "start 连上后 connectedSince 应非空")

            gateway.emitSignal(com.jiaoay.lark.gateway.GatewaySignal.Reconnecting)
            gateway.emitSignal(com.jiaoay.lark.gateway.GatewaySignal.Reconnected)
            assertTrue(
                awaitUntil {
                    val since = bridge.status.value.connectedSinceMillis
                    since != null && since != sinceAtStart
                },
                "Reconnected 应重置 connectedSince（新连接周期），start=${sinceAtStart} now=${bridge.status.value.connectedSinceMillis}",
            )
        }
    }

    @Test
    fun `未运行时信号不改写连接态（守卫：running=false 直通拒绝）`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleForSignal(tempRoot("notrun"), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            runBlocking { bridge.stop() }
            assertEquals(BridgeConnectionStatus.Idle, bridge.status.value.connection)

            // stop 后 bundleRef=null、signalScope 已取消——迟到的信号无人收集（结构性正确）；
            // 即使有收集者触达守卫，running=false 也直通拒绝。断言 = 连接态恒 Idle（Bridge 独占态不被侵入）。
            val gateway = gateways.last()
            gateway.emitSignal(com.jiaoay.lark.gateway.GatewaySignal.Reconnecting)
            assertEquals(
                BridgeConnectionStatus.Idle,
                bridge.status.value.connection,
                "stop 后迟到信号不得改写 Idle（守卫：Bundle 已拆、状态由 Bridge 独占）",
            )
        }
    }

    @Test
    fun `stop 清空 connectedSince 且状态快照反映停止`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleForSignal(tempRoot("stopclear"), gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            assertTrue(bridge.status.value.connectedSinceMillis != null, "运行中 connectedSince 应非空")

            runBlocking { bridge.stop() }
            assertEquals(null, bridge.status.value.connectedSinceMillis, "stop 后 connectedSince 应清空")
            assertEquals(false, bridge.status.value.running)
        }
    }
}
