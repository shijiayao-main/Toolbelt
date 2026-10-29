package com.jiaoay.xlog

import com.jiaoay.xlog.helpers.XLogTestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LogProbe] 文件级内容探测（文件判型路由积木）：
 * - 合成最小段（0x03 无压缩无加密）→ isXLog 真且 needsPrivateKey 假；
 * - 加密段标记（0x07 ECDH_TEA + zlib）→ needsPrivateKey 真；
 * - 随机垃圾字节 / 文本文件 → isXLog 假；
 * - 前缀垃圾 + 段（parser 跳过垃圾字节的能力）→ isXLog 真；
 * - maxSegments 提前截断 → segmentsFound 封顶。
 */
class LogProbeTest {

    private fun tempFile(name: String, bytes: ByteArray): File =
        File.createTempFile("log-probe-$name", ".bin").apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test
    fun `synthetic uncompressed segment is detected as xlog without private key`() {
        val file = tempFile("plain", XLogTestFactory.segment(mark = 0x03, payload = "hello log".toByteArray()))
        val probe = LogProbe.probe(file)

        assertTrue(probe.isXLog)
        assertEquals(1, probe.segmentsFound)
        assertFalse(probe.needsPrivateKey)
        assertEquals(setOf("NONE"), probe.compressions)
    }

    @Test
    fun `ecd-tea encrypted segment marks needsPrivateKey`() {
        // 0x07 = CompressStart2: ECDH_TEA + ZLIB（64B key 槽位）
        val file = tempFile(
            "encrypted",
            XLogTestFactory.segment(
                mark = 0x07,
                cryptKey = ByteArray(64),
                payload = XLogTestFactory.zlib("encrypted payload".toByteArray()),
            ),
        )
        val probe = LogProbe.probe(file)

        assertTrue(probe.isXLog)
        assertTrue(probe.needsPrivateKey)
        assertEquals(setOf("ZLIB"), probe.compressions)
    }

    @Test
    fun `random garbage bytes are not xlog`() {
        val garbage = ByteArray(64 * 1024) { (it * 31 + 17).toByte() }
        val file = tempFile("garbage", garbage)
        val probe = LogProbe.probe(file)

        assertFalse(probe.isXLog)
        assertEquals(0, probe.segmentsFound)
    }

    @Test
    fun `plain text file is not xlog`() {
        val file = tempFile("text", "2026-09-12 10:00:00.000 INFO some plain text log line\n".repeat(100).toByteArray())
        val probe = LogProbe.probe(file)

        assertFalse(probe.isXLog)
    }

    @Test
    fun `leading garbage before segment is skipped and detected`() {
        // parser 具备跳过垃圾字节找段头的能力（LogSpaceParser 逐字节扫描）
        val leadingGarbage = ByteArray(1024) { 0x41 }
        val file = tempFile(
            "mixed",
            XLogTestFactory.concat(leadingGarbage, XLogTestFactory.segment(mark = 0x03, payload = "after garbage".toByteArray())),
        )
        val probe = LogProbe.probe(file)

        assertTrue(probe.isXLog)
        assertEquals(1, probe.segmentsFound)
    }

    @Test
    fun `maxSegments caps early return`() {
        val segments = (1..5).map { XLogTestFactory.segment(mark = 0x03, seq = it, payload = "seg$it".toByteArray()) }
        val file = tempFile("multi", XLogTestFactory.concat(*segments.toTypedArray()))
        val probe = LogProbe.probe(file, maxSegments = 3)

        assertTrue(probe.isXLog)
        assertEquals(3, probe.segmentsFound, "应在第 3 段提前返回")
    }

    @Test
    fun `missing or empty file yields empty probe`() {
        val empty = tempFile("empty", ByteArray(0))
        assertFalse(LogProbe.probe(empty).isXLog)
        assertFalse(LogProbe.probe(File("/nonexistent/path")).isXLog, "不存在路径零抛异常")
    }
}
