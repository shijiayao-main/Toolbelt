# 语音转写

文件（`feature-voice` 模块根 `feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/voice/`）：

| 文件 | 职责 |
|------|------|
| `VoiceContributions.kt` | 导航注册出口（navItem GRAPHIC_EQ / group=AGENTS） |
| `VoicePage.kt` | TaskPageTemplate 四胞胎 + 模型下载引导区 + 实时录音区 + 审阅卡 + 降级文案 |
| `VoiceViewModel.kt` | FileTaskViewModel 继承（转写任务态 + 纪要审阅态 + aiEnabled 镜像 + 录音装配链；🔴 aiEnabled = `AiEnabledSignal.enabled` 直接订阅——init 一次性快照违契约：VM 窗口级存活下快照永不刷新，开关翻转后页面仍按陈旧值降级） |
| `SherpaTranscriber.kt` | sherpa-onnx 生产实现（🔴 JNI 面单文件钉死——含 SherpaVadEngine + transcribeSamples 段级面；>60 分钟截断产出 `Transcription.truncationNote` 标注） |
| `Transcriber.kt` | 转写器接口缝（生产 sherpa / 测试 fake；返回 `Transcription(text, truncationNote)`——截断标注非空 = 产物只含部分内容，任务卡与转写稿头部必须呈现，取证场景静默截断零容忍） |
| `VadEngine.kt` | VAD 引擎接口缝 + 纯数据 VadSegment（JNI 零外泄——2026-09-21 实时录音批） |
| `LiveTranscribe.kt` | VadSegmenter（窗口→段+音量流）+ LiveTranscriber（段→文本流）+ LiveSegment |
| `MicCapture.kt` | 麦克风采集层（TargetDataLine 16k/mono/16bit——全仓首例 javax.sound.sampled） |
| `ModelStore.kt` | 模型就绪判定 + 下载状态机 + ModelDownloader 接口缝（ASR 双文件 + VAD 单文件两清单项） |
| `GithubModelDownloader.kt` | 生产下载器（tar.bz2 流式解压 + downloadSingle 单文件直下） |
| `VoiceSummarizePrompts.kt` | AI 整理 system prompt（三段纪要） |
| `VoiceSummarizeSession.kt` | AI 整理会话（read_file + write_todos 点名装配） |

配套（`:core`）：`core/media/AudioTranscoder.kt`（ffmpeg argv 解码 + wav 直读）。

---

## 产品形态（确定性基座 + AI 增强层）

拖音频 → **离线转写**（sherpa-onnx SenseVoice int8，中英日韩粤；无 AI 完整可用）→ 转写原文
（原文态 SelectionContainer 可选中复制）→ 「AI 整理纪要」情境按钮（半显身层——TaskCard.onAiSummarize 槽）→ 三段纪要审阅卡
（要点/待办/结论；采纳才写回）。AI 关闭时按钮不渲染 + 页面降级文案（置灰不隐藏；降级
判定 = `AiEnabledSignal.enabled` 订阅消费，非一次性快照）。

## sherpa-onnx 依赖形态（design D1 → 2026-09-20 出库翻转）

**本地 libs 直引 + 构建期自动补齐**：`feature-voice/libs/` 五 jar（`sherpa-onnx-jvm-1.13.8.jar` +
四平台 native jar linux-x64/osx-aarch64/osx-x64/win-x64 各 ~10MB）**不入库**（`.gitignore`：
`feature-voice/libs/*.jar`），缺失/损坏时构建期由 `:feature-voice` 的 `downloadSherpaJars` 任务
自动从 GitHub release 下载 + sha256 校验（来历/手动兜底见 `libs/README.md`）——否决 jitpack
（网络首构 404 不稳）/ mavenLocal（机器间不可复现）；**不入 libs.versions.toml**（非 Maven
Central 坐标）。历史沿革：曾整体入库（2026-09-19），2026-09-20 经 git 历史手术剥离出库
（~39MB → 仓库瘦身至 ~5MB），主保险接线 `compileKotlinJvm dependsOn downloadSherpaJars`
（任何构建路径必先编译 feature-voice）。

