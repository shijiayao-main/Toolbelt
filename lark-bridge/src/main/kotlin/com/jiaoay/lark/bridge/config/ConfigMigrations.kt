package com.jiaoay.lark.bridge.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 单步迁移：(fromVersion, doc) -> migratedDoc（[CDS §10.1]）。
 * 🔴 在 **JsonObject 层**操作（不是 typed 层）——被删/改名字段不会让 decode 失败。
 */
typealias Migration = (Int, JsonObject) -> JsonObject

/**
 * 配置迁移框架（[CDS §10]）。当前 **14 步框架在位**：
 * v1→v2（fileMessage 退役键折入 experimental）、
 * v2→v3（agent.progress 旧默认显式值删键收敛到新默认，防刷屏）、
 * v3→v4（agent.progress.patchIntervalMillis 退役，滚动窗口精简）、
 * v4→v5（connection.selfBuiltWs 退役——入站恒走 :lark-ws，开关不再存在）、
 * v5→v6（fileMessage.maxFileSizeBytes 旧默认显式值删键收敛到新默认 1GB——
 *        分片突破平台 100MB 限后此值转为磁盘保护阈值，默认 100MB→1GB）、
 * v6→v7（agent.backend=="koog" 语义等价转换为 backend=claude + agent.dispatchEnabled=true——
 *        koog 从 backend 值域移除，调度层改由独立开关承载）、
 * v7→v8（agent-first 重构，多段合成一步）：
 *        三死配置退役（agent.session.capacity/keyByThread、fileMessage.allowedExtensions）；
 *        配置删除批（11 标量残键 + routes AgentRoute 匹配键退役——rejectionReply/
 *        groupRequireMention/unhandledReply/dispatchEnabled/ackTemplate/reuseFileKey/
 *        memoTtlHours/memoMaxEntries，非默认值折入 experimental.retiredV8.<点路径> 保底；
 *        CommandRoute.promptTemplate 非空者按块头格式附加进 agent.systemPrompt；
 *        AgentRoute 的非默认 stopCommand/resetCommand/triggers 折入 retiredV8 旧词通道）、
 * v8→v9（agent.nativeTools 段引入）：**纯版本锚点步**——该段全默认值，
 *        存量文档 decode 兼容，零数据变换（详见 [migrateV8ToV9] KDoc）。
 * v9→v10（agent 硬依赖化）：agent.enabled 总闸退役——agent 段残键一律移除，
 *        显式 false（用户曾主动关闸）折 experimental.retiredV10."agent.enabled" 保底、
 *        默认 true 静默丢；routes 数组中 kind=reply 条目整条剥离（固定回复槽退役——
 *        不剥离则旧文档 sealed 多态 decode 直接失败），trigger/reply 非空者折
 *        experimental.retiredV10.routes.reply.<id>.* 保底、空壳静默丢。
 * v10→v11（CLI 委托链退役）：11 个 CLI 专用键退役（agent.backend/customTemplate/
 *        permissionPreset/allowedTools/prohibitDelete/safetyPromptEnabled、
 *        agent.confirm.enabled/selfCheck、workspace.agentWorkDir/envAllowlist/outputCharset）
 *        ——非默认值折 experimental.retiredV11.<点路径> 保底、默认值静默丢；
 *        appServer 顶层段整段折 retiredV11.appServer（手编配置可见不吞）。
 * v11→v12（L2 授权面收敛）：agent.nativeTools 段（l2Enabled/l2Grants 双闸）整段退役——
 *        L1/L2 原生能力恒注册，授权闸收敛为飞书后台 scope 单一闸；
 *        非默认态（开关显式 true 或 grants 非空）折 experimental.retiredV12 保底、
 *        纯默认段静默丢。
 * v12→v13（准入白名单退役）：access.mode/allowedSenders/blockedSenders 三字段退役——
 *        入站准入恒放行，服务面收口改由飞书后台可用范围承担；allowedSenders 非空
 *        **平移**为 access.trustedUsers（原白名单成员即「自己人」），mode 与
 *        blockedSenders 非默认折 experimental.retiredV13 保底。
 * v13→v14（能力域注册闸退役）：agent.sysTools/devTools/selfStatus/startupProbe 四段
 *        整段退役 + agent.wsTools 四个开关退役（runTaskTimeoutMinutes 保留）——
 *        工具恒注册，注册面不再有配置闸；非默认取值折 experimental.retiredV14 保底。
 * v14→v15（xlog 解密恒启用）：fileMessage.xlogDecodeEnabled 退役——私钥为空时
 *        跳过并告警，不再是开关；显式 true 折 experimental.retiredV15 保底。
 * 后续版本按 fromVersion 递增顺序往 [MIGRATIONS] 追加。
 *
 * 退役 DataStore key（transport/token_dir/preset_migration_notice）与「文档内未知键保留」是两件事（config-domain-spec.md §10.2）：
 * 前者压根不进新文档（属 [LegacyDocumentMapper] 的废弃不迁移），后者是 bridge-config.json 的前向兼容（本类负责）。
 */
object ConfigMigrations {

    /** 当前 schema 版本。 */
    const val CURRENT_VERSION: Int = 15

    /**
     * 迁移链：v1→v2 把 fileMessage 域的 4 个退役键取值非默认时搬入 `experimental.retiredV1.<原键名>`
     * 保底（用户在配置文件里看得见什么被退役），随后从 fileMessage 移除；幂等、缺 fileMessage 段时 no-op。
     * v2→v3 把 agent.progress 域里等于旧默认（maxEntries=12 / cardMaxChars=5000）的显式键删除，
     * 让 typed 层 decode 落到新默认（6 / 2000）；用户改过的非默认值不动（尊重用户调参）。
     * v3→v4 把 agent.progress.patchIntervalMillis（死配置：编辑器有、管线零消费，真实节流是网关固定
     * 250ms）从文档移除——typed 域已无此字段，留着会在 encodeDefaults 写回时产生双份键；
     * 取值非默认（≠2000）的搬入 `experimental.retiredV3.patchIntervalMillis` 保底，默认值静默丢弃。
     * v4 之后的新版本按 fromVersion 递增顺序追加。
     */
    val MIGRATIONS: List<Migration> = listOf(
        ::migrateV1ToV2,
        ::migrateV2ToV3,
        ::migrateV3ToV4,
        ::migrateV4ToV5,
        ::migrateV5ToV6,
        ::migrateV6ToV7,
        ::migrateV7ToV8,
        ::migrateV8ToV9,
        ::migrateV9ToV10,
        ::migrateV10ToV11,
        ::migrateV11ToV12,
        ::migrateV12ToV13,
        ::migrateV13ToV14,
        ::migrateV14ToV15,
    )

