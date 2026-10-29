# Design — closeout-legacy-and-docs-truth

## 核心取舍（含用户裁定记录）

### 1. 归档 git 策略：归档区整体入库（用户拍板）
- .gitignore:21-22 由 `openspec/changes/*` + `!openspec/changes/rewrite-lark-bridge/` 改为 `openspec/changes/*` + `!openspec/changes/archive/`（删旧白名单行——归档后该路径不复存在，死规则）
- 时序硬性：**先改 .gitignore，再走 /opsx:archive**。若先 mv 后改，5 个 tracked 文件显示 deleted、archive 新目录被忽略，git 状态脏
- 3 个历史归档 change（remove-bridge-dead-code / ui-last-mile-and-docs-truth / govern-split-packages）按"整体入库"语义一并 git add
- 归档 skill 会 warning 工件不完整（rewrite-lark-bridge 无 proposal/tasks/specs delta，openspec status existingOutputPaths 全空）——预期内，确认继续；不触发 spec 同步

### 2. 迁移目标包：host/（用户拍板迁移；包名选 host）
- 仓库既有术语直接对应：EasyDataManager 自述"宿主域"、AGENTS/docs 通篇"宿主域 key"；`host` 全仓零占用
- `app/` 与 App.kt/AppViewModel/composeApp 三重撞车，弃
- **LogFileRestoreInitializer 随迁**：core.initializer 是现存第二个 split package（:core 有 JInitializer/JInitializerManager/InitResult，composeApp 只有 LogFileRestoreInitializer 1 文件，靠跨模块同包免 import 解析 JInitializer——与 ShellToolsDataStore 同构的"最危险形态"），随迁一次清零两个 split package
- AppViewModel 根级包不动：:core 模块无 com.jiaoay.easy_dev_toolbox.core 根级物理包，不构成跨模块同名
- 补 import 防漏闸：LogFileRestoreInitializer 补 `core.initializer.JInitializer`；EasyDataManagerLogFileEnabledTest 补 LocalDataManager/FileUtils——漏补即编译红

### 3. 分档表对账（handler 实际 weight 真源）
| Handler | weight | 位置 |
|---|---|---|
| SystemHandler | LIGHT | BuiltinHandlers.kt:234 |
| StopHandler | URGENT | :327 |
| InteractionHandler | LIGHT | :377（设计裁定 URGENT 为笔误） |
| CommandHandler | HEAVY + weightFor 动态 | :445 |
| ReplyHandler | LIGHT | :546 |
| AgentHandler | HEAVY | :596 |
| FileHandler | HEAVY | :672 |
| PendingFileGateHandler | LIGHT | :835 |

SYSTEM 槽实际装配（BridgeAssembler.kt:305-315）= PendingFileGateHandler + SystemHandler + StopHandler 三件 CompositeHandler，gate 首位；Responder.kt:103 CompositeHandler.weight = children.first().weight → 继承 gate 的 LIGHT，行为一致，代码零改动。

### 4. 视觉验收：看图只在主会话（用户特别提醒）
- 多模态仅主会话的 mode-coding 保证；**严禁子 agent 看图**
- 截图三级降级：gdm-screenshot -f（未实测）→ GNOME D-Bus Screenshot 全屏（SelectArea 实测 AccessDenied，全屏未验证）→ 请用户手动截图
- AI 无法驱动 Wayland 点击；应用首屏取决于 last_page 持久化值，非设置页时请用户点侧栏协助（设计内人机协作点）
- 截图落 /tmp 不入库

### 5. 登记不修（避免范围膨胀）
- **UiArchitectureGuardTest 隔离规则 bug**：import 匹配的 `groupValues[1]` 捕获含 `com.jiaoay.easy_dev_toolbox.` 前缀的完整 FQN，后续 `startsWith("page.")`/`startsWith("AppViewModel")`/`startsWith("App.")` 恒 false——feature 隔离规则实际从不命中。修复会暴露 feature-logkit 跨域 import page.logkit.*（FileTaskViewModel 共享基座），连带处理留给独立 change。本轮仅在该测试方法 KDoc 加"已知不生效"登记行
- build/handoff-rewrite-lark-bridge.md、build/final-audit-docs-and-deltas.md 被 `**/build/` 忽略，仅本地
- shell-history-store spec 的 EasyDataManager 接口表述滞后现实（getShellHistory），登记不修
- fix-windows-usability（差 5.3）与 unify-process-encoding（差 4.3）均差 Windows 真机验证，**保持 active 不归档**

## 验证设计

- W2 后：`./gradlew :composeApp:jvmTest --rerun-tasks --no-build-cache` + grep 闸（composeApp 内 `package com.jiaoay.easy_dev_toolbox.core` 仅剩 AppViewModel.kt 一处）
- W3 后：`:lark-bridge:compileKotlin` + grep 三分档表含 PendingFileGateHandler
- W4 后：grep 无私有 SwitchRow 残留、无 changes/rewrite-lark-bridge 活引用（排除 archive）
- 终验：`./gradlew build --rerun-tasks --no-build-cache` 全绿；`git ls-files openspec/changes/archive` 计数 ≥ 5 + 3 归档 change 工件；openspec list 清零 active 归档目标
