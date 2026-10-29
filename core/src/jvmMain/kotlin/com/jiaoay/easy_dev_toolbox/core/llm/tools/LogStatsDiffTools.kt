package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.AgentToolDescriptor
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AgentToolCatalog.Risk
import com.jiaoay.log.ClusterResult
import com.jiaoay.log.LogCluster
import com.jiaoay.log.LogDiff
import com.jiaoay.log.LogStatsAnalyzer
import com.jiaoay.log.LogStatsResult
import com.jiaoay.log.LogViewer
import com.jiaoay.log.TemplateCluster
import com.jiaoay.log.ViewerLine
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 日志侦察四件（分布概览 log.stats + 双日志对比 log.diff + 窗口原文 log.view + 错误聚类
 * log.cluster）的 agent 工具面。
 *
 * 引擎 = :log-tools 纯函数（[LogStatsAnalyzer.analyze] / [LogDiff.diff] / [LogViewer] /
 * [LogCluster.analyze]），本层只负责围栏（authorizeWithGate）、结果渲染与输出有界化——
 * 四件均只读零产物（Risk.READ_ONLY，不写任何文件）。
 *
 * 研判动线定位：「统计→下钻→引用」——几万行日志先看分布（Tag 频次/级别/时间跨度）
 * 再定过滤策略；两份日志先对比找行为差异再下钻差异窗口；定位到行号后用 log.view
 * 引用原文（产物只有文件路径时，引用原文不必再经 fs 工具 + 路径授权两跳）；同型异常
 * 刷屏先看 log.cluster 的 top-N 模板（每类各刷多少次/首末在几点）定排查优先级。
 *
 * ## 输出长度硬上界（真门禁：超限即刷爆 agent 上下文，研判链不可用）
 * - stats：Tag 频次只取前 [TAG_TOP_N] 项、Tag 名截断 [TAG_NAME_MAX_CHARS] 字符，
 *   推导上界 ≈ 30 × 80 + 固定头部 ≈ 2900 字符；结构性兜底 [STATS_MAX_OUTPUT_CHARS] = 4096；
 * - diff：只渲染**首个** hunk，hunk 窗口截断 [DIFF_HUNK_LINES_MAX] 行、单行截断
 *   [DIFF_LINE_MAX_CHARS] 字符，推导上界 ≈ 40 × 202 + 固定头部 ≈ 8400 字符；
 *   结构性兜底 [DIFF_MAX_OUTPUT_CHARS] = 16384；
 * - view：窗口行数请求钳制 ≤ [LogViewer.MAX_WINDOW_LINES]、单行截断
 *   [VIEW_LINE_MAX_CHARS] 字符、正文按 [VIEW_BODY_BUDGET_CHARS] 字符预算逐行收窗
 *   （预算尽即停 + 续读指针——大窗口不靠整体截断静默丢尾）；最坏请求
 *   （5000 行超长行 ≈ 2.5M 字符）由预算收口，结构性兜底 [capAt] ≤
 *   [VIEW_MAX_OUTPUT_CHARS] = 16384（与 diff 同档：同为研判证据面，默认 50 行 ×
 *   典型日志行长下单窗完整返回）；
 * - cluster：模板条数请求钳制 ≤ [CLUSTER_TOP_N_MAX]、单模板截断
 *   [CLUSTER_TEMPLATE_MAX_CHARS] 字符、行号样例只取前 [CLUSTER_LINE_SAMPLES] 个，
 *   推导上界 ≈ 20 × 370 + 固定头部 ≈ 7600 字符；结构性兜底
 *   [CLUSTER_MAX_OUTPUT_CHARS] = 16384（与 diff/view 同档）；
 * - 兜底经 [capAt] 实现：返回长度恒 ≤ 上界，**不依赖推导成立**（推导漂移时截断
 *   仍生效，单测另以上界字面值独立断言）。
 *
 * ## 输出锚定引擎既有契约（不静默）
 * - stats 的「未识别」差值（头行解析不出 Tag/级别的块数）按引擎 KDoc 口径
 *   （totalBlocks 减对应合计）明示给 LLM；
 * - diff 的 [LogDiff.DiffResult.Ok.degraded]（编辑距离超 [LogDiff.MAX_ALIGN_EDITS]
 *   的整体替换退化）恒渲染成显式行，附「差异行数不可信」告警与下钻指引；
 * - view 的加密段结构拒绝（[LogViewer.OpenResult.Failure]）与查看期间漂移失效
 *   （readWindow 抛 ISE）如实折叠为 FAILED 文案，分别附先解密指引与重试指引；
 * - cluster 的会计恒等式（[ClusterResult.Ok] KDoc：candidateBlocks = 归并 + 溢出 +
 *   空头跳过 + 超长跳过）逐项渲染不丢项；行号引用截断
 *   （[TemplateCluster.lineNumbersTruncated]）与引擎相似度阈值单源
 *   （[LogCluster.DEFAULT_SIMILARITY_THRESHOLD]，工具面不暴露阈值参数——未实测校准
 *   的论文值不交给 LLM 调节，改默认值须经 LogCluster KDoc「阈值校准记录」流程）。
 */
