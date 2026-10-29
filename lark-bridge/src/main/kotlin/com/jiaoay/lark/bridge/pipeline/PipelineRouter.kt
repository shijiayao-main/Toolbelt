// ① Intake 第 5 步——路由（顺序即优先级，AtomicReference 无锁整表替换）。
// 路由真源是「配置数组序 + 单表」，不是硬编码装配链。
// 🔴 本文件属 intake 零锁清单（IntakeGuardTest 守护）：热路径零查表、零分配、零锁——违反即挂死 intake 单消费者协程、全链路停摆。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.BridgeConfig
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 路由器：持有当前生效的 [PipelineSnapshot]，把消息映射到唯一一个 [MatchableHandler]。
 *
 * ## 为什么是 AtomicReference 而不是锁
 * 快照是**不可变**对象：配置变更时 [PipelineSnapshotFactory.build] 造一张全新快照，
 * 然后 [publish] 一次 `AtomicReference.set` 整表替换。读侧 [route] 只做一次 `get()` 拿到引用，
 * 之后全程在这张不可变快照上线性扫描 ⇒ 读侧零锁、零分配、零查表。
 * 🔴 **HOT 语义**：在途任务持有的是**旧快照里的旧 handler 实例与旧 config 引用**，
 *    替换不影响它们（不断连、不中断）；下一条消息才看到新快照。
 *
 * ## 顺序即优先级
 * `routes` **数组顺序 == 优先级**，[PipelineSnapshot.resolve] 线性 `firstOrNull { match }` ⇒ 数组前者胜。
 * 两条同触发词的 route，前一条永远命中，后一条**不可达**（`RouteOrderTest` 直接断言此行为）。
 *
 * ## 未命中的处置
 * [route] 返回 null ⇒ 调用方（[EventIntake]）交给 [Admission] 的 LIGHT 分支走 `runUnhandled`
 * 兜底（TEXT 未命中在 App 形态恒被 fallback 调度 agent 收编——agent 恒为硬依赖；非 TEXT
 * 未支持 kind 发显式指引）。**未命中不是错误**，是正常路径（例如用户随手发了一句话）。
 *
 * @param initial 首张快照（装配期用 `DefaultBridgeConfig` 构建，随后 publish 磁盘配置的那张）。
 */
class PipelineRouter(
    initial: PipelineSnapshot,
    private val log: BridgeLog = BridgeLog.Silent,
) {

    private val snapshotRef = AtomicReference(initial)

    private val matchedTotal = AtomicLong(0)
    private val unmatchedTotal = AtomicLong(0)
    private val publishedTotal = AtomicLong(0)

    /** 当前生效快照（只读；metrics 与 UI「路由」页展示用）。 */
    val snapshot: PipelineSnapshot get() = snapshotRef.get()

    /** 当前生效配置（== [snapshot].config，即快照构建时刻的那份）。 */
    val config: BridgeConfig get() = snapshotRef.get().config

    /** 当前候选条数（已剔除 disabled 与未知 kind）。 */
    val size: Int get() = snapshotRef.get().size

    /** 累计命中次数。 */
    val matchedCount: Long get() = matchedTotal.get()

    /** 累计未命中次数（可观测；持续为高说明配置或 handler 表没接对）。 */
    val unmatchedCount: Long get() = unmatchedTotal.get()

    /** 累计快照替换次数（HOT 应用次数；`PipelineSnapshotTest` 据此断言「变更才重建」）。 */
    val publishedCount: Long get() = publishedTotal.get()

    /**
     * 整表替换（配置热更新回调；无锁、O(1)、可在任意线程调用）。
     * @return 被替换掉的旧快照（调用方可据此比对在途任务是否仍持旧引用）。
     */
    fun publish(next: PipelineSnapshot): PipelineSnapshot {
        val previous = snapshotRef.getAndSet(next)
        publishedTotal.incrementAndGet()
        log.info("路由快照已替换：entries ${previous.size} → ${next.size}（routes=${next.config.routes.size}）")
        return previous
    }

    /**
     * 为一条消息找 handler。纯 CPU、不挂起、零锁、零分配（不 new 任何对象）。
     *
     * 副作用：把 [RequestContext.route] 定格为命中 route（未命中置 null）——见 [PipelineSnapshot.resolve]。
     * @return null ⇒ 未命中任何 handler，调用方走 `runUnhandled` 兜底。
     */
    fun route(ctx: RequestContext): MatchableHandler? {
        val entry = snapshotRef.get().resolve(ctx)
        if (entry == null) {
            unmatchedTotal.incrementAndGet()
            return null
        }
        matchedTotal.incrementAndGet()
        return entry
    }

    /** 生效顺序的可读预览（UI「路由」页；形如 `reply:quick-hello:LIGHT`）。 */
    fun describe(): List<String> = snapshotRef.get().describe()

    override fun toString(): String = "PipelineRouter(entries=$size, matched=$matchedCount, unmatched=$unmatchedCount)"
}
