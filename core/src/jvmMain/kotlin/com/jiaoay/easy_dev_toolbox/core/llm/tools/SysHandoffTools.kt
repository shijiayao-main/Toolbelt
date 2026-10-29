package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import kotlinx.serialization.Serializable

/**
 * 命令外委工具：`sys.handoff`——超出 sys.* 白名单面的动作（root/会话级/目录外步骤）的
 * 结构化出口：LLM 把修复方案里自己执行不了的步骤整理成命令清单（每条含作用/风险/回退），
 * 交用户**本人在终端执行**。
 *
 * ## 零执行面（本工具族唯一不触进程/文件系统的 sys.* 件）
 * 本工具不执行任何命令、不读不写任何文件——产出只是文本清单，是否执行由用户读完清单后
 * 自行裁决。因此本工具**不消费会话授权**（会话授权拒绝的用户恰恰最需要安全的降级出口），
 * 注册仍随 sys.* 三条件（config 门控 + 会话门控面在位），审计 trigger 经装配链注入。
 *
 * ## 话术纪律（P0 顾问段同源）
 * 回执恒带「未经执行」声明与转交话术（这条超出我的能力边界，需要您本人在终端执行 +
 * 逐条作用/风险/回退）；清单文本**不过脱敏器**（用户要复制粘贴执行，遮蔽会改写命令；
 * 泄密防线在上游——probe/read_config 输出进 LLM 前已脱敏）。不代执行、不伪装已执行：
 * 回执与负载恒标 executed=false，纪律段禁止 LLM 把清单表述成已完成的动作。
 *
 * ## 上界与审计
 * 步数/单字段长度/回执总长三重钳制（防清单刷爆 agent 上下文与确认疲劳）；每次调用
 * （含被拒）落审计（[SysAuditLog]，动作面 = 步数与首条命令摘要，输出全文不落）。
 *
 * ## 装配纪律
 * 工具本体与目录注册即收口三张守护网（围栏覆盖/结构化纪律/目录撞面）；装配门控 =
 * [AgentToolCatalog.ToolContextConfig.sysSessionGate] 非 null（trigger 归因来源；
 * 工厂返 null 不注册），恒不入常备清单。
 */
object SysHandoffTools {

    const val HANDOFF_NAME = "sys.handoff"

    /** 回执长度结构性上界（字符）——sys 家族同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** 单次外委步数上限（步）：再多用户读不完，应拆分会话分批外委 */
    const val MAX_STEPS = 8

    /** 单条命令长度上限（字符）：超长命令应拆步或交用户手敲要点 */
    const val MAX_COMMAND_CHARS = 500

    /** 单条说明字段长度上限（字符）：why/risk/rollback 的渲染预算 */
    const val MAX_TEXT_CHARS = 300

    // ── 工具：sys.handoff ──

