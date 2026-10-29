package com.jiaoay.easy_dev_toolbox.core.llm

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [JsonContentTypeFixHttpClient] 守护测试。
 *
 * 钉死两件真机踩过的事（Koog 1.2.0）：
 * 1. **String body 必须带 `Content-Type: application/json`**——Koog 的 `http-client-java`
 *    对 `requestBodyType == String::class` 硬编码 `text/plain`（发 JSON 却声明纯文本），
 *    严格校验该头的网关直接 404（实测 llm-gateway，排查成本极高）；
 * 2. **调用方已指定的 Content-Type 不被覆盖**（装饰器只补齐、不夺权）。
 *
 * 用本地 [HttpServer] 承接，零真实网络。
 */
class JsonContentTypeFixHttpClientTest {

    private lateinit var server: HttpServer
    private val received = mutableListOf<String?>()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received += exchange.requestHeaders.getFirst("Content-Type")
            val body = """{"ok":true}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** 直通 delegate 的工厂：记录被装饰器改写后的 headers，再转发到本地 server */
    private fun recordingFactory(): KoogHttpClient.Factory = object : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json,
        ): KoogHttpClient = object : KoogHttpClient {
            override val clientName: String = "recording"
            override suspend fun <R : Any> get(
                path: String, responseType: KClass<R>,
                parameters: Map<String, String>, headers: Map<String, String>,
            ): R = error("unused")

            override suspend fun <T : Any, R : Any> post(
                path: String, requestBody: T, requestBodyType: KClass<T>, responseType: KClass<R>,
                parameters: Map<String, String>, headers: Map<String, String>,
            ): R {
                // 用 JDK HttpClient 原样发出装饰器传进来的 headers——即被测行为
                val req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + path))
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody as String))
                    .build()
                java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                @Suppress("UNCHECKED_CAST")
                return """{"ok":true}""" as R
            }

            override fun <T : Any, R : Any, O : Any> sse(
                path: String, requestBody: T, requestBodyType: KClass<T>,
                dataFilter: (String?) -> Boolean, decodeStreamingResponse: (String) -> R,
                processStreamingChunk: (R) -> O?,
                parameters: Map<String, String>, headers: Map<String, String>,
            ): kotlinx.coroutines.flow.Flow<O> = error("unused")

            override fun <T : Any> lines(
                path: String, requestBody: T, requestBodyType: KClass<T>,
                parameters: Map<String, String>, headers: Map<String, String>,
            ): kotlinx.coroutines.flow.Flow<String> = error("unused")

            override fun close() = Unit
        }
    }

    private fun clientWith(headers: Map<String, String>): KoogHttpClient =
        JsonContentTypeFixHttpClientFactory(recordingFactory())
            .create("test", baseUrl, headers, emptyMap(), 5_000, 5_000, 5_000, Json)

    @Test
    fun `string body gets json content type`() = runBlocking {
        val client = clientWith(emptyMap())
        client.post("/v1/chat/completions", """{"a":1}""", String::class, String::class)

        assertEquals(1, received.size)
        assertTrue(
            received.single()?.startsWith("application/json") == true,
            "String body 必须以 application/json 发出（Koog 原实现硬编码 text/plain → 严格网关 404），实收：${received.single()}",
        )
    }

    @Test
    fun `explicit per call content type is not overridden`() = runBlocking {
        val client = clientWith(emptyMap())
        // 显式 Content-Type 走 per-call headers（装饰器的作用面）——不得被夺权
        client.post(
            path = "/v1/chat/completions",
            requestBody = """{"a":1}""",
            requestBodyType = String::class,
            responseType = String::class,
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
        )

        assertEquals("application/json; charset=utf-8", received.single(), "调用方显式指定的 Content-Type 不应被覆盖")
    }

    @Test
    fun `non content type headers pass through`() = runBlocking {
        val client = clientWith(mapOf("X-Trace" to "abc"))
        client.post("/v1/chat/completions", """{"a":1}""", String::class, String::class)

        assertTrue(received.single()?.startsWith("application/json") == true, "其余头透传时仍须补 Content-Type")
    }
}
