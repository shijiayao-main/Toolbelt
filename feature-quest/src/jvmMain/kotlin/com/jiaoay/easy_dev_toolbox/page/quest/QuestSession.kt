package com.jiaoay.easy_dev_toolbox.page.quest

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import com.jiaoay.easy_dev_toolbox.core.data.db.SessionStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChip
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChipKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatSessionReducer
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ErrorKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogLoopEvent
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogToolLoopRequest
import com.jiaoay.easy_dev_toolbox.core.llm.agent.NavActivityFacade
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import com.jiaoay.easy_dev_toolbox.core.llm.agent.koogToolLoopWithMemory
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskAnswer
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskCaps
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskRequest
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskUserTool
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolPayload
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResult
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResultSink
import com.jiaoay.easy_dev_toolbox.core.llm.tools.UserQuestionGate
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 流程会话核心（Quest 域 change 2——openspec add-flow-page 任务 2.1/2.2/2.3；UI 框架无关
 * reducer，直测缝）。
 *
 * ## 会话形态（WorkbenchSession 五件套骨架平移 + ChatMemory 续跑）
 * - **每轮 send = 独立 koogToolLoopWithMemory（sessionId=flowId）**：预算按轮重置，
 *   超时只死一轮不死整个流程；跨轮上下文经 ChatMemory（QuestHistoryProvider——
 *   store 滤 System + 窗口封顶 + SqliteChatHistoryProvider 写穿透）；
 * - **ask 桥**（UserQuestionGate 实现）：`user.ask_user` 工具挂起 → pendingQuestion 流
 *   （AskCard UI 数据源）→ resolve/skip/软超时（[AskCaps.resolveAskCapMillis] ——
 *   软超时永远先于 koogToolLoop 硬超时触发）；
 * - 🔴 **cancel 清 pendingQuestion**（补工作台既有瑕疵）+ 尾随 RUNNING 工具卡翻
 *   CANCELLED 态——取消后表单不残留；
 * - 工具集：QUEST_NAMES 基础面（change 3 接能力名集）+ user.ask_user 恒注册
 *   （gate 非 null 门控）；
 * - 纪律：CE 原样上抛；TimeoutCE 分类 TIMEOUT；不吞异常。
 *
 * @param ledger 时间线落盘（sessionId=flowId——messages 表三层同 key 之一）
 * @param extraToolNames 能力工具名集（change 3 注入；首版通用对话传空集）
 */
