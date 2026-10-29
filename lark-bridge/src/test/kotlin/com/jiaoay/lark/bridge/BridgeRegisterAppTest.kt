package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.gateway.GatewayErrorCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * [Bridge.registerApp] 门面透传契约（阶段四产品转正，2026-09-09）。
 *
 * 钉死三条：
 * 1. **未装配也可用**：不经 start、不经 bundleRef——一次性 gatewayFactory 构造网关、用完 close；
 * 2. **成功**：SyntheticGateway 默认回合成凭据 ⇒ [RegisterAppResult.Success] 且凭据可达；
 * 3. **三种失败归一**：QrExpired / InvalidArgument / Failed——🔴 绝不抛 GatewayException 给调用方
 *    （与 BridgeStartResult「绝不抛」同纪律）。
 */
class BridgeRegisterAppTest {

    private val dataRoot = Files.createTempDirectory("bridge-register-test").also { it.toFile().deleteOnExit() }

    private fun bridgeWith(
        registerHandler: (suspend (onQr: (String, Int) -> Unit) -> com.jiaoay.lark.gateway.LarkCredential)? = null,
    ): Bridge {
        val config = testConfig(routes = emptyList())
            .copy(connection = ConnectionConfig(appId = "", appSecret = "", region = "feishu"))
        return BridgeAssembler.assemble(
            dataRoot = dataRoot,
            gatewayFactory = {
                SyntheticGateway().also { g -> registerHandler?.let { g.registerHandler = it } }
            },
            defaultConfig = config,
            consoleLog = false,
        )
    }

    @Test
    fun `未装配未启动时 registerApp 可用且成功回凭据`() = runBlocking {
        val bridge = bridgeWith()
        var qrUrl: String? = null
        var expireIn: Int? = null

        val result = bridge.registerApp { url, expire ->
            qrUrl = url
            expireIn = expire
        }

        assertIs<RegisterAppResult.Success>(result, "默认 SyntheticGateway 须回 Success")
        assertTrue(result.credential.appId.isNotBlank(), "合成凭据 appId 非空")
        assertTrue(qrUrl == SyntheticGateway.DEFAULT_QR_URL, "QR 回调须转发到调用方")
        assertTrue(expireIn == SyntheticGateway.DEFAULT_QR_EXPIRE_SECONDS, "过期秒数须转发")
        bridge.close()
    }

    @Test
    fun `QrExpired 异常归一为 QrExpired 结果不抛`() = runBlocking {
        val bridge = bridgeWith(
            SyntheticGateway.failingRegisterHandler(GatewayErrorCode.RegisterQrExpired, "二维码已过期"),
        )
        val result = bridge.registerApp { _, _ -> }
        assertIs<RegisterAppResult.QrExpired>(result)
        assertTrue(result.message.contains("二维码已过期"), "归一须保留原因文案")
        bridge.close()
    }

    @Test
    fun `InvalidArgument 异常归一为 InvalidArgument 结果`() = runBlocking {
        val bridge = bridgeWith(
            SyntheticGateway.failingRegisterHandler(GatewayErrorCode.RegisterInvalidArgument, "参数不合法"),
        )
        val result = bridge.registerApp { _, _ -> }
        assertIs<RegisterAppResult.InvalidArgument>(result)
        bridge.close()
    }

    @Test
    fun `其它异常归一为 Failed 结果`() = runBlocking {
        val bridge = bridgeWith(
            SyntheticGateway.failingRegisterHandler(GatewayErrorCode.RegisterFailed, "网络异常"),
        )
        val result = bridge.registerApp { _, _ -> }
        assertIs<RegisterAppResult.Failed>(result)
        assertTrue(result.message.isNotBlank())
        bridge.close()
    }
}
