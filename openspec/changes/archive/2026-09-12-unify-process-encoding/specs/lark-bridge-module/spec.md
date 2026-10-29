# Delta: lark-bridge-module

## MODIFIED Requirements

### Requirement: 确认总线与 MCP permission server

系统 SHALL 提供本地 TCP 确认总线（127.0.0.1 随机端口，JSON 行协议）：stdio MCP server（独立短命进程，由 claude 经 `--permission-prompt-tool` 按需拉起）把权限请求转发到总线并同步等待裁决响应。MCP server SHALL 实现 MCP 协议的 initialize/tools-list/tools-call 方法（工具名 `permission_prompt`），把 tool_name + input 透传为确认请求内容；MCP server 的 chatId/senderOpenId SHALL 优先从 CLI `--chat`/`--sender` 取（由 provider 模板替换占位符后传入），回退到 `params["chat_context"]`（向后兼容）。总线 SHALL 在桥接停止时关闭，残留连接不阻塞进程退出。CLI 子命令 `permission-server` SHALL 支持 `--bus-port`（`--port` 为向后兼容别名）与 `--chat`/`--sender`。

**stdio 编码契约（本变更新增）**：MCP server 的 stdin/stdout SHALL 显式按 UTF-8 编解码（MCP 协议约定，不依赖平台默认编码——JDK 17 打包态 Windows zh-CN 默认字符集为 GBK，按平台默认收发将与 claude 的 UTF-8 约定错位，含中文路径的请求会乱码并导致白名单判定失配）。

系统 SHALL 在门面（`LarkBridge`）**构造期只建对象、不绑端口**：判定 `confirmConfigured = agent.enabled && confirmEnabled && backend == claude`（否则确认关闭），仅此时创建 `ConfirmService` → `LarkConfirmNotifier` → `ConfirmBus(service, logger, timeoutMillis, notifier)`，此时 SHALL NOT 绑定任何 loopback 端口、不启动协程作用域；确认超时 SHALL 经 `clampedConfirmTimeoutMillis(confirmTimeoutSeconds)` **下限钳制 10 秒**（0/负值会让确认卡片刚发出即超时拒绝）。

确认链路 SHALL 在 `LarkBridge.start()` 内经 `startConfirmRuntime()` 端到端装配（接线延迟到 start，Idle 态零资源）：`bus.start()` 绑**真实随机端口**（回环 `ServerSocket(0)`；端口 ≤ 0 时降级并置 `Unavailable`）→ `PermissionServerLauncher(busPort, workDir, workspaceWhitelist).prepare()` 生成启动脚本（null 时关总线降级）→ `selfCheck(script)` 以空 stdin 拉起脚本自检（`serve()` 读 EOF 即返回 0，验证 java 可执行 + classpath 完整 + MainKt 可加载 + 参数可解析；Failed 时删脚本 + 关总线降级）→ 三步全通过后 SHALL 用命令模板 `commandToken(script.path) + " --chat {chatId} --sender {senderOpenId}"` 把 provider 闭包回填到所有 `AgentExecutor.permissionPromptToolProvider`（`@Volatile`，内置与宿主注入实例均回填）并置 `ConfirmRuntimeStatus.Ready(port, scriptPath)`；任一步失败 SHALL 降级（关总线 / 删脚本 / 清 provider）并置 `ConfirmRuntimeStatus.Unavailable(reason)` 且记 error 级告警。`stop()` SHALL 无条件先调 `releaseConfirmRuntime()`（关总线 CAS 幂等 + 删启动脚本 + 清模板与 provider + 状态归位），Idle 态亦清理，避免宿主 recreate() 每次泄漏端口/协程。

系统 SHALL 不再拼 `java -cp …` shell 字符串作为 `--permission-prompt-tool` 的值（旧法三重失效：路径含空格被拆词、开发态 classpath 数十 KB 撞命令行长度上限、jpackage 态 `java.class.path` 不含业务类致 MainKt ClassNotFound），改由 `PermissionServerLauncher` 生成 `${java.io.tmpdir}/easydev-toolbox-perm/easydev-permission-server.sh|.bat`（classpath 取 `java.class.path` ∪ 自身 codeSource、路径平台化转义、含空格 token 加引号），claude 的 `--permission-prompt-tool` 值只指向脚本；脚本转发全部入参（unix `"$@"` / windows `%*`），故 `--chat`/`--sender` 由命令模板占位符执行时替换，无需为每个会话重写脚本。

**启动脚本内容与写出编码（本变更新增）**：脚本内容 SHALL 为纯 ASCII（注释与结构均不含非 ASCII 字符），使脚本在任何 ANSI 代码页下语义不变；Windows 平台脚本字节 SHALL 按平台 ANSI 编码（`native.encoding`，缺省回退 UTF-8）写出，确保中文用户名路径（`C:\Users\<中文名>\…`）被 cmd.exe 正确解析。`ClaudeBackend.buildArgsWithContext` 渲染命令时 provider 优先级 SHALL 为：执行时 `providerOverride` > 构造期 provider > 静态 `permissionPromptToolCommand`（向后兼容）。

