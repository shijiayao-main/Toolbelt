// :lark-gateway 测试（M3-T3，§11.3）。类名按裁定 5（B-16）用 Lark*MapperTest。
// 🔴 纯函数测试：零 SDK 加载。§15.2 M-2：contentJsonOf 走 LarkOutboundMapper 自带的纯 Kotlin JSON 编码器，
//    故转义往返可脱 SDK/脱 Gson 单测。
package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §11.3：Outbound / CardSpec → 卡片 JSON（Map）+ content JSON（字符串）的出站映射。
 * 字面值全部锁死自 [BC:1026-1032]，一个都不能改（§6.2.2）。
 */
class LarkOutboundMapperTest {

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.sub(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

    // ① toCardJson 的键全部存在且值精确；🔴 S6 真机裁定：config **不含** streaming_config（首版不启用打字机动画）
    @Test
    fun `toCardJson produces schema config header body and no streaming_config`() {
        val json = LarkOutboundMapper.toCardJson(
            CardSpec(title = "标题", titleColor = CardTitleColor.BLUE, markdown = "正文", streaming = true),
        )
        assertEquals("2.0", json["schema"])
        val config = json.sub("config")
        assertEquals(true, config["streaming_mode"])   // == spec.streaming
        assertEquals(true, config["update_multi"])     // 🔴 多次 patch 同一元素的前提，必设
        // 🔴 S6 真机（10002 failed to unmarshal PrintStepConfig / 11311 printStep is nil）：首版不启用 streaming_config。防回归网。
        assertFalse(config.containsKey("streaming_config"), "config 不得含 streaming_config（真机 11311/10002，§16）")
        assertEquals(setOf("streaming_mode", "update_multi", "summary"), config.keys)
        assertTrue((config.sub("summary")["content"] as String).isNotEmpty())
        val header = json.sub("header")
        assertEquals("blue", header["template"])       // == spec.titleColor.template
        assertEquals("plain_text", header.sub("title")["tag"])
        assertEquals("标题", header.sub("title")["content"])
        val elements = json.sub("body")["elements"] as List<Map<String, Any?>>
        assertEquals("markdown", elements[0]["tag"])
        assertEquals("stream_md", elements[0]["element_id"])
        assertEquals("正文", elements[0]["content"])
    }

    // ①补充：streaming_mode 跟随 spec.streaming（false 分支）
    @Test
    fun `toCardJson streaming_mode follows spec`() {
        val nonStreaming = LarkOutboundMapper.toCardJson(CardSpec(title = "t", markdown = "m", streaming = false))
        assertEquals(false, nonStreaming.sub("config")["streaming_mode"])
    }

    // ② summary 缺省时取 markdown.take(80)
    @Test
    fun `summary defaults to first eighty chars of markdown`() {
        val md = "x".repeat(200)
        val json = LarkOutboundMapper.toCardJson(CardSpec(title = "t", markdown = md, summary = null))
        assertEquals(80, (json.sub("config").sub("summary")["content"] as String).length)
        // 显式 summary 优先
        val json2 = LarkOutboundMapper.toCardJson(CardSpec(title = "t", markdown = md, summary = "自定义"))
        assertEquals("自定义", json2.sub("config").sub("summary")["content"])
    }

    // ③ button 走 behaviors 数组、无 action 键（递归遍历整个 Map）—— 否则 230099
    @Test
    fun `button uses behaviors array and there is no action key anywhere`() {
        val json = LarkOutboundMapper.toCardJson(
            CardSpec(
                title = "确认", markdown = "m",
                buttons = listOf(CardButtonSpec("同意", "approve", primary = true)),
            ),
        )
        val elements = json.sub("body")["elements"] as List<Map<String, Any?>>
        val btn = elements[1]
        assertEquals("button", btn["tag"])
        val behaviors = btn["behaviors"] as List<Map<String, Any?>>
        assertEquals("callback", behaviors[0]["type"])
        assertEquals("approve", (behaviors[0]["value"] as Map<String, Any?>)["key"])
        // 🔴 递归遍历整个 Map/List 结构，任何层级都不得出现 "action" 键
        assertFalse(containsKeyRecursive(json, "action"), "卡片 JSON 任何层级不得含 action 键（飞书 230099）")
    }

    private fun containsKeyRecursive(node: Any?, key: String): Boolean = when (node) {
        is Map<*, *> -> node.keys.any { it == key } || node.values.any { containsKeyRecursive(it, key) }
        is Iterable<*> -> node.any { containsKeyRecursive(it, key) }
        else -> false
    }

    // ④ primary 映射：true → "primary"，false → "default"
    @Test
    fun `button primary maps to type primary or default`() {
        val primary = LarkOutboundMapper.toCardJson(
            CardSpec(title = "t", markdown = "m", buttons = listOf(CardButtonSpec("a", "va", primary = true))),
        )
        val default = LarkOutboundMapper.toCardJson(
            CardSpec(title = "t", markdown = "m", buttons = listOf(CardButtonSpec("b", "vb", primary = false))),
        )
        assertEquals("primary", (primary.sub("body")["elements"] as List<Map<String, Any?>>)[1]["type"])
        assertEquals("default", (default.sub("body")["elements"] as List<Map<String, Any?>>)[1]["type"])
        // button text 走 plain_text 包裹
        assertEquals("plain_text", ((primary.sub("body")["elements"] as List<Map<String, Any?>>)[1].sub("text"))["tag"])
    }

    // ⑤ 流式卡 + button ⇒ 抛 IllegalArgumentException（决策 22 / 200810）—— mapper 的唯一允许抛点
    @Test
    fun `streaming card with button is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LarkOutboundMapper.toCardJson(
                CardSpec(title = "t", markdown = "m", streaming = true, buttons = listOf(CardButtonSpec("ok", "ok"))),
            )
        }
        // 非流式卡 + button 合法
        LarkOutboundMapper.toCardJson(CardSpec(title = "t", markdown = "m", streaming = false, buttons = listOf(CardButtonSpec("ok", "ok"))))
        // 流式卡无 button 合法
        LarkOutboundMapper.toCardJson(CardSpec(title = "t", markdown = "m", streaming = true))
    }

    // ⑥ msgTypeOf / contentJsonOf
    @Test
    fun `msgTypeOf and contentJsonOf produce the right literals`() {
        assertEquals("text", LarkOutboundMapper.msgTypeOf(OutboundKind.TEXT))
        assertEquals("interactive", LarkOutboundMapper.msgTypeOf(OutboundKind.MARKDOWN))
        assertEquals("interactive", LarkOutboundMapper.msgTypeOf(OutboundKind.CARD))
        // TEXT → {"text":...}
        assertEquals("""{"text":"hi"}""", LarkOutboundMapper.contentJsonOf(Outbound.text("hi")))
        // MARKDOWN → 含 "schema":"2.0"
        val md = LarkOutboundMapper.contentJsonOf(Outbound.markdown("**bold**"))
        assertTrue("\"schema\":\"2.0\"" in md, md)
        // CARD → 含 "schema":"2.0" 且卡片内容进入
        val card = LarkOutboundMapper.contentJsonOf(Outbound.card(CardSpec(title = "T", markdown = "M")))
        assertTrue("\"schema\":\"2.0\"" in card, card)
        assertTrue("\"content\":\"M\"" in card, card)
    }

    // ⑦ toPartialElementJson 只含 {element_id, content}，🔴 **绝不含 tag**（S6 真机 300312 tag cannot be updated，P1/P4 全灭；P2/P3 code=0）
    @Test
    fun `toPartialElementJson is P2 form without tag`() {
        val p = LarkOutboundMapper.toPartialElementJson("增量文本")
        // 🔴 防回归网（300312）：显式断言不含 tag 键
        assertFalse(p.containsKey("tag"), "partial_element 绝不能含 tag 键（真机 300312 tag cannot be updated，§16）")
        assertNull(p["tag"])
        assertEquals("stream_md", p["element_id"])
        assertEquals("增量文本", p["content"])
        assertNull(p["header"])
        assertNull(p["config"])
        assertNull(p["schema"])
        // 🔴 键集合精确等于 {element_id, content}（P2 形态）
        assertEquals(setOf("element_id", "content"), p.keys)
        assertEquals(2, p.size)
        // 自定义 elementId
        val p2 = LarkOutboundMapper.toPartialElementJson("x", elementId = "custom_id")
        assertEquals("custom_id", p2["element_id"])
        assertFalse(p2.containsKey("tag"))
    }

    // ⑧ toFinishSettingsJson：streaming_mode == false 且不含 elements
    @Test
    fun `toFinishSettingsJson disables streaming and has no elements`() {
        val s = LarkOutboundMapper.toFinishSettingsJson()
        val config = s.sub("config")
        assertEquals(false, config["streaming_mode"])
        assertNull(s["elements"])
        assertNull(config["elements"])
        assertNull(config["summary"]) // finalText == null 时不补 summary
        // finalText 非空 ⇒ 补 summary.content（取前 80 字）
        val s2 = LarkOutboundMapper.toFinishSettingsJson("y".repeat(200))
        assertEquals(false, s2.sub("config")["streaming_mode"])
        assertEquals(80, (s2.sub("config").sub("summary")["content"] as String).length)
    }

    // ⑧补充：toTerminalMarkdown 空 markdown 回退 fallback，非空忠实取
    @Test
    fun `toTerminalMarkdown falls back when blank`() {
        assertEquals("正文", LarkOutboundMapper.toTerminalMarkdown(CardSpec(title = "t", markdown = "正文"), "fallback"))
        assertEquals("fallback", LarkOutboundMapper.toTerminalMarkdown(CardSpec(title = "t", markdown = ""), "fallback"))
        assertEquals("fallback", LarkOutboundMapper.toTerminalMarkdown(CardSpec(title = "t", markdown = "   "), "fallback"))
    }

    // ⑨ toCardRefContent 三层结构精确
    @Test
    fun `toCardRefContent is the exact three level structure`() {
        val ref = LarkOutboundMapper.toCardRefContent("card_abc")
        assertEquals("card", ref["type"])
        val data = ref.sub("data")
        assertEquals("card_abc", data["card_id"])
        assertEquals(2, ref.size)
        assertEquals(1, data.size)
    }

    // ⑩ 转义往返：特殊字符（" / \ / \n / emoji）编码正确、往返一致
    @Test
    fun `encodeJson escapes special chars and round trips`() {
        assertEquals("""{"text":"a\"b"}""", LarkOutboundMapper.encodeJson(mapOf("text" to "a\"b")))
        assertEquals("""{"text":"a\\b"}""", LarkOutboundMapper.encodeJson(mapOf("text" to "a\\b")))
        assertEquals("""{"text":"a\nb"}""", LarkOutboundMapper.encodeJson(mapOf("text" to "a\nb")))
        assertEquals("""{"text":"a\tb"}""", LarkOutboundMapper.encodeJson(mapOf("text" to "a\tb")))
        // emoji（code >= 0x20）原样透传，不 \u 转义
        assertEquals("""{"text":"你好🎉"}""", LarkOutboundMapper.encodeJson(mapOf("text" to "你好🎉")))
        // 控制字符（< 0x20，非具名）走 \u00XX
        assertEquals("""{"text":"a\u0001b"}""", LarkOutboundMapper.encodeJson(mapOf("text" to "a\u0001b")))
        // null / boolean / number
        assertEquals("null", LarkOutboundMapper.encodeJson(null))
        assertEquals("true", LarkOutboundMapper.encodeJson(true))
        assertEquals("42", LarkOutboundMapper.encodeJson(42))
        // 往返：contentJsonOf(text) 的转义与我们期望的字面 JSON 一致
        assertEquals("""{"text":"line1\nline2 \"quoted\" \\ end 🎉"}""",
            LarkOutboundMapper.contentJsonOf(Outbound.text("line1\nline2 \"quoted\" \\ end 🎉")))
    }

    // Outbound 契约不变式：CARD 当且仅当 card != null；TEXT/MARKDOWN 当且仅当 text != null
    @Test
    fun `Outbound enforces its contract invariants`() {
        assertFailsWith<IllegalArgumentException> { Outbound(OutboundKind.CARD, text = "x", card = null) }
        assertFailsWith<IllegalArgumentException> { Outbound(OutboundKind.TEXT, text = null) }
        assertFailsWith<IllegalArgumentException> { Outbound(OutboundKind.CARD, card = CardSpec(title = "t", markdown = "m"), text = "x") }
        // 合法构造
        assertTrue(Outbound.card(CardSpec(title = "t", markdown = "m")).card != null)
    }

    // IMAGE 出站（2026-09-27 能力一级化批 A）：不变式 + 工厂 + msgType/content 字面值
    @Test
    fun `Outbound IMAGE invariant mirrors CARD`() {
        // IMAGE 无 image spec 抛 / 非 IMAGE 带 image 抛
        assertFailsWith<IllegalArgumentException> { Outbound(OutboundKind.IMAGE, image = null) }
        assertFailsWith<IllegalArgumentException> {
            Outbound(OutboundKind.TEXT, text = "x", image = OutboundImageSpec("img_x"))
        }
        // 合法构造 + 工厂
        val out = Outbound.image("img_abc")
        assertEquals(OutboundKind.IMAGE, out.kind)
        assertEquals("img_abc", out.image?.imageKey)
        assertNull(out.image?.imageKey?.let { null } ?: out.text, "IMAGE 不带正文")
        assertNull(out.file)
        assertNull(out.card)
    }

    @Test
    fun `msgTypeOf and contentJsonOf handle IMAGE`() {
        assertEquals("image", LarkOutboundMapper.msgTypeOf(OutboundKind.IMAGE))
        assertEquals("""{"image_key":"img_abc"}""", LarkOutboundMapper.contentJsonOf(Outbound.image("img_abc")))
    }
}
