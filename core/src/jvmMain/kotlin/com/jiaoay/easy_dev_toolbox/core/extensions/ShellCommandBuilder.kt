package com.jiaoay.easy_dev_toolbox.core.extensions

import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter
import java.io.File
import java.nio.charset.Charset

/**
 * Wraps a command string with the appropriate shell for the current OS.
 *
 * - Windows: cmd.exe /c
 * - macOS / Linux: uses [shellType] (ZSH / BASH / SH) as a login shell (-l),
 *   so the user's profile (e.g. ~/.zprofile or ~/.bash_profile) is loaded
 *   and PATH is set correctly. Switch [shellType] to match wherever the
 *   user has configured their environment variables.
 */
object ShellCommandBuilder {

    private val os = System.getProperty("os.name").lowercase()

    val isWindows: Boolean get() = os.contains("win")
    val isMac: Boolean get() = os.contains("mac")

    var shellType: ShellType = if (isMac) ShellType.ZSH else ShellType.BASH

    /**
     * 子进程流读写编码（读侧与写侧必须同一编码，否则中文命令/输出必乱码）：
     * Windows cmd.exe 经管道输出为控制台 ANSI 代码页（zh-CN = GBK，与 JVM 无关），
     * 按 native.encoding 显式对齐（不再依赖打包 JDK 版本——JDK17 平台默认 GBK 碰巧可读，
     * JDK18+ 平台默认 UTF-8 必乱）；Unix 子进程输出约定 UTF-8。
     * 已知取舍：Windows 下输出 UTF-8 的工具（node/python）会按 ANSI 解码而乱码。
     *
     * 🔴 `Charset.forName` 包 runCatching 兜底（非法 charset 名不炸，回退默认）。
     * 本属性是 object 的 **static val**，异常发生在**类初始化**阶段 —— 若字符集不可用，
     * 抛出的不是普通异常而是 `ExceptionInInitializerError`，会让**整条 Shell 链路**
     * （Shell 工具页 / adb 重写 / 读 shellType）全部失效，后续引用恒抛 `NoClassDefFoundError`。
     * 触发场景真实存在：jpackage 的 jlink 裁剪运行时若漏了 `jdk.charsets`，
     * 非 CJK 语言版 Windows（繁中 MS950 / 韩文 MS949 / 希伯来 1255 等）的 native.encoding
     * 就会 UnsupportedCharsetException（GBK 在 java.base 里，故中文环境测不出来）。
     * 模块清单已同步补 `jdk.charsets`（composeApp/build.gradle.kts）；此处兜底是纵深防御——
     * 宁可退回 UTF-8 输出略乱码，也不能让整个 Shell 功能崩掉。
     */
    val processCharset: Charset = if (isWindows) {
        runCatching { Charset.forName(System.getProperty("native.encoding", "UTF-8")) }
            .getOrDefault(Charsets.UTF_8)
    } else {
        Charsets.UTF_8
    }

    /**
     * 按命令选择子进程管道编码：adb 命令族恒 UTF-8，其余命令沿用 [processCharset]。
     *
     * 契约：adb 经管道输出的字节恒 UTF-8（设备侧 logcat/shell 文本按 UTF-8 编码），
     * 与控制台代码页无关——Windows 上按 [processCharset]（ANSI/GBK）解码会把中文
     * tag 与消息解成乱码。只对 adb 放开：dir/git 等原生工具仍按控制台代码页输出，
     * 全局改 UTF-8 会反向引入乱码。
     *
     * adb 判定覆盖两种命令形态（[isAdbCommandFamily]）：未重写的字面 `adb ...`，
     * 以及经 [AdbCommandRewriter.rewrite] 注入自定义路径后的形态（首 token 基名为
     * `adb` / `adb.exe`，允许带引号与目录前缀）。
     */
    fun charsetFor(command: String): Charset =
        if (isAdbCommandFamily(command)) Charsets.UTF_8 else processCharset

    /** adb 命令族判定（纯函数，测试可直接构造命令串验证）。 */
    internal fun isAdbCommandFamily(command: String): Boolean {
        val trimmed = command.trim()
        if (AdbCommandRewriter.isAdbCommand(trimmed)) return true
        val executable = executableToken(trimmed)
        if (executable.isEmpty()) return false
        val baseName = executable.substringAfterLast('/').substringAfterLast('\\')
        return baseName.equals("adb", ignoreCase = true) || baseName.equals("adb.exe", ignoreCase = true)
    }

    /** 命令首 token 的可执行文件部分：引号包裹的路径取到配对引号为止，否则取到首个空白。 */
    private fun executableToken(trimmed: String): String {
        if (trimmed.isEmpty()) return ""
        val quote = trimmed[0]
        if (quote == '"' || quote == '\'') {
            val end = trimmed.indexOf(quote, startIndex = 1)
            if (end > 1) return trimmed.substring(1, end)
        }
        return trimmed.substringBefore(' ')
    }

