package com.jiaoay.easy_dev_toolbox.page.logcat

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager

/**
 * logcat 观测域配置仓库：两 key（tag 过滤串 / 最低级别档）的字面量与历史宿主
 * （Shell 域配置仓库）完全一致，单一 DataStore 文件不变——存量用户过滤参数
 * 跨启动记忆零迁移，只换 object 宿主。
 *
 * 经 :core 的 [AppDataStore] 进程唯一实例读写（feature 禁止自行
 * PreferenceDataStoreFactory）。读写样板继承 [DomainConfigStore] 共享骨架
 * （read/write 委托对的唯一真源），测试缝留在本 object（见 [store]）。
 */
object LogcatConfigStore : DomainConfigStore() {

    /**
     * logcat 抓取面板的过滤查询串；空串 = 不过滤。载荷已从裸词串泛化为查询串
     * （[LogcatQueryParser] 语法分层：无算子裸串按旧语义回退）——旧存量值是合法
     * 新值子集，key 与语义零迁移。
     */
    internal const val KEY_LOGCAT_TAG_QUERY = "logcat_tag_query"

    /**
     * logcat 抓取面板的最低级别档：落盘形态 = 级别枚举的 name。
     * 空串 = null（无级别过滤）——与消费侧双向还原零歧义：null ↔ 空串、
     * 枚举 ↔ name，非法 name 回落 null。
     */
    internal const val KEY_LOGCAT_MIN_LEVEL = "logcat_min_level"

    /** 测试接缝：jvmTest 可整体替换为临时目录实例（对齐各域 ConfigStore 既有约定） */
    internal var store: LocalDataManager = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store

    suspend fun getLogcatTagQuery(): String = read(KEY_LOGCAT_TAG_QUERY, "")
    suspend fun setLogcatTagQuery(query: String) = write(KEY_LOGCAT_TAG_QUERY, query)

    /** 级别档读写以 name 字符串为载体（null 语义见 [KEY_LOGCAT_MIN_LEVEL]） */
    suspend fun getLogcatMinLevelName(): String = read(KEY_LOGCAT_MIN_LEVEL, "")
    suspend fun setLogcatMinLevelName(name: String) = write(KEY_LOGCAT_MIN_LEVEL, name)
}
