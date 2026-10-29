# lark-bridge-module Specification

> ⚠️ **本规格已作废（superseded）**。桥接模块已在 `rewrite-lark-bridge` 中推倒重写，旧 `:lark-bridge` 已于 M2 拆除并重建为 `:lark-bridge`（纯 JVM lib）+ `:lark-gateway`（唯一 SDK 隔离适配层）；本 v1 正文（连接层抽象、任务引擎、Skill 系统、权限纵深等描述）与新架构已不对应，仅作历史留档，不再维护。实现以 `openspec/changes/rewrite-lark-bridge/`（`behavior-contract.md` / `config-domain-spec.md` / `gateway-contract.md`）+ 源码为准。

## Purpose

定义独立 Kotlin JVM library 模块 :lark-bridge 的行为契约：模块不依赖任何 UI 框架，可被 composeApp 应用与 CLI 两种宿主复用，涵盖门面 API、运行日志事件流、飞书连接层抽象、任务引擎、三分路由类型标签、Skill 技能系统、多目录白名单沙箱、权限纵深防御与运行时确认、CLI 出口能力。

## Requirements

### Requirement: 独立 library 模块

系统 SHALL 新增独立 Kotlin JVM library 模块 `:lark-bridge`（包名 `com.jiaoay.lark.bridge`），不依赖任何 UI 框架，可被 `:composeApp` 与 CLI 两个宿主复用。

#### Scenario: 模块可独立构建

- **WHEN** 执行 `./gradlew :lark-bridge:build`
- **THEN** 构建成功，产出 library jar

#### Scenario: app 模块可依赖 lib

- **WHEN** `:composeApp` 的 commonMain 添加 `implementation(project(":lark-bridge"))`
- **THEN** `./gradlew :composeApp:build` 构建成功

### Requirement: 门面 API

系统 SHALL 提供 `LarkBridge` 门面类作为唯一公共入口，暴露：`state: StateFlow<BridgeState>`、`observeLogs(): Flow<BridgeLogEvent>`、幂等的 `start()`/`stop()`、`checkEnvironment()`、`sendTestMessage(text)`。

#### Scenario: start/stop 幂等

- **WHEN** 对 Running 状态的桥接重复调用 `start()`，或对 Stopped 状态重复调用 `stop()`
- **THEN** 均为无副作用操作，状态不变、不抛异常

### Requirement: 运行日志事件流

系统 SHALL 通过 `observeLogs()` 暴露 SharedFlow 日志总线（replay=200，溢出策略 DROP_OLDEST），日志事件携带 level/tag/message/source，source 区分 BRIDGE、LARK_CLI_STDOUT、LARK_CLI_STDERR。

#### Scenario: 晚订阅者回看历史

- **WHEN** 桥接运行产生超过 200 条日志后，新订阅者订阅 `observeLogs()`
- **THEN** 立即收到最近 200 条历史日志

#### Scenario: 慢消费者不阻塞引擎

- **WHEN** UI 消费日志速度低于产生速度
- **THEN** 总线按 DROP_OLDEST 丢弃最旧事件，引擎不阻塞

### Requirement: 飞书连接层抽象

系统 SHALL 定义 `LarkTransport` 接口（事件流 + 回复/发送/上传 + 环境检查），默认实现基于 lark-cli 子进程守护（stderr `[event] ready event_key=` 标记判定启动成功、stdout JSON 行事件流、非零退出指数退避重启、stdin 保持打开）。

#### Scenario: 消费子进程启动失败重启

- **WHEN** lark-cli event consume 子进程非零退出
- **THEN** 按指数退避策略重启（initial × 2^attempt 封顶），并记录重启次数与健康状态

#### Scenario: 环境预检

- **WHEN** 调用 `checkEnvironment()`
- **THEN** 返回 lark-cli 可用性、版本、auth 状态

### Requirement: 任务引擎

系统 SHALL 提供 `TaskExecutor` 注册表机制：宿主注入执行器列表，引擎按装配顺序 first-match-wins 分发事件（每个执行器的 `match` 为纯判定）；引擎负责 event_id 去重（LRU）、并发上限（Semaphore）、异常兑底回复。Agent 执行器 SHALL 在路由命中后、执行前完成发送者访问控制校验与工作目录约束校验（工作区域未配置时拒绝执行；显式工作目录必须位于工作区域内）。访问控制 SHALL 支持白名单（默认，空名单拒绝所有发送者）与黑名单（命中拒绝，其余放行）两种模式，未知模式回退白名单（fail-safe）。被拒绝时若配置了拒绝回复模板（支持 {sender} 占位符）SHALL 回复发送者，否则静默跳过并记录 open_id。无执行器匹配时若配置了未匹配兑底回复 SHALL 回复发送者。Agent 执行器的 `match` SHALL 为纯判定、零副作用（不写日志、不触发统计回调、不构造 prompt）；访问控制拒绝的副作用（拒绝日志/统计/拒绝回复渲染）SHALL 迁至 dispatch 未命中路径经 `consumeRejectionReply(event)` 送达，被拒发送者仍收到拒绝回复。Agent 执行器 SHALL 支持权限档位（只读/安全/工作区/完全开放/自定义），默认档位为「安全」禁删档（claude 去 Bash、qoder/copilot 去 shell，保留读写编辑），未知档位回退安全档（fail-safe）；档位映射为各后端原生工具限制语法；custom 后端档位不适用。安全提示注入启用时，Agent 后端 SHALL 按档位分层追加安全约束段（`AgentBackends.safetyPromptSuffix(preset, prohibitDelete)`）：`prohibitDelete=true` 注入禁删除/禁清空两句；**恒注入**禁操作白名单外路径；「禁止执行任意 shell」仅当 `!presetAllowsShell(preset)`（readonly/safe/未知档）才注入，workspace/all/custom 档（已授予 shell）不注入该句，避免 prompt 与可执行档自相矛盾。Agent 执行器 SHALL 在每次执行前后追加审计记录到 JSONL 文件，并在消息被接受/拒绝时向宿主统计回调发送事件（含 open_id/chatId/类型/内容摘要）。Agent 执行器 SHALL 支持紧急停止指令：精确匹配停止指令时终止该 chat 在途 agent 进程。当运行时确认启用且后端为 claude 时，Agent 执行器 SHALL 在渲染命令时注入 `--permission-prompt-tool` 参数指向本地 MCP permission server，使 agent 的高风险工具调用经确认服务把关。

#### Scenario: 事件重推去重

- **WHEN** 同一 event_id 的事件被投递两次
- **THEN** 第二次被丢弃，任务只执行一次

#### Scenario: 执行器异常不中断服务

- **WHEN** 某任务执行器抛出异常
- **THEN** 引擎捕获并回复失败结果，服务继续运行

#### Scenario: 白名单外发送者无法驱动 agent

- **WHEN** 白名单模式下发送者 open_id 不在白名单内（或白名单为空）且消息命中 agent 触发前缀
- **THEN** agent 子进程不被启动，日志记录被拒发送者 open_id；配置了拒绝回复时回复发送者

#### Scenario: 黑名单模式命中拒绝

- **WHEN** 黑名单模式下发送者 open_id 命中黑名单且消息命中 agent 触发前缀
- **THEN** agent 子进程不被启动；黑名单外发送者正常放行

#### Scenario: 拒绝回复模板渲染

- **WHEN** 发送者被访问控制拒绝且拒绝回复模板含 {sender} 占位符
- **THEN** 回复内容中占位符替换为发送者 open_id；模板为空时静默不回复

#### Scenario: match 纯化后拒绝回复仍送达

- **WHEN** 发送者被访问控制拒绝，Agent 执行器 `match` 返回 false 使消息落到无执行器命中路径
- **THEN** 引擎经 `consumeRejectionReply(event)` 取回拒绝文案并回复发送者（`match` 本身无副作用，拒绝日志/统计在 dispatch 未命中路径集中触发）

