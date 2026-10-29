# Proposal: shrink-progress-card-window

## Why

飞书过程卡片此前是 **append-only 累积**：渲染层窗口有界（6 条/80 字/2000 字），但泵层 `pumpSnapshotsToCard` 只推 delta、窗口丢头时整帧重推造成内容重复；底层 `patchElement` 本就是全量替换语义，却没被利用。长跑 agent 任务的过程卡片越滚越长，用户反馈「还是太长」。

同时存在两处配置失真：
- `agent.progress.patchIntervalMillis` 在编辑器里可改，但管线零消费——真实节流是网关侧固定 250ms 时间窗。对用户撒谎的 UI 不如删掉。
- `ProgressAccumulator` 构造参数 `entryMaxChars`（默认 80）真实生效，但装配点未透传，用户无法调节。

## What Changes

- `CardStreamSink` 接口 `tryAppend(text)` **替换为** `tryReplace(text): Boolean`（全量替换语义，不并存留死接口；实现者全仓仅 `LarkCardStreamSink` 与 `SyntheticCardStream` 两处，编译期全暴露）。
- `LarkCardStreamSink` 主循环 flush 条件由 `pendingChars > 0 && timeHit` 改为 `snap.dirty && timeHit`——修正「新快照更短时 pendingChars < 0，内容变化却永不 patch」缺陷；`charsHit`（4000 字符）与 `lastFiredChars` 退役（滚动窗口 cardMaxChars=2000 恒不可达）。
- 泵层 `pumpSnapshotsToCard` 删 `lastLength`/delta 计算/`onRotated` 整帧重推分支，改 `sink.tryReplace(snapshot)` 直通，返回推送帧计数。
- 新增 `agent.progress.entryMaxChars`（Number 40..200，默认 80，HOT）并透传装配。
- 删除 `agent.progress.patchIntervalMillis` + **schemaVersion v3→v4 迁移**：非默认用户值折入 `experimental.retiredV3`，默认值静默删（v1→v2 fileMessage 4 键退役同构先例）。
- 9min 段轮换**保留**：来源是飞书侧「卡片流式 10 分钟自动关流」假设（gateway-contract BC-1035），长跑 >10min 任务仍需轮换续命；24KB ByteBudget 判定保留作防御。

## Impact

- **行为变化（刻意变更，登记契约)**：进度卡推送语义从「增量累积」变「全量替换最新窗口快照」——卡片内容恒等于最新窗口，无历史累积、无重复段落。
- **breaking（接口层）**：`CardStreamSink.tryAppend → tryReplace`，仅 2 个实现者，编译期暴露。
- **配置迁移**：v3 文档一次性迁到 v4；`patchIntervalMillis` 调过参的用户值保底进 `experimental.retiredV3`（可手工找回）。
- **数值零变更**：窗口 6 条/80 字/2000 字与 BC-335..339 文案不动，只动推送语义。
- **代码**：`lark-gateway`（CardStreamSink/LarkCardStreamSink + 5 个流测试）、`lark-bridge`（ProgressSink/SyntheticGateway/BridgeConfig/BridgeFields/ConfigMigrations/BridgeAssembler）。
- **不涉及**：终态 `flushTerminal`（本就全量替换）、StreamTerminal 落账、CardReplyLedger、表情信号链。
