package com.jiaoay.easy_dev_toolbox.core.shelltools

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AdbCommandRewriter 规则回归（design 决策 1）：
 * 词边界判定、adbPath 替换（含空白路径单引号包裹）、`-s` 注入（显式指定优先）、
 * 非 adb 命令原样返回、设备检测（serial=null）仅替换路径。
 */
class AdbCommandRewriterTest {

    // ── 1. 普通命令注入 ──

    @Test
    fun `normal adb command injects serial after executable`() {
        val rewritten = AdbCommandRewriter.rewrite("adb shell pm clear com.example", adbPath = "", serial = "S1")
        assertEquals("adb -s S1 shell pm clear com.example", rewritten)
    }

    // ── 2. 链式逐条注入（每条命令独立重写，等价链式执行路径） ──

    @Test
    fun `chain commands are rewritten one by one`() {
        val commands = listOf(
            "adb devices",
            "adb shell pm clear com.example",
            "echo done",
        )
        val rewritten = commands.map { AdbCommandRewriter.rewrite(it, adbPath = "", serial = "S1") }
        assertEquals(
            listOf(
                "adb -s S1 devices",
                "adb -s S1 shell pm clear com.example",
                "echo done",
            ),
            rewritten,
        )
    }

    // ── 3. 已有 -s 不重复注入 ──

    @Test
    fun `explicit serial flag is not duplicated`() {
        val command = "adb -s S2 shell getprop ro.build.version"
        val rewritten = AdbCommandRewriter.rewrite(command, adbPath = "", serial = "S1")
        assertEquals(command, rewritten)
    }

    // ── 4. 非 adb 命令不变 ──

    @Test
    fun `non adb command is returned as is`() {
        val command = "ls -la /tmp"
        val rewritten = AdbCommandRewriter.rewrite(command, adbPath = "/opt/android/adb", serial = "S1")
        assertEquals(command, rewritten)
    }

    // ── 5. adbxxx 词边界不变 ──

    @Test
    fun `command starting with adb prefix word is not rewritten`() {
        val command = "adbshell getprop"
        val rewritten = AdbCommandRewriter.rewrite(command, adbPath = "", serial = "S1")
        assertEquals(command, rewritten)
    }

    // ── 6. 空路径保持 adb（serial 仍注入；serial=null 即设备检测路径时仅原样） ──

    @Test
    fun `empty adb path keeps adb executable`() {
        assertEquals(
            "adb -s S1 devices",
            AdbCommandRewriter.rewrite("adb devices", adbPath = "", serial = "S1"),
        )
        // 设备检测命令：serial = null → 只处理路径，保持原命令
        assertEquals(
            "adb devices -l",
            AdbCommandRewriter.rewrite("adb devices -l", adbPath = "", serial = null),
        )
    }

    // ── 7. 自定义路径替换 ──

    @Test
    fun `custom adb path replaces leading adb`() {
        val rewritten = AdbCommandRewriter.rewrite("adb version", adbPath = "/opt/android/platform-tools/adb", serial = null)
        assertEquals("/opt/android/platform-tools/adb version", rewritten)
    }

    // ── 8. 含空格路径引号包裹 ──

    @Test
    fun `adb path containing whitespace is single quoted`() {
        val rewritten = AdbCommandRewriter.rewrite("adb devices -l", adbPath = "/opt/my tools/adb", serial = null)
        assertEquals("'/opt/my tools/adb' devices -l", rewritten)
    }

    // ── 边界补充 ──

    @Test
    fun `bare adb command with serial appends serial flag`() {
        assertEquals(
            "/opt/adb -s S1",
            AdbCommandRewriter.rewrite("adb", adbPath = "/opt/adb", serial = "S1"),
        )
    }

    @Test
    fun `bare adb command without serial replaces executable only`() {
        assertEquals(
            "/opt/adb",
            AdbCommandRewriter.rewrite("adb", adbPath = "/opt/adb", serial = null),
        )
    }

    @Test
    fun `blank adb path string falls back to adb`() {
        val rewritten = AdbCommandRewriter.rewrite("adb devices", adbPath = "   ", serial = null)
        assertEquals("adb devices", rewritten)
    }

    @Test
    fun `leading and trailing whitespace is trimmed before rewrite`() {
        val rewritten = AdbCommandRewriter.rewrite("  adb devices  ", adbPath = "", serial = "S1")
        assertEquals("adb -s S1 devices", rewritten)
    }

    @Test
    fun `serial in device detection style null keeps devices command intact`() {
        // refreshDevices 使用场景：adbPath 替换 + 不注入 serial
        val rewritten = AdbCommandRewriter.rewrite("adb devices -l", adbPath = "/usr/local/bin/adb", serial = null)
        assertEquals("/usr/local/bin/adb devices -l", rewritten)
    }

    // ── Windows 变体（显式注入 windows = true，生产路径经 ShellCommandBuilder.isWindows 驱动） ──

    @Test
    fun `windows adb path containing whitespace is double quoted`() {
        val rewritten = AdbCommandRewriter.rewrite(
            "adb devices -l",
            adbPath = "C:\\Program Files\\platform-tools\\adb.exe",
            serial = null,
            windows = true,
        )
        assertEquals("\"C:\\Program Files\\platform-tools\\adb.exe\" devices -l", rewritten)
    }

    @Test
    fun `windows path without whitespace stays unquoted`() {
        val rewritten = AdbCommandRewriter.rewrite(
            "adb version",
            adbPath = "C:\\platform-tools\\adb.exe",
            serial = null,
            windows = true,
        )
        assertEquals("C:\\platform-tools\\adb.exe version", rewritten)
    }

    @Test
    fun `windows flag does not affect non adb command`() {
        val command = "dir C:\\tools"
        val rewritten = AdbCommandRewriter.rewrite(command, adbPath = "C:\\tools\\adb.exe", serial = null, windows = true)
        assertEquals(command, rewritten)
    }
}
