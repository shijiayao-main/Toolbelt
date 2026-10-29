// handler/：8 个能力 handler（System/DispatchAgent/Stop/Interaction/Command/Reply/Agent/File）。
// 🔴 纪律：handler 内零 ProcessBuilder（经 ctx.agent→ProgramLauncher）、零 synchronized/Mutex、
//    零 @Volatile var、零 com.jiaoay.easy_dev_toolbox、审计经 AuditJson（handler 不直接写审计）、
//    不吞 CancellationException；agent 字段每次从 ctx.liveConfig 解析（禁装配期缓存）。
//    违反即出错：ProcessBuilder 走 shell 语义、Windows ANSI charset 会破坏 agent 的 UTF-8 流解析；
//    锁/阻塞原语会卡死 handler 热路径；吞 CE 任务永不结束。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.confirm.ConfirmService
import com.jiaoay.lark.bridge.files.FileRegistry
import com.jiaoay.lark.bridge.files.ReferencedMessageResolver
import com.jiaoay.lark.bridge.pipeline.CardJson
import com.jiaoay.lark.bridge.pipeline.HandlerOutcome
import com.jiaoay.lark.bridge.pipeline.HandlerWeight
import com.jiaoay.lark.bridge.pipeline.MessageHandler
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.gateway.InboundKind

// =====================================================================================
// SYSTEM 槽：SystemHandler(LIGHT) + StopHandler(URGENT)
// =====================================================================================

/**
 * 系统自证与发现（LIGHT）：`ping`（链路自证）+ `/commands`（指令清单——只列引擎保留词，
 * 能力由本地调度 agent 自主判断，见 [renderCommands]）
 * + `/whoami`（回显发送者 openId 与会话 chatId——解决「填白名单需先知道自己的 openId」的鸡生蛋）
 * + `状态` / `/status`（回桥接运行真值——机器人活着才答得上来，
 * 桥接死了自然无回复，「无回复」本身就是离线信号；结构性不可能撒谎）。
 */
