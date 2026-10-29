// :lark-gateway 测试（M3-T3，§11.2 = BC NF-A2 LarkInboundMapperTest）。
// 🔴 纯函数测试：零 SDK 加载（§6.0 纪律 1 的收益），无挂起故不需 runTest。
package com.jiaoay.lark.gateway

import com.jiaoay.lark.gateway.internal.CardActionRecord
import com.jiaoay.lark.gateway.internal.NormalizedMessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §11.2：NormalizedMessageRecord / CardActionRecord → InboundMessage 的入站映射。
 *
 * 🔴 与规格的差异（施工实测，记入报告）：§11.2 ② 写「用反射 `InboundMessage::class.memberProperties`」，
 * 但 `memberProperties` 需 `kotlin-reflect`（本模块未引入）。改用等价的 `InboundMessage::class.java.declaredFields`
 * （JDK 反射，零依赖），断言意图完全一致：InboundMessage 结构性地不存在 eventId 字段（决策 15 / D12）。
 */
class LarkInboundMapperTest {

    /** 录制样本工厂（§11.2 ⑩：以 Kotlin 字面量写在测试文件内，不放 resources，避免 jlink/打包路径问题）。 */
    private fun record(
        messageId: String = "om_1",
        chatId: String = "oc_1",
        chatType: String? = "group",
        senderId: String = "ou_1",
        senderName: String? = "Alice",
        content: String? = "hello",
        rawContentType: String? = "text",
        resources: List<NormalizedMessageRecord.ResourceRecord> = emptyList(),
        mentions: List<NormalizedMessageRecord.MentionRecord> = emptyList(),
        mentionAll: Boolean = false,
        mentionedBot: Boolean = false,
        rootId: String? = null,
        threadId: String? = null,
        replyToMessageId: String? = null,
        createTime: Long = 1_700_000_000_000L,
    ) = NormalizedMessageRecord(
        messageId, chatId, chatType, senderId, senderName, content, rawContentType,
        resources, mentions, mentionAll, mentionedBot, rootId, threadId, replyToMessageId, createTime,
    )

    // ① 入站不做跨消息合并：mapper 是严格 1:1 纯函数，3 条 record ⇒ 3 条独立 InboundMessage
    @Test
    fun `three records in same chat map to three independent messages without merge`() {
        val records = listOf(
            record(messageId = "om_1", senderId = "ou_1", content = "a", createTime = 1_700_000_000_000L),
            record(messageId = "om_2", senderId = "ou_2", content = "b", createTime = 1_700_000_000_300L),
            record(messageId = "om_3", senderId = "ou_3", content = "c", createTime = 1_700_000_000_600L),
        )
        val msgs = records.map { LarkInboundMapper.fromMessage(it, receivedAtMillis = 1_700_000_001_000L) }
        assertEquals(3, msgs.size)
        // 每条的 messageId / senderId / createTimeMillis 各自独立，不被后一条覆盖（决策 4 / §5.5：合并会丢 messageId）
        assertEquals(listOf("om_1", "om_2", "om_3"), msgs.map { it.messageId })
        assertEquals(listOf("ou_1", "ou_2", "ou_3"), msgs.map { it.senderId })
        assertEquals(
            listOf(1_700_000_000_000L, 1_700_000_000_300L, 1_700_000_000_600L),
            msgs.map { it.createTimeMillis },
        )
        // content 不含 "\n\n" 拼接痕迹（SDK chatQueue 合并会把多条拼成一条，此处必须各自独立）
        msgs.forEach { assertTrue("\n\n" !in it.text, "text 不得含合并拼接痕迹：${it.text}") }
        assertEquals(listOf("a", "b", "c"), msgs.map { it.text })
    }

    // ② 字段名按 NormalizedMessage 实际写 + InboundMessage 结构性无 eventId 属性（决策 15 / D12）
    @Test
    fun `InboundMessage has no eventId property by construction`() {
        val names = InboundMessage::class.java.declaredFields.map { it.name }
        assertTrue(names.none { it == "eventId" }, "InboundMessage 不得有 eventId 字段（幂等键只能用 messageId）：$names")
        // rawContentType 存在（不是 messageType），且 raw 字段刻意不进（决策 14）
        assertTrue(names.contains("rawContentType"))
        assertTrue(names.none { it == "raw" }, "决策 14：includeRawInMessage 已 deprecated，raw 不进契约")
    }

