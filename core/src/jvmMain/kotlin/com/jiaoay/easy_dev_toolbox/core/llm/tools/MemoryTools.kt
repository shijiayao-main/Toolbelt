package com.jiaoay.easy_dev_toolbox.core.llm.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * L2 语义记忆域——agent 四件记忆工具。
 *
 * ## 设计（E4 调研：Claude Code 文件式语义记忆模式的桌面移植）
 * - 记忆 = `~/.easy_dev_toolbox/memory/` 目录下的 Markdown 文件：
 *   - `MEMORY.md` 索引（每条记忆一行指针：`- [标题](文件名.md) — 一句话钩子`）；
 *   - 分主题文件（kebab-case 命名，一事一文）；
 * - agent 经四件工具读写：[ViewMemoryTool] 读索引/指定文件、[AppendMemoryTool]
 *   追加新记忆（新建主题文件+登记索引）、[ReplaceMemoryTool] 原文替换、
 *   [DeleteMemoryTool] 删除（清文件+索引行）；
 * - 索引恒先读后写：agent 不被要求记住索引全量——view 索引即获得全部指针。
 *
 * ## 纪律
 * - **矛盾驱动 DELETE，不做 TTL**（调研 E4 拍板：记忆过时以「新信息与旧条目
 *   矛盾」为准，到期时间是伪需求）；
 * - 一次一事一文：不往同一文件堆多个主题（索引粒度 = 检索粒度）；
 * - 文件名 kebab-case、ASCII（跨平台路径安全）。
 */
/** 记忆目录路径防御（canonical 化后必须落在 dir 内；不触文件系统围栏——记忆域自带围栏）。
 *  顶层 internal 扩展：同包的 [MemoryManager]
 *  （UI 管理面）与四件工具共用同一防御判据。 */
internal fun File.isInside(dir: File): Boolean {
    val target = canonicalFile
    val root = dir.canonicalFile
    return target == root || target.path.startsWith(root.path + File.separator)
}

object MemoryTools {

    const val INDEX_FILE = "MEMORY.md"

    /** 索引行格式（登记与清理共用——测试锚） */
    internal fun indexLine(fileName: String, title: String, hook: String) = "- [$title]($fileName) — $hook"

    // ── 索引读写（四件共用） ──

    internal fun readIndex(dir: File): String =
        runCatching { File(dir, INDEX_FILE).readText() }.getOrDefault("")

    /** 索引行登记（已存在同链接行则跳过——幂等） */
    internal fun addIndexLine(dir: File, fileName: String, title: String, hook: String) {
        val line = indexLine(fileName, title, hook)
        val current = readIndex(dir)
        if (current.lines().any { it.contains("($fileName)") }) return
        val header = if (current.isBlank()) "# Memory Index\n" else current.trimEnd() + "\n"
        File(dir, INDEX_FILE).writeText("$header\n$line\n")
    }

    internal fun removeIndexLine(dir: File, fileName: String) {
        val current = readIndex(dir)
        if (current.isBlank()) return
        val filtered = current.lines().filterNot { it.contains("($fileName)") }
        // 只剩标题头（无任何条目行）= 索引清空（空壳头对 view 是噪音）
        val hasEntries = filtered.any { it.startsWith("- ") }
        File(dir, INDEX_FILE).writeText(
            if (!hasEntries) "" else filtered.joinToString("\n", postfix = "\n"),
        )
    }

    // ── 四件工具 ──