    class SysHandoffTool(
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
    ) : StructuredSimpleTool<SysHandoffTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = HANDOFF_NAME,
        description = "Hand OFF-of-boundary fix steps to the user as a structured command list " +
            "(root actions, session-level actions, anything outside the sys.* catalogs). This " +
            "tool EXECUTES NOTHING — it only formats the steps; the user decides and runs them " +
            "in their own terminal. Each step needs the exact command, what it does, its risk " +
            "and how to roll back. Use it when a needed fix is beyond sys.repair/sys.service/" +
            "sys.write_config catalogs (e.g. apt reinstall, Wayland re-login), or after " +
            "sys.verify keeps failing. Never pretend a handed-off step was executed.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Why these steps are out of your execution boundary and what goal they serve, " +
                    "e.g. 'apt reinstall needs root, which I never do'.",
            )
            val purpose: String,
            @property:LLMDescription(
                "1..8 steps, each with the exact terminal command (verbatim, copy-paste ready), " +
                    "why (what it does), risk (what can go wrong) and rollback (how to undo; " +
                    "omit for read-only checks).",
            )
            val steps: List<HandoffStep>,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val result = renderHandoff(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = HANDOFF_NAME,
                    action = auditAction(args),
                    verdict = result.first,
                    trigger = trigger,
                    outputSummary = sysAuditSummary(result.second),
                ),
            )
            return result.second
        }

        /** 渲染 + 审计裁决（审计行要在拒收与放行两种形态都可记录，故一并返回） */
        private fun renderHandoff(args: Args): Pair<String, ToolResult> {
            val purpose = args.purpose.trim()
            if (purpose.isEmpty()) {
                return SysAuditLog.VERDICT_BAD_ARGS to ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "purpose 不能为空——请说明这些步骤为什么超出你的执行边界（root/会话级/白名单外）",
                )
            }
            val steps = args.steps
            if (steps.isEmpty()) {
                return SysAuditLog.VERDICT_BAD_ARGS to ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "steps 不能为空——外委清单至少一条命令；没有可外委步骤就不要调用本工具",
                )
            }
            if (steps.size > MAX_STEPS) {
                return SysAuditLog.VERDICT_BAD_ARGS to ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "steps 超上限（${steps.size} > $MAX_STEPS 步）——用户读不完长清单，" +
                        "请拆分：先外委最关键的一两步，其余等用户回报后再继续",
                )
            }
            steps.forEachIndexed { index, step ->
                val command = step.command.trim()
                val why = step.why.trim()
                when {
                    command.isEmpty() -> return SysAuditLog.VERDICT_BAD_ARGS to ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "第 ${index + 1} 步 command 不能为空",
                    )
                    command.length > MAX_COMMAND_CHARS -> return SysAuditLog.VERDICT_BAD_ARGS to ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "第 ${index + 1} 步 command 超长（${command.length} > $MAX_COMMAND_CHARS 字符）——" +
                            "拆成多步或只给要点由用户补全",
                    )
                    why.isEmpty() -> return SysAuditLog.VERDICT_BAD_ARGS to ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "第 ${index + 1} 步 why 不能为空——每条命令都必须说明它做什么",
                    )
                    step.risk.length > MAX_TEXT_CHARS || step.rollback.length > MAX_TEXT_CHARS ->
                        return SysAuditLog.VERDICT_BAD_ARGS to ToolResult.Err(
                            ToolResult.ErrCode.BAD_ARGS,
                            "第 ${index + 1} 步 risk/rollback 超长（单字段 ≤ $MAX_TEXT_CHARS 字符）",
                        )
                }
            }

            val body = buildString {
                appendLine("handoff=外委清单（全部步骤未经执行——是否执行由用户本人决定）")
                appendLine("purpose: $purpose")
                steps.forEachIndexed { index, step ->
                    appendLine("${index + 1}) ${step.command.trim()}")
                    appendLine("   作用：${step.why.trim()}")
                    appendLine("   风险：${step.risk.trim().ifBlank { "未提供——请向用户说明确认后再执行" }}")
                    appendLine("   回退：${step.rollback.trim().ifBlank { "未提供（只读检查类命令通常无需回退）" }}")
                }
                appendLine("---")
                append(
                    "转交话术（原样转达，不得省略「未经执行」声明）：以上 ${steps.size} 条命令超出我的能力边界" +
                        "（root/会话级/白名单外动作），需要您本人在终端执行；我未执行、也不会代为执行。" +
                        "每条的作用/风险/回退已逐条列出，请自行判断后再运行，跑完把输出贴回来我继续分析。",
                )
            }
            val capped = capAt(body)
            return SysAuditLog.VERDICT_HANDOFF to ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.HandoffPayload(
                    stepCount = steps.size,
                    executed = false,
                    truncated = capped != body,
                ),
            )
        }

        /** 审计动作面：步数 + 首条命令摘要（命令全文不进审计动作面，防审计膨胀） */
        private fun auditAction(args: Args): String {
            val first = args.steps.firstOrNull()?.command?.trim().orEmpty()
            return "steps=${args.steps.size}: ${first.take(80)}"
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /** 单步外委条目：命令原文（用户在终端原样执行，不做任何改写——复制粘贴保真）+ 三段说明 */
    @Serializable
    data class HandoffStep(
        val command: String,
        val why: String,
        val risk: String = "",
        val rollback: String = "",
    )

    /**
     * `sys.handoff` 目录描述符。risk = READ_ONLY（零执行面：产出只是文本清单）；uiHint =
     * null：纯 agent 工具，不进命令面板。装配门控：sysSessionGate 非 null 才注册
     * （仅作审计 trigger 归因来源，不消费授权——零执行面无卡可发），恒不入常备清单。
     */
    val SYS_HANDOFF: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = HANDOFF_NAME,
        description = "Hand out-of-boundary fix steps to the user as a structured command list " +
            "(root/session-level actions outside the sys.* catalogs) — executes nothing, the " +
            "user runs the commands themselves; each step carries command/why/risk/rollback",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        factory = { _, sink, config ->
            config.sysSessionGate?.let { gate ->
                SysHandoffTool(sink, trigger = gate.trigger)
            }
        },
    )
}
