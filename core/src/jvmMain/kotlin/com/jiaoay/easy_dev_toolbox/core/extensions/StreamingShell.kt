package com.jiaoay.easy_dev_toolbox.core.extensions

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 流式 shell 执行器的进程句柄。
 *
 * kill 升级链的第一级：置位 [killRequested]（收割条件的唯一触发源）并
 * 请求温和终止（destroy / SIGTERM）；进程忽略信号时的升级强杀由 [StreamingShell.executeStreaming]
 * 的收割循环限时完成。
 */
class StreamingShellHandle internal constructor(private val process: Process) {

    /** 终止已请求标记（volatile）：收割循环据此启动"3s 等待 → destroyForcibly → 2s 等待 → 放行"升级链 */
    @Volatile
    var killRequested: Boolean = false
        private set

    /**
     * 请求终止进程（幂等，可安全多次调用）：置位 killRequested + 树杀（SIGTERM）。
     * 树杀而非单点：直接子进程是 shell 解释器，孙进程才是真正的命令——
     *    只杀解释器会让 `adb logcat` 一类命令变孤儿并继续占管道与端口。
     */
    fun kill() {
        killRequested = true
        ProcessTreeKiller.destroyTree(process, force = false)
    }
}

object StreamingShell {

    private const val TAG = "StreamingShell"

    /** waitFor 返回后等待读流协程 EOF 的兜底超时，防止派生继承 stdout 子进程时永久阻塞 */
    const val STREAM_DRAIN_TIMEOUT_MS = 1500L

    /** 温和终止（SIGTERM）后等待进程退出的宽限上限，超过则升级 destroyForcibly */
    const val TERMINATION_GRACE_MS = 3000L

    /** 强制终止（SIGKILL）后再等待的兜底上限，超过则放行执行流程并记录日志 */
    const val FORCE_KILL_WAIT_MS = 2000L

    /** 收割循环轮询间隔：正常完成最多延迟一个间隔被感知，换取 killRequested 检查点 */
    const val HARVEST_POLL_MS = 200L

    /**
     * 流式执行一条 shell 命令。
     *
     * 输出经 [onLine] 逐行回调消费，不随结果累积返回：返回的 [ShellResult] 仅保证
     * [ShellResult.exitCode] 准确，[ShellResult.stdout] / [ShellResult.stderr] 恒为空串
     * （保持 ShellResult API 形状以兼容 SimpleShell 共享数据类；需要输出内容须订阅 onLine，
     * 执行器内部不随输出总行数累积保留全量文本，内存有界）。
     *
     * 进程退出等待为带条件的限时收割循环：仅当 [StreamingShellHandle.killRequested]
     * 置位后才启动升级链（避免正常运行的长命令被误杀），阻塞式 waitFor 无法被协程取消，
     * 故以 200ms 轮询 killRequested 作为外部触发检查点。
     *
     * @param onStarted 进程启动后回调 handle，供外部 stop() 杀进程（须在启动回调内立即保存）；
     *        回调抛异常时进程按异常路径 destroy 收尾、异常原样以 Failure 返回（已 spawn 的进程不裸奔）
     * @param onLine 每读到一行输出立即回调（含 stdout / stderr 来源）。行按 **\n** 分隔：
     *        裸 \r 不作为行终止符、保留在行内容中——进度条类输出（adb push 的
     *        "45%\r67%\r100%"）到达为一整行，覆盖语义由消费方折叠（readLine 按 \r 分行
     *        会把进度条切成碎片行并丢失覆盖语义，已弃用）
     * @return 含真实 exitCode 的 Result（stdout/stderr 为空串；进程启动失败时为 Failure；
     *         强杀放行路径 exitCode 为 -1，并伴随一条 Stderr 提示行）。
     *         取消契约同 [SimpleShell.execute]：协程取消原样上抛、不并入 Failure——
     *         取消误读为「命令失败」会错误触发调用方的失败分支（错误回执/fallback 链）。
     */
    suspend fun executeStreaming(
        command: String,
        onStarted: (StreamingShellHandle) -> Unit = {},
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<ShellResult> = withContext(Dispatchers.IO) {
        JLog.d(TAG) { "executeStreaming: $command" }
        try {
            val wrapped = ShellCommandBuilder.wrap(command)
            val pb = ProcessBuilder(*wrapped)
            ShellCommandBuilder.applyShellEnv(pb)   // 🔴 注入探测到的登录 shell PATH（非交互 shell 缺用户 PATH 段会丢命令）
            ShellCommandBuilder.applyWorkingDir(pb) // 子进程工作目录定音（相对路径产物落点确定）
            val process = pb.start()
            val handle = StreamingShellHandle(process)

            var exitCode = -1
            var forcedGiveUp = false
            // 🔴 读流协程必须移出 coroutineScope（coroutineScope 会挂起等全部子协程，读流不结束即整命令不返回）。
            //    若读协程是 coroutineScope 的子协程——withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MS)
            //    只放弃 join，但 coroutineScope 在块结束前仍会等全部子协程完成，而读协程阻塞在
            //    **不可取消**的 reader.readLine() 上 ⇒ 孙进程持有管道时 1.5s 兜底被架空，
            //    executeStreaming 永不返回。改为在 scope 外异步启动（异步上下文仍在 Dispatchers.IO），
            //    join 超时后直接放过：进程已 kill、exitCode 已准确，残行由管道关闭自然终止。
            val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            // 管道编码按命令族定：adb 恒 UTF-8，其余走控制台代码页（见 [ShellCommandBuilder.charsetFor]）
            val charset = ShellCommandBuilder.charsetFor(command)
            // 读协程的管道 IOException 按流终止处理：进程被 destroy/强杀后 process reaper
            // 收割时并发关流，阻塞读醒来抛「Stream closed」而非 EOF——若带异常逃逸，
            // 读协程在 executeStreaming 返回后以未捕获异常落地（测试环境下污染后续用例）。
            val stdoutJob = streamScope.launch {
                try {
                    drainLines(process.inputStream.bufferedReader(charset)) { line ->
                        onLine(ShellOutput(line = line, source = ShellOutputSource.STDOUT))
                    }
                } catch (e: IOException) {
                    JLog.d(TAG) { "stdout reader terminated by pipe close: ${e.message}" }
                }
            }
            val stderrJob = streamScope.launch {
                try {
                    drainLines(process.errorStream.bufferedReader(charset)) { line ->
                        onLine(ShellOutput(line = line, source = ShellOutputSource.STDERR))
                    }
                } catch (e: IOException) {
                    JLog.d(TAG) { "stderr reader terminated by pipe close: ${e.message}" }
                }
            }
            try {
                // onStarted 在 try 内：消费方回调抛异常时 finally 的 destroy 照常收尾——
                // 进程不得因回调失败而裸奔（spawn 后无人终止即成孤儿进程）。
                onStarted(handle)
                // 收割循环（读流进行中，管道不会因写满而阻塞进程）：
                // 200ms 轮询 killRequested → 正常完成 / 3s 宽限 → destroyForcibly → 2s → 放行
                val harvest = harvestExit(process, handle)
                exitCode = harvest.exitCode
                forcedGiveUp = harvest.forcedGiveUp

                // 命令若派生继承 stdout 的子进程（如 `adb logcat &`），管道不会 EOF，
                // 读循环永不结束；超时放弃等待即可，exitCode 已准确
                withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MS) {
                    stdoutJob.join()
                    stderrJob.join()
                }
            } finally {
                // 退出前尽力收尾读协程：进程销毁后管道 EOF，readLine 自然返回 null。
                // join(1s) 防个别 fd 泄漏场景悬挂；streamScope.cancel() 兜底回收。
                runCatching { process.destroy() }
                withTimeoutOrNull(1_000) {
                    stdoutJob.join()
                    stderrJob.join()
                }
                streamScope.cancel()
            }

            if (forcedGiveUp) {
                onLine(
                    ShellOutput(
                        line = "进程未响应强制终止（已放弃等待，命令可能仍在后台运行）",
                        source = ShellOutputSource.STDERR,
                    )
                )
            }

            runCatching { process.destroy() }

            JLog.d(TAG) { "executeStreaming finish: $command, exitCode=$exitCode" }
            Result.success(ShellResult(exitCode = exitCode, stdout = "", stderr = ""))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure<ShellResult>(t)
        }
    }

