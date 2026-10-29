// observe：BridgeLog 的双写实现（日志台 + 委托）。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.PrintStreamBridgeLog
import java.io.PrintStream

/**
 * 双写日志实现：**UI 日志台（[LogBus] 环形缓冲）+ 委托（控制台 / 宿主文件日志）**。
 *
 * ## 级别过滤的两层分工
 * - [busMinLevel]：**日志台**的下限（默认 DEBUG = 全收）。UI 侧要能看细节，故默认不过滤。
 * - `delegate` 自带下限（[PrintStreamBridgeLog] 的 `minLevel`，默认 INFO）：控制台不必被 DEBUG 刷屏。
 * 两层独立 ⇒ 「控制台安静但 UI 可回看 DEBUG」这一旧行为得以保留。
 *
 * ## `logConsoleEnabled` 门控
 * [logConsoleEnabled] provider 控制是否向 LogBus 喂入事件（旧 UI 的"观察"开关语义）。
 * `enabled=false` 时 [emit] 跳过 `logBus.publish`，但 delegate 仍无条件输出（控制台/文件日志不受影响）。
 * provider 由装配期注入，读 `configRef.get().observability.logConsole.enabled` ⇒ HOT-reactive（配置热替换即刻生效）。
 *
 * ## 零阻塞 / 零锁
 * [LogBus.publish] 是 O(1) 非挂起（容量 1000 + version + droppedTotal），[RingLogBus] 全原子量实现 ⇒ 本实现可安全地
 * 在 intake 单协程与 task 协程里直接调用，不会引入锁或阻塞。
 *
 * 本类**无可变字段**（全部 `private val`），不构成全局可变状态。
 *
 * @param logBus 日志台数据面（UI 订阅 [LogBus.snapshot]/[LogBus.version]）
 * @param delegate 二级输出（默认 [BridgeLog.Silent] = 只进日志台；控制台场景传 [PrintStreamBridgeLog]）
 * @param tag 本实例的日志标签（旧接口是每次调用传 tag，新接口按实例固定）
 * @param busMinLevel 进日志台的最低级别
 * @param logConsoleEnabled 日志台开关 provider（`observability.logConsole.enabled`）；false 时不喂 LogBus
 * @param clock 时间源（可注入；测试用固定时钟断言 `atMillis`）
 */
class ObservingBridgeLog(
    private val logBus: LogBus,
    private val delegate: BridgeLog = BridgeLog.Silent,
    private val tag: String = DEFAULT_TAG,
    private val busMinLevel: PrintStreamBridgeLog.Level = PrintStreamBridgeLog.Level.DEBUG,
    private val logConsoleEnabled: () -> Boolean = { true },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BridgeLog {

    /** 本实例的标签（诊断/测试用）。 */
    val currentTag: String get() = tag

    override fun debug(message: String) = emit(PrintStreamBridgeLog.Level.DEBUG, message, null)

    override fun info(message: String) = emit(PrintStreamBridgeLog.Level.INFO, message, null)

    override fun warn(message: String, error: Throwable?) = emit(PrintStreamBridgeLog.Level.WARN, message, error)

    override fun error(message: String, error: Throwable?) = emit(PrintStreamBridgeLog.Level.ERROR, message, error)

    /**
     * 派生一个带不同 [tag] 的视图（共享同一 [logBus]、[delegate] 与 [logConsoleEnabled]）。
     * 旧 `BridgeLogger.d(tag) { ... }` 的每次调用传 tag，在新接口下用「按子系统派生实例」表达；
     *    派生是纯构造（无状态），可在装配期一次性建好，热路径不再 new。
     */
    fun tagged(tag: String): ObservingBridgeLog =
        ObservingBridgeLog(logBus, delegate, tag, busMinLevel, logConsoleEnabled, clock)

    private fun emit(level: PrintStreamBridgeLog.Level, message: String, error: Throwable?) {
        // 任务 25 门控：logConsoleEnabled=false 时跳过 LogBus 喂入（delegate 仍无条件输出）。
        if (logConsoleEnabled() && level.ordinal >= busMinLevel.ordinal) {
            // 🔴 日志台侧的失败绝不影响委托侧输出（旁路语义），故 runCatching 包住
            runCatching {
                logBus.publish(
                    LogLine(
                        seq = 0L, // 由 RingLogBus 统一发放
                        level = RingLogBus.levelLiteral(level),
                        tag = tag,
                        message = render(message, error),
                        atMillis = clock(),
                    ),
                )
            }
        }
        // 委托侧（🔴 穷尽 when 无 else：新增级别时本文件编译失败）
        when (level) {
            PrintStreamBridgeLog.Level.DEBUG -> delegate.debug(message)
            PrintStreamBridgeLog.Level.INFO -> delegate.info(message)
            PrintStreamBridgeLog.Level.WARN -> delegate.warn(message, error)
            PrintStreamBridgeLog.Level.ERROR -> delegate.error(message, error)
        }
    }

    companion object {
        /** 默认日志标签（旧 `StdoutBridgeLogger` 无固定 tag，此处定一个便于 UI 分组）。 */
        const val DEFAULT_TAG: String = "LarkBridge"

        /**
         * 🔒 异常拼接形态（旧 `BusBridgeLogger.e` :85-88 逐字）：`<text> | <SimpleName>: <message>`。
         * [error] 为 null 时原样返回 [message]。
         */
        fun render(message: String, error: Throwable?): String =
            if (error == null) {
                message
            } else {
                buildString {
                    append(message)
                    append(" | ").append(error.javaClass.simpleName).append(": ").append(error.message.orEmpty())
                }
            }

        /**
         * 装配便捷入口：日志台 + 控制台双写。
         * @param consoleMinLevel 控制台下限（默认 INFO；`observability.logConsole` 无级别字段时用它）
         * @param logConsoleEnabled 日志台开关 provider（任务 25）
         */
        fun withConsole(
            logBus: LogBus,
            out: PrintStream = System.err,
            consoleMinLevel: PrintStreamBridgeLog.Level = PrintStreamBridgeLog.Level.INFO,
            tag: String = DEFAULT_TAG,
            logConsoleEnabled: () -> Boolean = { true },
        ): ObservingBridgeLog = ObservingBridgeLog(
            logBus = logBus,
            delegate = PrintStreamBridgeLog(out, consoleMinLevel),
            tag = tag,
            logConsoleEnabled = logConsoleEnabled,
        )
    }
}
