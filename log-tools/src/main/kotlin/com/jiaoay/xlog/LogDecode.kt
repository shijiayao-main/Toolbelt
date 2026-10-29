package com.jiaoay.xlog

import com.jiaoay.xlog.codec.LogCodecRegistry
import com.jiaoay.xlog.codec.TeaKeyResolver
import com.jiaoay.xlog.parser.CryptType
import com.jiaoay.xlog.parser.LogSpaceSlice
import com.jiaoay.xlog.postprocess.PostProcessorPipeline
import com.jiaoay.xlog.postprocess.SegmentContext
import com.jiaoay.xlog.stream.XLogChunkReader
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * xlog 解码门面: 流式读取([XLogChunkReader]) → 段解析([com.jiaoay.xlog.parser.LogSpaceParser])
 * → 解码组合([LogCodecRegistry]) → 后处理管道([PostProcessorPipeline])。
 *
 * 实例无共享状态, 可并发调用; 每次调用内部持有独立的密钥缓存与后处理管道。
 *
 * 输出契约: [decodeFile] 原子替换写——temp 全量解码成功后 ATOMIC_MOVE 覆盖
 * [outFile]; 失败/取消路径删除 temp 且**保留旧产物**（半途密钥错误不 truncate 在盘产物）。
 *
 * @Author: leavesCZY
 */
class LogDecode() {

    fun decodeFile(privateKey: String, logFile: File, outFile: File) {
        // 密钥预校验（打开输出前）：头部窗口探测是否含加密段（非加密文件不误伤），
        // 语义与 TeaKeyResolver.resolve 的惰性抛出一致——只把失败时点从「首个加密段
        // 回调内」提前到「打开输出前」
        if (privateKey.isBlank() && LogProbe.probe(logFile).needsPrivateKey) {
            throw IllegalArgumentException("the log is encrypted and the private key needs to be entered")
        }
        val teaKeyResolver = TeaKeyResolver(privateKeyHex = privateKey)
        val pipeline = PostProcessorPipeline.default()
        // 🔴 temp 须删净（失败/取消路径）：.tmp 残留会被按前缀扫产物的消费面误当产物
        val tmpFile = File(outFile.parentFile ?: File("."), "${outFile.name}.tmp")
        try {
            // 全链显式 UTF-8（本库 jvmTarget 11 可跑在 JEP 400 之前的 JDK：默认字符集随
            // 平台漂移，Windows GBK 下中文日志全量乱码且不可逆）
            tmpFile.bufferedWriter(Charsets.UTF_8).use { outWriter ->
                var segmentIndex = 0
                val summary = XLogChunkReader(
                    input = FileInputStream(logFile),
                    fileLengthBytes = logFile.length(),
                ).forEachSlice { slice ->
                    val decodedLog = decodeSlice(slice = slice, teaKeyResolver = teaKeyResolver)
                    val context = SegmentContext(
                        segmentIndex = segmentIndex++,
                        magicStartMark = slice.magicStartMark,
                        seq = slice.seq,
                        beginHour = slice.beginHour,
                        endHour = slice.endHour,
                    )
                    pipeline.onSegmentStart(context)
                    pipeline.process(
                        content = String(decodedLog, Charsets.UTF_8),
                        context = context,
                        sink = { line, terminated ->
                            outWriter.append(line)
                            if (terminated) outWriter.append('\n')
                        }
                    )
                    pipeline.onSegmentEnd(context)
                }
                pipeline.onFinish()
            }
            moveIntoPlace(tmpFile, outFile)
        } catch (e: Throwable) {
            tmpFile.delete()
            throw e
        }
    }

    /** temp → 正式产物原子替换（ATOMIC_MOVE 不被文件系统支持时退化为 REPLACE_EXISTING） */
    private fun moveIntoPlace(tmpFile: File, outFile: File) {
        try {
            Files.move(
                tmpFile.toPath(),
                outFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmpFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun decodeSlice(slice: LogSpaceSlice, teaKeyResolver: TeaKeyResolver): ByteArray {
        val codec = LogCodecRegistry.codecFor(slice.magicStartMark)
        val teaKey = if (slice.magicStartMark.crypt != CryptType.NONE) {
            teaKeyResolver.resolve(slice.cryptKeyBytes)
        } else {
            null
        }
        return codec.decode(slice.logBytes(), teaKey)
    }
}
