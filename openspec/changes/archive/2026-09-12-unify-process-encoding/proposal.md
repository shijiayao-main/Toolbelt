# Proposal: unify-process-encoding

> ⚠️ 注：旧 `:lark-bridge` 已在 rewrite-lark-bridge 中移除，本 change 的成果由新模块承接（以下正文为历史记录，不改）。

## Why

跨平台审计确认：项目所有跨进程边界的字符编解码均依赖平台默认编码——Java 流 API（`InputStreamReader`/`bufferedReader()` 无参重载）在 JDK 17 打包态跟随系统 locale（Windows zh-CN 为 GBK），而链路对端（MCP stdio 的 claude、agent CLI 的 stdout、log-tools 输入文件）约定为 UTF-8。中文系统上表现为：permission server 收发 JSON-RPC 乱码、agent 回复乱码且 JSONL 解析失败、Shell 工具页中文输出乱码、log-tools 处理中文日志损坏内容、logback 日志文件编码不确定。

## What Changes

- MCP permission server 的 stdin/stdout 显式按 UTF-8 编解码（MCP 协议约定）。
- agent 子进程输出流显式按 UTF-8 解码（agent CLI stdout 约定，对齐 LarkCliProcess 现状）。
- Shell 工具链（SimpleShell / StreamingShell / InteractiveShell）子进程流编码平台化：Unix 显式 UTF-8；Windows 按 `native.encoding`（cmd.exe OEM/ANSI 代码页），读写同编码。
- permission server 启动脚本内容 ASCII 化（中文注释改英文）；Windows 平台脚本按 `native.encoding` 写出（中文用户名路径可被 cmd 正确解析）。
- log-tools 三个日志工具（LogSplitter / LogTagFilter / LogTimeFilter）的 FileReader/FileWriter 改为显式 UTF-8。
- logback 两个 PatternLayoutEncoder 显式指定 UTF-8。

## Capabilities

### Modified Capabilities

- `lark-bridge-module`：确认总线与 MCP permission server（stdio UTF-8 编解码、启动脚本内容与写出编码）。
- `simple-shell`：子进程输出解码编码。
- `interactive-shell`：stdin 写入编码与输出解码编码。
- `file-split-by-size`：输出文件编码。
- `log-tag-filter`：输出文件编码。

（StreamingShell 属 Shell 工具页执行引擎展示链路，其编码语义随 `interactive-shell`/`simple-shell` 的流编码条款一并约束，不单开 spec。）

## Impact

- **代码**：`lark-bridge`（McpPermissionServer、PermissionServerLauncher、AgentExecutor）、`composeApp`（ShellCommandBuilder、SimpleShell、StreamingShell、InteractiveShell、LogbackHelper）、`log-tools`（LogSplitter、LogTagFilter、LogTimeFilter）。
- **行为变化**：Unix 上从「平台默认（通常 UTF-8）」变显式 UTF-8，属行为修正；Windows 上 cmd.exe 子进程从「JDK17 打包态 GBK 碰巧可读」变显式 `native.encoding`，行为等价但不再依赖 JDK 版本（JDK18+ 打包时也不再乱码）。
- **已知取舍**：Windows 上 node 系工具输出 UTF-8 时按 native.encoding 解码会乱（现状 GBK 直读同样乱），文档化为已知限制，后续可加设置项。
- **不涉及**：文件名非法字符清洗（另行处理）、性能项。

## Impact 补充：不改变的行为

- 沙箱 env 白名单、cmd.exe 拉起包装等批次一内容不在本 change 范围。
- log-tools 输入源为 xlog/alog 解密产物（UTF-8 文本），显式 UTF-8 是行为修正而非格式迁移。
