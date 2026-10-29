# Tasks — govern-split-packages

## 1. feature-shelltools 迁移（真 split 主体；可与 change-1 波次 1 同期）

- [x] 1.1 【用户】git mv main 10 文件：`feature-shelltools/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/` 下 `core/data/ShellToolsDataStore.kt` 与 `core/shelltools/{ShellExecModels,ShellExecutionEngine,ShellToolsConfigRepository,ShellToolsRuntime}.kt` → `page/shelltools/`；`core/shelltools/config/{DefaultShellToolsConfigProvider,ShellToolsConfig,ShellToolsConfigLoader,ShellToolsConfigParser,ShellToolsNode}.kt` → `page/shelltools/config/`；验证：git status 显示 rename
- [x] 1.2 【用户】git mv jvmTest 6 文件同构移动（config/ShellToolsConfigParserTest+ToolNodeSearchFilterTest → page/shelltools/config/；shelltools/{ShellExecutionEngineOutputTest,ShellExecutionEngineRobustnessTest,ShellToolsConfigRepositoryTest,ShellToolsRuntimeTest} → page/shelltools/）；验证：git status rename
- [x] 1.3 【用户】git mv `feature-shelltools/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/shelltools/AdbCommandRewriterTest.kt` → `core/src/jvmTest/kotlin/com/jiaoay/easy_dev_toolbox/core/shelltools/`（测 :core 的类，零内容改动）；验证：`:core:compileTestKotlin` 绿
- [x] 1.4 AI 修正迁移文件 package 行（10 main + 6 test）+ ShellToolsDataStore **补 2 个 import**（core.data.AppDataStore、core.data.LocalDataManager）+ config 5 文件补 page.shelltools 父包类 import + ShellToolsConfigRepository 的 config.* import 改前缀；验证：`./gradlew :feature-shelltools:compileKotlin :feature-shelltools:compileTestKotlin` 绿
- [x] 1.5 feature 内 9 消费文件 import 批量修正（ShellToolsViewModel——注意 :12 AdbCommandRewriter import 保留 core.shelltools；ShellHistoryManager；CustomToolsController；ShellCustomEditorDialog；ShellToolsGroupDialogs；ShellToolsParentNode；ShellToolsChildNode；ShellOutputPanel；ShellToolsList）；验证：feature-shelltools 全模块 grep `easy_dev_toolbox.core.shelltools` 仅剩 AdbCommandRewriter 一处
- [x] 1.6 feature jvmTest 3 文件 import 修正（ShellHistoryManagerFlushTest/ShellHistoryManagerRemoveClearTest/ShellToolsViewModelDeviceSelectionTest，internal 接缝同模块不受影响）；验证：`./gradlew :feature-shelltools:jvmTest --rerun-tasks --no-build-cache` 绿
- [x] 1.7 composeApp：AppViewModel.kt:8 import 改 page.shelltools.ShellToolsDataStore；PageSettings.kt:36 死 import 删；验证：`./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache` 绿
- [x] 1.8 防漏闸 grep：feature-shelltools 内 `PreferenceDataStoreFactory` 零命中（进程唯一实例纪律不破）；验证：grep 输出

## 2. feature-larkbridge 迁移（🔴 硬前置：用户提交在途 13 文件）

- [x] 2.1 【用户】git mv main 5 文件：`feature-larkbridge/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/core/larkbridge/{BridgeErrorMapping,BridgeStore,LarkBridgeManager,LegacyConfigImporter,TaskErrorHint}.kt` → `page/larkbridge/`；jvmTest 3（BridgeErrorMappingTest/LegacyConfigImporterTest/TaskErrorHintTest）→ `jvmTest/.../page/larkbridge/`；验证：git status rename
- [x] 2.2 AI 修正 8 个 package 行 + 4 消费文件 import（composeApp main.kt:17；LarkBridgeViewModel.kt:7-8；CockpitPanel.kt:44；LarkBridgeViewModelTest.kt:3）；验证：全仓 grep `easy_dev_toolbox.core.larkbridge` 零命中（排除 docs/reports 与归档）
- [x] 2.3 【同 commit 强制】`lark-bridge/.../BridgeAssemblerParityTest.kt:601`：`endsWith("core/larkbridge/LarkBridgeManager.kt")` → `endsWith("page/larkbridge/LarkBridgeManager.kt")`；验证：`./gradlew :lark-bridge:test --tests "*BridgeAssemblerParityTest*" --rerun-tasks --no-build-cache` 绿
- [x] 2.4 docs 路径同步 2 处：docs/pages/lark-bridge.md:21 分层图、docs/index.md:75 欠账条目；验证：docs grep `core/larkbridge` 零命中（排除 reports/归档）

## 3. docs 补充同步（shelltools 侧）

- [x] 3.1 docs/pages/shell-tools.md core 层配套表路径改 page/shelltools[/config]/（并说明文件物理在 feature-shelltools 模块）；docs/architecture.md 分层图相应行同步；验证：docs grep `core/shelltools` 仅剩 :core 真包（AdbCommandRewriter）相关行

## 4. 终验收口

- [x] 4.1 `./gradlew build --rerun-tasks --no-build-cache` 全绿；验证：BUILD SUCCESSFUL
- [x] 4.2 `git show --stat -M` 核对 rename 检测（25 文件移动应为 rename 而非 delete+add）；验证：rename 计数符合预期
- [x] 4.3 `openspec validate govern-split-packages`；tasks 全勾；归档
