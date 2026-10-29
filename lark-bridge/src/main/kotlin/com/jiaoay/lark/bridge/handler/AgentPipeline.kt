// 共享 agent 委托管线（AgentHandler / CommandHandler / DispatchAgentHandler 共用）：
// 引用 prompt 四态分流 + 完整执行链（表情信号 / 流式进度卡 / 产物回传 / stderr 落日志）。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.agent.AgentRequest
import com.jiaoay.lark.bridge.agent.AgentRunResult
import com.jiaoay.lark.bridge.agent.ProgressAccumulator
import com.jiaoay.lark.bridge.agent.ProgressCardRenderer
import com.jiaoay.lark.bridge.agent.RunStatus
import com.jiaoay.lark.bridge.agent.deliverArtifacts
import com.jiaoay.lark.bridge.exec.AgentLine
import com.jiaoay.lark.bridge.files.ReferencedMessageResolver
import com.jiaoay.lark.bridge.pipeline.DispatchPayload
import com.jiaoay.lark.bridge.pipeline.DownloadCardSession
import com.jiaoay.lark.bridge.pipeline.HandlerOutcome
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.bridge.pipeline.TaskStatus
import com.jiaoay.lark.bridge.pipeline.flushAndCloseNonCancellable
import com.jiaoay.lark.bridge.pipeline.pumpSnapshotsToCard
import com.jiaoay.lark.gateway.CardStreamSink
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// =====================================================================================
// 公共：agent 委托（AgentHandler / CommandHandler / FileHandler 共用完整 agent 管线）
// =====================================================================================

/**
 * 引用回复 → prompt 组装（[AgentHandler] / [CommandHandler] 共用）。
 *
 * 四态分流（硬约束：未引用 ⇒ prompt 逐字等于现状，零变化路径）：
 * - resolver 为 null（能力关闭）或 [ReferencedMessageResolver.Result.NotReferenced] ⇒ 原文本 prompt；
 * - [ReferencedMessageResolver.Result.NotReferencedWithHint]（引用了但取不到：查表 miss 且补查
 *   未取回 / 跨会话）⇒ 原文本 + 引用说明——agent 能向用户解释原因而非瞎猜「拿不到路径」；
 * - [ReferencedMessageResolver.Result.Resolved] ⇒ `buildPromptText(text, files, texts)`（用户原文
 *   原样在前、引用上下文附加块在后——文件拼路径、文本拼原文，标识块头告知 agent 这是什么）；
 * - [ReferencedMessageResolver.Result.Failed] ⇒ 包装失败 outcome，调用方直接上抛（一切回复经 deliver 的纪律不破）。
 *
 * @param baseText 剥触发词后的用户文本（CommandRoute 下 = 模板渲染后结果——模板作者指令不丢）
 */
