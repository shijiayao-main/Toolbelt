// user.ask_user 的答案回传 handler：用户「引用提问卡回复」即回答——零格式学习成本。
// LIGHT 档不占许可（回答是秒级配对面，占 HEAVY 闸会把长任务与它的回答卡互锁死）。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.confirm.AskUserBus
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.HandlerOutcome
import com.jiaoay.lark.bridge.pipeline.HandlerWeight
import com.jiaoay.lark.bridge.pipeline.MessageHandler
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.TaskKind
import com.jiaoay.lark.gateway.InboundKind

/**
 * 提问答案回传入口：引用在途提问卡回复 ⇒ 经 [AskUserBus.decide] 完成工具挂起。
 *
 * ## 与 InteractionHandler（确认卡裁决）的分界
 * 确认卡是二元裁决（approve/deny 按钮或「确认 <id>」文字）；提问卡是**开放式回答**——
 * 两者都是 CARD/引用形态但值域不同，故 handler 分立（合并会让 match 谓词互相遮蔽）。
 *
 * ## 配对与防替答
 * - 配对键 = 提问卡 messageId（用户引用哪张卡回答哪张）；
 * - 只有**原提问者**的回复被采纳（senderOpenId 快照比对）——群聊里他人引用提问卡
 *   回复不误伤在途提问（bus.decide 返回 false，本 handler 静默跳过交后续路由）；
 * - 按钮形态（单选 Choice 字段的可选按钮）：cardAction value 形如 `qa-<requestId>:<option>`
 *   （见 feature 侧提问卡构建）——解析后作为单字段答案完成。
 *
 * ## 不进许可闸的理由
 * 答案配对是纯内存 O(1) 操作（ConcurrentHashMap + deferred.complete），
 * 与 InteractionHandler 同属 LIGHT——被拒用户不可能借此把并发额度吃光。
 */
class AskAnswerHandler(
    private val askBus: AskUserBus,
    @Suppress("UNUSED_PARAMETER") private val deps: HandlerDeps,
) : MessageHandler {

    override val kind: RouteKind = RouteKind.COMMAND
    override val weight: HandlerWeight = HandlerWeight.LIGHT
    override val timeoutSeconds: Long? = null

    override fun match(ctx: RequestContext): Boolean {
        // 卡片按钮回传：value 形如 qa-<seq>:<option> 才归本 handler（其余卡片动作归其他 handler）
        if (ctx.isCardAction) {
            val value = ctx.message.cardAction?.actionValue.orEmpty()
            return value.startsWith(ACTION_VALUE_PREFIX) && value.contains(':')
        }
        // 文本主通道：引用了在途提问卡
        if (ctx.inboundKind != InboundKind.TEXT) return false
        val replyTo = ctx.message.replyToMessageId?.takeIf { it.isNotBlank() } ?: return false
        return askBus.find(replyTo) != null
    }

    override suspend fun handle(ctx: RequestContext): HandlerOutcome {
        val replyTo = ctx.message.replyToMessageId?.takeIf { it.isNotBlank() }
        return if (ctx.isCardAction) {
            handleCardAction(ctx)
        } else if (replyTo != null && askBus.find(replyTo) != null) {
            handleTextAnswer(ctx, replyTo)
        } else {
            // match 与 handle 之间竞态（pending 恰好被注销）——静默跳过交后续路由
            HandlerOutcome.skipped(TaskKind.COMMAND, "提问已不在途", silent = true)
        }
    }

    /** 按钮回传：value = qa-<requestId>:<option>——单字段答案完成 */
    private suspend fun handleCardAction(ctx: RequestContext): HandlerOutcome {
        val action = ctx.message.cardAction ?: return HandlerOutcome.skipped(
            TaskKind.COMMAND, "非提问类卡片回传", silent = true,
        )
        val value = action.actionValue.orEmpty()
        val option = value.substringAfter(':', missingDelimiterValue = "")
        // CARD_ACTION 入站的 messageId 即所点卡片 id（gateway fromCardAction 契约；可空兜底跳过）
        val cardMessageId = ctx.message.messageId.takeIf { it.isNotBlank() }
            ?: return HandlerOutcome.skipped(TaskKind.COMMAND, "卡片回传缺锚", silent = true)
        val accepted = askBus.decide(
            cardMessageId = cardMessageId,
            senderOpenId = ctx.message.senderId,
            values = if (option.isBlank()) emptyMap() else mapOf(ACTION_FIELD_ID to option),
            skipped = false,
        )
        return if (accepted) {
            HandlerOutcome.success(TaskKind.COMMAND, "已收到回答")
        } else {
            HandlerOutcome.skipped(TaskKind.COMMAND, "回答未被采纳（非发起者或已不在途）", silent = true)
        }
    }

    /** 文本主通道：引用回复的全文 = 自由文本答案（单字段 unnamed 形态） */
    private suspend fun handleTextAnswer(ctx: RequestContext, cardMessageId: String): HandlerOutcome {
        val accepted = askBus.decide(
            cardMessageId = cardMessageId,
            senderOpenId = ctx.message.senderId,
            values = mapOf(TEXT_FIELD_ID to ctx.text),
            skipped = false,
        )
        return if (accepted) {
            Responder.decorate(HandlerOutcome.success(TaskKind.COMMAND, "已收到回答"))
        } else {
            // 非发起者回复/已不在途：静默跳过（他人引用提问卡是群聊正常现象，不回执打扰）
            Responder.decorate(
                HandlerOutcome.skipped(TaskKind.COMMAND, "回答未被采纳（非发起者或已不在途）", silent = true),
            )
        }
    }

    companion object {
        /** 提问卡按钮 value 前缀（与确认卡 `<token>:allow/deny` 形态区分） */
        internal const val ACTION_VALUE_PREFIX = "qa-"

        /** 按钮答案落位字段 id（feature 侧提问卡构建时与 AskField id 对齐） */
        internal const val ACTION_FIELD_ID = "answer"

        /** 自由文本答案落位字段 id */
        internal const val TEXT_FIELD_ID = "answer"
    }
}
