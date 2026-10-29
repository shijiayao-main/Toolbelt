# 飞书桥接页（LarkBridgePage）

> 用户视角 + 页面架构。施工规格与行为断言以 `openspec/changes/archive/2026-09-10-rewrite-lark-bridge/`（`behavior-contract.md` / `config-domain-spec.md` / `gateway-contract.md`）与源码为准；本文只讲“这个页面是什么、怎么用、由哪些模块拼成”。

🔴 **本页已推倒重写**：旧实现讲“CLI 子进程 + OpenAPI 双通道（长连接轮询/事件回调）”，该架构已**整体消失**。新架构 = 官方 `com.larksuite.oapi:oapi-sdk` 的 `LarkChannel` **SDK 长连接**，配置改为**单一外部 JSON 文档**，Compose UI 是它的编辑器。

---

## 一句话定位

把飞书群/私聊消息桥接到**本地 AI 调度 agent**（`:feature-larkbridge` 的 `KoogAgentRunner`，LLM 端点即大脑）：用户在飞书发消息 → 桥接按路由规则命中 → 未命中消息交给调度 agent 决定直答 / 调本地工具 / 向用户追问 → 结果以流式卡片回发到飞书。附带命令路由、文件消息、访问控制、写操作确认卡等能力。

🔴 **无外部 CLI 后端**：`call_cli_agent` 委托链与 claude/qoder/copilot/zcode/custom 五后端（含 appServer / permission-server 通道）已整体删除——能力面 = 本地 Koog 调度 + 本地工具族（文件处理 / 工作区域 ws·git / 系统诊断 sys·dev / HITL 提问）+ 飞书原生出站。

---

## 模块与依赖方向

```
:composeApp
  └── :feature-larkbridge          UI 编辑器 + 宿主门面（jvmTarget 25，可依赖 :core）
        ├─ page/larkbridge/        LarkBridgePage / ViewModel / 各卡片面板 / LarkBridgeManager（门面）/ BridgeStore / BridgeErrorMapping / LegacyConfigImporter
        │    └── koog/             调度域按职责分文件：KoogAgentRunner（run 编排大脑）/ KoogSystemPrompt（提示词分段 buildKoogSystemPrompt）/ KoogDispatchStrategy（larkDispatchStrategy）/ KoogSessionHistory（SessionHistoryProvider 会话历史）/ KoogBridgeTools（飞书原生出站工具族）/ BridgeToolRegistry（运行时工具注册表）/ BridgeLogTools（文件分析工具集）/ KnowledgeBase（手册 RAG）/ KoogWsGates（工作区 ws·git 会话门控与逐动作确认）/ KoogUserQuestionGate（user.ask_user 提问卡适配）/ KoogSys*·KoogDevSessionGate（sys·dev 域门控）
        ├── :core                  core/llm（LlmConfigStore/LlmClientFactory 端点域 + tools/ 围栏与工具族：ws/git/ask_user 工具本体在此）
        ├── :log-tools             log-tools 直调（decrypt_log/filter_by_tag/filter_by_time/filter_by_range/split_by_size/probe_file 能力面）
        └── :lark-bridge           纯 JVM 桥接内核（jvmTarget 11，不得依赖 :core）
              ├── config/     配置域（BridgeConfig / BridgeFields / ConfigMigrations / ConfigRepository / ConfigValidator / LegacyDocumentMapper / NativeCapability 分级模型单源）
              ├── pipeline/   处理链（HandlerKinds / PipelineSnapshot / Responder / DispatchPayload / NativeChatSurface 渠道中立 L2 接缝）
              ├── handler/    各能力 handler（reply / command / agent / file / audio / ask-answer …）+ AgentPipeline（共享 agent 管线：resolveReferencedPrompt / runAgentPipeline / ReferencedPromptOutcome / toOutcome / 表情信号常量）
              ├── agent/      执行器 seam（AgentRunner 接口 / NoAgentRunner CLI 形态占位 / Artifacts / Progress*）
              ├── confirm/    确认与提问（ConfirmService 状态机 + ToolConfirmGate + GatewayConfirmNotifier 由 BridgeAssembler 内实现 + AskUserBus 提问挂起配对 + RiskClassifier）
              ├── exec/       ProgramLauncher（ProcessBuilder 唯一出现点）/ WorkspacePolicy / SensitiveFilePolicy / ProcessRegistry
              ├── observe/    BridgeStatus / MetricsSampler / LogBus
              ├── files/      DownloadStore / FileRegistry / ReferencedMessageResolver / CardReplyLedger / PreprocessPipeline（解压闸走 `:log-tools` 的 `com.jiaoay.utils.SafeExtract`——zip-slip 双重校验，只许平移）
              └── cli/        独立 CLI 四子命令
                    └── :lark-gateway   飞书 SDK 隔离适配层（唯一允许 com.lark.oapi.*；SdkLarkGateway 类本体 + 尾部纯函数 per-concern 分文件——QuotedContentParsers / HistoryContentParsers / DownloadRangeParsers / ConnectDiagnostics / ContactDiagnostics / SdkCardStreamPort）
                          └── oapi-sdk 2.8.5
```

🔴 **隔离铁律**：SDK 类型只出现在 `:lark-gateway`（由 `LarkSdkIsolationTest` 9 项守护）；`:lark-bridge` 是 jvmTarget 11 纯 lib，零 `JLog`/`JResult`/`JErrorCode`/`AppDataStore`/`FileUtils`/`com.jiaoay.easy_dev_toolbox` 宿主包引用——数据根由宿主 `LarkBridgeManager` 经 `FileUtils.getLarkDir()` 注入。`:feature-larkbridge` 达成 **UI 零白名单条目**（所需尺寸全部走新增 token `consoleHeightMin`/`consoleHeightMax`/`strokeThin`/`progressInline`）。

---

## 配置模型：单一 JSON 文档

- **路径**：`~/.toolbelt/lark/bridge-config.json`（`schemaVersion` + 校验 + `.bak` 单代备份）。
- **写入路径**：UI draft 即时更新 → 400ms trailing debounce → `Mutex` 单写者 → `.tmp` + rename 原子写（`ConfigRepository`）。**无“未保存”状态**，落盘后给瞬时“已保存”提示（H10 sticky 条 / S9 保存按钮判定为语义消失，见 `behavior-contract.md` 脚注）。
- **声明式字段表 `BridgeFields.ALL`（47 字段，7 分组）是单一真源**，派生 UI 渲染 / 校验 / CLI `check` 输出。计数以 `BridgeFieldsTest`（`assertEquals(47, all.size)`）为唯一真源：

| 分组 | 标题 | 字段数 | 默认展开 |
|------|------|--------|----------|
| `CONNECTION` | 连接 | 6 | 是 |
| `ACCESS` | 访问控制 | 1 | 是 |
| `WORKSPACE` | 工作区 | 2 | 是 |
| `RUNTIME` | 运行时 | 7 | 否（高级旋钮） |
| `AGENT` | Agent 与后端 | 14 | 是 |
| `FILE_MESSAGE` | 文件消息 | 9 | 否（高级旋钮） |
| `OBSERVABILITY` | 可观测性 | 8 | 是 |

> 分组表是 **lib 侧 `ConfigGroup` 的声明分组**（供 CLI `check` 全量输出与 tier 元数据）；配置页 UI 渲染走 feature 侧 `P5FieldGroups`（四分区 + 两张独占渲染面，见「页面结构」节）——两套视图同一份 `BridgeFields.ALL`，不互相约束。

- **字段级 tier 分层**：每个字段携带 `tier`（BASIC/ADVANCED，**默认 ADVANCED**——新增字段不标注即进阶）。配置编辑器两段渲染：**基础配置区**（不可折叠）直出 5 个必看字段（`connection.appId`/`appSecret`/`region`、`access.trustedUsers`、`workspace.root`）；**进阶区**内部按 UI 四分区以小节头分节渲染其余 42 个字段。tier 是纯 UI 元数据（不进 JSON、零迁移）；`ConfigCheckPanel` 与 `check` CLI 保持全量输出不分层。

- **🔴 访问控制段 `access`（1 字段，HOT、BASIC）**：
  - `access.trustedUsers`（Tags，默认空）：**信任用户 open_id 名单**。名单内用户的高风险操作免会话域授权；名单外用户遇到越界/破坏性操作**直接拒绝**（不发确认卡——不让陌生人自行批准自己的破坏性操作）。空名单 = 无人免确认（安全默认）。
  - 入站准入**恒全放行**——原 `access.mode` / `allowedSenders` / `blockedSenders` 三字段已随 v12→v13 退役（存量白名单成员平移为信任用户，黑名单与 mode 折 `experimental.retiredV13` 保底）。服务面收口改由**飞书后台的机器人可用范围**承担（平台能力比桥接侧自维护名单更可靠）。
  - 判据单源：`lark-bridge/.../confirm/TrustPolicy.kt`（`isTrusted` / `decide`）。

- **🔴 能力域注册闸已全部退役（v13→v14）**：`agent.sysTools.*`（8）/ `agent.devTools.*`（3）/ `agent.selfStatus.enabled`（1）/ `agent.startupProbe.enabled`（1）/ `agent.wsTools` 的四个开关（4）共 17 个字段整段删除。**工具恒注册，注册面不再有配置闸**（`BridgeToolRegistry` 只在接缝缺席时跳过，见「工具注册分级全景」）。

- **`agent.wsTools` 现只剩 1 个数值字段**（HOT、AGENT 组）：

| 字段 | 默认 | 语义 |
|------|------|------|
| `agent.wsTools.runTaskTimeoutMinutes` | 10（钳域 1..60） | `ws.run_task` 超时上限；**实际生效另受 0.8×整体预算软钳制**（软超时须先于 agent 硬超时触发） |

- **`fileMessage.xlogDecodeEnabled` 已退役（v14→v15）**：xlog/alog 解密**恒启用**，私钥为空时跳过并告警（不再是开关）。

- **配置迁移 `schemaVersion` 现为 15**，近年三步：
  - **v12→v13**：`access` 白名单退役——`allowedSenders` 非空平移为 `trustedUsers`，`blockedSenders`/`mode` 非默认折 `retiredV13`；
  - **v13→v14**：能力域注册闸退役——五域开关整段删除，非默认取值折 `retiredV14`；
  - **v14→v15**：`fileMessage.xlogDecodeEnabled` 退役，显式 `true` 折 `retiredV15`、`false` 静默丢。

  > 🔴 退役必须走迁移**删键**而非只改 typed 默认：`BridgeJson` 开 `encodeDefaults=true`，存量文档必然躺着显式 `"enabled": false`。

- **不入 DataStore**（`AGENTS.md` 约定 2 例外）：DataStore 里只保留一个一次性迁移标记 `lark_bridge_legacy_imported`。

### 权限模型（改造后三层）

```
入站：全放行（服务面收口交给飞书后台的机器人可用范围）
  ↓
域准入：access.trustedUsers 信任名单（一次配置、按人判定、HOT 生效）
  ↓
高风险动作：逐动作确认卡（ToolConfirmGate，防替答 chatId+senderOpenId、超时=拒绝）
  ↓
非信任用户遇越界/破坏性操作：直接拒绝（不发卡）
```

判据单源：`lark-bridge/.../confirm/TrustPolicy.kt`。

实际只有**两档**（不是四级——`TrustPolicy` 只提供 `isTrusted` 二值判定）：

| 面 | 信任用户 | 非信任用户 |
|---|---|---|
| sys.* / dev.* / ws.* **域准入** | 放行 | **拒绝**（进不了域） |
| 写 / 修复 / 服务 / 跑任务**逐动作卡** | 免卡直接执行 | 到不了这层（域已被拒） |
| 跨会话写（`create_group_chat`） | 经确认卡批准 | **直接拒绝**（不发卡） |
| 归档产物出站卡（`[产物]` 打包/超阈值） | 经确认卡批准 | 经确认卡批准 |

> 「非信任用户进不了域」比「能进但逐项拒绝」更简单也更安全——这也是 `RiskClassifier`
> 的四级分级在本链路只剩 `.name` 字符串用途（风险徽标）的原因；真正的裁决点是信任名单。
>
> 归档产物卡对所有人保留：它是**交付面**（分析结果/报告出站）而非写面，硬拒会让
> 非信任用户的分析请求整体失败。代价是这条路径可被用来让 agent 打包工作区文件——
> 该风险由「工作区根由用户自己配置」+ 确认卡人工审查共同约束。

**落地位置**（施工时照此改）：

| 环节 | 实现 | 信任用户行为 |
|---|---|---|
| sys / dev / ws 域准入 | `KoogSysSessionGate` / `KoogDevSessionGate` / `KoogWsSessionGate.requireSessionAuthorization` | 放行（非信任 ⇒ 拒绝） |
| sys.write_config / sys.repair / sys.service 逐动作 | `KoogSysWriteGate` / `KoogSysRepairGate` / `KoogSysServiceGate.requireActionApproval` 首行 `if (sessionGate.isTrustedSender()) return true` | **免卡直接执行** |
| ws.write_file / ws.run_task 逐动作 | `KoogWsWriteGate` / `KoogWsRunTaskGate.requireActionApproval` 同上 | **免卡直接执行** |

> 🔴 唯一例外：`RiskClassifier` 判 `FORBIDDEN` 的破坏性命令（`rm -rf /`、fork bomb、`mkfs`、`dd of=/dev/`）恒拒，信任用户也不例外。
> 跨会话写（`create_group_chat`）仍经 `ToolConfirmGate` 强制确认——它不属于上述 Gate 族，走 `KoogBridgeTools` 自身确认面。

> 「越界 + 非信任用户 = 硬拒绝」是安全底线：不给陌生人弹一张「批不批准自己删系统文件」的卡。
> 信任用户仍要逐次确认越界操作——信任 ≠ 无限权力，一次误判不至于不可逆。

### 路由（routes）

`config.routes` 是有序路由表，**P3 agent-first 批后默认 3 条骨架**（`SystemRoute` / `AgentRoute` 空槽 / `FileRoute`——用户可配 `ReplyRoute`/`CommandRoute` 静态路由已随 P2 反转退役，见本节末退役说明），顺序 `system → agent → file`（**数组序即优先级**，`PipelineSnapshot.resolve()` 取首个命中即返回）。每条 route 是 sealed 多态（`classDiscriminator`）：`SystemRoute` / `CommandRoute` / `AgentRoute` / `FileRoute`（类型保留供迁移器处理旧文档残留；`ReplyRoute` 已整槽退役，存量 reply 条目由 v9→v10 迁移剥离并折 `experimental.retiredV10` 保底）。

**迁移合并（`mergeRoutes`）冲突判定 = id 或匹配键**：迁移旧配置合成新 route 时，与既有 route 若「同 id」**或**「匹配键相同」即视为冲突，**就地整条替换并保留被替换条目的原数组下标**（继承其优先级位置）；无冲突才追加到末尾（优先级最低）。匹配键按 kind 定义：`CommandRoute` = `aliases` 集合（任一别名重叠即冲突）、`AgentRoute`/`SystemRoute`/`FileRoute` = 各自单例按 `kind` 判定。⇒ 用户旧的自定义指令若与既有条目**别名重叠**，迁移时会**就地替换既有条目并继承其优先级位置**（不是被追加到末尾而遭永久遮蔽）；不重叠的才追加到末尾（优先级最低）。旧静态回复（static_replies）键已随 REPLY 槽退役不迁移。替换后条目 id 取用户条目 id（整条替换语义）。`lib` 侧 `ConfigValidator` 另有一道纯函数防线：`routes` 数组内若存在两条匹配键相同的条目（用户手工编辑也可能造成），产一条 `ConfigIssue`（指明冲突两条 id 与匹配键）供 UI 显示——**只提示、不阻断落盘**（与 `ConfigValidator` coerceIn 语义一致）。

用户级自定义由 routes 数据承载，能力级扩展由"新增一类能力改 4 处 3 文件"机制承载（sealed + discriminator ⇒ 漏写即红构建）。

> 🔴 **routes 数据体系退役**：用户可配 `CommandRoute`/`ReplyRoute` 静态路由已整体删除（routes 数组现在只余 `SystemRoute` 单例、`AgentRoute` 空槽、`FileRoute` 三类骨架）。`AgentRoute` 空槽必须保留——它是 **CLI 形态**（`backendId` 恒 `"none"`，即 `NoAgentRunner`）`ai` 前缀直跑的装配前提（App 形态该槽被「koog 在位」恒短路，无行为影响）。上文「迁移合并」描述的是退役前的历史语义：静态回复合成已随 REPLY 槽退役（static_replies 键退役不迁移、存量 reply 条目由 v9→v10 迁移剥离），迁移器仅对 skills→CommandRoute 仍按此逻辑处理旧文档残留。

### 智能调度（agent-first 默认形态，本地 Koog agent 作为唯一执行大脑）

**一句话定位**：**全量未命中消息不再需要 `ai` 前缀**——本地 Koog agent（`:feature-larkbridge` 的 `KoogAgentRunner`）接管全部未命中消息，作为「大脑」决定轻量直答（`reply_direct`）、调本地工具（文件分析 / 工作区域 ws·git / 系统诊断 sys·dev）、向用户追问（`user.ask_user`），或如实说明能力边界。**没有外部 CLI 后端可委托**——能力面的全部宽度由本地工具族决定。

**🔴 短路判定（现行语义）**：`agent.dispatchEnabled` 开关已退役——「App 形态全量消息归调度 agent」是**默认语义**，无开关无载体。短路判据只剩「`backendId == "koog"` 且 `isAvailable`」两个条件：

- **App 形态**（koog 在位且可用）：AgentHandler 的 triggers 前缀**恒不命中**，未命中消息经 fallback seam 全进 DispatchAgentHandler（大脑接管）；
- **CLI 形态**（`NoAgentRunner`，`backendId = "none"`）：前缀照旧直跑 AgentHandler——该形态无应答引擎，agent 类消息由 `NoAgentRunner` 显式报 `agent_backend_unavailable`（fail-closed，绝不静默假成功；见「独立 CLI」节）；
- **端点未配置**（isAvailable=false）：前缀回落 AgentHandler 直跑并明确报错（🔴 死区回落语义保留，防「前缀被屏蔽 + 兜底不接 + 无兜底文案」三重静默——真机事故防线）；
- **`resetCommand`（新对话）放行在前**：调度开启时清的正是 Koog runner 的多轮上下文，被吞会连「重置调度会话」都做不了；
- 数组序在前的槽指令（SYSTEM 槽 `/help`/`ping` 等）不受影响——天然保留。

**🔴 激活死锁修复（2026-09-14，isAvailable 快照死锁）**：`KoogAgentRunner.isAvailable` 读 `endpointRef` 快照，旧实现只在装配时刷一次 + run 内重刷——但闸门不开就进不了 run 刷快照 ⇒ 用户在 AI 设置页配好端点后 `isAvailable` 恒 false、调度永不激活。修复 = `LarkBridgeManager.getOrCreate()` 内 1Hz 端点刷新回路（`KOOG_ENDPOINT_REFRESH_MILLIS`，挂 gatewayScope 随 `onAppExit` 收掉；`runCatching` 吞错只 WARN）⇒ **配好端点 ≤1s 免重启激活**。DataStore 无变更通知流、AI 端点配置不走 `Bridge.applyConfig`，刷新只能轮询——1Hz 读内存驻值成本可忽略。🔴 该回路现为**运行态分档**：桥接运行期保持 1Hz 热更；未运行态每 `KOOG_ENDPOINT_IDLE_REFRESH_TICKS` 拍约 30s 才刷一次（空闲态不再每秒密集 DataStore 读）。代价：未运行态的陈旧快照喂三个消费面——启动预检硬拒绝门、驾驶舱缺端点提示条（≤31s 自愈）、环境预检 banner（常驻至下次预检、**不自愈**）——「桥接停着改端点后立即启动」有 ≤30s 误拒窗口（拒绝文案引导重试、下一拍自愈）；该窗口连同端点态订阅化已收口，收口明细登记于 docs/index.md「已知欠账」对应条目。

**🔴 宿主 xlog 私钥回落缓存的事件化**：`HostXlogKeyCache`（`page/larkbridge/HostXlogKeyCache.kt`）持 `keyRef`，经 `xlogKeyFallback` 缝注入桥接（桥接文档私钥留空时富化为 XLog 解密页私钥）。私钥真源 = DataStore 的 `xlog_private_key`（编辑入口在 XLog 解密页）：装配期 `seed()` 单次挂起读（回落缝首帧同步读契约）+ 装配点挂 gatewayScope collect `DecryptTools.defaultPrivateKeyFlow`（:core `LocalDataManager.valueFlow` 的公开变更流读口）——**App 改私钥保存即发射即写穿回落缓存**（无轮询等待窗口；collect 异常 warn 后 1s 退避重试，shutdown 取消作用域即收口）。回路不再并列刷私钥（每拍拉取职责已移除，回路收敛为端点快照单一职责）。

**调度 agent 的执行链**（`KoogAgentRunner`，5 参 run 全流程入口）：

- **端点/会话**：每 run 重刷 `refreshEndpoint()` → 校验 `isConfigured`（未配返回 `agent_config_invalid`）→ `LlmClientFactory.executor(endpoint)` 缓存复用 → ChatMemory 按 sessionKey 多轮挂接（LRU + windowSize 防膨胀）；
- **执行策略（2026-09-14 混合响应修复）**：自建 `larkDispatchStrategy`（`koog/KoogDispatchStrategy.kt` 文件级 internal fun，策略名 `"lark_dispatch"`）——图结构与官方 `singleRunStrategy` 完全一致，仅对调 `nodeSendToolResult` 两条出口边序（**工具回环在前、Finish 在后**）。🔴 根因：官方边序文本边先声明，图引擎 `resolveEdge` 按注册序取第一条能走的边、`onTextMessage` 只看 Text part 存在性——真机第二轮「收尾文本 + send_file/send_card 工具调用」**混合响应**被文本边截胡 `__finish__`，工具调用静默丢弃（用户收不到结论卡与报告文件）。对调后混合响应工具优先，纯文本轮照旧 Finish（语义不变）；
- **迭代上限**：`agent.maxIterations`（HOT 字段，**run 前现读**——2026-09-16 配置化批，宿主经 `maxIterationsProvider` 注入；runner 兜底默认 `DEFAULT_MAX_ITERATIONS = 60`）（🔴 迭代单位 = **节点执行次数**——每工具轮耗 3 节点（CallLLM+ExecuteTool+SendResult），30 ≈ 6 工具轮 + 失败重试余量；失控保险丝，真实时限仍由 `withTimeout(totalTimeoutMillis)` 兜底）。旧值 12 按工具轮只够 ~4 轮（线上真实链 ≈11 节点零余量）；
- **提示词分段序**（文件级 internal 纯函数 `buildKoogSystemPrompt`，直测缝）：① 调度分工 HEAD（`SYSTEM_PROMPT_HEAD`，**两分工表述**：一级 = 飞书原生操作、二级 = 本地文件处理与工作区域能力，并给出承接映射——代码/仓库问题 → `git.read` + `ws.run_task`、生成报告/写文件 → `ws.write_file`、信息不足会阻塞 → `user.ask_user`、超出边界 → 如实说明边界）→ ② **全局性格段**（AI 设置页「性格模式」五档，非空时）→ ③ persona 段（桥接域「机器人人设」；段头声明「若与全局性格模式冲突，以本段为准」——更具体者胜）→ ④ 工具清单（`listed()` 单源派生，只列实际注册的工具；文件处理段仅 `hasFileContext` 时注入）→ ⑤ 交付约定 → ⑥ 用户附加系统指令 → ⑦ **安全约束段**（AI 设置页「安全约束」两档，非空时，**最末**）。🔴 性格/安全经 `KoogPromptConfig` 独立快照（`promptConfigRef`，refreshEndpoint 并排刷新）注入——**不进 `LlmEndpointConfig`**（executor 缓存 key，掺字段引发无谓失效重建）、**不进 `AgentRequest`**（执行器侧不重复注入）；🔴 交付约定收紧：「用 send_file 工具发送（工作区工具产出已登记进围栏）；仅在无法调用 send_file 时才在最终回复末尾用 `[产物]` 标记兜底——同一文件不要双通道重复交付」（防 send_file 与 [产物] 双通道重复投递）；🔴 条件化：`send_file` / `send_image` 条目与交付约定整段**随两个开关改写**（`sandboxAvailable` / `artifactsEnabled`，见本节「send_file 沙箱放宽」末表）——总开关关闭时不列工具、交付约定改为「任何文件都无法发送」，避免「宣称可发但实际拒」的反向诱导；
- **工具集**（`BridgeToolRegistry.build`）：**L0 基础出站** `reply_direct` / `send_file` / `send_card` / `search_knowledge`（手册 RAG，知识库有内容时）+ **L1 三件恒注册**（`send_image` / `recall_own_message` / `reply_in_thread`——当前会话内原生操作，定义即注册零配置面）+ **P4 会话作用域三件**（`react_emoji` / `fetch_quoted_context` / `update_own_card`）+ `web.fetch`（恒注册）+ **文件分析工具集**（见下）+ **条件注册族**（L2 五件 / memory 四件 / remind 两件 / sys 七件 / dev 两件 / self.status / **ws 两件 + git.read** / `user.ask_user`——各自装配门控见「工具注册分级全景」表与「工作区域」「user.ask_user」小节）；
- **超时**：`withTimeout(totalTimeoutMillis)`，`TimeoutCancellationException` → `agent_timeout`；CE 原样重抛（「停止」指令的取消传播不吞）。

**文件分析工具集**——`feature-larkbridge/.../koog/BridgeLogTools.kt`，log-tools 直调零子进程：

| 工具 | 实现 | 产物 |
|------|------|------|
| `decrypt_log(file_path)` | `LogDecode().decodeFile`（🔴 私钥读 DispatchPayload 载体的**有效**私钥——真源 `fileMessage.xlogPrivateKey`；2026-09-15 私钥装配级统一批起 App 形态文档留空时经宿主回落缝富化为 XLog 解密页的 `xlog_private_key`，桥接显式配置恒优先） | 同目录 `.log`，登记围栏 |
| `filter_by_tag(file_path, tag_regex, tag_name)` | `LogTagFilter.filterByTag` | 过滤产物路径 |
| `filter_by_time(file_path, target_time, range_minutes)` | `LogTimeFilter.filterByTime` | 过滤产物路径 |
| `filter_by_range(file_path, start_time, end_time)` | `LogTimeFilter.filterByRange`（任意起止闭区间含两端——对称窗口整数分钟口径表达不了的奇数分钟区间；时间口径「宁拒不猜」由引擎承载） | 过滤产物路径 |
| `split_by_size(file_path, size_mb)` | `LogSplitter.splitBySize` | 多卷**全部**登记围栏 |
| `probe_file(file_path)` | `LogProbe.probe` 判型 | 判型摘要（供 LLM 决定要不要解密） |
| `extract_archive` / `preview_text` / `list_dir` | 复用 `:core` `core/llm/tools/DecryptTools` 现成工具类 | — |

- **围栏**：`ToolExecutionContext`（`:core` `core/llm/tools/`，2026-09-14 从 feature-ai 下沉）初始允许集 = 本消息引用文件的 `path/dir/artifacts` 全量（`DispatchPayload` 载体经 `AgentRequest.opaque` 透传——裸消息不包载体，`reply` 语义零变化）；工具路径参数必须命中允许集否则返回围栏拒绝文案（LLM 可自行调整，任务不崩）；`send_file` 的 file_path 同受围栏校验（并且**判据更宽**——见下）。

#### `send_file` 沙箱放宽（2026-09-18）

