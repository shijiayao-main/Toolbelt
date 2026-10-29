# shell-history-store Specification

## Purpose

定义 Shell 历史记录持久化存储的行为契约：历史条目数据模型、序列化格式、容量上限与 DataStore 读写规范。

## Requirements

### Requirement: 历史条目数据模型
系统 SHALL 使用 `ShellHistoryItem` sealed class 表示历史记录，包含以下子类型：
- `Normal`：普通命令，含 `id`、`displayLabel`（节点名）、`command`（实际命令）、`showOutput`
- `MultiSelect`：多选命令，含 `id`（格式 `nodeId:itemName`）、`displayLabel`（格式 "节点名 选项名"）、`executingKey`、`nodeName`（选项名）、`command`、`showOutput`
- `Input`：输入命令，含 `id`（解析后命令）、`displayLabel`（节点名）、`command`（解析后命令）、`showOutput`
- `Chain`：链式命令，含 `id`（nodeId）、`displayLabel`（节点名）、`commands`（命令列表）、`nodeId`、`showOutput`

#### Scenario: 唯一标识去重
- **WHEN** 同一 `id` 的历史条目被再次触发
- **THEN** 不新增条目，而是将已有条目移至列表最前

#### Scenario: Normal 类型 id 为命令字符串
- **WHEN** 执行 Normal 节点命令 `adb install app.apk`
- **THEN** 历史条目 id = `"adb install app.apk"`

#### Scenario: MultiSelect 类型 id 包含节点与选项
- **WHEN** 执行 MultiSelect 节点 `挡位切换` 的选项 `R`，executingKey = `gearNode:R`
- **THEN** 历史条目 id = `"gearNode:R"`

### Requirement: 历史记录上限为 30 条
系统 SHALL 保持历史列表最多 30 条记录。

#### Scenario: 超出上限时丢弃末尾
- **WHEN** 历史列表已有 30 条，新增第 31 条（非重复）
- **THEN** 末尾最旧一条被移除，新条目插入列表顶部，列表仍为 30 条

### Requirement: 历史数据持久化到 DataStore
系统 SHALL 将历史列表序列化为 JSON 字符串，存储到现有 `toolbelt` DataStore，key 为 `shell_command_history`。

#### Scenario: 应用启动时恢复历史
- **WHEN** ViewModel 初始化
- **THEN** 从 DataStore 读取历史 JSON，反序列化后填充 `_historyFlow`

#### Scenario: 正常写入防抖 1000ms
- **WHEN** 历史列表在 1000ms 内连续更新多次
- **THEN** DataStore 写入仅触发一次（最后一次变更后 1000ms）

#### Scenario: ViewModel 销毁时强制写入
- **WHEN** `ShellToolsViewModel.onCleared()` 被调用
- **THEN** 立即将当前历史列表写入 DataStore，不等待防抖计时器

### Requirement: EasyDataManager 扩展历史读写接口
`EasyDataManager` SHALL 新增 `getShellHistory(): String` 和 `setShellHistory(json: String)` 两个挂起函数，读写 key `shell_command_history`。

#### Scenario: 首次读取返回空字符串
- **WHEN** DataStore 中无历史数据
- **THEN** `getShellHistory()` 返回空字符串 `""`，反序列化结果为空列表
