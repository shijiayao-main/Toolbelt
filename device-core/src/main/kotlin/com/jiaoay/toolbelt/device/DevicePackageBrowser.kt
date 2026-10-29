package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform

/**
 * 设备应用浏览器内核（设备观测域）：包列表与单包详情的命令构造 + 执行调度。
 * 浏览是请求-回执模型（无轮询环），通道纪律与 [DeviceFileBrowser] 同链口径：
 * 命令全文经 [listAllPackagesCommand]/[listSystemPackagesCommand]/
 * [packageDetailsCommand] 构造——payload 按执行 shell 语义包裹后经
 * [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`，执行走 [AdbProcessRunner]
 * （由宿主机注入），fake 注入即可单测免真子进程。
 *
 * 全部包视角契约（不做第三方假设）：[listPackages] 两次列举取**全部**包 + `-s`
 * 系统包集合，[DevicePackageEntry.isSystem] 由集合差写入——全预装形态下
 * `-3` 列举为 0 条（「第三方」标记恒假无意义），预装/后装判别走系统标记。
 *
 * 失败语义：
 * - 两次列举与详情命令各自退出非零即失败（不重试不降级——pm/dumpsys 旗标组合
 *   已按真机实测定型，退出非零是设备侧真错误）；失败回执 message 面向用户：
 *   设备侧错误原文透传（pm 坏旗标回执是 `Error: Unknown option: ...` 等 stderr
 *   形态，不解析不翻译不吞）；
 * - 详情对**缺席包**的失败路径：dumpsys 对未安装包仍退出 0（只打印 Queries 等
 *   无关段零包记录），由解析层按格式不符显式失败，不静默；
 * - [packageName]/[serial] 为空零 spawn 显式失败。
 */
class DevicePackageBrowser(
    private val runner: AdbProcessRunner,
    private val endpointReader: () -> AdbEndpoint,
) {

    /**
     * 列举设备全部已安装应用（全部包视角）：全部包 `pm list packages
     * --show-versioncode -U` + 系统包集合 `pm list packages -s
     * --show-versioncode -U` 两次列举（同一行形态共用解析），[DevicePackageEntry]
     * 按系统集合成员标 [DevicePackageEntry.isSystem]。两次列举均须成功——
     * 系统标记缺席不静默降级为「全非系统」。
     */
    suspend fun listPackages(serial: String): Result<List<DevicePackageEntry>> {
        if (serial.isEmpty()) {
            return Result.failure(IllegalStateException("设备串号为空，未执行包列举"))
        }
        val endpoint = endpointReader()

        val all = runner.execute(listAllPackagesCommand(endpoint.executablePath, serial))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (all.exitCode != 0) {
            return Result.failure(
                IllegalStateException("包列举失败（adb 退出码 ${all.exitCode}）：${shellErrorText(all)}"),
            )
        }
        val allParsed = try {
            DevicePackageParsers.parsePackageList(all.stdout)
        } catch (e: IllegalArgumentException) {
            // 解析失败回执带动作名（两次列举同解析器，回执须能区分是哪次列举格式不符）
            return Result.failure(IllegalStateException("包列举${e.message}"))
        }

        val system = runner.execute(listSystemPackagesCommand(endpoint.executablePath, serial))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (system.exitCode != 0) {
            return Result.failure(
                IllegalStateException("系统包列举失败（adb 退出码 ${system.exitCode}）：${shellErrorText(system)}"),
            )
        }
        val systemParsed = try {
            DevicePackageParsers.parsePackageList(system.stdout)
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalStateException("系统包列举${e.message}"))
        }

        val systemNames = systemParsed.map { it.first }.toSet()
        return Result.success(
            allParsed.map { (name, versionCode, uid) ->
                DevicePackageEntry(
                    packageName = name,
                    versionCode = versionCode,
                    uid = uid,
                    isSystem = name in systemNames,
                )
            },
        )
    }

    /**
     * 单包详情（选中后再拉取的懒加载路径——`dumpsys package <pkg>` 单包输出
     * 约 100KB，全量列举不带此载荷）：解析 Packages 记录最小字段面
     * （[DevicePackageDetails]），其余段不进模型。
     */
    suspend fun packageDetails(packageName: String, serial: String): Result<DevicePackageDetails> {
        if (packageName.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("包名或设备串号为空，未执行包详情读取"))
        }
        val endpoint = endpointReader()
        val shell = runner.execute(packageDetailsCommand(packageName, endpoint.executablePath, serial))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (shell.exitCode != 0) {
            return Result.failure(
                IllegalStateException("包详情读取失败（adb 退出码 ${shell.exitCode}）：${shellErrorText(shell)}"),
            )
        }
        return try {
            Result.success(DevicePackageParsers.parsePackageDetails(shell.stdout, packageName))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    /** adb 进程真失败（可执行缺席等）统一人话回执 */
    private fun adbSpawnFailure(e: Throwable): Result<Nothing> =
        Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))

    companion object {

        /**
         * 全部包列举命令全文（internal 供单测逐字节断言通道纪律）：`pm list
         * packages --show-versioncode -U` payload 按执行 shell 语义包裹后经
         * [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`。
         */
        internal fun listAllPackagesCommand(adbPath: String, serial: String): String {
            val payload = "pm list packages --show-versioncode -U"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }

        /** 系统包集合列举命令全文（internal 供单测逐字节断言）：同全部包形态加 `-s` 旗标——同解析器共用，集合差只按包名 */
        internal fun listSystemPackagesCommand(adbPath: String, serial: String): String {
            val payload = "pm list packages -s --show-versioncode -U"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }

        /**
         * 单包详情命令全文（internal 供单测逐字节断言）：`dumpsys package <包名>`，
         * 包名 token 经 [DeviceFileBrowser.deviceShellQuote] 设备侧单引号零展开
         * （防包名形态外的输入面注入——引号是构造方责任）。
         */
        internal fun packageDetailsCommand(packageName: String, adbPath: String, serial: String): String {
            val payload = "dumpsys package ${DeviceFileBrowser.deviceShellQuote(packageName)}"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }
    }
}