object LogStatsDiffTools {

    const val STATS_NAME = "log.stats"
    const val DIFF_NAME = "log.diff"
    const val VIEW_NAME = "log.view"
    const val CLUSTER_NAME = "log.cluster"

    /** 四目录名集合——消费方 names 选择集单源引用（防字面漂移） */
    val NAMES: Set<String> = setOf(STATS_NAME, DIFF_NAME, VIEW_NAME, CLUSTER_NAME)

    /** stats：Tag 频次最多列出的条数（Top N 截断——输出有界化的主项） */
    internal const val TAG_TOP_N = 30

    /** stats：单条 Tag 名的显示截断长度（字符） */
    internal const val TAG_NAME_MAX_CHARS = 64

    /** stats：输出长度结构性上界（字符）——推导值见对象 KDoc */
    internal const val STATS_MAX_OUTPUT_CHARS = 4096

    /** diff：首 hunk 窗口最多渲染的行数 */
    internal const val DIFF_HUNK_LINES_MAX = 40

    /** diff：单行渲染截断长度（字符） */
    internal const val DIFF_LINE_MAX_CHARS = 200

    /** diff：输出长度结构性上界（字符）——推导值见对象 KDoc */
    internal const val DIFF_MAX_OUTPUT_CHARS = 16_384

    /** view：默认窗口行数（研判下钻的典型一屏量） */
    internal const val VIEW_DEFAULT_WINDOW_LINES = 50

    /** view：单行渲染截断长度（字符）——单条超长行不得独占输出预算 */
    internal const val VIEW_LINE_MAX_CHARS = 500

    /** view：输出长度结构性上界（字符）——推导与收窗形态见对象 KDoc */
    internal const val VIEW_MAX_OUTPUT_CHARS = 16_384

    /** view：正文行字符预算（总上界减头部/收尾行预留——正文 + 收尾恒 ≤ 总上界） */
    private const val VIEW_BODY_BUDGET_CHARS = VIEW_MAX_OUTPUT_CHARS - 256

    /** cluster：默认回报的模板条数（top-N 档） */
    internal const val CLUSTER_DEFAULT_TOP_N = 10

    /** cluster：回报模板数的请求上界（钳制档——top-N 截断明示剩余面） */
    internal const val CLUSTER_TOP_N_MAX = 20

    /** cluster：单模板串的显示截断长度（字符） */
    internal const val CLUSTER_TEMPLATE_MAX_CHARS = 200

    /** cluster：每模板呈现的块首行号样例数（1 基——log.view 续读锚） */
    internal const val CLUSTER_LINE_SAMPLES = 3

    /** cluster：输出长度结构性上界（字符）——推导值见对象 KDoc */
    internal const val CLUSTER_MAX_OUTPUT_CHARS = 16_384

