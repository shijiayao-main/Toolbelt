# lark-bridge-page Specification

> ⚠️ **本规格已作废（superseded）**。桥接页已在 `rewrite-lark-bridge` 中推倒重写，旧 `:feature-larkbridge` 已于 M2 拆除；本 v1 正文（尤其「配置由 App 层管理器持有、跨页面存活并经 EasyDataManager 持久化」）与新架构（外部单一 JSON 文档 `~/.toolbelt/lark/bridge-config.json`、明确不入 DataStore）**直接矛盾**，仅作历史留档，不再维护。实现以 `openspec/changes/rewrite-lark-bridge/`（`behavior-contract.md` / `config-domain-spec.md` / `gateway-contract.md`）+ 源码为准。

## Purpose

定义 composeApp 中飞书桥接功能页的行为契约：页面顶部「概览 / 设置」两段切换，概览段提供服务启停、运行状态卡、按类型/后端分类的使用统计、发送者统计与实时日志控制台；设置段按「安全边界优先」重组的十卡布局收纳配置（连接/工作区域/白名单/权限与安全/Agent/Skill/触发与回复/访问控制/固定回复/操作区），并以五个折叠分组（连接/工作区/安全与执行/路由与回复/操作）组织以缩短单屏滚动长度；配置由 App 层管理器持有、跨页面存活并经 EasyDataManager 持久化。

## Requirements

### Requirement: 桥接功能页

系统 SHALL 提供 `LarkBridge` 导航页面（`page/larkbridge/`），顶部「概览 / 设置」两段切换（`LarkBridgeSection` enum + `rememberSaveable` 记忆分段）；运行开关与状态文字留 `PageScaffold` actions 两段共用。概览段（`BridgeOverviewSection`）SHALL 按状态卡 → 使用统计卡 → 发送者统计卡 → 日志台排列。设置段（`BridgeSettingsSection`）SHALL 按「安全边界优先」重组的十卡顺序排列：连接配置 → 工作区域（单目录必填）→ 工作区白名单（多目录）→ 权限与安全 → agent 执行器 → Skill 配置 → 触发与回复 → 访问控制 → 固定回复 → 操作区（环境预检/测试消息）；十卡 SHALL 收纳于五个折叠分组（连接 / 工作区 / 安全与执行 / 路由与回复 / 操作），分组仅作视觉收纳层、SHALL NOT 改变十卡顺序，各分组展开态经 `rememberSaveable` 记忆（「路由与回复」默认折叠，其余默认展开）。配置区 SHALL NOT 提供传输层（cli/openapi）与区域（feishu/lark）选择项。

#### Scenario: 页面启停服务

- **WHEN** 用户在页面打开桥接开关
- **THEN** 桥接状态变为 Starting → Running，页面状态卡实时更新
- **WHEN** 用户关闭开关
- **THEN** 服务停止，相关子进程被清理

#### Scenario: 服务跨页面存活

- **WHEN** 桥接运行中用户导航到其他页面再返回
- **THEN** 服务仍在运行（服务实例由 App 层管理器持有，不随页面 ViewModel 销毁）

#### Scenario: 设置段十卡重组布局

- **WHEN** 用户切换到设置段
- **THEN** 卡片按「连接→工作区域→工作区白名单→权限与安全→Agent→Skill→触发与回复→访问控制→固定回复→操作区」顺序排列，收纳于五个折叠分组头（连接/工作区/安全与执行/路由与回复/操作）之下，分组可点击展开/收起且展开态跨分段切换记忆

#### Scenario: 配置区不暴露传输层与区域选择

- **WHEN** 用户查看配置区
- **THEN** 界面上不存在传输层（cli/openapi）与区域（feishu/lark）选择控件，App 宿主始终以 openapi + feishu 装配

### Requirement: 实时日志控制台

系统 SHALL 在页面提供实时日志列表：收集 `observeLogs()` 事件累积展示（上限约 1000 行，超出丢弃头部），按 source 着色区分，自动滚动到底部。

#### Scenario: 实时观察运行日志

- **WHEN** 桥接服务运行且页面日志开关开启
- **THEN** 新日志事件实时追加到控制台并自动滚动到底部

#### Scenario: 历史回看

- **WHEN** 用户进入页面时服务已运行一段时间
- **THEN** 控制台先展示最近的历史日志（来自 SharedFlow replay）

### Requirement: 配置持久化

