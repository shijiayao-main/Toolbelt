// :lark-gateway 测试（M3-T3，§11.6 SegmentRotationTest = BC NF-03，裁定 6/B-17 归属本轮）。
// 🔴 零 SDK / 零真机：FakeCardStreamPort + 虚拟时间。
// 🔴 §11.6 ②：必须在 CardStreamSink 层直接喂大内容，完全绕过 ProgressCardRenderer（其 cardMaxChars=5000 会让 24KB 预算永不触发 ⇒ 假绿）。
// 🔴 §11.6 ⑥（作者自律）：本文件不含任何「全局 sequence map 容量 / LRU 逐出」类断言——决策 2 已取消 R8 分支，
//    sequence 是 per-sink 的一个 Int。运行时自 grep 会因「为搜索而嵌入这些词」自相矛盾，故改为对生产源做正向结构断言（见下 noGlobalSequenceMap）。
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.lark.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** §11.6：24KB 预算分段轮换（UTF-8 字节、新建实体不复用 card_id、200860 立即轮换、per-sink sequence 单调）。 */
class SegmentRotationTest {

    // ①②④⑦ 40KB 一次喂入 ⇒ 恰 2 段（绕过 ProgressCardRenderer，直接在 sink 层）
    @Test
    fun `forty KB in a single append rotates into exactly two segments`() = runTest {
        val signals = mutableListOf<GatewaySignal>()
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler, onSignal = { signals.add(it) })
        testScheduler.runCurrent()                      // 建第 1 段
        assertEquals(1, port.createCount)
        assertEquals(1, sink.segmentIndex)

        sink.tryReplace("x".repeat(40_000))              // 🔴 40_000 字节 > cardByteBudget(24576)
        testScheduler.advanceTimeBy(50); testScheduler.runCurrent()   // 主循环 25ms 粒度轮询

