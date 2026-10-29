package com.jiaoay.easy_dev_toolbox.page.voice

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.WaveReader

/**
 * sherpa-onnx 生产实现。
 *
 * 🔴 **`com.k2fsa.sherpa.onnx.*` 全仓唯一出现点**（隔离守护测试 3.3 钉死）——
 *    页面/VM 经 [Transcriber]/[VadEngine] 接口消费，JNI 面零外泄。
 *    实时录音域（add-voice-live-recording-vad）的 [SherpaVadEngine] 也在本文件——
 *    同属 JNI 面，收口唯一出现点纪律。
 *
 * - **recognizer 单例复用**：模型加载 ~723ms 只付一次（spike 实测）；
 * - **native 库加载**：`sherpa_onnx.native.path` 指向 `~/.toolbelt/native-cache/`
 *   （LibraryLoader 自动解包 so 到持久缓存目录）；
 * - **超长截断**：>60min 音频截断转写（非流式一次吃整段；不 VAD 分段）。
 */
object SherpaTranscriber : Transcriber {

    private const val TAG = "SherpaTranscriber"

    /** 推理线程数（spike 实测 numThreads=2：5.6s 音频 210ms，RTF≈0.04） */
    private const val NUM_THREADS = 2

    /** 超长音频截断上限（分钟——SenseVoice 非流式一次吃整段，超限截断防内存/超时） */
    const val MAX_DURATION_MINUTES = 60

    /** recognizer 单例（双检锁经 AtomicReference 幂等——files 域守护零 @Volatile 先例同款） */
    private val recognizerRef = AtomicReference<OfflineRecognizer?>(null)

    /** native 解包缓存目录（LibraryLoader 经系统属性读）。真源 = [FileUtils.getNativeCacheDir] */
    private val nativeCacheDir: File = FileUtils.getNativeCacheDir()

    init {
        // 🔴 必须在首次加载 JNI 前设置（LibraryLoader 只在首次加载时读一次
        // sherpa_onnx.native.path——迟设不生效，native 解包落不进持久缓存目录）
        System.setProperty("sherpa_onnx.native.path", nativeCacheDir.absolutePath)
        nativeCacheDir.mkdirs()
    }

    override fun transcribe(audioFile: File): Result<Transcription> = runCatching {
        if (!ModelStore.isReady()) throw TranscriberNotReadyException()
        val recognizer = recognizerRef.get() ?: synchronized(this) {
            recognizerRef.get() ?: buildRecognizer().also { recognizerRef.set(it) }
        }
        val reader = WaveReader(audioFile.absolutePath)
        // 超长截断（spike：非流式一次吃整段——超限只取前 MAX_DURATION_MINUTES）
        val maxSamples = reader.getSampleRate() * 60L * MAX_DURATION_MINUTES
        val allSamples = reader.getSamples()
        val truncated = allSamples.size > maxSamples
        val samples = if (truncated) {
            JLog.w(TAG) { "音频超长，截断转写前 $MAX_DURATION_MINUTES 分钟（${audioFile.name}）" }
            allSamples.copyOf(maxSamples.toInt())
        } else {
            allSamples
        }
        // 截断标注随产物上抛（原始时长向上取整——62s 仍须示为 2 分钟，不四舍五入到 1）
        val truncationNote = if (truncated) {
            val originalMinutes = (allSamples.size / reader.getSampleRate() + 59) / 60
            "已截断至 $MAX_DURATION_MINUTES 分钟（原始 $originalMinutes 分钟）"
        } else {
            null
        }
        val stream: OfflineStream = recognizer.createStream()
        val text = try {
            stream.acceptWaveform(samples, reader.getSampleRate())
            recognizer.decode(stream)
            recognizer.getResult(stream).getText()
        } finally {
            stream.release()
        }
        Transcription(text, truncationNote)
    }.onFailure { JLog.w(TAG) { "transcribe failed: ${audioFile.name} — ${it.message}" } }

