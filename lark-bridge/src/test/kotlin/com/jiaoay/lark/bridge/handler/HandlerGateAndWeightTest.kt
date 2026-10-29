// M4-T8b 测试：7 个 handler 的 rawContentType 门控（S4 真机结论 2）+ 空 content 安全（S4 结论 3）
// + weightFor 分档矩阵（Leader 条件 4 / D11 两级准入的可执行验收）。
// 🔴 S4 真机实测结论（真机环境验证，优先级高于方案原文推断；凭据与事件数据不入库）：
//    - rawContentType 实测取值：text / image / file（image content=markdown，file content=类 XML `<file .../>`）；
//    - 文本匹配类 handler（Reply/Command/Agent/System/Stop）必须先按 rawContentType=="text" 门控，
//      绝不能对 file/image 的 content 做前缀/正则匹配（否则一条 regex 固定回复会被 `<file .../>` 误命中）；
//    - content 可能是空串（群里只 @机器人不带正文，SDK 剥离 mention 后 content=""）；
//    - 门控在本实现里的形态是 `ctx.inboundKind != InboundKind.TEXT`（= rawContentType=="text" 经网关归一化）。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.confirm.ConfirmService
import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.SystemRoute
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink
import com.jiaoay.lark.bridge.pipeline.FakeAgentRunner
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.HandlerWeight
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import com.jiaoay.lark.gateway.CardAction
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMention
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.InboundResource
import com.jiaoay.lark.gateway.LarkTarget
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 直接构造 [RequestContext]（不经全链路 Harness），只喂 match/weightFor 所需的 message + config。 */
private fun ctxOf(
    message: InboundMessage,
    config: BridgeConfig = testConfig(),
): RequestContext = RequestContext(
    message = message,
    config = config,
    route = null,
    reply = RecordingReplySink(LarkTarget.ofInbound(message)),
    progress = ConflatedProgressSink(),
    log = BridgeLog.Silent,
    agent = FakeAgentRunner(),
    processes = ProcessRegistry(config.runtime.maxConcurrentAgents),
)

private fun depsOf(config: BridgeConfig = testConfig()): HandlerDeps = HandlerDeps({ config }, BridgeLog.Silent)

/** S4 实测：file 消息 content 是类 XML `<file key="..." name="..."/>`（用户把 webp 作为文件发也是 file）。 */
private fun fileMsg(
    messageId: String = "om_file",
    content: String = """<file key="k" name="a.log"/>""",
): InboundMessage = InboundMessage(
    messageId = messageId,
    chatId = "oc_test",
    chatType = ChatType.P2P,
    senderId = "ou_alice",
    senderName = "",
    text = content,
    kind = InboundKind.FILE,
    rawContentType = "file",
    resources = listOf(InboundResource(type = "file", fileKey = "k", fileName = "a.log", durationMs = 0L)),
    mentions = emptyList(),
    mentionAll = false,
    mentionedBot = false,
    rootId = null,
    threadId = null,
    replyToMessageId = null,
    createTimeMillis = System.currentTimeMillis(),
)

/** S4 实测：image 消息 content 是 markdown `![image](img_v3_...)`（🔴 image ≠ file，不归 FileHandler）。 */
private fun imageMsg(messageId: String = "om_image"): InboundMessage = InboundMessage(
    messageId = messageId,
    chatId = "oc_test",
    chatType = ChatType.P2P,
    senderId = "ou_alice",
    senderName = "",
    text = "![image](img_v3_abc)",
    kind = InboundKind.IMAGE,
    rawContentType = "image",
    resources = listOf(InboundResource(type = "image", fileKey = "img_v3_abc", fileName = null, durationMs = 0L)),
    mentions = emptyList(),
    mentionAll = false,
    mentionedBot = false,
    rootId = null,
    threadId = null,
    replyToMessageId = null,
    createTimeMillis = System.currentTimeMillis(),
)