class QuestSession(
    private val scope: CoroutineScope,
    private val flowId: String,
    private val configProvider: suspend () -> LlmEndpointConfig,
    private val executorProvider: suspend (LlmEndpointConfig) -> MultiLLMPromptExecutor,
    private val modelProvider: suspend (LlmEndpointConfig) -> LLModel,
    private val historyProvider: QuestHistoryProvider,
    private val ledger: SessionStore? = null,
    private val extraToolNames: Set<String> = emptySet(),
    /** 私钥提供缝（log.analyze_log 管线解密级需要；null = 不装 analyze_log——纯明文分析） */
    private val privateKeyProvider: (suspend () -> String)? = null,
    /** L2 记忆目录（null = 不装 memory.*——与桌面链 memoryDir 配置同源） */
    private val memoryDir: File? = null,
    /** ask 软超时预算（毫秒）。须 ≤ 整体预算 0.8（AskCaps.resolveAskCapMillis
     *  换算产物——软超时先于整轮硬超时触发）。构造注入而非 send 时 runBlocking 现读——runTest 虚拟时钟下
     *  runBlocking 阻死挂起路径（测试期实测坑）；VM 装配期一次算好。 */
    private val askCapMillis: Long = AskCaps.resolveAskCapMillis(60 * 1000L * OVERALL_TIMEOUT_ROUNDS),
) {

    /** gate 桥：围栏外路径请求 → pendingGate 流（UI 确认卡）→ 挂起等用户裁决 */
    private val gateBridge = ToolExecutionContext.PathEscapeGate { path -> awaitGateDecision(path) }

    /** 会话围栏：空初始集 + escapeGate（附件渐进 register——拖入即授权） */
    private val context = ToolExecutionContext(escapeGate = gateBridge)

    /** 运行态（输入禁用判据/侧栏活动） */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 事件流（ChatEntry reducer 面——时间线数据源；单一真源，reducer 与本类共操作） */
    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries.asStateFlow()

    /** reducer 引擎（ChatSessionReducer 委托——本类只保留 Quest 独有面） */
    private val reducer = ChatSessionReducer(
        entries = _entries,
        ledger = ledger,
        ledgerKey = flowId,
        streamingEnabled = true, // 流程会话开流式面
    )

    /** ask 桥状态：当前挂起的提问（AskCard 渲染面；null = 无提问） */
    private val _pendingQuestion = MutableStateFlow<AskRequest?>(null)
    val pendingQuestion: StateFlow<AskRequest?> = _pendingQuestion.asStateFlow()

    /** gate 桥状态：当前待裁决的围栏外路径（PathConfirmCard 渲染面；null = 无请求） */
    private val _pendingGate = MutableStateFlow<File?>(null)
    val pendingGate: StateFlow<File?> = _pendingGate.asStateFlow()

    /** S5d · 会话证据文件面（工具产物累积——chips 深链跳工作台的路径真源） */
    private val _evidenceFiles = MutableStateFlow<List<String>>(emptyList())
    val evidenceFiles: StateFlow<List<String>> = _evidenceFiles.asStateFlow()

    /** S5c · Steer 排队面：running 期到达的插话（收尾 drain 续发——轮边界排队即设计本意） */
    private val _pendingQueue = MutableStateFlow<List<String>>(emptyList())
    val pendingQueue: StateFlow<List<String>> = _pendingQueue.asStateFlow()

    private var runJob: Job? = null
    private var pendingAnswer: CompletableDeferred<AskAnswer>? = null
    private val seq = AtomicLong(0)

    /**
     * sink：AskRequest 旁路 → pendingQuestion 流（表单卡数据源）；
     * S5d 起叠加 artifacts 精确物化（reducer 单源配方——产物 chips 挂末尾同名工具卡 + 证据文件集累积）。
     */
    private val sink = ToolResultSink.concurrentOf { name, result ->
        if (result is ToolResult.Ok) {
            val payload = result.data
            if (payload is ToolPayload.AskRequestPayload) {
                _pendingQuestion.value = payload.request
            } else if (result.artifacts.isNotEmpty()) {
                val chips = result.artifacts.map { a ->
                    ArtifactChip(a.path, ArtifactChipKind.valueOf(a.kind.name))
                }
                _evidenceFiles.update { cur -> (cur + result.artifacts.map { it.path }).distinct() }
                reducer.attachArtifactsToLastToolCall(name, chips)
            }
        }
    }

    /** S5d · sink 测试缝（emitSinkForTest——物化用例直测缝；生产路径经工具执行旁路触达） */
    internal fun emitSinkForTest(name: String, result: ToolResult) {
        sink.onResult(name, result)
    }

    /** ask gate（UserQuestionGate 实现——软超时在桥内，AskUserTool 分流超时文案） */
    private val askGate = UserQuestionGate { request ->
        val deferred = CompletableDeferred<AskAnswer>()
        pendingAnswer = deferred
        _pendingQuestion.value = request
        try {
            withTimeoutOrNull(askCapMillis) { deferred.await() }
                ?: AskAnswer(emptyMap(), skipped = true) // 软超时：用户未答——agent 收软文案继续
        } finally {
            _pendingQuestion.value = null
            pendingAnswer = null
        }
    }

    // ── ask 桥 UI 动作面 ──

    /** 测试缝：直接触达 ask gate（生产路径经 user.ask_user 工具）。 */
    internal suspend fun onAskForTest(request: AskRequest): AskAnswer = askGate.onAsk(request)

    /** 用户提交表单（values 按字段 id 索引；无字段自由文本用 [AskUserTool.PROMPT_ANSWER_KEY]）。 */
    fun resolveAsk(values: Map<String, String>) {
        pendingAnswer?.complete(AskAnswer(values, skipped = false))
    }

    /** 用户跳过（「暂无补充」钮——AskUserTool 回软文案）。 */
    fun skipAsk() {
        pendingAnswer?.complete(AskAnswer(emptyMap(), skipped = true))
    }

    // ── gate 桥（WorkbenchSession 配方平移——围栏外路径确认） ──

    private var pendingGateDecision: CompletableDeferred<Boolean>? = null

    private suspend fun awaitGateDecision(path: File): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingGateDecision = deferred
        _pendingGate.value = path
        try {
            return deferred.await()
        } finally {
            _pendingGate.value = null
            pendingGateDecision = null
        }
    }

    /** 用户批准围栏外路径访问（路径随后由工具侧登记进允许集——本会话后续免确认）。 */
    fun approveGate() {
        pendingGateDecision?.complete(true)
        pendingGateDecision = null
        _pendingGate.value = null
    }

    /** 用户拒绝（工具收 DENIED 软反馈——agent 继续而不崩）。 */
    fun denyGate() {
        pendingGateDecision?.complete(false)
        pendingGateDecision = null
        _pendingGate.value = null
    }

    /** gate 请求测试缝（生产路径经 gateBridge 闭包触达——WorkbenchSession 同款）。 */
    internal suspend fun awaitGateDecisionForTest(path: File): Boolean = awaitGateDecision(path)

    // ── 附件登记 ──

    /**
     * 附件到达：登记进围栏（渐进 register——拖入即授权）。
     * S4 · FileBuffer 双挂：进围栏即出暂存（「拖进会话才授权」——授权面与
     * 待安置面对偶收口，WorkbenchSession 同款）。
     */
    fun registerFiles(files: List<java.io.File>) {
        files.forEach { context.register(it) }
        com.jiaoay.easy_dev_toolbox.core.buffer.FileBuffer.removeAll(files.map { it.absolutePath })
    }

    // ── send 驱动（每轮独立 run） ──

    /**
     * 发起一轮对话（prompt = 用户输入 + 开工纪律；ChatMemory 经 sessionId 携带历史）。
     *
     * S5c · Steer 排队：running 期到达的插话不再静默丢弃——入 [pendingQueue]，收尾
     * drain 自动续发（轮边界排队即概念稿设计本意；ChatMemory 保证上下文连续）。
     * ask_user 挂起期排队消息合并进表单回答的特例一期不做（二期经 executor
     *    装饰器 interceptModelCall 真插话时一并处理——AgentToolMiddleware 注释已记）。
     *
     * @param userMessage 用户本轮输入（首条 = 目标描述；后续 = 回答补充/追问）
     * @param firstRound true = 首轮（追加开工纪律——缺关键信息先用 ask_user 集中问齐）
     * @param promptSection 能力 prompt 段（change 3 注入；空 = 通用对话）
     * @return false = 队满被拒（不入队；调用方回执「队列已满」）
     */
    fun send(userMessage: String, firstRound: Boolean, promptSection: String = ""): Boolean {
        if (_running.value) {
            if (_pendingQueue.value.size >= PENDING_QUEUE_MAX) {
                return false
            }
            _pendingQueue.update { it + userMessage }
            return true
        }
        startRound(userMessage, firstRound, promptSection)
        return true
    }

    /**
     * 本轮启动（无 running 检查——[send] 排队分支与 [settleRound] drain 续发的
     * 共同内层；running 语义 =「会话有无在跑的轮」，收尾续发时保持 true 过渡）。
     */
    private fun startRound(userMessage: String, firstRound: Boolean, promptSection: String) {
        _running.value = true
        reducer.appendUser(userMessage)
        runJob = scope.launch {
            val config = configProvider()
            val registry = AgentToolCatalog.buildRegistry(
                names = extraToolNames + setOf(AskUserTool.NAME),
                context = context,
                sink = sink,
                config = AgentToolCatalog.ToolContextConfig(
                    userQuestionGate = askGate,
                    privateKeyProvider = privateKeyProvider,
                    memoryDir = memoryDir,
                ),
            )
            try {
                val output = koogToolLoopWithMemory(
                    executor = executorProvider(config),
                    llmModel = modelProvider(config),
                    request = KoogToolLoopRequest(
                        prompt = buildPrompt(userMessage, firstRound, promptSection),
                        tools = registry,
                        systemPrompt = QuestPrompts.SYSTEM_PROMPT,
                        strategy = null,
                        maxIterations = maxIterations(),
                        overallTimeoutMillis = config.timeoutSeconds * 1000L * OVERALL_TIMEOUT_ROUNDS,
                        sessionId = flowId,
                        onEvent = { onLoopEvent(it) },
                    ),
                ) {
                    install(ai.koog.agents.chatMemory.feature.ChatMemory.Feature) {
                        chatHistoryProvider = historyProvider
                        windowSize(QuestHistoryProvider.WINDOW_SIZE)
                    }
                }
                finalizeRun(output)
                settleRound()
            } catch (e: TimeoutCancellationException) {
                reducer.appendError(ErrorKind.TIMEOUT, "本轮超时", "预算 ${config.timeoutSeconds}s × $OVERALL_TIMEOUT_ROUNDS 轮（下一条消息重新开始，历史不丢）")
                settleRound()
            } catch (e: CancellationException) {
                // 用户中止：不 drain（取消语义 = 清队，见 cancel()——CE rethrow 恒先于 drain）
                throw e
            } catch (e: Exception) {
                reducer.appendError(ErrorKind.FAILED, "本轮失败", e.message?.take(400) ?: e::class.simpleName)
                settleRound()
            }
        }
    }

    /**
     * S5c · 收尾（三个正常收尾点统一路径：成功/超时/失败——CE 分支不收尾）。
     *
     * 🔴 队列非空时**不落 running=false**（保持 true 无缝过渡到续发轮）：
     * 完成通知 collector（FlowViewModel）挂在 running 下降沿 + 页面不可见上——若收尾
     * 先落下降沿再 drain 置回 true，切走 tab 的用户会收到「流程已完成」伪通知，而
     * 插话轮随即开跑（dock 徽标仍亮）。下降沿只在**队列真正跑空**的收尾点出现一次。
     */
    private fun settleRound() {
        val next = _pendingQueue.value.firstOrNull() ?: run {
            _running.value = false
            return
        }
        _pendingQueue.value = _pendingQueue.value.drop(1)
        // 🔴 能力 prompt 段不再重拼：首轮已随消息进 ChatMemory 历史，重发会在历史里
        //    重复注入（对齐 FlowViewModel.sendMessage 追问路径的既有纪律——续轮恒空段）。
        startRound(next, firstRound = false, promptSection = "")
    }

    /** prompt 组装（用户输入 + 首轮开工纪律——纯函数直测缝）。 */
    internal fun buildPrompt(userMessage: String, firstRound: Boolean, promptSection: String): String = buildString {
        if (promptSection.isNotBlank()) {
            appendLine(promptSection)
            appendLine()
        }
        append(userMessage)
        if (firstRound) {
            appendLine()
            appendLine()
            appendLine("## 开工纪律")
            appendLine("- 若关键信息缺失，先用 user.ask_user 一次集中问齐（≤8 字段），拿到回答再动工")
            appendLine("- 执行中仅在真正缺料阻塞时补问，禁反复追问同一信息")
            appendLine("- 信息齐全直接开工，不必为了问而问")
        }
    }

    private suspend fun maxIterations(): Int = com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore.getFlowMaxIterations()

    /** 中止本轮（🔴 清 pendingQuestion + pendingGate——取消后表单/确认卡不残留；进度保全：已产出条目不清）。
     * S5c：🔴 先清 pendingQueue 再 cancel runJob（顺序钉死进测试）——取消 = 弃插话；
     * cancel 后收尾 drain 不再触发（CE 分支本不 drain，清队是取消语义的双保险）。 */
    fun cancel() {
        _pendingQueue.value = emptyList()
        pendingAnswer?.cancel()
        pendingAnswer = null
        _pendingQuestion.value = null
        pendingGateDecision?.cancel()
        pendingGateDecision = null
        _pendingGate.value = null
        runJob?.cancel()
        _running.value = false
        // 尾随 RUNNING 工具卡翻 CANCELLED（廉价 sweep——悬挂态视觉收口）
        _entries.update { list ->
            list.map { entry ->
                if (entry is ChatEntry.ToolCall && entry.state == ToolState.RUNNING) {
                    entry.copy(state = ToolState.CANCELLED, observation = "已中止")
                } else {
                    entry
                }
            }
        }
    }

    /** 历史进入：恢复 UI 时间线（messages replay——ChatMemory 层由 sessionId 自动挂接）。
     * S5d：证据文件重导（WorkbenchSession 配方平移——自时间线工具卡 artifacts chips 恢复证据面）。 */
    fun restore(entries: List<ChatEntry>) {
        runJob?.cancel()
        _entries.value = entries
        _running.value = false
        _evidenceFiles.value = entries
            .filterIsInstance<ChatEntry.ToolCall>()
            .flatMap { it.artifacts.map { chip -> chip.path } }
            .distinct()
    }

    // ── reducer（ChatSessionReducer 委托——StreamingFrame 流式开） ──

    internal fun onLoopEvent(event: KoogLoopEvent) = reducer.onLoopEvent(event)

    /** 流式草稿收口 + 终稿消重（reducer 委托）。 */
    internal fun finalizeRun(output: String) = reducer.finalizeRun(output)

    companion object {
        /** 整体超时 = 单次调用超时 × 3 轮（交互轮短预算——超时只死一轮；工作台是 ×6 整场） */
        const val OVERALL_TIMEOUT_ROUNDS = 3

        /**
         * Steer 排队容量上限（running 期 Enter 连环误触无闸——每条续发一轮
         * LLM 调用，失控面须封顶；量级对齐 [com.jiaoay.easy_dev_toolbox.core.theme.Dimens.listDomainMax]
         * 会话域 30，语义独立常量不借 UI token——队列容量非视觉尺寸）。
         */
        const val PENDING_QUEUE_MAX = 30

        // ── 活动门面（S5a dock RUN 徽标——NavActivityFacade 单源委托） ──

        private val facade = NavActivityFacade()

        /** 会话活动指示流（宿主经 ToolPageRegistration.navActivity 消费——dock 圆点/状态栏自动点亮） */
        val activity: StateFlow<NavActivity> get() = facade.activity

        /** VM attach 镜像（session running → 活动指示；切会话先 detach 收旧镜像） */
        fun attach(session: QuestSession, scope: CoroutineScope) = facade.attach(session.running, scope)

        fun detach() = facade.detach()
    }
}
