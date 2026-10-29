package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/**
 * 验证回环工具：`sys.verify`——修复动作执行后复跑对应只读探查，对比修复前后状态判定
 * 修复是否生效（生效/未生效/无法判定三态），复跑仍未生效时降级为外委建议。
 *
 * ## 判定 = 纯函数（快照矩阵单测钉死）
 * [evaluate] 是纯函数：修复动作目录条目 + 槽值 + 修复前探查快照（before）+ 复跑探查快照
 * （after）→ [Verdict] 三态。每条修复动作的验证探查 argv 与「生效」判据单源登记在
 * [verifyProbeArgv] 与 [evaluate]（im_config_set_fcitx5 → im-config -m 第 2 行、
 * fcitx5_restart_daemon → pgrep -a fcitx5 进程在位、gnome_extension_enable →
 * gnome-extensions info 的 State 行、gsettings_set → gsettings get 键值比对）；
 * before 缺失时按「此前状态未提供」降级判定（状态在位性仍可判），输出不可解析 =
 * 无法判定（不臆断）。
 *
 * ## 复跑与降级（失败 N 次 → handoff）
 * 探查最多尝试 [MAX_ATTEMPTS] 次（初跑 + 1 次复跑，间隔 [RETRY_DELAY_MS]——服务拉起类
 * 状态可能滞后数毫秒）；全部尝试仍未生效即降级：回执明示「降级外委」并给出 handoff
 * 清单要素（手动复查命令 + sys.repair 回执中的回滚配方），由 LLM 经 sys.handoff（未装配
 * 时直接文本）交用户本人执行——不再自动重试写动作。
 *
 * ## 授权与执行（L0 只读）
 * 验证探查是只读命令执行——执行前必过 [SysSessionGate.requireSessionAuthorization]
 * （sys.* 域会话档，缺席即 DENIED fail-closed；L0 免逐次卡），探查经
 * [SysProbeTools.ProbeExecutor]（argv 纯语义执行缝，执行件内黑名单恒拒）；回执恒 8K
 * capAt 收口。每次调用（含被拒）落审计（[SysAuditLog]，输出全文不落）。
 *
 * ## 装配纪律
 * 工具本体与目录注册即收口三张守护网（围栏覆盖/结构化纪律/目录撞面）；装配门控 =
 * [AgentToolCatalog.ToolContextConfig.sysSessionGate] 非 null（工厂返 null 不注册），
 * 恒不入常备清单。
 */
object SysVerifyTools {

    const val VERIFY_NAME = "sys.verify"

    /** 回执长度结构性上界（字符）——sys 家族同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /**
     * 探查尝试上限（初跑 + 复跑 1 次）：两次仍未生效即降级外委——状态滞后再长也不该
     * 靠自动重试掩盖（自动重试写动作是纪律段明令禁止的形态，验证侧同样收敛）。
     */
    const val MAX_ATTEMPTS = 2

    /** 复跑间隔（ms）：覆盖服务拉起/守护 fork 的常见滞后 */
    internal const val RETRY_DELAY_MS = 600L

    /** before 快照入参长度收口（字符）：探查回执本有 8K 上界，验证侧再收半档 */
    internal const val BEFORE_MAX_CHARS = 4096

    /** 验证判定三态：生效 / 未生效 / 无法判定 */
    enum class Verdict {
        EFFECTIVE,
        NOT_EFFECTIVE,
        INDETERMINATE,
    }

    /** 判定结果：三态 + 人话细节（回执正文单源） */
    internal data class Eval(val verdict: Verdict, val detail: String)

    /**
     * 验证探查 argv（修复动作 → 复跑探查的对应关系单源）：LLM 不选探查条目——
     * 传修复动作 key，探查条目由本映射推导（防 LLM 拿不相关探查自证生效）。
     * @throws IllegalArgumentException 修复动作槽缺失（调用方已先行校验，此处为防线兜底）
     */
    internal fun verifyProbeArgv(key: SysRepairTools.RepairKey, slots: Map<SysRepairTools.RepairSlot, String>): List<String> =
        when (key) {
            SysRepairTools.RepairKey.IM_CONFIG_SET_FCITX5 -> listOf("im-config", "-m")
            SysRepairTools.RepairKey.FCITX5_RESTART_DAEMON -> listOf("pgrep", "-a", "fcitx5")
            SysRepairTools.RepairKey.GNOME_EXTENSION_ENABLE ->
                listOf("gnome-extensions", "info", slots.getValue(SysRepairTools.RepairSlot.UUID))
            SysRepairTools.RepairKey.GSETTINGS_SET -> {
                val schemaKey = slots.getValue(SysRepairTools.RepairSlot.SCHEMA_KEY)
                listOf(
                    "gsettings", "get",
                    schemaKey.substringBeforeLast('.'), schemaKey.substringAfterLast('.'),
                )
            }
        }

