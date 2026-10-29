package com.jiaoay.xlog.codec

import com.jiaoay.xlog.StringUtils
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.spec.ECPublicKeySpec
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import javax.crypto.KeyAgreement

/**
 * secp256k1 ECDH 密钥协商, 自 DecryptUtils 迁入。
 * 段头携带的 64 字节 crypt key 是对方公钥的 X||Y, 协商结果作为 TEA 密钥。
 */
internal object EcdhKeyAgreement {

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    data class SecretKey(
        val privateKey: String,
        val publicKey: String
    )

    fun agree(publicKeyBytes: ByteArray, privateKeyHex: String): ByteArray {
        val publicKeyHexStringFormatted = String.format("04%s", StringUtils.byteArrayToHexString(bytes = publicKeyBytes))
        val keyAgreement = KeyAgreement.getInstance("ECDH", "BC")
        keyAgreement.init(loadPrivateKey(data = StringUtils.hexStringToByteArray(hexString = privateKeyHex)))
        keyAgreement.doPhase(loadPublicKey(data = StringUtils.hexStringToByteArray(hexString = publicKeyHexStringFormatted)), true)
        return keyAgreement.generateSecret()
    }

    /** 生成密钥对(测试与工具用途); privateKey 为 64 位补零 hex, publicKey 为 X(补零 32B) || Y(补零 32B) 的 hex */
    fun generateKeyPair(): SecretKey {
        val curveParameterSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val keyPairGenerator = KeyPairGenerator.getInstance("ECDH", "BC")
        keyPairGenerator.initialize(curveParameterSpec)
        val keyPair: KeyPair = keyPairGenerator.generateKeyPair()
        val privateKeyHex = (keyPair.private as ECPrivateKey).d.toString(16)
            .padStart(64, '0')
        val eCPublicKey = keyPair.public as ECPublicKey
        val encoded = eCPublicKey.q.getEncoded(false)
        val publicKeyBytes = encoded.copyOfRange(encoded.size - 64, encoded.size)
        val publicKey = StringUtils.byteArrayToHexString(bytes = publicKeyBytes)
        return SecretKey(privateKey = privateKeyHex, publicKey = publicKey)
    }

    private fun loadPublicKey(data: ByteArray): PublicKey {
        val parameterSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val keySpec = ECPublicKeySpec(parameterSpec.curve.decodePoint(data), parameterSpec)
        val keyAgreement = KeyFactory.getInstance("ECDH", "BC")
        return keyAgreement.generatePublic(keySpec)
    }

    private fun loadPrivateKey(data: ByteArray): PrivateKey {
        val parameterSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val keySpec = ECPrivateKeySpec(BigInteger(1, data), parameterSpec)
        val keyFactory = KeyFactory.getInstance("ECDH", "BC")
        return keyFactory.generatePrivate(keySpec)
    }
}
