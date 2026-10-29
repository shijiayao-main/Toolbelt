package com.jiaoay.easy_dev_toolbox.page.logworkbench

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import com.jiaoay.easy_dev_toolbox.core.data.db.SessionStore
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.WorkbenchPrompts
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChip
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChipKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatSessionReducer
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ErrorKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogLoopEvent
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogToolLoopRequest
import com.jiaoay.easy_dev_toolbox.core.llm.agent.NavActivityFacade
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import com.jiaoay.easy_dev_toolbox.core.llm.agent.koogToolLoop
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AnalyzeLogTool
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.KnowledgeTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.LogStatsDiffTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.TodoBoard
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResult
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResultSink
import com.jiaoay.easy_dev_toolbox.core.llm.tools.WebTools
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 工作台研判会话核心（UI 框架无关 reducer，直测缝）。
 *
 * ## 会话形态（单循环，非蒸馏两阶段）
 * - 围栏 = 会话附件集 + escapeGate HITL（附件渐进 register；围栏外路径弹窗确认——
 *   「页面即一个会话」语义，日志文件与管线产物都在围栏内）；
 * - 与蒸馏链的差异：**StreamingFrame 消费**（流式打字机——streaming 草稿→
 *   LlmCallCompleted 转正→finalizeRun 终稿消重，万能解密蓝本同款三段）；
 * - sessionId = runId（时间线 messages 与 run 一一对应）；
 * - 预算 = LlmConfigStore.getWorkbenchMaxIterations（独立 key，缺省 60 节点）；
 * - 多轮记忆 = koogToolLoop + sessionId（run 内多轮工具循环共享 Koog run 上下文；
 *   跨 run 上下文经报告 md 注入 prompt——不装 ChatMemory）。
 *
 * ## sink 产物精确挂载（4.3）
 * analyze_log 各阶段 artifacts → 时间线工具卡（artifacts chips——证据流数据源）+
 * [evidenceFiles]（中栏证据流文件面——阶段产物路径集）。
 *
 * 纪律：CE 原样上抛（cancel 传播）；TimeoutCE 分类 TIMEOUT（CE 分支之前）；不吞异常。
 */
