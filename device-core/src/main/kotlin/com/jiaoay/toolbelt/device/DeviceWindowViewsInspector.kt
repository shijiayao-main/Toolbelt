package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform
import java.io.File

/**
 * 全窗口视图检查内核（设备观测域）：`cmd window dump-visible-window-views` 一次
 * 抓全部可见窗口的 View 内部层级（无需应用 debuggable；耗时约 1s，对比 uiautomator
 * dump 数秒且只抓聚焦窗口），ZIP 条目按 [DeviceWindowViewsDecoder] 语法解码为
 * [DeviceWindowViewsEntry] 窗口清单。
 *
 * 通道纪律（与 [DeviceLayoutInspector] 同链口径）：命令全文经本类 companion 构造——
 * payload 按执行 shell 语义包裹（[wrapPayloadForHostShell]）后经 [AdbCommandRewriter]
 * 注入 adb 路径与 `-s <serial>`，执行走 [AdbProcessRunner]（由宿主机注入），
 * fake 注入即可单测免真子进程。
 *
 * 通道侦察结论（dvwv 产物是 ZIP 二进制，**不走 shell 文本 stdout**）：
 * 执行通道读 stdout 是文本读（按命令族选编码解码）——UTF-8 解码
 * 对压缩流里的非 UTF-8 字节序列做替换式纠错（U+FFFD），不可逆失真，且 `String` 无法
 * 回转任意字节；`adb exec-out` 的二进制安全面到不了文本读侧。故本内核
 * 三步编排，二进制恒走 adb pull 的文件通道（sync 协议，不经 shell 文本）：
 * 1. 设备侧落临时 ZIP（`dvwv > /data/local/tmp/<名>`；`echo` 回传 dump 退出码——
 *    跨 ROM 无此命令/权限门禁的失败显式化）；
 * 2. `adb pull` 取回本地临时文件；
 * 3. `rm -f` 清理设备侧临时产物（finally 兜底，零残留）。
 *
 * 失败语义（产物决定成败——dump 自身回执不可全信，与 DeviceLayoutInspector 的
 * 谎报防御同哲学）：
 * - dump 退出码行解析非零 → 显式失败透传设备侧回执原文（stderr 优先）；
 * - 退出码行缺席（ROM shell 变体）不预判，交给产物判型：pull 后无 ZIP 魔数
 *   （`PK\x03\x04`）即显式失败透传设备侧回执；
 * - adb 退出非零 / 进程真失败 → 人话回执带退出码与原文；ZIP 取回但零可解码条目
 *   （全条目结构损坏）由解码层显式失败，不静默回空。
 */