系统 SHALL 将页面配置（App ID、App Secret、测试消息目标 chat_id、dryRun、工作区域路径、工作区多目录白名单、发送者白名单、发送者黑名单、访问控制模式、拒绝回复模板、未匹配兑底回复、权限档位、禁删开关、安全提示注入开关、运行时确认开关/超时、技能列表）通过 EasyDataManager 持久化，遵循"持久化不直接用 DataStore"约定。新增配置 key SHALL 包括：`lark_bridge_workspace_whitelist`（多目录白名单，JSON 数组，与单目录取并集）、`lark_bridge_skills`（技能列表，`SkillConfig` JSON 整体存取）、`lark_bridge_agent_prohibit_delete`（禁删开关，默认 true）、`lark_bridge_agent_safety_prompt_enabled`（安全提示注入，默认 true）；权限档位 key `lark_bridge_agent_permission_preset` 默认为 `safe`。工作区域 SHALL 为必填项（无默认值，空串表示未配置）。访问控制模式 SHALL 支持白名单（默认）与黑名单两种；拒绝回复与未匹配兑底回复为可选模板（空 = 静默）。页面 SHALL 提供发送者统计卡：从统计文件聚合展示各发送者的对话量、被拒次数与用户名（用户名解析失败时以 open_id 兑底），支持手动刷新。

文本类配置项（App ID、App Secret、chat_id、白名单、触发前缀、重置指令、停止指令、工具限制、agent 启动目录、System Prompt、自定义模板）SHALL 采用失焦提交：输入过程仅更新内存态（不写持久化、不重建桥接实例），输入框失去焦点时值有变化才提交（持久化 + 重建实例）。开关类与单选类配置项 SHALL 保持点击即提交。桥接实例重建 SHALL 自动恢复运行态：重建前实例处于运行中时，重建后自动启动新实例。

工作区域卡 SHALL 保持显式保存流（保存按钮触发持久化与目录创建），不适用失焦提交。

