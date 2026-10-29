package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.lark.bridge.agent.AgentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 直接答复：轻量问答首选。 */
internal class ReplyDirectTool(
    private val request: AgentRequest,
) : SimpleTool<ReplyDirectTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "reply_direct",
    description = "Send a direct text reply to the user. Preferred for lightweight questions, chitchat, " +
        "concept explanations, and quick judgments.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The reply text to send to the user, in the user's language (Chinese by default)")
        val text: String,
    )

    override suspend fun execute(args: Args): String {
        val reply = BridgeToolRegistry.replySinkOf(request)
            ?: return DENIED_HINT
        val result = reply.text(args.text)
        // 🔴 成功文案必须附 messageId——recall_own_message 的
        //    设计前提是 LLM「从自己发送结果里拿 message id」，文案把 SendResult.messageId
        //    丢弃 ⇒ LLM 拿不到 id ⇒ recall 恒失败并自创绕路。
        //    分片场景附主 id（chunkMessageIds 的逐一交付文案复杂度不抵收益，同账本只记主 id 口径）。
        return if (result.ok) {
            "已发送给用户（message_id=" + (result.messageId ?: "?") +
                "）。若需要撤回可用 recall_own_message。若任务已完成，后续不要再调用任何工具，直接结束并给出简短收尾。"
        } else {
            "发送失败：${result.error}"
        }
    }

    companion object {
        internal const val DENIED_HINT = "出站通道不可用（本消息无回复能力面）"
    }
}

/**
 * 出站交付的共用围栏判定（从 [SendFileTool] 提取——send_image 与
 * send_file 判据**完全一致**，防双写漂移）。
 *
 * 判据序（各出站工具共用，拒绝文案逐字不变）：
 * 1. `artifactsEnabled == false` ⇒ 拒（与 `agent.artifacts.enabled` 用户开关对齐：关掉「产物回传」
 *    即不许 agent 往外发文件，任何文件类工具不得成为旁路）；
 * 2. 围栏前移：「命中精确允许集（引用链 + 工具产物）∨
 *    命中工作区沙箱」放行，否则统一回 [com.jiaoay.lark.bridge.exec.SensitiveFilePolicy.OPAQUE_DENIED_HINT]
 *    （不含存在性信息，防 LLM 把本工具当路径探测器）；`sandbox == null` 且无允许集 ⇒ 恒拒（fail-safe）；
 * 3. 存在性：不是文件 ⇒ 拒（此时已过围栏，存在性信息不泄漏）；
 * 4. [com.jiaoay.lark.bridge.exec.SensitiveFilePolicy] 排除表 ⇒ 拒（防「引用文件注入 → 诱导外发敏感文件」）。
 *
 * @return null = 放行（调用方继续后续步骤）；非 null = 拒绝文案（调用方直接返回给 LLM）
 */
internal fun outboundDeliveryFence(
    filePath: String,
    context: com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext?,
    sandbox: com.jiaoay.lark.bridge.exec.WorkspacePolicy?,
    artifactsEnabled: Boolean,
    homeDataRoot: java.io.File?,
): String? {
    // 前置闸 1：产物回传总开关。
    if (!artifactsEnabled) {
        return "产物回传已关闭（桥接配置「产物回传」开关）：请让用户在飞书桥接设置中开启后再试，或改用文字答复。"
    }
    val file = java.io.File(filePath)
    // 前置闸 2：围栏判定（🔴 `requireInsideWorkspace` 对不可解析路径抛 IOException ⇒ runCatching，
    //    异常按拒绝处理，与 SendFileTool 原私有 insideSandbox 同款）。
    val insideSandbox = sandbox?.let { runCatching { it.requireInsideWorkspace(file) }.getOrDefault(false) } == true
    val authorized = context?.authorize(filePath)
    if ((context != null && authorized == null && !insideSandbox) ||
        (context == null && !insideSandbox)
    ) {
        return com.jiaoay.lark.bridge.exec.SensitiveFilePolicy.OPAQUE_DENIED_HINT
    }
    if (!file.isFile) return "文件不存在：$filePath"
    // 前置闸 3：敏感/凭据文件排除表。
    if (com.jiaoay.lark.bridge.exec.SensitiveFilePolicy.isForbidden(file, homeDataRoot)) {
        return com.jiaoay.lark.bridge.exec.SensitiveFilePolicy.DENIED_HINT
    }
    return null
}

internal class SendCardTool(
    private val request: AgentRequest,
) : SimpleTool<SendCardTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "send_card",
    description = "Send an interactive-format card with a title and markdown body to the user. " +
        "Use for structured results (tables, lists, step-by-step summaries).",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Card title (short, no markdown)")
        val title: String,
        @property:LLMDescription("Card body in markdown format")
        val markdown: String,
    )

    override suspend fun execute(args: Args): String {
        val reply = BridgeToolRegistry.replySinkOf(request) ?: return ReplyDirectTool.DENIED_HINT
        val result = reply.card(
            com.jiaoay.lark.gateway.CardSpec(
                title = args.title,
                markdown = args.markdown,
            ),
        )
        // 成功文案同 reply_direct——附 messageId 供 recall/update_own_card。
        return if (result.ok) "卡片已发送（message_id=" + (result.messageId ?: "?") + "）" else "卡片发送失败：${result.error}"
    }
}

// =====================================================================================
// P4 会话作用域三件
// 🔴 硬边界（写死在 KDoc 防误扩）：三件都钉「当前会话作用域」——目标消息/会话来自
//    DispatchPayload 载体（本条用户消息的 messageId/chatId/引用链），不接受 LLM 指定任意
//    chatId/messageId 跨会话操作。
// 🔴 分级表述：**L1 会话内
//    原生操作恒可用**（消息/表情/图片/话题——本区工具 + send_file/send_card 家族）；**跨会话
//    能力**（拉群历史/成员拉取、建群、全局搜索用户、读文档）属 L2——payload.nativeSurface
//    接缝在位即注册（agent.nativeTools 配置闸已随 v11→v12 退役，授权闸收敛为飞书后台 scope）；
//    未注册时 LLM 应如实告知用户「该能力未开通」，不得试错；**替用户身份操作/
//    撤回他人消息/凭证读取恒排除**（L3，无配置载体，不得越线）。
//    生命周期操作（start/stop 桥接）同样恒排除。
// =====================================================================================

