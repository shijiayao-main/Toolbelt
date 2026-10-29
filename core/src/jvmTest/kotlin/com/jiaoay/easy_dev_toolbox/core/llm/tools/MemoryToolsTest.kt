package com.jiaoay.easy_dev_toolbox.core.llm.tools

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P3-B L2 语义记忆域单测：四件工具全语义 + 索引一致性 + 路径防御。
 */
class MemoryToolsTest {

    private fun tempDir(): File = Files.createTempDirectory("memory-tools").toFile()

    private suspend fun append(dir: File, fileName: String = "user-preferences.md", content: String = "偏好深色主题"): ToolResult =
        MemoryTools.AppendMemoryTool(dir).executeTyped(
            MemoryTools.AppendMemoryTool.Args(
                fileName = fileName,
                title = "用户偏好",
                hook = "主题与外观偏好",
                content = content,
            ),
        )

    // ── view_memory ──

    @Test
    fun `view empty index reports empty`() = runTest {
        val dir = tempDir()
        val result = MemoryTools.ViewMemoryTool(dir).executeTyped(MemoryTools.ViewMemoryTool.Args())
        assertTrue(result is ToolResult.Err, "空索引返回 Err 引导首次写入")
    }

    @Test
    fun `view index lists registered memories`() = runTest {
        val dir = tempDir()
        append(dir)

        val result = MemoryTools.ViewMemoryTool(dir).executeTyped(MemoryTools.ViewMemoryTool.Args())
        assertTrue(result is ToolResult.Ok)
        assertTrue((result as ToolResult.Ok).summary.contains("user-preferences.md"), "索引含文件指针")
    }

    @Test
    fun `view file returns full content`() = runTest {
        val dir = tempDir()
        append(dir, content = "偏好深色主题与等宽字体")

        val result = MemoryTools.ViewMemoryTool(dir)
            .executeTyped(MemoryTools.ViewMemoryTool.Args(fileName = "user-preferences.md"))
        assertTrue(result is ToolResult.Ok)
        assertTrue((result as ToolResult.Ok).summary.contains("等宽字体"))
    }

    @Test
    fun `view rejects path escape`() = runTest {
        val dir = tempDir()
        val result = MemoryTools.ViewMemoryTool(dir)
            .executeTyped(MemoryTools.ViewMemoryTool.Args(fileName = "../secrets.txt"))
        assertTrue(result is ToolResult.Err, "目录逃逸拒绝")
    }

    // ── append_memory ──

    @Test
    fun `append creates file and registers index`() = runTest {
        val dir = tempDir()
        val result = append(dir)
        assertTrue(result is ToolResult.Ok)

        val body = File(dir, "user-preferences.md").readText()
        assertTrue(body.contains("偏好深色主题"))
        assertTrue(File(dir, MemoryTools.INDEX_FILE).readText().contains("(user-preferences.md)"))
    }

    @Test
    fun `append is idempotent on index`() = runTest {
        val dir = tempDir()
        append(dir)
        append(dir, content = "追加第二条")

        val index = File(dir, MemoryTools.INDEX_FILE).readText()
        assertEquals(1, index.lines().count { it.contains("(user-preferences.md)") }, "重复登记不产生重复索引行")
    }

    @Test
    fun `append rejects non ascii filename`() = runTest {
        val dir = tempDir()
        val result = MemoryTools.AppendMemoryTool(dir).executeTyped(
            MemoryTools.AppendMemoryTool.Args(
                fileName = "偏好.md", // 非 ASCII
                title = "x",
                hook = "x",
                content = "x",
            ),
        )
        assertTrue(result is ToolResult.Err, "非 ASCII 文件名拒绝")
    }

    // ── replace_memory ──

    @Test
    fun `replace rewrites contradicted passage`() = runTest {
        val dir = tempDir()
        append(dir, content = "偏好深色主题")

        val result = MemoryTools.ReplaceMemoryTool(dir).executeTyped(
            MemoryTools.ReplaceMemoryTool.Args(
                fileName = "user-preferences.md",
                oldText = "深色主题",
                newText = "浅色主题（2026-09 更新）",
            ),
        )
        assertTrue(result is ToolResult.Ok)
        val body = File(dir, "user-preferences.md").readText()
        assertTrue(body.contains("浅色主题（2026-09 更新）"))
        assertFalse(body.contains("深色主题"), "旧文本被改写")
    }

    @Test
    fun `replace rejects ambiguous old text`() = runTest {
        val dir = tempDir()
        append(dir, content = "重复词 重复词")

        val result = MemoryTools.ReplaceMemoryTool(dir).executeTyped(
            MemoryTools.ReplaceMemoryTool.Args(
                fileName = "user-preferences.md",
                oldText = "重复词",
                newText = "新",
            ),
        )
        assertTrue(result is ToolResult.Err, "多处匹配拒绝——要求扩大范围")
    }

    @Test
    fun `replace rejects missing old text`() = runTest {
        val dir = tempDir()
        append(dir)

        val result = MemoryTools.ReplaceMemoryTool(dir).executeTyped(
            MemoryTools.ReplaceMemoryTool.Args(
                fileName = "user-preferences.md",
                oldText = "不存在的段落",
                newText = "新",
            ),
        )
        assertTrue(result is ToolResult.Err)
    }

    // ── delete_memory ──

    @Test
    fun `delete removes file and index line`() = runTest {
        val dir = tempDir()
        append(dir)
        append(dir, fileName = "work-conventions.md", content = "工作约定内容")

        val result = MemoryTools.DeleteMemoryTool(dir)
            .executeTyped(MemoryTools.DeleteMemoryTool.Args(fileName = "user-preferences.md"))
        assertTrue(result is ToolResult.Ok)

        assertFalse(File(dir, "user-preferences.md").exists(), "文件已删")
        val index = File(dir, MemoryTools.INDEX_FILE).readText()
        assertFalse(index.contains("(user-preferences.md)"), "索引行已清")
        assertTrue(index.contains("(work-conventions.md)"), "其他记忆不受影响")
    }

    @Test
    fun `delete last memory clears index`() = runTest {
        val dir = tempDir()
        append(dir)

        MemoryTools.DeleteMemoryTool(dir)
            .executeTyped(MemoryTools.DeleteMemoryTool.Args(fileName = "user-preferences.md"))

        assertTrue(File(dir, MemoryTools.INDEX_FILE).readText().isBlank(), "清空后索引为空")
    }
}
