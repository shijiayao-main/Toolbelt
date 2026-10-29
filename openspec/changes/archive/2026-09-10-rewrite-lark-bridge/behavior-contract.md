# 行为基线契约（behavior-contract.md）

> 里程碑 **M3-T1** 产出物。来源：M0 快照基线（HEAD=`bd03d58`）下旧 `:lark-bridge`（50 个测试文件 / 483 个 `@Test`）与旧 `:feature-larkbridge`（10 个测试文件 / 67 个 `@Test`）的**全部** 550 个测试方法。
> 唯一真源方案：`重写飞书桥接_ae9f1206.md`。SDK 实测证据：同目录 `spike-findings.md`。

---

## 1. 文档目的与使用方式

本文件是旧 60 个测试文件逐方法提取的**可执行行为规范**——旧模块 `:lark-bridge` 与 `:feature-larkbridge` 将在 M2 被整体删除，但**删除代码不等于删除规范**：这 550 个断言点是过去数月真机试错换来的行为契约，其中相当一部分（argv 渲染、权限档位映射、zip-slip 双重校验、审计 schema、脚本烘焙）是「实测定稿型」，重写即回归。因此：**M4/M5/M6 的每一个实现步骤，其产出物必须能绑定到本文件的至少一个 `BC-xxx` 编号；没有对应条目的步骤不算完成**；反向亦成立——标为「移植」的条目若在新架构中找不到承接它的测试，即为验收缺口。使用方式：实现某个域时，按 §3 主契约表筛出该域的条目，逐条在新测试类中落地断言（凡关联 D# 的条目**不许原样照抄旧断言**，必须按「锁住的行为」列给出的新形态写）；遇到字面值疑问查 §4；遇到 SDK 边界导致的形态变化查 §5；遇到旧代码坐标与方案表述不符查 §6；新增的非功能守护测试查 §7；缺陷验收查 §8；无法自行裁定的争议点查 §9。

---

## 2. 基线统计

### 2.1 数量核对

| 维度 | 方案宣称 | 实测 | 结论 |
|---|---|---|---|
| 测试**文件**数 | 50（lib）+ 10（UI） | 50 + 10 = **60** | **完全吻合** |
| 测试**方法**数 | 未宣称（方案「50 个测试 / 10 个测试」是**文件口径**） | 483（lib）+ 67（UI）= **550** | 实际断言点是 550 个，方案未给出方法口径数字，**无冲突但需在后续任务书中统一用方法口径** |
| 被测源文件数 | 79（lib）+ 32（feature） | 79 + 32 = **111** | 与方案 M0 表述一致 |
| `openapi/ws/*` 测试文件数 | 方案「测试策略」写「5 个」 | **4 个**（ChunkCache / V2EventParser / WsEndpointClient / WsFrameCodec） | **方案该处口径多算 1**；SDK 吸收组实为 ws×4 + CardElements + LarkOpenApiTransportUrl + LarkEventParse + CardContent = **8 文件** |

单文件含多测试类（迁移时按**类**拆分，勿按文件 1:1）：

- `progress/ProgressTest.kt` → 3 个类：`ClaudeStreamParserTest` / `ProgressCardRendererTest` / `ProgressAccumulatorTest`
- `log/BridgeLogBusTest.kt` → 2 个类：`BridgeLogBusTest` / `BusBridgeLoggerTest`
- 文件名 ≠ 类名：`quickcommand/QuickCommandRegistryTest.kt` → 类 `SkillRegistryTest`；`executors/QuickCommandExecutorTest.kt` → 类 `SkillExecutorTest`；`executors/QuickCommandExecutorAccessControlTest.kt` → 类 `SkillExecutorAccessControlTest`

### 2.2 三态统计

| 三态 | 文件数（合计 60） | 方法数（合计 550） | 本文件 BC 编号区间 |
|---|---|---|---|
| **移植** | 42（36 lib + 6 UI） | 441（397 lib + 44 UI） | 散布，见 §3 各行 |
| **SDK吸收** | 8（全在 lib） | 42 | BC-423 .. BC-464 |
| **废弃** | 10（6 lib + 4 UI） | 67（44 lib + 23 UI） | BC-121..135、BC-279..288、BC-465..483、BC-528..550 |

文件级三态与方案「测试策略」映射原则 5 条**完全一致**（原则③移植组 / 原则①SDK吸收组 / 原则④⑤废弃与改写组 / UI 4 废弃 + 6 移植）。

### 2.3 方法级三态的口径重叠（必须明示，否则统计对不上）

报告的文件级三态与方法级三态存在 **2 处口径重叠**，本文件按**方法级**为准编号。

**可机器核验的实测行计数**（`grep '^| BC-[0-9]\{3\} |'` 取第 5 列后按前缀归类，全 550 行零遗漏）：

| 行标签前缀 | 实测行数 |
|---|---|
| `移植…`（含 `移植（**改断言**）` / `移植（**去静态**）` / `移植（**改注入**）` / `移植（**改数值**）` / `移植（语义）` 等变体，**含 1 行双态**） | **442** |
| `SDK吸收…`（含 `SDK吸收**（cli 分支随 D15 退役）`） | **42** |
| `废弃…`（含 `废弃**（cli 传输退役）` / `废弃**（语义移入新 lifecycle）`） | **66** |
| 合计 | **550** |

**与 §2.2 的对账**：上表 442 行中有 **1 行是双态标签** `移植（语义）/ 废弃（旧类载体）`（见下文第 2 条）。把它只计入**废弃**侧，即得 §2.2 的 **移植 441 / SDK吸收 42 / 废弃 67 = 550**，与报告口径**精确吻合，零偏差**。两处重叠如下：

1. `LarkBridgeConfirmLifecycleTest.kt`（10 方法）文件级计为**废弃**（10 方法全进 44 个 lib 废弃方法数），但其中 `确认超时秒数低于下限时被钳制`（BC-476）1 个方法是**实测定稿纯函数**，标为**移植**（落 M4-T8 `ConfirmTimeoutClampTest`）。
2. `model/BridgeConfigTest.kt`（6 方法）文件级计为**移植**，但 6 个方法全部是「旧 `BridgeConfig` 数据类默认值断言」，语义上属**移植（语义）/ 废弃（旧类载体）**双态——默认值契约移到新 `BridgeConfigDefaultsTest`（M4-T5），旧 data class 本身随 M2 消失。

### 2.4 M0 基线保障状态（风险登记，非本任务可裁定）

`git tag --list` 实测共 8 个：`v0.0.3 / v0.0.4 / v0.0.5 / v0.0.9 / v0.0.10 / v0.0.11 / v0.1.0 / v0.1.1`，**`pre-lark-rewrite` 快照 tag 尚未创建**（`git rev-parse -q --verify refs/tags/pre-lark-rewrite` 返回不存在），HEAD=`bd03d58` 无对应 tag。当前本文件的基线可追溯性仅依赖「HEAD + 工作区内尚未删除的旧模块源码」。⇒ **M2 拆除旧模块之前必须先落 tag**，否则本文件 §3 中所有「禁止改写，逐字平移」条目将失去可核对的原始载体。本任务（T1）被明确禁止执行任何 git 写操作，故此项标为 `TBD(用户裁决中)`，见 §9 P1。

---

## 3. 主契约表

> **三态定义**（严格按方案「测试策略」5 条映射原则）：
> - **移植** = 行为在新架构仍成立，1:1 或近 1:1 平移到新测试类；
> - **SDK吸收** = 能力交给官方 `oapi-sdk:2.8.5`，旧测试废弃，但其中的**字面约束**改写为契约映射测试（`LarkPolicyMapperTest` / `LarkInboundMapperTest`，M3-T3）；
> - **废弃** = 旧传输层（`LarkCliTransport` / 自研 WS 栈）或旧配置模型（DataStore 35 key）特有，新架构不存在该概念，行为改由 `BridgeRuntimeIntegrationTest` / `CliConfigOverrideTest` / `LegacyConfigImporterTest` 承接。
>
> **凡关联 D# 的条目不许原样照抄旧断言**——「锁住的行为」列已给出新架构下的断言形态。
> **「禁止改写，逐字平移」标记**（下文简称 🔒）表示该条目对应方案点名的实测定稿代码，期望字面值见 §4，任何字符级差异都算回归。

### 3.1 lib `agent/`（45 方法 · 全部移植 · `AgentBackend.kt` 341 行🔒）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-001 | agent/AgentBackendTest.kt | claude 预设首轮建会话 | 🔒 argv==`["-p","<prompt>","--append-system-prompt","<sp>","--allowedTools","Read,Grep","--session-id","<uuid>"]`，见 §4.1 | 移植 | R3 | `AgentBackendArgvTest` · M4-T8 |
| BC-002 | | claude 预设续轮恢复会话 | 🔒 续轮 argv==`["-p","<prompt>","--resume","<uuid>"]`（**不再带** `--append-system-prompt` / `--allowedTools`） | 移植 | R3 | `AgentBackendArgvTest` · M4-T8 |
| BC-003 | | qoder 预设两段式与工具逗号拆分 | 🔒 首轮 `["-p",p,"--system-prompt",sp,"--tools","Read","Grep","--session-id",s]`；续轮 `["-p",p,"-r",s]`；`--tools` 值按逗号**拆成多个 argv 元素** | 移植 | R3 | `AgentBackendArgvTest` · M4-T8 |
| BC-004 | | custom 模板首轮移除 session 续轮替换 | 🔒 `SESSION_TOKEN="{session}"`：首轮整体**移除**该 token，续轮**替换**为真实 sessionId | 移植 | R3 | `AgentBackendArgvTest` · M4-T8 |
| BC-005 | | custom 模板不含 session 时降级为无会话 | 模板无 `{session}` 占位 → 不注入任何会话参数（不报错） | 移植 | — | `AgentBackendArgvTest` · M4-T8 |
| BC-006 | | custom 模板缺 prompt 占位符校验失败 | `PROMPT_TOKEN="{prompt}"` 缺失 → **配置校验失败**，不静默跑 | 移植 | — | `ConfigValidatorTest` · M4-T5 |
| BC-007 | | prompt 含特殊字符时作为单一 argv 元素 | 含空格/引号/换行的 prompt 是**单个** argv 元素，不被 shell 拆分（argv 语义而非 shell 语义） | 移植 | R3 | `AgentBackendArgvTest` · M4-T8 |
| BC-008 | | 工厂按 backend 选择实现 | 🔒 backend 字符串 `claude`/`qoder`/`copilot`/`custom` → 对应 Backend 实现类 | 移植 | — | `AgentBackendArgvTest` · M4-T8 |
| BC-009 | | copilot 预设首轮续轮均用 session-id 且带 silent | 🔒 argv 含 `-s`（silent，源码 :206）+ `--session-id <uuid>`，**首/续轮均带**（与 claude 两段式不同） | 移植 | R3 | `AgentBackendArgvTest` · M4-T8 |
| BC-010 | | copilot 预设工具权限三种映射 | 🔒 空工具串→无权限参；`*` 或 `all`→`--allow-all-tools`；否则→`--allow-tool` + 逗号 join（源码 :207-218） | 移植 | R3 | `AgentBackendArgvTest` · M4-T8 |
| BC-011 | | copilot 预设无会话时省略 session 参数 | 无会话 → argv 不含 `--session-id` | 移植 | — | `AgentBackendArgvTest` · M4-T8 |
| BC-012 | | 可用性检测 PATH 中存在的可执行文件 | PATH 命中 → `available==true` | 移植 | — | `AgentBackendAvailabilityTest` · M4-T8 |
| BC-013 | | 可用性检测不存在的可执行文件 | 未命中 → `false` | 移植 | — | `AgentBackendAvailabilityTest` · M4-T8 |
| BC-014 | | 可用性检测 custom 后端检查模板首 token | custom 用模板**首 token** 做可执行名探测 | 移植 | — | `AgentBackendAvailabilityTest` · M4-T8 |
| BC-015 | | 可用性检测未知后端返回 false | 未知 backend 字符串 → `false`（不抛） | 移植 | — | `AgentBackendAvailabilityTest` · M4-T8 |
| BC-016 | | findInPath 返回绝对路径且可被 ProcessBuilder 使用 | 解析结果为**绝对路径**，可直接喂 ProcessBuilder | 移植 | — | `AgentBackendAvailabilityTest` · M4-T8 |
| BC-017 | | findInPath 在 PATH 缺失时走 fallback 目录兜底 | PATH 无命中 → 回退 `~/.local/bin` 等 fallback 目录 | 移植 | R3 | `AgentBackendAvailabilityTest` · M4-T8 |
| BC-018 | | resolveExecutable 预设后端返回可执行文件 | 预设后端 → 返回 `File` 对象 | 移植 | — | `AgentBackendAvailabilityTest` · M4-T8 |
| BC-019 | agent/AgentBackendSafetyPromptTest.kt | workspace 档注入的安全段不含禁止执行任意 shell | 🔒 `presetAllowsShell(workspace)==true` → 安全段**不含** SHELL_BAN（源码 :306-312） | 移植 | R3 | `SafetyPromptLayeringTest` · M4-T8 |
| BC-020 | | all 档注入的安全段不含禁止执行任意 shell | 🔒 `presetAllowsShell(all)==true` → 不含 SHELL_BAN | 移植 | R3 | `SafetyPromptLayeringTest` · M4-T8 |
| BC-021 | | custom 档注入的安全段不含禁止执行任意 shell | 🔒 `presetAllowsShell(custom)==true` → 不含 SHELL_BAN | 移植 | R3 | `SafetyPromptLayeringTest` · M4-T8 |
| BC-022 | | safe 档注入完整安全段含禁止执行任意 shell | 🔒 SHELL_BAN 字面值=`禁止执行任意 shell 命令（除非明确授权）` | 移植 | R3 | `SafetyPromptLayeringTest` · M4-T8 |
| BC-023 | | readonly 档注入完整安全段含禁止执行任意 shell | 🔒 readonly 含 SHELL_BAN | 移植 | R3 | `SafetyPromptLayeringTest` · M4-T8 |
| BC-024 | | prohibitDelete 关闭时不注入禁删两句但保留禁越界 | 🔒 `prohibitDelete=false` → 去掉 DELETE_BAN（`禁止执行任何删除文件/目录的操作（rm、rmdir、unlink、shutil.rmtree、git clean、Remove-Item、del 等）`）与 `禁止清空目录内容`，但**保留** PATH_BAN（`禁止操作工作区白名单外的任何路径`） | 移植 | R3 | `SafetyPromptLayeringTest` · M4-T8 |
| BC-025 | | 注入关闭且 systemPrompt 为空时 argv 不含 append-system-prompt | `safetyPromptEnabled=false` 且 sp 空 → argv **无** `--append-system-prompt` | 移植 | — | `SafetyPromptLayeringTest` · M4-T8 |
| BC-026 | | 注入关闭但 systemPrompt 非空时只透传用户 prompt | 仅传用户 sp 原值，**不加**安全段 | 移植 | — | `SafetyPromptLayeringTest` · M4-T8 |
| BC-027 | | 注入开启且 systemPrompt 为空时 argv 含安全段 | sp 空 + 开启 → argv 含**纯安全段** | 移植 | — | `SafetyPromptLayeringTest` · M4-T8 |
| BC-028 | | 注入开启时安全段追加在用户 prompt 之后 | 🔒 顺序 = 用户 prompt → `\n\n` → 段头 `[安全约束 - 不可违反]` → 各句 → 尾句 | 移植 | R3 | `SafetyPromptLayeringTest` · M4-T8 |
| BC-029 | | safetyPromptSuffix 按档位分层 | 🔒 `safetyPromptSuffix(preset, prohibitDelete)` 是**纯函数**（源码 :286-297），逐字平移 | 移植 | R3 | `SafetyPromptLayeringTest` · M4-T8 |
| BC-030 | | qoder 后端同样按档位分层注入 | qoder 走**同一**分层逻辑（不分后端特化） | 移植 | — | `SafetyPromptLayeringTest` · M4-T8 |
| BC-031 | agent/PermissionPresetTest.kt | readonly 档 claude 映射只读工具集 | 🔒 `CLAUDE_READONLY=="Read,Grep,Glob,LS,WebFetch,WebSearch"` | 移植 | R3 | `PermissionPresetTest` · M4-T8 |
| BC-032 | | readonly 档 qoder 映射小写只读集 | 🔒 `QODER_READONLY=="read,grep,glob,ls,webfetch,websearch"`（全小写） | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-033 | | readonly 档 copilot 映射只读集 | 🔒 `COPILOT_READONLY=="read,grep,search,fetch"`（注意 copilot 无 glob/ls） | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-034 | | workspace 档 claude 含读写与执行 | 🔒 `CLAUDE_WORKSPACE=="Read,Grep,Glob,LS,WebFetch,WebSearch,Edit,Write,NotebookEdit,Bash"` | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-035 | | workspace 档为显式档位而非默认 | `PRESET_WORKSPACE != DEFAULT_PRESET` | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-036 | | all 档 claude 用通配 | 🔒 claude `all` → `"*"` | 移植 | R3 | `PermissionPresetTest` · M4-T8 |
| BC-037 | | all 档 copilot 用通配由后端转 allow-all-tools | 🔒 copilot `all` → `"*"` → CopilotBackend 渲染为 `--allow-all-tools` | 移植 | R3 | `PermissionPresetTest` · M4-T8 |
| BC-038 | | custom 档透传 allowedTools 原值 | 🔒 `PRESET_CUSTOM` → `allowedTools.trim()` 原样透传 | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-039 | | custom 后端任何档位都返回空串 | 🔒 `backend==custom` → `resolveTools` 恒返回 `""`（**优先于**档位判定） | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-040 | | create 按 workspace 档渲染 claude 命令 | 端到端渲染校验（preset→tools→argv 全链路） | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-041 | | create 按 all 档渲染 copilot 命令为 allow-all-tools | 端到端 | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-042 | | create custom 后端不追加工具参数 | custom 后端 argv **不含**任何工具参数 | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-043 | agent/AgentSessionStoreTest.kt | 复用与重置 | 同 key 复用 sessionId、`reset` 清空。**新断言**：key 须为复合键 | 移植 | **D13** | `SessionStoreCompositeKeyTest` · M4-T8 |
| BC-044 | | LRU 逐出最久未访问的 chat | `capacity=2` 时逐出**最久未访问**键（非插入序） | 移植 | **D13** | `SessionStoreCompositeKeyTest` · M4-T8 |
| BC-045 | | put 覆盖同 chat 不增加容量 | 同键覆盖不增 `size` | 移植 | **D13** | `SessionStoreCompositeKeyTest` · M4-T8 |

**D13 新断言（BC-043..045 不许原样移植）**：旧 `AgentSessionStore` 仅以 `chatId` 为键；新架构会话键须升级为 `chatId + (threadId ?: rootId ?: "")`。三条移植后须改用**复合键**断言，并**新增**一条「同 `chatId` 不同 `threadId` 的会话上下文互不串味」用例（新编号由 M4-T8 实现者追加为 BC-045a）。

**§1.1 报告口径校正**：调研报告 §1.1 在 `PermissionPresetTest` 段落列了 13 行，实测该文件只有 **12** 个 `@Test`；第 13 行「（常量）`DEFAULT_PRESET==PRESET_SAFE`，未知档位回退 safe」是**注释性汇总而非测试方法**。本表按 12 行编号（BC-031..042），该常量约束移入 §4.2 作为实测定稿字面值，由 M4-T8 补一条显式断言。

### 3.2 lib `confirm/`（72 方法 · 全部移植 · `PermissionServerLauncher.kt` 247 行🔒）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-046 | confirm/ConfirmServiceTest.kt | 批准后等待方收到 APPROVED | 裁决回传 `APPROVED` | 移植 | — | `ConfirmServiceTest` · M4-T8 |
| BC-047 | | 拒绝后等待方收到 DENIED | `DENIED` | 移植 | — | `ConfirmServiceTest` · M4-T8 |
| BC-048 | | 超时返回 TIMEOUT 等同拒绝不抛异常 | 超时 → `TIMEOUT`，**不抛异常**，语义等同拒绝（fail-safe deny） | 移植 | R3 | `ConfirmServiceTest` · M4-T8 |
| BC-049 | | 非原发送者确认被拒（防替答） | 非原 `senderOpenId` 或异 `chatId` → `Forbidden` | 移植 | — | `ConfirmServiceTest` · M4-T8 |
| BC-050 | | 未知短标识返回 NotFound | 未知 shortId → `NotFound` | 移植 | — | `ConfirmServiceTest` · M4-T8 |
| BC-051 | | cancelAll 后等待方收到 CANCELLED | 全部在途请求 → `CANCELLED` | 移植 | — | `ConfirmServiceTest` · M4-T8 |
| BC-052 | | 短标识提取与匹配 | 🔒 `SHORT_ID_CHARS=4`，`shortId.length<=4` | 移植 | — | `ConfirmServiceTest` · M4-T8 |
| BC-053 | | 入参摘要截断 | 🔒 `INPUT_SUMMARY_CHARS=120`，`inputSummary.length<=120` | 移植 | — | `ConfirmServiceTest` · M4-T8 |
| BC-054 | confirm/ConfirmBusTest.kt | 总线启动返回回环端口 | `start()` 后 `port>0` 且绑定 loopback | 移植 | — | `ConfirmBusTest` · M4-T8 |
| BC-055 | | 请求经总线转发裁决回传 | 裁决经总线完整往返 | 移植 | — | `ConfirmBusTest` · M4-T8 |
| BC-056 | | 拒绝裁决回传 false | deny → `false` | 移植 | — | `ConfirmBusTest` · M4-T8 |
| BC-057 | | close 后在途确认取消且端口释放 | `close()` → 在途 `CANCELLED` + 端口释放，且 **CAS 幂等**（重复 close 不抛） | 移植 | R3 | `ConfirmBusTest` · M4-T8 |
| BC-058 | | MCP server initialize 与 tools-list 响应 | 🔒 `initialize` 输出 `jsonrpc=="2.0"`、`PROTOCOL_VERSION=="2024-11-05"`、`SERVER_NAME=="easy-dev-toolbox-permission"` | 移植 | R3 | `McpPermissionServerTest` · M4-T8 |
| BC-059 | | MCP server 总线不可达时 fail-safe deny | 🔒 `busPort=1` 不可达 → 退出码 `EXIT_DENY==3` + `behavior=="deny"` | 移植 | R3 | `McpPermissionServerTest` · M4-T8 |
| BC-060 | confirm/McpPermissionServerRiskGateTest.kt | FORBIDDEN 命令直接 deny 且 reason 标明风险策略而非用户拒绝 | 🔒 `reason` **contains** `forbidden by risk policy`（完整形态 `forbidden by risk policy (<rule>)`），`pendingCount==0` | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-061 | | FORBIDDEN 命令即使用户想放行也不产生在途确认请求 | **不入总线**，`pendingCount==0`（风险策略优先于用户意愿） | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-062 | | LOW 只读工具免确认直接 allow | 🔒 退出码 0 + `behavior=="allow"` + `reason` **contains** `auto-approved`（完整形态 `auto-approved: low risk (<rule>)`） | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-063 | | LOW 只读工具不产生在途确认请求 | `pendingCount==0` | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-064 | | MEDIUM 写入类工具仍送总线裁决 | 入总线；`busPort=-1` 时 🔒 `reason=="denied by user"`（**精确相等**） | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-065 | | HIGH 删除类工具仍送总线裁决 | 入总线（HIGH 不自动 deny，仍走人工裁决） | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-066 | | MEDIUM 经真实总线产生在途请求并可被裁决放行 | 🔒 放行后 `reason=="approved"`（**精确相等**） | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-067 | | policy 注入后越界写入升级为 HIGH 并送总线 | 越界 `Write` → `HIGH` | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-068 | | policy 注入后白名单内写入仍为 MEDIUM 且不因越界规则误升 | 白名单内 `Write` → `MEDIUM` | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-069 | | policy 为 null 时不做越界校验但其余分级规则仍生效 | 无 policy → 跳过越界判定，其余规则照常 | 移植 | — | `McpRiskGateTest` · M4-T8 |
| BC-070 | confirm/RiskClassifierTest.kt | rm -rf 根目录识别为 FORBIDDEN | 🔒 `rm -rf /` → `RiskLevel.FORBIDDEN` | 移植 | R3 | `RiskClassifierTest` · M4-T8 |
| BC-071 | | rm -rf 家目录识别为 FORBIDDEN | 🔒 `rm -rf ~` → `FORBIDDEN` | 移植 | R3 | `RiskClassifierTest` · M4-T8 |
| BC-072 | | rm -rf 根目录的 JSON 包裹形式仍识别为 FORBIDDEN | JSON 包裹（工具入参形态）仍 `FORBIDDEN` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-073 | | rm -rf 根目录带尾随参数仍识别为 FORBIDDEN | 尾随参数 / `;` / `&&` / 尾空白 → 仍 `FORBIDDEN` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-074 | | rm -rf 工作区子目录不因去锚定而误升 FORBIDDEN | 子目录 `rm -rf` → `HIGH`（**不误升** FORBIDDEN） | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-075 | | 磁盘格式化命令识别为 HIGH | 🔒 `format C:` / `/dev/sda` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-076 | | 含 format 的正常文本不被误判为删除类 HIGH | `format json` / `String.format` **不误判** | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-077 | | rm 命令识别为 HIGH | `rm` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-078 | | rmdir 识别为 HIGH | `rmdir` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-079 | | unlink 识别为 HIGH | `unlink` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-080 | | git clean 识别为 HIGH | `git clean` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-081 | | shutil rmtree 识别为 HIGH | `shutil.rmtree` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-082 | | Delete 工具名识别为 HIGH | 工具名 `Delete` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-083 | | DeleteFile 工具名识别为 HIGH | 工具名 `DeleteFile` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-084 | | 越界路径写入识别为 HIGH | 🔒 越界 Write → `HIGH`，rule 含 `outside workspace` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-085 | | 白名单内路径写入不触发越界 HIGH | 白名单内 Write → `MEDIUM` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-086 | | Edit 工具识别为 MEDIUM | `Edit` → `MEDIUM` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-087 | | Bash 普通命令识别为 MEDIUM | `Bash ls` → `MEDIUM` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-088 | | Read 工具识别为 LOW | `Read` → `LOW` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-089 | | Grep 工具识别为 LOW | `Grep` → `LOW` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-090 | | Glob 工具识别为 LOW | `Glob` → `LOW` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-091 | | find -delete 识别为 HIGH | `find ... -delete` → `HIGH` | 移植 | — | `RiskClassifierTest` · M4-T8 |
| BC-092 | confirm/PermissionServerLauncherTest.kt | prepare 生成的脚本内含 classpath 与主类与端口 | 🔒 脚本含 `MAIN_CLASS=="com.jiaoay.lark.bridge.MainKt"` + 子命令 `permission-server` + `--bus-port 4321` + `resolveClasspath()` 烘焙结果，见 §4.5 | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-093 | | 命令模板 token 不含 classpath 因此不会撞命令行长度上限 | 🔒 `commandToken()` 结果长度 <512 且**不含** `pathSeparator` / `-cp`（classpath 只在脚本内） | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-094 | | prepare 幂等不累积临时文件 | 多次 `prepare` 不堆积临时脚本 | 移植 | — | `ProgramLauncherTest` · M4-T6 |
| BC-095 | | commandToken 对含空格路径整体加引号 | 🔒 `commandToken(path)`：含空白 → `"\"$path\""`，否则原样 | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-096 | | sh 单引号转义可安全承载含单引号的路径 | 🔒 `sh(v) == "'" + v.replace("'", "'\\''") + "'"`，即 `/tmp/it's` → `'/tmp/it'\''s'` | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-097 | | Windows 平台 bat 脚本经 cmd exe 包装拉起 | 🔒 `launchCommand(p, windows=true)==["cmd.exe","/d","/c",p]`（**是 `/c` 不是 `c`**，见 §6 校正） | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-098 | | Unix 平台 sh 前缀拉起保持不变 | 🔒 `launchCommand(p, windows=false)==["/bin/sh",p]` | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-099 | | prepare 生成的脚本内容为纯 ASCII | 脚本所有字符 `code<128`（避免跨平台编码歧义） | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-100 | | scriptCharset 平台语义正确 | 🔒 `scriptCharset(true)==native.encoding`（Windows ANSI）、`scriptCharset(false)==UTF-8` | 移植 | R11 | `ProgramLauncherTest` · M4-T6 |
| BC-101 | | resolveClasspath 非空且含本类 codeSource | 🔒 classpath = `java.class.path` ∪ 本类 codeSource，`LinkedHashSet` 去重保序 | 移植 | — | `ProgramLauncherTest` · M4-T6 |
| BC-102 | | resolveJavaBin 指向存在的可执行文件或回退 PATH | 🔒 `$JAVA_HOME/bin/java[.exe]`，缺失回退字面量 `"java"` | 移植 | — | `ProgramLauncherTest` · M4-T6 |
| BC-103 | | selfCheck 对正常退出脚本返回 Ok | 退出码 0 → `Ok`。**新断言**：默认 `async`，不阻塞启动 | 移植 | **D14** | `ProgramLauncherSelfCheckTest` · M4-T6 |
| BC-104 | | selfCheck 对非零退出脚本返回 Failed 且带退出码与输出摘要 | 🔒 Failed 文案含 `自检退出码 1` + 输出尾部（`TAIL_CHARS=400`）；实测样本含 `ClassNotFoundException` | 移植 | **D14** | `ProgramLauncherSelfCheckTest` · M4-T6 |
| BC-105 | | selfCheck 对不存在脚本返回 Failed 而非抛异常 | 🔒 Failed 文案 `启动脚本不存在：<path>`，**不抛** | 移植 | **D14** | `ProgramLauncherSelfCheckTest` · M4-T6 |
| BC-106 | | prepare 生成的脚本可自检拉起 | 端到端 self-check（生成→拉起→退出码 0） | 移植 | — | `ProgramLauncherSelfCheckTest` · M4-T6 |
| BC-107 | | deleteScript 删除脚本且对 null 与缺失文件安全 | 清理**幂等安全**（null / 已删 / 不存在均不抛） | 移植 | — | `ProgramLauncherTest` · M4-T6 |
| BC-108 | confirm/ConfirmChainIntegrationTest.kt | mcpConfig provider 产出 mcp-config JSON 与 mcp 工具引用 | 🔒 `PERMISSION_TOOL_REF=="mcp__easydev-perm__permission_prompt"`；`--mcp-config` JSON 含 `--chat oc_test` / `--sender ou_user`，见 §4.4 | 移植 | R3 | `McpConfigWiringTest` · M4-T8 |
| BC-109 | | mcpConfig 缺失时回退旧静态命令路径（兼容） | 无 provider → 走静态命令路径（`permissionPromptToolCommand`） | 移植 | — | `McpConfigWiringTest` · M4-T8 |
| BC-110 | | mcpConfig 与静态命令同时存在时 mcpConfig 优先且不重复引用 | 并存 → mcpConfig **胜**，`--permission-prompt-tool` 只出现 **1 次** | 移植 | R3 | `McpConfigWiringTest` · M4-T8 |
| BC-111 | | provider 返回 null 时不追加 --permission-prompt-tool | provider 返回 null → argv **无**该 flag | 移植 | — | `McpConfigWiringTest` · M4-T8 |
| BC-112 | | ConfirmBus 超时由构造参数驱动而非硬编码 | 🔒 `timeoutMillis=500` 驱动（实测 elapsed<2000，证明未被硬编码覆盖） | 移植 | R3 | `ConfirmBusTest` · M4-T8 |
| BC-113 | | 确认请求产生时 notifier 下发卡片 | 卡片文案含 `Bash` / `git push` / `确认`。**新形态**：确认卡必须是**独立非流式卡** | 移植 | R3 | `McpConfigWiringTest` · M4-T8 |
| BC-114 | | MCP server 优先用构造参数的 chatId-senderOpenId | 构造参数优先于环境变量 | 移植 | **D16** | `McpConfigWiringTest` · M4-T8 |
| BC-115 | | safe 档 claude 不含 Bash | 🔒 `CLAUDE_SAFE=="Read,Grep,Glob,LS,Edit,Write,NotebookEdit,WebFetch,WebSearch"`——含 Read/Edit/Write，**不含 Bash** | 移植 | R3 | `PermissionPresetTest` · M4-T8 |
| BC-116 | | safe 档 qoder 不含 shell | 🔒 `QODER_SAFE=="read,grep,glob,ls,webfetch,websearch,edit,write"`——**无 shell** | 移植 | — | `PermissionPresetTest` · M4-T8 |
| BC-117 | | safe 档为新默认 | 🔒 `DEFAULT_PRESET==PRESET_SAFE=="safe"` 且 `AgentConfig().permissionPreset=="safe"` | 移植 | — | `BridgeConfigDefaultsTest` · M4-T5 |

**D14 新断言（BC-103..105 不许原样移植）**：旧 `selfCheck` 是**同步冷启动**（拖 1-3s，D14 缺陷）；新架构 `selfCheck="async"` 为默认值，长连接建立成功后在后台探测，探测期间网关状态置 `Probing`。三条移植后须断言**默认异步**且探测期状态为 `Probing`，而非阻塞启动。

**D16 关联（BC-114）**：旧 `ConfirmChainIntegrationTest` 的 mcpConfig provider 经**静态全局**注入；新架构一律改**构造注入**，断言须从「设置全局 provider 后生效」改为「构造参数注入的 provider 生效」，并追加「无全局可变状态可被第二个实例覆盖」的反向断言。

### 3.3 lib `cli/`（18 方法 · 3 移植 + 15 废弃）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-118 | cli/PermissionServerCommandTest.kt | --bus-port 选项被识别（与 LarkBridge 命令模板一致） | clikt 识别 `--bus-port`，**须与 §4.5 脚本内烘焙的子命令参数串一致** | 移植 | R3 | `PermissionServerCommandTest` · M5-T10 |
| BC-119 | | --port 向后兼容别名仍被识别 | `--port` 别名兼容（同一 option 的两个名字） | 移植 | — | `PermissionServerCommandTest` · M5-T10 |
| BC-120 | | 未知选项被拒绝（验证解析确在校验选项名） | 未知 flag → 非零退出 + 报错（**不是静默忽略**） | 移植 | — | `PermissionServerCommandTest` · M5-T10 |
| BC-121 | cli/CommonOptionsBridgeConfigTest.kt | prohibitDelete 默认开启 | 旧：默认 `true` + `--no-` 次级名 | **废弃** | D8 | 默认值语义 → `BridgeConfigDefaultsTest` · M4-T5 |
| BC-122 | | --no-agent-prohibit-delete 可关闭禁删 | 旧：逐字段 CLI 旗标映射 | **废弃** | D8 | `CliConfigOverrideTest` · M5-T10 |
| BC-123 | | --agent-prohibit-delete 显式开启 | 旧：逐字段 | **废弃** | D8 | `CliConfigOverrideTest` · M5-T10 |
| BC-124 | | --no-agent-safety-prompt 可关闭安全提示注入 | 旧：逐字段 | **废弃** | D8 | `CliConfigOverrideTest` · M5-T10 |
| BC-125 | | safetyPromptEnabled 默认开启 | 旧：默认 `true` | **废弃** | D8 | 默认值语义 → `BridgeConfigDefaultsTest` · M4-T5 |
| BC-126 | | custom 档无 allowedTools 时 fail-fast 报错 | 旧 message 含 `--agent-allowed-tools`。**语义须保留**：custom 档空工具 → CLI 侧 fail-fast 报错 | **废弃** | — | 校验语义 → `ConfigValidatorTest` · M4-T5 |
| BC-127 | | custom 档带 allowedTools 时透传且工具集不被放大 | 旧：逐字段透传。**语义须保留**：透传原值、不放大 | **废弃** | R3 | `PermissionPresetTest` · M4-T8 |
| BC-128 | | 模型层对 custom 档空 allowedTools 降级 safe 而非完全开放 | 🔒 **fail-safe 降级**（M10 修复）：`resolveTools(preset=custom, allowedTools 空白)` → `safeTools(backend)`，**不是 `*`** | **废弃** | R3 | 语义 → `ConfigValidatorTest` · M4-T5 + `PermissionPresetTest` · M4-T8 |
| BC-129 | | agent 未启用时 custom 档不 fail-fast | `agentEnabled=false` → 不校验工具串 | **废弃** | — | `ConfigValidatorTest` · M4-T5 |
| BC-130 | | --skill 解析出完整技能配置 | 旧解析出 id/aliases/name/promptTemplate/requiresConfirm | **废弃** | D8 | route 语义 → `BridgeConfigRoundTripTest` · M4-T5 |
| BC-131 | | --skill-confirm 标记技能需运行时确认 | 旧：逐字段 | **废弃** | D8 | `BridgeConfigRoundTripTest` · M4-T5 |
| BC-132 | | --skill 模板中的等号被保留（limit=4 拆分） | 🔒 `split(":", limit=4)` 保证 promptTemplate 内的 `=` / 额外冒号不被截断 | **废弃** | — | 语义已由 JSON 文档天然消解，**无需承接** |
| BC-133 | | 非法 --skill 项被宽容忽略 | 旧：非法项跳过不报错 | **废弃** | — | 新架构改**显式校验报错**（JSON 文档不允许宽容吞），语义反转 |
| BC-134 | | --sender-stats-dir 透传 | 旧 CLI 旗标；新配置文档 `observability` 段**无此字段**（路径固定） | **废弃** | D8 | 见 §9 待裁定点 P8 |
| BC-135 | | CLI 与宿主直接构造对同一配置产出等价 BridgeConfig | 旧：两套装配人工对齐。**语义须保留**：唯一装配点 | **废弃** | **D16** | → `BridgeAssemblerParityTest` · M4-T9 |