/**
 * 给消息加/撤表情回应（当前会话作用域）。
 * 默认目标是**用户本条消息**（载体 messageId）——agent 表达「收到/认可/完成」的轻量通道；
 * 仅当用户明确要求对自己的**引用消息**加表情时才用 quoted_message_id（且须属本会话——
 * 飞书 react API 按 messageId 操作，跨会话消息平台自身会拒绝，这里不重复校验）。
 */
internal class ReactEmojiTool(
    private val request: AgentRequest,
) : SimpleTool<ReactEmojiTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "react_emoji",
    description = "Add an emoji reaction to a message in the CURRENT chat (default: the user's message " +
        "you are replying to). Use for lightweight acknowledgments (thumbs up on request received, " +
        "checkmark on quick confirmation). Do NOT use it as the reply itself — always reply with text too.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Feishu emoji key, e.g. OnIt, THUMBSUP, DONE, OK. Use system defaults")
        val emoji_key: String,
        @property:LLMDescription("Target message id. Leave empty to react to the user's current message")
        val message_id: String = "",
        @property:LLMDescription("Set true to REMOVE the reaction instead of adding it")
        val remove: Boolean = false,
    )

    override suspend fun execute(args: Args): String {
        val payload = BridgeToolRegistry.payloadOf(request)
            ?: return "表情回应能力不可用（无会话上下文）"
        val target = args.message_id.takeIf { it.isNotBlank() } ?: payload.messageId
        if (target.isBlank()) return "表情回应能力不可用（无目标消息）"
        if (args.emoji_key.isBlank()) return "emoji_key 不能为空（如 OnIt / THUMBSUP / OK）"
        val ok = payload.reply.react(target, args.emoji_key, add = !args.remove)
        return if (ok) {
            if (args.remove) "已撤回表情 ${args.emoji_key}" else "已添加表情 ${args.emoji_key}"
        } else {
            "表情操作失败（emoji key 可能无效或消息不存在）"
        }
    }
}

/**
 * 取回本会话中被引用消息的正文（当前会话作用域）。
 * 用户引用了一条消息（文本/机器人卡片回复）而载体围栏里没有它时，用本工具查机器人回复账本；
 * 空白目标回落到用户本条消息的引用链（quotedMessageId）。🔴 账本查询带 chatId 本会话校验
 * ——A 会话读不到 B 会话的机器人回复。
 */
internal class FetchQuotedContextTool(
    private val request: AgentRequest,
) : SimpleTool<FetchQuotedContextTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "fetch_quoted_context",
    description = "Fetch the content of a quoted (replied-to) message in the CURRENT chat from the " +
        "local reply ledger. Use when the user's message quotes one of YOUR earlier card replies and " +
        "you need its full text as context. Leave message_id empty to use the message the user quoted.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The quoted message id. Leave empty to use the message the user replied to")
        val message_id: String = "",
    )

    override suspend fun execute(args: Args): String {
        val payload = BridgeToolRegistry.payloadOf(request)
            ?: return "引用上下文能力不可用（无会话上下文）"
        val ledger = payload.replyLedger
            ?: return "引用上下文能力不可用（本地回复账本未启用）"
        val target = args.message_id.takeIf { it.isNotBlank() } ?: payload.quotedMessageId
        if (target.isNullOrBlank()) return "没有可取回的引用消息（用户消息未引用任何消息）"
        val text = ledger.lookup(target, payload.chatId)
            ?: return "引用消息不在本地账本中（可能是文件消息或重启前发出的旧回复）——请让用户直接粘贴内容"
        return text
    }
}

/**
 * 原位更新机器人自己发出的**卡片消息**（当前会话作用域）。
 * 载体 replyLedger 之外还须经 ReplySink.updateCard 出站；目标是本会话里机器人自己的卡片。
 * 仅支持更新**机器人自己发的卡**——更新用户消息/他人消息平台会拒绝；卡片 messageId
 *    须来自本会话（工具描述要求 LLM 只更新它在本会话刚发出的卡）。
 */
internal class UpdateOwnCardTool(
    private val request: AgentRequest,
) : SimpleTool<UpdateOwnCardTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "update_own_card",
    description = "Update a card message YOU previously sent in the CURRENT chat (new title and " +
        "markdown replace the old content in place). Use to correct or refine a card you just sent. " +
        "Only cards sent by you in this chat can be updated.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The message id of YOUR card to update (from send_card result or the ledger)")
        val message_id: String,
        @property:LLMDescription("New card title (short, no markdown)")
        val title: String,
        @property:LLMDescription("New card body in markdown format")
        val markdown: String,
    )

    override suspend fun execute(args: Args): String {
        val payload = BridgeToolRegistry.payloadOf(request)
            ?: return "卡片更新能力不可用（无会话上下文）"
        if (args.message_id.isBlank()) return "message_id 不能为空（用 send_card 发送结果里的消息 id）"
        // 会话作用域校验：目标卡必须记在**本会话**的机器人回复账本里——
        // 账本 lookup 带 chatId，跨会话 messageId 直接 miss ⇒ 拒绝更新。
        val ledger = payload.replyLedger
        val own = ledger?.lookup(args.message_id, payload.chatId)
        if (own == null) {
            return "只能更新你自己在当前会话发送的卡片（messageId 不在本会话的本地账本中）"
        }
        val result = payload.reply.updateExistingCard(
            args.message_id,
            com.jiaoay.lark.gateway.CardSpec(title = args.title, markdown = args.markdown),
        )
        return if (result.ok) "卡片已更新" else "卡片更新失败：${result.error}（消息可能已删除或不是可更新的卡片）"
    }
}

// =====================================================================================
// L1 三件（当前会话内默认开）：send_image /
// recall_own_message / reply_in_thread——定义即恒注册（无配置面），出站面 = ReplySink
// 三新成员（image / recallMessage / replyInThread，默认实现 = 能力缺席）。
// 🔴 硬边界同 P4 区：当前会话作用域，无 chat_id 参数——LLM 不可指定会话。
// =====================================================================================

