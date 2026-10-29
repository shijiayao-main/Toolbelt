package com.jiaoay.easy_dev_toolbox.core.device

import com.jiaoay.easy_dev_toolbox.core.extensions.ShellCommandBuilder
import com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell
import com.jiaoay.easy_dev_toolbox.core.extensions.StreamingShell
import com.jiaoay.toolbelt.device.AdbProcessHandle
import com.jiaoay.toolbelt.device.AdbProcessRunner
import com.jiaoay.toolbelt.device.ShellOutput
import com.jiaoay.toolbelt.device.ShellOutputSource
import com.jiaoay.toolbelt.device.ShellResult

/**
 * 设备域执行通道的宿主实现：把 :device-core 的执行语义接到本机 shell 通道上。
 *
 * 为什么必须由宿主提供而不是放进 lib：本机命令要经登录 shell 包裹并注入用户 PATH
 * （GUI 启动时环境不完整），还要按命令族选管道编码——这些都是宿主平台知识，
 * lib 保持零平台耦合才能被任意 JVM 项目复用。
 */
object SystemAdbProcessRunner : AdbProcessRunner {

    override suspend fun execute(command: String): Result<ShellResult> =
        SimpleShell.execute(command).map { it.toDeviceResult() }

    override suspend fun executeStreaming(
        command: String,
        onStarted: (AdbProcessHandle) -> Unit,
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<ShellResult> = StreamingShell.executeStreaming(
        command,
        onStarted = { handle -> onStarted(AdbProcessHandle { handle.kill() }) },
        onLine = { coreLine ->
            onLine(
                ShellOutput(
                    line = coreLine.line,
                    source = when (coreLine.source) {
                        com.jiaoay.easy_dev_toolbox.core.extensions.ShellOutputSource.STDOUT -> ShellOutputSource.STDOUT
                        com.jiaoay.easy_dev_toolbox.core.extensions.ShellOutputSource.STDERR -> ShellOutputSource.STDERR
                    },
                ),
            )
        },
    ).map { it.toDeviceResult() }

    private fun com.jiaoay.easy_dev_toolbox.core.extensions.ShellResult.toDeviceResult(): ShellResult =
        ShellResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
}

/**
 * 命令串变换缝：把重写后的命令交给执行通道前，按当前端点决定是否注入专用端口。
 *
 * 端口注入落在进程环境（`ANDROID_ADB_SERVER_PORT`）而不是命令文本：命令串保持与
 * 「用不用内置 adb」无关，历史命令可原样回放，重写器也不必感知端口。
 */
internal fun applyEndpointToShellEnv(endpoint: com.jiaoay.toolbelt.adb.AdbEndpoint) {
    ShellCommandBuilder.adbServerPort = endpoint.serverPort
}
