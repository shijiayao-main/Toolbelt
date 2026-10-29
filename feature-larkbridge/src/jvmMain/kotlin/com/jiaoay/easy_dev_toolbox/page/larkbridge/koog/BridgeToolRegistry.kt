package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.agents.core.tools.ToolRegistry
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.lark.bridge.agent.AgentRequest

/**
 * 桥接出站工具集（SimpleTool 构造注入闭包——每任务新造 ToolRegistry，DecryptAgent 单任务单实例先例）。
 * 工具闭包经 [AgentRequest.opaque] 拿到本消息的 [com.jiaoay.lark.bridge.pipeline.ReplySink]（handler 装配时塞入）。
 *
 * 🔴 chatId/senderOpenId 经 5 参 run 参数链贯穿——会话作用域工具（表情/引用取回/撤回/
 *    L2 写确认卡）据此钉「当前会话」（2 参调用传空串时这些工具按能力缺失处理不炸）。
 */
internal object BridgeToolRegistry {

    /**
     * 能力清单卡的静态工具名面：**不触 [build]**——build 需要 request 等运行时
     * 参数（构建的是本次消息的运行时注册表），UI 展示与运行时注册解耦；运行时清单仍由 [build] 决定。
     *
     * 清单 = 纯聊天消息下恒注册的 11 个出站/会话/联网查询工具（与 build 的注册分支逐名核对——
     * name 字符串改一处必须同步另一处，由 KoogAgentRunnerTest 的名称一致性断言钉死）。
     * 清单与 build 的注册分支逐名对齐（含 L1 三件 send_image /
     * recall_own_message / reply_in_thread）——L1 定义即恒注册（当前会话内原生操作，无配置面）。
     * - search_knowledge 依赖知识库有内容——属「条件可用」，能力卡按「常备能力」列出
     *   （条件性在 UI 上不区分，提示词里才精确门控）。
     * - 文件类工具（probe_file/decrypt_log/grep 等约 18 项）按消息引用文件动态注册
     *   （DispatchPayload.allowedFiles 非空才装配），**UI 不列**（无文件上下文时它们不存在）。
     * - L2 五工具（fetch_chat_history 等）条件注册——nativeSurface 接缝在位即注册
     *   （`agent.nativeTools` 双闸配置段已退役，授权闸收敛为飞书后台 scope 单一闸），
     *   **恒不进本清单**（接缝缺席即不注册，不进常备名单）。
     * - remind_user / cancel_reminder（会话内提醒与撤销两件）同属条件注册——宿主注入 reminderScheduler
     *   （App 形态网关 scope）才在册，CLI 形态/存量测试 null 不注册，恒不进本清单。
     * - memory 四件（长期记忆）同属条件注册——dataRoot 接线 + chatId 非空才装配（派生见
     *   [chatMemoryDir]），恒不进本清单。
     * - sys.* 七件 / dev.* 两件（本机诊断与设备观测）同属条件注册——域准入面
     *   （trustedUsersProvider 注入——按发送者 openId 判定信任名单）与装配接缝在位（缺一不注册），
     *   恒不进本清单；dev.* 在册时纯聊天消息会连带装配快照消费链（侦察四件 + log.filter_by_tag，
     *   见 build 的 dev 条件块）——同属条件注册面，不进本清单。
     * - ws.* 三件 + git.read（工作区域）同属条件注册——域准入面（trustedUsersProvider 注入 +
     *   confirmGate/chatId/senderOpenId，三域共用同一信任名单）与工作区根条件注册，
     *   恒不进本清单。
     * - user.ask_user（HITL 提问）同属条件注册——askBus 接线 + chatId/senderOpenId 非空
     *   才装配（纯交互零危险面不设配置开关），恒不进本清单。
     * - self.status（自观测）同属条件注册——nativeConfig 接线 + agent.selfStatus 开关 +
     *   状态源缝在位三条件（缺一不注册；零授权面故无域准入条件），恒不进本清单。
     * - web.fetch（联网查询）恒注册——与 remind_user/memory/L2 的条件注册不同：零注入依赖，
     *   白名单域名/私网段拒绝/重定向逐跳重校验三重防线全在 WebTools 工具本体，与本消息
     *   文件围栏无关（纯聊天主场景：查错误码/官方文档/AOSP 源码）。
     */
    val ALWAYS_REGISTERED_TOOLS: List<String> = listOf(
        "reply_direct",
        "send_file",
        "send_card",
        "send_image",
        "react_emoji",
        "fetch_quoted_context",
        "update_own_card",
        "recall_own_message",
        "reply_in_thread",
        "search_knowledge",
        "web.fetch",
    )

