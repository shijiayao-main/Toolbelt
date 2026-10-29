package com.jiaoay.xlog.stream

import com.jiaoay.xlog.helpers.XLogTestFactory.concat
import com.jiaoay.xlog.helpers.XLogTestFactory.segment
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XLogChunkReaderTest {

    private val markNoCompress: Byte = 0x03
    private val markNoCompressNoCrypt: Byte = 0x08

    private fun readAll(bytes: ByteArray, chunkSize: Int): Pair<List<String>, Long> {
        val reader = XLogChunkReader(
            input = ByteArrayInputStream(bytes),
            fileLengthBytes = bytes.size.toLong(),
            chunkSizeBytes = chunkSize,
        )
        val payloads = mutableListOf<String>()
        val summary = reader.forEachSlice { slice ->
            payloads.add(String(slice.logBytes()))
            null
        }
        return payloads to summary.droppedTailBytes
    }

    @Test
    fun `segment crossing chunk boundary is parsed`() {
        val bytes = concat(
            segment(mark = markNoCompress, seq = 1, payload = "first\n".toByteArray()),
            segment(mark = markNoCompress, seq = 2, payload = "second\n".toByteArray()),
        )
        val (payloads, dropped) = readAll(bytes, chunkSize = 16)
        assertEquals(listOf("first\n", "second\n"), payloads)
        assertEquals(0L, dropped)
    }

    @Test
    fun `segment larger than chunk triggers window growth`() {
        val bigPayload = "x".repeat(1000) + "\n"
        val bytes = segment(mark = markNoCompress, payload = bigPayload.toByteArray())
        val (payloads, _) = readAll(bytes, chunkSize = 16)
        assertEquals(listOf(bigPayload), payloads)
    }

    @Test
    fun `random segment soup with tiny chunks equals whole-buffer parse`() {
        val soup = concat(
            byteArrayOf(0x01, 0x40),                       // 垃圾
            segment(mark = markNoCompress, seq = 1, payload = "one\n".toByteArray()),
            segment(mark = markNoCompressNoCrypt, cryptKey = ByteArray(64), payload = "two\n".toByteArray()),
            byteArrayOf(0x50, 0x60, 0x70),                 // 垃圾
            segment(mark = markNoCompress, seq = 3, payload = ("three-" + "y".repeat(50) + "\n").toByteArray()),
            segment(mark = markNoCompress, seq = 4, payload = "four\n".toByteArray()),
        )
        // 整 buffer 解析(等价于旧版全量读入)
        val wholeWindowPayloads = mutableListOf<String>()
        var offset = 0
        while (true) {
            val outcome = com.jiaoay.xlog.parser.LogSpaceParser.parse(
                soup, offset, soup.size, remainingFileBytes = 0
            )
            val slice = when (outcome) {
                is com.jiaoay.xlog.parser.LogSpaceParser.Outcome.Segment -> outcome.slice
                is com.jiaoay.xlog.parser.LogSpaceParser.Outcome.NoCompleteSegment -> break
            }
            wholeWindowPayloads.add(String(slice.logBytes()))
            offset += slice.totalSize
        }
        // 各种 chunk 尺寸下流式输出必须与整 buffer 一致
        for (chunkSize in listOf(1, 5, 16, 64, 128, soup.size)) {
            val (streamed, _) = readAll(soup, chunkSize)
            assertEquals(wholeWindowPayloads, streamed, "chunkSize=$chunkSize mismatch")
        }
    }

    @Test
    fun `all-garbage file produces no output and no error`() {
        val (payloads, dropped) = readAll(byteArrayOf(0x01, 0x02, 0x40, 0x50, 0x60), chunkSize = 2)
        assertEquals(emptyList(), payloads)
        assertEquals(0L, dropped)
    }

    @Test
    fun `empty file produces no output`() {
        val (payloads, _) = readAll(ByteArray(0), chunkSize = 16)
        assertEquals(emptyList(), payloads)
    }

    @Test
    fun `incomplete tail segment is dropped and counted`() {
        val complete = segment(mark = markNoCompress, payload = "kept\n".toByteArray())
        val truncated = segment(mark = markNoCompress, payload = "lost".toByteArray()).copyOfRange(0, 6)
        val (payloads, dropped) = readAll(concat(complete, truncated), chunkSize = 8)
        assertEquals(listOf("kept\n"), payloads)
        assertEquals(6L, dropped)
    }

    @Test
    fun `slice window is only valid inside callback`() {
        // 记录切片位置, 在回调内立即拷贝, 模拟"同步消费"契约
        val bytes = concat(
            segment(mark = markNoCompress, seq = 1, payload = "aaa".toByteArray()),
            segment(mark = markNoCompress, seq = 2, payload = "bbb".toByteArray()),
        )
        val copies = mutableListOf<ByteArray>()
        val reader = XLogChunkReader(
            input = ByteArrayInputStream(bytes),
            fileLengthBytes = bytes.size.toLong(),
            chunkSizeBytes = 4,
        )
        reader.forEachSlice { slice ->
            copies.add(slice.logBytes())
            null
        }
        assertEquals(2, copies.size)
        assertTrue(copies[0].contentEquals("aaa".toByteArray()))
        assertTrue(copies[1].contentEquals("bbb".toByteArray()))
    }

    @Test
    fun `invalid chunk size is rejected`() {
        val error = kotlin.runCatching {
            XLogChunkReader(ByteArrayInputStream(ByteArray(0)), 0, chunkSizeBytes = 0)
        }
        assertTrue(error.isFailure)
    }
}
