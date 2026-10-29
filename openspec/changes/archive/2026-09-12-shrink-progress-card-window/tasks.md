# Tasks: shrink-progress-card-window

## 1. 网关层：tryReplace 语义

- [x] 1.1 `CardStreamSink` 接口 `tryAppend` 替换为 `tryReplace(text): Boolean`，KDoc 写明全量替换语义。验证：`./gradlew :lark-gateway:compileKotlin`
- [x] 1.2 `LarkCardStreamSink` 改造：`tryReplace` 用 1 槽 conflate `slot.set(Snapshot(text, text.length, dirty=true))`；主循环 flush 守卫 `snap.dirty && timeHit`；删 `charsHit` 判定与 `lastFiredChars`；`rotateSegment` 无条件补 patch。验证：`CardThrottleTest` 重写后全绿
- [x] 1.3 网关流测试同步：`CardStreamShapeTest`/`SegmentRotationTest`/`Stream10MinRotationTest`/`RotationFailureTerminatesTest`/`StreamTerminalSignalTest` 断言 append→replace 改名（10Min 轮换用例原样零改动）。验证：`./gradlew :lark-gateway:test`

## 2. 桥接层：泵直通 + 合成网关同形

- [x] 2.1 `pumpSnapshotsToCard` 简化：删 `lastLength`/delta 计算/`onRotated` 整帧重推分支，改 `sink.tryReplace(snapshot)` 直通，返回推送帧计数；`droppedSnapshots` 观测保留。验证：`./gradlew :lark-bridge:compileKotlin`
- [x] 2.2 `SyntheticGateway.SyntheticCardStream` 同形：appends→replaces、`latest()` 返回最新快照、`accumulated()` 语义对齐。验证：`SyntheticGatewayTest` 全绿
- [x] 2.3 `CardThrottleTest` 新增反例：快照变短（内容变化）必须 patch。验证：`./gradlew :lark-gateway:test --tests "*CardThrottle*"`

## 3. 配置域：entryMaxChars 暴露 + patchIntervalMillis 删除

- [x] 3.1 `ProgressConfig` 删 `patchIntervalMillis`、加 `entryMaxChars: Int = 80`；`schemaVersion` 3→4。验证：`./gradlew :lark-bridge:compileKotlin`
- [x] 3.2 `ConfigMigrations`：`CURRENT_VERSION=4`、MIGRATIONS 追加 `migrateV3ToV4`（守卫 `version >= 6`——step 收到的 version = fromVersion + 本步下标，下标 2 ⇒ v3 文档收到 5）；非默认 `patchIntervalMillis` 折入 `experimental.retiredV3`。验证：`ConfigMigrationsTest` 新增 v3→v4 三用例全绿
- [x] 3.3 `BridgeFields`：`patchIntervalMillis` 字段替换为 `entryMaxChars`（Number 40..200，默认 80，HOT，tier=ADVANCED）。验证：`BridgeFieldsTest` 61 口径净不变
- [x] 3.4 `BridgeAssembler` 装配点透传 `entryMaxChars`。验证：`./gradlew :lark-bridge:test`

## 4. 批次验收

- [x] 4.1 `./gradlew :lark-bridge:test :lark-gateway:test :feature-larkbridge:jvmTest` 全绿（含 `CardThrottleTest` 重写：1000 replaces/秒 ≤4 patch、变短必须 patch、slot.set 结构断言、latest wins）
- [x] 4.2 守护测试口径同步：`BridgeFieldsTest`/`BridgeConfigDefaultsTest`/`BridgeConfigRoundTripTest`/`ConfigMigrationsTest`/`CheckCommandTest` 字段数与键名断言
- [x] 4.3 真机验证项**登记**（执行待真机，见 agent-artifact-return 5.4 与 docs 真机清单）：长输出 agent 任务卡片全程 ≤ 窗口快照、无重复段落；跨 9min 任务轮换正常；10min 自动关流确认（BC-1035）
- [x] 4.4 `docs/pages/lark-bridge.md` 同步（字段表 patchIntervalMillis→entryMaxChars、推送语义小节）——随批次 3 一并落