#### Scenario: 未匹配兑底回复

- **WHEN** 消息未命中任何执行器且配置了未匹配兑底回复
- **THEN** 回复配置文本给发送者；未配置时保持静默

#### Scenario: 工作目录越界拒绝启动

- **WHEN** agent 配置的显式工作目录规范化后不位于工作区域内
- **THEN** agent 子进程不被启动，回复启动失败结果并说明越界原因

#### Scenario: 未配置工作区域拒绝执行

- **WHEN** 工作区域未配置且消息命中 agent 触发前缀
- **THEN** agent 子进程不被启动，回复「请先配置工作区域」类提示

#### Scenario: 只读档位限制工具集

- **WHEN** 权限档位配置为「只读」且 agent 后端为 claude/qoder/copilot
- **THEN** 渲染命令仅携带该后端的只读工具集（无写入/执行类工具）

#### Scenario: 安全档位为默认禁删档

- **WHEN** 权限档位未配置或配置为「安全」（默认档）
- **THEN** claude 工具集去除 Bash、qoder/copilot 去除 shell（保留读写编辑），未知档位值回退安全档（fail-safe）

#### Scenario: 完全开放档位放开工具

- **WHEN** 权限档位配置为「完全开放」且 agent 后端为 copilot
- **THEN** 渲染命令携带 `--allow-all-tools`（claude/qoder 携带全量工具列表）

#### Scenario: 自定义档位透传非空工具配置

- **WHEN** 权限档位配置为「自定义」且 allowedTools 非空
- **THEN** allowedTools 原始值按后端语法透传

#### Scenario: 宿主路径 custom + 空工具降级 safe

- **WHEN** 权限档位为「自定义」但 allowedTools 为空（宿主路径）
- **THEN** `PermissionPreset.resolveTools` 降级为 safe 安全工具集，MUST NOT 返回空串（空串会使后端不加限制 → 完全开放，与用户认知相反）

#### Scenario: 禁 shell 句仅在不允许 shell 的档注入

- **WHEN** 安全提示注入启用且档位为 readonly/safe/未知（`!presetAllowsShell`）
- **THEN** system prompt 尾部安全约束段含「禁止执行任意 shell」句；档位为 workspace/all/custom 时不注入该句

#### Scenario: 禁删句随禁删开关注入

- **WHEN** 安全提示注入启用且 `prohibitDelete=true`
- **THEN** 安全约束段含禁删除/禁清空两句；`prohibitDelete=false` 时不注入（尊重用户显式关闭）；禁操作白名单外路径一句恒注入

#### Scenario: custom 后端档位不适用

- **WHEN** agent 后端为 custom 且权限档位非「自定义」
- **THEN** 不追加任何工具限制参数（模板自带，档位映射跳过）

#### Scenario: 停止指令终止在途进程

- **WHEN** 某 chat 的 agent 进程执行中，同 chat 发送者发送精确匹配的停止指令
- **THEN** 在途进程被终止，发送者收到终止确认回复，会话保留

#### Scenario: 无在途进程时停止指令

- **WHEN** 该 chat 无在途 agent 进程时收到停止指令
- **THEN** 回复「当前没有在途任务」类提示，不拉起 agent

#### Scenario: 审计记录落盘

- **WHEN** agent 执行完成（无论成功/失败/超时）
- **THEN** 一条 JSONL 审计记录追加写入审计文件，含时间戳/chatId/senderOpenId/prompt 摘要/命令摘要/状态/退出码/耗时

#### Scenario: 确认启用时 claude 命令注入 permission-prompt-tool

- **WHEN** 运行时确认启用、后端为 claude 且确认总线已就绪
- **THEN** 渲染命令追加 `--permission-prompt-tool` 参数，值指向本地 MCP permission server 启动脚本

### Requirement: 回复类型标签（三分路由）

系统 SHALL 在 `TaskResult` 携带 `kind: TaskKind`（枚举 `COMMAND`/`SKILL`/`AGENT`，默认 `COMMAND`，旧调用签名不变），标识结果由哪一类处理器产出。回传层（`LarkResponder`）SHALL 按 kind 渲染类型前缀标签（`⚡ 指令` / `🧩 技能` / `🤖 Agent`），与状态标识（任务完成/失败/已跳过）拼在同一行加粗。审计记录 `AuditEntry.kind` SHALL 随记录落盘，供宿主按类型分类聚合。

#### Scenario: Agent 结果带 Agent 标签

- **WHEN** agent 执行器产出 kind=AGENT 的结果
- **THEN** 回传飞书的文本以 `🤖 Agent` 类型前缀开头，后接状态标识与 summary

#### Scenario: 技能结果带技能标签

- **WHEN** 技能执行器（别名命中委托 agent）产出 kind=SKILL 的结果
- **THEN** 回传文本以 `🧩 技能` 前缀开头，区别于直接 agent 路由

#### Scenario: 指令结果带指令标签

- **WHEN** ping / 固定回复 / 确认裁决等内置指令产出 kind=COMMAND 的结果
- **THEN** 回传文本以 `⚡ 指令` 前缀开头；COMMAND 类不落审计（审计仅覆盖 agent 执行链）

### Requirement: Skill 注册与发现

系统 SHALL 提供配置驱动的技能系统（`skill/` 包，lib 不依赖宿主、不依赖 `:log-tools`）：`SkillRegistry` 构造时**合并内置技能（`BuiltInSkills.ALL`）与 `BridgeConfig.skills`** 建索引，仅 enabled + 非空别名的技能参与匹配，别名匹配 SHALL 为最长前缀匹配（纯函数、无 IO、非阻塞）。`SkillExecutor` SHALL 支持**三种处理模式**：`/skills`（或 `/skills <id>`）发现命令直接返回技能清单/详情且 MUST NOT 跑 agent；别名命中时 SHALL 把别名后参数填入 promptTemplate 的 `{args}` 占位符（模板为空则裸 prompt 透传）；自然语言形态（`ai 技能名:参数`）命中时 SHALL 与别名通道归一处理，两通道均委托 `AgentExecutor.executeRenderedPrompt(..., resultKind = SKILL)` 复用完整 agent 管线（访问控制/串行/排队/超时/进程管理/会话/审计）。agent 未启用时命中返回失败（errorCode `skill_agent_disabled`）；模板为空返回 `skill_template_empty`。

技能执行器 SHALL 装配在固定回复之后、**宿主注入执行器（含宿主 `AgentExecutor`）之前**（C4），且 SHALL **恒装配**（不再以「存在 enabled + 非空别名技能」为装配条件——内置技能兜底使 `/skills` 与 `/log` 开箱可用）——技能别名（前缀匹配）与自然语言通道比 agent 触发前缀更具体，必须优先命中，否则宿主注入的 `AgentExecutor` 会以 `startsWith` 遮蔽同前缀技能别名（如 agent 前缀 `ai` 遮蔽技能别名 `ai-log`），且裸 agent 前缀会遮蔽自然语言技能路由。

技能系统 SHALL 收紧信息边界：`/skills` 清单与 `/skills <id>` 详情 MUST NOT 回显 promptTemplate 全文（模板含内部流程/路径/业务规则，属实现细节），对外仅展示 name / 别名 / description / 触发示例 / 需确认标记。`SkillExecutor` 的 `match`/`execute`/清单/详情四条入口 SHALL 均经 `executors/SenderAccessControl`（从 `AgentExecutor` 提取的共享判定，与 agent 共用同一份白/黑名单）闸门：未授权发送者发 `/skills` 或别名 SHALL 一律不命中（也不能用 `/skills` 枚举技能清单），事件落入引擎兑底拒绝路径由其统一生成拒绝回复并计入 `senderStats.onRejected`（技能侧不另开回复路径）。

