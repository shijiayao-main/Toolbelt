# Delta: lark-bridge-module

## MODIFIED Requirements

### Requirement: 过程卡片滚动窗口（全量替换推送）

桥接的过程卡片流式链路 SHALL 以「全量替换最新窗口快照」为唯一推送语义：渲染层 `ProgressAccumulator` 维持有界窗口（最近 N 条事件，条目截断与整卡截断沿用既有 Limits），泵层 `pumpSnapshotsToCard` SHALL 把每次快照**直通** `CardStreamSink.tryReplace(text)`，不做增量拼接、不因窗口丢头整帧重推。卡片在任意时刻 SHALL 恒等于「状态 + 最近 N 条事件」的最新快照——不累积历史、不产生重复段落。

`CardStreamSink` 接口 SHALL 只暴露 `tryReplace(text: String): Boolean`（1 槽 conflate 覆盖：新快照直接替换槽内旧快照，`dirty=true`）；**不得**保留 `tryAppend` 增量语义的成员。网关层主循环的 flush 判定 SHALL 为「`snapshot.dirty` 且 250ms 时间窗到点」——快照内容任何变化（含**变短**：窗口丢头挤掉旧事件）SHALL 触发 patch；不得以「增量字符数 > 0」作为 flush 条件。

**单卡收敛（2026-09-12 single-card-no-rotation）**：主动 TimeDeadline 轮换 SHALL 退役——无论流式会话持续多久，网关层 SHALL NOT 因时间到期强发新卡；一次任务的过程卡 SHALL 恒为 1 张（受理卡），终态原地更新。安全网三层：
1. **被动重建**：服务端返回 200750（CardEntityExpired）或任何**未登记业务码**时，SHALL 自动重建新卡续命（复用既有 rotateSegment；重建失败 SHALL 置 sink 关闭并退出主循环——不得 25ms 无限重试活锁）；已知结构性错误码（300302/230099/230001/300312/11311/10002 → CardJsonInvalid）SHALL 保持关闭不重建（重试必然再错）。
2. **字节预算轮换保留**：单卡内容达 24KB（`cardByteBudget`，飞书 30KB 硬限防御）SHALL 仍触发轮换；滚动窗口 cardMaxChars=2000 下恒不可达，仅作 cardMaxChars 调大时的兜底。
3. **旧卡收尾可观测**：轮换/重建时对旧卡的「定格 patch + finishSettings 关流」失败 SHALL 记 warn 日志并计入失败计数（不得静默吞）；收尾失败不阻断新卡。

🔴 **重建预算（2026-09-12 对抗评审补入）**：仅封死「重建失败」SHALL NOT 视为收敛——「patch 持续报错而建卡持续成功」时每 250ms 就能成功重建一次（≈4 张幽灵卡/秒入会话，恰是本 change 要消灭的刷屏换了触发路径）。故 sink SHALL 维护**连续重建计数**：达上限（`Tuning.maxConsecutiveRebuilds`）即关闭流式并记 warn；每次重建后 SHALL 退避 `Tuning.streamRebuildBackoffMs`；计数 SHALL **只在一次成功 patch 后清零**（在重建成功后清零等于没有上限）。`firePatch` 清除 dirty SHALL 以**发出那一版**为准（CAS 比对），不得用无条件写回——否则 patch 挂起窗口内到达的新快照被误标已刷，卡片停在旧内容。

进度事件单条长度 SHALL 由 `agent.progress.entryMaxChars`（Number，40..200，默认 80，HOT）配置并透传至 `ProgressAccumulator`。配置文档 SHALL 不含 `agent.progress.patchIntervalMillis`（schemaVersion 4 已迁移）。

#### Scenario: 长任务过程卡恒 1 张

- **WHEN** agent 任务持续 18 分钟且持续产出事件
- **THEN** 全程只有受理时发出的 1 张过程卡（终态原地更新），群里不再出现 9min 轮换的新卡

#### Scenario: 长跑任务 9 分钟轮换续命

- **WHEN** 单条流式会话持续超过 9 分钟
- **THEN** 主动 TimeDeadline 轮换已退役（2026-09-12 single-card-no-rotation），网关层不再因时间到期强发新卡——续命职责由服务端关流时的**被动重建**承接（见「服务端关流时被动重建续命」场景）；本场景保留为历史语义记录，不再是现行行为

#### Scenario: 服务端关流时被动重建续命

- **WHEN** 服务端对 patch 返回 200750 EntityExpired（或任何未登记业务码）
- **THEN** 网关层自动新建卡片实体续命（发出新卡），进度不中断；若重建建卡也失败，sink 关闭且主循环终止（无活锁）

#### Scenario: patch 持续失败时不无限重建刷幽灵卡

- **WHEN** patch 持续返回未登记码而建卡/发卡持续成功（如建出的卡缺元素导致 patch 路径 404）
- **THEN** 重建次数达 `maxConsecutiveRebuilds` 后 sink 关闭并记 warn——绝不每 250ms 重建一次把幽灵卡刷进会话

#### Scenario: 瞬时抖动不累积到重建上限

- **WHEN** 重建后的某次 patch 成功
- **THEN** 连续重建计数清零（抖动不累积，长任务不因偶发失败被误杀）

#### Scenario: 已知结构性错误不重建

- **WHEN** patch 返回 300302（卡片 JSON 结构错误）
- **THEN** sink 关闭不重建（换卡必然重演同类错误），失败计数可见

#### Scenario: 快照变短也必须 patch

- **WHEN** 新快照比上一已推送快照短（dirty=true 但字符增量为负）
- **THEN** 250ms 时间窗到点时网关层照样发出 patch

#### Scenario: 卡片恒等于最新窗口快照

- **WHEN** agent 持续输出事件使窗口丢头（旧事件被挤出）
- **THEN** 每次推送均为当前窗口的全量替换，卡片不含历史累积段落，长度有界于窗口上限

#### Scenario: 高频推送被节流合并

- **WHEN** 1 秒内调用 `tryReplace` 1000 次
- **THEN** 网关层实际 patch ≤ 4 次（250ms 窗口），且最终卡片内容等于最后一次快照（latest-wins）

#### Scenario: entryMaxChars 生效

- **WHEN** 用户把 `agent.progress.entryMaxChars` 从 80 改为 40（HOT 生效）
- **THEN** 后续过程卡快照的单条事件长度上限变为 40 字符（超长截断加省略号），无需重启桥接

#### Scenario: patchIntervalMillis 调参保底迁移

- **WHEN** v3 配置文档的 `agent.progress.patchIntervalMillis` 为非默认值且执行 v3→v4 迁移
- **THEN** 该值折入 `experimental.retiredV3` 键下；默认值静默删除
