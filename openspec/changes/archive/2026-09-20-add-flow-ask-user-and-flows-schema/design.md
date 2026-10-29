# Design: add-flow-ask-user-and-flows-schema

> 决策来源：plan 文件 v3「实施方案定稿」节（已批准）+ 三路探索报告。本 design
> 把已裁定的方案落成施工级决策记录，D 编号与 plan 对齐。

## D1 模块与边界

全部改动落 `:core`（llm/tools + data/db）+ `gradle` 零新依赖。**零 UI**——页面批
（add-flow-page）与能力批（add-flow-capabilities）后续落地。桥接链/工作台/蒸馏/
语音/MCP 零改动（ask_user 不入 base names + gate null 不注册的双重门控）。

## D2 ask_user 工具形态（agent 主动提问 HITL 原语）

- **工具名** `user.ask_user`：平台级原语（与 PathEscapeGate 同类）——描述行为而非
  页面；未来工作台追问/蒸馏澄清复用不改名（目录名是稳定契约）。
- **类形态**：`StructuredSimpleTool` 子类（ToolResult.kt:106-129 三段式先例）；
  `@Serializable Args(question, fields: List<FieldArg> = emptyList(), allowSkip: Boolean = false)`；
  FieldArg(id, label, type: String, options: List<String> = emptyList(),
  required: Boolean = true, placeholder: String? = null)。
- **type 校验**：非法 type → `ToolResult.Err(BAD_ARGS)`（DistillTools.kind 校验先例）。
- **四型字段**：TEXT / CHOICE / MULTI_CHOICE / FILE_PATH（🔴 不做 Date——全仓无
  DatePicker）。
- **gate**：`fun interface UserQuestionGate { suspend fun onAsk(request: AskRequest): AskAnswer }`
  ——executeTyped 内 gate.onAsk 挂起（escapeGate 的 suspendCancellableCoroutine
  穿透同款；CE 取消自动传播，AgentToolMiddlewareTest.kt:184 先例钉死）。
- **槽位**：`ToolContextConfig.userQuestionGate: UserQuestionGate? = null`（distillBuffer
  槽位先例）——描述符 factory `config.userQuestionGate?.let { AskUserTool(it, sink) } ?: null`。
- **旁路**：AskRequest 经 ToolResultSink 发 `AskRequestPayload`（ToolPayload 新
  子类，DistillProgressPayload 先例）——UI 渲染表单卡用；LLM 只见回传文案。
- **软反馈**（围栏 DENIED 同哲学——任务不崩）：
  - 已答：`Ok("用户回答：\n- {label}: {value}")` 逐字段清单；
  - skip：`Ok("用户表示暂无更多信息，请基于现有信息继续")`；
  - 超时：`Ok("用户未在限定时间内回答，请基于现有信息继续或先收尾")`。

## D3 等待上限红线（askCap）

🔴 koogToolLoop 的 `withTimeout(overallTimeoutMillis)` 包住整个 run——等人时间计入
硬预算。基建层提供换算函数：

```kotlin
fun resolveAskCapMillis(overallTimeoutMillis: Long): Long =
    (overallTimeoutMillis * 0.8).toLong().coerceIn(60_000L, 30 * 60_000L)
```

消费方（页面批的 QuestSession gate 桥）内 `withTimeoutOrNull(askCap)` 包 gate.onAsk
——软超时永远先于硬超时触发（桥接 resolveConfirmTimeoutUpperBound 纪律本位平移）。
0.8 系数保证：软超时触发后仍有 20% 硬预算余量让 agent 收尾输出。

## D4 flows 表（schema v3→v4）

- `Flows.sq`（Runs.sq:7-17 配方照抄）+ `3.sqm` 迁移（RunsMigrationTest 验收配方）：
  `flows(id TEXT PK, title TEXT, capabilities TEXT, created_at INTEGER, updated_at INTEGER)`
  + insertFlow（幂等 upsert ON CONFLICT）/ allFlows（updated_at 倒序）/ flowById /
  renameFlow / deleteFlowMessages + deleteFlow（级联拆单语句——SQLDelight 多语句
  命名查询只执行首条的既有坑）。
- **不做 status 列**：runs 有 status 因为分析是「作业」有终态；流程是对话永可续，
  运行态是 QuestSession 运行时（页面批落地）非持久元数据。
- **QuestStore 门面**（RunStore 配方平移）：create/listFlows/flowById/rename/delete；
  title 派生 = 首条用户消息截 80 字（SqliteSessionStore.ensureSession 先例）。
- **三店级联**：事务内 flows+messages+sessions（SQLDelight 面）；chat_history 经
  SqliteChatHistoryProvider.deleteHistory（JDBC 面，失败不回滚——宽松语义）。
- 🔴 id 三层同 key：flows.id = messages.session_id = chat_history.conversation_id
  （续跑语义的锚）。

## D5 预算 key

`LlmConfigStore` 加 `getFlowMaxIterations()`（缺省 40 节点 ≈ 20 工具轮——交互轮比
工作台整场分析短）+ 私有 KEY 常量。读写经 :core AppDataStore 单点（既有纪律）。
overallTimeout 换算（timeoutSeconds × 3 轮）属页面批消费面，本 change 只落 key。

## D6 测试策略

- AskUserTool 单测：fake gate 三态（resolve/skip/timeout）+ BAD_ARGS + 取消穿透
  （cancel 后 deferred 收 CE）——AgentToolMiddlewareTest 配方；
- catalog 门控：gate null 不注册 / 点名注册——buildRegistryForTest 配方；
- FlowsMigrationTest：v3→v4 不丢 runs 行 + user_version=4 + 二次打开零重复
  （RunsMigrationTest.kt:17-50 配方）；
- QuestStore CRUD/级联：inMemory db（RunStoreTest 配方）；
- 全模块守护 `--rerun-tasks --no-build-cache` 收口（扫盘型守护防假绿纪律）。