标记 `requiresConfirm=true` 的技能 SHALL 采用 fail-safe：当确认链路不可用（`AgentExecutor.isRuntimeConfirmAvailable()==false`，即后端非 claude / `confirmEnabled=false` / provider 未就绪）时 SHALL 返回失败（errorCode `skill_confirm_unavailable`）阻止执行，MUST NOT 只记日志照常执行（fail-open → fail-safe）。

#### Scenario: /skills 发现命令不跑 agent

- **WHEN** 用户发送 `/skills` 或 `/skills <id>`
- **THEN** 直接返回技能清单（name + 别名 + 说明 + 触发示例）或单技能详情，毫秒级响应，kind=SKILL，不拉起 agent 子进程

#### Scenario: 清单与详情不回显模板全文

- **WHEN** 授权用户查看 `/skills` 清单或 `/skills <id>` 详情
- **THEN** 回复仅含 name / 别名 / description / 触发示例 / 需确认标记，不含 promptTemplate 全文

#### Scenario: 未授权发送者不命中技能

- **WHEN** 未授权发送者（白名单外/黑名单内）发 `/skills` 或技能别名
- **THEN** `SkillExecutor` 不命中（无法枚举技能也无法执行），事件落入引擎兑底拒绝路径，计入 `senderStats.onRejected`

#### Scenario: 别名最长前缀命中委托 agent

- **WHEN** 用户发送 `/log-analyse x`，同时存在别名 `/log` 与 `/log-analyse`
- **THEN** 命中最长前缀 `/log-analyse`，参数 `x` 填入模板 `{args}`，委托 agent 管线执行，产出 kind=SKILL

#### Scenario: agent 未启用时别名命中失败

- **WHEN** 技能别名命中但 agent 未启用（或未注入）
- **THEN** 返回失败结果，errorCode 为 `skill_agent_disabled`，不拉起子进程

#### Scenario: 需确认技能在确认链路不可用时被阻止

- **WHEN** 技能 `requiresConfirm=true` 但确认链路不可用（非 claude / confirmEnabled=false / provider 未就绪）
- **THEN** 返回失败结果，errorCode 为 `skill_confirm_unavailable`，不拉起子进程（fail-safe）

### Requirement: 内置技能（开箱可用）

系统 SHALL 在 lib 侧预置内置技能 `BuiltInSkills`（不依赖 `:log-tools`，仅靠 promptTemplate 引导 agent 复用工作区内能力），当前含 `log-analysis`（id=`log-analysis`、name=`日志分析`、别名=`/log`、`requiresConfirm=false`、promptTemplate 含 `{args}`）。`SkillRegistry` 构造时 SHALL 合并内置技能与 `BridgeConfig.skills`（`mergeSkills`）：以内置为底，用户同 id 配置 SHALL **整体覆盖**内置项（覆盖后仍标记 `builtIn=true` 以示来源），用户对内置 id 设 `enabled=false` SHALL 关闭该内置技能，其余用户技能 SHALL 追加。`SkillExecutor` SHALL **恒装配**，使 `/skills` 与内置技能别名在 CLI 与 App 双宿主开箱可用。`/skills` 清单 SHALL 对内置技能标注来源（⁽内置⁾），MUST NOT 因内置技能而回显其 promptTemplate 全文。

#### Scenario: config.skills 为空时内置技能开箱可用

- **WHEN** `config.skills` 为空列表，授权用户发送 `/skills` 或 `/log <参数>`
- **THEN** `SkillExecutor` 仍恒装配并命中——`/skills` 清单含内置「日志分析」（标 ⁽内置⁾、别名 `/log`），`/log <参数>` 命中内置技能委托 agent 执行，kind=SKILL

#### Scenario: 用户同 id 覆盖内置技能

- **WHEN** `config.skills` 含 id=`log-analysis` 的用户配置项
- **THEN** 用户项整体覆盖内置项（name/别名/promptTemplate 等以用户配置为准），清单仍标注该技能来源为内置

#### Scenario: 用户 enabled=false 关闭内置技能

- **WHEN** `config.skills` 含 id=`log-analysis` 且 `enabled=false`
- **THEN** 该内置技能被关闭，不参与别名/自然语言匹配，`/skills` 清单不含该技能

### Requirement: 自然语言触发通道

系统 SHALL 提供自然语言技能触发通道（`SkillRegistry.findByNaturalLanguage`），形态为 `<agent触发前缀> <技能名/别名> <冒号> <参数>`：冒号 SHALL 兼容中文「：」与英文「:」，参数 SHALL 只在**首个冒号**处切分。解析 SHALL 经三重闸门，任一不满足即返回未命中（fall through 给 `AgentExecutor` 走裸 agent）：① 文本 SHALL 以某 agent 触发前缀开头，且去前缀后以空格开头或为空；② 去前缀后的 body SHALL 含冒号且冒号非首字符（`colonIdx > 0`）；③ 冒号前文本 SHALL **完整等于**（区分大小写）某 enabled 技能的 name / 别名（去斜杠或原样）/ id。命中后 SHALL 与别名通道归一（`aliasMatch ?: nlMatch`）走同一执行管线（`SenderAccessControl` 复检 → `requiresConfirm` fail-safe → renderPrompt → `executeRenderedPrompt(kind=SKILL)`）。因 `SkillExecutor` 装配先于 `AgentExecutor`，拦截 SHALL 在技能执行器内完成，MUST NOT 要求修改 `AgentExecutor` 的前缀解析。

#### Scenario: 裸 agent 提问不被误拦截

- **WHEN** 授权用户发送 `ai 随便问个问题`（无「技能名:」形态）
- **THEN** 三重闸门第 ② 条不满足（无冒号），`findByNaturalLanguage` 未命中，fall through 给 `AgentExecutor`，只剥前缀作为 prompt，kind=AGENT，不注入任何技能模板

#### Scenario: 中英文冒号均命中且参数只按首个冒号切分

- **WHEN** 授权用户发送 `ai 日志分析: 看crash` 或 `ai 日志分析：看crash`
- **THEN** 两种冒号均命中内置技能，参数取首个冒号之后的全部文本填入 `{args}`，剥离 agent 前缀与技能名后委托 agent 执行，kind=SKILL

#### Scenario: dry-run 四消息路由 kind 正确

- **WHEN** 以 `BuiltinExecutors.build` 真实装配链依次喂入 `/skills`、`/log 分析crash`、`ai 裸问题`、`ai 日志分析: 看crash`（`config.skills` 为空）
- **THEN** 命中执行器依次为 skill / skill / agent / skill，回传 kind 依次为 SKILL / SKILL / AGENT / SKILL，前三者中委托 agent 的两次拉起子进程、`/skills` 清单不跑 agent（`SkillRoutingDryRunTest` 断言，作为 Test Plan 飞书端手测的自动化替代证据）

### Requirement: Agent 进程沙箱约束（多目录白名单）

系统 SHALL 以多目录白名单（`WorkspacePolicy`）定义 agent 允许根集合：由单目录 `workspaceDir` 与多目录 `workspaceWhitelist` 取并集构成（`WorkspacePolicy.fromConfig`），构造时一次性 canonical 解析所有根并缓存。启动 agent 子进程时工作目录规范化（canonical path）后 SHALL 位于任一根之内，否则拒绝启动；路径前缀比对 SHALL 做分隔符对齐（防 `/foo/bar` 误放行 `/foo/barbaz`）；空白名单（roots 为空）SHALL fail-safe 拒绝所有路径。`agentWorkDir` 为空时 SHALL 回退到白名单第一根（不继承应用进程 cwd）。子进程环境变量 SHALL 采用白名单传递，不继承应用进程的完整环境：Unix 平台白名单为 PATH、HOME、LANG、LC_ALL、TZ、代理变量（含大小写变体）；**Windows 平台在 Unix 集合之外追加系统必需键 `SystemRoot`、`SystemDrive`、`USERPROFILE`、`HOMEDRIVE`、`HOMEPATH`、`APPDATA`、`LOCALAPPDATA`、`COMSPEC`、`PATHEXT`、`TEMP`、`TMP`**（均为系统运行键，不含凭据类变量；追加 SHALL 仅限该封闭列表，宿主不得运行时注入任意键）。agent 可执行文件解析的 fallback 目录 SHALL 覆盖 npm 官方全局安装位：Unix 追加 `~/.npm-global/bin`、Windows 追加 `%APPDATA%\npm`（其余 fallback 目录不变）。本沙箱定位为「降低概率 + 提高可见性」的纵深防御，非内核级强隔离（agent 为外部子进程）。

