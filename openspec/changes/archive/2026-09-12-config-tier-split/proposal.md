# Proposal: config-tier-split

## Why

飞书桥接配置页 65 个字段按 7 组平铺（`ConfigEditor` 逐组渲染 `SectionCard`），全新用户面对的是一张专业参数总表——用户反馈「配置依旧过于专业，能否拆分基础配置和进阶配置，循序渐进」。

65 个字段里全新用户从零到收发第一条消息**必看**的只有 5 个：凭据三件套（appId/appSecret/region）、工作区根目录（留空 = 沙箱拒绝所有路径）、发送者白名单（默认空名单 fail-closed = 拒绝所有人，含用户自己）。其余 60 个（运行时并发、配额、观测、产物回传细调……）在默认值下都「能跑」，属于首条消息之后的进阶话题。

## What Changes

- `BridgeFields.kt` 新增 `enum class FieldTier { BASIC, ADVANCED }`（title「基础配置」「进阶配置」）与 `BridgeField.tier: FieldTier = FieldTier.ADVANCED` 构造参数。**默认 ADVANCED**：后续新增字段不标注即进阶，基础区只增靠显式裁定。
- 5 个字段标 BASIC：`connection.appId`、`connection.appSecret`、`connection.region`、`access.allowedSenders`、`workspace.root`。
- `ConfigFieldRenderer.kt` 的 `ConfigEditor` 改两段渲染：
  - ①「基础配置」`SectionCard(collapsible=false)`：5 个 BASIC 字段；
  - ②「进阶配置」`SectionCard(collapsible=true, initialExpanded=false)`：内部按 7 组以 `CardHeader` 分小节渲染各组 ADVANCED 字段。
- `ConfigGroup.initialExpanded` 语义重定义为「进阶区内该组小节是否默认展开」（RUNTIME/FILE_MESSAGE 仍 false）——测试断言字面零改动，仅 KDoc 口径同步。
- tier 是纯 UI 元数据：不进 JSON、不进 BridgeConfig、零迁移。

## Impact

- **代码**：`lark-bridge`（BridgeFields +1 枚举 + tier 参数与 5 处标注）、`feature-larkbridge`（ConfigFieldRenderer 两段渲染）、`BridgeFieldsTest`（新增 tier 三断言：BASIC 恰 5、清单逐字、title 非空）。
- **不受影响（已核实消费方）**：`SecurityHintsCard`（纯函数）、`ConfigCheckPanel`（遍历 ALL 全量输出，诊断用途不分层）、字段级 issue 标错、`FieldRow` when 穷尽、`LarkBridgePage` 调用签名——零改动。
- **校验语义不升格**：BASIC 字段 issue 仍是阻断型/提醒型原有分类，tier 与「阻断 vs 提醒」正交。
- **零白名单合规**：新 UI 只用 SectionCard/CardHeader + 现有 Dimens 令牌，无新增硬编码尺寸。
