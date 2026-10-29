package com.jiaoay.lark.bridge.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 迁移报告（[CDS §11.3 / config-domain-spec.md §11.4]）。
 * - [discardedKeys]：3 项**废弃不迁移**且旧 DataStore 中确实存在的 key（transport/token_dir/preset_migration_notice）——
 *   🔴 **必须显式列出**（裁决 9：明确告知用户其旧设置被有意丢弃），非静默吞掉。
 * - [failedKeys]：逐 key runCatching 解析失败的 key（单 key 失败不阻断其余 34 个）。
 * - [ignoredResidualKeys]：残留 key（event_key/lark_cli_path/region），一律忽略不迁移。
 */
data class LegacyMigrationReport(
    val discardedKeys: List<String>,
    val failedKeys: List<String>,
    val ignoredResidualKeys: List<String>,
) {
    val isEmpty: Boolean get() = discardedKeys.isEmpty() && failedKeys.isEmpty()
}

/** 迁移结果：构造好的 v1 [config] + [report]。 */
data class LegacyMigrationResult(
    val config: BridgeConfig,
    val report: LegacyMigrationReport,
)

/**
 * 旧 DataStore（35 key）→ 新单一 JSON 文档 v1 的**纯函数**映射（[CDS §11]）。
 * 🔴 输入 `Map<String,String>` 快照，**不依赖 DataStore、不依赖 :core**（jvmTarget 11，lib 侧可单测）。
 * 🔴 逐 key 独立 runCatching：单 key 失败不阻断其余（[CDS §11.3]），失败 key 记入 [LegacyMigrationReport.failedKeys]。
 *
 * 触发条件、失败不置标记可重试、旧 key 迁移后不删除、UI「重新导入」按钮等编排逻辑属宿主侧，非本纯函数职责。
 */
object LegacyDocumentMapper {

    // —— 旧 key 常量（config-domain-spec.md §11.2 权威表）——
    // 只声明 [mapWithReport] 有消费分支的 key；退役 key 无消费分支，不声明常量（旧值静默丢弃，见各段注释）。
    private const val K_APP_ID = "lark_bridge_app_id"
    private const val K_APP_SECRET = "lark_bridge_app_secret"
    private const val K_CHAT_ID = "lark_bridge_chat_id"
    private const val K_DRY_RUN = "lark_bridge_dry_run"
    private const val K_ALLOWED_SENDERS = "lark_bridge_allowed_senders"
    private const val K_WORKSPACE_DIR = "lark_bridge_workspace_dir"
    private const val K_WORKSPACE_WHITELIST = "lark_bridge_workspace_whitelist"
    private const val K_AGENT_SYSTEM_PROMPT = "lark_bridge_agent_system_prompt"
    private const val K_AGENT_TIMEOUT_MINUTES = "lark_bridge_agent_timeout_minutes"
    private const val K_CONFIRM_TIMEOUT_SECONDS = "lark_bridge_agent_confirm_timeout_seconds"
    private const val K_AGENT_PREFIX = "lark_bridge_agent_prefix"
    private const val K_AGENT_RESET_COMMAND = "lark_bridge_agent_reset_command"
    private const val K_AGENT_STOP_COMMAND = "lark_bridge_agent_stop_command"
    private const val K_SKILLS = "lark_bridge_skills"
    private const val K_FILE_MESSAGE_ENABLED = "lark_bridge_file_message_enabled"
    private const val K_FILE_MAX_SIZE_MB = "lark_bridge_file_max_size_mb"
    private const val K_FILE_AUTO_EXTRACT = "lark_bridge_file_auto_extract"

    // —— 3 项废弃不迁移（config-domain-spec.md §11.2 #33-35 / config-domain-spec.md §11.4 裁决 9）——
    private const val K_TRANSPORT = "lark_bridge_transport"
    private const val K_TOKEN_DIR = "lark_bridge_token_dir"
    private const val K_PRESET_MIGRATION_NOTICE = "lark_bridge_preset_migration_notice"

    /** 废弃 key 全集（存在于旧 DataStore 时须在报告中显式列出）。 */
    val DISCARDED_KEYS: List<String> = listOf(K_TRANSPORT, K_TOKEN_DIR, K_PRESET_MIGRATION_NOTICE)

