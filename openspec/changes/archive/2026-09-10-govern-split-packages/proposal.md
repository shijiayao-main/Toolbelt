# Proposal — govern-split-packages

## Why

架构分析 P0-4 确认 split package 为唯一实质结构隐患：feature-shelltools 内部用 `com.jiaoay.easy_dev_toolbox.core.*` 包名（与 `:core` 模块同名包——core.data 横跨 3 模块、core.shelltools 横跨 2 模块），其中 ShellToolsDataStore 依赖"跨模块同包免 import"解析 `:core` 的 AppDataStore/LocalDataManager，属最危险形态；feature-larkbridge 的 core/larkbridge 属命名冒充。IDE 跳转/import 歧义，未来 JPMS 直接失败。

## What Changes

- **feature-shelltools（真 split 主体）**：core/data/ShellToolsDataStore.kt + core/shelltools/ 4 main 文件 → `page.shelltools` 根包（对齐 LogKitConfigStore/TimestampConfigStore 先例，同包互引 42 处零新增 import）；core/shelltools/config/ 5 文件 → `page.shelltools.config`；jvmTest 7 文件随主类同构移动；AdbCommandRewriterTest 挪 `:core` jvmTest（它测 :core 的类）；ShellToolsDataStore 补 2 个显式 import（AppDataStore/LocalDataManager）；feature 内 9 消费文件 + 3 jvmTest + composeApp 2 文件的 import 修正
- **feature-larkbridge（命名冒充）**：core/larkbridge/ 5 main + 3 jvmTest → `page.larkbridge` 根包整包平移（零名称冲突已验证）；4 消费文件 import 修正；**lark-bridge BridgeAssemblerParityTest:601 路径断言同步改（与文件移动同 commit 强制）**
- **docs 路径同步**：docs/pages/shell-tools.md、docs/architecture.md 分层图、docs/pages/lark-bridge.md:21、docs/index.md:75
- git mv（18 文件）由用户执行（本仓约定 AI 不做 git 操作），AI 在新路径上改 package 行与 import；严格"先 mv 后改内容"保 rename 检测

## Capabilities

（无——skip_specs: true。已验证 openspec/specs/ 对包路径 core.shelltools/core.larkbridge 零引用；ParityTest 路径修正属守护测试跟随现实，非 spec 级变更。）

## Impact

- 5 模块约 26 文件移动 + 约 16 文件 import 修正 + 4 docs
- internal 可见性（模块级）不受影响；无反射/ServiceLoader/META-INF 引用（已验证）；UiArchitectureGuardTest 无 relativePath 冲突（已验证）
- 验证：`--rerun-tasks --no-build-cache` 下 `:feature-shelltools:jvmTest :composeApp:jvmTest :core:jvmTest`（波次 1）与 `:lark-bridge:test :lark-gateway:test :feature-larkbridge:jvmTest :composeApp:jvmTest`（波次 2）+ 终验 `./gradlew build --rerun-tasks --no-build-cache`
