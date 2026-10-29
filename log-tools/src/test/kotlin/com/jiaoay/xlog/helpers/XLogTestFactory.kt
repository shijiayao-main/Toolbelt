package com.jiaoay.xlog.helpers

import com.github.luben.zstd.Zstd
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.interfaces.ECPrivateKey
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.Security
import java.util.zip.Deflater

/**
 * 测试用合成 xlog 字节工厂: 按 LogDecode 认识的段格式拼装二进制,
 * 以及 zlib/zstd/TEA 的压缩加密逆函数, 供端到端构造加密段。
 */
object XLogTestFactory {

    /** magic1 + seq2(LE) + beginHour1 + endHour1 + length4(LE) + cryptKey + payload + magicEnd */
    fun segment(
        mark: Byte,
        seq: Int = 0,
        beginHour: Int = 8,
        endHour: Int = 9,
        cryptKey: ByteArray = ByteArray(4),
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 2 + 1 + 1 + 4 + cryptKey.size + payload.size + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(mark)
        buffer.putShort(seq.toShort())
        buffer.put(beginHour.toByte())
        buffer.put(endHour.toByte())
        buffer.putInt(payload.size)
        buffer.put(cryptKey)
        buffer.put(payload)
        buffer.put(0x00)
        return buffer.array()
    }

    fun concat(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(chunks.sumOf { it.size })
        chunks.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** zlib(raw) 压缩, 与 Inflater(true) 配套 */
    fun zlib(data: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val deflater = Deflater(level, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buffer = ByteArray(8 * 1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    fun zstd(data: ByteArray): ByteArray {
        return Zstd.compress(data)
    }

    fun ecdhKeyPair(): Pair<ByteArray, ByteArray> {
        Security.addProvider(BouncyCastleProvider())
        val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val generator = KeyPairGenerator.getInstance("ECDH", "BC")
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()
        val publicEcKey = keyPair.public as ECPublicKey
        // 未压缩点编码 04 || X(32B) || Y(32B), 取后 64 字节并左侧补零
        val encoded = publicEcKey.q.getEncoded(false)
        val publicKey = encoded.copyOfRange(encoded.size - 64, encoded.size)
        val privateKeyHex = (keyPair.private as ECPrivateKey).d.toString(16)
        return publicKey to privateKeyHex.toByteArray()
    }

    /**
     * TEA 加密(teaDecipher 的逆轮), 8 字节整块加密, 不足块的部分原样保留,
     * 与 DecryptUtils.teaDecrypt 的处理方式镜像对称。
     */
    fun teaEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val blockCount = data.size shr 3 shl 3
        val result = ByteBuffer.allocate(data.size).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (offset < blockCount) {
            val block = ByteArray(8)
            ByteBuffer.wrap(data, offset, 8)[block]
            result.put(teaEncryptBlock(block, key))
            offset += 8
        }
        result.put(data.copyOfRange(blockCount, data.size))
        return result.array()
    }

    private fun teaEncryptBlock(block: ByteArray, key: ByteArray): ByteArray {
        val op = 0xffffffffL
        val delta = 0x9E3779B9L
        var sum = 0L
        var v0 = bytesToInt(block, 0).toLong() and 0x0FFFFFFFFL
        var v1 = bytesToInt(block, 4).toLong() and 0x0FFFFFFFFL
        val k1 = bytesToInt(key, 0).toLong() and 0x0FFFFFFFFL
        val k2 = bytesToInt(key, 4).toLong() and 0x0FFFFFFFFL
        val k3 = bytesToInt(key, 8).toLong() and 0x0FFFFFFFFL
        val k4 = bytesToInt(key, 12).toLong() and 0x0FFFFFFFFL
        repeat(16) {
            sum = (sum + delta) and op
            v0 = (v0 + (((v1 shl 4) + k1) xor (v1 + sum) xor ((v1 shr 5) + k2))) and op
            v1 = (v1 + (((v0 shl 4) + k3) xor (v0 + sum) xor ((v0 shr 5) + k4))) and op
        }
        return longToArray(v0, v1)
    }

    private fun longToArray(x: Long, y: Long): ByteArray {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(x.toInt())
        buffer.putInt(y.toInt())
        return buffer.array()
    }

    private fun bytesToInt(b: ByteArray, offset: Int): Int {
        return b[offset].toInt() and 0xFF or ((b[offset + 1].toInt() and 0xFF) shl 8) or ((b[offset + 2].toInt() and 0xFF) shl 16) or ((b[offset + 3].toInt() and 0xFF) shl 24)
    }
}