/** S4 实测：群里只 @机器人不带正文 ⇒ SDK 剥离 mention 后 content="" 且 mentionedBot=true。 */
private fun emptyGroupMsg(messageId: String = "om_empty"): InboundMessage = InboundMessage(
    messageId = messageId,
    chatId = "oc_group",
    chatType = ChatType.GROUP,
    senderId = "ou_alice",
    senderName = "",
    text = "",
    kind = InboundKind.TEXT,
    rawContentType = "text",
    resources = emptyList(),
    mentions = listOf(InboundMention(key = "@_user_1", openId = "ou_bot", userId = null, name = "咱的外置大脑")),
    mentionAll = false,
    mentionedBot = true,
    rootId = null,
    threadId = null,
    replyToMessageId = null,
    createTimeMillis = System.currentTimeMillis(),
)

private fun textMsg(text: String, messageId: String = "om_text", config: BridgeConfig? = null): InboundMessage =
    msg(text = text, messageId = messageId, senderName = "")

private fun cardActionMsg(value: String, messageId: String = "om_card"): InboundMessage = InboundMessage(
    messageId = messageId,
    chatId = "oc_test",
    chatType = ChatType.P2P,
    senderId = "ou_alice",
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
        operatorId = "ou_alice",
        actionTag = "button",
        actionName = null,
        actionOption = null,
        actionValue = value,
    ),
)

// =====================================================================================
// S4 结论 2：rawContentType 门控——文本匹配类 handler 绝不碰 file/image 的 content
// =====================================================================================
class RawContentTypeGateTest {

    @Test
    fun `🔴 file 消息（content 是类 XML）⇒ 文本匹配 handler 全不命中，仅 FileHandler 承接`() {
        val deps = depsOf()
        val msg = fileMsg()
        val ctx = ctxOf(msg)

        // 别名 `<file` 若无门控会被最长前缀命中
        val command = CommandHandler(CommandRoute(id = "c", name = "c", aliases = listOf("<file"), promptTemplate = "p {args}"), deps)
        // 触发前缀 `<file` 若无门控会被命中（P3：前缀数据源 = EngineReservedWords 常量，此处验证门控不受影响）
        val agent = AgentHandler(AgentRoute(id = "a"), deps)
        val system = SystemHandler(deps)
        val stop = StopHandler(null, deps)
        val file = FileHandler(FileRoute(), deps)

        assertFalse(command.match(ctx), "命令别名不得命中 file 的 content")
        assertFalse(agent.match(ctx), "agent 触发前缀不得命中 file 的 content")
        assertFalse(system.match(ctx), "系统自证不得命中 file")
        assertFalse(stop.match(ctx), "停止指令不得命中 file")
        assertTrue(file.match(ctx), "file 消息必须由 FileHandler 承接")
    }

    @Test
    fun `🔴 image 消息保持旧行为：不归 FileHandler，也不被任何文本 handler 命中（走未命中兜底）`() {
        val deps = depsOf()
        val ctx = ctxOf(imageMsg())

        val command = CommandHandler(CommandRoute(id = "c", name = "c", aliases = listOf("![image"), promptTemplate = "p"), deps)
        val agent = AgentHandler(AgentRoute(id = "a"), deps)
        val system = SystemHandler(deps)
        val stop = StopHandler(null, deps)
        val file = FileHandler(FileRoute(), deps)

        // 🔴 image ≠ file：刻意保留旧行为（方案与旧实现都是 messageType==file 才走文件链路），
        //    「全功能平移不增不减」⇒ 不把 image 接进文件分析，让它走未命中兜底静默留痕。
        assertFalse(file.match(ctx), "image 消息刻意不归 FileHandler（保留旧行为）")
        assertFalse(command.match(ctx))
        assertFalse(agent.match(ctx))
        assertFalse(system.match(ctx))
        assertFalse(stop.match(ctx))
    }

