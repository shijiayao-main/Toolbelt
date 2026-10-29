// ② Admission（两级准入）+ ③ Execute（超时与看门狗）+ ④ Reply（唯一出口调用）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.dispatch.BridgeRuntime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ② Admission：两级准入 + ③ Execute + ④ Reply 的调度中枢。
 *
 * ## 三档分流（方案「两级准入」表）
 * | 档位 | 谁 | 许可 | 附加语义 |
 * |---|---|---|---|
 * | [HandlerWeight.URGENT] | `StopHandler` | **绕过** | 🔴 先 [ChatLanes.cancelAll] 取消该 chat 的在途任务，再执行自己 |
 * | [HandlerWeight.LIGHT] | `SystemHandler`(ping/`/commands`)、`InteractionHandler`（卡片回传 + 确认/拒绝）、`FileHandler`（文件登记 + 回执）、拒绝回复、未命中兜底 | **不占** | 立即 launch |
 * | [HandlerWeight.HEAVY] | `AgentHandler`、`CommandHandler`→agent | [PermitGate] | + ChatLane 每 chat 上限 + `queueWaitSeconds` + 繁忙回复合批 |
 *
 * **三档分流的直接后果**：2 个长跑 HEAVY 在途时，`/help`（LIGHT）与「停止」（URGENT）**立即执行**，
 *    不排队、不占许可（`AdmissionTest` 有直接断言）。
 *
 * ## 为什么 [submit] 是**非挂起**的
 * [submit] 由 ① intake 单协程调用。若它挂起（例如在 intake 里 `permits.acquire()` 或
 * `withTimeoutOrNull(queueWaitSeconds)`），一条排队消息就会**冻结整条管线** 30 秒——所有会话全部停摆。
 * 因此：**许可等待、超时、出站全部发生在 `runtime.launchTask` 出来的任务协程里**，[submit] 只做
 * 「分档 + launch + 返回 Job」三件事，O(1) 返回。这是 NF-01（3 秒铁律）在桥接侧的结构性保证。
 *
 * ## URGENT 的取消语义
 * `cancelAll` **只 cancel Job，不动在途计数**：计数由各任务协程自己的 `finally { lanes.release() }` 归还，
 * 避免「cancel 后计数被减两次」。cancel 触发协程取消 ⇒ handler 收到 `CancellationException` ⇒
 * `ProgramLauncher` 的三段式 kill 链（SIGTERM→3s→SIGKILL→2s）在 `NonCancellable` 内跑完（T6 已内建）。
 *
 * ## 异常捕获顺序（易错点，务必保持）
 * `TimeoutCancellationException` **是** `CancellationException` 的子类 ⇒ `catch` 顺序必须是
 * 「Timeout → Cancellation → Throwable」，否则总超时会被误判成「外部取消」而静默不回复（用户永远等不到结果）。
 *
 * @param onOutcome ⑤ Observe seam（T9 接审计/统计/UI 日志台；🔴 必须 O(1) 非挂起，内部只允许 `trySend`）。
 */