    /** 各修复动作验证所需的槽（槽纪律单源：多传少传都拒收） */
    internal val requiredSlots: Map<SysRepairTools.RepairKey, Set<SysRepairTools.RepairSlot>> = mapOf(
        SysRepairTools.RepairKey.IM_CONFIG_SET_FCITX5 to emptySet(),
        SysRepairTools.RepairKey.FCITX5_RESTART_DAEMON to emptySet(),
        SysRepairTools.RepairKey.GNOME_EXTENSION_ENABLE to setOf(SysRepairTools.RepairSlot.UUID),
        SysRepairTools.RepairKey.GSETTINGS_SET to setOf(
            SysRepairTools.RepairSlot.SCHEMA_KEY, SysRepairTools.RepairSlot.VALUE,
        ),
    )

    /**
     * 生效判定纯函数：前后快照矩阵的判定面（单测以 fake 快照矩阵钉死三态）。
     * after 是复跑探查的原样输出（未脱敏前的比对在调用方完成——本函数只看输入串）；
     * before 为 null/空白 = 此前状态未提供（状态在位性判定不受影响）。
     */
    internal fun evaluate(
        key: SysRepairTools.RepairKey,
        slots: Map<SysRepairTools.RepairSlot, String>,
        before: String?,
        after: String,
    ): Eval = when (key) {
        SysRepairTools.RepairKey.IM_CONFIG_SET_FCITX5 -> evalImConfig(before, after)
        SysRepairTools.RepairKey.FCITX5_RESTART_DAEMON -> evalFcitx5Daemon(before, after)
        SysRepairTools.RepairKey.GNOME_EXTENSION_ENABLE -> evalExtensionEnabled(before, after)
        SysRepairTools.RepairKey.GSETTINGS_SET -> evalGsettings(slots, before, after)
    }

    /** im-config -m 第 2 行 = 当前生效输入法（解析单源复用 [SysRepairTools.imConfigActiveLine]） */
    private fun evalImConfig(before: String?, after: String): Eval {
        val afterActive = SysRepairTools.imConfigActiveLine(after)
            ?: return Eval(Verdict.INDETERMINATE, "im-config -m 输出解析不出当前生效输入法（第 2 行缺失或形态异常）")
        if (afterActive != "fcitx5") {
            return Eval(Verdict.NOT_EFFECTIVE, "当前生效输入法仍为「$afterActive」——fcitx5 未生效")
        }
        val beforeActive = before?.let { SysRepairTools.imConfigActiveLine(it) }
        val change = when {
            beforeActive == null -> "（此前状态未提供）"
            beforeActive == "fcitx5" -> "（此前已为 fcitx5——本动作为幂等重申）"
            else -> "（由「$beforeActive」变更而来）"
        }
        return Eval(Verdict.EFFECTIVE, "当前生效输入法已为 fcitx5$change")
    }

    /** pgrep -a fcitx5：有输出行 = 进程在位（exit 1 + 空输出 = 无进程，是确定性「未拉起」） */
    private fun evalFcitx5Daemon(before: String?, after: String): Eval {
        val running = after.lineSequence().any { it.isNotBlank() }
        if (!running) {
            return Eval(Verdict.NOT_EFFECTIVE, "无 fcitx5 进程在跑——守护未拉起")
        }
        val beforeRunning = before != null && before.lineSequence().any { it.isNotBlank() }
        val change = if (beforeRunning) "（此前已有进程在跑——本动作为幂等重申）" else "（此前无进程——本动作拉起）"
        return Eval(Verdict.EFFECTIVE, "fcitx5 守护进程在位$change")
    }

