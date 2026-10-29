package com.jiaoay.xlog.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TEA(XTEA 变体)解密, 自 DecryptUtils.teaDecrypt 原样迁入。
 * 8 字节整块解密, 不足块的部分原样保留。
 *
 * 逐块零分配化（性能热路径）——避免每 8 字节分配
 *    2 个 `ByteArray(8)` + 3 个 `ByteBuffer`（百 MB 加密日志 ⇒ 数千万级小对象，
 *    GC 主嫌疑）。重写为**预分配输出 + 复用单个工作缓冲**，算法/字节序/轮数/密钥
 *    语义逐字保持（回归由既有 XLog 解码用例背书）。
 */
internal object TeaCipher {

    fun decrypt(encryptedData: ByteArray, key: ByteArray): ByteArray {
        require(key.size >= 16) { "tea key must be at least 16 bytes" }
        val num = encryptedData.size shr 3 shl 3
        val ret = ByteBuffer.allocate(encryptedData.size).order(ByteOrder.LITTLE_ENDIAN)
        // 单块工作缓冲：块内 8 字节解出后 put 进 ret，下一块复用（零 per-block 分配）
        val block = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < num) {
            block.clear()
            block.put(encryptedData, i, 8)
            block.flip()
            val v0 = block.getInt().toLong() and 0x0FFFFFFFFL
            val v1 = block.getInt().toLong() and 0x0FFFFFFFFL
            val (o0, o1) = teaDecipherBlock(v0, v1, key)
            ret.putInt(o0.toInt())
            ret.putInt(o1.toInt())
            i += 8
        }
        if (encryptedData.size > num) {
            ret.put(encryptedData, num, encryptedData.size - num)
        }
        return ret.array()
    }

    /** 旧 [bytesToInt] 的直读形态（不再经过中间数组）。 */
    private fun bytesToInt(b: ByteArray, offset: Int): Int {
        return b[offset].toInt() and 0xFF or ((b[offset + 1].toInt() and 0xFF) shl 8) or ((b[offset + 2].toInt() and 0xFF) shl 16) or ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** 单块解密（v0/v1 输入，o0/o1 输出）；轮数/运算/掩码逐字保持旧 [teaDecipher] 语义。 */
    private fun teaDecipherBlock(v0In: Long, v1In: Long, k: ByteArray): Pair<Long, Long> {
        val op = 0xffffffffL
        val delta = 0x9E3779B9L
        var s = (delta shl 4) and op
        var v0 = v0In
        var v1 = v1In
        val k1 = bytesToInt(k, 0).toLong() and 0x0FFFFFFFFL
        val k2 = bytesToInt(k, 4).toLong() and 0x0FFFFFFFFL
        val k3 = bytesToInt(k, 8).toLong() and 0x0FFFFFFFFL
        val k4 = bytesToInt(k, 12).toLong() and 0x0FFFFFFFFL
        var cnt = 16
        while (cnt > 0) {
            v1 = (v1 - (((v0 shl 4) + k3) xor (v0 + s) xor ((v0 shr 5) + k4))) and op
            v0 = (v0 - (((v1 shl 4) + k1) xor (v1 + s) xor ((v1 shr 5) + k2))) and op
            s = (s - delta) and op
            cnt--
        }
        return v0 to v1
    }
}
