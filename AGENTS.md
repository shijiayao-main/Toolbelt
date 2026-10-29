# AGENTS.md — Toolbelt AI 协作指南

> 本文件是各 AI 编码助手（Claude Code / Codex / Copilot / Cursor / Gemini CLI 等）在本仓库工作的通用入口。读完本文件即可获得项目全貌，并在动手前按任务类型去 `docs/` 知识库读取对应专题文档，避免重复遍历源码。

---

## 项目是什么

Toolbelt（原 EasyDevToolbox）是一个 Kotlin Compose Desktop（JVM）开发者工具箱桌面应用，主打移动与嵌入式设备日志分析，并集成飞书桥接。Gradle 多模块：

| 模块 | 定位 | 源码根 |
|------|------|--------|
| `:composeApp` | 桌面应用主体（导航 + 首页 + ViewModel 装配） | `composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` |
| `:core` | 核心层（theme / data / log / initializer / entity / device 装配） | `core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/` |
| `:adb-core` | 内置 adb 分发与端点解析（jvmTarget 11 纯 JVM lib，包 `com.jiaoay.toolbelt.adb`：平台判定 / 二进制释放 / 命令重写；构建期下载 platform-tools 三平台件） | `adb-core/src/main/kotlin/com/jiaoay/toolbelt/adb/` |
| `:device-core` | 设备域能力（jvmTarget 11 纯 JVM lib，包 `com.jiaoay.toolbelt.device`：设备检测 / 文件 / 包管理 / 布局 / 性能 / 按键 / 无线；执行通道与日志由宿主注入） | `device-core/src/main/kotlin/com/jiaoay/toolbelt/device/` |
| `:ui` | 共享 UI 组件（PageScaffold / TonalCard / CommitTextField 等） | `ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/` |
| `:feature-timestamp` `:feature-logkit` `:feature-shelltools` | 各工具页 feature 模块 | `feature-*/src/jvmMain/kotlin/...` |
| `:feature-logcat` | logcat 实时抓取 feature（引擎 / VM / 面板 + 观测域 ConfigStore + App 级 Runtime），挂载于 dock 第六空间「设备观测」宿主聚合页 | `feature-logcat/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/logcat/` |
| `:feature-screencast` | scrcpy 投屏 feature（协议面 / 只读投屏引擎 / 面板 + App 级 Runtime，JavaCV 解码栈重依赖单模块隔离），设备观测空间第二成员 | `feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/` |
| `:feature-logworkbench` | 日志工作台页（分析输入/证据流/分析报告，家页） | `feature-logworkbench/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` |
| `:feature-ai` | AI 设置页 + 知识蒸馏（DistillPageContent，智能体中心子页） | `feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` |
| `:feature-archive` | 解压页 feature（ArchivePage，压缩包/分卷/7z 解压，工具聚合页子页） | `feature-archive/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` |
| `:feature-voice` | 语音转写页（VoicePageContent，智能体中心子页） | `feature-voice/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` |
| `:feature-mcp` | MCP 服务外放页（McpExposePage，吸顶头用例） | `feature-mcp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` |
| `:feature-quest` | 调查工作台证据流（FlowPageContent，智能体中心子页） | `feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` |
| `:feature-larkbridge` | 飞书桥接页 feature（UI 编辑器 + 宿主门面 `LarkBridgeManager`） | `feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` |
| `:test-support` | 跨模块测试支撑件（SceneGuardSupport 场景守护渲染底座，宿主域 jvmMain 源集形态） | `test-support/src/jvmMain/kotlin/...` |
| `:lark-bridge` | 纯 JVM 桥接内核（jvmTarget 11，配置域 / 处理链 / agent / confirm / CLI，带独立 CLI） | `lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/` |
| `:lark-gateway` | 飞书 SDK 隔离适配层（**唯一**允许出现 `com.lark.oapi.*` 的模块） | `lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/` |
| `:lark-ws` | 自研飞书长连接客户端（jvmTarget 11，零 SDK / 零第三方网络库，JDK HttpClient/WebSocket；**入站恒自研**——SDK websocket 路径已随下线批 2026-09-12 删除，SDK 侧恒 webhook 出站 facade） | `lark-ws/src/main/kotlin/com/jiaoay/lark/ws/` |
| `:log-tools` | 纯 JVM 库：XLog 解码 / 日志 Tag/时间过滤 / 文件分割，带独立 CLI | `log-tools/src/main/kotlin/com/jiaoay/` |

依赖方向（飞书桥接链）：`:composeApp` → `:feature-larkbridge` → `:lark-bridge` → `:lark-gateway` → `oapi-sdk`；`:lark-gateway` 另依赖 `:lark-ws`（下线批 2026-09-12 起**入站恒自研**：`WsInboundTransportFactory` 必传构造参数，SDK 侧恒 webhook 只留出站/identity facade）；`:lark-bridge` 另依赖 `:log-tools`（引用文件预处理：`.xlog`/`.alog` 解密）。
🔴 `:lark-bridge` 是 jvmTarget 11 的纯 JVM lib，**不得依赖 `:core`（jvmTarget 25）**——数据根路径由宿主注入，lib 侧不引用 `JLog`/`JResult`/`JErrorCode`/`AppDataStore`/`FileUtils`；SDK 类型只出现在 `:lark-gateway`。

