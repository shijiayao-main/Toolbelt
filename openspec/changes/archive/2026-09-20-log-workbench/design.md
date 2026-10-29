# Design

> 侦察地基：多路只读侦察已核清全部落地面，关键结论引用 `file:line`。
> 本文件把 plan v2 P4-C2/C3 形态 + 侦察事实 + 用户既有拍板合并为可施工决策。

## Context

- plan v2（~/.claude/plans/agent-mutable-matsumoto.md）P4-C2/C3 已定形态：三栏布局/run 对象/阶段条/证据面板基线/报告三态徽章/研判链工具集。本 change 落地它。
- 侦察证实：确定性管线（log.analyze_log）桌面侧「已接线无宿主」；sessions/messages 表 earmark 工作台但 run 元数据是结构性缺口；ShellToolsPage 是多栏布局唯一登记例外；ChatEntry/KoogToolLoop 已在 core.llm.agent 待工作台消费（KDoc 预写）。

## Goals / Non-Goals

**Goals**：三栏工作台页 + run 对象持久化 + 研判 agent 会话 + 深链入口 + 智能体组第三成员。

**Non-Goals**：
- 语音转写（P4-S 独立批）
- MCP 外放（P5）
- 工具页的全面情境按钮铺设（P5·F 批；本批只做 XLog 解密产物一处深链先例）
- source roots 围栏消费（工作台走会话附件围栏 + escapeGate，不消费源码目录——桥接隔离防线不动）

## D1 模块与导航：`:feature-logworkbench` 新模块 + 智能体组第三成员

- **新模块**：`settings.gradle.kts` include + `easydevtoolbox.kmp.feature` 约定（🔴 必须用 `src/jvmMain/kotlin` KMP 形态——纯 JVM lib 形态会撞 ScanSurfaceSelfCheckTest.A 的 lib 清单精确相等断言）。包名 `page.logworkbench`（避开与存量模块跨模块同 relativePath 的守护硬失败）。
- **枚举**：`ToolPage.LogWorkbench`（AiSettings 前——工作台是主力页排智能体组首位？**否**：注册序 = featureRegistrations 拼接序，落 composeApp ToolPageRegistry 里 LogWorkbenchContributions 的位置；产品语义上工作台是日志域主力，拼在 AiContributions **之前**（LogKit 之后），使侧栏序 = …解压、工作台、AI 设置、蒸馏、桥接）。
- **注册面**：`ToolNavItem(page, icon = MaterialSymbols.QUERY_STATS, label = "日志工作台", desc = …)`；`group = AGENTS`；`aiEnabled = AiToolsToggle.enabled`（置灰不隐藏）；`navActivity = WorkbenchSession.activity`（运行态镜像，DistillSession.attach/detach 同构）；首版无 ToolAction（B7 断言维持恒空——动作面 P5 再议）。
- **NavTest 断言面**（侦察逐条定位）：navItems 顺序表（:30-40，LarkBridge 恒最后）、智能体组列表（:71-75 → [AiSettings, Distill, LogWorkbench]……🔴 修正：拼接序决定组内序——若 LogWorkbenchContributions 拼 AiContributions 前，组内序 = [LogWorkbench, AiSettings, Distill]）、navActivity set（:167/:189 → +LogWorkbench）、aiEnabled set（:188 → {Distill, LogWorkbench}）。B7 恒空断言不动。
- 窗口标题零改动（navItem 页自动从 label 派生，App.kt else -> navLabel 兜底）。

## D2 run 对象：新增 `runs` 专表（不改动 sessions/messages）

侦察裁定 sessions 表只有 id/title/时间戳/tombstone——**加列不可行**（schema 迁移复杂度 vs 收益；run 元数据是结构化查询面）。决策：

- **SQLDelight schema v2→v3 迁移**：新表 `runs(id TEXT PRIMARY KEY, title TEXT NOT NULL, log_files TEXT NOT NULL, target_time TEXT, range_minutes INTEGER, status TEXT NOT NULL, report_path TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)`（log_files = JSON 数组；status ∈ running/completed/aborted/failed）。🔴 迁移纪律：`version = 3` + `.sqm` 迁移文件；migrate 不回写 user_version 的坑已有配方（KnowledgeEngineTest v1→v2 先例）。
- **时间线复用 messages 表**：run 的 sessionId = `"run-<UUID>"`，SessionStore.append 既有分型落列全复用（工具调用行/Assistant 报告行/ErrorBanner 行）。🔴 侦察坑位逐一规避：
  - observation 2000 字截断**不放宽**——证据原文归产物文件（时间线行存摘要+产物路径，中栏证据流读文件不读 DB）；
  - run 结束时**显式调 `tombstone` 于下一 run**（不，tombstone 是会话废弃语义——run 之间互不 tombstone，直接新 sessionId）；
  - `load()` 显式调一次（迁移链不自动跑的死线）；
  - 每个新 run 前 `replay(sessionId)` 回填 seq 计数器防撞号？——不需要：新 sessionId 从 1 计数天然安全；同 run 内追加才是既有语义。
