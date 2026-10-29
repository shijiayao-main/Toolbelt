package com.jiaoay.easy_dev_toolbox.core.llm

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * LlmConfigStore 四 key 读写往返（对齐 EasyDataManagerLogFileEnabledTest 的临时目录隔离法）：
 * 以独立 LocalDataManager 实例（临时 user.home + 随机文件名）替换 [LlmConfigStore.store]，
 * 验证默认值与写读一致性——不触真实用户配置。
 */
class LlmConfigStoreTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-llm-test")

    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
                originalStore = LlmConfigStore.store
        LlmConfigStore.store = LocalDataManager(dataStoreName = "test_llm_${UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
        LlmConfigStore.store = originalStore
    }

    @Test
    fun `default values are blank url key model and 60s timeout`() = runBlocking {
        assertEquals("", LlmConfigStore.getBaseUrl(), "Base URL 默认空串（= 官方端点）")
        assertEquals("", LlmConfigStore.getApiKey())
        assertEquals("", LlmConfigStore.getModelId())
        assertEquals(60, LlmConfigStore.getTimeoutSeconds())
    }

    @Test
    fun `write then read back all four keys`() = runBlocking {
        LlmConfigStore.setBaseUrl("https://api.deepseek.com/v1")
        LlmConfigStore.setApiKey("sk-test-123")
        LlmConfigStore.setModelId("deepseek-chat")
        LlmConfigStore.setTimeoutSeconds(120)

        assertEquals("https://api.deepseek.com/v1", LlmConfigStore.getBaseUrl())
        assertEquals("sk-test-123", LlmConfigStore.getApiKey())
        assertEquals("deepseek-chat", LlmConfigStore.getModelId())
        assertEquals(120, LlmConfigStore.getTimeoutSeconds())
    }

    @Test
    fun `overwrite updates the stored value`() = runBlocking {
        LlmConfigStore.setModelId("gpt-4o-mini")
        LlmConfigStore.setModelId("qwen-plus")
        assertEquals("qwen-plus", LlmConfigStore.getModelId())
    }

    // ── 第 5 key：AI 工具总开关（2026-09-12；2026-09-18 改三态派生默认） ──

    @Test
    fun `ai enabled defaults to false on fresh store`() = runBlocking {
        // 🔴 全新库（未写过任何 key）：端点未配置 ⇒ 默认关闭。
        //    修复前恒 true，导致首启用户看到一个「没必要拨、也拨不开」的开关。
        assertEquals(false, LlmConfigStore.getAiEnabled())
    }

    @Test
    fun `ai enabled derives true when endpoint configured but switch never written`() = runBlocking {
        // 🔴 升级用户保入口：已配好端点、从未碰过开关 ⇒ 派生为开
        //    （旧 KDoc 担心的「入口静默消失」由派生判据精确保住）。
        LlmConfigStore.setApiKey("sk-test-123")
        LlmConfigStore.setModelId("deepseek-chat")
        assertEquals(true, LlmConfigStore.getAiEnabled())
    }

    @Test
    fun `explicitly disabled switch stays false despite configured endpoint`() = runBlocking {
        // 🔴 显式值恒优先于派生：关过开关的用户即使后来配好端点也保持关闭（用户意志不可被覆盖）。
        LlmConfigStore.setAiEnabled(false)
        assertEquals(false, LlmConfigStore.getAiEnabled())

        LlmConfigStore.setApiKey("sk-test-123")
        LlmConfigStore.setModelId("deepseek-chat")
        assertEquals(false, LlmConfigStore.getAiEnabled(), "显式 false 不得被端点派生覆盖")
    }

    @Test
    fun `ai enabled write then read back`() = runBlocking {
        LlmConfigStore.setAiEnabled(false)
        assertEquals(false, LlmConfigStore.getAiEnabled())

        LlmConfigStore.setAiEnabled(true)
        assertEquals(true, LlmConfigStore.getAiEnabled())
    }

    // ── 性格模式/安全约束 4 key（2026-09-14 · Koog agent 全局配置） ──

    @Test
    fun `agent style and safety default to default preset and blank custom`() = runBlocking {
        // 🔴 默认 "default" = 装完即温和友好档 + 默认安全档（修真机「总感觉要跟我吵架」）
        assertEquals("default", LlmConfigStore.getAgentStyleId())
        assertEquals("", LlmConfigStore.getAgentStyleCustom())
        assertEquals("default", LlmConfigStore.getAgentSafetyId())
        assertEquals("", LlmConfigStore.getAgentSafetyCustom())
    }

    @Test
    fun `agent style and safety write then read back`() = runBlocking {
        LlmConfigStore.setAgentStyleId("catgirl")
        LlmConfigStore.setAgentStyleCustom("用猫娘口吻回复")
        LlmConfigStore.setAgentSafetyId("custom")
        LlmConfigStore.setAgentSafetyCustom("只许读取文件")
        assertEquals("catgirl", LlmConfigStore.getAgentStyleId())
        assertEquals("用猫娘口吻回复", LlmConfigStore.getAgentStyleCustom())
        assertEquals("custom", LlmConfigStore.getAgentSafetyId())
        assertEquals("只许读取文件", LlmConfigStore.getAgentSafetyCustom())
    }

    // ── 流程域单轮预算（Quest 域 change 1 任务 3.1——缺省 40 节点） ──

    @Test
    fun `flow max iterations defaults to 40 and writes back`() = runBlocking {
        assertEquals(40, LlmConfigStore.getFlowMaxIterations(), "流程缺省 40 节点（≈20 工具轮——交互轮短于整场分析）")
        LlmConfigStore.setFlowMaxIterations(60)
        assertEquals(60, LlmConfigStore.getFlowMaxIterations())
    }
}
