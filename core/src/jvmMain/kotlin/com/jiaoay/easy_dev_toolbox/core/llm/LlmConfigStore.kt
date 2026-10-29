package com.jiaoay.easy_dev_toolbox.core.llm

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import kotlinx.coroutines.flow.map

/**
 * AI 能力域配置仓库（LLM 端点）：OpenAI 兼容端点的 Base URL / API Key / 模型 ID / 向量模型 ID / 超时。
 *
 * 经 :core 的 [AppDataStore] 进程唯一实例读写（feature 禁止自行 PreferenceDataStoreFactory）。
 * 读写样板继承 [DomainConfigStore] 共享骨架（read/write 委托对的唯一真源），
 * 测试缝留在本 object（见 [store]）。
 * key 命名对齐 [LogKitConfigStore] 的 snake_case 风格。
 *
 * API Key 为明文存储 + UI 掩码（先例：xlog_private_key）；系统钥匙串属未来增强。
 */
object LlmConfigStore : DomainConfigStore() {

    private const val KEY_LLM_BASE_URL = "llm_base_url"
    private const val KEY_LLM_API_KEY = "llm_api_key"
    private const val KEY_LLM_MODEL_ID = "llm_model_id"
    private const val KEY_LLM_EMBEDDING_MODEL_ID = "llm_embedding_model_id"
    private const val KEY_LLM_TIMEOUT_SECONDS = "llm_timeout_seconds"
    private const val KEY_LLM_MAX_ITERATIONS = "llm_max_iterations"
    private const val KEY_LLM_AI_ENABLED = "llm_ai_enabled"

    /**
     * 测试接缝：替换为临时目录实例（先例：TimestampConfigStore 同款）。
     * 🔴 public 的原因：internal 跨模块不可见（消费方测试在 feature 模块）；
     *    set 仅测试语义，生产代码不得调用（[AppDataStore.store] 是进程唯一实例——
     *    生产调用 set 即进程内出现第二实例，同文件并发访问直接抛错）。
     */
    var store = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store

    // ── Base URL ──

    /** OpenAI 兼容端点根地址；空串 = 官方 https://api.openai.com（构造 client 时不覆盖默认值） */
    suspend fun getBaseUrl(): String = read(KEY_LLM_BASE_URL, "")
    suspend fun setBaseUrl(url: String) = write(KEY_LLM_BASE_URL, url)

    // ── API Key ──

    suspend fun getApiKey(): String = read(KEY_LLM_API_KEY, "")
    suspend fun setApiKey(key: String) = write(KEY_LLM_API_KEY, key)

    // ── 模型 ID ──

    /** 模型标识（如 gpt-4o-mini / deepseek-chat / qwen-plus）；必填才可用 */
    suspend fun getModelId(): String = read(KEY_LLM_MODEL_ID, "")
    suspend fun setModelId(id: String) = write(KEY_LLM_MODEL_ID, id)

    // ── 向量模型 ID（知识库 embed 用） ──

    suspend fun getEmbeddingModelId(): String = read(KEY_LLM_EMBEDDING_MODEL_ID, "")
    suspend fun setEmbeddingModelId(id: String) = write(KEY_LLM_EMBEDDING_MODEL_ID, id)

    /** 空配置时的内置缺省向量模型（OpenAI 兼容端点最普及的 embedding 模型名） */
    const val DEFAULT_EMBEDDING_MODEL_ID = "text-embedding-3-small"

    // ── 超时 ──

    /** 单次 LLM 调用超时（秒），页面校验域 5..600 */
    suspend fun getTimeoutSeconds(): Int = read(KEY_LLM_TIMEOUT_SECONDS, 60)
    suspend fun setTimeoutSeconds(seconds: Int) = write(KEY_LLM_TIMEOUT_SECONDS, seconds)

    // ── 桌面 agent 迭代上限（配置化，勿回退编译常量） ──

    suspend fun getMaxIterations(): Int = read(KEY_LLM_MAX_ITERATIONS, DEFAULT_MAX_ITERATIONS)
    suspend fun setMaxIterations(value: Int) = write(KEY_LLM_MAX_ITERATIONS, value)

