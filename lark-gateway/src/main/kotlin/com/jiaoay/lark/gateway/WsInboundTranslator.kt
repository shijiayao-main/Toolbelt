// :lark-gateway —— 自研 WS 入站翻译层（SDK channel/normalize 的 Kotlin 复刻）。
// 把 :lark-ws 的 WsInboundEvent 翻译成 internal NormalizedMessageRecord / CardActionRecord——
// 下游复用 LarkInboundMapper 纯函数 + offerInbound（回调纪律见 SdkLarkGateway），对 SdkLarkGateway 零新契约。
//
// 复刻范围（对照 oapi-sdk-java v2.8.5 源码，逐文件行号见各函数 KDoc）：
// - MessageNormalizer.java:21-61 总管线（mention 提取 → converter → 第二遍 mention 替换）
// - ConverterRegistry.java:27-53 分发 + MessageConverters.java:19-30 异常吞掉走 fallback
// - Mentions.java:20-55/109-133 提取与替换 + NormalizeTexts.java:135-141 空格归一
// - FileMessageConverter/ImageMessageConverter/AudioMessageConverter/VideoMessageConverter/
//   TextMessageConverter/FallbackMessageConverter（我方消费面够用的子集——kindOf 只认这些类型，
//   其余类型 SDK 也是产占位文本 + 空 resources，fallback 语义等价覆盖）
// - CardActionNormalizer.java:16-65（卡片回传 7 字段 + 闸门）
//
// 刻意不复刻（我方消费面零消费或刻意不偏离）：
// - post/merge_forward/interactive/todo/calendar 等富转换：kindOf 把它们全归 UNKNOWN，下游不路由；
//   fallback（读 text 键 / [unsupported message]）与 SDK 行为等价且更稳。
// - MentionInfo 五元组（Mentions.java:38）：NormalizedMessageRecord.MentionRecord(key, openId, userId, name)
//   照抄 SDK 形态——v2 事件 mentions[].id 是 UserId 对象，openId/userId 由 :lark-ws 解析层归一化后直通。
// - String.replace 字面子串替换（含 @_user_1 前缀吃掉 @_user_10 的边界缺陷）：与 SDK 逐字节对齐，不修。
package com.jiaoay.lark.gateway

import com.jiaoay.lark.gateway.internal.CardActionRecord
import com.jiaoay.lark.gateway.internal.NormalizedMessageRecord
import com.jiaoay.lark.ws.WsInboundEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WS 入站事件翻译器。顶层 class + 构造注入（F 组纪律：无 object 静态可变态）。
 * 纯函数语义：同输入同产出、绝不抛（未知值 fallback）。
 *
 * @param botOpenIdProvider connect 成功后的 BotIdentity open_id 延迟取用——mentionedBot 判定
 *   只比 open_id 字符串（Mentions.java:37，不看 mentioned_type）。
 */
