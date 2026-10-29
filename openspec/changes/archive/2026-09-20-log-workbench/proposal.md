# Proposal

## Why

P4-C1（三类分组 + 万能解密移除）后，产品的 agent 锚点已收敛为「对象绑定的专用会话页」——智能体组现只有 AI 设置与知识蒸馏，缺**日志分析工作台**这个核心成员（plan v2 P4-C2/C3，总蓝图第 4 阶段剩余主体）。当前状态：

- **确定性管线已建无宿主**：`log.analyze_log`（解密→时间过滤→环境扫描→Tag 过滤四级）在桥接链活跃生产，但桌面侧自万能解密移除后**零 UI 消费**——桌面用户没有任何入口用上 P4-A 建好的管线。
- **run 对象缺位**：日志分析的一次分析（输入参数+证据+报告）是可回看资产；sessions/messages 基建已 earmark 给工作台复用（P4-C1 保留白名单原文「休眠待工作台 run 历史复用」），但 run 元数据/状态/报告标记在现有表是**结构性缺口**（sessions 表只有 id/title/时间戳/tombstone）。
- **agent 研判链缺内容真源的产品化**：log-helper skill 六步流程 + Step 6 十条铁律（157 行）+ 报告五部分规范只存在于 CLI skill，桌面产品侧没有等价能力——工作台内置研判 agent（analyze_log + knowledge.* + fs.extended.* + memory.*）把这套方法论搬进产品。

## What Changes

**新模块 `:feature-logworkbench`**（KMP feature 约定，参照 feature-timestamp 最小配方）+ **智能体组第三成员**（导航注册，ToolPage.LogWorkbench）：

1. **三栏工作台页**（C2）：左栏（run 列表 + 文件/时间点/症状/Tag 结构化输入表单——非自由文本 prompt）｜中栏（证据流：管线阶段锚点 + 日志行级别着色 + 行级展开/±N 行上下文 + 字段一键过滤）｜右栏（报告面板：因果链三段卡 + 假设三态徽章 validated/invalidated/inconclusive——失败假设不抹除 + 引用展开跳中栏日志行）。布局参照 ShellToolsPage 例外先例（弹性中栏 + 固定宽侧栏）。
2. **run 对象持久化**（C2）：一次分析 = 一个可回看 run。**新增 `runs` 专表**（run 元数据结构化承载：输入参数/状态/报告标记——侦察证实 sessions 表是结构性缺口，加列不可行），时间线条目复用 messages 表（per-run sessionId）；run 列表/重命名/删除复用 SqliteSessionStore 管理面模式。
3. **研判 agent 会话**（C3）：WorkbenchSession 对话式单循环（B5 会话模式——escapeGate HITL + 附件渐进 register，非蒸馏两阶段）；工具集 = `log.analyze_log` + `knowledge.*` 三件 + `fs.extended.*` 七件 + `memory.*` 四件 + `write_todos`；研判 system prompt = log-helper Step 6 十铁律 + 报告五部分规范的产品化改写（内容真源 外部 log-helper skill（个人环境，未入库），与 KoogDistillPrompts 平移先例同构）。
4. **深链入口**（B6）：`LogWorkbenchDeepLink` object 单例流（pending 槽模式）——工具页产物（XLog 解密产物等）「深度分析」按钮携参数跳工作台；content 零参契约不动。
5. **报告产物落点**：`~/.easy_dev_toolbox/logworkbench/runs/<runId>/`（报告 md + 结构化 run 元数据 JSON）——延续「报告产物禁落源码仓库」铁律。

## Capabilities

### New Capabilities

- `log-workbench`: 日志分析工作台页——三栏布局/结构化输入/run 对象持久化与回看/证据流交互（行级展开/上下文跳转/字段过滤）/报告面板（因果链+假设三态徽章+引用互跳）/深链入口
- `log-workbench-agent`: 工作台内置研判 agent 会话——工具集装配/十铁律 system prompt/流式进度/多轮追问（报告追问=结果窗次级按钮，无主输入框 chat 形态）

### Modified Capabilities

- `navigation-grouping`: 智能体组从 [AiSettings, Distill] 扩为 [AiSettings, Distill, LogWorkbench]（注册序）；NavTest 断言面同步（navItems 顺序/分组列表/navActivity set/aiEnabled set）
- `runtime-observability`: 无 spec 级变更（工作台复用 JLog 既有规范，不列 delta）

## Impact

- **新增面**：`:feature-logworkbench` 模块（settings include + composeApp 依赖 + featureRegistrations 拼接行）；ToolPage.LogWorkbench 枚举值；`runs` 表（SQLDelight schema v3 迁移）；WorkbenchSession/WorkbenchViewModel/LogWorkbenchPage 三件；WorkbenchPrompts；DeepLink object。
- **改动面**：NavTest 五处精确断言（侦察已逐条定位 :30-40/:71-75/:167/:188/:196-200）；AiToolsToggle 链路零改动（aiEnabled 消费端自动纳新页）。
- **不动面**：PageHomeContent 分组渲染（数据驱动自动适配）；DockManager（MAX_DOCK_ITEMS=12 预留充足）；桥接链（工具集选择集零变化——工作台是纯桌面链消费者）；`source-roots-fence` 围栏（工作台不消费 source roots，日志文件走会话围栏 + escapeGate）。
- **风险**：① UiGuard 对新模块零豁免自动纳管——三栏布局的固定宽度/最小高度须全 token 化（新 token 走 theme.md 登记先例，非裸 dp 白名单）；② feature 隔离——工作台不得 import feature-ai 的 distill 会话件（共享抽象已在 core.llm.agent，KoogToolLoop/ChatEntry 直接复用）；③ `runs` 表迁移须复用 v2→v3 迁移链（migrate 不回写 user_version 的 SQLDelight 坑已有先例配方）；④ observation 2000 字截断对长证据的影响——证据原文走产物文件 + messages 行只存摘要（不放宽截断，证据面归中栏文件流）。
