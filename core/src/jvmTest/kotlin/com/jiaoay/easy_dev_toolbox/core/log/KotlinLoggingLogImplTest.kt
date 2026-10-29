package com.jiaoay.easy_dev_toolbox.core.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D1 回归：带异常的 e(tag, throwable, msg) 必须以 ERROR 级提交，
 * 在 root DEBUG 级别下依然可见（原缺陷为误调 trace 被过滤）。
 */
class KotlinLoggingLogImplTest {

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var rootLogger: ch.qos.logback.classic.Logger

    @BeforeTest
    fun setUp() {
        // 初始化 logback：root 级别 DEBUG + 控制台 appender（与应用运行态一致）
        LogbackHelper.init()
        rootLogger = requireNotNull(LogbackHelper.getRootLogger()) { "logback root logger 不可用" }
        assertEquals(Level.DEBUG, rootLogger.level)
        appender = ListAppender()
        appender.start()
        rootLogger.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        rootLogger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `e with throwable emits ERROR level event visible under DEBUG root`() {
        val impl = KotlinLoggingLogImpl()
        val throwable = IllegalStateException("boom-detail")

        impl.e("MyTag", throwable) { "failure message" }

        assertEquals(1, appender.list.size, "应产生且仅产生一条日志事件")
        val event = appender.list.first()

        // 级别为 ERROR（root 为 DEBUG 时依然可见）
        assertEquals(Level.ERROR, event.level)

        // 消息含 tag 前缀与消息文本
        assertTrue(event.formattedMessage.contains("MyTag"), "消息应含 tag 前缀，实际: ${event.formattedMessage}")
        assertTrue(event.formattedMessage.contains("failure message"), "消息应含正文，实际: ${event.formattedMessage}")

        // 事件携带 throwable（类名与消息可定位到原异常）
        val proxy = assertNotNull(event.throwableProxy, "事件应携带异常堆栈")
        assertEquals(IllegalStateException::class.java.name, proxy.className)
        assertTrue(proxy.message?.contains("boom-detail") == true, "异常消息应保留，实际: ${proxy.message}")
        assertTrue(proxy.stackTraceElementProxyArray?.isNotEmpty() == true, "异常应带堆栈")
    }

    @Test
    fun `e with throwable keeps message format aligned with other levels`() {
        val impl = KotlinLoggingLogImpl()
        val throwable = RuntimeException("plain")

        impl.e("FmtTag", throwable) { "body-text" }

        val event = appender.list.first()
        assertEquals(Level.ERROR, event.level)
        assertEquals("FmtTag: body-text", event.formattedMessage)
    }
}