## 常用命令

```bash
./gradlew :composeApp:run                       # 运行桌面应用（入口 MainKt）
./gradlew build                                 # 全模块构建（含测试）
./gradlew :composeApp:jvmTest                   # 主应用测试（含 UI 架构守护测试）
./gradlew :composeApp:guardTests :core:guardTests  # 守护面快速通道（composeApp 只跑 arch/ 守护测试、排除 *SceneGuardTest 场景渲染类；core 只跑 system/ 扫盘守护；两任务永跑——不入 up-to-date/build cache，不受扫盘假绿影响；内环反馈增益，不替代全量验收门槛，报告 XML 落各自 build/test-results/guardTests/）
./gradlew :log-tools:test                       # log-tools 模块测试
./gradlew :log-tools:shadowJar                  # log-tools 独立 CLI fat-jar
./gradlew :composeApp:packageDistributionForCurrentOS  # 打包本机安装包
```

JDK 工具链：JetBrains JDK 25（`gradle/gradle-daemon-jvm.properties` 自动下载，经 `updateDaemonJvm` 重生成）；版本统一由 `gradle/libs.versions.toml` 管理。宿主侧模块 jvmTarget 为 25（`KmpComposeConventionPlugin`），CLI 库模块保持 11（`JvmCliConventionPlugin`）。

### 开发运行已知噪音：热重载嵌套入口点告警（定案，勿再排查）

`./gradlew :composeApp:run`（热重载插件 `org.jetbrains.compose.hot-reload` 开发态）每次启动会打一条
`Warn | Skipping 'DevelopmentEntryPoint': We're already in an entry point` + 定位堆栈——**预期行为非 bug，勿再排查**：

- 机制：热重载 agent 把每个 `ComposeWindow.setContent` 的内容根包成 reload 入口点；本应用是恒组合双窗形态
  （主窗 + 投屏独立窗，U29 GC 根治契约要求启动期组合第二窗），第二窗的 setContent 必然命中嵌套判定 →
  告警一条后**照常直接执行内容**（窗功能零影响）。agent 对任何多 `ComposeWindow` 应用都如此（`ComposeDialog` 同样包裹）。
- 生产零影响：`packageDistributionForCurrentOS` 产物不含 agent，无此告警。
- 开发态 DX 代价（唯一真实影响）：投屏独立窗不注册为独立热重载入口点——**改投屏窗 UI 代码后热重载可能不生效，须重启 run 验证**。
- 安静模式（按需，压制 agent 全部 Warn 级——含窗口状态反序列化等工具告警，排查热重载异常时先摘掉）：
  `./gradlew :composeApp:run -Pcompose.reload.logLevel=Error`（gradle 属性 / 系统属性 / 环境变量三级均可，默认 Info）。

### 品牌更名白名单（品牌定名后冻结，防再议）

产品已由 EasyDevToolbox 更名 **Toolbelt**（窗口标题/bundleID `com.jiaoay.toolbelt`/打包产物名/CI Release 名/全部文案已改）。以下出现点**有意保留不改**，勿在后续批再提：

- **Kotlin 包名 `com.jiaoay.easy_dev_toolbox.*`**：全仓守护测试、跨模块引用、反射锚点成本巨大，且用户不可见；与品牌名无需一致。
- **build-logic 插件 id `easydevtoolbox.*`**：Gradle 内部标识，不出现在用户可见面。同性质保留面：`feature-shelltools` 的 Compose Resources 生成资源包名 `easydevtoolbox.feature.shelltools.generated.resources`（`packageOfResClass` 声明，改它须连动 import 与 convention，收益为零）。
- **openspec 归档区**：历史记录按当时名称保留。同性质保留面：`openspec/specs/lark-bridge-module/spec.md` 已作废（superseded）留档，其旧命名叙述按归档原则保留。

### 存储布局定名（定名后冻结，不再改）

- **机密根 `~/.toolbelt/`**（`core/data/FileUtils.kt` 的 `APP_DATA_DIR_NAME`，原 `~/.easy_dev_toolbox/`）：配置/密钥/db/日志/审计等应用私有数据。App 启动 main() 入口段经 `core/data/LegacyDataRootMigration` 一次性整树改名迁移（先于一切数据根访问）；旧根字面量仅存于迁移常量与 `SensitiveFilePolicy` 出站打码双形态兼容（`StoragePathLiteralGuardTest` 白名单钉死）。纯 CLI 用户手动 `mv ~/.easy_dev_toolbox ~/.toolbelt`。
- **可见产物树 `~/Documents/Toolbelt/`**：用户交付物（reports / recordings / perf / logcat / snapshots（投屏快照）/ knowledge（知识包导出））。路径构造唯一真源 = `FileUtils` 可见产物树访问器族；根可由用户在设置页「产物目录」配置（DataStore key `visible_export_root`，空 = 默认 Documents 根；启动期 `VisibleExportRootRestoreInitializer` 读入 `FileUtils.applyVisibleExportRoot` 进程单源）。旧默认根 `~/Toolbelt/` 的存量产物不迁移不回读。
- **数据根子目录必须走 FileUtils 访问器**（`getMemoryDir()` 等），禁止 `File(getAppDataDir(), "字面量")` 直拼（`StoragePathLiteralGuardTest` C 组拦截）；桥接 CLI 缺省根（`CliSupport.kt`）与 App 注入根的同根契约由 `LarkCliDataRootParityTest` 钉死。
- DataStore 文件名同改 `toolbelt.preferences_pb`（`LocalDataManager` 首读写自动 rename 旧名文件，key 内容无损）。

