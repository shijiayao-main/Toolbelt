# Delta: interactive-shell

## MODIFIED Requirements

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
