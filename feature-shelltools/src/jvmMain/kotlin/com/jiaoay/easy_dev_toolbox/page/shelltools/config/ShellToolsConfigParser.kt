package com.jiaoay.easy_dev_toolbox.page.shelltools.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 配置 JSON 与展示模型的双向转换。
 *
 * 子节点 body 按整型 type 手动路由（0=Normal/1=MultiSelect/2=Input/3=Chain），
 * 读（[parseChildBody]）与写（[encodeChildBody]）对称收敛在本解析器内，
 * 全管道禁止类自动多态方案（项目序列化规范）。
 */
object ShellToolsConfigParser {

    /** encodeDefaults 保证 type 鉴别字段始终输出，parse/encode 语义对称 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val configListSerializer = ListSerializer(ShellToolNodeConfig.serializer())

    // ── 解析：JSON 文本 → 展示节点树 ──

    /** 解析配置文本为展示节点树；JSON 非法或顶层非数组时返回空列表（兼容旧语义） */
    suspend fun parse(configJson: String): List<ToolNode> = withContext(Dispatchers.Default) {
        decodeConfigs(configJson)?.mapIndexed { index, config ->
            parseNode(config, index.toString())
        } ?: emptyList()
    }

    /**
     * 解析配置文本为 Config 列表；JSON 非法或顶层非数组返回 null。
     * 供调用方区分"解析失败"（null）与"合法空配置"（空列表）。
     */
    suspend fun decodeConfigs(configJson: String): List<ShellToolNodeConfig>? = withContext(Dispatchers.Default) {
        runCatching {
            json.decodeFromString(configListSerializer, configJson)
        }.getOrNull()
    }

    /** Config 列表 → 展示节点树。无 id 节点按树中路径确定性生成 id（重复加载稳定） */
    suspend fun toToolNodes(configs: List<ShellToolNodeConfig>): List<ToolNode> =
        withContext(Dispatchers.Default) {
            configs.mapIndexed { index, config ->
                parseNode(config, index.toString())
            }
        }

    private fun parseNode(config: ShellToolNodeConfig, pathPrefix: String): ToolNode {
        val name = config.name
        val id = config.id ?: pathPrefix

        return if (config.type == 0) {
            // 父节点
            val children = parseBodyList(config.body, id)
            ToolNode.Parent(
                id = id,
                name = name,
                children = children,
                isExpanded = false,
                isVisible = true,
            )
        } else {
            // 子节点
            ToolNode.Child(
                id = id,
                name = name,
                body = parseChildBody(config.body),
            )
        }
    }

    private fun parseBodyList(body: JsonElement?, parentPath: String): List<ToolNode> {
        if (body == null) return emptyList()
        return runCatching {
            val configs = json.decodeFromJsonElement(configListSerializer, body)
            configs.mapIndexed { index, childConfig ->
                parseNode(childConfig, "$parentPath.${childConfig.name}")
            }
        }.getOrDefault(emptyList())
    }

    // ── 子节点 body：整型 type 手动路由（读） ──

    /** 子节点 body 按整型 type 鉴别，解码为对应 ChildBody；未知 type 退化为空 Normal */
    fun parseChildBody(body: JsonElement?): ChildBody {
        if (body == null) return ChildBody.Normal(value = "", desc = "")

        val type = runCatching {
            (body as? JsonObject)?.get("type")?.jsonPrimitive?.intOrNull
        }.getOrNull() ?: 0

        return when (type) {
            0 -> {
                val normal = json.decodeFromJsonElement(NormalBody.serializer(), body)
                ChildBody.Normal(
                    value = normal.value,
                    desc = normal.desc,
                )
            }

            1 -> {
                val multi = json.decodeFromJsonElement(MultiSelectBody.serializer(), body)
                ChildBody.MultiSelect(
                    items = multi.list.map {
                        ChildBody.MultiSelect.MultiSelectItem(
                            name = it.name,
                            value = it.value,
                            desc = it.desc,
                        )
                    },
                )
            }

            2 -> {
                val input = json.decodeFromJsonElement(InputBody.serializer(), body)
                ChildBody.Input(
                    value = input.value,
                    params = input.list.map {
                        ChildBody.Input.InputParam(
                            name = it.name,
                            desc = it.desc,
                        )
                    },
                )
            }

            3 -> {
                val chain = json.decodeFromJsonElement(ChainBody.serializer(), body)
                ChildBody.Chain(
                    commands = chain.list,
                )
            }

            else -> ChildBody.Normal(value = "", desc = "")
        }
    }

    // ── 子节点 body：整型 type 手动路由（写，与 parseChildBody 对称） ──

    /** 将 ChildBody 编码为带整型 type 鉴别字段的 JsonElement */
    fun encodeChildBody(body: ChildBody): JsonElement = when (body) {
        is ChildBody.Normal -> json.encodeToJsonElement(
            NormalBody(type = 0, name = "", value = body.value, desc = body.desc)
        )

        is ChildBody.MultiSelect -> json.encodeToJsonElement(
            MultiSelectBody(
                type = 1,
                name = "",
                list = body.items.map {
                    MultiSelectItem(name = it.name, value = it.value, desc = it.desc)
                },
            )
        )

        is ChildBody.Input -> json.encodeToJsonElement(
            InputBody(
                type = 2,
                name = "",
                value = body.value,
                list = body.params.map {
                    InputParam(name = it.name, desc = it.desc)
                },
            )
        )

        is ChildBody.Chain -> json.encodeToJsonElement(
            ChainBody(type = 3, name = "", list = body.commands)
        )
    }
}
