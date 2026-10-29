package com.jiaoay.xlog

import com.jiaoay.xlog.helpers.XLogTestFactory.concat
import com.jiaoay.xlog.helpers.XLogTestFactory.segment
import kotlin.test.Test
import kotlin.test.assertEquals
import java.io.File
import java.nio.file.Files

/**
 * 特征化测试: 用合成 xlog 字节驱动当前 LogDecode, 固化既有输出行为,
 * 重构期间任何非预期变化都会在这里暴露。已知的缺陷行为(如段边界拼行)
 * 以显式期望值锁定, 重构后有意的修复需同步修改并注明 intentional change。
 */
class LogDecodeCharacterizationTest {

    private val markNoCompress: Byte = 0x03
    private val markNoCompressNoCrypt: Byte = 0x08

    private fun decodeToString(bytes: ByteArray): String {
        val dir = tempDir()
        val logFile = File(dir, "input.xlog")
        val outFile = File(dir, "input.txt")
        logFile.writeBytes(bytes)
        try {
            LogDecode().decodeFile(privateKey = "", logFile = logFile, outFile = outFile)
            return outFile.readText()
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 单段未压缩: 原文原样输出 */
    @Test
    fun `single no-compress segment passes payload through`() {
        val payload = "2024-06-06 hello xlog\n"
        val bytes = segment(mark = markNoCompress, payload = payload.toByteArray())
        assertEquals(payload, decodeToString(bytes))
    }

    /** 多段: 原文顺序拼接 */
    @Test
    fun `multiple segments are concatenated in order`() {
        val first = "first\n"
        val second = "second\n"
        val bytes = concat(
            segment(mark = markNoCompress, seq = 1, payload = first.toByteArray()),
            segment(mark = markNoCompress, seq = 2, payload = second.toByteArray()),
        )
        assertEquals(first + second, decodeToString(bytes))
    }

    /**
     * 段以换行结尾时: lineSequence 的尾随空串经 joinToString 还原为换行, 边界不拼行。
     * 注意前段无尾换行的拼行场景见下方用例。
     */
    @Test
    fun `segment boundary keeps trailing newline`() {
        val first = "[Asia/Shanghai]-1700000000000 first\n"
        val second = "[Asia/Shanghai]-1700000000001 second\n"
        val bytes = concat(
            segment(mark = markNoCompress, seq = 1, payload = first.toByteArray()),
            segment(mark = markNoCompress, seq = 2, payload = second.toByteArray()),
        )
        assertEquals(
            "11-15 06:13:20.000 first\n11-15 06:13:20.001 second\n",
            decodeToString(bytes)
        )
    }

    /**
     * intentional bug(重构时修复): 前段末尾无换行时, joinToString 不产生分隔符,
     * 后段内容直接拼接到前段末行。
     */
    @Test
    fun `segment without trailing newline merges with next segment`() {
        val first = "[Asia/Shanghai]-1700000000000 first"
        val second = "[Asia/Shanghai]-1700000000001 second\n"
        val bytes = concat(
            segment(mark = markNoCompress, seq = 1, payload = first.toByteArray()),
            segment(mark = markNoCompress, seq = 2, payload = second.toByteArray()),
        )
        assertEquals(
            "11-15 06:13:20.000 first11-15 06:13:20.001 second\n",
            decodeToString(bytes)
        )
    }

    /** 整段不以 [ 开头: 快速路径, 不做任何 normalize */
    @Test
    fun `non-bracket content bypasses normalization`() {
        val payload = "plain content [not-a-timestamp]\n"
        val bytes = segment(mark = markNoCompress, payload = payload.toByteArray())
        assertEquals(payload, decodeToString(bytes))
    }

    /** 行首时间戳归一化: [时区]-毫秒 → MM-dd HH:mm:ss.SSS */
    @Test
    fun `timezone timestamp is normalized to readable time`() {
        // 1700000000000 = 2023-11-14 22:13:20 UTC = 2023-11-14 17:13:20 America/New_York
        val payload = "[America/New_York]-1700000000000 log line\n"
        val bytes = segment(mark = markNoCompress, payload = payload.toByteArray())
        assertEquals("11-14 17:13:20.000 log line\n", decodeToString(bytes))
    }

    /** 畸形时区: 回退到系统默认时区 */
    @Test
    fun `invalid timezone falls back to system default`() {
        val payload = "[Not/AZone]-1700000000000 log line\n"
        val bytes = segment(mark = markNoCompress, payload = payload.toByteArray())
        val expected = java.time.Instant.ofEpochMilli(1700000000000L)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS"))
        assertEquals("$expected log line\n", decodeToString(bytes))
    }

    /** 段前垃圾字节被跳过 */
    @Test
    fun `garbage prefix is skipped`() {
        val payload = "payload\n"
        val bytes = concat(byteArrayOf(0x00, 0x01, 0x02), segment(mark = markNoCompress, payload = payload.toByteArray()))
        assertEquals(payload, decodeToString(bytes))
    }

    /** EOF 截断的尾段被静默丢弃, 前面完整段正常输出 */
    @Test
    fun `incomplete tail segment is silently dropped`() {
        val bytes = concat(
            segment(mark = markNoCompress, payload = "kept\n".toByteArray()),
            segment(mark = markNoCompress, payload = "lost".toByteArray()).copyOfRange(0, 6),
        )
        assertEquals("kept\n", decodeToString(bytes))
    }

    /** 64 字节 cryptKey 段(0x08) */
    @Test
    fun `64-byte crypt key segment passes payload through`() {
        val payload = "no-crypt-64\n"
        val bytes = segment(
            mark = markNoCompressNoCrypt,
            cryptKey = ByteArray(64) { it.toByte() },
            payload = payload.toByteArray(),
        )
        assertEquals(payload, decodeToString(bytes))
    }

    /** 全垃圾文件: 无输出, 无异常 */
    @Test
    fun `all-garbage file produces empty output`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x40, 0x50)
        assertEquals("", decodeToString(bytes))
    }

    /** 空文件: 无输出, 无异常 */
    @Test
    fun `empty file produces empty output`() {
        assertEquals("", decodeToString(ByteArray(0)))
    }

    private fun tempDir(): File = Files.createTempDirectory("characterization").toFile()
}

