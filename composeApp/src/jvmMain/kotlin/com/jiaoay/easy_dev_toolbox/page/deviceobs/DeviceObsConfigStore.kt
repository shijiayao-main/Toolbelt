package com.jiaoay.easy_dev_toolbox.page.deviceobs

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager

/**
 * 设备观测聚合页配置仓库：子页选择跨启动持久。
 *
 * 对照先例：工具页（[com.jiaoay.easy_dev_toolbox.page.tools.ToolsConfigStore]）与
 * 智能体中心（last_page 带签恢复）的子签选择均持久化，同域口径统一——重度用
 * 文件浏览/应用排查的用户（设备排障常见动线）重启不再多一次点击。
 *
 * 经 :core 的 [AppDataStore] 进程唯一实例读写（feature/宿主域禁止自行
 * PreferenceDataStoreFactory）。读写样板继承 [DomainConfigStore] 共享骨架，
 * 测试缝留在本 object（见 [store]）。
 *
 * 越界语义：存值不在 0..[SUBPAGE_MAX_INDEX]（手工改坏持久值如 9）时消费方回 0
 * （观测台）——坏值是脏数据回默认而非就近夹取（ToolsConfigStore 同口径，
 * 刻意不用 coerceIn）。
 */
object DeviceObsConfigStore : DomainConfigStore() {

    /** 子页 index：0=观测台 / 1=文件 / 2=应用 / 3=布局（DeviceObsPage 四子页序） */
    private const val KEY_DEVICE_OBS_SELECTED_SUBPAGE = "device_obs_selected_subpage"

    /** 四子页上限（含）；越界值视为脏数据回观测台 */
    const val SUBPAGE_MAX_INDEX = 3

    /** 测试接缝：jvmTest 可整体替换为临时目录实例（对齐 ToolsConfigStore.store 既有约定） */
    internal var store = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store

    /**
     * 子页选择读取（默认 0 = 观测台）。
     * 越界防御在消费方（DeviceObsPage 初值合成 `if (v in 0..[SUBPAGE_MAX_INDEX]) v else 0`）
     * ——store 层透传原始存值，坏值回默认的判定与「注入缝值优先、回落持久值」
     * 收口在同一处。
     */
    suspend fun getSelectedSubpage(): Int = read(KEY_DEVICE_OBS_SELECTED_SUBPAGE, 0)

    /** 子页选择回写（切子页直写无 debounce——低频交互，对齐 last_page 口径） */
    suspend fun setSelectedSubpage(index: Int) = write(KEY_DEVICE_OBS_SELECTED_SUBPAGE, index)
}
