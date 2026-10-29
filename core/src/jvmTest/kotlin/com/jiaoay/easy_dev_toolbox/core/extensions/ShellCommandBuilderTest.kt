// 🔴 2026-09-17 审计修复配套回归（报告 §四「可执行文件解析」①③ + §四-3.4）：
// - wrap 的 shell 路径多候选探测（修复 /bin/zsh 硬编码——发行版 shell 装在 /usr/bin 时启动失败）
// - 登录 shell PATH 探测与注入（修复 -l 不读 .zshrc ⇒ GUI 启动 node/npm 127）
//
// 🔴 验收纪律：涉及 object 状态（shellType / probedPath）——每个用例独立、断言只读公开面；
// resolvedPath 的真探测只在类 Unix 真机（本套件有 /bin/sh）下跑，Windows 直接跳过。
package com.jiaoay.easy_dev_toolbox.core.extensions

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShellCommandBuilderTest {

    private val isUnix: Boolean get() = !ShellCommandBuilder.isWindows

    @Test
    fun `wrap unix 返回登录 shell 形态`() {
        if (!isUnix) return
        val wrapped = ShellCommandBuilder.wrap("echo hi")
        // 登录 shell 三段式：<shell> -l -c <command>（候选探测后不再是裸字面 /bin/<name>）
        assertEquals(4, wrapped.size)
        assertTrue(wrapped[1] == "-l" && wrapped[2] == "-c", "应为登录 shell 形态：${wrapped.toList()}")
        assertTrue(File(wrapped[0]).canExecute(), "wrap 的 shell 路径必须可执行（修复前硬编码 /bin/* 可能不存在）：${wrapped[0]}")
        assertEquals("echo hi", wrapped[3])
    }

    @Test
    fun `wrap 产生的新 shell 路径可真实启动`() {
        if (!isUnix) return
        val wrapped = ShellCommandBuilder.wrap("printf ok")
        val pb = ProcessBuilder(*wrapped)
        ShellCommandBuilder.applyShellEnv(pb)
        val proc = pb.start()
        try {
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
            assertEquals(0, proc.waitFor())
            assertEquals("ok", out.trim())
        } finally {
            runCatching { proc.destroyForcibly() }
        }
    }

    @Test
    fun `resolvedPath 探测在真机登录 shell 下非空`() {
        if (!isUnix) return
        val path = ShellCommandBuilder.resolvedPath
        assertNotNull(path, "本机登录 shell 探测应成功（zsh/bash/sh 均已安装的测试机）")
        // 防污染双滤的契约：每个条目都是已存在目录
        path!!.split(File.pathSeparatorChar).forEach { entry ->
            assertTrue(File(entry).isDirectory, "探测 PATH 的条目应为已存在目录：$entry")
        }
        // 探测值必须包含系统目录 /usr/bin（登录 shell 的 PATH 不会缺失它）
        assertTrue(
            path.split(File.pathSeparatorChar).any { File(it).canonicalPath == File("/usr/bin").canonicalPath },
            "探测 PATH 应包含 /usr/bin",
        )
    }

    @Test
    fun `applyShellEnv 注入探测 PATH 后子进程可见`() {
        if (!isUnix) return
        val pb = ProcessBuilder("/bin/sh", "-c", "printf %s \"\$PATH\"")
        ShellCommandBuilder.applyShellEnv(pb)
        val proc = pb.start()
        try {
            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
            assertEquals(0, proc.waitFor())
            val expected = ShellCommandBuilder.resolvedPath
            if (expected != null) {
                // sh -c 不读 rc，子进程 PATH 应恰等于注入值
                assertEquals(expected, out)
            }
        } finally {
            runCatching { proc.destroyForcibly() }
        }
    }

    /**
     * charsetFor 的 adb 判定是纯函数、与操作系统无关——本机（Linux）即可验证判定分支，
     * 只有「Windows 上 processCharset 为 GBK 时 adb 仍解 UTF-8」这一端到端效果需 Windows
     * 真机 hexdump 验收。
     */
    @Test
    fun `charsetFor adb 命令族恒 UTF-8`() {
        listOf(
            "adb logcat",
            "  adb shell getprop",
            "adb",
            "/opt/platform-tools/adb logcat -v threadtime",
            "C:\\platform-tools\\adb.exe -s ABC123 logcat",
            "\"C:\\Program Files\\platform-tools\\adb.exe\" -s ABC123 logcat",
        ).forEach { command ->
            assertEquals(Charsets.UTF_8, ShellCommandBuilder.charsetFor(command), "应判为 adb 命令族：$command")
        }
    }

    @Test
    fun `charsetFor 非 adb 命令沿用 processCharset`() {
        listOf(
            "dir",
            "git status",
            "echo adb logcat",
            "adbshell getprop",
            "/opt/tools/adb-wrapper logcat",
        ).forEach { command ->
            assertEquals(
                ShellCommandBuilder.processCharset,
                ShellCommandBuilder.charsetFor(command),
                "不应判为 adb 命令族：$command",
            )
        }
    }
}