- classpath 按构建平台过滤：`sherpaJars` 全量 5 件仍是下载/sha256/up-to-date 指纹的唯一真源，
  依赖清单只挂「jvm 主件 + 本构建平台 native 件」（`sherpaJarPlatforms` 文件名 → `os-arch` 谓词，
  经 `providers.systemProperty("os.name")` / `"os.arch"` 配置期取值归一化匹配——属性读取计入配置
  缓存指纹，换平台不会沿用旧清单）——省掉每包约 28MB 非本平台二进制；
  平台不在 sherpa 资产集合内（如 linux-arm64 / win-arm64 无 release 资产）即配置期 `error()` 失败，
  不产出语音运行期才炸的静默失效包；
- JNI 加载：`SherpaTranscriber.init` 设 `-Dsherpa_onnx.native.path=~/.toolbelt/native-cache/`
  （持久缓存目录——LibraryLoader 自动解包目标）；
- 🔴 隔离守护：`SherpaOnnxIsolationTest`（composeApp arch/）——`com.k2fsa.sherpa.onnx.*`
  全仓只许出现在 `SherpaTranscriber.kt`（归一化扫描剥注释/字符串字面量）+ 构建脚本 jar 引用
  单点 + 白名单锚防腐化（LarkSdkIsolation 模式）。

## 音频摄取（AudioTranscoder——argv 程序调用语义）

**非 SimpleShell**：ffmpeg 是确定性文件变换不是交互 shell 命令——ProcessBuilder argv 直调
（lark-bridge ProgramLauncher argv 语义先例，但 :lark-bridge 不可 import 就地新写）。

```kotlin
object AudioTranscoder {
    fun isFfmpegAvailable(): Boolean                  // ffmpeg -version 探测 + 进程内缓存
    suspend fun decodeToPcm16kMono(input: File): Result<File>  // -ar 16000 -ac 1 -c:a pcm_s16le → 临时文件
    internal suspend fun awaitProcess(process, timeoutSeconds): Pair<Int, String>  // 等待环（超时/取消统一强杀）
    internal fun decodeArgv(input, output): List<String>  // 纯函数（单测缝）
    fun readWavPcm(path: File): Result<Pair<FloatArray, Int>>  // wav RIFF 直读（16bit PCM / 32bit float）
}
```

- wav **零外部依赖**直读（RIFF 头手工解析 + 多声道混合平均 + 重采样交 sherpa acceptWaveform）；
- 非 wav（mp3/m4a/flac/ogg）缺 ffmpeg 时任务失败并明示「需安装 ffmpeg」（spec 降级语义）；
- 🔴 **decodeToPcm16kMono 限时且可取消**（超时/取消收口在 `awaitProcess` 编排层）：
  - `FFMPEG_TIMEOUT_SECONDS = 300s` 单次解码硬上限——解码远快于实时，5 分钟覆盖数小时音频；
    超时 → `destroyForcibly` 子进程并报失败
  - 等待环 100ms 步进（`withTimeoutOrNull` 内轮询 `isAlive`），**全程可响应协程取消**——
    取消即逸出 `CancellationException`（不当解码失败处理），`finally` 中
    `destroyForcibly` 漏杀收口
  - 失败与取消统一收尾临时产物：临时输出文件经 `withContext(NonCancellable)` 删除
    （已取消协程内也删得掉；勿用 runCatching 包等待——会吞 CancellationException）

## 模型分发（ModelStore——design D3）

```kotlin
object ModelStore {
    val modelDir: File   // ~/.toolbelt/models/sense-voice-int8/
    fun isReady(): Boolean         // model.int8.onnx + tokens.txt 双文件在位（日常零 SHA 校验）
    fun download(downloader: ModelDownloader)   // .part 原子改名 + 下载完成 SHA 校验一次 + 进度 StateFlow
}
interface ModelDownloader { fun download(url, targetModel, targetTokens, progress); fun expectedModelSha256(): String? }
```

- 下载源：sherpa-onnx 官方 release `asr-models` tag 的 tar.bz2，**流式解压只取双目标文件**
  （README/test_wavs 不落盘）；
