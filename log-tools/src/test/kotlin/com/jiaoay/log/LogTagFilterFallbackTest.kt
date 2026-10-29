package com.jiaoay.log

import com.github.ajalt.clikt.core.parse
import com.jiaoay.log.cli.LogUtilsCommand
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LogTagFilter] 段落降级契约测试（无标准时间戳行首文件的块切分退化修复）：
 *
 * 无时间戳文件（mmap 转储 / tombstone 等 crash 内容）不降级时整份内容攒成单块，
 * tag 命中任意一行 = 全文件倾倒（all-or-nothing，过滤失效）。降级语义：
 * - 头部窗口（[LogTagFilter.HEADER_WINDOW_LINES] 行）零时间行 → 激活段落降级，
 *   空行兼作块切分触发（保真归块尾——命中块顺序写出自带空行分隔不粘连）；
 * - 时间行触发**不替换**：降级期间时间行照常切分；
 * - 有时间行 → 现行为逐位不变（正常文件字节级回归）；
 * - 降级激活须在 [ToolsResult.Success.warning] 与 CLI echo 明示（工具诚实性）。
 */
class LogTagFilterFallbackTest {

    private fun tempDir(): File = createTempDirectory("logtag-fallback-test").toFile()

    /** tombstone 风格 fixture：3 个空行分隔段落，仅段落 1 含 SIGSEGV */
    private fun tombstoneFile(dir: File): File {
        val content = buildString {
            appendLine("Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 1234 (RenderThread)")
            appendLine()
            appendLine("backtrace:")
            appendLine("  #00 pc 000000000001234  /system/lib64/libc.so (memcpy)")
            appendLine("  #01 pc 000000000005678  /system/lib64/libhwui.so (DrawFrame)")
            appendLine()
            appendLine("Abort message: 'FORTIFY: memcpy called with size that is too large'")
        }
        return File(dir, "tombstone.txt").apply { writeText(content) }
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8.name()))
        return try {
            block()
            buffer.toString(Charsets.UTF_8.name())
        } finally {
            System.setOut(original)
        }
    }

    /** [writeBlock] 经 BufferedWriter.newLine 落行分隔——字节级断言用同款分隔符拼期望值 */
    private fun expectedBytes(vararg lines: String): String =
        lines.joinToString(System.lineSeparator()) + System.lineSeparator()

    @Test
    fun `无时间戳文件 tag 命中只输出命中段落而非全文件`() {
        val dir = tempDir()
        val file = tombstoneFile(dir)
        val result = LogTagFilter.filterByTag(file, tagRegex = "SIGSEGV", tagName = "sig")

        val success = assertIs<ToolsResult.Success>(result)
        assertEquals(
            expectedBytes(
                "Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 1234 (RenderThread)",
                "",
            ),
            success.outputFile.readText(),
            "命中块 = 段落 1（含保真归块尾的空行），不得倾倒全文",
        )
        assertFalse("backtrace:" in success.outputFile.readText(), "未命中段落不得入产物")
        assertFalse("Abort message" in success.outputFile.readText(), "未命中段落不得入产物")
    }

    @Test
    fun `降级激活时 ToolsResult warning 携带段落切分提示`() {
        val dir = tempDir()
        val success = assertIs<ToolsResult.Success>(
            LogTagFilter.filterByTag(tombstoneFile(dir), tagRegex = "SIGSEGV", tagName = "warn"),
        )
        assertEquals(LogTagFilter.PARAGRAPH_FALLBACK_WARNING, success.warning, "降级形态必须明示，不得静默")
    }

    @Test
    fun `降级激活时 CLI filter_by_tag 在成功锚前输出 warning 行`() {
        val dir = tempDir()
        val file = tombstoneFile(dir)
        val output = captureStdout {
            LogUtilsCommand().parse(
                listOf(
                    "--type", "filter_by_tag",
                    "-f", file.absolutePath,
                    "--tagRegex", "SIGSEGV",
                    "--tagName", "cli",
                ),
            )
        }
        assertTrue(
            "warning: ${LogTagFilter.PARAGRAPH_FALLBACK_WARNING}" in output,
            "降级提示须先于成功锚出现在 stdout: $output",
        )
        assertTrue("success! outputFile:" in output, "成功锚须在场: $output")
    }

    @Test
    fun `带时间戳文件字节级回归不变且无降级提示`() {
        val dir = tempDir()
        // 块 1（第 1-4 行，含块内空行——正常模式空行保真入块不切块）；块 2（第 5-6 行）
        val content = buildString {
            appendLine("10-11 12:00:00.001 E/Alpha: start")
            appendLine("continuation MyTag hit")
            appendLine()
            appendLine("second continuation")
            appendLine("10-11 12:00:01.001 E/Beta: next")
            appendLine("plain miss line")
        }
        val file = File(dir, "timestamped.txt").apply { writeText(content) }

        val outFile = File(dir, "out.txt")
        LogTagFilter.filterByTagBlock(file, outFile, tagRegex = "MyTag")
        assertEquals(
            expectedBytes(
                "10-11 12:00:00.001 E/Alpha: start",
                "continuation MyTag hit",
                "",
                "second continuation",
            ),
            outFile.readText(),
            "正常文件块语义逐位不变（块内空行保真入块）",
        )

        val success = assertIs<ToolsResult.Success>(
            LogTagFilter.filterByTag(file, tagRegex = "MyTag", tagName = "reg"),
        )
        assertNull(success.warning, "带时间戳文件不降级——不得出降级提示")
    }

    @Test
    fun `预演与全量同口径-降级文件命中块数一致`() {
        val dir = tempDir()
        val content = buildString {
            appendLine("SIGSEGV paragraph one")
            appendLine()
            appendLine("noise paragraph two")
            appendLine()
            appendLine("SIGSEGV paragraph three")
            appendLine()
            appendLine("noise paragraph four")
        }
        val file = File(dir, "paragraphs.txt").apply { writeText(content) }

        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, tagRegex = "SIGSEGV", maxLines = 100))
        assertEquals(4, ok.totalBlocks, "降级文件按空行切段落：4 段 = 4 块")
        assertEquals(false, ok.truncated)
        assertEquals(true, ok.fallbackMode, "预演结果须携带降级标记（UI 呈现面的数据源）")

        val outFile = File(dir, "full-out.txt")
        LogTagFilter.filterByTagBlock(file, outFile, tagRegex = "SIGSEGV")
        assertEquals(
            expectedBytes("SIGSEGV paragraph one", "", "SIGSEGV paragraph three", ""),
            outFile.readText(),
            "全量产物 = 命中段落（块尾空行保真分隔不粘连）",
        )
        assertEquals(2, ok.matchedBlocks, "预演命中块数须与全量写出块数同口径（单源块规则）")
    }

    @Test
    fun `窗口第 500 行有时间行则不降级`() {
        val dir = tempDir()
        val lines = ArrayList<String>()
        lines.add("noise head a")
        lines.add("noise tail a")
        lines.add("")
        // 第 4-499 行：无时间行噪声（凑满窗口内前 499 行）
        for (i in 4..499) lines.add("noise body $i")
        // 第 500 行 = 窗口内首个时间行 → 不降级：噪声（含第 3 行空行）攒成一块
        lines.add("10-11 12:00:00.001 E/Tail: time line at window edge")
        lines.add("tail continuation")
        val file = File(dir, "edge500.txt").apply { writeText(lines.joinToString("\n") + "\n") }

        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, tagRegex = "E/Tail", maxLines = 1000))
        assertEquals(2, ok.totalBlocks, "不降级：噪声一块（空行不切块）+ 时间行起一块")
        assertEquals(1, ok.matchedBlocks)
        assertEquals(false, ok.fallbackMode, "窗口内见时间行 → 预演不降级（fallbackMode=false）")

        val success = assertIs<ToolsResult.Success>(
            LogTagFilter.filterByTag(file, tagRegex = "E/Tail", tagName = "edge"),
        )
        assertNull(success.warning, "窗口内见时间行 → 不降级")
    }

    @Test
    fun `窗口 500 行零时间行后仍有时间行则降级且时间行照常切分`() {
        val dir = tempDir()
        val lines = ArrayList<String>()
        lines.add("noise head a")
        lines.add("noise tail a")
        lines.add("")
        // 第 4-500 行：凑满恰好 500 行零时间行窗口
        for (i in 4..500) lines.add("noise body $i")
        // 第 501 行时间行在窗口外 → 降级激活；时间行触发不替换——照常切分
        lines.add("10-11 12:00:00.001 E/Tail: time line beyond window")
        lines.add("tail continuation")
        val file = File(dir, "edge501.txt").apply { writeText(lines.joinToString("\n") + "\n") }

        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, tagRegex = "E/Tail", maxLines = 1000))
        assertEquals(
            3, ok.totalBlocks,
            "降级语义 = 或触发：空行切噪声两段（1-2 / 4-500）+ 时间行切出第 3 块",
        )
        assertEquals(1, ok.matchedBlocks)

        val success = assertIs<ToolsResult.Success>(
            LogTagFilter.filterByTag(file, tagRegex = "E/Tail", tagName = "beyond"),
        )
        assertEquals(LogTagFilter.PARAGRAPH_FALLBACK_WARNING, success.warning, "窗口零时间行 → 降级并明示")
    }

    @Test
    fun `短于窗口的无时间戳文件同样降级且产物无多余分隔`() {
        val dir = tempDir()
        // 2 行单段落、无尾部空行：EOF 完结末块——产物无块尾空行（单块无粘连问题）
        val file = File(dir, "short.txt").apply {
            writeText("SIGSEGV crash line one\ncrash line two\n")
        }
        val success = assertIs<ToolsResult.Success>(
            LogTagFilter.filterByTag(file, tagRegex = "SIGSEGV", tagName = "short"),
        )
        assertEquals(
            expectedBytes("SIGSEGV crash line one", "crash line two"),
            success.outputFile.readText(),
            "EOF 完结的末块无块尾空行——不凭空造分隔",
        )
        assertEquals(LogTagFilter.PARAGRAPH_FALLBACK_WARNING, success.warning)

        val ok = assertIs<TagPreviewResult.Ok>(LogTagFilter.previewByTag(file, tagRegex = "SIGSEGV", maxLines = 100))
        assertEquals(1, ok.totalBlocks)
        assertEquals(1, ok.matchedBlocks)
    }
}
