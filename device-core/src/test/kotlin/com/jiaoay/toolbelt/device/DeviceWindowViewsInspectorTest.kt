package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.device.DeviceWindowViewsFixtures.windowViewsZip
import com.jiaoay.toolbelt.adb.AdbEndpoint

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 全窗口视图检查内核守护（fake runner 注入，零真子进程）：
 * - 通道纪律逐字节断言：dump（设备侧落 ZIP + echo 退出码）/ pull / rm 清理三命令
 *   全文经 [AdbCommandRewriter] 注入 adb 路径与 `-s <serial>`，payload 按本机 shell
 *   语义单引号包裹；
 * - 🔴 通道侦察永久证据：SimpleShell 读 stdout 是 UTF-8 解码的 `readText()`，ZIP
 *   二进制经该文本通道不可逆失真（替换式纠错）——本测试把失真钉成常驻事实，通道
 *   决策（二进制恒走 adb pull 文件通道）以此为准；
 * - 成功路径：fake runner 模拟 pull（向命令里的本地路径写 fixture ZIP）走通全
 *   编排 → 解码窗口清单；
 * - 失败透传矩阵：dump 退出码行非零 / adb 退出非零 / pull 失败 / 产物非 ZIP 魔数
 *   （dump 谎报防御——错误文本入文件在此现形）/ ZIP 零可解码条目，均显式人话失败；
 * - 设备侧零残留：成败路径 finally 清理恒发 rm -f；
 * - 空串号零 spawn 人话拒绝。
 */
class DeviceWindowViewsInspectorTest {

