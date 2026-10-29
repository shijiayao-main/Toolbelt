# Proposal

## Why

P4-A 确定性管线与 P3-D 知识库四表（SQLite）已落地，但知识库是**只读检索面**——写入侧完全缺失：用户的 848 行 md 知识真源（`<知识包目录>/knowledge-base/docs/`：TAG_REGISTRY 226 行 + LOG_ANALYSE_INDEX 441 行 + UPGRADE_LOG 134 行 + LOG_DIR_INFO 47 行 + 65 篇知识文档）进不了 DB，agent 也无法蒸馏新知识入库。知识库不内置进工具（用户个人数据铁律），App 出厂空库 + **导入能力 + 蒸馏能力**是库有内容的唯一两条路——本批补齐这两条路。

## What Changes

- **md→DB 导入器（kb-import）**：解析 ai-config 知识包 md 格式族（TAG_REGISTRY 主映射/别名表/超长 TAG 节、LOG_ANALYSE_INDEX 症状快查表/跨模块组合/全目录、UPGRADE_LOG 台账、65 篇知识文档骨架）为 `KnowledgePack` JSON → `importPack(REPLACE_PER_REPO)` 落库；移植 kb_scan.py 15 CHECK 中 6 项可产品化断言作导入校验；CLI 子命令 `log-tools --type kb-import --kb <目录>` 批处理存量真源
- **蒸馏工具域 `distill.*`（:core）**：`distill.report_step`（结构化进度上报——Claude Code TaskUpdate 模式，运行面板直接消费）+ `distill.propose_knowledge`（agent 产出候选知识卡只**暂存不落库**——审阅卡数据源，拦幻觉 TAG）；「蒸馏专家」system prompt 档（内容真源 = log-helper-knowledge SKILL.md 六步规格）；蒸馏链独立 maxIterations 预算配置
- **围栏目录前缀语义**：`ToolExecutionContext` 新增「允许根目录集合」（canonical 前缀比对，蓝本 = lark-bridge WorkspacePolicy，平移 Windows 大小写归一 + ~ 展开）——蒸馏读全目录不再逐文件 HITL 弹窗风暴；**只上桌面链**（桥接链明确不注入源码目录——出站旁路风险）
- **多源码目录配置**：`LlmConfigStore` 新 key（JSON 列表编码，KoogUserStyleMap 先例）+ AiSettingsPage 新 SectionCard「源码目录」（TagsField 模式：列表 + 逐行删除 + pickDirectory）
- **知识蒸馏页（:feature-ai 新页，智能体组第二成员）**：入口表单（源码目录选择 + entry hints 行内补全 chips + 排除规则默认继承 .gitignore）→ **计划卡审批**（agent 只读探索产出候选文件清单 + 选取理由，用户增删勾改后三选一）→ **逐文件实时回显**（正在读哪个文件/已提取几条 TAG）→ **审阅卡**（候选知识卡逐条接受/编辑/驳回才入库——Devin knowledge 模式）→ **验证面**（实读文件清单 + hints 命中标注 + 一键补 hints 重跑）
- **知识库管理面**：四表浏览/编辑/删除/导出（复用记忆面板四动作模式，挂知识蒸馏页）

**BREAKING**：无（纯新增 capability；蒸馏页注册进智能体组不改既有注册面）。

## Capabilities

### New Capabilities

- `knowledge-import`：md 知识包 → SQLite 四表的导入契约（格式解析/6 项校验断言/导入模式/CLI 批处理）
- `knowledge-distill`：agent 蒸馏链（distill.* 工具域/计划卡审批/审阅卡逐条入库/验证面/预算配置）
- `source-roots-fence`：围栏目录前缀语义 + 多源码目录配置轨道（桌面链限定/桥接链明确排除）

### Modified Capabilities

- `welcome-page`：智能体组成员数变化（知识蒸馏页加入智能体组后组内 2 页）——分组聚合数据驱动，requirement 语义不变（「移除页面后组内条目自动收敛」Scenario 的对称面），spec 无需 delta；仅 NavTest 断言面在 apply 批同步
- `ai-settings-console`：AI 设置页新增「源码目录」SectionCard（agent 域控制台职责扩展）

## Impact

- **模块**：`:core`（KnowledgeRepository/KnowledgePack 邻域新增导入器；ToolExecutionContext 围栏扩展；AgentToolCatalog 新增 distill.* 描述符；LlmConfigStore 新 key）、`:feature-ai`（知识蒸馏新页 + AiSettingsPage 新 SectionCard）、`:log-tools`（CLI kb-import 子命令）
- **依赖**：零新增第三方依赖（导入器 = kotlinx.serialization + 既有 SQLite；围栏 = 平移 WorkspacePolicy 逻辑不依赖 lark-bridge）
- **安全边界**：源码目录 allowlist 只进桌面链 context；`SendFileTool` 侧允许集不并入源码根（桥接出站旁路防线的 spec 级钉死）
- **既有契约**：KnowledgePack schemaVersion=1 不变（导入器产出既有 Pack 格式）；AgentToolCatalog 24→26 描述符（distill.* 两件，knowledge.* 的 hasContent 门控模式复制给蒸馏写入面）
