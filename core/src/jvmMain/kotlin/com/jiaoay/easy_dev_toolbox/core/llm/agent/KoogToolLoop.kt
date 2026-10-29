// Koog 工具循环骨架：AIAgent 装配 + handleEvents 事件映射 + 超时 + CE 重抛的**单点纪律**。
// 为什么抽骨架：AIAgent 装配与三条循环纪律（CE 原样重抛 / TimeoutCancellationException 分类 /
//  maxIterations 封顶）必须单点——纪律散在多处会漏修（混合响应工具丢失 bug 教训）。骨架把**纪律**收口，把**差异**留给参数。
package com.jiaoay.easy_dev_toolbox.core.llm.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import kotlinx.coroutines.withTimeout

/**
 * 一次工具循环运行的输入（骨架差异面）。
 *
 * @param prompt 任务提示词
 * @param tools 本任务工具注册表（围栏由调用方注入）
 * @param systemPrompt 系统提示词（调用方组装：DecryptAgent 固定文案 / KoogAgentRunner 按
 *        per-user 性格分流组装）
 * @param strategy **执行策略**（🔴 红线 1：必须可传——`larkDispatchStrategy` 的边序对调
 *        修混合响应工具丢失问题；null = Koog 官方 singleRunStrategy）
 * @param maxIterations 失控循环封顶（Koog 语义单位 = **节点数**：稳态每工具轮 2 节点
 *        ——nodeSendToolResult→nodeExecuteTool 回环、nodeCallLLM 仅首轮一次；12 ≈ 5~6 工具轮、
 *        60 ≈ 29~30 轮，见 KoogAgentRunner.kt DEFAULT_MAX_ITERATIONS 注释的换算实测）
 * @param overallTimeoutMillis 整体超时（单次调用超时已在 ConnectionTimeoutConfig 内建，
 *        此层防工具循环整体跑飞）
 * @param sessionId Koog AIAgent.run 的 session key（ChatMemory 按 runId 挂接多轮；单轮链传 null）
 * @param onEvent 事件回调（Koog 内部协程线程调用——调用方自行线程安全化：KoogAgentRunner 喂
 *        ProgressAccumulator / DecryptAgent 落 trace 列表）
 */
public class KoogToolLoopRequest(
    public val prompt: String,
    public val tools: ToolRegistry,
    public val systemPrompt: String,
    public val strategy: AIAgentGraphStrategy<String, String>? = null,
    public val maxIterations: Int,
    public val overallTimeoutMillis: Long,
    public val sessionId: String? = null,
    public val onEvent: (KoogLoopEvent) -> Unit = {},
)

/**
 * 骨架外抛的统一事件形态（调用方各自映射：进度卡 / 轨迹列表 / 消息流）。
 *
 * 事件 v2（参照 OpenHands Action→Observation 模式）：
 * - 工具三事件带 `toolCallId`（可空——Koog 侧理论上可为 null）——消费方按 id 做
 *   「同一张卡的状态迁移」而非三条独立轨迹（RUNNING → COMPLETED/FAILED 原位替换）；
 * - `argsSummary` / `observation` 截断摘要（全文走 ToolResultSink 结构化旁路）；
 * - [StreamingFrame] 携带文本 delta（非文本帧 null）。桥接侧裁定不变：只 touch 不进窗
 *   （滚动窗口防 token 帧糊屏——KoogAgentRunner 消费点注释为准）。
 */
public sealed interface KoogLoopEvent {
    public data class LlmCallStarting(val modelId: String) : KoogLoopEvent
    public data class LlmCallCompleted(val text: String?) : KoogLoopEvent
    public data class ToolCallStarting(
        val toolCallId: String?,
        val toolName: String,
        val argsSummary: String,
    ) : KoogLoopEvent

    public data class ToolCallCompleted(
        val toolCallId: String?,
        val toolName: String,
        val observation: String?,
    ) : KoogLoopEvent

    public data class ToolCallFailed(
        val toolCallId: String?,
        val toolName: String,
        val error: String,
    ) : KoogLoopEvent

    public data class ExecutionFailed(val error: String) : KoogLoopEvent

    /** 流式 token 帧到达（delta = 文本增量；非文本帧 null）。调用方按需 touch 看门狗或累积流式文本 */
    public data class StreamingFrame(val delta: String?) : KoogLoopEvent

    /** 观察文本截断上限（完整结果走 ToolResultSink 结构化旁路，事件只带摘要） */
    public companion object {
        public const val ARGS_SUMMARY_LIMIT: Int = 160
        public const val OBSERVATION_LIMIT: Int = 200
    }
}

