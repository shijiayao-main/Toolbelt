// 引用流重设计（2026-09-11）端到端行为测试——取代旧 FileGateTest（关键词门控机制已整体删除）。
// 覆盖：FileHandler 登记+静默回执（P3 起 ackTemplate 退役）/ AgentHandler 引用解析→直接拼接 prompt（2026-09-11 真机反馈：
//      JSON 围栏污染用户原信息，改拼接）/ 未引用逐字不变 / 文件补查三态（正向补登记 / chatId 护栏 /
//      null 回落）/ 文本引用即上下文（正向取回 / 跨会话拒绝） / CommandHandler 模板+引用组合 /
//      resolver null = 能力关闭零变化。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.files.DownloadStore
import com.jiaoay.lark.bridge.files.FileRegistry
import com.jiaoay.lark.bridge.files.PreprocessPipeline
import com.jiaoay.lark.bridge.files.ReferencedMessageResolver
import com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.HandlerOutcome
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import com.jiaoay.lark.bridge.pipeline.HandlerWeight
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.testConfig
import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.pipeline.FakeAgentRunner
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.gateway.ChatType
import com.jiaoay.lark.gateway.InboundKind
import com.jiaoay.lark.gateway.InboundMessage
import com.jiaoay.lark.gateway.InboundResource
import com.jiaoay.lark.gateway.LarkTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

class FileReferenceFlowTest {

    // ── 测试基建 ──

    private val workspace = File(System.getProperty("java.io.tmpdir"), "file-ref-flow-${System.nanoTime()}")
        .apply { mkdirs(); deleteOnExit() }

    private fun config(
        routes: List<com.jiaoay.lark.bridge.config.RouteRule> = listOf(AgentRoute()),
        fileMessage: com.jiaoay.lark.bridge.config.FileMessageConfig = com.jiaoay.lark.bridge.config.FileMessageConfig(),
        clock: () -> Long = { 0L },
    ): BridgeConfig = BridgeConfig(
        workspace = com.jiaoay.lark.bridge.config.WorkspaceConfig(root = workspace.path),
        routes = routes,
        fileMessage = fileMessage,
    )

    private fun ctxOf(message: InboundMessage, config: BridgeConfig): RequestContext = RequestContext(
        message = message,
        config = config,
        route = null,
        reply = RecordingReplySink(LarkTarget.ofInbound(message)),
        progress = ConflatedProgressSink(),
        log = BridgeLog.Silent,
        agent = FakeAgentRunner(),
        processes = ProcessRegistry(2),
    )

    /** 带虚拟时钟的文件消息。 */
    private fun fileMsg(
        messageId: String = "om_file",
        chatId: String = "oc_test",
        fileName: String = "crash.zip",
        fileKey: String = "fk_1",
        now: Long,
    ): InboundMessage = InboundMessage(
        messageId = messageId,
        chatId = chatId,
        chatType = ChatType.P2P,
        senderId = "ou_alice",
        senderName = "",
        text = """<file key="$fileKey" name="$fileName"/>""",
        kind = InboundKind.FILE,
        rawContentType = "file",
        resources = listOf(InboundResource(type = "file", fileKey = fileKey, fileName = fileName, durationMs = 0L)),
        mentions = emptyList(),
        mentionAll = false,
        mentionedBot = false,
        rootId = null,
        threadId = null,
        replyToMessageId = null,
        createTimeMillis = now,
    )

    /** 引用回复的文本消息。 */
    private fun quoteTextMsg(
        replyTo: String,
        text: String,
        chatId: String = "oc_test",
        messageId: String = "om_quote",
        now: Long,
    ): InboundMessage = InboundMessage(
        messageId = messageId,
        chatId = chatId,
        chatType = ChatType.P2P,
        senderId = "ou_alice",
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
        createTimeMillis = now,
    )

    /** 组装完整引用链（downloader 落盘真实文件）；返回 (registry, resolver, counters)。 */
    private data class Chain(val registry: FileRegistry, val resolver: ReferencedMessageResolver, val counters: PipelineCounters)