**为什么放宽**：工作区域工具（`ws.run_task` / `ws.write_file`）的产物落在**工作区根内**，而产物路径不经 `ToolExecutionContext` 注册就无法命中精确允许集 ⇒ `send_file` 会恒返回「路径不在围栏内」。`ws.write_file` 写成功会 `register` 进本消息围栏，但 `ws.run_task`（构建/测试产物）没有结构化产物字段 ⇒ 必须有沙箱级放宽兜底。

**放宽后的放行判据**（`SendFileTool.execute`，🔴 顺序即下表的顺序）：

| 序 | 闸门 | 行为 |
|----|------|------|
| 1 | 产物回传总开关 `agent.artifacts.enabled` | `false` ⇒ 一律拒（**先于一切路径判据**，不做旁路） |
| 2 | 围栏判定 | 放行 ⟺ `context == null`（纯聊天档，维持既有零校验语义，**不收窄**）∨ 命中精确允许集 ∨ 命中**工作区沙箱**。围栏外统一回 `SensitiveFilePolicy.OPAQUE_DENIED_HINT` |
| 3 | 文件存在性 | 仅对**已授权路径**报「文件不存在」 |
| 4 | 敏感文件排除表 | 见「出站敏感文件排除表」节 |
| 5 | 30MB 平台上限 | 网关层亦有校验，此处提前判定省一次上传 |

🔴 **闸 2 的围栏判定前移是刻意的**（收窄存在性 oracle）：原顺序是「存在性 → 敏感 → 围栏」，三段文案可区分 ⇒ LLM 能把 `send_file` 当探测器，枚举围栏外任意路径是否存在（如 `~/.ssh/id_rsa` 在不在）。前移后围栏外三种情形（存在且普通 / 存在且敏感 / 不存在）**回同一句**，区分性信息只留给日志与审计。

**沙箱根集合** = `workspace.root` ∪ `workspace.extraRoots`：

> 组装决策抽为 `LarkBridgeManager.sandboxPolicyOf(workspace)`（internal 纯函数，`LarkBridgeManagerSandboxTest` 直测）：两根本身即 `WorkspacePolicy.fromConfig` 的输入，**`agentWorkDir` 不再并入第三根**——该字段已随 v10→v11 迁移退役，且现网没有任何子进程以它为 cwd（工作区域工具的 cwd 恒 = `workspace.root`）。
>
> 生效时刻：run 前现读桥接 HOT 配置 ⇒ **保存即生效、不等重启**（与 `workspace.root`/`extraRoots` 自身的 `ApplyMode.RESTART` 语义有意不一致）。

**空工作区** ⇒ `sandboxPolicyOf` 返回 null ⇒ `send_file` 回落精确允许集判据（fail-safe）。

**提示词与能力面必须一致**（🔴 两条同型事故的教训）：

| 条件 | 提示词/工具描述的行为 |
|------|---------------------|
| `sandboxAvailable == false`（未配工作区） | 「send_file 的边界：与读类工具相同（本机未配置工作区，工作区放宽不生效）」——**不得**宣称可发工作区文件（否则 LLM 反复试错耗尽 `maxIterations`） |
| `artifactsEnabled == false`（总开关关闭） | 可用工具清单**不列** `send_file`；交付约定整段改写为「任何文件都无法发送 + 不要写 `[产物]` 标记 + 去设置页开启」；`SendFileTool.description` 同步改写为 `Currently UNAVAILABLE…`——**不得**留下「宣称可发但实际拒」的反向诱导 |

**读类工具刻意不放宽**：`probe_file`/`preview_text`/`decrypt_log`/`filter_by_tag`/`filter_by_time`/`filter_by_range`/`split_by_size`/`extract_archive`/`list_dir` + 扩展件（`read_file`/`grep`/`glob`/`convert_timestamp`/`hash_file`/`hex_dump`/`strings`）+ 侦察四件（`log.stats`/`log.diff`/`log.view`/`log.cluster`）共 **20 件**仍走严格允许集——放宽的只有「往外发」这一步。提示词因此**单列** `send_file` 的边界句，且围栏句明确写「以上**二十件读类工具**」（全部读类面，不含 `send_file`），避免 LLM 误以为读类工具也放宽。
- **注册条件**：**有引用文件才注册文件工具**（纯聊天消息省 token，LLM 不会幻觉调用不存在的工具）；系统提示词同步注入分工原则——「解密/Tag 过滤/时间过滤/分割/解压/预览必须优先用这些本地工具（快、零进程开销）；本地工具覆盖不了的深度分析先检查 `git.read` / `ws.run_task` 能否补足」。

**引用文件透传进调度（2026-09-14 修复问题 1）**：`DispatchAgentHandler` 构造接 `ReferencedMessageResolver`（装配点构造段上移保证依赖序），handle 走与 `AgentHandler` 同款 `resolveReferencedPrompt` 四态分流——引用文件消息：懒下载 + 预处理 + 拼接引用块进 prompt（**不剥前缀**，整条原文作 baseText）+ `pinnedDirs` 在途保护（agent 收尾 finally unpin）；未引用消息 prompt 逐字不变；resolver null（CLI 形态）零变化。

**进度接线（2026-09-14 修复问题 3）**：`progress` 经 5 参 run 参数链贯穿（🔴 旧实现读 `request.opaque as? ProgressAccumulator` 恒 null——opaque 专职携带 ReplySink，事件全丢、看门狗零 touch，真机表现为「无过程信息 → 120s 空闲判死」）。`handleEvents` 六回调映射：

| Koog 回调 | ProgressEvent |
|---|---|
| `onLLMCallStarting` | `Thinking("思考中（模型 ${model.id}）")` |
| `onLLMCallCompleted` | 非空文本 → `AssistantText`；空文本只 `touch()`（工具轮 response 常无文本，防空窗垃圾） |
| `onToolCallStarting` / `onToolCallCompleted` | `ToolUse` / `ToolResult(ok=true)` |
| `onToolCallFailed` | `ToolResult(ok=false, "工具 X 失败：<原因>")` |
| `onLLMStreamingFrameReceived` | **只 `touch()` 不进窗**——token 帧会糊掉 6 条滚动窗口挤出有语义事件；touch 只喂看门狗零污染 |

- **markStatus 三态**：成功 DONE / `TimeoutCancellationException` TIMEOUT / 其余 Throwable FAILED（CE 重抛分支不 mark——外层 `RunStatus.toTaskRunStatus()` 兜底）；前置 fail（端点未配）也 mark FAILED（进度卡不留「生成中」假象）。
- **收尾补条去重护栏**：`onLLMCallCompleted` 正常喂上时不再重复补一条 AssistantText（相邻 AssistantText 不合并，重复会让终态卡出现双份文本）。
- **测试缝**：`executorFactory` internal 构造参数注入 fake executor（🔴 Koog 1.2.0 AIAgent 实调 **ResolvedModel 重载**，只重写 LLM 重载会被基类默认实现绕开打到真 client——见 `KoogAgentRunnerTest`）；`MultiLLMPromptExecutor(emptyMap())` 必抛 ModelResolutionException，fake 须挂真 client 挂名 provider。
- **框架日志压制（2026-09-14）**：`LogbackHelper.init()` 把 `ai.koog` 压到 **INFO**——Koog 框架全部大 dump 都在 DEBUG 级（`Response: Assistant(parts=[...])` LLM 全文、`Received tool result:` 工具结果全文、`Executing node` 每步节点执行），root=DEBUG 时全部漏出刷屏。保留 `Executing tool (name: ...)`（INFO，每次工具调用一行，排障有用）与全部 WARN/ERROR（工具失败原因、Max iterations）。🔴 CLI fat-jar 不受影响（`:lark-bridge` jvmTarget 11 不依赖 `:core`，LogbackHelper 不在 jar 内；fat-jar 无 logback.xml——「事实 4」同款先例）。

#### 原生能力分级面（2026-09-27 能力一级化批 A~D；L1/L2/L3）

飞书原生 API 升为 agent 一级能力，按**会话作用域分级**（分级模型 lib 侧单源 = `lark-bridge/.../config/NativeCapability.kt`；`agent.nativeTools` 配置段见上「配置模型」节）：

| 级 | 定义 | 工具 | 注册语义 |
|----|------|------|----------|
| **L1** | 当前会话内原生操作 | `send_image`（发图片产物）/ `recall_own_message`（撤回机器人自己发的消息，**账本双重守护**；🔴 2026-09-27 撤回链修复：三发送工具 reply_direct/send_card/send_image 成功文案统一附 `（message_id=…）`——recall 的 description 声明 id 来自发送结果，成功文案丢 id 则链断在第一环，LLM 无从拿 id 只能自创绕路）/ `reply_in_thread`（群话题内回复，P2P 回落普通回复） | **定义即恒注册**（零配置面；常备清单 8→11） |
| **L2** | 跨会话能力（当前会话的纵深数据：历史/成员/信息；建群；查用户；读文档） | `fetch_chat_history` / `get_chat_info` / `create_group_chat`（🔴 写操作，经 `ToolConfirmGate` 强制确认卡，fail-closed）/ `lookup_user`（PII 最小化只回名字）/ `read_wiki_document`（`/wiki/` 两步解析 + `/docx/` 直读，30k 截断） | **接缝在位即恒注册**：`agent.nativeTools` 双闸已随 v11→v12 退役；唯一授权闸 = 飞书后台 scope（未开通时工具回落引导文案） |
| **L3** | 替用户身份 / 凭证读取 / 群生命周期（update/delete/解散/改名）/ 踢人 / 全局搜索（仅 user-token）/ pin | **无** | 🔴 **恒排除，无配置载体**——`LarkApiBoundaryTest` 负清单守护钉死 |

关键形态（施工者必读）：

- **Args 无 chat_id**：L1/L2 工具的目标会话恒钉死在 `NativeChatSurface` 实现闭包（每消息现造，chatId = 本消息所属会话）——LLM **不可指定会话**，由 `ChannelSeamGuardTest` 反射守护（Args 序列化字段名出现 `chat_id` 即红）。
- **渠道中立接缝**：`NativeChatSurface`（`lark-bridge/.../pipeline/NativeChatSurface.kt`）是工具面与网关的会话作用域收窄视图，**零飞书专属类型**（`ChannelSeamGuardTest` E 组守护）；生产实现 `GatewayNativeChatSurface` 只读不写（建群除外）。
- **默认实现 = 能力缺席**：`MessageGateway` 批 A/C 新增 11 成员全部带默认实现（null/false 返回）——scope 未开通 / 未连接时工具回落 scope 引导文案（`NativeCapability.scopeHintOf` 单源），绝不抛异常。
- **新群无回话通道**：`create_group_chat` 成功后返回 chatId + 「新群内 @ 机器人继续」引导（L1 出站边界写死）。
- **对应后台 scope**：L2 各 token 所需 scope 见上文「7 项后台核对清单」第 2 项的可选 scope 行；运行时能力卡注脚同样经 `scopeHintOf` 取串。

#### 工作区域（ws / git 域，`agent.wsTools.*`）

工具本体在 `:core`（`WsWriteTools.kt` / `WsRunTaskTools.kt` / `GitReadTools.kt`），桥接侧门控实现全在 `koog/KoogWsGates.kt`；配置段见「配置模型」节。三件工具的**授权模型是两段式**：

| 工具 | 动作面 | 授权与确认 |
|------|--------|-----------|
| `ws.write_file(relative_path, content)` | 工作区根内**相对路径**写（父目录必须已存在；**拒绝代建目录**），写前落 `.bak` 快照，写后 `register` 进本消息围栏供 `send_file` 直发 | **域准入（信任名单）+ 逐写 diff 确认卡**（完整脱敏前后 diff + 目标路径 + 快照路径 + 回滚配方）；diff 超展示预算 ⇒ **整体拒执**（不可分片绕过） |
| `ws.run_task(command, args, timeout_minutes)` | **编译期配方枚举**执行（`TaskRecipe`：GRADLE / MAVEN / NPM / PNPM），cwd 恒 = 工作区根（装配事实，非 LLM 参数） | **域准入（信任名单）+ 逐执行确认卡**（完整 argv + cwd + 超时档）；超时钳制 `1..min(配置上限, 0.8×整体预算)`，输出经 `SysOutputSanitizer` 脱敏 + 8K 收口 |
| `git.read(action, path)` | `log` / `diff` / `blame` / `status` 四动作枚举，恒注 `--no-pager`；`diff`/`blame` 的 path 过 `authorizeWithGate` 围栏 | **仅域准入（信任名单）**（只读零副作用无逐次卡） |

关键形态（施工者必读）：

- **域准入 = 按用户信任**：`KoogWsSessionGate.requireSessionAuthorization` 只判「本消息发送者是否在 `access.trustedUsers` 内」（`TrustPolicy.isTrusted`）。原「会话总授权卡（说明卡 + 确认卡、chatId 键控、重启即失、绝不持久化）」已退役——每会话重批既烦人且群聊里任一成员批准即授权整个会话，按人判定更精确。信任只覆盖**域准入**：`ws.write_file` / `ws.run_task` 仍各自逐动作确认卡（与会话档无关）。
- **ws 专用围栏 context**：工作区根 `register` 进 ws 专用 `ToolExecutionContext` 的允许集，**严禁借道 `allowedRoots`**——后者语义 = 命中即免 gate 直接放行 + 出站旁路，工作区根进 `allowedRoots` 会绕过 `send_file` 的沙箱授权语义。
- **`wsEscapeGate`（HITL 缝）**：目标路径 canonical 落在工作区根内才放行——路径越界的第二道防线，与授权模型解耦。
- **`ws.run_task` 行旁路**：`ArgvProcessRunner.run(argv, cwd, onLine)` 的 `onLine` 经装配缝 `runTaskTap` 喂进度卡尾部行 + `touch()` 保活——长任务「在跑但无事件」不被 120s 空闲看门狗误杀（`TAP_THROTTLE_MILLIS = 500`）。
- **装配缝（`:core` 目录单源）**：`AgentToolCatalog` 的 `ToolContextConfig` 新增 `wsWriteGate` / `wsRunTaskGate` / `gitSessionGate` / `runTaskTap` 四缝，目录新增 `WS_WRITE_FILE` / `WS_RUN_TASK` / `GIT_READ` 三描述符（`WRITE`/`RUN_TASK` 标 `Risk.DESTRUCTIVE`，`GIT_READ` 为 `READ_ONLY`）；工厂返 null 即不注册（fail-closed），三件恒不入任何 base names。
- **负面清单（编译期锁死）**：`git` 不入任务配方目录（由 `git.read` 只读承接）；`python` / `node` 解释器不入目录（解释器 = 任意代码执行，argv 白名单形同虚设）；提权/包管理黑名单由 `ArgvProcessRunner` 执行件恒拒兜底。git 写操作（commit/push/checkout）**永久不在能力面内**。

#### user.ask_user 飞书提问卡（HITL 开放式追问）

`:core` 契约（`AskUserTool.kt` / `AskUserModels.kt`：`AskRequest` / `AskField` 四形态 Text·Choice·MultiChoice·FilePath / `AskAnswer` / `UserQuestionGate` / `AskCaps`）不变；桥接侧新增两件实现：

| 件 | 位置 | 职责 |
|----|------|------|
| 发卡与挂起 | `koog/KoogUserQuestionGate.kt`（`UserQuestionGate` 实现） | 发**非流式 markdown 提问卡**（问题全文 + 字段清单 + 「引用本卡回复即作答」引导；`CardSpec` 无 buttons）→ 注册进 `AskUserBus` → 挂起等答案；软超时 `AskCaps.resolveAskCapMillis`（**0.8×整体预算**，60s..30min 钳域——软超时须先于 agent 硬超时触发）；发卡失败/答超时 = `skipped` **软反馈**（HITL 的失败模式是降级继续，不终止任务） |
| 答案回传 | `lark-bridge/.../confirm/AskUserBus.kt` + `handler/AskAnswerHandler.kt` | **主通道 = 引用提问卡回复**（零格式学习成本，全文即自由文本答案）；按钮通道已就绪但当前卡面未挂按钮——`AskAnswerHandler` 认 `qa-<requestId>:<option>` 形态的卡片回传（`ACTION_VALUE_PREFIX`/`ACTION_FIELD_ID` 单源），feature 侧发卡要挂按钮时按该前缀构值即可；配对键 = 提问卡 messageId；**防替答**（只采纳原提问者 `senderOpenId` 的回复，他人引用返回 false 不误伤在途提问）；`AskAnswer.skipped` **只由超时与 bus 层显式跳过产生**——用户回复「跳过」按自由文本答案透传（由 LLM 自行理解），handler 不解析跳过语义 |

- **与确认卡的分界**：确认卡是**二元裁决**（`InteractionHandler`，允许/拒绝）；提问卡是**开放式回答**（`AskAnswerHandler`，自由文本 + 结构化字段）——两个 handler 分立（合并会让 `match` 谓词互相遮蔽），同属 LIGHT 档不占许可。
- **注册门控（fail-closed）**：`askUserBus` 接线 + `chatId`/`senderOpenId` 非空才装配；askBus 缺席（CLI 形态/存量测试）不注册——挂起等一个无配对面的回答是死等。**不设配置开关**（纯交互零危险面，与 `reply_direct` 同级的一级能力）。


### 工具注册分级全景（`BridgeToolRegistry`：常备 / 条件注册 / 文件类）

Koog 本地 agent 的工具注册面按四档分级（单源 = `feature-larkbridge/.../koog/BridgeToolRegistry.kt`；
「能力清单卡」的静态名单 = `ALWAYS_REGISTERED_TOOLS`，与 build 注册分支逐名对齐由
`KoogAgentRunnerTest` 名称一致性断言钉死）：

| 档 | 工具 | 注册语义 |
|----|------|---------|
| **常备 11 件** | `reply_direct` / `send_file` / `send_card` / `send_image`（L1）/ `react_emoji` / `fetch_quoted_context` / `update_own_card`（会话作用域三件）/ `recall_own_message` / `reply_in_thread`（L1）/ `search_knowledge`（条件可用：知识库有内容）/ `web.fetch` | 纯聊天消息下恒注册；能力卡按常备能力列出（条件性不区分，提示词里精确门控） |
| **文件类约 20 项** | `probe_file` / `decrypt_log` / `grep` / fs.extended 取证件 / 侦察四件等（`BridgeLogTools` 装配，extended=true） | **按消息引用文件动态注册**（`DispatchPayload.allowedFiles` 非空才装配，受本消息文件围栏约束）——UI 不列（无文件上下文时它们不存在，LLM 不幻觉调用） |
| **条件注册** | `remind_user` / `cancel_reminder`（会话内提醒与撤销，`reminderScheduler` 注入才在册——App 形态网关 scope；CLI/存量测试 null 不注册）；`memory` 四件（长期记忆，`dataRoot` 接线 + `chatId` 非空才装配，见下节）；L2 五工具（`NativeChatSurface` 接缝在位即注册）；sys 七件 / dev 两件 / `self.status`（接缝在位即注册——**注册面已无配置闸**，v13→v14 退役）；**ws 两件（`ws.write_file` / `ws.run_task`）+ `git.read`**（工作区根已配置 + 信任名单接线，见上「工作区域」）；`user.ask_user`（askBus 接线 + chatId/senderOpenId 非空，见上「user.ask_user 飞书提问卡」） | 接缝缺席才不注册，**恒不进常备清单** |
| **`web.fetch`（联网查询）** | 白名单域名 + 私网段解析拒绝 + 重定向逐跳重校验三重防线（全在 `WebTools` 工具本体，`:core` 单源——桥接链与桌面工作台链同批接入） | **恒注册**（零注入依赖，与本消息文件围栏无关——纯聊天主场景：查错误码/官方文档/AOSP 源码） |

`remind_user` 档位 [1,720] 分钟、单会话在途 ≤5（finally 释放占位）、纯文本冒泡回本会话（job 绑网关
scope，任务取消 CE 原样放行）；撤销按回执里的提醒编号。

### 长期记忆（memory 四件，per-chat 隔离）

桥接链接入 `:core` memory 四件（`memory.view` / `memory.append` / `memory.replace` /
`memory.delete`）作为 agent 长期记忆：

- **目录派生**：`chatMemoryDir` 纯函数（桥接链 memoryDir 唯一派生点）——
  `<dataRoot>/lark/memory/<清洗后 chatId>/`；chatId 非字母数字/下划线/连字符的字符折叠为 `_`
  （`..`、路径分隔符全折叠杜绝路径注入，超 64 字符截断），清洗不丢字符（保形防碰撞）；
- **多用户隔离硬前提**：桥接是白名单多人共用面，记忆目录必须按 chatId 派生——共享单一目录
  会让任一白名单成员读到/覆盖其他成员的聊天背景；
- **fail-closed**：dataRoot 未接线（CLI 形态/存量测试）或 chatId 为空 ⇒ 四件全不注册；
- **新对话不清记忆**：记忆绑定飞书聊天关系（chatId）而非单次 agent run——「新对话」（`resetSession`）**只清 Koog 本侧的多轮会话历史**（`historyProvider.reset(sessionKey)`，无传导面：委托链退役后不再有第二个会话态需要重置），长期记忆与工作区文件/下载文件**不在重置面**、跨 run 长存（回执文案如实披露重置面口径）；记忆目录由 `append_memory` 首写时惰性创建（只读/未用 memory
  的会话不落任何目录）；
- **不设目录数上界/自动驱逐**：记忆是持久用户数据，自动删除不可逆；目录数天然以白名单会话数
  为上界；memory 自带 memoryDir 围栏（fileName 正则 + canonical 包含性防御），不读本消息
  文件围栏；
- 桌面链 memory 绑定日志分析 runs，与本链是两个装配面，互不牵连。

### 语音消息转写入链（AUDIO kind 承接）

飞书语音消息不再一律降级：宿主形态接线转写缝后，AUDIO 消息由 `AudioHandler` 承接——
下载语音资源（走本周期网关）→ 宿主闭包转码（任意格式 → 16kHz mono wav）+ ASR 推理
（lib 是 jvmTarget 11 纯 JVM，转写内核经 `AudioTranscriberSeam` 由宿主注入）→ **转写文本与
用户手打文字走完全相同的 agent 管线路径**（整段转写文本即 prompt，含引用文件四态分流——
「引用文件 + 发语音」复用引用流既有产物，零新链）：

- **触发口径（零新触发语义）**：语音本身就是显式用户动作，转写文本恒按聊天消息路由——不发明
  口播触发词（识别不出则消息被静默丢弃是最差失败形态）；
- **群语音静默跳过**（与无法 @ 定向同口径）；时长 10 分钟下载前拒收；转写中给提示；
- **三态回执**：转写成功 / 能力不可用（给引导文案：去哪下载模型装什么依赖，非失败不落审计码）/
  执行失败（人话原因，落 `AUDIO_TRANSCRIBE_FAILED` 审计码）；
- **CLI 形态无转写缝**（seam 缺省 null）⇒ AUDIO 维持未支持降级文案（见下「处置矩阵」）。

### 引用流文件分析（2026-09-11 重设计；晚间泛化为「引用即上下文」）

文件消息的语义从「收到即下载分析」整体切换为「**登记 → 引用触发 → 懒下载 → 预处理 → 拼接 prompt**」；晚间泛化后**引用回复不只是文件触发器——引用即上下文**（文件拼路径、文本拼原文，都带标识块）：

