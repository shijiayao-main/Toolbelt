package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 文件读取/搜索工具集（参照 deepagents 的虚拟 FS 工具词汇）：
 *
 * - read_file：**行分页读取**（带行号）——agent 从「一次吞全文」变「按需翻页」，
 *   大日志不再撑爆 LLM 上下文；超限返回分页指针（totalLines/offset 提示继续翻页）；
 * - grep：**字面量**搜索（KMP，非 regex——参数面小、注入面小、快），文件或目录递归，
 *   cap 1000 命中后截断标注（结果里含行号便于 read_file 定位）；
 * - glob：JDK `PathMatcher("glob:...")` 模式匹配（如 `*.alog`、跨目录的 `xlog` 通配模式），
 *   解包后找同类文件刚需；仅目录在允许集内才展开。
 *
 * 🔴 全部走 [ToolExecutionContext.authorizeWithGate] 围栏 + StructuredSimpleTool 三段式。
 */
object FileTools {

    private const val DEFAULT_PAGE_LIMIT = 200
    private const val GREP_MAX_RESULTS = 1000

    // ── AgentToolCatalog 描述符（P1 A1：新消费方经 catalog 装配；extended 门控语义见选择器） ──

    val READ_FILE: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.extended.read_file",
        description = "Read a text file page by page with line numbers",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = setOf("read_file"),
    ) { context, sink, _ -> ReadFileTool(context, sink) }

    val GREP: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.extended.grep",
        description = "Literal search (KMP) in a file or directory tree, capped at 1000 hits",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = setOf("grep"),
    ) { context, sink, _ -> GrepTool(context, sink) }

    val GLOB: AgentToolCatalog.AgentToolDescriptor = AgentToolCatalog.AgentToolDescriptor(
        name = "fs.extended.glob",
        description = "Glob-match files under an allowed directory",
        risk = AgentToolCatalog.Risk.READ_ONLY,
        aliases = setOf("glob"),
    ) { context, sink, _ -> GlobTool(context, sink) }

    // ── read_file：行分页读取 ──

    class ReadFileTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<ReadFileTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "read_file",
        description = "Read a text file page by page with line numbers (UTF-8 first, GB18030 fallback). " +
            "Each call loads the whole file to count lines and returns the [offset, offset+limit) page — " +
            "very large files cost a full read per call.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the file")
            val file_path: String,
            @property:LLMDescription("Line number to start from (0-based)")
            val offset: Int = 0,
            @property:LLMDescription("Max lines to return per call (default 200)")
            val limit: Int = DEFAULT_PAGE_LIMIT,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val file = context.authorizeWithGate(args.file_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (!file.isFile) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "文件不存在: ${file.absolutePath}")
            }
            if (args.offset < 0 || args.limit <= 0) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "参数非法：offset ≥ 0 且 limit > 0（实际 offset=${args.offset}, limit=${args.limit}）",
                )
            }

            // 单趟扫描：页外行只计数不驻留（内存上界 = O(offset + limit) 而非 O(总行数)）。
            // 仍需读完全文——totalLines / offset 越界判定 / hasMore 三项都要求知道总行数，
            // 工具 description 已按此申明（不接受弱化 totalLines 为下界的语义变更）。
            var totalLines = 0L
            val page = ArrayList<String>(args.limit)
            var hasMore = false
            file.useLines { lines ->
                for (line in lines) {
                    if (totalLines >= args.offset) {
                        if (page.size < args.limit) page += line else hasMore = true
                    }
                    totalLines++
                }
            }
            if (totalLines == 0L) return@withContext ToolResult.Ok("(文件为空)")

            if (args.offset >= totalLines) {
                return@withContext ToolResult.Err(
                    ToolResult.ErrCode.BAD_ARGS,
                    "offset ${args.offset} 超出文件总行数 $totalLines（0-based，最大起始行 = ${totalLines - 1}）",
                )
            }

            val body = page.mapIndexed { i, line -> "${args.offset + i + 1}: ${truncateLine(line)}" }
                .joinToString("\n")
            val summary = if (hasMore) {
                "$body\n\n[已返回 ${page.size}/$totalLines 行（offset=${args.offset}）；" +
                    "继续读取请用 offset=${args.offset + page.size}]"
            } else {
                "$body\n\n[已到文件末尾，共 $totalLines 行]"
            }
            ToolResult.Ok(
                summary = summary,
                data = ToolPayload.Paged(totalLines = totalLines, offset = args.offset, returned = page.size),
            )
        }

        /** 单行超长截断（防单行二进制乱码/超长 JSON 糊爆上下文） */
        private fun truncateLine(line: String, max: Int = 2000): String =
            if (line.length <= max) line else line.take(max) + "…(行超长已截断)"
    }

    // ── grep：字面量搜索 ──

    class GrepTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<GrepTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "grep",
        description = "Search for a literal (non-regex) text pattern in a file or directory (recursive). " +
            "Returns matching lines with file path and line numbers, capped at 1000 hits.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Literal text to search for (not a regex)")
            val pattern: String,
            @property:LLMDescription("Absolute path of the file or directory to search in")
            val path: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            if (args.pattern.isEmpty()) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "搜索词为空")
            }
            val root = context.authorizeWithGate(args.path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)

            // 枚举比扫描上限多取 1 个：恰等于上限时无法区分「正好 N 个」与「不止 N 个」
            val enumerated = if (root.isFile) {
                listOf(root)
            } else {
                root.walkTopDown().filter { it.isFile }.take(GREP_MAX_RESULTS * 2 + 1).toList()
            }
            val enumerationCapped = enumerated.size > GREP_MAX_RESULTS * 2
            val scanSet = if (enumerationCapped) enumerated.dropLast(1) else enumerated

            val hits = mutableListOf<String>()
            var hitCapped = false
            for (file in scanSet) {
                if (hits.size >= GREP_MAX_RESULTS) {
                    hitCapped = true
                    break
                }
                grepFile(file, args.pattern, hits)
            }
            val capped = hitCapped || enumerationCapped

            // 两态截断分开声明：枚举截断时「未找到匹配」不构成完整结论（剩余文件未扫描）
            val notes = mutableListOf<String>()
            if (hitCapped) notes += "已达 ${GREP_MAX_RESULTS} 条匹配上限，结果被截断——可缩小搜索范围"
            if (enumerationCapped) {
                notes += "目录树枚举已达 ${GREP_MAX_RESULTS * 2} 个文件上限，其余文件未扫描——本结果为部分覆盖，不构成完整结论；可缩小搜索范围或改为逐文件搜索"
            }

            if (hits.isEmpty() && !capped) {
                ToolResult.Ok(
                    summary = "(未找到匹配: \"${args.pattern}\")",
                    data = ToolPayload.GrepStats(hits = 0, capped = false),
                )
            } else {
                val head = if (hits.isEmpty()) "(未找到匹配: \"${args.pattern}\")" else hits.joinToString("\n")
                val noteText = if (notes.isEmpty()) {
                    "共 ${hits.size} 条匹配"
                } else {
                    notes.joinToString("；") + if (hits.isNotEmpty()) "；已命中 ${hits.size} 条" else ""
                }
                ToolResult.Ok(
                    summary = "$head\n\n[$noteText]",
                    data = ToolPayload.GrepStats(hits = hits.size, capped = capped),
                )
            }
        }

        private fun grepFile(file: File, pattern: String, hits: MutableList<String>) {
            val text = runCatching { file.readText() }.getOrNull() ?: return // 二进制/不可读跳过
            text.lineSequence().forEachIndexed { index, line ->
                if (hits.size >= GREP_MAX_RESULTS) return
                if (line.contains(pattern)) {
                    hits += "${file.absolutePath}:${index + 1}: ${line.trim().take(500)}"
                }
            }
        }
    }

    // ── glob：模式匹配 ──

    class GlobTool(
        private val context: ToolExecutionContext,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<GlobTool.Args>(
        sink = sink,
        argsType = typeToken<Args>(),
        name = "glob",
        description = "Find files matching a glob pattern (e.g. '*.alog', '**/*.xlog', 'log-?.txt') " +
            "under a directory. Only directories already allowed by the fence can be searched.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Absolute path of the directory to search in")
            val dir_path: String,
            @property:LLMDescription("Glob pattern like '*.alog' or '**/*.xlog'")
            val pattern: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val dir = context.authorizeWithGate(args.dir_path)
                ?: return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, deniedHint)
            if (!dir.isDirectory) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "不是目录: ${dir.absolutePath}")
            }
            val matcher = FileSystemsHolder.matcher(args.pattern)
            val matches = dir.walkTopDown()
                .filter { it.isFile }
                .filter { matcher.matches(it.toPath().fileName) || matcher.matches(it.toPath()) }
                .take(GLOB_MAX_RESULTS)
                .toList()

            if (matches.isEmpty()) {
                ToolResult.Ok("(无匹配文件: ${args.pattern})")
            } else {
                ToolResult.Ok(
                    summary = matches.joinToString("\n") { "${it.absolutePath} [${it.length()}B]" } +
                        "\n\n[共 ${matches.size} 个匹配${if (matches.size >= GLOB_MAX_RESULTS) "（达上限截断）" else ""}]",
                )
            }
        }

        private companion object {
            const val GLOB_MAX_RESULTS = 200
        }
    }

    /** PathMatcher 工厂收口（java.nio.file.FileSystems.getDefault() 单次获取） */
    private object FileSystemsHolder {
        private val fs = java.nio.file.FileSystems.getDefault()
        fun matcher(pattern: String): java.nio.file.PathMatcher = fs.getPathMatcher("glob:$pattern")
    }
}
