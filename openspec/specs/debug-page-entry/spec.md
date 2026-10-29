# debug-page-entry Specification

## Purpose

定义 Shell 调试页的隐藏入口行为契约：通过设置按钮三击手势唤起调试页，避免调试能力对普通用户暴露。

## Requirements

### Requirement: 三击设置按钮唤起调试页
用户在 500ms 内连续点击设置按钮 3 次时，系统 SHALL 打开 Shell 调试页面。非三击的点击 SHALL 正常切换到设置页。

#### Scenario: 三击触发
- **WHEN** 用户在 500ms 内连续点击设置按钮 3 次
- **THEN** 打开 Shell 调试页面，不切换到设置页

#### Scenario: 普通点击
- **WHEN** 用户单击或双击设置按钮（未在 500ms 内达到 3 次）
- **THEN** 正常切换到设置页面，不打开调试页

### Requirement: 调试页不持久化到 last page
调试页的打开状态 SHALL 使用独立的 Boolean 状态管理，不通过 `setCurrentPage` 持久化。关闭调试页后 SHALL 恢复到之前的页面。

#### Scenario: 打开调试页不影响持久化
- **WHEN** 当前在 XLogDecoder 页面，用户三击设置按钮打开调试页
- **THEN** 持久化存储中的 last page 仍为 XLogDecoder

#### Scenario: 关闭调试页恢复原页面
- **WHEN** 用户关闭调试页
- **THEN** 页面恢复到打开调试页之前的页面

#### Scenario: 重启应用不恢复调试页
- **WHEN** 调试页打开时应用退出并重新启动
- **THEN** 应用恢复到持久化的 last page，不显示调试页
