package com.jiaoay.xlog.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * xlog 段格式:
 * magic start(1B) | seq(2B LE) | begin hour(1B) | end hour(1B) |
 * length(4B LE) | crypt key(N B) | log(length B) | magic end(1B, 0x00)
 *
 * 无状态纯函数解析器: 给定缓冲区窗口与起始偏移, 找到下一条完整段或报告扫描进度。
 * 喂字节、压实、扩容是调用方(流式 reader)的职责。
 */
internal object LogSpaceParser {

    /** 头部固定部分: magic 1 + seq 2 + beginHour 1 + endHour 1 + length 4 */
    private const val HEADER_SIZE = 9

    private const val MAGIC_END: Byte = 0x00

    /** 单段载荷上限(xlog log_max_len 为 uint32, 这里收紧到 64MB) */
    private const val MAX_LOG_PAYLOAD_BYTES = 64L * 1024 * 1024

    sealed interface Outcome {
        /**
         * 解析出一条完整段。
         * @param startIndex 段头 magic 在窗口内的**绝对**偏移——调用方（流式 reader）推进
         *        消费游标必须以此为准（`consumed = startIndex + totalSize`），不得按 `from + totalSize`
         *        相对推进：parse 会跳过 from 之前的垃圾字节，段起点可能远大于 from，
         *        相对推进会把垃圾再次消费并重复返回同一段（LogProbe 探测垃圾前缀场景实测发现）。
         */
        data class Segment(val slice: LogSpaceSlice, val startIndex: Int) : Outcome

        /**
         * 未能从窗口内解析出完整段。
         * @param resumeIndex 下次续扫位置(垃圾已跳过; 处于候选段头部的从该候选起重试)
         */
        data class NoCompleteSegment(val resumeIndex: Int) : Outcome
    }

    /**
     * 在 window[from..<to] 内从 from 起逐字节扫描, 跳过垃圾字节, 返回首个完整段。
     *
     * @param remainingFileBytes 窗口之外(to 之后)还能从文件读到的字节数; 候选段所需字节
     *        超过"窗口内剩余 + 它"时判为无效垃圾继续向后扫(与"整文件已在内存"的旧语义
     *        等价, 避免把损坏长度误认为"数据未到齐"而挂到 EOF)
     */
    fun parse(
        window: ByteArray,
        from: Int,
        to: Int,
        remainingFileBytes: Long
    ): Outcome {
        val available = to - from
        if (available < HEADER_SIZE) {
            return Outcome.NoCompleteSegment(resumeIndex = from)
        }
        var index = from
        val lastIndex = to - HEADER_SIZE
        while (index <= lastIndex) {
            val magicStartMark = MagicStartMark.of(window[index])
            if (magicStartMark == null) {
                index++
                continue
            }

            val cryptKeyByteSize = magicStartMark.cryptKeyByteSize
            val lengthOffset = index + HEADER_SIZE - 4
            val logByteSize = readIntLE(window, lengthOffset)
            val payloadSizeInvalid = logByteSize !in 0..MAX_LOG_PAYLOAD_BYTES
            // 长度非法时 totalSize 无意义, 先按 0 占位, 该候选会走"无效垃圾"分支
            val totalSize = if (payloadSizeInvalid) 0 else HEADER_SIZE + cryptKeyByteSize + logByteSize + 1
            val endMarkIndex = index + totalSize - 1
            when {
                // 窗口内完整且合法: 返回段
                !payloadSizeInvalid && endMarkIndex < to && window[endMarkIndex] == MAGIC_END -> {
                    return segment(
                        magicStartMark = magicStartMark,
                        window = window,
                        index = index,
                        cryptKeyByteSize = cryptKeyByteSize,
                        logByteSize = logByteSize,
                        totalSize = totalSize
                    )
                }
                // 非法长度, 或所需字节超过文件剩余: 判为无效垃圾, 从下一字节继续
                payloadSizeInvalid ||
                        totalSize.toLong() > availableInFile(index, to, remainingFileBytes) -> {
                    index++
                }
                // 数据未到齐: 从当前候选处挂起等更多数据
                else -> return Outcome.NoCompleteSegment(resumeIndex = index)
            }
        }
        return Outcome.NoCompleteSegment(resumeIndex = index.coerceAtMost(to))
    }

    /** 文件中从窗口内 index 起剩余的总字节数(窗口内 index..<to + 窗口外未读) */
    private fun availableInFile(index: Int, to: Int, remainingFileBytes: Long): Long {
        return (to - index).toLong() + remainingFileBytes
    }

    private fun segment(
        magicStartMark: MagicStartMark,
        window: ByteArray,
        index: Int,
        cryptKeyByteSize: Int,
        logByteSize: Int,
        totalSize: Int
    ): Outcome.Segment {
        val seqStart = index + 1
        val seq = (window[seqStart].toInt() and 0xFF) or
                ((window[seqStart + 1].toInt() and 0xFF) shl 8)
        val cryptKeyBytes = window.copyOfRange(
            index + HEADER_SIZE,
            index + HEADER_SIZE + cryptKeyByteSize
        )
        return Outcome.Segment(
            slice = LogSpaceSlice(
                magicStartMark = magicStartMark,
                seq = seq,
                beginHour = window[index + 3].toInt() and 0xFF,
                endHour = window[index + 4].toInt() and 0xFF,
                cryptKeyBytes = cryptKeyBytes,
                window = window,
                logOffset = index + HEADER_SIZE + cryptKeyByteSize,
                logSize = logByteSize,
                totalSize = totalSize,
            ),
            startIndex = index,
        )
    }

    private fun readIntLE(buffer: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }
}