### 桌面应用截图验收管线（Linux/Wayland 环境实测配方）

UI 改动的视觉验收用以下管线截取应用窗口（`gdm-screenshot`/GNOME D-Bus Screenshot/portal 在 Wayland 合成器策略下常被拒绝，`xwd -root` 因合成器保护 BadMatch 不可用；替代路线）：

```bash
./gradlew :composeApp:run &                     # 后台启动应用
xwininfo -root -tree | grep Toolbelt      # 找窗口 ID（应用走 XWayland，窗口树可见）
xwd -id <WID> -out /tmp/shot.xwd                # 截取窗口（X11 客户端间 GetImage 不受合成器策略限制）
ffmpeg -y -loglevel error -i /tmp/shot.xwd /tmp/shot.png   # 转 PNG（无 convert/netpbm 环境的替代）
```

- 验收交互态（hover 渐入等）需把鼠标悬停在目标控件上再截；纯静态形态 + `UiArchitectureGuardTest` 守护测试联合背书。
  - **XTEST 指针注入自造交互态**（已验证配方；🔴 勘误：`XWarpPointer` 在 XWayland 下只动 X 内部指针、不动真实光标，合成器按真实 Wayland 光标路由事件——python-xlib 的 `xtest.fake_input` 路线**不可靠（误报）**。**可用配方 = ctypes 直调 libXtst**：`XTestFakeMotionEvent`（motion 可穿透到真实光标）+ `XTestFakeButtonEvent`（含滚轮 Button4/5）+ `XTestFakeKeyEvent`（键盘走焦点链不需指针））。窗口定位用 `xprop -root _NET_CLIENT_LIST` + `xprop -id <WID> _NET_WM_NAME` 找标题，`xwd -id <WID>` 截取；点击坐标 = 屏幕绝对坐标（`xwininfo -id <WID> -stats` 的 Absolute upper-left + 窗口内偏移）。⚠️ `pkill -f` 匹配串勿写进程名关键字裸串（会自匹配杀掉自己的 shell），用 `composeApp-jvm[.]jar` 括号技巧。
  - ⚠️ 指针拖拽到屏幕边缘会被钳制（合成指针停在屏幕边 1-4px）——拖拽/缩放验收测试别把指针压着屏幕边起步。
  - 🔴 **注入工具实现要点**（通道会环境性失效，重建后先验证死活）：ctypes 必须**全函数声明 argtypes**（64 位 dpy 指针截断=静默失败）；静止指针首事件会被丢弃（需插值预热若干步）。验证三件套 = argtypes 声明 + 预热移动 + `XQueryPointer` 前后对比；失效即停，转**真窗诊断 harness** 路线（jvmTest 一次性 `application{}` 真启动器 + 外部 xwd + PIL 像素程序化分析，jvmTest 开真窗需临时 ui-desktop 依赖）。
- 🔴 **排障（已实测）**：应用启动后窗口长时间不映射、`jcmd <pid> Thread.print` 见 `AWT-EventQueue-0` 卡在 `org.jetbrains.skia.FontMgr._nDefault` 且 100% CPU——根因是 `~/.cache/fontconfig` 陈旧缓存（部分国产发行版的 fontconfig 缓存兼容性问题）令 Skia 字体管理器初始化死循环；`rm -rf ~/.cache/fontconfig && fc-cache -f` 后重启应用即恢复。`fc-list` 秒回不代表缓存没坏（Skia 走的枚举路径不同）。
- ⚠️ AI 会话若 Read 本地图片文件返回空（视觉通道失效，会话级现象），改请用户**直接把截图贴进对话**（贴图通道独立且已验证可用）。
- 纯 Wayland 原生窗口（Wayland 合成器自身界面）截不了；对验收本应用（XWayland 客户端）无影响。

## 知识库（docs/）读取策略

`docs/index.md` 是总索引。**动手前先按任务类型读对应文档，不要盲目遍历源码**：

| 任务类型 | 先读 |
|---------|------|
| 新增/修改页面、组件、样式 | [docs/ui-style-guide.md](docs/ui-style-guide.md)，令牌细节以 [docs/core/theme.md](docs/core/theme.md) 为准（冲突时 theme.md 优先） |
| 执行 shell 命令 / 改 Shell 工具页 | [docs/pages/shell-tools.md](docs/pages/shell-tools.md) + [docs/core/shell.md](docs/core/shell.md) |
| 读写持久化配置 / 新增设置项 | [docs/core/data.md](docs/core/data.md) + [docs/core/viewmodel.md](docs/core/viewmodel.md) |
| 新增导航页面 | [docs/pages/navigation.md](docs/pages/navigation.md) |
| 修改 shell_tools.json 配置格式 | [docs/shell_tools.md](docs/shell_tools.md) |
| 理解启动流程 / 模块依赖 / 全局约定 | [docs/architecture.md](docs/architecture.md) |
| 日志系统（JLog） | [docs/core/log.md](docs/core/log.md) |
| 改设备域 / 改内置 adb 分发 / 加设备内核 | [docs/core/device.md](docs/core/device.md) |