    /** gnome-extensions info 的 State 行（大小写不敏感——不同 shell 版本标签形态有差异） */
    private fun evalExtensionEnabled(before: String?, after: String): Eval {
        val stateRegex = Regex("""(?i)\bstate:\s*([A-Za-z_]+)""")
        val afterState = stateRegex.find(after)?.groupValues?.get(1)?.uppercase()
            ?: return Eval(
                Verdict.INDETERMINATE,
                "gnome-extensions info 输出无 State 行——扩展可能未安装，可先用 sys.probe 的 " +
                    "gnome_extensions_list 条目枚举已装扩展",
            )
        if (afterState != "ENABLED") {
            return Eval(Verdict.NOT_EFFECTIVE, "扩展状态仍为 $afterState——未启用")
        }
        val beforeEnabled = before != null &&
            stateRegex.find(before)?.groupValues?.get(1)?.uppercase() == "ENABLED"
        val change = if (beforeEnabled) "（此前已启用——本动作为幂等重申）" else ""
        return Eval(Verdict.EFFECTIVE, "扩展已启用$change")
    }

    /** gsettings get 键值比对（GVariant 文本：剥离 @as 类型注解 + 空白归一——@as [] 与 [] 同值） */
    private fun evalGsettings(
        slots: Map<SysRepairTools.RepairSlot, String>,
        before: String?,
        after: String,
    ): Eval {
        val expected = slots.getValue(SysRepairTools.RepairSlot.VALUE).trim()
        val actual = after.trim()
        if (actual.isEmpty()) {
            return Eval(Verdict.INDETERMINATE, "gsettings get 输出为空，无法比对键值")
        }
        val normalize = { s: String ->
            s.trim().removePrefix("@as").trim().replace(Regex("\\s+"), "")
        }
        if (normalize(actual) != normalize(expected)) {
            return Eval(Verdict.NOT_EFFECTIVE, "键值仍为「$actual」（期望「$expected」）——写入未生效")
        }
        val beforeMatches = before != null && normalize(before.trim()) == normalize(expected)
        val change = if (beforeMatches) "（此前已是该值——本动作为幂等重申）" else ""
        return Eval(Verdict.EFFECTIVE, "键值已写入（$actual）$change")
    }

    // ── 工具：sys.verify ──

