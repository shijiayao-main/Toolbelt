package com.jiaoay.easy_dev_toolbox.core.system

import com.jiaoay.easy_dev_toolbox.core.extensions.ProcessTreeKiller
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次 argv 执行的回执。
 *
 * @param exitCode 子进程退出码原样上报（被 SIGTERM 终止常见 143；取不到时为 -1 兜底）。
 *   退出码是**诊断数据**不是失败信号
 *   （只读探查命令常见非零语义：pgrep 无匹配 = 1、systemctl status 单元不存在 = 非零、
 *   printenv 部分变量未设置 = 1），由调用方按命令自身语义解读
 * @param output stdout 与 stderr 合流输出（超 [ArgvProcessRunner.captureLimitChars] 上限被丢弃，
 *   见 [outputTruncated]）
 * @param outputTruncated 输出超捕获上限被丢弃（进程照常跑完，回执只含前缀）
 * @param timedOut 总超时触发（进程已被进程树级联终止，退出码取 -1 兜底）
 */
data class ArgvOutcome(
    val exitCode: Int,
    val output: String,
    val outputTruncated: Boolean,
    val timedOut: Boolean,
)

/**
 * argv 纯语义进程执行原语：无 shell 解释器、无字符串拼接、argv 数组直传 ProcessBuilder。
 * 只读诊断域（sys.* 工具族）的唯一执行件。
 *
 * 本文件是 core 侧 argv 语义诊断域唯一允许出现 [ProcessBuilder] 之处
 *   （CoreProcessBuilderAllowlistGuardTest 钉死，违反即红构建——真门禁由守护测试承载，不依赖标记文字）。不复用 core/extensions 的
 *   SimpleShell/StreamingShell/InteractiveShell——那是 shell 命令行语义
 *   （必经 ShellCommandBuilder.wrap 走 cmd.exe / zsh -l），LLM 驱动的白名单命令面
 *   必须保持 argv 结构语义：命令串拼接面在 shell 语义下不可防御注入。
 *   lark-bridge 的 exec/ProgramLauncher 是同语义蓝本，但该模块是 jvmTarget 11 纯 JVM lib、
 *   与 core 互不可见（模块依赖方向），kill 升级链配方在此逻辑平移。
 *
 * ## 执行面硬防线（不信任调用方纪律，黑名单在执行件内恒拒）
 * - 提权/包管理可执行文件（sudo/su/apt/pip 等，[BLOCKED_EXECUTABLES]）恒拒；
 * - systemctl / journalctl 不带 --user 恒拒（系统级服务/日志面不在只读诊断域内）。
 *
 * ## 结构性设计（对照「等待退出后再读输出」的朴素写法）
 * - 输出超 OS 管道缓冲（fcitx5-diagnose 量级即可超 64KB）时，退出后补读会写阻塞死锁——
 *   输出经独立 scope 的排干协程并发读取（独立 scope 非 withContext 子协程：
 *   阻塞在不可取消 read 上的排干协程若挂在调用方 scope 上，join 上限会被架空，
 *   ProgramLauncher 同型坑的平移）；
 * - 看门狗：[timeoutMillis] 总超时 → 进程树级联终止（SIGTERM→3s→SIGKILL→2s→放行 -1），
 *   整序列包在 NonCancellable 内；
 * - 取消联动：协程取消原样上抛（不当执行失败），finally 内 NonCancellable 杀净进程树防孤儿子进程；
 * - 捕获上界 [captureLimitChars]：超限丢弃残量但持续排干（防进程写阻塞挂死），回执标记截断。
 *
 * 契约：环境变量全量继承不裁剪——诊断对象就是当前桌面会话环境（printenv/gsettings 依赖之），
 * 输出侧脱敏由工具层负责，本原语不冒充脱敏。字符集恒 UTF-8（目标平台 Linux 桌面 UTF-8 locale；
 * Windows ANSI 陷阱不适用于 argv 语义域，见 docs/core/shell.md 两种语义分工节）。
 */
