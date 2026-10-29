package com.jiaoay.xlog.parser

/**
 * 一次段解析的结果: 段头信息 + 载荷在缓冲区窗口中的切片位置。
 * [window]/[logOffset]/[logSize] 是零拷贝切片, 仅在缓冲区窗口被复用前有效。
 */
internal class LogSpaceSlice(
    val magicStartMark: MagicStartMark,
    val seq: Int,
    val beginHour: Int,
    val endHour: Int,
    /** 段头 crypt key, ≤64 字节, 已拷贝(缓存 key 需要内容稳定) */
    val cryptKeyBytes: ByteArray,
    /** 载荷所在缓冲区(零拷贝切片的宿主) */
    val window: ByteArray,
    val logOffset: Int,
    val logSize: Int,
    /** 整段字节尺寸 = 头部 9 + cryptKeyByteSize + logSize + 尾部 magicEnd 1 */
    val totalSize: Int,
) {

    fun logBytes(): ByteArray = window.copyOfRange(logOffset, logOffset + logSize)

    override fun toString(): String {
        return "LogSpaceSlice(magicStartMark=$magicStartMark, seq=$seq, " +
                "beginHour=$beginHour, endHour=$endHour, " +
                "cryptKeySize=${cryptKeyBytes.size}, logSize=$logSize)"
    }
}
