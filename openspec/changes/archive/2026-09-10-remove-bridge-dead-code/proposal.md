# Proposal — remove-bridge-dead-code

## Why

2026-09-10 架构分析确认 `:lark-bridge` 存在约 1,000 行死机制与半死接缝：`TaskActivity`（"在途任务活动面"）装配链完整但生产链路**零写入方**，生产运行时驾驶舱"活动任务"恒为 0，属"功能假象"；另有 HotBus/BridgeEventBus 及 4 个死方法全仓零引用。用户已拍板：删除而非接通。

## What Changes

- **BREAKING（仅面向 :lark-bridge 内部 API，无外部消费方）**：
  - 删除 `observe/TaskActivity.kt`（220 行）及全链引用：`BridgeAssembler` 构造/传参、`Bridge` 持有字段与 `RuntimeBundle.create` 形参、`MetricsSampler.activity` 形参、`BridgeStatusSnapshot.activeTasks` 字段、`BridgeStatusPanel`"活动任务"行、2 个测试文件中的 9 个专测块
  - 删除 `dispatch/BridgeDispatchers.kt` 尾部零引用的 `HotBus` / `BridgeEventBus` 与 `BridgeRuntime.drainIntake()`，及随删的 3 个未用 import
  - 删除 3 个零调用死方法：`SinkRecord.truncate`（连带 `TRUNCATION_SUFFIX` 与 `AuditLogTest` 中引用该常量的反向断言）、`ReplySink.notifyRateLimitedFromSignal`、`Admission.overReleaseAnomalies`
  - 顺带删除 `Bridge.drainAwareConnection`（同性质零调用死 seam，含测试亦零调用）
- **不变**：`Draining.activeTasks`（"排空中（N）"）数据源是 `Admission.inFlight`，与 TaskActivity 无关，完整保留；`BridgeStatusSnapshot` 其余字段、十步关闭、装配唯一性守护全部不动。
- 预期净减约 1,100-1,300 行（main ~420 + test ~700+），零行为变化。

## Capabilities

### New Capabilities

（无——纯删除重构，不引入新行为。）

### Modified Capabilities

（无——TaskActivity/HotBus 等死代码在任何主 spec 中无 Requirement（grep `openspec/specs/` 零命中），`specs/lark-bridge-module` 已自我声明 superseded，现行契约 `behavior-contract.md` 的 D18 丢弃面枚举不含活动面。无可写 delta。）

**本 change 声明 `skip_specs: true`**：零行为变化，无 spec 级变更。

## Impact

- `:lark-bridge`（main 6 文件 + test 2 文件）、`:feature-larkbridge`（1 处 UI 行）
- 零牵连（已验证）：`BridgeAssemblerParityTest`（不反射 taskActivity，源码扫描字面量计数不变）、`IntakeGuardTest`、`BridgeLifecycleTest`、`:lark-gateway`、`:composeApp`、`:core`
- 验证命令：`./gradlew :lark-bridge:test :lark-gateway:test :feature-larkbridge:jvmTest`；扫盘型守护用 `--rerun-tasks --no-build-cache` 防假绿