internal suspend fun resolveReferencedPrompt(
    resolver: ReferencedMessageResolver?,
    ctx: RequestContext,
    kind: TaskKind,
    baseText: String,
): ReferencedPromptOutcome {
    // 下载进度卡**两阶段**：这里只做闸门判定（[DownloadCardSession.of] 零副作用），
    //    真正的 API 副作用（`reply.stream`）推迟到 resolver 发出「确实有文件要下载」信号时
    //    （`onDownloadStarted` → [DownloadCardSession.onStarted]）——「真的需要下载才开卡」：
    //    若在此处就发卡，**纯文本引用**（只走 `im.v1.message.get` 取原文、压根不下载）也会收到
    //    一张空卡，正是判据要排除的噪音。
    //    落点选在本函数内部（而非两个调用点各开）：**落点唯一 ⇒ 不可能漏开**，未来新增调用方自动继承。
    val card = DownloadCardSession.of(ctx)
    // 终态按**实际产出**判定，而非「函数跑完就算成功」：卡片一旦开过（说明登记表里确实有文件），
    //    若最终拿不到任何 FilePayload——资源 fileKey 全空导致回落 NotReferenced、或只取回了文本——
    //    报「引用文件已就绪」就是假话。默认 FAILED，仅真正产出文件时才置 DONE。
    var terminal = DownloadCardSession.TerminalState.FAILED
    try {
        val result = resolver?.resolve(
            ctx,
            // suspend 回调：卡片必须在**文件循环之前**真正建出来（`reply.stream` 是一次 API 调用），
            //    否则下载已在跑、卡还没开，用户依旧静默。
            onDownloadStarted = { card?.onStarted() },
            // 同一字面量双路分发：resolver 的 report 既喂 ctx.progress（看门狗/审计），也旁路给下载卡。
            //    未开卡时 `append` 内部进有界 pending 缓冲（不丢文案、不上卡）。
            onStage = { card?.append(it) },
        )
        return when (result) {
            null, is ReferencedMessageResolver.Result.NotReferenced ->
                ReferencedPromptOutcome.Prompt(baseText)
            is ReferencedMessageResolver.Result.NotReferencedWithHint ->
                ReferencedPromptOutcome.Prompt("$baseText\n${result.hint}")
            is ReferencedMessageResolver.Result.Resolved -> {
                if (result.files.isNotEmpty()) terminal = DownloadCardSession.TerminalState.DONE
                // 🔴 在途保护：引用目录在 agent 执行期间 pin 住——配额逐出（evictIfNeeded 已接活）
                //    跳过 pinned 目录，防「agent 运行中其文件目录被他人新下载触发 FIFO 逐出删除」竞态。
                //    调用方在 agent 管线跑完后 finally unpin。
                val pinnedDirs = result.files.map { java.io.File(it.dir) }
                pinnedDirs.forEach { resolver.store.pin(it) }
                ReferencedPromptOutcome.Prompt(
                    ReferencedMessageResolver.buildPromptText(baseText, result.files, result.texts),
                    pinnedDirs = pinnedDirs,
                    // 内部工具集：引用文件 path/dir/artifacts 全量作为 Koog 工具围栏初始
                    //    允许集（AgentRequest.opaque 的 DispatchPayload 载体；CLI argv 后端不读）。
                    allowedFiles = result.files
                        .flatMap { listOf(java.io.File(it.path), java.io.File(it.dir)) + it.artifacts.map { a -> java.io.File(a) } }
                        .distinct(),
                )
            }
            is ReferencedMessageResolver.Result.Failed -> {
                ReferencedPromptOutcome.Failed(
                    Responder.decorate(
                        HandlerOutcome.failure(kind, result.summary, errorCode = result.errorCode),
                    ),
                )
            }
        }
    } finally {
        // 🔴 收尾必须在本函数的 finally（四态全覆盖）——`Failed` 分支**不进** `runAgentPipeline`，
        //    拿不到那里的 finally 保护；漏收尾会重演「幽灵卡永驻生成中 + 无限占住 openStreams」。
        //    `card == null`（未过闸门）或**下载卡从未开过**（纯文本引用）时整体为 no-op
        //    ⇒ `NotReferenced`/文本引用路径零变化（连一个 API 都没发出去）。
        //    收尾走 `NonCancellable`——取消（停止指令/超时）落在下载期间也必须把卡收掉。
        card.flushAndCloseNonCancellable(terminal)
    }
}

/** [resolveReferencedPrompt] 的结果：Prompt = 继续走 agent；Failed = 直接上抛。 */
internal sealed interface ReferencedPromptOutcome {
    /**
     * @param pinnedDirs 本次引用 pin 住的下载目录（在途保护）——调用方在 agent 管线收尾
     *   `finally { pinnedDirs.forEach { resolver.store.unpin(it) } }`（agent 崩溃/取消也必须解保护）。
     * @param allowedFiles 引用文件 + 其下载目录/预处理产物——Koog 工具
     *   围栏的初始允许集，经 [AgentRequest.opaque] 的载体下传（CLI argv 后端不读）。
     */
    data class Prompt(
        val text: String,
        val pinnedDirs: List<java.io.File> = emptyList(),
        val allowedFiles: List<java.io.File> = emptyList(),
    ) : ReferencedPromptOutcome
    data class Failed(val outcome: HandlerOutcome) : ReferencedPromptOutcome
}

