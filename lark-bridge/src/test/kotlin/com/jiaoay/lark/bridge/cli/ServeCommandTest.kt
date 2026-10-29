// M5-T10 测试：serve 子命令——无凭据优雅失败、单实例锁被占、--dry-run 离线跑通。
// 🔴 全部经注入的 SyntheticGateway + exit recorder，绝不真退出 JVM、绝不连飞书（本轮零真机调用的结构性保证：
//    缺 appId 时 start() 在建 bundle 前即 Refused，真实 SdkLarkGateway 工厂根本不被调用）。
package com.jiaoay.lark.bridge.cli

import com.github.ajalt.clikt.core.parse
import com.jiaoay.lark.bridge.BridgeAssembler
import com.jiaoay.lark.bridge.BridgeStartResult
import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import com.jiaoay.lark.bridge.pipeline.SyntheticGateway
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private fun tempRoot(tag: String): Path =
    Files.createTempDirectory("cli-serve-$tag-${System.nanoTime()}").also { it.toFile().deleteOnExit() }

private class ServeCapture {
    val baos = ByteArrayOutputStream()
    val out = PrintStream(baos, true)
    val exits = mutableListOf<Int>()
    fun exit(code: Int) {
        exits.add(code)
    }

    fun text(): String = baos.toString(Charsets.UTF_8.name())
}

class ServeCommandTest {

    @Test
    fun `🔴 无 appId ⇒ Refused(ConfigInvalid) 优雅失败 + 非 0 退出 + 明确文案（不抛栈、不建连）`() {
        val root = tempRoot("noappid")
        val cap = ServeCapture()
        val gw = SyntheticGateway()
        ServeCommand(cap.out, cap::exit, gatewayFactoryOverride = { gw })
            .parse(listOf("--data-dir", root.toString()))
        assertEquals(listOf(2), cap.exits, "缺 appId ⇒ 退出码 2（配置域），实际=${cap.exits}")
        assertTrue(cap.text().contains("启动被拒"), "应有明确拒绝文案，实际=\n${cap.text()}")
        assertTrue(cap.text().contains("ConfigInvalid"), "应标注错误码 ConfigInvalid")
        assertEquals(0, gw.connectCount, "预检失败在建 bundle 之前 ⇒ 网关绝不建连")
    }

    @Test
    fun `🔴 单实例锁被占 ⇒ BlockedByPeerInstance + 非 0 退出（3）+ D19-R16 提示`() {
        val root = tempRoot("lock")
        val appId = "cli_lock_test"
        val holderConfig = DefaultBridgeConfig.default()
            .copy(connection = ConnectionConfig(appId = appId, appSecret = "s"))
        // 先由另一座 Bridge 占住同 dataRoot+appId 的单实例锁。
        val holder = BridgeAssembler.assemble(root, { SyntheticGateway() }, holderConfig, consoleLog = false)
        runBlocking { holder.applyConfig(holderConfig) }
        assertEquals(BridgeStartResult.Started, runBlocking { holder.start() }, "前置：holder 应先取得锁")
        try {
            val cap = ServeCapture()
            ServeCommand(cap.out, cap::exit, gatewayFactoryOverride = { SyntheticGateway() }).parse(
                listOf(
                    "--data-dir", root.toString(),
                    "--dry-run", "--once",
                    "--set", "connection.appId=$appId",
                    "--set", "connection.appSecret=s",
                ),
            )
            assertEquals(listOf(3), cap.exits, "锁被占 ⇒ 退出码 3，实际=${cap.exits}")
            assertTrue(cap.text().contains("BlockedByPeerInstance"), "应标注 BlockedByPeerInstance")
            assertTrue(cap.text().contains("双开"), "应给出 D19/R16 双开丢消息提示，实际=\n${cap.text()}")
        } finally {
            runBlocking { holder.stop() }
            holder.close()
        }
    }

    @Test
    fun `🔴 --dry-run --once ⇒ 用 SyntheticGateway 离线跑通（连的是替身、正常退出 0）`() {
        val root = tempRoot("dry")
        val cap = ServeCapture()
        val gw = SyntheticGateway()
        ServeCommand(cap.out, cap::exit, gatewayFactoryOverride = { gw }).parse(
            listOf(
                "--data-dir", root.toString(),
                "--dry-run", "--once",
                "--set", "connection.appId=cli_dry",
                "--set", "connection.appSecret=s",
            ),
        )
        assertEquals(listOf(0), cap.exits, "--once 正常停止 ⇒ 退出码 0，实际=${cap.exits}")
        assertEquals(1, gw.connectCount, "dry-run 连的是 SyntheticGateway（离线），应建连一次")
        assertTrue(gw.closeCount >= 1, "停止应关闭网关")
        assertTrue(cap.text().contains("桥接已启动"), "应打印启动态，实际=\n${cap.text()}")
        assertTrue(cap.text().contains("--once"), "应打印 --once 优雅停止文案")
    }
}
