// M4-T8 agent/：🔴 平移旧 `progress/ProgressTest.kt` 的 `ProgressAccumulatorTest`（3 条）→ `ProgressAccumulator`，
// 并补齐方案要求的滚动窗口断言：12 条 + 5000 字符默认、丢最旧可观测（D18）、🔴 O(1) 与会话时长无关。
// 源文件 KDoc 明确引用了本测试类名 `ProgressAccumulatorWindowTest`（ProgressAccumulator.kt:31）。
package com.jiaoay.lark.bridge.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ProgressAccumulator]：BC §3 progress 组滚动窗口 + D18 丢弃可观测 + 非功能「O(1) 与会话时长无关」。
 *
 * 🔴 承接旧 `ProgressAccumulatorTest` 三条（滚动窗口保留最近 N 条、相邻 thinking 合并、终态标记），
 * 并钉住方案硬指标：默认窗口 12 条、整卡 5000 字符、单条 200 字符；长跑不退化成 O(n)。
 */
class ProgressAccumulatorWindowTest {

    @Test
    fun `滚动窗口保留最近 N 条并丢最旧（平移旧断言 + D18 计数）`() {
        val acc = ProgressAccumulator(maxEntries = 3)
        for (i in 1..5) acc.onEvent(ProgressEvent.ToolUse("Bash", "cmd$i"))
        val snapshot = acc.snapshot()
        assertEquals(3, snapshot.events.size)
        val last = snapshot.events.last() as ProgressEvent.ToolUse
        assertEquals("cmd5", last.argsSummary)
        assertEquals(2L, acc.droppedEntries, "🔴 D18：挤出最旧的 2 条必须可观测")
        assertEquals(5L, acc.appendedCount)
    }

    @Test
    fun `相邻 thinking 合并为一条（平移旧断言）`() {
        val acc = ProgressAccumulator(maxEntries = 12)
        acc.onEvent(ProgressEvent.Thinking("第一段"))
        acc.onEvent(ProgressEvent.Thinking("第二段"))
        acc.onEvent(ProgressEvent.ToolUse("Bash", "ls"))
        acc.onEvent(ProgressEvent.Thinking("第三段"))
        val snapshot = acc.snapshot()
        assertEquals(3, snapshot.events.size)
        val first = snapshot.events.first() as ProgressEvent.Thinking
        assertTrue(first.text.contains("第一段"))
        assertTrue(first.text.contains("第二段"))
        assertEquals(1L, acc.mergedEntries, "相邻 thinking 合并一次")
    }

    @Test
    fun `终态标记进入快照（平移旧断言）`() {
        val acc = ProgressAccumulator()
        assertEquals(RunStatus.FAILED, acc.snapshot(statusOverride = RunStatus.FAILED).status)
        assertEquals(RunStatus.RUNNING, acc.snapshot().status)
        acc.markStatus(RunStatus.DONE)
        assertEquals(RunStatus.DONE, acc.snapshot().status, "markStatus 后 snapshot 反映终态")
    }

    @Test
    fun `🔴 默认窗口 6 条 单条 80 字符 整卡 2000 字符（2026-09-11 防刷屏裁定值）`() {
        assertEquals(6, ProgressAccumulator.DEFAULT_MAX_ENTRIES)
        assertEquals(80, ProgressAccumulator.DEFAULT_ENTRY_MAX_CHARS)
        assertEquals(2000, ProgressAccumulator.DEFAULT_CARD_MAX_CHARS)
        val acc = ProgressAccumulator()
        assertEquals(6, acc.limits.maxEntries)
        assertEquals(80, acc.limits.entryMaxChars)
        assertEquals(2000, acc.limits.cardMaxChars)
    }

    @Test
    fun `🔴 O(1) 与会话时长无关：长跑 1000 条窗口恒 6（不退化 O(n)）`() {
        val acc = ProgressAccumulator() // maxEntries 默认 6
        repeat(1000) { i -> acc.onEvent(ProgressEvent.AssistantText("line-$i")) }
        assertEquals(6, acc.entryCount, "🔴 窗口恒 <= maxEntries，与已产出多少行无关")
        assertEquals(6, acc.snapshot().events.size)
        assertEquals(1000L, acc.appendedCount, "累计接收 1000 条")
        assertEquals(994L, acc.droppedEntries, "挤出 1000-6=994 条（D18 可观测）")
    }

    @Test
    fun `🔴 有界内存：单条超长文本入库前硬截断到 STORED_ENTRY_MAX_CHARS`() {
        val acc = ProgressAccumulator()
        acc.onEvent(ProgressEvent.AssistantText("x".repeat(5000)))
        val stored = (acc.snapshot().events.single() as ProgressEvent.AssistantText).text
        assertEquals(ProgressAccumulator.STORED_ENTRY_MAX_CHARS, stored.length, "入库前截到 2000 字符上限")
    }

    @Test
    fun `墙钟心跳进窗口投帧但不驱动空闲看门狗`() {
        var now = 1_000L
        val delegate = com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink(clock = { now })
        val acc = ProgressAccumulator(delegate = delegate)

        now = 5_000L
        acc.onHeartbeat(ProgressEvent.AssistantText("CLI 执行中（已 60s）"))
        assertEquals(1, acc.snapshot().events.size, "心跳条目须进滚动窗口（终态卡「过程」段承载）")
        assertTrue(
            acc.renderSnapshot().contains("CLI 执行中"),
            "心跳须投帧驱动卡片换新——不投帧则静默期卡片静止、用户无从判活",
        )
        assertEquals(
            1_000L,
            delegate.lastActivityMillis,
            "心跳不得驱动活动时间戳——墙钟心跳不是子进程活动证据，touch 会蒙蔽空闲看门狗（挂死任务永不被杀）",
        )

        // 对照组：真实事件必须推进活动时间戳（看门狗只认真实输出的既有语义不回归）
        acc.onEvent(ProgressEvent.ToolUse("Bash", "real"))
        assertEquals(5_000L, delegate.lastActivityMillis, "真实事件照旧 touch 驱动看门狗")
    }
}
