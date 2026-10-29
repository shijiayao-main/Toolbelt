// 网关工厂唯一化：App 宿主与 CLI 共用同一个「config → MessageGateway」装配点。
// 🔴 若 feature 侧与 CLI 各自同构重建网关装配 ⇒ 两份平行实现，lib 改网关装配入参时
//    App 侧不会红构建（装配等价性破坏）。本文件为 **public** 工厂，CLI 与 feature 都调它；
//    并由 BridgeAssemblerParityTest 钉死「全仓只有一处构造 SdkLarkGateway」。
// 纪律：只构造对象、**不连接**（连接发生在 Bridge.start 内）；缺凭据时 start 在建运行时束前即 Refused，
//    故本工厂在无凭据场景根本不被调用（零真机调用的结构性保证）。SDK 适配类 SdkLarkGateway 属 `:lark-gateway`，
//    本模块引用它是允许的（被禁的是 com.lark.oapi.* 的 SDK 类型，只准出现在 :lark-gateway）。
package com.jiaoay.lark.bridge

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import com.jiaoay.lark.gateway.GatewaySdkSettings
import com.jiaoay.lark.gateway.LarkPolicy
import com.jiaoay.lark.gateway.MessageGateway
import com.jiaoay.lark.gateway.SdkLarkGateway
import com.jiaoay.lark.gateway.WsClientTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * App 与 CLI 共用的网关工厂（全仓**唯一**构造 [SdkLarkGateway] 的地方）。
 */
object BridgeGateways {

    /**
     * 🔴 **策略单一构造点**：全仓构造 [LarkPolicy] 只允许经此函数。
     *
     * 为什么必须收口：多处各自构造 `LarkPolicy` 时一处改动漏改就分叉——
     * 真实缺陷曾是「三处一致地推了错的 requireMention」。
     *
     * 🔴 **`requireMention` 恒传 `false`（SDK 侧全放行）**——这是与 SDK 的分工，不是「暂未实现」：
     * SDK 自带的 `com.lark.oapi.channel.safety.PolicyGate` 判定颗粒度是**会话级**（只看 chatType +
     * 是否 @机器人），**不区分消息类型**。真机实证：群里发的**文件消息物理上无法携带 @**，
     * 于是只要 `requireMention=true`，SDK 就把所有群文件消息拦在 `"message"` 事件之前——桥接侧
     * `PolicyGate` 的 `InboundKind.FILE` 豁免**永远执行不到**（死代码），文件从未登记、引用必然 miss。
     *
     * 因此群@门控**完全由桥接侧 `PolicyGate` 承担**（它能按消息类型豁免，判定表完整）。
     * 代价：群里未 @ 的消息也会进 intake（SDK 不再帮省流量）——但桥接侧拒绝是纯 CPU 判定 +
     * O(1) 计数、不占许可、`rejectionReply` 为空时完全静默，成本可忽略；且收回后所有拒绝都能走
     * 桥接统一路径（可回文案、可审计），比 SDK 静默丢弃更好。
     */
    fun policy(): LarkPolicy = LarkPolicy(requireMention = false)

    /**
     * 从生效配置派生真实 SDK 网关（不连接）。
     *
     * 🔴 派生规则（gateway-contract.md §15.2 M-5：引擎注入 allowedFileDirs；漏并项即引擎白名单越界或误拒）：
     * - `allowedFileDirs` = `workspace.root` ∪ `workspace.extraRoots`（去空白项）；
     * - `downloadMaxBytes` = `fileMessage.maxFileSizeBytes`；
     * - `requireMention` **恒 `false`**（见 [policy]——SDK 侧全放行，群@门控归桥接侧）。
     *
     * 入站传输恒走自研 `:lark-ws`：恒注入 [WsClientTransportFactory]——
     * SDK 侧 transport 切 "webhook"（防双长连接互踢），出站/identity 仍走 LarkChannel facade。
     */
    fun sdk(config: BridgeConfig, scope: CoroutineScope): MessageGateway {
        val allowedFileDirs = buildList {
            if (config.workspace.root.isNotBlank()) add(config.workspace.root)
            config.workspace.extraRoots.filter { it.isNotBlank() }.forEach { add(it) }
        }
        val settings = GatewaySdkSettings.DEFAULT.copy(
            allowedFileDirs = allowedFileDirs,
            downloadMaxBytes = config.fileMessage.maxFileSizeBytes,
            // 🔴 恒 false：不从配置取值（原因见 policy()）。字段保留以便显式覆盖 SDK 默认值，
            //    并保持 `LarkPolicy` 与 SDK `PolicyConfig` 的 5 字段一一对应契约。
            requireMention = policy().requireMention,
        )
        // 入站恒自研：WsClientTransportFactory 必传——SDK 侧 webhook facade 只承担出站/identity
        return SdkLarkGateway(
            netDispatcher = Dispatchers.IO,
            scope = scope,
            wsInboundFactory = WsClientTransportFactory(Dispatchers.IO),
            settings = settings,
        )
    }

    /**
     * 按 dry-run 语义解析网关：`dryRunOverride`（CLI `--dry-run` 旗标）或 `config.connection.dryRun`
     * 任一为真 ⇒ [SyntheticGateway] 替身（本地联调不出网），否则 [sdk]。
     *
     * App 侧调用 `resolve(config, scope)`（dryRunOverride 恒 false，只看配置）；CLI 侧调用
     * `resolve(config, scope, dryRunOverride = dryRun)`（旗标与配置取或）。
     */
    fun resolve(config: BridgeConfig, scope: CoroutineScope, dryRunOverride: Boolean = false): MessageGateway =
        if (dryRunOverride || config.connection.dryRun) SyntheticGateway() else sdk(config, scope)
}