**废弃理由（映射原则④）**：新架构 CLI 不再逐字段映射到旧 `BridgeConfig`（方案已声明删除约 30 个业务字段旗标），改为 `CliConfigOverrideTest` 断言「CLI 只对单一 JSON 配置文档做覆盖」。其中 **4 条语义必须保留迁移**：① BC-126 custom 档空 allowedTools 的 fail-fast → `ConfigValidatorTest`；② BC-128 模型层 fail-safe 降级 safe → `PermissionPresetTest`；③ BC-127 透传不放大 → `PermissionPresetTest`；④ BC-135 CLI 与宿主装配等价 → `BridgeAssemblerParityTest`（消解 D16 两套装配）。BC-133 的「宽容忽略」语义在新架构**刻意反转**为显式报错，属有意变更，不算回归。

### 3.4 lib `engine/`（26 方法 · 全部移植 · 映射原则② 重组为 EventIntakeTest / AdmissionTest / TimeoutTest）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-136 | engine/BridgeEngineTest.kt | 事件分发到匹配执行器并回复 | intake → dispatch → reply 全链路贯通 | 移植 | — | `EventIntakeTest` · M4-T7 |
| BC-137 | | 重复 event_id 被去重 | 旧断言 `executeCount==1`（键=eventId）。**新断言**：键改 `messageId`，且新增「同 messageId 不同 eventId 仍去重」 | 移植（**改断言**） | **D12** | `MessageDeduperTest` · M4-T7 |
| BC-138 | | 无匹配执行器时按 unhandledReply 配置决定回复 | `SKIPPED` + 配置文本 | 移植 | — | `PolicyGateTest` · M4-T7 |
| BC-139 | | 无匹配执行器且 unhandledReply 关闭时静默 | 静默（不发消息） | 移植 | — | `PolicyGateTest` · M4-T7 |
| BC-140 | | 执行器异常不中断服务且回复失败 | `FAILURE` + errorCode，**服务续跑**（异常不冒泡到 intake） | 移植 | R3 | `EventIntakeTest` · M4-T7 |
| BC-141 | | TaskDispatcher 按注册顺序匹配第一个 | 旧：首个 `match` 胜。**新断言**：顺序来自配置文档 `routes` 数组 | 移植（**改断言**） | **D16** | `RouteOrderTest` · M4-T7 |
| BC-142 | engine/BridgeEngineCountersTest.kt | 两个成功一个失败后计数为 2 与 1 | `completedCount==2` / `failedCount==1` | 移植 | — | `MetricsCountersTest` · M4-T7 |
| BC-143 | | skipped 结果不计入计数 | `SKIPPED` **不计**入 completed/failed。**新增**：所有 DROP_OLDEST 丢弃点须有独立计数器 | 移植 | **D18** | `MetricsCountersTest` + `JsonlSinkDropCountTest` · M4-T8 |
| BC-144 | engine/BridgeEngineQueueTest.kt | 排队等待超时收到任务较多回复 | 🔒 `errorCode=="task_queue_timeout"`。**新断言**：按 Admission 三档 | 移植 | **D11** | `AdmissionTest` · M4-T7 |
| BC-145 | | urgent 事件在许可占满时仍执行 | 旧 `isUrgent=true` 绕过全局许可。**新断言**：URGENT 通道独立准入，不被 HEAVY 占满阻塞 | 移植 | **D11** | `AdmissionTest` · M4-T7 |
| BC-146 | | stop 后 restart 事件仍被处理 | 重启后续处理（生命周期幂等） | 移植 | **D20** | `BridgeLifecycleTest` · M4-T9 |
| BC-147 | engine/BridgeEngineTimeoutTest.kt | 执行器声明独立超时时按其值超时 | 🔒 `timeoutSeconds=1` 覆盖全局 60 → `errorCode=="task_timeout"`，summary 含 `任务超时（>1s）` | 移植 | — | `TimeoutTest` · M4-T7 |
| BC-148 | | 执行器未声明超时时回退全局值 | 回退全局 `taskTimeoutSeconds` | 移植 | — | `TimeoutTest` · M4-T7 |
| BC-149 | engine/BridgeEngineUnhandledReplyTest.kt | unhandledReplyText 非空时无匹配执行器回复配置文本 | `SKIPPED` + 该文本原值 | 移植 | — | `PolicyGateTest` · M4-T7 |
| BC-150 | | unhandledReplyText 为空且开关关闭时静默 | 静默 | 移植 | — | `PolicyGateTest` · M4-T7 |
| BC-151 | | unhandledReplyText 为空且开关开启时回退开关语义 | 🔒 summary 含 `not a handled request` | 移植 | — | `PolicyGateTest` · M4-T7 |
| BC-152 | engine/SkillRoutingDryRunTest.kt | 装配链顺序为 ping card-action static-reply skill file-message host-agent | 旧硬编码 `["ping", CardActionExecutor.EXECUTOR_ID, "static-reply", QuickCommandExecutor.EXECUTOR_ID, "file_message", AgentExecutor.EXECUTOR_ID]`。**新断言**：改为「构造两条同触发词 route，数组前者胜」 | 移植（**改断言**） | **D16** | `RouteOrderTest`（实测承载：`pipeline/RoutingTest.kt:52`「两条同触发词 route ⇒ 数组前者胜」/`:100`「enabled=false 跳过且不占位次（BC-152）」）+ `BridgeAssemblerParityTest` · M4-T7/T9 |
| BC-153 | | dry-run 消息一 skills 清单由技能执行器处理且 kind 为 SKILL | `/commands` → `SKILL`，回复含 `日志分析` / `/log` / `内置`，**不跑 agent** | 移植 | — | `SkillRoutingTest` · M4-T8 |
| BC-154 | | dry-run 消息二 log 别名走内置技能委托 agent 且 kind 为 SKILL | 🔒 `/log` 别名 → prompt `startsWith("/log-helper ")` | 移植 | — | `SkillRoutingTest` · M4-T8 |
| BC-155 | | dry-run log-helper 别名透传不重复后缀 | 🔒 `/log-helper <参数>` → args **不重复**加 `/log-helper` 前缀 | 移植 | — | `SkillRoutingTest` · M4-T8 |
| BC-156 | | dry-run logx 不命中任何快捷指令（边界校验） | 🔒 `/logx` **不命中**（别名边界校验，不被 `/log` 拆分误命中） | 移植 | R1 | `SkillRoutingTest` · M4-T8 |
| BC-157 | | dry-run 消息三 裸 ai 提问走 AgentExecutor 且 kind 为 AGENT | 裸 `ai <问题>` → `AGENT`，prompt == 去前缀后的原文 | 移植 | — | `SkillRoutingTest` · M4-T8 |
| BC-158 | | dry-run 消息四 自然语言通道命中内置技能且 kind 为 SKILL | 🔒 `ai 日志分析:` → `SKILL`（自然语言通道） | 移植 | — | `SkillRoutingTest` · M4-T8 |
| BC-159 | | dry-run 四条消息依次喂入同一装配链路由与 kind 均正确 | 顺序路由一致性（同一条链、无状态污染） | 移植 | — | `SkillRoutingTest` · M4-T8 |
| BC-160 | | dry-run 固定回复仍优先于技能执行器 | `COMMAND` 优先于 `SKILL` | 移植 | — | `RouteOrderTest` · M4-T7 |
| BC-161 | | dry-run 未授权发送者不命中技能也不命中 agent | 访问控制**前置**于路由 | 移植 | — | `AccessControlTest` · M4-T7 |

**D12 新断言（BC-137，关键）**：去重键从 `eventId` 改 `messageId`。双证据闭合——① SDK `NormalizeDedupKeys.message()` 实测返回 `"message:" + messageId`；② 飞书官方文档明确「消息事件重推时 `event_id` 可能不同，幂等请用 `message_id`」。移植后断言须为「相同 `messageId` 重推 → `executeCount==1`」，并新增「同 `messageId` 不同 `eventId` 仍去重」用例。SDK `SafetyConfig.dedupTtlMs` 作第二道网，**不自研第二套去重语义**（R9）。注意 `NormalizedMessage` **无 `eventId` 字段**（见 §5.8），旧断言形态在新架构无载体。

**D11 新断言（BC-144..145）**：旧为全局许可不分轻重；新架构两级准入 `URGENT` / `LIGHT` / `HEAVY`。两条移植后须按 Admission 三档断言，核心新增用例：`/help` 类 LIGHT 请求**不得排在**长跑 HEAVY agent 之后。

**D16 新断言（BC-141 / BC-152）**：装配链顺序不再硬编码在 `build()`，改为配置文档 `routes` 数组即优先级；顺序断言须改为「构造两条同触发词 route，数组前者胜」。

**D18 新断言（BC-143）**：`SKIPPED` 不计数保留，但所有 `DROP_OLDEST` 丢弃点须新增计数器指标断言（落 `JsonlSinkDropCountTest`，见 §7）。

### 3.5 lib `executors/`（127 方法 · 117 移植 + 10 废弃）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-162 | executors/AgentExecutorTest.kt | 前缀命中触发 agent 执行并回复 stdout | 前缀命中 → 拉子进程 → 回 stdout | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-163 | | 未命中前缀与关闭状态均不路由 | 不命中 / `enabled=false` → `match==false` | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-164 | | 同一 chat 第二次调用恢复同一会话 | 同 chat 复用 sessionId（`--resume`） | 移植 | **D13** | `AgentExecutorTest` · M4-T8 |
| BC-165 | | 新对话指令重置会话 | 🔒 reset 指令（默认 `新对话`）清会话 | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-166 | | 重置指令精确匹配不误伤前缀消息 | **精确 `equals`** 才重置（`新对话啊` 不触发） | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-167 | | 同一 chat 超过排队上限回繁忙 | 🔒 `errorCode=="agent_busy"`（旧 `AgentConfig.queueLimit=2`） | 移植 | **D11** | `AdmissionTest` · M4-T7 |
| BC-168 | | 超时终止子进程并回复失败 | 🔒 `errorCode=="agent_timeout"` + 进程被杀（`isAlive==false`）。**新断言更强**：见 §4.8 kill 升级链 | 移植 | R10 | `CancellationKillsProcessTest` · M4-T6 |
| BC-169 | | 后端不可用回复失败且不重试 | 🔒 `errorCode=="agent_backend_unavailable"`，**不重试** | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-170 | | 非零退出携带 stderr 尾部 | 🔒 `errorCode=="agent_exit_1"`（动态形态 `agent_exit_<code>`）+ stderr 尾 `STDERR_TAIL_CHARS=200` | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-171 | | 超长输出截断保留头尾 | 🔒 `MAX_OUTPUT_CHARS=4000`，`summary.length<4200` 且含 `超长截断`，**保头尾** | 移植 | R3 | `AgentExecutorTest` · M4-T8 |
| BC-172 | | dry-run 打印命令不拉起子进程 | dry-run **不 spawn**（进程工厂零调用） | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-173 | | custom 后端模板无效时回复配置失败 | 🔒 `errorCode=="agent_config_invalid"` | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-174 | | custom 后端按模板渲染执行 | 模板渲染（`{prompt}`/`{session}`） | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-175 | | 多前缀任一命中即触发 | 🔒 `"ai,/ai"` 任一命中即触发 | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-176 | | 多前缀最长匹配去前缀 | **最长前缀胜**（`/ai` 优于 `ai`） | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-177 | | 多前缀单值旧配置兼容 | 单值（无逗号）兼容 | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-178 | | 多前缀空白与空项容错 | 空白项忽略，不产生空前缀 | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-179 | | 前缀配置为空回退默认 ai | 🔒 空 → `DEFAULT_TRIGGER_PREFIX=="ai"` | 移植 | — | `BridgeConfigDefaultsTest` · M4-T5 |
| BC-180 | | 群聊不同用户会话相互隔离 | 群聊按 sender 隔离会话 | 移植 | **D13** | `AgentExecutorTest` · M4-T8 |
| BC-181 | | 群聊新对话只重置发送者自己的会话 | reset **仅影响自己**的会话 | 移植 | **D13** | `AgentExecutorTest` · M4-T8 |
| BC-182 | | P2P 会话键仍为 chatId 不按人隔离 | 旧：P2P 键 == `chatId`。**新断言**：键 == `chatId + (threadId ?: rootId ?: "")` | 移植（**改断言**） | **D13** | `SessionStoreCompositeKeyTest` · M4-T8 |
| BC-183 | | 群聊同 chat 仍串行不因会话隔离放开并发 | 同 chat **串行**（会话隔离 ≠ 并发放开） | 移植 | **D11** | `AdmissionTest` · M4-T7 |
| BC-184 | | 触发词与agent前缀重叠时固定回复优先 | `COMMAND` 优先（路由顺序契约） | 移植 | — | `RouteOrderTest` · M4-T7 |
| BC-185 | | 未命中固定回复时agent正常触发 | fall-through 到 agent | 移植 | — | `RouteOrderTest` · M4-T7 |
| BC-186 | | 白名单命中放行 | allowlist 命中 → `AccessDecision.ALLOWED` | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-187 | | 白名单未命中静默拒绝不拉起进程 | `NOT_IN_WHITELIST` → 拒，**不 spawn** | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-188 | | 白名单为空拒绝所有发送者 | 🔒 空名单 → **拒全部**（fail-closed，非 fail-open） | 移植 | R3 | `AccessControlTest` · M4-T7 |
| BC-189 | | 白名单空白项容错 | 空白项忽略 | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-190 | | 黑名单模式命中拒绝未命中放行 | 🔒 `ACCESS_MODE_BLACKLIST=="blacklist"` 语义反转 | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-191 | | 黑名单模式空黑名单放行所有人 | 🔒 空黑名单 → **放行全部**（与空白名单相反，刻意非对称） | 移植 | R3 | `AccessControlTest` · M4-T7 |
| BC-192 | | 未知访问控制模式回退白名单 | 🔒 fail-safe：未知 mode → `ACCESS_MODE_WHITELIST=="whitelist"` | 移植 | R3 | `AccessControlTest` · M4-T7 |
| BC-193 | | 拒绝回复模板渲染 sender 占位符并经静态方法取用 | 🔒 `{sender}` → `未授权：ou_stranger，请联系管理员`。**新断言**：去静态取用（旧 `consumeRejectionReply` 走 companion `rejectionHolder`） | 移植（**去静态**） | **D16** | `AccessControlTest` · M4-T7 |
| BC-194 | | 拒绝回复为空时静默不生成 | 空模板 → 静默 | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-195 | | 统计回调记录接受与拒绝事件 | `SenderStatsListener.onMessage` / `onRejected` 回调触发。**新断言**：回调链**全程无 `runBlocking`** | 移植 | **D5** | `SenderStatsSinkTest` · M4-T8 |
| BC-196 | | 工作区域未配置拒绝执行并提示 | 🔒 `errorCode=="agent_workspace_missing"` | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-197 | | agentWorkDir 为空回退工作区域根 | 空 → workspace 根 | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-198 | | agentWorkDir 位于工作区域内正常执行 | 域内 → 放行 | 移植 | — | `WorkspacePolicyTest` · M4-T6 |
| BC-199 | | agentWorkDir 越界拒绝启动 | 越界 → 拒（不 spawn） | 移植 | R3 | `WorkspacePolicyTest` · M4-T6 |
| BC-200 | | 停止指令无在途进程时回复提示不拉起进程 | 🔒 `停止`（默认值）精确匹配，无在途 → 提示文案，**不 spawn** | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-201 | | 停止指令终止在途进程并回复确认 | summary 含 `已终止` + 进程 `isAlive==false`。**新断言更强**：§4.8 kill 升级链 | 移植 | R10 | `CancellationKillsProcessTest` · M4-T6 |
| BC-202 | | 停止指令不重置会话 | **停止 ≠ 重置**（sessionId 保留） | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-203 | | 停止指令为空串时禁用不拦截 | 空串 → 该功能禁用，消息正常 fall-through | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-204 | | 停止指令带额外文本不误伤 | 精确匹配（`停止一下` 不触发） | 移植 | — | `AgentExecutorTest` · M4-T8 |
| BC-205 | | 停止指令旁路取消在途确认（B1 接线） | 停止 → `cancelAll()` 在途确认（URGENT 旁路语义） | 移植 | **D11** | `AdmissionTest` · M4-T7 |
| BC-206 | | 回填 mcpConfig 后 agent 命令含 mcp-config 与工具引用且带会话上下文 | 🔒 `--permission-prompt-tool` 值 == `mcp__easydev-perm__permission_prompt`。**新断言**：改构造注入 | 移植（**改注入**） | **D16** | `McpConfigWiringTest` · M4-T8 |
| BC-207 | | 未回填 provider 时命令不含 --permission-prompt-tool | 无 provider → argv 无该 flag | 移植 | — | `McpConfigWiringTest` · M4-T8 |
| BC-208 | | Windows 下 cmd shim 经 cmd exe 包装拉起 | 🔒 `spawnCommand(.cmd, windows=true)` → `["cmd.exe","/d","/c", exe.absolutePath, ...]` | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-209 | | Windows 下 bat 脚本同样包装 | 🔒 `.bat` / 大写 `.CMD` 同样包装 | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-210 | | Windows 下 exe 与 Unix 路径不包装 | 🔒 `.exe` / 非 Windows → **不包装** | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-211 | | executableFile 为 null 时回退裸可执行名不包装 | null → 裸名（`"claude"`），不包装 | 移植 | — | `ProgramLauncherTest` · M4-T6 |
| BC-212 | executors/QuickCommandExecutorTest.kt（类名 `SkillExecutorTest`） | 别名命中触发技能执行并委托 agent | 别名 → 委托 agent，`kind==SKILL` | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-213 | | promptTemplate 的 args 占位符正确替换 | 🔒 `{args}` 替换为用户输入 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-214 | | promptTemplate 为空时直接透传 args | 空模板 → 透传 args 原值 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-215 | | promptTemplate 多处 args 均被替换 | **多处** `{args}` 全部替换 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-216 | | skills 命令返回技能清单不跑 agent | 🔒 `/commands` → 清单，**不 spawn** | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-217 | | skills id 返回技能详情 | 🔒 `/commands <id>` → 详情 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-218 | | skills 不存在的 id 返回失败 | 未知 id → 失败 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-219 | | 无用户技能时 skills 清单展示内置技能并标注内置 | 🔒 内置 `log-analysis` 开箱可见且标注「内置」 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-220 | | 关闭内置指令后 commands 清单为空 | `enabled=false` → 空清单 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-221 | | 非文本消息不匹配 | 非 text 消息 → `match==false`。**新形态**：按 `rawContentType` 判定 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-222 | | 空消息不匹配 | 空 → `false` | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-223 | | 未命中别名不匹配 | 未命中 → `false` | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-224 | | agent 未启用时技能执行返回失败 | 🔒 `errorCode=="command_agent_disabled"` | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-225 | | disabled 技能不被匹配 | 禁用 → 不匹配 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-226 | | 技能结果 kind 为 SKILL | 🔒 `TaskKind.SKILL`（枚举含 SKILL，见 §4.12） | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-227 | | 多别名技能最长匹配优先 | **最长别名胜** | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-228 | | timeoutSeconds 取自 agentExecutor | 复用 agent 超时值 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-229 | | agentExecutor 为 null 时 timeoutSeconds 为 null | null → null（不硬编码兜底） | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-230 | | 自然语言通道命中技能并委托 agent 产出 SKILL | 🔒 `ai <技能名>:<参数>` → `SKILL` | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-231 | | 自然语言通道兼容中文冒号 | 🔒 中文全角冒号 `：` 同样切分 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-232 | | 自然语言通道按别名引用也能命中 | 别名引用（非 name）也命中 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-233 | | 裸 ai 提问不被技能拦截而 fall through 到 AgentExecutor | 裸 ai → `AGENT` | 移植 | — | `RouteOrderTest` · M4-T7 |
| BC-234 | | 自然语言通道未授权发送者不命中 | 访问控制前置 | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-235 | | 自然语言通道不匹配已关闭的技能 | 禁用 → 不匹配 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-236 | | agent 未启用时自然语言通道命中返回失败 | 🔒 `command_agent_disabled` | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-237 | | 无用户技能配置时内置技能两条通道均开箱可用 | **别名 + 自然语言双通道**均可用 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-238 | | 内置技能详情可查且不回显模板全文 | 详情**不回显** `promptTemplate` 全文（防泄 `/log-helper` 内部约束） | 移植 | R3 | `SkillExecutorTest` · M4-T8 |
| BC-239 | executors/QuickCommandExecutorAccessControlTest.kt（类名 `SkillExecutorAccessControlTest`） | whitelist 未命中发 skills 清单时 match 不命中且执行返回 SKIPPED | 未授权 → `SKIPPED` | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-240 | | whitelist 未命中发 skills 详情时 match 不命中且执行返回 SKIPPED | `SKIPPED` | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-241 | | whitelist 未命中发技能别名时 match 不命中且执行返回 SKIPPED | `SKIPPED`，**不回显模板** | 移植 | R3 | `AccessControlTest` · M4-T7 |
| BC-242 | | blacklist 命中发 skills 清单与详情均 SKIPPED 且不含模板文本 | 🔒 不回显 `SECRET_TEMPLATE_BODY`（测试用哨兵字符串） | 移植 | R3 | `AccessControlTest` · M4-T7 |
| BC-243 | | blacklist 未命中者仍可正常使用技能清单 | 放行 | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-244 | | 授权用户的清单只展示名称别名说明示例不含 promptTemplate | 🔒 清单不含 `promptTemplate` / 哨兵 `R-77` / `/etc/secret-rules` | 移植 | R3 | `AccessControlTest` · M4-T7 |
| BC-245 | | 授权用户的详情只展示元信息不含 promptTemplate | 详情不泄模板 | 移植 | R3 | `AccessControlTest` · M4-T7 |
| BC-246 | | visibleTo 在 accessGate 拒绝时返回空清单 | 经 accessGate 过滤后为空 | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-247 | | 内置技能同样受访问闸门约束 | **内置也过闸**（无特权） | 移植 | R3 | `AccessControlTest` · M4-T7 |
| BC-248 | | 需确认技能在确认开关关闭时 fail-safe 阻断而非静默执行 | 🔒 `errorCode=="command_confirm_unavailable"`，**不拉进程** | 移植 | R3 | `ConfirmServiceTest` · M4-T8 |
| BC-249 | | 需确认技能在 provider 未回填时 fail-safe 阻断 | 阻断（同上） | 移植 | R3 | `ConfirmServiceTest` · M4-T8 |
| BC-250 | | 需确认技能在确认链路就绪时正常执行 | 就绪 → 执行 | 移植 | — | `ConfirmServiceTest` · M4-T8 |
| BC-251 | | 无需确认技能不受确认链路可用性影响 | 免确认技能照跑 | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-252 | | agent 未启用时先报 agent 未启用而非确认不可用 | 🔒 `command_agent_disabled` **优先于** `command_confirm_unavailable`（错误码优先级契约） | 移植 | — | `SkillExecutorTest` · M4-T8 |
| BC-253 | | 自然语言通道命中需确认技能时同样 fail-safe 阻断 | 阻断 | 移植 | R3 | `ConfirmServiceTest` · M4-T8 |
| BC-254 | | 自然语言通道在确认链路就绪时正常执行且不回显模板 | 就绪执行 + 不泄模板 | 移植 | R3 | `SkillExecutorTest` · M4-T8 |
| BC-255 | | 自然语言通道对未授权发送者不命中也不执行 | 不命中 | 移植 | — | `AccessControlTest` · M4-T7 |
| BC-256 | executors/CardActionExecutorTest.kt | match 仅放行受支持动作且过访问控制 | 受支持动作 + accessGate 双条件 | 移植 | — | `CardActionHandlerTest` · M4-T8 |
| BC-257 | | run_skill 未知技能返回失败 | 🔒 `errorCode=="command_not_found"` | 移植 | — | `CardActionHandlerTest` · M4-T8 |
| BC-258 | | run_skill 需参数技能点选后回引导不盲跑 | 回引导文案，**不盲跑** | 移植 | R3 | `CardActionHandlerTest` · M4-T8 |
| BC-259 | | confirm_decide 未启用确认时返回 skipped | `SKIPPED` + `kind==COMMAND` | 移植 | — | `CardActionHandlerTest` · M4-T8 |
| BC-260 | | confirm_decide 按钮裁决走同一状态机 | 非原发送者 → `Forbidden`；原发送者 → `APPROVED`（与 BC-049 同一状态机） | 移植 | R3 | `ConfirmServiceTest` · M4-T8 |
| BC-261 | executors/StaticReplyExecutorTest.kt | 精确命中返回配置内容 | **精确 `equals`** | 移植 | — | `StaticReplyHandlerTest` · M4-T8 |
| BC-262 | | 消息首尾空白不影响命中 | `trim()` 后命中 | 移植 | — | `StaticReplyHandlerTest` · M4-T8 |
| BC-263 | | 相似文本不误命中 | 🔒 `/helpme`、`/help extra` **不命中** `/help` | 移植 | R3 | `StaticReplyHandlerTest` · M4-T8 |
| BC-264 | | 大小写敏感匹配 | **大小写敏感** | 移植 | — | `StaticReplyHandlerTest` · M4-T8 |
| BC-265 | | 禁用规则与空白触发词不参与匹配 | 禁用 / 空白触发词跳过 | 移植 | — | `StaticReplyHandlerTest` · M4-T8 |
| BC-266 | | 多规则按配置顺序首个命中 | **首个命中**（顺序即优先级，对应 `routes`） | 移植 | **D16** | `RouteOrderTest` · M4-T7 |
| BC-267 | | dry-run 打印将回复内容 | 🔒 summary 含 `dry-run` | 移植 | — | `StaticReplyHandlerTest` · M4-T8 |
| BC-268 | | 非文本消息不匹配 | 非 text → 不匹配 | 移植 | — | `StaticReplyHandlerTest` · M4-T8 |
| BC-269 | executors/TaskKindTaggingTest.kt | agent 成功结果标记 AGENT | 成功 → `AGENT` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-270 | | agent 失败与非零退出结果标记 AGENT | 失败 → `AGENT` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-271 | | agent 超时与后端不可用结果标记 AGENT | 超时 / 不可用 → `AGENT` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-272 | | agent 工作区域缺失结果标记 AGENT | 工作区缺失 → `AGENT` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-273 | | agent 跳过类结果标记 AGENT | 跳过 → `AGENT`（kind 与 status 正交） | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-274 | | agent 重置与停止指令结果标记 AGENT | 重置 / 停止 → `AGENT` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-275 | | agent dry-run 结果标记 AGENT | dry-run → `AGENT` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-276 | | ping 执行器标记 COMMAND | 🔒 `ping` → `COMMAND` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-277 | | 固定回复执行器标记 COMMAND（含 dry-run） | 固定回复 → `COMMAND` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-278 | | 确认执行器裁决与未找到均标记 COMMAND | 确认 → `COMMAND` | 移植 | — | `TaskKindTaggingTest` · M4-T8 |
| BC-279 | executors/BuiltinExecutorsAssemblyTest.kt | 有技能配置时装配 QuickCommandExecutor 在 StaticReply 与 Agent 之间 | 旧硬编码 `staticIdx < skillIdx < agentIdx` | **废弃** | **D16** | → `BridgeAssemblerParityTest` · M4-T9 |
| BC-280 | | 无技能配置时仍装配 QuickCommandExecutor 使内置技能开箱可用 | 旧：内置 `log-analysis` 恒装配 | **废弃** | D16 | → `BridgeAssemblerParityTest` · M4-T9 |
| BC-281 | | 用户技能全部禁用时 QuickCommandExecutor 仍装配（内置技能兜底） | 旧：硬编码装配 | **废弃** | D16 | → `BridgeAssemblerParityTest` · M4-T9 |
| BC-282 | | 用户技能别名为空时 QuickCommandExecutor 仍装配（内置技能兜底） | 旧：硬编码 | **废弃** | D16 | → `BridgeAssemblerParityTest` · M4-T9 |
| BC-283 | | 用户关闭内置技能后其别名不再命中但 skills 命令仍可用 | 旧：硬编码 | **废弃** | D16 | 语义 → `SkillExecutorTest`（BC-220 已覆盖） |
| BC-284 | | 技能别名优先于 agent 触发前缀匹配 | 旧 C4：技能插 agent 前避免前缀遮蔽。**语义须保留** | **废弃** | D16 | → `RouteOrderTest` · M4-T7 |
| BC-285 | | agent 未启用时 QuickCommandExecutor 仍装配（清单命令可用） | 旧：硬编码 | **废弃** | D16 | → `BridgeAssemblerParityTest` · M4-T9 |
| BC-286 | | 宿主已装配 AgentExecutor 时 QuickCommandExecutor 复用宿主实例 | 旧 `agentCount==1` 复用单例 | **废弃** | D16 | 新架构唯一装配点天然消解 |
| BC-287 | | 技能执行器插在宿主注入执行器之前避免 agent 前缀遮蔽 | 旧：硬编码顺序 | **废弃** | D16 | → `RouteOrderTest` · M4-T7 |
| BC-288 | | 无技能配置时宿主执行器仍排在技能之后 | 旧 ids==`["ping", CardActionExecutor.EXECUTOR_ID, QuickCommandExecutor.EXECUTOR_ID, "host-custom"]` | **废弃** | D16 | → `BridgeAssemblerParityTest` · M4-T9 |

**BC-279..288 废弃理由（D16）**：整个文件锁死的是旧 `BuiltinExecutors.build()`（源码 :39-99）的**硬编码装配链顺序**与「复用宿主 `AgentExecutor` 单例」的静态装配纪律。新架构装配顺序由**配置文档 `routes` 数组**声明式决定，唯一装配点 `BridgeAssembler.assemble()`（App 与 CLI 共用），由 `BridgeAssemblerParityTest` 守护「同一 config 装配出的 handler 集合与顺序确定且唯一」。移植后须改写为：断言 `routes` 数组顺序即优先级、技能 route 排在 agent route 前（**前缀遮蔽语义保留**，见 BC-284），而非硬编码索引比较。

**BC-193 / BC-206 的 D16 静态状态实测坐标**：`executors/AgentExecutor.kt` companion 内 `@Volatile private var rejectionHolder: AgentExecutor?`（声明 :604-605，赋值 :95，读 :632，条件清空 :643，文件 735 行）；`executors/BuiltinExecutors.kt` object 级 `@Volatile var fileDownloader`（声明 :105-106，消费 :68）。新架构二者一律改**构造注入**，并由 `BridgeFieldsTest` / `LarkSdkIsolationTest` 之外的**新增守护断言**兜底（顶层 object/companion 内不得存在 `@Volatile var`，实现形态建议用源码扫描而非反射），**是否纳入待裁**，见 §9 待裁定点 P12。

### 3.6 lib `filemsg/`（10 方法 · 全部移植 · `FileExtractor.kt` 179 行 zip-slip 校验🔒一行不改）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-289 | filemsg/FileMessageExecutorTest.kt | zip 解包产出文件清单 | `extract` 解出 **2** 个文件 | 移植 | R3 | `FileMessageHandlerTest` · M4-T8 |
| BC-290 | | zip-slip 条目被拒绝不落盘 | 🔒 `ZipEntry("../escape.log")` → `files.isEmpty()`，**磁盘上不留任何文件**，见 §4.7 | 移植 | R3 | `FileExtractorTest` · M4-T8 |
| BC-291 | | safeChild 拒绝绝对路径与穿越 | 🔒 拒 `/etc/passwd`、`../x.log`、`a/../../x.log`；**放行** `a/b.log` | 移植 | R3 | `FileExtractorTest` · M4-T8 |
| BC-292 | | isArchive 按扩展名判定 | 🔒 `.zip`/`.tar.gz`/`.TGZ` → true；`.log`/`.xlog` → false。**注意实测还含 `.tar` 与 `.gz`** | 移植 | — | `FileExtractorTest` · M4-T8 |
| BC-293 | | file 消息命中且下载后委托 agent | 小文本**内嵌** prompt（`textInlineMaxBytes=256KB` 以内） | 移植 | — | `FileMessageHandlerTest` · M4-T8 |
| BC-294 | | zip 附件解包后 prompt 指向解出文件 | prompt 指向**解出文件**，不含包本体 | 移植 | — | `FileMessageHandlerTest` · M4-T8 |
| BC-295 | | 扩展名不在白名单不执行并提示 | 🔒 `virus.exe` → `errorCode=="file_extension_rejected"` | 移植 | — | `FileMessageHandlerTest` · M4-T8 |
| BC-296 | | 触发词门控：配置前缀时不匹配不命中 | `triggerPrefix` 非空时前缀门控生效 | 移植 | — | `FileMessageHandlerTest` · M4-T8 |
| BC-297 | | enabled 关闭后不命中 | 禁用 → `match==false` | 移植 | — | `FileMessageHandlerTest` · M4-T8 |
| BC-298 | | 下载失败回 file_download_failed 且不残留临时目录 | 🔒 `errorCode=="file_download_failed"`，`tmp-*` 目录**用完即删**。**新断言追加**：下载走流式落盘，**不在堆内缓冲整个文件**（见 §5.9） | 移植 | R3 | `FileMessageHandlerTest` · M4-T8 |

