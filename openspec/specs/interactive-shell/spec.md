# interactive-shell Specification

## Purpose

定义 InteractiveShell 交互式进程抽象的行为契约：长存活子进程的启动、命令发送、Marker 结束判定与会话关闭，供交互式命令场景复用。

## Requirements

### Requirement: 启动交互式进程

InteractiveShell SHALL 提供 `start` 方法，接受启动命令（如 `"adb shell"`），创建并启动一个长期存活的子进程。

**子进程流编码（本变更新增）**：进程 stdin 写入与 stdout/stderr 读取 SHALL 使用同一显式编码——Unix 平台 UTF-8；Windows 平台按平台 ANSI 编码（`native.encoding`）。写读不同编码时中文命令会被子进程按错误代码页解析（如 UTF-8 字节被 GBK 进程读取），输出同理。

#### Scenario: 启动 adb shell

- **WHEN** 调用 `start("adb shell")`
- **THEN** 子进程被创建并运行，InteractiveShell 进入可交互状态

#### Scenario: 启动任意交互式命令

- **WHEN** 调用 `start("python3")` 或其他交互式命令
- **THEN** 对应子进程被创建并运行

#### Scenario: 中文命令与输出按同一编码读写（本变更新增）

- **WHEN** 向交互式进程发送含中文的命令行，且进程回显含中文
- **THEN** stdin 写入与 stdout/stderr 读取按同一平台编码（Unix UTF-8 / Windows native.encoding）处理，中文不乱码

### Requirement: 发送命令

InteractiveShell SHALL 提供 `sendCommand` 方法，向已启动的进程 stdin 写入一行文本。

#### Scenario: 发送单条命令

- **WHEN** 进程已启动，调用 `sendCommand("ls -la")`
- **THEN** 文本被写入进程 stdin 并 flush，进程执行该命令

#### Scenario: 进程未启动时发送命令

- **WHEN** 进程未启动或已关闭，调用 `sendCommand`
- **THEN** 操作失败或被忽略，不产生异常崩溃

### Requirement: 流式输出并区分来源
InteractiveShell SHALL 提供 `outputFlow: SharedFlow<ShellOutput>` 属性，按行推送进程输出。`ShellOutput` SHALL 包含 `line`（文本内容）和 `source`（STDOUT 或 STDERR 枚举）。

#### Scenario: 接收 stdout 输出
- **WHEN** 进程向 stdout 写入一行文本
- **THEN** outputFlow 发射一个 `ShellOutput(line=文本, source=STDOUT)`

#### Scenario: 接收 stderr 输出
- **WHEN** 进程向 stderr 写入一行文本
- **THEN** outputFlow 发射一个 `ShellOutput(line=文本, source=STDERR)`

### Requirement: 队列执行命令
InteractiveShell SHALL 提供 `executeQueue` 方法，接受命令列表，逐条自动执行。每条命令实际发送 `<cmd> && echo __DONE__`，在 stdout 中检测到 `__DONE__` 后再发送下一条。

#### Scenario: 队列自动执行
- **WHEN** 调用 `executeQueue(listOf("cd system", "ls", "cd data/Log"))`
- **THEN** 依次发送每条命令（附带 Marker），检测到 Marker 后发送下一条，直到队列为空

#### Scenario: 队列执行完后进程保持存活
- **WHEN** `executeQueue` 的所有命令执行完毕
- **THEN** 进程保持存活，可以继续调用 `sendCommand` 发送更多命令

### Requirement: 关闭会话
InteractiveShell SHALL 提供 `close` 方法，销毁子进程并释放所有资源（流、协程）。

#### Scenario: 主动关闭
- **WHEN** 调用 `close()`
- **THEN** 子进程被销毁，读取输出的协程被取消，资源被释放

#### Scenario: 重复关闭
- **WHEN** 已关闭后再次调用 `close()`
- **THEN** 不产生异常
