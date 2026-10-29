package com.jiaoay.easy_dev_toolbox.core.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * D2 回归：后台线程未捕获异常经全局兜底产生 ERROR 级日志（含线程名与堆栈），
 * 并委托原 default handler，handler 自身故障不引发二次异常。
 */
class GlobalUncaughtExceptionHandlerTest {

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var rootLogger: ch.qos.logback.classic.Logger

    @BeforeTest
    fun setUp() {
        // 接通真实日志管道：root DEBUG + ListAppender + KotlinLoggingLogImpl
        LogbackHelper.init()
        rootLogger = requireNotNull(LogbackHelper.getRootLogger()) { "logback root logger 不可用" }
        appender = ListAppender()
        appender.start()
        rootLogger.addAppender(appender)
        JLog.init(KotlinLoggingLogImpl())
    }

    @AfterTest
    fun tearDown() {
        // 恢复默认日志实现，避免污染同 JVM 其他测试
        JLog.init(DefaultLogImpl())
        rootLogger.detachAppender(appender)
        appender.stop()
    }

    /** 在独立后台线程抛未捕获异常，等 handler 完成 */
    private fun runOnThreadWithUncaught(
        handler: Thread.UncaughtExceptionHandler,
        throwable: RuntimeException,
    ): Thread {
        val thread = Thread { throw throwable }
        thread.uncaughtExceptionHandler = handler
        thread.start()
        thread.join(10_000)
        return thread
    }

    @Test
    fun `background thread uncaught exception logs ERROR with stack and delegates`() {
        val delegated = mutableListOf<Pair<Thread, Throwable>>()
        val handler = GlobalUncaughtExceptionHandler.create(
            delegate = Thread.UncaughtExceptionHandler { t, e -> delegated.add(t to e) },
        )
        val ex = RuntimeException("uncaught-boom")
        val thread = runOnThreadWithUncaught(handler, ex)

        // 产生 ERROR 级日志记录（含线程名与堆栈）
        val event = appender.list.firstOrNull { it.throwableProxy != null }
        assertNotNull(event, "应产生携带异常的日志事件")
        assertEquals(Level.ERROR, event.level)
        assertTrue(
            event.formattedMessage.contains(thread.name),
            "日志消息应含线程名，实际: ${event.formattedMessage}",
        )
        val proxy = event.throwableProxy!!
        assertEquals(RuntimeException::class.java.name, proxy.className)
        assertTrue(proxy.message?.contains("uncaught-boom") == true)
        assertTrue(proxy.stackTraceElementProxyArray?.isNotEmpty() == true, "异常应带完整堆栈")

        // 原 handler 收到委托调用（同一线程、同一异常实例）
        assertEquals(1, delegated.size, "原 handler 应被委托调用一次")
        assertSame(thread, delegated.first().first)
        assertSame(ex, delegated.first().second)
    }

    @Test
    fun `log failure does not prevent delegation and does not rethrow`() {
        val delegated = mutableListOf<Pair<Thread, Throwable>>()
        val handler = GlobalUncaughtExceptionHandler.create(
            delegate = Thread.UncaughtExceptionHandler { t, e -> delegated.add(t to e) },
            logError = { _, _ -> error("logging system broken") }, // 日志系统自身故障
        )
        val ex = RuntimeException("plain-boom")
        val thread = runOnThreadWithUncaught(handler, ex)

        // 自保护：日志故障不传播，delegate 仍被调用
        assertEquals(1, delegated.size)
        assertSame(thread, delegated.first().first)
        assertSame(ex, delegated.first().second)
    }

    @Test
    fun `null delegate still logs error`() {
        val handler = GlobalUncaughtExceptionHandler.create(delegate = null)
        val ex = RuntimeException("no-delegate-boom")
        runOnThreadWithUncaught(handler, ex)

        val event = appender.list.firstOrNull { it.throwableProxy != null }
        assertNotNull(event, "delegate 为 null 时也应产生 ERROR 日志")
        assertEquals(Level.ERROR, event.level)
    }

    /** 呈现接缝：presenter 收到含线程名/异常消息/日志尾部（含崩溃日志行本身）的报告，且先于 delegate */
    @Test
    fun `presenter receives diagnostic report before delegate`() {
        RecentLogBuffer.clear()
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        var received: String? = null
        val handler = GlobalUncaughtExceptionHandler.create(
            delegate = Thread.UncaughtExceptionHandler { _, _ -> order.add("delegate") },
            presenter = { report ->
                received = report
                order.add("presenter")
            },
        )
        JLog.i("TailProbe") { "before-crash-marker" }
        val thread = runOnThreadWithUncaught(handler, RuntimeException("report-boom"))

        val report = assertNotNull(received, "presenter 应收到诊断报告")
        assertTrue(report.contains(thread.name), "报告应含崩溃线程名，实际:\n$report")
        assertTrue(report.contains("report-boom"), "报告应含异常消息")
        assertTrue(report.contains("before-crash-marker"), "报告日志尾部应含崩溃前的既有日志行")
        assertTrue(
            report.contains("Uncaught exception in thread"),
            "报告日志尾部应含本次崩溃自身的 ERROR 日志行（日志先于报告组装的顺序契约）",
        )
        assertEquals(listOf("presenter", "delegate"), order, "呈现应先于委托（用户读完报告后平台崩溃语义才继续）")
        RecentLogBuffer.clear()
    }