#### Scenario: 多根白名单内合法启动

- **WHEN** agent 工作目录（显式配置或回退到白名单第一根）规范化后位于白名单任一根内
- **THEN** 子进程以该目录为工作目录启动，环境变量仅包含白名单项

#### Scenario: 越界工作目录被拦截

- **WHEN** agent 工作目录规范化后不位于白名单任一根内（如 /tmp、$HOME 根）
- **THEN** 进程不被启动，返回启动失败结果

#### Scenario: 空白名单 fail-safe

- **WHEN** 单目录与多目录白名单均为空（roots 为空）
- **THEN** `WorkspacePolicy` 拒绝所有路径校验（无白名单 = 无允许区域），agent 拒绝执行

#### Scenario: 分隔符对齐防前缀混淆

- **WHEN** 白名单根为 `/foo/bar`，待校验路径为 `/foo/barbaz`
- **THEN** 校验不通过（分隔符对齐避免前缀子串误放行）

#### Scenario: 环境变量不泄露敏感值

- **WHEN** 应用进程环境中存在 API Key / Token 类变量（如 GITHUB_TOKEN）
- **THEN** agent 子进程环境中不包含未列入白名单的变量

#### Scenario: Unix 平台白名单零变化

- **WHEN** 应用运行于 macOS / Linux，agent 子进程被启动
- **THEN** 子进程环境变量键集与平台化扩展前完全一致（PATH、HOME、LANG、LC_ALL、TZ、代理变量及大小写变体），不含 Windows 系统键

#### Scenario: Windows 平台白名单含系统必需键

- **WHEN** 应用运行于 Windows，agent 子进程被启动
- **THEN** 子进程环境变量在 Unix 集合之外包含 `SystemRoot`/`USERPROFILE`/`APPDATA`/`COMSPEC`/`PATHEXT` 等系统必需键（存在时），否则 node 系 agent CLI 无法初始化

#### Scenario: npm 官方全局安装位可被发现

- **WHEN** 用户经 `npm install -g`（默认 prefix，未配置 nvm/自定义 prefix）安装 agent CLI，桌面环境 PATH 不含 npm bin 目录
- **THEN** `~/.npm-global/bin`（Unix）或 `%APPDATA%\npm`（Windows）中的可执行文件被 agent 可执行文件解析的 fallback 搜索命中（解析顺序不变：PATH 逐目录 → 用户级 fallback → 未找到）

### Requirement: 下载文件落入工作区域

系统 SHALL 将飞书消息中的文件下载固定写入 `<工作区域>/downloads/<yyyy-MM-dd>/` 目录，文件名经清洗处理，写入前对最终路径做规范化校验，确保不越出工作区域。

#### Scenario: 下载文件落在日期目录内

- **WHEN** 收到含文件的消息并触发下载
- **THEN** 文件写入 `<工作区域>/downloads/<yyyy-MM-dd>/`，路径规范化后仍位于工作区域内

#### Scenario: 路径穿越被拦截

- **WHEN** 构造的下载路径（如含 .. 的文件名）规范化后越出工作区域
- **THEN** 下载被拒绝并记录失败日志

### Requirement: 运行时确认服务

系统 SHALL 提供确认状态机（ConfirmService）：确认请求进入 PENDING 后同步等待裁决，裁决来源为飞书确认回复（APPROVED/DENIED）、超时（TIMEOUT，默认 60 秒，等同拒绝）或用户停止指令（CANCELLED）。确认请求与 agent 进程 1:1 挂起（MCP stdio 同步请求-响应）。确认回复 SHALL 仅接受原消息发送者（chatId + senderOpenId 与发起请求时一致）的裁决，防群聊替答。

#### Scenario: 批准后 agent 继续

- **WHEN** agent 请求权限产生确认请求，原发送者回复「确认 <id>」
- **THEN** 状态机转为 APPROVED，MCP server 返回 allow，agent 继续执行

#### Scenario: 拒绝后 agent 自行调整

- **WHEN** 原发送者回复「拒绝 <id>」
- **THEN** 状态机转为 DENIED，MCP server 返回 deny，agent 收到拒绝说明并自行调整策略（进程不被 kill）

#### Scenario: 超时等同拒绝

- **WHEN** 确认请求超过配置时长（默认 60 秒）无裁决
- **THEN** 状态机转为 TIMEOUT，等同 DENIED（agent 收到拒绝，进程不被 kill）

#### Scenario: 非原发送者确认被拒

- **WHEN** 群聊中非发起 agent 任务的用户回复「确认 <id>」
- **THEN** 该回复不产生裁决，回复提示仅原请求者可确认

#### Scenario: 停止指令取消在途确认

- **WHEN** 确认等待期间用户发送停止指令
- **THEN** 在途确认转为 CANCELLED，agent 进程被终止

### Requirement: 确认总线与 MCP permission server

系统 SHALL 提供本地 TCP 确认总线（127.0.0.1 随机端口，JSON 行协议）：stdio MCP server（独立短命进程，由 claude 经 `--permission-prompt-tool` 按需拉起）把权限请求转发到总线并同步等待裁决响应。MCP server SHALL 实现 MCP 协议的 initialize/tools-list/tools-call 方法（工具名 `permission_prompt`），把 tool_name + input 透传为确认请求内容；MCP server 的 chatId/senderOpenId SHALL 优先从 CLI `--chat`/`--sender` 取（由 provider 模板替换占位符后传入），回退到 `params["chat_context"]`（向后兼容）。总线 SHALL 在桥接停止时关闭，残留连接不阻塞进程退出。CLI 子命令 `permission-server` SHALL 支持 `--bus-port`（`--port` 为向后兼容别名）与 `--chat`/`--sender`。

**stdio 编码契约（本变更新增）**：MCP server 的 stdin/stdout SHALL 显式按 UTF-8 编解码（MCP 协议约定，不依赖平台默认编码——JDK 17 打包态 Windows zh-CN 默认字符集为 GBK，按平台默认收发将与 claude 的 UTF-8 约定错位，含中文路径的请求会乱码并导致白名单判定失配）。

系统 SHALL 在门面（`LarkBridge`）**构造期只建对象、不绑端口**：判定 `confirmConfigured = agent.enabled && confirmEnabled && backend == claude`（否则确认关闭），仅此时创建 `ConfirmService` → `LarkConfirmNotifier` → `ConfirmBus(service, logger, timeoutMillis, notifier)`，此时 SHALL NOT 绑定任何 loopback 端口、不启动协程作用域；确认超时 SHALL 经 `clampedConfirmTimeoutMillis(confirmTimeoutSeconds)` **下限钳制 10 秒**（0/负值会让确认卡片刚发出即超时拒绝）。