- 🔴 **进度按已读原始字节计**（进度修正口径）：计数流 `ProgressCountingInputStream` 包在
  BZip2 解压层**之下**、对齐 `Content-Length` 的压缩流口径——tar 读出的是解压后字节，解压后
  字节与压缩流长度混算会让进度超 100%、速度/ETA 失真（BZip2 解压比可达 ~2x）；跳过条目
  （`skipFully`）读的原始字节同样过计数流，进度单调覆盖全流（`downloadSingle` 单文件直下无
  解压层，天然同口径）；
- **下载速度/ETA 行**（AG-06 · 批 8）：VM 层 500ms 窗口速度差分流（重组不重置）——
  下载卡显示「N MB/s · 剩余约 M 分」；双坑门控：speed ≤ 0 整行不显（0 MB/s 无信息量）、
  totalBytes=-1（未知总长）无 ETA 可估只显速度；ETA 只做约数取整分（网络抖动不做精确倒计时）；
- **失败/校验失败卡手动放置指引**（AG-06）：完整绝对路径 mono 小字（PathConfirmCard 式）+
  「打开模型目录」钮（`VoiceViewModel.openModelDir`）——国内网络兜底：手动放置双文件
  （model.int8.onnx + tokens.txt）到模型目录即就绪跳过下载；
- **手动放置跳过**：用户把双文件放模型目录即就绪（零镜像清单）；
- 超时 2h（227MB）；取消保全 .part、失败清理回 Failed/Corrupt 态；
- 🔴 **下载链路 IO 下放**（2026-10-09 修复批）：`download` / `downloadVad` 整体包
  `withContext(Dispatchers.IO)`（调用方 scope = Main.immediate）——网络流 / SHA 全文件
  哈希 / tar 解压全阻塞，不下放则 227MB 下载全程冻结 UI；
- **VAD 下载卡对齐 ASR 卡**（同页双标消除）：Downloading 态补字节进度（totalBytes>0 =
  determinate 条「N / M MB」；未知总长 = 不定圈 + 已读字节裸显）+「取消下载」钮
  （接 `cancelDownloadVadModel`）。

## 转写核心（SherpaTranscriber）

- recognizer **单例复用**（模型加载 ~723ms 只付一次——spike 实测）；`numThreads=2`（RTF≈0.04）；
- 超长截断：`MAX_DURATION_MINUTES = 60`（SenseVoice 非流式一次吃整段，超限只转前 60
  分钟）——🔴 截断**带标注**（2026-10-09 修复批）：`Transcription.truncationNote`
  「已截断至 60 分钟（原始 N 分钟）」随产物上抛 → 任务卡 Success(note) + 转写稿 md 头部
  引注双呈现（原始时长向上取整）；
- **VM 构造零 JNI**：Transcriber 经构造参数注入（默认 SherpaTranscriber 惰性——首次转写才触加载）；
- `transcribeSamples(samples, sampleRate)`（2026-09-21 实时录音批）：段样本直喂
  OfflineStream（零文件中转），与文件面共享 recognizer 单例；🔴 逐段推理包
  `withContext(Dispatchers.Default)`（collect 语境 = Main.immediate，JNI 纯 CPU 每段
  秒级——Main 上执行 = 每段冻结全 UI 一次）。

## 模型准备区（P2 合并批 2026-09-25）

`ModelGuideSection` 单点承载两模型的就绪引导：ASR 未就绪 → ASR 引导卡（原样）；
ASR 就绪而 VAD 缺失 → **VAD 引导卡上移至此**（原在实时录音区内的第二个下载入口退役，
同屏双下载卡割裂消失）；录音钮未就绪时区内在钮旁就近显示原因线索（「见上方模型准备区」）。

## 实时录音（VAD 切段伪流式——2026-09-21 add-voice-live-recording-vad）

边说边出字：麦克风 → VAD 切语音段 → 逐段离线转写（段粒度延迟，RTF≈0.04 亚秒；
真流式 OnlineRecognizer 后置视效果再定）。

