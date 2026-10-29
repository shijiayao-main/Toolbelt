# Tasks — closeout-legacy-and-docs-truth

## 1. W1：rewrite-lark-bridge 归档

- [x] 1.1 【AI】.gitignore:21-22 改为 `openspec/changes/*` + `!openspec/changes/archive/`（删 rewrite-lark-bridge 白名单行）；验证：git check-ignore archive/ 下文件不再命中
- [x] 1.2 【AI】/opsx:archive rewrite-lark-bridge（工件不完整 warning 确认继续，无 delta specs 不触发 sync）→ archive/2026-09-10-rewrite-lark-bridge/；验证：openspec list 不再列出
- [x] 1.3 【AI】修正 4 处旧路径引用：AGENTS.md:59、docs/index.md:74、docs/pages/lark-bridge.md:3 与 :280 → 指向 archive/2026-09-10-rewrite-lark-bridge/；验证：grep "changes/rewrite-lark-bridge"（排除 archive）零命中
- [ ] 1.4 【用户】git add .gitignore openspec/ + AGENTS.md docs/ + commit；验证：git ls-files openspec/changes/archive 计数 ≥ 5 + 3 历史归档工件

## 2. W2：composeApp 迁移

- [x] 2.1 【用户】git mv 3 文件：core/data/EasyDataManager.kt → host/；core/initializer/LogFileRestoreInitializer.kt → host/initializer/；jvmTest .../core/data/EasyDataManagerLogFileEnabledTest.kt → jvmTest .../host/；验证：git status rename
- [x] 2.2 【AI】3 个 package 行 + LogFileRestoreInitializer 补 core.initializer.JInitializer import + 测试补 LocalDataManager/FileUtils import + 消费方 import（main.kt:16、PageSettings.kt:33、AppViewModel.kt:7，:8 loadEnum 保留 core.data）；验证：:composeApp:compileKotlin + compileTestKotlin 绿
- [x] 2.3 【AI】UiArchitectureGuardTest 隔离规则 KDoc 加"已知不生效"登记行；验证：编译绿
- [x] 2.4 【AI】grep 闸 + ./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache 绿；【用户】commit

## 3. W3：分档表 KDoc 对账

- [x] 3.1 Admission.kt（~:178-183）LIGHT 行补 PendingFileGateHandler（URGENT 行确认已正确）
- [x] 3.2 MessageHandler.kt（~:14-18）LIGHT 行补 PendingFileGateHandler
- [x] 3.3 HandlerKinds.kt build() KDoc（~:46-50）SYSTEM 槽补 PendingFileGateHandler(LIGHT，装配首位)；Responder.kt:103 权重语义确认无需改动
- [x] 3.4 验证：:lark-bridge:compileKotlin 绿 + grep 三表；【用户】commit

## 4. W4：docs-sync 复核

- [x] 4.1 SKILL.md ×2（.claude + .github 镜像）：:33 删 core/larkbridge/**；:31 补 feature-shelltools/**；:37 改 core/data/**（:core）+ core/AppViewModel.kt + host/**
- [x] 4.2 P1：settings.md:35/:61/:67 私有 SwitchRow → AppSwitchRow（hover 形态）
- [x] 4.3 P1：shell-tools.md:15/:16/:21/:239 补 AppConfirmDialog(isDangerous=true) 与空态交互登记
- [x] 4.4 P2：ui-style-guide.md 补 AppSwitchRow/AppConfirmDialog/EmptyStateView/CommitTextField 组件小节 + 6.2 空态改组件优先 + 白名单口径现场实测统一
- [x] 4.5 P2：index.md :72 勾选/:69 措辞拆分/:63 日期推进/目录树补 reports/；viewmodel.md:85-93 补 registerState
- [x] 4.6 ①连锁：docs/core/data.md:3/:12 + docs/architecture.md:119 改 host 包表述
- [x] 4.7 P3：5 处行数快照现场 wc -l 实测刷新（navigation.md:45、lark-bridge.md:202/:216/:312、白名单口径）
- [x] 4.8 验收：docs-sync 自检 + grep 无死 SwitchRow；【用户】commit

## 5. W5：PageSettings 视觉验收（主会话执行，禁子 agent 看图）

- [x] 5.1 后台 ./gradlew :composeApp:run + 就绪等待
- [x] 5.2 截图落盘 /tmp（gdm-screenshot → D-Bus Screenshot → 用户手动 三级降级）
- [x] 5.3 主会话 Read 图核对（hover 渐入/行可点/bodyLarge/布局）；非设置页时请用户点侧栏
- [x] 5.4 kill 应用；用户认可后勾选

## 6. W6：收口

- [x] 6.1 ./gradlew build --rerun-tasks --no-build-cache 全绿
- [x] 6.2 design.md 登记项核对（UiGuard bug / handoff 本地件 / shell-history-store spec 滞后）
- [x] 6.3 tasks 全勾 + openspec validate + 归档；【用户】commit