**工作区域路径校验语义（本变更新增）**：工作区域路径的绝对性校验 SHALL 采用平台语义（`java.io.File.isAbsolute`）——Unix 下以 `/` 开头为绝对路径；Windows 下盘符路径（`C:\…`、`C:/…`）与 UNC 路径（`\\…`）均为绝对路径。`~` 前缀展开 SHALL 兼容两种分隔符（`~/` 与 `~\`），展开行为由单一共享实现提供，各调用点 MUST NOT 自行维护展开逻辑。

#### Scenario: 配置重启后保留

- **WHEN** 用户填写 App ID / App Secret / chat_id / 工作区域 / 白名单并重启应用
- **THEN** 配置项恢复为上次保存的值

#### Scenario: 工作区域未配置时功能受限

- **WHEN** 工作区域未配置且用户通过飞书触发 agent 执行
- **THEN** agent 执行器拒绝执行并回复「请先配置工作区域」类提示，启动预检给出警告

#### Scenario: 工作区域路径输入校验

- **WHEN** 用户输入的工作区域路径不是绝对路径或无法创建
- **THEN** 配置卡给出错误提示，不保存无效值

#### Scenario: Windows 盘符路径可保存（本变更新增）

- **WHEN** 应用运行于 Windows，用户输入工作区域路径 `C:\dev\ws` 或 `C:/dev/ws`
- **THEN** 校验通过，保存按钮出现，路径可保存并生效

#### Scenario: 波浪号展开兼容两种分隔符（本变更新增）

- **WHEN** 用户输入 `~/work`、`~\work` 或裸 `~`
- **THEN** 三种输入均展开为用户主目录（及其子目录），校验按展开后结果进行

#### Scenario: 白名单未命中发送者被拒绝并引导配置

- **WHEN** 白名单为空或消息发送者 open_id 不在白名单内，且消息命中 agent 触发前缀
- **THEN** agent 不执行，日志台记录该发送者 open_id 供用户复制配置

#### Scenario: 输入过程零副作用

- **WHEN** 桥接运行中，用户在白名单输入框连续输入多个字符（未失焦）
- **THEN** 桥接实例不重建、服务持续运行，持久化未写入

#### Scenario: 失焦提交且值有变化才重建

- **WHEN** 用户在配置输入框修改后点击输入框外（失焦）
- **THEN** 值有变化时持久化并重建桥接实例；值未变化时不触发持久化与重建

#### Scenario: 运行态自动恢复

- **WHEN** 桥接运行中，用户失焦提交任一文本类配置
- **THEN** 桥接实例重建后自动恢复运行状态（无需手动重拨开关），日志记录重建与恢复

#### Scenario: 未运行时提交不启动

- **WHEN** 桥接未运行，用户失焦提交配置
- **THEN** 配置持久化、实例重建，服务保持未启动（不意外拉起）

### Requirement: 工作区白名单卡（多目录）

系统 SHALL 提供工作区白名单卡（`BridgeWorkspaceWhitelistCard`）：在单目录工作区域之外追加多个允许根，与单目录取并集构成 agent 沙箱允许区域（lib `WorkspacePolicy.fromConfig`）。交互范式 SHALL 对齐单目录工作区域卡：本地编辑态 + 浏览回填 + 显式「添加」提交（手输路径不立即回写）；列表行 hover 渐显删除；单目录作为白名单首项只读展示（其增删仍在工作区域卡）。

#### Scenario: 添加白名单目录

- **WHEN** 用户在白名单卡输入或浏览选定一个目录并点「添加」
- **THEN** 该目录追加到 `lark_bridge_workspace_whitelist` 并持久化，与单目录取并集生效

#### Scenario: 手输不立即回写

- **WHEN** 用户在白名单输入框连续输入（未点「添加」）
- **THEN** 仅更新本地编辑态，不写持久化、不重建桥接实例

### Requirement: 权限与安全卡（含诚实边界文案）

系统 SHALL 提供权限与安全卡（`BridgeSecurityCard`），集中收纳安全边界配置：五档权限档位（只读/安全（默认）/工作区/完全开放/自定义）+ 禁删开关 + 安全提示注入开关 + 运行时二次确认开关/超时。卡尾 SHALL 明示诚实边界文案：运行时确认的后端限制（仅 claude）与沙箱的纵深防御定位（非内核级强隔离）。运行时确认开关 SHALL 仅在 claude 后端可用（其他后端置灰）；custom 后端隐藏档位行；custom 档 SHALL 显示工具限制输入（需填非空）；`all` 档 SHALL 额外红色逃生舱告警；禁删开启但档位已失效（workspace/all 允许 shell 可间接删除）时 SHALL 提示。

权限与安全卡 SHALL 收集门面可观测状态 `LarkBridge.confirmRuntimeStatus`（经 `LarkBridgeManager` → `LarkBridgeViewModel` 透传）并按三态展示：`Disabled` 隐藏（开关已表达语义）；`Ready` 低调展示就绪与端口（info 语义）；`Unavailable` SHALL 以错误语义色 + 警告图标显式告警并给出人读原因（明示高危操作将 fail-safe deny），避免用户困惑于「为何全部被拒」。

#### Scenario: 诚实边界文案常驻卡尾

- **WHEN** 用户查看权限与安全卡
- **THEN** 卡尾显示两条诚实边界文案：「运行时确认仅 claude 后端支持（其他后端高危操作 fail-safe 拒绝）」与「本沙箱为纵深防御（降低风险 + 提高可见性），非内核级强隔离；建议白名单指向专用工作目录而非家目录/项目根」

#### Scenario: 确认链路不可用时显式告警

- **WHEN** `confirmRuntimeStatus` 为 `Unavailable(reason)`（总线/启动脚本/自检任一失败）
- **THEN** 卡内以错误语义色 + 警告图标展示告警文本，含人读原因与「高危操作将被拒绝执行（fail-safe deny）」提示

#### Scenario: 确认就绪时低调展示端口

- **WHEN** `confirmRuntimeStatus` 为 `Ready(port, scriptPath)`
- **THEN** 卡内低调展示「运行时确认就绪（端口 <port>）」（info 语义，不打扰）；`Disabled` 时不展示状态行

#### Scenario: 非 claude 后端确认开关置灰

- **WHEN** agent 后端选择为非 claude（qoder/copilot/custom）
- **THEN** 运行时确认开关置灰不可用（无 permission-prompt-tool 通道）

#### Scenario: 默认档位为安全禁删档

- **WHEN** 用户首次打开权限与安全卡（未配置过档位）
- **THEN** 档位默认选中「安全」（禁删档），禁删开关与安全提示注入开关默认开启

#### Scenario: all 档逃生舱告警与禁删失效提示

- **WHEN** 用户选中「完全开放」档，或开启禁删但档位为 workspace/all
- **THEN** all 档显示红色逃生舱告警；禁删开启但档位已失效（允许 shell 可间接删除）时显示失效提示

### Requirement: 默认权限档迁移横幅

系统 SHALL 对存量从未写过 `lark_bridge_agent_permission_preset` key 的用户做一次性迁移（`LarkBridgeManager.migratePermissionPresetOnce`）：升级后显式写回 `safe` + 记迁移日志 + 置页面顶部一次性横幅待展示标记（写回后 key 存在，下次启动不再进迁移分支，日志与横幅各仅一次）。飞书桥接页顶部（两段切换之前、概览/设置两段共用可见）SHALL 展示迁移横幅（`BridgePresetMigrationBanner`）：仅存量首次（`presetMigrationNotice=true`）弹出，说明默认档已调为「安全」（禁 shell/禁删）、如需构建执行切「工作区」档；点关闭标记已读（持久化 false）后续不再弹。

#### Scenario: 存量首次展示迁移横幅

- **WHEN** 存量用户（未写过 permissionPreset key）升级后首次进入飞书桥接页
- **THEN** 默认档被写回 `safe`，页面顶部展示一次性横幅：「默认权限档已调整为『安全』（禁 shell/禁删）。如需构建与命令执行，请在『权限与安全』切到『工作区』档。」（概览/设置两段均可见）

#### Scenario: 关闭横幅后不再弹

- **WHEN** 用户点击横幅关闭按钮
- **THEN** `presetMigrationNotice` 持久化为 false，后续启动不再展示横幅（迁移日志亦仅记一次）

#### Scenario: 新用户不进迁移分支

- **WHEN** 用户已写过 permissionPreset key（新用户或已迁移）
- **THEN** 不触发一次性迁移，不展示迁移横幅

### Requirement: Skill 管理卡

系统 SHALL 提供技能配置卡（`BridgeSkillCard`）：技能增删改（CRUD 范式对齐固定回复卡 `StaticReplyCard`），每技能包含 ID/展示名/别名/描述/提示词模板（`{args}` 占位符）/启用/需确认；技能列表经 `lark_bridge_skills` JSON 整体存取。别名重叠时 SHALL 提示（不阻断，lib 侧按最长匹配裁决）。

卡内用户可编辑技能列表之后 SHALL 提供独立「内置技能（开箱即用 · 只读）」分区（`BuiltInSkillsSection`），逐条展示 lib `BuiltInSkills.ALL` 的**只读条目**（name + 「内置」徽标 + 别名 + description + 触发用法提示），MUST NOT 提供 Switch/编辑框/删除按钮，且 SHALL 在视觉上与用户可编辑条目区分（弱化底色 `surfaceVariant`（`AppAlpha.bgSubtle`）+ 弱化文字 `AppAlpha.contentFaint/contentSoft` + primary 色「内置」徽标）。内置技能 SHALL 经纯函数 `builtInSkillDisplays()` 直接映射 lib 配置，MUST NOT 经 VM 用户 CRUD 路径、MUST NOT 写入 `lark_bridge_skills` 持久化。分区底部 SHALL 提示「内置技能可用相同 id 覆盖，或设 enabled=false 关闭」（与 lib `SkillRegistry.mergeSkills` 合并语义一致）。用户技能提示词模板字段的 supportingText 与每条内置条目 SHALL 展示与运行时三通道一致的自然语言触发用法示例（`buildSkillTriggerHint`，如「用户可用 /log <参数> 或 ai 日志分析: <参数> 触发」）。

#### Scenario: 新增技能

- **WHEN** 用户在 Skill 管理卡填写 ID/展示名/别名/提示词模板并保存
- **THEN** 技能追加到 `lark_bridge_skills` 并持久化，启用后参与别名最长前缀匹配

#### Scenario: 提示词模板 {args} 占位符

- **WHEN** 用户编辑技能的提示词模板
- **THEN** 卡提示模板用 `{args}` 占位符承接别名后参数（与 lib SkillExecutor 一致）

#### Scenario: 内置技能以只读条目展示并标注「内置」

- **WHEN** 用户打开设置段 Skill 管理卡
- **THEN** 用户可编辑技能列表之后展示「内置技能（开箱即用 · 只读）」分区，逐条展示 lib `BuiltInSkills.ALL`（如 log-analysis：名称「日志分析」+「内置」徽标 + 别名 `/log` + 描述 + 触发用法提示），无 Switch/编辑框/删除按钮，且弱化底色/弱化文字/primary 徽标使其与用户可编辑条目视觉区分

#### Scenario: 内置技能不进入用户配置持久化

- **WHEN** 用户新增/编辑/删除自有技能并触发 `lark_bridge_skills` 持久化
- **THEN** 持久化列表仅含用户技能，内置技能 id（`BuiltInSkills.IDS`，如 `log-analysis`）MUST NOT 被写入；内置只读展示恒源自 lib `BuiltInSkills.ALL`，不依赖用户存档（空存档时仍展示）

#### Scenario: 内置技能覆盖/关闭提示

- **WHEN** 用户查看内置技能分区
- **THEN** 分区底部提示「内置技能可用相同 id 覆盖，或设 enabled=false 关闭」（与 lib `SkillRegistry.mergeSkills` 合并语义一致）

#### Scenario: 触发用法示例覆盖别名与自然语言双通道

- **WHEN** 用户查看某条内置技能，或编辑用户技能的提示词模板字段
- **THEN** 内置条目与用户技能模板字段 supportingText 均展示 `buildSkillTriggerHint` 生成的用法提示（含别名命令 `/log <参数>` 与自然语言 `ai 日志分析: <参数>` 两种形态）；技能无别名时回退 `/<id>`、name 为空时回退 id

### Requirement: 概览按类型分类统计

系统 SHALL 在概览段使用统计卡（`BridgeUsageStatsCard`）按类型（kind）分类聚合展示：口径单一——全部指标来自 audit.jsonl 聚合（`UsageStats`/`aggregateAudit` 纯函数），`AuditEntry.kind` 驱动 `byKind` 聚合（kindLabel：COMMAND→指令/SKILL→技能/AGENT→Agent/null→未知）。审计仅覆盖 agent 执行链（Ping/固定回复不落审计），故实际聚合 Agent/技能两类，COMMAND 不落审计。进入概览读一次 + 手动刷新，无轮询。

#### Scenario: 按类型聚合展示

- **WHEN** 用户进入概览段且 audit.jsonl 含带 kind 字段的记录
- **THEN** 使用统计卡「按类型」区按 Agent/技能分类展示计数（旧记录 kind=null 归「未知」）

#### Scenario: COMMAND 不落审计

- **WHEN** 用户执行 ping / 固定回复等 COMMAND 类指令
- **THEN** 该类不写入 audit.jsonl，概览类型统计不出现 COMMAND 计数

### Requirement: 退出清理

系统 SHALL 在应用退出时停止桥接服务并销毁 lark-cli 子进程。

#### Scenario: 退出应用无残留进程

- **WHEN** 桥接运行中用户关闭应用
- **THEN** LarkBridge.stop() 被调用，lark-cli 子进程被销毁

### Requirement: 配置页基础/进阶分层渲染

飞书桥接配置页 SHALL 按字段级 tier 分层渲染：每个 `BridgeField` SHALL 携带 `tier`（BASIC / ADVANCED，默认 ADVANCED——新增字段不标注即进阶，基础区只增靠显式裁定并同步守护断言）。配置编辑器 SHALL 渲染为两段：

- **基础配置区**：不可折叠的 `SectionCard`，只渲染 tier=BASIC 的字段（5 个：`connection.appId`、`connection.appSecret`、`connection.region`、`access.allowedSenders`、`workspace.root`——全新用户从零收发第一条消息的必看项）；
- **进阶配置区**：默认收起的可折叠 `SectionCard`，内部按 `ConfigGroup`（7 组）以 `CardHeader` 小节头分节渲染各组 tier=ADVANCED 的字段；小节默认展开与否沿用 `ConfigGroup.initialExpanded`（RUNTIME/FILE_MESSAGE 默认收起）。

tier SHALL 为纯 UI 元数据：不进 JSON 持久化文档、不进 BridgeConfig、零迁移。配置诊断面板（`ConfigCheckPanel`）与 `check` CLI SHALL 保持全量字段输出（诊断用途不分层）。字段级 issue 标错与校验语义 SHALL 不因 tier 变化（BASIC 字段 issue 仍按原阻断/提醒分类）。

#### Scenario: 首屏只露必看字段

- **WHEN** 全新用户打开飞书桥接配置页
- **THEN** 首屏可见 5 个 BASIC 字段（凭据三件套、发送者白名单、工作区根目录）与一个默认收起的进阶区，其余 60 个字段不可见

#### Scenario: 进阶区展开后按组可寻址

- **WHEN** 用户展开进阶配置区
- **THEN** 7 个领域分组小节头齐全（连接/访问/工作区/运行时/agent/文件消息/观测），RUNTIME 与 FILE_MESSAGE 小节默认收起，各组内 ADVANCED 字段按声明序渲染

#### Scenario: 新增字段默认进阶

- **WHEN** 后续迭代新增 `BridgeField` 未标注 tier
- **THEN** 该字段落在进阶区所属组的小节内，基础区清单不变（BASIC 清单逐字断言守护）

#### Scenario: 诊断面板不受分层影响

- **WHEN** 配置存在校验 issue 且用户打开配置诊断面板
- **THEN** 面板全量列出 issue 字段（含进阶字段），不因折叠态漏报
