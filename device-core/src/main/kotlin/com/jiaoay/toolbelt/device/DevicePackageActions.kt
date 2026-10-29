package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbCommandRewriter
import com.jiaoay.toolbelt.adb.AdbEndpoint
import com.jiaoay.toolbelt.adb.AdbPlatform
import java.io.File

/**
 * APK 导出单 split 的开始进度回执（busy 展示锚）。
 *
 * 契约：[fileIndex] 0 基；[fileCount] = 本次导出的 split 总数；[fileName] 是本 split
 * 的**本地目标名**（[DeviceApkExporter.apkExportFileName] 产物）；[expectedSizeBytes]
 * 是导出开始时刻的设备侧 stat 快照（展示锚，非校验锚——校验走拉取内核内部的
 * 拉取时刻 stat 真值，两个锚刻意分离同拉取面板的 ls 尺寸展示配方）。
 */
data class DeviceApkExportFileStart(
    val packageName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val fileName: String,
    val devicePath: String,
    val expectedSizeBytes: Long,
)

/**
 * APK 导出完成回执（成功态）：[files] 逐 split 对应一次完整拉取，成员即
 * [DeviceFilePullReceipt] 原样（.part 落盘 + stat 校验 + 原子转正逐文件由拉取内核
 * 执行，本层只做编排不重复造传输语义）。
 */
data class DeviceApkExportReceipt(
    val packageName: String,
    val files: List<DeviceFilePullReceipt>,
)

/**
 * 设备 APK 导出内核（设备观测域）：`pm path <pkg>` 路径列举 + 逐 split 经
 * [DeviceFilePuller] 拉回的编排层。
 *
 * 通道纪律（与 [DevicePackageBrowser] 同链口径）：pm path 命令经
 * [pmPathCommand] 构造——包名 token 设备侧单引号零展开，payload 按本机执行
 * shell 语义包裹后经 [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`；
 * 执行走 [AdbProcessRunner]（由宿主机注入）；split 传输恒走注入的
 * [DeviceFilePuller]（拉取侧通道/校验/取消语义全部继承）。
 *
 * 失败语义：
 * - 缺席包走 pm path 退出非零分支（空输出 + 退出码 1 的形态——错误原文透传，
 *   无输出时回执点名「包未安装或无 APK」）；退出 0 但解析为空集合同样显式失败
 *   「包未安装或无 APK」（双保险——空集不冒充合法读数）；
 * - 逐 split 顺序执行：任一 split 前置 stat 失败/类型非普通文件/拉取失败即整体
 *   失败并停止后续 split；**已完成的 split 不回滚**（本地已有完整文件是合法产物，
 *   回执 message 点明中断点供人工核对）；
 * - 导出中途取消：委托拉取内核单飞行取消（[requestCancel]），在途 split 落取消
 *   回执并清理 .part，后续 split 不再发起。
 */