class SystemHandler(
    private val deps: HandlerDepsLike,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.SYSTEM
    override val weight: HandlerWeight = HandlerWeight.LIGHT
    override val timeoutSeconds: Long? = null

    override fun match(ctx: RequestContext): Boolean {
        if (ctx.inboundKind != InboundKind.TEXT) return false
        val text = ctx.text.trim()
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        return lower.startsWith(PING) || isCommandsCommand(text) || isWhoamiCommand(text) ||
            isStatusCommand(text) || isHelpCommand(text)
    }

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        val text = ctx.text.trim()
        if (isCommandsCommand(text)) {
            return Responder.decorate(HandlerOutcome.success(TaskKind.COMMAND, renderCommands(ctx)))
        }
        if (isWhoamiCommand(text)) {
            return Responder.decorate(HandlerOutcome.success(TaskKind.COMMAND, renderWhoami(ctx)))
        }
        if (isStatusCommand(text)) {
            return Responder.decorate(HandlerOutcome.success(TaskKind.COMMAND, renderStatus()))
        }
        if (isHelpCommand(text)) {
            // /help 是内置固定回复——不依赖用户可配路由数据。
            return Responder.decorate(HandlerOutcome.success(TaskKind.COMMAND, HELP_REPLY))
        }
        val payload = text.removePrefix(PING).removePrefix("PING").trim()
        val summary = if (payload.isBlank()) "pong 🏓" else "pong 🏓: $payload"
        return Responder.decorate(HandlerOutcome.success(TaskKind.COMMAND, summary))
    }

    /**
     * `/whoami` 回显：发送者 openId + 会话 chatId + 自助指引。
     * 只回调用者自己的标识（`ctx.message.senderId`），不构成信息泄漏；chatId 顺带解决
     * 「App UI 发测试消息不知道目标会话 ID」的来源问题。
     */
    private fun renderWhoami(ctx: RequestContext): String = buildString {
        appendLine("你的 openId：`${ctx.message.senderId}`")
        appendLine("本会话 chatId：`${ctx.chatId}`")
        append("把 openId 填进「访问控制 → 允许的发送者」即可放行自己")
    }.trimEnd()

    /**
     * 清单只列**引擎保留指令**（lib 自知，不枚举引擎能力外的清单——用户可配的 CommandRoute/
     * 触发前缀已退役，其能力由本地调度 agent 自主判断）+ 停止/重置词经 [EngineReservedWords]
     * 现解析（存量自定义词继续展示）。agent 侧工具能力清单由宿主 UI 呈现（依赖方向：lib 不得
     * import feature-larkbridge 的注册表）。
     */
    private fun renderCommands(ctx: RequestContext): String = buildString {
        appendLine("可用指令：")
        appendLine("- `ping` — 链路自证")
        appendLine("- `状态` / `/status` — 查看桥接是否在线")
        appendLine("- `/commands` — 查看本清单")
        appendLine("- `/whoami` — 查看你的 openId 与会话 ID")
        EngineReservedWords.resetWords(ctx.liveConfig).forEach {
            appendLine("- `$it` — 重置会话")
        }
        EngineReservedWords.stopWords(ctx.liveConfig).forEach {
            appendLine("- `$it` — 停止在途任务")
        }
        append("其余消息将由本地 AI 助手自动判断处理")
    }.trimEnd()

    /**
     * `状态` / `/status` 渲染。
     *
     * **字段只用快照真实拥有的**（如「今日处理消息数」无数据源——
     *    PipelineCounters 全为累计值、无日界分桶；UI 的「今日」来自 audit.jsonl 的 UTC epoch-day，
     *    handler 拿不到且时区口径会打架 ⇒ 一律用**累计**口径）。
     * **语义诚实**：不伪造「心跳」字样（SDK 内部 90s ping 无我方可观测钩子）；
     *    「数据截至」来自 sampledAtMillis，是快照真实拥有的量。
     * statusProvider 为默认值或快照缺失 ⇒ 回退「状态暂不可知」（Handler 未装配于生产链路的窄面）。
     */
    private fun renderStatus(): String {
        val snapshot = deps.statusProvider()
        if (snapshot == null || !snapshot.running) {
            return "桥接未运行或状态暂不可知（若你刚发送过消息机器人有响应，可能是配置未保存后重启）"
        }
        return buildString {
            val connection = snapshot.connection
            when (connection) {
                is com.jiaoay.lark.bridge.observe.BridgeConnectionStatus.Connected ->
                    appendLine("🟢 在线${connection.botName?.let { " · $it" } ?: ""}")
                com.jiaoay.lark.bridge.observe.BridgeConnectionStatus.Reconnecting ->
                    appendLine("🟡 在线 · 长连接重连中（期间消息可能延迟）")
                com.jiaoay.lark.bridge.observe.BridgeConnectionStatus.Connecting ->
                    appendLine("🟡 在线 · 建连中")
                is com.jiaoay.lark.bridge.observe.BridgeConnectionStatus.Disconnected ->
                    appendLine("🟠 在线 · 连接已断开：${connection.msg}")
                is com.jiaoay.lark.bridge.observe.BridgeConnectionStatus.Blocked ->
                    appendLine("🔴 在线 · 被阻塞：${connection.detail}")
                is com.jiaoay.lark.bridge.observe.BridgeConnectionStatus.Draining ->
                    appendLine("🟠 在线 · 排空中（${connection.activeTasks} 个在途任务）")
                com.jiaoay.lark.bridge.observe.BridgeConnectionStatus.Idle ->
                    appendLine("⚪ 在线 · 连接空闲")
            }
            snapshot.connectedSinceMillis?.let { since ->
                appendLine("连接自：${formatMillis(since)}（已运行 ${formatDuration(snapshot.sampledAtMillis - since)}）")
            }
            val c = snapshot.counters
            appendLine("累计处理：接收 ${c.intakeReceived} · 完成 ${c.completed} · 失败 ${c.failed}")
            appendLine("存活 agent 进程：${snapshot.liveProcesses}")
            snapshot.lastError?.let { appendLine("最近异常：$it") }
            append("数据截至：${formatMillis(snapshot.sampledAtMillis)}")
        }.trimEnd()
    }

    /** epoch millis → `HH:mm:ss`（运行状态是短时效信息；用 JVM 默认时区，与 UI 时钟口径一致）。 */
    private fun formatMillis(millis: Long): String =
        java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

    /** millis → `1h 23m` / `2d 3h`（人工可读时长；不支持负值——采样时刻早于连接时刻属异常帧，显示为 0）。 */
    private fun formatDuration(millis: Long): String {
        val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    companion object {
        const val PING: String = "ping"
        const val COMMANDS: String = "/commands"
        const val WHOAMI: String = "/whoami"
        const val HELP: String = "/help"

        /**
         * `/help` 的内置回复（默认回复文案，不随路由配置变化）。
         */
        const val HELP_REPLY: String = "**可用指令**\n\n发送 `/commands` 查看完整清单"

        /** 状态指令的中文与斜杠两种形态。 */
        const val STATUS: String = "状态"
        const val STATUS_SLASH: String = "/status"

        /** `/commands` 或 `/commands <id>`（精确前缀 + 边界）。 */
        fun isCommandsCommand(text: String): Boolean {
            val t = text.trim()
            return t == COMMANDS || t.startsWith("$COMMANDS ")
        }

        /** `/whoami` 精确匹配（不吃参数）——带参数属误触，回未命中兜底。 */
        fun isWhoamiCommand(text: String): Boolean = text.trim() == WHOAMI

        /** `/help` 精确匹配（不吃参数，大小写不敏感）。 */
        fun isHelpCommand(text: String): Boolean = text.trim().lowercase() == HELP

        /** `状态` / `/status` 精确匹配（不吃参数，大小写不敏感）——「状态 xx」属误触，回未命中兜底。 */
        fun isStatusCommand(text: String): Boolean {
            val t = text.trim().lowercase()
            return t == STATUS || t == STATUS_SLASH
        }
    }
}

