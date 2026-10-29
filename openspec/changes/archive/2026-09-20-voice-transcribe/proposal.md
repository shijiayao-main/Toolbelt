# Proposal: voice-transcribe（语音转写工具页）

## Why

总蓝图 P4-S 收官批：日志工作台（对象绑定专用会话页）已落地，语音转写是「确定性基座 + AI 增强层」产品叙事的最后一个纯演示位——接 LLM 前是原始转写（离线可用），接后是条理纪要。spike 实测（2026-09-20，linux-x64 + JDK 25）已确认 sherpa-onnx v1.13.8 JVM 绑定成熟：SenseVoice int8 中文识别正确、模型加载 723ms / 5.6s 音频识别 210ms（RTF≈0.04），ffmpeg 解码 mp3/m4a 管线畅通——技术风险已清零，现在做产品化落点。

## What Changes

- **新 feature 模块 `:feature-voice`**：语音转写页（智能体组第四成员——注册序蒸馏后）。拖音频文件 → 离线转写 → 转写原文区呈现；「AI 整理纪要」情境按钮（半显身层）产出结构化纪要审阅卡。
- **新 capability `voice-transcribe`**：转写页的用户可见行为契约（确定性基座语义 + AI 整理形态 + 模型分发）。
- **sherpa-onnx 依赖引入**：GitHub release 预编译 jar（`sherpa-onnx-jvm` + 平台 native jar 按 OS 过滤四平台）；不引 jitpack（网络不稳）也不内嵌安装包（227MB 模型会翻倍安装包体积）。
- **模型分发**：首启检测 + 下载到 `~/.easy_dev_toolbox/models/sense-voice-int8/`（GitHub asr-models release 源，SHA 校验 + 已存在即跳过 + 手动放置目录即用）。
- **ffmpeg 解码工具**：`:core` 新增 argv 语义程序调用小工具（ProcessBuilder 直调——对齐 lark-bridge `ProgramLauncher` 先例，非 SimpleShell 的 shell 命令行语义）；wav 直读不依赖 ffmpeg，mp3/m4a/flac/ogg 经 ffmpeg 转 16kHz mono PCM；缺失 ffmpeg 时页面明示降级。
- **修改 capability `navigation-grouping`**：智能体组 3 成员 → 4 成员（语音转写进组的 scenario 更新）。

## Capabilities

- **New Capabilities**：
  - `voice-transcribe`——语音转写工具页行为契约
- **Modified Capabilities**：
  - `navigation-grouping`——智能体组成员扩展（3→4）

## Impact

- **新增**：`:feature-voice` 模块（settings include + `easydevtoolbox.kmp.feature` convention + composeApp 依赖行）；`ToolPage.VoiceTranscribe` 枚举值（Distill 后 Debug 前）；`core/` 新增 ffmpeg argv 调用工具 + 模型目录管理
- **依赖新增**：`com.k2fsa.sherpa.onnx:sherpa-onnx-jvm`（本地 jar 引入——Gradle 方案在 design 定夺）；平台 native jar（linux-x64 / osx-aarch64 / osx-x64 / win-x64 四平台）
- **复用**：TaskPageTemplate 四胞胎形态（FileTaskViewModel 拖放 + 任务卡列表）；KoogToolLoop 装配（AI 整理链经 :core LlmClientFactory 门面）；AiToolsToggle 置灰语义（宿主拼接点 copy 注入——aiEnabled 真源唯一）
- **不影响**：桥接链、工作台研判链、蒸馏链（独立会话，共享工具目录但不入 base names）
- **验收**：全模块守护 `--rerun-tasks --no-build-cache` 全绿 + UiGuard 零白名单新条目 + NavTest 断言面同步（智能体组四成员）
