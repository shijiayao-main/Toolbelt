package com.jiaoay.lark.bridge.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 单一 JSON 配置文档的根（[CDS §3.1 / 附录 A]）。
 * 所有字段均带默认值（存量配置 JSON 无新键时反序列化不受影响）。
 * experimental 原样暂存未识别顶层段（前向兼容，[CDS §10.2]）。
 * 数据根路径由宿主注入（[CDS §1.3]）——lib 侧不引用 :core FileUtils（jvmTarget 11 vs 21，R19）。
 */
@Serializable
data class BridgeConfig(
    // 🔴 schemaVersion 默认值必须与 ConfigMigrations.CURRENT_VERSION 同源（直接引用常量）：
    //    写死字面量会在加迁移步时与 CURRENT_VERSION 漂移——新建默认配置带旧版本戳，
    //    下次落盘经 load 会重跑已跑过的迁移步（幂等但属漂移）。
    val schemaVersion: Int = ConfigMigrations.CURRENT_VERSION,
    val connection: ConnectionConfig = ConnectionConfig(),
    val access: AccessConfig = AccessConfig(),
    val workspace: WorkspaceConfig = WorkspaceConfig(),
    val runtime: RuntimeConfig = RuntimeConfig(),
    val agent: AgentConfig = AgentConfig(),
    val fileMessage: FileMessageConfig = FileMessageConfig(),
    val observability: ObservabilityConfig = ObservabilityConfig(),
    val routes: List<RouteRule> = DefaultBridgeConfig.DEFAULT_ROUTES,
    val experimental: JsonObject = JsonObject(emptyMap()),
)

/** connection 域（[CDS §3.2.1]）。 */
@Serializable
data class ConnectionConfig(
    val appId: String = "",
    val appSecret: String = "",
    val region: String = "feishu",
    val autoStart: Boolean = false,
    val dryRun: Boolean = false,
    val testChatId: String = "",
    // selfBuiltWs 入站切换开关已删除：入站恒走自研 :lark-ws 传输。
    // 旧文档里的显式键由迁移 v4→v5 折入 experimental.retiredV4.selfBuiltWs 保底（取值非 false 时可见）。
)

/**
 * access 域：信任用户名单（高风险操作免确认的唯一授权载体）。
 *
 * 入站准入**恒全放行**（`mode`/`allowedSenders`/`blockedSenders` 三字段已退役，存量文档
 * 由迁移 v12→v13 处理——原白名单成员平移为本域 [trustedUsers]，黑名单折 retiredV13 保底）。
 * 服务面收紧要靠**飞书后台的机器人可用范围**，不在桥接配置里做 sender 级拦截。
 *
 * [trustedUsers] 判据见 [TrustPolicy]：名单内用户的高风险操作免逐动作确认；名单外用户
 * 遇到越界/破坏性操作**直接拒绝**（不发确认卡——不让陌生人自行批准自己的破坏性操作）。
 * 空名单 = 无人免确认（安全默认，不是「放行全部」）。
 */
@Serializable
data class AccessConfig(
    /** 信任用户 open_id 列表（空白项由 [TrustPolicy] 过滤）。 */
    val trustedUsers: List<String> = emptyList(),
)

/** workspace 域（[CDS §3.2.3]）。CLI 子进程专属键（agentWorkDir/envAllowlist/outputCharset）已随委托链退役。 */
@Serializable
data class WorkspaceConfig(
    val root: String = "",
    val extraRoots: List<String> = emptyList(),
)

/** runtime 域（[CDS §3.2.4]）。taskTimeoutSeconds=60；agentTimeoutMinutes=10。 */
@Serializable
data class RuntimeConfig(
    val maxConcurrentAgents: Int = 2,
    val chatQueueLimit: Int = 2,
    val queueWaitSeconds: Long = 30,
    val busyReplyCooldownSeconds: Long = 30,
    val taskTimeoutSeconds: Long = 60,
    val agentTimeoutMinutes: Long = 10,
    val agentIdleTimeoutSeconds: Long = 120,
    // unhandledReply（兜底回复模板）已删除：TEXT 未命中恒进调度 agent（App 形态；agent
    // 恒为硬依赖，不可用时产出显式报错卡）/ 恒静默留痕（CLI 形态）；非 TEXT kind 的
    // 降级文案机制（UnsupportedKindNotice）保留。存量残键折入 experimental.retiredV8 保底。
)

/** agent 域（[CDS §3.2.5]）。agent 恒为硬依赖（无独立开关）——桥接的应答能力以 agent 在位为前提：
 *  调度不可用时消息收显式报错卡、入口拒绝启动，不存在「关闭 agent 仍应答」的降级形态；
 *  安全由 access 默认 allowlist 空名单 fail-closed 兜底。
 *
 * CLI 委托后端字段（backend/customTemplate/permissionPreset/allowedTools/prohibitDelete/
 * safetyPromptEnabled）已随委托链整体退役：agent 域收敛为本地 AI（Koog）调度配置——
 * 全量未命中消息归调度 agent 是恒定语义，无「重任务委托目标」配置面。 */