    @Test
    fun `门控只认归一化 inboundKind：TEXT 才可能命中文本 handler，FILE_IMAGE 一律短路`() {
        val deps = depsOf()
        val command = CommandHandler(CommandRoute(id = "rp", name = "rp", aliases = listOf("ping"), promptTemplate = "p"), deps)
        // 同一段文本 "ping"：kind=TEXT 命中；kind=FILE 即便文本相同也不命中（门控优先于内容比较）
        val asText = ctxOf(textMsg("ping"))
        val asFile = ctxOf(
            InboundMessage(
                messageId = "om_x", chatId = "oc_test", chatType = ChatType.P2P, senderId = "ou_a", senderName = "",
                text = "ping", kind = InboundKind.FILE, rawContentType = "file",
                resources = listOf(InboundResource(type = "file", fileKey = "k", fileName = "a.log", durationMs = 0L)),
                mentions = emptyList(), mentionAll = false, mentionedBot = false,
                rootId = null, threadId = null, replyToMessageId = null, createTimeMillis = System.currentTimeMillis(),
            ),
        )
        assertTrue(command.match(asText))
        assertFalse(command.match(asFile))
    }
}

// =====================================================================================
// S4 结论 3：content 空串安全——群里只 @机器人不带正文
// =====================================================================================
class EmptyContentGateTest {

    @Test
    fun `🔴 content 为空串的群 @机器人消息 ⇒ handler 全不命中且不抛异常（走未命中兜底）`() {
        val deps = depsOf()
        val ctx = ctxOf(emptyGroupMsg())

        val system = SystemHandler(deps)
        val stop = StopHandler(null, deps)
        val command = CommandHandler(CommandRoute(id = "c", name = "c", aliases = listOf("ai", "/help"), promptTemplate = ""), deps)
        val interaction = InteractionHandler(null, deps)
        val agent = AgentHandler(AgentRoute(id = "a"), deps)
        val file = FileHandler(FileRoute(), deps)

        // 全部 match() 都不得抛异常（空串下的 substring/first/split 越界是旧实现常见坑）
        assertFalse(system.match(ctx), "空串不得被当成 ping 的前缀")
        assertFalse(stop.match(ctx), "空串 != 停止指令")
        assertFalse(command.match(ctx), "空串首字符边界校验必须安全返回 false")
        assertFalse(interaction.match(ctx), "空串不匹配裁决正则")
        assertFalse(agent.match(ctx), "空串不得命中 agent 触发前缀")
        assertFalse(file.match(ctx), "空串是 TEXT，不归 FileHandler")
    }

    @Test
    fun `空串下 CommandHandler 的最长别名前缀 + 首字符边界校验不越界`() {
        val deps = depsOf()
        val command = CommandHandler(CommandRoute(id = "c", name = "c", aliases = listOf("a", "ai", "/x"), promptTemplate = "p"), deps)
        val ctx = ctxOf(textMsg(""))
        assertFalse(command.match(ctx))
        // weightFor 同样纯 CPU、空串安全（matchedAlias("") 返回 null ⇒ LIGHT）
        assertEquals(HandlerWeight.LIGHT, command.weightFor(ctx))
    }

    @Test
    fun `空串下 AgentHandler 与 SystemHandler 的 trim-isEmpty 早返回`() {
        val deps = depsOf()
        assertFalse(SystemHandler(deps).match(ctxOf(textMsg("   "))))
        assertFalse(AgentHandler(AgentRoute(id = "a"), deps).match(ctxOf(textMsg("   "))))
    }
}

// =====================================================================================
// Leader 条件 4：weightFor 分档矩阵（D11 两级准入的可执行验收）
// =====================================================================================
class HandlerWeightMatrixTest {

    @Test
    fun `停止指令 ⇒ StopHandler URGENT`() {
        // P3：AgentRoute.stopCommand 已退役——停止词真源 = EngineReservedWords（常量「停止」），
        // config 无需携带旧词；StopHandler 每消息现读保留词表。
        val config = testConfig(routes = listOf(AgentRoute()))
        val stop = StopHandler(null, depsOf(config))
        val ctx = ctxOf(textMsg("停止"), config)
        assertTrue(stop.match(ctx))
        assertEquals(HandlerWeight.URGENT, stop.weightFor(ctx))
        assertEquals(HandlerWeight.URGENT, stop.weight, "静态档位也是 URGENT")
    }