    /**
     * memory 四件目录名（名集自持同值——:core 的记忆名集是 internal 跨模块不可见，
     * 目录名是稳定契约；桌面工作台/Quest 链同款先例）。
     */
    private val MEMORY_NAMES = setOf("memory.view", "memory.append", "memory.replace", "memory.delete")

    /** chatId 目录段长度上限（防文件名超长；飞书 chatId 为 oc_/ww_ 前缀短字母数字串，上限仅防御异常输入）。 */
    private const val CHAT_ID_DIR_MAX_CHARS = 64

    /**
     * per-chat 长期记忆目录派生（纯函数——不触文件系统；目录由 append_memory 首写时惰性创建，
     * 只读/未用 memory 的会话不落任何目录）。
     *
     * 多用户隔离硬前提（违反即跨用户泄漏）：桥接是白名单多人共用面，记忆目录必须按
     * chatId 派生（`<dataRoot>/lark/memory/<chatId>/`）——共享单一目录会让任一白名单
     * 成员读到/覆盖其他成员的聊天背景；本函数是桥接链 memoryDir 的唯一派生点。
     *
     * 契约：
     * - 返回 null = 本次不装配 memory 四件（dataRoot 未接线或 chatId 为空——2 参入口/
     *   存量测试形态，fail-closed）；
     * - chatId 清洗：非 ASCII 字母数字/下划线/连字符的字符折叠为 `_`（`..`、路径分隔符等
     *   全部折叠，杜绝路径注入），超 [CHAT_ID_DIR_MAX_CHARS] 截断；清洗不丢弃字符
     * （保形防碰撞：`a/b` 与 `ab` 折叠后仍可区分）；
     * - 「新对话」不清记忆：记忆绑定飞书聊天关系（chatId）而非单次 agent run——
     *   [KoogAgentRunner.resetSession] 清当轮 Koog 会话历史
     *   会话，记忆不在重置面、跨 run 长存（记忆的价值正是用户下次发消息时 agent 仍
     *   记得既有背景）；桌面链 memory 绑定日志分析 runs，
     *   与本链是两个装配面，互不牵连；
     * - 不设目录数上界/自动驱逐：记忆目录是持久用户数据，自动删除不可逆；桥接按白名单
     *   会话收口，目录数天然以白名单会话数为上界（[SessionHistoryProvider] LRU
     *   「只逐内存不逐持久层」同款取舍）。
     */
    fun chatMemoryDir(dataRoot: java.io.File?, chatId: String): java.io.File? {
        if (dataRoot == null || chatId.isBlank()) return null
        val sanitized = buildString {
            for (c in chatId) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_')
        }.take(CHAT_ID_DIR_MAX_CHARS)
        val memoryRoot = java.io.File(java.io.File(dataRoot, "lark"), "memory")
        return java.io.File(memoryRoot, sanitized)
    }

