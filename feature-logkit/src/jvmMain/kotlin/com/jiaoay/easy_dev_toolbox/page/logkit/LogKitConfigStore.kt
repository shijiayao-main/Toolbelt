package com.jiaoay.easy_dev_toolbox.page.logkit

import com.jiaoay.easy_dev_toolbox.core.data.AppDataStore
import com.jiaoay.easy_dev_toolbox.core.data.DomainConfigStore
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager

/**
 * 日志工具域配置仓库（xlog 解密 / tag 过滤 / 文件分割）：key 与原 EasyDataManager
 * 的 KEY_XLOG_PRIVATE_KEY / KEY_LOG_TAG_* / KEY_FILE_SPLIT_SIZE_MB 完全一致，
 * 单一 DataStore 文件不变——存量用户配置零迁移。
 *
 * 经 :core 的 [AppDataStore] 进程唯一实例读写（feature 禁止自行 PreferenceDataStoreFactory）。
 * 读写样板继承 [DomainConfigStore] 共享骨架。
 */
object LogKitConfigStore : DomainConfigStore() {

    /**
     * 测试接缝：替换为临时目录实例（先例：[com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore.store] 同款）。
     * public 的原因：internal 跨模块不可见（消费方测试在 composeApp 等下游模块）；
     * set 仅测试语义，生产代码不得调用（[AppDataStore.store] 是进程唯一实例——
     * 生产调用 set 即进程内出现第二实例，同文件并发访问直接抛错）。
     * 注入受控 scope 的实例可让 DataStore 文件 IO 随测试调度同步落定（见
     * [LocalDataManager] 构造参数 `datastoreScope` 契约）。
     */
    var store: LocalDataManager = AppDataStore.store

    override val activeStore: LocalDataManager
        get() = store


    private const val KEY_XLOG_PRIVATE_KEY = "xlog_private_key"
    private const val KEY_LOG_TAG_REGEX = "log_tag_regex"
    private const val KEY_LOG_TAG_NAME = "log_tag_name"
    private const val KEY_FILE_SPLIT_SIZE_MB = "file_split_size_mb"

    // ── XLog ──

    suspend fun getXLogPrivateKey(): String = read(KEY_XLOG_PRIVATE_KEY, "")
    suspend fun setXLogPrivateKey(key: String) = write(KEY_XLOG_PRIVATE_KEY, key)

    // ── Log Tag 过滤 ──

    /** Tag 正则（用于匹配日志行中的 Tag），默认空 */
    suspend fun getLogTagRegex(): String = read(KEY_LOG_TAG_REGEX, "")
    suspend fun setLogTagRegex(regex: String) = write(KEY_LOG_TAG_REGEX, regex)

    /** 输出标记（用于输出文件命名），默认空 */
    suspend fun getLogTagName(): String = read(KEY_LOG_TAG_NAME, "")
    suspend fun setLogTagName(name: String) = write(KEY_LOG_TAG_NAME, name)

    // ── 文件分割 ──

    suspend fun getFileSplitSizeMb(): Int = read(KEY_FILE_SPLIT_SIZE_MB, 10)
    suspend fun setFileSplitSizeMb(sizeMb: Int) = write(KEY_FILE_SPLIT_SIZE_MB, sizeMb)
}