确认链路 SHALL 在 `LarkBridge.start()` 内经 `startConfirmRuntime()` 端到端装配（接线延迟到 start，Idle 态零资源）：`bus.start()` 绑**真实随机端口**（回环 `ServerSocket(0)`；端口 ≤ 0 时降级并置 `Unavailable`）→ `PermissionServerLauncher(busPort, workDir, workspaceWhitelist).prepare()` 生成启动脚本（null 时关总线降级）→ `selfCheck(script)` 以空 stdin 拉起脚本自检（`serve()` 读 EOF 即返回 0，验证 java 可执行 + classpath 完整 + MainKt 可加载 + 参数可解析；Failed 时删脚本 + 关总线降级）→ 三步全通过后 SHALL 用命令模板 `commandToken(script.path) + " --chat {chatId} --sender {senderOpenId}"` 把 provider 闭包回填到所有 `AgentExecutor.permissionPromptToolProvider`（`@Volatile`，内置与宿主注入实例均回填）并置 `ConfirmRuntimeStatus.Ready(port, scriptPath)`；任一步失败 SHALL 降级（关总线 / 删脚本 / 清 provider）并置 `ConfirmRuntimeStatus.Unavailable(reason)` 且记 error 级告警。`stop()` SHALL 无条件先调 `releaseConfirmRuntime()`（关总线 CAS 幂等 + 删启动脚本 + 清模板与 provider + 状态归位），Idle 态亦清理，避免宿主 recreate() 每次泄漏端口/协程。

系统 SHALL 不再拼 `java -cp …` shell 字符串作为 `--permission-prompt-tool` 的值（旧法三重失效：路径含空格被拆词、开发态 classpath 数十 KB 撞命令行长度上限、jpackage 态 `java.class.path` 不含业务类致 MainKt ClassNotFound），改由 `PermissionServerLauncher` 生成 `${java.io.tmpdir}/easydev-toolbox-perm/easydev-permission-server.sh|.bat`（classpath 取 `java.class.path` ∪ 自身 codeSource、路径平台化转义、含空格 token 加引号），claude 的 `--permission-prompt-tool` 值只指向脚本；脚本转发全部入参（unix `"$@"` / windows `%*`），故 `--chat`/`--sender` 由命令模板占位符执行时替换，无需为每个会话重写脚本。

**启动脚本内容与写出编码（本变更新增）**：脚本内容 SHALL 为纯 ASCII（注释与结构均不含非 ASCII 字符），使脚本在任何 ANSI 代码页下语义不变；Windows 平台脚本字节 SHALL 按平台 ANSI 编码（`native.encoding`，缺省回退 UTF-8）写出，确保中文用户名路径（`C:\Users\<中文名>\…`）被 cmd.exe 正确解析。`ClaudeBackend.buildArgsWithContext` 渲染命令时 provider 优先级 SHALL 为：执行时 `providerOverride` > 构造期 provider > 静态 `permissionPromptToolCommand`（向后兼容）。

系统 SHALL 经门面暴露可观测状态 `LarkBridge.confirmRuntimeStatus: StateFlow<ConfirmRuntimeStatus>`，把「运行时确认不可用」从一行 warn 日志升级为宿主可观测状态：`Disabled`（agent 关 / confirmEnabled=false / 后端非 claude / 桥接已停，正常配置态）、`Ready(port, scriptPath)`（总线已监听 + 脚本自检通过）、`Unavailable(reason)`（总线/脚本/自检任一失败，高危操作将 fail-safe deny，附人读原因）。宿主（App 设置页 / CLI serve）SHALL 收集本状态，`Unavailable` 时据此显式告警，而非让用户困惑于「为何全部被拒」。

#### Scenario: MCP server 转发权限请求

- **WHEN** claude 调用 permission_prompt 工具（tool_name + input）
- **THEN** MCP server 经总线发送确认请求并阻塞等待，收到裁决后按 MCP 响应格式返回 allow/deny

#### Scenario: MCP stdio 按 UTF-8 编解码（本变更新增）

- **WHEN** claude 向 stdin 写入含中文路径的 UTF-8 请求，或 MCP server 回写含非 ASCII 字符的裁决
- **THEN** MCP server 显式按 UTF-8 解码读取与编码回写，中文内容不乱码、白名单判定基于正确路径

#### Scenario: start() 内自检通过后回填 provider 并置 Ready

- **WHEN** `LarkBridge.start()` 内 `bus.start()` 取到真实端口、`PermissionServerLauncher.prepare()` 生成脚本、`selfCheck()` 拉起通过
- **THEN** provider 闭包回填到所有 AgentExecutor，`confirmRuntimeStatus` 置 `Ready(port, scriptPath)`；claude 渲染命令时追加 `--permission-prompt-tool`，值只指向启动脚本（含空格时整体加引号），`--chat`/`--sender` 执行时替换占位符

#### Scenario: Windows 启动脚本自检可通过（本变更新增）

- **WHEN** 应用运行于 Windows，`selfCheck()` 拉起 `.bat` 启动脚本
- **THEN** 脚本经 `cmd.exe /d /c` 执行，自检验证 java 可执行 + classpath 完整 + MainKt 可加载 + 参数可解析，通过后 `confirmRuntimeStatus` 置 `Ready`

#### Scenario: Idle 态不绑端口

- **WHEN** 门面已构造但尚未调 `start()`（Idle 态）且 confirmConfigured=true
- **THEN** `ConfirmBus` 未绑定任何 loopback 端口（`isRunning=false`、`port=-1`），未启动协程作用域

#### Scenario: 启动脚本自检失败降级为 Unavailable

- **WHEN** `bus.start()` 端口 ≤ 0、或 `prepare()` 返回 null、或 `selfCheck()` 非 0 退出/超时
- **THEN** 降级（关总线 / 删脚本 / 清 provider），`confirmRuntimeStatus` 置 `Unavailable(reason)` 并记 error 级告警（高危操作将 fail-safe deny）

#### Scenario: 启动脚本内容为纯 ASCII 且按平台编码写出（本变更新增）

- **WHEN** `prepare()` 在 Windows 平台生成 `.bat` 脚本（工作目录或 classpath 含中文路径）
- **THEN** 脚本内容不含非 ASCII 字符（注释为英文），字节按平台 ANSI 编码（native.encoding）写出，cmd.exe 按 ANSI 解析时路径不乱码、java 可正常拉起

#### Scenario: 后端非 claude 时确认不可用

- **WHEN** agent.enabled && confirmEnabled 但后端非 claude
- **THEN** 启动自检置 `confirmRuntimeStatus` 为 `Unavailable`（附原因「运行时确认仅 claude 后端支持」）并告警

#### Scenario: 确认超时下限钳制

- **WHEN** 配置的 `confirmTimeoutSeconds` 为 0 或负值
- **THEN** `clampedConfirmTimeoutMillis` 钳制为下限 10 秒（避免确认卡片刚发出即超时拒绝）

#### Scenario: 总线断连时默认拒绝

- **WHEN** MCP server 无法连接确认总线（桥接已停止/端口失效）
- **THEN** MCP server 返回 deny 并以非零退出码退出（fail-safe：无法确认即拒绝）

#### Scenario: 桥接停止时无条件释放

- **WHEN** 桥接服务停止（调 `stop()`，含 Idle 态）
- **THEN** `releaseConfirmRuntime()` 关确认总线（CAS 幂等）、ServerSocket 关闭、在途确认全部转为 CANCELLED、删除启动脚本、清模板与 provider、`confirmRuntimeStatus` 归位

### Requirement: 飞书确认指令与确认卡片下发

系统 SHALL 在执行器链前端装配确认执行器（ConfirmExecutor）：精确匹配「确认 <id>」/「拒绝 <id>」格式的消息并驱动确认状态机；确认指令 SHALL NOT 进入 agent 路由（精确匹配短路）。确认请求产生时系统 SHALL 经确认通知出口（`ConfirmNotifier`/`LarkConfirmNotifier`）向原会话下发确认卡片：`ConfirmBus` SHALL 在 `newRequest` 后、`awaitDecision` 前调用 notifier；卡片为飞书 interactive（schema 2.0，橙色「⚠️ 高危操作确认」标题），含工具名、操作摘要（截断 120 字符）、4 位短确认 ID、确认/拒绝指令引导；经 `transport.sendCard(chatId, ...)` 发送；下发失败 SHALL 降级不阻断（MUST NOT 抛异常，仅记日志，不影响状态机继续等待裁决）；dry-run 只记日志不发送。

