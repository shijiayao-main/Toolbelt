package com.jiaoay.xlog

import com.jiaoay.xlog.parser.CryptType
import com.jiaoay.xlog.parser.MagicStartMark
import com.jiaoay.xlog.stream.XLogChunkReader
import java.io.File
import java.io.FileInputStream

/**
 * xlog 内容级探测（文件判型路由积木）：只读头部窗口判断文件是否为
 * xlog/alog 格式，零解码、零密钥、零输出——与 [LogDecode] 的「全量解密」相对。
 *
 * 现状边界：[MagicStartMark]/[LogSpaceParser]/[XLogChunkReader] 均 internal（模块内实现细节），
 * 本 object 是文件级探测的**唯一 public 入口**，供 agent 文件嗅探（core/llm/tools/FileSniffer）调用。
 *
 * 判定语义：
 * - [XLogProbe.isXLog] = 窗口内解析出 ≥1 条完整段且段头 magic 命中（误报率极低：
 *   要求 magic + 9B 头 + 合法长度 + 尾标 0x00 的完整段结构自洽，随机字节几乎不可能拼出）；
 * - [XLogProbe.needsPrivateKey] = 命中任一 ECDH_TEA 加密段（无钥不可解密，调用方提示用户配置）。
 *
 * 阻塞 IO：调用方自行包 Dispatchers.IO。
 */
object LogProbe {

    /** 探测结果快照 */
    data class XLogProbe(
        val isXLog: Boolean,
        val segmentsFound: Int,
        val needsPrivateKey: Boolean,
        /** 命中段的压缩方式名集合（NONE/ZLIB/ZSTD），供 LLM 判型摘要展示 */
        val compressions: Set<String>,
    )

    /**
     * 探测文件头部。
     *
     * @param maxBytes 最多读取的头部窗口（默认 8MB 对齐 XLogChunkReader.DEFAULT_CHUNK_SIZE_BYTES）
     * @param maxSegments 解析出该段数即提前返回（探测只需定性，不必扫全文件）
     */
    fun probe(
        file: File,
        maxBytes: Long = 8L * 1024 * 1024,
        maxSegments: Int = 16,
    ): XLogProbe {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxSegments > 0) { "maxSegments must be positive" }
        if (!file.isFile) return XLogProbe(isXLog = false, segmentsFound = 0, needsPrivateKey = false, compressions = emptySet())

        val window = minOf(file.length(), maxBytes)
        var segments = 0
        var needsPrivateKey = false
        val compressions = mutableSetOf<String>()

        FileInputStream(file).use { input ->
            try {
                XLogChunkReader(
                    input = LimitedInputStream(input, window),
                    fileLengthBytes = window,
                ).forEachSlice { slice ->
                    segments++
                    if (slice.magicStartMark.crypt == CryptType.ECDH_TEA) needsPrivateKey = true
                    compressions += slice.magicStartMark.compression.name
                    if (segments >= maxSegments) throw ProbeBudgetReached()
                }
            } catch (_: ProbeBudgetReached) {
                // 达到探测预算即提前收工（forEachSlice 无正常提前退出通道）
            }
        }

        return XLogProbe(
            isXLog = segments > 0,
            segmentsFound = segments,
            needsPrivateKey = needsPrivateKey,
            compressions = compressions,
        )
    }

    /** 探测预算达成的内部控制流信号（非错误；禁堆栈填充） */
    private class ProbeBudgetReached : RuntimeException(null, null, false, false)

    /** 限长包装流：到 [limit] 后报 EOF（探测只看头部窗口） */
    private class LimitedInputStream(
        private val delegate: java.io.InputStream,
        private val limit: Long,
    ) : java.io.InputStream() {
        private var served = 0L

        override fun read(): Int {
            if (served >= limit) return -1
            val b = delegate.read()
            if (b >= 0) served++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit) return -1
            val n = delegate.read(b, off, minOf(len, (limit - served).toInt()))
            if (n > 0) served += n
            return n
        }
    }
}
