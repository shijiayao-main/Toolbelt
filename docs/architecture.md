# 整体架构

## 启动流程

```
main() [main.kt]
  └── application { Window(state, icon, undecorated=true, transparent=true, resizable=false) { App() } }
        ├── WindowState：默认 1280×860、居中、最小 960×640、窗口图标 Res.drawable.app
        ├── 启动同步预读（runBlocking ×2，首组合求值前就绪）：
        │     readSavedWindowGeometry()（窗口几何记忆）+ readUiPreloadState()（主题/深浅/
        │     玻璃双开关/恢复页——🔴「启动亮色闪图」修复 2026-09-25：异步恢复前首帧画
        │     硬编码默认值 = 亮色闪图，预载后首帧即保存值）
        ├── CompositionLocalProvider(LocalAppWindowScope)  ← WindowScope 下发（AppTitleBar 消费）
        ├── remember { JInitializerManager.startInit() }   ← 一次性启动初始化
        └── App() [App.kt]
              ├── AppViewModel.getViewModel(uiPreload)   ← 创建/复用全局 ViewModel（预载种子化）
              ├── AppTheme(colorScheme, themeMode, windowShape) ← 深浅主题装配 + 语义色 Local
              │     + 窗口根裁剪（透明圆角窗：AppShapes.glassPanel clip + strokeThin/borderSubtle
              │     定缘；WindowPlacement.Maximized 时 windowShape=null 回正满屏）
              ├── Column { AppTitleBar(自绘标题栏：三控制圆点+动态窗标题+冻结快照拖拽+双击最大化)
              │             └── Box(weight) ─ initState 三态：null → Loading / Failed → 失败列表+退出按钮 / Success → PageHome }
              ├── host/UndecoratedResizeOverlay（八向缩放边条，resizable=false 关内建 resizer；
              │     冻结快照算法单次原子 setBounds，松手即停三重 break）
              └── 窗口标题双写：AWT window.title（任务栏）+ AppTitleBar 文本（同源 windowTitle remember）
```

> 初始化域细节（JInitializer 注册方式 / 高优并发阻断·低优串行错峰执行模型 / `InitResult` 三态与 `initStateFlow` 分阶段发射时序）见 [core/entities-and-initializer.md](core/entities-and-initializer.md)。

> ⚠️ **窗口形态经维护者四轮真机 A/B 裁定（2026-09-25）**：无边框（应用驱动 setSize，内建 resizer 剧烈抖动/自绘冻结快照版单调）vs 系统标题栏（WM 平滑但 XAWT 双窗结构左/上缘「内容先移后宽」+ XWayland stretch）——**都抖，维护者裁定无边框观感优先**。EWMH `_NET_WM_MOVERESIZE` 原生接管被本机 WM 无视（连原生 X 窗都无视）。完整证据链见 docs/core/theme.md「窗口形态终态」节。

退出清理：`DisposableEffect.onDispose` 收口进 `AppHost.shutdown`（全应用唯一退出序，调用点在 main.kt）：`participants.shutdown → scope.cancel → bridgeExit → hostTeardown`——`bridgeExit` = `LarkBridgeManager.onAppExit()`（桥接优雅关闭的宿主入口，非 suspend，内部含唯一一层 `runBlocking`），`hostTeardown` = `LlmClientFactory.invalidate()`（AI executor 缓存收口）后再 `LogbackHelper.onDestroy()`。顺序理由：桥接先停再关日志，否则退出期的桥接日志无处落。`onAppExit()` 首行 `bridgeRef.getAndSet(null) ?: return`——桥接从未装配/未启动时**立即返回**，不使用桥接功能的用户关窗不受影响。退出序契约详见下文「AppHost 与能力四态」节。

> ✅ **关窗 EDT 阻塞上限 ≤ 约 1.5 秒**（`APP_EXIT_EDT_BUDGET_MILLIS = 1500ms`，`Bridge.onAppExit()` 的 N-2 修复；旧实现在 EDT 上串行 `close()` 最坏冻结约 18 秒）：`onAppExit()` 改为三段式——① **数据安全关键步「⑧ 配置 `flushNow()`」仍在 EDT 上同步先做**（几十 ms，绝不后台化、绝不经预算截断 ⇒ 不会丢配置）；② 优雅关闭的慢收尾（② join 在途任务 `GRACE_MILLIS=5000ms` / ④ 关闭开放卡片流 / ⑤ `disconnect` / ⑥ `gateway.close`，各带超时预算，最坏合计约 18 秒）整体挪到**非守护后台线程 `lark-bridge-exit`** 上继续跑完——慢步骤是被**移到后台**而非被压缩，该线程保 JVM 存活至收尾完成后才退出（有界终止，不会永久挂住）；③ EDT 侧只 `Thread.join(APP_EXIT_EDT_BUDGET_MILLIS)` 等它，到点即返回让窗口关闭。桥接从未装配/未启动时宿主 `LarkBridgeManager.onAppExit()` 首行 `bridgeRef.getAndSet(null) ?: return` 立即返回（见上）⇒ **不使用桥接功能的用户关窗不受任何影响**。

---

## 模块依赖关系

