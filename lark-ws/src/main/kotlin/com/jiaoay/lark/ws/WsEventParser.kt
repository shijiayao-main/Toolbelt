// :lark-ws —— 事件 payload 解析（P2MessageReceiveV1 瘦身镜像；kotlinx.serialization）。
// 对照 oapi-sdk-java v2.8.5：service/im/v1/model/P2MessageReceiveV1.java + EventMessage.java +
// EventSender.java + MentionEvent.java（mentions 元素）+ event/EventDispatcher.doWithoutValidation 的事件类型提取逻辑。
// 只镜像我方消费的字段（NormalizeMessage 16 getter 中传输层必须提供的子集），
//    富语义（mention 剥离、content 转换）留在 gateway 侧翻译层——transport 层不解业务。
package com.jiaoay.lark.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 已解析的入站事件（transport 层产物；由 gateway 侧翻译成契约类型——本模块零契约依赖）。 */
sealed interface WsInboundEvent {
    /** 事件类型字符串（header.event_type，如 `im.message.receive_v1`；card.action.trigger 走 [CardAction]）。 */
    val eventType: String

    /** 消息接收事件（im.message.receive_v1）——P2MessageReceiveV1 瘦身。 */
    @Serializable
    data class MessageReceived(
        @SerialName("event_type") override val eventType: String,
        val messageId: String,
        val chatId: String,
        val chatType: String,
        val messageType: String,
        val content: String,
        val createTime: String,
        val senderId: String,
        val senderType: String,
        val tenantKey: String? = null,
        val rootId: String? = null,
        val parentId: String? = null,
        val threadId: String? = null,
        val mentions: List<WsMention> = emptyList(),
    ) : WsInboundEvent

    /** 卡片回传事件（card.action.trigger）——P2CardActionTrigger 瘦身；payload 原文留给 gateway 解。 */
    @Serializable
    data class CardAction(
        @SerialName("event_type") override val eventType: String,
        val payload: String,
    ) : WsInboundEvent

    /** 其他已订阅类型（传输层不认识但按事件类型分发）——payload 原文透传。 */
    @Serializable
    data class Unknown(
        @SerialName("event_type") override val eventType: String,
        val payload: String,
    ) : WsInboundEvent
}

/**
 * mention 瘦身（EventMessage.mentions 元素 = SDK MentionEvent；id 双形态归一化产物）。
 * 🔴 v2 事件真源 `mentions[].id` 是 **UserId 对象** `{"user_id":…,"open_id":…,"union_id":…}`
 *    （MentionEvent.java:35-36 的 id 字段类型是 UserId 对象类；UserId.java:21-30 三键均可缺失），
 *    裸 String 形态只存在于 REST 侧 Mention 模型（Mention.java:33-34 配 id_type）——不抄它。
 *    id 在此归一化：对象形态取 open_id/user_id 两键；裸串形态（防御兼容，同 senderId 双形态
 *    先例）整串进 openId；id 缺席则两键 null（对齐 SDK Mentions.java:35-36 的 getId() == null 分支）。
 */
@Serializable
data class WsMention(
    val key: String,
    /** id 归一化的 open_id——bot 判定（Mentions.java:37 纯 open_id 相等）与 MentionRecord.openId 的唯一来源。 */
    val openId: String? = null,
    /** id 归一化的 user_id（MentionInfo 五元组取 getId().getUserId()；裸串/缺席形态为 null）。 */
    val userId: String? = null,
    val name: String? = null,
)

/**
 * 事件 payload JSON 解析器。顶层 class + 构造注入 Json（F 组纪律：无 object 可变态）。
 * 🔴 事件类型提取逻辑照抄 EventDispatcher.doWithoutValidation：`event.type` 与 `header.event_type`
 *    双形态（V1 事件在 event.type；V2 事件在 header.event_type——**header 优先**，SDK 同序；
 *    漏判任一形态即解析抛错、该事件整条丢弃）。
 */
class WsEventParser(private val json: Json = DEFAULT_JSON) {

    fun parse(payload: ByteArray): WsInboundEvent {
        val text = payload.toString(Charsets.UTF_8)
        if (text.isBlank()) throw IllegalArgumentException("empty event payload")
        return parse(text)
    }

