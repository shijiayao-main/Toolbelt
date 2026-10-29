package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 拉取内核守护（fake gateway + fake stat 注入，零真子进程）：
 * - .part 语义：成功校验后转正（.part 消失、终态文件在位）、中断（退出码非零）/
 *   校验失败（大小不等）均清理 .part 不落终态文件；
 * - 通道纪律逐字节断言：双路径单引号零展开（含空格/内嵌单引号转义）+
 *   AdbCommandRewriter 注入 adb 路径与 `-s <serial>`；
 * - 摘要行解析：括号尾巴锚（N bytes in Xs）+ 速度 token，全 miss 容忍 null；
 * - 失败 stderr 原文透传 + 取消人话回执（kill 后退出码非零走清理路径）；
 * - 前置校验：非普通文件（目录/symlink）与 stat 失败零 spawn 人话拒绝；
 *   空参数零 spawn；
 * - 既有终态文件被原子覆盖（REPLACE_EXISTING）、陈旧 .part 先清理、
 *   父目录按需创建。
 *
 * 🔴 单测不碰用户数据根：本地目标一律落在临时目录。
 */
class DeviceFilePullerTest {

    /** fake 句柄：killed 标记供取消用例断言「kill 经句柄触达」 */
    private class RecordingHandle : AdbPullHandle {
        var killed = false
        override fun kill() {
            killed = true
        }
    }

    /** gateway fake：记录命令全文（逐字节断言锚），按脚本回执并模拟 adb 写 .part */
    private class FakePullGateway(
        private val script: suspend (command: String, handle: AdbPullHandle, onLine: suspend (ShellOutput) -> Unit) -> Int,
    ) : AdbPullGateway {
        val commands = mutableListOf<String>()

        override suspend fun execute(
            command: String,
            onStarted: (AdbPullHandle) -> Unit,
            onLine: suspend (ShellOutput) -> Unit,
        ): Result<Int> {
            commands += command
            val handle = RecordingHandle()
            onStarted(handle)
            return Result.success(script(command, handle, onLine))
        }
    }

