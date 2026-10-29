package com.jiaoay.easy_dev_toolbox.core.media

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 音频解码转码器：
 * ffmpeg 子进程把任意音频格式解码为 ASR 标准输入（16kHz mono PCM wav）。
 *
 * 🔴 **argv 程序调用语义，非 shell 命令行**：ffmpeg 是确定性文件变换工具，
 *    ProcessBuilder 直调（argv 数组，不经 ShellCommandBuilder.wrap 走 cmd.exe / zsh -l——
 *    那是交互 shell 语义且 processCharset 在 Windows 是 ANSI，UTF-8 路径参数会被编成
 *    乱码、ffmpeg 起手即失败）。先例对齐
 *    lark-bridge `exec/ProgramLauncher` 的 argv 语义（但 :lark-bridge 是 jvmTarget 11
 *    纯 JVM lib 不可 import——在 :core 新写）。
 *
 * wav 不经 ffmpeg 直读（[readWavPcm]——零外部依赖基座，spec「wav 直读不依赖 ffmpeg」）。
 */
object AudioTranscoder {

    private const val TAG = "AudioTranscoder"

    /** ASR 模型输入标准：SenseVoice 16kHz mono */
    const val TARGET_SAMPLE_RATE = 16000

    /** ffmpeg 单次解码硬上限（秒）——解码远快于实时，5 分钟覆盖数小时音频；超时即强杀报失败 */
    private const val FFMPEG_TIMEOUT_SECONDS = 300L

    /** 等待环轮询间隔（ms）——等待全程可响应协程取消与超时 */
    private const val PROCESS_POLL_INTERVAL_MS = 100L

    /** ffmpeg 探测结果进程内缓存（null = 未探测；探测一次全 App 复用） */
    private val ffmpegAvailable = AtomicReference<Boolean?>(null)

    /** ffmpeg 是否可用（`ffmpeg -version` 探测，进程内只探一次） */
    fun isFfmpegAvailable(): Boolean = ffmpegAvailable.get() ?: probeFfmpeg().also {
        ffmpegAvailable.set(it)
    }

    private fun probeFfmpeg(): Boolean = runCatching {
        val process = ProcessBuilder("ffmpeg", "-version").start()
        process.inputStream.bufferedReader().readLine() ?: ""
        process.waitFor() == 0
    }.getOrDefault(false).also {
        if (!it) JLog.w(TAG) { "ffmpeg 不可用——mp3/m4a/flac/ogg 转写降级（wav 直读不受影响）" }
    }

    /**
     * 解码为 16kHz mono PCM wav（ffmpeg 子进程）。
     * 输出落系统临时目录（转写是一次性消费，产物不入应用数据目录）。
     *
     * 契约：限时且可取消——[FFMPEG_TIMEOUT_SECONDS] 超时或协程取消都会
     * destroyForcibly 子进程并删除临时产物；取消原样上抛（不当解码失败）。
     */
    suspend fun decodeToPcm16kMono(input: File): Result<File> {
        if (!isFfmpegAvailable()) {
            return Result.failure(IllegalStateException("系统未安装 ffmpeg——该格式需 ffmpeg 解码（wav 文件不受影响）"))
        }
        val output = File.createTempFile("voice-${input.nameWithoutExtension}-", ".16k.wav")
        return try {
            val process = ProcessBuilder(decodeArgv(input, output)).redirectErrorStream(true).start()
            // stderr 合流捕获（ffmpeg 进度/错误都在 stderr）——进程退出后读全量
            val (code, captured) = awaitProcess(process, FFMPEG_TIMEOUT_SECONDS)
            check(code == 0 && output.isFile && output.length() > 44L) {
                "ffmpeg 解码失败（exit=$code）：${captured.take(300)}"
            }
            JLog.d(TAG) { "decode ok: ${input.name} -> ${output.name} (${output.length()}B)" }
            Result.success(output)
        } catch (t: Throwable) {
            // 失败与取消统一收尾临时产物——NonCancellable 保证已取消协程内也删完；
            // 取消不当普通失败处理（runCatching 会吞 CancellationException 的陷阱）
            withContext(NonCancellable) { output.delete() }
            if (t is CancellationException) throw t
            JLog.w(TAG) { "decode failed: ${input.name} — ${t.message}" }
            Result.failure(t)
        }
    }

