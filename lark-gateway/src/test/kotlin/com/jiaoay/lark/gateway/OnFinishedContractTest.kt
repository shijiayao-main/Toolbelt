// 🔴 2026-09-17 审计修复配套守护：CardStreamSink.onFinished「恰一次」语义（openStreams 摘除链）。
//
// 背景：BridgeAssembler.TrackingReplySink 把开出的每条卡片流登记进 openStreams，此前**只增不减**——
// 长跑期间「消息数 × sink」恒定增长（每个 sink 持整段卡片快照 + Job），关闭时 closeOpenStreams()
// 逐流收尾的开销也随存活数线性上涨。对照网关侧 SdkLarkGateway 的 invokeOnCompletion 摘除先例，
// 接口面新增 onFinished 回调，由 OnceCallback 保证「恰一次」。
//
// 本文件覆盖：
// - OnceCallback 的四格语义矩阵（正常触发 / 幂等 / 晚到注册补触发 / null 注销不触发）；
// - LarkCardStreamSink 三条终态路径（close / 建卡失败主循环退出 / 正常 cancel）各自恰触发一次；
// - 建卡秒败早于注册的竞态：晚到注册必须补触发（真实竞态——job 在构造期即启动）。
package com.jiaoay.lark.gateway

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnFinishedContractTest {

    // ==================== OnceCallback 语义矩阵 ====================

    @Test
    fun `once callback fires exactly once and repeat fire is no-op`() {
        val cb = OnceCallback()
        val n = AtomicInteger(0)
        cb.set { n.incrementAndGet() }
        cb.fire()
        cb.fire()   // 重复触发：幂等 no-op
        assertEquals(1, n.get())
    }

    @Test
    fun `late registration after fire is compensated`() {
        val cb = OnceCallback()
        cb.fire()    // 先触发（终态先到）
        val n = AtomicInteger(0)
        cb.set { n.incrementAndGet() }  // 晚到注册：立即补触发
        assertEquals(1, n.get())
        cb.fire()   // 再 fire 不会二次消费
        assertEquals(1, n.get())
    }

    @Test
    fun `null registration never fires`() {
        val cb = OnceCallback()
        val n = AtomicInteger(0)
        cb.set { n.incrementAndGet() }
        cb.set(null)    // 注销：已注册的回调被清掉
        cb.fire()
        assertEquals(0, n.get(), "注销后 fire 不得触发已清掉的回调")
    }

    @Test
    fun `fire without any callback is safe`() {
        val cb = OnceCallback()
        cb.fire()   // 无回调时触发安全（终态先于任何注册）
        val n = AtomicInteger(0)
        cb.set { n.incrementAndGet() }
        assertEquals(1, n.get())
    }

    @Test
    fun `second set replaces without firing`() {
        val cb = OnceCallback()
        val a = AtomicInteger(0)
        val b = AtomicInteger(0)
        cb.set { a.incrementAndGet() }
        cb.set { b.incrementAndGet() }  // 覆盖：a 永不被触发
        cb.fire()
        assertEquals(0, a.get())
        assertEquals(1, b.get())
    }

    // ==================== LarkCardStreamSink 三路终态 ====================

    @Test
    fun `close triggers onFinished exactly once`() = kotlinx.coroutines.test.runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()   // 建第 1 段
        val n = AtomicInteger(0)
        sink.onFinished { n.incrementAndGet() }
        sink.close()
        testScheduler.runCurrent()   // job.cancel() 后 finally 在调度器上执行
        assertEquals(1, n.get())
    }

    @Test
    fun `job natural exit on create failure triggers onFinished with late registration`() =
        kotlinx.coroutines.test.runTest {
            val port = FakeCardStreamPort().apply {
                createResponder = { CardCallOutcome(success = false, code = 99999) }   // 建卡恒失败
            }
            val sink = newTestSink(port, backgroundScope, testScheduler)
            testScheduler.advanceTimeBy(1); testScheduler.runCurrent()   // 建卡失败 ⇒ 主循环退出
            val n = AtomicInteger(0)
            sink.onFinished { n.incrementAndGet() }   // 晚到注册：必须补触发
            assertEquals(1, n.get())
        }

    @Test
    fun `cancel path triggers onFinished`() = kotlinx.coroutines.test.runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()   // 建第 1 段
        val n = AtomicInteger(0)
        sink.onFinished { n.incrementAndGet() }
        sink.job.cancel()
        testScheduler.runCurrent()
        assertEquals(1, n.get())
    }

    @Test
    fun `flushTerminal also triggers onFinished`() = kotlinx.coroutines.test.runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.advanceTimeBy(1); testScheduler.runCurrent()   // 建第 1 段
        val n = AtomicInteger(0)
        sink.onFinished { n.incrementAndGet() }
        sink.flushTerminal(CardSpec(title = "完成", markdown = "done"))
        testScheduler.runCurrent()   // open=false ⇒ 主循环下一轮退出 ⇒ finally fire
        testScheduler.advanceTimeBy(Tuning.sinkPollIntervalMs + 1); testScheduler.runCurrent()
        assertEquals(1, n.get())
    }
}