    private fun buildChain(
        config: BridgeConfig,
        fileContent: ByteArray = "dummy zip".toByteArray(),
        clockValue: () -> Long,
        cardReplyLedger: com.jiaoay.lark.bridge.files.CardReplyLedger? = null,
        quotedFetcher: suspend (String) -> com.jiaoay.lark.gateway.QuotedMessage? = { null },
        downloadFailure: com.jiaoay.lark.gateway.DownloadResult? = null,
    ): Chain {
        val registry = FileRegistry(clock = clockValue)
        val counters = PipelineCounters()
        val store = DownloadStore({ config }, BridgeLog.Silent, clockValue)
        val preprocess = PreprocessPipeline({ config }, BridgeLog.Silent)
        // downloadFailure != null ⇒ 下载恒失败并透传该结果（平台 100MB 限等失败文案用例）；
        // 否则成功落盘（既有用例零变化）。
        val downloader: suspend (String, String, File) -> com.jiaoay.lark.gateway.DownloadResult = { _, _, dest ->
            downloadFailure ?: run {
                dest.parentFile?.mkdirs()
                dest.writeBytes(fileContent)
                com.jiaoay.lark.gateway.DownloadResult.OK
            }
        }
        val resolver = ReferencedMessageResolver(
            registry = registry,
            store = store,
            preprocess = preprocess,
            downloader = downloader,
            configProvider = { config },
            counters = counters,
            log = BridgeLog.Silent,
            cardReplyLedger = cardReplyLedger,
            quotedFetcher = quotedFetcher,
        )
        return Chain(registry, resolver, counters)
    }

    // ── FileHandler：登记（P3 起恒静默——ackTemplate 已退役） ──

    @Test
    fun `文件消息-登记进 registry 且恒静默回执`() = runBlocking {
        val cfg = config()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val registry = FileRegistry(clock = { 0L })
        val counters = PipelineCounters()
        val handler = FileHandler(FileRoute(), deps, registry, counters)

        val ctx = ctxOf(fileMsg(now = 0L), cfg)
        val outcome = handler.handle(ctx)

        // 🔴 P3 agent-first（修正 6）：文件登记改默认静默——回执卡是重复噪音（引用流程本就有下载进度卡反馈），
        //    登记事实仍经 FILE kind outcome + Observe 审计留痕（silent skipped）。
        assertEquals(TaskStatus.SKIPPED, outcome.status, "登记后恒静默 skipped：$outcome")
        assertEquals("文件已登记（静默）", outcome.summary)
        assertEquals(com.jiaoay.lark.bridge.pipeline.ReplyDirective.SILENT, outcome.directive, "静默登记不得出站")
        assertEquals(1, registry.size, "登记表应有 1 条")
        assertEquals(1L, counters.filesRegistered, "filesRegistered 计数")
        val record = registry.lookup("om_file", "oc_test", Long.MAX_VALUE)
        assertTrue(record != null, "登记后按 messageId+chatId 可查")
        assertEquals("ou_alice", record.senderId)
        assertEquals("fk_1", record.resources.single().fileKey)
    }

    @Test
    fun `FileHandler 是 LIGHT 档（仅登记+静默留痕不占许可）`() {
        val cfg = config()
        val handler = FileHandler(FileRoute(), HandlerDeps({ cfg }, BridgeLog.Silent))
        assertEquals(HandlerWeight.LIGHT, handler.weight)
        assertEquals(HandlerWeight.LIGHT, handler.weightFor(ctxOf(fileMsg(now = 0L), cfg)))
    }

    // ── AgentHandler：引用解析 → 直接拼接 prompt ──

