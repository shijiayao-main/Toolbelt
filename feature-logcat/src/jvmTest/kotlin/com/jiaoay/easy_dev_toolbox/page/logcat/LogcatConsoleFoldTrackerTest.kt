package com.jiaoay.easy_dev_toolbox.page.logcat

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 折叠增量追踪器守护（[LogcatConsoleFoldTracker] 对全量参考实现 [foldConsoleRows]）：
 * - 等价性：任意「批量追加 + 溢出截头」序列下，增量 rows() 与全量重扫逐组逐字段
 *   一致——覆盖尾段未闭帧组跨阈值成长（临时呈现由平铺翻折为组）、溢出跨界组可见段
 *   重算两档（≥ 阈值仍折叠 / 跌破阈值平铺）、深截进尾段未闭帧组的头部修剪；
 * - 渲染 key 稳定性：组头行存活的折叠组，渲染 key（滚动窗首行绝对序号 + 组内下标，
 *   与面板 LazyColumn key / expandedStacks 展开记账同公式）跨 commit 与截头恒定——
 *   面板展开态跨 commit 保留的机制面；组头行被截断的组 key 随组头行移位，与全量
 *   重扫同口径（参考实现同样以组首可见行锚定）。
 */
class LogcatConsoleFoldTrackerTest {

    /** 帧序列发生器：裸缩进与 threadtime 头两形态交替（[STACK_FRAME_LINE] 双形态均须命中） */
    private fun frame(line: Int): String =
        if (line % 2 == 0) {
            "    at com.demo.App.f$line(App.kt:$line)"
        } else {
            "10-11 09:00:00.${(line % 1000).toString().padStart(3, '0')}  100  200 E AndroidRuntime: \tat com.demo.App.f$line(App.kt:$line)"
        }

    /**
     * 影子滚动窗：驱动方式与引擎 commitBatch 同构（批量追加 → 溢出截头），每步可与
     * 全量参考实现比对。渲染 key 公式 = 已追加总数 - 窗行数 + 组内下标（与面板
     * firstAbsoluteIndex + row.firstIndex 同源）。
     */
    private class ShadowWindow(private val capacity: Int) {
        val tracker = LogcatConsoleFoldTracker()
        val lines = mutableListOf<String>()
        var appendedTotal = 0L
        private var nextId = 0

        fun plain(): String = "plain-${nextId++}"

        fun filler(count: Int): List<String> = List(count) { "filler-${nextId++}" }

        fun commit(batch: List<String>) {
            lines.addAll(batch)
            batch.forEach { tracker.append(it) }
            appendedTotal += batch.size
            val overflow = lines.size - capacity
            if (overflow > 0) {
                repeat(overflow) { lines.removeAt(0) }
                tracker.evict(overflow)
            }
        }

        fun assertEquivalent() {
            assertEquals(foldConsoleRows(lines), tracker.rows(), "增量折叠须与全量参考实现逐组一致")
        }

        /** 组头行存活的折叠组渲染 key；窗内无折叠组即 fail */
        fun firstStackKey(): Long {
            val base = appendedTotal - lines.size
            val stack = tracker.rows().firstOrNull { it is LogcatConsoleRow.Stack }
                ?: error("窗内应有折叠组：lines=${lines.size}")
            return base + stack.firstIndex
        }
    }

    @Test
    fun `incremental fold stays equivalent to full refold across commits and evictions`() {
        val w = ShadowWindow(capacity = 30)
        // 尾段未闭帧组：先短栈（临时平铺）→ 跨阈值成长（临时呈现翻折为组）→ 非帧行定稿
        w.commit(listOf(w.plain()) + (1..3).map { frame(it) })
        w.assertEquivalent()
        w.commit((4..8).map { frame(it) })
        w.assertEquivalent()
        w.commit(listOf(w.plain()))
        w.assertEquivalent()
        // 追加致溢出：平铺行淘汰出窗
        w.commit(w.filler(40))
        w.assertEquivalent()
        // 尾段再起新帧组并深截：截头落进帧组中段的路径由等价锚一并覆盖
        w.commit((1..20).map { frame(it) } + w.filler(20))
        w.assertEquivalent()
    }

