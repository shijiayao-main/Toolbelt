package com.jiaoay.xlog.codec

import com.jiaoay.xlog.helpers.XLogTestFactory.teaEncrypt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TeaCipherTest {

    @Test
    fun `encrypt-decrypt roundtrip on 8-byte multiples`() {
        val key = ByteArray(16) { (it * 3 + 1).toByte() }
        val original = ByteArray(64) { (it * 17).toByte() }
        val encrypted = teaEncrypt(original, key)
        val decrypted = TeaCipher.decrypt(encrypted, key)
        assertContentEquals(original, decrypted)
    }

    @Test
    fun `remainder bytes are preserved unchanged`() {
        val key = ByteArray(16) { (it + 7).toByte() }
        val original = ByteArray(11) { (it * 5).toByte() }
        val encrypted = teaEncrypt(original, key)
        // 前 8 字节被加密, 后 3 字节必须原样
        assertContentEquals(original.copyOfRange(8, 11), encrypted.copyOfRange(8, 11))
        val decrypted = TeaCipher.decrypt(encrypted, key)
        assertContentEquals(original, decrypted)
    }

    @Test
    fun `encrypted data differs from original`() {
        val key = ByteArray(16) { it.toByte() }
        val original = "secret payload!!".toByteArray()
        val encrypted = teaEncrypt(original, key)
        assertTrue(!encrypted.contentEquals(original))
    }

    @Test
    fun `key shorter than 16 bytes is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TeaCipher.decrypt(ByteArray(8), ByteArray(15))
        }
    }

    @Test
    fun `decrypt matches legacy implementation signature`() {
        // 与 DecryptUtils.teaDecrypt 相同的数据路径: 每块独立解密
        val key = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val block1 = ByteArray(8) { 0x11 }
        val block2 = ByteArray(8) { 0x22.toByte() }
        val original = block1 + block2
        val decrypted = TeaCipher.decrypt(teaEncrypt(original, key), key)
        assertContentEquals(original, decrypted)
        assertEquals(16, decrypted.size)
    }
}
