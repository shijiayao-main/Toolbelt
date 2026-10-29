// 出站单写点文本过滤断言：GatewayReplySink 四文本成员 + 流式卡面（受理卡/运行帧/终态卡）
// + 已答卡更新（updateExistingCard 正文承载 inputSummary 回显，属真实出站文本）全部经同一
// 文本过滤入口（SensitiveFilePolicy.sanitizeOutboundText），file/image/react/recallMessage
// 维持既有语义零改动。工具直答、终答摘要、CLI 委托 summary 的出站端样本全部经本口兜住
// （上下文回灌段属上游，不在本口范围）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.TrackingReplySink
import com.jiaoay.lark.bridge.dispatch.BridgeDispatchers
import com.jiaoay.lark.bridge.exec.SensitiveFilePolicy
import com.jiaoay.lark.bridge.files.CardReplyLedger
import com.jiaoay.lark.gateway.CardSpec
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.LarkTarget
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ReplySinkTextFilterTest {

    private val dataRoot = File(System.getProperty("java.io.tmpdir"), "reply-filter-data-root")

    private fun groupMsg(): InboundMessage = InboundMessage(
        messageId = "om_q",
        chatId = "oc_group",
        chatType = ChatType.GROUP,
        senderId = "ou_user",
        senderName = "",
        text = "hi",
        kind = InboundKind.TEXT,
        rawContentType = "text",
        resources = emptyList(),
        mentions = emptyList(),
        mentionAll = false,
        mentionedBot = false,
        rootId = null,
        threadId = null,
        replyToMessageId = null,
        createTimeMillis = 0L,
    )

    private fun sink(
        gateway: SyntheticGateway,
        homeDataRoot: File? = dataRoot,
        replyToMessageId: String? = null,
    ): GatewayReplySink = GatewayReplySink(
        gateway = gateway,
        target = LarkTarget.ofInbound(groupMsg()),
        rateGovernor = RateGovernor(),
        netDispatcher = Dispatchers.Default,
        replyToMessageId = replyToMessageId,
        homeDataRoot = homeDataRoot,
    )

    // ── 四文本成员：各一条命中样本断言出站已打码（单写点全覆盖）──

    @Test
    fun `text 成员命中打码且发送语义不变`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        val result = s.text("配置在 ~/.easy_dev_toolbox/lark/bridge-config.json")
        assertTrue(result.ok, "打码是内容变换不是交付失败，ok 语义不变")
        val out = gw.recordedSends.single().out.text!!
        assertFalse(out.contains("easy_dev_toolbox"), "出站文本不得残留机密根：$out")
        assertTrue(out.endsWith(SensitiveFilePolicy.TEXT_REDACTED_HINT))
    }

    @Test
    fun `markdown 成员命中打码`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        assertTrue(s.markdown("## 报告\n私钥 /home/u/.ssh/id_rsa 已轮换").ok)
        val out = gw.recordedSends.single().out.text!!
        assertFalse(out.contains("id_rsa"), "markdown 出站不得残留凭据名：$out")
        assertTrue(out.contains("## 报告"), "非命中段保留")
    }

    @Test
    fun `card 成员 title 与 markdown 双面打码`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        val result = s.card(
            CardSpec(
                title = "报告 ~/.easy_dev_toolbox/x 就绪",
                markdown = "结论：密钥在 /home/u/.ssh/id_rsa，见 ~/.easy_dev_toolbox/lark/audit.jsonl",
            ),
        )
        assertTrue(result.ok)
        val card = gw.recordedSends.single().out.card!!
        assertFalse(card.title.contains("easy_dev_toolbox"), "卡片标题须打码：${card.title}")
        assertFalse(card.markdown.contains("id_rsa"), "卡片正文须打码：${card.markdown}")
        assertFalse(card.markdown.contains("easy_dev_toolbox"), "机密根子路径须打码：${card.markdown}")
    }

    @Test
    fun `replyInThread 成员命中打码且话题标记保留`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw, replyToMessageId = "om_q")
        val result = s.replyInThread("话题内结论：证书在 /etc/certs/server.pem")
        assertTrue(result.ok)
        val out = gw.recordedSends.single().out
        assertTrue(out.replyInThread, "话题标记不得被过滤破坏")
        assertEquals("om_q", out.replyToMessageId, "锚定不得被过滤破坏")
        assertFalse(out.text!!.contains("server.pem"), "话题内回复须打码：${out.text}")
    }

    @Test
    fun `无命中文本原样出站（含普通路径与日志样例）`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        val clean = "10-18 14:23:01.234 E Cam: open /data/vendor/camera/config.bin failed"
        assertTrue(s.text(clean).ok)
        assertSame(clean, gw.recordedSends.single().out.text, "普通路径与日志样例零打码原样出站")
    }

    @Test
    fun `homeDataRoot 为 null 时凭据名仍过滤机密根判据跳过`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw, homeDataRoot = null)
        assertTrue(s.text("私钥 /home/u/.ssh/id_rsa").ok)
        assertFalse(gw.recordedSends.single().out.text!!.contains("id_rsa"), "凭据名判据不依赖宿主根注入")
    }

    // ── 文本成员之外的出站面：既有语义零改动 ──

    @Test
    fun `file 与 image 成员照旧走文件通道不受文本过滤影响`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        val f = Files.createTempFile("reply-filter", ".md").apply { Files.write(this, "报告".toByteArray()) }
        val img = Files.createTempFile("reply-filter", ".png").apply { Files.write(this, byteArrayOf(1)) }
        assertTrue(s.file(f, "报告.md").ok, "文件通道既有语义不变")
        assertTrue(s.image(img).ok, "图片通道既有语义不变")
        assertEquals(2, gw.recordedSends.size)
        assertEquals(1, gw.recordedUploads.size)
        assertEquals(1, gw.recordedImageUploads.size)
    }

    @Test
    fun `react 与 recallMessage 不占 send 出站也不受文本过滤影响`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        assertTrue(s.react("om_mine", "THUMBSUP"))
        assertTrue(s.recallMessage("om_mine"))
        assertEquals(0, gw.recordedSends.size, "react/recall 不是新出站")
    }

    // ── 已答卡更新：inputSummary 回显属真实出站文本面，经同一过滤入口 ──

    @Test
    fun `updateExistingCard 已答卡正文命中打码且普通文本原样放行`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        // 裁决后的已答卡回显用户请求命令（inputSummary 语义样本）——正文命中机密根须打码
        val result = s.updateExistingCard(
            "om_card",
            CardSpec(title = "已裁决", markdown = "请求命令：cat ~/.easy_dev_toolbox/lark/bridge-config.json"),
        )
        assertTrue(result.ok, "打码不构成更新失败")
        val updated = gw.recordedCardUpdates.single().spec
        assertFalse(updated.markdown.contains("easy_dev_toolbox"), "已答卡正文须打码：${updated.markdown}")
        assertTrue(updated.markdown.contains("请求命令"), "非命中段保留")
        assertEquals("已裁决", updated.title, "无命中标题原样保留")

        // 普通文本放行：无命中的已答卡原样透传（引用不变，与其余文本成员同语义）
        val clean = CardSpec(title = "已裁决", markdown = "命令已被用户拒绝，未执行任何文件操作")
        assertTrue(s.updateExistingCard("om_card2", clean).ok)
        assertSame(clean, gw.recordedCardUpdates.last().spec, "无命中原样透传不复制")
    }

    // ── 打码后账本落账不受影响（TrackingReplySink 装饰链）──

    @Test
    fun `打码后账本照常落账且出站已打码`() = runBlocking {
        val gw = SyntheticGateway()
        val ledger = CardReplyLedger()
        val tracking = TrackingReplySink(
            sink(gw),
            CopyOnWriteArrayList(),
            ledger,
            chatId = "oc_group",
        )
        val body = "配置在 ~/.easy_dev_toolbox/lark/bridge-config.json"
        val result = tracking.text(body)
        assertTrue(result.ok, "打码不构成交付失败")
        val out = gw.recordedSends.single().out.text!!
        assertFalse(out.contains("easy_dev_toolbox"))
        assertTrue(
            !ledger.lookup(result.messageId!!, "oc_group").isNullOrEmpty(),
            "打码不阻断账本落账（recall 守护/引用兜底的账本前提不受影响）",
        )
    }

    // ── replyUnhandled 兜底：经 markdown 成员天然过过滤入口 ──

    @Test
    fun `replyUnhandled 兜底文本经同一过滤入口`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        assertTrue(s.replyUnhandled("未支持的消息类型：原文 ~/.easy_dev_toolbox/lark/x.json 已收到"))
        val out = gw.recordedSends.single().out.text!!
        assertFalse(out.contains("easy_dev_toolbox"), "兜底出站同样被过滤入口兜住：$out")
    }

    // ── EventIntake 路径（RequestContextFactory 构造的 sink 同样带过滤）──

    @Test
    fun `EventIntake 路径构造的 sink 同样过滤出站文本`() = runBlocking {
        val gw = SyntheticGateway()
        val contexts = RequestContextFactory(
            configProvider = { testConfig() },
            gateway = gw,
            rateGovernor = RateGovernor(),
            dispatchers = BridgeDispatchers.all(Dispatchers.Default),
            agent = FakeAgentRunner(),
            processes = com.jiaoay.lark.bridge.exec.ProcessRegistry(2),
            homeDataRoot = dataRoot,
        )
        val reply = contexts.create(groupMsg()).reply
        assertTrue(reply.text("配置在 ~/.easy_dev_toolbox/lark/bridge-config.json").ok)
        val out = gw.recordedSends.single().out.text!!
        assertFalse(out.contains("easy_dev_toolbox"), "工厂路径的出站文本同样被过滤：$out")
    }

    // ── 流式卡面：受理卡/运行帧/终态卡全部经同一过滤入口 ──

    @Test
    fun `流式卡受理卡与运行帧与终态卡全部打码`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        val streamSink = s.stream(
            CardSpec(title = "进度 ~/.easy_dev_toolbox/x", markdown = "> 📝 正在处理"),
        )
        val recorded = gw.recordedStreams.single()
        assertFalse(recorded.initial.title.contains("easy_dev_toolbox"), "受理卡标题须打码：${recorded.initial.title}")
        assertTrue(recorded.initial.markdown == "> 📝 正在处理", "无命中正文原样保留")

        assertTrue(streamSink.tryReplace("> 🔧 工具 ~/.easy_dev_toolbox/lark/bridge-config.json"))
        assertFalse(recorded.latest().contains("easy_dev_toolbox"), "运行帧须打码：${recorded.latest()}")

        streamSink.flushTerminal(CardSpec(title = "完成 /home/u/.ssh/id_rsa", markdown = "结果见回复"))
        val terminal = recorded.terminal!!
        assertFalse(terminal.title.contains("id_rsa"), "终态卡标题须打码：${terminal.title}")

        streamSink.close()
        assertTrue(recorded.closedTimes >= 1, "装饰器纯委托：close 正常透传")
        assertFalse(streamSink.isOpen, "isOpen 正常透传（已发终态卡）")
        assertEquals(1, streamSink.segmentIndex, "segmentIndex 正常透传")
    }

    // ── 缺口样本：CLI 委托 summary 与 HandlerOutcome 终答的出站端被兜住 ──

    @Test
    fun `CLI 委托 summary 含机密根路径经 reply 直答出站已打码`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        // CLI 委托 stdout 尾链样本（超长尾段 + 机密根命中）：出站端（reply_direct 的文本面）兜住即达标
        val summary = "委托完成。stdout 尾部：\n" +
            "读取配置 ~/.easy_dev_toolbox/lark/bridge-config.json 成功\n" +
            "内容行 x".repeat(200)
        assertTrue(s.text(summary).ok)
        val out = gw.recordedSends.single().out.text!!
        assertFalse(out.contains("easy_dev_toolbox"), "CLI 委托 summary 的出站文本须打码：$out")
        assertTrue(out.contains("内容行 x"), "超长尾段其余内容保留")
    }

    @Test
    fun `HandlerOutcome 终答摘要经 reply 通道出站已打码`() = runBlocking {
        val gw = SyntheticGateway()
        val s = sink(gw)
        val finalSummary = "分析完成：崩溃前读取了 /home/u/project/.env 与 ~/.easy_dev_toolbox/lark/audit.jsonl"
        assertTrue(s.markdown(finalSummary).ok)
        val out = gw.recordedSends.single().out.text!!
        assertFalse(out.contains(".env"), "终答摘要的凭据名须打码：$out")
        assertFalse(out.contains("easy_dev_toolbox"), "终答摘要的机密根须打码：$out")
    }

    // ── 文件面 OPAQUE oracle：文本面落地不回退文件面语义 ──

    @Test
    fun `文本面落地不改变 OPAQUE 存在性收窄的既有文案`() {
        // oracle 与文本面互补不重叠：文件面收窄文案逐字不变（判据序零改动）
        assertEquals(
            "该路径不可交付（不在本消息的可发送范围内）。请检查路径，或改用文字答复。",
            SensitiveFilePolicy.OPAQUE_DENIED_HINT,
        )
    }
}