### 3.7 lib `audit/` + `stats/`（20 方法 · 全部移植 · 审计 schema 是实测定稿）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-299 | audit/AuditLoggerTest.kt | 追加写入 JSONL 每行一个对象 | 🔒 `FILE_NAME=="audit.jsonl"`，逐行一个 JSON 对象，见 §4.11 | 移植 | **D4** | `AuditSinkTest` · M4-T8 |
| BC-300 | | 目录不存在自动创建 | 自动 `mkdirs` | 移植 | — | `AuditSinkTest` · M4-T8 |
| BC-301 | | 禁用态（空目录）不写入不抛异常 | 空目录 → 禁用，**不抛** | 移植 | — | `AuditSinkTest` · M4-T8 |
| BC-302 | | 写入失败仅警告不影响主链路 | 写失败**仅 warn**，主链路继续（旁路语义） | 移植 | R3 | `AuditSinkTest` · M4-T8 |
| BC-303 | | 命令摘要截断与换行压平 | 🔒 `CMD_SUMMARY_CHARS=60`（**报告 §2.11 漏了此常量**）+ 换行压平为单行 | 移植 | — | `AuditSinkTest` · M4-T8 |
| BC-304 | | prompt 摘要截断 | 🔒 `PROMPT_SUMMARY_CHARS=200` | 移植 | R3 | `AuditSinkTest` · M4-T8 |
| BC-305 | | 带 backend 的记录 encode 后 decode 保持 backend | `backend` 字段往返一致 | 移植 | — | `AuditSinkTest` · M4-T8 |
| BC-306 | | 旧格式 JSON 行（无 backend 键）decode 为 null | 🔒 缺键 → `null`（`encodeDefaults=false` + `ignoreUnknownKeys=true`） | 移植 | R6 | `AuditSinkTest` · M4-T8 |
| BC-307 | | 带 kind 的记录 encode 后 decode 保持 kind | `kind` 字段往返一致 | 移植 | — | `AuditSinkTest` · M4-T8 |
| BC-308 | | 旧格式 JSON 行（无 kind 键）decode 为 null | 缺键 → `null` | 移植 | R6 | `AuditSinkTest` · M4-T8 |
| BC-309 | | readAll 展开 tilde 并按行解析 | 🔒 `~` 展开后按行解析 | 移植 | — | `AuditSinkTest` · M4-T8 |
| BC-310 | | readAll 含未知键的 JSON 行不丢弃 | 🔒 `ignoreUnknownKeys=true`，未知键行**不丢弃** | 移植 | R6 | `AuditSinkTest` + `JsonlTailReaderTest` · M4-T8 |
| BC-311 | stats/SenderStatsStoreTest.kt | onMessage 追加 MESSAGE 记录并解析用户名 | 🔒 `TYPE_MESSAGE=="MESSAGE"` + 用户名解析。**新断言**：解析走 suspend + 缓存，**无 runBlocking** | 移植 | **D5** | `SenderStatsSinkTest` · M4-T8 |
| BC-312 | | onRejected 按判定结果区分类型 | 🔒 `TYPE_REJECTED_WHITELIST=="REJECTED_WHITELIST"` / `TYPE_REJECTED_BLACKLIST=="REJECTED_BLACKLIST"` | 移植 | — | `SenderStatsSinkTest` · M4-T8 |
| BC-313 | | 用户名解析失败留空不抛异常 | 解析失败 → 字段**留空**，不抛。**新断言**：须在 `runTest` 协程下断言，且「缓存未命中不阻塞写路径」 | 移植 | **D5** | `SenderStatsSinkTest` · M4-T8 |
| BC-314 | | 禁用态（空目录）不写入不抛异常 | 空目录 → 禁用 | 移植 | — | `SenderStatsSinkTest` · M4-T8 |
| BC-315 | | readAll 文件不存在返回空列表 | 缺文件 → 空列表 | 移植 | — | `SenderStatsSinkTest` · M4-T8 |
| BC-316 | | readAll 跳过损坏行 | 损坏行跳过，不中断整体解析 | 移植 | — | `SenderStatsSinkTest` · M4-T8 |
| BC-317 | | readAll 含未知键的 JSON 行不丢弃 | `ignoreUnknownKeys` | 移植 | R6 | `SenderStatsSinkTest` · M4-T8 |
| BC-318 | | 内容摘要截断 | 🔒 `CONTENT_SUMMARY_CHARS=120`；文件名 🔒 `FILE_NAME=="sender_stats.jsonl"` | 移植 | — | `SenderStatsSinkTest` · M4-T8 |

**D4 新断言（BC-299..310）**：旧实现每条记录 `open → write → close`（D4 缺陷）；新架构 `JsonlSink` 常驻 `BufferedWriter` + 500ms / 64 条批量 flush。**schema 断言原样移植**，但「写入方式」相关断言须改为批量落盘，并配 `JsonlSinkDropCountTest`（§7）守护丢弃计数。

**D5 新断言（BC-311 / BC-313，关键 · 目标文件已校正）**：旧「用户名解析失败留空不抛」方法内部走 `runBlocking` 同步网络解析。**实测校正**：`runBlocking` **不在** `openapi/UserNameResolver.kt`（该文件 :33 已是 `suspend fun resolve(openId: String): String?`，仅平移即可），实际在 **`stats/SenderStatsStore.kt:88-90` 的 `runBlockingName`**（`:63` onMessage 与 `:79` onRejected 两处调用）。⇒ 删 `runBlocking` 的**目标文件是 SenderStatsStore**，相关旧测试断言按此归位。新架构改 suspend + 缓存，**移出写路径**。

### 3.8 lib `model/` + `progress/` + `log/`（实测 28 方法 · 全部移植 · TaskKind 含 SKILL 保留）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-319 | model/TaskResultTest.kt | 默认 kind 为指令型 | 🔒 `TaskResult(...).kind==COMMAND`（success/failure/skipped 三个工厂默认亦 `COMMAND`） | 移植 | — | `TaskResultTest` · M4-T8 |
| BC-320 | | success 工厂可指定 kind 且其他字段不受影响 | `kind=AGENT`，其余字段不变 | 移植 | — | `TaskResultTest` · M4-T8 |
| BC-321 | | failure 工厂可指定 kind 且保留错误码 | 🔒 `kind=SKILL` + `errorCode=="agent_timeout"` | 移植 | — | `TaskResultTest` · M4-T8 |
| BC-322 | | skipped 工厂可指定 kind | `kind=AGENT` + `status=SKIPPED` | 移植 | — | `TaskResultTest` · M4-T8 |
| BC-323 | | copy 不丢失 kind（引擎补耗时路径） | `copy` 保 `kind`（引擎补 elapsedMillis 路径不覆盖 kind） | 移植 | R3 | `TaskResultTest` · M4-T8 |
| BC-324 | | 三类类型枚举齐备 | 🔒 `TaskKind.entries==["COMMAND","SKILL","AGENT"]`（**顺序亦固定**），见 §4.12 | 移植 | R3 | `TaskResultTest` · M4-T8 |
| BC-325 | model/BridgeConfigTest.kt | 新字段默认值 | workspaceWhitelist/skills 空、`prohibitDelete`/`safetyPromptEnabled` 默认 `true`、`workspaceDir==""` | 移植（语义）/ 废弃（旧类载体） | D8 | `BridgeConfigDefaultsTest` · M4-T5 |
| BC-326 | | QuickCommandConfig 字段默认值 | id/name/description/promptTemplate==`""`、aliases 空、`enabled=true`、`requiresConfirm=false` | 移植（语义） | — | `BridgeConfigDefaultsTest` · M4-T5 |
| BC-327 | | 存量 JSON 缺新字段可正常解析 | 缺字段取默认，**向后兼容** | 移植 | R6 | `ConfigMigrationsTest` · M4-T5 |
| BC-328 | | 存量 JSON 含未来未知键不丢弃 | 🔒 `ignoreUnknownKeys`，**未知键保留回写** | 移植 | R6 | `ConfigMigrationUnknownKeyPreservedTest` · M4-T5 |
| BC-329 | | 新字段序列化往返一致 | encode → decode 等价 | 移植 | — | `BridgeConfigRoundTripTest` · M4-T5 |
| BC-330 | | 技能配置可显式关闭与免确认 | `enabled=false` 过滤、`requiresConfirm` 生效 | 移植 | — | `BridgeConfigRoundTripTest` · M4-T5 |
| BC-331 | progress/ProgressTest.kt（类 `ClaudeStreamParserTest`） | assistant 事件分发 thinking text tool_use | 🔒 实测 JSONL（claude 2.1.260）：`ToolUse.name=="Bash"`、`argsSummary=="ls -l \| wc -l"`、`terminalResultText=="最终回复文本"`、`terminalIsError==false` | 移植 | R3 | `ClaudeStreamParserTest` · M4-T8 |
| BC-332 | | user 事件的 tool_result 产出 ToolResult | 🔒 `ok==true`、`summary=="3"` | 移植 | — | `ClaudeStreamParserTest` · M4-T8 |
| BC-333 | | 非 JSON 行与未知 type 静默忽略不崩溃 | 噪声行 → **0 事件**，不崩 | 移植 | R3 | `ClaudeStreamParserTest` · M4-T8 |
| BC-334 | | result 事件 isError 标记 | 🔒 `is_error:true` → `terminalIsError==true` | 移植 | — | `ClaudeStreamParserTest` · M4-T8 |
| BC-335 | progress/ProgressTest.kt（类 `ProgressCardRendererTest`） | 受理卡片含任务摘要与蓝色 header | 🔒 含 `已受理` + 摘要 + header 色 `blue` | 移植 | **D1** | `ProgressCardRendererTest` · M3-T3 |
| BC-336 | | 进行中卡片含时长与事件流 | 🔒 含 `已运行 35s` + 图标 `💭` `🔧` `✅` | 移植 | **D1** | `ProgressCardRendererTest` · M3-T3 |
| BC-337 | | 终态卡片按状态换色 | 🔒 DONE → `已完成` + `green`；FAILED → `失败` + `red` | 移植 | **D1** | `ProgressCardRendererTest` · M3-T3 |
| BC-338 | | 整卡超长时丢弃最旧条目 | 🔒 `Limits(maxEntries=12, entryMaxChars=200, cardMaxChars=3000)` → `body.length<=3200` | 移植 | R7 | `ProgressCardRendererTest` + `SegmentRotationTest` · M3-T3 |
| BC-339 | | 单条超长截断加省略号 | 🔒 含省略号 `…` | 移植 | — | `ProgressCardRendererTest` · M3-T3 |
| BC-340 | progress/ProgressTest.kt（类 `ProgressAccumulatorTest`） | 滚动窗口保留最近 N 条 | 🔒 `maxEntries=3` → 保留最近 3 条，`last=="cmd5"` | 移植 | **D3** | `ProgressAccumulatorTest` · M4-T8 |
| BC-341 | | 相邻 thinking 合并为一条 | 相邻 `Thinking` **合并**为一条（防刷屏） | 移植 | — | `ProgressAccumulatorTest` · M4-T8 |
| BC-342 | | 终态标记进入快照 | `statusOverride` 生效 | 移植 | — | `ProgressAccumulatorTest` · M4-T8 |
| BC-343 | log/BridgeLogBusTest.kt（类 `BridgeLogBusTest`） | publish 后订阅者可收到 | replay 生效，`message`/`level`/`tag` 三者一致 | 移植 | — | `LogBusTest` · M4-T8 |
| BC-344 | | replay 保留最近事件 | 10 条全保留 | 移植 | — | `LogBusTest` · M4-T8 |
| BC-345 | | 超量发布后只保留最近 replay 条 | 旧 🔒 `REPLAY_COUNT=200`：300 条 → `first=="msg-100"`、`last=="msg-299"`。**新断言改数值**：新 `LogBus.capacity=1000`（D7/R14 刻意变更） | 移植（**改数值**） | **D7/D18** | `LogBusTest` + `JsonlSinkDropCountTest` · M4-T8 |
| BC-346 | log/BridgeLogBusTest.kt（类 `BusBridgeLoggerTest`） | logger 写入总线并转发 delegate | 🔒 写总线 + 转发 delegate，前缀 `I:` / `E2:` | 移植 | — | `LogBusTest` · M4-T8 |

**§1.8 报告口径校正**：调研报告 §1.8 标题写「34 方法」，实测 `model/TaskResultTest`(6) + `model/BridgeConfigTest`(6) + `progress/ProgressTest`(4+5+3=12) + `log/BridgeLogBusTest`(3+1=4) = **28**。本表按 28 行编号（BC-319..346）。差额 6 疑为报告把 `model/` 与 `progress/` 的文件级行数重复计入，**不影响 §2.2 的 550 方法总数**（该总数由 `@Test` 直接计数得出）。

**BC-345 的 D7/R14 刻意变更**：旧 `BridgeLogBus.REPLAY_COUNT=200`（源码 :44），新 `LogBus` 为环形 `ArrayDeque(capacity=1000)` + `SnapshotStateList` + 10Hz 合批。旧断言的**字面数值**（`msg-100` / `msg-299`）在容量 1000 下不成立，移植时须改为「发布 1500 条 → `first=="msg-500"`、`last=="msg-1499"`」或等价的比例断言，并配 `LogConsoleCoalescingTest`（§7）守护 UI 侧合批。

### 3.9 lib `quickcommand/` + `reply/` + `util/` + `lark/`（策略/路径类 · 76 方法 · 全部移植）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-347 | quickcommand/QuickCommandRegistryTest.kt（类名 `SkillRegistryTest`） | 别名前缀命中返回对应技能 | 🔒 `/log crash.txt` → `id=="log"` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-348 | | 精确等于别名也命中 | `/log` → 命中 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-349 | | 多别名命中取最长 | 🔒 `/log-analyse` 胜 `/log` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-350 | | 短别名文本不被长别名误命中 | `/log crash` → 短别名 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-351 | | disabled 技能不匹配 | 禁用 → `null` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-352 | | findByAlias 未命中返回 null | 空 / 空白 / 无关 → `null` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-353 | | allEnabled 只返回启用技能 | 过滤 disabled | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-354 | | 空别名的技能不参与匹配 | 无别名 → `allEnabled` 计 0 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-355 | | findByAliasWithArgs 分离参数 | 🔒 `/log crash.txt 2024-01-01` → `args=="crash.txt 2024-01-01"` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-356 | | findByAliasWithArgs 无参数时 args 为空 | `args==""` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-357 | | findByAliasWithArgs 未命中返回 null | `null` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-358 | | findById 按 id 查找（含 disabled） | **含禁用**（详情可查语义） | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-359 | | visibleTo 当前返回 allEnabled | `visibleTo==allEnabled` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-360 | | isCommandsCommand 识别发现命令 | 🔒 `/commands`、`/commands x`、trim 后 → true；`/skillsomething` → **false** | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-361 | | commandsCommandArg 提取子参数 | 🔒 `/commands log-analysis` → `"log-analysis"` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-362 | | 多别名技能任一别名均可命中 | 🔒 `/log`、`/日志`、`/analyse` 均命中 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-363 | | QuickCommandDescriptor from 正确映射字段 | blank name → 回退 id；过滤空白别名；`builtIn` 标记 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-364 | | 自然语言通道按技能名命中并返回去前缀去技能名后的参数 | 🔒 `ai 日志分析: x` → id + args | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-365 | | 自然语言通道兼容中文冒号 | 中文全角冒号 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-366 | | 自然语言通道兼容英文冒号且冒号后无空格 | 🔒 `名:crash.txt` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-367 | | 自然语言通道按别名去斜杠命中 | 🔒 `ai log: x` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-368 | | 自然语言通道按别名原样命中 | 🔒 `ai /log: x` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-369 | | 自然语言通道按技能 id 命中 | 🔒 `ai log-analysis: x` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-370 | | 裸 ai 提问不被自然语言通道拦截 | 🔒 无 `名:` 形态 → `null`（含 `ai 日志分析` 无冒号、`ai`、`ai `） | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-371 | | 自然语言通道冒号在首位或技能名未知时不命中 | 🔒 `ai : x`、未知技能、部分匹配 → `null` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-372 | | 自然语言通道要求前缀后紧跟空格避免误吞同前缀词 | 🔒 `ail` / `aix` → `null` | 移植 | R3 | `SkillRegistryTest` · M4-T8 |
| BC-373 | | 自然语言通道未配置 agent 前缀时整体关闭 | prefixes 空 → `null` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-374 | | 自然语言通道多前缀取最长命中 | 🔒 `/ai`、`小助手` 命中 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-375 | | 自然语言通道不匹配 disabled 技能 | 禁用 → `null` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-376 | | 自然语言通道参数含冒号时只在首个冒号处切分 | 🔒 `10:30 的 crash` 整体为 args | 移植 | R3 | `SkillRegistryTest` · M4-T8 |
| BC-377 | | 自然语言通道参数为空时返回空串 | 🔒 `名:` → `args==""` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-378 | | 自然语言通道多技能各自命中不串台 | 多技能独立命中，部分名不命中 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-379 | | 内置技能默认合并进注册表且标记 builtIn | 🔒 `id=="log-analysis"`、`name=="日志分析"`、`aliases==["/log","/log-helper"]`、`builtIn==true` | 移植 | R3 | `SkillRegistryTest` · M4-T8 |
| BC-380 | | 用户同 id 配置整体覆盖内置技能 | **整体替换**非字段合并，仍标 `builtIn` | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-381 | | 用户对内置 id 设 enabled false 可关闭内置技能 | 关闭生效，**详情仍可查** | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-382 | | 用户技能与内置技能共存互不干扰 | 两 id 并存 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-383 | | builtInSkills 传空列表时注册表只含用户技能 | 空内置 → 仅用户 | 移植 | — | `SkillRegistryTest` · M4-T8 |
| BC-384 | | 内置技能定义满足开箱可用契约 | 🔒 `LOG_ANALYSIS`：`aliases==["/log","/log-helper"]`、`requiresConfirm==false`、`promptTemplate` 含 `{args}`（实测全文 `/log-helper {args}`）；`ALL==[LOG_ANALYSIS]`；`IDS=={"log-analysis"}` | 移植 | R3 | `SkillRegistryTest` · M4-T8 |
| BC-385 | | 别名后接非空白字符不命中 | 🔒 `/log-helper` 中 `/log` 后是 `-` → **不命中**；`/logx`、`/logabc x` 不命中 | 移植 | R3 | `SkillRegistryTest` · M4-T8 |
| BC-386 | | 内置 log-helper 别名透传参数不重复 | 🔒 `/log-helper 09-02_...` → args **不含**被误拆的 `-helper` 脏后缀 | 移植 | R3 | `SkillRegistryTest` · M4-T8 |
| BC-387 | reply/LarkResponderKindTest.kt | AGENT 类型渲染 Agent 前缀 | 🔒 首行 == `**🤖 Agent · ✅ 任务完成**` | 移植 | R3 | `ReplyRendererTest` · M4-T8 |
| BC-388 | | SKILL 类型渲染快捷指令前缀 | 🔒 首行 == `**🧩 快捷指令 · ✅ 任务完成**` | 移植 | — | `ReplyRendererTest` · M4-T8 |
| BC-389 | | COMMAND 类型渲染指令前缀（默认） | 🔒 首行 == `**⚡ 指令 · ✅ 任务完成**` | 移植 | — | `ReplyRendererTest` · M4-T8 |
| BC-390 | | 三种状态均携带类型前缀 | 🔒 失败 `❌ 任务失败`、跳过 `⏭️ 已跳过` | 移植 | — | `ReplyRendererTest` · M4-T8 |
| BC-391 | | 错误码与耗时排版保持原样 | 🔒 `lines[1]==摘要`、`lines[2]=="错误码：`agent_timeout`"`、`lines[3]=="耗时：1.5s"`（`elapsedMillis/1000.0`） | 移植 | R3 | `ReplyRendererTest` · M4-T8 |
| BC-392 | | 无 messageId 时走 send 通道仍带类型前缀 | messageId 空 → `send` 通道，`replies` 为空 | 移植 | — | `ReplyRendererTest` · M4-T8 |
| BC-393 | util/PathExpansionTest.kt | 裸 ~ 展开为主目录 | 🔒 `~` → `user.home` | 移植 | R3 | `PathExpansionTest` · M4-T6 |
| BC-394 | | 正斜杠 tilde 前缀展开为主目录子路径（POSIX 惯例） | 🔒 `~/work` → `$home/work` | 移植 | — | `PathExpansionTest` · M4-T6 |
| BC-395 | | 反斜杠 tilde 前缀同样展开（Windows 分隔符习惯） | 🔒 `~\work` → `$home\work` | 移植 | R11 | `PathExpansionTest` · M4-T6 |
| BC-396 | | 无前缀路径原样返回 | 🔒 `/opt/data`、`relative/path`、`C:\work` **原样** | 移植 | — | `PathExpansionTest` · M4-T6 |
| BC-397 | | ~user 形态不展开（不解析他人主目录） | 🔒 `~root/home` **原样**（安全：不解析他人主目录） | 移植 | R3 | `PathExpansionTest` · M4-T6 |
| BC-398 | | Unix 绝对路径判定 | `/opt/data` 绝对；`relative/path`、`C:\work` 相对 | 移植 | — | `PathExpansionTest` · M4-T6 |
| BC-399 | | 当前平台上判定与 java io File 一致 | 🔒 `isAbsolute(p) == File(p).isAbsolute` | 移植 | R11 | `PathExpansionTest` · M4-T6 |
| BC-400 | lark/WorkspacePolicyTest.kt | 多根命中任一放行 | 任一 root 的子目录 → 放行 | 移植 | — | `WorkspacePolicyTest` · M4-T6 |
| BC-401 | | 空 roots fail-safe 拒绝所有 | 🔒 `isEmpty` → 拒 `/tmp`、`/`（fail-closed） | 移植 | R3 | `WorkspacePolicyTest` · M4-T6 |
| BC-402 | | 分隔符边界防误放行 | 🔒 `/a/bc` **不是** `/a/b` 的子目录 → 拒 | 移植 | R3 | `WorkspacePolicyTest` · M4-T6 |
| BC-403 | | 越界路径被拒绝 | `/etc`、`/` → 拒 | 移植 | — | `WorkspacePolicyTest` · M4-T6 |
| BC-404 | | fromConfig 取并集去空白 | `workspaceDir` + `whitelist` **并集**，去空白项 | 移植 | — | `WorkspacePolicyTest` · M4-T6 |
| BC-405 | | fromConfig 全空时 isEmpty | 全空 → `isEmpty` | 移植 | — | `WorkspacePolicyTest` · M4-T6 |
| BC-406 | | fromConfig workspaceDir 与 whitelist 重复时去重 | 去重后 `canonicalRoots.size==1` | 移植 | — | `WorkspacePolicyTest` · M4-T6 |
| BC-407 | | denyDelete 标记透传 | `denyDelete` 布尔透传到策略对象 | 移植 | — | `WorkspacePolicyTest` · M4-T6 |
| BC-408 | lark/DownloadPathPolicyTest.kt | 工作区域未配置返回 null | 空 / 空白 workspace → `null` | 移植 | — | `DownloadPathPolicyTest` · M4-T6 |
| BC-409 | | 正常文件名落在 downloads 日期目录 | target 在 workspace 内、path 含 `downloads`、name = **messageId 前缀 + 原名** | 移植 | — | `DownloadPathPolicyTest` · M4-T6 |
| BC-410 | | 路径分隔符被清洗 | 🔒 `../../etc/passwd` → name 不含 `/`、不含 `..`，仍在 workspace 内 | 移植 | R3 | `DownloadPathPolicyTest` · M4-T6 |
| BC-411 | | 反斜杠与控制字符被清洗 | 🔒 `a\b\u0007c.txt` → 去反斜杠 + 去控制字符 | 移植 | R11 | `DownloadPathPolicyTest` · M4-T6 |
| BC-412 | | 清洗后为空回退 file | 🔒 `../..` → name 以 `_file` 结尾 | 移植 | — | `DownloadPathPolicyTest` · M4-T6 |
| BC-413 | | 越界路径抛 IOException | canonical 越界检测 → 抛 `IOException` | 移植 | R3 | `DownloadPathPolicyTest` · M4-T6 |
| BC-414 | lark/SandboxedProcessFactoryTest.kt | 合法工作目录正常启动 | 子目录 → 透传 workDir | 移植 | — | `ProgramLauncherTest` · M4-T6 |
| BC-415 | | 工作区域根本身作为工作目录放行 | root **本身**放行 | 移植 | — | `ProgramLauncherTest` · M4-T6 |
| BC-416 | | 越界工作目录抛 IOException | 🔒 `/tmp` → `IOException`，message 含 `越界` | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-417 | | 前缀相似但非子目录的路径被拒绝 | 同级前缀扩展 → 拒 | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-418 | | workingDir 为空不校验直接透传 | `null` → 透传 | 移植 | — | `ProgramLauncherTest` · M4-T6 |
| BC-419 | | BASE_ENV_KEYS 含代理大小写变体且覆盖基础项 | 🔒 11 键含 `PATH`/`HOME`/`LANG`/`LC_ALL`/`TZ`/`http_proxy`/`https_proxy`/`no_proxy`/`HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`，见 §4.9 | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-420 | | Unix 平台基础白名单与历史 BASE_ENV_KEYS 逐键相等（零回归锚点） | 🔒 `effectiveBaseKeys(isWindows=false)==BASE_ENV_KEYS` | 移植 | R3 | `ProgramLauncherTest` · M4-T6 |
| BC-421 | | Windows 平台基础白名单追加全部系统必需键 | 🔒 `==BASE_ENV_KEYS + WINDOWS_REQUIRED_ENV_KEYS`（11 键），见 §4.9 | 移植 | R3/R11 | `ProgramLauncherTest` · M4-T6 |
| BC-422 | | defaultFactory 返回的工厂具备目录校验能力 | `defaultFactory` 也拒越界 | 移植 | — | `ProgramLauncherTest` · M4-T6 |

**BC-419..421 与新配置的冲突（重要，须裁定）**：方案新配置文档 `workspace.envAllowlist` 默认值为 **8 键** `["PATH","HOME","LANG","LC_ALL","TZ","HTTP_PROXY","HTTPS_PROXY","NO_PROXY"]`，**缺** 旧 `BASE_ENV_KEYS` 的三个小写变体 `http_proxy`/`https_proxy`/`no_proxy`（Linux 惯例用小写，curl/git/python 均优先读小写）；且旧 `WINDOWS_REQUIRED_ENV_KEYS` 11 键与 `effectiveBaseKeys(isWindows)` 的**平台化追加逻辑**在新方案中**无对应表述**。BC-420/BC-421 是明确的「零回归锚点」，若照新默认值实现会直接失败。⇒ 标为 §9 待裁定点 **P7**，本文档不自行改方案。

### 3.10 lib SDK 吸收组（42 方法 · 8 文件 · 全部 SDK吸收 → 改写为 `LarkInboundMapperTest` / `LarkPolicyMapperTest`）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-423 | lark/LarkEventParseTest.kt（旧用 `LarkCliTransport`） | 解析标准群聊事件 | `chat_type=="group"`、mention 前缀剥离后 🔒 `content=="你是谁"`。**新形态**：SDK `NormalizedMessage` 无 `eventId` 字段 | **SDK吸收** | **D15** | `LarkInboundMapperTest` · M3-T3 |
| BC-424 | | 解析私聊事件 | 🔒 `chat_type=="p2p"`、`createTime==""`（旧 cli 无该字段） | **SDK吸收** | D15 | `LarkInboundMapperTest` · M3-T3 |
| BC-425 | | 非 JSON 行不抛异常 | `rawJson` 兜底、`eventId==""` | **SDK吸收** | — | `LarkInboundMapperTest` · M3-T3 |
| BC-426 | | event_id 缺省回退 message_id | 旧：`eventId` 回退 `messageId`。**新架构**：直接以 `messageId` 为唯一幂等键，无回退概念 | **SDK吸收** | **D12** | `MessageDeduperTest` · M4-T7 |
| BC-427 | openapi/ws/V2EventParserTest.kt | 解析标准群聊 v2 事件 | schema 2.0 `header`+`event` 解析、mention 剥离 + **双重解码**、🔒 `content=="你是谁"`、`mentioned==true` | **SDK吸收** | R1 | `LarkInboundMapperTest` · M3-T3 |
| BC-428 | | 纯文本无 @ 的群聊消息不含 mention | `mentioned==false` | **SDK吸收** | — | `LarkInboundMapperTest` · M3-T3 |
| BC-429 | | 解析私聊事件含 at 标记 | p2p、🔒 `content=="ping"` | **SDK吸收** | — | `LarkInboundMapperTest` · M3-T3 |
| BC-430 | | 解析含 at 标记的群聊事件 | 🔒 `<at>` 剥离后 `content=="帮我分析"` | **SDK吸收** | — | `LarkInboundMapperTest` · M3-T3 |
| BC-431 | | 非法 payload 不抛异常 | `rawJson` 兜底，**不抛** | **SDK吸收** | R1 | `LarkInboundMapperTest` · M3-T3 |
| BC-432 | | event_id 缺省回退 message_id | 同 BC-426 | **SDK吸收** | **D12** | `MessageDeduperTest` · M4-T7 |
| BC-433 | | 解析卡片按钮回调事件 | 🔒 `card.action.trigger` → `messageType=="card_action"`、`cardAction.name=="run_skill"`、`valueJson` 含 `skill_id`、`openMessageId`。**新形态**：SDK 侧字段名不同，映射须重写 | **SDK吸收** | R7 | `LarkInboundMapperTest` · M3-T3 |
| BC-434 | | 卡片回调 operator 顶层直挂 id 时兜底解析 | `operator.open_id` **扁平形态**兜底 | **SDK吸收** | — | `LarkInboundMapperTest` · M3-T3 |
| BC-435 | | 卡片回调 operator 全空时 rawJson 兜底提取 ou_ id | 从 `rawJson` 提取**首个** `ou_` 前缀串 | **SDK吸收** | — | `LarkInboundMapperTest` · M3-T3 |
| BC-436 | | 未知事件类型返回空 eventId 由引擎丢弃 | 🔒 `im.chat.updated` → `eventId==""` → 引擎丢弃。**新形态**：改 `messageId==""` 丢弃 | **SDK吸收** | D12 | `StaleFilterTest` · M4-T7 |
| BC-437 | openapi/ws/ChunkCacheTest.kt | 单片直接返回 | `sum==1` → 直接返回 data | **SDK吸收** | R1 | **直接删除**（SDK 承担分片） |
| BC-438 | | 分片齐包后按序重组 | 乱序 seq 重组 🔒 `=="aaaabbbb"` | **SDK吸收** | — | **直接删除** |
| BC-439 | | 乱序三片重组 | 🔒 `=="aabbcc"` | **SDK吸收** | — | **直接删除** |
| BC-440 | | 重复 seq 覆盖 | 重推覆盖，齐包后清缓存 | **SDK吸收** | D12 | **直接删除** |
| BC-441 | | 过期分片被清理后重新收集 | `EXPIRE_MILLIS` 过期清理 | **SDK吸收** | — | **直接删除** |
| BC-442 | openapi/ws/WsEndpointClientTest.kt | feishu 区域端点拼接在域名根下（非 open-apis 前缀） | 🔒 URL==`https://open.feishu.cn/callback/ws/endpoint`、path==`/callback/ws/endpoint` | **SDK吸收** | R1/D15 | **直接删除**（SDK `ws.Client` 承担） |
| BC-443 | | lark 区域端点用国际版域名根 | 🔒 `https://open.larksuite.com/callback/ws/endpoint` | **SDK吸收** | — | **直接删除** |
| BC-444 | | 成功响应解析 wss URL 与 ClientConfig | `pingIntervalSeconds=30` / `reconnectCount=3` / `reconnectIntervalSeconds=60` / `reconnectNonceSeconds=10` | **SDK吸收** | — | **直接删除**（SDK 内部值，不可断言） |
| BC-445 | | 非零错误码映射为不可重试连接异常 | 🔒 `code=99991672` → `FatalConnectException`。**语义须保留**：错误码 → 网关状态映射 | **SDK吸收** | R2 | `LarkGatewayErrorMappingTest` · M3-T3 |
| BC-446 | | 可重试错误码映射为可重试连接异常 | 🔒 `code=1` → `RetryableConnectException` | **SDK吸收** | R2 | `LarkGatewayErrorMappingTest` · M3-T3 |
| BC-447 | | HTTP 404 纯文本响应映射为不可重试异常 | 404 → `FatalConnectException` | **SDK吸收** | R2 | `LarkGatewayErrorMappingTest` · M3-T3 |
| BC-448 | | 缺 URL 字段视为可重试异常 | 无 URL → `Retryable` | **SDK吸收** | R2 | `LarkGatewayErrorMappingTest` · M3-T3 |
| BC-449 | openapi/ws/WsFrameCodecTest.kt | 编码解码回环 - 空帧 | `WsFrame` 回环等价 | **SDK吸收** | R1 | **直接删除**（已否决「自研 WebSocket 协议栈」） |
| BC-450 | | 编码解码回环 - ping 帧 | `METHOD_CONTROL` 回环 | **SDK吸收** | — | **直接删除** |
| BC-451 | | 编码解码回环 - 事件帧含 payload | `METHOD_DATA` + `headerValue("type")=="event"` | **SDK吸收** | — | **直接删除** |
| BC-452 | | 编码解码回环 - 多 header 与大 varint | `Long.MAX_VALUE` seqId、70000 字节 payload（>64KB 多字节 varint） | **SDK吸收** | — | **直接删除** |
| BC-453 | | 解码截断数据抛异常 | 截断 → `IllegalArgumentException` | **SDK吸收** | — | **直接删除** |
| BC-454 | | 未知字段被跳过 | 未知 field 跳过 | **SDK吸收** | — | **直接删除** |
| BC-455 | openapi/CardElementsTest.kt | skill panel button uses behaviors callback not action wrapper | 🔒 卡片 schema 2.0 button 走 `behaviors` 数组（`type=="callback"`），**无 `action` 属性**——否则飞书回 `230099 unknown property: action`，见 §4.14 | **SDK吸收** | R7 | `LarkPolicyMapperTest` · M3-T3 |
| BC-456 | | confirm action buttons also use behaviors | 确认卡按钮同样 `behaviors`、**无 `action`** | **SDK吸收** | R7 | `LarkPolicyMapperTest` · M3-T3 |
| BC-457 | openapi/LarkOpenApiTransportUrlTest.kt | reply 走 token 与消息接口的正确完整路径 | 🔒 paths==[`/open-apis/auth/v3/tenant_access_token/internal`, `/open-apis/im/v1/messages/om_test_001/reply`] | **SDK吸收** | D15 | **直接删除**（SDK 承担 URL） |
| BC-458 | | path 防御性拼接回归-无斜杠 path 不再粘连 | 无斜杠 path 自动补齐，不与 baseUrl 粘连（避免 `open-apisim/...` 404） | **SDK吸收** | — | **直接删除** |
| BC-459 | | 网关 404 纯文本时 reply 返回失败不抛异常 | 404 → `false`，**不抛**。**语义须保留**：出站失败必须可观测 | **SDK吸收** | R2 | `LarkGatewayErrorMappingTest` · M3-T3 |
| BC-460 | lark/CardContentTest.kt（**被测源文件实为 `openapi/MessageContent.kt`**，见 §6） | openapi cardContent 输出 2_0 结构且 content 往返一致 | 🔒 `schema=="2.0"` + `body.elements[0].tag=="markdown"`（1.0 markdown 只支持子集，`####` 标题不渲染），见 §4.14 | **SDK吸收** | **D1**/R7 | `LarkPolicyMapperTest` · M3-T3 |
| BC-461 | | openapi cardContent 转义特殊字符 | 引号 / 反斜杠 / 换行转义往返一致 | **SDK吸收** | — | `LarkPolicyMapperTest` · M3-T3 |
| BC-462 | | cli jsonCardContent 输出 2_0 结构且与 openapi 同构 | cli 与 openapi 卡片结构一致 | **SDK吸收**（cli 分支随 D15 退役） | D15 | **直接删除** |
| BC-463 | | msgType markdown 走 interactive | 🔒 markdown → `"interactive"`；否则 → `"text"` | **SDK吸收** | — | `LarkPolicyMapperTest` · M3-T3 |
| BC-464 | | replyBody 与 sendBody 携带 2_0 卡片 content | 🔒 `msg_type=="interactive"`、content 含 `"schema":"2.0"`、`sendBody.receive_id` | **SDK吸收** | — | `LarkPolicyMapperTest` · M3-T3 |

**SDK 吸收改写指引（映射原则①）**：这 8 个文件锁住的是**旧自研传输层**——`LarkCliTransport` 裸 ProcessBuilder（D15 退役）、自研 WebSocket 协议栈（`WsFrameCodec` / `WsEndpointClient` / `ChunkCache`）、openapi URL 手工拼接、卡片 JSON 手工构造。新架构传输全权交给官方 `com.larksuite.oapi:oapi-sdk:2.8.5` 的 `LarkChannel`（隔离在 `:lark-gateway`，由 `LarkSdkIsolationTest` 守护）。42 条按三类处置：

1. **改写为 `LarkInboundMapperTest`（M3-T3）**：BC-423..426、BC-427..436 —— SDK 入站事件 → 内部 `InboundMessage` 归一化。覆盖 mention 剥离、双重解码、`card.action.trigger` → cardAction、`operator` 三层兜底提取 `ou_`、未知事件类型丢弃。**幂等键改 `messageId`**（D12），且须按 §5.8 的 `NormalizedMessage` **实际字段名**（`rawContentType` 而非 `messageType`）写断言。
2. **改写为 `LarkPolicyMapperTest`（M3-T3）**：BC-455、BC-456、BC-460、BC-461、BC-463、BC-464 —— 内部出站意图 → SDK 卡片/消息 API。覆盖卡片 schema 2.0 markdown、`behaviors` 无 `action`、`msg_type` interactive/text。**流式改 CardKit `streaming_mode`**（D1），30KB / 24KB 分段 / 9min 轮换 / 200810 交互互斥由 R7 六层防护 + §7 新增非功能测试承担。
3. **直接删除（不承接）**：BC-437..444、BC-449..454、BC-457、BC-458、BC-462 —— 纯 SDK 内部机制（protobuf 编解码、endpoint URL、分片缓存、openapi URL 拼接、cli 卡片分支）。SDK 已实现，不再自研（对应已否决方案「自研 WebSocket 协议栈」）。其中 BC-444 的 `pingIntervalSeconds=30` 等**是 SDK 内部值，新架构不可断言**。
4. **语义升级为 `LarkGatewayErrorMappingTest`（M3-T3）**：BC-445..448、BC-459 —— 错误码/HTTP 状态 → 网关状态与可观测性映射。**必须补 §5.3 的新增断言**：CardKit 流式四类业务码（300317 / 200860 / 200750 / 200810）在 SDK 内被静默吞掉，新实现自行检查 response 成功码并产出「计数 + 日志 + 错误映射」。

