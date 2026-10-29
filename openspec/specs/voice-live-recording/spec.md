# voice-live-recording Specification

## Purpose
TBD - created by archiving change add-voice-live-recording-vad. Update Purpose after archive.

## Requirements

### Requirement: 实时录音分段转写

语音转写页 SHALL 提供实时录音能力：用户开始录音后，麦克风采集的音频经 Silero
VAD 切分语音段，每段实时喂现有 SenseVoice 离线转写器，段文本 SHALL 即时上屏
（伪流式——段粒度延迟，非音素级 partial）。停止录音 SHALL 结束采集并 flush
尾部未完结段。

#### Scenario: 边说边出字

- **GIVEN** ASR 与 VAD 模型就绪、用户点击开始录音
- **WHEN** 用户说完一段话并停顿（VAD 判定段结束）
- **THEN** 该段文本在 2 秒内出现在分段结果区（RTF≈0.04 转写 + VAD 停顿窗口）

#### Scenario: 模型未就绪置灰

- **GIVEN** ASR 或 VAD 任一模型缺失
- **WHEN** 用户进入录音区
- **THEN** 录音钮置灰 + 引导文案指明缺失模型与下载入口

#### Scenario: 停止收尾

- **GIVEN** 录音中、用户说完最后一个字立即点停止
- **WHEN** 停止触发
- **THEN** 采集结束，VAD flush 尾段并完成尾段转写，无丢字

### Requirement: VAD 模型分发

Silero VAD 模型 SHALL 纳入 ModelStore 分发：模型清单新增 VAD 项（独立下载源 +
SHA 校验 + 同款 .part 原子改名配方）；就绪判定扩展为 ASR 双文件 + VAD 单文件
三者齐备。下载进度与失败态复用既有 State 面。

#### Scenario: VAD 模型单独下载

- **GIVEN** ASR 就绪、VAD 缺失
- **WHEN** 用户点录音区的下载引导
- **THEN** VAD 模型经既有下载器管线拉取，进度可见，完成后校验 SHA 并就绪

### Requirement: 录音结果整理联动

停止录音后，累计分段文本 SHALL 可一键发起既有 AI 整理纪要链（VoiceSummarizeSession
——三段纪要纪律同款）；AI 关闭时整理入口置灰降级（与文件转写面同语义）。

#### Scenario: 录音全文整理

- **GIVEN** 录音完成且分段文本非空、AI 已开启
- **WHEN** 用户点「整理纪要」
- **THEN** 全部分段文本按顺序拼接进 VoiceSummarizeSession，审阅卡流程同既有面