@Serializable
data class AgentConfig(
    val systemPrompt: String = "",

    /**
     * 调度 agent 步数预算（默认 60）：Koog 内建 `maxAgentIterations`，
     * **单位 = 节点执行次数**（稳态每工具轮 2 节点 ⇒ 60 ≈ 29~30 工具轮；真机实测一次合法深度
     * 日志分析约需 15+ 轮）。触顶产出独立 errorCode `agent_max_iterations`（不落泛化 task_error）。
     * HOT 字段——Koog 调度 agent 每次 run 前现读，无需重启桥接。
     */
    val maxIterations: Int = 60,

    /**
     * 机器人人设（默认空 = 无附加人设）：决定调度 agent 轻量回复的语气与风格
     * （类似 Claude 的 output style）。只注入本地调度层提示词——**不透传给被委托的 CLI**
     * （CLI 重任务有自己的风格配置，人设不应污染其输出）；与 [systemPrompt]（任务向附加指令，
     * 两端共用）语义分界。HOT 字段。
     */
    val persona: String = "",

    /**
     * 群聊唤起模式（默认「仅@唤起」）：
     * - `mention`（默认）：群聊仅 @机器人 的消息进 agent——成本闸（每条闲聊一次 LLM 会同时
     *   烧钱与污染 ChatMemory 上下文；P2P 单聊恒每条必答不受影响）；
     * - `all`：群聊每条白名单消息都进 agent（体验最智能但成本最高）。
     * 消费点：PolicyGate 群@门控（原 access.groupRequireMention 的语义升级载体，本枚举为唯一真源）。
     * HOT 字段——PolicyGate 每消息现读。
     */
    val groupMode: String = "mention",
    val progress: ProgressConfig = ProgressConfig(),
    val confirm: ConfirmConfig = ConfirmConfig(),
    val artifacts: ArtifactsConfig = ArtifactsConfig(),

    /**
     * 工作区域数值钳制（`agent.wsTools.*`）：只剩 `runTaskTimeoutMinutes` 一项。
     *
     * 原五个域开关（sysTools/devTools/selfStatus/startupProbe/wsTools 的 enabled 与分开关）
     * 已随 v13→v14 迁移退役——工具恒注册，注册面不再有配置闸；高风险动作由
     * [com.jiaoay.lark.bridge.confirm.TrustPolicy] 按信任用户裁决 + 逐动作确认卡兜底。
     */
    val wsTools: WsToolsConfig = WsToolsConfig(),
)

/**
 * 工作区域数值钳制（`agent.wsTools.*`）。
 *
 * 原 `enabled` / `writeEnabled` / `runTaskEnabled` / `gitEnabled` 四个注册闸已随
 * v13→v14 迁移退役——ws 域与 git 域工具恒注册，准入由
 * [com.jiaoay.lark.bridge.confirm.TrustPolicy] + 逐动作确认卡承担。
 */
@Serializable
data class WsToolsConfig(
    /**
     * ws.run_task 超时上限（分钟，钳制域 1..60）：Args.timeout_minutes 的钳制上界；
     * 实际生效值另受 0.8×整体预算软钳制（软超时先于 agent 硬超时，倒置则任务被硬超时误杀）。
     */
    val runTaskTimeoutMinutes: Int = 10,
)

/**
 * agent 会话域（空壳）：
 * `capacity` / `keyByThread` 曾是**生产零接线的死配置**——`LruSessionStore` 装配点
 *    只传 clock，容量落类默认 64；`keyByThread` 降级入口无任何生产调用（管线一律传
 *    `ctx.sessionKey` 网关复合键）。两字段已从 typed 域删除（存量文档残键由迁移 v7→v8
 *    折入 experimental.retiredV8 保底）。域对象保留是为 `agent.session` JSON 段的前向兼容占位。
 */
@Serializable
class SessionConfig {
    // capacity/keyByThread 已删（生产零接线死配置，见类 KDoc）。
    //    域对象保留为 `agent.session` JSON 段的前向兼容占位（@Serializable 无参类可正常编解码空段）。
    override fun equals(other: Any?): Boolean = other is SessionConfig
    override fun hashCode(): Int = SessionConfig::class.hashCode()
    override fun toString(): String = "SessionConfig()"
}

@Serializable
data class ProgressConfig(
    val enabled: Boolean = true,
    val maxEntries: Int = 6,
    val entryMaxChars: Int = 80,
    val cardMaxChars: Int = 2000,
)

