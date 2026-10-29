/**
 * 限时存在（旧配置导入完成后删除本类）。
 *
 * 本文件是旧 DataStore 35 个 lark_bridge_* key → 单一 JSON 文档 `~/.easy_dev_toolbox/lark/bridge-config.json` 的
 * **一次性**迁移器。门控逻辑：
 * 1. `bridge-config.json` 已存在 ⇒ 跳过（[LegacyImportOutcome.ConfigExists]）——保护手工/新 UI 文档不被旧值污染；
 * 2. 探测旧 DataStore 是否真有哨兵 key（`lark_bridge_app_id`）：
 *    - **无旧数据** ⇒ 置标记（若尚未置位）后返回 [LegacyImportOutcome.NoLegacyData]；
 *    - **有旧数据** ⇒ **无论标记是否置位，都执行迁移**（此时 config 不存在，用户唯一会处于此状态的场景
 *      就是「首次升级」或「刻意删掉了配置文件想要重新导入」——两种情况下迁移都是用户想要的结果）。
 * 3. 迁移失败 ⇒ 不写文档、不置标记（下次自动重试）。
 *
 * 幂等标记 `lark_bridge_legacy_imported` 从此**只承担一个作用**：在「确认无旧数据可迁」之后避免每次启动
 * 重复读 DataStore 做全量探测。它**不再**是迁移的短路闸门。
 *
 * 迁移成功后 `bridge-config.json` 存在 ⇒ 下次启动被第 1 步拦住，**不会重复迁移**，所以
 * 「有旧数据就总是迁移」不会造成每次启动重写配置。
 */
package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.ConfigPaths
import com.jiaoay.lark.bridge.config.ConfigRepository
import com.jiaoay.lark.bridge.config.LegacyDocumentMapper
import com.jiaoay.lark.bridge.config.LegacyMigrationReport
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.coroutineScope

/**
 * [LegacyConfigImporter.importOnce] 的结果分流（供日志与 UI 一次性提示消费）。
 *
 * 🔴 只有 [Migrated] 会落盘 `bridge-config.json` 并置幂等标记；[Failed] **不写文档、不置标记**，
 * 以便下次启动自动重试（消解「失败创建空白文档」与「不置标记下次重试」的自相矛盾——
 * 失败即落盘会把用户锁死在空白配置且永不重试）。
 */
sealed interface LegacyImportOutcome {
    /**
     * 历史遗留 outcome（**不再被 `importOnce` 返回**）。
     * 原语义：幂等标记已置位 ⇒ 无条件短路。
     * 现语义：标记只在「无旧数据」时避免重复探测，有旧数据时不再短路。
     * 保留此 data object 以免破坏 sealed interface 的既有消费者编译。
     */
    data object AlreadyImported : LegacyImportOutcome

    /** `bridge-config.json` 已存在 ⇒ 跳过（冲突策略 = 跳过迁移，保护手工/新 UI 文档不被旧值污染）。 */
    data object ConfigExists : LegacyImportOutcome

    /** 旧 DataStore 从未写过哨兵 key（`lark_bridge_app_id`）⇒ 无旧配置可迁，跳过（不落盘）。 */
    data object NoLegacyData : LegacyImportOutcome

    /** 迁移成功：已落盘 + 已置标记。[report] 供 UI 显式告知废弃项/失败项（CDS §11.4 裁决 9）。 */
    data class Migrated(val report: LegacyMigrationReport, val migratedKeyCount: Int) : LegacyImportOutcome

    /** 迁移失败（写盘异常等）：不写任何文档、不置标记，下次启动自动重试。 */
    data class Failed(val reason: String) : LegacyImportOutcome
}

/**
 * 旧 DataStore（35 key）→ 新单一 JSON 文档 v1 的**宿主侧一次性导入编排**。
 *
 * **映射逻辑不在本类**：35 key 的字段级映射、routes 合成、单位/枚举变换全部是 lib 侧纯函数
 * [LegacyDocumentMapper]（jvmTarget 11，已单测），本类只负责宿主侧编排（jvmTarget 25，可读 `:core` 的
 * [LocalDataManager]）：读旧 key → 组 `Map<String,String>` → 调 mapper → 原子写文档 → 置幂等标记。
 *
 * ## 宿主侧规则（逐条落地位置见各步注释）
 * 1. **只把 `hasKey == true` 的旧 key 放进 Map**（见 [snapshotLegacyKeys]）：缺失 key 绝不以空串/默认值填入，
 *    否则 `agent_prefix` 会把 `AgentRoute.triggers` 从默认 `["ai","/ai"]` 降级为 `["ai"]`（lib 侧有测试钉死）。
 * 2. **触发时机**：飞书 feature 入口（[com.jiaoay.easy_dev_toolbox.page.larkbridge.LarkBridgeViewModel] 首次 init）
 *    异步执行，在 `BridgeAssembler.assemble()` 与首次 `save()` 之前；不放全局 `JInitializerManager`、不阻塞启动。
 * 3. **冲突策略 = 跳过迁移**：`bridge-config.json` 已存在即不触发（[ConfigExists]）。不新增「重新导入」按钮；
 *    重新导入的手工路径 = 删掉 `bridge-config.json` 后重启 App（写进文档）。
 * 4. **失败行为**：迁移失败时不写任何文档、不置标记（[Failed]），下次自动重试；只有成功才落盘 + 置标记。
 * 5. **旧数据不清除**：迁移成功后不删旧 DataStore 的 35 key（保留回退能力；两份真源并存的风险由标记 +
 *    「config 已存在即跳过」共同消解）。
 * 6. **幂等标记** `lark_bridge_legacy_imported`（Boolean）：标记只在「确认无旧数据可迁」时
 *    置位，作用是避免后续启动重复探测 DataStore。有旧数据时**无论标记是否置位都执行迁移**。
 * 7. **可测性**：测试一律用合成样本（临时目录 + 重定向 `user.home` 的隔离 [LocalDataManager]），
 *    🔴 不得依赖真实 `~/.easy_dev_toolbox`（依赖真实目录会写坏用户真实数据；见 `LegacyConfigImporterTest`）。
 * 8. **CLI 侧不跑迁移**（jvmTarget 11 访问不到 `:core` DataStore）；纯 CLI 用户无旧数据可迁，不构成能力丢失。
 */