- **装配链**：`MicCapture`（TargetDataLine 16k/mono/16bit 帧循环，finally 收线）
  → `VadSegmenter`（512 样本窗喂引擎 + 段 drain + RMS 音量流 ×3 增益 + finish flush
  尾段不丢字）→ `LiveTranscriber`（SharedFlow 通道 🔴 串行段转写——recognizer
  非线程安全）→ 段列表流（UI 分段结果区）；
- **VAD 引擎**：`VadEngine` 接口缝 + `SherpaVadEngine`（🔴 JNI 收口
  SherpaTranscriber.kt——隔离守护唯一出现点；纯数据 `VadSegment` 防外泄）；
- **VAD 模型**：ModelStore 第二清单项 `silero_vad.onnx`（629KB，sha256 实测固化；
  `isVadReady()` 独立判定 + `downloadVad` 复用 .part/SHA/原子改名配方 +
  `ModelDownloader.downloadSingle` 单文件缝）——录音门 = ASR && VAD 双就绪；
- **UI**：VoicePage「实时录音」SectionCard（开始/停止 + mm:ss + 音量条 + 分段结果
  LazyColumn + VAD 缺失下载引导）；分段区**贴底跟随**（AG-11 · 批 8：`BottomFollowScrollEffect`
  接线——新段追加时贴底判定通过即平移跟随滚底，用户上翻查看历史不劫持视点；此前的
  「贴底跟随」记载是假声明，现补真实现）；停止后「整理纪要」= 段全文落临时 md 走
  VoiceSummarizeSession 既有链；
- MicCapture 不单测（系统依赖）——fake 面在 VadSegmenter/LiveTranscriber/ModelStore。

## AI 整理链（VoiceSummarizeSession——design D5）

- 工具集 `SUMMARIZE_NAMES = setOf("fs.extended.read_file")` 一件 + write_todos 补挂——
  最精简面（**不新增工具目录条目**）；
- 预算 = 全局 `LlmConfigStore.getMaxIterations()`（整理是短流程无独立 key）；超时 × 4 轮；
- 审阅态：`State.Running` 渲染**整理进行中卡**（目标名 + 秒表耗时 + 「取消」——原
  Running 落 else 零渲染 =「点了整理没反应」；dismissReview 在 Running 态即 cancel，
  Esc/scrim 与取消钮同语义；「整理纪要」钮 busy 接整理会话态，Running 期禁再点——
  原硬编码 false 的静默吞点击收口）→ `State.Produced(markdown)` → 页面 AlertDialog 审阅卡 → 采纳（acceptNote 写回
  acceptedNote）/ 取消（AG-10 · 批 8：「放弃」→「取消」——design-language §4.2 对话框
  dismiss 恒「取消」，dismissReview 语义不变不采纳不写回；「整理失败」卡「知道了」已
  退役改「取消」；VoiceReviewCopyGuardTest 双向守护）/ 重新整理；产物落
  `~/.toolbelt/voice/transcripts/<名>.md`
  （实时录音整理稿同域落 `voice/transcripts/live-<时间戳>.md`——与文件转写稿归域一致；
  早期版本曾落 `<root>/transcripts/`，存量旧文件惰性不迁，原地保留可读），
  纪要导出 `voice/notes/`——导出后行内回执「已导出：$path」（AG-12 · 批 8：
  mono 等宽路径回执，与蒸馏页回执同构模板；回执路径可见性 = VoiceExportReceiptGuardTest 锚）。

## 测试面

`ModelStoreTest`（5 件：就绪/fake 下载回环/SHA Corrupt/已就绪跳过/失败清理）+
`LiveTranscribeTest`（7 件：fake VadEngine 段状态机/音量面/fullText + VAD 就绪判定/
下载回环/Corrupt 清理；等待语义随推理下放 Default 改真实时钟轮询）+
`VoiceAiEnabledSignalTest`（判别性用例——init 快照实现必红，钉死订阅契约）+
`VoiceTaskCancelReceiptTest`（转写取消收口回执——partialOutput 如实回填）+
`AudioTranscoderTest`（4 件：argv 纯函数/wav 头解析/坏头安全/探测缓存）。零模型零 JNI
（Transcriber/VadEngine/ModelDownloader 接口缝 fake——单测纪律）；真机验收背书转写正确性。
