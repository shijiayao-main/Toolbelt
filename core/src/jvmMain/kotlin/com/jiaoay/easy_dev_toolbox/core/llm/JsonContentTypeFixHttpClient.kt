package com.jiaoay.easy_dev_toolbox.core.llm

import ai.koog.http.client.HttpClientFactoryResolver
import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * Koog `http-client-java` 的 Content-Type bug 修正层（真机定位）。
 *
 * ## 根因
 * `AbstractOpenAILLMClient.getResponse` 把请求体**序列化成 String** 后调用
 * `httpClient.post(path, requestBody = <String>, requestBodyType = String::class)`；
 * 而 `JavaKoogHttpClient.prepareRequestBody` 的 String 分支硬编码：
 * ```kotlin
 * if (requestBodyType == String::class) RequestBody(body, contentType = "text/plain")
 * else RequestBody(json.encodeToString(...), contentType = "application/json")
 * ```
 * 即**发 JSON body 却声明 `Content-Type: text/plain`**。
 *
 * 宽容网关（OpenAI 官方 / vLLM 默认）忽略该头故不显形；严格校验 Content-Type 的网关
 * （实测 llm-gateway）直接按路由不匹配返回 **404**——表现为「请求发出去了但 404、
 * errorBody 为空」，极易误判为 URL 拼接或路径错误（真机排查绕了远路）。
 *
 * ## 修法为何是装饰器而非配置
 * `JavaKoogHttpClient.headers` 注入的默认头**不生效**：`post`/`sse` 里调用的是
 * `mergeHeaders(instanceHeaders, mapOf("Content-Type" to preparedBody.contentType), perCallHeaders)`，
 * 中间那层由框架按 body 类型计算，会**覆盖** instance headers（`mergeHeaders` 是
 * LinkedHashMap 顺序 putAll，后者胜）。只有**末位 per-call headers** 能覆盖，
 * 而它由 `AbstractOpenAILLMClient` 内部产生，外部无从注入 ⇒ 只能在 `KoogHttpClient` 层包一层。
 *
 * ## 边界
 * 只改 Content-Type，不改 URL/body/鉴权/超时；`accept` 与其余 per-call headers 原样透传
 * （调用方显式指定的 Content-Type 亦不覆盖）。上游 Koog 修复后可整体移除本文件。
 */
internal class JsonContentTypeFixHttpClient(
    private val delegate: KoogHttpClient,
) : KoogHttpClient by delegate {

    override val clientName: String get() = delegate.clientName

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.post(path, requestBody, requestBodyType, responseType, parameters, withJsonContentType(headers))

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = delegate.sse(
        path, requestBody, requestBodyType, dataFilter, decodeStreamingResponse, processStreamingChunk,
        parameters, withJsonContentType(headers),
    )

    /** 补 `Content-Type: application/json`；调用方已显式指定则不覆盖（大小写不敏感） */
    private fun withJsonContentType(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) headers
        else headers + ("Content-Type" to "application/json")
}

/** 包裹 `http-client-java`（经 SPI 发现的默认实现）的工厂——见 [JsonContentTypeFixHttpClient] */
internal class JsonContentTypeFixHttpClientFactory(
    private val delegate: KoogHttpClient.Factory = HttpClientFactoryResolver.resolve(),
) : KoogHttpClient.Factory {

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = JsonContentTypeFixHttpClient(
        delegate.create(
            clientName, baseUrl, headers, queryParameters,
            requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis, json,
        )
    )
}
