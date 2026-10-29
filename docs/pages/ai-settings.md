# AI 能力域（:feature-ai）

文件（`feature-ai` 模块根 `feature-ai/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/ai/`）：

| 文件 | 职责 |
|------|------|
| `AiContributions.kt` | 导航注册出口（dock 系统区 + palette；AGENTS 组） |
| `AiSettingsPage.kt` | AI 设置页（SYS-01 双栏四分类 + 五个对话框浮层） |
| `AiSettingsViewModel.kt` | 页面 VM（配置读写 + 连通性探测 + 模型目录 + 记忆快照） |
| `LlmModelCatalog.kt` / `ModelCatalogList.kt` / `ModelIdDropdown.kt` | 模型 ID 拉取/静态表/下拉选择（Koog 1.2.0 `models()` 静态表零能力——见 memory koog 接入批） |
| `AiToolsToggle.kt` | AI 工具开关三态逻辑（`llm_ai_enabled`——未配置时开关不可用，配置好自动可用） |
| `distill/DistillPage.kt` | 知识蒸馏页（四阶段闭环 + `DistillPageContent` 无头变体供智能体中心内嵌；管理面已拆出——见下 `KbManagePanel.kt`） |
| `distill/KbManagePanel.kt` | 知识库管理面（`KbManagePanelContent` 仓库四表浏览 + REVIEW 一键看 + `kbRepoBrowse*` 纯函数——2026-10-11 B7 自 DistillPage 整块平移，蒸馏五阶段主体零扰动） |
| `distill/DistillViewModel.kt` | 蒸馏 VM（Phase 五态 + plan 审批 + 审阅行） |
| `distill/DistillSession.kt` | 蒸馏 agent 会话（探索→计划→提炼→审阅四阶段） |

配套核心层（`:core`，AI 域公共资产 2026-09-19 P4-C1 下沉）：

| 域 | 文件 | 职责 |
|----|------|------|
| 端点 | `core/llm/LlmConfigStore.kt` | LLM 配置仓库（AppDataStore，key 见下表） |
| 端点 | `core/llm/LlmClientFactory.kt` | OpenAI 兼容 client 构造单点 |
| 循环 | `core/llm/agent/KoogToolLoop.kt` | Koog AIAgent 装配 + 事件映射 + 超时 + CE 重抛骨架（纪律单点） |
| 循环 | `core/llm/agent/ChatSessionReducer.kt` | Koog 事件 → `ChatEntry` 时间线归一（三 Session 委托单源，`streamingEnabled` 门控流式） |
| 模型 | `core/llm/agent/ChatModels.kt` | `ChatEntry` sealed 模型族（User/Assistant/ToolCall/Activity/ErrorBanner/TodoBoardSnapshot + ArtifactChip/TodoChip） |
| 围栏 | `core/llm/tools/ToolExecutionContext.kt` | 任务级路径围栏（允许集 + allowedRoots + HITL escapeGate） |
| 工具 | `core/llm/tools/AgentToolCatalog.kt` 等 | 工具域目录（fs.extended / knowledge / memory / distill / ask_user / analyze_log …） |
| 记忆 | `core/llm/tools/MemoryManager.kt` | L2 长期记忆索引读写（与 agent memory.* 工具同源） |
| 会话库 | `core/data/db/` | SessionStore/SessionLedger/SqliteSessionStore/Database（SQLite 会话持久层）+ KnowledgeRepository（知识库四表） |

> AI 总开关语义（P4-C1 后）：AI 能力 = 「本地 agent（Koog）+ OpenAI 兼容端点」。
> 万能解密页已移除；fs.* 工具族 / AgentToolCatalog / KoogToolLoop / SessionStore
> 家族 / memory 域全部下沉 `:core` 休眠复用（桥接调度与桌面 agent 链共用）。

---

## AI 设置页（AiSettingsPage）

**双栏四分类**（SYS-01 · 重设计批 6：`SettingsTwoPane` 承载，六 SectionCard 平移进右舱）：