### 知识库的已知边界（最后核对日期见 docs/index.md）

以下内容**尚无专篇文档**，涉及时需直接读源码（欠账清单的唯一真源在 [docs/index.md](docs/index.md)「已知欠账」小节，补齐后即时更新）：

- `:log-tools` 模块**已有专篇** [docs/log-tools.md](docs/log-tools.md)（`com/jiaoay/xlog/` 编解码管线、`com/jiaoay/log/` 过滤与分割、CLI）；用户功能见上文「功能清单」
- `page/xlog/`、`page/logtag/`、`page/filesplit/`、`page/timestamp/` 四页面**用户手册已有专篇**（`docs/manual/` 的 xlog-decode.md / tag-filter.md / file-splitter.md / timestamp.md）；代码级专篇（VM/引擎细节）仍无——涉及施工读源码
- `entity/JResult`、`entity/JErrorCode`、`core/initializer/` **已有专篇** [docs/core/entities-and-initializer.md](docs/core/entities-and-initializer.md)；`core/data/FileUtils.kt` 在 [docs/core/data.md](docs/core/data.md) 数据根一节；`widget/TonalCard.kt` 在 [docs/core/theme.md](docs/core/theme.md)；`page/home/ToolPageRegistry.kt` 在 [docs/pages/navigation.md](docs/pages/navigation.md) 页面注册节
- 🔴 飞书桥接域**已有专篇**：用户视角见 [docs/pages/lark-bridge.md](docs/pages/lark-bridge.md)，施工规格见 `openspec/changes/archive/2026-09-10-rewrite-lark-bridge/` 三份文档（`behavior-contract.md` / `config-domain-spec.md` / `gateway-contract.md`）；桥接内核 `:lark-bridge` 的管线/handler 细节仍以源码 + behavior-contract 为准。

> 知识库内容可能滞后于代码。文档与源码冲突时，以源码为准，并更新知识库（本仓库提供 docs-sync skill 的，优先走 skill；没有 skill 机制的助手直接按上述规范更新 docs/）。

## 关键约定

1. **Shell 执行禁用 `ProcessBuilder` 直接调用**——统一走 `SimpleShell` / `StreamingShell` / `InteractiveShell`（`core/extensions/`）。（约定，无守护测试，人工遵守）
   - 🔴 **例外（飞书桥接域）**：agent 子进程与 MCP stdio 一律走 `:lark-bridge` 的 `exec/ProgramLauncher`（argv 程序调用语义 + `Charsets.UTF_8`），**不走** `SimpleShell`/`StreamingShell`/`InteractiveShell`——后者是 shell 命令行语义（必经 `ShellCommandBuilder.wrap` 走 `cmd.exe /c` 或 `/bin/zsh -l`），且 `processCharset` 在 Windows 是 ANSI，与 claude stream-json 的 UTF-8 冲突。lib 侧 `ProcessBuilder` 唯一出现点由守护测试钉死在 `exec/ProgramLauncher.kt`。
   - 🔴 **例外（只读诊断域，sys.\*）**：`sys.probe` 白名单只读命令目录经 `:core` 的 `system/ArgvProcessRunner`（argv 纯语义：无 shell、argv 数组直传；执行件内提权/包管理黑名单恒拒 + `systemctl`/`journalctl` 恒 `--user` 硬校验，看门狗 + 进程树级联终止）。core 侧 `ProcessBuilder` **合法点集**由 `CoreProcessBuilderAllowlistGuardTest`（`:core`，`src/jvmTest/.../system/`）钉死：`core/extensions/` 四件（shell 语义收口件）+ `media/AudioTranscoder.kt`（ffmpeg argv 直调先例）+ `system/ArgvProcessRunner.kt`（诊断域唯一执行件，`core/llm/tools/` 内零 `ProcessBuilder`）——新增合法点须**先**在守护测试白名单登记理由再落生产代码。
2. **持久化统一经 `:core` 的 `AppDataStore` 进程唯一实例 + `loadEnum`**，不要自己 new DataStore：宿主域 key 走 `EasyDataManager`，feature 域 key 走各自模块的 ConfigStore（`LogKitConfigStore` / `TimestampConfigStore` / `ShellToolsDataStore`），明细见 [docs/core/data.md](docs/core/data.md)。（由 `DataStoreSingletonGuardTest`（`:composeApp`，`src/jvmTest/.../arch/`）守护——归一化扫描全仓生产源码的 `PreferenceDataStoreFactory`/`DataStoreFactory` 构造点（含 import），白名单唯一合法点 = `core/data/LocalDataManager.kt`（AppDataStore 单例底层，写路径密钥硬化挂钩所在）；新增合法构造点须**先**在测试白名单登记理由再落生产代码）
   - 🔴 **例外（飞书桥接域）**：桥接配置走**外部单一 JSON 文档** `~/.toolbelt/lark/bridge-config.json`（带 schemaVersion + 校验 + 迁移），由 `:lark-bridge` 的 `ConfigRepository` 管理，**不入 DataStore**；DataStore 里只保留一个一次性迁移标记 `lark_bridge_legacy_imported`（T15 旧 35 key 导入的幂等**辅助标记**，**非短路闸门**——F-8 收窄后：主闸门是 `bridge-config.json` 是否存在，有旧数据时无论标记是否置位都执行迁移，标记只用于「确认无旧数据可迁后避免每次启动重复读 DataStore」；删掉 `bridge-config.json` 后重启即会重新迁移，若想彻底从零开始需同时清掉旧 DataStore 里的 `lark_bridge_*` 键，口径同下文 CLI 节）。理由：lib 是 jvmTarget 11 纯 JVM，不能依赖 `:core` 的 DataStore；且配置需可被 CLI 与 App 共享、可手工编辑、可导出。