1. **登记（FileHandler，LIGHT 档）**：用户发文件 → 桥接把 `messageId → (chatId, senderId, fileKey, fileName)` 登进内存登记表 `FileRegistry`，并按 `fileMessage.ackTemplate` 回执（`{fileName}` 占位替换；模板空白 = 静默）。**不下载、不起 agent**。群聊中文件消息物理上无法携带 @，`PolicyGate.isAllowed` 对 `InboundKind.FILE` **无条件**豁免群@门控（不读任何配置；白名单仍前置）——🔴 2026-09-12 前这条豁免实际是死代码（SDK 抢先拦截，见上文「群消息 @门控」的修复说明），现在它是**唯一的放行依据**。
2. **引用触发**：用户**引用回复**某条消息并发送指令（如「分析这个日志」）——App 形态下未命中固定指令的消息一律进调度 agent，**无需 `ai` 前缀**；CLI 形态无调度层，仍须命中 `AgentRoute`/`CommandRoute` 前缀 → `ReferencedMessageResolver` 按 `replyToMessageId` 先查登记表（跨会话拒查、`registryTtlHours` 过期回落）→ 命中（文件）才懒下载；**查表未命中时自动补查**（2026-09-11 真机反馈根治）：经飞书 `im.v1.message.get` 现查被引用消息——文件消息且与本会话同 chat ⇒ 补登记救回（覆盖「引用旧气泡 / 进程重启清空登记表 / TTL 过期」三类场景）；**文本/卡片消息（同 chat）⇒ 原文直接作为 agent 上下文**（不登记、不下载——登记表只收文件消息，文本与卡片引用全靠补查取回）。**引用机器人的回复**同样支持，双路兜底：① 完整卡消息（非流式）⇒ `im.v1.message.get` 取回后提取 `body.elements[]` 的 markdown 正文；② **CardKit 流式卡**——飞书无「按 card_id 读卡片实体」的 API（SDK cardkit 仅 create/update/batchUpdate/settings/idConvert 五方法，已核实），正文只在我们自己手里 ⇒ **终态落账**：sink 终态时经 `GatewaySignal.StreamTerminal`（messageId + 终态全文）写入 Bridge 稳定件 `CardReplyLedger`（FIFO 512 条），引用时先查本地账（零 API 调用）；🔴 2026-09-12 起账本**持久化**至 `<dataRoot>/card-reply-ledger.jsonl`（kotlinx.serialization JSONL；record 全量重写、load 坏行跳过 + 压缩重写）——此前「进程重启丢账」的已知限界**已修复**，跨重启引用机器人回复可命中。补查不适用跨会话引用；API 失败（scope 不足 / 不支持的消息类型 / 已删除）一律回落引用说明。
3. **下载与配额**：落盘到 `<workspace.root>/downloads/<senderOpenId>/<yyyy-MM-dd>/<messageId前12>_<文件名>/`；每用户 `perUserQuotaBytes`（默认 2GB）超限按收录时间 FIFO 逐出该用户最旧目录，全局 `totalQuotaBytes`（默认 8GB）跨用户兜底；二次引用同文件免下载（幂等复用）。
4. **预处理**（`PreprocessPipeline` 固定序、每步失败降级不中断）：① AutoExtract（`autoExtract`，zip/tar/gz 解压到 `extracted/`）→ ② XlogDecode（`xlogDecodeEnabled` + `xlogPrivateKey`，`.xlog`/`.alog` 解密到 `decoded/`，走 `:log-tools`；2026-09-15 私钥装配级统一批起 App 形态下 `xlogPrivateKey` 留空自动回落 XLog 解密页私钥，CLI 形态文档自包含）→ ③ CustomCommand（`preprocessCommand` argv 模板须含 `{input}`、可选 `{output}`，直喂 `ProgramLauncher` 不起 shell；`preprocessExtensions` 空白 = 全部文件）。
5. **拼接 prompt**（2026-09-11 真机反馈：```json 围栏会污染用户原本的信息，已改为直接拼接）：用户原文**原样在前**，引用上下文作结构化附加块在后——文件块 `[引用文件 N]` + `名称：` + `路径：`（原始文件）+ `目录：`（专属工作目录）+ `预处理产物：` 列表（若有）；文本块 `[引用消息]` + `内容：` + 原文。**标识块头即「告诉 agent 这是什么」**。**未引用/未命中且补查未取回/跨会话时不再静默**：prompt 末尾附一句引用说明（agent 能向用户解释原因并引导重发，而不是「拿不到路径还自己瞎猜」）；完全未引用的消息 prompt 逐字不变（零变化硬约束）。
6. **失败原因传导**（2026-09-12 平台 100MB 限批次）：任一文件下载失败 ⇒ 整单失败、agent 绝不起跑（防残缺文件集误导）。失败原因经 `DownloadResult`（`error`/`larkCode`）渲染进用户文案——飞书平台 100MB 限（234037）→「超过飞书平台 100MB 下载上限，该文件无法下载…请压缩或拆分后重新发送」、我方 `maxFileSizeBytes` 超限 →「超大小上限」、限流 →「请稍后重试」；全部为大小限时尾部建议替换恒「请重试」（重试无法解决，不再误导）。下载卡的失败阶段行同样带原因。

🔴 已知限界（2026-09-11 晚三次收窄）：登记表（`FileRegistry`）是 Bridge 稳定件——App 内「停止 → 启动」不丢登记；**进程级重启清空登记表后，引用未命中会自动经 `im.v1.message.get` 现查被引用消息补登记救回**（引用旧气泡 / 跨重启 / TTL 过期三类场景全覆盖；文本引用同样靠补查取回原文；前提是应用具备消息读取 scope，见下）。**降级限界**：补查 API 不可用（未授 `im:message:readonly` 类 scope / 网络失败 / 消息类型不支持引用上下文或已删除——图片/富文本等类型不拼上下文）时回落引用说明——agent 向用户解释并引导重发，不再静默瞎猜。已落盘文件仍在配额区内，重启后由 `DownloadStore.rescan()` 重建账本。**引用未命中自解释**：引用了消息但登记表与补查都取不到时，prompt 末尾自动附一句引用说明；跨会话引用（消息在别的会话里）不走补查，同样回落说明；完全未引用的消息 prompt 逐字不变。旧 v1 配置的 `fileMessage.triggerPrefix` 等 4 键经 schema v2 迁移退役（非默认值折入 `experimental.retiredV1.*` 保底）。

> **2026-09-11 晚·引用即上下文泛化批**（用户真机需求：引用文字也该把对应文本补充到 prompt）：
> 1. **泛化**：`QuotedFileMessage` → sealed `QuotedMessage`（File/Text），`fetchQuotedFileMessage` → `fetchQuotedMessage`（第 14 成员名实相符）；`ReferencedFileResolver` → `ReferencedMessageResolver`。文件引用：下载后（或本地已存在时）把路径拼到 prompt 末尾；**文本引用**：查表必然 miss（登记表只收文件消息）⇒ 经 `im.v1.message.get` 取回原文直接拼到 prompt 末尾，不登记不下载。
> 2. **标识块**：`[引用文件 N]`（名称/路径/目录/预处理产物）与 `[引用消息]`（内容：原文）——标识块头即告诉 agent 这是什么。
> 3. **护栏同款**：文本路径与文件路径共用跨会话护栏（registry 命中但 chatId 不匹配 ⇒ 不补查；补查回的消息 chatId 必须与当前会话一致）——他会的文本原文绝不进 prompt。
> 4. **降级**：补查未取回（scope 不足 / 不支持的消息类型如图片富文本 / 已删除）回落引用说明；`fileMessage.enabled=false` 是引用流总开关（文件与文本引用一并关闭）。
> 5. **机器人回复账本**（真机二轮补，同晚）：真机引用机器人回复未命中 ⇒ 流式卡正文在卡片实体里取不到——落账兜底 `CardReplyLedger`（`files/` 域第 7 件，Bridge 稳定件）+ `GatewaySignal.StreamTerminal` 网关信号链 + interactive 完整卡提取（`parseQuotedCardContent`）。
>
> **2026-09-11 傍晚·引用旧气泡根治批**（前一批，泛化的基座）：
> 1. **根因**：用户重发文件后引用到的往往是**同名旧气泡**——登记表只覆盖「本进程内新收到的文件消息」，引用哪条气泡不受代码控制。修复 = lookup miss 时调飞书 `im.v1.message.get` 现查被引用消息，msgType=="file" 且与本会话同 chatId 才补登记，随后走正常懒下载。跨会话引用不补查（护栏语义），补查回的 chatId 不匹配同样拒绝。
> 2. **prompt 形态**：```json 围栏块（`{"text":…, "files":[…]}`）会污染用户原本的信息——改为**直接拼接**：用户原文原样在前，`[引用文件 N]` 附加块（名称/路径/目录/预处理产物）在后。
> 3. **scope 前提**：`im.v1.message.get` 属主动读单条消息，收消息用的 `im:message.p2p_msg:readonly` 未必覆盖——若日志出现「引用补查失败 code=99991672」，需到飞书后台为应用开通 `im:message:readonly` scope 并发版；未开通不影响其它功能（补查失败回落引用说明）。

> **2026-09-11 真机反馈修复批**（6 项，根因与回归用例全记录在案）：
> 1. **配置被覆写**：VM draft 曾以「未装配时的全默认空配置」起步，首次编辑会把「默认值+局部编辑」整份覆写磁盘（凭据被抹 ⇒「每次都要重填」+「启动被拒 appId 为空」）。修复 = 进页即装配（`ensureAssembled`），draft 初值恒为磁盘真值；连带修复磁盘 `autoStart=true` 永不生效。
> 2. **收起卡片底部间距**：`SectionCard` 的 bottom padding 曾藏在 `AnimatedVisibility` 内部（收起即消失），已移到 header 行并动画过渡。
> 3. **启动反馈可见化**：启动结果横幅从默认折叠的操作区移到常驻位（点启动被拒必可见）；`onStart` 前置预检（appId 空 ⇒ 硬错误；装配完成但本地 AI 端点未配置 ⇒ `Refused(AgentUnavailable)` 硬拒绝——agent 恒为硬依赖；工作区空 ⇒ 警示不阻断）。
> 4. **停止闪烁**：busy 小圈改为长在发起动作的按钮上；Draining 态显示「正在停止…」；停止不清结果横幅；驾驶舱快捷行补 busy 门控。
> 5. **引用流登记表**：见上「已知限界」收窄。
> 6. **日志降噪**：`addReaction` 响应无 `reaction_id` 视为成功（装饰性信号不再刷「网关出站失败」WARN）；出站失败日志 `chat=` 改 `target=`（setReaction 路径该槽位装的是 messageId）。

---

## 飞书后台配置（必做）

> 🔴 本节内容源自施工期的 S4/S6 脚手架 runbook（`spike-s4s6-runbook.md`，S4/S6 真机验证完毕后已按其 §7 删除清单移除，非交付物），逐字核实后搬入。新架构从自研 WebSocket 切换到官方 SDK `LarkChannel` 长连接，**必须**在飞书开放平台后台完成以下配置，否则桥接启动后显示「已连接」却收不到任何消息。

### 7 项后台核对清单

| # | 项目 | 说明 |
|---|------|------|
| 1 | 机器人能力已启用 | 应用能力 → 机器人 |
| 2 | 权限 scope | **最小集**：`im:message.p2p_msg:readonly`（收私聊）、`im:message.group_at_msg:readonly`（收群里 @ 机器人的消息）、`im:message:send_as_bot`（发消息）、`im:message.reactions:write_only`（表情回应，2026-09-27 批 D 补登记——P4 `react_emoji` 上线时漏登记）、`im:resource`（收文件/图片，兼发图片/文件）、`contact:contact.base:readonly`（显示发送者名字；🔴 2026-09-11 真机实证旧名 contact:user.base:readonly 已更名，报错 msg 会给出完整候选名单，任一 readonly 变体即可）；**流式卡片另需 `cardkit:card:write`**；**引用旧气泡/重启后补查另需 `im:message:readonly`**（`im.v1.message.get` 主动读单条消息，2026-09-11 引用补查——未开通时补查降级为引用说明，不影响其它功能；2026-09-11 真机实证：该应用此 scope **已生效**，文本引用取回成功）。官方流式文档另列 `im:message`，建议一并核对。**L2 跨会话能力的可选 scope**（默认关，开启 `agent.nativeTools` 才按 grants 申请，不影响最小集功能）：`im:message.history:readonly`（fetch_chat_history）、`im:chat`（get_chat_info / create_group_chat）、`wiki:wiki:readonly` 与 `docx:document:readonly`（read_wiki_document）；lookup_user 复用最小集已含的 `contact:contact.base:readonly`。 |
| 3 | 事件订阅 | `im.message.receive_v1`，订阅方式 = **使用长连接接收事件** |
| 4 | 回调订阅 | `card.action.trigger`，订阅方式 = **使用长连接接收回调**（⚠️ 旧版「消息卡片回传交互」长连接**不支持**，**必须用新版**） |
| 5 | 应用已发版 + 可用范围包含你自己 | 个人版发布后立即生效 |
| 6 | 飞书客户端版本 | **≥ 7.20**（卡片 JSON 2.0 与流式所需）；**自定义流式参数 `streaming_config`（print_frequency_ms/step/strategy）需 ≥ 7.23**；列表/代码块 Markdown 需 ≥ 7.6 |
| 7 | 网络可直连公网 | 🔴 **长连接不支持代理**；公司严格代理网络下不可用（检查 `HTTP_PROXY`/`HTTPS_PROXY` 是否被注入本进程） |

### 🔴 顺序陷阱（头号非代码失败原因）

> **在后台把订阅方式切成「长连接」并点保存的那一刻，本地必须已经有一条 SDK 长连接处于「已连接」状态，否则保存失败（鸡生蛋）。**

**正确顺序**（务必照做）：

1. **先**在 App 桥接页填好 App ID / App Secret，点**「启动」**
2. **等**状态显示**「已连接」**、**保持 App 开着别关**
3. **再**打开飞书开放平台后台：
   - 事件订阅 `im.message.receive_v1` → 选「使用长连接接收事件」→ 保存
   - 回调订阅 `card.action.trigger` → 选「使用长连接接收回调」（新版）→ 保存
4. 保存成功后，回到飞书客户端发消息即可

> 若先改后台再连接：保存会失败，或保存成功但事件仍走旧通道 ⇒ 收不到任何消息、误判为「连接失败」。**这是失败的头号非代码原因。**

### 群消息 @门控（`agent.groupMode`）

默认只处理群里 **@ 机器人**（或 @所有人）的消息（`agent.groupMode = "mention"`，源码 `AgentConfig.groupMode`）。若后台已授予敏感权限 `im:message.group_msg`（收群里未 @ 的消息）且用户把 `agent.groupMode` 设为 `"all"`（「全部回复」），则会处理群里全部消息；未知值一律回落仅@唤起（成本闸 fail-safe：宁可少放行不可多烧钱）。旧标量键 `access.groupRequireMention` 已随 schema v7→v8 迁移退役（非默认值折 `experimental.retiredV8` 保底，真源迁入 `agent.groupMode`）。

🔴 **门控只在桥接侧生效——SDK 侧恒全放行**（2026-09-12 真机 bug 修复）。引擎下推给 SDK 的 `LarkPolicy.requireMention` **恒为 `false`**（单一构造点 `BridgeGateways.policy()`），不随本开关变化：

```kotlin
// lark-bridge/.../pipeline/PolicyGate.kt 的 PolicyGate.isAllowed（桥接侧，唯一拒绝执行点）
// 前置分支略：CARD_ACTION / FILE 无条件放行，非 GROUP 会话一律放行
return agentGroupModeProvider() == GROUP_MODE_ALL || message.mentionedBot || message.mentionAll
```

> **为什么不下推给 SDK**（真机教训）：SDK `safety.PolicyGate.evaluate()` 的判定颗粒度是**会话级**
> （只看 chatType + 是否 @机器人），**不区分消息类型**——群里发文件时消息物理上无法携带 @，
> 于是 `requireMention=true` 时 SDK 会把文件消息全部拦在 `"message"` 事件之前，桥接侧针对
> `InboundKind.FILE` 的豁免（`PolicyGate.isAllowed`）**永远执行不到** ⇒ 文件从未登记 ⇒
> 后续引用时登记表必 miss。修复：SDK 侧全放行，门控与 FILE 豁免都在桥接侧这一个可观测点上判定。

> ⚠️ `im:message.group_msg` 审批门槛高（需向飞书申请敏感权限），大多数场景不需要开启。默认 `mention`（仅@唤起）已满足「群里 @ 机器人触发 agent」的正常使用。

---

## 页面结构（LarkBridgePage）

**吸顶头**用例之一（另两处：`AgentCenterPage` / `McpExposePage`，见 `docs/ui-style-guide.md`）：滚动放在 content 内部，`PageScaffold` 的 modifier 不得再带 `verticalScroll`。🔴 本页是唯一开 `bottomPenetration = true` 的吸顶页（批 5-b 底部真穿透）：包裹层移除底部玻璃带让位，各页签滚动容器以 `padding(bottom = LocalScaffoldGlassInsets 底值)` 挂 `verticalScroll` 之后消费——内容滑入状态栏玻璃下被 blur 折射。

**页签（P5 2026-09-26 五签收口四签）**：`驾驶舱 / 配置 / 日志 / 统计` 四页签，默认落「驾驶舱」；原「运行」页签已并入驾驶舱（`BridgeStatusPanel` 整体保留在其内）、**「路由」页签已随 P3 routes 退役删除**（`RouteRuleEditor` 是死 UI 一并清零）。页签组件 = `:ui` `SegmentedControl`（哑光胶囊分段控件，预览稿 `.seg` 直译，零动画）——非 M3 TabRow。

