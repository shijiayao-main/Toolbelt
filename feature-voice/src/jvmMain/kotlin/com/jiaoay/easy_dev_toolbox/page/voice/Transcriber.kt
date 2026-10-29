package com.jiaoay.easy_dev_toolbox.page.voice

import java.io.File

/**
 * 转写器接口缝：
 * 生产实现 [SherpaTranscriber]（JNI 面）；测试用 fake（零模型零 JNI——单测纪律）。
 */
interface Transcriber {

    /**
     * 转写一段音频文件。
     *
     * @param audioFile 16kHz mono PCM wav（wav 直读场景由调用方先解出；sherpa 自持重采样）
     * @return [Transcription]（文本 + 可选截断标注）；截断标注非空 = 产物只含部分内容，
     *     任务卡与转写稿头部必须呈现（取证场景静默截断零容忍）
     * @throws TranscriberNotReadyException 模型未就绪
     */
    fun transcribe(audioFile: File): Result<Transcription>
}

/** 转写产物（[truncationNote] null = 全量转写，无降级） */
data class Transcription(
    val text: String,
    val truncationNote: String? = null,
)

/** 模型未就绪（调用方应先引导下载） */
class TranscriberNotReadyException : IllegalStateException(
    "转写模型未就绪——请先下载 SenseVoice 模型（或手动放置到模型目录）",
)
