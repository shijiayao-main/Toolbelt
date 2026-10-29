package com.jiaoay.easy_dev_toolbox.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.firstOrNull

// 本层不得叠 withContext(Dispatchers.IO) 之类的真实调度器 hop：DataStore 内部读经
// readState、写经 writeActor 恒派发到自身 scope（LocalDataManager 的 datastoreScope
// 注入缝可替换），再叠一层会形成第二个不可注入的调度器——测试注入受控 scope 后
// 仍有真实 IO 线程续体与测试调度器的竞态。本层剩余工作（key 解析、Preferences map
// 取值）是纯内存操作，无阻塞面。

public suspend inline fun <reified T> DataStore<Preferences>.getValue(
    key: String,
    defaultValue: T,
): T {
    if (key.isEmpty()) {
        return defaultValue
    }

    val preferencesKey =
        getKey<T>(key = key, value = defaultValue) ?: return defaultValue
    val value = data.firstOrNull()?.get(preferencesKey)
    return value ?: defaultValue
}

/**
 * key 是否已**显式写入**（区别于「读出的值等于默认值」）。
 *
 * 一次性迁移需要判定「存量存档从未写过该 key」：读值无法区分「未写入」与「写入过默认值」
 * （两者都返回默认值），故按 Preferences 的 key 名做存在性判定。
 * 只读不写内存缓存——避免以哨兵默认值污染 [getValue] 的写穿缓存
 * （缓存命中优先于默认值，哨兵值会顶掉真实默认值）。
 */
public suspend fun DataStore<Preferences>.containsKey(key: String): Boolean {
    if (key.isEmpty()) {
        return false
    }
    val preferences: Preferences = data.firstOrNull() ?: return false
    return preferences.asMap().keys.any { it.name == key }
}

public suspend inline fun <reified T : Any> DataStore<Preferences>.setValue(
    key: String,
    value: T,
) {
    if (key.isEmpty()) {
        return
    }

    val preferencesKey = getKey<T>(key = key, value = value) ?: return
    edit { preferences ->
        preferences[preferencesKey] = value
    }
}

@PublishedApi
internal inline fun <reified T> getKey(key: String, value: T): Preferences.Key<T>? {
    if (key.isEmpty()) {
        return null
    }

    val key = when (value) {
        is Int -> intPreferencesKey(key)
        is Double -> doublePreferencesKey(key)
        is String -> stringPreferencesKey(key)
        is Boolean -> booleanPreferencesKey(key)
        is Float -> floatPreferencesKey(key)
        is Long -> longPreferencesKey(key)
        else -> {
            if (value is Set<*>) {
                stringSetPreferencesKey(key)
            } else {
                null
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    return key as? Preferences.Key<T>
}