    // ③ chatType 映射：大小写敏感精确匹配
    @Test
    fun `chatTypeOf maps p2p group and tolerates case and null`() {
        assertEquals(ChatType.P2P, LarkInboundMapper.chatTypeOf("p2p"))
        assertEquals(ChatType.GROUP, LarkInboundMapper.chatTypeOf("group"))
        assertEquals(ChatType.UNKNOWN, LarkInboundMapper.chatTypeOf(null))
        assertEquals(ChatType.UNKNOWN, LarkInboundMapper.chatTypeOf("GROUP")) // 大写 → UNKNOWN（SDK 字面值实测小写）
        assertEquals(ChatType.UNKNOWN, LarkInboundMapper.chatTypeOf("P2P"))
        assertEquals(ChatType.UNKNOWN, LarkInboundMapper.chatTypeOf("something_else"))
    }

    // ④ createTimeMillis > 0（录制样本用真实飞书毫秒时间戳驱动）
    @Test
    fun `createTimeMillis is positive and taken from record`() {
        val m = LarkInboundMapper.fromMessage(record(createTime = 1_700_000_000_000L), receivedAtMillis = 1L)
        assertTrue(m.createTimeMillis > 0)
        assertEquals(1_700_000_000_000L, m.createTimeMillis)
        // fromCardAction 无 createTime getter ⇒ 用网关接收时刻（> 0）
        val ca = LarkInboundMapper.fromCardAction(
            CardActionRecord("ou_1", "button", "confirm", null, "v", "om_1", "oc_1"),
            receivedAtMillis = 1_700_000_002_000L,
        )
        assertTrue(ca.createTimeMillis > 0)
        assertEquals(1_700_000_002_000L, ca.createTimeMillis)
    }

    // ⑤ rawContentType 容忍未知值：绝不抛、绝不 valueOf（决策 15 / BC §9 P11）
    @Test
    fun `kindOf tolerates unknown future and null values without throwing`() {
        assertEquals(InboundKind.UNKNOWN, LarkInboundMapper.kindOf("some_future_lark_type"))
        assertEquals(InboundKind.UNKNOWN, LarkInboundMapper.kindOf(null))
        // 🔴 "interactive" 不是 CARD_ACTION：卡片回传走独立事件流 "cardAction"（§2.3），待 S4 真机回填
        assertEquals(InboundKind.UNKNOWN, LarkInboundMapper.kindOf("interactive"))
        assertEquals(InboundKind.UNKNOWN, LarkInboundMapper.kindOf("card"))
    }

    // ⑥ 10 个已知 rawContentType 全覆盖（§6.1.4）
    @Test
    fun `kindOf covers all known rawContentType literals`() {
        assertEquals(InboundKind.TEXT, LarkInboundMapper.kindOf("text"))
        assertEquals(InboundKind.IMAGE, LarkInboundMapper.kindOf("image"))
        assertEquals(InboundKind.FILE, LarkInboundMapper.kindOf("file"))
        assertEquals(InboundKind.AUDIO, LarkInboundMapper.kindOf("audio"))
        assertEquals(InboundKind.AUDIO, LarkInboundMapper.kindOf("media")) // audio/media 同归 AUDIO
        assertEquals(InboundKind.VIDEO, LarkInboundMapper.kindOf("video"))
        assertEquals(InboundKind.POST, LarkInboundMapper.kindOf("post"))
        assertEquals(InboundKind.MERGE_FORWARD, LarkInboundMapper.kindOf("merge_forward"))
        assertEquals(InboundKind.STICKER, LarkInboundMapper.kindOf("sticker"))
        assertEquals(InboundKind.SHARE_CHAT, LarkInboundMapper.kindOf("share_chat"))
        assertEquals(InboundKind.SHARE_USER, LarkInboundMapper.kindOf("share_user"))
    }

    // ⑦ 不二次剥离 bot mention：SDK 已剥（stripBotMentions=true 硬编码 [SF:857]），网关原样透传
    @Test
    fun `fromMessage does not re-strip bot mention`() {
        val stripped = LarkInboundMapper.fromMessage(record(content = "hello"), receivedAtMillis = 1L)
        assertEquals("hello", stripped.text)
        // 输入仍含 @_user_1 且 mentions 非空 ⇒ 输出原样（网关不得再剥）
        val withMention = LarkInboundMapper.fromMessage(
            record(
                content = "@_user_1 hello",
                mentions = listOf(NormalizedMessageRecord.MentionRecord("@_user_1", "ou_bot", "u1", "Bot")),
            ),
            receivedAtMillis = 1L,
        )
        assertEquals("@_user_1 hello", withMention.text) // 🔴 原样，未剥离
        assertEquals(1, withMention.mentions.size)
        assertEquals("ou_bot", withMention.mentions[0].openId)
    }