```
App
 └── PageHome（调查工作台 S2 五舱形态：dock + SpaceBar + WorkspaceTabs + inspector + 状态栏）
       ├── PageHomeMenu        dock 降级形态（静态图标集：家/会话/设备/桥接/命令入口）
       ├── SpaceBar            调查空间栏（SpaceStore/spaces 表——文件/会话/产物聚合）
       ├── WorkspaceTabs       主区 tab 容器（tab 条 + PageHomeContent AnimatedContent 分发）
       │     └── PageHomeContent 按 currentPage 渲染对应注册页
       │           ├── PageSettings    → AppViewModel（主题模式/配色/Shell/日志/调试开关）
       │           ├── XLogDecoderPage → XLogDecoderViewModel → :log-tools LogDecode（palette 深入模式可达）
       │           ├── LogTagFilterPage → LogTagFilterViewModel → :log-tools LogTagFilter（同上）
       │           ├── FileSplitterPage → FileSplitterViewModel → :log-tools LogSplitter（同上）
       │           ├── ShellToolsPage  → ShellToolsViewModel → ShellHistoryManager + CustomToolsController + ShellToolsRuntime.engine
       │           ├── AiSettingsPage  → AiSettingsViewModel → LlmConfigStore + LlmClientFactory（OpenAI 兼容端点 + 记忆面板）
       │           ├── LarkBridgePage  → LarkBridgeViewModel → LarkBridgeManager（宿主门面）→ :lark-bridge Bridge（配置编辑器 + 运行状态 + 日志台 + 诊断操作；tab 容器成员）
       │           └── ShellDebugPage  → ShellDebugViewModel → SimpleShell / InteractiveShell [调试入口]
       ├── AppStatusBar         只读状态栏（面包屑/后台活动/桥接连接/AI 开关）
       └── CommandPalette       Ctrl+K 命令面板（S3 升格：带参命令 + HUD + 空态智能）

Gradle 模块：
:composeApp ──> :log-tools（纯 JVM lib，XLog 解码/日志工具 + CLI）
:composeApp ──> :feature-larkbridge ──> :lark-bridge ──> :lark-gateway ──> oapi-sdk
  （飞书桥接链；:lark-bridge 是 jvmTarget 11 纯 JVM lib，不得反向依赖 :core(jvmTarget 25)；
    SDK 类型 com.lark.oapi.* 只允许出现在 :lark-gateway，由 LarkSdkIsolationTest 守护）
:feature-larkbridge ──> :core（core/llm 端点域 + core/llm/tools 围栏与工具族，
  飞书桥接调度 agent 与 AI 域共用——万能解密页已于 2026-09-19 P4-C1 移除，FileSniffer/
  DecryptTools/SessionStore 家族等平台资产随批下沉 :core；工作区域工具本体
  WsWriteTools/WsRunTaskTools/GitReadTools 与 HITL 提问契约 AskUserTool/AskUserModels、
  WsSessionGate 同在此层（桥接侧只放门控实现，见 :feature-larkbridge koog/KoogWsGates.kt）；
  UiGuard feature 隔离 ⇒ 共用件归 :core）──> :log-tools（api 传递）
:lark-gateway ──> :lark-ws（自研飞书长连接客户端，jvmTarget 11，JDK HttpClient/WebSocket，零 SDK 零第三方网络库；
  下线批 2026-09-12 起**入站恒自研**——WsInboundTransportFactory 必传构造参数（生产 WsClientTransportFactory），
  SDK websocket 入站路径已删除、SDK 侧恒 transport("webhook")（防双长连接互踢），只留出站/identity facade；
  详见 docs/pages/lark-bridge.md「自研长连接客户端」节）
:composeApp ──> :feature-ai ──> :log-tools
  （AI 能力域：AI 设置（2026-09-19 P4-C1 起万能解密页已移除——fs.* 工具族/AgentToolCatalog/
    KoogToolLoop/SessionStore 家族/memory 域全部下沉 :core 休眠待工作台复用）。
    Koog 消费实况为四模块（真源 = :core 的 api 传递；各消费模块编译期直接 import 的
    artifact 按需显式声明）：:core api 四件（agents-core / http-client-java /
    agents-features-event-handler / agents-features-memory）；:feature-larkbridge
    显式五件（agents-features-memory / agents-features-event-handler /
    embeddings-llm / rag-vector / rag-vector-jvm）；:feature-ai 显式三件
    （agents-core / http-client-java / agents-features-event-handler）；
    :test-fixtures compileOnly 一件（agents-core，见下——唯一编译期例外）。
    🔴 Koog 字节码是 Java 17 ⇒ 运行时只进宿主侧 jvmTarget 25 模块，不得进
    :lark-bridge / :log-tools；唯一例外 = :test-fixtures 的 compileOnly 编译期
    接触、零运行时渗透）
:test-support（跨模块测试支撑件，2026-10-13 P176 新建；jvmTarget 25 宿主侧模块）
  （承载 SceneGuardSupport——ImageComposeScene 像素级场景守护**渲染底座/配方真源**
    （renderPixels / contentRows / 场景泵形态；配方要点全为实测取舍）：新增像素守护
    必须 import 本文件单源消费，测试类内私有复刻配方由 SceneGuardCopyRatchetTest
    棘轮守护（全仓零豁免）。包名恒 com.jiaoay.easy_dev_toolbox.arch——composeApp 侧
    arch 守护测试同包免 import 消费；模块自身 jvmMain 源集形态，消费方 jvmTest 声明
    testImplementation(project(":test-support"))（composeApp 与 :feature-* 测试源集））
:composeApp ──> :feature-mcp ──> :core（+ MCP Kotlin SDK 0.15.0 / Ktor server 3.5.1）
  （MCP 外放域，2026-09-20 P5 mcp-expose：本地工具与 agent 能力经 MCP streamable HTTP
    （127.0.0.1）递给外部 client——入口组第二成员；🔴 io.modelcontextprotocol.* / io.ktor.*
    类型只许出现在三装配层文件（McpServerManager/McpToolRegistrar/McpAgentTools），由
    McpSdkIsolationTest 守护；专篇见 docs/pages/mcp.md）
:composeApp ──> :feature-quest ──> :core
  （流程域 Quest，2026-09-20/21 六 change：agent 主动向用户索要信息的会话页——
    user.ask_user HITL 工具 + flows 表 + 能力目录（值同源自持）+ escapeGate 确认卡 +
    ChatMemory 双轨续聊（ask_user 工具/AskRequest 在 :core core/llm/tools）；
    智能体组第五成员；🔴 依赖铁律：零 feature 互依赖、QuestPrompts 零性格段；
    专篇见 docs/pages/quest.md）
:core ──> :device-core ──> :adb-core（设备域与内置 adb，均为 jvmTarget 11 纯 JVM lib）
  （:adb-core 包 com.jiaoay.toolbelt.adb：平台判定 / 端点解析（用户配置 > 随包内置 >
    系统 PATH）/ 二进制释放（resources → ~/.toolbelt/adb/v<版本>/）/ 命令重写；
    :device-core 包 com.jiaoay.toolbelt.device：设备检测与选定态、文件浏览/拉取/推送、
    APK 安装与包管理、布局与全窗口视图检查、性能采样、按键注入、无线连接。
    两个 lib 零宿主依赖——执行通道（AdbProcessRunner）、日志（AdbLog）、协程作用域、
    端点读取缝全部由调用方注入；宿主实现分别在 :core 的 SystemAdbProcessRunner 与
    DeviceHub。lib 换包名是硬要求：有 src/main/kotlin 的模块被 ScanSurfaceSelfCheckTest
    自动判定为 jvm11 lib，而 LarkSdkIsolationTest D 组禁 lib 引用宿主包前缀。
    专篇见 docs/core/device.md）
:test-fixtures（跨模块测试基建共享模块，2026-09-27 审计批新增）
  （纯 JVM、jvmTarget 11——全仓最低 common denominator，core/composeApp/feature
    域测试（25）与 lark-bridge 域测试（11）都消费得起；🔴 零生产模块依赖——只放
    无依赖测试工具，业务态 fixture 各测试自持。现承载 UserHomeIsolation
    （user.home 三件套样板单源，16 个测试文件消费）与 KoogFakeExecutor
    （koog-fake-executor-resolvedmodel 教训配方的 open 基座，KoogToolLoopTest/
    QuestSessionTest 继承）。消费方式：各模块 `kotlin.sourceSets["jvmTest"].
    dependencies { implementation(project(":test-fixtures")) }`；模块清单由
    LarkSdkIsolationTest.LIB_MODULES 守护登记（含 test-fixtures），新 lib 模块
    须同步该清单——ScanSurfaceSelfCheckTest 会拦。🔴 Koog 编译期例外登记：
    KoogFakeExecutor 基座 compileOnly 一件（agentsCore）——Koog 字节码是
    Java 17，本模块 jvmTarget 11 仅编译期接触；compileOnly 不进产物、消费方
    测试全在宿主侧 jvmTarget 25 模块运行 ⇒ 零运行时渗透）
```

