package com.jiaoay.easy_dev_toolbox.page.mcp

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DecryptTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.KnowledgeTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResultSink
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 工具面映射：描述符 → MCP Tool 的**单一真源派生** + 围栏装配 + 调用分发。
 *
 * ## 外放名集（[MCP_EXPOSE_NAMES]）
 * fs.extended 取证七件 + knowledge 检索三件 + log.analyze_log 管线 + log.* 确定性六件。
 * 🔴 memory.* 不外放（个人记忆不进外部 client）；🔴 write_todos 不外放（桌面会话件）；
 * 🔴 fs.* 基础四件不外放（decrypt_file/extract_archive 桌面已有专属页 + 出站面收窄；
 * preview_text/list_dir 由 fs.extended.read_file 覆盖读取需求）。
 *
 * ## schema 派生（纯函数 [toolSchemaOf]）
 * Koog `Tool.descriptor` 已从 Args 的 @Serializable 反射出 requiredParameters/optionalParameters
 * （SchemaGenerator 真源）——本层只做 Koog 参数类型 → JSON Schema 类型的小映射（String/Integer/
 * Float/Boolean 四类 + List/Enum/AnyOf 递归），描述 = descriptor.description（Koog 已并入
 * LLMDescription 注解文案）。
 *
 * ## 调用分发（[ToolDispatcher]）
 * 每次端点请求经 block 工厂局部构建（[buildDispatcher]）：授权根现读 → registry 装配；
 * MCP handler 按工具名查表 → kotlinx Json 解码 args → executeUnsafe（Koog 类型化入口）。
 * 围栏经 [ToolExecutionContext.withAllowedRoots]（源码目录 allowlist = getSourceRoots
 * **每请求现读**——AI 设置页目录增删对下一个请求即时生效，与 [McpAgentTools] 每次现读
 * 语义一致）+ escapeGate deny-only（fail-safe，远程 client 无法响应桌面弹窗，
 * 拒绝文案带授权目录清单）。
 *
 * 🔴 本文件是 io.modelcontextprotocol.* 三白名单文件之一（McpSdkIsolationTest 5.1 钉死）。
 */
object McpToolRegistrar {

    private const val TAG = "McpToolRegistrar"

    /**
     * 外放工具名集（feature 内自持——WorkbenchSession.WORKBENCH_NAMES 先例：
     * :core 的名集是 private/internal 跨模块不可见，目录名是稳定契约）。
     */
    val MCP_EXPOSE_NAMES: Set<String> = setOf(
        // fs.extended 取证七件
        "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
        "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
        "fs.extended.convert_timestamp",
        // knowledge 检索三件
        "knowledge.search", "knowledge.read", "knowledge.lookup_tag",
        // 确定性管线
        "log.analyze_log",
        // log.* 确定性六件（解密/Tag 过滤/时间过滤/区间过滤/分割/判型——外部 client 直调）
        "log.decrypt", "log.filter_by_tag", "log.filter_by_time", "log.filter_by_range",
        "log.split_by_size", "log.probe_file",
    )

    /** serverInfo（Implementation——name/version 必填）。 */
    private val SERVER_INFO = Implementation(name = "Toolbelt", version = "1.0.0")

