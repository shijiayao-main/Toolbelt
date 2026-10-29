# Design: add-flow-capabilities

## D1 能力目录注册项（QuestCapabilities 真身）

- 硬编码 `object QuestCapabilities`（change 2 骨架已建），两注册项：

```kotlin
data class QuestAbility(
    val id: String,          // "log-analysis" / "voice-minutes"
    val title: String,       // "日志分析" / "语音纪要"
    val description: String,
    val toolNames: Set<String>,
    val promptSection: String,
)
```

- **日志分析 toolNames 值同源自持**（WORKBENCH_NAMES 先例纪律——:core 私有常量
  跨模块不可见，目录名是稳定契约）：fs.extended 七件（read_file/grep/glob/
  hash_file/hex_dump/strings/convert_timestamp）+ knowledge 三件
  （KnowledgeTools.KNOWLEDGE_NAMES 是 public 可直接引用？——不可：值同源自持纪律，
  在 feature-quest 写字面量；守护测试兜住漂移）+ memory 四件
  （memory.view/append/replace/delete）+ log.analyze_log。
- **语音纪要 toolNames** = fs.extended.read_file 单件。
- 两能力 toolNames 交集为空（并选无冲突）。

## D2 promptSection 协作版改写（双重人设防线）

两真源都是**独白式完整 system prompt**（「你是一名…专家」开头）——直接拼进
用户消息前会与 QuestPrompts.SYSTEM_PROMPT 形成双重人设。改写纪律：

- 去掉「你是一名…专家」人设开头，换段式过渡：「## 已启用能力：日志分析——本流程
  中涉及日志/故障分析的任务，按以下纪律执行」；
- 保留全部**纪律正文**（十铁律/验证门/知识库消费/报告格式；三段纪要纪律）——
  语义逐条保留，改写风格与 WorkbenchPrompts「语境改写」先例同构；
- 产物命名：`QuestAbilityPrompts.LOG_ANALYSIS_SECTION` / `VOICE_MINUTES_SECTION`
  （新文件，feature-quest 内——feature 互不依赖，不能 import feature-voice 的
  VoiceSummarizePrompts）；落 capability 的 promptSection 字段。

## D3 QuestSession 装配缝扩展

- 构造参数追加：`privateKeyProvider: (suspend () -> String)? = null`（null =
  analyze_log 不装——纯明文分析）、`memoryDir: File? = File(FileUtils.getAppDataDir(),
  "memory")` 默认值同工作台语义；ToolContextConfig 透传两缝。
- 工具集装配：`extraToolNames + setOf(AskUserTool.NAME)` 既有公式不动；extra 由
  VM 侧 abilityOptions 映射（toolNamesFor 已就绪）。
- 🔴 escapeGate 桥（proposal 新定夺项）：QuestSession 的 context 从裸
  `ToolExecutionContext()` 改为 `ToolExecutionContext(escapeGate = gateBridge)`；
  gateBridge = WorkbenchSession.kt:77 同款（pendingGate StateFlow +
  CompletableDeferred + 挂起等 UI + cancel 清理）；FlowPage 加路径确认卡
  （AskCard 同位渲染——pendingGate 非空时输入区上方，允许/拒绝两钮）。
- VM 侧 startFlow 对齐：选了 log-analysis → privateKeyProvider =
  DecryptTools.defaultPrivateKeyProvider；memoryDir 默认值直连。

## D4 能力目录守护测试（QuestCapabilitiesGuardTest）

- 判据：`QuestCapabilities.all.flatMap { it.toolNames }` 每项 ∈
  `AgentToolCatalog.all.map { it.name }`（「列了没注册」零容忍）；
- id 唯一断言 + 两真实 id 存在断言 + promptSection 非空断言；
- 落 feature-quest jvmTest（纯 :core 引用，零 fixture）。

## D5 FlowPage UI 增量

- 新建表单 MultiChoiceField options 直读 QuestCapabilities.all（既有
  abilityOptions 面已就绪——本批真身注入后自动出现 chips，UI 零改动）；
- 引导文案分支（abilityOptions.isEmpty 提示「暂无注册能力」——保留，防未来
  清空态回归）；
- 路径确认卡：新组件 PathConfirmCard（AskCard 同款容器形态：TonalCard + 路径
  文本 + 允许/拒绝 TextButton）——复用 pendingGate 流面，渲染位置与 AskCard
  同区（两者互斥不会同时出现——ask 挂起时无 fs 调用、反之亦然）。

## D6 测试策略

- QuestSessionTest 增量：gate 桥三态（allow 登记围栏 + deny DENIED 软反馈 +
  cancel 清 pendingGate）——WorkbenchSessionTest gate 用例配方平移；
- QuestCapabilitiesGuardTest：目录 ∈ catalog + id 唯一 + promptSection 非空；
- FlowViewModelTest 面不扩（abilityOptions/toolNamesFor 是纯映射，守护测试
  兜住注册面即可）；
- 全模块守护 `--rerun-tasks --no-build-cache` 终跑。

## D7 端到端验收剧本（真机——待已配置的 LLM 端点）

1. 开新流程 → 勾「日志分析」→ 目标「分析 /path/app.log 里 AVM 启动失败」→ 开始
2. agent 开工卡集中问（时间点/症状等）→ 提交
3. 围栏外路径确认卡出现 → 允许 → agent 跑管线/取证
4. 追问一句验证续上下文 → 重启应用 → 历史进入 → 追问验证 ChatMemory
5. 语音纪要：拖转写 md 附件 → 勾「语音纪要」→ agent 出三段纪要
