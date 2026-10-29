package com.jiaoay.xlog

import com.jiaoay.xlog.helpers.XLogTestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 内容判型门面特征测试：后缀不可信场景（已解密文件误挂 .alog 后缀、明文 mmap、
 * 真加密文件、空文件）判型路由正确性。
 */
class LogFormatDetectorTest {

    private fun tempFile(name: String, content: ByteArray): File {
        val dir = kotlin.io.path.createTempDirectory("fmt-detect").toFile()
        return File(dir, name).apply {
            writeBytes(content)
            deleteOnExit()
        }
    }

    @Test
    fun `年份明文内容挂 alog 后缀判为 PLAINTEXT`() {
        val f = tempFile(
            "decoded.alog",
            "2026-09-29 10:00:00.000 D/Main: already decoded\n2026-09-29 10:01:00.000 D/Main: b\n".toByteArray(),
        )
        assertEquals(LogFormatDetector.Format.PLAINTEXT, LogFormatDetector.detect(f))
    }

    @Test
    fun `明文 mmap 标记判为 PLAINTEXT`() {
        val f = tempFile(
            "plain.alog",
            "~~~~~ begin of mmap ~~~~~\n09-29 10:00:00.000 1 2 3 D T: msg\n".toByteArray(),
        )
        assertEquals(LogFormatDetector.Format.PLAINTEXT, LogFormatDetector.detect(f))
    }

    @Test
    fun `裸 MM-dd 明文判为 PLAINTEXT`() {
        val f = tempFile("x.txt", "09-29 10:00:00.000 D/Main: msg\n".toByteArray())
        assertEquals(LogFormatDetector.Format.PLAINTEXT, LogFormatDetector.detect(f))
    }

    @Test
    fun `真 xlog 段结构判为 XLOG_SEGMENTS`() {
        val f = tempFile("real.xlog", XLogTestFactory.segment(mark = 0x03, payload = "hello log".toByteArray()))
        assertEquals(LogFormatDetector.Format.XLOG_SEGMENTS, LogFormatDetector.detect(f))
    }

    @Test
    fun `空文件与随机二进制判为 UNKNOWN`() {
        assertEquals(LogFormatDetector.Format.UNKNOWN, LogFormatDetector.detect(tempFile("empty.alog", ByteArray(0))))
        assertEquals(
            LogFormatDetector.Format.UNKNOWN,
            LogFormatDetector.detect(tempFile("rand.alog", byteArrayOf(0x55, 0x51, 0x33, 0x22, 0x10, 0x44))),
        )
    }

    @Test
    fun `不存在文件判为 UNKNOWN`() {
        assertEquals(
            LogFormatDetector.Format.UNKNOWN,
            LogFormatDetector.detect(File("/nonexistent/path/x.alog")),
        )
    }
}