```
核心依赖链：
各页面 ViewModel
  └── SimpleShell / StreamingShell / InteractiveShell
        └── ShellCommandBuilder   读取 shellType 决定调用哪个 shell
              └── ShellType       ZSH | BASH（由 AppViewModel 管理，存入 EasyDataManager）

全局状态：
AppViewModel
  └── EasyDataManager（泛型 read/write 收敛）
        └── LocalDataManager
              └── DataStore<Preferences>（文件：~/.toolbelt/.datastore/toolbelt.preferences_pb）
```

---

## Shell 工具配置与执行域（物理位于 feature-shelltools 模块 page/shelltools[/config]/）

包名 `com.jiaoay.easy_dev_toolbox.page.shelltools[.config]`（原 core/shelltools 域整体迁入 feature-shelltools；
`AdbCommandRewriter` 留在 :core 真包 `core/shelltools/`，StreamingShell 等 shell 执行器仍在 :core `core/extensions/`）。

```
page/shelltools（feature-shelltools 模块）
 ├── ShellToolsViewModel            聚合器；engine 经 get() 转发 ShellToolsRuntime（App 级）
 ├── ShellToolsConfigRepository     三源配置管道唯一出入口（默认资源 → 主配置 → 自定义追加）
 │     ├── 读：load() → LoadedShellTools(nodes + mainConfigError)；主配置只读
 │     ├── 写：upsertTool / deleteTool / createGroup / renameGroup / deleteGroup /
 │     │        moveTool / import / export —— 仅写 shell_tools_custom.json
 │     ├── 并发：单实例 Mutex 覆盖读-改-写全临界区；.tmp + rename 原子写
 │     └── 迁移：旧扁平结构归入默认分组；缺 id 补 UUID 立即回写（幂等）；首次迁移前 .bak
 ├── ShellToolsRuntime              App 级单例：CoroutineScope(SupervisorJob + Default) + engine
 ├── ShellExecutionEngine           串行队列 + 流式输出 + 状态机（执行状态脱离页面生命周期）
 ├── ShellExecModels                ExecStatus / ExecOutputItem / ExecOutputType
 ├── ShellToolsDataStore            Shell 域配置仓库（经 :core AppDataStore 进程唯一实例读写）
 ├── CustomToolsController          节点树状态 + 展开记忆；CRUD 转发 Repository（companion 持进程级共享单例）
 ├── UI（列表/编辑器/分组对话框/输出面板/历史面板）
 └── config/
       ├── ShellToolsConfig         JSON 1:1 映射模型（含可选 id）
       ├── ShellToolsConfigLoader   主配置加载（NotFound/Empty/ParseFailed 错误码）
       ├── ShellToolsConfigParser   JSON ↔ ToolNode 双向转换（整型 type 手动路由 0/1/2/3）
       ├── ShellToolsNode           UI 展示模型 ToolNode / ChildBody
       └── DefaultShellToolsConfigProvider  composeResources 默认资源读取（缓存 + 降级）
```

