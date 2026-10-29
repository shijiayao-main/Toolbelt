package com.jiaoay.easy_dev_toolbox.page.voice

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * SenseVoice int8 模型管理。
 *
 * ## 模型分发契约（spec「模型分发与就绪检测」）
 * - **就绪判定**：model.int8.onnx 与 tokens.txt 双文件在位即就绪——**日常启动不做 SHA 校验**
 *   （下载完成时校验一次；手动放置 = 用户自担文件完好）；
 * - **下载**：tar.bz2 流式解压只取两目标文件（README/test_wavs 不落盘）；
 *   `.part` 临时文件 + 完成原子改名；进度经 [state] 进页面；
 * - **手动放置**：目录内双文件在即跳过——公司内网镜像下载后放入即用（国内网络兜底）。
 *
 * 下载器经 [ModelDownloader] 接口缝注入（生产 GitHub 实现 / 测试 fake——单测零网络）。
 */
object ModelStore {

    /** 模型目录：`~/.toolbelt/models/sense-voice-int8/` */
    val modelDir: File = File(FileUtils.getModelsDir(), "sense-voice-int8")

    /** 就绪所需双文件名（契约面——手动放置按此命名） */
    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"

    /** 下载源（sherpa-onnx 官方 asr-models release——常量化供测试替换） */
    internal const val DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"

    // ── 状态面（StateFlow 进页面渲染） ──

    sealed class State {
        /** 未检测（页面进入时先探一次） */
        data object Unknown : State()

        /** 就绪（双文件在位——含手动放置） */
        data object Ready : State()

        /** 下载中（bytesPerSecond 可选展示） */
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State()

        /** 下载失败（可重试——retry 恢复 Downloading） */
        data class Failed(val reason: String) : State()

