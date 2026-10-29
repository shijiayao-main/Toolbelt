package com.jiaoay.easy_dev_toolbox.page.mcp

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * McpServerManager 状态机单测（P5 openspec mcp-expose 任务 1.2——design D8 零 Ktor/零 SDK）。
 *
 * server 启停经 [McpServerManager.McpEngine] 接口缝 fake——Ktor embeddedServer 与 SDK
 * 装配不进单测依赖面（SDK 0.x 行为面留真机验收 5.3）。
 */
class McpServerManagerTest {

    /** fake 引擎：记录 start 调用序；可编程失败（端口占用场景）。 */
    private class FakeEngine : McpServerManager.McpEngine {
        val startPorts = mutableListOf<Int>()
        var stopCount = 0
        var failWith: Exception? = null

        override fun start(port: Int): McpServerManager.McpEngine.StoppedHandle {
            startPorts += port
            failWith?.let { throw it }
            return McpServerManager.McpEngine.StoppedHandle { stopCount++ }
        }
    }

    /** 端口占用模拟：Ktor 的 BindException 是 java.net 家族（类名含 BindException）。 */
    private class FakeBindException(cause: Throwable) :
        RuntimeException("Address already in use", cause)

    private lateinit var engine: FakeEngine

    @BeforeTest
    fun setUp() {
        McpServerManager.resetForTest()
        engine = FakeEngine()
        McpServerManager.engineForTest = engine
    }

    @AfterTest
    fun tearDown() {
        // engine 复位由 resetForTest 收口（回 DefaultEngine——防 FakeEngine 跨类残留，
        // 2026-09-20 Quest 批 flaky 根治）；此处不再自行置 engine。
        McpServerManager.resetForTest()
    }

    @Test
    fun `startNow transitions to running and records port`() {
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(8765) }

        assertTrue(McpServerManager.status.value.running, "启动后状态面 running")
        assertEquals(8765, McpServerManager.status.value.port)
        assertEquals(listOf(8765), engine.startPorts, "引擎收到配置端口")
        assertEquals(null, McpServerManager.status.value.detail)
    }

    @Test
    fun `startNow twice is idempotent - engine starts only once`() {
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(8765) }
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(9000) }

        assertEquals(listOf(8765), engine.startPorts, "重复开启 = 空操作（不二次起 server）")
        assertEquals(8765, McpServerManager.status.value.port, "端口维持首次启动值")
    }

    @Test
    fun `port conflict surfaces failure without crash`() {
        engine.failWith = FakeBindException(java.net.BindException("Address already in use"))

        kotlinx.coroutines.runBlocking { McpServerManager.startNow(8765) }

        // spec 场景「端口冲突可感知」：FAILED 态 + 明示文案 + 不崩溃
        assertFalse(McpServerManager.status.value.running, "失败后不进 RUNNING")
        assertTrue(
            McpServerManager.status.value.detail?.contains("端口被占用") == true,
            "失败文案明示端口占用：实际=${McpServerManager.status.value.detail}",
        )
    }

    @Test
    fun `generic failure detail carries exception message`() {
        engine.failWith = IllegalStateException("boom")

        kotlinx.coroutines.runBlocking { McpServerManager.startNow(8765) }

        assertFalse(McpServerManager.status.value.running)
        assertTrue(McpServerManager.status.value.detail?.contains("boom") == true, "非占用失败带原始 message")
    }

    @Test
    fun `stopNow stops engine and resets status`() {
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(8765) }
        McpServerManager.stopNow()

        assertFalse(McpServerManager.status.value.running, "停止后状态面回 STOPPED")
        assertEquals(1, engine.stopCount, "引擎停止句柄被调用")
        assertEquals(McpServerManager.Status.STOPPED.port, McpServerManager.status.value.port)
    }

    @Test
    fun `stopNow without start is a no-op`() {
        McpServerManager.stopNow()

        assertEquals(0, engine.stopCount, "未运行时停止 = 空操作")
        assertFalse(McpServerManager.status.value.running)
    }

    @Test
    fun `run cancel hook fires on stopNow`() {
        var cancelled = 0
        McpServerManager.registerRunCancelHook { cancelled++ }
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(8765) }
        McpServerManager.stopNow()

        assertEquals(1, cancelled, "停止服务时取消在途 run（McpRunRegistry 收口）")
    }

    @Test
    fun `shutdown stops running server`() {
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(8765) }
        McpServerManager.shutdown()

        assertEquals(1, engine.stopCount, "App 退出停 server（不残留监听——spec 场景）")
        assertFalse(McpServerManager.status.value.running)
    }

    @Test
    fun `restart after stop uses fresh port`() {
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(8765) }
        McpServerManager.stopNow()
        kotlinx.coroutines.runBlocking { McpServerManager.startNow(9001) }

        assertEquals(listOf(8765, 9001), engine.startPorts, "停止后可按新端口重启")
        assertTrue(McpServerManager.status.value.running)
        assertEquals(9001, McpServerManager.status.value.port)
    }
}