class DeviceApkExporter(
    private val runner: AdbProcessRunner,
    private val puller: DeviceFilePuller,
    private val statEntry: suspend (path: String, serial: String) -> Result<DeviceFileStat>,
    private val endpointReader: () -> AdbEndpoint,
    private val log: AdbLog = AdbLog.NONE,
) {

    /**
     * 列举包的全部 APK 设备侧路径（`pm path <pkg>`，多 split 返回多行 `package:...`
     * 按行收集）。空包名/空串号零 spawn 人话拒绝；缺席包与空输出集显式失败见类头。
     */
    suspend fun listApkPaths(packageName: String, serial: String): Result<List<String>> {
        if (packageName.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("包名或设备串号为空，未执行 APK 路径列举"))
        }
        val endpoint = endpointReader()
        val shell = runner.execute(pmPathCommand(packageName, endpoint.executablePath, serial))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (shell.exitCode != 0) {
            val errorText = shellErrorText(shell, emptyFallback = "")
            val hint = errorText.ifEmpty { "空输出——包未安装或无 APK" }
            return Result.failure(IllegalStateException("APK 路径列举失败（adb 退出码 ${shell.exitCode}）：$hint"))
        }
        val paths = try {
            DevicePackageParsers.parseApkPaths(shell.stdout)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        if (paths.isEmpty()) {
            return Result.failure(IllegalStateException("包未安装或无 APK：$packageName（pm path 空输出）"))
        }
        return Result.success(paths)
    }

    /**
     * 导出包的全部 APK 到本地目录：pm path 取路径列表 → 逐 split 拉回（本地目标名 =
     * [apkExportFileName]；.part + 校验 + 原子转正逐文件由拉取内核执行）。
     *
     * [onFileStart] 在每个 split 拉取开始前回调（busy 进度锚——序号/总数/目标名/
     * 期望大小），同步调用非挂起；[targetDir] 由调用方注入（App 形态默认下载目录，
     * 单测注入临时目录——不碰用户数据根）。
     */
    suspend fun exportApk(
        packageName: String,
        targetDir: File,
        serial: String,
        onFileStart: (DeviceApkExportFileStart) -> Unit = {},
    ): Result<DeviceApkExportReceipt> {
        if (packageName.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("包名或设备串号为空，未执行 APK 导出"))
        }
        val paths = listApkPaths(packageName, serial).getOrElse { return Result.failure(it) }
        val files = mutableListOf<DeviceFilePullReceipt>()
        for ((index, devicePath) in paths.withIndex()) {
            val fileName = apkExportFileName(packageName, index)
            val stat = statEntry(devicePath, serial).getOrElse { e ->
                return Result.failure(
                    IllegalStateException(
                        "APK 导出前置校验失败（文件 ${index + 1}/${paths.size} $fileName，未读到设备侧大小）" +
                            "：${e.message ?: "未知异常"}",
                    ),
                )
            }
            if (stat.type != DeviceFileType.FILE) {
                val typeText = when (stat.type) {
                    DeviceFileType.DIRECTORY -> "目录"
                    DeviceFileType.SYMLINK -> "symlink"
                    DeviceFileType.FILE -> "普通文件"
                }
                return Result.failure(
                    IllegalStateException("仅支持导出普通文件（$devicePath 是$typeText），导出中止——已完成文件不回滚"),
                )
            }
            onFileStart(
                DeviceApkExportFileStart(
                    packageName = packageName,
                    fileIndex = index,
                    fileCount = paths.size,
                    fileName = fileName,
                    devicePath = devicePath,
                    expectedSizeBytes = stat.sizeBytes,
                ),
            )
            val receipt = puller
                .pullFile(devicePath, File(targetDir, fileName).absolutePath, serial)
                .getOrElse { e ->
                    return Result.failure(
                        IllegalStateException(
                            "APK 导出中断（文件 ${index + 1}/${paths.size} $fileName）" +
                                "：${e.message ?: "未知异常"}——已完成文件不回滚",
                        ),
                    )
                }
            files += receipt
        }
        return Result.success(DeviceApkExportReceipt(packageName, files))
    }

    /** 请求取消在途导出（幂等；无在途为无动作）——委托拉取内核单飞行取消面 */
    fun requestCancel() {
        puller.requestCancel()
    }

    /** adb 进程真失败（可执行缺席等）统一人话回执 */
    private fun adbSpawnFailure(e: Throwable): Result<Nothing> =
        Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))

    companion object {

        /** 导出前置 stat 的默认实现（[DeviceFileBrowser] App 级共事实例外的轻量私有实例） */
        private suspend fun defaultStatEntry(path: String, serial: String): Result<DeviceFileStat> =
            error("必须由实例注入 statEntry：默认实现需要 runner 与 endpointReader")

        /**
         * pm path 命令全文（internal 供单测逐字节断言通道纪律）：`pm path <包名>`，
         * 包名 token 经 [DeviceFileBrowser.deviceShellQuote] 设备侧单引号零展开
         * （与 dumpsys 详情命令同配方——引号是构造方责任）。
         */
        internal fun pmPathCommand(packageName: String, adbPath: String, serial: String): String {
            val payload = "pm path ${DeviceFileBrowser.deviceShellQuote(packageName)}"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }

        /**
         * 单 split 本地目标名（internal 供单测断言防覆盖语义）：首个 split 直接
         * `<包名>.apk`，其余 split 追加序号 `<包名>-2.apk`/`<包名>-3.apk`…——包名
         * 前缀跨包消歧（多包导出同目录互不覆盖），序号后缀同包多 split 消歧
         * （多 split 设备侧各自成行，目标名按位序落位不复用设备侧文件名）。
         */
        internal fun apkExportFileName(packageName: String, index: Int): String =
            if (index == 0) "$packageName.apk" else "$packageName-${index + 1}.apk"
    }
}

// OPEN(真机门禁): pm disable-user/enable 端到端真机验证（真实翻转设备状态）归维护者
// 门禁——本内核只保证命令构造与回执语义；读读数面（pm path/-d 集合回读/导出小
// APK 只读拉取）已过真机实测。

