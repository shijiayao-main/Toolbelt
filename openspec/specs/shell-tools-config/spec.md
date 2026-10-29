# shell-tools-config Specification

## Purpose

定义 Shell 工具配置体系的行为契约：JSON 配置的数据结构与解析规则、配置层与展示层的模型分离、三源加载管道（内置默认 → 用户主配置 → 自定义追加）以及节点标识稳定性，保障配置驱动下工具列表的正确加载与扩展。

## Requirements

### Requirement: JSON 配置数据结构

配置文件 SHALL 遵循 `docs/shell_tools.md` 定义的数据结构，采用树形组织。外层 `type` 为 0 表示父节点（可展开，包含子节点），1 表示子节点（可点击执行）。子节点的 `body.type` 区分执行模式：0=普通、1=多选、2=输入、3=链式。

#### Scenario: 父节点结构

- **GIVEN** JSON 中 type 为 0 的节点
- **THEN** 解析为可展开的父节点，name 为显示名称，body 为子节点数组

#### Scenario: 普通子节点结构

- **GIVEN** JSON 中 type 为 1 且 body.type 为 0 的节点
- **THEN** 解析为单命令节点，body.value 为命令模板，body.name 为按钮名称，body.desc 为描述

#### Scenario: 多选子节点结构

- **GIVEN** JSON 中 type 为 1 且 body.type 为 1 的节点
- **THEN** 解析为多按钮节点，body.list 中每项包含 name、value、desc

#### Scenario: 输入子节点结构

- **GIVEN** JSON 中 type 为 1 且 body.type 为 2 的节点
- **THEN** 解析为带参数的命令节点，body.value 为包含占位符的命令模板，body.list 中每项包含 name 和 desc

#### Scenario: 链式子节点结构

- **GIVEN** JSON 中 type 为 1 且 body.type 为 3 的节点
- **THEN** 解析为串行执行节点，body.list 为命令字符串数组

### Requirement: 配置层与展示层分离

配置解析 SHALL 产生两层数据模型：`ShellToolConfig`（与 JSON 1:1 映射）和 `ToolNode`（UI 展示模型）。UI 层只依赖展示模型，不直接引用配置模型。展示模型包含 UI 状态字段（id、isExpanded、isVisible），配置模型不包含这些字段。

#### Scenario: 配置变更不影响 UI 逻辑

- **GIVEN** 配置文件格式需要调整
- **WHEN** 仅修改 ConfigParser 的解析逻辑
- **THEN** UI 层代码无需修改，因为 UI 只依赖 ToolNode 展示模型

### Requirement: 配置文件加载

配置加载 SHALL 采用三源管道：内置默认配置（打包于应用资源内）→ 用户主配置 `~/.toolbelt/shell_tools.json` → 自定义配置 `~/.toolbelt/shell_tools_custom.json`。

- 主配置文件存在且解析成功时，SHALL 完整取代内置默认配置进行展示；应用 SHALL NOT 写入用户主配置文件（保持手工维护的只读语义）
- 主配置文件不存在时，SHALL 使用内置默认配置展示（新用户开箱可用，不再进入空状态）
- 主配置解析失败或为空白文件时，SHALL 回退内置默认配置并展示错误提示，不显示空白页面
- 自定义配置 SHALL 以独立分组追加展示，且持久化于自定义文件，不写入主配置文件

#### Scenario: 全新用户冷启动

- **WHEN** 用户目录不存在 `shell_tools.json` 与 `shell_tools_custom.json`
- **THEN** 列表展示内置默认配置的全部分组与工具，页面不出现错误提示，也不出现"未找到配置文件"空状态

#### Scenario: 主配置存在且合法

- **WHEN** 用户维护了合法的 `shell_tools.json`
- **THEN** 列表展示主配置的分组与工具（取代内置默认配置），自定义工具按自定义分组规则追加展示

#### Scenario: 主配置格式错误

- **WHEN** `shell_tools.json` 内容不是合法 JSON
- **THEN** 页面展示错误提示（如"配置文件解析失败"），同时列表回退展示内置默认配置，页面保持可用

#### Scenario: 主配置为空白文件

- **WHEN** `shell_tools.json` 存在但内容为空白
- **THEN** 与解析失败同等处理：展示错误提示并回退内置默认配置

#### Scenario: 仅存在自定义配置

- **WHEN** 主配置不存在而 `shell_tools_custom.json` 存在
- **THEN** 列表展示内置默认配置与自定义分组，自定义工具不丢失

### Requirement: 节点标识稳定性

解析产物 SHALL 为每个节点提供稳定标识（id）：内置默认配置与主配置节点的 id SHALL 由其在配置树中的路径确定性生成，同一配置内容重复加载产生相同 id；自定义工具节点的 id SHALL 来自其持久化的唯一标识。

#### Scenario: 同一配置重复加载

- **WHEN** 相同的配置内容被重复加载（如点击刷新按钮）
- **THEN** 各节点 id 与上次加载结果一致，分组展开状态与执行中节点的 loading 态不丢失

#### Scenario: 主配置节点 id 与自定义节点 id 不冲突

- **WHEN** 自定义工具与主配置工具同名或位于同名分组
- **THEN** 两者 id 不同，编辑、删除与执行互不影响
