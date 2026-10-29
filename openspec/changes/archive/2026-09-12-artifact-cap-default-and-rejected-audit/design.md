# Design: artifact-cap-default-and-rejected-audit

## Context

取证链（2026-09-12）：audit.jsonl 唯一一条带 attachments 的行 = 11:09:10 AVM 回合、3 份成功；磁盘同目录实际 5 份（mtime 全部 ≤ 11:08 早于审计落点）；skill 侧 `AVM_TIME_STEP.md:159/172` 规定 N+1 行标记 ⇒ agent 应写 5 条；`Artifacts.kt:91` 的 `remainingSlots <= 0 -> reject("超出数量上限 3")` 与「未回传的恰是按序第 4、5 份」严丝合缝。`maxCount` 是四配置项里唯一被真机撞上的默认值。

取证最大盲区：`rejected` 清单只拼进回复正文（用户可见但转瞬被聊天记录淹没），不进审计——`HandlerOutcome.attachments` 只装成功清单，审计 schema 无被拒字段。

## Decisions

### D1. 默认 3→5 而非拉满 10

5 恰好覆盖 AVM 多事件典型场景（4 事件 = 5 份）。拉满 10 会让「agent 失控写几十条标记行」的刷文件雨风险面变大——数量上限的存在意义（防失控）与「正常场景不截断」要平衡。用户已可在配置页 1..10 自调。

### D2. 被拒清单落审计而非只落日志

- 文件日志默认关（`log_file_enabled=false`，app.log 0 字节）——落日志取不了证。
- 审计 JSONL 是「出事后唯一可复盘的凭据」的既定定位；被拒条目是失败行为本身，理应进。
- 字段形态沿 `attachments` 的「后加可空」惯例：`rejectedArtifacts: List<String>? = null`，旧格式行 decode null，空清单落 null 不产空键（`takeIf { isNotEmpty() }`）。
- 值内容直接复用 `deliverArtifacts` 已拼好的还原行（`[产物] <路径>  <!-- 原因 -->`）——与用户在飞书回复里看到的形态一致，一份数据两处消费。

### D3. 不改「按出现序截断」语义

汇总报告永远排最后、最先被砍的产品语义问题（超限时优先保汇总）不在本 change 范围——默认值调到 5 后正常场景不再触发截断；真触发时（>5 份）用户可在飞书回复末尾看到完整被拒清单（含原因），自己去调上限。这是最小扰动取舍。

## Risk / Trade-offs

- 审计 schema 12→13 字段：沿既有「后加可空字段」惯例零破坏；`AuditLogTest` 断言同步。
- 默认值变更只影响「从零开始/未写键」的文档；用户真机文档已显式写 3，需自行调整（验收清单已注明）。

## Migration Plan

无 JSON 迁移。落地序：`DeliveryResult.rejected` → 审计 schema 字段 → `withArtifactsDelivered` 接线 → `BridgeAssembler` 落盘 → 测试同步。
