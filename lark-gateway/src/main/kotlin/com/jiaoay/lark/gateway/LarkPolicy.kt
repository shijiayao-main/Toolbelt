// :lark-gateway 策略与 SDK 设置的纯数据层；本文件签名禁 SDK 类型（mapper 零 SDK 手法的关键，gateway-contract.md §2.1）。
// LarkPolicy 是契约面；GatewaySdkSettings 是 LarkPolicyMapper 的输出（字段名与 SDK 属性名 1:1），由 SdkLarkGateway 翻译成 SDK 对象。
package com.jiaoay.lark.gateway

/**
 * 契约层策略。只含 SDK PolicyConfig 能表达的 5 个字段（语义收窄）。
 * sender 级 allow/block、blocklist、rejectionReply 不下推，由引擎 PipelineSnapshot 原子替换承担 [CDS §4.4]。
 * dmMode 用 String（"open"/"disabled"/"allowlist"），不引入 DmMode 枚举。
 */
data class LarkPolicy(
    val groupAllowlist: List<String> = emptyList(), // 引擎恒传 emptyList（全放行）
    val dmMode: String = "open",                    // "open" | "disabled" | "allowlist"；引擎恒传 "open"
    val dmAllowlist: List<String> = emptyList(),    // dmMode == "allowlist" 时才有意义；引擎恒传 emptyList
    /**
     * 🔴 引擎**恒传 `false`**：SDK 侧全放行，群@门控完全由桥接侧 `PolicyGate` 承担。
     *
     * 为什么不再下推用户配置：SDK `safety.PolicyGate.evaluate()` 的判定颗粒度是
     * **会话级**（只看 chatType + 是否 @机器人），**不区分消息类型**——群文件消息物理上无法携带 @，
     * `requireMention=true` 时 SDK 会把它们全部拦在 `"message"` 事件之前，桥接侧针对
     * `InboundKind.FILE` 的豁免永远执行不到。
     *
     * 字段**保留**（不删）的两个理由：① 与 SDK `PolicyConfig` 保持 5 字段一一对应（本类 KDoc 的契约自证）；
     * ② 显式传 `false` 不依赖「SDK 默认值恰好是 false」，可抵御未来 SDK 默认值变更。
     */
    val requireMention: Boolean = false,
    val respondToMentionAll: Boolean = true,        // 引擎恒传 true
)

/**
 * mapper 的输出：纯数据，字段名与 SDK 属性名 1:1。这是「mapper 零 SDK 类型」的关键。
 * 🔴 含全部必须覆写的 SDK 默认值：chatQueueEnabled=false、retryMaxAttempts=1（关 SDK 盲退避）、
 * streamThrottleMs=250 + streamThrottleChars=4000（Throttle 是「或」关系）、textChunkLimit=3500。
 * 🔴 allowedFileDirs 默认空，由引擎经 copy() 注入 workspace 根（[SF:916]：不注入则本地路径全被 SSRF 防护拦截）。
 * downloadSingleFlight 是 Int=2（Semaphore(2) 并发上限），非 Boolean。
 */
data class GatewaySdkSettings(
    // —— SafetyConfig ——
    val chatQueueEnabled: Boolean = false,        // 🔴 SDK 属性名 isChatQueueEnabled，恒 false——误开 true 则 600ms 窗口合并消息、只留最后一条
    val dedupTtlMs: Long = 43_200_000L,           // 12h（SDK 默认）
    val dedupMaxEntries: Int = 5000,              // SDK 默认
    val dedupSweepMs: Long = 300_000L,            // 5min（SDK 默认）
    val staleMessageWindowMs: Long = 1_800_000L,  // 30min（SDK 默认）
    val processingLockTtlMs: Long = 300_000L,     // 5min（SDK 默认）
    // —— PolicyConfig ——
    val groupAllowlist: List<String> = emptyList(),
    val dmMode: String = "open",
    val dmAllowlist: List<String> = emptyList(),
    val requireMention: Boolean = false,
    val respondToMentionAll: Boolean = true,
    // —— BatchTextConfig（入站合批）—— chatQueueEnabled=false 后全部失效，仅为完整性保留默认值
    val batchDelayMs: Int = 600,                  // SDK 默认
    // —— OutboundConfig（出站）——
    val textChunkLimit: Int = 3500,               // 出站分片属 OutboundConfig
    val streamThrottleMs: Int = 250,              // SDK 默认 100（零余量），我方 250（留 60% 余量）
    val streamThrottleChars: Int = 4000,          // SDK 默认 50，必须调大（Throttle 是「或」关系）
    val streamInitialText: String = "Thinking...",// SDK 默认；我方自建卡 JSON，不用，保留默认
    val ssrfGuardEnabled: Boolean = true,         // SDK 默认 true
    val ssrfAllowlist: List<String> = emptyList(),// SDK 默认空
    val allowedFileDirs: List<String> = emptyList(), // 🔴 SDK 默认空 ⇒ 必须由引擎注入（[SF:916]：不注入则本地路径全被 SSRF 防护拦截）
    // —— RetryConfig ——
    val retryMaxAttempts: Int = 1,                // SDK 默认 3，关掉盲指数退避
    val retryBaseDelayMs: Int = 500,              // SDK 默认；maxAttempts=1 时不生效
    // —— 网关自有（非 SDK 字段，一并放这里以便单点注入）——
    val downloadMaxBytes: Long = 1_073_741_824L,   // = fileMessage.maxFileSizeBytes 默认 1GB（Range 分片已突破平台 100MB 限，此值转为纯磁盘保护阈值）
    val downloadResourceType: String = "file",    // GetMessageResourceReq.setType()
    val downloadSingleFlight: Int = 2,            // 并发下载上限（Semaphore(2)；分片后堆峰值 = 并发数 × 单片大小）
    val downloadChunkBytes: Int = 33_554_431,     // 单片字节数 = 32MB-1（🔴 飞书官方限制单片 ≤ 32MB，33554432 会超）
    val downloadMaxChunks: Int = 4_096,           // 片数上限（防「1 字节片」病态服务器；诚实分片数 = 1GB/32MB = 32，4096 给百倍余量）
) {
    companion object {
        val DEFAULT = GatewaySdkSettings()
    }
}