| 分类 | SectionCard | 内容 |
|------|-------------|------|
| 模型服务 | AI 工具 | `AppSwitchRow`「启用 AI 工具」——开时先跑连通性探测（30s 封顶），失败拒绝开启弹原因；关闭 = 依赖模型服务的能力（飞书智能调度等）不可用，本地工具页不受影响 |
| 模型服务 | 模型服务 | Base URL / API Key（SYS-16 明文切换 + 复制钮；🔴 两钮 revealed = hover **或字段获焦**——`onFocusEvent` 观察，键盘 Tab 到字段即点亮，裸 hoverable 只认鼠标时 eye 零键盘通路）/ 模型 ID（下拉：测试连通后可「拉取模型列表」，网关不支持时手动输入）/ 向量模型 ID（知识库语义检索用，留空走默认+端点不支持自动本地检索）/ 请求超时；「测试连通性」= 普通 completion 探测（探测协程单句柄 + 意图代号——再入按 `probeJob.isActive` 拒绝、拨关取消在途探测、迟到成功只在「仍是本代意图」时落开关，探测 30s 期间拨 OFF 不被迟到回执反向覆写），成功展示应答前 40 字符 |
| 智能体 | 性格模式 | Koog agent 全局回复风格（output style 多档 + custom 自持）；仅作用飞书机器人「智能调度」本地回复；**按用户专属档**在「飞书桥接 → 统计 → 用户详情」配置，未配置用户用全局档 |
| 智能体 | 安全约束 | 安全档位两档（默认/严格——工具面收窄）；改动即时生效（≤1s 免重启：桥接端点刷新回路现读本页配置） |
| 能力授权 | 源码目录 | `TagsField` allowlist——授权桌面 agent（蒸馏等）整目录读取源码（对应 `ToolExecutionContext.allowedRoots`，配置时一次授权免逐文件 HITL 弹窗；🔴 标签行体 focusable——键盘 Tab 到行焦点冒泡使删除 X 显现可达，此前键盘加的 tag 键盘删不掉）；🔴 桌面链专属，**桥接链恒不消费**（源码目录不会因此可发飞书群） |
| 长期记忆 | 长期记忆 | `MemoryManager` 索引概览（首屏 `LaunchedEffect(Unit)` IO 首刷 + 刷新走协程 `withContext(Dispatchers.IO)`——listIndex 是阻塞文件 IO，直调点击 handler 冻结 Main；首刷前不再显示误导性「暂无长期记忆」）+ 「查看」/「管理全部记忆」开 `MemoryManagerDialog`（SYS-02：四动作 查看/编辑/删除/清空 收独立对话框——删除/清空为 L2 确认；编辑「保存」失败卡内 inline error 提示「正文未落盘」，写盘包 IO 协程不再跑 Main）；与 agent memory.* 工具同源读写 `MemoryManager.listIndex` |

- 未配置时页顶 Info 横幅「AI 工具将以本地规则降级运行」（不阻断任何功能）；
- 五个对话框是浮层不进双栏分类（content 尾部组合）；
- 滚动责任：Wide 档 `headerSticky = true` + 右舱局部滚（SettingsTwoPane 内置）；窄窗整页滚。

XA-19（重设计批 11）：全部对话框次钮/关闭动作措辞恒「取消」（design-language §4.2——禁「关闭/不了/返回」混用；含 MemoryManagerDialog）。

### LlmConfigStore key 清单

| key | 类型/默认 | 用途 |
|-----|-----------|------|
| `llm_base_url` / `llm_api_key` / `llm_model_id` | String / "" | OpenAI 兼容端点三件 |
| `llm_embedding_model_id` | String / "text-embedding-3-small" | 向量模型（知识库 RAG） |
| `llm_timeout_seconds` | Int / 60 | 请求超时 |
| `llm_max_iterations` / `llm_distill_max_iterations` / `llm_workbench_max_iterations` / `llm_flow_max_iterations` | Int 各域默认 | Koog 循环预算——🔴 单位 = **节点数**（稳态每工具轮 2 节点；60 ≈ 29~30 工具轮） |
| `llm_source_roots` | List | 源码目录 allowlist（SourceRootsMap 编码） |
| `llm_ai_enabled` | Boolean / false | AI 工具总开关（hasKey 判定「未配置态」三态语义） |

---

## KoogToolLoop（agent 循环骨架）

`core/llm/agent/KoogToolLoop.kt`——AIAgent 装配此前两份（DecryptAgent / KoogAgentRunner）纪律散落，
2026-09-15 S4 收口为骨架：**纪律单点、差异参数化**。三条不可妥协红线：

1. **必须允许传 strategy**——`larkDispatchStrategy()` 的边序对调是 2026-09-14「混合响应工具丢失」
   真机 bug 的直接修复；null = Koog 官方 `singleRunStrategy`；
