# Proposal: fix-windows-usability

> ⚠️ 注：旧 `:lark-bridge` 已在 rewrite-lark-bridge 中移除，本 change 的成果由新模块承接（以下正文为历史记录，不改）。

## Why

跨平台兼容性审计（2026-09-05）确认：当前代码在 Windows 中文系统上 lark-bridge 桥接链路基本不可用——工作区域路径校验不认盘符导致配置无法保存、agent 后端以裸命令名 spawn 无法启动 npm 的 `.cmd` shim、子进程环境变量白名单缺 Windows 系统必需键（缺 `SystemRoot` 时 node 无法初始化）、adb 自定义路径引号对 `cmd.exe` 无效。四项叠加使 Windows 用户从配置到执行全链路受阻。macOS/Linux 行为不受影响，需保证零回归。

## What Changes

- 子进程环境变量白名单按平台扩展：Windows 追加系统必需键（`SystemRoot`、`SystemDrive`、`USERPROFILE`、`HOMEDRIVE`、`HOMEPATH`、`APPDATA`、`LOCALAPPDATA`、`COMSPEC`、`PATHEXT`、`TEMP`、`TMP`）；Unix 键集保持不变。
- Windows 下以 `cmd.exe /d /c` 拉起 `.cmd`/`.bat` 形态的 agent 可执行文件与 permission server 启动脚本（`.exe` 与 Unix 路径不受影响）。
- 工作区域路径的绝对性校验改用平台语义（`java.io.File.isAbsolute`），Windows 盘符路径（`C:\…`、`C:/…`、UNC）不再被误判非法。
- adb 自定义路径含空白时的包裹符按执行 shell 语义选择：Unix 单引号（现状不变）、Windows 双引号。
- 收敛 6 处重复的 `~` 前缀展开实现为单一工具函数，并兼容 Windows 分隔符 `~\`。
- agent 可执行文件 fallback 搜索目录补全：`~/.npm-global/bin` 与 Windows 的 `%APPDATA%\npm`。

## Capabilities

### Modified Capabilities

- `lark-bridge-module`：Agent 进程沙箱约束（env 白名单平台化）、确认总线与 MCP permission server（Windows 启动脚本拉起方式）、agent 可执行文件解析（fallback 目录补全）。
- `lark-bridge-page`：工作区域路径输入校验（绝对路径判定语义平台化）。
- `app-settings`：ADB 路径解析顺序（含空白路径的引号语义按执行 shell 平台化）。

## Impact

- **代码**：`lark-bridge`（SandboxedProcessFactory、AgentExecutor、PermissionServerLauncher、AgentBackendAvailability、新增 PathExpansion 工具）、`composeApp`（BridgeWorkspaceCard、LarkBridgeViewModel、AdbCommandRewriter、AuditLogger/SenderStatsStore 的 tilde 展开调用点）。
- **安全语义**：env 白名单追加的 11 个键全部为系统键（非凭据类），Unix 用户的白名单语义零变化；`extraEnvKeys` 宿主扩展通道不变。
- **测试**：现有 436 + 282 用例必须保持全绿；新增 Windows 变体用例与 PathExpansion 单测。
- **不涉及**：进程树终止、性能项、cmd 元字符完备转义（另行排期）。
