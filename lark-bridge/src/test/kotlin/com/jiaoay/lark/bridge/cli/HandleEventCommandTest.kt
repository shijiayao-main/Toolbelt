// M5-T10 测试：handle-event 子命令——🔴 完全离线端到端验证五阶段管线（喂 SyntheticGateway，恒不触网）。
// 这是 M5-T11 真机闸门之外唯一能端到端验证引擎的入口，故必须做扎实：喂示例 JSON（ping）⇒ SystemHandler ⇒ pong 🏓，
// 断言出站结果与五阶段计数器均可从命令输出观测到。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.parse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("cli-event-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

private class EventCapture {
    val baos = ByteArrayOutputStream()
    val out = PrintStream(baos, true)
    val exits = mutableListOf<Int>()
    fun exit(code: Int) {
        exits.add(code)
    }

    fun text(): String = baos.toString(Charsets.UTF_8.name())
}

class HandleEventCommandTest {

    private fun writeEvent(root: Path, json: String): Path =
        root.resolve("event-${System.nanoTime()}.json").also { Files.write(it, json.toByteArray(Charsets.UTF_8)) }

    private fun run(root: Path, eventFile: Path, vararg extra: String): EventCapture {
        val cap = EventCapture()
        HandleEventCommand(cap.out, cap::exit)
            .parse((listOf("--event", eventFile.toString(), "--data-dir", root.toString()) + extra).toList())
        return cap
    }

    @Test
    fun `🔴 离线回放 ping ⇒ 管线跑通 ⇒ 出站 pong + 计数器可断言（不依赖飞书）`() {
        val root = tempRoot("ping")
        val event = writeEvent(
            root,
            """
            {
              "messageId": "om_test_ping",
              "chatId": "oc_test",
              "chatType": "p2p",
              "senderId": "ou_tester",
              "senderName": "Tester",
              "text": "ping",
              "kind": "text"
            }
            """.trimIndent(),
        )
        val cap = run(root, event)
        val text = cap.text()
        assertTrue(text.contains("pong"), "ping 应经五阶段管线由 SystemHandler 回复 pong，实际输出=\n$text")
        assertTrue(text.contains("intakeReceived=1"), "intake 应收到 1 条，实际输出=\n$text")
        assertTrue(text.contains("completed=1"), "应有 1 条完成，实际输出=\n$text")
        assertTrue(text.contains("om_test_ping"), "输出应回显事件 messageId")
        assertTrue(cap.exits.isEmpty(), "成功回放不应触发退出，实际=${cap.exits}")
    }

    @Test
    fun `未知文本 ⇒ 无匹配路由 ⇒ unmatched 计数（管线仍跑通不崩溃）`() {
        val root = tempRoot("unmatched")
        val event = writeEvent(
            root,
            """
            {
              "messageId": "om_test_none",
              "chatType": "p2p",
              "senderId": "ou_x",
              "text": "这是一条不会命中任何 handler 的普通消息",
              "kind": "text"
            }
            """.trimIndent(),
        )
        val cap = run(root, event)
        val text = cap.text()
        assertTrue(text.contains("intakeReceived=1"), "intake 应收到 1 条，实际输出=\n$text")
        assertTrue(cap.exits.isEmpty(), "无匹配不应崩溃退出，实际=${cap.exits}")
    }

    @Test
    fun `空事件输入 ⇒ 打印示例 + 非 0 退出（不崩溃）`() {
        val root = tempRoot("empty")
        val event = writeEvent(root, "   ")
        val cap = EventCapture()
        // 空文件（--event 指向 mustExist 文件，内容空白）⇒ 命令打印示例并 exit(1)。
        HandleEventCommand(cap.out, cap::exit)
            .parse(listOf("--event", event.toString(), "--data-dir", root.toString()))
        assertTrue(cap.exits.contains(1), "空事件应非 0 退出，实际=${cap.exits}")
        assertTrue(cap.text().contains("messageId"), "应打印可复制的示例 JSON")
    }

    @Test
    fun `损坏事件 JSON ⇒ ConfigParseFailed 退出码（2）+ 明确文案（不抛栈）`() {
        val root = tempRoot("bad")
        val event = writeEvent(root, "{ 这不是合法 JSON ")
        val cap = EventCapture()
        HandleEventCommand(cap.out, cap::exit)
            .parse(listOf("--event", event.toString(), "--data-dir", root.toString()))
        assertTrue(cap.exits.contains(2), "损坏事件 JSON 应退出码 2，实际=${cap.exits}")
        assertTrue(cap.text().contains("解析失败"), "应给出明确解析失败文案")
    }

    // ── 引用流两连回放（2026-09-11 重设计）：文件事件登记 → 引用事件触发分析 ──

