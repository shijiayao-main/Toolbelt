# voice-transcribe

## Purpose

定义语音转写工具页的用户可见行为契约：拖音频 → 离线转写（确定性基座，无 AI 完整可用）→ 转写原文 → AI 整理纪要（半显身层）。本 capability 是「确定性基座 + AI 增强层」叙事在语音域的产品化落点——归智能体组（卖点是纪要）。

## Requirements

### Requirement: 转写入口与音频摄取

语音转写页 SHALL 提供音频文件拖放与选择入口（TaskPageTemplate 四胞胎形态）：支持 wav（直读，不依赖外部工具）、mp3/m4a/flac/ogg（经 ffmpeg 解码为 16kHz mono PCM 后摄取）。用户机器缺失 ffmpeg 时，非 wav 音频 SHALL 明示「需安装 ffmpeg 才能转写该格式」，wav 转写不受影响。

#### Scenario: 拖入 wav 直接转写

- **GIVEN** 用户拖入一个 16kHz 或非 16kHz 的 wav 文件
- **WHEN** 转写执行
- **THEN** wav 直读（无 ffmpeg 依赖），重采样到模型要求的 16kHz mono 后转写；产出原文进任务卡

#### Scenario: mp3 经 ffmpeg 解码转写

- **GIVEN** 用户机器有 ffmpeg 且拖入 mp3 文件
- **WHEN** 转写执行
- **THEN** ffmpeg 子进程解码为 16kHz mono PCM 后转写；结果与同源 wav 转写语义一致

#### Scenario: 缺 ffmpeg 时明示降级

- **GIVEN** 用户机器无 ffmpeg
- **WHEN** 用户拖入 mp3 文件
- **THEN** 该任务标记失败并提示「需安装 ffmpeg」；wav 任务照常可用

### Requirement: 离线转写基座

转写 SHALL 完全离线执行（sherpa-onnx SenseVoice int8 本地推理，中英日韩粤五语）：不依赖任何网络服务与 LLM 端点；转写任务卡呈现处理进度与结果，支持批量文件与任务历史（TaskPageTemplate 形态）。

#### Scenario: 拔网线转写完整可用

- **GIVEN** 用户未配置任何 AI 能力且网络断开
- **WHEN** 用户拖入音频并转写
- **THEN** 转写原文正常产出——基座可用性不依赖 LLM 与网络（模型下载除外）

#### Scenario: 批量转写

- **GIVEN** 用户拖入多个音频文件
- **WHEN** 转写执行
- **THEN** 每文件一张任务卡，逐个转写呈现结果；单文件失败不影响其余文件

### Requirement: 模型分发与就绪检测

SenseVoice int8 模型 SHALL 首次使用时下载到应用数据目录（`~/.toolbelt/models/sense-voice-int8/`，227MB——不内嵌安装包）；下载源为 sherpa-onnx 官方 release。模型未就绪时页面 SHALL 明示下载引导（进度可见）；已存在（完整下载或用户手动放置）SHALL 跳过下载直接可用；下载 SHALL 带 SHA 校验（防截断损坏文件）。

#### Scenario: 首次使用触发模型下载

- **GIVEN** 模型目录为空
- **WHEN** 用户首次进入转写页或发起转写
- **THEN** 页面呈现模型下载引导与进度；下载完成（SHA 校验过）后转写可用

#### Scenario: 手动放置模型即用

- **GIVEN** 用户手工将 model.int8.onnx 与 tokens.txt 放入模型目录
- **WHEN** 用户进入转写页
- **THEN** 就绪检测通过，直接可转写，不触发下载

### Requirement: AI 整理纪要（半显身层）

转写原文上 SHALL 提供「AI 整理纪要」情境按钮：点击后经既有 agent 链（KoogToolLoop 装配 + fs.extended 读取原文）产出结构化纪要——要点 / 待办 / 结论三段；纪要 SHALL 以审阅卡形态呈现，用户接受后才写回（替换或追加原文旁的纪要区）。AI 关闭时按钮 SHALL 置灰并明示降级（配置模型服务后可整理）。

#### Scenario: AI 整理产出三段纪要

- **GIVEN** 一段已完成的转写原文且 AI 已配置
- **WHEN** 用户点击「AI 整理纪要」
- **THEN** agent 读取原文产出要点/待办/结论三段结构化纪要；经审阅卡确认后写回纪要区

#### Scenario: AI 关闭时置灰降级

- **GIVEN** AI 工具开关关闭
- **WHEN** 转写页渲染
- **THEN** 「AI 整理纪要」按钮置灰并明示「配置模型服务后可整理」；转写基座不受影响