### 3.11 lib 根 Integration（19 方法 · 4 文件 · 18 废弃 + 1 移植 → `BridgeRuntimeIntegrationTest` + `SyntheticGateway`）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-465 | LarkBridgeAgentIntegrationTest.kt | agent 启用时事件经引擎路由到 agent 并回复结果 | 事件 → AgentExecutor → reply；🔒 命令首 token==`claude`、含 `分析日志`、reply 含 `任务完成` + stdout | **废弃** | — | → `BridgeRuntimeIntegrationTest` · M4-T9 |
| BC-466 | | agent 关闭时 ai 消息不被处理且 ping 行为不变 | agent 关 → 0 命令 0 回复；🔒 `ping` → `pong` | **废弃** | — | → `BridgeRuntimeIntegrationTest` · M4-T9 |
| BC-467 | LarkBridgeConfirmLifecycleTest.kt | Idle 态构造不绑定确认总线端口 | 构造期 `bus.port==-1`、`isRunning==false`、`status==Disabled`、`state==Idle` | **废弃**（语义移入新 lifecycle） | **D9** | → `BridgeLifecycleTest` · M4-T9 |
| BC-468 | | Idle 态 stop 不抛异常且总线仍未绑定 | Idle stop 不改状态、`port==-1`、**不抛** | **废弃** | D9/D20 | → `TeardownCompletenessTest` · M4-T9 |
| BC-469 | | recreate 多次不累积监听端口 | 🔒 ×8 recreate 后 running 计数==0、全 `port==-1` | **废弃** | **D9** | → `StartStopLeakTest` · M4-T9 |
| BC-470 | | start 绑定端口 stop 释放且状态归位 | start → `isRunning` + `port>0` + `Ready`；stop → `port==-1` + `Disabled` | **废弃** | **D20** | → `BridgeLifecycleTest` · M4-T9 |
| BC-471 | | stop 后再次 start 可重新绑定端口（scope 重建） | 二次 start 重绑、`acceptLoop` 存活 | **废弃** | D9 | → `StartStopLeakTest` · M4-T9 |
| BC-472 | | confirmEnabled 但后端非 claude 时状态为 Unavailable | 🔒 `status==Unavailable`、reason 含 `claude`、`bus==null` | **废弃**（语义移入新 lifecycle） | — | → `BridgeLifecycleTest` · M4-T9 |
| BC-473 | | agent 关闭时确认状态为 Disabled 且不创建总线 | `Disabled` + `bus==null` | **废弃** | — | → `BridgeLifecycleTest` · M4-T9 |
| BC-474 | | stop 注销静态 rejectionHolder 使旧实例不再产出拒绝回复 | 🔒 `consumeRejectionReply==null`、`stats.rejected` **不增长** | **废弃** | **D16** | 静态全局删除后**自然消解**，实测承载 `confirm/ConfirmServiceTest.kt:186`「BC-474 两个实例互不干扰（旧 rejectionHolder 静态串味已消除）」；装配唯一性另由 `BridgeAssemblerParityTest` 守护 |
| BC-475 | | agent 关闭重建后 consumeRejectionReply 为 null 且 senderStats 零回调 | 旧实例不产拒绝回复、不写统计（「未启用不统计」承诺） | **废弃** | **D16** | 同 BC-474；实测承载 `confirm/ConfirmServiceTest.kt:211`「BC-475 cancelAll 后旧实例不再接受裁决」 |
| BC-476 | | 确认超时秒数低于下限时被钳制 | 🔒 `clampedConfirmTimeoutMillis(0)==MIN_CONFIRM_TIMEOUT_SECONDS*1000==10000`、`(-30)` 同为 10000、`(120)==120000`，见 §4.9 | **移植**（该纯函数断言独立保留） | R3 | `ConfirmTimeoutClampTest` · M4-T8 |
| BC-477 | LarkBridgeRecoveringStateTest.kt | consumer 重启后状态进入 Recovering 并在退避后回 Running | 🔒 `health.restartCount=1` → `Recovering(attempt==1, reason 含 "consumer exited")` → 退避后 `Running` | **废弃** | D8 | → 新 `gateway.updatePolicy` 重连语义 · M4-T9 |
| BC-478 | | 非 Running 态（Idle）收到 health 不误置 Recovering | Idle 收 health → **保持 Idle** | **废弃** | — | → `BridgeLifecycleTest` · M4-T9 |
| BC-479 | LarkBridgeStartConfigTest.kt | openapi 传输层缺 appId 时进入 Stopped 且不启动 transport | 🔒 `Stopped.error=="appId/appSecret 未配置（openapi 传输层）"`、`startCount==0` | **废弃** | D15 | → `BridgeStartConfigTest` · M4-T9 |
| BC-480 | | openapi 传输层缺 appSecret 时进入 Stopped 且不启动 transport | 同上 | **废弃** | D15 | → `BridgeStartConfigTest` · M4-T9 |
| BC-481 | | cli 传输层 eventKey 为空时仍报 eventKey 未配置 | 🔒 `Stopped.error=="eventKey 未配置"` | **废弃**（cli 传输退役） | **D15** | **不承接**（`eventKey` 概念消失） |
| BC-482 | | openapi 传输层配置齐全时 eventKey 为空不阻断启动 | 🔒 `stateAtStart==Starting` → `Running`、`startCount==1`、日志含 `transport=openapi` + `appId=cli_test` | **废弃** | — | → `BridgeStartConfigTest` · M4-T9 |
| BC-483 | | cli 传输层配置齐全时正常启动 | 🔒 日志含 `transport=cli` + `eventKey=ev-key` | **废弃** | **D15** | **不承接** |

**废弃理由（映射原则⑤）**：4 个 `LarkBridge*IntegrationTest` 依赖旧 `LarkTransport` 接口 + cli/openapi 双传输层 + 旧 `LarkBridge` 门面。新架构统一走 `:lark-gateway` 的 SDK `LarkChannel`，引擎侧用 `SyntheticGateway` 测试替身，改写为 `BridgeRuntimeIntegrationTest`（M4-T9）。

**三条断言须从废弃文件中「抢救」移植**（不可随文件丢弃）：

1. **确认超时钳制**（BC-476，`clampedConfirmTimeoutMillis`）：`0` / `-30` 秒 → `MIN_CONFIRM_TIMEOUT_SECONDS*1000`（0 秒会让确认卡刚发即超时 = 等同拒绝），`120` → `120000` 不改写。此为**实测定稿纯函数**，移入新 confirm 域单测。
2. **Idle 态零资源占用 / recreate 不泄漏端口**（BC-467..471，D9）：语义移入 `StartStopLeakTest`（start→stop ×20 断言 threadCount 不增、`liveProcesses==0`、`cardStreamsOpen==0`、`scope.children.count()==0`）+ `TeardownCompletenessTest`（D20 十步关闭）。**threadCount 断言标 `TBD(用户裁决中)`**，见 §5.10。
3. **静态 rejectionHolder 注销**（BC-474/475，D16）：新架构删除静态全局改构造注入，此断言**自然消解**——实测承载 `confirm/ConfirmServiceTest.kt:186`（BC-474 两实例互不干扰）/`:211`（BC-475 cancelAll 后旧实例不再接受裁决）；装配唯一性另由 `BridgeAssemblerParityTest` 守护。

### 3.12 UI `feature-larkbridge`（67 方法 · 10 文件 · 44 移植 + 23 废弃）

| 编号 | 旧测试文件 | 测试方法 | 锁住的行为（含关键常量/字面值） | 三态 | 关联 D#/R# | 新架构落点（新测试名 + 里程碑 T#） |
|---|---|---|---|---|---|---|
| BC-484 | page/larkbridge/BridgeSecurityCardLogicTest.kt | prohibitDelete ineffective for workspace preset | 🔒 `isProhibitDeleteIneffective(workspace)==true` | 移植 | R3 | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-485 | | prohibitDelete ineffective for all preset | 🔒 `all` → `true` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-486 | | prohibitDelete effective for safe preset | 🔒 `safe` → `false` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-487 | | prohibitDelete effective for readonly preset | 🔒 `readonly` → `false` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-488 | | prohibitDelete effective for custom preset | 🔒 `custom` → `false` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-489 | | timeout upper bound caps at 3600 for large agent timeout | 🔒 `agentTimeout=120min` → 上限 `3600s`（`MAX_CONFIRM_TIMEOUT_SECONDS=3600L`） | 移植 | R3 | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-490 | | timeout upper bound uses agent timeout when smaller | 🔒 `10min` → `600s` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-491 | | timeout upper bound at exact boundary | 🔒 `60min` → `3600s`（边界不越） | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-492 | | timeout upper bound minimum 1 second for zero agent timeout | 🔒 `0` → `coerceAtLeast(1)==1s` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-493 | | timeout upper bound for 1 minute agent timeout | 🔒 `1min` → `60s` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-494 | | Disabled maps to Hidden | `ConfirmRuntimeStatus.Disabled` → `Hidden` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-495 | | Ready maps to Info with port | `Ready` → `Info`，message 含**端口** + `就绪` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-496 | | Unavailable maps to Alert with reason | `Unavailable` → `Alert`，含 reason + 🔒 `fail-safe deny` | 移植 | R3 | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-497 | | Unavailable alert message contains rejection warning | 🔒 Alert 文案含 `拒绝执行` | 移植 | — | `BridgeSecurityCardLogicTest` · M6-T13 |
| BC-498 | page/larkbridge/BridgeQuickCommandCardLogicTest.kt | builtInCommandDisplays lists every lib built-in command | 条目数 == `BuiltInQuickCommands.ALL.size`，含 `log-analysis` | 移植 | — | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-499 | | built-in log-analysis entry exposes name aliases and description | 🔒 `name=="日志分析"`、`aliasesText` 含 `/log`、description 非空 | 移植 | — | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-500 | | trigger hint for built-in log command mentions trigger and natural language | 🔒 hint 含 `/log` + `ai 日志分析` + `<参数>` | 移植 | — | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-501 | | trigger hint falls back to id-based trigger when no aliases | 🔒 无别名 → `/deploy` + `ai 部署` | 移植 | — | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-502 | | trigger hint uses id when name blank | name 空 → 回退 id | 移植 | — | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-503 | | fresh user persistence holds no built-in command ids | 空存档**不含**内置 id，但展示恒非空 | 移植 | — | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-504 | | user command CRUD never writes built-in ids into persistence by default | 常规 CRUD **不误带**内置 id | 移植 | R3 | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-505 | | builtInCommandRows pure built-in when no user config | 无用户配置 → `overridden==false`、`userIndex==-1` | 移植 | — | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-506 | | builtInCommandRows flags overridden and disabled from same-id user config | 同 id → `overridden==true`、effective 取用户配置 | 移植 | — | `BridgeQuickCommandCardLogicTest` · M6-T13 |
| BC-507 | | VM toggle built-in off persists same-id disabled config | 关内置 → 写**同 id** `enabled=false` | 移植 | — | `LarkBridgeViewModelTest` · M6-T13 |
| BC-508 | | VM restore default removes user override and falls back to built-in | 恢复默认 → 删同 id、回退内置 | 移植 | — | `LarkBridgeViewModelTest` · M6-T13 |
| BC-509 | page/larkbridge/ListConfigCommitTest.kt | commitListConfig same value short-circuits without side effects | 相同值 → `return false`，persist/runtime 计数 == **0** | 移植 | **D8** | `ListConfigCommitTest` · M6-T13 |
| BC-510 | | commitListConfig different value triggers persist and runtime | 变化 → `true`，state 同步更新，persist + runtime **各 1 次** | 移植 | **D8** | `ListConfigCommitTest` · M6-T13 |
| BC-511 | | VM updateQuickCommand with same value does not trigger recreate | 相同值 → recreate 计数 **0** | 移植 | **D8** | `LarkBridgeViewModelTest` · M6-T13 |
| BC-512 | | VM updateQuickCommand with different value triggers recreate exactly once | 变化 → **旧断言** recreate 恰 1 次；**新断言** `updatePolicy` 恰 1 次或标记需重启，**不再整桥重建断连** | 移植（**改断言**） | **D8** | `LarkBridgeViewModelTest` · M6-T13 |
| BC-513 | page/larkbridge/ResolveAgentWorkDirHintTest.kt | both empty shows guidance hint without error | 全空 → **非错误**，文案含 `请先配置` | 移植 | — | `ResolveAgentWorkDirHintTest` · M6-T13 |
| BC-514 | | single dir empty but whitelist non-empty shows first whitelist root | 回显白名单**首根** canonical | 移植 | — | `ResolveAgentWorkDirHintTest` · M6-T13 |
| BC-515 | | workDir under second whitelist root is valid (not error) | 第二根下 → **非错误** | 移植 | — | `ResolveAgentWorkDirHintTest` · M6-T13 |
| BC-516 | | workDir outside all roots shows error with all canonical roots listed | 越界 → 错误，**列出所有** canonical 根 | 移植 | R3 | `ResolveAgentWorkDirHintTest` · M6-T13 |
| BC-517 | | workDir empty with multiple roots shows first root with multi-root hint (S4) | 多根空 → 首根 + 🔒 `多根时取第一个` | 移植 | — | `ResolveAgentWorkDirHintTest` · M6-T13 |
| BC-518 | | tilde prefix shows error hint | 🔒 `~/projects` → 错误，含 `绝对路径` | 移植 | R3 | `ResolveAgentWorkDirHintTest` · M6-T13 |
| BC-519 | | valid subdirectory of single root shows canonical path | 🔒 text==`实际启动目录：<canonical>` | 移植 | — | `ResolveAgentWorkDirHintTest` · M6-T13 |
| BC-520 | page/larkbridge/UsageStatsByDayTest.kt | produces exactly seven buckets ending today | **7 桶**，末位今日、首位今日-6，空输入全零。**新实现改 `forEachIndexed`** | 移植 | **D17** | `UsageStatsByDayTest` · M6-T13 |
| BC-521 | | counts statuses per day and drops entries outside window | 按天分类计数 success/failure/skipped，窗口外丢弃（**总和==6**） | 移植 | **D17** | `UsageStatsByDayTest` · M6-T13 |
| BC-522 | | empty days between events stay zero | 中间空桶为零 | 移植 | — | `UsageStatsByDayTest` · M6-T13 |
| BC-523 | | custom days parameter controls bucket count | 🔒 `days=3` → 3 桶 | 移植 | — | `UsageStatsByDayTest` · M6-T13 |
| BC-524 | page/larkbridge/UsageStatsKindTest.kt | byKind groups by task kind with friendly labels | 🔒 COMMAND/SKILL/AGENT → `指令`/`快捷指令`/`Agent`，total **降序** | 移植 | — | `UsageStatsKindTest` · M6-T13 |
| BC-525 | | legacy entries without kind fall into unknown bucket | 🔒 `kind==null` → `未知` 桶（向后兼容旧审计行） | 移植 | R6 | `UsageStatsKindTest` · M6-T13 |
| BC-526 | | command kind maps to command label | 🔒 COMMAND → `指令` | 移植 | — | `UsageStatsKindTest` · M6-T13 |
| BC-527 | | empty entries yields empty byKind | 空输入 → byKind 空 | 移植 | — | `UsageStatsKindTest` · M6-T13 |
| BC-528 | core/data/EasyDataManagerLarkAgentTest.kt | default values when no record exists | 🔒 默认：`agentEnabled=false`、`backend=="claude"`、`prefix=="ai"`、`resetCommand=="新对话"`、`timeout==10L` 分钟、其余空串。**字面映射须进 `LegacyConfigImporterTest`** | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-529 | | write then read back all agent fields | DataStore 读写往返（backend/customTemplate/prefix/reset/workDir/systemPrompt/timeout） | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-530 | | cached accessors reflect persisted values after write | `cachedTextField` 同步可读、默认回退 | **废弃** | R6/D8 | → `ConfigRepositoryTest` · M4-T5 |
| BC-531 | core/data/EasyDataManagerLarkSkillsWhitelistTest.kt | whitelist default empty when no record exists | 🔒 DataStore key `lark_bridge_workspace_whitelist` 默认空 | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-532 | | whitelist write then read back preserves order | **保序**往返 | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-533 | | whitelist overwrite with empty clears | 空覆盖清空 | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-534 | | whitelist corrupted json degrades to empty | 🔒 损坏 JSON → 空（**不抛**） | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-535 | | skills default empty when no record exists | 🔒 key `lark_bridge_skills` 默认空 | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-536 | | skills write then read back full fields | `QuickCommandConfig` **全字段**往返 | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-537 | | skills corrupted json degrades to empty | 损坏 → 空 | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-538 | | prohibitDelete defaults true | 🔒 默认 `true`（**安全底线**） | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-539 | | prohibitDelete write then read back | 往返 | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-540 | | safetyPromptEnabled defaults true | 🔒 默认 `true` | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-541 | | safetyPromptEnabled write then read back | 往返 | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-542 | core/data/EasyDataManagerStaticRepliesTest.kt | default empty list when no record exists | 🔒 key `lark_bridge_static_replies` 默认空 | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-543 | | write then read back rules | `StaticReplyRule(trigger/reply/enabled)` 往返 | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-544 | | overwrite with empty list clears rules | 空覆盖清空 | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-545 | | corrupted json degrades to empty list | 损坏 → 空 | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-546 | core/larkbridge/LarkBridgeManagerMigrationTest.kt | fresh save without preset key triggers migration to safe | 🔒 无 preset key → 迁移为 `PRESET_SAFE`、迁移后 key 存在 | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |
| BC-547 | | migration sets banner notice flag | 迁移置**横幅标记** | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-548 | | migration log produced exactly once | 🔒 迁移日志 `permission preset migrated` **恰 1 次** | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-549 | | second call does not repeat migration | 二次调用**不重复**迁移 | **废弃** | — | → `LegacyConfigImporterTest` · M6-T12 |
| BC-550 | | user who explicitly chose preset skips migration | 已显式选 `workspace` → **不覆写**、不弹横幅 | **废弃** | R6 | → `LegacyConfigImporterTest` · M6-T12 |

**UI 4 个废弃文件改写指引（映射原则 + M7-T15）**：`EasyDataManagerLark*Test`(3 文件 18 方法) + `LarkBridgeManagerMigrationTest`(5 方法) 锁住的是旧 **DataStore 35 个 key** 的持久化语义。新架构配置改为单一 JSON 文档（`~/.easy_dev_toolbox/lark/bridge-config.json`），DataStore 键**全部废弃**，改写为 `LegacyConfigImporterTest`（T15：一次性导入旧 35 key，单 key 独立 `runCatching`、**不置标记可重试**、**旧 key 不删除**、preset 缺省迁移为 `safe`）。

**迁移时须保留的字面映射（进 `LegacyConfigImporterTest` 断言，即 R6「用户既有配置丢失」的验收标尺）**：`agentEnabled` 默认 `false`、`backend` 默认 `"claude"`、`prefix` 默认 `"ai"`、`resetCommand` 默认 `"新对话"`、`timeout` 默认 `10` 分钟、`prohibitDelete`/`safetyPromptEnabled` 默认 `true`、`permissionPreset` 缺省迁移为 `PRESET_SAFE`、损坏 JSON 降级为空。35 key 的**实测精确清单与 1:1 映射结论**见 §6.4。

**UI 6 个移植文件**：均为**纯函数 UI 逻辑**（禁删档失效判定、确认超时上限 `min(3600, agentTimeoutMinutes*60)`、确认状态三态映射、快捷指令卡展示/触发提示/内置行模型、列表提交短路、workDir 提示、使用统计聚合），随 feature 骨架（M6-T12）重建后 1:1 平移（M6-T13）。两处 D# 影响断言形态：

- **D8**（BC-509..512 `ListConfigCommit` 的 recreate 短路）：新架构对应「单一 `StateFlow`（🔴 **方案期望范式**；实际实现为 7 个独立 `MutableStateFlow`——`_draft:StateFlow<BridgeConfig>`（配置态的单一不可变 flow）+ `_issues`/`_applyResult`/`_startResult`/`_busy`/`_banner`/`_probe` 6 个一次性信号；设计裁定接受该偏离，理由见 `config-domain-spec.md §15 #14`） + 热应用/需重启二分 + `gateway.updatePolicy()`」——**短路语义保留**（相同值零副作用），但「值变化触发 recreate」须改为「值变化触发 `updatePolicy` 或标记需重启」，**不再整桥重建断连**。
- **D17**（BC-520/521 `UsageStatsByDay` 的 O(n²) `indexOf`）：新架构改 `forEachIndexed`，**聚合结果断言原样保留**（7 桶、总和==6、窗口外丢弃）。

**⚠️ `rememberChartProgress()` 不可视为存活 API**：BC-520..527 的使用统计聚合结果在旧 UI 由 `feature-larkbridge/.../page/larkbridge/chart/ChartCanvas.kt:18` 的 `internal fun rememberChartProgress()` 消费渲染，该函数**随 M2 删除而消失**。M6-T13 图表重写时**必须重新实现**，不能假设 `:ui` 侧存在同名存活 API。对应 UsageStats / chart 旧测试标注「**M6 重新实现后恢复**」，见 §6.3。

---

## 4. 实测定稿型断言清单

> 这些是社区/线上实测踩坑后固化在旧测试里的**精确字面值**，方案 R3 点名「禁止重写只许平移」。新架构平移后必须**逐字符比对**，字面值不得漂移。本节所有数值均由本次 T1 任务**直接读取旧模块源码复核**，与调研报告的差异已在各小节末尾以「⚠️ 报告校正」标出。
>
> 三块 🔒 **禁止重写、只许平移**的实测定稿代码精确坐标（任务书定稿口径）：
> - `agent/AgentBackend.kt` **341 行**（`ClaudeBackend` :49-144，其中 `--mcp-config` :106-111、`--permission-prompt-tool` :110-111；`CopilotBackend` :196-224，`-s` :206、`--allow-tool` :207-218；`object AgentBackends` :257-340，分层安全提示 `safetyPromptSuffix` :286-297，`presetAllowsShell` :306-312）
> - `confirm/PermissionServerLauncher.kt` **247 行**（`MAIN_CLASS = "com.jiaoay.lark.bridge.MainKt"` 在 :175，classpath 烘焙 `resolveClasspath` :208-220，`launchCommand` 纯函数 :161-162，Windows ANSI 写脚本 :165）
> - `filemsg/FileExtractor.kt` **179 行**（zip-slip 双重校验 `safeChild` :169-179）

### 4.1 四后端 argv 渲染（BC-001..018 · 源 `AgentBackend.kt` 341 行🔒）

| 后端 / 场景 | 精确 argv 字面值 |
|---|---|
| **claude 首轮** | `["-p", <prompt>, "--append-system-prompt", <sp>, "--allowedTools", "Read,Grep", "--session-id", <uuid>]` |
| **claude 续轮** | `["-p", <prompt>, "--resume", <uuid>]`（**不带** `--append-system-prompt`、**不带** `--allowedTools`） |
| claude + mcpConfig 非空 | 在 `--allowedTools` 之后追加 `["--mcp-config", <json>, "--permission-prompt-tool", "mcp__easydev-perm__permission_prompt"]`（源码 :106-111） |
| claude + `streamJson=true` | 追加 `["--output-format", "stream-json", "--verbose"]`（源码 :121-126） |
| claude 条件顺序 | `-p` → `--append-system-prompt`（sp 非空白时）→ `--allowedTools`（工具串非空白时）→ `--mcp-config`/`--permission-prompt-tool` → `--output-format`/`--verbose` → `--session-id`/`--resume` |
| **qoder 首轮** | `["-p", <prompt>, "--system-prompt", <sp>, "--tools", "Read", "Grep", "--session-id", <s>]`——`--tools` 的值按逗号**拆成多个独立 argv 元素** |
| **qoder 续轮** | `["-p", <prompt>, "-r", <s>]` |
| **copilot 首/续轮** | `["-p", <prompt>, "-s", "--session-id", <uuid>]`——`-s`=silent（源码 :206），**首续轮均带 `--session-id`**（与 claude/qoder 两段式不同） |
| copilot 工具映射（三态） | 空工具串 → **无**权限参；`*` 或 `all` → `--allow-all-tools`；`shell(git), write` → `["--allow-tool", "shell(git),write"]`（源码 :207-218） |
| **custom 模板** | `PROMPT_TOKEN="{prompt}"`、`SESSION_TOKEN="{session}"`。含 `{session}`：首轮**整体移除**该 token、续轮**替换**为真实 id；`{prompt}` **必填**否则配置校验失败；无 `{session}` → 无会话降级 |
| 特殊字符 prompt | 含空格 / 引号 / 换行的 prompt 恒为**单一 argv 元素**（argv 程序调用语义，不经 shell 拆分） |
| 未知 backend | 工厂回退 `ClaudeBackend`；可用性检测返回 `false` |
| 可执行解析 | `findInPath` 命中 PATH → **绝对路径**；PATH 缺失 → 回退 `~/.local/bin` 等 fallback 目录 |
| 常量 | `ClaudeBackend.PERMISSION_MCP_SERVER_KEY = "easydev-perm"`；`PERMISSION_TOOL_REF = "mcp__easydev-perm__permission_prompt"` |

**⚠️ 报告校正**：调研报告 §2.1 的 argv 表**漏了 claude `streamJson=true` 分支**（追加 `--output-format stream-json --verbose`，源码 :121-126），且旧测试**无任何方法覆盖该分支**。⇒ 平移后 M4-T8 的 `AgentBackendArgvTest` 必须**补一条新断言**（建议编号 BC-018a）锁住该分支，否则流式进度卡（D1 的 CardKit 流式）依赖的 stream-json 输出格式将无守护。

### 4.2 权限档位 5 档映射（BC-031..042 / BC-115..117 · 源 `PermissionPreset.kt` 110 行）

档位常量：`PRESET_READONLY="readonly"`、`PRESET_SAFE="safe"`、`PRESET_WORKSPACE="workspace"`、`PRESET_ALL="all"`、`PRESET_CUSTOM="custom"`；🔒 `DEFAULT_PRESET = PRESET_SAFE`。

| 档位 | claude 工具集（精确字面值） | qoder（全小写） | copilot |
|---|---|---|---|
| `readonly` | `Read,Grep,Glob,LS,WebFetch,WebSearch` | `read,grep,glob,ls,webfetch,websearch` | `read,grep,search,fetch` |
| `safe`（== DEFAULT） | `Read,Grep,Glob,LS,Edit,Write,NotebookEdit,WebFetch,WebSearch`（**不含 Bash**） | `read,grep,glob,ls,webfetch,websearch,edit,write`（**无 shell**） | `read,grep,search,fetch,write,edit` |
| `workspace` | `Read,Grep,Glob,LS,WebFetch,WebSearch,Edit,Write,NotebookEdit,Bash` | `read,grep,glob,ls,webfetch,websearch,edit,write,shell` | `read,grep,search,fetch,write,edit,shell` |
| `all` | `*` | 🔒 **`read,grep,glob,ls,webfetch,websearch,edit,write,shell`**（== `QODER_WORKSPACE`，qoder **无全量通配**） | `*` → 渲染为 `--allow-all-tools` |
| `custom` | `allowedTools.trim()` 原样透传；**空白时 fail-safe 降级为 `safe` 档工具集**（M10 修复，**不是** `*`） | 同左 | 同左 |

`resolveTools(backend, preset, allowedTools)` 判定顺序（**顺序本身是契约**）：

1. `backend == "custom"` → 恒返回 `""`（**优先于**任何档位判定；custom 后端 argv 不追加任何工具参数）
2. 否则按 `preset`：`custom` → `allowedTools.trim().ifBlank { safeTools(backend) }`；`readonly`/`safe`/`workspace`/`all` → 对应工具集；**未识别档位串 → `safeTools(backend)`**（fail-safe 回退 safe）

**⚠️ 报告校正**（两处，均会导致实现写错）：
1. 报告 §2.2 权限档位表**缺 qoder 的 `all` 档**，且未说明 qoder `all` **不等于** `*` 而是等于 `QODER_WORKSPACE`。若照「all → `*`」实现，qoder 后端会收到非法工具串。
2. 报告 §2.2 把两件**不同的事**混在 custom 一行里：① `backend=custom` → 恒空串（判定顺序第 1 步）；② `preset=custom` 且 `allowedTools` 空白 → fail-safe 降级 `safe`（判定顺序第 2 步）。二者触发条件与结果都不同，BC-039 锁 ①、BC-128 锁 ②，**不可合并断言**。

### 4.3 分层安全提示（BC-019..030 · 源 `AgentBackend.kt:286-297` `safetyPromptSuffix`🔒）

精确字面值（**逐字符**，含全角标点与换行结构）：

```
\n\n[安全约束 - 不可违反]\n
- 禁止执行任何删除文件/目录的操作（rm、rmdir、unlink、shutil.rmtree、git clean、Remove-Item、del 等）   ← 仅 prohibitDelete=true
- 禁止清空目录内容                                                                                    ← 仅 prohibitDelete=true
- 禁止操作工作区白名单外的任何路径                                                                     ← 恒有
- 禁止执行任意 shell 命令（除非明确授权）                                                              ← 仅 presetAllowsShell(preset)==false
- 违反以上约束的操作将被系统自动拒绝\n                                                                  ← 恒有（末句，无换行结尾）
```

- 测试用哨兵常量：`SHELL_BAN` 匹配子串 `禁止执行任意 shell`；`DELETE_BAN` 匹配子串 `禁止执行任何删除文件/目录的操作`；`PATH_BAN` == `禁止操作工作区白名单外的任何路径`
- 🔒 `presetAllowsShell(preset)`：`workspace` / `all` / `custom` → `true`（**不含** SHELL_BAN）；其余（`readonly` / `safe` / 未知档位串）→ `false`（**含** SHELL_BAN）
- `prohibitDelete=false`：去除 DELETE_BAN **与** `禁止清空目录内容` 两句，但**保留** PATH_BAN
- `safetyPromptEnabled=false` 且 sp 空 → argv **不含** `--append-system-prompt`；sp 非空 → 只透传用户 prompt 原值
- `safetyPromptEnabled=true` 且 sp 空 → argv 含**纯安全段**；sp 非空 → 顺序为「用户 prompt → 安全段」（安全段**追加在后**）
- `safetyPromptSuffix(preset, prohibitDelete)` 是**纯函数**（`internal`），qoder 后端走**同一**逻辑，不分后端特化

### 4.4 claude `--mcp-config` / `--permission-prompt-tool` 格式（BC-108..111 / BC-206..207）

- 🔒 `PERMISSION_TOOL_REF == "mcp__easydev-perm__permission_prompt"`（由 `PERMISSION_MCP_SERVER_KEY="easydev-perm"` 拼成 `mcp__<key>__permission_prompt`）
- 回填 mcpConfig 后 argv：`--permission-prompt-tool` 的值**精确等于**上述常量
- `--mcp-config` 的 JSON 内含 permission-server 启动参数：`--chat <chatId>`（测试样本 `oc_test`）、`--sender <openId>`（测试样本 `ou_user`）
- mcpConfig 与旧静态命令**并存**时：mcpConfig **优先**，且 `--permission-prompt-tool` 在 argv 中**只出现 1 次**（不重复引用）
- provider 返回 `null` → argv **不追加** `--permission-prompt-tool`
- **D16 影响**：provider 的注入方式由静态全局改**构造注入**，断言载体变化但上述字面值不变

### 4.5 `PermissionServerLauncher` 脚本烘焙（BC-092..107 · 源 247 行🔒）

常量（精确字面值）：

| 常量 | 值 | 源码行 |
|---|---|---|
| `MAIN_CLASS` | `"com.jiaoay.lark.bridge.MainKt"` | :175 |
| `SCRIPT_BASENAME` | `"easydev-permission-server"` | :178 |
| `SCRIPT_DIRNAME` | `"easydev-toolbox-perm"` | :181 |
| `SELF_CHECK_TIMEOUT_MILLIS` | `30_000L` | :184 |
| `TAIL_CHARS` | `400` | :187 |

纯函数与行为：

- 🔒 `launchCommand(scriptPath, windows)` == `if (windows) listOf("cmd.exe", "/d", "/c", scriptPath) else listOf("/bin/sh", scriptPath)`（源码 :161-162）
- 🔒 `scriptCharset(windows)` == `if (windows) Charset.forName(System.getProperty("native.encoding", "UTF-8")) else Charsets.UTF_8`（源码 :169-170）；Windows 脚本按 ANSI **字节**写出（:63-67 `file.writeBytes(script.toByteArray(scriptCharset(windows = true)))`，:165 附近）
- 🔒 `sh(value)` == `"'" + value.replace("'", "'\\''") + "'"`（源码 :233）；样本 `/tmp/it's` → `'/tmp/it'\''s'`
- 🔒 `commandToken(path)` == `if (path.any { it.isWhitespace() }) "\"$path\"" else path`（源码 :238-239）；结果**长度 <512**、**不含** `File.pathSeparator`、**不含** `-cp`（classpath 只烘焙进脚本体，规避 Windows 命令行长度上限）
- 🔒 `resolveClasspath()`（源码 :208-220 / :213-227）= `System.getProperty("java.class.path")` 拆分 ∪ 本类 `codeSource` 路径，用 `LinkedHashSet` **去重保序**，再以 `File.pathSeparator` join；结果**非空**且**含本类 codeSource**
- 🔒 `resolveJavaBin()`（源码 :200-205）= `$JAVA_HOME/bin/java`（Windows 加 `.exe`），`JAVA_HOME` 缺失或文件不存在 → 回退字面量 `"java"`
- 🔒 子命令参数串（源码 :138-146）= `"permission-server --bus-port " + busPort` + 可选 `" --work-dir " + sh(workDir)` + 每个白名单根 `" --workspace-whitelist " + sh(root)`
- 脚本内容**纯 ASCII**（所有字符 `code < 128`）
- `selfCheck` 返回文案（精确字面值）：脚本缺失 → `"启动脚本不存在：${script.path}"`；超时 → `"自检超时（>${timeoutMillis}ms 未退出）"`；非零退出 → `"自检退出码 $exit：${output.take(TAIL_CHARS)}"`（实测样本含 `自检退出码 1` 与 `ClassNotFoundException`）；拉起抛异常 → `"自检拉起失败：..."`。**四种情况均返回 `Failed`，一律不抛异常**

**⚠️ 报告校正**：报告 §2.5 把 Windows 拉起写作 `["cmd.exe", "/d", "c", "C:\\tmp\\perm.bat"]`（**漏了 `/c` 的斜杠**），源码实为 `"/c"`；报告 §1.2 的表格中该处是**正确**的，属 §2.5 笔误。本文件以源码为准（BC-097 已按 `"/c"` 写）。

### 4.6 Windows `.cmd`/`.bat` spawn 包装（BC-208..211 · `AgentExecutorTest`）

- `spawnCommand`：`windows=true` 且扩展名 `.cmd` / `.bat` / `.CMD`（**大写同样命中**）→ `["cmd.exe", "/d", "/c", <exe.absolutePath>, ...原参数]`
- `.exe` 与 Unix 平台路径 → **不包装**
- `executableFile == null` → 回退**裸可执行名**（如 `"claude"`），不包装
- 与 §4.5 `launchCommand` 的区别：`launchCommand` 包的是**脚本**，`spawnCommand` 包的是**可执行文件**；两者共用 `["cmd.exe","/d","/c",...]` 前缀形态，新架构应统一到 `ProgramLauncher`（M4-T6）后**分别保留两条断言**

### 4.7 zip-slip 双重校验（BC-289..292 / BC-295 / BC-298 · 源 `FileExtractor.kt` 179 行🔒一行不改）

