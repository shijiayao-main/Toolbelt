// 引用锚定测试（2026-09-12 conclusion-reply-anchor）：GatewayReplySink 级注入——
// 群聊出站携带 replyToMessageId（死字段激活）、私聊豁免、file 链也继承锚定。
// SyntheticGateway.send 录制 RecordedSend(target, out) ⇒ 断言直读 out.replyToMessageId。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ReplySinkAnchoringTest {

    /** 群聊消息（锚定主场景）。 */
    private fun groupMsg(): InboundMessage = msg(chatId = "oc_group", chatType = ChatType.GROUP, messageId = "om_question_group")

    /** 私聊消息（豁免场景）。 */
    private fun p2pMsg(): InboundMessage = msg(chatId = "oc_p2p", chatType = ChatType.P2P, messageId = "om_question_p2p")

    @Test
    fun `群聊出站携带锚定 id（text 与 markdown 双链）`() = runBlocking {
        // replyAnchorOf 取值语义：群聊 ⇒ 提问消息 id
        assertEquals("om_question_group", RequestContextFactory.replyAnchorOf(groupMsg()))
        val gw = SyntheticGateway()
        val sink = GatewayReplySink(
            gateway = gw,
            target = LarkTarget.ofInbound(groupMsg()),
            rateGovernor = RateGovernor(),
            netDispatcher = kotlinx.coroutines.Dispatchers.Default,
            replyToMessageId = RequestContextFactory.replyAnchorOf(groupMsg()),
        )
        assertTrue(sink.text("收到").ok)
        assertTrue(sink.markdown("结论").ok)
        assertEquals("om_question_group", gw.recordedSends[0].out.replyToMessageId, "text 链锚定")
        assertEquals("om_question_group", gw.recordedSends[1].out.replyToMessageId, "markdown 链锚定")
    }

    @Test
    fun `私聊豁免 - replyAnchorOf 返回 null`() {
        assertNull(RequestContextFactory.replyAnchorOf(p2pMsg()), "P2P 引用样式冗余 ⇒ 不锚定")
    }

    @Test
    fun `群聊 file 链继承锚定（Outbound file 携带 replyToMessageId）`() = runBlocking {
        val gw = SyntheticGateway()
        val sink = GatewayReplySink(
            gateway = gw,
            target = LarkTarget.ofInbound(groupMsg()),
            rateGovernor = RateGovernor(),
            netDispatcher = kotlinx.coroutines.Dispatchers.Default,
            replyToMessageId = RequestContextFactory.replyAnchorOf(groupMsg()),
        )
        java.nio.file.Files.createTempFile("anchor", ".md").toFile().apply { writeText("报告") }.let { f ->
            val r = sink.file(f.toPath(), "报告.md")
            assertTrue(r.ok, "SyntheticGateway 假件上传恒成功：${r.error}")
        }
        // file 链最终也经 gateway.send(Outbound.file) ⇒ 锚定字段须透传到网关层（真机由 SdkLarkGateway.sendFileByKey 的 reply 分支消费）
        val recorded = gw.recordedSends.lastOrNull() ?: error("应有出站记录")
        assertEquals("om_question_group", recorded.out.replyToMessageId, "file 出站必须携带锚定 id")
    }

    @Test
    fun `无锚定时字段保持 null - 语义与关闭前一致`() = runBlocking {
        val gw = SyntheticGateway()
        val sink = GatewayReplySink(
            gateway = gw,
            target = LarkTarget.ofInbound(p2pMsg()),
            rateGovernor = RateGovernor(),
            netDispatcher = kotlinx.coroutines.Dispatchers.Default,
            replyToMessageId = null,
        )
        sink.markdown("结论")
        assertNull(gw.recordedSends.last().out.replyToMessageId, "私聊/未传锚定 ⇒ 字段不填（关闭前形态）")
    }

    // 🔴 对抗评审补测：卡片回传豁免。fromCardAction 硬编码 chatType=UNKNOWN ⇒ 仅靠 P2P 判定时，
    //    私聊里点确认按钮的裁决回复会被错误锚定（整条 cardAction 路径豁免失效）。锚定语义是「锚到用户的提问」，
    //    卡片回传不是提问（是用户对机器人的交互）⇒ 一律不锚定。
    @Test
    fun `卡片回传一律不锚定 - chatType 恒 UNKNOWN 使 P2P 豁免失效`() {
        val cardAction = msg(
            chatId = "oc_p2p", chatType = ChatType.UNKNOWN,
            messageId = "om_confirm_card", kind = InboundKind.CARD_ACTION,
        )
        assertNull(RequestContextFactory.replyAnchorOf(cardAction), "卡片回传不是提问 ⇒ 不锚定（含群聊裁决回复）")

        val groupCardAction = msg(
            chatId = "oc_group", chatType = ChatType.UNKNOWN,
            messageId = "om_confirm_card", kind = InboundKind.CARD_ACTION,
        )
        assertNull(RequestContextFactory.replyAnchorOf(groupCardAction), "群聊卡片回传同样不锚定")
    }
}
