package com.jiaoay.easy_dev_toolbox.core.llm.tools

/**
 * agent 主动提问 HITL 模型域（Quest 域 change 1——openspec add-flow-ask-user-and-flows-schema）。
 *
 * ## 定位
 * 「agent 执行中主动向用户索要信息」的结构化契约：agent 调 `user.ask_user` 工具时，
 * 工具挂起经 [UserQuestionGate] 把 [AskRequest] 递到宿主 UI 面（表单卡），用户回答
 * [AskAnswer] 回流后作为工具结果喂 LLM 继续执行。
 *
 * 先例：escapeGate（`ToolExecutionContext.PathEscapeGate` 的 suspend 挂起裁决）+
 * 桥接 ConfirmService（CompletableDeferred 挂起等卡片回传）——但两者都是**二元裁决**；
 * 本域是**开放式结构化提问**（全仓首例）。
 *
 * ## 软反馈哲学（与围栏 DENIED 同源）
 * 用户跳过 / 等待超时不让任务崩：工具回传软文案（AskUserTool 内组装），agent 基于
 * 现有信息自行调整——HITL 的失败模式是「降级继续」而非「终止」。
 */
sealed interface AskField {
    val id: String
    val label: String
    val required: Boolean

    /** 单行文本（自由输入） */
    data class Text(
        override val id: String,
        override val label: String,
        override val required: Boolean = true,
        val placeholder: String? = null,
    ) : AskField

    /** 单选（选项集内选一） */
    data class Choice(
        override val id: String,
        override val label: String,
        val options: List<String>,
        override val required: Boolean = true,
    ) : AskField

    /** 多选（选项集内选零到多个，逗号拼接回传） */
    data class MultiChoice(
        override val id: String,
        override val label: String,
        val options: List<String>,
        override val required: Boolean = true,
    ) : AskField

    /** 文件/目录路径（宿主 UI 走目录选择器/拖入；手输亦可） */
    data class FilePath(
        override val id: String,
        override val label: String,
        override val required: Boolean = true,
        val placeholder: String? = null,
    ) : AskField
}

/** 一次提问请求（经 ToolResultSink AskRequestPayload 旁路给宿主 UI 渲染表单卡）。 */
data class AskRequest(
    /** Koog toolCallId（可空——时间线卡状态迁移配对用） */
    val toolCallId: String?,
    /** 提问主文案（agent 生成的自然语言问题） */
    val question: String,
    /** 结构化字段表单（空 = 单条自由文本回答） */
    val fields: List<AskField> = emptyList(),
    /** 是否允许用户跳过（false 时 UI 不渲染跳过钮；跳过仍可能因超时发生） */
    val allowSkip: Boolean = false,
)

/** 用户回答（values 按字段 id 索引；skipped = 用户点了「暂无补充」）。 */
data class AskAnswer(
    val values: Map<String, String>,
    val skipped: Boolean = false,
)

/**
 * 提问桥接缝（宿主 UI 实现——如 QuestSession 的 pendingQuestion StateFlow + deferred）。
 *
 * 语义：onAsk **挂起**直到用户回答/跳过/超时（超时语义在实现侧——基建层只提供
 * [resolveAskCapMillis] 换算）；CE 取消自动传播（工具协程被 cancel 时
 * 挂起点自动中断，AgentToolMiddlewareTest 先例纪律）。
 */
fun interface UserQuestionGate {
    suspend fun onAsk(request: AskRequest): AskAnswer
}

/** ask 域等待上限换算（🔴 红线：软超时必须先于 koogToolLoop 硬超时触发——倒置则用户作答中整链被硬超时误杀，任务不可逆中断）。 */
object AskCaps {

    /** 下限：1 分钟（交互轮不设过短软超时——用户可能正在找文件） */
    private const val MIN_CAP_MILLIS = 60_000L

    /** 上限：30 分钟（钳域——极端大的整体预算不放大等人窗口） */
    private const val MAX_CAP_MILLIS = 30 * 60_000L

    /** 换算系数：0.8——软超时触发后仍留 20% 硬预算余量让 agent 收尾输出 */
    private const val CAP_RATIO = 0.8

    /**
     * 整体预算 → 提问等待上限（桥接 resolveConfirmTimeoutUpperBound 纪律的本位平移：
     * 确认等待不得超过 agent 总超时，否则 agent 已被 kill 确认还挂着）。
     */
    fun resolveAskCapMillis(overallTimeoutMillis: Long): Long {
        if (overallTimeoutMillis <= 0L) return MIN_CAP_MILLIS
        val scaled = (overallTimeoutMillis * CAP_RATIO).toLong()
        return scaled.coerceIn(MIN_CAP_MILLIS, MAX_CAP_MILLIS)
    }
}
