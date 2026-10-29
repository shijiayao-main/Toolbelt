package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.system.ArgvOutcome
import com.jiaoay.easy_dev_toolbox.core.system.ArgvProcessRunner
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditRecord
import com.jiaoay.easy_dev_toolbox.core.system.SysOutputSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 受限任务执行工具：`ws.run_task`——LLM 只能选编译期任务配方（[TaskRecipe] 枚举），
 * 不能造任意命令；构建/测试类长任务在工作区根内执行，每执行经确认卡人审。
 *
 * ## 配方目录（编译期锁死，扩项走提案评审）
 * 初始集 GRADLE / MAVEN / NPM / PNPM。**负面清单**：git 不入目录（git.read 只读工具
 * 承接）；python / node 解释器不入目录（解释器 = 任意代码执行，argv 白名单形同虚设）；
 * 提权/包管理黑名单由 [ArgvProcessRunner] 执行件恒拒兜底。
 *
 * ## 执行面（全部复用 sys.* 基建）
 * - [ArgvProcessRunner]：argv 纯语义 + 提权黑名单 + 进程树级联终止 + 有界捕获；
 * - cwd 恒 = 工作区根（[WsSessionGate.wsRoot] 装配注入，非 LLM 参数）；
 * - 超时：Args.timeout_minutes 钳制 1..min(配置上限, 0.8×整体预算)——0.8 系数镜像
 *   [AskCaps]（软超时必须先于 koogToolLoop 硬超时触发，倒置则任务被硬超时误杀）；
 * - 进度：[runTaskTap] 行旁路（喂空闲看门狗 touch + 尾部行进进度卡）——长任务
 *   「在跑但无事件」不被 120s 空闲看门狗误杀的活动信号通道；
 * - 输出：SysOutputSanitizer 脱敏 + 8K capAt 收口 + 全文落盘取回指针（超长输出
 *   经 OutputCapMiddleware 兜底驱逐）。
 *
 * ## 确认语义
 * 信任用户免卡直接执行；非信任用户进不了域（域准入即拒、到不了本面）——确认失败/超时
 * = 拒绝执行（fail-closed，装配兜底路径）。
 */
object WsRunTaskTools {

    const val RUN_TASK_NAME = "ws.run_task"

    /** 回执长度结构性上界（字符）——sys 家族同款 8K 真门禁 */
    internal const val MAX_OUTPUT_CHARS = 8192

    /** 行旁路最小间隔（ms）：限频防高输出任务刷爆进度卡 */
    internal const val TAP_THROTTLE_MILLIS = 500L

    /**
     * 任务配方目录（编译期锁死）：executable + 说明。LLM 只传 recipe 名 + args 尾参；
     * args 每元素过形态校验（无空白、无换行——argv 元素不承载控制字符）。
     */
    enum class TaskRecipe(internal val executable: String) {
        GRADLE("gradlew"),
        MAVEN("mvn"),
        NPM("npm"),
        PNPM("pnpm"),
        ;

        /** LLM 入参形态的配方名（enum name 小写投影） */
        val id: String get() = name.lowercase()
    }

    // ── 确认卡数据与授权面契约 ──

    /** 任务执行确认卡数据：完整 argv + cwd + 超时档（卡面单源） */
    data class RunTaskActionCard(
        val recipe: String,
        val argv: List<String>,
        val cwd: String,
        val timeoutMinutes: Int,
    )

    /**
     * ws.run_task 逐任务授权面（装配链侧契约）。[requireSessionAuthorization] = 域准入
     * （信任用户名单判定）；[requireActionApproval] 对信任用户免卡直接放行；非信任用户
     * 到不了本卡，两卡路径仅作装配兜底。
     */
    interface WsRunTaskGate : WsSessionGate {

        /**
         * 整体预算供体（毫秒）——软超时 0.8 系数的换算输入（装配链注入当次任务的
         * overallTimeoutMillis；缺席时按默认档）。
         */
        val overallTimeoutMillisProvider: () -> Long

        /**
         * 任务超时配置上限（分钟）——装配链从桥接配置 agent.wsTools.runTaskTimeoutMinutes
         * 现读注入；Args.timeout_minutes 钳制上界。
         */
        val configuredTimeoutMinutes: Int

        /**
         * 要求本次执行的用户确认（挂起直到裁决/超时）。
         *
         * @return true = 用户批准本次执行；false = 拒绝/超时/说明卡发送失败（fail-closed）
         */
        suspend fun requireActionApproval(card: RunTaskActionCard): Boolean
    }

    // ── 工具：ws.run_task ──