三源合并优先级：内置默认配置（composeResources/files/shell_tools_default.json，仅通用示例命令，新用户开箱可用）
→ 用户主配置（`~/.toolbelt/shell_tools.json` 存在且解析成功则**完整取代**默认；失败回退默认
并随结果下发错误提示）→ 自定义配置（`shell_tools_custom.json` 分组树追加尾部）。

---

## 页面路由（调查工作台 S7 终态 · 2026-09-22）

路由枚举 = 主区内容 key：`:core` 模块 `page/ToolPage.kt`（真身 `core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ToolPage.kt`，**20 值**，`Default` 欢迎页已删）。
**currentPage 语义自 S2 收窄**：不再是「14 页等权导航」，而是「主区 tab/页面
内容 key」——主消费面 = WorkspaceTabs tab 选中态 + PageHomeContent 内容分发。

| 形态 | 枚举值 | 页面 | 说明 |
|------|--------|------|------|
| 家 | `LogWorkbench` | `LogWorkbenchPage` | 启动落点；证据流视图宿主（`:feature-logworkbench`，块化证据流 + 研判，专篇见 [pages/log-workbench.md](pages/log-workbench.md)） |
| tab 成员 | `Flow` | `FlowPage` | 会话（agent 应用级实体，S5 升格：切页存活 + RUN 徽标 + 完成通知 + Steer 排队；`:feature-quest`，专篇见 [pages/quest.md](pages/quest.md)） |
| tab 成员 | `LarkBridge` | `LarkBridgePage` | 桥接（持续监控流；内部结构零改动搬进 tab 容器） |
| dock 设备 | `ShellTools` | `ShellToolsPage` | ADB 快捷命令 + 设备态 |
| 五件套（palette 深入模式可达） | `XLogDecoder`/`LogTagFilter`/`FileSplitter`/`Timestamp`/`Archive` | 各 feature 页 | navItem 已退役（S3）；palette 带参命令直执行 + openPage 深入模式跳原页（临时 tab 承载） |
| dock 工具 | `Tools` | 工具聚合页（宿主注册页） | dock 第 5 图标「工具」：五件套页内子签收拢为常驻直达面（Ctrl+5）；五件套自身 navItem 仍退役、palette 深入模式跳原页 |
| dock 设备观测 | `DeviceObservation` | 设备观测空间页（宿主聚合页 `page/deviceobs/`） | dock 第 6 图标「设备观测」（Ctrl+6，末位追加——Ctrl+1..5 序不变）：承载 :feature-logcat 内容组件（`LogcatPanel`）+ 设备栏（:core 设备域单源 + :ui 共享渲染件）；专篇见 [pages/logcat.md](pages/logcat.md) |
| dock 智能体 | `AgentCenter` | 智能体中心聚合页（宿主注册页） | dock「智能体中心」：页内子页签承载会话（`Flow`）/知识蒸馏（`Distill`）/语音转写（`VoiceTranscribe`）；三值 navItem 退役、保留 content，存量 last_page 改道 AgentCenter |
| tab 成员 | `Report` | 报告页（宿主自有视图） | 工作台 tab 容器第四成员（最近 run 报告卡列表 + 空态引导；palette 可达） |
| 入口组 | `McpExpose` | `McpExposePage` | MCP 服务外放（[pages/mcp.md](pages/mcp.md)） |
| 智能体组 | `AiSettings`/`Distill`/`VoiceTranscribe` | 各 feature 页 | AI 设置（置灰引导落点）/ 知识蒸馏 / 语音转写（[pages/voice.md](pages/voice.md)） |
| 结构性页面 | `Setting`/`AppCenter`/`Debug` | 宿主页 | 设置（Ctrl+, 直达）/ 应用中心（🔴 已退役——反馈 6，枚举值三段式保留恒不可达）/ 调试（开发者选项门控） |

页面切换：`PageHomeContent` 中 `AnimatedContent`（fade + 轻微 slide，方向表经
`navItemsWithAppCenter` 展开），无独立返回栈。🔴 Debug 页返回链已退役（SYS-15 ·
重设计批 6）：`backFromDebug()` / `previousPage` 已删除——dock 系统区/命令面板
（均按 `debugEnabled` 门控）切走即离开，无返回链。

