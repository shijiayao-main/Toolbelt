package com.jiaoay.easy_dev_toolbox.page.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal
import com.jiaoay.easy_dev_toolbox.core.navigation.SpaceAttribution
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import com.jiaoay.easy_dev_toolbox.core.media.AudioTranscoder
import com.jiaoay.easy_dev_toolbox.widget.TaskUi
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import com.jiaoay.easy_dev_toolbox.widget.task.FileTaskViewModel
import com.jiaoay.easy_dev_toolbox.widget.task.ProcessResult
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音转写 VM（P4-S openspec voice-transcribe 任务 2.2）。
 *
 * ## 继承形态
 * [FileTaskViewModel] 四胞胎基类（任务卡状态机/openFolder/clearTasks 收敛面）——
 * 本类只实现 [process]（wav 直读 / 非 wav 经 ffmpeg 解码分支）+ 转写产物落盘
 * （AI 整理链经产物文件挂接）。
 *
 * ## 纪要面（审阅卡态）
 * - [summarizeState]：VoiceSummarizeSession 透传（Running/Produced/Failed）；
 * - [acceptedNote]：采纳后写回的纪要文本（一次一份——per-文件纪要首版不做，
 *   整理入口 = 指定任务卡）；
 * - aiEnabled 镜像：[aiEnabled]（feature 互不依赖——AiEnabledSignal 订阅消费，
 *   WorkbenchViewModel 同款先例）。
 *
 * 🔴 VM 构造零 JNI 触达：[Transcriber] 经接口注入（默认 [SherpaTranscriber] 惰性取用——
 *    构造只持引用，首次转写才触 JNI 模型加载；构造期触 JNI 会把模型缺失/加载失败
 *    前移成 VM 构造失败，整页不可用）。
 */
