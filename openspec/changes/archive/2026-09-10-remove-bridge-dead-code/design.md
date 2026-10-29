# Design — remove-bridge-dead-code

## 上下文

来源：架构分析报告 P0-1/P0-2。删除对象全部经 grep + 读码双验证为零调用/零引用死代码。本文件记录关键取舍与施工核实点，避免施工者重新调查。

## 核心取舍：activeTasks 字段随机制一并删除（路径 B）

- **背景**：`BridgeStatusSnapshot.activeTasks`（observe/BridgeStatus.kt:116）唯一数据源是 `TaskActivity.activeCount`（MetricsSampler.kt:105），而 TaskActivity 生产零写入 → 该字段恒 0。UI 的"活动任务"行（feature-larkbridge BridgeStatusPanel.kt:125）因此永远是"功能假象"。
- **候选路径**：
  - A：保留字段默认 0 —— feature 层零改动，但假象仍在，违背 P0-1 动机
  - B：删除字段 + 删 UI 行（**已选定**）—— 用户对"活动任务"功能零印象，删除消解假象；改动面已验证仅 1 处 UI
- **B 的边界（重要）**：`BridgeConnectionStatus.Draining.activeTasks`（BridgeStatus.kt:53）是**另一个字段**，数据源 `Admission.inFlight`（真实计数），"排空中（N）"展示完整保留。两字段同名不同源，施工时勿误伤。

## 施工核实点（Explore 已给出行号，施工时二次确认）

1. **`Admission.overReleaseAnomalies`（Admission.kt:127）**：删除前先核实其底层计数器 `overReleases` 的自增点。若计数器仅服务该只读属性 → 一并删除；若计数器还参与 WARN 日志等逻辑 → 只删只读属性，保留计数器与自增。
2. **`SinkRecord.truncate`（ObserveSeams.kt:115-124）**：`AuditLogTest.kt:157` 引用 `TRUNCATION_SUFFIX` 常量做反向断言——断言本身随方法一起删（该测试断言的就是 truncate 行为）。
3. **`ObserveStatusTest` 文件头注释**提及"任务活动面"与 BC-341..352：已核实 behavior-contract 的 BC-341..352 映射的是 ProgressAccumulator/LogBus/SkillRegistry，**无任何 BC 行专属 TaskActivity**，删除不孤儿化契约；注释微调即可。

## 测试处置原则

- **整块删**：仅测死代码本身的测试（ObserveStatusTest 7 个、JsonlSinkDropCountTest 2 个）
- **小改保留**：借用死代码构造场景但断言其他行为的测试（丢弃计数汇总、数据源汇聚）——删掉 activity 相关行后其余断言原样保留
- **绝不顺手删活测试**：与 MetricsSampler.kt:137-141 WARN 文本的"四处枚举"对齐为准（该枚举本就不含活动面）

## 不做什么

- 不动 P0-3/P0-4/P0-5（:ui 迁移、split package、docs 修正）——另行施工
- 不动 `MessageHandler.kt:16` / `Admission.kt:188` 的 URGENT/LIGHT 分档表（属 P0-5）
- 不跑任何 git 命令（用户自行提交）；rewrite-lark-bridge 的归档不在本 change 范围
- 不降低任何守护测试的断言强度（ParityTest/IntakeGuardTest 已验证零牵连，无此需要）

## 验证设计

1. 全量测试：`./gradlew :lark-bridge:test :lark-gateway:test :feature-larkbridge:jvmTest`
2. 防 build cache 假绿（AGENTS.md 🔴 要求，ParityTest 属扫盘型）：`--rerun-tasks --no-build-cache`
3. 残留 grep 清零：`TaskActivity|HotBus|BridgeEventBus|drainIntake|notifyRateLimitedFromSignal|drainAwareConnection|overReleaseAnomalies|TRUNCATION_SUFFIX`（main+test，排除 openspec/ 与 docs/reports/）
4. feature 层"活动任务"字样清零；"排空中"文案保留
5. `git status` 仅预期文件变更
