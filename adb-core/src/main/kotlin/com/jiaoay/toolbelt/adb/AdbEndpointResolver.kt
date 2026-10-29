package com.jiaoay.toolbelt.adb

/**
 * 端点解析：决定一次 adb 调用该用哪个可执行文件、要不要专用 server 端口。
 *
 * 只有一支：**随包内置**。用随包释出的绝对路径，并带上它专用的 server 端口——同一个
 * 端口上不同版本的 adb client 会互相结束对方的 server，内置 adb 用专用端口才能与用户
 * 机器上既有的 adb 生态（IDE、终端）并存。
 *
 * ## 为什么不提供「系统 PATH 回退」或「自定义路径」
 *
 * 桌面应用以用户身份运行、写的是自己的数据目录，释放内置二进制的失败面只剩磁盘写满
 * 一类极端情况，且都可由 UI 提示让用户自行处置——为它保留一条隐式搜索路径，换来的是
 * 「用户看到的行为取决于机器上装了哪个版本的 adb」这一类不可控差异。
 * 需要用自己的 adb 的开发者，直接用终端即可，本工具的价值在于把多条命令编排好一次执行。
 *
 * 因此解析失败时**不静默换支**：返回带原因的失败，由调用面给人话与处置建议。
 */
class AdbEndpointResolver(
    private val releaser: AdbBinaryReleaser,
) {

    /** 最近一次解析失败的原因（null = 正常）；供设置页与诊断面呈现 */
    @Volatile
    var lastIssue: String? = null
        private set

    /**
     * 解析当前应使用的端点。
     *
     * 返回 null 表示内置 adb 不可用——调用方必须把 [lastIssue] 送达用户可见面，
     * 不得当成「没有设备」静默处理。
     */
    fun resolve(): AdbEndpoint? = when (val outcome = releaser.ensureReleased()) {
        is AdbReleaseOutcome.Released -> {
            lastIssue = null
            AdbEndpoint(outcome.executable.absolutePath, AdbEndpoint.BUNDLED_SERVER_PORT)
        }
        is AdbReleaseOutcome.AlreadyPresent -> {
            lastIssue = null
            AdbEndpoint(outcome.executable.absolutePath, AdbEndpoint.BUNDLED_SERVER_PORT)
        }
        is AdbReleaseOutcome.Conflict -> {
            lastIssue = outcome.hint
            null
        }
        is AdbReleaseOutcome.Failed -> {
            lastIssue = outcome.reason
            null
        }
    }

    /** 预热：启动期后台触发一次释放，避免首次设备检测承担十余 MB 落盘延迟 */
    fun warmup() {
        resolve()
    }
}
