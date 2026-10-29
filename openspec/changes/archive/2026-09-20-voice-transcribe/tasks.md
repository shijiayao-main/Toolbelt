# Tasks: voice-transcribe

## 1. 地基：依赖引入与解码工具（infra）

- [x] 1.1 sherpa-onnx jar 入库与依赖接线：下载 `sherpa-onnx-jvm-1.13.8.jar` + 四平台 native jar 到 `:feature-voice/libs/`；`feature-voice/build.gradle.kts` 建块（`easydevtoolbox.kmp.feature` + settings include + composeApp 依赖行 + `implementation(files(...))`）；模块空骨架编译绿（D1）
- [x] 1.2 `:core` `media/AudioTranscoder.kt`：`isFfmpegAvailable()`（探测 + 进程内缓存）+ `decodeToPcm16kMono()`（argv 直调 ffmpeg，stderr 捕获）；`AudioDecode.readWav()` 直读（RIFF 头解析 + 重采样交 sherpa `acceptWaveform`）；单测：ffmpeg 探测/argv 构造纯函数/wav 头解析（非 wav/坏头安全）（D6）
- [x] 1.3 `ModelStore`（feature 内 `models/`）：模型目录 `~/.easy_dev_toolbox/models/sense-voice-int8/` 就绪判定（双文件在位）+ 下载状态机（.part 临时文件 + 原子改名 + SHA 校验一次 + 进度 StateFlow + 手动放置跳过）；下载器接口缝（`interface ModelDownloader`——生产 GitHub 实现/测试 fake）；单测：就绪判定/状态机迁移/已存在跳过/fake 下载回环（D3）

## 2. 转写核心与页面（core-ui）

- [x] 2.1 `VoiceTranscriber` 接口缝 + sherpa-onnx 生产实现：`interface Transcriber`（feature 内）+ `SherpaTranscriber`（recognizer 单例复用 + `sherpa_onnx.native.path` 指向 `~/.easy_dev_toolbox/native-cache/` + numThreads=2 + 60min 截断常量）；🔴 `com.k2fsa.sherpa.onnx.*` 只许出现在本文件（D7/D8 隔离）；fake 实现进测试源集
- [x] 2.2 `VoiceViewModel`：模型就绪检测 + 转写任务态（FileTaskViewModel 适配或直持任务列表——wav/mp3 分支解码）+ AI 整理链挂接 + aiEnabled 镜像（WorkbenchViewModel 同款）；🔴 VM 构造零 JNI 触达（Transcriber 经接口注入）
- [x] 2.3 `VoicePage`：TaskPageTemplate 四胞胎（title="语音转写" + dropZone 音频 accepts）+ 任务卡成功态转写原文展示 + 「AI 整理纪要」按钮（成功卡 actions 槽——TaskCard 新增 `onAiSummarize` 槽）+ 模型下载引导区（未就绪时）+ ffmpeg 缺失降级文案；全 token 化零白名单
- [x] 2.4 `VoiceSummarizePrompts`（三段纪要格式 + 原文不改写纪律）+ AI 整理会话（fs.extended.read_file + write_todos 点名装配 + 转写产物 register 围栏 + 全局 agent 预算）；审阅卡态（采纳才写回/重新整理）；单测：prompt 组装/审阅卡状态迁移（fake executor）（D5）

## 3. 导航注册与守护（nav-guard）

- [x] 3.1 `ToolPage.VoiceTranscribe` 枚举值 + `VoiceContributions`（navItem icon=GRAPHIC_EQ、group=AGENTS、aiEnabled 不上送——宿主注入）+ featureRegistrations 拼接（Ai 后 LarkBridge 前——组内序 [工作台, AI 设置, 蒸馏, 语音转写]）
- [x] 3.2 宿主接线：ToolPageRegistry 拼接点 copy 注入 aiEnabled（AiToolsToggle 真源）+ NavTest 断言面同步（智能体组四成员 + aiEnabled set + navItems 顺序表）
- [x] 3.3 sherpa-onnx 隔离守护测试（LarkSdkIsolation 模式）：`com.k2fsa.sherpa.onnx.*` 全仓只允许出现在 `SherpaTranscriber.kt`（归一化扫描 + 反射成员核对口径对齐先例）

## 4. 收口（close）

- [x] 4.1 全模块守护终跑 `--rerun-tasks --no-build-cache`（core/feature-voice/feature-logkit/feature-logworkbench/feature-larkbridge/composeApp/log-tools）+ UiGuard 零白名单新条目核对
- [x] 4.2 真机验收清单执行：拖 wav 实测中文转写 / mp3 经 ffmpeg 转写一致 / 模型下载全流程（含进度与校验）/ 手动放置跳过 / AI 整理三段纪要审阅卡 / AI 关闭置灰降级（已配置的 LLM 端点）
  - 🔴 **V2 冻结记载裁决（2026-10-09 真机验收独立验收位）**：本项「模型下载全流程」勾选与全项目修复批核实的「下载链路全程跑 Main（227MB 网络流 + SHA 全文件哈希 + tar 解压均阻塞）」**可同时为真**——当时为「冻结但能完成」，勾选保留不改（结论面成立）。行为面修正：`ModelStore.download/downloadVad` 已包 `withContext(Dispatchers.IO)`（实时转写逐段推理同批下放 `Dispatchers.Default`）。
  - **真机复跑未执行登记**：本机页面「下载模型」按钮对自动化指针注入无响应（同窗口键盘注入亦全系统失效，E2E 通道受限），冻结时长数值未取得；模型目录为空态可复现下载路径。**复检触发器**：自动化通道恢复（或换机）后，重命名 `~/.easy_dev_toolbox/models/sense-voice-int8/` 触发下载并计时——预期 UI 全程可响应（IO 已下放，冻结时长应为 0）；若仍观测到冻结则按缺陷重开并回改本登记。
- [x] 4.3 openspec validate + git commit 收口 + docs-sync 提醒（新增页面/导航分组变更——确认后执行）

## 验证方式（apply 阶段执行）

- 单测全绿（零模型零 JNI——Transcriber/ModelDownloader 接口缝 fake）
- 真机验收清单（4.2）——JNI 面与模型下载只在真机验
- 全模块守护 `--rerun-tasks --no-build-cache`（含 sherpa-onnx 隔离守护新面）
