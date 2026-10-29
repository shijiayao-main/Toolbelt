# Shell 执行层

源文件均位于 `core/extensions/`。

---

## 文件总览

| 文件 | 职责 |
|------|------|
| `ShellType.kt` | Shell 类型枚举（ZSH / BASH） |
| `ShellCommandBuilder.kt` | 根据平台和 ShellType 构建 `ProcessBuilder` 参数 |
| `SimpleShell.kt` | 一次性执行命令，等待结束，返回 `ShellResult` |
| `StreamingShell.kt` | 流式执行命令，逐行回调输出（不累积全量文本），支持外部 kill + 三段式升级链（SIGTERM→3s→SIGKILL→2s→放行） |
| `InteractiveShell.kt` | 保持 shell 会话，流式输出，支持顺序执行命令队列 |
| `ShellModels.kt` | 数据模型：`ShellResult`、`ShellOutput`、`ShellOutputSource` |

> 🔴 子进程工作目录 = 用户主目录（`ShellCommandBuilder.applyWorkingDir`，SimpleShell 与 StreamingShell 的 pb 装配点同挂）——进程 cwd 随启动方式漂移（gradle run=工程根、打包启动=安装目录），定音 user.home 后相对路径命令的产物落点确定。
> 🔴 StreamingShell 的行按 **\n** 分隔，裸 `\r` 不作行终止符、保留在行内容中（readLine 按 \r 分行会把进度条切成碎片行并丢失覆盖语义，已弃用）；控制台展示侧（ShellExecutionEngine）经 `ConsoleLineSanitizer` 剥 ANSI 转义并折叠进度条（只留最终态）。

---

## ShellType

```kotlin
enum class ShellType(val displayName: String) {
    ZSH("Zsh"),
    BASH("Bash")
}
```

- 由 `AppViewModel` 管理，存入 `EasyDataManager`（key: `shell_type`）
- 初始化时写入 `ShellCommandBuilder.shellType`
- macOS 默认 `ZSH`，Linux 默认 `BASH`

---

## ShellCommandBuilder

```kotlin
object ShellCommandBuilder {
    val isWindows: Boolean   // os.name contains "win"
    val isMac: Boolean       // os.name contains "mac"
    var shellType: ShellType // 可在运行时修改，AppViewModel 负责同步

    fun wrap(command: String): Array<String>
}
```

**wrap 输出：**

| 平台 | 命令 |
|------|------|
| Windows | `["cmd.exe", "/c", command]` |
| macOS/Linux + ZSH | `["/bin/zsh", "-l", "-c", command]` |
| macOS/Linux + BASH | `["/bin/bash", "-l", "-c", command]` |

`-l`（login shell）确保读取用户配置文件（`~/.zprofile` 或 `~/.bash_profile`），PATH 才能正确加载。

> **重要**：用户的 PATH（如 `adb`）需配置在对应 shell 的 login 配置文件中才能生效。如果 `adb devices` 报找不到命令，让用户在设置页切换 Shell 类型以匹配他们配置 PATH 的 shell。

---

## SimpleShell

```kotlin
object SimpleShell {
    suspend fun execute(vararg cmd: String): Result<ShellResult>
}
```

**特点：**
- 内部将 `cmd` 数组 join 为单个字符串，传给 `ShellCommandBuilder.wrap()`
- stdout / stderr 并行异步读取（两个 `async` 协程）
- 等待进程退出后返回 `ShellResult(exitCode, stdout, stderr)`
- 用 `runCatching` 包裹，异常以 `Result.failure` 返回

**用法示例：**
```kotlin
val result = SimpleShell.execute("adb", "devices")
result.onSuccess { shellResult ->
    // shellResult.exitCode, shellResult.stdout, shellResult.stderr
}
```

---

## InteractiveShell

维持一个长运行的 shell 进程，可以多次发送命令。

```kotlin
class InteractiveShell(private val scope: CoroutineScope) {
    val outputFlow: SharedFlow<ShellOutput>   // 实时输出流

    fun start(command: String)                // 启动 shell 进程（如 "adb shell"）
    suspend fun sendCommand(command: String)  // 向进程 stdin 写入一行命令
    suspend fun executeQueue(commands: List<String>)  // 顺序执行命令队列，逐步等待完成
    fun close()                              // 关闭进程和读取协程
}
```