object LegacyConfigImporter {

    /** 幂等标记 key（Boolean；旧 DataStore 树不存在，纯新增）。只用于「无旧数据时避免重复探测」。 */
    const val MARKER_KEY = "lark_bridge_legacy_imported"

    /** 触发哨兵：旧 DataStore 从未显式写过 app_id ⇒ 判定「无旧桥接配置」，不迁移（config-domain-spec.md §11.3 触发三元组）。 */
    private const val SENTINEL_KEY = "lark_bridge_app_id"

    // —— 35 key 按 DataStore 强类型分组（config-domain-spec.md §11.2 权威表；类型错读会拿到默认值，故必须精确分组）——

    /** String 型旧 key（23 个 = 21 业务 + 2 废弃 transport/token_dir）。 */
    private val STRING_KEYS = listOf(
        "lark_bridge_app_id",
        "lark_bridge_app_secret",
        "lark_bridge_chat_id",
        "lark_bridge_access_mode",
        "lark_bridge_allowed_senders",
        "lark_bridge_blocked_senders",
        "lark_bridge_rejection_reply",
        "lark_bridge_workspace_dir",
        "lark_bridge_workspace_whitelist",
        "lark_bridge_agent_work_dir",
        "lark_bridge_agent_backend",
        "lark_bridge_agent_custom_template",
        "lark_bridge_agent_system_prompt",
        "lark_bridge_agent_permission_preset",
        "lark_bridge_agent_allowed_tools",
        "lark_bridge_unhandled_reply",
        "lark_bridge_agent_prefix",
        "lark_bridge_agent_reset_command",
        "lark_bridge_agent_stop_command",
        "lark_bridge_static_replies",
        "lark_bridge_skills",
        // 废弃不迁移，但需读入 Map 以便 mapper 在 report.discardedKeys 中显式列出（CDS §11.4 裁决 9）
        "lark_bridge_transport",
        "lark_bridge_token_dir",
    )

    /** Boolean 型旧 key（9 个 = 8 业务 + 1 废弃 preset_migration_notice）。宿主先 `toString()` 再入 Map。 */
    private val BOOLEAN_KEYS = listOf(
        "lark_bridge_dry_run",
        "lark_bridge_group_require_mention",
        "lark_bridge_agent_enabled",
        "lark_bridge_agent_prohibit_delete",
        "lark_bridge_agent_safety_prompt_enabled",
        "lark_bridge_agent_confirm_enabled",
        "lark_bridge_file_message_enabled",
        "lark_bridge_file_auto_extract",
        // 废弃（旧 preset 迁移横幅的瞬态 UI 标记），读入仅供 report 显式告知
        "lark_bridge_preset_migration_notice",
    )

    /** Long 型旧 key（3 个）。宿主先 `toString()` 再入 Map，mapper 内 `toLongOrNull()` 还原。 */
    private val LONG_KEYS = listOf(
        "lark_bridge_agent_timeout_minutes",
        "lark_bridge_agent_confirm_timeout_seconds",
        "lark_bridge_file_max_size_mb",
    )

    /** 残留 key（代码中无常量，仅可能存在于老用户 DataStore）：一律忽略，但读入以便 report 显式列出。 */
    private val RESIDUAL_KEYS = listOf(
        "lark_bridge_event_key",
        "lark_bridge_lark_cli_path",
        "lark_bridge_region",
    )

