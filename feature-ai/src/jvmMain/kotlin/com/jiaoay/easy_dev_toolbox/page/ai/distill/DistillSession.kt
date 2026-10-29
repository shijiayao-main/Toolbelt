package com.jiaoay.easy_dev_toolbox.page.ai.distill

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import com.jiaoay.easy_dev_toolbox.core.data.db.SessionStore
import com.jiaoay.easy_dev_toolbox.core.llm.KoogDistillPrompts
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatSessionReducer
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ErrorKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogLoopEvent
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogToolLoopRequest
import com.jiaoay.easy_dev_toolbox.core.llm.agent.NavActivityFacade
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import com.jiaoay.easy_dev_toolbox.core.llm.agent.koogToolLoop
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.KnowledgeTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolPayload
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResult
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResultSink
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 蒸馏会话核心（两阶段装配；UI 框架无关 reducer，直测缝）。
 *
 * ## 两阶段（Koog 无中途换工具面能力 ⇒ 两次独立 koogToolLoop 调用）
 * - [startExplore]：只读探索（fs.extended 读取族 + knowledge.*，**无 distill.***）→
 *   计划 JSON（repo + plan[{path, reason}] + notes）经 [phaseResult] 交 UI 解析成计划卡；
 * - [startDistill]：执行蒸馏（读取族 + distill.* 两件 + knowledge.*）——**批准的计划清单
 *   经 prompt 显式携带**（计划是用户裁决产物，必须逐字传达，不依赖隐式记忆）。
 *   sessionId 两阶段同值（Koog 按 runId 挂多轮记忆时跨阶段衔接）。
 *
 * ## 与万能解密会话（已删蓝本）的差异
 * - 围栏 = [ToolExecutionContext.withAllowedRoots]（源码目录前缀放行——免逐文件 HITL）；
 * - 产出通道 = [proposalBuffer]（distill.propose_knowledge 暂存候选，不落库）；
 * - 进度面 = [progress]（distill.report_step 旁路 → 结构化进度面板）；
 * - 预算 = LlmConfigStore.getDistillMaxIterations（蒸馏链独立，缺省 60 节点）；
 * - 落盘 = [ledger] 可选（蒸馏 run 历史回看）。
 *
 * 纪律：CE 原样上抛（cancel 传播）；TimeoutCE 分类 TIMEOUT（CE 分支之前）；不吞异常。
 */
