package com.jiaoay.toolbelt.adb

/**
 * 一次 adb 调用用的端点：可执行文件路径 + 是否附带专用 server 端口。
 *
 * [serverPort] 非空 ⇒ 该端点是随包内置的 adb，调用方须让它连接专用端口上的 server、
 * 与本机默认端口上的既有 adb server 生态完全隔离（同端口上不同版本的 client 会互相
 * 结束对方的 server，导致任一方的设备列表周期性闪断）。
 *
 * [serverPort] 为空 ⇒ 用户自定义路径或系统 PATH 上的 adb，沿用其既有 server 生态，
 * 不得注入端口——那会切断它与用户终端 / IDE 里 adb 的连接态。
 */
data class AdbEndpoint(
    /** 可执行文件的绝对路径（随包内置 adb 的释放产物） */
    val executablePath: String,
    /** 专用 server 端口；null = 不注入，沿用默认端口 */
    val serverPort: Int? = null,
    /**
     * 宿主平台（决定命令里的引号语义）；null = 未知系统，按 Unix 语义处理。
     *
     * 与可执行文件路径同处一个值对象：平台判定与资源选取必须同源，两处各自判定
     * 会让「跑在哪个平台」出现两种答案。
     */
    val platform: AdbPlatform? = AdbPlatform.current(),
) {
    /** 是否应注入专用端口（内置端点为 true，配置/PATH 端点为 false） */
    val usesDedicatedServer: Boolean get() = serverPort != null

    /** 传给执行环境的端口值；未启用为 null */
    val serverPortEnvValue: String? get() = serverPort?.toString()

    companion object {
        /**
         * 内置 adb 的专用 server 端口。
         *
         * 选非默认端口是为了与用户机器上既有的 adb server（IDE、命令行工具链）并存：
         * 同一个端口上不同版本的 client 会互相结束对方的 server。
         */
        const val BUNDLED_SERVER_PORT: Int = 5038
    }
}
