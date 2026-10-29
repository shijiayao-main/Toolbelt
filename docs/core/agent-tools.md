# Agent 工具面（AgentToolCatalog 目录 / 路径围栏 / 侦察四件 / 名集装配）

> 源码分布（工具实现全在 `:core`）：
> - **工具目录** `core/llm/tools/AgentToolCatalog.kt`——全仓 agent 工具的单一注册面
> - **围栏** `core/llm/tools/ToolExecutionContext.kt`——任务级路径允许集 + `authorizeWithGate`
> - **结构化基类** `core/llm/tools/ToolResult.kt`——`StructuredSimpleTool` 三段式 + `ToolResultSink`
> - **工具家族** `LogTaskTools.kt` / `AnalyzeLogTool.kt` / `LogStatsDiffTools.kt`（侦察四件）/ `DecryptTools.kt` / `FileTools.kt` / `ForensicTools.kt` / `TimestampTool.kt` / `MemoryTools.kt` / `KnowledgeTools.kt` / `WebTools.kt` / `DistillTools.kt` / `AskUserTool.kt` / `TodoTools.kt` / `WsWriteTools.kt` / `WsRunTaskTools.kt` / `GitReadTools.kt`
> - **兜底** `OutputCapMiddleware.kt`——大结果驱逐 middleware
>
> 路径围栏的用户视角说明见 [pages/ai-settings.md](../pages/ai-settings.md)「路径围栏」节；三消费面
> （工作台 / Quest / 桥接）的页面装配见 [pages/log-workbench.md](../pages/log-workbench.md) /
> [pages/quest.md](../pages/quest.md) / [pages/lark-bridge.md](../pages/lark-bridge.md)；MCP 外放见
> [pages/mcp.md](../pages/mcp.md)。

---

## 工具目录（AgentToolCatalog——全仓唯一注册面）

`object AgentToolCatalog`（`AgentToolCatalog.kt`）。全仓 agent 工具的**单一事实源**：桌面链与
飞书桥接链共用本目录，装配期按消费方需求过滤生成 Koog `ToolRegistry`——「新增工具 = 注册即接入」
的注册面只有这一处，**勿在链侧自建手工 ToolRegistry**（目录外唯一合法补挂件是 `write_todos`，见下表）。

### 描述符与装配

| 成员 | 语义 |
|------|------|
| `AgentToolDescriptor(name, description, risk, aliases, uiHint, params, factory)` | 工具描述符。`factory` 返回 null = 本次装配不注册该工具（门控语义） |
| `Risk.READ_ONLY / Risk.DESTRUCTIVE` | 风险分级：本地权限引擎依据；外放 MCP 时映射为行为注解。🔴 risk 必填、写盘工具必须显式标 DESTRUCTIVE（漏标 = 外放时被当只读放行） |
| `UiHint(title, keywords, description)` | omnibar 命令面板投影；null = 不进命令面板的纯 agent 工具 |
| `ToolContextConfig(privateKeyProvider, todoBoard, fenceHint, memoryDir, distillBuffer, userQuestionGate)` | 装配期注入面：私钥提供者 / 计划板 / 围栏拒绝文案变体 / 记忆目录 / 蒸馏候选暂存 / HITL 提问桥——对应槽位为 null 即该家族不注册 |
| `buildRegistry(selector | names, context, sink, config, middlewares)` | 按谓词或名字集过滤目录装配 `ToolRegistry`（`names` 形态 = 单测/CLI 精装） |
| `resolve(alias)` | 用别名查实名（工具类注册名 → 目录名；MCP 外放前的兼容缝） |
| `all` | 目录全量只读视图（测试/审计用） |

目录纪律由 `ToolCatalogDisciplineTest`（`composeApp/src/jvmTest/.../arch/`）守护：name 全目录唯一、
MCP 标识符字符集 `[A-Za-z0-9._-]`、点分命名空间至少两段、risk 必填且 DESTRUCTIVE 显式。

### 工具全景（38 件：目录 37 + 目录外补挂 1）

「LLM 调用名」= 工具类注册进 Koog registry 的名字（LLM 实际看到/调用的名字，多数家族为裸名）；
「目录名」= catalog 的点分命名空间名，两者经 `aliases` 桥接。侦察四件与 `write_todos` 例外：
调用名与目录名一致 / 无目录名。

