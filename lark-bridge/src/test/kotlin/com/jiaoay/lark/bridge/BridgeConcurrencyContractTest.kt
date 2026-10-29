// M4 收尾（task 18 项 1 · G-03）：Bridge 门面 suspend 重构的**行为契约**测试。
// 🔴 背景（设计裁定 2026-09-08，task 18）：start()/applyConfig()/teardownBundle 已改 suspend，全类 runBlocking
//    由 11 处收敛到 2 处（仅 close()/onAppExit() 各一层，是 AutoCloseable + JVM shutdown hook 的必需桥接）。
//    本文件用**行为**钉死这次重构的三条语义保证，任何一条回退（重新把阻塞藏进公共 API、拆掉 NonCancellable、
//    或把 close()/onAppExit() 也改 suspend）都会让对应用例红：
//    (a) teardownBundle 的 withContext(NonCancellable)：取消 stop() 的 job，十步收尾仍必须**跑完**（不漏 disconnect/close）；
//    (b) == 验收门 4c：start() 是**协作式挂起**，慢 connect 期间让出调用方线程，绝不饿死同池其它任务（防 Compose 主线程 ANR）；
//    (c) close()/onAppExit() 保持**非 suspend**：从阻塞世界同步调用即可停桥（唯一一层 runBlocking 桥接）。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.awaitUntil
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.gateway.GatewayConnectResult
import com.jiaoay.lark.gateway.LarkCredential
import com.jiaoay.lark.gateway.LarkPolicy
import com.jiaoay.lark.gateway.MessageGateway
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("bridge-conc-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

/** 带凭据的生命周期配置（appId 非空 ⇒ 通过预检；SYSTEM+AGENT 路由 ⇒ 可跑通管线）。 */
private fun concConfig(appId: String = "cli_conc_app"): BridgeConfig =
    testConfig(routes = listOf(SystemRoute(), AgentRoute()))
        .copy(connection = ConnectionConfig(appId = appId, appSecret = "secret", region = "feishu"))

/** 轮询直到条件成立（真实并发下的收敛断言，不用固定 sleep 猜时长）。 */
/**
 * 门控网关：`disconnect()`（stop 第⑤步）进门即置 [disconnectEntered]，然后**停在** [gate] 上等测试放行。
 * 用 `MessageGateway by synth` 委托复用 [SyntheticGateway] 的全部记录面，只覆写 disconnect 一个成员。
 * 🔴 用途：把「取消」精确定位在 teardown **中途**（第⑤步内），证明 withContext(NonCancellable) 真的兜住了取消。
 */
private class GatedDisconnectGateway(
    private val synth: SyntheticGateway,
    private val gate: CompletableDeferred<Unit>,
) : MessageGateway by synth {
    @Volatile
    var disconnectEntered: Boolean = false
        private set

    override suspend fun disconnect() {
        disconnectEntered = true
        // 🔴 停在 teardown 第⑤步内：若外层没有 NonCancellable，取消 stop 的 job 会在此抛 CancellationException，
        //    导致第⑥步 gateway.close 及其后所有步骤永不执行（漏线程/漏连接池）。
        gate.await()
        synth.disconnect()
    }
}

/**
 * 慢连接网关：`connect()` 人为 [connectDelayMillis] 后再委托 [SyntheticGateway]。
 * 🔴 用途（门 4c）：让 start() 的挂起点足够长，以便观测它是否**让出**调用方线程（协作式挂起 vs 阻塞）。
 */
private class SlowConnectGateway(
    private val synth: SyntheticGateway,
    private val connectDelayMillis: Long,
) : MessageGateway by synth {
    override suspend fun connect(cred: LarkCredential, policy: LarkPolicy): GatewayConnectResult {
        delay(connectDelayMillis)
        return synth.connect(cred, policy)
    }
}

class BridgeConcurrencyContractTest {

    // ------------------------------------------------------------------ (a) NonCancellable teardown

    @Test
    fun `项1(a) 取消 stop() 的 job ⇒ teardown 仍在 NonCancellable 内跑完十步（disconnect+close 各一次，绝不漏收尾）`() {
        val root = tempRoot("noncancel")
        val synth = SyntheticGateway()
        val gate = CompletableDeferred<Unit>()
        val gated = GatedDisconnectGateway(synth, gate)
        val bridge = BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = { gated },
            defaultConfig = concConfig(),
            consoleLog = false,
        )
        // 独立作用域承载 stop()，以便从外部取消它（模拟宿主销毁 / SIGTERM 打断优雅关闭）。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { bridge.start() }
            assertEquals(1, synth.connectCount, "前置：start 应已建连一次")

            val stopJob = scope.launch { bridge.stop(graceMillis = 5_000L) }
            assertTrue(awaitUntil { gated.disconnectEntered }, "前置：stop 应已推进到第⑤步 disconnect 并停在门控上")

            // 🔴 在 teardown 中途取消 stop 的 job；随后放行门控。
            stopJob.cancel()
            gate.complete(Unit)
            runBlocking { stopJob.join() }

            // NonCancellable ⇒ 取消被兜住，十步全部跑完：⑤disconnect + ⑥close 各一次。
            assertEquals(1, synth.disconnectCount, "⑤ disconnect 必须完成（NonCancellable 兜住取消）")
            assertEquals(
                1,
                synth.closeCount,
                "⑥ close 必须完成——若无 NonCancellable，取消会在第⑤步内中断，close 及其后步骤永不执行（漏连接池/线程）",
            )
            assertFalse(bridge.isRunning, "stop 入口已 CAS running=false")
        } finally {
            scope.cancel()
            bridge.close()
        }
    }

    // ------------------------------------------------------------------ (b) == 验收门 4c

    @Test
    fun `门4c start() 不阻塞调用方线程：慢 connect 挂起期间让出单线程受限池，同池任务照常推进（防 Compose 主线程 ANR）`() {
        val root = tempRoot("4c")
        val synth = SyntheticGateway()
        // connect 人为慢 600ms：若 start() 阻塞线程，这 600ms 内单线程池将被独占。
        val slow = SlowConnectGateway(synth, connectDelayMillis = 600L)
        val bridge = BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = { slow },
            defaultConfig = concConfig(),
            consoleLog = false,
        )
        // 🔴 单线程受限池：模拟「ViewModel 只有一条主线程」的最坏情形——阻塞即饿死同池其它任务。
        val single = Dispatchers.Default.limitedParallelism(1)
        try {
            val ticks = AtomicInteger(0)
            val ticksAtStartReturn = AtomicInteger(-1)

            val result = runBlocking {
                val startDeferred = async(single) {
                    val r = bridge.start()
                    // 🔴 记录 start() **返回瞬间**同池 tick 任务的进度：协作式挂起 ⇒ 此刻 tick 应已跑完；阻塞 ⇒ 应为 0。
                    ticksAtStartReturn.set(ticks.get())
                    r
                }
                val tickJob = launch(single) {
                    // 150ms 总时长 < connect 的 600ms 挂起窗口 ⇒ 只要 start() 让出线程，这些 tick 必在 start 返回前跑完。
                    repeat(15) {
                        ticks.incrementAndGet()
                        delay(10L)
                    }
                }
                val r = withTimeout(5_000L) { startDeferred.await() }
                tickJob.join()
                r
            }

            assertEquals(BridgeStartResult.Started, result, "start() 应在预算内正常返回 Started")
            assertEquals(1, synth.connectCount)
            assertEquals(
                15,
                ticksAtStartReturn.get(),
                "🔴 start() 返回瞬间同池 15 次 tick 应已全部完成——证明 connect 是协作式挂起、让出了线程；" +
                    "若 start() 阻塞（旧 runBlocking 语义），单线程池被独占，此刻 ticks 应为 0（同池任务被饿死）",
            )
        } finally {
            // 🔴 limitedParallelism(1) 是对共享 Dispatchers.Default 的**视图**（不自建线程池），无需 close，不会漏线程。
            bridge.close()
        }
    }

    // ------------------------------------------------------------------ (c) close()/onAppExit() 非 suspend 桥接

    @Test
    fun `项1(c) close() 非 suspend：从阻塞世界同步调用即停桥（AutoCloseable 契约，内部唯一一层 runBlocking 驱动 stop）`() {
        val root = tempRoot("close")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val bridge = BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = { SyntheticGateway().also { g -> g.connectResult = GatewayConnectResult.Connected("ou_bot", "Bot"); gateways.add(g) } },
            defaultConfig = concConfig(),
            consoleLog = false,
        )
        runBlocking { bridge.start() }
        assertTrue(bridge.isRunning, "前置：start 后应在运行")

        // 🔴 close() 是非 suspend（AutoCloseable / use{} 契约）：直接在阻塞世界调用，无需 runBlocking 包裹。
        bridge.close()

        assertFalse(bridge.isRunning, "close() 应同步停桥")
        assertEquals(1, gateways[0].disconnectCount, "close()→stop() 应完成第⑤步 disconnect")
        assertEquals(1, gateways[0].closeCount, "close()→stop() 应完成第⑥步 close")
    }

    @Test
    fun `项1(c) onAppExit() 非 suspend：JVM shutdown hook 契约——同步 flushNow 配置后停桥`() {
        val root = tempRoot("onappexit")
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val bridge = BridgeAssembler.assemble(
            dataRoot = root,
            gatewayFactory = { SyntheticGateway().also { g -> g.connectResult = GatewayConnectResult.Connected("ou_bot", "Bot"); gateways.add(g) } },
            defaultConfig = concConfig(),
            consoleLog = false,
        )
        runBlocking { bridge.start() }
        assertTrue(bridge.isRunning)

        // 🔴 onAppExit() 是非 suspend（JVM shutdown hook 是 Thread，无法 suspend）：阻塞世界直接调用。
        bridge.onAppExit()

        assertFalse(bridge.isRunning, "onAppExit() 应同步停桥")
        assertEquals(1, gateways[0].closeCount, "onAppExit()→close()→stop() 应释放网关")
    }
}
