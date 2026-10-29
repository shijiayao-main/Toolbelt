package com.jiaoay.lark.bridge.exec

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [BC §4.5 / §4.6 · BC-097/098/208..211] LaunchCommand argv 命令构造纯函数断言。
 * 🔴 BC 校正：Windows 包装是 `"/c"`（带斜杠），不是旧报告 §2.5 笔误的 `"c"`。
 * 🔴 这是 argv 程序调用语义，非 shell 命令行语义（不经 ShellCommandBuilder.wrap / cmd.exe /c 或 zsh -l 包装整条命令行）。
 */
class LaunchCommandTest {

    // ── launchCommand（脚本拉起，BC §4.5，源 PermissionServerLauncher.kt:161-162）──

    @Test
    fun `launchCommand - Windows 用 cmd exe d c 包装脚本（斜杠 c 非裸 c）`() {
        val cmd = LaunchCommand.launchCommand("C:\\scripts\\perm.cmd", windows = true)
        assertEquals(listOf("cmd.exe", "/d", "/c", "C:\\scripts\\perm.cmd"), cmd)
        assertEquals("/c", cmd[2], "BC 校正：必须是 /c 不是 c")
    }

    @Test
    fun `launchCommand - 非 Windows 用 bin sh 拉起脚本`() {
        assertEquals(
            listOf("/bin/sh", "/tmp/perm.sh"),
            LaunchCommand.launchCommand("/tmp/perm.sh", windows = false),
        )
    }

    // ── spawnCommand（可执行文件 spawn，BC §4.6，源 AgentExecutor.kt:550）──

    @Test
    fun `spawnCommand - executableFile 为 null 回退裸可执行名不包装`() {
        val cmd = LaunchCommand.spawnCommand(
            executableFile = null,
            args = listOf("--print", "--output-format", "stream-json"),
            isWindows = false,
            fallbackExecutable = "claude",
        )
        assertEquals(
            listOf("claude", "--print", "--output-format", "stream-json"),
            cmd,
        )
    }

    @Test
    fun `spawnCommand - Windows 点 cmd 扩展名包装（绝对路径 + args）`() {
        val exe = File("C:\\tools\\claude.cmd")
        val cmd = LaunchCommand.spawnCommand(exe, listOf("-p", "hi"), isWindows = true, fallbackExecutable = "claude")
        assertEquals(listOf("cmd.exe", "/d", "/c", exe.absolutePath, "-p", "hi"), cmd)
    }

    @Test
    fun `spawnCommand - Windows 点 bat 扩展名包装`() {
        val exe = File("C:\\tools\\claude.bat")
        val cmd = LaunchCommand.spawnCommand(exe, emptyList(), isWindows = true, fallbackExecutable = "claude")
        assertEquals(listOf("cmd.exe", "/d", "/c", exe.absolutePath), cmd)
    }

    @Test
    fun `spawnCommand - Windows 大写 CMD 扩展名同样命中（大小写不敏感）`() {
        val exe = File("C:\\tools\\claude.CMD")
        val cmd = LaunchCommand.spawnCommand(exe, listOf("x"), isWindows = true, fallbackExecutable = "claude")
        assertEquals("cmd.exe", cmd[0])
        assertEquals("/c", cmd[2])
        assertEquals(exe.absolutePath, cmd[3])
    }

    @Test
    fun `spawnCommand - Windows 点 exe 不包装（绝对路径直接执行）`() {
        val exe = File("C:\\tools\\claude.exe")
        val cmd = LaunchCommand.spawnCommand(exe, listOf("--version"), isWindows = true, fallbackExecutable = "claude")
        assertEquals(listOf(exe.absolutePath, "--version"), cmd)
    }

    @Test
    fun `spawnCommand - Unix 平台点 cmd 也不包装（非 Windows 不加 cmd 前缀）`() {
        val exe = File("/usr/local/bin/claude.cmd")
        val cmd = LaunchCommand.spawnCommand(exe, listOf("-p"), isWindows = false, fallbackExecutable = "claude")
        assertEquals(listOf(exe.absolutePath, "-p"), cmd)
    }

    @Test
    fun `spawnCommand - Unix 普通可执行文件绝对路径 + args 原样`() {
        val exe = File("/usr/local/bin/claude")
        val cmd = LaunchCommand.spawnCommand(exe, listOf("--print"), isWindows = false, fallbackExecutable = "claude")
        assertEquals(listOf("/usr/local/bin/claude", "--print"), cmd)
    }

