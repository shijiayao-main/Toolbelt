// :lark-gateway 测试（M3-T3，§11.7 Stream10MinRotationTest = BC NF-04，裁定 6/B-17 归属本轮）。
// 🔴 单卡收敛（2026-09-12）：主动 TimeDeadline 轮换**退役**——本文件语义整体反转为「不轮换」断言：
//    长任务持续流式也不得强发新卡（BC-1035「10min 自动关流」从未实测，真实关流由 200750 被动重建兜底）。
// 🔴 全程虚拟时间（testScheduler），零 SDK（FakeCardStreamPort 承载 cardkit 四步）。
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.lark.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** 单卡收敛：长任务不主动轮换 + 200750 被动重建 + 预算轮换仍保留。 */
class Stream10MinRotationTest {

    // ① 跨过原 9min 期限 ⇒ 不轮换（恒 1 段、零轮换信号）
    @Test
    fun `crossing former nine minute deadline does not rotate`() = runTest {
        val port = FakeCardStreamPort()
        val signals = mutableListOf<GatewaySignal>()
        val sink = newTestSink(port, backgroundScope, testScheduler, onSignal = { signals.add(it) })
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(Tuning.streamSegmentDeadlineMs + 1000); testScheduler.runCurrent()   // 541_000 > 540_000
        assertEquals(1, port.createCount, "主动轮换已退役：跨过 9min 不得强发新卡")
        assertEquals(1, sink.segmentIndex)
        assertTrue(signals.filterIsInstance<GatewaySignal.StreamSegmentRotated>().isEmpty(), "不得有 TimeDeadline 轮换信号")
        sink.close(); testScheduler.runCurrent()
    }