    // ⑧ sessionKey 复合键：chatId + (threadId ?: rootId ?: "")（D13）
    @Test
    fun `sessionKey is chatId plus threadId else rootId else empty`() {
        val withThread = LarkInboundMapper.fromMessage(record(chatId = "oc_1", threadId = "omt_9", rootId = "omr_8"), 1L)
        assertEquals("oc_1omt_9", withThread.sessionKey) // threadId 优先
        val withRoot = LarkInboundMapper.fromMessage(record(chatId = "oc_1", threadId = null, rootId = "omr_8"), 1L)
        assertEquals("oc_1omr_8", withRoot.sessionKey) // 退到 rootId
        val plain = LarkInboundMapper.fromMessage(record(chatId = "oc_1", threadId = null, rootId = null), 1L)
        assertEquals("oc_1", plain.sessionKey) // 两者皆 null ⇒ chatId + ""
    }

    // ⑨ fromCardAction 三条规则（§6.1.5）
    @Test
    fun `fromCardAction obeys the three rules`() {
        val m = LarkInboundMapper.fromCardAction(
            CardActionRecord(
                operatorId = "ou_op", actionTag = "button", actionName = "confirm",
                actionOption = "opt", actionValue = "val", messageId = "om_c1", chatId = "oc_c1",
            ),
            receivedAtMillis = 1_700_000_003_000L,
        )
        // 规则 2：kind 恒 CARD_ACTION，rawContentType 恒 null（CardActionEvent 无该 getter）
        assertEquals(InboundKind.CARD_ACTION, m.kind)
        assertNull(m.rawContentType)
        // 规则 3：chatType 恒 UNKNOWN（卡片回传事件不提供 chatType）
        assertEquals(ChatType.UNKNOWN, m.chatType)
        // cardAction != null 且字段透传
        assertNotNull(m.cardAction)
        assertEquals("ou_op", m.cardAction!!.operatorId)
        assertEquals("button", m.cardAction!!.actionTag)
        assertEquals("confirm", m.cardAction!!.actionName)
        assertEquals("opt", m.cardAction!!.actionOption)
        assertEquals("val", m.cardAction!!.actionValue)
        // 规则 1：messageId = r.messageId ?: ""（可能无 messageId，引擎去重/路由须容忍空值，待 S4 回填）
        assertEquals("om_c1", m.messageId)
        assertEquals("oc_c1", m.chatId)
        assertEquals("ou_op", m.senderId)
    }

    // ⑨补充：fromCardAction 的 messageId/chatId 为 null 时退化为空串（不抛、不 NPE）
    @Test
    fun `fromCardAction tolerates null messageId and chatId`() {
        val m = LarkInboundMapper.fromCardAction(
            CardActionRecord("ou_op", "button", null, null, null, messageId = null, chatId = null),
            receivedAtMillis = 1L,
        )
        assertEquals("", m.messageId)
        assertEquals("", m.chatId)
        assertEquals(InboundKind.CARD_ACTION, m.kind)
    }

    // ⑩补充：fromMessage 的 cardAction 恒 null；resources/mentions 逐字段透传
    @Test
    fun `fromMessage leaves cardAction null and propagates resources and mentions`() {
        val m = LarkInboundMapper.fromMessage(
            record(
                rawContentType = "file",
                resources = listOf(NormalizedMessageRecord.ResourceRecord("file", "fk_1", "a.zip", 0L)),
                mentions = listOf(NormalizedMessageRecord.MentionRecord("@_user_1", "ou_a", "u_a", "A")),
                mentionAll = true,
                mentionedBot = true,
                rootId = "omr", threadId = "omt", replyToMessageId = "om_reply",
            ),
            receivedAtMillis = 1L,
        )
        assertNull(m.cardAction) // fromMessage 恒 null
        assertEquals(InboundKind.FILE, m.kind)
        assertEquals(1, m.resources.size)
        assertEquals("fk_1", m.resources[0].fileKey)
        assertEquals("a.zip", m.resources[0].fileName)
        assertEquals(0L, m.resources[0].durationMs)
        assertEquals(1, m.mentions.size)
        assertEquals("ou_a", m.mentions[0].openId)
        assertTrue(m.mentionAll)
        assertTrue(m.mentionedBot)
        assertEquals("omr", m.rootId)
        assertEquals("omt", m.threadId)
        assertEquals("om_reply", m.replyToMessageId)
        assertEquals("Alice", m.senderName)
        // senderName null ⇒ ""（SDK 只从事件本身取，不额外发 HTTP [SF:855]）
        assertEquals("", LarkInboundMapper.fromMessage(record(senderName = null), 1L).senderName)
        // content null ⇒ ""
        assertEquals("", LarkInboundMapper.fromMessage(record(content = null), 1L).text)
        assertNotEquals("", m.messageId)
    }
}
