package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.IOException
import kotlinx.serialization.Serializable

/**
 * 用户级服务动作工具：`sys.service`——systemctl 恒带 --user 的白名单服务动作目录
 * （L2 级），LLM 只能选（服务名 × 动作）组合，不能造命令。
 *
 * ## 服务白名单（编译期锁死）
 * 复用 sys.probe 的 [SysProbeTools.SERVICE_WHITELIST] 并**剔除 gnome-shell**——
 * 桌面会话级单元（重启 gnome-shell 等同重启整个桌面会话，Wayland 下杀掉全部窗口）
 * 属负面清单「桌面会话级动作」，永不入动作目录；其余 7 个用户级服务（输入法/音频/
 * portal）重启只影响本用户会话内的对应功能。加服务 = 改 [SERVICE_WHITELIST] 常量
 * 并同步静态断言面（会话级单元结构性不可入）。
 *
 * ## 动作枚举与回滚配方
 * 只收 restart / start / stop（status 只读已归 sys.probe 的 systemd_user_status）。
 * 每动作登记回滚配方：restart → 再 restart（幂等重试）；stop → start（恢复运行）；
 * start → stop。执行前跑 `systemctl --user is-active <service>` 只读快照捕获原状态，
 * 回滚配方与原状态注记进确认卡、回执与审计（快照失败降级为通用配方，不拒执——
 * 服务动作的回滚不依赖捕获值，与「回滚依赖原值」的 gsettings 类写动作不同档）。
 *
 * ## 授权与徽标（信任用户免卡）
 * 执行前必过 [SysServiceGate.requireSessionAuthorization]（sys.* 域会话档——缺席即
 * DENIED fail-closed）：信任用户（access.trustedUsers 内）直接执行、无确认卡；名单外
 * 用户进不了域，到不了本动作面。目录描述符 risk 同步标 DESTRUCTIVE（权限引擎与外放
 * 门控依据）。
 *
 * ## 执行与回执
 * 经 [SysProbeTools.ProbeExecutor]（sys 家族共用 argv 纯语义执行缝；执行件内黑名单
 * 恒拒 + systemctl 缺 --user 恒拒）；回执恒过 [SysOutputSanitizer] + 8K capAt 收口；
 * 变更动作非零退出码 = 未生效（回执明示），提示用 sys.probe 复查。每次执行（含被拒）
 * 落审计（[SysAuditLog]，含回滚信息，输出全文不落）。
 *
 * ## 装配纪律
 * 工具本体与目录注册即收口三张守护网（围栏覆盖/结构化纪律/目录撞面）；装配门控 =
 * [AgentToolCatalog.ToolContextConfig.sysServiceGate] 非 null（工厂返 null 不注册），
 * 恒不入常备清单。
 */
object SysServiceTools {

    const val SERVICE_NAME = "sys.service"

    /** 回执长度结构性上界（字符）——sys 家族同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** 回滚信息长度上界（字符）：确认卡/回执/审计三面同用 */
    internal const val ROLLBACK_MAX_CHARS = 500

    /**
     * 服务动作白名单：sys.probe 只读服务白名单剔除 gnome-shell（桌面会话级单元不入
     * 动作目录——重启等同重启整个桌面会话，负面清单条目）。防用户自造单元劫持的
     * 理由与只读域同源（~/.config/systemd/user 内同名单元可被用户自造）。
     */
    val SERVICE_WHITELIST: Set<String> = SysProbeTools.SERVICE_WHITELIST - "gnome-shell"

    /** 桌面会话级单元（永不入动作目录，拒绝文案点名给出处） */
    const val SESSION_LEVEL_SERVICE = "gnome-shell"

    // ── 目录（编译期锁死：动作 → 固定 argv 模板 + 回滚配方） ──

