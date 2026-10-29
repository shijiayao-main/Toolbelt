package com.jiaoay.easy_dev_toolbox.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [LlmEndpointUrls] 规范化测试。
 *
 * 钉死真机踩过的坑：用户按 OpenAI 官方 SDK 惯例填写含 `/v1` 的 baseUrl，
 * 而 Koog 的路径常量自带 `v1/` 段，拼接后成 `/v1/v1/chat/completions`
 * ——宽容网关仍返 200 故不显形，严格网关 404。
 */
class LlmEndpointUrlsTest {

    @Test
    fun `strips trailing v1 so koog path constant composes correctly`() {
        assertEquals(
            "https://llm-gateway.example.com",
            LlmEndpointUrls.normalizedBaseUrl("https://llm-gateway.example.com/v1"),
        )
    }

    @Test
    fun `strips trailing slash before v1`() {
        assertEquals("https://api.deepseek.com", LlmEndpointUrls.normalizedBaseUrl("https://api.deepseek.com/v1/"))
    }

    @Test
    fun `keeps base url without version segment as is`() {
        assertEquals("https://api.openai.com", LlmEndpointUrls.normalizedBaseUrl("https://api.openai.com"))
    }

    @Test
    fun `handles non root path gateway`() {
        // 网关挂在子路径下时同样只剥版本段，不动业务前缀
        assertEquals("https://host/gateway", LlmEndpointUrls.normalizedBaseUrl("https://host/gateway/v1"))
    }

    @Test
    fun `blank falls back to official endpoint`() {
        assertEquals("https://api.openai.com", LlmEndpointUrls.normalizedBaseUrl(""))
        assertEquals("https://api.openai.com", LlmEndpointUrls.normalizedBaseUrl("   "))
        assertEquals("https://api.openai.com", LlmEndpointUrls.normalizedBaseUrl("https://api.openai.com/v1"))
    }

    @Test
    fun `v1 only url falls back to official endpoint`() {
        // 退化输入不应产出空串
        assertEquals("https://api.openai.com", LlmEndpointUrls.normalizedBaseUrl("/v1"))
    }

    @Test
    fun `models endpoint resolves for both conventions`() {
        val expected = "https://host/v1/models"
        assertEquals(expected, LlmEndpointUrls.modelsEndpoint("https://host/v1"))
        assertEquals(expected, LlmEndpointUrls.modelsEndpoint("https://host"))
    }
}