/**
 * 未命中兜底调度 agent（本地 Koog 调度）。**不由路由表装配**——由 `Admission` 的
 * `fallbackHandlerProvider` seam 在「路由未命中任何 handler」时现问现用：
 *
 * - 固定配置（SYSTEM/REPLY/COMMAND/AGENT/FILE 五类 route）按数组序**优先命中**，本 handler 只收
 *   「全部未命中」的消息（用户随口一句话 → 智能判断：自己答 / 调工具 / 转发 CLI agent）；
 * - `handle` 复用 [runAgentPipeline] 全套管线（受理卡/进度卡/表情/产物回传/stderr 落日志），
 *   唯一差异是**不走 AgentRoute 触发词匹配**——整条消息即 prompt；
 * - 🔴 引用流接线：[resolveReferencedPrompt] 四态分流与 [AgentHandler] 同款——
 *   「引用文件 + 命令」消息由本 handler 承接时（调度开启后前缀消息被短路、未命中消息统一兜底，
 *   有引用能力的 AgentHandler/CommandHandler 永远到不了），引用文件懒下载 + 预处理 + 路径拼 prompt
 *   的能力必须在此闭环，否则调度层永远拿不到被引用文件；
 * - 🔴 `match` 恒 false：本 handler 绝不进路由快照（进快照会遮蔽「未命中」语义且破坏数组序优先级），
 *   只经 [MatchableHandler.route]=null 包装走 HEAVY 闸（占许可 + per-chat 上限——调度 agent 可能
 *   内嵌 CLI 转发，重量不可控）；
 * - 可用性判定收口在 `handle` 内：现校 `ctx.agent.isAvailable`（agent 恒为硬依赖，
 *   不可用产出 AGENT_CONFIG_INVALID 显式报错卡，不静默回落——闭包判定与执行之间
 *   配置可能热改，执行前现读是唯一可信时点）。
 */
class DispatchAgentHandler(
    @Suppress("UNUSED_PARAMETER") private val deps: HandlerDepsLike,
    /** 引用文件解析器（对齐 [AgentHandler] 第三参惯例：null = 引用能力关闭，行为零变化）。 */
    private val referencedFiles: ReferencedMessageResolver? = null,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.AGENT
    override val weight: HandlerWeight = HandlerWeight.HEAVY
    override val timeoutSeconds: Long? = null

    /** 🔴 恒 false：只由 fallback seam 显式调用，绝不参与路由匹配。 */
    override fun match(ctx: RequestContext): Boolean = false

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        val text = ctx.text.trim()
        if (text.isEmpty()) {
            return Responder.decorate(HandlerOutcome.skipped(TaskKind.AGENT, "空消息不可调度", silent = true))
        }
        if (!ctx.agent.isAvailable) {
            // 可用性闸：执行前现读（闭包判定与执行之间配置可能热改）。
            // 死区兜底明确告诉用户去 AI 设置页配置端点（不静默也不误导）；
            // TaskErrorHint 对该码区分 Koog（指 AI 设置页端点）/CLI（指模板）。
            val hint = if (ctx.agent.backendId == "koog") {
                "本地 AI 未就绪：请在应用的「AI 设置」页配置模型端点（Base URL / API Key / 模型）后重试"
            } else {
                "本地调度 agent 不可用（后端不可用）"
            }
            return Responder.decorate(
                HandlerOutcome.failure(
                    TaskKind.AGENT,
                    hint,
                    errorCode = TaskErrorCodes.AGENT_CONFIG_INVALID,
                ),
            )
        }
        // baseText = 整条原文（不剥任何前缀）——「prompt 保留原文」是既有契约
        //    （DispatchAgentRouteTest 钉死），剥前缀语义由调度 agent 的系统提示词自理。
        return when (val resolved = resolveReferencedPrompt(referencedFiles, ctx, TaskKind.AGENT, text)) {
            is ReferencedPromptOutcome.Failed -> resolved.outcome
            is ReferencedPromptOutcome.Prompt -> try {
                // 🔴 在途保护：agent 管线（含 CLI 转发长任务）期间引用目录 pin 住，收尾 finally unpin——漏 pin 则在途 agent 的引用文件会被下载区逐出、任务中途丢文件。
                Responder.decorate(runAgentPipeline(ctx, resolved.text, TaskKind.AGENT, allowedFiles = resolved.allowedFiles, homeDataRoot = deps.dataRootProvider(), replyLedger = deps.replyLedgerProvider(), nativeSurface = deps.nativeSurfaceFactory?.invoke(ctx), confirmGate = deps.confirmGateFactory?.invoke(ctx)))
            } finally {
                resolved.pinnedDirs.forEach { referencedFiles?.store?.unpin(it) }
            }
        }
    }
}

