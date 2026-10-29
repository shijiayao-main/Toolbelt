# Proposal: add-voice-live-recording-vad

## Why

需求（2026-09-20）：「音频转写需支持实时录制（目前只有拖文件离线转写）」。
现有语音转写页只吃文件输入（拖入/选择）；会议/口述场景需要边说边出字。
plan v3 已裁定路线：**VAD 切段伪流式起步**（保 SenseVoice 模型——spike 实测
RTF≈0.04，每段转写亚秒级；真流式 OnlineRecognizer + zipformer streaming 需新
模型分发，后置视伪流式真机效果再定）。

## What Changes

1. **麦克风采集层**（全仓零麦克风代码，新建 `MicCapture`，约百行）：
   `javax.sound.sampled.TargetDataLine` 16kHz mono 16bit PCM 采集，帧循环读
   → float[] 样本流。IO 线程独立 scope，状态面（idle/recording）+ 音量（RMS）。
2. **Silero VAD 切段**（`VadSegmenter`）：sherpa jar 内 `com.k2fsa.sherpa.onnx.Vad`
   API 已核实可用（acceptWaveform/compute/front/pop/flush 全套）；Silero VAD
   模型进 `ModelStore` 分发清单（vad 清单项 + 就绪双判定：ASR 双文件 + VAD 单文件）。
3. **分段转写管线**（`LiveTranscriber`）：VAD 切出语音段（SpeechSegment.samples）
   → 现有 OfflineRecognizer 逐段转写 → 段结果流（每段出字即上屏）——复用
   SherpaTranscriber 的 recognizer 单例与装配配方。
4. **录音 UI**（VoicePage 增量）：「实时录音」SectionCard——开始/停止钮 +
   录制时长/音量条 + 分段结果区（段序号 + 文本，滚动跟随）；模型未就绪（ASR
   或 VAD 任一缺失）时录音钮置灰并引导下载。
5. **AI 整理联动**：停止后整段转写文本可一键发 VoiceSummarizeSession（既有链）。

## Out of Scope

- 真流式 partial（OnlineRecognizer + zipformer streaming——后置批）
- VAD 段边界优化（说话人分离、重叠语音——远期）
- 录音文件落盘持久化（本批只出文字结果，不存音频文件）
