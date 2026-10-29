package com.jiaoay.easy_dev_toolbox.core.llm.tools

import java.io.File

/**
 * L2 语义记忆的 UI 管理面（记忆面板四动作的后端）。
 *
 * ## 定位
 * agent 侧四件工具（`MemoryTools`）与本面**同源**——都走 [MemoryTools] 的索引配方
 * （indexLine/addIndexLine/removeIndexLine），保证「agent 写入的条目在 UI 面板可见、
 * UI 面板的清空 agent 立即可见」，两个消费面永不漂移。
 *
 * ## 四动作（E4 调研：查看/编辑/删除/清空是记忆面板标配）
 * - 查看：列出索引条目 + 读指定记忆文件正文；
 * - 编辑：整文件覆写（正文级编辑——工具面 replace 的 UI 形态，语义更粗但更直观）；
 * - 删除：删单文件 + 清索引行（= delete_memory 工具同款）；
 * - 清空：全目录清空（清空需 UI 二次确认，agent 侧无对应——破坏性只归人）。
 */
object MemoryManager {

    /** 索引条目（MEMORY.md 一行指针的解析形态） */
    data class MemoryIndexEntry(
        val fileName: String,
        val title: String,
        val hook: String,
    )

    /** 记忆目录是否有内容（索引有登记行 = 有内容；空索引/无索引 = 空） */
    fun hasContent(dir: File): Boolean =
        listIndex(dir).isNotEmpty()

    /** 读索引（解析 `-[标题](文件名) — 钩子` 行；坏行静默跳过） */
    fun listIndex(dir: File): List<MemoryIndexEntry> {
        val index = MemoryTools.readIndex(dir)
        if (index.isBlank()) return emptyList()
        return index.lines().mapNotNull { line ->
            val m = Regex("""^- \[(.+?)]\((.+?)\) — (.+)$""").find(line) ?: return@mapNotNull null
            MemoryIndexEntry(
                fileName = m.groupValues[2],
                title = m.groupValues[1],
                hook = m.groupValues[3],
            )
        }
    }

    /** 读单条记忆正文（miss 返回 null） */
    fun readEntry(dir: File, fileName: String): String? {
        val file = File(dir, fileName)
        if (!file.isInside(dir)) return null
        return if (file.isFile) file.readText() else null
    }

    /**
     * 编辑：整文件覆写（UI 面板的「编辑」动作）。
     * 索引不动（标题/钩子由 agent 侧维护）；正文空串 = 留空文件（清空而非删除）。
     */
    fun writeEntry(dir: File, fileName: String, content: String): Boolean {
        if (!Regex("^[a-zA-Z0-9._-]+$").matches(fileName)) return false
        val file = File(dir, fileName)
        if (!file.isInside(dir)) return false
        if (!hasContent(dir)) return false // 未登记索引的文件不写（防 UI 编造条目）
        dir.mkdirs()
        file.writeText(content)
        return true
    }

    /** 删除单条（= delete_memory 工具同款：删文件 + 清索引行） */
    fun deleteEntry(dir: File, fileName: String): Boolean {
        val file = File(dir, fileName)
        if (!file.isInside(dir) || !file.isFile) return false
        file.delete()
        MemoryTools.removeIndexLine(dir, fileName)
        return true
    }

    /** 清空全部记忆（删目录下所有 .md + 清索引；⚠️ UI 侧必须二次确认） */
    fun clearAll(dir: File): Int {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: return 0
        var count = 0
        files.forEach { if (it.delete()) count++ }
        return count
    }
}
