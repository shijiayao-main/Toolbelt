package com.jiaoay.easy_dev_toolbox.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * @param dataStoreName 存储数据的文件名
 * @param datastoreDir 数据目录注入。DataStore 的落盘路径在本实例**首次读写**时才解析
 *   （androidx FileStorage 在 createConnection 时才调 produceFile），测试隔离必须在
 *   构造期把目录钉死——否则用例还原 `user.home` 后才执行的首操作会把文件落进真实
 *   数据目录。null = 沿用既有 lazy 语义（生产恒经 AppDataStore 单例，不注入）。
 * @param datastoreScope DataStore 内部读写协程的宿主 scope 注入。null = 库省缺
 *   （`Dispatchers.IO + SupervisorJob()`）。测试注入 `Dispatchers.Unconfined` 后文件 IO
 *   在调用线程同步落定：drain 即全静默，无真实 IO 线程续体与 `Dispatchers.resetMain`
 *   的竞态。注入约束（违反即 DataStore 永久不可用）：scope 必须长命自带独立 Job
 *   （如 SupervisorJob）——DataStore 把 scope 取消视为终态（Final）信号；也不得复用
 *   viewModelScope 等测试外生命周期 scope。
 */
class LocalDataManager(
    private val dataStoreName: String,
    private val datastoreDir: File? = null,
    private val datastoreScope: CoroutineScope? = null,
    /**
     * 历史文件名（改名迁移用）：首读写时若目标文件缺失而历史名文件（原样名与
     * 归一化 `.preferences_pb` 名皆查）在位，rename 到目标名。空表 = 无历史名。
     */
    private val legacyFileNames: List<String> = emptyList(),
) {

    private companion object {
        private const val PREFERENCES_FILE_EXTENSION = "preferences_pb"
        private const val TAG = "LocalDataManager"
    }

    private val localDataStore: DataStore<Preferences> by lazy {
        val corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { e ->
            // 🔴 corruptionHandler 统一兜底：preferences_pb 损坏 → ERROR 日志（含真实异常、可观测）
            //    → 清空重建（空文档），该 key 读链回落默认值——勿移除（移除后损坏异常会抛到调用点）。
            JLog.e(TAG, e) { "DataStore 文件损坏，已清空重建（$dataStoreName）：存量配置将回落默认值" }
            emptyPreferences()
        }
        val produceFile: () -> File = {
            val dir = datastoreDir ?: FileUtils.getDatastoreDir()
            // 🔴 终态判定：mkdirs 返回值不可信——目录已被并发方建好时返回
            //    false（语义是「是否由本次调用创建」，非「目录是否存在」）。测试滞留协程与
            //    初始化线程并发触发 lazy 时，后到者 mkdirs 撞已存在目录返回 false 会被误判
            //    失败（DockManagerToggleTest 实测 flaky 1/3）。判定条件 = 目录终态存在即可。
            if (dir.isDirectory.not() && dir.mkdirs().not() && dir.isDirectory.not()) {
                error("Failed to create datastore directory: ${dir.absolutePath}")
            }

            val targetFileName = normalizeDataStoreFileName(dataStoreName)
            val targetFile = File(dir, targetFileName)
            if (targetFile.exists().not()) {
                val legacyCandidates = buildList {
                    add(File(dir, dataStoreName))
                    legacyFileNames.forEach { name ->
                        add(File(dir, name))
                        add(File(dir, normalizeDataStoreFileName(name)))
                    }
                }
                legacyCandidates.firstOrNull { it.exists() }?.renameTo(targetFile)
            }
            targetFile
        }
        val injected = datastoreScope
        // null 时不传 scope——省缺由库自带（Dispatchers.IO + SupervisorJob），不镜像默认值
        if (injected == null) {
            PreferenceDataStoreFactory.create(
                corruptionHandler = corruptionHandler,
                produceFile = produceFile,
            )
        } else {
            PreferenceDataStoreFactory.create(
                corruptionHandler = corruptionHandler,
                scope = injected,
                produceFile = produceFile,
            )
        }
    }

    private fun normalizeDataStoreFileName(fileName: String): String {
        val extension = ".$PREFERENCES_FILE_EXTENSION"
        return if (fileName.endsWith(extension)) {
            fileName
        } else {
            "$fileName$extension"
        }
    }

    // 以下三者供 public inline 的 getValue/setValue 访问，@PublishedApi 保持二进制兼容
    @PublishedApi
    internal val cacheDataMap: MutableMap<String, Any> = HashMap()

    @PublishedApi
    internal val readWriteLock = ReentrantReadWriteLock()

    @PublishedApi
    internal fun dataStore(): DataStore<Preferences> = localDataStore

    /** 同步读内存缓存（不触盘；未加载返回 null）——失焦提交比较基准用。配置仓库骨架 API，仅供各域 ConfigStore 调用 */
    fun getCached(key: String): String? = readWriteLock.read {
        (cacheDataMap[key] as? String)
    }

    /**
     * 单 key 字符串值的变更流（冷流；跨模块 collect 的公开读口——[dataStore] 是 internal，
     * 事件化订阅须由此进）。
     *
     * 语义：DataStore 的 `data` 流在每次 edit 落盘后发射整表，本函数按 key map 出单值并去重
     * （同值不重复发射）；collect 即得当前值，无 key ⇒ 发射 null（与 [getValue] 的默认值
     * 语义对齐时由调用方 `orEmpty()` 归一）。空 key 返回空流（对齐 [getValue] 的空 key 卫语句）。
     *
     * 缓存一致性边界：本流**直读 DataStore 落盘真源、不经** [getValue] 的内存缓存
     * （cacheDataMap），也不回填缓存——不存在两套真源：写路径 [setValue] 先写穿缓存再落盘，
     * DataStore 文件是唯一持久化真源，本流发射的永远是已落盘值；缓存只服务 [getValue] 的
     * 同步快读，两条读路径对同一落盘文件恒一致（例外：[clear] 清盘不清缓存是既有语义，
     * 本流不放大——流侧始终反映落盘真值）。
     *
     * 协程/线程语义：冷流，多次 collect 各自独立重读、共享本实例的同一 DataStore
     * （无 StateFlow 缓存语义）；collect 在调用方作用域执行，DataStore 内部自管 IO 线程；
     * 文件损坏经 corruptionHandler 清空重建（发射空表 ⇒ 本 key 发射 null），不向订阅方抛异常。
     */
    fun valueFlow(key: String): Flow<String?> =
        if (key.isEmpty()) {
            emptyFlow()
        } else {
            dataStore().data
                .map { it[stringPreferencesKey(key)] }
                .distinctUntilChanged()
        }

    suspend fun hasKey(key: String): Boolean = dataStore().containsKey(key)

    /** 挂起读 + 写穿缓存回填（预热语义见实现内注释）。配置仓库骨架 API，仅供各域 ConfigStore 调用 */
    suspend inline fun <reified T> getValue(
        key: String,
        defaultValue: T,
    ): T {
        if (key.isEmpty()) {
            return defaultValue
        }
        readWriteLock.read {
            cacheDataMap[key]?.let {
                if (it is T) {
                    return it
                }
            }
        }

        val stored = dataStore().getValue(
            key = key,
            defaultValue = defaultValue,
        )
        // 写穿回填：挂起读结果写入内存缓存。cachedXxx() 失焦提交比较基准依赖此预热
        // （loadConfig 全量挂起读即完成预热）；缺失会导致「值未变化」判定永远失效，
        // 每次失焦都误判有变化 → persist + recreate 桥接（重建风暴）
        readWriteLock.write {
            (stored as? Any)?.let { cacheDataMap[key] = it }
        }
        return stored
    }

    /** 挂起写（先写穿缓存再落盘）。配置仓库骨架 API，仅供各域 ConfigStore 调用 */
    suspend inline fun <reified T : Any> setValue(
        key: String,
        value: T,
    ) {
        if (key.isEmpty()) {
            return
        }
        readWriteLock.write {
            cacheDataMap[key] = value
        }

        dataStore().setValue(key = key, value = value)
        hardenSecretFile()
    }

    suspend fun setValues(values: Map<String, Any>) {
        if (values.isEmpty()) {
            return
        }
        readWriteLock.write {
            values.forEach { (key, value) -> cacheDataMap[key] = value }
        }
        dataStore().edit { prefs ->
            values.forEach { (key, value) ->
                when (value) {
                    is Int -> prefs[androidx.datastore.preferences.core.intPreferencesKey(key)] = value
                    is Long -> prefs[androidx.datastore.preferences.core.longPreferencesKey(key)] = value
                    is Float -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(key)] = value
                    is Boolean -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(key)] = value
                    is String -> prefs[androidx.datastore.preferences.core.stringPreferencesKey(key)] = value
                    else -> error("不支持的偏好值类型：${value::class}")
                }
            }
        }
        hardenSecretFile()
    }

    suspend fun clear() {
        localDataStore.edit {
            it.clear()
        }
        hardenSecretFile()
    }

    // post-edit 硬化钩子：DataStore 以临时文件 + 原子替换落盘，新文件权限由进程 umask
    // 决定——密钥 preferences 文件必须写后即 600，不得敞到下次启动重扫。inline 写路径的
    // chmod 调用经此非 inline helper 承载。钩子漏斗外仍有残留写者（原子替换的 .tmp 中间
    // 文件、corruptionHandler 清空重建、legacy rename、直呼 dataStore().edit 的旁路），
    // 由启动期 SecretFilePermHardeningInitializer 幂等重扫兜底——钩子不替代扫描。
    @PublishedApi
    internal fun hardenSecretFile() {
        SecretFilePerms.harden600(targetFile())
    }

    private fun targetFile(): File =
        File(datastoreDir ?: FileUtils.getDatastoreDir(), normalizeDataStoreFileName(dataStoreName))
}