3. **UI 令牌强制**：间距/圆角/透明度用 `core/theme/` 的 `Dimens`/`AppShapes`/`AppAlpha`，动效一律走 `AppMotion` 工厂（`fast()`/`standard()`/`slow()`/`staggered()`/`softSpring()`，时长档位见 `MotionDurations`；裸写 `tween(`/`spring(` 仅 `Motion.kt` 内白名单）——裸 dp、裸 alpha、Material Shapes、elevated card、`animateScrollToItem` 会被 `UiArchitectureGuardTest` 拦截（例外必须先登记 `docs/core/theme.md` 的「有据保留」清单再入守护白名单）。🔴 飞书桥接页（`:feature-larkbridge`）达成**零白名单条目**：所需尺寸全部走新增 token（`consoleHeightMin`/`consoleHeightMax`/`strokeThin`/`progressInline`），无一进入白名单。
4. **卡片统一 `AppCardStyles.tonal()`** / `widget/TonalCard`。（`ElevatedCard` 部分由守护测试拦截，配方统一为人工约定）
5. **依赖方向 page → core 单向**，页面骨架用 `widget/PageScaffold` + `PageHeader`。（约定，无守护测试，人工遵守）
6. **JSON 用 kotlinx.serialization**（已移除 Gson）。（约定，无守护测试，人工遵守）
   - 🔴 **例外（飞书桥接域）**：官方 `com.larksuite.oapi:oapi-sdk` **传递引入 Gson**，无法移除。隔离边界 = `:lark-gateway`（SDK 类型只出现在此模块）。🔴 **shadowJar 禁止 relocate Gson/protobuf**（SDK 内部反射依赖原始包名，relocate 会破坏其反序列化）。桥接域自身的配置文档仍用 kotlinx.serialization（`BridgeJson`）。
7. **日志统一 `JLog.d(TAG) { ... }`** 形式（惰性求值）。（约定，无守护测试，人工遵守）
8. 🔴 **飞书 SDK 只允许出现在 `:lark-gateway`**：`com.lark.oapi.*` 的任何 import / 类型引用 / 构造都不得泄漏到 `:lark-bridge`、`:feature-larkbridge`、`:composeApp` 等模块，由 `LarkSdkIsolationTest`（`:composeApp`，`src/jvmTest/.../arch/`）守护（源码归一化扫描 + 反射成员核对）。`core/.../LogbackHelper.kt` 里的 `getLogger("com.lark.oapi")` 是 **logback logger 名字符串字面量**（用途：压制 SDK 日志噪音），非 import 非类型引用，SDK 缺席时无副作用——守护测试的归一化会剥字符串字面量，故不误报（豁免说明见 `docs/architecture.md`）。
9. 🔴 **注释宪法**（由 `CommentGuardTest`（`:composeApp`，`src/jvmTest/.../arch/`）守护）：源码注释只写三类内容，其余不写——
   - **为什么**：取舍/坑/防误改契约（代码能自说明的不写）；
   - **契约/不变量**：null 与返回值语义、线程模型、异常条件、硬限制数值；
   - **指引与登记**：指向 `docs/` 知识库或仓库内真实文件的锚（唯一被鼓励的跨文件引用）；`// OPEN(主题): 待验证描述` 形式的待办登记。指向仓库内真实归档（`openspec/changes/archive/` 等）的「§N.M」规格锚与工单令牌属合法指引，但**令牌须附可解析的真实路径**——仓内唯一文件名 + 节号即可解析（如 `gateway-contract.md §15.2 M-16`、`design-doc.md §5.3#4`、`ui-style-guide.md §4.7h`）；检索不到出处的令牌不得保留，删令牌留契约正文。
   **公共 API 契约优先写 KDoc**——守护测试对 KDoc 与普通注释实施区别对待（KDoc 内 `[符号]` 链接受悬空校验），公共契约以 KDoc 为第一载体。
   **禁止**（守护机械化拦截，白名单为计数棘轮——只许降不许升）：历史叙事（批次号 P0-P5/M?-T??/WB-??/DS-??/DOC-??/「批 N」/「下线批」/「重设计批」/「终验」、日期 2026-xx-xx、「旧实现/新实现」「主人拍板」「蜂群」「对抗评审」「决策 N」「勘误」）、悬空 KDoc `[链接]`（目标符号不存在）、注释掉的声明级代码、复述代码、引用 `*.html` 设计稿/仓库外稿面（`MOCKUP_HTML_REF` 零容忍——稿面出处登记进 docs/，注释写「值 + 单位 + 依据」自足契约句）。🔴 只标「违反即出错/数据损坏/不可逆」的真门禁，不加普通强调；🔴 注释计数由 `CommentGuardTest` 棘轮守护，只降不升。历史归 commit message，决策知识归 docs/，不进源码；注释与代码矛盾时以代码为准并同 commit 改注释；注释被守护测试 raw 依赖时先改测试锚后清注释。政策与棘轮 baseline 总数见 `docs/architecture.md`「注释治理」节。