    /** 读记忆：无 fileName 读索引；有则读指定主题文件 */
    class ViewMemoryTool(
        private val dir: File,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<ViewMemoryTool.Args>(
        sink = sink,
        argsType = typeToken<ViewMemoryTool.Args>(),
        name = NAME,
        description = "Read long-term memory: no argument reads the index (MEMORY.md), " +
            "pass a file name to read that memory entry in full.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Memory file to read (e.g. user-preferences.md). Empty = read index")
            val fileName: String = "",
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            if (args.fileName.isBlank()) {
                val index = readIndex(dir)
                if (index.isBlank()) {
                    ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "记忆索引为空——还没有任何长期记忆（可用 append_memory 首次写入）")
                } else {
                    ToolResult.Ok("记忆索引：\n$index")
                }
            } else {
                val file = File(dir, args.fileName)
                if (!file.isInside(dir)) return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, "非法文件名：${args.fileName}")
                if (!file.isFile) return@withContext ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "记忆文件不存在：${args.fileName}（先读索引确认文件名）")
                ToolResult.Ok(file.readText())
            }
        }

        companion object {
            const val NAME = "view_memory"
        }
    }

    /** 追加记忆：新建/追加主题文件 + 索引登记 */
    class AppendMemoryTool(
        private val dir: File,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<AppendMemoryTool.Args>(
        sink = sink,
        argsType = typeToken<AppendMemoryTool.Args>(),
        name = NAME,
        description = "Write long-term memory: create or append to a topic memory file (one topic per file) " +
            "and register it in the index. Call when the user states a preference, correction or important fact.",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Memory file name (kebab-case, one topic per file, e.g. user-preferences.md)")
            val fileName: String,
            @property:LLMDescription("Memory title for the index (e.g. \"用户偏好\")")
            val title: String,
            @property:LLMDescription("One-line hook for the index (decides whether this memory is worth reading later)")
            val hook: String,
            @property:LLMDescription("Memory body in Markdown, appended to the end of the file")
            val content: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            if (args.fileName.isBlank() || args.content.isBlank()) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "fileName 与 content 必填")
            }
            if (!Regex("^[a-zA-Z0-9._-]+$").matches(args.fileName)) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "文件名须 kebab-case/ASCII：${args.fileName}")
            }
            val file = File(dir, args.fileName)
            if (!file.isInside(dir)) return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, "非法文件名：${args.fileName}")
            dir.mkdirs()
            val existed = file.isFile
            val body = if (existed) file.readText().trimEnd() + "\n\n" + args.content + "\n" else args.content + "\n"
            file.writeText(body)
            addIndexLine(dir, args.fileName, args.title, args.hook)
            ToolResult.Ok(if (existed) "已追加到 ${args.fileName}" else "已新建记忆文件 ${args.fileName} 并登记索引")
        }

        companion object {
            const val NAME = "append_memory"
        }
    }

    /** 替换记忆：文件内精确文本替换（矛盾修正用——新信息覆盖旧段落） */
    class ReplaceMemoryTool(
        private val dir: File,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<ReplaceMemoryTool.Args>(
        sink = sink,
        argsType = typeToken<ReplaceMemoryTool.Args>(),
        name = NAME,
        description = "Fix long-term memory: replace an exact text snippet inside a memory file " +
            "(use when new information contradicts an old memory; rewrite instead of append).",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Memory file name")
            val fileName: String,
            @property:LLMDescription("Exact old text to replace (must be unique in the file)")
            val oldText: String,
            @property:LLMDescription("New text to put in place")
            val newText: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val file = File(dir, args.fileName)
            if (!file.isInside(dir)) return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, "非法文件名：${args.fileName}")
            if (!file.isFile) return@withContext ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "记忆文件不存在：${args.fileName}")
            val body = file.readText()
            val first = body.indexOf(args.oldText)
            if (first < 0) return@withContext ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "原文不存在于 ${args.fileName}（先 view_memory 读当前内容）")
            if (body.indexOf(args.oldText, first + 1) >= 0) {
                return@withContext ToolResult.Err(ToolResult.ErrCode.BAD_ARGS, "原文在文件中出现多次（不唯一）——扩大 oldText 范围使其唯一")
            }
            file.writeText(body.replaceFirst(args.oldText, args.newText))
            ToolResult.Ok("已替换 ${args.fileName} 中的一处记忆段落")
        }

        companion object {
            const val NAME = "replace_memory"
        }
    }

    /** 删除记忆：删文件 + 清索引行（矛盾驱动 DELETE 的执行面） */
    class DeleteMemoryTool(
        private val dir: File,
        sink: ToolResultSink = ToolResultSink.NOOP,
    ) : StructuredSimpleTool<DeleteMemoryTool.Args>(
        sink = sink,
        argsType = typeToken<DeleteMemoryTool.Args>(),
        name = NAME,
        description = "Delete one long-term memory file and its index line (use when a memory is proven " +
            "outdated or wrong; no automatic expiry — deletion needs an explicit reason).",
    ) {
        @Serializable
        data class Args(
            @property:LLMDescription("Memory file to delete")
            val fileName: String,
        )

        override suspend fun executeTyped(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val file = File(dir, args.fileName)
            if (!file.isInside(dir)) return@withContext ToolResult.Err(ToolResult.ErrCode.DENIED, "非法文件名：${args.fileName}")
            if (!file.isFile) return@withContext ToolResult.Err(ToolResult.ErrCode.NOT_FOUND, "记忆文件不存在：${args.fileName}")
            file.delete()
            removeIndexLine(dir, args.fileName)
            ToolResult.Ok("已删除记忆 ${args.fileName} 并清索引")
        }

        companion object {
            const val NAME = "delete_memory"
        }
    }
}
