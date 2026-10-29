# Design: voice-transcribe

## Context

spike 实测（/tmp/p4-s-spike-result.md，2026-09-20）已确认全部技术面：sherpa-onnx v1.13.8 JVM 绑定（builder API）、SenseVoice int8 中文识别正确（加载 723ms / 5.6s 音频识别 210ms）、ffmpeg 解码管线畅通、JNI 加载机制（`-Dsherpa_onnx.native.path` 或 jar 内自动解包）。本设计只解决产品化落点。

## D1 sherpa-onnx 依赖引入（Gradle 方案定夺）

**决策：本地 libs 目录 + flatDir 仓库**——jar 从 GitHub release 下载后入库（jvm.jar 183KB + 4 平台 native jar 约 40MB，git LFS 不需要——总量可控）。

- 落点：`:feature-voice/libs/`（模块内自带依赖，不污染全仓根）
- `build.gradle.kts`：`implementation(files("libs/sherpa-onnx-jvm-1.13.8.jar"))` + 按当前 OS 的 native jar 经 **Detekt 式运行时过滤不适用**——Gradle 没有原生「按 OS 过滤」能力，四平台 native jar **全部进编译期 classpath**（运行时 LibraryLoader 自解包当前平台 so，其余平台 so 闲置在 jar 内约 30MB 磁盘开销——jpackage 打包时需裁剪，见 D4）
- 🔴 不入 `libs.versions.toml`（非 Maven Central 坐标，catalog 管 Maven 依赖）；不入 jitpack（网络构建不稳定，spike 期 jitpack 首构 404）
- 备选方案（已否）：mavenLocal（机器间不可复现，CI 会挂）

**sherpa-onnx 引入是发布面问题**：GitHub release 是 javadoc/源码不可得的二进制分发，版本升级 = 手动换 jar。

## D2 模块与页面形态

**`:feature-voice`**（`easydevtoolbox.kmp.feature` convention——feature-logworkbench 同款配方）：

```
page/voice/
├── VoiceContributions.kt      # 注册出口（navItem icon=GRAPHIC_EQ、group=AGENTS）
├── VoicePage.kt               # TaskPageTemplate 四胞胎 + 纪要区
├── VoiceViewModel.kt          # 模型就绪检测 + 转写任务态 + AI 整理链
├── VoiceTranscriber.kt         # sherpa-onnx 封装（object 单例：recognizer 复用）
└── AudioDecode.kt              # wav 直读 + ffmpeg 解码分支
```

- **页面骨架**：`TaskPageTemplate`（title="语音转写"）+ 成功态任务卡追加「AI 整理纪要」按钮槽（TaskCard 的 `onDeepAnalyze` 槽同款模式扩展——新增独立 `onAiSummarize` 槽避免语义混用）
- **转写任务卡**：成功态展示转写原文（TaskCard 的 Success 描述行）+ 点击卡展开完整原文
- **归组**：`ToolPageGroup.AGENTS`，注册序在 `LarkBridgeContributions` 前、`AiContributions` 后（蒸馏后）——featureRegistrations 拼接点加一行

## D3 模型分发（就绪检测 + 下载）

`ModelStore`（:core 或 feature 内——落 `~/.easy_dev_toolbox/models/sense-voice-int8/`）：

- **就绪判定**：`model.int8.onnx` 与 `tokens.txt` 双文件存在即就绪（**不做 SHA 日常校验**——只在下载完成时校验一次，日常启动快）
- **下载**：GitHub asr-models release 的 tar.bz2 → 流式解压取 model.int8.onnx + tokens.txt 两文件落目录（不全量解压 README/test_wavs）；`.part` 临时文件 + 完成原子改名；下载进度经 StateFlow 进页面
- **手动放置**：目录内两文件在即跳过下载——用户可自携模型（公司内网镜像下载后放入）
- 🔴 下载源 URL 与文件名常量化进 `ModelStore` companion（测试缝）；**不做镜像清单**（首版单源，国内网络问题走手动放置兜底——spec 已背书）

## D4 JNI 打包（jpackage）

native so 的运行时加载路径：`LibraryLoader` 优先读 `-Dsherpa_onnx.native.path`，否则从 classpath jar 内 `sherpa-onnx/native/<os>-<arch>/` 解包到临时目录。

**决策：依赖 jar 内自动解包**（零打包配置）——App 启动时首次转写解包到 `~/.easy_dev_toolbox/native-cache/`（LibraryLoader 默认临时目录改持久缓存目录经系统属性注入）。jpackage 裁剪 native jar 到当前平台是 P5 后优化项，**首版四平台 jar 全带**（多 ~30MB 安装包，可接受）。

## D5 AI 整理链（半显身层）

复用 KoogToolLoop 既有装配（**不新增工具目录条目**——fs.extended.read_file + write_todos 经 `AgentToolCatalog.buildRegistry` 点名装配）：

