package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.utils.SafeExtract
import com.jiaoay.xlog.LogProbe
import java.io.File
import java.nio.charset.CodingErrorAction

/**
 * 本地文件嗅探（纯本地、零 LLM；原万能解密嗅探器，工作台管线判型复用）：读文件头部窗口
 * 汇总一份供 LLM 判型的摘要。
 *
 * 组成：文件名/大小/后缀 + 首字节 hex 预览 + UTF-8 严格文本探测 + [LogProbe] 段结构
 * 探测 + [SafeExtract.isArchive] 压缩包后缀提示。全部 O(头部窗口) IO。
 *
 * 设计动机：类型路由的最终裁判是 LLM（executeStructured），
 * 本类只提供证据材料——「不靠代码规则写死类型判断」的原则下，本地探测是输入而非结论。
 */
data class SniffSummary(
    val fileName: String,
    val sizeBytes: Long,
    val extension: String,
    val firstBytesHex: String,
    val looksUtf8Text: Boolean,
    val xlogProbe: LogProbe.XLogProbe?,
    val archiveHint: Boolean,
) {
    /** 渲染给 LLM 的证据摘要（判型 prompt 的 user 消息体） */
    fun toEvidenceText(): String = buildString {
        appendLine("文件名: $fileName")
        appendLine("大小: $sizeBytes 字节")
        appendLine("后缀: ${if (extension.isBlank()) "(无)" else extension}")
        appendLine("首字节(hex): $firstBytesHex")
        appendLine("UTF-8 文本探测: ${if (looksUtf8Text) "是（内容为合法 UTF-8 文本）" else "否"}")
        xlogProbe?.let { probe ->
            appendLine(
                "XLog 段结构探测: ${if (probe.isXLog) "命中（${probe.segmentsFound} 段，" +
                    "压缩=${probe.compressions.joinToString("/")}，" +
                    "需要私钥=${if (probe.needsPrivateKey) "是" else "否"}）" else "未命中"}",
            )
        }
        if (archiveHint) appendLine("压缩包后缀提示: 是（zip/tar/gz 族）")
    }
}

/** 嗅探执行器（object + 纯函数；阻塞 IO，调用方包 Dispatchers.IO） */
object FileSniffer {

    /** 头部窗口：64KB 足够段结构探测与文本定性，避免大文件全读 */
    private const val SNIFF_WINDOW_BYTES = 64 * 1024
    private const val HEX_PREVIEW_BYTES = 32

    suspend fun sniff(file: File): SniffSummary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val head = ByteArray(SNIFF_WINDOW_BYTES).also { buf ->
            file.inputStream().use { input ->
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
            }
        }.let { if (it.size > file.length()) it.copyOf(file.length().toInt()) else it }

        SniffSummary(
            fileName = file.name,
            sizeBytes = file.length(),
            extension = file.extension.lowercase(),
            firstBytesHex = head.take(HEX_PREVIEW_BYTES).joinToString(" ") {
                String.format("%02X", it)
            },
            looksUtf8Text = head.isNotEmpty() && isStrictUtf8(head),
            xlogProbe = LogProbe.probe(file),
            archiveHint = SafeExtract.isArchive(file.name),
        )
    }

    /** UTF-8 严格解码探测：全部头部窗口可无差错解码即判文本（防误把二进制当日志） */
    private fun isStrictUtf8(bytes: ByteArray): Boolean = runCatching {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes))
        true
    }.getOrDefault(false)
}
