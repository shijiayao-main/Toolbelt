// CLI 委托后端退役后的 CLI 形态（serve/handle-event）AgentRunner 占位实现：
// App 形态恒注入 Koog 调度 agent（agentRunnerOverride），CLI 形态无应答引擎——
// 未命中 agent 消息经本类显式失败（fail-closed，绝不静默假成功）。
package com.jiaoay.lark.bridge.agent

import com.jiaoay.lark.bridge.exec.AgentLine
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes

/**
 * 无应答引擎的占位执行器（CLI 形态装配专用）。
 *
 * - [backendId] = "none"：审计/统计上与任何真实后端可区分；
 * - [isAvailable] 恒 false：环境预检据此报「agent 不可用」；
 * - [run] 恒 `AGENT_BACKEND_UNAVAILABLE`（显式错误码，不静默）；
 * - [resetSession]/[resetReceipt] 恒 false/null（无会话态可清）。
 */
class NoAgentRunner : AgentRunner {

    override val backendId: String = BACKEND_ID

    override val isAvailable: Boolean = false

    override suspend fun run(request: AgentRequest, onLine: (AgentLine) -> Unit): AgentRunResult = failResult()

    override suspend fun run(
        request: AgentRequest,
        chatId: String,
        senderOpenId: String,
        progress: ProgressAccumulator?,
        onLine: (AgentLine) -> Unit,
    ): AgentRunResult {
        progress?.markStatus(RunStatus.FAILED)
        return failResult()
    }

    override suspend fun resetSession(sessionKey: String): Boolean = false

    override suspend fun resetReceipt(sessionKey: String): String? = null

    private fun failResult(): AgentRunResult = AgentRunResult(
        exitCode = -1,
        summary = SUMMARY,
        detail = SUMMARY,
        errorCode = TaskErrorCodes.AGENT_BACKEND_UNAVAILABLE,
        elapsedMillis = 0L,
    )

    companion object {
        const val BACKEND_ID = "none"

        /** 失败文案：引导语义 = 该形态无应答引擎（不是配置错误，是能力边界）。 */
        const val SUMMARY =
            "本形态未装配应答引擎（本地 AI 调度 agent 仅桌面 App 形态可用）——消息已回放但无法生成回复。"
    }
}