    class SysVerifyTool(
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val executor: SysProbeTools.ProbeExecutor = SysProbeTools.ProbeExecutor.DEFAULT,
        private val audit: SysAuditLog = SysAuditLog(),
        private val trigger: String? = null,
        /** 会话级门控面（L0 只读探查；null = 门控面缺席 ⇒ 恒 DENIED，fail-closed） */
        private val verifyGate: SysSessionGate? = null,
    ) : StructuredSimpleTool<SysVerifyTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = VERIFY_NAME,
        description = "Verify a sys.repair fix by re-running the matching read-only probe and " +
            "comparing before/after state. Pass the repair action key (im_config_set_fcitx5 / " +
            "fcitx5_restart_daemon / gnome_extension_enable / gsettings_set) plus the same " +
            "slots used for the repair (uuid / schema_key / value) and optionally the " +
            "'pre-state' snapshot from the repair receipt as before. Verdicts: effective / " +
            "not_effective / indeterminate. The probe is retried once if not effective; if it " +
            "still fails, degrade: hand the manual check + rollback commands to the user via " +
            "sys.handoff instead of retrying the write action.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Repair action key to verify: im_config_set_fcitx5 / fcitx5_restart_daemon / " +
                    "gnome_extension_enable / gsettings_set. The matching probe is derived " +
                    "automatically — you never pick a probe key here.",
            )
            val key: String,
            @property:LLMDescription(
                "Same uuid used by the gnome_extension_enable repair (required for that key).",
            )
            val uuid: String? = null,
            @property:LLMDescription(
                "Same schema_key used by the gsettings_set repair (required for that key).",
            )
            val schema_key: String? = null,
            @property:LLMDescription(
                "Same value used by the gsettings_set repair (required for that key) — the " +
                    "written value the probe output is compared against.",
            )
            val value: String? = null,
            @property:LLMDescription(
                "The 'pre-state' snapshot from the sys.repair receipt (the before-repair probe " +
                    "output). Optional; omit when the receipt carries no pre-state block.",
            )
            val before: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val verified = executeVerified(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = VERIFY_NAME,
                    action = verified.action,
                    verdict = verified.verdictOverride ?: sysAuditVerdict(verified.result),
                    exitCode = verified.exitCode,
                    trigger = trigger,
                    outputSummary = sysAuditSummary(verified.result),
                ),
            )
            return verified.result
        }

        /** 执行包装体：结果 + 审计动作面 + 退出码 + 裁决覆写 */
        private data class Verified(
            val result: ToolResult,
            val action: String,
            val exitCode: Int? = null,
            val verdictOverride: String? = null,
        )

        private suspend fun executeVerified(args: Args): Verified {
            // 域准入（会话档）：验证探查是只读命令执行——门控面缺席 = 装配缺陷，fail-closed。
            val gate = verifyGate ?: return Verified(
                ToolResult.Err(
                    ToolResult.ErrCode.DENIED,
                    "sys.verify 未装配会话门控面（fail-closed 拒绝）——本链不支持验证回环。",
                ),
                action = "key=${args.key}（门控面缺席）",
                verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
            )
            if (!gate.requireSessionAuthorization(VERIFY_NAME)) {
                return Verified(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本消息发送者不在信任用户名单，无法使用 sys.* 诊断能力——请如实告知用户" +
                            "去桥接设置页把其 openId 加入「信任用户」，禁止编造验证结果。",
                    ),
                    action = "key=${args.key}（会话授权拒绝）",
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val key = SysRepairTools.RepairKey.entries.firstOrNull { it.id == args.key.trim().lowercase() }
                ?: return Verified(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "未知修复动作「${args.key}」。本工具按修复动作推导验证探查，不能自造命令。" +
                            "可验证动作：${SysRepairTools.RepairKey.entries.joinToString("、") { it.id }}",
                    ),
                    action = "key=${args.key}（未命中修复目录）",
                )
            val required = requiredSlots.getValue(key)

            // 槽纪律：验证只收该修复动作声明的槽（多传少传都拒收——与修复侧同口径）
            val supplied = mapOf(
                SysRepairTools.RepairSlot.UUID to args.uuid,
                SysRepairTools.RepairSlot.SCHEMA_KEY to args.schema_key,
                SysRepairTools.RepairSlot.VALUE to args.value,
            ).filterValues { it != null }.mapValues { it.value as String }
            val unexpected = supplied.keys - required
            if (unexpected.isNotEmpty()) {
                return Verified(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "动作 ${key.id} 的验证不接受参数 ${unexpected.joinToString("、") { it.id }}——" +
                            "验证只需 ${(required).joinToString("、") { it.id }.ifEmpty { "无槽" }}",
                    ),
                    action = "key=${key.id}（槽纪律拒收）",
                )
            }
            val missing = required - supplied.keys
            if (missing.isNotEmpty()) {
                return Verified(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "动作 ${key.id} 的验证缺必填参数 ${missing.joinToString("、") { it.id }}——" +
                            "传修复时用的同值（uuid / schema_key / value）",
                    ),
                    action = "key=${key.id}（缺必填槽）",
                )
            }
            val resolved = buildMap {
                supplied.forEach { (slot, raw) ->
                    when (val check = when (slot) {
                        SysRepairTools.RepairSlot.UUID -> SysProbeTools.checkUuid(raw)
                        SysRepairTools.RepairSlot.SCHEMA_KEY -> checkSchemaKey(raw)
                        SysRepairTools.RepairSlot.VALUE -> SysRepairTools.checkSchemaValue(
                            supplied[SysRepairTools.RepairSlot.SCHEMA_KEY]?.trim().orEmpty(),
                            raw,
                        )
                    }) {
                        is SysProbeTools.SlotCheck.Accepted -> put(slot, check.value)
                        is SysProbeTools.SlotCheck.Rejected -> return Verified(
                            ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, check.reason),
                            action = "key=${key.id}（槽校验拒收）",
                        )
                    }
                }
            }

            val verifyArgv = verifyProbeArgv(key, resolved)
            val action = "verify ${key.id}: ${verifyArgv.joinToString(" ")}"

            // 复跑循环：最多 MAX_ATTEMPTS 次探查；仍未生效 → 降级外委（不再自动重试写动作）
            val before = args.before?.take(BEFORE_MAX_CHARS)
            var attempts = 0
            var exitCode: Int? = null
            var lastEval = Eval(Verdict.INDETERMINATE, "未执行任何探查")
            while (attempts < MAX_ATTEMPTS) {
                attempts++
                // 失败契约同 ProbeExecutor：只翻译声明的异常（CE 穿透；其余异常上抛不吞）
                val outcome = try {
                    executor.run(verifyArgv)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    return Verified(
                        ToolResult.Err(
                            ToolResult.ErrCode.DENIED,
                            e.message ?: "验证探查 argv 被执行面策略拒绝",
                        ),
                        action = action,
                    )
                } catch (e: IOException) {
                    return Verified(
                        ToolResult.Err(
                            ToolResult.ErrCode.NOT_FOUND,
                            "验证探查命令「${verifyArgv.first()}」启动失败（本机未安装或不可执行）：${e.message ?: e.javaClass.simpleName}",
                            hint = "可改用 sys.probe 对应只读条目人工复查，或如实告知用户该命令在本机不可用",
                        ),
                        action = action,
                    )
                }
                exitCode = outcome.exitCode
                lastEval = if (outcome.timedOut) {
                    Eval(Verdict.INDETERMINATE, "验证探查超时未完成，已被终止——无法判定")
                } else {
                    evaluate(key, resolved, before, outcome.output)
                }
                if (lastEval.verdict == Verdict.EFFECTIVE) break
                if (attempts < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
            }
            return Verified(renderReceipt(key, verifyArgv, lastEval, attempts), action = action, exitCode = exitCode)
        }

        /** 结构化回执：verify=<三态> + 判定细节 + 探查命令（8K capAt 收口；未生效带降级外委要素） */
        private fun renderReceipt(
            key: SysRepairTools.RepairKey,
            verifyArgv: List<String>,
            eval: Eval,
            attempts: Int,
        ): ToolResult {
            val body = buildString {
                appendLine("verify=${eval.verdict.name.lowercase()}（action=${key.id}，探查 $attempts/$MAX_ATTEMPTS 次）")
                appendLine("detail: ${eval.detail}")
                appendLine("probe: ${verifyArgv.joinToString(" ")}")
                when (eval.verdict) {
                    Verdict.EFFECTIVE -> append(
                        "修复已生效——如实告知用户验证结果；如用户暂不在场可建议其用 remind_user 设个提醒稍后回来继续观察。",
                    )
                    Verdict.INDETERMINATE -> append(
                        "无法判定生效与否（探查输出不可解析）——不要臆断；用 sys.probe 对应只读条目人工复查，" +
                            "仍无法判定时把检查命令交用户本人执行。",
                    )
                    Verdict.NOT_EFFECTIVE -> {
                        appendLine("降级外委：复跑仍未生效，不要再自动重试写动作——用 sys.handoff（未装配时直接文本）" +
                            "把下列要素交用户本人在终端执行：")
                        appendLine("1) 手动复查命令：${verifyArgv.joinToString(" ")}（看输出判断实际状态）")
                        appendLine("2) 回滚配方：取 sys.repair 回执中的 rollback 行（修复前已捕获原值快照）")
                        append("3) 逐条说明作用/风险/回退，明示这些命令未经执行、由用户自行判断。")
                    }
                }
            }
            val capped = capAt(body)
            return ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.VerifyPayload(
                    key = key.id,
                    verdict = eval.verdict.name.lowercase(),
                    attempts = attempts,
                    truncated = capped != body,
                ),
            )
        }

        private fun capAt(text: String): String = LogStatsDiffTools.capAt(text, MAX_OUTPUT_CHARS)

        /** schema.key 槽白名单（修复侧写键白名单同源——键不在册即拒收） */
        private fun checkSchemaKey(raw: String): SysProbeTools.SlotCheck {
            val name = raw.trim()
            return if (name in SysRepairTools.GSETTINGS_WRITE_KEYS) {
                SysProbeTools.SlotCheck.Accepted(name)
            } else {
                SysProbeTools.SlotCheck.Rejected(
                    "schema.key「$name」不在修复白名单内" +
                        "（允许：${SysRepairTools.GSETTINGS_WRITE_KEYS.keys.sorted().joinToString("、")}）",
                )
            }
        }
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `sys.verify` 目录描述符。risk = READ_ONLY（只读探查复跑，零写面）；uiHint = null：
     * 纯 agent 工具，不进命令面板。装配门控：sysSessionGate 非 null 才注册（L0 会话档，
     * 免逐次卡），恒不入常备清单。
     */
    val SYS_VERIFY: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = VERIFY_NAME,
        description = "Verify a sys.repair fix by re-running the matching read-only probe and " +
            "comparing before/after snapshots — three-state verdict (effective / not_effective / " +
            "indeterminate), one retry then degrade to sys.handoff",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        factory = { _, sink, config ->
            config.sysSessionGate?.let { gate ->
                SysVerifyTool(sink, verifyGate = gate, trigger = gate.trigger)
            }
        },
    )
}
