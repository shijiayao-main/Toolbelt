package com.jiaoay.xlog.stream

import com.jiaoay.xlog.postprocess.DecodeSummary
import com.jiaoay.xlog.parser.LogSpaceParser
import com.jiaoay.xlog.parser.LogSpaceSlice
import com.jiaoay.xlog.parser.MagicStartMark
import java.io.InputStream

/**
 * 滑动窗口流式读取器: 以 [chunkSizeBytes] 为单位把文件读入内存窗口,
 * 逐段交给 [LogSpaceParser] 解析, 避免 available() 式的全量读入。
 *
 * 协议:
 * - 窗口末尾可能是半条段 → 剩余字节压实到窗口头部后继续填充;
 * - 单段超过窗口容量 → 窗口按需扩容(封顶 [maxLogPayloadBytes] + 头部余量);
 * - EOF 处不完整的尾段静默丢弃(与旧版整文件语义一致), 字节数记入
 *   [DecodeSummary.droppedTailBytes];
 * - [LogSpaceSlice.window] 仅在回调返回前有效(下次压实/扩容后内容被覆盖)。
 *
 * @param input 会由 [forEachSlice] 关闭
 */
internal class XLogChunkReader(
    private val input: InputStream,
    private val fileLengthBytes: Long,
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
    private val maxLogPayloadBytes: Long = MAX_LOG_PAYLOAD_BYTES,
) {

    init {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be positive" }
        require(maxLogPayloadBytes >= chunkSizeBytes) { "maxLogPayloadBytes must be >= chunkSizeBytes" }
    }

    /** 驱动解析循环, 对每条完整段调用一次 action, 直到 EOF */
    fun forEachSlice(action: (LogSpaceSlice) -> Unit): DecodeSummary {
        val summary = DecodeSummary()
        var window = ByteArray(chunkSizeBytes)
        var filled = 0
        var consumed = 0
        var eof = false
        var remainingFileBytes = fileLengthBytes

        fun windowCapacityLimit(): Int {
            // 头部 9B + key 64B + 载荷上限 + 尾标 1B
            val limit = 9 + 64 + maxLogPayloadBytes + 1
            return if (limit > Int.MAX_VALUE) Int.MAX_VALUE else limit.toInt()
        }

        fun fill(): Boolean {
            if (eof) return false
            // 窗口已满且仍可能需要更多数据: 扩容
            if (filled == window.size) {
                val doubled = window.size * 2
                val limit = windowCapacityLimit()
                window = window.copyOf(if (doubled <= limit) doubled else limit)
            }
            val bytesRead = input.read(window, filled, window.size - filled)
            if (bytesRead < 0) {
                eof = true
                return false
            }
            filled += bytesRead
            remainingFileBytes -= bytesRead
            return true
        }

        fun compact() {
            if (consumed == 0) return
            window.copyInto(window, 0, consumed, filled)
            filled -= consumed
            consumed = 0
        }

        try {
            while (true) {
                if (!eof && filled == consumed && !fill()) {
                    // EOF 且窗口无数据: 结束
                    break
                }
                val outcome = LogSpaceParser.parse(
                    window = window,
                    from = consumed,
                    to = filled,
                    remainingFileBytes = remainingFileBytes
                )
                when (outcome) {
                    is LogSpaceParser.Outcome.Segment -> {
                        val slice = outcome.slice
                        action(slice)
                        // 🔴 绝对偏移推进：parse 会跳过 consumed 起的垃圾字节，
                        //    段头可能远在 from 之后——按 from+totalSize 相对推进会把垃圾重复消费、
                        //    同一段被反复返回（垃圾前缀实测死循环）。startIndex 由 parse 一并返回。
                        consumed = outcome.startIndex + slice.totalSize
                    }
                    is LogSpaceParser.Outcome.NoCompleteSegment -> {
                        consumed = outcome.resumeIndex
                        if (eof) {
                            // EOF 处不完整的尾段: 静默丢弃(与旧版一致)。
                            // 仅当残留以段头 magic 起始时才视为"半条段", 纯垃圾不算。
                            if (filled > consumed && MagicStartMark.of(window[consumed]) != null) {
                                summary.droppedTailBytes += filled - consumed
                            }
                            break
                        }
                        compact()
                        val progressed = fill()
                        if (!progressed && filled == consumed) {
                            break
                        }
                    }
                }
            }
        } finally {
            input.close()
        }
        return summary
    }

    companion object {
        private const val DEFAULT_CHUNK_SIZE_BYTES = 8 * 1024 * 1024
        private const val MAX_LOG_PAYLOAD_BYTES = 64L * 1024 * 1024
    }
}
