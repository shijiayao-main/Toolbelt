package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设备应用包列表内核守护（fake runner 注入，零真子进程）：
 * - 通道纪律逐字节断言：三命令（全部包列举/系统包列举/单包详情）全文经
 *   [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`，payload 按本机 shell
 *   语义单引号包裹（详情命令包名 token 自带设备侧单引号）；
 * - 系统标记集合差：全部包 × 系统包集合成员 → isSystem（-3 视角不假设——
 *   全预装形态系统集 = 全集不炸，部分后装形态正确分流）；
 * - 包列表解析矩阵：正常三段行/坏行显式失败（段序互换/段缺席/多段——pm list
 *   行形态单一，任何不可识别行整体失败不静默跳过）/空列表合法读数/pty `\r` 剥除；
 * - 详情解析真机实测 fixture：versionName/firstInstallTime/uid/codePath 提取 +
 *   requested permissions 子段计数 + 记录外段终止 + versionName=null 字面量；
 *   跨 ROM 防御：记录头缺席（未安装包）/记录名不符/必备字段缺席，均显式失败
 *   带定位信息；
 * - 失败透传：pm 退出非零错误原文（含 stdout 错误形态）不翻译不吞；两次列举
 *   各自失败独立回执；空串号/空包名零 spawn 人话拒绝；adb 进程真失败人话回执。
 */
class DevicePackageBrowserTest {

    /** runner fake：记录命令全文（通道纪律断言锚），按命令内容路由回执 */
    private class FakeGateway(private val respond: suspend (String) -> ShellResult) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return Result.success(respond(command))
        }
    }

    private val allListFixture = """
        package:com.demo.media versionCode:60400000 uid:10061
        package:com.example.userapp versionCode:7 uid:10234
        package:com.android.cts.priv.ctsshim versionCode:31 uid:10022
    """.trimIndent()

    private val systemListFixture = """
        package:com.demo.media versionCode:60400000 uid:10061
        package:com.android.cts.priv.ctsshim versionCode:31 uid:10022
    """.trimIndent()

    /**
     * 真机实测结构裁样本（两用：`packageDetails` 走 fake runner 全量输出；
     * `DevicePackageParsers.parsePackageDetails` 解析矩阵直接吃）——顶层前段 +
     * Packages 记录（4 缩进 key=value + 6 缩进子段行）+ 记录外段。
     */
    private val dumpsysFixture = """
        Domain verification status:
        Failure printing domain verification information

        Queries:
          system apps queryable: false

        Packages:
          Package [com.demo.media] (72b58e2):
            userId=10061
            pkg=Package{bce3670 com.demo.media}
            codePath=/system/app/DemoDvrApp
            versionCode=60400000 minSdk=28 targetSdk=30
            versionName=V9.9.9_20990101000000000_Release
            flags=[ SYSTEM HAS_CODE PERSISTENT ALLOW_CLEAR_USER_DATA LARGE_HEAP ]
            dataDir=/data/user/0/com.demo.media
            firstInstallTime=2025-01-01 08:00:00
            lastUpdateTime=2025-01-01 08:00:00
            requested permissions:
              android.permission.RECORD_AUDIO
              android.permission.CAMERA
              android.permission.READ_EXTERNAL_STORAGE
              com.demo.permission.CABIN_CAPTURE
            install permissions:
              android.permission.WRITE_SETTINGS: granted=true
              android.permission.CONFIGURE_WIFI_DISPLAY: granted=true
            User 0:
              runtime=true

        Shared users:
          SharedUser [android.uid.system] (bf67f26):
            userId=1000
    """.trimIndent()

    private fun browser(runner: AdbProcessRunner, adbPath: String = "") =
        DevicePackageBrowser(runner = runner, endpointReader = { AdbEndpoint(adbPath) })

    // ── 包列表解析矩阵（纯函数直测）──

    @Test
    fun `parsePackageList parses three-segment lines`() {
        val parsed = DevicePackageParsers.parsePackageList(allListFixture)
        assertEquals(3, parsed.size)
        assertEquals("com.demo.media", parsed[0].first)
        assertEquals(60400000L, parsed[0].second)
        assertEquals(10061L, parsed[0].third)
    }

    @Test
    fun `parsePackageList strips pty carriage returns and keeps output order`() {
        val output = "package:com.example versionCode:5 uid:1000\r\npackage:com.other versionCode:6 uid:1001\r\n"
        assertEquals(
            listOf("com.example", "com.other"),
            DevicePackageParsers.parsePackageList(output).map { it.first },
        )
    }

    @Test
    fun `parsePackageList returns empty list for empty output`() {
        assertEquals(emptyList(), DevicePackageParsers.parsePackageList(""))
        assertEquals(emptyList(), DevicePackageParsers.parsePackageList("\n  \n"))
    }

    @Test
    fun `parsePackageList fails on unrecognized line`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parsePackageList("totally unknown output shape")
        }
        assertTrue(e.message!!.contains("totally unknown"), "坏行显式失败带原行样例（实际：${e.message}）")
    }

    @Test
    fun `parsePackageList fails on swapped segment order`() {
        // 段序是实测定形态，段序互换不猜段位
        assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parsePackageList("package:com.example uid:1000 versionCode:5")
        }
    }

    @Test
    fun `parsePackageList fails on missing or nonnumeric segment`() {
        assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parsePackageList("package:com.example versionCode:5")
        }
        assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parsePackageList("package:com.example versionCode:x uid:1000")
        }
    }

    @Test
    fun `parsePackageList fails on trailing extra segment`() {
        assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parsePackageList("package:com.example versionCode:5 uid:1000 extra:1")
        }
    }

    // ── 详情解析矩阵（真机实测 fixture 基线）──

    @Test
    fun `parsePackageDetails extracts minimal field set from real-device fixture`() {
        val details = DevicePackageParsers.parsePackageDetails(dumpsysFixture, "com.demo.media")

        assertEquals("com.demo.media", details.packageName)
        assertEquals(60400000L, details.versionCode)
        assertEquals("V9.9.9_20990101000000000_Release", details.versionName)
        assertEquals(10061L, details.uid)
        assertEquals("/system/app/DemoDvrApp", details.codePath)
        assertEquals("2025-01-01 08:00:00", details.firstInstallTime)
        assertEquals("2025-01-01 08:00:00", details.lastUpdateTime)
        assertEquals(4, details.requestedPermissionsCount, "requested permissions 子段计数，install permissions 与 User 子段不计数")
    }

    @Test
    fun `parsePackageDetails maps versionName null literal to null and keeps spaces in value`() {
        val output = dumpsysFixture
            .replace("V9.9.9_20990101000000000_Release", "null")
            .replace("\n", "\r\n")
        assertNull(DevicePackageParsers.parsePackageDetails(output, "com.demo.media").versionName, "versionName=null 字面量 → null；同时覆盖 pty \\r 剥除")

        val spaced = dumpsysFixture.replace("V9.9.9_20990101000000000_Release", "V6.4 beta build")
        assertEquals("V6.4 beta build", DevicePackageParsers.parsePackageDetails(spaced, "com.demo.media").versionName, "versionName 含空格原样保留（值取等号后整行）")
    }

    @Test
    fun `parsePackageDetails counts zero when requested permissions section absent`() {
        val removedPermissions = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.READ_EXTERNAL_STORAGE",
            "com.demo.permission.CABIN_CAPTURE",
        )
        val output = dumpsysFixture.lines()
            .filter { it.trim() != "requested permissions:" && it.trim() !in removedPermissions }
            .joinToString("\n")
        assertEquals(0, DevicePackageParsers.parsePackageDetails(output, "com.demo.media").requestedPermissionsCount)
    }

    @Test
    fun `parsePackageDetails fails when package record header is absent`() {
        // 未安装包：dumpsys 退出 0 只打印无关段——记录头缺席显式失败不静默
        val output = """
            Domain verification status:
            Failure printing domain verification information

            Package Changes:
              Sequence number=0
        """.trimIndent()
        val e = assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parsePackageDetails(output, "com.missing.pkg")
        }
        assertTrue(e.message!!.contains("未找到包记录"), "缺席包显式失败（实际：${e.message}）")
    }

    @Test
    fun `parsePackageDetails fails when record name mismatches requested package`() {
        val e = assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parsePackageDetails(dumpsysFixture, "com.other.pkg")
        }
        assertTrue(e.message!!.contains("不符"), "记录名与请求包名不符显式失败（实际：${e.message}）")
    }

    @Test
    fun `parsePackageDetails fails listing missing required fields`() {
        val output = dumpsysFixture
            .replace("    versionCode=60400000 minSdk=28 targetSdk=30\n", "")
            .replace("    firstInstallTime=2025-01-01 08:00:00\n", "")
        val e = assertFailsWith<IllegalArgumentException> {
            DevicePackageParsers.parsePackageDetails(output, "com.demo.media")
        }
        assertTrue(e.message!!.contains("versionCode"), "缺席字段逐名列出（实际：${e.message}）")
        assertTrue(e.message!!.contains("firstInstallTime"), "缺席字段逐名列出（实际：${e.message}）")
    }

    // ── 浏览器命令构造与执行调度 ──

    @Test
    fun `listPackages builds byte-exact rewritten commands for both listings`() = runTest {
        val runner = FakeGateway { command ->
            if (command.contains("packages -s ")) {
                ShellResult(0, systemListFixture, "")
            } else {
                ShellResult(0, allListFixture, "")
            }
        }
        val result = browser(runner).listPackages("S1")

        assertEquals(
            listOf(
                "adb -s S1 shell 'pm list packages --show-versioncode -U'",
                "adb -s S1 shell 'pm list packages -s --show-versioncode -U'",
            ),
            runner.commands,
            "全部包与系统包两次列举命令逐字节成立：payload 本机 shell 单引号包裹 + -s serial 注入",
        )
        val packages = result.getOrThrow()
        assertEquals(
            listOf("com.demo.media", "com.example.userapp", "com.android.cts.priv.ctsshim"),
            packages.map { it.packageName },
            "返回保持 pm list 输出序",
        )
        assertEquals(listOf(true, false, true), packages.map { it.isSystem }, "系统标记 = -s 集合成员差")
        assertEquals(60400000L, packages[0].versionCode)
        assertEquals(10061L, packages[0].uid)
    }

    @Test
    fun `listPackages marks all system when system set equals full set`() = runTest {
        // 全预装形态：-3 为 0 条，-s 集合 = 全集——全部标系统不炸
        val runner = FakeGateway { ShellResult(0, allListFixture, "") }
        val packages = browser(runner).listPackages("S1").getOrThrow()
        assertEquals(3, packages.size)
        assertTrue(packages.all { it.isSystem }, "全预装形态 -s 集合 = 全集，isSystem 全真")
    }

    @Test
    fun `listPackages rewrites custom adb path`() = runTest {
        val runner = FakeGateway { ShellResult(0, allListFixture, "") }
        browser(runner, adbPath = "/opt/my adb").listPackages("S1")

        assertEquals(
            listOf(
                "'/opt/my adb' -s S1 shell 'pm list packages --show-versioncode -U'",
                "'/opt/my adb' -s S1 shell 'pm list packages -s --show-versioncode -U'",
            ),
            runner.commands,
            "含空格 adb 路径按执行 shell 语义单引号包裹",
        )
    }

    @Test
    fun `listPackages passes through pm error text on nonzero exit`() = runTest {
        val runner = FakeGateway { ShellResult(255, "Error: Unknown option: --bogus", "") }
        val result = browser(runner).listPackages("S1")

        assertEquals(1, runner.commands.size, "首次列举失败不重试不降级")
        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("退出码 255"), "失败回执带退出码（实际：${e.message}）")
        assertTrue(e.message!!.contains("Error: Unknown option: --bogus"), "pm 错误原文透传（实际：${e.message}）")
    }

    @Test
    fun `listPackages passes through device stderr when system listing fails`() = runTest {
        val runner = FakeGateway { command ->
            if (command.contains("packages -s ")) {
                ShellResult(1, "", "Error: failed to get system packages")
            } else {
                ShellResult(0, allListFixture, "")
            }
        }
        val result = browser(runner).listPackages("S1")

        assertEquals(2, runner.commands.size)
        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("系统包列举失败"), "两次列举各自独立回执可区分（实际：${e.message}）")
        assertTrue(e.message!!.contains("Error: failed to get system packages"), "设备侧 stderr 原文透传（实际：${e.message}）")
    }

    @Test
    fun `listPackages fails explicitly on unrecognized listing output`() = runTest {
        val runner = FakeGateway { ShellResult(0, "totally unknown output shape", "") }
        val result = browser(runner).listPackages("S1")

        assertEquals(1, runner.commands.size, "格式不符不发起第二次列举")
        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("包列举"), "解析失败回执带动作名（实际：${e.message}）")
        assertTrue(e.message!!.contains("格式不符"), "格式不符显式失败不静默（实际：${e.message}）")
    }

    @Test
    fun `listPackages rejects empty serial without spawn`() = runTest {
        val runner = FakeGateway { ShellResult(0, allListFixture, "") }
        val result = browser(runner).listPackages("")

        assertTrue(result.isFailure && result.exceptionOrNull()!!.message!!.contains("为空"), "空串号零 spawn 人话拒绝")
        assertEquals(emptyList(), runner.commands, "拒绝路径零 exec")
    }

    @Test
    fun `listPackages lands adb spawn failure as human-readable error`() = runTest {
        val boom = AdbProcessRunner { Result.failure(IllegalStateException("adb not found")) }
        val result = browser(boom).listPackages("S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("adb 执行失败"), "进程启动真失败落人话回执（实际：${e.message}）")
        assertTrue(e.message!!.contains("adb not found"))
    }

    @Test
    fun `packageDetails builds byte-exact command with device-side quoted package name`() = runTest {
        val runner = FakeGateway { ShellResult(0, "", "") }
        browser(runner).packageDetails("com.demo.media", "S1")

        assertEquals(
            listOf("""adb -s S1 shell 'dumpsys package '\''com.demo.media'\'''"""),
            runner.commands,
            "dumpsys 命令逐字节成立：包名设备侧单引号零展开，payload 整体经本机 shell 单引号转义包裹",
        )
    }

    @Test
    fun `packageDetails parses full dumpsys output and returns minimal fields`() = runTest {
        val runner = FakeGateway { ShellResult(0, dumpsysFixture, "") }
        val details = browser(runner).packageDetails("com.demo.media", "S1").getOrThrow()

        assertEquals("com.demo.media", details.packageName)
        assertEquals(60400000L, details.versionCode)
        assertEquals("V9.9.9_20990101000000000_Release", details.versionName)
        assertEquals(10061L, details.uid)
        assertEquals("/system/app/DemoDvrApp", details.codePath)
        assertEquals("2025-01-01 08:00:00", details.firstInstallTime)
        assertEquals(4, details.requestedPermissionsCount)
    }

    @Test
    fun `packageDetails fails with human-readable receipt for absent package`() = runTest {
        // 未安装包：dumpsys 退出 0 只打印无关段零包记录——失败走解析路径
        val runner = FakeGateway { ShellResult(0, "Domain verification status:\nFailure printing domain verification information\n", "") }
        val result = browser(runner).packageDetails("com.missing.pkg", "S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("未找到包记录"), "缺席包人话失败回执（实际：${e.message}）")
        assertTrue(e.message!!.contains("com.missing.pkg"), "回执带请求包名（实际：${e.message}）")
    }

    @Test
    fun `packageDetails passes through nonzero exit error text`() = runTest {
        val runner = FakeGateway { ShellResult(1, "", "dumpsys: Error: package not found") }
        val result = browser(runner).packageDetails("com.missing.pkg", "S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("包详情读取失败"), "非零退出失败回执（实际：${e.message}）")
        assertTrue(e.message!!.contains("dumpsys: Error: package not found"), "设备侧 stderr 原文透传（实际：${e.message}）")
    }

    @Test
    fun `packageDetails rejects empty package name or serial without spawn`() = runTest {
        val runner = FakeGateway { ShellResult(0, dumpsysFixture, "") }
        val b = browser(runner)

        assertTrue(b.packageDetails("", "S1").isFailure, "空包名零 spawn 拒绝")
        assertTrue(b.packageDetails("com.example", "").isFailure, "空串号零 spawn 拒绝")
        assertEquals(emptyList(), runner.commands)
    }
}
