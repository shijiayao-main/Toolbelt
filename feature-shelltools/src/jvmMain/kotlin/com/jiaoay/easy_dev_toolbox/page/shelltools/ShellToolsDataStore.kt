package com.jiaoay.easy_dev_toolbox.page.shelltools

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager

/**
 * Shell 域配置仓库：key 与原 EasyDataManager 的 KEY_SHELL_*
 * 完全一致，单一 DataStore 文件不变——存量用户配置零迁移。
 *
 * adb 可执行文件路径不再是可配置项：内置 adb 是唯一来源，端点由 :core 设备域的
 * `DeviceHub.currentEndpoint()` 统一解析，本仓库不持有任何 adb 路径配置。
 *
 * 经 :core 的 [AppDataStore] 进程唯一实例读写（feature 禁止自行 PreferenceDataStoreFactory）。
 * 读写样板继承 [DomainConfigStore] 共享骨架（read/write 委托对的唯一真源），
 * 测试缝留在本 object（见 [store]）。
 */
object ShellToolsDataStore : DomainConfigStore() {

    // internal：ShellHistoryManager 注入独立 store 时复用同一 key（保持 key 单一来源）
    internal const val KEY_SHELL_HISTORY = "shell_command_history"
    internal const val KEY_SHELL_HISTORY_SORT = "shell_history_sort_mode"

    /** 测试接缝：jvmTest 可整体替换为临时目录实例（对齐 EasyDataManager.store 既有约定） */
    internal var store: LocalDataManager = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store

    suspend fun getShellHistory(): String = read(KEY_SHELL_HISTORY, "")
    suspend fun setShellHistory(json: String) = write(KEY_SHELL_HISTORY, json)

    suspend fun getShellHistorySortMode(): String = read(KEY_SHELL_HISTORY_SORT, "MostUsed")
    suspend fun setShellHistorySortMode(mode: String) = write(KEY_SHELL_HISTORY_SORT, mode)

}