class VoiceViewModel(
    /** 转写器缝（默认 sherpa 生产实现——测试注入 fake） */
    private val transcriber: Transcriber = SherpaTranscriber,
    /** 任务 IO 线程域（产物落盘/归属链/目录直开——继承 [FileTaskViewModel] 同一缝的 VM 级自有段） */
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FileTaskViewModel(ioDispatcher) {

    private val TAG = "VoiceViewModel"

    /** AI 整理会话（纪要产出 → 审阅卡） */
    private val summarizeSession = VoiceSummarizeSession(viewModelScope)

    /** AI 开关镜像（[AiEnabledSignal] 直接订阅——init 一次性快照违契约：VM 窗口级
     *  存活下快照永不刷新，开关翻转后页面仍按陈旧值降级） */
    val aiEnabled: StateFlow<Boolean> = AiEnabledSignal.enabled

    /** 模型就绪态（页面下载引导消费） */
    val modelState: StateFlow<ModelStore.State> = ModelStore.state

    /** 整理审阅态（session 透传——Produced 时审阅卡呈现） */
    val summarizeState: StateFlow<VoiceSummarizeSession.State> = summarizeSession.state

    /** 已采纳纪要（写回态——null = 无纪要） */
    private val _acceptedNote = MutableStateFlow<String?>(null)
    val acceptedNote: StateFlow<String?> = _acceptedNote.asStateFlow()

    /** 整理中的任务卡文件名（入口锚——审阅卡归属展示） */
    private val _summarizingFor = MutableStateFlow<String?>(null)
    val summarizingFor: StateFlow<String?> = _summarizingFor.asStateFlow()

    // ── 实时录音面（add-voice-live-recording-vad 任务 3.1——VAD 伪流式） ──

    /** 录音运行态 */
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** 录音错误面（麦克风占用/缺失——null = 无错） */
    private val _recordError = MutableStateFlow<String?>(null)
    val recordError: StateFlow<String?> = _recordError.asStateFlow()

    /** 录制时长（秒——start 起算） */
    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    /** 分段转写结果（LiveTranscriber 透传） */
    // 会话流透传面（transcriber/segmenter 为 null 时用缓存空实例——每次新建会让
    // collectAsState 的 flow key 失稳导致重复重启收集，quest 域 FlowViewModel 同款配方）
    private val emptySegments = MutableStateFlow<List<LiveSegment>>(emptyList())
    val liveSegments: StateFlow<List<LiveSegment>> get() = _liveTranscriber?.segments ?: emptySegments

    /** 音量面（VadSegmenter.level 透传——无 segmenter 时恒 0） */
    private val emptyLevel = MutableStateFlow(0f)
    val liveLevel: StateFlow<Float> get() = _segmenter?.level ?: emptyLevel

    private var _segmenter: VadSegmenter? = null
    private var _liveTranscriber: LiveTranscriber? = null
    private var _micCapture: MicCapture? = null
    private var _vadEngine: VadEngine? = null
    private var elapsedJob: kotlinx.coroutines.Job? = null

    /** VAD 模型态（页面下载引导消费） */
    val vadState: StateFlow<ModelStore.State> = ModelStore.vadState

    init {
        ModelStore.refresh()
        ModelStore.refreshVad()
    }

    // ── 转写（FileTaskViewModel.process 实现） ──

    /**
     * 单文件转写：wav 直读 → 转写；非 wav 经 ffmpeg 解码 16k mono → 转写。
     * 产物落 `~/.toolbelt/voice/transcripts/<原名>.md`（AI 整理链消费）。
     */
    override suspend fun process(file: File): ProcessResult {
        // ffmpeg 可用性前置（非 wav 必需——spec「缺 ffmpeg 明示降级」）
        if (!file.extension.equals("wav", ignoreCase = true) && !AudioTranscoder.isFfmpegAvailable()) {
            return ProcessResult.Failure("转写 ${file.extension} 需系统安装 ffmpeg——或先转存为 wav 再拖入")
        }

        val pcmWav = if (file.extension.equals("wav", ignoreCase = true)) {
            file
        } else {
            AudioTranscoder.decodeToPcm16kMono(file).getOrElse {
                return ProcessResult.Failure("音频解码失败：${it.message?.take(200)}")
            }
        }

        val result = transcriber.transcribe(pcmWav)
        return result.fold(
            onSuccess = { outcome ->
                // 使用计数口径：转写完成 = 一次真实使用
                ToolUsageSignal.report(ToolUsageSignal.PageId.VOICE_TRANSCRIBE)
                val transcriptFile = writeTranscript(file, outcome.text, outcome.truncationNote)
                // 转写产物归属当前调查空间——本分支已在
                // IO 语境（FileTaskViewModel.runTask 的 withContext(ioDispatcher)），
                // 直调即可。无选中空间静默跳过；失败槽内
                // JLog 收口不拖垮转写主流程
                SpaceAttribution.attributeFiles(listOf(transcriptFile.absolutePath))
                // 截断标注上抛任务卡（Success.note——>60min 静默截断零容忍）
                ProcessResult.Success(transcriptFile.absolutePath, outcome.truncationNote)
            },
            onFailure = { ProcessResult.Failure(it.message?.take(300) ?: "转写失败") },
        )
    }

    /** 转写产物文件（`voice/transcripts/<原名>.md`——提交去重与取消收口共用同一定义） */
    private fun transcriptFileOf(source: File): File =
        File(FileUtils.getVoiceTranscriptsDir(), "${source.nameWithoutExtension}.md")

    override fun outputTargetsOf(file: File): Set<String> = setOf(transcriptFileOf(file).absolutePath)

    /** 覆盖不预删：转写稿在成功收尾时整体写出——预删丢「失败保旧产物」语义 */
    override fun deleteStaleOutputsOf(file: File) = Unit

    /**
     * 取消收口：产物在盘即如实回填（「已取消 · 部分产物已保留」）。不删——
     * 无法区分本次运行刚写出的产物与更早一次转写同名音频留下的历史产物，删除有破坏风险。
     */
    override fun onTaskCancelled(file: File): String? {
        val out = transcriptFileOf(file)
        return if (out.isFile) out.absolutePath else null
    }

    /** 转写产物落盘（AI 整理围栏挂接点——返回文件供 startSummarize；截断标注进 md 头部） */
    private suspend fun writeTranscript(source: File, text: String, truncationNote: String?): File = withContext(ioDispatcher) {
        val dir = FileUtils.getVoiceTranscriptsDir()
        dir.mkdirs()
        val out = File(dir, "${source.nameWithoutExtension}.md")
        val body = buildString {
            if (truncationNote != null) {
                appendLine("> $truncationNote")
                appendLine()
            }
            append(text)
        }
        out.writeText(body)
        JLog.d(TAG) { "transcript saved: ${out.name} (${text.length} 字)" }
        out
    }

    // ── 模型下载（页面引导按钮消费；取消面——Job 持引用可停） ──

    private var modelDownloadJob: kotlinx.coroutines.Job? = null
    private var vadDownloadJob: kotlinx.coroutines.Job? = null

    /**
     * 下载速度差分流（VM 层 500ms 窗口；ModelStore.State 零改动；分片取消/Unknown
     * 回退时 finally 清零，速度流不悬挂旧值）。
     */
    private val _downloadSpeed = MutableStateFlow(0L)

    /** 下载速度（字节/秒口径——≤0 时 UI 隐速度/ETA 行，0 MB/s 无信息量） */
    val downloadSpeed: StateFlow<Long> = _downloadSpeed.asStateFlow()

    fun downloadModel(downloader: ModelDownloader = GithubModelDownloader()) {
        modelDownloadJob = viewModelScope.launch {
            // 速度差分：500ms 窗口读 ModelStore.state 进度做差——UI 重组
            // 不重置窗口（VM 层稳于 UI 层两帧差分）
            val meterJob = launch {
                var lastBytes = 0L
                var lastAt = System.currentTimeMillis()
                while (true) {
                    kotlinx.coroutines.delay(500)
                    val cur = (ModelStore.state.value as? ModelStore.State.Downloading)
                        ?.downloadedBytes ?: break
                    val now = System.currentTimeMillis()
                    val dt = (now - lastAt).coerceAtLeast(1)
                    _downloadSpeed.value = ((cur - lastBytes) * 1000 / dt).coerceAtLeast(0)
                    lastBytes = cur
                    lastAt = now
                }
            }
            try {
                ModelStore.download(downloader)
            } finally {
                meterJob.cancel()
                _downloadSpeed.value = 0L
            }
        }
    }

    /**
     * 取消模型下载：CancellationException 语义——ModelStore 内部保全 .part 分片
     * （下次续传从头重下但已落盘分片保留，不删）；状态回 Unknown（引导区可再次发起）。
     */
    fun cancelDownloadModel() {
        modelDownloadJob?.cancel()
        modelDownloadJob = null
    }

    /** VAD 模型下载（录音区引导——add-voice-live-recording-vad） */
    fun downloadVadModel(downloader: ModelDownloader = GithubModelDownloader()) {
        vadDownloadJob = viewModelScope.launch {
            ModelStore.downloadVad(downloader)
        }
    }

    fun cancelDownloadVadModel() {
        vadDownloadJob?.cancel()
        vadDownloadJob = null
    }

    /**
     * 打开模型目录（手动放置引导——Failed/Corrupt 卡消费）。
     *
     * 不走 [com.jiaoay.easy_dev_toolbox.core.data.DesktopUtils.openInFolder]——
     * 它开的是 path 的 parentFile（文件语义），本处要开 modelDir 目录本体（直开差
     * 一级到 models/），故 VM 内私有直开（Desktop.getDesktop().open 同款先例）。
     */
    fun openModelDir() {
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                java.awt.Desktop.getDesktop().open(ModelStore.modelDir)
            }.onFailure { e ->
                JLog.e(TAG, e) { "openModelDir failed: ${ModelStore.modelDir.absolutePath}" }
            }
        }
    }

    // ── AI 整理（成功卡按钮消费） ──

    /** 发起整理（taskId = 成功任务卡——产物路径在 Success.outputPath） */
    fun startSummarize(task: TaskUi) {
        val outputPath = (task.status as? TaskUiStatus.Success)?.outputPath ?: return
        if (!aiEnabled.value) return
        _summarizingFor.value = task.fileName
        summarizeSession.startSummarize(File(outputPath))
    }

    // ── 实时录音动作面（add-voice-live-recording-vad） ──

    /** 开始录音（ASR+VAD 双就绪门；装配链 mic→segmenter→transcriber） */
    fun startRecording() {
        if (_recording.value) return
        if (!ModelStore.isReady() || !ModelStore.isVadReady()) return
        _recordError.value = null

        // 装配链：转写器（recognizer 单例复用）→ 段切分器 → 麦克风
        val transcriber = LiveTranscriber(
            scope = viewModelScope,
            transcriber = LiveTranscriber.SegmentTranscriber(SherpaTranscriber::transcribeSamples),
        )
        transcriber.start()
        val engine = SherpaVadEngine.fromModelStore() ?: run {
            _recordError.value = "VAD 引擎装配失败（模型文件异常）"
            transcriber.stop()
            return
        }
        _vadEngine = engine
        val segmenter = VadSegmenter(engine, onSegment = transcriber.segmentSink)
        _liveTranscriber = transcriber
        _segmenter = segmenter
        val mic = MicCapture(
            scope = viewModelScope,
            onWindow = segmenter::feed,
            onError = { err ->
                _recordError.value = err
                stopRecording()
            },
        )
        _micCapture = mic
        transcriber.reset()
        segmenter.reset()
        mic.start()
        if (mic.isRunning) {
            _recording.value = true
            _elapsedSeconds.value = 0
            elapsedJob = viewModelScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    _elapsedSeconds.value += 1
                }
            }
        }
    }

    /**
     * 停止录音（flush 尾段——不丢字；资源全收）。两条进入路径共用同一清理链：
     *
     * - 正常停止（`_recording == true`）：停计时 → 收资源 → 复位录音态；
     * - mic.start() 失败回调（`_recording == false`，[MicCapture] 在设 captureJob
     *   前同步 onError 进来）：装配链已建（transcriber/segmenter/vadEngine），仅
     *   `_recording` 未置位——必须同样全量收口，否则 VAD native 句柄/段切分器/
     *   转写协程随每次失败尝试累积泄漏（下次 startRecording 直接覆盖引用，泄漏
     *   即不可达）。
     */
    fun stopRecording() {
        val wasRecording = _recording.value
        if (wasRecording) {
            elapsedJob?.cancel()
            elapsedJob = null
        }
        _micCapture?.stop() // 先停采集（finally 收线）
        _micCapture = null
        _segmenter?.finish() // flush 尾段——尾字不丢
        _segmenter = null
        _vadEngine?.release()
        _vadEngine = null
        _liveTranscriber?.stop()
        if (wasRecording) _recording.value = false
    }

    /** 录音全文整理（段文本落 md → 既有 SummarizeSession 链） */
    fun summarizeRecording() {
        val live = _liveTranscriber ?: return
        val text = live.fullText()
        if (text.isBlank() || !aiEnabled.value) return
        // 落点与文件转写稿同域（voice/transcripts/——产物归域一致性；存量
        // <root>/transcripts/ 旧文件惰性不迁，见 docs/pages/voice.md 过渡说明）
        val outFile = File(
            com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getVoiceTranscriptsDir(),
            "live-${System.currentTimeMillis()}.md",
        )
        outFile.parentFile.mkdirs()
        viewModelScope.launch(ioDispatcher) {
            outFile.writeText(text)
            // 实时录音整理原文归属当前调查空间——本协程
            // 已在 IO 语境（launch(ioDispatcher)），直调即可。无选中空间静默跳过；
            // 失败槽内 JLog 收口不拖垮整理链
            SpaceAttribution.attributeFiles(listOf(outFile.absolutePath))
            _summarizingFor.value = "实时录音"
            summarizeSession.startSummarize(outFile)
        }
    }

    /** 清空录音结果面（新会话前重置） */
    fun clearRecording() {
        _liveTranscriber?.reset()
        _recordError.value = null
    }

    /** 采纳纪要（审阅卡确认——写回 acceptedNote） */
    fun acceptNote() {
        summarizeSession.accept()?.let { markdown ->
            _acceptedNote.value = markdown
            _summarizingFor.value = null
        }
    }

    /**
     * 测试接缝：直接置已采纳纪要文本——生产唯一写点 =
     * [acceptNote]（session 产 Produced 态需真实 LLM，单测不可驱动）；
     * exportNote 归属链（launch(ioDispatcher) 包裹）单测经此缝驱动。set 仅测试语义。
     */
    internal fun setAcceptedNoteForTest(markdown: String) {
        _acceptedNote.value = markdown
    }

    fun dismissReview() {
        summarizeSession.cancel()
        _summarizingFor.value = null
    }

    /** 纪要导出落盘（采纳后用户可存文件——`~/.toolbelt/voice/notes/`） */
    fun exportNote(): File? {
        val note = _acceptedNote.value ?: return null
        val dir = FileUtils.getVoiceNotesDir()
        dir.mkdirs()
        val out = File(dir, "note-${System.currentTimeMillis()}.md")
        out.writeText(note)
        // 导出纪要归属当前调查空间——本函数是 Main 同步
        // 非 suspend（页面 TextButton onClick 直调），归属写（同步 SQLite）必须包进
        // viewModelScope.launch(ioDispatcher)，不阻塞 Main。无选中空间静默跳过；
        // 失败槽内 JLog 收口不影响导出结果（文件已落盘、返回值已定）
        viewModelScope.launch(ioDispatcher) {
            SpaceAttribution.attributeFiles(listOf(out.absolutePath))
        }
        return out
    }
}
