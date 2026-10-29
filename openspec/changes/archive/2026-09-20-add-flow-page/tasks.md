# Tasks: add-flow-page

## 1. 模块骨架与 ChatMemory 层

- [x] 1.1 `:feature-quest` 模块骨架（settings include + easydevtoolbox.kmp.feature +
      composeApp 依赖）+ `FlowContributions` 占位（保编译绿）
- [x] 1.2 `QuestHistoryProvider`（store 滤 System + takeLast(40) 窗口封顶 + 写穿透
      委托 SqliteChatHistoryProvider）+ 单测（System 滤除/窗口截断/写穿透读回）

## 2. QuestSession 会话体

- [x] 2.1 QuestSession 五件套骨架（构造注入 + `_running/_entries` reducer 面 +
      ChatMemory install + QuestHistoryProvider 挂接）
- [x] 2.2 ask 桥（`_pendingQuestion` StateFlow + CompletableDeferred +
      withTimeoutOrNull(AskCaps) 软超时 + resolve/skip UI 动作面 + 🔴 cancel 清
      pendingQuestion + 尾随 RUNNING 卡翻 CANCELLED）
- [x] 2.3 send 驱动（每轮独立 koogToolLoopWithMemory：sessionId=flowId + 开工纪律
      prompt 组装 + QUEST_BASE_NAMES+ask_user 装配 + B8 落盘 ledger）+ cancel
- [x] 2.4 QuestSession 单测（reducer 迁移/ask 桥三态/cancel 穿透/ChatMemory 续跑
      System 滤除——WorkbenchSessionTest 配方）

## 3. 页面与 UI 组件

- [x] 3.1 `:ui` MultiChoiceField（ChoiceField FilterChip 流平移多选）
- [x] 3.2 FlowViewModel（createFlow/openFlow=QuestStore+replay+newSession/
      resolveAsk/skipAsk/touch 活跃刷新/aiEnabled 镜像）+ QuestCapabilities 空目录
- [x] 3.3 FlowPage 两栏（ListMode：历史列表 RunHistoryList 配方 + 新建表单；
      ChatMode：ChatMessageList + AskCard 字段分派 + ChatComposer placeholder
      「回答 / 追问」+ FileDropZone 附件）全 token 化零白名单
- [x] 3.4 ToolVisualizerRegistry 注册 user.ask_user 紧凑态渲染（等待补充/问答摘要）
      ——QuestToolVisualizers + FlowContributions init 装配

## 4. 导航注册与守护

- [x] 4.1 `ToolPage.Flow` 枚举 + FlowContributions 正式注册（group=AGENTS）+
      ToolPageRegistry 拼接 + aiEnabled 宿主注入 + NavTest 顺序/分组断言同步
- [x] 4.2 全模块守护 `--rerun-tasks --no-build-cache` 全绿（UiGuard 零新增白名单
      + 既有隔离守护全过；DockManagerToggleTest 上限撞车随注册池扩容修复——
      MAX_DOCK_ITEMS 12→16）
