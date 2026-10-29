# Design

## Context

现状（调研实证，file:line 见会话调研产出）：

- 导航：`ToolPage` 13 值枚举（core/page/ToolPage.kt）+ `ToolPageRegistration` 六扩展点契约（core/navigation/ToolPageRegistration.kt:26-41）+ 宿主 `ToolPageRegistry.featureRegistrations` 拼接；分组是宿主侧 `navSections` 硬编码三组 + `page in listOf(...)` 过滤行（composeApp/page/home/ToolPageRegistry.kt:41-47,119-139）。
- 万能解密：feature-ai `page/ai/decrypt/` 四文件（AiChatPage/AiChatViewModel/AiChatSession/AiChatHub）+ SessionLedger/SessionStore/SqliteSessionStore；`ToolPage.AiDecrypt` 枚举 + AiContributions 注册（AiContributions.kt:49,52-86）。
- 记忆面板：万能解密页头 PSYCHOLOGY 入口 + 四动作弹窗（AiChatPage.kt:83-94,236-381），后端 MemoryManager（core/llm/tools/MemoryManager.kt）与 agent memory.* 工具同源。
- DockManager.MAX_DOCK_ITEMS = 9（host/DockManager.kt:36）恰等于当前注册全量数。
- FileSniffer/FileTypeDecision 在 feature-ai 包内。

约束：UiGuard 守护（PageScaffold/token 纪律/feature 隔离）、NavVisibility/PageUsageTracker 以 ToolPage 为 key、命令面板动作协议经 ToolAction 薄转发。

## Goals / Non-Goals

**Goals**：分组成为注册契约的一等字段（加页免宿主改动）；万能解密整页移除且平台层资产零损失；记忆面板无损迁移；dock 上限不再卡新页。

**Non-Goals**：不新建日志工作台/知识蒸馏页（后续批）；不动 dock/应用中心渲染逻辑；不做情境按钮（P5·F）；不迁移 usageCounts 历史数据（移除页的旧计数 key 保留无害）。

## Decisions

### D1 · group 字段放契约层（core/navigation），默认值 = null 表示结构页

`ToolPageRegistration` 加 `group: ToolPageGroup? = null`（三值枚举放 core/navigation 同包）。null = 结构页（欢迎页/应用中心/宿主设置/调试页），不进分组渲染。

- **为什么 null 默认而非 TOOLS 默认**：结构页（Default/AppCenter/Setting/Debug）天然不属三组，null 让「未声明 group 的注册项自动不进分组」成为安全默认——漏标的页面不会错误地出现在工具组。
- **备选否决**：四值枚举（含 STRUCTURAL）——结构页本来就不该有 navItem 参与分组渲染，多一个枚举值只是把 null 语义换个写法。

### D2 · 宿主 navSections 从「过滤行」变「聚合器」

删除三组 `listOf(...)` 过滤行，替换为按 `registration.group` 聚合的纯函数（`ToolPageGroup.entries.flatMap { registrations.filter { it.group == it } }`），分组卡渲染数据源指向聚合结果。分组显示名（工具/智能体/入口）放宿主映射表（一处）。

- **为什么聚合而非迁移清单**：迁移清单 = 另一份硬编码，违背本批目的。
- 顺序稳定由枚举声明序保证（TOOLS → AGENTS → ENTRY）。

### D3 · 万能解密移除的「删除白名单」

删：`ToolPage.AiDecrypt` 枚举值、AiContributions 的 decrypt 注册（页面/navItem/actions）、feature-ai `page/ai/decrypt/` 的 AiChatPage/AiChatViewModel/AiChatSession/AiChatHub/SessionLedger、classifyWithLlm/localFallbackDecision 判型链、`docs/pages/ai-decrypt.md`。

保留（白名单，测试与装配继续覆盖）：`SessionStore`/`SqliteSessionStore`（挪 core/data/db 或留 feature-ai？——**挪 :core**：工作台 run 历史是它的下一个消费者，feature-ai 内无 UI 后它的宿主是平台层）、`fs.*` 工具族、AgentToolCatalog、KoogToolLoop、memory.*/MemoryManager、AiToolsToggle。

FileSniffer/FileTypeDecision 挪 `core/llm/tools/`（工作台管线判型的消费点在 :core 侧）。

