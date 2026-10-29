文件：`feature-quest/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/quest/`（本模块根）
     `core/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/llm/tools/AskUser*.kt`（ask_user 工具域）

# 会话域（:feature-quest——Quest）

> 2026-09-20/21 Quest 域六 change 落地（openspec add-flow-*/add-distill-output-viewer/
> reword-remove-vehicle-terms/add-voice-live-recording-vad 全归档）。维护者的差异化
> 设想：**agent 主动向用户索要信息**（反转「人发指令」），市面未见的一等交互原语。
>
> 称谓（AG-01 · 去限定场景语批：「流程域」→「会话域」）：页面聚合签、注册 label、
> 导航语义已全改「会话/会话域」（`AgentCenterPage` 签名「会话」、`ToolPageLabels`
> Flow ->「会话」）；「流程」保留为 palette 检索别名（老用户心智迁移）与代码域称谓
> （KDoc 中「Quest 域/流程域」同义），下文以「会话域」行文，历史记述处（如 openspec
> 归档、引语）按 LB-05 先例保留原文。

## 文件总览

| 文件 | 职责 |
|------|------|
| `QuestSession.kt` | 会话核心（ask 桥 + gate 桥 + ChatMemory 每轮独立 run；reducer 自 2026-09-27 审计批起委托 :core `ChatSessionReducer`——streamingEnabled=true） |
| `FlowViewModel.kt` | 页面 VM（flows 列表 + 两态切换 + 会话流透传） |
| `FlowPage.kt` | 两栏页（左历史 + 主区新建表单/聊天流 + AskCard/PathConfirmCard） |
| `FlowContributions.kt` | 导航注册（AGENTS 组第五成员）+ QuestToolVisualizers 装配 |
| `QuestCapabilities.kt` | 能力目录（硬编码注册表：log-analysis / voice-minutes） |
| `QuestAbilityPrompts.kt` | 能力 prompt 段协作版（2026-09-27 审计批起：日志分析纪律正文直接引 :core `WorkbenchPrompts.disciplineSection(heading="###", subject="本流程")` 单源——「本流程」是 prompt 正文的域自称字面量（LB-05 先例保留），非 UI 称谓；本文件只保留过渡段与工具面；语音纪要段仍自持） |
| `QuestHistoryProvider.kt` | ChatMemory 持久层包装（滤 System + 窗口 40 封顶） |
| `QuestPrompts.kt` | SYSTEM_PROMPT（任务域指令零性格段） |
| `QuestToolVisualizers.kt` | user.ask_user 时间线紧凑态可视化 |
| :core `AskUserModels.kt` | AskField sealed 四型 / AskRequest / AskAnswer / UserQuestionGate / AskCaps |
| :core `AskUserTool.kt` | `user.ask_user` 工具（sink 旁路 + 软反馈三态） |
| :core `QuestStore.kt` + `Flows.sq` | flows 表（schema v4）+ 三店级联删除 |

## 核心架构

### 两层各司其职（历史续聊 = 双轨）

- **ChatMemory 层（续跑真源）**：`koogToolLoopWithMemory(sessionId=flowId)` +
  `install(ChatMemory.Feature) { chatHistoryProvider; windowSize(40) }`——load 非空
  历史整体替换后续跑，「继续执行」的机制本体；
- **UI 时间线层（回看真源）**：messages 表经 `SessionStore.append`（稳态条目）+
  `timeline.replay(id)` 恢复 ChatEntry 列表（工作台 openRun 先例）；
- 🔴 不合成一层：ChatEntry↔Message 映射有损（Activity 无对应物），双写必发散。

### ask 桥（agent 主动提问 → 表单卡）

```
AskUserTool.executeTyped → gate.onAsk(request) 挂起
    → QuestSession.pendingQuestion StateFlow（AskCard 渲染面）
    → 用户提交(resolveAsk)/跳过(skipAsk)/软超时(withTimeoutOrNull(askCapMillis))
    → AskAnswer 软反馈三态（已答/暂无补充/超时——任务不崩，agent 自行调整）
```

- 🔴 askCap 红线：`AskCaps.resolveAskCapMillis(overall) = overall×0.8 钳 [1min,30min]`
  ——软超时永远先于 koogToolLoop 硬超时；
- `ToolContextConfig.userQuestionGate` 槽位（null 不注册——distillBuffer 同款反向门控）；
- `PROMPT_ANSWER_KEY = "answer"`：无 fields 自由文本的约定回填键。

### gate 桥（围栏外路径确认——change 3 补）

