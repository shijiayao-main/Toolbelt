package com.jiaoay.lark.bridge.config

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.handler.CommandHandler
import com.jiaoay.lark.bridge.handler.CompositeHandler
import com.jiaoay.lark.bridge.handler.StopHandler
import com.jiaoay.lark.bridge.handler.SystemHandler
import com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink
import com.jiaoay.lark.bridge.pipeline.FakeAgentRunner
import com.jiaoay.lark.bridge.pipeline.HandlerDeps
import com.jiaoay.lark.bridge.pipeline.HandlerKinds
import com.jiaoay.lark.bridge.pipeline.PipelineRouter
import com.jiaoay.lark.bridge.pipeline.PipelineSnapshotFactory
import com.jiaoay.lark.bridge.pipeline.RecordingReplySink
import com.jiaoay.lark.bridge.pipeline.RequestContext
import com.jiaoay.lark.bridge.pipeline.msg
import com.jiaoay.lark.gateway.LarkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [CDS §13.8] LegacyDocumentMapper：可迁移 key 全覆盖 + 单 key 失败不阻断 + 3 项废弃显式记录
 * + whitelist→allowlist + file_max_size_mb ×1048576 + workspace_whitelist JSON 数组
 * + static_replies 退役不迁移（REPLY 槽已退役）+ skills→routes[command] + agent_prefix→experimental.retiredV8 旧词通道。
 * CLI 委托链退役后的键（agent_work_dir/backend/custom_template/permission_preset/allowed_tools/
 * prohibit_delete/safety_prompt_enabled/confirm_enabled 等）一律静默丢弃、不炸导入。
 *
 * 🔴 另含两组回归守护（`build/t15-migration-preflight.md` 风险节）：
 * - **R1**：合成 route 与既有 routes **同 id ⇒ 就地整条替换并保留原下标**（复现旧 `BuiltInQuickCommands`
 *   「用户 skills 同 id 覆盖先条合成条目」的能力；默认 routes 已无 CommandRoute，产品裁定去业务化），
 *   含一条经 [PipelineRouter] 的端到端命中断言；
 * - **R4**：Map 中**不含** `agent_prefix` 键时 retiredV8 不落 agent 旧词键（词表回落 EngineReservedWords
 *   常量默认 `["ai","/ai"]`；P3 起 routes 数据退役，不再有 AgentRoute.triggers 字段）。
 */
class LegacyDocumentMapperTest {

    private fun mapOf(vararg pairs: Pair<String, String>) = pairs.toMap()

