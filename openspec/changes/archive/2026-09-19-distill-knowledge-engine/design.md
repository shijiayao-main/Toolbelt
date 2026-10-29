# Design

## Context

现状（调研实证，file:line 见本文件下文条目）：

- 知识库 DB 四表 + 检索面已就绪（P3-D）：`KnowledgeRepository` 四表 upsert API、`KnowledgePack.importPack/exportPack`（REPLACE_ALL/REPLACE_PER_REPO，前向版本校验）、`KnowledgeTools` 三件（search/read/lookup_tag，hasContent 空库门控）。
- md 真源格式族已实证可机器解析：知识文档骨架稳定（一级标题 + 元块三行 `> **症状触发词**/关联文档/蒸馏元数据` + `####` 功能分组 + `###` 排查要点决策树）；TAG_REGISTRY 主映射按仓库分节三列表；LOG_ANALYSE_INDEX 症状快查表三列表；kb_scan.py 17 项 CHECK（编号 1-17，含 AVM 专项）。
- agent 平台地基：`AgentToolCatalog` 24 描述符单注册面（新工具 = 加描述符）；`KoogToolLoopRequest`（systemPrompt/maxIterations/事件流三参数化）；`ToolVisualizerRegistry` 零注册全默认卡；`ToolExecutionContext` isAllowed = canonicalFile 精确集合比较（无目录前缀语义）。
- 写入侧缺口：全仓零 write 类工具；`maxIterations` 默认 24 节点（≈12 工具轮）对蒸馏长流程偏紧；多轮记忆只有 prompt 注入（工具观察不进历史）。
- 围栏蓝本：lark-bridge `WorkspacePolicy`（canonicalRoots 前缀比对 + Windows 大小写归一 + ~ 展开 + 不存在路径尾段保留输入拼写）已修过 Windows 大小写坑。

约束：UiGuard token/隔离纪律；`:lark-bridge` 不可依赖（围栏扩展在 :core 平移蓝本逻辑）；知识库内容不内置（App 出厂空库）；桥接链明确不注入源码目录（维护者裁定，出站旁路）。

## Goals / Non-Goals

**Goals**：存量 md 真源一键入库（848 行 + 65 文档）；蒸馏链「计划审批 → 进度可感知 → 审阅卡入库 → 验证面」四段全闭合；源码目录读取免弹窗风暴；蒸馏预算独立可配。

**Non-Goals**：不做 update-alone 自省 agent（golden_samples 只做数据迁移）；不做 DB→md 反向导出器（双形态由 KnowledgePack JSON 导出承载，md 导出后置）；不改桥接链任何围栏行为（隔离是钉死不是改造）；不做 TAG 提取的代码语言感知（首版蒸馏靠 read_file+grep+LLM，语言感知工具后置）；蒸馏会话不做历史持久化/续跑（run 对象化属工作台批）。

## Decisions

### D1 · 导入器落 :core，产出 KnowledgePack JSON，复用 importPack 全链

新 `core/data/db/KnowledgeMdImporter.kt`：解析 md 目录 → 产出 `KnowledgePack` 既有 JSON 结构（schemaVersion=1）→ 走 `KnowledgePack.importPack(REPLACE_PER_REPO)` 落库。**不在导入器内直接写 SQL**——四表写入语义（事务/upsert/repo 边界/版本校验）已有唯一真源，导入器只是「md → Pack」的纯函数 + 校验层。

- **为什么**：导入器只做格式归一，写入面单一真源避免双轨；Pack 格式同时是「用户手工构造知识包」的输入面（md 导入器只是其中一种来源）。
- 校验断言 6 项（kb_scan CHECK 1/3/4/5/14/15 的产品化移植）在**解析期**执行——ParseResult 带错误列表，非空即拒绝产出 Pack（整包事务语义，无半包入库）。

### D2 · TAG 主映射解析为「文档 → TAG 集」，tag_registry 行级展开