    @Test
    fun `引用回复命中 agent 触发词-下载并组拼接 prompt`() = runBlocking {
        val cfg = config()
        var now = 0L
        val chain = buildChain(cfg, "PK fake zip".toByteArray(), clockValue = { now })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        // 先登记文件（模拟 FileHandler 已跑过）
        val fileCtx = ctxOf(fileMsg(now = now), cfg)
        FileHandler(FileRoute(), deps, chain.registry, chain.counters).handle(fileCtx)

        now = 1_000L
        val quoteCtx = ctxOf(quoteTextMsg("om_file", "ai 帮我排查 crash", now = now), cfg)
        // ctxOf 内置的 agent 是独立实例，这里换成同一个 agent 以便断言其收到的 prompt
        val quoteCtxWithAgent = RequestContext(
            message = quoteCtx.message,
            config = cfg,
            route = null,
                reply = quoteCtx.reply,
            progress = quoteCtx.progress,
            log = BridgeLog.Silent,
            agent = agent,
            processes = quoteCtx.processes,
        )
        handler.handle(quoteCtxWithAgent)

        assertEquals(1, agent.requests.size, "agent 应被调用一次")
        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("帮我排查 crash\n\n[引用文件 1]"),
            "🔴 用户原文应原样在前（不被 JSON 围栏污染）：$prompt")
        assertTrue(prompt.contains("名称：crash.zip"), "拼接块应含文件名：$prompt")
        assertTrue(prompt.contains("路径：") && prompt.contains(workspace.path), "拼接块应含路径：$prompt")
        assertTrue(prompt.contains("目录：") && prompt.contains("ou_alice"), "目录应按发送者区分：$prompt")
        assertFalse(prompt.contains("```json"), "🔴 不应再出现 JSON 围栏：$prompt")
        assertEquals(1L, chain.counters.filesReferenced, "filesReferenced 计数")
        assertEquals(1L, chain.counters.downloadsStored, "downloadsStored 计数")
    }

    // ==================== 下载失败的用户可读文案（2026-09-12 平台 100MB 限传导批次）====================

    /** 下载失败 ⇒ agent 不被调用 + 失败 outcome 透传 resolver 聚合句（e2e 锚点）。 */
    private suspend fun downloadFailureOutcome(
        downloadResult: com.jiaoay.lark.gateway.DownloadResult,
    ): HandlerOutcome {
        val cfg = config()
        val chain = buildChain(cfg, clockValue = { 0L }, downloadFailure = downloadResult)
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        FileHandler(FileRoute(), deps, chain.registry, chain.counters).handle(ctxOf(fileMsg(now = 0L), cfg))
        val outcome = handler.handle(
            ctxOf(quoteTextMsg("om_file", "ai 帮我排查 crash", now = 1_000L), cfg).let {
                RequestContext(
                    message = it.message, config = cfg, route = null,
                    reply = it.reply, progress = it.progress, log = BridgeLog.Silent,
                    agent = agent, processes = it.processes,
                )
            },
        )
        assertEquals(0, agent.requests.size, "下载失败 ⇒ agent 绝不能被调用（防残缺文件集误导）")
        return outcome
    }

    @Test
    fun `下载返回平台大小限-用户文案指出 100MB 平台限且不再误导重试`() = runBlocking {
        // 真机 2026-09-12：108.1MiB zip ⇒ 飞书 http=400 code=234037
        val outcome = downloadFailureOutcome(
            com.jiaoay.lark.gateway.DownloadResult(
                false, com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge, 234037, "http=400",
            ),
        )
        assertEquals(TaskStatus.FAILURE, outcome.status)
        val text = outcome.summary
        assertTrue(text.contains("100MB"), "文案须点明平台 100MB 上限：$text")
        assertTrue(text.contains("无法下载"), "文案须说明该文件无法下载：$text")
        assertTrue(text.contains("压缩或拆分"), "尾部建议须给行动指引：$text")
        assertFalse(text.contains("请重试"), "🔴 平台限是永久性失败——恒「请重试」是误导，不得出现：$text")
        assertEquals(TaskErrorCodes.FILE_DOWNLOAD_FAILED, outcome.errorCode, "errorCode 锁死不新增")
    }

    @Test
    fun `下载返回权限类失败-文案提示保密消息或不可见（分片批新码）`() = runBlocking {
        // Range 分片批（2026-09-13）：234038 保密消息禁止下载 / 234040 消息不可见 ⇒ PermissionDenied
        val outcome = downloadFailureOutcome(
            com.jiaoay.lark.gateway.DownloadResult(
                false, com.jiaoay.lark.gateway.GatewayErrorCode.PermissionDenied, 234038, "http=400",
            ),
        )
        assertEquals(TaskStatus.FAILURE, outcome.status)
        val text = outcome.summary
        assertTrue(text.contains("保密消息"), "权限类文案须说清可能原因：$text")
        assertTrue(text.contains("不可见"), "须并列提示「对应用不可见」：$text")
        assertEquals(TaskErrorCodes.FILE_DOWNLOAD_FAILED, outcome.errorCode, "errorCode 锁死不新增")
    }

    @Test
    fun `下载返回我方超限-文案区分配置上限而非平台限`() = runBlocking {
        // 我方 downloadMaxBytes 超限：206 首片预检 / 200 降级下载后拒绝 ⇒ larkCode 恒 null
        val outcome = downloadFailureOutcome(
            com.jiaoay.lark.gateway.DownloadResult(
                false, com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge, null, "downloaded > max",
            ),
        )
        val text = outcome.summary
        assertTrue(text.contains("超过本地大小上限"), "我方限新文案须说清是本地可调上限（分片批口径）：$text")
        assertTrue(text.contains("设置"), "我方限文案须指路「可在设置中调整」（维护者拍板）：$text")
        assertFalse(text.contains("飞书平台"), "🔴 我方限不得冒充平台限（判据 larkCode!=null 写反即此处红）：$text")
        assertTrue(text.contains("压缩或拆分"), "同为大小限 ⇒ 尾部建议同为「压缩或拆分」：$text")
    }

    @Test
    fun `下载被限流-文案提示稍后重试`() = runBlocking {
        val outcome = downloadFailureOutcome(
            com.jiaoay.lark.gateway.DownloadResult(
                false, com.jiaoay.lark.gateway.GatewayErrorCode.RateLimited, 11232, "http=429",
            ),
        )
        val text = outcome.summary
        assertTrue(text.contains("限流"), "限流须可辨识：$text")
        assertTrue(text.contains("请稍后重试"), "限流建议 = 稍后重试：$text")
        // 混合失败（限流非大小限）⇒ 尾部建议不得声称「超过大小限制」
        assertFalse(text.contains("压缩或拆分"), "🔴 allSizeLimited 判定被破坏即此处红：$text")
    }

    @Test
    fun `无引用-普通消息-prompt 逐字等于现状（零变化路径）`() = runBlocking {
        val cfg = config()
        val chain = buildChain(cfg, clockValue = { 0L })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        val ctx = ctxOf(filelessText("ai 纯文本提问", now = 0L), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        assertEquals(1, agent.requests.size)
        assertEquals("纯文本提问", agent.requests.single().prompt, "🔴 无引用时 prompt 必须逐字等于现状")
    }

    @Test
    fun `resolver 为 null-引用能力关闭-零变化`() = runBlocking {
        val cfg = config()
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, null)

        val ctx = ctxOf(filelessText("ai 老版本行为", now = 0L), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)
        assertEquals("老版本行为", agent.requests.single().prompt)
    }

    @Test
    fun `引用未登记的消息-fetch 未取回落 hint`() = runBlocking {
        val cfg = config()
        var fetchCalls = 0
        val chain = buildChain(cfg, clockValue = { 0L }, quotedFetcher = {
            fetchCalls++
            null
        })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        val ctx = ctxOf(quoteTextMsg("om_never_registered", "ai 引用不存在的", now = 0L), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("引用不存在的\n（引用提示："), "未命中应回落纯文本+引用说明：$prompt")
        assertTrue(prompt.contains("本地未取回该消息"), "说明应指出未取回原因：$prompt")
        // 🔴 2026-09-12 诊断补全：补查确实跑过 ⇒ hint 须注明「已尝试补查未取回」+ 指向网关分类日志
        assertTrue(prompt.contains("已尝试 im.v1.message.get 补查未取回"), "跑了补查的未命中须注明补查已执行（真机排查对账用）：$prompt")
        assertTrue(prompt.contains("重新发送内容"), "说明应引导重发：$prompt")
        assertEquals(0L, chain.counters.filesReferenced, "未命中不计 filesReferenced")
        assertEquals(1, fetchCalls, "🔴 补查必须恰好一次（评审修复：双 fetch 防回归）")
    }

    @Test
    fun `fileMessage enabled=false-引用流总开关-文本引用同样关闭且零变化`() = runBlocking {
        // 评审确认的语义钉子：fileMessage.enabled 是引用流**总开关**（文件与文本一并关）——
        // 回落裸 NotReferenced（不带 hint），prompt 逐字等于现状（零变化硬约束第三条腿）。
        val cfg = config(fileMessage = com.jiaoay.lark.bridge.config.FileMessageConfig(enabled = false))
        val chain = buildChain(cfg, clockValue = { 0L }, quotedFetcher = { fail("总开关关闭时不应补查") })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        val ctx = ctxOf(quoteTextMsg("om_text_off", "ai 引用文本", now = 0L), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        assertEquals("引用文本", agent.requests.single().prompt, "🔴 开关关闭时 prompt 必须逐字等于现状")
        assertEquals(0L, chain.counters.filesReferenced, "关闭时不计 filesReferenced")
    }

    @Test
    fun `引用跨会话-视为未命中且不补查`() = runBlocking {
        val cfg = config()
        var now = 0L
        val chain = buildChain(cfg, clockValue = { now }, quotedFetcher = { fail("跨会话引用不应触发补查") })
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val counters = PipelineCounters()

        // 登记在 oc_test
        FileHandler(FileRoute(), deps, chain.registry, counters).handle(ctxOf(fileMsg(now = now), cfg))
        // 引用发生在 oc_other
        val agent = FakeAgentRunner()
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)
        val ctx = ctxOf(quoteTextMsg("om_file", "ai 跨会话引用", chatId = "oc_other", now = now), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)
        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("跨会话引用\n（引用提示："), "跨会话引用回落纯文本+说明：$prompt")
        assertTrue(prompt.contains("跨会话引用（消息在别的会话里）"), "说明应指出跨会话原因：$prompt")
    }

    @Test
    fun `TTL 过期-先补查救回`() = runBlocking {
        val cfg = config()
        var now = 0L
        // 补查回同 chatId 的文件消息 ⇒ 救回 TTL 过期场景（2026-09-11 真机反馈的根治路径）
        val chain = buildChain(cfg, clockValue = { now }, quotedFetcher = { messageId ->
            com.jiaoay.lark.gateway.QuotedMessage.File(
                messageId = messageId, chatId = "oc_test", senderId = "ou_alice",
                fileKey = "fk_1", fileName = "crash.zip",
            )
        })
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val counters = PipelineCounters()

        FileHandler(FileRoute(), deps, chain.registry, counters).handle(ctxOf(fileMsg(now = now), cfg))
        // 时间前进 73h（默认 TTL 72h）——lookup 惰性清除后 miss
        now = 73 * 3_600_000L
        val agent = FakeAgentRunner()
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)
        val ctx = ctxOf(quoteTextMsg("om_file", "ai 过期引用", now = now), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)
        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("过期引用\n\n[引用文件 1]"),
            "TTL 过期经补查应救回并组拼接 prompt：$prompt")
        assertTrue(prompt.contains("名称：crash.zip"), "补登记后的文件名应进入 prompt：$prompt")
        assertEquals(1L, chain.counters.filesRegistered, "补登记应计 filesRegistered")
        assertEquals(1L, chain.counters.filesReferenced, "救回应计 filesReferenced")
    }

    @Test
    fun `补查回的 chatId 不匹配-不补登记回落 hint`() = runBlocking {
        val cfg = config()
        // 补查回的消息挂在别的会话 ⇒ 防补查路径绕过跨会话护栏
        val chain = buildChain(cfg, clockValue = { 0L }, quotedFetcher = { messageId ->
            com.jiaoay.lark.gateway.QuotedMessage.File(
                messageId = messageId, chatId = "oc_other", senderId = "ou_alice",
                fileKey = "fk_1", fileName = "crash.zip",
            )
        })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        val ctx = ctxOf(quoteTextMsg("om_ghost", "ai 引用别会话的文件", now = 0L), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("引用别会话的文件\n（引用提示："), "chatId 不匹配应回落 hint：$prompt")
        assertTrue(prompt.contains("本地未取回该消息"), "说明应指出未取回：$prompt")
        // 🔴 2026-09-12 诊断补全：本用例补查**确实执行了**（取回但 chatId 不匹配被护栏拒）⇒ 须注明「已尝试补查」
        assertTrue(prompt.contains("已尝试 im.v1.message.get 补查未取回"), "补查已执行（被护栏拒）的未命中须注明补查已执行：$prompt")
        assertEquals(0, chain.registry.size, "🔴 不应补登记他会的消息")
        assertEquals(0L, chain.counters.filesRegistered, "不补登记则不计 filesRegistered")
    }

    // ── 文本引用即上下文（2026-09-11 晚泛化） ──

    @Test
    fun `引用文本消息-取回原文拼接为上下文`() = runBlocking {
        val cfg = config()
        var fetchCalls = 0
        // 登记表只收文件消息 ⇒ 文本引用查表必然 miss，全靠 fetcher 取回原文
        val chain = buildChain(cfg, clockValue = { 0L }, quotedFetcher = { messageId ->
            fetchCalls++
            com.jiaoay.lark.gateway.QuotedMessage.Text(
                messageId = messageId, chatId = "oc_test", senderId = "ou_alice",
                text = "今天早上9点服务重启了一次，之后内存一直涨",
            )
        })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        val ctx = ctxOf(quoteTextMsg("om_text_quote", "ai 结合上文排查内存泄漏", now = 0L), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("结合上文排查内存泄漏\n\n[引用消息]\n内容：\n今天早上9点服务重启了一次，之后内存一直涨"),
            "文本引用应把原文拼到末尾并带标识块：$prompt")
        assertEquals(0, chain.registry.size, "🔴 文本引用不登记进 FileRegistry")
        assertEquals(0L, chain.counters.filesRegistered, "文本引用不计 filesRegistered")
        assertEquals(1L, chain.counters.filesReferenced, "文本引用命中计 filesReferenced")
        assertEquals(1, fetchCalls, "🔴 文本引用必须恰好一次补查（评审修复：双 fetch 防回归）")
    }

    @Test
    fun `引用机器人回复-本地账本兜底-零 fetch 调用`() = runBlocking {
        val cfg = config()
        // 模拟终态落账：流式卡终态（messageId = 卡消息在会话里的 id）已由 handleSignal 写入账本
        val ledger = com.jiaoay.lark.bridge.files.CardReplyLedger()
        ledger.record("om_card_reply_1", "oc_test", "机器人上次的终态回答全文")
        var fetchCalls = 0
        val chain = buildChain(cfg, clockValue = { 0L }, cardReplyLedger = ledger, quotedFetcher = {
            fetchCalls++
            null
        })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        val ctx = ctxOf(quoteTextMsg("om_card_reply_1", "ai 继续刚才的分析", now = 0L), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("继续刚才的分析\n\n[引用消息]\n内容：\n机器人上次的终态回答全文"),
            "机器人回复应从本地账本取回并拼 [引用消息] 块：$prompt")
        assertEquals(0, fetchCalls, "🔴 账本命中时不得发起 fetch（本地兜底零 API 调用）")
        assertEquals(1L, chain.counters.filesReferenced, "账本命中计 filesReferenced")
    }

    @Test
    fun `引用文本消息-跨会话 fetch 拒绝回落 hint`() = runBlocking {
        val cfg = config()
        // fetch 回的文本挂在别的会话 ⇒ 与文件同款跨会话护栏
        val chain = buildChain(cfg, clockValue = { 0L }, quotedFetcher = { messageId ->
            com.jiaoay.lark.gateway.QuotedMessage.Text(
                messageId = messageId, chatId = "oc_other", senderId = "ou_alice",
                text = "别的会话里的文本",
            )
        })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        val ctx = ctxOf(quoteTextMsg("om_text_ghost", "ai 引用别会话的文本", now = 0L), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("引用别会话的文本\n（引用提示："), "跨会话文本应回落 hint：$prompt")
        assertTrue(prompt.contains("本地未取回该消息"), "说明应指出未取回：$prompt")
        assertFalse(prompt.contains("别的会话里的文本"), "🔴 他会文本原文不得进 prompt：$prompt")
    }

    // ── CommandHandler：模板 + 引用组合 ──

    @Test
    fun `引用回复命中 CommandRoute-模板渲染后的 text 进拼接块`() = runBlocking {
        val route = CommandRoute(
            id = "dddd", name = "排查", aliases = listOf("dddd"),
            description = "排查 bug", promptTemplate = "请排查以下 bug：{args}",
        )
        val cfg = config(routes = listOf(route))
        var now = 0L
        val chain = buildChain(cfg, clockValue = { now })
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val counters = PipelineCounters()

        FileHandler(FileRoute(), deps, chain.registry, counters).handle(ctxOf(fileMsg(now = now), cfg))
        now = 1_000L
        val agent = FakeAgentRunner()
        val handler = CommandHandler(route, deps, chain.resolver)
        val ctx = ctxOf(quoteTextMsg("om_file", "dddd 2026-09-09 的 crash", now = now), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("请排查以下 bug：2026-09-09 的 crash\n\n[引用文件 1]"),
            "🔴 模板渲染后的 text 原样在前（模板作者指令不丢）：$prompt")
        assertTrue(prompt.contains("名称：crash.zip"), "拼接块应含文件名：$prompt")
        assertFalse(prompt.contains("```json"), "🔴 不应再出现 JSON 围栏：$prompt")
    }

    // ── 辅助 ──

    /** 普通文本（replyTo = 空串——🔴 防御面：空白与 null 同义，走零变化路径不加引用说明）。 */
    private fun filelessText(text: String, now: Long): InboundMessage =
        quoteTextMsg(replyTo = "", text = text, now = now)

    // ==================== DispatchAgentHandler：调度入口接引用流（2026-09-14 真机批）====================

    /**
     * 调度兜底入口（未命中消息 → fallback → 本 handler）与 AgentHandler 同款四态分流——
     * 调度开启后有引用能力的 AgentHandler/CommandHandler 被短路，引用链必须在此闭环。
     */
    @Test
    fun `调度 handler 引用文件-整条消息为 prompt 且拼接引用块（不剥前缀）`() = runBlocking {
        val cfg = config()
        var now = 0L
        val chain = buildChain(cfg, "PK fake zip".toByteArray(), clockValue = { now })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = DispatchAgentHandler(deps, chain.resolver)

        FileHandler(FileRoute(), deps, chain.registry, chain.counters).handle(ctxOf(fileMsg(now = now), cfg))

        now = 1_000L
        val ctx = ctxOf(quoteTextMsg("om_file", "ai 帮我解密这个文件", now = now), cfg)
        val ctxWithAgent = RequestContext(
            ctx.message, cfg, null, ctx.reply, ctx.progress,
            BridgeLog.Silent, agent, ctx.processes,
        )
        handler.handle(ctxWithAgent)

        assertEquals(1, agent.requests.size, "调度入口应调用 agent 一次")
        val prompt = agent.requests.single().prompt
        // 🔴 调度契约：整条原文（含前缀）原样在前——剥前缀由调度 agent 自理（DispatchAgentRouteTest 钉死）
        assertTrue(prompt.startsWith("ai 帮我解密这个文件\n\n[引用文件 1]"),
            "调度入口 prompt 应为整条原文 + 引用块（不剥前缀）：$prompt")
        assertTrue(prompt.contains("名称：crash.zip"), "拼接块应含文件名：$prompt")
        assertTrue(prompt.contains("路径：") && prompt.contains(workspace.path), "拼接块应含路径：$prompt")
        // 内部工具集载体：allowedFiles 经 opaque 下传（DispatchPayload）
        val payload = agent.requests.single().opaque as? com.jiaoay.lark.bridge.pipeline.DispatchPayload
        assertTrue(payload != null, "有引用文件时 opaque 应升级为 DispatchPayload 载体")
        assertTrue(payload.allowedFiles.any { it.name == "crash.zip" }, "围栏允许集应含引用文件：${payload.allowedFiles}")
        // 2026-09-15 私钥装配级统一批：钉死 ctx.liveConfig → 载体透传链（decrypt_log 的私钥真源）——
        // 富化发生在 createBundle 的 configProvider（Bridge.enrichXlogKey），载体携带的是富化后的有效私钥。
        assertEquals(cfg.fileMessage.xlogPrivateKey, payload.xlogPrivateKey, "载体应携带 liveConfig 的 xlog 私钥（富化后的有效值）")
        assertEquals(1L, chain.counters.filesReferenced, "filesReferenced 计数")
    }

    @Test
    fun `调度 handler 无引用-prompt 逐字等于原文且载体恒包 DispatchPayload（P4 统一载体）`() = runBlocking {
        val cfg = config()
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val chain = buildChain(cfg, clockValue = { 0L })
        val handler = DispatchAgentHandler(deps, chain.resolver)

        handler.handle(ctxOf(filelessText("随便聊聊", now = 0L), cfg).let {
            RequestContext(it.message, cfg, null, it.reply, it.progress,
                BridgeLog.Silent, agent, it.processes)
        })

        assertEquals("随便聊聊", agent.requests.single().prompt, "无引用 ⇒ prompt 逐字等于原文（零变化）")
        // 🔴 P4 统一载体（2026-09-26 评审修正 10）：恒包 DispatchPayload——裸 ReplySink 快路径已删
        //    （纯聊天恰是 react_emoji/fetch_quoted_context/update_own_card 主场景）。
        //    无引用 ⇒ allowedFiles 空 + quotedMessageId null + 会话作用域四件照常下发。
        val opaque = agent.requests.single().opaque
        assertTrue(opaque is com.jiaoay.lark.bridge.pipeline.DispatchPayload, "P4 起恒包载体（无裸快路径）")
        opaque as com.jiaoay.lark.bridge.pipeline.DispatchPayload
        assertTrue(opaque.allowedFiles.isEmpty(), "无引用 ⇒ 允许集空（文件工具不注册的判定依据）")
        assertEquals(null, opaque.quotedMessageId, "无引用 ⇒ quotedMessageId 为 null")
        assertEquals(cfg.fileMessage.xlogPrivateKey, opaque.xlogPrivateKey, "私钥照常随载体下发")
        // 批 B（2026-09-27 能力一级化）：chatType 传导（reply_in_thread 工具的群/单聊分流判据）
        assertEquals(ChatType.P2P, opaque.chatType, "载体应携带入站消息的 chatType")
    }

    @Test
    fun `调度 handler 引用下载失败-Failed outcome 透传且 agent 零调用`() = runBlocking {
        val cfg = config()
        val chain = buildChain(
            cfg, clockValue = { 0L },
            downloadFailure = com.jiaoay.lark.gateway.DownloadResult(
                false, com.jiaoay.lark.gateway.GatewayErrorCode.DownloadTooLarge, null, "downloaded > max",
            ),
        )
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = DispatchAgentHandler(deps, chain.resolver)

        FileHandler(FileRoute(), deps, chain.registry, chain.counters).handle(ctxOf(fileMsg(now = 0L), cfg))
        val outcome = handler.handle(
            ctxOf(quoteTextMsg("om_file", "解密这个", now = 1_000L), cfg).let {
                RequestContext(it.message, cfg, null, it.reply, it.progress,
                    BridgeLog.Silent, agent, it.processes)
            },
        )
        assertEquals(0, agent.requests.size, "下载失败 ⇒ agent 绝不能被调用")
        assertEquals(TaskStatus.FAILURE, outcome.status, "失败 outcome 应直接透传（resolver 内已 decorate）")
    }

    @Test
    fun `调度 handler resolver 为 null-引用能力关闭-零变化`() = runBlocking {
        val cfg = config()
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = DispatchAgentHandler(deps, referencedFiles = null)

        // 不登记文件、直接引用一个不存在 messageId——resolver null ⇒ resolveReferencedPrompt 走原文本
        handler.handle(ctxOf(quoteTextMsg("om_missing", "处理一下", now = 0L), cfg).let {
            RequestContext(it.message, cfg, null, it.reply, it.progress,
                BridgeLog.Silent, agent, it.processes)
        })
        assertEquals("处理一下", agent.requests.single().prompt, "resolver null ⇒ 引用能力关闭，prompt 逐字原文")
    }

    // ==================== 回执别名（2026-09-15）：引用回执同样命中文件 ====================

    /**
     * 端到端：FILE 消息登记 → ack 回执发出（onDelivered seam 拿到回执 messageId）→ 别名登记 →
     * 用户**引用回执**发指令 → resolver 走完整懒下载/预处理链，prompt 拼出 [引用文件] 块。
     * 🔴 回执别名批的闭环锚点：文案歧义（「引用这条消息」被读成引用回执）从此不再是功能断链。
     */
    @Test
    fun `引用 ack 回执-别名命中走完整下载链`() = runBlocking {
        val cfg = config()
        var now = 0L
        val chain = buildChain(cfg, "PK fake zip".toByteArray(), clockValue = { now })
        val agent = FakeAgentRunner()
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)
        val handler = AgentHandler(AgentRoute(), deps, chain.resolver)

        // ① FILE 消息登记（FileHandler）——真实链路中 handle 先于 deliver
        val fileCtx = ctxOf(fileMsg(now = now), cfg)
        FileHandler(FileRoute(), deps, chain.registry, chain.counters).handle(fileCtx)

        // ② 模拟 onDelivered seam：ack 回执发出成功 ⇒ 别名登记（BridgeAssembler 同款判定）
        val original = chain.registry.lookup("om_file", "oc_test", ttlMillis = 0)!!
        chain.registry.alias("om_ack_reply_1", original)

        // ③ 用户引用回执（不是原文件消息）发指令
        now = 1_000L
        val quoteCtx = ctxOf(quoteTextMsg("om_ack_reply_1", "ai 帮我排查 crash", now = now), cfg)
        val quoteCtxWithAgent = RequestContext(
            message = quoteCtx.message, config = cfg, route = null,
            reply = quoteCtx.reply, progress = quoteCtx.progress,
            log = BridgeLog.Silent, agent = agent, processes = quoteCtx.processes,
        )
        handler.handle(quoteCtxWithAgent)

        val prompt = agent.requests.single().prompt
        assertTrue(prompt.startsWith("帮我排查 crash\n\n[引用文件 1]"),
            "引用回执应与引用原文件消息同款命中（完整下载链）：$prompt")
        assertTrue(prompt.contains("名称：crash.zip"), "拼接块应含文件名：$prompt")
        // 🔴 dest 归属验证：record.messageId 仍是原文件消息 id ⇒ 下载目录不漂移
        assertTrue(prompt.contains("路径：") && prompt.contains(workspace.path), "拼接块应含路径：$prompt")
        assertEquals(1L, chain.counters.filesReferenced, "引用回执命中计 filesReferenced")
        assertEquals(1L, chain.counters.downloadsStored, "走真实下载链（非补查旁路）")
    }

    @Test
    fun `别名与原条目并发引用-同一 dest 不双下载`() = runBlocking {
        val cfg = config()
        var now = 0L
        var downloadCalls = 0
        val registry = FileRegistry(clock = { now })
        val counters = PipelineCounters()
        val store = DownloadStore({ cfg }, BridgeLog.Silent, { now })
        val preprocess = PreprocessPipeline({ cfg }, BridgeLog.Silent)
        val downloader: suspend (String, String, File) -> com.jiaoay.lark.gateway.DownloadResult = { _, _, dest ->
            downloadCalls++
            dest.parentFile?.mkdirs()
            dest.writeBytes("same content".toByteArray())
            com.jiaoay.lark.gateway.DownloadResult.OK
        }
        val resolver = ReferencedMessageResolver(
            registry = registry, store = store, preprocess = preprocess, downloader = downloader,
            configProvider = { cfg }, counters = counters, log = BridgeLog.Silent,
            cardReplyLedger = null, quotedFetcher = { null },
        )
        val deps = HandlerDeps({ cfg }, BridgeLog.Silent)

        // 登记原文件 + 别名
        val fileCtx = ctxOf(fileMsg(now = now), cfg)
        FileHandler(FileRoute(), deps, registry, counters).handle(fileCtx)
        val original = registry.lookup("om_file", "oc_test", ttlMillis = 0)!!
        registry.alias("om_ack_reply_1", original)

        // 两路先后引用（同 dest ⇒ recordedDirFor 复用，第二次零下载）
        val handler = AgentHandler(AgentRoute(), deps, resolver)
        for (replyTo in listOf("om_file", "om_ack_reply_1")) {
            val agent = FakeAgentRunner()
            val ctx = ctxOf(quoteTextMsg(replyTo, "ai 排查", now = ++now), cfg)
            handler.handle(RequestContext(
                ctx.message, cfg, null, ctx.reply, ctx.progress,
                BridgeLog.Silent, agent, ctx.processes,
            ))
        }
        assertEquals(1, downloadCalls, "🔴 两路引用必须落同一 dest（record.messageId 保留原 id）⇒ 恰一次下载")
    }
}
