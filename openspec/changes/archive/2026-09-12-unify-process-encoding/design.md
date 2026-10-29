# Design: unify-process-encoding

## Context

项目所有跨进程/跨文件边界的编解码均走平台默认编码。编码风险仅在「平台默认 ≠ UTF-8」的组合下暴露：JDK 17 打包 + Windows zh-CN（默认 GBK）。项目 lark-bridge/log-tools 的 jvmTarget 均为 11，composeApp 打包运行时为 JDK 17（jlink 裁剪），故 Windows 分发态是真实受影响面。

对端编码约定（不可协商）：
- MCP stdio：claude 按 MCP 协议以 UTF-8 收发 JSON-RPC。
- agent CLI stdout：UTF-8 JSONL。
- xlog/alog 解密产物：UTF-8 文本。
- cmd.exe 管道输出：控制台 OEM/ANSI 代码页（zh-CN = cp936），与 JVM 无关。

## Goals / Non-Goals

**Goals**
- 每个跨边界点显式声明编码，行为不随打包 JDK 版本（17 vs 18+，JEP 400）漂移。
- Windows cmd.exe 子进程按 `native.encoding` 读写（唯一平台例外）。
- 启动脚本内容 ASCII 化，使编码问题在脚本层消失。

**Non-Goals**
- 文件名非法字符的全项目统一清洗（log-tag-filter 输出名除外——spec 增量已含，实现随本 change 落地）。
- Windows 上 node 系工具 UTF-8 输出的自动探测（已知取舍，文档化）。
- 性能项。

## Decisions

### D1. 编码选择的单一真源
`ShellCommandBuilder`（composeApp）新增 `processCharset: Charset`（Windows → `Charset.forName(System.getProperty("native.encoding", "UTF-8"))`，Unix → UTF-8）；lark-bridge 侧 McpPermissionServer/AgentExecutor 直接用 `Charsets.UTF_8`（对端是 claude，协议约定，无平台差异）。两处语义不同是**有意的**：cmd.exe 子进程流是控制台代码页，MCP/agent 流是协议约定 UTF-8。

### D2. .bat 内容 ASCII 化 + native.encoding 写出
`windowsScript()` 的中文 rem 注释改英文（ASCII 化是主修复——内容 ASCII 后任何单字节代码页解析结果一致）；写出经 `scriptCharset()` 纯函数（Windows → native.encoding 缺省 UTF-8；Unix → UTF-8），Windows 分支 `writeBytes(toByteArray(scriptCharset()))`。中文用户名路径被 GBK 写出后恰被 cmd 的 GBK 解析读回。风险：非 GBK 的西欧 ANSI 代码页同样成立（各自 native.encoding）。

### D3. log-tools 显式 UTF-8 + 输出名清洗
FileReader/FileWriter → InputStreamReader/OutputStreamWriter 包 FileInputStream/FileOutputStream + UTF-8（12 处，机械替换）。LogTagFilter 输出名清洗函数与 DownloadPathPolicy 语义对齐（剔除 `<>:"|?*` 与路径分隔符、Windows 保留名追加 `_` 后缀），但本 change 只落 log-tag-filter 一处（DownloadPathPolicy 清洗缺口生产未接线，不在本 change 扩面）。

### D4. logback encoder 显式 charset
两处 `PatternLayoutEncoder` 加 `encoder.charset = Charsets.UTF_8`（logback 默认即 UTF-8，显式化是防御：防未来 logback 默认变更与读者误判）。

## Risk / Trade-offs

- **Windows cmd 子进程读 UTF-8 工具输出会乱**（node/python 输出 UTF-8 时按 GBK 解码）——与现状一致（现状 JDK17 GBK 直读同样乱），不引入回归；后续可加「输出编码」设置项。
- **log-tools 对历史 GBK 输出文件的兼容**——输入源是 UTF-8 解密产物，不存在历史 GBK 文件场景；新增中文往返特征测试锁行为。
- **InteractiveShell 写侧编码切换**：Windows 上从 UTF-8 写变 native.encoding 写，修复「中文命令被 GBK 进程误读」，不回归 ASCII 命令。
- **既有用例红**：先判定是被修复暴露的真实缺陷还是回归，不许跳过。

## Open Questions

无。
