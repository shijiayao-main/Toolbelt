package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import com.jiaoay.lark.bridge.agent.AgentRequest
import com.jiaoay.lark.bridge.agent.AgentRunner
import com.jiaoay.lark.bridge.agent.AgentRunResult
import com.jiaoay.lark.bridge.agent.ProgressAccumulator
import com.jiaoay.lark.bridge.agent.ProgressEvent
import com.jiaoay.lark.bridge.agent.RunStatus
import com.jiaoay.lark.bridge.pipeline.TaskErrorCodes
import com.jiaoay.lark.bridge.exec.AgentLine
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.KoogAgentPromptPresets
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogLoopEvent
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogToolLoopRequest
import com.jiaoay.easy_dev_toolbox.core.llm.agent.koogToolLoopWithMemory
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地 Koog 调度 agent。
 *
 * ## 定位：调度层，不是「又一个 CLI 后端」
 * `agent.backend == "koog"` 时生效——用户消息**先过固定路由**（SYSTEM/REPLY/COMMAND/AGENT/FILE
 * 数组序优先），**未命中的消息**由本 agent 智能判断：
 * - 轻量问答 ⇒ `reply_direct` 工具直接答复（不转发 CLI）；
 * - 需要过程产物 ⇒ `send_file` / `send_card` 工具经本消息的 [ReplySink] 回传（CLI agent 无法支持
 *   「用户想要过程文件」的场景正是本层的价值）；
 * - 重任务 ⇒ `call_cli_agent` 工具转发 CLI 后端（claude/qoder/copilot/zcode/custom）——显著减少更重的
 *   CLI agent 任务执行。
 *
 * ## 多轮上下文
 * Koog 1.2.0 官方 `agents-features-memory` 的 `install(ChatMemory.Feature)` + `AIAgent.run(input, sessionId)`
 * ——**sessionId 即 runId**（AIAgentBase.kt:72），把桥接域 `AgentRequest.sessionKey`（= chatId +
 * thread 语义，话题隔离自动继承）透传即完成多轮挂接。
 *
 * 🔴 两个官方实现的坑（读 koog 源码核实）由 [SessionHistoryProvider] 规避：
 * 1. `ChatMemory.store` 存**整个 prompt**（含 system）且 load 非空历史时**整体替换**——system 会
 *    重复注入。本 provider store 时滤掉 System 消息；
 * 2. `InMemoryChatHistoryProvider` 无会话容量上限——本 provider 自带 LRU（对齐桥接域
 *    `agent.session.capacity` = 64 语义）。
 *
 * ## 纪律
 * - CE（CancellationException）原样上抛（「停止」/超时取消传播，同 DecryptAgent 纪律）；
 * - 失败归一为 [AgentRunResult]（errorCode 取 lark-bridge TaskErrorCodes 全集之一），不抛业务异常；
 * - 进度事件喂 [ProgressAccumulator]（onLLMCallStarting → Thinking、
 *   onToolCallStarting/Completed/Failed → ToolUse/ToolResult、onLLMCallCompleted → AssistantText——
 *   🔴 **progress 经 5 参 run 参数链贯穿**，勿读 request.opaque 取 ProgressAccumulator
 *   （opaque 专职携带 ReplySink，as? 取 Progress 恒 null）；
 */
