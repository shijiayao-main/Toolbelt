# Tasks: artifact-cap-default-and-rejected-audit

## 1. maxCount 默认值

- [x] 1.1 `ArtifactsConfig.maxCount` 默认 3→5（KDoc 注明真机取证依据：AVM 多事件 skill 按 N+1 产出）。验证：`BridgeConfigDefaultsTest` 同步后绿
- [x] 1.2 schemaVersion 不 bump（typed 层默认值兜底惯例）；存量显式配置不受影响。验证：`BridgeConfigRoundTripTest` 原样绿

## 2. 被拒清单落审计

- [x] 2.1 `deliverArtifacts` 的 `DeliveryResult` +`rejected: List<String>`（含路径与原因）。验证：`ArtifactsTest` 数量上限用例补断言后绿
- [x] 2.2 `HandlerOutcome` +`rejectedArtifacts` 字段（success 工厂补参）。验证：`./gradlew :lark-bridge:compileKotlin`
- [x] 2.3 `SinkRecord.Audit`/`AuditEntryJson` +`rejectedArtifacts: List<String>? = null`（旧格式行 decode null；空清单落 null 不产空键）。验证：`AuditLogTest` schema 13 字段断言同步后绿
- [x] 2.4 `withArtifactsDelivered` 写 `delivery.rejected`、`BridgeAssembler` onOutcome `takeIf { isNotEmpty() }` 落盘。验证：`ArtifactUploadPipelineTest` 沙箱外用例补断言后绿

## 3. 验收

- [x] 3.1 四模块全量 `--rerun-tasks --no-build-cache` 全绿
- [x] 3.2 真机（2026-09-12 维护者确认真机跑过）：配置页把 `agent.artifacts.maxCount` 按需调大；复跑 AVM 多事件分析确认 5 份全回传、审计行 `rejectedArtifacts` 为 null
- [x] 3.3 建议（零代码，属可选建议非交付物）：设置页开启「日志存储」（当前关，桥接 WARN 进程退出即丢）
