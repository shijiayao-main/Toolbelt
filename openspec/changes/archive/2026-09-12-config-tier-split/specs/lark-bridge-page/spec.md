# Delta: lark-bridge-page

## ADDED Requirements

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
