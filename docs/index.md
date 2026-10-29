# Toolbelt 知识库索引

> 本目录是 AI 辅助开发的知识库，记录项目代码结构与设计约定，读此文件即可定位所需文档，无需反复扫描源码。

---

## 技术栈

| 层次 | 技术 |
|------|------|
| UI 框架 | Compose Multiplatform (JVM Desktop) |
| 状态管理 | AndroidX ViewModel + Kotlin StateFlow |
| 持久化 | AndroidX DataStore (Preferences) |
| 异步 | Kotlin Coroutines / Dispatchers.IO |
| 日志 | 自研 JLog（可替换实现） |
| JSON | kotlinx.serialization（多态 @Serializable） |
| AI Agent | Koog 1.3.0（真源 = `:core` api 传递四件；`:feature-ai`（三件）/ `:feature-larkbridge`（显式五件，含 embeddings/rag 线）按需自声明，`:test-fixtures` compileOnly 一件（编译期接触、零运行时渗透）；🔴 字节码 Java 17 ⇒ 不得进 jvmTarget 11 的 lib 模块） |

---

## 文档目录

```
docs/
├── index.md                ← 你在这里（总索引）
├── shell_tools.md          ← shell_tools.json 外部配置文件格式规范
├── architecture.md         ← 启动流程、模块依赖关系、页面路由、关键约定
├── ui-style-guide.md       ← UI 风格规范与标准（设计令牌速查 / 页面骨架 / 共享组件 API / 守护规则）
├── log-tools.md            ← :log-tools 库（XLog 解码管线 / 内容判型 / Tag·时间过滤与分割 / 确定性分析管线 / 大文件查看引擎 LogViewer / 独立 CLI / SafeExtract）
├── core/
│   ├── shell.md            ← Shell 执行层（SimpleShell / StreamingShell / InteractiveShell / ShellCommandBuilder / ShellType）
│   ├── data.md             ← 数据存储层（EasyDataManager / LocalDataManager / loadEnum / DesktopUtils）
│   ├── session-store.md    ← 会话存储域（SessionStore 家族 / Database 与 user_version 迁移语义 / ChatEntry 模型族 / ChatSessionReducer 三 Session 委托单源）
│   ├── entities-and-initializer.md ← 结果实体与启动初始化域（JResult 四分支 / JErrorCode 错误码全量清单 / JInitializerManager 注册与高优·低优执行模型 / InitResult 三态与发射时序）
│   ├── viewmodel.md        ← AppViewModel（全局状态与方法）
│   ├── theme.md            ← 主题系统（AppThemeSpec 三角色+底色主权+覆写表 / 20 套目录裁定 / hue-keep v2 / 样张 / 昼战 / 语义色 / 尺寸动效玻璃 token）
│   ├── agent-tools.md      ← Agent 工具面（AgentToolCatalog 目录 / authorizeWithGate 路径围栏与匹配集 / 侦察四件与名集单源 / 输出硬上界 / StructuredSimpleTool 三段式 / 添加新工具步骤）
│   ├── native-memory.md    ← native 内存与 glibc malloc 调参（投屏 RSS 高水位机制链 / NativeAllocatorTuner 双参数契约 / FFM 收口单点 / 验收口径）
│   └── log.md              ← 日志系统（JLog）
├── pages/
│   ├── navigation.md       ← 页面导航（R1 空间/文档模型：dock 六空间不产 tab / openTabs 文档集 Auto-hide / 复活通道封堵 / 命令面板 / 全局热键表）
│   ├── settings.md         ← 设置页（外观模式 / 配色 / Shell / 日志 / 开发者选项）
│   ├── shell-tools.md      ← Shell 工具页（ViewModel 分层 / ToolNode 树 / 状态模型 / 执行引擎）
│   ├── logcat.md           ← logcat 实时抓取（:feature-logcat：三形态匹配器 / 有界滚动窗 + 全量落盘 / 标记锚点 / 两过滤参数 key——挂载于设备观测空间页）
│   ├── lark-bridge.md      ← 飞书桥接页（SDK 长连接 / 单一 JSON 配置文档 / 本地 Koog 调度 + 本地工具族 / 工作区域 ws·git / HITL 提问卡 / Compose 编辑器 / 诊断操作 / 自研长连接客户端 :lark-ws）
│   ├── ai-settings.md      ← AI 能力域（:feature-ai：AI 设置 / Koog 接入 / 记忆与知识库 / 路径围栏）
│   ├── logkit-tools.md     ← 日志工具四胞胎（feature-logkit 三页 + feature-timestamp：TaskPageTemplate 同构骨架 / 深度分析链 / 深度分析钮）+ 日志对比页 + Tag 过滤统计面板
│   ├── log-workbench.md    ← 日志工作台（:feature-logworkbench：三栏页 / runs 表 / 研判链 / HITL 非模态确认卡 / 深链含 run 回看）
│   ├── voice.md             ← 语音转写（:feature-voice：sherpa-onnx 离线转写 / 模型分发 / VAD 实时录音 / AI 整理纪要）
│   ├── quest.md             ← 会话域（:feature-quest：agent 主动索要信息 / ask_user HITL / flows 表 / 能力目录——AG-01 后称谓「会话域」，检索别名「流程」保留）
│   ├── mcp.md               ← MCP 服务（:feature-mcp：本地能力经 MCP 协议外放 / 三装配层 / run 登记表 / 隔离守护）
│   └── debug.md            ← Debug 页（设置开关入口，Shell 调试聊天界面）
├── manual/                 ← 面向最终用户的用户手册（user-manual skill 产物，与本知识库分开维护）
└── reports/                ← 一次性分析产物（如 architecture-analysis-*.md），不入本索引
```

