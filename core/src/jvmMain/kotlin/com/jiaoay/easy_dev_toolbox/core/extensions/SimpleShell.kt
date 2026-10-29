package com.jiaoay.easy_dev_toolbox.core.extensions

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

object SimpleShell {

    private const val TAG = "SimpleShell"

    /**
     * 执行一条一次性 shell 命令。
     *
     * 取消契约：协程取消原样上抛，**不并入 Failure**——Failure 仅承载进程级真失败
     * （启动失败/读写异常）。调用方把取消误读为「命令失败」会错误触发 fallback 链、
     * 错误状态面或收口跳过（退出码/输出语义见 [ShellResult]）。
     *
     * 取消的到达点：[waitFor] 阻塞不响应取消，取消在进程退出后的首个挂起点
     * （输出 await）抛出；本方法无 kill 面，长命令被取消后子进程跑完自然退出
     * （destroy 在 finally 收口，仅在异常/取消路径有实际动作）。
     */
    suspend fun execute(vararg cmd: String): Result<ShellResult> = withContext(Dispatchers.IO) {
        val msg = cmd.joinToString(separator = " ")
        JLog.d(TAG) { "execute: $msg" }
        try {
            val wrapped = ShellCommandBuilder.wrap(msg)
            val pb = ProcessBuilder(*wrapped)
            ShellCommandBuilder.applyShellEnv(pb)   // 🔴 注入探测到的登录 shell PATH（非交互 shell 缺用户 PATH 段会丢命令）
            ShellCommandBuilder.applyWorkingDir(pb) // 子进程工作目录定音（与 StreamingShell 同挂）
            val process = pb.start()
            // 管道编码按命令族定：adb 恒 UTF-8，其余走控制台代码页（见 [ShellCommandBuilder.charsetFor]）
            val charset = ShellCommandBuilder.charsetFor(msg)
            val shellResult = try {
                val stdoutDeferred = async {
                    process.inputStream.bufferedReader(charset).use { it.readText() }
                }

                val stderrDeferred = async {
                    process.errorStream.bufferedReader(charset).use { it.readText() }
                }

                val exitCode = process.waitFor()
                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()

                JLog.d(TAG) { "execute finish: $msg, exitCode=$exitCode" }
                ShellResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
            } finally {
                // 异常/取消路径同样收口子进程：await 因取消早退时进程未销毁即成孤儿。
                // 必须树杀——destroy() 只杀 shell 解释器，孙进程（真正的命令）会被
                // 收养成孤儿（`adb logcat &` 一类尤其明显）。
                ProcessTreeKiller.destroyTree(process, force = false)
            }
            Result.success(shellResult)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure<ShellResult>(t)
        }
    }
}
