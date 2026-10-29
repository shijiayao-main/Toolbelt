package com.jiaoay.easy_dev_toolbox.page.logcat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 崩溃行高亮判定纯函数单测：高亮与折叠（[foldConsoleRows]）共用同一帧行真源——
 * 一致性断言钉死两渲染面不漂移（折叠组内帧恒为崩溃行 / 非崩溃行永不进折叠组）；
 * 阈值差（短帙不折叠但仍高亮）是唯一允许的口径差，方向由独立用例钉死。
 */
class LogcatCrashHighlightTest {

    /** threadtime 头形态帧行（真实 logcat AndroidRuntime 输出形态：消息体前导制表符） */
    private fun headerFrame(line: Int): String =
        "10-11 09:00:00.${(line % 1000).toString().padStart(3, '0')}  100  200 E AndroidRuntime: \tat com.demo.App.f$line(App.kt:$line)"

    /** 裸缩进形态帧行（粘贴堆栈形态：无 logcat 头） */
    private fun rawFrame(line: Int): String = "    at com.demo.App.f$line(App.kt:$line)"

    @Test
    fun `both frame forms are crash lines`() {
        assertTrue(isCrashLine(headerFrame(1)), "threadtime 头帧行应判定崩溃")
        assertTrue(isCrashLine(rawFrame(1)), "裸缩进帧行应判定崩溃")
        assertTrue(isCrashLine("at com.demo.App.f1(App.kt:1)"), "无缩进裸帧行应判定崩溃")
    }

    @Test
    fun `non frame lines are not crash lines`() {
        listOf(
            "",
            "java.lang.IllegalStateException: boom", // 异常头行非帧行
            "10-11 09:00:00.000  100  200 I T: attack at dawn", // 消息体含 at 子串非帧行
            "at-sign without whitespace is not a frame",
        ).forEach { line ->
            assertFalse(isCrashLine(line), "非帧行不得判定崩溃（$line）")
        }
    }

    @Test
    fun `highlight judgment stays consistent with fold grouping`() {
        val lines = listOf(
            "java.lang.IllegalStateException: boom",
        ) + (1..3).map { rawFrame(it) } + // 短帙：高亮不折叠
            listOf("10-11 09:00:00.000  100  200 I Mid: separator") +
            (1..6).map { headerFrame(it) } + // 长串：折叠 + 高亮
            listOf("10-11 09:00:00.009  100  200 E T: not a frame")
        val rows = foldConsoleRows(lines)
        // 正向：折叠组内全部帧行均判定崩溃——高亮不漏折叠面
        rows.filterIsInstance<LogcatConsoleRow.Stack>().forEach { stack ->
            stack.frames.forEach { frame ->
                assertTrue(isCrashLine(frame), "折叠组内帧行必须判定崩溃——高亮/折叠判定漂移")
            }
        }
        // 反向：非崩溃行永不进折叠组——折叠不误收高亮口径外的行
        val foldedIndices = rows.filterIsInstance<LogcatConsoleRow.Stack>()
            .flatMap { (it.firstIndex until it.firstIndex + it.frames.size).toList() }
            .toSet()
        lines.forEachIndexed { index, line ->
            if (!isCrashLine(line)) {
                assertFalse(
                    foldedIndices.contains(index),
                    "非崩溃行不得进折叠组（$line）——两判定漂移",
                )
            }
        }
    }

    @Test
    fun `short frame runs highlight without folding`() {
        val short = (1..MIN_FOLDABLE_STACK_FRAMES - 1).map { rawFrame(it) }
        val rows = foldConsoleRows(short)
        assertTrue(rows.all { it is LogcatConsoleRow.Plain }, "短帙不折叠")
        assertTrue(short.all { isCrashLine(it) }, "短帙仍逐行高亮——阈值差只在折叠面")
    }
}