    /** fake stat：拉取前置校验面（路径+串号记录锚） */
    private class FakeStat(private val stat: DeviceFileStat) {
        val calls = mutableListOf<Pair<String, String>>()
        val statEntry: suspend (String, String) -> Result<DeviceFileStat> = { path, serial ->
            calls += path to serial
            Result.success(stat)
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

    private fun tempDir(): java.io.File = Files.createTempDirectory("device-pull").toFile()

    /** 模拟 adb 把 size 字节写进 .part 并吐摘要行的成功脚本 */
    private fun successfulPullScript(partPath: String, size: Long): suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int =
        { _, _, onLine ->
            java.io.File(partPath).writeBytes(ByteArray(size.toInt()))
            onLine(ShellOutput("1 file pulled, 0 skipped. 38.4 MB/s ($size bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }

    private fun puller(gateway: AdbPullGateway, stat: DeviceFileStat, adbPath: String = ""): DeviceFilePuller =
        DeviceFilePuller(
            runner = NoopRunner,
            gateway = gateway,
            statEntry = FakeStat(stat).statEntry,
            endpointReader = { AdbEndpoint(adbPath) },
        )

    @Test
    fun `successful pull promotes part file after size verification and parses summary`() = runTest {
        val dir = tempDir()
        val gateway = FakePullGateway(successfulPullScript("${dir.absolutePath}/build.log.part", 512))
        val result = puller(gateway, statOf(512)).pullFile("/sdcard/build.log", "${dir.absolutePath}/build.log", "S1")

        assertEquals(
            listOf("adb -s S1 pull '/sdcard/build.log' '${dir.absolutePath}/build.log.part'"),
            gateway.commands,
            "命令 = adb -s <serial> pull + 双路径单引号零展开（本地路径含空格也字面化）",
        )
        val receipt = result.getOrThrow()
        assertEquals("${dir.absolutePath}${java.io.File.separator}build.log", receipt.localPath)
        assertEquals(512L, receipt.sizeBytes, "校验通过的本地实际大小（= 设备侧期望）")
        assertEquals(512L, receipt.summaryBytes, "摘要行括号尾巴的字节数")
        assertEquals("38.4 MB/s", receipt.speedText, "摘要行速度 token 原文")
        assertEquals(512L, java.io.File(receipt.localPath).length(), "终态文件转正在位")
        assertTrue(!java.io.File("${dir.absolutePath}/build.log.part").exists(), "转正后 .part 不存在")
    }

    @Test
    fun `both paths containing spaces and embedded quote stay literal in command`() = runTest {
        val dir = tempDir()
        val gateway = FakePullGateway { _, _, _ -> 1 }
        puller(gateway, statOf(8), adbPath = "/opt/my adb")
            .pullFile("/sdcard/my dir/it's.log", "${dir.absolutePath}/my dl/it's.log", "S1")

        assertEquals(
            listOf(
                """'/opt/my adb' -s S1 pull '/sdcard/my dir/it'\''s.log' '${dir.absolutePath}/my dl/it'\''s.log.part'""",
            ),
            gateway.commands,
            "设备路径/本地路径含空格与内嵌单引号必须字面化（单引号零展开 + '\\'' 重开闭转义），adb 路径含空格同样包裹",
        )
    }

    @Test
    fun `nonzero exit cleans part and surfaces stderr verbatim without final file`() = runTest {
        val dir = tempDir()
        val gateway = FakePullGateway { command, _, onLine ->
            // 模拟中断的 pull：留下无标记部分文件（硬需求场景）后非零退出
            val partPath = command.substringAfterLast(" ").trim('\'')
            java.io.File(partPath).writeBytes(ByteArray(85))
            onLine(ShellOutput("adb: error: failed to copy '/sdcard/big.alog' to 'x': Operation not permitted", ShellOutputSource.STDERR))
            1
        }
        val result = puller(gateway, statOf(349)).pullFile("/sdcard/big.alog", "${dir.absolutePath}/big.alog", "S1")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("adb 退出码 1"), "回执含退出码（实际：$message）")
        assertTrue(
            message.contains("adb: error: failed to copy '/sdcard/big.alog' to 'x': Operation not permitted"),
            "设备侧 stderr 原文透传（实际：$message）",
        )
        assertTrue(message.contains("临时文件已清理"), "回执说明清理动作（实际：$message）")
        assertTrue(!java.io.File("${dir.absolutePath}/big.alog").exists(), "终态文件不落（部分文件不得冒充完整）")
        assertTrue(!java.io.File("${dir.absolutePath}/big.alog.part").exists(), ".part 已清理")
    }

    @Test
    fun `size mismatch on zero exit cleans part with human receipt`() = runTest {
        val dir = tempDir()
        val gateway = FakePullGateway { command, _, _ ->
            // 退出 0 但只写到 85B（期望 349B）：校验面兜底
            val partPath = command.substringAfterLast(" ").trim('\'')
            java.io.File(partPath).writeBytes(ByteArray(85))
            0
        }
        val result = puller(gateway, statOf(349)).pullFile("/sdcard/big.alog", "${dir.absolutePath}/big.alog", "S1")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("传输中断，文件不完整已清理"), "人话回执（实际：$message）")
        assertTrue(message.contains("本地 85B"), "回执含本地实际（实际：$message）")
        assertTrue(message.contains("设备侧期望 349B"), "回执含设备侧期望（实际：$message）")
        assertTrue(!java.io.File("${dir.absolutePath}/big.alog").exists(), "不落终态文件")
        assertTrue(!java.io.File("${dir.absolutePath}/big.alog.part").exists(), ".part 已清理")
    }

    @Test
    fun `requestCancel yields cancelled receipt with part cleaned`() = runTest {
        val dir = tempDir()
        lateinit var victim: DeviceFilePuller
        val gateway = FakePullGateway { command, handle, _ ->
            val partPath = command.substringAfterLast(" ").trim('\'')
            java.io.File(partPath).writeBytes(ByteArray(85))
            victim.requestCancel() // 模拟用户在途取消（kill 后 adb 非零退出）
            if ((handle as RecordingHandle).killed) 1 else 0
        }
        victim = puller(gateway, statOf(349))
        val result = victim.pullFile("/sdcard/big.alog", "${dir.absolutePath}/big.alog", "S1")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("拉取已取消"), "取消回执区别于设备侧错误透传（实际：$message）")
        assertTrue(message.contains("临时文件已清理"), "取消同走 .part 清理路径（实际：$message）")
        assertTrue(!java.io.File("${dir.absolutePath}/big.alog").exists())
        assertTrue(!java.io.File("${dir.absolutePath}/big.alog.part").exists())
    }

    @Test
    fun `non-file stat and stat failure reject before any spawn`() = runTest {
        val dir = tempDir()
        val typeStat = FakeStat(statOf(4096, DeviceFileType.DIRECTORY))
        val typePuller = DeviceFilePuller(runner = NoopRunner, gateway = FakePullGateway { _, _, _ -> 0 }, statEntry = typeStat.statEntry, endpointReader = { AdbEndpoint("") })
        val typeResult = typePuller.pullFile("/sdcard/Movies", "${dir.absolutePath}/Movies", "S1")
        assertTrue(typeResult.exceptionOrNull()?.message!!.contains("仅支持拉取普通文件"), "目录条目人话拒绝")
        assertEquals(listOf("/sdcard/Movies" to "S1"), typeStat.calls, "前置 stat 已执行")

        val failStat: suspend (String, String) -> Result<DeviceFileStat> = { _, _ ->
            Result.failure(IllegalStateException("stat 读取失败（adb 退出码 1）：stat: '/sdcard/x': No such file or directory"))
        }
        val failPuller = DeviceFilePuller(runner = NoopRunner, gateway = FakePullGateway { _, _, _ -> 0 }, statEntry = failStat, endpointReader = { AdbEndpoint("") })
        val failResult = failPuller.pullFile("/sdcard/x", "${dir.absolutePath}/x", "S1")
        assertTrue(
            failResult.exceptionOrNull()?.message!!.contains("stat: '/sdcard/x': No such file or directory"),
            "stat 失败原文透传（实际：${failResult.exceptionOrNull()?.message}）",
        )
    }

    @Test
    fun `empty arguments reject before any spawn`() = runTest {
        val gateway = FakePullGateway { _, _, _ -> 0 }
        val puller = puller(gateway, statOf(8))
        puller.pullFile("", "/tmp/x", "S1")
        puller.pullFile("/sdcard/x", "", "S1")
        puller.pullFile("/sdcard/x", "/tmp/x", "")
        assertEquals(0, gateway.commands.size, "空参数零 spawn（stat 与 pull 均不发）")
    }

    @Test
    fun `successful pull atomically replaces existing final and cleans stale part`() = runTest {
        val dir = tempDir()
        val target = java.io.File(dir, "build.log")
        target.writeText("旧内容")
        java.io.File(dir, "build.log.part").writeText("上次中断的残渣") // 陈旧 .part 先行清理
        val gateway = FakePullGateway(successfulPullScript("${dir.absolutePath}/build.log.part", 8))

        val result = puller(gateway, statOf(8)).pullFile("/sdcard/build.log", target.absolutePath, "S1")

        result.getOrThrow()
        assertEquals(8L, target.length(), "终态文件被本次结果覆盖（原子替换语义）")
        assertEquals(8, target.readBytes().size)
        assertTrue(!java.io.File(dir, "build.log.part").exists(), "陈旧 .part 已清理")
    }

    @Test
    fun `missing parent directories are created on demand`() = runTest {
        val dir = tempDir()
        val deep = "${dir.absolutePath}/dl/sub"
        val gateway = FakePullGateway(successfulPullScript("$deep/build.log.part", 4))
        val result = puller(gateway, statOf(4)).pullFile("/sdcard/build.log", "$deep/build.log", "S1")

        assertEquals(4L, java.io.File("$deep/build.log").length(), "不存在的父目录按需创建")
    }

    @Test
    fun `summary parsing anchors on parenthesized tail and tolerates missing line`() {
        val summary = DeviceFilePuller.parsePulledSummary(
            listOf("1 file pulled, 0 skipped. 38.4 MB/s (402653184 bytes in 10.500s)"),
        )
        assertEquals(402653184L, summary?.bytes, "括号尾巴字节锚")
        assertEquals("38.4 MB/s", summary?.speedText, "速度 token")
        assertEquals("10.500", summary?.secondsText, "耗时秒")
        assertEquals(1L, summary?.fileCount, "单文件摘要计数 token")

        // 老格式摘要（无 skipped 段）同锚解析
        val legacy = DeviceFilePuller.parsePulledSummary(listOf("1 file pulled. 2.1 MB/s (512 bytes in 0.000s)"))
        assertEquals(512L, legacy?.bytes)
        assertEquals("2.1 MB/s", legacy?.speedText)

        // 多行输出取末个命中行
        val multi = DeviceFilePuller.parsePulledSummary(
            listOf("", "1 file pulled, 0 skipped. 38.4 MB/s (512 bytes in 0.013s)"),
        )
        assertEquals(512L, multi?.bytes)

        // 目录链多文件摘要（N files pulled）同锚解析——文件数/总字节是信息面
        val dirSummary = DeviceFilePuller.parsePulledSummary(
            listOf("3 files pulled, 0 skipped. 10.6 MB/s (612 bytes in 0.500s)"),
        )
        assertEquals(612L, dirSummary?.bytes, "目录摘要总字节")
        assertEquals(3L, dirSummary?.fileCount, "目录摘要文件数")
        assertEquals("10.6 MB/s", dirSummary?.speedText)

        assertNull(DeviceFilePuller.parsePulledSummary(listOf("")), "无摘要行为 null（信息面缺席不否定拉取）")
        assertNull(DeviceFilePuller.parsePulledSummary(emptyList()), "零输出为 null")
    }

    // —— 目录递归拉取链（fake gateway，零真子进程；校验面弱化契约 = 退出码 +
    //    .part 临时目录成形，无单文件级 stat 尺寸校验，见内核 KDoc） ——

    /** 模拟 adb 把递归产物写进 .part 临时目录并吐多文件摘要行的成功脚本 */
    private fun successfulDirPullScript(partDirPath: String): suspend (String, AdbPullHandle, suspend (ShellOutput) -> Unit) -> Int =
        { _, _, onLine ->
            val partDir = java.io.File(partDirPath).apply { mkdirs() }
            java.io.File(partDir, "a.alog").writeBytes(ByteArray(512))
            java.io.File(partDir, "sub").mkdirs()
            java.io.File(partDir, "sub/b.alog").writeBytes(ByteArray(100))
            onLine(ShellOutput("2 files pulled, 0 skipped. 38.4 MB/s (612 bytes in 1.0s)", ShellOutputSource.STDOUT))
            0
        }

    @Test
    fun `directory pull builds byte-exact command and promotes part dir recursively`() = runTest {
        val dir = tempDir()
        val gateway = FakePullGateway(successfulDirPullScript("${dir.absolutePath}/Movies.part"))
        val result = puller(gateway, statOf(8)).pullDirectory("/sdcard/Movies", "${dir.absolutePath}/Movies", "S1")

        assertEquals(
            listOf("adb -s S1 pull '/sdcard/Movies' '${dir.absolutePath}/Movies.part'"),
            gateway.commands,
            "目录链命令 = adb -s <serial> pull + 双路径单引号零展开（本地目标为 .part 目录）",
        )
        val receipt = result.getOrThrow()
        assertEquals("${dir.absolutePath}${java.io.File.separator}Movies", receipt.localPath)
        assertEquals(2L, receipt.fileCount, "摘要行文件数（信息面）")
        assertEquals(612L, receipt.totalBytes, "摘要行总字节（信息面）")
        assertEquals("38.4 MB/s", receipt.speedText)
        val promoted = java.io.File(dir, "Movies")
        assertTrue(promoted.isDirectory, "转正后终态目录在位")
        assertEquals(512L, java.io.File(promoted, "a.alog").length(), "顶层递归产物在位")
        assertTrue(java.io.File(promoted, "sub/b.alog").isFile, "子目录递归产物在位")
        assertTrue(!java.io.File(dir, "Movies.part").exists(), "转正后 .part 目录消失")
    }

    @Test
    fun `directory pull nonzero exit cleans part dir recursively without final dir`() = runTest {
        val dir = tempDir()
        val gateway = FakePullGateway { command, _, onLine ->
            val partPath = command.substringAfterLast(" ").trim('\'')
            java.io.File(partPath).mkdirs()
            java.io.File(partPath, "half.alog").writeBytes(ByteArray(85))
            onLine(ShellOutput("adb: error: failed to copy '/sdcard/Movies/x.alog' to 'x': Operation not permitted", ShellOutputSource.STDERR))
            1
        }
        val result = puller(gateway, statOf(8)).pullDirectory("/sdcard/Movies", "${dir.absolutePath}/Movies", "S1")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("adb 退出码 1"), "回执含退出码（实际：$message）")
        assertTrue(message.contains("Operation not permitted"), "设备侧 stderr 原文透传（实际：$message）")
        assertTrue(message.contains("临时目录已清理"), "回执说明清理动作（实际：$message）")
        assertTrue(!java.io.File(dir, "Movies").exists(), "终态目录不落（半途目录不得冒充完整）")
        assertTrue(!java.io.File(dir, "Movies.part").exists(), ".part 目录递归清理（内含半途文件）")
    }

