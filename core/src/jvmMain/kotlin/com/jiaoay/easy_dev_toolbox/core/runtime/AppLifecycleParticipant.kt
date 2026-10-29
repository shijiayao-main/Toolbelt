// S2 · App 生命周期参与者契约（:core 定义接口，feature/composeApp 实现）。
// 设计裁定：
//  - App 级参与者统一经 AppHost 注册调度（勿在 main.kt 手工散装装配、勿自建 App 级 scope）；
//  - 接口落 :core（纯类型零依赖），实现侧在各自 feature/宿主——能力上报经 [capabilities]，
//    :core 刻意不认识任何数据源（源提取在源所在模块）；
//  - [shutdown] 刻意非 suspend：`DisposableEffect.onDispose` 与 JVM shutdown hook 都无法挂起（「零 runBlocking 新增」裁定）。
package com.jiaoay.easy_dev_toolbox.core.runtime

import kotlinx.coroutines.CoroutineScope

/**
 * App 生命周期参与者：宿主 [com.jiaoay.easy_dev_toolbox.core] 之外的模块
 * 把「App 级启动/退出钩子」收口到 [AppHost] 统一调度。
 *
 * 实现纪律：
 * - `start` 幂等；[scope] 由 AppHost 提供（参与者**不自建 App 级 scope**——本契约的要点）；
 * - `shutdown` 快速非阻塞（需优雅等待的清理在实现内自管，先例：Bridge.onAppExit 的 EDT budget）；
 * - 注册时序：宿主 `main()` 顶层同步 `AppHost.register(...)`，`startAll()` 在首帧前 await。
 */
interface AppLifecycleParticipant {

    /** 参与者标识（日志与排障用；建议实现类 companion const）。 */
    val id: String

    suspend fun start(scope: CoroutineScope)

    /** 退出清理（非 suspend；见文件头裁定）。幂等；恒不抛（内部 runCatching）。 */
    fun shutdown()

    /** 能力四态快照（默认空 = 无能力声明；CapabilityId 语义见 [CapabilitySnapshot.kt]）。 */
    fun capabilities(): Map<CapabilityId, CapabilityState> = emptyMap()
}
