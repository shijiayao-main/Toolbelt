// P3-E · 记忆面板管理面验收（2026-09-19）：MemoryManager 四动作（查看/编辑/删除/清空）
// 与 agent 工具（MemoryTools）同源——索引配方共用，两个消费面零漂移。
package com.jiaoay.easy_dev_toolbox.core.llm.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryManagerTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("edt-mem-mgr").toFile()

    /** 经 agent 工具写入的种子（保证同源验证面真实） */
    private fun seedViaAgentTool(dir: File) {
        val sink = ToolResultSink.NOOP
        val append = MemoryTools.AppendMemoryTool(dir, sink)
        val result = kotlinx.coroutines.runBlocking {
            append.executeTyped(
                MemoryTools.AppendMemoryTool.Args(
                    fileName = "user-preferences.md",
                    title = "用户偏好",
                    hook = "喜欢结论先行的回复",
                    content = "# 用户偏好\n结论先行，分节呈现。",
                ),
            )
        }
        assertTrue(result is ToolResult.Ok, "agent 工具写入种子成功")
    }

    @Test
    fun `agent 写入的条目在管理面可见-同源零漂移`() {
        val dir = tempDir()
        seedViaAgentTool(dir)

        assertTrue(MemoryManager.hasContent(dir))
        val index = MemoryManager.listIndex(dir)
        assertEquals(1, index.size, "agent 写入的索引行经管理面解析可见")
        assertEquals("user-preferences.md", index.first().fileName)
        assertEquals("用户偏好", index.first().title)
        assertEquals("喜欢结论先行的回复", index.first().hook)
        assertTrue(MemoryManager.readEntry(dir, "user-preferences.md")!!.contains("结论先行"))
    }

    @Test
    fun `编辑覆写正文-索引不动`() {
        val dir = tempDir()
        seedViaAgentTool(dir)

        val ok = MemoryManager.writeEntry(dir, "user-preferences.md", "改写后的正文")
        assertTrue(ok, "已登记条目可编辑")
        assertEquals("改写后的正文", MemoryManager.readEntry(dir, "user-preferences.md"))
        // 索引行原样（标题/钩子不随正文编辑漂移）
        val index = MemoryManager.listIndex(dir)
        assertEquals("用户偏好", index.first().title, "索引行不随正文编辑变化")
    }

    @Test
    fun `编辑未登记文件被拒绝-防 UI 编造条目`() {
        val dir = tempDir()
        assertFalse(MemoryManager.writeEntry(dir, "ghost.md", "不存在于索引"), "无索引登记的文件不可编辑")
    }

    @Test
    fun `编辑非法文件名被拒绝`() {
        val dir = tempDir()
        seedViaAgentTool(dir)
        assertFalse(MemoryManager.writeEntry(dir, "../escape.md", "路径逃逸"), "路径逃逸被拒")
    }

    @Test
    fun `删除单条-文件与索引行同步清`() {
        val dir = tempDir()
        seedViaAgentTool(dir)

        assertTrue(MemoryManager.deleteEntry(dir, "user-preferences.md"))
        assertFalse(MemoryManager.hasContent(dir), "删除后索引清空（只剩标题头即清）")
        assertEquals(null, MemoryManager.readEntry(dir, "user-preferences.md"))
    }

    @Test
    fun `清空全部-删除所有记忆文件`() {
        val dir = tempDir()
        seedViaAgentTool(dir)
        val sink = ToolResultSink.NOOP
        kotlinx.coroutines.runBlocking {
            MemoryTools.AppendMemoryTool(dir, sink).executeTyped(
                MemoryTools.AppendMemoryTool.Args(
                    fileName = "project-facts.md",
                    title = "项目事实",
                    hook = "P3 是记忆批",
                    content = "P3 落地中",
                ),
            )
        }

        val count = MemoryManager.clearAll(dir)
        assertEquals(3, count, "三个 md 文件全删（两条记忆 + MEMORY.md 索引本身）")
        assertTrue(MemoryManager.listIndex(dir).isEmpty(), "清空后索引为空")
    }

    @Test
    fun `空目录-空索引无内容`() {
        val dir = tempDir()
        assertFalse(MemoryManager.hasContent(dir))
        assertTrue(MemoryManager.listIndex(dir).isEmpty())
        assertEquals(0, MemoryManager.clearAll(dir))
    }
}
