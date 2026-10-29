package com.jiaoay.toolbelt.device

import com.jiaoay.toolbelt.adb.AdbEndpoint

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 设备文件浏览器内核守护（fake runner 注入，零真子进程）：
 * - 通道纪律逐字节断言：命令全文经 AdbCommandRewriter 注入 adb 路径与
 *   `-s <serial>`，payload 含设备侧单引号路径（Unix 单引号零展开配方——路径
 *   含空格/`$` 字面化，内嵌单引号 `'\''` 转义）；
 * - `ls -la` → `ls -l` 回退链：仅退出非零触发回退；退出 0 格式不符不重试；
 * - 设备侧错误原文透传（`ls: <path>: No such file or directory` 不翻译不吞）；
 * - 空路径/空串号零 spawn 人话拒绝；adb 进程真失败落人话回执；
 * - stat 备面：逐字节命令断言 + 机器可读单行解析 + 缺席条目错误透传。
 */
class DeviceFileBrowserTest {

    /** runner fake：记录命令全文（通道纪律断言锚），按命令内容路由回执 */
    private class FakeGateway(private val respond: suspend (String) -> ShellResult) : AdbProcessRunner {
        val commands = mutableListOf<String>()
        override suspend fun execute(command: String): Result<ShellResult> {
            commands += command
            return Result.success(respond(command))
        }
    }

    /** 真机（toybox 0.8.4）八列长格式实测基线样例 */
    private val lsFixture = """
        total 24
        drwxrwx--x 2 root root 4096 2026-10-11 14:23 Movies
        -rw-rw---- 1 root root 512 2026-10-11 08:01 build.log
        lrwxrwxrwx 1 root root 12 2026-09-30 18:00 sdcard -> /storage/emulated/0
    """.trimIndent()

    private fun browser(runner: AdbProcessRunner, adbPath: String = "") =
        DeviceFileBrowser(runner = runner, endpointReader = { AdbEndpoint(adbPath) })

    @Test
    fun `listDirectory builds rewritten command with device-side quoted path`() = runTest {
        val runner = FakeGateway { ShellResult(0, lsFixture, "") }
        val result = browser(runner).listDirectory("/sdcard", "S1")

        assertEquals(
            listOf("""adb -s S1 shell 'ls -la '\''/sdcard'\'''"""),
            runner.commands,
            "命令须为 adb -s <serial> shell + 单引号包裹 payload（payload 内路径自带设备侧单引号，内嵌单引号 '\\'' 转义）",
        )
        val entries = result.getOrThrow()
        assertEquals(listOf("Movies", "build.log", "sdcard"), entries.map { it.name })
        assertEquals(DeviceFileType.SYMLINK, entries[2].type)
    }

    @Test
    fun `listDirectory rewrites custom adb path and keeps space-containing path literal`() = runTest {
        val runner = FakeGateway { ShellResult(0, lsFixture, "") }
        val result = browser(runner, adbPath = "/opt/my adb").listDirectory("/sdcard/my dir", "S1")

        assertEquals(
            listOf("""'/opt/my adb' -s S1 shell 'ls -la '\''/sdcard/my dir'\'''"""),
            runner.commands,
            "路径含空格必须字面化：设备侧单引号包裹后整体 payload 再经本机 shell 单引号转义包裹",
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `listDirectory falls back to ls -l only when ls -la exits nonzero`() = runTest {
        val runner = FakeGateway { command ->
            if (command.contains("ls -la ")) {
                // 老 ls 不认 -a 组合旗标的形态
                ShellResult(1, "", "ls: invalid option -- 'a'")
            } else {
                ShellResult(0, lsFixture, "")
            }
        }
        val result = browser(runner).listDirectory("/sdcard", "S1")

        assertEquals(
            listOf(
                """adb -s S1 shell 'ls -la '\''/sdcard'\'''""",
                """adb -s S1 shell 'ls -l '\''/sdcard'\'''""",
            ),
            runner.commands,
            "退出非零才回退，回退命令旗标降为 -l",
        )
        assertEquals(listOf("Movies", "build.log", "sdcard"), result.getOrThrow().map { it.name })
    }

    @Test
    fun `listDirectory passes through device error text when both attempts fail`() = runTest {
        val errorText = "ls: /sdcard/nope: No such file or directory"
        val runner = FakeGateway { ShellResult(1, "", errorText) }
        val result = browser(runner).listDirectory("/sdcard/nope", "S1")

        assertEquals(2, runner.commands.size, "主链失败后须执行 -l 回退链一次（共两次 exec 后收敛失败）")
        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("退出码 1"), "失败回执须带退出码（实际：${e.message}）")
        assertTrue(e.message!!.contains(errorText), "设备侧 stderr 原文透传不翻译不吞（实际：${e.message}）")
    }

    @Test
    fun `listDirectory fails without fallback when exit-zero output is unrecognized`() = runTest {
        val runner = FakeGateway { ShellResult(0, "totally unknown output shape", "") }
        val result = browser(runner).listDirectory("/sdcard", "S1")

        assertEquals(1, runner.commands.size, "退出 0 格式不符不触发回退（-l 与 -la 同输出形态，重试无意义）")
        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("格式不符"), "格式不符须显式失败不静默（实际：${e.message}）")
    }

