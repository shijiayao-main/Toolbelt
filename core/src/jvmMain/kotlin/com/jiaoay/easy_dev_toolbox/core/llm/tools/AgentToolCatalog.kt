package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.AgentToolMiddleware
import com.jiaoay.easy_dev_toolbox.core.llm.agent.decorateRegistry
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec

/**
 * 统一 agent 工具目录（产品「注册即接入」的承重墙）。
 *
 * ## 定位
 * 全仓 agent 工具的**单一事实源**：桌面链与飞书桥接两条链共用本目录——装配期按消费方需求
 * 过滤生成 ToolRegistry，「新增工具 = 注册即接入」的注册面只有这一处（勿在链侧自建手工 ToolRegistry）。
 */
object AgentToolCatalog {

    /** 工具风险分级（本地权限引擎依据；外放 MCP 时映射为行为注解） */
    enum class Risk {
        /** 只读：不改文件系统（probe/preview/read/grep/hash…）——默认放行 */
        READ_ONLY,

        /** 有副作用（写产物文件）：桌面 HITL 门可放行追加；外放 stdio 形态默认拒绝+文案 */
        DESTRUCTIVE,
    }

    /** omnibar 命令投影（P2 交互骨架消费；null = 不进命令面板的纯 agent 工具） */
    data class UiHint(
        val title: String,
        val keywords: List<String> = emptyList(),
        val description: String = "",
    )

    data class ToolContextConfig(
        val privateKeyProvider: (suspend () -> String)? = null,
        val todoBoard: TodoBoard? = null,
        val fenceHint: String? = null,
        val memoryDir: java.io.File? = null,
        /** 蒸馏候选暂存集（null = 本链不装 distill.*——蒸馏链点名装配） */
        val distillBuffer: DistillTools.ProposalBuffer? = null,
        /** agent 主动提问桥（Quest 域；null = 本链不装 user.ask_user——点名装配专用） */
        val userQuestionGate: UserQuestionGate? = null,
        /**
         * sys.* 域准入面（信任用户名单判定；null = 本链不装 sys.*——工具工厂返 null
         * 不注册，fail-closed；sys.probe / sys.read_config 的唯一装配门控）。
         */
        val sysSessionGate: SysSessionGate? = null,
        /**
         * sys.repair 逐动作授权面（信任用户免卡直执；非信任用户进不了域、到不了本面；
         * null = 本链不装——工厂返 null 不注册，fail-closed）。
         */
        val sysRepairGate: SysRepairTools.SysRepairGate? = null,
        /**
         * sys.write_config 逐写授权面（信任用户免卡直执；非信任用户进不了域、到不了本面；
         * null = 本链不装——工厂返 null 不注册，fail-closed）。
         */
        val sysWriteGate: SysWriteTools.SysWriteGate? = null,
        /**
         * sys.service 逐动作授权面（信任用户免卡直执；非信任用户进不了域、到不了本面；
         * null = 本链不装——工厂返 null 不注册，fail-closed）。
         */
        val sysServiceGate: SysServiceTools.SysServiceGate? = null,
        /**
         * dev.* 域准入面（信任用户名单判定；null = 本链不装 dev.*——工具工厂返 null
         * 不注册，fail-closed，见 [DevTools.DevSessionGate]）。dev.devices / dev.snaplog
         * 的唯一装配门控之一。
         */
        val devSessionGate: DevTools.DevSessionGate? = null,
        /**
         * dev.* 设备源缝（设备检测单源适配——每次调用触发新检测；null = 本链不装 dev.*，
         * 工厂返 null 不注册，fail-closed；serial 白名单的校验对象来源）。
         */
        val devDeviceSource: DevTools.DeviceSource? = null,
        /**
         * self.status 状态源缝（桥接内部健康数据面适配——连接态/计数/配置校验/审计聚合，
         * 全部进程内只读；null = 本链不装 self.status——工厂返 null 不注册，fail-closed；
         * 桌面链默认不装配，见 [SelfStatusTools]）。
         */
        val selfStatusSource: SelfStatusTools.SelfStatusSource? = null,
        /**
         * 工作区写（ws.write_file）逐写授权面（信任用户免卡直执；非信任用户进不了域、
         * 到不了本面；null = 本链不装——工厂返 null 不注册，fail-closed）。
         */
        val wsWriteGate: WsWriteTools.WsWriteGate? = null,
        /**
         * 受限任务执行（ws.run_task）逐任务授权面（信任用户免卡直执；非信任用户进不了域、
         * 到不了本面；null = 本链不装——工厂返 null 不注册，fail-closed）。
         */
        val wsRunTaskGate: WsRunTaskTools.WsRunTaskGate? = null,
        /**
         * git 只读（git.read）域准入面（null = 本链不装——工厂返 null 不注册，fail-closed；
         * 与 ws.* 共用同一信任名单判定）。
         */
        val gitSessionGate: WsSessionGate? = null,
        /**
         * ws.run_task 行旁路（长任务进度喂入——装配链注入 touch + 尾部行进窗；
         * null = 不旁路，仅测试形态；非 null 但 wsRunTaskGate 缺席时工具不注册，
         * 本缝无消费点）。
         */
        val runTaskTap: ((String) -> Unit)? = null,
    )

