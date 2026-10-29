package com.jiaoay.lark.bridge.exec

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [方案 R10 · 管道排干防死锁] PipeBackpressureTest：10 万行输出 + 停滞消费者 ⇒ 缓冲有界（channel ≤4096 + OS 管道）、无 OOM、
 * 🔴 [kotlinx.coroutines.channels.BufferOverflow.SUSPEND] 语义证明：**无一行丢失**（若为 DROP，received < 100000）。
 * 消费者停滞期间子进程因背压阻塞仍存活，证明未无脑吞下 10 万行入内存。
 */
class PipeBackpressureTest {

    private companion object {
        const val TOTAL_LINES = 100_000
    }

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("pipe-$tag-${System.nanoTime()}").toFile().apply { deleteOnExit() }

    @Test
    fun `10 万行 + 停滞解析器 - 缓冲有界无 OOM 且 SUSPEND 零丢行`() = runBlocking {
        val root = tempDir("backpressure")
        val registry = ProcessRegistry(maxConcurrent = 4)
        val launcher = ProgramLauncher(WorkspacePolicy(listOf(root)), registry, isWindows = false)
        val procRef = AtomicReference<Process?>(null)
        val received = AtomicInteger(0)
        try {
            val channel = ProgramLauncher.lineChannel()
            assertEquals(ProgramLauncher.LINE_CHANNEL_CAPACITY, 4096)

            // 消费者：先停滞 1.5s（模拟解析器卡顿），随后全速 drain
            val consumer = launch(Dispatchers.IO) {
                delay(1_500)
                for (line in channel) received.incrementAndGet()
            }
            // 生产者：yes | head -n 100000（恰 10 万行 "y"）
            val producer = launch(Dispatchers.IO) {
                launcher.launch(
                    ProgramSpec(
                        command = listOf("/bin/sh", "-c", "yes | head -n $TOTAL_LINES"),
                        workingDir = root,
                    ),
                    channel,
                    totalTimeoutMillis = 120_000,
                    idleTimeoutMillis = 120_000,
                    onStart = { procRef.set(it) },
                )
            }

            // 消费者停滞期间：channel 满(4096)+OS 管道(~64KB) 后子进程 write 阻塞仍存活 ⇒ 缓冲有界，未吞下 10 万行
            val started = withTimeoutOrNull(8_000) { while (procRef.get() == null) delay(20); true } ?: false
            assertTrue(started, "子进程应启动")
            delay(600)
            val p = procRef.get()
            assertNotNull(p)
            assertEquals(0, received.get(), "消费者仍停滞，未消费任何行")
            assertTrue(p.isAlive, "背压阻塞下子进程应仍存活（证明缓冲有界，未无脑吞 10 万行入内存）")

            // 放行：等生产者跑完（依赖消费者 drain），再关 channel，消费者读到 EOF 收尾
            producer.join()
            channel.close()
            consumer.join()

            // 🔴 SUSPEND（非 DROP）：恰 10 万行一行不丢
            assertEquals(TOTAL_LINES, received.get(), "SUSPEND 背压下不得丢任何一行（DROP 会 < $TOTAL_LINES）")
            assertTrue(!p.isAlive, "跑完后子进程应已退出")
        } finally {
            registry.close()
        }
    }
}