/**
 * 停止在途任务（URGENT）。匹配引擎停止词（[EngineReservedWords.stopWords]——routes 存量值 →
 * retiredV8 迁移旧词 → 常量默认「停止」三段来源，与 AgentRoute 数据解耦）。
 *
 * ## 为什么是 URGENT
 * URGENT 分支会先 `lanes.cancelAll(chatId)` 取消该 chat 的在途 HEAVY 任务——对**停止语义恰恰正确**：
 * 用户发「停止」就是要杀掉正在跑的 agent。取消传播 ⇒ `ProgramLauncher` 三段式 kill 触发。
 *
 * ## 与 ConfirmService 的协作
 * `ConfirmService.cancelAll()` 把在途确认请求置 [ConfirmService.Decision.CANCELLED]——按其 KDoc，
 * 唯有 CANCELLED（用户停止指令）才随停止逻辑 kill 进程（TIMEOUT/DENIED 都不 kill）。
 */
class StopHandler(
    private val confirmService: ConfirmService?,
    private val deps: HandlerDepsLike,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.SYSTEM
    override val weight: HandlerWeight = HandlerWeight.URGENT
    override val timeoutSeconds: Long? = null

    override fun match(ctx: RequestContext): Boolean {
        if (ctx.inboundKind != InboundKind.TEXT) return false
        return EngineReservedWords.isStop(ctx.text, ctx.liveConfig)
    }

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        // 在途 HEAVY 任务的取消由 Admission 的 URGENT 分支（lanes.cancelAll）已完成；
        //    此处补一刀：取消在途确认请求（等待方收到 CANCELLED，随停止逻辑 kill agent 进程）。
        confirmService?.cancelAll()
        deps.log.info("停止指令 chatId=${ctx.chatId} sender=${ctx.message.senderId}")
        return Responder.decorate(HandlerOutcome.success(TaskKind.COMMAND, "已停止当前会话的在途任务"))
    }

    companion object {
        /** 兼容别名（旧配置默认停止词的消费点等价物）——新代码一律走 [EngineReservedWords]。 */
        const val DEFAULT_STOP: String = EngineReservedWords.STOP
    }
}

// =====================================================================================
// COMMAND 槽：InteractionHandler(LIGHT) + CommandHandler(LIGHT/HEAVY)
// =====================================================================================

/**
 * 裁决入口（**LIGHT**）：卡片按钮回传 + 文本「确认/拒绝 <id>」两路输入合并到同一裁决流。
 *
 * ## 为什么是 LIGHT 而非 URGENT
 * 三条独立证据：
 * 1. **`ConfirmService` 语义**：确认请求与 agent 进程 1:1、`awaitDecision` 挂起、**只有 CANCELLED（用户停止指令）
 *    才 kill 进程**，TIMEOUT/DENIED 都不 kill。URGENT 分支的 `lanes.cancelAll(chatId)` 会杀掉正在等这次裁决的
 *    agent 子进程 ⇒ 确认流必然断裂。
 * 2. **裁决需即时生效**：确认按钮响应必须快，但即时**绝不等于 cancel 在途 agent**。
 * 3. **🔴 若为 HEAVY 会死锁**：chat lane 已被「正在等裁决的 agent」占满，裁决消息排不进去 ⇒ LIGHT 绕过许可
 *    正是唯一可行解。
 * ⇒ 取 LIGHT：不占许可、直接 launch、**不触发 cancelAll**，裁决即时送达且不伤在途 agent。
 */