🔒 `safeChild(destDir, entryName)` 精确逻辑（源码 :169-178）：

```kotlin
if (entryName.isBlank()) return null
val normalized = entryName.replace('\\', '/')
if (normalized.startsWith("/") || normalized.contains("..")) return null     // 第一重：字面校验
val target = File(destDir, normalized)
return runCatching {
    if (!target.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) null else target   // 第二重：canonical 校验
}.getOrNull()
```

- 拒绝清单：`/etc/passwd`（绝对路径）、`../x.log`（穿越）、`a/../../x.log`（嵌套穿越）、空白串
- 放行清单：`a/b.log`
- `ZipEntry("../escape.log")` → `files.isEmpty()` 且**磁盘上不留任何文件**
- 🔒 `isArchive(fileName)`（源码 :24-28）：`.zip` / **`.tar`** / `.tar.gz` / `.tgz` / `.gz` → `true`（**大小写不敏感**，`lowercase()` 后比较，故 `.TGZ` 亦真）；`.log` / `.xlog` → `false`
- 🔒 tar 解析常量：`TAR_TYPEFLAG_OFFSET = 156`、`TAR_BLOCK_SIZE = 512`；typeFlag **非 `'0'` 且非 `' '` 的条目一律跳过**（符号链接 / 硬链接是逃逸面）
- 扩展名不在白名单（如 `virus.exe`）→ 🔒 `errorCode == "file_extension_rejected"`；默认白名单字面值 `.log,.txt,.xlog,.alog,.zip,.tar,.tar.gz,.tgz,.gz`
- 下载失败 → 🔒 `errorCode == "file_download_failed"`，`tmp-*` 临时目录**用完即删**

**⚠️ 报告校正**：报告 §2.7 的 `isArchive` 只列了 `zip`/`tar.gz`/`TGZ`，**漏了 `.tar` 与 `.gz` 两个独立后缀**；也未记录 tar typeFlag 白名单（`'0'`/`' '`）这条**安全相关**的实测约束。平移时二者都必须保留。

### 4.8 kill 升级链 / 超时 / 取消（BC-168 / BC-201 / BC-147..148）

**新架构 kill 升级链（精确时序，M4-T6 `ProgramLauncher` 必须实现）**：

```
取消或超时触发
  → SIGTERM（Process.destroy()）
  → 等 3s
  → 仍存活则 SIGKILL（Process.destroyForcibly()）
  → 等 2s
  → 仍存活则放行并记 exitCode = -1
整个 kill 序列包在 NonCancellable 内（协程已取消仍须完成杀进程）
```

- `CancellationKillsProcessTest`（§7）断言：真实 `sleep 60` 子进程，取消后 **5s 内 `isAlive==false`**
- 🔒 超时数值：执行器 `timeoutSeconds=1` **覆盖**全局 60 → `errorCode=="task_timeout"`，summary 含 `任务超时（>1s）`；排队超时 → `errorCode=="task_queue_timeout"`
- 🔒 `AgentExecutor.ENGINE_TIMEOUT_BUFFER_SECONDS = 30L`（引擎层兜底超时 = 内部超时 + 30s 缓冲）
- 🔒 超长输出截断：`MAX_OUTPUT_CHARS = 4000`，`summary.length < 4200`，含子串 `超长截断`，**保留头尾**
- 🔒 `MAX_PROMPT_CHARS = 8000`（prompt 超长截断）、`STDERR_TAIL_CHARS = 200`（失败信息携带的 stderr 尾部）、`CMD_ARG_SUMMARY_CHARS = 60`（命令摘要中单个 argv 元素的截断长度）
- 旧 `AgentProcessRunner.JOIN_TIMEOUT_MS = 2_000L`（源码 :733）—— **D9 泄漏点**，新架构改协程 join，该常量**不再存在**

**⚠️ 重要：kill 升级链是「新增行为」而非「平移」**。实测旧 `AgentProcessRunner`（源码 :667-735）的 `finally` 块内**只有** `if (process.isAlive) process.destroyForcibly()`（:708）——即**直接 SIGKILL，无 SIGTERM 宽限期，无 3s/2s 升级链**。旧测试的两条相关断言（BC-168「超时终止子进程」、BC-201「停止指令终止在途进程」）**只锁 `isAlive==false`**，不锁升级时序。⇒ 新架构的 `SIGTERM→3s→SIGKILL→2s→放行 -1` 属方案「全功能平移对照」C 段「必须重建」范畴，**证据等级为方案设计而非旧代码实测**；`CancellationKillsProcessTest` 的 5s 阈值断言是**新增闸门**，不与任何旧断言冲突。

### 4.9 环境变量白名单（BC-419..421 · 源 `SandboxedProcessFactory.kt`）

🔒 `BASE_ENV_KEYS`（源码 :57-61，**11 键**，顺序即字面值）：

```
"PATH", "HOME", "LANG", "LC_ALL", "TZ",
"http_proxy", "https_proxy", "no_proxy",
"HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY"
```

🔒 `WINDOWS_REQUIRED_ENV_KEYS`（源码 :68-71，**11 键**）：

```
"SystemRoot", "SystemDrive", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
"APPDATA", "LOCALAPPDATA", "COMSPEC", "PATHEXT", "TEMP", "TMP"
```

🔒 `effectiveBaseKeys(isWindows)`（源码 :74-75）= `if (isWindows) BASE_ENV_KEYS + WINDOWS_REQUIRED_ENV_KEYS else BASE_ENV_KEYS`

- **零回归锚点**：`effectiveBaseKeys(isWindows=false) == BASE_ENV_KEYS` **逐键相等**（BC-420）
- Windows：`== BASE_ENV_KEYS + WINDOWS_REQUIRED_ENV_KEYS`，共 **22 键**（BC-421）
- 语义：白名单是**过滤**而非**追加**——宿主进程环境只有命中白名单的键才透传给 agent 子进程（沙箱化）
- 冲突警示：方案新配置 `workspace.envAllowlist` 默认只有 **8 键**，缺 `http_proxy`/`https_proxy`/`no_proxy` 三个小写变体，且无 Windows 平台化追加逻辑的对应表述 → 见 §9 待裁定点 **P7**

**其他实测定稿超时/钳制常量**：

- 🔒 `MIN_CONFIRM_TIMEOUT_SECONDS = 10L`（源 `LarkBridge.kt:472`）；`clampedConfirmTimeoutMillis(seconds) = seconds.coerceAtLeast(10L) * 1000`（:478-479）
  - `clampedConfirmTimeoutMillis(0) == 10_000L`；`(-30) == 10_000L`；`(120) == 120_000L`（BC-476）
  - 理由：0 秒会让确认卡**刚发出即超时** = 等同拒绝，用户永远无法放行
- 🔒 UI 侧上限 `MAX_CONFIRM_TIMEOUT_SECONDS = 3600L`（源 `BridgeSecurityCard.kt:34`）；`resolveConfirmTimeoutUpperBound(agentTimeoutMinutes) = minOf(3600L, (agentTimeoutMinutes * 60).coerceAtLeast(1L))`（:40-41）
  - 边界：`120min→3600`、`60min→3600`、`10min→600`、`1min→60`、`0→1`（BC-489..493）
- `ConfirmBus` 超时由**构造参数** `timeoutMillis` 驱动（测试用 500ms，断言 elapsed<2000），**非硬编码**（BC-112）

### 4.10 `~` 展开（BC-393..399 · `PathExpansionTest`）

- `~` → `System.getProperty("user.home")`
- `~/work` → `$home/work`（POSIX 正斜杠）
- `~\work` → `$home\work`（Windows 反斜杠**同样展开**）
- 🔒 `~root/home`（`~user` 形态）→ **原样不展开**（安全：不解析他人主目录）
- 无前缀路径**原样返回**：`/opt/data`、`relative/path`、`C:\work`
- 🔒 `isAbsolute(p) == java.io.File(p).isAbsolute` **逐路径一致**（不自造平台判定逻辑）：`/opt/data` 绝对；`relative/path`、`C:\work` 在 Unix 上相对

### 4.11 审计字段 schema（BC-299..318 · 实测定稿）

🔒 `AuditEntry` **11 字段**（源 `AuditLogger.kt:18-38`，字段名与可空性即 schema）：

| 字段 | 类型 | 备注 |
|---|---|---|
| `ts` | `Long` | 毫秒时间戳 |
| `chatId` | `String` | |
| `senderOpenId` | `String` | |
| `prompt` | `String` | 截断至 `PROMPT_SUMMARY_CHARS` |
| `command` | `String` | 截断至 `CMD_SUMMARY_CHARS` + **换行压平** |
| `status` | `String` | |
| `errorCode` | `String? = null` | 可空，`encodeDefaults=false` 时缺键 |
| `exitCode` | `Int? = null` | 可空 |
| `elapsedMillis` | `Long` | |
| `backend` | `String? = null` | 可空（后加字段，旧行无此键） |
| `kind` | `String? = null` | 可空（后加字段，旧行无此键） |

🔒 常量：`FILE_NAME = "audit.jsonl"`（:75）、`PROMPT_SUMMARY_CHARS = 200`（:78）、**`CMD_SUMMARY_CHARS = 60`（:81）**

- 写用 `Json { encodeDefaults = false }`（:53）→ 可空字段为 null 时**不写键**
- 读用 `Json { ignoreUnknownKeys = true }`（:93）→ **未知键不丢弃该行**、损坏行跳过
- `readAll` 先做 `~` 展开再按行解析
- 向后兼容契约：旧格式 JSON 行（无 `backend` / 无 `kind` 键）decode 后对应字段为 `null`（BC-306 / BC-308），UI 侧 `kind==null` 落入 `未知` 桶（BC-525）

🔒 `SenderStatsStore` 常量（源 `stats/SenderStatsStore.kt`）：`FILE_NAME = "sender_stats.jsonl"`（:108）、`CONTENT_SUMMARY_CHARS = 120`（:111）、`TYPE_MESSAGE = "MESSAGE"`（:113）、`TYPE_REJECTED_WHITELIST = "REJECTED_WHITELIST"`（:114）、`TYPE_REJECTED_BLACKLIST = "REJECTED_BLACKLIST"`（:115）

🔒 `ConfirmService` 常量：`INPUT_SUMMARY_CHARS = 120`（:118）、`SHORT_ID_CHARS = 4`（:125）

**⚠️ 报告校正**：报告 §2.11 的审计 schema **漏了 `CMD_SUMMARY_CHARS = 60`**（源码 :81 实测存在，且 BC-303「命令摘要截断与换行压平」正依赖它）。平移时若只按 200 实现，命令摘要会超长 4 倍。

### 4.12 `TaskKind` 枚举与回复类型前缀（BC-319..324 / BC-387..392）

- 🔒 `enum class TaskKind { COMMAND, SKILL, AGENT }`（源 `model/TaskResult.kt:12`）—— `TaskKind.entries == ["COMMAND","SKILL","AGENT"]`，**顺序固定**（序列化按 name 故顺序不影响 JSON，但 UI 排序与 `ordinal` 依赖它）
- **`SKILL` 必须保留**：这是「快捷指令」与「Agent 直问」在审计/统计/UI 三处区分的唯一载体，删掉即丢失 BC-524..526 的分组能力
- 默认 `kind == COMMAND`；`success`/`failure`/`skipped` 三个工厂可显式指定 kind；`copy()` **不丢** kind（引擎补 elapsedMillis 路径）
- 🔒 `RiskLevel` 枚举顺序：`LOW, MEDIUM, HIGH, FORBIDDEN`（源 `confirm/RiskClassifier.kt:19-28`）；FORBIDDEN 的 rule 形态 `"forbidden pattern: ${pattern.pattern}"`（:99）

🔒 回复文案精确字面值（源 `reply/LarkResponder.kt:51-72`）：

| 场景 | 首行字面值 |
|---|---|
| AGENT 成功 | `**🤖 Agent · ✅ 任务完成**` |
| SKILL 成功 | `**🧩 快捷指令 · ✅ 任务完成**` |
| COMMAND 成功 | `**⚡ 指令 · ✅ 任务完成**` |
| 任意类型失败 | `**<类型标签> · ❌ 任务失败**` |
| 任意类型跳过 | `**<类型标签> · ⏭️ 已跳过**` |

- 排版：`lines[0]` = 上述首行；`lines[1]` = `result.summary`；`lines[2]` = `` 错误码：`<code>` ``（反引号包裹，仅 errorCode 非空时）；`lines[3]` = `耗时：<x.x>s`（`elapsedMillis / 1000.0`，如 `耗时：1.5s`，仅 `elapsedMillis > 0` 时）
- `kindLabel` 映射：`AGENT→"🤖 Agent"`、`SKILL→"🧩 快捷指令"`、`COMMAND→"⚡ 指令"`
- `messageId` 为空 → 走 `send` 通道（`replies` 空、`sends` 有值），**仍带类型前缀**
- 无处可回（非 P2P 且 chatId 空白）→ **静默丢弃**（源码 :44）。**新架构须改为可观测**：D18 要求该丢弃点加计数器

### 4.13 errorCode 全集（实测 grep 汇总 · 新旧必须逐一对齐）

| errorCode | 触发条件 | 关联 BC |
|---|---|---|
| `agent_backend_unavailable` | 后端可执行文件不可用，**不重试** | BC-169 |
| `agent_busy` | 同 chat 超过排队上限（旧 `queueLimit=2`） | BC-167 |
| `agent_config_invalid` | custom 后端模板无效 | BC-173 |
| `agent_empty_output` | agent 输出为空 | — |
| `agent_timeout` | agent 内部超时 | BC-168 |
| `agent_workspace_missing` | 工作区域未配置 | BC-196 |
| `agent_exit_<code>` | 🔒 **动态形态**，如 `agent_exit_1`（源 `AgentExecutor.kt:487`：`"agent_exit_${outcome.exitCode}"`） | BC-170 |
| `command_agent_disabled` | agent 未启用（**优先于** `command_confirm_unavailable`） | BC-224 / BC-236 / BC-252 |
| `command_confirm_unavailable` | 需确认技能但确认链路不可用（fail-safe 阻断） | BC-248 / BC-249 / BC-253 |
| `command_not_found` | `run_skill` 未知技能 | BC-257 |
| `command_template_empty` | promptTemplate 为空 | — |
| `file_agent_disabled` | 文件消息但 agent 未启用 | — |
| `file_download_failed` | 下载失败 | BC-298 |
| `file_extension_rejected` | 扩展名不在白名单 | BC-295 |
| `task_error` | 执行器抛异常兜底 | BC-140 |
| `task_queue_timeout` | 排队等待超时 | BC-144 |
| `task_timeout` | 任务超时 | BC-147 |

**共 17 个**（其中 1 个为动态模板）。新架构 lib 侧定义**同构** `BridgeErrorCode`（不得依赖 `:core` 的 `JErrorCode`，因 lib 是 jvmTarget 11 而 `:core` 是 21），feature 侧做 `BridgeErrorCode → JErrorCode` 映射。**这 17 个字面值一个都不能少、不能改名**——它们已落进用户的 `audit.jsonl` 历史文件，且 BC-525 的 `未知` 桶逻辑依赖旧行可解析。

### 4.14 卡片结构（BC-455 / BC-456 / BC-460..464 / BC-335..339 · SDK吸收但字面约束须进 `LarkPolicyMapperTest`）

- 🔒 卡片必须 `schema == "2.0"` + `body.elements`。1.0 结构的 markdown **仅支持子集**，`####` 标题 / 表格 / 引用**不渲染**（线上实测原样显示星号与竖线）
- 🔒 markdown 元素：`body.elements[0].tag == "markdown"`
- 🔒 `msgType(markdown = true) == "interactive"`；`msgType(markdown = false) == "text"`
- 🔒 `replyBody` / `sendBody`：`msg_type == "interactive"`、`content` 字符串内含 `"schema":"2.0"`、`sendBody` 带 `receive_id`
- 🔒 button 回调必须走 **`behaviors` 数组**（元素 `type == "callback"`），**禁止 `action` 包裹属性**——否则飞书回 `230099 parse card json err: unknown property: action`（实锤联调日志）
- 🔒 `ProgressCardRenderer.Limits`（源码 :18-21）三层截断：`maxEntries = 12`、`entryMaxChars = 200`、`cardMaxChars = 5000`（默认值；测试用 `Limits(12, 200, 3000)` 断言 `body.length <= 3200`）
- 🔒 进度卡文案与配色（BC-335..337）：受理含 `已受理` + header 色 `blue`；进行中含 `已运行 35s` + 图标 `💭`（thinking）/ `🔧`（tool_use）/ `✅`（tool_result 成功）；终态 DONE → `已完成` + `green`，FAILED → `失败` + `red`
- 单条超长截断加省略号 `…`（BC-339）
- **D1 影响**：旧实现用**消息编辑**做流式（D1 缺陷），新架构改 CardKit `streaming_mode`。上述**卡片结构字面值全部保留**，但「更新方式」的断言改由 `CardThrottleTest` / `SegmentRotationTest` / `Stream10MinRotationTest`（§7）承担
- **R7 六层防护数值**（新增，旧测试无覆盖）：单卡 30KB 上限（主动按 24KB 预算分段）、单卡更新 10 次/秒上限（节流至 4 次/秒 = 250ms 间隔，留 60% 余量）、10min 自动关流（主动 9min 轮换）、卡片实体 14 天过期且仅可发一次（每次轮换**新建**实体，不复用 `card_id`）、流式期间无法响应卡片回传（`200810` ⇒ 流式卡内**禁放任何交互组件**，确认/裁决一律用**独立非流式卡**）、同群 5 QPS（`RateGovernor` 每 chat 4 rps + 全局 40 rps）

### 4.15 MCP permission server 协议常量（BC-058 / BC-059 / BC-060..069）

源 `confirm/McpPermissionServer.kt`，🔒 精确字面值：

| 常量 | 值 | 源码行 |
|---|---|---|
| `jsonrpc`（响应字段） | `"2.0"` | :54 |
| `METHOD_INITIALIZE` | `"initialize"` | :220 |
| `METHOD_TOOLS_LIST` | `"tools/list"` | :221 |
| `METHOD_TOOLS_CALL` | `"tools/call"` | :222 |
| `TOOL_NAME` | `"permission_prompt"` | :223 |
| `PROTOCOL_VERSION` | `"2024-11-05"` | :224 |
| `SERVER_NAME` | `"easy-dev-toolbox-permission"` | :225 |
| `SERVER_VERSION` | `"1.0"` | :226 |
| `EXIT_DENY` | `3` | :229 |
| `SOCKET_TIMEOUT_MILLIS` | `5 * 60_000`（= 300000） | :232 |

🔒 `reason` 字面值与**匹配方式**（两种方式不可混用）：

| 场景 | reason 值 | 断言方式 |
|---|---|---|
| FORBIDDEN 风险策略拒绝 | `"forbidden by risk policy (${classification.rule})"`（:137） | **contains** `forbidden by risk policy` |
| LOW 自动放行 | `"auto-approved: low risk (${classification.rule})"`（:139） | **contains** `auto-approved` |
| 总线路径用户拒绝 | `"denied by user"` | **精确相等** |
| 总线路径用户放行 | `"approved"` | **精确相等** |

- `behavior` 字段读自 `result.behavior`，取值只有 `"allow"` / `"deny"`
- 退出码语义：允许 → `0`；拒绝 → `EXIT_DENY == 3`
- **fail-safe deny**：总线不可达（测试用 `busPort=1`）→ `EXIT_DENY` + `behavior=="deny"`（BC-059）
- `TOOL_NAME` 与 §4.4 的 `PERMISSION_TOOL_REF` 的关系：`mcp__` + `easydev-perm`（server key，**不是** `SERVER_NAME`）+ `__` + `permission_prompt`。**注意 server key 与 SERVER_NAME 是两个不同的字符串**，平移时极易写错


---

## 5. SDK 2.8.5 实测导致的断言调整

> 本节 11 条结论均由 **M1 Spike 的源码级实测**确认（证据见同目录 `spike-findings.md`），会直接改变部分旧断言的**迁移形态**。每条给出：SDK 实测事实 → 对方案/断言的影响 → 受影响 BC 编号 → 新增断言。
> **证据等级说明**：本节把方案中原本标为「假设/待验证」的若干条**升级为「源码实测」**，其中 §5.7 直接影响 `Handler3SecondTest` 的阈值定级。

### 5.1 去重键：`messageId`（双证据闭合，D12 定案）

- **SDK 实测**：`NormalizeDedupKeys.message()` 返回 `"message:" + messageId`——SDK 自身的入站去重**只用 messageId**，完全不涉及 eventId。
- **官方文档证据**：飞书明确「消息事件重推时 `event_id` 可能不同，幂等请用 `message_id`，不要依赖 `event_id`」。
- ⇒ **D12 修复方向双证据闭合**：旧实现以 `eventId` 为幂等键是**确凿缺陷**，新架构一律以 `messageId` 为唯一幂等键。
- **断言调整**：旧代码/旧测试中**任何基于 `eventId` 的断言一律改写为 `messageId`**。受影响条目：BC-137（重复 event_id 被去重）、BC-426 / BC-432（event_id 缺省回退 message_id）、BC-436（未知事件类型返回空 eventId）、BC-440（重复 seq 覆盖）。
- **新增断言**：`MessageDeduperTest`（M4-T7）必须含「**同 `messageId` 不同 `eventId` 仍去重**」用例（旧架构下该用例**必然失败**，是 D12 的直接反证）。`MessageDeduper` 规格：LRU 4096 + TTL 12h；SDK `SafetyConfig.dedupTtlMs` 作**第二道网**，**不自研第二套去重语义**（R9）。

### 5.2 卡片流式 sequence：每流一个 `Int`，取消 LRU map

- **SDK 实测**：`MarkdownStreamControllerImpl` 内部持 `private int sequence`——**每个流对象一个计数器**，SDK 内部**不存在** `card_id → sequence` 的全局 map。
- ⇒ **方案 R8 的 `card_id→sequence LRU(256)` 与 `cardSequenceMapSize` 指标取消**。新架构改为：每个 `CardStreamSink` 实例持**一个 `Int`**，随 sink 生命周期自然回收。
- **断言调整**：`SegmentRotationTest` / `Stream10MinRotationTest`（§7）中**不得**出现「map size 上限 256」「LRU 逐出」类断言；改为断言「**每个 sink 的 sequence 从 0 单调递增**」+「**sink 关闭后无残留状态**」（由 `StartStopLeakTest` 的 `cardStreamsOpen==0` 承接）。
- **收益**：少一个需要容量治理的全局状态，D9/D20 的关闭链路少一步。

### 5.3 CardKit 流式错误被 SDK 静默吞掉（必须自行检查成功码）

- **SDK 实测**：`RawMessageSender` 的 `createCardInstance` / `updateCardElementContent` / `finishStreamingCard` / `patchCard` **四个方法均不调用 `assertSuccessfulResponse`**。后果：HTTP 200 + 业务码失败时**无异常、无日志、无返回值差异**——调用方以为成功，用户看到卡片永久停在中间态。
- 已知会命中的业务码：`300317`（sequence 不连续 / 乱序）、`200860`（单卡 30KB 上限）、`200750`（卡片实体 14 天过期）、`200810`（流式期间无法响应卡片回传）；另有 `11232`（整点压力限流）、`230099`（`unknown property: action`，见 §4.14）。
- ⇒ **新实现改为在 `:lark-gateway` 内直接使用 raw client 的 cardkit API，并自行检查 response 成功码**（不复用 SDK 的 `RawMessageSender` 包装层）。
- **新增断言**（M3-T3，`LarkGatewayErrorMappingTest`）：**「卡片更新失败必须可观测」**——三件套缺一不可：
  1. **计数**：失败按业务码分类计数，暴露为指标（供 `JsonlSinkDropCountTest` 同源的丢弃可观测性纪律约束）；
  2. **日志**：每次失败写一条含业务码 + cardInstanceId + sequence 的 WARN/ERROR；
  3. **错误映射**：四类码各自映射到内部错误语义（sequence 冲突 → 重建流；30KB → 触发分段轮换；14 天过期 → 新建卡片实体；200810 → 确认卡必须走独立非流式卡）。
- **关联 BC**：BC-459（网关 404 reply 返回失败不抛异常）的「出站失败必须可观测」语义由此条**强化**——旧实现只对 HTTP 层失败可观测，业务码层是盲区。

### 5.4 `RetryConfig` 不感知频控响应头（退避全部自有承担）

- **SDK 实测**：全 SDK 源码 grep `x-ogw-ratelimit-reset` **零命中**。`RetryConfig` 只有 `maxAttempts = 3` + `baseDelayMs = 500` 的**盲指数退避**（500ms → 1s → 2s），完全不读飞书返回的频控重置时间。
- ⇒ **方案既定分支生效**：频控退避**全部由自有 `RateGovernor` 承担**，不指望 SDK。
- **断言归属调整**：所有「读 `x-ogw-ratelimit-reset` 退避」「11232 整点压力限流退避」相关断言**挂在 `RateGovernorTest`（M4-T7）而非任何 SDK 相关测试**。`RateGovernor` 规格：令牌桶每 chat **4 rps** + 全局 **40 rps**（飞书同群 5 QPS 限制，留 20% 余量）；挂起等待必须在 `net` 具名 dispatcher 上，**不得阻塞 intake**。
- **注意**：SDK 的盲退避仍会在**其自身重试路径**上生效（3 次），与 `RateGovernor` 的前置节流**叠加**。⇒ `RateGovernorTest` 须补一条断言：「`RateGovernor` 放行后才发起 SDK 调用」，避免两层退避互相放大导致 4s+ 延迟。

### 5.5 SDK 默认入站合批必须关闭（`chatQueueEnabled=false`）

- **SDK 实测**：`SafetyConfig.chatQueueEnabled` 默认 **`true`**，配 `BatchTextConfig.delayMs = 600`，会把**同一 chat 600ms 窗口内的多条消息合并成一条**：`content` 用 `\n\n` 拼接，但 `messageId` / `senderId` / `createTime` **只保留最后一条**。
- **四个后果**（每条都是致命缺陷）：
  1. **发送者归属错乱**——合并后 `senderId` 是最后一条的，群聊里 A 的消息会被记到 B 名下，直接破坏审计（BC-299..310 的 `senderOpenId` 字段）与访问控制（BC-186..194）；
  2. **停止指令被合并导致 URGENT 通路失效**——`停止` 与前面的普通消息合并后不再是精确匹配（BC-200..205 全部依赖精确 `equals`），D11 的 URGENT 旁路准入形同虚设；
  3. **每条入站固有 +600ms 延迟**——即使只有一条消息也要等满窗口；
  4. **与我方 `ChatLane` 双重排队**——SDK 排一次、内核再排一次，背压语义（`PipeBackpressureTest`）无法推理。
- ⇒ **网关固定 `chatQueueEnabled = false`**（硬编码，**不暴露为用户配置旋钮**——符合已否决方案「把 SDK 调优旋钮暴露进用户配置文档」）。
- **新增断言**（M3-T3，`LarkInboundMapperTest`）：**「入站不做跨消息合并，一条飞书消息对应一条 `InboundMessage`」**——同 chat 600ms 内灌 3 条 → 产出 **3 条** `InboundMessage`，且每条的 `messageId`/`senderId`/`createTime` **各自独立正确**。
- **关联 BC**：BC-423..436 全部（入站映射语义的前提）；BC-200..205（停止指令精确匹配依赖不被合并）。

### 5.6 SDK 异步 API 全部跑在 `ForkJoinPool.commonPool`

- **SDK 实测**：SDK 的 `supplyAsync` / `runAsync` **均不传 `Executor`** 参数 ⇒ 全部落在 `ForkJoinPool.commonPool()`。commonPool 的并行度是 `CPU 核数 - 1`，被阻塞 IO 占满会**污染整个 JVM 的并行流与其他协程调度**。
- ⇒ **所有 SDK 调用必须包在自有 `net` 具名 dispatcher 内**，且**优先使用 `*Sync` 变体**（同步方法在调用方线程执行，可控）。
- **新增断言**（M3-T3，`:lark-gateway` 测试）：**「网关不在 commonPool 上执行阻塞 IO」**——实现形态建议：在 SDK 调用点包裹的 dispatcher 内断言 `Thread.currentThread()` 名称前缀属于自有 `net` 池，或用 `ThreadMXBean` 采样 commonPool 线程的 CPU/阻塞时间在压测下不增长。
- **关联**：与 §5.7 的 handler 线程约束共同构成「SDK 线程模型必须被完全接管」的两条硬边界；亦与 R10（管道死锁）同源——阻塞点必须全部在自有可控池内。

### 5.7 handler 线程 == OkHttp WS reader 线程，ACK 同步等待（`Handler3SecondTest` 定级升级）

- **SDK 实测**：入站 handler 的**执行线程就是 OkHttp WebSocket 的 reader 线程**，且 **ACK 在 handler 同步返回之后才发出**。handler 抛异常 ⇒ ACK 返回 500 ⇒ **飞书重推该事件**。
- ⇒ **`Handler3SecondTest` 的 `<1ms` 断言是硬需求**，**证据等级从「方案假设」升为「源码实测」**。理由链：reader 线程被占用期间**整条长连接的所有后续事件都无法读取**（不只是当前事件超时），且抛异常会直接触发重推 → 配合 D12 修复前的 eventId 去重会**重复跑 agent**（R9 的完整因果）。
- **断言形态**（M4-T7，`Handler3SecondTest`）：SDK 回调内**只允许** `runCatching { inboundChannel.trySend(msg) }`，耗时 **<1ms**；结构性约束：intake 路径 grep 断言**零 `synchronized`、零 `Mutex`、零 suspend 调用、零 launch、零日志格式化**。
- **关联 BC**：BC-136（事件分发全链路）、BC-140（执行器异常不中断服务）——后者的「异常不冒泡到 intake」在新架构下**升格为「异常绝不冒泡到 SDK handler」**，否则会触发 ACK 500 重推。

### 5.8 `NormalizedMessage` 实际字段名（入站映射断言按实写）

- **SDK 实测**：类型字段名是 **`rawContentType`**（**不是** `messageType`），且**无 `eventId` 字段**。
- 完整字段清单：`messageId` / `chatId` / `chatType`（取值 `"p2p"` 或 `"group"`）/ `senderId` / `senderName` / `content` / `resources` / `mentions` / `mentionAll` / `mentionedBot` / `rootId` / `threadId` / `replyToMessageId` / `createTime`（**`long` 毫秒**）/ `raw`。
- ⇒ **入站映射断言一律按实际字段名写**（`LarkInboundMapperTest`，M3-T3）。
- **逐项影响**：
  - BC-423 / BC-424 的 `chat_type` → 用 `chatType`，取值字面值 `"p2p"` / `"group"` **与旧实现一致**（无需改值，只改字段名）；
  - BC-424 的 `createTime==""` → **旧断言不成立**：SDK 的 `createTime` 是 `long` 毫秒，非空字符串。新断言改为「`createTime > 0`」，并用于 `StaleFilter`（陈旧事件过滤）；
  - BC-425 / BC-426 / BC-432 / BC-436 的 `eventId` → **无载体**，全部改 `messageId`（与 §5.1 一致）；
  - BC-433 的 `messageType=="card_action"` → 改用 `rawContentType`，且**卡片回调的 rawContentType 实际取值须在 M1-S6/M3-T3 真机确认**（标 §9 待裁定点 **P11**）；
  - **D13 好消息**：SDK **原生提供** `rootId` / `threadId` / `replyToMessageId` 三个字段 ⇒ BC-043..045 / BC-182 的复合会话键 `chatId + (threadId ?: rootId ?: "")` **无需自己从 raw JSON 挖**，直接读字段即可。

### 5.9 `downloadResource` 返回 `CompletableFuture<byte[]>`（禁止全量入堆）

- **SDK 实测**：`downloadResource(fileKey, type)` 返回 **`CompletableFuture<byte[]>`**——**不落盘**、**无 `messageId` / `dest` 参数**。reaction 实际签名为 `addReaction(messageId, emojiType) → String` / `removeReaction(...)` / `removeReactionByEmoji(...)`。
- ⇒ **网关契约签名不变**（对外仍暴露「下载到目标文件」语义），**实现侧适配**：不能直接把 SDK 的 `byte[]` 结果透传。
- **关键风险**：`fileMessage.maxFileSizeBytes` 默认 **100MB**（旧 `FileMessageConfig.maxFileSizeBytes = 100L*1024*1024`）。若用 SDK 的 `byte[]` 路径，**单个 100MB 文件会全量入堆**；配合并发下载极易 OOM。
- ⇒ **改走 raw client 的流式落盘**（`InputStream` → `FileOutputStream` 边读边写）。
- **新增断言**（M3-T3 + M4-T8）：**「下载不在堆内缓冲整个文件」**——实现形态建议：下载一个大文件（或 mock 大 `InputStream`）时采样 `Runtime.totalMemory()-freeMemory()` 增量**远小于文件体积**，或断言实现路径不出现 `ByteArray` 累积（可用固定 8KB 缓冲 + 计数断言写入次数 ≈ 文件大小/8KB）。
- **关联 BC**：BC-298（下载失败 + 不残留临时目录）**追加**此断言；BC-293（小文本内嵌 prompt，`textInlineMaxBytes = 256KB` 以内）不受影响——**内嵌路径可以继续用 byte[]，只有落盘路径必须流式**；BC-409..413（`DownloadPathPolicy` 的路径清洗与 messageId 前缀命名）签名不变。
- **补充**：`CompletableFuture` 的桥接**不需要** `kotlinx-coroutines-jdk8`——`kotlinx/coroutines/future/FutureKt.class` 已在 `kotlinx-coroutines-core-jvm-1.11.0.jar` 内（方案已解包验证，**禁止新增该依赖**）。

### 5.10 `ws.Client` 线程泄漏（缓解方案 `TBD(用户裁决中)`）

- **SDK 实测**：`ws.Client` **构造即创建非 daemon 固定线程池**，且**全类无 `shutdown` 方法**；`close()` **只做 disconnect**，不回收线程池。
- ⇒ **每个 start/stop 周期泄漏 ≥1 个非 daemon 线程**，且非 daemon 线程会**阻止 JVM 自然退出**（对 Compose Desktop 应用 = 关窗后进程残留）。
- **🔴 缓解方案已裁定并实测落地（S6 真机 + Leader，2026-09-08；原 `TBD(用户裁决中)` / §9 P2）**：采用方向 **①+③ 组合**——`SdkLarkGateway.close()` 内**反射调用 SDK `ws.Client` 内部 `executor` 的 `shutdownNow()`**（实测回落 15–18 个线程），但 OkHttp Dispatcher 仍是非 daemon ⇒ 残留 **2 个非 daemon 线程**，故**进程退出必须由宿主显式 `exitProcess(0)`**（网关内绝不 `exitProcess`/`System.exit`/`Runtime.halt`，由 `GatewayCloseReleasesThreadsTest` grep 钉死）。详见 `gateway-contract.md §16.6`。
- **断言现状**：`BridgeStartStopLeakTest`（§7 NF-15，🔴 **不是** `exec/StartStopLeakTest.kt`）的 **`threadCount` 断言已裁定为「不在桥接级做」**——改由 ④`scope.children.count()==0` + `cli/CliExitProcessTest` + `:lark-gateway` 的 `GatewayCloseReleasesThreadsTest` 三方间接覆盖（残留 2 非 daemon 线程阈值不可靠）。其余三项断言（`liveProcesses==0`、`cardStreamsOpen==0`、`scope.children.count()==0`）**正常落地**。
- **关联 BC**：BC-469（recreate ×8 不累积监听端口）、BC-471（stop 后再次 start 可重绑）——旧断言只覆盖**端口**泄漏，新架构必须扩展到**线程**泄漏。

### 5.11 `RegisterApp` 扫码建应用 API 确实存在（是否纳入首批 `TBD(用户裁决中)`）

- **SDK 实测**：`com.lark.oapi.scene.registration.RegisterApp` **确实存在**，为**阻塞式轮询**，最长约 **600s**（10 分钟）——对应用户扫码创建应用的完整流程。
- **⚠️ 是否纳入首批标 `TBD(用户裁决中)`——本文档不自行填写结论。**
- **纳入的影响面（供裁决参考）**：① 属**新增能力**，不在「全功能平移」范围内（旧实现无此功能，无对应 BC 条目）；② 600s 阻塞轮询必须跑在自有 `net` dispatcher（§5.6），且需要独立的取消/超时闸门；③ UI 侧需要新增二维码展示卡片，触及 M6-T13 与 UI 铁律（零动画要求）；④ CLI 侧可能需要第 6 个子命令（方案定的是**五**子命令，M5-T10）；⑤ 若纳入，`Handler3SecondTest` 的 <1ms 约束**不适用**于该路径（它不是入站 handler），但需要新的非功能测试守护 600s 轮询的可中断性。
- **旧测试基线**：**无任何 BC 条目**对应此能力（BC-001..550 全覆盖旧 550 方法，无遗漏）。