    /** cluster：默认级别过滤集（错误聚类语义——levels 参数为空时回落此档） */
    private val DEFAULT_CLUSTER_LEVELS =
        listOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)

    /** 时间戳呈现格式（与工具链口径 yyyy-MM-dd_HH:mm:ss.SSS 一致） */
    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss.SSS")

    // ── 工具 1：分布概览（纯读、零产物） ──

    class StatsTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<StatsTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = STATS_NAME,
        description = "Scan a whole plain-text device log file and report a distribution overview: " +
            "total log blocks, per-level counts, tag frequency (top $TAG_TOP_N) and timestamp span. " +
            "Recon step: run this BEFORE choosing a tag regex or time window. Zero output files.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the plain-text log file (.txt)")
            val file_path: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            if (!input.isFile) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "文件不存在: ${input.absolutePath}",
                )
            }
            when (val result = LogStatsAnalyzer.analyze(input)) {
                is LogStatsResult.Ok -> ToolResult.Ok(renderStats(result, input.name))
                is LogStatsResult.Failure ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "统计失败: ${result.reason}")
            }
        }
    }

    // ── 工具 2：双日志对比（纯读、零产物） ──

    class DiffTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<DiffTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = DIFF_NAME,
        description = "Line-diff two plain-text log files and report only a bounded summary: total " +
            "hunk count, line counts of both sides, and the FIRST hunk (context/removed/added lines). " +
            "Set normalize_timestamps=true to treat lines differing only in the leading timestamp " +
            "as equal (compare behavior, not clocks). Zero output files.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the baseline (old) plain-text log file")
            val old_file_path: String,
            @property:LLMDescription("Absolute path of the comparison (new) plain-text log file")
            val new_file_path: String,
            @property:LLMDescription(
                "true = treat lines differing only in the leading timestamp as equal " +
                    "(recommended when comparing two captures of the same scene); false = exact compare",
            )
            val normalize_timestamps: Boolean = false,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val oldFile = context.authorizeWithGate(args.old_file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            val newFile = context.authorizeWithGate(args.new_file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            if (!oldFile.isFile) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "基准文件不存在: ${oldFile.absolutePath}",
                )
            }
            if (!newFile.isFile) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "对比文件不存在: ${newFile.absolutePath}",
                )
            }
            when (val result = LogDiff.diff(oldFile, newFile, args.normalize_timestamps)) {
                is LogDiff.DiffResult.Ok ->
                    ToolResult.Ok(renderDiff(result, oldFile.name, newFile.name, args.normalize_timestamps))
                is LogDiff.DiffResult.Failure ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "对比失败: ${result.reason}")
            }
        }
    }

    // ── 工具 3：窗口原文下钻（纯读、零产物） ──

    class ViewTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<ViewTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = VIEW_NAME,
        description = "Read a bounded window of raw log lines (with 1-based line numbers) from a " +
            "plain-text log file. Drill-down step: after log.stats/log.diff pinpoint a region, " +
            "cite the original lines here. window_lines is clamped to ${LogViewer.MAX_WINDOW_LINES} " +
            "and the output is char-capped (over-long single lines are truncated). Zero output files.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the plain-text log file (.txt)")
            val file_path: String,
            @property:LLMDescription("Line number to start from (1-based, matches the line numbers shown in the output)")
            val start_line: Int = 1,
            @property:LLMDescription(
                "Max lines to return per call (default $VIEW_DEFAULT_WINDOW_LINES, " +
                    "clamped to ${LogViewer.MAX_WINDOW_LINES})",
            )
            val window_lines: Int = VIEW_DEFAULT_WINDOW_LINES,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            if (!input.isFile) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "文件不存在: ${input.absolutePath}",
                )
            }
            if (args.start_line < 1 || args.window_lines <= 0) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "参数非法：start_line ≥ 1（1 基行号）且 window_lines > 0" +
                        "（实际 start_line=${args.start_line}, window_lines=${args.window_lines}）",
                )
            }
            when (val opened = LogViewer.open(input)) {
                is LogViewer.OpenResult.Failure ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "查看失败: ${opened.reason}")
                is LogViewer.OpenResult.Ok -> {
                    val session = opened.session
                    if (session.totalLines == 0) {
                        return@withContext ToolResult.Ok("（文件为空，0 行）")
                    }
                    if (args.start_line > session.totalLines) {
                        return@withContext ToolResult.Err(
                            ToolResult.ErrCode.BAD_ARGS,
                            "start_line ${args.start_line} 超出总行数 ${session.totalLines}" +
                                "（1 基，最大起始行 = ${session.totalLines}）",
                        )
                    }
                    val requested = minOf(args.window_lines, LogViewer.MAX_WINDOW_LINES)
                    try {
                        val window = session.readWindow(args.start_line - 1, requested)
                        ToolResult.Ok(
                            renderView(input.name, session.totalLines, args.start_line, requested, window),
                        )
                    } catch (e: IllegalStateException) {
                        // 漂移失效折叠人话提示：引擎 ISE 文案已含失效原因，此处只补「重试即重建」指引
                        ToolResult.Err(
                            ToolResult.ErrCode.FAILED,
                            "查看失败: ${e.message}",
                            hint = "重试本工具即可（重开会话自动重建索引）",
                        )
                    }
                }
            }
        }
    }

    // ── 工具 4：错误聚类（纯读、零产物） ──

    class ClusterTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
        private val fenceHint: String? = null,
    ) : StructuredSimpleTool<ClusterTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = CLUSTER_NAME,
        description = "Cluster same-shape error blocks of a plain-text log file into top-N " +
            "template summaries (Drain-style): template string (variables folded to <*>), count, " +
            "first/last timestamp and sample 1-based head line numbers. Default levels = E/F " +
            "(error/fatal); pass all six level letters to cluster every block. Recon step: run " +
            "this BEFORE deep analysis to prioritize which recurring anomaly to drill into. " +
            "Zero output files.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the plain-text log file (.txt)")
            val file_path: String,
            @property:LLMDescription(
                "Level letters of block heads to cluster, e.g. [\"E\",\"F\"]. " +
                    "Empty = default [\"E\",\"F\"] (error/fatal); " +
                    "pass [\"V\",\"D\",\"I\",\"W\",\"E\",\"F\"] to cluster every block",
            )
            val levels: List<String> = emptyList(),
            @property:LLMDescription(
                "Max template clusters to report (default $CLUSTER_DEFAULT_TOP_N, " +
                    "clamped to $CLUSTER_TOP_N_MAX)",
            )
            val limit: Int = CLUSTER_DEFAULT_TOP_N,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val input = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, fenceHint ?: deniedHint)
            if (!input.isFile) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.NOT_FOUND,
                    "文件不存在: ${input.absolutePath}",
                )
            }
            if (args.limit <= 0) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "参数非法：limit > 0（实际 ${args.limit}）",
                )
            }
            val parsedLevels = LinkedHashSet<LogStatsAnalyzer.Level>()
            for (letter in args.levels) {
                val level = LogStatsAnalyzer.Level.entries.firstOrNull { it.letter == letter }
                if (level == null) {
                    return@withContext ToolResult.Err(
                        ToolResult.ErrCode.BAD_ARGS,
                        "非法级别: $letter（合法值: " +
                            "${LogStatsAnalyzer.Level.entries.joinToString("/") { it.letter }}）",
                    )
                }
                parsedLevels.add(level)
            }
            // 空列表 = 默认 E/F；显式全集 ≈ 不过滤（级别解析不出的块仍显式计入候选差值）
            val orderedLevels = LogStatsAnalyzer.Level.entries.filter { it in parsedLevels }
                .ifEmpty { DEFAULT_CLUSTER_LEVELS }
            val topN = minOf(args.limit, CLUSTER_TOP_N_MAX)
            when (val result = LogCluster.analyze(input, orderedLevels.toSet())) {
                is ClusterResult.Ok -> ToolResult.Ok(
                    renderCluster(
                        result,
                        input.name,
                        orderedLevels.joinToString("/") { it.letter },
                        topN,
                    ),
                )
                is ClusterResult.Failure ->
                    ToolResult.Err(ToolResult.ErrCode.FAILED, "聚类失败: ${result.reason}")
            }
        }
    }

    // ── 渲染（有界化在此收口） ──

    /**
     * stats 渲染。未识别差值口径 = 引擎契约：totalBlocks − 对应合计
     * （头行解析不出 Tag/级别的块）。
     */
    internal fun renderStats(result: LogStatsResult.Ok, fileName: String): String = buildString {
        appendLine("统计概览（$fileName）：")
        appendLine("- 扫描行数: ${result.scannedLines}")
        appendLine("- 日志块总数: ${result.totalBlocks}")
        if (result.firstTimestamp != null && result.lastTimestamp != null) {
            appendLine(
                "- 时间跨度: ${TIME_FORMAT.format(result.firstTimestamp)} ~ " +
                    "${TIME_FORMAT.format(result.lastTimestamp)}（共 ${result.spanMillis} 毫秒）",
            )
        } else {
            appendLine("- 时间跨度: 无可识别时间行")
        }
        val levels = LogStatsAnalyzer.Level.entries.joinToString(" ") {
            "${it.letter}=${result.levelCounts[it] ?: 0}"
        }
        val unrecognizedLevels = result.totalBlocks - result.levelCounts.values.sum()
        appendLine("- 级别计数（块）: $levels（未识别级别块数: $unrecognizedLevels）")
        if (result.tagCounts.isEmpty()) {
            appendLine("- Tag 频次: 无可识别 Tag")
        } else {
            val top = result.tagCounts.take(TAG_TOP_N)
            val unrecognizedTags = result.totalBlocks - result.tagCounts.sumOf { it.count }
            appendLine(
                "- Tag 频次 Top ${top.size}/${result.tagCounts.size}" +
                    "（未识别 Tag 块数: $unrecognizedTags）:",
            )
            top.forEach { appendLine("  - ${displayTag(it.tag)}: ${it.count}") }
            val rest = result.tagCounts.size - top.size
            if (rest > 0) {
                appendLine("  （其余 $rest 个 Tag 未列出——可用 log.filter_by_tag 按正则直接下钻）")
            }
        }
    }.trim().let { capAt(it, STATS_MAX_OUTPUT_CHARS) }

    /**
     * diff 渲染。行号 1 基（与 fs.extended.read_file 展示口径一致）。
     * 只渲染首个 hunk；degraded 恒显式成行。
     */
    internal fun renderDiff(
        result: LogDiff.DiffResult.Ok,
        oldName: String,
        newName: String,
        normalized: Boolean,
    ): String = buildString {
        appendLine("对比结果（$oldName → $newName，normalize_timestamps=$normalized）：")
        appendLine("- 行数: old ${result.oldLineCount} / new ${result.newLineCount}")
        appendLine("- 差异 hunk 数: ${result.hunks.size}")
        if (result.degraded) {
            appendLine(
                "- degraded: 是——编辑距离超过对齐上限（${LogDiff.MAX_ALIGN_EDITS}），" +
                    "中间区域整体替换未逐行对齐，差异行数不可信；建议先用 log.filter_by_time " +
                    "把两份各自缩小到差异时间窗后分片对比",
            )
        }
        if (result.hunks.isEmpty()) {
            appendLine("- 两份文件在当前行恒等契约下全等（0 个差异 hunk）")
        } else {
            val hunk = result.hunks.first()
            appendLine(
                "- 首 hunk @@ -${hunk.oldStart + 1},${hunk.oldLines} " +
                    "+${hunk.newStart + 1},${hunk.newLines} @@（窗口共 ${hunk.lines.size} 行，" +
                    "仅显示前 ${minOf(DIFF_HUNK_LINES_MAX, hunk.lines.size)} 行）:",
            )
            hunk.lines.take(DIFF_HUNK_LINES_MAX).forEach { line ->
                val prefix = when (line.kind) {
                    LogDiff.LineKind.CONTEXT -> " "
                    LogDiff.LineKind.REMOVED -> "-"
                    LogDiff.LineKind.ADDED -> "+"
                }
                appendLine("  $prefix${truncate(line.text, DIFF_LINE_MAX_CHARS)}")
            }
            val hidden = hunk.lines.size - DIFF_HUNK_LINES_MAX
            if (hidden > 0) {
                appendLine("  （首 hunk 窗口共 ${hunk.lines.size} 行，其余 $hidden 行未显示）")
            }
            if (result.hunks.size > 1) {
                appendLine(
                    "（其余 ${result.hunks.size - 1} 个 hunk 未显示——可用 log.filter_by_time " +
                        "缩小到各 hunk 时间窗后分片对比）",
                )
            }
        }
    }.trim().let { capAt(it, DIFF_MAX_OUTPUT_CHARS) }

    /**
     * view 渲染。行号 1 基（[ViewerLine.number] 引擎展示口径——工具入参 start_line 与
     * 输出行号同基，LLM 可按输出行号直接续窗）。正文按字符预算逐行累计，预算尽即停
     * 并显式收窗（附续读指针）——大窗口不靠整体截断静默丢尾。
     */
    internal fun renderView(
        fileName: String,
        totalLines: Int,
        startLine: Int,
        requestedLines: Int,
        window: List<ViewerLine>,
    ): String = buildString {
        appendLine(
            "原文窗口（$fileName，共 $totalLines 行）：start_line=$startLine（1 基），请求 $requestedLines 行",
        )
        var used = 0
        var rendered = 0
        for (line in window) {
            val row = "  ${line.number}: ${truncate(line.text, VIEW_LINE_MAX_CHARS)}"
            if (used + row.length + 1 > VIEW_BODY_BUDGET_CHARS) break
            append(row)
            append('\n')
            used += row.length + 1
            rendered++
        }
        when {
            rendered < window.size ->
                appendLine(
                    "（输出字符上界收窗：本窗 ${window.size} 行仅渲染前 $rendered 行——" +
                        "继续查看用 start_line=${startLine + rendered}）",
                )
            startLine + window.size - 1 < totalLines ->
                appendLine("（窗口共 ${window.size} 行——继续查看用 start_line=${startLine + window.size}）")
            else ->
                appendLine("（已到文件末尾）")
        }
    }.trim().let { capAt(it, VIEW_MAX_OUTPUT_CHARS) }

    /**
     * cluster 渲染。会计恒等式逐项呈现（引擎 KDoc 契约——无静默丢弃）；行号样例 1 基
     * （与 log.view / fs.extended.read_file 输出同基——LLM 可按样例行号直接续读原文）。
     */
    internal fun renderCluster(
        result: ClusterResult.Ok,
        fileName: String,
        levelLetters: String,
        topN: Int,
    ): String = buildString {
        appendLine("错误聚类（$fileName，级别过滤 $levelLetters，模板 Top $topN）：")
        appendLine("- 扫描行数: ${result.scannedLines}")
        appendLine("- 块总数: ${result.totalBlocks}；级别命中候选块: ${result.candidateBlocks}")
        appendLine(
            "- 候选块去向: 归并进簇 ${result.templateBlocks} + 模板容量溢出 ${result.overflowBlocks}" +
                " + 空消息头跳过 ${result.skippedEmptyHeadBlocks} + 超长行跳过 ${result.oversizedLineBlocks}",
        )
        if (result.templates.isEmpty()) {
            appendLine("- 模板: 零簇（无候选块归并成模板）——可放宽 levels 或核查文件级别形态")
        } else {
            appendLine("- 模板 Top ${minOf(topN, result.templates.size)}/${result.templates.size}（计数降序）:")
            result.templates.take(topN).forEach { cluster ->
                appendLine("  - [${cluster.count} 次] ${truncate(cluster.template, CLUSTER_TEMPLATE_MAX_CHARS)}")
                val span = if (cluster.firstTimestamp != null && cluster.lastTimestamp != null) {
                    "${TIME_FORMAT.format(cluster.firstTimestamp)} ~ ${TIME_FORMAT.format(cluster.lastTimestamp)}"
                } else {
                    "无可识别时间戳"
                }
                appendLine(
                    "    首/末: $span；块首行号样例（1 基）: " +
                        "${cluster.lineNumbers.take(CLUSTER_LINE_SAMPLES).joinToString(", ")}" +
                        "（共 ${cluster.count} 处" +
                        "${if (cluster.lineNumbersTruncated) "；行号引用已达引擎上限被截断" else ""}）",
                )
            }
            val rest = result.templates.size - minOf(topN, result.templates.size)
            if (rest > 0) {
                appendLine(
                    "  （其余 $rest 个模板未列出——低频面；可按模板关键词用 fs.extended.grep 下钻，" +
                        "或用 log.view 按行号样例引用原文）",
                )
            }
        }
    }.trim().let { capAt(it, CLUSTER_MAX_OUTPUT_CHARS) }

    private fun displayTag(tag: String): String = truncate(tag, TAG_NAME_MAX_CHARS)

    private fun truncate(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else "${text.take(maxChars)}…"

    /** 截断溢出标记（含标记在内总长恒 ≤ maxChars——见 [capAt]） */
    private const val CAP_MARKER = "…[输出超上界已截断]"

    /**
     * 结构性输出上界兜底：长度 ≤ maxChars 时原样返回；超限截到 maxChars 并以
     * 标记收尾（含标记总长恒 ≤ maxChars——硬上界不依赖渲染推导成立）。
     */
    internal fun capAt(text: String, maxChars: Int): String =
        if (text.length <= maxChars) {
            text
        } else {
            text.take(maxChars - CAP_MARKER.length) + CAP_MARKER
        }

    // ── 目录描述符（AgentToolCatalog 注册面） ──

    /** `log.stats` 目录描述符。uiHint = null：纯 agent 工具（研判链自用），不进命令面板。 */
    val LOG_STATS: AgentToolDescriptor = AgentToolDescriptor(
        name = STATS_NAME,
        description = "Distribution overview of a plain-text device log " +
            "(block/tag/level counts + timestamp span) — scout before filtering",
        risk = Risk.READ_ONLY,
    ) { context, sink, config ->
        StatsTool(context, sink, config.fenceHint)
    }

    /** `log.diff` 目录描述符。uiHint = null：纯 agent 工具（研判链自用），不进命令面板。 */
    val LOG_DIFF: AgentToolDescriptor = AgentToolDescriptor(
        name = DIFF_NAME,
        description = "Line-diff two plain-text log files (bounded summary: hunk count + first hunk)",
        risk = Risk.READ_ONLY,
    ) { context, sink, config ->
        DiffTool(context, sink, config.fenceHint)
    }

    /** `log.view` 目录描述符。uiHint = null：纯 agent 工具（研判链自用），不进命令面板。 */
    val LOG_VIEW: AgentToolDescriptor = AgentToolDescriptor(
        name = VIEW_NAME,
        description = "Bounded window of raw log lines with line numbers " +
            "(drill-down citation after log.stats/log.diff)",
        risk = Risk.READ_ONLY,
    ) { context, sink, config ->
        ViewTool(context, sink, config.fenceHint)
    }

    /** `log.cluster` 目录描述符。uiHint = null：纯 agent 工具（研判链自用），不进命令面板。 */
    val LOG_CLUSTER: AgentToolDescriptor = AgentToolDescriptor(
        name = CLUSTER_NAME,
        description = "Top-N template clustering of same-shape error blocks " +
            "(count/first-last timestamp/sample head line numbers) — " +
            "prioritize recurring anomalies before drill-down",
        risk = Risk.READ_ONLY,
    ) { context, sink, config ->
        ClusterTool(context, sink, config.fenceHint)
    }
}
