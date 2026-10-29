// 路由快照（预计算 List<MatchableHandler>，配置变更时一次性构建，热路径零查表零分配）。
// HOT 的落点：AtomicReference 无锁整表替换，在途任务持有旧快照引用继续跑完。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.config.AgentRoute
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.CommandRoute
import com.jiaoay.lark.bridge.config.FileRoute
import com.jiaoay.lark.bridge.config.RouteKind
import com.jiaoay.lark.bridge.config.RouteRule
import com.jiaoay.lark.bridge.config.SystemRoute
import java.util.concurrent.atomic.AtomicLong

/**
 * [RouteRule] → [RouteKind] 的映射（🔴 穷尽 `when`，无 `else`）。
 *
 * 为什么放在 :lark-bridge 而不是给 `RouteRule` 加 `val kind`：
 * `RouteRule` 刻意只含 `id`/`enabled`（序列化面最小）。
 * 本扩展用**穷尽 when** 达到同等强度——新增 `RouteRule` 子类时，本文件**编译失败**（红构建），
 * 与「sealed + classDiscriminator 让漏写即红构建」是同一道闸门，只是落在消费侧。
 */
val RouteRule.routeKind: RouteKind
    get() = when (this) {
        is SystemRoute -> RouteKind.SYSTEM
        is CommandRoute -> RouteKind.COMMAND
        is AgentRoute -> RouteKind.AGENT
        is FileRoute -> RouteKind.FILE
    }

/**
 * 快照里的一个可执行条目：route + handler 配对。
 *
 * [route] 可空——`Admission` 的 fallback agent 路径（未命中转本地 Koog 调度）没有命中
 * route，用 `MatchableHandler(route = null, handler)` 包装；快照 `entries` 的常规条目恒非空
 * （[PipelineSnapshotFactory.build] 只以真实 route 构造）。
 * handler 在**快照构建期**一次性构造，热路径不再 `new`、不再查 map（方案「热路径零查表零分配」）。
 */
class MatchableHandler(
    val route: RouteRule?,
    val handler: MessageHandler,
) {
    val kind: RouteKind get() = handler.kind
    val weight: HandlerWeight get() = handler.weight
    /** 命中 route 的 id；fallback 路径（route == null）取 handler kind 名（日志/审计可辨）。 */
    val routeId: String get() = route?.id ?: "fallback:${handler.kind.name.lowercase()}"

    override fun toString(): String = "MatchableHandler(${route?.let { "${it.routeKind.name}/${it.id}" } ?: "fallback"}, ${handler.weight})"
}

/**
 * 不可变路由快照。
 *
 * **顺序即优先级**：[entries] 的顺序 == `config.routes` 数组顺序（仅剔除 disabled 与未知 kind），
 *    [resolve] 线性扫描 `firstOrNull { match }` ⇒ **数组前者胜**，无硬编码装配链。
 * 热路径零分配：[resolve] 用带下标的 while 循环（不用 `indices`/迭代器），
 *    逐个候选把 route 写进传入的 ctx（ctx 每消息只构造一次），命中即返回。
 */
class PipelineSnapshot(
    val config: BridgeConfig,
    val entries: List<MatchableHandler>,
) {

    val size: Int get() = entries.size

    /**
     * 找出第一个命中的 handler；未命中返回 null（调用方走 `runUnhandled` 兜底，见 [Admission]）。
     * 副作用：把 [RequestContext.route] 定格为命中 route（未命中则置 null）。
     *    这只在 ① intake 单协程里发生 ⇒ 无竞态（「Intake 单消费者本身就是并发原语」）。
     */
    fun resolve(ctx: RequestContext): MatchableHandler? {
        val list = entries
        var i = 0
        while (i < list.size) {
            val entry = list[i]
            ctx.route = entry.route
            if (entry.handler.match(ctx)) return entry
            i++
        }
        ctx.route = null
        return null
    }

    /** 只读预览：不触碰 ctx 地列出全部候选（UI「路由」页的生效顺序展示用）。 */
    fun describe(): List<String> = entries.map { "${it.route?.routeKind?.jsonValue ?: "fallback"}:${it.routeId}:${it.weight}" }

    override fun toString(): String = "PipelineSnapshot(entries=$size, routes=${config.routes.size})"
}

/**
 * 快照工厂：`config.routes` → [PipelineSnapshot]（配置变更时由 HOT 订阅回调调用一次）。
 *
 * 构建纪律：
 * 1. `routes.filter { enabled }`——`enabled=false` 的 route **被跳过**，且**不占优先级位次**；
 * 2. **按数组序**追加到 entries ⇒ 顺序即优先级；
 * 3. `HandlerKinds.factoryFor(route.routeKind)` 为 null（未登记 = 「未知 kind」）⇒ 跳过并计数
 *    ⇒ 该 route 的消息必然未命中 ⇒ 走兜底（`runUnhandled`）；
 * 4. 🔴 工厂抛异常 ⇒ 记 ERROR 并跳过该 route，**不让一条坏 route 拖垮整张快照**（配置校验之外的运行时兜底）。
 */