---

## 6. 仓库实测校正

> 本节 6 条由本次 T1 任务的**只读核验**确认，用于避免后续里程碑改错文件。每条给出：方案/报告原表述 → 实测事实 → 对后续任务的影响。

### 6.1 D5 的 `runBlocking` 目标文件更正为 `SenderStatsStore`

- **方案原表述**：D5「runBlocking 在 `openapi/UserNameResolver.kt`」。
- **实测事实**：`openapi/UserNameResolver.kt`（58 行）**:33 已是 `suspend fun resolve(openId: String): String?`**，**全文无 `runBlocking`**——该文件**仅平移即可**。真正的 `runBlocking` 在 **`stats/SenderStatsStore.kt:88-90` 的 `private fun runBlockingName(openId: String): String`**（实现体 `kotlinx.coroutines.runBlocking { userNameResolver(openId) }`），调用点为 **:63**（`onMessage`）与 **:79**（`onRejected`）；文件共 132 行，其 KDoc 自述「回调为同步接口，内部用 runBlocking 桥接挂起解析器」。
- ⇒ **删 `runBlocking` 的目标文件更正为 `stats/SenderStatsStore.kt`**，相关旧测试断言（BC-311 / BC-313）按此归位到 `SenderStatsSinkTest`（M4-T8）。
- **连带影响**：`SenderStatsListener`（源 `AgentExecutor.kt:655-661`）是**同步接口**（`fun onMessage(...)` / `fun onRejected(...)`，非 suspend），这是 `runBlocking` 存在的**根因**。新架构须把该监听接口改为 `suspend`，否则 `runBlocking` 删不掉。BC-195（统计回调记录接受与拒绝事件）的断言载体随之变化。

### 6.2 `BuiltinExecutors.kt` 的「108 行」是文件全长而非 `build()` 长度

- **方案原表述**：「`BuiltinExecutors.kt` 的 `build()` 108 行」。
- **实测事实**：`BuiltinExecutors.kt` **全文 108 行**（`wc -l` 报 107，差 1 为末行无换行符口径），其中 **`build()` 为 :39-99，约 61 行**。⇒ 方案表述应理解为**文件全长**。
- **影响**：仅口径澄清，不改变 D16 的修复方向（`build()` 硬编码装配链 → `routes` 数组 + 唯一装配点 `BridgeAssembler.assemble()`）。BC-279..288 的废弃理由中引用的坐标已按 `:39-99` 写。

### 6.3 `rememberChartProgress()` 随 M2 消失，M6-T13 必须重新实现

- **实测事实**：`rememberChartProgress()` 位于 `feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/chart/ChartCanvas.kt:18`，修饰符为 **`internal`**。
- ⇒ 该函数**随 M2 拆除 `:feature-larkbridge` 而彻底消失**，M6-T13 图表重写时**必须重新实现**，**不能视为 `:ui` 模块的存活 API**（`internal` 修饰也决定了它从未跨模块可见）。
- **对断言的影响**：BC-520..527（`UsageStatsByDayTest` 4 条 + `UsageStatsKindTest` 4 条）锁住的是**纯聚合函数**的输出，与图表渲染解耦，可**独立平移**；但其**渲染消费方**（chart 相关旧测试）标注 **「M6 重新实现后恢复」**——即 M6-T13 完成图表重写前，这些条目处于「规范已固化、测试待恢复」状态，**不算验收缺口**。

### 6.4 旧配置 35 key 实测精确吻合，无配置丢失风险

- **实测事实**：
  - `feature-larkbridge/.../core/data/LarkBridgeConfigFields.kt` 的 `internal enum class LarkBridgeTextField` 共 **14 项**：`CHAT_ID`(`lark_bridge_chat_id`) / `APP_ID`(`lark_bridge_app_id`) / `APP_SECRET`(`lark_bridge_app_secret`) / `ALLOWED_SENDERS`(`lark_bridge_allowed_senders`) / `BLOCKED_SENDERS`(`lark_bridge_blocked_senders`) / `REJECTION_REPLY`(`lark_bridge_rejection_reply`) / `UNHANDLED_REPLY`(`lark_bridge_unhandled_reply`) / `AGENT_CUSTOM_TEMPLATE`(`lark_bridge_agent_custom_template`) / `AGENT_PREFIX`(`lark_bridge_agent_prefix`, 默认 `"ai"`) / `AGENT_RESET_COMMAND`(`lark_bridge_agent_reset_command`, 默认 `"新对话"`) / `AGENT_WORK_DIR`(`lark_bridge_agent_work_dir`) / `AGENT_SYSTEM_PROMPT`(`lark_bridge_agent_system_prompt`) / `AGENT_STOP_COMMAND`(`lark_bridge_agent_stop_command`, 默认 `"停止"`) / `AGENT_ALLOWED_TOOLS`(`lark_bridge_agent_allowed_tools`)
  - `feature-larkbridge/.../core/data/LarkBridgeConfigStore.kt`（224 行）的 **21 个 `KEY_` 常量**（:37-70）：`DRY_RUN` / `TRANSPORT` / `TOKEN_DIR` / `AGENT_ENABLED` / `AGENT_BACKEND` / `AGENT_TIMEOUT_MINUTES` / `STATIC_REPLIES` / `WORKSPACE_DIR` / `ACCESS_MODE` / `AGENT_PERMISSION_PRESET` / `PRESET_MIGRATION_NOTICE` / `AGENT_CONFIRM_ENABLED` / `GROUP_REQUIRE_MENTION` / `FILE_MESSAGE_ENABLED` / `FILE_MAX_SIZE_MB` / `FILE_AUTO_EXTRACT` / `AGENT_CONFIRM_TIMEOUT` / `WORKSPACE_WHITELIST` / `SKILLS` / `AGENT_PROHIBIT_DELETE` / `AGENT_SAFETY_PROMPT_ENABLED`
  - **14 + 21 = 恰好 35**，且**两组 key 无重叠**。
- ⇒ 与方案 **M7-T15 权威迁移映射表 1:1 精确吻合，无遗漏无多余，无配置丢失风险**（R6 的主要担忧点已消解）。
- **残留 key 处置**：`lark_bridge_event_key` / `lark_bridge_lark_cli_path` / `lark_bridge_region` 三个 key 在代码中**确已无对应常量**（历史遗留，仅可能存在于老用户的 DataStore 文件里）⇒ 按 **「忽略不迁移」** 处理。理由：三者分别对应 `eventKey`（D15 cli 传输退役后概念消失，见 BC-481/483）、`larkCliPath`（同 D15）、`region`（SDK 侧由 `LarkChannel` 配置承担，且旧值 `feishu`/`lark` 语义与新 SDK domain 参数一致，但无用户数据可丢）。
- **实测的旧默认值（进 `LegacyConfigImporterTest` 断言，即 R6 验收标尺）**：`dryRun=false`、`transport="cli"`、`tokenDir=""`、`agentEnabled=false`、`backend="claude"`、`timeoutMinutes=10L`、`accessMode="whitelist"`、`preset="safe"`、`confirmEnabled=false`、`confirmTimeout=60L`、`groupRequireMention=true`、`fileMessageEnabled=true`、`fileMaxSizeMb=100L`、`fileAutoExtract=true`、`prohibitDelete=true`、`safetyPromptEnabled=true`
- ⚠️ **注意 `transport` 旧默认值是 `"cli"`**，而 D15 已退役 cli 传输 ⇒ 迁移时该 key **不导入**（新架构无 transport 概念），但若用户显式设过 `transport="openapi"` 亦无需迁移。**这是 35 key 中唯一一个「有常量但语义消失」的项**，方案 T15 映射表须明确标注，见 §9 待裁定点 **P9**。

### 6.5 待删除的静态全局可变状态实测位置（D16）

| 位置 | 声明 | 赋值 / 读取 | 文件规模 |
|---|---|---|---|
| `executors/AgentExecutor.kt` | companion 内 `@Volatile private var rejectionHolder: AgentExecutor? = null`（**:604-605**） | 赋值 **:95**（`rejectionHolder = this`）；读 **:632**（`consumeRejectionReply`：`val executor = rejectionHolder ?: return null`）；条件清空 **:643**（`clearRejectionHolder`：`if (rejectionHolder === owner) rejectionHolder = null`） | 735 行 |
| `executors/BuiltinExecutors.kt` | object 级 `@Volatile var fileDownloader: (suspend (messageId: String, fileKey: String, target: java.io.File) -> Boolean)? = null`（**:105-106**） | 消费 **:68**（`FileMessageExecutor(config, logger, effectiveAgent, downloader = fileDownloader ?: { _, _, _ -> false })`） | 108 行 |

- ⇒ 对应断言标注 **D16**：BC-114、BC-193、BC-206、BC-474、BC-475（旧断言依赖静态取用），以及 BC-135、BC-141、BC-152、BC-279..288（旧断言依赖硬编码装配）。
- **新架构对策**：二者一律改**构造注入**，唯一装配点 `BridgeAssembler.assemble(config, logger, gatewayFactory)`（App 与 CLI 共用），由 `BridgeAssemblerParityTest`（§7 第 18 项）守护「同一 config 装配出的 handler 集合与顺序确定且唯一」——把 D16 从「靠纪律」变成「结构上不可能出错」。
- **`clearRejectionHolder` 的 C3 修复语义须保留**：旧代码用 `===` 引用比较**只清空自己**（不覆盖后来者），防「关闭 agent → 重建」后旧实例残留导致：被拒者仍收拒绝回复、仍写 `sender_stats.jsonl`（违反「未启用不统计」承诺）、仍触发 OpenAPI 联系人网络调用、且 rejection 抢占 `unhandledReplyText` 兜底契约。新架构虽无静态 holder，但**「旧实例不得在 stop 后继续产生副作用」这条语义必须有断言**（由 `TeardownCompletenessTest` 承接，见 §7 第 14 项）。

### 6.6 「禁止重写、只许平移」三块代码的精确坐标（定稿）

| 文件 | 总行数 | 关键坐标 | 对应 BC |
|---|---|---|---|
| `agent/AgentBackend.kt` | **341** | `ClaudeBackend` :49-144（`--mcp-config` :106-111、`--permission-prompt-tool` :110-111、`streamJson` 分支 :121-126）；`QoderBackend` :154-185；`CopilotBackend` :196-224（`-s` :206、`--allow-tool` :207-218）；`TemplateBackend` :231-254；`object AgentBackends` :257-340（`safetyPromptSuffix` :286-297、`presetAllowsShell` :306-312） | BC-001..042、BC-115..116 |
| `confirm/PermissionServerLauncher.kt` | **247** | `launchCommand` :161-162；`scriptCharset` :169-170；`MAIN_CLASS` :175；`SCRIPT_BASENAME` :178；`SCRIPT_DIRNAME` :181；`SELF_CHECK_TIMEOUT_MILLIS` :184；`TAIL_CHARS` :187；`resolveJavaBin` :200-205；`resolveClasspath` :208-220（实现体 :213-227）；`sh` :233；`commandToken` :238-239；`subcommandArgs` :138-146；Windows ANSI 写脚本 :63-67 / :165 | BC-092..107 |
| `filemsg/FileExtractor.kt` | **179** | `isArchive` :24-28；`safeChild` :169-179（实现体 :169-178）；`TAR_TYPEFLAG_OFFSET = 156`、`TAR_BLOCK_SIZE = 512` | BC-289..292 |

- **行数口径说明**：任务书采用「编辑器总行数」口径（341 / 247 / 179 / 735 / 108），`wc -l` 分别报 340 / 247 / 179 / 735 / 107（差 1 来自末行是否有换行符）。**本文件统一采用任务书口径**，后续里程碑引用时以任务书数字为准，避免误判「文件被改过」。
- 三块的旧测试断言在 §3 主契约表中已标 **🔒「禁止改写，逐字平移」**，期望的精确字面值见 §4.1 / §4.2 / §4.3 / §4.5 / §4.7。

---

## 7. 新增非功能测试清单（覆盖缺口）

> 旧 60 个测试**只覆盖功能正确性**，对方案 D1-D20 与 R1-R21 中的**非功能性缺陷**（频控、背压、泄漏、3 秒铁律、原子写、单实例互斥、优雅关闭、SDK 隔离）几乎零覆盖——这正是旧实现能带着 D1-D20 上线数月而不被测试发现的原因。本节列出方案「测试策略」明列的 **18 个新增非功能测试**（编号 `NF-01`..`NF-18`），以及 §5 SDK 实测结论额外要求的 **7 个守护测试**（编号 `NF-A1`..`NF-A7`，**不在方案 18 个清单内，是本文档基于源码级实测追加的**）。
>
> **测试基础设施统一约定**（方案明列，全部 25 项通用）：`kotlinx-coroutines-test` + `runTest` + `StandardTestDispatcher` + **虚拟时间**（`advanceTimeBy` / `runCurrent`）；长驻循环（节流器、看门狗、RateGovernor、LogBus ticker）一律用 **`backgroundScope`** 启动，避免 `runTest` 因未结束的子协程挂死；真实进程/真实文件的测试（`NF-07` / `NF-10` / `NF-13` / `NF-15`）**不用虚拟时间**，走 `Dispatchers.IO` + 真实 `withTimeout`。
>
> **验收纪律**：`NF-01`..`NF-18` 每项都必须在对应里程碑的 `./gradlew build` 中**实际存在并跑绿**；只在文档里列名而无测试文件，视为该里程碑未完成。

### 7.1 方案明列的 18 项（NF-01 .. NF-18）

| 编号 | 新增测试 | 守护 D#/R# | 里程碑 T# | 关键断言与阈值 |
|---|---|---|---|---|
| **NF-01** | `Handler3SecondTest` | **R9**（+ §5.7 证据升级为源码实测） | M4-T7 | ① SDK 回调内**只允许** `runCatching { inboundChannel.trySend(msg) }`，单次耗时 **< 1ms**；② 结构性约束：intake 路径 **grep 断言零 `synchronized`、零 `Mutex`、零 `suspend` 调用、零 `launch`、零日志格式化**；③ 回调内**不得**出现任何 IO / 网络 / 锁 / JSON 序列化。**依据**：§5.7 实测 handler 线程 == OkHttp WS reader 线程且 ACK 同步等待，reader 被占用期间整条长连接的所有后续事件都无法读取 |
| **NF-02** | `CardThrottleTest` | **R7②** + **D1** + **D3** | M4-T8 | 虚拟时间下 **1000 次 `append`/秒 ⇒ ≤ 4 次 `update`**（节流窗口 **250ms = 4/s**，对飞书「单卡 10 次/秒」上限留 **60% 余量**）；终态更新**不受窗口限制**（立即 flush）；节流器为 **1 槽 conflate + 单一不可变快照**（D3 修复，禁止 `Channel(Channel.UNLIMITED)`，禁止四变量非原子组合）。⚠️ 关联 **§9 P10**（旧 `progressPatchIntervalMillis` 用户可调项是否保留）与 **§9 P4**（`cardMaxChars=5000` 是否放宽） |
| **NF-03** | `SegmentRotationTest` | **R7④** + **D1** | M4-T8 | **40KB 内容 ⇒ 恰好 2 段**（主动分段预算 **24KB**，对飞书单卡 **30KB** 硬上限留 20% 余量）；每段是**新建卡片实体**；**sequence 断言按 §5.2 调整**——「每个 `CardStreamSink` 持一个 `Int`，从 0 单调递增」，**禁止**出现「`card_id→sequence` map size 上限 256」「LRU 逐出」「`cardSequenceMapSize` 指标」类断言（R8 分支已因 SDK 实测取消） |
| **NF-04** | `Stream10MinRotationTest` | **R7⑤** + **R20** + **D1** | M4-T8 | **9min 主动轮换**（避开飞书 10min 自动关流）；每次轮换**新建**卡片实体、**不复用 `card_id`**（R20：卡片实体 14 天过期且仅可发送一次）；`200750`（14 天过期）映射为**专属错误码**并触发重建；虚拟时间跨 9min 边界断言轮换**恰发生一次** |
| **NF-05** | `RateGovernorTest` | **D2** + **R7③**（+ §5.4 断言归属调整） | M4-T7 | 令牌桶 **每 chat 4 rps** + **全局 40 rps**；读 `x-ogw-ratelimit-reset` 响应头退避；`11232`（整点压力限流）触发退避；退避挂起在**自有 `net` dispatcher**（不阻塞 `Default`）；**追加断言（§5.4）**：「`RateGovernor` 放行后才发起 SDK 调用」——避免自有前置节流与 SDK 内部盲指数退避（`maxAttempts=3` / `baseDelayMs=500`）互相叠加放大。⚠️ **SDK `RetryConfig` 不感知 reset 头**（全 SDK 源码 grep 零命中），故频控退避**全部**由本测试守护，不得指望 SDK |
| **NF-06** | `PipeBackpressureTest` | **R10** + **D3** | M4-T6 | **10 万行输出 + 停滞的解析器 ⇒ 管道 channel 元素数 ≤ 4096**（`Channel(4096, SUSPEND)`），进程**不 OOM**、解析器恢复后**无丢行**（JSONL 完整性）；stdout / stderr **双流并发排干**（`coroutineScope { launch(pipe){...} ; launch(pipe){...} }`），任一管道填满**不阻塞**另一路；启动后**立即 `process.outputStream.close()`** 关 stdin；**不用** `redirectErrorStream`（需区分来源） |
| **NF-07** | `CancellationKillsProcessTest` | **R10** + **D9** | M4-T6 | 真实 `sleep 60` 子进程，**取消后 5s 内 `isAlive == false`**；kill 升级链时序断言：`SIGTERM(destroy())` → 等 **3s** → 仍存活则 `SIGKILL(destroyForcibly())` → 等 **2s** → 仍存活则**放行并记 `exitCode = -1`**；整个 kill 序列包在 **`NonCancellable`** 内（外层取消不得中断 kill）；`ProcessRegistry` 登记/注销配对。**注意**：kill 升级链是**新增行为**，旧 `AgentExecutor.kt:708` 只有裸 `destroyForcibly()`，无平移来源 |
| **NF-08** | `IdleWatchdogTest` | **D10** | M4-T8 | 新增 `agentIdleTimeoutSeconds = 120` 空闲看门狗：**120s 无任何输出即终止**子进程；与总超时（`taskTimeoutSeconds`，旧默认 600s）**相互独立**——总超时未到但空闲超时先到时也必须终止；两种超时的 `errorCode` 可区分（见 §4.13 `agent_timeout` 语义）；**旧实现零覆盖**（grep `idle` 在旧 lib 仅命中 `BridgeState.Idle` 状态名，与看门狗无关） |
| **NF-09** | `JsonlSinkDropCountTest` | **D18** + **D4** | M4-T8 | `JsonlSink` 常驻 **`BufferedWriter`** + **500ms / 64 条**批量 flush（D4 修复，禁止旧「每条记录 `open → write → close`」）；审计与统计各一条 `Channel<SinkRecord>`，容量 **8192** + `DROP_OLDEST`；**所有丢弃点必须有计数器**并暴露为指标（`droppedRecords`），断言「灌入 > 8192 条时 `droppedRecords > 0` 且数值精确」；`flushAndClose()` 在 `NonCancellable` 内、进程退出前不丢已入队记录。**schema 字面值断言见 §4.11**（含报告漏记的 `CMD_SUMMARY_CHARS = 60`） |
| **NF-10** | `ConfigAtomicWriteTest` | **R6** + **R17** | M4-T5 | 原子写（临时文件 + `rename`）；写前**备份**旧文件；文件损坏时回退 `Failed(LarkConfigParseFailed)`（**不抛**、不静默用默认值覆盖用户配置）；**写入中途抛异常 ⇒ 旧文件完好无损**（可继续加载）；新建文件权限 **POSIX 600**（R17：`appSecret` 明文落 JSON 的泄露面缓解）；`secret` 字段在日志/审计/`check` 输出中**单点统一掩码** |
| **NF-11** | `ConfigMigrationUnknownKeyPreservedTest` | **R6** + **R18** | M7-T15 | `schemaVersion` v0 → v1 迁移正确；**未知顶层段 / 未知键必须原样保留**（前向兼容：新版本写的键不被旧版本迁移逻辑吃掉）；单 key 独立 `runCatching`（一个 key 解析失败不影响其余 34 个）；**不置迁移标记 ⇒ 可重试**；二次启动**标记短路**零成本（R18）；35 key 映射见 §6.5（实测 14 + 21 = 35，1:1 精确吻合）。⚠️ 关联 **§9 P5**（已退役配置项是「保留为未知键」还是「显式删除」）与 **§9 P9**（`transport` key 处置） |
| **NF-12** | `LogConsoleCoalescingTest` | **D7** + **R14** | M6-T13 | **5000 事件/秒 ⇒ ≤ 10 次状态变更**（`SnapshotStateList` O(1) 追加 + **10Hz ticker** 合批）；`removeRange(0, size - 1000)` **每 tick 一次**（不是每条一次）；`LogBus` 环形 `ArrayDeque(capacity = 1000)` + version，覆盖最旧并累加 `droppedTotal`；ticker **绑 Composable 生命周期**（离开页面即停）；**流式输出与日志行零动画**（UI 铁律）。⚠️ 旧 `LarkBridgeViewModel.kt:346-356` 已有「有界复制（C5-P2）」部分缓解（`MAX_LOG_LINES = 1000`，见 §8 D7 行），但仍是**每条日志一次 List 构造 + subList**，5000/s ⇒ 5000 次/秒 1000 元素复制，D7 描述属实 |
| **NF-13** | `SingleInstanceLockTest` | **D19** + **R16** | M4-T7 | `FileChannel.tryLock()`：锁文件路径 **`~/.easy_dev_toolbox/lark/locks/<sha256(appId).take(16)>.lock`**；**二次获取返回 `BlockedByPeerInstance`**；**进程崩溃由 OS 自动释放**（无需陈旧锁清理代码——这是选 FileLock 而非 pid 文件的理由，须有断言：kill -9 持锁进程后新进程能立即拿到锁）；**App 与 CLI 共享同一把锁**；被占时 **CLI 非 0 退出** + UI 明确文案。**旧实现零覆盖**（全仓 grep `tryLock` 零命中） |
| **NF-14** | `TeardownCompletenessTest` | **D20** | M4-T9 | **十步关闭序列完整执行且顺序正确**：① `inboundChannel.close()` 并计数残留 → ② `taskJobs.forEach{cancel()}` + `withTimeoutOrNull(grace){join()}` → ③ `ProcessRegistry.killAll()` → ④ 遍历开放卡片流发**终态卡** + `sink.close()`（`NonCancellable`, 3s）→ ⑤ `gateway.disconnect()`(5s) → ⑥ `gateway.close()` 释放 SDK 线程/连接池(5s) → ⑦ audit/stats `flushAndClose()`（`NonCancellable`）→ ⑧ `configWriter.flushNow()`（`NonCancellable`）→ ⑨ `confirmBus.close()` + `deleteScript()` → ⑩ 释放 appId 锁 → `scope.cancel()` → `state = Stopped`。默认 `graceMillis = 5000`，**每步都有超时上限**；`AtomicBoolean` CAS **保证幂等**（重复 `stop()` 不重入、不二次发终态卡）；`onAppExit()` 与 **JVM shutdown hook** 都调用它；**追加语义（§6.4）**：「旧实例不得在 stop 后继续产生副作用」（承接旧 `clearRejectionHolder` 的 `===` 只清空自己的 C3 修复语义，见 BC-474 / BC-475）。**旧实现对照**：`LarkBridge.kt:412 fun stop()` → `:424 scope.cancel()`，**直接砍，无排空** |
| **NF-15** | `BridgeStartStopLeakTest`（🔴 实测承载类名，**不是** `exec/StartStopLeakTest.kt`——后者是进程原语域的同名旧文件，测子进程泄漏 `liveProcesses==0`，非本项） | **D9** + **R10** + **§5.10** | M4-T9 | **start → stop ×20 循环**后断言四项：① **`threadCount` 不增长** —— 🔴 **已裁定（Leader，2026-09-08，T16 回填；原 `TBD(用户裁决中)` / §9 P2）**：**不在桥接级做线程数断言**，改由 ④`scope.children.count()==0`（=`supervisorChildCount()==0`）+ `cli/CliExitProcessTest`（CLI `serve` 退出契约）+ `:lark-gateway` 的 `GatewayCloseReleasesThreadsTest`（`close()` 释放 SDK 线程 + 残留 2 非 daemon 线程须配合显式 `exitProcess`）**三方间接覆盖**（理由：SDK `ws.Client` 构造即创建非 daemon 固定线程池且全类无 `shutdown`，桥接级线程数阈值不可靠，见 §5.10 / gateway-contract §16.6）；② **`liveProcesses == 0`**；③ **`cardStreamsOpen == 0`**；④ **`scope.children.count() == 0`**。**②③④ 正常落地**；②③④ 同时承接 BC-467..471 抢救出的「Idle 态零资源占用 / recreate 不泄漏端口」语义 |
| **NF-16** | `LarkSdkIsolationTest` | **决策 1** + **R19** + **R12** | **M3-T4** | ① 全仓 `**/build.gradle.kts` 中匹配 `oapi-sdk` 的文件集合 **== `{lark-gateway/build.gradle.kts}`**（唯一）；② `lark-gateway/src` **之外**的所有 `.kt` 文件中**不得出现** `com.lark.oapi` / `com.google.gson` / `org.apache.http` / `com.google.common` / `com.larksuite` 任一字符串（含 import 与全限定名）；③ **R19 方向断言**：`:lark-bridge` / `:lark-gateway` 源码**不得出现** `com.jiaoay.easy_dev_toolbox` 包引用（lib jvmTarget 11 不能依赖 `:core` jvmTarget 21）；④ **验收方式（方案明列）**：**故意在 `:core` 加一行 `import com.google.gson.Gson` 后本测试必须失败**——只跑「通过」不算验证，必须验证「能抓错」 |
| **NF-17** | `BridgeFieldsTest` | **R15** + **R17** | M6-T12 | 逐 field 断言：**`set(get(x))` 恒等**（读改写往返不丢值）、**`id` 全局唯一**、**`group` 非空**、**`applyMode` 非空**（热应用 / 需重启二分，D8）；`BridgeField` 必须用**显式 get/set lambda**——**不反射、不 `KProperty`**（R15：避免 schema 驱动 UI 变成新的「魔法」，要求「跳转即达」）；`secret` 标记字段在日志/审计/`check` 输出中**统一掩码**（R17）；bespoke 卡（连接/路由/日志/统计）**不进表**——断言这 4 类**不出现在** `BridgeFields` 中 |
| **NF-18** | `BridgeAssemblerParityTest` | **D16** + **D8** + **R1** | M4-T9 | 唯一装配点 **`BridgeAssembler.assemble(config, logger, gatewayFactory): Bridge`**：**同一 config 装配出的 handler 集合与顺序「确定且唯一」**（多次 assemble 结果 `equals`，无隐藏状态、无时间依赖、无随机顺序）；**App 与 CLI 共用同一个 `assemble`**（断言两条入口拿到等价 handler 列表）；handler 顺序来自 **`routes` 数组声明式优先级**（构造两条同触发词 route，断言**数组前者胜**）；`gatewayFactory` 可注入 **`SyntheticGateway`** ⇒ **引擎侧开发与测试完全不依赖 SDK 可用性**（R1 缓解）。**D16 消解证据**：装配点无静态可变状态（对照 §6.4 实测的 `AgentExecutor.kt:604-605` `rejectionHolder` 与 `BuiltinExecutors.kt:105-106` `fileDownloader`） |

### 7.2 §5 SDK 实测追加的 7 项（NF-A1 .. NF-A7，**方案 18 项清单之外**）

> 这 7 项不在方案「测试策略」的 18 个新增测试列表内，是本文档依据 **§5 SDK 2.8.5 源码级实测结论**追加的必要守护。**若不加，§5 的实测结论就没有任何测试承接，等于白测**。

| 编号 | 新增测试 | 守护 | 里程碑 T# | 关键断言与阈值 |
|---|---|---|---|---|
| **NF-A1** | `MessageDeduperTest` | **D12** + §5.1 | M4-T7 | ① 幂等键 == **`messageId`**，`MessageDeduper` 规格 **LRU 4096 + TTL 12h**；② **必含用例「同 `messageId` 不同 `eventId` 仍去重」**——该用例在旧架构下**必然失败**（旧 `BridgeEngine.kt:130` 用 `seenEvents.put(event.eventId, ...)`），是 D12 的**直接反证**；③ SDK `SafetyConfig.dedupTtlMs` 作**第二道网**，**不自研第二套去重语义**（R9）；④ **`messageId == ""` 的事件丢弃**（承接 BC-436 的形态改写：旧断言是 `eventId == ""`） |
| **NF-A2** | `LarkInboundMapperTest` | §5.5 + §5.8 + **R9** | **M3-T3** | ① **「入站不做跨消息合并，一条飞书消息对应一条 `InboundMessage`」**——同 chat **600ms 内灌 3 条 ⇒ 产出 3 条** `InboundMessage`，且每条的 `messageId` / `senderId` / `createTime` **各自独立不被覆盖**（反证：SDK 默认 `chatQueueEnabled=true` + `BatchTextConfig.delayMs=600` 会合并成 1 条、content 用 `\n\n` 拼接、三元信息只保留最后一条）；② **网关固定 `chatQueueEnabled = false`** 并断言其**不可被配置打开**；③ **字段名按 `NormalizedMessage` 实际写**：`rawContentType`（**不是** `messageType`）、**无 `eventId` 字段**，其余 `messageId`/`chatId`/`chatType("p2p"\|"group")`/`senderId`/`senderName`/`content`/`resources`/`mentions`/`mentionAll`/`mentionedBot`/`rootId`/`threadId`/`replyToMessageId`/`createTime(long ms)`/`raw`；④ **`createTime > 0`**（承接 BC-424 的形态改写：旧断言 `createTime == ""` 在 SDK 下不成立）；⑤ 用**录制的 `NormalizedMessage` 样本**驱动（方案映射原则①），不连真机 |
| **NF-A3** | `LarkGatewayErrorMappingTest` | §5.3 + **R2** + **R7⑥** | **M3-T3** | ① **「卡片更新失败必须可观测」三件套缺一不可**：**计数**（按业务码分类计数并暴露为指标）+ **日志**（可定位到 `card_id` 与业务码）+ **错误映射**（每个码有专属 `BridgeErrorCode`）；② 覆盖 **`300317`**（sequence 乱序）/ **`200860`**（30KB 超限）/ **`200750`**（14 天过期，触发重建）/ **`200810`**（流式期间无法响应卡片回传）/ **`230099`**（unknown property: action）/ **`11232`**（整点压力限流）；③ **`99991672` → `FatalConnectException`**、**可重试码 → `RetryableConnectException`**、**HTTP 404 纯文本 → `FatalConnectException`**、**缺 URL 字段 → `Retryable`**（承接 BC-445..448 / BC-459 的语义升级）；④ 出站失败**返回 `false` 不抛异常**（BC-459）。**根因（§5.3）**：SDK `RawMessageSender` 的 `createCardInstance` / `updateCardElementContent` / `finishStreamingCard` / `patchCard` **均不调用 `assertSuccessfulResponse`**，HTTP 200 + 业务码失败时**无异常无日志**⇒ 新实现必须在 `:lark-gateway` 内**直接用 raw client 的 cardkit API 并自行检查 response 成功码** |
| **NF-A4** | `GatewayDispatcherTest` | §5.6 + **R10** | **M3-T3** | **「网关不在 `ForkJoinPool.commonPool` 上执行阻塞 IO」**：所有 SDK 调用必须包在**自有 `net` dispatcher** 内（**优先用 `*Sync` 变体**，避免 `supplyAsync` / `runAsync` 不传 Executor 而落到 commonPool）；实现形态建议：在 SDK 调用点断言 `Thread.currentThread().name` 前缀属于自有 `net` 池，或用 `Thread.getAllStackTraces()` 采样确认 commonPool 线程**无阻塞栈**。**根因**：SDK 异步 API 全部跑在 commonPool，阻塞它会污染**整个 JVM** 的并行流与其他 commonPool 用户 |
| **NF-A5** | `FileDownloadStreamingTest` | §5.9 + **R10** | M3-T3 + M4-T8 | **「下载不在堆内缓冲整个文件」**：`fileMessage.maxFileSizeBytes` 默认 **100MB**，落盘路径**禁止** `byte[]` 全量入内存，改走 **raw client 流式落盘**；实现形态建议：下载大文件（或 mock 大 `InputStream`）时采样 `Runtime.totalMemory() - freeMemory()` 增量**远小于文件体积**，或断言写入是**分块**进行的；**内嵌小资源路径可继续用 `byte[]`**（SDK `downloadResource(fileKey, type)` 返回 `CompletableFuture<byte[]>`，不落盘、无 `messageId`/`dest` 参数）——**只有落盘路径必须流式**；下载路径策略仍受 `DownloadPathPolicy` 白名单约束（BC-414..418） |
| **NF-A6** | `LarkPolicyMapperTest` | 映射原则① + **R7** | **M3-T3** | 方案映射原则① 明列的契约映射测试（与 NF-A2 成对）：**内部出站意图 → SDK 卡片/消息 API** 的字面约束。承接 BC-455 / BC-456 / BC-460 / BC-461 / BC-463 / BC-464：① 卡片 **schema `"2.0"`** + `body.elements`；② button 走 **`behaviors` 数组**（`type == "callback"`），**无 `action` 包裹**（否则 `230099 unknown property: action`）；③ markdown → `msg_type == "interactive"`，否则 `"text"`；④ `sendBody.receive_id` 存在；⑤ 特殊字符（引号/反斜杠/换行）转义**往返一致**；⑥ **流式卡内禁放任何交互组件**，确认/裁决一律用**独立非流式卡**（架构级消解 `200810`）——须有**负向断言**：流式卡 elements 中出现 button 时 mapper 拒绝或降级 |
| **NF-A7** | `StaleFilterTest` | §5.8 + **R9** | M4-T7 | 承接 `pipeline/StaleFilter.kt`（方案 T7 文件清单内，但**方案 18 项测试清单未列**）：用 `NormalizedMessage.createTime`（**`long` 毫秒**，非字符串）判定陈旧事件并丢弃；阈值须可配置且有默认；**边界断言**：`createTime == 0` / 未来时间戳 / 本机时钟回拨三种异常输入**不得**导致误丢正常消息或抛异常；丢弃**必须计数**（D18 纪律，与 NF-09 同源） |

### 7.3 NF 与 BC 的交叉索引（防止两套清单各跑各的）

| NF | 承接的 BC 编号（抢救 / 改写 / 语义升级） |
|---|---|
| NF-02 / NF-03 / NF-04 | BC-335..339（卡片结构字面值）、BC-455 / BC-456 / BC-460..464（SDK吸收但字面约束保留） |
| NF-06 / NF-07 | BC-162..288 中的进程与管道相关条目（`executors/`，🔒 部分见 §4.5 / §4.6） |
| NF-09 | BC-299..318（audit + stats，schema 见 §4.11） |
| NF-10 / NF-11 | BC-319..324（`model/BridgeConfigTest` 默认值双态）、BC-528..550（UI 4 个废弃文件的 DataStore 字面映射） |
| NF-14 / NF-15 | **BC-467..471**（Idle 态零资源占用，从废弃文件抢救）、**BC-474 / BC-475**（静态 `rejectionHolder` 注销语义，从废弃文件抢救）、**BC-476**（确认超时钳制纯函数，从废弃文件抢救） |
| NF-16 | BC-423..464 全部（SDK吸收组的隔离前提）、§6.4（D16 静态状态坐标） |
| NF-18 | BC-193 / BC-206（D16 静态状态）、BC-136..161（engine → pipeline 重组）、BC-465 / BC-472 / BC-473（Integration 抢救条目） |
| NF-A1 | BC-137（旧 eventId 去重断言的**改写目标**）、BC-426 / BC-432 / BC-436 |
| NF-A2 / NF-A6 | BC-423..464（SDK吸收组 42 方法的**全部**字面约束落点） |
| NF-A3 | BC-445..448 / BC-459（错误码 → 网关状态映射的语义升级） |