    /**
     * 服务动作目录条目。LLM 入参 = [id]（enum name 的小写）。
     * argv 恒为 `systemctl --user <action> <service>`（执行件内缺 --user 恒拒——双层防线）。
     *
     * @param label 语义化动作名（确认卡标题）
     * @param semantics 语义说明（确认卡正文：做什么、影响面）
     * @param riskBadge 风险徽标（确认卡风险行；L2 恒 DESTRUCTIVE）
     * @param rollbackFor 回滚配方（服务名 + 执行前 is-active 快照输出 → 配方一行）
     */
    enum class ServiceAction(
        internal val label: String,
        internal val semantics: String,
        internal val riskBadge: String,
        internal val rollbackFor: (service: String, preState: String) -> String,
    ) {
        /**
         * 重启用户级服务：服务进程中断数秒后由 systemd 重新拉起；幂等动作——
         * 异常时再次 restart 即回滚重试。
         */
        RESTART(
            label = "重启用户级服务",
            semantics = "systemctl --user restart：服务进程中断数秒后重新拉起——影响面限当前" +
                "用户会话内的该服务功能（输入法/音频/portal 等按服务而定）",
            riskBadge = "DESTRUCTIVE",
            rollbackFor = { service, pre ->
                val preNote = preStateNote(pre)
                "回滚：systemctl --user restart $service（restart 幂等——重启后异常时再次 restart 重试；" +
                    "仍异常用 sys.probe 的 journalctl_user_unit 条目查该服务日志）$preNote"
            },
        ),

        /** 启动用户级服务：回滚 = stop（执行前已在运行时本动作无实际影响）。 */
        START(
            label = "启动用户级服务",
            semantics = "systemctl --user start：拉起该用户级服务（已在运行时为幂等无操作）",
            riskBadge = "DESTRUCTIVE",
            rollbackFor = { service, pre ->
                when {
                    pre == "active" ->
                        "回滚：无需操作（执行前 is-active=active，本动作无实际影响）；如需停止由用户确认后执行 systemctl --user stop $service"
                    else ->
                        "回滚：systemctl --user stop $service（执行前状态${preStateNote(pre)}；停止即恢复本动作前形态）"
                }
            },
        ),

        /** 停止用户级服务：回滚 = start（执行前已停止时本动作无实际影响）。 */
        STOP(
            label = "停止用户级服务",
            semantics = "systemctl --user stop：终止该用户级服务进程——对应功能（输入法/音频等）" +
                "即时失效，直至再次 start 或重启会话",
            riskBadge = "DESTRUCTIVE",
            rollbackFor = { service, pre ->
                when {
                    pre == "inactive" ->
                        "回滚：无需操作（执行前 is-active=inactive，本动作无实际影响）；如需恢复运行执行 systemctl --user start $service"
                    else ->
                        "回滚：systemctl --user start $service（执行前状态${preStateNote(pre)}；启动即恢复本动作前形态）"
                }
            },
        ),
        ;

        /** LLM 入参形态的动作名（enum name 的小写投影） */
        val id: String get() = name.lowercase()

        /** 动作 argv（systemctl 恒带 --user——执行件内黑名单同款硬校验之外的第一层） */
        internal fun buildArgv(service: String): List<String> =
            listOf("systemctl", "--user", id, service)

        /** 只读原状态快照 argv（is-active 非零退出码 = inactive 等状态数据，非失败） */
        internal val preReadArgv: (service: String) -> List<String> =
            { service -> listOf("systemctl", "--user", "is-active", service) }

        /** 回滚配方长度收口（确认卡/回执/审计三面同源截断） */
        fun rollbackOf(service: String, preState: String): String =
            rollbackFor(service, preState).take(ROLLBACK_MAX_CHARS)
    }

    /** is-active 快照注记（捕获失败/异常形态按「未捕获」降级，不给回滚配方瞎编原状态） */
    private fun preStateNote(preState: String): String =
        if (preState.isNotBlank()) "：$preState" else "未捕获"

    // ── 确认卡数据与授权面契约（装配链实现承担发卡/防替答/超时=拒绝） ──

    /**
     * 服务动作确认卡数据（L2 卡面单源）：完整 argv + 语义化动作名 + DESTRUCTIVE 徽标 +
     * 回滚配方（含执行前原状态注记）。完整内容由实现经说明卡承载（sys.repair 动作卡
     * 同款两卡形态——确认卡 inputSummary 被服务侧截断装不下全文）。
     */
    data class ServiceActionCard(
        val actionKey: String,
        val label: String,
        val service: String,
        val argv: List<String>,
        val riskBadge: String,
        val semantics: String,
        val preState: String,
        val rollback: String,
    )

    /**
     * sys.service 逐动作授权面（装配链侧契约）。
     * [requireSessionAuthorization] = 域准入（信任用户名单判定，名单外进不了域）；
     * [requireActionApproval] 对信任用户免卡直接放行；非信任用户被域准入拒绝、到不了本卡，
     * 两卡路径仅作装配兜底。
     */
    interface SysServiceGate : SysSessionGate {

        /**
         * 要求本动作的用户确认（挂起直到裁决/超时）。
         *
         * @return true = 用户批准本动作；false = 拒绝/超时/说明卡发送失败（fail-closed）
         */
        suspend fun requireActionApproval(card: ServiceActionCard): Boolean
    }

    // ── 工具：sys.service ──

