package com.jiaoay.easy_dev_toolbox.core.media

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 子进程等待编排单测（真实 POSIX 子进程——零 ffmpeg 依赖，用 sleep 造挂死态）：
 * 超时强杀与协程取消强杀两条收口路径 + 正常退出返回码/合流输出。
 */
class AudioTranscoderProcessTest {

    @Test
    fun `超时未退出则强杀并报失败`() = runBlocking {
        val process = ProcessBuilder("sleep", "30").start()
        val e = assertFailsWith<IllegalStateException> {
            AudioTranscoder.awaitProcess(process, 1)
        }
        assertTrue(e.message.orEmpty().contains("超时"), "超时报错须带超时语义：${e.message}")
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "强杀后子进程须退出")
    }

    @Test
    fun `协程取消中断等待并强杀子进程`() = runBlocking {
        val process = ProcessBuilder("sleep", "30").start()
        val job = launch { AudioTranscoder.awaitProcess(process, 60) }
        // 等进入 waitFor 后再取消（取消时必须真的在阻塞等待）
        delay(500)
        job.cancelAndJoin()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "取消后子进程须被 destroyForcibly")
    }

    @Test
    fun `正常退出返回退出码与合流输出`() = runBlocking {
        val process = ProcessBuilder("sh", "-c", "echo hi; exit 0").start()
        val (code, output) = AudioTranscoder.awaitProcess(process, 10)
        assertEquals(0, code)
        assertEquals("hi", output.trim())
    }
}
