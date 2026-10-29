# Proposal: artifact-cap-default-and-rejected-audit

## Why

真机取证（2026-09-12）：AVM 耗时分析回合 agent 实际产出 **5 份报告**（skill 规范 N+1 行：4 单事件 + 1 汇总），但 `agent.artifacts.maxCount=3` 把按序第 4、5 份截断——**汇总报告（skill 规范放最后）永远最先被砍**，最不该丢的那份必丢。飞书侧链路本身全通（同回合 3 份成功上传实证 im:resource scope、fileType="stream"、sendFileByKey 全部生效）。

次生问题：被拒条目只还原到飞书回复正文里，**不落审计**——失败取证只能翻聊天记录；且桌面端文件日志默认关（`log_file_enabled=false`），桥接 WARN 全在内存日志台进程退出即丢。

用户拍板：`maxCount` 本就可在配置页调（会自己去改），代码侧把**默认值调高到 5**；被拒清单补落审计。

## What Changes

- `ArtifactsConfig.maxCount` 默认 **3→5**（编辑器范围 1..10 不变；schemaVersion 不 bump——带默认值改默认走 typed 层兜底惯例，存量文档已显式写 3 的用户值不受影响）。
- `deliverArtifacts` 的 `DeliveryResult` +`rejected: List<String>`（含路径与拒绝原因）。
- `HandlerOutcome` +`rejectedArtifacts: List<String> = emptyList()`（success 工厂同步补参）。
- 审计 schema 扩容：`SinkRecord.Audit`/`AuditEntryJson` +`rejectedArtifacts: List<String>? = null`（沿 attachments 的「后加可空字段」惯例，旧格式行 decode 为 null 不破坏；空清单落 null 不产空键）；`BridgeAssembler` onOutcome 接线。

## Impact

- **新装用户**默认能全量回传 5 份产物（AVM 4 事件场景 N+1=5 正好）；存量显式配置不受影响（用户自行在配置页调整）。
- 审计行 12→13 字段（`AuditLogTest` schema 断言同步）；失败取证闭环：被拒原因落盘可查，不再依赖翻飞书聊天记录。
- 真机项 5.4①②③ 的正向部分（scope/fileType/链路）经本轮取证实证生效，可勾。
