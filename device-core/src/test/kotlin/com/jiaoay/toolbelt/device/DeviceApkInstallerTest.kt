package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * APK 安装内核守护（fake install gateway + fake 包列举 gateway 注入，零真 adb 零真
 * 安装——真机安装执行按门禁归维护者，本层只验内核契约）：
 * - 通道纪律逐字节断言：`adb install -r` 旗标 + 本地路径单引号零展开（含空格/
 *   内嵌单引号转义）+ AdbCommandRewriter 注入 adb 路径与 `-s <serial>`；
 * - 结果解析：Success 标记行 / Failure [原因] 标记行优先于退出码（老 adb 版本
 *   Failure 仍退出 0）；标记缺席按退出码兜底（零 = 成功回显原文进回执）；
 * - 追溯档位：基线档（文件名 + 大小 + adb 回显原文）恒在场；升档差集（新装形态
 *   包名 + dumpsys versionName / 升级覆盖形态差集空如实注明 / 列举失败降档注明 /
 *   多新包不猜不采）；
 * - 取消收割：协程取消时在途 adb 进程先 kill 再放行取消异常（已确认的安装不得
 *   脱离监督继续落机）；
 * - 本地路径不存在/空参数零 spawn 拒绝。
 *
 * 🔴 单测不碰用户数据根：本地源文件一律落在临时目录。
 */
class DeviceApkInstallerTest {

    /** fake 安装句柄：killed 标记供取消用例断言「kill 经句柄触达」 */
    private class RecordingHandle : AdbInstallHandle {
        var killed = false
        override fun kill() {
            killed = true
        }
    }

    /** gateway fake：记录命令全文（逐字节断言锚），按脚本回执 */
    private class FakeInstallGateway(
        private val script: suspend (command: String, handle: AdbInstallHandle, onLine: suspend (ShellOutput) -> Unit) -> Int,
    ) : AdbInstallGateway {
        val commands = mutableListOf<String>()
        override suspend fun execute(
            command: String,
            onStarted: (AdbInstallHandle) -> Unit,
            onLine: suspend (ShellOutput) -> Unit,
        ): Result<Int> {
            commands += command
            val handle = RecordingHandle()
            onStarted(handle)
            return Result.success(script(command, handle, onLine))
        }
    }

