// `Map<RouteKind, HandlerFactory>` **单表**。
// 新增一类能力只在此加一行：① RouteKind 加枚举值（sealed + classDiscriminator 让漏写即红构建）
//    ② 本文件的 build() 加一个具名参数 ③ 装配点传入工厂。管线代码零改动。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.config.RouteKind

/**
 * route 种类 → handler 工厂的**唯一映射表**。
 *
 * 表是**不可变**的（构造后只读）⇒ 无锁并发读安全（`PipelineSnapshot` 在配置变更时整表重建，
 *    而不是就地改这张表；这正是 HOT applyMode 的实现基础）。
 * 🔴 未登记的 [RouteKind] ⇒ `factoryFor` 返回 null ⇒ 该 route 在快照构建时被跳过 ⇒
 *    消息未命中任何 handler ⇒ 走 `runUnhandled` 兜底（TEXT 静默留痕；非 TEXT 显式指引）。
 */
class HandlerKinds(private val table: Map<RouteKind, HandlerFactory>) {

    /** 已登记的种类集合（UI「路由」页与文档核对用）。 */
    val registeredKinds: Set<RouteKind> get() = table.keys

    /** 已登记条目数（恒 ≤ [RouteKind] 的值数）。 */
    val size: Int get() = table.size

    /** 未登记的种类返回 null（调用方据此判定「未知 kind」）。 */
    fun factoryFor(kind: RouteKind): HandlerFactory? = table[kind]

    /** 是否已登记（快照构建期的快速判定）。 */
    fun contains(kind: RouteKind): Boolean = table.containsKey(kind)

    /** 缺失的种类（应为空集；非空说明装配没接完，测试据此报警）。 */
    fun missingKinds(): Set<RouteKind> = RouteKind.entries.toSet() - table.keys

    override fun toString(): String = "HandlerKinds(${registeredKinds.sortedBy { it.name }.joinToString { it.name }})"

    companion object {
        /** 空表：任何 kind 都未登记 ⇒ 一切消息走 runUnhandled 兜底（未命中静默语义的基准装配）。 */
        val EMPTY: HandlerKinds = HandlerKinds(emptyMap())

        /** 任意组合（测试与装配点通用入口）。 */
        fun of(vararg pairs: Pair<RouteKind, HandlerFactory>): HandlerKinds = HandlerKinds(pairs.toMap())

        /**
         * **新增一类能力只在此加一行**的落点：四个具名参数与 [RouteKind] 的四个值一一对应。
         * 传 null ⇒ 该 kind 不登记（其 route 在快照构建时被跳过 ⇒ 消息落 runUnhandled 兜底）。
         * 唯一装配点调用本函数，把各 handler 工厂按 kind 归位：
         * - [system] ← `SystemHandler`(LIGHT) + `StopHandler`(URGENT，同属 SYSTEM 表的内置分支)
         * - [command] ← `CommandHandler`(LIGHT 清单 / HEAVY 委托 agent) + `InteractionHandler`(LIGHT)
         * - [agent] ← `AgentHandler`(HEAVY)
         * - [file] ← `FileHandler`(LIGHT，仅登记 + 回执，不下载不跑 agent)
         */
        fun build(
            system: HandlerFactory? = null,
            command: HandlerFactory? = null,
            agent: HandlerFactory? = null,
            file: HandlerFactory? = null,
        ): HandlerKinds {
            val map = LinkedHashMap<RouteKind, HandlerFactory>(RouteKind.entries.size)
            if (system != null) map[RouteKind.SYSTEM] = system
            if (command != null) map[RouteKind.COMMAND] = command
            if (agent != null) map[RouteKind.AGENT] = agent
            if (file != null) map[RouteKind.FILE] = file
            return HandlerKinds(map)
        }
    }
}
