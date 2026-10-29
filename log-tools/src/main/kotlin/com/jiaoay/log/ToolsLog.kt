package com.jiaoay.log

/**
 * 库内失败上报：替代散落的 `e.printStackTrace()`。
 *
 * 为什么不走日志门面：:log-tools 是零日志依赖的纯 JVM lib（独立 CLI fat jar 纪律——
 * 引入 kotlin-logging/slf4j 会把 provider 拖进 21MB 的 shadow jar 并改变 CLI 输出流）。
 * 统一走 System.err 单行结构化输出：
 * - 桌面 App 内：stderr 可被宿主重定向/logback 捕获（旧 printStackTrace 的裸堆栈在
 *   编程式 logback 装配下文件日志收不到，且无 tag 无因由、不可 grep）；
 * - CLI 模式：stderr 与 stdout 业务输出分流（mordant 终端渲染走 stdout），`2>` 重定向即可收集。
 *
 * 异常链逐层展开（cause 链在单行内可见），截断 500 字符防爆行。
 */
internal object ToolsLog {

    private const val MAX_CHARS = 500

    /** 单行结构化失败上报：`[tools-fail] <where>: <类型>: <消息> (caused by <类型>: <消息>…)` */
    fun reportFailure(where: String, e: Throwable) {
        val sb = StringBuilder("[tools-fail] ").append(where).append(": ")
        var current: Throwable? = e
        var first = true
        while (current != null && sb.length < MAX_CHARS) {
            if (!first) sb.append(" (caused by ")
            sb.append(current.javaClass.simpleName).append(": ").append(current.message.orEmpty())
            if (!first) sb.append(")")
            current = current.cause
            first = false
        }
        System.err.println(sb.take(MAX_CHARS))
    }
}
