// S2 · AppHost：App 级装配单点 + 唯一根 scope + 退出序。
// 落 :core（而非 :composeApp）的裁定：feature 侧参与者（ShellToolsRuntime 等）需要派生
//    App 级 scope——依赖方向恒 feature → core，宿主模块不可被 feature 引用；把 AppHost 放
//    :composeApp 会逼出「feature 反射摸宿主」的坏味道。宿主特有清理（桥接退出、日志后端销毁）
//    经 shutdown(bridgeExit, hostTeardown) 函数参数注入，:core 不认识任何宿主类型。
// 收口对象（改造前散落三处 object 单例各自为政）：
//  - ShellToolsRuntime（scope 无 cancel 路径）、DockManager.submitScope（同）；
//  - LarkBridgeManager 的 1Hz Koog 端点刷新回路（桥接门面持有 AI 域职责，越界）；
//  - main.kt 的 registerInitializer 裸调与 onDispose 三连清理调用。
// 不可动前提（方案 S2 裁定）：
//  - Bridge.APP_EXIT_EDT_BUDGET_MILLIS 的退出时序契约（BridgeAppExitEdtBudgetTest）形状不动——
//    本类 shutdown 只是「桥接先停、后台继续、日志后关」原顺序的调度者，不进 Bridge 内部；
//  - LarkBridgeManager.getOrCreate 内的 runBlocking { refreshEndpoint() } 保留（搬 scope 不搬语义，
//    它与 1Hz 回路是可用性死锁修复的两半）。
package com.jiaoay.easy_dev_toolbox.core.runtime

import com.jiaoay.easy_dev_toolbox.core.initializer.JInitializerManager
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.runtime.AppLifecycleParticipant
import com.jiaoay.easy_dev_toolbox.core.runtime.CapabilityRegistry
import com.jiaoay.easy_dev_toolbox.core.runtime.InMemoryCapabilityRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * App 级宿主单点。
 *
 * 职责边界（刻意最小）：
 * 1. 持有全应用唯一 App 级 [scope]（SupervisorJob + Default）——feature 侧 App 级后台回路一律挂载于此，
 *    不再自建 CoroutineScope（AppHostGuardTest A 组守护宿主侧新构造点）；
 * 2. [register] + [startAll]：参与者幂等启动（异常隔离，单点失败不拖垮其他）；
 * 3. [shutdown]：严格顺序退出（顺序即契约，见 [SHUTDOWN_ORDER_DESCRIPTION]）。
 *

 */
object AppHost {

    private const val TAG = "AppHost"

    /** 退出序（🔴 顺序即契约，乱序则桥接停不净、退出期日志丢失；与 main.kt 旧 onDispose 三连一致）：
     *  ① participants.shutdown（桥接在内——先停网络与子进程，最长等待由各参与者自管，
     *     Bridge.onAppExit 的 EDT budget 不受影响）；
     *  ② scope.cancel（收掉全部后台回路）；
     *  ③ 宿主收尾（executor 缓存 invalidate + 日志后端销毁——日志必须最后关，退出期日志仍可落）。 */
    private val SHUTDOWN_ORDER_DESCRIPTION = "participants → scope → 宿主收尾（LlmClientFactory.invalidate → LogbackHelper.onDestroy）"

    /** 全应用唯一 App 级 scope（宿主侧新 App 级回路一律挂这里；AppHostGuardTest A 组守护）。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 能力四态注册表（纯 sink；各 participant 上报）。 */
    val capabilities: CapabilityRegistry = InMemoryCapabilityRegistry()

    private val participants = java.util.concurrent.CopyOnWriteArrayList<AppLifecycleParticipant>()

    /** 初始化器注册（main 顶层同步调用；startInit 之前）。 */
    fun registerInitializers(initializers: List<com.jiaoay.easy_dev_toolbox.core.initializer.JInitializer>) {
        initializers.forEach { JInitializerManager.registerInitializer(it) }
    }

    /** 注册生命周期参与者（幂等：重复 id 跳过）。 */
    fun register(participant: AppLifecycleParticipant) {
        if (participants.any { it.id == participant.id }) {
            JLog.w(TAG) { "参与者重复注册被忽略 id=${participant.id}" }
            return
        }
        participants += participant
    }

    /** 幂等启动全部参与者（异常隔离；能力快照就地覆盖）。 */
    suspend fun startAll() {
        participants.forEach { participant ->
            runCatching { participant.start(scope) }
                .onFailure { JLog.e(TAG, it) { "参与者启动失败 id=${participant.id}（已跳过，不拖垮其他）" } }
            runCatching { participant.capabilities().forEach { (id, state) -> capabilities.report(id, state) } }
        }
    }

    /**
     * 退出清理（非 suspend：DisposableEffect.onDispose 无法挂起）。
     * 顺序见 [SHUTDOWN_ORDER_DESCRIPTION]；每步 runCatching——单步失败不阻断后续清理。
     */
    fun shutdown(
        bridgeExit: () -> Unit,
        hostTeardown: () -> Unit,
    ) {
        participants.forEach { participant ->
            runCatching { participant.shutdown() }
                .onFailure { JLog.e(TAG, it) { "参与者退出清理失败 id=${participant.id}" } }
        }
        runCatching { scope.cancel() }
        runCatching { bridgeExit() }
            .onFailure { JLog.e(TAG, it) { "桥接退出清理失败" } }
        runCatching { hostTeardown() }
            .onFailure { JLog.e(TAG, it) { "宿主收尾失败" } }
        JLog.i(TAG) { "AppHost 已完成退出序（$SHUTDOWN_ORDER_DESCRIPTION）" }
    }

    /** 测试接缝：清空参与者注册表（不 cancel scope——scope 是进程级单例，cancel 后不可复用）。 */
    internal fun resetForTest() {
        participants.clear()
    }
}