    /** 工具描述符：name + 风险 + 工厂 + 命令投影。工厂返回 null = 本次装配不注册该工具。 */
    data class AgentToolDescriptor(
        val name: String,
        val description: String,
        val risk: Risk,
        val aliases: Set<String> = emptySet(),
        val uiHint: UiHint? = null,
        val params: List<ParamSpec> = emptyList(),
        val factory: (
            context: ToolExecutionContext,
            sink: ToolResultSink,
            config: ToolContextConfig,
        ) -> Tool<*, String>?,
    )

    // ── 蒸馏域描述符（P4-B；buffer 来自 ToolContextConfig.distillBuffer——蒸馏链会话注入。
    //    不入任何 base names：names 选择集显式点名才注册——桥接/桌面旧链恒不装） ──

    private val DISTILL_REPORT_STEP: AgentToolDescriptor = AgentToolDescriptor(
        name = "distill.report_step",
        description = "Report distillation progress (stage/file/cumulative hint) to the run panel",
        risk = Risk.READ_ONLY,
        aliases = emptySet(),
        factory = { _, sink, _ ->
            DistillTools.ReportStepTool(sink)
        },
    )

    private val DISTILL_PROPOSE_KNOWLEDGE: AgentToolDescriptor = AgentToolDescriptor(
        name = "distill.propose_knowledge",
        description = "Submit a knowledge candidate to the session review buffer (never writes DB directly)",
        risk = Risk.READ_ONLY,
        aliases = emptySet(),
        factory = { _, sink, config ->
            config.distillBuffer?.let { DistillTools.ProposeKnowledgeTool(it, sink) }
        },
    )

    // ── 记忆域描述符（memoryDir 来自 ToolContextConfig.memoryDir——宿主注入数据根） ──

    private val VIEW_MEMORY: AgentToolDescriptor = AgentToolDescriptor(
        name = "memory.view",
        description = "Read long-term memory index or a specific memory file",
        risk = Risk.READ_ONLY,
        aliases = setOf(MemoryTools.ViewMemoryTool.NAME),
        factory = { _, sink, config ->
            config.memoryDir?.let { MemoryTools.ViewMemoryTool(it, sink) }
        },
    )

    private val APPEND_MEMORY: AgentToolDescriptor = AgentToolDescriptor(
        name = "memory.append",
        description = "Write long-term memory (create or append a topic file, register in index)",
        risk = Risk.READ_ONLY,
        aliases = setOf(MemoryTools.AppendMemoryTool.NAME),
        factory = { _, sink, config ->
            config.memoryDir?.let { MemoryTools.AppendMemoryTool(it, sink) }
        },
    )

    private val REPLACE_MEMORY: AgentToolDescriptor = AgentToolDescriptor(
        name = "memory.replace",
        description = "Fix long-term memory by exact text replacement (contradiction-driven rewrite)",
        risk = Risk.READ_ONLY,
        aliases = setOf(MemoryTools.ReplaceMemoryTool.NAME),
        factory = { _, sink, config ->
            config.memoryDir?.let { MemoryTools.ReplaceMemoryTool(it, sink) }
        },
    )