    /**
     * 包列举/详情 gateway fake：按命令面路由——`pm list packages`（全部包）与
     * `-s`（系统包）各自按序弹出脚本输出，`dumpsys package` 按序弹出详情结果
     * （包名从命令中按序取，不作键）。零真 adb。
     */
    private class FakePkgGateway(
        allLists: List<String> = emptyList(),
        systemLists: List<String> = emptyList(),
        detailResults: List<Result<ShellResult>> = emptyList(),
    ) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        private val allLists = ArrayDeque(allLists)
        private val systemLists = ArrayDeque(systemLists)
        private val detailResults = ArrayDeque(detailResults)

        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return when {
                "list packages -s" in command ->
                    Result.success(ShellResult(0, systemLists.removeFirst(), ""))
                "list packages" in command ->
                    Result.success(ShellResult(0, allLists.removeFirst(), ""))
                "dumpsys package" in command -> detailResults.removeFirst()
                else -> error("包域 fake 未预期的命令：$command")
            }
        }
    }

    private fun tempDir(): java.io.File = Files.createTempDirectory("device-install").toFile()

    /** 本地源 APK（临时目录，缺省 512 字节） */
    private fun localApk(dir: java.io.File, name: String = "app.apk", size: Int = 512): java.io.File =
        java.io.File(dir, name).apply { writeBytes(ByteArray(size)) }

    private fun installer(gateway: AdbInstallGateway, pkgGateway: AdbProcessRunner): DeviceApkInstaller =
        DeviceApkInstaller(
            runner = AdbProcessRunner { Result.success(ShellResult(0, "", "")) },
            gateway = gateway,
            packageBrowser = DevicePackageBrowser(runner = pkgGateway, endpointReader = { AdbEndpoint("") }),
            endpointReader = { AdbEndpoint("") },
        )

    /** 安装成功脚本（流式安装引导行 + Success 标记行 + 退出码 0） */
    private fun successScript(): suspend (String, AdbInstallHandle, suspend (ShellOutput) -> Unit) -> Int =
        { _, _, onLine ->
            onLine(ShellOutput("Performing Streamed Install", ShellOutputSource.STDOUT))
            onLine(ShellOutput("Success", ShellOutputSource.STDOUT))
            0
        }

    /** `pm list packages` 输出（一行一包三段定序） */
    private fun packageList(vararg names: String): String =
        names.joinToString("\n") { "package:$it versionCode:7 uid:10234" }

    /** `dumpsys package <pkg>` 单包记录 fixture（真机实测结构裁样本） */
    private fun detailsFixture(pkg: String, versionName: String?): String = """
        Queries:
          system apps queryable: false

        Packages:
          Package [$pkg] (72b58e2):
            userId=10234
            codePath=/data/app/~~x==$pkg
            versionCode=7 minSdk=28 targetSdk=30
            versionName=${versionName ?: "null"}
            firstInstallTime=2025-01-01 08:00:00
            lastUpdateTime=2025-01-01 08:00:00
            requested permissions:
              android.permission.CAMERA

        Shared users:
    """.trimIndent()

    // ── 命令整形（-r 旗标 + 串号注入 + 引号零展开） ─────────────────────────────

    @Test
    fun `install command carries -r flag serial injection and literal local path`() {
        assertEquals(
            "adb -s S1 install -r '/tmp/drop/app.apk'",
            DeviceApkInstaller.installCommand("/tmp/drop/app.apk", adbPath = "", serial = "S1"),
            "命令 = adb -s <serial> install -r + 本地路径单引号零展开",
        )
        val escaped = "/tmp/my dir/it's.app".replace("'", "'\\''")
        assertEquals(
            "'/opt/my adb' -s S1 install -r '$escaped'",
            DeviceApkInstaller.installCommand("/tmp/my dir/it's.app", adbPath = "/opt/my adb", serial = "S1"),
            "本地路径含空格与内嵌单引号必须字面化（'\\'' 重开闭转义），adb 路径含空格同样包裹",
        )
    }

    // ── 成功解析（Success 标记行 → 基线档回执 + 升档差集） ──────────────────────

    @Test
    fun `new package install yields receipt with echo identity package and version`() = runTest {
        val dir = tempDir()
        val local = localApk(dir, "app.apk", size = 1024)
        val installGateway = FakeInstallGateway(successScript())
        // 升档差集：安装前只有 com.a，安装后多出 com.b（差集恰一新包）→ 详情取 versionName
        val pkgGateway = FakePkgGateway(
            allLists = listOf(packageList("com.a"), packageList("com.a", "com.b")),
            systemLists = listOf(packageList("com.a"), packageList("com.a")),
            detailResults = listOf(
                Result.success(ShellResult(0, detailsFixture("com.b", "1.2.3"), "")),
            ),
        )
        val receipt = installer(installGateway, pkgGateway)
            .installApk(local.absolutePath, "S1")
            .getOrThrow()

        assertEquals(
            listOf("adb -s S1 install -r '${local.absolutePath}'"),
            installGateway.commands,
            "安装命令恒走 -r 旗标 + 串号注入链",
        )
        assertEquals("app.apk", receipt.localFileName, "基线档：本地文件名")
        assertEquals(1024L, receipt.sizeBytes, "基线档：本地大小")
        assertEquals("Performing Streamed Install\nSuccess", receipt.adbOutput, "基线档：adb 回显原文不翻译不截断")
        assertEquals("com.b", receipt.packageName, "升档档：差集新装包名实证进回执")
        assertEquals("1.2.3", receipt.versionName, "升档档：dumpsys versionName 进回执")
        assertNull(receipt.identityNote, "升档全要素在场无缺席注明")
        assertEquals(5, pkgGateway.commands.size, "前列举 2 + 后列举 2 + 详情 1（包列举/详情走既有包浏览器命令面）")
    }

    @Test
    fun `upgrade overwrite with empty diff degrades identity with honest note`() = runTest {
        val dir = tempDir()
        val local = localApk(dir, "app.apk")
        val installGateway = FakeInstallGateway(successScript())
        // -r 装已有包形态：前后包集相同，差集为空 → 如实回落文件名档
        val pkgGateway = FakePkgGateway(
            allLists = listOf(packageList("com.a"), packageList("com.a")),
            systemLists = listOf(packageList("com.a"), packageList("com.a")),
        )
        val receipt = installer(installGateway, pkgGateway)
            .installApk(local.absolutePath, "S1")
            .getOrThrow()

        assertEquals("app.apk", receipt.localFileName, "基线档恒在场（升档缺席不否定安装）")
        assertEquals("Performing Streamed Install\nSuccess", receipt.adbOutput, "回显原文进回执")
        assertNull(receipt.packageName, "差集空不猜包名（禁止从文件名猜）")
        assertNull(receipt.versionName)
        assertEquals("升级覆盖，包名未识别", receipt.identityNote, "升级覆盖形态如实注明")
    }

    // ── 失败解析（标记行优先于退出码 + 兜底口径） ────────────────────────────────

    @Test
    fun `failure marker line fails receipt with reason verbatim even on zero exit`() = runTest {
        val dir = tempDir()
        val local = localApk(dir)
        val installGateway = FakeInstallGateway { _, _, onLine ->
            onLine(ShellOutput("Performing Streamed Install", ShellOutputSource.STDOUT))
            onLine(ShellOutput("Failure [INSTALL_FAILED_ALREADY_EXISTS]", ShellOutputSource.STDOUT))
            0 // 老 adb 版本 Failure 仍退出 0——标记行优先
        }
        val pkgGateway = FakePkgGateway(
            allLists = listOf(packageList("com.a")),
            systemLists = listOf(packageList("com.a")),
        )
        val message = installer(installGateway, pkgGateway)
            .installApk(local.absolutePath, "S1")
            .exceptionOrNull()
            ?.message ?: ""

        assertTrue(message.contains("Failure [INSTALL_FAILED_ALREADY_EXISTS]"), "失败原因原文透传（实际：$message）")
        assertTrue(message.contains("安装失败"), "失败回执人话（实际：$message）")
        assertEquals(1, installGateway.commands.size, "安装只 spawn 一次")
        assertEquals(2, pkgGateway.commands.size, "失败路径止于前列举（无后列举无详情）")
    }

    @Test
    fun `unmarked echo falls back to exit code with output preserved`() = runTest {
        val dir = tempDir()
        val local = localApk(dir)

        val failed = FakeInstallGateway { _, _, onLine ->
            onLine(ShellOutput("adb: error: failed to stat", ShellOutputSource.STDERR))
            1
        }
        val failedMessage = installer(
            failed,
            FakePkgGateway(
                allLists = listOf(packageList("com.a")),
                systemLists = listOf(packageList("com.a")),
            ),
        ).installApk(local.absolutePath, "S1")
            .exceptionOrNull()?.message ?: ""
        assertTrue(failedMessage.contains("adb 退出码 1"), "非零退出无标记行按退出码失败（实际：$failedMessage）")
        assertTrue(failedMessage.contains("adb: error: failed to stat"), "stderr 原文透传（实际：$failedMessage）")

        val oddZero = FakeInstallGateway { _, _, onLine ->
            onLine(ShellOutput("done-ish", ShellOutputSource.STDOUT))
            0
        }
        // 前后列举同集（升级覆盖形态）——升档缺席按差集空口径如实注明
        val oddLists = FakePkgGateway(
            allLists = listOf(packageList("com.a"), packageList("com.a")),
            systemLists = listOf(packageList("com.a"), packageList("com.a")),
        )
        val receipt = installer(oddZero, oddLists).installApk(local.absolutePath, "S1").getOrThrow()
        assertEquals("done-ish", receipt.adbOutput, "零退出无标记行兜底为成功且回显原文原样进回执（向已安装侧倾斜）")
        assertEquals("升级覆盖，包名未识别", receipt.identityNote, "升档缺席如实注明（实际：${receipt.identityNote}）")
    }

    // ── 升档降档面（列举失败/详情失败/多新包——不猜不造） ────────────────────────

    @Test
    fun `identity degrades with honest notes when listing or details fail`() = runTest {
        val dir = tempDir()
        val local = localApk(dir)

        // 前列举失败（退出非零）→ 包名缺席注明，安装主流程不被阻断
        val beforeFail = FakePkgGateway(
            allLists = emptyList(),
            systemLists = emptyList(),
        ).let { gateway ->
            object : AdbProcessRunner by gateway {
                override suspend fun execute(command: String): Result<ShellResult> {
                    if ("list packages" in command) {
                        return Result.success(ShellResult(1, "", "Error: Unknown option: --show-versioncode"))
                    }
                    return gateway.execute(command)
                }
            }
        }
        val beforeFailReceipt = installer(FakeInstallGateway(successScript()), beforeFail)
            .installApk(local.absolutePath, "S1")
            .getOrThrow()
        assertNull(beforeFailReceipt.packageName)
        assertTrue(
            beforeFailReceipt.identityNote!!.contains("安装前包列举失败"),
            "前列举失败如实注明（实际：${beforeFailReceipt.identityNote}）",
        )

        // 后列举失败 → 同降档口径
        val afterFailScript = object : AdbProcessRunner {
            var calls = 0
            override suspend fun execute(command: String): Result<ShellResult> {
                calls++
                return if (calls <= 2) {
                    Result.success(ShellResult(0, packageList("com.a"), ""))
                } else {
                    Result.success(ShellResult(1, "", "adb: error"))
                }
            }
        }
        val afterFailReceipt = installer(FakeInstallGateway(successScript()), afterFailScript)
            .installApk(local.absolutePath, "S1")
            .getOrThrow()
        assertTrue(
            afterFailReceipt.identityNote!!.contains("安装后包列举失败"),
            "后列举失败如实注明（实际：${afterFailReceipt.identityNote}）",
        )

        // 多新包（一次安装引入两个新包的异常形态）→ 不猜不采
        val multiNew = FakePkgGateway(
            allLists = listOf(packageList("com.a"), packageList("com.a", "com.b", "com.c")),
            systemLists = listOf(packageList("com.a"), packageList("com.a")),
        )
        val multiReceipt = installer(FakeInstallGateway(successScript()), multiNew)
            .installApk(local.absolutePath, "S1")
            .getOrThrow()
        assertNull(multiReceipt.packageName, "多新包不猜包名")
        assertTrue(
            multiReceipt.identityNote!!.contains("无法唯一判别"),
            "多新包如实注明（实际：${multiReceipt.identityNote}）",
        )

        // 详情失败（差集包名实证在场，版本名缺席注明原因）
        val detailFail = FakePkgGateway(
            allLists = listOf(packageList("com.a"), packageList("com.a", "com.b")),
            systemLists = listOf(packageList("com.a"), packageList("com.a")),
            detailResults = listOf(Result.success(ShellResult(0, "无 Package 记录段", ""))),
        )
        val detailReceipt = installer(FakeInstallGateway(successScript()), detailFail)
            .installApk(local.absolutePath, "S1")
            .getOrThrow()
        assertEquals("com.b", detailReceipt.packageName, "差集实证包名保留（不因详情失败连坐降档）")
        assertNull(detailReceipt.versionName)
        assertTrue(
            detailReceipt.identityNote!!.contains("版本名读取失败"),
            "详情失败如实注明（实际：${detailReceipt.identityNote}）",
        )
    }

    // ── 取消收割（在途 adb 进程先 kill 再放行取消异常） ──────────────────────────

    @Test
    fun `scope cancellation kills in flight adb process and rethrows`() = runTest {
        val dir = tempDir()
        val local = localApk(dir)
        lateinit var handle: AdbInstallHandle
        val installGateway = FakeInstallGateway { _, startedHandle, _ ->
            handle = startedHandle
            throw CancellationException("窗关 scope 取消")
        }
        val caught = try {
            installer(
                installGateway,
                FakePkgGateway(
                    allLists = listOf(packageList("com.a")),
                    systemLists = listOf(packageList("com.a")),
                ),
            ).installApk(local.absolutePath, "S1")
            null
        } catch (e: CancellationException) {
            e
        }
        assertTrue(caught != null, "协程取消原样放行（不吞成失败回执）")
        assertTrue((handle as RecordingHandle).killed, "取消时在途 adb 进程先经句柄收割（已确认的安装不得脱离监督继续落机）")
    }

    // ── 本地侧拒绝（零 spawn） ───────────────────────────────────────────────────

    @Test
    fun `local path missing or blank args reject without any spawn`() = runTest {
        val dir = tempDir()
        val installGateway = FakeInstallGateway { _, _, _ -> 0 }
        val pkgGateway = FakePkgGateway()
        val installer = installer(installGateway, pkgGateway)

        val missing = installer.installApk("${dir.absolutePath}/absent.apk", "S1")
        assertTrue((missing.exceptionOrNull()?.message ?: "").contains("本地 APK 不存在"))
        installer.installApk(dir.absolutePath, "S1") // 目录不是普通文件
        installer.installApk("", "S1")
        installer.installApk("${dir.absolutePath}/x.apk", "")
        assertEquals(0, installGateway.commands.size, "本地校验/空参数零 adb spawn")
        assertEquals(0, pkgGateway.commands.size, "零包列举命令（本地侧拒绝先于设备侧探测）")
    }

    // ── 标记行解析矩阵（internal 解析锚） ───────────────────────────────────────

    @Test
    fun `install echo parsing anchors on exact markers and strips pty tail`() {
        assertEquals("Success", DeviceApkInstaller.parseInstallEcho(listOf("Performing Streamed Install", "Success\r", " ")))
        assertEquals(
            "Failure [INSTALL_FAILED_INVALID_APK]",
            DeviceApkInstaller.parseInstallEcho(listOf("Failure [INSTALL_FAILED_INVALID_APK]")),
        )
        assertEquals(
            "Failure 大小不匹配",
            DeviceApkInstaller.parseInstallEcho(listOf("x", "Failure 大小不匹配")),
            "Failure 后自由文本形态同收（原因原文是回执面）",
        )
        assertNull(DeviceApkInstaller.parseInstallEcho(listOf("Successful", "Failure-ish")), "近似词不是标记行")
        assertNull(DeviceApkInstaller.parseInstallEcho(emptyList()), "无回显为 null（成败判定归退出码兜底）")
        assertEquals(
            "Success",
            DeviceApkInstaller.parseInstallEcho(listOf("Success", "Failure [x]")),
            "首个标记行胜出（adb 契约恒打印其一，多标记是异常形态不猜后行）",
        )
    }
}
