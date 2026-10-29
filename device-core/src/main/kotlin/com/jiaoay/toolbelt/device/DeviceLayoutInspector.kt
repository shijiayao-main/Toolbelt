package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform

/**
 * 窗口布局检查内核（设备观测域）：`uiautomator dump` 一次抓全窗口层级树
 * （控件是谁/文本/坐标/可点），解析为 [DeviceLayoutDump] 节点树。
 *
 * 通道纪律（与 [DevicePackageBrowser] 同链口径）：命令全文经 [dumpCommand]
 * 构造——payload 按执行 shell 语义包裹（[DeviceFileBrowser.wrapPayloadForHostShell]）
 * 后经 [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`，执行走
 * [AdbProcessRunner]（由宿主机注入），fake 注入即可单测免真子进程。
 *
 * 单次执行编排：`uiautomator dump <临时路径>; cat <临时路径>; rm -f <临时路径>`
 * 一次 adb shell payload 内完成 dump → 取回 → 清理（三次设备侧命令一次 spawn，
 * 设备侧零残留——临时产物不留在用户设备上）。dump 的进度行（真机形态
 * `UI hierchary dumped to: <路径>`）与 XML 同流 stdout，解析前由本内核剥除。
 *
 * 失败语义（dump 谎报防御——真机实测 uiautomator dump 对不可写路径仍打进度行
 * 且退出 0，成败不能信 dump 自身回执）：
 * - 成败由 **cat 取回的 XML 产物**判定：无 XML 产物 = dump 未落产物，显式失败
 *   透传设备侧回执原文；
 * - 设备侧 `ERROR` 前缀行（未开「USB 调试（安全设置）」等权限门禁的 ROM 以此
 *   形态报错）显式失败并透传该行原文，不解析不翻译不吞；
 * - adb 退出码非零 / 进程真失败 → 人话回执带退出码与设备侧原文；
 * - XML 取回但格式不符（非 XML / 无 hierarchy 根 / bounds 缺席）由解析层
 *   [DeviceLayoutParsers] 显式失败，不静默回空。
 */
class DeviceLayoutInspector(
    private val runner: AdbProcessRunner,
    private val endpointReader: () -> AdbEndpoint,
) {

    /**
     * 抓取当前窗口层级树：dump → 取回 → 清理单次编排，成功返回解析后的节点树。
     * [serial] 为空零 spawn 显式失败。耗时以 dump 为界（层级大时数秒级），
     * 本方法不设超时——超时治理归调用方（与设备观测域其他命令同口径）。
     */
    suspend fun dumpHierarchy(serial: String): Result<DeviceLayoutDump> {
        if (serial.isEmpty()) {
            return Result.failure(IllegalStateException("设备串号为空，未执行窗口层级抓取"))
        }
        val endpoint = endpointReader()
        val shell = runner.execute(dumpCommand(endpoint.executablePath, serial))
            .getOrElse { e -> return Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}")) }
        if (shell.exitCode != 0) {
            return Result.failure(
                IllegalStateException("窗口层级抓取失败（adb 退出码 ${shell.exitCode}）：${shellErrorText(shell)}"),
            )
        }
        val stdout = shell.stdout.replace("\r", "")
        val xmlStart = stdout.indexOf(XML_DECLARATION).takeIf { it >= 0 }
            ?: stdout.indexOf(HIERARCHY_ELEMENT_OPEN).takeIf { it >= 0 }
        if (xmlStart == null) {
            val errorLine = firstErrorLine(stdout)
            if (errorLine != null) {
                return Result.failure(
                    IllegalStateException("窗口层级抓取失败（设备侧错误原文）：$errorLine"),
                )
            }
            return Result.failure(
                IllegalStateException(
                    "窗口层级抓取失败：未取回窗口层级 XML（uiautomator 未落产物）" +
                        "——设备侧回执：${firstNonBlankLine(stdout) ?: "无输出"}；stderr：${shell.stderr.trim().ifBlank { "无" }}",
                ),
            )
        }
        val deviceError = firstErrorLine(stdout.substring(0, xmlStart))
        if (deviceError != null) {
            return Result.failure(
                IllegalStateException("窗口层级抓取失败（设备侧错误原文）：$deviceError"),
            )
        }
        return try {
            Result.success(DeviceLayoutParsers.parseLayoutDump(stdout.substring(xmlStart)))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    /** 首个非空行（设备侧回执样例锚），全空返回 null */
    private fun firstNonBlankLine(text: String): String? = text.lines().firstOrNull { it.isNotBlank() }?.trim()

    /**
     * 首个 `ERROR` 前缀行（uiautomator 命令的错误回执约定形态）：行首匹配，
     * 属性值内嵌的 ERROR 字样不误报；扫描范围由调用方限定（XML 起点之前）。
     */
    private fun firstErrorLine(text: String): String? =
        text.lines().firstOrNull { it.trim().startsWith(ERROR_PREFIX) }?.trim()

    companion object {
        internal const val ERROR_PREFIX = "ERROR"

        /**
         * 设备侧 dump 产物临时路径（单源常量）：shell 用户恒可写的设备侧 tmp
         * （/sdcard 未挂载/受限的 ROM 上仍可用）。固定常量无空白/元字符，不经
         * 设备侧引号——引号配方只服务调用方输入面，内部常量无注入面。
         */
        internal const val DEVICE_DUMP_PATH = "/data/local/tmp/toolbelt_uidump.xml"

        private const val XML_DECLARATION = "<?xml"

        private const val HIERARCHY_ELEMENT_OPEN = "<hierarchy"

        /**
         * 抓取命令全文（internal 供单测逐字节断言通道纪律）：dump → cat → rm -f
         * 三段 `;` 定序（前段失败不阻断后段——成败由 XML 产物判定，rm -f 兜底
         * 清理恒执行）。payload 按执行 shell 语义包裹后经 [AdbCommandRewriter]
         * 注入 adb 路径与 `-s <serial>`。
         */
        internal fun dumpCommand(adbPath: String, serial: String): String {
            val payload = "uiautomator dump $DEVICE_DUMP_PATH; cat $DEVICE_DUMP_PATH; rm -f $DEVICE_DUMP_PATH"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }
    }
}