class InteractionHandler(
    private val confirmService: ConfirmService?,
    private val deps: HandlerDepsLike,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.COMMAND
    override val weight: HandlerWeight = HandlerWeight.LIGHT
    override val timeoutSeconds: Long? = null

    override fun match(ctx: RequestContext): Boolean {
        if (ctx.isCardAction) return true
        if (ctx.inboundKind != InboundKind.TEXT) return false
        return DECISION_REGEX.matches(ctx.text.trim())
    }

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        val service = confirmService
            ?: return Responder.decorate(HandlerOutcome.skipped(TaskKind.COMMAND, "确认功能未启用"))
        val approved: Boolean
        val id: String
        if (ctx.isCardAction) {
            val value = ctx.message.cardAction?.actionValue.orEmpty()
            // 确认卡按钮 value 形如 "<token>:allow" / "<token>:deny"（见 CardJson.confirm）
            val idx = value.lastIndexOf(':')
            if (idx <= 0) {
                return Responder.decorate(HandlerOutcome.skipped(TaskKind.COMMAND, "非裁决类卡片回传", silent = true))
            }
            id = value.substring(0, idx)
            approved = value.substring(idx + 1) == ACCEPT_VALUE
        } else {
            val m = DECISION_REGEX.matchEntire(ctx.text.trim())
                ?: return Responder.decorate(HandlerOutcome.skipped(TaskKind.COMMAND, "非确认指令", silent = true))
            val (verb, parsedId) = m.destructured
            approved = verb == VERB_APPROVE
            id = parsedId
        }
        val outcome = when (val result = service.decide(id, ctx.chatId, ctx.message.senderId, approved)) {
            is ConfirmService.DecideResult.Ok -> {
                // 裁决成功 ⇒ 把**原确认卡**整卡替换为已答态（按钮消失、标注结果），
                //    消灭「原卡按钮滞留 + 新发文字回执」的状态脱节。
                //    cardMessageId/toolName/inputSummary 都是裁决时快照捎带（发卡后回填 + 条目快照，
                //    见 GatewayConfirmNotifier / ConfirmService.decide）；cardMessageId 为 null =
                //    未发卡/未回填（SyntheticGateway 默认实现等）⇒ 跳过更新，行为同旧版。
                //    正文末行补「单据：cf-xxxx（事后对账短标识）」（result.requestId 快照捎带）。
                //    🔴 更新失败只 log 不阻断（已答态是增强，降级即回到现状「文字回执」）。
                val cardMessageId = result.cardMessageId
                if (cardMessageId != null) {
                    runCatching {
                        ctx.reply.updateExistingCard(
                            messageId = cardMessageId,
                            spec = CardJson.answeredConfirm(
                                approved = approved,
                                toolName = result.toolName,
                                markdown = buildString {
                                    append(if (approved) "**已批准** ✓" else "**已拒绝** ✗")
                                    append("\n**工具**：`").append(result.toolName).append("`")
                                    if (result.inputSummary.isNotBlank()) {
                                        append("\n**参数**：").append(result.inputSummary)
                                    }
                                    // 事后对账短标识——cf- 前缀 + shortId 末段 hex4
                                    //    （与 requestId 生成格式 cf-<seq>-<hex4> 对账一致；
                                    //    shortId 本身不带前缀故手写）。短标识是末段 hex4 前缀匹配（matchesIdPrefix
                                    //    语义），seq 不同 hex4 相同时非唯一——对账用非唯一键，不发明新格式。
                                    append("\n单据：cf-")
                                        .append(ConfirmService.shortId(result.requestId))
                                        .append("（事后对账短标识）")
                                },
                            ),
                        )
                    }.onFailure {
                        deps.log.warn("确认卡已答态更新失败 id=$cardMessageId（已降级为文字回执）：${it.message}")
                    }
                }
                HandlerOutcome.success(
                    TaskKind.COMMAND,
                    if (approved) "已批准，agent 将继续执行" else "已拒绝，agent 将调整策略",
                )
            }
            ConfirmService.DecideResult.NotFound ->
                HandlerOutcome.skipped(TaskKind.COMMAND, "确认请求不存在或已处理（#$id）")
            ConfirmService.DecideResult.Forbidden ->
                HandlerOutcome.skipped(TaskKind.COMMAND, "仅原请求者可确认该操作")
        }
        deps.log.info("裁决 id=$id approved=$approved chat=${ctx.chatId} -> $outcome")
        return Responder.decorate(outcome)
    }

    companion object {
        const val VERB_APPROVE: String = "确认"
        const val VERB_DENY: String = "拒绝"
        const val ACCEPT_VALUE: String = "allow"

        /** 「确认 &lt;id&gt;」/「拒绝 &lt;id&gt;」（id 为短标识）。 */
        val DECISION_REGEX: Regex = Regex("^($VERB_APPROVE|$VERB_DENY)\\s+([0-9a-f]{1,16})$")
    }
}

/**
 * 本地工具指令（**LIGHT(清单查询) / HEAVY(委托 agent)**，经 [weightFor] 表达）。
 * 最长别名前缀 + 首字符边界校验 → 渲染 `promptTemplate` 委托 agent。
 *
 * ## 动态档位
 * - `promptTemplate` 空白 ⇒ **纯信息/清单查询**，不跑 agent ⇒ LIGHT；
 * - `promptTemplate` 非空 ⇒ 委托 agent 执行 ⇒ HEAVY（占许可 + ChatLane）。
 */
