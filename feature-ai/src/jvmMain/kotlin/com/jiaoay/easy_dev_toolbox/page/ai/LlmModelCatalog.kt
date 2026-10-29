package com.jiaoay.easy_dev_toolbox.page.ai

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointUrls

/**
 * 可用模型清单拉取（OpenAI 兼容 `GET /v1/models`）。
 *
 * ## 为何自建而不走 Koog 的 `models()`
 * Koog 1.2.0 **已实现** `OpenAILLMClient.models()`（源码 OpenAILLMClient.kt:634），但其结果
 * 对第三方网关不可直接用：它按静态表 `OpenAIModels.modelsById()` 匹配已知模型、
 * 未命中者返回 `LLModel(provider, id)`——**零 capabilities**。静态表只含 OpenAI 官方
 * 模型（gpt-5.x / o3 等），第三方网关模型（如自建网关的别名）必然未命中 ⇒ 拿到零能力
 * 模型 ⇒ 喂给 agent 会抛 `IllegalArgumentException: Model X does not support completion`。
 * 故此处只拉**模型 ID 字符串**，选中后经 [LlmClientFactory.model] 统一构造带完整能力清单
 * 的 LLModel——UI 需要的也只是 ID 列表。
 *
 * ## 复用 Koog 的 HTTP 栈而非新起 HttpClient
 * 经 [KoogHttpClient] 复用已配置的超时与 SPI 选出的 JDK HttpClient 实现，
 * 与聊天请求同源——避免第二套连接池与超时语义（亦让 Content-Type 修正层统一生效）。
 *
 * ## 协议形态
 * `{"object":"list","data":[{"id":"…","owned_by":"…"}, …]}`；
 * 部分网关额外给 `created` / `object`，此处只取 `id`（其余字段对本 UI 无意义）。
 * 解析失败（非预期 JSON 结构）显式报错而非静默返回空列表——空列表与「拉取失败」
 * 在 UI 上必须有截然不同的文案，否则用户会把网关异常误读为「确实没有模型」。
 */
internal object LlmModelCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 拉取可用模型 ID 列表（已去重、按字典序排序，便于 UI 稳定展示）。
     *
     * @throws Exception 网络失败、鉴权失败（HTTP 非 2xx）、或响应结构非预期
     */
    suspend fun fetch(client: KoogHttpClient, rawBaseUrl: String, apiKey: String): List<String> =
        withContext(Dispatchers.IO) {
            val endpoint = LlmEndpointUrls.modelsEndpoint(rawBaseUrl)
            // get 的 path 直传绝对 URL：KoogHttpClient.resolvePath 对绝对地址原样采用
            // （URI.create(path).isAbsolute → return path），避免与 baseUrl 二次拼接
            val body: String = client.get(
                path = endpoint,
                responseType = String::class,
                headers = buildMap {
                    if (apiKey.isNotBlank()) put("Authorization", "Bearer ${apiKey.trim()}")
                },
            )
            parseModelIds(body)
        }

    /** 从 `/v1/models` 响应体提取模型 ID；结构不符时抛 [IllegalStateException] */
    internal fun parseModelIds(body: String): List<String> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw IllegalStateException("模型列表响应不是 JSON 对象：${body.take(200)}", it) }

        // 兼容 data 为数组的标准形态
        val data = root["data"] as? JsonArray
            ?: throw IllegalStateException("模型列表响应缺少 data 数组：${body.take(200)}")

        val ids = data.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            item["id"]?.jsonPrimitiveOrNull()
        }
        return ids.filter { it.isNotBlank() }.distinct().sorted()
    }

    /**
     * 取字符串形态的原始值；非字符串（数字 `123`、`null`、布尔）一律返回 null。
     *
     * 🔴 不可直接用 `jsonPrimitive.content`：它会把 `123` 渲染成 `"123"`、把 JsonNull
     * 渲染成 `"null"`，垃圾数据会混进模型列表（单测 `tolerates non object entries and
     * non string ids` 实测捕获）。`isString` 是唯一可靠的字符串判定。
     */
    private fun JsonElement.jsonPrimitiveOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }
}
