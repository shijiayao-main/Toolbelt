// :lark-gateway —— WsInboundTranslator 单测（接线批 2026-09-12）。
// 断言口径 = SDK TestNormalizeConverters 期望值 + MessageNormalizer/Mentions/NormalizeTexts 规则表
// （调查报告 ws-inv-sdk-message-normalize.md；逐条带源文件行号）。
// 🔴 全部用例断言「与 SDK 逐字节对齐」的形态（含 @_user_1 前缀吃 @_user_10 边界缺陷——刻意不修）。
package com.jiaoay.lark.gateway

import com.jiaoay.lark.ws.WsInboundEvent
import com.jiaoay.lark.ws.WsMention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WsInboundTranslatorTest {

    private fun translator(botOpenId: String? = null) =
        WsInboundTranslator(botOpenIdProvider = { botOpenId })

    private fun messageEvent(
        content: String,
        messageType: String = "text",
        mentions: List<WsMention> = emptyList(),
        chatType: String = "p2p",
        parentId: String? = null,
        rootId: String? = null,
        createTime: String = "1700000000000",
    ) = WsInboundEvent.MessageReceived(
        eventType = "im.message.receive_v1",
        messageId = "om_1",
        chatId = "oc_1",
        chatType = chatType,
        messageType = messageType,
        content = content,
        createTime = createTime,
        senderId = "ou_sender",
        senderType = "user",
        mentions = mentions,
        rootId = rootId,
        parentId = parentId,
    )

    // ==================== content 转换（converters 子集）====================

    @Test
    fun `text 消息取 text 键原文`() {
        val r = translator().translate(messageEvent("""{"text":"hello world"}"""))
        assertEquals("hello world", r.content)
        assertTrue(r.resources.isEmpty())
    }

    @Test
    fun `file 消息产 file 标签与 resource（name 转义双引号）`() {
        val r = translator().translate(messageEvent("""{"file_key":"fk_1","file_name":"a\"b.log"}""", messageType = "file"))
        assertEquals("""<file key="fk_1" name="a&quot;b.log"/>""", r.content)
        assertEquals(1, r.resources.size)
        assertEquals("file", r.resources[0].type)
        assertEquals("fk_1", r.resources[0].fileKey)
        assertEquals("a\"b.log", r.resources[0].fileName)
    }

    @Test
    fun `file 缺 file_key 产占位文本无 resource`() {
        val r = translator().translate(messageEvent("""{"file_name":"x.log"}""", messageType = "file"))
        assertEquals("[file]", r.content)
        assertTrue(r.resources.isEmpty())
    }

    @Test
    fun `file 缺 name 省属性`() {
        val r = translator().translate(messageEvent("""{"file_key":"fk_2"}""", messageType = "file"))
        assertEquals("""<file key="fk_2"/>""", r.content)
        assertNull(r.resources[0].fileName)
    }

    @Test
    fun `image 消息产 markdown 形态`() {
        val r = translator().translate(messageEvent("""{"image_key":"img_1"}""", messageType = "image"))
        assertEquals("![image](img_1)", r.content)
        assertEquals("image", r.resources[0].type)
        assertEquals("img_1", r.resources[0].fileKey)
    }

    @Test
    fun `audio 整秒 duration 产 Ns 形态`() {
        val r = translator().translate(messageEvent("""{"file_key":"au_1","duration":1500}""", messageType = "audio"))
        assertEquals("""<audio key="au_1" duration="1.5s"/>""", r.content)
        assertEquals(1500L, r.resources[0].durationMs)
    }

    @Test
    fun `audio 整千毫秒产整秒形态`() {
        val r = translator().translate(messageEvent("""{"file_key":"au_2","duration":2000}""", messageType = "audio"))
        assertEquals("""<audio key="au_2" duration="2s"/>""", r.content)
    }

    @Test
    fun `audio 千毫秒以内原样 ms`() {
        val r = translator().translate(messageEvent("""{"file_key":"au_3","duration":800}""", messageType = "audio"))
        assertEquals("""<audio key="au_3" duration="800ms"/>""", r.content)
    }

    @Test
    fun `audio 缺 duration 省属性`() {
        val r = translator().translate(messageEvent("""{"file_key":"au_4"}""", messageType = "audio"))
        assertEquals("""<audio key="au_4"/>""", r.content)
        assertEquals(0L, r.resources[0].durationMs)
    }

    @Test
    fun `video 带 name 与 duration（media 类型同 converter）`() {
        val content = """{"file_key":"v_1","file_name":"clip.mp4","duration":3200,"image_key":"cover"}"""
        val video = translator().translate(messageEvent(content, messageType = "video"))
        assertEquals("""<video key="v_1" name="clip.mp4" duration="3.2s"/>""", video.content)
        assertEquals("video", video.resources[0].type)
        // 🔴 media 类型的 resource type 也是 "video"（ConverterRegistry.java:36）
        val media = translator().translate(messageEvent(content, messageType = "media"))
        assertEquals("video", media.resources[0].type)
        assertEquals("""<video key="v_1" name="clip.mp4" duration="3.2s"/>""", media.content)
    }

    @Test
    fun `未知类型走 fallback 读 text 键`() {
        val r = translator().translate(messageEvent("""{"text":"from post"}""", messageType = "post"))
        assertEquals("from post", r.content)
        assertTrue(r.resources.isEmpty())
    }

    @Test
    fun `fallback 无 text 键产占位（converter 异常吞掉同语义）`() {
        val r = translator().translate(messageEvent("""{"foo":1}""", messageType = "interactive"))
        assertEquals("[unsupported message]", r.content)
    }

    @Test
    fun `content 非合法 JSON 走 fallback 不抛`() {
        val r = translator().translate(messageEvent("not-json", messageType = "text"))
        // text converter 取不到 text 键 → ""，但 normalizeSpaces 后空串；转换器异常吞掉走 fallback 读 text 键也失败
        assertTrue(r.content == "" || r.content == "[unsupported message]")
    }

    // ==================== mention 解析（Mentions.java 复刻）====================

    @Test
    fun `非 bot mention 占位符替换为可读名字`() {
        val ev = messageEvent(
            content = """{"text":"@_user_1 你好"}""",
            mentions = listOf(WsMention(key = "@_user_1", openId = "ou_u1", name = "张三")),
        )
        val r = translator().translate(ev)
        assertEquals("@张三 你好", r.content)
        assertFalse(r.mentionedBot)
    }

    @Test
    fun `bot mention 剥除两种形态（key 与 @+name 两形态）`() {
        val ev = messageEvent(
            content = """{"text":"@_user_1 @BotName 帮我看看"}""",
            mentions = listOf(WsMention(key = "@_user_1", openId = "ou_bot", userId = "u_bot", name = "BotName")),
        )
        val r = translator("ou_bot").translate(ev)
        // bot 剥除：key @_user_1 与 "@BotName" 都被移除，双空格折叠
        assertEquals("帮我看看", r.content)
        assertTrue(r.mentionedBot)
        // userId 直通防线（MentionInfo 五元组取 getId().getUserId()——Mentions.java:36,38）
        assertEquals("u_bot", r.mentions[0].userId)
    }

    @Test
    fun `mention 名字缺失回退 openId 再回退 key`() {
        val ev = messageEvent(
            content = """{"text":"@_user_2 hi"}""",
            mentions = listOf(WsMention(key = "@_user_2", openId = "ou_u2", name = null)),
        )
        val r = translator().translate(ev)
        assertEquals("@ou_u2 hi", r.content)
    }

    @Test
    fun `mentionAll 由 mentions 列表驱动且不入列表`() {
        val ev = messageEvent(
            content = """{"text":"@_all 通知"}""",
            mentions = listOf(WsMention(key = "@_all", openId = "all", name = null)),
        )
        val r = translator().translate(ev)
        assertTrue(r.mentionAll)
        assertTrue(r.mentions.isEmpty(), "@_all 不入 mention 列表（Mentions.java:31-34 continue）")
    }

    @Test
    fun `mentionAll 也由原始 content 正则驱动（mentions 缺席时）`() {
        val ev = messageEvent(content = """{"text":"call @_all now"}""")
        val r = translator().translate(ev)
        assertTrue(r.mentionAll, "`@_all\\b` 扫原始 content 字符串（Mentions.java:14-15）")
    }

    @Test
    fun `mentionedBot 判定只比 openId 字符串`() {
        val ev = messageEvent(
            content = """{"text":"hi"}""",
            mentions = listOf(WsMention(key = "@_user_1", openId = "ou_bot", name = null)),
        )
        assertTrue(translator("ou_bot").translate(ev).mentionedBot)
        assertFalse(translator("ou_other").translate(ev).mentionedBot)
        assertFalse(translator(null).translate(ev).mentionedBot, "botOpenId 缺席恒 false（:37 botOpenId != null 前置）")
    }

    /**
     * 🔴 2026-09-13 群@门控误拒事故核心回归线：v2 事件 mentions[].id 是 UserId 对象（parser 归一化
     *    出 openId/userId），bot 判定必须命中 openId 而非 userId——修复前该形态在解析层就被
     *    整列表吞掉（mentionedBot 恒 false ⇒ group_require_mention 误拒），修复后整链可判定。
     */
    @Test
    fun `对象形态 mention 的 openId 命中 bot 判定`() {
        val ev = messageEvent(
            content = """{"text":"@_user_1 hi"}""",
            mentions = listOf(WsMention(key = "@_user_1", openId = "ou_bot", userId = "u_bot", name = "Bot")),
        )
        val r = translator("ou_bot").translate(ev)
        assertTrue(r.mentionedBot, "bot 判定必须比 openId（Mentions.java:37），不得误比 userId")
        assertEquals("u_bot", r.mentions[0].userId, "对象形态 userId 直通 MentionRecord")
    }

    @Test
    fun `mention 占位符前缀缺陷与 SDK 对齐（@_user_1 吃 @_user_10）`() {
        // 🔴 String.replace 字面子串替换的边界缺陷照抄不修（Mentions.java:129）——
        //    @_user_1 的替换会吃掉 @_user_10 的前缀（SDK 同款行为）
        val ev = messageEvent(
            content = """{"text":"@_user_1 和 @_user_10 你们"}""",
            mentions = listOf(
                WsMention(key = "@_user_1", openId = "ou_1", name = "甲"),
                WsMention(key = "@_user_10", openId = "ou_10", name = "乙"),
            ),
        )
        val r = translator().translate(ev)
        // SDK 行为：@_user_1 → "@甲"（顺带把 @_user_10 的前缀吃成 "@甲0"），@_user_10 再替换时已无占位符
        assertEquals("@甲 和 @甲0 你们", r.content)
    }

    // ==================== 空格归一与字段派生（NormalizeTexts）====================

    @Test
    fun `normalizeSpaces NBSP 替换 trim 双空格折叠`() {
        val r = translator().translate(messageEvent("""{"text":"  a  b  c "}"""))
        assertEquals("a b c", r.content)
    }

    @Test
    fun `replyToMessageId 取 parent_id 非 root_id（对照 MessageNormalizer 57 行）`() {
        val r = translator().translate(
            messageEvent("""{"text":"hi"}""", parentId = "om_parent", rootId = "om_root"),
        )
        assertEquals("om_parent", r.replyToMessageId)
        assertEquals("om_root", r.rootId)
    }

    @Test
    fun `createTime 非法归零`() {
        val r = translator().translate(messageEvent("""{"text":"x"}""", createTime = "not-a-number"))
        assertEquals(0L, r.createTime)
    }

    @Test
    fun `senderName 生产恒 null（名字由桥侧补拉）`() {
        val r = translator().translate(messageEvent("""{"text":"x"}"""))
        assertNull(r.senderName)
    }

    // ==================== cardAction（CardActionNormalizer 复刻）====================

    @Test
    fun `cardAction 七字段提取与 actionValue Map toString 形态`() {
        val payload = """
            {"event":{"action":{"tag":"button","name":"btn","option":"","value":{"token":"t1","decision":"allow"}},
            "context":{"open_message_id":"om_c","open_chat_id":"oc_c"},
            "operator":{"open_id":"ou_op","user_id":"","union_id":""}}}
        """.trimIndent()
        val r = translator().translateCardAction(payload)!!
        assertEquals("om_c", r.messageId)
        assertEquals("oc_c", r.chatId)
        assertEquals("ou_op", r.operatorId)
        assertEquals("button", r.actionTag)
        assertEquals("btn", r.actionName)
        assertEquals("", r.actionOption, "空白 option 保留原样（contentOrNull 非空白过滤）")
        // Map.toString 形态对齐 SDK 的 Gson 路径：字符串值无引号（mapToString 剥引号——
        // kotlinx JsonElement.toString 会带引号，SDK 的 Map<String,Any>.toString 不会）
        assertEquals("{token=t1, decision=allow}", r.actionValue)
    }

    @Test
    fun `cardAction 闸门 messageId 缺席丢弃`() {
        val payload = """{"event":{"action":{"tag":"button"},"context":{"open_chat_id":"oc_c"},"operator":{"open_id":"ou_op"}}}"""
        assertNull(translator().translateCardAction(payload))
    }

    @Test
    fun `cardAction 闸门 operator 三级回退 user_id`() {
        val payload = """
            {"event":{"action":{"tag":"button"},"context":{"open_message_id":"om_c","open_chat_id":"oc_c"},
            "operator":{"open_id":"","user_id":"u_1","union_id":"un_1"}}}
        """.trimIndent()
        val r = translator().translateCardAction(payload)!!
        assertEquals("u_1", r.operatorId, "firstNonBlank(open_id, user_id, union_id) 回退链")
    }

    @Test
    fun `cardAction 空 tag 兜底 unknown`() {
        val payload = """
            {"event":{"action":{"value":{"k":"v"}},"context":{"open_message_id":"om_c","open_chat_id":"oc_c"},
            "operator":{"open_id":"ou_op"}}}
        """.trimIndent()
        val r = translator().translateCardAction(payload)!!
        assertEquals("unknown", r.actionTag)
    }

    @Test
    fun `cardAction 非 JSON payload 静默丢弃不抛`() {
        assertNull(translator().translateCardAction("not-json"))
    }

    @Test
    fun `cardAction 容忍扁平无 envelope 旧形态`() {
        val payload = """{"action":{"tag":"button"},"context":{"open_message_id":"om_f","open_chat_id":"oc_f"},"operator":{"open_id":"ou_of"}}"""
        val r = translator().translateCardAction(payload)!!
        assertEquals("om_f", r.messageId)
    }
}