系统 SHALL 经门面暴露可观测状态 `LarkBridge.confirmRuntimeStatus: StateFlow<ConfirmRuntimeStatus>`，把「运行时确认不可用」从一行 warn 日志升级为宿主可观测状态：`Disabled`（agent 关 / confirmEnabled=false / 后端非 claude / 桥接已停，正常配置态）、`Ready(port, scriptPath)`（总线已监听 + 脚本自检通过）、`Unavailable(reason)`（总线/脚本/自检任一失败，高危操作将 fail-safe deny，附人读原因）。宿主（App 设置页 / CLI serve）SHALL 收集本状态，`Unavailable` 时据此显式告警，而非让用户困惑于「为何全部被拒」。

#### Scenario: MCP server 转发权限请求

- **WHEN** claude 调用 permission_prompt 工具（tool_name + input）
- **THEN** MCP server 经总线发送确认请求并阻塞等待，收到裁决后按 MCP 响应格式返回 allow/deny

#### Scenario: MCP stdio 按 UTF-8 编解码（本变更新增）

- **WHEN** claude 向 stdin 写入含中文路径的 UTF-8 请求，或 MCP server 回写含非 ASCII 字符的裁决
- **THEN** MCP server 显式按 UTF-8 解码读取与编码回写，中文内容不乱码、白名单判定基于正确路径

#### Scenario: start() 内自检通过后回填 provider 并置 Ready

- **WHEN** `LarkBridge.start()` 内 `bus.start()` 取到真实端口、`PermissionServerLauncher.prepare()` 生成脚本、`selfCheck()` 拉起通过
- **THEN** provider 闭包回填到所有 AgentExecutor，`confirmRuntimeStatus` 置 `Ready(port, scriptPath)`；claude 渲染命令时追加 `--permission-prompt-tool`，值只指向启动脚本（含空格时整体加引号），`--chat`/`--sender` 执行时替换占位符

#### Scenario: Windows 启动脚本自检可通过（本变更新增）

- **WHEN** 应用运行于 Windows，`selfCheck()` 拉起 `.bat` 启动脚本
- **THEN** 脚本经 `cmd.exe /d /c` 执行，自检验证 java 可执行 + classpath 完整 + MainKt 可加载 + 参数可解析，通过后 `confirmRuntimeStatus` 置 `Ready`

#### Scenario: Idle 态不绑端口

- **WHEN** 门面已构造但尚未调 `start()`（Idle 态）且 confirmConfigured=true
- **THEN** `ConfirmBus` 未绑定任何 loopback 端口（`isRunning=false`、`port=-1`），未启动协程作用域

#### Scenario: 启动脚本自检失败降级为 Unavailable

- **WHEN** `bus.start()` 端口 ≤ 0、或 `prepare()` 返回 null、或 `selfCheck()` 非 0 退出/超时
- **THEN** 降级（关总线 / 删脚本 / 清 provider），`confirmRuntimeStatus` 置 `Unavailable(reason)` 并记 error 级告警（高危操作将 fail-safe deny）

#### Scenario: 启动脚本内容为纯 ASCII 且按平台编码写出（本变更新增）

- **WHEN** `prepare()` 在 Windows 平台生成 `.bat` 脚本（工作目录或 classpath 含中文路径）
- **THEN** 脚本内容不含非 ASCII 字符（注释为英文），字节按平台 ANSI 编码（native.encoding）写出，cmd.exe 按 ANSI 解析时路径不乱码、java 可正常拉起

#### Scenario: 后端非 claude 时确认不可用

- **WHEN** agent.enabled && confirmEnabled 但后端非 claude
- **THEN** 启动自检置 `confirmRuntimeStatus` 为 `Unavailable`（附原因「运行时确认仅 claude 后端支持」）并告警

#### Scenario: 确认超时下限钳制

- **WHEN** 配置的 `confirmTimeoutSeconds` 为 0 或负值
- **THEN** `clampedConfirmTimeoutMillis` 钳制为下限 10 秒（避免确认卡片刚发出即超时拒绝）

#### Scenario: 总线断连时默认拒绝

- **WHEN** MCP server 无法连接确认总线（桥接已停止/端口失效）
- **THEN** MCP server 返回 deny 并以非零退出码退出（fail-safe：无法确认即拒绝）

#### Scenario: 桥接停止时无条件释放

- **WHEN** 桥接服务停止（调 `stop()`，含 Idle 态）
- **THEN** `releaseConfirmRuntime()` 关确认总线（CAS 幂等）、ServerSocket 关闭、在途确认全部转为 CANCELLED、删除启动脚本、清模板与 provider、`confirmRuntimeStatus` 归位