10. 🔴 **agent 工具面文件入参围栏**（由 `ToolFenceCoverageGuardTest`（`:composeApp`，`src/jvmTest/.../arch/`）守护）：`core/llm/tools/` 的每个 `StructuredSimpleTool` 凡 Args 含路径形参（属性名 = `path` 或以 `_path` 结尾），类体内必须调用 `ToolExecutionContext.authorizeWithGate` 围栏校验（LLM 路径入参不可信——幻觉路径/文件内容注入，漏调即围栏静默失效）；无文件入参/围栏外合法形态的工具族必须在测试白名单登记理由（**先改测试锚再落生产代码**）；`StructuredSimpleTool` 子类只允许出现在 `core/llm/tools/`（工具面收口，逃出即双失明——围栏与结构化纪律扫描面都在此目录）。

## 功能清单（用户视角）

- **日志工作台（家页）**：AI 研判日志（分析输入 → 证据流 → 分析报告）；分析报告可导出为可分享 Markdown（落 `~/Documents/Toolbelt/reports/`）、一键送飞书（文件消息直发桥接接收会话）、打开报告文件
- **日志分析**：XLog 解密（`.xlog`/`.alog` → txt，拖入目录可递归枚举批量入卡）/ Tag 正则过滤日志块（正则预演 + 看分布，目录拖入批量）/ 按起止时刻截取时间区间日志段（产物落原文件旁）/ 大文件按 MB 分割 / 两份日志 diff 对比（时间戳归一化对齐）/ 大文件日志查看器（应用内查看 txt 日志：行号/时间戳/级别跳转、文本搜索、行文本选中复制、深度分析直投 AI 研判、送 Tag 过滤与送时间截取管线衔接；打开失败的加密件一键送 XLog 解密）；各工具任务卡的成功产物右键可查看/深度分析/AI 整理/送下一工具/送飞书
- **压缩包解压**：拖入（支持多选）zip / tar / tar.gz / tgz / gz / zip 分卷（`.zip.001…`）/ PKZip 分卷（`.z01…`）/ 7z（含 `.7z.001` 分卷）解到同级 `_extracted` 目录；解压产物一键送 XLog 解密（产物目录内单个加密日志直投命令面板、多个跳页批量入卡）
- **Shell 工具**：adb/shell 快捷指令一键执行，支持自定义命令（4 类）、设备检测与多设备选择、执行历史回放
- **设备观测**（dock 第六空间，Ctrl+6，四个子页）：**观测台**=logcat 实时抓取落盘（tag/级别过滤、抓取中可改、可「标记」复现点/武装捕获，停止后「查看」带锚点直达，产物送 Tag 过滤/工作台/飞书——引擎/VM/面板在 `:feature-logcat`）+ **投屏**（单行入口点击直达独立投屏窗——默认普通窗、顶栏图钉钮可切恒置顶与主窗并用，关窗即停投；窗内多指手势：Ctrl+滚轮离散捏合/Ctrl+拖对称双指捏合+旋转/Shift+拖双指平移/右键泊指（≤3 指常驻触摸）/画质档位弹层/快照（可送飞书）/录屏/设备键排与文本注入——引擎/VM/面板/独立窗在 `:feature-screencast`，真窗渲染诊断通道 `./gradlew :feature-screencast:realWindowDiag`）+ 性能采样 + 按键遥控四面板 + 复现记录编排行（logcat/性能/投屏一键同起同停）；**文件**子页=设备文件浏览（拉取到 ~/Downloads、增量 .part 校验）；**应用**子页=设备应用面板（应用列表/详情/快照对比）；**布局**子页=窗口布局检查器（dvwv 全窗口视图层级树 + 左舱全层级预览图——类 AS Layout Inspector 形态：节点真实背景色/类族洗底 + a11y 源画文本 + 标签贪心避让 + isDrawn 绘制面开关 + clipChildren 命中收窄；隐藏节点默认不画、选中/hover 显形；预览节点双击进子树（树头栈式返回），点击预览节点展开祖先路径定位到树，详情独立右列展示（Wide/Medium 三栏，Compact 树内嵌） + 选节点点亮投屏画面）——设备检测态消费 `:core` 设备域单源（与 Shell 页共享），设备功能聚合在 `DeviceHub`
- **小工具与集成**：时间戳（双向转换 / 当前时间秒·毫秒·日志格式三态常显 / 两时刻区间差值（毫秒 + 人性化）/ 多行批量转换逐行对齐）；**飞书桥接**（已推倒重写：官方 SDK 长连接 + 单一 JSON 配置文档 + Compose 编辑器，旧 CLI 双通道/OpenAPI 架构已整体消失）
- 另有**设置页**（外观/配色/Shell 类型/ADB 路径/日志存储/开发者选项）与隐藏的 **Shell 调试页**（开发者选项开启后可见）——这两者不属于工具分组，入口固定在导航底部

