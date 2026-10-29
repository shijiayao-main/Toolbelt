package com.jiaoay.easy_dev_toolbox.core.log

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 进程内最近日志环形缓冲（崩溃诊断报告的日志尾部数据源）。
 *
 * 形态 = logback appender（[RecentLogAppender]）挂在真实日志管道上：只收录
 * **实际发射**的事件——被级别过滤/惰性消息未求值的日志不占缓冲，热路径零额外
 * 求值成本。缓冲容量 [CAPACITY] 行，满后丢最旧。
 *
 * 日志后端初始化之前（启动极早期）缓冲为空：此时 [tail] 返回空列表，诊断报告
 * 的日志尾部段留空、不阻断组装。
 */
object RecentLogBuffer {

    /** 缓冲容量（行）；崩溃报告经 [tail] 只取其尾部子集 */
    const val CAPACITY = 500

    // logback appender 可从任意业务线程并发进入（root logger 无锁聚合），读写同锁串行化
    private val lock = Any()

    private val lines = ArrayDeque<String>(CAPACITY)

    /** 追加一行（供 [RecentLogAppender] 调用；测试可直接注入） */
    internal fun record(line: String) {
        synchronized(lock) {
            if (lines.size >= CAPACITY) lines.removeFirst()
            lines.addLast(line)
        }
    }

    /** 返回最近 [n] 行（时间升序，最旧在前；n 非负由调用方保证——崩溃路径不做防御性求值） */
    fun tail(n: Int): List<String> {
        synchronized(lock) {
            if (n >= lines.size) return lines.toList()
            return lines.subList(lines.size - n, lines.size).toList()
        }
    }

    /** 清空缓冲（测试隔离用；生产路径无调用方） */
    fun clear() {
        synchronized(lock) { lines.clear() }
    }
}

/**
 * [RecentLogBuffer] 的 logback 接入端：由 [LogbackHelper.init] 挂到 root logger，
 * 只做「格式化成单行 + 入环」，不做过滤/落盘（那是其他 appender 的职责）。
 *
 * 异常栈不入缓冲：行内只保留消息正文（崩溃报告另附异常本体完整栈，重复入环
 * 会挤占正常日志尾部的有效行数）。
 */
internal class RecentLogAppender : AppenderBase<ILoggingEvent>() {

    override fun append(event: ILoggingEvent) {
        RecentLogBuffer.record(formatLine(event))
    }

    companion object {
        // DateTimeFormatter 不可变线程安全（SimpleDateFormat 非线程安全，appender 多线程进入）
        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        private fun formatLine(event: ILoggingEvent): String {
            val time = timeFormat.format(Instant.ofEpochMilli(event.timeStamp))
            val level = event.level.toString().padEnd(5)
            return "$time [${event.threadName}] $level ${event.formattedMessage}"
        }
    }
}
