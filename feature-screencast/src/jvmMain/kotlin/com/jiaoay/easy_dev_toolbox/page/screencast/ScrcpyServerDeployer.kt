package com.jiaoay.easy_dev_toolbox.page.screencast

/**
 * scrcpy-server 部署命令整形：push / 隧道 / remove 收尾 / app_process 拉起。
 *
 * 命令形态与官方客户端组装序逐字对齐（`app/src/server.c` push_server 与
 * sc_server_start 的命令向量、`app/src/adb/adb.c` 的 forward/reverse 组装），
 * 本类型只做纯字符串整形，执行通道归 [ScrcpyAdbGateway] / [ScrcpyServerProcessRunner]。
 *
 * 执行通道纪律（两语义通道不混用）：
 * - push / forward / reverse / remove 为一次性命令 → [ScrcpyAdbGateway]（生产实现
 *   SimpleShell + AdbCommandRewriter 注入自定义 adb 路径与串号）；
 * - `adb shell app_process` 为长跑流（server 活多久它活多久，stdout = server 日志诊断面）
 *   → [ScrcpyServerProcessRunner]（生产实现 StreamingShell）——误走一次性命令语义会在
 *   读尽 stdout 处永久阻塞；
 * - 视频流为二进制，不经任何 shell 文本行通道，由本地 Socket 直读后交 [ScrcpyDemuxer]。
 */
object ScrcpyServerDeployer {

    /**
     * server 版本首参：必须与所部署 server jar 内置版本逐字一致——server 端
     * `Options.parse` 对 args[0] 与 BuildConfig.VERSION_NAME 做硬校验，不一致即抛异常断连，
     * 故 server jar 与本常量必须随包绑定、同批升级。
     */
    const val SERVER_VERSION = "5.0.1"

    /** 设备端部署路径（server.c `SC_DEVICE_SERVER_PATH`） */
    const val DEVICE_SERVER_PATH = "/data/local/tmp/scrcpy-server.jar"

    /** 官方默认本地端口范围（app/meson.build DEFAULT_LOCAL_PORT_RANGE 27183..27199） */
    const val PORT_RANGE_FIRST = 27183
    const val PORT_RANGE_LAST = 27199

    /** scid 上界：31 位非负（`Options.java` scid 注释），随机生成时取值域 [0, 2^31) */
    const val SCID_MAX_EXCLUSIVE = 1L shl 31

    /**
     * push server jar 到设备端定路径（覆盖旧文件；版本不符由 serve 命令首参校验兜底）。
     * 本地路径必须双引号包裹：路径含空白字符（Windows 用户目录默认形态）时裸拼即令
     * adb 断词——双引号在登录 shell 与 cmd.exe 两种执行语义下均为合法包裹（AdbCommandRewriter
     * 只包可执行 token，参数包裹归本整形点）。
     */
    fun buildPushCommand(serverJarPath: String): String =
        "adb push \"$serverJarPath\" $DEVICE_SERVER_PATH"

    /** forward 隧道：宿主端口 → 设备端 abstract socket（客户端连入形态） */
    fun buildForwardCommand(port: Int, socketName: String): String =
        "adb forward tcp:$port localabstract:$socketName"

    /** reverse 隧道：设备端 abstract socket → 宿主端口（server 反连形态，官方默认优先路径） */
    fun buildReverseCommand(port: Int, socketName: String): String =
        "adb reverse localabstract:$socketName tcp:$port"

    /** forward 规则收尾（官方 sc_adb_tunnel_close 显式 remove；不清理则规则残留至设备断开） */
    fun buildRemoveForwardCommand(port: Int): String =
        "adb forward --remove tcp:$port"

    /** reverse 规则收尾：按设备端 socket 名 remove（与建立时同源 [ScrcpyProtocol.deviceSocketName]） */
    fun buildRemoveReverseCommand(socketName: String): String =
        "adb reverse --remove localabstract:$socketName"

    /**
     * app_process 拉起命令（长跑流，走 [ScrcpyServerProcessRunner]）。
     *
     * 形态：`adb shell CLASSPATH=<设备端路径> app_process / <入口类> <版本首参> <key=value 参数>`
     * - 版本首参 = [SERVER_VERSION]，与 server jar 逐字绑定（不一致 server 端直接抛异常）；
     * - scid 以 8 位十六进制出现在命令与 socket 名两处，必须同源（[ScrcpyProtocol.deviceSocketName]）；
     * - `audio=false` 恒定（音频捕获属后续产品决策，不进参数面）；
     * - `clipboard_autosync=false` 恒定：autosync 的变更监听推送对本端是死流量（设备端
     *   剪贴板变化推送无人消费）；关掉后 server 只响应显式 get_clipboard 读回
     *   （`Controller.getClipboard` 的 `!clipboardAutosync` 分支）——读回通道与
     *   set_clipboard 的落地实证共用（引擎 deviceClipboard 流）；
     * - [control] 决定 server 是否开第二条控制 socket：true 时 server 在 video socket 之外
     *   背靠背再 accept（forward）/再反连（reverse）一条 control socket——控制通道握手
     *   **无探活字节**（server 的 sendDummyByte 在首条 socket 写后即置 false），设备名
     *   也只写首条 socket；两端读序分型必须与 [ScrcpyHandshakeReader] 的注释契约一致
     *   （在控制通道上等探活字节会把设备消息首字节误当探活，通道必然失效）；
     * - [tunnelForward] 决定 server 端监听/反连形态，必须与隧道建立结果一致
     *   （reverse 隧道传 false，forward 隧道传 true——探活字节的有无也随它分型）；
     * - 降负载参数（[maxSize]/[maxFps]/[videoBitRate]）与 [displayId]（多 Display 设备场景）
     *   为可选 key=value 项，null 不追加。
     */
    fun buildServeCommand(
        scid: Long,
        tunnelForward: Boolean,
        control: Boolean = true,
        displayId: Int? = null,
        maxSize: Int? = null,
        maxFps: Float? = null,
        videoBitRate: Long? = null,
    ): String = buildString {
        append("adb shell CLASSPATH=")
        append(DEVICE_SERVER_PATH)
        append(" app_process / com.genymobile.scrcpy.Server ")
        append(SERVER_VERSION)
        append(" scid=%08x".format(scid))
        append(" tunnel_forward=")
        append(tunnelForward)
        append(" control=")
        append(control)
        append(" audio=false clipboard_autosync=false")
        displayId?.let { append(" display_id=").append(it) }
        maxSize?.let { append(" max_size=").append(it) }
        maxFps?.let { append(" max_fps=").append(it) }
        videoBitRate?.let { append(" video_bit_rate=").append(it) }
    }
}