    /** 呈现通道故障不得阻断委托：崩溃处理链上没有下一层兜底 */
    @Test
    fun `presenter failure does not prevent delegation and does not rethrow`() {
        val delegated = mutableListOf<Pair<Thread, Throwable>>()
        val handler = GlobalUncaughtExceptionHandler.create(
            delegate = Thread.UncaughtExceptionHandler { t, e -> delegated.add(t to e) },
            presenter = { error("presenter broken") },
        )
        val ex = RuntimeException("presenter-boom")
        val thread = runOnThreadWithUncaught(handler, ex)

        assertEquals(1, delegated.size, "呈现故障后 delegate 仍应被调用")
        assertSame(thread, delegated.first().first)
        assertSame(ex, delegated.first().second)
    }

    /**
     * 4.2 协程兜底验证：viewModelScope 结构（SupervisorJob + dispatcher）的协程未捕获
     * 异常最终到达 JVM default handler 并被本处理器记 ERROR 日志。
     *
     * 🔴 异常经 context 级 CoroutineExceptionHandler 显式转发到线程 default handler，
     * 不走「无 CEH 协程依赖 coroutines 内部回落」的路径——kotlinx-coroutines-test 的
     * ExceptionCollector（service 全局注册，首个 runTest 起激活）会收集 worker JVM 内
     * 所有未被 runTest 消化的协程未处理异常，真实回落路径的异常必然滞留收集器、
     * 毒化同 worker 后续 runTest（顺序型 flaky，DistillIngestTest 曾被记帐）；
     * context CEH 令 coroutines 判定异常已处理、不进收集器。回落管线
     * （handleCoroutineExceptionImpl → 线程 uncaughtExceptionHandler）属 kotlinx 公开
     * 契约，不由本测试钉死。
     * 🕳 覆盖缺口登记：该回落路径在本测试源集内**无自动化守护**——同一 JVM 内任何
     * 无-CEH 协程异常都会被收集器截收、滞留并毒化同 worker 后续 runTest，无法在
     * 不引毒的前提下断言（如需覆盖须建独立测试源集隔离 classpath）。
     */
    @Test
    fun `coroutine uncaught exception reaches JVM default handler and logs`() {
        val originalDefault = Thread.getDefaultUncaughtExceptionHandler()
        val latch = java.util.concurrent.CountDownLatch(1)
        val logged = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalUncaughtExceptionHandler.create(
                delegate = null,
                logError = { _, throwable ->
                    logged.add(throwable)
                    latch.countDown()
                },
            ),
        )
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
                    // 转发即 coroutines 对「context 携带 CEH」异常的标准处理路径；
                    // default handler 此时仍是本测试安装的 GlobalUncaughtExceptionHandler
                    Thread.getDefaultUncaughtExceptionHandler()
                        ?.uncaughtException(Thread.currentThread(), throwable)
                },
        )
        try {
            scope.launch {
                throw RuntimeException("coroutine-boom")
            }
            assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS), "协程未捕获异常应到达 JVM default handler")
            assertEquals("coroutine-boom", logged.first().message)
        } finally {
            // scope 收尾：取消在途协程 + 恢复原 default handler，防跨测试污染
            scope.cancel()
            Thread.setDefaultUncaughtExceptionHandler(originalDefault)
        }
    }

    // ── 良性环境噪音分流（AWT 托盘支持态误报——Linux 桌面 XTrayIconPeer 延迟初始化）──

    /** 实测形态：isTraySupported 启动期返回 true，XTrayIconPeer 延迟初始化后再查 getSystemTray 即抛（Linux 桌面） */
    private fun trayQuirkException(): UnsupportedOperationException = UnsupportedOperationException(
        "The system tray is not supported on the current platform.",
    ).apply {
        stackTrace = arrayOf(
            StackTraceElement("sun.awt.X11.XTrayIconPeer$3$1", "run", null, -1),
            StackTraceElement("java.desktop/java.awt.event.InvocationEvent", "dispatch", null, -1),
        )
    }

    @Test
    fun `benign tray quirk skips dialog and keeps delegate with warn log`() {
        val delegated = mutableListOf<Pair<Thread, Throwable>>()
        val presented = mutableListOf<String>()
        val handler = GlobalUncaughtExceptionHandler.create(
            delegate = Thread.UncaughtExceptionHandler { t, e -> delegated.add(t to e) },
            presenter = { presented.add(it) },
        )
        val ex = trayQuirkException()

        handler.uncaughtException(Thread.currentThread(), ex)

        assertTrue(presented.isEmpty(), "良性噪音不得呈现模态诊断报告（纯对话框噪音）：$presented")
        assertEquals(1, delegated.size, "委托保持平台既有语义（不吞异常）")
        val warnEvent = appender.list.lastOrNull { it.level == Level.WARN }
        assertNotNull(warnEvent, "良性噪音应留 WARN 级日志痕迹")
        assertTrue(
            warnEvent.formattedMessage.contains("良性环境噪音"),
            "WARN 消息应说明跳过原因，实际: ${warnEvent.formattedMessage}",
        )
    }

    @Test
    fun `same signature without tray peer in stack is not benign`() {
        val presented = mutableListOf<String>()
        val handler = GlobalUncaughtExceptionHandler.create(
            delegate = null,
            presenter = { presented.add(it) },
        )
        // 同 message 但堆栈无 XTrayIconPeer——可能来自应用自己的 getSystemTray 调用，
        // 不是 AWT 延迟初始化噪音，必须照常呈现
        handler.uncaughtException(
            Thread.currentThread(),
            UnsupportedOperationException("The system tray is not supported on the current platform."),
        )
        assertEquals(1, presented.size, "非托盘 peer 来源的同签名异常照常呈现诊断报告")
    }
}
