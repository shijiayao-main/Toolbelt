package com.jiaoay.toolbelt.adb

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 命令重写规则：词边界判定、路径替换（含空白路径按执行 shell 引号）、`-s` 注入
 * （显式优先）、非 adb 命令原样返回。
 *
 * 平台由入参注入，测试不依赖运行测试的机器——同一套期望值在三个平台跑出同样结果。
 */
class AdbCommandRewriterTest {

    private val unix = AdbPlatform.LINUX
    private val windows = AdbPlatform.WINDOWS

    private fun rewrite(
        command: String,
        path: String = "",
        serial: String? = null,
        platform: AdbPlatform? = unix,
    ) = AdbCommandRewriter.rewrite(command, path, serial, platform)

    // ── 串号注入 ──

    @Test
    fun `normal adb command injects serial after executable`() {
        assertEquals(
            "adb -s S1 shell pm clear com.example",
            rewrite("adb shell pm clear com.example", serial = "S1"),
        )
    }

    @Test
    fun `chain commands are rewritten one by one`() {
        val commands = listOf("adb devices", "adb shell pm clear com.example", "echo done")
        assertEquals(
            listOf("adb -s S1 devices", "adb -s S1 shell pm clear com.example", "echo done"),
            commands.map { rewrite(it, serial = "S1") },
        )
    }

    @Test
    fun `explicit serial flag is not duplicated`() {
        val command = "adb -s S2 shell getprop ro.build.version"
        assertEquals(command, rewrite(command, serial = "S1"))
    }

    @Test
    fun `null serial leaves probe commands untouched`() {
        assertEquals("/usr/local/bin/adb devices -l", rewrite("adb devices -l", "/usr/local/bin/adb"))
    }

    // ── 非 adb 命令 ──

    @Test
    fun `non adb command is returned as is`() {
        val command = "ls -la /tmp"
        assertEquals(command, rewrite(command, "/opt/android/adb", "S1"))
    }

    @Test
    fun `command starting with adb prefix word is not rewritten`() {
        val command = "adbshell getprop"
        assertEquals(command, rewrite(command, serial = "S1"))
    }

    // ── 路径替换与引号语义 ──

    @Test
    fun `empty path keeps literal adb executable`() {
        assertEquals("adb -s S1 devices", rewrite("adb devices", "", "S1"))
        assertEquals("adb devices -l", rewrite("adb devices -l", "", null))
    }

    @Test
    fun `blank path falls back to literal adb`() {
        assertEquals("adb devices", rewrite("adb devices", "   "))
    }

    @Test
    fun `custom path replaces leading adb`() {
        assertEquals(
            "/opt/android/platform-tools/adb version",
            rewrite("adb version", "/opt/android/platform-tools/adb"),
        )
    }

    @Test
    fun `unix path with whitespace is single quoted`() {
        // 经登录 shell 执行时单引号才成立；cmd.exe 不解析单引号
        assertEquals("'/opt/my tools/adb' devices -l", rewrite("adb devices -l", "/opt/my tools/adb"))
    }

    @Test
    fun `windows path with whitespace is double quoted`() {
        assertEquals(
            "\"C:\\Program Files\\platform-tools\\adb.exe\" devices -l",
            rewrite("adb devices -l", "C:\\Program Files\\platform-tools\\adb.exe", platform = windows),
        )
    }

    @Test
    fun `windows path without whitespace stays unquoted`() {
        assertEquals(
            "C:\\platform-tools\\adb.exe version",
            rewrite("adb version", "C:\\platform-tools\\adb.exe", platform = windows),
        )
    }

    @Test
    fun `unknown platform falls back to unix quoting`() {
        assertEquals("'/opt/my tools/adb' devices", rewrite("adb devices", "/opt/my tools/adb", platform = null))
    }

    // ── 裸 adb 与空白 ──

    @Test
    fun `bare adb command with serial appends serial flag`() {
        assertEquals("/opt/adb -s S1", rewrite("adb", "/opt/adb", "S1"))
    }

    @Test
    fun `bare adb command without serial replaces executable only`() {
        assertEquals("/opt/adb", rewrite("adb", "/opt/adb"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed before rewrite`() {
        assertEquals("adb -s S1 devices", rewrite("  adb devices  ", "", "S1"))
    }

    // ── 端点重载 ──

    @Test
    fun `endpoint overload uses its executable path`() {
        val endpoint = AdbEndpoint("/home/u/.toolbelt/adb/v37.0.1/adb", AdbEndpoint.BUNDLED_SERVER_PORT)
        assertEquals(
            "/home/u/.toolbelt/adb/v37.0.1/adb -s S1 shell id",
            AdbCommandRewriter.rewrite("adb -s S1 shell id", endpoint, "S1", unix),
        )
    }

    @Test
    fun `dedicated server port never leaks into command text`() {
        // 端口经执行环境变量注入，命令串与是否内置 adb 无关——命令可原样回放
        val bundled = AdbEndpoint("/opt/adb", AdbEndpoint.BUNDLED_SERVER_PORT)
        val plain = AdbEndpoint("/opt/adb", null)
        assertEquals(
            AdbCommandRewriter.rewrite("adb devices -l", plain, null, unix),
            AdbCommandRewriter.rewrite("adb devices -l", bundled, null, unix),
        )
    }

    // ── isAdbCommand ──

    @Test
    fun `isAdbCommand honours word boundary`() {
        assertEquals(true, AdbCommandRewriter.isAdbCommand("adb devices"))
        assertEquals(true, AdbCommandRewriter.isAdbCommand("  adb  "))
        assertEquals(true, AdbCommandRewriter.isAdbCommand("adb"))
        assertEquals(false, AdbCommandRewriter.isAdbCommand("adbshell x"))
        assertEquals(false, AdbCommandRewriter.isAdbCommand("echo adb devices"))
    }
}
