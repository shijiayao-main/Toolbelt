// 流式卡终态落账信号（2026-09-11 晚）：flushTerminal / close 兜底两路径都必须发 StreamTerminal，
// 携带终态全文与卡消息 messageId——「引用机器人回复」的本地账兜底全靠这条信号链。
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class StreamTerminalSignalTest {

    @Test
    fun `flushTerminal 后发射 StreamTerminal 携带终态全文与 messageId`() = runTest {
        val port = FakeCardStreamPort()
        val signals = mutableListOf<GatewaySignal>()
        val sink = newTestSink(port, backgroundScope, testScheduler, onSignal = { signals.add(it) })
        testScheduler.runCurrent() // 建第 1 段（生成 card_1 + om_c1）

        sink.tryReplace("流式中的部分输出…")
        sink.flushTerminal(CardSpec(title = "完成", markdown = "最终结论：问题已定位"))
        testScheduler.runCurrent()

        val terminal = signals.filterIsInstance<GatewaySignal.StreamTerminal>().singleOrNull()
        assertNotNull(terminal, "flushTerminal 必须发射 StreamTerminal，实际信号=$signals")
        assertEquals("msg_1", terminal.messageId, "messageId 应为按引用发送得到的卡消息 id")
        assertEquals("最终结论：问题已定位", terminal.markdown, "markdown 应为终态卡全文（CardSpec 优先于流内快照）")
        sink.close(); testScheduler.runCurrent()
    }

    @Test
    fun `close 兜底路径同样发射 StreamTerminal（正文 = 流内快照）`() = runTest {
        val port = FakeCardStreamPort()
        val signals = mutableListOf<GatewaySignal>()
        val sink = newTestSink(port, backgroundScope, testScheduler, onSignal = { signals.add(it) })
        testScheduler.runCurrent()

        sink.tryReplace("未发终态卡就关闭的正文")
        sink.close() // close 兜底：也算终态落定
        testScheduler.runCurrent()

        val terminal = signals.filterIsInstance<GatewaySignal.StreamTerminal>().singleOrNull()
        assertNotNull(terminal, "close 兜底也应发射 StreamTerminal，实际信号=$signals")
        assertEquals("未发终态卡就关闭的正文", terminal.markdown)
        assertEquals("msg_1", terminal.messageId)
    }

    @Test
    fun `flushTerminal 与 close 不重复发射（幂等）`() = runTest {
        val port = FakeCardStreamPort()
        val signals = mutableListOf<GatewaySignal>()
        val sink = newTestSink(port, backgroundScope, testScheduler, onSignal = { signals.add(it) })
        testScheduler.runCurrent()

        sink.flushTerminal(CardSpec(title = "完成", markdown = "终态"))
        sink.close() // flushTerminal 已置 open=false ⇒ close 幂等短路，不再发
        testScheduler.runCurrent()

        assertEquals(1, signals.count { it is GatewaySignal.StreamTerminal }, "终态信号必须恰好一次：$signals")
    }

    @Test
    fun `空流 close 不发射（无记账价值）`() = runTest {
        val port = FakeCardStreamPort()
        val signals = mutableListOf<GatewaySignal>()
        val sink = newTestSink(port, backgroundScope, testScheduler, onSignal = { signals.add(it) })
        testScheduler.runCurrent()

        sink.close()
        testScheduler.runCurrent()

        assertNull(signals.firstOrNull { it is GatewaySignal.StreamTerminal }, "空流 close 无记账价值不应发射")
        assertTrue(signals.isEmpty() || signals.all { it !is GatewaySignal.StreamTerminal })
    }
}