    @Test
    fun `ping 与 commands 清单 ⇒ SystemHandler LIGHT`() {
        val system = SystemHandler(depsOf())
        assertEquals(HandlerWeight.LIGHT, system.weightFor(ctxOf(textMsg("ping"))))
        assertEquals(HandlerWeight.LIGHT, system.weightFor(ctxOf(textMsg("/commands"))))
        assertTrue(system.match(ctxOf(textMsg("ping"))))
        assertTrue(system.match(ctxOf(textMsg("/commands"))))
    }

    // =====================================================================================
    // /whoami（2026-09-09 产品新增）：回显 openId 与 chatId，解决「填白名单需先知道自己的 openId」
    // =====================================================================================

    @Test
    fun `whoami 精确匹配且带参数不命中`() = runBlocking {
        val system = SystemHandler(depsOf())
        assertTrue(system.match(ctxOf(textMsg("/whoami"))), "`/whoami` 须命中 SystemHandler")
        assertFalse(system.match(ctxOf(textMsg("/whoami x"))), "带参数属误触，不得命中")
        assertFalse(system.match(ctxOf(textMsg("whoami"))), "无斜杠不命中")
        assertTrue(system.match(ctxOf(textMsg("  /whoami  "))), "前后空白 trim 后须命中")
    }

    @Test
    fun `whoami 回复含发送者 openId 与会话 chatId 及填白名单指引`() = runBlocking {
        val system = SystemHandler(depsOf())
        val outcome = system.handle(ctxOf(textMsg("/whoami")))
        val text = com.jiaoay.lark.bridge.handler.Responder.render(outcome)
        assertTrue(text.contains("ou_alice"), "回复须含发送者 openId")
        assertTrue(text.contains("oc_test"), "回复须含会话 chatId")
        assertTrue(text.contains("允许的发送者"), "须含填白名单指引")
    }

    @Test
    fun `commands 清单含 whoami 条目`() = runBlocking {
        val system = SystemHandler(depsOf())
        val outcome = system.handle(ctxOf(textMsg("/commands")))
        val text = com.jiaoay.lark.bridge.handler.Responder.render(outcome)
        assertTrue(text.contains("/whoami"), "指令清单须含 /whoami 条目")
    }

    @Test
    fun `卡片回传 ⇒ InteractionHandler LIGHT`() {
        val interaction = InteractionHandler(null, depsOf())
        val ctx = ctxOf(cardActionMsg("abcd1234:allow"))
        assertTrue(interaction.match(ctx))
        assertEquals(HandlerWeight.LIGHT, interaction.weightFor(ctx))
        // 🔴 LIGHT 而非 URGENT：URGENT 的 cancelAll 会杀掉正在等这次裁决的 agent（设计裁定，方案 line196 笔误）
        assertEquals(HandlerWeight.LIGHT, interaction.weight)
    }

    @Test
    fun `确认-拒绝文本裁决 ⇒ InteractionHandler LIGHT`() {
        val interaction = InteractionHandler(null, depsOf())
        val approve = ctxOf(textMsg("确认 abcd1234"))
        val deny = ctxOf(textMsg("拒绝 abcd1234"))
        assertTrue(interaction.match(approve))
        assertTrue(interaction.match(deny))
        assertEquals(HandlerWeight.LIGHT, interaction.weightFor(approve))
        assertEquals(HandlerWeight.LIGHT, interaction.weightFor(deny))
    }

