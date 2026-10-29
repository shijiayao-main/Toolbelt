package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.toolbelt.device.AdbInstallGateway
import com.jiaoay.toolbelt.device.AdbInstallHandle
import com.jiaoay.toolbelt.device.AdbPushGateway
import com.jiaoay.toolbelt.device.AdbPushHandle
import com.jiaoay.toolbelt.device.DeviceApkInstaller
import com.jiaoay.toolbelt.device.DeviceFilePusher
import com.jiaoay.toolbelt.device.DeviceFileStat
import com.jiaoay.toolbelt.device.DeviceFileType
import com.jiaoay.toolbelt.device.DevicePackageBrowser
import com.jiaoay.toolbelt.device.ShellOutput
import com.jiaoay.toolbelt.device.ShellOutputSource
import com.jiaoay.toolbelt.device.ShellResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import com.jiaoay.toolbelt.adb.AdbEndpoint

/**
 * 拖拽推送/安装路由守护（fake gateway + fake stat + fake 包列举注入真
 * [DeviceFilePusher]/[DeviceApkInstaller]——内核全真、adb 子进程零真零真安装，
 * 路由契约即内核管线契约；真机安装执行按门禁归维护者）：
 * - 路由链：普通文件 → pusher 既有覆盖确认管线 → 缺省目标 `/sdcard/`（文件子页
 *   起点根同值锚）+ 命令全文逐字节断言；
 * - `.apk` 安装路由：恒过安装确认对话（确认前零 adb install spawn——安装不可逆
 *   静默零容忍）；确认 = `install -r` 命令 + 追溯回执（新装形态包名 + 版本名 /
 *   升级覆盖形态差集空如实注明）；取消 = 零动作零写、该 APK 落拒收回执后继续
 *   后续文件；
 * - 覆盖确认两态：预检已存在 → 确认数据在场 + 零 spawn；确认 = overwrite=true 重推
 *   （跳过预检）；取消 = 零动作零写、该文件落拒收回执后继续后续文件；
 * - 多文件混合批：`.apk` 与普通文件各走各管线各回执；
 * - 批间互斥 + 串号缺席/空拖入整批拒绝零 spawn；
 * - drop 面源码锚：drop 目标只在独立窗根层（feature 模块内唯一 + 主窗面板零
 *   drop 面），安装确认与覆盖确认两道对话（isDangerous）在窗内收口。
 *
 * 🔴 单测不碰用户数据根：本地源文件一律落在临时目录。
 */
class ScreencastWindowDropPushTest {

    private fun tempDir(): File = Files.createTempDirectory("drop-push").toFile()

    private fun localFile(dir: File, name: String, size: Int = 512): File =
        File(dir, name).apply { writeBytes(ByteArray(size)) }