### 库模块 CLI（log-tools CLI 专篇见 [docs/log-tools.md](docs/log-tools.md)「独立 CLI」节——各 type 旗标语义/退出码/判型路由的完整契约；lark-bridge CLI 已载入 docs/pages/lark-bridge.md 的「独立 CLI」节。此处仅留速查）

- **log-tools CLI**：`java -jar log-tools-all.jar --type <xlog|filter_by_tag|filter_by_time|filter_by_range|split_by_size|pipeline|diff|stats> -f <文件>`（入口 `com.jiaoay.log.MainKt`；`xlog` 需 `-k <私钥>`、`filter_by_tag` 需 `--tagRegex/--tagName`、`filter_by_time` 需 `--targetTime/--range`、`filter_by_range` 需 `--startTime/--endTime`（起止区间截取，完整时间戳口径与 UI 侧 tools.slice-time 一致）、`split_by_size` 需 `--size`、`diff` 需 `--other <对比文件>`（可选 `--normalizeTimestamps` 时间戳归一化对齐）、`stats` 仅需 `-f`（纯统计输出，无产物文件），`pipeline` 详见专篇）
- **lark-bridge CLI**：`java -jar lark-bridge-all.jar <serve|check|send|handle-event|permission-server>`（入口 `com.jiaoay.lark.bridge.MainKt`），配置读单一 JSON 文档 `~/.toolbelt/lark/bridge-config.json`。
  - 🔴 **breaking change（重写后）**：旧 CLI 的 **30 个业务旗标已全部删除**（如 `--app-id`/`--agent-prefix`/`--static-reply` 等），配置改由**单一 JSON 文档** + `--set <点路径>=<值>` 承载。🔴 **`--set` 为 ephemeral 语义、永不持久化**：只在本进程内存生效，任何情况都不写 `bridge-config.json`（App UI 编辑器保存才落盘）。旧「靠旗标传配置」的纯 CLI 用户需迁移到编辑 JSON 文档；旧 DataStore 的 35 个 key 由**首次打开飞书桥接页时**经 T15 `LegacyConfigImporter` 后台异步一次性导入（**不阻塞 App 启动**；因首页 `AnimatedContent` 只组合当前页，迁移推迟到首次进入桥接页那一刻，而非 App 一启动就迁移；纯 CLI 用户 jvmTarget 11 访问不到 `:core` 的 DataStore，不触发迁移，需自建 `bridge-config.json`）（`bridge-config.json` 已存在则跳过；重新导入 = 删该文件后重启 App。若想彻底从零开始（不要旧设置），需同时清掉旧 DataStore 里的 `lark_bridge_*` 键，否则重启会再次迁移回旧值）。

## 测试

