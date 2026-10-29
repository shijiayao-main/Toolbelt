package com.jiaoay.lark.bridge.config

/**
 * 配置分组（[CDS §6.1]）。order 决定 UI 排列；initialExpanded 决定进阶区内该组小节是否默认展开
 * （字段级分层后组卡只存在于进阶区）。
 */
enum class ConfigGroup(
    val title: String,
    val order: Int,
    val initialExpanded: Boolean,
) {
    CONNECTION("连接", 0, initialExpanded = true),
    ACCESS("访问控制", 1, initialExpanded = true),
    WORKSPACE("工作区", 2, initialExpanded = true),
    RUNTIME("运行时", 3, initialExpanded = false), // config-domain-spec.md §16⑥ 4 个高级旋钮
    AGENT("Agent 与后端", 4, initialExpanded = true),
    FILE_MESSAGE("文件消息", 5, initialExpanded = false), // config-domain-spec.md §16⑥ 3 个高级旋钮
    OBSERVABILITY("可观测性", 6, initialExpanded = true),
}

/**
 * 字段分层（配置页基础·进阶拆分）。
 * - BASIC：配置页顶部「基础配置」区直出——全新用户「从零到收发第一条消息」的必看字段，清单逐字钉死在 BridgeFieldsTest。
 * - ADVANCED：收进下方默认折叠的「进阶配置」区（按 [ConfigGroup] 分小节）。
 *
 * 默认 ADVANCED：新增字段不显式标注即进阶——基础区只增不减，必须显式裁定（防漏标把新手必看项埋进折叠区）。
 * 纯 UI 元数据：不进 JSON 文档、不进 [BridgeConfig]、零迁移。
 * title 是 UI 文案（同 [ConfigGroup.title] 先例，声明式字段表承载文案，UI 零硬编码）。
 */
enum class FieldTier(val title: String) {
    BASIC("基础配置"),
    ADVANCED("进阶配置"),
}

/**
 * 生效方式（[CDS §5.1 / config-domain-spec.md §5.5]）。
 * HOT：经 gateway.updatePolicy() + AtomicReference<PipelineSnapshot> 无锁替换即生效，不断连、在途任务不受影响。
 * RESTART：需重建连接/重新装配子进程工厂（凭据、进程沙箱参数、后端选择等不可热替换的基础设施）。
 */
enum class ApplyMode { HOT, RESTART }

/**
 * 编辑器类型（[CDS §5.1]，8 种，未新增第 9 种）。
 * Number 的 min/max 恒为存储口径（字节），不是 displayScale 换算后的显示口径（config-domain-spec.md §16②）。
 * Number/Tags 各增带默认值的参数（displayScale/unitSuffix、pickDirectory）——加参数不加类型（config-domain-spec.md §16②/config-domain-spec.md §16③）。
 */
sealed interface Editor {
    data object Text : Editor
    data object Multiline : Editor
    data object Secret : Editor
    data object Switch : Editor
    data object Directory : Editor
    data class Number(
        val min: Long,
        val max: Long,
        val displayScale: Long = 1, // UI 显示换算除数（config-domain-spec.md §16②）
        val unitSuffix: String = "", // UI 显示单位后缀（config-domain-spec.md §16②）
    ) : Editor

    /**
     * 单选枚举。[options] 是落盘的 raw 值（校验与序列化以此为准）；[labels] 是 raw 值 → 展示文案的映射
     * （G-E：展示文案属于声明式字段表，不属于 UI 代码，故不违反 R-19「禁 UI 硬编码文案」）。
     * [labels] 缺省空表 ⇒ UI 渲染以 raw 值 fallback；建议为每个 option 填齐 label（BridgeFieldsTest 钉死覆盖）。
     */
    data class Choice(
        val options: List<String>,
        val labels: Map<String, String> = emptyMap(),
    ) : Editor
    data class Tags(
        val separator: String = ",",
        val pickDirectory: Boolean = false, // true=渲染「添加目录」按钮（config-domain-spec.md §16③）
    ) : Editor
}

/**
 * 单个可编辑标量字段的声明（[CDS §5.1]）。UI schema 驱动渲染与通用提交的最小单元。
 * @param id JSON 点路径，全局唯一
 * @param secret 打印/日志/check 输出统一掩码（前 4 后 4，中间 *）
 * @param validate 返回非 null = 校验失败消息；本表所有字段均用默认 {null}——
 *   数值区间（coerceIn 提示）/ 必填非空白 / 枚举合法性三类检查集中在 [ConfigValidator]（config-domain-spec.md §16⑤），
 *   跨字段规则（appId 必填条件、customTemplate {prompt}）无法在单字段 (T)->String? 签名内表达。
 */
