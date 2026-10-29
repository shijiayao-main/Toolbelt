package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform

/**
 * 设备文件浏览器内核（设备观测域）：目录列举与单条目 stat 的命令构造 + 执行调度。
 * 浏览是请求-回执模型（无轮询环、无设备态常驻），路径与目标串号由调用面解析，
 * 本类不做设备态推断——与 [KeyEventInjector] 的默认目标解析是分工不是缺位。
 *
 * 通道纪律（与 [KeyEventInjector]/[PerfSampler] 同链口径）：
 * - 命令全文经 [listDirectoryCommand]/[statCommand] 构造——payload 按执行 shell
 *   语义包裹后经 [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`；
 * - 执行走 [AdbProcessRunner]（由宿主机注入），fake 注入即可单测，
 *   免真子进程；
 * - 设备侧路径按 Unix 单引号零展开配方包裹（路径含空格/`$`/反引号必须字面化——
 *   双引号内展开即命令注入面）。[AdbCommandRewriter] 只包可执行 token，参数
 *   引号是本构造方的责任。
 *
 * 失败语义：
 * - [listDirectory] 主链 `ls -la`，退出非零回退 `ls -l`（老 ls 不认 `-a` 组合旗标
 *   的形态）——回退只由退出码触发；退出 0 但解析格式不符不重试（`-l` 与 `-la`
 *   同输出形态，重试无意义），解析失败原样透传；
 * - 失败回执 message 面向用户：设备侧 stderr 原文透传（`ls: <path>: No such
 *   file or directory` 等设备侧人话不翻译不吞）；
 * - [path]/[serial] 为空零 spawn 显式失败。
 */
