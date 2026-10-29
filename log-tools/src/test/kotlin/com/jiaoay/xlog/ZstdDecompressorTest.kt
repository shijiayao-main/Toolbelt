package com.jiaoay.xlog

import com.github.luben.zstd.Zstd
import com.jiaoay.xlog.codec.ZstdDecompressor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** zstd 截断修复回归: 解压结果超过旧版单次 read 上限(约 1MB)时必须完整 */
class ZstdDecompressorTest {

    @Test
    fun `decompression larger than 1MB is complete`() {
        val original = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val compressed = Zstd.compress(original)
        val decompressed = ZstdDecompressor.decompress(compressed)
        assertTrue(decompressed.contentEquals(original))
    }

    @Test
    fun `decompression larger than 4MB is complete`() {
        val original = ByteArray(4 * 1024 * 1024 + 12345) { ((it * 7) % 253).toByte() }
        val compressed = Zstd.compress(original)
        val decompressed = ZstdDecompressor.decompress(compressed)
        assertTrue(decompressed.contentEquals(original))
    }

    @Test
    fun `small payload roundtrip`() {
        val original = "hello zstd".toByteArray()
        val decompressed = ZstdDecompressor.decompress(Zstd.compress(original))
        assertTrue(decompressed.contentEquals(original))
    }

    @Test
    fun `empty decompressed output does not throw`() {
        val emptyCompressed = Zstd.compress(ByteArray(0))
        val decompressed = ZstdDecompressor.decompress(emptyCompressed)
        assertEquals(0, decompressed.size)
    }
}