退役记录：`Default` 欢迎页 S2 退役、S7 删枚举值；旧 14 页等权导航/DockManager
定制（dock_items）/BottomPanel（panel_*）/Ctrl+J·Ctrl+Alt+J 热键/Debug 返回链
（backFromDebug）全部随批次删除。
导航模型细节（五舱/dock 降级/命令面板/NavVisibility）见
[pages/navigation.md](pages/navigation.md)。

---

## AppHost 与能力四态（2026-09-15 S2/S3 落地）

- **AppHost**（`core/src/jvmMain/.../core/runtime/AppHost.kt`）：App 级装配单点 + 全应用唯一根
  scope + 退出序（`participants.shutdown → scope.cancel → 宿主收尾`——桥接先停、后台继续、
  日志后关，顺序即契约）。🔴 落 `:core` 而非 `:composeApp`：feature 侧参与者（ShellToolsRuntime 等）
  需要派生 App 级 scope，依赖方向恒 feature → core。宿主侧 `CoroutineScope(` 构造点由
  `AppHostGuardTest`（A/B 组）守护：新 App 级 scope 必须从 `AppHost.scope` 派生或进白名单带理由。
- **AppLifecycleParticipant**（同包）：feature 把 App 级启动/退出钩子注册进 AppHost
  （`main.kt` 顶层同步注册、`startAll` 在 startInit 后 await）；参与者不自建 App 级 scope。
- **密钥文件权限硬化**（2026-10-09 修复批，`host/initializer/SecretFilePermHardeningInitializer.kt`
  经 main.kt 注册 level 2）：启动期对 `~/.toolbelt/.datastore/*.preferences_pb`
  补 POSIX 600（明文密钥落盘处——llm_api_key / xlog_private_key）；幂等重扫（会话内写经
  umask 重建文件，回退窗口到下次启动收口）；非 POSIX 文件系统（Windows）整体跳过；
  单文件失败仅告警不阻断启动。
- **能力四态**（`CapabilitySnapshot.kt`）：`CapabilityId` 仅 **2 值（LLM/GATEWAY）**×
  `Verified/PermissionRequired/Degraded/Unavailable` 四态（`CLI_AGENT` 已随 CLI 委托链退役删除）。🔴 **判断**采用四态优于布尔：
  「未配置」与「配了调不通」必须可分，不引入按渠道嵌套的第二层；`CapabilityRegistry` 是纯 sink
  （:core 不认识任何数据源，各 feature 经 `capabilities()` 上报）。
- 🔴 **不可动前提**：`Bridge.APP_EXIT_EDT_BUDGET_MILLIS` 的退出时序契约
  （`BridgeAppExitEdtBudgetTest` 断言 `synth.closeCount == 0`——EDT 侧只等有限预算、优雅关闭
  teardown 后台继续）任何重构不得改变形状。

## 第二 IM 接缝的边界规则（2026-09-15 S5 建立「写纸不写代码」；2026-09-27 能力一级化批 D 起文档 + 守护测试双轨）

真实接缝 = 模块边界（`MessageGateway` **29 成员**（批 A +9、批 C +2 后）、`ReplySink` 唯一出站口、
`CardJson`、`LarkSdkIsolationTest` A~F、`LarkApiBoundaryTest`、`ChannelSeamGuardTest`），**不是类型名**。
已登记的穿透面（诚实清点，批 D 重核）：`lark-bridge/src/main` 下 **27 个文件**以 `import` 语句引用
`com.jiaoay.lark.gateway`（另有 6 个文件以全限定名内联引用，合计 33——import 数为口径时注明）；
`ReplySink.kt` 的 `val target: LarkTarget` 是飞书类型写进唯一出站契约——「新 gateway 模块 +
复用五阶段管线」的说法不成立，届时需 `ReplySink.target` 中立化 + `BridgeAssemblerParityTest`
字面断言一次性同改。**今天不付改名成本，届时付一次性成本；这个等价交换是有意的。**

边界规则（新增代码即刻生效；🔴 原「写纸不写代码」的第一条已升格为 `ChannelSeamGuardTest` 守护）：
- `pipeline/` 与 `handler/` **不得新建** `LarkTarget.Chat(` / `CardSpec(` 构造点——只允许经
  `RequestContextFactory`（`EventIntake.kt` 的 `targetOf`/`replyAnchorOf`）与 `CardJson`
  （卡片构造唯一入口；先例：`BridgeAssembler.kt` 注释记录的 replyAnchorOf 双写事故，对抗评审两次命中同一点）。
  🔴 守护基线（当前 HEAD 实况）：两目录内 `LarkTarget.Chat(` **零命中**（构造只在 `BridgeAssembler` /
  `Bridge` / `cli/SendCommand`——装配点与 CLI 入口，不在守护面）；`CardSpec(` **4 处全部收在
  `pipeline/CardJson.kt`**。越界即红（`ChannelSeamGuardTest.C`）。
- 新渠道接入的落点 = 新 `:xxx-gateway` 模块 + `BridgeConfig` 域字段 + `BridgeGateways` 分派，
  五阶段管线对渠道类型的耦合届时一次性厘清。

### 能力一级化分级模型（2026-09-27 批 A~D 落地，lib 侧单源 = `lark-bridge/.../config/NativeCapability.kt`）

飞书原生 API 升为 agent 一级能力；分级模型同时服务**会话作用域安全**（LLM 能力边界）与**渠道中立层**
（第二 IM 时哪些面要动）。三级定义：