class DistillSession(
    private val scope: CoroutineScope,
    private val configProvider: suspend () -> LlmEndpointConfig,
    private val executorProvider: suspend (LlmEndpointConfig) -> MultiLLMPromptExecutor,
    private val modelProvider: suspend (LlmEndpointConfig) -> LLModel,
    /** 源码根目录提供缝（缺省 LlmConfigStore 现读——任务粒度快照，热生效） */
    private val sourceRootsProvider: suspend () -> List<String> = { LlmConfigStore.getSourceRoots() },
    private val ledger: SessionStore? = null,
) {

    /** 运行态（侧栏活动指示/输入禁用判据） */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 事件流（ChatEntry reducer 面——运行面板工具调用卡；单一真源，reducer 与本类共操作） */
    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()

    /** 进度流（distill.report_step 旁路面——结构化进度面板数据源） */
    private val _progress = MutableStateFlow<DistillProgressState?>(null)
    val progress: StateFlow<DistillProgressState?> = _progress.asStateFlow()

    /** 候选暂存集（distill.propose_knowledge 落点——审阅卡唯一数据源） */
    val proposalBuffer = DistillTools.ProposalBuffer()

    /** 阶段完成产物（探索 = 计划 JSON / 执行 = 收尾总结文本） */
    private val _phaseResult = MutableStateFlow<String?>(null)
    val phaseResult: StateFlow<String?> = _phaseResult.asStateFlow()

    /**
     * 阶段失败信号（超时/异常 catch 置位——VM watcher 消费后置 null）。🔴 失败也是
     * 终态：只置 running=false 会让运行相位（EXPLORING/DISTILLING）楔死——UI 出口钮
     * 随 running 消失且无任何相位出口；此信号保证失败必达 VM 收口（切回 FORM/REVIEW）。
     */
    private val _phaseFailure = MutableStateFlow<String?>(null)
    val phaseFailure: StateFlow<String?> = _phaseFailure.asStateFlow()

    /** 失败信号消费（VM 切相后调用——防新 watcher 启动时陈值重放） */
    fun consumePhaseFailure() {
        _phaseFailure.value = null
    }

    /**
     * 测试缝：直置 phaseResult（VM watcher 相位收口直测——生产唯一写点在 runPhase
     * 收尾，单测无法驱动真实 LLM 循环）。set 仅测试语义。
     */
    internal fun setPhaseResultForTest(value: String?) {
        _phaseResult.value = value
    }

    /** 会话 job（中止 = cancel——进度/候选保全） */
    private var job: Job? = null

    /** 会话 id（两阶段同值——记忆衔接/落盘 key） */
    private val sessionId: String = "distill-${java.util.UUID.randomUUID()}"

    /** reducer 引擎（ChatSessionReducer 委托——本类只保留蒸馏独有面） */
    private val reducer = ChatSessionReducer(
        entries = _entries,
        ledger = ledger,
        ledgerKey = sessionId,
        streamingEnabled = false, // 蒸馏链不开流式面（进度走 report_step 结构化旁路）
    )

    /** sink 映射：distill.report_step 旁路负载 → 进度快照（候选走 buffer，sink 不重处理） */
    private val sink = ToolResultSink.concurrentOf { _, result ->
        if (result is ToolResult.Ok) {
            val payload = result.data as? ToolPayload.DistillProgressPayload ?: return@concurrentOf
            _progress.value = DistillProgressState(
                subject = payload.subject,
                activeForm = payload.activeForm,
                stage = payload.stage,
                progressHint = payload.progressHint,
            )
        }
    }

    // ── 探索阶段（只读——产计划卡，不产知识不写文件） ──

    fun startExplore(featureDesc: String, entryHints: List<String>) {
        if (_running.value) return
        resetForPhase()
        _running.value = true
        val prompt = buildString {
            appendLine("功能模块描述：$featureDesc")
            appendLine(
                if (entryHints.isEmpty()) "入口提示：（空——请自行从功能描述定位入口）"
                else "入口提示：${entryHints.joinToString("、")}",
            )
        }
        job = scope.launch { runPhase(EXPLORE_PHASE, prompt, withDistillTools = false) }
    }

    // ── 执行阶段（计划经 prompt 显式携带——用户裁决产物逐字传达） ──

    fun startDistill(approval: ApprovedPlan) {
        if (_running.value) return
        proposalBuffer.reset()
        _progress.value = null
        _phaseResult.value = null
        _phaseFailure.value = null
        _running.value = true
        val planText = approval.plan.joinToString("\n") { "  - ${it.path}（${it.reason}）" }
        val prompt = buildString {
            appendLine("功能模块描述：${approval.featureDesc}")
            if (approval.entryHints.isNotEmpty()) {
                appendLine("入口提示：${approval.entryHints.joinToString("、")}")
            }
            appendLine("用户已批准的文件计划（按此清单执行，不要扩展到清单外文件）：")
            append(planText)
        }
        job = scope.launch { runPhase(DISTILL_PHASE, prompt, withDistillTools = true) }
    }

    /** 单阶段执行体（两阶段共用：配置快照 → 装配 → koogToolLoop → 纪律收敛） */
    private suspend fun runPhase(phase: String, userPrompt: String, withDistillTools: Boolean) {
        // 配置快照/源码根/工具装配失败同样必达失败信号：前置求值异常若漏出，本函数
        // 无 catch 兜底——_running 恒 true 且 phaseFailure 不置位，运行相位双重楔死
        //（出口钮随 running 消失 + watcher 无信号可收）
        val prepared = runCatching {
            val config = configProvider()
            val roots = sourceRootsProvider().map { File(it) }
            val context = ToolExecutionContext.withAllowedRoots(roots)
            val names = buildSet {
                addAll(EXTENDED_FILE_NAMES)
                addAll(KnowledgeTools.KNOWLEDGE_NAMES)
                if (withDistillTools) addAll(DistillTools.DISTILL_NAMES)
            }
            val registry = AgentToolCatalog.buildRegistry(
                names = names,
                context = context,
                sink = sink,
                config = AgentToolCatalog.ToolContextConfig(
                    distillBuffer = if (withDistillTools) proposalBuffer else null,
                ),
            )
            config to registry
        }
        val (config, registry) = prepared.getOrElse { e ->
            if (e is CancellationException) throw e
            val reason = e.message?.take(400) ?: e::class.simpleName ?: "未知错误"
            reducer.appendError(ErrorKind.FAILED, "蒸馏阶段失败", reason)
            _phaseFailure.value = "$phase 阶段装配失败：$reason"
            _running.value = false
            return
        }
        try {
            val request = KoogToolLoopRequest(
                prompt = userPrompt,
                tools = registry,
                systemPrompt = if (withDistillTools) {
                    KoogDistillPrompts.DISTILL_PROMPT
                } else {
                    KoogDistillPrompts.EXPLORE_PROMPT
                },
                strategy = null, // 单链官方 singleRunStrategy（骨架回落）
                maxIterations = LlmConfigStore.getDistillMaxIterations(),
                overallTimeoutMillis = config.timeoutSeconds * 1000L * OVERALL_TIMEOUT_ROUNDS,
                sessionId = sessionId,
                onEvent = { event -> onLoopEvent(event) },
            )
            val output = koogToolLoop(
                executor = executorProvider(config),
                llmModel = modelProvider(config),
                request = request,
            )
            _phaseResult.value = output
            _running.value = false
        } catch (e: TimeoutCancellationException) {
            reducer.appendError(ErrorKind.TIMEOUT, "整体超时（$phase 阶段）", "预算 ${config.timeoutSeconds}s × $OVERALL_TIMEOUT_ROUNDS 轮")
            _phaseFailure.value = "整体超时（$phase 阶段，预算 ${config.timeoutSeconds}s × $OVERALL_TIMEOUT_ROUNDS 轮）"
            _running.value = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message?.take(400) ?: e::class.simpleName ?: "未知错误"
            reducer.appendError(ErrorKind.FAILED, "蒸馏阶段失败", reason)
            _phaseFailure.value = "$phase 阶段执行失败：$reason"
            _running.value = false
        }
    }

    // ── reducer：Koog 事件 → ChatEntry（ChatSessionReducer 委托——流式面关） ──

    internal fun onLoopEvent(event: KoogLoopEvent) = reducer.onLoopEvent(event)

    // ── 条目操作（reducer 内收口——落盘桥稳态条目语义） ──

    /** 中止（进度/候选保全——已上报内容不清空） */
    fun cancel() {
        job?.cancel()
        _running.value = false
    }

    private fun resetForPhase() {
        proposalBuffer.reset()
        reducer.clear()
        _progress.value = null
        _phaseResult.value = null
        _phaseFailure.value = null
    }

    /** 计划卡批准形态（startDistill 入参——探索产物 + 用户增删勾改后的结果面） */
    data class ApprovedPlan(
        val featureDesc: String,
        val entryHints: List<String>,
        val plan: List<PlanItem>,
    )

    /** 计划卡条目（探索产物——用户增删勾改的原子单位） */
    data class PlanItem(val path: String, val reason: String)

    /** 进度面板快照（UI 状态形态——report_step 旁路投影；上游负载无百分比字段，不设 percent 槽） */
    data class DistillProgressState(
        val subject: String,
        val activeForm: String,
        val stage: String,
        val progressHint: String,
    )

    companion object {
        /** fs.extended 读取族名集（两阶段工具面——read/grep/glob 三件只读件；取证件不需要） */
        val EXTENDED_FILE_NAMES = setOf(
            "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
        )

        const val EXPLORE_PHASE = "explore"
        const val DISTILL_PHASE = "distill"

        /** 整体超时 = 单次调用超时 × 6 轮（万能解密会话先例 OVERALL_TIMEOUT_ROUNDS=6） */
        const val OVERALL_TIMEOUT_ROUNDS = 6

        // ── 活动门面（宿主 navActivity 消费面——AiChatHub 模式） ──

        private val activitySink = MutableStateFlow(com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Idle)
        private var mirrorJob: Job? = null

        /** 侧栏活动指示流（宿主经 ToolPageRegistration.navActivity 消费） */
        val activity: StateFlow<com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity> =
            activitySink.asStateFlow()

        /** VM attach 镜像（session running → 活动指示） */
        fun attach(session: DistillSession, scope: CoroutineScope) {
            detach()
            mirrorJob = scope.launch {
                session.running.collect { running ->
                    activitySink.value = if (running) {
                        com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Running
                    } else {
                        com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Idle
                    }
                }
            }
        }

        fun detach() {
            mirrorJob?.cancel()
            mirrorJob = null
            activitySink.value = com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Idle
        }
    }
}