/**
 * 发送图片产物（L1）：图表/截图等图片文件作为飞书图片消息发给用户。
 * 围栏判据与 [SendFileTool] **完全一致**（同一套四参 context/sandbox/artifactsEnabled/
 * homeDataRoot + 共用 [outboundDeliveryFence]——同一前置闸序、同一拒绝文案面）；
 * 唯一差异是出站成员：`reply.image`（uploadImage→Outbound.image 两步，网关侧另有约 10MB 平台上限）。
 */
internal class SendImageTool(
    private val request: AgentRequest,
    private val context: com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext? = null,
    private val sandbox: com.jiaoay.lark.bridge.exec.WorkspacePolicy? = null,
    /** `agent.artifacts.enabled`（产物回传总开关）：false ⇒ 本工具一律拒绝（不放旁路）。 */
    private val artifactsEnabled: Boolean = true,
    /** 桥接数据根（敏感文件判据 1 的输入；null = 未知，跳过该判据）。 */
    private val homeDataRoot: java.io.File? = null,
) : SimpleTool<SendImageTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "send_image",
    // 描述与实际能力面一致的纪律（send_file 同款）：总开关关闭时 execute 恒拒，描述同步改写。
    description = if (artifactsEnabled) {
        "Send a local image file (chart, screenshot, diagram) to the user as a Lark image message. " +
            "The file must be one the user sent (referenced), a product of your tools, or a file inside " +
            "the configured workspace. Use it for visual deliverables; use send_file for non-image artifacts."
    } else {
        "Currently UNAVAILABLE: the bridge's artifact-return switch is turned off by the user, so no image " +
            "can be sent (this tool always fails). Deliver results as text (reply_direct) or a card (send_card) instead."
    },
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute path of the local image file to send (png/jpg and other common formats)")
        val file_path: String,
        @property:LLMDescription("Optional display name shown in Lark; empty = use the file name")
        val display_name: String = "",
    )

    override suspend fun execute(args: Args): String {
        val reply = BridgeToolRegistry.replySinkOf(request) ?: return ReplyDirectTool.DENIED_HINT
        val file = java.io.File(args.file_path)
        // 共用交付围栏（与 send_file 同一套——总开关/围栏/存在性/敏感表，判据序与文案逐字一致）
        outboundDeliveryFence(args.file_path, context, sandbox, artifactsEnabled, homeDataRoot)?.let { return it }
        val result = reply.image(Path.of(file.absolutePath))
        // 成功文案同 reply_direct——附 messageId 供 recall。
        return if (result.ok) {
            "图片已发送：${args.display_name.ifBlank { file.name }}（message_id=" + (result.messageId ?: "?") + "）"
        } else {
            "发送失败：${result.error}"
        }
    }
}

internal class RecallOwnMessageTool(
    private val request: AgentRequest,
) : SimpleTool<RecallOwnMessageTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "recall_own_message",
    description = "Recall (unsend) a message YOU previously sent in the CURRENT chat. Only messages " +
        "sent by the bot itself in this chat can be recalled — never user messages or others' messages. " +
        "The message id comes from your own send results in this session.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The message id of YOUR OWN message to recall (from your send results in this chat)")
        val message_id: String,
    )

    override suspend fun execute(args: Args): String {
        val payload = BridgeToolRegistry.payloadOf(request)
            ?: return "撤回能力不可用（无会话上下文）"
        if (args.message_id.isBlank()) return "message_id 不能为空（用你自己发送结果里的消息 id）"
        // 🔴 账本双重守护：payload.replyLedger 非空 且 lookup(message_id, chatId)
        //    命中才放行——账本是「机器人自己在本会话发过」的唯一凭据。
        val ledger = payload.replyLedger
        if (ledger == null) {
            return "撤回能力不可用（本地回复账本未启用）"
        }
        val own = ledger.lookup(args.message_id, payload.chatId)
            ?: return "该消息不在本会话机器人回复账本中，无法撤回（只能撤回机器人自己在当前会话发的消息）"
        val ok = payload.reply.recallMessage(args.message_id)
        return if (ok) "已撤回消息" else "撤回失败（消息可能已被删除或平台拒绝）"
    }
}

/**
 * 群聊话题内回复（L1）：把回复发进话题保持讨论线索。目标 = 用户本条消息（载体锚定，
 * 无 chat_id 参数——LLM 不可指定会话）。P2P/UNKNOWN 回落 `reply.text`（单聊无话题语义）
 * 并如实返回「已按普通回复发送」。
 */
internal class ReplyInThreadTool(
    private val request: AgentRequest,
) : SimpleTool<ReplyInThreadTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "reply_in_thread",
    description = "Reply inside the message thread (topic) in a GROUP chat to keep the discussion " +
        "organized. In P2P chats this falls back to a normal reply automatically. Do not use it for " +
        "the primary final answer — prefer reply_direct unless the user is discussing in a thread.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The reply text to send inside the thread")
        val text: String,
    )

    override suspend fun execute(args: Args): String {
        val payload = BridgeToolRegistry.payloadOf(request)
            ?: return "话题回复能力不可用（无会话上下文）"
        return when (payload.chatType) {
            // 群聊：走话题出站（replyTo 锚定由 send 通路统一注入）
            com.jiaoay.lark.gateway.ChatType.GROUP -> {
                val result = payload.reply.replyInThread(args.text)
                if (result.ok) "已发送到话题" else "话题回复失败：${result.error}"
            }
            // P2P/UNKNOWN：回落普通回复并如实告知（不静默改道——LLM 需要知道形态）
            else -> {
                val result = payload.reply.text(args.text)
                if (result.ok) "当前会话非群聊，已按普通回复发送" else "发送失败：${result.error}"
            }
        }
    }
}

