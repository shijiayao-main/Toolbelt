package com.jiaoay.easy_dev_toolbox.page.larkbridge.field

import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.BridgeFields

/**
 * 配置页五分区 IA（结构真源登记文件见 docs/architecture.md「注释治理」节）——
 */
object P5FieldGroups {

    /** 机器人主卡字段（身份面：agent.persona / agent.systemPrompt / agent.groupMode）。 */
    val BOT_IDENTITY: Set<String> = setOf(
        "agent.persona",
        "agent.systemPrompt",
        "agent.groupMode",
    )

    data class ConfigZone(
        val title: String,
        val fieldIds: List<String>,
        val sectionTitles: Map<String, String> = emptyMap(),
    )

    /**
     * 能力域门控字段集：**已随 v13→v14 整段退役为空**。
     *
     * sys.* / dev.* / self.status / startupProbe / ws.* 五域工具恒注册，注册面不再有配置闸。
     * 保留空集与派生面 [DOMAIN_FACE_FIELDS] 是为守护测试的覆盖对账判据零改动
     * （「主卡 + 能力域 + 分区并集 == ALL」仍然成立）。
     */
    val DOMAIN_FACE: Set<String> = emptySet()

    val ZONES: List<ConfigZone> = listOf(
        ConfigZone(
            title = "连接与准入",
            fieldIds = listOf(
                "connection.appId",
                "connection.appSecret",
                "connection.region",
                "connection.autoStart",
                "connection.dryRun",
                "connection.testChatId",
                "access.trustedUsers",
                "workspace.root",
                "workspace.extraRoots",
            ),
        ),
        ConfigZone(
            title = "任务行为与限额",
            // 内嵌四小节（结构树 L3 直译）：progress/confirm/artifacts 同属 AGENT 组，
            // 小节标题由 sectionTitles 覆盖（不按 ConfigGroup.title 挂「Agent 与后端」一个头）
            sectionTitles = mapOf(
                "runtime.maxConcurrentAgents" to "运行时",
                "agent.maxIterations" to "Agent 策略",
                "agent.progress.enabled" to "进度",
                "agent.confirm.timeoutSeconds" to "确认",
                "agent.artifacts.enabled" to "产物",
            ),
            fieldIds = listOf(
                // 运行时小节（RUNTIME 组 7 件）
                "runtime.maxConcurrentAgents",
                "runtime.chatQueueLimit",
                "runtime.queueWaitSeconds",
                "runtime.busyReplyCooldownSeconds",
                "runtime.taskTimeoutSeconds",
                "runtime.agentTimeoutMinutes",
                "runtime.agentIdleTimeoutSeconds",
                // Agent 策略小节（maxIterations 单件——步数预算，本地 AI 调度的失控保险丝）
                "agent.maxIterations",
                "agent.wsTools.runTaskTimeoutMinutes",
                // 进度小节（agent.progress.* 4 件）
                "agent.progress.enabled",
                "agent.progress.maxEntries",
                "agent.progress.entryMaxChars",
                "agent.progress.cardMaxChars",
                // 确认小节（agent.confirm.timeoutSeconds 单件——写操作确认卡的等待上限）
                "agent.confirm.timeoutSeconds",
                // 产物小节（agent.artifacts.* 4 件）
                "agent.artifacts.enabled",
                "agent.artifacts.maxCount",
                "agent.artifacts.maxSizeBytes",
                "agent.artifacts.allowedExtensions",
            ),
        ),
        ConfigZone(
            title = "文件消息",
            fieldIds = listOf(
                "fileMessage.enabled",
                "fileMessage.maxFileSizeBytes",
                "fileMessage.autoExtract",
                "fileMessage.registryTtlHours",
                "fileMessage.perUserQuotaBytes",
                "fileMessage.totalQuotaBytes",
                "fileMessage.xlogPrivateKey",
                "fileMessage.preprocessCommand",
                "fileMessage.preprocessExtensions",
            ),
        ),
        ConfigZone(
            title = "可观测性",
            fieldIds = listOf(
                "observability.auditEnabled",
                "observability.senderStatsEnabled",
                "observability.auditRotationMb",
                "observability.auditKeepFiles",
                "observability.logConsole.enabled",
                "observability.logConsole.capacity",
                "observability.logConsole.tickerHz",
                "observability.logConsole.followBottom",
            ),
        ),
    )

    // ───────────────────────── 派生面（不手维护，防两清单漂移） ─────────────────────────

    /**
     * 全部分区字段 id 并集（对账用：主卡 + L2 面 + 本并集 == ALL）。
     * 守护测试逐字断言各分区计数（10/4/18/7/10/8），新增字段不标分区即红。
     */
    val ZONE_IDS: Set<String> = ZONES.flatMap { it.fieldIds }.toSet()

    /**
     * 分区平铺字段（旧 [ADVANCED] 派生的五分区等价物）：
     * ALL − 主卡 − 分区并集 = 空集由守护测试保证（无孤儿）。
     */
    val ZONE_FIELDS: List<BridgeField<*>> = BridgeFields.ALL.filter { it.id in ZONE_IDS }

    /** 能力域门控对应的 BridgeField 声明（恒空——字段已整段退役；保留供守护对账）。 */
    val DOMAIN_FACE_FIELDS: List<BridgeField<*>> = BridgeFields.ALL.filter { it.id in DOMAIN_FACE }

    /** 机器人主卡对应的 BridgeField 声明（按 ALL 声明序）。 */
    val BOT_IDENTITY_FIELDS: List<BridgeField<*>> = BridgeFields.ALL.filter { it.id in BOT_IDENTITY }

    fun zoneFields(zone: ConfigZone): List<BridgeField<*>> =
        BridgeFields.ALL.filter { it.id in zone.fieldIds }

    // ───────────────────────── 行级派生（修改态 / 生效方式，纯 feature 层计算） ─────────────────────────

    fun isModified(field: BridgeField<*>, config: com.jiaoay.lark.bridge.config.BridgeConfig): Boolean {
        @Suppress("UNCHECKED_CAST")
        val typed = field as BridgeField<Any?>
        return typed.get(config) != typed.get(com.jiaoay.lark.bridge.config.DefaultBridgeConfig.default())
    }

    /** 全部已修改字段的 id 集合（@modified 过滤 + 工具条徽标计数）。 */
    fun modifiedIds(config: com.jiaoay.lark.bridge.config.BridgeConfig): Set<String> =
        BridgeFields.ALL.filter { isModified(it, config) }.map { it.id }.toSet()

    /** 须重启生效的字段 id 集合（RESTART 全量，与是否已改无关——@restart 过滤的清单源）。 */
    val RESTART_IDS: Set<String> =
        BridgeFields.ALL.filter { it.applyMode == com.jiaoay.lark.bridge.config.ApplyMode.RESTART }.map { it.id }.toSet()
}
