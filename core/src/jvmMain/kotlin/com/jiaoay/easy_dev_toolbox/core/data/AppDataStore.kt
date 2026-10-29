package com.jiaoay.easy_dev_toolbox.core.data

/**
 * 进程唯一 Preferences DataStore 持有者：所有域配置仓库共享此实例。
 *
 * 为什么必须唯一：同一 .preferences_pb 文件被多个 DataStore 实例打开会抛
 * "multiple DataStores active for the same file"（启动即崩）。feature 域配置仓库
 * （各 ConfigStore）一律经本对象取 store，禁止自行 PreferenceDataStoreFactory.create；
 * key 命名空间靠既有 key 前缀约定隔离（xlog_ / shell_ / lark_bridge_ 等）。
 */
object AppDataStore {

    /** DataStore 文件名（落盘 `~/.toolbelt/.datastore/toolbelt.preferences_pb`） */
    private const val DATA_STORE_NAME = "toolbelt"

    /**
     * 旧 DataStore 文件名（改名前的落点名）：构造期注入给 [LocalDataManager]，首读写时
     * 一次性 rename 到新名文件（存在旧名文件且新名文件缺失才执行——不双写不丢数据）。
     */
    private const val LEGACY_DATA_STORE_NAME = "easy_dev_toolbox"

    private var _store: LocalDataManager = LocalDataManager(
        dataStoreName = DATA_STORE_NAME,
        legacyFileNames = listOf(LEGACY_DATA_STORE_NAME),
    )

    /**
     * 进程唯一 store 实例（配置仓库骨架 API，仅供各域 ConfigStore 持有）。
     *
     * internal 可写接缝：jvmTest 以临时目录实例整体替换（对齐 EasyDataManager.store 既有约定）；
     * 生产代码不得重新赋值。公开只读经 [store]。
     */
    internal var storeForTest: LocalDataManager
        get() = _store
        set(value) {
            _store = value
        }

    val store: LocalDataManager
        get() = _store
}
