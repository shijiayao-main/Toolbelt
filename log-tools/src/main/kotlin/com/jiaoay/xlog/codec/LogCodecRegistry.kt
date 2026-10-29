package com.jiaoay.xlog.codec

import com.jiaoay.xlog.parser.CompressType
import com.jiaoay.xlog.parser.CryptType
import com.jiaoay.xlog.parser.MagicStartMark

/** 解密策略: 将段载荷解密为明文字节(未加密格式不参与组合) */
internal fun interface LogDecryptor {
    fun decrypt(data: ByteArray, teaKey: ByteArray): ByteArray
}

/** 解压策略: 将明文字节解压为日志内容 */
internal fun interface LogDecompressor {
    fun decompress(data: ByteArray): ByteArray
}

/** 段的解码组合: 解密(可选)+ 解压(可选) */
internal class LogCodec(
    private val decryptor: LogDecryptor?,
    private val decompressor: LogDecompressor?,
) {

    /** @param teaKey 加密段必传, 由 [TeaKeyResolver] 协商 */
    fun decode(logBytes: ByteArray, teaKey: ByteArray?): ByteArray {
        val decrypted = if (decryptor != null) {
            decryptor.decrypt(logBytes, requireNotNull(teaKey) { "encrypted segment requires tea key" })
        } else {
            logBytes
        }
        return decompressor?.decompress(decrypted) ?: decrypted
    }
}

/** 段头标记 → 解码组合的注册中心, 按 mark 元数据自动推导 */
internal object LogCodecRegistry {

    private val decryptors: Map<CryptType, LogDecryptor> = mapOf(
        CryptType.ECDH_TEA to LogDecryptor { data, teaKey ->
            TeaCipher.decrypt(data, teaKey)
        },
    )

    private val decompressors: Map<CompressType, LogDecompressor> = mapOf(
        CompressType.ZLIB to LogDecompressor { ZlibDecompressor.decompress(it) },
        CompressType.ZSTD to LogDecompressor { ZstdDecompressor.decompress(it) },
    )

    fun codecFor(mark: MagicStartMark): LogCodec {
        return LogCodec(
            decryptor = decryptors[mark.crypt],
            decompressor = decompressors[mark.compression],
        )
    }
}
