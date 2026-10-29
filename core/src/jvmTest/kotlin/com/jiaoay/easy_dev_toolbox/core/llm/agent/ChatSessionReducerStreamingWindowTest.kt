package com.jiaoay.easy_dev_toolbox.core.llm.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * delta 合并窗单测（ChatSessionReducer 的 [ChatEntry.Assistant] 节流契约履行）：
 * 虚拟时间驱动（runTest delay 虚拟化——禁真实 sleep），零 LLM/零网络。
 *
 * 覆盖面：
 * - 窗首 delta 立即发布 + 窗内后续 delta 只缓冲不发布（合并语义）；
 * - 窗到期一次性冲刷缓冲（发布序列无中间态）；
 * - finalizeRun 收口强制冲刷滞留 delta——末段不丢（正确性硬点）；
 * - 收口终态与不开窗（逐 delta 直发）实现逐字一致；
 * - LlmCallCompleted 冲刷后原位转正（草稿 id 保留）+ 收口后窗到期无幽灵发布；
 * - restore/clear 整体替换丢弃窗内滞留 delta；
 * - 不开窗（默认构造）保持逐 delta 直发旧形态。
 */
class ChatSessionReducerStreamingWindowTest {

    private val windowMillis = 64L

    private fun lastAssistant(entries: MutableStateFlow<List<ChatEntry>>): ChatEntry.Assistant? =
        entries.value.lastOrNull() as? ChatEntry.Assistant