class CommandHandler(
    private val route: CommandRoute,
    private val deps: HandlerDepsLike,
    /** 引用文件解析器（装配期单例注入；null = 引用能力关闭，行为零变化）。 */
    private val referencedFiles: ReferencedMessageResolver? = null,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.COMMAND
    override val weight: HandlerWeight = HandlerWeight.HEAVY
    override val timeoutSeconds: Long? = null

    /** 🔴 纯 CPU、O(别名数)、零 IO、零锁：命中别名且模板非空 ⇒ HEAVY，否则 LIGHT（weightFor 在 submit 热路径求值，IO/锁会卡死 intake）。 */
    override fun weightFor(ctx: RequestContext): HandlerWeight =
        if (matchedAlias(ctx.text) != null && route.promptTemplate.isNotBlank()) HandlerWeight.HEAVY else HandlerWeight.LIGHT

    override fun match(ctx: RequestContext): Boolean {
        if (ctx.inboundKind != InboundKind.TEXT) return false
        return matchedAlias(ctx.text) != null
    }

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        val alias = matchedAlias(ctx.text)
            ?: return Responder.decorate(HandlerOutcome.skipped(TaskKind.SKILL, "未命中快捷指令", silent = true))
        val args = ctx.text.trim().removePrefix(alias).trim()
        val template = route.promptTemplate

        // 清单/信息分支（模板空白）：不跑 agent，直接回指令说明（LIGHT）
        if (template.isBlank()) {
            val info = route.description.ifBlank { route.name }
            return Responder.decorate(HandlerOutcome.success(TaskKind.SKILL, info))
        }

        // 错误码优先级：执行器不可用 **优先于** 确认不可用
        if (!ctx.agent.isAvailable) {
            return Responder.decorate(
                HandlerOutcome.failure(
                    TaskKind.SKILL,
                    "执行后端不可用，无法执行快捷指令「${route.name}」",
                    errorCode = TaskErrorCodes.COMMAND_AGENT_DISABLED,
                ),
            )
        }
        if (route.requiresConfirm && !confirmAvailable(ctx)) {
            return Responder.decorate(
                HandlerOutcome.failure(
                    TaskKind.SKILL,
                    "快捷指令「${route.name}」需运行时确认，但当前后端不支持或确认未启用，已阻止执行",
                    errorCode = TaskErrorCodes.COMMAND_CONFIRM_UNAVAILABLE,
                ),
            )
        }
        val basePrompt = renderTemplate(template, args)
        if (basePrompt.isBlank()) {
            return Responder.decorate(
                HandlerOutcome.failure(
                    TaskKind.SKILL,
                    "快捷指令「${route.name}」的 promptTemplate 为空，请检查配置",
                    errorCode = TaskErrorCodes.COMMAND_TEMPLATE_EMPTY,
                ),
            )
        }
        return when (val resolved = resolveReferencedPrompt(referencedFiles, ctx, TaskKind.SKILL, basePrompt)) {
            is ReferencedPromptOutcome.Failed -> resolved.outcome
            is ReferencedPromptOutcome.Prompt -> try {
                // 🔴 在途保护：agent 管线（含子进程长跑）期间引用目录 pin 住，收尾 finally unpin——漏 pin 则在途 agent 的引用文件会被下载区逐出、任务中途丢文件。
                Responder.decorate(runAgentPipeline(ctx, resolved.text, TaskKind.SKILL, allowedFiles = resolved.allowedFiles, homeDataRoot = deps.dataRootProvider(), replyLedger = deps.replyLedgerProvider(), nativeSurface = deps.nativeSurfaceFactory?.invoke(ctx), confirmGate = deps.confirmGateFactory?.invoke(ctx)))
            } finally {
                resolved.pinnedDirs.forEach { referencedFiles?.store?.unpin(it) }
            }
        }
    }

    /**
     * 最长别名前缀 + 首字符边界校验：`text == alias` 或 `text` 以 `alias` 开头且紧跟空白
     * （避免 `ai` 遮蔽 `ai-log`）。多别名命中取最长者。
     */
    private fun matchedAlias(rawText: String): String? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        return route.aliases
            .filter { it.isNotBlank() }
            .filter { alias ->
                text == alias || (text.startsWith(alias) && text.length > alias.length && text[alias.length].isWhitespace())
            }
            .maxByOrNull { it.length }
    }

    /** 运行时确认是否可用（执行器可用即可——确认链无单独开关，不可用即 fail-closed 拒绝）。 */
    private fun confirmAvailable(ctx: RequestContext): Boolean = ctx.agent.isAvailable

    companion object {
        const val ARGS_PLACEHOLDER: String = "{args}"

        /** 模板渲染：空模板 ⇒ 透传 args。 */
        fun renderTemplate(template: String, args: String): String =
            if (template.isBlank()) args else template.replace(ARGS_PLACEHOLDER, args)
    }
}

// =====================================================================================
// AGENT 槽：AgentHandler(HEAVY)
// =====================================================================================