class DeviceFileBrowser(
    private val runner: AdbProcessRunner,
    private val endpointReader: () -> AdbEndpoint,
) {

    /**
     * 列举设备侧目录：主链 `ls -la`，退出非零回退 `ls -l`，两次都退出非零则失败
     * 回执透传设备侧错误原文。成功回执 = 条目列表（空目录为空列表——`ls` 退出 0
     * 且零条目是合法读数，解析层保证非空输出必有可识别行或显式失败）。
     */
    suspend fun listDirectory(path: String, serial: String): Result<List<DeviceFileEntry>> {
        if (path.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("路径或设备串号为空，未执行目录列举"))
        }
        val endpoint = endpointReader()
        val primary = runner.execute(listDirectoryCommand(path, endpoint.executablePath, serial, includeDotEntries = true))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (primary.exitCode == 0) return parseToListing(primary.stdout)
        val fallback = runner.execute(listDirectoryCommand(path, endpoint.executablePath, serial, includeDotEntries = false))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (fallback.exitCode == 0) return parseToListing(fallback.stdout)
        return Result.failure(
            IllegalStateException(
                "目录读取失败（adb 退出码 ${fallback.exitCode}）：${shellErrorText(fallback, primary)}",
            ),
        )
    }

    /**
     * 单条目 stat 机器可读读取（详情/校验备面）：条目缺席（退出非零）失败回执
     * 透传设备侧错误原文。精确纪元秒与尺寸取自设备侧 stat，不经 ls 列数据。
     *
     * stat vs lstat 解引用语义（真机实测钉死）：不带 `-L` 的 `stat` 走
     * lstat 语义——对 symlink 返回 "symbolic link" 自身类型与链接路径字节数
     * （`/sdcard` 实测 `symbolic link|21`）；`[dereference] = true` 走 `stat -L`
     * 解引用语义——返回最终目标的类型与尺寸（`/sdcard` 实测 `directory`，
     * symlink→普通文件实测 `regular file|<目标尺寸>`）。断链 symlink 在 `-L`
     * 下退出码非 0（目标缺席即失败，不静默）；断链回执的设备端 stderr 文案
     * 待维护者真机门禁（构造断链需写操作，离线侧按退出码非零契约实现）。
     */
    suspend fun statEntry(
        path: String,
        serial: String,
        dereference: Boolean = false,
    ): Result<DeviceFileStat> {
        if (path.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("路径或设备串号为空，未执行 stat 读取"))
        }
        val endpoint = endpointReader()
        val shell = runner.execute(statCommand(path, endpoint.executablePath, serial, dereference))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (shell.exitCode != 0) {
            return Result.failure(
                IllegalStateException("stat 读取失败（adb 退出码 ${shell.exitCode}）：${shellErrorText(shell)}"),
            )
        }
        return try {
            Result.success(DeviceFileParsers.parseStatLine(shell.stdout))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    /** adb 进程真失败（可执行缺席等）统一人话回执 */
    private fun adbSpawnFailure(e: Throwable): Result<Nothing> =
        Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))

    /** 退出 0 的 ls 输出进解析层；格式不符显式失败（不回退不吞） */
    private fun parseToListing(stdout: String): Result<List<DeviceFileEntry>> =
        try {
            Result.success(DeviceFileParsers.parseLsEntries(stdout))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }

    companion object {

        /**
         * 目录列举命令全文（internal 供单测逐字节断言通道纪律）：`ls -la`/`ls -l` +
         * 设备侧单引号零展开路径，payload 再按执行 shell 语义包裹后经
         * [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`。
         */
        internal fun listDirectoryCommand(
            path: String,
            adbPath: String,
            serial: String,
            includeDotEntries: Boolean,
        ): String {
            val payload = buildString {
                append("ls -l")
                if (includeDotEntries) append('a')
                append(' ')
                append(deviceShellQuote(path))
            }
            return AdbCommandRewriter.rewrite("adb shell ${wrapPayloadForHostShell(payload)}", adbPath, serial)
        }

        /**
         * 单条目 stat 命令全文（internal 供单测逐字节断言）：`stat -c
         * '%F|%s|%Y|%A|%U|%G'` 机器可读单行 + 设备侧单引号零展开路径；[dereference]
         * 为 true 时走 `stat -Lc ...`（解引用语义见 [statEntry] 契约）。格式串自身
         * 含单引号，经 [wrapPayloadForHostShell] 转义后由执行 shell 还原原文。
         */
        internal fun statCommand(path: String, adbPath: String, serial: String, dereference: Boolean = false): String {
            val flagHead = if (dereference) "stat -Lc" else "stat -c"
            val payload = "$flagHead '%F|%s|%Y|%A|%U|%G' ${deviceShellQuote(path)}"
            return AdbCommandRewriter.rewrite("adb shell ${wrapPayloadForHostShell(payload)}", adbPath, serial)
        }

        /**
         * 设备侧 token 引号：恒 Unix 单引号零展开（`$`/反引号/空白字面化，内嵌
         * 单引号以 `'\''` 重开闭转义）——设备 shell 解析的是 payload 原文，路径
         * 必须自带引号才不被设备 shell 二次分词，与本机 OS 无关。
         * （internal 单源：[DevicePackageBrowser] 的 dumpsys 包名 token 同配方复用）
         */
        internal fun deviceShellQuote(token: String): String = "'" + token.replace("'", "'\\''") + "'"

        /**
         * payload 的本机 shell 包裹：Unix 单引号并转义内嵌单引号（payload 自带
         * 设备侧引号，直包配方对含引号 payload 失效）；Windows 走 cmd.exe 双引号
         * （payload 内单引号安全；路径含双引号不支撑，登记残余——与
         * [AdbCommandRewriter] 的可执行 token 包裹同限）。
         * （internal 单源：[DevicePackageBrowser] 的 pm/dumpsys payload 同配方复用）
         */
        internal fun wrapPayloadForHostShell(payload: String, platform: AdbPlatform? = AdbPlatform.current()): String =
            if (platform?.isWindows == true) {
                "\"$payload\""
            } else {
                "'" + payload.replace("'", "'\\''") + "'"
            }
    }
}
