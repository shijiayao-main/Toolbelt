package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 推送内核守护（fake gateway + fake stat 注入，零真子进程）：
 * - 不静默覆盖：overwrite=false 且目标已存在 → 类型化 [DevicePushTargetExistsException]
 *   拒推、零 adb push spawn；overwrite=true 直接推送（跳过预检）；
 * - [DeviceFilePusher.checkTargetExists] 两态：stat 成功 = 已存在、「No such file
 *   or directory」= 不存在、其余 stat 失败原样透传；
 * - 通道纪律逐字节断言：双路径单引号零展开（含空格/内嵌单引号转义）+
 *   AdbCommandRewriter 注入 adb 路径与 `-s <serial>`；
 * - 校验面：推送后设备侧 stat 大小必须等于本地期望（不等/读不到/非普通文件
 *   均失败回执）；
 * - 摘要行解析：括号尾巴锚 + 速度 token（与拉取侧同构），全 miss 容忍 null；
 * - 失败 stderr 原文透传 + 取消人话回执（含设备侧残留部分文件明示——无 .part
 *   语义）；
 * - 本地路径不存在/空参数零 spawn 拒绝。
 *
 * 🔴 单测不碰用户数据根：本地源文件一律落在临时目录。
 */
class DeviceFilePusherTest {

    /** fake 句柄：killed 标记供取消用例断言「kill 经句柄触达」 */
    private class RecordingHandle : AdbPushHandle {
        var killed = false
        override fun kill() {
            killed = true
        }
    }

    /** gateway fake：记录命令全文（逐字节断言锚），按脚本回执 */
    private class FakePushGateway(
        private val script: suspend (command: String, handle: AdbPushHandle, onLine: suspend (ShellOutput) -> Unit) -> Int,
    ) : AdbPushGateway {
        val commands = mutableListOf<String>()

        override suspend fun execute(
            command: String,
            onStarted: (AdbPushHandle) -> Unit,
            onLine: suspend (ShellOutput) -> Unit,
        ): Result<Int> {
            commands += command
            val handle = RecordingHandle()
            onStarted(handle)
            return Result.success(script(command, handle, onLine))
        }
    }

    private fun statOf(size: Long, type: DeviceFileType = DeviceFileType.FILE) = DeviceFileStat(
        type = type,
        sizeBytes = size,
        modifiedEpochSeconds = 1_700_000_000L,
        permissions = "-rw-rw----",
        owner = "root",
        group = "root",
    )

    private fun tempDir(): java.io.File = Files.createTempDirectory("device-push").toFile()

    /** 本地源文件（临时目录，512 字节缺省） */
    private fun localFile(dir: java.io.File, name: String = "build.log", size: Int = 512): java.io.File =
        java.io.File(dir, name).apply { writeBytes(ByteArray(size)) }

    /**
     * fake stat（预检/校验两用，按调用序路由）：
     * - existSize 非 null：首轮预检回「已存在」成功值（内核拒推后不再有本轮次调用；
     *   确认覆盖重推时次轮起是后置校验，回 verifySize）；
     * - existSize 为 null：首轮预检回 No such file 原文（不存在），后置校验轮回 verifySize。
     * [expectPrecheck] = false（overwrite=true 用例）：无预检轮，全部调用回 verifySize。
     */
    private class FakePushStat(
        private val verifySize: Long,
        private val existSize: Long? = null,
        private val verifyType: DeviceFileType = DeviceFileType.FILE,
        private val expectPrecheck: Boolean = true,
    ) {
        var calls = 0

        // 嵌套类不吃外围实例：stat 构造自持（与外围 statOf 同形态）
        private fun statOf(size: Long, type: DeviceFileType = DeviceFileType.FILE) = DeviceFileStat(
            type = type,
            sizeBytes = size,
            modifiedEpochSeconds = 1_700_000_000L,
            permissions = "-rw-rw----",
            owner = "root",
            group = "root",
        )

        val statEntry: suspend (String, String) -> Result<DeviceFileStat> = { _, _ ->
            calls++
            when {
                !expectPrecheck -> Result.success(statOf(verifySize, verifyType))
                existSize != null && calls == 1 -> Result.success(statOf(existSize))
                existSize != null -> Result.success(statOf(verifySize, verifyType))
                calls == 1 ->
                    Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：stat: '/sdcard/x': No such file or directory"))

                else -> Result.success(statOf(verifySize, verifyType))
            }
        }
    }

