package com.jiaoay.easy_dev_toolbox.core.extensions

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D4 回归：流式执行移除无消费者的 stdout/stderr 累积后，
 * 逐行回调（内容/顺序/来源）、exitCode 语义保持，结果 stdout/stderr 为空串。
 */
class StreamingShellTest {

    @Test
    fun `multi-line output streams via onLine and result fields are empty`() = runBlocking {
        val lines = mutableListOf<ShellOutput>()

        val result = StreamingShell.executeStreaming(
            command = "printf 'line1\\nline2\\nline3\\n'",
            onLine = { lines.add(it) },
        )

        assertTrue(result.isSuccess, "命令应执行成功: ${result.exceptionOrNull()?.message}")
        val shellResult = result.getOrThrow()

        // exitCode 准确
        assertEquals(0, shellResult.exitCode)

        // stdout/stderr 恒为空串（不随结果返回）
        assertEquals("", shellResult.stdout)
        assertEquals("", shellResult.stderr)

        // onLine 逐行回调内容与顺序正确（均为 stdout 来源）
        val stdoutLines = lines.filter { it.source == ShellOutputSource.STDOUT }
        assertEquals(listOf("line1", "line2", "line3"), stdoutLines.map { it.line })
    }

    @Test
    fun `stderr lines stream with STDERR source and exit code is real`() = runBlocking {
        val lines = mutableListOf<ShellOutput>()

        val result = StreamingShell.executeStreaming(
            command = "printf 'err-line\\n' >&2; exit 7",
            onLine = { lines.add(it) },
        )

        val shellResult = result.getOrThrow()
        assertEquals(7, shellResult.exitCode, "应返回真实退出码")
        assertEquals("", shellResult.stdout)
        assertEquals("", shellResult.stderr)

        val stderrLines = lines.filter { it.source == ShellOutputSource.STDERR }
        assertEquals(listOf("err-line"), stderrLines.map { it.line })
        assertTrue(lines.none { it.source == ShellOutputSource.STDOUT && it.line.isNotBlank() })
    }

    @Test
    fun `onStarted delivers killable handle`() = runBlocking {
        var handle: StreamingShellHandle? = null
        val result = StreamingShell.executeStreaming(
            command = "echo done",
            onStarted = { handle = it },
            onLine = { },
        )
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().exitCode)
        // handle 可用（kill 为幂等安全调用，进程已退出时 runCatching 兜底）
        handle?.kill()
        Unit
    }
}
