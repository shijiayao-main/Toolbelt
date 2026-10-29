package com.jiaoay.easy_dev_toolbox.core.data

/**
 * 域配置仓库共享骨架：各域 ConfigStore（宿主域 EasyDataManager、各 feature 域
 * ConfigStore）同构 read/write 委托对的唯一真源。新增一个域配置仓库 = 继承本类 +
 * 声明 key 常量与一行 get/set，不再手抄读写样板。
 *
 * 读写通道 [activeStore]：默认恒为进程唯一实例 [AppDataStore.store]；带测试缝的
 * 子类将其 override 为缝 var——缝被整实例替换后，后续读写即时跟随新实例。
 * 测试缝本体不进本类：internal 跨模块不可见，缝必须留在各域 Store 自己的
 * object 内（既有隔离配法逐字依赖 `Store.store = LocalDataManager(临时实例)`
 * 形态）；无缝的域不 override，保持恒经 [AppDataStore]。
 */
abstract class DomainConfigStore {

    /** 当前生效的 store 通道：默认进程唯一实例，带测试缝的子类 override 为缝 var（见类 KDoc） */
    protected open val activeStore: LocalDataManager
        get() = AppDataStore.store

    protected suspend inline fun <reified T : Any> read(key: String, default: T): T =
        activeStore.getValue(key, default)

    protected suspend inline fun <reified T : Any> write(key: String, value: T) =
        activeStore.setValue(key, value)
}
