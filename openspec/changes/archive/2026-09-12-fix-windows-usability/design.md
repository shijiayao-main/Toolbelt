# Design: fix-windows-usability

## Context

跨平台审计（见 docs/compatibility-audit-20260905.html）确认 Windows 可用性四项阻断。期间提交 1412c4d 已引入 `AgentBackendAvailability`（PATH 扫描 + fallback 目录 + Windows 扩展名变体），agent 可执行文件的绝对路径解析已解决，但仍有三处 Windows 专属缺口：

1. **环境变量白名单**（`SandboxedProcessFactory.BASE_ENV_KEYS`）只有 Unix 惯例键，Windows 缺 `SystemRoot` 等系统键时 node 系 CLI 无法初始化。
2. **`.cmd`/`.bat` 拉起**：JVM `ProcessBuilder` 在 Windows 直接以 `.cmd`/`.bat` 为可执行文件时 `CreateProcess` 不解析批处理扩展名。两处同型缺口：`AgentExecutor`（agent 后端）与 `PermissionServerLauncher.commandFor`（运行时确认自检）。
3. **路径语义**：工作区域校验 `startsWith("/")` 不认盘符；adb 路径单引号对 `cmd.exe` 无效。

## Goals / Non-Goals

**Goals**
- Windows 中文系统上：工作区域可保存、agent 可拉起、运行时确认自检可通过、含空格 adb 路径可执行。
- macOS/Linux 行为零变化（env 键集、引号语义、拉起方式逐字节保持）。
- 收敛 6 处重复 tilde 展开为单一实现并兼容 `~\`。

**Non-Goals**
- 进程树终止（独立 change）。
- cmd 元字符完备转义（`CommandLineToArgvW` 级别，复杂度不成比例；文档化已知限制）。
- 性能项（X01~X07，另行排期）。
- `.bat` 换 PowerShell 或 JNA CreateProcess（超出止血范围）。

## Decisions

### D1. env 白名单：平台化扩展而非扩大 Unix 集合
`BASE_ENV_KEYS` 保持不动（Unix 锚点，现有测试逐键断言），新增 `WINDOWS_REQUIRED_ENV_KEYS` 常量与纯函数 `effectiveBaseKeys(isWindows: Boolean): Set<String>`；两个 `defaultFactory` 重载经 `effectiveBaseKeys(isWindows())` 取并集。理由：① 纯函数可测试（Unix 零回归锚点 = 两集合在 `isWindows=false` 时逐键相等）；② spec 增量把追加项写成封闭列表，杜绝宿主运行时注入任意键；③ `extraEnvKeys` 通道语义不变。

### D2. cmd.exe 包装仅限 `.cmd`/`.bat` 解析结果
`AgentExecutor` 新增私有纯函数 `spawnCommand(executableFile, args, isWindows)`：Windows 且解析结果以 `.cmd`/`.bat` 结尾时返回 `listOf("cmd.exe", "/d", "/c", absPath) + args`，其余（`.exe`、Unix 路径、executableFile 为 null 的兜底裸名）不包装。`/d` 禁用 AutoRun（避免用户自启动脚本污染子进程）。`PermissionServerLauncher.commandFor` 同型改造（`.bat` → cmd.exe 包装，`.sh` → sh 前缀）。理由：包装范围最小化，`claude.exe` 等原生二进制不走 cmd 避免引号二次解析。

### D3. 绝对路径校验下沉为纯函数
`BridgeWorkspaceCard` 的 `expanded.startsWith("/")` 改为 `java.io.File(expanded).isAbsolute`（正确处理盘符/UNC）。校验逻辑保持就地（仅一处调用点，抽象不成比例），但 tilde 展开收敛为 `lark-bridge` 的共享工具 `PathExpansion.expandTilde`（识别 `~`、`~/`、`~\`），替换 6 处重复实现：composeApp 2 处（BridgeWorkspaceCard、LarkBridgeViewModel）+ lark-bridge 4 处（AuditLogger ×2、SenderStatsStore ×2）。composeApp 已依赖 lark-bridge，无模块方向问题。

### D4. adb 引号：注入平台标记而非全局读取
`AdbCommandRewriter` 的 `adbExecutableToken` / `rewrite` 增加 `windows: Boolean = ShellCommandBuilder.isWindows` 参数。理由：现有 13 个测试用例（Unix 语义，含 :96 单引号锚点）一字不改即可保持绿；Windows 变体用参数显式驱动可测。

### D5. fallback 目录补全
`AgentBackendAvailability.FALLBACK_DIRS` 追加：Unix `~/.npm-global/bin`；Windows `getenv("APPDATA")/npm`。两行兜底，不再扩大（login shell PATH 探测等属过度设计）。

## Risk / Trade-offs

- **B06 先于 A02**：cmd.exe 缺 `SystemRoot` 连 node 都拉不起来，两者必须同批合入（同一 change）。
- **cmd 包装的参数转义**：`cmd.exe` 对参数二次解析，prompt 含 `"`/`&`/`^` 时与 POSIX 语义不同——已知剩余缺口，文档化，不在本轮根治。
- **AdbCommandRewriter 回归风险在 Unix 分支**：以「现有 13 用例零改动保持绿」为硬验收。
- **tilde 收敛的调用点回归**：逐处核对展开语义（`~` 裸前缀、`~/`、`~\`），收敛后以 PathExpansionTest 锁行为。

## Open Questions

无。
