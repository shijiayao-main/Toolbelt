# Design: add-flow-page

> 决策来源：plan v3「实施方案定稿」A/C/D/E 节（已批准）+ 两路探索报告。本 change
> 是页面批——把 change 1 基建接成用户可见面。

## D1 模块与注册

- 新模块 `:feature-quest`（`easydevtoolbox.kmp.feature` convention——voice/mcp 同款；
  必带 `alias(libs.plugins.kotlinSerialization)`？——本模块不用 @Serializable 工具
  Args（ask_user 在 :core），零 serialization 插件需求，不带）；
- 依赖：`:core`（api 传递 Koog/会话基建）；**零 feature 互依赖**（workbench 名集/
  prompts 经值同源自持——既有铁律）；
- 注册触点（探索报告 B 主题 6 清单）：`ToolPage.Flow` 枚举（VoiceTranscribe 后）+
  `FlowContributions`（group=AGENTS，注册序 voice 后）+ ToolPageRegistry 拼接 +
  aiEnabled 宿主 copy 注入（voiceRegistrations 先例）+ NavTest 顺序/分组断言同步。

## D2 QuestSession（WorkbenchSession 五件套骨架平移）

- 构造五件套：scope/configProvider/executorProvider/modelProvider + ledger
  （SqliteSessionStore，sessionId=flowId）；
- **每轮 send = 独立 `koogToolLoopWithMemory`**（sessionId=flowId）——ChatMemory
  挂接经 `install(ChatMemory.Feature)`（KoogAgentRunner.kt:329 先例）+
  QuestHistoryProvider；
- 状态流面：`_running/_entries`（ChatEntry reducer——WorkbenchSession.kt:314-363
  配方平移）+ `_pendingQuestion: StateFlow<AskRequest?>` + `activeFlowId`；
- **ask 桥**（gateBridge 平移 + 两处补强）：
  - `gate = UserQuestionGate { request -> deferred 等待 }`，内部
    `withTimeoutOrNull(AskCaps.resolveAskCapMillis(轮预算))`——超时回
    `AskAnswer(skipped=true)`（AskUserTool 已按 allowSkip 分流超时文案）；
  - 🔴 **cancel() 清 pendingQuestion**（补工作台既有瑕疵）+ 尾随 RUNNING ToolCall
    翻 CANCELLED；
- 工具装配：`AgentToolCatalog.buildRegistry(names = QUEST_BASE_NAMES + ask_user,
  context, sink, config = ToolContextConfig(userQuestionGate = gate))`——
  QUEST_BASE_NAMES 首版 = 空（通用对话零工具；能力名集 change 3 接线）+
  `user.ask_user` 恒注册（gate 非 null）；
- 预算：`LlmConfigStore.getFlowMaxIterations()`（change 1 落的 40 节点）+
  `overallTimeoutMillis = timeoutSeconds × 1000 × 3`（短轮预算——超时只死一轮）。

## D3 QuestHistoryProvider（桥接纪律平移，约 40 行）

`SessionHistoryProvider`（KoogAgentRunner.kt:625-671）是 feature-larkbridge
internal 不可 import——**纪律平移**（非代码平移）：
- store 滤 System 消息（防 ChatMemory load 整体替换时 system 重复注入——桥接已修坑）；
- takeLast(windowSize=40) 窗口封顶（桥接 LRU 换成窗口截断——桌面单活跃流程不需要
  LRU 淘汰，直接读 SqliteChatHistoryProvider 持久层）；
- 持久化委托 `SqliteChatHistoryProvider`（:core 现成件——JDBC 共享 db 文件）。

## D4 页面（FlowPage 两栏 + 新建表单态）

- **ListMode**：左栏 `Dimens.workbenchSidePanelWidth` 宽历史列表（RunHistoryList
  配方：HoverableRow + 状态/时间 + 重命名/删除）+ 主区新建表单（SectionCard：
  MultiChoiceField 能力多选 + CommitTextField 目标 + 开始按钮）；
- **ChatMode**：ChatMessageList（weight 1f）+ AskCard（pendingQuestion 非空时固定
  Composer 上方）+ ChatComposer（placeholder「回答 / 追问」——边界张力守则）+
  FileDropZone 包聊天区（拖入附件 register）；
- AskCard 字段分派：Text→CommitTextField / Choice→ChoiceField / MultiChoice→
  MultiChoiceField / FilePath→文本框 + 拖入（首版不做目录选择器）；
- 时间线内 ask 卡经 `ToolVisualizerRegistry.register("user.ask_user")` 渲染紧凑态；
- aiEnabled：注入后 AI 关闭时新建按钮置灰 + 引导文案（置灰不隐藏——同语义）。

## D5 MultiChoiceField（:ui 新组件）

ChoiceField 的 FilterChip FlowRow 分支（ChoiceField.kt:63-89）平移多选语义：
`selected: Set<String>` + toggle 进出 + OnCheckedChange 样式同款。落 `:ui`
widget/ 供流程页与未来复用（log 多选面板的正式组件化）。

## D6 首轮开工卡（agent 驱动——plan C 节裁定）

首轮 prompt 组装：`目标描述 + 已启用能力清单 + 开工纪律段`（「若关键信息缺失，
先用 user.ask_user 一次集中问齐 ≤8 字段再动工；执行中仅在真正缺料阻塞时补问；
禁反复追问同一信息」）。能力 promptSection 拼接（change 3 落实际能力段）。

## D7 测试策略

- QuestSession 单测（WorkbenchSessionTest 配方平移）：reducer 原位迁移/流式草稿/
  终稿消重 + **ask 桥三态**（resolve/skip/软超时——runTest + advanceUntilIdle）+
  **cancel 穿透**（runJob.cancel 后 pendingQuestion 归 null）+ ChatMemory 续跑
  （inMemory db：store 后 load 非空 + System 滤除）；
- FlowHistoryProvider 单测：System 滤除 + 窗口封顶断言；
- NavTest 顺序/分组断言同步（智能体组五成员）；
- 全模块守护 `--rerun-tasks --no-build-cache`（含 UiGuard 零新增白名单——全 token
  化：左栏宽复用 workbenchSidePanelWidth、间距走 Dimens 既有 token）。
