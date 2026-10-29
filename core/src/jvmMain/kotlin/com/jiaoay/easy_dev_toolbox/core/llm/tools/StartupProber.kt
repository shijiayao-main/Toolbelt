package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * 启动自检探查的一次性结论（推——启动自检的对外形态；分类判则见 [StartupProber] 类 KDoc）。
 */
sealed interface StartupProbeOutcome {

    /** 全部探查项执行完成且未命中任何确定性异常态 ⇒ 正常（调用方静默，不发消息） */
    data object Normal : StartupProbeOutcome

    /** 命中至少一个确定性异常态 ⇒ 模板消息文本（已脱敏、已收口上界），由调用方原样外发 */
    data class Anomaly(val message: String) : StartupProbeOutcome

    /**
     * 未命中异常但存在探查项执行失败（命令缺失/超时/策略拒绝）⇒ 如实报告不静默：
     * 机器健康结论本次未覆盖，调用方把文本原样外发（异常与失败并存时归入 [Anomaly]，失败清单随附）。
     */
    data class ProbeFailed(val message: String) : StartupProbeOutcome
}

/**
 * 启动自检推送（健康观测面）：桥接长连接建立后**一次性**执行的确定性机器健康探查。
 *
 * ## 触发形态：启动触发非定时任务
 * 本组件只提供「跑一次」的 [runStartupProbe]；调用时机 = 桥接 ws 连接成功后的宿主缝
 * （lark-bridge 门面 start 成功路径）。恒不承载任何周期/定时/驻留触发——定时自检属
 * 能力定案负面清单的无人值守面（负面清单第 11 条：无人值守环境不扩域），
 * 本组件结构上无调度面（无内部循环、无时钟、无挂起重试）。
 *
 * ## 探查子集（复用 sys.probe 编译期目录 5 个条目，argv 单源零双写）
 * 机器健康关键项 = [SysProbeTools.ProbeKey.OS_RELEASE]（发行版语境）、
 * [SysProbeTools.ProbeKey.DESKTOP_ENV_BASICS]（桌面会话语境）、
 * [SysProbeTools.ProbeKey.SYSTEMD_USER_STATUS] × [STARTUP_PROBE_SERVICES]（用户级关键服务）、
 * [SysProbeTools.ProbeKey.IM_CONFIG_MODE] + [SysProbeTools.ProbeKey.FCITX5_PROCESSES]
 * （输入法期望核对）。argv 全部由 [SysProbeTools.ProbeKey.buildArgv] 编译期模板组装
 * （零 LLM 零 agent：探查与判则全确定性纯代码，不产出任何 agent 工具面）。
 *
 * ## 异常判定（保守：只有确定性失败态才算异常）
 * - 服务项：`systemctl --user status` 输出存在 `Active: failed` 行（[SERVICE_FAILED_REGEX]）；
 *   `inactive (dead)` 与单元未安装（exit=4 / could not be found）都不是失败态（按设计不活跃/未装载），不判异常；
 * - 输入法项：`im-config -m` 第二非空行（man 语义 = active configuration (user)）为
 *   fcitx5（期望已武装）且 `pgrep -a fcitx5` 无匹配（exit=1）——配置与进程态的确定性矛盾；
 *   im-config 输出形态不符/执行失败 ⇒ 期望不武装，不判异常；
 * - 其余探查项（发行版/桌面环境）只作报告语境，无异常判则。
 *
 * ## 执行与审计纪律
 * 探查走 [ProbeExecutor]（生产 = ArgvProcessRunner：argv 纯语义 + 提权黑名单 +
 * systemctl 恒 --user 硬校验 + 看门狗）；每探查项落 sys-audit 一条
 * （[SysAuditLog]，tool = [AUDIT_TOOL]：执行放行/执行失败/围栏拒绝三裁决）；输出摘要
 * 脱敏后落审计（全文不落）。探查失败如实报告不静默：任一探查项执行失败 ⇒ 失败清单，
 * 无异常但有失败 ⇒ [StartupProbeOutcome.ProbeFailed]（全量失败报告）。
 *
 * 模板消息文本恒经脱敏（[SysOutputSanitizer]）与整体上界（[MESSAGE_MAX_CHARS]）收口。
 */