| 家族（实现文件） | 目录名 | LLM 调用名 | 风险 | 装配门控 |
|---|---|---|---|---|
| log.* 六件（`LogTaskTools.kt`） | `log.decrypt` | `decrypt_log` | DESTRUCTIVE | `privateKeyProvider` 非 null |
| | `log.filter_by_tag` | `filter_by_tag` | DESTRUCTIVE | — |
| | `log.filter_by_time` | `filter_by_time` | DESTRUCTIVE | — |
| | `log.filter_by_range` | `filter_by_range` | DESTRUCTIVE | — |
| | `log.split_by_size` | `split_by_size` | DESTRUCTIVE | — |
| | `log.probe_file` | `probe_file` | READ_ONLY | — |
| 分析管线（`AnalyzeLogTool.kt`） | `log.analyze_log` | `analyze_log` | DESTRUCTIVE（写产物） | `privateKeyProvider` 非 null（加密形态才需私钥，解密段门控） |
| 侦察四件（`LogStatsDiffTools.kt`） | `log.stats` | `log.stats` | READ_ONLY（零产物） | — |
| | `log.diff` | `log.diff` | READ_ONLY | — |
| | `log.view` | `log.view` | READ_ONLY | — |
| | `log.cluster` | `log.cluster` | READ_ONLY | — |
| fs.* 四件（`DecryptTools.kt`） | `fs.decrypt_file` | `decrypt_file` | DESTRUCTIVE | `privateKeyProvider` 非 null |
| | `fs.extract_archive` | `extract_archive` | DESTRUCTIVE | — |
| | `fs.preview_text` | `preview_text` | READ_ONLY | — |
| | `fs.list_dir` | `list_dir` | READ_ONLY | — |
| fs.extended.* 七件（`FileTools.kt` / `ForensicTools.kt` / `TimestampTool.kt`） | `fs.extended.read_file` | `read_file` | READ_ONLY | — |
| | `fs.extended.grep` | `grep` | READ_ONLY | — |
| | `fs.extended.glob` | `glob` | READ_ONLY | — |
| | `fs.extended.hash_file` | `hash_file` | READ_ONLY | — |
| | `fs.extended.hex_dump` | `hex_dump` | READ_ONLY | — |
| | `fs.extended.strings` | `strings` | READ_ONLY | — |
| | `fs.extended.convert_timestamp` | `convert_timestamp` | READ_ONLY | — |
| memory.* 四件（`MemoryTools.kt`） | `memory.view` | `view_memory` | READ_ONLY | `memoryDir` 非 null |
| | `memory.append` | `append_memory` | READ_ONLY | `memoryDir` 非 null |
| | `memory.replace` | `replace_memory` | READ_ONLY | `memoryDir` 非 null |
| | `memory.delete` | `delete_memory` | DESTRUCTIVE | `memoryDir` 非 null |
| knowledge.* 四件（`KnowledgeTools.kt`） | `knowledge.search_cases` | `knowledge.search_cases` | READ_ONLY | `KnowledgeTools.hasCases()`（案例库空不注册，独立于 `hasContent` 的单项判） |
| | `knowledge.search` | `knowledge.search` | READ_ONLY | `KnowledgeTools.hasContent()`（空库不注册） |
| | `knowledge.read` | `knowledge.read` | READ_ONLY | 同上 |
| | `knowledge.lookup_tag` | `knowledge.lookup_tag` | READ_ONLY | 同上 |
| web.* 一件（`WebTools.kt`） | `web.fetch` | `web.fetch` | READ_ONLY | — |
| ws.* 两件（`WsWriteTools.kt` / `WsRunTaskTools.kt`） | `ws.write_file` | `ws.write_file` | DESTRUCTIVE | `wsWriteGate` 非 null（三闸：WsSessionGate 会话授权 + wsEscapeGate 围栏 + diff 确认卡） |
| | `ws.run_task` | `ws.run_task` | DESTRUCTIVE | `wsRunTaskGate` 非 null（配方枚举 gradle/maven/npm/pnpm + 每执行确认卡 + 行旁路喂看门狗） |
| git 一件（`GitReadTools.kt`） | `git.read` | `git.read` | READ_ONLY | `gitSessionGate` 非 null（log/diff/blame/status 枚举，恒 --no-pager） |
| distill.* 两件（`DistillTools.kt`） | `distill.report_step` | `distill.report_step` | READ_ONLY | `distillBuffer` 非 null；不入任何 base names（点名装配） |
| | `distill.propose_knowledge` | `distill.propose_knowledge` | READ_ONLY | 同上 |
| HITL 提问件（`AskUserTool.kt`） | `user.ask_user` | `user.ask_user` | READ_ONLY | `userQuestionGate` 非 null；点名装配 |
| 计划投影（`TodoTools.kt`） | （无目录条目） | `write_todos` | 零文件 I/O | `todoBoard` 非 null 时经 `ToolRegistry` 直拼补挂（`DecryptTools.buildToolRegistry` 与 `WorkbenchSession.buildRegistry` 两装配点的「裸装配→补挂→装饰」配方）——TodoBoard 是桌面会话件，不进目录 |

