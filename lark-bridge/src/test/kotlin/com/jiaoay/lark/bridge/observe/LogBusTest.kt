// M4-T8 第一批 · observe 组测试（4/8）：日志台环形缓冲 + 观测型 BridgeLog + 截断工具。
// 🔴 移植自旧 `log/BridgeLogBusTest.kt`（3 条）与旧 `BusBridgeLoggerTest`（1 条）——**4 条逐条移植**。
// 🔴 BC 对应：BC-323..BC-330（日志台 replay / 超量只留最近 / logger 双写）+ D18（覆盖必须计数）
//    + 有界内存链路第 5 行（`observability.logConsole.capacity=1000`，DROP_OLDEST）。
// 🔴 旧实现是 `MutableSharedFlow(replay=200, extraBufferCapacity=..., onBufferOverflow=DROP_OLDEST)`：
//    每条日志都要走一次 Flow 收集器调度。新实现是**零锁环形缓冲 + 版本号**，UI 以 1Hz 拉快照。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.PrintStreamBridgeLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogBusTest {

    // ── 旧 BridgeLogBusTest 3 条逐条移植（replay 语义 → 环形缓冲语义）──

    @Test
    fun `publish 后订阅者可收到`() {
        val bus = RingLogBus()
        bus.publish(RingLogBus.LEVEL_INFO, "tag", "hello")
        // 🔒 旧断言「replay 生效：新订阅者可立即收到」的等价：快照里立刻有这一行
        val line = bus.snapshot().single()
        assertEquals("hello", line.message)
        assertEquals(RingLogBus.LEVEL_INFO, line.level)
        assertEquals("INFO", line.level)
        assertEquals("tag", line.tag)
        assertEquals(1L, line.seq)
        assertEquals(1L, bus.version)
        assertEquals(1L, bus.publishedTotal)
        assertEquals(1, bus.size)
    }

    @Test
    fun `replay 保留最近事件`() {
        val bus = RingLogBus(capacity = 1_000)
        repeat(10) { bus.publish(RingLogBus.LEVEL_DEBUG, "t", "msg-$it") }
        val received = bus.snapshot()
        // 🔒 旧断言逐字：最旧的 10 条都保留（未超上限）
        assertEquals(10, received.size)
        assertEquals("msg-0", received.first().message)
        assertEquals("msg-9", received.last().message)
        assertEquals((1..10).map { it.toLong() }, received.map { it.seq }, "快照必须按 seq 升序")
        assertEquals(0L, bus.droppedTotal, "未满 ⇒ 零丢弃")
    }

    @Test
    fun `超量发布后只保留最近 capacity 条`() {
        // 🔒 旧 `REPLAY_COUNT=200`，300 条 ⇒ 只留 msg-100..msg-299。新架构容量取
        //    `observability.logConsole.capacity`（默认 1000），此处用 200 复现旧断言形态。
        val bus = RingLogBus(capacity = 200)
        assertEquals(200, bus.capacity)
        assertEquals(1_000, RingLogBus.DEFAULT_CAPACITY, "🔴 默认容量对齐 logConsole.capacity=1000")
        repeat(300) { bus.publish(RingLogBus.LEVEL_DEBUG, "t", "msg-$it") }
        val received = bus.snapshot()
        assertEquals(200, received.size)
        // 🔒 旧断言逐字：最旧的被丢弃，最新的保留
        assertEquals("msg-100", received.first().message)
        assertEquals("msg-299", received.last().message)
        assertEquals(300L, bus.publishedTotal)
        assertEquals(100L, bus.droppedTotal, "🔴 D18：覆盖 = 丢弃，必须计数")
        assertEquals(300L, bus.version, "每次 publish 都 +1（UI 据此判断有新行）")
    }

    @Test
    fun `logger 写入总线并转发 delegate`() {
        // 🔒 旧 `BusBridgeLoggerTest` 的等价：双写（总线 + delegate），且异常按旧形态拼接
        val bus = RingLogBus()
        val written = mutableListOf<String>()
        val delegate = object : BridgeLog {
            override fun debug(message: String) { written.add("D:$message") }
            override fun info(message: String) { written.add("I:$message") }
            override fun warn(message: String, error: Throwable?) { written.add("W:${ObservingBridgeLog.render(message, error)}") }
            override fun error(message: String, error: Throwable?) { written.add("E:${ObservingBridgeLog.render(message, error)}") }
        }
        val logger = ObservingBridgeLog(logBus = bus, delegate = delegate, tag = "tag", clock = { 5_000L })

        logger.info("info-msg")
        logger.error("err-msg", RuntimeException("boom"))
        logger.debug("debug-msg")
        logger.warn("warn-msg")

        assertEquals(4, written.size)
        assertEquals("I:info-msg", written[0])
        assertEquals("E:err-msg | RuntimeException: boom", written[1])
        assertEquals("D:debug-msg", written[2])
        assertEquals("W:warn-msg", written[3])

        // 总线侧：4 行、级别字面值正确、tag 一致、时间来自注入时钟
        val lines = bus.snapshot()
        assertEquals(4, lines.size)
        assertEquals(listOf("INFO", "ERROR", "DEBUG", "WARN"), lines.map { it.level })
        assertEquals(listOf("info-msg", "err-msg | RuntimeException: boom", "debug-msg", "warn-msg"), lines.map { it.message })
        assertTrue(lines.all { it.tag == "tag" })
        assertTrue(lines.all { it.atMillis == 5_000L })
        assertEquals("tag", logger.currentTag)
        // 🔴 tagged() 派生实例共用同一条总线，但 tag 不同（子域分组）
        val child = logger.tagged("Agent")
        assertEquals("Agent", child.currentTag)
        child.info("from-agent")
        assertEquals(5, bus.size)
        assertEquals("Agent", bus.snapshot().last().tag)
    }

    // ── 新增：环形缓冲 / 版本号 / clear 语义 ──

    @Test
    fun `clear 幂等且 version 与 droppedTotal 不归零`() {
        val bus = RingLogBus(capacity = 3)
        repeat(5) { bus.publish(RingLogBus.LEVEL_INFO, "t", "m$it") }
        assertEquals(2L, bus.droppedTotal)
        val versionBefore = bus.version
        bus.clear()
        assertEquals(0, bus.size)
        assertEquals(emptyList(), bus.snapshot())
        assertEquals(versionBefore + 1, bus.version, "🔴 clear 让 version +1，UI 才能察觉「清空过」并重组")
        assertEquals(2L, bus.droppedTotal, "🔴 D18：clear 不得抹掉丢弃历史")
        assertEquals(5L, bus.publishedTotal)
        bus.clear() // 幂等
        assertEquals(0, bus.size)
        // clear 后继续 publish：seq 单调递增（不与旧行抢 seq）
        bus.publish(RingLogBus.LEVEL_INFO, "t", "after")
        assertEquals(6L, bus.snapshot().single().seq)
    }

    @Test
    fun `publish 传入的 seq 被实现覆写为自增值`() {
        val bus = RingLogBus()
        // 🔴 调用方（ObservingBridgeLog）不持有全局 seq，一律由总线发放 ⇒ 不会重号
        bus.publish(LogLine(seq = 999L, level = "INFO", tag = "t", message = "a", atMillis = 1L))
        bus.publish(LogLine(seq = 0L, level = "INFO", tag = "t", message = "b", atMillis = 2L))
        assertEquals(listOf(1L, 2L), bus.snapshot().map { it.seq })
    }

    @Test
    fun `容量下限为 1 且级别字面值与旧 LogLevel 一致`() {
        val tiny = RingLogBus(capacity = -5)
        repeat(3) { tiny.publish(RingLogBus.LEVEL_INFO, "t", "m$it") }
        assertEquals(1, tiny.size)
        assertEquals("m2", tiny.snapshot().single().message, "容量 1 ⇒ 只留最新一行")
        assertEquals(2L, tiny.droppedTotal)
        // 🔒 旧 `model/LogLevel` 的 4 个值 → 字面值
        assertEquals("DEBUG", RingLogBus.levelLiteral(PrintStreamBridgeLog.Level.DEBUG))
        assertEquals("INFO", RingLogBus.levelLiteral(PrintStreamBridgeLog.Level.INFO))
        assertEquals("WARN", RingLogBus.levelLiteral(PrintStreamBridgeLog.Level.WARN))
        assertEquals("ERROR", RingLogBus.levelLiteral(PrintStreamBridgeLog.Level.ERROR))
        assertEquals(4, PrintStreamBridgeLog.Level.entries.size, "新增级别时 levelLiteral 的穷尽 when 会编译失败")
        assertTrue(tiny.toString().contains("RingLogBus"))
    }

    @Test
    fun `总线在多线程并发 publish 下不丢计数且不越界`() {
        val bus = RingLogBus(capacity = 64)
        val threads = (0 until 8).map { i ->
            Thread { repeat(500) { bus.publish(RingLogBus.LEVEL_INFO, "t$i", "m$it") } }.apply { start() }
        }
        threads.forEach { it.join() }
        // 🔴 零锁环形：4000 次 publish 全部计数，缓冲恒 <= capacity
        assertEquals(4_000L, bus.publishedTotal)
        assertEquals(4_000L, bus.version)
        assertEquals(4_000L - 64L, bus.droppedTotal)
        assertEquals(64, bus.size)
        assertEquals(64, bus.snapshot().size)
        assertEquals(64, bus.snapshot().map { it.seq }.distinct().size, "seq 不得重号")
    }

    // ── ObservingBridgeLog 的拼接形态（旧 BusBridgeLogger.e :85-88 逐字）──

    @Test
    fun `render 的异常拼接形态逐字对齐旧实现`() {
        assertEquals("纯消息", ObservingBridgeLog.render("纯消息", null))
        assertEquals("炸了 | IllegalStateException: boom", ObservingBridgeLog.render("炸了", IllegalStateException("boom")))
        // 🔒 message 为 null 的异常 ⇒ 空串（旧 `.orEmpty()`）
        assertEquals("炸了 | RuntimeException: ", ObservingBridgeLog.render("炸了", RuntimeException()))
        // 嵌套 cause 不展开（旧实现只取 SimpleName + message）
        val nested = RuntimeException("outer", IllegalStateException("inner"))
        assertEquals("炸了 | RuntimeException: outer", ObservingBridgeLog.render("炸了", nested))
        assertEquals("LarkBridge", ObservingBridgeLog.DEFAULT_TAG)
    }

    @Test
    fun `withConsole 双写且受级别下限过滤`() {
        val bus = RingLogBus()
        val out = ByteArrayOutputStream()
        val logger = ObservingBridgeLog.withConsole(bus, PrintStream(out, true, "UTF-8"), PrintStreamBridgeLog.Level.INFO)
        logger.debug("不该上控制台")
        logger.info("该上控制台")
        logger.error("错误", IllegalStateException("x"))
        // 🔴 总线侧**不受**控制台级别下限影响（busMinLevel 默认 DEBUG）⇒ 3 行全在
        assertEquals(3, bus.size)
        assertEquals(listOf("DEBUG", "INFO", "ERROR"), bus.snapshot().map { it.level })
        val printed = out.toString("UTF-8")
        assertFalse(printed.contains("不该上控制台"), "控制台必须按 minLevel 过滤：$printed")
        assertTrue(printed.contains("该上控制台"))
        assertTrue(printed.contains("IllegalStateException: x"))
    }

    // ── StringTruncation（审计/进度共用的码点截断）──

    @Test
    fun `truncate 按码点截断且 ellipsis 可配`() {
        assertEquals("abc…", StringTruncation.truncate("abcdef", 4))
        assertEquals("abc", StringTruncation.truncate("abcdef", 3, ellipsis = ""))
        assertEquals("abc", StringTruncation.truncate("abc", 3))
        assertEquals("abc", StringTruncation.truncate("abc", 10))
        assertEquals("", StringTruncation.truncate("", 5))
        // 🔴 按**码点**而非 char：emoji（代理对）不得被切成半个
        val emoji = "🚀🚀🚀🚀"
        val cut = StringTruncation.truncate(emoji, 3)
        assertEquals("🚀🚀…", cut)
        assertEquals(3, cut.codePointCount(0, cut.length))
        assertTrue(cut.codePoints().allMatch { it == 0x1F680 || it == '…'.code }, "不得出现孤立代理项")
        // maxChars <= 1 的边界：不抛，退化到空/单字符
        assertNotNull(StringTruncation.truncate("abcdef", 1))
        assertNotNull(StringTruncation.truncate("abcdef", 0))
    }
}
