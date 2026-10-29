// P1 引擎保留词固化批的专属测试（agent-first 重构 2026-09-26；P3 批 routes 数据退役后改两段来源）。
// 钉死两段来源解析（retiredV8 迁移旧词 → 常量默认）与精确匹配语义——
// 这是「停止杀不掉在途任务」「自定义旧词静默失效」两条评审 P1 风险的回归防线。
package com.jiaoay.lark.bridge.handler

import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import com.jiaoay.lark.bridge.config.RouteRule
import com.jiaoay.lark.bridge.config.SystemRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineReservedWordsTest {

    /** 构造带 experimental JSON 的配置（routes 直接传 typed 值，避开手写多态 JSON 的坑）。 */
    private fun config(
        routes: List<RouteRule> = DefaultBridgeConfig.DEFAULT_ROUTES,
        experimentalJson: String = "{}",
    ): BridgeConfig {
        val base = BridgeConfig(routes = routes)
        if (experimentalJson == "{}") return base
        val merged = kotlinx.serialization.json.Json.parseToJsonElement(experimentalJson).jsonObjectOrNull()
            ?: return base
        return base.copy(experimental = merged)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        this as? kotlinx.serialization.json.JsonObject

    @Test
    fun `默认配置下停止词与重置词均为常量默认`() {
        val c = config()
        assertEquals(listOf("停止"), EngineReservedWords.stopWords(c))
        assertEquals(listOf("新对话"), EngineReservedWords.resetWords(c))
        assertTrue(EngineReservedWords.isStop("停止", c))
        assertTrue(EngineReservedWords.isStop(" 停止 ", c), "trim 后全等——带空格也命中")
        assertTrue(EngineReservedWords.isReset("新对话", c))
    }

    @Test
    fun `retiredV8 旧词在前-常量默认兜底（P3 迁移落点，routes 数据退役后唯一自定义词通道）`() {
        val c = config(
            experimentalJson = """{"retiredV8":{"routes.agent.stopCommand":"停","routes.agent.resetCommand":"重来"}}""",
        )
        assertEquals(listOf("停", "停止"), EngineReservedWords.stopWords(c), "retiredV8 旧词在前、常量默认兜底在后")
        assertEquals(listOf("重来", "新对话"), EngineReservedWords.resetWords(c))
        assertTrue(EngineReservedWords.isStop("停", c), "迁移落点的旧词须命中（评审修正 5）")
        assertTrue(EngineReservedWords.isStop("停止", c), "常量默认仍兜底可用")
        assertTrue(EngineReservedWords.isReset("重来", c))
        assertTrue(EngineReservedWords.isReset("新对话", c))
    }

    @Test
    fun `retiredV8 旧词在 routes 退役后仍存活（P3 迁移落点）`() {
        val c = config(
            routes = listOf(SystemRoute(id = "builtin", enabled = true)),
            experimentalJson = """{"retiredV8":{"routes.agent.stopCommand":"停","routes.agent.resetCommand":"清空"}}""",
        )
        assertEquals(listOf("停", "停止"), EngineReservedWords.stopWords(c))
        assertEquals(listOf("清空", "新对话"), EngineReservedWords.resetWords(c))
        assertTrue(EngineReservedWords.isStop("停", c), "迁移落点的旧词须命中（评审修正 5）")
    }

    @Test
    fun `匹配是精确全等——前缀误触不命中`() {
        val c = config()
        assertFalse(EngineReservedWords.isStop("停止一下", c), "「停止一下」不是停止指令")
        assertFalse(EngineReservedWords.isStop("停止ing", c))
        assertFalse(EngineReservedWords.isReset("新对话吧", c))
        assertFalse(EngineReservedWords.isStop("", c), "空串不命中")
    }

    @Test
    fun `retiredV8 无旧词段时只回落常量默认（routes 数据退役后默认通道）`() {
        // routes 已无 AgentRoute 匹配键（P3 退役）⇒ 无 retiredV8 旧词 = 纯常量语义，
        // 不再有「disabled 条目贡献词」的分支（原用例的 routes 段真源已删）。
        val c = config(routes = listOf(SystemRoute(id = "builtin", enabled = true)))
        assertEquals(listOf("停止"), EngineReservedWords.stopWords(c))
        assertFalse(EngineReservedWords.isStop("停下", c), "未迁移的自定义词不得凭空生效")
    }
}
