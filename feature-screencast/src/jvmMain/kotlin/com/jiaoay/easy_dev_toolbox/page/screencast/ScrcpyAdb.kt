package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellResult
import com.jiaoay.easy_dev_toolbox.core.extensions.StreamingShell
import com.jiaoay.easy_dev_toolbox.core.extensions.StreamingShellHandle
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter

/** 一次性 adb 命令执行接缝（push / forward / reverse / remove 收尾）。
 * 命令串由 [ScrcpyServerDeployer] 整形，重写与执行解耦——单测以 fake 注入，不真跑 adb。
 * 取消契约：协程取消原样上抛、不并入 Failure（生产实现经 [SimpleShell] 在 :core 收口）；
 * 实现方把取消装进 Failure 时，消费面的失败分支会误触发 fallback/收口跳过。 */
fun interface ScrcpyAdbGateway {
    /** 执行一条一次性命令，返回含 exitCode/stdout/stderr 的结果（进程启动失败为 Failure） */
    suspend fun execute(command: String): Result<ShellResult>
}

/** 生产实现：经 [AdbCommandRewriter] 注入自定义 adb 路径与设备串号后走 [SimpleShell]。
 * 串号注入对全部 deploy 命令生效（含 serve 命令的 `adb shell ...` 形态）；空 adbPath 保持裸 adb。 */
class SimpleShellScrcpyAdbGateway(
    private val adbPath: String = "",
    private val serial: String? = null,
) : ScrcpyAdbGateway {
    override suspend fun execute(command: String): Result<ShellResult> =
        SimpleShell.execute(AdbCommandRewriter.rewrite(command, adbPath, serial))
}

/** server 长跑流执行接缝（LogcatStreamRunner 同构）：[run] 挂起至流终止后返回 exitCode
 * （进程启动失败返回 null），[stop] 为唯一终止入口。 */
interface ScrcpyServerProcessRunner {
    suspend fun run(command: String, onLine: suspend (String) -> Unit): Int?
    fun stop()
}

/** 生产实现：[StreamingShell] 直跑 `adb shell app_process` 长跑流。
 * stdout = server 日志诊断面（server 日志全走 stdout），stderr = 拉起失败第一错误源；
 * 停止按钮语义 = [stop] 置位收割链（3s 宽限 → destroyForcibly），与实时抓取引擎同配方。 */
class StreamingShellScrcpyRunner : ScrcpyServerProcessRunner {

    @Volatile
    private var currentHandle: StreamingShellHandle? = null

    override suspend fun run(command: String, onLine: suspend (String) -> Unit): Int? {
        currentHandle = null
        val result = StreamingShell.executeStreaming(
            command = command,
            onStarted = { handle -> currentHandle = handle },
            onLine = { output -> onLine(output.line) },
        )
        currentHandle = null
        return result.fold(
            onSuccess = { it.exitCode },
            onFailure = { e ->
                JLog.e(TAG) { "scrcpy server stream failed to start: ${e.message}" }
                null
            },
        )
    }

    override fun stop() {
        currentHandle?.kill()
    }

    private companion object {
        const val TAG = "ScrcpyRunner"
    }
}
