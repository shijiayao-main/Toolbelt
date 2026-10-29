package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 子页选择跨启动持久守护：DeviceObsConfigStore 读写往返验收
 * （ToolsConfigStoreTest 同款配方）。
 *
 * - 跨启动 round-trip：setSelectedSubpage(2) → 读回 2（DataStore 文件层持久由
 *   LocalDataManager 既有契约承载）；
 * - 默认值：全新 store 读 0（观测台——未选择过的用户回默认子页）；
 * - store 层透传语义：坏值（9）写入读回仍是 9——越界回 0 的判定收口在
 *   DeviceObsPage 消费方（初值合成按 `DeviceObsConfigStore.SUBPAGE_MAX_INDEX`
 *   判界），store 不猜语义。
 *
 * 隔离 DataStore + 临时 user.home 构造（先例：ToolsConfigStoreTest）。
 */
class DeviceObsConfigStoreTest {

    /** user.home 隔离（共享件 UserHomeIsolation） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-deviceobsconfig-test")

    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        originalStore = DeviceObsConfigStore.store
        DeviceObsConfigStore.store = LocalDataManager(dataStoreName = "test_deviceobsconfig_${UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
        DeviceObsConfigStore.store = originalStore
    }

    @Test
    fun `selected subpage defaults to console on fresh store`() = runBlocking {
        assertEquals(0, DeviceObsConfigStore.getSelectedSubpage(), "默认 = 0（观测台——未选择过的用户）")
    }

    @Test
    fun `out-of-range value passes through store untouched`() = runBlocking {
        // store 层透传：坏值 9 写入读回仍是 9——「越界回观测台」的判定收口在
        // DeviceObsPage 消费侧（刻意不用 coerceIn——坏值是脏数据须回默认 0 而非就近夹取）
        DeviceObsConfigStore.setSelectedSubpage(9)
        assertEquals(9, DeviceObsConfigStore.getSelectedSubpage(), "坏值槽层透传（消费方 DeviceObsPage 回 0）")
        assertEquals(3, DeviceObsConfigStore.SUBPAGE_MAX_INDEX, "四子页上限 index = 3（0..3 共四子页）")
    }

    @Test
    fun `selected subpage write then read back`() = runBlocking {
        // 验收语义：切到「应用」子页后（重启）进设备观测即落在应用子页——
        // 写读一致是 DataStore 持久链的最小可断言面
        DeviceObsConfigStore.setSelectedSubpage(2)
        assertEquals(2, DeviceObsConfigStore.getSelectedSubpage(), "切到「应用」子页（index 2）写读一致")
    }

    @Test
    fun `selected subpage overwrite updates the stored value`() = runBlocking {
        DeviceObsConfigStore.setSelectedSubpage(3)
        DeviceObsConfigStore.setSelectedSubpage(1)
        assertEquals(1, DeviceObsConfigStore.getSelectedSubpage(), "切子页覆写语义（后值覆盖前值）")
    }
}