/**
 * 表情信号的 emoji key。
 * 字面值 = 飞书开放平台 emoji_type 通用名：`OnIt`（处理中）对应飞书内置表情「收到」、
 * `DONE`/`THUMBSUP`（完成）、`CROSS_MARK`（失败）。key 不合法时 `react` 失败已被 `runCatching`
 * 静默吞掉（最坏无表情），字面值可按实际冒烟结果再校准——**不构成功能风险**。
 */
internal const val EMOJI_ON_IT: String = "OnIt"
internal const val EMOJI_DONE: String = "THUMBSUP"
internal const val EMOJI_FAILED: String = "CROSS_MARK"

/** 失败回合 stderrTail 落日志的截断长度（防日志爆行；采集上限本身是 TailBuffer 4000 字符）。 */
internal const val STDERR_TAIL_LOG_CHARS: Int = 500

/**
 * 经 [RequestContext.agent] 复用**完整 agent 管线**（委托目标不是静态单例，而是每消息 ctx
 * 携带的、装配期唯一注入的 [com.jiaoay.lark.bridge.agent.AgentRunner]——消灭静态全局可变状态）。
 *
 * 带会话上下文的 5 参重载是 [com.jiaoay.lark.bridge.agent.AgentRunner] 接口成员——
 *    所有实现（CLI 后端 / Koog 调度 agent / 测试 Fake）统一走接口重载，确认卡与进度卡链路
 *    对任何后端可用（不做 `as?` 窄化）。
 * 逐行输出喂 [RequestContext.progress]（空闲看门狗活动信号 + 进度累积）。
 * 超时值从 `ctx.liveConfig.runtime` 现算（不缓存）。
 * 五个可选注入件（allowedFiles/homeDataRoot/replyLedger/nativeSurface/confirmGate）统一纪律：
 *    由各 handler 从 `deps.<provider>()` 现读/现造传入（每消息现取，不缓存）；
 *    默认 null = 存量测试/Harness 零改动 + 对应能力 fail-closed 关闭。各参数 KDoc 只记自身特性。
 *
 * ## 流式进度卡接线
 * `ctx.progress` 是 [ProgressAccumulator]（装配点 `BridgeAssembler.progressOverride` 构造）时：
 * 1. 任务开始先发**受理卡**（[ProgressCardRenderer.receipt]，流式、无按钮），
 *    即 `reply.stream(receipt)` 开出 [CardStreamSink]；
 * 2. 启动卡片泵协程：`pumpSnapshotsToCard(accumulator.snapshots, sink)` 把最新窗口快照全量替换上卡（滚动窗口，不累积历史）；
 * 3. agent 结束（成功/失败/超时/取消四态）后 `flushTerminal(终态卡)` 收尾（在 `finally` 里，
 *    取消路径也必达）+ `accumulator.close()` 让泵退出。
 * pump 挂在任务协程的 Job 下，任务被取消（URGENT 停止/超时）时随结构化并发一并取消；
 * `flushTerminal`/`close` 网关侧是 `NonCancellable`，终态卡不被取消竞态吞掉。
 * 进度卡任何失败都**不阻断任务**（降级：本条消息退回「无进度卡」的旧行为，只 WARN）。
 * `agent.progress.enabled=false` ⇒ stream-json 关闭（无事件）⇒ 跳过整条卡片链路。
 */
