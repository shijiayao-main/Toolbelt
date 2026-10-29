// :lark-gateway 入站记录的纯数据快照类型；本文件签名禁 SDK 类型（mapper 零 SDK 手法，gateway-contract.md §6.1.1）。
// internal 包：非契约面，:lark-bridge 不应引用。由 SdkLarkGateway 的 capture() 在回调线程内填充（微秒级）。
package com.jiaoay.lark.gateway.internal

/**
 * NormalizedMessage 的只读快照。字段名 = NormalizedMessage 的 16 个 getter 去 get/is 前缀后的驼峰形态。
 * raw / eventId 字段刻意不进（raw 是 deprecated 面；幂等键是 messageId）。
 */
data class NormalizedMessageRecord(
    val messageId: String,
    val chatId: String,
    val chatType: String?,        // "p2p" | "group"（SDK 实测字面值）
    val senderId: String,
    val senderName: String?,
    val content: String?,         // 已转换 + 已剥离 bot mention（stripBotMentions=true 硬编码）
    val rawContentType: String?,  // 不是 messageType（原样透传，含未知值）
    val resources: List<ResourceRecord>,
    val mentions: List<MentionRecord>,
    val mentionAll: Boolean,
    val mentionedBot: Boolean,
    val rootId: String?,
    val threadId: String?,
    val replyToMessageId: String?,
    val createTime: Long,         // long 毫秒（不是字符串）
) {
    /** ResourceDescriptor 的 4 个 getter。 */
    data class ResourceRecord(val type: String?, val fileKey: String?, val fileName: String?, val durationMs: Long)

    /** MentionInfo 的 4 个 getter。 */
    data class MentionRecord(val key: String?, val openId: String?, val userId: String?, val name: String?)
}

/** CardActionEvent 的只读快照。卡片回传也要能定位会话（messageId/chatId）。 */
data class CardActionRecord(
    val operatorId: String?,
    val actionTag: String?,
    val actionName: String?,
    val actionOption: String?,
    val actionValue: String?,
    val messageId: String?,
    val chatId: String?,
)
