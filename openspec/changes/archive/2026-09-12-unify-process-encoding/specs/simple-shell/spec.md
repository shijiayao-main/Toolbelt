# Delta: simple-shell

## MODIFIED Requirements

### Requirement: 执行单条命令并返回完整结果

SimpleShell SHALL 接受一条命令（字符串数组形式），执行该命令，等待其完成，并返回包含 exit code、stdout 文本和 stderr 文本的结果对象。

**子进程流编码（本变更新增）**：子进程 stdout/stderr SHALL 按显式编码解码——Unix 平台按 UTF-8；Windows 平台按平台 ANSI 编码（`native.encoding`，`cmd.exe /c` 包装的内置命令与原生素工具经管道输出为控制台代码页，显式同编码避免依赖 JDK 打包版本；代价是输出 UTF-8 的工具在 Windows 上可能乱码，属已知取舍）。

#### Scenario: 命令执行成功

- **WHEN** 调用 `execute` 传入合法命令（如 `["ls", "-la"]`）
- **THEN** 返回 `Result` 成功值，包含 exit code 为 0、stdout 为命令输出文本、stderr 为空字符串

#### Scenario: 命令执行失败（非零退出码）

- **WHEN** 调用 `execute` 传入会失败的命令（如 `["ls", "/nonexistent"]`）
- **THEN** 返回 `Result` 成功值，包含非零 exit code、stdout 可能为空、stderr 包含错误信息

#### Scenario: 命令不存在

- **WHEN** 调用 `execute` 传入不存在的命令（如 `["nonexistent_cmd"]`）
- **THEN** 返回 `Result` 失败值，包含对应异常

#### Scenario: 中文输出按平台编码正确解码（本变更新增）

- **WHEN** 命令输出含非 ASCII 字符（Unix 下 UTF-8 输出；Windows 下 cmd 内置命令按控制台代码页输出）
- **THEN** stdout 按对应平台编码解码，中文字符不乱码，且行为不随打包 JDK 版本（17 与 18+）变化
