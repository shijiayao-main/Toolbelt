package com.jiaoay.easy_dev_toolbox.core.system

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * argv 纯语义执行原语单测（真实 POSIX 子进程——零外部工具依赖，sh/sleep/echo/false 造态）：
 * 正常退出码与合流输出、退出码非零按数据回执、捕获上界截断、总超时级联终止、
 * 协程取消联动终止、执行面硬防线（黑名单/缺 --user/空 argv/空白元素）、
 * 可执行文件缺失的 IOException 失败回执。
 */
class ArgvProcessRunnerTest {

    @Test
    fun `正常退出返回退出码与合流输出`() = runBlocking {
        val runner = ArgvProcessRunner()
        val outcome = runner.run(listOf("echo", "hello"))
        assertEquals(0, outcome.exitCode)
        assertEquals("hello", outcome.output.trim())
        assertTrue(!outcome.timedOut)
        assertTrue(!outcome.outputTruncated)
    }

    @Test
    fun `stdout 与 stderr 合流捕获`() = runBlocking {
        val runner = ArgvProcessRunner()
        val outcome = runner.run(listOf("sh", "-c", "echo out; echo err 1>&2"))
        assertEquals(0, outcome.exitCode)
        assertTrue("out" in outcome.output, "stdout 须入回执：${outcome.output}")
        assertTrue("err" in outcome.output, "stderr 须合流入回执：${outcome.output}")
    }

    @Test
    fun `非零退出码按数据回执不判失败`() = runBlocking {
        val outcome = ArgvProcessRunner().run(listOf("false"))
        assertEquals(1, outcome.exitCode, "false 的退出码 1 是数据（pgrep 无匹配同款语义）")
        assertTrue(!outcome.timedOut)
    }

    @Test
    fun `捕获上界截断且进程跑完`() = runBlocking {
        val runner = ArgvProcessRunner(captureLimitChars = 100)
        val outcome = runner.run(listOf("sh", "-c", "yes hello | head -200"))
        assertEquals(0, outcome.exitCode, "超捕获上界只是丢弃残量，进程照常跑完")
        assertTrue(outcome.outputTruncated, "超界须标记截断")
        assertTrue(outcome.output.length <= 100, "输出不得超捕获上界（实际 ${outcome.output.length}）")
    }

    @Test
    fun `总超时级联终止并按数据回执`() = runBlocking {
        val runner = ArgvProcessRunner(timeoutMillis = 500)
        val outcome = runner.run(listOf("sleep", "30"))
        assertTrue(outcome.timedOut, "超时须标记 timedOut")
        assertTrue(
            outcome.exitCode != 0,
            "被终止的进程退出码非 0（SIGTERM 143 / SIGKILL 137），实际 ${outcome.exitCode}",
        )
    }

    @Test
    fun `协程取消原样上抛且不吞成失败`() = runBlocking {
        val runner = ArgvProcessRunner()
        val job = launch { runner.run(listOf("sleep", "30")) }
        delay(300)
        job.cancelAndJoin()
        assertTrue(job.isCancelled, "取消须穿透为 CancellationException（run 返回失败回执会吞掉取消）")
    }

    @Test
    fun `执行面硬防线恒拒提权与包管理`() {
        val runner = ArgvProcessRunner()
        // 直接断言策略拒绝理由（policyRejectReason 纯函数，run 惰性不触进程）
        val reason = runner.policyRejectReason(listOf("sudo", "ls"))
        assertTrue(reason != null && "黑名单" in reason, "sudo 须黑名单拒绝：$reason")
        assertTrue(runner.policyRejectReason(listOf("/usr/bin/sudo", "ls")) != null, "路径形态的 sudo 也须按 basename 命中")
        for (exe in listOf("su", "pkexec", "apt", "apt-get", "dpkg", "pip", "snap", "brew")) {
            assertTrue(runner.policyRejectReason(listOf(exe, "x")) != null, "$exe 须黑名单拒绝")
        }
    }

    @Test
    fun `systemctl 与 journalctl 缺 user 恒拒`() {
        val runner = ArgvProcessRunner()
        assertTrue(runner.policyRejectReason(listOf("systemctl", "status", "fcitx5")) != null, "缺 --user 的 systemctl 须拒")
        assertTrue(runner.policyRejectReason(listOf("journalctl", "-n", "50")) != null, "缺 --user 的 journalctl 须拒")
        assertNull(runner.policyRejectReason(listOf("systemctl", "--user", "status", "fcitx5")))
        assertNull(runner.policyRejectReason(listOf("journalctl", "--user", "-n", "50")))
    }

    @Test
    fun `argv 形态校验拒空与空白元素`() {
        val runner = ArgvProcessRunner()
        assertEquals("argv 不能为空", runner.policyRejectReason(emptyList()))
        assertTrue(runner.policyRejectReason(listOf("echo", " ")) != null, "空白元素须拒")
        val e = assertFailsWith<IllegalArgumentException> { runBlocking { runner.run(emptyList()) } }
        assertTrue(e.message.orEmpty().contains("argv"), "拒绝理由须人话：${e.message}")
    }

    @Test
    fun `可执行文件缺失按 IOException 失败上抛`() = runBlocking {
        val e = assertFailsWith<IOException> {
            ArgvProcessRunner().run(listOf("definitely-missing-probe-xyz", "--flag"))
        }
        assertTrue(e.message.orEmpty().isNotBlank(), "IOException message 供调用方渲染命令不可用文案")
    }
}
