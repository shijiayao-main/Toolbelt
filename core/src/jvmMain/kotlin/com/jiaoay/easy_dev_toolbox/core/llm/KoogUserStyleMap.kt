package com.jiaoay.easy_dev_toolbox.core.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * openId → styleId 映射条目（per-user 性格档）。
 * [styleId] 恒为**内置档 id**（per-user 不支持 custom——自定义是全局档专属）；
 * 「跟随全局」= 条目不存在，无哨兵值。
 */
@Serializable
data class KoogUserStyleEntry(val openId: String, val styleId: String)

/**
 * per-user 性格档映射的纯编解码（存储形态 = DataStore 单 key `llm_agent_style_user_map`
 * 的 JSON 数组串——Preferences 只支持标量，故映射表序列化为字符串）。
 *
 * 纯函数 object（对齐 [KoogAgentPromptPresets] 的可直测形态）：
 * - [decode] 失败（手工改坏 / 半写崩溃残留）一律 `runCatching` 回落空表，绝不抛——
 *   Preferences 无事务，坏值不该炸桥接；
 * - [encode] 时 `associateBy` 去重（同 openId 后写胜）；
 * - 查询/增删全是 List 纯函数，`KoogUserStyleMapTest` 直测。
 *
 * 消费链：`KoogAgentRunner.refreshEndpoint` 快照读 → `systemPromptFor` 按 senderOpenId
 * 现解析（1Hz 回路 + run 前重刷双路 HOT，≤1s 生效）；UI 写入经用户详情页
 * （`feature-larkbridge` 统计页）整表提交。
 */
object KoogUserStyleMap {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(KoogUserStyleEntry.serializer())

    /** 坏值防御：decode 失败回落空表（= 全员跟随全局）。 */
    fun decode(raw: String): List<KoogUserStyleEntry> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    /** 序列化整表（同 openId 后写胜——去重后写）。 */
    fun encode(entries: List<KoogUserStyleEntry>): String =
        json.encodeToString(serializer, entries.associateBy { it.openId }.values.toList())

    /** 映射查询；返回 null = 跟随全局。 */
    fun styleIdOf(entries: List<KoogUserStyleEntry>, openId: String): String? =
        entries.lastOrNull { it.openId == openId }?.styleId

    /** upsert 单条（存在则替换）。 */
    fun upsert(entries: List<KoogUserStyleEntry>, openId: String, styleId: String): List<KoogUserStyleEntry> =
        entries.filterNot { it.openId == openId } + KoogUserStyleEntry(openId, styleId)

    /** 删除单条（= 恢复跟随全局）。 */
    fun remove(entries: List<KoogUserStyleEntry>, openId: String): List<KoogUserStyleEntry> =
        entries.filterNot { it.openId == openId }
}
