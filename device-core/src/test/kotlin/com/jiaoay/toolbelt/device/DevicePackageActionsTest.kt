package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 设备应用动作内核守护（APK 导出 + 系统应用禁启，fake runner + fake stat 注入，
 * 零真子进程）：
 * - pm path 解析矩阵：单行/多 split 多行按行收集/pty `\r` 剥除/坏行显式失败/
 *   空输出空列表（空集的显式失败判定在导出内核层）；
 * - 通道纪律逐字节断言：pm path/禁用/启用/已禁用集合四命令全文 + 导出逐 split
 *   的 adb pull 命令全文（复用拉取内核双路径单引号零展开 + .part 后缀）；
 * - 导出编排：多 split 顺序拉回、本地目标名防覆盖（包名前缀 + 序号后缀）、
 *   onFileStart 进度锚（序号/总数/目标名/期望大小）、完成回执逐文件；
 * - 失败语义：缺席包（退出 1 空输出）与退出 0 空输出均显式失败「包未安装或无
 *   APK」且零拉取 spawn；中途 split 失败整体失败、已完成文件不回滚；
 * - 禁启：内核只收执行参数（确认归调用面），退出 0 成功/非零透传原文/空参数
 *   零 spawn；已禁用集合探测共用包列表解析器。
 *
 * 🔴 单测不碰用户数据根：导出目标目录一律临时目录。
 */

/** fake stat 的回执构造（文件顶层：嵌套 fake 类内 lambda 可直调，无外类接收者依赖） */
private fun statOf(size: Long, type: DeviceFileType = DeviceFileType.FILE) = DeviceFileStat(
    type = type,
    sizeBytes = size,
    modifiedEpochSeconds = 1_700_000_000L,
    permissions = "-rw-r--r--",
    owner = "root",
    group = "root",
)

class DevicePackageActionsTest {

