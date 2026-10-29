package com.jiaoay.xlog.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EcdhKeyAgreementTest {

    /** ECDH 对称性: A 私钥 × B 公钥 == B 私钥 × A 公钥 */
    @Test
    fun `ecdh key agreement is symmetric`() {
        val alice = EcdhKeyAgreement.generateKeyPair()
        val bob = EcdhKeyAgreement.generateKeyPair()

        val alicePublicBytes = hexToBytes(alice.publicKey)
        val bobPublicBytes = hexToBytes(bob.publicKey)

        val sharedByAlice = EcdhKeyAgreement.agree(bobPublicBytes, alice.privateKey)
        val sharedByBob = EcdhKeyAgreement.agree(alicePublicBytes, bob.privateKey)
        assertContentEquals(sharedByAlice, sharedByBob)
        assertEquals(32, sharedByAlice.size)
    }

    /** 公钥 hex 是 128 字符(X 32B + Y 32B 补零), 与设备端格式兼容 */
    @Test
    fun `generated public key hex is fixed 128 chars`() {
        val pair = EcdhKeyAgreement.generateKeyPair()
        assertEquals(128, pair.publicKey.length)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        val bytes = ByteArray(len)
        for (i in 0 until len) {
            bytes[i] = hex.substring(i * 2, (i + 1) * 2).toInt(16).toByte()
        }
        return bytes
    }
}
