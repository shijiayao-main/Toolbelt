// 能力一级化批 B（2026-09-27）测试：
// 1. GatewayReplySink 三新成员（image / replyInThread / recallMessage）的出站行为；
// 2. TrackingReplySink 账本扩容——四出口（text/markdown/card/file）成功落账、失败不记、截断 2000。
// 镜像 ReplySinkAnchoringTest 的形态（SyntheticGateway + GatewayReplySink 直构造）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.TrackingReplySink
import com.jiaoay.lark.bridge.files.CardReplyLedger
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.GatewayErrorCode
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import com.jiaoay.lark.gateway.SendResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ReplySinkNativeCapabilitiesTest {

    /** 群聊消息（锚定/话题主场景）。 */
    private fun groupMsg(): InboundMessage = replyMsg(chatId = "oc_group", chatType = ChatType.GROUP, messageId = "om_question_group")

    private fun replyMsg(
        chatId: String,
        chatType: ChatType,
        messageId: String,
        kind: InboundKind = InboundKind.TEXT,
    ): InboundMessage = msg(
        chatId = chatId,
        chatType = chatType,
        messageId = messageId,
        kind = kind,
        senderId = "ou_user",
        senderName = "",
        text = "hi",
        createTimeMillis = 0L,
    )

    private fun sink(
        gateway: SyntheticGateway,
        target: LarkTarget,
        replyToMessageId: String? = null,
    ): GatewayReplySink = GatewayReplySink(
        gateway = gateway,
        target = target,
        rateGovernor = RateGovernor(),
        netDispatcher = Dispatchers.Default,
        replyToMessageId = replyToMessageId,
    )

    // ── image（两步 uploadImage→Outbound.image，镜像 file 纪律）──

    @Test
    fun `image 走两步出站且 IMAGE 不变式成立`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw, LarkTarget.ofInbound(groupMsg()))
        val img = Files.createTempFile("native-img", ".png").apply { Files.write(this, byteArrayOf(1, 2, 3)) }
        val result = s.image(img)
        assertTrue(result.ok, "SyntheticGateway 假件上传恒成功：${result.error}")
        assertEquals("om_synthetic_1", result.messageId)
        // 记录面：一次图片上传 + 一次 IMAGE 出站
        assertEquals(1, gw.recordedImageUploads.size)
        val recorded = gw.recordedSends.single()
        assertEquals(com.jiaoay.lark.gateway.OutboundKind.IMAGE, recorded.out.kind)
        assertEquals("image_key_synthetic_1", recorded.out.image?.imageKey)
    }

    @Test
    fun `image 上传失败返回 UploadFailed 且零出站`() = runBlocking {
        val gw = SyntheticGateway()
        gw.uploadImageResult = false // 模拟上传失败（null = 能力缺席）
        val s = sink(gw, LarkTarget.ofInbound(groupMsg()))
        val img = Files.createTempFile("native-img-fail", ".png").apply { Files.write(this, byteArrayOf(1)) }
        val result = s.image(img)
        assertFalse(result.ok, "上传失败须返回失败结果")
        assertEquals(GatewayErrorCode.UploadFailed, result.error, "失败码须是 UploadFailed（file 同款）")
        assertNull(result.messageId)
        assertEquals(0, gw.recordedSends.size, "上传失败不得发起出站")
    }

    @Test
    fun `image 链继承群聊锚定（与 file 同构）`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw, LarkTarget.ofInbound(groupMsg()), replyToMessageId = "om_question_group")
        val img = Files.createTempFile("native-img-anchor", ".png").apply { Files.write(this, byteArrayOf(1)) }
        assertTrue(s.image(img).ok)
        assertEquals("om_question_group", gw.recordedSends.single().out.replyToMessageId, "image 出站必须携带锚定 id")
    }

    // ── replyInThread（话题标记 + 统一锚定）──

    @Test
    fun `replyInThread 置位话题标记且保留锚定`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw, LarkTarget.ofInbound(groupMsg()), replyToMessageId = "om_question_group")
        val result = s.replyInThread("话题内回复")
        assertTrue(result.ok)
        val out = gw.recordedSends.single().out
        assertTrue(out.replyInThread, "话题标记必须置位")
        assertEquals("话题内回复", out.text)
        assertEquals("om_question_group", out.replyToMessageId, "锚定由统一 send 注入，话题标记不得破坏它")
    }

    @Test
    fun `replyInThread 无锚定时话题标记仍置位`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw, p2pTarget())
        assertTrue(s.replyInThread("正文").ok)
        val out = gw.recordedSends.single().out
        assertTrue(out.replyInThread)
        assertNull(out.replyToMessageId, "未传锚定 ⇒ 字段不填")
    }

    private fun p2pTarget(): LarkTarget = LarkTarget.ofInbound(replyMsg(chatId = "oc_p2p", chatType = ChatType.P2P, messageId = "om_q_p2p"))

    // ── recallMessage（镜像 react：不占令牌桶、直调网关）──

    @Test
    fun `recallMessage 直调网关并返回结果`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw, LarkTarget.ofInbound(groupMsg()))
        assertTrue(s.recallMessage("om_mine"), "SyntheticGateway 默认撤回成功")
        assertEquals(listOf(com.jiaoay.lark.bridge.pipeline.RecordedRecall("om_mine")), gw.recordedRecalls)
        assertEquals(0, gw.recordedSends.size, "撤回不是新出站（无 send 记录）")
    }

    @Test
    fun `recallMessage 失败返回 false 不抛`() = runBlocking {
        val gw = SyntheticGateway()
        gw.recallResult = false
        val s = sink(gw, LarkTarget.ofInbound(groupMsg()))
        assertFalse(s.recallMessage("om_gone"))
    }

    // ── 接口默认实现 = 能力缺席（既有替身零改动的契约背书）──

    @Test
    fun `接口默认实现返回失败（能力缺席契约）`() = runBlocking {
        val bare = object : ReplySink {
            override val target: LarkTarget = LarkTarget.Chat("oc_x")
            override val chatId: String? = "oc_x"
            override suspend fun text(body: String) = SendResult(ok = true, messageId = "m")
            override suspend fun markdown(body: String) = SendResult(ok = true, messageId = "m")
            override suspend fun card(spec: com.jiaoay.lark.gateway.CardSpec) = SendResult(ok = true, messageId = "m")
            override suspend fun file(file: java.nio.file.Path, displayName: String?) = SendResult(ok = true, messageId = "m")
            override fun stream(initial: com.jiaoay.lark.gateway.CardSpec) =
                throw UnsupportedOperationException()
            override suspend fun react(messageId: String, emojiKey: String, add: Boolean) = true
        }
        val imgFail = bare.image(java.nio.file.Path.of("/tmp/whatever.png"))
        assertFalse(imgFail.ok)
        assertEquals(GatewayErrorCode.UploadFailed, imgFail.error, "image 默认实现 = UploadFailed")
        val threadFail = bare.replyInThread("x")
        assertFalse(threadFail.ok)
        val recallFail = bare.recallMessage("om_x")
        assertFalse(recallFail, "recallMessage 默认实现 = false")
    }

    // ── TrackingReplySink 账本扩容（批 B 第 7 条）──

    /** 组装带账本的 TrackingReplySink（delegate 走 SyntheticGateway 同形）。 */
    private fun trackingSink(
        gateway: SyntheticGateway,
        ledger: CardReplyLedger,
        chatId: String?,
        openStreams: java.util.concurrent.CopyOnWriteArrayList<com.jiaoay.lark.gateway.CardStreamSink> =
            java.util.concurrent.CopyOnWriteArrayList(),
    ): TrackingReplySink {
        val delegate = GatewayReplySink(
            gateway = gateway,
            target = LarkTarget.Chat(chatId ?: "oc_unknown"),
            rateGovernor = RateGovernor(),
            netDispatcher = Dispatchers.Default,
        )
        return TrackingReplySink(delegate, openStreams, ledger, chatId)
    }

    @Test
    fun `text 成功落账且 lookup 带本会话校验命中`() = runBlocking {
        val gw = SyntheticGateway()
        val ledger = CardReplyLedger()
        val s = trackingSink(gw, ledger, chatId = "oc_group")
        val result = s.text("一段回复")
        assertTrue(result.ok)
        assertEquals("一段回复", ledger.lookup(result.messageId!!, "oc_group"), "成功回复须落账且本会话可查")
    }

    @Test
    fun `markdown 与 card 成功落账（card 正文带标题前缀）`() = runBlocking {
        val gw = SyntheticGateway()
        // 🔴 SyntheticGateway 默认恒回同一 messageId ⇒ 第二条 record 覆写第一条；按出站种类发不同 id
        gw.sendHandler = { _, out ->
            SendResult(ok = true, messageId = "m_${out.kind.name.lowercase()}")
        }
        val ledger = CardReplyLedger()
        val s = trackingSink(gw, ledger, chatId = "oc_group")
        val md = s.markdown("markdown 正文")
        val card = s.card(com.jiaoay.lark.gateway.CardSpec(title = "分析报告", markdown = "## 结论"))
        assertTrue(md.ok && card.ok)
        assertEquals("markdown 正文", ledger.lookup(md.messageId!!, "oc_group"))
        assertEquals("[卡片] 分析报告\n## 结论", ledger.lookup(card.messageId!!, "oc_group"), "card 正文须带 [卡片] 标题前缀")
    }

    @Test
    fun `file 成功落账（正文带文件名标记）`() = runBlocking {
        val gw = SyntheticGateway()
        val ledger = CardReplyLedger()
        val s = trackingSink(gw, ledger, chatId = "oc_group")
        val f = Files.createTempFile("native-ledger", ".md").apply { Files.write(this, "报告".toByteArray()) }
        val result = s.file(f, "报告.md")
        assertTrue(result.ok)
        assertEquals("[文件消息] 报告.md", ledger.lookup(result.messageId!!, "oc_group"))
    }

    @Test
    fun `失败出站不落账（messageId 缺失或 ok=false）`() = runBlocking {
        val gw = SyntheticGateway()
        gw.sendResult = com.jiaoay.lark.gateway.SendResult(ok = false, messageId = null, error = GatewayErrorCode.SendFailed)
        val ledger = CardReplyLedger()
        val s = trackingSink(gw, ledger, chatId = "oc_group")
        assertFalse(s.text("失败回复").ok)
        s.markdown("失败 markdown")
        assertEquals(0, ledger.size, "失败出站不得落账")
    }

    @Test
    fun `正文超 2000 字截断落账`() = runBlocking {
        val gw = SyntheticGateway()
        val ledger = CardReplyLedger()
        val s = trackingSink(gw, ledger, chatId = "oc_group")
        val long = "长".repeat(3000)
        val result = s.text(long)
        assertTrue(result.ok)
        assertEquals(2000, ledger.lookup(result.messageId!!, "oc_group")?.length, "账本正文须截断到 2000 字")
    }

    @Test
    fun `账本跨会话 miss（oc_b 记的账 oc_group 查不到）`() = runBlocking {
        val gw = SyntheticGateway()
        val ledger = CardReplyLedger()
        val sB = trackingSink(gw, ledger, chatId = "oc_b")
        val result = sB.text("B 会话回复")
        assertTrue(result.ok)
        assertNull(ledger.lookup(result.messageId!!, "oc_group"), "A 会话查不到 B 会话的账")
        assertEquals("B 会话回复", ledger.lookup(result.messageId!!, "oc_b"), "本会话可查")
    }

    @Test
    fun `replyInThread 出站同样落账（四出口扩容面）`() = runBlocking {
        val gw = SyntheticGateway()
        val ledger = CardReplyLedger()
        val s = trackingSink(gw, ledger, chatId = "oc_group")
        val result = s.replyInThread("话题回复")
        assertTrue(result.ok)
        assertEquals("话题回复", ledger.lookup(result.messageId!!, "oc_group"), "replyInThread 出站须落账（recall 守护覆盖它）")
    }
}
