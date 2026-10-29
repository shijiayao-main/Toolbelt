package com.jiaoay.easy_dev_toolbox.core.log

/**
 * JVM 全局未捕获异常兜底。
 *
 * 在 main 入口注册 [Thread.setDefaultUncaughtExceptionHandler]：未捕获异常依次
 * ① 以 JLog ERROR 级记录（含线程名与完整堆栈）→ ② 组装诊断报告并呈现
 * （[CrashReportDialog]，异常栈 + 最近日志尾部 + 一键复制）→ ③ 委托原 default
 * handler 保持平台既有崩溃语义（不吞异常）。注册点位于 application {} 之前，
 * 此时 JLog 尚未 init，回落 DefaultLogImpl（stdout），保证初始化前异常不丢。
 *
 * 自保护硬约束：uncaughtException 内任何一段都不得向外抛异常——handler 内抛出的
 * 二次异常没有下一层兜底，会顶替原异常成为进程的真实死因。三段各自独立 try-catch，
 * 任一段故障不阻断后续段；报告组装（②内层）同样被包住，组装故障只是少一个
 * 对话框，①日志与③委托照常执行。
 *
 * 协程兜底策略：viewModelScope 等作用域的未捕获异常最终传播至 dispatcher 线程，
 * 已被本 handler 覆盖；仅绕开 ViewModel 作用域的独立协程需调用方自行注入
 * CoroutineExceptionHandler。
 */
object GlobalUncaughtExceptionHandler {

    private const val TAG = "Uncaught"

    /**
     * 已知良性环境噪音识别：环境支持态启动后变更的异常——应用无感知、无后续伤害，
     * 呈现模态诊断对话框只会制造噪音。命中 = 跳过报告与呈现（日志留痕 + 委托保持
     * 平台语义）。
     */
    internal fun isKnownBenignEnvironmentQuirk(e: Throwable): Boolean {
        // AWT 托盘支持态误报：部分 Linux 桌面（XWayland 代理 SystemTray 协议但桌面
        // 未装 AppIndicator 扩展，或托盘管理器会话中段消亡）启动期 isTraySupported=true、
        // XTrayIconPeer 延迟初始化再查 getSystemTray 即抛——托盘缺席是该桌面既有状态，
        // Toolbelt 内托盘仅「常驻标识+退出」装饰面，应用本体无感知
        // 帧类名匹配用 contains：真实帧是匿名内部类形态（如 XTrayIconPeer$3$1），
        // endsWith("XTrayIconPeer") 对它恒不命中
        return e is UnsupportedOperationException &&
            e.message?.contains("system tray is not supported") == true &&
            e.stackTrace.any { it.className.contains("XTrayIconPeer") }
    }

    /** 注册全局兜底（应用入口调用一次）；呈现通道 = [CrashReportDialog] */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            create(delegate = previous, presenter = ::presentCrashReport),
        )
    }

    /**
     * 构造处理器实例（独立可测）。
     *
     * @param delegate 记录与呈现完成后委托的原 handler（null 则跳过）
     * @param logError 日志动作，默认走 JLog ERROR 级；测试可注入
     * @param logBenign 良性噪音日志动作，默认走 JLog WARN 级；测试可注入
     * @param presenter 诊断报告呈现动作；测试默认 no-op——真对话框是模态阻塞的，
     *   单测注入默认值不得弹出真实窗口
     */
    internal fun create(
        delegate: Thread.UncaughtExceptionHandler?,
        logError: (thread: Thread, throwable: Throwable) -> Unit = ::defaultLogError,
        logBenign: (thread: Thread, throwable: Throwable) -> Unit = ::defaultLogBenign,
        presenter: (report: String) -> Unit = {},
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
        // 良性环境噪音先行分流：跳过报告与呈现（模态对话框对无感异常是纯噪音），
        // 日志留痕 + 委托保持平台既有语义
        if (isKnownBenignEnvironmentQuirk(e)) {
            try {
                logBenign(t, e)
            } catch (_: Throwable) {
            }
            try {
                delegate?.uncaughtException(t, e)
            } catch (_: Throwable) {
            }
            return@UncaughtExceptionHandler
        }
        // 三段独立自保护：顺序是契约（日志先于报告组装，报告尾部才能含崩溃日志行；
        // 呈现先于委托，用户读完报告后平台崩溃语义才继续走）
        try {
            logError(t, e)
        } catch (_: Throwable) {
        }
        var report: String? = null
        try {
            report = CrashReport.build(
                threadName = t.name,
                throwable = e,
                logTail = RecentLogBuffer.tail(CrashReport.LOG_TAIL_LINES),
            )
        } catch (_: Throwable) {
        }
        if (report != null) {
            try {
                presenter(report)
            } catch (_: Throwable) {
            }
        }
        try {
            delegate?.uncaughtException(t, e)
        } catch (_: Throwable) {
        }
    }

    private fun presentCrashReport(report: String) {
        CrashReportDialog.show(report)
    }

    private fun defaultLogError(thread: Thread, throwable: Throwable) {
        JLog.e(TAG, throwable) { "Uncaught exception in thread ${thread.name}" }
    }

    private fun defaultLogBenign(thread: Thread, throwable: Throwable) {
        JLog.w(TAG) { "良性环境噪音（应用无感知，跳过诊断报告）：${throwable.message}" }
    }
}