    @Test
    fun `randomized commit and eviction sequences stay equivalent to full refold`() {
        val rng = Random(42)
        repeat(24) { trial ->
            val w = ShadowWindow(capacity = rng.nextInt(6, 48))
            repeat(40) {
                val batch = List(rng.nextInt(1, 14)) {
                    if (rng.nextInt(100) < 35) frame(rng.nextInt(1, 999)) else "plain-$trial-${rng.nextInt(1000)}"
                }
                w.commit(batch)
                w.assertEquivalent()
            }
        }
    }

    @Test
    fun `straddling eviction keeps visible stack frames folded at or above threshold`() {
        val w = ShadowWindow(capacity = 30)
        w.commit(listOf(w.plain()) + (1..12).map { frame(it) } + listOf(w.plain()))
        // 14 + 22 = 36 → 截 6：非帧行 + 帧 1..5 出窗，可见 7 帧 ≥ 阈值
        w.commit(w.filler(22))
        w.assertEquivalent()
        val rows = w.tracker.rows()
        val stack = assertIs<LogcatConsoleRow.Stack>(rows.first())
        assertEquals((6..12).map { frame(it) }, stack.frames, "可见段 ≥ 阈值仍折叠，组内只留存活帧行")
        assertEquals(0, stack.firstIndex, "跨界组重锚在窗首（可见段起点）")
    }

    @Test
    fun `straddling eviction unfolds stack when visible frames drop below threshold`() {
        val w = ShadowWindow(capacity = 30)
        w.commit(listOf(w.plain()) + (1..8).map { frame(it) } + listOf(w.plain()))
        // 10 + 26 = 36 → 截 6：非帧行 + 帧 1..5 出窗，可见 3 帧 < 阈值
        w.commit(w.filler(26))
        w.assertEquivalent()
        val rows = w.tracker.rows()
        assertTrue(rows.take(3).all { it is LogcatConsoleRow.Plain }, "可见段跌破阈值平铺（与全量重扫同口径）")
        assertEquals((6..8).map { frame(it) }, rows.take(3).map { (it as LogcatConsoleRow.Plain).line })
        assertEquals(0, rows.first().firstIndex)
    }

    @Test
    fun `deep eviction into tail pending frames trims from the front and stays equivalent`() {
        // 窗容量小于单批帧组：截断落进尾段未闭帧组——余段从窗首起按阈值照常裁决
        val w = ShadowWindow(capacity = 10)
        w.commit((1..15).map { frame(it) })
        w.assertEquivalent()
        val rows = w.tracker.rows()
        assertEquals(1, rows.size, "满窗整帧组折叠为单组")
        val stack = assertIs<LogcatConsoleRow.Stack>(rows[0])
        assertEquals(frame(6), stack.frames.first(), "截头后组内首帧 = 存活首行")
        assertEquals(0, stack.firstIndex)
    }

    @Test
    fun `render key of surviving stack group is stable across commits and evictions`() {
        val w = ShadowWindow(capacity = 50)
        w.commit(w.filler(10) + (1..6).map { frame(it) } + listOf(w.plain()))
        w.commit(w.filler(11))
        val keyBefore = w.firstStackKey()
        // 28 + 27 = 55 → 截 5：组头行（原下标 10）存活
        w.commit(w.filler(27))
        w.assertEquivalent()
        assertEquals(keyBefore, w.firstStackKey(), "溢出截头后组头行存活的折叠组渲染 key 恒定（展开记账不漂移）")
        // 追加新帧行（并入尾段未闭组）：既存活组的 key 不受后续 commit 影响
        w.commit(listOf(frame(7)))
        w.assertEquivalent()
        assertEquals(keyBefore, w.firstStackKey(), "追加 commit 不改存活组的渲染 key（展开态不丢失）")
    }

    @Test
    fun `reset clears finalized rows and pending frames without residue`() {
        val tracker = LogcatConsoleFoldTracker()
        tracker.append("plain")
        (1..6).forEach { tracker.append(frame(it)) }
        tracker.reset()
        assertEquals(emptyList(), tracker.rows(), "reset 后零残留")
        // reset 后新序列照常折叠（旧下标不串位）
        (1..5).forEach { tracker.append(frame(it)) }
        assertEquals(foldConsoleRows((1..5).map { frame(it) }), tracker.rows())
    }
}
