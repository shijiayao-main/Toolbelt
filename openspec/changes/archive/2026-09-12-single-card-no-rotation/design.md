# Design: single-card-no-rotation

## Context

过程卡链路：受理卡（第 1 段，`reply.stream(receipt)`）→ 主循环 25ms 轮询（250ms 节流 patch）→ 9min TimeDeadline 主动轮换（每轮换 `createCardEntity` + `sendCardByReference` = **发一条新消息**）→ 终态 `flushTerminal` 原地更新最后一张。

取证（2026-09-12 三代理调研 + 主会话交叉验证）确认了三件事：
1. **多卡公式与真机对账吻合**：audit 末行 18.73min 任务 ⇒ ⌈18.73/9⌉ = 3 张卡，与用户看到的 `segmentIndex=2/3` 逐字对上。
2. **BC-1035 是假设**：「10min 自动关流」从方案清单 R7⑤ 抄到代码常量，全程零实测；gateway-contract §13-6 的验证处方（11min 不轮换观测错误码）从未执行，T11 runbook 还把该项弄丢了。今天之前没有任何一张卡活过 10min（都被我方 9min 主动轮换掉了）。
3. **被动重建兜底已存在**：`check()` 已有 `200750 EntityExpired → ROTATE_ENTITY_EXPIRED → rotateSegment` 分支——主动轮换退役后安全网是现成的。

用户拍板「只发一张」；撤回旧卡方案（发新卡后撤旧卡）被否（「撤回或不动都很怪」）。

## Decisions

### D1. 退役主动轮换而非拉长 deadline 或撤回

- 撤回方案：群里留撤回痕迹，用户否。
- 拉长 deadline（如 55min）：仍是「到点强发新卡」，只是频率变低——不解决「只发一张」的诉求。
- **退役主动轮换**：完全满足「只发一张」；风险面全部由既有被动分支兜住。ByteBudget 分支保留（30KB 硬限防御与时间无关）。

### D2. 未知码 GIVE_UP → REBUILD_STREAM

主动轮换退役后唯一可能导致「进度永停」的路径是：服务端在某个未预期时刻（假设 10min 关流为真、或任何未登记错误）返回未知码，`check()` else 分支 GIVE_UP ⇒ 静默死流。死流比多一张卡糟糕得多（用户盯着一张停在半路的卡永远不知道任务还活着）。改为先试换卡续命：
- 换卡成功 ⇒ 进度恢复（多一张卡，可观测）；
- 换卡失败 ⇒ `rotateSegment` 返回 false ⇒ `open=false` 收敛（不会活锁）。
已知结构性错误码（300302/230099/230001/300312/11311/10002 → CardJsonInvalid）保持 GIVE_UP——它们是请求结构被拒，换卡必然重演。

### D3. actOnFailure 返回值修复（评审顺带发现）

`REBUILD_STREAM`/`ROTATE_BYTE_BUDGET`/`ROTATE_ENTITY_EXPIRED` 三分支此前丢弃 `rotateSegment` 返回值——与主循环 `budgetHit` 分支的 P0-1 修复（丢弃 false 会自维持活锁）同款隐患。统一改为失败置 `open=false`。这是 D2 的前置：没有这个修复，REBUILD_STREAM 升级会引入活锁。

### D4. 旧卡收尾观测（顺带修盲点）

`rotateSegment` 对旧卡的「定格 patch + finishSettings 关流」此前裸 `runCatching` 丢弃结果——收尾失败旧卡永驻「生成中」且零日志零计数。补 `.onFailure` warn + 成功但业务码非零时走 `check()` 计数。收尾失败仍不阻断轮换（旧卡已死，新卡继续才是正途）。

## Risk / Trade-offs

- **10min 假设未裁决**：若为真，被动重建恢复多卡（=现状）；真机验收顺车裁决（跑 >11min 任务观测错误码），届时再议 card().update() 整卡续写二开。
- **未知码重建**最坏多一张卡；重建失败正确收敛（open=false，进度停在最后帧，终态仍可发文字结论）。
- 虚拟时间测试的反转是语义变更的忠实镜像（不轮换 = 恒 1 段）。

## Migration Plan

无配置迁移（轮换不可配、无该字段）。落地序：D3（返回值修复）→ D1（退役 deadlineHit）→ D2（else 升级）→ D4（收尾观测）→ 测试反转。