/**
 * 系统应用禁启内核（设备观测域）：`pm disable-user --user 0`/`pm enable` 执行 +
 * `pm list packages -d` 已禁用集合探测。
 *
 * 确认分层契约：本内核只收「执行」参数不做确认——pm disable-user 是改设备状态的
 * 高危动作，确认对话归 VM/面板层（与推送覆盖确认同款分层），内核被调用即执行。
 *
 * 通道纪律（与 [DevicePackageBrowser] 同链口径）：三命令经
 * [listDisabledPackagesCommand]/[disablePackageCommand]/[enablePackageCommand]
 * 构造——包名 token 设备侧单引号零展开，payload 按本机 shell 语义包裹后经
 * [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`；执行走 [AdbProcessRunner]
 * （由宿主机注入），fake 注入即可单测。
 *
 * 已禁用集合探测：`pm list packages -d --show-versioncode -U` 与包列表同命令形态
 * 同解析器（`-d` 旗标筛选已禁用包，行形态与全量列举同构——集合成员即当前已禁用
 * 包，禁启钮按集合成员差显其一）。
 */
class DevicePackageToggler(
    private val runner: AdbProcessRunner,
    private val endpointReader: () -> AdbEndpoint,
    private val log: AdbLog = AdbLog.NONE,
) {

    /**
     * 探测设备当前已禁用包集合（`pm list packages -d`）：返回包名集合，成员 =
     * 当前处于禁用态的包（禁启钮显其一的数据源）。空串号零 spawn 人话拒绝。
     */
    suspend fun disabledPackages(serial: String): Result<Set<String>> {
        if (serial.isEmpty()) {
            return Result.failure(IllegalStateException("设备串号为空，未执行已禁用包集合探测"))
        }
        val endpoint = endpointReader()
        val shell = runner.execute(listDisabledPackagesCommand(endpoint.executablePath, serial))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (shell.exitCode != 0) {
            return Result.failure(
                IllegalStateException("已禁用包集合探测失败（adb 退出码 ${shell.exitCode}）：${shellErrorText(shell)}"),
            )
        }
        return try {
            Result.success(DevicePackageParsers.parsePackageList(shell.stdout).map { it.first }.toSet())
        } catch (e: IllegalArgumentException) {
            Result.failure(IllegalStateException("已禁用包集合${e.message}"))
        }
    }

    /** 禁用包（`pm disable-user --user 0 <pkg>`）：被调用即执行，确认归调用面。 */
    suspend fun disablePackage(packageName: String, serial: String): Result<Unit> =
        runToggle(packageName, serial, "禁用") { adbPath -> disablePackageCommand(packageName, adbPath, serial) }

    /** 启用包（`pm enable <pkg>`）：被调用即执行，确认归调用面。 */
    suspend fun enablePackage(packageName: String, serial: String): Result<Unit> =
        runToggle(packageName, serial, "启用") { adbPath -> enablePackageCommand(packageName, adbPath, serial) }

    /** 禁/启共用执行体：退出 0 即成功（pm 回显是信息面不校验），非零透传设备侧原文 */
    private suspend fun runToggle(
        packageName: String,
        serial: String,
        actionText: String,
        command: suspend (adbPath: String) -> String,
    ): Result<Unit> {
        if (packageName.isEmpty() || serial.isEmpty()) {
            return Result.failure(IllegalStateException("包名或设备串号为空，${actionText}未执行"))
        }
        val shell = runner.execute(command(endpointReader().executablePath))
            .getOrElse { e -> return adbSpawnFailure(e) }
        if (shell.exitCode != 0) {
            return Result.failure(
                IllegalStateException("${actionText}失败（adb 退出码 ${shell.exitCode}）：${shellErrorText(shell)}"),
            )
        }
        return Result.success(Unit)
    }

    /** adb 进程真失败（可执行缺席等）统一人话回执 */
    private fun adbSpawnFailure(e: Throwable): Result<Nothing> =
        Result.failure(IllegalStateException("adb 执行失败：${e.message ?: "未知异常"}"))

    companion object {

        /** 已禁用包集合命令全文（internal 供单测逐字节断言）：`-d` 旗标筛选已禁用包，行形态与全量列举同构共用解析器 */
        internal fun listDisabledPackagesCommand(adbPath: String, serial: String): String {
            val payload = "pm list packages -d --show-versioncode -U"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }

        /** 禁用命令全文（internal 供单测逐字节断言）：`--user 0` 钉死主用户——多用户形态不做用户面选择，禁用态恒落主用户 */
        internal fun disablePackageCommand(packageName: String, adbPath: String, serial: String): String {
            val payload = "pm disable-user --user 0 ${DeviceFileBrowser.deviceShellQuote(packageName)}"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }

        /** 启用命令全文（internal 供单测逐字节断言） */
        internal fun enablePackageCommand(packageName: String, adbPath: String, serial: String): String {
            val payload = "pm enable ${DeviceFileBrowser.deviceShellQuote(packageName)}"
            return AdbCommandRewriter.rewrite(
                "adb shell ${DeviceFileBrowser.wrapPayloadForHostShell(payload)}",
                adbPath,
                serial,
            )
        }
    }
}