    /**
     * 🔴 shell 可执行文件路径多候选探测（PATH 上无 zsh 时不至于直接失败）。
     *
     * 发行版可能把 shell 装在 `/usr/bin`（无 `/bin` symlink——部分容器化 / USRMERGE 未合并
     * 环境），单一路径会让 `ProcessBuilder.start()` 抛 IOException 且报错笼统。
     *
     * 候选链：`/bin/<name>` → `/usr/bin/<name>` → `/usr/local/bin/<name>`。
     * 探测结果缓存（shell 路径不随运行期变化）；全 miss 时回退 `/bin/<name>` 旧字面值
     * （保持既有报错形态，零回归）。
     */
    private val resolvedShellPaths: Map<ShellType, String> by lazy {
        ShellType.entries.associateWith { type ->
            val name = type.executableName
            listOf("/bin/$name", "/usr/bin/$name", "/usr/local/bin/$name")
                .firstOrNull { File(it).canExecute() }
                ?: "/bin/$name"
        }
    }

    // —— 登录 shell 真实 PATH 探测（GUI 启动补偿） ——

    /** 探测结果（null = 未探测到 / Windows 不需要）；[probedPathDone] 置位后不再重探。 */
    @Volatile
    private var probedPath: String? = null
    @Volatile
    private var probedPathDone: Boolean = false

    val resolvedPath: String?
        get() {
            if (isWindows) return null
            if (probedPathDone) return probedPath
            synchronized(this) {
                if (probedPathDone) return probedPath
                probedPath = runCatching { probeLoginShellPath() }.getOrNull()
                probedPathDone = true
            }
            return probedPath
        }

    /**
     * adb server 端口（null = 不注入，沿用默认端口）。
     *
     * 用内置 adb 时置为专用端口，让它的 server 与本机既有 adb server 并存——同一个
     * 端口上不同版本的 client 会互相结束对方的 server，导致两边设备列表周期性闪断。
     * 用户配置了自定义 adb 路径时必须置 null：那是用户自己的 adb 生态，换端口会把它
     * 从用户终端与 IDE 的连接态里摘出去。
     */
    @Volatile
    var adbServerPort: Int? = null

    @JvmStatic
    fun applyShellEnv(pb: ProcessBuilder) {
        val path = resolvedPath
        if (path != null) runCatching { pb.environment()["PATH"] = path }
        // 端口经环境变量而非命令串注入：命令文本保持与「用不用内置 adb」无关，
        // 历史命令可原样回放，重写器也不必感知端口
        adbServerPort?.let { port ->
            runCatching { pb.environment()["ANDROID_ADB_SERVER_PORT"] = port.toString() }
        }
    }

    /**
     * 子进程工作目录定音 = 用户主目录：打包启动与 gradle run 两形态的进程 cwd
     * 分别是安装目录/工程根——相对路径命令的产物落点随启动方式漂移；user.home
     * 恒存在可写，落点确定。SimpleShell 与 StreamingShell 的 pb 装配点必须同挂。
     */
    @JvmStatic
    fun applyWorkingDir(pb: ProcessBuilder) {
        pb.directory(File(System.getProperty("user.home")))
    }

    /** 一次性探测：`<shell> -l -i -c 'printf %s "$PATH"'`，3s 死线，末行 + isDirectory 双滤。 */
    private fun probeLoginShellPath(): String? {
        val shell = resolvedShellPaths.getValue(shellType)
        val pb = ProcessBuilder(shell, "-l", "-i", "-c", "printf %s \"\$PATH\"")
        pb.redirectInput(File("/dev/null"))
        val proc = runCatching { pb.start() }.getOrNull() ?: return null
        // 读放后台线程（readLine 是阻塞读，rc 挂死时主线程死线检查永远执行不到）；
        // 主线程 waitFor(3s) 超时即 destroyForcibly ⇒ 管道 EOF ⇒ 读线程自然解除阻塞
        val readerThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        probedLines.append(line).append('\n')
                    }
                }
            }
        }
        readerThread.isDaemon = true
        readerThread.start()
        runCatching { proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { proc.destroyForcibly() }   // 超时/已退出都 destroy（幂等），管道 EOF 收尾读线程
        runCatching { readerThread.join(1_000) }
        val lastLine = probedLines.toString().lineSequence().lastOrNull { it.isNotBlank() } ?: return null
        val filtered = lastLine.split(File.pathSeparatorChar)
            .filter { it.isNotBlank() && File(it).isDirectory }
        if (filtered.isEmpty()) return null
        return filtered.joinToString(File.pathSeparator)
    }

    /** 探测进程的 stdout 累积缓冲（仅 [probeLoginShellPath] 的读线程写，读线程 join 后才读）。 */
    private val probedLines = StringBuilder()

    fun wrap(command: String): Array<String> = when {
        isWindows -> arrayOf("cmd.exe", "/c", command)
        else -> arrayOf(resolvedShellPaths.getValue(shellType), "-l", "-c", command)
    }
}
