package com.jiaoay.testfixtures

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock



/**
 * Koog fake executor 基座（跨模块测试基建单源）。
 *
 * 原三处「配方拷贝」收敛：KoogToolLoopTest（:core）、KoogAgentRunnerTest
 * （:feature-larkbridge）、QuestSessionTest 的 SucceedingExecutor（:feature-quest）
 * 注释互证「照抄配方」。
 *
 * 🔴 本类存在的理由（koog fake executor 事故教训）：
 *    AIAgent 实调 [execute] 的 **ResolvedModel 重载**——只重写 LLModel 重载会被基类
 *    默认实现绕开、真 client 发网络请求。本基座钉死重写 ResolvedModel 重载，并挂
 *    真 OpenAI client 于不可达端点（provider 匹配让 resolveModel 放行）——永不发请求。
 *
 * 子类只覆写 [fakeExecute]（默认回「骨架测试回复」）：
 * ```kotlin
 * private class HangingExecutor : KoogFakeExecutor() {
 *     override suspend fun fakeExecute(): Message.Assistant {
 *         delay(60_000); return super.fakeExecute()
 *     }
 * }
 * ```
 */
open class KoogFakeExecutor : MultiLLMPromptExecutor(
    OpenAILLMClient(
        apiKey = "sk-test",
        settings = OpenAIClientSettings(
            baseUrl = "http://localhost:1", // 不可达端点——execute 已重写，永不发请求
            timeoutConfig = ConnectionTimeoutConfig(requestTimeoutMillis = 1_000),
        ),
    ),
) {
    override suspend fun execute(
        prompt: Prompt,
        model: ResolvedModel,
        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
    ): Message.Assistant = fakeExecute()

    /** 子类覆写面：默认回固定骨架文本（metaInfo 用 KoogClock.System 真值）。 */
    protected open suspend fun fakeExecute(): Message.Assistant = Message.Assistant(
        content = "骨架测试回复",
        metaInfo = ResponseMetaInfo.create(KoogClock.System),
    )
}