    /** 已识别的顶层段（[CDS §3.1]）；其余顶层键视为未知，折入 experimental 保留。
     *  `appServer` 必须在册：漏登会被 foldUnknownIntoExperimental 折入 experimental，
     *  typed 域落默认——手工编辑的客户端配置被静默吞掉。 */
    val KNOWN_TOP_LEVEL_KEYS: Set<String> = setOf(
        "schemaVersion", "connection", "access", "workspace", "runtime",
        "agent", "fileMessage", "observability", "routes", "experimental",
    )

    /**
     * 应用迁移链并把 schemaVersion 标记为 [CURRENT_VERSION]。
     * 空链时对 v0/无版本文档仅做「版本戳」升迁（内容原样保留）。
     */
    fun migrate(doc: JsonObject): JsonObject {
        val fromVersion = doc["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 0
        var result = doc
        var version = fromVersion
        for (step in MIGRATIONS) {
            result = step(version, result)
            version++
        }
        val out = result.toMutableMap()
        out["schemaVersion"] = JsonPrimitive(CURRENT_VERSION)
        return JsonObject(out)
    }

    /**
     * 把 [KNOWN_TOP_LEVEL_KEYS] 之外的未识别顶层键折入 `experimental` 段（[CDS §10.2]），
     * 使 BridgeJson（ignoreUnknownKeys=true）decode 后未知键不被丢弃、写回时随 experimental 一并保留。
     * 幂等：再次调用时未知键已在 experimental（一个已知键）内，不再变动。
     */
    fun foldUnknownIntoExperimental(doc: JsonObject): JsonObject {
        val unknown = doc.filterKeys { it !in KNOWN_TOP_LEVEL_KEYS }
        if (unknown.isEmpty()) return doc
        val existing = (doc["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
        val merged = JsonObject(existing + unknown)
        val out = doc.toMutableMap()
        unknown.keys.forEach { out.remove(it) }
        out["experimental"] = merged
        return JsonObject(out)
    }

    /**
     * v1→v2（引用流重设计）：fileMessage 的 4 个退役键**一律从 fileMessage 移除**
     * （typed 域已无此字段，留着会在 encodeDefaults 写回时产生双份键），
     * 其中取值非默认的再搬入 `experimental.retiredV1.<原键名>`（用户在配置文件里看得见什么被退役）。
     *
     * 幂等：再次调用时退役键已不在 fileMessage ⇒ no-op；缺 fileMessage 段 ⇒ no-op。
     * 默认值判定（搬移条件）：`triggerPrefix` 非 `""`、`pendingWindowMillis` 非 `60000`、
     * `textInlineMaxBytes` 非 `262144`、`imageBatchWindowMillis` 非 `500`——默认值直接静默丢弃（无信息量）。
     */
    internal fun migrateV1ToV2(version: Int, doc: JsonObject): JsonObject {
        if (version >= 2) return doc
        val fileMessage = doc["fileMessage"] as? JsonObject ?: return doc
        val retiredKeys = RETIRED_V1_FILE_KEYS.filter { fileMessage.containsKey(it) }
        if (retiredKeys.isEmpty()) return doc
        val retired = buildMap {
            putIfNonDefault(fileMessage, "triggerPrefix", JsonPrimitive(""))
            putIfNonDefault(fileMessage, "pendingWindowMillis", JsonPrimitive(60_000))
            putIfNonDefault(fileMessage, "textInlineMaxBytes", JsonPrimitive(262_144))
            putIfNonDefault(fileMessage, "imageBatchWindowMillis", JsonPrimitive(500))
        }
        val kept = fileMessage.filterKeys { it !in retiredKeys }
        val out = doc.toMutableMap()
        out["fileMessage"] = JsonObject(kept)
        if (retired.isNotEmpty()) {
            val experimental = (doc["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
            val retiredBucket = (experimental["retiredV1"] as? JsonObject) ?: JsonObject(emptyMap())
            out["experimental"] = JsonObject(
                experimental.toMutableMap().apply {
                    this["retiredV1"] = JsonObject(retiredBucket.toMutableMap().apply { putAll(retired) })
                },
            )
        }
        return JsonObject(out)
    }

    /** 键存在且值 != 默认 ⇒ 收进 retired（JsonElement 内容比较语义）。 */
    private fun MutableMap<String, JsonElement>.putIfNonDefault(
        section: JsonObject,
        key: String,
        defaultValue: JsonElement,
    ) {
        val value = section[key] ?: return
        if (value != defaultValue) put(key, value)
    }

    /**
     * v2→v3（进度卡防刷屏）：agent.progress 的 maxEntries / cardMaxChars
     * 若显式存了**旧默认值**（12 / 5000——encodeDefaults=true 使每次 UI 保存全量写回，存量文档几乎必有），
     * 从文档中删除该键 ⇒ decode 落到 typed 层新默认（6 / 2000）；下次 UI 保存以新默认回写，即完成收敛。
     * 用户改过的非默认值**原样保留**（尊重用户调参，绝不静默改动）。
     *
     * 幂等：删键后（或本就无键/无 agent 段）再次调用 no-op。
     * entryMaxChars 不在 v2 文档里（当时无此配置载体），无需处理。
     */
    internal fun migrateV2ToV3(version: Int, doc: JsonObject): JsonObject {
        // 🔴 框架语义：step 收到的 version = fromVersion + 本步下标（上一步即使 no-op 也照常 +1）。
        //    本步是下标 1：v2 文档收到 3（必须处理）；v3+ 文档收到 >= 4（跳过）。
        //    守卫不能写 `>= 3`——那会把真正的 v2 文档也跳掉。
        if (version >= 4) return doc
        val agent = doc["agent"] as? JsonObject ?: return doc
        val progress = agent["progress"] as? JsonObject ?: return doc
        val staleKeys = PROGRESS_OLD_DEFAULT_KEYS.filter { key ->
            progress[key]?.let { it == PROGRESS_OLD_DEFAULTS[key] } == true
        }
        if (staleKeys.isEmpty()) return doc
        val out = doc.toMutableMap()
        out["agent"] = JsonObject(
            agent.toMutableMap().apply {
                this["progress"] = JsonObject(progress.filterKeys { it !in staleKeys })
            },
        )
        return JsonObject(out)
    }

    /** v2 时 agent.progress 的旧默认显式值（键 → 旧默认 JSON 值）；等于旧默认才删。 */
    private val PROGRESS_OLD_DEFAULT_KEYS: Set<String> = setOf("maxEntries", "cardMaxChars")

    private val PROGRESS_OLD_DEFAULTS: Map<String, JsonElement> = mapOf(
        "maxEntries" to JsonPrimitive(12),
        "cardMaxChars" to JsonPrimitive(5000),
    )

    /**
     * v3→v4（滚动窗口精简）：agent.progress 的 `patchIntervalMillis` 退役——编辑器有、
     * 管线零消费的死配置（真实节流是网关固定 streamThrottleMs=250ms），typed 域已删除该字段，
     * 残键在 encodeDefaults=true 的全量写回时会复现双份键。处理与 v1→v2 退役键同构：
     * **一律从 progress 移除**；取值非默认（≠2000）再搬入 `experimental.retiredV3.patchIntervalMillis` 保底。
     *
     * 幂等：移除后（或本就无键/无 progress 段）再次调用 no-op。
     */
    internal fun migrateV3ToV4(version: Int, doc: JsonObject): JsonObject {
        // 🔴 框架语义：step 收到的 version = fromVersion + 本步下标（上一步即使 no-op 也照常 +1）。
        //    本步是下标 2：v3 文档收到 5（必须处理）；v4+ 文档收到 >= 6（跳过）。
        //    守卫不能写 `>= 5`——那会把真正的 v3 文档也跳掉（v2→v3 步内的先例注释同理）。
        if (version >= 6) return doc
        val agent = doc["agent"] as? JsonObject ?: return doc
        val progress = agent["progress"] as? JsonObject ?: return doc
        if (!progress.containsKey("patchIntervalMillis")) return doc
        val retired = mutableMapOf<String, JsonElement>()
        val value = progress["patchIntervalMillis"]
        if (value != null && value != JsonPrimitive(2000L)) {
            retired["patchIntervalMillis"] = value
        }
        val out = doc.toMutableMap()
        out["agent"] = JsonObject(
            agent.toMutableMap().apply {
                this["progress"] = JsonObject(progress.filterKeys { it != "patchIntervalMillis" })
            },
        )
        if (retired.isNotEmpty()) {
            val experimental = (doc["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
            val retiredBucket = (experimental["retiredV3"] as? JsonObject) ?: JsonObject(emptyMap())
            out["experimental"] = JsonObject(
                experimental.toMutableMap().apply {
                    this["retiredV3"] = JsonObject(retiredBucket.toMutableMap().apply { putAll(retired) })
                },
            )
        }
        return JsonObject(out)
    }

    /** v1 的 fileMessage 退役键集合（typed 域 v2 已删除的键）。 */
    private val RETIRED_V1_FILE_KEYS: Set<String> =
        setOf("triggerPrefix", "pendingWindowMillis", "textInlineMaxBytes", "imageBatchWindowMillis")

    /**
     * v4→v5（入站传输恒自研化）：connection.selfBuiltWs 退役——入站恒走自研 `:lark-ws`
     * 传输，开关不再存在。与 v3→v4 同款：typed 域已无此字段，留着会在 encodeDefaults 写回时产生双份键。
     * 取值非默认（≠false，即用户曾显式开启）的搬入 `experimental.retiredV4.selfBuiltWs` 保底；
     * 默认值（false）静默丢弃（无信息量）。幂等、缺 connection 段时 no-op。
     */
    internal fun migrateV4ToV5(version: Int, doc: JsonObject): JsonObject {
        // 🔴 框架语义：本步是链上下标 3：v4 文档收到 7（必须处理）；v5+ 文档收到 >= 8（跳过）。
        //    守卫不能写 `>= 7`——那会把真正的 v4 文档也跳掉。
        if (version >= 8) return doc
        val connection = doc["connection"] as? JsonObject ?: return doc
        if (!connection.containsKey("selfBuiltWs")) return doc
        val value = connection["selfBuiltWs"]
        val out = doc.toMutableMap()
        out["connection"] = JsonObject(connection.filterKeys { it != "selfBuiltWs" })
        // 非默认值（true：用户曾显式开启自研入站）保底进 retiredV4——用户看得见什么被退役
        if (value != null && value != JsonPrimitive(false)) {
            val experimental = (doc["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
            val retiredBucket = (experimental["retiredV4"] as? JsonObject) ?: JsonObject(emptyMap())
            out["experimental"] = JsonObject(
                experimental.toMutableMap().apply {
                    this["retiredV4"] = JsonObject(retiredBucket.toMutableMap().apply { put("selfBuiltWs", value) })
                },
            )
        }
        return JsonObject(out)
    }

    /**
     * v5→v6（Range 分片）：fileMessage.maxFileSizeBytes 的**旧默认显式值**（104857600 = 100MB）删键
     * 收敛到新默认（1073741824 = 1GB）。与 v2→v3 同构：encodeDefaults=true 使 UI 保存全量写回 ⇒ 存量文档
     * 几乎必然躺着等于旧默认的显式键——**只改 typed 默认对存量用户零生效**，必须删键让 decode 落新默认。
     * 用户改过的非默认值**原样保留**（尊重用户调参）。
     *
     * 背景：Range 分片突破飞书平台 100MB 完整下载限 ⇒ 此值从「平台限传导」转为纯磁盘保护阈值，
     * 默认 100MB→1GB，编辑器范围 1MB–4GB。
     *
     * 幂等：删键后（或本就无键/无 fileMessage 段）再次调用 no-op。
     */
    internal fun migrateV5ToV6(version: Int, doc: JsonObject): JsonObject {
        // 🔴 框架语义：本步是链上下标 4：v5 文档收到 9（必须处理）；v6+ 文档收到 >= 10（跳过）。
        //    守卫不能写 `>= 9`——那会把真正的 v5 文档也跳掉。
        if (version >= 10) return doc
        val fileMessage = doc["fileMessage"] as? JsonObject ?: return doc
        if (fileMessage["maxFileSizeBytes"] != JsonPrimitive(104857600L)) return doc
        val out = doc.toMutableMap()
        out["fileMessage"] = JsonObject(fileMessage.filterKeys { it != "maxFileSizeBytes" })
        return JsonObject(out)
    }

    /**
     * v6→v7（调度层正名）：`agent.backend == "koog"` 语义等价转换——
     * koog 从 backend 值域移除（它是调度层不是执行引擎，首版挤进下拉造成「换引擎」迷惑且
     * 令 CLI 委托链死锁），改为独立开关 `agent.dispatchEnabled` 承载。
     * 转换规则：backend=koog ⇒ backend 改回 "claude" + dispatchEnabled=true
     * （选 koog 的用户，其意图正是「开启本地 AI 调度」；CLI 委托目标回默认 claude，
     * 用户可在执行后端下拉自行改选）。其他 backend 值不动、dispatchEnabled 保持文档原值
     * （v6 文档无此键 ⇒ decode 落默认 false，存量 CLI 用户行为零变化）。
     *
     * 幂等：backend 非 koog（或本就无 agent 段）再次调用 no-op。
     */
    internal fun migrateV6ToV7(version: Int, doc: JsonObject): JsonObject {
        // 🔴 框架语义：本步是链上下标 5：v6 文档收到 11（必须处理）；v7+ 文档收到 >= 12（跳过）。
        //    守卫不能写 `>= 11`——那会把真正的 v6 文档也跳掉。
        if (version >= 12) return doc
        val agent = doc["agent"] as? JsonObject ?: return doc
        if (agent["backend"] != JsonPrimitive("koog")) return doc
        val out = doc.toMutableMap()
        out["agent"] = JsonObject(
            agent.toMutableMap().apply {
                this["backend"] = JsonPrimitive("claude")
                this["dispatchEnabled"] = JsonPrimitive(true)
            },
        )
        return JsonObject(out)
    }

    /**
     * v7→v8（agent-first 重构：三死配置 + 配置删除合成一步）：
     *
     * **死配置段**：agent.session.{capacity,keyByThread}（生产零接线）、
     * fileMessage.allowedExtensions（运行时零消费）。
     *
     * **配置删除段**：
     * - 8 标量残键退役：access.rejectionReply（拒绝改默认静默）、access.groupRequireMention
     *   （真源迁 agent.groupMode）、runtime.unhandledReply（TEXT 兜底归调度 agent；非 TEXT 降级
     *   文案机制保留）、agent.dispatchEnabled（升格默认）、fileMessage.ackTemplate（文件
     *   登记改默认静默）、agent.artifacts.{reuseFileKey,memoTtlHours,memoMaxEntries}
     *   （上传复用批次整体退役）；
     * - routes 里的 AgentRoute 匹配键退役：非默认 stopCommand/resetCommand/triggers 折入
     *   `experimental.retiredV8.routes.agent.*`（旧词迁移通道——用户改过的停止词
     *   如「停」经 [EngineReservedWords] 全程存活）；AgentRoute 残段保留（id/enabled 仍有效）；
     * - CommandRoute.promptTemplate 非空者按块头格式附加进 agent.systemPrompt
     *   （模板含 {args} 占位、多条拼接无分隔的迁移产物质量问题；原文同时折入 retiredV8 保底）。
     *
     * 处理与 v3→v4 / v4→v5 退役键同构：**一律从所在段移除**（typed 域已无字段，留着会在
     * encodeDefaults 写回时产生双份键），取值非默认的搬入 `experimental.retiredV8.<点路径>` 保底
     * （用户在配置文件里看得见什么被退役）；默认值静默丢弃（无信息量）。
     *
     * `session` 段若被掏空则整个段删除（SessionConfig 已是空壳）。
     *
     * 幂等：移除后（或本就无键/无段）再次调用 no-op。
     */
    internal fun migrateV7ToV8(version: Int, doc: JsonObject): JsonObject {
        // 🔴 框架语义：本步是链上下标 6：v7 文档收到 13（必须处理）；v8+ 文档收到 >= 14（跳过）。
        //    守卫不能写 `>= 13`——那会把真正的 v7 文档也跳掉（v2→v3 步内注释的同型差一先例）。
        if (version >= 14) return doc
        val retired = mutableMapOf<String, JsonElement>()
        val out = doc.toMutableMap()

        // ── agent.session.{capacity,keyByThread}（P0）──
        val agent = doc["agent"] as? JsonObject
        val session = agent?.get("session") as? JsonObject
        if (session != null) {
            val keptSession = session.toMutableMap()
            retireSessionKey(session, keptSession, "capacity", JsonPrimitive(64), retired)
            retireSessionKey(session, keptSession, "keyByThread", JsonPrimitive(true), retired)
            out["agent"] = JsonObject(
                agent.toMutableMap().apply {
                    if (keptSession.isEmpty()) {
                        this.remove("session")
                    } else {
                        this["session"] = JsonObject(keptSession)
                    }
                },
            )
        }

        // ── fileMessage.allowedExtensions（P0）──
        val fileMessage = doc["fileMessage"] as? JsonObject
        if (fileMessage != null && fileMessage.containsKey("allowedExtensions")) {
            val value = fileMessage["allowedExtensions"]
            out["fileMessage"] = JsonObject(fileMessage.filterKeys { it != "allowedExtensions" })
            if (value != null && value != JsonPrimitive(".log,.txt,.xlog,.alog,.zip,.tar,.tar.gz,.tgz,.gz")) {
                retired["fileMessage.allowedExtensions"] = value
            }
        }

        // ── P3 段：8 标量残键 + AgentRoute 匹配键 + promptTemplate 通道 ──
        migrateP3ScalarKeys(doc, out, retired)
        val appendix = migrateP3Routes(doc, out, retired)

        if (retired.isEmpty() && appendix.isEmpty()) return JsonObject(out)
        val experimental = (doc["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
        val retiredBucket = (experimental["retiredV8"] as? JsonObject) ?: JsonObject(emptyMap())
        out["experimental"] = JsonObject(
            experimental.toMutableMap().apply {
                this["retiredV8"] = JsonObject(retiredBucket.toMutableMap().apply { putAll(retired) })
            },
        )
        // promptTemplate 通道结果附加进 agent.systemPrompt（在 experimental 装配之后——out 的 agent 段）
        if (appendix.isNotEmpty()) {
            val currentAgent = out["agent"] as? JsonObject ?: JsonObject(emptyMap())
            val basePrompt = (currentAgent["systemPrompt"] as? JsonPrimitive)?.content ?: ""
            out["agent"] = JsonObject(
                currentAgent.toMutableMap().apply {
                    this["systemPrompt"] = JsonPrimitive(
                        if (basePrompt.isBlank()) appendix
                        else basePrompt + "\n\n" + appendix,
                    )
                },
            )
        }
        return JsonObject(out)
    }

    /**
     * v8→v9（agent.nativeTools 段引入）：**纯版本锚点步**——零数据变换。
     * 本批新增 `agent.nativeTools` 配置段全部带默认值（l2Enabled=false + grants 空），
     * 存量 v8 文档 decode 直接落 typed 默认即可，无需任何键搬移；
     * 本步存在的意义是把 schemaVersion 从 8 推到 9（版本号与「配置面形状」同步演进，
     * 为批 C 的 L2 配置消费留版本锚点——若后续发现需要搬移，有专属步可写）。
     *
     * 幂等：恒 no-op（除版本戳由 migrate 统一盖写）。
     */
    internal fun migrateV8ToV9(version: Int, doc: JsonObject): JsonObject {
        // 🔴 框架语义：本步是链上下标 7：v8 文档收到 15（必须处理）；v9+ 文档收到 >= 16（跳过）。
        //    守卫差一纪律同 v7→v8 步（:332 的 version >= 14）——不能写 `>= 15`，
        //    那会把真正的 v8 文档也跳掉。
        if (version >= 16) return doc
        return doc
    }

    /**
     * v9→v10（agent 硬依赖化）：agent 不再是可关闭的组件，降级运行态整体退役。
     *
     * **① agent.enabled 总闸退役**：agent 段残键一律移除（BridgeJson 的 ignoreUnknownKeys 使
     * 残键 decode 自然消亡，本段搬移的必要性在「显式 false 用户保底可见」而非 decode 安全）；
     * 显式 false（用户曾主动关闸）折入
     * `experimental.retiredV10."agent.enabled"` 保底（用户在配置文件里看得见什么被退役），
     * 默认 true 静默丢（无信息量）。
     *
     * **② routes 数组中 kind=reply 条目整条剥离**（固定回复槽退役）：🔴 不剥离则旧文档经
     * BridgeJson 的 sealed 多态 decode 直接失败（ReplyRoute 类型已不存在）→ ConfigParseFailed。
     * trigger/reply 非空者折入 `experimental.retiredV10.routes.reply.<id>.*` 保底；空壳条目静默丢。
     *
     * 处理与 v7→v8 退役键同构：一律从所在段/数组移除 + 非默认值保底。
     * 幂等：移除后（或本就无键/无段/无 reply 条目）再次调用 no-op。
     */
    internal fun migrateV9ToV10(version: Int, doc: JsonObject): JsonObject {
        // 🔴 框架语义：本步是链上下标 8：v9 文档收到 17（必须处理）；v10+ 文档收到 >= 18（跳过）。
        //    守卫差一纪律同 v8→v9 步（version >= 16 先例）——不能写 `>= 17`，那会把真正的
        //    v9 文档也跳掉（enabled 残键不退役、reply 条目不剥离 ⇒ sealed decode 直接炸）。
        if (version >= 18) return doc
        val out = doc.toMutableMap()
        val retired = mutableMapOf<String, JsonElement>()

        // ── ① agent.enabled 残键退役 ──
        val agent = out["agent"] as? JsonObject
        if (agent != null && agent.containsKey("enabled")) {
            val kept = agent.toMutableMap()
            retireScalar(kept, "agent.enabled", "enabled", JsonPrimitive(true), retired)
            out["agent"] = JsonObject(kept)
        }

        // ── ② routes 数组中 kind=reply 条目整条剥离 ──
        val routes = out["routes"] as? kotlinx.serialization.json.JsonArray
        if (routes != null) {
            val keptElements = mutableListOf<JsonElement>()
            var replySeen = false
            for (element in routes) {
                val route = element as? JsonObject
                if (route != null && (route["kind"] as? JsonPrimitive)?.content == "reply") {
                    replySeen = true
                    retireReplyRoute(route, retired)
                } else {
                    keptElements.add(element)
                }
            }
            if (replySeen) out["routes"] = kotlinx.serialization.json.JsonArray(keptElements)
        }

        if (retired.isEmpty()) return JsonObject(out)
        val experimental = (out["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
        val retiredBucket = (experimental["retiredV10"] as? JsonObject) ?: JsonObject(emptyMap())
        out["experimental"] = JsonObject(
            experimental.toMutableMap().apply {
                this["retiredV10"] = JsonObject(retiredBucket.toMutableMap().apply { putAll(retired) })
            },
        )
        return JsonObject(out)
    }

    /**
     * v10→v11：CLI 委托链退役——11 个 CLI 专用键 + appServer 顶层段退役。
     *
     * 框架语义：链上下标 9——v10 文档收到 19（必须处理）；v11+ 文档收到 >= 20（跳过）。
     * 非默认值折 `experimental.retiredV11.<点路径>` 保底（用户在配置文件里看得见什么被退役），
     * 默认值静默丢；appServer 段非默认态（enabled=true 或显式键存在）整段折
     * `retiredV11.appServer` 保底——纯默认段静默丢。
     */
    internal fun migrateV10ToV11(version: Int, doc: JsonObject): JsonObject {
        if (version >= 20) return doc
        val out = doc.toMutableMap()
        val retired = mutableMapOf<String, JsonElement>()

        val agent = out["agent"] as? JsonObject
        if (agent != null) {
            val kept = agent.toMutableMap()
            retireScalar(kept, "agent.backend", "backend", JsonPrimitive("claude"), retired)
            retireScalar(kept, "agent.customTemplate", "customTemplate", JsonPrimitive(""), retired)
            retireScalar(kept, "agent.permissionPreset", "permissionPreset", JsonPrimitive("safe"), retired)
            retireScalar(kept, "agent.allowedTools", "allowedTools", JsonPrimitive(""), retired)
            retireScalar(kept, "agent.prohibitDelete", "prohibitDelete", JsonPrimitive(true), retired)
            retireScalar(kept, "agent.safetyPromptEnabled", "safetyPromptEnabled", JsonPrimitive(true), retired)
            val confirm = kept["confirm"] as? JsonObject
            if (confirm != null) {
                val keptConfirm = confirm.toMutableMap()
                retireScalar(keptConfirm, "agent.confirm.enabled", "enabled", JsonPrimitive(false), retired)
                retireScalar(keptConfirm, "agent.confirm.selfCheck", "selfCheck", JsonPrimitive("async"), retired)
                kept["confirm"] = JsonObject(keptConfirm)
            }
            out["agent"] = JsonObject(kept)
        }

        val workspace = out["workspace"] as? JsonObject
        if (workspace != null) {
            val kept = workspace.toMutableMap()
            retireScalar(kept, "workspace.agentWorkDir", "agentWorkDir", JsonPrimitive(""), retired)
            retireEnvAllowlist(kept, retired)
            retireScalar(kept, "workspace.outputCharset", "outputCharset", JsonPrimitive("utf-8"), retired)
            out["workspace"] = JsonObject(kept)
        }

        // appServer 顶层段整段退役：显式键存在即折（纯默认段静默丢）。
        val appServer = out["appServer"]
        if (appServer is JsonObject) {
            val nonDefault = appServer.entries.any { (_, v) ->
                when (v) {
                    is JsonPrimitive -> v.content !in listOf("false", "", "[]", "120", "filtered")
                    else -> true
                }
            }
            out.remove("appServer")
            if (nonDefault) retired["appServer"] = appServer
        }

        if (retired.isEmpty()) return JsonObject(out)
        val experimental = (out["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
        val retiredBucket = (experimental["retiredV11"] as? JsonObject) ?: JsonObject(emptyMap())
        out["experimental"] = JsonObject(
            experimental.toMutableMap().apply {
                this["retiredV11"] = JsonObject(retiredBucket.toMutableMap().apply { putAll(retired) })
            },
        )
        return JsonObject(out)
    }

    /**
     * v11→v12：L2 授权面收敛——agent.nativeTools 段（l2Enabled/l2Grants 双闸）整段退役。
     *
     * 框架语义：链上下标 10——v11 文档收到 21（必须处理）；v12+ 文档收到 >= 22（跳过）。
     * L1/L2 原生能力恒注册（NativeCapability 单源），授权闸收敛为飞书后台 scope 单一闸。
     * 非默认态（开关显式 true 或 grants 非空）折 `experimental.retiredV12.<点路径>` 保底
     * （用户在配置文件里看得见什么被退役）；纯默认段（false + 空数组）静默丢。
     */
    internal fun migrateV11ToV12(version: Int, doc: JsonObject): JsonObject {
        if (version >= 22) return doc
        val out = doc.toMutableMap()
        val retired = mutableMapOf<String, JsonElement>()

        val agent = out["agent"] as? JsonObject
        if (agent != null) {
            val nativeTools = agent["nativeTools"]
            if (nativeTools is JsonObject) {
                val kept = agent.toMutableMap()
                kept.remove("nativeTools")
                out["agent"] = JsonObject(kept)
                val nonDefault = nativeTools.entries.any { (_, v) ->
                    when (v) {
                        is JsonPrimitive -> v.content !in listOf("false", "")
                        is kotlinx.serialization.json.JsonArray -> v.isNotEmpty()
                        else -> true
                    }
                }
                if (nonDefault) {
                    nativeTools.entries.forEach { (k, v) ->
                        retired["agent.nativeTools.$k"] = v
                    }
                }
            }
        }

        if (retired.isEmpty()) return JsonObject(out)
        val experimental = (out["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
        val retiredBucket = (experimental["retiredV12"] as? JsonObject) ?: JsonObject(emptyMap())
        out["experimental"] = JsonObject(
            experimental.toMutableMap().apply {
                this["retiredV12"] = JsonObject(retiredBucket.toMutableMap().apply { putAll(retired) })
            },
        )
        return JsonObject(out)
    }

    /**
     * v12→v13：access 域语义翻转——准入白名单退役，改为「信任用户」名单。
     *
     * 框架语义：链上下标 11——v12 文档收到 23（必须处理）；v13+ 文档收到 >= 24（跳过）。
     *
     * 数据变换：
     * - `allowedSenders` 非空 ⇒ **平移**为 `access.trustedUsers`（原白名单成员本就是「自己人」，
     *   语义从「谁能用机器人」变为「谁能免确认」，断裂面最小）；同时折 `retiredV13` 保底可见。
     * - `blockedSenders` 非空 ⇒ 折 `retiredV13.access.blockedSenders`（准入已全放行，
     *   拦截语义消失，须让用户在配置文件里看得见）。
     * - `mode` 非默认（≠ "allowlist"）⇒ 折保底；默认值静默丢。
     */
    internal fun migrateV12ToV13(version: Int, doc: JsonObject): JsonObject {
        if (version >= 24) return doc
        val out = doc.toMutableMap()
        val retired = mutableMapOf<String, JsonElement>()

        val access = out["access"] as? JsonObject
        if (access != null) {
            val kept = access.toMutableMap()
            val allowed = (access["allowedSenders"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentIfNotBlank() }
                ?: emptyList()

            retireScalar(kept, "access.mode", "mode", JsonPrimitive("allowlist"), retired)
            retireScalar(kept, "access.allowedSenders", "allowedSenders",
                kotlinx.serialization.json.JsonArray(emptyList()), retired)
            retireScalar(kept, "access.blockedSenders", "blockedSenders",
                kotlinx.serialization.json.JsonArray(emptyList()), retired)

            if (allowed.isNotEmpty()) {
                kept["trustedUsers"] = kotlinx.serialization.json.JsonArray(allowed.map { JsonPrimitive(it) })
            }
            out["access"] = JsonObject(kept)
        }

        if (retired.isEmpty()) return JsonObject(out)
        val experimental = (out["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
        val retiredBucket = (experimental["retiredV13"] as? JsonObject) ?: JsonObject(emptyMap())
        out["experimental"] = JsonObject(
            experimental.toMutableMap().apply {
                this["retiredV13"] = JsonObject(retiredBucket.toMutableMap().apply { putAll(retired) })
            },
        )
        return JsonObject(out)
    }

    /**
     * v13→v14：能力域注册闸退役——sys.* / dev.* / self.status / startupProbe / ws.* 五域
     * 的开关字段整段删除（工具恒注册，注册面不再有配置闸）。
     *
     * 框架语义：链上下标 12——v13 文档收到 25（必须处理）；v14+ 文档收到 >= 26（跳过）。
     *
     * 为什么必须走迁移删键而非只改 typed 默认：`BridgeJson.encodeDefaults=true` ⇒ 存量文档
     * 必然躺着显式 `"enabled": false`，typed 层收到默认值仍会让工具不注册。
     *
     * 保底：sysTools/devTools/selfStatus/startupProbe 任一段存在非默认值（布尔显式 true 等）时
     * 整段折 `experimental.retiredV14.<点路径>`；wsTools 只退役四个开关，runTaskTimeoutMinutes 恒保留不折。
     */
    internal fun migrateV13ToV14(version: Int, doc: JsonObject): JsonObject {
        if (version >= 26) return doc
        val out = doc.toMutableMap()
        val retired = mutableMapOf<String, JsonElement>()

        val agent = out["agent"] as? JsonObject
        if (agent != null) {
            val kept = agent.toMutableMap()
            // 四域整段退役（段内无保留字段）
            listOf("sysTools", "devTools", "selfStatus", "startupProbe").forEach { section ->
                val seg = kept.remove(section) as? JsonObject ?: return@forEach
                val nonDefault = seg.entries.any { (_, v) ->
                    when (v) {
                        is JsonPrimitive -> v.content !in listOf("false", "")
                        is kotlinx.serialization.json.JsonArray -> v.isNotEmpty()
                        else -> true
                    }
                }
                if (nonDefault) {
                    seg.entries.forEach { (k, v) -> retired["agent.$section.$k"] = v }
                }
            }
            // wsTools 只退役四个开关，保留 runTaskTimeoutMinutes
            val ws = kept["wsTools"] as? JsonObject
            if (ws != null) {
                val keptWs = ws.toMutableMap()
                listOf("enabled", "writeEnabled", "runTaskEnabled", "gitEnabled").forEach { key ->
                    retireScalar(keptWs, "agent.wsTools.$key", key, JsonPrimitive(false), retired)
                }
                kept["wsTools"] = JsonObject(keptWs)
            }
            out["agent"] = JsonObject(kept)
        }

        if (retired.isEmpty()) return JsonObject(out)
        val experimental = (out["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
        val retiredBucket = (experimental["retiredV14"] as? JsonObject) ?: JsonObject(emptyMap())
        out["experimental"] = JsonObject(
            experimental.toMutableMap().apply {
                this["retiredV14"] = JsonObject(retiredBucket.toMutableMap().apply { putAll(retired) })
            },
        )
        return JsonObject(out)
    }

    /**
     * v14→v15：`fileMessage.xlogDecodeEnabled` 退役——xlog/alog 解密恒启用
     * （私钥为空时跳过并告警）。
     *
     * 框架语义：链上下标 13——v14 文档收到 27（必须处理）；v15+ 文档收到 >= 28（跳过）。
     *
     * 旧值显式 `true` 折 `experimental.retiredV15` 保底；`false` 静默丢（恒开语义下不再有意义）。
     */
    internal fun migrateV14ToV15(version: Int, doc: JsonObject): JsonObject {
        if (version >= 28) return doc
        val out = doc.toMutableMap()
        val retired = mutableMapOf<String, JsonElement>()
        val fm = out["fileMessage"] as? JsonObject
        if (fm != null) {
            val kept = fm.toMutableMap()
            retireScalar(kept, "fileMessage.xlogDecodeEnabled", "xlogDecodeEnabled", JsonPrimitive(false), retired)
            out["fileMessage"] = JsonObject(kept)
        }
        if (retired.isEmpty()) return JsonObject(out)
        val experimental = (out["experimental"] as? JsonObject) ?: JsonObject(emptyMap())
        val bucket = (experimental["retiredV15"] as? JsonObject) ?: JsonObject(emptyMap())
        out["experimental"] = JsonObject(
            experimental.toMutableMap().apply {
                this["retiredV15"] = JsonObject(bucket.toMutableMap().apply { putAll(retired) })
            },
        )
        return JsonObject(out)
    }

    /** 数组元素取非空白字符串（迁移内部工具）。 */
    private fun JsonPrimitive.contentIfNotBlank(): String? =
        content.trim().takeIf { it.isNotEmpty() }

    private fun retireEnvAllowlist(section: MutableMap<String, JsonElement>, retired: MutableMap<String, JsonElement>) {
        val value = section.remove("envAllowlist") ?: return
        val isDefault = value is kotlinx.serialization.json.JsonArray &&
            value.mapNotNull { (it as? JsonPrimitive)?.content } == DefaultBridgeConfig.DEFAULT_ENV_ALLOWLIST.toList()
        if (!isDefault) retired["workspace.envAllowlist"] = value
    }

    /** reply 条目退役保底：trigger/reply 非空者折 `retiredV10.routes.reply.<id>.*`（空壳静默丢）。 */
    private fun retireReplyRoute(route: JsonObject, retired: MutableMap<String, JsonElement>) {
        val id = (route["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "reply"
        (route["trigger"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let {
            retired["routes.reply.$id.trigger"] = JsonPrimitive(it)
        }
        (route["reply"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }?.let {
            retired["routes.reply.$id.reply"] = JsonPrimitive(it)
        }
    }

    /**
     * P3：8 个标量残键从所在段移除 + 非默认值收进 retired（点路径键）。
     * 🔴 一律从 [out]（运行中产物）而非 doc（原文）构建 kept——P0 段（session 掏空 /
     *    allowedExtensions 删除）可能已改写同段，从 doc 构建会把 P0 刚删的键**写回**
     *    （真实 v7 存量文档 encodeDefaults 全量写回，session 与 dispatchEnabled、
     *    allowedExtensions 与 ackTemplate 必然同段并存，此坑必触发）。
     */
    private fun migrateP3ScalarKeys(doc: JsonObject, out: MutableMap<String, JsonElement>, retired: MutableMap<String, JsonElement>) {
        // access.{rejectionReply,groupRequireMention}
        val access = out["access"] as? JsonObject
        if (access != null) {
            val kept = access.toMutableMap()
            retireScalar(kept, "access.rejectionReply", "rejectionReply", JsonPrimitive(""), retired)
            retireScalar(kept, "access.groupRequireMention", "groupRequireMention", JsonPrimitive(true), retired)
            out["access"] = JsonObject(kept)
        }
        // runtime.unhandledReply
        val runtime = out["runtime"] as? JsonObject
        if (runtime != null && runtime.containsKey("unhandledReply")) {
            val kept = runtime.toMutableMap()
            retireScalar(kept, "runtime.unhandledReply", "unhandledReply", JsonPrimitive(""), retired)
            out["runtime"] = JsonObject(kept)
        }
        // agent.dispatchEnabled（out.agent 可能已被 P0 段掏空 session——在其基础上改）
        val agent = out["agent"] as? JsonObject ?: (doc["agent"] as? JsonObject) ?: return
        if (agent.containsKey("dispatchEnabled") ||
            (agent["artifacts"] as? JsonObject)?.let {
                it.containsKey("reuseFileKey") || it.containsKey("memoTtlHours") || it.containsKey("memoMaxEntries")
            } == true
        ) {
            val kept = agent.toMutableMap()
            retireScalar(kept, "agent.dispatchEnabled", "dispatchEnabled", JsonPrimitive(false), retired)
            out["agent"] = JsonObject(kept)
            val artifacts = (out["agent"] as? JsonObject)?.get("artifacts") as? JsonObject
            if (artifacts != null) {
                val keptArtifacts = artifacts.toMutableMap()
                retireScalar(keptArtifacts, "agent.artifacts.reuseFileKey", "reuseFileKey", JsonPrimitive(false), retired)
                retireScalar(keptArtifacts, "agent.artifacts.memoTtlHours", "memoTtlHours", JsonPrimitive(168), retired)
                retireScalar(keptArtifacts, "agent.artifacts.memoMaxEntries", "memoMaxEntries", JsonPrimitive(512), retired)
                out["agent"] = JsonObject(
                    (out["agent"] as JsonObject).toMutableMap().apply { this["artifacts"] = JsonObject(keptArtifacts) },
                )
            }
        }
        // fileMessage.ackTemplate（out.fileMessage 可能已被 P0 段删掉 allowedExtensions——在其基础上改）
        val fm = out["fileMessage"] as? JsonObject
        if (fm != null && fm.containsKey("ackTemplate")) {
            val kept = fm.toMutableMap()
            retireScalar(kept, "fileMessage.ackTemplate", "ackTemplate", JsonPrimitive(""), retired)
            out["fileMessage"] = JsonObject(kept)
        }
    }

    /** 单键退役：默认值静默丢、非默认收进 retired 表（[section] 就地移除）。 */
    private fun retireScalar(
        section: MutableMap<String, JsonElement>,
        dottedKey: String,
        key: String,
        defaultValue: JsonElement,
        retired: MutableMap<String, JsonElement>,
    ) {
        val value = section.remove(key) ?: return
        if (value != defaultValue) retired[dottedKey] = value
    }

    /**
     * P3：routes 数组（typed 层序列化形态 = `List<RouteRule>` 数组，元素带 `kind` 判别符，
     * 非对象段——见 BridgeJson classDiscriminator="kind"）里的退役处理：
     * - AgentRoute 元素：非默认 stopCommand/resetCommand/triggers 折入
     *   `retiredV8.routes.agent.*`（旧词迁移通道，修正 5——[EngineReservedWords] 读取端；
     *   用户改过的停止词如「停」全程存活）；匹配键从元素移除（id/enabled 保留）。
     * - CommandRoute 元素：promptTemplate 非空者按块头格式附加进 agent.systemPrompt（修正 12），
     *   原文折入 retiredV8 保底；模板从元素移除（其余字段保留——CommandRoute 类仍存在）。
     *
     * @return systemPrompt 附录（promptTemplate 通道产物；空 = 无可迁模板）。
     */
    private fun migrateP3Routes(doc: JsonObject, out: MutableMap<String, JsonElement>, retired: MutableMap<String, JsonElement>): String {
        val routes = doc["routes"] as? kotlinx.serialization.json.JsonArray ?: return ""
        val appendix = StringBuilder()
        val keptElements = mutableListOf<JsonElement>()
        for (element in routes) {
            val route = element as? JsonObject ?: run { keptElements.add(element); continue }
            when ((route["kind"] as? JsonPrimitive)?.content) {
                "agent" -> {
                    val kept = route.toMutableMap()
                    retireScalar(kept, "routes.agent.stopCommand", "stopCommand", JsonPrimitive("停止"), retired)
                    retireScalar(kept, "routes.agent.resetCommand", "resetCommand", JsonPrimitive("新对话"), retired)
                    // triggers：数组形态（默认 ["ai","/ai"]）
                    val triggers = kept.remove("triggers")
                    if (triggers is kotlinx.serialization.json.JsonArray) {
                        val values = triggers.mapNotNull { (it as? JsonPrimitive)?.content }
                        if (values.isNotEmpty() && values != listOf("ai", "/ai")) {
                            retired["routes.agent.trigger"] = JsonPrimitive(values.joinToString(","))
                        }
                    }
                    keptElements.add(JsonObject(kept))
                }
                "command" -> {
                    val kept = route.toMutableMap()
                    val template = (kept["promptTemplate"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    if (template.isNotEmpty()) {
                        val name = (kept["name"] as? JsonPrimitive)?.content
                            ?: (kept["id"] as? JsonPrimitive)?.content ?: ""
                        val aliases = kept["aliases"] as? kotlinx.serialization.json.JsonArray
                        val aliasText = aliases?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString("、").orEmpty()
                        if (appendix.isEmpty()) {
                            appendix.appendLine("以下为用户历史快捷指令参考（旧指令体系的 prompt 模板；{args} 为占位符，用户消息参数将替换到该位置）：")
                        }
                        appendix.appendLine("快捷指令 $name（别名 $aliasText）：$template")
                        retired["routes.command.$name.promptTemplate"] = JsonPrimitive(template)
                        kept.remove("promptTemplate")
                    }
                    keptElements.add(JsonObject(kept))
                }
                else -> keptElements.add(element)
            }
        }
        if (keptElements.size != routes.size || keptElements.zip(routes).any { it.first != it.second }) {
            out["routes"] = kotlinx.serialization.json.JsonArray(keptElements)
        }
        return appendix.toString().trimEnd().ifEmpty { "" }
    }

    /** session 段退役单键：默认值静默丢、非默认收进 retired 表（键带 `agent.session.` 前缀）。 */
    private fun retireSessionKey(
        session: JsonObject,
        keptSession: MutableMap<String, JsonElement>,
        key: String,
        defaultValue: JsonElement,
        retired: MutableMap<String, JsonElement>,
    ) {
        val value = session[key] ?: return
        keptSession.remove(key)
        if (value != defaultValue) retired["agent.session.$key"] = value
    }
}