internal suspend fun runAgentPipeline(
    ctx: RequestContext,
    prompt: String,
    kind: TaskKind,
    resetSession: Boolean = false,
    /** 引用文件围栏初始允许集（null/空 = 裸 ReplySink 载体，行为同现状）。 */
    allowedFiles: List<File>? = null,
    /**
     * 桥接数据根——`[产物]` 标记链的敏感判据 1 输入。
     * 默认 null（未知）：数据根判据整体跳过（凭据族判据仍生效）。
     */
    homeDataRoot: File? = null,
    /**
     * 机器人回复账本——DispatchPayload 会话作用域件（fetch_quoted_context
     * 的查询面）。
     * 默认 null = 上下文取回能力关闭，工具如实告知 LLM。
     */
    replyLedger: com.jiaoay.lark.bridge.files.CardReplyLedger? = null,
    /**
     * L2 跨会话原生能力面——DispatchPayload 载体件（fetch_chat_history
     * 等五工具的读写面）。每消息现造一件（chatId 绑定，见 NativeChatSurface KDoc）。
     * 默认 null = L2 五工具全不注册，fail-closed。
     */
    nativeSurface: com.jiaoay.lark.bridge.pipeline.NativeChatSurface? = null,
    /**
     * L2 写操作确认门——DispatchPayload 载体件（create_group_chat 强制确认）。
     * 默认 null = 写操作 fail-closed 拒绝。
     */
    confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate? = null,
): HandlerOutcome {
    val runtime = ctx.liveConfig.runtime
    val agent = ctx.liveConfig.agent
    val accumulator = ctx.progress as? ProgressAccumulator
    // 进度卡总开关：enabled=false 时 stream-json 关闭（Backends.streamJson），事件与卡片链路一并跳过
    val progressCardEnabled = agent.progress.enabled && accumulator != null
    // 受理卡延迟触发：轻答任务（纯 reply_direct、零工具零文本事件）不再刷卡——受理卡改由
    //    accumulator 的**首个进度事件**回调开出：首个 Thinking/ToolUse/AssistantText 事件到达才开
    //    （真有过程值得展示才占用视觉位）。开卡失败降级为无进度卡回复。
    //    终态收尾兜底：任务结束时若从未开过卡（比如首事件前的快速失败），flushTerminal 跳过——
    //    文字回复本身就是反馈，一张秒开秒关的闪卡（churn）是反模式。
    val cardSinkRef = java.util.concurrent.atomic.AtomicReference<CardStreamSink?>(null)
    if (progressCardEnabled) {
        val starter = accumulator!!
        // 🔴 捕获**任务协程上下文**（runAgentPipeline 在 Admission launchTask 的任务协程内被调，
        //    coroutineContext 含任务 Job）——延迟开卡的泵必须挂到这个 Job 下（URGENT 停止/超时
        //    取消任务时泵随结构化并发一并取消）。回调执行点在 agent 工具协程，
        //    直接取 coroutineContext 会拿错域，故捕获任务域传入。
        val taskContext = coroutineContext
        // 延迟开卡 + 泵协程一并挂入回调：首事件到来（accumulator.record → wire 回调）时
        //    开卡成功才启动泵（失败降级：无卡无泵，文字回复照常）。
        //    🔴 不可 supervisorScope/coroutineScope（挂起等子协程——曾致事故，见下方
        //    「泵失败不传染」注释同因）；用捕获的任务域包立即返回的 Scope，launch 后不 join。
        starter.wireDelayedCardStart {
            runCatching { ctx.reply.stream(ProgressCardRenderer.receipt(prompt)) }
                .onSuccess { sink ->
                    cardSinkRef.set(sink)
                    CoroutineScope(taskContext).launch {
                        runCatching { pumpSnapshotsToCard(slot = starter.snapshots, sink = sink) }
                            .onFailure { if (it !is CancellationException) ctx.log.warn("进度卡泵异常：${it.message}") }
                    }
                }
                .onFailure { ctx.log.warn("进度卡开启失败（降级为无进度卡回复）：${it.message}") }
        }
    }
    // 3.4 表情信号：agent 类任务在**用户原消息**上加 👀 接单、
    //    终态换 ✅/❌（取消 = 撤回 👀）。卡片原地更新不触发推送，表情是手机端唯一的完成信号。
    //    接单表情失败静默（最坏无表情）；目标 messageId 是用户消息
    //    而非卡片，故与 RateGovernor 的出站配额无冲突（react 不占令牌桶，见 ReplySink.react）。
    val reactionApplied = runCatching { ctx.reply.react(ctx.message.messageId, EMOJI_ON_IT, add = true) }
        .getOrDefault(false)
    var outcomeForReaction: HandlerOutcome? = null
    // 终态结果提为 finally 可见——失败状态带要拼 errorText（错误码）。
    //    现结构里 result 在 try 块内、finally 的终态卡收尾拿不到，故顶部 var + try 内赋值。
    var terminalResult: AgentRunResult? = null
    try {
        val request = AgentRequest(
            sessionKey = ctx.sessionKey,
            prompt = prompt,
            totalTimeoutMillis = runtime.agentTimeoutMinutes.toLong() * 60_000L,
            idleTimeoutMillis = runtime.agentIdleTimeoutSeconds.toLong() * 1000L,
            resetSession = resetSession,
            systemPrompt = agent.systemPrompt,
            // CLI argv 后端不读此字段）。类型收窄在 AgentRequest.opaque 注释里说明。
            persona = agent.persona,
            // per-message 出站能力面（进程内 Koog 调度 agent 的工具闭包读它发文件/卡片/文本；
            // CLI argv 后端不读此字段）。类型收窄在 AgentRequest.opaque 注释里说明。
            // 🔴 恒包 DispatchPayload——删除「无引用文件返回裸 ReplySink」快路径（纯文本闲聊恰是
            //    react_emoji/fetch_quoted_context/update_own_card 主场景）；会话作用域四件
            //    （messageId/chatId/引用链/账本引用）随载体下发，工具钉「当前会话作用域」。
            opaque = DispatchPayload.of(
                reply = ctx.reply,
                allowedFiles = allowedFiles.orEmpty(),
                xlogPrivateKey = ctx.liveConfig.fileMessage.xlogPrivateKey,
                messageId = ctx.message.messageId,
                chatId = ctx.chatId,
                quotedMessageId = ctx.message.replyToMessageId?.takeIf { it.isNotBlank() },
                replyLedger = replyLedger,
                // reply_in_thread 工具的群/单聊分流判据。
                chatType = ctx.message.chatType,
                // L2 跨会话面 + 写确认门（null = 未接线/链不可用，L2 全不注册或 fail-closed——
                // 由调用 handler 的工厂闭包现造）。
                nativeSurface = nativeSurface,
                confirmGate = confirmGate,
            ),
        )
        // 🔴 空回调：喂进度是执行器实现的职责（结构化事件或纯文本行，二选一）。
        //    此处若再 append 一次，会把**原始 JSON 行**（一行可达数 KB）双重喂进窗口：
        //    卡片糊满 JSON 垃圾 + 12 条窗口瞬间刷满 + 30KB ByteBudget 提前轮换（实测「分两条且超长」）。
        //    非空实现仅在 onLine 需要旁路观测时才需要（当前无此需求）。
        val onLine: (AgentLine) -> Unit = { }
        // 🔴 5 参重载经 [AgentRunner] 接口成员调用——不做具体实现类窄化：
        //    窄化会让 feature 侧注入的调度 agent 拿不到 chatId/progress，确认卡与进度卡链路全断。
        val result: AgentRunResult = ctx.agent.run(
            request = request,
            chatId = ctx.chatId,
            senderOpenId = ctx.message.senderId,
            progress = accumulator,
            onLine = onLine,
        )
        terminalResult = result  // 终态卡收尾（finally）读它拼失败状态带的 errorText
        val outcome = result.toOutcome(kind, ctx.agent.backendId)
        // stderrTail 落日志：agent 子进程 stderr 的尾部采集若无消费者，超时 kill / CLI 启动失败 /
        // 崩溃退出的第一手诊断信息就丢了。失败/超时回合 WARN 一次（成功回合 stderr 通常为空，不打扰）。
        // detail 已含结构化收尾，此处只补 stderr 独有信息；截断到 500 字符防日志爆行。
        if (!result.isOk && result.stderrTail.isNotBlank()) {
            ctx.log.warn(
                "agent stderr 尾部（backend=${ctx.agent.backendId} exit=${result.exitCode} " +
                    "timedOut=${result.timedOut} idleTimedOut=${result.idleTimedOut}）：\n" +
                    result.stderrTail.take(STDERR_TAIL_LOG_CHARS),
            )
        }
        // 🔴 产物回传：仅成功回合处理；任何异常按「原文照发」降级，绝不阻断文字回复
        //    （runCatching 兜底）；outcome.summary 被替换为「剥离标记后的正文 + 失败条目还原」。
        val delivered = outcome.withArtifactsDelivered(ctx, homeDataRoot, confirmGate)
        outcomeForReaction = delivered
        return delivered
    } finally {
        // 表情终态（成功/失败/取消三路都在此兜住）：outcome 未定格 = 协程被取消（超时/停止）⇒ 撤回 👀。
        //    NonCancellable 保证取消路径也能发（与下方终态卡同因）。
        withContext(kotlinx.coroutines.NonCancellable) {
            runCatching {
                when {
                    outcomeForReaction == null ->
                        // 取消（URGENT 停止 / withTimeout 到点）：撤回接单表情，不标失败
                        if (reactionApplied) ctx.reply.react(ctx.message.messageId, EMOJI_ON_IT, add = false)
                    outcomeForReaction.status == TaskStatus.SUCCESS ->
                        ctx.reply.react(ctx.message.messageId, EMOJI_DONE, add = true)
                    else ->
                        ctx.reply.react(ctx.message.messageId, EMOJI_FAILED, add = true)
                }
            }
        }
        // 🔴 终态卡收尾（成功/失败/超时/取消四条路径都必须走到）：flushTerminal 是 NonCancellable
        //    （网关侧实现），即使本协程已被取消（withTimeout 到点 / URGENT 停止）也能补发终态。
        //    🔴 不收尾的后果（实测复现过）：sink 主循环仍 open，9min 后触发 TimeDeadline 轮换
        //    ⇒ 向用户会话**再发一张幽灵卡** + 无限占住 openStreams。
        //    延迟开卡：从未开过卡（首事件前的快速完成/失败——纯 reply_direct 轻答）则无卡可收，
        //    跳过即可（文字回复即反馈，秒开秒关的闪卡是反模式）。
        cardSinkRef.get()?.let { sink ->
            val acc = accumulator
            if (acc == null) {
                runCatching { sink.close() }
            } else {
                runCatching {
                    // 🔴 终态卡必须传 limits——不传则恒用渲染器默认值，
                    //    UI/CLI 调 maxEntries/cardMaxChars 对终态卡不生效；传 accumulator.limits
                    //    与运行中卡同源，配置真正落地。
                    sink.flushTerminal(
                        ProgressCardRenderer.terminal(
                            status = acc.snapshot().status.toTaskRunStatus(),
                            elapsedMillis = 0L, // 终态卡标题文案由 markStatus 的快照反映；耗时以文字回复为准
                            progress = acc.snapshot(),
                            limits = acc.limits,
                            // 失败态拼错误详情——纯码形态 `errorCode（exit=N）`：
                            //    lib 侧无错误码→人话映射（人话表在 feature 侧，lib 不造）。
                            //    仅「正常返回且失败」路径携带；超时/取消（terminalResult == null，
                            //    协程在 run 返回前被取消）不传——状态带只出「已超时/失败」短语，
                            //    错误码属于文字回复职责。成功态 errorText 恒 null。
                            errorText = terminalResult?.let { r ->
                                if (r.isOk) null else r.errorCode?.let { code -> "$code（exit=${r.exitCode}）" }
                            },
                        ),
                    )
                }.onFailure { if (it !is CancellationException) ctx.log.warn("终态卡发送失败：${it.message}") }
            }
        }
        accumulator?.close()   // 让泵的 slot.receive() 返回 null 而退出
    }
}