    /**
     * 装配 server（McpServerManager.DefaultEngine 挂载点——每次端点请求经
     * mcpStatelessStreamableHttp 的 block 工厂调用）。
     *
     * 工具注册经 [registerTools]：描述符面单一真源（名称/描述/schema 全派生）；
     * dispatcher 每请求局部构建（无跨请求共享可变状态——授权根随配置即时生效/撤销）。
     */
    fun buildServer(): Server {
        val server = Server(
            serverInfo = SERVER_INFO,
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
            ),
        )
        registerTools(server, buildDispatcher())
        McpAgentTools.register(server)
        return server
    }

    /**
     * 构建调用分发器（每请求一次——围栏每请求现读源码目录 allowlist，AI 设置页
     * 目录增删对下一个请求即时生效/撤销）。
     *
     * 快照语义：单次请求按其受理时的授权根快照执行（在途请求不因配置变更被追杀）；
     * 同一 client 会话跨请求可能看到不同授权根（目录在两次请求间被删时表现为后续
     * 请求被拒——deny-only 围栏下的预期行为，非时好时坏）。
     *
     * 硬限制：[AgentToolCatalog.buildRegistry] 必须保持纯内存装配——本函数落在每请求的
     * HTTP 路径上（tools/list 等轻请求也全额重建），往 catalog 加带 IO 或重初始化的
     * 工具会令每请求成本线性放大。
     */
    private fun buildDispatcher(): ToolDispatcher {
        // DataStore 读容错（读失败回落空集：deny-only 围栏下空集 = 该请求全拒
        //    ——fail-safe 方向：宁可全拒不放行无围栏路径；JLog.w 留排障线索）
        val roots = runCatching { runBlocking { LlmConfigStore.getSourceRoots() } }
            .getOrElse {
                JLog.w(TAG) { "源码目录读取失败（${it.message}）——围栏回落空集（本请求全拒）" }
                emptyList()
            }
        val context = ToolExecutionContext.withAllowedRoots(
            allowedRoots = roots.map { java.io.File(it) },
            escapeGate = ToolExecutionContext.PathEscapeGate { path ->
                // 🔴 deny-only：远程 client 无法响应桌面 HITL 弹窗——
                // fail-safe 同 McpPermissionServer「无法确认即拒绝」哲学；reason 经返回 false
                // 落进工具的 DENIED 文案（JLog 补记授权清单供排障）。
                JLog.w(TAG) {
                    "MCP 围栏外路径被拒绝：$path（授权目录：${roots.joinToString("; ")}）"
                }
                false
            },
        )
        val registry = AgentToolCatalog.buildRegistry(
            names = MCP_EXPOSE_NAMES,
            context = context,
            sink = ToolResultSink.NOOP,
            config = AgentToolCatalog.ToolContextConfig(
                privateKeyProvider = DecryptTools.defaultPrivateKeyProvider,
                fenceHint = "路径不在 MCP 授权范围内（授权目录 = AI 设置页「源码目录」清单 + 本次调用登记的文件），请检查路径",
            ),
        )
        return ToolDispatcher(registry)
    }

    /**
     * 描述符 → MCP Tool 注册（单一真源派生——spec「工具清单与描述符面一致」）。
     * agent 会话工具（workbench_analyze/distill_run/workbench_status）在 McpAgentTools 补挂。
     *
     * 双名域解析：MCP 侧工具名 = 目录名（`log.decrypt` 点分命名空间——外放契约），
     *    Koog registry 内注册名 = 工具实现名（`decrypt_log` 旧名——存量 LLM 提示词兼容面）。
     *    查表经目录描述符的 aliases 解析（registry 名 ∈ {name, aliases}）。
     */
    private fun registerTools(server: Server, dispatcher: ToolDispatcher) {
        val catalog = AgentToolCatalog.all.filter { it.name in MCP_EXPOSE_NAMES }
        catalog.forEach { descriptor ->
            val tool = dispatcher.toolOf(descriptor) ?: run {
                JLog.w(TAG) { "外放名集内工具装配为 null（能力门控）：${descriptor.name}" }
                return@forEach
            }
            val description = descriptor.uiHint?.description ?: descriptor.description
            server.addTool(
                name = descriptor.name,
                description = description,
                inputSchema = toolSchemaOf(tool),
            ) { request ->
                dispatcher.dispatch(descriptor, request)
            }
        }
    }

    // ── schema 派生（纯函数——单测直测缝） ──

    /**
     * Koog 工具参数面 → MCP ToolSchema（JSON Schema）。
     * 类型映射：String→string / Integer→integer / Float→number / Boolean→boolean /
     * List→array+items / Enum→enum / AnyOf→anyOf / 其他（Object/Null）→ 回落 string。
     */
    internal fun toolSchemaOf(tool: ToolBase<*, *>): ToolSchema {
        val d = tool.descriptor
        val properties = buildJsonObject {
            d.requiredParameters.forEach { put(it.name, parameterSchema(it.type)) }
            d.optionalParameters.forEach { put(it.name, parameterSchema(it.type)) }
        }
        return ToolSchema(
            properties = properties,
            required = d.requiredParameters.map { it.name }.takeIf { it.isNotEmpty() },
        )
    }

    /** 单参数 schema（JSON Schema 节点；描述并入 description 字段）。 */
    private fun parameterSchema(type: ToolParameterType): JsonObject = buildJsonObject {
        when (type) {
            is ToolParameterType.String -> put("type", "string")
            is ToolParameterType.Integer -> put("type", "integer")
            is ToolParameterType.Float -> put("type", "number")
            is ToolParameterType.Boolean -> put("type", "boolean")
            is ToolParameterType.Enum -> put("enum", JsonArray(type.entries.map { JsonPrimitive(it) }))
            is ToolParameterType.List -> {
                put("type", "array")
                put("items", parameterSchema(type.itemsType))
            }
            is ToolParameterType.AnyOf -> put(
                "anyOf",
                JsonArray(type.types.map { parameterSchema(it.type) }),
            )
            else -> put("type", "string")
        }
    }

    /** 测试接缝：直接断言 registry 装配面（名集精确集合——不触达 server）。 */
    internal fun buildRegistryForTest(): ToolRegistry {
        val context = ToolExecutionContext.withAllowedRoots(emptyList())
        return AgentToolCatalog.buildRegistry(
            names = MCP_EXPOSE_NAMES,
            context = context,
            config = AgentToolCatalog.ToolContextConfig(
                privateKeyProvider = DecryptTools.defaultPrivateKeyProvider,
            ),
        )
    }

    /**
     * 调用分发器：目录名 → 别名解析 → 查表 → kotlinx 解码 args → Koog executeUnsafe 执行。
     * 每请求构建（[buildDispatcher] 现读授权根装配；测试可自建实例注入）。
     */
    class ToolDispatcher(
        val registry: ToolRegistry,
        private val json: Json = Json { ignoreUnknownKeys = true },
    ) {

        /**
         * 目录描述符 → Koog registry 工具（双名域解析）：
         * registry 内注册名 = 工具实现名（Koog name），目录描述符面 = 点分目录名 + aliases。
         * 查表序：目录名直查 → aliases 逐个查（两者之一必中，装配即同源）。
         */
        fun toolOf(descriptor: AgentToolCatalog.AgentToolDescriptor): ToolBase<*, *>? {
            registry.getToolOrNull(descriptor.name)?.let { return it }
            descriptor.aliases.firstNotNullOfOrNull { registry.getToolOrNull(it) }?.let { return it }
            return null
        }

        /** MCP tools/call → 本地工具执行（结果文本回传 client——spec「产物路径回传」）。 */
        @OptIn(
            ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class,
            ai.koog.serialization.annotations.InternalKoogSerializationApi::class,
        )
        fun dispatch(descriptor: AgentToolCatalog.AgentToolDescriptor, request: CallToolRequest): CallToolResult {
            val tool = toolOf(descriptor)
                ?: return errorResult("工具不存在：${descriptor.name}（可用工具见 tools/list）")
            val argsJson: JsonObject = request.params.arguments ?: JsonObject(emptyMap())
            return try {
                // kotlinx Json 串行中转：typeToken<Args>() 是 KotlinTypeToken（KType 载体）——
                // 经 kotlinx 的 serializer(KType) 反射拿 KSerializer（@Serializable Args 契约）；
                // ignoreUnknownKeys 容忍 client 多传字段。
                val typed: Any? = when (val token = tool.argsType) {
                    is ai.koog.serialization.KSerializerTypeToken<*> -> token.serializer
                    is ai.koog.serialization.KotlinTypeToken ->
                        kotlinx.serialization.serializer(token.type)
                    else -> null
                }?.let { serializer ->
                    @Suppress("UNCHECKED_CAST")
                    json.decodeFromString(serializer as kotlinx.serialization.KSerializer<Any?>, argsJson.toString())
                } ?: argsJson
                val result = runBlocking { tool.executeUnsafe(typed) }
                CallToolResult(content = listOf(TextContent(text = result.toString())))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                JLog.w(TAG) { "MCP 工具调用失败：${descriptor.name}（${e.message}）" }
                errorResult("工具执行失败：${e.message ?: e::class.simpleName}")
            }
        }

        /**
         * MCP 侧 progress 通知（agent 会话长任务上报——McpAgentTools 消费）。
         *
         * // OPEN(MCP): progress 通知通道未接线（本函数与 [progressTokenOf] 现零调用点
         * ——客户端有替代路：runId 返回文本已带 workbench_status 轮询指引）；
         * re-entry 条件 = 客户端反馈需要推送进度时接 sink→progressNotification。
         */
        fun progressNotification(request: CallToolRequest, progress: Double, message: String?): ProgressNotification? {
            val token = progressTokenOf(request) ?: return null
            return ProgressNotification(
                ProgressNotificationParams(
                    progressToken = token,
                    progress = progress,
                    message = message,
                ),
            )
        }

        private fun progressTokenOf(request: CallToolRequest): ProgressToken? {
            val tokenElement = request.params.arguments?.get("_meta")?.jsonObject?.get("progressToken")
                ?: return null
            return when {
                tokenElement is JsonPrimitive && tokenElement.isString ->
                    ProgressToken(tokenElement.content)
                tokenElement is JsonPrimitive && (tokenElement.longOrNull != null) ->
                    ProgressToken(tokenElement.long)
                else -> null
            }
        }

        private fun errorResult(text: String): CallToolResult =
            CallToolResult(content = listOf(TextContent(text = text)), isError = true)
    }
}