fs.extended 读用户口述路径触发 `PathEscapeGate` → `pendingGate StateFlow`
（渲染面 = :ui 共享 `PathConfirmCard`，经 `widget/GateConfirmOverlay` 覆盖层件装配——
2026-10-11 B9 起进出场过渡/末值缓存/组合期抢焦上提共享件，工作台与流程页（会话域
FlowPage）两消费面同一配方；2026-09-27 审计批 HITL 形态统一：同用非模态卡，Enter 允许 /
Esc 拒绝为真实现）→ 允许（登记围栏本会话免确认）/
拒绝（DENIED 软反馈）。
🔴 cancel 同时清 pendingQuestion + pendingGate——表单/确认卡不残留（工作台
既有瑕疵的补齐版）。

### 每轮 send = 独立 koogToolLoopWithMemory

预算按轮重置（超时只死一轮）；工具集 = `extraToolNames + user.ask_user`；
`ToolContextConfig` 透传 privateKeyProvider（勾日志分析才装 analyze_log）+
memoryDir。开工纪律段仅首轮（`firstRound` 标志——续轮不重复注入）。

## 能力目录（硬编码注册表）

```kotlin
object QuestCapabilities {
    val all = listOf(
        QuestAbility("log-analysis", "日志分析", ..., toolNames = /* fs.extended 七件 +
            knowledge 三件 + memory 四件 + 侦察三件 + log 时间过滤两件（filter_by_time/
            filter_by_range）+ log.analyze_log——值同源自持 */),
        QuestAbility("voice-minutes", "语音纪要", ..., toolNames = setOf("fs.extended.read_file")),
    )
}
```

- 加能力 = 加一个注册项（AgentToolCatalog 同款纪律）；
- 🔴 值同源自持：:core 的 EXTENDED/MEMORY 名集是 private 不可 import——目录名是
  稳定契约，`QuestCapabilitiesGuardTest` 钉死 ∈ AgentToolCatalog 可解析面
  （防「列了没注册」漂移——落地时就抓到 knowledge.read_doc→read 真名失真）；
- promptSection 协作版：去「你是一名…专家」人设开头（防与 SYSTEM_PROMPT 双重
  人设），纪律正文逐条保留；
- 通用问答 = 隐式能力（全不选零工具零 prompt 段）。

## 页面两态

- **ListMode**（activeFlow == null）：左栏历史会话列表（HoverableRow 改名/删；运行中点
  历史行 StatusSignals 明示「当前会话运行中，请先中止或等本轮结束后再切换」——不静默
  return）+ 主区新建表单（MultiChoiceField 能力多选 + 目标输入 + 开始钮；busy 接
  `startInFlight`——双击双建会话 UI 半边，VM 侧另有 in-flight 守卫 + flowId UUID 根治
  同毫秒撞 SQLite 主键）；
- **ChatMode**（非 null）：ChatMessageList（贴底跟随 contentVersion = hashCode+size——
  封顶后 itemCount 不再变化，流式增量靠内容哈希触发跟随）+ AskCard（pendingQuestion——
  Composer 上方固定）+ PathConfirmCard（pendingGate，经共享 `GateConfirmOverlay` 装配；
  🔴 **组合即抢焦**——FocusRequester + 组合期 runCatching requestFocus（B9 起内聚在共享
  overlay，FlowPage 侧只传 focusRequester），不抢焦则焦点驻 ChatComposer、Enter 被输入框
  吞掉把草稿当插话入队、Esc 死键，卡面键帽提示成假承诺）+ FileDropZone 附件 + ChatComposer
  placeholder「回答 / 追问」（🔴 边界张力守则：对象绑定页不滑向通用 chat 页；Enter handler
  首行 KeyDown 过滤——被拒路径 KeyUp 重入双回执）；
  「返回新建表单」运行中弹 AppConfirmDialog（文案点明「已排队的 N 条插话一并丢弃
  （不可恢复）」——exitFlow 直接 cancel 会杀运行轮，与同页 deleteFlow 确认卡一紧一松收口；
  空闲时直退零摩擦）；
  AskCard 多字段 Enter 逐字段下移（P1 动线批——仅最后一个可输入字段的 Enter 提交整卡，
  其余字段 Enter = 焦点下移，防误提交）；
- AI 关闭置灰引导（aiEnabled 镜像直读 LlmConfigStore——feature 互不依赖铁律）。

## 会话升格（S5 四补丁 · 2026-09-22）

窗口级 ViewModelStore 驻留（desktop Compose 1.12 + lifecycle 2.11——切页 VM 不
销毁，关窗才 clear）使「升格」收敛为可见性补丁，无需搬家：

