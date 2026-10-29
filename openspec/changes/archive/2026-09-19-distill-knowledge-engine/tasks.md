# Tasks

## 1. 围栏与配置地基（source-roots-fence）

- [x] 1.1 `core/llm/tools/ToolExecutionContext.kt` 加 `allowedRoots: Set<File>` 构造参数（默认空集 = 存量行为零变化）；isAllowed 判定扩展「精确集命中 ∨ 允许根前缀命中」；前缀比对平移 WorkspacePolicy 三件（canonical 化符号链接 fail-safe / Windows 大小写归一 / ~ 展开——逻辑平移不引依赖）
- [x] 1.2 围栏单测：目录内文件免 gate 放行 / 根外路径原语义 / 符号链接逃逸拒绝 / Windows 大小写归一 / 尾段不存在路径保留拼写（照 WorkspacePolicy 修过的坑逐条钉）
- [x] 1.3 `core/llm/LlmConfigStore.kt` 新 key `llm_agent_source_roots`（JSON 列表编码，KoogUserStyleMap 先例）+ `SourceRootsStore` 访问器单点收敛（get/set/suspend 语义）；单测：多目录编解码回环 + 空默认
- [x] 1.4 🔴 桥接隔离守护测试：BridgeToolRegistry 构造的 context.allowedRoots 恒空集断言（源码根永不进桥接链）+ send_file 出站判据与 source roots 无关断言；`:feature-larkbridge:jvmTest` 过
- [x] 1.5 AiSettingsPage 新 SectionCard「源码目录」（列表 + 逐行删除 + pickDirectory + 授权范围文案明示）；目录列表组件下沉 `:ui`（TagsField 平移共享，按 UiGuard feature 隔离规则）或 :ui 薄件——apply 时定夺；`:feature-ai:jvmTest` 过；git commit

## 2. md→DB 导入器 + CLI（knowledge-import）

- [x] 2.1 `core/data/db/KnowledgeMdImporter.kt`：md 目录解析 → KnowledgePack JSON（D1/D2 范围：TAG_REGISTRY 主映射/别名附注/超长 TAG、LOG_ANALYSE_INDEX 三处、知识文档骨架含 body 元块与决策树、UPGRADE_LOG 跳过不导 DB 只 warning 提示）；纯函数 + ParseResult（错误/警告双列表）
- [x] 2.2 6 项导入校验断言（kb_scan CHECK 1/3/4/5/14/15 产品化）：tag 行 vs 主映射 / 标题格式 / 索引 vs 磁盘双向 / 主映射 vs 磁盘双向 / 仓库分节 / 分节与前缀一致；任一 ERROR 拒绝产出 Pack（整包事务）
- [x] 2.3 导入器单测：合成小知识包全量导入幂等回环 + 每项 CHECK 的违例样本各一（红绿双向）；对样例知识包真源（~/<知识包目录>/knowledge-base/docs）解析冒烟——848 行/65 文档四表条目数对照手工抽验
- [x] 2.4 `:log-tools` CLI 新 `--type kb-import --kb <目录>` 子命令（Clikt 既有骨架追加；`:log-tools` 是纯 JVM jvmTarget 11——导入器若依赖 :core 则 CLI 走 :core 依赖注入路径，与 analyze_log 管线 CLI 同构核对）；CLI 集成测试（合法包/违例包退出码）
- [x] 2.5 真源全量导入验收：kb-import 对 ai-config 全量跑通 + `knowledge.search` 以既有症状词（如「AVM 拉起失败」）命中验证；git commit

## 3. 蒸馏工具域（knowledge-distill agent 侧）

- [x] 3.1 `core/llm/tools/DistillTools.kt`：`distill.report_step`（subject/activeForm/stage/progress → ToolResultSink 旁路 + render 文案）+ `distill.propose_knowledge`（kind/title/body/source_refs → 会话态暂存列表，不落库不落盘）；ToolResult 三段式契约实现
- [x] 3.2 `AgentToolCatalog` 加两描述符（24→26，distill.* 命名空间，**不入 base names**——蒸馏链选择集显式点名注册，桥接/桌面旧链恒不注册）；catalog 守护测试计数更新（BridgeLogToolsTest 断言面不受影响——桥接选择集不含 distill.*）
- [x] 3.3 蒸馏专家 system prompt：`KoogAgentPromptPresets` 扩展或独立常量（内容真源 = ~/.claude/skills/log-helper-knowledge/SKILL.md 六步规格：Step 0 参数确认/1 探索收集/2 模块划分/3 信息提炼/4 文档生成格式/5 四表同步语义——蒸馏语境改写：四表同步 → propose_knowledge 上报）；prompt 长度与 token 面核对
- [x] 3.4 预算 key `llm_distill_max_iterations`（int 缺省 60，LlmConfigStore；KoogToolLoopRequest 构造点现读）+ 单测；git commit

