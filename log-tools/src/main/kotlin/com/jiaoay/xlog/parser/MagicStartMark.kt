package com.jiaoay.xlog.parser

import com.jiaoay.xlog.parser.MagicStartMark.CompressNoCryptStart
import com.jiaoay.xlog.parser.MagicStartMark.CompressStart
import com.jiaoay.xlog.parser.MagicStartMark.CompressStart1
import com.jiaoay.xlog.parser.MagicStartMark.CompressStart2
import com.jiaoay.xlog.parser.MagicStartMark.NoCompressNoCryptStart
import com.jiaoay.xlog.parser.MagicStartMark.NoCompressStart
import com.jiaoay.xlog.parser.MagicStartMark.NoCompressStart1
import com.jiaoay.xlog.parser.MagicStartMark.AsyncNoCryptZstdStart
import com.jiaoay.xlog.parser.MagicStartMark.AsyncZstdStart
import com.jiaoay.xlog.parser.MagicStartMark.SyncNoCryptZstdStart
import com.jiaoay.xlog.parser.MagicStartMark.SyncZstdStart

/** 加密方式(段头标记携带的能力元数据) */
internal enum class CryptType {
    NONE,
    /** 64 字节公钥 + ECDH 协商 TEA 密钥 */
    ECDH_TEA,
}

/** 压缩方式(段头标记携带的能力元数据) */
internal enum class CompressType {
    NONE,
    ZLIB,
    ZSTD,
}

/**
 * 段头 magic 标记, 每项携带该格式的解码能力元数据,
 * 解析/解码的按 mark 分发全部由此推导。
 */
internal enum class MagicStartMark(
    val mark: Byte,
    val cryptKeyByteSize: Int,
    val crypt: CryptType,
    val compression: CompressType,
) {
    NoCompressStart(mark = 0x03, cryptKeyByteSize = 4, CryptType.NONE, CompressType.NONE),
    NoCompressStart1(mark = 0x06, cryptKeyByteSize = 64, CryptType.NONE, CompressType.NONE),
    NoCompressNoCryptStart(mark = 0x08, cryptKeyByteSize = 64, CryptType.NONE, CompressType.NONE),

    // 0x04/0x05: 仅 0x04 走 zlib; 0x05 名实不符, 原样输出(与既有行为保持一致——解码兼容约定)
    CompressStart(mark = 0x04, cryptKeyByteSize = 4, CryptType.NONE, CompressType.ZLIB),
    CompressStart1(mark = 0x05, cryptKeyByteSize = 4, CryptType.NONE, CompressType.NONE),
    CompressStart2(mark = 0x07, cryptKeyByteSize = 64, CryptType.ECDH_TEA, CompressType.ZLIB),
    CompressNoCryptStart(mark = 0x09, cryptKeyByteSize = 64, CryptType.NONE, CompressType.ZLIB),

    // 0x0A/0x0B: 名实不符, 原样输出(与既有行为保持一致, 待真实日志确认)
    SyncZstdStart(mark = 0x0A, cryptKeyByteSize = 64, CryptType.NONE, CompressType.NONE),
    SyncNoCryptZstdStart(mark = 0x0B, cryptKeyByteSize = 64, CryptType.NONE, CompressType.NONE),
    AsyncZstdStart(mark = 0x0C, cryptKeyByteSize = 64, CryptType.ECDH_TEA, CompressType.ZSTD),
    AsyncNoCryptZstdStart(mark = 0x0D, cryptKeyByteSize = 64, CryptType.NONE, CompressType.ZSTD);

    companion object {

        private val LOOKUP: Array<MagicStartMark?> = buildLookup()

        private fun buildLookup(): Array<MagicStartMark?> {
            val table = arrayOfNulls<MagicStartMark>(256)
            entries.forEach { table[it.mark.toInt() and 0xFF] = it }
            return table
        }

        fun of(mark: Byte): MagicStartMark? = LOOKUP[mark.toInt() and 0xFF]
    }
}