- **RunStore 门面**（core/data/db 新文件）：`createRun/listRuns/renameRun/deleteRun/updateStatus/updateReportPath` 直查；管理面模式照 SqliteSessionStore.listSummaries 先例。
- **报告落盘**：`~/.easy_dev_toolbox/logworkbench/runs/<runId>/report.md`（FileUtils.getAppDataDir 同源路径拼接）；runs 表存 report_path。

## D3 工作台会话：WorkbenchSession 单循环（B5 会话模式，非蒸馏两阶段）

- **形态裁定**：对话式单循环——日志分析的对象（文件+时间点）由结构化表单给定，无需「先探索后批准」；附件渐进 register 进会话围栏；escapeGate HITL 弹窗（路径未登记时确认，B5 语义）。**蒸馏两阶段不复用**（那是「计划审批」产品语义，工作台的产物是分析非写入）。
- **构造签名**（DistillSession 同构平移）：`WorkbenchSession(scope, configProvider, executorProvider, modelProvider, privateKeyProvider, ledger, sessionId)`——sessionId = runId（时间线与 run 一一对应）。
- **reducer**：KoogLoopEvent → ChatEntry 平移（ToolCall 原位状态迁移）；🔴 `StreamingFrame` **要消费**（流式打字机——AiChatSession 蓝本的 streaming 草稿+转正模式，蒸馏链忽略它是蒸馏专属决策）。
- **多轮记忆**：`koogToolLoop` + sessionId（runId）——不装 ChatMemory（分析上下文经报告 md 注入 prompt；桥接式 ChatMemory 是长期对话语义，run 是单任务）。
- **预算**：`LlmConfigStore` 新 key `llm_workbench_max_iterations`（缺省 60——研判+报告是长流程，同蒸馏档位；页面校验域 6..120）。🔴 单位=Koog 图节点（≈2 节点/工具轮）——maxIterations 耗尽事故先例。
- **工具集**：`EXTENDED_NAMES(7) + KNOWLEDGE_NAMES(3) + MEMORY_NAMES(4) + PIPELINE_NAMES(1) + write_todos`——经 `AgentToolCatalog.buildRegistry(names…)` 精装（names 显式点名，不入 base names；桥接零影响）。`ToolContextConfig(privateKeyProvider, todoBoard, memoryDir)` 按蒸馏先例注入。
- **进度面**：StreamingFrame 流式 + 工具卡状态迁移（不再造 report_step 旁路工具——工作台进度=运行面板的 ChatEntry 流本身）。

## D4 研判提示词：WorkbenchPrompts（core/llm 独立常量）

- **形态**：照 KoogDistillPrompts 先例——**不并入 KoogAgentPromptPresets**（任务域指令 vs 性格档；systemPrompt = WORKBENCH_PROMPT + 安全档尾部如需）。
- **内容真源**：外部 log-helper skill（个人环境，未入库）（157 行铁律 + 报告五部分 108 行 + 缺失协议 58 行）的产品化改写：
  - ANALYZE_PROMPT：十铁律全文改写为 agent 行为约束（日志出现≠执行成功…多周期竞态）+ Step 6 验证门（方式 A 代码路径/方式 B 日志中间步骤；未过门只能写怀疑点）+ 报告五部分（结论/根因分列/时间线/逐字证据块/元数据）+ 表格-日志配对；
  - 知识库消费指令：先 knowledge.search 症状词 → lookup_tag 反查 → read_doc 加载，再合并 tag_regex 跑管线（Step 4 流程的产品化）；
  - 🔴 不照抄 CLI 专属段落：报告落点规则（7.1）改为产品落点（runs 目录）；grep 命令自检规范保留精神但工具面换成 fs.extended.grep 的参数语义。
- **长度纪律**：与 DISTILL_PROMPT 同量级（<3KB）；铁律全文 157 行不逐字搬——按「agent 执行约束」语境改写压缩（每条铁律 1-3 句），报告规范以结构模板呈现。

## D5 三栏布局与 token 纪律