        /** 校验失败（下载完成但 SHA 不符——文件已清理，可重试） */
        data class Corrupt(val detail: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 下载进度发射时间窗（毫秒）：块级回调（64KB/块——高速下载每秒百余次）
     * 在窗内合并，全量逐块发射新 [State.Downloading] 实例会驱动整页重组风暴。
     */
    internal const val PROGRESS_WINDOW_MILLIS = 100L

    /**
     * 进度节流时间源（毫秒）：默认真实单调钟——测试注入虚拟时钟驱动窗语义，
     * 免真实 sleep 脆弱等待；覆写后须在测试 finally 恢复默认。
     */
    internal var progressNowMillis: () -> Long = { System.nanoTime() / 1_000_000 }

    /** ASR 下载进度节流器（发射面 = [state]） */
    private fun newThrottle(): ProgressThrottle = ProgressThrottle(
        windowMillis = PROGRESS_WINDOW_MILLIS,
        nowMillis = progressNowMillis,
    ) { read, total -> _state.value = State.Downloading(read, total) }

    /** 就绪判定（双文件在位——纯文件存在性检查，零 SHA） */
    fun isReady(): Boolean = File(modelDir, MODEL_FILE).isFile && File(modelDir, TOKENS_FILE).isFile

    /**
     * 已保留分片字节数（取消后引导文案「已下载 N MB · 继续下载」的数据源）：
     * .part 文件存在即有保留分片（取消保全语义——CancellationException 不删）。
     * 注意本版续传为**从头重下**（.part 只证明取消前落过盘，不提供断点续传）。
     */
    fun retainedPartialBytes(): Long =
        (File(modelDir, "$MODEL_FILE.part").takeIf { it.isFile }?.length() ?: 0L) +
            (File(modelDir, "$TOKENS_FILE.part").takeIf { it.isFile }?.length() ?: 0L)

    // ── VAD 清单项（add-voice-live-recording-vad——录音段切分引擎） ──

    /** VAD 模型目录：`~/.toolbelt/models/silero-vad/` */
    val vadModelDir: File = File(FileUtils.getModelsDir(), "silero-vad")

    /** VAD 就绪所需单文件名 */
    const val VAD_MODEL_FILE = "silero_vad.onnx"

    /** VAD 下载源（sherpa 官方 asr-models release——单文件直下） */
    internal const val VAD_DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    /**
     * VAD 模型期望 SHA-256（629KB；源不发布摘要文件时以首次下载产物固化，后续校验按此）
     */
    internal const val VAD_SHA256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

    /** VAD 就绪判定（单文件在位——录音门 = isReady() && isVadReady()） */
    fun isVadReady(): Boolean = File(vadModelDir, VAD_MODEL_FILE).isFile

    /** 就绪检测入口（页面进入时调用——刷新状态面） */
    fun refresh() {
        _state.value = if (isReady()) State.Ready else State.Unknown
    }

    /**
     * 下载模型（tar.bz2 流式解压取双文件）。
     * 逐块读入时更新 [State.Downloading]；SHA 校验失败删文件回 [State.Corrupt]。
     */
    suspend fun download(downloader: ModelDownloader) = withContext(Dispatchers.IO) {
        // 🔴 IO 下放（调用方 scope = Main.immediate）：网络流 / SHA 全文件哈希 / tar 解压
        //    全阻塞——不下放则 227MB 下载全程冻结 UI
        if (isReady()) {
            _state.value = State.Ready
            return@withContext
        }
        modelDir.mkdirs()
        val modelOut = File(modelDir, MODEL_FILE)
        val tokensOut = File(modelDir, TOKENS_FILE)
        val modelPart = File(modelDir, "$MODEL_FILE.part")
        val tokensPart = File(modelDir, "$TOKENS_FILE.part")
        _state.value = State.Downloading(0, -1)
        try {
            val throttle = newThrottle()
            downloader.download(
                url = DOWNLOAD_URL,
                targetModel = modelPart,
                targetTokens = tokensPart,
            ) { read, total -> throttle.onProgress(read, total) }
            // 完成收口：末块不足一窗时强制把进度冲到最终值（不得停在 99%——
            // SHA 校验期页面仍处 Downloading 面，进度可见 100%）
            throttle.flush()

            // SHA 校验一次（下载完成时）——不符即清理回 Corrupt
            val modelSha = downloader.expectedModelSha256()
            if (modelSha != null && !sha256Matches(modelPart, modelSha)) {
                modelPart.delete(); tokensPart.delete()
                _state.value = State.Corrupt("model.int8.onnx SHA 校验失败（下载截断或源损坏）")
                return@withContext
            }

            // 原子改名（.part → 正名——就绪判定只见正名文件）
            if (!modelPart.renameTo(modelOut) || !tokensPart.renameTo(tokensOut)) {
                throw IllegalStateException("模型文件改名失败（目录权限？）")
            }
            _state.value = State.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 中止保全：.part 保留（下次续传由下载器决定是否利用；本版从头重下）
            _state.value = State.Unknown
            throw e
        } catch (e: Exception) {
            modelPart.delete(); tokensPart.delete()
            _state.value = State.Failed(e.message?.take(300) ?: e::class.simpleName ?: "下载失败")
        }
    }

    private fun sha256Matches(file: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected.lowercase()
    }

    // ── VAD 下载（add-voice-live-recording-vad——单文件直下，配方同 ASR） ──

    private val _vadState = MutableStateFlow<State>(State.Unknown)
    val vadState: StateFlow<State> = _vadState.asStateFlow()

    /** VAD 就绪检测（页面进入录音区时调用） */
    fun refreshVad() {
        _vadState.value = if (isVadReady()) State.Ready else State.Unknown
    }

    /** 下载 VAD 模型（.part + SHA + 原子改名——同 ASR 配方） */
    suspend fun downloadVad(downloader: ModelDownloader) = withContext(Dispatchers.IO) {
        // IO 下放同 [download]（SHA 全文件哈希秒级阻塞——Main 上即冻结）
        if (isVadReady()) {
            _vadState.value = State.Ready
            return@withContext
        }
        vadModelDir.mkdirs()
        val out = File(vadModelDir, VAD_MODEL_FILE)
        val part = File(vadModelDir, "$VAD_MODEL_FILE.part")
        _vadState.value = State.Downloading(0, -1)
        try {
            val throttle = ProgressThrottle(
                windowMillis = PROGRESS_WINDOW_MILLIS,
                nowMillis = progressNowMillis,
            ) { read, total -> _vadState.value = State.Downloading(read, total) }
            downloader.downloadSingle(VAD_DOWNLOAD_URL, part) { read, total ->
                throttle.onProgress(read, total)
            }
            // 完成收口同 [download]——末块强制冲到最终值（不得停在 99%）
            throttle.flush()
            if (!sha256Matches(part, VAD_SHA256)) {
                part.delete()
                _vadState.value = State.Corrupt("silero_vad.onnx SHA 校验失败（下载截断或源损坏）")
                return@withContext
            }
            if (!part.renameTo(out)) throw IllegalStateException("VAD 模型文件改名失败（目录权限？）")
            _vadState.value = State.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            _vadState.value = State.Unknown
            throw e
        } catch (e: Exception) {
            part.delete()
            _vadState.value = State.Failed(e.message?.take(300) ?: e::class.simpleName ?: "下载失败")
        }
    }
}

/**
 * 模型下载器接口缝（单测零网络）：
 * 生产实现流式拉 tar.bz2 并解出两目标文件；fake 直接把字节数组写进目标。
 */
interface ModelDownloader {
    /** 下载并解出双文件到 .part（progress 回调：(已读字节, 总字节或 -1)） */
    suspend fun download(
        url: String,
        targetModel: File,
        targetTokens: File,
        progress: (read: Long, total: Long) -> Unit,
    )