    private val DELETE_MEMORY: AgentToolDescriptor = AgentToolDescriptor(
        name = "memory.delete",
        description = "Delete a long-term memory file and its index line",
        risk = Risk.DESTRUCTIVE,
        aliases = setOf(MemoryTools.DeleteMemoryTool.NAME),
        factory = { _, sink, config ->
            config.memoryDir?.let { MemoryTools.DeleteMemoryTool(it, sink) }
        },
    )

    // ── HITL 域描述符（Quest 域 change 1；gate 来自 ToolContextConfig.userQuestionGate。
    //    不入任何 base names：names 选择集显式点名才注册——桥接/工作台/蒸馏/语音
    //    旧链恒不装；桌面流程页（QuestSession，change 2 落地）点名装配） ──

    private val ASK_USER: AgentToolDescriptor = AgentToolDescriptor(
        name = "user.ask_user",
        description = "Ask the user a question to gather missing information (batch fields in one call)",
        risk = Risk.READ_ONLY,
        aliases = setOf(AskUserTool.NAME),
        uiHint = UiHint(title = "向用户提问", keywords = listOf("ask", "question"), description = "agent 主动向用户索要缺失信息"),
        // params 只声明 question 单参（纪律断言面）；fields 是 List<FieldArg> 嵌套结构，
        // ParamSpec 四型表达不了 MULTI_CHOICE 列表——投影裁掉（palette 不汇入，
        // 见 ParamCommandCatalog.PROJECTION_EXCLUDED），会话内 HITL 形态照常。
        params = listOf(
            com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec(
                id = "question",
                label = "问题",
                type = com.jiaoay.easy_dev_toolbox.core.navigation.ParamType.TEXT,
                placeholder = "要向用户提出的问题",
            ),
        ),
        factory = { _, sink, config ->
            config.userQuestionGate?.let { AskUserTool(it, sink) }
        },
    )

    // ── 目录（全仓唯一注册面；新工具加一行 = 三处可用：桌面链/桥接链/未来 MCP 外放） ──

