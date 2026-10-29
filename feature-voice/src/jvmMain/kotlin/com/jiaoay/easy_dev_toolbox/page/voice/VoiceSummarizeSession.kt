package com.jiaoay.easy_dev_toolbox.page.voice

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.WorkbenchPrompts
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogToolLoopRequest
import com.jiaoay.easy_dev_toolbox.core.llm.agent.koogToolLoop
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoBoard
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResultSink
import com.jiaoay.easy_dev_toolbox.core.llm.tools.WriteTodosTool
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AI 整理会话：
 * 转写产物文件 register 进围栏 → koogToolLoop（fs.extended.read_file + write_todos
 * 点名装配——**不新增工具目录条目**）→ 纪要文本 → 审阅卡（采纳才写回）。
 *
 * - 预算 = 全局 agent 预算 [LlmConfigStore.getMaxIterations]（整理是短流程，无独立 key）；
 * - 会话无 reducer 时间线面（纪要是一次性产出，不经 ChatEntry 流）——比 WorkbenchSession
 *   轻得多；状态只有 [state]（Idle/Running/产出文本）。
 */
class VoiceSummarizeSession(
    private val scope: CoroutineScope,
    private val configProvider: suspend () -> LlmEndpointConfig = { LlmEndpointConfig.read() },
    private val executorProvider: suspend (LlmEndpointConfig) -> MultiLLMPromptExecutor = { LlmClientFactory.executor(it) },
    private val modelProvider: suspend (LlmEndpointConfig) -> LLModel = { LlmClientFactory.model(it.modelId) },
) {

    /** 计划投影板（write_todos 挂接） */
    val todoBoard = TodoBoard()

    /** 会话围栏：转写产物文件 register（read_file 可读） */
    private val context = ToolExecutionContext()

    sealed class State {
        data object Idle : State()
        data object Running : State()
        /** 纪要产出（审阅中——未采纳不写回） */
        data class Produced(val markdown: String) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var runJob: Job? = null

    /** 发起整理：transcriptFile = 转写产物 md（围栏内）；产出进 [State.Produced] */
    fun startSummarize(transcriptFile: File) {
        if (_state.value is State.Running) return
        context.register(transcriptFile)
        _state.value = State.Running
        runJob = scope.launch {
            val config = configProvider()
            try {
                val output = koogToolLoop(
                    executor = executorProvider(config),
                    llmModel = modelProvider(config),
                    request = KoogToolLoopRequest(
                        prompt = buildPrompt(transcriptFile),
                        tools = buildRegistry(),
                        systemPrompt = VoiceSummarizePrompts.SUMMARIZE_PROMPT,
                        strategy = null,
                        maxIterations = LlmConfigStore.getMaxIterations(),
                        overallTimeoutMillis = config.timeoutSeconds * 1000L * OVERALL_TIMEOUT_ROUNDS,
                        sessionId = "voice-summarize",
                        onEvent = { /* 纪要会话无时间线面——事件零消费（超短流程） */ },
                    ),
                )
                _state.value = State.Produced(output)
            } catch (e: TimeoutCancellationException) {
                _state.value = State.Failed("整理超时（预算 ${config.timeoutSeconds}s × $OVERALL_TIMEOUT_ROUNDS 轮）")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message?.take(300) ?: e::class.simpleName ?: "整理失败")
            }
        }
    }

    /** prompt 组装（纯函数直测缝） */
    internal fun buildPrompt(transcriptFile: File): String =
        "请读取并整理以下转写原文文件：\n${transcriptFile.absolutePath}\n\n按纪要格式输出三段结构化纪要。"

    /** 工具集装配（fs.extended.read_file 一件 + write_todos 补挂——最精简面） */
    private fun buildRegistry() = AgentToolCatalog.buildRegistry(
        names = SUMMARIZE_NAMES,
        context = context,
        sink = ToolResultSink.NOOP,
        config = AgentToolCatalog.ToolContextConfig(
            privateKeyProvider = DecryptTools.defaultPrivateKeyProvider,
            todoBoard = todoBoard,
            memoryDir = null,
        ),
        middlewares = emptyList(),
    ).let { base ->
        com.jiaoay.easy_dev_toolbox.core.llm.agent.decorateRegistry(
            ai.koog.agents.core.tools.ToolRegistry {
                base.tools.forEach { tool(it) }
                tool(WriteTodosTool(todoBoard, ToolResultSink.NOOP))
            },
            emptyList(),
        )
    }

    /** 采纳（审阅卡确认——写回纪要区由 VM 持有） */
    fun accept(): String? = (_state.value as? State.Produced)?.markdown

    /** 重新整理（审阅卡驳回后再跑） */
    fun retry(transcriptFile: File) {
        _state.value = State.Idle
        startSummarize(transcriptFile)
    }

    fun cancel() {
        runJob?.cancel()
        _state.value = State.Idle
    }

    companion object {
        /** 整理链只装一件取证工具（read_file 逐页读原文）——最精简面 */
        internal val SUMMARIZE_NAMES: Set<String> = setOf("fs.extended.read_file")

        /** 整体超时 = 单次调用超时 × 4 轮（短流程；工作台 6 轮先例减档） */
        const val OVERALL_TIMEOUT_ROUNDS = 4
    }
}
