package com.jiaoay.xlog.parser

import com.jiaoay.xlog.helpers.XLogTestFactory.concat
import com.jiaoay.xlog.helpers.XLogTestFactory.segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogSpaceParserTest {

    private val markNoCompress: Byte = 0x03
    private val markNoCompressNoCrypt: Byte = 0x08

    private fun parseFirst(bytes: ByteArray): LogSpaceParser.Outcome {
        return LogSpaceParser.parse(window = bytes, from = 0, to = bytes.size, remainingFileBytes = 0)
    }

    @Test
    fun `single 0x03 segment parses all header fields`() {
        val payload = "hello".toByteArray()
        val cryptKey = byteArrayOf(1, 2, 3, 4)
        val bytes = segment(
            mark = markNoCompress, seq = 0x0102, beginHour = 8, endHour = 9,
            cryptKey = cryptKey, payload = payload
        )
        val outcome = parseFirst(bytes)
        val slice = assertIs<LogSpaceParser.Outcome.Segment>(outcome).slice
        assertEquals(MagicStartMark.NoCompressStart, slice.magicStartMark)
        assertEquals(0x0102, slice.seq)
        assertEquals(8, slice.beginHour)
        assertEquals(9, slice.endHour)
        assertTrue(cryptKey.contentEquals(slice.cryptKeyBytes))
        assertEquals(payload.size, slice.logSize)
        assertEquals(bytes.size, slice.totalSize)
        assertTrue(payload.contentEquals(slice.logBytes()))
    }

    @Test
    fun `single 0x08 segment with 64-byte crypt key`() {
        val payload = "no-crypt".toByteArray()
        val cryptKey = ByteArray(64) { it.toByte() }
        val bytes = segment(mark = markNoCompressNoCrypt, cryptKey = cryptKey, payload = payload)
        val slice = assertIs<LogSpaceParser.Outcome.Segment>(parseFirst(bytes)).slice
        assertEquals(MagicStartMark.NoCompressNoCryptStart, slice.magicStartMark)
        assertEquals(64, slice.cryptKeyBytes.size)
        assertEquals(payload.size, slice.logSize)
    }

    /** 元数据锁定表: 与既有解码行为一致(0x05/0x0A/0x0B 名实不符, 按原样输出处理) */
    @Test
    fun `mark capability metadata table is locked`() {
        fun meta(mark: Byte) = MagicStartMark.of(mark)!!
        assertEquals(4 to CryptType.NONE to CompressType.NONE, meta(0x03).let { it.cryptKeyByteSize to it.crypt to it.compression })
        assertEquals(4, meta(0x04).cryptKeyByteSize); assertEquals(CompressType.ZLIB, meta(0x04).compression); assertEquals(CryptType.NONE, meta(0x04).crypt)
        assertEquals(4, meta(0x05).cryptKeyByteSize); assertEquals(CompressType.NONE, meta(0x05).compression); assertEquals(CryptType.NONE, meta(0x05).crypt)
        assertEquals(64, meta(0x06).cryptKeyByteSize); assertEquals(CompressType.NONE, meta(0x06).compression); assertEquals(CryptType.NONE, meta(0x06).crypt)
        assertEquals(64, meta(0x07).cryptKeyByteSize); assertEquals(CompressType.ZLIB, meta(0x07).compression); assertEquals(CryptType.ECDH_TEA, meta(0x07).crypt)
        assertEquals(64, meta(0x08).cryptKeyByteSize); assertEquals(CompressType.NONE, meta(0x08).compression); assertEquals(CryptType.NONE, meta(0x08).crypt)
        assertEquals(64, meta(0x09).cryptKeyByteSize); assertEquals(CompressType.ZLIB, meta(0x09).compression); assertEquals(CryptType.NONE, meta(0x09).crypt)
        assertEquals(64, meta(0x0A).cryptKeyByteSize); assertEquals(CompressType.NONE, meta(0x0A).compression); assertEquals(CryptType.NONE, meta(0x0A).crypt)
        assertEquals(64, meta(0x0B).cryptKeyByteSize); assertEquals(CompressType.NONE, meta(0x0B).compression); assertEquals(CryptType.NONE, meta(0x0B).crypt)
        assertEquals(64, meta(0x0C).cryptKeyByteSize); assertEquals(CompressType.ZSTD, meta(0x0C).compression); assertEquals(CryptType.ECDH_TEA, meta(0x0C).crypt)
        assertEquals(64, meta(0x0D).cryptKeyByteSize); assertEquals(CompressType.ZSTD, meta(0x0D).compression); assertEquals(CryptType.NONE, meta(0x0D).crypt)
    }

    @Test
    fun `unknown mark bytes have no mapping`() {
        assertNull(MagicStartMark.of(0x00))
        assertNull(MagicStartMark.of(0x02))
        assertNull(MagicStartMark.of(0x0E))
        assertNull(MagicStartMark.of(0x7F.toByte()))
    }

    @Test
    fun `consecutive segments parse in order`() {
        val bytes = concat(
            segment(mark = markNoCompress, seq = 1, payload = "one".toByteArray()),
            segment(mark = markNoCompress, seq = 2, payload = "two".toByteArray()),
        )
        var offset = 0
        val seqs = mutableListOf<Int>()
        while (true) {
            val outcome = LogSpaceParser.parse(bytes, offset, bytes.size, remainingFileBytes = 0)
            val slice = when (outcome) {
                is LogSpaceParser.Outcome.Segment -> outcome.slice
                is LogSpaceParser.Outcome.NoCompleteSegment -> break
            }
            seqs.add(slice.seq)
            offset += slice.totalSize
        }
        assertEquals(listOf(1, 2), seqs)
        assertEquals(bytes.size, offset)
    }

    @Test
    fun `zero-length payload segment is valid`() {
        val bytes = segment(mark = markNoCompress, payload = ByteArray(0))
        val slice = assertIs<LogSpaceParser.Outcome.Segment>(parseFirst(bytes)).slice
        assertEquals(0, slice.logSize)
        assertEquals(bytes.size, slice.totalSize)
    }

    @Test
    fun `garbage prefix is skipped and resume from segment start`() {
        val seg = segment(mark = markNoCompress, payload = "ok".toByteArray())
        val bytes = concat(byteArrayOf(0x01, 0x40, 0x50, 0x02), seg)
        val slice = assertIs<LogSpaceParser.Outcome.Segment>(parseFirst(bytes)).slice
        assertEquals(seg.size, slice.totalSize)
        assertTrue("ok".toByteArray().contentEquals(slice.logBytes()))
    }

    @Test
    fun `truncated header returns no complete segment at start`() {
        val bytes = segment(mark = markNoCompress, payload = "x".toByteArray()).copyOfRange(0, 5)
        val outcome = parseFirst(bytes)
        assertIs<LogSpaceParser.Outcome.NoCompleteSegment>(outcome)
    }

    @Test
    fun `truncated payload returns no complete segment at candidate position`() {
        val full = segment(mark = markNoCompress, payload = "payload".toByteArray())
        val bytes = full.copyOfRange(0, full.size - 3)
        val outcome = parseFirst(bytes)
        assertIs<LogSpaceParser.Outcome.NoCompleteSegment>(outcome)
    }

    @Test
    fun `missing end mark with enough remaining bytes keeps waiting`() {
        val full = segment(mark = markNoCompress, payload = "payload".toByteArray())
        // 缺尾字节 0x00, 但 remainingFileBytes 表明文件还有后续数据 → 挂起等数据
        val bytes = full.copyOfRange(0, full.size - 1)
        val outcome = LogSpaceParser.parse(bytes, 0, bytes.size, remainingFileBytes = 100)
        assertIs<LogSpaceParser.Outcome.NoCompleteSegment>(outcome)
    }

    @Test
    fun `missing end mark at eof is treated as invalid garbage`() {
        val full = segment(mark = markNoCompress, payload = "payload".toByteArray())
        val bytes = full.copyOfRange(0, full.size - 1)
        val outcome = LogSpaceParser.parse(bytes, 0, bytes.size, remainingFileBytes = 0)
        assertIs<LogSpaceParser.Outcome.NoCompleteSegment>(outcome)
    }

    @Test
    fun `negative length is rejected and scanning continues`() {
        val good = segment(mark = markNoCompress, payload = "good".toByteArray())
        // 长度字段写 -1(0xFFFFFFFF)
        val badHeader = byteArrayOf(
            0x03, 0, 0, 8, 9,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val bytes = concat(badHeader, good)
        val slice = assertIs<LogSpaceParser.Outcome.Segment>(parseFirst(bytes)).slice
        assertTrue("good".toByteArray().contentEquals(slice.logBytes()))
    }

    @Test
    fun `oversized length beyond file size is rejected and scanning continues`() {
        val good = segment(mark = markNoCompress, payload = "good".toByteArray())
        // 长度写 100MB, remainingFileBytes = 0 → 不可能, 跳过
        val badHeader = byteArrayOf(0x03, 0, 0, 8, 9, 0x00, 0x00, 0x40, 0x06)
        val bytes = concat(badHeader, good)
        val slice = assertIs<LogSpaceParser.Outcome.Segment>(parseFirst(bytes)).slice
        assertTrue("good".toByteArray().contentEquals(slice.logBytes()))
    }

    @Test
    fun `fake magic inside payload with failed checksum is ignored`() {
        // 载荷内含伪 magic + 假长度, 但 endMark 校验不过 → 正常解析外层段
        val fakeInner = byteArrayOf(0x03, 0, 0, 8, 9, 0x40, 0x00, 0x00, 0x00, 0x01, 0x02)
        val payload = concat(fakeInner, "real".toByteArray())
        val bytes = segment(mark = markNoCompress, payload = payload)
        val slice = assertIs<LogSpaceParser.Outcome.Segment>(parseFirst(bytes)).slice
        assertEquals(payload.size, slice.logSize)
        assertTrue(payload.contentEquals(slice.logBytes()))
    }

    @Test
    fun `window smaller than header returns no complete segment`() {
        val outcome = LogSpaceParser.parse(ByteArray(8), 0, 8, remainingFileBytes = 100)
        assertIs<LogSpaceParser.Outcome.NoCompleteSegment>(outcome)
    }

    @Test
    fun `empty window returns no complete segment`() {
        val outcome = LogSpaceParser.parse(ByteArray(0), 0, 0, remainingFileBytes = 0)
        assertIs<LogSpaceParser.Outcome.NoCompleteSegment>(outcome)
    }

    @Test
    fun `parse from mid-window offset works`() {
        val seg = segment(mark = markNoCompress, payload = "mid".toByteArray())
        val prefix = byteArrayOf(0x01, 0x02, 0x03)
        val bytes = concat(prefix, seg)
        val slice = assertIs<LogSpaceParser.Outcome.Segment>(
            LogSpaceParser.parse(bytes, from = 1, to = bytes.size, remainingFileBytes = 0)
        ).slice
        assertEquals(seg.size, slice.totalSize)
        assertTrue("mid".toByteArray().contentEquals(slice.logBytes()))
    }
}
