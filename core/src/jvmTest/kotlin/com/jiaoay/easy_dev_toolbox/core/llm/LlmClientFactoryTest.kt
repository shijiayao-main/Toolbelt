package com.jiaoay.easy_dev_toolbox.core.llm

import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LlmClientFactory 缓存语义（executorProvider 注入 fake，零真实网络）：
 * - 同配置命中缓存（provider 只调用一次）；
 * - 任一字段变更重建并 close 旧实例（AutoCloseable 失效）；
 * - invalidate 清缓存并 close；
 * - model() 的能力组合钉死（Completions 强制 chat/completions + Schema.JSON.Standard + Tools）。
 */
class LlmClientFactoryTest {

    /** fake executor：记录 close 次数，供失效断言（构造签名对齐 MultiLLMPromptExecutor(Map)） */
    private class FakeExecutor(val id: Int) : MultiLLMPromptExecutor(emptyMap()) {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val created = mutableListOf<FakeExecutor>()
    private val originalProvider = LlmClientFactory.executorProvider

    @AfterTest
    fun tearDown() {
        // 还原 provider 并清缓存，防跨用例泄漏
        LlmClientFactory.executorProvider = originalProvider
        LlmClientFactory.executorProvider = originalProvider // 双保险：先还原再 invalidate 触发 close
        LlmClientFactory.invalidate()
    }

    private fun installProvider() {
        LlmClientFactory.executorProvider = { created.add(FakeExecutor(created.size)); created.last() }
    }

    private fun cfg(baseUrl: String = "https://gw.example.com", key: String = "sk-1", model: String = "m1", timeout: Int = 60) =
        LlmEndpointConfig(baseUrl, key, model, timeout)

    @Test
    fun `same config reuses cached executor without rebuilding`() {
        installProvider()
        val first = LlmClientFactory.executor(cfg())
        val second = LlmClientFactory.executor(cfg())
        assertEquals(1, created.size, "同配置应命中缓存，provider 只调用一次")
        assertTrue(first === second, "两次获取应是同一实例")
    }

    @Test
    fun `config change rebuilds and closes the old executor`() {
        installProvider()
        val old = LlmClientFactory.executor(cfg())
        val newOne = LlmClientFactory.executor(cfg(model = "m2"))

        assertEquals(2, created.size, "配置变更应重建")
        assertTrue(created[0].closed, "旧 executor 应被 close（AutoCloseable 失效）")
        assertTrue(!created[1].closed, "新 executor 不应被 close")
        assertTrue(old !== newOne)
    }

    @Test
    fun `invalidate closes current executor and clears cache`() {
        installProvider()
        LlmClientFactory.executor(cfg())
        LlmClientFactory.invalidate()

        assertTrue(created[0].closed, "invalidate 应 close 当前实例")
        assertEquals(1, created.size, "invalidate 后再取应重建")
        LlmClientFactory.executor(cfg())
        assertEquals(2, created.size)
    }

    @Test
    fun `model declares completions json schema and tools capabilities`() {
        val model = LlmClientFactory.model("deepseek-chat")
        assertEquals("deepseek-chat", model.id)
        assertTrue(model.supports(LLMCapability.OpenAIEndpoint.Completions), "强制 /v1/chat/completions 路由")
        assertTrue(model.supports(LLMCapability.Schema.JSON.Standard), "executeStructured 结构化输出")
        assertTrue(model.supports(LLMCapability.Tools), "工具循环")
    }

    @Test
    fun `isConfigured requires both api key and model id`() {
        assertTrue(cfg().isConfigured)
        assertTrue(!cfg(key = "").isConfigured, "缺 key 不可用")
        assertTrue(!cfg(model = " ").isConfigured, "缺模型不可用")
    }
}
