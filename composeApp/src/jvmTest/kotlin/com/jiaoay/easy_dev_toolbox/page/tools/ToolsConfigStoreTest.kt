package com.jiaoay.easy_dev_toolbox.page.tools

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 子签选择跨启动持久守护：ToolsConfigStore 读写往返验收。
 *
 * - 跨启动 round-trip：setSelectedTab(5) → 读回 5（DataStore 文件层持久由
 *   LocalDataManager 既有契约承载——TimestampConfigStore 同款无跨实例重开测法，
 *   同文件多 DataStore 实例会炸 "multiple DataStores active"）；
 * - 默认值：全新 store 读 0（第 1 签 XLog 解密——未配置用户回默认签）；
 * - store 层透传语义：坏值（13）写入读回仍是 13——越界回 0 的判定收口在
 *   ToolsPage 消费方（初值合成序按 `ToolsConfigStore.TAB_MAX_INDEX` 判界），store 不猜语义
 *   （消费侧源码断言见 ToolPageWiringGuardTest）。
 *
 * 隔离 DataStore + 临时 user.home 构造（先例：LlmConfigStoreTest 临时目录隔离法）。
 */
class ToolsConfigStoreTest {

    /** user.home 隔离（共享件 UserHomeIsolation） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-toolsconfig-test")

    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        originalStore = ToolsConfigStore.store
        ToolsConfigStore.store = LocalDataManager(dataStoreName = "test_toolsconfig_${UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
        ToolsConfigStore.store = originalStore
    }

    @Test
    fun `selected tab defaults to first tab on fresh store`() = runBlocking {
        assertEquals(0, ToolsConfigStore.getSelectedTab(), "默认 = 0（第 1 签 XLog 解密——未配置用户）")
    }

    @Test
    fun `out-of-range value passes through store untouched`() = runBlocking {
        // store 层透传：坏值 13 写入读回仍是 13——「越界回第 1 签」的判定收口在
        // ToolsPage 消费侧（刻意不用 coerceIn——coerceIn(13)=12 落
        // 第 13 签，坏值是脏数据须回默认 0 而非就近夹取）
        ToolsConfigStore.setSelectedTab(13)
        assertEquals(13, ToolsConfigStore.getSelectedTab(), "坏值槽层透传（消费方 ToolsPage 回 0）")
        assertEquals(12, ToolsConfigStore.TAB_MAX_INDEX, "十三签上限 index = 12（0..12 共十三签）")
    }

    @Test
    fun `selected tab write then read back`() = runBlocking {
        // 验收语义：切到「日志对比」签后（重启）进入工具页即落在日志对比签——
        // 写读一致是 DataStore 持久链的最小可断言面
        ToolsConfigStore.setSelectedTab(5)
        assertEquals(5, ToolsConfigStore.getSelectedTab(), "切到「日志对比」签（index 5）写读一致")
    }

    @Test
    fun `selected tab overwrite updates the stored value`() = runBlocking {
        ToolsConfigStore.setSelectedTab(3)
        ToolsConfigStore.setSelectedTab(1)
        assertEquals(1, ToolsConfigStore.getSelectedTab(), "切签覆写语义（后值覆盖前值）")
    }
}