    @Test
    fun `命令清单查询（promptTemplate 空白）⇒ CommandHandler LIGHT`() {
        val command = CommandHandler(CommandRoute(id = "c", name = "帮助", aliases = listOf("/help"), promptTemplate = ""), depsOf())
        val ctx = ctxOf(textMsg("/help"))
        assertTrue(command.match(ctx))
        assertEquals(HandlerWeight.LIGHT, command.weightFor(ctx), "模板空白 ⇒ 纯信息查询不跑 agent ⇒ LIGHT")
    }

    @Test
    fun `命令委托 agent（promptTemplate 非空）⇒ CommandHandler HEAVY`() {
        val command = CommandHandler(
            CommandRoute(id = "c", name = "修复", aliases = listOf("/fix"), promptTemplate = "请修复：{args}"),
            depsOf(),
        )
        val ctx = ctxOf(textMsg("/fix 空指针"))
        assertTrue(command.match(ctx))
        assertEquals(HandlerWeight.HEAVY, command.weightFor(ctx), "模板非空 ⇒ 委托 agent ⇒ HEAVY")
    }

    @Test
    fun `agent 前缀 ⇒ AgentHandler HEAVY`() {
        // P3：AgentRoute 匹配键退役——前缀数据源 = EngineReservedWords 常量（"ai"/"/ai" 仍在）。
        val agent = AgentHandler(AgentRoute(id = "a"), depsOf())
        val ctx = ctxOf(textMsg("ai 干活"))
        assertTrue(agent.match(ctx))
        assertEquals(HandlerWeight.HEAVY, agent.weightFor(ctx))
        assertEquals(HandlerWeight.HEAVY, agent.weight)
    }

    @Test
    fun `文件消息 ⇒ FileHandler LIGHT（2026-09-11 引用流重设计：仅登记+回执）`() {
        val file = FileHandler(FileRoute(), depsOf())
        val ctx = ctxOf(fileMsg())
        assertTrue(file.match(ctx))
        assertEquals(HandlerWeight.LIGHT, file.weightFor(ctx))
        assertEquals(HandlerWeight.LIGHT, file.weight)
    }

    @Test
    fun `清单指令（模板空白）⇒ CommandHandler LIGHT`() {
        val command = CommandHandler(CommandRoute(id = "c", name = "清单", aliases = listOf("ping"), promptTemplate = ""), depsOf())
        val ctx = ctxOf(textMsg("ping"))
        assertTrue(command.match(ctx))
        assertEquals(HandlerWeight.LIGHT, command.weightFor(ctx))
    }

    @Test
    fun `未覆写 weightFor 的 handler 退回静态 weight（默认实现契约）`() {
        // StopHandler / AgentHandler / FileHandler / SystemHandler / InteractionHandler 均不覆写 ⇒ weightFor==weight
        val deps = depsOf()
        assertEquals(SystemHandler(deps).weight, SystemHandler(deps).weightFor(ctxOf(textMsg("ping"))))
        assertEquals(FileHandler(FileRoute(), deps).weight,
            FileHandler(FileRoute(), deps).weightFor(ctxOf(fileMsg())))
        assertEquals(AgentHandler(AgentRoute(), deps).weight, AgentHandler(AgentRoute(), deps).weightFor(ctxOf(textMsg("ai x"))))
    }

    @Test
    fun `🔴 SystemRoute 缺省 ⇒ StopHandler 退回 DEFAULT_STOP（stopCommand 从当前配置解析，禁装配期缓存）`() {
        // testConfig 默认 routes=listOf(SystemRoute())，无 AgentRoute ⇒ stopCommand 退回「停止」
        val stop = StopHandler(null, depsOf())
        val ctx = ctxOf(textMsg("停止"))
        assertTrue(stop.match(ctx))
        assertEquals(HandlerWeight.URGENT, stop.weightFor(ctx))
    }
}

// =====================================================================================
// 2026-09-15 确认卡已答态批：裁决成功 ⇒ 原确认卡整卡替换为已答态（answeredQuestionCard 已答卡语义）
// =====================================================================================
class InteractionAnsweredCardTest {

