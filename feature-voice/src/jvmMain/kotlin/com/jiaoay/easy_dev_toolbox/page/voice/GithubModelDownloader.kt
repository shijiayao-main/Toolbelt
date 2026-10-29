package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * 生产模型下载器（P4-S voice-transcribe——[ModelStore] 的 GitHub release 实现）。
 *
 * tar.bz2 **流式**解压只取两目标文件（model.int8.onnx / tokens.txt——README/test_wavs
 * 不落盘）；JDK HttpClient 流式拉取带进度回调。
 *
 * commons-compress 依赖经 :core 传递可见（feature-archive 同族先例）。
 */
class GithubModelDownloader : ModelDownloader {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override suspend fun download(
        url: String,
        targetModel: File,
        targetTokens: File,
        progress: (Long, Long) -> Unit,
    ) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofHours(2)) // 227MB 大模型——宽限
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "下载源响应 ${response.statusCode()}" }
        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)

        // 进度按已读**原始字节**计（对齐 Content-Length 口径）：counted 包在 BZip2 之下
        // ——tar.read 返回的是解压后字节，与压缩流长度不同口径混算会让进度超 100%、
        // ETA/速度失真（BZip2 解压比可达 ~2x）。跳过条目（skipFully）读的原始字节
        // 同样经 counted，进度单调覆盖全流。
        val counted = ProgressCountingInputStream(response.body(), total, progress)
        TarArchiveInputStream(BZip2CompressorInputStream(counted)).use { tar ->
            val buf = ByteArray(64 * 1024)
            var entryRef: TarArchiveEntry?
            while (tar.nextTarEntry.also { entryRef = it } != null) {
                val entry = entryRef!!
                val name = entry.name.substringAfterLast('/')
                val target = when (name) {
                    ModelStore.MODEL_FILE -> targetModel
                    ModelStore.TOKENS_FILE -> targetTokens
                    else -> null // README/test_wavs 等跳过
                }
                if (target == null) {
                    tar.skipFully(entry.size)
                    continue
                }
                target.outputStream().use { out ->
                    var n = tar.read(buf)
                    while (n >= 0) {
                        out.write(buf, 0, n)
                        n = tar.read(buf)
                    }
                }
            }
        }
    }

    /** 官方 release 未发布 sha 摘要文件——null = 跳过校验（spec：SHA 校验在源提供时执行） */
    override suspend fun expectedModelSha256(): String? = null

    /** 单文件直下（VAD——非 tar.bz2 打包，纯流式落盘） */
    override suspend fun downloadSingle(url: String, target: File, progress: (Long, Long) -> Unit) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(10)) // 629KB 小模型——宽限即可
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "下载源响应 ${response.statusCode()}" }
        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        var read = 0L
        val buf = ByteArray(64 * 1024)
        target.outputStream().use { out ->
            response.body().use { input ->
                var n = input.read(buf)
                while (n >= 0) {
                    out.write(buf, 0, n)
                    read += n
                    progress(read, total)
                    n = input.read(buf)
                }
            }
        }
    }
}

/** tar 条目跳过（commons-compress 无条目级 skip API——手读丢弃） */
private fun TarArchiveInputStream.skipFully(size: Long) {
    var remaining = size
    val buf = ByteArray(64 * 1024)
    while (remaining > 0) {
        val n = read(buf, 0, remaining.coerceAtMost(buf.size.toLong()).toInt())
        if (n < 0) break
        remaining -= n
    }
}

/**
 * 原始字节计数流（包在解压层之下）：每读到一块原始字节即上报
 * `(累计已读原始字节, [total])`。close/skip 直通委托——BZip2 只走块读，
 * skip 由解压层消化不经本层。
 */
private class ProgressCountingInputStream(
    delegate: java.io.InputStream,
    private val total: Long,
    private val progress: (Long, Long) -> Unit,
) : java.io.FilterInputStream(delegate) {

    private var read = 0L

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        val n = super.read(buf, off, len)
        if (n > 0) {
            read += n
            progress(read, total)
        }
        return n
    }

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            read += 1
            progress(read, total)
        }
        return b
    }
}