    /**
     * 执行一次性导入（幂等、异步、失败可重试）。
     *
     * 门控顺序：
     * 1. `bridge-config.json` 已存在 ⇒ [LegacyImportOutcome.ConfigExists]
     * 2. 探测哨兵 key（`lark_bridge_app_id`）：
     *    - 无旧数据 ⇒ 置标记（若尚未置位）后返回 [LegacyImportOutcome.NoLegacyData]
     *    - 有旧数据 ⇒ **无论标记是否置位**，执行迁移
     * 3. 迁移失败 ⇒ 不写文档、不置标记，下次自动重试
     *
     * @param store 进程唯一 [LocalDataManager]（生产传 `AppDataStore.store`；测试传临时目录隔离实例）。
     * @param dataRoot 桥接数据根（生产传 `FileUtils.getLarkDir().toPath()`；测试传临时目录）。
     * @param log 宿主日志出口（🔴 只记 key **名**与统计，绝不记 appSecret 等 secret 值——记进日志即凭据不可逆泄漏）。
     */
    suspend fun importOnce(
        store: LocalDataManager,
        dataRoot: Path,
        log: (String) -> Unit = {},
    ): LegacyImportOutcome {
        val paths = ConfigPaths(dataRoot)

        // 门控步骤 1（规则 3）：bridge-config.json 已存在 ⇒ 跳过（保护手工/新 UI 文档不被旧值污染）
        if (Files.exists(paths.configFile)) {
            return LegacyImportOutcome.ConfigExists
        }

        // 门控步骤 2：探测旧 DataStore 是否真有哨兵 key（轻量单次读取，不做全量快照）
        val hasSentinel = store.hasKey(SENTINEL_KEY)

        if (!hasSentinel) {
            // 无旧数据可迁：置标记（若尚未置位）以避免后续启动重复探测，然后返回
            if (!store.getValue(MARKER_KEY, false)) {
                store.setValue(MARKER_KEY, true)
            }
            return LegacyImportOutcome.NoLegacyData
        }

        // 门控步骤 2b：有旧数据 ⇒ 无论标记是否置位，都执行迁移。
        // 理由：此时 bridge-config.json 不存在（步骤 1 已通过），用户唯一会处于此状态的场景是
        // 「首次升级」或「刻意删掉了配置文件想要重新导入」——两种情况下迁移都是用户想要的结果。

        // 规则 1：只把 hasKey == true 的旧 key 放进 Map（缺失 key 绝不填默认值）
        val snapshot = snapshotLegacyKeys(store)

        // 映射（lib 侧纯函数，逐 key runCatching，单 key 失败不阻断其余）
        val result = LegacyDocumentMapper.mapWithReport(snapshot)

        // 规则 4：只有写盘成功才落文档 + 置标记；失败不写、不置标记，下次重试
        val written = writeConfigAtomically(paths, result.config, log)
        if (!written) {
            return LegacyImportOutcome.Failed("写入 ${paths.configFile} 失败")
        }

        // 规则 5：不删除旧 DataStore 的 35 key（保留回退能力）；规则 6：成功才置幂等标记
        store.setValue(MARKER_KEY, true)
        log(
            "旧配置迁移完成：搬运 ${snapshot.size} key / 废弃 ${result.report.discardedKeys.size} / " +
                "解析失败 ${result.report.failedKeys.size} / 忽略残留 ${result.report.ignoredResidualKeys.size}",
        )
        return LegacyImportOutcome.Migrated(result.report, snapshot.size)
    }

    /**
     * 🔴 规则 1：遍历全部旧 key，**只把 `store.hasKey(k) == true` 的 key 放进结果 Map**。
     * Boolean/Long 值先 `toString()`（mapper 输入是 `Map<String,String>`，内部再 `toBoolean()/toLongOrNull()` 还原）。
     * 缺失的 key **绝不出现在结果里**——否则 mapper 会用旧默认覆盖新默认（如 triggers 降级）。
     */
    private suspend fun snapshotLegacyKeys(store: LocalDataManager): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in STRING_KEYS) {
            if (store.hasKey(k)) out[k] = store.getValue(k, "")
        }
        for (k in BOOLEAN_KEYS) {
            if (store.hasKey(k)) out[k] = store.getValue(k, false).toString()
        }
        for (k in LONG_KEYS) {
            if (store.hasKey(k)) out[k] = store.getValue(k, 0L).toString()
        }
        for (k in RESIDUAL_KEYS) {
            if (store.hasKey(k)) out[k] = store.getValue(k, "")
        }
        return out
    }

    /**
     * 复用 lib 侧 [ConfigRepository] 的 config-domain-spec.md §9.1 原子写序列（tmp → fsync → POSIX600 → ATOMIC_MOVE，覆写前备份 .bak），
     * 保证迁移落盘与 UI 编辑落盘走**同一条**写入路径（单一真源，无第二套写盘实现）。
     *
     * 🔴 [ConfigRepository.flushPending] 对写失败是「记日志 + 把 draft 放回 pending」而**不抛**，故本函数以
     * 「flushNow 后文件是否真实存在」作为成功判据（写失败则文件缺失 ⇒ 返回 false ⇒ 上层不置标记——
     * 把「不抛」误当成功会置标记、迁移永不重试）。
     */
    private suspend fun writeConfigAtomically(
        paths: ConfigPaths,
        config: BridgeConfig,
        log: (String) -> Unit,
    ): Boolean = try {
        coroutineScope {
            val repo = ConfigRepository(paths, this)
            repo.save(config)
            repo.flushNow()
        }
        Files.exists(paths.configFile)
    } catch (e: Throwable) {
        log("旧配置迁移写盘异常：${e.message}")
        false
    }
}
