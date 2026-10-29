package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskCaps
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskField
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskRequest
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskAnswer
import com.jiaoay.easy_dev_toolbox.core.llm.tools.UserQuestionGate
import com.jiaoay.lark.bridge.confirm.AskUserBus
import com.jiaoay.lark.bridge.pipeline.ReplySink
import com.jiaoay.lark.gateway.CardSpec

/**
 * user.ask_user 的飞书形态适配（[UserQuestionGate] 契约的桥接装配侧）。
 *
 * ## 形态
 * - 提问卡（markdown 非流式卡）承载问题全文 + 字段清单 + 单据号——引用回复该卡即作答
 *   （主通道，零格式学习成本）；单选 Choice 字段另附候选按钮（`qa-<requestId>:<option>`），
 *   点击与引用回复等效；
 * - 挂起经 [AskUserBus.awaitAnswer]（防替答/超时 = skipped 软反馈——HITL 的失败模式是
 *   降级继续而非终止）；
 * - 软超时 [AskCaps.resolveAskCapMillis]（0.8×整体预算，60s..30min 钳域——软超时必须
 *   先于 koogToolLoop 硬超时触发，倒置则用户作答中整链被硬超时误杀）。
 *
 * ## 与确认卡的差异
 * 确认卡是二元裁决（InteractionHandler）；提问卡是开放式回答（AskAnswerHandler）——
 * 卡面无「确认/拒绝」按钮，跳过语义由用户回复内容承载（AskAnswer.skipped 由超时与
 * bus 层显式跳过产生）。
 *
 * ## 回答解析
 * 引用回复的全文 = 自由文本答案（落 `answer` 字段）；按钮回传 = 单选答案。
 * 多字段表单的「key: value 逐行」解析由 feature 侧宽容解析（字段 id 未命中时值并入
 * `answer` 自由文本位——解析失败不崩，软反馈语义）。
 */
internal class KoogUserQuestionGate(
    private val askBus: AskUserBus,
    private val chatId: String,
    private val senderOpenId: String,
    /** 提问卡发送口（null = 发不出卡 ⇒ 返回 skipped 软反馈，不挂起等一个永远不来的回答） */
    private val reply: ReplySink?,
    /** 整体预算供体（当次任务的 overallTimeoutMillis；AskCaps 软超时换算输入） */
    private val overallTimeoutMillisProvider: () -> Long,
) : UserQuestionGate {

    override suspend fun onAsk(request: AskRequest): AskAnswer {
        val reply = reply
            ?: return AskAnswer(values = emptyMap(), skipped = true)
        // 发提问卡（失败 = 软反馈跳过——挂起等一个用户看不到的提问是死等）
        val sent = runCatching {
            reply.card(CardSpec(title = ASK_CARD_TITLE, markdown = askCardMarkdown(request)))
        }.getOrDefault(null)
        val cardMessageId = sent?.messageId
        if (sent?.ok != true || cardMessageId.isNullOrBlank()) {
            return AskAnswer(values = emptyMap(), skipped = true)
        }
        askBus.register(
            cardMessageId = cardMessageId,
            chatId = chatId,
            senderOpenId = senderOpenId,
            question = request.question,
        )
        val answer = askBus.awaitAnswer(
            cardMessageId = cardMessageId,
            timeoutMillis = AskCaps.resolveAskCapMillis(overallTimeoutMillisProvider()),
        )
        return AskAnswer(values = answer.values, skipped = answer.skipped)
    }

    private companion object {
        const val ASK_CARD_TITLE = "❓ 机器人需要你的补充"

        /** 提问卡正文：问题 + 字段清单 + 回答引导（引用本卡回复即作答） */
        fun askCardMarkdown(request: AskRequest): String = buildString {
            appendLine(request.question)
            if (request.fields.isNotEmpty()) {
                appendLine()
                appendLine("**需要补充的信息**：")
                request.fields.forEach { field ->
                    when (field) {
                        is AskField.Text -> appendLine("- ${field.label}（文本）")
                        is AskField.Choice -> appendLine("- ${field.label}（单选：${field.options.joinToString(" / ")}）")
                        is AskField.MultiChoice -> appendLine("- ${field.label}（多选：${field.options.joinToString(" / ")}）")
                        is AskField.FilePath -> appendLine("- ${field.label}（文件/目录路径）")
                    }
                }
            }
            appendLine()
            append("请**引用回复本条消息**作答（一次回复全部内容）；无补充可直接回复「跳过」。")
        }
    }
}