    /** recognizer 装配（模型文件就绪前置由 transcribe 守门） */
    private fun buildRecognizer(): OfflineRecognizer {
        val modelDir = ModelStore.modelDir
        val senseVoice = OfflineSenseVoiceModelConfig.builder()
            .setModel(File(modelDir, ModelStore.MODEL_FILE).absolutePath)
            .build()
        val modelConfig = OfflineModelConfig.builder()
            .setSenseVoice(senseVoice)
            .setTokens(File(modelDir, ModelStore.TOKENS_FILE).absolutePath)
            .setNumThreads(NUM_THREADS)
            .setDebug(false)
            .build()
        val config = OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(modelConfig)
            .setDecodingMethod("greedy_search")
            .build()
        val t0 = System.currentTimeMillis()
        val recognizer = OfflineRecognizer(config)
        JLog.d(TAG) { "模型加载 ${System.currentTimeMillis() - t0}ms" }
        return recognizer
    }

    /**
     * 段样本直转（add-voice-live-recording-vad——实时录音分段喂入面）。
     *
     * 与 [transcribe] 共享 recognizer 单例（模型加载只付一次）；VAD 段是内存样本
     * 非文件——createStream + acceptWaveform 直喂，零文件中转。
     * 🔴 JNI 类型零外泄（隔离守护纪律）：返回纯 String Result。
     */
    fun transcribeSamples(samples: FloatArray, sampleRate: Int): Result<String> = runCatching {
        if (!ModelStore.isReady()) throw TranscriberNotReadyException()
        val recognizer = recognizerRef.get() ?: synchronized(this) {
            recognizerRef.get() ?: buildRecognizer().also { recognizerRef.set(it) }
        }
        val stream: OfflineStream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).getText()
        } finally {
            stream.release()
        }
    }.onFailure { JLog.w(TAG) { "transcribeSegment failed — ${it.message}" } }

    /** 测试缝：重置单例（模型目录换新后强制重建 recognizer） */
    fun resetForTest() {
        recognizerRef.get()?.release()
        recognizerRef.set(null)
    }
}

/**
 * sherpa Vad 生产实现（add-voice-live-recording-vad 任务 2.1——JNI 收口本文件）。
 *
 * 🔴 JNI 前置：`sherpa_onnx.native.path` 已由本文件 object init 设置
 * （同进程 property 只设一次）。
 */
class SherpaVadEngine(modelPath: String) : VadEngine {

    private val vad = com.k2fsa.sherpa.onnx.Vad(
        com.k2fsa.sherpa.onnx.VadModelConfig.builder()
            .setSileroVadModelConfig(
                com.k2fsa.sherpa.onnx.SileroVadModelConfig.builder()
                    .setModel(modelPath)
                    .setThreshold(0.5f)
                    .setMinSilenceDuration(0.5f) // 停顿 0.5s 切段（口述节奏）
                    .setMinSpeechDuration(0.25f)
                    .setWindowSize(WINDOW_SIZE)
                    .setMaxSpeechDuration(MAX_SPEECH_DURATION_SECONDS)
                    .build(),
            )
            .setSampleRate(SAMPLE_RATE)
            .setNumThreads(1)
            .setDebug(false)
            .build(),
    )

    override fun acceptWaveform(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun isEmpty(): Boolean = vad.empty()

    override fun nextSegment(): VadSegment? =
        if (vad.empty()) null else vad.front().let { seg ->
            VadSegment(seg.start, seg.samples).also { vad.pop() }
        }

    override fun flush() = vad.flush()

    override fun reset() = vad.reset()

    override fun release() = vad.release()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512 // Silero 窗口（32ms @16k）
        const val MAX_SPEECH_DURATION_SECONDS = 20f // 长段强制切分上限（伪流式粒度）

        /** 便捷构造（模型就绪判由调用方守门；未就绪返回 null） */
        fun fromModelStore(): SherpaVadEngine? {
            if (!ModelStore.isVadReady()) return null
            return SherpaVadEngine(File(ModelStore.vadModelDir, ModelStore.VAD_MODEL_FILE).absolutePath)
        }
    }
}
