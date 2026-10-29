// 能力一级化批 A（2026-09-27）：parseHistoryTextBrief 纯函数直测——listMessages 的正文摘要数据源。
// 不 mock SDK 响应对象——顶层 internal 纯函数，直接喂字符串（与 QuotedParseTest 同款姿态）。
package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTextBriefTest {

    // ── text 型 ──

    @Test
    fun `text 型取 text 字段`() {
        assertEquals("hello world", parseHistoryTextBrief("text", """{"text":"hello world"}"""))
    }

    @Test
    fun `text 型空白正文落不支持形态`() {
        assertEquals("[不支持类型 text]", parseHistoryTextBrief("text", """{"text":""}"""))
        assertEquals("[不支持类型 text]", parseHistoryTextBrief("text", """{"text":"   "}"""))
        assertEquals("[不支持类型 text]", parseHistoryTextBrief("text", "{}"))
    }

    // ── post 型 ──

    @Test
    fun `post 型取嵌套文本段（title 与段落，段落间换行，链接不混入）`() {
        val content = """
            {"title":"标题","content":[[{"tag":"text","text":"第一段"},{"tag":"a","text":"链接不算"}],
            [{"tag":"text","text":"第二段"}]]}
        """.trimIndent()
        assertEquals("标题\n第一段\n第二段", parseHistoryTextBrief("post", content))
    }

    @Test
    fun `post 型无 title 直接段落`() {
        val content = """{"content":[[{"tag":"text","text":"只有正文"}]]}"""
        assertEquals("只有正文", parseHistoryTextBrief("post", content))
    }

    @Test
    fun `post 型空段落落不支持形态`() {
        assertEquals("[不支持类型 post]", parseHistoryTextBrief("post", """{"content":[]}"""))
    }

    // ── file / image / media 型 ──

    @Test
    fun `file 型取 file_name 包文件标记`() {
        assertEquals("[文件 crash.zip]", parseHistoryTextBrief("file", """{"file_key":"fk_x","file_name":"crash.zip"}"""))
        assertEquals("[文件 shot.png]", parseHistoryTextBrief("image", """{"image_key":"img_x","file_name":"shot.png"}"""))
        assertEquals("[文件 clip.mp4]", parseHistoryTextBrief("media", """{"file_name":"clip.mp4"}"""))
    }

    @Test
    fun `file 型缺 file_name 落不支持形态`() {
        assertEquals("[不支持类型 file]", parseHistoryTextBrief("file", """{"file_key":"fk_x"}"""))
    }

    // ── 不支持类型 / 坏 JSON ──

    @Test
    fun `不支持类型落方括号标记`() {
        assertEquals("[不支持类型 sticker]", parseHistoryTextBrief("sticker", """{"whatever":1}"""))
        assertEquals("[不支持类型 interactive]", parseHistoryTextBrief("interactive", """{"type":"card"}"""))
    }

    @Test
    fun `msgType 空白落空消息标记`() {
        assertEquals("[空消息]", parseHistoryTextBrief("", "{}"))
        assertEquals("[空消息]", parseHistoryTextBrief("   ", "随便"))
    }

    @Test
    fun `坏 JSON 不炸落兜底`() {
        assertEquals("[不支持类型 text]", parseHistoryTextBrief("text", "裸文本不是 JSON"))
        assertEquals("[不支持类型 text]", parseHistoryTextBrief("text", """{"text":123}"""))
        assertEquals("[不支持类型 post]", parseHistoryTextBrief("post", "{broken"))
        assertEquals("[不支持类型 file]", parseHistoryTextBrief("file", ""))
    }
}
