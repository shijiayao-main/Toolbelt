package com.jiaoay.easy_dev_toolbox.page.voice

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * VAD 段切分器（add-voice-live-recording-vad 任务 2.2——窗口喂入 → 段事件流）。
 *
 * 纯状态机（零 JNI 依赖——引擎经 [VadEngine] 缝注入，fake 可脚本化段序列）：
 * - [feed]：采集线程每窗（512 样本）调用一次——窗口喂引擎 + 音量 RMS 上抛；
 * - 段检出：feed 内 drain（engine 空了为止——一次 feed 可能出多段）；
 * - [finish]：停止收尾——flush 强制闭合尾段（不丢字）；
 * - [reset]：会话间复位（段计数归零）。
 *
 * @param onSegment 段回调（采集线程内联触发——转写消费方自行切线程，
 *        见 [LiveTranscriber] 的 Channel 中转）
 */
class VadSegmenter(
    private val engine: VadEngine,
    private val onSegment: (samples: FloatArray, startSample: Int) -> Unit,
) {

    /** 音量面（RMS 归一 [0,1]——UI 音量条数据源） */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** 喂入一窗口（16k mono PCM float） */
    fun feed(window: FloatArray) {
        _level.value = rms(window)
        engine.acceptWaveform(window)
        drain()
    }

    /** 停止收尾：flush 闭合尾段（未完结语音段强制出段——尾字不丢） */
    fun finish() {
        _level.value = 0f
        engine.flush()
        drain()
    }

    /** 会话复位 */
    fun reset() {
        _level.value = 0f
        engine.reset()
    }

    /** 取走全部已检出段（feed/finish 共用——一次窗口可能触发多段） */
    private fun drain() {
        while (!engine.isEmpty()) {
            val seg: VadSegment = engine.nextSegment() ?: break
            onSegment(seg.samples, seg.startSample)
        }
    }

    private fun rms(window: FloatArray): Float {
        if (window.isEmpty()) return 0f
        var sum = 0.0
        for (s in window) sum += (s * s).toDouble()
        val rms = kotlin.math.sqrt(sum / window.size).toFloat()
        return (rms * LEVEL_GAIN).coerceIn(0f, 1f) // 归一增益（人声典型 RMS≈0.05-0.3）
    }

    companion object {
        /** 音量条增益（人声 RMS 远低于 1.0——×3 抬升可读性） */
        const val LEVEL_GAIN = 3f

        private const val TAG = "VadSegmenter"
    }
}

/**
 * 一段转写产物（伪流式单元——段序号 + 文本）。
 *
 * 契约：不携带段样本——样本仅是转写输入，入列即死重；段音频回放未立项，
 * 立项时再随需求重估字段面。
 */
data class LiveSegment(
    val index: Int,
    val startSample: Int,
    val text: String,
) {
    /** 段起始秒（16k 采样率换算） */
    val startSeconds: Float get() = startSample / 16_000f
}

/**
 * 实时转写管线（add-voice-live-recording-vad 任务 2.4——段 → 文本流）。
 *
 * 段经 SharedFlow 进来（VadSegmenter 采集线程回调 → Channel 中转到消费协程），
 * 逐段喂 Transcriber（🔴 串行纪律——recognizer 非线程安全，单流逐段），
 * 结果 append 进 [segments]（UI 分段结果区数据源）。
 *
 * @param transcriber 段转写器（生产 SherpaTranscriber 单例复用；测试 fake——
 *        注意 Transcriber.transcribe(File) 是文件签名，段样本经临时 wav 中转，
 *        见 [SegmentTranscriber]；本类不感知文件细节）
 */
class LiveTranscriber(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val transcriber: SegmentTranscriber,
) {

    /** 段样本转写缝（与文件面 Transcriber 分离——段是内存样本非文件） */
    fun interface SegmentTranscriber {
        fun transcribe(samples: FloatArray, sampleRate: Int): Result<String>
    }

    private val _segments = MutableStateFlow<List<LiveSegment>>(emptyList())
    val segments: StateFlow<List<LiveSegment>> = _segments.asStateFlow()

    // 段累积底账：双写入口——collect 协程（append，宿主 scope 派发器上执行）与
    // reset()（UI 线程调用）。安全性由 Main 单线程派发串行化成立（生产装配
    // scope = viewModelScope 即 Main.immediate，reset 与 collect 续体同线排队），
    // 非线程安全结构（plain MutableList）；注入多线程 scope 则本账本契约失效。
    // append 只入账一次，发布时拷贝快照（消费方持旧快照不受后续入账影响）
    private val segmentBuffer = mutableListOf<LiveSegment>()

    private val pending = MutableSharedFlow<Pair<FloatArray, Int>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // 采集面背压：丢旧窗不阻塞录音
    )

    /** 段入队（VadSegmenter onSegment 回调线程调用——非挂起） */
    val segmentSink: (samples: FloatArray, startSample: Int) -> Unit = { samples, start ->
        pending.tryEmit(samples to start)
    }

    private var collector: kotlinx.coroutines.Job? = null

    private val seq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 启动消费（幂等——重复启动 no-op） */
    fun start() {
        if (collector?.isActive == true) return
        collector = scope.launchCollect()
    }

    /** 停止消费（段结果面保留——UI 回看；下次 start 前须 [reset]） */
    fun stop() {
        collector?.cancel()
        collector = null
    }

    /** 会话复位（段列表清空 + 序号归零） */
    fun reset() {
        segmentBuffer.clear()
        _segments.value = emptyList()
        seq.set(0)
    }

    // 扩展函数不可全限定调用：receiver CoroutineScope 在位时裸 launch 即解析
    private fun kotlinx.coroutines.CoroutineScope.launchCollect(): kotlinx.coroutines.Job =
        launch {
            pending.collect { (samples, start) ->
                val index = seq.incrementAndGet()
                // 🔴 推理下放 Default（collect 语境 = Main.immediate）：逐段 JNI 纯 CPU
                //    （每秒级），Main 上执行 = 每段冻结全 UI 一次
                val result = withContext(Dispatchers.Default) {
                    transcriber.transcribe(samples, SherpaVadEngine.SAMPLE_RATE)
                }
                val text = result.getOrElse { e ->
                    JLog.w("LiveTranscriber") { "段 $index 转写失败: ${e.message}" }
                    ""
                }
                if (text.isNotBlank()) {
                    segmentBuffer.add(LiveSegment(index, start, text))
                    _segments.value = segmentBuffer.toList()
                }
            }
        }

    /** 全文拼接（整理纪要入口——段序连接） */
    fun fullText(): String = _segments.value.joinToString("\n") { it.text }
}