/**
 * 本地 agent（HEAVY）：最长触发前缀（`agent.triggers`，默认 `["ai","/ai"]`）+ 边界校验；`resetCommand` 重置会话。
 * `resetCommand`（新对话）清的是生效执行器的会话（调度开启时即 Koog 的多轮上下文，
 * 并传导重置 call_cli_agent 委托出去的 CLI 会话；长期记忆/工作区文件不在重置面——回执如实披露）。
 * 短路判定 =「生效执行器是 Koog 且可用」：App 形态（koog 在位）前缀恒不命中、未命中消息经
 *    fallback seam 全进 DispatchAgentHandler；CLI 形态（backendId 恒非 koog）前缀照旧直跑；
 *    端点未配置（isAvailable=false）前缀回落 AgentHandler 直跑并明确报错（死区回落语义保留，
 *    防三重静默）。
 */
class AgentHandler(
    private val route: AgentRoute,
    private val deps: HandlerDepsLike,
    /** 引用文件解析器（装配期单例注入；null = 引用能力关闭，行为与旧版逐字一致——存量测试零改动）。 */
    private val referencedFiles: ReferencedMessageResolver? = null,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.AGENT
    override val weight: HandlerWeight = HandlerWeight.HEAVY
    override val timeoutSeconds: Long? = null

    override fun match(ctx: RequestContext): Boolean {
        if (ctx.inboundKind != InboundKind.TEXT) return false
        val text = ctx.text.trim()
        if (text.isEmpty()) return false
        // resetCommand **放行在前**——「新对话」指令清的正是调度 agent（Koog）的
        //    ChatMemory 会话，经 [EngineReservedWords] 三段解析；
        //    triggers 前缀命中被「生效执行器是 Koog 且可用」短路（App 形态全量消息归调度
        //    agent 判断；CLI 形态恒不短路，前缀照旧直跑）。
        // 🔴 死区防线语义保留：调度 agent 不可用（端点未配置）时前缀照旧命中本 handler
        //    （产出明确报错而非静默）——「前缀被屏蔽 + 兜底不接 + 无兜底文案」三重静默的防线。
        if (EngineReservedWords.isReset(text, ctx.liveConfig)) return true
        if (ctx.agent.backendId == "koog" &&
            ctx.agent.isAvailable
        ) return false
        return matchedTrigger(text, ctx.liveConfig) != null
    }

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        val text = ctx.text.trim()
        if (EngineReservedWords.isReset(text, ctx.liveConfig)) {
            val reset = ctx.agent.resetSession(ctx.sessionKey)
            // 详细回执（历史形态的会话销毁口径；null = 无详细回执，既有文案逐字不变）
            // ——读取一次即消费，调用方不缓存。
            val detailedReceipt = ctx.agent.resetReceipt(ctx.sessionKey)
            // 回执如实披露重置面口径：清的是会话态（当轮上下文）；
            // 长期记忆/工作区文件/下载文件不在重置面——说「全部清空」会诱导用户
            // 用「新对话」当清记忆/清文件的手段（实际不清，回执即谎言）。
            val summary = if (reset) {
                "已重置对话（会话上下文已清空），下次提问将开启新会话。" +
                    "注意：agent 长期记忆、工作区文件与已下载文件不在重置面，仍会保留" +
                    (detailedReceipt?.let { "\n$it" } ?: "")
            } else {
                "当前没有可重置的会话"
            }
            return Responder.decorate(HandlerOutcome.success(TaskKind.AGENT, summary))
        }
        if (!ctx.agent.isAvailable) {
            return Responder.decorate(
                HandlerOutcome.failure(
                    TaskKind.AGENT,
                    "agent 后端不可用（可执行文件未找到）",
                    errorCode = TaskErrorCodes.AGENT_BACKEND_UNAVAILABLE,
                ),
            )
        }
        val trigger = matchedTrigger(text, ctx.liveConfig) ?: return Responder.decorate(
            HandlerOutcome.skipped(TaskKind.AGENT, "未命中 agent 触发前缀", silent = true),
        )
        val basePrompt = text.removePrefix(trigger).trim()
        return when (val resolved = resolveReferencedPrompt(referencedFiles, ctx, TaskKind.AGENT, basePrompt)) {
            is ReferencedPromptOutcome.Failed -> resolved.outcome
            is ReferencedPromptOutcome.Prompt -> try {
                // 🔴 在途保护：agent 管线（含子进程长跑）期间引用目录 pin 住，收尾 finally unpin——漏 pin 则在途 agent 的引用文件会被下载区逐出、任务中途丢文件。
                Responder.decorate(runAgentPipeline(ctx, resolved.text, TaskKind.AGENT, allowedFiles = resolved.allowedFiles, homeDataRoot = deps.dataRootProvider(), replyLedger = deps.replyLedgerProvider(), nativeSurface = deps.nativeSurfaceFactory?.invoke(ctx), confirmGate = deps.confirmGateFactory?.invoke(ctx)))
            } finally {
                resolved.pinnedDirs.forEach { referencedFiles?.store?.unpin(it) }
            }
        }
    }

    /**
     * 最长触发前缀 + 边界校验（`ai` 不遮蔽 `ai-log`）。
     * 数据源是 [EngineReservedWords.triggerPrefixes]（retiredV8 旧前缀 + 常量默认）——
     *    CLI 前缀匹配的唯一真源。
     */
    private fun matchedTrigger(text: String, config: com.jiaoay.lark.bridge.config.BridgeConfig): String? =
        EngineReservedWords.triggerPrefixes(config)
            .filter { it.isNotBlank() }
            .filter { t -> text == t || (text.startsWith(t) && text.length > t.length && text[t.length].isWhitespace()) }
            .maxByOrNull { it.length }
}