    @Test
    fun `全量可迁移 key 逐项映射正确（退役键静默丢弃）`() {
        val legacy = mapOf(
            "lark_bridge_app_id" to "cli_app",
            "lark_bridge_app_secret" to "sec",
            "lark_bridge_chat_id" to "oc_chat",
            "lark_bridge_dry_run" to "true",
            "lark_bridge_access_mode" to "blacklist",
            "lark_bridge_allowed_senders" to "ou_1, ou_2 ,, ou_3",
            "lark_bridge_blocked_senders" to "ou_bad",
            "lark_bridge_rejection_reply" to "拒绝：{sender}",
            "lark_bridge_group_require_mention" to "false",
            "lark_bridge_workspace_dir" to "/opt/ws",
            "lark_bridge_workspace_whitelist" to """["/a","/b"]""",
            "lark_bridge_agent_work_dir" to "~/agent",
            "lark_bridge_agent_enabled" to "true",
            "lark_bridge_agent_backend" to "qoder",
            "lark_bridge_agent_custom_template" to "tpl {prompt}",
            "lark_bridge_agent_system_prompt" to "sys",
            "lark_bridge_agent_permission_preset" to "workspace",
            "lark_bridge_agent_allowed_tools" to "Read,Write",
            "lark_bridge_agent_prohibit_delete" to "false",
            "lark_bridge_agent_safety_prompt_enabled" to "false",
            "lark_bridge_agent_timeout_minutes" to "25",
            "lark_bridge_agent_confirm_enabled" to "true",
            "lark_bridge_agent_confirm_timeout_seconds" to "90",
            "lark_bridge_agent_prefix" to "ai,/ai,bot",
            "lark_bridge_agent_reset_command" to "重来",
            "lark_bridge_agent_stop_command" to "停下",
            "lark_bridge_unhandled_reply" to "没听懂",
            "lark_bridge_file_message_enabled" to "false",
            "lark_bridge_file_max_size_mb" to "50",
            "lark_bridge_file_auto_extract" to "false",
        )
        val result = LegacyDocumentMapper.mapWithReport(legacy)
        val c = result.config
        // connection
        assertEquals("cli_app", c.connection.appId)
        assertEquals("sec", c.connection.appSecret)
        assertEquals("oc_chat", c.connection.testChatId)
        assertEquals(true, c.connection.dryRun)
        // access（准入白名单退役：旧白名单平移为「信任用户」；blockedSenders/mode 无对应
        // 载体，旧值丢弃不炸导入）
        assertEquals(listOf("ou_1", "ou_2", "ou_3"), c.access.trustedUsers)
        // workspace（agentWorkDir 随 CLI 委托链退役——旧键静默丢弃）
        assertEquals("/opt/ws", c.workspace.root)
        assertEquals(listOf("/a", "/b"), c.workspace.extraRoots)
        // agent（agent_enabled 退役不迁移——agent 恒为硬依赖；backend/customTemplate/
        // permissionPreset/allowedTools/prohibitDelete/safetyPromptEnabled/confirm.enabled
        // 同批退役，旧值丢弃不炸导入；failedKeys 为空已由文件尾断言钉住）
        assertEquals("sys", c.agent.systemPrompt)
        assertEquals(90L, c.agent.confirm.timeoutSeconds)
        // runtime（unhandledReply 退役不迁移——TEXT 兜底归调度 agent）
        assertEquals(25L, c.runtime.agentTimeoutMinutes)
        // fileMessage
        assertEquals(false, c.fileMessage.enabled)
        assertEquals(50L * 1048576L, c.fileMessage.maxFileSizeBytes)
        assertEquals(false, c.fileMessage.autoExtract)
        // routes[agent]（AgentRoute 匹配键退役——旧词折入 experimental.retiredV8）；
        // typed routes 里不再有 AgentRoute 条目（默认两槽 system+file，旧 agent 词不入 routes）。
        val retired = c.experimental["retiredV8"] as kotlinx.serialization.json.JsonObject
        assertEquals("ai,/ai,bot", (retired["routes.agent.trigger"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("重来", (retired["routes.agent.resetCommand"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("停下", (retired["routes.agent.stopCommand"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(result.report.failedKeys.isEmpty())
    }

    @Test
    fun `旧 access_mode 键退役（准入恒放行，无 mode 载体）`() {
        assertEquals(emptyList(), LegacyDocumentMapper.map(mapOf("lark_bridge_access_mode" to "whitelist")).access.trustedUsers)
        assertEquals(emptyList(), LegacyDocumentMapper.map(mapOf("lark_bridge_access_mode" to "blacklist")).access.trustedUsers)
    }

    @Test
    fun `file_max_size_mb 乘 1048576`() {
        assertEquals(100L * 1048576L, LegacyDocumentMapper.map(mapOf("lark_bridge_file_max_size_mb" to "100")).fileMessage.maxFileSizeBytes)
        // 非法值回退默认 100
        assertEquals(100L * 1048576L, LegacyDocumentMapper.map(mapOf("lark_bridge_file_max_size_mb" to "abc")).fileMessage.maxFileSizeBytes)
    }

    @Test
    fun `workspace_whitelist JSON 数组串解析 - 非法回退空列表`() {
        assertEquals(listOf("/x", "/y"), LegacyDocumentMapper.map(mapOf("lark_bridge_workspace_whitelist" to """["/x","/y"]""")).workspace.extraRoots)
        assertTrue(LegacyDocumentMapper.map(mapOf("lark_bridge_workspace_whitelist" to "not-json")).workspace.extraRoots.isEmpty())
    }

    @Test
    fun `static_replies 退役不迁移（键存在时忽略不炸导入）`() {
        // 固定回复槽整槽退役：旧 static_replies 键被静默忽略，不合成任何 route、不落保底
        val json = """[{"trigger":"/t1","reply":"r1","enabled":true},{"trigger":"/t2","reply":"r2","enabled":false}]"""
        val result = LegacyDocumentMapper.mapWithReport(mapOf("lark_bridge_static_replies" to json))
        val c = result.config
        assertTrue(c.routes.none { it.id.startsWith("reply-") }, "不得合成 reply 条目")
        assertEquals(DefaultBridgeConfig.DEFAULT_ROUTES, c.routes, "routes 保持默认形态")
        assertTrue(result.report.failedKeys.isEmpty(), "键存在不得炸导入")
    }

    @Test
    fun `skills JSON 到多条 routes-command`() {
        val json = """[{"id":"deploy","name":"部署","aliases":["/deploy"],"description":"d","promptTemplate":"/deploy {args}","enabled":true,"requiresConfirm":true}]"""
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_skills" to json))
        val cmd = c.routes.filterIsInstance<CommandRoute>().first { it.id == "deploy" }
        assertEquals("部署", cmd.name)
        assertEquals(listOf("/deploy"), cmd.aliases)
        assertEquals("/deploy {args}", cmd.promptTemplate)
        assertEquals(true, cmd.requiresConfirm)
    }

    @Test
    fun `agent_prefix 折入 retiredV8（P3 旧词通道，逗号串形态）`() {
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_agent_prefix" to "ai,/ai"))
        // "ai,/ai" 与默认值同 ⇒ 默认值静默丢（无信息量），retiredV8 不落键
        assertTrue(
            (c.experimental["retiredV8"] as? kotlinx.serialization.json.JsonObject)?.containsKey("routes.agent.trigger") != true,
            "与默认前缀相同 ⇒ 不落 retiredV8（retireLegacyAgentWord 的默认值静默丢语义）",
        )
        // 非默认前缀 ⇒ 折入 retiredV8（逗号串）
        val custom = LegacyDocumentMapper.map(mapOf("lark_bridge_agent_prefix" to "bot,/bot"))
        val retired = custom.experimental["retiredV8"] as kotlinx.serialization.json.JsonObject
        assertEquals("bot,/bot", (retired["routes.agent.trigger"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `3 项废弃 key 出现在迁移报告中显式记录（P9 裁决 9）`() {
        val legacy = mapOf(
            "lark_bridge_transport" to "cli",
            "lark_bridge_token_dir" to "/tok",
            "lark_bridge_preset_migration_notice" to "true",
            "lark_bridge_app_id" to "a",
        )
        val report = LegacyDocumentMapper.mapWithReport(legacy).report
        assertEquals(3, report.discardedKeys.size)
        assertTrue(report.discardedKeys.contains("lark_bridge_transport"))
        assertTrue(report.discardedKeys.contains("lark_bridge_token_dir"))
        assertTrue(report.discardedKeys.contains("lark_bridge_preset_migration_notice"))
        assertFalse(report.isEmpty)
    }

    @Test
    fun `废弃 key 不存在时报告不列出`() {
        val report = LegacyDocumentMapper.mapWithReport(mapOf("lark_bridge_app_id" to "a")).report
        assertTrue(report.discardedKeys.isEmpty())
    }

    @Test
    fun `残留 key 一律忽略但记入 ignoredResidualKeys`() {
        val legacy = mapOf(
            "lark_bridge_event_key" to "e",
            "lark_bridge_lark_cli_path" to "/cli",
            "lark_bridge_region" to "cn",
        )
        val result = LegacyDocumentMapper.mapWithReport(legacy)
        assertEquals(3, result.report.ignoredResidualKeys.size)
        // 不影响正常默认
        assertEquals("", result.config.connection.appId)
    }

    @Test
    fun `单 key 失败不阻断其余（非法 timeout 值走默认，其余正常）`() {
        val legacy = mapOf(
            "lark_bridge_app_id" to "good",
            "lark_bridge_agent_timeout_minutes" to "not-a-number", // toLongOrNull ?: 10 → 不失败，走默认
            "lark_bridge_workspace_whitelist" to "[broken", // JSON 解析失败回退空，不阻断
            "lark_bridge_agent_enabled" to "true",
        )
        val result = LegacyDocumentMapper.mapWithReport(legacy)
        assertEquals("good", result.config.connection.appId)
        assertEquals(10L, result.config.runtime.agentTimeoutMinutes) // 回退默认
        // agent_enabled 已退役不迁移：键存在时被静默丢弃，不影响其余 key 的映射
        assertTrue(result.report.failedKeys.isEmpty())
        assertTrue(result.config.workspace.extraRoots.isEmpty())
    }

    @Test
    fun `空输入返回默认文档`() {
        val c = LegacyDocumentMapper.map(emptyMap())
        assertEquals(DefaultBridgeConfig.default(), c)
    }

    @Test
    fun `内置 routes 保留（system 与 file 不被 legacy 覆盖；P1 后默认条目仅两槽）`() {
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_app_id" to "a"))
        assertTrue(c.routes.any { it is SystemRoute })
        assertTrue(c.routes.any { it is FileRoute })
        // P1 引擎保留词固化（2026-09-26）：/help 与 agent 的默认条目已从 DEFAULT_ROUTES 退役——
        // /help 内置化进 SystemHandler、agent 词由 EngineReservedWords 接管（旧 DataStore 的
        // agent_prefix/reset/stop 键折入 experimental.retiredV8，见 R4 用例；P3 起不再合成 AgentRoute 条目）。
    }

    // ==================================================================================
    // 🔴 R1：routes 同 id 覆盖回归（旧 BuiltInQuickCommands「用户 skills 同 id 覆盖内置」的能力必须一个不少）
    // ==================================================================================

    /** R1-a：同 id 覆盖 ⇒ 长度不变 + 坐在既有条目原下标 + 内容取自用户旧值 + 其余默认条目逐条原样。
     *  🔴 默认 routes 已无 CommandRoute（产品裁定去业务化）⇒ 用两条**用户 skill 同 id** 构造同 id 冲突：
     *  第一条合成为 route，第二条同 id 在 merge 时就地替换第一条（保留下标 = 追加位置）。 */
    @Test
    fun `R1 用户 skill 同 id 后条就地整条替换先条并保留其下标`() {
        val json = """[{"id":"deploy","name":"部署","aliases":["/deploy"],""" +
            """"description":"第一版","promptTemplate":"/v1 {args}","enabled":true,"requiresConfirm":true},""" +
            """{"id":"deploy","name":"我的部署","aliases":["/deploy","/dep"],""" +
            """"description":"用户自定义描述","promptTemplate":"/my-skill {args}","enabled":true,"requiresConfirm":true}]"""
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_skills" to json))
        val defaults = DefaultBridgeConfig.DEFAULT_ROUTES
        // 两条 skill 中第二条同 id ⇒ 合成后只有 1 条 deploy（不是 2 条）
        assertEquals(1, c.routes.count { it is CommandRoute && it.id == "deploy" })
        // 🔴 长度 = 默认 + 1（同 id 后条替换先条，不再追加）
        assertEquals(defaults.size + 1, c.routes.size)
        // 🔴 覆盖后的条目坐在先条的下标上（默认 routes 之后的第一位）
        val overridden = c.routes[defaults.size] as CommandRoute
        assertEquals("我的部署", overridden.name)
        assertEquals(listOf("/deploy", "/dep"), overridden.aliases)
        assertEquals("用户自定义描述", overridden.description)
        assertEquals("/my-skill {args}", overridden.promptTemplate)
        assertEquals(true, overridden.requiresConfirm)
        // 🔴 默认 routes 逐条原样、下标不动
        assertEquals(defaults, c.routes.take(defaults.size))
    }

    /** R1-b：id 不冲突 ⇒ 追加末尾（🔴 绝不插到默认条目之前，默认条目整体前移不变）。 */
    @Test
    fun `R1 用户 skill id 不冲突时追加在 routes 末尾且默认条目下标不变`() {
        val json = """[{"id":"my-skill","name":"自定义","aliases":["/my"],"description":"d","promptTemplate":"/x {args}","enabled":true,"requiresConfirm":false}]"""
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_skills" to json))
        val defaults = DefaultBridgeConfig.DEFAULT_ROUTES
        assertEquals(defaults.size + 1, c.routes.size)
        // 🔴 默认条目整体逐条等于原默认（用户条目绝不插到默认条目之前）
        assertEquals(defaults, c.routes.take(defaults.size))
        assertEquals("my-skill", c.routes.last().id)
    }

    /**
     * R1-d 🔴 **端到端**：覆盖后经 [PipelineRouter] 对该 id 的匹配**命中后条而非先条**。
     *
     * 判别力来自「两条用户 skill 同 id `deploy`，第二条保留别名但改掉 promptTemplate」：不合并式合成下
     * 数组里会有两条 deploy（先条在前），`PipelineSnapshot.resolve` 取**数组首个命中** ⇒ 命中的会是先条
     * （promptTemplate `/v1 {args}`），后条覆盖静默失效。同 id 就地替换后数组只剩后条。
     */
    @Test
    fun `R1 端到端 PipelineRouter 对同 id deploy 的匹配命中后条而非先条`() {
        val json = """[{"id":"deploy","name":"部署","aliases":["/deploy"],""" +
            """"description":"第一版","promptTemplate":"/v1 {args}","enabled":true,"requiresConfirm":false},""" +
            """{"id":"deploy","name":"我的部署","aliases":["/deploy"],""" +
            """"description":"用户自定义描述","promptTemplate":"/my-skill {args}","enabled":true,"requiresConfirm":false}]"""
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_skills" to json))

        // 🔴 用生产 handler（CommandHandler）与生产 PipelineSnapshotFactory/PipelineRouter，不造假件路由
        val kinds = HandlerKinds.build(command = { route, d -> CommandHandler(route as CommandRoute, d) })
        val snapshot = PipelineSnapshotFactory(kinds, HandlerDeps({ c }, BridgeLog.Silent)).build(c)
        val router = PipelineRouter(snapshot)

        val message = msg(text = "/deploy 执行部署")
        val ctx = RequestContext(
            message = message,
            config = c,
            route = null,
            reply = RecordingReplySink(LarkTarget.ofInbound(message)),
            progress = ConflatedProgressSink(),
            log = BridgeLog.Silent,
            agent = FakeAgentRunner(),
            processes = ProcessRegistry(2),
        )
        val matched = router.route(ctx)
        assertNotNull(matched)
        assertEquals("deploy", matched.routeId)
        // 🔴🔴 命中**后条**：整条内容取自后条用户值（不合并式合成下这里会是先条的 "部署" / "/v1 {args}"）
        val hit = matched.route as CommandRoute
        assertEquals("我的部署", hit.name)
        assertEquals("用户自定义描述", hit.description)
        assertEquals("/my-skill {args}", hit.promptTemplate)
        // 🔴 同 id 在快照里只有一条（不合并式合成下会是 2 条 deploy）
        assertEquals(1, snapshot.entries.count { it.routeId == "deploy" })
        assertEquals(ctx.route, hit)
    }

    // ==================================================================================
    // 🔴 R2：匹配键冲突（同触发条件、不同 id ⇒ 就地替换保留下标，消解真实数据实证的永久遮蔽缺陷）
    // ==================================================================================

    /**
     * R2-b 🔴 **P1 语义更新（2026-09-26）**：/help 已内置化进 SystemHandler（引擎保留词）——
     * 无论用户 routes 数据里是否带 /help 别名（skills 合成的 CommandRoute；static_replies 已随
     * REPLY 槽退役），管线都**先命中内置 help 直答**（路由数组序 SystemRoute 恒在前）。
     */
    @Test
    fun `R2 端到端 PipelineRouter 对 help 恒命中 SystemHandler 内置直答（P1 内置化语义）`() {
        // 用户旧数据携带 /help 别名（skills 合成；静态回复键已退役不迁移）
        val json = """[{"id":"user-help","name":"我的帮助","aliases":["/help"],"promptTemplate":"测试"}]"""
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_skills" to json))

        // 🔴 用生产 handler（SystemHandler+CommandHandler）与生产 PipelineSnapshotFactory/PipelineRouter
        val kinds = HandlerKinds.build(
            system = { _, d -> CompositeHandler(RouteKind.SYSTEM, listOf(SystemHandler(d), StopHandler(null, d))) },
            command = { route, d -> CommandHandler(route as CommandRoute, d) },
        )
        val snapshot = PipelineSnapshotFactory(kinds, HandlerDeps({ c }, BridgeLog.Silent)).build(c)
        val router = PipelineRouter(snapshot)

        val message = msg(text = "/help")
        val ctx = RequestContext(
            message = message,
            config = c,
            route = null,
            reply = RecordingReplySink(LarkTarget.ofInbound(message)),
            progress = ConflatedProgressSink(),
            log = BridgeLog.Silent,
            agent = FakeAgentRunner(),
            processes = ProcessRegistry(2),
        )
        val matched = router.route(ctx)
        // routes 里用户 /help 条目追加在末尾（SystemRoute 恒在前）⇒ /help 命中内置直答，非用户条目
        assertNotNull(matched, "/help 须命中内置 SystemHandler")
        assertEquals(RouteKind.SYSTEM, matched.handler.kind)
        assertEquals("builtin", matched.routeId)
    }

    /**
     * R2-c：CommandRoute 别名重叠——用户先合成的 skill id=`first`，后条 id=`second` 不撞 id，
     * 但 aliases 含 `/deploy` 与先条重叠 ⇒ 匹配键冲突 ⇒ 就地替换、保留先条下标。
     */
    @Test
    fun `R2 用户 skill 别名重叠先条别名时就地替换保留下标`() {
        val json = """[{"id":"first","name":"第一版","aliases":["/deploy","/d1"],""" +
            """"description":"第一版","promptTemplate":"/v1 {args}","enabled":true,"requiresConfirm":false},""" +
            """{"id":"second","name":"我的分析","aliases":["/deploy","/custom"],""" +
            """"description":"用户自定义","promptTemplate":"/my-tool {args}","enabled":true,"requiresConfirm":false}]"""
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_skills" to json))
        val defaults = DefaultBridgeConfig.DEFAULT_ROUTES
        // 🔴 长度 = 默认 + 1（别名重叠就地替换先条，不再追加）
        assertEquals(defaults.size + 1, c.routes.size, "别名重叠须就地替换，长度不变")
        // 🔴 先条的下标上坐的是后条（id/内容整条取后条）
        val hit = c.routes[defaults.size] as CommandRoute
        assertEquals("second", hit.id, "整条替换取后条 id")
        assertEquals("我的分析", hit.name)
        assertEquals(listOf("/deploy", "/custom"), hit.aliases)
        assertEquals("/my-tool {args}", hit.promptTemplate)
        // 🔴 其余默认条目原样
        assertEquals(defaults, c.routes.take(defaults.size))
    }

    /**
     * R2-e 🔴 **两道防线自洽**：用户数据携带 /help 别名（skills 合成追加末尾，static_replies 已退役）
     * 时，ConfigValidator 不应产 routes 匹配键重复 issue（内置 help 直答不占路由位，无第二条 /help 条目）。
     */
    @Test
    fun `R2 ConfigValidator 不对用户 help 别名追加结果产 routes 重复 issue（内置 help 不占路由位）`() {
        val json = """[{"id":"user-help","name":"我的帮助","aliases":["/help"],"promptTemplate":"测试"}]"""
        val c = LegacyDocumentMapper.map(mapOf("lark_bridge_skills" to json))
        val issues = ConfigValidator.validate(c)
        assertTrue(
            issues.none { it.fieldId == "routes" },
            "内置 help 已不占路由位 ⇒ 用户 /help 别名唯一，不应产 routes 重复 issue；实际 issues=$issues",
        )
    }

    // ==================================================================================
    // 🔴 R4：agent 旧词退役折入 retiredV8（P3 语义：routes 数据退役，EngineReservedWords 为真源）
    // ==================================================================================

    /** 读 retiredV8 下的单个字符串值（无段/无键返回 null）。 */
    private fun retiredV8Word(c: BridgeConfig, key: String): String? {
        val retiredV8 = c.experimental["retiredV8"] as? kotlinx.serialization.json.JsonObject ?: return null
        return (retiredV8[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    /**
     * R4（P3 语义重述）：Map 中**不含** `agent_prefix` 键时，retiredV8 不落任何 agent 旧词键
     * ——词表回落 EngineReservedWords 常量默认（`["ai","/ai"]`，由 EngineReservedWordsTest 钉死）。
     *
     * 🔴 机制：局部函数 `str(key)` 首行 `legacyData[key] ?: return` ⇒ key 不存在则该域**完全不动**。
     * 🔴 宿主侧「只把 `hasKey==true` 的 key 放进 Map」的编排规则属 M7-T15，本条只守 lib 侧纯函数契约。
     */
    @Test
    fun `R4 Map 不含 agent_prefix 键时 retiredV8 不落 agent 旧词键（词表回落常量默认不降级）`() {
        // ① 空输入：无任何 retiredV8 段
        assertTrue(
            retiredV8Word(LegacyDocumentMapper.map(emptyMap()), "routes.agent.trigger") == null &&
                retiredV8Word(LegacyDocumentMapper.map(emptyMap()), "routes.agent.resetCommand") == null &&
                retiredV8Word(LegacyDocumentMapper.map(emptyMap()), "routes.agent.stopCommand") == null,
            "空输入 ⇒ 不落任何 agent 旧词键（EngineReservedWords 回落常量默认）",
        )
        // ② 迁了别的 key（含 agent 域的其它两个 route key）但独缺 agent_prefix：
        //    reset/stop 旧词照常折入 retiredV8（证明「不动 trigger」不是「整个 agent 域都没迁」）
        val legacyNoPrefix = mapOf(
            "lark_bridge_app_id" to "cli_app",
            "lark_bridge_agent_enabled" to "true",
            "lark_bridge_agent_backend" to "claude",
            "lark_bridge_agent_reset_command" to "重来",
            "lark_bridge_agent_stop_command" to "停下",
            "lark_bridge_skills" to """[{"id":"x","name":"X","aliases":["/x"],"promptTemplate":"/x {args}"}]""",
            "lark_bridge_static_replies" to """[{"trigger":"/t","reply":"r","enabled":true}]""",
        )
        val c = LegacyDocumentMapper.map(legacyNoPrefix)
        assertEquals(null, retiredV8Word(c, "routes.agent.trigger"), "独缺 agent_prefix ⇒ trigger 不落 retiredV8")
        assertEquals("重来", retiredV8Word(c, "routes.agent.resetCommand"))
        assertEquals("停下", retiredV8Word(c, "routes.agent.stopCommand"))
        // ③ 对照：**显式写入** agent_prefix="ai"（仅默认首项）⇒ 旧迁移 R2 语义是「triggers 被
        //    整体替换为 ["ai"]」（用户显式收窄意图——不要 /ai）⇒ 折入 "ai" 保底（非默认集合）
        assertEquals(
            "ai",
            retiredV8Word(LegacyDocumentMapper.map(mapOf("lark_bridge_agent_prefix" to "ai")), "routes.agent.trigger"),
            "显式写 'ai' 是收窄意图（旧 R2 整体替换语义），须保留",
        )
        // ④ 对照：显式写入空串 ⇒ splitList 过滤后为空 ⇒ 归一化后为空列表 ⇒ 等价「无旧词」，不落键
        assertTrue(
            retiredV8Word(LegacyDocumentMapper.map(mapOf("lark_bridge_agent_prefix" to "")), "routes.agent.trigger") == null,
            "空串 ⇒ 归一化后为空 ⇒ 不落键（词表回落常量默认）",
        )
    }
}
