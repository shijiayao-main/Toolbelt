# Tasks: unify-process-encoding

## 1. lark-bridge：MCP stdio 与 agent 输出（B01/B05/B02）

- [x] 1.1 `McpPermissionServer` stdin/stdout 构造默认值显式 `Charsets.UTF_8`。验证：编译通过，`McpPermissionServerRiskGateTest` 全绿
- [x] 1.2 `AgentExecutor` 输出 drain 流显式 `bufferedReader(Charsets.UTF_8)`。验证：编译通过，`AgentExecutorTest` 全绿
- [x] 1.3 `PermissionServerLauncher.windowsScript()` 中文 rem 注释改 ASCII；新增 `scriptCharset(windows: Boolean)` 纯函数（Windows → native.encoding 缺省 UTF-8）；Windows 写出改 `writeBytes`。验证：`PermissionServerLauncherTest` 全绿
- [x] 1.4 `PermissionServerLauncherTest` 补断言：脚本内容纯 ASCII、scriptCharset 语义。验证：测试全绿

## 2. composeApp：Shell 工具链编码（B03）+ logback（C04）

- [x] 2.1 `ShellCommandBuilder` 新增 `processCharset`（Windows → native.encoding，Unix → UTF-8）。验证：编译通过
- [x] 2.2 `SimpleShell`/`StreamingShell`/`InteractiveShell` 读侧 `bufferedReader(processCharset)`、写侧 `bufferedWriter(processCharset)`（共 7 处）。验证：现有 Shell 测试全绿
- [x] 2.3 `LogbackHelper` 两个 `PatternLayoutEncoder` 加 `encoder.charset = Charsets.UTF_8`。验证：编译通过，日志文件为 UTF-8

## 3. log-tools：显式 UTF-8 + 输出名清洗（C01）

- [x] 3.1 `LogSplitter`/`LogTagFilter`/`LogTimeFilter` 共 12 处 FileReader/FileWriter 改显式 UTF-8 流。验证：`grep -rn "FileReader\|FileWriter" log-tools/src/main` 0 命中
- [x] 3.2 `LogTagFilter` 输出文件名清洗（非法字符 + Windows 保留名）。验证：新增单测覆盖非法字符与保留名
- [x] 3.3 新增中文读写往返特征测试（写含中文临时文件 → 工具处理 → 读回逐行一致）。验证：`log-tools:test` 全绿

## 4. 批次验收

- [x] 4.1 全量测试 `./gradlew :lark-bridge:test :composeApp:jvmTest :log-tools:test` 全绿。验证：BUILD SUCCESSFUL
- [x] 4.2 `openspec validate unify-process-encoding` 通过
- [x] 4.3 人工验证项（2026-09-12 维护者确认已验证）：中文用户名下 permission server 自检 Ok、Shell 工具页中文输出不乱码、log-tools 处理中文日志输出正常