#### Scenario: 确认卡片内容完整

- **WHEN** agent 请求权限产生确认请求
- **THEN** bot 经 `LarkConfirmNotifier` 向原会话发送 interactive 卡片（schema 2.0），含工具名、操作摘要（截断 120 字符）、短确认标识（如 a3f2）与「发送 `确认 <id>` 批准 / `拒绝 <id>` 阻止」引导

#### Scenario: 卡片下发失败不阻断确认等待

- **WHEN** 确认卡片下发失败（transport 异常）
- **THEN** notifier 仅记日志不抛异常，确认状态机仍继续等待裁决（不阻断主链路）

#### Scenario: 确认指令不进 agent 路由

- **WHEN** 消息为「确认 a3f2」且存在匹配的待确认请求
- **THEN** ConfirmExecutor 消费该消息，agent 执行器不收到该事件

#### Scenario: 未知确认标识回复提示

- **WHEN** 确认指令携带的短标识无匹配的待确认请求（已裁决/不存在）
- **THEN** 回复「确认请求不存在或已处理」类提示

### Requirement: 高危操作分类器（RiskClassifier）

系统 SHALL 提供高危操作分类器 `confirm/RiskClassifier.classify(toolName, input, policy?)`，输出四级风险（`LOW`/`MEDIUM`/`HIGH`/`FORBIDDEN`）并附命中规则描述：破坏性命令模式（如 `rm -rf /`、fork bomb、`chmod -R 777 /`）归 FORBIDDEN；删除类工具名/命令模式与写工具越出白名单路径归 HIGH；写入/shell 类工具归 MEDIUM；其余（读取类）归 LOW。路径越界校验仅在传入非空 `policy`（WorkspacePolicy）时生效。

分类器 SHALL 已端到端接入 MCP 确认运行时链路：`McpPermissionServer.handleToolsCall` 在把权限请求送确认总线之前 SHALL 先调用 `RiskClassifier.classify` 分级，并按等级决定处置——`FORBIDDEN` SHALL 直接返回 deny 且 MUST NOT 送确认总线（破坏性命令不存在「确认即放行」路径，进程以 `EXIT_DENY=3` 退出）；`LOW` SHALL 直接返回 allow 且 MUST NOT 送确认总线（只读操作免飞书确认，避免确认卡片淹没）；`MEDIUM`/`HIGH` SHALL 送 `ConfirmBus` 走飞书裁决。`McpPermissionServer` 的 `policy` SHALL 由 CLI 子命令 `permission-server` 经 `--work-dir`/`--workspace-whitelist` 构建（`WorkspacePolicy.fromConfig(...).takeIf { !it.isEmpty }`）；白名单为空时 `policy=null`，仅做非路径类规则（删除/破坏性模式），不做越界判定。

#### Scenario: 破坏性命令归 FORBIDDEN 并直接拒绝

- **WHEN** claude 请求执行 `rm -rf /` 类破坏性命令模式
- **THEN** `classify` 返回 FORBIDDEN，MCP server 直接返回 deny（behavior=deny）且不连确认总线，进程以退出码 3 退出

#### Scenario: 只读操作归 LOW 并自动放行

- **WHEN** claude 请求执行读取类工具（LOW）
- **THEN** `classify` 返回 LOW，MCP server 直接返回 allow 且不连确认总线（免飞书确认）

#### Scenario: 删除/写入类归 HIGH/MEDIUM 送飞书裁决

- **WHEN** toolName 为删除类工具（Delete/Remove/DeleteFile 等）或命中删除命令模式（HIGH），或为写入/shell 类工具（MEDIUM）
- **THEN** MCP server 连确认总线发送权限请求并阻塞等待飞书裁决

#### Scenario: 写入越出白名单归 HIGH

- **WHEN** 传入非空 `policy` 且写工具的目标路径越出白名单
- **THEN** 返回 HIGH 风险等级并送飞书确认；未传 `policy`（空白名单）时不做路径越界判定，其余规则仍生效

### Requirement: CLI 出口

系统 SHALL 通过 shadowJar 提供 CLI 入口（clikt 子命令树）：`serve`（启动桥接服务）、`check`（环境自检）、`send`（发送测试消息）、`handle-event`（离线回放事件 JSON）、`permission-server`（stdio MCP permission server，由 claude 按需拉起），支持 `--dry-run`（不起子进程，打印将执行的命令与回复）。

共享选项（`cli/CommonOptions.kt`，serve / handle-event 同参）SHALL 提供安全底线与技能类参数：`--agent-prohibit-delete` / `--no-agent-prohibit-delete`（默认开）、`--agent-safety-prompt` / `--no-agent-safety-prompt`（默认开，内容按档位分层）、`--agent-allowed-tools`（逗号分隔，仅 `custom` 档生效）、`--skill <id>=<别名逗号分隔>=<展示名>=<模板>`（可重复，`{args}` 为用户输入占位符）、`--skill-confirm <id>`（可重复，标记该技能 requiresConfirm=true）、`--sender-stats-dir`。`--agent-permission-preset custom` SHALL 必须同时给非空 `--agent-allowed-tools`，否则抛 `UsageError`（CLI 边界 fail-fast，防静默完全开放）。

#### Scenario: 离线回放事件

- **WHEN** 执行 `java -jar lark-bridge-all.jar handle-event --event samples/xxx.json --dry-run`
- **THEN** 不启动 lark-cli，打印事件解析结果与将回复的内容，退出码为 0

#### Scenario: custom 档缺工具列表时 fail-fast

- **WHEN** CLI 传 `--agent-enable --agent-permission-preset custom` 但未给非空 `--agent-allowed-tools`
- **THEN** `toBridgeConfig()` 抛 `UsageError`（提示 custom 必须同时指定非空工具列表，否则静默完全开放）

#### Scenario: --skill 与 --skill-confirm 回放技能路由

- **WHEN** CLI 传 `--skill "log-analyse=/log,/log-analyse=日志分析=分析日志 {args}" --skill-confirm log-analyse`
- **THEN** 解析为一个 `SkillConfig`（id=log-analyse、别名=/log 与 /log-analyse、requiresConfirm=true），handle-event 回放可复现线上技能路由

### Requirement: 过程卡片滚动窗口（全量替换推送）

桥接的过程卡片流式链路 SHALL 以「全量替换最新窗口快照」为唯一推送语义：渲染层 `ProgressAccumulator` 维持有界窗口（最近 N 条事件，条目截断与整卡截断沿用既有 Limits），泵层 `pumpSnapshotsToCard` SHALL 把每次快照**直通** `CardStreamSink.tryReplace(text)`，不做增量拼接、不因窗口丢头整帧重推。卡片在任意时刻 SHALL 恒等于「状态 + 最近 N 条事件」的最新快照——不累积历史、不产生重复段落。

`CardStreamSink` 接口 SHALL 只暴露 `tryReplace(text: String): Boolean`（1 槽 conflate 覆盖：新快照直接替换槽内旧快照，`dirty=true`）；**不得**保留 `tryAppend` 增量语义的成员。网关层主循环的 flush 判定 SHALL 为「`snapshot.dirty` 且 250ms 时间窗到点」——快照内容任何变化（含**变短**：窗口丢头挤掉旧事件）SHALL 触发 patch；不得以「增量字符数 > 0」作为 flush 条件。

