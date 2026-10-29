package com.jiaoay.lark.bridge.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 路由种类（[CDS §4.1]）。jsonValue 即 sealed 多态的 classDiscriminator="kind" 取值。
 * 数组顺序 == 路由优先级：PipelineRouter 按数组序遍历 routes.filter{enabled}，firstOrNull{matches} 命中即停。
 */
enum class RouteKind(val jsonValue: String) {
    SYSTEM("system"),
    COMMAND("command"),
    AGENT("agent"),
    FILE("file"),
}

/**
 * 路由规则 sealed 层级（[CDS §4.2]）。多态判别字段为 "kind"（由 BridgeJson 统一配置 classDiscriminator="kind"）。
 * routes 是列表型字段，不进 BridgeFields.ALL 声明表（[CDS §4.5]）。
 * feature 层路由编辑器已删除；REPLY 槽（ReplyRoute/固定回复）已整槽退役——存量 reply 条目
 * 由 v9→v10 迁移剥离（折 experimental.retiredV10 保底），保留本类型仅为存量 bridge-config.json
 * 的 routes 数组反序列化，不再有 UI 编辑入口。
 * 新增一类能力改 4 处，sealed + classDiscriminator 让漏写即红构建。
 */
@Serializable
sealed interface RouteRule {
    val id: String
    val enabled: Boolean
}

@Serializable
@SerialName("system")
data class SystemRoute(
    override val id: String = "builtin",
    override val enabled: Boolean = true,
) : RouteRule

@Serializable
@SerialName("command")
data class CommandRoute(
    override val id: String = "",
    override val enabled: Boolean = true,
    val name: String = "",
    val aliases: List<String> = emptyList(),
    val description: String = "",
    val promptTemplate: String = "",
    val requiresConfirm: Boolean = false,
) : RouteRule

@Serializable
@SerialName("agent")
data class AgentRoute(
    override val id: String = "default",
    override val enabled: Boolean = true,
    // triggers / resetCommand / stopCommand 已删除（routes 数据退役）：三匹配键改由
    // [EngineReservedWords] 承载——编译期常量（"ai"/"/ai"、"新对话"、"停止"）
    // + 迁移期用户旧词（experimental.retiredV8）。CLI 前缀匹配的数据源同批切到
    // EngineReservedWords（AgentHandler.matchedTrigger）；存量文档残键折入 retiredV8 保底。
) : RouteRule

/**
 * 文件路由（仅剩总开关语义）：`enabled=false` ⇒ 文件消息不登记
 * （FileHandler 不 match），引用回复自然无从命中。旧的 `triggerPrefix` 是 main 零消费的死配置，已删除。
 */
@Serializable
@SerialName("file")
data class FileRoute(
    override val id: String = "file-analysis",
    override val enabled: Boolean = true,
) : RouteRule
