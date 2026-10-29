# Design: add-voice-live-recording-vad

## D1 模块内新增三件（feature-voice，零跨模块改动）

```
MicCapture.kt      — TargetDataLine 采集：16kHz/mono/16bit PCM 帧 → float[] 流
VadSegmenter.kt    — com.k2fsa.sherpa.onnx.Vad 包装：acceptWaveform 窗口喂入，
                     段检出（empty==false → front/pop 循环）→ SpeechSegment.samples
LiveTranscriber.kt — 段→文本管线：VAD 段样本喂 recognizer.createStream/decode
                     （SherpaTranscriber recognizer 单例复用配方）
```

## D2 API 细节（javap 实测核实——2026-09-21）

- `Vad(VadModelConfig)`；`acceptWaveform(float[])`（窗口大小样本数——Silero 512 @16k）；
- 段检出：`empty()` false → `front()`（SpeechSegment: getStart/getSamples）→ `pop()`；
- 收尾：`flush()` 强制闭合未完结段；`reset()` 会话间复用；
- Config：`VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder()
  .setModel(path).setThreshold(0.5f).setMinSilenceDuration(0.5f)
  .setMinSpeechDuration(0.25f).setWindowSize(512).build()).setSampleRate(16000).build()`
- 🔴 JNI 前置同 SherpaTranscriber：`sherpa_onnx.native.path` 已在既有 init 设置
  （MicCapture/VadSegmenter 构造时机晚于 SherpaTranscriber 单例即可——同一进程
  property 只设一次）。

## D3 ModelStore VAD 清单项

- 复用既有下载管线（ModelDownloader 接口缝 + .part 原子改名 + SHA 校验）；
- VAD 项：`silero-vad.onnx` 单文件；`vadModelDir = models/silero-vad/`；
- 就绪判定扩展：`isReady()`（ASR 既有）不动；新增 `isVadReady()`——录音门 =
  `isReady() && isVadReady()`（文件转写面不受 VAD 缺失影响——零回归）；
- 下载源常量 `VAD_DOWNLOAD_URL`（sherpa 官方 release silero_vad.onnx）+ SHA
  常量——**动工时从 release 页核实真实 URL 与 sha256**。

## D4 线程模型

```
MicCapture（IO 线程循环）
  └─ Channel<float[]> ─→ VadSegmenter.accept()（消费协程）
                          ├─ 段检出 → LiveTranscriber.transcribeSegment(samples)
                          │             └─ StateFlow<List<Segment>>（序号+文本）
                          └─ 音量 RMS → StateFlow<Float>（UI 音量条）
```
- 采集协程 cancel 即停（TargetDataLine.stop+close 在 finally）；
- 转写在段检出协程内串行（recognizer 非线程安全——单流逐段）；
- VM 持 scope，页面销毁不自动停录音（桌面单窗口语义——stop 由用户显式）。

## D5 UI（VoicePage 增量——「实时录音」SectionCard）

- 开始/停止单钮（状态切换文案）；录音中显示时长（mm:ss）+ 音量条（RMS→
  LinearProgressIndicator 归一）；
- 分段结果区：LazyColumn 段行（`#n · 文本`）+ 尾部跟随（🔴 scrollToItem 零动画
  铁律）；
- 模型未就绪：钮置灰 + 缺失清单文案（ASR/VAD 分指）；
- 「整理纪要」钮：分段文本 join("\n") 发 vm.startSummarize 既有链（文本源 =
  录音段而非任务产物——VoiceSummarizeSession 输入是文本，直接复用）；
- 全 token 化零白名单（音量条宽/高走 Dimens 既有档）。

## D6 测试策略

- VadSegmenter：段状态机纯逻辑面（feed 样本 → 段检出回调）用 fake Vad 不可行
  （JNI 依赖）——改为**接口缝**：`VadEngine` interface（acceptWaveform/front/
  empty/pop/flush）包真实现，测试 fake 数组脚本化段序列；
- LiveTranscriber：fake Transcriber 段→文本直测；
- MicCapture：不单测（TargetDataLine 系统依赖——真机验收面）；
- ModelStore：VAD 清单项就绪判定/下载状态机单测（fake downloader 既有配方）；
- 全模块守护终跑 + 真机录音验收（麦克风权限/延迟体感——具备麦克风的桌面环境）。

## D7 真机验收剧本

1. 语音页 → 录音区（ASR+VAD 就绪）→ 开始 → 说两句话各停顿 → 段文本 2s 内逐段出
2. 说满长句（>20s）→ VAD maxSpeechDuration 切段不崩
3. 停止 → 尾段 flush 出字 → 「整理纪要」→ 审阅卡三段格式
4. 删 VAD 模型文件 → 录音钮置灰 + 缺失文案 → 下载引导可见