    /** 缺省 24 节点（≈12 工具轮——原万能解密编译常量 MAX_ITERATIONS=24 的值等价迁移） */
    const val DEFAULT_MAX_ITERATIONS = 24

    // ── 蒸馏链独立预算（蒸馏 = 全模块扫描长流程，通用 24 节点偏紧）──

    suspend fun getDistillMaxIterations(): Int = read(KEY_LLM_DISTILL_MAX_ITERATIONS, DEFAULT_DISTILL_MAX_ITERATIONS)
    suspend fun setDistillMaxIterations(value: Int) = write(KEY_LLM_DISTILL_MAX_ITERATIONS, value)

    /** 蒸馏缺省 60 节点（≈30 工具轮） */
    const val DEFAULT_DISTILL_MAX_ITERATIONS = 60

    // ── 工作台研判链独立预算（研判=管线调用+知识检索+取证+报告生成，长流程）──

    suspend fun getWorkbenchMaxIterations(): Int = read(KEY_LLM_WORKBENCH_MAX_ITERATIONS, DEFAULT_WORKBENCH_MAX_ITERATIONS)
    suspend fun setWorkbenchMaxIterations(value: Int) = write(KEY_LLM_WORKBENCH_MAX_ITERATIONS, value)

    /** 工作台缺省 60 节点（≈30 工具轮） */
    const val DEFAULT_WORKBENCH_MAX_ITERATIONS = 60

    // ── 流程域单轮预算（Quest 域 change 1——每轮 send 独立 run，预算按轮重置）──

    suspend fun getFlowMaxIterations(): Int = read(KEY_LLM_FLOW_MAX_ITERATIONS, DEFAULT_FLOW_MAX_ITERATIONS)
    suspend fun setFlowMaxIterations(value: Int) = write(KEY_LLM_FLOW_MAX_ITERATIONS, value)

    /** 流程缺省 40 节点（≈20 工具轮） */
    const val DEFAULT_FLOW_MAX_ITERATIONS = 40

    // ── 多源码目录 allowlist（🔴 桌面链专属配置，桥接链恒不消费——桥接链接入即用户源码树出站旁路、可被发往飞书）──

    suspend fun getSourceRoots(): List<String> = SourceRootsMap.decode(read(KEY_LLM_SOURCE_ROOTS, ""))

    /** 源码目录整表写（AI 设置页「源码目录」卡片提交）。 */
    suspend fun setSourceRoots(roots: List<String>) = write(KEY_LLM_SOURCE_ROOTS, SourceRootsMap.encode(roots))
    // ── AI 工具总开关 ──

    suspend fun getAiEnabled(): Boolean {
        if (!activeStore.hasKey(KEY_LLM_AI_ENABLED)) {
            return getApiKey().isNotBlank() && getModelId().isNotBlank()
        }
        return read(KEY_LLM_AI_ENABLED, false)
    }

    suspend fun setAiEnabled(enabled: Boolean) = write(KEY_LLM_AI_ENABLED, enabled)

    // ── Koog agent 性格模式与安全约束（koog agent 是大脑——
    //     全局配置放 AI 设置页，不进飞书桥接配置域） ──
    // 不进 LlmEndpointConfig：它是 executor 缓存 key，掺入新字段会引发缓存无谓失效重建。
    //    消费侧（KoogAgentRunner.refreshEndpoint）经独立快照读取，HOT ≤1s 生效。

    /** 性格模式档 id（内置档见 [KoogAgentStylePreset]；默认 "default" = 温和友好） */
    suspend fun getAgentStyleId(): String = read(KEY_LLM_AGENT_STYLE_ID, "default")
    suspend fun setAgentStyleId(id: String) = write(KEY_LLM_AGENT_STYLE_ID, id)

    /** 自定义性格正文（仅 styleId=="custom" 时生效；留空 = 不注入性格段） */
    suspend fun getAgentStyleCustom(): String = read(KEY_LLM_AGENT_STYLE_CUSTOM, "")
    suspend fun setAgentStyleCustom(text: String) = write(KEY_LLM_AGENT_STYLE_CUSTOM, text)