2. seam `public`（`:core` 之外看不见 internal）；
3. 骨架不接管 ChatMemory 安装（仅 KoogAgentRunner 用——单轮链强行接管会耦合两条链）。

```kotlin
public class KoogToolLoopRequest(
    prompt: String, tools: ToolRegistry, systemPrompt: String,
    strategy: AIAgentGraphStrategy<String, String>?,   // 🔴 红线 1
    maxIterations: Int,                                // 🔴 单位 = 节点数（非轮数）
    overallTimeoutMillis: Long, sessionId: String?,    // ChatMemory 按 runId 挂接；单轮链 null
    onEvent: suspend (KoogAgentEvent) -> Unit,         // Koog 内部协程线程调用——调用方自行线程安全化
)
public suspend fun koogToolLoop(executor, model, request): KoogToolLoopResult
public suspend fun koogToolLoopWithMemory(...)       // 带特性安装钩子变体（多轮链用）
```

- 超时分层：单次调用超时在 ConnectionTimeoutConfig 内建；`withTimeout` 防工具循环整体跑飞；
- CE（CancellationException）原样重抛——取消语义不吞；
- `maxIterations` 耗尽 = 任务失败（agent_max_iterations 专项 catch，JLog 落盘——koog-maxiterations 事故批）；
- 事件归一经 `ChatSessionReducer`（`streamingEnabled` 门控 StreamingFrame——蒸馏链不开流式面）；
- fake executor 测试基座见 `:test-fixtures` `KoogFakeExecutor`（🔴 AIAgent 实调 `ResolvedModel`
  重载，只重写 LLModel 重载会被基类默认实现绕开直挂真 client——emptyMap 必抛
  ModelResolutionException，见 memory koog-fake-executor 批）。

## ToolExecutionContext（任务级路径围栏）

LLM 工具调用的路径参数不可信（幻觉路径 / preview_text 结果回流上下文的间接注入）——
所有文件类工具经围栏校验：

- **允许集**：初始 = `initialAllowed`（单文件任务 = {inputFile}；桥接 = 引用文件 + 下载目录产物）；
  decrypt/extract 产物自动登记；未命中路径 → 工具返回错误文案（LLM 自行调整，任务不崩）；
- **允许根目录** `allowedRoots`（P4-B 蒸馏批）：命中 canonical 前缀直接放行且不走 HITL——
  信任边界从「运行时逐路径确认」前移到「配置时一次授权」；🔴 消费面钉死只有桌面链注入
  （源码目录进桥接允许集 = 整个项目可发飞书群——出站旁路），蓝本 = lark-bridge WorkspacePolicy；
- **HITL escapeGate**（B1）：未命中路径挂起询问用户（桌面 = `PathConfirmCard` 非模态卡
  Enter 允许 / Esc 拒绝；桥接 = 确认卡），批准即临时登记放行；gate 为 null = 直接拒绝；
  取消任务随 Job 取消自动中断（CE 穿透）。

## 会话存储（core/data/db SessionStore 家族）

```
ChatEntry（core/llm/agent/ChatModels.kt，零 Koog 依赖——Koog 事件到 ChatEntry 的映射在 reducer）
  ├─ append 持久化 → SessionStore 接口（SqliteSessionStore 主实现 / SessionLedger 变体）
  │    └─ messages 表（SQLite，sessionId 关联；artifacts 落盘编码 `path|kind`——🔴 `|` 非 `:`
  │        ，Linux 路径可含冒号；纯 path 旧数据解码回落 FILE）
  ├─ 时间线回看 → timeline.replay(id) 恢复 ChatEntry 列表（工作台 openRun 先例）
  └─ 续聊历史 → SqliteChatHistoryProvider（ChatMemory provider 适配）
```

- **双轨不合成**（quest.md 同款纪律）：ChatMemory 层（续跑真源）与 UI 时间线层（回看真源）
  双写——ChatEntry↔Message 映射有损（Activity 无对应物），合成一层必发散；
- `ChatSessionReducer`（2026-09-27 审计批单源）：Workbench/Quest 三 Session 的
  Koog 事件 → ChatEntry 归一收敛为委托单源，`streamingEnabled = true`（蒸馏链显式传 false）；
- `Database`（object）：SQLite 单例装配（`ToolbeltDb`，SQLDelight）。

## 知识蒸馏（distill/）