---

## 8. D1-D20 验收对照表

> 方案「旧实现必须修掉的 20 个缺陷（验收清单）」是**验收标尺**，不是背景说明。**「旧实现位置」列全部经本轮只读 grep / Read 实测确认**（相对 `lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/`，UI 侧相对 `feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/larkbridge/`），可直接跳转核对。
> **状态列一律为「待实现」**——M2 删除旧模块后，本表是判断「新架构是否真的修掉了缺陷」的唯一依据；每条的守护测试必须在对应里程碑**实际跑绿**，否则该 D# 状态不得改为「已修」。
> ⚠️ 标注 **「方案表述与实测有偏差」** 的行，见 §9 对应待裁定点，**本文档不自行改方案**。

| D# | 缺陷（方案原文） | 旧实现位置（**本轮实测**） | 新架构对策 | 守护测试 | 关联 BC | 状态 |
|---|---|---|---|---|---|---|
| **D1** | 流式走「消息编辑」（社区实测 20-30 次后静默失败） | `openapi/LarkOpenApiTransport.kt:119-133`（`PATCH /im/v1/messages/$messageId`，:119 dryRun 日志 `[update-card]`、:126 与 :133 两处 `httpClient.patchJson`）；body 构造在 `openapi/MessageContent.kt:52`；HTTP 原语 `openapi/OpenApiClient.kt:37`（`patchJson`）；节流窗口配置 `model/BridgeConfig.kt:157`（`progressPatchIntervalMillis` 注释「进度卡片 patch 合并窗口毫秒」） | **彻底弃用**消息编辑，改 **CardKit 卡片实体 + `streaming_mode`**；`:lark-gateway` 内直接用 raw client 的 cardkit API 并**自行检查 response 成功码**（§5.3） | NF-02 / NF-03 / NF-04 / NF-A3 / NF-A6 | BC-335..339、BC-455..464 | **待实现**（M4-T8 + M3-T3） |
| **D2** | 出站零频控零退避 | **实测确认零命中**：全 lib grep `ratelimit`/`rate_limit`/`429` 在**出站路径**无任何命中；仅有 3 处**非出站**退避——`progress/ProgressThrottler.kt:18`（节流 patch，非频控）、`lark/LarkCliProcess.kt:106`+`:173-179`（`computeBackoff`，**进程重启**退避，`MAX_BACKOFF_SHIFT=5`）、`openapi/ws/LarkWsClient.kt:133`（`backoffDelay`，**WS 重连**退避）⇒ **「出站消息 API 零频控零退避」属实** | `RateGovernor` 每 chat **4 rps** + 全局 **40 rps** + 读 `x-ogw-ratelimit-reset` 退避（含 `11232` 整点压力限流）。⚠️ **§5.4 实测：SDK `RetryConfig` 不感知 reset 头**（只有 `maxAttempts=3` + `baseDelayMs=500` 盲指数退避）⇒ 频控退避**全部**由自有 `RateGovernor` 承担 | **NF-05** | BC-445..448、BC-459 | **待实现**（M4-T7） |
| **D3** | 节流器用 `Channel(UNLIMITED)` + 四变量非原子组合有竞态 | `progress/ProgressThrottler.kt:24` —— **`private val channel = Channel<Signal>(Channel.UNLIMITED)`**（实测逐字命中） | **1 槽 conflate + 单一不可变快照** | **NF-02** / NF-06 | BC-340..346（`progress/`） | **待实现**（M4-T8） |
| **D4** | 审计/统计每条记录 open→write→close | `audit/AuditLogger.kt`（写路径逐条 open；读路径 `:94 file.readLines()`）+ `stats/SenderStatsStore.kt`（读路径 `:124 file.readLines()`） | `JsonlSink` 常驻 **`BufferedWriter`** + **500ms / 64 条**批量 flush | **NF-09** | BC-299..318 | **待实现**（M4-T8） |
| **D5** | 统计路径内 `runBlocking` 解析用户名（缓存未命中即同步网络调用） | ⚠️ **方案表述与实测有偏差（§6.1）**：方案指向 `openapi/UserNameResolver.kt`，但该文件 `:33` **已是 `suspend fun resolve`、全文无 `runBlocking`**（仅平移即可）。**实际位置是 `stats/SenderStatsStore.kt:88-90` 的 `runBlockingName`**，调用点 **`:63`（`onMessage`）** 与 **`:79`（`onRejected`）**。**根因**：`SenderStatsListener`（声明在 `executors/AgentExecutor.kt:655-661`）是**同步接口**，故必须 `runBlocking` 才能调 suspend 的解析器 | 改 suspend + 缓存，**删除 `runBlocking`**，移出写路径。**⚠️ 连带要求**：`SenderStatsListener` 必须同步改为 **suspend 接口**，否则 `runBlocking` 删不掉（这是本条能否验收的**前置条件**） | NF-09（写路径批量）+ 移植后的 stats 单测 | BC-311..318 | **待实现**（M4-T8） |
| **D6** | 审计/统计无轮转，读取 `readLines()` 全量入内存 | `audit/AuditLogger.kt:94 file.readLines().asSequence()` + `stats/SenderStatsStore.kt:124 file.readLines().asSequence()`（**两处实测逐字命中**） | **8MB × 3 轮转** + `JsonlTailReader` 从文件尾 **64KB 反向分块读** | NF-09（同源纪律）+ 新增轮转/尾读单测（M4-T8） | BC-299..318 | **待实现**（M4-T8） |
| **D7** | UI 日志台每条日志做一次 1000 元素 List 全量复制 | `LarkBridgeViewModel.kt:346-356`（`bridge.observeLogs().collect` 内 `_logLines.update { list -> ... }`）；`MAX_LOG_LINES = 1000` 在 **`:55`**。⚠️ **实测补充**：该处**已有「有界复制（C5-P2）」部分缓解**（`:349-351` 超限时先截头再 `subList`，把每条 O(n) 降为稳定 O(MAX_LOG_LINES)），但**仍是每条日志一次 List 构造 + 一次 subList**，5000 事件/秒 ⇒ **5000 次/秒 1000 元素复制** ⇒ **D7 描述属实，只是非「完全未处理」** | `SnapshotStateList` **O(1) 追加** + **10Hz ticker** 合批（5000 事件/秒 ⇒ **≤10 次状态变更**）；`LogBus` 环形 `ArrayDeque(1000)` + version + `droppedTotal`；VM 侧 `removeRange(0, size-1000)` **每 tick 一次** | **NF-12** | BC-343..346（`log/BridgeLogBusTest`，⚠️ 旧 `REPLAY_COUNT=200` 字面值在容量 1000 下不成立，见 §3.8 改写指引） | **待实现**（M6-T13） |
| **D8** | 40+ 独立 StateFlow + 每次字段提交触发 `recreate()`（改白名单就断长连接） | **实测精确计数**：`= MutableStateFlow` 声明 **46 处**，其中 **`LarkBridgeViewModel.kt` 独占 46 处声明中的绝大多数（该文件 `MutableStateFlow` 出现 48 次、`asStateFlow()` 暴露 45 次、全文 779 行）**，`ListConfigCommit.kt` 2 处；**lib 侧仅 8 处**（`LarkBridge.kt` 3 + `openapi/ws/LarkWsClient.kt` 3 + `engine/BridgeEngine.kt` 2）⇒ **「40+」精确吻合（46）**，但 ⚠️ **分布与直觉相反：几乎全在 feature 侧 VM，lib 侧只有 8 个**。`recreate()` 语义见 `LarkBridge.kt:53` 与 `:408` 注释（「宿主 `LarkBridgeManager.recreate()` 语义是 stop() + new，Idle 态也必须清理干净」） | 单一 `StateFlow<BridgeUiState>`（🔴 **方案期望范式**；实际实现为 7 个独立 `MutableStateFlow`——`_draft:StateFlow<BridgeConfig>`（配置态的单一不可变 flow）+ `_issues`/`_applyResult`/`_startResult`/`_busy`/`_banner`/`_probe` 6 个一次性信号；设计裁定接受该偏离，理由见 `config-domain-spec.md §15 #14`）+ **热应用/需重启二分**（`applyMode`）+ `gateway.updatePolicy()`。⇒ **改造重心在 M6-T13（VM 重写），不在 M4 lib 侧**；lib 侧 8 个 StateFlow 收敛进网关/pipeline 状态 | **NF-17**（`applyMode` 非空）/ **NF-18** | BC-484..527（UI 移植组）、BC-121..135（CLI 逐字段旗标废弃） | **待实现**（M6-T12/T13 为主） |
| **D9** | 子进程排干用裸守护线程，`join(2s)` 超时即泄漏 | `executors/AgentExecutor.kt:702-703`（**`drainOut.join(JOIN_TIMEOUT_MS)` / `drainErr.join(JOIN_TIMEOUT_MS)`**）+ `:728`（**`isDaemon = true`**）；`:708` 只有裸 `destroyForcibly()`（**无 kill 升级链**） | 协程 + `Dispatchers.IO`，**结构化并发**；双流并发排干 + 立即关 stdin + `Channel(4096, SUSPEND)`；**kill 升级链 SIGTERM→3s→SIGKILL→2s→放行 -1**，整序列在 `NonCancellable` 内 | **NF-07** / **NF-15** / NF-06 | BC-162..288 | **待实现**（M4-T6） |
| **D10** | 只有总超时无空闲看门狗（agent 卡死要等满 10 分钟） | **实测确认零命中**：全 lib grep `idle`（忽略大小写）仅命中 `model/BridgeState.kt:5 data object Idle` 与 `LarkBridge.kt:48/:52/:74/:249/:408` 的 **Idle 生命周期状态名**，**与空闲看门狗无关** ⇒ **「无空闲看门狗」属实** | 新增 **`agentIdleTimeoutSeconds = 120`** 空闲看门狗，与总超时**相互独立** | **NF-08** | BC-147 / BC-148（`TimeoutTest` 总超时语义）+ 新增 | **待实现**（M4-T8） |
| **D11** | 全局许可不区分轻重（`/help` 排在 2 个长跑 agent 后面） | `engine/BridgeEngine.kt:76` —— **`private val semaphore = Semaphore(maxConcurrentTasks)`**（单一全局许可，实测命中）；`:39` 注释「排队等待限时（超时回任务较多提示），执行阶段独立限时」 | **两级准入 URGENT / LIGHT / HEAVY**；URGENT 绕过许可并 cancel 目标 chat 在途任务；繁忙回复**按 chat 合批** | `AdmissionTest`（移植，非 NF）+ NF-18 | BC-144 / BC-145 / BC-167 / BC-183 / BC-205 | **待实现**（M4-T7） |
| **D12** | **去重键用 `eventId`**（飞书官方明确消息事件重推时 event_id 可能不同 ⇒ 幂等失效） | `engine/BridgeEngine.kt:125`（`if (event.eventId.isBlank())` 丢弃）、**`:130`（`if (seenEvents.put(event.eventId, System.currentTimeMillis()) != null)`）**、`:131`（`duplicate event ${event.eventId}, skip` 日志）；`eventId` 来源 `model/LarkEvent.kt:31`、`openapi/ws/V2EventParser.kt:57`、`lark/LarkCliTransport.kt:190`（**`eventId = str("event_id").ifBlank { str("message_id") }`** —— 旧实现已有回退，但**主键仍是 event_id**） | **改 `messageId`**（SDK `SafetyConfig` 作第二道网）。⇒ **双证据闭合（§5.1）**：SDK `NormalizeDedupKeys.message()` == `"message:" + messageId`；飞书官方文档明确「幂等请用 `message_id`，不要依赖 `event_id`」。**旧任何基于 eventId 的断言一律改写为 messageId** | **NF-A1**（`MessageDeduperTest`） | **BC-137**（旧断言改写目标）、BC-426 / BC-432 / BC-436 | **待实现**（M4-T7） |
| **D13** | 会话 key 只用 chatId（群内多话题上下文串味） | `agent/AgentSessionStore.kt:18` —— **`fun put(chatId: String, sessionId: String)`**（实测：键类型就是 `String chatId`，无 thread/root 维度） | 升级 **`chatId + (threadId ?: rootId ?: "")`**。⇒ **§5.8 利好**：SDK `NormalizedMessage` **原生提供** `rootId` / `threadId` / `replyToMessageId`，**无需自己挖 raw JSON** | 移植后的 `AgentSessionStoreTest`（3 方法改用复合键）+ 新增「同 chatId 不同 threadId 上下文互不串味」用例 | BC-043..045 | **待实现**（M4-T8） |
| **D14** | 启动时 `selfCheck` 同步拉起一次完整 JVM 冷启动（拖 1-3s） | `LarkBridge.kt:361` —— **`val outcome = withContext(Dispatchers.IO) { launcher.selfCheck(script) }`**（启动路径内**同步等待**）；实现 `confirm/PermissionServerLauncher.kt:87`（`fun selfCheck(script: File, timeoutMillis: Long = SELF_CHECK_TIMEOUT_MILLIS)`，**`SELF_CHECK_TIMEOUT_MILLIS = 30_000L`** 在 `:184`）⇒ **最坏可拖 30s，非仅 1-3s** | **`selfCheck = "async"` 默认**，连接成功后**后台探测**，期间 `Probing` 态 | 移植后的 launcher 单测 + `BridgeRuntimeIntegrationTest`（Probing 态） | BC-046..117（`confirm/` 72 方法全移植，🔒 脚本烘焙见 §4.5） | **待实现**（M4-T9） |
| **D15** | `LarkCliTransport` 裸用 `ProcessBuilder` 绕过自家沙箱 | `lark/LarkCliTransport.kt`（190 行处含 eventId 解析）+ `lark/LarkCliProcess.kt`（`:106`/`:173-179` 重启退避）+ `lark/CliCommandFactory.kt` + `lark/TransportFactory.kt` —— **传输层全家** | **lark-cli 传输层整体退役**；同时退役配置项 `RestartPolicy` / `OpenApiConfig.transportType` / `tokenDir` / `redirectPort` / `scopes`（方案 A 段明列）。⚠️ **`transport` 旧默认值是 `"cli"`**（§6.5）⇒ 35 key 中唯一「有常量但语义消失」的项，见 **§9 P9** | 无（能力整体消失）；退役配置的迁移处置由 NF-11 承接 | BC-118..135（cli 18 方法：3 移植 + 15 废弃）、BC-465..483（Integration） | **待实现**（M2 删除 + M7-T15） |
| **D16** | 静态全局可变状态 ×2 | **实测精确坐标（§6.4）**：① `executors/AgentExecutor.kt` **companion 内 `@Volatile private var rejectionHolder: AgentExecutor?`**（声明 **`:604-605`**，赋值 **`:95`**，读 **`:632`**，条件清空 **`:643`** 用 **`===` 引用比较只清空自己**，文件 **735 行**）；② `executors/BuiltinExecutors.kt` **object 级 `@Volatile var fileDownloader`**（声明 **`:105-106`**，消费 **`:68`**，文件 **108 行**） | **构造注入，删除**；唯一装配点 `BridgeAssembler.assemble(config, logger, gatewayFactory)`（App 与 CLI 共用）。**`clearRejectionHolder` 的 C3 修复语义须保留**：「旧实例不得在 stop 后继续产生副作用」（防被拒者仍收拒绝回复、仍写 `sender_stats.jsonl`、仍触发 OpenAPI 联系人网络调用、rejection 抢占 `unhandledReplyText` 兜底） | **NF-18**（`BridgeAssemblerParityTest`）+ **NF-14**（副作用语义）+ **§9 P12 已落地为 NF-16 `LarkSdkIsolationTest` F 组附加断言**（`findStaticVolatileVarOffenders`，`:372`） | BC-193 / BC-206、BC-474 / BC-475 | **待实现**（M4-T9） |
| **D17** | 图表 `buckets.indexOf(bucket)` 在 `forEach` 内 O(n²) | `chart/UsageBarChart.kt:54` —— **`val left = slot * buckets.indexOf(bucket) + (slot - barWidth) / 2f`**（实测逐字命中；同文件 `:96` 另有 `buckets.last().date.format(DAY_LABEL)`） | **`forEachIndexed`**。⚠️ **该文件在 `feature-larkbridge`，随 M2 删除而消失**；`rememberChartProgress()`（`chart/ChartCanvas.kt:18`，**`internal`**）同样消失 ⇒ **M6-T13 图表重写时必须重新实现本条修复**，不能视为 `:ui` 的存活 API（§6.3）。验收口径见 **§9 P6** | M6-T13 图表测试（旧 `UsageStats*Test`(2) 近 1:1 平移，标「M6 重新实现后恢复」） | BC-509..527 区间内的 chart / UsageStats 条目 | **待实现**（M6-T13） |
| **D18** | 日志/审计 `DROP_OLDEST` 静默丢数据不可观测 | **实测三处 `DROP_OLDEST` 全部无计数器**：`openapi/ws/LarkWsClient.kt:61`、`engine/BridgeEngine.kt:86`、`log/BridgeLogBus.kt:19`（`:12` 注释「replay=200 让晚进页面能回看最近 200 条；DROP_OLDEST 保证慢消费者不反压引擎」——**明确承认丢弃但无观测**） | **全部丢弃点加计数器并暴露为指标**（`droppedTotal` / `droppedRecords`）；卡片更新失败同样纳入可观测纪律（§5.3 三件套） | **NF-09** + NF-12（`droppedTotal`）+ NF-A3（卡片失败计数）+ NF-A7（陈旧丢弃计数） | BC-143（`SKIPPED` 不计数保留）、BC-299..318、BC-343..346 | **待实现**（M4-T8 / M6-T13） |
| **D19** | 无同 appId 单实例互斥 | **实测确认零命中**：全仓（`lark-bridge/src/main` + `feature-larkbridge/src`）grep **`tryLock` 零命中** ⇒ **「无单实例互斥」属实** | **`AppIdLock`**：`~/.easy_dev_toolbox/lark/locks/<sha256(appId).take(16)>.lock` + **`FileChannel.tryLock()`**；选 FileLock 而非 pid 文件的理由是**进程崩溃由 OS 自动释放，无需陈旧锁清理代码**；被占 ⇒ `BlockedByPeerInstance` + UI 明确文案 + **CLI 非 0 退出**；**App 与 CLI 共享同一把锁** | **NF-13** | 新增（旧无对应 BC） | **待实现**（M4-T7） |
| **D20** | 停止链路无优雅排空（直接 `scope.cancel()` 砍） | `LarkBridge.kt:412`（**`fun stop()`**）→ **`:424`（`scope.cancel()`）**，中间**无排空、无终态卡、无 flush、无脚本清理**（实测确认） | **十步关闭序列**（完整步骤见 §7 NF-14）；`stop(graceMillis = 5000)`，每步有超时上限，`AtomicBoolean` CAS 幂等；`onAppExit()` 与 JVM shutdown hook 都调用它 | **NF-14** + **NF-15** | BC-467..471（Idle 态零资源占用，抢救）、BC-474 / BC-475 | **待实现**（M4-T9） |

### 8.1 D1-D20 覆盖完整性自检

- **20 条全部落到「实测旧位置 + 新对策 + 守护测试 + BC 编号 + 里程碑」五元组**，无一条悬空。
- **旧实现「实测零命中」的 4 条**（D2 出站频控 / D10 空闲看门狗 / D19 单实例互斥 / 以及 D16 之外的静态状态）属**纯新增能力，无平移来源**——实现时**不能靠「参考旧代码」**，必须严格按方案规格写：D2 → NF-05、D10 → NF-08、D19 → NF-13。
- **方案表述与实测有偏差的 2 条**：**D5**（位置错指 `UserNameResolver.kt`，实为 `SenderStatsStore.kt:88-90`，见 §6.1）与 **D7**（已有 C5-P2 部分缓解，非完全未处理）。**D8** 的数字精确吻合（46 > 40）但**分布与直觉相反**（几乎全在 feature VM，lib 侧仅 8 个），影响改造重心归属。三者均**未自行改方案**，偏差已就地标注。
- **随 M2 消失、须在 M6 重新实现的 2 条**：**D7**（VM 日志台）与 **D17**（`UsageBarChart.kt:54`）——二者的旧载体都在 `feature-larkbridge`，**M4/M5 期间不存在「已修」状态**，验收点在 **M6-T13**。
- **`REPLAY_COUNT=200` → 环形 1000 的字面值断裂**（D7 / D18 交叉）：旧 `BridgeLogBusTest` 断言 `msg-100` / `msg-299` 在容量 1000 下**不成立**，移植时须改为「发布 1500 条 → `first == "msg-500"`、`last == "msg-1499"`」或等价比例断言（详见 §3.8）。


---

## 9. 待裁定点（本文档发现但**无权自行裁定**的事项）

> 本节列出整理 550 条契约过程中发现的、**方案与旧代码不一致**或**方案未覆盖**的决策点。**本文档一律不自行改方案、不自行填结论**，只把证据与影响面摆清楚供裁决。
> **编号约定**：`P7` / `P8` / `P9` / `P11` / `P12` 已在 §3、§4、§6 正文中被前向引用，编号**不得变更**；其余编号为本节新增分配。
> **阻塞级别**：🔴 = 阻塞后续里程碑启动，必须先裁；🟡 = 阻塞对应测试落地，可在该里程碑开工前裁；🟢 = 不阻塞，可事后补裁。

### 9.1 🔴 P1 —— M0 快照 tag `pre-lark-rewrite` 尚未创建（阻塞 M2）

- **实测证据**：`git tag --list` 共 8 个 —— `v0.0.3 / v0.0.4 / v0.0.5 / v0.0.9 / v0.0.10 / v0.0.11 / v0.1.0 / v0.1.1`；`git rev-parse -q --verify refs/tags/pre-lark-rewrite` **返回不存在**；`git rev-parse HEAD` == `bd03d58`，**无任何 tag 指向**；工作区另有未提交改动（`M gradle/libs.versions.toml`、`M settings.gradle.kts`、`?? lark-gateway/build.gradle.kts`、`?? lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/package-info.kt` —— **均为 M1 Spike 产物，非本任务所改**）。
- **影响面**：方案 R3 的缓解措施第一条就是「**M0 tag + T1 契约表**」，回滚手段也明写 `git checkout <M0 tag> -- lark-bridge/ feature-larkbridge/`。**tag 不落 ⇒ M2 拆除旧模块后，本文件 §3 中所有 🔒「禁止改写，逐字平移」条目（§4.1 / §4.2 / §4.3 / §4.5 / §4.7）将失去可核对的原始载体**，R3「行为回归（静默回归）」的触发后应对手段失效。
- **本任务的处置**：T1 被明确禁止执行任何 git 写操作（tag / commit / push / reset），故**只登记不执行**。
- **需裁决**：① 是否由用户自行在当前 HEAD 创建 `pre-lark-rewrite`；② **未提交的工作区改动（`lark-gateway/` 等）是先提交再打 tag，还是打 tag 后再提交**——这决定 tag 是否包含 M1 spike 产物。**建议先提交再打 tag**，但**不自行决定**。
- **裁决前不得启动**：M2。

### 9.2 🔴 P2 —— SDK `ws.Client` 线程泄漏的缓解方案（决定 NF-15 的 `threadCount` 阈值）

- **实测证据（§5.10）**：`ws.Client` **构造即创建非 daemon 固定线程池**，且**全类无 `shutdown` 方法**，`close()` 只做 disconnect ⇒ **每个 start/stop 周期泄漏 ≥ 1 个非 daemon 线程**，且**会阻止 JVM 自然退出**。
- **影响面**：① **NF-15 `StartStopLeakTest` 的 `threadCount` 断言无法定阈值**（其余三项 `liveProcesses==0` / `cardStreamsOpen==0` / `scope.children.count()==0` 不受影响，可正常落地）；② **NF-14 十步关闭的第 ⑥ 步「`gateway.close()` 释放 SDK 线程/连接池(5s)」在 SDK 现状下无法真正兑现**——方案该步的措辞与 SDK 实测能力有落差；③ App 侧「进设置页调参 → `recreate()`」是高频路径（旧 `LarkBridge.kt:53` / `:408` 注释已承认此模式），泄漏会累积；④ CLI `serve` 子命令退出时可能需 `exitProcess` 强制退出。
- **可选方向（仅供裁决参考，均未定案）**：① 反射调用 SDK 内部线程池的 `shutdown`；② 每进程只允许一次 `ws.Client` 构造（start/stop **复用同一实例**，靠 connect/disconnect 切换）；③ 接受泄漏但用 `Runtime.addShutdownHook` + `exitProcess` 强制退出；④ 向 SDK 上游提 issue 并锁定 2.8.5 版本。
- **本文档不自行填写结论。** 裁决后需回填两处：§5.10 的缓解方案、NF-15 的 `threadCount` 阈值。
- 🔴 **已裁定并回填（Leader + S6 真机，2026-09-08，T16）**：采用方向 **①+③ 组合**（`close()` 反射 `shutdownNow()` + 宿主显式 `exitProcess(0)`；残留 2 非 daemon 线程）。§5.10 缓解方案与 NF-15 `threadCount` 均已回填——**`threadCount` 不在桥接级断言**，由 `scope.children.count()==0` + `CliExitProcessTest` + `GatewayCloseReleasesThreadsTest` 三方间接覆盖。NF-15 承载类实测为 `BridgeStartStopLeakTest`。详见 §5.10 / NF-15 / `gateway-contract.md §16.6`。

### 9.3 🟡 P3 —— `RegisterApp` 扫码建应用 API 是否纳入首批

- **实测证据（§5.11）**：`com.lark.oapi.scene.registration.RegisterApp` **确实存在**，**阻塞式轮询最长约 600s**。
- **影响面**：① 属**新增能力**，不在「全功能平移」范围内（旧实现无此功能，**无对应 BC 条目**）；② 600s 阻塞轮询必须跑在自有 `net` dispatcher（NF-A4 / §5.6），且需要**独立的取消/超时语义**（与 NF-08 的 120s 空闲看门狗、NF-07 的 kill 升级链都不同量级）；③ **影响 M5-T10 的 CLI 子命令数量**——方案明写「**CLI 五子命令**」，若纳入则变六个，M5-T10 的任务书与验收清单都要改；④ R21（首次配置卡在后台）的缓解里提到「`RegisterApp` 扫码流程可自动完成大部分后台配置」，**若不纳入，R21 只能靠文档步骤顺序 + UI 引导横幅缓解**。
- **本文档不自行填写结论。**

### 9.4 🟡 P4 —— `ProgressCardRenderer.Limits.cardMaxChars = 5000` 在 CardKit 下是否放宽

- **实测证据**：旧 `progress/ProgressCardRenderer.kt:18-21` 的 `Limits` = `maxEntries=12` / `entryMaxChars=200` / `cardMaxChars=5000`；旧测试用 `Limits(12, 200, 3000)` 断言 `body.length <= 3200`（见 §4 与 BC-335..339）。
- **冲突点**：**5000 字符上限是为「消息编辑」时代设的**（D1）；新架构改 CardKit 后，飞书单卡硬上限是 **30KB**，方案 R7④ 的主动分段预算是 **24KB**，NF-03 断言「40KB ⇒ 2 段」。**若保留 `cardMaxChars = 5000`，则 24KB 分段预算永远用不到，NF-03 的「40KB ⇒ 2 段」断言与实际渲染行为矛盾**（渲染侧早就在 5000 处截断了，到不了 24KB）。
- **需裁决**：① `cardMaxChars` 是否提升到 **24576（24KB）** 与分段预算对齐；② `maxEntries=12` / `entryMaxChars=200` 是否同步放宽；③ 旧断言 `body.length <= 3200` 是**实测定稿型**（BC-335..339 标移植），改数值属于**刻意变更**，需明确授权后才能改，否则按 §1 纪律算回归。
- **影响**：NF-02 / NF-03 / BC-335..339 的断言数值。

### 9.5 🟡 P5 —— 已退役配置项在「未知键保留」语义下是保留还是显式删除

- **冲突点**：方案 A 段明列**整体消失**的配置项：`RestartPolicy`、`OpenApiConfig.transportType` / `tokenDir` / `redirectPort` / `scopes`（D15 传输层退役的连带项）。而 **NF-11 `ConfigMigrationUnknownKeyPreservedTest`（R6）要求「未知顶层段 / 未知键必须原样保留」**（前向兼容）。二者语义**直接冲突**：这些退役键迁移进新 JSON 文档后，**究竟算「应删除的已知退役项」还是「应保留的未知键」**？
- **两种走法的后果**：① **保留** ⇒ 新 JSON 文档里长期躺着 5 个永远不被读取的键，用户手改会产生「改了没效果」的困惑，且下次迁移仍要处理；② **显式删除** ⇒ 破坏 NF-11 的「未知键保留」不变量的**可测性**（测试无法区分「故意删的退役键」与「误删的前向兼容键」），需要在 schema 里维护一份「已知退役键黑名单」。
- **需裁决**：走 ① 还是 ②；若走 ②，黑名单放在哪（schema 常量 / 迁移代码内联）。
- **影响**：NF-11 断言形态、M7-T15 迁移实现、§6.5 的 35 key 映射表中这 5 项的处置列。

### 9.6 🟡 P6 —— M6-T13 图表 / UsageStats 的验收口径（D17 归属）

- **实测证据（§6.3）**：`rememberChartProgress()` 在 `feature-larkbridge/.../chart/ChartCanvas.kt:18`，是 **`internal`**，**随 M2 删除而消失**；D17 的缺陷载体 `chart/UsageBarChart.kt:54` 同样消失。
- **冲突点**：方案映射原则把 `UsageStats*Test`(2) 列为「**近 1:1 平移**」，但**被测源文件已不存在**——平移的对象只能是「重新实现的图表」。方案 M6-T13 未细化图表的**功能等价范围**（图表类型、桶粒度、交互、`DAY_LABEL` 格式等）。
- **需裁决**：① M6-T13 是否要求图表与旧实现**功能等价**（含 `UsageBarChart.kt:96` 的 `buckets.last().date.format(DAY_LABEL)` 标签格式）；② **D17（`forEachIndexed`）的验收点是否明确挂在 M6-T13**（本文档 §8 已如此标注，但方案 D17 行未写里程碑）；③ 旧 `UsageStats*Test` 标「M6 重新实现后恢复」是否算**验收缺口**（本文档判定：**不算**，因为 M4/M5 期间载体不存在，但需用户确认此口径）。
- **影响**：§8 D17 行的状态判定、M6-T13 任务书的验收清单、BC-509..527 区间条目的落点。

### 9.7 🔴 P7 —— `workspace.envAllowlist` 默认 8 键 vs 旧实测 11 键 + Windows 平台化追加逻辑（已在 §3.9 / §4.9 引用）

- **实测证据**：旧 `lark/SandboxedProcessFactory.kt` 的 `BASE_ENV_KEYS`（`:57-61`，**11 键**）= `"PATH","HOME","LANG","LC_ALL","TZ","http_proxy","https_proxy","no_proxy","HTTP_PROXY","HTTPS_PROXY","NO_PROXY"`；`WINDOWS_REQUIRED_ENV_KEYS`（`:68-71`，**11 键**）= `"SystemRoot","SystemDrive","USERPROFILE","HOMEDRIVE","HOMEPATH","APPDATA","LOCALAPPDATA","COMSPEC","PATHEXT","TEMP","TMP"`；`effectiveBaseKeys(isWindows)`（`:74-75`）= `if (isWindows) BASE + WINDOWS_REQUIRED else BASE`。
- **冲突点**：方案新配置文档 `workspace.envAllowlist` 默认值为 **8 键** `["PATH","HOME","LANG","LC_ALL","TZ","HTTP_PROXY","HTTPS_PROXY","NO_PROXY"]`，**缺 3 个小写变体** `http_proxy` / `https_proxy` / `no_proxy`；且 **`WINDOWS_REQUIRED_ENV_KEYS` 与 `effectiveBaseKeys(isWindows)` 的平台化追加逻辑在方案中无对应表述**。
- **后果（若照方案默认值实现）**：① **Linux 下 curl / git / python 优先读小写代理变量 ⇒ agent 子进程静默失去代理能力**（用户无感知，只在需要代理的网络环境下失败）；② **Windows 下缺 `SystemRoot` / `COMSPEC` / `PATHEXT` 等 ⇒ 子进程可能直接起不来**（这不是性能问题，是功能性破坏）；③ **BC-420 / BC-421 是明确的「零回归锚点」，照新默认值实现会直接测试失败**。
- **需裁决**：① 默认值补回 3 个小写变体（变 11 键）；② Windows 11 键是**硬编码平台化追加**（不进用户配置，同旧 `effectiveBaseKeys`）还是**并入默认 allowlist**；③ 若选硬编码追加，`envAllowlist` 的语义需在文档里明确为「用户额外允许的键」而非「完整白名单」。
- **影响**：BC-419..421、M4-T6/T8 的 `SandboxedProcessFactory` 平移、配置文档默认值。

### 9.8 🟡 P8 —— `--sender-stats-dir` CLI 旗标的处置（已在 BC-134 引用）

- **实测证据**：旧 CLI 有 `--sender-stats-dir` 旗标（BC-134，标**废弃**，关联 D8）；新配置文档 `observability` 段**无此字段**（路径固定）。
- **冲突点**：方案 M5-T10 要求「**CLI 五子命令**」且 `CliConfigOverrideTest` 断言「CLI **不再逐字段映射**」，但**未明确 `--sender-stats-dir` 是彻底删除还是保留为覆盖项**。若彻底删除，属**功能削减**，与决策 3「首批范围是全功能平移，现有能力一个不少」有张力（虽然 D8 已授权删除约 30 个业务字段旗标）。
- **需裁决**：① 彻底删除（归入 D8 授权的 30 个旗标）；② 保留为「数据根目录」注入的一部分（R19 要求 lib 侧数据根路径由**宿主注入**，`--sender-stats-dir` 正是注入点之一，**删除后 CLI 场景如何注入 stats 目录需另有答案**）。
- **影响**：BC-134 三态、M5-T10 CLI 旗标清单、R19 的宿主注入实现。

### 9.9 🟡 P9 —— `transport` key（旧默认值 `"cli"`）的迁移处置（已在 §6.5 引用）

- **实测证据（§6.5）**：35 key 中 `transport` 的**旧默认值是 `"cli"`**，而 **D15 已退役 lark-cli 传输层** ⇒ **这是 35 key 中唯一一个「有常量但语义消失」的项**。
- **冲突点**：① 迁移到新文档时该 key **无目标字段**；② 若用户显式设过 `transport="openapi"`，新架构下 openapi 传输由 SDK 接管，**该值也无需迁移**；③ 但 NF-11 的「未知键保留」（R6）与 P5 的裁决会决定它是被保留还是被删。
- **需裁决**：方案 M7-T15 映射表须**明确标注**该 key 的处置（本文档倾向标「**忽略不迁移**」，与已确认无常量的残留 key `lark_bridge_event_key` / `lark_bridge_lark_cli_path` / `lark_bridge_region` 同类处理），但**不自行改方案映射表**。
- **影响**：§6.5 映射表、NF-11、M7-T15。

### 9.10 🟡 P10 —— `progressPatchIntervalMillis` 用户可调项在 D3 固定节流下是否保留

- **实测证据**：旧 `model/BridgeConfig.kt:157` 有 `progressPatchIntervalMillis`，注释「**进度卡片 patch 合并窗口毫秒（节流；终态更新不受此窗口限制）**」；`LarkBridge.kt:198` 将其传入 `ProgressReporter` → `ProgressThrottler`。
- **冲突点**：**NF-02 / R7② 把节流窗口固定为 250ms（= 4/s，对飞书 10 次/秒上限留 60% 余量）**，方案未在新配置文档中给 `progressPatchIntervalMillis` 留字段。⇒ **这是一个用户当前可调、新架构消失的旋钮**，与决策 3「全功能平移」有张力；但方案另有已否决项「**把 SDK 调优旋钮暴露进用户配置文档**」，倾向于**不暴露**。
- **需裁决**：① 彻底删除该旋钮（固定 250ms）；② 保留为配置项但**加上限钳制**（例如 `coerceAtMost(250)`，防用户调小导致触发飞书频控）——注意旧实现**已有钳制先例**：确认超时的 `MIN_CONFIRM_TIMEOUT_SECONDS=10L`（`LarkBridge.kt:472`）与 `MAX_CONFIRM_TIMEOUT_SECONDS=3600L`（`BridgeSecurityCard.kt:34`），见 §4。
- **影响**：NF-02 断言、M4-T5 配置 schema、BC-340..346。