// =====================================================================================
// 会话内提醒（remind_user / cancel_reminder，当前会话作用域）：N 分钟后回到本会话把
// 用户指定文本原文发出；受理回执带会话内编号，计划有变可按编号撤销（cancel 按点火门闩
// 分两态回执：未点火 = 真取消；点火在途 = 如实报「已在送达途中」，不冒充取消成功）。
// 提醒≠定时任务（硬边界）：无周期执行、到期不自动起 agent——到期动作恒为纯文本冒泡
//    （[com.jiaoay.lark.bridge.pipeline.ReplySink.text]，频控走既有 RateGovernor，零新出站旁路）；
//    「每天 X 点跑检查」cron 形态不做（桥接生命周期 = 桌面 App 存活期，无人值守起任务属
//    成本/安全双炸弹，与 FileHandler 删除「收到即分析」旧形态同口径）。
// 生命周期如实：job 挂宿主注入的 [ReminderScheduler]（App 形态绑定网关 scope）——
//    应用退出/装配周期结束 ⇒ 提醒自然丢失，回执明示，不冒充可靠闹钟。
// =====================================================================================

/**
 * 会话内提醒的延时 job 面（宿主注入缝，与 KoogAgentRunner 各 `*Provider` 同族）。
 * App 形态由宿主以网关 scope 构造注入；CLI 形态/存量测试不注入（null）⇒ remind_user 不注册。
 * 可见性说明：public 只因 [KoogAgentRunner] 构造参数需要暴露该类型（实现 [BridgeReminderScheduler] 恒 internal）。
 */
interface ReminderScheduler {
    /**
     * 受理一条延时提醒 job（到期执行 [fire]，[fire] 抛 CancellationException 原样放行）。
     *
     * @return [ReminderScheduleResult.Accepted] = 已受理（reminderId 供撤销指代）；
     *         [ReminderScheduleResult.Denied] = 人话拒收理由（当前会话在途提醒已满），调用方直接回给 LLM
     */
    fun schedule(chatId: String, delayMillis: Long, fire: suspend () -> Unit): ReminderScheduleResult

    /**
     * 撤销一条在途提醒（实现以点火门闩分两态：未点火 = Job.cancel 真取消——取消传播不吞
     * CancellationException；已点火在途 = 尽力 Job.cancel，但已派发的出站请求不可撤销——
     * 回执必须如实，不得冒充取消成功）。
     * 会话作用域硬边界：只查 [chatId] 名下的 job 表——别的会话的编号恒查不到（按 [ReminderCancelResult.NotFound] 拒绝）。
     *
     * @return [ReminderCancelResult] 三态（语义钉死在该密封态 KDoc）
     */
    fun cancel(chatId: String, reminderId: Long): ReminderCancelResult
}

/**
 * [ReminderScheduler.cancel] 的撤销结果三态（回执如实的单源语义面）：
 * - [Cancelled]：点火前赢得门闩——fire 零执行，「不会再响起」承诺成立；
 * - [FiredInFlight]：已点火、出站发送在途——尽力取消但已派发请求不可撤销，文本可能仍会送达；
 * - [NotFound]：本会话不存在该编号的提醒（已到期出表/已被撤销/编号不属于本会话/已完成撞表窗口）。
 * 可见性说明：public 只因 [ReminderScheduler] 接口面需要暴露（实现与消费侧均在本模块）。
 */
sealed interface ReminderCancelResult {
    /** 未点火即撤销：fire 闭包零执行——回执「不会再响起了」承诺由此成立。 */
    data object Cancelled : ReminderCancelResult

    /** 点火在途：出站请求已派发不可撤销，尽力取消后文本仍可能送达——回执不得冒充取消成功。 */
    data object FiredInFlight : ReminderCancelResult

    /** 没找到：编号不存在、已到期/已被撤销出表，或不属于本会话。 */
    data object NotFound : ReminderCancelResult
}

/**
 * [ReminderScheduler.schedule] 的受理结果两态：受理成功带会话作用域编号 | 人话拒收理由。
 * 可见性说明：public 只因 [ReminderScheduler] 接口面需要暴露（实现与消费侧均在本模块）。
 */
sealed interface ReminderScheduleResult {
    /** 已受理：[reminderId] 是 per-chat 自增编号——remind_user 回执转给 LLM，cancel_reminder 按它撤销。 */
    data class Accepted(val reminderId: Long) : ReminderScheduleResult

    /** 人话拒收理由（当前会话在途提醒已满），调用方原样回给 LLM。 */
    data class Denied(val reason: String) : ReminderScheduleResult
}

/**
 * [ReminderScheduler] 的协程实现：job 挂构造注入的 [scope]——宿主传网关 scope，
 * scope 取消（装配周期结束/应用退出）即全部提醒取消；单会话在途上限
 * [MAX_INFLIGHT_PER_CHAT] 防连环设定刷屏（撤销即释放占位，上限文案的「或被取消」承诺由此兑现）。
 * 在途 job 按 per-chat 自增编号入表（[cancel] 撤销面）；到期/取消路径都出表。
 * 到期发送失败只记日志不重试：提醒是尽力而为语义（回执已向用户明示会丢失）。
 */
