package com.jiaoay.easy_dev_toolbox.core.llm

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.serialization.json.Json

/**
 * LLM 端点配置快照（缓存 key：任一字段变更即整组失效重建）。
 *
 * @param baseUrl OpenAI 兼容端点根地址；空串 = 官方 https://api.openai.com
 * @param timeoutSeconds 单次调用超时（秒），映射 Koog 内建 ConnectionTimeoutConfig.requestTimeoutMillis
 */
data class LlmEndpointConfig(
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val timeoutSeconds: Int,
) {
    /** 端点是否已具备最小可用配置（缺任一必填项则 AI 工具走本地规则降级） */
    val isConfigured: Boolean get() = apiKey.isNotBlank() && modelId.isNotBlank()

    companion object {
        /**
         * 端点快照**单点构造**（S4）——四处消费（AiSettingsViewModel init / KoogAgentRunner
         * refreshEndpoint / KnowledgeBase embedder provider）同源读持久层，字段增删只改本函数
         * （漏读一处即缓存 key 漂移/行为分叉）。
         * [modelIdOverride] 供 embedding 链复用（`modelId = ""` 占位是**刻意设计**：
         * 向量模型独立声明，不掺进 executor 缓存 key）——非 override 场景恒读 [LlmConfigStore.getModelId]。
         */
        suspend fun read(modelIdOverride: String? = null): LlmEndpointConfig = LlmEndpointConfig(
            baseUrl = LlmConfigStore.getBaseUrl(),
            apiKey = LlmConfigStore.getApiKey(),
            modelId = modelIdOverride ?: LlmConfigStore.getModelId(),
            timeoutSeconds = LlmConfigStore.getTimeoutSeconds(),
        )
    }
}

/**
 * Koog executor / model 的进程级缓存门面（object 非 VM 字段：VM 随组合销毁重建，
 * executor 缓存挂 VM 会每次进页重建——进程级 object + 配置快照做 key，先例 LarkBridgeManager）。
 * 缓存失效：key 即 [LlmEndpointConfig] 相等性——任一字段变更自动 close 旧 executor
 * （AutoCloseable）并重建。
 * HTTP 栈：http-client-java 经 SPI 自动发现 → JDK HttpClient（不引 Ktor）；超时走
 * Koog 内建 [ConnectionTimeoutConfig]——JDK HttpClient 自身无请求级超时，此层不可省。
 */
object LlmClientFactory {

    /** 测试接缝：注入 fake executor，单测零真实网络 */
    internal var executorProvider: (LlmEndpointConfig) -> MultiLLMPromptExecutor = { cfg ->
        MultiLLMPromptExecutor(buildOpenAIClient(cfg))
    }

    private val lock = Any()
    private var cached: Pair<LlmEndpointConfig, MultiLLMPromptExecutor>? = null
    private var cachedHttp: Pair<LlmEndpointConfig, KoogHttpClient>? = null

    fun executor(config: LlmEndpointConfig): MultiLLMPromptExecutor = synchronized(lock) {
        cached?.takeIf { it.first == config }?.second
            ?: run {
                cached?.second?.close()
                executorProvider(config).also { cached = config to it }
            }
    }

    fun httpClient(config: LlmEndpointConfig): KoogHttpClient = synchronized(lock) {
        cachedHttp?.takeIf { it.first == config }?.second
            ?: run {
                cachedHttp?.second?.close()
                buildKoogHttpClient(config).also { cachedHttp = config to it }
            }
    }

    fun model(modelId: String): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = modelId,
        capabilities = listOf<LLMCapability>(
            LLMCapability.Completion,
            LLMCapability.OpenAIEndpoint.Completions,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.Tools,
        ),
    )

    /** 进程退出清理（App DisposableEffect 旁调用；JVM 退出本会兜底，此为卫生性收口） */
    fun invalidate() = synchronized(lock) {
        cached?.second?.close()
        cached = null
        cachedHttp?.second?.close()
        cachedHttp = null
    }

    fun embeddingModel(modelId: String): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = modelId,
        capabilities = listOf<LLMCapability>(LLMCapability.Embed),
    )

    fun embeddingClient(config: LlmEndpointConfig): OpenAILLMClient = buildOpenAIClient(config)

    /** 与 executor 同源配置的 HTTP 客户端（模型清单拉取用；见 [httpClient]） */
    private fun buildKoogHttpClient(cfg: LlmEndpointConfig): KoogHttpClient =
        JsonContentTypeFixHttpClientFactory().create(
            clientName = "Toolbelt/AiSettings",
            // 规范化：剥掉用户可能填的 /v1 版本段（Koog 的路径常量自带该段，详见 LlmEndpointUrls）
            baseUrl = LlmEndpointUrls.normalizedBaseUrl(cfg.baseUrl),
            headers = emptyMap(),
            queryParameters = emptyMap(),
            // 模型列表是轻量元数据请求，无需挂满用户配置的对话超时
            requestTimeoutMillis = CATALOG_TIMEOUT_MILLIS,
            connectTimeoutMillis = CATALOG_TIMEOUT_MILLIS,
            socketTimeoutMillis = CATALOG_TIMEOUT_MILLIS,
            json = Json { ignoreUnknownKeys = true },
        )

    private fun buildOpenAIClient(cfg: LlmEndpointConfig): OpenAILLMClient = OpenAILLMClient(
        apiKey = cfg.apiKey,
        settings = OpenAIClientSettings(
            // 规范化与探测/拉取同源：两套 baseUrl 惯例都收敛到 Koog 语义
            baseUrl = LlmEndpointUrls.normalizedBaseUrl(cfg.baseUrl),
            timeoutConfig = ConnectionTimeoutConfig(requestTimeoutMillis = cfg.timeoutSeconds * 1000L),
        ),
        // 🔴 必须显式注入修正工厂：Koog 1.2.0 的 http-client-java 对 String body 硬编码
        //    Content-Type: text/plain（发 JSON 却声明纯文本）——严格校验该头的网关直接 404。
        //    详见 JsonContentTypeFixHttpClient 的类注释。
        httpClientFactory = JsonContentTypeFixHttpClientFactory(),
    )

    /** 模型清单请求超时（元数据请求，与用户配置的对话超时解耦） */
    private const val CATALOG_TIMEOUT_MILLIS = 15_000L
}