    @Test
    fun `directory pull cancel yields cancelled receipt with part dir cleaned`() = runTest {
        val dir = tempDir()
        lateinit var victim: DeviceFilePuller
        val gateway = FakePullGateway { command, handle, _ ->
            val partPath = command.substringAfterLast(" ").trim('\'')
            java.io.File(partPath).mkdirs()
            java.io.File(partPath, "half.alog").writeBytes(ByteArray(85))
            victim.requestCancel() // 模拟用户在途取消（kill 后 adb 非零退出）
            if ((handle as RecordingHandle).killed) 1 else 0
        }
        victim = puller(gateway, statOf(8))
        val result = victim.pullDirectory("/sdcard/Movies", "${dir.absolutePath}/Movies", "S1")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("拉取已取消"), "取消回执区别于设备侧错误透传（实际：$message）")
        assertTrue(message.contains("临时目录已清理"), "取消同走 .part 递归清理路径（实际：$message）")
        assertTrue(!java.io.File(dir, "Movies").exists())
        assertTrue(!java.io.File(dir, "Movies.part").exists())
    }

    @Test
    fun `directory pull overwrites existing same-name dir and self-heals stale residues`() = runTest {
        val dir = tempDir()
        val target = java.io.File(dir, "Movies").apply { mkdirs() }
        java.io.File(target, "old.log").writeText("旧内容")
        val stalePart = java.io.File(dir, "Movies.part").apply { mkdirs() }
        java.io.File(stalePart, "junk").writeText("上次中断残渣")
        val staleAside = java.io.File(dir, "Movies.part-old").apply { mkdirs() }
        java.io.File(staleAside, "oldjunk").writeText("上次转正中断的旧终态")
        val gateway = FakePullGateway(successfulDirPullScript("${dir.absolutePath}/Movies.part"))

        val result = puller(gateway, statOf(8)).pullDirectory("/sdcard/Movies", target.absolutePath, "S1")

        result.getOrThrow()
        assertTrue(!java.io.File(target, "old.log").exists(), "同名目录覆盖语义 = 终态被本次结果替换（与单文件一致）")
        assertTrue(java.io.File(target, "a.alog").isFile, "新产物在位")
        assertTrue(!stalePart.exists(), "陈旧 .part 自愈清理（残留即不完整产物）")
        assertTrue(!staleAside.exists(), "陈旧让位副本自愈清理")
    }

    @Test
    fun `directory pull zero exit without part dir fails with human receipt`() = runTest {
        val dir = tempDir()
        val gateway = FakePullGateway { _, _, _ -> 0 } // 退出 0 但临时目录未成形（设备侧非目录形态）
        val result = puller(gateway, statOf(8)).pullDirectory("/sdcard/x", "${dir.absolutePath}/x", "S1")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message.contains("本地临时目录未成形"), "成形校验面兜底（实际：$message）")
        assertTrue(!java.io.File(dir, "x").exists(), "不落终态目录")
    }

    @Test
    fun `directory pull rejects empty arguments without spawn`() = runTest {
        val dir = tempDir()
        val gateway = FakePullGateway { _, _, _ -> 0 }
        val p = puller(gateway, statOf(8))
        p.pullDirectory("", "${dir.absolutePath}/Movies", "S1")
        p.pullDirectory("/sdcard/Movies", "", "S1")
        p.pullDirectory("/sdcard/Movies", "${dir.absolutePath}/Movies", "")
        assertEquals(0, gateway.commands.size, "空参数零 spawn")
    }
}
