package com.jiaoay.xlog

import com.jiaoay.log.LogTimestamp
import java.io.File

/**
 * 日志文件内容判型门面：后缀不可信（存在已解密文件仍挂 .alog/.xlog 后缀、
 * 明文 mmap 形态被当加密文件送解密等误判场景），统一以内容嗅探为准、后缀仅作兜底提示。
 *
 * 判型优先级：
 * 1. xlog/alog 段结构探测（[LogProbe]，magic+段头自洽，误报率极低）；
 * 2. 明文 mmap 形态（首行 `begin of mmap` 标记或行首可读时间戳）；
 * 3. 兜底 UNKNOWN（既非段结构也非明文时间戳——可能是密钥错误产物或非日志文件）。
 *
 * 阻塞 IO：调用方自行包 Dispatchers.IO。
 */
object LogFormatDetector {

    /** 内容判型结果 */
    enum class Format {
        /** xlog/alog 加密/压缩段结构——走 [LogDecode] 解密 */
        XLOG_SEGMENTS,

        /** 明文 mmap 或已解码文本——无需解密，直接可过滤 */
        PLAINTEXT,

        /** 未知形态（空文件/随机二进制/损坏数据） */
        UNKNOWN,
    }

    /**
     * 头部窗口嗅探文件真实格式。
     * @param windowBytes 最多读取的头部窗口（默认 4MB——明文判定只需首行，段结构判定对齐 LogProbe 预算）
     */
    fun detect(file: File, windowBytes: Long = 4L * 1024 * 1024): Format {
        if (!file.isFile || file.length() == 0L) return Format.UNKNOWN

        // 1) xlog 段结构探测（权威判型：段头 magic + 长度 + 尾标自洽）
        val probe = LogProbe.probe(file, maxBytes = windowBytes)
        if (probe.isXLog) return Format.XLOG_SEGMENTS

        // 2) 明文判定：首行 begin-of-mmap 标记，或头部窗口内出现行首可读时间戳
        if (looksLikePlaintextLog(file, windowBytes)) return Format.PLAINTEXT

        return Format.UNKNOWN
    }

    /**
     * 解密入口的统一前置路由：返回「应该怎么处理这个文件」。
     * - XLOG_SEGMENTS → 正常走解密；
     * - PLAINTEXT → 已是明文，跳过解密（调用方直接把源文件当产物用，或按自身产物语义复制）；
     * - UNKNOWN → 按后缀意图尝试解密（保持既有行为：后缀 .alog/.xlog = 用户意图是解密，
     *   失败由解密器如实报错）。
     */
    fun routeFor(file: File): Format =
        detect(file)

    private fun looksLikePlaintextLog(file: File, windowBytes: Long): Boolean {
        val window = minOf(file.length(), windowBytes)
        val head = ByteArray(window.toInt()).let { buf ->
            file.inputStream().use { input ->
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        }
        val text = String(head, Charsets.UTF_8)
        val firstLine = text.lineSequence().firstOrNull()?.trimStart() ?: return false
        if (firstLine.contains("begin of mmap")) return true
        // 头部窗口任一行命中行首时间戳契约即明文（单源复用 :log 侧 LogTimestamp 判定）
        return text.lineSequence().take(200).any { line ->
            LogTimestamp.isTimeLine(line)
        }
    }
}