    fun parse(payload: String): WsInboundEvent {
        val root = json.parseToJsonElement(payload).jsonObject
        val eventType = extractEventType(root)
            ?: throw IllegalArgumentException("event payload has no type: ${payload.take(80)}")
        return when (eventType) {
            EVENT_MESSAGE_RECEIVE -> parseMessageReceived(root, eventType, payload)
            EVENT_CARD_ACTION -> WsInboundEvent.CardAction(eventType, payload)
            else -> WsInboundEvent.Unknown(eventType, payload)
        }
    }

    private fun extractEventType(root: JsonObject): String? {
        // V1：{"event":{"type":"…",…}}；V2：{"header":{"event_type":"…"}}——V2 header 优先（SDK 同序）
        root["header"]?.takeIf { it is JsonObject }?.jsonObject?.get("event_type")
            ?.takeIf { it is JsonObject == false }?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        root["event"]?.takeIf { it is JsonObject }?.jsonObject?.get("type")
            ?.takeIf { it is JsonObject == false }?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun parseMessageReceived(root: JsonObject, eventType: String, raw: String): WsInboundEvent.MessageReceived {
        val event = root["event"]?.jsonObjectOrNull()
            ?: throw IllegalArgumentException("message event has no event body: ${raw.take(80)}")
        val sender = event["sender"]?.jsonObjectOrNull()
        val message = event["message"]?.jsonObjectOrNull()
            ?: throw IllegalArgumentException("message event has no message body: ${raw.take(80)}")
        return WsInboundEvent.MessageReceived(
            eventType = eventType,
            messageId = message.str("message_id") ?: "",
            chatId = message.str("chat_id") ?: "",
            chatType = message.str("chat_type") ?: "",
            messageType = message.str("message_type") ?: "",
            content = message.str("content") ?: "",
            createTime = message.str("create_time") ?: "",
            senderId = sender?.str("sender_id")?.let { sid ->
                // sender_id 可能是对象（{"open_id":…}）也可能是裸串——真机 payload 两形态都出现过，兼容
                if (sid.isNotEmpty()) sid else ""
            } ?: sender?.jsonObjectOrNull("sender_id")?.str("open_id").orEmpty(),
            senderType = sender?.str("sender_type") ?: "",
            tenantKey = sender?.str("tenant_key"),
            rootId = message.str("root_id")?.takeIf { it.isNotEmpty() },
            parentId = message.str("parent_id")?.takeIf { it.isNotEmpty() },
            threadId = message.str("thread_id")?.takeIf { it.isNotEmpty() },
            mentions = message["mentions"]?.let { el -> parseMentions(el) } ?: emptyList(),
        )
    }

    /**
     * mentions 数组逐元素解析。
     * 🔴 真机 v2 事件 mentions[].id 是 UserId 对象（非裸串）——按裸串解码必抛，
     *    若被 runCatching 吞成**空列表** ⇒ mentionedBot=false ⇒ group_require_mention 误拒。
     *    静默丢 mention 是禁止的事故模式——解析失败必须响亮（key 缺失抛出，由 WsClient 既有
     *    catch 打日志丢弃），不吞异常降级。
     */
    private fun parseMentions(el: JsonElement): List<WsMention> {
        val array = el as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val out = ArrayList<WsMention>(array.size)
        for (item in array) {
            val obj = item as? JsonObject
                ?: throw IllegalArgumentException("mention element is not an object: $item")
            val key = obj.str("key")
            if (key.isNullOrEmpty()) throw IllegalArgumentException("mention missing key: $item")
            var openId: String? = null
            var userId: String? = null
            when (val id = obj["id"]) {
                is JsonObject -> { // 真机 v2 恒对象形态（UserId：三键均可缺失，null 安全对齐 Mentions.java:35-36）
                    openId = id.str("open_id")
                    userId = id.str("user_id")
                }
                is kotlinx.serialization.json.JsonPrimitive -> openId = id.content // 裸串防御兼容（同 senderId 先例）
                else -> {} // id 缺席（JsonNull/不存在）→ 双 null，对齐 SDK getId() == null 分支
            }
            out.add(WsMention(key = key, openId = openId, userId = userId, name = obj.str("name")))
        }
        return out
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { (this[key] as? JsonObject ?: get(key))?.let { el ->
            when (el) {
                is kotlinx.serialization.json.JsonNull -> null
                else -> runCatching { el.jsonPrimitive.content }.getOrNull()
            }
        } }.getOrNull()

    private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? =
        (this[key] as? JsonObject)

    private fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    companion object {
        const val EVENT_MESSAGE_RECEIVE = "im.message.receive_v1"
        const val EVENT_CARD_ACTION = "card.action.trigger"

        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
    }
}