1. **S5d 工具卡产物物化**：QuestSession sink 平移 WorkbenchSession artifacts
   十八行配方——Ok.artifacts → ArtifactChip 挂末尾同名工具卡 + 累积
   `evidenceFiles` StateFlow（AskRequestPayload 分支保留——ask 桥依赖）；
   `restore()` 从 ToolCall 卡 chips 重导证据；FlowPage `onOpenArtifact` =
   `DeepAnalyzeHook.offer` → 跳工作台深链；SqliteSessionStore artifacts 落盘
   编码 `path|kind`（🔴 分隔符 `|` 非 `:`——Linux 路径可含冒号；纯 path 旧数据
   解码回落 FILE）；
2. **S5c Steer 排队**：running 期 `send` 不再静默丢弃——入 `pendingQueue`
   StateFlow，收尾（正常/超时/失败三处）drain 自动续发；🔴 `cancel()`
   先清队再 cancel runJob（顺序钉死进测试——取消 = 弃插话）；ChatComposer
   Enter 吞键改 `onQueue` 回调（默认 null 吞键——非会话消费方零变化）；
   Composer 上方「已排队 N 条（本轮结束后自动发送）」提示行；
   🔴 ask_user 挂起期排队合并进表单回答的特例一期不做（二期 executor
   装饰器 interceptModelCall 真插话时一并处理）；
3. **S5a dock RUN 徽标**：QuestSession companion `activity` 镜像
   （`attach(session, scope)/detach()`——WorkbenchSession 配方）+
   FlowContributions 上送 navActivity（同模块直引）；FlowViewModel `enterFlow`
   attach + `onCleared` detach（窗口关窗收镜像）——dock 圆点/状态栏活动段/
   tab 活动点三面自动点亮；
4. **S5b 完成通知**：FlowViewModel 挂 running 下降沿收集——`prev && !cur &&
   !chatVisible`（FlowChat DisposableEffect markActive 自造可见性信号）→
   `:core` `StatusSignals.show("会话「title」已完成")`（StatusHost 状态面
   S5b 起下沉 `core/runtime/StatusSignals.kt`，渲染壳留宿主 StatusHostOverlay）。

## flows 表（schema v3→v4）

```sql
CREATE TABLE flows (
    id TEXT NOT NULL PRIMARY KEY,   -- 三层同 key：messages.session_id = chat_history.conversation_id
    title TEXT NOT NULL,            -- 首条用户消息截 80 字派生（deriveTitle）
    capabilities TEXT NOT NULL,     -- 能力 id JSON 数组串
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
```

🔴 不做 status 列（会话是对话永可续——与 runs「作业有终态」性质不同）。
删除三店级联：事务内 flows + messages + sessions 行（SQLDelight 拆单语句纪律），
chat_history 经 JDBC 宽松清（失败不回滚）。

## 约定与红线

1. 🔴 上限常量已随 DockManager 退役删除（S2 dock 降级静态图标集——
   MAX_DOCK_ITEMS 撞车教训记入 memory，新定制面再议）；
2. QuestHistoryProvider.store **滤 System 消息**（防 ChatMemory load 整体替换时
   system 重复注入——桥接已修坑纪律平移）+ takeLast(40) 窗口封顶；
3. ask_user 紧凑态可视化经 `ToolVisualizerRegistry.register`（FlowContributions
   init 装配——register 是 map put 幂等）；
4. 测试缝：`onAskForTest`/`awaitGateDecisionForTest`/`emitSinkForTest`（internal）
   ——生产路径经 gate/sink 闭包，测试直触（反射拿 private + runTest 组合协程
   首段不执行的实测坑）；
5. 首轮 prompt 组装：能力段拼最前 + 目标 + 开工纪律（≤8 字段一次集中问齐）——
   续轮不重拼能力段（已进 ChatMemory 历史，重拼会重复注入）；
6. S5 排队红线：drain 续发只在收尾三处（正常/超时/失败；CancellationException
   rethrow 不 drain）；cancel 先清队再 cancel runJob——QuestSessionTest 钉死；
7. PathConfirmCard 抢焦装配守护：`FlowPageFocusWiringGuardTest` 双文件锚——FlowPage
   侧钉 FocusRequester remember + 传参共享 overlay（`focusRequester = gateFocus`），
   共享件 `GateConfirmOverlay.kt` 侧钉组合期主动 `runCatching { focusRequester.requestFocus() }`
   ——抢焦配方回退即守护红（2026-10-09 修复批落地，B9 上提后锚随迁共享件）。
