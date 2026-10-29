package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.ArgvProcessRunner
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * git 只读工具：`git.read`——action 枚举（log / diff / blame / status），恒注
 * `--no-pager`，工作区根内仓库查询。
 *
 * ## 安全语义
 * - 只读零副作用（无确认卡；域准入与 ws.* 共用 [WsSessionGate] 的同一信任名单判定——
 *   名单内 git.read 与 ws.* 同时可用、名单外同时进不了域；ws 的写/执行动作对信任用户
 *   同样免卡）；
 * - [GitAction.diff] 的可选 path 参数过任务围栏（authorizeWithGate）——
 *   围栏外路径的 diff 拒收（工作区内文件内容是敏感面，围栏语义与读类工具一致）；
 * - 输出恒脱敏 + 8K capAt 收口（git log/diff 可携带仓库内任意文本——含凭据形态时
 *   确认卡/回执不得原文外泄）；
 * - 非零退出码是数据不是失败（无 .git 目录 / 无该文件历史时如实转述）。
 *
 * ## 装配门控
 * 工具本体与目录注册即收口三张守护网；装配门控 = config.gitSessionGate 非 null
 * （工厂返 null 不注册），恒不入常备清单。gitEnabled 配置开关（fail-closed 默认关）
 * 由装配链在 gate 之外前置判定——把整个仓库历史喂 LLM 是数据暴露面，只读也要开关。
 */
object GitReadTools {

    const val GIT_READ_NAME = "git.read"

    /** 回执长度结构性上界（字符）——sys 家族同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** git log 行数钳制上限（行） */
    internal const val LOG_MAX_LINES = 200

    /** git 只读动作目录（编译期锁死）：恒注 --no-pager；扩项走提案评审 */
    enum class GitAction {
        LOG,
        DIFF,
        BLAME,
        STATUS,
        ;

        /** LLM 入参形态的动作名（enum name 小写投影） */
        val id: String get() = name.lowercase()
    }

    // ── 工具：git.read ──