- 测试框架：`kotlin("test")`
- UI 架构守护：`composeApp/src/jvmTest/.../arch/UiArchitectureGuardTest`（飞书桥接页零白名单条目）
- 🔴 SDK 隔离守护：`composeApp/src/jvmTest/.../arch/LarkSdkIsolationTest`（钉死 `com.lark.oapi.*` 只在 `:lark-gateway`）
- 🔴 桥接装配唯一性守护：`lark-bridge/src/test/.../BridgeAssemblerParityTest`（钉死全仓唯一装配点 + 唯一 `SdkLarkGateway` 构造点）
- 🔴 注释宪法守护：`composeApp/src/jvmTest/.../arch/CommentGuardTest`（拦历史叙事/悬空链接/注释掉的声明/`*.html` 稿面引用；白名单 = 计数棘轮，见「关键约定」第 9 条）
- 🔴 agent 工具面围栏覆盖守护：`composeApp/src/jvmTest/.../arch/ToolFenceCoverageGuardTest`（路径形参工具必调 `authorizeWithGate` + 全工具分类白名单 + 子类只许出现在 `core/llm/tools`，见「关键约定」第 10 条）
- 🔴 撞面守护（agent 工具目录）：`composeApp/src/jvmTest/.../arch/ToolCatalogDisciplineTest`（工具 name 全目录唯一 + 别名不撞其他工具实名/别名 + params id 列表唯一 + 目录外补挂件与 name 集互斥——撞面全部静默失效零报错；新增 agent 工具/别名/带参工具时核对断言面）
- 🔴 撞面守护（palette 带参命令）：`composeApp/src/jvmTest/.../page/home/ParamCommandCatalogTest`（全量命令面 id 唯一 + 标签/描述/提示文案非空白；新增 palette 带参命令时核对断言面）
- 🔴 撞面守护（页面注册）：`composeApp/src/jvmTest/.../page/home/ToolPageWiringGuardTest`（全量注册表 page 值唯一——撞 page = 消费面静默蒸发；新增页面注册时核对断言面。同守护另钉死**一次性消费槽双 effect 守护族**五面——ToolsPage/AgentCenterPage 预选槽、LogWorkbenchPage 深链、CommandPalette 衔接 seed、KbManagePanelContent 在签，配方与守护锚统一引用见 [docs/pages/navigation.md](docs/pages/navigation.md)「一次性消费槽与页面驻留」节；新造 pending+consume 槽时按册消费并核对断言面）
- 🔴 存储路径字面量守护：`composeApp/src/jvmTest/.../arch/StoragePathLiteralGuardTest`（三组：旧根名 `easy_dev_toolbox` 字面量白名单 = FileUtils 迁移常量 / AppDataStore 旧 DataStore 名 / SensitiveFilePolicy 出站打码旧形态，新增合法位点须**先**在 `legacyRootLiteralWhitelist` 登记理由再落代码；`"Toolbelt"` 不得出现在路径构造上下文（展示串不受限）；数据根子目录禁止 `File(getAppDataDir(), "字面量")` 直拼——新子目录先在 FileUtils 落访问器）
- 🔴 桥接 CLI/App 同根契约：`composeApp/src/jvmTest/.../arch/LarkCliDataRootParityTest`（`:lark-bridge` CLI 缺省根字面量与 `FileUtils.getLarkDir()` 派生路径全等——lib 不得依赖 `:core` 的两处独立拼写，单方漂移即红；CliSupport 构造形态改变时先改本测试正则）
- 🔴 ReportDelivery 消费纪律守护：`composeApp/src/jvmTest/.../arch/ReportDeliveryDisciplineTest`（全仓生产源集 `ReportDelivery.deliver(` 位点计数棘轮锚 = 7——`deliver` 的 null = 已送出成功语义，消费面恒「先收 reason 再判空」；新消费面不入锚即红、位点消失须降锚不许静默漂移；elvis 反模式 `deliver(...) ?: 失败提示` 全仓零容忍（会把成功吞成失败）；新增/删除送飞书消费面时改 `expectedDeliverSites` 锚并登记理由）
- 改动 UI 后必须跑 `./gradlew :composeApp:jvmTest`；改动桥接内核后跑 `./gradlew :lark-bridge:test :lark-gateway:test :lark-ws:test :log-tools:test :feature-larkbridge:jvmTest`（`:lark-ws`/`:log-tools` 均在桥接依赖链上——入站恒自研与引用文件解密，须一并回归）
- 🔴 扫盘型守护测试（源码扫描）会被 build cache 假绿，验收一律用 `--rerun-tasks --no-build-cache`
- 🔴 发布前置测试门禁：`.github/workflows/release.yml` 的 `test-gate` job——tag 发布前 linux 全模块 `jvmTest test --rerun-tasks --no-build-cache`（任务名双写：KMP 模块用 jvmTest、纯 JVM lib 用 test，只写 test 会空跑），任一红阻断四平台打包；暂 `-x :feature-logworkbench:jvmTest`（该模块两个用例依赖本机未入 git 的概念稿/本地 db，CI 必红），环境依赖收口后移除排除恢复全量
- test-retry 插件（`org.gradle.test-retry`，convention 统一配置）：仅 CI 环境变量在场时 `maxRetries=2`（本地恒 0，不污染内环反馈）；重试转绿不 fail 构建，XML 逐次离散记录可作 flaky 测量起点——官方立场：重试只是缓解，必须与已知良性红登记/归因流程并行
- 测试结果 XML 消费前先跑 `python3 scripts/check_test_results.py`（mtime 新鲜度机械校验 + 只读汇总；陈旧/部分在盘快照当验收依据是已实锤的假绿通道；退出码 2 = 没找到任何 XML = 任务可能根本没跑）

### 已知 flaky 与良性红登记（施工红先查表，命中即按配方归因/绕行，不进施工预算）

- **DeviceObsSubpageSceneGuardTest**（composeApp 观测台两用例）：负载敏感 flake——全套 jvmTest 下随机红、单跑/整包恒绿，「死代码也能红」（2026-10-19 实锤：曾误归因 PerfPanel FlowRow 改动并回退无辜代码，同配置复跑证伪）；机器 swap 高压时与「内存近枯类加载级联漂移红」同族。处置配方：单跑 + 同配置复跑归因，未完成归因禁止回退代码。
- **feature-logworkbench:jvmTest** 两个环境性预存失败（glossary 词表缺未入 git 文件 / 本地 registry db 为空）：复跑核对失败因 = 外部依赖缺失即按环境失败放行，勿修。
- **ScreencastWindowDropPushTest**（feature-screencast）：ScreencastEngine 会话协程逃逸测试 dispatcher 的跨测试污染，负载/时序敏感；失败名与真因无关，复跑绿即按 flake 处理。
- **Kotlin daemon 并发冲突**：测试红而零错误输出 = 复跑即绿；多会话并发跑测试须串行。
- 历史已收口的假红族（复发即按上述归因协议处理）：跨 JVM 条件性假红——「真实目录零增长」类断言曾被其他模块测试跨 JVM 新建文件误伤，已由 eager `datastoreDir` 注入收口。

### 施工纪律（任务描述/任务单硬约束）

1. 环境敏感红先归因（单跑 + 同配置复跑），**未完成归因禁止回退代码**。
2. 排查/侦察产出的机制论断（布局语义、时序、并发行为类）**必须实证**（无头渲染双盲对照、复现实验等）后才可作为修复依据。
3. 注释与代码矛盾时**以代码为准**（审计/走查同执行此纪律）；采信任何注释断言前先以代码实测核对。
4. 命中上表已知良性红即**绕行不修**，修复从功能批剥离为独立微批。
