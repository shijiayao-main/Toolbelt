package com.jiaoay.easy_dev_toolbox.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MCP 外放服务配置 key 单测（P5 openspec mcp-expose 任务 1.3）：
 * - 默认值契约：enabled 恒 false（design D6 不自动起）、端口 8765；
 * - key 回环：开关/端口读写；
 * - 校验域：越界存量值读侧回落缺省（手工编辑 DataStore 防御）。
 *
 * DataStore 接缝：LlmConfigStoreTest 同款临时目录 + LocalDataManager 隔离配方。
 */
class McpServerConfigKeyTest {

    @Test
    fun `enabled defaults to false and round trips`() = kotlinx.coroutines.runBlocking {
        withTempStore {
            assertFalse(LlmConfigStore.getMcpServerEnabled(), "缺省恒 false（不自动起 server）")
            LlmConfigStore.setMcpServerEnabled(true)
            assertTrue(LlmConfigStore.getMcpServerEnabled(), "开关 key 回环")
            LlmConfigStore.setMcpServerEnabled(false)
            assertFalse(LlmConfigStore.getMcpServerEnabled(), "关闭回环")
        }
    }

    @Test
    fun `port defaults to 8765 and round trips`() = kotlinx.coroutines.runBlocking {
        withTempStore {
            assertEquals(8765, LlmConfigStore.getMcpServerPort(), "缺省端口 8765")
            LlmConfigStore.setMcpServerPort(9100)
            assertEquals(9100, LlmConfigStore.getMcpServerPort(), "端口 key 回环")
        }
    }

    @Test
    fun `out-of-range stored port falls back to default`() = kotlinx.coroutines.runBlocking {
        withTempStore {
            // 直接写底层 key 模拟手工编辑 / 半写残留的越界存量值
            LlmConfigStore.store.setValue("mcp_server_port", 80)
            assertEquals(8765, LlmConfigStore.getMcpServerPort(), "越界存量（特权端口）读侧回落缺省")
            LlmConfigStore.store.setValue("mcp_server_port", 70000)
            assertEquals(8765, LlmConfigStore.getMcpServerPort(), "越界存量（超 TCP 上限）读侧回落缺省")
        }
    }

    @Test
    fun `port range constant covers only high ports`() {
        assertEquals(1024, LlmConfigStore.MCP_PORT_RANGE.first)
        assertEquals(65535, LlmConfigStore.MCP_PORT_RANGE.last)
        assertTrue(8765 in LlmConfigStore.MCP_PORT_RANGE)
        assertFalse(80 in LlmConfigStore.MCP_PORT_RANGE)
    }

    /** 临时 DataStore 接缝（原 store / user.home 恢复；🔴 删临时目录前必须先恢复原值——DataStore 在途写陷阱）。 */
    private suspend fun withTempStore(block: suspend () -> Unit) {
        val home = com.jiaoay.testfixtures.UserHomeIsolation("edt-mcp-test")
        home.redirect()
        val original = LlmConfigStore.store
        LlmConfigStore.store = com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager(
            dataStoreName = "test_mcp_${java.util.UUID.randomUUID()}",
        )
        try {
            block()
        } finally {
            LlmConfigStore.store = original
            home.restore()
        }
    }
}
