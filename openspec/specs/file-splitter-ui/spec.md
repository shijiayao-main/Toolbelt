# file-splitter-ui Specification

## Purpose

定义文件分割工具页的界面行为契约：分割大小设置、文件拖入、任务进度展示与结果反馈的交互规范。

## Requirements

### Requirement: 文件分割大小设置
系统 SHALL 提供分割大小输入框，允许用户输入整数（单位 MB），最小值 MUST 为 1。系统 SHALL 通过 DataStore 持久化该设置，应用重启后自动恢复上次输入的值。

#### Scenario: 用户修改分割大小
- **WHEN** 用户在分割大小输入框中输入新值
- **THEN** 系统将新值持久化到 DataStore

#### Scenario: 应用启动恢复分割大小
- **WHEN** 文件分割页面首次加载
- **THEN** 系统从 DataStore 读取上次保存的分割大小并填入输入框；若未保存过，默认值为 10

#### Scenario: 输入无效值
- **WHEN** 用户输入小于 1 的值或非数字内容
- **THEN** 系统不允许执行分割操作

### Requirement: 文件拖拽输入
系统 SHALL 提供文件拖拽区域，仅接受 `.txt` 扩展名的文件，支持同时拖入多个文件。

#### Scenario: 拖入合法文件
- **WHEN** 用户将一个或多个 `.txt` 文件拖入拖拽区域
- **THEN** 系统为每个文件创建分割任务并开始执行

#### Scenario: 拖入非 txt 文件
- **WHEN** 用户拖入的文件不包含 `.txt` 扩展名
- **THEN** 系统忽略该文件，不创建任务

### Requirement: 文件分割任务执行
系统 SHALL 使用 `LogSplitter.splitBySize` 按用户设置的分割大小对拖入的文件执行分割操作，分割结果文件输出到源文件所在目录。

#### Scenario: 分割成功
- **WHEN** 分割操作成功完成
- **THEN** 任务状态显示为成功，展示首个输出文件路径，提供"打开目录"按钮

#### Scenario: 输出文件已存在
- **WHEN** 首个输出文件（`{name}_000.txt`）已存在
- **THEN** 任务状态显示为失败，展示错误信息"xxx 已存在，不需要再次进行处理"

#### Scenario: 分割过程中
- **WHEN** 文件正在执行分割操作
- **THEN** 任务状态显示为"分割中"

### Requirement: 任务列表管理
系统 SHALL 以列表形式展示所有分割任务，每个任务显示文件名和执行状态，支持清空任务列表。

#### Scenario: 查看任务列表
- **WHEN** 存在分割任务
- **THEN** 系统展示任务列表，显示任务数量和每个任务的文件名及状态

#### Scenario: 清空任务列表
- **WHEN** 用户点击清空按钮
- **THEN** 系统清除所有任务

#### Scenario: 打开输出目录
- **WHEN** 用户在成功的任务上点击"打开目录"
- **THEN** 系统使用系统文件管理器打开输出文件所在目录

### Requirement: 导航入口
系统 SHALL 在 NavigationRail 中新增"文件分割"导航项，点击后切换到文件分割页面。

#### Scenario: 导航到文件分割页面
- **WHEN** 用户在 NavigationRail 中点击"文件分割"项
- **THEN** 内容区显示文件分割工具页面
