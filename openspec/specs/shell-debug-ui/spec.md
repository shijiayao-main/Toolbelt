# shell-debug-ui Specification

## Purpose

定义 Shell 调试页的界面行为契约：IM 聊天风格的消息展示、命令输入与发送、会话状态与消息滚动的交互规范。

## Requirements

### Requirement: IM 聊天风格消息展示
调试页 SHALL 使用 LazyColumn 展示聊天消息列表，底部为输入框和发送按钮，上方为消息区域。用户输入的命令 SHALL 作为用户消息上屏，命令执行的输出 SHALL 逐行追加为系统消息。每条消息最长为屏幕宽度，自动换行。

#### Scenario: 用户输入上屏
- **WHEN** 用户输入命令并点击发送
- **THEN** 命令文本作为用户消息出现在聊天列表中

#### Scenario: 输出逐行追加
- **WHEN** 命令执行产生输出
- **THEN** 每行输出作为独立的系统消息追加到聊天列表，标记 STDOUT 或 STDERR 来源

#### Scenario: 消息自动换行
- **WHEN** 输出内容超过屏幕宽度
- **THEN** 消息文本自动换行显示

### Requirement: SimpleShell 和 InteractiveShell 模式切换
调试页 SHALL 提供开关控件切换 SimpleShell 和 InteractiveShell 模式。切换时各自的聊天记录独立保留。

#### Scenario: 正常切换
- **WHEN** 当前无活跃 InteractiveShell 会话，用户点击切换开关
- **THEN** 模式切换成功，显示目标模式的聊天记录

#### Scenario: 有活跃会话时屏蔽切换
- **WHEN** InteractiveShell 模式下有活跃会话，用户点击切换开关
- **THEN** 切换被屏蔽，提示用户先关闭会话

### Requirement: SimpleShell 模式调试
在 SimpleShell 模式下，用户输入命令并发送后，系统 SHALL 调用 `SimpleShell.execute` 执行命令，将 exitCode、stdout、stderr 作为输出消息展示。

#### Scenario: 执行命令并展示结果
- **WHEN** 用户在 SimpleShell 模式输入 `ls -la` 并发送
- **THEN** 调用 `SimpleShell.execute("ls", "-la")`，stdout 和 stderr 逐行作为 OUTPUT 消息展示，exitCode 作为最后一条消息展示

#### Scenario: 执行期间按钮置灰
- **WHEN** SimpleShell 命令正在执行中
- **THEN** 发送按钮置灰不可点击，直到 `execute` 返回

### Requirement: InteractiveShell 模式调试
在 InteractiveShell 模式下，用户 SHALL 先通过启动命令输入框和启动按钮启动会话，会话启动后才能发送命令。发送命令时系统 SHALL 自动追加 `&& echo __DONE__`，通过检测 Marker 判断输出结束。

#### Scenario: 启动会话
- **WHEN** 用户输入启动命令（如 `adb shell`）并点击启动按钮
- **THEN** InteractiveShell 启动对应进程，发送按钮从置灰变为可用

#### Scenario: 关闭会话
- **WHEN** 用户点击关闭按钮
- **THEN** InteractiveShell 调用 close()，会话结束，发送按钮置灰

#### Scenario: 发送命令并等待 Marker
- **WHEN** 会话已启动，用户输入 `ls` 并发送
- **THEN** 实际发送 `ls && echo __DONE__`，输出逐行推送到聊天列表，检测到 `__DONE__` 后发送按钮恢复可用

#### Scenario: 输出等待期间按钮置灰
- **WHEN** InteractiveShell 命令已发送，Marker 尚未返回
- **THEN** 发送按钮置灰不可点击
