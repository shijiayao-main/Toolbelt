# Proposal — ui-last-mile-and-docs-truth

## Why

架构分析 P0-3 确认：`:ui` 收敛组件建好后"最后一公里"未走完——AppConfirmDialog 全仓零消费、EmptyStateView/AppSwitchRow 建立后旧手写未替换，属于"抽象存在但被绕过"的迁移欠账。P0-5 确认：docs 三处失真（配置归属表 6 行错误 + 缺 4 key）会误导新贡献者，lark-bridge 两处 InteractionHandler 分档表仍列已裁定为笔误的 URGENT。另有两项 reviewer 登记清理：Bridge.kt `draining` 标志删除 drainAwareConnection 后只写不读；ObserveStatusTest 头注释引用了错误的 BC 范围。

## What Changes

- **AppSwitchRow 增强**（`ui/.../widget/AppSwitchRow.kt`）：新增 `hoverable: Boolean = false`（经 `widget/interactions/HoverableRow` 提供 hover 背景渐入）、`hoverShape: Shape = RectangleShape`、`labelStyle: TextStyle = bodyMedium` 三参，全部默认值 = 现渲染路径，既有消费方（RouteRuleEditor×6、ConfigFieldRenderer:125）零改动零视觉变化；重写 KDoc（修正"声称收敛 HoverableRow 形态但实现没做"的不一致）
- **5 处手写替换**（用户已确认把范围外第 3 个确认框纳入）：
  - ShellHistoryPanel.kt:109-129 手写空态 → EmptyStateView
  - ShellHistoryPanel.kt:151-167 清空历史确认框 → AppConfirmDialog(isDangerous=true)
  - ShellToolsParentNode.kt:217-235 删除分组确认框 → AppConfirmDialog(isDangerous=true)
  - ShellToolsChildNode.kt:190-205 删除自定义命令确认框 → AppConfirmDialog(isDangerous=true)
  - PageSettings.kt:226-249 私有 SwitchRow 删除，两调用点 → AppSwitchRow(hoverable=true, hoverShape=AppShapes.medium, labelStyle=bodyLarge)
- **docs 修正**：architecture.md:119 持久化约定改双轨表述；data.md 文件总览/配置表 6 行归属/补 4 缺失 key/新增步骤双轨化；AGENTS.md:84 CLI 断言拆分
- **lark-bridge 注释**：MessageHandler.kt:16-17 与 Admission.kt:181-182 分档表 URGENT→LIGHT（依据 BuiltinHandlers.kt:360/377 设计裁定）；ObserveStatusTest.kt:1-4 删错误 BC-341..352 引用保留 D18/D19/D20
- **清理**：Bridge.kt:143-144/306/310 draining 字段+KDoc+两行 set 删除（全仓零读取已验证）
- 已知接受微差：PageSettings 开关行点击热区上下各缩 10dp、无 ripple（hover 背景为主反馈）

## Capabilities

（无——skip_specs: true。组件替换不改 shell-history-panel spec 约束的行为（清空需确认对话框不变）；docs 与注释修正非 spec 级；已验证 openspec/specs/ 对 AppSwitchRow/AppConfirmDialog/EmptyStateView 及分档表零引用。）

## Impact

- `:ui` 1 文件、feature-shelltools 3 文件、composeApp 1 文件、lark-bridge 4 文件、docs 3 文件、AGENTS.md
- AppSwitchRow 增强为向后兼容扩展（新参全带默认值）；守护测试零阻塞（替换不产生新违规、不改白名单；分档表注释经剥注释扫描机制零影响）
- 验证：`--rerun-tasks --no-build-cache` 下 `:composeApp:jvmTest`（UiArchitectureGuardTest）、`:feature-shelltools:jvmTest`、`:feature-larkbridge:jvmTest`（桥接页零白名单态势）、`:lark-bridge:test`