/**
 * [RunStatus] → [RunStatus]（终态卡渲染用）：任务取消（URGENT 停止 / withTimeout 到点）时
 * accumulator 尚未 markStatus（RUNNING），此时终态卡按「失败」收尾（与文字回复的「任务超时」一致）。
 */
private fun RunStatus.toTaskRunStatus(): RunStatus = when (this) {
    RunStatus.RUNNING -> RunStatus.FAILED   // 未 markStatus 即被取消 ⇒ 按失败收尾
    else -> this
}

/** [AgentRunResult] → [HandlerOutcome]（🔴 errorCode 原样透传，全是 behavior-contract.md §4.13 的 17 字面值之一——码外值会让宿主错误映射认不出）。 */
internal fun AgentRunResult.toOutcome(kind: TaskKind, backend: String): HandlerOutcome =
    if (isOk) {
        HandlerOutcome.success(kind = kind, summary = summary, detail = detail, elapsedMillis = elapsedMillis, backend = backend)
    } else {
        HandlerOutcome.failure(
            kind = kind,
            summary = summary.ifBlank { detail.ifBlank { "agent 执行失败" } },
            errorCode = errorCode ?: TaskErrorCodes.TASK_ERROR,
            detail = detail,
            exitCode = exitCode,
            elapsedMillis = elapsedMillis,
            backend = backend,
        )
    }

