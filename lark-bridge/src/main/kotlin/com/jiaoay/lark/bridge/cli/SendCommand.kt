// send 子命令：向指定 chat_id 发一条测试消息。
// 无凭据时优雅失败并给出错误码：无 appId 且非 dry-run ⇒ 直接 Refused 文案 + 非 0 退出，
//    绝不建连、绝不触网（结构性保证）。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.jiaoay.lark.bridge.BridgeGateways
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.gateway.GatewayConnectResult
import com.jiaoay.lark.gateway.LarkCredential
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.Outbound
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * `send`：发送一条测试消息到指定 chat_id。
 *
 * @param gatewayFactoryOverride 测试注入的网关工厂（null ⇒ 按 --dry-run/config.dryRun 选 SyntheticGateway 或真实 SDK 网关）。
 */
class SendCommand(
    out: PrintStream = System.out,
    exit: (Int) -> Unit = { exitProcess(it) },
    private val gatewayFactoryOverride: (() -> MessageGateway)? = null,
) : BridgeConfigCommand("send", out, exit) {

    private val chatId: String by option("--chat-id", help = "目标聊天 chat_id").required()
    private val text: String by option("--text", help = "消息文本").required()

    override fun help(context: Context) = "发送一条测试消息到指定 chat_id（无凭据时优雅失败并给出错误码）"

    override fun run() {
        val resolution = resolveConfig()
        if (resolution is ConfigResolution.Err) {
            emit("配置错误 [${resolution.code}]：${resolution.message}")
            exit(exitCodeFor(resolution.code))
            return
        }
        val config = (resolution as ConfigResolution.Ok).config
        val useSynthetic = dryRun || config.connection.dryRun || gatewayFactoryOverride != null

        // 无 appId 且非 dry-run ⇒ 优雅失败（不建连、不触网），给出明确错误码。
        if (!useSynthetic && config.connection.appId.isBlank()) {
            emit("发送被拒 [${BridgeErrorCode.ConfigInvalid}]：connection.appId 为空（请在配置文档填写凭据，或用 --dry-run 离线演练）")
            exit(exitCodeFor(BridgeErrorCode.ConfigInvalid))
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // G-F：经唯一工厂 BridgeGateways.resolve 取网关（CLI 与 App 共用，不再各自拼 SdkLarkGateway）。
        val gateway: MessageGateway = gatewayFactoryOverride?.invoke()
            ?: BridgeGateways.resolve(config, scope, dryRunOverride = dryRun)
        try {
            runBlocking {
                val cred = LarkCredential(
                    appId = config.connection.appId.ifBlank { SyntheticGateway.DEFAULT_APP_ID },
                    appSecret = config.connection.appSecret,
                    region = config.connection.region,
                )
                // 策略经单一构造点（BridgeGateways.policy）：requireMention 恒 false，SDK 侧全放行。
                val policy = BridgeGateways.policy()
                when (val connect = gateway.connect(cred, policy)) {
                    is GatewayConnectResult.Connected -> Unit
                    GatewayConnectResult.BlockedByPeerInstance -> {
                        emit("连接被拒 [${BridgeErrorCode.BlockedByPeerInstance}]：同一 appId 已有另一条长连接在线")
                        exit(exitCodeFor(BridgeErrorCode.BlockedByPeerInstance))
                        return@runBlocking
                    }
                    is GatewayConnectResult.Failed -> {
                        emit("连接失败 [${BridgeErrorCode.ConnectFailed}]：${connect.larkMsg ?: connect.error.name}")
                        exit(exitCodeFor(BridgeErrorCode.ConnectFailed))
                        return@runBlocking
                    }
                }
                val result = gateway.send(LarkTarget.Chat(chatId), Outbound.text(text))
                if (result.ok) {
                    emit("发送成功 → chat_id=$chatId messageId=${result.messageId}")
                } else {
                    emit("发送失败 [${BridgeErrorCode.SendFailed}]：${result.larkMsg ?: result.error?.name ?: "未知"}")
                    exit(exitCodeFor(BridgeErrorCode.SendFailed))
                }
                gateway.disconnect()
            }
        } finally {
            gateway.close()
            scope.cancel()
        }
    }
}