    class GitReadTool(
        private val context: ToolExecutionContext,
        private val gate: WsSessionGate,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val audit: SysAuditLog = SysAuditLog(),
    ) : StructuredSimpleTool<GitReadTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = GIT_READ_NAME,
        description = "Read-only git queries (log / diff / blame / status) against the git " +
            "repository at the configured workspace root. Pagers are always disabled and " +
            "output is capped. For diff you may pass one optional path inside the repo. " +
            "No mutations are ever performed (no commit / push / checkout — those are " +
            "permanently outside this tool).",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Query action. One of: log (recent commits), diff (working tree diff), " +
                    "blame (annotate file lines), status (working tree status).",
            )
            val action: String,
            @property:LLMDescription(
                "Optional file path for diff/blame, relative to the workspace root " +
                    "(e.g. src/main.kt). Ignored for log/status.",
            )
            val path: String? = null,
        )

        private data class Queried(
            val result: ToolResult,
            val action: String,
            val verdictOverride: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val queried = executeQuery(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = GIT_READ_NAME,
                    action = queried.action,
                    verdict = queried.verdictOverride ?: sysAuditVerdict(queried.result),
                    exitCode = null,
                    trigger = gate.trigger,
                    outputSummary = sysAuditSummary(queried.result),
                ),
            )
            return queried.result
        }

        private suspend fun executeQuery(args: Args): Queried {
            if (!gate.requireSessionAuthorization(GIT_READ_NAME)) {
                return Queried(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获工作区（ws.*）授权（用户未批准或确认超时）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」。",
                    ),
                    action = "git:${args.action}（会话授权拒绝）",
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val action = GitAction.entries.firstOrNull { it.id == args.action.trim().lowercase() }
                ?: return Queried(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "未知动作「${args.action}」。可选动作：${GitAction.entries.joinToString(" / ") { it.id }}。" +
                            "写操作（commit/push/checkout 等）永久不在本工具目录内。",
                    ),
                    action = "git:${args.action}（动作未命中）",
                )
            val cwd = gate.wsRoot
            if (!cwd.isDirectory) {
                return Queried(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "工作区根「${cwd.path}」不存在或不是目录——查询无法执行",
                    ),
                    action = "git:${action.id}（工作区缺失）",
                )
            }
            if (!File(cwd, ".git").exists()) {
                return Queried(
                    ToolResult.Err(
                        ToolResult.ErrCode.NOT_FOUND,
                        "工作区根「${cwd.path}」不是 git 仓库（未发现 .git）——无法执行 git 查询。" +
                            "请如实告知用户该目录不是仓库",
                    ),
                    action = "git:${action.id}（非 git 仓库）",
                )
            }
            // diff/blame 的 path 参数过任务围栏（工作区内文件内容是敏感面）
            var authorizedPath: File? = null
            if (action == GitAction.DIFF || action == GitAction.BLAME) {
                val raw = args.path?.trim().orEmpty()
                if (raw.isNotEmpty()) {
                    val target = File(cwd, raw)
                    val authorized = context.authorizeWithGate(target.path)
                        ?: return Queried(
                            ToolResult.Err(
                                ToolResult.ErrCode.DENIED,
                                "路径「${target.path}」未获任务围栏放行（只能查询已获授权的路径）",
                            ),
                            action = "git:${action.id}:$raw（围栏拒绝）",
                            verdictOverride = SysAuditLog.VERDICT_FENCE_DENIED,
                        )
                    // canonical 包含性（工作区内符号链接穿越拒收）
                    val inside = runCatching {
                        val rootCanon = cwd.canonicalPath
                        val targetCanon = authorized.canonicalPath
                        targetCanon == rootCanon || targetCanon.startsWith(rootCanon + File.separator)
                    }.getOrDefault(false)
                    if (!inside) {
                        return Queried(
                            ToolResult.Err(
                                ToolResult.ErrCode.DENIED,
                                "路径「${authorized.path}」解析后落在工作区根之外（符号链接穿越拒收）。",
                            ),
                            action = "git:${action.id}:$raw（工作区根穿越拒绝）",
                            verdictOverride = SysAuditLog.VERDICT_FENCE_DENIED,
                        )
                    }
                    authorizedPath = authorized
                }
            }
            val argv = when (action) {
                GitAction.LOG -> listOf("git", "--no-pager", "log", "--oneline", "-n", LOG_MAX_LINES.toString())
                GitAction.DIFF -> listOf("git", "--no-pager", "diff") +
                    (authorizedPath?.let { listOf("--", it.absolutePath) } ?: emptyList())
                GitAction.BLAME -> {
                    if (authorizedPath == null) {
                        return Queried(
                            ToolResult.Err(
                                ToolResult.ErrCode.BAD_ARGS,
                                "blame 需要 path 参数（仓库内相对路径）",
                            ),
                            action = "git:blame（缺 path）",
                        )
                    }
                    listOf("git", "--no-pager", "blame", "--", authorizedPath.absolutePath)
                }
                GitAction.STATUS -> listOf("git", "--no-pager", "status", "--short")
            }
            val actionLabel = "git:${action.id}" + (authorizedPath?.let { ":${args.path}" } ?: "")
            return withContext(Dispatchers.IO) {
                val runner = ArgvProcessRunner()
                val outcome: ArgvOutcome = try {
                    runner.run(argv = argv, cwd = cwd)
                } catch (e: IllegalArgumentException) {
                    return@withContext Queried(
                        ToolResult.Err(ToolResult.ErrCode.DENIED, e.message ?: "argv 拒收"),
                        action = "$actionLabel（argv 拒收）",
                        verdictOverride = SysAuditLog.VERDICT_FENCE_DENIED,
                    )
                } catch (e: java.io.IOException) {
                    return@withContext Queried(
                        ToolResult.Err(
                            ToolResult.ErrCode.NOT_FOUND,
                            "无法启动 git（${e.message ?: e.javaClass.simpleName}）——git 可能未安装",
                        ),
                        action = "$actionLabel（启动失败）",
                        verdictOverride = SysAuditLog.VERDICT_FAILED,
                    )
                }
                Queried(renderReceipt(action.id, cwd.path, outcome), action = actionLabel)
            }
        }

        /** 结构化回执：action/exit/输出（脱敏 + 8K capAt 收口）；非零退出码是数据不是失败 */
        private fun renderReceipt(actionId: String, repoPath: String, outcome: ArgvOutcome): ToolResult {
            val output = SysOutputSanitizer.sanitize(outcome.output.take(MAX_OUTPUT_CHARS))
            val body = buildString {
                appendLine("action=git:$actionId")
                appendLine("repo=$repoPath")
                appendLine("exit=${outcome.exitCode}")
                if (outcome.outputTruncated) appendLine("outputTruncated=true（输出超捕获上界，只含前缀）")
                appendLine("--- output ---")
                append(output)
            }
            val capped = LogStatsDiffTools.capAt(body, MAX_OUTPUT_CHARS)
            return ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.GitReadPayload(
                    action = actionId,
                    repoPath = repoPath,
                    exitCode = outcome.exitCode,
                    truncated = capped != body,
                ),
            )
        }
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `git.read` 目录描述符。risk = READ_ONLY；装配门控：config.gitSessionGate 非 null
     * 才注册（工厂返 null），恒不入常备清单。
     */
    val GIT_READ: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = GIT_READ_NAME,
        description = "Read-only git queries (log/diff/blame/status) on the workspace repo — " +
            "no-pager always, output capped, mutations permanently excluded",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        factory = { context, sink, config ->
            config.gitSessionGate?.let { gate ->
                GitReadTool(context, gate, sink)
            }
        },
    )
}