    private val catalog: List<AgentToolDescriptor> = buildList {
        // log.* —— 桥接五件下沉（LogTaskTools.kt 实现；桌面链 extended 形态同样可用）
        add(LogTaskTools.DECRYPT_LOG)
        add(LogTaskTools.FILTER_BY_TAG)
        add(LogTaskTools.FILTER_BY_TIME)
        add(LogTaskTools.FILTER_BY_RANGE)
        add(LogTaskTools.SPLIT_BY_SIZE)
        add(LogTaskTools.PROBE_FILE)

        // log.analyze_log —— P4-A 确定性管线（解密→时间过滤→环境扫描→Tag 过滤；
        // 中间产物不穿模型；需要私钥提供者，桌面/桥接两链通用）
        add(ANALYZE_LOG)

        // log.stats / log.diff / log.view / log.cluster —— 侦察四件（READ_ONLY 零产物；
        // 「统计→下钻→引用」：stats 看分布定过滤策略，diff 对比两份日志找行为差异，
        // view 按行号引用原文，cluster 把同型异常归并成 top-N 模板摘要定排查优先级；
        // 输出有界化见其对象 KDoc）
        add(LogStatsDiffTools.LOG_STATS)
        add(LogStatsDiffTools.LOG_DIFF)
        add(LogStatsDiffTools.LOG_VIEW)
        add(LogStatsDiffTools.LOG_CLUSTER)

        // fs.* —— 本地文件四件
        add(DecryptTools.DECRYPT_FILE)
        add(DecryptTools.EXTRACT_ARCHIVE)
        add(DecryptTools.PREVIEW_TEXT)
        add(DecryptTools.LIST_DIR)

        // fs.extended.* —— 扩展件（大文件分析/取证；两链 extended=true 时并入）
        add(FileTools.READ_FILE)
        add(FileTools.GREP)
        add(FileTools.GLOB)
        add(ForensicTools.HASH_FILE)
        add(ForensicTools.HEX_DUMP)
        add(ForensicTools.STRINGS)
        add(CONVERT_TIMESTAMP)

        // memory.* —— L2 语义记忆四件（P3-B；目录经 config.memoryDir 注入，null 不注册）
        add(VIEW_MEMORY)
        add(APPEND_MEMORY)
        add(REPLACE_MEMORY)
        add(DELETE_MEMORY)

        // knowledge.* —— 领域知识库四件（P3-D；装配门控 = 知识库有内容——factory 内判
        // hasContent()，空库返回 null 不注册，无需新增 config 槽；search_cases 单独
        // 收窄门控 hasCases()（samples > 0 单项判——空样本库不注册恒 NOT_FOUND 件）
        add(KnowledgeTools.SEARCH_GOLDEN_SAMPLES)
        add(KnowledgeTools.SEARCH_KNOWLEDGE_DOCS)
        add(KnowledgeTools.READ_KNOWLEDGE_DOC)
        add(KnowledgeTools.LOOKUP_TAG)

        // web.fetch —— 网络查询件（READ_ONLY 零文件 I/O：白名单域名 GET → 纯文本；
        // 域名白名单编译期常量单源见 WebTools.ALLOWED_HOSTS）
        add(WebTools.WEB_FETCH)

        // distill.* —— 蒸馏域两件（P4-B；不入任何 base names：选择集显式点名才装——
        // propose 的装配门控 = ToolContextConfig.distillBuffer 非 null，桥接/桌面旧链恒不装）
        add(DISTILL_REPORT_STEP)
        add(DISTILL_PROPOSE_KNOWLEDGE)

        // user.ask_user —— HITL 提问件（Quest 域 change 1；不入任何 base names：
        // 点名装配；门控 = ToolContextConfig.userQuestionGate 非 null）
        add(ASK_USER)

        // sys.probe —— 只读系统诊断件（白名单命令目录：LLM 只能选不能造；执行经
        // core/system/ArgvProcessRunner argv 纯语义原语）。装配门控 =
        // ToolContextConfig.sysSessionGate 非 null（工厂返 null 不注册，fail-closed）；
        // 桥接链条件注册三条件见 BridgeToolRegistry，恒不入任何 base names
        add(SysProbeTools.SYS_PROBE)

        // sys.read_config —— 窄路径只读配置族件（编译期路径族 + 围栏双闸 + 输出脱敏
        // + 256KB 单文件上界；清单族走 manifest 摘要形态）。装配门控同 sys.probe
        // （sysSessionGate 非 null 才注册），恒不入任何 base names
        add(SysReadConfigTools.SYS_READ_CONFIG)

        // sys.repair —— 白名单修复动作件（编译期动作目录：固定 argv + 逐动作回滚配方 +
        // 逐动作授权面；risk=DESTRUCTIVE——写系统状态面）。装配门控 = sysRepairGate 非 null
        // （工厂返 null 不注册，fail-closed），信任用户免卡直执，恒不入任何 base names
        add(SysRepairTools.SYS_REPAIR)

        // sys.write_config —— 纯数据配置写件（唯一写族 fcitx5_config；写前 .bak 快照 +
        // 完整 diff 授权面 + diff 超展示预算拒执；risk=DESTRUCTIVE——写盘面）。
        // 装配门控 = sysWriteGate 非 null（工厂返 null 不注册，fail-closed），信任用户
        // 免卡直执，恒不入任何 base names
        add(SysWriteTools.SYS_WRITE_CONFIG)

        // sys.service —— 用户级服务动作件（systemctl 恒带 --user + 七服务白名单 + 动作枚举
        // restart/start/stop + 逐动作回滚配方；桌面会话级单元结构性排除；risk=DESTRUCTIVE）。
        // 装配门控 = sysServiceGate 非 null（工厂返 null 不注册，fail-closed），恒不入任何 base names
        add(SysServiceTools.SYS_SERVICE)

        // sys.verify —— 验证回环件（修复后复跑对应只读探查对比前后快照，三态判定：
        // 生效/未生效/无法判定；复跑仍未生效降级外委）。装配门控 = sysSessionGate 非 null
        // （sys.* 域准入面），恒不入任何 base names
        add(SysVerifyTools.SYS_VERIFY)

        // sys.handoff —— 命令外委件（超出白名单面的 root/会话级动作 → 结构化命令清单交
        // 用户本人执行；零执行面、executed 恒 false）。装配门控 = sysSessionGate 非 null
        // （仅审计 trigger 归因，不消费授权），恒不入任何 base names
        add(SysHandoffTools.SYS_HANDOFF)

        // dev.devices —— 只读设备清单件（agent 设备域首件；设备检测单源：串号+型号，
        // serial 原样回显供 dev.snaplog 白名单 round-trip）。装配门控 = devSessionGate +
        // devDeviceSource 双非 null（工厂返 null 不注册，fail-closed），恒不入任何 base names
        add(DevTools.DEV_DEVICES)

        // dev.snaplog —— 设备日志快照件（固定 argv 模板 adb -s <serial> logcat -d -t <N>；
        // serial 走当前检测列表白名单，行数钳制 10..5000；产物落数据根 devlogs 并登记进
        // 文件围栏供 log.* 分析链消费）。装配门控同 dev.devices，恒不入任何 base names
        add(DevTools.DEV_SNAPLOG)

        // self.status —— 自观测件（桥接自身运行健康的只读聚合快照：连接态/重连计数/
        // 流水线 12 行计数/配置校验进程内摘要/sys-audit 聚合计数——全部进程内只读，
        // 零文件 I/O 零子进程；审计只报聚合计数绝不落记录原文）。装配门控 =
        // ToolContextConfig.selfStatusSource 非 null（工厂返 null 不注册，fail-closed）；
        // 配置域总开关与桥接接线三条件核对在桥接装配侧，恒不入任何 base names
        add(SelfStatusTools.SELF_STATUS)

        // ws.write_file —— 工作区写文件件（工作区根内相对路径写 + 逐写授权面 +
        // .bak 快照回滚 + diff 超展示预算拒执；risk=DESTRUCTIVE——写盘面）。
        // 装配门控 = wsWriteGate 非 null（工厂返 null 不注册，fail-closed），信任用户
        // 免卡直执，恒不入任何 base names
        add(WsWriteTools.WS_WRITE_FILE)

        // ws.run_task —— 受限任务执行件（编译期配方目录 gradle/maven/npm/pnpm；cwd 恒 =
        // 工作区根；逐任务授权面 + 长任务行旁路喂看门狗；risk=DESTRUCTIVE——执行面）。
        // 装配门控 = wsRunTaskGate 非 null（工厂返 null 不注册，fail-closed），恒不入任何 base names
        add(WsRunTaskTools.WS_RUN_TASK)

        // git.read —— git 只读件（log/diff/blame/status 枚举 + 恒 --no-pager + 工作区根
        // canonical 包含性 + diff/blame 路径过围栏；risk=READ_ONLY——零副作用无逐次卡）。
        // 装配门控 = gitSessionGate 非 null（工厂返 null 不注册，fail-closed），恒不入任何 base names
        add(GitReadTools.GIT_READ)
    }

    /** 目录全量（只读视图；测试/审计用） */
    val all: List<AgentToolDescriptor> get() = catalog

    fun buildRegistry(
        selector: (AgentToolDescriptor) -> Boolean,
        context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        config: ToolContextConfig = ToolContextConfig(),
        middlewares: List<AgentToolMiddleware> = emptyList(),
    ): ToolRegistry = decorateRegistry(
        ToolRegistry {
            catalog.filter(selector).forEach { descriptor ->
                descriptor.factory(context, sink, config)?.let { tool(it) }
            }
        },
        middlewares,
    )

    /** 按名字精装（单测/CLI 场景） */
    fun buildRegistry(
        names: Set<String>,
        context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        config: ToolContextConfig = ToolContextConfig(),
        middlewares: List<AgentToolMiddleware> = emptyList(),
    ): ToolRegistry = buildRegistry({ it.name in names }, context, sink, config, middlewares)

    /** 用别名查实名（桥接旧名 → 目录名；P5 MCP 外放前的兼容缝） */
    fun resolve(alias: String): String? =
        catalog.firstOrNull { it.name == alias || alias in it.aliases }?.name
}