    /** 残留 key（代码中无常量，仅可能存在于老用户 DataStore）——一律忽略（config-domain-spec.md §11.2 尾注）。 */
    val RESIDUAL_KEYS: List<String> = listOf("lark_bridge_event_key", "lark_bridge_lark_cli_path", "lark_bridge_region")

    private const val BYTES_PER_MB = 1048576L

    /** 旧 JSON 串解析专用（宽容，不复用 BridgeJson/AuditJson——那两个分别绑定配置文档与审计）。 */
    private val LegacyJson = Json { ignoreUnknownKeys = true }

    /** 纯函数入口（config-domain-spec.md §11.1 签名）：返回构造好的 v1 [BridgeConfig]。 */
    fun map(legacyData: Map<String, String>): BridgeConfig = mapWithReport(legacyData).config

    /** 带迁移报告的入口：宿主/UI 用报告向用户展示废弃项与失败项。 */
    fun mapWithReport(legacyData: Map<String, String>): LegacyMigrationResult {
        val failed = mutableListOf<String>()
        var c = DefaultBridgeConfig.default()

        fun str(key: String, apply: (String) -> BridgeConfig) {
            val v = legacyData[key] ?: return
            runCatching { c = apply(v) }.onFailure { failed += key }
        }

        fun bool(key: String, apply: (Boolean) -> BridgeConfig) {
            val v = legacyData[key] ?: return
            runCatching { c = apply(v.trim().toBoolean()) }.onFailure { failed += key }
        }

        // —— connection (#1-4) ——
        str(K_APP_ID) { c.copy(connection = c.connection.copy(appId = it)) }
        str(K_APP_SECRET) { c.copy(connection = c.connection.copy(appSecret = it)) }
        str(K_CHAT_ID) { c.copy(connection = c.connection.copy(testChatId = it)) }
        bool(K_DRY_RUN) { c.copy(connection = c.connection.copy(dryRun = it)) }

        // —— access (#5-9) ——
        // 准入白名单/黑名单已退役：旧白名单成员平移为「信任用户」（语义从「谁能用机器人」
        // 变为「谁能免确认」）。access_mode / blocked_senders / rejection_reply /
        // group_require_mention 无对应载体（拒绝恒静默；群门控真源 = agent.groupMode），
        // 一律静默丢弃——不进 ignoredResidualKeys（该报告只列 RESIDUAL_KEYS）。
        str(K_ALLOWED_SENDERS) { c.copy(access = c.access.copy(trustedUsers = splitList(it))) }

        // —— workspace (#10-12) ——
        str(K_WORKSPACE_DIR) { c.copy(workspace = c.workspace.copy(root = it)) }
        str(K_WORKSPACE_WHITELIST) { c.copy(workspace = c.workspace.copy(extraRoots = parseStringArray(it))) }
        // lark_bridge_agent_work_dir（退役不迁移）：CLI 委托链退役后无 agent 工作目录配置面。

        // —— agent (#13-20) ——
        // lark_bridge_agent_enabled（退役不迁移）：agent 恒为硬依赖，「关闭 agent」不再是被支持的配置态。
        // CLI 委托后端字段（backend/customTemplate/permissionPreset/allowedTools/prohibitDelete/
        // safetyPromptEnabled）同属退役字段：agent 域只剩本地 AI（Koog）调度配置；旧值静默丢弃。
        str(K_AGENT_SYSTEM_PROMPT) { c.copy(agent = c.agent.copy(systemPrompt = it)) }

        // —— runtime (#21, #29) ——
        str(K_AGENT_TIMEOUT_MINUTES) { c.copy(runtime = c.runtime.copy(agentTimeoutMinutes = it.toLongOrNull() ?: 10L)) }
        // lark_bridge_unhandled_reply（退役不迁移）：TEXT 兜底由调度 agent 承担、
        // 非 TEXT 降级文案机制内置化；旧值静默丢弃。

        // —— agent.confirm (#22-23) ——
        // lark_bridge_agent_confirm_enabled（退役不迁移）：确认链恒启用，无单独开关。
        str(K_CONFIRM_TIMEOUT_SECONDS) {
            c.copy(agent = c.agent.copy(confirm = c.agent.confirm.copy(timeoutSeconds = it.toLongOrNull() ?: 60L)))
        }

        // —— routes[agent] (#24-26；改投 retiredV8 保底——旧词迁移通道) ——
        // AgentRoute 匹配键已退役：非默认值折入 experimental.retiredV8.routes.agent.*，
        // 由 EngineReservedWords 读取（用户改过的停止词如「停」全程存活，防「LLM 口头
        // 应答已停止但在途任务还在跑」的危险误导）；默认值静默丢弃。
        str(K_AGENT_PREFIX) { retireLegacyAgentWord(c, "routes.agent.trigger", splitList(it), listOf("ai", "/ai")) }
        str(K_AGENT_RESET_COMMAND) { retireLegacyAgentWord(c, "routes.agent.resetCommand", listOf(it), listOf("新对话")) }
        str(K_AGENT_STOP_COMMAND) { retireLegacyAgentWord(c, "routes.agent.stopCommand", listOf(it), listOf("停止")) }

        // lark_bridge_static_replies（退役不迁移）：固定回复槽已整槽退役，旧值静默丢弃。

        // —— routes[command] (#28) ——
        // 合成条目与既有 routes **同 id ⇒ 就地整条替换并保留被替换条目的原数组下标**
        //    （`routes` 数组顺序 == 路由优先级，保留下标即保留优先级）；不冲突才追加末尾。
        //    ⚠️ `PipelineSnapshot.resolve` 取数组首个命中 ⇒ 追加式合成会让用户条目被默认条目
        //    永久遮蔽（覆盖静默失效）。
        str(K_SKILLS) { c.copy(routes = mergeRoutes(c.routes, parseSkills(it))) }

        // —— fileMessage (#30-32) ——
        bool(K_FILE_MESSAGE_ENABLED) { c.copy(fileMessage = c.fileMessage.copy(enabled = it)) }
        str(K_FILE_MAX_SIZE_MB) {
            c.copy(fileMessage = c.fileMessage.copy(maxFileSizeBytes = (it.toLongOrNull() ?: 100L) * BYTES_PER_MB))
        }
        bool(K_FILE_AUTO_EXTRACT) { c.copy(fileMessage = c.fileMessage.copy(autoExtract = it)) }

        // —— #33-35 废弃不迁移：显式记入报告（存在的才列）——
        val discarded = DISCARDED_KEYS.filter { legacyData.containsKey(it) }
        val ignoredResidual = RESIDUAL_KEYS.filter { legacyData.containsKey(it) }

        return LegacyMigrationResult(c, LegacyMigrationReport(discarded, failed, ignoredResidual))
    }