    /** 安全策略档 id（内置档见 [KoogAgentSafetyPreset]；默认 "default"） */
    suspend fun getAgentSafetyId(): String = read(KEY_LLM_AGENT_SAFETY_ID, "default")
    suspend fun setAgentSafetyId(id: String) = write(KEY_LLM_AGENT_SAFETY_ID, id)

    /** 自定义安全正文（仅 safetyId=="custom" 时生效；留空 = 不注入安全约束段） */
    suspend fun getAgentSafetyCustom(): String = read(KEY_LLM_AGENT_SAFETY_CUSTOM, "")
    suspend fun setAgentSafetyCustom(text: String) = write(KEY_LLM_AGENT_SAFETY_CUSTOM, text)

    // ── per-user 性格档映射（openId → 内置档 id；无条目 = 跟随全局）──
    // 存储形态 = JSON 数组串（Preferences 只支持标量）；编解码收敛在 [KoogUserStyleMap] 纯函数。
    // 配置入口在「飞书桥接 → 统计 → 用户详情」（对着人配，非 AI 设置页集中配）。

    /** per-user 映射整表读（坏值防御回落空表）。 */
    suspend fun getAgentStyleUserMap(): List<KoogUserStyleEntry> =
        KoogUserStyleMap.decode(read(KEY_LLM_AGENT_STYLE_USER_MAP, ""))

    /** per-user 映射整表写（单 key 整写，无增量）。 */
    suspend fun setAgentStyleUserMap(entries: List<KoogUserStyleEntry>) =
        write(KEY_LLM_AGENT_STYLE_USER_MAP, KoogUserStyleMap.encode(entries))

    /**
     * per-user 映射变更流（collect 即得当前表，每次落盘后即时发射）。
     * UI 选中态跟随订阅口（写后回显）；坏值防御同 [getAgentStyleUserMap] 回落空表。
     */
    fun agentStyleUserMapFlow(): kotlinx.coroutines.flow.Flow<List<KoogUserStyleEntry>> =
        activeStore.valueFlow(KEY_LLM_AGENT_STYLE_USER_MAP)
            .map { KoogUserStyleMap.decode(it ?: "") }

    private const val KEY_LLM_AGENT_STYLE_ID = "llm_agent_style_id"
    private const val KEY_LLM_AGENT_STYLE_CUSTOM = "llm_agent_style_custom"
    private const val KEY_LLM_AGENT_SAFETY_ID = "llm_agent_safety_id"
    private const val KEY_LLM_AGENT_SAFETY_CUSTOM = "llm_agent_safety_custom"
    private const val KEY_LLM_AGENT_STYLE_USER_MAP = "llm_agent_style_user_map"
    private const val KEY_LLM_DISTILL_MAX_ITERATIONS = "llm_distill_max_iterations"
    private const val KEY_LLM_WORKBENCH_MAX_ITERATIONS = "llm_workbench_max_iterations"
    private const val KEY_LLM_FLOW_MAX_ITERATIONS = "llm_flow_max_iterations"
    private const val KEY_LLM_SOURCE_ROOTS = "llm_agent_source_roots"

    // ── MCP 外放服务配置（LLM 配置域同族归属）──

    private const val KEY_MCP_SERVER_ENABLED = "mcp_server_enabled"
    private const val KEY_MCP_SERVER_PORT = "mcp_server_port"

    suspend fun getMcpServerEnabled(): Boolean = read(KEY_MCP_SERVER_ENABLED, false)
    suspend fun setMcpServerEnabled(enabled: Boolean) = write(KEY_MCP_SERVER_ENABLED, enabled)

    /** MCP 缺省端口 8765（避开常用 3000/8080/9000 开发端口段）。 */
    const val DEFAULT_MCP_PORT = 8765

    /** 端口合法域（1024 起 = 避开特权端口与最常用低位端口；65535 = TCP 上限）。 */
    val MCP_PORT_RANGE: IntRange = 1024..65535

    suspend fun getMcpServerPort(): Int =
        read(KEY_MCP_SERVER_PORT, DEFAULT_MCP_PORT).takeIf { it in MCP_PORT_RANGE } ?: DEFAULT_MCP_PORT

    suspend fun setMcpServerPort(port: Int) = write(KEY_MCP_SERVER_PORT, port)
}