class WorkbenchSession(
    private val scope: CoroutineScope,
    private val runId: String,
    private val configProvider: suspend () -> LlmEndpointConfig,
    private val executorProvider: suspend (LlmEndpointConfig) -> MultiLLMPromptExecutor,
    private val modelProvider: suspend (LlmEndpointConfig) -> LLModel,
    /** 私钥提供缝（管线解密级需要；null = 本会话不装 analyze_log——纯明文分析） */
    private val privateKeyProvider: (suspend () -> String)? = null,
    /** L2 记忆目录（null = 不装 memory.*——与桌面链 memoryDir 配置同源）。真源 = [FileUtils.getMemoryDir] */
    private val memoryDir: File? = FileUtils.getMemoryDir(),
    private val ledger: SessionStore? = null,
    /**
     * 研判失败终态回执（超时/异常分支必达）：入参 = 降级报告文本（错误摘要 + 已产
     * 证据清单）。🔴 失败也必须落终态——只置 running=false 会让 run 行永久 RUNNING
     * （库行状态 ≠ RUNNING 是记录系统不变量）；cancel 路径不走此回调（ABORTED 由
     * VM 收口，语义不同）。回调携带 runId 而非依赖 VM 侧 currentRunId——防回调延迟
     * 期间 session 被置换后写错 run。
     */
    private val onRunFailed: (runId: String, degradedReport: String) -> Unit = { _, _ -> },
) {

    /** 计划投影板（write_todos 挂接——研判过程可视化） */
    val todoBoard = TodoBoard()

    /** 会话 gate 桥：围栏外路径请求 → pendingGate 流（Compose 弹确认）→ await 用户裁决 */
    private val gateBridge = ToolExecutionContext.PathEscapeGate { path -> awaitGateDecision(path) }

    /** 会话形态 context：空初始集 + escapeGate（附件渐进 register） */
    private val context = ToolExecutionContext(escapeGate = gateBridge)

    /** 当前会话 runId（VM 收尾/管理面判定「当前 run」用；只读暴露——构造期定死） */
    val runIdValue: String get() = runId

    /** 运行态（侧栏活动指示/输入禁用判据） */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 事件流（ChatEntry reducer 面——运行面板/时间线数据源；单一真源，reducer 与本类共操作） */
    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()

    /** reducer 引擎（ChatSessionReducer 委托——本类只保留工作台独有面） */
    private val reducer = ChatSessionReducer(
        entries = _entries,
        ledger = ledger,
        ledgerKey = runId,
        streamingEnabled = true, // 工作台开流式面（蒸馏链忽略是专属决策）
        // delta 合并窗（Assistant 节流契约履行——窗值档位见常量 KDoc）
        deltaMergeWindowMillis = STREAMING_DELTA_MERGE_WINDOW_MILLIS,
        flushScope = scope,
    )

    /** HITL 确认门（Compose Dialog 消费弹确认；null 恒拒如现状） */
    private val _pendingGate = MutableStateFlow<File?>(null)
    val pendingGate: StateFlow<File?> = _pendingGate.asStateFlow()

    /** 管线阶段产物路径集（证据流数据源——analyze_log artifacts 累积） */
    private val _evidenceFiles = MutableStateFlow<List<String>>(emptyList())
    val evidenceFiles: StateFlow<List<String>> = _evidenceFiles.asStateFlow()

    /** 本次研判收尾产物（报告 markdown 文本——落盘归 VM；null = 未完成） */
    private val _phaseResult = MutableStateFlow<String?>(null)
    val phaseResult: StateFlow<String?> = _phaseResult.asStateFlow()

    private var runJob: Job? = null
    private val seq = AtomicLong(0)

    /** sink：工具产物精确挂载（artifacts chips → 末尾同名工具卡 + 证据文件集累积——reducer 单源配方） */
    private val sink = ToolResultSink.concurrentOf { name, result ->
        if (result is ToolResult.Ok && result.artifacts.isNotEmpty()) {
            val chips = result.artifacts.map { a ->
                ArtifactChip(a.path, ArtifactChipKind.valueOf(a.kind.name))
            }
            _evidenceFiles.update { cur -> (cur + result.artifacts.map { it.path }).distinct() }
            reducer.attachArtifactsToLastToolCall(name, chips)
        }
    }

    // ── HITL gate 桥（蓝本平移） ──

    fun approveGate() {
        pendingDecision?.complete(true)
        pendingDecision = null
        _pendingGate.value = null
    }

    fun denyGate() {
        pendingDecision?.complete(false)
        pendingDecision = null
        _pendingGate.value = null
    }

    private var pendingDecision: CompletableDeferred<Boolean>? = null

    private suspend fun awaitGateDecision(path: File): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingDecision = deferred
        _pendingGate.value = path
        try {
            return deferred.await()
        } finally {
            _pendingGate.value = null
            pendingDecision = null
        }
    }

    // ── 附件登记（渐进——拖入即进围栏） ──

    /**
     * 附件到达：登记进允许集（管线产物经 context.register 由工具侧自动挂接）。
     * S4 · FileBuffer 双挂：进围栏即出暂存（「拖进会话才授权」——register 是
     * 授权面，Buffer 是待安置面，两者对偶收口在此）。
     */
    fun registerFiles(files: List<File>) {
        files.forEach { context.register(it) }
        com.jiaoay.easy_dev_toolbox.core.buffer.FileBuffer.removeAll(files.map { it.absolutePath })
    }

    // ── 管线直跑挂接面（AI 关闭降级——无 sink 链时手工回补证据面） ──

    /** 管线产物手工登记（直跑降级）：证据文件集 + 时间线工具卡（终态呈现阶段结果） */
    fun addPipelineArtifact(artifact: File, stage: com.jiaoay.log.pipeline.LogAnalysisPipeline.Stage, note: String) {
        _evidenceFiles.update { cur -> (cur + artifact.absolutePath).distinct() }
        reducer.appendToolCallCard(
            toolCallId = "pipeline-${seq.incrementAndGet()}",
            toolName = AnalyzeLogTool.NAME,
            argsSummary = "管线直跑 · ${stage.name}",
            state = ToolState.COMPLETED,
            observation = note,
            artifacts = listOf(ArtifactChip(artifact.absolutePath, ArtifactChipKind.FILE)),
        )
    }

    /** 管线中止登记（直跑降级）：时间线 ErrorBanner（阶段标记 + 原因） */
    fun appendPipelineAbort(stage: com.jiaoay.log.pipeline.LogAnalysisPipeline.Stage, reason: String) {
        reducer.appendError(ErrorKind.FAILED, "管线中止于 ${stage.name}", reason.take(400))
    }

    // ── 研判执行（4.3 runAnalysis） ──

    /**
     * 发起一次研判（表单参数 → prompt 组装 → 单循环 koogToolLoop）。
     *
     * @param input 结构化表单参数（日志文件/时间点/范围/症状/Tag 正则——4.1 表单态）
     * @param followUpContext 追问链上下文（null = 首次研判；非 null = 增量研判——
     *        已有报告 md 注入 prompt，产出增量结论）
     */
    fun startAnalysis(
        input: AnalysisInput,
        followUpContext: String? = null,
    ) {
        if (_running.value) return
        _phaseResult.value = null
        _running.value = true
        registerFiles(input.logFiles.map { File(it) })
        runJob = scope.launch {
            // 配置快照/工具装配失败同样必达失败回执：前置求值若异常漏出，launch 体
            // 无 catch 兜底，run 行会永久停在 RUNNING（库行状态 ≠ RUNNING 不变量
            // 对一切失败路径成立，不限于 koogToolLoop 内的失败）
            val prepared = runCatching { configProvider() to buildRegistry() }
            val (config, registry) = prepared.getOrElse { e ->
                if (e is CancellationException) throw e
                val reason = e.message?.take(400) ?: e::class.simpleName ?: "未知错误"
                reducer.appendError(ErrorKind.FAILED, "研判失败", reason)
                onRunFailed(runId, degradedFailureReport(reason))
                _running.value = false
                return@launch
            }
            try {
                val output = koogToolLoop(
                    executor = executorProvider(config),
                    llmModel = modelProvider(config),
                    request = KoogToolLoopRequest(
                        prompt = buildPrompt(input, followUpContext),
                        tools = registry,
                        systemPrompt = WorkbenchPrompts.ANALYZE_PROMPT,
                        strategy = null, // 单链官方 singleRunStrategy（骨架回落）
                        maxIterations = LlmConfigStore.getWorkbenchMaxIterations(),
                        overallTimeoutMillis = config.timeoutSeconds * 1000L * OVERALL_TIMEOUT_ROUNDS,
                        sessionId = runId,
                        onEvent = { event -> onLoopEvent(event) },
                    ),
                )
                _phaseResult.value = output
                _running.value = false
            } catch (e: TimeoutCancellationException) {
                val reason = "研判整体超时（预算 ${config.timeoutSeconds}s × $OVERALL_TIMEOUT_ROUNDS 轮）"
                reducer.appendError(ErrorKind.TIMEOUT, "研判整体超时", "预算 ${config.timeoutSeconds}s × $OVERALL_TIMEOUT_ROUNDS 轮")
                onRunFailed(runId, degradedFailureReport(reason))
                _running.value = false
            } catch (e: CancellationException) {
                // 中止保全：已产出的条目/证据/部分结论保留（run 态由 VM 收口——ABORTED）
                throw e
            } catch (e: Exception) {
                val reason = e.message?.take(400) ?: e::class.simpleName ?: "未知错误"
                reducer.appendError(ErrorKind.FAILED, "研判失败", reason)
                onRunFailed(runId, degradedFailureReport(reason))
                _running.value = false
            } finally {
                // busy 复位兜底：任何路径（含中止/失败分支）都保证 running 复位，
                // 按钮不得恒「研判运行中…」（cancel() 自带复位在前，此处重复写 false
                // 无害幂等）。
                _running.value = false
            }
        }
    }

    /**
     * 失败降级报告（五部分骨架同 [runPipelineOnly] 直跑降级——ReportPanel parseReport
     * 按段解析）：错误摘要 + 已产证据清单。报告落盘/状态落库由 VM 经 [onRunFailed] 承接。
     */
    private fun degradedFailureReport(reason: String): String = buildString {
        appendLine("# 日志分析报告")
        appendLine("## 结论")
        appendLine("——研判未完成，本报告为失败降级记录（时间线内失败前的条目已保留）")
        appendLine()
        appendLine("## 问题根因")
        appendLine("- ✗ $reason")
        appendLine()
        appendLine("## 完整时间线")
        appendLine("（失败前的工具执行条目见证据流时间线）")
        appendLine()
        appendLine("## 关键依据")
        val evidence = _evidenceFiles.value
        if (evidence.isEmpty()) {
            appendLine("（无已产证据）")
        } else {
            evidence.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## 元数据")
        appendLine("- 终态：FAILED（研判失败，非完成态结论）")
    }

    /** 结构化输入（表单态——4.1/5.3 消费面） */
    data class AnalysisInput(
        val logFiles: List<String>,
        val targetTime: String,
        val rangeMinutes: Long,
        val symptom: String,
        val tagRegex: String? = null,
    )

    /** prompt 组装（结构化输入 + 症状 + 追问上下文——纯函数直测缝） */
    internal fun buildPrompt(input: AnalysisInput, followUpContext: String?): String = buildString {
        appendLine("日志文件（已授权读取）：")
        input.logFiles.forEach { appendLine("- $it") }
        appendLine("问题时间点：${input.targetTime}")
        appendLine("时间范围（±分钟）：${input.rangeMinutes}")
        appendLine("症状描述：${input.symptom}")
        if (!input.tagRegex.isNullOrBlank()) {
            appendLine("用户指定 Tag 正则（可先反查知识库扩充）：${input.tagRegex}")
        }
        if (followUpContext != null) {
            appendLine()
            appendLine("## 已有报告（增量研判——新结论追加，原结论不改写）")
            appendLine(followUpContext)
        }
    }

    /** 工具集装配（4.2：extended + knowledge + memory + analyze_log + write_todos） */
    private fun buildRegistry() = AgentToolCatalog.buildRegistry(
        names = WORKBENCH_NAMES,
        context = context,
        sink = sink,
        config = AgentToolCatalog.ToolContextConfig(
            privateKeyProvider = privateKeyProvider,
            todoBoard = todoBoard,
            memoryDir = memoryDir,
        ),
        middlewares = emptyList(),
    ).let { base ->
        // write_todos 补挂（DecryptTools.buildToolRegistry 的「裸装配→补挂→装饰」配方——
        // write_todos 不在目录（TodoBoard 是桌面会话件），经 ToolRegistry 直拼）
        com.jiaoay.easy_dev_toolbox.core.llm.agent.decorateRegistry(
            ai.koog.agents.core.tools.ToolRegistry {
                base.tools.forEach { tool(it) }
                tool(com.jiaoay.easy_dev_toolbox.core.llm.tools.WriteTodosTool(todoBoard, sink))
            },
            emptyList(),
        )
    }

    /** 中止（进度保全——已产出的条目/证据/部分结论不清空） */
    fun cancel() {
        runJob?.cancel()
        _running.value = false
    }

    // ── 测试缝（internal——同模块 jvmTest 直测；不扩大生产可见性） ──

    /** gate 请求测试缝（生产路径经 gateBridge 闭包触达） */
    internal suspend fun awaitGateDecisionForTest(path: File): Boolean = awaitGateDecision(path)

    /** 装配测试缝（registry 面直测——不触达 executor/model） */
    internal fun buildRegistryForTest() = buildRegistry()

    /**
     * 测试缝：直置 phaseResult（收集器竞速/收口链直测——生产唯一写点在 startAnalysis
     * 收尾，单测无法驱动真实 LLM 循环）。set 仅测试语义。
     */
    internal fun setPhaseResultForTest(value: String?) {
        _phaseResult.value = value
    }

    /** 重放既有 run 时间线（run 回看入口——replay 后可继续追加追问） */
    fun restore(entries: List<ChatEntry>) {
        runJob?.cancel()
        _entries.value = entries
        _running.value = false
        // 证据文件重导（回看时证据面自时间线工具卡恢复——artifacts chips 即产物路径面）
        _evidenceFiles.value = entries
            .filterIsInstance<ChatEntry.ToolCall>()
            .flatMap { it.artifacts.map { chip -> chip.path } }
            .distinct()
    }

    // ── reducer：Koog 事件 → ChatEntry（ChatSessionReducer 委托——流式打字机开） ──

    internal fun onLoopEvent(event: KoogLoopEvent) = reducer.onLoopEvent(event)

    /**
     * 流式草稿收口 + 终稿消重（reducer 委托）：runJob 收尾统一处理。
     * - 草稿悬挂（流中断无 LlmCallCompleted）：output 空则草稿转正兜底 / 非空丢弃草稿按 output 落；
     * - 终稿消重：末条 assistant 与 output 同文本跳过（singleRunStrategy 下两者恒同文本）。
     */
    internal fun finalizeRun(output: String) = reducer.finalizeRun(output)

    companion object {
        /** fs.extended 取证七件（目录名——与 AgentToolCatalog 描述符一一对齐） */
        private val EXTENDED_FILE_NAMES = setOf(
            "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
            "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
            "fs.extended.convert_timestamp",
        )

        private val KNOWLEDGE_NAMES = KnowledgeTools.KNOWLEDGE_NAMES

        /** L2 记忆四件（目录名——memoryDir null 时 factory 返回 null 不注册） */
        private val MEMORY_NAMES = setOf("memory.view", "memory.append", "memory.replace", "memory.delete")

        private val PIPELINE_NAMES = setOf("log.analyze_log")

        /**
         * log.* 时间过滤两件：filter_by_time = 对称窗口（时间点 ±N 整数分钟）；
         * filter_by_range = 任意起止闭区间（奇数分钟区间等对称窗口表达不了的形状）。
         */
        private val LOG_FILTER_NAMES = setOf("log.filter_by_time", "log.filter_by_range")

        /** 侦察四件（log.stats / log.diff / log.view / log.cluster，READ_ONLY 零产物——研判链「统计→下钻→引用」+ 错误聚类） */
        private val RECON_NAMES = LogStatsDiffTools.NAMES

        /** 网络查询件（web.fetch，READ_ONLY 零文件 I/O——白名单域名网页拉取，[WebTools.WEB_NAMES] 单源） */
        private val WEB_NAMES = WebTools.WEB_NAMES

        /**
         * 工作台研判工具集（4.2）：fs.extended 七件（取证）+ knowledge 四件（历史案例检索
         * + 知识库三件）+ memory 四件（L2 记忆，memoryDir 门控）+ analyze_log 管线（私钥
         * 门控）+ log 时间过滤两件（对称窗口/任意区间）+ 侦察四件 log.stats/log.diff/
         * log.view/log.cluster（分布概览、双日志对比、窗口原文引用、同型异常 top-N
         * 模板聚类）+ web.fetch（陌生错误码/官方文档联网查询）。
         * 名集在本模块自持（BridgeLogTools.BRIDGE_NAMES 先例——:core 的 EXTENDED_NAMES/
         *    MEMORY_NAMES 是 private/internal 跨模块不可见；目录名是稳定契约）。
         * 不入任何 base names——names 显式点名才装，桥接/桌面旧链零影响。
         * write_todos 不在此（经 todoBoard 补挂——目录外桌面会话件）。
         */
        val WORKBENCH_NAMES: Set<String> = EXTENDED_FILE_NAMES +
            KNOWLEDGE_NAMES +
            MEMORY_NAMES +
            PIPELINE_NAMES +
            LOG_FILTER_NAMES +
            RECON_NAMES +
            WEB_NAMES

        /** 整体超时 = 单次调用超时 × 6 轮（万能解密/蒸馏会话先例） */
        const val OVERALL_TIMEOUT_ROUNDS = 6

        /**
         * delta 合并窗时长（64ms——首字零延迟 + 窗内合并到期的打字机节奏；
         * 档位依据 LLM 流式 delta 突发间隔，人眼可感知跳变阈值内）。
         */
        const val STREAMING_DELTA_MERGE_WINDOW_MILLIS = 64L

        // ── 活动门面（宿主 navActivity 消费面——NavActivityFacade 单源委托） ──

        private val facade = NavActivityFacade()

        /** 侧栏活动指示流（宿主经 ToolPageRegistration.navActivity 消费） */
        val activity: StateFlow<com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity> get() = facade.activity

        /** VM attach 镜像（session running → 活动指示） */
        fun attach(session: WorkbenchSession, scope: CoroutineScope) = facade.attach(session.running, scope)

        fun detach() = facade.detach()
    }
}