| 级 | 定义 | 载体 | 语义 |
|----|------|------|------|
| **L1** | 当前会话内原生操作 | 工具三件：`send_image` / `recall_own_message`（账本双重守护）/ `reply_in_thread` | **定义即恒注册**，零配置面 |
| **L2** | 跨会话能力（会话纵深数据/建群/查用户/读文档） | 工具五件：`fetch_chat_history` / `get_chat_info` / `create_group_chat`（写操作经 `ToolConfirmGate` 强制确认）/ `lookup_user` / `read_wiki_document` | **三条件注册，缺一不注册**：① `agent.nativeTools` 配置接线 ② `NativeCapability.l2ToolsFor` 命中（l2Enabled + grants 双闸）③ `NativeChatSurface` 接缝在位 |
| **L3** | 替用户身份 / 凭证读取 / 群生命周期 / 踢人 / 全局搜索 / pin | **无** | **恒排除，无配置载体**（`LarkApiBoundaryTest` 负清单守护） |

- **fail-closed 语义**：`agent.nativeTools` 默认 `l2Enabled=false` + grants 空 = 全禁（v9 配置段）；
  gateway 侧全部新成员**带默认实现**（null/false 返回）——「默认实现 = 能力缺席」，scope 未开通/
  未连接时工具回落 scope 引导文案（`NativeCapability.scopeHintOf` 单源），绝不抛异常。
- **NativeCapability 单源**：L1 名单（`l1Tools()`）/ L2 授权判定（`isL2Granted`）/ 工具名↔grant↔scope
  映射（`l2ToolsFor` / `scopeHintOf`）全部收敛在一个 lib 侧纯函数 object——UI 能力卡、agent 注册面、
  降级文案三处消费同一份映射，防三处漂移。
- **渠道中立接缝已实质化（批 C）**：`NativeChatSurface`（渠道中立接口，**零飞书专属类型**）+
  `GatewayNativeChatSurface`（每消息现造闭包，chatId 构造即定格）——工具面经 `DispatchPayload.nativeSurface`
  消费；「Args 无 chat_id」（目标会话钉死实现闭包，LLM 不可指定会话）由 `ChannelSeamGuardTest.D`
  反射守护（经 kotlinx serializer descriptor 读 Args 字段名——🔴 Java 反射参数名在未开
  `-java-parameters` 时恒 arg0/arg1，不可用）。

**第二渠道接入时需要动什么**（十项清单——本轮不做任何新渠道，此清单只定义届时的一次性成本面）：

1. `ReplySink.target` 中立化（`LarkTarget` → 渠道中立 Target——唯一出站契约里的飞书类型）；
2. `BridgeConfig` 渠道判别（connection 域或新渠道域字段 + 迁移步）；
3. `BridgeGateways` 分派（按渠道选 gateway 实现）；
4. `InboundMessage` 归一（入站事件模型跨渠道核对——字段名已按中立命名，逐字段核实语义等价）；
5. 卡片协议渠道化（`CardSpec`→ 各渠道卡片 JSON 的 mapper——现在只有 `LarkOutboundMapper`）；
6. 策略/传输（`LarkPolicy` / `WsInboundTransportFactory` 的渠道面）；
7. 分级表（`NativeToolGrant` 的 scope 串按渠道后台语义重核——token 语义可复用，scope 串是渠道运营面）；
8. 确认卡（`ConfirmService`/`ToolConfirmGate` 的卡片协议依赖——随第 5 项联动）；
9. 向导与文档（`CockpitLogic.BACKEND_CHECKLIST` 的后台配置清单是飞书运营面，新渠道须另写）；
10. 守护测试面（`LarkSdkIsolationTest` / `LarkApiBoundaryTest` / `ChannelSeamGuardTest` 的模块清单与
    黑名单前缀扩新渠道 gateway）。

## 关键约定

- 每个功能页面有独立的 `XxxViewModel`，通过 `viewModel { XxxViewModel() }` 创建，**不共享状态**
- 全局共享状态（主题、主题模式、当前页、Shell 类型、调试开关）统一放 `AppViewModel`
- Shell 执行**不直接用** `ProcessBuilder`，统一通过 `SimpleShell` / `StreamingShell` / `InteractiveShell`
- 持久化**不直接用** DataStore（禁止自行 `PreferenceDataStoreFactory`）：全部配置仓库统一经 `:core` 的 `AppDataStore` **进程唯一实例**读写（同一文件多 DataStore 实例会启动即崩）。双轨归属——宿主域 key（外观/导航/调试开关/日志开关/Shell 类型）走 `EasyDataManager`（物理位于 `:composeApp` 模块的 `host/` 宿主包，2026-09-10 迁入）；feature 域 key 走各自模块的 ConfigStore（`LogKitConfigStore` / `TimestampConfigStore` / `ShellToolsDataStore`）。enum 恢复用 `core/data/EnumRestore.kt` 的 `loadEnum`。桥接域 `bridge-config.json` 例外见下节
- 页面标题统一用 `widget/PageHeader`；文件输入统一用 `widget/FileDropZone`（拖拽+点击）；文件任务卡统一用 `widget/TaskCard` + `TaskUi/TaskUiStatus`
- 状态/日志语义色统一从 `core/theme/EasyToolboxColors.kt` 的 `LocalEasyToolboxColors.current` 读取，**页面禁止硬编码 Color(0xFF...)**
- 序列化统一用 kotlinx.serialization（Gson 已移除）

### 飞书桥接域例外（详见 `AGENTS.md` 约定 1/2/6/8）

