package com.jiaoay.lark.bridge.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CDS §13.1] BridgeConfig 往返/缺字段默认/未知键忽略/experimental 保留。 */
class BridgeConfigRoundTripTest {

    @Test
    fun `encode-decode 全字段默认值往返等价`() {
        val original = DefaultBridgeConfig.default()
        val text = BridgeJson.encodeToString(BridgeConfig.serializer(), original)
        val decoded = BridgeJson.decodeFromString(BridgeConfig.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun `encode-decode 非默认值往返等价`() {
        val original = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(appId = "cli_x", appSecret = "s3cr3t", region = "lark", autoStart = true),
            runtime = RuntimeConfig(maxConcurrentAgents = 5, taskTimeoutSeconds = 120, agentTimeoutMinutes = 20),
        )
        val decoded = BridgeJson.decodeFromString(
            BridgeConfig.serializer(),
            BridgeJson.encodeToString(BridgeConfig.serializer(), original),
        )
        assertEquals(original, decoded)
    }

    @Test
    fun `缺字段取 data class 默认值`() {
        // 只给 schemaVersion，其余键全缺 → decode 后各域取默认
        // 🔴 文档里的 schemaVersion 值不参与 decode 覆盖（decode 直读）——最小文档须与
        //    CURRENT_VERSION 同源，否则与 default() 的版本戳不等
        val minimal = """{"schemaVersion":${ConfigMigrations.CURRENT_VERSION}}"""
        val decoded = BridgeJson.decodeFromString(BridgeConfig.serializer(), minimal)
        assertEquals(DefaultBridgeConfig.default(), decoded)
        assertEquals(60L, decoded.runtime.taskTimeoutSeconds)
        assertEquals(10L, decoded.runtime.agentTimeoutMinutes)
    }

    @Test
    fun `未知键忽略不报错`() {
        val withUnknown = """{"schemaVersion":2,"unknownKey":123,"connection":{"appId":"a","whatever":true}}"""
        val decoded = BridgeJson.decodeFromString(BridgeConfig.serializer(), withUnknown)
        assertEquals("a", decoded.connection.appId)
    }

    @Test
    fun `experimental 段非空往返不丢`() {
        val experimental = buildJsonObject {
            put("futureFlag", true)
            put("nested", buildJsonObject { put("k", "v") })
        }
        val original = DefaultBridgeConfig.default().copy(experimental = experimental)
        val decoded = BridgeJson.decodeFromString(
            BridgeConfig.serializer(),
            BridgeJson.encodeToString(BridgeConfig.serializer(), original),
        )
        assertEquals(experimental, decoded.experimental)
        assertEquals(JsonPrimitive(true), (decoded.experimental["futureFlag"]))
    }

    @Test
    fun `routes sealed 多态经 classDiscriminator kind 往返保留子类型`() {
        val original = DefaultBridgeConfig.default()
        val text = BridgeJson.encodeToString(BridgeConfig.serializer(), original)
        assertTrue(text.contains("\"kind\""), "序列化应含 classDiscriminator=kind")
        val decoded = BridgeJson.decodeFromString(BridgeConfig.serializer(), text)
        assertEquals(original.routes, decoded.routes)
        assertTrue(decoded.routes[0] is SystemRoute)
        // P1 引擎保留词固化（2026-09-26）：默认 routes 已无 AgentRoute（收窄 SYSTEM+FILE）——
        // sealed 多态往返用显式构造的 AgentRoute 文档验证（存量 routes 数据本批仍可解析）。
        val withAgent = """{"schemaVersion":8,"routes":[{"kind":"system","id":"builtin"},
            {"kind":"agent","id":"default","triggers":["ai"],"resetCommand":"新对话","stopCommand":"停止"},
            {"kind":"file","id":"file-analysis"}]}"""
        val decodedAgent = BridgeJson.decodeFromString(BridgeConfig.serializer(), withAgent)
        assertTrue(decodedAgent.routes.any { it is AgentRoute }, "存量 AgentRoute 文档仍可解析")
    }

    @Test
    fun `AuditJson 与 BridgeJson 配方分离（§16-7 禁令）`() {
        // AuditJson：encodeDefaults=false + prettyPrint=false + 无 classDiscriminator
        assertTrue(AuditJson !== BridgeJson)
        val obj = JsonObject(mapOf("a" to JsonPrimitive(1)))
        val auditText = AuditJson.encodeToString(JsonObject.serializer(), obj)
        assertTrue(!auditText.contains("\n"), "AuditJson 必须单行紧凑（jsonl）")
        // BridgeJson prettyPrint=true 会产生多行
        val bridgeText = BridgeJson.encodeToString(JsonObject.serializer(), obj)
        assertTrue(bridgeText.contains("\n"), "BridgeJson prettyPrint=true 应多行")
    }
}
