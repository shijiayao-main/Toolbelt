# Design: config-tier-split

## Context

字段表 `BridgeFields.ALL`（65 字段、7 组）是配置域的单一真源；UI 侧 `ConfigEditor` 逐组渲染 `SectionCard`（组折叠态取 `ConfigGroup.initialExpanded`）。此前分层粒度只有「组」——而组是**领域分类**（连接/访问/工作区/运行时/agent/文件/观测），不是**用户旅程分层**。全新用户的旅程是「填凭据 → 圈人 → 定工作区 → 收发」，这条线横跨 3 个组，被组的平铺打散了。

用户拍板「字段级分层」而非「组级分层」：56/61（当时）字段在默认值下能跑，真正必看的只有 5 个——分层轴应该贴用户旅程，而不是贴领域分类。

## Goals / Non-Goals

**Goals**
- 首屏只露 5 个必看字段 + 一个折叠的进阶区，全新用户 10 分钟内发出第一条消息。
- 进阶区展开后信息不丢：7 个领域分组仍在（作为进阶区内的小节头），存量用户可按组找字段。
- 后续新增字段默认进阶，不污染基础区（基础区只增靠显式裁定）。

**Non-Goals**
- 分层不进配置域：tier 不进 JSON、不进 BridgeConfig、零迁移——纯 UI 元数据。
- 校验/诊断不分层：`ConfigCheckPanel` 仍全量输出（诊断面板分层反而藏问题）。
- 不做「向导式」配置流（Step-by-step wizard）：折叠两段是最小扰动方案，向导是另一种产品形态。

## Decisions

### D1. tier 默认 ADVANCED

`BridgeField.tier: FieldTier = FieldTier.ADVANCED`——与「新增字段默认 RESTART 还是 HOT」类似的默认值取向：**默认进阶，露出靠显式标注**。产物回传 4 字段、entryMaxChars 等新字段因此零额外工作。若默认 BASIC，任何一个新字段都会自动挤进首屏，基础区从 5 个悄悄变 20 个——「清单逐字钉死」断言防不住「忘了标」，防不住「标错方向」。

### D2. BASIC 5 字段的裁定标准

标准 = **全新用户从零收发第一条消息的必看项**（缺一个都发不出消息）：

| 字段 | 为什么必看 |
|---|---|
| `connection.appId` | 凭据必填，无它不连 |
| `connection.appSecret` | 与 appId 成对 |
| `connection.region` | 海外租户必改；Choice 编辑器认知负担低 |
| `workspace.root` | 留空 = 沙箱拒绝所有路径，agent 必拒 |
| `access.allowedSenders` | 默认 allowlist 空名单 fail-closed = 拒绝所有人（含自己），不发白名单没人能触发 |

落选示例：`autoStart`（手动启动即可）、`agent.backend`（默认 claude 即用）、`access.mode`（默认白名单模式已是安全默认）。清单逐字钉死在 `BridgeFieldsTest`，进出须显式裁定并同步断言。

### D3. 渲染结构：两段 SectionCard + 组小节头

方案对比时否决了「组内上下分」（Y 方案：组标题下 BASIC 字段直出、ADVANCED 收进组内折叠尾）——56/61 是 ADVANCED，组内「小头大折叠尾」头重脚轻且要嵌套折叠。拍板两段：

- 基础区 `SectionCard(collapsible=false)`：5 个 BASIC 字段按声明序直出；
- 进阶区 `SectionCard(collapsible=true, initialExpanded=false)`：内部 7 个 `CardHeader` 小节头（对齐 `:ui` 的卡内小节头先例）分节渲染各组 ADVANCED 字段。

`ConfigGroup.initialExpanded` 语义重定义为「进阶区内该组小节默认展开与否」——RUNTIME/FILE_MESSAGE 仍 false，`BridgeFieldsTest` 断言字面零改动，只改 KDoc 口径（防后来者按旧语义误改）。

### D4. 零白名单合规

新 UI 只用 `SectionCard`/`CardHeader` + 现有 Dimens 令牌；折叠态取自枚举属性，Composable 无硬编码尺寸——飞书桥接页保持零白名单条目。

## Risk / Trade-offs

- **进阶字段可发现性下降**：折叠区首屏不可见——取舍即分层本意；`ConfigCheckPanel`（诊断）与 `check` CLI 全量输出兜底「字段在哪」的查找需求。
- **tier 与组的双轴可能漂移**：新增字段标错 tier 不会编译失败——由 `BridgeFieldsTest` 的 BASIC 清单逐字断言守护（改清单必须动测试）。

## Migration Plan

无迁移：tier 不进持久化文档。UI 一次改到位（`ConfigEditor` 单文件），守护断言同批落地。