    @Test
    fun `listDirectory rejects empty path or serial without spawn`() = runTest {
        val runner = FakeGateway { ShellResult(0, lsFixture, "") }
        val b = browser(runner)

        val noPath = b.listDirectory("", "S1")
        assertTrue(noPath.isFailure && noPath.exceptionOrNull()!!.message!!.contains("为空"), "空路径零 spawn 人话拒绝")
        val noSerial = b.listDirectory("/sdcard", "")
        assertTrue(noSerial.isFailure && noSerial.exceptionOrNull()!!.message!!.contains("为空"), "空串号零 spawn 人话拒绝")
        assertEquals(emptyList(), runner.commands, "拒绝路径零 exec")
    }

    @Test
    fun `listDirectory lands adb spawn failure as human-readable error`() = runTest {
        val boom = AdbProcessRunner { Result.failure(IllegalStateException("adb not found")) }
        val result = browser(boom).listDirectory("/sdcard", "S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("adb 执行失败"), "进程启动真失败须落人话回执（实际：${e.message}）")
        assertTrue(e.message!!.contains("adb not found"))
    }

    @Test
    fun `statEntry builds byte-exact stat command and parses machine-readable line`() = runTest {
        val runner = FakeGateway { ShellResult(0, "regular file|1234|1728636000|-rw-rw-r--|root|root", "") }
        val result = browser(runner).statEntry("/sdcard/a.txt", "S1")

        assertEquals(
            listOf("""adb -s S1 shell 'stat -c '\''%F|%s|%Y|%A|%U|%G'\'' '\''/sdcard/a.txt'\'''"""),
            runner.commands,
            "stat 格式串含单引号，payload 整体经本机 shell 单引号转义包裹后逐字节成立",
        )
        val stat = result.getOrThrow()
        assertEquals(DeviceFileType.FILE, stat.type)
        assertEquals(1234L, stat.sizeBytes)
        assertEquals(1728636000L, stat.modifiedEpochSeconds)
    }

    @Test
    fun `statEntry dereference flag builds -L command form`() = runTest {
        val runner = FakeGateway { ShellResult(0, "directory|4096|1728636000|drwxrwx--x|root|sdcard", "") }
        browser(runner).statEntry("/sdcard/sdcard", "S1", dereference = true)

        assertEquals(
            listOf("""adb -s S1 shell 'stat -Lc '\''%F|%s|%Y|%A|%U|%G'\'' '\''/sdcard/sdcard'\'''"""),
            runner.commands,
            "解引用档逐字节回归锚：-L 与格式串合并为 -Lc 单旗标头，缺 -L 即 symlink 真值面静默失真",
        )
    }

    @Test
    fun `statEntry passes through device error for absent entry`() = runTest {
        val errorText = "stat: '/sdcard/nope': No such file or directory"
        val runner = FakeGateway { ShellResult(1, "", errorText) }
        val result = browser(runner).statEntry("/sdcard/nope", "S1")

        val e = result.exceptionOrNull()!!
        assertTrue(e.message!!.contains("stat 读取失败"), "缺席条目须失败回执（实际：${e.message}）")
        assertTrue(e.message!!.contains(errorText), "设备侧 stderr 原文透传（实际：${e.message}）")
    }

    @Test
    fun `statEntry rejects empty path or serial without spawn`() = runTest {
        val runner = FakeGateway { ShellResult(0, "regular file|1|1|-rw-r--|root|root", "") }
        val b = browser(runner)

        assertTrue(b.statEntry("", "S1").isFailure, "空路径零 spawn 拒绝")
        assertTrue(b.statEntry("/sdcard/a.txt", "").isFailure, "空串号零 spawn 拒绝")
        assertEquals(emptyList(), runner.commands)
    }
}