- **配置不入 DataStore**：桥接配置走外部单一 JSON 文档 `~/.toolbelt/lark/bridge-config.json`（数据根由宿主 `LarkBridgeManager` 经 `FileUtils.getLarkDir()` 注入 lib），DataStore 只保留一个一次性迁移标记 `lark_bridge_legacy_imported`。
- **agent 子进程不走 shell 封装**：一律走 `:lark-bridge` 的 `exec/ProgramLauncher`（argv 语义 + `Charsets.UTF_8`），不走 `SimpleShell`/`StreamingShell`/`InteractiveShell`（后者是 shell 命令行语义、Windows 下 charset 为 ANSI）。lib 侧 `ProcessBuilder` 唯一出现点由守护测试钉死在 `exec/ProgramLauncher.kt`。
- **SDK 隔离**：官方 `oapi-sdk` 传递引入 Gson 无法移除，隔离边界 = `:lark-gateway`；shadowJar 禁止 relocate Gson/protobuf。
- 🔴 **`core/.../LogbackHelper.kt` 的 `context.getLogger("com.lark.oapi")`** 是 **logback logger 名字符串字面量**（用途：压制 SDK 日志噪音），**非 import、非类型引用**，SDK 缺席时无副作用——语义合规、字面命中。`LarkSdkIsolationTest`（`:composeApp`，`src/jvmTest/.../arch/`）B 组归一化会剥除字符串字面量，故守护测试不误报，此为有意豁免。

---

## 测试执行面

- 全量验收门槛口径不变：改 UI/注释后必跑 `./gradlew :composeApp:jvmTest`（含 `*SceneGuardTest` 场景渲染像素带守护——skiko 渲染依赖，是最重最慢段）。内环快速通道两件：`./gradlew :composeApp:guardTests`（只跑 `arch/` 守护面、排除 `*SceneGuardTest`）与 `./gradlew :core:guardTests`（只跑 `core` `system/` 扫盘守护面）——「改一点 → 秒级看守护红不红」；快速任务是内环增益，不替代全量验收门槛。测试报告 XML 落各自 `build/test-results/guardTests/`。
- 两个 guardTests 任务均为**永跑语义**（`outputs.upToDateWhen { false }` + `outputs.cacheIf { false }`）：扫盘守护被扫源码不在 task 声明输入里（跨模块源码变更不进 cache key），up-to-date/FROM-CACHE 会假绿——永跑是机械防线，AI/人不必再记得为守护通道手加 flag；全量 jvmTest 内嵌的其余扫盘守护仍受假绿面约束，验收铁律（`--rerun-tasks --no-build-cache`）不变。
- test-retry 插件（`org.gradle.test-retry` 1.6.6，经 `KmpComposeConventionPlugin`/`JvmCliConventionPlugin` 统一配置）：仅 CI 环境变量在场时 `maxRetries=2`、`maxFailures=20` 熔断、重试转绿不 fail 构建；本地恒 0。重试只是 flaky 缓解，与 AGENTS.md「已知 flaky 与良性红登记」节并行。
- 发布前置测试门禁：`.github/workflows/release.yml` 的 `test-gate` job——tag 发布前 linux 全模块 `jvmTest test --rerun-tasks --no-build-cache`（任务名双写：KMP 模块用 `jvmTest`、纯 JVM lib 用 `test`，只写 `test` 空跑——dry-run 实证；暂排除 `:feature-logworkbench:jvmTest` 的环境依赖用例），任一红阻断四平台打包。
- 测试结果 XML 新鲜度机械校验：`python3 scripts/check_test_results.py`（mtime 门槛 + 只读汇总；陈旧快照当验收依据是假绿通道）。

---

## 注释治理（2026-10-06 起，CommentGuardTest 守护）

政策真源 = `AGENTS.md`「关键约定」第 9 条「注释宪法」。本节记录治理政策与守护机制的运维面。

### 为什么

2026-10-06 三域蜂群审计（host/features/libs）确认：全仓 19,962 行注释（11.9%）中大量为「历史叙事」（批次号/日期/「主人拍板」/已删符号墓碑）与硬矛盾（注释与代码行为不符，三域约 170 处已定位）。注释与代码漂移后，AI 读模块会建立错误行为模型——这是注释治理的直接动机。历史归 commit message，决策知识归 `docs/`，不进源码。

### 守护机制（CommentGuardTest）

