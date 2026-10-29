package com.jiaoay.easy_dev_toolbox.core.runtime

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 应用内查看器请求槽行为验收：offer/consume 覆盖清槽语义 + hostMounted 挂载信号。
 * 生产面（palette 命令/日志对比差异行跳转/工作台证据流截断动作）的接线守护在各
 * 宿主模块 wiring 守护测试背书，此处只锁槽本体契约。
 */
class LogViewerRequestTest {

    @AfterTest
    fun tearDown() {
        LogViewerRequest.clearForTest()
    }

    @Test
    fun `offer writes pending request with overwrite semantics and consume clears slot`() {
        LogViewerRequest.offer("/tmp/a.log")
        assertEquals("/tmp/a.log", LogViewerRequest.pending.value?.path)
        assertEquals(null, LogViewerRequest.pending.value?.initialLine, "不带行号请求 initialLine = null")
        assertEquals(null, LogViewerRequest.pending.value?.initialTimestamp, "不带定位请求 initialTimestamp = null")
        LogViewerRequest.offer("/tmp/b.log", initialLine = 42)
        assertEquals("/tmp/b.log", LogViewerRequest.pending.value?.path, "覆盖语义：在台请求被新请求替换")
        assertEquals(42, LogViewerRequest.pending.value?.initialLine)
        LogViewerRequest.consume()
        assertEquals(null, LogViewerRequest.pending.value, "consume 清槽（关闭后可再次打开）")
    }

    @Test
    fun `offer carries initial timestamp and passes both positioning fields through without ruling`() {
        LogViewerRequest.offer("/tmp/c.log", initialTimestamp = "10-11 14:23:45.123")
        assertEquals("10-11 14:23:45.123", LogViewerRequest.pending.value?.initialTimestamp)
        assertEquals(null, LogViewerRequest.pending.value?.initialLine, "纯时间戳请求不带行号")

        // 并存透传：行号优先的裁决在宿主消费面执行——槽只透传不裁决，两字段
        // 原样在台供消费面按优先级取用
        LogViewerRequest.offer("/tmp/d.log", initialLine = 7, initialTimestamp = "10-11 14:23:45.123")
        assertEquals(7, LogViewerRequest.pending.value?.initialLine)
        assertEquals("10-11 14:23:45.123", LogViewerRequest.pending.value?.initialTimestamp)
        LogViewerRequest.consume()
    }

    @Test
    fun `host mounted flag defaults false marks idempotently and resets for test`() {
        assertFalse(LogViewerRequest.hostMounted.value, "未挂载恒 false——生产面降级判据（不得静默 no-op）")
        LogViewerRequest.markHostMounted()
        assertTrue(LogViewerRequest.hostMounted.value, "宿主置位后挂载态为 true")
        LogViewerRequest.markHostMounted()
        assertTrue(LogViewerRequest.hostMounted.value, "置位幂等")
        LogViewerRequest.clearForTest()
        assertFalse(LogViewerRequest.hostMounted.value, "clearForTest 复位挂载态（单测隔离）")
    }
}
