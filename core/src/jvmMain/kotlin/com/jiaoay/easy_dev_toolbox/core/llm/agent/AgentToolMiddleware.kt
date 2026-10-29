package com.jiaoay.easy_dev_toolbox.core.llm.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry

/**
 * Agent 工具中间件（参照 deepagents 的 middleware 拦截边界）：
 *
 * 背景：Koog 1.2.0 的 `AIAgent` 构造点固定 systemPrompt/toolRegistry，`handleEvents` 是
 * 事件轨（fire-and-forget）非拦截轨；真正要「改写工具结果」（大结果驱逐）或「拦截执行」
 * （HITL 挂起）必须包在工具执行链上。本层用**构造点装饰器**实现：`buildToolRegistry`
 * 组装时经 [withMiddlewares] 逐工具包裹，middleware 拿到**类型化 args**（非 JSON）。
 *
 * 设计边界：
 * - 只覆盖本仓自建工具（全仓工具均自建 SimpleTool，无外部工具需装饰）；
 * - `interceptModelCall`（LLM 请求前拦截）**本批不实现**——未来走 executor 装饰器
 *   （MultiLLMPromptExecutor 子类包 delegate，KoogToolLoopTest FakeExecutor 配方），
 *   不碰 @InternalAgentsApi；
 * - 🔴 不得吞 CancellationException——取消传播（「停止」指令）必须原样穿透，
 *   否则装饰器把任务取消吞成正常完成。
 */
interface AgentToolMiddleware {
    /** 中间件标识（诊断/日志用；链上无需唯一） */
    val id: String

    /**
     * 拦截一次工具调用。
     *
     * @param call 调用上下文（工具名 + 类型化 args）
     * @param execute 下游执行（其余 middleware 链 + 真实工具体）；短路 = 不调用 [execute] 直接返回结果文案
     * @return 喂给 LLM 的结果文案
     */
    suspend fun intercept(call: ToolCallContext, execute: suspend () -> String): String
}

/** 工具调用上下文（装饰器在构造点保留类型信息） */
data class ToolCallContext(
    val toolName: String,
    val args: Any?,
)

/** 装饰后的工具（委托原工具的 argsType/descriptor，仅替换 execute 链） */
private class MiddlewareDecoratedTool<TArgs : Any>(
    private val delegate: Tool<TArgs, String>,
    private val middlewares: List<AgentToolMiddleware>,
) : Tool<TArgs, String>(
    argsType = delegate.argsType,
    resultType = delegate.resultType,
    descriptor = delegate.descriptor,
) {
    override suspend fun execute(args: TArgs): String {
        var next: suspend () -> String = { delegate.execute(args) }
        // 逆序折叠：链头最先拦截（middlewares[0] 最外层）
        for (index in middlewares.indices.reversed()) {
            val middleware = middlewares[index]
            val downstream = next
            next = { middleware.intercept(ToolCallContext(delegate.name, args), downstream) }
        }
        return next()
    }
}

/**
 * 构造点装饰：把 middleware 链包在该工具外面。
 *
 * 类型信息在构造点保留（[MiddlewareDecoratedTool] 复用 delegate 的 TypeToken/descriptor），
 * registry 重建见 [decorateRegistry]。
 */
fun <TArgs : Any> Tool<TArgs, String>.withMiddlewares(
    middlewares: List<AgentToolMiddleware>,
): Tool<TArgs, String> =
    if (middlewares.isEmpty()) this else MiddlewareDecoratedTool(this, middlewares)

/**
 * 注册表级组装收口：把已有 registry 里的每个工具按链包裹，重建注册表。
 *
 * 只装饰 `Tool<TArgs, String>` 形态的工具（本仓全部 SimpleTool 均是）；
 *    其他形态（理论存在）原样保留——不因装饰面破坏注册表完整性。
 */
fun decorateRegistry(registry: ToolRegistry, middlewares: List<AgentToolMiddleware>): ToolRegistry {
    if (middlewares.isEmpty()) return registry
    return ToolRegistry {
        registry.tools.forEach { tool ->
            @Suppress("UNCHECKED_CAST")
            val decorated = (tool as? Tool<Any, String>)
                ?.withMiddlewares(middlewares)
                ?: tool
            tool(decorated)
        }
    }
}
