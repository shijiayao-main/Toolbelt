package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import com.jiaoay.lark.bridge.config.BridgeJson
import com.jiaoay.lark.bridge.config.CommandRoute
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T15 宿主侧一次性导入编排测试（CDS §13.9 / preflight §5.7 / F-8 收窄门控）。
 *
 * 🔴 **规则 7：全部用合成样本**——`user.home` 重定向到临时目录（使隔离 [LocalDataManager] 的 DataStore
 * 落临时位置），`dataRoot` 是另一个独立临时目录（`bridge-config.json` 落此处）。**绝不触碰真实
 * `~/.easy_dev_toolbox`**（真实 prefs_pb 含 appSecret 明文，禁读禁写）。
 *
 * 🔒 钉死的宿主侧规则：
 * - ① `onlyMigratesWrittenKeys`：只迁 `hasKey==true` 的 key（守护 agent_prefix 触发词降级）；
 * - ② `skipsWhenConfigFileExists`：config 已存在即跳过（冲突策略）；
 * - ③ `failureDoesNotSetMarker`：失败不写文档、不置标记、可重试；
 * - ④ `setsMarkerOnSuccess` + `secondCallSkipsDueToConfigExists`：成功置标记、二次调用因 config 存在跳过；
 * - ⑤ `markerSetWithLegacyDataStillMigrates`：🔴 F-8 核心——标记已置位 + 有旧数据 ⇒ 仍迁移；
 * - ⑥ `markerSetWithoutLegacyDataSkips`：标记已置位 + 无旧数据 ⇒ NoLegacyData；
 * - 全 35 key 端到端 + 三个变换点 + 废弃项报告。
 */
class LegacyConfigImporterTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-legacy-test")

    private lateinit var dataRootDir: Path
    private lateinit var store: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
                dataRootDir = Files.createTempDirectory("edt-legacy-data-${System.nanoTime()}")
        // 隔离 store（随机文件名 + 临时 user.home）：与真实 easy_dev_toolbox.preferences_pb 完全隔离
        store = LocalDataManager(dataStoreName = "test_legacy_${UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
        runCatching { dataRootDir.toFile().deleteRecursively() }
    }

    private val configFile: Path get() = dataRootDir.resolve("bridge-config.json")

    private fun readConfig(): BridgeConfig =
        BridgeJson.decodeFromString(BridgeConfig.serializer(), Files.readString(configFile))

    // ============================================================================
    // ① 只迁 hasKey==true（守护 agent_prefix 触发词降级 —— preflight §3.2 / R4）
    // ============================================================================

    /** 🔒 只写部分 key（**不含** agent_prefix）⇒ AgentRoute.triggers 保留新默认 ["ai","/ai"]，不降级为 ["ai"]。 */
    @Test
    fun onlyMigratesWrittenKeys() = runBlocking {
        store.setValue("lark_bridge_app_id", "cli_partial")
        store.setValue("lark_bridge_chat_id", "oc_partial")
        // 🔴 刻意不写 agent_prefix —— 若宿主把缺失 key 以默认塞入 Map，mapper 会把 triggers 降级

        val outcome = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.Migrated>(outcome)

        // P3（2026-09-26）：未写 agent_prefix ⇒ 无 retiredV8.trigger 键（常量默认在
        // EngineReservedWords，不进配置文档）
        val retired = (readConfig().experimental as? kotlinx.serialization.json.JsonObject)
            ?.get("retiredV8") as? kotlinx.serialization.json.JsonObject
        assertNull(retired?.get("routes.agent.trigger"), "未写 agent_prefix ⇒ 不产生旧前缀保底键")
        assertEquals("cli_partial", readConfig().connection.appId)
        assertEquals("oc_partial", readConfig().connection.testChatId)
    }

    /** 写了 agent_prefix（哪怕就是默认 "ai"）⇒ triggers 被整体替换为逗号切分结果（覆盖新默认，符合迁移语义）。 */
    @Test
    fun agentPrefixReplacesTriggersWhenWritten() = runBlocking {
        store.setValue("lark_bridge_app_id", "cli_p")
        store.setValue("lark_bridge_agent_prefix", "ai,ask,hey")

        LegacyConfigImporter.importOnce(store, dataRootDir)
        // P3：写了非默认 agent_prefix ⇒ 折入 retiredV8.trigger（逗号串）
        val retired = (readConfig().experimental as? kotlinx.serialization.json.JsonObject)
            ?.get("retiredV8") as? kotlinx.serialization.json.JsonObject
        assertEquals("ai,ask,hey", (retired?.get("routes.agent.trigger") as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    // ============================================================================
    // ② config 已存在即跳过（冲突策略 a）—— F-8 测试规格 #4
    // ============================================================================

    /** 🔒 预置 bridge-config.json ⇒ 跳过迁移，原文档一字不改（保护手工/新 UI 文档不被旧值污染）。即使标记未置位且有旧数据。 */
    @Test
    fun skipsWhenConfigFileExists() = runBlocking {
        val existing = """{"schemaVersion":1,"connection":{"appId":"MANUAL"}}"""
        Files.writeString(configFile, existing)
        store.setValue("lark_bridge_app_id", "cli_should_not_win")

        val outcome = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.ConfigExists>(outcome)
        assertEquals(existing, Files.readString(configFile), "🔒 已存在的文档不得被迁移覆写")
        assertFalse(store.getValue(LegacyConfigImporter.MARKER_KEY, false), "跳过时不得置标记")
    }

    // ============================================================================
    // ③ 失败不写文档、不置标记、可重试（设计裁定）—— F-8 测试规格 #5
    // ============================================================================

    /** 🔒 写盘失败（dataRoot 父级是普通文件 ⇒ mkdirs 失败）⇒ Failed、不写文档、不置标记；再次调用仍重试。 */
    @Test
    fun failureDoesNotSetMarker() = runBlocking {
        val blocker = File(homeIsolation.home, "blocker-${System.nanoTime()}")
        blocker.writeText("i am a file, not a dir")
        val badDataRoot = blocker.toPath().resolve("lark") // 父级是文件 ⇒ 无法创建目录 ⇒ 原子写失败

        store.setValue("lark_bridge_app_id", "cli_fail")

        val first = LegacyConfigImporter.importOnce(store, badDataRoot)
        assertIs<LegacyImportOutcome.Failed>(first)
        assertFalse(store.getValue(LegacyConfigImporter.MARKER_KEY, false), "🔒 失败不得置标记（否则重试永不触发）")
        assertFalse(Files.exists(badDataRoot.resolve("bridge-config.json")), "🔒 失败不得创建任何文档（含空白文档）")

        // 未置标记 ⇒ 下次仍会尝试（此处仍失败，证明「重试被触发」而非被标记短路）
        val second = LegacyConfigImporter.importOnce(store, badDataRoot)
        assertIs<LegacyImportOutcome.Failed>(second, "未置标记 ⇒ 下次调用仍进入迁移路径（可重试）")
        assertFalse(store.getValue(LegacyConfigImporter.MARKER_KEY, false), "重试后仍未置标记（始终可再重试）")
    }

    // ============================================================================
    // ④ 成功置标记 + 二次调用因 config 存在而跳过 —— F-8 测试规格 #3 & #6
    // ============================================================================

    /** 🔒 迁移成功后 lark_bridge_legacy_imported == true（标记未置位 + 有旧数据 ⇒ 迁移并置标记）。 */
    @Test
    fun setsMarkerOnSuccess() = runBlocking {
        store.setValue("lark_bridge_app_id", "cli_marker")
        assertFalse(store.getValue(LegacyConfigImporter.MARKER_KEY, false), "前置条件：标记未置位")

        val outcome = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.Migrated>(outcome)
        assertTrue(store.getValue(LegacyConfigImporter.MARKER_KEY, false), "🔒 成功须置幂等标记")
        assertTrue(Files.exists(configFile), "成功须落盘 bridge-config.json")
    }

    /** 🔒 迁移成功后再次调用 ⇒ 因 config 已存在而跳过（证明「有旧数据就总是迁移」不会导致每次启动重写）。 */
    @Test
    fun secondCallSkipsDueToConfigExists() = runBlocking {
        store.setValue("lark_bridge_app_id", "cli_once")
        LegacyConfigImporter.importOnce(store, dataRootDir)
        val afterFirst = Files.readString(configFile)
        assertTrue(Files.exists(configFile), "前置条件：首次迁移已落盘")

        val second = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.ConfigExists>(second, "🔒 config 已存在 ⇒ 第二次调用被步骤 1 拦住")
        assertEquals(afterFirst, Files.readString(configFile), "跳过不得改写文档")
    }

    // ============================================================================
    // ⑤🔴 F-8 核心：标记已置位 + config 不存在 + 有旧数据 ⇒ 仍然迁移
    // ============================================================================

    /**
     * 🔒 F-8 核心测试：幂等标记已置位（`lark_bridge_legacy_imported == true`）但 `bridge-config.json`
     * 不存在（用户手工删除了配置文件），且旧 DataStore 里有哨兵 key ⇒ **仍然执行迁移**。
     *
     * 这证明标记不再永久短路迁移，「删掉配置文件后重启 App = 重新导入」这条文档承诺被兑现。
     */
    @Test
    fun markerSetWithLegacyDataStillMigrates() = runBlocking {
        // 预设：标记已置位（模拟此前已确认过无旧数据或已迁移过后用户删了 config）
        store.setValue(LegacyConfigImporter.MARKER_KEY, true)
        // 预设：旧 DataStore 有哨兵 key（有旧数据可迁）
        store.setValue("lark_bridge_app_id", "cli_reimport")
        store.setValue("lark_bridge_chat_id", "oc_reimport")
        // 预设：config 文件不存在（用户已手工删除）
        assertFalse(Files.exists(configFile), "前置条件：config 不存在")

        val outcome = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.Migrated>(outcome, "🔒 F-8：标记已置位 + 有旧数据 + config 不存在 ⇒ 仍然迁移")
        assertTrue(Files.exists(configFile), "迁移应落盘 bridge-config.json")
        assertEquals("cli_reimport", readConfig().connection.appId)
        assertEquals("oc_reimport", readConfig().connection.testChatId)
    }

    // ============================================================================
    // ⑥ 标记已置位 + config 不存在 + 无旧数据 ⇒ 跳过（NoLegacyData）
    // ============================================================================

    /** 🔒 标记已置位 + 无旧数据（哨兵 key 不存在）⇒ 直接返回 NoLegacyData，不重复探测全量 key。 */
    @Test
    fun markerSetWithoutLegacyDataSkips() = runBlocking {
        store.setValue(LegacyConfigImporter.MARKER_KEY, true)
        // 不写任何 lark_bridge_* 旧 key ⇒ 哨兵不存在
        assertFalse(Files.exists(configFile), "前置条件：config 不存在")

        val outcome = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.NoLegacyData>(outcome, "🔒 标记已置 + 无旧数据 ⇒ NoLegacyData")
        assertFalse(Files.exists(configFile), "不应创建 config")
    }

    /** 无旧数据（从未写过哨兵 app_id）且标记未置位 ⇒ NoLegacyData + 置标记（避免后续重复探测）。 */
    @Test
    fun noLegacyDataIsSkipped() = runBlocking {
        val outcome = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.NoLegacyData>(outcome)
        assertFalse(Files.exists(configFile))
        assertTrue(store.getValue(LegacyConfigImporter.MARKER_KEY, false), "🔒 F-8：无旧数据时应置标记（避免后续重复探测）")
    }

    // ============================================================================
    // 全 35 key 端到端 + 三个变换点 + 废弃项报告
    // ============================================================================

    /** 🔒 写全 35 key（含 3 废弃）⇒ 各域字段正确映射；access_mode 改名、file_max_size 换算、whitelist JSON 解析。 */
    @Test
    fun importsAll35KeysIntoNewDocument() = runBlocking {
        writeFullLegacySample()

        val outcome = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.Migrated>(outcome)
        val cfg = readConfig()

        // connection (#1-4)
        assertEquals("cli_test_app", cfg.connection.appId)
        assertEquals("secret_abcd1234wxyz", cfg.connection.appSecret)
        assertEquals("oc_test", cfg.connection.testChatId)
        assertTrue(cfg.connection.dryRun)
        // access (#5-9)：旧白名单平移为信任用户；mode/黑名单无对应载体
        assertEquals(listOf("ou_a", "ou_b"), cfg.access.trustedUsers)
        // P3（2026-09-26）：rejectionReply/groupRequireMention 已退役——DataStore 旧键不迁移
        //（拒绝恒静默=修正 6；群门控真源=agent.groupMode）
        // workspace (#10-12)：whitelist JSON 数组解析
        //（agent_work_dir 已随 CLI 委托链退役：旧键读入但不迁移，不进 typed 域）
        assertEquals("/ws/root", cfg.workspace.root)
        assertEquals(listOf("/extra1", "/extra2"), cfg.workspace.extraRoots, "🔒 workspace_whitelist JSON 须解析为 List")
        // agent (#13-20, #22-23)：agent_enabled 已退役不迁移（agent 恒为硬依赖）——
        // DataStore 旧键丢弃不进 typed 域、不炸导入（照 rejection_reply 先例）
        // CLI 委托后端字段（backend/customTemplate/permissionPreset/allowedTools/prohibitDelete/
        // safetyPromptEnabled）与 confirm.enabled 同批退役——旧键只读入不进 typed 域。
        assertEquals("sys", cfg.agent.systemPrompt)
        assertEquals(90L, cfg.agent.confirm.timeoutSeconds)
        // runtime (#21, #29)
        assertEquals(25L, cfg.runtime.agentTimeoutMinutes)
        // P3（2026-09-26）：unhandledReply 已退役不迁移（TEXT 兜底归调度 agent，修正 7）
        // routes[agent] (#24-26)：匹配键退役——非默认旧词折入 experimental.retiredV8（修正 5 通道）
        val retiredV8 = (cfg.experimental as? kotlinx.serialization.json.JsonObject)
            ?.get("retiredV8") as? kotlinx.serialization.json.JsonObject
        assertNotNull(retiredV8, "旧 agent 词须折入 experimental.retiredV8")
        assertEquals("ai,/ai,ask", (retiredV8?.get("routes.agent.trigger") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("reset", (retiredV8?.get("routes.agent.resetCommand") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("halt", (retiredV8?.get("routes.agent.stopCommand") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        // routes[reply] (#27)：static_replies 已退役不迁移（REPLY 槽整槽退役）——不合成任何 reply 条目
        assertTrue(cfg.routes.none { it.id.startsWith("reply-") }, "static_replies 退役 ⇒ 不合成 reply 条目")
        assertEquals(DefaultBridgeConfig.DEFAULT_ROUTES.size + 1, cfg.routes.size, "仅 skills 合成追加一条")
        // routes[command] (#28)：用户 skill 合成为 CommandRoute 追加（默认 routes 已无内置 CommandRoute，
        // 产品裁定去业务化 ⇒ 无「就地覆盖内置」场景；同 id 覆盖语义由 LegacyDocumentMapperTest R1 守护）
        val myCmd = cfg.routes.filter { it.id == "my-analysis" }
        assertEquals(1, myCmd.size, "🔒 用户 skill 须恰好合成一条 CommandRoute")
        assertEquals("LA", (myCmd.single() as CommandRoute).name, "合成条目须为用户版")
        // fileMessage (#30-32)：MB→字节换算
        assertFalse(cfg.fileMessage.enabled)
        assertEquals(50L * 1048576L, cfg.fileMessage.maxFileSizeBytes, "🔒 file_max_size_mb 须 ×1048576")
        assertFalse(cfg.fileMessage.autoExtract)
        // 废弃项 (#33-35) 显式记入报告，且不进文档
        val report = outcome.report
        assertTrue(report.discardedKeys.containsAll(listOf("lark_bridge_transport", "lark_bridge_token_dir", "lark_bridge_preset_migration_notice")),
            "🔒 3 个废弃 key 须在报告中显式列出（CDS §11.4 裁决 9），实得 ${report.discardedKeys}")
        assertEquals(3, report.discardedKeys.size)
    }

    /** 单 key 值非法不阻断其余（mapper 逐 key 防御性解析）：非法 whitelist JSON 降级为空，其余字段正常映射。 */
    @Test
    fun singleKeyFailureDoesNotBlock() = runBlocking {
        store.setValue("lark_bridge_app_id", "cli_ok")
        store.setValue("lark_bridge_chat_id", "oc_ok")
        store.setValue("lark_bridge_workspace_whitelist", "not-a-json-array") // 非法 JSON 数组

        val outcome = LegacyConfigImporter.importOnce(store, dataRootDir)
        assertIs<LegacyImportOutcome.Migrated>(outcome, "单 key 值非法不得阻断整体迁移")
        val cfg = readConfig()
        assertEquals("oc_ok", cfg.connection.testChatId, "其余 key 须正常映射")
        assertEquals(emptyList(), cfg.workspace.extraRoots, "非法 JSON 须降级为空列表（parseStringArray 兜底），不抛异常")
    }

    private suspend fun writeFullLegacySample() {
        // String（21 业务 + 2 废弃）
        store.setValue("lark_bridge_app_id", "cli_test_app")
        store.setValue("lark_bridge_app_secret", "secret_abcd1234wxyz")
        store.setValue("lark_bridge_chat_id", "oc_test")
        store.setValue("lark_bridge_access_mode", "whitelist")
        store.setValue("lark_bridge_allowed_senders", "ou_a,ou_b")
        store.setValue("lark_bridge_blocked_senders", "ou_c")
        store.setValue("lark_bridge_rejection_reply", "no")
        store.setValue("lark_bridge_workspace_dir", "/ws/root")
        store.setValue("lark_bridge_workspace_whitelist", """["/extra1","/extra2"]""")
        store.setValue("lark_bridge_agent_work_dir", "~/agentwork")
        store.setValue("lark_bridge_agent_backend", "qoder")
        store.setValue("lark_bridge_agent_custom_template", "")
        store.setValue("lark_bridge_agent_system_prompt", "sys")
        store.setValue("lark_bridge_agent_permission_preset", "readonly")
        store.setValue("lark_bridge_agent_allowed_tools", "tool1")
        store.setValue("lark_bridge_unhandled_reply", "dunno")
        store.setValue("lark_bridge_agent_prefix", "ai,/ai,ask")
        store.setValue("lark_bridge_agent_reset_command", "reset")
        store.setValue("lark_bridge_agent_stop_command", "halt")
        store.setValue("lark_bridge_static_replies", """[{"trigger":"hi","reply":"hello","enabled":true}]""") // 退役不迁移：REPLY 槽已退役，读入仅供 mapper 忽略
        store.setValue("lark_bridge_skills", """[{"id":"my-analysis","name":"LA","aliases":[],"description":"d","promptTemplate":"p","enabled":true,"requiresConfirm":false}]""")
        store.setValue("lark_bridge_transport", "cli") // 废弃
        store.setValue("lark_bridge_token_dir", "/tok") // 废弃
        // Boolean（8 业务 + 1 废弃）
        store.setValue("lark_bridge_dry_run", true)
        store.setValue("lark_bridge_group_require_mention", false)
        store.setValue("lark_bridge_agent_enabled", true) // 退役不迁移：读入仅供 mapper 忽略，不进 typed 域
        store.setValue("lark_bridge_agent_prohibit_delete", false)
        store.setValue("lark_bridge_agent_safety_prompt_enabled", false)
        store.setValue("lark_bridge_agent_confirm_enabled", true)
        store.setValue("lark_bridge_file_message_enabled", false)
        store.setValue("lark_bridge_file_auto_extract", false)
        store.setValue("lark_bridge_preset_migration_notice", true) // 废弃
        // Long（3）
        store.setValue("lark_bridge_agent_timeout_minutes", 25L)
        store.setValue("lark_bridge_agent_confirm_timeout_seconds", 90L)
        store.setValue("lark_bridge_file_max_size_mb", 50L)
    }
}