class WsInboundTranslator(
    private val botOpenIdProvider: () -> String?,
    private val json: Json = DEFAULT_JSON,
) {

    /** 消息事件 → record；解析失败不抛（fallback 管线兜底，MessageConverters.java:25-29 同语义）。 */
    fun translate(ev: WsInboundEvent.MessageReceived): NormalizedMessageRecord {
        val botOpenId = botOpenIdProvider()
        val mentionState = extractMentions(ev.mentions, botOpenId)
        val converted = convertContent(ev.messageType, ev.content)
        // 第二遍统一替换 + 空格归一（MessageNormalizer.java:36 对所有类型执行）
        val content = resolveMentions(converted.first, mentionState)
        return NormalizedMessageRecord(
            messageId = ev.messageId,
            chatId = ev.chatId,
            chatType = ev.chatType.takeIf { it.isNotEmpty() },
            senderId = ev.senderId,
            senderName = null, // SDK 生产路径恒 null（NormalizeOptions.resolveSenderName 传 null）；名字由桥侧 UserNameResolver 补拉
            content = content,
            rawContentType = ev.messageType.takeIf { it.isNotEmpty() },
            resources = converted.second,
            mentions = mentionState.map { NormalizedMessageRecord.MentionRecord(it.key, it.openId, it.userId, it.name) },
            mentionAll = mentionState.isMentionAll || MENTION_ALL_IN_CONTENT.containsMatchIn(ev.content),
            mentionedBot = mentionState.any { it.isBot } ||
                (botOpenId != null && mentionState.any { it.openId == botOpenId }),
            rootId = ev.rootId,
            threadId = ev.threadId,
            replyToMessageId = ev.parentId, // MessageNormalizer.java:57：parent_id（非 root_id）
            createTime = parseLongOrZero(ev.createTime), // NormalizeTexts.java:24-30：失败归零
        )
    }

    /** 卡片回传 → record；messageId/chatId/operatorId 任一 blank 丢弃返回 null（CardActionNormalizer.java:42-44 闸门）。 */
    fun translateCardAction(payload: String): CardActionRecord? {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val event = (root["event"] as? JsonObject) ?: root // 容忍扁平无 envelope 旧形态（:80-84）
        val messageId = event.str("context")?.let { (it as? JsonObject)?.str("open_message_id")?.contentOrNull() }
        val chatId = event.str("context")?.let { (it as? JsonObject)?.str("open_chat_id")?.contentOrNull() }
        val operator = event["operator"] as? JsonObject
        val operatorId = firstNonBlank(
            operator?.str("open_id")?.contentOrNull(),
            operator?.str("user_id")?.contentOrNull(),
            operator?.str("union_id")?.contentOrNull(),
        )
        if (messageId.isNullOrBlank() || chatId.isNullOrBlank() || operatorId.isNullOrBlank()) return null
        val action = event["action"] as? JsonObject
        // actionValue 的 Map.toString 形态对齐 SDK capture：Map<String,Any> → toString（M-19 保真度同款）
        val actionValue = action?.get("value")?.let { v ->
            when {
                v is JsonObject && v.keys.isNotEmpty() -> mapToString(v)
                v is JsonPrimitive -> "{value=${v.content}}" // 标量兜底 singletonMap("value", 标量) 的 toString 形态
                else -> null
            }
        }
        return CardActionRecord(
            operatorId = operatorId,
            actionTag = action?.str("tag")?.contentOrNull() ?: "unknown", // :46-51：空 → unknown
            actionName = action?.str("name")?.contentOrNull(),
            actionOption = action?.str("option")?.contentOrNull(),
            actionValue = actionValue,
            messageId = messageId,
            chatId = chatId,
        )
    }

    // ==================== mention 状态（Mentions.java 复刻）====================

    /** 提取后的 mention（MentionInfo 镜像；@_all 不入列表只驱动布尔，Mentions.java:31-34 continue）。 */
    private class ExtractedMention(
        val key: String,
        val openId: String?,
        val userId: String?,
        val name: String?,
        val isBot: Boolean,
    )

    private class MentionState(val mentions: List<ExtractedMention>, val isMentionAll: Boolean) {
        operator fun iterator(): Iterator<ExtractedMention> = mentions.iterator()
        fun any(pred: (ExtractedMention) -> Boolean) = mentions.any(pred)
        fun map(f: (ExtractedMention) -> NormalizedMessageRecord.MentionRecord) = mentions.map(f)
    }

    /** Mentions.extract（:20-55 事件重载）。 */
    private fun extractMentions(eventMentions: List<com.jiaoay.lark.ws.WsMention>, botOpenId: String?): MentionState {
        val out = ArrayList<ExtractedMention>()
        var mentionAll = false
        for (m in eventMentions) {
            if (m.key == "@_all") {
                mentionAll = true
                continue // 不进列表
            }
            // 纯 open_id 相等（:35-37 访问链 getId().getOpenId() == BotIdentity.getOpenId()，null 安全）
            val isBot = botOpenId != null && m.openId == botOpenId
            out.add(ExtractedMention(key = m.key, openId = m.openId, userId = m.userId, name = m.name, isBot = isBot))
        }
        return MentionState(out, mentionAll)
    }

    /**
     * 第二遍替换 + 空格归一（Mentions.resolveMentions :109-133 + normalizeSpaces :135-141）。
     * String.replace 字面子串替换（非正则、非 token 化）——@_user_1 会吃掉 @_user_10 前缀，
     *    与 SDK 逐字节对齐（含该边界缺陷，不修）。
     */
    private fun resolveMentions(raw: String, state: MentionState): String {
        var result = raw
        for (m in state.mentions) {
            val replacement = "@" + firstNonBlank(m.name, m.openId, m.key) // :118-124 三级回退
            result = if (m.isBot) {
                result.replace(m.key, "").replace(replacement, "") // bot 剥两种形态（:127）
            } else {
                result.replace(m.key, replacement) // :129 非 bot 一律换成可读 @Name
            }
        }
        return normalizeSpaces(result)
    }

    /** normalizeSpaces（NormalizeTexts.java:135-141）：NBSP→空格、trim、双空格折叠。 */
    private fun normalizeSpaces(s: String): String {
        var r = s.replace(' ', ' ')
        r = r.trim()
        while (r.contains("  ")) r = r.replace("  ", " ")
        return r
    }

    // ==================== content 转换（converters 子集复刻）====================

    /** 类型转换产出：(content 文本, resources)。异常吞掉走 fallback（MessageConverters.java:25-29）。 */
    private fun convertContent(messageType: String?, content: String): Pair<String, List<NormalizedMessageRecord.ResourceRecord>> =
        runCatching { convertInner(messageType ?: "unknown", content) } // null 按 unknown（:20）
            .getOrElse { fallbackConvert(content) }

    private fun convertInner(type: String, content: String): Pair<String, List<NormalizedMessageRecord.ResourceRecord>> {
        val parsed: JsonObject? = runCatching {
            if (content.isBlank()) null else json.parseToJsonElement(content).jsonObject
        }.getOrNull()
        return when (type) {
            "text" -> (parsed?.str("text")?.contentOrNull() ?: "") to emptyList() // TextMessageConverter:14-17
            "file" -> fileConvert(parsed) // FileMessageConverter:17-24
            "image" -> imageConvert(parsed) // ImageMessageConverter:16-21
            "audio" -> audioConvert(parsed) // AudioMessageConverter:17-25
            "video", "media" -> videoConvert(parsed) // VideoMessageConverter:16-28；media 同一个 converter
            else -> fallbackConvert(content) // unknown/interactive/post/... 一律 fallback（Registry 未注册的同款语义）
        }
    }

    private fun fileConvert(parsed: JsonObject?): Pair<String, List<NormalizedMessageRecord.ResourceRecord>> {
        val key = parsed?.str("file_key")?.contentOrNull()
        if (key.isNullOrEmpty()) return "[file]" to emptyList() // 缺 key → 占位（FileMessageConverter:19-20）
        val name = parsed?.str("file_name")?.contentOrNull()
        val text = if (name.isNullOrEmpty()) "<file key=\"$key\"/>" // name 缺省整体省属性（:22）
        else """<file key="$key" name="${escapeAttr(name)}"/>""" // escapeAttr 只转义双引号（NormalizeTexts:97-99）
        return text to listOf(NormalizedMessageRecord.ResourceRecord("file", key, name, 0L)) // ResourceDescriptor: ("file", key, name, null, null)
    }

    private fun imageConvert(parsed: JsonObject?): Pair<String, List<NormalizedMessageRecord.ResourceRecord>> {
        val key = parsed?.str("image_key")?.contentOrNull()
        if (key.isNullOrEmpty()) return "[image]" to emptyList()
        return "![image]($key)" to listOf(NormalizedMessageRecord.ResourceRecord("image", key, null, 0L))
    }

    private fun audioConvert(parsed: JsonObject?): Pair<String, List<NormalizedMessageRecord.ResourceRecord>> {
        val key = parsed?.str("file_key")?.contentOrNull()
        if (key.isNullOrEmpty()) return "[audio]" to emptyList()
        val durationMs = parsed?.str("duration")?.contentOrNull()?.let { parseLongOrZero(it) } ?: 0L
        val text = if (durationMs > 0) "<audio key=\"$key\" duration=\"${formatDuration(durationMs)}\"/>"
        else "<audio key=\"$key\"/>" // duration 缺失/非正 → 省属性（AudioMessageConverter:21-22）
        return text to listOf(NormalizedMessageRecord.ResourceRecord("audio", key, null, durationMs))
    }

    private fun videoConvert(parsed: JsonObject?): Pair<String, List<NormalizedMessageRecord.ResourceRecord>> {
        val key = parsed?.str("file_key")?.contentOrNull()
        if (key.isNullOrEmpty()) return "[video]" to emptyList()
        val name = parsed?.str("file_name")?.contentOrNull()
        val durationMs = parsed?.str("duration")?.contentOrNull()?.let { parseLongOrZero(it) } ?: 0L
        val cover = parsed?.str("image_key")?.contentOrNull()
        val attrs = buildString {
            if (!name.isNullOrEmpty()) append(""" name="${escapeAttr(name)}"""")
            if (durationMs > 0) append(""" duration="${formatDuration(durationMs)}"""")
        }
        // media 类型的 resource type 也是 "video"（ConverterRegistry.java:36）
        return "<video key=\"$key\"$attrs/>" to listOf(NormalizedMessageRecord.ResourceRecord("video", key, name, durationMs))
    }

    /** FallbackMessageConverter:13-16——读顶层 text 键，无则占位。 */
    private fun fallbackConvert(content: String): Pair<String, List<NormalizedMessageRecord.ResourceRecord>> {
        val text = runCatching {
            json.parseToJsonElement(content).jsonObject.str("text")?.contentOrNull()
        }.getOrNull()
        return (text ?: "[unsupported message]") to emptyList()
    }

    // ==================== 工具（NormalizeTexts 复刻）====================

    /** formatDuration（NormalizeTexts.java:32-43）：<1000ms 原样 ms；整秒 "Ns"；否则 US Locale %.1fs。 */
    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms % 1000 == 0L -> "${ms / 1000}s"
        else -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    }

    /** parseLong（NormalizeTexts.java:24-30）：null/异常一律 0L。 */
    private fun parseLongOrZero(v: String?): Long =
        if (v.isNullOrBlank()) 0L else v.toLongOrNull() ?: 0L

    /** firstNonBlank（NormalizeTexts.java:101-105）。 */
    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    /** escapeAttr（NormalizeTexts.java:97-99）：只转义双引号。 */
    private fun escapeAttr(s: String): String = s.replace("\"", "&quot;")

    private fun JsonObject.str(key: String) = this[key]

    private fun kotlinx.serialization.json.JsonElement?.contentOrNull(): String? =
        runCatching { (this as? JsonPrimitive)?.content }.getOrNull()

    /**
     * JsonObject → Map.toString 形态（{k=v, k2=v2}）。值形态对齐 SDK：SDK 侧 CardActionNormalizer
     * 用 Gson 把 value 反序列化成 Map<String,Any> 再 toString——字符串值**无引号**（LinkedHashMap 的
     * toString 只写内容）；kotlinx JsonElement.toString 会带引号 ⇒ 字符串值须剥引号对齐
     * （下游 InteractionHandler 按 lastIndexOf(':') 解析，两形态均兼容；但对齐后真机对账
     * 日志可直接 diff）。
     */
    private fun mapToString(obj: JsonObject): String =
        obj.entries.joinToString(", ", "{", "}") { (k, v) -> "$k=${gsonValueForm(v)}" }

    /** Gson Map<String,Any> 的值 toString 形态：字符串取内容（无引号），其余（数字/布尔/嵌套）原样。 */
    private fun gsonValueForm(v: kotlinx.serialization.json.JsonElement): String = when (v) {
        is kotlinx.serialization.json.JsonPrimitive -> v.content   // "t1" → t1（Gson 产 String，toString 无引号）
        else -> v.toString()
    }

    companion object {
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }

        /** Mentions.java:14-15：`@_all\b` 扫的是原始 content 字符串（含 JSON 转义环境）。 */
        private val MENTION_ALL_IN_CONTENT = Regex("@_all\\b")
    }
}
