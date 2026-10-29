package com.jiaoay.easy_dev_toolbox.page.mcp

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MCP server 冒烟回归（P5 openspec mcp-expose 任务 5.3 的本机自验面——保留为常驻防线）。
 *
 * 验证面：SDK + Ktor CIO 在纯 JVM 真起端点（design D9 风险——0.x SDK 与 Ktor 引擎
 * 组合能否真实监听/握手，单测零 Ktor 覆盖不到这一层）。SDK 0.x 升级时这条防线最先报警。
 *
 * 端口用 18765/18766 高位段（避 8765 生产口）；每用例起停自管，无跨用例残留。
 * 完整 5.3 验收（MCP Inspector UI 全链 + 研判真机 + 取消保全 + jpackage）在桌面 App
 * 形态由维护者参与执行。
 */
class McpServerSmokeTest {

    @Test
    fun `embedded server starts and answers mcp initialize over http`() {
        val port = 18765 // 高位端口防本机 8765 被占
        McpServerManager.resetForTest()
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(port) }
        try {
            assertTrue(McpServerManager.status.value.running, "server 必须进入运行态")

            // HTTP POST initialize 握手（stateless streamable HTTP——JSON-RPC over HTTP）
            val body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-11-25",
                   "capabilities":{},
                   "clientInfo":{"name":"smoke","version":"0.0.1"}}}
            """.trimIndent().replace("\n", "")
            val conn = java.net.URL("http://127.0.0.1:$port/mcp")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray())
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            assertTrue("Toolbelt" in response, "initialize 应答含 serverInfo.name=Toolbelt：$response")
            assertTrue("tools" in response, "initialize 应答声明 tools capability：$response")
        } finally {
            McpServerManager.stopNow()
            McpServerManager.resetForTest()
        }
    }

    @Test
    fun `tools list returns exposed tool names`() {
        val port = 18766
        McpServerManager.resetForTest()
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(port) }
        try {
            // 先 initialize 再 tools/list（stateless 形态下两请求独立，但保持协议习惯）
            val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"s","version":"0"}}}"""
            post(port, init)

            val list = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
            val response = post(port, list)

            assertTrue("fs.extended.read_file" in response, "工具清单含取证七件：$response")
            assertTrue("workbench_analyze" in response, "工具清单含 agent 会话工具：$response")
            assertTrue("workbench_status" in response)
            assertTrue("memory.view" !in response, "🔴 memory.* 不得出现在外放清单")
        } finally {
            McpServerManager.stopNow()
            McpServerManager.resetForTest()
        }
    }

    private fun post(port: Int, body: String): String {
        val conn = java.net.URL("http://127.0.0.1:$port/mcp")
            .openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.doOutput = true
        conn.outputStream.write(body.toByteArray())
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }
}