    class SysServiceTool(
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val executor: SysProbeTools.ProbeExecutor = SysProbeTools.ProbeExecutor.DEFAULT,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
        /** 逐动作授权面（装配门控；null = 门控面缺席 ⇒ 恒 DENIED，fail-closed） */
        private val serviceGate: SysServiceGate? = null,
    ) : StructuredSimpleTool<SysServiceTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = SERVICE_NAME,
        description = "Run a whitelisted USER-LEVEL systemd service action (always systemctl " +
            "--user). You can ONLY pick a (service, action) pair from the catalog, never craft " +
            "a command. Services: fcitx5, ibus, ibus-daemon, pipewire, wireplumber, pulseaudio, " +
            "xdg-desktop-portal (session-level units like gnome-shell are structurally absent). " +
            "Actions: restart (idempotent, rollback = restart again), start (rollback = stop), " +
            "stop (rollback = start). EVERY action requires the user to approve a per-action " +
            "confirmation card showing the full command, the DESTRUCTIVE badge and the rollback " +
            "recipe (the session-level grant never covers service actions). The service state is " +
            "captured before the action; verify afterwards with sys.probe systemd_user_status.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "User-level service name from the whitelist, e.g. fcitx5 / pipewire / " +
                    "xdg-desktop-portal. Whitelist; the rejection message lists the allowed " +
                    "values. Session-level units (gnome-shell) are permanently rejected.",
            )
            val service: String,
            @property:LLMDescription(
                "Action to run: restart / start / stop (status is read-only — use sys.probe " +
                    "systemd_user_status instead).",
            )
            val action: String,
        )

        /** 执行包装体：结果 + 审计动作面 + 退出码 + 裁决覆写 + 回滚信息 */
        private data class Serviced(
            val result: ToolResult,
            val action: String,
            val exitCode: Int?,
            val verdictOverride: String? = null,
            val rollback: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val serviced = executeServiced(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = SERVICE_NAME,
                    action = serviced.action,
                    verdict = serviced.verdictOverride ?: sysAuditVerdict(serviced.result),
                    exitCode = serviced.exitCode,
                    trigger = trigger,
                    outputSummary = sysAuditSummary(serviced.result),
                    rollback = serviced.rollback,
                ),
            )
            return serviced.result
        }

        private suspend fun executeServiced(args: Args): Serviced {
            // 域准入（会话档）：门控面缺席 = 装配缺陷，fail-closed 拒绝。
            val gate = serviceGate ?: return Serviced(
                ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "sys.service 未装配逐动作确认门（fail-closed 拒绝）——本链不支持服务动作。",
                ),
                action = "service=${args.service} action=${args.action}（门控面缺席）",
                exitCode = null,
                verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
            )
            if (!gate.requireSessionAuthorization(SERVICE_NAME)) {
                return Serviced(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获 sys.* 诊断域授权（发送者不在信任用户名单）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造执行结果。",
                    ),
                    action = "service=${args.service} action=${args.action}（会话授权拒绝）",
                    exitCode = null,
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val service = args.service.trim()
            if (service !in SERVICE_WHITELIST) {
                return Serviced(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        if (service == SESSION_LEVEL_SERVICE) {
                            "「$SESSION_LEVEL_SERVICE」是桌面会话级单元——重启等同重启整个桌面会话" +
                                "（Wayland 下杀掉全部窗口），永不入服务动作目录；请把重启需求以" +
                                "可复制命令交用户本人在终端执行"
                        } else {
                            "服务名「$service」不在动作白名单内（允许：${SERVICE_WHITELIST.sorted().joinToString("、")}）——" +
                                "服务名白名单防用户自造单元劫持；需要新服务名：修改 " +
                                "core/llm/tools/SysServiceTools.kt 的 SERVICE_WHITELIST 常量（编译期单源）"
                        },
                    ),
                    action = "service=$service action=${args.action}（服务白名单拒收）",
                    exitCode = null,
                )
            }
            val action = ServiceAction.entries.firstOrNull { it.id == args.action.trim().lowercase() }
                ?: return Serviced(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "未知动作「${args.action}」。可用动作：${ServiceAction.entries.joinToString("、") { it.id }}" +
                            "（status 只读已归 sys.probe 的 systemd_user_status 条目）",
                    ),
                    action = "service=$service action=${args.action}（未命中动作目录）",
                    exitCode = null,
                )

            val argv = action.buildArgv(service)
            val actionLine = argv.joinToString(" ")

            // 只读原状态快照（is-active；非零退出码 = inactive 等状态数据）：
            // 快照失败降级为未捕获（服务动作回滚不依赖捕获值，不拒执）。
            val preState = capturePreState(service)
            val rollback = action.rollbackOf(service, preState)

            // L2 逐动作确认卡：完整 argv + 语义 + DESTRUCTIVE 徽标 + 回滚配方
            val card = ServiceActionCard(
                actionKey = action.id,
                label = action.label,
                service = service,
                argv = argv,
                riskBadge = action.riskBadge,
                semantics = action.semantics,
                preState = preState,
                rollback = rollback,
            )
            if (!gate.requireActionApproval(card)) {
                return Serviced(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "服务动作「${action.label}（$service）」未经用户确认（拒绝/超时/说明卡发送失败）——未执行。" +
                            "请如实告知用户可在其本人终端手动执行该命令，禁止未经确认重试。",
                    ),
                    action = actionLine,
                    exitCode = null,
                    verdictOverride = SysAuditLog.VERDICT_ACTION_DENIED,
                    rollback = rollback,
                )
            }

            // 失败契约同 ProbeExecutor：只翻译声明的异常（CE 穿透；其余异常上抛不吞）
            val outcome = try {
                executor.run(argv)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                return Serviced(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        e.message ?: "argv 被执行面策略拒绝",
                    ),
                    action = actionLine,
                    exitCode = null,
                    rollback = rollback,
                )
            } catch (e: IOException) {
                return Serviced(
                    ToolResult.Err(
                        ToolResult.ErrCode.NOT_FOUND,
                        "命令「${argv.first()}」启动失败（本机未安装或不可执行）：${e.message ?: e.javaClass.simpleName}",
                        hint = "如实告知用户该命令在本机不可用（禁止编造执行结果）",
                    ),
                    action = actionLine,
                    exitCode = null,
                    rollback = rollback,
                )
            }
            if (outcome.timedOut) {
                return Serviced(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "命令「${argv.first()}」超时未完成，已被终止——服务动作结果未知，" +
                            "必须先用 sys.probe 的 systemd_user_status 条目核实实际状态再决定下一步",
                        hint = "如需恢复，把回滚配方交用户本人在终端执行",
                    ),
                    action = actionLine,
                    exitCode = null,
                    rollback = rollback,
                )
            }
            return Serviced(renderReceipt(action, service, argv, outcome, rollback), action = actionLine, exitCode = outcome.exitCode, rollback = rollback)
        }

        /**
         * 原状态快照：is-active 的输出（非零退出码是状态数据——inactive/failed 都有效）。
         * 命令不可用/超时/策略拒绝 = 未捕获（空串），不拒执。
         */
        private suspend fun capturePreState(service: String): String {
            val outcome = try {
                executor.run(ServiceAction.RESTART.preReadArgv(service))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            return outcome?.takeIf { !it.timedOut }?.output?.trim().orEmpty()
        }

        /** 结构化回执：action/argv/exit/rollback + 输出（恒脱敏 + 8K capAt 收口）+ 验证提示 */
        private fun renderReceipt(
            action: ServiceAction,
            service: String,
            argv: List<String>,
            outcome: ArgvOutcome,
            rollback: String,
        ): ToolResult {
            val output = SysOutputSanitizer.sanitize(outcome.output).ifBlank { "（无输出）" }
            val verifyHint = "验证：sys.probe 的 systemd_user_status 条目（service 传 $service）复查服务状态；" +
                "或 journalctl_user_unit 条目查该服务日志"
            val body = buildString {
                appendLine("action=${action.id}（${action.label}）")
                appendLine("argv: ${argv.joinToString(" ")}")
                appendLine("exit=${outcome.exitCode}")
                appendLine("rollback: $rollback")
                appendLine("--- output ---")
                append(output)
                if (outcome.exitCode != 0) {
                    appendLine()
                    appendLine(
                        "（退出码 ${outcome.exitCode} 非零——服务动作语义与只读探查不同：非零 = 未生效或执行失败，" +
                            "不要臆断已生效）",
                    )
                }
                appendLine()
                append(verifyHint)
            }
            val capped = capAt(body)
            return ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.ServicePayload(
                    action = action.id,
                    service = service,
                    exitCode = outcome.exitCode,
                    rollback = rollback,
                    truncated = capped != body || outcome.outputTruncated,
                ),
            )
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `sys.service` 目录描述符。risk = DESTRUCTIVE（用户级服务状态写面）；uiHint = null：
     * 纯 agent 工具，不进命令面板。装配门控：config.sysServiceGate 非 null 才注册
     * （工厂返 null），恒不入常备清单。
     */
    val SYS_SERVICE: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = SERVICE_NAME,
        description = "Run a whitelisted user-level systemd service action (systemctl --user " +
            "always; restart/start/stop only; session-level units excluded) — catalog (service, " +
            "action) pairs only, never command strings; every action needs a per-action user " +
            "confirmation card with the DESTRUCTIVE badge and rollback recipe",
        risk = AgentToolCatalog.Risk.DESTRUCTIVE,
        factory = { _, sink, config ->
            config.sysServiceGate?.let { gate ->
                SysServiceTool(sink, serviceGate = gate, trigger = gate.trigger)
            }
        },
    )
}