    /**
     * 等待子进程退出并捕获合流输出（编排层收口——超时/取消统一强杀）。
     *
     * 契约：
     * - [timeoutSeconds] 内未退出 → destroyForcibly 后抛 IllegalStateException；
     * - 协程取消 → 等待环立即抛 CancellationException，finally destroyForcibly 后原样上抛；
     * - 正常退出 → 返回 (exitCode, 合流输出全文)。
     *
     * 硬限制：进程输出量须小于 OS 管道缓冲——超缓冲进程会在退出前写阻塞死锁
     * （本调用方 argv 带 -loglevel error 恒满足）。
     */
    internal suspend fun awaitProcess(process: Process, timeoutSeconds: Long): Pair<Int, String> {
        // 等待环必须在 try 内：协程取消的 CancellationException 直接从 withTimeoutOrNull
        // 逸出（withTimeoutOrNull 只把超时收敛为 null，不拦外部取消）——环在 try 外则
        // finally 永不执行，子进程漏杀。
        try {
            val exited = withTimeoutOrNull(timeoutSeconds * 1000) {
                while (process.isAlive) delay(PROCESS_POLL_INTERVAL_MS)
                true
            } ?: false
            if (!exited) throw IllegalStateException("子进程超时未退出（>${timeoutSeconds}s）")
            return process.exitValue() to process.inputStream.bufferedReader().readText()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** argv 构造（纯函数——单测缝）：`ffmpeg -y -i <in> -ar 16000 -ac 1 -c:a pcm_s16le <out>` */
    internal fun decodeArgv(input: File, output: File): List<String> = listOf(
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", input.absolutePath,
        "-ar", TARGET_SAMPLE_RATE.toString(),
        "-ac", "1",
        "-c:a", "pcm_s16le",
        output.absolutePath,
    )

    // ── wav 直读（零外部依赖基座） ──

    /**
     * 读 wav PCM 样本（RIFF 头解析——16bit PCM 与 32bit float 两种常见形态；
     * 重采样交 sherpa-onnx `acceptWaveform`（其内部按 sampleRate 重采样），
     * 本层只解析不转码）。
     *
     * @return (samples, sampleRate)——float 归一化样本
     */
    fun readWavPcm(path: File): Result<Pair<FloatArray, Int>> = runCatching {
        require(path.isFile) { "文件不存在: ${path.absolutePath}" }
        path.inputStream().use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            // RIFF 头：'RIFF' + size + 'WAVE'
            require(header.size >= 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
            ) { "不是有效的 WAV 文件（RIFF/WAVE 头缺失）" }

            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var audioFormat = 0
            var dataBytes = -1
            var dataOffset = -1

            // 块遍历：找 fmt 与 data 块（RIFF 块序不保证 fmt 在前——逐块扫）
            while (true) {
                val chunkHeader = ByteArray(8)
                val n = input.readFully(chunkHeader)
                if (n < 8) break
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = leInt(chunkHeader, 4)
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize.coerceAtMost(64))
                        input.readFully(fmt)
                        audioFormat = leShort(fmt, 0)
                        channels = leShort(fmt, 2)
                        sampleRate = leInt(fmt, 4)
                        bitsPerSample = leShort(fmt, 14)
                    }
                    "data" -> {
                        dataBytes = chunkSize
                        dataOffset = 0 // 相对当前位置
                        break
                    }
                    else -> input.skip(chunkSize + (chunkSize % 2).toLong()) // 对齐填充
                }
            }
            require(sampleRate > 0 && channels > 0 && dataBytes > 0) { "WAV 头缺少 fmt/data 块" }

            val samples: FloatArray = when (audioFormat to bitsPerSample) {
                1 to 16 -> read16BitPcm(input, dataBytes, channels)
                3 to 32 -> read32BitFloat(input, dataBytes, channels)
                else -> throw IllegalArgumentException(
                    "不支持的 WAV 编码（format=$audioFormat bits=$bitsPerSample）——建议经 ffmpeg 转码",
                )
            }
            // 立体声 → 声道平均（mono 化；重采样交 sherpa）
            samples to sampleRate
        }
    }.onFailure { JLog.w(TAG) { "wav 解析失败: ${path.name} — ${it.message}" } }

    /** 16bit PCM → float 归一化（多声道混合平均） */
    private fun read16BitPcm(input: java.io.InputStream, dataBytes: Int, channels: Int): FloatArray {
        val bytes = ByteArray(dataBytes)
        input.readFully(bytes)
        val frames = bytes.size / (2 * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt()
                sum += ((hi shl 8) or lo) / 32768f
            }
            out[f] = sum / channels
        }
        return out
    }

    /** 32bit IEEE float → float（多声道混合平均） */
    private fun read32BitFloat(input: java.io.InputStream, dataBytes: Int, channels: Int): FloatArray {
        val bytes = ByteArray(dataBytes)
        input.readFully(bytes)
        val frames = bytes.size / (4 * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val i = (f * channels + c) * 4
                val v = Float.fromBits(
                    (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt()) shl 24),
                )
                sum += v
            }
            out[f] = sum / channels
        }
        return out
    }

    // ── 小工具 ──

    private fun java.io.InputStream.readFully(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) return off
            off += n
        }
        return off
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt()) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