    // ==================== 🔴 2026-09-17 审计修复回归（W2：cmd 引号转义） ====================

    /**
     * W2：JDK ProcessImpl 对 .cmd/.bat 的 VERIFICATION_CMD_BAT 校验遇**裸内嵌双引号**直接抛
     * IllegalArgumentException（穿透 BackendAgentRunner 双 catch ⇒ 裸 task_error）。
     * 修复 = .cmd/.bat 包装分支 args 内嵌 `"` 转义为 `\"`；.exe 与 Unix 不受影响（原样）。
     */
    @Test
    fun `spawnCommand - Windows 点 cmd 引号参数转义为反斜杠引号`() {
        val exe = File("""C:\npm\claude.cmd""")
        val cmd = LaunchCommand.spawnCommand(
            exe,
            listOf("-p", """他说"hi""""),
            isWindows = true,
            fallbackExecutable = "claude",
        )
        // 内嵌引号被转义：`"` → `\"`
        assertEquals("""他说\"hi\"""", cmd[5], "内嵌双引号必须转义（JDK VERIFICATION_CMD_BAT 会拒裸引号）")
        assertEquals("-p", cmd[4])
    }

    @Test
    fun `spawnCommand - Windows 点 exe 引号参数原样不转义`() {
        val exe = File("""C:\npm\claude.exe""")
        val cmd = LaunchCommand.spawnCommand(
            exe,
            listOf("""纯 argv 语义"引号"原样"""),
            isWindows = true,
            fallbackExecutable = "claude",
        )
        // .exe 不经 cmd.exe，argv 直传——引号原样保留（JDK 的 EXE 校验不拒内嵌引号）
        assertEquals("""纯 argv 语义"引号"原样""", cmd[1])
    }

    @Test
    fun `spawnCommand - Unix 引号参数原样不转义`() {
        val exe = File("/usr/local/bin/claude")
        val cmd = LaunchCommand.spawnCommand(
            exe,
            listOf("""他说"hi""""),
            isWindows = false,
            fallbackExecutable = "claude",
        )
        assertEquals(listOf("/usr/local/bin/claude", """他说"hi""""), cmd)
    }

    /** 转义点前的尾反斜杠加倍（`foo\"` 会被 cmd 误解析为引号继续符）。 */
    @Test
    fun `spawnCommand - cmd 分支转义点前尾反斜杠加倍`() {
        val exe = File("""C:\npm\claude.cmd""")
        val cmd = LaunchCommand.spawnCommand(
            exe,
            listOf("""path\"quote"""),
            isWindows = true,
            fallbackExecutable = "claude",
        )
        // `\"` 前的反斜杠加倍：path\ + " → path\\\"quote
        assertEquals("""path\\\"quote""", cmd[4])
    }

    /** 末尾奇数个反斜杠会被 JDK 拼外层引号时误吞——加倍防歧义。 */
    @Test
    fun `spawnCommand - cmd 分支末尾奇数反斜杠加倍`() {
        val exe = File("""C:\npm\claude.cmd""")
        // 输入尾部一个 \：JDK 会给参数包外层引号 "trailing\"，末尾 \" 被解析为转义引号 ⇒ 裸断。
        // 修复：奇数尾反斜杠加倍为 \\，外层引号安全闭合。
        val cmd = LaunchCommand.spawnCommand(
            exe,
            listOf("trailing\\"),   // 参数 = trailing\（单个尾反斜杠）
            isWindows = true,
            fallbackExecutable = "claude",
        )
        assertEquals("trailing\\\\", cmd[4], "末尾奇数个反斜杠必须加倍（防外层引号误吞）")
    }

    /** 无引号无尾反斜杠的参数零改动（快路径）。 */
    @Test
    fun `spawnCommand - cmd 分支无引号参数零改动`() {
        val exe = File("""C:\npm\claude.cmd""")
        val cmd = LaunchCommand.spawnCommand(
            exe,
            listOf("--print", "普通参数"),
            isWindows = true,
            fallbackExecutable = "claude",
        )
        assertEquals(listOf("--print", "普通参数"), cmd.drop(4))
    }
}
