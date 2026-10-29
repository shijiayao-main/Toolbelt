// 能力一级化批 C（2026-09-27）：ToolConfirmGate 适配层直测。
// 🔴 ConfirmService 既有语义（防替答/超时=拒绝/CANCELLED）由 ConfirmServiceTest 钉死、不重测——
//    本文件只测适配层：fail-closed 三态、确认卡通知次序、裁决透传。
package com.jiaoay.lark.bridge.confirm

import com.jiaoay.lark.bridge.BridgeLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class ToolConfirmGateTest {

    /** 录制型 notifier：记录收到的确认请求（不发卡）。 */
    private class RecordingNotifier : ConfirmNotifier {
        val notified = mutableListOf<ConfirmService.ConfirmRequest>()
        override suspend fun notify(request: ConfirmService.ConfirmRequest) {
            notified.add(request)
        }
    }

    private fun gate(
        service: ConfirmService?,
        notifier: ConfirmNotifier?,
        chatId: String = "oc_1",
        sender: String = "ou_a",
        timeoutMillis: Long = 5_000,
    ) = ToolConfirmGate(service, notifier, chatId, sender, timeoutMillis, BridgeLog.Silent)

    // ── fail-closed（红线 4：链不可用绝不放行） ──

    @Test
    fun `service 缺席立即 DENIED 且不挂起`() = runBlocking {
        val started = System.currentTimeMillis()
        val verdict = gate(service = null, notifier = RecordingNotifier())
            .requireConfirmation("create_group_chat", "建群")
        assertEquals(ToolConfirmGate.Verdict.DENIED, verdict, "链不可用须 fail-closed")
        assertTrue(System.currentTimeMillis() - started < 1_000, "缺席须立即拒（不挂超时时长）")
    }

    @Test
    fun `notifier 缺席立即 DENIED（用户看不到确认卡挂满超时是体验断裂）`() = runBlocking {
        val started = System.currentTimeMillis()
        val verdict = gate(service = ConfirmService(), notifier = null)
            .requireConfirmation("create_group_chat", "建群")
        assertEquals(ToolConfirmGate.Verdict.DENIED, verdict)
        assertTrue(System.currentTimeMillis() - started < 1_000, "缺席须立即拒")
    }

    // ── 正常链路：通知次序 + 裁决透传 ──

    @Test
    fun `确认请求先登记再通知且裁决透传 APPROVED`() = runBlocking {
        val service = ConfirmService()
        val notifier = RecordingNotifier()
        val g = gate(service, notifier)
        val waiter = async { g.requireConfirmation("create_group_chat", "建群「项目群」，成员 2 人") }
        delay(50)
        // 通知先于裁决到达（GAP-2 次序：newRequest → notify → awaitDecision）
        assertEquals(1, notifier.notified.size, "确认卡必须发出一次")
        val request = notifier.notified.single()
        assertEquals("oc_1", request.chatId)
        assertEquals("ou_a", request.senderOpenId)
        assertEquals("create_group_chat", request.toolName)
        assertTrue("项目群" in request.inputSummary, "入参摘要须含建群信息：${request.inputSummary}")
        // 用户批准
        assertTrue(
            service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", true)
                is ConfirmService.DecideResult.Ok,
        )
        assertEquals(ToolConfirmGate.Verdict.APPROVED, waiter.await())
    }

    @Test
    fun `用户拒绝透传 DENIED`() = runBlocking {
        val service = ConfirmService()
        val notifier = RecordingNotifier()
        val g = gate(service, notifier)
        val waiter = async { g.requireConfirmation("create_group_chat", "建群") }
        delay(50)
        val request = notifier.notified.single()
        service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_a", false)
        assertEquals(ToolConfirmGate.Verdict.DENIED, waiter.await(), "拒绝须透传 DENIED")
    }

    @Test
    fun `超时透传 DENIED（超时等于拒绝既有语义）`() = runBlocking {
        val service = ConfirmService()
        val notifier = RecordingNotifier()
        val g = gate(service, notifier, timeoutMillis = 100)
        assertEquals(ToolConfirmGate.Verdict.DENIED, g.requireConfirmation("create_group_chat", "建群"))
        assertEquals(0, service.pendingCount(), "超时后在途条目须注销（复用 awaitDecision 语义）")
    }

    /** 🔴 用户「停止」（cancelAll → CANCELLED）对工具面同样 = DENIED（与 MCP server 同口径）。 */
    @Test
    fun `cancelAll 后等待方按 DENIED 处理`() = runBlocking {
        val service = ConfirmService()
        val notifier = RecordingNotifier()
        val g = gate(service, notifier)
        val waiter = async { g.requireConfirmation("create_group_chat", "建群") }
        delay(50)
        service.cancelAll()
        assertEquals(ToolConfirmGate.Verdict.DENIED, waiter.await(), "CANCELLED 对工具面 = 拒绝")
    }

    /** 🔴 防替答：非原发送者裁决不影响等待方（ConfirmService 快照被正确传导）。 */
    @Test
    fun `防替答快照正确传导（他人裁决无效，等待方最终超时拒绝）`() = runBlocking {
        val service = ConfirmService()
        val notifier = RecordingNotifier()
        val g = gate(service, notifier, timeoutMillis = 200)
        val waiter = async { g.requireConfirmation("create_group_chat", "建群") }
        delay(50)
        val request = notifier.notified.single()
        assertEquals(
            ConfirmService.DecideResult.Forbidden,
            service.decide(ConfirmService.shortId(request.requestId), "oc_1", "ou_other", true),
            "他人裁决须被防替答拦下（快照传导正确的证据）",
        )
        assertEquals(ToolConfirmGate.Verdict.DENIED, waiter.await())
    }
}
