package com.jiaoay.easy_dev_toolbox.core.log

/**
 * 崩溃诊断报告组装（纯函数，独立可测）。
 *
 * 输入 = 线程名 + 异常本体 + 日志尾部行集（[RecentLogBuffer.tail] 的输出），
 * 输出 = 单块可复制文本——「闪退」变「可报告」的最小信息集：异常类/消息、
 * 栈前 [STACK_FRAME_LIMIT] 帧、Caused by 链（限 [CAUSE_CHAIN_LIMIT] 层）、
 * 最近 [LOG_TAIL_LINES] 行日志。
 *
 * 截断上限是硬契约：崩溃现场栈深可达数百帧、日志尾部可达 [RecentLogBuffer.CAPACITY]
 * 行，报告直接进对话框/剪贴板，无界组装会把「可报告」变「卡死」。
 */
object CrashReport {

    /** 异常栈保留帧数上限（直接帧；Caused by 链另计） */
    const val STACK_FRAME_LIMIT = 12

    /** Caused by 链保留层数上限（同时是自引用环的退出护栏） */
    const val CAUSE_CHAIN_LIMIT = 3

    /** 日志尾部行数上限（由调用方经 [RecentLogBuffer.tail] 截取后传入） */
    const val LOG_TAIL_LINES = 40

    fun build(threadName: String, throwable: Throwable, logTail: List<String>): String {
        val sb = StringBuilder()
        sb.append("Toolbelt 未处理异常诊断报告\n")
        sb.append("线程: ").append(threadName).append('\n')
        sb.append("异常: ").append(throwable.javaClass.name)
        throwable.message?.let { sb.append(": ").append(it) }
        sb.append('\n')
        for (frame in throwable.stackTrace.take(STACK_FRAME_LIMIT)) {
            sb.append("    at ").append(frame).append('\n')
        }
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < CAUSE_CHAIN_LIMIT) {
            sb.append("Caused by: ").append(cause.javaClass.name)
            cause.message?.let { sb.append(": ").append(it) }
            sb.append('\n')
            cause = cause.cause
            depth++
        }
        if (logTail.isNotEmpty()) {
            sb.append("\n最近日志（尾部 ").append(logTail.size).append(" 行）:\n")
            for (line in logTail) sb.append(line).append('\n')
        }
        return sb.toString()
    }
}
