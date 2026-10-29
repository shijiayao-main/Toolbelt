# Delta: lark-bridge-module

## ADDED Requirements

### Requirement: 过程卡片滚动窗口（全量替换推送）

桥接的过程卡片流式链路 SHALL 以「全量替换最新窗口快照」为唯一推送语义：渲染层 `ProgressAccumulator` 维持有界窗口（最近 N 条事件，条目截断与整卡截断沿用既有 Limits），泵层 `pumpSnapshotsToCard` SHALL 把每次快照**直通** `CardStreamSink.tryReplace(text)`，不做增量拼接、不因窗口丢头整帧重推。卡片在任意时刻 SHALL 恒等于「状态 + 最近 N 条事件」的最新快照——不累积历史、不产生重复段落。

`CardStreamSink` 接口 SHALL 只暴露 `tryReplace(text: String): Boolean`（1 槽 conflate 覆盖：新快照直接替换槽内旧快照，`dirty=true`）；**不得**保留 `tryAppend` 增量语义的成员。网关层主循环的 flush 判定 SHALL 为「`snapshot.dirty` 且 250ms 时间窗到点」——快照内容任何变化（含**变短**：窗口丢头挤掉旧事件）SHALL 触发 patch；不得以「增量字符数 > 0」作为 flush 条件（该判定在替换语义下会漏掉变短快照）。

节流（250ms 时间窗）、24KB 字节预算轮换、9min 时间轮换 SHALL 保留（时间轮换依据飞书「卡片流式 10 分钟自动关流」假设，BC-1035）；`charsHit`（增量 4000 字符触发）SHALL 退役（滚动窗口 cardMaxChars 恒不可达）。

进度事件单条长度 SHALL 由 `agent.progress.entryMaxChars`（Number，40..200，默认 80，HOT）配置并透传至 `ProgressAccumulator`。配置文档 SHALL 不再包含 `agent.progress.patchIntervalMillis`（编辑器可改但管线零消费的死配置，删除）：schemaVersion SHALL 升至 4，v3→v4 迁移 SHALL 把该键的**非默认**用户值折入 `experimental.retiredV3`（可手工找回），默认值静默删除。

#### Scenario: 卡片恒等于最新窗口快照

- **WHEN** agent 持续输出事件使窗口丢头（旧事件被挤出）
- **THEN** 每次推送均为当前窗口的全量替换，卡片不含历史累积段落，长度有界于窗口上限

#### Scenario: 快照变短也必须 patch

- **WHEN** 新快照比上一已推送快照短（dirty=true 但字符增量为负）
- **THEN** 250ms 时间窗到点时网关层照样发出 patch，用户看到的卡片同步缩短

#### Scenario: 高频推送被节流合并

- **WHEN** 1 秒内调用 `tryReplace` 1000 次
- **THEN** 网关层实际 patch ≤ 4 次（250ms 窗口），且最终卡片内容等于最后一次快照（latest-wins）

#### Scenario: 长跑任务 9 分钟轮换续命

- **WHEN** 单条流式会话持续超过 9 分钟
- **THEN** 网关层按既有 TimeDeadline 语义轮换新卡（旧卡保留在会话里），滚动窗口推送语义不受轮换影响

#### Scenario: patchIntervalMillis 调参保底迁移

- **WHEN** v3 配置文档的 `agent.progress.patchIntervalMillis` 为非默认值且执行 v3→v4 迁移
- **THEN** 该值折入 `experimental.retiredV3` 键下，配置加载成功且不丢失用户调参记录；默认值则静默删除

#### Scenario: entryMaxChars 生效

- **WHEN** 用户把 `agent.progress.entryMaxChars` 从 80 改为 40（HOT 生效）
- **THEN** 后续过程卡快照的单条事件长度上限变为 40 字符（超长截断加省略号），无需重启桥接
