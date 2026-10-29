package com.jiaoay.lark.bridge.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [CDS §13.6] 迁移框架：v0→当前版本（退役键折入 experimental / 旧默认删键收敛 / 语义等价转换 / 纯版本锚点各步见分组注释）、未知顶层段保留。 */
class ConfigMigrationsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `当前版本为 15 且迁移链恰 14 步（… v12→v13 + v13→v14 + v14→v15）`() {
        assertEquals(15, ConfigMigrations.CURRENT_VERSION)
        assertEquals(14, ConfigMigrations.MIGRATIONS.size)
    }

    @Test
    fun `无版本文档升迁盖当前版本戳`() {
        val doc = json.parseToJsonElement("""{"connection":{"appId":"a"}}""").jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        // 内容原样保留
        assertEquals("a", migrated["connection"]!!.jsonObject["appId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `伪造 v0 文档升迁到当前版本（无退役键时内容不动）`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":0,"access":{"trustedUsers":["ou_a"]}}""").jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("ou_a", migrated["access"]!!.jsonObject["trustedUsers"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `未知顶层段折入 experimental 保留`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":1,"futureSection":{"x":1},"connection":{"appId":"a"}}""",
        ).jsonObject
        val folded = ConfigMigrations.foldUnknownIntoExperimental(doc)
        val experimental = folded["experimental"]!!.jsonObject
        assertTrue(experimental.containsKey("futureSection"))
        assertEquals(1, experimental["futureSection"]!!.jsonObject["x"]!!.jsonPrimitive.int)
        // 已知键不被折入
        assertTrue(folded.containsKey("connection"))
        assertTrue(!ConfigMigrations.KNOWN_TOP_LEVEL_KEYS.contains("futureSection"))
    }

    @Test
    fun `foldUnknown 幂等（再次调用不变动）`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":1,"weird":true}""").jsonObject
        val once = ConfigMigrations.foldUnknownIntoExperimental(doc)
        val twice = ConfigMigrations.foldUnknownIntoExperimental(once)
        assertEquals(once, twice)
    }

    @Test
    fun `全已知键文档 fold 不变动`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":1,"connection":{},"access":{},"workspace":{},"runtime":{},"agent":{},"fileMessage":{},"observability":{},"routes":[],"experimental":{}}""",
        ).jsonObject
        assertEquals(doc, ConfigMigrations.foldUnknownIntoExperimental(doc))
    }

    @Test
    fun `migrate 后 decode 为 typed BridgeConfig 成功`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":0,"runtime":{"taskTimeoutSeconds":99}}""").jsonObject
        val folded = ConfigMigrations.foldUnknownIntoExperimental(ConfigMigrations.migrate(doc))
        val config = BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), folded)
        assertEquals(99L, config.runtime.taskTimeoutSeconds)
        assertEquals(ConfigMigrations.CURRENT_VERSION, config.schemaVersion)
    }

    // ── v1→v2（2026-09-11 引用流重设计）：fileMessage 退役键折入 experimental.retiredV1 ──

    @Test
    fun `v1 退役键非默认值折入 retiredV1 后从 fileMessage 移除`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":1,"fileMessage":{"enabled":true,"triggerPrefix":"分析","pendingWindowMillis":120000}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        val fm = migrated["fileMessage"]!!.jsonObject
        assertTrue(!fm.containsKey("triggerPrefix"), "退役键应已从 fileMessage 移除")
        assertTrue(!fm.containsKey("pendingWindowMillis"))
        assertEquals("true", fm["enabled"]!!.jsonPrimitive.content, "非退役键原样保留")
        val retired = migrated["experimental"]!!.jsonObject["retiredV1"]!!.jsonObject
        assertEquals("分析", retired["triggerPrefix"]!!.jsonPrimitive.content)
        assertEquals(120000, retired["pendingWindowMillis"]!!.jsonPrimitive.int)
    }

    @Test
    fun `v1 退役键取默认值时直接丢弃（不进 retiredV1）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":1,"fileMessage":{"triggerPrefix":"","pendingWindowMillis":60000,"textInlineMaxBytes":262144}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val fm = migrated["fileMessage"]!!.jsonObject
        assertTrue(!fm.containsKey("triggerPrefix"), "默认值键同样要从 fileMessage 移除")
        // 🔴 全默认 ⇒ 无信息量 ⇒ 不产 retiredV1 桶（experimental 甚至可以不出现）
        val experimental = migrated["experimental"]
        assertTrue(experimental == null || !experimental.jsonObject.containsKey("retiredV1"),
            "默认值无信息量，不搬：${experimental}")
    }

    @Test
    fun `v2 文档再迁移幂等（retiredV1 不重复搬移）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":2,"fileMessage":{"enabled":true},"experimental":{"retiredV1":{"triggerPrefix":"分析"}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val retired = migrated["experimental"]!!.jsonObject["retiredV1"]!!.jsonObject
        assertEquals("分析", retired["triggerPrefix"]!!.jsonPrimitive.content)
        assertTrue(!migrated["fileMessage"]!!.jsonObject.containsKey("triggerPrefix"))
    }

    @Test
    fun `缺 fileMessage 段时 v1→v2 no-op`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":1,"access":{"trustedUsers":["ou_a"]}}""").jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals("ou_a", migrated["access"]!!.jsonObject["trustedUsers"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
    }

    // ── v2→v3（2026-09-11 防刷屏裁定）：agent.progress 旧默认显式值删键收敛 ──

    @Test
    fun `v2 旧默认显式值删键后盖版本戳（decode 落新默认）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":2,"agent":{"progress":{"enabled":true,"maxEntries":12,"cardMaxChars":5000}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        val progress = migrated["agent"]!!.jsonObject["progress"]!!.jsonObject
        assertTrue(!progress.containsKey("maxEntries"), "旧默认 maxEntries=12 应删键（decode 落新默认 6）")
        assertTrue(!progress.containsKey("cardMaxChars"), "旧默认 cardMaxChars=5000 应删键（decode 落新默认 2000）")
        assertEquals("true", progress["enabled"]!!.jsonPrimitive.content, "非处理键原样保留")
        // decode 后真落到新默认
        val config = BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), migrated)
        assertEquals(6, config.agent.progress.maxEntries)
        assertEquals(2000, config.agent.progress.cardMaxChars)
    }

    @Test
    fun `JsonObject 层操作 - 被删改名字段不让 decode 失败`() {
        // 模拟旧文档含新 schema 已不存在的字段 legacyOnly → 折入 experimental 后仍可 decode
        val doc = json.parseToJsonElement("""{"schemaVersion":1,"legacyOnly":"x"}""").jsonObject
        val folded = ConfigMigrations.foldUnknownIntoExperimental(doc)
        val config = BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), folded)
        assertEquals(JsonPrimitive("x"), config.experimental["legacyOnly"])
    }

    @Test
    fun `experimental 是已知顶层键（不被自身 fold 二次搬移）`() {
        assertTrue(ConfigMigrations.KNOWN_TOP_LEVEL_KEYS.contains("experimental"))
        val doc = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "experimental" to JsonObject(mapOf("k" to JsonPrimitive("v")))))
        val folded = ConfigMigrations.foldUnknownIntoExperimental(doc)
        assertEquals("v", folded["experimental"]!!.jsonObject["k"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v2 用户自定义值不删键（尊重调参）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":2,"agent":{"progress":{"maxEntries":20,"cardMaxChars":8000}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val progress = migrated["agent"]!!.jsonObject["progress"]!!.jsonObject
        assertEquals(20, progress["maxEntries"]!!.jsonPrimitive.int, "用户调过的 maxEntries 原样保留")
        assertEquals(8000, progress["cardMaxChars"]!!.jsonPrimitive.int, "用户调过的 cardMaxChars 原样保留")
    }

    @Test
    fun `缺 agent 或 progress 段时 v2→v3 与 v3→v4 no-op`() {
        val noAgent = json.parseToJsonElement("""{"schemaVersion":2,"access":{"trustedUsers":["ou_a"]}}""").jsonObject
        val migratedA = ConfigMigrations.migrate(noAgent)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migratedA["schemaVersion"]!!.jsonPrimitive.int)
        assertTrue(!migratedA.containsKey("agent"), "无 agent 段不凭空造段")

        val noProgress = json.parseToJsonElement("""{"schemaVersion":2,"agent":{"enabled":true}}""").jsonObject
        val migratedB = ConfigMigrations.migrate(noProgress)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migratedB["schemaVersion"]!!.jsonPrimitive.int)
        assertTrue(!migratedB["agent"]!!.jsonObject.containsKey("progress"), "无 progress 段不凭空造段")
    }

    @Test
    fun `v3 文档再迁移幂等（旧默认键已删不再变动）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":3,"agent":{"progress":{"enabled":true}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        // v3→v4 对无 patchIntervalMillis 的文档 no-op；仅版本戳升 4
        assertTrue(!migrated["agent"]!!.jsonObject["progress"]!!.jsonObject.containsKey("patchIntervalMillis"))
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
    }

    // ── v3→v4（2026-09-12 滚动窗口精简）：agent.progress.patchIntervalMillis 退役 ──

    @Test
    fun `v3 用户调参的 patchIntervalMillis 折入 retiredV3 后从 progress 移除`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":3,"agent":{"progress":{"enabled":true,"patchIntervalMillis":500}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        val progress = migrated["agent"]!!.jsonObject["progress"]!!.jsonObject
        assertTrue(!progress.containsKey("patchIntervalMillis"), "退役键应已从 progress 移除（typed 域已无此字段）")
        assertEquals("true", progress["enabled"]!!.jsonPrimitive.content, "非退役键原样保留")
        val retired = migrated["experimental"]!!.jsonObject["retiredV3"]!!.jsonObject
        assertEquals(500, retired["patchIntervalMillis"]!!.jsonPrimitive.int, "非默认调参须保底进 retiredV3（看得见什么被退役）")
        // decode 成功且无此字段
        val config = BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), migrated)
        assertEquals(ConfigMigrations.CURRENT_VERSION, config.schemaVersion)
    }

    @Test
    fun `v3 patchIntervalMillis 为默认值 2000 时直接丢弃（不进 retiredV3）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":3,"agent":{"progress":{"enabled":true,"patchIntervalMillis":2000}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val progress = migrated["agent"]!!.jsonObject["progress"]!!.jsonObject
        assertTrue(!progress.containsKey("patchIntervalMillis"), "默认值键同样要从 progress 移除")
        // 🔴 全默认 ⇒ 无信息量 ⇒ 不产 retiredV3 桶
        val experimental = migrated["experimental"]
        assertTrue(experimental == null || !experimental.jsonObject.containsKey("retiredV3"),
            "默认值无信息量，不搬：$experimental")
    }

    @Test
    fun `v7 无死配置残键时迁移幂等（内容不变仅重盖版本戳）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"agent":{"progress":{"enabled":true,"maxEntries":6}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val expected = json.parseToJsonElement(
            """{"schemaVersion":${ConfigMigrations.CURRENT_VERSION},"agent":{"progress":{"enabled":true,"maxEntries":6}}}""",
        ).jsonObject
        assertEquals(expected, migrated, "v7 文档无死配置残键 ⇒ 内容不变仅重盖版本戳（v8→v9 / v9→v10 / v10→v11 / v11→v12 对该文档零变换）")
    }

    // ── v7→v8（2026-09-26 agent-first P0 批）：三死配置退役（session.capacity/keyByThread/fileMessage.allowedExtensions）──

    @Test
    fun `v7 死配置非默认值折入 retiredV8 后从所在段移除`() {
        // 用户调过参的存量文档：三键全非默认 ⇒ 全部进 retiredV8 保底
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"agent":{"session":{"capacity":128,"keyByThread":false}}, """ +
                """"fileMessage":{"allowedExtensions":".log,.zip"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        // session 段被掏空 ⇒ 整段删除（typed 层已是空壳）
        assertTrue(!migrated["agent"]!!.jsonObject.containsKey("session"), "session 空段应被删除")
        assertTrue(!migrated["fileMessage"]!!.jsonObject.containsKey("allowedExtensions"))
        val retired = migrated["experimental"]!!.jsonObject["retiredV8"]!!.jsonObject
        assertEquals(128, retired["agent.session.capacity"]!!.jsonPrimitive.int)
        assertEquals(false, retired["agent.session.keyByThread"]!!.jsonPrimitive.boolean)
        assertEquals(".log,.zip", retired["fileMessage.allowedExtensions"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v7 死配置取默认值时直接丢弃（不进 retiredV8）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"agent":{"session":{"capacity":64,"keyByThread":true}}, """ +
                """"fileMessage":{"allowedExtensions":".log,.txt,.xlog,.alog,.zip,.tar,.tar.gz,.tgz,.gz"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertTrue(!migrated["agent"]!!.jsonObject.containsKey("session"), "全默认 session 段掏空即删")
        assertTrue(!migrated["fileMessage"]!!.jsonObject.containsKey("allowedExtensions"))
        val experimental = migrated["experimental"]
        assertTrue(experimental == null || !experimental.jsonObject.containsKey("retiredV8"),
            "默认值无信息量，不搬：$experimental")
    }

    @Test
    fun `v7 session 段留有未来键时不掏空（只删两个退役键）`() {
        // 前向兼容：若未来 typed 域给 session 加了新字段，旧文档迁移只删本批退役的两键
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"agent":{"session":{"capacity":32,"futureKey":"v"}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val session = migrated["agent"]!!.jsonObject["session"]!!.jsonObject
        assertTrue(!session.containsKey("capacity"), "退役键应已移除")
        assertEquals("v", session["futureKey"]!!.jsonPrimitive.content, "未知键原样保留（fold 语义兜底）")
        val retired = migrated["experimental"]!!.jsonObject["retiredV8"]!!.jsonObject
        assertEquals(32, retired["agent.session.capacity"]!!.jsonPrimitive.int, "非默认值进保底")
    }

    @Test
    fun `v7→v8 幂等（迁移产物再迁不动）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"agent":{"session":{"capacity":99}},"fileMessage":{"allowedExtensions":".log"}}""",
        ).jsonObject
        val once = ConfigMigrations.migrate(doc)
        val twice = ConfigMigrations.migrate(once)
        assertEquals(once, twice)
    }

    @Test
    fun `v7 无 agent 与 fileMessage 段时 v8 步 no-op`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":7,"connection":{"appId":"a"}}""").jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals("a", migrated["connection"]!!.jsonObject["appId"]!!.jsonPrimitive.content)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
    }

    // ── v7→v8 P3 段（2026-09-26 agent-first 配置删除批）：8 标量残键 + routes 匹配键退役 + promptTemplate 通道 ──

    @Test
    fun `v7 P3 八标量非默认值折入 retiredV8 后从所在段移除`() {
        // 8 个退役标量全非默认 ⇒ 全部进 retiredV8 保底（点路径键）
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"access":{"mode":"blocklist","rejectionReply":"走开","groupRequireMention":false},""" +
                """"runtime":{"unhandledReply":"没听懂"},"agent":{"dispatchEnabled":true,"artifacts":{"reuseFileKey":true,"memoTtlHours":24,"memoMaxEntries":64}},""" +
                """"fileMessage":{"ackTemplate":"已收 {fileName}"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val access = migrated["access"]!!.jsonObject
        val runtime = migrated["runtime"]!!.jsonObject
        val agent = migrated["agent"]!!.jsonObject
        val artifacts = agent["artifacts"]!!.jsonObject
        assertTrue(!access.containsKey("rejectionReply") && !access.containsKey("groupRequireMention"))
        assertTrue(!runtime.containsKey("unhandledReply"))
        assertTrue(!agent.containsKey("dispatchEnabled"))
        assertTrue(!artifacts.containsKey("reuseFileKey") && !artifacts.containsKey("memoTtlHours") && !artifacts.containsKey("memoMaxEntries"))
        assertTrue(!migrated["fileMessage"]!!.jsonObject.containsKey("ackTemplate"))
        val retired = migrated["experimental"]!!.jsonObject["retiredV8"]!!.jsonObject
        assertEquals("走开", retired["access.rejectionReply"]!!.jsonPrimitive.content)
        assertEquals(false, retired["access.groupRequireMention"]!!.jsonPrimitive.boolean)
        assertEquals("没听懂", retired["runtime.unhandledReply"]!!.jsonPrimitive.content)
        assertEquals(true, retired["agent.dispatchEnabled"]!!.jsonPrimitive.boolean)
        assertEquals(true, retired["agent.artifacts.reuseFileKey"]!!.jsonPrimitive.boolean)
        assertEquals(24, retired["agent.artifacts.memoTtlHours"]!!.jsonPrimitive.int)
        assertEquals(64, retired["agent.artifacts.memoMaxEntries"]!!.jsonPrimitive.int)
        assertEquals("已收 {fileName}", retired["fileMessage.ackTemplate"]!!.jsonPrimitive.content)
        // mode 在 v8 步原样保留（v12→v13 才退役，见该步用例）
        assertEquals(null, access["mode"], "全链跑到 v13，mode 已退役")
    }

    @Test
    fun `v7 P3 八标量取默认值时直接丢弃（不进 retiredV8）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"access":{"rejectionReply":"","groupRequireMention":true},""" +
                """"runtime":{"unhandledReply":""},"agent":{"dispatchEnabled":false,"artifacts":{"reuseFileKey":false,"memoTtlHours":168,"memoMaxEntries":512}},""" +
                """"fileMessage":{"ackTemplate":""}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val experimental = migrated["experimental"]
        // ackTemplate 默认值是长文案——encodeDefaults 存量文档若显式写默认值会被当非默认折入；
        // 空串默认则静默丢。此处全默认 ⇒ retiredV8 至多含 ackTemplate 之外零键。
        assertTrue(
            experimental == null || (experimental.jsonObject["retiredV8"] == null),
            "全默认无信息量，不搬：$experimental",
        )
    }

    @Test
    fun `v7 routes AgentRoute 匹配键退役 - 非默认词折入 retiredV8 通道`() {
        // 🔴 typed 层 routes 序列化形态 = 数组（kind 判别符），非对象段
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"routes":[{"kind":"agent","id":"default","enabled":true,""" +
                """"triggers":["bot","/bot"],"resetCommand":"重置","stopCommand":"停"}]}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val routes = migrated["routes"]!!.jsonArray
        val agentRoute = routes[0].jsonObject
        assertTrue(!agentRoute.containsKey("triggers") && !agentRoute.containsKey("resetCommand") && !agentRoute.containsKey("stopCommand"))
        assertEquals("default", agentRoute["id"]!!.jsonPrimitive.content, "id/enabled 保留")
        val retired = migrated["experimental"]!!.jsonObject["retiredV8"]!!.jsonObject
        assertEquals("bot,/bot", retired["routes.agent.trigger"]!!.jsonPrimitive.content, "triggers 逗号串保底")
        assertEquals("重置", retired["routes.agent.resetCommand"]!!.jsonPrimitive.content)
        assertEquals("停", retired["routes.agent.stopCommand"]!!.jsonPrimitive.content, "用户改过的停止词存活（修正 5）")
    }

    @Test
    fun `v7 routes AgentRoute 默认匹配键静默丢（不进 retiredV8）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"routes":[{"kind":"agent","id":"default","enabled":true,""" +
                """"triggers":["ai","/ai"],"resetCommand":"新对话","stopCommand":"停止"}]}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val retired = (migrated["experimental"] as? kotlinx.serialization.json.JsonObject)?.get("retiredV8")
        assertTrue(retired == null || (retired.jsonObject as Map<String, kotlinx.serialization.json.JsonElement>).keys.none { it.startsWith("routes.agent.") },
            "默认匹配键无信息量不搬：$retired")
    }

    @Test
    fun `v7 CommandRoute promptTemplate 附加进 agent-systemPrompt 且原文保底`() {
        // 修正 12：模板含 {args} 占位、多条拼接有块头格式
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"agent":{"systemPrompt":"基础提示"},""" +
                """"routes":[{"kind":"command","id":"c1","name":"部署","aliases":["/deploy","/dp"],"description":"d",""" +
                """"promptTemplate":"/my-skill {args}","requiresConfirm":false}]}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val systemPrompt = migrated["agent"]!!.jsonObject["systemPrompt"]!!.jsonPrimitive.content
        assertTrue(systemPrompt.startsWith("基础提示"), "原 systemPrompt 前置保留")
        assertTrue(systemPrompt.contains("快捷指令 部署（别名 /deploy、/dp）：/my-skill {args}"), "块头格式（修正 12）：$systemPrompt")
        assertTrue(systemPrompt.contains("{args} 为占位符"), "告知 LLM 占位符性质")
        assertTrue(!migrated["routes"]!!.jsonArray[0].jsonObject.containsKey("promptTemplate"), "模板从 route 移除")
        val retired = migrated["experimental"]!!.jsonObject["retiredV8"]!!.jsonObject
        assertEquals("/my-skill {args}", retired["routes.command.部署.promptTemplate"]!!.jsonPrimitive.content, "原文保底")
    }

    @Test
    fun `v7 P0 与 P3 同段并存时互不覆盖（真存量文档形态）`() {
        // 🔴 回归钉：真实 v7 存量文档（encodeDefaults 全量写回）里 session 与 dispatchEnabled、
        //    allowedExtensions 与 ackTemplate 必然同段并存——P3 块曾从 doc（原文）构建 kept，
        //    把 P0 刚删的 allowedExtensions/session 键写回。
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"agent":{"session":{"capacity":128},"dispatchEnabled":true},""" +
                """"fileMessage":{"allowedExtensions":".log","ackTemplate":"收到 {fileName}"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val agent = migrated["agent"]!!.jsonObject
        val fm = migrated["fileMessage"]!!.jsonObject
        assertTrue(!agent.containsKey("session"), "P0 删的 session 不得被 P3 写回")
        assertTrue(!agent.containsKey("dispatchEnabled"), "P3 删 dispatchEnabled")
        assertTrue(!fm.containsKey("allowedExtensions"), "P0 删的 allowedExtensions 不得被 P3 写回")
        assertTrue(!fm.containsKey("ackTemplate"), "P3 删 ackTemplate")
        val retired = migrated["experimental"]!!.jsonObject["retiredV8"]!!.jsonObject
        assertEquals(128, retired["agent.session.capacity"]!!.jsonPrimitive.int)
        assertEquals(true, retired["agent.dispatchEnabled"]!!.jsonPrimitive.boolean)
        assertEquals(".log", retired["fileMessage.allowedExtensions"]!!.jsonPrimitive.content)
        assertEquals("收到 {fileName}", retired["fileMessage.ackTemplate"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v7→v8 P3 段幂等（含全部退役面再迁不动）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":7,"access":{"rejectionReply":"x"},"runtime":{"unhandledReply":"y"},""" +
                """"agent":{"dispatchEnabled":true},"routes":[{"kind":"agent","id":"d","triggers":["bot"],"stopCommand":"停"},""" +
                """{"kind":"command","id":"c1","name":"部署","promptTemplate":"tpl {args}"}]}""",
        ).jsonObject
        val once = ConfigMigrations.migrate(doc)
        val twice = ConfigMigrations.migrate(once)
        assertEquals(once, twice, "迁移产物再迁必须零变化")
    }

    @Test
    fun `守卫差一防线 - v8 文档（收到 version 14）跳过 v7→v8 步`() {
        // 🔴 框架语义：本步是链上下标 6，v7 文档收到 13、v8 文档收到 14。
        //    直接调 internal 步验证守卫：>=14 时原样返回（不误伤已是 v8 的文档）。
        val doc = json.parseToJsonElement(
            """{"schemaVersion":8,"agent":{"session":{"capacity":64,"keyByThread":true}}}""",
        ).jsonObject
        val untouched = ConfigMigrations.migrateV7ToV8(14, doc)
        assertEquals(doc, untouched, "v8 文档不应再动")
    }

    // ── v8→v9（2026-09-27 能力一级化批 B）：纯版本锚点步（零数据变换） ──

    @Test
    fun `v8 文档经全链迁移到当前版本（enabled 默认键静默退役）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":8,"agent":{"enabled":true},"connection":{"appId":"a"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        // v8→v9 步零变换；v9→v10 步把 agent.enabled 默认值（true）静默退役 ⇒ agent 段收敛为空对象
        val expected = json.parseToJsonElement(
            """{"schemaVersion":${ConfigMigrations.CURRENT_VERSION},"agent":{},"connection":{"appId":"a"}}""",
        ).jsonObject
        assertEquals(expected, migrated)
    }

    @Test
    fun `v11 文档含 nativeTools 双闸段迁移 v12 退役（非默认态折 retiredV12 保底、纯默认静默丢）`() {
        // 非默认态（l2Enabled=true + grants 非空）⇒ 折 experimental.retiredV12 保底（用户可见什么被退役）
        val doc = json.parseToJsonElement(
            """{"schemaVersion":11,"agent":{"nativeTools":{"l2Enabled":true,"l2Grants":["READ_WIKI"]}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val experimental = (migrated["agent"]?.let { it } ?: migrated["experimental"]) ?: migrated
        val retired = (migrated["experimental"] as? kotlinx.serialization.json.JsonObject)
            ?.get("retiredV12") as? kotlinx.serialization.json.JsonObject
        assertTrue(retired != null, "非默认态必须折 retiredV12 保底，实际=${migrated}")
        assertTrue(
            (retired["agent.nativeTools.l2Grants"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "[\"READ_WIKI\"]"
                || (retired["agent.nativeTools.l2Grants"]?.toString()?.contains("READ_WIKI") == true),
            "l2Grants 非空须保底，实际=${retired}",
        )
        // agent 段内 nativeTools 键已消失
        val agent = migrated["agent"] as? kotlinx.serialization.json.JsonObject
        assertTrue(agent?.containsKey("nativeTools") != true, "agent.nativeTools 段必须移除，实际=${agent?.keys}")
        // 纯默认段（false + 空数组）⇒ 静默丢（无 retiredV12 保底）
        val docDefault = json.parseToJsonElement(
            """{"schemaVersion":11,"agent":{"nativeTools":{"l2Enabled":false,"l2Grants":[]}}}""",
        ).jsonObject
        val migratedDefault = ConfigMigrations.migrate(docDefault)
        val agentDefault = migratedDefault["agent"] as? kotlinx.serialization.json.JsonObject
        assertTrue(agentDefault?.containsKey("nativeTools") != true, "纯默认段必须移除，实际=${agentDefault?.keys}")
        assertTrue(
            ((migratedDefault["experimental"] as? kotlinx.serialization.json.JsonObject)?.containsKey("retiredV12") != true),
            "纯默认段不得折保底，实际=${migratedDefault["experimental"]}",
        )
    }

    @Test
    fun `守卫差一防线 - v9 文档（收到 version 16）跳过 v8→v9 步`() {
        // 🔴 框架语义：本步是链上下标 7，v8 文档收到 15、v9 文档收到 16。
        //    守卫数字 = 步序×2（最后一步 v7→v8 守卫 14 的同型纪律）；写 `>= 15` 会把真正的 v8 文档跳掉。
        val doc = json.parseToJsonElement("""{"schemaVersion":9,"agent":{}}""").jsonObject
        val untouched = ConfigMigrations.migrateV8ToV9(16, doc)
        assertEquals(doc, untouched, "v9 文档不应再动")
    }

    @Test
    fun `v8→v9 幂等（迁移产物再迁零变化）`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":8,"connection":{"appId":"a"}}""").jsonObject
        val once = ConfigMigrations.migrate(doc)
        val twice = ConfigMigrations.migrate(once)
        assertEquals(once, twice)
    }

    // ── v9→v10：agent.enabled 残键退役 + routes reply 条目剥离（REPLY 槽整槽退役）──

    @Test
    fun `v9 显式关闭的 agent-enabled 折入 retiredV10 后从 agent 段移除`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":9,"agent":{"enabled":false,"backend":"claude"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        val agent = migrated["agent"]!!.jsonObject
        assertTrue(!agent.containsKey("enabled"), "退役键应已从 agent 段移除（typed 域已无此字段）")
        // backend 默认 claude 由 v10→v11 静默丢（默认值无信息量，不进 retiredV11）
        assertTrue(!agent.containsKey("backend"), "agent.backend 已随 CLI 委托链退役")
        val retired = migrated["experimental"]!!.jsonObject["retiredV10"]!!.jsonObject
        assertEquals(false, retired["agent.enabled"]!!.jsonPrimitive.boolean, "显式 false 是用户主动关闸的痕迹，保底进 retiredV10")
        assertTrue(
            migrated["experimental"]!!.jsonObject["retiredV11"] == null,
            "backend=claude 是默认值 ⇒ 不进 retiredV11",
        )
    }

    @Test
    fun `v10 到 v11 守卫差一防线：v10 文档（version 19）必须处理，v11 文档（version 20 起）必须跳过`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":10,"agent":{"backend":"qoder"},"workspace":{"agentWorkDir":"/tmp/x"}}""",
        ).jsonObject
        // 链上下标 9：真实 v10 文档收到的 version 恰为 19——守卫误写 >= 19 会把整步跳过（残留键不退役）
        val migrated = ConfigMigrations.migrateV10ToV11(19, doc)
        val agent = migrated["agent"]!!.jsonObject
        assertTrue(!agent.containsKey("backend"), "v10 文档（version=19）的退役键必须被处理")
        val workspace = migrated["workspace"]!!.jsonObject
        assertTrue(!workspace.containsKey("agentWorkDir"), "v10 文档（version=19）的 workspace 退役键必须被处理")
        val retired = migrated["experimental"]!!.jsonObject["retiredV11"]!!.jsonObject
        assertEquals("qoder", retired["agent.backend"]!!.jsonPrimitive.content, "非默认 backend 保底进 retiredV11")
        // v11+ 文档（version>=20）跳过：同输入直传 20 应原样返回
        val skipped = ConfigMigrations.migrateV10ToV11(20, doc)
        assertTrue(
            (skipped["agent"]!!.jsonObject).containsKey("backend"),
            "v11+ 文档（version=20）本步零变换（幂等防线：已迁移文档不重跑）",
        )
    }

    @Test
    fun `v10 非默认 CLI 键经全链迁移折入 retiredV11 保底且从 typed 段移除`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":10,"agent":{"backend":"zcode","allowedTools":"Bash","confirm":{"enabled":true,"timeoutSeconds":90,"selfCheck":"sync"}},"appServer":{"enabled":true,"serverPath":"/opt/z/app-server"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        val agent = migrated["agent"]!!.jsonObject
        assertTrue(!agent.containsKey("backend"), "backend 已退役")
        assertTrue(!agent.containsKey("allowedTools"), "allowedTools 已退役")
        assertTrue(
            (agent["confirm"]!!.jsonObject).containsKey("timeoutSeconds") && !(agent["confirm"]!!.jsonObject).containsKey("enabled"),
            "confirm 段只留 timeoutSeconds",
        )
        assertTrue(!migrated.containsKey("appServer"), "appServer 顶层段整段退役")
        val retired = migrated["experimental"]!!.jsonObject["retiredV11"]!!.jsonObject
        assertEquals("zcode", retired["agent.backend"]!!.jsonPrimitive.content)
        assertEquals("Bash", retired["agent.allowedTools"]!!.jsonPrimitive.content)
        assertEquals(true, retired["agent.confirm.enabled"]!!.jsonPrimitive.boolean)
        assertTrue(retired.containsKey("appServer"), "非默认 appServer 段整段保底进 retiredV11")
    }

    @Test
    fun `v9 agent-enabled 缺省或默认 true 时静默丢（不进 retiredV10）`() {
        // 缺省（键从未写）：agent 段无 enabled ⇒ 本步对该段零变化
        val absent = json.parseToJsonElement(
            """{"schemaVersion":9,"agent":{"backend":"claude"}}""",
        ).jsonObject
        val migratedAbsent = ConfigMigrations.migrate(absent)
        assertTrue(!migratedAbsent["agent"]!!.jsonObject.containsKey("enabled"))
        assertTrue(migratedAbsent["experimental"]?.jsonObject?.containsKey("retiredV10") != true)
        // 显式 true（encodeDefaults 全量写回的常态）＝默认值 ⇒ 无信息量，静默丢
        val explicit = json.parseToJsonElement(
            """{"schemaVersion":9,"agent":{"enabled":true}}""",
        ).jsonObject
        val migratedExplicit = ConfigMigrations.migrate(explicit)
        assertTrue(!migratedExplicit["agent"]!!.jsonObject.containsKey("enabled"))
        val experimental = migratedExplicit["experimental"]
        assertTrue(experimental == null || !experimental.jsonObject.containsKey("retiredV10"),
            "默认值无信息量，不搬：$experimental")
    }

    @Test
    fun `缺 agent 段与缺 routes 段时 v9→v10 no-op`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":9,"connection":{"appId":"a"}}""").jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals("a", migrated["connection"]!!.jsonObject["appId"]!!.jsonPrimitive.content)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        assertTrue(!migrated.containsKey("agent"), "无 agent 段不凭空造段")
    }

    @Test
    fun `v9→v10 幂等（迁移产物再迁不动）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":9,"agent":{"enabled":false},""" +
                """"routes":[{"kind":"reply","id":"r1","enabled":true,"trigger":"/t","match":"exact","reply":"r"}]}""",
        ).jsonObject
        val once = ConfigMigrations.migrate(doc)
        val twice = ConfigMigrations.migrate(once)
        assertEquals(once, twice)
    }

    @Test
    fun `守卫差一防线 - 真 v9 文档（收到 version 17）必须被 v9→v10 步处理`() {
        // 🔴 框架语义：本步是链上下标 8，v9 文档收到 17。守卫写 `>= 17` 会把真 v9 文档整步跳掉
        //    （enabled 残键不退役、reply 条目不剥离 ⇒ sealed 多态 decode 直接失败），变异即此处红。
        val doc = json.parseToJsonElement(
            """{"schemaVersion":9,"agent":{"enabled":false}}""",
        ).jsonObject
        val processed = ConfigMigrations.migrateV9ToV10(17, doc)
        assertTrue(!processed["agent"]!!.jsonObject.containsKey("enabled"), "真 v9 文档（version=17）必须被本步处理")
    }

    @Test
    fun `守卫差一防线 - v10 文档（收到 version 18）跳过 v9→v10 步`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":10,"agent":{}}""").jsonObject
        val untouched = ConfigMigrations.migrateV9ToV10(18, doc)
        assertEquals(doc, untouched, "v10 文档不应再动")
    }

    @Test
    fun `v9 routes 的 reply 条目整条剥离且 trigger-reply 非空折 retiredV10 保底`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":9,"routes":[{"kind":"system","id":"builtin"},""" +
                """{"kind":"reply","id":"help","enabled":true,"trigger":"/help","match":"exact","reply":"内置帮助"}]}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val routes = migrated["routes"]!!.jsonArray
        assertEquals(1, routes.size, "reply 条目整条剥离（固定回复槽退役）")
        assertEquals("builtin", routes[0].jsonObject["id"]!!.jsonPrimitive.content, "非 reply 条目原样保留")
        val retired = migrated["experimental"]!!.jsonObject["retiredV10"]!!.jsonObject
        assertEquals("/help", retired["routes.reply.help.trigger"]!!.jsonPrimitive.content, "非空 trigger 保底可见")
        assertEquals("内置帮助", retired["routes.reply.help.reply"]!!.jsonPrimitive.content, "非空 reply 保底可见")
        // 🔴 剥离的必要性锚：typed 层已无 ReplyRoute，不剥离则 sealed 多态 decode 直接失败
        val config = BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), migrated)
        assertEquals(1, config.routes.size)
    }

    @Test
    fun `v9 routes 的空壳 reply 条目静默丢（无保底）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":9,"routes":[{"kind":"reply","id":"empty","enabled":true}]}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val routes = migrated["routes"]?.jsonArray
        assertTrue(routes == null || routes.isEmpty(), "空壳 reply 条目剥离后 routes 收敛为空：$routes")
        val experimental = migrated["experimental"]
        assertTrue(experimental == null || !experimental.jsonObject.containsKey("retiredV10"),
            "trigger/reply 均空 = 无信息量，不搬：$experimental")
    }

    // ── v5→v6（2026-09-13 Range 分片批）：fileMessage.maxFileSizeBytes 旧默认显式值删键收敛 1GB ──

    @Test
    fun `v5 旧默认显式 maxFileSizeBytes 删键后盖版本戳（decode 落新默认 1GB）`() {
        // encodeDefaults=true ⇒ 存量文档几乎必然躺着等于旧默认（104857600）的显式键
        val doc = json.parseToJsonElement(
            """{"schemaVersion":5,"fileMessage":{"enabled":true,"maxFileSizeBytes":104857600}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        val fm = migrated["fileMessage"]!!.jsonObject
        assertTrue(!fm.containsKey("maxFileSizeBytes"), "旧默认 100MB 显式键应删（decode 落新默认 1GB）")
        assertEquals("true", fm["enabled"]!!.jsonPrimitive.content, "非处理键原样保留")
        // decode 后真落到新默认
        val config = BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), migrated)
        assertEquals(1_073_741_824L, config.fileMessage.maxFileSizeBytes, "删键后 decode 须落新默认 1GB")
    }

    @Test
    fun `v5 用户自定义 maxFileSizeBytes 不删键（尊重调参）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":5,"fileMessage":{"maxFileSizeBytes":524288000}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val fm = migrated["fileMessage"]!!.jsonObject
        assertEquals(
            524288000, fm["maxFileSizeBytes"]!!.jsonPrimitive.int,
            "用户调过的 500MB 原样保留（尊重用户调参，绝不静默改动）",
        )
    }

    @Test
    fun `缺 fileMessage 段时 v5→v6 no-op`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":5,"access":{"trustedUsers":["ou_a"]}}""").jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        assertTrue(!migrated.containsKey("fileMessage"), "无 fileMessage 段不凭空造段")
        assertEquals("ou_a", migrated["access"]!!.jsonObject["trustedUsers"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `v6 文档里用户显式设回 100MB 不得被误删（守卫语义）`() {
        // 🔴 守卫防线：v6+ 文档（收到 version>=10）必须跳过本步——升级后用户可能**故意**把上限调回 100MB
        //    （值恰等于旧默认），守卫失效（写 `>= 11`）会每次加载都把这个用户选择静默删掉。
        val doc = json.parseToJsonElement(
            """{"schemaVersion":6,"fileMessage":{"maxFileSizeBytes":104857600}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(
            104857600, migrated["fileMessage"]!!.jsonObject["maxFileSizeBytes"]!!.jsonPrimitive.int,
            "v6 文档里的显式 100MB 是用户选择，绝不得被迁移误删（off-by-one 变异即此处红）",
        )
    }

    // ── v4→v5（2026-09-12 自研长连接下线批）：connection.selfBuiltWs 退役 ──

    @Test
    fun `v4 显式开启的 selfBuiltWs 折入 retiredV4 后从 connection 移除`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":4,"connection":{"appId":"a","selfBuiltWs":true}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        val connection = migrated["connection"]!!.jsonObject
        assertTrue(!connection.containsKey("selfBuiltWs"), "退役键应已从 connection 移除（typed 域已无此字段）")
        assertEquals("a", connection["appId"]!!.jsonPrimitive.content, "非退役键原样保留")
        val retired = migrated["experimental"]!!.jsonObject["retiredV4"]!!.jsonObject
        assertEquals("true", retired["selfBuiltWs"]!!.jsonPrimitive.content, "用户曾显式开启 ⇒ 保底进 retiredV4（看得见什么被退役）")
        // decode 成功且无此字段
        val config = BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), migrated)
        assertEquals(ConfigMigrations.CURRENT_VERSION, config.schemaVersion)
    }

    @Test
    fun `v4 selfBuiltWs 为默认值 false 时直接丢弃（不进 retiredV4）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":4,"connection":{"appId":"a","selfBuiltWs":false}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val connection = migrated["connection"]!!.jsonObject
        assertTrue(!connection.containsKey("selfBuiltWs"), "默认值键同样要从 connection 移除")
        // 🔴 全默认 ⇒ 无信息量 ⇒ 不产 retiredV4 桶
        val experimental = migrated["experimental"]
        assertTrue(experimental == null || !experimental.jsonObject.containsKey("retiredV4"),
            "默认值无信息量，不搬：$experimental")
    }

    @Test
    fun `缺 connection 段时 v4→v5 no-op`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":4,"access":{"trustedUsers":["ou_a"]}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("ou_a", migrated["access"]!!.jsonObject["trustedUsers"]!!.jsonArray[0].jsonPrimitive.content)
    }

    // ── v6→v7（批 6-R 调度层正名）──────────────────────────────────────────

    @Test
    fun `v6 backend=koog 语义等价转换 - claude 且调度意图由 agent-first 默认承载`() {
        // 🔴 P3（2026-09-26）链式终点更新：v6→v7 步仍写 dispatchEnabled=true（迁移链内语义等价），
        //    但随即被 v7→v8 P3 段退役——「开启调度」的意图由 agent-first 默认语义承载（App 形态
        //    恒调度），残键折入 retiredV8 保底。终态断言：无 dispatchEnabled 键 + 保底可见。
        val doc = json.parseToJsonElement(
            """{"schemaVersion":6,"agent":{"enabled":true,"backend":"koog","systemPrompt":"s"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
        val agent = migrated["agent"]!!.jsonObject
        // koog→claude 转换的终态证据：backend 落默认 claude ⇒ 被 v10→v11 静默丢（若转换失效保留 "koog"，
        // 非默认值会折入 retiredV11.agent.backend——下一行断言即红）
        assertTrue(!agent.containsKey("backend"), "backend 已随 CLI 委托链退役")
        assertTrue(
            migrated["experimental"]!!.jsonObject["retiredV11"] == null,
            "转换后的 claude 是默认值 ⇒ 不进 retiredV11（含 \"koog\" 即 koog→claude 转换失效）",
        )
        assertTrue(!agent.containsKey("dispatchEnabled"), "P3 起配置面无此键")
        assertEquals(true, migrated["experimental"]!!.jsonObject["retiredV8"]!!.jsonObject["agent.dispatchEnabled"]!!.jsonPrimitive.boolean,
            "v6 选 koog 的调度意图进 retiredV8 保底（用户可见什么被退役）")
        // 其他 agent 键原样保留
        assertEquals("s", agent["systemPrompt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `v6 backend=claude 不动且不开启调度（存量 CLI 用户零变化）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":6,"agent":{"backend":"claude","enabled":true}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val agent = migrated["agent"]!!.jsonObject
        // 全链终态：backend=claude 是默认值 ⇒ v10→v11 静默丢；enabled=true 是默认值 ⇒ v9→v10 静默丢
        assertTrue(agent.isEmpty(), "全默认 agent 段收敛为空对象：$agent")
        // dispatchEnabled 不被 v7 步主动写入（无 koog 可转即 no-op）——decode 落 typed 默认 false
        assertTrue(!agent.containsKey("dispatchEnabled"))
        val config = BridgeJson.decodeFromJsonElement(BridgeConfig.serializer(), migrated)
        assertTrue(config.agent.systemPrompt.isEmpty(), "typed 解码零变化")
    }

    @Test
    fun `v6→v7 幂等（迁移产物再迁不动）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":6,"agent":{"backend":"koog"}}""",
        ).jsonObject
        val once = ConfigMigrations.migrate(doc)
        val twice = ConfigMigrations.migrate(once)
        assertEquals(once, twice)
    }

    @Test
    fun `v6 无 agent 段时 v7 步 no-op`() {
        val doc = json.parseToJsonElement("""{"schemaVersion":6,"connection":{"appId":"a"}}""").jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals("a", migrated["connection"]!!.jsonObject["appId"]!!.jsonPrimitive.content)
        assertEquals(ConfigMigrations.CURRENT_VERSION, migrated["schemaVersion"]!!.jsonPrimitive.int)
    }

    // ── v12→v13（access 白名单退役 → 信任用户名单）────────────────────────────

    @Test
    fun `v12→v13 旧白名单平移为信任用户，blockedSenders 与 mode 折保底`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":12,"access":{"mode":"blocklist","allowedSenders":["ou_a","ou_b"],"blockedSenders":["ou_bad"]}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val access = migrated["access"]!!.jsonObject

        assertEquals(
            listOf("ou_a", "ou_b"),
            access.getValue("trustedUsers").jsonArray.map { it.jsonPrimitive.content },
            "旧白名单成员必须平移为信任用户",
        )
        assertEquals(null, access["mode"], "mode 已退役")
        assertEquals(null, access["allowedSenders"], "allowedSenders 已退役")
        assertEquals(null, access["blockedSenders"], "blockedSenders 已退役")

        val retired = migrated["experimental"]!!.jsonObject["retiredV13"]!!.jsonObject
        assertEquals("blocklist", retired.getValue("access.mode").jsonPrimitive.content, "非默认 mode 须折保底")
        assertEquals(
            listOf("ou_bad"),
            retired.getValue("access.blockedSenders").jsonArray.map { it.jsonPrimitive.content },
            "黑名单拦截语义消失，须让用户在配置里看得见",
        )
    }

    @Test
    fun `v12→v13 默认 access 段静默丢（不留保底噪音）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":12,"access":{"mode":"allowlist","allowedSenders":[],"blockedSenders":[]}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        assertEquals(null, migrated["experimental"]?.jsonObject?.get("retiredV13"), "全默认段不该产生保底桶")
    }

    @Test
    fun `v12→v13 幂等（迁移产物再迁不动）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":12,"access":{"mode":"allowlist","allowedSenders":["ou_a"],"blockedSenders":[]}}""",
        ).jsonObject
        val once = ConfigMigrations.migrate(doc)
        val twice = ConfigMigrations.migrate(once)
        assertEquals(once, twice)
    }

    // ── v13→v14（能力域注册闸退役）──────────────────────────────────────────

    @Test
    fun `v13→v14 四域整段删除，非默认取值折 retiredV14`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":13,"agent":{"sysTools":{"enabled":true,"probeEnabled":true},""" +
                """"devTools":{"enabled":false,"devicesEnabled":false},""" +
                """"selfStatus":{"enabled":false},"startupProbe":{"enabled":false}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val agent = migrated["agent"]!!.jsonObject

        listOf("sysTools", "devTools", "selfStatus", "startupProbe").forEach {
            assertNull(agent[it], "$it 段必须整段退役")
        }
        val retired = migrated["experimental"]!!.jsonObject["retiredV14"]!!.jsonObject
        assertEquals(true, retired.getValue("agent.sysTools.enabled").jsonPrimitive.boolean)
        assertEquals(true, retired.getValue("agent.sysTools.probeEnabled").jsonPrimitive.boolean)
        // 全默认段静默丢（不产生保底噪音）
        assertNull(retired["agent.devTools.enabled"])
        assertNull(retired["agent.selfStatus.enabled"])
    }

    @Test
    fun `v13→v14 wsTools 只退役四个开关，保留 runTaskTimeoutMinutes`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":13,"agent":{"wsTools":{"enabled":true,"writeEnabled":true,""" +
                """"runTaskEnabled":false,"gitEnabled":true,"runTaskTimeoutMinutes":25}}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(doc)
        val ws = migrated["agent"]!!.jsonObject["wsTools"]!!.jsonObject

        assertEquals(25, ws.getValue("runTaskTimeoutMinutes").jsonPrimitive.int, "数值钳制项必须保留")
        listOf("enabled", "writeEnabled", "runTaskEnabled", "gitEnabled").forEach {
            assertNull(ws[it], "$it 开关必须退役")
        }
        val retired = migrated["experimental"]!!.jsonObject["retiredV14"]!!.jsonObject
        assertEquals(true, retired.getValue("agent.wsTools.enabled").jsonPrimitive.boolean)
        assertEquals(true, retired.getValue("agent.wsTools.writeEnabled").jsonPrimitive.boolean)
        assertEquals(true, retired.getValue("agent.wsTools.gitEnabled").jsonPrimitive.boolean)
        assertNull(retired["agent.wsTools.runTaskEnabled"], "默认值开关静默丢")
    }

    // ── v14→v15（xlog 解密恒启用）────────────────────────────────────────────

    @Test
    fun `v14→v15 xlogDecodeEnabled 退役（显式 true 折保底，false 静默丢）`() {
        val on = json.parseToJsonElement(
            """{"schemaVersion":14,"fileMessage":{"xlogDecodeEnabled":true,"xlogPrivateKey":"k"}}""",
        ).jsonObject
        val migrated = ConfigMigrations.migrate(on)
        val fm = migrated["fileMessage"]!!.jsonObject
        assertNull(fm["xlogDecodeEnabled"], "开关必须退役")
        assertEquals("k", fm.getValue("xlogPrivateKey").jsonPrimitive.content, "私钥保留")
        assertEquals(
            true,
            migrated["experimental"]!!.jsonObject["retiredV15"]!!.jsonObject
                .getValue("fileMessage.xlogDecodeEnabled").jsonPrimitive.boolean,
            "显式开启折保底可见",
        )

        val off = json.parseToJsonElement("""{"schemaVersion":14,"fileMessage":{"xlogDecodeEnabled":false}}""").jsonObject
        assertNull(
            ConfigMigrations.migrate(off)["experimental"]?.jsonObject?.get("retiredV15"),
            "默认 false 静默丢，不产生保底噪音",
        )
    }

    @Test
    fun `v13→v14 幂等（迁移产物再迁不动）`() {
        val doc = json.parseToJsonElement(
            """{"schemaVersion":13,"agent":{"sysTools":{"enabled":true},"wsTools":{"enabled":true,"runTaskTimeoutMinutes":15}}}""",
        ).jsonObject
        val once = ConfigMigrations.migrate(doc)
        val twice = ConfigMigrations.migrate(once)
        assertEquals(once, twice)
    }
}
