package com.jiaoay.easy_dev_toolbox.page.logcat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 崩溃堆栈折叠检测纯函数单测：阈值边界 / threadtime 头与裸缩进两形态帧行 /
 * 多组独立折叠 / 非帧行零误折 / 下标连续性。折叠只改渲染面（引擎/落盘契约零触碰
 * 由引擎侧用例钉死，本文件只钉检测函数）。
 */
class LogcatConsoleFoldTest {

    /** threadtime 头形态帧行（真实 logcat AndroidRuntime 输出形态：消息体前导制表符） */
    private fun headerFrame(line: Int): String =
        "10-11 09:00:00.${(line % 1000).toString().padStart(3, '0')}  100  200 E AndroidRuntime: \tat com.demo.App.f$line(App.kt:$line)"

    /** 裸缩进形态帧行（粘贴堆栈形态：无 logcat 头） */
    private fun rawFrame(line: Int): String = "    at com.demo.App.f$line(App.kt:$line)"

    @Test
    fun `long consecutive stack runs fold into a single expandable group`() {
        val lines = listOf(
            "10-11 09:00:00.000  100  200 E AndroidRuntime: FATAL EXCEPTION: main",
        ) + (1..6).map { headerFrame(it) } + listOf(
            "10-11 09:00:00.010  100  200 I OtherTag: after stack",
        )
        val rows = foldConsoleRows(lines)
        assertEquals(3, rows.size, "首行 + 折叠组 + 尾行共三组")
        val head = assertIs<LogcatConsoleRow.Plain>(rows[0])
        assertEquals(lines[0], head.line)
        val stack = assertIs<LogcatConsoleRow.Stack>(rows[1])
        assertEquals(6, stack.frames.size, "整段帧行进组")
        assertEquals(1, stack.firstIndex, "组下标锚在首帧行")
        val tail = assertIs<LogcatConsoleRow.Plain>(rows[2])
        assertEquals(lines.last(), tail.line)
    }

    @Test
    fun `runs below threshold stay plain and exact threshold folds`() {
        val short = (1..MIN_FOLDABLE_STACK_FRAMES - 1).map { rawFrame(it) }
        val plainRows = foldConsoleRows(short)
        assertEquals(short.size, plainRows.size, "短栈不折叠——折叠反而多一次点击")
        assertTrue(plainRows.all { it is LogcatConsoleRow.Plain })
        assertEquals(short, plainRows.map { (it as LogcatConsoleRow.Plain).line })

        val exact = (1..MIN_FOLDABLE_STACK_FRAMES).map { rawFrame(it) }
        val foldedRows = foldConsoleRows(exact)
        assertEquals(1, foldedRows.size, "恰好达到阈值即折叠")
        val stack = assertIs<LogcatConsoleRow.Stack>(foldedRows[0])
        assertEquals(exact, stack.frames)
    }

    @Test
    fun `raw stack lines without logcat header also fold`() {
        val lines = listOf(
            "java.lang.IllegalStateException: boom",
        ) + (1..7).map { rawFrame(it) }
        val rows = foldConsoleRows(lines)
        assertEquals(2, rows.size, "异常行平铺 + 帧行成组")
        assertIs<LogcatConsoleRow.Plain>(rows[0])
        val stack = assertIs<LogcatConsoleRow.Stack>(rows[1])
        assertEquals(7, stack.frames.size)
        assertEquals(1, stack.firstIndex)
    }

    @Test
    fun `non stack lines never fold and indices stay contiguous`() {
        val lines = listOf(
            "10-11 09:00:00.000  100  200 I AttackTag: attack at dawn",
            "at-sign without whitespace is not a frame",
            "10-11 09:00:00.001  100  200 E AndroidRuntime: at com.demo.One.kt:1",
        )
        val rows = foldConsoleRows(lines)
        assertEquals(lines.size, rows.size, "无长堆栈串时全部平铺")
        assertEquals(lines, rows.map { (it as LogcatConsoleRow.Plain).line })
        assertEquals(lines.indices.toList(), rows.map { it.firstIndex }, "下标与原行位逐位对齐")
    }

    @Test
    fun `multiple stack groups fold independently`() {
        val lines = (1..5).map { rawFrame(it) } +
            listOf("10-11 09:00:00.000  100  200 I Mid: separator") +
            (6..10).map { headerFrame(it) }
        val rows = foldConsoleRows(lines)
        assertEquals(3, rows.size, "两组堆栈各自成组，中间隔行平铺")
        val first = assertIs<LogcatConsoleRow.Stack>(rows[0])
        assertEquals(0, first.firstIndex)
        assertIs<LogcatConsoleRow.Plain>(rows[1])
        val second = assertIs<LogcatConsoleRow.Stack>(rows[2])
        assertEquals(6, second.firstIndex, "第二组锚在隔行之后")
        assertEquals(5, second.frames.size)
    }

    @Test
    fun `empty window folds to empty rows`() {
        assertTrue(foldConsoleRows(emptyList()).isEmpty())
    }
}
