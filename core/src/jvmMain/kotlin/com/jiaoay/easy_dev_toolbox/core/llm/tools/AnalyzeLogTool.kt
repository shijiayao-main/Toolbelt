package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.log.pipeline.LogAnalysisPipeline
import com.jiaoay.log.pipeline.LogAnalysisPipeline.StageResult
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamSpec
import com.jiaoay.easy_dev_toolbox.core.navigation.ParamType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 日志分析确定性管线的 agent 工具面。
 *
 * ## 定位（G1 裁定：中间结果不穿模型）
 * `analyze_log` 把 skill 时代的概率性流程（prompt 记步骤）升级为**确定性代码流程**：
 * - 管线内部逐级执行（解密 → 时间过滤 + 空结果守护 → 环境扫描 → Tag 过滤），
 *   **中间产物不回传 LLM**——只有最终产物路径 + 附注返回（skill 时代 token 15 万 → 2 千
 *   的实测收益来源）；
 * - 中止语义显式化：`Abort` 带阶段标记与指引（回捞/反查），LLM 拿到的是「哪一步、为什么停、
 *   下一步怎么办」而不是半截日志；
 * - 语义研判（Step 6 十铁律）**不在此工具**——归根因研判 agent 的系统提示词。
 *
 * ## 与管线分步 API 的关系
 * 工具面向 LLM 只暴露**一次性全跑**（[Args.stage] 默认 ALL）+ 单级重跑（REDECRYPT/
 * RETAG——中止后修正参数重入）；分步全暴露会诱导 LLM 把每级产物读回上下文（正是
 * 「中间结果不穿模型」要消灭的形态）。
 */
class AnalyzeLogTool(
    private val context: ToolExecutionContext,
    sink: ToolResultSink = ToolResultSink.NOOP,
    private val privateKeyProvider: suspend () -> String,
) : StructuredSimpleTool<AnalyzeLogTool.Args>(
    sink = sink,
    argsType = typeToken<Args>(),
    name = NAME,
    description = "Run the deterministic log analysis pipeline on a log file: decrypt (.alog/.xlog → .txt, " +
        "reusing existing product if present) → time-window filter (with empty-result guard) → " +
        "environment scan (debug/mock injection detection) → tag filter. Only the final artifact path " +
        "and stage notes return to you — intermediate products never enter your context. " +
        "If a stage aborts, fix the cause (e.g. salvage-verify the file choice, or lookup actual TAG " +
        "spellings via knowledge.lookup_tag) and re-run the specific stage.",
) {
    @Serializable
    data class Args(
        @property:LLMDescription("Absolute path of the log file (.alog/.xlog encrypted, or .txt plain)")
        val log_path: String,
        @property:LLMDescription("Target time point, format yyyy-MM-dd_HH:mm:ss.SSS")
        val target_time: String,
        @property:LLMDescription("Time window in minutes (symmetric ±range around target)")
        val range_minutes: Long,
        @property:LLMDescription(
            "Merged tag regex like 'TagA|TagB' from knowledge docs; empty = skip tag filter stage",
        )
        val tag_regex: String = "",
        @property:LLMDescription("Tag name for output file naming; empty = derived from regex")
        val tag_name: String = "",
        @property:LLMDescription(
            "Debug/mock injection pattern for environment scan (from knowledge base); empty = skip env scan",
        )
        val env_pattern: String = "",
        @property:LLMDescription(
            "Stage to run: ALL (default) | REDECRYPT | RETAG — use REDECRYPT/RETAG to re-enter " +
                "a single stage after fixing an abort cause",
        )
        val stage: String = STAGE_ALL,
    )

    override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
        val logFile = context.authorizeWithGate(args.log_path)
            ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
        if (args.stage == STAGE_REDECRYPT) {
            // 单级重入：解密（授权后产物登记——后续级可继续访问）
            return@withContext stageResult(
                LogAnalysisPipeline.runDecrypt(logFile, privateKeyProvider()),
                register = true,
            )
        }
        if (args.stage == STAGE_RETAG) {
            val timeFiltered = context.authorizeWithGate(args.log_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (args.tag_regex.isBlank()) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "RETAG 级需要 tag_regex——先经知识库反查合并 Tag")
            }
            return@withContext stageResult(
                LogAnalysisPipeline.runTagFilter(timeFiltered, args.tag_regex, args.tag_name),
                register = true,
            )
        }

        // 一次性全跑（管线内部产物不回传——只落最终 artifact）
        val result = LogAnalysisPipeline.run(
            LogAnalysisPipeline.Request(
                logFile = logFile,
                privateKey = privateKeyProvider(),
                targetTime = args.target_time,
                rangeMinutes = args.range_minutes,
                tagRegex = args.tag_regex.ifBlank { null },
                tagName = args.tag_name.ifBlank { null },
                envPattern = args.env_pattern.ifBlank { null },
            ),
        )
        stageResult(result, register = true)
    }

    /** 阶段结果 → ToolResult 三段式（产物登记围栏 + artifacts 精确引用） */
    private fun stageResult(result: StageResult, register: Boolean): ToolResult = when (result) {
        is StageResult.Ok -> {
            result.artifact?.let { if (register) context.register(it) }
            ToolResult.Ok(
                summary = "[${result.stage}] ${result.note}" +
                    (result.artifact?.let { " → ${it.absolutePath}" } ?: ""),
                artifacts = result.artifact?.let {
                    listOf(ArtifactRef(path = it.absolutePath, kind = ArtifactKind.FILE, sizeBytes = it.length()))
                } ?: emptyList(),
            )
        }
        is StageResult.Abort ->
            ToolResult.Err(
                code = ToolResult.ErrCode.FAILED,
                message = "管线中止于 [${result.stage}]：${result.reason}",
                hint = when (result.stage) {
                    LogAnalysisPipeline.Stage.DECRYPT -> "确认文件路径与私钥（XLog 解密页可配）"
                    LogAnalysisPipeline.Stage.TIME_FILTER ->
                        "执行定位校验回捞：在模块目录相邻小时文件 grep 目标时间戳，" +
                            "命中=选错文件（换文件重跑），全不命中=日志轮换丢失（向用户如实报告）"
                    LogAnalysisPipeline.Stage.ENV_SCAN -> null
                    LogAnalysisPipeline.Stage.TAG_FILTER ->
                        "用 knowledge.lookup_tag 反查 TAG 实际落盘形式（截断/前缀变体）后重跑 RETAG 级"
                },
            )
    }

    companion object {
        const val NAME = "analyze_log"
        const val STAGE_ALL = "ALL"
        const val STAGE_REDECRYPT = "REDECRYPT"
        const val STAGE_RETAG = "RETAG"
    }
}

