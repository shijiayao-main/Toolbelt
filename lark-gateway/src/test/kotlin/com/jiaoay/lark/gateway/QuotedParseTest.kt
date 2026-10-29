// parseQuotedContent 三分支纯函数单测（2026-09-11 晚泛化：引用即上下文）。
// 不 mock SDK 响应对象——parseQuoted* 是顶层 internal 纯函数，直接喂字符串。
package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotedParseTest {

    // ── file 分支（S4 真机实测形态） ──

    @Test
    fun `file content-解析 key 与 name`() {
        val q = parseQuotedContent("om_1", "oc_1", "ou_1", "file", "<file key=\"file_v3_abc\" name=\"crash.zip\"/>")
        assertTrue(q is QuotedMessage.File, "file 消息应产出 File 子类：$q")
        q as QuotedMessage.File
        assertEquals("file_v3_abc", q.fileKey)
        assertEquals("crash.zip", q.fileName)
        assertEquals("oc_1", q.chatId)
        assertEquals("ou_1", q.senderId)
    }

    @Test
    fun `file content-name 空白兜底为 file`() {
        val q = parseQuotedContent("om_1", "oc_1", "ou_1", "file", "<file key=\"fk_x\" name=\"\"/>")
        assertEquals("file", (q as QuotedMessage.File).fileName)
    }

    @Test
    fun `file content-key 缺失返回 null`() {
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "file", "<file name=\"crash.zip\"/>"))
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "file", "随便什么非 XML 文本"))
    }

    // ── file 分支·形态②（2026-09-12 真机取证修复）：message.get 原始 JSON ──
    // 🔴 背景：入站事件经 SDK FileMessageConverter 转成 <file key=…/>（形态①）；
    //    但补查 im.v1.message.get 返回的 body.content 是原始 JSON {"file_key","file_name"}。
    //    修复前 file 分支只有 XML 正则 ⇒ 补查路径的文件消息永远静默解析失败
    //    （真机 22:02：重启后引用 20:54 的文件消息，重启前命中、重启后补查救不回）。

    @Test
    fun `file content-形态2-原始 JSON 的 file_key 与 file_name 解析`() {
        val q = parseQuotedContent(
            "om_1", "oc_1", "ou_1", "file",
            "{\"file_key\":\"file_v3_0015f_30c2f397-580a-42a2-9612-22fb02dbdbag\",\"file_name\":\"20260902_112801.zip\"}",
        )
        assertTrue(q is QuotedMessage.File, "message.get 原始 JSON 形态必须可解析（修复前此用例必红）：$q")
        q as QuotedMessage.File
        assertEquals("file_v3_0015f_30c2f397-580a-42a2-9612-22fb02dbdbag", q.fileKey)
        assertEquals("20260902_112801.zip", q.fileName)
    }

    @Test
    fun `file content-形态2-file_name 缺失或空白兜底为 file`() {
        assertEquals(
            "file",
            (parseQuotedContent("om_1", "oc_1", "ou_1", "file", "{\"file_key\":\"fk_x\"}") as QuotedMessage.File).fileName,
        )
    }

    @Test
    fun `file content-两种形态都不中返回 null`() {
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "file", "{\"nope\":1}"))
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "file", "{\"file_name\":\"only-name.zip\"}"))
    }

    // ── text 分支（飞书文档实证：JSON 字符串 {"text":"…"}) ──

    @Test
    fun `text content-解析 text 原文`() {
        val q = parseQuotedContent("om_1", "oc_1", "ou_1", "text", "{\"text\":\"hello world\"}")
        assertTrue(q is QuotedMessage.Text, "text 消息应产出 Text 子类：$q")
        assertEquals("hello world", (q as QuotedMessage.Text).text)
    }

    @Test
    fun `text content-转义与换行还原`() {
        val q = parseQuotedContent(
            "om_1", "oc_1", "ou_1", "text",
            "{\"text\":\"第一行\\n第二行 \\\"引号\\\" 与\\\\\"}",
        )
        // Gson 还原转义——免手写 unescape 的正是此处价值
        assertEquals("第一行\n第二行 \"引号\" 与\\", (q as QuotedMessage.Text).text)
    }

    @Test
    fun `text content-blank 与缺失 text 返回 null`() {
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "text", "{\"text\":\"\"}"))
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "text", "{\"text\":\"   \"}"))
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "text", "{}"))
    }

    @Test
    fun `text content-非 JSON 返回 null 不抛`() {
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "text", "裸文本不是 JSON"))
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "text", "{\"text\":123}"))
    }

    // ── interactive（卡片）分支（2026-09-11 晚真机补：引用机器人回复） ──

    @Test
    fun `interactive 完整卡-提取 markdown 正文`() {
        // 自家出站卡同构（LarkOutboundMapper.toCardJson）：body.elements[].tag=="markdown"
        val card = """
            {"schema":"2.0","config":{},"header":{"title":{"tag":"plain_text","content":"分析完成"}},
             "body":{"elements":[{"tag":"markdown","element_id":"e1","content":"排查结论：崩溃源于空指针"},
                                   {"tag":"button","text":{"tag":"plain_text","content":"允许"}}]}}
        """.trimIndent()
        val q = parseQuotedContent("om_1", "oc_1", "ou_1", "interactive", card)
        assertEquals("排查结论：崩溃源于空指针", (q as QuotedMessage.Text).text)
    }

    @Test
    fun `interactive 完整卡-多 markdown 元素按序拼接`() {
        val card = """
            {"schema":"2.0","body":{"elements":[
              {"tag":"markdown","content":"第一段"},
              {"tag":"button","content":"忽略我"},
              {"tag":"markdown","content":"第二段"}]}}
        """.trimIndent()
        val q = parseQuotedContent("om_1", "oc_1", "ou_1", "interactive", card)
        assertEquals("第一段\n\n第二段", (q as QuotedMessage.Text).text)
    }

    @Test
    fun `interactive CardKit 实体引用-取不到正文返回 null`() {
        // 流式卡的 content 是 {"type":"card","data":{"card_id":…}}——正文在卡片实体里，message.get 拿不到
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "interactive",
            """{"type":"card","data":{"card_id":"7100xxx"}}"""))
    }

    @Test
    fun `interactive-无 markdown 元素或非法 JSON 返回 null`() {
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "interactive", "不是 JSON"))
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "interactive",
            """{"schema":"2.0","body":{"elements":[{"tag":"button"}]}}"""))
    }

    // ── 不支持类型分支 ──

    @Test
    fun `不支持的消息类型返回 null`() {
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "image", "{\"image_key\":\"img_v2_x\"}"))
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "sticker", "{}"))
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "", "空 msgType"))
    }

    // ── post 富文本（2026-09-15 真机补：引用机器人回复此前恒 miss） ──

    @Test
    fun `post 富文本提取 title 与 text 段落`() {
        // 机器人 markdown 出站经 SDK sendMarkdown 落成 post——真机 2026-09-15 18:07 引用机器人回复
        // 在此 miss 落「本地未取回」hint。修复后取回正文作引用上下文。
        val content = """
            {"title":"回执标题","content":[
              [{"tag":"text","text":"第一段第一句"},{"tag":"text","text":"第一段第二句"}],
              [{"tag":"text","text":"第二段"}]
            ]}
        """.trimIndent()
        val parsed = parseQuotedContent("om_1", "oc_1", "ou_1", "post", content)
        assertTrue(parsed is QuotedMessage.Text, "post 应解析为 Text 上下文：$parsed")
        parsed as QuotedMessage.Text
        assertEquals("om_1", parsed.messageId)
        assertEquals("oc_1", parsed.chatId)
        assertEquals(
            "回执标题\n第一段第一句 第一段第二句\n第二段",
            parsed.text,
            "title 作首行、段落内空格连接、段落间换行",
        )
    }

    @Test
    fun `post 无 title 只有段落时正文仅由段落组成`() {
        val content = """{"content":[[{"tag":"text","text":"仅一段"}]]}"""
        val parsed = parseQuotedContent("om_1", "oc_1", "ou_1", "post", content)
        assertEquals("仅一段", (parsed as QuotedMessage.Text).text)
    }

    @Test
    fun `post 空 content 或非法 JSON 返回 null（不猜）`() {
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "post", "{\"content\":[]}"), "空段落数组 ⇒ 正文为空 ⇒ null")
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "post", "not-json"), "非法 JSON ⇒ null")
        assertNull(parseQuotedContent("om_1", "oc_1", "ou_1", "post", """{"content":[[{"tag":"image","image_key":"x"}]]}"""), "无 text 元素 ⇒ 正文为空 ⇒ null")
        // title 单独存在时是有效正文（飞书富文本标题即内容的一部分）⇒ 非 null
        val titleOnly = parseQuotedContent("om_1", "oc_1", "ou_1", "post", "{\"title\":\"富文本\"}")
        assertTrue(titleOnly is QuotedMessage.Text && titleOnly.text == "富文本", "仅 title 也是可引用上下文")
    }
}
