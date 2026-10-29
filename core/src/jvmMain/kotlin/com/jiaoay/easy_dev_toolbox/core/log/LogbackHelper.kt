package com.jiaoay.easy_dev_toolbox.core.log

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import kotlinx.coroutines.flow.MutableStateFlow
import org.slf4j.LoggerFactory

object LogbackHelper {
    private const val TAG = "LogbackHelper"

    // 控制台格式
    const val CONSOLE_PATTERN = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{35, 3} - %msg%n"

    // 输出日志格式
    private const val LOG_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{50} - %msg%n"
    private const val MAX_HISTORY = 15
    private const val TOTAL_SIZE_CAP = "500MB"

    private var _fileAppender: RollingFileAppender<ILoggingEvent>? = null

    private var _enableWriteFile: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val enableWriteFile = _enableWriteFile

    fun init() {
        val context = getContext() ?: return
        val rootLogger = getRootLogger() ?: return
        context.reset()
        val consoleAppender = createConsoleAppender()

        rootLogger.addAppender(consoleAppender)

        // 最近日志环形缓冲（崩溃诊断报告数据源）：与控制台同挂 root，只收录实际发射的事件
        val recentAppender = RecentLogAppender()
        recentAppender.name = "RECENT_BUFFER"
        recentAppender.start()
        rootLogger.addAppender(recentAppender)

        // 3. 设置全局日志级别
        rootLogger.level = Level.DEBUG

        // 4. 压制飞书 SDK 日志噪音：com.lark.oapi 在 INFO/DEBUG 下会打印大量长连接帧与出站调用日志，淹没应用日志。
        //    必须放在上面的 context.reset() 之后，否则具名 logger 配置会被 reset 清空。
        context.getLogger("com.lark.oapi").level = Level.WARN
        // 5. 压到 ERROR：ws 层对未订阅事件（已读回执 im.message.message_read_v1、
        //    bot_p2p_chat_entered_v1 等）每条打一行 WARN "not found handler"，聊天活跃时刷屏；
        //    断线/重连有应用侧 Reconnecting 信号兜底，ws 层真 WARN 可吞。
        //    注：CLI fat-jar 无 logback 配置，不受此控制（docs/pages/lark-bridge.md「事实 4」）。
        context.getLogger("com.lark.oapi.ws").level = Level.ERROR
        // 6. 压制 Koog agent 框架 dump：真机日志里框架 DEBUG 巨量刷屏——
        //    `Response: Assistant(parts=[...])` LLM 全文、`Received tool result:` 工具结果全文、
        //    `Executing node` / `Completed node`。logback logger 名 = 类全限定名，`ai.koog`
        //    前缀统一压住；`Executing tool (name: ...)` 是 INFO 保留——每次工具调用一行，排障有用。
        //    注：CLI fat-jar 无 logback 配置不受此控制（docs/pages/lark-bridge.md「事实 4」同款先例）。
        context.getLogger("ai.koog").level = Level.INFO
    }

    fun enableFileLog() {
        if (_enableWriteFile.value) return
        val root = LogbackHelper.getRootLogger() ?: return
        val fileAppender = LogbackHelper.getFileAppender(create = true) ?: return
        JLog.i(TAG) { "enableFileLog" }
        root.addAppender(fileAppender)
        _enableWriteFile.value = true
    }

    fun disableFileLog() {
        if (!_enableWriteFile.value) return
        val fileAppender = LogbackHelper.getFileAppender(create = false) ?: return
        LogbackHelper.getRootLogger()?.detachAppender(fileAppender)
        JLog.i(TAG) { "disableFileLog" }
        _enableWriteFile.value = false
    }

    fun onDestroy() {
        getFileAppender(create = false)?.encoder?.stop()
        getFileAppender(create = false)?.stop()
    }

    fun getRootLogger(): Logger? {
        val factory = LoggerFactory.getILoggerFactory() ?: return null
        return factory.getLogger(Logger.ROOT_LOGGER_NAME) as? Logger
    }

    private fun createConsoleAppender(): ConsoleAppender<ILoggingEvent>? {
        val context = getContext() ?: return null

        val encoder = PatternLayoutEncoder()
        encoder.context = context
        encoder.pattern = CONSOLE_PATTERN
        encoder.charset = Charsets.UTF_8
        encoder.start()

        val appender = ConsoleAppender<ILoggingEvent>()
        appender.context = context
        appender.name = "CONSOLE"
        appender.setEncoder(encoder)
        appender.start()
        return appender
    }

    private fun getFileAppender(create: Boolean): RollingFileAppender<ILoggingEvent>? {
        if (_fileAppender == null && create) {
            _fileAppender = createFileAppender()
        }
        return _fileAppender
    }

    private fun createFileAppender(): RollingFileAppender<ILoggingEvent>? {
        val logDir = FileUtils.getLogDir()
        val context = getContext() ?: return null
        // 1. 创建编码器
        val encoder = PatternLayoutEncoder()
        encoder.setContext(context)
        encoder.pattern = LOG_PATTERN
        encoder.charset = Charsets.UTF_8
        encoder.start()

        // 2. 创建轮转策略
        val rollingPolicy = TimeBasedRollingPolicy<ILoggingEvent>()
        rollingPolicy.setContext(context)
        rollingPolicy.fileNamePattern = "${logDir.absolutePath}/app-%d{yyyy-MM-dd}.log"
        rollingPolicy.maxHistory = MAX_HISTORY
        rollingPolicy.setTotalSizeCap(FileSize.valueOf(TOTAL_SIZE_CAP))

        // 3. 创建文件Appender
        val appender = RollingFileAppender<ILoggingEvent>()
        appender.setContext(context)
        appender.name = "FILE"
        appender.file = "${logDir.absolutePath}/app.log"
        appender.encoder = encoder
        appender.setRollingPolicy(rollingPolicy)

        rollingPolicy.setParent(appender)
        rollingPolicy.start()
        appender.start()

        return appender
    }

    private fun getContext(): LoggerContext? {
        return LoggerFactory.getILoggerFactory() as? LoggerContext
    }
}