---

## 快速定位指南

| 我想做的事 | 看哪里 |
|-----------|--------|
| 写新页面/新组件（对齐 UI 规范） | [ui-style-guide.md](ui-style-guide.md) |
| 加 hover 说明气泡 / 选中·悬停洗底行 / 文字 chip 钮 / HITL 确认覆盖层（不再手写配方） | [ui-style-guide.md](ui-style-guide.md) 4.7o（HoverTooltipBox / selectionHoverWashColor / ChipButton）+ 4.7m（GateConfirmOverlay） |
| 加 inspector 槽位贡献 / palette 带参命令 | [ui-style-guide.md](ui-style-guide.md) 2.9 节 + [pages/navigation.md](pages/navigation.md)「ToolPageRegistry」节 |
| 改全局快捷键 / F1 参考卡 | `host/HotkeyHub.kt`（唯一真源；表见 [ui-style-guide.md](ui-style-guide.md) 5.6 节） |
| 加浮层 Toast / L1 撤销条 | `:core` `core/runtime/StatusSignals.kt`（状态面，S5b 下沉）+ `host/StatusHost.kt`（渲染壳；见 [ui-style-guide.md](ui-style-guide.md) 4.7k） |
| 执行一条 shell 命令 | [core/shell.md](core/shell.md) — `SimpleShell` |
| 保持 shell 会话并发多条命令 | [core/shell.md](core/shell.md) — `InteractiveShell` |
| 读写持久化配置项 | [core/data.md](core/data.md) — `EasyDataManager` |
| 改设备域 / 改内置 adb 分发 / 加设备内核 | [core/device.md](core/device.md) — `:adb-core` / `:device-core` 两个纯 JVM lib + `:core` 的 `DeviceHub` 装配 |
| 新增全局设置项（含 UI） | [core/viewmodel.md](core/viewmodel.md) + [pages/settings.md](pages/settings.md) |
| 新增导航页面 | [pages/navigation.md](pages/navigation.md) |
| 修改 Shell 工具页逻辑 | [pages/shell-tools.md](pages/shell-tools.md) |
| 修改 shell_tools.json 配置格式 | [shell_tools.md](shell_tools.md) |
| 切换主题配色 | [core/theme.md](core/theme.md) |
| UI 改动后做视觉验收（截图应用窗口） | [AGENTS.md](../AGENTS.md)「桌面应用截图验收管线」（xwd + ffmpeg，Wayland 实测可用） |
| UI 布局改动做像素级回归验证（零显示器） | `composeApp/src/jvmTest/.../arch/SegmentedGapGuardTest` + `AnimatedContentSingleRootGuardTest`（ImageComposeScene 场景渲染 + PNG 像素带断言——2026-09-26 起布局验证首选路线，不依赖实机/截图）；**带状态页面**走「预驱动 VM 状态注入」配方（VM 经 ViewModelProvider 同 key 预置进独立 ViewModelStore + CompositionLocalProvider 提供 owner；组合期异常须用 CoroutineExceptionHandler 捕获，否则场景静默渲染成空白）——先例：`composeApp/.../arch/LogDiffSceneGuardTest`（双槽/拒收横幅/结果态行级着色）、`LogTagFilterSceneGuardTest`（预演卡/统计面板）、`feature-logcat/.../logcat/LogcatPanelSceneGuardTest`（Running 自动展开 + 深底控制台；渲染期间有活跃后台协程的场景须专用单线程 dispatcher，防 SnapshotStateObserver 跨线程误报；该模块 jvmTest 为此装配了 skiko 运行时依赖） |
| 使用/修改飞书桥接功能 | [pages/lark-bridge.md](pages/lark-bridge.md) |
| 改日志工作台（块化证据流 / run 回看 / 研判链 / File Buffer / 深链） | [pages/log-workbench.md](pages/log-workbench.md) |
| 改报告出站 / 送飞书面（导出 Markdown / `ReportDelivery` 中立槽 / `Bridge.sendFile` 直发门面） | [pages/log-workbench.md](pages/log-workbench.md)「报告出站」节 + [pages/lark-bridge.md](pages/lark-bridge.md)「运行与诊断操作」节（sendFile 门面）与「出站敏感文件排除表」节 |
| 改语音转写（模型分发 / ffmpeg 解码 / sherpa JNI / AI 整理纪要 / VAD 实时录音） | [pages/voice.md](pages/voice.md) |
| 改流程域（ask_user 主动提问 / flows 表 / 能力目录 / ChatMemory 续聊 / 确认卡 / S5 会话升格） | [pages/quest.md](pages/quest.md)（AG-01 称谓：页面与注册 label 已改「会话/会话域」，专篇标题同步；「流程」为检索别名保留） |
| 改 agent 会话存储 / 会话时间线回看 / ChatEntry 模型族 / 会话 reducer | [core/session-store.md](core/session-store.md)（SessionStore 家族 / Database user_version 迁移语义 / 三 Session 委托） |
| 写可失败操作的结果类型 / 加错误码 / 改启动初始化器（注册·分组·level） | [core/entities-and-initializer.md](core/entities-and-initializer.md)（JResult / JErrorCode / JInitializerManager 执行模型 / InitResult 发射时序） |
| 加/改 agent 工具（AgentToolCatalog 目录 / 路径围栏 authorizeWithGate / 侦察四件 / 名集装配 / 添加新工具步骤清单） | [core/agent-tools.md](core/agent-tools.md) |
| 改 MCP 外放（服务启停 / 外放工具名集 / agent 会话 run / 围栏 deny-only / 隔离白名单） | [pages/mcp.md](pages/mcp.md) |
| 改 XLog 产物「深度分析」深链接线 | [pages/navigation.md](pages/navigation.md)「命令面板」节 + [pages/log-workbench.md](pages/log-workbench.md)「深链」节 |
| 开/改智能调度（Koog 大脑 / 本地工具族 / 免重启激活） | [pages/lark-bridge.md](pages/lark-bridge.md#智能调度agent-first-默认形态本地-koog-agent-作为唯一执行大脑) |
| 加/改 L1/L2 原生工具（send_image/recall/L2 五件接缝在位即注册） | [pages/lark-bridge.md](pages/lark-bridge.md#原生能力分级面2026-09-27-能力一级化批-adl1l2l3)「原生能力分级面」节 + [architecture.md](architecture.md)「能力一级化分级模型」节 |
| 改权限模型（入站准入 / 信任用户名单 / 高风险分级） | [pages/lark-bridge.md](pages/lark-bridge.md)「权限模型（改造后三层）」节 + 「配置模型」节（`access.trustedUsers`；源码 `lark-bridge/.../confirm/TrustPolicy.kt` + `pipeline/AccessControl.kt`） |
| 改 NativeChatSurface / ToolConfirmGate（L2 接缝/确认门） | [pages/lark-bridge.md](pages/lark-bridge.md#原生能力分级面2026-09-27-能力一级化批-adl1l2l3)「原生能力分级面」节（源码 `lark-bridge/.../pipeline/NativeChatSurface.kt` + `confirm/ToolConfirmGate.kt`） |
| 改桥接配置页 IA（四分区/搜索/快速上手门控/跨会话 scope 验证/确认区块） | [pages/lark-bridge.md](pages/lark-bridge.md#页面结构larkbridgepage)「页面结构」节 + 「确认卡已答态」节（确认卡骨架）+ 「运行与诊断操作」节（探测/三窄口） |
| 加/改工作区域工具（ws.write_file / ws.run_task / git.read；信任名单域准入 + 逐动作确认卡） | [pages/lark-bridge.md](pages/lark-bridge.md)「工作区域（ws / git 域）」节（源码 `:core` `WsWriteTools.kt` / `WsRunTaskTools.kt` / `GitReadTools.kt` + feature 侧 `koog/KoogWsGates.kt`） |
| 改桥接 HITL 提问（user.ask_user 提问卡 / 引用回复作答 / 防替答） | [pages/lark-bridge.md](pages/lark-bridge.md)「user.ask_user 飞书提问卡」节（源码 `:core` `AskUserTool.kt` + lib `confirm/AskUserBus.kt` / `handler/AskAnswerHandler.kt` + feature `koog/KoogUserQuestionGate.kt`） |
| 接第二 IM 渠道（届时的一次性成本面） | [architecture.md](architecture.md)「能力一级化分级模型」节末「第二渠道接入时需要动什么」十项清单 |
| 机器人收不到消息 / 首次配置飞书后台 | [pages/lark-bridge.md](pages/lark-bridge.md#飞书后台配置必做) |
| 让机器人分析群里发的日志文件/压缩包 | [pages/lark-bridge.md](pages/lark-bridge.md#五阶段处理链) |
| 加/改「状态」指令或在线状态展示 | [pages/lark-bridge.md](pages/lark-bridge.md#运行与诊断操作) |
| 改文件下载去重/上传复用（UploadKeyMemo/ContentIndex） | [pages/lark-bridge.md](pages/lark-bridge.md#文件复用2026-09-12-文件复用批次) |
| 群里发文件不触发下载 / 改群@门控 | [pages/lark-bridge.md](pages/lark-bridge.md)「群消息 @门控（`agent.groupMode`）」节 |
| 改引用文件的下载/预处理进度反馈（下载卡） | [pages/lark-bridge.md](pages/lark-bridge.md#下载进度卡2026-09-12独立于-agent-过程卡) |
| 改自研长连接客户端（:lark-ws 协议/心跳/合包/重连） | [pages/lark-bridge.md](pages/lark-bridge.md)「自研长连接客户端」节 |
| 改确认卡/权限确认链路（已答态回执/裁决捎带/防替答） | [pages/lark-bridge.md](pages/lark-bridge.md#确认卡已答态2026-09-15-卡片借鉴批answeredquestioncard-已答卡语义)「确认卡已答态」节 |
| 改 AI 端点配置（Koog / LLM）/ AI 工具开关（`llm_ai_enabled` 三态） | [pages/ai-settings.md](pages/ai-settings.md) + `core/llm/LlmConfigStore.kt` + `core/llm/LlmClientFactory.kt` |
| 改 AI 设置页（模型服务 / 记忆面板 / 性格 / 安全约束） | [pages/ai-settings.md](pages/ai-settings.md)（源码 `:feature-ai` `page/ai/AiSettingsPage.kt` + `AiSettingsViewModel.kt`） |
| 改知识蒸馏（四阶段闭环 / 知识库四表浏览） | [pages/ai-settings.md](pages/ai-settings.md)「知识蒸馏」节（源码 `:feature-ai` `page/ai/distill/`） |
| 改 XLog 解码管线 / 行首时间戳契约 / log-tools CLI / SafeExtract 三闸 | [log-tools.md](log-tools.md) |
| 改大文件查看引擎 / 查看器会话（行偏移索引内存上界 / 窗口 seek / 级别导航含起点口径 / 搜索结果集上界 / 漂移失效） | [log-tools.md](log-tools.md)「查看引擎」节 |
| 改 XLog 解密 / Tag 过滤 / 文件分割三工具页（TaskPageTemplate 四胞胎） | [pages/logkit-tools.md](pages/logkit-tools.md) |
| 改日志对比页（双槽拖入 / 归一化开关 / degraded 明示 / hunk 巡检 / 差异行跳查看器 / 导出） | [pages/logkit-tools.md](pages/logkit-tools.md)「日志对比页」节 |
| 改 Tag 过滤「看分布」统计面板（全文件口径 / Tag 点击回填正则 / 导出 Markdown / 送飞书 ReportDelivery 第二消费面） | [pages/logkit-tools.md](pages/logkit-tools.md)「统计面板」节 |
| 改 logcat 实时抓取（三形态匹配器 / 有界滚动窗 + 全量落盘 / 中途改过滤回溯语义 / 前置检查 / 标记锚点） | [pages/logcat.md](pages/logcat.md) |
| 改设备观测空间（观测台/文件/应用/布局四子签 / 复现记录编排 / 文件·应用·布局 VM） | 源码 `composeApp/.../page/deviceobs/`（用户功能面见 [manual/device-obs.md](manual/device-obs.md)；设备内核与内置 adb 见 [core/device.md](core/device.md)；页面装配层施工专篇仍欠账，见下方「已知欠账」） |
| 改时间戳页（双向转换 / Enter 立即求值 / 当前时间卡折叠） | [pages/logkit-tools.md](pages/logkit-tools.md)「时间戳」节 |
| 改出站文件交付（敏感文件排除表 / send_file 沙箱放宽 / 产物总开关） | [pages/lark-bridge.md](pages/lark-bridge.md)「出站敏感文件排除表」+ 智能调度节「send_file 沙箱放宽」 |
| 改投屏/解码链 RSS 高水位 / glibc malloc 调参 / 新增 FFM native 直调 | [core/native-memory.md](core/native-memory.md)（NativeAllocatorTuner 双参数契约 / FFM 收口由 ForeignLinkerIsolationTest 守护）
| 写跨模块测试要隔离 user.home / 需要 Koog fake executor | `:test-fixtures`（`UserHomeIsolation` / `KoogFakeExecutor`——jvmTarget 11 零生产依赖；消费方式见 [architecture.md](architecture.md)「模块依赖关系」） |
| 查调优常量的飞书硬限制出处 / 写注释（稿面引用清零口径 / 规格·工单令牌可解析锚） | [pages/lark-bridge.md](pages/lark-bridge.md)「飞书硬限制与调优常量出处」节 + [architecture.md](architecture.md)「注释治理」节（宪法与稿面出处锚归档约定） |
| 面向最终用户的使用说明 | [manual/index.md](manual/index.md)（用户手册，与本知识库分开维护） |

---

## 已知欠账（最后核对：2026-10-28 docs-sync 批「飞书桥接去 CLI 委托」——lark-bridge 专篇全量对齐（本地 AI 调度为唯一执行面 / 工作区域 ws·git 与 user.ask_user 两个新小节 / 配置 69 字段四分区 / CLI 四子命令 / zcode 五后端节删除）+ zcode 在途变更销账；前次 2026-10-14 P128 docs-sync 集中批（manual 六批新面 + 侦察四件修正 + 桥接工具分级/长期记忆/语音节 + :test-support 模块行）；再前 2026-10-11 集中维护窗口（7z 整容器比闸销账）+ 文本卫生批（F2 桥接轮询收口登记），补齐后即时更新本节）

以下内容**尚无专篇文档**，涉及时直接读源码；补齐动作由 docs-sync skill（`.claude/skills/docs-sync/`）执行：

- [x] **`:log-tools` 模块专篇**（已销账，2026-10-11 文档批）：[log-tools.md](log-tools.md)——xlog 段格式与流式读取 / magic 标记→解码组合表 / ECDH-TEA 密钥语义 / `LogDecode` 原子替换写契约 / `LogFormatDetector`·`LogProbe` 内容判型 / `LogTagFilter`·`LogTimeFilter`·`LogSplitter` 行为契约 / CLI（含 `pipeline` 第五类型与确定性管线）/ `SafeExtract` 三闸与 zip-slip。页面装配侧见 [pages/logkit-tools.md](pages/logkit-tools.md)。
- [x] 四个页面专篇（已销账，2026-10-02 重设计批 11 DS-19）：`page/xlog/`、`page/logtag/`、`page/filesplit/`（feature-logkit）、`page/timestamp/`（feature-timestamp）合篇 [pages/logkit-tools.md](pages/logkit-tools.md)（四胞胎同构 TaskPageTemplate + 各页差异；时间戳页 XA-13 Enter 立即求值/TL-09 当前时间卡折叠已随批记载）
- [x] **AI 能力域专篇**（已销账，2026-10-02 重设计批 11 DS-19）：[pages/ai-settings.md](pages/ai-settings.md)——AI 设置页（模型服务/性格/安全约束/源码目录/记忆面板，SYS-01 双栏）、LlmConfigStore、KoogToolLoop、ToolExecutionContext 路径围栏、SessionStore 家族、知识蒸馏页（KbManagePanelContent 四表浏览 + REVIEW 一键看）。实时录音见 [pages/voice.md](pages/voice.md)；工作台/流程域见 [log-workbench.md](pages/log-workbench.md) / [quest.md](pages/quest.md)
- [x] **会话存储域专篇**（已销账，2026-10-11 文档批）：[core/session-store.md](core/session-store.md)——SessionStore 接口契约（append 幂等 upsert / tombstone / replay / latestSessionId / load 实现自决）/ `SqliteSessionStore` 生产实现（🔴 seqCounters 必须 ConcurrentHashMap 的并发不变量 / 会话自动登记 / 管理面 listSummaries·rename·delete 级联 / observation 截 2000 / artifacts `path|kind` 回放保真编码 / load 显式 no-op）/ `SessionLedger` JSONL 变体（测试实现与历史参照，生产零装配点）/ `Database` 进程唯一 SQLite 装配 + 🔴 user_version 迁移语义（建表判定与回写顺序）/ `ChatEntry` 模型族 / `ChatSessionReducer` 三 Session 委托单源（Workbench runId / Quest flowId / Distill 会话 id 三层同 key）+ NavActivityFacade。AI 能力域总览交叉锚见 [pages/ai-settings.md](pages/ai-settings.md)「会话存储」节。
- [x] **结果实体与启动初始化域专篇**（已销账，2026-10-11 文档批）：[core/entities-and-initializer.md](core/entities-and-initializer.md)——`entity/JResult` 四分支契约（Success/Failed 载荷与 `msg ?: code.desc` 展示约定 / Loading·Empty 现网零构造点仅存 StatusView 规格分支）/ `entity/JErrorCode` 全量清单（0 哨兵 + 1000 段 Shell + 2000 段桥接六子域，与 lib 侧 `BridgeErrorCode` 16 值 1:1 经 `BridgeErrorMapping` 映射）/ `JInitializer` 接口（isHighLevel 分组开关 / level 1..10）/ `JInitializerManager` 注册（AppHost 单点、须在 startInit 前）与高优并发阻断·低优串行错峰执行模型 / `InitResult` 三态 + `initStateFlow` 分阶段发射时序（低优失败不阻止进主界面）+ 现网初始化器注册表。API 级 StatusView 描述另见 [ui-style-guide.md](ui-style-guide.md) 4.3 节
- [x] `core/initializer/`（职责/注册方式/三态已入 [core/entities-and-initializer.md](core/entities-and-initializer.md) 上条同篇销账）
- [x] `core/data/FileUtils.kt`（应用数据目录单一真源，已登记于 [core/data.md](core/data.md)「FileUtils 目录访问器」节，含 `getLarkDir()`）
- [x] `page/home/ToolPageRegistry.kt`（导航注册装配点，已有专节见 [pages/navigation.md](pages/navigation.md)「ToolPageRegistry」）
- [x] `widget/TonalCard.kt`（已入册，2026-10-02 重设计批 11 DS-19：[ui-style-guide.md](ui-style-guide.md) 4.2b——16dp 新基准 + 入参表 + 「新卡片一律走本组件」契约）
- [x] 飞书桥接（**已推倒重写完成**）：旧 `:lark-bridge` / `:feature-larkbridge` 已拆除并按官方 SDK 长连接架构重建（`:composeApp → :feature-larkbridge → :lark-bridge → :lark-gateway → oapi-sdk`）。用户视角专篇见 [pages/lark-bridge.md](pages/lark-bridge.md)，施工规格见 `openspec/changes/archive/2026-09-10-rewrite-lark-bridge/`（`behavior-contract.md` / `config-domain-spec.md` / `gateway-contract.md`）。桥接内核 `:lark-bridge` 的管线/handler 细节仍以源码 + behavior-contract 为准（无独立专篇）。
- [x] **`schemaVersion` 默认值与 `ConfigMigrations.CURRENT_VERSION` 同源漂移（已销账，2026-09-26 P0 批修复 + 2026-09-27 批 B 推到 v9）**：`BridgeConfig.kt` 默认值改为 `= ConfigMigrations.CURRENT_VERSION` 同源（现 9），dump 落盘不再写回旧版本；`BridgeConfigDefaultsTest`/`ConfigMigrationsTest` 双侧断言同源。
- [x] **`BridgeStatus.kt` KDoc「22 项计数」与 `PipelineCounters.kt`「26 项」不一致（已销账，2026-09-27 docs-sync）**：BridgeStatus KDoc 已对齐 26 项。
- [ ] **`LaunchCommand.spawnCommand` 的 Windows `.cmd/.bat` 包装与 `ConfigRepository.setPosix600` 在 Windows 的 runCatching 静默略过**（jvm11/Windows 边界）：Windows 真机验证批次顺带核对。
- [ ] **多实例持久化账本文件锁竞争**（第二个 appId 同写同一 `dataRoot`）：等真出现第二渠道/第二 appId 时与「账本命名空间」一起设计，现在做是零读者设计。
- [ ] **`FeatureTaskViewModel` 等页面任务模型的统一抽象**：三层证伪成立（驱动模型不相容 / `TaskStatus` 字面值冻结在存量 `audit.jsonl` / jvm11-`HandlerOutcome` 与 jvm21-`TaskUi` 跨 jvmTarget 结构性不可能）——**永久不做**，登记在案防再议。
- [x] **tab 模型重划（已销账，2026-09-26 维护者拍板 + 落地 ac76bb3）**：设置/AI 设置/MCP/调试经 `SINGLE_SESSION_PAGES` 移出 tab 集——「不会多开的页不占 tab 位」；tab 集收敛为纯文档（报告+五件套深入模式）。
- [x] **设置页堆积重构（已销账，SYS-01 双栏化落地）**：左分类导航双栏已落地——`ui/widget/SettingsTwoPane.kt` 单源组件，`PageSettings.kt`/`AiSettingsPage.kt` 消费（SettingsPageCopyGuardTest 在案守护）；原「仍欠」表述过时，勾销。
- [ ] **`LegacyConfigImporter` 于 1.1.0 后删除**（`feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/LegacyConfigImporter.kt`）—— T15 旧 35 key 一次性导入的宿主侧编排，导入标记 `lark_bridge_legacy_imported` 置位后即为死代码。到期后连同 `BridgeStore.ensureLegacyMigrated()` 调用点一并移除。
- [x] **manual/ 用户手册批**（已销账，2026-10-11 全量评审修复批消费——manual/ 属 user-manual skill 管辖，本知识库不代改）：重设计批 11 用户可感知变化已全量同步——TL-07（「Tag 名称」→「输出标记」三面同词）、TL-05（四页空态文案「还没有××任务」）、TL-06（任务卡 ⋯ 菜单钮与右键同源条目）、TL-09（「当前时间」卡折叠化）、TL-11（「深度分析」钮扩至 XLog/Tag 过滤/文件分割三签，分割签投全卷）、XA-19（对话框次钮统一「取消」）、XA-13（时间戳 Enter 立即求值）七处逐项落进对应篇目；同批销账：全册入口心智由「左侧分组导航」统一改 dock 五空间/系统区/Ctrl+K（含复核追加的 lark-bridge.md「左侧飞书桥接」与 manual/index.md「左侧导航」两处），D3 debug.md「页面顶部返回钮」已随单开系统页导航对齐退役、手册改「经 dock/命令面板切走即离开」（该漂移曾漏登本清单，此处补登并同批销账）。
- [x] **在途变更 add-zcode-agent-backend 已随 CLI 委托链退役销账**：zcode 作为 CLI 后端的实现（`BackendAgentRunner` 的 zcode 分支 / `BackendAvailability` / `ZcodeEnvelopeParser` / `agent.backend` 值域项）与整条委托链一并删除，变更目录与验收欠账（E1 安装位入表 / E2 真机两轮对话 / C2 措辞对齐）**失去对象**——不再归档、不再验收。[pages/lark-bridge.md](pages/lark-bridge.md) 的 zcode 专属语义节已同步删除（现网无任何外部 CLI 后端）。
- [x] **桥接 Koog 端点轮询收口（已最终销账，三个子项全部落地）**（`feature-larkbridge/.../page/larkbridge/LarkBridgeManager.kt` 刷新回路）：回路行为**运行态分档**保持原样——桥接运行期 1Hz 热更、未运行态每 `KOOG_ENDPOINT_IDLE_REFRESH_TICKS` 拍约 30s 一次；回路职责已收敛为**端点快照单一职责**（私钥每拍并列刷职责移除）。**收口明细**：① onStart/onProbe 预检点按需 `refreshEndpoint`（单次 DataStore 读，`LarkBridgeViewModel` 在读 `probeEnvironment` 前经 `BridgeStore.refreshEndpointSnapshot` → `LarkBridgeManager.refreshKoogEndpointSnapshot` 现刷）——启动预检（端点硬拒绝门）与环境预检 banner 两个消费面的「桥接停着改端点后立即启动」陈旧误拒窗口归零；② 驾驶舱第 0 步/缺端点提示条的 `aiEndpointReady` 订阅化——`KoogAgentRunner.refreshEndpoint`（快照单写点）经 `onEndpointReadyChanged` 回调同步发射 `LarkBridgeManager.koogEndpointReady` StateFlow（装配期种子刷/分档回路/预检现刷共用同一发射口），经 `BridgeStore.aiEndpointReady` → `LarkBridgeViewModel.aiEndpointReady` 暴露、页面 collectAsState——端点配置变更随下一次快照刷新即时重组，组合期同步读快照的窗口归零（快照新鲜度仍由分档回路承担）；③ 私钥侧事件化——:core `LocalDataManager` 增公开 `valueFlow(key)` 变更流读口（`dataStore()` 为 internal 不可跨模块 collect；流**直读 DataStore 落盘真源、不经** getValue 内存缓存，一致性边界见其 KDoc），`DecryptTools.defaultPrivateKeyFlow` 暴露 `xlog_private_key` 变更流；`LarkBridgeManager` 的宿主私钥回落缓存改由 `HostXlogKeyCache` 事件化维护——装配期 `seed()` 单次挂起读（回落缝首帧同步读契约）+ 装配点挂 gatewayScope collect 变更流（shutdown 取消作用域即收口；collect 异常 warn 后 1s 退避重试），私钥保存即发射即写穿回落缓存，空闲态 ≤30s 陈旧窗口归零。事件源口径：私钥真源是 DataStore 的 `xlog_private_key`（编辑入口在 XLog 解密页），与 bridge-config.json 保存链是两条写链。
- [x] **7z 解压压缩比闸条件项**（已销账，2026-10-11 集中维护窗口落地）：整容器压缩比闸接入 `ExtractGates`（累计解出量÷容器字节、10MiB 落盘后起查，仅 7z 路径传入容器字节）——7z 恢复三闸语义（条目数 10 万 + 累计 2GiB + 整容器比 200:1），配真实 7z 炸弹用例实证中止。剩余可选项：条目级运行时压缩字节（commons-compress `SevenZFile.getStatisticsForCurrentEntry()`，public）收益边际；commons-compress 升级时核对 `getCompressedSize()` 可见性（公开则可直接换逐条目取数）。
- [x] **设备观测空间用户手册**（已销账，2026-10-14 P128 docs-sync 集中批——蜂群裁决 do 授权执行）：新篇 [manual/device-obs.md](manual/device-obs.md)——四子签（观测台/文件/应用/布局）+ 转无线 + 复现记录一键三采 + 投屏（画质档位/快照/录屏）+ 性能（多设备曲线/CSV 导出）+ 按键遥控；同批 manual 六面：logcat 查询语法/武装捕获/延迟自停/堆栈折叠·崩溃高亮/特征预设（[manual/logcat.md](manual/logcat.md)）、工具页 9→13 签七小件新篇（正则/JSON/Base64/哈希/颜色/JWT/ULID）、日志对比分布对照（[manual/log-diff.md](manual/log-diff.md)）、工作台证据 chip/存案/查看器书签·小地图·run 持久化（[manual/log-workbench.md](manual/log-workbench.md)）、Smart Detection 剪贴板识别（[manual/index.md](manual/index.md)）。
- [ ] **设备观测空间施工面专篇**（`composeApp/.../page/deviceobs/`：DeviceObsPage 四子签宿主聚合 / 复现记录编排器 / 文件·应用·布局三 VM / PerfPanel 会话表 / ReproRecord 三成员装配）：用户面已由 [manual/device-obs.md](manual/device-obs.md) 覆盖，开发者施工面（编排器契约/VM 单源上提配方/场景守护注入缝）无专篇——涉及时直接读源码。

---

## 源码根路径

```
composeApp/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/
```

以下文档中的所有文件路径均相对于此根路径。