    private fun pusher(
        gateway: AdbPushGateway,
        stat: FakePushStat,
        adbPath: String = "",
    ): DeviceFilePusher = DeviceFilePusher(
        runner = NoopRunner,
        gateway = gateway,
        statEntry = stat.statEntry,
        endpointReader = { AdbEndpoint(adbPath) },
    )

    /** 模拟 adb push 成功并吐摘要行的脚本 */
    private fun successfulPushScript(): suspend (String, AdbPushHandle, suspend (ShellOutput) -> Unit) -> Int =
        { _, _, onLine ->
            onLine(ShellOutput("1 file pushed, 0 skipped. 38.4 MB/s (512 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }

    @Test
    fun `target absent push succeeds with verified size and parsed summary`() = runTest {
        val dir = tempDir()
        val local = localFile(dir)
        val stat = FakePushStat(verifySize = 512)
        val gateway = FakePushGateway(successfulPushScript())
        val result = pusher(gateway, stat).pushFile(local.absolutePath, "/sdcard/", "S1")

        assertEquals(
            listOf("adb -s S1 push '${local.absolutePath}' '/sdcard/build.log'"),
            gateway.commands,
            "命令 = adb -s <serial> push + 双路径单引号零展开（设备目标 = 目录去尾斜杠 + 本地名）",
        )
        assertEquals(2, stat.calls, "预检 stat（不存在）+ 推送后校验 stat 各一次")
        val receipt = result.getOrThrow()
        assertEquals("/sdcard/build.log", receipt.devicePath, "设备目标全路径进回执")
        assertEquals(512L, receipt.sizeBytes, "校验通过的大小（本地期望 = 设备侧实际）")
        assertEquals(512L, receipt.summaryBytes, "摘要行括号尾巴的字节数")
        assertEquals("38.4 MB/s", receipt.speedText, "摘要行速度 token 原文")
    }

    @Test
    fun `both paths containing spaces and embedded quote stay literal in command`() = runTest {
        val dir = tempDir()
        val local = localFile(dir, name = "it's.log")
        val stat = FakePushStat(verifySize = 512)
        val gateway = FakePushGateway(successfulPushScript())
        pusher(gateway, stat, adbPath = "/opt/my adb")
            .pushFile(local.absolutePath, "/sdcard/my dir/", "S1")

        // 插值路径含内嵌单引号：期望侧同样按 `'\''` 重开闭转义后才可逐字节比对
        val escapedLocal = local.absolutePath.replace("'", "'\\''")
        assertEquals(
            listOf(
                """'/opt/my adb' -s S1 push '$escapedLocal' '/sdcard/my dir/it'\''s.log'""",
            ),
            gateway.commands,
            "本地/设备双路径含空格与内嵌单引号必须字面化（单引号零展开 + '\\'' 重开闭转义），adb 路径含空格同样包裹",
        )
    }

    @Test
    fun `existing target rejects without spawn when overwrite is false`() = runTest {
        val dir = tempDir()
        val local = localFile(dir)
        val stat = FakePushStat(verifySize = 512, existSize = 1024)
        val gateway = FakePushGateway { _, _, _ -> 0 }
        val result = pusher(gateway, stat).pushFile(local.absolutePath, "/sdcard/", "S1")

        val cause = result.exceptionOrNull()
        assertIs<DevicePushTargetExistsException>(cause, "已存在拒绝是类型化回执（调用面据此出确认对话）")
        assertEquals("/sdcard/build.log", cause.devicePath)
        assertTrue(cause.message!!.contains("设备目标已存在"), "拒绝回执人话（实际：${cause.message}）")
        assertEquals(0, gateway.commands.size, "零 adb push spawn——确认前不碰设备")
        assertEquals(1, stat.calls, "只有预检 stat 一次，无后置校验 stat")
    }

    @Test
    fun `overwrite true skips precheck and pushes`() = runTest {
        val dir = tempDir()
        val local = localFile(dir)
        val stat = FakePushStat(verifySize = 512, expectPrecheck = false)
        val gateway = FakePushGateway(successfulPushScript())
        val result = pusher(gateway, stat).pushFile(local.absolutePath, "/sdcard/", "S1", overwrite = true)

        assertEquals(1, stat.calls, "overwrite=true 只做推送后校验 stat（跳过存在预检）")
        assertEquals(1, gateway.commands.size, "确认后真正推送一次")
        assertEquals("/sdcard/build.log", result.getOrThrow().devicePath)
    }

    @Test
    fun `nonzero exit surfaces stderr verbatim with partial-file warning`() = runTest {
        val dir = tempDir()
        val local = localFile(dir)
        val stat = FakePushStat(verifySize = 512)
        val gateway = FakePushGateway { _, _, onLine ->
            onLine(ShellOutput("adb: error: failed to copy '${local.absolutePath}' to '/sdcard/build.log': Read-only file system", ShellOutputSource.STDERR))
            1
        }
        val result = pusher(gateway, stat).pushFile(local.absolutePath, "/sdcard/", "S1")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("adb 退出码 1"), "回执含退出码（实际：$message）")
        assertTrue(
            message.contains("Read-only file system"),
            "设备侧 stderr 原文透传（实际：$message）",
        )
        assertTrue(message.contains("可能残留部分文件"), "回执明示设备侧残留面（实际：$message）")
        assertEquals(1, stat.calls, "失败路径无后置校验 stat")
    }

    @Test
    fun `requestCancel yields cancelled receipt with partial-file warning`() = runTest {
        val dir = tempDir()
        val local = localFile(dir)
        lateinit var victim: DeviceFilePusher
        val gateway = FakePushGateway { _, handle, _ ->
            victim.requestCancel() // 模拟用户在途取消（kill 后 adb 非零退出）
            if ((handle as RecordingHandle).killed) 1 else 0
        }
        victim = pusher(gateway, FakePushStat(verifySize = 512))
        val result = victim.pushFile(local.absolutePath, "/sdcard/", "S1")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("推送已取消"), "取消回执区别于设备侧错误透传（实际：$message）")
        assertTrue(message.contains("可能残留部分文件"), "取消回执明示无 .part 限制（实际：$message）")
    }

    @Test
    fun `post-push size mismatch and unreadable stat fail the receipt`() = runTest {
        val dir = tempDir()
        val local = localFile(dir)
        val mismatch = pusher(FakePushGateway { _, _, _ -> 0 }, FakePushStat(verifySize = 85))
        val mismatchResult = mismatch.pushFile(local.absolutePath, "/sdcard/", "S1")
        val mismatchMessage = mismatchResult.exceptionOrNull()?.message ?: ""
        assertTrue(mismatchMessage.contains("推送后校验不匹配"), "半截写入被校验面兜底（实际：$mismatchMessage）")
        assertTrue(mismatchMessage.contains("设备侧 85B"), "回执含设备侧实际（实际：$mismatchMessage）")
        assertTrue(mismatchMessage.contains("本地 512B"), "回执含本地期望（实际：$mismatchMessage）")

        val unreadable: suspend (String, String) -> Result<DeviceFileStat> = { _, _ ->
            Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：adb: error"))
        }
        val unreadablePusher = DeviceFilePusher(
            runner = NoopRunner,
            gateway = FakePushGateway { _, _, _ -> 0 },
            statEntry = unreadable,
            endpointReader = { AdbEndpoint("") },
        )
        // overwrite=true 跳过预检直达推送：推送后校验 stat 读不到 = 结果未确认
        val unreadableResult = unreadablePusher.pushFile(local.absolutePath, "/sdcard/", "S1", overwrite = true)
        assertTrue(
            (unreadableResult.exceptionOrNull()?.message ?: "").contains("推送后校验失败"),
            "校验 stat 读不到 = 推送结果未确认（实际：${unreadableResult.exceptionOrNull()?.message}）",
        )
    }

    @Test
    fun `local path missing or non-file rejects before any spawn`() = runTest {
        val dir = tempDir()
        val stat = FakePushStat(verifySize = 512)
        val gateway = FakePushGateway { _, _, _ -> 0 }
        val pusher = pusher(gateway, stat)

        val missing = pusher.pushFile("${dir.absolutePath}/absent.log", "/sdcard/", "S1")
        assertTrue(
            (missing.exceptionOrNull()?.message ?: "").contains("本地文件不存在"),
            "本地源缺席人话拒绝（实际：${missing.exceptionOrNull()?.message}）",
        )
        pusher.pushFile(dir.absolutePath, "/sdcard/", "S1") // 目录不是普通文件
        pusher.pushFile("/sdcard/x.log", "", "S1")
        pusher.pushFile("", "/sdcard/", "S1")
        pusher.pushFile("/sdcard/x.log", "/sdcard/", "")
        assertEquals(0, gateway.commands.size, "本地校验/空参数零 push spawn")
        assertEquals(0, stat.calls, "预检 stat 同样零调用（本地侧拒绝先于设备侧探测）")
    }

    @Test
    fun `checkTargetExists maps stat outcomes to exists two states`() = runTest {
        val gateway = FakePushGateway { _, _, _ -> 0 }
        val existsPusher = DeviceFilePusher(
            runner = NoopRunner,
            gateway = gateway,
            statEntry = { _, _ -> Result.success(statOf(1024)) },
            endpointReader = { AdbEndpoint("") },
        )
        assertEquals(true, existsPusher.checkTargetExists("/sdcard/", "build.log", "S1").getOrThrow(), "stat 成功 = 已存在")

        val absentPusher = DeviceFilePusher(
            runner = NoopRunner,
            gateway = gateway,
            statEntry = { _, _ ->
                Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：stat: '/sdcard/build.log': No such file or directory"))
            },
            endpointReader = { AdbEndpoint("") },
        )
        assertEquals(
            false,
            absentPusher.checkTargetExists("/sdcard/", "build.log", "S1").getOrThrow(),
            "No such file 原文 = 不存在（false 不是失败）",
        )

        val opaquePusher = DeviceFilePusher(
            runner = NoopRunner,
            gateway = gateway,
            statEntry = { _, _ ->
                Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：Permission denied"))
            },
            endpointReader = { AdbEndpoint("") },
        )
        val opaque = opaquePusher.checkTargetExists("/sdcard/", "build.log", "S1")
        assertTrue(opaque.isFailure, "其余 stat 失败原样透传不猜（实际：$opaque）")
        assertEquals(0, gateway.commands.size, "预检是只读探测，零 push spawn")
    }

    @Test
    fun `precheck failure inside push surfaces verbatim without spawn`() = runTest {
        val dir = tempDir()
        val local = localFile(dir)
        val gateway = FakePushGateway { _, _, _ -> 0 }
        val pusher = DeviceFilePusher(
            runner = NoopRunner,
            gateway = gateway,
            statEntry = { _, _ -> Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：Connection refused")) },
            endpointReader = { AdbEndpoint("") },
        )
        val result = pusher.pushFile(local.absolutePath, "/sdcard/", "S1")
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("推送前置预检失败"), "预检不可判失败不冒充「已存在」（实际：$message）")
        assertTrue(message.contains("Connection refused"), "预检失败原文透传（实际：$message）")
        assertEquals(0, gateway.commands.size, "预检失败零 push spawn")
    }

    @Test
    fun `summary parsing anchors on parenthesized tail and tolerates missing line`() {
        val summary = DeviceFilePusher.parsePushedSummary(
            listOf("1 file pushed, 0 skipped. 38.4 MB/s (402653184 bytes in 10.500s)"),
        )
        assertEquals(402653184L, summary?.bytes, "括号尾巴字节锚（与拉取侧摘要同构）")
        assertEquals("38.4 MB/s", summary?.speedText, "速度 token")
        assertEquals("10.500", summary?.secondsText, "耗时秒")

        // 老格式摘要（无 skipped 段）同锚解析
        val legacy = DeviceFilePusher.parsePushedSummary(listOf("1 file pushed. 2.1 MB/s (512 bytes in 0.000s)"))
        assertEquals(512L, legacy?.bytes)
        assertEquals("2.1 MB/s", legacy?.speedText)

        // 多行输出取末个命中行
        val multi = DeviceFilePusher.parsePushedSummary(
            listOf("", "1 file pushed, 0 skipped. 38.4 MB/s (512 bytes in 0.013s)"),
        )
        assertEquals(512L, multi?.bytes)

        assertNull(DeviceFilePusher.parsePushedSummary(listOf("")), "无摘要行为 null（信息面缺席不否定推送）")
        assertNull(DeviceFilePusher.parsePushedSummary(emptyList()), "零输出为 null")
    }

    @Test
    fun `device target joins directory without slash and file name`() {
        assertEquals("/sdcard/build.log", DeviceFilePusher.joinDeviceTarget("/sdcard/", "build.log"))
        assertEquals("/sdcard/Movies/build.log", DeviceFilePusher.joinDeviceTarget("/sdcard/Movies", "build.log"))
    }
}