class Admission(
    private val runtime: BridgeRuntime,
    private val configProvider: () -> BridgeConfig,
    private val lanes: ChatLanes,
    private val timeouts: TimeoutPolicy,
    private val counters: PipelineCounters,
    private val permits: PermitGate,
    private val log: BridgeLog = BridgeLog.Silent,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onOutcome: ((RequestContext, HandlerOutcome) -> Unit)? = null,
    /**
     * ④ Reply 之后的出站回执 seam：每次真实发出一条回复（text/markdown/card）
     * 时回调，携带 SendResult（messageId 在此处才有——handler 只产出 outcome，出站归 deliver）。
     * O(1) 非挂起（同 onOutcome 纪律）；成功失败都回调，成败判定归接线方。
     * 消费方：回执别名登记（FILE 消息的 ack 回执 id → FileRegistry.alias）。
     */
    private val onDelivered: ((RequestContext, com.jiaoay.lark.gateway.SendResult) -> Unit)? = null,
    /**
     * ⑮ senderName 解析 seam（执行路径 + 拒绝回复路径）。
     * 在 task 协程里调用（非 Intake 单消费者协程），允许挂起。
     * 装配时接 `UserNameResolver.nameOf(ctx.message.senderId)` → 写入 `ctx.senderName`。
     */
    private val resolveSenderName: (suspend (RequestContext) -> Unit)? = null,
    /**
     * ⑮ senderName 只读缓存 seam（拒绝路径降级：rejectionReply 为空时用 peek，未命中则写空串）。
     * 🔴 非挂起、绝不触发 API。
     * **已知差异**：peek 未命中时 senderName 为空串——此路径退化为只读缓存命中，未命中就写空串。
     */
    private val peekSenderName: ((RequestContext) -> Unit)? = null,
    /**
     * 未命中兜底 agent seam（App 形态恒激活）：
     * 路由未命中任何 handler 时询问本 provider——非 null 返回 [MessageHandler] 则该消息改走
     * **HEAVY 同款闸**（调度 agent 可能内嵌 CLI 转发，重量不可控，绝不按 LIGHT 秒回）；
     * null（CLI 形态不注入 / 非文本消息或空白文本）⇒ 原样 `runUnhandled`（行为逐字不变）。
     * 🔴 返回的 handler 必须自报 [HandlerWeight.HEAVY]（`weightFor` 恒 HEAVY）——本类不强制校验，
     * 装配点契约（CLI 恒传 null ⇒ CLI 行为逐字不变；App 侧注入非 null 即激活，
     * 判定真源 = 装配点闭包的 TEXT 形态判定；agent 恒为硬依赖，可用性闸收口在
     * DispatchAgentHandler 内现读执行器）。
     */
    private val fallbackHandlerProvider: ((RequestContext) -> MessageHandler?)? = null,
) {

    /** 当前在途任务数（含 LIGHT/URGENT/HEAVY；metrics 用）。 */
    private val inFlightTasks = AtomicInteger(0)

    /** 当前在途任务数。 */
    val inFlight: Int get() = inFlightTasks.get()

    /** 许可闸门（`AdmissionTest` 断言「LIGHT 不占许可」时读 [PermitGate.available]）。 */
    val permitGate: PermitGate get() = permits

    /**
     * 提交一条消息到 ②/③/④。🔴 **非挂起、O(1)、恒不抛**。
     *
     * @param entry [PipelineRouter.route] 的结果；**null ⇒ 未命中任何 handler**，走 [runUnhandled] 兜底。
     * @return 任务 Job（测试用 `join()` 等它跑完）。
     */
    fun submit(ctx: RequestContext, entry: MatchableHandler?): Job {
        counters.recordSubmitted()
        // 🔴 `when` 的 subject **只求值一次** `weightFor(ctx)`，
        //    Kotlin 会缓存 subject 表达式的结果 ⇒ 分档与后续 runHeavy/invokeHandler/deliver 全程复用同一档位，
        //    绝不二次求值（杜绝「按 LIGHT 准入却按 HEAVY 记账」的隐性错乱）。
        //    默认 `weightFor == weight`（静态）⇒ 不覆写它的 handler 与 ScriptedHandler 行为完全不变。
        //    subject 仍是 `entry?.…` 形态 ⇒ URGENT/LIGHT/HEAVY 分支里 `entry` 被智能转换为非空。
        return when (entry?.handler?.weightFor(ctx)) {
            HandlerWeight.URGENT -> runtime.launchTask {
                // 🔴 先取消该 chat 的在途任务，再执行自己（「停止」必须在 agent 还在跑时起效）。
                counters.recordUrgentCancellations(lanes.cancelAll(ctx.chatId))
                runGuarded(ctx) { runHandler(ctx, entry) }
            }

            HandlerWeight.LIGHT -> runtime.launchTask { runGuarded(ctx) { runHandler(ctx, entry) } }

            // entry == null（未命中）⇒ 问 fallback agent seam（本地 Koog 调度）：
            // 有 handler ⇒ 包成 entry 走 HEAVY 同款闸（调度 agent 重量不可控，须占许可 + per-chat 上限）；
            // 无（seam 未注入 / 开关关 / 端点未配）⇒ 原 `runUnhandled` 兜底（行为逐字不变）。
            null -> {
                val fallback = fallbackHandlerProvider?.invoke(ctx)
                if (fallback != null) {
                    val fallbackEntry = MatchableHandler(route = null, handler = fallback)
                    runtime.launchTask { runGuarded(ctx) { runHeavy(ctx, fallbackEntry) } }
                } else {
                    runtime.launchTask { runGuarded(ctx) { runUnhandled(ctx) } }
                }
            }

            HandlerWeight.HEAVY -> runtime.launchTask { runGuarded(ctx) { runHeavy(ctx, entry) } }
        }
    }

    /**
     * 提交一条**拒绝回复**（① Intake 的 sender 准入 / 策略门控判否时调用）。
     *
     * 🔴 **不占许可**（拒绝回复直接进 ⑤ Observe，不经许可闸）：
     *    走 LIGHT 分支立即 launch ⇒ 2 个长跑 HEAVY 在途时拒绝回复仍然秒回。
     * 🔴 它**不进** ChatLane 也不取 [PermitGate] 许可 ⇒ 被拒用户不可能把并发额度吃光。
     *
     * @param replyText null 或空白 ⇒ **完全静默**（`rejectionReply` 为空即不回；
     *        群@门控的拒绝也走这条——那是正常闲聊，回复反而是噪音），仍进 ⑤ Observe 留审计痕迹。
     */
    fun submitRejection(ctx: RequestContext, replyText: String?): Job = runtime.launchTask {
        runGuarded(ctx) {
            val text = replyText?.takeIf { it.isNotBlank() }
            // ⑮ senderName 解析（拒绝路径）：有回复 ⇒ 已在协程 ⇒ 可挂起解析；静默 ⇒ 只读缓存降级。
            if (text != null) {
                resolveSenderName?.invoke(ctx)
            } else {
                // peek 未命中时 senderName 为空串（只读缓存降级，不触发 API）。
                peekSenderName?.invoke(ctx)
            }
            val outcome = if (text == null) {
                HandlerOutcome.skipped(kind = TaskKind.COMMAND, summary = REJECTION_SILENT_SUMMARY, silent = true)
            } else {
                // SKIPPED 而非 SUCCESS：拒绝不计入「完成/失败」metrics，但审计必须留痕。
                //    errorCode 取 null：TaskErrorCodes 全集里**无**「拒绝」类错误码（拒绝不是失败），
                //    审计侧靠 `SenderStatsRecords` 的 TYPE_REJECTED_* 区分。
                HandlerOutcome.skipped(kind = TaskKind.COMMAND, summary = text)
            }
            if (text != null) {
                val result = runCatching { ctx.reply.markdown(text) }.getOrNull()
                if (result != null && !result.ok) counters.recordReplyFailure()
            }
            counters.recordOutcome(outcome)
            onOutcome?.invoke(ctx, outcome)
        }
    }

    /** 任务协程的统一外壳：在途计数 + 顶层异常兜底（执行器异常绝不中断服务）。 */
    private suspend fun CoroutineScope.runGuarded(ctx: RequestContext, block: suspend CoroutineScope.() -> Unit) {
        inFlightTasks.incrementAndGet()
        // 🔴 空闲看门狗的基准时刻：进任务即 touch，否则 `lastActivityMillis==0` 会让看门狗立即误判空闲。
        ctx.progress.touch()
        try {
            block()
        } catch (e: CancellationException) {
            counters.recordCancelled()
            throw e                       // 🔴 结构化取消必须原样上抛，否则 taskJob 不会真正结束
        } catch (e: Throwable) {
            counters.recordHandlerError()
            log.error("任务执行失败 chatId=${ctx.chatId} messageId=${ctx.message.messageId}", e)
            val outcome = HandlerOutcome.error(TaskKind.AGENT, e.message)
            deliver(ctx, outcome)
        } finally {
            inFlightTasks.decrementAndGet()
        }
    }

    /** LIGHT / URGENT：不占许可，直接跑 handler。 */
    private suspend fun CoroutineScope.runHandler(ctx: RequestContext, entry: MatchableHandler) {
        val outcome = invokeHandler(ctx, entry)
        deliver(ctx, outcome)
    }

    /**
     * 未命中任何 handler 的兜底。
     *
     * TEXT 未命中在 App 形态恒被 fallback 调度 agent 收编（本方法仅 CLI 形态触达，恒静默留痕）；
     *    **非 TEXT kind 的降级文案机制原样保留**（「不静默丢失」纪律）：
     *    可识别的未支持类型（IMAGE/AUDIO/…/SHARE_USER，见 [unsupportedKindNotice]）
     *    仍发明确降级文案——群里发图 @机器人 不再零响应零提示。
     */
    private suspend fun runUnhandled(ctx: RequestContext) {
        counters.recordUnmatched()
        // 非 TEXT 可识别未支持 kind ⇒ 明确降级文案（文案真源 UnsupportedKindNotice.kt）；
        // TEXT（App 形态归调度 agent 后此处仅 CLI 兜底）⇒ 静默留痕。
        val notice = unsupportedKindNotice(ctx.inboundKind)
        val sent = notice != null && runCatching { ctx.reply.replyUnhandled(notice) }.getOrElse { false }
        if (!sent) {
            log.debug("未命中任何 handler（静默留痕）messageId=${ctx.message.messageId}")
        }
        // SKIPPED：未命中不计入完成/失败 metrics（旧语义平移），但**必须**进 ⑤ Observe（审计要留痕）。
        val outcome = HandlerOutcome.skipped(
            kind = TaskKind.COMMAND,
            summary = if (sent) notice ?: UNHANDLED_SILENT_SUMMARY else UNHANDLED_SILENT_SUMMARY,
            silent = !sent,
        )
        counters.recordOutcome(outcome)
        onOutcome?.invoke(ctx, outcome)
    }

    /** HEAVY：ChatLane 上限 → 许可（带排队超时）→ 执行 → 归还。 */
    private suspend fun CoroutineScope.runHeavy(ctx: RequestContext, entry: MatchableHandler) {
        val runtimeConfig = configProvider().runtime
        val kind = taskKindOf(entry.kind)

        // 第 1 级：每 chat 在途上限（`agent_busy`）。不排队、立即回，让用户知道该等。
        if (!lanes.tryAcquire(ctx.chatId, runtimeConfig.chatQueueLimit)) {
            counters.recordChatBusy()
            val busy = HandlerOutcome.chatBusy(runtimeConfig.chatQueueLimit, kind)
            // 繁忙回复按 chat 合批（`busyReplyCooldownSeconds`）：窗口内同 chat 只回一次。
            if (lanes.claimBusyReply(ctx.chatId, runtimeConfig.busyReplyCooldownSeconds.toLong() * 1000L)) {
                deliver(ctx, busy)
            } else {
                counters.recordBusyReplySuppressed()
                counters.recordOutcome(busy)
                onOutcome?.invoke(ctx, busy)
                log.debug("会话繁忙回复被合批抑制 chatId=${ctx.chatId}")
            }
            return
        }

        var acquired = false
        try {
            // 第 2 级：全局许可 + 排队超时（`task_queue_timeout`）。
            // 🔴 等待发生在**任务协程**里，不在 intake ⇒ 排队不冻结管线（见类 KDoc）。
            val waitMillis = runtimeConfig.queueWaitSeconds.toLong() * 1000L
            withTimeoutOrNull(waitMillis.coerceAtLeast(TimeoutPolicy.MIN_TOTAL_MILLIS)) {
                permits.acquire()
                acquired = true            // 🔴 紧跟 acquire 之后置位，中间无挂起点 ⇒ 取消不会造成许可泄漏
            }
            if (!acquired) {
                counters.recordQueueTimeout()
                deliver(ctx, HandlerOutcome.queueTimeout(runtimeConfig.queueWaitSeconds.toLong(), kind))
                return
            }

            // 登记 Job 供 URGENT 精确取消（🔴 用本协程的 Job，即 runtime.launchTask 发放的兄弟 SupervisorJob——登记错对象会让 cancelAll 取消到错的协程树）。
            val self = coroutineContext[Job]
            if (self != null) lanes.register(ctx.chatId, self)
            val outcome = invokeHandler(ctx, entry)
            deliver(ctx, outcome)
        } finally {
            val self = coroutineContext[Job]
            if (self != null) lanes.unregister(ctx.chatId, self)
            if (acquired) permits.release()
            lanes.release(ctx.chatId)
        }
    }

    /**
     * ③ Execute：`withTimeout(总超时) { handler.handle(ctx) }` + 空闲看门狗。
     * 🔴 handler 收到取消 ⇒ `ProgramLauncher` 的三段式 kill 触发（`TimeoutTest` 断言接线正确）；断链即子进程逃逸泄漏。
     */
    private suspend fun CoroutineScope.invokeHandler(ctx: RequestContext, entry: MatchableHandler): HandlerOutcome {
        // ⑮ senderName 解析（执行路径）：在 handler.handle(ctx) 之前，使 handler 与后续 Observe 都能拿到名字。
        // 此处已在 task 协程内（非 Intake 单消费者协程），允许挂起。
        resolveSenderName?.invoke(ctx)

        val kind = taskKindOf(entry.kind)
        val totalMillis = timeouts.totalMillis(entry.handler.timeoutSeconds, entry.kind)
        val idleMillis = timeouts.idleMillis()
        val cause = AtomicReference<TimeoutCause?>(null)
        val started = clock()

        return try {
            val produced = withTimeout(totalMillis) {
                coroutineScope {
                    val holder = AtomicReference<HandlerOutcome?>(null)
                    val work = launch { holder.set(entry.handler.handle(ctx)) }
                    // 🔴 空闲看门狗：只 cancel `work`，不 cancel 外层 ⇒ 总超时与空闲超时天然可区分。
                    val watchdog = if (idleMillis > 0L) {
                        launch {
                            while (isActive) {
                                delay(TimeoutPolicy.IDLE_CHECK_SLICE_MILLIS)
                                if (clock() - ctx.progress.lastActivityMillis >= idleMillis) {
                                    cause.set(TimeoutCause.IDLE)
                                    work.cancel()
                                    break
                                }
                            }
                        }
                    } else {
                        null
                    }
                    work.join()
                    watchdog?.cancel()
                    if (cause.get() == TimeoutCause.IDLE) null else holder.get()
                }
            }
            if (produced != null) {
                if (produced.elapsedMillis == 0L) produced.copy(elapsedMillis = clock() - started) else produced
            } else {
                counters.recordIdleTimeout()
                HandlerOutcome.idleTimeout(idleMillis / 1000L, kind)
            }
        } catch (e: TimeoutCancellationException) {
            // 🔴 必须先于 CancellationException 捕获（前者是后者的子类）。
            counters.recordTotalTimeout()
            log.warn("任务总超时 chatId=${ctx.chatId} route=${entry.routeId} limit=${totalMillis}ms")
            HandlerOutcome.totalTimeout(totalMillis / 1000L, kind)
        } catch (e: CancellationException) {
            // 外部取消（URGENT 停止指令 / stop()）：🔴 静默收尾不回复，原样上抛完成结构化取消。
            counters.recordCancelled()
            throw e
        } catch (e: Throwable) {
            counters.recordHandlerError()
            log.error("handler 抛异常 route=${entry.routeId} kind=${entry.kind}", e)
            HandlerOutcome.error(kind, e.message, clock() - started)
        }
    }

    /**
     * ④ Reply：唯一出口调用点 + ⑤ Observe 交接。
     *
     * 🔴 handler **不得**自行出站（[MessageHandler.handle] 的契约纪律）；一切回复都经此，
     *    因此一切回复都经 [RateGovernor]（NF-05）；绕过即频控/回复账本/出站计数全部失效。
     * [ReplyDirective.SILENT] / [ReplyDirective.ALREADY_REPLIED] ⇒ 不发任何消息，但仍进 ⑤ Observe。
     * 🔴 出站失败**只计数不抛**：一次发送失败不该让任务协程以异常收场（用户已经看到失败文案了）。
     */
    private suspend fun deliver(ctx: RequestContext, outcome: HandlerOutcome) {
        if (outcome.directive != ReplyDirective.SILENT && outcome.directive != ReplyDirective.ALREADY_REPLIED) {
            val card = outcome.card
            // 🔴 显式 rethrow 取消：若用 `runCatching { … }.getOrElse { … }` 会**吞掉 CancellationException**
            // ——出站回复阶段的取消（用户「停止」/ 任务超时 / stop() 关闭）会被误记成一次「回复发送异常」
            // 并计入 `replyFailures`，同时取消信号不再向上传播 ⇒ 协程该死不死。
            // 其余异常仍降级为 null（不阻断审计与计数）。
            val sendResult = try {
                when {
                    card != null -> ctx.reply.card(card)
                    outcome.summary.isNotBlank() -> ctx.reply.markdown(outcome.summary)
                    else -> null
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled // 🔴 取消不是回复失败：不计 replyFailures、不写 warn、原样上抛
            } catch (error: Throwable) {
                log.warn("回复发送异常 chatId=${ctx.chatId}", error)
                null
            }
            if (sendResult != null && !sendResult.ok) counters.recordReplyFailure()
            // 回执别名 seam：SendResult.messageId 此前在此被丢弃——回执别名登记
            //    （引用回执也能命中文件）需要它。成功失败都回调，判定归接线方（O(1) 非挂起纪律同 onOutcome）。
            sendResult?.let { onDelivered?.invoke(ctx, it) }
        }
        counters.recordOutcome(outcome)
        onOutcome?.invoke(ctx, outcome)
    }

    companion object {
        /** 未命中静默路径的审计 summary（不进出站，只落 audit.jsonl）。 */
        const val UNHANDLED_SILENT_SUMMARY: String = "unmatched_silent"

        /** 拒绝且无回复文案时的审计 summary（不进出站，只落 audit.jsonl / sender_stats.jsonl）。 */
        const val REJECTION_SILENT_SUMMARY: String = "rejected_silent"

        /**
         * [RouteKind] → [TaskKind]（UI 渲染用的中文标签）。
         * 🔴 穷尽 `when` 无 `else`：`RouteKind` 新增值时本文件**编译失败**（与 `routeKind` 同一道闸门）。
         * SYSTEM/COMMAND → 指令；AGENT/FILE → Agent（文件消息本质是「下载→抽取→交给 agent」）。
         */
        fun taskKindOf(kind: RouteKind): TaskKind = when (kind) {
            RouteKind.SYSTEM -> TaskKind.COMMAND
            RouteKind.COMMAND -> TaskKind.COMMAND
            RouteKind.AGENT -> TaskKind.AGENT
            RouteKind.FILE -> TaskKind.AGENT
        }
    }
}