knowledge.* 的 `KNOWLEDGE_NAMES` 名集以四个工具类 `NAME` 常量拼装（`KnowledgeTools.kt`，
含 `knowledge.search_cases`——历史案例检索，研判消费流程首步「先案例后文档」），
是跨模块可见的现成名集；memory / fs.extended 的名集在 `:core` 为 private/internal，
跨模块消费方自持同值名集（见下文名集装配）。

时间过滤两件分工：`log.filter_by_time` = 对称窗口（时间点 ±N 整数分钟，引擎
`LogTimeFilter.filterByTime`）；`log.filter_by_range` = 任意起止闭区间（引擎
`LogTimeFilter.filterByRange`，含两端——奇数分钟区间等对称窗口整数分钟口径表达不了的
形状）。`log.filter_by_range` 描述符 uiHint = null：palette 已有同语义带参命令
`tools.slice-time`（宿主 `ParamCommandCatalog.timeSliceCommand`），带 uiHint 会被
executeProjection 投影进命令面板成同能力双入口。

### 旁路与结果面（ToolResult.kt）

- `StructuredSimpleTool<TArgs>` 是全部工具的基类：`executeTyped` 返回结构化 `ToolResult`，
  基类 `execute` 负责三段式（见「三段式纪律」节）。
- `ToolResult.Ok(summary, artifacts, data)` / `Err(code, message, hint)`；
  `ErrCode` 四值 `DENIED / BAD_ARGS / NOT_FOUND / FAILED`。`ArtifactRef(path, kind)` 消灭
  「兄弟文件启发式猜」——产物精确路径由工具显式上报。
- `ToolPayload` 结构化负载：`Paged`（read_file 分页指针）/ `GrepStats` / `DistillProgressPayload` /
  `DistillProposalPayload` / `AskRequestPayload`——新工具按需加子类。
- `ToolResultSink`：结果旁路给程序侧（UI 工具卡/运行面板），默认 `NOOP`。
- `deniedHint`：基类提供的统一围栏拒绝文案（桥接链经 `config.fenceHint` 注入变体）。

---

## 路径围栏（ToolExecutionContext）与 `authorizeWithGate`

`ToolExecutionContext`（`ToolExecutionContext.kt`）。**LLM 的路径入参不可信**——两类风险：
幻觉路径（编造系统敏感路径）与文件内容注入（`preview_text` 结果回流上下文后，恶意日志文本可
诱导后续工具调用任意路径）。围栏语义：

- **允许集**：初始 = 任务引用文件（单文件任务单元素；桥接多文件 = 引用文件 + 下载目录产物）；
  decrypt/extract 的产物经 `register` / `registerArchiveContents` 自动登记（产物可继续被访问）。
- **允许根**（`allowedRoots`）：命中任一允许根（canonical 前缀比对，分隔符边界 + Windows
  大小写归一 + `~` 展开）的路径**直接放行且不走 HITL gate**——信任边界前移到「配置时一次授权
  整个目录」（源码目录场景，防逐文件弹窗风暴）。🔴 消费面钉死**只有桌面链**注入源码根；
  桥接链恒不注入（源码目录进桥接允许集 = 整个项目可被发到飞书群——出站旁路），由
  `allowedRootsCount = 0` 相关守护断言钉死。构造用命名工厂 `withAllowedRoots(...)` 而非
  operator invoke 重载（消 List 重载歧义，防桥接调用点被静默劫持）。
- **`authorize(path)`**：同步围栏校验——未命中返回 null（调用方翻译为 DENIED 文案）。
  合法调用点仅限 `ToolExecutionContext` 自身与测试（守护钉死）。
