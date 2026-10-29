# Tasks — ui-last-mile-and-docs-truth

## 1. AppSwitchRow 增强（:ui）

- [x] 1.1 `ui/.../widget/AppSwitchRow.kt`：新增 `hoverable/hoverShape/labelStyle` 三参（签名见 design.md），`hoverable=true` 时经 `interactions/HoverableRow` 渲染（背景挂 modifier 链外层、shape 对齐、AppMotion.fast + AppAlpha.bgSubtle）；重写 KDoc（修正"声称收敛 HoverableRow 但未实现"的不一致，说明新参语义与默认形态）；验证：`./gradlew :ui:compileKotlin :composeApp:compileKotlin`
- [x] 1.2 既有消费方零回归：RouteRuleEditor×6、ConfigFieldRenderer:125 不传新参渲染不变；验证：`./gradlew :feature-larkbridge:jvmTest --rerun-tasks --no-build-cache` 全绿（含桥接页零白名单守护）

## 2. ShellHistoryPanel 双替换（feature-shelltools）

- [x] 2.1 手写空态（:109-129）→ `EmptyStateView(text = "暂无历史记录", icon = Icons.Outlined.History)`；删 AppAlpha import（确认文件内无其他使用后）；验证：该文件 grep `AppAlpha` 零命中；`:feature-shelltools:compileKotlin` 绿
- [x] 2.2 清空历史确认框（:151-167）→ `AppConfirmDialog(title="清空历史记录", text=<原文案含插值>, isDangerous=true, confirmLabel="清空", onConfirm={clearAllHistory(); 收起}, onDismiss={收起})`；删 AlertDialog import（确认 SelectionActionBar 等他处不再用）；验证：文件 grep `AlertDialog` 零命中；编译绿
- [x] 2.3 shell-history-panel spec 行为不变：对照 `openspec/specs/shell-history-panel/spec.md` 清空需确认对话框、空历史禁用入口场景逐条成立；验证：阅读核对 + 测试绿

## 3. 两个删除确认框替换（feature-shelltools）

- [x] 3.1 `ShellToolsParentNode.kt:217-235` 删除分组确认框 → AppConfirmDialog(isDangerous=true, confirmLabel="删除"，title/text 原文案平移含插值)；验证：编译绿
- [x] 3.2 `ShellToolsChildNode.kt:190-205` 删除自定义命令确认框 → 同构替换；验证：编译绿
- [x] 3.3 守护白名单不增减：`./gradlew :composeApp:jvmTest --tests "*UiArchitectureGuardTest*" --rerun-tasks --no-build-cache` 绿

## 4. PageSettings SwitchRow 迁移（composeApp）

- [x] 4.1 删私有 SwitchRow（:226-249）与 material3.Switch import（确认 ThemeOptionItem 不用 Switch）；两调用点（:153/:171）→ AppSwitchRow(hoverable=true, hoverShape=AppShapes.medium, labelStyle=bodyLarge, modifier=fillMaxWidth().clip(AppShapes.medium).padding(16/10))；验证：文件 grep 裸 `Switch(` 零命中；`:composeApp:compileKotlin` 绿
- [x] 4.2 视觉人工验收：`./gradlew :composeApp:run` 过设置页——hover 渐入正常、行高字号与迁移前一致；已知微差（热区缩 10dp、无 ripple）记录确认；验证：人工确认

## 5. P0-5 docs 修正

- [x] 5.1 `docs/architecture.md:119`：持久化约定改双轨表述（宿主域 EasyDataManager / feature 域各自 ConfigStore / 全部经 :core AppDataStore 进程唯一实例 / 桥接域 JSON 例外指向 :126 节）；验证：与 AGENTS.md 约定 2、EasyDataManager KDoc 三方口径一致
- [x] 5.2 `docs/core/data.md`：文件总览行修正 + 补 AppDataStore.kt；配置表 6 行归属改注（xlog_private_key/file_split_size_mb→LogKitConfigStore；shell_command_history/shell_history_sort_mode/shell_output_panel_height_dp/shell_output_panel_expanded/adb_executable_path→ShellToolsDataStore）+ 补 4 缺失 key（custom_seed_color/log_file_enabled→宿主；log_tag_regex/log_tag_name→LogKit；timestamp_format→Timestamp）；:64-67 新增步骤双轨化；验证：与三个 ConfigStore 源码逐 key 对照
- [x] 5.3 `AGENTS.md:84`：括号断言改为"log-tools CLI 全仓唯此处有；lark-bridge CLI 见 docs/pages/lark-bridge.md「独立 CLI」节"；验证：文档评审

## 6. lark-bridge 注释修正（🔴 硬前置：用户提交在途 13 文件）

- [x] 6.1 `pipeline/MessageHandler.kt:16-17`：URGENT 行删 InteractionHandler、LIGHT 行补入；验证：grep 该文件 URGENT 行无 InteractionHandler
- [x] 6.2 `pipeline/Admission.kt` 分档表同款修正（grep URGENT 定位，行号已漂移）；验证：同上
- [x] 6.3 `observe/ObserveStatusTest.kt:1-4`：删"BC-341..BC-352（状态面）"引用，保留 D18/D19/D20 表述；验证：`./gradlew :lark-bridge:test --tests "*ObserveStatus*" --rerun-tasks --no-build-cache` 绿

## 7. Bridge.kt draining 死写清理（🔴 同上依赖 W0）

- [x] 7.1 删 Bridge.kt :143-144（字段+KDoc）、:306、:310（两行 set）；验证：该文件 grep `draining` 零命中；`./gradlew :lark-bridge:test :feature-larkbridge:jvmTest --rerun-tasks --no-build-cache` 绿

## 8. change 收口

- [ ] 8.1 `openspec validate ui-last-mile-and-docs-truth` 通过；tasks 全勾；归档