四阶段闭环（`DistillPageContent` 无头变体供智能体中心第二签内嵌；VM 经 `viewModel{}` 窗口级驻留双形态同实例）：

1. **入口表单**（FORM）：源码目录只读显示（配置真源在 AI 设置页 + 引导跳转；未配置
   Error 横幅 trailing 内联「去 AI 设置」钮——`GoAiSettingsHook` 中立槽，宿主未接线时
   点击 no-op）+ 功能模块描述必填 +
   entry hints chips 可留空（agent 自搜兜底）；表单下方即 **KbManagePanelContent**（AG-15 拆 public、
   B7 起独立文件 `distill/KbManagePanel.kt`——仓库四表浏览 + REVIEW 一键看 + `KnowledgeRepository.tagsOf`）。
   深链直达消费契约：palette「知识库管理」候选行点击 → `KbManagePanelHook`（`:core` 中立一次性
   布尔槽，读即清）置位，消费面双路径收口——① 落签初值承载（面板首次组合
   `LaunchedEffect(Unit)` 经 `consume` 一次取走槽内既有载荷）② 在签订阅（用户已停在蒸馏签时
   palette 浮层 offer，订阅 `pending` 流即时置位展开标记）——同一载荷恰消费一次不迟发；展开
   落地统一由等 `kbStats` 就绪的 effect 承担（展开首仓库浏览面；空库无仓库可展开，载荷就地作废）；
2. **计划卡审批**（PLAN_REVIEW）：候选文件清单 + 选取理由 + 勾选态 → 批准执行 / 重新探索 / 作废
   （AG-09 显式出口，作废回表单内容保留）；
3. **运行面板**（EXPLORING）：report_step 结构化进度（🔴 无 percent 槽——上游负载无百分比
   字段，槽已随批删除）+ 工具调用卡流（ChatEntry 渲染；定高窗 `heightIn(consoleHeightMax)`
   内 LazyColumn 自滚 + `BottomFollowScrollEffect` 贴底跟随——页级 verticalScroll 外层下
   全量 forEach 组合会让新卡恒落视口外）；
4. **审阅卡**（REVIEW）：候选逐条 接受/驳回 + sourceRefs citation（kind 徽章 + 标题 + 正文；
   正文 SelectionContainer 可选中）；
   验证面收尾 =「已产 N 候选 / 已接受 M」。

🔴 **失败也是终态**（2026-10-09 修复批收口）：`DistillSession` 超时/异常/装配失败置
`phaseFailure` 信号，VM watcher 消费——探索期失败报错回 FORM 重试、执行期失败回
REVIEW（已产候选保全进审阅面）；计划 JSON 解析失败同样切回 FORM（不静默楔死
EXPLORING）；运行面板出口钮不随 running 消失（`exitStuckPhase` 滞留兜底：探索回表单/
执行回审阅）；入库失败横幅 `_ingestError` 经 startExplore/restart/abandonPlan 三口清
（不跨轮残留）。

- 蒸馏产出 = 知识库四表（docs/tags/decision_tree/golden_samples，`KnowledgeRepository`）；
  检索走 RAG：向量优先（embedding 已配 + `embed` 能力声明硬检查）→ 本地 bigram 降级
  （`KnowledgeBase`，详见 lark-bridge.md 降级表）；
- 导入：`KbImportCli` / `KnowledgeMdImporter`（CLI echo 的 ✅/❌ 属 EMOJI 豁免四文件——DS-17）；
- 预算独立 key `llm_distill_max_iterations`；流式关闭（`streamingEnabled = false`）。

---

## 测试面

`AiSettingsViewModelTest`（配置读写/探测/开关三态；含探测迟到回执不覆写用例）、`AiSettingsPageGuardTest`（页面源码锚）、
`DistillViewModelFailureTest`（五路失败全走 FORM/REVIEW——探索失败/执行失败/解析畸形/装配失败/滞留出口）、
`ContentSoftRepoGuardTest`（composeApp arch/——全仓生产源 contentSoft 文字透明合成扫描，
XA-15 CockpitContentSoftGuardTest 模板推广；豁免登记表逐文件带理由成棘轮）、
`KoogToolLoopTest`（继承 `:test-fixtures` KoogFakeExecutor）、`QuestSessionTest`/`WorkbenchSessionTest`
（reducer 消费方）、知识库四表 repository 测试（真库共存断言法——memory p3 批）。
零 LLM / 零网络（接口缝 fake 单测纪律）；真机验收背书连通性。
