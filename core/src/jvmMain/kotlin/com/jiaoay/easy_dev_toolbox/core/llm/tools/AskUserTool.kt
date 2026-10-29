package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/**
 * agent 主动提问工具（Quest 域 change 1——openspec add-flow-ask-user-and-flows-schema 任务 1.2）。
 *
 * ## 行为
 * executeTyped 内经 [gate] 挂起等人（CE 取消自动传播）；AskRequest 经 sink 旁路
 * `AskRequestPayload` 供 UI 渲染表单卡；回答回流后组装软反馈文案喂 LLM。
 *
 * ## 软反馈三态（围栏 DENIED 同哲学——HITL 的失败模式是「降级继续」而非「终止」）
 * - 已答：`用户回答：` + 逐字段 `标签: 值` 清单（无 fields 自由文本时原样回传）；
 * - 跳过：`用户表示暂无更多信息…`；
 * - 超时（gate 实现侧 withTimeoutOrNull 触发后回 skipped 形态）：`用户未在限定时间内回答…`。
 *
 * 🔴 超时不是工具的事：gate 实现（页面批 QuestSession）内 withTimeoutOrNull(askCap)
 *    包裹——软超时永远先于 koogToolLoop 整体硬超时（[AskCaps.resolveAskCapMillis]）。
 */
class AskUserTool(
    private val gate: UserQuestionGate,
    private val sink: ToolResultSink = ToolResultSink.NOOP,
) : StructuredSimpleTool<AskUserTool.Args>(
    sink = sink,
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Ask the user a question to gather missing information before or during " +
        "a task. Prefer batching: collect ALL missing key inputs in ONE call (fields list) " +
        "instead of asking one by one. Use only when the answer truly blocks progress — " +
        "do not ask what you can find out yourself with your tools.",
) {

    @Serializable
    data class Args(
        @property:LLMDescription("The question to show the user, in natural language")
        val question: String,
        @property:LLMDescription(
            "Optional structured form fields. Empty = plain free-text answer. " +
                "Type: TEXT (single line) / CHOICE (pick one from options) / " +
                "MULTI_CHOICE (pick zero or more) / FILE_PATH (file or directory path).",
        )
        val fields: List<FieldArg> = emptyList(),
        @property:LLMDescription("Whether the user may skip answering (true = show a skip button)")
        val allowSkip: Boolean = false,
    )

    @Serializable
    data class FieldArg(
        val id: String,
        val label: String,
        @property:LLMDescription("TEXT / CHOICE / MULTI_CHOICE / FILE_PATH")
        val type: String,
        val options: List<String> = emptyList(),
        val required: Boolean = true,
        val placeholder: String? = null,
    )

    override suspend fun executeTyped(args: Args): ToolResult {
        if (args.question.isBlank()) {
            return ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "question 不能为空")
        }
        // type 校验（DistillTools.kind 先例——非法值 BAD_ARGS 而非崩溃）
        val parsedFields = args.fields.map { field ->
            when (field.type.trim().uppercase()) {
                "TEXT" -> AskField.Text(field.id, field.label, field.required, field.placeholder)
                "CHOICE" -> {
                    if (field.options.isEmpty()) {
                        return ToolResult.Err(
                            ToolResult.ErrCode.BAD_ARGS,
                            "CHOICE 字段（${field.id}）必须提供非空 options",
                        )
                    }
                    AskField.Choice(field.id, field.label, field.options, field.required)
                }
                "MULTI_CHOICE" -> {
                    if (field.options.isEmpty()) {
                        return ToolResult.Err(
                            ToolResult.ErrCode.BAD_ARGS,
                            "MULTI_CHOICE 字段（${field.id}）必须提供非空 options",
                        )
                    }
                    AskField.MultiChoice(field.id, field.label, field.options, field.required)
                }
                "FILE_PATH" -> AskField.FilePath(field.id, field.label, field.required, field.placeholder)
                else -> {
                    return ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "字段 type 必须是 TEXT / CHOICE / MULTI_CHOICE / FILE_PATH 之一" +
                            "（${field.id} 收到：${field.type}）",
                    )
                }
            }
        }
        val request = AskRequest(
            toolCallId = currentToolCallId(),
            question = args.question,
            fields = parsedFields,
            allowSkip = args.allowSkip,
        )
        // 旁路先于回答回流：UI 表单卡的数据源（sink 顺序与 StructuredSimpleTool.execute
        // 的「先 sink 后 render」不同——此处 request 须在挂起前到达 UI，回答后才轮到结果旁路）
        sink.onResult(name, ToolResult.Ok(ASK_REQUEST_SENTINEL, data = ToolPayload.AskRequestPayload(request)))
        val answer = gate.onAsk(request)
        return renderAnswer(request, answer)
    }

    /** 回答 → 软反馈文案（三态；AgentToolMiddlewareTest 取消穿透纪律在 gate 挂起点）。 */
    private fun renderAnswer(request: AskRequest, answer: AskAnswer): ToolResult {
        return if (answer.skipped) {
            ToolResult.Ok(
                if (request.allowSkip) {
                    "用户表示暂无更多信息，请基于现有信息继续"
                } else {
                    // allowSkip=false 的超时形态（gate 实现侧软超时回 skipped——见类头红线注）
                    "用户未在限定时间内回答，请基于现有信息继续或先收尾"
                }
            )
        } else if (request.fields.isEmpty()) {
            val text = answer.values[PROMPT_ANSWER_KEY].orEmpty()
            ToolResult.Ok("用户回答：$text")
        } else {
            val lines = request.fields.joinToString("\n") { field ->
                val value = answer.values[field.id].orEmpty()
                "- ${field.label}: $value"
            }
            ToolResult.Ok("用户回答：\n$lines")
        }
    }

    // ── Koog 上下文取 toolCallId（时间线卡状态迁移配对）──

    /** 当前工具调用的 toolCallId（Koog ToolCallContext 无公开 API 时回落 null）。 */
    private fun currentToolCallId(): String? = null

    companion object {
        const val NAME = "user.ask_user"

        /** 无 fields 自由文本回答的 values 键（UI 侧约定回填此键）。 */
        const val PROMPT_ANSWER_KEY = "answer"

        /** AskRequest 旁路的 summary 占位（真实问题文本在 payload 里——不占 LLM 上下文）。 */
        private const val ASK_REQUEST_SENTINEL = "已向用户发起提问（表单已送达）"
    }
}