class DeviceWindowViewsInspector(
    private val runner: AdbProcessRunner,
    private val endpointReader: () -> AdbEndpoint,
) {

    /**
     * 抓取全部可见窗口的 View 层级：dump → pull → 清理三步编排，成功返回窗口
     * 条目清单。[serial] 为空零 spawn 显式失败。本方法不设超时——超时治理归调用方
     * （与设备观测域其他命令同口径）。
     */
    suspend fun dumpWindowViews(serial: String): Result<DeviceWindowViewsDump> {
        if (serial.isEmpty()) {
            return Result.failure(IllegalStateException("设备串号为空，未执行全窗口视图抓取"))
        }
        val endpoint = endpointReader()
        try {
            val dump = runner.execute(dumpCommand(endpoint.executablePath, serial))
                .getOrElse { e -> return Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}")) }
            if (dump.exitCode != 0) {
                return Result.failure(
                    IllegalStateException("全窗口视图抓取失败（adb 退出码 ${dump.exitCode}）：${receiptOf(dump)}"),
                )
            }
            val dumpRc = parseDumpExitCode(dump.stdout.replace("\r", ""))
            if (dumpRc != null && dumpRc != 0) {
                return Result.failure(
                    IllegalStateException("全窗口视图抓取失败（设备侧退出码 $dumpRc）：${receiptOf(dump)}"),
                )
            }

            val localZip = File.createTempFile(LOCAL_PREFIX, LOCAL_SUFFIX)
            try {
                val pull = runner.execute(pullCommand(DEVICE_ZIP_PATH, localZip.absolutePath, endpoint.executablePath, serial))
                    .getOrElse { e ->
                        return Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))
                    }
                if (pull.exitCode != 0) {
                    return Result.failure(
                        IllegalStateException("全窗口视图抓取失败（adb 退出码 ${pull.exitCode}）：${receiptOf(pull)}"),
                    )
                }
                val bytes = localZip.readBytes()
                if (!bytes.startsWithMagic()) {
                    return Result.failure(
                        IllegalStateException(
                            "全窗口视图抓取失败：未取回窗口视图 ZIP（设备侧回执：${receiptOf(dump) ?: "无输出"}）" +
                                "——该 ROM 可能不支持 dump-visible-window-views",
                        ),
                    )
                }
                val windows = DeviceWindowViewsDecoder.decodeZip(bytes)
                if (windows.isEmpty()) {
                    return Result.failure(IllegalStateException("全窗口视图抓取失败：ZIP 内无可解码窗口条目（解码层结构损坏隔离后为空）"))
                }
                return Result.success(DeviceWindowViewsDump(windows))
            } finally {
                runCatching { localZip.delete() }
            }
        } finally {
            // 设备侧临时产物零残留：成败路径都清理（best-effort，清理失败不掩盖主回执）
            runner.execute(cleanupCommand(endpoint.executablePath, serial))
        }
    }

    /** 设备侧回执原文透传：stderr 优先，缺席回看 stdout，再缺席返回 null */
    private fun receiptOf(shell: ShellResult): String? =
        shell.stderr.trim().ifBlank { null }
            ?: shell.stdout.trim().ifBlank { null }

    /**
     * dump 退出码行解析（internal 供单测）：`echo <前缀>$?` 回传行，缺席返回 null
     * （ROM shell 变体不预判，交给产物判型）。`\r` 由调用方剥除后传入亦可。
     */
    internal fun parseDumpExitCode(stdout: String): Int? =
        stdout.lines().firstOrNull { it.startsWith(DUMP_RC_PREFIX) }
            ?.removePrefix(DUMP_RC_PREFIX)
            ?.trim()
            ?.toIntOrNull()

    companion object {
        /** ZIP 魔数（产物判型锚：dump 谎报/错误文本写入文件皆在此现形） */
        private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04)

        /** ZIP 魔数判型（stdlib 无 ByteArray.startsWith 前缀面，本类私有一个就够） */
        private fun ByteArray.startsWithMagic(): Boolean =
            size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { this[it] == ZIP_MAGIC[it] }

        /** 设备侧 dump 产物临时 ZIP 路径（shell 用户恒可写；固定常量无空白/元字符） */
        internal const val DEVICE_ZIP_PATH = "/data/local/tmp/toolbelt_wvviews.zip"

        /** dump 退出码回传前缀（`echo <前缀>$?`，设备侧 shell 展开） */
        internal const val DUMP_RC_PREFIX = "TWV_DUMP_RC:"

        private const val LOCAL_PREFIX = "toolbelt-wvviews-"
        private const val LOCAL_SUFFIX = ".zip"

        /**
         * dump 命令全文（internal 供单测逐字节断言通道纪律）：设备侧重定向落临时
         * ZIP + `echo` 回传退出码（payload 内 `$?` 由 host shell 单引号保护、设备侧
         * shell 展开），经 [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`。
         */
        internal fun dumpCommand(adbPath: String, serial: String): String {
            val payload = "cmd window dump-visible-window-views > $DEVICE_ZIP_PATH; echo $DUMP_RC_PREFIX\$?"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }

        /**
         * pull 命令全文（internal 供单测）：双路径按 host shell 语义包裹（Unix 单引号
         * 零展开），经 [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`；设备路径是
         * adbd sync 协议参数不经设备 shell 分词（与 [DeviceFilePuller] 同配方）。
         */
        internal fun pullCommand(devicePath: String, localPath: String, adbPath: String, serial: String): String {
            val payload = "adb pull ${DeviceFileBrowser.wrapPayloadForHostShell(devicePath)} " +
                DeviceFileBrowser.wrapPayloadForHostShell(localPath)
            return AdbCommandRewriter.rewrite(payload, adbPath, serial)
        }

        /**
         * 清理命令全文（internal 供单测）：finally 兜底的设备侧零残留面；固定常量
         * 路径无空白不经设备侧引号（与 [DeviceLayoutInspector] 内部常量同口径）。
         */
        internal fun cleanupCommand(adbPath: String, serial: String): String {
            val payload = "rm -f $DEVICE_ZIP_PATH"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }
    }
}
