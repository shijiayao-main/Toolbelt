package com.jiaoay.easy_dev_toolbox.page.voice

/**
 * VAD 引擎接口缝：
 * 生产实现 SherpaVadEngine（在 SherpaTranscriber.kt 内——🔴 JNI 隔离守护钉死
 * `com.k2fsa.sherpa.onnx.*` 全仓唯一出现点）；测试 fake 脚本化段序列
 * （零模型零 JNI——单测纪律，Transcriber 接口缝同配方）。
 *
 * 语义（javap 实测核实 sherpa 1.13.8）：
 * - [acceptWaveform] 窗口样本喂入（Silero 512 样本 @16k = 32ms）；
 * - 段检出：[isEmpty] false → [nextSegment] 逐段取走；
 * - [flush] 强制闭合未完结段（停止录音收尾——尾段不丢字）；
 * - [reset] 会话间复位（跨录音会话共享引擎实例）。
 */
interface VadEngine {

    /** 喂入一窗口样本（float PCM [-1,1]——16kHz） */
    fun acceptWaveform(samples: FloatArray)

    /** 是否有待取语音段 */
    fun isEmpty(): Boolean

    /** 取走一段语音（调用方在 isEmpty==false 后调用） */
    fun nextSegment(): VadSegment?

    /** 强制闭合未完结段（停止收尾） */
    fun flush()

    /** 复位（跨会话） */
    fun reset()

    /** 释放底层资源 */
    fun release()
}

/** 检出的语音段（纯数据类型——JNI 面零外泄，隔离守护纪律） */
data class VadSegment(
    val startSample: Int,
    val samples: FloatArray,
)