- **骨架**：照 ShellToolsPage 例外先例——**不套 PageScaffold**（信息密度优先），`Column(padding(space16))` + `Row(weight(1f))`：左栏固定宽（输入表单+run 列表，`width(280.dp)`——🔴 须进 UiGuard 值级白名单或新 token）+ VerticalDivider + 中栏 `weight(1f)`（证据流）+ VerticalDivider + 右栏固定宽（报告，`width(320.dp)` 同前）。**决策：新 token 优先**——`Dimens.workbenchSidePanelWidth`/`workbenchReportPanelWidth` 走 theme.md「有据保留」登记先例（蒸馏批零白名单纯净度目标同款），确需裸 dp 的布局微调再进值级白名单。
- **UiGuard 登记清单**：① theme.md「有据保留」+ 守护值级白名单双登记（新文件零豁免）；② 跨模块 relativePath——`page/logworkbench/` 全新包路径天然避开；③ feature 隔离——不 import feature-ai/宿主包（WorkbenchSession 自建，共享件全在 core.llm.agent）。
- **证据流行渲染**：LogConsolePanel 配方平移（级别四色 token `colors.logError/logWarn/logDebug/logInfo` + Monospace + spacedBy(space2) + LazyColumn(key=seq) + VerticalScrollbarSlot + BottomFollowScrollEffect 贴底）；行级展开用 ToolCallCard 的 AnimatedVisibility(softSpring) 折叠先例。🔴 时间直方图与 ±N 行上下文是本批**新交互面**——直方图 Canvas 照 UsageBarChart 的 rememberChartProgress 入场模式；±N 上下文展开读产物文件对应行段（内存中有界窗口，懒读）。
- **报告面板**：三段卡用 TonalCard + 三态徽章（primary/error/onSurfaceVariant 着色 labelSmall——已证实/已排除/存疑）；引用跳转 = 中栏 LazyColumn `listState.animateScrollToItem`？🔴 animateScrollToItem 被 UiGuard 拦——用 `scrollToItem`（非动画版，合规）。

## D6 深链：pending 槽 object 单例

- `LogWorkbenchDeepLink`（feature 模块内 object）：`MutableStateFlow<PendingInput?>` + `consume()`——`data class PendingInput(logFiles: List<String>, prefillNote: String?)`。
- 消费点：LogWorkbenchPage `LaunchedEffect(Unit)` poll + consume → VM.prefill；**不携带 UI 状态跨页**（只传文件路径等纯数据）。ToolAction 契约明令薄转发进 object 门面——深链是 object 门面模式的合法应用。
- XLog 解密产物「深度分析」按钮：feature-logkit 的产物卡 actions 加情境按钮（🔴 feature→feature import 禁令——经 :ui 层回调接口或宿主接线？**决策：产物按钮不直接 import 深链 object（feature-logkit 不依赖 feature-logworkbench）——在 ToolPageRegistration.actions 面外，走宿主 composeApp 接线**：LogKit 产物卡暴露回调槽（:ui TaskCard 既有 actions 槽），宿主 App 侧接线调 DeepLink + setCurrentPage。若接线复杂度过高，降级方案：本批只做欢迎页/侧栏入口，深链降为 P4-C 内子批。

## D7 验收与守护

- 全模块守护 `--rerun-tasks --no-build-cache`（core/feature-logworkbench/feature-logkit/feature-larkbridge/composeApp/log-tools）。
- 新增守护面：runs 表迁移测试（v2 库升级不丢 sessions/messages + runs 可写）；WorkbenchSession reducer 单测（DistillSessionTest 同款纪律——零 LLM/零网络，事件→条目映射/围栏 HITL/中止保全）；RunStore 管理面单测（create/list/rename/delete 回退）。
- NavTest 同步是硬门槛（五处断言精确集合）。
- 端到端真机验收（拖日志→管线→证据流→报告→run 回看）待维护者配端点后跑；数据面程序化闭环（管线产物挂载/run 落库/报告文件生成）用合成日志样本单测背书。

## Risks / Trade-offs

| 风险 | 缓解 |
|---|---|
| UiGuard 新模块零豁免自动纳管 | 新 token 双登记先例（蒸馏批零白名单配方）；三栏固定宽全部 token 化 |
| runs 表 v3 迁移坑（SQLDelight migrate 不回写 user_version） | KnowledgeEngineTest v1→v2 配方复用 + 迁移测试钉死 |
| observation 2000 截断 vs 长证据 | 证据原文归产物文件，DB 只存摘要+路径（决策不放宽——FTS 检索面不变） |
| feature→feature import（logkit→logworkbench 深链） | 宿主接线方案（D6）；降级路径已备 |
| 桥接零影响 | 工具集 names 显式点名，不改任何 base names；BridgeLogToolsTest 环境无关断言已防回归 |
| maxIterations 单位误读 | 新 key 缺省 60 节点；KDoc 换算注释（≈30 工具轮）照蒸馏先例 |

## Open Questions（施工中可能回访）

- 时间直方图的时间桶算法（固定 60 桶 vs 自适应）——施工时按产物行数定，不阻塞。
- 报告 md 的模板文件化（常量 vs 资源文件）——首版常量，复杂化再抽。