    // ② 持续流式 18min ⇒ 仍恒 1 段（旧断言「恰 2 次轮换」的反转）
    @Test
    fun `eighteen minutes of continuous streaming stays single card`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        repeat(1080) {                                   // 1080 × 1000ms = 1_080_000ms = 18min
            sink.tryReplace("x")
            testScheduler.advanceTimeBy(1000)
        }
        testScheduler.runCurrent()
        assertEquals(1, port.createCount, "18min 持续流式 ⇒ 恒 1 段（单卡收敛，真机 18.7min 任务曾发 3 张卡）")
        assertEquals(1, port.cardIds.size)
        sink.close(); testScheduler.runCurrent()
    }

    // ③ 200750 映射为专属错误码 CardEntityExpired 并触发**被动**重建 + EntityExpired 信号 + 失败计数（兜底语义不变）
    @Test
    fun `code 200750 rebuilds with EntityExpired signal and is counted`() = runTest {
        val signals = mutableListOf<GatewaySignal>()
        val port = FakeCardStreamPort()
        port.patchResponder = { idx -> if (idx == 1) CardCallOutcome(success = false, code = 200750) else null }
        val sink = newTestSink(port, backgroundScope, testScheduler, onSignal = { signals.add(it) })
        testScheduler.runCurrent()

        sink.tryReplace("hello world")
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // patch → 200750 → CardEntityExpired → 重建

        assertEquals(1L, sink.failureCount(GatewayErrorCode.CardEntityExpired), "200750 须计入 CardEntityExpired 失败计数")
        assertEquals(2, port.createCount, "200750 应触发被动重建（新建卡片实体）")
        assertTrue(signals.filterIsInstance<GatewaySignal.StreamSegmentRotated>().any { it.reason == RotationReason.EntityExpired },
            "重建须发 reason=EntityExpired 信号")
        sink.close(); testScheduler.runCurrent()
    }

    // ④ 未登记业务码（非已知结构性错误）⇒ 被动重建续命（不再静默 GIVE_UP 死流）
    @Test
    fun `unregistered code rebuilds instead of silent giveup`() = runTest {
        val port = FakeCardStreamPort()
        port.patchResponder = { idx -> if (idx == 1) CardCallOutcome(success = false, code = 47001) else null } // 未登记码
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        sink.tryReplace("content")
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // patch → 47001 → 重建

        assertEquals(2, port.createCount, "未登记码应先试换卡续命（2026-09-12：else 从 GIVE_UP 升级 REBUILD_STREAM）")
        assertTrue(sink.isOpen, "重建成功 ⇒ 流继续开放")
        sink.close(); testScheduler.runCurrent()
    }

    // ⑤ 已知结构性错误（300302 JSON 结构）仍 GIVE_UP 死流——换卡也没用（负例守护，防兜底升级误伤真死分支）
    @Test
    fun `known structural code still gives up`() = runTest {
        val port = FakeCardStreamPort()
        port.patchResponder = { idx -> if (idx == 1) CardCallOutcome(success = false, code = 300302) else null }
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        sink.tryReplace("content")
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // patch → 300302 → GIVE_UP

        assertEquals(1, port.createCount, "300302 结构错误重试必然再错 ⇒ 不重建（CardJsonInvalid 保持 GIVE_UP）")
        assertEquals(1L, sink.failureCount(GatewayErrorCode.CardJsonInvalid))
        sink.close(); testScheduler.runCurrent()
    }

    // ⑥ 24KB 预算轮换保留（30KB 硬限防御，与期限无关）
    @Test
    fun `byte budget rotation still works without deadline`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        sink.tryReplace("x".repeat(40_000))                 // 40KB > 24576 预算
        testScheduler.advanceTimeBy(1000); testScheduler.runCurrent()
        assertEquals(2, port.createCount, "ByteBudget 轮换保留（滚动窗口恒不可达，但 cardMaxChars 调大时兜底）")
        sink.close(); testScheduler.runCurrent()
    }

    // ⑦ 🔴 对抗评审 P1 回归守护：patch **持续**报未登记码而建卡持续成功 ⇒ 重建受预算约束、流最终关闭。
    //    没有这条断言时，else→REBUILD_STREAM 会每 250ms 成功重建一次（≈4 张幽灵卡/秒轰进会话）——
    //    恰是本次单卡收敛要消灭的刷屏换了个触发路径。
    @Test
    fun `persistent unknown code stops after rebuild budget instead of flooding cards`() = runTest {
        val port = FakeCardStreamPort()
        port.patchResponder = { _ -> CardCallOutcome(success = false, code = 47001) }   // 每次 patch 都失败，建卡恒成功
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()

        repeat(10) {                                        // 10 帧 × (patch 失败 + 1s 退避)
            sink.tryReplace("frame-$it")
            testScheduler.advanceTimeBy(1_500)
            testScheduler.runCurrent()
        }
        testScheduler.advanceTimeBy(Tuning.sinkPollIntervalMs * 2); testScheduler.runCurrent()

        // 首段 1 张 + 至多 maxConsecutiveRebuilds 张重建卡；绝不允许「每帧一张」的无界增长
        assertTrue(port.createCount <= 1 + Tuning.maxConsecutiveRebuilds,
            "重建必须有预算上限（实际建卡 ${port.createCount} 张，上限 ${1 + Tuning.maxConsecutiveRebuilds}）")
        assertTrue(!sink.isOpen, "预算耗尽后流必须关闭（不得无限重建刷幽灵卡）")
        sink.close(); testScheduler.runCurrent()
    }

    // ⑧ 重建预算在「一次成功 patch」后清零——瞬时抖动不该累积到上限而误杀长任务
    @Test
    fun `rebuild budget resets after a successful patch`() = runTest {
        val port = FakeCardStreamPort()
        val failFrames = setOf(1, 3, 5)                     // 三次失败被两次成功隔开
        port.patchResponder = { idx -> if (idx in failFrames) CardCallOutcome(success = false, code = 47001) else null }
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()

        repeat(6) {
            sink.tryReplace("frame-$it")
            testScheduler.advanceTimeBy(1_500)
            testScheduler.runCurrent()
        }

        assertTrue(sink.isOpen, "成功 patch 须清零重建计数 ⇒ 抖动不累积、长任务不被误杀（实际建卡 ${port.createCount} 张）")
        sink.close(); testScheduler.runCurrent()
    }
}