- **`authorizeWithGate(path)`**：**HITL 单一入口**（生产工具唯一合法围栏调用）——命中允许集或
  允许根直接放行；未命中且配置了 `PathEscapeGate` 时**挂起询问**用户（Compose Dialog /
  飞书确认卡统一接缝），批准 = register + 放行（同路径不再询问），拒绝 = null → DENIED 文案；
  无 gate = 既有拒绝语义零变化（桥接默认形态）。gate 挂起期间任务取消（CE）穿透挂起点自动中断。

### 围栏覆盖守护与路径形参匹配集

`ToolFenceCoverageGuardTest`（`composeApp/src/jvmTest/.../arch/`，扫盘型）把「路径入参必须过围栏」
钉成红构建门禁，四个断言组：

- **A 组**：Args 含路径形参的工具，类体内必须调用 `authorizeWithGate`/`authorize`。
  **路径形参匹配集**（全量盘点推导，勿放宽）= 属性名等于 `path` 或以 `_path` 结尾——现网路径形参
  恰好全部落在此判据内（`file_path` / `path` / `dir_path` / `archive_path` / `old_file_path` /
  `new_file_path` / `log_path`），且无任何非路径属性以 `_path` 结尾；不做「含 path 字样」宽匹配
  （误伤 xpath 类命名）。
- **B 组**：分类完整性——每个被发现的工具必须命中二者之一（调围栏 ∨ 白名单登记理由）；
  白名单条目指向的类被删/改名/迁出后必须同步删条目（防腐化）。
- **C 组**：解析器 + 归一化（剥注释/字符串内容）单元自锁——注释里的 `authorizeWithGate` 文字、
  字符串默认值里的 `file_path` 不判为真实调用/形参。
- **D 组**：`StructuredSimpleTool` 子类只允许出现在 `core/llm/tools`——在 feature/lib 另立工具面
  会同时逃过本守护与 `ToolStructuredDisciplineTest`（围栏 + 三段式纪律双双失明）；确需另立
  工具面必须先改测试锚登记新扫描根。

**无文件入参 / 围栏外合法形态白名单**（`noFileInputWhitelist`，12 项，类名 → 理由）：

| 工具类 | 理由 |
|---|---|
| `ViewMemoryTool` / `AppendMemoryTool` / `ReplaceMemoryTool` / `DeleteMemoryTool` | 记忆域自带围栏：`File(dir, fileName)` 由构造注入的 `memoryDir` 拼出，fileName 是裸文件名非路径（kebab-case/ASCII 正则 + `isInside` canonical 包含性防御），文件系统访问钉死在 memoryDir 内 |
| `ReportStepTool` | 蒸馏进度上报纯展示零文件 I/O，参数只进 sink 旁路与渲染文案 |
| `ProposeKnowledgeTool` | 候选知识暂存内存 buffer 零文件 I/O（sourceRefs 是 citation 展示文本） |
| `ConvertTimestampTool` | 纯时间戳换算零文件 I/O |
| `AskUserTool` | HITL 提问零文件 I/O（字段 type 含 FILE_PATH 但是用户回答的表单域类型，答案回流 LLM） |
| `SearchKnowledgeDocsTool` / `ReadKnowledgeDocTool` / `LookupTagTool` | SQLite 知识库检索/读取/反查零文件系统 I/O（repo/name 是库内标识非文件路径） |
| `WriteTodosTool` | 内存规划板（StateFlow）零文件 I/O，todos 只进任务级 `TodoBoard` |

🔴 扩展契约：新增 `StructuredSimpleTool` 必须先完成分类再落生产代码——含文件入参 → 类体内调
`authorizeWithGate`；无文件入参/围栏外合法形态 → **先**在白名单登记理由**再**落代码（顺序颠倒即
红构建）。扫描数量下限（`MIN_TOOL_CLASSES`）防目录重构后扫描器空转永绿。

---

## StructuredSimpleTool 三段式纪律

Koog `SimpleTool` 的 TResult 被 final 定死为 String，故结构化结果设计为三段式（`ToolResult.kt`）：

```
executeTyped(args): ToolResult   ← 结构化执行体（测试直测缝——单测直接断言结构）
  → sink.onResult(name, result)  ← 旁路给程序侧（UI 卡片/运行面板，先 sink 后 render——同源）
  → result.render(): String      ← 渲染文案喂 LLM（Ok → summary；Err → message + hint）
```

`ToolStructuredDisciplineTest`（`composeApp/src/jvmTest/.../arch/`，扫盘型）守护两条：

