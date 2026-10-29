package com.jiaoay.easy_dev_toolbox.page.shelltools.history

import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

/**
 * ShellHistoryItem 的 JSON 序列化/反序列化工具。
 * kotlinx.serialization 多态（类鉴别器 `type`），老数据零迁移直接兼容。
 */
object ShellHistorySerializer {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(ShellHistoryItem.serializer())

    fun serialize(items: List<ShellHistoryItem>): String {
        return json.encodeToString(listSerializer, items)
    }

    fun deserialize(json: String): List<ShellHistoryItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            this.json.decodeFromString(listSerializer, json)
        }.getOrElse { emptyList() }
    }
}