    /** pm/dumpsys 通道 fake：记录命令全文（逐字节断言锚），按脚本回执 */
    private class FakeDetectGateway(private val respond: suspend (String) -> ShellResult) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return Result.success(respond(command))
        }
    }

    /** 拉取通道 fake：记录命令全文（导出逐 split 命令断言锚），按脚本回执并模拟写 .part */
    private class FakePullGateway(
        private val script: suspend (command: String, onLine: suspend (ShellOutput) -> Unit) -> Int,
    ) : AdbPullGateway {
        val commands = mutableListOf<String>()
        override suspend fun execute(
            command: String,
            onStarted: (AdbPullHandle) -> Unit,
            onLine: suspend (ShellOutput) -> Unit,
        ): Result<Int> {
            commands += command
            onStarted(AdbPullHandle { })
            return Result.success(script(command, onLine))
        }
    }

    /** fake stat：按设备路径路由大小（导出前置 stat 与拉取内核内部 stat 各持一份） */
    private class SizeStat(private val sizeFor: (String) -> Long) {
        val calls = mutableListOf<String>()
        val entry: suspend (String, String) -> Result<DeviceFileStat> = { path, _ ->
            calls += path
            Result.success(statOf(sizeFor(path)))
        }
    }

    private fun tempDir(): java.io.File = Files.createTempDirectory("device-apk-export").toFile()

    /** 多 split pm path fixture（base + arm64 split，真机形态路径） */
    private val pmPathFixture = """
        package:/data/app/~~aXb/com.demo.app-Q1w2e3/base.apk
        package:/data/app/~~aXb/com.demo.app-Q1w2e3/split_config.arm64_v8a.apk
    """.trimIndent()

    /** 导出内核装配：pm 通道 + 拉取内核（fake pull 通道 + fake 拉取内部 stat）+ fake 导出前置 stat（两个 stat 同值路由） */
    private fun exporter(
        detect: AdbProcessRunner,
        pull: AdbPullGateway,
        sizeFor: (String) -> Long = { 1L },
    ): DeviceApkExporter = DeviceApkExporter(
        runner = detect,
        puller = DeviceFilePuller(
            runner = detect,
            gateway = pull,
            statEntry = SizeStat(sizeFor).entry,
            endpointReader = { AdbEndpoint("") },
        ),
        statEntry = SizeStat(sizeFor).entry,
        endpointReader = { AdbEndpoint("") },
    )

    private fun toggler(runner: AdbProcessRunner) = DevicePackageToggler(runner = runner, endpointReader = { AdbEndpoint("") })

    /** 从 pull 命令全文取末个单引号 token（本地 .part 路径）——模拟 adb 落 .part 用 */
    private fun partPathOf(command: String): String =
        command.substringAfterLast(" '").removeSuffix("'")

    // ── pm path 解析矩阵（纯函数直测）──

    @Test
    fun `parseApkPaths collects multi-split lines in output order`() {
        assertEquals(
            listOf(
                "/data/app/~~aXb/com.demo.app-Q1w2e3/base.apk",
                "/data/app/~~aXb/com.demo.app-Q1w2e3/split_config.arm64_v8a.apk",
            ),
            DevicePackageParsers.parseApkPaths(pmPathFixture),
            "多 split 一行一路径按行收集保持输出序",
        )
    }

    @Test
    fun `parseApkPaths strips pty carriage returns and returns empty for blank output`() {
        assertEquals(
            listOf("/data/app/com.single/base.apk"),
            DevicePackageParsers.parseApkPaths("package:/data/app/com.single/base.apk\r\n"),
            "pty \\r 剥除",
        )
        assertEquals(emptyList(), DevicePackageParsers.parseApkPaths(""), "空输出 = 空列表（显式失败判定在导出内核层）")
        assertEquals(emptyList(), DevicePackageParsers.parseApkPaths("\n \n"), "纯空白行跳过")
    }

    @Test
    fun `parseApkPaths fails on unrecognized line`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parseApkPaths("package:/data/app/base.apk extra-token")
        }
        assertTrue(e.message!!.contains("格式不符"), "坏行显式失败带原行样例（实际：${e.message}）")
        assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parseApkPaths("not-a-package-line")
        }
    }

    // ── pm path 通道纪律与列举调度 ──

    @Test
    fun `pmPathCommand is byte-exact with device-side quoted package name`() {
        assertEquals(
            """adb -s S1 shell 'pm path '\''com.demo.app'\'''""",
            DeviceApkExporter.pmPathCommand("com.demo.app", "", "S1"),
            "pm path 命令逐字节成立：包名设备侧单引号零展开，payload 整体经本机 shell 转义包裹",
        )
        assertEquals(
            """'/opt/my adb' -s S1 shell 'pm path '\''com.demo.app'\'''""",
            DeviceApkExporter.pmPathCommand("com.demo.app", "/opt/my adb", "S1"),
            "含空格 adb 路径按执行 shell 语义单引号包裹",
        )
    }

    @Test
    fun `listApkPaths parses multi-split device paths`() = runTest {
        val runner = FakeDetectGateway { ShellResult(0, pmPathFixture, "") }
        val paths = exporter(runner, FakePullGateway { _, _ -> 0 })
            .listApkPaths("com.demo.app", "S1")

        assertEquals(
            listOf("""adb -s S1 shell 'pm path '\''com.demo.app'\'''"""),
            runner.commands,
        )
        assertEquals(
            listOf(
                "/data/app/~~aXb/com.demo.app-Q1w2e3/base.apk",
                "/data/app/~~aXb/com.demo.app-Q1w2e3/split_config.arm64_v8a.apk",
            ),
            paths.getOrThrow(),
        )
    }

    @Test
    fun `listApkPaths fails explicitly for absent package with empty output and exit 1`() = runTest {
        val runner = FakeDetectGateway { ShellResult(1, "", "") }
        val result = exporter(runner, FakePullGateway { _, _ -> 0 })
            .listApkPaths("com.absent.pkg", "S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("退出码 1"), "缺席包走退出非零分支（实际：${e.message}）")
        assertTrue(e.message!!.contains("包未安装或无 APK"), "空输出回执点名缺席语义（实际：${e.message}）")
    }

    @Test
    fun `listApkPaths fails explicitly when exit 0 but output empty`() = runTest {
        val runner = FakeDetectGateway { ShellResult(0, "\n", "") }
        val result = exporter(runner, FakePullGateway { _, _ -> 0 })
            .listApkPaths("com.absent.pkg", "S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("包未安装或无 APK"), "退出 0 空输出不冒充合法读数（实际：${e.message}）")
        assertTrue(e.message!!.contains("com.absent.pkg"), "回执带请求包名（实际：${e.message}）")
    }

    @Test
    fun `listApkPaths passes through device stderr on nonzero exit`() = runTest {
        val runner = FakeDetectGateway { ShellResult(1, "", "Error: Could not find package") }
        val result = exporter(runner, FakePullGateway { _, _ -> 0 })
            .listApkPaths("com.demo.app", "S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("APK 路径列举失败"), "非零退出人话回执（实际：${e.message}）")
        assertTrue(e.message!!.contains("Error: Could not find package"), "设备侧错误原文透传（实际：${e.message}）")
    }

    @Test
    fun `listApkPaths rejects empty params without spawn`() = runTest {
        val detect = FakeDetectGateway { ShellResult(0, pmPathFixture, "") }
        val pull = FakePullGateway { _, _ -> 0 }
        val kernel = exporter(detect, pull) { 1L }

        assertTrue(kernel.listApkPaths("", "S1").isFailure, "空包名零 spawn 拒绝")
        assertTrue(kernel.listApkPaths("com.demo.app", "").isFailure, "空串号零 spawn 拒绝")
        assertEquals(emptyList(), detect.commands, "拒绝路径零 exec")
    }

    // ── 导出编排 ──

    @Test
    fun `exportApk pulls each split with byte-exact commands and promotes files`() = runTest {
        val dir = tempDir()
        val detect = FakeDetectGateway { ShellResult(0, pmPathFixture, "") }
        val pull = FakePullGateway { command, onLine ->
            val part = java.io.File(partPathOf(command))
            val size = if (command.contains("split_config")) 128L else 512L
            part.writeBytes(ByteArray(size.toInt()))
            onLine(ShellOutput("1 file pulled, 0 skipped. 38.4 MB/s ($size bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }
        val progress = mutableListOf<DeviceApkExportFileStart>()
        val result = exporter(detect, pull) { if (it.contains("split_config")) 128L else 512L }
            .exportApk("com.demo.app", dir, "S1") { progress += it }

        assertEquals(
            listOf(
                "adb -s S1 pull '/data/app/~~aXb/com.demo.app-Q1w2e3/base.apk' " +
                    "'${dir.absolutePath}/com.demo.app.apk.part'",
                "adb -s S1 pull '/data/app/~~aXb/com.demo.app-Q1w2e3/split_config.arm64_v8a.apk' " +
                    "'${dir.absolutePath}/com.demo.app-2.apk.part'",
            ),
            pull.commands,
            "导出逐 split 命令逐字节成立：首个 split 目标名 = 包名，后续 split 追加序号防覆盖，.part 后缀语义复用拉取内核",
        )
        val receipt = result.getOrThrow()
        assertEquals(2, receipt.files.size, "回执逐 split 一条拉取记录")
        assertEquals(512L, receipt.files[0].sizeBytes)
        assertEquals(128L, receipt.files[1].sizeBytes)
        assertEquals("${dir.absolutePath}/com.demo.app.apk", receipt.files[0].localPath)
        assertEquals("${dir.absolutePath}/com.demo.app-2.apk", receipt.files[1].localPath)
        assertTrue(java.io.File("${dir.absolutePath}/com.demo.app.apk").exists(), "首个 split 转正在位")
        assertTrue(java.io.File("${dir.absolutePath}/com.demo.app-2.apk").exists(), "后续 split 转正在位")

        assertEquals(
            listOf(
                DeviceApkExportFileStart("com.demo.app", 0, 2, "com.demo.app.apk", "/data/app/~~aXb/com.demo.app-Q1w2e3/base.apk", 512L),
                DeviceApkExportFileStart("com.demo.app", 1, 2, "com.demo.app-2.apk", "/data/app/~~aXb/com.demo.app-Q1w2e3/split_config.arm64_v8a.apk", 128L),
            ),
            progress,
            "onFileStart 进度锚：序号/总数/目标名/期望大小逐 split 回调",
        )
    }

    @Test
    fun `exportApk single split keeps plain package file name`() = runTest {
        val dir = tempDir()
        val detect = FakeDetectGateway { ShellResult(0, "package:/data/app/com.single/base.apk", "") }
        val pull = FakePullGateway { command, onLine ->
            val part = java.io.File(partPathOf(command))
            part.writeBytes(ByteArray(64))
            onLine(ShellOutput("1 file pulled, 0 skipped. (64 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }
        val receipt = exporter(detect, pull) { 64L }
            .exportApk("com.single", dir, "S1")
            .getOrThrow()

        assertEquals(1, receipt.files.size)
        assertEquals("${dir.absolutePath}/com.single.apk", receipt.files[0].localPath, "单 split 目标名 = 包名（无序号后缀）")
    }

    @Test
    fun `exportApk aborts on split failure and keeps completed files`() = runTest {
        val dir = tempDir()
        val detect = FakeDetectGateway { ShellResult(0, pmPathFixture, "") }
        val pull = FakePullGateway { command, onLine ->
            if (command.contains("split_config")) {
                onLine(ShellOutput("adb: error: failed to stat", ShellOutputSource.STDERR))
                1
            } else {
                val part = java.io.File(partPathOf(command))
                part.writeBytes(ByteArray(512))
                0
            }
        }
        val result = exporter(detect, pull) { 512L }
            .exportApk("com.demo.app", dir, "S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("APK 导出中断"), "中途失败整体失败（实际：${e.message}）")
        assertTrue(e.message!!.contains("2/2"), "回执点名中断点（实际：${e.message}）")
        assertTrue(e.message!!.contains("不回滚"), "已完成文件不回滚入回执（实际：${e.message}）")
        assertTrue(java.io.File("${dir.absolutePath}/com.demo.app.apk").exists(), "首个 split 已转正不回滚")
        assertEquals(2, pull.commands.size, "失败后不再发起后续 split（本例 split 2 是末个）")
    }

    @Test
    fun `exportApk for absent package fails with zero pull spawns`() = runTest {
        val dir = tempDir()
        val detect = FakeDetectGateway { ShellResult(1, "", "") }
        val pull = FakePullGateway { _, _ -> 0 }
        val result = exporter(detect, pull) { 1L }
            .exportApk("com.absent.pkg", dir, "S1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("包未安装或无 APK"), "缺席包显式失败（实际：${result.exceptionOrNull()!!.message}）")
        assertEquals(emptyList(), pull.commands, "缺席包零拉取 spawn")
    }

    @Test
    fun `apkExportFileName dedupes by package prefix and split ordinal`() {
        assertEquals("com.demo.app.apk", DeviceApkExporter.apkExportFileName("com.demo.app", 0))
        assertEquals("com.demo.app-2.apk", DeviceApkExporter.apkExportFileName("com.demo.app", 1))
        assertEquals("com.demo.app-3.apk", DeviceApkExporter.apkExportFileName("com.demo.app", 2))
    }

    // ── 禁启命令构造与执行 ──

    @Test
    fun `toggle commands are byte-exact with device-side quoted package name`() {
        assertEquals(
            """adb -s S1 shell 'pm list packages -d --show-versioncode -U'""",
            DevicePackageToggler.listDisabledPackagesCommand("", "S1"),
        )
        assertEquals(
            """adb -s S1 shell 'pm disable-user --user 0 '\''com.demo.app'\'''""",
            DevicePackageToggler.disablePackageCommand("com.demo.app", "", "S1"),
            "禁用命令逐字节成立：--user 0 钉死主用户 + 包名设备侧单引号零展开",
        )
        assertEquals(
            """'/opt/my adb' -s S1 shell 'pm enable '\''com.demo.app'\'''""",
            DevicePackageToggler.enablePackageCommand("com.demo.app", "/opt/my adb", "S1"),
            "启用命令逐字节成立：含空格 adb 路径单引号包裹",
        )
    }

    @Test
    fun `disabledPackages parses disabled set sharing package list parser`() = runTest {
        val runner = FakeDetectGateway {
            ShellResult(
                0,
                """
                package:com.disabled.one versionCode:5 uid:10100
                package:com.disabled.two versionCode:6 uid:10101
                """.trimIndent(),
                "",
            )
        }
        val disabled = toggler(runner).disabledPackages("S1").getOrThrow()

        assertEquals(setOf("com.disabled.one", "com.disabled.two"), disabled, "已禁用集合共用包列表解析器（-d 行形态同构）")
    }

    @Test
    fun `disabledPackages passes through nonzero exit and rejects empty serial`() = runTest {
        val runner = FakeDetectGateway { ShellResult(1, "", "Error: failed to list disabled") }
        val result = toggler(runner).disabledPackages("S1")
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Error: failed to list disabled"), "非零退出原文透传")

        val quiet = FakeDetectGateway { ShellResult(0, "", "") }
        assertTrue(toggler(quiet).disabledPackages("").isFailure, "空串号零 spawn 拒绝")
        assertEquals(emptyList(), quiet.commands)
    }

    @Test
    fun `disable and enable succeed on exit zero and pass through device errors`() = runTest {
        val runner = FakeDetectGateway { ShellResult(0, "Package com.demo.app new state: disabled-user", "") }
        val kernel = toggler(runner)
        assertTrue(kernel.disablePackage("com.demo.app", "S1").isSuccess, "退出 0 即成功（pm 回显是信息面不校验）")
        assertTrue(kernel.enablePackage("com.demo.app", "S1").isSuccess)
        assertEquals(2, runner.commands.size)

        val failing = FakeDetectGateway { ShellResult(1, "", "Error: not allowed") }
        val failResult = toggler(failing).disablePackage("com.demo.app", "S1")
        assertTrue(failResult.exceptionOrNull()!!.message!!.contains("禁用失败"), "失败回执带动作名（实际：${failResult.exceptionOrNull()!!.message}）")
        assertTrue(failResult.exceptionOrNull()!!.message!!.contains("Error: not allowed"), "设备侧错误原文透传")
    }

    @Test
    fun `disable and enable reject empty params without spawn`() = runTest {
        val runner = FakeDetectGateway { ShellResult(0, "", "") }
        val kernel = toggler(runner)

        assertTrue(kernel.disablePackage("", "S1").isFailure, "空包名零 spawn 拒绝")
        assertTrue(kernel.enablePackage("com.demo.app", "").isFailure, "空串号零 spawn 拒绝")
        assertEquals(emptyList(), runner.commands, "拒绝路径零 exec")
    }
}
