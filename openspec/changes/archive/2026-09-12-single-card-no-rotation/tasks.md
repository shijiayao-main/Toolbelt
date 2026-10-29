# Tasks: single-card-no-rotation

## 1. 主动轮换退役

- [x] 1.1 `LarkCardStreamSink` 主循环删 `deadlineHit` 分支与 `segmentStartedAt` 状态（被动重建走既有 200750 分支）。验证：`./gradlew :lark-gateway:compileKotlin`
- [x] 1.2 `Tuning.streamSegmentDeadlineMs` KDoc 标退役（常量保留供 11min 真机实验复现参照）。验证：编译通过
- [x] 1.3 ByteBudget 分支保留不动（24KB 硬限防御）。验证：`byte budget rotation still works without deadline` 用例绿

## 2. 未知码兜底升级 + 活锁修复

- [x] 2.1 `check()` 的 `else -> GIVE_UP` 改 `else -> REBUILD_STREAM`（已知结构性错误 CardJsonInvalid 保持 GIVE_UP）。验证：`unregistered code rebuilds instead of silent giveup` + `known structural code still gives up` 用例绿
- [x] 2.2 🔴 P0-1 同款：`actOnFailure` 三个重建/轮换分支丢弃 `rotateSegment` 返回值——统一失败置 `open=false`（不修此项 D2 会引入活锁）。验证：`passive rebuild with failing create terminates loop` 用例绿
- [x] 2.3 旧卡收尾两步补 `.onFailure` warn + 业务码非零走 `check()` 计数（静默盲点修复）。验证：编译 + 既有轮换用例不红

## 3. 测试反转

- [x] 3.1 `Stream10MinRotationTest` 语义反转：跨 9min/18min 持续流式恒 1 段、零 TimeDeadline 信号；保留 200750 被动重建用例；新增未知码重建/结构错误死流/ByteBudget 保留三用例。验证：`./gradlew :lark-gateway:test`
- [x] 3.2 `RotationFailureTerminatesTest` deadline 用例改写为「未登记码 → REBUILD → 建卡失败 → 终止」路径（推进量含最后一个 poll 周期）。验证：同上

## 3b. 对抗评审修复（2026-09-12，51-agent 工作流）

- [x] 3b.1 🔴 P1：`check()` else→REBUILD_STREAM 无上限——patch 持续报错而建卡持续成功时约 4 张幽灵卡/秒。新增 `Tuning.maxConsecutiveRebuilds`/`streamRebuildBackoffMs` + `rebuildBudgeted()`，计数只由**成功 patch** 清零。验证：`persistent unknown code stops after rebuild budget instead of flooding cards`（已 mutation-test 确认无预算即红）
- [x] 3b.2 P2：`firePatch` 跨挂起点无条件清 dirty ⇒ 挂起窗口内到达的新快照被误标已刷（卡片停旧内容）。改 CAS 比对。验证：既有 CardThrottleTest 不红
- [x] 3b.3 补 ⑧ 用例：一次成功 patch 后预算清零（抖动不累积误杀长任务）。验证：绿

## 4. 验收

- [x] 4.1 `./gradlew :lark-bridge:test :lark-gateway:test :feature-larkbridge:jvmTest :composeApp:jvmTest --rerun-tasks --no-build-cache` 全绿
- [x] 4.2 真机验收（2026-09-12 维护者确认真机跑过，顺车裁决 BC-1035）：>11min 长任务过程卡恒 1 张；若出现第 2 张看是否 200750 自动重建（预期行为，届时再议 card().update() 二开）；观测第 10min 后 patch 是否报错
- [x] 4.3 docs 同步（核对确认已完成：lark-bridge.md:302-308 单卡语义 + :748 T11 runbook 第 13 项已补回）：`docs/pages/lark-bridge.md` 轮换语义改单卡 + **T11 runbook 补回丢失的 10min 验证项**（gateway-contract §13-6 处方）