internal class BridgeReminderScheduler(
    private val scope: CoroutineScope,
) : ReminderScheduler {

    private val inflightByChat = ConcurrentHashMap<String, AtomicInteger>()

    private val nextIdByChat = ConcurrentHashMap<String, AtomicLong>()

    /** 在途提醒条目：job + 占位释放闭包 + 点火状态门闩（两路 CAS 竞争各恰好一次/单向迁移的载体）。 */
    private class InflightReminder(
        val job: Job,
        val releaseInflight: () -> Unit,
        val state: AtomicInteger,
    )

    /** 在途 job 表（撤销面）：chatId → (reminderId → 条目)；编号 per-chat 自增不复用。 */
    private val jobsByChat = ConcurrentHashMap<String, ConcurrentHashMap<Long, InflightReminder>>()

    override fun schedule(chatId: String, delayMillis: Long, fire: suspend () -> Unit): ReminderScheduleResult {
        val inflight = inflightByChat.computeIfAbsent(chatId) { AtomicInteger() }
        if (inflight.incrementAndGet() > MAX_INFLIGHT_PER_CHAT) {
            inflight.decrementAndGet()
            return ReminderScheduleResult.Denied(
                "本会话待响起的提醒已有 $MAX_INFLIGHT_PER_CHAT 个（上限）——请等其中一条到期或被取消后再设新的～",
            )
        }
        val reminderId = nextIdByChat.computeIfAbsent(chatId) { AtomicLong() }.incrementAndGet()
        val jobs = jobsByChat.computeIfAbsent(chatId) { ConcurrentHashMap() }
        // 占位释放恰好一次（CAS 门闩）：job 在启动队列里被 cancel 时其 finally 不可达，
        // cancel 路径必须能独立释放——两路竞争由 compareAndSet 收敛为一次。
        val released = AtomicBoolean(false)
        fun releaseInflight() {
            if (released.compareAndSet(false, true)) inflight.decrementAndGet()
        }
        // 点火门闩（与占位释放 CAS 同款结构——launch 前声明、闭包捕获、条目承载）：
        // PENDING→FIRING（点火方）与 PENDING→CANCELLED（撤销方）经 CAS 单向迁移恰好一次——
        // 撤销先赢得门闩时 fire 零执行（「不会再响起」的回执承诺由此成立），
        // 点火先赢得时已派发的出站请求不可撤销（回执如实报 [ReminderCancelResult.FiredInFlight]）。
        val state = AtomicInteger(STATE_PENDING)
        val job = scope.launch {
            try {
                delay(delayMillis)
                if (!state.compareAndSet(STATE_PENDING, STATE_FIRING)) {
                    return@launch // 撤销已赢得门闩：fire 零执行（job.cancel 的 CE 传播之外的第二重保证）
                }
                try {
                    fire()
                } catch (e: CancellationException) {
                    throw e // CE 原样放行：取消传播不吞（finally 释放占位后协程按取消终结）
                } catch (t: Throwable) {
                    JLog.w(TAG) { "提醒发送失败（chatId=$chatId）：${t.message}" }
                }
            } finally {
                releaseInflight() // 到期/取消/异常路径都释放（finally 恒达——协程已启动的前提下）
                jobs.remove(reminderId) // 到期出表；cancel 抢先出表时此处为无操作
            }
        }
        // launch 返回即入表；先完成再入表的窗口由 cancel 的 isActive 判定兜底（完成态按「没找到」走）。
        jobs[reminderId] = InflightReminder(job, ::releaseInflight, state)
        return ReminderScheduleResult.Accepted(reminderId)
    }

    override fun cancel(chatId: String, reminderId: Long): ReminderCancelResult {
        val entry = jobsByChat[chatId]?.remove(reminderId) ?: return ReminderCancelResult.NotFound
        // 同步释放占位（撤销即释放——上限拒收文案的「或被取消」承诺由此兑现）：
        // job 尚在启动队列被取消时其 finally 不可达，这里必须独立释放；
        // 已启动路径与 finally 的释放经 CAS 门闩收敛为恰好一次。
        entry.releaseInflight()
        // 完成态兜底（既有语义保持）：fire 已完整跑完的撞表窗口按「没找到」走。
        if (!entry.job.isActive) return ReminderCancelResult.NotFound
        return if (entry.state.compareAndSet(STATE_PENDING, STATE_CANCELLED)) {
            // 未点火：真取消——fire 零执行，出站面零触达。
            entry.job.cancel()
            ReminderCancelResult.Cancelled
        } else {
            // 点火在途：尽力取消挂起点，但已派发的出站请求不可撤销——回执不得冒充取消成功。
            entry.job.cancel()
            ReminderCancelResult.FiredInFlight
        }
    }

    companion object {
        /** 点火门闩三态值（单向迁移：PENDING→FIRING 或 PENDING→CANCELLED，绝不回流）。 */
        private const val STATE_PENDING = 0
        private const val STATE_FIRING = 1
        private const val STATE_CANCELLED = 2

        /** 单会话在途提醒上限（未响起的都算在途；防 LLM 连环设定刷屏）。 */
        internal const val MAX_INFLIGHT_PER_CHAT: Int = 5

        private const val TAG = "BridgeReminderScheduler"
    }
}

/**
 * 会话内一次性提醒（当前会话作用域）：[Args.delay_minutes] 分钟后把 [Args.text] 原文透传给用户。
 * 目标会话恒取载体 chatId（无 chat_id 参数——LLM 不可指定会话，会话作用域工具族硬边界同款）。
 * 提醒 job 生命周期 = 宿主注入 [scheduler] 的 scope（App 形态 = 网关 scope）：
 * 桌面应用退出/桥接停止即丢失，回执明示生命周期边界，不冒充可靠闹钟。
 */
internal class RemindUserTool(
    private val request: AgentRequest,
    private val scheduler: ReminderScheduler,
) : SimpleTool<RemindUserTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "remind_user",
    description = "Schedule a ONE-SHOT reminder in the CURRENT chat: after delay_minutes the given text " +
        "is sent verbatim. Use when the user is waiting on a long task and asks to be reminded later, " +
        "or agrees to re-check results in N minutes. Do NOT use it for immediate questions or " +
        "recurring/cron-style jobs. Reminders are lost if the desktop app exits or the bridge stops — " +
        "tell the user this when setting one.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Minutes from now until the reminder fires (1-720)")
        val delay_minutes: Int,
        @property:LLMDescription("The reminder text sent to the user verbatim when it fires, in the user's language")
        val text: String,
    )

    override suspend fun execute(args: Args): String {
        val payload = BridgeToolRegistry.payloadOf(request)
            ?: return "提醒能力不可用（无会话上下文）"
        val chatId = payload.chatId?.takeIf { it.isNotBlank() }
            ?: return "提醒能力不可用（无当前会话）"
        if (args.text.isBlank()) return "提醒内容不能为空（写到期时要对用户说的那句话）"
        if (args.delay_minutes < MIN_DELAY_MINUTES) {
            return "提醒间隔至少 $MIN_DELAY_MINUTES 分钟——更短的等待不如直接等这轮答复～"
        }
        if (args.delay_minutes > MAX_DELAY_MINUTES) {
            return "提醒间隔最长 $MAX_DELAY_MINUTES 分钟（12 小时）——更远的约定请让用户用日历" +
                "（桌面应用退出提醒会丢失，不冒充可靠闹钟）"
        }
        // 到期文本 = 用户指定内容原文透传（fire 闭包捕获本消息 ReplySink，出站走既有频控通道）
        return when (val result = scheduler.schedule(chatId, args.delay_minutes * MILLIS_PER_MINUTE) {
            payload.reply.text(args.text)
        }) {
            is ReminderScheduleResult.Denied -> result.reason
            is ReminderScheduleResult.Accepted -> "已设定（提醒 #${result.reminderId}）：${args.delay_minutes} 分钟后我会回到本会话提醒用户～" +
                "计划有变时可用 cancel_reminder 撤销（reminder_id=${result.reminderId}）。" +
                "提醒依赖桌面端应用保持运行——应用退出或桥接停止提醒会丢失（不是系统闹钟），设定时请向用户转达这一点。"
        }
    }

    companion object {
        internal const val MIN_DELAY_MINUTES: Int = 1

        /** 档位上限（12 小时）：更远的约定生命周期靠不住，拒收并引导用户用日历。 */
        internal const val MAX_DELAY_MINUTES: Int = 720

        private const val MILLIS_PER_MINUTE: Long = 60_000L
    }
}

