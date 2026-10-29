// :lark-gateway 策略映射纯函数；本文件签名禁 SDK 类型（mapper 零 SDK 手法，gateway-contract.md §2.1/gateway-contract.md §5）。
// LarkPolicy → GatewaySdkSettings（纯数据），由 SdkLarkGateway.applySettings() 翻译成 SDK 对象（唯一翻译点）。
package com.jiaoay.lark.gateway

/**
 * 策略映射（纯函数、无状态、无 IO）。
 * 语义收窄：只下推 SDK PolicyConfig 能表达的 5 个字段；sender 级 allow/block、blocklist、rejectionReply 不下推。
 * 🔴 SDK 侧 updatePolicy 是全量覆盖那 5 个字段而非增量 merge——本 mapper 产出的 settings 始终含完整 5 字段，
 *    SdkLarkGateway.updatePolicy() 必须整体替换，绝不能只构造部分填充的 PolicyConfig（否则清空 groupAllowlist）。
 */
object LarkPolicyMapper {

    /**
     * LarkPolicy(5 字段) → GatewaySdkSettings(24 字段)。
     * 非 PolicyConfig 的字段一律取 GatewaySdkSettings 的硬编码默认值（不暴露给用户配置）。
     * 🔴 allowedFileDirs 默认空，由引擎经 settings.copy(allowedFileDirs=...) 注入 workspace 根（[SF:916]：不注入则本地路径全被 SSRF 防护拦截）。
     */
    fun toSettings(policy: LarkPolicy): GatewaySdkSettings = GatewaySdkSettings(
        groupAllowlist = policy.groupAllowlist,
        dmMode = policy.dmMode,
        dmAllowlist = policy.dmAllowlist,
        requireMention = policy.requireMention,
        respondToMentionAll = policy.respondToMentionAll,
    )
}