TAG_REGISTRY 主映射行（`| 文档路径 | TAG1, TAG2, ... |`）解析为 Pack 的 tags 段（每 TAG 一行：repo/tag/doc_name）；别名表与超长 TAG 节解析为**附注**（knowledge_docs.body 骨架元数据携带），不单独建表——DB 四表 schema 不动（P3-D 既有契约），别名知识经 `KnowledgeRepository` 的前缀变体展开（PREFIXES 既有逻辑）继续生效。

### D3 · distill.* 两件工具：report_step（上报）+ propose_knowledge（暂存）

- `distill.report_step(subject, activeForm, stage, progress)`：READ_ONLY 风险级；agent 每完成一个文件/阶段调用；实现为 ToolResultSink 旁路——结构化数据经 sink 直达运行面板（不占 LLM 上下文），render 文案喂 LLM 作确认。
- `distill.propose_knowledge(kind, title, body, source_refs)`：候选卡**暂存进程内会话态**（不落库不落盘）；kind ∈ {TAG, CONSTRAINT, DECISION_NODE, GOLDEN_SAMPLE}；source_refs 为 file:line 列表（审阅卡 citation 面板消费）。
- **为什么不直接写库**：蒸馏质量铁律 = 不静默入库（plan v2 D4 风险面「幻觉 TAG 在 propose_knowledge 暂存层可拦截」）；暂存层是审阅卡唯一数据源。
- 描述符进 `AgentToolCatalog`（24→26），hasContent 门控复制 knowledge.* 模式——但 distill.* 用**反向门控**：蒸馏页打开（会话建立）才注册，桥接/其他链恒不注册（选择集显式点名，不入 base names）。

### D4 · 围栏目录前缀语义：ToolExecutionContext 新增 allowedRoots 集合

`ToolExecutionContext` 加 `allowedRoots: Set<File>`（默认空集 = 行为零变化）：

- isAllowed 判定扩展为 `exactSet 命中 ∨ allowedRoots 前缀命中`；前缀比对平移 WorkspacePolicy 三件：canonical 化（符号链接 fail-safe）、Windows 大小写归一、`~` 展开。
- **逻辑平移不依赖**：把 WorkspacePolicy.kt:42-49 的大小写归一分支抄进 :core（两处语义必须逐字一致——修复过的坑不在第二处重演），不引 :lark-bridge 依赖。
- HITL gate 语义：目录前缀命中时**跳过 gate**（不弹窗）——信任边界前移到配置时授权（设置页文案明示）；根外路径走既有 gate/拒绝语义不变。
- 🔴 **只上桌面链**：桌面蒸馏链构造 context 时把配置的源码目录 canonical 后注入 allowedRoots；桥接链装配点**恒不注入**（LarkBridgeManager 是桥接链唯一装配缝，加守护测试钉死其 context 不含源码根）。

### D5 · 源码目录配置轨道：LlmConfigStore 单 key JSON 列表

- key `llm_agent_source_roots`（string，JSON 编码 List<String>——KoogUserStyleMap 先例，DataStore 无原生 List<string> 先例）；`SourceRootsStore` 访问器收敛单点（memoryDir 双写漂移教训：现读现拼禁止，经访问器）。
- 桌面链 HOT 生效：蒸馏任务启动时现读（DataStore suspend 读，任务粒度快照——不跨任务缓存，无失效问题）。
- UI：AiSettingsPage 新 SectionCard「源码目录」（TagsField 模式平移：列表 + 逐行删除 + pickDirectory；TagsField 是 feature-larkbridge 私有件 → 组件下沉 :ui 共享，或薄复制——apply 批按 UiGuard feature 隔离规则定夺，倾向下沉）。

### D6 · 蒸馏页架构：对象绑定专用页（三层形态的 agent 会话层显身）

`:feature-ai` 新 `page/ai/distill/`：`DistillPage`（入口表单/运行面板/审阅面三段切换）+ `DistillViewModel`（会话态持有）+ `DistillSession`（KoogToolLoop 装配，AiChatSession reducer 模式——ChatEntry 消息流复用，P4-C1 下沉的 core/llm/agent.ChatEntry 直接可用）。

