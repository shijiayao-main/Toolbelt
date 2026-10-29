package com.jiaoay.easy_dev_toolbox.core.llm

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 多源码目录 allowlist 的纯编解码（存储形态 = DataStore 单 key
 * `llm_agent_source_roots` 的 JSON 数组串，Preferences 只支持标量；照 [KoogUserStyleMap]
 * 先例：编解码收敛纯函数 object，可直测）。
 *
 * ## 语义
 * - 列表元素 = 目录绝对路径字符串（**不在此展开 ~、不 canonical 化**——展开与 canonical
 *   在消费点（ToolExecutionContext.withAllowedRoots 构造期）统一执行；此处只管存取形状。
 *   单点收敛：展开/canonical 逻辑只在围栏一处，避免「存展开值 vs 存原始值」双态漂移）；
 * - [decode] 坏值防御（手工改坏 / 半写崩溃残留）一律 `runCatching` 回落空表，绝不抛
 *   （Preferences 无事务，坏值不该炸蒸馏链装配）；
 * - [encode] 去空白、按路径字符串去重（后写胜）；**顺序保留**（列表序 = UI 展示序）。
 *
 * 消费链（🔴 桥接链恒不消费——出站旁路防线）：
 * - 读：桌面蒸馏链任务启动时现读（任务粒度快照——DataStore suspend 读，不跨任务缓存，
 *   无失效问题；热生效 = 新任务自动带新目录）；
 * - 写：AI 设置页「源码目录」SectionCard（整表提交）。
 */
object SourceRootsMap {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    /** 坏值防御：decode 失败回落空表（= 未配置任何源码目录）。 */
    fun decode(raw: String): List<String> =
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    /** 序列化整表：去空白 + 去重（后写胜）+ 保序。 */
    fun encode(roots: List<String>): String =
        json.encodeToString(serializer, roots.map { it.trim() }.filter { it.isNotBlank() }.distinct())

    /** 追加单条（已存在则移到末位——最近添加优先展示）。 */
    fun add(roots: List<String>, path: String): List<String> =
        (roots.filterNot { it == path.trim() } + path.trim()).filter { it.isNotBlank() }

    /** 删除单条。 */
    fun remove(roots: List<String>, path: String): List<String> =
        roots.filterNot { it == path }
}