class PipelineSnapshotFactory(
    private val kinds: HandlerKinds,
    private val deps: HandlerDeps,
    private val log: BridgeLog = BridgeLog.Silent,
) {

    private val disabledSkipped = AtomicLong(0)
    private val unknownKindSkipped = AtomicLong(0)
    private val factoryFailures = AtomicLong(0)
    private val builds = AtomicLong(0)

    /** 被 `enabled=false` 跳过的 route 累计条数（所有丢弃点必须可观测）。 */
    val disabledSkippedTotal: Long get() = disabledSkipped.get()

    /** 因 kind 未登记而跳过的 route 累计条数。 */
    val unknownKindSkippedTotal: Long get() = unknownKindSkipped.get()

    /** 工厂构造失败的累计条数。 */
    val factoryFailureTotal: Long get() = factoryFailures.get()

    /** 快照构建次数（HOT 替换次数；`PipelineSnapshotTest` 据此断言「变更才重建」）。 */
    val buildCount: Long get() = builds.get()

    fun build(config: BridgeConfig): PipelineSnapshot {
        builds.incrementAndGet()
        val routes = config.routes
        // 🔴 撞名守卫：「状态」/「/status」是高频中文词，
        //    SystemHandler 的内置匹配按数组序裁决（firstOrNull 命中即止）——若用户自定义
        //    CommandRoute/AgentRoute 的 trigger/别名与它撞名且排在 SystemRoute 之前，状态指令会被吃掉。
        //    每次构建 WARN 一次（HOT 重建时提醒仍存在），不阻断路由——裁决权在数组序。
        warnBuiltinCommandCollisions(routes)
        val entries = ArrayList<MatchableHandler>(routes.size)
        var i = 0
        while (i < routes.size) {
            val route = routes[i]
            if (!route.enabled) {
                disabledSkipped.incrementAndGet()
                i++
                continue
            }
            val factory = kinds.factoryFor(route.routeKind)
            if (factory == null) {
                unknownKindSkipped.incrementAndGet()
                log.warn("route kind 未登记 handler 工厂，已跳过：kind=${route.routeKind.jsonValue} id=${route.id}")
                i++
                continue
            }
            val handler = runCatching { factory.create(route, deps) }
                .onFailure { e ->
                    factoryFailures.incrementAndGet()
                    log.error("handler 工厂构造失败，已跳过该 route：id=${route.id}", e)
                }
                .getOrNull()
            if (handler != null) entries.add(MatchableHandler(route, handler))
            i++
        }
        return PipelineSnapshot(config, entries)
    }

    /**
     * 内置指令撞名检测：只查与 SystemHandler 内置精确指令撞名的
     * 用户 route（CommandRoute 的 name/aliases、AgentRoute 的 triggers/resetCommand/stopCommand）。
     * SystemRoute(id="builtin") 只是 DEFAULT_ROUTES 首位——存量配置可把它移后/禁用 ⇒ 一并检测：
     *    SystemRoute 非首位且其前存在会命中「状态」的 route 时,状态指令按数组序被遮蔽。
     */
    private fun warnBuiltinCommandCollisions(routes: List<com.jiaoay.lark.bridge.config.RouteRule>) {
        val builtinIndex = routes.indexOfFirst { it is com.jiaoay.lark.bridge.config.SystemRoute }
        var i = 0
        while (i < routes.size) {
            val route = routes[i]
            val colliding = when (route) {
                is com.jiaoay.lark.bridge.config.CommandRoute ->
                    route.aliases.any { isBuiltinStatusWord(it) } || isBuiltinStatusWord(route.name)
                // AgentRoute 撞名检测已随 routes 配置收窄退役删除（triggers/resetCommand/stopCommand
                // 不复存在）；引擎保留词与内置词的并存由 EngineReservedWords 承担。
                is com.jiaoay.lark.bridge.config.AgentRoute -> false
                else -> false
            }
            if (colliding) {
                if (builtinIndex < 0) {
                    log.warn("route「${route.id}」的触发词与内置「状态」/「/status」撞名，且配置中无 SystemRoute ⇒ 状态指令将不可用（请删除该触发词或添加 SystemRoute）")
                } else if (i < builtinIndex) {
                    log.warn("route「${route.id}」的触发词与内置「状态」/「/status」撞名，且排在 SystemRoute 之前 ⇒ 按数组序优先级，状态指令会被它遮蔽（请调整顺序或改名）")
                }
            }
            i++
        }
    }

    /** 与 SystemHandler 状态指令精确匹配同口径（trim + lowercase + 全等）。 */
    private fun isBuiltinStatusWord(word: String?): Boolean {
        if (word == null) return false
        val t = word.trim().lowercase()
        return t == "状态" || t == "/status"
    }
}