| 区域 | 面板 | 职责 |
|------|------|------|
| 顶部 | `OperationBar`（批 5-b 收敛版） | 常驻仅**连接态行**（**状态圆点 + 文字**，🔴 顺序陷阱观测点）+ 展开箭头；🔴 启动/停止/发测试消息已迁至驾驶舱 hero（本组件不再重复）；展开区 = 导入/导出/打开目录 + 测试消息两输入框（chatId/testMessage 状态由 `LarkBridgePage` 外提共享——hero「测试消息」与折叠区同源；P1 动线批 2026-09-25：chatId 空时点 hero 钮 = 展开本区 + **焦点直落 chatId 输入框** + 空值引导容器条（/whoami 查询法），展开区底部新增「发送测试消息」钮**就近直达**——旧「填完回 hero 再点」三跳链路退役）+ 回显区（统一 **ResultBanner 三档容器条**：成功 successContainer / 警告 warning 合成底 / 失败 errorContainer，5s 自动清除） |
| 驾驶舱 tab | `CockpitPanel` + `CockpitLogic` | **连接 hero 区**（16dp 圆角无标题卡 `AppShapes.glassPanel`：iconHero 云系列状态图标七态映射 + 状态主行 + 副行 + 🔴 右侧按钮行 = 启动\|停止单钮随状态切换（busy 内嵌行内进度圈长在发起动作的钮上）+ 测试消息；staggered 一次性入场 240ms）/**进行中的确认区块**（IA 批⑤ 2026-09-27：`pendingViews` 非空才渲染——每行 LOCK 图标 + 工具名 mono + 发起者（openId 前 8 位）+ 剩余秒 pill（mm:ss，负值「即将超时」warning；🔴 时钟锚 pendingViews 帧——audit 重读锚点在确认窗内冻结）+ 已读 N 人（`Bridge.messageReadCount` 有界翻页聚合 5 页 500 封顶；未发卡「已读 —」））/**快速开始向导**（P5 2026-09-26 五步化——**第 0 步「AI 端点」前置**：`aiEndpointReady`（端点就绪订阅流——发布点 = 端点快照单点刷新，页面 collectAsState 即时重组）未就绪时「去配置 AI」经 `:core` 中立槽 `GoAiSettingsHook` 跳 AI 设置页；CheckCircle/Circle 图标态 + x/5 进度 + AI 端点→凭据→启动→后台 7 项 checklist（顺序陷阱警告容器条）→首条消息；**LB-01（批 9）存续改「后台清单未完成」驱动**——纯新用户（凭据全空且配置文件不存在）展开态直出，凭据就绪（扫码回填/手填）后**不再整卡消失**，收成 collapsible 折叠态（标题「快速开始 · 后台配置未完成」，x/5 进度进 headerTrailing pill）；清单勾完或首条已收 = 消失；凭据半填维持旧收起语义；IA 批③起第 1 步「去填写」深链配置页快速上手卡并聚焦首个空基础字段）/**grid2 双卡**（批 5-b：最近任务 \| 今日统计 半宽双卡——最近任务 = audit 尾部 5 条三态状态图标 + FAILURE 经 `TaskErrorHint` 人话修复 + IA 批⑤行尾表情徽标（`CockpitPanelLogic.userReactionBadge` 过滤机器人自点后计数「👍 ×N」，audit 行带 `userMessageId` 才有锚点——旧行诚实缺席；行渲染件 `cockpit/RecentTaskRow.kt` 独立 internal 文件——2026-10-11 B4，被统计页 `UsageStatsPanel` 跨包复用）；今日统计 = 近 7 天迷你柱状图（`UsageCharts.UsageBarChart` height=`chartCompactHeight`，与统计签同语言）+ 今日计数行（`UsageStats.summarizeToday` 真实审计口径）；空态 `EmptyStateView`）/ 快捷操作行（仅 环境预检/去配置——启停上 hero） |
| 驾驶舱 tab | `BridgeStatusPanel` | 运行状态快照（running / connection / confirm / counters + `appId`（行尾复制按钮）/ 最近异常 / 停止原因；核心三行带状态圆点，标签 labelSmall 弱化） |
| 配置 tab | **机器人主卡**（`BotIdentityCard`，P5b 2026-09-26） | agent-first 身份面：头部行「机器人主卡」+ 右上角「本地 AI · agent-first」pill（aiAccent 语义色）；3 个身份字段（`agent.persona` / `agent.systemPrompt` / `agent.groupMode`）复用 FieldRow 渲染器（`field/FieldRow.kt` 独立 internal 件——2026-10-11 B4 自 ConfigFieldRenderer 提取，ConfigFieldRenderer 与本卡双侧消费；label/hint/editor 全部来自 `BridgeFields.ALL` 声明表，UI 零硬编码） |
| 配置 tab | 能力中心卡（`BotCapabilityCard`，P5b 能力清单卡 → IA 批②升级 + LB-19 头部行「能力中心」+ 灰 pill「常备 N · 跨会话 6」） | `ALWAYS_REGISTERED_TOOLS` 静态 **11** 工具名 pill 平铺（🔴 不触 `BridgeToolRegistry.build`——运行时注册表需 request 参数，UI 展示与运行时注册解耦）+ **跨会话 scope 验证区块**（L2 恒注册；唯一授权闸 = 飞书后台 scope——勾选面已随 `agent.nativeTools` 配置段退役）：六行 `ScopeVerifyRow`（`NativeToolGrant.label` + 一句人话 + scopeHint 等宽胶囊 + 复制 + 后台三件套 + **探测验证三态**（`GrantProbe` + `Bridge.probeNativeGrant`，🔴 运行时观察值不落盘））→ 底部「复制全部所需权限」）；**能力域门控区块已删除**（v13→v14 后无开关可配）；文件类工具按消息引用动态注册不列 |
| 配置 tab | 配置编辑器（`ConfigFieldRenderer` + `field/*`） | **四分区 IA（`P5FieldGroups.ZONES` 单一真源；对账 47 = 主卡 3 + 能力域门控 0（已退役）+ 分区 44）**：工具条（搜索 label/hint/id 全文匹配 + `@modified`/`@restart` 两 chip + 已改徽标——过滤模式=命中字段平铺直接可编辑，与门控正交）→ **新用户门控双形态**（`QuickSetupGate` 两徽章组判定：凭据/工作区任一未完成 ⇒ 四分区目录整体替换为快速上手卡——恰 BASIC 5 件 + 两 pill 完成徽章行（✓凭据 ✓工作区）+ 「高级配置（N 项）→」弱入口（会话级 advancedExpanded）；填全**原地变形**回四分区=纯数据驱动重组零状态迁移；判据是徽章组而非字面判空——region 是 Choice 默认值永不为空）→ **四分区平铺**（`SectionCard` collapsible 默认收起，标题行即目录「分区名 · N 项 · 已改 M · 错误 K」：连接与准入 9 / 任务行为与限额 18（内嵌运行时/Agent 策略/进度/确认/产物五小节）/ 文件消息 9 / 可观测性 8）→ 行级增强（FieldRow：已修改左缘竖条 + hover「恢复默认」 + RESTART 字段恒带「重启生效」琥珀 pill；**响应式双形态**（`FieldRowLayout`）：横排三段时 hint 段带 `fieldHintMaxWidth` 320dp 宽上限（无上限时软换行 hint 会吃满剩余宽把编辑器 weight 段压到零宽），行可用宽 < `fieldRowStackBelowWidth` 620dp 时降级纵排（lab+pill 行 / hint 整行 / ctl 整行——窗口最小尺寸按物理像素钳制，HiDPI 缩放下窄窗必经纵排兜底））→ 驾驶舱向导「去填写」深链此卡并聚焦首个空字段（`configFocusTicket` 票据 + snapshotFlow 等 FocusRequester 就绪）；tier 分层元数据保留（`BridgeFieldsTest` 断言锚） |
| 配置 tab | `SecurityHintsCard` | 分层安全提示（**全容器条化**：确认超时动态上限 + 确认链路状态回显（`ConfirmRuntimeStatus` 四态穷尽；🔴 现网 `confirmStatusRef` 只有 `Disabled` 一个写入点——MCP 自检链退役后 `Ready`/`Probing`/`Unavailable` 已无生产产生方，`resolveConfirmStatusDisplay` 因此恒走 `Hidden`，该行不渲染）+ **两条暗门警告**：allowlist 空名单=拒所有人（指引用 /whoami）、agent 启用但工作区空=沙箱拒所有路径。「禁删失效」hint 已随 CLI 权限档位（`permissionPreset`/`prohibitDelete`）退役——无任何提示时整卡不渲染） |
| 配置 tab | `ConfigCheckPanel` | 配置校验回显（**结论容器条** + 字段行瘦身 2-3 行：label + id 复制按钮 / 等宽生效值 + 校验图标 / issue；`appSecret` 掩码「前 4 后 4」） |
| ~~路由 tab~~ | ~~`RouteRuleEditor`~~ | **已随 P3/P5 删除**（routes 数据体系退役——本行仅存历史参照） |
| 日志 tab | `LogConsolePanel` | 日志台（`LogBus` 环形缓冲上限 1000 + 显式「清空」按钮；级别过滤默认选中 ERROR/WARN/INFO——P0 一致性批 2026-09-25 起，旧默认仅 ERROR+WARN 易误以为缺行；流式行 `contentType` 复用组合缓存槽；LB-12：行首 `HH:mm:ss` 时间列 + **复制=当前过滤结果**（⚖️ 拍板 2026-09-29 推翻旧「复制恒全量」：真过滤态首行披露头「过滤态 N/M」+ 按钮文案随态「复制过滤结果/复制全部」；全量态无头；每行四段 `HH:mm:ss\tLEVEL\tTAG\tmessage`——🔴 Shell 输出台同款时间列 = **未排期欠账**（跨台边界，2026-10-02 批 11 DS-19 记档））） |
| 统计 tab | `UsageStatsPanel` | 使用统计（加载态 hero 进度圈；标题行 CardHeader；发送者行 hover 复制 openId）。文件拆分（2026-10-11 B4）：发送者详情二级页独立 `stats/SenderDetailPanel.kt`、图表 Composable 归 `stats/UsageCharts.kt`；`StatLine`/`formatElapsed` internal 化留主面板（双侧共享） |
| 全局 | `RegisterAppDialog` | 扫码建应用流程对话框（见「扫码建应用」节；四态均带状态图标/进度圈，复制链接走 CopyButton 反馈） |

**视觉重设计（2026-09-10；批 5-b 增补）**：页签切换 `AnimatedContent` 过渡（fadeIn standard + 温和上浮 / fadeOut fast）；页签组件换 `SegmentedControl`（哑光胶囊）；驾驶舱对齐预览稿页③（hero 按钮行 + grid2 双卡）；驾驶舱 audit 一次 IO 同喂最近任务/今日统计/迷你图三处；底部真穿透（见上「吸顶头」节）；全部 SectionCard 经共享组件默认 `tonal = true` 落到真卡容器；按钮文字回归 M3 默认 labelLarge；全页 emoji 状态符清零（换 Material 图标体系）；零白名单纪律不变（守护测试 `--rerun-tasks --no-build-cache` 全绿）。

**Secret 编辑**：`connection.appSecret` 在配置编辑器里用**全掩码 + 用户主动 reveal**（🔴 eye 显形钮已实现——`ConfigFieldRenderer` Editor.Secret trailing `VISIBILITY/VISIBILITY_OFF` 切换，2026-10-09 修复批补齐此前「契约有、实现无」的缺口；eye 钮恒 enabled 不挂 hover 门控——disabled 钮不进焦点链，键盘用户零 reveal 通路）；在 `ConfigCheckPanel` 回显里用**前 4 后 4**掩码（与 CLI `check` 同规则）。🔴 掩码值绝不出现在日志/审计。

---

## 运行与诊断操作

- **启动/停止**：`Bridge.start()` / `stop()`（门面）。启动前拒绝点（appId 空 / 同 appId 已持锁 / 单实例锁失败 / 同 appId 另一条长连接在线 / 长连接失败）会写入 `stopReason` / `lastError`，经 `BridgeStatusPanel` 回显。
- **一键重启（2026-09-14）**：`LarkBridgeViewModel.onRestart()`——RESTART 字段（appId/凭据/工作区根等）变更后黄条上的「重启桥接」按钮，`busyAction=RESTARTING` 驱动转圈，`stop()` 完成后接着 `start()`（串行、`runCatching` 兜底不炸 VM）；仅桥接运行中显示（未运行时保存即下次启动生效）。🔴 智能调度开关与 AI 端点配置已免重启（HOT + 1Hz 刷新回路），按钮只服务真正需要重启的字段。🔴 配置签 L0 黄条（「N 项待重启生效」）已带 trailing 内联「立即重启」钮（2026-10-09 修复批——重启动作此前只藏在默认折叠的 OperationBar 内，黄条纯文字指路是断链；`enabled = !busy`）。
- **环境预检（G-B）**：`Bridge.probeEnvironment()` → `EnvironmentProbe`（`agentBackend`（App 形态恒 `"koog"`，CLI 形态 `"none"`）、`agentAvailable`、`connectionAppId`、`running`、`credentialReady`），零网络零 IO——只读执行器 seam 与配置/运行态原子量（外部 CLI 后端退役后不再有 PATH 探测）。
- **L2 授权验证探测（IA 批② 2026-09-27）**：`Bridge.probeNativeGrant(grant, testChatId, lookupOpenId, defaultFailText)` → `String?`（null=通过；失败返人话）——经**运行中网关**的只读探测（listMessages pageSize=1 / getChatInfo / resolveSenderProfile），🔴 恒不另造网关绝不 connect（D19 互踢——首版曾走 `BridgeGateways.resolve` 另造一次性网关，会把主桥接踢下线，已整体删除）；手动触发、失败不重试、结果不落盘（运行时观察值）。接缝链 = `Bridge` → `LarkBridgeManager.probeNativeGrant` → `BridgeStore`（默认 null）→ VM `grantVerifyStates`。
- **驾驶舱三个只读窄口（IA 批⑤ 2026-09-27）**：`Bridge.pendingConfirmations()`（进行中确认视图 `PendingConfirmView`——非挂起纯内存快照，搭 1Hz 状态帧拉取）/ `listMessageReactions(messageId)`（`listReactions` 透传——含机器人自点，feature 侧 `CockpitPanelLogic.userReactionBadge` 按 botOpenId 过滤）/ `messageReadCount(messageId)`（`readReceipts` 有界翻页聚合：单页 100、上限 5 页 500 封顶）——全部恒经运行中网关/服务实例（D19 纪律同上）、runCatching 恒不抛、未运行返回空表/null 诚实缺席。
- **发测试消息（G-B）**：`Bridge.sendTest(chatId, text)` 经 gateway 出站；入口 = 驾驶舱 hero「测试消息」钮（主入口；🔴 AppButton + disabledReason——置灰必带原因「先启动桥接，再发测试消息」/「有操作进行中」，禁静默置灰）+ 操作区展开底部「发送测试消息」钮（就近第二入口，免回驾驶舱 hero 二次点按；chatId 空时 hero 钮转展开+聚焦+引导）；dryRun 语义标注留在操作区展开区（干跑 = SyntheticGateway 不出网 / 直连 = 真实发出）。
- **宿主直发文件门面**：`Bridge.sendFile(chatId, file)` → `FileSendOutcome?`（桥接未运行返回 null，与 `sendTest` 同契约；sealed 四态 `Sent/InvalidInput/Denied/Failed` 供宿主逐态渲染人话）。文件消息两步式：`gateway.uploadFile` 得 file_key → 发 `msg_type=file`（与产物回传通道同款传输形态）。输入校验（chatId 空/文件不存在）与出站排除表在传输前判，拒绝不消耗上传配额。🔴 排除表判据（`SensitiveFilePolicy.isForbidden`）与 `send_file` 工具、`[产物]` 标记链**同一张表**（排除表共用单一执行点，见下「出站敏感文件排除表」节）——调用方是宿主 UI、路径非 LLM 可控，但 fail-closed 不因信任来源放宽：排除表对任何调用方形态恒一致。宿主侧消费面 = 报告外发胶水 `LarkBridgeManager.deliverReport`（接收会话 = `connection.testChatId`，零新配置面；链路见 [pages/log-workbench.md](log-workbench.md)「报告出站」节）。gateway 调用落点须留在 `Bridge.kt`（`ReplySinkUniquenessTest` 旁路白名单按文件登记，挪文件即红）。守护测试 `BridgeSendFileTest`（`:lark-bridge`）。
- **扫码建应用（registerApp，2026-09-10 转正）**：`Bridge.registerApp(onQr)`（🔴 **未装配也可用**：门面内一次性网关、用完 close、绝不抛——三种失败归一为 `RegisterAppResult` sealed）。UI 为 `RegisterAppDialog` 四态对话框（Waiting/QrShowing(浏览器打开二维码+复制链接)/Success(自动回填凭据)/Failed(重试)）；驾驶舱快速开始 step1 提供「扫码建应用」入口。SDK 轮询阻塞最长约 600s 且不可中断——🔴 **QrShowing 态可收起对话框**（2026-10-09 修复批：取消/scrim/Esc 置 UI 回 Idle 但轮询照跑，迟到的终态经横幅落地不再重新拉起对话框，凭据回填恒走 updateConfig VM 级链路）；🔴 **再入守卫 = `registerJob` 活性判据**（UI 态可被收起，Job 活性才是「轮询在途」真源——按 UI 态判会在收起后放进第二个并发 registerApp 双 SDK 轮询竞速；再入拒绝横幅明示「SDK 轮询最长约 600s、不可中断」）。
- **`/whoami` 内置命令**：`SystemHandler` 精确匹配 `/whoami`，回复发送者 openId + 会话 chatId + 填信任名单指引——「填 `access.trustedUsers` 需先知道自己的 openId」的自助途径。准入已恒放行 ⇒ `/whoami` 恒可答（不再有被白名单拒绝的自救场景）。
- **表情任务信号**：agent 类任务（AGENT/COMMAND→agent/FILE/AUDIO）在用户消息上加 `OnIt` 接单、终态换 `THUMBSUP`/`CROSS_MARK`，取消撤回 `OnIt`——流式卡原地更新不触发推送，表情是手机端唯一的完成信号（同类 cc-connect done_emoji / Copilot eyes 同构）。接线点 `runAgentPipeline`（三入口共用），`NonCancellable` 保证取消路径也达；emoji key 失败 runCatching 静默（最坏无表情）。LIGHT 类（ping/help/清单）不加——毫秒级响应加了是噪音。
- **`状态` / `/status` 内置命令（2026-09-12 在线状态批次新增）**：`SystemHandler` 精确匹配，回复桥接运行真值——连接态（在线/重连中 + botName）、连接自（时刻）+ 运行时长、累计处理消息数（intakeReceived/completed/failed，🔴 累计口径——无日界分桶数据源，不伪造「今日」）、存活 agent 进程数、数据截至时刻（sampledAtMillis——🔴 语义诚实：不伪造「心跳」字样，SDK 内部 90s ping 无我方可观测钩子）。**语义结构性不可能撒谎**：机器人活着才答得上来，桥接死了自然无回复，「无回复」本身就是离线信号（飞书不因对端不在线拦截消息，发送方静默成功）。LIGHT 档不占许可。🔴 **补答边界**：断线 ≤30 分钟内重连时，状态指令事件靠飞书重投（15s/5min 档）补答；**断线 >30 分钟 ⇒ 事件被 StaleFilter（30min 窗口，先于审计）确定性静默丢弃、永不补答**——此时请重发一次状态指令。🔴 **撞名守卫**：「状态」是高频中文词，若用户自定义 route 触发词与它撞名且排在 SystemRoute 之前，状态指令会被数组序遮蔽——快照构建时 WARN 提示。
- **本地在线指示真实化（2026-09-12 同批次，方案 C）**：① 修「UI 计数冻结帧」欠账——`publishStatus()` 此前只在生命周期跳变时刷新，运行中计数恒为启动时刻旧帧；现 metricsScope 每 tick（1Hz）回调 `publishStatus()`，UI 流水线计数/日志行数/存活进程实时刷新。② 修「重连中不可达」缺陷——`RuntimeBundle.handleSignal` 对 Reconnecting/Reconnected 从 `log.debug` 升为具名分支（INFO 日志 + `Bridge.onGatewayConnectionSignal` 状态机守卫写入：仅 running 且当前态 ∈ {Connected, Reconnecting} 时迁移，Blocked/Draining/Idle 由 Bridge 独占）；UI「重连中」分支生产可达，CLI serve 重连迁移在控制台可见。③ `BridgeStatusSnapshot` 新增 `connectedSinceMillis` / `lastSignalAtMillis` 字段（驾驶舱 hero 区显示「连接自 HH:mm」，运行状态卡显示连接时长）。

## 产品默认值裁定（2026-09-10）

- **agent 恒为硬依赖**：`agent.enabled` 总闸已退役（v9→v10 迁移把存量显式值折 `experimental.retiredV10` 保底）——不存在「关闭 agent 仍收发」的形态；装配完成但本地 AI 端点未配置时启动被 `Refused(AgentUnavailable)` 硬拒绝。服务面收口靠飞书后台的机器人可用范围（准入在桥接侧恒放行）；高风险操作按 `access.trustedUsers` 判定。
- **凭据必填校验收窄**：`ConfigValidator` 的 appId/appSecret 必填只在「凭据部分填写（用户在配置过程中）」或 `autoStart=true` 时触发；凭据全空 = 全新用户合法中间态（由 `Bridge.start` 的 Refused 兜底）。防「向导里先填一项凭据就被校验失败不落盘卡死」。
- **默认 routes 3 条骨架**（P3 退役批后：system/agent 空槽/file——业务指令与静态回复已删）+ 负向守护测试防业务回流（见「路由（routes）」节）。

---

## 独立 CLI（`:lark-bridge`，四子命令）

| 子命令 | 用途 |
|--------|------|
| `serve` | 启动长连接常驻服务（**无 agent 应答形态**：消息收发/文件登记/固定指令照常，agent 类消息由 `NoAgentRunner` 显式报 `agent_backend_unavailable`） |
| `send` | 发送一条消息 |
| `check` | 配置校验 + 全 47 字段自解释输出（`appSecret` 前 4 后 4 掩码；不分层，全量输出；计数以 `BridgeFieldsTest` 为真源） |
| `handle-event` | 处理单个事件（调试/回放） |

🔴 **breaking change**：旧 CLI 的 **30 个业务旗标已删除**，配置改由单一 JSON 文档承载；`--set <点路径>=<值>` 为 **ephemeral 永不持久化**（只影响本次进程）。纯 CLI 用户无旧 DataStore 数据可迁（jvmTarget 11 访问不到 `:core`），CLI 侧不跑 T15 迁移。

> **`permission-server` 子命令已删除**：它是 CLI 委托后端（claude 的 `--mcp-config` 权限确认通道）专用的服务端，随委托链一并退役（`PermissionServerLauncher` / `McpPermissionServer` / `ConfirmBus` 本地 TCP 全删）。App 形态的确认链不依赖任何子进程：`ConfirmService` 状态机 + `GatewayConfirmNotifier` 直接走飞书卡片出站，裁决回传经 `InteractionHandler`（按钮 / 「确认 &lt;id&gt;」文本）。

---

## 配置迁移（T15，一次性）

- **触发**：**首次打开飞书桥接页时**后台异步迁移（`LegacyConfigImporter.importOnce()` 的生产唯一调用链 = `LarkBridgeViewModel.init { viewModelScope.launch { store.ensureLegacyMigrated() } }` → `BridgeStore.ensureLegacyMigrated()`），在 `BridgeAssembler.assemble()` 与首次 `save()` 之前完成，**不阻塞 App 启动**、**不放全局 `JInitializerManager`**。🔴 因首页用 `AnimatedContent(targetState = currentPage)` **只组合当前页**，迁移会推迟到用户**首次进入桥接页**那一刻，而非「App 一启动就迁移」（上次停留在别的页的用户，启动时不会触发迁移；本次验证之所以一启动即触发，是因真实 DataStore 里 `last_page = LarkBridge`，`AppViewModel` 恢复了上次访问页）。
- **纯 CLI 用户不触发迁移**：`serve`/`check`/`handle-event` 等 CLI 子命令跑在 jvmTarget 11 的 `:lark-bridge`，访问不到 `:core`（jvmTarget 25）的 DataStore，故 CLI 侧不跑 T15 迁移，需自己创建 `bridge-config.json`（或用 `--config` 指向一份）。
- **数据根改名（`~/.easy_dev_toolbox` → `~/.toolbelt`）纯 CLI 用户手动迁移**：CLI 缺省数据根已是 `~/.toolbelt/lark`，但 CLI 形态不跑 App 的启动改名迁移——旧机器存量配置需手动 `mv ~/.easy_dev_toolbox ~/.toolbelt` 一次（或用 `--data-dir` 显式指旧路径过渡）。
- **`connection.autoStart` 真实行为（F-2 接线后）**：桥接门面装配完成后，若 `connection.autoStart == true` 且当前未运行，则在协程里调用一次 `Bridge.start()`。🔴 落点在 **feature 侧**（`LarkBridgeStore.ensureAutoStarted()`），**不改 lib 侧 `Bridge` 门面语义**（自动启动是宿主策略：CLI `serve` 本就总是启动、`handle-event`/`check` 绝不启动）。🔴 **只在装配那一次触发**（`autoStartGuard: AtomicBoolean` 幂等闸门，`compareAndSet(false, true)`），不在每次进入桥接页时重复触发（否则用户每次切页都会重连飞书）。🔴 **判定排在迁移之后**（`LarkBridgeViewModel.init` 内先 `ensureLegacyMigrated()` 后 `ensureAutoStarted()`），否则会用迁移前的默认值 `false` 漏启动。`Bridge.start()` 是 suspend，只在 `viewModelScope.launch` 协程里调，**绝不在 EDT 上 `runBlocking`**（`feature-larkbridge/src/jvmMain` 的 `runBlocking` 命中数为 0）。
- **门控顺序（F-8 收窄后）**：
  1. `bridge-config.json` **已存在 ⇒ 跳过**（这是主闸门，保护手工/新 UI 编辑过的文档不被旧值污染）。
  2. 否则探测旧 DataStore 里是否真有哨兵 key（`lark_bridge_app_id`）：
     - **无旧数据** ⇒ 置标记（若尚未置位）后返回 `NoLegacyData`。标记此后避免每次启动重复探测 DataStore。
     - **有旧数据** ⇒ 🔴 **无论标记是否置位，都执行迁移**（此时 config 不存在，场景只有「首次升级」或「用户刻意删掉了配置文件想重新导入」）。
  3. 迁移**失败** ⇒ 不写任何文档、不置标记，下次自动重试。
- **幂等标记 `lark_bridge_legacy_imported` 的收窄语义**：此标记**只**用于「确认无旧数据可迁后避免每次启动重复读 DataStore」；它**不再**是迁移的无条件短路闸门。`bridge-config.json` 存在才是跳过迁移的主闸门。迁移成功后 config 存在 ⇒ 下次启动被步骤 1 拦住 ⇒ 不会重复迁移。
- **映射**：只把 `hasKey == true` 的旧 key 放进 Map（缺失 key 绝不填空串/默认值，否则 `AgentRoute.triggers` 会从 `["ai","/ai"]` 降级为 `["ai"]`）；lib 侧纯函数 `LegacyDocumentMapper` 承载 35 key → 新文档映射。
- **旧数据不清除**：迁移成功后不删旧 35 key（保留回退能力）。
- **手工重新导入**：删除 `bridge-config.json` 后重启 App ⇒ **会**重新从旧 DataStore 迁移（F-8 后文档承诺兑现）。若想彻底从零开始（不要旧设置），需同时清掉旧 DataStore 里的 `lark_bridge_*` 键，否则重启会再次迁移回旧值；或迁移后在 UI 里修改。

---

## 页面行为基线（2026-09-15 S5 建立；弱耦合——基线号只在文档，测试引用真实存在的测试名）

| 基线 | 当前行为 | 变更类型 | 自动化证据 | 真机证据 | 回滚路径 |
|------|---------|---------|-----------|---------|---------|
| BL-1 群里发图 @机器人 | 收到明确降级文案（说明类型 + 给出转文字/文件的出路） | **新增**（S1；此前零响应零提示） | `UnsupportedKindNoticeTest`（穷尽 12 kind + 端到端行为） | 待真机：发图收文案截图 | `Admission.runUnhandled` 非 TEXT 文案与 TEXT 静默语义回退 |
| BL-2 未匹配计数可见 | 状态页「未匹配消息」行（非零警示色） | **新增**（S1；计数器早已有但 UI 不可见） | `CountersTest`（26 项快照不断言 UI）+ UiGuard 零白名单 | 待真机：状态页截图 | 删行即回 |
| BL-3 侧栏桥接活动指示 | 桥接在跑 ⇒ dock 图标右下 primary 圆点 | **新增**（S3；2026-09-15 真机验证通过——首验发现订阅时序竞态，修复见 `LarkBridgeManager.attachHostLoops` 注释） | `ToolPageRegistryNavTest` S3 三组用例 | ✅ 2026-09-15 真机确认 | `navActivity` 置 null 即回 |
| BL-4 桥接页随 App 退出收敛 | 参与者退出序：桥接先停、后台回路随 AppHost scope 收 | **改善**（S2；原依赖网关 scope 一并收） | `AppHostGuardTest` A/B 组 + `BridgeAppExitEdtBudgetTest` | 待真机：关窗后 jstack 无残留线程 | AppHost.shutdown 参数化，回退单 commit |
| BL-5 发测试消息旁路 | 不经频控（用户主动探针） | **保留**（已登记旁路，`ReplySinkUniquenessTest` 白名单带理由） | `ReplySinkUniquenessTest`（第四处旁路即红） | — | — |
| BL-6 确认卡出站旁路 | 准入前发出、无消息回合上下文 | **保留**（同上） | 同上 | — | — |
| BL-7 确认卡已答态 | 裁决（按钮或「确认/拒绝 <id>」文本）后原确认卡整卡替换为已答态（「✅ 已批准 / 已拒绝」标题、按钮消失、保留原工具与参数上下文）；卡更新失败降级为纯文字回执（answeredQuestionCard 已答卡语义） | **新增**（2026-09-15 卡片借鉴批；`im.v1.message.patch` 原位更新，30KB 上限、仅 interactive 消息） | `InteractionAnsweredCardTest` 5 用例（含降级/未回填跳过）+ `ConfirmServiceTest` 捎带 3 用例 | 待真机：点批准后原卡变已答态、另窗口旧按钮消失 | `DecideResult.Ok.cardMessageId` 判 null 跳过即回旧版行为 |

🔴 弱耦合纪律：基线号**不**要求出现在测试方法名里（87 个测试文件、1054 个 `fun` 声明——
强耦合会把成本推到 2-3 人日并触发一次大规模改名，否决）；自动化证据列引用**真实存在**的测试名。

## 未支持消息类型处置矩阵（2026-09-15 S1「不静默丢失」）

`InboundKind`（`lark-gateway/.../GatewayModel.kt`）共 **12 值**，处置三分：

| kind | handler | 未命中兜底行为（`Admission.runUnhandled`） |
|------|---------|------------------------------------------|
| TEXT | 5 处分支（`BuiltinHandlers` 的 `SystemHandler`/`StopHandler`/`InteractionHandler`/`CommandHandler`/`AgentHandler` `match` 谓词——以 grep `InboundKind.TEXT` 实况为准） | 空模板 ⇒ **完全静默**（「路由没配」≠「类型不支持」；审计 `unmatched_silent`） |
| FILE | `BuiltinHandlers` 的 `FileHandler`（引用流登记） | 同上 |
| CARD_ACTION | 卡片回传放行（`PolicyGate.isAllowed`） | 同上 |
| AUDIO | `AudioHandler`（语音转写——**宿主转写缝在位时**；见上「语音消息转写入链」节） | 转写缝缺席（CLI/测试形态）⇒ 明确降级文案（同下行） |
| IMAGE / VIDEO / POST / MERGE_FORWARD / STICKER / SHARE_CHAT / SHARE_USER | **零 handler** | **明确降级文案**（`UnsupportedKindNotice.kt` 穷尽 when 无 else——新增第 13 个 kind 编译失败强制补文案；文案为固定常量，无用户自定义通道） |
| UNKNOWN | 零 handler | 空模板 ⇒ 静默（无法解释的输入不发猜测性文案；审计留痕） |

语义优先级：**kind 降级文案（非 TEXT）> 静默留痕（TEXT）**。审计 summary 两态可分：kind 文案原样 / `unmatched_silent`。

## 降级表（「首选 → 次选 → 最终降级」三列格式）

| 能力 | 首选 | 次选 | 最终降级 |
|------|------|------|----------|
| 文件类型判定 | —（万能解密页已移除） | — | **本地规则**（`core/llm/tools/FileSniffer` + `FileTypeDecision`：xlogProbe/压缩包魔数零 LLM 判定，2026-09-19 P4-C1 下沉 :core） |
| 桥接智能调度 | 本地 Koog agent（端点已配且可用） | —（无外部后端可降级：委托链已退役） | `ai` 前缀照旧命中 AgentHandler 报 `agent_backend_unavailable`（死区回落——明错优于三重静默；未命中消息的兜底调度报 `agent_config_invalid` 并指路 AI 设置页）；CLI 形态同一错误码（无应答引擎） |
| 知识库检索（RAG） | 向量检索（embedding 模型已配 + `embed` 能力声明硬检查） | — | **本地 bigram**（`KnowledgeBase.LOCAL_MIN_SCORE=0.2` + `LocalBigramEmbedder`：相邻字符对计数向量 + cosine，纯内存零网络；本地只负责召回、选段归 chat LLM） |
| 出站回复 | `ReplySink`（频控/退避/锚定） | 已登记旁路：`Bridge.sendTest`（连通性自检，不经频控）/ `BridgeAssembler` 确认卡（准入前无回合上下文）/ `cli/SendCommand`（CLI 一次性行为） | 旁路失败如实报错（`ReplySinkUniquenessTest` 守护：第四处旁路即红） |
| 确认卡已答态（2026-09-15） | `im.v1.message.patch` 原位更新原确认卡（`ReplySink.updateExistingCard`，裁决时 `DecideResult.Ok` 快照捎带 messageId） | 纯文字回执照发（patch 失败/未回填卡 ⇒ runCatching 降级只 log，裁决链路不受影响） | 与旧版行为一致（原卡按钮滞留 + 文字回执——BL-7 回滚路径即此） |

## 不可配置的渠道假设（写死常量清单）

以下五处是**刻意的渠道假设**（非配置项）——改它们=改行为契约，须连守护测试一起动：

| 位置 | 假设 | 理由 |
|------|------|------|
| `BridgeGateways.policy()` | `LarkPolicy(requireMention = false)` | @提及的群门控在 `PolicyGate`（`agent.groupMode`）做，不在网关层重复 |
| `BridgeGateways.sdk()` | 入站恒自研 `:lark-ws`（SDK 侧 transport 恒 webhook） | 防双长连接互踢（下线批 2026-09-12 裁定） |
| `EventIntake.replyAnchorOf` | `ChatType.P2P -> null`（单聊不锚定） | 单聊没有「回复引用谁」的歧义；群聊锚 `messageId` 防串话 |
| `BridgeAssembler.assemble`（StaleFilter 装配） | `StaleFilter(GatewaySdkSettings.DEFAULT.staleMessageWindowMs)` | 陈旧窗口走 SDK 默认（30min），刻意不进 BridgeConfig——防用户把它调没导致重放攻击面 |
| `BridgeAssembler.assemble`（RateGovernor 装配） | `RateGovernor(GatewayLimits.perChatRps, globalRps)` | 频控阈值走网关契约默认值，桥接配置层不透出（NF-05 的结构性前提） |

### 飞书硬限制与调优常量出处（2026-10-06 注释治理批迁入）

`lark-gateway/Tuning.kt` 各常量的「对应飞书硬限制 + 余量」出处登记（源码注释按注释宪法只留数值与余量，考证过程归本表）：

| 常量 | 值 | 飞书硬限制 | 余量计算 | 考证出处 |
|------|-----|-----------|---------|---------|
| `GatewayLimits.perChatRps` | 4 | 同群 5 QPS | 留 20% | 真机限流实验（429 响应 `x-ogw-ratelimit-reset` 头观测）+ 对抗评审修正轮 |
| `GatewayLimits.globalRps` | 40 | 接口级 50/s | 留 20% | 同上 |
| `GatewayLimits.textChunkLimit` | 3500 | 单条消息文本上限 | 分片阈值（属 OutboundConfig 面） | SDK `SendInput` 调研 |
| `GatewayLimits.cardByteBudget` | 24576 | 单卡 30KB（30720B） | 留 6144B（6KB）给 JSON 转义膨胀（按 UTF-8 字节算） | SDK 卡片 schema + 真机卡构建实测 |
| `Tuning.streamThrottleMs` | 250 | 单卡更新 10 次/秒硬限制 | 4 次/秒留 60%；SDK 默认 100（=10/s）零余量必须覆写 | 流式卡节流真机实测 |
| `Tuning.streamThrottleChars` | 4000 | —（增量刷阈值非平台限） | SDK 默认 50 过小；`Throttle.note()` 是「或」关系，只调时间无效 | SDK `CardThrottle` 源码 |

> 出处引用形态约定：历史方案文档的 `[BC:nnn]`/`[SF:nnn]`/`R7①` 字面引用已从源码注释移除（注释宪法禁批次叙事）；原引用目标在 `openspec/changes/archive/2026-09-10-rewrite-lark-bridge/`（behavior-contract / gateway-contract），需要考古时去归档区按错误码/编号检索。

---

## 守护测试

- `LarkSdkIsolationTest`（`:composeApp`，`src/jvmTest/.../arch/`，与 `UiArchitectureGuardTest` 同包，9 项）：SDK 类型只在 `:lark-gateway`。
- `LarkApiBoundaryTest`（`:composeApp`，arch/ 同包，4 项；能力一级化批 D 新增）：**L3 恒排除**负清单——`MessageGateway` 成员名不得出现全局搜索/pin/踢人/群生命周期（update·delete+chat 词元共现）命名；正向锚 = 批 C 基线 29 成员名单 + L1/L2 能力成员在位（防误删致负清单空转）。
- `ChannelSeamGuardTest`（`:composeApp`，arch/ 同包，6 项；能力一级化批 D 新增）：**渠道中立层边界**——① `pipeline/`/`handler/` 不得新建 `LarkTarget.Chat(`/`CardSpec(` 构造点（白名单基线 = pipeline/CardJson.kt 的 4 处，卡片构造唯一入口）；② L1/L2 八工具 Args 反射无 `chat_id` 参数（经 kotlinx serializer descriptor 读——🔴 Java 反射参数名在未开 `-java-parameters` 时恒 arg0/arg1，不可用）；③ `NativeChatSurface` 接口签名零飞书专属类型（LarkTarget/CardSpec/CardJson；渠道中立值类型族允许）。
- `UiArchitectureGuardTest`（`:composeApp`，11 项）：UI 铁律 + 零白名单 + feature 隔离（feature 不得跨页面域 import——跨域复用下沉 `:core`，`ToolExecutionContext`/`DecryptTools` 即此路径产物）。
- `BridgeAssemblerParityTest`（`:lark-bridge`，22 项）：装配点唯一性（`HandlerKinds.build` / `RuntimeBundle.create` / `return Bridge(` / `PipelineSnapshotFactory(` / `assemble(` 落点白名单）+ 全仓只有一处构造 `SdkLarkGateway`；app-server / ⑫ 确认卡槽透传两组用例已随委托链退役删除。
- `DispatchAgentRouteTest`（`:lark-bridge`，9 项）：`ai` 前缀的短路语义（判据 = `backendId == "koog"` 且 `isAvailable`——`dispatchEnabled` 开关已退役）+ resetCommand 放行 + 死区回落 + **CLI 形态（`NoAgentRunner`）永不短路且显式报不可用** + availability 翻真免重启激活。
- `KoogAgentRunnerTest`（`:feature-larkbridge`，101 项）：isAvailable 快照语义 / refreshEndpoint 翻真 + `onEndpointReadyChanged` 同步发射 / 进度事件链 + markStatus 三态 / 超时 TIMEOUT / 2 参 no-op / 提示词分段序（HEAD 工具交付约定齐全且安全段最末 / 性格段在 persona 前 / 安全段在附加指令后 / custom 档经 LlmConfigStore 刷新进 system 消息 / **总开关关闭时提示词不列 send_file 且交付约定改写** / **提示词照旧档零变化** / 侦察四件与 sys·dev·L2·ws·ask_user 各行挂 `listed` 门控且围栏计数句与列出行数一致）/ **混合响应轮工具不丢弃**（三轮 fake 工具调用 → 文本+send_file 混合同体 → 终文本，断言 fake ReplySink 真收到 file——旧 singleRunStrategy 下红、larkDispatchStrategy 下绿，红绿双向验证过）/ L1 三工具直测 + L2 五工具直测 + **接缝在位即全量注册**（L2 / sys / dev / self.status / **ws 两件 + git.read**——注册面已无配置闸）/ **信任名单域准入 + 逐动作确认卡**（sys·dev·ws 三域按 `TrustPolicy` 判定 + 写/执行卡防替答 + 说明卡失败 fail-closed）/ 名称一致性断言（常备清单 11）。
- `BridgeLogToolsTest`（`:feature-larkbridge`，3 项）：基础工具清单（registry 调用名保留旧裸名）+ `extended` 门控并入扩展件 + 桥接围栏拒绝文案逐字保留。
- `SyntheticGatewayTest`（`:lark-bridge`，31 项）：合成网关替身当**被测对象**逐成员钉死——反射穷尽 `MessageGateway` 全部成员名单（名单逐字对齐 gateway-contract）+ 能力一级化批新成员的记录面/可编程注入断言。替身失真 = 下游管线测试全静默假绿，故替身本身是守护对象。
- `ReplySinkNativeCapabilitiesTest`（`:lark-bridge`，15 项）：`image/replyInThread/recallMessage` 三成员出站行为（镜像 file/react 纪律）+ `TrackingReplySink` 账本六出口落账（成功记/失败不记/截断 2000）。
- `NativeChatSurfaceTest`（`:lark-bridge`，14 项）：`GatewayNativeChatSurface` 行为钉死——chatId 闭包捕获（工具参数无 chat_id 的实现前提）/ URL 分流（/wiki/ 两步 / /docx/ 直读）/ 30k 截断 / 群信息含首 50 成员格式化。
- `ToolConfirmGateTest`（`:lark-bridge`，7 项）：确认门适配层——链不可用（service/notifier 缺席）立即 DENIED fail-closed / 拒绝·超时·取消四态归一 DENIED / 超时钳制。
- `AskUserBusTest`（`:lark-bridge`，6 项）：提问挂起总线——register/decide 配对 / 防替答（非发起者回复不完成挂起）/ 超时回 `skipped` 软反馈并注销 / 取消路径不泄漏 pending 项。
- `AskAnswerHandlerTest`（`:lark-bridge`，6 项）：答案回传 handler——引用提问卡文本主通道 / 按钮 `qa-<requestId>:<option>` 副通道 / 非提问类卡片回传跳过 / 无锚点跳过 / 非发起者静默不采纳 / `match` 与 `handle` 竞态时静默交后续路由。
- `P5FieldGroupsTest`（`:feature-larkbridge`）：四分区 IA 守护——主卡 + 能力域门控（已退役为空集）+ 四分区并集==**47**、两两互斥、逐分区计数逐字（**9/18/9/8**）、`isModified`/`RESTART_IDS` 派生面、`ConfigFilter` 三态解析。
- `QuickSetupGateTest`（`:feature-larkbridge`）：新用户门控——默认门控开两组全空 / 凭据单亮优先级序 / 两组全完成门控关 / region 豁免（只改 region 无法翻转门控）。
- `CockpitPanelLogicTest`（`:feature-larkbridge`，10 项）：确认行剩余秒格式化（mm:ss + expiring 位）+ 表情徽标过滤（botOpenId 自点剔除 / 只认 THUMBSUP+CROSS_MARK / 取人最多组 / 空态缺席）。
- `BridgeProbeNativeGrantTest` + `BridgePendingConfirmationsTest`（`:lark-bridge`，`BridgeLifecycleTest` 内测试组）：门面窄口守护——**零新建连接**（D19 互踢红线：`connectCount` 不增）+ 只读（`recordedSends==0`）+ SyntheticGateway 可编程注入断言（listMessages/getChatInfo 探测记录面 / listReactionsResult / readReceipts 翻页聚合上界）。
- `GatewayConfirmNotifierTest`（`:lark-bridge`，4 项）：确认卡骨架四件齐全 / 空 riskLevel 无风险行 / 按钮 token 与兜底行短码同源 / 发送成功回填 messageId 回归。

---

## 五阶段处理链

源码：`lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/pipeline/`

```
飞书事件 → ① Intake → ② Admission → ③ Execute → ④ Reply → ⑤ Observe
```

### ① Intake（`EventIntake.kt`，205 行）

**单协程、`Dispatchers.Default`、全程零锁、非挂起 O(1)**。

五步顺序：`StaleFilter` → `MessageDeduper` → `AccessControl.decide` → `PolicyGate` → `PipelineRouter`

- 陈旧过滤（`staleMessageWindowMs = 30min`）
- 幂等去重（key = `messageId`，LRU 4096 + TTL 12h）
- sender 准入（**恒放行**——sender 级 allow/block 已随 v12→v13 退役，判定点保留以守住五步链结构与计数位；服务面收口靠飞书后台可用范围）
- 策略门控（群@门控；拒绝恒静默不回复——群里没@机器人属正常闲聊）
- 路由（顺序即优先级，`PipelineSnapshot.resolve` 线性扫描 `firstOrNull { match }`）

🔴 **「Intake 单消费者本身就是并发原语」**：全部状态只在这一个协程里被串行访问，不需要任何互斥。`processOne` 被 `runCatching` 包裹——单条消息异常绝不拖垮 intake。

### ② Admission（`Admission.kt`，调度中枢；许可闸 `PermitGate.kt`、超时策略 `TimeoutPolicy.kt` 同包独立文件）

**三档分流 + 许可闸门**。`submit` 是非挂起 O(1)（由 intake 单协程调用，若它挂起会冻结整条管线）。

| 档位 | 谁 | 许可 | 附加语义 |
|------|------|------|------|
| **URGENT** | `StopHandler`、`InteractionHandler` | **绕过** | 先 `ChatLanes.cancelAll(chatId)` 取消该 chat 的在途任务，再执行自己 |
| **LIGHT** | `SystemHandler`(ping/`/commands`)、`ReplyHandler`、**`AskAnswerHandler`**（提问卡答案回传）、拒绝回复、未命中兜底 | **不占** | 立即 launch |
| **HEAVY** | `AgentHandler`、`CommandHandler`→agent、`FileHandler`、`AudioHandler`、`DispatchAgentHandler`（未命中兜底调度） | `PermitGate` | + ChatLane 每 chat 上限 + `queueWaitSeconds` 排队超时 + 繁忙回复合批（`busyReplyCooldownSeconds`） |

🔴 **D11 的直接后果**：2 个长跑 HEAVY 在途时，`/help`（LIGHT）与「停止」（URGENT）**立即执行**，不排队、不占许可。

`PermitGate` 支持 HOT 调参（`runtime.maxConcurrentAgents`）：名义容量 4096，扩容 = 循环 release，缩容 = 后台协程渐进 acquire。

### ③ Execute（`Admission.kt:invokeHandler`）

`withTimeout(总超时) { handler.handle(ctx) }` + 空闲看门狗（`agentIdleTimeoutSeconds`）。

超时策略三档：
- handler 声明 `timeoutSeconds != null` → 该值覆盖全局
- agent 类（AGENT / FILE） → `runtime.agentTimeoutMinutes * 60_000`（默认 10min）
- 其余（system / reply / command） → `runtime.taskTimeoutSeconds * 1000`（默认 60s）

异常捕获顺序：`TimeoutCancellationException` → `CancellationException` → `Throwable`（前者是后者子类，顺序不可颠倒）。

### ④ Reply（`Admission.kt:deliver`）

唯一出口调用点。handler **不得**自行出站——一切回复都经此，因此一切回复都经 `RateGovernor`。出站失败只计数不抛。`ReplyDirective.SILENT` / `ALREADY_REPLIED` 不发任何消息但仍进 ⑤。

### 确认卡已答态（2026-09-15 卡片借鉴批，answeredQuestionCard 已答卡语义）

裁决（按钮回传或「确认/拒绝 <id>」文本）成功后，**原确认卡**经 `im.v1.message.patch` 整卡替换为已答态——按钮消失、标注结果、保留原工具与参数上下文；文字回执照发（群聊后来者可见裁决记录）。链路四环：

- **回填**：`GatewayConfirmNotifier` 发卡成功 ⇒ `ConfirmService.recordCardMessageId`（`cardMessageIdSink` 构造注入，装配点接 confirmService；发卡仍在旁路点，见 BL-6）。
- **捎带**：`ConfirmService.decide` 成功时 `DecideResult.Ok` **快照捎带** `cardMessageId`/`toolName`/`inputSummary`——🔴 捎带而非事后查账：decide 后 `awaitDecision` 随即注销 PendingEntry，事后查会竞态扑空。
- **更新**：`InteractionHandler` Ok 分支经 `ReplySink.updateExistingCard` + `CardJson.answeredConfirm`（非流式、**无按钮**、批准绿标/拒绝灰标）。
- **降级**：卡更新失败/卡未回填 ⇒ runCatching 只 log、跳过更新——裁决与文字回执不受任何影响（`ReplySink.updateExistingCard` 与 `MessageGateway.updateCard` 均带默认实现，SyntheticGateway 等替身零改动）。

🔴 `updateExistingCard` 是**更新既有消息**而非新出站：不占 RateGovernor 令牌桶（同 react 先例），但退避窗口内仍等待（限流期间打 API 只会延长退避）。网关侧 `SdkLarkGateway.updateCard` 走 raw client `im().v1().message().patch`（`sendFileByKey`/`uploadFile` 同款 raw 模式 + 限流双信号）；官方约束：**仅 interactive 消息**（本仓全部卡片出站 msg_type 恒 "interactive"，形态恒匹配）、整卡替换 ≤ 30KB。流式卡（cardkit 实体）不走此接口——它们的更新归 `LarkCardStreamSink` 的 patchElement 流。

#### 确认卡骨架 + 已答态短标识（纯 lib 文案层，CardSpec 零新字段）

- **确认卡 markdown 骨架四件**（`GatewayConfirmNotifier.notify`）：① 风险徽标行（`**风险**：<level>（高风险操作，请仔细核对参数）`——`ConfirmRequest.riskLevel` 非空白才渲染；工具路径自报等级——L2 `create_group_chat` 与 ws/sys/dev 各门控均写死 `RiskClassifier.RiskLevel.HIGH.name`，**不走 classify**：`RiskClassifier` 规则表是 MCP 工具语义，对建群/写文件类动作会误判 LOW）；② 工具名 + 参数摘要（INPUT_SUMMARY_CHARS=120）；③ 信任行「仅发起本次操作的用户可裁决（他人回复无效）」；④ 短码兜底行「无法点按钮？回复 **确认 <hex4>** 或 **拒绝 <hex4>**」——把文字裁决通道显式写进卡（DECISION_REGEX 既有，card.action.trigger 未配置场景仍可裁决）；token 与兜底行短码统一走 `ConfirmService.shortId` 单源。
- **已答态单据短标识**：`DecideResult.Ok` 捎带 `requestId`，已答卡正文末行「单据：cf-xxxx（事后对账短标识）」——🔴 短标识是末段 hex4 前缀匹配（`matchesIdPrefix` 语义）非唯一键，如实钉住不发明新格式。
- **🔴 确认超时装配修复**：生产装配点曾四参构造漏传 `timeoutMillis` ⇒ 确认超时恒落默认 300s（`agent.confirm.timeoutSeconds` 配置不生效，KDoc 声称与实况不符）；现 `timeoutMillis` 必传、装配点经 `clampedConfirmTimeoutMillis` 现读钳制（HOT 语义与 `ToolConfirmGate` 工厂同源），且 `effectiveTimeoutMillis` 随请求快照记账（`newRequest` 第 6 参显式传——记账值与 `awaitDecision` 生效值同源，驾驶舱剩余秒据此计算）。`agent.confirm.enabled` / `selfCheck` 开关已随 v10→v11 退役，确认链恒启用。

### 结论引用锚定（2026-09-12 conclusion-reply-anchor）

群聊里结论「不知道在回答谁」的问题由**引用回复气泡**解决：出站回复链把消息锚定为对提问消息的 reply。`Outbound.replyToMessageId` 此前是死字段（消费方在网关、全仓零生产者），本批激活：

- **注入**：`GatewayReplySink` 构造参数 `replyToMessageId`，私有 `send()` 出口一处 `copy` 注入——text / markdown / card / file 四条出站链**一次全继承**（拒绝回复、未命中兜底顺带覆盖；后续新增链路天然携带）。
- **豁免**：`RequestContextFactory.replyAnchorOf` 唯一判定——**群聊**（`chatType != P2P`）取提问 `messageId`；**私聊豁免**（一对一引用气泡是噪音，行为与现状一致）；🔴 **卡片回传豁免**（`kind == CARD_ACTION` ⇒ null——`fromCardAction` 硬编码 `chatType=UNKNOWN`，只靠 P2P 判定时私聊里点确认按钮的裁决回复会被错误锚定；卡片回传是用户对机器人的交互、不是提问）。默认路径（`EventIntake`）与生产路径（`BridgeAssembler` replyOverride）两个 sink 构造点**调用同一函数**取值（曾内联复制成双源，对抗评审命中两次，已收敛）。
- **file 链 reply 分支**：`sendFileByKey` 在锚定非空时优先走 `im().v1().message().reply`（msgType="file"）；reply 失败且错误属 TARGET_REVOKED 类（230020 撤回 / 230017 无权限 / http 404）降级 create 重发——**产物不丢优先于锚定完整**。
- **接受边界**：长结论分片只首片锚定（SDK 内置 `i==0`）；markdown 走 reply 先转 post、被拒自动降级纯文本且保留锚定（SDK 内置）；进度流式卡不锚定（过程态无需归属，`CardStreamPort` 不扩签名）。

### 过程卡片滚动窗口（2026-09-12；单卡收敛同日；IA 批④状态带 2026-09-27）

过程卡片的推送语义是「**全量替换最新窗口快照**」：渲染层 `ProgressAccumulator` 维持有界窗口（最近 6 条事件，单条截断 `agent.progress.entryMaxChars` 默认 80 字、整卡 2000 字）→ 泵层 `pumpSnapshotsToCard` 把每次快照**直通** `CardStreamSink.tryReplace()` → 网关层 250ms 时间窗节流后 `patchElement` 全量替换。**卡片在任意时刻恒等于「状态 + 最近 N 条事件」的最新快照**——不累积历史、不重复段落（旧增量语义会在窗口丢头时产生重复内容，且「快照变短」会漏 patch，均已修复并由 `CardThrottleTest` 反例钉死）。

**🔴 正文首行状态带（IA 批④ 2026-09-27）**：流式卡 header 建卡后不可变是平台事实（cardkit 无 header 更新通道，`flushTerminal` 只写正文）——状态权威落**正文首行状态带**，卡头恒蓝是诚实形态。四态：`⏳ 进行中`｜`✅ 已完成 · 用时 X`｜`❌ 失败 · <errorText>`｜`⏱ 已超时 · X`。🔴 两条落位裁定（验收修复）：状态带与 footer 都必须挂 **`body()` 底座**——运行中卡每帧经 `ProgressAccumulator.record`/`markStatus` → `delegate.append(body(...))`，`running()` 入口无生产调用点、只挂入口会被下一帧冲掉；`elapsedMillis==0` 不拼「用时」段（现调用点恒传 0——耗时以文字回复为准，拼「用时 0s」是自欺）；`errorText` 是纯码形态 `errorCode（exit=N）`（17 码人话表是 feature 侧 `TaskErrorHint` 既有资产，下沉为后置批不新造）；`OVERFLOW_BODY` 截断兜底不叠加；「**状态**：」行保留（状态带是叠加非替换）。**footer 表情提示**（`REACTION_FOOTER_HINT`「任务完成时，你原来的消息会收到表情提示」）只挂 RUNNING 态（受理期太早、终态已换表情再提是噪）——把既有表情信号机制（用户消息 👀→✅/❌）写进产品文案。

**单卡收敛（2026-09-12 single-card-no-rotation）**：9 分钟主动 TimeDeadline 轮换已**退役**（「飞书 10min 自动关流」是 BC-1035 从未实测的纯假设——gateway-contract §13 第 6 条的 11min 实验一直没人跑，而轮换的代价是每 9min 群里多一张新卡）。**一次任务的过程卡恒为 1 张**（受理卡），终态原地更新。安全网三层：
1. **被动重建**：patch 返回 200750（EntityExpired）或任何**未登记业务码**时自动换新卡续命（复用既有 rotateSegment；重建建卡失败则 sink 关闭退出主循环——修复了 rotateSegment 返回值被丢弃的 P0-1 同款活锁隐患）；已知结构性错误码（300302 等 → CardJsonInvalid）保持关闭不重建。
2. **字节预算轮换保留**：单卡 24KB（`cardByteBudget`，飞书 30KB 硬限防御）仍触发轮换；滚动窗口 2000 字下恒不可达，仅作 `cardMaxChars` 调大时的兜底。
3. **旧卡收尾可观测**：轮换/重建时旧卡的定格 patch + finishSettings 关流失败记 warn 并计入失败计数，不再静默吞。
4. 🔴 **重建预算**（对抗评审补入）：只封死「重建失败」不算收敛——「patch 持续报错而建卡持续成功」时每 250ms 就能成功重建一次（≈4 张幽灵卡/秒，恰是本轮要消灭的刷屏换了个触发路径）。现由 `Tuning.maxConsecutiveRebuilds`（3）+ `streamRebuildBackoffMs`（1s）约束，计数**只由一次成功 patch 清零**（在重建成功后清零等于没上限），超限记 warn 关流。

BC-1035 的最终裁决改为真机顺车：新代码本来就不轮换，跑一次 >11min 长任务观测第 10min 后 patch 是否报错（见 T11 runbook 表 C 档第 13 项）。若出现第 2 张卡且日志带 200750，那是被动重建在工作（预期行为）。

配置项：`agent.progress.entryMaxChars`（Number 40..200，默认 80，HOT）。旧 `patchIntervalMillis` 已删除（编辑器可改但管线零消费的死配置；真实节流是网关固定 250ms）——schemaVersion v4 迁移时非默认用户值折入 `experimental.retiredV3`。`Tuning.streamSegmentDeadlineMs` 常量保留仅供历史参照与 11min 实验复现（KDoc 已标 🔴 退役）。

### 下载进度卡（2026-09-12，独立于 agent 过程卡）

**问题**：引用流的「下载 + 预处理」全部发生在 `resolveReferencedPrompt`（agent 管线**之前**），而过程卡要等 `runAgentPipeline` 才开 ⇒ 大文件下载期间用户零可见反馈（静默数分钟）。

**🔴 为什么是独立的一张卡，而不是复用过程卡**（用户拍板）：过程卡更多是 agent 所用；混用会让卡片语义纠缠、不利于未来扩展、导致逻辑耦合。**「单卡收敛」语义只约束 agent 场景**，故一次任务出现两张卡（下载卡 + agent 卡）是可接受的、预期内的。

`DownloadCardSession`（`lark-bridge/.../pipeline/DownloadCardSession.kt`）要点：

| 维度 | 做法 | 理由 |
|---|---|---|
| 生命周期 | **两阶段**：`of(ctx)` 只做闸门判定（零副作用），`onStarted()` 才真发卡 | 判据是「**真的需要下载才开卡**」——纯文本引用（只走 `im.v1.message.get` 取原文、压根不下载）**一个 API 都不发**，否则用户会收到一张空卡 |
| 渲染 | **线性阶段表**（全部阶段 accumulate）+ `cardMaxChars` 硬截断 | 阶段文案离散有限（「准备引用文件 x…」→「已下载 x，预处理中…」）；过程卡的**滚动窗口**会把「准备下载」这行早期阶段挤出去，而那恰是用户最想看的 |
| 通道 | 复用 `ConflatedSlot` + `pumpSnapshotsToCard` + `CardJson.streaming/terminal` | 不自造队列、不手搓卡片 JSON，遵守「有界内存链路」纪律 |
| 阶段文案 | resolver 的 `report(text)` **同一字面量双路分发**（喂 `ctx.progress` + 旁路给卡） | 绝不写两套文案（必然随时间漂移）；`ctx.progress` 逐字保留服务看门狗与审计 |
| 开卡前文案 | 进**有界** `pending` 缓冲，开卡时一次性铺上 | 「已找回被引用文件 x，开始下载…」发生在开卡信号之前，丢弃会让卡片缺一步 |
| 收尾 | `finally` + `NonCancellable`（`flushAndCloseNonCancellable`），**无条件关 `ConflatedSlot`** | 四态（含 `Failed`——它**不进** `runAgentPipeline`）全覆盖；槽不关 ⇒ 泵协程挂在任务 Job 上永不退出 ⇒ 结构化并发等它、任务永不完成 |
| 心跳 | 开卡后每 `HEARTBEAT_MILLIS`（10s）调一次 `progress.touch()` | 见下 |

**开卡闸门**（复用既有开关，**不新增配置键**——新增会造出「有下载卡但 agent 进度关着」等正交组合）：`ctx.progress is ProgressAccumulator` && `agent.progress.enabled` && `fileMessage.enabled`。

**🔴 心跳顺带修掉一个既有隐患**：`Admission` 的空闲看门狗以 `clock() - progress.lastActivityMillis >= idleMillis` 判死（默认 `runtime.agentIdleTimeoutSeconds` = 120s）。resolver 在「准备引用文件」到最后一次上报之间**不发 touch**，`downloadResource` 内部还有 `Semaphore(2)` 排队期同样无 touch ⇒ **单文件下载超过 120s 时任务被误判卡死并取消**（用户看到超时失败，实际下载正常）。心跳用 `touch()` 而非 `append("心跳…")`：`touch` 只写 `lastActivityMillis`，不写事件、不动阶段表、不动丢帧计数 ⇒ **不污染卡内容与 D18 统计**。心跳协程挂在任务 Job 下、`delay` 是可取消挂起点 ⇒ **绝不阻止取消**（URGENT 停止仍立即生效）。

> **能力上限（勿误判为实现偷懒）**：进度粒度**只能是阶段级**，做不了百分比/字节数——
> ① 下载 API **全量入堆、无 InputStream 路径**（SDK 2.8.5 硬约束；🔴 网关侧已改为直连
> `com.lark.oapi.core.Transport.send`，但它与 `messageResource().get()` 殊途同归，底层
> `OkHttpTransport` 两条读取分支都会物化出 `byte[]`）⇒ 下载中拿不到已传字节数；
> ② 入站事件 `InboundResource` 只有 type/fileKey/fileName/durationMs、
> **无 size 字段** ⇒ 连「总共多大」都预知不了，无法显示「x/y MB」；
> ③ 🔴 **飞书平台限制单文件下载 ≤100MB**（SDK 2.8.5 `MessageResource` javadoc 原文「仅支持下载
> 100 MB 以内的资源文件」；2026-09-12 真机实证 108.1MiB zip ⇒ `http=400` + 业务码 234037
> 「Downloaded file size exceeds limit.」在**下载中**被服务端拒绝）。入站无 size ⇒ 下载前不可预知、
> 登记时预警是伪需求**明确不做**——唯一传导点就是失败那一刻（234037 经 `DownloadResult` 传导进
> 用户失败文案「超过飞书平台 100MB 下载上限…请压缩或拆分后重新发送」，不再误导性地说「请重试」）。

### agent 产物回传（2026-09-12）

agent 跑 skill 生成的报告/结果文件（如日志分析 `analysis_result_*.md`）可以**通用协议**回传给用户，桥接不逐技能定制：

1. **注入约定**：`agent.artifacts.enabled`（默认 true）时，agent 后端 system prompt 末尾追加独立的「产物交付约定」段——告知 agent 在最终回复**最末尾**每行一条 `[产物] <文件绝对路径>`（未产出则不加）。与安全提示段相互独立。
2. **解析**：桥接只认「末尾连续块」的标记行；正文中间的 `[产物]` 字样不剥离不解析。
3. **校验上传**：每条路径过 tilde 展开 → 存在性 → `WorkspacePolicy` canonical 沙箱校验（路径来自 agent 文本绝不信任）→ **敏感文件排除表**（2026-09-18，见下「出站敏感文件排除表」）→ 大小上限（`maxSizeBytes` 默认 25MB）→ 数量上限（`maxCount` **默认 5**——2026-09-12 实测依据：多事件分析 skill 按 N+1 产出（4 事件 = 4 单报告 + 1 汇总 = 5 份），默认 3 会把按序末尾的**汇总报告**最先砍掉；默认值只影响未显式写键的文档，存量已写 3 的需自己在配置页调）→ 扩展名白名单（`allowedExtensions` 空则不限）→ 经 `ReplySink.file()` 两步上传（`im:resource` 得 file_key → raw client 直发 `msg_type="file"` + `content={"file_key":…}`——🔴 SDK `SendInput.file` 首参语义是「本地路径/URL」而非 file_key，不可用），两步各计一次频控名额。
4. **降级**：失败条目标记行原样还原到正文末尾（附原因注释，用户至少可见绝对路径）；agent 不写标记行 ⇒ 零上传零副作用；编排整体异常 ⇒ 原文照发（`CancellationException` 显式 rethrow——停止/超时落在上传窗口时表情与终态不错乱）。仅 SUCCESS 回合处理；交付期每次出站前 touch 喂空闲看门狗（多文件慢传不被 120s 误杀）。
5. **审计**：成功清单写审计行 `attachments`（`<文件名> <- <原路径>`）；**被拒条目清单写同条审计行 `rejectedArtifacts`**（元素即还原到正文的同款行 `[产物] <路径>  <!-- 原因 -->`——一份数据两处消费：正文给用户即时可见、审计给事后取证；旧格式审计行 decode 为 null，空清单落 null 不产空键）。审计行 12→13 字段。

🔴 真机前置：飞书后台需授予 `im:resource` 上传权限（收文件已含该 scope，上传是同 scope 的写方向，真机第一跑确认）；30MB 为飞书文档值，桥接配 25MB 留余量。

### 出站敏感文件排除表（2026-09-18，`SensitiveFilePolicy`）

**一句话定位**：所有**把本地内容发出去**的路径都过同一张排除表——文件通道拦下凭据/密钥类文件与桥接/宿主自身的配置数据；文本出站面对命中段打码（机密根整串整段拒发）。

**为什么需要它**：`send_file` 的交付面从「本消息引用链」放宽到「工作区沙箱」（2026-09-18，见智能调度节「send_file 沙箱放宽」）后出现一条不对称——八件**读**类工具（`probe_file`/`preview_text`/…）仍走严格允许集、**读不了**工作区文件的字节，而 `send_file` 能把整个文件外发。攻击链：引用文件里的恶意文本 → 间接 prompt injection → 诱导 LLM 外发工作区内的敏感文件。若 `workspace.root` 配成家目录或 `~/.toolbelt`（常见配法），明文 `appSecret` 与明文 LLM API Key 都在射程内。

**覆盖四条出站面**（🔴 只装一条等于留同宽旁路——攻击者改走另一条即可）：

| 出站面 | 挂载点 |
|------|--------|
| `[产物]` 标记链 | `lark-bridge/.../agent/Artifacts.kt` 的 `deliverArtifacts` when 链（紧跟 `requireInsideWorkspace`） |
| `send_file` 工具 | `KoogBridgeTools.SendFileTool.execute` 前置闸 3 |
| `Bridge.sendFile` 宿主直发门面 | `Bridge.kt` 的 `sendFile` 传输前置（紧跟输入校验——拒绝不消耗上传配额） |
| **文本出站面**（reply 通道全部进飞书文本） | `lark-bridge/.../pipeline/ReplySink.kt` 的 `GatewayReplySink` 四文本成员（text / markdown / card 双面 / replyInThread）+ 流式卡出站点装饰（受理卡/运行帧/终态卡），判据入口 = `SensitiveFilePolicy.sanitizeOutboundText` |

数据根经 `HandlerDeps.dataRootProvider → runAgentPipeline(bridgeDataRoot) → withArtifactsDelivered → deliverArtifacts` 透传到标记链；`send_file` 侧走 `homeDataRootProvider`；门面侧机密根 = 装配期 `sensitiveRoot`（宿主应用数据根注入；缺省回落 `paths.dataRoot`，与产物回传通道一致）；文本出站面机密根经 `GatewayReplySink` 构造参数注入（`BridgeAssembler` 三处 + `RequestContextFactory` 一处，与文件通道同源）。

**文本面处置策略**（与文件面「整文件拒绝」不同形）：命中段打码为固定占位（`TEXT_REDACTED_PLACEHOLDER`，纯文本安全）+ 整条文本末尾附一次打码提示（`TEXT_REDACTED_HINT`，措辞与文件面 `DENIED_HINT` 区分、自明「已打码」）；**仅机密根路径形态整串出现才整段拒发**（`TEXT_REJECTED_NOTICE`，不出原文任何片段）。打码后发送语义仍为成功（`SendResult.ok` 不变）。文本是字符串非文件 ⇒ 机密根用形态串枚举（literal `~/.toolbelt` + 宿主注入路径串变体，分隔符边界对齐防前缀误杀；`~user/`/`$HOME/` 等别名形态为已知缺口）；凭据名以**文件名形态**（路径末段/独立 token）命中才打码，自然语言普通词（`secret`/`key` 等纯字母裸词散文用法）不打码；普通绝对路径与日志样例全量放行（日志分析主业误杀面为零）。

**判据（任一命中即拒）**：

| # | 判据 | 细节 |
|---|------|------|
| 1 | **宿主机密根内** | 根 = **`~/.toolbelt`**（宿主应用数据根，**不是** `lark/` 子目录）。内含 `lark/`（配置文档明文 appSecret / 审计 / 统计）与 `.datastore/`（明文 **LLM API Key** 与 xlog 私钥）——两者是**兄弟目录**，只防 `lark/` 会留同级缺口。用 `canonicalPath` 比较（抗软链穿越与 `./`、`a/../b` 变体） |
| 2 | **凭据族文件名形态** | 对 raw name 与 **canonical name 双重比对**（OR），命中下列任一即拒 |

**凭据族明细**：

| 形态 | 内容 |
|------|------|
| 扩展名 | `pem` / `key` / `p12` / `pfx` / `jks` / `keystore` |
| 精确名 / **stem**（去末段扩展名） | `id_rsa`/`id_dsa`/`id_ecdsa`/`id_ed25519`、`credentials`/`secrets`/`secret`/`passwd`/`shadow`、`key`/`privkey`/`privatekey`/`private_key`/`private-key`、`service-account`/`service_account`/`aws-credentials`、`bridge-config.json` |
| **私钥名前缀续写** | `id_rsa` 族 + `.`/`_`/`-` 续写（覆盖 `id_rsa.bak`、`id_rsa_old`、`id_rsa-bak`——无扩展名的备份变体） |
| 多段兜底 | 名字含 `.id_rsa` / `.pem` / `.key`（覆盖 `report.md.id_rsa`） |
| `.env` 族 | `.env` 及 `.env.*`（前缀形式） |
| 桥接 sidecar | **精确名** `.meta.json`（与 `DownloadStore.META_FILE_NAME` 同源） |
| **显式放行** | `.pub`（公钥不是机密，SSH 惯例专指公钥）；`.meta.json` 之外如 `report.meta.json` 也放行 |

🔴 **判据 2 必须同时看 canonical 名**：工作区内的构建/测试任务（`ws.run_task`）能建软链（`ln -s ~/.ssh/id_rsa report.md`），只看 raw name 会被 `report.md` 骗过而放行私钥。

**方向是 fail-closed**：无法解析的路径、可疑命中一律拒（误杀代价 = 用户收到「目标不可交付」，而非静默泄漏）。

**有意误杀清单**（保守的代价，显式登记）：`.env.example`/`.env.template` 等模板（难与真凭据区分）、`sample.key`/`keyboard.key` 一类配置示例（被扩展名族拒）。

**与 `send_file` 规则面的其余差异**（有意保留）：`[产物]` 通道另有 `maxCount`/`maxSizeBytes`/`allowedExtensions` 三重限制；`send_file` 只有「存在 + 30MB 平台上限」。八件读类工具**刻意保持**严格允许集，不随沙箱放宽。

**守护测试**：`lark-bridge` 的 `SensitiveFilePolicyTest`（判据逐条 + 软链绕过 + stem 变体 + `.pub` 放行 + 数据根取哪一级的判别性）、`ArtifactsTest`（标记链挂表 + 管线级透传）、`:feature-larkbridge` 的 `KoogAgentRunnerTest`（工具侧三闸顺序 + 存在性不泄漏）、`LarkBridgeManagerSandboxTest`（沙箱根集合组装）。

### 🔴 下载为何直连 `Transport.send`（2026-09-12 真机缺陷修复）

网关 `SdkLarkGateway.doDownload` **不再调** SDK 的 `client.im().v1().messageResource().get(req)`，改为直接调
`com.lark.oapi.core.Transport.send(Config, RequestOptions, "GET", path, tokenTypes, req)` 拿 `RawResponse`。

**为什么**：`MessageResource.get()` 的**非 200 分支**走 `UnmarshalRespUtil.unmarshalResp`，在 JDK 16+ 必抛
`JsonIOException`（见「SDK 2.8.5 已知缺陷」D1），该异常吞掉真实 HTTP 状态码 ⇒ 2026-09-12 真机表现为
「下载失败 + 一句 Gson 异常」，看不出是权限/已删除/限流。

**直连为何可行**（已逐行核实 SDK 源码）：

| 关注点 | 事实 |
|--------|------|
| 路径拼接 | `Transport.send` 内部仍走 `ReqTranslator`，`GetMessageResourceReq` 自带 `@Path`（`message_id`/`file_key`）与 `@Query`（`type`）⇒ 模板替换与 query 拼接照旧，**不需要** `messageResource()` 那层壳 |
| token | **不绑定 `Client`**：`ReqTranslator.getToken` 用 `GlobalTokenManager`（全局静态）+ 传入的 `config`；`TokenManager.getTenantAccessToken(config, …)` 只读 `appId/appSecret/appType/cache`，全是公开 setter ⇒ `Config()` 自建可行 |
| token 缓存 | `LarkChannel` 无自定义缓存 ⇒ SDK 落 `LocalCache.getInstance()`（进程级单例）⇒ 自建 `Config` 不设 cache 也命中同一份，**不产生额外 token 请求** |
| 凭据来源 | `Client.config` 私有且无 getter（D3）⇒ 网关在 `connect()` 成功点留存 `credentialRef` |
| baseUrl | 与现状**严格一致**取 `BaseUrlEnum.FeiShu`。🔴 不要按 `cred.region` 切 `LarkSuite`——region 当前根本没接进 baseUrl（`regionHint` 只服务 `registerApp`），自作主张切换会引入一个当前不存在的行为差异 |

**行为差异**（唯一一处）：不再有 `resp.writeFile(tmp)`，改为 `Files.write(tmp, raw.body)`——少一次
`ByteArrayOutputStream.toByteArray()` 全量拷贝，内存峰值反而下降。其余防护（① 上限钳制、② 并发单飞、
③ 临时文件 + `ATOMIC_MOVE` + 失败清理、①' 下载后大小阈值）**逐字保留、一律未弱化**。

**错误可观测性**（同批修复）：非 200 时信号 msg 带 `http=<真实状态码> body=<响应体片段> key=<fileKey>`，
业务码从错误体提取后经 `LarkErrorMapper.fromLarkCode(业务码, HTTP 状态码)` 映射
（429→`RateLimited` 供 `RateGovernor` 退避、5xx→`ConnectRetryable`、403 附业务码→`ConnectFatal`）。
异常兜底分支仍保留（网络中断等），msg 补 `t::class.simpleName` 以便与 HTTP 分支区分。

**失败原因传导到用户可见层（2026-09-12 平台 100MB 限批次）**：

- `MessageGateway.downloadResource` 契约 `Boolean` → **`DownloadResult`**（`error: GatewayErrorCode?` +
  `larkCode: Int?` + `detail`，`SendResult` 同构先例）。🔴 **双轨**：信号（SendFailed/RateLimited 经
  `signals`）服务运维日志（每进程单收集器——`Channel.receiveAsFlow` 是 fan-out，第二个订阅者会偷走
  BridgeAssembler 的信号并打断 RateGovernor 退避，**这就是不能让 resolver 订阅 signals 的原因**）；
  返回值服务调用方决策。所有 `offerSignal` 原样保留。
- `LarkErrorMapper` 登记 **234037 → `DownloadTooLarge`**（与「我方超限」同码）。🔴 平台限 vs 我方限的
  判据 = `DownloadResult.larkCode != null`：我方超限发生在 HTTP 200 之后（业务码必 null）、平台限必带
  业务码。**234037 魔数只登记在 mapper 一处，bridge 不复制**。
- resolver（`ReferencedMessageResolver.downloadFailureReason`）文案表：平台限 → 「超过飞书平台 100MB
  下载上限，该文件无法下载」；我方限 → 「超大小上限」（既有文案）；限流 → 「下载被飞书限流，请稍后重试」。
  聚合句尾部建议按「全部为大小限」切换为「**压缩或拆分后重新发送**」（重试无法解决），消灭误导性的恒「请重试」。
  `errorCode` 仍锁死 `file_download_failed`（区分信息全走 summary）。
- **429 双信号**：`doDownload` 非 200 且 RateLimited 时与 `uploadFile` 对齐同发
  `GatewaySignal.RateLimited(null, larkCode, null)`（真实可达性极低——下载频控 1000/min + Semaphore(2)，
  纯防御性对称）。🔴 退避作用面是**后续出站发送**而非下载本身（下载不经 `RateGovernor.acquire`）。

**测试**：`lark-gateway/src/test/.../SdkDownloadTransportTest.kt`——注入假 `IHttpTransport`，**无凭据即可驱动
200 与非 200 两条真实分支**（此前 `FileDownloadStreamingTest` 只做源码 grep，`downloadResource` 的 SDK 调用
路径**零真实覆盖**，正是本次缺陷得以长期藏身之处）。

### 文件复用（2026-09-12 文件复用批次）

**平台能力边界（已核实官方文档）**：飞书上传无秒传/内容寻址（`im/v1/files` 无 checksum 参数、响应只给 opaque file_key）；file_key 绑定维度是（应用，key）而非（消息，key），历史 key 复用直发文档未禁止但有效期零文档。下载侧 `message-resource` 可反复调（1000 次/分钟频控）、**单文件 ≤100MB**（平台硬限，超限返回 234037）。

**下载侧复用（全量落地）**：
1. **同消息幂等修复**：每个 per-message 目录落 `.meta.json` sidecar（messageId/fileKey/fileName/sha256/bytes/preprocessStamp）；`DownloadStore.recordedDirFor(messageId)` 经 meta 重建「messageId → 目录」映射——修「目录按当天日期分桶，跨天/重启再引用必重下」缺陷。rescan 读 meta 重建（跨进程复用不失效）。
2. **跨消息内容寻址**：下载完成后流式 SHA-256（任务协程内，100MB≈0.1s）入 sha256 → 目录索引；hash 命中其他消息的目录且预处理配置指纹（stamp）匹配 ⇒ 删刚下的副本、`FilePayload.path` 指向原目录（省磁盘省预处理；**不省下载 API**——入站事件无内容指纹，能力上限如此）。stamp 不匹配 = 配置已变，走正常下载路径（产物按新配置重跑，不污染共享源目录）。
3. **预处理幂等**：meta.preprocessStamp 比对当前 autoExtract/xlogDecodeEnabled/preprocessCommand/xlogPrivateKey 配置指纹（**有效私钥**口径——App 形态含宿主回落富化后的值，回落变化同样触发重跑），匹配 ⇒ artifacts 一并复用；不匹配才重跑（修掉此前每次引用无条件重跑）。
4. **复用路径复查大小**：`maxFileSizeBytes` 是 HOT 配置，复用命中时按当前值复查（调小上限后旧大文件不再喂给 agent）。
5. **并发单飞**：resolver 侧 per-dest CompletableDeferred 共享「下载+预处理+记账」整段——两个用户并发引用同一条文件消息，后到者共享结果而非整单 Failed。
6. **配额逐出接活 + 在途保护**：`evictIfNeeded`（此前生产零调用的死代码）在 recordStored 后接进任务协程；`pin/unpin` 引用计数保护 agent 执行期的目录（agent 运行中其文件目录不被他人新下载触发的 FIFO 逐出删除；pinned 被跳过时由下一个可逐条目顶包）。

**上传侧 memo（落地但默认关）**：`UploadKeyMemo`（Bridge 稳定件，`<dataRoot>/upload-key-memo.jsonl` 持久化，载入压缩重写防无上界增长）记录 `(path,size,mtime) → file_key`。`agent.artifacts.reuseFileKey`（默认 **false**）开启后：命中 ⇒ 跳过 uploadFile 直接 send 历史 key（零上传 API 调用）；**失效回退**：FILE send 返回 ok=false ⇒ 一律作废条目 + 回退两步上传——任何 send 失败都失去对 key 的信任；同一 uuid 贯穿「命中发送 + 回退重发」防假失败双投递（SDK CreateMessageReqBody.uuid：相同 uuid 1 小时内至多成功发一条）。配置项：`reuseFileKey`（默认 false）/ `memoTtlHours`（默认 168h）/ `memoMaxEntries`（默认 512）。

**缓存键为何是 path+size+mtime 而非内容 sha256**：真机验证「同字节流重传是否同 key」前内容寻址不可靠（若实测同 key，可升级为内容寻址——改名/移动后仍命中）；path+size+mtime 是最小可信维度，同键内容漂移窗口 = mtime 粒度内（已知风险）。

**已知边界**：legacy 目录（无 .meta.json）只进账本不进索引（仅同消息可复用）；缓存目录在下载配额区内受 FIFO 逐出管辖。

### 真机验证清单（文件复用批次，`reuseFileKey` 开启 gate）

| # | 验证项 | 决定什么 |
|---|--------|---------|
| 1 | 同字节流两次上传，file_key 是否相同 | memo 缓存键可否升级为内容 sha256（若同，改名/移动后仍命中） |
| 2 | 历史 file_key 再发新消息：可行性 + 时效（24h/7d/30d 三点观测，记录实际失败码——`sendFileByKeyCreate` 已透传 larkCode） | reuseFileKey 的价值前提与 TTL 档位 |
| 3 | 消息**撤回**（非删除）后 message-resource 是否拒绝下载（230110 只提删除） | 下载侧边界 |
| 4 | 用户重发同一文件：两条**入站**消息 file_key 是否相同（#1 的入站镜像，一次真机同做） | 若同 ⇒ 下载侧跨消息可免 API 调用，ContentIndex 可简化为 fileKey→路径二级索引 |
| 5 | **>100MB 文件**（现成样本 `20260902_112801.zip` = 108.1MiB）引用 + `ai` 指令：期望下载卡出现失败阶段行、任务失败 summary 含「超过飞书平台 100MB 下载上限」与「压缩或拆分」、errorCode 仍为 `file_download_failed`、日志 SendFailed msg 含 `http=400` 与 234037 | 平台 100MB 限传导链（2026-09-12 批次）的真机闭环 |

🔴 在线状态批次的真机顺车项：断网 30s 验证 UI「重连中」→「已连接」迁移 + CLI 日志 INFO 可见；飞书发「状态」收真值回复；长断线 >30min 后发状态无补答（预期行为，StaleFilter 窗口）。

### ⑤ Observe（`onOutcome` 回调）

审计 JSONL / 发送者统计 / UI 日志台。`onOutcome` 必须 O(1) 非挂起（内部只允许 `trySend`）。审计记录 15 字段（IA 批⑤ 2026-09-27 起 `AuditEntryJson` 尾部后加 `userMessageId`——用户原消息 id，最近任务表情回显的 `listReactions` 查询锚点；后加惯例三件套：默认 null + 空串落 null 不产空键 + `AuditJson` ignoreUnknownKeys 旧行兼容；计数守护 `AuditLogTest`）。

---

## SDK 吸收清单

旧自研代码中以下能力已由官方 SDK（`oapi-sdk 2.8.5`）内建承接：

| 旧能力 | 新承载 | 备注 |
|--------|--------|------|
| pbbp2 protobuf 帧编解码 | SDK `ws/Client` 内部 | shaded protobuf（`com.lark.oapi.google.protobuf`） |
| 分片重组 | SDK `ws/Client` 内部 | Guava Cache `expireAfterWrite(5min)` |
| 端点换取（WebSocket URL） | SDK `ChannelClientFactory` | `connect()` 内部 `fetchBotIdentity` |
| 心跳与重连 | SDK `ws/Client` | 🔴 `pingInterval` **实测 90s**（三轮真机日志实测间隔恰为 90 秒：`16:57:14 → 16:58:44 → 17:00:14`。SDK 官方标称默认 120s 见 `spike-findings.md`「S3 附带发现」F 项散文与「附录 A」表格，但真机由 SDK 内部控制为 90s；`lark-gateway/Tuning.kt` 与 `:lark-bridge` **均未声明、也未接线**任何 ping/心跳间隔常量，故不存在「死的调优旋钮」——90s 是 SDK 内部行为，我方无可调点）、`reconnectInterval=120s`（SDK 标称，未真机验）、`reconnectCount=-1`（无限重连） |
| 消息归一化 | SDK `ChannelInboundProcessor` | `NormalizedMessage` 16 个 getter |
| 去重（messageId LRU） | SDK `SafetyPipeline.SeenCache` | `dedupMaxEntries=5000`、`dedupTtlMs=12h` |
| 群白名单 | SDK `PolicyGate`（**已禁用**） | 我方设 `groupAllowlist=[]`/`dmMode=open`/`requireMention=false` 全放行，自行在 ① Intake 做访问控制（保住黑名单能力） |
| @门控 | SDK `PolicyGate`（**已禁用**） | 同上，我方 `PolicyGate` 自行判定 |
| 自动分片（出站长文本） | SDK `OutboundSender` | `textChunkLimit=3500` |
| 流式卡片（CardKit） | **我方自建** `LarkCardStreamSink` | SDK 的 `stream()` 是回调驱动，与推驱动契约不兼容；我方 `new OutboundSender(rawClient, options)` 自行驱动 `createCardInstance` → `updateCardElementContent` → `finishStreamingCard` |
| 重试 | **SDK 盲退避已关闭**（`retryMaxAttempts=1`） | SDK 的 `OutboundRetry` 用 `Thread.sleep` 占 commonPool；我方 `RateGovernor` 自行退避 |
| SSRF 防护 | SDK `OutboundSender` | `ssrfGuardEnabled=true` 保持开启 |
| 表情反馈 | SDK `LarkChannel.addReaction/removeReactionByEmoji` | 直接转发 |
| 扫码建应用 | SDK `RegisterApp`（**延后**） | UI 给禁用态按钮；方案裁定「可后置，先返回 Unsupported」 |

> 🔴 **三套 ping 数字的关系（避免混淆）**：**30s** = 旧自研 WebSocket 协议栈的 `pingIntervalSeconds`（`behavior-contract.md` BC-444，已标「SDK 吸收/直接删除」，**属已废弃的旧架构值，不可断言**）；**120s** = 飞书 SDK 官方文档标称的 `pingInterval` 默认值（`spike-findings.md`「S3 附带发现」F 项散文记录 + 同项真机实测更正脚注、「附录 A」表格记录 + 同表更正脚注，均已标注「标称 vs 真机实测」）；**90s** = **真机实测的实际间隔**（三轮 T11 日志一致：`16:57:14 → 16:58:44 → 17:00:14`）。新架构下**只有 90s 是当前事实**，且我方无可调点（`Tuning.kt` 未声明也未接线任何 ping 常量）。

> ⚠️ **数字巧合防呆**：`runtime.agentIdleTimeoutSeconds` 的默认值也是 **120**（秒，`RuntimeConfig.agentIdleTimeoutSeconds`；NF-08/D10 的 agent 空闲看门狗，属**我方配置字段**），与上表「心跳与重连」行里 SDK 标称的 `pingInterval=120s` 数字相同但**语义完全无关**：前者是我方可调的 agent 空闲超时、`<=0` 表示禁用看门狗（`TimeoutPolicy.kt` 的 idleMillis KDoc）；后者是 SDK 内部心跳、我方无可调点（真机实测 90s）。🔴 任何「统一改 120s」的批量操作都必须先区分二者，避免误伤看门狗常量。

---

## SDK 边界实测结论（M1 Spike）

素材来源：`openspec/changes/archive/2026-09-10-rewrite-lark-bridge/spike-findings.md`

### SDK 版本

`com.larksuite.oapi:oapi-sdk:2.8.5`（Maven Central 当前最新版）

### 🔴 SDK 2.8.5 已知缺陷（升级 SDK 时逐条复核）

| # | 缺陷 | 影响 | 我方处置 |
|---|------|------|---------|
| D1 | **下载响应的非 200 分支在 JDK 16+ 必抛 `JsonIOException`**：`UnmarshalRespUtil.unmarshalResp` = `Jsons.DEFAULT.fromJson`，而 `GetMessageResourceResp extends BaseResponse<ByteArrayOutputStream>` ⇒ Gson 建适配器树时对 `ByteArrayOutputStream#buf` 调 `setAccessible(true)`，强封装下抛 `InaccessibleObjectException` 并被包成 `JsonIOException: Failed making field 'java.io.ByteArrayOutputStream#buf' accessible`。**该异常吞掉真实 HTTP 状态码与服务端业务码**（真机 2026-09-12：403/99991672 表现为一句 Gson 异常）。JDK 11 时期强封装默认关闭所以没暴露 | 下载域（全 SDK 20 个 `BaseResponse<ByteArrayOutputStream>` 响应类**全是下载语义**，本项目只用到 `im` 域 3 个，仅 `GetMessageResourceResp` 在用） | 改直连 `Transport.send` 拿 `RawResponse`，**完全绕开 `unmarshalResp`**；错误经 `LarkErrorMapper.fromLarkCode(业务码, HTTP 状态码)` 分层映射。见 `SdkLarkGateway.doDownload` KDoc 与 `SdkDownloadTransportTest`。**不加 `--add-opens`**（只管 App/安装包，CLI 与测试进程不受保护） |
| D2 | **`ReqTranslator` 字段笔误**：读 `requestOptions.isSupportUpload()` 却赋给 `RawRequest.supportDownLoad`。SDK 的 `MessageResource` 只设 download 不设 upload ⇒ 原生路径下 `supportDownLoad` 恒 `false` | 仅影响 `OkHttpTransport` 走哪条读取分支（`IOs.readAll(byteStream())` vs `body().bytes()`）——**两条分支结果等价、内存上界相同，均非流式**，故删掉规避代码**没有任何可见症状** | `doDownload` 里 `setSupportDownLoad(true)` 与 `setSupportUpload(true)` **必须同时设**；由 `SdkDownloadTransportTest` 断言 `RawRequest.supportDownLoad == true` 钉死（否则这行会被当冗余清理掉） |
| D3 | `Client.config` 私有且**无 getter** | 自建 `Config` 做直连时拿不到凭据 | 网关在 `connect()` 成功点留存凭据（`credentialRef: AtomicReference<LarkCredential?>`，非 `@Volatile var`），`close()` 清空；留存点须在 `channel = ch` 之后（过早留存 ⇒ 连接失败时下载仍会带凭据发请求） |
| D4 | **平台下载大小限**（非 SDK 缺陷，服务端硬限）：message-resource 仅支持 ≤100MB 文件，超限 `http=400` + 业务码 234037「Downloaded file size exceeds limit.」（SDK `MessageResource` javadoc 载明；2026-09-12 真机实证 108.1MiB zip） | 客户端不可绕过；入站事件无 size ⇒ 下载前不可预知 | `downloadMaxBytes`/`maxFileSizeBytes` 默认同值对齐；234037 → `DownloadTooLarge`（mapper 唯一登记点）；失败经 `DownloadResult` 传导用户文案「压缩或拆分后重新发送」。SDK 升级时随 D1 一起复核上限是否变化 |
| D5 | **`ws.Client` 线程池永不 shutdown**（反射 shutdown 的根因）：`ws/Client.java` 的 `Executors.newFixedThreadPool(2*CPU+1)`（executor 字段初始化处）非 daemon、全类无 shutdown；`close()` 只 `disconnect()` | 每次 connect/close 周期泄漏 `2*CPU+1` 个非 daemon 线程，JVM 无法自然退出；SDK 升级改字段名则反射静默失效 | **已根治（下线批 2026-09-12）**：入站恒走 `:lark-ws`（零自建线程池，`close()` = `scope.cancel` 一次全回收，反射 shutdown 路径已整体删除）；SDK 侧恒 webhook 模式 ⇒ `ws.Client` 压根不构造。残留 OkHttp Dispatcher 线程（出站仍走 SDK Client）由宿主 `exitProcess` 兜底——契约不变 |

### 自研长连接客户端（`:lark-ws`，2026-09-12 骨架+接线+下线三批完成——**入站恒自研，SDK websocket 路径已删除**）

> 参照 SDK 源码（v2.8.5 tag `e92a32cf`，与生产依赖一致）用 Kotlin 重实现 `com.lark.oapi.ws.Client` 的传输层核心，
> 根治 D5。下线批（2026-09-12）一步到位：**入站事件流恒走 `:lark-ws`**——`connection.selfBuiltWs` 开关已删除
> （配置迁移 v4→v5 退役保底进 `experimental.retiredV4`），`SdkLarkGateway.wsInboundFactory` 改为**必传构造参数**
> （生产 `WsClientTransportFactory`，编译期不变量），SDK 侧 LarkChannel 恒定 `transport("webhook")`
> （🔴 硬约束：不切则同 appId 双长连接被服务端互踢——D19 形态；webhook 模式下 `rawWsClient==null`，
> LarkChannel 退化为纯出站/identity facade）；出站（send/上传/下载/补查/流式卡）与 identity 仍走 LarkChannel。
>
> **随下线批整体删除**：`registerHandlers` + `capture`×2 + `captureNormalizedMessage`（SDK 入站转换链）、
> `reflectivelyShutdownWsExecutor` + `wsExecutorReleased`（反射 shutdown 路径——webhook 恒定后 rawWsClient
> 恒 null，无池可关；close 收口 = `WsClient.close` 的 `scope.cancel` 一次全回收，零反射）、
> SDK 入站相关 import（ChannelEventHandler/NormalizedMessage/CardActionEvent/RejectEvent）。
> `credentialRef` **保留**：下载直连（`Transport.send` 自建 Config）需要凭据而非 token，这是真机验证过的链路。

**接线批新增文件（`lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/`）**：
`WsInboundTranslator.kt`（SDK normalize 复刻：mention 提取/替换 + content 转换 + cardAction 闸门——
纯函数语义，对照 `MessageNormalizer`/`Mentions`/`NormalizeTexts`/各 Converter/`CardActionNormalizer` 逐行号）；
`WsInboundTransport.kt`（注入缝接口：`WsInboundTransportFactory` 工厂语义——WsClient.close 终态 ⇒ 每次
connect 新建；`WsStateListener`；`WsInboundTransport`）；`WsClientTransportFactory.kt`（生产实装：
WsClient 适配器 + JDK bootstrapHttp + 状态观察协程）。测试：`WsInboundTranslatorTest`（30 例——
转换模板/mention 语义/闸门/fallback 逐字节对齐 SDK，含 @_user_1 前缀缺陷照抄）+ `WsInboundGatewayTest`
（6 例——恒 webhook/必传无分叉/SDK 入站删除/close 收口源码守护）+ `Handler3SecondTest` 改写
（3 秒铁律锚点迁至 `handleWsInboundEvent`）。

| 维度 | SDK `ws.Client` | `:lark-ws` |
|------|----------------|-----------|
| 线程模型 | 自建 `FixedThreadPool(2*CPU+1)` 非 daemon 永不 shutdown（D5 泄漏源） | 零自建线程池：全协程 + 注入 dispatcher；`close()` = `scope.cancel()` 一次全回收 |
| 事件处理 | handler 跑 SDK 池线程，无背压无取消无生命周期；handler 抛错只回 500 事件丢失 | `WsDispatcher` Channel 背压（满载计数 + WARN 不静默）；挂起回调签名；SupervisorJob 单帧失败不掀翻 |
| 合包丢弃 | Guava Cache `expireAfterWrite(5min)` 过期静默丢弃 | 超时清扫协程，丢弃必带 WARN 含 messageId 与分片进度 |
| 网络栈 | shaded okhttp | JDK 标准库 `java.net.http.HttpClient/WebSocket`（jvmTarget 11 原生；零第三方网络库） |
| 帧协议 | shaded protobuf 生成代码 | 手写 wire-format 编解码（pbbp2 仅 2 message / 9 字段；未知字段天然跳过）——round-trip 测试锁死 |
| 依赖 | SDK 传递 Gson/protobuf/okhttp/Guava | 仅 kotlinx-coroutines + kotlinx-serialization；`LarkSdkIsolationTest` 黑名单全不沾 |

协议事实（源码考古，照此实现）：bootstrap `POST /callback/ws/endpoint`（PascalCase body/响应）；pbbp2 protobuf 帧（`method` CONTROL=0/DATA=1，header `type`=event/card/ping/pong）；握手失败 header `handshake-status`/`handshake-msg`（514/403/1000040350 分类）；`pong` 带非空 payload 时热更新 `ClientConfig`（重连参数 + ping 间隔——ping/reconnect 循环**逐轮读配置**，源码守护断言钉死）；`sum>1` 按 `(message_id,seq,sum)` 合包；回执帧 = 原帧 rebuild + `biz_rt` header + Base64 ack JSON。

回执三态（🔴 骨架批曾把「handler 正常返回 null」误判 500——会触发服务端重发风暴）：**异常→500、正常完成→200、返回 null 只是「无回执载荷」（data 字段缺席，code 仍 200）**；队列满回执 503（服务端重发语义）。

模块文件：`lark-ws/src/main/kotlin/com/jiaoay/lark/ws/`——`WsClient.kt`（连接/心跳/合包/重连/回执）、`WsFrame.kt`（编解码）、`WsModels.kt`（bootstrap 模型）、`WsEventParser.kt`（事件瘦身解析，V1/V2 双形态）、`WsTokenProvider.kt`（tenant token 缓存，expire-300s 前置）、`WsDispatcher.kt`（背压分发）、`JdkWsConnection.kt`（JDK WebSocket 生产实现 + 分片拼装）。测试 `FakeWsConnection` 注入驱动，零网络依赖（47 例）。

### S3 四问结论

| # | 问题 | 结论 |
|---|------|------|
| Q1 | `connect()`/`send()`/卡片流式的返回类型与线程模型 | 全部 `CompletableFuture`，默认跑在 `ForkJoinPool.commonPool()`；handler 在 shaded-okhttp WS reader 线程上被**同步调用**，返回后 SDK 才回 ACK。⇒ SDK 回调线程内只允许 `runCatching { trySend }`（3 秒铁律的结构性保证） |
| Q2 | `SafetyConfig` 去重键 | `messageId`（`NormalizeDedupKeys.message()` = `"message:" + messageId`；`SafetyPipeline.eventId(msg)` 名为 eventId 实返 `msg.getMessageId()`） |
| Q3 | 卡片流式是否内部维护 sequence | 是，SDK `MarkdownStreamControllerImpl` 有实例字段 `private int sequence`，每流一个实例 ⇒ 天然单调递增 |
| Q4 | `RetryConfig` 是否感知 `x-ogw-ratelimit-reset` | **完全不感知**（全树 grep 零命中）。重试是 `sleep(baseDelayMs * 3^(attempt-1))` 盲退避 + `Thread.sleep` 占 commonPool ⇒ 我方设 `maxAttempts=1` 关掉它，限流退避全部由 `RateGovernor` 自行承担 |

### S2 jlink 模块清单（9 个）

```
java.base / java.datatransfer / java.desktop / java.logging / java.net.http
java.prefs / java.xml / jdk.crypto.ec / jdk.unsupported
```

结论：**无需新增 `modules(...)`，无需 `includeAllModules = true`**。`httpclient → java.naming + java.security.jgss` 缺口是**死代码路径**（`ApacheHttpClientTransport` 无实例化点，SDK 默认走 shaded OkHttp）。

### S5 slf4j 解析

`slf4j-api` 最终解析版本 = **2.0.17**（logback-classic 1.5.18 带入，SDK 声明的 1.7.30 被 Gradle 抬升）。无版本冲突、无 `NoSuchMethodError`、无 NOP 退化。Gson 2.9.0 与 kotlinx-serialization-json 1.9.0 共存无干扰。

---

## 性能参数表

源码：`lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/Tuning.kt`（150 行）

### 公开面（`GatewayLimits`，`:lark-bridge` 的 `RateGovernor` 读此处）

| 常量 | 值 | 飞书硬限制 | 留余量理由 |
|------|------|------|------|
| `perChatRps` | 4 | 同群 5 QPS | 留 20% 余量 |
| `globalRps` | 40 | 接口级 50/s | 留 20% 余量 |
| `textChunkLimit` | 3500 | 单条消息文本上限 | SDK `OutboundConfig` 同值 |
| `cardByteBudget` | 24576 | 单卡 30KB（30720B） | 留 6144B（6KB）给 JSON 转义膨胀 |

### 内部调优（`internal object Tuning`，36 个常量；下表只列关键项）

| 域 | 常量 | 值 | 硬限制/理由 |
|------|------|------|------|
| 卡片流式 | `streamThrottleMs` | 250ms | 单卡更新 10 次/秒；250ms = 4 次/秒，留 60% 余量（SDK 默认 100 零余量，必须覆写） |
| 卡片流式 | `streamThrottleChars` | 4000 | SDK 默认 50（Throttle.note() 是「或」关系，只调时间无效） |
| 卡片流式 | `streamSegmentDeadlineMs` | 540,000ms (9min) | 🔴 **已退役**（2026-09-12 单卡收敛）：主动轮换分支已删，常量保留仅供历史参照与 11min 真机实验复现 |
| 卡片流式 | `sinkPollIntervalMs` | 25ms | sink 协程 delay 粒度（每秒最多 40 次唤醒） |
| 卡片流式 | `sinkCloseTimeoutMs` | 3,000ms | flushTerminal + close 总预算 |
| 频控 | `rateLimitBackoffMs` | 1,000ms | reset 头缺失时兜底退避基数（指数 1→2→4s） |
| 频控 | `rateLimitBackoffMaxMs` | 30,000ms | 退避上限 |
| 出站 | `retryMaxAttempts` | 1 | 🔴 关掉 SDK 盲指数退避（默认 3，用 Thread.sleep 占 commonPool） |
| 出站 | `ssrfGuardEnabled` | true | 不关（SSRF 防护是唯一屏障） |
| 出站 | `chatQueueEnabled` | false | 🔴 必须显式覆写（SDK 默认 true 会合并消息破坏管线语义） |
| 连接 | `wsHandshakeTimeoutMs` | 15,000ms | SDK 内部硬编码 `awaitWebSocketReady(15000)` |
| 连接 | `connectTimeoutMs` | 20,000ms | connect() 整体预算（> ws 握手 + 5s 余量） |
| 连接 | `disconnectTimeoutMs` | 5,000ms | 优雅关闭第 ⑤ 步 |
| 连接 | `closeTimeoutMs` | 5,000ms | 优雅关闭第 ⑥ 步 |
| 连接 | `inboundChannelCapacity` | 512 | SDK handler → intake Channel 容量（溢出 ⇒ Dropped） |
| 下载 | `messageResourcePath` | `/open-apis/im/v1/messages/:message_id/resources/:file_key` | 直连 `Transport.send` 用；`:message_id`/`:file_key` 由 `ReqTranslator` 依 `GetMessageResourceReq` 的 `@Path` 替换 |
| 下载 | `httpOk` / `bodySnippetMax` | 200 / 512B | 成功判定；非 200 错误体进信号时的截断上限 |
| 下载 | `testAccessToken` | `t-test-only-token` | 🔴 **仅测试缝**：注入假 `IHttpTransport` 时绕开 token 往返（生产恒不使用） |

---

## 硬限制应对表

| 飞书侧硬限制 | 我方应对 | 余量 |
|------|------|------|
| 单卡更新 10 次/秒 | `streamThrottleMs = 250ms`（4 次/秒） | 60% |
| 单卡 30KB | `cardByteBudget = 24576` 字节 | 6KB 给转义膨胀 |
| 流式卡片 10 分钟自动关闭（🔴 BC-1035 从未实测的假设） | 主动轮换已退役（2026-09-12 单卡收敛）；若假设为真由 200750/未登记码**被动重建**自动兜住（会多一张卡但进度不断）；假设真伪由 T11 表 C 档第 13 项真机裁决 | — |
| 同群 5 QPS | `perChatRps = 4` | 20% |
| 接口级 50/s | `globalRps = 40` | 20% |
| 🔴 消息资源下载单文件 ≤100MB（服务端硬限，SDK javadoc 实锤；真机 108.1MiB ⇒ http=400 + 234037） | `downloadMaxBytes`/`maxFileSizeBytes` 默认同值 100MB 对齐；234037 → `DownloadTooLarge` → 用户文案「超过飞书平台 100MB 下载上限…请压缩或拆分后重新发送」（重试无效不再误导） | —（同值，无余量可言） |
| handler 必须 3 秒内返回且不抛异常 | SDK 回调线程内只允许 `runCatching { inboundChannel.trySend }`，一切处理在 intake 单协程 | 结构性保证 |
| SDK `chatQueueEnabled=true` 会合并消息 | 显式设 `false`（`Tuning.chatQueueEnabled`） | — |
| SDK `RetryConfig` 盲退避占 commonPool | `retryMaxAttempts=1` 关闭 + 我方 `RateGovernor` 自行退避 | — |

### S6 真机实测发现的四条坑

1. **`msgType` 必须 `"interactive"`**：用 `"card"` 会返回错误码 `230001`（格式错误）
2. **`partial_element` 不能含 `tag` 字段**：否则返回 `300312`
3. **首版不启用 `streaming_config`**：`step`/`print_step` 参数会触发 `11311`/`10002`
4. **分段预算须按累计总量跨 N×预算**：单段 24576 字节用尽后轮换新卡，下一段预算重新计算（非总量除以段数）

---

## 全功能平移对照表

> 完整 77 项旧 UI 能力盘点 + 三张对照表 + 13 项必须手写清单见 `build/m6-preflight.md`「第 5 项｜旧 feature-larkbridge UI 盘点与「全功能平移」清单」章；35 行配置映射表见 `build/t15-migration-preflight.md` 第 3 项。

### 已知减损与延后项

| 项目 | 处置 | 理由 |
|------|------|------|
| `registerApp` 扫码建应用 | **延后**（UI 给禁用态按钮 + 说明文案） | 方案裁定「可后置，先返回 Unsupported」；它是新增能力（非平移），延后不违反「现有能力一个不少」 |
| CLI 执行后端相关 UI（模板/权限档/健康度 banner/pill） | **整体删除** | 委托链退役后无外部后端可配可探；配置域 11 个 CLI 专用键随 v10→v11 迁移退役 |
| 旧 sticky「未保存更改」条 + 「保存工作区域」按钮 | **语义消失** | 保留 auto-debounce 自动落盘 ⇒ 无「未保存」状态；改用瞬时「已保存」提示 |
| BC-288 第 4 位 `host-custom` 宿主自定义执行器 | **有意的架构变更** | 用户级自定义由 `config.routes` 数据承载；能力级扩展由「新增一类能力改 4 处 3 文件」机制承载（sealed + discriminator ⇒ 漏写即红构建） |

---

## 两条编码链分栏

### UI → 配置文档（写入链）

```
用户键击
  → ViewModel.updateField(field, value)
    → field.set(_draft.value, value)       // 纯函数，返回新 BridgeConfig
      → _draft.value = next                // StateFlow 即时更新（UI 零延迟回显）
        → 400ms trailing debounce          // 高频键击合并
          → drop(1)                        // 首次打开页面不落盘
            → writeMutex.withLock          // 单写者串行化
              → ConfigValidator.validate   // 校验失败即中止（不覆写文件）
                → store.applyConfig(cfg, persist=true)
                  → ConfigRepository.save  // .tmp + rename 原子写
                    → bridge-config.json   // 最终落盘
```

### 飞书事件 → 出站（处理链）

```
SDK WS reader 线程（shaded OkHttp）
  → handler 同步回调（3 秒铁律：ACK 在 handler 返回后才发）
    → runCatching { inboundChannel.trySend(msg) }   // O(1) 非阻塞，溢出 DROP_OLDEST
      → ① EventIntake（单协程、Dispatchers.Default、零锁）
        → StaleFilter → MessageDeduper → AccessControl → PolicyGate → PipelineRouter
          → ② Admission.submit（非挂起 O(1)）
            → URGENT: cancelAll + launch
            → LIGHT: launch
            → HEAVY: ChatLane.tryAcquire → PermitGate.acquire(排队超时) → launch
              → ③ invokeHandler: withTimeout(总超时) + 空闲看门狗
                → handler.handle(ctx)              // 纯挂起，agent 执行（LLM 调用 / 工具链）在此
                  → ④ deliver: ReplySink → RateGovernor → gateway
                    → SDK OutboundSender（netDispatcher）→ 飞书 API
                      → ⑤ onOutcome: trySend → 审计/统计/日志台
```

🔴 **两条链的隔离**：写入链全在 ViewModel（Compose UI 线程 + viewModelScope）；处理链全在 Bridge 运行时（独立 CoroutineScope + SupervisorJob）。唯一交汇点是 `store.applyConfig` 触发 `PipelineSnapshot` 原子替换（`AtomicReference.set`，HOT 字段无锁热替换）。

---

## T11 真机验证 runbook

> 本节由旧「待真机确认（T11）」小节**升级改写**而来：T11（端到端真机验证闸门）的可执行 runbook，供用户独立挑时间跑完剩余验证。素材见 `behavior-contract.md` §11.5「遗留给 T11 真机闸门的待验项」与 `gateway-contract.md §16.8`；下文所有源码行为均按 `lark-bridge/…` 现网源码逐行核实。
> 🔴 **进度基线**：三轮「安全子集」已于 2026-09-08 在**生产代码路径**上真机跑过（累计 50+ 分钟真实长连接），基础设施层面全部通过；但三轮观测窗口内用户均未发消息 ⇒ **消息级取证始终为零**，剩余项交由用户按本 runbook 执行。

### 三档划分（🔴 避免后来者重复劳动）

#### A 档 · 已真机通过（生产代码路径，三轮安全子集，共 11 项）

| # | 项 | 真机证据（`build/t11*.log`） |
|---|----|------|
| 1 | 长连接建立（启动 → Connected ≈ 1.4–1.8s） | `t11-serve.log`：`16:57:10` 启动 → `16:57:12` `connected to wss://…` |
| 2 | `connectionState = Connected(botOpenId, botName)` | 三轮均打印 `桥接已启动 … bot=Connected(botOpenId=ou_****, botName=…)` |
| 3 | 单实例锁获取 | `已取得单实例锁：…/locks/<16位十六进制>.lock` |
| 4 | `BlockedByPeerInstance` + 退出码 3 + D19/R16 提示（🔴 跨 JVM `FileLock` 互斥**首次真机验证**） | `t11-second-instance.log`：第二实例 `启动被拒 [BlockedByPeerInstance]` + `EXIT_CODE=3` + D19/R16 提示文案 |
| 5 | 锁文件名形态 `<sha256(appId).take(16)>.lock`（16 字符十六进制） | `t11-lock-verify.log` 锁路径 |
| 6 | SIGTERM → 优雅关闭（disconnect ~11ms → 释放锁 → 停止） | 三轮关闭序列（`disconnected` → `已释放单实例锁` → `桥接已停止`） |
| 7 | 锁释放（OS 回收 POSIX record lock，无陈旧锁残留） | 关闭后 `已释放单实例锁`；二次启动可重新取到锁 |
| 8 | 线程构成与非 daemon 残留由 `exitProcess` 兜住 | `t11-jstack-running.log` / `t11b-jstack-loaded.log` / `t11c-jstack-idle-*.log` |
| 9 | WS ping/pong 90s 周期连续 17+ 轮稳定 | `t11b/t11c-serve.log`：pong `pool-1-thread-2` → `thread-19` 连续，每 90s 一轮 |
| 10 | `--set` ephemeral 三轮均不落盘 | 跑后 `bridge-config.json` size/mtime 未变 |
| 11 | SDK 事件推送通道畅通 + 未注册事件类型正确降级 | `t11b-serve.log`：收到 9 个真实事件（`bot_p2p_chat_entered_v1`×2 + `message_read_v1`×7），全部 WARN `not found handler` 丢弃、`processed=0` 未进 intake、不崩 |

🔴 **证据边界**：此前 M1 的 S4/S6 用的是**已删除的 spike 脚手架**，其证据**不能替代**生产代码路径的验证；上面 11 项是**第一次在生产代码路径上**取得的真机证据。

#### B 档 · 待用户配合（runbook 阶段一：安全子集，消息级 13 项）

`rawContentType` 真实取值 · `chatType`/`chatId` · `senderName` 解析（scope `contact:contact.base:readonly`）· 群内 @不带正文时 `content` 空串 · 群内不@ → `policyRejected` · 五阶段计数器增量 · `ping`→`pong 🏓` · 🔴 `/help`→「测试」（**最高价值**）· 未命中兜底静默 · jsonl 真实增量与只增不改 · 有负载线程构成 · 有在途状态的优雅关闭。

> 🔴 **原第 6 项「SDK 消息回调耗时」已从「用户必须完成的取证项」降级为「已由离线测量覆盖，真机不需单独打点」**，三条证据：
> 1. **离线基准已度量整条回调路径**：`Handler3SecondTest`（`:lark-gateway`，5 tests）用**真实 SDK `NormalizedMessage` 样本**（16 getter + 2 `ResourceDescriptor` + 1 `MentionInfo`，触发 capture 内两处 `toList` 浅拷贝）度量**含 `capture()` 段的整条 SDK 回调路径**（`captureNormalizedMessage` + `LarkInboundMapper.fromMessage`），2000 次 JIT 预热 + 10000 次取样，断言 **avg < 250µs**（`Handler3SecondTest`；另有 <1ms 预算断言同文件），远低于飞书「handler 必须 3 秒内返回且不抛异常」的铁律。
> 2. **结构性保证**：SDK 回调线程内只允许 `runCatching { inboundChannel.trySend(msg) }`（非阻塞、非挂起、O(1)、不抛异常）；`Handler3SecondTest` ②③⑤ 用 grep 断言回调函数体**零** `synchronized`/`Mutex`/`suspend`/`launch`/日志/IO/网络/JSON 序列化。
> 3. **三轮真机间接佐证**：第二轮 9 个非消息事件的回调「到达 → WARN 日志」耗时 **≤3ms**，且回调线程确认为 `pool-1-thread-N`。
>
> **为什么不值得为此临时加日志**：源码无现成的「回调耗时」emit 点；`audit.jsonl` 的 `elapsedMillis` 语义是**任务耗时**（agent/命令执行）而非 SDK 回调耗时，二者不可混用。**轻量兜底**：若日志里出现回调相关 WARN/超时再深挖即可，**不作为用户必须完成的取证项**。

#### C 档 · 待用户配合（runbook 阶段二：完整 T11 独有项）

agent 全链路 · 流式卡片 CardKit（D1 非消息编辑）· 🔴 卡片流泄漏 · 确认卡点击允许/拒绝 · 停止指令（URGENT + `cancelAll`）· 多轮会话（`session.keyByThread`）· 引用流文件分析（发文件收 ack → 引用回复+指令 → 下载/预处理/拼接 prompt；引用旧气泡或重启后未命中会经 `im.v1.message.get` 补查救回）· 表情回应 · 断线重连 · 统计页环形图与入场动画目视 · `.deb` 打包产物真连 · 🔴 **单卡收敛（2026-09-12 补回 gateway-contract §13 第 6 条丢失的验证项——BC-1035 从未实测，一直没人跑）** · 🔴 **结论引用锚定（2026-09-12）** · 🔴 **产物回传 5 份全量 + 被拒审计（2026-09-12）**。

---

### ① 前置条件（开工前逐条确认）

1. **事件订阅**：飞书后台 `im.message.receive_v1` = 「使用长连接接收事件」。
2. **回调订阅**：`card.action.trigger` = 「使用长连接接收**回调**」（🔴 新版；旧版「消息卡片回传交互」长连接**不支持**确认卡点击）。
3. 🔴 **顺序陷阱（对本用户已不需要做）**：一般规则是「后台改订阅方式并点保存的那一刻，本地必须已有一条 SDK 长连接处于『已连接』状态，否则保存失败（鸡生蛋）」。**但三轮实测已证明本应用的后台订阅配置正确**（第二轮收到 9 个真实事件即为铁证）⇒ **本用户无需再动后台**。**仅当你换 appId / 换应用时**才需要重走这个顺序（先在 App 里连上、保持在线，再改后台保存）。
4. **scope 最小集**：`im:message.p2p_msg:readonly`（收私聊）、`im:message.group_at_msg:readonly`（收群里 @ 的消息）、`im:message:send_as_bot`（发消息）、`im:message.reactions:write_only`（表情回应 react_emoji，2026-09-27 批 D 补登记——此前漏登记，未授予时表情操作降级失败，不影响其它）、`im:resource`（收文件/图片）、`contact:contact.base:readonly`（显示发送者名字；🔴 2026-09-11 真机实证旧名 contact:user.base:readonly 已更名，报错 msg 会给出完整候选名单，任一 readonly 变体即可）、`cardkit:card:write`（流式卡片）、`im:message:readonly`（引用补查 `im.v1.message.get`——未开通时该功能降级，不影响其它）。L2 跨会话能力的**可选** scope（默认关、按 grants 申请）见上文「7 项后台核对清单」第 2 项。
   - 🔴 **`contact:contact.base:readonly`（旧名 contact:user.base:readonly，已更名）三轮实测时尚未确认是否已授予**。**怎么判断它生效**：跑完阶段一后看 `sender_stats.jsonl` 新增行是否带**非空 `userName`**——历史 10 行**根本没有 `userName` 键（为空）**（旧实现从未成功解析过用户名），所以「新行 `userName` 非空」是该 scope 生效的**最直接证据**。若仍为空 ⇒ scope 未授予或解析降级（**不阻塞其它验证**，`senderName` 走只读缓存降级为空串，见 `Admission.submitRejection`）。
   - `cardkit:card:write`：S6 实测已成功建卡 + patch ⇒ **已授予**（阶段二流式卡片需要）。
5. **配置文件**：`~/.toolbelt/lark/bridge-config.json` 已存在（T15 迁移产物，权限 600）。纯 CLI `serve` 直接读它。

---

### ② 阶段一：安全子集（零 agent 执行、零打扰他人）

**可直接复制粘贴的完整命令**（把 `<你的 openId>` 换成下一步查到的值）：

```bash
java -jar lark-bridge/build/libs/lark-bridge-all.jar serve --verbose \
  --set "access.trustedUsers=<你的 openId>"
```

> 🔴 `serve` 是无应答引擎形态（恒注入 `NoAgentRunner`）：agent 后端预检已随委托链退役，agent 类消息显式报 `agent_backend_unavailable`。离线联调用 `--dry-run`（SyntheticGateway 不出网）。旧配方 `--set "agent.enabled=false"` / `--set "runtime.unhandledReply="` / `--set "agent.backend=…"`（以及更早的 `access.rejectionReply=`）对应的配置键均已退役，不要再使用。

> jar 由 `./gradlew :lark-bridge:shadowJar` 产出（🔴 本 runbook 是纯文档，不代跑；三轮验证用的就是它）。**前台**运行便于 Ctrl+C；若**后台**运行见下面「关闭方式」的坑。

> 🔴 **`--verbose` 日志的隐私警告（分享前必读）**：`--verbose` 会打开两套 DEBUG 输出，其中都含**不可外泄的标识**——
> - **SDK 自己的 DEBUG**（`com.lark.oapi.*`）：`com.lark.oapi.ws.Listener` 打印的 wss URL 含 **`access_key` / `ticket` / `device_id` / `conn_id`** 等**会话凭据**；`com.lark.oapi.core.cache.LocalCache` 会打印**完整 appId**（真机日志已实证：`put key:tenant_token:app_secret:cli_…`）。
> - **桥接侧自己的 DEBUG**（本次 T11 修复新增可见）：`--verbose` 现在会把桥接侧 console 下限也降到 DEBUG，放出既有的**准入/拒绝判定**日志（`sender 准入拒绝 …`、`策略门控拒绝 …`）。这些文本里的 `chatId` / `senderOpenId` **已做掩码**（前 6 + `****` + 后 4），但同样建议按需截取。
>
> ⇒ **不要把 `--verbose` 的原始日志贴到公开渠道（issue、群聊、截图）**；如需分享，**先掩码** SDK 那批会话凭据与完整 appId 字段。下文「怎么判断跑成功了」的样板日志已把 appId/openId/botOpenId/连接参数一律掩码，可直接作对照。

**五层防护各自挡什么**（源码已核实）：

| 层 | `--set` | 挡住什么 | 源码依据 |
|----|---------|---------|---------|
| 1 | `access.trustedUsers=<你>` | 名单内用户的高风险操作免域授权；名单外用户遇越界/破坏性操作**直接拒绝**（不发卡）。入站准入本身**恒放行**——服务面收口靠飞书后台的机器人可用范围 | `TrustPolicy.decide` |
| 2 | （默认行为，无需 `--set`） | 非信任用户的越界/破坏性操作被**硬拒绝**（`TrustPolicy` 判决 DENY，不发确认卡）；拒绝事实经审计可见 | `TrustPolicy.decide` + `Admission.kt`（静默路径仍进 Observe） |
| 3 | CLI `serve` 无应答引擎 | `serve` 恒注入 `NoAgentRunner` ⇒ **agent 类消息不可能被执行**（显式报 `agent_backend_unavailable`），阶段一零副作用；启动前预检不再校验 agent 后端（该检查随委托链退役） | `BridgeAssembler.assemble(agentRunnerOverride = null)` → `NoAgentRunner`；`ServeCommand` 装载注释 |
| 4 | （默认行为，无需 `--set`） | 不匹配任何指令的**文字消息完全静默**（CLI 形态无调度层，TEXT 未命中恒静默留痕，`unmatched` 计数增加）；**未支持类型**（发图/贴纸等 9 种 kind）回明确降级文案（见「未支持消息类型处置矩阵」） | `Admission.runUnhandled` + `UnsupportedKindNotice.kt`；TEXT 静默 summary 仍为 `unmatched_silent` |
| 5 | 全部 `--set` = **ephemeral 永不落盘** | 只在本进程内存生效，**任何情况都不写 `bridge-config.json`** ⇒ 跑完配置逐字未变 | `ServeCommand.run`（`applyConfig(config, persist = false)`）；空串 coerce 见 `CliSupport.coerceValue`（String 字段 `--set x=` 经 `is String -> raw` 原样写入空串） |

🔴 **openId 自助获取**（自己查，别把真实值写进任何提交物）：

```bash
python3 -c "import json;print(sorted({json.loads(l)['senderOpenId'] for l in open('$HOME/.toolbelt/lark/sender_stats.jsonl') if l.strip()}))"
```

> 该命令列出历史出现过的全部 `senderOpenId`（当前 `sender_stats.jsonl` 10 行、去重后 **1 个 = 你自己**）。若为空，先在飞书给机器人发一条消息（此时 allowlist 尚未生效拦不住），再重跑本命令取值。

🔴 **关闭方式**：`kill -TERM <pid>`（`<pid>` 即启动日志里的 `SERVE_PID`，或 `pgrep -f lark-bridge-all`）。**两个三轮实测出来的坑**：

- **`kill -INT` 对后台启动的进程无效**：shell 以 `&` 启动的子进程继承 `SigIgn` 的 SIGINT 位（实测 `/proc/<pid>/status` 显示 `SigIgn: 0000000000000002`），JVM **不覆盖**已设为 `SIG_IGN` 的信号 ⇒ 后台（`nohup … &` / systemd）运行**只能用 `SIGTERM`**；**前台运行时 Ctrl+C（SIGINT）有效**。（详见「行为事实 2」）
- 🔴 **`SIGTERM` 路径退出码是 `143` 而不是 `0`——这是正常现象，不是失败**：JVM 进入 shutdown sequence 后**锁定退出状态**，hook 里 `latch.countDown()` 释放主线程后再调 `exitProcess(0)` 已无法改变退出码（源码 `ServeCommand.run` 的 `ServePhase.Resident` 分支：`addShutdownHook { bridge.close(); scope.cancel(); latch.countDown() }` → `latch.await()` → `exit(EXIT_OK)`，信号路径下这句 `exit(EXIT_OK)` **冗余但无害**）。**只有 `--once` 路径（`ServeCommand.run` 的 `ServePhase.OnceStopped` 分支）退出码才是 `0`**。（详见「行为事实 1」）

🔴 **怎么判断跑成功了**（三轮实测真实日志形态；appId/openId/botOpenId/连接参数**一律掩码**，作对照样板）：

启动成功样板：
```text
数据根：/home/<你>/.toolbelt/lark
配置来源：/home/<你>/.toolbelt/lark/bridge-config.json
[INFO] 配置变更含 RESTART 字段 [workspace.root]：已记录，需重启生效
[INFO] 已取得单实例锁：/home/<你>/.toolbelt/lark/locks/<16位十六进制>.lock
… DEBUG com.lark.oapi.core.cache.LocalCache -- put key:tenant_token:app_secret:cli_****1bc4: …   ← SDK 打印完整 appId（此处已掩码，见「行为事实 4」）
… INFO  com.lark.oapi.ws.Listener -- connected to wss://msg-frontier.feishu.cn/ws/v2?…           ← 一次性连接参数（device_id/access_key/ticket/conn_id）已略
[INFO] 桥接已启动 appId=cli_aa*** bot=Connected(botOpenId=ou_****, botName=<你的机器人名>)
[INFO] intake 单消费者协程已启动（capacity=512）
桥接长驻运行中，Ctrl+C 停止…
… [pool-1-thread-1] DEBUG com.lark.oapi.ws.Client -- ping success …
… [pool-1-thread-2] DEBUG com.lark.oapi.ws.Client -- receive pong …    ← 每 90s 一轮，pong 线程号递增不复用（见「行为事实 3」）
```

优雅关闭样板（`kill -TERM` 后）：
```text
[INFO] intake 单消费者协程已收尾（processed=N）
… INFO com.lark.oapi.ws.Client -- disconnected to wss://msg-frontier.feishu.cn/ws/v2?…
[INFO] 已释放单实例锁
[INFO] 桥接已停止
```
四行**按序**出现即优雅关闭成功；**退出码 143 属正常**（见上）。

🔴 **建议发哪些消息、各自验什么**（在飞书客户端对机器人发；阶段一为 CLI 纯中继验证——`serve` 无应答引擎，agent 类消息只会收到显式报错，不会有任何真实执行）：

| # | 发送内容 | 场景 | 期望结果 / 验什么 |
|---|---------|------|------|
| 1 | `ping` | 单聊 | 回 `pong 🏓`（`SystemHandler`，**LIGHT 档不占许可**） |
| 2 | `/help` | 单聊 | 回复**内置帮助文本**（`SystemHandler` 内置化直答；静态回复键已随 REPLY 槽退役不迁移，存量 reply 条目经 v9→v10 迁移剥离 ⇒ 不会再出现「用户自定义 help 覆盖内置」的旧形态） |
| 3 | 一句不匹配任何指令的话（如 `今天天气不错`） | 单聊 | **应静默**（TEXT 未命中路由——CLI 形态无调度层，未命中恒静默留痕；`unmatched` 计数增加。对照组：发一张图未命中 ⇒ 收到明确降级文案） |
| 4 | 一张图片 | 单聊 | 验 `rawContentType=image`（进 intake、被路由；观察日志/审计里 `rawContentType` 的**真实取值**与 `chatType`/`chatId`） |
| 5 | 一个 `.log` 文件 | 单聊 | 验 `rawContentType=file`（同上） |
| 6 | `/log 随便写点东西` | 单聊 | 🔴 **不进调度**（CLI 形态无调度层）：消息未命中任何固定指令 ⇒ 静默留痕（`unmatched` 计数增加）。自查：`pgrep -af java` 只应有本 `serve` 进程 |
| 7 | @机器人 + 正文（如 `@bot ping`） | **测试群** | 验群内 @带正文：`mentionedBot=true`、`content` 含正文、命中路由 |
| 8 | @机器人 **不带正文**（只 @） | **测试群** | 验 `content` 为**空串**、`mentionedBot=true` |
| 9 | **不 @** 随便发一句 | **测试群** | 验 `PolicyGate` 拦下（群@门控）、`policyRejected` 计数增加、**不进路由不回** |

🔴 **群聊警告**：第 7–9 项**只在测试群做**，**不要用工作群**——即便阶段一，机器人对 `ping`/`/help` 等仍会**真实回复**，同事能看到。没有测试群就跳过 7–9（不影响 1–6）。

🔴 **证据采集方式**（跑之前先建基线，跑之后对比）：

1. **日志重定向到文件**：命令末尾加 `2>&1 | tee build/t11-user-stage1.log`，保留完整 stdout/stderr。
2. **计数器窗口前后各取一次**：🔴 `serve` 模式**只在收尾打印 `processed=N`**（`EventIntake.start` 收尾日志），22 项计数器**不逐条打印**。计数器增量按两条路取：
   - **JSONL 行增量（主）**：`sender_stats.jsonl` 的 `type` 字段区分处置——`MESSAGE`=放行、`REJECTED_ALLOWLIST`=白名单拒、`REJECTED_BLOCKLIST`=黑名单拒（`SenderStatsRecords.typeOf`）；`audit.jsonl` 的 `status`/`kind`/`errorCode`/`command` 区分每条结果。
   - **离线单事件读数（辅）**：`handle-event` 子命令处理完一个事件后会打印「`== 计数器（五阶段管线）==`」块，含 `accessRejected`/`policyRejected`/`unmatched`/`submitted`/`completed`/`skipped` 等（`HandleEventCommand.run` 的「== 计数器（五阶段管线）==」打印段）；但它走 `SyntheticGateway` 而非真实长连接，仅用于核对计数器语义。
3. **`stat` 对比 JSONL 只增不改不截断**：窗口前后各跑一次
   ```bash
   stat -c '%n size=%s mtime=%Y' ~/.toolbelt/lark/audit.jsonl ~/.toolbelt/lark/sender_stats.jsonl
   wc -l ~/.toolbelt/lark/audit.jsonl ~/.toolbelt/lark/sender_stats.jsonl
   ```
   **原基线**：`audit.jsonl` = 11137 B / 10 行、`sender_stats.jsonl` = 2238 B / 10 行。跑后 size 与行数**只增不减**（append-only，绝不改写/截断既有行）。
4. **`userName` 是否非空**（判 scope `contact:contact.base:readonly` 是否生效）：
   ```bash
   python3 -c "import json;rows=[json.loads(l) for l in open('$HOME/.toolbelt/lark/sender_stats.jsonl') if l.strip()];print('total',len(rows),'with_userName',sum(1 for r in rows if r.get('userName')))"
   ```
   🔴 历史 10 行 `userName` **全为空/无此键**（旧实现从未成功解析）。**新增行 `userName` 非空 = scope 生效**；仍为空 = 未授予或解析降级（不阻塞其它项）。
5. **`--set` ephemeral 的证明**：窗口前后 `bridge-config.json` 与 `.datastore/*.preferences_pb` 的 **size + mtime 必须逐字未变**：
   ```bash
   stat -c '%n size=%s mtime=%Y perm=%a' ~/.toolbelt/lark/bridge-config.json ~/.toolbelt/.datastore/toolbelt.preferences_pb
   ```
   基线：`bridge-config.json` 3682 B / perm 600；`toolbelt.preferences_pb` 3186 B。**任何变化都说明 `--set` 落盘了（不应发生）**。

---

### ③ 阶段二：完整 T11（放开 agent，有真实副作用）

🔴 **阶段二必须走桌面 App 形态**：CLI `serve` 恒为无应答引擎形态（`NoAgentRunner`），agent 全链路只能在 App 里验（App 注入 `KoogAgentRunner`）。

**与阶段一的差异**：
- **在 App 桥接页启动**并配好 AI 端点（AI 设置页），任意未命中消息都会进调度 agent（无需 `ai` 前缀）。
- 信任名单可**扩充**（`access.trustedUsers` 增加更多 openId）或**保持**（只信自己）。
- 拒绝路径与未命中路径恒为既定语义（拒绝静默、TEXT 未命中静默），无文案可配置——旧 `rejectionReply` / `unhandledReply` 配置键均已退役。

🔴 **放开后的真实副作用**（务必知情）：**会真调 LLM 端点**（消耗 API 配额）、**会真建流式卡片**（飞书侧出现「生成中」卡片）、**会真在群里回复**（同事可见）、- **可能真写工作区文件 / 真跑构建任务**（每动作另有确认卡把关；信任用户免卡）。建议仍先在**单聊 + 测试群**做，确认无误再放开范围。

**逐项待验场景 + 通过判据 + 失败长什么样**：

| # | 场景 | 通过判据 | 失败时长什么样 |
|---|------|---------|--------------|
| 1 | agent 全链路 + 流式卡片（CardKit） | 发触发 agent 的消息 → 飞书出现流式卡片，内容**逐段追加**（`createCardInstance` → `updateCardElementContent` → `finishStreamingCard`），终态卡片停止追加 | 卡片永驻「生成中」/ 内容靠**消息编辑**刷屏（🔴 D1 要求**非消息编辑**）/ LLM 端点未配或调用失败 |
| 2 | 🔴 **卡片流泄漏** | 触发 agent → 流式卡开始追加 → **中途 `kill -TERM`** → 飞书侧那张卡停在**终态**（不是永远「Generating…」） | 卡片永远停在「生成中」不收尾。🔴 这一项单元测试是**空真**（无真实卡片），**只能真机验** |
| 3 | 确认卡点击「允许」/「拒绝」 | 触发需权限确认的 agent → 飞书出现**独立非流式确认卡** → 点「允许」agent 继续、点「拒绝」agent 终止；验 `CardJson` 的 `"$token:$acceptValue"` 契约（回调值形如 `<token>:<acceptValue>`） | 点击无反应 / 回调值解析不出 token / 允许后不继续、拒绝后不终止 |
| 3b | 🔴 **确认卡已答态（2026-09-15 新增）** | 裁决后原确认卡整卡变为「✅ 已批准」/「已拒绝」**无按钮**形态（`im.v1.message.patch` 原位更新）；另开窗口看该卡也无按钮（陈旧按钮从视觉根源消失）；文字回执照发 | 原卡按钮滞留可重复点击 / 卡未变样只有文字回执 / patch 报错（scope 或消息形态不匹配——待真机核对 `im:message` 写权限面） |
| 4 | 停止指令 | 长跑 agent 在途时发停止指令 → **URGENT 档绕过许可立即执行** + `lanes.cancelAll(chatId)` 取消该 chat 在途任务 → LLM 调用与在途工具执行被取消传播终止 | 停止指令排队等许可（应 URGENT 立即）/ 在途任务未被取消 |
| 5 | 多轮会话 | 同一会话连续多轮 → agent 复用 ChatMemory 历史（`sessionKey` = chatId + thread 语义），后轮能引用前轮；发「新对话」后 Koog 历史清空、长期记忆保留 | 每轮都当新会话、上下文丢失 / 新对话后旧上下文仍残留 |
| 6 | 引用流文件分析 | 发 zip/文件 → 收到「已收到文件」回执（仅登记不分析）；**引用回复**该文件消息并发送任意消息（App 形态全量消息归调度 agent，无需 `ai` 前缀）→ 文件被下载到 `<workspace>/downloads/<senderId>/<日期>/<消息>/`、按需预处理（解压 / xlog 解密 / 自定义命令）、agent prompt 为**用户原文在前 + `[引用文件 N]` 附加块（名称/路径/目录/预处理产物）在后**的拼接形态；**引用旧气泡 / 重启后登记清空 / TTL 过期**时经 `im.v1.message.get` 补查救回（进度卡见「已找回被引用文件」）；**zip-slip 防护**（`../` 逃逸路径被拒）、**扩展名白名单**、**大小上限**、**每用户 2GB / 全局 8GB 配额 FIFO 逐出**均生效（`files/` 域四件套） | 引用后无反应（登记表未命中且补查未救回）/ 下载目录错位 / 解压路径逃逸 / 超大文件不设限 / 配额不逐出 |
| 7 | 表情回应 | 发消息后机器人 `addReaction` → 飞书侧出现对应 emoji | emoji 不出现 / 报错 |
| 8 | 断线重连 | 手动断网 ~30s 后恢复 → SDK 自动重连（`reconnectCount=-1` 无限重连）并继续收消息 | 断网后不再重连 / 重连后收不到消息 |
| 9 | 统计页（tab 4）环形图 + 入场动画**目视确认** | 进统计页：环厚正常 / 扇数 ≤8 / 尾部归并「其他」/ 起始角在 **12 点方向顺时针** / 入场约 **180ms 渐进绘制** / **总数为 0 时不崩不显示 NaN** | 起始角不在 12 点 / 逆时针 / 无入场动画 / 扇数 >8 不归并 / 空数据显示 NaN 或崩溃 |
| 10 | 🔴 **`.deb` 打包产物真连一次飞书** | 装 `.deb` → 启动 App → 桥接页真连飞书成功收发消息，**不出现 `NoClassDefFoundError` / TLS 握手失败** ⇒ 证 jlink 模块集（S2 的 9 个）在**真实安装环境**下充分 | `NoClassDefFoundError`（缺 `jdk.crypto.*` / `java.naming` 等）/ TLS 握手失败。静态已用裁剪 runtime 的类加载探针证明充分，**实跑是最后一道** |
| 11 | 有负载线程构成 | agent 出站/子进程运行时 `jstack <pid>` 应出现 `bridge-net` / `bridge-pipe` / `bridge-sink` 命名线程（`BridgeDispatchers` 的 `NAME_NET`/`NAME_PIPE`/`NAME_SINK` 常量）。🔴 三轮**空载/轻载只见** `pool-1-thread-N`（SDK WS）+ `DefaultDispatcher-worker-N`（我方 Default 池）+ `ForkJoinPool.commonPool-worker-N` + `OkHttp …` + `main`——`bridge-*` 命名线程**只在真实出站/agent 负载下才 materialize**（`limitedParallelism` 是视图，懒起线程） | 负载下仍无 `bridge-*` 线程 / 线程数无界增长 |
| 12 | 有在途状态的优雅关闭 | agent 在途时 `kill -TERM` → 走 D20 优雅排空（`drainIntake` 停收新事件，在途任务按 grace 收尾或强杀）→ 释放锁 → 退出码 143（正常） | 在途任务被硬切无收尾 / 锁未释放 / 卡死不退出 |
| 13 | 🔴 **BC-1035 十分钟关流裁决（2026-09-12 补回——gateway-contract §13 第 6 条处方，原 runbook 漏登）** | 跑一次 **>11min** 长任务（新代码本来就不轮换，无需改任何配置）→ 观测：① 过程卡**恒 1 张**；② 第 10min 后 patch 是否报错、报什么码（日志 `--verbose` 看桥接侧 check 计数）；③ 卡片最终形态。若中途出现第 2 张卡且日志带 `200750`/`StreamSegmentRotated(reason=EntityExpired 或 SequenceConflict)` ⇒ 是**被动重建**在工作（预期行为，不算失败），记录错误码后继续——届时再议 card().update() 二开 | >10min 后 patch 持续报错且卡片死在中间态不重建（未知码也应触发 REBUILD_STREAM）/ 卡片永驻「生成中」 |
| 14 | 🔴 **结论引用锚定（2026-09-12）** | 群里发提问触发 agent → 结论文本与回传文件消息均带**引用气泡**挂在该提问下；私聊同样操作 → **无引用样式**（豁免）；撤回提问后文件消息仍能直发（TARGET_REVOKED 降级 create） | 群里结论无引用气泡 / 私聊出现引用气泡（豁免失效）/ 撤回提问后文件消息丢失 |
| 15 | 🔴 **产物回传 5 份全量 + 被拒审计（2026-09-12）** | 跑一次多事件分析（4 事件 = 5 份产物）→ **5 份全部回传**且每份文件消息都引用提问消息；`audit.jsonl` 该回合行 `attachments` 含 5 项、`rejectedArtifacts` 为 null。再把 `agent.artifacts.maxCount` 临时调成 3 复跑 → 6、7 份被拒时正文末尾还原被拒行、审计行 `rejectedArtifacts` 非空可查 | 5 份场景仍截断（配置页把 maxCount 从旧文档的 3 调上来——真机文档已显式写 3 不会吃到新默认）/ 被拒条目审计里查不到 |

---

### ④ 收尾

🔴 **跑完请轮换 appSecret**：它在本次重构过程中曾以明文出现在协作对话里，也已落进那个 600 权限的 `bridge-config.json`。轮换后在 App 桥接页或 CLI 更新 `connection.appSecret`。

---

## 三轮真机验证新发现的行为事实（登记）

> 下列 4 条为三轮安全子集真机验证**新发现**的行为事实，按「现象 / 根因 / 是不是缺陷 / 用户怎么应对」登记；均已按现网源码核实。

### 事实 1 · `SIGTERM` 退出码 = 143，`--once` 退出码 = 0

- **现象**：`kill -TERM` 停 `serve` 长驻进程，shell 拿到退出码 **143**；`serve --once` 正常退出码 **0**。
- **根因**：JVM 收到 SIGTERM 后进入 shutdown sequence 并**锁定退出状态**；hook 里 `latch.countDown()` 释放主线程后再调 `exitProcess(0)` 已**无法改变**退出码（Java 规范）。源码 `ServeCommand.run` 的 `ServePhase.Resident` 分支。
- **是不是缺陷**：**不是**。⇒ `ServeCommand.kt` 长驻路径的 `exit(EXIT_OK)`（`ServePhase.Resident` 分支末尾）在**信号路径上冗余但无害**；`exitProcess` 契约在 **`--once` 路径（`ServePhase.OnceStopped` 分支）上仍是必需的**——那里没有信号触发的 shutdown，而 SDK `gateway.close()` 后仍残留 2 个非 daemon 线程会挂住 JVM。
- **用户怎么应对**：对外部编排工具（systemd/docker/CI），**143 是标准的「被 SIGTERM 终止」码，不应视为错误**；但**若有脚本断言退出码 == 0，在信号路径上会 fail**——这类脚本应改判 143，或只在 `--once` 路径断言 0。

### 事实 2 · `kill -INT` 对后台启动的进程无效

- **现象**：`nohup java -jar … serve &` 后台起的进程，`kill -INT <pid>` **不触发**优雅关闭；前台运行时 Ctrl+C 有效。
- **根因**：shell 以 `&` 启动的子进程**继承 `SigIgn` 的 SIGINT 位**（实测 `/proc/<pid>/status` 显示 `SigIgn: 0000000000000002`），JVM **不覆盖**已设为 `SIG_IGN` 的信号。
- **是不是缺陷**：**不是代码缺陷，是文档措辞陷阱**。⇒ CLI `--help` 里「Ctrl+C 优雅停止」的说法**只在前台运行时成立**。
- **用户怎么应对**：以 `nohup … &` / systemd 等**后台运行请用 `SIGTERM`**（`kill -TERM`）；前台运行 Ctrl+C 即可。

### 事实 3 · 🔴 SDK `com.lark.oapi.ws.Client` 线程池 = `newFixedThreadPool(availableProcessors()*2+1)`

- **现象**：`serve` 长跑时 `pool-1-thread-N` 编号**只增不复用**——每 90s 一次 pong 就新增一个（三轮日志实证：round2 pong 走到 `thread-18`、round3 走到 `thread-19`；有事件进来时编号继续往后排）。
- **根因**（🔴 **字节码反汇编实证**，非推断）：`javap -c -p` 显示 `Client` 构造函数里 `Runtime.getRuntime().availableProcessors()` → `iconst_2 imul` → `iconst_1 iadd` → `Executors.newFixedThreadPool(I)`，即 **`newFixedThreadPool(核数×2+1)`**。`ThreadPoolExecutor.execute()` 在 `workerCount < corePoolSize` 时**总是新建线程而不复用空闲线程** ⇒ 每次 pong 新增一个 worker，编号不复用。
- **是不是缺陷**：**不是泄漏，是有界**。上限 = **核数×2+1**（22 核机器上是 45），约 **67 分钟（每 90s 一次 pong 新增一个 worker，90s × 45 ≈ 4050s ≈ 67.5min）到达上限后永久稳定**；45×1MB ≈ **45MB 虚拟内存** ⇒ 对 7×24 长驻**无实质影响**。
  - 🔴 **曾有两个错误推断被字节码实证否定，保留以防后来者重犯**：① 认为是 `CachedThreadPool` + keepAlive 60s 会回收（**错**——是 FixedThreadPool，corePoolSize 内不回收）；② 推算 960 线程/天 ≈ 1GB（**错**——忽略了 FixedThreadPool 的 `corePoolSize` 上限，到 45 就封顶）。
- **用户怎么应对**：无需处理。若观察到 `pool-1-thread-N` 编号停在某个上限（≈ 核数×2+1）后不再增长，即为正常收敛。

### 事实 4 · 飞书会推送未订阅的事件类型，桥接正确降级

- **现象**：三轮实测收到**未在桥接注册**的事件类型 `im.chat.access_event.bot_p2p_chat_entered_v1`（进入单聊）与 `im.message.message_read_v1`（消息已读）；SDK 以 **WARN** 打印 `not found handler` 并**丢弃**。
- **根因**：飞书按应用后台的订阅/权限推送一批事件，桥接只注册了 `im.message.receive_v1` 与 `card.action.trigger` 的 handler；未注册类型 SDK 找不到 handler ⇒ WARN + drop。
- **是不是缺陷**：**不是**。⇒ **不进 intake**（三轮 `processed=0` 即证）、**不影响功能、不崩**。**副作用**：`--verbose` 模式下长时间运行 + 用户频繁阅读消息会产生**大量 WARN 噪音**。
- **用户怎么应对**：噪音可忽略。**可选改进（登记为待议，本轮不实施）**：给 `:lark-bridge` 加一份 `logback.xml` 把 `com.lark.oapi.ws.Client` 压到 ERROR。🔴 注意 App 侧已有 `core/…/LogbackHelper.kt`（把 `com.lark.oapi` 压到 WARN），但 **CLI fat-jar 里没有 `logback.xml`**，所以 CLI 侧不受控。
- ⚠️ **相关已知灰色地带**：SDK 的 `com.lark.oapi.core.cache.LocalCache` 在 **DEBUG** 级别会打印**完整 appId**（三轮日志实证 `put key:tenant_token:app_secret:cli_****1bc4:`；桥接侧自己的 `emit()` 是掩码的）。**appId 不是 secret，不构成安全风险**，但与掩码纪律存在灰色地带；若在意，同样可用上面那份 `logback.xml`（把 `com.lark.oapi` 压到 INFO/WARN）解决。

---

### 附：升级前的原始 8 项待验清单（保留，交叉索引用）

> 下表是升级前「待真机确认（T11）」小节的既有清单，已被上面阶段一/阶段二**逐项吸收并补全通过判据**；保留于此便于对照。其中第 5 项（双实例互斥）已在三轮中真机通过、第 1 项的静态部分（jlink 模块充分性）已由类加载探针证明，实跑部分归阶段二第 10 项。🔴 **第 2 项的取证口径已于交付前修正**（原文误指 `audit.jsonl`，实为 `sender_stats.jsonl` 的 `userName` 键——审计磁盘 schema 不含名字字段，详见该行）。

| # | 待验项 | 验证方法 |
|---|--------|---------|
| 1 | S2 jlink 模块保留对 oapi-sdk 2.8.5 是否充分（是否还需 `jdk.crypto.*` / `java.naming`） | 用打好的 `.deb` 实际收发一次消息，确认不出现 `NoClassDefFoundError` 或 TLS 握手失败 |
| 2 | `resolveSenderName` 真实取名连通性 | 需先授 scope `contact:contact.base:readonly`（旧名已更名，2026-09-11 真机实证），发一条消息后确认 **`sender_stats.jsonl` 新增行的 `userName` 键非空**。🔴 **不是 `audit.jsonl`**——审计磁盘 schema `AuditEntryJson`（`lark-bridge/…/observe/AuditLog.kt`）**恰好 13 字段（2026-09-12 产物回传 +`attachments`、同日 artifact-cap-default-and-rejected-audit +`rejectedArtifacts`）、不含 `senderName`/`userName`**，被 `behavior-contract.md` BC-299..318 🔒 锁定（锁的是兼容纪律：往返一致/缺键兼容/未知键容忍；归档 §4.11 的 11 字段表已被 2026-09-12 的 `attachments` 追加 11→12、`rejectedArtifacts` 追加 12→13 **已登记例外**——见上文「agent 产物回传」小节），设计裁定不为 senderName/userName 扩审计 schema。这是**刻意设计、非能力回归**：旧实现的审计同样没有名字字段（三重证据核实——`git show bd03d58` 的旧 `AuditEntry`（audit/AuditLogger.kt）、旧测试的 legacy 行、活体样本 `audit.jsonl` 首行 10 键集合 `ts/chatId/senderOpenId/prompt/command/status/errorCode/elapsedMillis/backend/kind`）。用户名解析结果**只落 `sender_stats.jsonl` 的 `userName`**（新实现 `SenderStatsEntry.userName: String = ""` + `encodeDefaults=true` ⇒ 新行**总带此键**，值为名字或空串）。 |
| 3 | 卡片流泄漏 | 用真开流式卡的 agent 任务验：观察卡片是否正常收尾（`finishStreamingCard`）而非永驻「生成中」 |
| 5 | 双实例 `BlockedByPeerInstance` 跨 JVM 互斥实效 | 同时启两个 `serve` 进程（同 appId），确认后者被拒 |
| 6 | 断线重连 | 手动断网 30s 后恢复，观察是否自动重连并补收消息 |
| 7 | 表情回应 | 发一条消息后 `addReaction`，确认 emoji 出现 |
| 8 | 统计页（tab 4）环形图与入场动画的**实际视觉渲染** | 人工进入统计页目视确认：环厚/扇数 ≤8/尾部归并「其他」/起始角在 12 点方向顺时针/入场有约 180ms 渐进绘制/总数为 0 时不崩不显示 NaN。（裁定：不引入 Compose UI test 依赖——角度数学/除零/扇数截断已被 `DonutChartGeometry` 纯函数 8 条单测覆盖，`Canvas { drawArc }` 只是薄封装 + `AppMotion.standard()`，真机目视比 headless 组合测试更强） |
