package com.jiaoay.easy_dev_toolbox.core.log

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 最近日志环形缓冲测试：容量上限/丢最旧/读取顺序（直接注入），以及经真实
 * logback 管道的端到端收录（只收录实际发射的事件）。
 */
class RecentLogBufferTest {

    @AfterTest
    fun tearDown() {
        RecentLogBuffer.clear()
    }

    @Test
    fun `tail returns most recent lines in order and capacity drops oldest`() {
        RecentLogBuffer.clear()
        val total = RecentLogBuffer.CAPACITY + 100
        (1..total).forEach { RecentLogBuffer.record("line-$it") }

        // 只取尾部 n 行，时间升序
        assertEquals(
            (total - 9..total).map { "line-$it" },
            RecentLogBuffer.tail(10),
        )
        // n 超过存量 → 返回全量（= 容量上限行）
        assertEquals(RecentLogBuffer.CAPACITY, RecentLogBuffer.tail(total).size)
        // 超容部分被丢弃
        assertFalse(RecentLogBuffer.tail(RecentLogBuffer.CAPACITY).contains("line-1"))
        assertTrue(RecentLogBuffer.tail(RecentLogBuffer.CAPACITY).contains("line-$total"))
    }

    @Test
    fun `logback pipeline captures emitted events with level and thread`() {
        RecentLogBuffer.clear()
        LogbackHelper.init()
        JLog.init(KotlinLoggingLogImpl())
        try {
            JLog.i("RingProbe") { "ring-buffer-marker" }
            val tail = RecentLogBuffer.tail(10)

            val line = tail.firstOrNull { it.contains("RingProbe: ring-buffer-marker") }
            assertTrue(line != null, "实际发射的日志应入环形缓冲，实际尾部: $tail")
            assertTrue(line!!.contains("INFO"), "行内应含级别字段")
            assertTrue(line.contains(Thread.currentThread().name), "行内应含线程名字段")
        } finally {
            JLog.init(DefaultLogImpl())
            RecentLogBuffer.clear()
        }
    }
}