    /**
     * 🔴 opaque 载体统一解析：出站工具读 [AgentRequest.opaque] 必须经本函数。
     *
     * 背景：有引用文件的消息 opaque 是 [com.jiaoay.lark.bridge.pipeline.DispatchPayload]（包装器形态），而
     * reply_direct/send_file/send_card 三工具若直接写裸
     * `opaque as? ReplySink`——DispatchPayload **是包装器不是 ReplySink**，`as?` 恒 null ⇒
     * 三工具在有文件上下文的分析任务上**全部返回 DENIED_HINT**（agent 自救改文字交付）。
     * 🔴 新增出站工具一律经本函数或 [payloadOf] 取载体，勿再裸 cast。
     */
    fun replySinkOf(request: AgentRequest): com.jiaoay.lark.bridge.pipeline.ReplySink? {
        val opaque = request.opaque
        return when (opaque) {
            is com.jiaoay.lark.bridge.pipeline.DispatchPayload -> opaque.reply
            is com.jiaoay.lark.bridge.pipeline.ReplySink -> opaque
            else -> null
        }
    }

    /**
     * DispatchPayload 载体解析——会话作用域工具（react_emoji /
     * update_own_card / fetch_quoted_context）经它取 messageId/chatId/引用链/账本引用。
     * 存量测试裸 ReplySink 形态返回 null（会话作用域工具按能力缺失处理，不炸）。
     */
    fun payloadOf(request: AgentRequest): com.jiaoay.lark.bridge.pipeline.DispatchPayload? =
        request.opaque as? com.jiaoay.lark.bridge.pipeline.DispatchPayload