// ── AgentToolCatalog 描述符（P4-A：log.analyze_log——点分命名空间 log.*） ──

/** `analyze_log` 目录描述符（agent 装配面——两链通用） */
val ANALYZE_LOG: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
    name = "log.analyze_log",
    description = "Deterministic log analysis pipeline (decrypt → time filter → env scan → tag filter); " +
        "intermediate products never enter the model context",
    risk = AgentToolCatalog.Risk.DESTRUCTIVE, // 写产物文件（.txt/.filter-*.txt）
    aliases = setOf(AnalyzeLogTool.NAME),
    uiHint = AgentToolCatalog.UiHint(
        title = "日志分析管线",
        keywords = listOf("日志", "分析", "log", "analyze", "管线"),
        description = "确定性日志分析管线（解密→时间过滤→环境扫描→Tag 过滤）",
    ),
    params = listOf(
        ParamSpec(
            id = "log_path",
            label = "日志文件路径",
            type = ParamType.FILE,
            placeholder = ".alog/.xlog 加密或 .txt 纯文本绝对路径",
        ),
        ParamSpec(
            id = "target_time",
            label = "目标时间点",
            type = ParamType.TEXT,
            placeholder = "yyyy-MM-dd_HH:mm:ss.SSS",
        ),
        ParamSpec(id = "range_minutes", label = "前后窗口（分钟）", type = ParamType.NUMBER),
        ParamSpec(id = "tag_regex", label = "Tag 正则（可选）", type = ParamType.TEXT, required = false, placeholder = "知识库合并 Tag；留空跳过 Tag 过滤级"),
        ParamSpec(id = "tag_name", label = "输出标记（可选）", type = ParamType.TEXT, required = false),
        ParamSpec(id = "env_pattern", label = "环境扫描模式（可选）", type = ParamType.TEXT, required = false, placeholder = "debug/mock 注入特征正则；留空跳过环境扫描级"),
        ParamSpec(
            id = "stage",
            label = "执行阶段",
            type = ParamType.CHOICE,
            default = AnalyzeLogTool.STAGE_ALL,
            options = listOf(AnalyzeLogTool.STAGE_ALL, AnalyzeLogTool.STAGE_REDECRYPT, AnalyzeLogTool.STAGE_RETAG),
        ),
    ),
) { context, sink, config ->
    val key = config.privateKeyProvider ?: return@AgentToolDescriptor null
    AnalyzeLogTool(context, sink, key)
}
