package com.jiaoay.easy_dev_toolbox.core.llm.tools

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 取证三件 + convert_timestamp 纪律（零 LLM/零网络）：
 * - hash_file：已知向量 + 算法白名单；
 * - hex_dump：16 字节/行 + offset 跳转 + 超长截断；
 * - strings：可打印串提取 + 围栏；
 * - convert_timestamp：10/13 位时间戳 + 日期串 + 非法输入。
 */
class ForensicToolsTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("forensic").toFile()

    // ── hash_file ──

    @Test
    fun `hash_file computes sha256 known vector`() = runTest {
        val dir = tempDir()
        val file = File(dir, "data.bin").apply { writeBytes("hello".toByteArray()) }
        val tool = ForensicTools.HashFileTool(ToolExecutionContext(file))

        val ok = tool.executeTyped(ForensicTools.HashFileTool.Args(file.absolutePath))

        assertTrue(ok is ToolResult.Ok)
        assertTrue(
            (ok as ToolResult.Ok).summary.contains("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),
            "sha256('hello') 已知向量",
        )
        dir.deleteRecursively()
    }

    @Test
    fun `hash_file rejects unknown algorithm`() = runTest {
        val dir = tempDir()
        val file = File(dir, "x").apply { writeText("x") }
        val tool = ForensicTools.HashFileTool(ToolExecutionContext(file))

        val err = tool.executeTyped(ForensicTools.HashFileTool.Args(file.absolutePath, algo = "sha1"))

        assertTrue(err is ToolResult.Err && err.code == ToolResult.ErrCode.BAD_ARGS, "算法白名单外必须拒")
        dir.deleteRecursively()
    }

    // ── hex_dump ──

    @Test
    fun `hex_dump renders offset and ascii column`() = runTest {
        val dir = tempDir()
        val file = File(dir, "blob.bin").apply { writeBytes("ABCDE".toByteArray()) }
        val tool = ForensicTools.HexDumpTool(ToolExecutionContext(file))

        val ok = tool.executeTyped(ForensicTools.HexDumpTool.Args(file.absolutePath, offset = 0, length = 16))

        assertTrue(ok is ToolResult.Ok)
        val summary = (ok as ToolResult.Ok).summary
        assertTrue(summary.startsWith("00000000"), "首行偏移标注: $summary")
        assertTrue(summary.contains("41 42 43 44 45"), "hex 列: $summary")
        assertTrue(summary.contains("|ABCDE|"), "ASCII 列: $summary")
        dir.deleteRecursively()
    }

    @Test
    fun `hex_dump caps length at 4096`() = runTest {
        val dir = tempDir()
        val file = File(dir, "big.bin").apply { writeBytes(ByteArray(8192) { 0x41 }) }
        val tool = ForensicTools.HexDumpTool(ToolExecutionContext(file))

        val ok = tool.executeTyped(ForensicTools.HexDumpTool.Args(file.absolutePath, offset = 0, length = 99999))

        assertTrue(ok is ToolResult.Ok)
        assertTrue((ok as ToolResult.Ok).summary.contains("dumped 4096B"), "length 必须 cap 到 4096")
        dir.deleteRecursively()
    }

    // ── strings ──

    @Test
    fun `strings extracts printable runs above min length`() = runTest {
        val dir = tempDir()
        val bytes = byteArrayOf(
            0x00, 0x01, 0x02,
            0x68, 0x69, 0x00, // "hi" 短于 min_length=4 → 不收
            0x68, 0x74, 0x74, 0x70, 0x3a, 0x2f, 0x2f, 0x61, 0x2e, 0x63, 0x6f, 0x6d, 0x00, // "http://a.com"
        )
        val file = File(dir, "bin").apply { writeBytes(bytes) }
        val tool = ForensicTools.StringsTool(ToolExecutionContext(file))

        val ok = tool.executeTyped(ForensicTools.StringsTool.Args(file.absolutePath, min_length = 4))

        assertTrue(ok is ToolResult.Ok)
        val summary = (ok as ToolResult.Ok).summary
        assertTrue(summary.contains("http://a.com"), "应提取长串: $summary")
        assertTrue(!summary.contains("\nhi\n") && !summary.startsWith("hi\n"), "短于 min_length 不收")
        dir.deleteRecursively()
    }

    // ── convert_timestamp ──

    @Test
    fun `convert_timestamp handles 13 digit millis`() = runTest {
        val tool = ConvertTimestampTool()
        val ok = tool.executeTyped(ConvertTimestampTool.Args("1756790400000"))
        assertTrue(ok is ToolResult.Ok)
        assertTrue((ok as ToolResult.Ok).summary.contains("1756790400000 → "))
    }

    @Test
    fun `convert_timestamp handles datetime string`() = runTest {
        val tool = ConvertTimestampTool()
        val ok = tool.executeTyped(ConvertTimestampTool.Args("2026-01-01 00:00:00"))
        assertTrue(ok is ToolResult.Ok)
        assertTrue((ok as ToolResult.Ok).summary.contains("毫秒 "))
    }

    @Test
    fun `convert_timestamp rejects garbage`() = runTest {
        val tool = ConvertTimestampTool()
        val err = tool.executeTyped(ConvertTimestampTool.Args("not-a-timestamp!!"))
        assertTrue(err is ToolResult.Err && err.code == ToolResult.ErrCode.BAD_ARGS)
    }

    @Test
    fun `timestamp converter thin delegate matches core implementation`() {
        // 下沉回归锚：feature-timestamp 的薄委托与 :core Timestamps 输出一致（防复制漂移复发）
        val result = com.jiaoay.easy_dev_toolbox.core.extensions.Timestamps.convert(
            "1756790400",
            "yyyy-MM-dd_HH:mm:ss.SSS",
        )
        assertTrue(result is com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult.FromTimestamp)
        assertEquals(1756790400000L, (result as com.jiaoay.easy_dev_toolbox.core.extensions.ConversionResult.FromTimestamp).millis)
    }
}