public suspend fun koogToolLoop(
    executor: MultiLLMPromptExecutor,
    llmModel: LLModel,
    request: KoogToolLoopRequest,
): String {
    val agent = AIAgent(
        promptExecutor = executor,
        llmModel = llmModel,
        // 🔴 红线 1：strategy 必须可传——null 时回落 Koog 官方 singleRunStrategy（DecryptAgent
        //    单轮链语义）；KoogAgentRunner 恒传 larkDispatchStrategy()（混合响应工具优先修复）
        strategy = request.strategy ?: singleRunStrategy(),
        toolRegistry = request.tools,
        systemPrompt = request.systemPrompt,
        maxIterations = request.maxIterations,
    ) {
        handleEvents {
            onLLMCallStarting { ctx -> request.onEvent(KoogLoopEvent.LlmCallStarting(ctx.model.id)) }
            onLLMCallCompleted { ctx ->
                request.onEvent(KoogLoopEvent.LlmCallCompleted(ctx.response?.textContent()?.trim()))
            }
            onToolCallStarting { ctx ->
                request.onEvent(KoogLoopEvent.ToolCallStarting(ctx.toolCallId, ctx.toolName, summarizeArgs(ctx.toolArgs)))
            }
            onToolCallCompleted { ctx ->
                request.onEvent(KoogLoopEvent.ToolCallCompleted(ctx.toolCallId, ctx.toolName, summarizeResult(ctx.toolResult)))
            }
            onToolCallFailed { ctx ->
                request.onEvent(
                    KoogLoopEvent.ToolCallFailed(
                        ctx.toolCallId,
                        ctx.toolName,
                        ctx.error?.message ?: "未知",
                    ),
                )
            }
            onLLMStreamingFrameReceived { ctx ->
                request.onEvent(KoogLoopEvent.StreamingFrame((ctx.streamFrame as? StreamFrame.TextDelta)?.text))
            }
        }
    }
    return withTimeout(request.overallTimeoutMillis) {
        if (request.sessionId != null) {
            agent.run(request.prompt, request.sessionId)
        } else {
            agent.run(request.prompt)
        }
    }
}

/**
 * 事件摘要辅助（文件级 internal——两入口共用，直测缝）：
 * 参数/结果只取摘要（全文经 ToolResultSink 结构化旁路），防长结果刷爆消息流。
 */
internal fun summarizeArgs(args: JSONObject): String = args.toString()
    .take(KoogLoopEvent.ARGS_SUMMARY_LIMIT)

internal fun summarizeResult(result: JSONElement?): String? =
    result?.toString()?.take(KoogLoopEvent.OBSERVATION_LIMIT)

/**
 * [koogToolLoop] 的带特性安装钩子变体（多轮链用）。
 *
 * 🔴 红线 3 的另一半：骨架**不接管 ChatMemory**——但多轮链（KoogAgentRunner）的
 * ChatMemory 安装与骨架的 handleEvents 必须共存于同一个 installFeatures 切面
 * （AIAgent 构造只接受一个 lambda——装两次即互相覆盖，事件映射与记忆安装必丢其一）。
 * 本变体把特性安装暴露为参数，骨架仍收口
 * 事件映射/超时/纪律，调用方只注入自己的 Feature（先例配 ChatMemory.Feature）。
 *
 * @param installFeatures 特性安装块（receiver = GraphAIAgent.FeatureContext）——骨架的
 *        handleEvents 已内置，调用方追加自己的 Feature（不要重复装 handleEvents）
 */
public suspend fun koogToolLoopWithMemory(
    executor: MultiLLMPromptExecutor,
    llmModel: LLModel,
    request: KoogToolLoopRequest,
    installFeatures: ai.koog.agents.core.agent.GraphAIAgent.FeatureContext.() -> Unit,
): String {
    val agent = AIAgent(
        promptExecutor = executor,
        llmModel = llmModel,
        strategy = request.strategy ?: singleRunStrategy(),
        toolRegistry = request.tools,
        systemPrompt = request.systemPrompt,
        maxIterations = request.maxIterations,
    ) {
        this.installFeatures() // 调用方特性（如 ChatMemory.Feature）先装
        handleEvents {
            onLLMCallStarting { ctx -> request.onEvent(KoogLoopEvent.LlmCallStarting(ctx.model.id)) }
            onLLMCallCompleted { ctx ->
                request.onEvent(KoogLoopEvent.LlmCallCompleted(ctx.response?.textContent()?.trim()))
            }
            onToolCallStarting { ctx ->
                request.onEvent(KoogLoopEvent.ToolCallStarting(ctx.toolCallId, ctx.toolName, summarizeArgs(ctx.toolArgs)))
            }
            onToolCallCompleted { ctx ->
                request.onEvent(KoogLoopEvent.ToolCallCompleted(ctx.toolCallId, ctx.toolName, summarizeResult(ctx.toolResult)))
            }
            onToolCallFailed { ctx ->
                request.onEvent(
                    KoogLoopEvent.ToolCallFailed(
                        ctx.toolCallId,
                        ctx.toolName,
                        ctx.error?.message ?: "未知",
                    ),
                )
            }
            onLLMStreamingFrameReceived { ctx ->
                request.onEvent(KoogLoopEvent.StreamingFrame((ctx.streamFrame as? StreamFrame.TextDelta)?.text))
            }
        }
    }
    return withTimeout(request.overallTimeoutMillis) {
        if (request.sessionId != null) {
            agent.run(request.prompt, request.sessionId)
        } else {
            agent.run(request.prompt)
        }
    }
}
