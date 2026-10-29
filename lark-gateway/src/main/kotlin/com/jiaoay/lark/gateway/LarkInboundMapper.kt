// :lark-gateway 入站映射纯函数；本文件签名禁 SDK 类型（mapper 零 SDK 手法，gateway-contract.md §6.0 纪律 1）。
// NormalizedMessageRecord / CardActionRecord（internal 包）→ InboundMessage（契约面）。
package com.jiaoay.lark.gateway

import com.jiaoay.lark.gateway.internal.CardActionRecord
import com.jiaoay.lark.gateway.internal.NormalizedMessageRecord

/**
 * 入站映射（纯函数、无状态、无 IO、无隐式时钟）。
 * 🔴 未知值一律走 fallback，绝不抛异常——抛了就炸整条入站链路。时钟由调用方注入（receivedAtMillis）。
 */
object LarkInboundMapper {

    /** NormalizedMessageRecord → InboundMessage（gateway-contract.md §6.1.3 字段对照表）。 */
    fun fromMessage(r: NormalizedMessageRecord, receivedAtMillis: Long): InboundMessage = InboundMessage(
        messageId = r.messageId,                 // 幂等键；isBlank() 仍产出，由引擎 MessageDeduper 丢弃
        chatId = r.chatId,
        chatType = chatTypeOf(r.chatType),
        senderId = r.senderId,
        senderName = r.senderName ?: "",         // SDK 只从事件本身取，不额外发 HTTP
        text = r.content ?: "",                  // 🔴 不得二次剥离 @机器人（SDK 已剥，stripBotMentions=true）——二次剥离会误删用户正文
        kind = kindOf(r.rawContentType),
        rawContentType = r.rawContentType,       // 原样透传（含未知值），供可观测与 P11 回填
        resources = r.resources.map { InboundResource(it.type, it.fileKey, it.fileName, it.durationMs) },
        mentions = r.mentions.map { InboundMention(it.key, it.openId, it.userId, it.name) },
        mentionAll = r.mentionAll,
        mentionedBot = r.mentionedBot,
        rootId = r.rootId,
        threadId = r.threadId,
        replyToMessageId = r.replyToMessageId,
        createTimeMillis = r.createTime,
        cardAction = null,                       // fromMessage 恒 null
        gatewayReceivedAtMillis = receivedAtMillis,
    )

    /**
     * CardActionRecord → InboundMessage（gateway-contract.md §6.1.5 三条规则）。
     * 规则 1：messageId = r.messageId ?: ""（卡片回传可能没有 messageId，引擎去重/路由须容忍空值）。
     * 规则 2：kind 恒 CARD_ACTION，rawContentType 恒 null（CardActionEvent 无该 getter）。
     * 规则 3：chatType 恒 UNKNOWN（卡片回传事件不提供 chatType）。
     */
    fun fromCardAction(r: CardActionRecord, receivedAtMillis: Long): InboundMessage = InboundMessage(
        messageId = r.messageId ?: "",
        chatId = r.chatId ?: "",
        chatType = ChatType.UNKNOWN,
        senderId = r.operatorId ?: "",
        senderName = "",
        text = "",
        kind = InboundKind.CARD_ACTION,
        rawContentType = null,
        resources = emptyList(),
        mentions = emptyList(),
        mentionAll = false,
        mentionedBot = false,
        rootId = null,
        threadId = null,
        replyToMessageId = null,
        createTimeMillis = receivedAtMillis,     // CardActionEvent 无 createTime getter ⇒ 用网关接收时刻（> 0）
        cardAction = CardAction(
            operatorId = r.operatorId ?: "",
            actionTag = r.actionTag,
            actionName = r.actionName,
            actionOption = r.actionOption,
            actionValue = r.actionValue,
        ),
        gatewayReceivedAtMillis = receivedAtMillis,
    )

    /** "p2p"→P2P；"group"→GROUP；其它/null→UNKNOWN。大小写敏感精确匹配（SDK 字面值实测小写）。 */
    fun chatTypeOf(raw: String?): ChatType = when (raw) {
        "p2p" -> ChatType.P2P
        "group" -> ChatType.GROUP
        else -> ChatType.UNKNOWN
    }

    /**
     * rawContentType → InboundKind（容忍未知值）。
     * 🔴 禁止写成 enum valueOf（会在未知值上抛 IllegalArgumentException）。else 兜底 UNKNOWN，绝不抛、绝不丢弃。
     * 🔴 "interactive"/"card" → UNKNOWN（不是 CARD_ACTION）：卡片回传走独立事件流 "cardAction"。
     */
    fun kindOf(rawContentType: String?): InboundKind = when (rawContentType) {
        "text" -> InboundKind.TEXT
        "image" -> InboundKind.IMAGE
        "file" -> InboundKind.FILE
        "audio", "media" -> InboundKind.AUDIO
        "video" -> InboundKind.VIDEO
        "post" -> InboundKind.POST
        "merge_forward" -> InboundKind.MERGE_FORWARD
        "sticker" -> InboundKind.STICKER
        "share_chat" -> InboundKind.SHARE_CHAT
        "share_user" -> InboundKind.SHARE_USER
        else -> InboundKind.UNKNOWN
    }
}
