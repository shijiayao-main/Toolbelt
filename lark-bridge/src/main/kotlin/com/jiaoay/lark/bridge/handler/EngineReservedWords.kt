// 引擎保留词：「停止」「新对话」等逃生口指令与 routes 数据解耦——匹配判定不依赖用户可配的
// AgentRoute 字段，改为统一保留词表解析。三段来源（顺序即优先级）：
//   1. experimental.retiredV8.routes.agent.{stopCommand,resetCommand,trigger}（v8 迁移的
//      用户旧词/旧前缀保底落点——routes 数据退役后自定义词从这读，一次性迁移语义，不暴露 UI）；
//   2. 编译期常量默认（「停止」「新对话」「ai」「/ai」）。
// 🔴 为什么不直接删自定义词：用户改过的停止词（如「停」）若静默失效，消息会落给 agent——
//    agent 跑在被取消的任务里无法自我取消，LLM 会口头应答「已停止」而在途任务仍在跑
//    （危险误导）；保留词表让旧词全程存活。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.config.BridgeConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 引擎保留词表（P1 起 SystemGate 的唯一指令解析点）。
 *
 * 消费方：[StopHandler]（URGENT 停止）、[AgentHandler]（resetCommand 重置会话）、
 * [SystemHandler.renderCommands]（指令清单展示）。
 *
 * 全部为**精确匹配词**（trim 后全等，与既有 StopHandler/matchedTrigger 的边界语义一致），
 *    不做前缀/模糊——逃生口指令必须零误判。
 */
object EngineReservedWords {

    /** 停止在途任务的默认词（原 AgentRoute.stopCommand 默认值内置化）。 */
    const val STOP: String = "停止"

    /** 重置会话的默认词（原 AgentRoute.resetCommand 默认值内置化）。 */
    const val RESET: String = "新对话"

    /** CLI 前缀默认值（原 AgentRoute.triggers 默认值内置化；App 形态恒被短路——仅 CLI 直跑用）。 */
    val DEFAULT_TRIGGERS: List<String> = listOf("ai", "/ai")

    /** v8 迁移把非默认旧词/旧前缀折入 retiredV8 的键名（点路径风格）。 */
    private const val RETIRED_V8_STOP: String = "routes.agent.stopCommand"
    private const val RETIRED_V8_RESET: String = "routes.agent.resetCommand"
    private const val RETIRED_V8_TRIGGER: String = "routes.agent.trigger"

    /**
     * 当前生效的停止词集合（retiredV8 旧词 → 常量默认；去重保序）。
     * 每次现读（对齐 StopHandler 原有「禁装配期缓存」纪律）。
     */
    fun stopWords(config: BridgeConfig): List<String> =
        buildList {
            retiredWord(config, RETIRED_V8_STOP)?.let { add(it) }
            add(STOP)
        }.distinct()

    /**
     * 当前生效的重置词集合（同 [stopWords] 的两段来源）。
     * 🔴 每次现读（配置热更后下一条消息即生效）。
     */
    fun resetWords(config: BridgeConfig): List<String> =
        buildList {
            retiredWord(config, RETIRED_V8_RESET)?.let { add(it) }
            add(RESET)
        }.distinct()

    /**
     * 当前生效的 CLI 触发前缀集合（retiredV8 旧前缀 → 常量默认 ["ai","/ai"]）。
     * 仅 CLI 形态消费（App 形态前缀被「koog 在位」恒短路——P2 评审修正 1）；
     *    前缀匹配语义（最长前缀 + 边界校验）由 [AgentHandler.matchedTrigger] 承担。
     */
    fun triggerPrefixes(config: BridgeConfig): List<String> =
        buildList {
            retiredWord(config, RETIRED_V8_TRIGGER)?.let { add(it) }
            addAll(DEFAULT_TRIGGERS)
        }.distinct()

    /** 文本是否命中停止词（trim 后全等精确匹配）。 */
    fun isStop(text: String, config: BridgeConfig): Boolean {
        val t = text.trim()
        return stopWords(config).any { it == t }
    }

    /** 文本是否命中重置词（trim 后全等精确匹配）。 */
    fun isReset(text: String, config: BridgeConfig): Boolean {
        val t = text.trim()
        return resetWords(config).any { it == t }
    }

    /** 从 experimental.retiredV8 读单个字符串旧词（无段/无键/非原始值返回 null）。 */
    private fun retiredWord(config: BridgeConfig, key: String): String? = runCatching {
        val retiredV8 = config.experimental["retiredV8"] as? JsonObject ?: return null
        val value = retiredV8[key] ?: return null
        value.jsonPrimitive.content.takeIf { it.isNotBlank() }
    }.getOrNull()
}
