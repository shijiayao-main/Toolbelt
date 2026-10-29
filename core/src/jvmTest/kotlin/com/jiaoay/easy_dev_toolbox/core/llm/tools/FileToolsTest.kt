package com.jiaoay.easy_dev_toolbox.core.llm.tools

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B2 扩展工具集纪律（零 LLM/零网络）：
 * - read_file：行分页 + 行号 + 翻页指针 + 越界拒；围栏拒绝；
 * - grep：字面量命中 + 行号 + 目录递归；围栏拒绝；
 * - glob：模式匹配 + 围栏外目录拒绝；
 * - hash_file：sha256/md5 已知向量 + 算法白名单；
 * - hex_dump：16 字节/行格式 + 超长截断到 4096；
 * - strings：可打印串提取 + min_length 过滤；
 * - convert_timestamp：10/13 位时间戳 + 日期串双向；
 * - write_todos：全量覆盖 + 空项拒绝 + TodoBoard 状态流。
 */
class FileToolsTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("file-tools").toFile()

    private fun toolResult(result: ToolResult.Ok): String = result.summary

    // ── read_file ──

    @Test
    fun `read_file paginates with line numbers and pointer`() = runTest {
        val dir = tempDir()
        val file = File(dir, "log.txt").apply {
            writeText((1..10).joinToString("\n") { "line-$it" })
        }
        val tool = FileTools.ReadFileTool(ToolExecutionContext(file))

        val ok = tool.executeTyped(FileTools.ReadFileTool.Args(file.absolutePath, offset = 0, limit = 4))

        assertTrue(ok is ToolResult.Ok)
        val summary = toolResult(ok as ToolResult.Ok)
        assertTrue(summary.contains("1: line-1"), "应带行号: $summary")
        assertTrue(summary.contains("4: line-4"))
        assertTrue(summary.contains("offset=4"), "应带翻页指针: $summary")
        val paged = (ok as ToolResult.Ok).data as ToolPayload.Paged
        assertEquals(10L, paged.totalLines)
        assertEquals(4, paged.returned)
        dir.deleteRecursively()
    }

    @Test
    fun `read_file tail page reports end of file`() = runTest {
        val dir = tempDir()
        val file = File(dir, "log.txt").apply { writeText((1..5).joinToString("\n") { "L$it" }) }
        val tool = FileTools.ReadFileTool(ToolExecutionContext(file))

        val ok = tool.executeTyped(FileTools.ReadFileTool.Args(file.absolutePath, offset = 3, limit = 10))

        assertTrue(ok is ToolResult.Ok)
        assertTrue((ok as ToolResult.Ok).summary.contains("已到文件末尾"), "尾页应标注末尾")
        dir.deleteRecursively()
    }

    @Test
    fun `read_file rejects offset beyond eof and fence`() = runTest {
        val dir = tempDir()
        val file = File(dir, "log.txt").apply { writeText("x") }
        val tool = FileTools.ReadFileTool(ToolExecutionContext(file))

        val beyond = tool.executeTyped(FileTools.ReadFileTool.Args(file.absolutePath, offset = 99))
        assertTrue(beyond is ToolResult.Err)

        val denied = tool.executeTyped(FileTools.ReadFileTool.Args("/etc/passwd"))
        assertTrue(denied is ToolResult.Err && denied.code == ToolResult.ErrCode.DENIED)
        dir.deleteRecursively()
    }

    // ── grep ──

    @Test
    fun `grep finds literal matches with line numbers`() = runTest {
        val dir = tempDir()
        val file = File(dir, "app.log").apply {
            writeText("INFO boot\nERROR crash here\nINFO idle\nERROR again\n")
        }
        val tool = FileTools.GrepTool(ToolExecutionContext(file))

        val ok = tool.executeTyped(FileTools.GrepTool.Args("ERROR", file.absolutePath))

        assertTrue(ok is ToolResult.Ok)
        val summary = (ok as ToolResult.Ok).summary
        assertTrue(summary.contains("app.log:2:"), "应带文件:行号: $summary")
        assertTrue(summary.contains("共 2 条匹配"))
        dir.deleteRecursively()
    }

    @Test
    fun `grep recurses directories within fence`() = runTest {
        val dir = tempDir()
        val sub = File(dir, "extracted").apply { mkdirs() }
        File(sub, "a.log").writeText("needle in a")
        File(sub, "b.log").writeText("needle in b\nnothing")
        val context = ToolExecutionContext(listOf(sub))
        val tool = FileTools.GrepTool(context)

        val ok = tool.executeTyped(FileTools.GrepTool.Args("needle", sub.absolutePath))

        assertTrue(ok is ToolResult.Ok)
        assertTrue((ok as ToolResult.Ok).summary.contains("共 2 条匹配"), "目录递归两文件各 1 命中")
        dir.deleteRecursively()
    }

    @Test
    fun `grep enumeration cap with hits reports partial coverage`() = runTest {
        // 目录树超过扫描上限（GREP_MAX_RESULTS*2）：命中但必须声明部分覆盖——
        // LLM 会把「N 条匹配」当完整结论，静默截断的树覆盖=误导。
        // needle 按 1/100 铺面写入：walkTopDown 顺序是文件系统任意序，单点文件可能
        // 恰好落在未扫描区——铺面保证任意枚举序下扫描集必含命中
        val dir = tempDir()
        val sub = File(dir, "tree").apply { mkdirs() }
        repeat(2100) { i ->
            File(sub, "bulk-$i.log").writeText(if (i % 100 == 0) "needle in $i" else "x")
        }
        val context = ToolExecutionContext(listOf(sub))
        val tool = FileTools.GrepTool(context)

        val ok = tool.executeTyped(FileTools.GrepTool.Args("needle", sub.absolutePath))

        assertTrue(ok is ToolResult.Ok)
        val payload = (ok as ToolResult.Ok).data as ToolPayload.GrepStats
        assertEquals(true, payload.capped, "枚举截断必须置 capped=true")
        assertTrue(ok.summary.contains("部分覆盖"), "回执必须声明部分覆盖: ${ok.summary}")
        assertTrue(ok.summary.contains("needle in"), "已扫描文件中的命中仍在: ${ok.summary}")
        dir.deleteRecursively()
    }

    @Test
    fun `grep enumeration cap with zero hits does not claim no match`() = runTest {
        // 零命中 + 枚举截断：不得以「未找到匹配」单独收尾——剩余未扫描文件里可能还有匹配
        val dir = tempDir()
        val sub = File(dir, "tree").apply { mkdirs() }
        repeat(2100) { File(sub, "bulk-$it.log").writeText("x") }
        val context = ToolExecutionContext(listOf(sub))
        val tool = FileTools.GrepTool(context)

        val ok = tool.executeTyped(FileTools.GrepTool.Args("absent-needle", sub.absolutePath))

        assertTrue(ok is ToolResult.Ok)
        val payload = (ok as ToolResult.Ok).data as ToolPayload.GrepStats
        assertEquals(0, payload.hits)
        assertEquals(true, payload.capped, "零命中但树被截断，capped 必须=true")
        assertTrue(ok.summary.contains("未找到匹配"), "如实报告已扫描面零命中")
        assertTrue(ok.summary.contains("部分覆盖"), "同时声明未扫描面——不构成完整结论: ${ok.summary}")
        dir.deleteRecursively()
    }

    // ── glob ──

    @Test
    fun `glob matches files by pattern`() = runTest {
        val dir = tempDir()
        File(dir, "one.alog").writeText("x")
        File(dir, "two.alog").writeText("x")
        File(dir, "skip.txt").writeText("x")
        val tool = FileTools.GlobTool(ToolExecutionContext(dir))

        val ok = tool.executeTyped(FileTools.GlobTool.Args(dir.absolutePath, "*.alog"))

        assertTrue(ok is ToolResult.Ok)
        val summary = (ok as ToolResult.Ok).summary
        assertTrue(summary.contains("one.alog") && summary.contains("two.alog"))
        assertTrue(!summary.contains("skip.txt"))
        dir.deleteRecursively()
    }

    @Test
    fun `glob rejects directory outside fence`() = runTest {
        val dir = tempDir()
        val inside = File(dir, "inside").apply { mkdirs() }
        val tool = FileTools.GlobTool(ToolExecutionContext(inside))

        val denied = tool.executeTyped(FileTools.GlobTool.Args(dir.absolutePath, "*"))

        assertTrue(denied is ToolResult.Err && denied.code == ToolResult.ErrCode.DENIED, "围栏外目录必须拒")
        dir.deleteRecursively()
    }

    // ── buildToolRegistry 门控 ──

    @Test
    fun `buildToolRegistry extended flag adds new tools`() {
        val dir = tempDir()
        val input = File(dir, "probe.txt").apply { writeText("x") }
        val base = DecryptTools.buildToolRegistry(ToolExecutionContext(input))
        val extended = DecryptTools.buildToolRegistry(ToolExecutionContext(input), extended = true, todoBoard = TodoBoard())

        val baseNames = base.tools.map { it.name }.toSet()
        val extNames = extended.tools.map { it.name }.toSet()
        assertTrue("read_file" !in baseNames && "read_file" in extNames, "extended 门控：base 无扩展件")
        assertTrue("write_todos" in extNames && "write_todos" !in baseNames, "todoBoard 门控")
        assertTrue("hash_file" in extNames && "hex_dump" in extNames && "strings" in extNames && "convert_timestamp" in extNames)
        assertTrue("decrypt_file" in baseNames && "decrypt_file" in extNames, "基础四件恒在")
        dir.deleteRecursively()
    }

    // ── TodoBoard / write_todos ──

    @Test
    fun `write_todos replaces board state and rejects blank content`() = runTest {
        val board = TodoBoard()
        val tool = WriteTodosTool(board)

        val ok = tool.executeTyped(
            WriteTodosTool.Args(
                listOf(
                    TodoItem("解密文件", TodoStatus.DONE),
                    TodoItem("分析错误行", TodoStatus.IN_PROGRESS),
                ),
            ),
        )
        assertTrue(ok is ToolResult.Ok)
        assertEquals(2, board.items.value.size)
        assertEquals(TodoStatus.IN_PROGRESS, board.items.value[1].status)

        val bad = tool.executeTyped(WriteTodosTool.Args(listOf(TodoItem("", TodoStatus.PENDING))))
        assertTrue(bad is ToolResult.Err, "空 content 必须拒")
        assertEquals(2, board.items.value.size, "失败调用不得改动看板")
    }
}
