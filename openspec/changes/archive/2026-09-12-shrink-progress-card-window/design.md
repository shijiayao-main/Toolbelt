# Design: shrink-progress-card-window

## Context

过程卡片链路三层：`ProgressAccumulator`（渲染层窗口 6 条/80 字/2000 字，有界）→ `pumpSnapshotsToCard`（泵层，此前推 delta）→ `LarkCardStreamSink`（网关层，250ms 节流 + 24KB ByteBudget + 9min 轮换，底层 `patchElement` 全量替换）。

此前泵层与网关层的组合产生累积：泵只推 delta、窗口丢头（新事件挤掉旧事件）时整帧重推造成重复段落；网关层 slot 把 delta 追加进累积缓冲。三层里只有渲染层有界，下游两层都在变长。

`patchElement` 的飞书语义本就是「全量替换该 element 内容」——把中间两层全部改成直通全量替换，窗口上界就能穿透到卡片本体。

## Goals / Non-Goals

**Goals**
- 卡片恒等于「状态 + 最近 N 条窗口」的最新快照——不累积历史、不重复段落。
- 修正「快照变短永不 patch」的 flush 缺陷。
- 删除对用户撒谎的死配置，暴露真实生效但不可调的参数。

**Non-Goals**
- 窗口数值调整（6/80/2000 与 BC-335..339 文案零变更）。
- 9min 段轮换的去留（保留——长跑续命依赖它，BC-1035）。
- 终态路径（`flushTerminal` 语义本就正确，零改动）。

## Decisions

### D1. tryAppend → tryReplace（接口替换，不并存）

不保留 `tryAppend`：并存留死接口违背「编译器穷尽检查」纪律；全仓实现者仅 2 处（`LarkCardStreamSink`/`SyntheticCardStream`），改签名让编译器把所有调用方翻出来。`tryReplace` 用 1 槽 conflate 覆盖 `slot.set(Snapshot(text, text.length, dirty=true))`——同 conflate 语义，只是槽里存的是「最新全文」而非「待追加增量」。

### D2. flush 条件改 `snap.dirty && timeHit`

旧守卫 `pendingChars > 0` 在增量语义下正确（有增量才 patch），在替换语义下有缺陷：新快照更短（事件被窗口挤掉）时 `pendingChars < 0`，内容变了却永不 patch——用户看到的卡片停在旧快照。改 `snap.dirty` 后任何内容变化都触发 patch。`charsHit`（4000 字符）退役：滚动窗口 cardMaxChars=2000，charsHit 恒不可达，留着是死代码。补单测钉死「快照变短必须 patch」反例。

### D3. 泵层直通化

`pumpSnapshotsToCard` 删 `lastLength`/delta/`onRotated` 分支后只剩「拿快照 → tryReplace」直通，返回推送帧计数；`droppedSnapshots` 观测（D18 丢帧可观测）保留。合成网关 `SyntheticCardStream` 同形改造（appends→replaces、`accumulated()` 返回最新快照），保证测试与生产同形。

### D4. patchIntervalMillis 删除 + v3→v4 迁移

真实节流在网关侧固定 250ms 时间窗（BC-1033 锁定），`patchIntervalMillis` 编辑器可改但管线零消费——接线则与「SDK 调优旋钮不暴露进配置」决策冲突，删掉是唯一诚实选项。迁移走 v1→v2 同构先例：非默认用户值折入 `experimental.retiredV3`（可手工找回），默认值静默删。

🔴 迁移框架语义注意：step 收到的 version = fromVersion + 本步下标（`migrateV3ToV4` 是下标 2，v3 文档收到 5），守卫须写 `if (version >= 6) return doc`——不是直觉上的 `>= 4`。

### D5. entryMaxChars 透传暴露

`ProgressAccumulator` 构造已有该参数（默认 80），只是 `BridgeAssembler` 装配点没传。新增 `agent.progress.entryMaxChars`（Number 40..200，默认 80，HOT）并透传——一条事件在过程卡里占几行由用户调，与窗口总字数（cardMaxChars）分工不同。

## Risk / Trade-offs

- **API breaking**：`tryAppend→tryReplace` 是接口级变更——但实现者仅 2 处且都在本仓库，编译期全暴露，无外部消费者。
- **历史过程不可回看**：滚动窗口语义下用户在飞书里翻不到更早的事件——这是「精简」诉求的直接取舍，任务全过程的完整轨迹仍在审计日志里。
- **迁移一次成型**：v3→v4 是删键迁移，`retiredV3` 保底用户调参；框架成熟（3 步链 v1→v2→v3→v4 已有测试覆盖）。

## Migration Plan

1. 网关层先行：接口改签名 + 两实现者改造 + 流测试断言改名（10Min 轮换用例原样）。
2. 桥接层跟进：泵层直通 + Synthetic 同形 + 配置域（+entryMaxChars −patchIntervalMillis）+ 迁移 + 装配透传。
3. 守护测试同步：`BridgeFieldsTest` 口径（61 净不变 +1−1）、`ConfigMigrationsTest`（v3→v4 用例）、`AgentPipelinePumpTest`（replace 语义断言）。
