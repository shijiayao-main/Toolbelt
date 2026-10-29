package com.jiaoay.toolbelt.device

/**
 * 一次命令执行的结果。
 *
 * [exitCode] 非零不等于「命令没跑起来」——adb 对设备侧失败（设备未授权、包名不存在等）
 * 同样返回非零并把人话写在 stderr 里，调用方要按语义分流，不能只看非零就报「执行失败」。
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** 流式输出来源：区分设备端正常输出与错误诊断。 */
enum class ShellOutputSource {
    STDOUT,
    STDERR,
}

/** 流式输出的一行。 */
data class ShellOutput(
    val line: String,
    val source: ShellOutputSource,
)

/**
 * 在途流式命令的终止句柄：交给调用方，用于用户主动中断（拉取取消、抓取停止）。
 *
 * 与 [AdbProcessRunner] 分离是测试缝——fake runner 不需要真实现终止语义。
 */
fun interface AdbProcessHandle {
    /** 请求终止；实现须连子进程树一起收，只杀包装 shell 会留下孤儿。幂等。 */
    fun kill()
}

/**
 * adb 命令的执行通道：由宿主注入（宿主侧走登录 shell 包裹并注入 PATH），
 * 本模块只依赖语义不依赖实现。
 *
 * 单抽象方法（[execute]）使其可按 lambda 构造——测试注入 fake 零真子进程即是一行。
 * [executeStreaming] 有默认实现：按一次性执行后在输出上回放，供不需要真流式的
 * 调用方与 fake 复用；宿主实现覆盖它以提供逐行实时回调与可中断句柄。
 */
fun interface AdbProcessRunner {

    /** 执行一次性命令，返回完整输出 */
    suspend fun execute(command: String): Result<ShellResult>

    /**
     * 执行流式命令，逐行回调。
     *
     * [onStarted] 在进程启动后立即调用并交出终止句柄——调用方要能在命令跑完前中断它。
     * 默认实现退化为一次性执行后回放：进程已经跑完，句柄是空实现，取消语义由
     * 覆盖方承载。返回值为命令终态（取消时按实现的取消契约上抛，不折成失败）。
     */
    suspend fun executeStreaming(
        command: String,
        onStarted: (AdbProcessHandle) -> Unit = {},
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<ShellResult> = execute(command).also { result ->
        onStarted(AdbProcessHandle { /* 一次性通道无在途进程可终止 */ })
        result.getOrNull()?.let { shell ->
            for (line in shell.stdout.lineSequence()) {
                onLine(ShellOutput(line, ShellOutputSource.STDOUT))
            }
            for (line in shell.stderr.lineSequence()) {
                onLine(ShellOutput(line, ShellOutputSource.STDERR))
            }
        }
    }
}

/**
 * 日志缝：本模块不绑定具体日志后端（宿主可接自己的日志系统，也可整体丢弃）。
 *
 * 用函数式接口而非抽象类：宿主侧一行 lambda 即可接上，无需额外适配类。
 */
fun interface AdbLog {
    fun debug(tag: String, message: () -> String)

    companion object {
        /** 丢弃全部日志（测试与嵌入式场景的默认实现） */
        val NONE: AdbLog = AdbLog { _, _ -> }
    }
}
