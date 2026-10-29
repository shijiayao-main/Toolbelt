// :lark-ws —— 分发器单测：背压 / 满载计数 / 关闭收口（零协程泄漏——对应 StartStopLeakTest 语义）。
package com.jiaoay.lark.ws

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WsDispatcherTest {

    private val logs = java.util.concurrent.ConcurrentLinkedQueue<Pair<WsLogLevel, String>>()

    @Test
    fun `submit 后挂起处理完成`() = runTest {
        val processed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val d = WsDispatcher(Dispatchers.Default, capacity = 8) { _, _ -> }
        val latch = CountDownLatch(1)
        d.submit("f1") { processed.add("a"); latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        assertEquals(listOf("a"), processed.toList())
        d.close()
        assertEquals(1, d.processedCount)
        assertEquals(0, d.droppedCount)
    }

    @Test
    fun `满载丢弃并计数（不阻塞提交方）`() = runTest {
        val slow = java.util.concurrent.atomic.AtomicInteger(0)
        val d = WsDispatcher(Dispatchers.Default, capacity = 2, log = { l, m -> logs.add(l to m) })
        // 用 latch 卡住消费：队列 2 + 在途 1 之外的全满载
        val gate = CountDownLatch(1)
        d.submit("block") { gate.await() }
        // 消费协程拿到 block 任务后队列空出——立刻塞满 2
        while (d.processedCount + 0L < 0) delay(10) // processedCount 在 block 完成后才 +1，此处不适用
        // 直接灌：capacity=2 全满 + 消费在途 1
        var accepted = 0
        for (i in 1..10) {
            if (d.submit("f$i") { }) accepted++
        }
        // 队列容量 2：首灌时 block 已被消费协程取走（在途），f1/f2 入队成功，f3+ 满载
        assertTrue(accepted < 10, "应有部分提交被拒（accepted=$accepted）")
        assertTrue(d.droppedCount > 0, "满载丢弃必须计数（dropped=${d.droppedCount}）")
        assertTrue(logs.any { it.first == WsLogLevel.WARN && it.second.contains("已满") }, "满载必须 WARN 不静默")
        gate.countDown()
        d.close()
    }

    @Test
    fun `处理异常被吞且计数照常（回执语义归调用方）`() = runTest {
        val d = WsDispatcher(Dispatchers.Default, capacity = 8, log = { l, m -> logs.add(l to m) })
        d.submit("boom") { throw IllegalStateException("handler boom") }
        d.submit("ok") { }
        // 🔴 竞态修复（2026-09-13 批 1 升级后首现）：latch countDown 不能作完成信号——
        //    block 执行完到 processed.incrementAndGet() 之间有窗口，断言可能差一拍
        //    （偶发 expected:<2> but was:<1>）。轮询等计数落定（上限 5s），boom+ok 都计入。
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (d.processedCount < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(logs.any { it.first == WsLogLevel.WARN && it.second.contains("事件处理异常") })
        assertEquals(2, d.processedCount) // 异常任务也计入已处理
        d.close()
    }

    @Test
    fun `close 后 scope 全回收（children 归零——零反射的收口语义）`() = runTest {
        val d = WsDispatcher(Dispatchers.Default, capacity = 4) { _, _ -> }
        val latch = CountDownLatch(1)
        d.submit("f") { latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
        d.close()
        // scope 是私有的——用行为断言代替：close 后 submit 直接失败（channel 已关）
        assertTrue(!d.submit("after-close") { })
    }

    @Test
    fun `并发提交与消费无损`() = runTest {
        val d = WsDispatcher(Dispatchers.Default, capacity = 256) { _, _ -> }
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val threads = (1..4).map {
            Thread {
                repeat(50) { d.submit("f-$it") { counter.incrementAndGet() } }
            }.also { it.start() }
        }
        threads.forEach { it.join(5000) }
        // 全部处理完（容量足够无丢弃）
        val deadline = System.currentTimeMillis() + 5000
        while (counter.get() < 200 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(200, counter.get())
        assertEquals(0, d.droppedCount)
        d.close()
    }
}