class ArgvProcessRunner(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val captureLimitChars: Int = DEFAULT_CAPTURE_LIMIT_CHARS,
) {

    private val tag = "ArgvProcessRunner"

    /**
     * 执行一条 argv 命令并等待退出（stdout/stderr 合流捕获）。
     *
     * 契约：超时与非零退出码**不上抛**（按 [ArgvOutcome] 数据回执）；失败走异常：
     *
     * @param argv argv 数组直传（无 shell 语义）
     * @param cwd 工作目录（null = 继承当前进程 cwd——sys.* 只读探查语义；
     *   ws.run_task 构建任务注入工作区根）
     * @param onLine 输出行旁路（排干协程线程调用，每新行回调一次；null = 不旁路）。
     *   长任务的**活动信号通道**：调用方借它喂空闲看门狗（touch），防「任务在跑但无进度事件」
     *   被误判卡死。回调抛异常按吞掉处理（旁路绝不断主流程）
     *
     * @throws IllegalArgumentException argv 非法/黑名单命中/缺 --user（拒绝理由在人话 message 内）
     * @throws IOException 可执行文件不存在/启动失败
     * @throws kotlinx.coroutines.CancellationException 协程取消原样上抛（子进程已级联终止）
     */
    suspend fun run(
        argv: List<String>,
        cwd: File? = null,
        onLine: ((String) -> Unit)? = null,
    ): ArgvOutcome {
        policyRejectReason(argv)?.let { throw IllegalArgumentException(it) }
        return withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder(argv)
                    .apply {
                        if (cwd != null) directory(cwd)
                        // adb server 端口：argv 通道不走 ShellCommandBuilder.applyShellEnv，
                        // 端口须在此单独注入，否则用内置 adb 的诊断命令会打到默认端口的
                        // 另一个 server 上，设备列表与其余链路不一致
                        com.jiaoay.easy_dev_toolbox.core.extensions.ShellCommandBuilder.adbServerPort?.let { port ->
                            environment()["ANDROID_ADB_SERVER_PORT"] = port.toString()
                        }
                    }
                    .redirectErrorStream(true)
                    .start()
            } catch (e: IOException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            }
            // stdin 立即关闭（防子进程等输入挂死——探查命令不应读输入）
            runCatching { process.outputStream.close() }
            val sink = BoundedCapture(captureLimitChars, onLine)
            val drainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                drainScope.launch { sink.captureFrom(process.inputStream) }
                val completed = withTimeoutOrNull(timeoutMillis) {
                    while (process.isAlive) delay(POLL_INTERVAL_MILLIS)
                    true
                }
                val timedOut = completed == null
                if (timedOut) killChain(process)
                // 进程已死即放弃等排干（句柄泄漏/孙进程僵死管道不 EOF 场景不挂死）：
                // 进程树已级联销毁，管道随句柄关闭自然 EOF，残协程随 scope.cancel 回收
                withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MILLIS) {
                    while (!sink.closed) delay(POLL_INTERVAL_MILLIS)
                }
                drainScope.cancel()
                val exit = runCatching { process.exitValue() }.getOrDefault(-1)
                val outcome = ArgvOutcome(
                    exitCode = exit,
                    output = sink.snapshot(),
                    outputTruncated = sink.truncated,
                    timedOut = timedOut,
                )
                JLog.d(tag) {
                    "probe done: ${argv.first()} exit=$exit" +
                        (if (timedOut) " timedOut(>${timeoutMillis}ms)" else "") +
                        (if (outcome.outputTruncated) " outputTruncated" else "")
                }
                outcome
            } finally {
                drainScope.cancel()
                withContext(NonCancellable) {
                    if (process.isAlive) killChain(process)
                }
            }
        }
    }

    // —— 执行面硬防线 ——

    /**
     * argv 策略校验（执行件内恒拒，不信任调用方纪律）：返回 null = 放行；非 null = 拒绝理由。
     */
    internal fun policyRejectReason(argv: List<String>): String? {
        if (argv.isEmpty()) return "argv 不能为空"
        if (argv.any { it.isBlank() }) return "argv 元素不能为空白"
        val executable = File(argv.first()).name
        if (executable in BLOCKED_EXECUTABLES) {
            return "可执行文件「$executable」在只读诊断域黑名单内（提权/包管理面恒拒，不走白名单流程）"
        }
        if (executable == "systemctl" && "--user" !in argv) {
            return "systemctl 必须带 --user（系统级服务面不在只读诊断域内）"
        }
        if (executable == "journalctl" && "--user" !in argv) {
            return "journalctl 必须带 --user（系统日志面不在只读诊断域内）"
        }
        return null
    }

    // —— 内部实现 ——

    /**
     * kill 升级链（ProgramLauncher 配方平移）：SIGTERM→[TERM_GRACE_MILLIS]→SIGKILL→
     * [KILL_GRACE_MILLIS]→放行（进程已死，退出码由调用方取 -1 兜底）。
     * 进程树级联终止：只杀直接子进程不够——探查命令可能派生孙进程，逐级对整棵树发信号。
     */
    private suspend fun killChain(process: Process) = withContext(NonCancellable) {
        if (!process.isAlive) return@withContext
        destroyTree(process, force = false)
        if (awaitExit(process, TERM_GRACE_MILLIS)) return@withContext
        destroyTree(process, force = true)
        awaitExit(process, KILL_GRACE_MILLIS)
    }

    /**
     * 进程树终止：转调 [ProcessTreeKiller] 单源（shell 语义三件共用同一实现，
     * 叶子优先 + 先快照再终止的纪律不得在两个地方各写一份）。
     */
    private fun destroyTree(process: Process, force: Boolean) {
        ProcessTreeKiller.destroyTree(process, force)
    }

    /** 轮询等待退出（协程时钟友好：虚拟时间可测逻辑，真实进程可测 kill 语义） */
    private suspend fun awaitExit(process: Process, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (process.isAlive) delay(POLL_INTERVAL_MILLIS)
            true
        } ?: !process.isAlive

    /**
     * 有界输出捕获：超 [limitChars] 丢弃残量但持续读到 EOF（防进程写阻塞挂死）。
     * 写入仅发生在排干协程；读取经 synchronized 与写入互斥（join 超时放弃等待时
     * 不读未冻结的半态）。[onLine] 旁路在捕获主流程内按行分派（行边界由换行符切分，
     * 最后一行 EOF 时无换行也补发）。
     */
    private class BoundedCapture(
        private val limitChars: Int,
        private val onLine: ((String) -> Unit)? = null,
    ) {
        private val sb = StringBuilder()
        private var count = 0

        @get:Synchronized
        var truncated = false
            private set

        @get:Synchronized
        var closed = false
            private set

        fun captureFrom(stream: InputStream) {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(8192)
                val pending = StringBuilder()
                while (true) {
                    val n = try {
                        reader.read(buf)
                    } catch (_: IOException) {
                        -1 // 流被 kill 关闭 → 视作 EOF
                    }
                    if (n < 0) break
                    if (onLine != null) {
                        pending.append(buf, 0, n)
                        // 按行切分旁路：完整行才回调（半行留在 pending）
                        var idx = pending.indexOf('\n')
                        while (idx >= 0) {
                            val line = pending.substring(0, idx).trimEnd('\r')
                            runCatching { onLine(line) }
                            pending.delete(0, idx + 1)
                            idx = pending.indexOf('\n')
                        }
                        // pending 无上界保护：旁路行缓冲在超限捕获场景会随截断停止增长
                        //（append 截断后 reader 不再喂入），但正常长流的半行可达 chunk 大小，
                        // 故旁路模式下 pending 满 8192 字符强制冲刷一次（丢行边界，保内存上界）
                        if (pending.length >= 8192) {
                            val line = pending.toString()
                            runCatching { onLine(line) }
                            pending.setLength(0)
                        }
                    }
                    append(buf, n)
                }
                if (onLine != null && pending.isNotEmpty()) {
                    runCatching { onLine(pending.toString()) }
                }
            }
            markClosed()
        }

        @Synchronized
        private fun append(buf: CharArray, n: Int) {
            if (count >= limitChars) {
                truncated = true
                return
            }
            val room = limitChars - count
            if (n > room) {
                sb.append(buf, 0, room)
                count = limitChars
                truncated = true
            } else {
                sb.append(buf, 0, n)
                count += n
            }
        }

        @Synchronized
        private fun markClosed() {
            closed = true
        }

        @Synchronized
        fun snapshot(): String = sb.toString()
    }

    companion object {
        /** 探查命令总超时（ms）：只读探查命令应在秒级完成，20s 覆盖 fcitx5-diagnose 量级 */
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        /** 输出捕获上界（字符）：探查回执在工具层另有 8K 渲染上界，本上界防内存无界 */
        const val DEFAULT_CAPTURE_LIMIT_CHARS = 256 * 1024

        /** SIGTERM 宽限期（ms）——destroy() 后等 3s（ProgramLauncher 配方平移） */
        const val TERM_GRACE_MILLIS = 3_000L

        /** SIGKILL 宽限期（ms）——destroyForcibly() 后等 2s（ProgramLauncher 配方平移） */
        const val KILL_GRACE_MILLIS = 2_000L

        /** 退出轮询间隔（ms） */
        const val POLL_INTERVAL_MILLIS = 50L

        /** 排干协程收尾上限（ms）：进程已死即放弃等待，防句柄泄漏场景挂死 */
        const val STREAM_DRAIN_TIMEOUT_MILLIS = 1_500L

        /**
         * 提权/包管理可执行文件黑名单（按 basename 匹配）：只读诊断域永不承载提权与
         * 安装面（能力定案的负面清单），执行件内恒拒——不信任调用方守 prompt 约定。
         */
        val BLOCKED_EXECUTABLES: Set<String> = setOf(
            "sudo", "su", "doas", "pkexec", "gksu", "gksudo",
            "apt", "apt-get", "dpkg", "pip", "pip3", "snap", "pkcon",
            "dnf", "yum", "zypper", "pacman", "brew",
        )
    }
}