### 9.11 🟡 P11 —— 卡片回调的 `rawContentType` 实际取值（待 M1-S6 / M3-T3 真机确认）（已在 §5.8 引用）

- **实测证据（§5.8）**：`NormalizedMessage` 的实际字段名是 **`rawContentType`（不是 `messageType`）**，且**无 `eventId` 字段**。
- **未决部分**：BC-433 旧断言是 `messageType == "card_action"`，改写为 `rawContentType` 后，**卡片回调事件的 `rawContentType` 实际字符串取值尚未真机确认**（源码级 spike 只能确认字段名，不能确认飞书服务端下发的值）。
- **需裁决 / 需实测**：**M1-S6（卡片流式限制）或 M3-T3 / M5-T11 真机联调时抓取实际取值**，回填 BC-433 与 NF-A2 的断言字面值。
- **影响**：BC-433、NF-A2、`HandlerKinds` 的 `RouteKind` 判定（卡片回调路由）。**在实测前，该断言不得凭猜测写死。**

### 9.12 🟢 P12 —— 是否新增「顶层 object/companion 内不得存在 `@Volatile var`」守护断言（已在 §3.5 / §6.4 引用）

- **背景**：D16 的两处静态全局可变状态（`AgentExecutor.kt:604-605` `rejectionHolder`、`BuiltinExecutors.kt:105-106` `fileDownloader`）在新架构改构造注入后，**NF-18 `BridgeAssemblerParityTest` 只能守护「装配确定性」，无法阻止未来有人再写一个 `object` 级 `@Volatile var`**。
- **建议（非裁定）**：新增一条源码扫描守护断言——**`:lark-bridge` 顶层 `object` 与 `companion object` 内不得存在 `@Volatile var`**，把 D16 从「靠纪律」变成「结构上不可能出错」。实现形态可参照 NF-16 `LarkSdkIsolationTest` 的源码 grep 风格（比反射更易维护，且不受混淆/内联影响）。
- **需裁决**：是否纳入（会新增第 26 个非功能测试，或作为 NF-18 的一条附加断言）。**本文档不自行决定。**
- 🔴 **已裁定并落地（Leader，2026-09-08，T16 回填）**：**已落地为 NF-16 `LarkSdkIsolationTest` 的一条附加断言**（不新增独立测试类）——F 组 `findStaticVolatileVarOffenders`（`LarkSdkIsolationTest.kt:372` `F no static volatile mutable state in lib modules`）。判据为**作用域感知**：只禁 `:lark-bridge`/`:lark-gateway` 生产源集里 **object/companion object 体内或顶层** 的 `@Volatile var`；**类级实例字段放行**（如 `LarkCardStreamSink` 的实例级 volatile 状态合法）。详见 `config-domain-spec.md §14.4 / §15 #13`。

### 9.13 🟢 P13 —— `Probing` 态的超时上限（旧 `SELF_CHECK_TIMEOUT_MILLIS = 30_000L` 是否沿用）

- **实测证据**：D14 的旧位置 `LarkBridge.kt:361` 同步调 `launcher.selfCheck(script)`，实现 `PermissionServerLauncher.kt:87`，默认超时 **`SELF_CHECK_TIMEOUT_MILLIS = 30_000L`（`:184`）** ⇒ **方案表述「拖 1-3s」是常态值，最坏可拖 30s**。
- **未决部分**：方案对策是 `selfCheck="async"` 默认 + 连接成功后后台探测 + 期间 `Probing` 态，但**未给出 `Probing` 态的最长停留时间**，也未说明**探测失败后的状态迁移**（回 `Running` 带降级标记？还是进独立的 `Degraded` 态？）。
- **需裁决**：① `Probing` 上限沿用 30s 还是缩短；② 探测失败的状态迁移与用户可见文案；③ **`TAIL_CHARS = 400`（`:187`，自检输出尾部截断）是否保留**——这是 🔒 实测定稿常量（§4.5），但方案未在 `Probing` 语境下提及。
- **影响**：M4-T9 `Bridge.kt` 生命周期状态机、BC-046..117 中的 selfCheck 相关条目。

### 9.14 🟢 P14 —— §7.2 追加的 7 项测试（NF-A1..NF-A7）是否批准纳入范围

- **背景**：方案「测试策略」明列 **18 个**新增非功能测试。本文档依据 §5 的 **11 条 SDK 源码级实测结论**追加了 **7 项**（`MessageDeduperTest` / `LarkInboundMapperTest` / `LarkGatewayErrorMappingTest` / `GatewayDispatcherTest` / `FileDownloadStreamingTest` / `LarkPolicyMapperTest` / `StaleFilterTest`）。
- **口径澄清**：其中 `LarkInboundMapperTest` 与 `LarkPolicyMapperTest` **方案映射原则①已明列**（只是未计入「18 个新增非功能测试」清单，属**方案自身清单口径遗漏**，非本文档新增）；`MessageDeduperTest` / `StaleFilterTest` 对应方案 T7 文件清单内的 `MessageDeduper.kt` / `StaleFilter.kt`（**有实现文件、无对应测试**）；**真正由本文档新增的是 `LarkGatewayErrorMappingTest` / `GatewayDispatcherTest` / `FileDownloadStreamingTest` 三项**，分别承接 §5.3（卡片失败被 SDK 静默吞掉）/ §5.6（commonPool 阻塞 IO）/ §5.9（100MB `byte[]` OOM）——**这三条 SDK 缺陷若不设测试守护，等于实测白做**。
- **需裁决**：是否批准这 3 项纳入 M3-T3 范围（会扩大 T3 工作量，但 T3 本身就是 SDK 隔离适配层，归属自然）。

### 9.15 🔴 P15 —— **本文件所在路径 `openspec/changes/` 被 `.gitignore` 忽略**（交付物无法入库）

- **实测证据**：`git check-ignore -v openspec/changes/rewrite-lark-bridge/behavior-contract.md` 返回 **`.gitignore:21:openspec/changes/`**；`git status --porcelain --untracked-files=all` 中**完全看不到本文件**（也看不到 `spike-findings.md`）。
- **后果**：① **M3-T1 与 M1 的两个关键交付物都不会被提交**，即使「用户自行提交」也不会包含它们；② M2 删除旧模块后，本文件是 550 条断言的**唯一载体**——若仅存于未纳管的工作区，一次 `git clean -fdx` 或换机就会**彻底丢失**，与 P1（tag 未落）叠加后风险更高；③ M4/M5/M6 的子 agent 需要从仓库读取本文件作为验收依据，未纳管 ⇒ 无法通过 `git checkout` 分发。
- **需裁决**（三选一，本文档**不自行改 `.gitignore`**，因为 T1 被禁止修改任何现有文件）：① 在 `.gitignore:21` 为 `rewrite-lark-bridge/` 加否定规则（`!openspec/changes/rewrite-lark-bridge/`）并纳入版本控制；② 将本文件移到已纳管路径（如 `docs/lark-bridge/behavior-contract.md`），**需同步修改所有引用本路径的任务书**；③ 维持忽略，但用户自行将文件备份到仓库外并知晓丢失风险。
- **裁决前不得启动**：M2（与 P1 同为 M2 的前置）。

### 9.16 待裁定点汇总

| 编号 | 主题 | 级别 | 阻塞的里程碑 | 裁决后需回填的位置 |
|---|---|---|---|---|
| **P1** | M0 `pre-lark-rewrite` tag 未创建 | 🔴 | **M2** | §2.4、本节 |
| **P2** | `ws.Client` 线程泄漏缓解（决定 `threadCount` 阈值） | 🔴 | M4-T9 | §5.10、NF-15、§8 D20 |
| **P3** | `RegisterApp` 是否纳入首批 | 🟡 | M5-T10 | §5.11、M5-T10 任务书 |
| **P4** | `cardMaxChars=5000` 是否放宽到 24KB | 🟡 | M4-T8 | NF-02 / NF-03、BC-335..339 |
| **P5** | 退役配置项 vs「未知键保留」语义冲突 | 🟡 | M7-T15 | NF-11、§6.5 |
| **P6** | M6-T13 图表验收口径（D17 归属） | 🟡 | M6-T13 | §6.3、§8 D17 |
| **P7** | env allowlist 8 键 vs 11 键 + Windows 平台化追加 | 🔴 | M4-T6/T8 | §3.9、§4.9、BC-419..421 |
| **P8** | `--sender-stats-dir` 处置 | 🟡 | M5-T10 | BC-134 |
| **P9** | `transport` key（默认 `"cli"`）迁移处置 | 🟡 | M7-T15 | §6.5、NF-11 |
| **P10** | `progressPatchIntervalMillis` 是否保留 | 🟡 | M4-T5/T8 | NF-02、BC-340..346 |
| **P11** | 卡片回调 `rawContentType` 实际取值 | 🟡 | M3-T3 | BC-433、NF-A2 |
| **P12** | 是否新增 `@Volatile var` 守护断言 | 🟢 | 不阻塞 | §3.5、§6.4、NF-18 |
| **P13** | `Probing` 态超时上限与失败迁移 | 🟢 | M4-T9 | §8 D14、BC-046..117 |
| **P14** | NF-A1..A7 追加测试是否批准纳入 | 🟢 | M3-T3 | §7.2 |
| **P15** | **`openspec/changes/` 被 gitignore，交付物无法入库** | 🔴 | **M2** | `.gitignore:21`、本节 |


---

## 10. 平移执行清单（供 Leader 驱动 M3 / M4 / M5 / M6 / M7）

### 10.1 五个执行组

| 组 | 规模 | 执行纪律 |
|---|---|---|
| **① 移植组** | **42 文件 / 441 方法** | 近 1:1 平移。凡标 🔒 的条目**禁止改写**，字面值以 §4 为准；**按测试类拆分而非按文件 1:1**（§2.1 列出 3 处「文件名 ≠ 类名」、2 处「单文件多类」） |
| **② D# 改写组** | 散布在移植组内，**10 个 D# 涉及断言改写**：D12（eventId→messageId）、D13（会话复合键）、D16（静态状态→构造注入）、D11（两级准入）、D14（async selfCheck）、D5（删 runBlocking，**目标文件是 `stats/SenderStatsStore.kt:88-90` 而非 `openapi/UserNameResolver.kt`**）、D4（批量落盘）、D8（单一 UiState）、D17（`forEachIndexed`）、D18（丢弃计数） | **不许原样照抄旧断言**，必须按 §3「锁住的行为」列给出的**新形态**写；每条改写在 §8 有对应行可查 |
| **③ SDK吸收组** | **8 文件 / 42 方法**（BC-423..464） | 旧测试**删除**，字面约束改写为 NF-A2 `LarkInboundMapperTest` + NF-A6 `LarkPolicyMapperTest` + NF-A3 `LarkGatewayErrorMappingTest`；用**录制的 `NormalizedMessage` 样本**驱动，不连真机 |
| **④ 废弃组** | **10 文件 / 67 方法**（44 lib + 23 UI） | 行为改由 `BridgeRuntimeIntegrationTest`（+ `SyntheticGateway`）/ `CliConfigOverrideTest` / `LegacyConfigImporterTest` 承接。**⚠️ 必须先从废弃文件里抢救 3 组断言（见 §10.3），再删文件** |
| **⑤ 新增组** | **18（方案明列）+ 7（本文档追加，待 P14 批准）= 25 项** | 见 §7；每项须在对应里程碑的 `./gradlew build` 中**实际存在并跑绿**，只在文档列名不算完成 |

### 10.2 按里程碑的落点索引

| 里程碑 T# | 承接的 BC 区间 / 条目 | 承接的 NF | 备注 |
|---|---|---|---|
| **M3-T3**（`:lark-gateway` 网关契约与 `LarkChannel` 适配） | BC-423..464（SDK吸收 42 方法的全部字面约束）、BC-414..418（下载路径策略） | NF-A2 / NF-A3 / NF-A4 / NF-A6（+ NF-A5 的网关侧） | **P11 未裁前，BC-433 的 `rawContentType` 取值断言不得写死**；`MessageGateway` 保持最小面 **12 成员**（R1） |
| **M3-T4**（SDK 隔离守护） | BC-423..464 的隔离前提 | **NF-16** | 验收必须包含「**故意在 `:core` 加 `import com.google.gson.Gson` 后测试失败**」——只跑「通过」不算验证 |
| **M4-T5**（配置域） | BC-006（模板缺 `{prompt}` 校验失败）、BC-319..324（`model/` 默认值双态）、§6.5 的 35 key 实测清单 | NF-10 | **P7 / P10 影响 schema 默认值**；lib 侧不得引用 `JLog`/`JResult`/`JErrorCode`/`AppDataStore`/`FileUtils`（R19） |
| **M4-T6**（进程原语，可与 T5 并行） | BC-162..288 中的进程/管道/沙箱条目（🔒 见 §4.5 / §4.6 / §4.9） | NF-06 / NF-07（+ NF-A5 的落盘侧） | **禁止复用 `SimpleShell`/`StreamingShell`/`InteractiveShell`**；agent 子进程与 MCP stdio **一律 `Charsets.UTF_8`**（R11） |
| **M4-T7**（处理链） | BC-136..161（engine 26 方法重组为 `EventIntakeTest` / `AdmissionTest` / `TimeoutTest`）、BC-144 / BC-145 / BC-147 / BC-148 / BC-167 / BC-183 / BC-205 | NF-01 / NF-05 / NF-13 / **NF-A1** / NF-A7 | **NF-01 的 `<1ms` 是源码实测硬需求（§5.7），非方案假设**；路由顺序来自 `routes` 数组声明式优先级 |
| **M4-T8**（handlers / agent / confirm / observe / files） | BC-001..045（agent 45，🔒 `AgentBackend.kt` 341 行）、BC-046..117（confirm 72，🔒 脚本烘焙）、BC-162..288（executors 127）、BC-289..298（filemsg 10，🔒 zip-slip 双重校验）、BC-299..318（audit + stats 20）、BC-325..346（progress + log）、BC-347..422（quickcommand / reply / util / lark） | NF-02 / NF-03 / NF-04 / NF-08 / NF-09（+ NF-A5 的 executor 侧） | **本里程碑承接的 BC 最多（约 380 条）**；🔒 三块逐字平移，字面值查 §4.1 / §4.2 / §4.3 / §4.5 / §4.7；**P4 影响 NF-02/NF-03 阈值** |
| **M4-T9**（门面与唯一装配点） | BC-465 / BC-472 / BC-473（Integration 抢救）、BC-474 / BC-475（`rejectionHolder` 语义抢救）、BC-467..471（Idle 零资源抢救）、BC-476（确认超时钳制抢救） | NF-14 / NF-15 / NF-18 | **P2 未裁前 NF-15 的 `threadCount` 阈值留空，其余三项正常落地**；`BridgeAssembler.assemble` 是 App 与 CLI 的**唯一**共用装配点 |
| **M5-T10**（CLI 五子命令） | BC-118..120（3 移植）、BC-121..135（15 废弃 → `CliConfigOverrideTest`，断言 CLI **不再逐字段映射**） | — | **P3 影响子命令数量（五 → 六）；P8 影响 `--sender-stats-dir`** |
| **M5-T11**（端到端真机验证闸门） | 全部 🔒 条目的真机复核 + **P11 的 `rawContentType` 取值实测** | — | **人工闸门，通过前不开始 M6**；需提前告知用户「飞书后台核对清单」7 项（含**第 3/4 步顺序陷阱**：后台保存长连接订阅方式时本地必须已有一条 SDK 长连接处于已连接状态，R21） |
| **M6-T12**（feature 骨架） | BC-528..550（UI 4 个废弃文件 23 方法的 DataStore 字面映射 → `LegacyConfigImporterTest`） | NF-17 | **与 M7-T15 的分工**：T12 建 importer 骨架 + 字面映射断言；T15 完成 35 key 全量导入 + 未知键保留 |
| **M6-T13**（UI，**每张卡片写完即跑 `./gradlew :composeApp:jvmTest`**） | BC-484..527（UI 移植 44 方法）、**D7 / D17 的实际验收点** | NF-12 | `rememberChartProgress()`（`chart/ChartCanvas.kt:18`，`internal`）**必须重新实现**（§6.3）；**P6 影响图表验收口径**；UI 铁律 + **零白名单条目**（需在 `Dimens.kt` 新增 4 个 token 并登记 `docs/core/theme.md`） |
| **M6-T14**（导航回填） | `main.kt` 与 `ToolPageRegistry` **原样加回 M2 删掉的行**（包名类名全部复用） | — | **`ToolPageRegistry.kt:63` 的 `navSections()` filter 始终不动**；`core/.../page/ToolPage.kt:15` 的 `LarkBridge` 枚举值在 M2 期间就**保留** |
| **M7-T15**（配置迁移） | §6.5 的 35 key（`LarkBridgeTextField` 14 + `LarkBridgeConfigStore` 21）**1:1 精确吻合** | NF-11 | **P5 / P9 影响退役键处置**；残留 3 key（`lark_bridge_event_key` / `lark_bridge_lark_cli_path` / `lark_bridge_region`，实测代码中确已无常量）按「**忽略不迁移**」处理 |
| **M7-T16**（文档收口） | 本文件 §9 的裁决结果回填 + `AGENTS.md` 三条例外（约定 1 / 约定 2 / 约定 6）与**新增第 8 条**（飞书 SDK 只允许出现在 `:lark-gateway`，由 `LarkSdkIsolationTest` 守护）+ `docs/core/theme.md` 4 个新 token 登记 | — | `docs/core/theme.md:181-182` 的旧 larkbridge 白名单须与 **M2 同提交**清理（R5：`UiArchitectureGuardTest.kt:400` 防腐化断言对不存在文件直接 fail） |

### 10.3 ⚠️ 从「废弃文件」中抢救的 3 组断言（**删文件前必须先救出**）

废弃组的 10 个文件 / 67 个方法整体作废，但其中 **3 组断言锁住的是与旧传输层无关的通用契约**，随文件删除会**静默失传**（R3 的典型触发形态）：

1. **确认超时钳制纯函数（BC-476）** —— 出自废弃文件 `LarkBridgeConfirmLifecycleTest.kt`（10 方法**文件级**废弃，此 1 方法**方法级移植**，见 §2.3 口径重叠说明）。🔒 字面值：`MIN_CONFIRM_TIMEOUT_SECONDS = 10L`（`LarkBridge.kt:472`）、`clampedConfirmTimeoutMillis(s) = s.coerceAtLeast(10L) * 1000`（`:478-479`）、`MAX_CONFIRM_TIMEOUT_SECONDS = 3600L`（`BridgeSecurityCard.kt:34`）、`resolveConfirmTimeoutUpperBound(min) = minOf(3600L, (min * 60).coerceAtLeast(1L))`（`:40-41`）。**落点**：M4-T8 `ConfirmTimeoutClampTest`。
2. **Idle 态零资源占用 / recreate 不泄漏端口（BC-467..471）** —— 出自废弃的 `LarkBridge*IntegrationTest`。语义依据：旧 `LarkBridge.kt:53` / `:408` 注释明写「宿主 `LarkBridgeManager.recreate()` 语义是 stop() + new，**Idle 态（进设置页调参的常态）也必须清理干净**」。**落点**：NF-15 `StartStopLeakTest` + NF-14 `TeardownCompletenessTest`（M4-T9）。
3. **静态 `rejectionHolder` 注销语义（BC-474 / BC-475）** —— 出自废弃文件，但锁住的是 **C3 修复的通用契约**：「**旧实例不得在 stop 后继续产生副作用**」（旧代码用 `===` 引用比较**只清空自己**，不覆盖后来者，防「关闭 agent → 重建」后旧实例残留导致：被拒者仍收拒绝回复、仍写 `sender_stats.jsonl`（违反「未启用不统计」承诺）、仍触发 OpenAPI 联系人网络调用、rejection 抢占 `unhandledReplyText` 兜底契约）。新架构虽无静态 holder（D16），**该语义必须保留断言**。**落点**：NF-14（M4-T9）。

### 10.4 本文件自身的完成度 checklist

- [x] §1 文档目的与使用方式（含「**无对应条目的步骤不算完成**」双向约束）
- [x] §2 基线统计（60 文件 / 550 方法 / 三态双口径 / 2 处口径重叠明示 / M0 tag 风险登记）
- [x] §3 主契约表 **BC-001 .. BC-550 全 550 条**，6 列齐备，分 12 个小节
- [x] §4 实测定稿型断言清单（§4.1-§4.15，🔒 标记「禁止改写，逐字平移」）
- [x] §5 SDK 2.8.5 实测导致的断言调整（11 条，含 2 条 `TBD(用户裁决中)`）
- [x] §6 仓库实测校正（6 条）
- [x] §7 新增非功能测试清单（**18 项方案明列 + 7 项本文档追加**，含 NF ↔ BC 交叉索引）
- [x] §8 D1-D20 验收对照表（**「旧实现位置」列全部本轮 grep / Read 实测确认**）+ 覆盖完整性自检
- [x] §9 待裁定点 **P1-P15**（编号与 §3 / §4 / §6 的前向引用一致）+ 汇总表
- [x] §10 平移执行清单（5 组 + 按里程碑落点索引 + 3 组抢救断言）
- [ ] **§9 P1-P15 的裁决结果回填**（待用户裁决，**非本任务范围**）
- [x] **§5.10 / NF-15 `threadCount` 的 `TBD` 消解**（P2 已裁定：`close()` 反射 `shutdownNow()` + 宿主 `exitProcess`；`threadCount` 不在桥接级断言，三方间接覆盖；承载类 = `BridgeStartStopLeakTest`）
- [ ] **§5.11 `RegisterApp` 的 `TBD`**（P3：依方案**延后**，`MessageGateway.registerApp` 先返回 Unsupported，UI 给禁用态按钮 + 说明；属新增能力非平移，归 T11/后续）
- [ ] **BC-433 的 `rawContentType` 取值实测回填**（依赖 P11，M1-S6 或 M5-T11）
- [ ] **§4.1 claude `streamJson=true` 分支（`--output-format stream-json --verbose`，源码 `AgentBackend.kt:121-126`）的 BC-018a 补写**——旧测试**零覆盖**，但 D1 的 CardKit 流式依赖该输出格式，须在 M4-T8 补断言

---

## 11. T16 修正记录与脚注补录（2026-09-08，设计裁定 / 实测回填）

> 🔴 **本节为追加脚注 + 修正记录，不改任何断言本体、不删条目、不改「移植/SDK吸收/废弃」标注**（遵守 T16 修改纪律）。§3 主契约表内本轮只动了「落 XXXTest」列（`AssemblerParityTest` 16 处引用回填）与 NF-15 承载列/①`threadCount`（原 `TBD` 回填为裁定）。

### 11.1 §B.3 38 MISS 回填（`AssemblerParityTest` 16 处引用改指实际承载）

计划类 `AssemblerParityTest` 在 `build/audit-bc-mapping.md §B.3` 判定为**零承载**，task 21 新建 `BridgeAssemblerParityTest`（16 tests，`:lark-bridge`）承接装配唯一性/parity 语义。16 处引用（原行号 231/233/255/395/396/397/398/401/404/406/641/658/1216/1261/1318/1421）回填如下：

| 引用点 | 原计划 | 实际承载（回填） |
|---|---|---|
| BC-152（route 顺序，行 255） | `RouteOrderTest` + `AssemblerParityTest` | `RouteOrderTest` = `pipeline/RoutingTest.kt:52`（数组前者胜）/`:100`（enabled=false 跳过）；parity 部分 → `BridgeAssemblerParityTest` |
| BC-474/475（rejectionHolder 注销，行 641/658） | `AssemblerParityTest` | `confirm/ConfirmServiceTest.kt:186`（BC-474 两实例互不干扰）/`:211`（BC-475 cancelAll 后旧实例不再接受裁决）；装配唯一性另由 `BridgeAssemblerParityTest` 守护 |
| D16 静态态反射守护（P12，行 1318/1421） | 建议新增守护 | **已落地为 NF-16 `LarkSdkIsolationTest` F 组附加断言** `findStaticVolatileVarOffenders`（`LarkSdkIsolationTest.kt:372`），作用域感知（object/companion/顶层禁，类级实例字段放行） |
| 其余（BC-135 / BC-279..288 / NF-18 / §6.5 等 parity 语义） | `AssemblerParityTest` | 全部 → `BridgeAssemblerParityTest` |

> 38 MISS 的完整三列映射（重命名承载 / 合并承载 / M6 合法空缺）见 `build/audit-bc-mapping.md §B.3:256-297`（该报告只读，md5 `a7393980…`）。其中 5 项标「M6-T13 未交付」的 UI 卡片逻辑（`BridgeQuickCommandCardLogicTest` / `BridgeSecurityCardLogicTest` / `LarkBridgeViewModelTest` / `ListConfigCommitTest` / `UsageStats*Test`）已在 M6/M7 由 `:feature-larkbridge` 的 jvmTest 承接（含本轮 G-A/G-B 的 `LarkBridgeViewModelTest` 追加）。

### 11.2 两条 设计裁定脚注

- 🔴 **BC-288 第 4 位 `host-custom`（宿主自定义执行器）在新架构无对应物 = 有意的架构变更**（非回归）。理由：用户级自定义由 `config.routes` **数据**承载（不再需要一个硬编码的「宿主执行器」占位 kind）；能力级扩展由「新增一类能力改 4 处 3 文件」机制承载；`RouteKind` sealed + `classDiscriminator` 让漏写即红构建。方案已**否决**拆 `:bridge-api` 纯契约模块这个同方向做法（见方案「已否决替代方案」表）。故 BC-288 的旧 `ids==[…, "host-custom"]` 断言废弃，语义由 `BridgeAssemblerParityTest`「装配顺序来自 routes 数组声明式优先级」承接。
- 🔴 **`CompositeHandler.weight = staticWeight ?: children.first().weight`（`Responder.kt:103`）⇒ 槽内子 handler 顺序变动会改变 `PipelineSnapshot.describe()` 的字面输出**。`describe()` 是 UI 路由预览与 `RoutingTest` 的载体，故**槽内顺序会外溢到用户可见文案**。COMMAND 槽 children 顺序为 `[InteractionHandler(LIGHT), CommandHandler(static HEAVY)]` ⇒ `childWeights` 恒为 `[LIGHT, HEAVY]`、Composite 静态 `weight` 恒为 `LIGHT`（取 first）。调整槽内顺序属**用户可见变更**，须同步 `describe()` 断言与 UI 预览。

### 11.3 新旧语义错位脚注：「装配」与「可命中」拆成两层

旧实现里「装配」与「可命中」是**同一件事**（`BuiltinExecutors.build()` 硬编码装配链即命中顺序）。新架构拆成**两层**：

- **装配层 = `HandlerKinds` 表**（`HandlerKinds.build()` 把 7 个 handler 工厂按 kind 归位）；
- **可命中层 = `PipelineSnapshot`**（`PipelineSnapshotFactory.build` 会**跳过 `enabled=false` 的 route 与未出现的 kind**）。

⇒ **BC-280/281（无技能配置/技能全禁用时仍装配 QuickCommandExecutor）只能断言在 `HandlerKinds` 表层面**（装配存在性），不能断言在 `PipelineSnapshot` 层面（可命中性受 `enabled` 与 route 出现与否影响）。这是有意的两层解耦，非缺陷。

### 11.4 源码注释级登记（① / ③ / #9-A / ⑤ / ⑦ / ⑧ / ⑨ / ⑩ / ⑪）

- **① 静态 `weight` vs 动态 `weightFor(ctx)` 的区分**：`CommandHandler` 的**静态** `weight` 是 **HEAVY**（`handler/BuiltinHandlers.kt:301`），LIGHT 只出现在 `weightFor(ctx)` 的**动态**判定（`:305-306`：命中别名且模板非空 ⇒ HEAVY，否则 LIGHT）。⇒ COMMAND 槽 Composite 的 `childWeights` 恒为 `[LIGHT, HEAVY]`（first = InteractionHandler 静态 LIGHT）。🔴 `pipeline/HandlerKinds.kt:48` 与 `handler/Responder.kt:81` 的注释写的都是**动态档位**（「LIGHT 清单 / HEAVY 委托 agent」），容易被误读成静态 weight——`HandlerKinds.kt:48` 已由 task 21 从 URGENT 改为 LIGHT，无需再动，但阅读时须注意静/动态之别。
- **③ `files/SafeExtract.kt` 的 `extractTar` 512 padding 对齐修复**：旧实现拒绝穿越条目后**不跳对齐**，导致后续合法条目 header 错位。新实现补了 512 字节 padding 对齐——这**超出「只许平移」的字面授权**，但方向正确且有 `files/SafeExtractTest.kt` 覆盖 ⇒ **Leader 已追认为授权差异**（2026-09-08）。🔴 `safeChild`（zip-slip 双重校验）仍是「禁止重写、只许平移」，未动。
- **#9-A `confirm/PermissionServerLauncher.kt` 的 `-Dfile.encoding=UTF-8`（常量 `FILE_ENCODING_FLAG`）= Leader 追认的授权差异**（与 ③ 同属偏离清单 #9 家族，另一半即 ③ 的 `SafeExtract.extractTar`）：生成的临时脚本正文多了一个 java 参数——unix 模板（`:147`）与 Windows 模板（`:157`）各一行，源码 `:397-400`（L2 差异②）已有自陈注释。它**超出「禁止重写、只许平移」的字面授权**（属**输出字节变更**）。追认理由：与硬约束「agent 子进程与 MCP stdio 一律 `Charsets.UTF_8`」同源，修的是 Windows 下 permission-server 回传中文裁决乱码；该 flag 是纯 ASCII，不破坏「windows 脚本内容保持纯 ASCII」的既有约束（BC-099）。🔴 **残余风险 → 归 T11 真机复验**：若参数插入位置在某平台导致 java 解析失败，确认链路会**静默拉不起 permission-server** ⇒ unix 与 Windows 各跑一次「触发确认 → 点允许 → 收到裁决」，并 `cat` 生成的临时脚本核对 java 命令行。（批准人=Leader，2026-09-08）
- **⑤ `.bak` 备份语义**：实现是「**每次覆写前备份 + 新 `.bak` 覆盖旧（单代）**」，与 `config-domain-spec.md §9.1`（`首次写前：复制当前文件为 .bak（仅首次，后续覆写 .bak）`）**一致**；与方案 line 148「首次写前留一份」措辞不一致 ⇒ **以 CDS 为准**（单代 `.bak`，非多代历史）。
- **⑦ `RateGovernor` 按 `x-ogw-ratelimit-reset` 头精确退避的分支真机恒不触发**（S6 实测该头不存在，`gateway-contract.md §16.5`），但有 null 降级到固定指数阶梯（1s→2s→4s→…上限 30s）+ 双入口 + 双分支专测（`RateGovernorTest`）⇒ 属**已文档化的防御性设计，不是死代码，不删**（若飞书未来加回该头仍能用）。
- **⑧ 活体 `audit.jsonl` / `sender_stats.jsonl` 权限是 664 而非 600**：方案 R17 **只要求 `bridge-config.json` 设 600**（含 appSecret 明文），这两个 jsonl 不含密钥；且旧实现产出的两文件同为 664 ⇒ **非回归**，登记即可。
- **⑨ Windows 无 POSIX 600**：`appSecret` 明文迁移在 Windows 上**无文件权限保护**（POSIX 600 不生效），登记为**已知限制**，不做 Windows ACL 工程（超出平移范围）。
- **⑩ Kotlin 接口带默认实现的 suspend 成员 ⇒ 反射合成方法**：给接口加**带默认实现的 suspend 成员**（如 `MessageGateway.resolveSenderName`）后，反射会额外看到合成方法 `xxx$suspendImpl` 与 `access$xxx$jd`。`SyntheticGatewayTest` 已加 `filter { !it.contains('$') }` 过滤（否则成员数误报 15 而非 13）。后续再加同类成员需注意（见 `gateway-contract.md §2.6` 补录）。
- **⑪ `runBlocking` 计数判据**：一律按**实际调用点**计数、命令用 `grep -rnE 'runBlocking\s*\{'`，🔴 **禁用 `grep -c runBlocking`**（KDoc 里出现该字样是允许的，本项目已因此产生过验收判据冲突——如 `observe/SenderStats.kt:103` 的 KDoc 表格描述旧实现 `runBlocking { resolver(openId) }` 会被 `-c` 误计）。当前全仓 **main 侧真实调用点 = 5**：`Bridge.kt:373`（`close()`）/`:385`（`onAppExit()` 内 flushNow）——`AutoCloseable` 与 JVM shutdown hook 的必需桥接；`cli/ServeCommand.kt:83`、`cli/HandleEventCommand.kt:122`、`cli/SendCommand.kt:64`——各为子命令 `run()` 边界唯一一层，无嵌套。`:lark-gateway` main = 0，`:feature-larkbridge` jvmMain = 0。

### 11.5 遗留给 T11 真机闸门的待验项（不在本轮范围，仅登记）

- 🔴 **S2 jlink 模块保留复核**：`composeApp/build.gradle.kts:58` 附近 `modules("java.net.http")` 的 jlink 保留，对新引入的 `oapi-sdk 2.8.5` **是否仍充分**（是否还需 `jdk.crypto.*` / `java.naming` 等）**尚未验证**。本轮**不跑打包**（`packageDistributionForCurrentOS` 太慢，归 T11）。`gateway-contract.md §10.5 / §16.8 #8` 已判定 `httpclient → java.naming + java.security.jgss` 缺口是**死代码路径**（`ApacheHttpClientTransport` 无实例化点，默认走 shaded OkHttp），但 jlink 裁剪产物的真机收发验证仍归 T11。
- 其余 §16.8 仍待 S4/T11 的 7 条（rawContentType / 10min 自动关流 / jlink 网络加密路径 / 100MB 下载堆峰值 / registerApp 扫码流 / BlockedByPeerInstance 互斥 / merge_forward handler 线程耗时）同归 T11。

### 11.6 追加 设计裁定登记（#15：不引入 Compose UI test 依赖）

- 🔴 **裁定：不为 `:feature-larkbridge` 引入 Compose UI test 依赖**（偏离清单 #15）。理由三条：① 需改 `gradle/libs.versions.toml`（全仓构建地基）或在 `feature-larkbridge/build.gradle.kts` 硬编码不确定能否解析的坐标，构建基础设施风险与残余覆盖收益不成比例；② 真正易崩的面（角度数学、除零、>8 扇截断）已被 `stats/DonutChartGeometry.kt` 的 8 条纯函数单测覆盖（`stats/DonutSlicesTest.kt`，实测 8 个 `@Test`），剩下的只是 `Canvas { drawArc(...) }` 薄封装 + `AppMotion.standard()`；③ T11 真机闸门已含「人工进统计页目视确认」，对渲染而言是比 headless 组合测试更强的证据。
- 🔴 **残余覆盖边界（如实登记）**：统计页（tab 4）的环形图与入场动画的**实际视觉渲染从未被任何自动化手段执行过**——`page/larkbridge/LarkBridgePage.kt:76` 的 `var tab by rememberSaveable { mutableStateOf(0) }` 初值为 0（配置页）、统计页是 tab 4，全新启动不进其组合 ⇒ 该面自动化覆盖为零，仅靠 T11 人工目视。此边界随本条一并归 T11 真机闸门。
- （批准人=Leader，2026-09-08）

---

> **文档结束。** 本文件由 M3-T1 产出，共 10 节 + T16 追加的 §11 修正记录/脚注节；BC 条目 550 条（BC-001..BC-550），NF 条目 25 项（NF-01..NF-18 + NF-A1..NF-A7），D 条目 20 条（D1..D20），待裁定点 15 项（P1..P15，其中 P2/P12 已于 T16 回填裁定）。**T16 本轮只改了「落 XXXTest」列、NF-15 承载列/①`threadCount`（原 TBD 回填）、§5.10/§9.2 的 TBD 消解，并追加本节脚注——未改任何断言本体、未删条目、未改「移植/SDK吸收/废弃」标注。未执行任何 git 写操作（HEAD 仍为 `bd03d58`）。**
