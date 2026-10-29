# shell-history-panel Specification

## Purpose

定义 Shell 工具页右侧历史记录面板的行为契约：左右分栏布局、历史条目展示与点击重执行、执行时自动更新历史（含批量执行），以及单条删除与清空全部的管理能力。

## Requirements

### Requirement: ShellToolsContent 布局改为左右分栏

`ShellToolsContent` SHALL 使用 `Row` 将页面分为左右两栏：左侧占 `weight(1f)` 展示原有配置命令区域，右侧占固定宽度（建议 280.dp）展示历史记录面板，两栏之间有 `VerticalDivider` 分隔。

#### Scenario: 左侧保留完整原有功能

- **WHEN** 用户打开 ShellTools 页面
- **THEN** 左侧显示标题栏、设备状态栏、搜索框、工具列表，交互行为与改造前完全一致

#### Scenario: 右侧显示历史面板

- **WHEN** 用户打开 ShellTools 页面
- **THEN** 右侧显示 `ShellHistoryPanel`，包含"历史记录"标题和历史条目列表

### Requirement: 历史面板展示条目

`ShellHistoryPanel` SHALL 使用 `LazyColumn` 滚动展示历史列表，最新条目在顶部。

#### Scenario: Normal / MultiSelect / Input 类型显示两行普通样式

- **WHEN** 历史列表包含 Normal、MultiSelect 或 Input 类型条目
- **THEN** 每条显示为可点击卡片，第一行为 `displayLabel`（bodyMedium），第二行为 `command`（labelSmall，onSurfaceVariant 颜色）

#### Scenario: Chain 类型保留链式执行样式

- **WHEN** 历史列表包含 Chain 类型条目
- **THEN** 显示为与 `ChainChildNode` 相似的样式（tertiary 颜色，PlaylistPlay 图标，"链式执行 · N步" 副标题）

#### Scenario: 历史为空时显示占位提示

- **WHEN** 历史列表为空
- **THEN** 显示居中灰色文字"暂无历史记录"

### Requirement: 点击历史条目重新执行命令

点击历史面板中的任意条目 SHALL 触发对应命令的重新执行，执行逻辑与原始节点点击完全一致。

#### Scenario: 点击 Normal 历史条目执行命令

- **WHEN** 用户点击 Normal 类型历史条目
- **THEN** 调用 `viewModel.executeNormal(...)` 并传入对应参数

#### Scenario: 点击 MultiSelect 历史条目执行命令

- **WHEN** 用户点击 MultiSelect 类型历史条目
- **THEN** 调用 `viewModel.executeMultiSelectItem(...)` 并传入对应参数

#### Scenario: 点击 Chain 历史条目执行命令

- **WHEN** 用户点击 Chain 类型历史条目
- **THEN** 调用 `viewModel.executeChain(...)` 并传入对应参数

### Requirement: 执行时自动更新历史记录

所有命令执行入口（列表节点点击、手动输入、历史回放、多选批量执行）SHALL 在触发执行时更新历史记录：同 id 条目去重移位并累计使用次数，不产生重复条目。

#### Scenario: 执行时历史列表立即更新

- **WHEN** 用户点击任意命令节点触发执行
- **THEN** 历史面板立即更新，新条目显示在列表顶部

#### Scenario: 批量执行写入历史

- **GIVEN** 历史面板多选模式下选中 3 条已有历史条目
- **WHEN** 用户点击"串行执行选中项"
- **THEN** 3 个选中条目均按其原有 id 去重合并更新（使用次数加一、最近使用时间刷新、移至队首），不新增重复条目，且该更新随既有防抖机制持久化

### Requirement: 单条删除历史记录

历史面板 SHALL 支持删除单条历史记录：非多选模式下，鼠标悬停在历史条目上时显示删除动作，点击后该条目立即删除（单条删除无需确认）。删除立即反映在列表中，并经既有防抖持久化机制写入 DataStore。

#### Scenario: 悬停显示删除入口

- **WHEN** 用户将鼠标悬停在非多选模式下的历史条目上
- **THEN** 该条目显示删除动作按钮

#### Scenario: 删除单条立即生效

- **GIVEN** 历史列表包含 5 条记录
- **WHEN** 用户点击其中 1 条的删除按钮
- **THEN** 该条目立即从列表消失，其余 4 条及其顺序保持不变

#### Scenario: 删除后持久化

- **WHEN** 用户删除某条历史记录后重启应用
- **THEN** 该条目不再出现在历史列表中

#### Scenario: 多选模式下不显示删除入口

- **WHEN** 历史面板处于多选批量执行模式
- **THEN** 条目不显示单条删除按钮，避免与勾选操作冲突

### Requirement: 清空全部历史记录

历史面板 SHALL 提供"清空全部"入口，点击后弹出确认对话框；用户确认后清空全部历史记录并持久化，取消则列表保持原样。历史为空时该入口处于禁用状态。

#### Scenario: 确认清空

- **GIVEN** 历史列表包含若干条记录
- **WHEN** 用户点击"清空全部"并在确认对话框中点击确认
- **THEN** 全部历史条目被移除，面板显示"暂无历史记录"空状态占位

#### Scenario: 取消清空

- **GIVEN** 历史列表包含若干条记录
- **WHEN** 用户点击"清空全部"并在确认对话框中点击取消
- **THEN** 历史列表保持原样，不发生任何删除

#### Scenario: 清空后持久化

- **WHEN** 用户确认清空全部历史后重启应用
- **THEN** 历史列表仍为空

#### Scenario: 历史为空时清空入口禁用

- **WHEN** 历史列表为空
- **THEN** "清空全部"入口处于禁用状态
