# Delta: lark-bridge-module

## MODIFIED Requirements

### Requirement: Agent 进程沙箱约束（多目录白名单）

系统 SHALL 以多目录白名单（`WorkspacePolicy`）定义 agent 允许根集合：由单目录 `workspaceDir` 与多目录 `workspaceWhitelist` 取并集构成（`WorkspacePolicy.fromConfig`），构造时一次性 canonical 解析所有根并缓存。启动 agent 子进程时工作目录规范化（canonical path）后 SHALL 位于任一根之内，否则拒绝启动；路径前缀比对 SHALL 做分隔符对齐（防 `/foo/bar` 误放行 `/foo/barbaz`）；空白名单（roots 为空）SHALL fail-safe 拒绝所有路径。`agentWorkDir` 为空时 SHALL 回退到白名单第一根（不继承应用进程 cwd）。子进程环境变量 SHALL 采用白名单传递，不继承应用进程的完整环境：Unix 平台白名单为 PATH、HOME、LANG、LC_ALL、TZ、代理变量（含大小写变体）；**Windows 平台在 Unix 集合之外追加系统必需键 `SystemRoot`、`SystemDrive`、`USERPROFILE`、`HOMEDRIVE`、`HOMEPATH`、`APPDATA`、`LOCALAPPDATA`、`COMSPEC`、`PATHEXT`、`TEMP`、`TMP`**（均为系统运行键，不含凭据类变量；追加 SHALL 仅限该封闭列表，宿主不得运行时注入任意键）。agent 可执行文件解析的 fallback 目录 SHALL 覆盖 npm 官方全局安装位：Unix 追加 `~/.npm-global/bin`、Windows 追加 `%APPDATA%\npm`（其余 fallback 目录不变）。本沙箱定位为「降低概率 + 提高可见性」的纵深防御，非内核级强隔离（agent 为外部子进程）。

#### Scenario: 多根白名单内合法启动

- **WHEN** agent 工作目录（显式配置或回退到白名单第一根）规范化后位于白名单任一根内
- **THEN** 子进程以该目录为工作目录启动，环境变量仅包含白名单项

#### Scenario: 越界工作目录被拦截

- **WHEN** agent 工作目录规范化后不位于白名单任一根内（如 /tmp、$HOME 根）
- **THEN** 进程不被启动，返回启动失败结果

#### Scenario: 空白名单 fail-safe

- **WHEN** 单目录与多目录白名单均为空（roots 为空）
- **THEN** `WorkspacePolicy` 拒绝所有路径校验（无白名单 = 无允许区域），agent 拒绝执行

#### Scenario: 分隔符对齐防前缀混淆

- **WHEN** 白名单根为 `/foo/bar`，待校验路径为 `/foo/barbaz`
- **THEN** 校验不通过（分隔符对齐避免前缀子串误放行）

#### Scenario: 环境变量不泄露敏感值

- **WHEN** 应用进程环境中存在 API Key / Token 类变量（如 GITHUB_TOKEN）
- **THEN** agent 子进程环境中不包含未列入白名单的变量

#### Scenario: Unix 平台白名单零变化

- **WHEN** 应用运行于 macOS / Linux，agent 子进程被启动
- **THEN** 子进程环境变量键集与平台化扩展前完全一致（PATH、HOME、LANG、LC_ALL、TZ、代理变量及大小写变体），不含 Windows 系统键

#### Scenario: Windows 平台白名单含系统必需键

- **WHEN** 应用运行于 Windows，agent 子进程被启动
- **THEN** 子进程环境变量在 Unix 集合之外包含 `SystemRoot`/`USERPROFILE`/`APPDATA`/`COMSPEC`/`PATHEXT` 等系统必需键（存在时），否则 node 系 agent CLI 无法初始化

#### Scenario: npm 官方全局安装位可被发现

- **WHEN** 用户经 `npm install -g`（默认 prefix，未配置 nvm/自定义 prefix）安装 agent CLI，桌面环境 PATH 不含 npm bin 目录
- **THEN** `~/.npm-global/bin`（Unix）或 `%APPDATA%\npm`（Windows）中的可执行文件被 agent 可执行文件解析的 fallback 搜索命中（解析顺序不变：PATH 逐目录 → 用户级 fallback → 未找到）

### Requirement: 确认总线与 MCP permission server

系统 SHALL 提供本地 TCP 确认总线（127.0.0.1 随机端口，JSON 行协议）：stdio MCP server（独立短命进程，由 claude 经 `--permission-prompt-tool` 按需拉起）把权限请求转发到总线并同步等待裁决响应。MCP server SHALL 实现 MCP 协议的 initialize/tools-list/tools-call 方法（工具名 `permission_prompt`），把 tool_name + input 透传为确认请求内容；MCP server 的 chatId/senderOpenId SHALL 优先从 CLI `--chat`/`--sender` 取（由 provider 模板替换占位符后传入），回退到 `params["chat_context"]`（向后兼容）。总线 SHALL 在桥接停止时关闭，残留连接不阻塞进程退出。CLI 子命令 `permission-server` SHALL 支持 `--bus-port`（`--port` 为向后兼容别名）与 `--chat`/`--sender`。

