package com.jiaoay.easy_dev_toolbox.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 诊断报告组装纯函数测试：字段完备性、栈/Caused by/日志尾部的截断上限、
 * 空尾部语义。组装是崩溃链上的唯一格式化点，断言锚定在报告的对外契约字段。
 */
class CrashReportTest {

    /** 递归造栈：栈深远超 [CrashReport.STACK_FRAME_LIMIT]，用于截断断言 */
    private fun deepFailure(depth: Int): RuntimeException {
        if (depth <= 0) return RuntimeException("deep-boom")
        return deepFailure(depth - 1)
    }

    @Test
    fun `report contains thread name, exception class, message and stack frames`() {
        val ex = RuntimeException("boom-message")
        val report = CrashReport.build(threadName = "worker-1", throwable = ex, logTail = emptyList())

        assertTrue(report.contains("worker-1"), "报告应含线程名字段")
        assertTrue(report.contains("java.lang.RuntimeException"), "报告应含异常类名")
        assertTrue(report.contains("boom-message"), "报告应含异常消息")
        assertTrue(Regex("(?m)^    at ").containsMatchIn(report), "报告应含栈帧")
        // 空日志尾部不出现「最近日志」段（启动极早期缓冲为空的语义）
        assertFalse(report.contains("最近日志"))
    }

    @Test
    fun `stack frames are capped at limit`() {
        val report = CrashReport.build("t", deepFailure(40), logTail = emptyList())
        assertEquals(
            CrashReport.STACK_FRAME_LIMIT,
            Regex("(?m)^    at ").findAll(report).count(),
            "直接栈帧数应被截断到上限",
        )
    }

    @Test
    fun `cause chain rendered with class and message`() {
        val ex = RuntimeException("outer", IllegalStateException("mid", IllegalArgumentException("root-cause")))
        val report = CrashReport.build("t", ex, logTail = emptyList())

        assertTrue(report.contains("Caused by: java.lang.IllegalStateException: mid"))
        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException: root-cause"))
        assertEquals(2, Regex("(?m)^Caused by: ").findAll(report).count())
    }

    @Test
    fun `log tail rendered verbatim in order`() {
        val lines = (1..CrashReport.LOG_TAIL_LINES).map { "log-line-$it" }
        val report = CrashReport.build("t", RuntimeException("x"), logTail = lines)

        assertTrue(report.contains("最近日志"))
        for (line in lines) {
            assertTrue(report.contains(line), "日志尾部行应逐字入报: $line")
        }
        // 顺序契约：最旧在前，最后一行是尾部末行
        assertTrue(
            report.indexOf("log-line-1") < report.indexOf("log-line-${CrashReport.LOG_TAIL_LINES}"),
            "日志尾部应时间升序（最旧在前）",
        )
    }
}