    @Test
    fun `🔴 引用流两连回放-文件登记存活但 CLI 形态无应答引擎 ⇒ 显式失败不结算引用`() {
        val root = tempRoot("quote")
        val event = writeEvent(
            root,
            """
            [
              {
                "messageId": "om_file_1",
                "chatId": "oc_demo",
                "chatType": "p2p",
                "senderId": "ou_demo",
                "text": "<file key=\"fk_demo\" name=\"crash.zip\"/>",
                "kind": "file",
                "resources": [{"type": "file", "fileKey": "fk_demo", "fileName": "crash.zip"}]
              },
              {
                "messageId": "om_quote_1",
                "chatId": "oc_demo",
                "chatType": "p2p",
                "senderId": "ou_demo",
                "text": "ai 分析这个压缩包",
                "kind": "text",
                "replyToMessageId": "om_file_1"
              }
            ]
            """.trimIndent(),
        )
        val cap = run(root, event, "--set", "workspace.root=${root}")
        val text = cap.text()
        // 第一条：FileHandler 登记（文件登记恒静默——ackTemplate 已退役）
        assertTrue(text.contains("filesRegistered=1"), "应观测到登记计数（同进程登记表存活），实际输出=\n$text")
        assertTrue(!text.contains("已收到文件"), "文件登记不回执（静默留痕），实际输出=\n$text")
        // 第二条：CLI 形态装配 NoAgentRunner（backendId="none"、isAvailable=false）⇒ 引用解析在
        //    agent 管线内，可用性闸先失败 ⇒ 不计引用/不下载，但显式报错（fail-closed 不静默假成功）
        assertTrue(text.contains("agent_backend_unavailable"), "CLI 形态须显式报后端不可用，实际输出=\n$text")
        assertTrue(text.contains("filesReferenced=0"), "无应答引擎 ⇒ 不进入引用解析，实际输出=\n$text")
        assertTrue(text.contains("downloadsStored=0"), "无应答引擎 ⇒ 不下载，实际输出=\n$text")
        assertTrue(!text.contains("ConfigInvalid"), "引用回放不应有配置错误，实际输出=\n$text")
        assertTrue(cap.exits.isEmpty(), "两连回放不应触发退出，实际=${cap.exits}")
    }

    @Test
    fun `🔴 引用未登记的消息 ID ⇒ 引用回落纯文本不下载`() {
        val root = tempRoot("miss")
        val event = writeEvent(
            root,
            """
            {
              "messageId": "om_quote_alone",
              "chatId": "oc_demo",
              "chatType": "p2p",
              "senderId": "ou_demo",
              "text": "ai 单独引用",
              "kind": "text",
              "replyToMessageId": "om_never_registered"
            }
            """.trimIndent(),
        )
        val cap = run(root, event, "--set", "workspace.root=${root}")
        val text = cap.text()
        assertTrue(text.contains("intakeReceived=1"), "管线应跑通，实际输出=\n$text")
        assertTrue(text.contains("filesReferenced=0"), "未命中不应计引用，实际输出=\n$text")
        assertTrue(text.contains("downloadsStored=0"), "未命中不应下载，实际输出=\n$text")
        assertTrue(cap.exits.isEmpty(), "未命中回落不应退出，实际=${cap.exits}")
    }

    /** 端到端回放：文本引用（登记表必 miss）在 CLI 形态下不进入引用解析（agent 可用性闸先失败）。 */
    @Test
    fun `🔴 文本引用回放-CLI 形态无应答引擎 ⇒ 显式失败且不结算引用`() {
        val root = tempRoot("textquote")
        val event = writeEvent(
            root,
            """
            {
              "messageId": "om_quote_text",
              "chatId": "oc_demo",
              "chatType": "p2p",
              "senderId": "ou_demo",
              "text": "ai 结合引用排查",
              "kind": "text",
              "replyToMessageId": "om_text_src"
            }
            """.trimIndent(),
        )
        val cap = EventCapture()
        val customizer = { gw: com.jiaoay.lark.bridge.pipeline.SyntheticGateway ->
            gw.quotedMessageResult = com.jiaoay.lark.gateway.QuotedMessage.Text(
                messageId = "om_text_src", chatId = "oc_demo", senderId = "ou_demo",
                text = "今早 9 点服务重启过一次",
            )
        }
        HandleEventCommand(cap.out, cap::exit, customizer)
            .parse(listOf("--event", event.toString(), "--data-dir", root.toString(),
                "--set", "workspace.root=${root}"))
        val text = cap.text()
        // 引用链行为断言：CLI 形态无应答引擎 ⇒ 引用解析不执行（availability 闸先失败），
        //    失败显式可见（agent_backend_unavailable），管线不崩、不退格式错误
        assertTrue(text.contains("agent_backend_unavailable"), "CLI 形态须显式报后端不可用，实际输出=\n$text")
        assertTrue(text.contains("filesReferenced=0"), "无应答引擎 ⇒ 不计引用，实际输出=\n$text")
        assertTrue(!text.contains("ConfigInvalid"), "引用回放不应有配置错误，实际输出=\n$text")
        assertTrue(cap.exits.isEmpty(), "文本引用回放不应退出，实际=${cap.exits}")
    }
}
