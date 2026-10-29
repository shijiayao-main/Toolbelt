package com.jiaoay.easy_dev_toolbox.core.llm.tools

import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 嗅探与判型解析（纯本地，零 LLM）：
 * - zip 后缀 → archiveHint；UTF-8 文本 → looksUtf8Text；合成 xlog 段 → xlogProbe 命中；
 * - 二进制垃圾 → 文本探测否 + xlog 否；
 * - FileTypeDecision JSON 往返 + 非法 kind 兜底。
 */
class FileSnifferTest {

    private suspend fun sniffBytes(name: String, bytes: ByteArray): SniffSummary {
        val file = File.createTempFile("sniff", ".$name").apply { writeBytes(bytes); deleteOnExit() }
        return FileSniffer.sniff(file)
    }

    @Test
    fun `utf8 text file is detected as text and not xlog`() = kotlinx.coroutines.test.runTest {
        val summary = sniffBytes("txt", "2026-09-12 INFO plain text line\n".repeat(50).toByteArray())
        assertTrue(summary.looksUtf8Text)
        assertFalse(summary.xlogProbe!!.isXLog)
        assertFalse(summary.archiveHint)
        assertTrue(summary.toEvidenceText().contains("UTF-8 文本探测: 是"))
    }

    @Test
    fun `zip suffix yields archive hint`() = kotlinx.coroutines.test.runTest {
        // 最小合法 zip（空条目）：ZipOutputStream 写一个 entry 后 close
        val zipBytes = java.io.ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("inner.txt"))
                zos.write("hello".toByteArray())
                zos.closeEntry()
            }
            bos.toByteArray()
        }
        val summary = sniffBytes("log.zip", zipBytes)
        assertTrue(summary.archiveHint)
        assertFalse(summary.looksUtf8Text, "zip 二进制不应误判为 UTF-8 文本")
    }

    @Test
    fun `synthetic xlog segment is detected by probe`() = kotlinx.coroutines.test.runTest {
        val segment = syntheticXLogSegment(payload = "设备日志内容".toByteArray())
        val summary = sniffBytes("alog", segment)
        assertTrue(summary.xlogProbe!!.isXLog, "合成段应被 LogProbe 命中")
        assertEquals(setOf("NONE"), summary.xlogProbe.compressions)
    }

    /** 手写最小段（0x03 无压缩无加密）：magic+seq2+begin1+end1+len4+key4+payload+尾标0x00 */
    private fun syntheticXLogSegment(payload: ByteArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(1 + 2 + 1 + 1 + 4 + 4 + payload.size + 1)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put(0x03)
        buffer.putShort(0)
        buffer.put(8.toByte())
        buffer.put(9.toByte())
        buffer.putInt(payload.size)
        buffer.put(ByteArray(4))
        buffer.put(payload)
        buffer.put(0x00)
        return buffer.array()
    }

    @Test
    fun `random binary is neither text nor xlog`() = kotlinx.coroutines.test.runTest {
        val garbage = ByteArray(8 * 1024) { (it * 7 + 3).toByte() }
        val summary = sniffBytes("bin", garbage)
        assertFalse(summary.looksUtf8Text)
        assertFalse(summary.xlogProbe!!.isXLog)
        assertFalse(summary.archiveHint)
    }

    @Test
    fun `evidence text contains all key fields`() = kotlinx.coroutines.test.runTest {
        // 临时目录下用确定名建文件（createTempFile 会加随机前缀，文件名断言需要可控名）
        val dir = java.nio.file.Files.createTempDirectory("sniff-evidence").toFile()
        val file = File(dir, "demo.xlog").apply { writeBytes("x".repeat(100).toByteArray()); deleteOnExit() }
        val summary = FileSniffer.sniff(file)
        val evidence = summary.toEvidenceText()
        assertTrue(evidence.contains("文件名: demo.xlog"))
        assertTrue(evidence.contains("后缀: xlog"))
        assertTrue(evidence.contains("首字节(hex):"))
        dir.deleteRecursively()
    }
}

class FileTypeDecisionTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `json round trip preserves all fields`() {
        val decision = FileTypeDecision(kind = "xlog", decryptable = true, reason = "段头 0x03 命中")
        val encoded = json.encodeToString(FileTypeDecision.serializer(), decision)
        val decoded = json.decodeFromString(FileTypeDecision.serializer(), encoded)
        assertEquals(decision, decoded)
    }

    @Test
    fun `parse accepts valid json and lowercases kind`() {
        val result = FileTypeDecision.parse("""{"kind":"Archive","decryptable":false,"reason":"zip 后缀"}""")
        assertTrue(result.isSuccess)
        assertEquals(FileTypeDecision.KIND_ARCHIVE, result.getOrThrow().kind)
    }

    @Test
    fun `parse fails on malformed json`() {
        assertTrue(FileTypeDecision.parse("not json at all").isFailure)
    }

    @Test
    fun `blank kind falls back to unknown`() {
        val result = FileTypeDecision.parse("""{"kind":"","decryptable":false,"reason":"?"}""")
        assertEquals(FileTypeDecision.KIND_UNKNOWN, result.getOrThrow().kind)
    }
}