**executeQueue 机制：**  
每条命令追加 `&& echo __DONE__`，等待 stdout 出现 `__DONE__` 标记后才发送下一条，实现顺序执行。

**典型用法（Chain 类型命令）：**
```kotlin
val shell = InteractiveShell(scope = viewModelScope)
shell.start("adb shell")
shell.executeQueue(listOf("cd /data/log", "ls -la"))
shell.close()
```

---

## ShellModels

```kotlin
// SimpleShell 返回值
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

// InteractiveShell 输出流元素
enum class ShellOutputSource { STDOUT, STDERR }

data class ShellOutput(
    val line: String,
    val source: ShellOutputSource,
)
```

---

## StreamingShell

```kotlin
object StreamingShell {
    suspend fun executeStreaming(
        command: String,
        onStarted: (StreamingShellHandle) -> Unit = {},
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<ShellResult>
}

class StreamingShellHandle {
    val killRequested: Boolean   // @Volatile
    fun kill()                   // 请求终止（幂等）
}
```

**特点：**
- 内部同样经 `ShellCommandBuilder.wrap()` 构建命令
- 输出经 `onLine` 逐行回调消费，**不随结果累积返回**（内存有界）
- 返回的 `ShellResult` 仅 `exitCode` 准确，`stdout`/`stderr` 恒为空串
- 进程退出等待为带条件的限时收割循环：`killRequested` 未置位时以 200ms 轮询等待正常完成（不设总上限）；置位后走升级链 SIGTERM(3s) → destroyForcibly(2s) → 放行(-1)
- `onStarted` 回调 handle 供外部 `kill()` 杀进程

**典型用法（Shell 工具页的流式执行引擎）：**
```kotlin
StreamingShell.executeStreaming(
    command = "adb logcat",
    onStarted = { handle = it },
    onLine = { output -> emit(output) },
)
```

---

## 两种进程调用语义的分工（关键约定）

本项目的进程调用分为两种语义，**不可混用**：

### 1. Shell 命令行语义（`core/extensions/`）

| 封装 | 特点 |
|------|------|
| `SimpleShell` | 一次性执行，等待结束 |
| `StreamingShell` | 流式逐行回调 |
| `InteractiveShell` | 长运行会话，多次发命令 |

- **必经** `ShellCommandBuilder.wrap()`：Windows 走 `cmd.exe /c`，macOS/Linux 走 `/bin/zsh -l -c` 或 `/bin/bash -l -c`
- **`processCharset`**：Windows 下是 **ANSI**（系统默认编码），非 UTF-8
- 适用场景：用户手动输入 shell 命令（ADB 指令、Shell 工具页等）

### 2. 程序 argv 调用语义（`lark-bridge/exec/ProgramLauncher`）

- 直接 `ProcessBuilder(argv)` — argv 数组程序调用，**不经** `ShellCommandBuilder.wrap()`
- **一律** `Charsets.UTF_8`
- 适用场景：agent 子进程（claude/codex/gemini/custom）与 MCP stdio
- `:core` 侧同语义件 = `core/system/ArgvProcessRunner`（只读诊断域 `sys.*` 工具族的唯一执行件：执行面黑名单硬拒 + systemctl/journalctl 恒 `--user`）

### 为什么飞书桥接的 agent 子进程不能用 Shell 命令行语义

1. agent 需要 argv 程序调用语义（精确传递参数数组），而非 shell 命令行语义（字符串拼接后交给 shell 解释）
2. claude 的 `stream-json` 输出是 UTF-8，与 Windows 下的 ANSI `processCharset` 冲突（会乱码）
3. `ProcessBuilder` 出现点按模块**精确收口**（两侧各有守护测试钉死，新增合法点须先改守护锚登记理由再落生产代码）：
   - `:lark-bridge` 侧唯一合法点 = `exec/ProgramLauncher.kt`（`LarkSdkIsolationTest` E 组）；
   - `:core` 侧合法点集 = `core/extensions/` 四件（shell 命令行语义收口）+ `media/AudioTranscoder.kt`（ffmpeg argv 直调先例）+ `system/ArgvProcessRunner.kt`（只读诊断域 argv 执行原语——`sys.*` 工具族唯一执行件，`core/llm/tools/` 内零 `ProcessBuilder`）（`CoreProcessBuilderAllowlistGuardTest`）。