        assertEquals(2, port.createCount, "40KB 超 24576 预算 ⇒ 恰轮换 1 次 ⇒ 2 段")
        assertEquals(2, sink.segmentIndex)
        // ④ 每段是新建卡片实体，card_id 互不相同（R20：绝不复用）
        assertEquals(2, port.cardIds.size)
        assertNotEquals(port.cardIds[0], port.cardIds[1], "轮换必须新建 card_id，绝不复用（R20）")
        // ⑦ 轮换发出 StreamSegmentRotated(reason=ByteBudget, segmentIndex=2, newMessageId != null)
        val rotated = signals.filterIsInstance<GatewaySignal.StreamSegmentRotated>()
        assertEquals(1, rotated.size)
        assertEquals(RotationReason.ByteBudget, rotated[0].reason)
        assertEquals(2, rotated[0].segmentIndex)
        assertTrue(rotated[0].newMessageId != null, "轮换信号须带新段的 messageId")
        sink.close(); testScheduler.runCurrent()
    }

    // ③ 按 UTF-8 字节算，不按字符算：8192 中文字符 = 24576 字节 = 预算 ⇒ 触发；8192 ASCII = 8192 字节 ⇒ 不触发
    @Test
    fun `rotation threshold counts UTF-8 bytes not characters`() = runTest {
        val chinese = FakeCardStreamPort()
        val sinkC = newTestSink(chinese, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        sinkC.tryReplace("中".repeat(8192))              // 8192 × 3 字节 = 24576 = 预算
        testScheduler.advanceTimeBy(50); testScheduler.runCurrent()
        assertEquals(2, chinese.createCount, "8192 中文字符 = 24576 UTF-8 字节 = 预算 ⇒ 必轮换（若按字符算 8192<24576 则永不触发）")
        sinkC.close(); testScheduler.runCurrent()

        val ascii = FakeCardStreamPort()
        val sinkA = newTestSink(ascii, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        sinkA.tryReplace("x".repeat(8192))               // 8192 × 1 字节 = 8192 < 预算
        testScheduler.advanceTimeBy(50); testScheduler.runCurrent()
        assertEquals(1, ascii.createCount, "8192 ASCII = 8192 字节 < 24576 ⇒ 不轮换")
        sinkA.close(); testScheduler.runCurrent()
    }

    // ⑧ 200860 触发立即轮换（不等预算判定）+ 失败可观测计数
    @Test
    fun `business code 200860 triggers immediate rotation and is counted`() = runTest {
        val port = FakeCardStreamPort()
        port.patchResponder = { idx -> if (idx == 1) CardCallOutcome(success = false, code = 200860) else null }
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        assertEquals(1, port.createCount)

        sink.tryReplace("hello world")                   // dirty=true 才会 firePatch（滚动窗口）
        testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // timeHit → patch → 200860 → CardTooLarge → 立即轮换

        assertEquals(2, port.createCount, "200860 应紧接着触发 createCardEntity（立即轮换，不等预算）")
        assertEquals(1L, sink.failureCount(GatewayErrorCode.CardTooLarge), "200860 须计入 CardTooLarge 失败计数（可观测三件套之一，§11.4 ⑨）")
        sink.close(); testScheduler.runCurrent()
    }

    // ⑤ per-sink sequence 从起始值单调递增（§5.2：每个 sink 持一个 Int，无全局 map）
    @Test
    fun `sequence increases monotonically within one segment from its start`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        repeat(3) {
            sink.tryReplace("x")
            testScheduler.advanceTimeBy(250); testScheduler.runCurrent()   // 每 250ms 一次 timeHit patch
        }
        assertEquals(1, port.createCount, "文本远小于预算 ⇒ 无轮换")
        assertEquals(listOf(1, 2, 3), port.patchSequences, "同一 sink 内 sequence 从起始值单调递增（§5.2）")
        sink.close(); testScheduler.runCurrent()
    }

    // ⑥ 正向结构断言（决策 2 取消 R8）：生产源不存在全局 card_id→sequence map / 容量治理
    @Test
    fun `sink holds no global sequence map`() {
        val src = readSinkSource()
        // 决策 2：sequence 是 per-sink 的一个 Int；不存在任何全局 map 承载 card_id→sequence
        // 🔴 P1-3 后 sequence 是 per-sink 的 AtomicInteger 字段（仍是 per-sink 单值、非全局 map）；追加接受新形态，不减旧覆盖
        assertTrue("sequence: Int = 0" in src || "var sequence" in src || "AtomicInteger" in src, "sequence 应是 per-sink 的原子/Int 字段")
        // 轮换后 sequence 归零（新实体 ⇒ 新 sequence 空间，§5.2）；P1-3 后用 AtomicInteger.set(0)
        assertTrue("sequence = 0" in src || "sequence.set(0)" in src, "轮换后 sequence 必须重置为起始值")
        // 🔴 不存在 ConcurrentHashMap<.., ..sequence..> 形态的全局 sequence 容器（failureCounters 是按错误码计数，非 sequence map）
        assertTrue(!Regex("""ConcurrentHashMap<[^>]*sequence""", RegexOption.IGNORE_CASE).containsMatchIn(src),
            "禁止全局 card_id→sequence map（决策 2 已取消 R8）")
    }

    // ⑨ 🔴 Bug4 反例钉死（§16 / S6 真机 build/s6p-overflow.log）：脚手架 expOverflow 用「当前段缓冲长度」判定 + 每段 setLength(0) 清零
    //    ⇒ 条件永真 ⇒ 无限循环刷屏（EXIT=143，被 pkill）。本实现分段预算基于「累计快照跨过 N×预算」：rotateSegment 后 slot 归零
    //    ⇒ budgetHit 下次不再成立 ⇒ 循环必然终止。喂 40KB + 推进大量虚拟时间，断言段数封顶且协程可终止。
    @Test
    fun `forty KB rotation terminates and does not loop forever`() = runTest {
        val port = FakeCardStreamPort()
        val sink = newTestSink(port, backgroundScope, testScheduler)
        testScheduler.runCurrent()
        sink.tryReplace("x".repeat(40_000))              // 一次喂入 40KB > 24576 预算 ⇒ 恰轮换 1 次
        // 🔴 推进远超一次轮换所需的虚拟时间：若判定错误依赖「当前段缓冲长度」，清零后条件永真 ⇒ createCount 无限增长
        repeat(400) { testScheduler.advanceTimeBy(25); testScheduler.runCurrent() }
        assertEquals(2, port.createCount, "40KB 只跨过一次 24576 预算 ⇒ 恰 2 段；轮换后 slot 归零 ⇒ budgetHit 不再成立 ⇒ 循环必然终止（钉死 Bug4 反例）")
        assertEquals(2, sink.segmentIndex)
        // 关闭后 sink 不再开放、主循环协程终止（不残留无限轮换）
        sink.close(); testScheduler.runCurrent()
        assertFalse(sink.isOpen)
        assertTrue(sink.job.isCompleted, "close 后主循环 job 必须终止（不无限轮换）")
    }

    private fun readSinkSource(): String {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val f = File(dir, "lark-gateway/src/main/kotlin/com/jiaoay/lark/gateway/LarkCardStreamSink.kt")
        assertTrue(f.exists(), "定位不到 LarkCardStreamSink.kt：${f.absolutePath}")
        return f.readText()
    }
}
