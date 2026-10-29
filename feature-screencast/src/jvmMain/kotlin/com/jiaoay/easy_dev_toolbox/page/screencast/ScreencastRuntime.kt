package com.jiaoay.easy_dev_toolbox.page.screencast

import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.runtime.AppHost
import com.jiaoay.easy_dev_toolbox.core.runtime.AppLifecycleParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 投屏观测运行时（App 级单例）：持有全局唯一 [ScreencastEngine]——投屏状态脱离页面
 * 生命周期，切页不中断（页面 VM 仅做转发）。不进任何串行执行队列（终止语义见
 * [ScreencastEngine] 头注释）。
 *
 * scope 由 :core 的 AppHost 统一收口——本单例不得自建孤儿 scope（无 cancel 路径，
 * 进程存续期协程泄漏）；从 [AppHost.scope] 派生子 scope，退出随 AppHost.shutdown
 * 的根 cancel 一并收。宿主经 AppHost.register 挂入退出序：[shutdown] 停止在跑的
 * 投屏并**有界等待**终态落定——会话收尾（adb 隧道规则 remove + 显示触点恢复）在会话
 * 协程 finally 的 NonCancellable 段，无等待面时它和 JVM 退出竞速，退出期规则残留非
 * 确定；等待超时放行（退出优先于完美收尾，残留规则在下次 adb 重连时由 adb 自清，
 * 显示触点残留由 [start] 的次启动核对兜底）。
 */
object ScreencastRuntime : AppLifecycleParticipant {

    override val id: String = "screencast"

    val scope: CoroutineScope =
        CoroutineScope(AppHost.scope.coroutineContext + SupervisorJob(AppHost.scope.coroutineContext.job))

    /** 全局唯一投屏引擎（App 级单实例，与实时抓取引擎同层同口径） */
    val engine: ScreencastEngine = ScreencastEngine(scope)

    private const val TAG = "ScreencastRuntime"

    /**
     * 启动钩子：显示触点强杀残留核对（兜底验收面，引擎保持惰性——核对不触引擎）。
     * 上一进程开启显示触点后被强杀时，持久标记留在档、设备端残留开启态（设备常显
     * 触摸圈）——启动即按标记恢复原值并清标记；设备未连/命令失败保留标记，下次启动
     * 再试。异步执行不阻塞 App 启动（AppHost.startAll 首帧前 await 不受此影响）。
     */
    override suspend fun start(scope: CoroutineScope) {
        this@ScreencastRuntime.scope.launch(Dispatchers.IO) {
            runCatching {
                val adbPath = DeviceHub.currentEndpoint().executablePath
                ScreencastShowTouches.restoreResidueAtStartup(
                    gatewayFactory = { serial -> SimpleShellScrcpyAdbGateway(adbPath, serial) },
                )
            }.onFailure { JLog.e(TAG) { "show touches residue check failed: ${it.message}" } }
        }
    }

    /** 终态落定有界等待（ms）：覆盖「stop 解锁 → 收尾 remove → Stopped 落定」的常规窗口 */
    private const val SHUTDOWN_JOIN_TIMEOUT_MS = 3_000L

    override fun shutdown() {
        engine.stop()
        if (engine.status.value is ScreencastStatus.Idle) return
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT_MS) {
                engine.status.first { it is ScreencastStatus.Stopped || it is ScreencastStatus.Idle }
            }
        }
    }
}