    private fun depsOf(config: BridgeConfig = testConfig()): HandlerDeps = HandlerDeps({ config }, BridgeLog.Silent)

    /** ctxOf 是文件级私有——本类内复制一份（带出站断言所需 reply 引用）。 */
    private fun ctxWithReply(message: InboundMessage): Pair<RequestContext, RecordingReplySink> {
        val reply = RecordingReplySink(LarkTarget.ofInbound(message))
        val ctx = RequestContext(
            message = message,
            config = testConfig(),
            route = null,
            reply = reply,
            progress = ConflatedProgressSink(),
            log = BridgeLog.Silent,
            agent = FakeAgentRunner(),
            processes = ProcessRegistry(testConfig().runtime.maxConcurrentAgents),
        )
        return ctx to reply
    }

    @Test
    fun `🔴 裁决成功且卡已回填 ⇒ 原卡更新为已答态（无按钮 + 结果标注 + 原上下文）`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_test", "ou_alice", "Bash", "rm -rf build", effectiveTimeoutMillis = 5_000)
        service.recordCardMessageId(request.requestId, "om_confirm_card_1")
        // 卡片按钮回传：value = "<token>:allow"（token 是 requestId 短标识段）
        val (ctx, reply) = ctxWithReply(cardActionMsg("${request.requestId.substringAfterLast('-')}:allow"))
        val handler = InteractionHandler(service, depsOf())
        val outcome = handler.handle(ctx)
        // 文字回执照发（增强不替代）
        assertTrue(outcome.status == TaskStatus.SUCCESS, "裁决应成功：$outcome")
        assertEquals(1, reply.cardUpdates.size, "已答态更新应恰好一次")
        val (messageId, spec) = reply.cardUpdates.single()
        assertEquals("om_confirm_card_1", messageId, "更新的必须是原确认卡")
        assertEquals("✅ 已批准", spec.title, "已答态标题（批准=绿）")
        assertTrue(spec.buttons.isEmpty(), "🔴 已答态必须无按钮（消灭陈旧按钮）")
        assertFalse(spec.streaming, "已答态绝不能进流式通道（200810）")
        assertTrue(spec.markdown.contains("已批准"), "正文应含裁决结果：${spec.markdown}")
        assertTrue(spec.markdown.contains("Bash"), "正文应保留原工具名：${spec.markdown}")
        assertTrue(spec.markdown.contains("rm -rf build"), "正文应保留原参数摘要：${spec.markdown}")
    }

    /** 🔴 批 ④（屏④「单据：cf-a3f2」直译）：已答态正文末行补短标识对账行。 */
    @Test
    fun `🔴 批④ 已答态正文含单据短标识对账行`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_test", "ou_alice", "Bash", "rm -rf build", effectiveTimeoutMillis = 5_000)
        service.recordCardMessageId(request.requestId, "om_confirm_card_3")
        val (ctx, reply) = ctxWithReply(cardActionMsg("${request.requestId.substringAfterLast('-')}:allow"))
        val outcome = InteractionHandler(service, depsOf()).handle(ctx)
        assertTrue(outcome.status == TaskStatus.SUCCESS, "裁决应成功：$outcome")
        val spec = reply.cardUpdates.single().second
        val shortId = ConfirmService.shortId(request.requestId)
        assertTrue(
            spec.markdown.contains("单据：cf-$shortId（事后对账短标识）"),
            "正文应含短标识对账行（cf- 前缀 + shortId）：${spec.markdown}",
        )
    }

    @Test
    fun `🔴 裁决成功但卡未回填 ⇒ 跳过已答态更新（行为同旧版）`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_test", "ou_alice", "Bash", "x", effectiveTimeoutMillis = 5_000)
        // 不 recordCardMessageId（模拟 SynθtheticGateway 默认实现 / 发卡失败）
        val (ctx, reply) = ctxWithReply(cardActionMsg("${request.requestId.substringAfterLast('-')}:deny"))
        val outcome = InteractionHandler(service, depsOf()).handle(ctx)
        assertTrue(outcome.status == TaskStatus.SUCCESS, "裁决应成功：$outcome")
        assertEquals(0, reply.cardUpdates.size, "未回填 ⇒ 不做已答态更新")
        // 文字回执语义仍在（outcome 的 summary 会由管线 Reply 阶段发出——handler 层只产出语义）
        assertTrue(outcome.summary.contains("已拒绝"), "裁决语义不因卡未回填而消失：${outcome.summary}")
    }

    @Test
    fun `🔴 NotFound 与 Forbidden 不触发已答态更新`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_test", "ou_alice", "Bash", "x", effectiveTimeoutMillis = 5_000)
        service.recordCardMessageId(request.requestId, "om_confirm_card_1")
        val handler = InteractionHandler(service, depsOf())
        // NotFound：未知短标识
        val (ctx1, reply1) = ctxWithReply(cardActionMsg("ffff:allow"))
        handler.handle(ctx1)
        assertEquals(0, reply1.cardUpdates.size, "NotFound 不得更新卡")
        // Forbidden：非原发送者（cardActionMsg 的 operatorId=ou_alice，用别的 pending + 别的 sender 裁决）
        val (ctx2, reply2) = ctxWithReply(cardActionMsg("${request.requestId.substringAfterLast('-')}:allow").let {
            it.copy(senderId = "ou_other") // 防替答：senderId 不匹配 ⇒ Forbidden
        })
        handler.handle(ctx2)
        assertEquals(0, reply2.cardUpdates.size, "Forbidden 不得更新卡")
    }

    @Test
    fun `🔴 文本裁决通道同样触发已答态更新（按钮与文字两路一致）`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_test", "ou_alice", "Bash", "x", effectiveTimeoutMillis = 5_000)
        service.recordCardMessageId(request.requestId, "om_confirm_card_2")
        val (ctx, reply) = ctxWithReply(textMsg("确认 ${request.requestId.substringAfterLast('-')}"))
        val outcome = InteractionHandler(service, depsOf()).handle(ctx)
        assertTrue(outcome.status == TaskStatus.SUCCESS)
        assertEquals(1, reply.cardUpdates.size, "文字裁决同样更新原卡")
        val spec = reply.cardUpdates.single().second
        assertEquals("✅ 已批准", spec.title)
        assertTrue(spec.buttons.isEmpty())
    }

    @Test
    fun `🔴 已答态更新抛异常 ⇒ 降级不阻断（裁决仍成功）`() = runBlocking {
        val service = ConfirmService()
        val request = service.newRequest("oc_test", "ou_alice", "Bash", "x", effectiveTimeoutMillis = 5_000)
        service.recordCardMessageId(request.requestId, "om_confirm_card_1")
        val message = cardActionMsg("${request.requestId.substringAfterLast('-')}:allow")
        // 委托 RecordingReplySink 但 updateExistingCard 抛异常（更新失败路径）——runCatching 兜底语义直测
        val recorder = RecordingReplySink(LarkTarget.ofInbound(message))
        val throwingSink = object : com.jiaoay.lark.bridge.pipeline.ReplySink by recorder {
            override suspend fun updateExistingCard(messageId: String, spec: com.jiaoay.lark.gateway.CardSpec): com.jiaoay.lark.gateway.SendResult =
                throw IllegalStateException("synthetic patch failure")
        }
        val ctx = RequestContext(
            message = message, config = testConfig(), route = null,
            reply = throwingSink,
            progress = ConflatedProgressSink(), log = BridgeLog.Silent,
            agent = FakeAgentRunner(), processes = ProcessRegistry(testConfig().runtime.maxConcurrentAgents),
        )
        val outcome = InteractionHandler(service, depsOf()).handle(ctx)
        assertTrue(outcome.status == TaskStatus.SUCCESS, "🔴 卡更新失败必须降级——裁决仍成功：$outcome")
    }
}