- **两阶段 prompt**：探索阶段（只读工具集：fs.extended.*/grep/glob + 产计划卡 JSON）与执行阶段（完整工具集 + distill.*）是两次独立 koogToolLoop 调用——不是单次会话切模式（Koog 无中途换工具面能力，两次调用经 sessionId 记忆衔接）。
- **运行面板**：蒸馏专属渲染——逐文件进度（report_step 消费）+ 候选卡实时流（propose_knowledge 消费）+ 既有 ChatEntry 流降级兼容（复用 ChatMessageList 渲染工具调用卡）。
- **审阅卡**：`DistillReviewState`（VM 内 StateFlow：待审/已接受/已驳回三态列表）——接受动作直调 KnowledgeRepository upsert（UI 层入库，不经 LLM）；驳回留痕。
- **注册**：DistillPage 进智能体组（ToolPage.Distill 枚举 + AiContributions 追加注册，group=AGENTS）；navActivity 上送运行态（AiChatHub 死后的新活动流）。
- **验证面 + 管理面**：蒸馏完成卡（实读清单 + hints 命中标注 + 重跑入口）；四表管理面复用记忆面板四动作模式（listIndex→listAll/review→readDialog/edit→upsert/delete）。

### D7 · 蒸馏预算：独立 key `llm_distill_max_iterations`（缺省 60 节点）

LlmConfigStore 新 key（int，缺省 60 ≈ 30 工具轮——SKILL 全模块扫描实证需求 20+ 文件）；蒸馏链构造 KoogToolLoopRequest 时现读；预算耗尽 = Koog 既有 maxIterations 中止路径（TaskError 面板呈现，已上报步骤保全——report_step 面板数据是进程态不受 agent 生命周期影响）。

## Risks / Trade-offs

- **md 解析器的脆弱面**：ai-config 格式族是「自用文档」非严格 schema（表格容差/别名表自由文本）——解析器只认实证骨架（D2 范围），骨架外内容跳过并 warning（不 ERROR）；格式演进由 6 项 CHECK 拦截结构性漂移。
- **桥接隔离的守护成本**：D4 的「桥接恒不注入」目前是装配纪律，加守护测试（BridgeToolRegistry 构造的 context.allowedRoots 恒空集——源码根断言）钉死。
- **蒸馏质量依赖 LLM**：首版无代码语言感知（grep 字面量），TAG 提取靠模型读码——审阅卡 citation 面板是唯一质量闸（用户可核对 file:line）；语言感知提取工具后置批。
- **两次 koogToolLoop 的记忆衔接**：Koog ChatMemory 按 sessionId 挂接（既有 SqliteChatHistoryProvider）——探索阶段的结论（计划清单）经 prompt 显式携带进执行阶段首条消息（不依赖隐式记忆），记忆挂接为增强非依赖。
- **pickDirectory 是 Swing JFileChooser**（:ui 既有公共函数）：无碍。
- **产物落点**：蒸馏候选/报告落 `~/.easy_dev_toolbox/distill/`（应用数据目录），源码目录只读——spec 钉死。

## Migration Plan

1. **契约与围栏地基**：SourceRootsStore + ToolExecutionContext.allowedRoots + 桥接隔离守护测试（编译绿 + 既有围栏测试零回归）
2. **导入器 + CLI**：KnowledgeMdImporter（6 项 CHECK）+ log-tools kb-import 子命令 + 对 ai-config 真源全量导入验收（848 行/65 文档对照）
3. **蒸馏工具域**：distill.* 两件描述符 + 蒸馏专家 prompt 档 + 预算 key（catalog 26 件守护测试更新）
4. **蒸馏页**：DistillPage/VM/Session + 计划卡/运行面板/审阅卡/验证面 + AiContributions 注册（智能体组第二成员）
5. **管理面 + 收口**：四表管理面 + 端到端验收（真源码目录 → 蒸馏 → 审阅入库 → knowledge.search 命中）+ 全模块守护终跑

每步编译绿后推进下一步；1/2 可并行无依赖；4 依赖 1+3。

> 本批预计 2~3 个 apply 会话完成（plan v2 施工总序约束「单 change 一两批内完成」——本批是 P4 最重组件，拆为上表 5 步在 tasks.md 落任务组，每步独立 commit 可中断恢复）。
