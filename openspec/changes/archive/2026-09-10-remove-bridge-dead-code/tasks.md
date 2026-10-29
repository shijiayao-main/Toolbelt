# Tasks — remove-bridge-dead-code

## 1. TaskActivity 全链删除（main）

- [x] 1.1 删除整文件 `lark-bridge/src/main/kotlin/com/jiaoay/lark/bridge/observe/TaskActivity.kt`（220 行）；验证：文件不存在，编译无 TaskActivity 引用报错前的 import 清理已完成
- [x] 1.2 `BridgeAssembler.kt`：删 TaskActivity import（~L52）、构造行（~L555）、Bridge 实参（~L595）、RuntimeBundle.create 形参（~L219）、MetricsSampler 实参（~L406）；验证：该文件 grep `TaskActivity|taskActivity` 零命中
- [x] 1.3 `Bridge.kt`：删 import（~L32）、`taskActivity` 字段（~L131）、`createBundle` 实参（~L696）、`drainAwareConnection` 方法（~L670-671）；验证：该文件 grep `taskActivity|drainAwareConnection` 零命中
- [x] 1.4 `MetricsSampler.kt`：删 `activity` 形参（~L48）与 `activeTasks = activity?.activeCount ?: 0` 行（~L105，快照构造不再传该字段）；验证：该文件 grep `activity|activeTasks` 零命中（Draining 相关不在此文件，若有命中逐一核实）
- [x] 1.5 `observe/BridgeStatus.kt`：删 `BridgeStatusSnapshot.activeTasks` 字段（~L116）；**保留** `BridgeConnectionStatus.Draining.activeTasks`（~L53，数据源 Admission.inFlight）；验证：grep 该文件 `activeTasks` 仅剩 Draining 处
- [x] 1.6 `dispatch/BridgeDispatchers.kt`：删 `BridgeRuntime.drainIntake()`（~L191-199）、尾部 `HotBus`（~L212-232）与 `BridgeEventBus`（~L234-238）、随之未用的 3 个 import（~L16-18：MutableSharedFlow/MutableStateFlow/launch）；验证：文件 grep `HotBus|BridgeEventBus|drainIntake|MutableSharedFlow|MutableStateFlow` 零命中

## 2. P0-2 其余死方法删除（main）

- [x] 2.1 核实 `Admission.overReleaseAnomalies`（`pipeline/Admission.kt:~127`）底层计数器 `overReleases` 的全部写入/读取点：若计数器仅服务该属性则连计数器一并删；若另有 WARN 等消费则只删只读属性；验证：该文件 grep `overReleas` 结果与决策一致且编译通过
- [x] 2.2 `pipeline/ReplySink.kt`：删 `notifyRateLimitedFromSignal`（~L125-131）；验证：全仓 grep `notifyRateLimitedFromSignal` 零命中
- [x] 2.3 `observe/ObserveSeams.kt`：删 `SinkRecord.truncate` 与 `TRUNCATION_SUFFIX`（~L115-124）；同步删 `AuditLogTest.kt:~157` 引用该常量的断言（仅删该断言，测试其余部分保留）；验证：全仓 grep `TRUNCATION_SUFFIX|SinkRecord.truncate`（排除 openspec/docs）零命中

## 3. 测试同步（test）

- [x] 3.1 `ObserveStatusTest.kt`：整块删 7 个专测（~L26-185）；小改 2 个——「droppedTotal 汇总」删 L279 `activeTasks = 2` 与 L288 断言（**L294-297 Draining 断言保留**）、「采样器汇聚」删构造/传参/start/断言相关行（~L305-333 中 activity 部分）；3 个测试不动；文件头注释去掉"任务活动面"表述；验证：该文件 grep `TaskActivity|activeTasks` 仅剩 Draining 相关
- [x] 3.2 `JsonlSinkDropCountTest.kt`：整块删 2 个专测（~L190-233）；小改 2 个——「droppedTotal 汇总 WARN」删 L244 构造与 L249 传参、「数据源缺失」删 L302 断言；文件头注释"五个丢弃面"改四处（对齐 MetricsSampler WARN 文本枚举）；其余 8 个测试不动；验证：该文件 grep `TaskActivity` 零命中
- [x] 3.3 全仓 grep `TaskActivity|HotBus|BridgeEventBus` 零残留（排除 openspec/ 与 docs/reports/）

## 4. feature 层 UI 同步

- [x] 4.1 `feature-larkbridge/.../status/BridgeStatusPanel.kt`：删"活动任务" StatusRow（~L125）；**保留** L263"排空中（N）"（Draining 数据源无关）；验证：该文件 grep `activeTasks` 仅剩 Draining 标签处、"活动任务"零命中

## 5. 全量验证（verifier 取证）

- [x] 5.1 `./gradlew :lark-bridge:test :lark-gateway:test :feature-larkbridge:jvmTest` 全绿；附真实输出片段
- [x] 5.2 扫盘型守护防假绿：`./gradlew :lark-bridge:test :feature-larkbridge:jvmTest --rerun-tasks --no-build-cache` 全绿（🔴 AGENTS.md 要求）；附真实输出片段
- [x] 5.3 `git status` 仅预期文件：lark-bridge main 6 文件 + test 2 文件 + feature-larkbridge 1 文件 + openspec/changes/remove-bridge-dead-code/；无其他意外改动