系统 SHALL 在门面（`LarkBridge`）**构造期只建对象、不绑端口**：判定 `confirmConfigured = agent.enabled && confirmEnabled && backend == claude`（否则确认关闭），仅此时创建 `ConfirmService` → `LarkConfirmNotifier` → `ConfirmBus(service, logger, timeoutMillis, notifier)`，此时 SHALL NOT 绑定任何 loopback 端口、不启动协程作用域；确认超时 SHALL 经 `clampedConfirmTimeoutMillis(confirmTimeoutSeconds)` **下限钳制 10 秒**（0/负值会让确认卡片刚发出即超时拒绝）。

确认链路 SHALL 在 `LarkBridge.start()` 内经 `startConfirmRuntime()` 端到端装配（接线延迟到 start，Idle 态零资源）：`bus.start()` 绑**真实随机端口**（回环 `ServerSocket(0)`；端口 ≤ 0 时降级并置 `Unavailable`）→ `PermissionServerLauncher(busPort, workDir, workspaceWhitelist).prepare()` 生成启动脚本（null 时关总线降级）→ `selfCheck(script)` 以空 stdin 拉起脚本自检（`serve()` 读 EOF 即返回 0，验证 java 可执行 + classpath 完整 + MainKt 可加载 + 参数可解析；Failed 时删脚本 + 关总线降级）→ 三步全通过后 SHALL 用命令模板 `commandToken(script.path) + " --chat {chatId} --sender {senderOpenId}"` 把 provider 闭包回填到所有 `AgentExecutor.permissionPromptToolProvider`（`@Volatile`，内置与宿主注入实例均回填）并置 `ConfirmRuntimeStatus.Ready(port, scriptPath)`；任一步失败 SHALL 降级（关总线 / 删脚本 / 清 provider）并置 `ConfirmRuntimeStatus.Unavailable(reason)` 且记 error 级告警。`stop()` SHALL 无条件先调 `releaseConfirmRuntime()`（关总线 CAS 幂等 + 删启动脚本 + 清模板与 provider + 状态归位），Idle 态亦清理，避免宿主 recreate() 每次泄漏端口/协程。

**启动脚本执行方式（本变更新增）**：Windows 平台 SHALL 以 `cmd.exe /d /c <脚本路径>` 拉起 `.bat` 启动脚本（自检与 claude 实际执行同一形态）——JVM 的 `ProcessBuilder` 直接以 `.bat` 为可执行文件时 Windows `CreateProcess` 不解析批处理扩展名，导致自检恒失败、运行时确认恒降级；Unix 平台维持 `sh <脚本>` 方式不变。拉起方式 SHALL 与脚本扩展名联动：`.bat` → cmd.exe 包装，`.sh` → sh 前缀。

系统 SHALL 不再拼 `java -cp …` shell 字符串作为 `--permission-prompt-tool` 的值（旧法三重失效：路径含空格被拆词、开发态 classpath 数十 KB 撞命令行长度上限、jpackage 态 `java.class.path` 不含业务类致 MainKt ClassNotFound），改由 `PermissionServerLauncher` 生成 `${java.io.tmpdir}/easydev-toolbox-perm/easydev-permission-server.sh|.bat`（classpath 取 `java.class.path` ∪ 自身 codeSource、路径平台化转义、含空格 token 加引号），claude 的 `--permission-prompt-tool` 值只指向脚本；脚本转发全部入参（unix `"$@"` / windows `%*`），故 `--chat`/`--sender` 由命令模板占位符执行时替换，无需为每个会话重写脚本。`ClaudeBackend.buildArgsWithContext` 渲染命令时 provider 优先级 SHALL 为：执行时 `providerOverride` > 构造期 provider > 静态 `permissionPromptToolCommand`（向后兼容）。

系统 SHALL 经门面暴露可观测状态 `LarkBridge.confirmRuntimeStatus: StateFlow<ConfirmRuntimeStatus>`，把「运行时确认不可用」从一行 warn 日志升级为宿主可观测状态：`Disabled`（agent 关 / confirmEnabled=false / 后端非 claude / 桥接已停，正常配置态）、`Ready(port, scriptPath)`（总线已监听 + 脚本自检通过）、`Unavailable(reason)`（总线/脚本/自检任一失败，高危操作将 fail-safe deny，附人读原因）。宿主（App 设置页 / CLI serve）SHALL 收集本状态，`Unavailable` 时据此显式告警，而非让用户困惑于「为何全部被拒」。

#### Scenario: MCP server 转发权限请求

- **WHEN** claude 调用 permission_prompt 工具（tool_name + input）
- **THEN** MCP server 经总线发送确认请求并阻塞等待，收到裁决后按 MCP 响应格式返回 allow/deny

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
