package com.jiaoay.easy_dev_toolbox.page.mcp

import com.jiaoay.easy_dev_toolbox.core.llm.KoogDistillPrompts
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.WorkbenchPrompts
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogLoopEvent
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogToolLoopRequest
import com.jiaoay.easy_dev_toolbox.core.llm.agent.koogToolLoop
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.KnowledgeTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.LogStatsDiffTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResult
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResultSink
import com.jiaoay.easy_dev_toolbox.core.llm.tools.WebTools
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * agent 会话外放：`workbench_analyze` / `distill_run` / `workbench_status` 三个 MCP 工具。
 *
 * ## 会话模型（stateless）
 * 每次 tools/call = 独立 run（runId 生成 + McpRunRegistry 登记 + [McpServerManager] scope
 * 上后台执行）。长会话与 MCP 请求的生命周期解耦：client 断连不杀 run，结果经
 * `workbench_status` 按 runId 两段式查询；在途时 tools/call 即时返回 runId + 进行中说明。
 *
 * ## 执行体
 * 研判 = [WorkbenchPrompts.ANALYZE_PROMPT] + 自持 [WORKBENCH_NAMES]（工作台链同配方
 * 去 memory 四件——feature 互不依赖铁律下不可引用 WorkbenchSession.WORKBENCH_NAMES，
 * 与 prompt「可用工具」节的一致性由 McpAgentToolNamesGuardTest 钉死）；
 * 蒸馏 = [KoogDistillPrompts.DISTILL_PROMPT] 的**审批前形态**（探索+执行压缩为一次直行：
 * 源码目录已整体授权，候选经 ProposalBuffer 暂存**不写库**，产出文本随结果回传——
 * 桌面审阅入库仍走蒸馏页）。
 *
 * ## 预算与超时
 * 研判 = getWorkbenchMaxIterations、蒸馏 = getDistillMaxIterations（既有 key 零新增）；
 * 整体超时 = 端点超时 × [OVERALL_TIMEOUT_ROUNDS]（工作台/蒸馏同款换算）。
 *
 * 🔴 本文件是 io.modelcontextprotocol.* 三白名单文件之一（McpSdkIsolationTest 钉死）。
 */
object McpAgentTools {

    private const val TAG = "McpAgentTools"

    /** 整体超时轮数（WorkbenchSession.OVERALL_TIMEOUT_ROUNDS 同款值——feature 间不可 import） */
    private const val OVERALL_TIMEOUT_ROUNDS = 6

    /** fs.extended 取证七件（值同源自持——:core 的 EXTENDED_NAMES 是 private 跨模块不可见，目录名是稳定契约） */
    private val EXTENDED_FILE_NAMES = setOf(
        "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
        "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
        "fs.extended.convert_timestamp",
    )

    /**
     * log.* 时间过滤两件：filter_by_time = 对称窗口（时间点 ±N 整数分钟）；
     * filter_by_range = 任意起止闭区间（奇数分钟区间等对称窗口表达不了的形状）。
     * 值同源自持（LogTaskTools 无家族 NAMES 常量，与工作台链 LOG_FILTER_NAMES 同值镜像）。
     */
    private val LOG_FILTER_NAMES = setOf("log.filter_by_time", "log.filter_by_range")

    /**
     * 研判工具集（internal = 名集守护测试缝）：与桌面工作台链同配方、仅去 memory 四件
     * （个人记忆不进外部 client——装配不传 memoryDir，列了也恒不注册）。
     * :core 可见真源单源引用：knowledge 四件（[KnowledgeTools.KNOWLEDGE_NAMES]）、
     * 侦察四件（[LogStatsDiffTools.NAMES]）、网络查询一件（[WebTools.WEB_NAMES]）；
     * 其余（取证七件/过滤两件/管线一件）按值同源自持纪律镜像——:core 对应常量
     * private 跨模块不可见。
     * 与 ANALYZE_PROMPT「可用工具」节的一致性由 McpAgentToolNamesGuardTest 钉死（防漂）。
     */
    internal val WORKBENCH_NAMES: Set<String> = EXTENDED_FILE_NAMES +
        KnowledgeTools.KNOWLEDGE_NAMES +
        LOG_FILTER_NAMES +
        LogStatsDiffTools.NAMES +
        WebTools.WEB_NAMES +
        setOf("log.analyze_log")

