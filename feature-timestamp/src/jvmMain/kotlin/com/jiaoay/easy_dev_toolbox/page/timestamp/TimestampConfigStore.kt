package com.jiaoay.easy_dev_toolbox.page.timestamp

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager

/**
 * 时间戳域配置仓库：key 与原 EasyDataManager.KEY_TIMESTAMP_FORMAT 完全一致，
 * 单一 DataStore 文件（easy_dev_toolbox.preferences_pb）不变——存量用户配置零迁移。
 *
 * 经 :core 的 [AppDataStore] 进程唯一实例读写（feature 禁止自行 PreferenceDataStoreFactory）。
 * 读写样板继承 [DomainConfigStore] 共享骨架（read/write 委托对的唯一真源），
 * 测试缝留在本 object（见 [store]）。
 */
object TimestampConfigStore : DomainConfigStore() {

    private const val KEY_TIMESTAMP_FORMAT = "timestamp_format"

    /** 测试接缝：jvmTest 可整体替换为临时目录实例（对齐 EasyDataManager.store 既有约定） */
    internal var store = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store

    /**
     * 输出格式预设（存 TimestampFormat 枚举 name，默认 "LOG"）。
     * 恢复侧由调用方 `runCatching { valueOf }` 回退默认，对齐 shell-history-sort 的宽容恢复风格。
     */
    suspend fun getTimestampFormat(): String = read(KEY_TIMESTAMP_FORMAT, "LOG")
    suspend fun setTimestampFormat(format: String) = write(KEY_TIMESTAMP_FORMAT, format)
}
