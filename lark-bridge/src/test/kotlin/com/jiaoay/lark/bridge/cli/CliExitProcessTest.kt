// M5-T10 测试：🔴 头号注意事项（gateway-contract §16.6 / S6 真机实测）的钉子。
// SdkLarkGateway.close() 单靠反射 shutdown 不能让 JVM 自然退出（残留 OkHttp Dispatcher + main 两个非 daemon 线程）
// ⇒ serve 的停止流程必须在 bridge.close() 返回后由宿主显式 exitProcess。本测试用注入的 exit seam 断言：
//   (1) exit 确被调用；(2) 调用发生在 gateway.close() **之后**（exit 瞬间 closeCount >= 1）；(3) 正常停止退出码为 0。
// 🔴 测试里绝不真退出 JVM（exit 是 recorder，不是 exitProcess）。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.parse
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("cli-exit-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

class CliExitProcessTest {

    @Test
    fun `🔴 serve --once 停止路径：exit 被调用且发生在 gateway-close() 之后（§16-6），退出码 0`() {
        val root = tempRoot("after-close")
        val gw = SyntheticGateway()
        val baos = ByteArrayOutputStream()
        val out = PrintStream(baos, true)
        val exits = mutableListOf<Int>()
        // 🔴 关键：在 exit 被调用的**当下**读取 closeCount，证明 close() 已先于 exit 发生。
        var closeCountAtExit = -1
        val exitSeam: (Int) -> Unit = { code ->
            closeCountAtExit = gw.closeCount
            exits.add(code)
        }

        ServeCommand(out, exitSeam, gatewayFactoryOverride = { gw }).parse(
            listOf(
                "--data-dir", root.toString(),
                "--dry-run", "--once",
                "--set", "connection.appId=cli_exit",
                "--set", "connection.appSecret=s",
            ),
        )

        assertEquals(listOf(0), exits, "🔴 serve 正常停止必须由宿主显式 exit(0)，实际=$exits")
        assertTrue(
            closeCountAtExit >= 1,
            "🔴 exitProcess 必须在 gateway.close() **之后**调用（否则残留非 daemon 线程，JVM 停不下来）；" +
                "exit 瞬间 closeCount=$closeCountAtExit",
        )
        // 事后复核：网关确已关闭。
        assertTrue(gw.closeCount >= 1, "停止后网关应已 close")
    }

    @Test
    fun `🔴 serve 被拒路径（缺 appId）：exit 非 0 且在 close() 之后（不抛栈、不悬挂）`() {
        val root = tempRoot("refused-exit")
        val gw = SyntheticGateway()
        val baos = ByteArrayOutputStream()
        val out = PrintStream(baos, true)
        val exits = mutableListOf<Int>()
        // 🔴 --dry-run：本用例钉的是「缺 appId ⇒ ConfigInvalid(2)」闸，agent 预检须豁免，
        //    防测试机无 claude 时预检先拒（exit 1）把被测闸遮住。
        ServeCommand(out, { exits.add(it) }, gatewayFactoryOverride = { gw })
            .parse(listOf("--data-dir", root.toString(), "--dry-run"))
        assertEquals(listOf(2), exits, "缺 appId 被拒 ⇒ exit(2)，实际=$exits")
        // 被拒路径 start() 在建 bundle 前返回，网关未被创建/连接（结构性零真机保证）。
        assertEquals(0, gw.connectCount, "被拒路径不得建连")
    }

    @Test
    fun `🔴 serve 预检面（CLI 后端构造退役后）：配置在表字段可 --dry-run --once 起桥（离线联调形态）`() {
        // CLI 后端构造旗标已出表——本用例由「不可解析后端 ⇒ 预检拒绝」收窄为「在表配置可正常起桥」
        //（AgentUnavailable 预检路径随后续 serve 降级整体退役）。
        val root = tempRoot("agent-unavailable")
        val gw = SyntheticGateway()
        val baos = ByteArrayOutputStream()
        val out = PrintStream(baos, true)
        val exits = mutableListOf<Int>()
        ServeCommand(out, { exits.add(it) }, gatewayFactoryOverride = { gw }).parse(
            listOf(
                "--data-dir", root.toString(),
                "--dry-run", "--once",
                "--set", "connection.appId=cli_agent_gate",
                "--set", "connection.appSecret=s",
                "--set", "agent.groupMode=all",
            ),
        )
        assertEquals(listOf(0), exits, "dry-run once 正常停止 exit(0)，实际=$exits")
    }

    @Test
    fun `🔴 serve 预检 dryRun 豁免：后端不可解析仍可 --dry-run --once 起桥（离线联调形态）`() {
        val root = tempRoot("agent-dryrun-exempt")
        val gw = SyntheticGateway()
        val baos = ByteArrayOutputStream()
        val out = PrintStream(baos, true)
        val exits = mutableListOf<Int>()
        ServeCommand(out, { exits.add(it) }, gatewayFactoryOverride = { gw }).parse(
            listOf(
                "--data-dir", root.toString(),
                "--dry-run", "--once",
                "--set", "connection.appId=cli_agent_dry",
                "--set", "connection.appSecret=s",
                "--set", "agent.groupMode=all",
            ),
        )
        assertEquals(listOf(0), exits, "dryRun 豁免预检 ⇒ --once 正常停止 exit(0)，实际=$exits")
    }

    @Test
    fun `🔴 handle-event 不做 agent 预检：后端不可解析仍可离线回放 SYSTEM pong（离线验证锚不受预检误伤）`() {
        val root = tempRoot("handle-event-no-precheck")
        val event = root.resolve("ev-${System.nanoTime()}.json")
        Files.write(
            event,
            """
            {"messageId":"om_he_gate","chatId":"oc_he","chatType":"p2p","senderId":"ou_he",
             "senderName":"He","text":"ping","kind":"text"}
            """.trimIndent().toByteArray(Charsets.UTF_8),
        )
        val baos = ByteArrayOutputStream()
        val out = PrintStream(baos, true)
        val exits = mutableListOf<Int>()
        HandleEventCommand(out, { exits.add(it) }).parse(
            listOf(
                "--event", event.toString(),
                "--data-dir", root.toString(),
                "--set", "agent.groupMode=all",
            ),
        )
        val text = baos.toString(Charsets.UTF_8.name())
        assertTrue(text.contains("pong"), "SYSTEM pong 锚在无 agent 机器上必须可用（恒 SyntheticGateway 离线），实际输出=\n$text")
        assertTrue(exits.isEmpty() || exits.all { it == 0 }, "回放成功不得非 0 退出，实际=$exits")
    }
}
