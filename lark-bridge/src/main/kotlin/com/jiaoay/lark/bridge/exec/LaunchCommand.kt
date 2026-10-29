package com.jiaoay.lark.bridge.exec

import java.io.File

/**
 * argv 程序调用命令构造纯函数（behavior-contract.md §4.5 / §4.6）。
 *
 * 🔴 这是 **argv 程序调用语义**，不是 shell 命令行语义（不复用 :core 的 SimpleShell/StreamingShell/InteractiveShell，
 *    它们必经 ShellCommandBuilder.wrap() 走 `cmd.exe /c` 或 `/bin/zsh -l`，且 Windows processCharset 是 ANSI，
 *    与 claude `stream-json` 的 UTF-8 冲突）。本文件只产出 argv 列表，由 [ProgramLauncher] 交 ProcessBuilder 直接执行。
 *
 * 🔴 **字面值校正**：Windows 包装是 `"/c"`（带斜杠）——历史报告曾笔误为 `"c"`，勿改回。
 */
object LaunchCommand {

    /** Windows cmd 包装前缀（脚本与可执行文件共用形态）。 */
    private val CMD_PREFIX = listOf("cmd.exe", "/d", "/c")

    /**
     * 脚本拉起命令（behavior-contract.md §4.5）：
     * Windows → `["cmd.exe","/d","/c", scriptPath]`；其余 → `["/bin/sh", scriptPath]`。
     */
    fun launchCommand(scriptPath: String, windows: Boolean): List<String> =
        if (windows) CMD_PREFIX + scriptPath else listOf("/bin/sh", scriptPath)

    /**
     * 可执行文件 spawn 命令（behavior-contract.md §4.6，源 AgentExecutor.kt:550）：
     * - `executableFile == null` → 回退**裸可执行名** [fallbackExecutable]（如 `"claude"`），不包装；
     * - Windows 且扩展名 `.cmd`/`.bat`（**大小写不敏感**，`.CMD` 同样命中）→ `["cmd.exe","/d","/c", <absolutePath>, ...args]`；
     * - `.exe` 与 Unix 平台路径 → **不包装**：`[<absolutePath>, ...args]`。
     *
     * 🔴 .cmd/.bat 包装分支的 args 做 cmd 引号转义——
     *    JDK ProcessImpl 对 .cmd/.bat 走 VERIFICATION_CMD_BAT 校验：参数含**裸内嵌双引号**
     *    直接抛 IllegalArgumentException（"use the three argument form to escape"），且该异常
     *    穿透执行器的 IOException/IllegalStateException 双 catch ⇒ 用户拿到
     *    无 errorCode 的裸 task_error。prompt 含引号（如「他说"hi"」）即必崩。
     *    转义规则（cmd 惯例 + JDK 校验要求）：内嵌 `"` → `\"`；引号段的尾 `\` 加倍（防 `foo\"`
     *    被解析为引号继续符）。外层引号由 JDK joinCommandLine 按需添加，此处只做内嵌转义。
     *
     * ⚠️ 已知限制不修（cmd.exe 语义限制）：cmd 对 `/c` 后整条命令行做 `%VAR%` 变量展开，
     *    **引号内同样生效**；命令行位置**无转义序列**（`%%` 只在批处理文件内有效，Node
     *    cross-spawn / Gradle 均不处理）。prompt 恰含 `%PATH%` 等真实变量名时会先展开再交
     *    CLI——内容被改写或本机环境信息进上下文。无法在命令行层防御，登记为已知限制；
     *    彻底解法（prompt 走 stdin）属 CLI 协议变更，超出本次范围。
     */
    fun spawnCommand(
        executableFile: File?,
        args: List<String>,
        isWindows: Boolean,
        fallbackExecutable: String,
    ): List<String> {
        val executable = executableFile?.absolutePath ?: return listOf(fallbackExecutable) + args
        val needsCmdWrapper = isWindows &&
            (executable.endsWith(".cmd", ignoreCase = true) || executable.endsWith(".bat", ignoreCase = true))
        return if (needsCmdWrapper) CMD_PREFIX + executable + args.map(::escapeCmdQuote) else listOf(executable) + args
    }

    /**
     * cmd.exe 参数的内嵌引号转义：`"` → `\"`；转义点前的尾反斜杠加倍。
     * 仅 .cmd/.bat 包装分支使用（.exe 与 Unix 不经 shell，argv 直传无此问题）。
     */
    private fun escapeCmdQuote(arg: String): String {
        if (!arg.contains('"') && !arg.endsWith("\\")) return arg
        val sb = StringBuilder(arg.length + 8)
        var i = 0
        while (i < arg.length) {
            val c = arg[i]
            if (c == '"') {
                // 引号点：其前的连续尾反斜杠先加倍（\" → \\" 语义），再补转义引号
                var backslashes = 0
                var j = sb.length
                while (j > 0 && sb[j - 1] == '\\') { backslashes++; j-- }
                repeat(backslashes) { sb.append('\\') }   // 尾 \ 加倍
                sb.append("\\\"")
            } else {
                sb.append(c)
            }
            i++
        }
        // 末尾若残留奇数个反斜杠（会被 JDK 拼上外层引号时误吞）：加倍防歧义
        var tail = 0
        var k = sb.length
        while (k > 0 && sb[k - 1] == '\\') { tail++; k-- }
        if (tail % 2 == 1) sb.append('\\')
        return sb.toString()
    }
}
