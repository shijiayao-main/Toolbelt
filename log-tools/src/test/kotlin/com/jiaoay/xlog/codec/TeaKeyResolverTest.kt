package com.jiaoay.xlog.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TeaKeyResolverTest {

    @Test
    fun `same public key resolves to cached key`() {
        val pair = EcdhKeyAgreement.generateKeyPair()
        val resolver = TeaKeyResolver(privateKeyHex = pair.privateKey)
        val publicKeyBytes = hexToBytes(pair.publicKey)

        val first = resolver.resolve(publicKeyBytes)
        val second = resolver.resolve(publicKeyBytes)
        assertContentEquals(first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun `blank private key throws with legacy message`() {
        val resolver = TeaKeyResolver(privateKeyHex = "")
        val error = assertFailsWith<IllegalArgumentException> {
            resolver.resolve(ByteArray(64))
        }
        assertEquals("the log is encrypted and the private key needs to be entered", error.message)
    }

    @Test
    fun `resolve matches cross-pair agreement`() {
        val alice = EcdhKeyAgreement.generateKeyPair()
        val bob = EcdhKeyAgreement.generateKeyPair()
        val resolver = TeaKeyResolver(privateKeyHex = alice.privateKey)
        val byResolver = resolver.resolve(hexToBytes(bob.publicKey))
        val direct = EcdhKeyAgreement.agree(hexToBytes(bob.publicKey), alice.privateKey)
        assertContentEquals(direct, byResolver)
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