// =====================================================================================
// FILE 槽：FileHandler(LIGHT)
// =====================================================================================

/**
 * 文件消息（LIGHT）：`messageType==file` → **仅登记**进 [FileRegistry] →
 * 恒静默登记；**不下载、不起 agent**——下载发生在用户引用回复该消息
 * 并命中 Agent/Command 路由时（[ReferencedMessageResolver] 懒下载）。
 *
 * 「收到文件即下载分析」与「关键词门控挂起窗口」两种旧形态均已删除——
 *    通用底座上任意白名单用户发文件即自动起 agent 子进程是成本与安全双炸弹；
 *    新流程用一行用户动作（引用 + 指令）换取显式意图。
 * LIGHT 档理由：登记 + 回复是毫秒级 CPU + 一次出站，不占 PermitGate/ChatLane 配额——
 *    否则两个在途 agent 会让第三个人的文件登记排队 30s，引用体验断裂。
 * 群聊豁免配套：`PolicyGate` 对 `InboundKind.FILE` 豁免群@门控
 *    （文件消息物理上无法携带 @），登记是被动行为无下载无 agent，AccessControl 白名单仍前置。
 *    该豁免是文件消息进入管线的**唯一放行依据**（SDK 侧恒全放行，见 `BridgeGateways.policy()`）。
 *
 * @param registry 登记表（🔴 装配期单例：PipelineSnapshot 每次 HOT 重建都 new handler 实例，
 *    registry 必须跨快照存活）。缺省自建仅供测试。
 * @param counters 管线计数器（filesRegistered 观测；null = 测试不计数）。
 */
class FileHandler(
    private val route: FileRoute,
    private val deps: com.jiaoay.lark.bridge.pipeline.HandlerDeps,
    private val registry: FileRegistry = FileRegistry(),
    private val counters: PipelineCounters? = null,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.FILE
    override val weight: HandlerWeight = HandlerWeight.LIGHT
    override val timeoutSeconds: Long? = null

    /** 🔴 纯谓词、零副作用（登记在 handle——谓词带副作用会让 HOT 重建的 resolve 试匹配产生幻影登记）。 */
    override fun match(ctx: RequestContext): Boolean {
        if (ctx.inboundKind != InboundKind.FILE) return false
        if (!ctx.liveConfig.fileMessage.enabled) return false
        return ctx.message.resources.any { !it.fileKey.isNullOrBlank() }
    }

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        val resources = ctx.message.resources
            .filter { !it.fileKey.isNullOrBlank() }
            .map { FileRegistry.ResourceRef(fileKey = it.fileKey.orEmpty(), fileName = it.fileName ?: "file") }
        if (resources.isEmpty()) {
            return Responder.decorate(
                HandlerOutcome.failure(TaskKind.COMMAND, "文件下载失败，请稍后重试", errorCode = TaskErrorCodes.FILE_DOWNLOAD_FAILED),
            )
        }

        // 登记（幂等覆盖：同 messageId 重发以最新为准；时间戳由 registry clock 盖章）
        registry.register(
            messageId = ctx.message.messageId,
            chatId = ctx.chatId,
            senderId = ctx.message.senderId,
            resources = resources,
        )
        counters?.recordFileRegistered()
        deps.log.info("文件已登记 fileName=${resources.first().fileName} messageId=${ctx.message.messageId} chatId=${ctx.chatId}")

        // 回执：文件登记默认静默（引用流程本就有下载进度卡反馈，回执卡是重复噪音）；
        // 登记事实仍经 FILE kind outcome + Observe 审计留痕（silent skipped 不丢审计）。
        return Responder.decorate(HandlerOutcome.skipped(TaskKind.COMMAND, "文件已登记（静默）", silent = true))
    }
}

/**
 * handler 构造期依赖的**本地别名**（等价 [com.jiaoay.lark.bridge.pipeline.HandlerDeps]，仅为缩短 import）。
 * 只含跨消息稳定的 `configProvider` 与 `log`（每消息依赖走 [RequestContext]）。
 */
typealias HandlerDepsLike = com.jiaoay.lark.bridge.pipeline.HandlerDeps
