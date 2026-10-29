// 启动自检推送（推——桥接面）守护：start 成功路径的一次性触发 + 三闸 fail-closed 矩阵。
// 🔴 全部经 BridgeAssembler.assemble 装配（与 BridgeLifecycleTest 同型），网关用 SyntheticGateway
//    替身；探查本体在宿主侧（core StartupProber 单测另册），本册只钉门面接线与门控矩阵：
//    缝在位+开关开+接收会话已配置 ⇒ 异常文本原样外发 testChatId / 正常态静默零消息 /
//    开关关（缺省）/ 缝缺席（CLI 形态）/ testChatId 空白 ⇒ 零探查零出站。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.observe.StartupProbeSeam
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.awaitUntil
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.gateway.GatewayConnectResult
import com.jiaoay.lark.gateway.LarkTarget
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("bridge-startup-probe-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

/** 记次 fake 缝（返回固定结果；探查本体由 core 单测另册守护） */
private class RecordingSeam(private val result: String?) : StartupProbeSeam {
    val invocations = AtomicInteger(0)
    override suspend fun runStartupProbe(): String? {
        invocations.incrementAndGet()
        return result
    }
}

class BridgeStartupProbeTest {

    private val probeMessage = "【Toolbelt 启动自检】发现 1 项机器健康异常（测试模板文本）"

    private fun assembleWith(
        tag: String,
        testChatId: String = "oc_probe_target",
        seam: StartupProbeSeam?,
        gateways: CopyOnWriteArrayList<SyntheticGateway>,
    ): Bridge {
        val cfg = testConfig(routes = listOf(SystemRoute())).copy(
            connection = ConnectionConfig(
                appId = "probe_app_$tag",
                appSecret = "secret",
                region = "feishu",
                testChatId = testChatId,
            ),
        )
        return BridgeAssembler.assemble(
            dataRoot = tempRoot(tag),
            gatewayFactory = {
                SyntheticGateway().also { g -> g.connectResult = GatewayConnectResult.Connected("ou_bot", "Bot"); gateways.add(g) }
            },
            defaultConfig = cfg,
            consoleLog = false,
            startupProbe = seam,
        )
    }

    @Test
    fun `开且缝在位且接收会话已配置 ⇒ 异常文本原样外发到 testChatId 且探查恰一次`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val seam = RecordingSeam(probeMessage)
        assembleWith("push", seam = seam, gateways = gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            val gw = gateways.single()
            assertTrue(
                awaitUntil {
                    gw.recordedSends.any {
                        (it.target as? LarkTarget.Chat)?.chatId == "oc_probe_target" && it.out.text == probeMessage
                    }
                },
                "模板消息须原样外发到 testChatId，实际出站=${gw.outboundTexts}",
            )
            assertEquals(1, seam.invocations.get(), "启动触发一次性（非定时任务——不重复探查）")
        }
    }

    @Test
    fun `正常态缝返 null ⇒ 探查一次但零出站消息（静默）`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val seam = RecordingSeam(null)
        assembleWith("silent", seam = seam, gateways = gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            assertTrue(awaitUntil { seam.invocations.get() == 1 }, "探查应被触发一次")
            assertFalse(
                awaitUntil(timeoutMillis = 800) { gateways.single().outboundTexts.isNotEmpty() },
                "正常态必须静默零消息，实际出站=${gateways.single().outboundTexts}",
            )
        }
    }

    @Test
    fun `缝缺席（CLI 测试形态）⇒ 零探查零出站（fail-closed）`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        assembleWith("noseam", seam = null, gateways = gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            assertTrue(gateways.single().outboundTexts.isEmpty(), "缝缺席恒不探查不发")
        }
    }

    @Test
    fun `接收会话未配置 ⇒ 零探查零出站（当次跳过记日志）`() {
        val gateways = CopyOnWriteArrayList<SyntheticGateway>()
        val seam = RecordingSeam(probeMessage)
        assembleWith("nochat", testChatId = "", seam = seam, gateways = gateways).use { bridge ->
            assertEquals(BridgeStartResult.Started, runBlocking { bridge.start() })
            assertEquals(0, seam.invocations.get(), "无目的地不跑探查")
            assertTrue(gateways.single().outboundTexts.isEmpty(), "零出站")
        }
    }
}