    fun build(
        endpointConfig: LlmEndpointConfig,
        request: AgentRequest,
        chatId: String = "",
        senderOpenId: String = "",
        /** 交付沙箱上界（[KoogAgentRunner.sandboxProvider] 现值；null = 不设上界，回落精确允许集）。 */
        sandbox: com.jiaoay.lark.bridge.exec.WorkspacePolicy? = null,
        /** 产物回传总开关（[KoogAgentRunner.artifactsEnabledProvider] 现值；false ⇒ send_file/send_image 一律拒 + 委托 prompt 与 AGENTS.md 不注入 [产物] 约定段）。 */
        artifactsEnabled: Boolean = true,
        /**
         * 桥接数据根（敏感文件判据 1 + memory 目录派生根）。
         * null = 未接线（CLI 形态/存量测试）⇒ 跳过该判据且 memory 四件不注册（fail-closed）。
         */
        homeDataRoot: java.io.File? = null,
        /**
         * L2 原生工具配置（[KoogAgentRunner.nativeToolsProvider] 现值）。
         * null = 未接线（CLI 形态/存量测试）⇒ L2 五工具全不注册（fail-closed 三条件之一）。
         */
        nativeConfig: com.jiaoay.lark.bridge.config.BridgeConfig? = null,
        /**
         * 会话内提醒 job 面（[KoogAgentRunner.reminderSchedulerProvider] 现值）。
         * null = 未注入（CLI 形态/存量测试）⇒ remind_user / cancel_reminder 不注册
         * （条件注册，同 L2 口径不进常备清单）。
         */
        reminderScheduler: ReminderScheduler? = null,
        /**
         * 信任用户名单提供者（`access.trustedUsers` 现读）——sys.* / dev.* / ws.* 三域共用的
         * 唯一授权判据。null = 未接线（CLI 形态/存量测试）⇒ 三域全件不注册（fail-closed）。
         */
        trustedUsersProvider: (() -> List<String>)? = null,
        /**
         * dev.* 设备源缝（设备检测单源适配，[AppDeviceSource]）。
         * null = 未注入（CLI 形态/存量测试）⇒ dev.* 全件不注册（fail-closed 三条件之一）。
         */
        devDeviceSource: com.jiaoay.easy_dev_toolbox.core.llm.tools.DevTools.DeviceSource? = null,
        /**
         * self.status 状态源缝（桥接内部健康数据面适配，[AppSelfStatusSource]）。
         * null = 未注入（CLI 形态/存量测试/桌面链缺省）⇒ self.status 不注册（fail-closed
         * 三条件之一）。
         */
        selfStatusSource: com.jiaoay.easy_dev_toolbox.core.llm.tools.SelfStatusTools.SelfStatusSource? = null,
        /**
         * user.ask_user 挂起配对总线（宿主稳定件透传）。null = 未接线（CLI 形态/存量测试）
         * ⇒ ask_user 不注册（fail-closed——挂起等一个无配对面的回答是死等）。
         */
        askUserBus: com.jiaoay.lark.bridge.confirm.AskUserBus? = null,
        /**
         * 当次任务整体预算供体（ws.run_task 软超时 0.8 系数与 ask_user AskCaps 换算的
         * 共同输入）。缺省 { 0L } = 未接线（测试窄面）——AskCaps 对 0 回下限档。
         */
        overallTimeoutMillisProvider: () -> Long = { 0L },
        /**
         * ws.run_task 行旁路（长任务进度喂入：touch + 尾部行进进度卡；null = 不旁路）。
         */
        runTaskTap: ((String) -> Unit)? = null,
    ): ToolRegistry {
        // opaque 载体分流——DispatchPayload（有引用文件）带围栏允许集与
        // 桥接私钥；裸 ReplySink（纯聊天）无文件面，文件工具不注册（省 token，LLM 不会幻觉
        // 调用不存在的工具）。
        val payload = request.opaque as? com.jiaoay.lark.bridge.pipeline.DispatchPayload
        val fileContext = payload?.takeIf { it.allowedFiles.isNotEmpty() }
            ?.let { com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(it.allowedFiles) }
        return ToolRegistry {
            tool(ReplyDirectTool(request))
            tool(SendFileTool(request, fileContext, sandbox, artifactsEnabled, homeDataRoot))
            tool(SendCardTool(request))
            // L1 三件恒注册（L1 定义即恒注册，无配置面）：
            //    send_image 与 send_file 同一套围栏判据（同参注入）；
            //    recall_own_message / reply_in_thread 走 payloadOf 会话作用域校验。
            tool(SendImageTool(request, fileContext, sandbox, artifactsEnabled, homeDataRoot))
            tool(RecallOwnMessageTool(request))
            tool(ReplyInThreadTool(request))
            // 会话作用域三件恒注册（纯聊天恰是主场景——
            //    载体已恒包 DispatchPayload，不再有裸快路径）。工具内部经 payloadOf 取
            //    会话作用域四件；存量测试裸 ReplySink 形态 ⇒ 工具返回能力缺失提示不炸。
            tool(ReactEmojiTool(request))
            tool(FetchQuotedContextTool(request))
            tool(UpdateOwnCardTool(request))
            // 会话内提醒两件（条件注册——宿主注入 scheduler 才在册；CLI 形态/存量测试 null 不注册，
            // 不进 ALWAYS_REGISTERED_TOOLS，与 L2 条件注册同口径）。撤销与设定同一 scheduler
            // 注入门控（拆开会让「回执带编号却无工具可撤」的能力面错位）。
            if (reminderScheduler != null) {
                tool(RemindUserTool(request, reminderScheduler))
                tool(CancelReminderTool(request, reminderScheduler))
            }
            // 🔴 L2 五工具**条件注册**（授权闸收敛后只剩接缝一道条件）：
            //    payload.nativeSurface 非空（每消息接缝在位——由 runAgentPipeline 装配点现造）。
            //    `agent.nativeTools` 双闸配置段已退役——L2 能力恒注册，授权闸收敛为飞书后台
            //    scope 单一闸（scope 未开通时工具回落 scope 引导文案，fail-closed 由工具面承接）。
            //    不进 ALWAYS_REGISTERED_TOOLS（接缝缺席即不注册，KDoc 口径）。
            if (payload?.nativeSurface != null) {
                tool(FetchChatHistoryTool(request))
                tool(GetChatInfoTool(request))
                tool(
                    CreateGroupChatTool(
                        request,
                        payload.confirmGate,
                        isTrustedSender = {
                            trustedUsersProvider?.let { p ->
                                com.jiaoay.lark.bridge.confirm.TrustPolicy.isTrusted(
                                    com.jiaoay.lark.bridge.config.AccessConfig(trustedUsers = p()),
                                    senderOpenId,
                                )
                            } ?: false
                        },
                    ),
                )
                tool(LookupUserTool(request))
                tool(ReadWikiDocumentTool(request))
            }
            // 长期记忆四件（条件注册：dataRoot 接线 + chatId 非空才装配，同 remind_user/L2
            // 口径不进 ALWAYS_REGISTERED_TOOLS）。memory 自带 memoryDir 围栏（fileName 正则 +
            // isInside canonical 包含防御），不进本消息文件围栏族——纯聊天消息（主场景：
            // 用户交代跨会话背景）同样装配；装配与有无引用文件无关。
            val memoryDir = chatMemoryDir(homeDataRoot, chatId)
            if (memoryDir != null) {
                tools(com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.buildRegistry(
                    names = MEMORY_NAMES,
                    // memory 工具不读文件围栏（自带 memoryDir 围栏）——无引用文件时传空允许集
                    // 仅为满足 buildRegistry 形参，不构成任何放行面。
                    context = fileContext
                        ?: com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(emptyList()),
                    config = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.ToolContextConfig(
                        memoryDir = memoryDir,
                    ),
                ).tools)
            }
            // web.fetch（恒注册——纯聊天「查错误码/查官方文档」主场景与文件消息同在册）。
            //    白名单域名/私网段拒绝/重定向逐跳重校验三重防线全在 WebTools 工具本体，
            //    不读本消息文件围栏——无引用文件时传空允许集仅为满足 buildRegistry 形参，
            //    不构成任何放行面（memory 四件同款契约）。
            tools(com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.buildRegistry(
                names = com.jiaoay.easy_dev_toolbox.core.llm.tools.WebTools.WEB_NAMES,
                context = fileContext
                    ?: com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(emptyList()),
            ).tools)
            // sys.* 七件（条件注册）——缺一不注册，fail-closed（缺闸放行即
            //    L0 非凭据资料裸奔 / 写面无卡，属装配缺陷）：
            //    ① 域准入面在位（trustedUsersProvider 注入——按发送者 openId 判定信任名单，
            //       名单外进不了域；无发卡面）；
            //    ② 卡面与审计归因面在位（confirmGate + chatId + senderOpenId 非空）。
            //    族根放行严禁借道 allowedRoots（命中即免 gate 直接放行 + 出站旁路）。
            //    注册面已无配置闸（v13→v14 退役）：接缝在位即全量注册。
            val sysConfirmGate = payload?.confirmGate
            if (trustedUsersProvider != null && sysConfirmGate != null &&
                chatId.isNotBlank() && senderOpenId.isNotBlank()
            ) {
                val sysNames = setOf(
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.SysProbeTools.PROBE_NAME,
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.SysReadConfigTools.READ_CONFIG_NAME,
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.SysRepairTools.REPAIR_NAME,
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.SysWriteTools.WRITE_CONFIG_NAME,
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.SysServiceTools.SERVICE_NAME,
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.SysVerifyTools.VERIFY_NAME,
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.SysHandoffTools.HANDOFF_NAME,
                )
                run {
                    // sys 专用 context：族内相对路径经 SysFamilyEscapeGate HITL 缝放行；
                    // 不与本消息文件围栏共用 context（把族根挂进文件围栏会让 fs.*/log.*
                    // 读类工具也读到族内路径——围栏面被 sys 授权意外放宽）。
                    val sysContext = com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(
                        escapeGate = SysFamilyEscapeGate(),
                    )
                    val sysGate = KoogSysSessionGate(
                        trustedUsersProvider = trustedUsersProvider,
                        senderOpenId = senderOpenId,
                        sysContext = sysContext,
                    )
                    // 修复/写入/服务三个逐动作面：信任用户免卡直执、非信任用户进不了域
                    // （两卡路径仅装配兜底——名单在两次判定间变化时仍走说明卡 + 确认卡）。
                    val sysRepairGate = KoogSysRepairGate(
                        sessionGate = sysGate,
                        confirmGate = sysConfirmGate,
                        reply = replySinkOf(request),
                    )
                    val sysWriteGate = KoogSysWriteGate(
                        sessionGate = sysGate,
                        confirmGate = sysConfirmGate,
                        reply = replySinkOf(request),
                    )
                    val sysServiceGate = KoogSysServiceGate(
                        sessionGate = sysGate,
                        confirmGate = sysConfirmGate,
                        reply = replySinkOf(request),
                    )
                    tools(com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.buildRegistry(
                        names = sysNames,
                        context = sysContext,
                        config = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.ToolContextConfig(
                            sysSessionGate = sysGate,
                            sysRepairGate = sysRepairGate,
                            sysWriteGate = sysWriteGate,
                            sysServiceGate = sysServiceGate,
                        ),
                    ).tools)
                }
            }
            // dev.* 两件（条件注册）——缺一不注册，fail-closed（缺闸放行即设备
            //    读取面裸奔，属装配缺陷）。域边界负面清单（设备侧安装/卸载/push/截屏/
            //    隐私面/输入注入/无线配对/root 永不入域）见 koog 开发域边界定案（本 KDoc 自足，不再外指s 的
            //    koog-dev-domain-boundary 终稿；不进 ALWAYS_REGISTERED_TOOLS（恒不进
            //    常备清单——条件注册，sys/L2/memory 同口径）：
            //    ① 域准入面在位（trustedUsersProvider 注入——按发送者 openId 判定信任名单，
            //       名单外进不了域；无发卡面）；
            //    ② 装配面在位（confirmGate + chatId + senderOpenId 非空 + 设备源注入）。
            //    三域共用同一信任名单，不存在按域独立授权档。
            //    注册面已无配置闸（v13→v14 退役）：接缝在位即全量注册。
            if (trustedUsersProvider != null && devDeviceSource != null &&
                sysConfirmGate != null && chatId.isNotBlank() && senderOpenId.isNotBlank()
            ) {
                val devNames = setOf(
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.DevTools.DEVICES_NAME,
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.DevTools.SNAPLOG_NAME,
                )
                run {
                    // dev 与 log.* 分析链共用同一围栏 context 实例：dev.snaplog 产物
                    // register 进本实例后 log.stats/log.cluster/log.view/log.filter_by_tag
                    // 直接消费（快照即入既有分析链）。有引用文件的消息复用本消息围栏；
                    // 纯聊天自建空围栏（初始允许集为空——dev 产物登记前分析链读不到任何路径）。
                    val devContext = fileContext
                        ?: com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(emptyList())
                    val devGate = KoogDevSessionGate(
                        trustedUsersProvider = trustedUsersProvider,
                        senderOpenId = senderOpenId,
                    )
                    tools(com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.buildRegistry(
                        names = devNames,
                        context = devContext,
                        config = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.ToolContextConfig(
                            devSessionGate = devGate,
                            devDeviceSource = devDeviceSource,
                        ),
                    ).tools)
                    if (fileContext == null) {
                        // 纯聊天（dev.snaplog 主场景）：log.* 分析件不随消息文件注册——
                        // dev 在册时把快照消费链（侦察四件 + Tag 过滤）挂上同一围栏；
                        // OutputCap 兜底驱逐同款（驱逐文件落数据根 evicted，登记回本围栏）。
                        val devEvictionDir = java.io.File(
                            com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getLarkDir(),
                            "evicted",
                        )
                        tools(BridgeLogTools.buildSnapshotAnalysisRegistry(
                            context = devContext,
                            middlewares = listOf(
                                com.jiaoay.easy_dev_toolbox.core.llm.tools.OutputCapMiddleware(
                                    devContext,
                                    devEvictionDir,
                                ),
                            ),
                        ).tools)
                    }
                }
            }
            if (fileContext != null) {
                // 内部工具集（「内部工具能做的不委托外部 CLI」）——log-tools 直调零子进程。
                // 子注册表经 builder.tools(list) 并入（ToolRegistryBuilder 有 List 形态入口）。
                // extended=true 并入 :core 扩展件（read_file 分页/grep/glob/取证/时间戳）——
                // 大文件分析不被迫走 preview_text 反复试；OutputCap 兜底驱逐挂 middlewares
                // （超长结果落盘 <宿主机密根>/lark/evicted + 取回指针，驱逐文件 register 回围栏）。
                val evictionDir = java.io.File(
                    com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getLarkDir(),
                    "evicted",
                )
                tools(BridgeLogTools.buildRegistry(
                    context = fileContext,
                    privateKeyProvider = { payload.xlogPrivateKey },
                    middlewares = listOf(
                        com.jiaoay.easy_dev_toolbox.core.llm.tools.OutputCapMiddleware(fileContext, evictionDir),
                    ),
                    extended = true,
                ).tools)
            }
            // self.status 单件（条件注册）——三条件缺一不注册，fail-closed（缺闸放行即
            //    自观测面裸奔，属装配缺陷）。不进 ALWAYS_REGISTERED_TOOLS（恒不进常备清单
            //    ——条件注册，sys/dev/L2 同口径）：
            //    ① nativeConfig 接线（宿主注入 nativeToolsProvider——CLI 形态/存量测试恒 null）；
            //    ② 状态源缝在位（App 装配侧注入桥接内部态适配——数据面与状态页同源）。
            //    无域准入条件是结构性口径不是省略：自观测是零授权面（只读聚合、无文件/
            //    设备/进程访问、审计只报聚合计数），防替答卡与信任名单域准入均不适用——
            //    不虚设 sys/dev 的域准入条件。
            if (selfStatusSource != null) {
                tools(com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.buildRegistry(
                    names = com.jiaoay.easy_dev_toolbox.core.llm.tools.SelfStatusTools.STATUS_NAMES,
                    // 零文件面：不读本消息文件围栏——无引用文件时传空允许集仅为满足
                    // buildRegistry 形参，不构成任何放行面（memory/web.fetch 同款契约）。
                    context = fileContext
                        ?: com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(emptyList()),
                    config = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.ToolContextConfig(
                        selfStatusSource = selfStatusSource,
                    ),
                ).tools)
            }
            // ws 域三件 + git.read（条件注册）——缺一不注册，fail-closed（缺闸放行即
            //    工作区读写面裸奔，属装配缺陷）。不进 ALWAYS_REGISTERED_TOOLS（恒不进
            //    常备清单——条件注册，sys/dev/L2 同口径）：
            //    ① 域准入面在位（trustedUsersProvider 注入——按发送者 openId 判定信任名单，
            //       名单外进不了域；无发卡面——ws.run_task 亦然）；
            //    ② 装配面在位（confirmGate + chatId + senderOpenId 非空）；
            //    ③ 工作区根已配置（nativeConfig 的 workspace.root 非空且为目录，任务超时上限
            //       同源——wsRoot 是装配事实不是 LLM 输入）。
            //    三域共用同一信任名单，不存在按域独立授权档。
            //    注册面已无配置闸（v13→v14 退役）：三条件全过即全量注册。
            val wsCfg = nativeConfig?.agent?.wsTools
            if (trustedUsersProvider != null && sysConfirmGate != null &&
                chatId.isNotBlank() && senderOpenId.isNotBlank()
            ) {
                val wsRoot = nativeConfig?.workspace?.root?.takeIf { it.isNotBlank() }
                    ?.let { java.io.File(com.jiaoay.lark.bridge.exec.Paths.expandTilde(it)) }
                    ?.takeIf { it.isDirectory }
                if (wsRoot != null) {
                    val wsNames = setOf(
                        com.jiaoay.easy_dev_toolbox.core.llm.tools.WsWriteTools.WRITE_FILE_NAME,
                        com.jiaoay.easy_dev_toolbox.core.llm.tools.WsRunTaskTools.RUN_TASK_NAME,
                        com.jiaoay.easy_dev_toolbox.core.llm.tools.GitReadTools.GIT_READ_NAME,
                    )
                    run {
                        // ws 专用 context：工作区根内路径经 WsEscapeGate HITL 缝放行；
                        // 不与本消息文件围栏共用 context（工作区根挂进文件围栏会让 fs.*/log.*
                        // 读类工具也读到工作区——围栏面被 ws 授权意外放宽，sys 专用 context 同因）。
                        val wsContext = com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(
                            escapeGate = WsEscapeGate { wsRoot },
                        )
                        val wsGate = KoogWsSessionGate(
                            trustedUsersProvider = trustedUsersProvider,
                            senderOpenId = senderOpenId,
                            wsContext = wsContext,
                            wsRootProvider = { wsRoot },
                            overallTimeoutMillisProvider = overallTimeoutMillisProvider,
                        )
                        val wsWriteGate = KoogWsWriteGate(wsGate, sysConfirmGate, replySinkOf(request))
                        val wsRunTaskGate = KoogWsRunTaskGate(
                            sessionGate = wsGate,
                            confirmGate = sysConfirmGate,
                            reply = replySinkOf(request),
                            configuredTimeoutMinutesProvider = {
                                wsCfg?.runTaskTimeoutMinutes
                                    ?: com.jiaoay.lark.bridge.config.WsToolsConfig().runTaskTimeoutMinutes
                            },
                        )
                        tools(com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.buildRegistry(
                            names = wsNames,
                            context = wsContext,
                            config = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.ToolContextConfig(
                                wsWriteGate = wsWriteGate,
                                wsRunTaskGate = wsRunTaskGate,
                                gitSessionGate = wsGate,
                                runTaskTap = runTaskTap,
                            ),
                        ).tools)
                    }
                }
            }
            // user.ask_user（条件注册——askBus 接线 + chatId/senderOpenId 非空才装配；
            //    纯交互零危险面不设配置开关，与 reply_direct 同级的一级能力；CLI 形态/
            //    存量测试 askBus 缺席不注册 = 天然 fail-closed，恒不进常备清单）。
            if (askUserBus != null && chatId.isNotBlank() && senderOpenId.isNotBlank()) {
                tools(com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.buildRegistry(
                    names = setOf(com.jiaoay.easy_dev_toolbox.core.llm.tools.AskUserTool.NAME),
                    // 零文件面：空允许集仅为满足 buildRegistry 形参，不构成任何放行面
                    // （memory/web.fetch 同款契约）。
                    context = fileContext
                        ?: com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(emptyList()),
                    config = com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.ToolContextConfig(
                        userQuestionGate = KoogUserQuestionGate(
                            askBus = askUserBus,
                            chatId = chatId,
                            senderOpenId = senderOpenId,
                            reply = replySinkOf(request),
                            overallTimeoutMillisProvider = overallTimeoutMillisProvider,
                        ),
                    ),
                ).tools)
            }
            // 知识库检索（手册随 App 分发——hasContent 只查清单资源在位，零网络零构建；
            // 懒构建/降级态都封在 KnowledgeBase 里，工具保持薄壳）
            if (KnowledgeBase.hasContent()) tool(SearchKnowledgeTool())
        }
    }
}