**单卡收敛（2026-09-12 single-card-no-rotation）**：主动 TimeDeadline 轮换 SHALL 退役——无论流式会话持续多久，网关层 SHALL NOT 因时间到期强发新卡；一次任务的过程卡 SHALL 恒为 1 张（受理卡），终态原地更新。安全网三层：
1. **被动重建**：服务端返回 200750（CardEntityExpired）或任何**未登记业务码**时，SHALL 自动重建新卡续命（复用既有 rotateSegment；重建失败 SHALL 置 sink 关闭并退出主循环——不得 25ms 无限重试活锁）；已知结构性错误码（300302/230099/230001/300312/11311/10002 → CardJsonInvalid）SHALL 保持关闭不重建（重试必然再错）。
2. **字节预算轮换保留**：单卡内容达 24KB（`cardByteBudget`，飞书 30KB 硬限防御）SHALL 仍触发轮换；滚动窗口 cardMaxChars=2000 下恒不可达，仅作 cardMaxChars 调大时的兜底。
3. **旧卡收尾可观测**：轮换/重建时对旧卡的「定格 patch + finishSettings 关流」失败 SHALL 记 warn 日志并计入失败计数（不得静默吞）；收尾失败不阻断新卡。

🔴 **重建预算（2026-09-12 对抗评审补入）**：仅封死「重建失败」SHALL NOT 视为收敛——「patch 持续报错而建卡持续成功」时每 250ms 就能成功重建一次（≈4 张幽灵卡/秒入会话，恰是本 change 要消灭的刷屏换了触发路径）。故 sink SHALL 维护**连续重建计数**：达上限（`Tuning.maxConsecutiveRebuilds`）即关闭流式并记 warn；每次重建后 SHALL 退避 `Tuning.streamRebuildBackoffMs`；计数 SHALL **只在一次成功 patch 后清零**（在重建成功后清零等于没有上限）。`firePatch` 清除 dirty SHALL 以**发出那一版**为准（CAS 比对），不得用无条件写回——否则 patch 挂起窗口内到达的新快照被误标已刷，卡片停在旧内容。

进度事件单条长度 SHALL 由 `agent.progress.entryMaxChars`（Number，40..200，默认 80，HOT）配置并透传至 `ProgressAccumulator`。配置文档 SHALL 不含 `agent.progress.patchIntervalMillis`（schemaVersion 4 已迁移）。

#### Scenario: 长任务过程卡恒 1 张

- **WHEN** agent 任务持续 18 分钟且持续产出事件
- **THEN** 全程只有受理时发出的 1 张过程卡（终态原地更新），群里不再出现 9min 轮换的新卡

#### Scenario: 长跑任务 9 分钟轮换续命

- **WHEN** 单条流式会话持续超过 9 分钟
- **THEN** 主动 TimeDeadline 轮换已退役（2026-09-12 single-card-no-rotation），网关层不再因时间到期强发新卡——续命职责由服务端关流时的**被动重建**承接（见「服务端关流时被动重建续命」场景）；本场景保留为历史语义记录，不再是现行行为

#### Scenario: 服务端关流时被动重建续命

- **WHEN** 服务端对 patch 返回 200750 EntityExpired（或任何未登记业务码）
- **THEN** 网关层自动新建卡片实体续命（发出新卡），进度不中断；若重建建卡也失败，sink 关闭且主循环终止（无活锁）

#### Scenario: patch 持续失败时不无限重建刷幽灵卡

- **WHEN** patch 持续返回未登记码而建卡/发卡持续成功（如建出的卡缺元素导致 patch 路径 404）
- **THEN** 重建次数达 `maxConsecutiveRebuilds` 后 sink 关闭并记 warn——绝不每 250ms 重建一次把幽灵卡刷进会话

#### Scenario: 瞬时抖动不累积到重建上限

- **WHEN** 重建后的某次 patch 成功
- **THEN** 连续重建计数清零（抖动不累积，长任务不因偶发失败被误杀）

#### Scenario: 已知结构性错误不重建

- **WHEN** patch 返回 300302（卡片 JSON 结构错误）
- **THEN** sink 关闭不重建（换卡必然重演同类错误），失败计数可见

#### Scenario: 快照变短也必须 patch

- **WHEN** 新快照比上一已推送快照短（dirty=true 但字符增量为负）
- **THEN** 250ms 时间窗到点时网关层照样发出 patch

#### Scenario: 卡片恒等于最新窗口快照

- **WHEN** agent 持续输出事件使窗口丢头（旧事件被挤出）
- **THEN** 每次推送均为当前窗口的全量替换，卡片不含历史累积段落，长度有界于窗口上限

#### Scenario: 高频推送被节流合并

- **WHEN** 1 秒内调用 `tryReplace` 1000 次
- **THEN** 网关层实际 patch ≤ 4 次（250ms 窗口），且最终卡片内容等于最后一次快照（latest-wins）

#### Scenario: entryMaxChars 生效

- **WHEN** 用户把 `agent.progress.entryMaxChars` 从 80 改为 40（HOT 生效）
- **THEN** 后续过程卡快照的单条事件长度上限变为 40 字符（超长截断加省略号），无需重启桥接

#### Scenario: patchIntervalMillis 调参保底迁移

- **WHEN** v3 配置文档的 `agent.progress.patchIntervalMillis` 为非默认值且执行 v3→v4 迁移
- **THEN** 该值折入 `experimental.retiredV3` 键下；默认值静默删除

### Requirement: agent 产物回传（标记约定 + 文件消息）

桥接 SHALL 以「system prompt 约定 + 末尾标记行」的通用协议回传 agent 产物文件，不逐技能定制：

**注入**：agent 后端构造时 SHALL 按配置 `agent.artifacts.enabled` 在 effective system prompt 末尾条件追加独立的「产物交付约定」段（告知 agent：任务产出报告/结果文件时，在最终回复**最末尾**每行一条 `[产物] <文件绝对路径>`；未产出则不加）。该段 SHALL 与安全提示段相互独立（安全提示关闭时产物约定仍注入）。

**解析**：桥接 SHALL 只从 agent 最终回复的**末尾连续块**提取标记行——从尾部越过空行后必须命中标记行才回溯收集连续标记块；正文中间出现的 `[产物]` 字样 SHALL 不剥离、不解析（防 code block / 引文误伤）；空白路径条目跳过。

**校验与上传**：每条路径 SHALL 依次通过 tilde 展开 → 文件存在 → `WorkspacePolicy` canonical 沙箱校验（路径来自 agent 文本绝不信任；空 roots fail-safe 拒绝所有）→ 大小上限（`agent.artifacts.maxSizeBytes`）→ 数量上限（`agent.artifacts.maxCount`）→ 扩展名白名单（`agent.artifacts.allowedExtensions`，空 = 不限），全部通过后经出站出口以**两步上传**回传（`uploadFile` 得 file_key → raw client 直发 `msg_type="file"` + `content={"file_key":…}`——🔴 SDK `SendInput.file` 首参语义是本地路径/URL，不可用于 file_key），两步各计一次频控名额（上传步 acquire 计点；限流识别由网关侧 `GatewaySignal.RateLimited` 信号驱动退避）。

**降级**：仅任务状态为 SUCCESS 且 `enabled=true` 时进入处理；任何校验或上传失败的条目 SHALL 把标记行原样还原到正文末尾（附原因注释，用户至少可见绝对路径）；解析/编排整体异常 SHALL 降级为原文照发（🔴 `CancellationException` 必须显式 rethrow——取消/超时落在上传窗口时终态不错乱）；交付期每次出站 SHALL 先 `progress.touch()` 喂空闲看门狗。agent 未写标记行 ⇒ 零上传零副作用（与现状行为一致）。

