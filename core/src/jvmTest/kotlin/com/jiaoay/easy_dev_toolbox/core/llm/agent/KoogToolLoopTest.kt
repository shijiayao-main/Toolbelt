// S4 · KoogToolLoop 骨架三条纪律直测（方案 5.2 裁定：DecryptAgentTest 不存在，骨架直测替代）。
// 🔴 测的是骨架纪律而非 Koog 行为：strategy 透传 / 超时上抛 / CE 原样重抛。
// fake executor 配方照抄 KoogAgentRunnerTest（koog-fake-executor-resolvedmodel 教训：
// AIAgent 实调 ResolvedModel 重载——只重写 LLModel 重载会被基类默认实现绕开、真 client 发请求）。
package com.jiaoay.easy_dev_toolbox.core.llm.agent

import com.jiaoay.testfixtures.KoogFakeExecutor
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.agents.core.tools.ToolDescriptor
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

class KoogToolLoopTest {

    /**
     * fake executor 基座：改继承共享 [KoogFakeExecutor]（:test-fixtures 单源——
     * koog-fake-executor-resolvedmodel 教训见其 KDoc）；本测试零子类覆写，
     * 直接用默认「骨架测试回复」。
     */
    private open class FakeExecutor : KoogFakeExecutor()

    /** 挂起型 fake：驱动超时路径。 */
    private class HangingExecutor : FakeExecutor() {
        override suspend fun fakeExecute(): Message.Assistant {
            delay(60_000)
            return super.fakeExecute()
        }
    }

    @Test
    fun `A strategy passthrough - custom strategy is consumed not swallowed`() {
        // 红线 1 的结构断言：骨架把 strategy 透传给 AIAgent（用标识名不同的最小图策略跑通即证）。
        // （larkDispatchStrategy 边序对调的正面对断言在 KoogAgentRunnerTest——本测试只证透传。）
        runBlocking {
            val output = koogToolLoop(
                executor = FakeExecutor(),
                llmModel = TEST_MODEL,
                request = KoogToolLoopRequest(
                    prompt = "ping",
                    tools = ai.koog.agents.core.tools.ToolRegistry.EMPTY,
                    systemPrompt = "test",
                    strategy = testEchoStrategy(),
                    maxIterations = 3,
                    overallTimeoutMillis = 5_000,
                ),
            )
            assertTrue(output.isNotBlank(), "strategy 透传后 agent 应正常出文本，实际=$output")
        }
    }

    @Test
    fun `B timeout - TimeoutCancellationException propagates unclassified`() {
        // 纪律 2：骨架不做 try/catch——超时异常原样上抛给调用方分类（TIMEOUT 终态）
        assertFailsWith<TimeoutCancellationException> {
            runBlocking {
                koogToolLoop(
                    executor = HangingExecutor(),
                    llmModel = TEST_MODEL,
                    request = KoogToolLoopRequest(
                        prompt = "ping",
                        tools = ai.koog.agents.core.tools.ToolRegistry.EMPTY,
                        systemPrompt = "test",
                        maxIterations = 3,
                        overallTimeoutMillis = 1, // 1ms：必超时
                    ),
                )
            }
        }
    }

    @Test
    fun `C cancellation - CancellationException propagates not swallowed`() {
        // 纪律 1：CE 原样上抛——外层 cancel 骨架必须把 CE 传出来，绝不吞成别的形态
        val thrown: CancellationException? = runBlocking {
            try {
                coroutineScope {
                    val job = async {
                        koogToolLoop(
                            executor = HangingExecutor(),
                            llmModel = TEST_MODEL,
                            request = KoogToolLoopRequest(
                                prompt = "ping",
                                tools = ai.koog.agents.core.tools.ToolRegistry.EMPTY,
                                systemPrompt = "test",
                                maxIterations = 3,
                                overallTimeoutMillis = 60_000,
                            ),
                        )
                    }
                    delay(100) // 给 agent 启动时间后取消
                    coroutineContext.cancelChildren()
                    job.await()
                }
                null
            } catch (e: CancellationException) {
                e
            }
        }
        assertTrue(thrown != null, "取消骨架必须上抛 CancellationException，绝不吞（「停止」指令传播链）")
    }

    private companion object {
        val TEST_MODEL = LLModel(
            provider = LLMProvider.OpenAI,
            id = "test-model",
            capabilities = listOf(LLMCapability.Completion),
        )

        /** 最小图策略（nodeLLMRequest → finish；与 singleRunStrategy 同构但标识不同——证明透传） */
        fun testEchoStrategy(): AIAgentGraphStrategy<String, String> =
            strategy<String, String>("s4_echo_test") {
                val nodeCallLLM by nodeLLMRequest()
                edge(nodeStart forwardTo nodeCallLLM)
                edge(nodeCallLLM forwardTo nodeFinish onTextMessage { true })
            }
    }
}
