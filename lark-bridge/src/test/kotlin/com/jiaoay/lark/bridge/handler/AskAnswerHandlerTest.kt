// AskAnswerHandler 直测：配对（引用/按钮双通道）/防替答/未配对静默跳过。
// bus 本体语义（超时/取消注销）由 AskUserBusTest 钉死；本文件只钉 handler 的 match/handle 面。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.confirm.AskUserBus
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.HandlerWeight
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.pipeline.FakeAgentRunner
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.bridge.pipeline.ReplyDirective
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.gateway.CardAction
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class AskAnswerHandlerTest {

    private val bus = AskUserBus(BridgeLog.Silent)

    private fun handler() = AskAnswerHandler(bus, HandlerDeps({ testConfig() }, BridgeLog.Silent))

    private fun textMsg(
        messageId: String = "om_reply",
        replyTo: String? = "om_card",
        senderId: String = "ou_a",
        text: String = "方案 B",
    ): InboundMessage = InboundMessage(
        messageId = messageId,
        chatId = "oc_test",
        chatType = ChatType.P2P,
        senderId = senderId,
        senderName = "",
        text = text,
        kind = InboundKind.TEXT,
        rawContentType = "text",
        resources = emptyList(),
        mentions = emptyList(),
        mentionAll = false,
        mentionedBot = false,
        rootId = null,
        threadId = null,
        replyToMessageId = replyTo,
        createTimeMillis = System.currentTimeMillis(),
    )

    private fun cardActionMsg(
        cardMessageId: String = "om_card",
        actionValue: String = "qa-1:选项二",
        senderId: String = "ou_a",
    ): InboundMessage = InboundMessage(
        messageId = cardMessageId,
        chatId = "oc_test",
        chatType = ChatType.P2P,
        senderId = senderId,
        senderName = "",
        text = "",
        kind = InboundKind.CARD_ACTION,
        rawContentType = null,
        resources = emptyList(),
        mentions = emptyList(),
        mentionAll = false,
        mentionedBot = false,
        rootId = null,
        threadId = null,
        replyToMessageId = null,
        createTimeMillis = System.currentTimeMillis(),
        cardAction = CardAction(
            operatorId = senderId,
            actionTag = null,
            actionName = null,
            actionOption = null,
            actionValue = actionValue,
        ),
    )

    // ── 档位与配对面 ──

    @Test
    fun `LIGHT 档不占许可（秒级配对面不与长任务互锁）`() {
        assertEquals(HandlerWeight.LIGHT, handler().weight)
    }

    @Test
    fun `match 引用在途提问卡才命中，未引用不命中`() {
        val h = handler()
        bus.register("om_card", "oc_test", "ou_a", "问题")
        assertTrue(h.match(ctxOf(textMsg(replyTo = "om_card"))), "引用在途提问卡须命中")
        assertFalse(h.match(ctxOf(textMsg(replyTo = null))), "未引用不命中")
        assertFalse(h.match(ctxOf(textMsg(replyTo = "om_other"))), "引用非提问卡不命中")
    }

    @Test
    fun `match 按钮回传 value 形如 qa-前缀才命中`() {
        val h = handler()
        assertTrue(h.match(ctxOf(cardActionMsg(actionValue = "qa-1:选项二"))), "qa- 前缀按钮须命中")
        assertFalse(h.match(ctxOf(cardActionMsg(actionValue = "cf-9:allow"))), "确认卡按钮归 InteractionHandler 不命中")
    }

    // ── handle 双通道 ──

    @Test
    fun `引用回复完成挂起（发起者）`() = runBlocking {
        val h = handler()
        bus.register("om_card", "oc_test", "ou_a", "问题")
        val awaiting = async { bus.awaitAnswer("om_card", 10_000) }
        yield()
        val outcome = h.handle(ctxOf(textMsg()))
        assertEquals(TaskStatus.SUCCESS, outcome.status, "发起者回答须被采纳：${outcome.summary}")
        assertEquals("方案 B", awaiting.await().values["answer"])
    }

    @Test
    fun `按钮回传完成挂起（单选答案落 answer 字段）`() = runBlocking {
        val h = handler()
        bus.register("om_card", "oc_test", "ou_a", "单选")
        val awaiting = async { bus.awaitAnswer("om_card", 10_000) }
        yield()
        val outcome = h.handle(ctxOf(cardActionMsg()))
        assertEquals(TaskStatus.SUCCESS, outcome.status, "按钮回答须被采纳：${outcome.summary}")
        assertEquals("选项二", awaiting.await().values["answer"])
    }

    @Test
    fun `非发起者回答静默跳过（群聊他人引用提问卡不误伤）`() = runBlocking {
        val h = handler()
        bus.register("om_card", "oc_test", "ou_a", "问题")
        val outcome = h.handle(ctxOf(textMsg(senderId = "ou_b")))
        assertTrue(outcome.status == TaskStatus.SKIPPED && outcome.directive == ReplyDirective.SILENT, "他人回答须静默跳过：${outcome.status}")
    }

    // ── 测试基建 ──

    private fun ctxOf(message: InboundMessage): RequestContext = RequestContext(
        message = message,
        config = testConfig(),
        route = null,
        reply = RecordingReplySink(LarkTarget.ofInbound(message)),
        progress = com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink(),
        log = BridgeLog.Silent,
        agent = FakeAgentRunner(),
        processes = com.jiaoay.lark.bridge.exec.ProcessRegistry(testConfig().runtime.maxConcurrentAgents),
    )
}