/**
 * 撤销一条还没响起的会话内提醒（当前会话作用域）：按 remind_user 回执里的编号取消。
 * 回执两态如实：未点火 = 真取消（「不会再响起了」）；点火在途 = 如实报「已在送达途中、
 * 无法撤销」（不冒充取消成功——回执如实面）。已到期/已撤销/跨会话编号按「没找到」拒绝。
 * 会话作用域硬边界：目标会话恒取载体 chatId（无 chat_id 参数，会话作用域工具族同款）——
 * 只能撤本载体会话设的提醒，别的会话的相同编号恒查不到（人话按「没找到」拒绝）。
 * 撤销不动「stop→start 间隙丢失」既有如实句语义（撤销的是在途 job，不是生命周期承诺）。
 */
internal class CancelReminderTool(
    private val request: AgentRequest,
    private val scheduler: ReminderScheduler,
) : SimpleTool<CancelReminderTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "cancel_reminder",
    description = "Cancel a pending one-shot reminder in the CURRENT chat by its reminder number " +
        "(the number in the remind_user receipt, e.g. 3 for 提醒 #3). Use when plans changed, the task " +
        "finished early, or the user asks to cancel a reminder. A reminder already delivering its " +
        "message cannot be truly cancelled — the receipt will report that honestly and the message may " +
        "still arrive. Already-delivered or previously-cancelled reminders report not-found; " +
        "reminders from other chats cannot be cancelled.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The reminder number from the remind_user receipt (the N in 提醒 #N)")
        val reminder_id: Long,
    )

    override suspend fun execute(args: Args): String {
        val payload = BridgeToolRegistry.payloadOf(request)
            ?: return "撤销能力不可用（无会话上下文）"
        val chatId = payload.chatId?.takeIf { it.isNotBlank() }
            ?: return "撤销能力不可用（无当前会话）"
        return when (scheduler.cancel(chatId, args.reminder_id)) {
            ReminderCancelResult.Cancelled ->
                "已取消提醒 #${args.reminder_id}（不会再响起了）"
            ReminderCancelResult.FiredInFlight ->
                "提醒 #${args.reminder_id} 已在送达途中，无法撤销（消息可能仍会送达用户，请如实转告）"
            ReminderCancelResult.NotFound ->
                "没找到这个编号的提醒——可能已到期或已取消（提醒编号只在本会话内有效，撤销不了其他会话的提醒）"
        }
    }
}

// =====================================================================================
// L2 五件（跨会话能力，接缝在位即注册）：
// fetch_chat_history / get_chat_info / create_group_chat / lookup_user / read_wiki_document。
// 注册条件（唯一一道）：payload.nativeSurface 非空——判定在 [BridgeToolRegistry.build]，
//    本区工具类只实现能力面。`agent.nativeTools` 双闸配置段已退役——授权闸收敛为
//    飞书后台 scope 单一闸。
// 🔴 硬边界（反射守护钉死形态）：Args **无 chat_id 参数**——目标会话恒取
//    DispatchPayload.nativeSurface 的钉死 chatId，LLM 不可指定会话。
// fail-closed：nativeSurface 缺席 ⇒ 能力缺失文案；scope 未开通 ⇒ scope 引导文案
//    （NativeCapability.scopeHintOf 单源）——描述与降级文案必须与实际能力面一致（红线 5）。
// =====================================================================================

/** L2 工具公共尾注（降级文案的 scope 引导由各工具按 grant 拼装）。 */
private fun l2Unavailable(grantHint: String?): String =
    if (grantHint == null) "该能力不可用" else "该能力不可用（可能未在飞书后台开通 $grantHint scope 并发版）"

internal class FetchChatHistoryTool(
    private val request: AgentRequest,
) : SimpleTool<FetchChatHistoryTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "fetch_chat_history",
    description = "Fetch recent message history of the CURRENT chat (the conversation you are talking in). " +
        "Use for questions about what was said earlier, context recap, or finding an earlier message. " +
        "Use page_token from the previous result to fetch more.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("How many messages to fetch (1-50, default 20)")
        val limit: Int = 20,
        @property:LLMDescription("Page token from the previous fetch result; empty = first page")
        val page_token: String = "",
    )

    override suspend fun execute(args: Args): String {
        val surface = BridgeToolRegistry.payloadOf(request)?.nativeSurface
            ?: return "历史拉取不可用（原生会话能力未接线）"
        val page = surface.fetchChatHistory(args.limit.coerceIn(1, 50), args.page_token.takeIf { it.isNotBlank() })
            ?: return l2Unavailable(com.jiaoay.lark.bridge.config.NativeCapability.scopeHintOf(name))
        if (page.items.isEmpty()) return "没有更多历史消息"
        val body = page.items.joinToString("\n") { item ->
            val time = java.time.Instant.ofEpochMilli(item.createTimeMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            val sender = item.senderOpenId.ifBlank { "?" }
            "[$time] $sender：${item.textBrief}"
        }
        // 续页提示：has_more 时附 page_token（LLM 下一轮原样回传）
        return if (page.hasMore && !page.nextPageToken.isNullOrBlank()) {
            "$body\n（还有更早的消息，用 page_token 继续拉取：${page.nextPageToken}）"
        } else {
            body
        }
    }
}