    /** 收割结果：forcedGiveUp = 强杀后仍未退出而放行（区别于真实退出码，exitCode 取 -1） */
    private class HarvestResult(val exitCode: Int, val forcedGiveUp: Boolean)

    /**
     * 按 \n 分行读取（不用 readLine：它把裸 \r 也当行终止符，进度条输出会被
     * 切成碎片行且丢失「覆盖前行」语义——\r 保留在行内容中交消费方折叠）。
     * 语义对齐 readLine：EOF 时残余非空行照常回调；连续 \n 产出空行。
     */
    private suspend fun drainLines(
        reader: java.io.BufferedReader,
        onLine: suspend (String) -> Unit,
    ) {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            when {
                c == -1 -> {
                    if (line.isNotEmpty()) onLine(line.toString())
                    return
                }
                c.toChar() == '\n' -> {
                    onLine(line.toString())
                    line.clear()
                }
                else -> line.append(c.toChar())
            }
        }
    }

    /**
     * 收割循环：
     * - killRequested 未置位时以 200ms 轮询窗口等待正常完成（不设总上限，长命令不受影响）
     * - killRequested 置位（手动停止 / 看门狗超时）后：等待至多 [TERMINATION_GRACE_MS]，
     *   仍未退出则 destroyForcibly，再等待至多 [FORCE_KILL_WAIT_MS]，仍未退出则放行（-1）
     */
    private fun harvestExit(process: Process, handle: StreamingShellHandle): HarvestResult {
        while (true) {
            if (handle.killRequested) {
                // 升级链前提成立：终止已请求
                if (process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    return HarvestResult(process.exitValue(), forcedGiveUp = false)
                }
                JLog.w(TAG) {
                    "process did not respond to SIGTERM within ${TERMINATION_GRACE_MS}ms, destroying forcibly"
                }
                // 树杀：直接子进程是 shell 解释器，孙进程才是真正的命令——
                //    只杀解释器会把 `adb logcat` 一类命令留成孤儿并继续占管道。
                ProcessTreeKiller.destroyTree(process, force = true)
                if (process.waitFor(FORCE_KILL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    return HarvestResult(process.exitValue(), forcedGiveUp = false)
                }
                JLog.e(TAG) {
                    "process unresponsive even to SIGKILL (pid=${runCatching { process.pid() }
                        .getOrDefault(-1)}), giving up to keep execution queue alive"
                }
                return HarvestResult(-1, forcedGiveUp = true)
            }
            if (process.waitFor(HARVEST_POLL_MS, TimeUnit.MILLISECONDS)) {
                return HarvestResult(process.exitValue(), forcedGiveUp = false)
            }
        }
    }
}
