package com.jiaoay.xlog.codec

import com.jiaoay.xlog.StringUtils

/**
 * ECDH 密钥协商 + session 级缓存。
 * 同一文件的数百个加密段通常来自同一设备(公钥相同), 缓存避免重复协商。
 * 生命周期为单次 decodeFile(私钥可能被用户中途更换, 不做跨调用缓存)。
 */
internal class TeaKeyResolver(private val privateKeyHex: String) {

    private val cache = HashMap<String, ByteArray>()

    fun resolve(cryptKeyBytes: ByteArray): ByteArray {
        if (privateKeyHex.isBlank()) {
            throw IllegalArgumentException("the log is encrypted and the private key needs to be entered")
        }
        val publicKeyHex = StringUtils.byteArrayToHexString(bytes = cryptKeyBytes)
        return cache.getOrPut(publicKeyHex) {
            EcdhKeyAgreement.agree(publicKeyBytes = cryptKeyBytes, privateKeyHex = privateKeyHex)
        }
    }
}