/** 查询当前会话信息与成员名单（L2）。返回已格式化文本（surface 侧组装，工具保持薄壳）。 */
internal class GetChatInfoTool(
    private val request: AgentRequest,
) : SimpleTool<GetChatInfoTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "get_chat_info",
    description = "Get information about the CURRENT chat (name, description, member count and the " +
        "first 50 members with their open ids). Use when you need to know who is in this conversation " +
        "or what this chat is called.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("No arguments needed")
        val unused: String = "",
    )

    override suspend fun execute(args: Args): String {
        val surface = BridgeToolRegistry.payloadOf(request)?.nativeSurface
            ?: return "会话信息查询不可用（原生会话能力未接线）"
        val text = surface.chatInfoWithMembers()
        if (text.isEmpty()) return l2Unavailable(com.jiaoay.lark.bridge.config.NativeCapability.scopeHintOf(name))
        return text
    }
}

internal class CreateGroupChatTool(
    private val request: AgentRequest,
    private val confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate?,
    /**
     * 本消息发送者是否信任用户（`access.trustedUsers`）。跨会话写操作**只对信任用户开放**：
     * 非信任用户连确认卡都不发——防替答机制只校验发起者本人，不设信任闸就等于让任意陌生人
     * 自己批准自己的建群操作。
     */
    private val isTrustedSender: () -> Boolean = { false },
) : SimpleTool<CreateGroupChatTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "create_group_chat",
    description = "Create a new group chat with the given members. REQUIRES explicit user confirmation " +
        "before execution (the user will be asked via a confirmation card). Use ONLY when the user " +
        "explicitly asked to create a group. The bot becomes the owner but CANNOT send messages into " +
        "the new group — tell the user to @ the bot there to continue.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Name of the new group chat")
        val name: String,
        @property:LLMDescription("Open ids of members to add (list; the bot itself is added automatically)")
        val member_open_ids: List<String>,
    )

    override suspend fun execute(args: Args): String {
        val payload = BridgeToolRegistry.payloadOf(request) ?: return "建群能力不可用（无会话上下文）"
        val surface = payload.nativeSurface
            ?: return "建群能力不可用（原生会话能力未接线）"
        if (args.name.isBlank()) return "群名不能为空"
        // 跨会话写操作：非信任用户直接拒绝（不发卡——不给陌生人自批入口）。
        if (!isTrustedSender()) {
            return "建群是跨会话写操作，仅限信任用户执行（把 openId 加入「信任用户」名单后可用）。"
        }
        // 🔴 强制确认门（写操作）：链不可用（confirmGate=null）⇒ fail-closed 引导文案，不建群。
        val gate = confirmGate
        if (gate == null) {
            return "建群需要用户确认，但确认链路不可用（未开启运行时确认），已取消建群。"
        }
        val verdict = gate.requireConfirmation(
            toolName = name,
            inputSummary = "建群「${args.name}」，成员 ${args.member_open_ids.size} 人",
            // 建群是跨会话写操作自报 HIGH（classify 规则是 MCP 语义，勿走）
            riskLevel = com.jiaoay.lark.bridge.confirm.RiskClassifier.RiskLevel.HIGH.name,
        )
        if (verdict != com.jiaoay.lark.bridge.confirm.ToolConfirmGate.Verdict.APPROVED) {
            return "用户未确认，已取消建群。"
        }
        val creation = surface.createGroupChat(args.name, args.member_open_ids)
            ?: return l2Unavailable(com.jiaoay.lark.bridge.config.NativeCapability.scopeHintOf(name))
        // L1 出站边界引导句（写死）：新群内机器人不能主动开口。
        return "新群已创建（chatId=${creation.chatId}）。机器人不能主动向新群发消息——请让用户在新群里 @ 机器人继续对话。"
    }
}

/** 查用户（L2）。PII 最小化：只回名字（头像 URL 对文本回复无意义，不回传）。 */
internal class LookupUserTool(
    private val request: AgentRequest,
) : SimpleTool<LookupUserTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "lookup_user",
    description = "Look up a Feishu user's display name by their open id (minimal info: name only). " +
        "Use to turn an open id from chat history or member lists into a human-readable name.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The user's open id (starts with ou_)")
        val open_id: String,
    )

    override suspend fun execute(args: Args): String {
        val surface = BridgeToolRegistry.payloadOf(request)?.nativeSurface
            ?: return "用户查询不可用（原生会话能力未接线）"
        if (args.open_id.isBlank()) return "open_id 不能为空"
        val profile = surface.lookupUser(args.open_id)
            ?: return "查不到该用户（可能未开通 contact:contact.base:readonly scope、" +
                "通讯录授权范围未覆盖该用户，或 open_id 无效）"
        return if (profile.name.isNotBlank()) profile.name else "查不到该用户的名字（open_id 可能无效）"
    }
}

internal class ReadWikiDocumentTool(
    private val request: AgentRequest,
) : SimpleTool<ReadWikiDocumentTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "read_wiki_document",
    description = "Read the plain-text content of a Feishu wiki page or document. Accepts a full URL " +
        "(.../wiki/... or .../docx/...) or a wiki node token. Long documents are truncated at ~30k chars. " +
        "Use when the user shares a wiki/docx link and asks about its content.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The wiki/docx URL or a wiki node token")
        val url: String,
    )

    override suspend fun execute(args: Args): String {
        val surface = BridgeToolRegistry.payloadOf(request)?.nativeSurface
            ?: return "文档读取不可用（原生会话能力未接线）"
        if (args.url.isBlank()) return "url 不能为空"
        val text = surface.readWikiDocument(args.url)
        if (text.isEmpty()) {
            // wiki 与 docx 两个 scope 都可能缺——一起引导（工具面不区分失败发生在哪一步）
            return "读不到该文档（可能未在飞书后台开通 wiki:wiki:readonly 与 docx:document:readonly scope，" +
                "或文档不在应用可见范围）"
        }
        return text
    }
}