- 位置：`composeApp/src/jvmTest/.../arch/CommentGuardTest.kt`；扫描面分两档：全部规则管生产双源集（`src/jvmMain/kotlin` + `src/main/kotlin`，含 includeBuild 的 `build-logic`），生产面自检下限 450 文件；narrative 三正则与 `MOCKUP_HTML_REF` 同时管测试双源集（`src/jvmTest/kotlin` + `src/test/kotlin`），悬空 KDoc 链接与注释掉的声明不扩测试面（测试面误伤面大，另行评估）。
- 规则：`NARRATIVE_DATE`（日期叙事）/ `NARRATIVE_BATCH`（批次卡号）/ `NARRATIVE_PROCESS`（过程词）/ `DANGLING_KDOC_LINK`（悬空 KDoc 链接）/ `COMMENTED_OUT_DECL`（注释掉的声明）/ `MOCKUP_HTML_REF`（注释区 `*.html` 设计稿引用，零容忍——`text/html` MIME 写法与字符串字面量不在拦截面）。前三个走棘轮 baseline，后三个零容忍。
- 字符串字面量不参与叙事匹配（URL/日期值/示例串安全）。
- 🔴 baseline = 计数棘轮：`path → 最大命中行数`，只许降不许升，归零条目必须删除；调大 baseline 须在 commit message 给理由。生产面叙事存量曾清零（空 map 零容忍态）；随后 narrative 正则补盲区变体并扩面至测试双源集，按真跑 offender 清单回填存量基线——`narrativeBaseline` 现为 341 条（生产与测试两侧存量，多为盲区变体形态的既有命中），`redMarkBaseline` 现为 220 条；不在 map 中的文件恒零容忍。两数字与守护测试的「baseline entry counts match docs」断言互锁——增删条目须同 commit 同步本节口径。
- 🔴 标记计数棘轮（`redMarkBaseline`，同只降不升语义）：按文件登记注释区 🔴 行数上限（字符串字面量里的 🔴 不计），只登记仍有 🔴 的文件；🔴 的口径 = 真门禁（违反即出错/数据损坏/不可逆，AGENTS.md「关键约定」第 9 条），普通强调去标降级即可消化存量。

### 测试锚纪律

仓库守护测试有两类契约面：剥注释后断言的「禁出现」类（对注释治理免疫）与 raw 源码 `readText()` 断言的「必存在」类（依赖注释/字符串活着）。清理注释前必须先 grep 本批文件的 raw 测试依赖（`grep -rn "readText\(\)|in source|substringAfter\(" <模块>/src/*Test`），needle 落在注释里的**先迁锚到代码常量或改写测试断言，后清注释**。已登记的锚寄生案例与处置见治理批 commit message（批 A）。

### 稿面出处锚与规格令牌的归档约定

2026-10-06 批（C1-C7）曾确立「对稿注释保留稿面值语义」口径（稿面 file:line + 值写进注释本体）；2026-10-11 批按「AI 可独立求值」最高准则收紧为**生产注释稿面引用清零**：

- 生产源码注释不再引用任何仓库外/未入库的设计预览稿与共享样式文件——单条注释必须自足可求值：值 + 单位 + 依据直接写进注释（例：`/** 图表主图高度（柱状图/环形图统一高度；环形图基准 120dp） */`），不依赖稿面文件在场。
- 设计来源（稿面文件 → 消费点对照表）随注释自足登记（值 + 单位 + 依据）；取值现状真源 = `docs/core/theme.md` / `docs/ui-style-guide.md` 与源码 token 定义。
- `CommentGuardTest` 的 `MOCKUP_HTML_REF` 规则对注释区 `*.html` 引用零容忍（字符串字面量与 `text/html` MIME 写法不在拦截面）。
- 规格锚与工单令牌（`§N.M` / `M-xx` / `BC-xxx`）须附可解析的真实路径：仓内唯一文件名 + 节号即可解析（如 `gateway-contract.md §15.2 M-16`）；令牌族真源对照表同上登记文件。检索不到出处的令牌删令牌留契约正文。
- 治理后新写叙事由 `CommentGuardTest` 棘轮拦截（存量按 baseline 锁定只降不升，未登记文件零容忍）

---

## 内存取证管线（2026-09-22 R 批 C1/C3，jcmd 三点采样）

怀疑内存泄漏时的标准取证流程（JBR 25 工具链，全仓唯一记载点）：

```bash
JBR=~/.gradle/jdks/jetbrains_s_r_o_-25-amd64-linux.2/bin
PID=$(pgrep -f 'easy_dev_toolbox.MainKt' | head -1)   # ⚠️ 必先 ps 甄别——pgrep 会同中
                                                      # gradle wrapper 与热重载 devtools 伴随进程
take() { $JBR/jcmd $PID GC.run >/dev/null; sleep 2; $JBR/jcmd $PID GC.class_histogram > "histo_$1.txt"; }
# t0 = 基线（idle ≥2min）→ 执行可疑操作（切页循环/拖拽）→ t1 → idle 10min → t2
$JBR/jstat -gcutil $PID 5000 120 &                    # 并行采样：O 区间两次 Full GC 后不回落 = 泄漏信号
```

判读要点（R 批实测沉淀）：

- **直方图类名在 p[3]**（p[4] 是模块注记 `java.base@25`）——解析脚本误用 p[4] 会把全部 java.base 类坍缩成一个键，得出假「零增长」（本批真实踩坑）
- 判读重点类：`com.jiaoay.*` ViewModel 家族（每种恒 1 实例 = 正常——窗口级 ViewModelStore 驻留是设计使然）、`SlotTable`/`CompositionImpl`（个位数）、`kotlinx.coroutines`、`skiko`
- **切页链零泄漏基线**（R 批 100 轮真实切页实测）：VM 每种恒 1、SlotTable=6、CompositionImpl=3、idle 10min 全类零增长
- 🔴 **开发态噪音**：热重载 devtools 让 javassist 动态类把元空间吃到恒 98%+、活对象 +1~2MB/10min（反射元数据）——`./gradlew :composeApp:run` 是双进程形态（编排器 + 应用），取证要么避开热重载直接 `java -jar`，要么只看 t0→t1→t2 差值
- 🔴 **本机输入合成全线不可用**（Wayland compositor 拦截 XTest/XSendEvent 合成键与指针——XWarpPointer 后 XQueryPointer 不动即铁证）：交互压测须走应用内自压测（临时 main args 分支）或人工操作