    /** 推送成功脚本（吐摘要行 + 退出码 0） */
    private fun successScript(): suspend (String, suspend (ShellOutput) -> Unit) -> Int =
        { _, onLine ->
            onLine(ShellOutput("1 file pushed, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }

    private fun router(
        scope: CoroutineScope,
        gateway: FakePushGateway,
        stat: ScriptedStat,
        installer: DeviceApkInstaller = defaultFakeInstaller(),
    ): ScreencastDropPushRouter = ScreencastDropPushRouter(
        scope = scope,
        runner = NoopRunner,
        endpointReader = { AdbEndpoint("") },
        pusher = DeviceFilePusher(
            runner = NoopRunner,
            gateway = gateway,
            statEntry = stat.statEntry,
            endpointReader = { AdbEndpoint("") },
        ),
        installer = installer,
    )

    /**
     * 纯推送用例的缺省安装内核（零真 adb）：安装 gateway 抛错——推送用例不该触达
     * 安装面，触达即测试失败显式暴露。
     */
    private fun defaultFakeInstaller(): DeviceApkInstaller = DeviceApkInstaller(
        runner = NoopRunner,
        gateway = AdbInstallGateway { command, _, _ -> error("推送用例不得触达安装面：$command") },
        packageBrowser = DevicePackageBrowser(
            runner = { error("推送用例不得触达包列举面") },
            endpointReader = { AdbEndpoint("") },
        ),
        endpointReader = { AdbEndpoint("") },
    )

    /** 安装成功脚本（引导行 + Success 标记行 + 退出码 0） */
    private fun installSuccessScript(): suspend (String, suspend (ShellOutput) -> Unit) -> Int =
        { _, onLine ->
            onLine(ShellOutput("Performing Streamed Install", ShellOutputSource.STDOUT))
            onLine(ShellOutput("Success", ShellOutputSource.STDOUT))
            0
        }

    /** 升档差集的包列举脚本（前/后列举同集 = 升级覆盖形态；详情缺席） */
    private fun upgradeOverwritePkgGateway(): FakePkgGateway = FakePkgGateway(
        allLists = listOf(pkgListOf("com.a"), pkgListOf("com.a")),
        systemLists = listOf(pkgListOf("com.a"), pkgListOf("com.a")),
    )

    /** 新装形态的包列举脚本（安装后多出 com.b → 差集取包名，详情带 versionName） */
    private fun newPackagePkgGateway(): FakePkgGateway = FakePkgGateway(
        allLists = listOf(pkgListOf("com.a"), pkgListOf("com.a", "com.b")),
        systemLists = listOf(pkgListOf("com.a"), pkgListOf("com.a")),
        detailResults = listOf(
            Result.success(
                ShellResult(0, detailsFixtureOf("com.b", "1.2.3"), ""),
            ),
        ),
    )

    // ── 路由链：drop → DeviceFilePusher 既有管线 → 缺省目标 ─────────────────────

    @Test
    fun `regular file drop routes through pusher pipeline to default device dir`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "build.log")
        val gateway = FakePushGateway(successScript())
        val stat = ScriptedStat(listOf(dropPushAbsentStat(), dropPushStatOf(512)))
        val dropPush = router(this, gateway, stat)

        assertNull(dropPush.offer(listOf(local), "S1"), "Streaming 会话内普通文件须受理")
        advanceUntilIdle()

        assertEquals(
            listOf("adb -s S1 push '${local.absolutePath}' '/sdcard/build.log'"),
            gateway.commands,
            "路由恒走 DeviceFilePusher 管线（预检 + 推送 + 后置校验），目标 = 缺省设备目录",
        )
        assertEquals(
            listOf(ScreencastDropPushLine.Pushed("build.log", "/sdcard/build.log", 512)),
            dropPush.lines.value,
            "完成回执携带设备目标全路径与校验通过的大小",
        )
        assertNull(dropPush.confirm.value, "无覆盖裁决在场")
        assertEquals(2, stat.calls.size, "预检 stat（不存在）+ 推送后校验 stat 各一次")
    }

    @Test
    fun `default device dir matches file subpage browse root`() {
        // 值锚：与设备观测「文件」子页浏览起点根（/sdcard/）逐字节一致——拖拽面不造第二默认
        assertEquals("/sdcard/", SCREENCAST_DROP_PUSH_DEVICE_DIR)
        assertTrue(isApkDrop("x.APK"), "大写后缀须路由安装管线")
        assertTrue(isApkDrop("x.apk"), "小写后缀须路由安装管线")
        assertTrue(isApkDrop("X.Apk"), "混合大小写后缀须路由安装管线")
        assertEquals(false, isApkDrop("x.apk.bak"), "后缀须是名尾（.bak 结尾非 apk）")
        assertEquals(false, isApkDrop("apk.txt"), "名含 apk 词但非 .apk 后缀不走安装管线")
    }

    // ── .apk 安装路由（恒过确认对话——确认前零 adb install spawn） ───────────────

    @Test
    fun `apk drop surfaces install confirm with file name and size before any spawn`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "app.APK", size = 1024)
        val pushGateway = FakePushGateway(successScript())
        val installGateway = FakeInstallGateway(installSuccessScript())
        val dropPush = ScreencastDropPushRouter(
            scope = this,
            runner = NoopRunner,
            endpointReader = { AdbEndpoint("") },
            pusher = DeviceFilePusher(
                runner = NoopRunner,
                gateway = pushGateway,
                statEntry = ScriptedStat(emptyList()).statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
            installer = DeviceApkInstaller(
                runner = NoopRunner,
                gateway = installGateway,
                packageBrowser = DevicePackageBrowser(
                    runner = newPackagePkgGateway(),
                    endpointReader = { AdbEndpoint("") },
                ),
                endpointReader = { AdbEndpoint("") },
            ),
        )

        assertNull(dropPush.offer(listOf(local), "S1"), "拖入受理（安装走确认分层）")
        advanceUntilIdle()

        assertEquals(
            ScreencastApkInstallConfirm("app.APK", 1024),
            dropPush.installConfirm.value,
            "安装确认在场数据携带本地文件名 + 大小（确认对话文案的追溯基线）",
        )
        assertEquals(listOf(ScreencastDropPushLine.Installing("app.APK")), dropPush.lines.value, "裁决挂起期在途行占位")
        assertTrue(installGateway.commands.isEmpty(), "确认前零 adb install spawn——安装不可逆，静默安装零容忍")
        assertTrue(pushGateway.commands.isEmpty(), ".apk 不走推送管线（不推到 /sdcard 冒充已处理）")
        assertTrue(
            dropPush.offer(listOf(local), "S1") != null,
            "裁决挂起期间批 in-flight——新拖入被拒（内核单飞行）",
        )

        dropPush.dismissInstall()
        advanceUntilIdle()

        assertNull(dropPush.installConfirm.value, "取消即清确认面")
        val line = dropPush.lines.value.single()
        assertIs<ScreencastDropPushLine.Rejected>(line, "取消分支落拒收回执")
        assertTrue(line.message.contains("已取消安装"), "回执明示设备侧未改动（实际：${line.message}）")
        assertTrue(installGateway.commands.isEmpty(), "取消分支零动作零写（零安装零包列举命令）")
    }

