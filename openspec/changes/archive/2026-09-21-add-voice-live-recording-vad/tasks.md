# Tasks: add-voice-live-recording-vad

## 1. VAD 模型分发

- [x] 1.1 ModelStore VAD 清单项（`isVadReady()` + VAD_DOWNLOAD_URL/sha256 常量——
      URL/sha 经真实下载实测核实：silero_vad.onnx 629KB @ asr-models release +
      downloadSingle 接口缝 + 复用下载管线 + vadState 独立流）+ 单测（fake
      downloader 就绪判定/下载回环/Corrupt 清理——7 用例）
- [x] 1.2 sherpa release 实际 URL 与 sha256 核实（GitHub API asset 清单 +
      实下载 sha256sum——`9e2449e1...fd6`；native so JNI 符号 nm 验证全套在位）

## 2. 采集与切段引擎

- [x] 2.1 `VadEngine` 接口缝 + `SherpaVadEngine`（com.k2fsa Vad 包装——
      acceptWaveform/front/pop/flush/reset + Silero 阈值/窗口/时长参数）——
      JNI 隔离便于 fake 测试
- [x] 2.2 `VadSegmenter`（VadEngine 驱动：窗口喂入 → 段检出 drain + RMS 音量流
      + finish flush 尾段）+ fake engine 段状态机单测
- [x] 2.3 `MicCapture`（TargetDataLine 16k/mono/16bit 采集 → 窗口回调 +
      麦克风缺失/占用降级 + finally 收线资源必收）
- [x] 2.4 `LiveTranscriber`（段 SharedFlow 通道 → 串行段转写 → 段列表流 +
      fullText 拼接）+ SherpaTranscriber.transcribeSamples 段级公开面
      （recognizer 单例复用——JNI 类型零外泄）+ fake 单测

## 3. VM 与 UI

- [x] 3.1 VoiceViewModel 录音面（recording/error/elapsed/segments/level 流 +
      startRecording 装配链 mic→segmenter→transcriber + stopRecording flush
      资源全收 + summarizeRecording 段全文落 md 走既有链 + downloadVadModel）
- [x] 3.2 VoicePage「实时录音」SectionCard（开始/停止 + mm:ss 时长 + 音量条 +
      分段结果 LazyColumn + VAD 缺失下载引导行 + 整理纪要/清空入口 + AI 关闭
      降级文案）全 token 化零白名单

## 4. 收口

- [x] 4.1 全模块守护 `--rerun-tasks --no-build-cache` 全绿 + openspec validate +
      归档 + 真机录音验收剧本（design D7——待真机麦克风环境）
