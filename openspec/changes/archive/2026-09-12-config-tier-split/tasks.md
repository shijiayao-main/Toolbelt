# Tasks: config-tier-split

## 1. 字段表：FieldTier 机制

- [x] 1.1 `BridgeFields.kt` 新增 `enum class FieldTier(val title: String) { BASIC("基础配置"), ADVANCED("进阶配置") }`；`BridgeField` 构造参数加 `val tier: FieldTier = FieldTier.ADVANCED`（默认进阶）。验证：`./gradlew :lark-bridge:compileKotlin`
- [x] 1.2 5 个字段标 BASIC：`connection.appId`/`connection.appSecret`/`connection.region`/`access.allowedSenders`/`workspace.root`。验证：`BridgeFieldsTest` tier 断言全绿
- [x] 1.3 `BridgeFieldsTest` 新增三条断言：tier 两分全覆盖（BASIC 5 + ADVANCED 60）、BASIC 清单逐字（防漂移）、`FieldTier.title` 非空。验证：`./gradlew :lark-bridge:test --tests "*BridgeFields*"`

## 2. UI：两段渲染

- [x] 2.1 `ConfigFieldRenderer.kt` 的 `ConfigEditor` 改两段：基础区 `SectionCard(collapsible=false)` 渲染 BASIC 字段；进阶区 `SectionCard(collapsible=true, initialExpanded=false)` 内按 `ConfigGroup` 以 `CardHeader(style=titleSmall)` 分小节渲染 ADVANCED 字段。验证：`./gradlew :feature-larkbridge:compileKotlinJvm`
- [x] 2.2 `ConfigGroup.initialExpanded` KDoc 语义同步（「进阶区内该组小节默认展开」），断言字面零改动。验证：`BridgeFieldsTest` 分组折叠断言原样绿
- [x] 2.3 核实消费方零改动：`SecurityHintsCard`/`ConfigCheckPanel`/`FieldRow`/`LarkBridgePage` 调用签名不受分层影响。验证：`./gradlew :feature-larkbridge:jvmTest`

## 3. 批次验收

- [x] 3.1 `./gradlew :lark-bridge:test :feature-larkbridge:jvmTest :composeApp:jvmTest` 全绿；扫盘守护 `--rerun-tasks --no-build-cache` 通过（UiArchitectureGuardTest 零白名单维持）
- [x] 3.2 截图管线验收（2026-09-12 维护者确认）（`xwd -id` + ffmpeg）：首屏只 5 个基础字段 + 折叠进阶区；展开后 7 小节齐全、RUNTIME/FILE_MESSAGE 小节默认收起——**待真机截图**（AI Read 图片通道失效，需用户贴图确认后勾选）
- [x] 3.3 `docs/pages/lark-bridge.md` 字段表加 tier 说明——随批次 3 一并落