    class WsRunTaskTool(
        private val context: ToolExecutionContext,
        private val gate: WsRunTaskGate,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val audit: SysAuditLog = SysAuditLog(),
        /** 行旁路（进度喂入：装配链注入 touch + 尾部行进窗；null = 不旁路，仅测试形态） */
        private val runTaskTap: ((String) -> Unit)? = null,
    ) : StructuredSimpleTool<WsRunTaskTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = RUN_TASK_NAME,
        description = "Run a whitelisted BUILD/TEST task (gradle / maven / npm / pnpm) in the " +
            "configured workspace root. You pick a compile-time recipe name plus task " +
            "arguments (e.g. recipe=gradle, args=[\"test\"]); arbitrary commands are never " +
            "accepted. Each run requires a per-run confirmation card showing the full argv, " +
            "working directory and timeout. Long-running output streams to the user's progress " +
            "card. The working directory is always the workspace root.",
    ) {

        @Serializable
        data class Args(
            @property:LLMDescription(
                "Build recipe name. One of: gradle, maven, npm, pnpm. Never invent other names.",
            )
            val command: String,
            @property:LLMDescription(
                "Task arguments passed to the build tool, e.g. [\"test\"] or [\"run\", \"build\"]. " +
                    "Each argument must be a plain token without spaces or newlines.",
            )
            val args: List<String> = emptyList(),
            @property:LLMDescription(
                "Timeout in minutes (1..configured cap). The run is killed at the deadline " +
                    "(process-tree cascade). Default 10.",
            )
            val timeout_minutes: Int = DEFAULT_TIMEOUT_MINUTES,
        ) {
            companion object {
                const val DEFAULT_TIMEOUT_MINUTES = 10
            }
        }

        private data class Executed(
            val result: ToolResult,
            val action: String,
            val verdictOverride: String? = null,
        )

        override suspend fun executeTyped(args: Args): ToolResult {
            val executed = executeTask(args)
            audit.append(
                SysAuditRecord(
                    ts = sysAuditTimestamp(),
                    tool = RUN_TASK_NAME,
                    action = executed.action,
                    verdict = executed.verdictOverride ?: sysAuditVerdict(executed.result),
                    exitCode = null,
                    trigger = gate.trigger,
                    outputSummary = sysAuditSummary(executed.result),
                ),
            )
            return executed.result
        }

        private suspend fun executeTask(args: Args): Executed {
            if (!gate.requireSessionAuthorization(RUN_TASK_NAME)) {
                return Executed(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "本会话未获工作区（ws.*）授权（发送者不在信任用户名单）——请如实告知用户" +
                            "需先由用户在桥接设置页把其 openId 加入「信任用户」，禁止编造执行结果。",
                    ),
                    action = "run:${args.command}（会话授权拒绝）",
                    verdictOverride = SysAuditLog.VERDICT_SESSION_DENIED,
                )
            }
            val recipe = TaskRecipe.entries.firstOrNull { it.id == args.command.trim().lowercase() }
                ?: return Executed(
                    ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "未知任务配方「${args.command}」。可选配方：${TaskRecipe.entries.joinToString(" / ") { it.id }}。" +
                            "任意命令、解释器（python/node）与 git 均不在目录内——git 查询走 git.read。",
                    ),
                    action = "run:${args.command}（配方未命中）",
                )
            // argv 元素形态校验：无空白/换行（argv 元素不承载控制字符；执行件另有空白元素恒拒）
            args.args.forEachIndexed { i, token ->
                if (token.isBlank() || token.contains('\n') || token.contains('\r')) {
                    return Executed(
                        ToolResult.Err(
                            ToolResult.ErrCode.BAD_ARGS,
                            "args[$i] 含空白/换行，拒收——每个参数必须是单个纯 token",
                        ),
                        action = "run:${recipe.id}（参数形态拒收）",
                    )
                }
            }
            // 超时钳制：1..min(配置上限, 0.8×整体预算换算分钟)
            val overallMillis = gate.overallTimeoutMillisProvider()
            val softCapMinutes = if (overallMillis > 0) {
                (overallMillis * 8 / 10 / 60_000L).toInt().coerceAtLeast(1)
            } else {
                Int.MAX_VALUE
            }
            val timeoutMinutes = args.timeout_minutes.coerceIn(1, minOf(gate.configuredTimeoutMinutes, softCapMinutes))
            val argv = listOf(recipe.executable) + args.args
            val action = "run ${recipe.id} ${args.args.joinToString(" ")}".trim()
            val cwd = gate.wsRoot
            if (!cwd.isDirectory) {
                return Executed(
                    ToolResult.Err(
                        ToolResult.ErrCode.FAILED,
                        "工作区根「${cwd.path}」不存在或不是目录——任务无法启动",
                    ),
                    action = "$action（工作区缺失）",
                )
            }

            // 逐任务确认卡：完整 argv + cwd + 超时档
            val card = RunTaskActionCard(
                recipe = recipe.id,
                argv = argv,
                cwd = cwd.path,
                timeoutMinutes = timeoutMinutes,
            )
            if (!gate.requireActionApproval(card)) {
                return Executed(
                    ToolResult.Err(
                        ToolResult.ErrCode.DENIED,
                        "任务「$action」未经用户确认（拒绝/超时/说明卡发送失败）——未执行。" +
                            "请如实告知用户，禁止未经确认重试。",
                    ),
                    action = action,
                    verdictOverride = SysAuditLog.VERDICT_ACTION_DENIED,
                )
            }

            // 行旁路限频：每 TAP_THROTTLE_MILLIS 至多透传一行尾部行（touch + 进度窗喂入由装配链实现）
            var lastTapAt = 0L
            val onLine: ((String) -> Unit)? = runTaskTap?.let { tap ->
                { line ->
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt >= TAP_THROTTLE_MILLIS) {
                        lastTapAt = now
                        runCatching { tap(line.take(200)) }
                    }
                }
            }

            val runner = ArgvProcessRunner(
                timeoutMillis = timeoutMinutes * 60_000L,
                // 捕获上界放宽：构建日志可达数 MB；回执渲染层另有 8K 收口
                captureLimitChars = ArgvProcessRunner.DEFAULT_CAPTURE_LIMIT_CHARS,
            )
            return withContext(Dispatchers.IO) {
                val outcome: ArgvOutcome = try {
                    runner.run(argv = argv, cwd = cwd, onLine = onLine)
                } catch (e: IllegalArgumentException) {
                    return@withContext Executed(
                        ToolResult.Err(ToolResult.ErrCode.DENIED, e.message ?: "argv 拒收"),
                        action = "$action（argv 拒收）",
                        verdictOverride = SysAuditLog.VERDICT_FENCE_DENIED,
                    )
                } catch (e: java.io.IOException) {
                    return@withContext Executed(
                        ToolResult.Err(
                            ToolResult.ErrCode.NOT_FOUND,
                            "无法启动「${recipe.executable}」（${e.message ?: e.javaClass.simpleName}）——" +
                                "构建工具可能未安装或不在 PATH 内",
                        ),
                        action = "$action（启动失败）",
                        verdictOverride = SysAuditLog.VERDICT_FAILED,
                    )
                }
                Executed(renderReceipt(action, timeoutMinutes, outcome), action = action)
            }
        }

        /** 结构化回执：action/timeout/exit/输出尾部（脱敏 + 8K capAt 收口） */
        private fun renderReceipt(action: String, timeoutMinutes: Int, outcome: ArgvOutcome): ToolResult {
            val tail = SysOutputSanitizer.sanitize(outcome.output.takeLast(MAX_OUTPUT_CHARS / 2))
            val body = buildString {
                appendLine("action=$action")
                appendLine("timeout=${timeoutMinutes}min")
                appendLine("exit=${outcome.exitCode}${if (outcome.timedOut) "（超时被杀）" else ""}")
                if (outcome.outputTruncated) appendLine("outputTruncated=true（输出超捕获上界，只含前缀）")
                appendLine("--- output tail ---")
                append(tail)
            }
            val capped = LogStatsDiffTools.capAt(body, MAX_OUTPUT_CHARS)
            return ToolResult.Ok(
                summary = capped.trim(),
                data = ToolPayload.RunTaskPayload(
                    action = action,
                    exitCode = outcome.exitCode,
                    timedOut = outcome.timedOut,
                ),
            )
        }
    }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /**
     * `ws.run_task` 目录描述符。risk = DESTRUCTIVE（执行面）；装配门控：config.wsRunTaskGate
     * 非 null 才注册（工厂返 null），恒不入常备清单。
     */
    val WS_RUN_TASK: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = RUN_TASK_NAME,
        description = "Run a whitelisted build/test task (gradle/maven/npm/pnpm) in the workspace " +
            "root — compile-time recipe only, per-run confirmation card, long-run output streams " +
            "to the progress card",
        risk = AgentToolCatalog.Risk.DESTRUCTIVE,
        factory = { context, sink, config ->
            config.wsRunTaskGate?.let { gate ->
                WsRunTaskTool(context, gate, sink, runTaskTap = config.runTaskTap)
            }
        },
    )
}