internal class SearchKnowledgeTool : SimpleTool<SearchKnowledgeTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "search_knowledge",
    description = "Search the official user manual of this tool (Toolbelt) for how to use its features. " +
        "You MUST call this before answering questions like \"how does this tool work\", \"what can it do\" or " +
        "\"where is some button\". Answer based on the retrieved manual content; never invent features.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("The question or keywords about this tool's usage, in the user's language")
        val query: String,
    )

    override suspend fun execute(args: Args): String {
        val result = KnowledgeBase.search(args.query)
        return when {
            // 检索无命中 / 索引未就绪（端点未配）——如实告知，让模型转述而不是编造
            result == null -> "手册中未检索到相关内容（或知识库尚未就绪）。请如实告知用户，不要编造工具用法。"
            else -> result
        }
    }
}

/**
 * 发送文件（过程产物交付——CLI agent 无法支持的场景正是本工具的价值）。
 *
 * ## 交付判据（产物回传语义）
 * 前置闸（任一不过即拒，**先于**路径判据）：
 * 1. `artifactsEnabled == false` ⇒ 拒（与 `agent.artifacts.enabled` 用户开关对齐：关掉「产物回传」
 *    即不许 agent 往外发文件，本工具不得成为旁路）；
 * 2. 命中 [com.jiaoay.lark.bridge.exec.SensitiveFilePolicy] ⇒ 拒（桥接数据根 / 凭据族 /
 *    `*.meta.json` / 私钥证书扩展名——防「引用文件注入 → 诱导外发敏感文件」）。
 *
 * 路径判据：放行 ⟺ 命中精确允许集（本消息引用链 + 工具产物，canonicalFile 集合）
 *                ∨ 命中工作区沙箱（`root` ∪ `extraRoots`，canonical 前缀）
 * 纯聊天档（`context == null`）同样走「允许集 ∨ 沙箱」放行：纯聊天档 allowedFiles 恒空
 *    ⇒ 实际判据=沙箱（未配工作区 ⇒ 恒拒，fail-safe）——agent-first 后纯聊天是主路径，零围栏即暴露面。
 *
 * 🔴 上述沙箱判定对**两档均适用**；`sandbox == null`（未配工作区）时：
 *    有引用文件档回落精确允许集、纯聊天档恒拒——不再有不设上界的路径。
 *
 * 🔴 工作区放宽是有意的安全边界设计：CLI 子进程产出落在 cwd（∈ 工作区），但
 *    `AgentRunResult` 无结构化产物字段，CLI 无法把自己的产物登记进允许集；
 *    放宽后 `send_file` 在**有引用文件的消息**里
 *    的可发送面 = `[产物]` 标记链的既有面（同一 `WorkspacePolicy`、同一组根），
 *    不越出用户自配的工作区；未配工作区 ⇒ `sandbox == null` ⇒ 回落精确允许集。
 *
 * ⚠️ 与 `[产物]` 通道仍有宽度差异（已知且有意保留，共 3 项）：`maxCount`／`maxSizeBytes`／
 *    `allowedExtensions`——本工具只有「存在 + 30MB 平台上限」（`enabled` 已对齐，见前置闸 1）。
 *    **读**类文件处理工具（probe_file / preview_text / … 共十五件）**刻意保持严格允许集**，
 *    不随本工具放宽。
 */
internal class SendFileTool(
    private val request: AgentRequest,
    private val context: com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext? = null,
    private val sandbox: com.jiaoay.lark.bridge.exec.WorkspacePolicy? = null,
    /** `agent.artifacts.enabled`（产物回传总开关）：false ⇒ 本工具一律拒绝（不放旁路）。 */
    private val artifactsEnabled: Boolean = true,
    /** 桥接数据根（敏感文件判据 1 的输入；null = 未知，跳过该判据）。 */
    private val homeDataRoot: java.io.File? = null,
) : SimpleTool<SendFileTool.Args>(
    argsType = ai.koog.serialization.typeToken<Args>(),
    name = "send_file",
    // 描述必须与 execute 的**实际能力面一致**。总开关关闭时 execute 一律拒，
    //    描述若仍宣称「可发工作区内文件」，LLM 会反复试错 → 耗尽 maxIterations
    //    （与 systemPromptFor 的 sandboxAvailable 条件化同一纪律）。
    description = if (artifactsEnabled) {
        "Send a local file to the user as a Lark file message. Use for delivering process artifacts " +
            "(generated reports, extracted data, intermediate files). The file must be one the user sent " +
            "(referenced), a product of your tools, or a file inside the configured workspace " +
            "(including files produced by a delegated CLI task)."
    } else {
        "Currently UNAVAILABLE: the bridge's artifact-return switch is turned off by the user, so no file " +
        "can be sent (this tool always fails). Do not attempt to send files; deliver results as text " +
        "(reply_direct) or a card (send_card) instead, and tell the user to enable the switch in the " +
        "Lark Bridge settings if they need files."
    },
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute path of the local file to send")
        val file_path: String,
        @property:LLMDescription("Optional display name shown in Lark; empty = use the file name")
        val display_name: String = "",
    )

    /**
     * 工作区沙箱命中判定已移入文件级 [outboundDeliveryFence]——
     * 防双写漂移；保留此注释防有人往回加私有实现。
     */

    override suspend fun execute(args: Args): String {
        val reply = BridgeToolRegistry.replySinkOf(request) ?: return ReplyDirectTool.DENIED_HINT
        val file = java.io.File(args.file_path)
        // 共用交付围栏（与 send_image 同款判据）：四段判据原序逐字保留——
        //    总开关 → 围栏前移 → 存在性 → 敏感表；拒绝文案逐字不变（KoogAgentRunnerTest 的围栏断言背书）。
        outboundDeliveryFence(args.file_path, context, sandbox, artifactsEnabled, homeDataRoot)?.let { return it }
        // 飞书平台单文件上限 30MB（网关层亦有校验，此处提前判定省一次上传）。
        if (file.length() > MAX_FILE_BYTES) return "文件超过 30MB 平台上限：${file.length() / 1024 / 1024}MB"
        val result = reply.file(Path.of(file.absolutePath), args.display_name.ifBlank { file.name })
        return if (result.ok) "文件已发送：${file.name}" else "发送失败：${result.error}"
    }

    companion object {
        internal val MAX_FILE_BYTES: Long = 30L * 1024 * 1024
    }
}
