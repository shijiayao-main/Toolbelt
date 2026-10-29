# Design — govern-split-packages

## 核心取舍

### 1. 目标包映射（先例对齐 + 最小 diff）

| 现位置 | 目标 | 理由 |
|---|---|---|
| core/data/ShellToolsDataStore.kt | `page.shelltools` 根包 | 与 LogKitConfigStore（page.logkit）/TimestampConfigStore（page.timestamp）先例完全一致（域配置仓库平铺根包） |
| core/shelltools/ 4 main（ShellExecModels/ShellExecutionEngine/ShellToolsConfigRepository/ShellToolsRuntime） | `page.shelltools` 根包 | 消费方全在 page.shelltools 树内；整包平移使同包互引（Engine↔ExecModels↔Runtime 42 处）零新增 import |
| core/shelltools/config/ 5 文件 | `page.shelltools.config` | 配置模型层职责独立；保留子包使 30+ 处 import 只改前缀 `core→page`，diff 最小 |
| core/larkbridge/ 5 main + 3 test | `page.larkbridge` 根包整包平移 | 7 顶层声明与根包现有声明零冲突（已验证）；包内互引免 import 全保；不发明 page.*.core 新层级 |
| AdbCommandRewriterTest | `:core` jvmTest `core/shelltools/` | 测的是 :core 的 AdbCommandRewriter（靠同包免 import），"测谁归谁"；文件仅 kotlin.test 两个 import，零内容修改 |

不推荐的备选（page.shelltools.engine / page.larkbridge.core 子包）：无先例，且会把包内互引从免 import 变显式 import，改动面增大。

### 2. git mv 分工（尊重约定）

- **用户执行 git mv**（18 文件，清单见 tasks.md）；**AI 在新路径改 package 行与 import**。
- 严格"先 mv 后改内容"：rename 检测基于内容相似度（阈值 50%），moved+edited 后相似度 >95% 稳过；若先改内容再 mv 有跌破阈值风险。
- 不用"AI 新建+删旧"替代：520 行的 ShellToolsConfigRepository 手抄转录有走样风险。

### 3. 最危险的坑与防漏闸

- **ShellToolsDataStore 现在零 import**——靠"跨模块同包"（与 :core 的 core.data 同名）免 import 引用 AppDataStore/LocalDataManager。迁到 page.shelltools 后必须补 2 个显式 import，漏补即编译红（编译器兜底）；同时验收 grep `PreferenceDataStoreFactory` 在 feature-shelltools 必须零命中（防有人顺手新建实例破坏"进程唯一实例"纪律）。
- **BridgeAssemblerParityTest:601**：`sources.any { it.first.endsWith("core/larkbridge/LarkBridgeManager.kt") && ... }` 硬编码路径——LarkBridgeManager 移动与该断言修改**必须同 commit**，否则中间提交必红；且该测试扫盘型，必须 `--rerun-tasks --no-build-cache` 验证防 build cache 假绿。
- **ShellToolsViewModel.kt:12 的 AdbCommandRewriter import 保留**——那是 :core 真包的类，:core 不动。
- config 5 文件迁入子包后需补父包（page.shelltools）类 import；ShellToolsConfigRepository 的 6 个 config.* import 改前缀。
- internal 可见性是模块级，包迁移不影响（ShellToolsDataStore 的 internal var store 测试接缝、ShellExecutionEngine 的 2 个 internal const 均同模块消费，已验证）。

### 4. 施工顺序（考虑在途改动）

1. **先 shelltools**（真 split 主体、施工面自包含、不牵连 lib 模块守护测试）；可与 change-1 波次 1 同期。
2. **后 larkbridge**（跨模块 ParityTest 同步 + lark-bridge 在途 13 文件未落地时有干扰风险）——**硬前置 W0**（用户先提交在途改动）。

### 5. git mv 前的确认清单（用户执行时）

- 工作区 remove-bridge-dead-code 的 13 文件已提交（larkbridge 波次前置）
- mv 后 `git status` 应显示 rename（R100 或高相似度），出现 delete+add 成对即说明顺序或时序出错，停下来排查

## 验证设计

- 波次 1（shelltools）：`./gradlew :feature-shelltools:jvmTest :composeApp:jvmTest :core:jvmTest --rerun-tasks --no-build-cache`
- 波次 2（larkbridge）：`./gradlew :lark-bridge:test :lark-gateway:test :feature-larkbridge:jvmTest :composeApp:jvmTest --rerun-tasks --no-build-cache`
- 终验：`./gradlew build --rerun-tasks --no-build-cache`；`git show --stat -M` 核对 rename 计数；全仓 grep `easy_dev_toolbox.core.shelltools` 仅剩 :core AdbCommandRewriter 一处、`easy_dev_toolbox.core.larkbridge` 零命中
- docs 路径同步 4 处：docs/pages/shell-tools.md（core 层配套表）、docs/architecture.md 分层图、docs/pages/lark-bridge.md:21、docs/index.md:75