    /** runner fake：记录命令全文（通道纪律断言锚），按命令内容路由回执 */
    private class FakeGateway(private val respond: suspend (String) -> Result<ShellResult>) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return respond(command)
        }
    }

    private val fixtureZip = windowViewsZip(
        "1151e7a StatusBar" to DeviceWindowViewsFixtures.statusBarStream(),
    )

    /** 模拟 adb pull：从命令解析本地目标路径并写入 [payload]（fake 层仿真文件通道） */
    private fun pullResponder(payload: ByteArray): suspend (String) -> Result<ShellResult> = { command ->
        when {
            // pull 命令重写后形态 `adb -s S pull '<dev>' '<local>'`（adb 与 pull 不相邻）
            command.contains(" pull ") -> {
                val localPath = command.trim().substringAfterLast(" ").trim('\'', '"')
                File(localPath).writeBytes(payload)
                Result.success(ShellResult(0, "1 file pulled, 0 skipped. 12.3 MB/s (${payload.size} bytes in 0.000s)", ""))
            }

            command.contains("rm -f") -> Result.success(ShellResult(0, "", ""))

            else -> Result.success(ShellResult(0, "${DeviceWindowViewsInspector.DUMP_RC_PREFIX}0", ""))
        }
    }

    private fun inspector(runner: AdbProcessRunner, adbPath: String = "") =
        DeviceWindowViewsInspector(runner = runner, endpointReader = { AdbEndpoint(adbPath) })

    // ── 通道纪律逐字节断言 ──

    @Test
    fun `dump command redirects to device temp and echoes exit code`() {
        assertEquals(
            "adb -s SERIAL shell 'cmd window dump-visible-window-views > " +
                "/data/local/tmp/toolbelt_wvviews.zip; echo TWV_DUMP_RC:\$?'",
            DeviceWindowViewsInspector.dumpCommand(adbPath = "", serial = "SERIAL"),
        )
    }

    @Test
    fun `dump command injects custom adb path`() {
        assertEquals(
            "'/opt/my adb' -s S shell 'cmd window dump-visible-window-views > " +
                "/data/local/tmp/toolbelt_wvviews.zip; echo TWV_DUMP_RC:\$?'",
            DeviceWindowViewsInspector.dumpCommand(adbPath = "/opt/my adb", serial = "S"),
        )
    }

    @Test
    fun `pull and cleanup commands follow channel discipline`() {
        assertEquals(
            "adb -s S pull '/data/local/tmp/toolbelt_wvviews.zip' '/tmp/shot.zip'",
            DeviceWindowViewsInspector.pullCommand(
                DeviceWindowViewsInspector.DEVICE_ZIP_PATH,
                "/tmp/shot.zip",
                adbPath = "",
                serial = "S",
            ),
        )
        assertEquals(
            "adb -s S shell 'rm -f /data/local/tmp/toolbelt_wvviews.zip'",
            DeviceWindowViewsInspector.cleanupCommand(adbPath = "", serial = "S"),
        )
    }

    @Test
    fun `dumpWindowViews sends exact command chain through runner`() = runTest {
        val runner = FakeGateway(pullResponder(fixtureZip))
        inspector(runner, adbPath = "/opt/adb").dumpWindowViews("SERIAL").getOrThrow()
        assertEquals(3, runner.commands.size)
        assertEquals(DeviceWindowViewsInspector.dumpCommand("/opt/adb", "SERIAL"), runner.commands[0])
        val pull = runner.commands[1]
        assertTrue(
            pull.startsWith("/opt/adb -s SERIAL pull '/data/local/tmp/toolbelt_wvviews.zip' "),
            "pull 命令经重写器注入 adb 路径与 -s：$pull",
        )
        assertEquals(DeviceWindowViewsInspector.cleanupCommand("/opt/adb", "SERIAL"), runner.commands[2], "finally 清理恒发")
    }

    // ── 🔴 通道侦察永久证据 ──

    @Test
    fun `reconnaissance - text channel utf8 decode corrupts zip bytes`() {
        // SimpleShell 读 stdout 语义 = bufferedReader(UTF-8).readText() ≡ String(bytes, UTF_8)
        //（替换式纠错：非法 UTF-8 序列 → U+FFFD，不可逆）。ZIP 二进制含压缩流与
        // CRC 的任意字节，经文本通道往返必有损——dvwv 产物因此恒走 adb pull 文件
        // 通道（sync 协议，不经 shell 文本），本测试把该结论钉成常驻事实。
        val zip = fixtureZip
        assertTrue(zip.size > 64, "前置：fixture 为真实 ZIP（非退化空壳）")
        val text = String(zip, Charsets.UTF_8)
        val roundTrip = text.toByteArray(Charsets.UTF_8)
        assertTrue(
            !roundTrip.contentEquals(zip),
            "ZIP 二进制经文本通道应有损（通道侦察结论：SimpleShell 文本 stdout 不可承载 ZIP）",
        )
    }

    // ── 成功路径 ──

    @Test
    fun `success decodes window entries through full orchestration`() = runTest {
        val runner = FakeGateway(pullResponder(fixtureZip))
        val dump = inspector(runner).dumpWindowViews("S").getOrThrow()
        assertEquals(1, dump.windows.size)
        val window = dump.windows.single()
        assertEquals("1151e7a", window.windowHash)
        assertEquals("StatusBar", window.title)
        assertEquals("android.widget.RelativeLayout", window.root.className)
    }

    // ── 失败透传矩阵 ──

    @Test
    fun `missing rc line falls through to product check and fails without zip`() = runTest {
        // 退出码行缺席（ROM shell 变体）不预判：交给产物判型——空文件非 ZIP 在此现形
        val runner = FakeGateway {
            Result.success(ShellResult(0, "Error: unknown command 'dump-visible-window-views'\n", ""))
        }
        val result = inspector(runner).dumpWindowViews("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("未取回窗口视图 ZIP"), message)
        assertTrue(message.contains("dump-visible-window-views"), "失败人话指明命令形态（降级判定锚）：$message")
        assertEquals(3, runner.commands.size, "pull 已尝试、finally 清理恒发（零残留）")
    }

    @Test
    fun `explicit non zero rc line fails without pull`() = runTest {
        val runner = FakeGateway {
            Result.success(ShellResult(0, "${DeviceWindowViewsInspector.DUMP_RC_PREFIX}1\nError: 权限不足\n", ""))
        }
        val result = inspector(runner).dumpWindowViews("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("设备侧退出码 1"), message)
        assertTrue(message.contains("权限不足"))
        assertTrue(runner.commands.none { it.contains("adb pull") }, "rc 非零不进入 pull 步")
    }

    @Test
    fun `non zero adb exit fails with exit code and device text`() = runTest {
        val runner = FakeGateway { Result.success(ShellResult(1, "", "adb: device offline")) }
        val result = inspector(runner).dumpWindowViews("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("adb 退出码 1"))
        assertTrue(message.contains("adb: device offline"))
    }

    @Test
    fun `pull failure fails with human receipt`() = runTest {
        val runner = FakeGateway { command ->
            if (command.contains(" pull ")) {
                Result.success(ShellResult(1, "", "adb: no such file or directory"))
            } else {
                Result.success(ShellResult(0, "", ""))
            }
        }
        val result = inspector(runner).dumpWindowViews("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("adb 退出码 1") && message.contains("no such file"), message)
    }

    @Test
    fun `non zip product fails with dump lie defense receipt`() = runTest {
        val runner = FakeGateway(pullResponder("Error: not a zip, just error text".toByteArray()))
        val result = inspector(runner).dumpWindowViews("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("未取回窗口视图 ZIP"), message)
        assertTrue(message.contains("dump-visible-window-views"), "失败人话指明命令形态（降级判定锚）")
    }

    @Test
    fun `zip with zero decodable entries fails explicitly`() = runTest {
        val enc = DeviceWindowViewsFixtures.StreamEncoder()
        enc.intProperty("window:left", 0)
        enc.intProperty("window:top", 0)
        enc.beginObject("android.view.View", "h")
        enc.rawProperty("rogue", 'Q', byteArrayOf(1))
        enc.endObject()
        enc.endStream()
        val runner = FakeGateway(pullResponder(windowViewsZip("h bad" to enc.toByteArray())))
        val result = inspector(runner).dumpWindowViews("S")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("无可解码窗口条目"))
    }

    @Test
    fun `runner process failure fails with human receipt`() = runTest {
        val runner = FakeGateway { Result.failure(IllegalStateException("spawn 失败")) }
        val result = inspector(runner).dumpWindowViews("S")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message.contains("adb 执行失败"))
        assertTrue(message.contains("spawn 失败"))
    }

    @Test
    fun `local temp file is cleaned after decode`() = runTest {
        val runner = FakeGateway(pullResponder(fixtureZip))
        inspector(runner).dumpWindowViews("S").getOrThrow()
        val leftovers = File(System.getProperty("java.io.tmpdir")).listFiles { f ->
            f.name.startsWith("toolbelt-wvviews-")
        } ?: emptyArray()
        assertTrue(leftovers.isEmpty(), "本地临时 ZIP 应随编排清理，残留 ${leftovers.map { it.name }}")
    }

    // ── 零 spawn 门控 ──

    @Test
    fun `empty serial rejects without spawn`() = runTest {
        val runner = FakeGateway(pullResponder(fixtureZip))
        val result = inspector(runner).dumpWindowViews("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("设备串号为空"))
        assertEquals(0, runner.commands.size, "零 spawn：runner 未收到任何命令")
    }

    // ── 解析辅助 ──

    @Test
    fun `dump rc line parsing tolerates cr and missing line`() {
        val inspector = inspector(FakeGateway { Result.success(ShellResult(0, "", "")) })
        assertEquals(0, inspector.parseDumpExitCode("TWV_DUMP_RC:0"))
        assertEquals(1, inspector.parseDumpExitCode("progress line\r\nTWV_DUMP_RC:1\r\n"))
        assertEquals(null, inspector.parseDumpExitCode("no rc line here"))
    }
}