- tools 目录下每个 `SimpleTool` 子类必须继承 `StructuredSimpleTool`（防新工具绕过结构化纪律
  直接返回裸 String，产物路径退回「字符串猜」时代）；
- 生产工具的 `executeTyped` 内**不得调用旧 `context.authorize(`**——必须走
  `authorizeWithGate(`（HITL 接缝单一入口）。

---

## 侦察四件（log.stats / log.diff / log.view / log.cluster）与名集单源

`object LogStatsDiffTools`（`LogStatsDiffTools.kt`）：日志侦察四件，研判动线
「统计→下钻→引用 + 聚类定优先级」——几万行日志先看分布（Tag 频次/级别/时间跨度）再定过滤策略；
两份日志先对比找行为差异窗口再下钻；同型异常刷屏先跑 cluster 的 top-N 模板摘要定排查优先级；
定位到行号后按行号引用原文。引擎 = `:log-tools` 纯函数
（`LogStatsAnalyzer` / `LogDiff` / `LogViewer` / Drain 式模板聚类），本层只负责围栏、结果渲染与
输出有界化，四件均只读零产物。

**名集单源**：`LogStatsDiffTools.NAMES`（= `{log.stats, log.diff, log.view, log.cluster}`）是四
目录名的唯一真源；各消费方的名集一律单源引用它，不得自写字面量（防漂移）：

| 消费链 | 名集装配点 | 引用形态 |
|---|---|---|
| 桌面日志工作台 | `WorkbenchSession.WORKBENCH_NAMES`（`feature-logworkbench`） | `RECON_NAMES = LogStatsDiffTools.NAMES` 拼进 WORKBENCH_NAMES（fs.extended 七件 + knowledge 四件 + memory 四件 + `log.analyze_log` + 时间过滤两件 + 侦察四件 + `web.fetch`，共 23 件） |
| Quest 会话域 | `QuestCapabilities.RECON_NAMES`（`feature-quest`） | 同款单源引用，拼进 log-analysis 能力的 toolNames |
| 飞书桥接链 | `BridgeLogTools.RECON_NAMES`（`feature-larkbridge`） | 同款单源引用，并入桥接文件工具选择集（受本消息文件围栏约束） |
| MCP 研判链 | `McpAgentTools.WORKBENCH_NAMES`（`feature-mcp`） | 同款单源引用，拼进 workbench_analyze run 的研判名集（19 件 = 工作台配方去 memory 四件） |

**prompt 引导同步**（工具注册面与提示词引导必须同步，三处先例）：

- `WorkbenchPrompts.ANALYZE_PROMPT`（`:core`，工作台研判执行）——「可用工具」清单列侦察四件
  动线说明（stats/diff/view/cluster 分工与「先案例后文档」检索纪律）；
- `QuestAbilityPrompts.LOG_ANALYSIS_SECTION`（`feature-quest`）——同段纪律正文单源拼装；
- `KoogSystemPrompt`（`feature-larkbridge`）——每行挂 `listed()` 单源派生：注册集非空时只列
  实际注册的工具（提示词与注册表同步省）；🔴 增删该节任何一条工具行必须同步改「以上 N 件读类
  工具」计数句与列出行数（守护测试钉死两者一致）。

MCP 外放（`feature-mcp`）是第四消费面，且是**两个独立名集面**（勿混）：

- 外放注册面 `McpToolRegistrar.MCP_EXPOSE_NAMES`（17 件，自持——feature 互不依赖铁律下
  跨 feature 不可引用工作台名集），装配同样经
  `AgentToolCatalog.buildRegistry(names = MCP_EXPOSE_NAMES, ...)`；
- 研判链名集 `McpAgentTools.WORKBENCH_NAMES`（19 件 = 工作台链配方去 memory 四件——
  🔴 memory.*/write_todos 不进外部 client，是共用 ANALYZE_PROMPT 的仅有两项豁免面）。
  研判 run 与工作台链共用 `WorkbenchPrompts.ANALYZE_PROMPT`，「可用工具」节与名集的
  一致性由 `McpAgentToolNamesGuardTest`（`feature-mcp`）钉死——prompt 增工具行而名集
  未跟上时即红。详见 [pages/mcp.md](../pages/mcp.md)。

### 🔴 输出长度硬上界（真门禁：超限即刷爆 agent 上下文，研判链不可用）

侦察四件自带结构性输出上界（`LogStatsDiffTools.kt` 常量），全部经 `capAt` 兜底——返回长度恒 ≤
上界，**不依赖推导成立**（推导漂移时截断仍生效，单测另以上界字面值独立断言）：

