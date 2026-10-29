// :lark-ws —— 事件 payload 解析单测：V1/V2 事件类型双形态 / 消息事件全字段 / card / 未知类型。
package com.jiaoay.lark.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WsEventParserTest {

    private val parser = WsEventParser()

    /** 真机形态样例（P2MessageReceiveV1，V2 header.event_type——SDK EventDispatcher 优先序）。 */
    private val messageEventJson = """
        {
          "schema": "2.0",
          "header": {
            "event_id": "e_1",
            "event_type": "im.message.receive_v1",
            "create_time": "1700000000000",
            "token": "v1",
            "app_id": "cli_a",
            "tenant_key": "t1"
          },
          "event": {
            "sender": {
              "sender_id": {"union_id": "on_u", "user_id": "on_u2", "open_id": "ou_sender"},
              "sender_type": "user",
              "tenant_key": "t1"
            },
            "message": {
              "message_id": "om_msg_1",
              "root_id": "",
              "parent_id": "om_parent",
              "thread_id": "om_thread",
              "create_time": "1700000001000",
              "chat_id": "oc_chat",
              "chat_type": "group",
              "message_type": "text",
              "content": "{\"text\":\"@_user_1 hello\"}",
              "mentions": [
                {"key": "@_user_1", "id": {"open_id": "ou_mention", "user_id": "u_mention", "union_id": "on_m"}, "name": "被提及"}
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `消息事件全字段解析（V2 header 事件类型优先）`() {
        val ev = parser.parse(messageEventJson)
        val msg = assertIs<WsInboundEvent.MessageReceived>(ev)
        assertEquals("im.message.receive_v1", msg.eventType)
        assertEquals("om_msg_1", msg.messageId)
        assertEquals("oc_chat", msg.chatId)
        assertEquals("group", msg.chatType)
        assertEquals("text", msg.messageType)
        assertEquals("{\"text\":\"@_user_1 hello\"}", msg.content)
        assertEquals("1700000001000", msg.createTime)
        assertEquals("ou_sender", msg.senderId)
        assertEquals("user", msg.senderType)
        assertEquals("om_parent", msg.parentId)
        assertEquals("om_thread", msg.threadId)
        assertEquals("om_thread", msg.threadId)
        assertNull(msg.rootId) // 空串转 null
        assertEquals(1, msg.mentions.size)
        // 🔴 v2 事件真源形态：id 是 UserId 对象（MentionEvent.java:35-36），解析层归一化 openId/userId
        assertEquals(WsMention(key = "@_user_1", openId = "ou_mention", userId = "u_mention", name = "被提及"), msg.mentions[0])
    }

    private fun <T> assertNull(v: T?) = kotlin.test.assertNull(v)

    @Test
    fun `V1 事件类型形态（event type 字段）也能识别`() {
        // V1 事件无 header.event_type，类型在 event.type——SDK doWithoutValidation 同序回退
        val v1 = """{"event":{"type":"im.message.receive_v1","message":{"message_id":"om_v1","chat_id":"oc","message_type":"text","content":"{}","create_time":"1","chat_type":"p2p"},"sender":{"sender_id":"ou_x","sender_type":"user"}}}"""
        val ev = parser.parse(v1)
        val msg = assertIs<WsInboundEvent.MessageReceived>(ev)
        assertEquals("im.message.receive_v1", msg.eventType)
        assertEquals("om_v1", msg.messageId)
        assertEquals("ou_x", msg.senderId) // 裸串 sender_id 形态
    }

    @Test
    fun `sender_id 对象形态取 open_id`() {
        val obj = """{"event":{"type":"im.message.receive_v1","message":{"message_id":"m","chat_id":"c","message_type":"text","content":"","create_time":"1","chat_type":"p2p"},"sender":{"sender_id":{"open_id":"ou_obj"},"sender_type":"app"}}}"""
        val ev = parser.parse(obj)
        assertEquals("ou_obj", assertIs<WsInboundEvent.MessageReceived>(ev).senderId)
    }

    @Test
    fun `card 事件按 payload 透传`() {
        val card = """{"header":{"event_type":"card.action.trigger"},"event":{}}"""
        val ev = parser.parse(card)
        val cardEvent = assertIs<WsInboundEvent.CardAction>(ev)
        assertEquals("card.action.trigger", cardEvent.eventType)
        assertTrue(cardEvent.payload.contains("card.action.trigger"))
    }

    @Test
    fun `未知事件类型透传原文`() {
        val unknown = """{"header":{"event_type":"im.chat.member.bot.added_v1"},"event":{"chat_id":"oc"}}"""
        val ev = parser.parse(unknown)
        val u = assertIs<WsInboundEvent.Unknown>(ev)
        assertEquals("im.chat.member.bot.added_v1", u.eventType)
        assertTrue(u.payload.isNotEmpty())
    }

    @Test
    fun `无事件类型拒绝`() {
        assertFailsWith<IllegalArgumentException> { parser.parse("""{"foo":1}""") }
        assertFailsWith<IllegalArgumentException> { parser.parse("{}") }
    }

    @Test
    fun `空 payload 拒绝`() {
        assertFailsWith<IllegalArgumentException> { parser.parse(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { parser.parse("  ") }
    }

    @Test
    fun `byteArray 入参解析同 string`() {
        val ev = parser.parse(messageEventJson.toByteArray(Charsets.UTF_8))
        assertEquals("om_msg_1", assertIs<WsInboundEvent.MessageReceived>(ev).messageId)
    }

    @Test
    fun `bot p2p mention 事件形态（真机 2026-09 实测 payload 结构）`() {
        // 真机实测：p2p 消息 mentions 为空数组、root/parent/thread 为空串
        val p2p = """{"schema":"2.0","header":{"event_type":"im.message.receive_v1"},"event":{"sender":{"sender_id":{"open_id":"ou_p"},"sender_type":"user"},"message":{"message_id":"om_p2p","root_id":"","parent_id":"","thread_id":"","create_time":"1700000000000","chat_id":"oc_p","chat_type":"p2p","message_type":"file","content":"{\"file_key\":\"fk_1\",\"file_name\":\"a.zip\"}","mentions":[]}}}"""
        val msg = assertIs<WsInboundEvent.MessageReceived>(parser.parse(p2p))
        assertEquals("file", msg.messageType)
        assertEquals(0, msg.mentions.size)
        assertNull(msg.rootId)
        assertNull(msg.parentId)
        assertNull(msg.threadId)
        assertTrue(msg.content.contains("file_key"))
    }

    // ==================== 🔴 2026-09-13 群@门控误拒事故防线（mentions[].id 双形态）====================

    /** 裸串 id 防御兼容：解析层把整串归一化进 openId（同 senderId 双形态先例）。 */
    @Test
    fun `mention id 裸串形态兼容进 openId`() {
        val raw = """{"event":{"type":"im.message.receive_v1","message":{"message_id":"m1","chat_id":"c","message_type":"text","content":"","create_time":"1","chat_type":"group","mentions":[{"key":"@_user_1","id":"ou_bare","name":"裸串"}]},"sender":{"sender_id":"ou_x","sender_type":"user"}}}"""
        val msg = assertIs<WsInboundEvent.MessageReceived>(parser.parse(raw))
        assertEquals(listOf(WsMention(key = "@_user_1", openId = "ou_bare", userId = null, name = "裸串")), msg.mentions)
    }

    /** UserId 对象三键均可缺失（null 安全对齐 Mentions.java:35-36）；缺失键为 null 不炸。 */
    @Test
    fun `mention id 对象形态缺 open_id 时 openId 为 null`() {
        val raw = """{"event":{"type":"im.message.receive_v1","message":{"message_id":"m2","chat_id":"c","message_type":"text","content":"","create_time":"1","chat_type":"group","mentions":[{"key":"@_user_1","id":{"user_id":"u_only","union_id":"on_only"},"name":"缺开"}]},"sender":{"sender_id":"ou_x","sender_type":"user"}}}"""
        val msg = assertIs<WsInboundEvent.MessageReceived>(parser.parse(raw))
        assertEquals(WsMention(key = "@_user_1", openId = null, userId = "u_only", name = "缺开"), msg.mentions[0])
    }

    /** id 整体缺席 → openId/userId 双 null（对齐 SDK getId() == null 的 null 安全分支）。 */
    @Test
    fun `mention id 缺席时 openId userId 均为 null`() {
        val raw = """{"event":{"type":"im.message.receive_v1","message":{"message_id":"m3","chat_id":"c","message_type":"text","content":"","create_time":"1","chat_type":"group","mentions":[{"key":"@_user_1","name":"无id"}]},"sender":{"sender_id":"ou_x","sender_type":"user"}}}"""
        val msg = assertIs<WsInboundEvent.MessageReceived>(parser.parse(raw))
        assertEquals(WsMention(key = "@_user_1", openId = null, userId = null, name = "无id"), msg.mentions[0])
    }

    /**
     * 🔴 key 缺失响亮拒绝：静默丢 mention 正是本事故「mention 失真 ⇒ 门控误拒且零日志」的模式——
     * 解析失败必须抛（由 WsClient 既有 catch 打日志丢弃整条），不得吞成空列表。
     */
    @Test
    fun `mention 缺 key 拒绝整条事件`() {
        val raw = """{"event":{"type":"im.message.receive_v1","message":{"message_id":"m4","chat_id":"c","message_type":"text","content":"","create_time":"1","chat_type":"group","mentions":[{"id":{"open_id":"ou_nokey"},"name":"无key"}]},"sender":{"sender_id":"ou_x","sender_type":"user"}}}"""
        assertFailsWith<IllegalArgumentException> { parser.parse(raw) }
    }
}