    @Test
    fun `first delta publishes immediately and window buffers subsequent deltas`() = runTest {
        val entries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val reducer = ChatSessionReducer(
            entries = entries,
            ledgerKey = "run-window",
            deltaMergeWindowMillis = windowMillis,
            flushScope = CoroutineScope(coroutineContext),
        )

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "a"))
        assertEquals("a", lastAssistant(entries)?.text, "窗首 delta 立即发布（首字零延迟）")
        assertTrue(lastAssistant(entries)?.streaming == true)

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "b"))
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "c"))
        runCurrent()
        assertEquals(
            "a",
            lastAssistant(entries)?.text,
            "窗内后续 delta 只缓冲不发布（entries 不随逐 delta 重建）",
        )
    }

    @Test
    fun `window expiry publishes merged buffer without intermediate states`() = runTest {
        val entries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val reducer = ChatSessionReducer(
            entries = entries,
            ledgerKey = "run-window",
            deltaMergeWindowMillis = windowMillis,
            flushScope = CoroutineScope(coroutineContext),
        )

        // 发布序列取证：收集器记录每次 entries 发射的末条 assistant 文本
        //（backgroundScope 收集——StateFlow collect 永不完成，挂主 TestScope 会挂死 runTest；
        //  首条 <init> 是 StateFlow 订阅即发射的初始空列表）
        val emissions = mutableListOf<String>()
        backgroundScope.launch {
            entries.collect { list ->
                emissions.add((list.lastOrNull() as? ChatEntry.Assistant)?.text ?: "<init>")
            }
        }
        runCurrent()

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "a"))
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "b"))
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "c"))
        runCurrent()
        assertEquals(listOf("<init>", "a"), emissions, "窗内零中间发布")

        advanceTimeBy(windowMillis)
        runCurrent()
        assertEquals(listOf("<init>", "a", "abc"), emissions, "窗到期一次发布合并文本，无 ab 中间态")
        assertEquals(1, entries.value.size, "合并发布不增行（草稿原位追加）")

        // 窗关闭后新 delta 重开窗：仍是首字立即 + 新窗缓冲
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "d"))
        runCurrent()
        assertEquals("abcd", lastAssistant(entries)?.text, "窗关闭后新 delta 立即发布（重开窗）")
        advanceTimeBy(windowMillis)
        runCurrent()
        assertEquals(listOf("<init>", "a", "abc", "abcd"), emissions, "空窗到期零发布（无噪音发射）")
    }

    @Test
    fun `finalize run flushes pending deltas so dangling draft keeps full text`() = runTest {
        val entries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val reducer = ChatSessionReducer(
            entries = entries,
            ledgerKey = "run-window",
            deltaMergeWindowMillis = windowMillis,
            flushScope = CoroutineScope(coroutineContext),
        )

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "前"))
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "半段"))
        // 窗未到期直接收口（流中断无完成事件）——滞留 delta 必须强制冲刷
        reducer.finalizeRun("")
        val promoted = lastAssistant(entries)
        assertEquals("前半段", promoted?.text, "收口冲刷后草稿转正文本含窗内末段（不丢尾）")
        assertEquals(false, promoted?.streaming)
        advanceTimeBy(windowMillis * 2)
        runCurrent()
        assertEquals(
            "前半段",
            lastAssistant(entries)?.text,
            "收口后窗到期无幽灵发布（冲刷 job 已取消）",
        )
    }

    @Test
    fun `finalize run final state equals no-window implementation`() = runTest {
        val windowedEntries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val windowed = ChatSessionReducer(
            entries = windowedEntries,
            ledgerKey = "run-windowed",
            deltaMergeWindowMillis = windowMillis,
            flushScope = CoroutineScope(coroutineContext),
        )
        val legacyEntries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val legacy = ChatSessionReducer(
            entries = legacyEntries,
            ledgerKey = "run-legacy",
        )

        val deltas = listOf("结论", "第一", "段。", "结论", "第二", "段。")
        deltas.forEachIndexed { index, delta ->
            windowed.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = delta))
            legacy.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = delta))
            // 窗中段制造「窗开着收口」的竞争形态（部分 delta 滞留窗内）
            if (index == 2) advanceTimeBy(windowMillis / 2)
        }
        windowed.finalizeRun("")
        legacy.finalizeRun("")

        val w = windowedEntries.value.lastOrNull() as? ChatEntry.Assistant
        val l = legacyEntries.value.lastOrNull() as? ChatEntry.Assistant
        assertEquals(l?.text, w?.text, "收口终态文本与无节流实现一致")
        assertEquals(l?.streaming, w?.streaming, "收口终态 streaming 旗一致")
        assertEquals(windowedEntries.value.size, legacyEntries.value.size, "收口终态行数一致")
    }

    @Test
    fun `llm completion flushes buffer and promotes draft in place`() = runTest {
        val entries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val reducer = ChatSessionReducer(
            entries = entries,
            ledgerKey = "run-window",
            deltaMergeWindowMillis = windowMillis,
            flushScope = CoroutineScope(coroutineContext),
        )

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "结"))
        val draftId = lastAssistant(entries)?.id
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "论")) // 窗内滞留
        reducer.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "结论终稿"))

        val promoted = lastAssistant(entries)
        assertEquals(draftId, promoted?.id, "冲刷后原位转正——草稿 id 保留（与无节流实现一致）")
        assertEquals("结论终稿", promoted?.text)
        assertEquals(false, promoted?.streaming)
        assertEquals(1, entries.value.count { it is ChatEntry.Assistant }, "转正不增行")
        advanceTimeBy(windowMillis * 2)
        runCurrent()
        assertEquals("结论终稿", lastAssistant(entries)?.text, "转正后窗到期无幽灵发布")
    }

    @Test
    fun `restore drops windowed pending deltas`() = runTest {
        val entries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val reducer = ChatSessionReducer(
            entries = entries,
            ledgerKey = "run-window",
            deltaMergeWindowMillis = windowMillis,
            flushScope = CoroutineScope(coroutineContext),
        )

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "a"))
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "b")) // 窗内滞留
        reducer.restore(emptyList())
        advanceTimeBy(windowMillis * 2)
        runCurrent()
        assertTrue(
            entries.value.isEmpty() && entries.value.none { it is ChatEntry.Assistant },
            "整体替换后滞留 delta 不冲刷进新列表（无幽灵草稿）",
        )

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "新"))
        advanceTimeBy(windowMillis)
        runCurrent()
        assertEquals("新", lastAssistant(entries)?.text, "restore 后新窗正常开（旧窗 job 不阻塞）")
    }

    @Test
    fun `clear drops windowed pending deltas`() = runTest {
        val entries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val reducer = ChatSessionReducer(
            entries = entries,
            ledgerKey = "run-window",
            deltaMergeWindowMillis = windowMillis,
            flushScope = CoroutineScope(coroutineContext),
        )

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "a"))
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "b")) // 窗内滞留
        reducer.clear()
        advanceTimeBy(windowMillis * 2)
        runCurrent()
        assertTrue(entries.value.isEmpty(), "clear 后滞留 delta 不再发布")
    }

    @Test
    fun `null window keeps legacy per-delta publish`() {
        val entries = MutableStateFlow<List<ChatEntry>>(emptyList())
        val reducer = ChatSessionReducer(entries = entries, ledgerKey = "run-legacy")

        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "a"))
        reducer.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "b"))
        assertEquals(
            "ab",
            lastAssistant(entries)?.text,
            "不开窗（默认构造）逐 delta 直发——既有消费语境零变化",
        )
        reducer.finalizeRun("ab")
        assertEquals("ab", lastAssistant(entries)?.text)
        assertFalse(lastAssistant(entries)?.streaming == true)
    }
}
