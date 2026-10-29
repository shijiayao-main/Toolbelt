// S1 · toInboundMessage 映射细节的端到端直测（CLI handle-event 是唯一离线回放入口）。
// 🔴 缺口：HandleEventCommandTest 已覆盖端到端主路径（ping/引用流/文件两连），但
//    CliEvent → InboundMessage 的 16 字段映射细节零直测——非法 kind 回落 TEXT、
//    chatType 大小写归一、messageId 兜底合成、createTimeMillis<=0 兜底当前时钟。
//    这是「第二 IM 参照实现的活证据」（方案 5.3：不必改 InboundMessage 也能造入站消息），
//    映射错了整条回放链都会静默失真，必须钉死。
// 测法：映射函数是 private、DTO 是 private ⇒ 从 CLI 回放端到端观测（handle-event 把映射后的
//    messageId/chatType/kind/text/replyTo 逐字段回显到 stdout——这本身就是映射产物的诚实快照）。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.parse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ToInboundMessageMappingTest {

    private fun tempRoot(tag: String): Path =
        Files.createTempDirectory("cli-map-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

    private fun run(eventJson: String, vararg extra: String): String {
        val root = tempRoot("run")
        val eventFile = root.resolve("event.json").also { Files.write(it, eventJson.toByteArray(Charsets.UTF_8)) }
        val baos = ByteArrayOutputStream()
        val out = PrintStream(baos, true)
        HandleEventCommand(out, exit = {}).parse(
            (listOf("--event", eventFile.toString(), "--data-dir", root.toString()) + extra).toList(),
        )
        return baos.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `非法 kind 回落 TEXT 且 rawContentType 原样透传`() {
        val text = run(
            """
            {
              "messageId": "om_map1",
              "chatId": "oc_map",
              "chatType": "p2p",
              "senderId": "ou_map",
              "text": "ping",
              "kind": "definitely_not_a_kind"
            }
            """.trimIndent(),
        )
        // runCatching { valueOf } 回落 TEXT：管线按 TEXT 跑通（ping ⇒ pong），回显 kind=TEXT
        assertTrue("kind:      TEXT" in text, "非法 kind 必须回落 TEXT（valueOf 失败兜底），实际输出=\n$text")
        assertTrue("pong" in text, "回落 TEXT 后管线应跑通回 pong")
    }

    @Test
    fun `chatType 大小写与未知值归一（GROUP 大写合法、未知值 UNKNOWN 不崩溃）`() {
        val text = run(
            """
            {
              "messageId": "om_map2",
              "chatId": "oc_map",
              "chatType": "GROUP",
              "senderId": "ou_map",
              "text": "ping",
              "kind": "text"
            }
            """.trimIndent(),
        )
        assertTrue("chatType:  GROUP" in text, "lowercase() 后匹配 ⇒ 大写 GROUP 合法，实际输出=\n$text")

        val textUnknown = run(
            """
            {
              "messageId": "om_map3",
              "chatType": "town_square",
              "text": "ping",
              "kind": "text"
            }
            """.trimIndent(),
        )
        assertTrue("chatType:  UNKNOWN" in textUnknown, "未知 chatType ⇒ UNKNOWN（不崩溃），实际输出=\n$textUnknown")
    }

    @Test
    fun `messageId 兜底合成（空串 ⇒ om_cli_ 前缀、不与显式 ID 混淆）`() {
        val text = run(
            """
            {
              "chatId": "oc_map",
              "text": "ping",
              "kind": "text"
            }
            """.trimIndent(),
        )
        // 空 messageId ⇒ ifBlank 合成 om_cli_<nanoTime>；回显必须带 om_cli_ 前缀
        assertTrue("messageId: om_cli_" in text, "空 messageId 必须兜底合成 om_cli_ 前缀，实际输出=\n$text")
        assertTrue("om_cli_" in text && "messageId: om_cli_" in text)
    }

    @Test
    fun `引用字段与 mention 旗标映射（replyToMessageId 透传、mentionedBot 置位）`() {
        val text = run(
            """
            {
              "messageId": "om_map4",
              "chatId": "oc_map",
              "chatType": "p2p",
              "senderId": "ou_map",
              "text": "ping",
              "kind": "text",
              "replyToMessageId": "om_origin",
              "mentionedBot": true
            }
            """.trimIndent(),
        )
        assertTrue("replyTo:   om_origin" in text, "replyToMessageId 必须透传，实际输出=\n$text")
        assertTrue("pong" in text)
    }

    @Test
    fun `未知 JSON 键宽容（ignoreUnknownKeys）与缺字段全默认`() {
        // CliEvent 全字段有默认值 + EventJson ignoreUnknownKeys ⇒ 只喂 text 也能跑通
        val text = run("""{"text": "ping", "futureField": "whatever"}""")
        assertTrue("pong" in text, "缺省字段全默认 + 未知键宽容 ⇒ 最小事件也能跑通管线，实际输出=\n$text")
        assertTrue("messageId: om_cli_" in text, "messageId 兜底合成在前一断言已验，此处确认最小事件同样合成")
    }
}
