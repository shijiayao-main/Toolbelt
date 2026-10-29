# command-show-output-toggle Specification

## Purpose

为命令节点提供执行后输出展示行为的开关能力：通过节点级 showOutput 配置字段，决定命令执行时是跳转输出页面，还是留在列表页内以按钮 loading 状态静默执行，兼顾即时查看输出与后台执行两种使用场景。

## Requirements

### Requirement: 命令节点支持 showOutput 配置字段

命令节点配置（`ShellToolNodeConfig`）中 SHALL 支持可选的 `showOutput: Boolean` 字段，缺省值为 `false`。该字段控制命令执行后的交互行为：`true` 跳转输出页，`false` 在列表页内联展示 loading。

#### Scenario: 配置文件中未包含 showOutput 字段

- **WHEN** JSON 配置中某命令节点未设置 `showOutput` 字段
- **THEN** 系统 SHALL 将其视为 `showOutput = false`，即执行时不跳转输出页

#### Scenario: 配置文件中 showOutput 设置为 true

- **WHEN** JSON 配置中某命令节点设置 `showOutput: true`
- **THEN** 系统 SHALL 保持原有行为，点击后跳转至输出页面

### Requirement: showOutput=false 时按钮展示 loading 状态

当节点 `showOutput = false` 时，点击执行按钮后该按钮 SHALL 进入 loading 状态（展示 `CircularProgressIndicator`），并禁用点击，直到命令执行完毕后恢复正常。

#### Scenario: Normal 类型命令执行中

- **WHEN** 用户点击 `showOutput=false` 的 Normal 类型命令按钮
- **THEN** 按钮 SHALL 展示 loading 指示器，命令在后台执行，不切换页面

#### Scenario: Normal 类型命令执行完毕

- **WHEN** `showOutput=false` 的 Normal 类型命令执行结束（成功或失败）
- **THEN** 按钮 SHALL 恢复正常可点击状态，页面保持在列表页

#### Scenario: MultiSelect 类型某 item 执行中

- **WHEN** 用户点击 `showOutput=false` 的 MultiSelect 节点中的某个 item 按钮
- **THEN** 该 item 按钮 SHALL 展示 loading 状态，同节点内其他 item 按钮 SHALL 被禁用

#### Scenario: Input 类型命令执行中

- **WHEN** 用户在 `showOutput=false` 的 Input 节点弹窗中填写参数并点击执行
- **THEN** 弹窗关闭，对应按钮 SHALL 展示 loading 状态，命令在后台执行

#### Scenario: Chain 类型命令执行中（showOutput=false）

- **WHEN** 用户点击 `showOutput=false` 的 Chain 类型命令按钮
- **THEN** 按钮 SHALL 展示 loading 状态，链式命令在后台执行，不切换页面

### Requirement: Chain 类型 showOutput=false 失败时跳转输出页

Chain 类型命令设置 `showOutput = false` 时，若执行过程中任意步骤失败，系统 SHALL 自动跳转到输出页面，以便用户查看失败原因。

#### Scenario: Chain 命令某步骤失败

- **WHEN** `showOutput=false` 的 Chain 命令执行过程中某步骤失败或超时
- **THEN** 系统 SHALL 清除按钮 loading 状态，并自动跳转至输出页面展示失败详情

#### Scenario: Chain 命令全部成功

- **WHEN** `showOutput=false` 的 Chain 命令所有步骤执行成功
- **THEN** 按钮 SHALL 恢复正常状态，不跳转输出页

### Requirement: showOutput=true 时保持原有跳转行为

当节点 `showOutput = true` 时，系统 SHALL 完全保持现有行为，点击按钮后立即跳转至输出页面。

#### Scenario: showOutput=true 的命令执行

- **WHEN** 用户点击 `showOutput=true` 的任意类型命令按钮
- **THEN** 系统 SHALL 立即切换至输出页面，展示执行过程与结果
