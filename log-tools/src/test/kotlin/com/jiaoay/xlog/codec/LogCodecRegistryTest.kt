package com.jiaoay.xlog.codec

import com.github.luben.zstd.Zstd
import com.jiaoay.xlog.helpers.XLogTestFactory.zlib
import com.jiaoay.xlog.parser.MagicStartMark
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogCodecRegistryTest {

    @Test
    fun `0x07 maps to tea decryptor and zlib decompressor`() {
        val codec: LogCodec = LogCodecRegistry.codecFor(MagicStartMark.CompressStart2)
        assertEquals(LogCodec::class.simpleName, codec.javaClass.simpleName)
        // 载荷 = teaEncrypt(zlib(plain)), codec 先解密后解压
        val key = ByteArray(16) { it.toByte() }
        val original = "codec-registry zlib".toByteArray()
        val payload = teaEncryptAll(zlib(original), key)
        assertContentEquals(original, codec.decode(payload, key))
    }

    @Test
    fun `0x0C maps to tea decryptor and zstd decompressor`() {
        val codec = LogCodecRegistry.codecFor(MagicStartMark.AsyncZstdStart)
        val key = ByteArray(16) { (it * 3).toByte() }
        val original = "codec-registry zstd".toByteArray()
        // 载荷 = teaEncrypt(zstd(plain)), codec 先解密后解压
        val payload = teaEncryptAll(Zstd.compress(original), key)
        assertContentEquals(original, codec.decode(payload, key))
    }

    @Test
    fun `0x0D maps to no decryptor and zstd decompressor`() {
        val codec = LogCodecRegistry.codecFor(MagicStartMark.AsyncNoCryptZstdStart)
        val original = "no crypt zstd".toByteArray()
        val compressed = Zstd.compress(original)
        assertContentEquals(original, codec.decode(compressed, null))
    }

    @Test
    fun `plain marks pass data through`() {
        listOf(
            MagicStartMark.NoCompressStart,
            MagicStartMark.NoCompressStart1,
            MagicStartMark.NoCompressNoCryptStart,
            MagicStartMark.CompressStart1,
            MagicStartMark.SyncZstdStart,
            MagicStartMark.SyncNoCryptZstdStart,
        ).forEach { mark ->
            val original = "plain-$mark".toByteArray()
            assertContentEquals(original, LogCodecRegistry.codecFor(mark).decode(original, null))
        }
    }

    @Test
    fun `encrypted codec without tea key fails fast`() {
        val codec = LogCodecRegistry.codecFor(MagicStartMark.CompressStart2)
        val error = kotlin.runCatching { codec.decode(ByteArray(8), null) }
        assertTrue(error.isFailure, "encrypted segment without tea key must fail")
    }

    private fun teaEncryptAll(data: ByteArray, key: ByteArray): ByteArray {
        return com.jiaoay.xlog.helpers.XLogTestFactory.teaEncrypt(data, key)
    }
}