- **为什么 SqliteSessionStore 挪 :core**：它在 feature-ai 里只被将死的 AiChatViewModel 引用；它是 Database.db 的伴生设施（sessions/messages 表访问器），逻辑归属 core/data/db。挪动只改 package 与 import，零行为变化，测试随迁。
- **🔴 ChatEntry 模型家族随迁下沉 :core（实施批补充，2026-09-19）**：初版「SqliteSessionStore 只依赖 SQLDelight 生成类 + Koog Message，零新增依赖」的断言**漏算了 ChatEntry**——SessionStore/SessionLedger/SqliteSessionStore 三文件全依赖 `widget.chat.ChatEntry` 家族（:ui 模块），core→ui 是循环依赖。修正：`ChatModels.kt`（纯数据模型，零 Compose 依赖——唯一 import 是 AtomicLong）整体下沉 `core/llm/agent/ChatModels.kt`（会话域模型随会话存储同层）；widget/chat 六 UI 文件与 feature-ai 消费方 import 改指新包。备选否决：①契约去 ChatEntry 化（照 LedgerMigrator DTO 绕路）——SqliteSessionStore 对 sealed 六子类深度 when 遍历，改造量大且引入第三套模型；②SessionStore 留 feature-ai——工作台（:feature-logworkbench）引用 feature-ai 破坏 feature 互不依赖铁律。

### D4 · 记忆面板迁移 = UI 面平移，ViewModel 挂点换宿主

AiChatPage 的记忆面板代码块（状态变量 + DropdownMenu + 三弹窗，约 150 行）平移到 AiSettingsPage 顶部 SectionCard「长期记忆」（标题 + 管理按钮行）。`memoryDir` 真源改为 AiSettingsViewModel 直接持有（`FileUtils.getAppDataDir()/memory`——与 MemoryManager/agent 装配同一路径，AiChatSession 的双写先例就此消亡）。MemoryManager 零改动。

### D5 · DockManager 上限 9 → 12

注释自证取值理由同步改（原注释绑定「当前注册数」；新值 12 = 现有 9 活动工具页 + 预留工作台/蒸馏/转写 3 页）。上限只挡 dock 定制面，不影响注册面。

### D6 · AiToolsToggle 的 aiEnabled 流消费点处理

万能解密移除后，aiEnabled 置灰流只剩 AI 设置页自身消费（页面本体不受 AI 开关限制——设置页必须随时可进）。AiContributions 里 decrypt 注册删除后，NavVisibility 的 AI 置灰页面集合自动为空（集合由注册表推导，非硬编码）——无需额外清理，但需在守护测试中钉死「AI 置灰面为零」不炸。

## Risks / Trade-offs

- **移除页的 usage 计数残留**：PageUsageTracker 历史里可能有 AiDecrypt 计数 key。保留不炸（枚举删除后该 key 不再被读）；DataStore 里旧 key 属无害残留，不做清理迁移（收益为零）。
- **SqliteSessionStore 挪 :core 的模块依赖**：core 已依赖 koog（ToolExecutionContext 家族）；SqliteSessionStore 依赖 SQLDelight 生成类 + ChatEntry 家族——后者经 D3 补充裁定随迁下沉 :core（widget.chat.ChatModels.kt → core/llm/agent/），下沉后零反向依赖。
- **AiChatSession 的 escapeGate 死参数**（调研发现的既有缺陷：构造参数声明未消费）随整文件删除自然消亡——若有人在移除前引用它不会生效，无迁移负担。
- **分组卡视觉**：三分组卡片文案（「工具」「智能体」「入口」）需过 ui-style-guide 检查清单；欢迎页提示卡既有组件复用，零新组件。
- **测试锚更新**：NavVisibilityTest/PageUsageTrackerTest/UiArchitectureGuardTest 若有 AiDecrypt 锚需同步清理；AiChatSessionTest 等会话域测试随移除删除（SqliteSessionStore 测试随迁 :core 保留）。

## Migration Plan

1. 契约先行：ToolPageGroup 枚举 + group 字段 + 全部 feature Contributions 补 group 声明（编译绿，行为未变——navSections 双轨期）
2. 宿主切换：navSections 过滤行 → 聚合器（欢迎页渲染换真源；行为可观察变化点）
3. 移除万能解密：枚举值/注册/页面文件/判型链删除 + SqliteSessionStore/FileSniffer 挪 :core
4. 记忆面板平移 AiSettingsPage + AiSettingsViewModel.memoryDir
5. DockManager 上限 + 守护测试锚清理 + 全模块回归

每步编译绿后推进下一步；2 与 3 可交换但 3 依赖 1（AiDecrypt 删枚举前 group 声明已就位）。
