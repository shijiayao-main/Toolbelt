// 任务 25：observability.logConsole.enabled 门控生效测试。
// 🔴 验证 enabled=true 时 LogBus 收到事件、enabled=false 时收不到（且 delegate 不受影响）。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.BridgeLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogConsoleEnabledTest {

    /** 录制型 LogBus 假件：记录所有 publish 调用。 */
    private class RecordingLogBus : LogBus {
        val published = CopyOnWriteArrayList<LogLine>()
        private val versionCounter = AtomicLong(0)
        override val capacity: Int = 1000
        override val version: Long get() = versionCounter.get()
        override val size: Int get() = published.size
        override val droppedTotal: Long get() = 0L
        override val publishedTotal: Long get() = published.size.toLong()
        override fun publish(line: LogLine) { published.add(line); versionCounter.incrementAndGet() }
        override fun publish(level: String, tag: String, message: String) {
            publish(LogLine(seq = 0L, level = level, tag = tag, message = message, atMillis = System.currentTimeMillis()))
        }
        override fun snapshot(): List<LogLine> = published.toList()
        override fun snapshotVersion(): Long = published.size.toLong()
        override fun clear() { published.clear() }
    }

    /** 录制型 delegate：记录所有输出。 */
    private class RecordingDelegate : BridgeLog {
        val messages = CopyOnWriteArrayList<String>()
        override fun debug(message: String) { messages.add("D:$message") }
        override fun info(message: String) { messages.add("I:$message") }
        override fun warn(message: String, error: Throwable?) { messages.add("W:$message") }
        override fun error(message: String, error: Throwable?) { messages.add("E:$message") }
    }

    @Test
    fun `enabled=true 时 LogBus 收到事件`() {
        val bus = RecordingLogBus()
        val delegate = RecordingDelegate()
        val log = ObservingBridgeLog(
            logBus = bus,
            delegate = delegate,
            logConsoleEnabled = { true },
        )

        log.info("hello")
        log.warn("warning")

        assertEquals(2, bus.published.size, "enabled=true 时 LogBus 应收到全部事件")
        assertEquals("hello", bus.published[0].message)
        assertEquals("warning", bus.published[1].message)
        // delegate 也收到
        assertEquals(2, delegate.messages.size)
    }

    @Test
    fun `enabled=false 时 LogBus 收不到事件`() {
        val bus = RecordingLogBus()
        val delegate = RecordingDelegate()
        val log = ObservingBridgeLog(
            logBus = bus,
            delegate = delegate,
            logConsoleEnabled = { false },
        )

        log.info("hello")
        log.warn("warning")
        log.error("err")
        log.debug("dbg")

        assertEquals(0, bus.published.size, "enabled=false 时 LogBus 不应收到任何事件")
    }

    @Test
    fun `enabled=false 时 delegate 仍正常输出（门控只影响 LogBus）`() {
        val bus = RecordingLogBus()
        val delegate = RecordingDelegate()
        val log = ObservingBridgeLog(
            logBus = bus,
            delegate = delegate,
            logConsoleEnabled = { false },
        )

        log.info("hello")
        log.error("err", RuntimeException("x"))

        assertEquals(0, bus.published.size, "LogBus 应被门控")
        assertEquals(2, delegate.messages.size, "delegate 不受门控影响")
        assertTrue(delegate.messages[0].contains("hello"))
        assertTrue(delegate.messages[1].contains("err"))
    }

    @Test
    fun `HOT-reactive：运行时切换 enabled 立即生效`() {
        val bus = RecordingLogBus()
        val enabled = AtomicBoolean(true)
        val log = ObservingBridgeLog(
            logBus = bus,
            logConsoleEnabled = { enabled.get() },
        )

        log.info("before")
        assertEquals(1, bus.published.size)

        // 热切换为 false
        enabled.set(false)
        log.info("during")
        assertEquals(1, bus.published.size, "切换为 false 后不应再喂入")

        // 热切换回 true
        enabled.set(true)
        log.info("after")
        assertEquals(2, bus.published.size, "切换回 true 后应恢复喂入")
    }

    @Test
    fun `withConsole 工厂方法正确传递 logConsoleEnabled`() {
        val bus = RecordingLogBus()
        val log = ObservingBridgeLog.withConsole(
            logBus = bus,
            logConsoleEnabled = { false },
        )

        log.info("should not appear in bus")
        assertEquals(0, bus.published.size, "withConsole 应正确传递 logConsoleEnabled provider")
    }

    @Test
    fun `tagged 派生实例继承 logConsoleEnabled`() {
        val bus = RecordingLogBus()
        val parent = ObservingBridgeLog(
            logBus = bus,
            logConsoleEnabled = { false },
        )
        val child = parent.tagged("SubTag")

        child.info("from child")
        assertEquals(0, bus.published.size, "tagged 派生应继承 logConsoleEnabled")
    }
}
