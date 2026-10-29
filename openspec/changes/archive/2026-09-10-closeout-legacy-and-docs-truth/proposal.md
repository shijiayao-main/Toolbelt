# Proposal — closeout-legacy-and-docs-truth

## Why

架构治理三 change 归档后遗留 5 项收尾工作：① composeApp 内 `core.data`/`core.initializer` 包名冒称 :core（后者经读码确认为第二个 split package——LogFileRestoreInitializer 靠跨模块同包免 import 解析 JInitializer，与 ShellToolsDataStore 同构）；② HandlerWeight 分档表 4 处缺 PendingFileGateHandler（其中 docs/pages/lark-bridge.md 的 URGENT 行还残留与代码矛盾的 InteractionHandler）；③ docs-sync SKILL.md 映射表含已迁移的 `core/larkbridge/**` 旧路径；④ PageSettings 开关行迁移后视觉人工验收未做；⑤ rewrite-lark-bridge 归档前置已满足（工件已在 d71cf0c 入库）。同时 docs 审计发现组件手册缺 4 组件、settings.md/shell-tools.md 交互描述滞后等偏差，需 docs-sync 复核。

## What Changes

- **归档**：rewrite-lark-bridge → `archive/2026-09-10-rewrite-lark-bridge/`（先改 .gitignore 再 mv，时序硬性）；归档区整体入库（.gitignore 加 `!openspec/changes/archive/`，历史 3 个归档 change 一并入库——用户已拍板）；修正 4 处旧路径活引用（AGENTS.md:59、docs/index.md:74、docs/pages/lark-bridge.md:3 与 :280）
- **迁移**：EasyDataManager → `host/`、LogFileRestoreInitializer → `host/initializer/`、测试文件随迁；补跨模块同包隐式解析失效的显式 import；AppViewModel 根级包不动（用户已拍板）
- **分档表**：Admission.kt / MessageHandler.kt / HandlerKinds.kt 的 KDoc 与 docs/pages/lark-bridge.md 三档表——补 PendingFileGateHandler(LIGHT)、删 lark-bridge.md URGENT 行的 InteractionHandler（代码零行为改动）
- **SKILL.md ×2**：:33 删 core/larkbridge/**；:31 补 feature-shelltools/**；:37 改 core/data/**（:core）+ core/AppViewModel.kt + 新增 host/**
- **docs-sync 复核**：P1（settings.md 3 处死 SwitchRow 引用、shell-tools.md 确认框/空态描述）；P2（ui-style-guide 补 4 组件小节、index.md 欠账、viewmodel.md 补 registerState）；P3（行数快照现场实测）
- **登记**：UiArchitectureGuardTest 隔离规则前缀匹配 bug（只登记不修）；build/handoff-*.md 仅本地；shell-history-store spec 滞后
- **视觉验收**：AI 启动应用截图，主会话看图核对（多模态仅主会话保证，禁子 agent 看图）

## Capabilities

（无——skip_specs: true。已验证 openspec/specs 对包路径与 host 零引用；分档表是 KDoc；归档与 docs 纠偏无行为变更。）

## Impact

- composeApp 3 文件移动 + 4 文件 import 修正、lark-bridge 3 文件 KDoc、docs 7 文件、SKILL.md 2 文件、.gitignore、openspec 目录
- 验证：`--rerun-tasks --no-build-cache` 下 `:composeApp:jvmTest`、`:lark-bridge:test`、终验 `./gradlew build`
