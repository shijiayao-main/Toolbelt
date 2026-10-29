package com.jiaoay.easy_dev_toolbox.core.llm.tools

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文件工具族四件（fs.*）的正常/错误路径（零 LLM、零网络）：
 * - decrypt_file：合成 0x03 段（无加密）→ 解出文本；空私钥场景不适用（0x03 不需钥）；
 * - extract_archive：临时 zip → 解包 + 产物登记；非压缩后缀拒绝；
 * - preview_text：截断标注 + GB18030 回退；
 * - list_dir：条目格式；
 * - 围栏：越界路径全部拒绝。
 */
class DecryptToolsTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("decrypt-tools").toFile()

    /** 手写最小段（0x03 无压缩无加密） */
    private fun syntheticSegment(payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 2 + 1 + 1 + 4 + 4 + payload.size + 1).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x03).putShort(0).put(8.toByte()).put(9.toByte())
        buffer.putInt(payload.size).put(ByteArray(4)).put(payload).put(0x00)
        return buffer.array()
    }

    private fun zipOf(name: String, content: String): ByteArray =
        java.io.ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }

    // ── 围栏 ──

    @Test
    fun `execution context authorizes input file and rejects outsiders`() {
        val dir = tempDir()
        val input = File(dir, "input.xlog").apply { writeText("x") }
        val outsider = File(dir, "sibling.txt").apply { writeText("secret") }
        val context = ToolExecutionContext(input)

        assertTrue(context.isAllowed(input))
        assertFalse(context.isAllowed(outsider), "未登记的兄弟文件应被拒绝")
        assertFalse(context.authorize("../../etc/passwd") != null, ".. 逃逸应被拒绝")
        assertFalse(context.authorize("/etc/passwd") != null, "绝对外部路径应被拒绝")
        dir.deleteRecursively()
    }

    @Test
    fun `registered artifact becomes authorized afterwards`() {
        val dir = tempDir()
        val input = File(dir, "input.xlog").apply { writeText("x") }
        val product = File(dir, "input.xlog.txt").apply { writeText("decoded") }
        val context = ToolExecutionContext(input)

        assertFalse(context.isAllowed(product), "产物登记前不可访问")
        context.register(product)
        assertTrue(context.isAllowed(product), "产物登记后应被授权（解密→预览链路）")
        dir.deleteRecursively()
    }

    // ── decrypt_file ──

    @Test
    fun `decrypt_file decodes synthetic unencrypted segment`() = runTest {
        val dir = tempDir()
        val input = File(dir, "sample.alog").apply { writeBytes(syntheticSegment("设备日志内容 hello".toByteArray())) }
        val context = ToolExecutionContext(input)
        val tool = DecryptTools.DecryptFileTool(context) { "unused-for-0x03" }

        val result = tool.execute(DecryptTools.DecryptFileTool.Args(file_path = input.absolutePath))

        assertTrue(result.startsWith("解密成功"), "实际: $result")
        val output = File(dir, "sample.alog.txt")
        assertTrue(output.isFile, "应在同目录产出 .txt")
        assertTrue(output.readText().contains("设备日志内容"), "解出内容应含原文")
        assertTrue(context.isAllowed(output), "产物应自动登记进围栏")
        dir.deleteRecursively()
    }

    @Test
    fun `decrypt_file rejects path outside fence`() = runTest {
        val dir = tempDir()
        val input = File(dir, "input.alog").apply { writeBytes(syntheticSegment("x".toByteArray())) }
        val tool = DecryptTools.DecryptFileTool(ToolExecutionContext(input)) { "key" }

        val result = tool.execute(DecryptTools.DecryptFileTool.Args(file_path = "/etc/passwd"))

        assertTrue(result.contains("不在本任务允许范围内"), "越界应返回拒绝文案: $result")
        dir.deleteRecursively()
    }

    // ── extract_archive ──

    @Test
    fun `extract_archive unpacks zip and returns file list`() = runTest {
        val dir = tempDir()
        val archive = File(dir, "logs.zip").apply { writeBytes(zipOf("inner.txt", "zipped content")) }
        val context = ToolExecutionContext(archive)
        val tool = DecryptTools.ExtractArchiveTool(context)

        val result = tool.execute(DecryptTools.ExtractArchiveTool.Args(archive_path = archive.absolutePath))

        assertTrue(result.startsWith("解包成功"), "实际: $result")
        assertTrue(result.contains("inner.txt"))
        val extracted = File(dir, "logs.zip_extracted/inner.txt")
        assertTrue(extracted.isFile && extracted.readText() == "zipped content")
        assertTrue(context.isAllowed(extracted), "解出文件应登记进围栏")
        dir.deleteRecursively()
    }

    @Test
    fun `extract_archive rejects non-archive suffix`() = runTest {
        val dir = tempDir()
        val txt = File(dir, "plain.txt").apply { writeText("not an archive") }
        val tool = DecryptTools.ExtractArchiveTool(ToolExecutionContext(txt))

        val result = tool.execute(DecryptTools.ExtractArchiveTool.Args(archive_path = txt.absolutePath))

        assertTrue(result.contains("不支持的压缩格式"), "实际: $result")
        dir.deleteRecursively()
    }

    // ── preview_text ──

    @Test
    fun `preview_text truncates long content`() = runTest {
        val dir = tempDir()
        val file = File(dir, "long.txt").apply { writeText("A".repeat(10_000)) }
        val tool = DecryptTools.PreviewTextTool(ToolExecutionContext(file))

        val result = tool.execute(DecryptTools.PreviewTextTool.Args(file_path = file.absolutePath))

        assertTrue(result.contains("已截断"), "超长应标注截断: ${result.take(60)}")
        dir.deleteRecursively()
    }

    @Test
    fun `preview_text falls back to gb18030 for chinese encoding`() = runTest {
        val dir = tempDir()
        // GB18030 编码的中文（非合法 UTF-8 字节序列 → 触发回退）
        val gbBytes = "中文日志内容测试".toByteArray(charset("GB18030"))
        val file = File(dir, "gb.log").apply { writeBytes(gbBytes) }
        val tool = DecryptTools.PreviewTextTool(ToolExecutionContext(file))

        val result = tool.execute(DecryptTools.PreviewTextTool.Args(file_path = file.absolutePath))

        assertEquals("中文日志内容测试", result, "GB18030 回退应还原中文")
        dir.deleteRecursively()
    }

    // ── list_dir ──

    @Test
    fun `list_dir formats entries with size and dir marker`() = runTest {
        val dir = tempDir()
        File(dir, "a.txt").writeText("12345")
        File(dir, "sub").mkdirs()
        val tool = DecryptTools.ListDirTool(ToolExecutionContext(dir))

        val result = tool.execute(DecryptTools.ListDirTool.Args(dir_path = dir.absolutePath))

        assertTrue(result.contains("a.txt [5B]"), "实际: $result")
        assertTrue(result.contains("sub [dir]"))
        dir.deleteRecursively()
    }

    // ── B0 结构化结果（2026-09-18 架构升级批）：sink 旁路 + 渲染文案逐字兼容 ──

    @Test
    fun `decrypt_file sink receives structured artifact with exact render text`() = runTest {
        val dir = tempDir()
        val input = File(dir, "sample.alog").apply { writeBytes(syntheticSegment("结构化内容".toByteArray())) }
        val context = ToolExecutionContext(input)
        val received = mutableListOf<Pair<String, ToolResult>>()
        val sink = ToolResultSink.concurrentOf { name, result -> received += name to result }
        val tool = DecryptTools.DecryptFileTool(context, sink) { "unused" }

        val rendered = tool.execute(DecryptTools.DecryptFileTool.Args(file_path = input.absolutePath))

        // 渲染文案与旧字面逐字兼容（runLocalPath 的 startsWith 判断零回归）
        val output = File(dir, "sample.alog.txt")
        assertEquals("解密成功: ${output.absolutePath}", rendered)
        // sink 收到结构化产物（精确路径，消灭兄弟文件启发式）
        assertEquals(1, received.size)
        val (name, result) = received.single()
        assertEquals("decrypt_file", name)
        val ok = result as ToolResult.Ok
        assertEquals(listOf(output.absolutePath), ok.artifacts.map { it.path })
        dir.deleteRecursively()
    }

    @Test
    fun `extract_archive sink receives directory and files`() = runTest {
        val dir = tempDir()
        val archive = File(dir, "logs.zip").apply { writeBytes(zipOf("inner.txt", "c")) }
        val context = ToolExecutionContext(archive)
        val received = mutableListOf<Pair<String, ToolResult>>()
        val sink = ToolResultSink.concurrentOf { name, result -> received += name to result }
        val tool = DecryptTools.ExtractArchiveTool(context, sink)

        val rendered = tool.execute(DecryptTools.ExtractArchiveTool.Args(archive_path = archive.absolutePath))

        assertTrue(rendered.startsWith("解包成功，共 1 个文件:"))
        val ok = received.single().second as ToolResult.Ok
        assertEquals(2, ok.artifacts.size, "解包目录 + 产物文件各一条")
        assertTrue(ok.artifacts.any { it.kind == com.jiaoay.easy_dev_toolbox.core.llm.tools.ArtifactKind.EXTRACTED })
        assertTrue(ok.artifacts.any { it.path.endsWith("inner.txt") })
        dir.deleteRecursively()
    }

    @Test
    fun `denied path emits structured Err with DENIED code`() = runTest {
        val dir = tempDir()
        val input = File(dir, "input.alog").apply { writeBytes(syntheticSegment("x".toByteArray())) }
        val received = mutableListOf<Pair<String, ToolResult>>()
        val sink = ToolResultSink.concurrentOf { name, result -> received += name to result }
        val tool = DecryptTools.DecryptFileTool(ToolExecutionContext(input), sink) { "key" }

        val rendered = tool.execute(DecryptTools.DecryptFileTool.Args(file_path = "/etc/passwd"))

        assertEquals("路径不在本任务允许范围内（只能访问用户拖入的文件与本次解密/解包的产物），请检查路径或改用已登记文件", rendered)
        val err = received.single().second as ToolResult.Err
        assertEquals(ToolResult.ErrCode.DENIED, err.code)
        dir.deleteRecursively()
    }

    @Test
    fun `noop sink keeps legacy construction form working`() = runTest {
        val dir = tempDir()
        val input = File(dir, "legacy.alog").apply { writeBytes(syntheticSegment("旧形态".toByteArray())) }
        // 存量构造形态（sink 缺省）：桥接 BridgeLogTools 三处直接构造零改动
        val tool = DecryptTools.DecryptFileTool(ToolExecutionContext(input)) { "unused" }

        val rendered = tool.execute(DecryptTools.DecryptFileTool.Args(file_path = input.absolutePath))

        assertTrue(rendered.startsWith("解密成功"))
        dir.deleteRecursively()
    }
}