    /**
     * 会话执行 scope（宿主 scope 经
     * McpServerManager.scopeForAgent() 注入（start(scope) 时 AppHost 传入）——
     * 自建 CoroutineScope 面消失，AppHostGuardTest 豁免条目随之删除。
     * App 退出时 AppHost scope cancel = 在途 run 一并收口（不再依赖进程终止兜底）。
     */
    private val scope: CoroutineScope
        get() = McpServerManager.scopeForAgent()

    private val runSeq = AtomicLong(0)

    /**
     * 注册 agent 会话工具（buildServer 尾部补挂——2.3 接线）。
     * schema 手写（agent 工具是 MCP 专属面，不经 Koog 描述符派生）。
     */
    fun register(server: Server) {
        server.addTool(
            name = "workbench_analyze",
            description = "启动日志根因研判 agent（后台长流程）：传入日志文件/时间点/症状，Toolbelt 内跑研判循环" +
                "（十铁律 + 工具取证），返回 runId——用 workbench_status 查询进度与报告",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("log_files", schemaArray("日志文件绝对路径列表（在授权目录内）"))
                    put("target_time", schemaString("问题时间点 yyyy-MM-dd_HH:mm:ss.SSS"))
                    put("range_minutes", schemaInteger("时间窗口 ±分钟"))
                    put("symptom", schemaString("症状描述"))
                    put("tag_regex", schemaString("可选：用户指定 Tag 正则（'TagA|TagB'）"))
                    put("follow_up_context", schemaString("可选：追问链上下文（原报告文本）"))
                },
                required = listOf("log_files", "target_time", "range_minutes", "symptom"),
            ),
        ) { request ->
            startAnalyze(request)
        }

        server.addTool(
            name = "distill_run",
            description = "启动知识蒸馏 agent（审批前形态——不写知识库）：扫描源码目录蒸馏日志分析知识候选，" +
                "返回 runId + 候选文本（经 workbench_status 查询）；入库需在 Toolbelt 蒸馏页人工审阅",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("source_dir", schemaString("源码根目录（必须在授权目录内）"))
                    put("module_desc", schemaString("功能模块描述（蒸馏定位锚）"))
                    put("entry_hints", schemaArray("入口提示（类名/文件名，可空）"))
                },
                required = listOf("source_dir", "module_desc"),
            ),
        ) { request ->
            startDistill(request)
        }

        server.addTool(
            name = "workbench_status",
            description = "查询 agent 会话 run 的进度与结果（两段式查询：progressNote 阶段/结果文本/产物路径清单）",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("run_id", schemaString("runId（workbench_analyze/distill_run 返回）"))
                },
                required = listOf("run_id"),
            ),
        ) { request ->
            queryStatus(request)
        }
    }

    // ── workbench_analyze（3.2） ──

    private fun startAnalyze(request: CallToolRequest): CallToolResult {
        val endpointCheck = checkEndpoint()
        if (endpointCheck != null) return endpointCheck

        val args = request.params.arguments ?: JsonObject(emptyMap())
        val logFiles = args.strList("log_files")
        val targetTime = args.str("target_time")
        val rangeMinutes = args.long("range_minutes")
        val symptom = args.str("symptom")
        val tagRegex = args.str("tag_regex")
        val followUp = args.str("follow_up_context")
        if (logFiles.isEmpty() || targetTime.isNullOrBlank() || symptom.isNullOrBlank() || rangeMinutes == null) {
            return errorResult("参数不全：log_files/target_time/range_minutes/symptom 必填")
        }

        val handle = McpRunRegistry.register(nextRunId(), "workbench_analyze")
            ?: return errorResult("在途 agent 会话已达上限（${McpRunRegistry.MAX_RUNS}），请稍后重试或查询已有 run")

        val job = scope.launch {
            val roots = LlmConfigStore.getSourceRoots().map { File(it) }
            val context = ToolExecutionContext.withAllowedRoots(roots)
            val artifacts = mutableListOf<String>()
            val sink = ToolResultSink.concurrentOf { _, result ->
                if (result is ToolResult.Ok) {
                    result.artifacts.forEach { a ->
                        synchronized(artifacts) {
                            if (a.path !in artifacts) artifacts += a.path
                        }
                    }
                    handle.artifacts.value = artifacts.toList()
                }
            }
            val registry = AgentToolCatalog.buildRegistry(
                names = WORKBENCH_NAMES,
                context = context,
                sink = sink,
                config = AgentToolCatalog.ToolContextConfig(
                    privateKeyProvider = DecryptTools.defaultPrivateKeyProvider,
                ),
            )
            try {
                val config = LlmEndpointConfig.read()
                val prompt = buildAnalyzePrompt(logFiles, targetTime, rangeMinutes, symptom, tagRegex, followUp)
                handle.progressNote.value = "研判启动（预算 ${LlmConfigStore.getWorkbenchMaxIterations()} 节点）"
                val output = koogToolLoop(
                    executor = LlmClientFactory.executor(config),
                    llmModel = LlmClientFactory.model(config.modelId),
                    request = KoogToolLoopRequest(
                        prompt = prompt,
                        tools = registry,
                        systemPrompt = WorkbenchPrompts.ANALYZE_PROMPT,
                        strategy = null,
                        maxIterations = LlmConfigStore.getWorkbenchMaxIterations(),
                        overallTimeoutMillis = config.timeoutSeconds * 1000L * OVERALL_TIMEOUT_ROUNDS,
                        onEvent = { event -> onLoopEvent(handle, event) },
                    ),
                )
                handle.complete(output)
                JLog.i(TAG) { "研判 run 完成：${handle.runId}" }
            } catch (e: TimeoutCancellationException) {
                handle.fail("研判整体超时（预算 ${LlmConfigStore.getTimeoutSeconds()}s × $OVERALL_TIMEOUT_ROUNDS 轮）")
            } catch (e: CancellationException) {
                // 取消保全：已产出的阶段产物保留（中止标记）
                handle.cancelWithPartial(null)
                JLog.i(TAG) { "研判 run 已取消（部分产物保留）：${handle.runId}" }
            } catch (e: Exception) {
                handle.fail(e.message?.take(400) ?: e::class.simpleName ?: "未知错误")
                JLog.e(TAG, e) { "研判 run 失败：${handle.runId}" }
            }
        }
        handle.cancelHook = { job.cancel() }
        McpRunRegistry.sweepFinished()
        return textResult(
            "研判已启动。runId=${handle.runId}\n" +
                "会话在 Toolbelt 后台执行（不受本次连接影响）——调用 workbench_status 查询进度与报告。" +
                "日志文件已在授权围栏内逐次登记。",
        )
    }

    /** 研判 prompt 组装（WorkbenchSession.buildPrompt 值同源——feature 间不可 import，语义逐字等价）。 */
    internal fun buildAnalyzePrompt(
        logFiles: List<String>,
        targetTime: String,
        rangeMinutes: Long,
        symptom: String,
        tagRegex: String?,
        followUpContext: String?,
    ): String = buildString {
        appendLine("日志文件（已授权读取）：")
        logFiles.forEach { appendLine("- $it") }
        appendLine("问题时间点：$targetTime")
        appendLine("时间范围（±分钟）：$rangeMinutes")
        appendLine("症状描述：$symptom")
        if (!tagRegex.isNullOrBlank()) {
            appendLine("用户指定 Tag 正则（可先反查知识库扩充）：$tagRegex")
        }
        if (followUpContext != null) {
            appendLine()
            appendLine("## 已有报告（增量研判——新结论追加，原结论不改写）")
            appendLine(followUpContext)
        }
    }

    // ── distill_run（3.3） ──

    private fun startDistill(request: CallToolRequest): CallToolResult {
        val endpointCheck = checkEndpoint()
        if (endpointCheck != null) return endpointCheck

        val args = request.params.arguments ?: JsonObject(emptyMap())
        val sourceDir = args.str("source_dir")
        val moduleDesc = args.str("module_desc")
        val entryHints = args.strList("entry_hints")
        if (sourceDir.isNullOrBlank() || moduleDesc.isNullOrBlank()) {
            return errorResult("参数不全：source_dir/module_desc 必填")
        }

        // 围栏前置校验：source_dir 必须在授权目录内（deny-only 语义在 start 前即可判定）
        val roots = kotlinx.coroutines.runBlocking {
            runCatching { LlmConfigStore.getSourceRoots() }.getOrDefault(emptyList())
        }
        val dir = File(sourceDir)
        val inside = runCatching {
            val canonical = dir.canonicalPath
            roots.any { root ->
                val r = File(root).canonicalPath
                canonical == r || canonical.startsWith(r.trimEnd('/') + "/")
            }
        }.getOrDefault(false)
        if (!inside) {
            return errorResult(
                "source_dir 不在授权目录内（授权目录 = AI 设置页「源码目录」清单：$roots）。" +
                    "请在 Toolbelt 的 AI 设置页添加该目录后重试。",
            )
        }

        val handle = McpRunRegistry.register(nextRunId(), "distill_run")
            ?: return errorResult("在途 agent 会话已达上限（${McpRunRegistry.MAX_RUNS}），请稍后重试")

        val job = scope.launch {
            val context = ToolExecutionContext.withAllowedRoots(listOf(dir))
            val proposalBuffer = DistillTools.ProposalBuffer()
            val registry = AgentToolCatalog.buildRegistry(
                names = EXTENDED_FILE_NAMES + KnowledgeTools.KNOWLEDGE_NAMES + DistillTools.DISTILL_NAMES,
                context = context,
                sink = ToolResultSink.NOOP,
                config = AgentToolCatalog.ToolContextConfig(distillBuffer = proposalBuffer),
            )
            try {
                val config = LlmEndpointConfig.read()
                val prompt = buildString {
                    appendLine("功能模块描述：$moduleDesc")
                    if (entryHints.isNotEmpty()) {
                        appendLine("入口提示：${entryHints.joinToString("、")}")
                    }
                    appendLine("源码目录（已整体授权直接执行蒸馏，无需计划审批）：$sourceDir")
                }
                handle.progressNote.value = "蒸馏启动（审批前形态——候选不写库）"
                val output = koogToolLoop(
                    executor = LlmClientFactory.executor(config),
                    llmModel = LlmClientFactory.model(config.modelId),
                    request = KoogToolLoopRequest(
                        prompt = prompt,
                        tools = registry,
                        systemPrompt = KoogDistillPrompts.DISTILL_PROMPT,
                        strategy = null,
                        maxIterations = LlmConfigStore.getDistillMaxIterations(),
                        overallTimeoutMillis = config.timeoutSeconds * 1000L * OVERALL_TIMEOUT_ROUNDS,
                        onEvent = { event -> onLoopEvent(handle, event) },
                    ),
                )
                // 审批前形态：候选文本拼装回传（入库走桌面蒸馏页人工审阅）
                val proposals = proposalBuffer.snapshot()
                val proposalText = if (proposals.isEmpty()) {
                    "（无候选产出）"
                } else {
                    buildString {
                        appendLine("## 蒸馏候选（${proposals.size} 条——审批前形态，未入库）")
                        proposals.forEach { p ->
                            appendLine()
                            appendLine("### [${p.kind}] ${p.title}")
                            appendLine(p.body)
                            appendLine("来源：${p.sourceRefs.joinToString("、")}")
                        }
                    }
                }
                handle.complete(output + "\n\n" + proposalText)
                JLog.i(TAG) { "蒸馏 run 完成：${handle.runId}（候选 ${proposals.size} 条）" }
            } catch (e: TimeoutCancellationException) {
                handle.fail("蒸馏整体超时（预算 ${LlmConfigStore.getTimeoutSeconds()}s × $OVERALL_TIMEOUT_ROUNDS 轮）")
            } catch (e: CancellationException) {
                handle.cancelWithPartial(proposalBuffer.snapshot().joinToString("\n") { "[${it.kind}] ${it.title}" })
                JLog.i(TAG) { "蒸馏 run 已取消（候选保留）：${handle.runId}" }
            } catch (e: Exception) {
                handle.fail(e.message?.take(400) ?: e::class.simpleName ?: "未知错误")
                JLog.e(TAG, e) { "蒸馏 run 失败：${handle.runId}" }
            }
        }
        handle.cancelHook = { job.cancel() }
        McpRunRegistry.sweepFinished()
        return textResult(
            "蒸馏已启动（审批前形态——不写知识库）。runId=${handle.runId}\n" +
                "调用 workbench_status 查询进度；完成后候选文本随结果回传，入库请在 Toolbelt 蒸馏页审阅。",
        )
    }

    // ── workbench_status（2.3 补挂） ──

    private fun queryStatus(request: CallToolRequest): CallToolResult {
        val runId = (request.params.arguments ?: JsonObject(emptyMap())).str("run_id")
            ?: return errorResult("参数不全：run_id 必填")
        val run = McpRunRegistry.find(runId)
            ?: return errorResult("runId 不存在：$runId（可能已清退或从未启动）")

        val body = buildString {
            appendLine("runId: ${run.runId}（${run.toolName}）")
            appendLine("状态: ${run.phase.value}")
            if (run.progressNote.value.isNotBlank()) appendLine("进度: ${run.progressNote.value}")
            if (run.artifacts.value.isNotEmpty()) {
                appendLine("阶段产物:")
                run.artifacts.value.forEach { appendLine("- $it") }
            }
            run.result.value?.let { appendLine(); appendLine("## 结果"); appendLine(it) }
        }
        return textResult(body)
    }

    // ── 共用 ──

    /** 未配端点明示（spec 场景：工具面不受影响，agent 面报错指引）。 */
    private fun checkEndpoint(): CallToolResult? {
        val config = runCatching {
            kotlinx.coroutines.runBlocking { LlmEndpointConfig.read() }
        }.getOrNull()
        return if (config == null || !config.isConfigured) {
            errorResult("未配置 LLM 端点——研判/蒸馏需要本地 LLM。请先在 Toolbelt 的 AI 设置页配置端点（Base URL/API Key/模型 ID）后再调用。确定性工具面不受影响。")
        } else {
            null
        }
    }

    /** Koog 事件 → 进度说明（progressNote——workbench_status 的实时进度面）。 */
    private fun onLoopEvent(handle: McpRunRegistry.RunHandle, event: KoogLoopEvent) {
        when (event) {
            is KoogLoopEvent.LlmCallStarting -> handle.progressNote.value = "模型思考中（${event.modelId}）"
            is KoogLoopEvent.ToolCallStarting -> handle.progressNote.value = "调用工具 ${event.toolName}…"
            is KoogLoopEvent.ToolCallCompleted -> handle.progressNote.value = "工具 ${event.toolName} 完成"
            is KoogLoopEvent.ToolCallFailed -> handle.progressNote.value = "工具 ${event.toolName} 失败：${event.error}"
            is KoogLoopEvent.ExecutionFailed -> handle.progressNote.value = "执行失败：${event.error.take(120)}"
            else -> Unit // LlmCallCompleted/StreamingFrame 不更新粗粒度进度
        }
    }

    private fun nextRunId(): String = "run-${System.currentTimeMillis()}-${runSeq.incrementAndGet()}"

    // ── 测试缝（同模块 jvmTest——生产不得调用） ──

    internal fun checkEndpointForTest(): CallToolResult =
        checkEndpoint() ?: textResult("端点已配置")

    internal fun statusForTest(runId: String): CallToolResult = queryStatus(
        CallToolRequest(
            io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                name = "workbench_status",
                arguments = buildJsonObject { put("run_id", runId) },
            ),
        ),
    )

    private fun textResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = text)))

    private fun errorResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = text)), isError = true)

    // schema 节点 helper（agent 工具手写 schema——非描述符派生面）

    private fun schemaString(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }

    private fun schemaInteger(desc: String) = buildJsonObject { put("type", "integer"); put("description", desc) }

    private fun schemaArray(desc: String) = buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject { put("type", "string") })
        put("description", desc)
    }

    // args JSON 取值 helper（MCP client 传参宽容面）

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            it.contentOrNull?.toLongOrNull() ?: it.longOrNull
        }

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        } ?: emptyList()
}