@Serializable
data class ConfirmConfig(
    /** 工具侧写操作确认卡的等待上限（ToolConfirmGate 消费；超时=拒绝）。 */
    val timeoutSeconds: Long = 60,
)

/**
 * 产物回传域（`agent.artifacts.*`）。
 * 协议：桥接注入「产物交付约定」，agent 在最终回复末尾每行一条 `[产物] <绝对路径>`；
 * 桥接解析标记 → 剥离正文 → 沙箱校验 → 上传为飞书文件消息。未写标记行 ⇒ 零行为变化。
 */
@Serializable
data class ArtifactsConfig(
    val enabled: Boolean = true,
    /** 单次回复最多上传文件数；超限条目降级还原（1..10）。默认 5（实测依据：多事件分析 skill 按 N+1 产出，4 事件=5 份，默认 3 会截断汇总报告）。 */
    val maxCount: Int = 5,
    /** 单文件大小上限（字节，UI MB 显示）；飞书 im/v1/files 硬限约 30MB，默认 25MB 留余量。 */
    val maxSizeBytes: Long = 26_214_400,
    /** 允许回传的扩展名（逗号分隔，如 `.md,.txt`）；空白 = 不限。 */
    val allowedExtensions: String = "",
    // reuseFileKey / memoTtlHours / memoMaxEntries（上传 file_key 复用三件套）已删除：
    // 价值前提（同字节流重传同 key、历史 key 有效期）始终未获真机验证，UploadKeyMemo 账本
    // 与整条接线同批退役；上行恒走「上传→send file_key」两步。
    // 存量残键折入 experimental.retiredV8 保底。
)

/**
 * fileMessage 域（[CDS §3.2.6]）。maxFileSizeBytes / 配额字段存储字节，UI 以 MB 显示。
 *
 * 退役键（v1→v2 迁移折入 experimental.retiredV1.*）：`triggerPrefix` / `pendingWindowMillis` /
 *    `textInlineMaxBytes` / `imageBatchWindowMillis`——旧关键词门控挂起机制整体删除，
 *    新语义 = 文件仅登记（FileRegistry），引用回复（replyToMessageId）命中前缀才懒下载。
 */
@Serializable
data class FileMessageConfig(
    val enabled: Boolean = true,
    /**
     * 文件大小上限（字节）。🔴 Range 分片已突破飞书平台 100MB 完整下载限
     * ⇒ 此值转为纯**本地磁盘保护阈值**（网关首片 Content-Range 预检 + 200 降级路径下载后拒绝双层把关）。
     * 默认 1GB；UI 编辑范围 1MB–4GB。
     */
    val maxFileSizeBytes: Long = 1073741824,
    val autoExtract: Boolean = true,
    // allowedExtensions（允许扩展名）已删除：全仓运行时零消费的死配置——
    // 仅 TaskErrorHint 的 FILE_EXTENSION_REJECTED 提示引用字段 id，而该错误码本身无生产者（零产出路径）。
    // 存量文档残键由迁移 v7→v8 折入 experimental.retiredV8.fileMessage.allowedExtensions 保底。
    // ackTemplate（收到文件回执模板）已删除：文件登记改**默认静默**
    // （引用流程本就有下载进度卡反馈，回执卡是重复噪音；接收事实仍经 FILE kind outcome +
    // Observe 审计留痕）。存量残键折入 experimental.retiredV8 保底。
    /** 登记表 TTL（小时）；过期后引用视为未命中，落回纯文本。 */
    val registryTtlHours: Long = 72,
    /** 每发送者下载配额（字节）；超限按收录时间 FIFO 逐出最旧的 per-message 目录。 */
    val perUserQuotaBytes: Long = 2147483648,
    /** 全局下载总配额（字节）；超限跨发送者按最旧逐出。 */
    val totalQuotaBytes: Long = 8589934592,
    /** XLog 私钥（hex）。🔴 Secret：check/日志/审计一律掩码。 */
    val xlogPrivateKey: String = "",
    /** 自定义预处理命令（argv 模板，须含 `{input}`，可含 `{output}`；直喂 ProgramLauncher，不起 shell）。 */
    val preprocessCommand: String = "",
    /** 自定义命令作用的扩展名（逗号分隔，如 `.zip,.xlog`；空白 = 对所有文件生效）。 */
    val preprocessExtensions: String = "",
)

/** observability 域（[CDS §3.2.7]）。 */
@Serializable
data class ObservabilityConfig(
    val auditEnabled: Boolean = true,
    val senderStatsEnabled: Boolean = true,
    val auditRotationMb: Int = 8,
    val auditKeepFiles: Int = 3,
    val logConsole: LogConsoleConfig = LogConsoleConfig(),
)

@Serializable
data class LogConsoleConfig(
    val enabled: Boolean = true,
    val capacity: Int = 1000,
    val tickerHz: Int = 10,
    val followBottom: Boolean = true,
)