class StartupProber(
    /** 执行缝（测试注入 fake；生产 = SysProbeTools.ProbeExecutor.DEFAULT 即 ArgvProcessRunner） */
    private val executor: SysProbeTools.ProbeExecutor = SysProbeTools.ProbeExecutor.DEFAULT,
    /** 审计落盘（默认应用数据根 audit/；测试注入临时目录或 NOOP） */
    private val audit: SysAuditLog = SysAuditLog(),
) {

    /** 单个探查项的执行回执（原始输出供判则；出站渲染前统一脱敏） */
    private data class ItemResult(
        val argvText: String,
        val exitCode: Int?,
        val output: String,
        /** 非空 = 执行面失败（命令缺失/超时/策略拒绝）——判则跳过、进失败清单 */
        val failureReason: String?,
    )

    /**
     * 跑一次完整启动自检。恒不抛业务异常（单项失败折叠进结果；整体异常折叠为
     * [StartupProbeOutcome.ProbeFailed]——探查失败如实报告不静默）；协程取消原样上抛。
     */
    suspend fun runStartupProbe(): StartupProbeOutcome = try {
        probeAll()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        StartupProbeOutcome.ProbeFailed(
            "启动自检执行异常（机器健康结论未覆盖）：${t.javaClass.simpleName}: ${t.message ?: "无消息"}",
        )
    }

    private suspend fun probeAll(): StartupProbeOutcome {
        val anomalies = mutableListOf<String>()
        val anomaliesEvidence = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val restLines = mutableListOf<String>()

        // ① 机器语境（无异常判则——只进模板消息的「机器环境」段；失败仍进失败清单如实报告）
        val osContext = contextLine(trackedItem(SysProbeTools.ProbeKey.OS_RELEASE, emptyMap(), failures))
        val desktopContext = contextLine(
            trackedItem(SysProbeTools.ProbeKey.DESKTOP_ENV_BASICS, emptyMap(), failures),
        )

        // ② 用户级关键服务：Active: failed 才判异常（inactive/未安装非失败态）
        for (service in STARTUP_PROBE_SERVICES) {
            val result = trackedItem(
                SysProbeTools.ProbeKey.SYSTEMD_USER_STATUS,
                mapOf(SysProbeTools.ParamKind.SERVICE to service),
                failures,
            )
            val failed = result.failureReason == null &&
                SERVICE_FAILED_REGEX.containsMatchIn(result.output)
            if (failed) {
                anomalies += "用户级服务「$service」处于 failed 状态"
                anomaliesEvidence += evidenceBlock(result.argvText, result.exitCode, result.output)
            } else {
                restLines += detailLine(result, note = serviceRestNote(result))
            }
        }

        // ③ 输入法期望核对：期望武装（im-config 用户级配置 = fcitx5）才跑进程核对
        val im = trackedItem(SysProbeTools.ProbeKey.IM_CONFIG_MODE, emptyMap(), failures)
        val armed = im.failureReason == null &&
            im.exitCode == 0 &&
            secondNonBlankLine(im.output) == IM_CONFIGURED_FRAMEWORK
        if (armed) {
            val pg = trackedItem(SysProbeTools.ProbeKey.FCITX5_PROCESSES, emptyMap(), failures)
            when {
                pg.failureReason != null -> restLines += detailLine(pg, note = "执行失败")
                pg.exitCode == 1 -> {
                    anomalies += "输入法框架配置为 fcitx5，但 fcitx5 进程未在运行（pgrep 无匹配）"
                    anomaliesEvidence += evidenceBlock(pg.argvText, pg.exitCode, pg.output)
                }
                pg.exitCode == 0 -> restLines += detailLine(pg, note = "进程在跑")
                else -> restLines += detailLine(pg, note = "退出码非无匹配语义，不判异常")
            }
        } else {
            restLines += detailLine(im, note = "期望未武装（用户级配置非 fcitx5 或探查失败）")
        }

        return when {
            anomalies.isNotEmpty() -> StartupProbeOutcome.Anomaly(
                renderAnomalies(anomalies, anomaliesEvidence, failures, restLines, osContext, desktopContext),
            )
            failures.isNotEmpty() -> StartupProbeOutcome.ProbeFailed(
                renderFailures(failures),
            )
            else -> StartupProbeOutcome.Normal
        }
    }

    // ── 单项执行 + 审计 ──

    /**
     * 执行一个目录条目并把执行面失败收进 [failures]（探查失败如实报告不静默——
     * 失败清单是 ProbeFailed/Anomaly 消息「探查未完成」段的唯一来源）。
     */
    private suspend fun trackedItem(
        key: SysProbeTools.ProbeKey,
        slots: Map<SysProbeTools.ParamKind, String>,
        failures: MutableList<String>,
    ): ItemResult = runItem(key, slots).also { result ->
        result.failureReason?.let { failures += "${result.argvText}：$it" }
    }

    /** 执行一个目录条目（argv 由编译期模板组装）并落审计；执行面失败折叠为失败回执不抛 */
    private suspend fun runItem(
        key: SysProbeTools.ProbeKey,
        slots: Map<SysProbeTools.ParamKind, String>,
    ): ItemResult {
        val argv = key.buildArgv(slots)
        val argvText = argv.joinToString(" ")
        val outcome = try {
            executor.run(argv)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            appendAudit(argvText, exitCode = null, verdict = SysAuditLog.VERDICT_FENCE_DENIED, output = "")
            return ItemResult(argvText, exitCode = null, output = "", failureReason = e.message ?: "argv 被执行面策略拒绝")
        } catch (e: IOException) {
            appendAudit(argvText, exitCode = null, verdict = SysAuditLog.VERDICT_FAILED, output = "")
            return ItemResult(argvText, exitCode = null, output = "", failureReason = "命令启动失败（本机未安装或不可执行）")
        }
        if (outcome.timedOut) {
            appendAudit(argvText, outcome.exitCode, SysAuditLog.VERDICT_FAILED, outcome.output)
            return ItemResult(argvText, outcome.exitCode, outcome.output, failureReason = "超时未完成，已被终止")
        }
        appendAudit(argvText, outcome.exitCode, SysAuditLog.VERDICT_STARTUP_PROBE, outcome.output)
        return ItemResult(argvText, outcome.exitCode, outcome.output, failureReason = null)
    }

    private suspend fun appendAudit(argvText: String, exitCode: Int?, verdict: String, output: String) {
        audit.append(
            SysAuditRecord(
                ts = sysAuditTimestamp(),
                tool = AUDIT_TOOL,
                action = argvText,
                verdict = verdict,
                exitCode = exitCode,
                trigger = AUDIT_TRIGGER,
                outputSummary = SysOutputSanitizer.sanitize(output)
                    .lineSequence().filter { it.isNotBlank() }.joinToString(" ")
                    .take(SysAuditLog.SUMMARY_MAX_CHARS)
                    .ifBlank { null },
            ),
        )
    }

    // ── 模板消息渲染（脱敏 + 整体上界收口） ──

    private fun renderAnomalies(
        anomalies: List<String>,
        evidence: List<String>,
        failures: List<String>,
        restLines: List<String>,
        osContext: String?,
        desktopContext: String?,
    ): String = buildString {
        appendLine("【Toolbelt 启动自检】发现 ${anomalies.size} 项机器健康异常：")
        anomalies.forEachIndexed { i, line ->
            appendLine("${i + 1}. $line")
        }
        evidence.forEach { block ->
            appendLine()
            append(block)
        }
        if (failures.isNotEmpty()) {
            appendLine()
            appendLine("[探查未完成 ${failures.size} 项（如实报告）]")
            failures.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("[机器环境]")
        appendLine("- 发行版：$osContext")
        appendLine("- 桌面会话：$desktopContext")
        if (restLines.isNotEmpty()) {
            appendLine()
            appendLine("[其余探查项]")
            restLines.forEach { appendLine("- $it") }
        }
        appendLine()
        append("（本消息由桥接启动时一次性自检触发，非定时任务；输出已脱敏。）")
    }.let { LogStatsDiffTools.capAt(it, MESSAGE_MAX_CHARS) }

    private fun renderFailures(failures: List<String>): String = buildString {
        appendLine("【Toolbelt 启动自检】探查未能完成（如实报告，机器健康结论未覆盖）：")
        failures.forEach { appendLine("- $it") }
        appendLine()
        append("（本消息由桥接启动时一次性自检触发，非定时任务。）")
    }.let { LogStatsDiffTools.capAt(it, MESSAGE_MAX_CHARS) }

    /** 服务项未判异常时的明细注（未安装/inactive/失败如实区分——inactive 是设计态不是失败态） */
    private fun serviceRestNote(result: ItemResult): String = when {
        result.failureReason != null -> "执行失败"
        result.output.lowercase().contains("could not be found") -> "单元未安装"
        else -> "非 failed 态"
    }

    /** 异常项证据块：argv + 退出码 + 脱敏输出摘要（单项截 [ITEM_EXCERPT_MAX_CHARS]） */
    private fun evidenceBlock(argvText: String, exitCode: Int?, output: String): String {
        val sanitized = SysOutputSanitizer.sanitize(output).trim()
        val excerpt = if (sanitized.isBlank()) "（无输出）" else sanitized.take(ITEM_EXCERPT_MAX_CHARS)
        return buildString {
            appendLine("   argv: $argvText（exit=$exitCode）")
            append("   输出（脱敏摘要）：")
            append(excerpt.lineSequence().joinToString("\n   "))
        }
    }

    /** 语境/其余项单行渲染：argv + 退出码 + 注（语境无退出码语义，失败原因如实） */
    private fun detailLine(result: ItemResult, note: String): String =
        "${result.argvText}：exit=${result.exitCode ?: "-"}（$note" +
            (result.failureReason?.let { "：$it" } ?: "") + "）"

    /** 语境行（发行版/桌面会话）：失败如实、空输出如实、多行折叠为分号连接 */
    private fun contextLine(result: ItemResult): String = when {
        result.failureReason != null -> "探查失败（${result.failureReason}）"
        else -> {
            val sanitized = SysOutputSanitizer.sanitize(result.output)
            sanitized.lineSequence().filter { it.isNotBlank() }.joinToString("；")
                .ifBlank { "（无输出）" }
                .take(CONTEXT_MAX_CHARS)
        }
    }

    /** 输出第二非空行（im-config -m 的 active configuration (user) 行；不足两行 = null） */
    private fun secondNonBlankLine(output: String): String? =
        output.lineSequence().filter { it.isNotBlank() }.elementAtOrNull(1)?.trim()

    companion object {
        /** sys-audit 工具名（聚合面按它计数；与 sys.* 工具名同族形态，非 agent 工具目录成员） */
        const val AUDIT_TOOL: String = "startup.probe"

        /** sys-audit 触发者标识（区别于桥接消息形态的 chatId+senderOpenId） */
        const val AUDIT_TRIGGER: String = "startup"

        /** 模板消息整体上界（字符）——出站文本真门禁（web.fetch/sys.probe 同档 8K） */
        const val MESSAGE_MAX_CHARS: Int = 8192

        /** 单条异常项输出摘要上界（字符） */
        const val ITEM_EXCERPT_MAX_CHARS: Int = 600

        /** 语境单行上界（字符） */
        const val CONTEXT_MAX_CHARS: Int = 600

        /**
         * 用户级关键服务探查集（每个都必须在 [SysProbeTools.SERVICE_WHITELIST] 内——
         * 运行时过滤兜底：白名单收编时对应服务静默退出探查集，绝不绕过白名单直跑）。
         * fcitx5 单元在多数发行版由会话脚本拉起而非 user unit（未装载 = 跳过不判异常），
         * 收进探查集覆盖「单元已装载且 failed」的发行版形态。
         */
        val STARTUP_PROBE_SERVICES: List<String> =
            listOf("fcitx5", "pipewire", "wireplumber", "xdg-desktop-portal")
                .filter { it in SysProbeTools.SERVICE_WHITELIST }

        /**
         * 服务异常判则：systemctl status 输出的 `Active: failed` 行（唯一确定性失败态）。
         * 退出码不参与判则（inactive 与 failed 同为非零，版本间语义有漂移）。
         */
        internal val SERVICE_FAILED_REGEX: Regex = Regex("""(?m)^\s*Active:\s+failed\b""")

        /** 输入法期望武装值：im-config -m 第二非空行（active configuration (user)）== 此值 */
        internal const val IM_CONFIGURED_FRAMEWORK: String = "fcitx5"
    }
}
