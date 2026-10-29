package com.jiaoay.easy_dev_toolbox.page.shelltools.config

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsConfigRepository
import easydevtoolbox.feature.shelltools.generated.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内置默认配置文本的读取来源抽象，供 [ShellToolsConfigRepository] 注入（单测可替换）。
 */
fun interface DefaultConfigProvider {
    /** 返回默认配置 JSON 文本；读取失败返回 null（调用方降级空列表） */
    suspend fun read(): String?
}

/**
 * 从 composeResources 读取内置默认配置（composeResources/files/shell_tools_default.json）。
 *
 * - 成功读取后进程内缓存，避免重复 IO
 * - 读取失败记日志并返回 null（下次重试，保留自愈可能）；调用方降级为空默认列表
 */
object ComposeDefaultConfigProvider : DefaultConfigProvider {

    private const val TAG = "DefaultConfigProvider"
    private const val RESOURCE_PATH = "files/shell_tools_default.json"

    @Volatile
    private var cachedText: String? = null

    @Volatile
    private var cacheLoaded = false

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (cacheLoaded) return@withContext cachedText
        val text = runCatching {
            Res.readBytes(RESOURCE_PATH).decodeToString()
        }.onFailure { e ->
            JLog.e(TAG) { "Failed to read bundled default config, fallback to empty: ${e.message}" }
        }.getOrNull()
        if (text != null) {
            synchronized(this) {
                cachedText = text
                cacheLoaded = true
            }
        }
        text
    }
}