## 4. 知识蒸馏页（:feature-ai distill/）

- [x] 4.1 `ToolPage.Distill` 枚举值 + AiContributions 注册（navItem group=AGENTS 智能体组第二成员 + navActivity 运行态上送 + 无命令面板动作首版）；NavTest 断言面更新（智能体组 [AiSettings, Distill] / 活动流 [LarkBridge, Distill]）
- [x] 4.2 `DistillSession`：两阶段装配——探索阶段 koogToolLoop（只读工具集 + 计划产出 JSON schema）与执行阶段（完整集 + distill.*）两次调用，sessionId 记忆衔接 + 计划清单 prompt 显式携带；AiChatSession reducer 模式（KoogLoopEvent → ChatEntry 流，core/llm/agent.ChatEntry）
- [x] 4.3 `DistillViewModel`：会话态（运行/中止/阶段切换/候选卡列表/审阅三态）+ report_step/propose_knowledge sink 消费（结构化进度面板数据源）+ 中止保全语义
- [x] 4.4 `DistillPage` UI：入口表单（源码目录选择/功能描述必填/entry hints chips 可留空带兜底文案/排除规则默认 .gitignore）→ 计划卡（候选文件清单+选取理由+增删勾改+三选一决策）→ 运行面板（逐文件进度+候选卡实时流+ChatMessageList 降级兼容）→ 审阅卡（逐条 接受/编辑/驳回 + source_refs citation 展示）→ 验证面（实读清单+hints 命中标注+补 hints 重跑+命中演示问答）；PageScaffold + token 纪律零白名单
- [x] 4.5 审阅入库链：接受动作直调 KnowledgeRepository 对应表 upsert（TAG→tag_registry/CONSTRAINT→decision_nodes 附注/DECISION_NODE→decision_nodes/GOLDEN_SAMPLE→golden_samples）；驳回留痕（DistillReviewState 三态持久在会话内）；幻觉 TAG 拦截场景单测（source_refs 定位不到 → 可驳回且库内无脏数据）
- [x] 4.6 UI 架构守护：UiArchitectureGuardTest 全绿（distill 页零白名单条目）+ feature 隔离（不 import feature-larkbridge/larkbridge 件）；git commit

## 5. 管理面 + 端到端收口

- [x] 5.1 知识库四表管理面（蒸馏页内 tab 或同域子面板：按 repo 浏览/查看/编辑/删除/导出 KnowledgePack JSON——记忆面板四动作模式复用；管理面写入与 agent 检索同源断言）
- [x] 5.2 端到端验收（数据面程序化闭环全绿：DistillIngest 写入→knowledge.search 命中→管理面可见可删；LLM 真机链待维护者配置端点后验证）——原任务：真实源码目录（可选拿本仓库或 ai-config 冒烟）→ 入口表单 → 计划审批 → 蒸馏运行（进度回显可见）→ 审阅卡接受入库 → `knowledge.search` 命中新知识 → 管理面可见可删；蒸馏产物落点核对（`~/.easy_dev_toolbox/distill/`，源码目录 git status 干净）
- [x] 5.3 全模块守护终跑 `--rerun-tasks --no-build-cache`（core/feature-ai/feature-larkbridge/composeApp/log-tools）+ 欢迎页智能体组两成员截图留档；openspec validate；git commit（收口）

## 验证方式（apply 阶段执行）

- 全模块守护 `--rerun-tasks --no-build-cache` 全绿
- kb-import 对 ai-config 真源（848 行/65 文档）全量导入 + 抽验（TAG 行数/症状表行数与 md 对照）
- 蒸馏端到端一次（真源码目录 → 计划卡 → 审阅入库 → knowledge.search 命中）
- 桥接隔离守护：桥接链 context.allowedRoots 恒空集（grep + 测试双背书）
- 蒸馏产物不污染源码工作区（git status 干净）