class KoogAgentRunner(
    /** 会话历史容量（LRU 上限；对齐桥接域 SessionConfig.capacity 语义）。 */
    private val sessionCapacity: Int = DEFAULT_SESSION_CAPACITY,
    /** 单会话保留的最大消息条数（windowSize 语义：防长对话 prompt 无限膨胀）。 */
    private val historyWindowSize: Int = DEFAULT_HISTORY_WINDOW,
    private val persistedHistoryProvider: PersistedHistory? = null,
    private val maxIterationsProvider: () -> Int = { DEFAULT_MAX_ITERATIONS },
    private val sandboxProvider: () -> com.jiaoay.lark.bridge.exec.WorkspacePolicy? = { null },
    private val artifactsEnabledProvider: () -> Boolean = { true },
    private val homeDataRootProvider: () -> java.io.File? = { null },
    private val nativeToolsProvider: () -> com.jiaoay.lark.bridge.config.BridgeConfig? = { null },
    /**
     * 会话内提醒 job 面（remind_user 的延时承载）：宿主注入绑定网关 scope 的实现；
     * null = 未注入（CLI 形态/存量测试）⇒ remind_user 不注册（条件注册）。
     */
    private val reminderSchedulerProvider: () -> ReminderScheduler? = { null },
    /**
     * 信任用户名单提供者（`access.trustedUsers` 现读）——sys.* / dev.* / ws.* 三域共用的
     * 唯一授权判据。null = 未接线 ⇒ 三域全件不注册（fail-closed）。
     */
    private val trustedUsersProvider: (() -> List<String>)? = null,
    /**
     * dev.* 设备源缝（设备检测单源适配 [AppDeviceSource]；null = 未注入（CLI 形态/
     * 存量测试）⇒ dev.* 全件不注册，fail-closed 三条件之一）。
     */
    private val devDeviceSource: com.jiaoay.easy_dev_toolbox.core.llm.tools.DevTools.DeviceSource? = null,

    /**
     * self.status 状态源缝（桥接内部健康数据面适配 [AppSelfStatusSource]；null = 未注入
     * （CLI 形态/存量测试/桌面链缺省）⇒ self.status 不注册，fail-closed 三条件之一）。
     */
    private val selfStatusSource: com.jiaoay.easy_dev_toolbox.core.llm.tools.SelfStatusTools.SelfStatusSource? = null,

    /**
     * user.ask_user 挂起配对总线 provider（宿主 Bridge 稳定件透出——runner 构造早于 Bridge
     * 装配，恒 provider 形态每次 run 现取）。null 返回 = 未装配 ⇒ ask_user 不注册
     * （fail-closed——挂起等一个无配对面的回答是死等）。
     */
    private val askUserBusProvider: () -> com.jiaoay.lark.bridge.confirm.AskUserBus? = { null },

    /**
     * 端点就绪态变更回调（[refreshEndpoint] 写入端点快照后同步发射；false = 快照缺失或未配置）。
     * 契约：回调是端点就绪态在全类的唯一发射口——宿主据此维护就绪订阅流，订阅方零挂起
     * 即时反映端点配置变更，与 [isAvailable] 同源同拍；DataStore 读失败上抛时回调不发射
     * （订阅方沿用上次快照语义，与刷新回路的容错口径一致）。
     */
    private val onEndpointReadyChanged: (Boolean) -> Unit = {},
    internal val executorFactory: (LlmEndpointConfig) -> Pair<MultiLLMPromptExecutor, LLModel> = { cfg ->
        LlmClientFactory.executor(cfg) to LlmClientFactory.model(cfg.modelId)
    },
) : AgentRunner {

    override val backendId: String = BACKEND_ID

    /** 端点配置快照缓存（[refreshEndpoint] 由装配点在 isAvailable 判定前刷新）。 */
    private val endpointRef = AtomicReference<LlmEndpointConfig?>(null)

    private val promptConfigRef = AtomicReference(KoogPromptConfig())

    suspend fun refreshEndpoint() {
        endpointRef.set(LlmEndpointConfig.read()) // S4 单点构造（原四行样板）
        // 就绪态随快照写入同步发射（快照单写点 = 就绪态单发射点，两态永不分叉）
        onEndpointReadyChanged(endpointRef.get()?.isConfigured == true)
        promptConfigRef.set(
            KoogPromptConfig(
                styleId = LlmConfigStore.getAgentStyleId(),
                styleCustom = LlmConfigStore.getAgentStyleCustom(),
                // per-user 映射：openId → 内置档 id——快照存原始 id，
                // systemPromptFor 内按 senderOpenId 现解析（custom 档过滤回落全局）。
                userStyles = LlmConfigStore.getAgentStyleUserMap()
                    .associate { it.openId to it.styleId },
                safetyPrompt = KoogAgentPromptPresets.safetyPrompt(
                    LlmConfigStore.getAgentSafetyId(),
                    LlmConfigStore.getAgentSafetyCustom(),
                ),
            ),
        )
    }

    /** 会话历史表（LRU 语义自管：[SessionHistoryProvider]；带持久层写穿透）。 */
    private val historyProvider = SessionHistoryProvider(
        capacity = sessionCapacity,
        windowSize = historyWindowSize,
        persistStore = persistedHistoryProvider,
    )

    override val isAvailable: Boolean
        get() = endpointRef.get()?.isConfigured == true

    override suspend fun run(request: AgentRequest, onLine: (AgentLine) -> Unit): AgentRunResult =
        run(request, chatId = "", senderOpenId = "", progress = null, onLine = onLine)

    /**
     * 「新对话」重置：清当轮会话历史（[SessionHistoryProvider.reset]）。
     * 长期记忆不在重置面：memory.* 绑定 chatId 跨 agent run 长存（派生口径见
     * [BridgeToolRegistry.chatMemoryDir]）——用户下次发消息时 agent 仍记得既有背景，
     * 正是记忆的价值所在；要清记忆须显式让 agent 调 delete_memory。
     * 工作区文件/下载文件同样不在重置面（回执文案在 AgentHandler 如实披露）。
     */
    override suspend fun resetSession(sessionKey: String): Boolean =
        historyProvider.reset(sessionKey)

    /**
     * 聚合传导目标的详细回执：Koog 本侧历史重置无详细回执面——恒 null，
     * AgentHandler 维持既有文案（回执缺失 = 沿用通用重置文案）。
     */
    override suspend fun resetReceipt(sessionKey: String): String? = null

    /**
     * 5 参接口重载 = 全流程入口：端点刷新与校验、executor 构造收拢在此（两入口共用，勿再散回 2 参重载）。
     */
    override suspend fun run(
        request: AgentRequest,
        chatId: String,
        senderOpenId: String,
        progress: ProgressAccumulator?,
        onLine: (AgentLine) -> Unit,
    ): AgentRunResult {
        val startedAt = System.currentTimeMillis()
        // 每次执行前重刷端点快照（isAvailable 的同步判定兜不住 DataStore 热改——run 在协程内可挂起读）。
        refreshEndpoint()
        val endpoint = endpointRef.get()
        if (endpoint == null || !endpoint.isConfigured) {
            progress?.markStatus(RunStatus.FAILED)
            return fail(startedAt, TaskErrorCodes.AGENT_CONFIG_INVALID, "本地 AI 端点未配置（请在 AI 设置页配置 Base URL / API Key / 模型）")
        }
        val (executor, model) = executorFactory(endpoint)
        val result = run(request, executor, model, startedAt, progress, chatId, senderOpenId)
        if (progress != null && result.isOk && progress.entryCount == 0) {
            progress.onEvent(ProgressEvent.AssistantText(result.summary))
        }
        return result
    }

    private suspend fun run(
        request: AgentRequest,
        executor: MultiLLMPromptExecutor,
        model: LLModel,
        startedAt: Long,
        progress: ProgressAccumulator?,
        chatId: String,
        senderOpenId: String,
    ): AgentRunResult = withContext(Dispatchers.Default) {
        // 步数预算 run 前现读一次（本次 run 内一致）：HOT 配置改值下一次 run 生效。
        val maxIterations = maxIterationsProvider().coerceAtLeast(MIN_MAX_ITERATIONS)
        // 交付沙箱同样 run 前现读一次（一次读入局部变量，避免同一次 run 内配置热更撕裂）。
        val sandbox = sandboxProvider()
        // 产物回传总开关与桥接数据根（send_file 的两个前置闸输入 + memory 目录派生根，
        // 现读一次防同 run 内热更撕裂）。
        val artifactsEnabled = artifactsEnabledProvider()
        val homeDataRoot = homeDataRootProvider()
        // L2 原生工具配置：run 前现读一次；null = 未接线 ⇒ L2 全不注册。
        val nativeConfig = nativeToolsProvider()
        // 会话内提醒 job 面：run 前现读一次；null = 未注入 ⇒ remind_user 不注册。
        val reminderScheduler = reminderSchedulerProvider()
        val endpointConfig = endpointRef.get()
            ?: return@withContext failMarked(progress, startedAt, TaskErrorCodes.AGENT_CONFIG_INVALID, "端点快照缺失")
        val tools = BridgeToolRegistry.build(
            endpointConfig = endpointConfig,
            request = request,
            chatId = chatId,
            senderOpenId = senderOpenId,
            sandbox = sandbox,
            artifactsEnabled = artifactsEnabled,
            homeDataRoot = homeDataRoot,
            nativeConfig = nativeConfig,
            reminderScheduler = reminderScheduler,
            trustedUsersProvider = trustedUsersProvider,
            devDeviceSource = devDeviceSource,
            selfStatusSource = selfStatusSource,
            askUserBus = askUserBusProvider(),
            overallTimeoutMillisProvider = { request.totalTimeoutMillis },
            // ws.run_task 长任务活动信号：行旁路 → progress.touch（喂空闲看门狗防 120s 误杀）
            // + 尾部行进进度卡（用户可见的执行中反馈）。
            runTaskTap = progress?.let { p -> { line: String -> p.onEvent(ProgressEvent.AssistantText("⏳ $line")) } },
        )
        // 注册表先行（单源派生）：本次 run 实际注册的工具名集合传给提示词，
        //    清单行按它条件输出（「列了没注册」错位自动消失）。
        val registeredToolNames = tools.tools.map { it.name }.toSet()
        try {
            // AIAgent 装配 + 事件映射 + 超时纪律收口到 :core 的 koogToolLoop 骨架。
            // 🔴 本链三个不可妥协点逐字保留：
            //    ① strategy 恒传 larkDispatchStrategy()——混合响应工具丢失修复（骨架红线 1）；
            //    ② ChatMemory 安装在本链自装（骨架红线 3：不接管——骨架的 installFeatures 面不暴露）；
            //       经 installFeatures 钩子注入（骨架 handleEvents 在同一切面内共存）；
            //    ③ 进度事件映射（progress == null 时 no-op——2 参入口/CLI 形态）：
            //       LLM 阶段 → Thinking（相邻由 accumulator 合并）；工具 → ToolUse/ToolResult（失败如实）；
            //       LLM 完成 → AssistantText（仅非空：工具轮 response 常无文本，空文本进窗纯垃圾）；
            //       流式 token 帧 → 只 touch 不进窗（滚动窗口防 token 帧糊屏挤出有语义事件——
            //       DownloadCardSession.startHeartbeat 同款）。record() 内自动 touch ⇒ 喂事件即保活。
            val output = koogToolLoopWithMemory(
                executor = executor,
                llmModel = model,
                request = KoogToolLoopRequest(
                    prompt = request.prompt,
                    tools = tools,
                    systemPrompt = systemPromptFor(
                        request = request,
                        senderOpenId = senderOpenId,
                        sandboxAvailable = sandbox != null,
                        artifactsEnabled = artifactsEnabled,
                        registeredToolNames = registeredToolNames,
                    ),
                    strategy = larkDispatchStrategy(),
                    maxIterations = maxIterations,
                    overallTimeoutMillis = request.totalTimeoutMillis,
                    sessionId = request.sessionKey, // 🔴 sessionId 即 runId——ChatMemory 按 runId 存取 ⇒ 话题隔离自动继承
                    onEvent = { event ->
                        when (event) {
                            is KoogLoopEvent.LlmCallStarting ->
                                progress?.onEvent(ProgressEvent.Thinking("思考中（模型 ${event.modelId}）"))
                            is KoogLoopEvent.LlmCallCompleted -> {
                                val text = event.text
                                if (!text.isNullOrBlank()) {
                                    progress?.onEvent(ProgressEvent.AssistantText(text))
                                } else {
                                    progress?.touch() // 无文本的完成也算活动（工具轮间看门狗保活）
                                }
                            }
                            is KoogLoopEvent.ToolCallStarting ->
                                // argsSummary 用真实参数摘要（勿退化为「调用工具 X」）
                                progress?.onEvent(
                                    ProgressEvent.ToolUse(
                                        name = event.toolName,
                                        argsSummary = "调用工具 ${event.toolName}（${event.argsSummary}）",
                                    ),
                                )
                            is KoogLoopEvent.ToolCallCompleted ->
                                // observation 真实结果摘要回填（勿退化为「X 完成」）
                                progress?.onEvent(
                                    ProgressEvent.ToolResult(
                                        name = event.toolName,
                                        ok = true,
                                        summary = event.observation?.take(120) ?: "${event.toolName} 完成",
                                    ),
                                )
                            is KoogLoopEvent.ToolCallFailed ->
                                progress?.onEvent(
                                    ProgressEvent.ToolResult(
                                        name = event.toolName,
                                        ok = false,
                                        summary = "工具 ${event.toolName} 失败：${event.error}",
                                    ),
                                )
                            is KoogLoopEvent.StreamingFrame -> progress?.touch()
                            is KoogLoopEvent.ExecutionFailed -> Unit // agent 级失败由外层 catch 分类，进度卡终态不在此重复
                        }
                    },
                ),
                installFeatures = {
                    install(ChatMemory.Feature) {
                        chatHistoryProvider = historyProvider
                        windowSize(historyWindowSize)
                    }
                },
            )
            progress?.markStatus(RunStatus.DONE)
            AgentRunResult(
                exitCode = 0,
                summary = output,
                elapsedMillis = System.currentTimeMillis() - startedAt,
                outputChars = output.length,
            )
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            progress?.markStatus(RunStatus.TIMEOUT)
            fail(startedAt, TaskErrorCodes.AGENT_TIMEOUT_ALIAS, "本地调度 agent 超时（${request.totalTimeoutMillis / 1000}s）")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 🔴 CE 原样上抛：「停止」指令的取消传播不吞（DecryptAgent 同款纪律）；
            //    此分支不 markStatus——外层 runAgentPipeline 的终态卡经 RunStatus.toTaskRunStatus()（RUNNING→FAILED）兜底。
        } catch (e: AIAgentMaxNumberOfIterationsReachedException) {
            // 专项 catch（勿并回泛化 task_error）：异常全链零包装（Koog
            //    runCatchingCancellable→getOrThrow 同实例 rethrow）才在此归类为独立
            //    errorCode AGENT_MAX_ITERATIONS；文案面向飞书端用户、须中文可操作
            //    （英文原文「consider increasing maxAgentIterations…」不可操作）。
            JLog.w(TAG) { "Koog 调度 agent 迭代上限触顶（${maxIterations} 节点）：${e.message}" }
            progress?.markStatus(RunStatus.FAILED)
            fail(startedAt, TaskErrorCodes.AGENT_MAX_ITERATIONS, "任务太复杂，agent 步数预算（$maxIterations 步）用完了还没得出结论；请拆小任务或分多轮提问")
        } catch (e: Throwable) {
            // 未分类异常必须落盘（异常类名+消息+栈）——audit 只记 errorCode，
            //    不落盘则真机排查无凭据。
            JLog.e(TAG, e) { "Koog 调度 agent 未分类异常：${e::class.qualifiedName}" }
            progress?.markStatus(RunStatus.FAILED)
            fail(startedAt, TaskErrorCodes.TASK_ERROR, "本地调度 agent 执行失败：${e.message ?: e::class.simpleName}")
        }
    }

    /** 前置 fail（私有 run 内联 return 用）——同步 markStatus FAILED，进度卡不留「生成中」假象。 */
    private fun failMarked(progress: ProgressAccumulator?, startedAt: Long, errorCode: String, detail: String): AgentRunResult {
        progress?.markStatus(RunStatus.FAILED)
        return fail(startedAt, errorCode, detail)
    }

    private fun systemPromptFor(
        request: AgentRequest,
        senderOpenId: String,
        sandboxAvailable: Boolean,
        artifactsEnabled: Boolean = true,
        registeredToolNames: Set<String> = emptySet(),
    ): String {
        val cfg = promptConfigRef.get()
        val userStyleId = cfg.userStyles[senderOpenId]
            ?.takeIf { it.isNotBlank() && it != "custom" }
        val styleOverride = userStyleId?.let { KoogAgentPromptPresets.stylePrompt(it, "") }
        return buildKoogSystemPrompt(
            request = request,
            knowledgeAvailable = KnowledgeBase.hasContent(),
            stylePrompt = KoogAgentPromptPresets.stylePrompt(cfg.styleId, cfg.styleCustom),
            safetyPrompt = cfg.safetyPrompt,
            styleOverridePrompt = styleOverride,
            sandboxAvailable = sandboxAvailable,
            artifactsEnabled = artifactsEnabled,
            registeredToolNames = registeredToolNames,
        )
    }

    private fun fail(startedAt: Long, errorCode: String, detail: String): AgentRunResult = AgentRunResult(
        exitCode = -1,
        summary = detail,
        detail = detail,
        errorCode = errorCode,
        elapsedMillis = System.currentTimeMillis() - startedAt,
    )

    companion object {
        /** 审计 backend 字段标识（桥接侧 `AgentHandler` 的 Koog 判定读同一字面值）。 */
        const val BACKEND_ID = "koog"

        /** 日志 TAG（JLog 纪律：`JLog.d(TAG) { ... }` 惰性求值形态）。 */
        private const val TAG = "KoogAgentRunner"

        /** 会话 LRU 容量（对齐桥接域 SessionConfig.capacity 默认 64）。 */
        internal const val DEFAULT_SESSION_CAPACITY = 64

        /** 单会话窗口（防长对话 prompt 无限膨胀；windowSize 语义）。 */
        internal const val DEFAULT_HISTORY_WINDOW = 20

        /** 步数预算下限（provider 外部值防御：低于此值 agent 连一轮工具循环都跑不完）。 */
        internal const val MIN_MAX_ITERATIONS = 10

        /**
         * 工具循环上限（迭代单位 = **节点执行次数**，非工具轮数——Koog `AIAgentSubgraph` 每执行
         * 一个节点 `++iterations`、超限抛 [ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException]，
         * 检查发生在节点执行之前）。
         * 换算口径：`larkDispatchStrategy` 稳态回环边是 nodeSendToolResult → nodeExecuteTool
         * （nodeCallLLM 仅首轮走一次），**每工具轮耗 2 节点** ⇒ 60 ≈ 29~30 工具轮；一次合法深度
         * 日志分析约需 15+ 工具轮，60 留有余量（30 ≈ 14 轮不够用）。
         * 失控保险丝不变：真实时限由 withTimeout(totalTimeoutMillis) 兜底，本值不放宽任何实际超时。
         */
        internal const val DEFAULT_MAX_ITERATIONS = 60

        internal val SYSTEM_PROMPT_HEAD = """
你是飞书机器人的本地调度助手。用户的消息没有命中任何固定指令，由你判断最佳处理方式：
- 能力分级：飞书原生操作（发消息/表情/图片/话题，当前会话内）是你的一级能力；本地文件处理（解密/过滤/分割/解压）与工作区能力（写文件/构建任务/git 只读）是二级辅助手段——日志分析等文件任务归二级，分析结果可经一级通道（图片/文件/卡片）交付；
- 轻量问答/闲聊/概念解释 → 直接用 reply_direct 答复（中文、简洁、准确）；
- 需要交付文件 → send_file；需要交付图片 → send_image；需要结构化展示 → send_card；
- 复杂任务承接：代码/仓库问题 → git.read 查历史与差异 + ws.run_task 跑构建测试；生成报告/写文件 → ws.write_file（diff 确认卡后写入，send_file 交付）；长日志分析 → log.* 管线与侦察件；
- 信息不足且会阻塞任务 → user.ask_user 一次把问题问全（多字段批量），不要瞎猜；
- 超出以上工具能力边界时 → 如实说明边界并给用户可执行的步骤清单（sys.handoff 形态），不硬答、不编造、不伪装已执行。
判断原则：能用轻手段解决的绝不调重工具；文件任务先想本地工具链；写/执行类动作必经用户确认卡，未经确认绝不重试。
        """.trimIndent()
    }
}
