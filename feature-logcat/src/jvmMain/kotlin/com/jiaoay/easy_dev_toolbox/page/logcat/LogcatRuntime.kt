package com.jiaoay.easy_dev_toolbox.page.logcat

import com.jiaoay.easy_dev_toolbox.core.runtime.AppHost
import com.jiaoay.easy_dev_toolbox.core.runtime.AppLifecycleParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job

/**
 * logcat 观测运行时（App 级单例）：持有全局唯一 [LogcatCaptureEngine]——抓取状态
 * 脱离页面生命周期，切页不中断（页面 VM 仅做转发）。不进任何串行执行队列
 * （终止语义见 [LogcatCaptureEngine] 头注释）。
 *
 * scope 由 :core 的 AppHost 统一收口——本单例不得自建孤儿 scope（无 cancel 路径，
 * 进程存续期协程泄漏）；从 [AppHost.scope] 派生子 scope，退出随 AppHost.shutdown
 * 的根 cancel 一并收。宿主经 AppHost.register 挂入退出序：[shutdown] 停止在跑的
 * 抓取（kill 走 StreamingShell 升级收割链；写盘句柄由引擎收尾协程异步关闭）。
 */
object LogcatRuntime : AppLifecycleParticipant {

    override val id: String = "logcat"

    val scope: CoroutineScope =
        CoroutineScope(AppHost.scope.coroutineContext + SupervisorJob(AppHost.scope.coroutineContext.job))

    /** 全局唯一 logcat 抓取引擎（App 级单实例，与宿主 Shell 执行引擎同层同口径） */
    val engine: LogcatCaptureEngine = LogcatCaptureEngine(scope)

    /** 引擎惰性；scope 已在对象图就绪——无启动钩子 */
    override suspend fun start(scope: CoroutineScope) = Unit

    override fun shutdown() {
        engine.stop()
    }
}