    /** 逗号分隔 → List<String>（trim + 过滤空项；config-domain-spec.md §11.2 #6/#24）。 */
    private fun splitList(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** JSON 数组串 → List<String>；解析失败回退空列表（config-domain-spec.md §11.2 #11）。 */
    private fun parseStringArray(raw: String): List<String> =
        runCatching { LegacyJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    /**
     * 旧 agent 词键退役保底：非默认值折入 experimental.retiredV8.<dottedKey>。
     * 默认值静默丢弃（无信息量）；List 形态（triggers）以逗号连接保存、读取端 splitList 复原。
     */
    private fun retireLegacyAgentWord(
        config: BridgeConfig,
        dottedKey: String,
        values: List<String>,
        defaults: List<String>,
    ): BridgeConfig {
        val normalized = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty() || normalized == defaults) return config
        val experimental = (config.experimental as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
        val retiredV8 = (experimental["retiredV8"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
        retiredV8[dottedKey] = JsonPrimitive(normalized.joinToString(","))
        experimental["retiredV8"] = JsonObject(retiredV8)
        return config.copy(experimental = JsonObject(experimental))
    }

    /**
     * 把合成出的 [incoming] 并入 [existing]（skills → CommandRoute 合成专用）。
     *
     * ## 冲突判定（id **或** 匹配键）
     * 冲突 = 以下任一成立：
     * - **id 相同**；
     * - **匹配键重叠**（消解「同触发条件、不同 id ⇒ 用户条目被内置永久遮蔽」的实证缺陷）：
     *   - [CommandRoute] → `aliases` 集合有**任一元素重叠**；
     *   - [AgentRoute] → 同 kind 即冲突（P3 起单例语义——匹配键已退役）；
     *   - [SystemRoute] / [FileRoute] → 各自是单例，按 `kind`（即 `routeKind`）判定冲突。
     *
     * ## 冲突时的行为
     * **就地整条替换，并保留被替换条目的原数组下标**（保留下标即保留优先级——精确复现「用户配置覆盖内置」的旧语义）。
     * 替换后条目的 `id` 取**用户条目的 id**（整条替换语义，不合并字段）。
     *
     * ## 无冲突时
     * 仍**追加在末尾**（优先级最低）。这是既定且已文档化的行为：默认 3 条 `system→agent→file` 在前，
     * 迁移来的用户条目优先级最低。
     *
     * 🔴 三条不变量（不得违反）：
     * ① 不改 `PipelineSnapshot.resolve` 的「数组首个命中」（改 lastOrNull 会破坏全局「数组顺序 == 优先级」）；
     * ② 绝不把用户条目插到默认条目之前（除非是就地替换占了默认条目的下标）；
     * ③ 绝不改默认 routes 顺序（system → agent → file）。
     *
     * 声明为 `internal` 而非 `private`：测试需直接断言匹配键规则。
     */
    internal fun mergeRoutes(existing: List<RouteRule>, incoming: List<RouteRule>): List<RouteRule> {
        if (incoming.isEmpty()) return existing
        val routes = existing.toMutableList()
        for (rule in incoming) {
            val idx = findConflictIndex(routes, rule)
            if (idx >= 0) routes[idx] = rule else routes += rule
        }
        return routes
    }

    /**
     * 在 [routes] 中找出与 [incoming] 冲突的首条下标（-1 = 无冲突）。
     * 冲突 = id 相同 **或** 匹配键重叠（见 [mergeRoutes] KDoc）。
     */
    private fun findConflictIndex(routes: List<RouteRule>, incoming: RouteRule): Int {
        // 先按 id 查（O(n) 但 n ≤ 10，且保持既有行为的前向兼容）
        val byId = routes.indexOfFirst { it.id == incoming.id }
        if (byId >= 0) return byId
        // 再按匹配键查
        return routes.indexOfFirst { existing -> isMatchingKeyConflict(existing, incoming) }
    }

    /**
     * 判定两条 route 是否存在**匹配键冲突**（同 kind 才可能冲突；跨 kind 不冲突）。
     * 纯函数、零 IO、零锁。
     */
    private fun isMatchingKeyConflict(existing: RouteRule, incoming: RouteRule): Boolean {
        // 跨 kind 不冲突
        if (existing::class != incoming::class) return false
        return when (existing) {
            is CommandRoute -> {
                val inc = incoming as CommandRoute
                // aliases 任一重叠即冲突
                existing.aliases.any { it in inc.aliases }
            }
            // AgentRoute 匹配键已退役——按单例语义判定（同 kind 即冲突）
            is AgentRoute -> true
            is SystemRoute -> true  // 单例：同 kind 即冲突
            is FileRoute -> true    // 单例：同 kind 即冲突
        }
    }

    /** skills JSON（历史名 skills，实际存 QuickCommand）→ 多条 [CommandRoute]（config-domain-spec.md §11.2 #28）。 */
    private fun parseSkills(raw: String): List<CommandRoute> =
        runCatching { LegacyJson.decodeFromString<List<LegacyQuickCommand>>(raw) }
            .getOrDefault(emptyList())
            .mapIndexed { i, s ->
                CommandRoute(
                    id = s.id.ifBlank { "command-$i" },
                    enabled = s.enabled,
                    name = s.name,
                    aliases = s.aliases,
                    description = s.description,
                    promptTemplate = s.promptTemplate,
                    requiresConfirm = s.requiresConfirm,
                )
            }

    /** 旧 QuickCommandConfig 形态（model/BridgeConfig.kt:215）。 */
    @Serializable
    private data class LegacyQuickCommand(
        val id: String = "",
        val name: String = "",
        val aliases: List<String> = emptyList(),
        val description: String = "",
        val promptTemplate: String = "",
        val enabled: Boolean = true,
        val requiresConfirm: Boolean = false,
    )
}
