package com.jiaoay.toolbelt.adb

/**
 * adb 命令重写器：把字面 `adb ...` 命令串改写成指向真实可执行文件、并注入目标设备串号。
 *
 * 纯函数、无副作用——同样的入参恒得同样的出参，调用方不需要持有任何状态。
 *
 * 规则：
 * 1. 命令 trim 后不以 `adb` 开头（词边界：`adb` 之后须为空白或行尾）→ 原样返回；
 *    `adbshell getprop`（无空格分隔）不是 adb 命令，管道右侧的 adb 也不处理。
 * 2. [AdbEndpoint.executablePath] 非空 → 行首 `adb` 替换为该路径。路径含空白字符时
 *    按执行 shell 语义包裹：Unix 经登录 shell 执行用单引号；Windows 经 `cmd.exe /c`
 *    执行用双引号——cmd 不解析单引号，单引号包裹会使命令按字面断裂。
 *    空串保持字面 `adb`，交给执行环境按 PATH 解析。
 * 3. [serial] 非空且 `adb` 后第一个参数不是 `-s`（用户显式指定优先，不重复注入）→
 *    在可执行文件后插入 `-s <serial>`；serial 为 null/空时不注入（设备检测等探测命令）。
 *
 * 专用 server 端口不走本函数的命令串：端口经执行环境变量注入（见 `AdbEndpoint`），
 * 对命令文本零侵入——重写结果与是否内置 adb 无关，命令可原样回放。
 */
object AdbCommandRewriter {

    private const val ADB = "adb"
    private const val SERIAL_FLAG = "-s"
    private val WHITESPACE = "\\s+".toRegex()

    /** 重写单条命令：非 adb 命令原样返回；adb 命令替换可执行路径并按需注入 `-s` */
    fun rewrite(command: String, endpoint: AdbEndpoint, serial: String?): String =
        rewrite(command, endpoint.executablePath, serial, platform = AdbPlatform.current())

    /** 同上，[platform] 由调用方注入（测试与生产统一走同一逻辑；null 按 Unix 语义处理） */
    fun rewrite(command: String, endpoint: AdbEndpoint, serial: String?, platform: AdbPlatform?): String =
        rewrite(command, endpoint.executablePath, serial, platform)

    /** 路径形态重载：只要可执行路径、不关心端口的调用方用（命令串回放、命令构造器） */
    fun rewrite(command: String, adbPath: String, serial: String?): String =
        rewrite(command, adbPath, serial, AdbPlatform.current())

    /** 路径 + 平台重载（测试注入平台，生产走 [AdbPlatform.current]） */
    fun rewrite(command: String, adbPath: String, serial: String?, platform: AdbPlatform?): String {
        val trimmed = command.trim()
        if (!startsWithAdbWord(trimmed)) return command

        val executable = adbExecutableToken(adbPath, platform?.isWindows == true)
        val rest = trimmed.substring(ADB.length)
        if (rest.isEmpty()) {
            return if (serial.isNullOrEmpty()) executable else "$executable $SERIAL_FLAG $serial"
        }

        // rest 必以空白开头（词边界已保证）；首参数已显式携带 -s 时不重复注入
        val firstArg = rest.trim().split(WHITESPACE).firstOrNull()
        val hasExplicitSerial = firstArg == SERIAL_FLAG

        return if (!serial.isNullOrEmpty() && !hasExplicitSerial) {
            "$executable $SERIAL_FLAG $serial$rest"
        } else {
            "$executable$rest"
        }
    }

    /** 词边界判定：以 `adb` 开头且其后为行尾或空白字符 */
    private fun startsWithAdbWord(trimmed: String): Boolean {
        if (!trimmed.startsWith(ADB)) return false
        return trimmed.length == ADB.length || trimmed[ADB.length].isWhitespace()
    }

    /**
     * adb 命令判定（词边界单源，[startsWithAdbWord] 复用）：
     * 未连接执行预警等场景需要「这是不是 adb 命令」的公开判定——私有判定开公开口不重复实现
     */
    fun isAdbCommand(command: String): Boolean = startsWithAdbWord(command.trim())

    /** 自定义可执行文件 token：路径空白 → 保持 `adb`；含空白字符 → 按执行 shell 语义加引号 */
    private fun adbExecutableToken(adbPath: String, windows: Boolean): String {
        val path = adbPath.trim()
        if (path.isEmpty()) return ADB
        return if (path.any { it.isWhitespace() }) {
            if (windows) "\"$path\"" else "'$path'"
        } else {
            path
        }
    }
}