class BridgeField<T>(
    val id: String,
    val group: ConfigGroup,
    val label: String,
    val hint: String,
    val editor: Editor,
    val secret: Boolean = false,
    val get: (BridgeConfig) -> T,
    val set: (BridgeConfig, T) -> BridgeConfig,
    val validate: (T) -> String? = { null },
    val applyMode: ApplyMode = ApplyMode.HOT,
    val tier: FieldTier = FieldTier.ADVANCED,
)

/**
 * 全部用户可编辑标量字段的声明表（[CDS §5.2]，逐行严格对齐）。
 * routes 是列表型字段，不进本表（[CDS §4.5]）。
 * 统计以 **BridgeFieldsTest 为唯一真源**（字段数 / HOT / RESTART / editor 计数 / tier 计数）——
 * 本 KDoc 不复述具体数字（加字段只改测试，不二次同步注释）。
 */
object BridgeFields {

    val ALL: List<BridgeField<*>> = listOf(
        // —— CONNECTION ——
        BridgeField(
            id = "connection.appId", group = ConfigGroup.CONNECTION, label = "App ID",
            hint = "飞书自建应用 App ID", editor = Editor.Text,
            get = { it.connection.appId },
            set = { c, v -> c.copy(connection = c.connection.copy(appId = v)) },
            applyMode = ApplyMode.RESTART,
            tier = FieldTier.BASIC,
        ),
        BridgeField(
            id = "connection.appSecret", group = ConfigGroup.CONNECTION, label = "App Secret",
            hint = "飞书自建应用密钥（绝不写入日志）", editor = Editor.Secret, secret = true,
            get = { it.connection.appSecret },
            set = { c, v -> c.copy(connection = c.connection.copy(appSecret = v)) },
            applyMode = ApplyMode.RESTART,
            tier = FieldTier.BASIC,
        ),
        BridgeField(
            id = "connection.region", group = ConfigGroup.CONNECTION, label = "区域",
            hint = "feishu=国内；lark=海外", editor = Editor.Choice(
                listOf("feishu", "lark"),
                labels = mapOf("feishu" to "飞书（国内）", "lark" to "Lark（海外）"),
            ),
            get = { it.connection.region },
            set = { c, v -> c.copy(connection = c.connection.copy(region = v)) },
            applyMode = ApplyMode.RESTART,
            tier = FieldTier.BASIC,
        ),
        BridgeField(
            id = "connection.autoStart", group = ConfigGroup.CONNECTION, label = "自动启动",
            hint = "应用启动时自动连接桥接", editor = Editor.Switch,
            get = { it.connection.autoStart },
            set = { c, v -> c.copy(connection = c.connection.copy(autoStart = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "connection.dryRun", group = ConfigGroup.CONNECTION, label = "干跑模式",
            hint = "不起子进程不连 WS，只打印", editor = Editor.Switch,
            get = { it.connection.dryRun },
            set = { c, v -> c.copy(connection = c.connection.copy(dryRun = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "connection.testChatId", group = ConfigGroup.CONNECTION, label = "测试 Chat ID",
            hint = "发送测试消息的目标", editor = Editor.Text,
            get = { it.connection.testChatId },
            set = { c, v -> c.copy(connection = c.connection.copy(testChatId = v)) },
            applyMode = ApplyMode.HOT,
        ),
        // connection.selfBuiltWs 开关已删除（入站恒自研，迁移 v4→v5 退役保底）
        // —— ACCESS ——
        BridgeField(
            id = "access.trustedUsers", group = ConfigGroup.ACCESS, label = "信任用户",
            hint = "信任用户的 open_id（逗号分隔）：名单内用户的高风险操作免逐次确认；" +
                "名单外用户遇到越界/破坏性操作会被直接拒绝。留空 = 无人免确认（安全默认）。" +
                "在飞书发 /whoami 可获取 open_id",
            editor = Editor.Tags(),
            get = { it.access.trustedUsers },
            set = { c, v -> c.copy(access = c.access.copy(trustedUsers = v)) },
            applyMode = ApplyMode.HOT,
            tier = FieldTier.BASIC,
        ),
        // —— WORKSPACE ——
        BridgeField(
            id = "workspace.root", group = ConfigGroup.WORKSPACE, label = "工作区根目录",
            hint = "agent 沙箱根目录。⚠️ 留空 = 沙箱拒绝所有路径，agent 任务会以「工作区缺失」失败", editor = Editor.Directory,
            get = { it.workspace.root },
            set = { c, v -> c.copy(workspace = c.workspace.copy(root = v)) },
            applyMode = ApplyMode.RESTART,
            tier = FieldTier.BASIC,
        ),
        BridgeField(
            id = "workspace.extraRoots", group = ConfigGroup.WORKSPACE, label = "额外目录",
            hint = "多目录白名单（带目录选择器）", editor = Editor.Tags(pickDirectory = true),
            get = { it.workspace.extraRoots },
            set = { c, v -> c.copy(workspace = c.workspace.copy(extraRoots = v)) },
            applyMode = ApplyMode.RESTART,
        ),
        // —— RUNTIME ——
        BridgeField(
            id = "runtime.maxConcurrentAgents", group = ConfigGroup.RUNTIME, label = "最大并发",
            hint = "同时处理的 agent 任务数", editor = Editor.Number(1, 16),
            get = { it.runtime.maxConcurrentAgents },
            set = { c, v -> c.copy(runtime = c.runtime.copy(maxConcurrentAgents = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "runtime.chatQueueLimit", group = ConfigGroup.RUNTIME, label = "聊天队列上限",
            hint = "同一 chat 排队上限", editor = Editor.Number(1, 8),
            get = { it.runtime.chatQueueLimit },
            set = { c, v -> c.copy(runtime = c.runtime.copy(chatQueueLimit = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "runtime.queueWaitSeconds", group = ConfigGroup.RUNTIME, label = "排队超时(秒)",
            hint = "等待许可的超时", editor = Editor.Number(5, 300),
            get = { it.runtime.queueWaitSeconds },
            set = { c, v -> c.copy(runtime = c.runtime.copy(queueWaitSeconds = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "runtime.busyReplyCooldownSeconds", group = ConfigGroup.RUNTIME, label = "繁忙冷却(秒)",
            hint = "同 chat 繁忙回复合批间隔", editor = Editor.Number(5, 300),
            get = { it.runtime.busyReplyCooldownSeconds },
            set = { c, v -> c.copy(runtime = c.runtime.copy(busyReplyCooldownSeconds = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "runtime.taskTimeoutSeconds", group = ConfigGroup.RUNTIME, label = "任务超时(秒)",
            hint = "通用任务超时", editor = Editor.Number(10, 3600),
            get = { it.runtime.taskTimeoutSeconds },
            set = { c, v -> c.copy(runtime = c.runtime.copy(taskTimeoutSeconds = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "runtime.agentTimeoutMinutes", group = ConfigGroup.RUNTIME, label = "Agent 超时(分)",
            hint = "agent 任务独立超时", editor = Editor.Number(1, 120),
            get = { it.runtime.agentTimeoutMinutes },
            set = { c, v -> c.copy(runtime = c.runtime.copy(agentTimeoutMinutes = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "runtime.agentIdleTimeoutSeconds", group = ConfigGroup.RUNTIME, label = "空闲超时(秒)",
            hint = "无输出即终止", editor = Editor.Number(10, 600),
            get = { it.runtime.agentIdleTimeoutSeconds },
            set = { c, v -> c.copy(runtime = c.runtime.copy(agentIdleTimeoutSeconds = v)) },
            applyMode = ApplyMode.HOT,
        ),
        // —— AGENT ——
        BridgeField(
            id = "agent.systemPrompt", group = ConfigGroup.AGENT, label = "System Prompt",
            hint = "注入 agent 的系统提示", editor = Editor.Multiline,
            get = { it.agent.systemPrompt },
            set = { c, v -> c.copy(agent = c.agent.copy(systemPrompt = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.persona", group = ConfigGroup.AGENT, label = "机器人人设",
            // 调度层轻量回复的语气/风格（output style 等价物）——与 System Prompt
            // 的语义分界：persona 只进本地调度层；systemPrompt 是全部回复的附加指令。
            hint = "调度层回复的语气与风格（如「用猫娘口吻回复」）；与 AI 设置页的「性格模式」" +
                "叠加生效（全局底座 + 本机器人专属），冲突时以本段为准",
            editor = Editor.Multiline,
            get = { it.agent.persona },
            set = { c, v -> c.copy(agent = c.agent.copy(persona = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.maxIterations", group = ConfigGroup.AGENT, label = "步数预算",
            // 调度 agent 的工具循环封顶。
            // 🔴 单位 = Koog 节点执行次数（稳态每工具轮 2 节点）：60 ≈ 29~30 轮；上限 300 防误配炸内存
            //    （300 节点 ≈ 150 工具轮，真实时限仍由「Agent 超时(分)」兜底）。
            hint = "本地 AI 调度 agent 的最大步数（Koop 节点数；每轮工具调用约耗 2 步，60 ≈ 30 轮）。" +
                "复杂任务被「步数预算用完」截停时可调大；真实时限仍由 Agent 超时兜底",
            editor = Editor.Number(10, 300),
            get = { it.agent.maxIterations },
            set = { c, v -> c.copy(agent = c.agent.copy(maxIterations = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.groupMode", group = ConfigGroup.AGENT, label = "群聊唤起模式",
            // 群聊消息进 agent 的判定——成本闸。默认 mention（仅@唤起）：
            // 群内闲聊不烧 LLM、不污染会话上下文；P2P 恒每条必答。
            hint = "群聊中哪些消息唤起本地 AI：仅@唤起（推荐，省成本）或全部回复。私聊不受影响，每条必答",
            editor = Editor.Choice(
                listOf("mention", "all"),
                labels = mapOf(
                    "mention" to "仅@唤起（推荐）",
                    "all" to "全部回复",
                ),
            ),
            get = { it.agent.groupMode },
            set = { c, v -> c.copy(agent = c.agent.copy(groupMode = v)) },
            applyMode = ApplyMode.HOT,
        ),
        // agent.session.capacity（会话容量）/ agent.session.keyByThread（按话题隔离）已删除：
        // 生产零接线死配置——LruSessionStore 装配点只传 clock（容量落类默认 64）、
        // keyOf 降级入口无生产调用。存量文档残键由迁移 v7→v8 折入 experimental.retiredV8 保底。
        BridgeField(
            id = "agent.progress.enabled", group = ConfigGroup.AGENT, label = "进度回显",
            hint = "流式更新执行过程", editor = Editor.Switch,
            get = { it.agent.progress.enabled },
            set = { c, v -> c.copy(agent = c.agent.copy(progress = c.agent.progress.copy(enabled = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.progress.maxEntries", group = ConfigGroup.AGENT, label = "进度条数",
            hint = "滚动窗口保留事件数", editor = Editor.Number(3, 50),
            get = { it.agent.progress.maxEntries },
            set = { c, v -> c.copy(agent = c.agent.copy(progress = c.agent.progress.copy(maxEntries = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.progress.entryMaxChars", group = ConfigGroup.AGENT, label = "单条字符上限",
            hint = "进度卡单条事件的截断长度", editor = Editor.Number(40, 200),
            get = { it.agent.progress.entryMaxChars },
            set = { c, v -> c.copy(agent = c.agent.copy(progress = c.agent.progress.copy(entryMaxChars = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.progress.cardMaxChars", group = ConfigGroup.AGENT, label = "卡片字符上限",
            hint = "进度卡内容上限（超长丢最旧）", editor = Editor.Number(500, 20000),
            get = { it.agent.progress.cardMaxChars },
            set = { c, v -> c.copy(agent = c.agent.copy(progress = c.agent.progress.copy(cardMaxChars = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.confirm.timeoutSeconds", group = ConfigGroup.AGENT, label = "确认超时(秒)",
            hint = "超时等同拒绝（下限 10s）", editor = Editor.Number(10, 3600),
            get = { it.agent.confirm.timeoutSeconds },
            set = { c, v -> c.copy(agent = c.agent.copy(confirm = c.agent.confirm.copy(timeoutSeconds = v))) },
            applyMode = ApplyMode.HOT,
        ),
        // —— agent.artifacts（产物回传，tier 默认 ADVANCED）——
        BridgeField(
            id = "agent.artifacts.enabled", group = ConfigGroup.AGENT, label = "产物回传",
            hint = "agent 最终回复末尾的 `[产物] <路径>` 标记行解析后作为文件消息回传", editor = Editor.Switch,
            get = { it.agent.artifacts.enabled },
            set = { c, v -> c.copy(agent = c.agent.copy(artifacts = c.agent.artifacts.copy(enabled = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.artifacts.maxCount", group = ConfigGroup.AGENT, label = "产物数量上限",
            hint = "单次回复最多回传的文件数", editor = Editor.Number(1, 10),
            get = { it.agent.artifacts.maxCount },
            set = { c, v -> c.copy(agent = c.agent.copy(artifacts = c.agent.artifacts.copy(maxCount = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.artifacts.maxSizeBytes", group = ConfigGroup.AGENT, label = "产物大小上限(MB)",
            hint = "单个产物文件的大小上限（飞书侧硬限约 30MB）",
            editor = Editor.Number(1048576, 31457280, displayScale = 1048576, unitSuffix = " MB"),
            get = { it.agent.artifacts.maxSizeBytes },
            set = { c, v -> c.copy(agent = c.agent.copy(artifacts = c.agent.artifacts.copy(maxSizeBytes = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "agent.artifacts.allowedExtensions", group = ConfigGroup.AGENT, label = "产物扩展名白名单",
            hint = "逗号分隔（如 .md,.txt）；空 = 不限", editor = Editor.Text,
            get = { it.agent.artifacts.allowedExtensions },
            set = { c, v -> c.copy(agent = c.agent.copy(artifacts = c.agent.artifacts.copy(allowedExtensions = v))) },
            applyMode = ApplyMode.HOT,
        ),
        // agent.nativeTools 段（l2Enabled/l2Grants 双闸）已随 v11→v12 迁移退役：
        // L1/L2 原生能力恒注册，授权闸收敛为飞书后台 scope 单一闸（NativeCapability 单源）。
        // —— agent.wsTools（仅保留数值钳制项；能力域开关已随 v13→v14 迁移退役）——
        // sys.* / dev.* / self.status / startupProbe / ws.* 五域恒注册，注册面不再有配置闸；
        // 高风险动作由 TrustPolicy 按用户裁决 + 逐动作确认卡兜底。
        BridgeField(
            id = "agent.wsTools.runTaskTimeoutMinutes", group = ConfigGroup.AGENT, label = "任务超时上限(分)",
            hint = "ws.run_task 的超时钳制上界（分钟，1..60；实际生效另受 0.8×整体预算软钳制）",
            editor = Editor.Number(1, 60),
            get = { it.agent.wsTools.runTaskTimeoutMinutes },
            set = { c, v -> c.copy(agent = c.agent.copy(wsTools = c.agent.wsTools.copy(runTaskTimeoutMinutes = v))) },
            applyMode = ApplyMode.HOT,
        ),
        // —— FILE_MESSAGE ——
        BridgeField(
            id = "fileMessage.enabled", group = ConfigGroup.FILE_MESSAGE, label = "文件消息",
            hint = "收到文件仅登记；引用回复并命中指令前缀才下载分析", editor = Editor.Switch,
            get = { it.fileMessage.enabled },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(enabled = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "fileMessage.maxFileSizeBytes", group = ConfigGroup.FILE_MESSAGE, label = "文件大小上限(MB)",
            hint = "UI 显示 MB，存储字节（默认 1GB）；Range 分片已突破飞书 100MB 平台限，此值是本地磁盘保护阈值，超过即拒绝下载并提示",
            editor = Editor.Number(1048576, 4294967296, displayScale = 1048576, unitSuffix = " MB"),
            get = { it.fileMessage.maxFileSizeBytes },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(maxFileSizeBytes = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "fileMessage.autoExtract", group = ConfigGroup.FILE_MESSAGE, label = "自动解包",
            hint = "引用触发下载后 zip/tar.gz/tgz/gz 自动解压", editor = Editor.Switch,
            get = { it.fileMessage.autoExtract },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(autoExtract = v)) },
            applyMode = ApplyMode.HOT,
        ),
        // fileMessage.allowedExtensions（允许扩展名）已删除：运行时零消费死配置。
        BridgeField(
            id = "fileMessage.registryTtlHours", group = ConfigGroup.FILE_MESSAGE, label = "登记有效期(小时)",
            hint = "文件登记可被引用回复的窗口（默认 72h）", editor = Editor.Number(1, 720),
            get = { it.fileMessage.registryTtlHours },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(registryTtlHours = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "fileMessage.perUserQuotaBytes", group = ConfigGroup.FILE_MESSAGE, label = "每用户配额(MB)",
            hint = "下载区按发送者分目录；超限按最旧清理（默认 2048MB）",
            editor = Editor.Number(1048576, 1099511627776, displayScale = 1048576, unitSuffix = " MB"),
            get = { it.fileMessage.perUserQuotaBytes },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(perUserQuotaBytes = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "fileMessage.totalQuotaBytes", group = ConfigGroup.FILE_MESSAGE, label = "全局配额(MB)",
            hint = "所有用户下载总量上限；超限跨用户按最旧清理（默认 8192MB）",
            editor = Editor.Number(1048576, 1099511627776, displayScale = 1048576, unitSuffix = " MB"),
            get = { it.fileMessage.totalQuotaBytes },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(totalQuotaBytes = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "fileMessage.xlogPrivateKey", group = ConfigGroup.FILE_MESSAGE, label = "XLog 私钥",
            hint = "XLog 解密私钥（hex）；绝不写入日志。留空时 App 形态自动回落本应用「XLog 解密」页的私钥；CLI 形态留空即未配置", editor = Editor.Secret, secret = true,
            get = { it.fileMessage.xlogPrivateKey },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(xlogPrivateKey = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "fileMessage.preprocessCommand", group = ConfigGroup.FILE_MESSAGE, label = "自定义预处理命令",
            hint = "argv 模板，须含 {input}，可含 {output}；直执行不起 shell", editor = Editor.Text,
            get = { it.fileMessage.preprocessCommand },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(preprocessCommand = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "fileMessage.preprocessExtensions", group = ConfigGroup.FILE_MESSAGE, label = "预处理作用扩展名",
            hint = "自定义命令作用的扩展名（逗号分隔，如 .zip,.xlog；空 = 所有文件）", editor = Editor.Text,
            get = { it.fileMessage.preprocessExtensions },
            set = { c, v -> c.copy(fileMessage = c.fileMessage.copy(preprocessExtensions = v)) },
            applyMode = ApplyMode.HOT,
        ),
        // —— OBSERVABILITY ——
        BridgeField(
            id = "observability.auditEnabled", group = ConfigGroup.OBSERVABILITY, label = "审计日志",
            hint = "记录 agent 执行审计", editor = Editor.Switch,
            get = { it.observability.auditEnabled },
            set = { c, v -> c.copy(observability = c.observability.copy(auditEnabled = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "observability.senderStatsEnabled", group = ConfigGroup.OBSERVABILITY, label = "发送者统计",
            hint = "记录对话量与用户名", editor = Editor.Switch,
            get = { it.observability.senderStatsEnabled },
            set = { c, v -> c.copy(observability = c.observability.copy(senderStatsEnabled = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "observability.auditRotationMb", group = ConfigGroup.OBSERVABILITY, label = "审计轮转(MB)",
            hint = "单文件大小上限", editor = Editor.Number(1, 64),
            get = { it.observability.auditRotationMb },
            set = { c, v -> c.copy(observability = c.observability.copy(auditRotationMb = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "observability.auditKeepFiles", group = ConfigGroup.OBSERVABILITY, label = "保留文件数",
            hint = "轮转保留的历史文件", editor = Editor.Number(1, 10),
            get = { it.observability.auditKeepFiles },
            set = { c, v -> c.copy(observability = c.observability.copy(auditKeepFiles = v)) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "observability.logConsole.capacity", group = ConfigGroup.OBSERVABILITY, label = "日志台容量",
            hint = "UI 显示的最大行数", editor = Editor.Number(100, 5000),
            get = { it.observability.logConsole.capacity },
            set = { c, v -> c.copy(observability = c.observability.copy(logConsole = c.observability.logConsole.copy(capacity = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "observability.logConsole.tickerHz", group = ConfigGroup.OBSERVABILITY, label = "刷新频率(Hz)",
            hint = "日志台合批刷新", editor = Editor.Number(1, 30),
            get = { it.observability.logConsole.tickerHz },
            set = { c, v -> c.copy(observability = c.observability.copy(logConsole = c.observability.logConsole.copy(tickerHz = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "observability.logConsole.followBottom", group = ConfigGroup.OBSERVABILITY, label = "跟随底部",
            hint = "新日志自动滚到底", editor = Editor.Switch,
            get = { it.observability.logConsole.followBottom },
            set = { c, v -> c.copy(observability = c.observability.copy(logConsole = c.observability.logConsole.copy(followBottom = v))) },
            applyMode = ApplyMode.HOT,
        ),
        BridgeField(
            id = "observability.logConsole.enabled", group = ConfigGroup.OBSERVABILITY, label = "日志台开关",
            hint = "关掉后 UI 日志台不再收 lib 侧事件", editor = Editor.Switch,
            get = { it.observability.logConsole.enabled },
            set = { c, v -> c.copy(observability = c.observability.copy(logConsole = c.observability.logConsole.copy(enabled = v))) },
            applyMode = ApplyMode.HOT,
        ),
    )
}