    @Test
    fun `confirmed apk install runs install dash r with new package identity in receipt`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "app.apk")
        val pushGateway = FakePushGateway(successScript())
        val installGateway = FakeInstallGateway(installSuccessScript())
        val pkgGateway = newPackagePkgGateway()
        val dropPush = ScreencastDropPushRouter(
            scope = this,
            runner = NoopRunner,
            endpointReader = { AdbEndpoint("") },
            pusher = DeviceFilePusher(
                runner = NoopRunner,
                gateway = pushGateway,
                statEntry = ScriptedStat(emptyList()).statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
            installer = DeviceApkInstaller(
                runner = NoopRunner,
                gateway = installGateway,
                packageBrowser = DevicePackageBrowser(runner = pkgGateway, endpointReader = { AdbEndpoint("") }),
                endpointReader = { AdbEndpoint("") },
            ),
        )

        dropPush.offer(listOf(local), "S1")
        advanceUntilIdle()
        assertEquals(ScreencastApkInstallConfirm("app.apk", 512), dropPush.installConfirm.value)

        dropPush.confirmInstall()
        advanceUntilIdle()

        assertEquals(
            listOf("adb -s S1 install -r '${local.absolutePath}'"),
            installGateway.commands,
            "确认后真正安装一次（-r 旗标 + 串号注入 + 本地路径字面化）",
        )
        assertEquals(
            listOf(
                ScreencastDropPushLine.Installed(
                    fileName = "app.apk",
                    sizeBytes = 512,
                    adbEcho = "Performing Streamed Install\nSuccess",
                    packageName = "com.b",
                    versionName = "1.2.3",
                    identityNote = null,
                ),
            ),
            dropPush.lines.value,
            "安装回执行携带追溯基线（文件名 + 大小 + adb 回显原文）与升档（差集新装包名 + dumpsys versionName），在途行原位翻面",
        )
        assertTrue(pushGateway.commands.isEmpty(), ".apk 不走推送管线")
        assertNull(dropPush.installConfirm.value, "确认后即清确认面（一个文件最多过一道安装确认）")
    }

    @Test
    fun `upgrade overwrite install falls back to file name identity with honest note`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "app.apk")
        val installGateway = FakeInstallGateway(installSuccessScript())
        val dropPush = ScreencastDropPushRouter(
            scope = this,
            runner = NoopRunner,
            endpointReader = { AdbEndpoint("") },
            pusher = DeviceFilePusher(
                runner = NoopRunner,
                gateway = FakePushGateway(successScript()),
                statEntry = ScriptedStat(emptyList()).statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
            installer = DeviceApkInstaller(
                runner = NoopRunner,
                gateway = installGateway,
                packageBrowser = DevicePackageBrowser(runner = upgradeOverwritePkgGateway(), endpointReader = { AdbEndpoint("") }),
                endpointReader = { AdbEndpoint("") },
            ),
        )

        dropPush.offer(listOf(local), "S1")
        advanceUntilIdle()
        dropPush.confirmInstall()
        advanceUntilIdle()

        assertEquals(
            listOf(
                ScreencastDropPushLine.Installed(
                    fileName = "app.apk",
                    sizeBytes = 512,
                    adbEcho = "Performing Streamed Install\nSuccess",
                    packageName = null,
                    versionName = null,
                    identityNote = "升级覆盖，包名未识别",
                ),
            ),
            dropPush.lines.value,
            "升级覆盖形态（-r 装已有包差集空）如实回落文件名档，禁止猜包名",
        )
    }

    @Test
    fun `failed install surfaces adb echo verbatim in rejected line`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "app.apk")
        val installGateway = FakeInstallGateway { _, onLine ->
            onLine(ShellOutput("Performing Streamed Install", ShellOutputSource.STDOUT))
            onLine(ShellOutput("Failure [INSTALL_FAILED_INVALID_APK]", ShellOutputSource.STDOUT))
            1
        }
        val dropPush = ScreencastDropPushRouter(
            scope = this,
            runner = NoopRunner,
            endpointReader = { AdbEndpoint("") },
            pusher = DeviceFilePusher(
                runner = NoopRunner,
                gateway = FakePushGateway(successScript()),
                statEntry = ScriptedStat(emptyList()).statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
            installer = DeviceApkInstaller(
                runner = NoopRunner,
                gateway = installGateway,
                packageBrowser = DevicePackageBrowser(runner = upgradeOverwritePkgGateway(), endpointReader = { AdbEndpoint("") }),
                endpointReader = { AdbEndpoint("") },
            ),
        )

        dropPush.offer(listOf(local), "S1")
        advanceUntilIdle()
        dropPush.confirmInstall()
        advanceUntilIdle()

        val line = dropPush.lines.value.single()
        assertIs<ScreencastDropPushLine.Rejected>(line, "安装失败落拒收回执")
        assertTrue(line.message.contains("Failure [INSTALL_FAILED_INVALID_APK]"), "失败原因原文透传（实际：${line.message}）")
        assertTrue(line.message.contains("安装失败"), "失败人话（实际：${line.message}）")
    }

    // ── 多文件混合批（.apk 与普通文件各走各管线各回执） ──────────────────────────

    @Test
    fun `mixed batch routes apk to install and regular file to push`() = runTest {
        val dir = tempDir()
        val apk = localFile(dir, "app.apk")
        val regular = localFile(dir, "note.txt")
        val pushGateway = FakePushGateway(successScript())
        val stat = ScriptedStat(listOf(dropPushAbsentStat(), dropPushStatOf(512)))
        val installGateway = FakeInstallGateway(installSuccessScript())
        val dropPush = ScreencastDropPushRouter(
            scope = this,
            runner = NoopRunner,
            endpointReader = { AdbEndpoint("") },
            pusher = DeviceFilePusher(
                runner = NoopRunner,
                gateway = pushGateway,
                statEntry = stat.statEntry,
                endpointReader = { AdbEndpoint("") },
            ),
            installer = DeviceApkInstaller(
                runner = NoopRunner,
                gateway = installGateway,
                packageBrowser = DevicePackageBrowser(runner = upgradeOverwritePkgGateway(), endpointReader = { AdbEndpoint("") }),
                endpointReader = { AdbEndpoint("") },
            ),
        )

        dropPush.offer(listOf(apk, regular), "S1")
        advanceUntilIdle()

        // 首件（.apk）挂起在安装确认，次件（普通文件）尚未处理——逐文件串行
        assertEquals(ScreencastApkInstallConfirm("app.apk", 512), dropPush.installConfirm.value)
        assertTrue(pushGateway.commands.isEmpty(), "apk 裁决挂起期普通文件未被处理（逐文件串行）")

        dropPush.confirmInstall()
        advanceUntilIdle()

        assertEquals(
            listOf("adb -s S1 install -r '${apk.absolutePath}'"),
            installGateway.commands,
            ".apk 走安装管线",
        )
        assertEquals(
            listOf("adb -s S1 push '${regular.absolutePath}' '/sdcard/note.txt'"),
            pushGateway.commands,
            "普通文件走既有推送管线（互不串道）",
        )
        val lines = dropPush.lines.value
        assertEquals(2, lines.size, "两类文件各落各回执行")
        assertIs<ScreencastDropPushLine.Installed>(lines[0], ".apk 安装回执行")
        assertEquals(
            ScreencastDropPushLine.Pushed("note.txt", "/sdcard/note.txt", 512),
            lines[1],
            "普通文件推送回执行",
        )
    }

    // ── 覆盖确认两态（确认 → overwrite=true 重推 / 取消 → 零动作零写） ────────────

    @Test
    fun `existing target surfaces confirm data with zero spawn and cancel keeps device untouched`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "build.log")
        val gateway = FakePushGateway(successScript())
        val stat = ScriptedStat(listOf(dropPushStatOf(1024)))
        val dropPush = router(this, gateway, stat)

        dropPush.offer(listOf(local), "S1")
        advanceUntilIdle()

        assertEquals(
            ScreencastDropPushConfirm("build.log", "/sdcard/build.log"),
            dropPush.confirm.value,
            "预检已存在 → 确认数据在场（类型化拒绝 → 用户裁决面）",
        )
        assertTrue(gateway.commands.isEmpty(), "确认前零 adb push spawn——不静默覆盖")
        assertEquals(1, stat.calls.size, "只有预检 stat 一次")
        assertTrue(
            dropPush.offer(listOf(local), "S1") != null,
            "确认挂起期间批 in-flight——新拖入被拒（推送内核单飞行）",
        )

        dropPush.dismissConfirm()
        advanceUntilIdle()

        assertNull(dropPush.confirm.value, "取消即清确认面")
        val line = dropPush.lines.value.single()
        assertIs<ScreencastDropPushLine.Rejected>(line, "取消分支落拒收回执")
        assertTrue(line.message.contains("已取消覆盖"), "回执明示设备侧未改动（实际：${line.message}）")
        assertTrue(gateway.commands.isEmpty(), "取消分支零动作零写")
        assertEquals(1, stat.calls.size, "取消后无任何新增设备侧探测")
    }

    @Test
    fun `confirm overwrite repushes with overwrite semantics skipping precheck`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "build.log")
        val gateway = FakePushGateway(successScript())
        val stat = ScriptedStat(listOf(dropPushStatOf(1024), dropPushStatOf(512)))
        val dropPush = router(this, gateway, stat)

        dropPush.offer(listOf(local), "S1")
        advanceUntilIdle()
        assertEquals(ScreencastDropPushConfirm("build.log", "/sdcard/build.log"), dropPush.confirm.value)

        dropPush.confirmOverwrite()
        advanceUntilIdle()

        assertEquals(
            listOf(ScreencastDropPushLine.Pushed("build.log", "/sdcard/build.log", 512)),
            dropPush.lines.value,
            "确认分支 = overwrite=true 重推同文件，在途行原位翻面为完成",
        )
        assertEquals(1, gateway.commands.size, "确认后真正推送一次")
        assertEquals(2, stat.calls.size, "预检 1 次 + 推送后校验 1 次（确认重推跳过预检）")
        assertNull(dropPush.confirm.value)
    }

    // ── 多文件逐件处理（失败不中断后续 + 在途行原位翻面） ───────────────────────

    @Test
    fun `multi file batch processes each file and failure keeps the rest going`() = runTest {
        val dir = tempDir()
        val first = localFile(dir, "a.log")
        val second = localFile(dir, "b.log")
        var invocation = 0
        val gateway = FakePushGateway { _, onLine ->
            invocation++
            if (invocation == 1) {
                onLine(
                    ShellOutput(
                        "adb: error: failed to copy '${first.absolutePath}' to '/sdcard/a.log': Read-only file system",
                        ShellOutputSource.STDERR,
                    ),
                )
                1
            } else {
                onLine(ShellOutput("1 file pushed, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)", ShellOutputSource.STDOUT))
                0
            }
        }
        val stat = ScriptedStat(listOf(dropPushAbsentStat(), dropPushAbsentStat(), dropPushStatOf(512)))
        val dropPush = router(this, gateway, stat)

        dropPush.offer(listOf(first, second), "S1")
        advanceUntilIdle()

        assertEquals(2, gateway.commands.size, "两个文件各推送一次")
        val lines = dropPush.lines.value
        assertEquals(2, lines.size, "逐文件回执均在场（失败不吞掉后续文件的回执）")
        val firstLine = assertIs<ScreencastDropPushLine.Rejected>(lines[0], "首件失败回执")
        assertTrue(firstLine.message.contains("adb 退出码 1"), "失败人话原文透传（实际：${firstLine.message}）")
        assertEquals(
            ScreencastDropPushLine.Pushed("b.log", "/sdcard/b.log", 512),
            lines[1],
            "首件失败不中断后续文件（第二件照常完成）",
        )
    }

    @Test
    fun `pushing line holds while transfer in flight then flips in place`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "build.log")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway = FakePushGateway { _, onLine ->
            gate.await()
            onLine(ShellOutput("1 file pushed, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }
        val stat = ScriptedStat(listOf(dropPushAbsentStat(), dropPushStatOf(512)))
        val dropPush = router(this, gateway, stat)

        dropPush.offer(listOf(local), "S1")
        advanceUntilIdle()

        assertEquals(
            listOf(ScreencastDropPushLine.Pushing("build.log")),
            dropPush.lines.value,
            "传输在途 = 在途行占位（等 gate，批未收尾）",
        )
        assertTrue(dropPush.offer(listOf(local), "S1") != null, "在途批未收尾前新拖入被拒")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(ScreencastDropPushLine.Pushed("build.log", "/sdcard/build.log", 512)),
            dropPush.lines.value,
            "完成后在途行原位翻面为完成行（不追加重复行）",
        )
    }

    // ── 批拒绝面（空拖入/串号缺席——零动作零 spawn） ──────────────────────────────

    @Test
    fun `offer rejects empty drop and absent serial without any device call`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, "build.log")
        val gateway = FakePushGateway(successScript())
        val stat = ScriptedStat(emptyList())
        val dropPush = router(this, gateway, stat)

        val emptyDrop = dropPush.offer(emptyList(), "S1")
        assertTrue(emptyDrop!!.contains("不含本地普通文件"), "空拖入人话拒收（实际：$emptyDrop）")
        val noSerial = dropPush.offer(listOf(local), null)
        assertTrue(noSerial!!.contains("串号"), "串号缺席人话拒收（实际：$noSerial）")
        val blankSerial = dropPush.offer(listOf(local), "")
        assertTrue(blankSerial != null, "空串号同拒绝面")
        assertTrue(gateway.commands.isEmpty(), "整批拒绝零 spawn")
        assertTrue(stat.calls.isEmpty(), "整批拒绝零 stat")
        assertTrue(dropPush.lines.value.isEmpty(), "拒绝不产生回执行")
    }

    // ── drop 面源码锚（drop 目标在位性——事件注入底座不可用，锚兜底） ─────────────

    @Test
    fun `drop face anchors at detached window root only`() {
        val windowSource = sourceAt(
            "feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/ScreencastDetachedWindow.kt",
        )
        assertTrue("dragAndDropTarget(" in windowSource, "独立窗根层须挂 drop 目标（拖拽投屏窗=push 的入口面）")
        assertTrue(
            "shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList }" in windowSource,
            "仅文件列表拖放起拖（先例配方：设置页图片拖入）",
        )
        assertTrue(
            "dropPush.offer(files, serial)" in windowSource,
            "drop 消费须经 ScreencastDropPushRouter 路由（不旁路推送管线）",
        )
        assertTrue(
            "AppConfirmDialog(" in windowSource && "isDangerous = true" in windowSource,
            "覆盖确认对话须挂 App 级 AppConfirmDialog 危险主钮形态",
        )
        assertTrue(
            "安装 APK 到设备？" in windowSource && "dropPush::confirmInstall" in windowSource,
            "安装确认对话须在窗内收口（危险主钮形态，文案携带文件名 + 大小）",
        )

        // 模块内 drop 面唯一性：feature-screencast 生产源内出现 dragAndDropTarget 的文件
        // 只允许独立窗一件（主窗零 drop 改动的结构背书——主窗面板是纯启动器）
        val projectRoot = locateProjectRoot()
        val moduleSrc = File(projectRoot, "feature-screencast/src/jvmMain/kotlin")
        val dropFaces = moduleSrc.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("dragAndDropTarget(") }
            .map { it.relativeTo(moduleSrc).path }
            .toList()
        assertEquals(
            listOf("com/jiaoay/easy_dev_toolbox/page/screencast/ScreencastDetachedWindow.kt"),
            dropFaces,
            "drop 面限定独立窗根层（主窗启动器行 ScreencastPanel 零 drop 改动——多一张 drop 面即抢文件面）",
        )

        // .apk 安装路由与取消跳过语义锚（路由在路由层收口，窗面不另判后缀）
        val routerSource = sourceAt(
            "feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/ScreencastWindowDropPush.kt",
        )
        assertTrue("if (isApkDrop(file.name))" in routerSource, "路由层 .apk 分流闸在位")
        assertTrue(
            "installOne(file, serial)" in routerSource,
            ".apk 命中即走安装执行体（安装确认在执行体内恒先于 install spawn）",
        )
        assertTrue("overwrite = false" in routerSource, "首推恒 overwrite=false（不静默覆盖，预检拒绝归确认层）")
    }

    private fun locateProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
            hops++
        }
        error("项目根定位失败（settings.gradle.kts 未在 8 跳内命中）")
    }

    private fun sourceAt(relativePath: String): String {
        val file = File(locateProjectRoot(), relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}

/**
 * 拖拽推送测试面的共享 fakes（跨测试类复用——路由层单测与窗内容渲染单测同一套
 * fake gateway/stat，零真 adb 子进程）。pusher 全真（fake 只落在 adb 执行与 stat
 * 探测两缝上）——路由契约即 DeviceFilePusher 管线契约。
 */

/** gateway fake：记录命令全文（逐字节断言锚），按脚本回执（脚本可挂起——在途行用例） */
internal class FakePushGateway(
    private val script: suspend (command: String, onLine: suspend (ShellOutput) -> Unit) -> Int,
) : AdbPushGateway {
    val commands = mutableListOf<String>()

    override suspend fun execute(
        command: String,
        onStarted: (AdbPushHandle) -> Unit,
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<Int> {
        commands += command
        onStarted(object : AdbPushHandle {
            override fun kill() = Unit
        })
        return Result.success(script(command, onLine))
    }
}

/** fake stat：按调用序回放脚本结果（预检/后置校验两用） */
internal class ScriptedStat(results: List<Result<DeviceFileStat>>) {
    val calls = mutableListOf<String>()
    val statEntry: suspend (String, String) -> Result<DeviceFileStat> = { path, _ ->
        calls += path
        results[calls.size - 1]
    }
}

internal fun dropPushStatOf(size: Long): Result<DeviceFileStat> = Result.success(
    DeviceFileStat(
        type = DeviceFileType.FILE,
        sizeBytes = size,
        modifiedEpochSeconds = 1_700_000_000L,
        permissions = "-rw-rw----",
        owner = "root",
        group = "root",
    ),
)

/** 「目标不存在」判别的设备侧 stat 失败原文（内核按 No such file 信号判不存在） */
internal fun dropPushAbsentStat(): Result<DeviceFileStat> = Result.failure(
    IllegalStateException("stat 读取失败（adb 退出码 1）：stat: '/sdcard/x': No such file or directory"),
)

// ── 安装/包列举共享 fakes（跨测试类复用——路由层单测与窗内容渲染单测同一套，零真 adb） ──

/** 安装 gateway fake：记录命令全文（逐字节断言锚），按脚本回执 */
internal class FakeInstallGateway(
    private val script: suspend (command: String, onLine: suspend (ShellOutput) -> Unit) -> Int,
) : AdbInstallGateway {
    val commands = mutableListOf<String>()

    override suspend fun execute(
        command: String,
        onStarted: (AdbInstallHandle) -> Unit,
        onLine: suspend (ShellOutput) -> Unit,
    ): Result<Int> {
        commands += command
        onStarted(object : AdbInstallHandle {
            override fun kill() = Unit
        })
        return Result.success(script(command, onLine))
    }
}

/**
 * 包列举/详情 gateway fake：`pm list packages`（全部包）与 `-s`（系统包）各自按序
 * 弹出脚本输出，`dumpsys package` 按序弹出详情结果——安装追溯升档的差集/详情两用。
 */
internal class FakePkgGateway(
    allLists: List<String> = emptyList(),
    systemLists: List<String> = emptyList(),
    detailResults: List<Result<ShellResult>> = emptyList(),
) : com.jiaoay.toolbelt.device.AdbProcessRunner {
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

/** `pm list packages --show-versioncode -U` 一行一包输出（差集脚本件） */
internal fun pkgListOf(vararg names: String): String =
    names.joinToString("\n") { "package:$it versionCode:7 uid:10234" }

/** `dumpsys package <pkg>` 单包记录 fixture（追溯升档详情脚本件） */
internal fun detailsFixtureOf(pkg: String, versionName: String?): String = """
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