    /** 模型文件期望 SHA-256（null = 跳过校验——官方 release 未发布 sha 时） */
    suspend fun expectedModelSha256(): String?

    /**
     * 单文件直下（VAD 等非打包模型——LiveTranscribe change 5）：
     * 生产实现直接流式落盘；本默认实现走「双文件面 + 忽略第二目标」不可行
     * （tar.bz2 解包语义），故默认抛 Unsupported——测试 fake 按需覆写。
     */
    suspend fun downloadSingle(url: String, target: File, progress: (read: Long, total: Long) -> Unit) {
        throw UnsupportedOperationException("本下载器不支持单文件直下")
    }
}

/**
 * 下载进度时间窗节流器：高频块级进度在窗内只记**合并值**（最新值覆盖），
 * 窗到期后的首个回调发射合并值；[flush] 在下载完成时强制发射滞留的合并值——
 * 末块不足一个时间窗时进度也必须冲到最终值，不得停在 99%。
 *
 * 双条件缺一不可：只做时间窗不收口 → 末块进度滞留窗内被吞（视觉停在 99%）；
 * 只做收口不合并 → 窗语义失效回到逐块全量发射。
 *
 * 线程契约：单下载任务单实例，回调与 [flush] 由同一下载协程顺序调用——
 * 非线程安全。发射的首个回调必经（0% 起点可见）。
 *
 * @param windowMillis 发射时间窗（窗内回调只更新合并值不发射）
 * @param nowMillis 时间源缝（毫秒——测试注入虚拟时钟，禁真实 sleep 脆弱窗测）
 * @param emit 窗到期/收口时的发射面（宿主 StateFlow 写入）
 */
internal class ProgressThrottle(
    private val windowMillis: Long,
    private val nowMillis: () -> Long,
    private val emit: (read: Long, total: Long) -> Unit,
) {
    private var lastEmitTime = NEVER
    private var pendingRead = 0L
    private var pendingTotal = -1L
    private var hasPending = false

    /** 进度回调：首个回调必发射；窗内只记合并值，窗到期后的首个回调发射 */
    fun onProgress(read: Long, total: Long) {
        pendingRead = read
        pendingTotal = total
        hasPending = true
        val now = nowMillis()
        if (lastEmitTime == NEVER || now - lastEmitTime >= windowMillis) {
            lastEmitTime = now
            hasPending = false
            emit(read, total)
        }
    }

    /** 完成收口：强制发射窗内滞留的合并值；无滞留时 no-op（不重复发射末值） */
    fun flush() {
        if (!hasPending) return
        hasPending = false
        emit(pendingRead, pendingTotal)
    }

    private companion object {
        /** 未发射哨兵（时间源恒非负——负值判定「从未发射」不与真实时刻撞面） */
        const val NEVER = -1L
    }
}