| 工具 | 有界化手段 | 结构性上界 |
|---|---|---|
| `log.stats` | Tag 频次只取前 `TAG_TOP_N` = 30 项、Tag 名截 `TAG_NAME_MAX_CHARS` = 64 字符 | `STATS_MAX_OUTPUT_CHARS` = 4096 |
| `log.diff` | 只渲染**首个** hunk、窗口截 `DIFF_HUNK_LINES_MAX` = 40 行、单行截 `DIFF_LINE_MAX_CHARS` = 200 字符 | `DIFF_MAX_OUTPUT_CHARS` = 16384 |
| `log.view` | 请求钳制 ≤ `LogViewer.MAX_WINDOW_LINES`（引擎上界 5000 行）、单行截 `VIEW_LINE_MAX_CHARS` = 500 字符、正文按 `VIEW_BODY_BUDGET_CHARS` 字符预算逐行收窗（预算尽即停 + 续读指针，不静默丢尾） | `VIEW_MAX_OUTPUT_CHARS` = 16384 |
| `log.cluster` | 模板条数钳制 ≤ `CLUSTER_TOP_N_MAX` = 20、单模板截 `CLUSTER_TEMPLATE_MAX_CHARS` = 200 字符、每模板行号样例只取前 `CLUSTER_LINE_SAMPLES` = 3 个 | `CLUSTER_MAX_OUTPUT_CHARS` = 16384 |

输出锚定引擎既有契约（不静默）：stats 的「未识别」差值按引擎口径明示；diff 的 `degraded`
（编辑距离超 `LogDiff.MAX_ALIGN_EDITS`）恒渲染显式行 + 「差异行数不可信」告警；view 的加密段
拒绝与查看期间漂移失效折叠为 FAILED 文案并附指引。

**全工具兜底**：`OutputCapMiddleware`（`AgentToolMiddleware` 实现）拦在所有工具结果上——超过
`DEFAULT_MAX_CHARS` = 80K 字符时全文落盘 + 原地替换为头尾 5 行预览 + `[N lines truncated, 全文:
<path>]` 取回指针；🔴 驱逐文件经 `context.register` 回允许集（否则取回指针是死路）；不吞
CancellationException；落盘失败保底返回原文。装配经 `decorateRegistry(registry, middlewares)`
逐工具包裹（`core/llm/agent/AgentToolMiddleware.kt`）。

---

## 添加新工具的步骤清单

1. **实现类**放 `core/llm/tools/`，继承 `StructuredSimpleTool<TArgs>`（三段式纪律；写在别的
   模块会同时逃过围栏守护与结构化守护——D 组钉死）。
2. **围栏**：Args 含路径形参（名字等于 `path` 或以 `_path` 结尾）→ 类体内调
   `context.authorizeWithGate(...)`（勿用旧 `authorize`）；无文件入参或围栏外合法形态 → **先**
   在 `ToolFenceCoverageGuardTest.noFileInputWhitelist` 登记理由，**再**落生产代码。
3. **目录注册**：在 `AgentToolCatalog` 的 `catalog` 列表加一行 `AgentToolDescriptor`——name
   点分命名空间 ≥ 两段、MCP 字符集、risk 显式（写盘必标 DESTRUCTIVE）、需要门控时在 factory
   内判 config 槽位返回 null。
4. **名集**：消费方按需把目录名拼进自己的 names 选择集；成家族的新工具学
   `LogStatsDiffTools.NAMES` 先例把家族名集做成本模块常量，跨模块消费方单源引用（勿散写字面量）。
5. **prompt 引导**：注册面变了引导必须同步——`:core` 侧改 `WorkbenchPrompts.ANALYZE_PROMPT`；
   Quest 侧改 `QuestAbilityPrompts`；桥接侧改 `KoogSystemPrompt`（`listed()` 派生 + 计数句同步）。
6. **输出有界化**：返回给 LLM 的文案设结构性上界 + `capAt` 兜底（侦察四件配方）；大结果兜底
   交给 `OutputCapMiddleware`，勿自造截断。
7. **守护锚验收**：`./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache`（扫盘型守护
   铁律，build cache 会假绿）——`ToolFenceCoverageGuardTest` / `ToolStructuredDisciplineTest` /
   `ToolCatalogDisciplineTest` 三守护全绿才算接入完成。
