# Tasks: fix-windows-usability

## 1. 环境变量白名单平台化（B06，先于 cmd.exe 包装）

- [x] 1.1 `SandboxedProcessFactory` 新增 `WINDOWS_REQUIRED_ENV_KEYS` 常量与 `effectiveBaseKeys(isWindows: Boolean): Set<String>` 纯函数，两个 `defaultFactory` 重载改用 `effectiveBaseKeys(isWindows()) + extraEnvKeys`。验证：`./gradlew :lark-bridge:compileKotlin` 通过
- [x] 1.2 `SandboxedProcessFactoryTest` 新增：`effectiveBaseKeys(false)` 与 `BASE_ENV_KEYS` 逐键相等（Unix 零回归锚点）、`effectiveBaseKeys(true)` 包含全部 11 个 Windows 必需键。验证：`./gradlew :lark-bridge:test --tests "*SandboxedProcessFactory*"` 全绿
- [x] 1.3 同步 `openspec/specs/lark-bridge-module/spec.md` Agent 进程沙箱约束的 env 白名单条款（合入时归档 delta）。验证：`openspec validate fix-windows-usability` 通过

## 2. Windows .cmd/.bat 拉起走 cmd.exe 包装（A02）

- [x] 2.1 `AgentExecutor` 新增 `spawnCommand(executableFile, args, isWindows)` 纯函数：Windows 且扩展名为 `.cmd`/`.bat` 时 `listOf("cmd.exe", "/d", "/c", absPath) + args`，其余不包装；替换现有命令构造。验证：编译通过，dry-run 模式行为不变
- [x] 2.2 新增 spawn 纯函数测试：Windows + `.cmd` → 包装；Windows + `.exe` → 不包装；Unix + 任意 → 不包装。验证：`./gradlew :lark-bridge:test --tests "*AgentExecutor*"` 全绿
- [x] 2.3 `PermissionServerLauncher.commandFor` Windows 分支改为 `listOf("cmd.exe", "/d", "/c", script.path)`。验证：编译通过
- [x] 2.4 `PermissionServerLauncherTest` 补 Windows 命令形态断言。验证：`./gradlew :lark-bridge:test --tests "*PermissionServerLauncher*"` 全绿

## 3. 工作区域路径校验认盘符（A01）+ tilde 收敛

- [x] 3.1 新增 `lark-bridge` 共享工具 `PathExpansion.expandTilde`（识别 `~`、`~/`、`~\`），替换 composeApp 2 处（BridgeWorkspaceCard、LarkBridgeViewModel）与 lark-bridge 4 处（AuditLogger ×2、SenderStatsStore ×2）重复实现。验证：编译通过
- [x] 3.2 新增 `PathExpansionTest`：`~`、`~/x`、`~\x`、无前缀原样。验证：`./gradlew :lark-bridge:test --tests "*PathExpansion*"` 全绿
- [x] 3.3 `BridgeWorkspaceCard` 绝对路径校验改 `java.io.File(expanded).isAbsolute`。验证：编译通过，现有 jvmTest 全绿
- [x] 3.4 同步 `openspec/specs/lark-bridge-page/spec.md` 工作区域输入校验 Scenario。验证：`openspec validate fix-windows-usability` 通过

## 4. adb 引号按平台（B04）+ fallback 补全（B07 残余）

- [x] 4.1 `AdbCommandRewriter` 增加 `windows: Boolean = ShellCommandBuilder.isWindows` 参数，`adbExecutableToken` Windows 分支双引号包裹。验证：现有 13 个 Unix 用例零改动保持绿
- [x] 4.2 新增 Windows 变体测试：`windows = true` + 含空格路径 → 双引号。验证：`./gradlew :composeApp:jvmTest --tests "*AdbCommandRewriter*"` 全绿
- [x] 4.3 `AgentBackendAvailability.FALLBACK_DIRS` 追加 `~/.npm-global/bin` 与 Windows `%APPDATA%\npm`。验证：编译通过
- [x] 4.4 同步 `openspec/specs/app-settings/spec.md` adb 引号语义。验证：`openspec validate fix-windows-usability` 通过

## 5. 批次验收

- [x] 5.1 全量测试：`./gradlew :lark-bridge:test :composeApp:jvmTest :log-tools:test` 全绿（436 + 282 基线 + 新增用例）。验证：BUILD SUCCESSFUL，无失败用例
- [x] 5.2 `openspec validate fix-windows-usability` 通过
- [x] 5.3 人工验证项（2026-09-12 维护者确认已验证）：工作区域 `C:\dev\ws` 可保存、agent 拉起 claude、confirmRuntimeStatus==Ready、含空格 adb 路径执行成功