**审计（2026-09-12 artifact-cap-default-and-rejected-audit 扩容）**：成功回传的产物清单 SHALL 写入回合审计记录（`attachments`，格式 `<文件名> <- <原路径>`）；**被拒条目清单 SHALL 写入同条审计记录的 `rejectedArtifacts`**（元素即还原到正文的同款行：`[产物] <路径>  <!-- 原因 -->`——一份数据两处消费，正文供用户即时可见、审计供事后取证）。两者均沿「后加可空字段」惯例：`List<String>? = null`，旧格式审计行 decode 为 null 不破坏；空清单落 null 不产空键。

**配置**（`agent.artifacts` 4 字段，全 HOT）：`enabled`（默认 true）、`maxCount`（1..10，**默认 5**——2026-09-12 真机取证：AVM 多事件 skill 按 N+1 产出（4 事件 = 5 份），默认 3 会把按序末尾的汇总报告截断；上限的存在意义是防 agent 失控刷文件雨，不是压正常场景，5 恰好覆盖典型值而未拉满 10）、`maxSizeBytes`（默认 26214400 = 25MB，MB 显示口径）、`allowedExtensions`（逗号分隔，默认空）。带默认值键改默认 SHALL 走 typed 层默认值兜底，不 bump schemaVersion；存量文档已显式写值的用户配置不受默认变更影响。

**网关契约**：`OutboundKind` SHALL 含 FILE；`MessageGateway` SHALL 暴露 `suspend fun uploadFile(source: Path, displayName: String?): String?`（默认实现返回 null 表示不支持，替身与测试假件零破坏）；SDK 实现走 `im().v1().file().create`（fileType="stream"）。

#### Scenario: 成功回合产物以文件消息回传

- **WHEN** agent 成功完成日志分析任务，最终回复末尾含 `[产物] /workspace/reports/analysis_result_x.md`
- **THEN** 该文件经沙箱校验后以飞书文件消息发送给用户，文字回复剥离标记行且正文保留

#### Scenario: 默认上限下 AVM 五份产物全量回传

- **WHEN** agent 回合末尾产出 5 条标记行（4 单事件 + 1 汇总），配置文档未显式写 `agent.artifacts.maxCount`
- **THEN** 5 份全部回传，零截断，审计行 `attachments` 含 5 项且 `rejectedArtifacts` 为 null

#### Scenario: 超限被拒条目落审计可取证

- **WHEN** agent 产出 7 条标记行而 `maxCount=5`，按序第 6、7 条被「超出数量上限」拒绝
- **THEN** 5 份照常回传；正文末尾还原 2 条被拒行（附原因），同回合审计行 `rejectedArtifacts` 含这 2 条同款还原行——失败取证不依赖翻飞书聊天记录

#### Scenario: agent 不写标记行则零副作用

- **WHEN** agent 正常回复且末尾无任何标记行（或 `enabled=false`）
- **THEN** 零上传、正文逐字节原样，行为与该功能不存在时一致

#### Scenario: 正文中间的标记字样不误伤

- **WHEN** agent 回复正文（非末尾）里引用了 `[产物] 示例` 字样且末尾无标记块
- **THEN** 正文原样保留，该字样不触发解析与上传

#### Scenario: 沙箱外路径拒绝上传并还原告知

- **WHEN** 标记行路径经 canonical 校验在 WorkspacePolicy 之外（或文件不存在 / 超大小 / 超数量 / 扩展名不匹配）
- **THEN** 不上传该条目，正文末尾以「以下产物未能回传」段还原标记行（附原因），被拒行同步落审计 `rejectedArtifacts`，文字回复不受阻

#### Scenario: 旧格式审计行读回不破坏

- **WHEN** 解析不含 `rejectedArtifacts` 键的历史审计行
- **THEN** decode 为 null 不报错；含该键的新行正常读出清单

#### Scenario: 审计记录携带产物清单

- **WHEN** 一条产物成功回传
- **THEN** 该回合审计行含 `attachments`（`<文件名> <- <原路径>`）；旧格式审计行读回时 attachments 为 null 不报错

#### Scenario: 上传或解析异常不阻断文字回复

- **WHEN** uploadFile 返回失败（如无 im:resource 权限）或编排过程抛异常
- **THEN** 文字回复照常送达（失败条目还原为标记行 / 整体原文照发），服务不中断

### Requirement: 结论引用锚定（群聊锚定、私聊豁免）

桥接的出站回复链 SHALL 支持把回复消息锚定为对提问消息的「引用回复」，用于群聊中定位结论归属：

**注入**：`GatewayReplySink` SHALL 以构造参数接收 `replyToMessageId: String?`，在私有 `send()` 出口一处以 `out.copy(replyToMessageId = ...)` 注入——text / markdown / card / file 四条出站链 SHALL 一次全部继承锚定（含拒绝回复与未命中兜底），后续新增出站链路 SHALL 天然携带。

**豁免**：锚定值 SHALL 由 `RequestContextFactory.replyAnchorOf(message)` 唯一判定：**群聊**（`chatType != P2P`）取提问消息 `messageId`；**私聊**（P2P）SHALL 返回 null（豁免——一对一场景引用气泡是噪音）；🔴 **卡片回传**（`kind == CARD_ACTION`）SHALL 一律返回 null——`fromCardAction` 硬编码 `chatType = UNKNOWN`（卡片回传事件不提供 chatType），仅靠 P2P 判定时整条卡片回传路径的豁免恒失效（私聊里点确认按钮的裁决回复会被错误锚定）；锚定语义是「锚到用户的**提问**」，卡片回传是用户对机器人的交互、不是提问，故一律不锚定。默认路径（`EventIntake`）与生产路径（`BridgeAssembler` replyOverride）两个 sink 构造点 SHALL **调用同一函数**取值（SHALL NOT 内联复制判定——双写会使测试背书的判定与生产运行的判定静默分叉）。

**file 链 reply 分支**：`SdkLarkGateway.sendFileByKey` SHALL 在 `replyToMessageId` 非空时优先走 reply endpoint（`im().v1().message().reply`，`msgType="file"`）；为空时保持 create 直发。reply 失败且错误码属 TARGET_REVOKED 类（230020 消息被撤回 / 230017 无权限 / http 404）SHALL 降级 create 重发——**产物不丢优先于锚定完整**；其余失败码 SHALL 原样走既有出站错误处理，不吞不静默。

**接受边界**：长结论分片只有首片锚定（SDK 内置 `i==0`）；markdown 走 reply 先转 post、被拒自动降级纯文本且保留锚定（SDK 内置行为）；进度流式卡不在锚定范围（过程态无需归属定位，`CardStreamPort` 不扩签名）。

#### Scenario: 群聊结论引用提问消息

- **WHEN** 用户在群聊发消息触发 agent 任务成功完成
- **THEN** 结论文本消息以「引用回复」形式锚定到该提问消息（outbound 携带提问 messageId），群里可定位这是谁的结论

#### Scenario: 私聊豁免不产生引用样式

- **WHEN** 同一提问发生在私聊（chatType == P2P）
- **THEN** `replyAnchorOf` 返回 null，结论以普通消息直发，与现状行为一致

#### Scenario: 卡片回传不锚定

- **WHEN** 用户在私聊（或群聊）点确认卡的「允许」按钮，cardAction 事件经 `fromCardAction` 进入（chatType 恒 UNKNOWN）
- **THEN** 裁决回复不携带锚定（不会被错误挂到确认卡上）——卡片回传不是提问

#### Scenario: 文件消息同样锚定且降级不丢

- **WHEN** 群聊回合带产物回传，file 消息走 reply endpoint 失败且错误码为 230020（提问被撤回）
- **THEN** 自动降级 create 重发该文件消息（产物不丢），不再带引用关系；其余错误码原样上抛走既有错误处理

#### Scenario: 四条出站链一处注入处处生效

- **WHEN** 同一回合产生 text 结论、markdown 结论、拒绝回复、file 产物四种出站消息且群聊锚定非空
- **THEN** 四条消息全部携带同一 `replyToMessageId`（无需各链路单独接线）
