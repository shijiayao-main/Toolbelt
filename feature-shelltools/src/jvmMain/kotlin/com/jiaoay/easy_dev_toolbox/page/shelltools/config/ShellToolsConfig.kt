package com.jiaoay.easy_dev_toolbox.page.shelltools.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON 配置模型，与 shell_tools.json / shell_tools_custom.json 1:1 映射
 */

/**
 * @param body 父节点 body 为递归子节点列表、子节点 body 按整型 type 鉴别，
 *   kotlinx 类鉴别器仅支持字符串，故保真为 [JsonElement]，由解析层二次判别。
 * @param id 节点稳定标识：主配置/默认配置节点缺省（解析时按路径确定性生成）；
 *   自定义工具/分组节点持久化 UUID（读入迁移补齐），改名不改变 id。
 */
@Serializable
data class ShellToolNodeConfig(
    val type: Int,
    val name: String,
    val body: JsonElement? = null,
    val id: String? = null,
)

// ── 父节点 body: 子节点列表 ──
// body 类型为 List<ShellToolNodeConfig>，由解析层从 JsonElement 还原

// ── 子节点 body 变体 ──

@Serializable
data class NormalBody(
    val type: Int = 0,
    val name: String = "",
    val value: String = "",
    val desc: String = "",
)

@Serializable
data class MultiSelectBody(
    val type: Int = 1,
    val name: String = "",
    val list: List<MultiSelectItem> = emptyList(),
)

@Serializable
data class MultiSelectItem(
    val name: String = "",
    val value: String = "",
    val desc: String = "",
)

@Serializable
data class InputBody(
    val type: Int = 2,
    val name: String = "",
    val value: String = "",
    val list: List<InputParam> = emptyList(),
)

@Serializable
data class InputParam(
    val name: String = "",
    val desc: String = "",
)

@Serializable
data class ChainBody(
    val type: Int = 3,
    val name: String = "",
    val list: List<String> = emptyList(),
)
