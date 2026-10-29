# Proposal: single-card-no-rotation

## Why

用户真机反馈（2026-09-12）：18.7 分钟的 AVM 任务在群里发了 **3 张过程卡**（`agentTimeoutMinutes=60` 跑满则 7 张）——「已受理 思考中」卡片依旧刷屏。消息条数公式：**1（受理卡）+ ⌈T/9min⌉−1（TimeDeadline 每 9min 主动轮换各发一条新消息）**，终态原地更新不发新卡。

轮换根因 BC-1035「飞书卡片流式 10 分钟自动关流」经取证是**从未实测的纯假设**：从重写方案文件 R7⑤ 一路抄到 `Tuning.kt:37`，M1 spike 零触及、S6 单卡最长只活几十秒、gateway-contract §13-6 的 11min 实验处方始终没人跑、T11 runbook 还把验证项弄丢了。用户拍板：**只发一张过程卡**（撤回旧卡与保留多卡都被否——「都很怪」）。

## What Changes

- **主动 TimeDeadline 轮换退役**：主循环 `deadlineHit` 分支与 `segmentStartedAt` 状态删除；`Tuning.streamSegmentDeadlineMs` 常量保留仅作历史参照（KDoc 标退役）。ByteBudget(24KB) 轮换保留（飞书单卡 30KB 硬限防御，滚动窗口 2000 字恒不可达）。
- **被动重建兜底**：服务端若真关流 ⇒ patch 报 200750 EntityExpired ⇒ 既有 `check()` 的 `ROTATE_ENTITY_EXPIRED` 分支自动换卡续命（降级为旧多卡行为，不静默死流）；若假设为假 ⇒ 恒 1 张卡。
- **未知码兜底升级**：`check()` 的 `else -> GIVE_UP` 改 `else -> REBUILD_STREAM`——被动轮换退役后，服务端报未登记码若仍死流（进度永停、无人知晓）比换卡续命糟糕。已知结构性错误（300302/230099 等 → CardJsonInvalid）保持 GIVE_UP 不变（换卡也没用）。
- **P0-1 同款修复（评审发现）**：`actOnFailure` 的三个重建/轮换分支此前**丢弃 `rotateSegment` 返回值**——重建失败不置 `open=false` 会 25ms 无限重试活锁（约 80 次/秒轰 API）。现统一失败即关闭。
- **观测盲点修复**：`rotateSegment` 旧卡收尾两步（patch+finishSettings）此前裸 `runCatching` 静默吞失败（旧卡可能永驻「生成中」且零日志）——补 warn 日志 + `check()` 计数。
- **测试反转**：`Stream10MinRotationTest` 语义整体反转为「不轮换」断言（18min 恒 1 段）+ 新增未登记码重建/已知结构错误死流负例 + ByteBudget 保留例；`RotationFailureTerminatesTest` 的 deadline 用例改为被动重建失败路径。

## Impact

- **行为变化（刻意）**：长任务过程卡从 ⌈T/9min⌉ 张收敛为恒 1 张；BC-1035 假设为真时自动降级回多卡（200750 触发）。
- **契约变更**：9min 主动轮换条款退役（gateway-contract §11.7 的 NF-04 语义反转）；`StreamSegmentRotated(TimeDeadline)` 信号理论上不再出现（ByteBudget/EntityExpired/SequenceConflict 仍会）。
- **真机待验**（验收顺车）：>11min 长任务观测 ①第 10min 后 patch 是否报错/报什么码 ②卡片最终形态——这是所有后续二开（如 card().update() 整卡续写）的共同前置实验。