/**
 * 产物回传：成功回合的 summary 末尾若带 `[产物] <路径>` 标记块，
 * 解析 → 沙箱校验 → 上传为飞书文件消息，summary 替换为剥离后的正文（失败条目还原到末尾）。
 *
 * 🔴 降级纪律：仅 SUCCESS 且 `agent.artifacts.enabled` 时处理；catch Throwable 兜底——上游异常按
 * 「原文照发」返回原 outcome，绝不阻断文字回复；🔴 CancellationException 显式 rethrow：
 * 「停止」/总超时落在上传窗口时取消信号必须上抛，否则 finally 会给
 * 用户原消息加 ✅（正确语义是撤回 👀）、超时改判错乱。上传成功清单写进 [HandlerOutcome.attachments]
 * （审计真实生效）。失败/关闭/无标记 ⇒ 原样返回。每次出站前 touch 喂空闲看门狗（多文件慢速上传
 * 不被 120s 看门狗误杀——agent 输出驱动的 touch 在交付期恒无）。
 */
private suspend fun HandlerOutcome.withArtifactsDelivered(
    ctx: RequestContext,
    dataRoot: File?,
    confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate? = null,
): HandlerOutcome {
    val artifactsCfg = ctx.liveConfig.agent.artifacts
    if (status != TaskStatus.SUCCESS || !artifactsCfg.enabled) return this
    try {
        val workspace = ctx.liveConfig.workspace
        val policy = com.jiaoay.lark.bridge.exec.WorkspacePolicy.fromConfig(
            workspaceDir = workspace.root,
            workspaceWhitelist = workspace.extraRoots,
        )
        val delivery = deliverArtifacts(
            text = summary,
            config = object : com.jiaoay.lark.bridge.agent.ArtifactsConfigView {
                override val maxCount = artifactsCfg.maxCount
                override val maxSizeBytes = artifactsCfg.maxSizeBytes
                override val allowedExtensions = artifactsCfg.allowedExtensions
            },
            policy = policy,
            // 🔴 敏感文件判据 1 的输入。与 `send_file` 工具同源同值——
            //    两条出站通道必须装同一把锁，否则同宽旁路（参见 deliverArtifacts 的参数注释）。
            homeDataRoot = dataRoot,
            // 归档/超阈值产物 HIGH 确认门：与 L2 确认卡同一 per-message 件（防替答 chatId+senderOpenId 已绑定）。
            confirmGate = confirmGate,
            reply = object : com.jiaoay.lark.bridge.agent.ReplyFileSink {
                override suspend fun file(
                    file: java.io.File,
                    displayName: String?,
                ): com.jiaoay.lark.gateway.SendResult {
                    ctx.progress.touch() // 慢速上传也计为活动（防空闲看门狗误杀）
                    return ctx.reply.file(file.toPath(), displayName)
                }
            },
        )
        if (delivery.artifacts.isEmpty() && delivery.body == summary) return this // 无标记块：零改动
        return copy(
            summary = delivery.body,
            attachments = delivery.artifacts.map { "${it.fileName} <- ${it.path}" },
            // 被拒清单：随审计 rejectedArtifacts 落盘，失败原因不再只存飞书回复正文
            rejectedArtifacts = delivery.rejected,
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        ctx.log.warn("产物回传降级（原文照发）：${e.message}")
        return this
    }
}