- system prompt：`VoiceSummarizePrompts`（独立常量文件，WorkbenchPrompts 先例——不并入 KoogAgentPromptPresets）：三段纪要格式（要点/待办/结论）+ 原文不改写纪律 + 中英日韩粤混排处理
- 会话围栏：转写产物文件 register 进 context（附件渐进同工作台）；预算 = 全局 agent 预算（整理是短流程，不设独立 key）
- 审阅卡：产出三段纪要先进 UI 审阅态，用户「采纳」才写回纪要区（TranscriptNote 状态）；「重新整理」可再跑
- aiEnabled 置灰：宿主 ToolPageRegistry 拼接点 copy 注入（AiToolsToggle 真源唯一——feature 互不依赖铁律，log-workbench 同款先例）；页内按钮置灰态读 `LlmConfigStore.getAiEnabled` 镜像（WorkbenchViewModel.aiEnabled 同款）

## D6 ffmpeg 解码（argv 语义工具）

`:core` 新增 `core/media/AudioTranscoder.kt`（ProcessBuilder argv 直调——**非 SimpleShell**：ffmpeg 是确定性程序调用不是 shell 命令行交互；lark-bridge ProgramLauncher 的 argv 语义先例，但 `:lark-bridge` 是 jvmTarget 11 纯 JVM lib 不可 import——**在 :core 新写**，~60 行）：

- `fun isFfmpegAvailable(): Boolean`（`ffmpeg -version` 探测，结果缓存进程内）
- `fun decodeToPcm16kMono(input: File, output: File): Result<File>`（`ffmpeg -y -i <in> -ar 16000 -ac 1 -c:a pcm_s16le <out>.wav`；stderr 捕获进错误信息）
- wav 直读：`AudioDecode.readWav(path)`（JDK `AudioSystem` 或手解 RIFF 头——16kHz/48kHz/立体声都重采样进 sherpa `acceptWaveform`，sherpa-onnx 自持重采样）
- 🔴 守护测试：`ProcessBuilder` 在 :core 的出现点由现有扫描测试口径（若 `core/` 域有扫盘守护）核对——lark-bridge 域钉死唯一出现点的守护不受影响（那是 :lark-bridge 模块内的断言）

## D7 转写核心（VoiceTranscriber）

- **单例 object**：recognizer 进程内复用（模型加载 723ms 只付一次）；`@Volatile` 状态 + `synchronized` 初始化（files 域守护先例：零 @Volatile 原则用 AtomicReference——核对 files 域守护测试口径，本域在 feature 不在 files 域）
- API：`fun transcribe(pcmWav: File): Result<String>`（IO 线程调用方负责）；模型未就绪返回明确错误码
- **大文件分段**：SenseVoice 非流式一次性吃整段（spike 实测 5.6s OK）；>30min 音频按固定窗分段转写拼接——**首版不做**，任务卡对超长音频标注「已转写前 N 分钟」截断（上限常量 60min，超限截断转写）
- **线程模型**：转写在 `Dispatchers.IO` 串行队列执行（sherpa-onnx 线程安全但单 recognizer 实例——`numThreads=2` 是推理内并行）；批量文件逐个串行转写

## D8 UiGuard 与守护

- 零白名单目标：页面全 token 化（复用四胞胎既有 token 面——无新布局尺寸需求；纪要区走 TonalCard/PageScaffold 既有 token）
- NavTest 断言面：智能体组 `[LogWorkbench, AiSettings, Distill, Voice]`（注册序）；navActivity 无（转写无后台活动面——任务卡即态）；aiEnabled set 加 `Voice`
- ModuleGraphGuard：feature-voice 只依赖 :core/:ui（convention 预置）+ 本地 jar；无 feature→feature import
- 守护测试新增：**sherpa-onnx 类型隔离**（对齐 LarkSdkIsolation 模式）——`com.k2fsa.sherpa.onnx.*` 只允许出现在 `VoiceTranscriber.kt`（单文件钉死，页面/VM 不碰 JNI 面）

## D9 测试策略

- **零模型单测**：VoiceTranscriber 的 JNI 面不进单测（模型 227MB 不进 CI）——**转写核心经接口缝抽象**：`interface Transcriber { fun transcribe(...): Result<String> }`，生产实现 sherpa-onnx，测试用 fake（返回固定文本）；真机验收才触 JNI
- 单测面：模型就绪判定/下载状态机（fake 下载器）/wav 头解析/ffmpeg argv 构造/AI 整理 prompt 组装/审阅卡状态迁移/NavTest 四成员断言
- 真机验收清单：拖 wav 转写正确 / mp3 解码转写 / 模型下载全流程 / 手动放置跳过 / AI 整理三段纪要 / 拔 AI 开关置灰
