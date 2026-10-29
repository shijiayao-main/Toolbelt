package com.jiaoay.lark.bridge.exec

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

/**
 * 在途子进程有界登记表。
 *
 * - **有界**：上限 `maxConcurrent * 2`（[ProcessRegistry.capacity]）——超限 [register] 返回 false，
 *   调用方据此拒绝启动（归 AgentUnavailable），防止子进程无限堆积耗尽句柄/内存。
 * - **JVM shutdown hook**：进程崩溃/被 kill -15 时 [killAll] 兜底 destroyForcibly 所有在途子进程，防孤儿进程泄漏。
 *
 * 🔴 构造注入 [maxConcurrent]（无全局单例）；线程安全集合，check-then-act 用局部 monitor 保原子。
 */
class ProcessRegistry(maxConcurrent: Int) {

    /**
     * 容量上限：并发 agent 数的 2 倍（在途 + 排队余量），至少 1。
     * 🔴 @Volatile 可热更——`runtime.maxConcurrentAgents` 标 HOT：若装配期固化，HOT 扩容后新任务
     *    仍被旧容量拒绝（PermitGate 放行、registry 拒收，
     *    用户看到「并发上限改大了却还是起不来」）。热更口 = [reconfigureCapacity]。
     */
    @Volatile
    var capacity: Int = (maxConcurrent * 2).coerceAtLeast(1)
        private set

    /**
     * 热更容量上限（与 [PermitGate.reconfigure] 同链路，Bridge.applyConfiguration 下推）。
     * 缩容只影响**后续** register 判定，在途进程不杀（与许可闸门的「在途跑完」语义一致）。
     */
    fun reconfigureCapacity(maxConcurrent: Int) {
        capacity = (maxConcurrent * 2).coerceAtLeast(1)
    }

    private val live: MutableSet<Process> = Collections.newSetFromMap(ConcurrentHashMap())

    private val shutdownHook = Thread({ killAll() }, "lark-bridge-process-reaper")

    init {
        // 兜底：JVM 退出时清理在途子进程（若已处于 shutdown 阶段则忽略）
        runCatching { Runtime.getRuntime().addShutdownHook(shutdownHook) }
    }

    /** 当前在途子进程数（StartStopLeakTest 断言 `liveProcesses == 0`）。 */
    val liveCount: Int get() = live.size

    /** 是否已达容量上限。 */
    val isFull: Boolean get() = live.size >= capacity

    /**
     * 登记一个已启动的子进程。
     * @return true = 登记成功；false = 已达容量上限（调用方须自行终止该进程并回 AgentUnavailable）。
     */
    fun register(process: Process): Boolean = synchronized(live) {
        if (live.size >= capacity) return false
        live.add(process)
        true
    }

    /** 注销一个已结束的子进程（与 [register] 配对，NF-07）。 */
    fun unregister(process: Process) {
        live.remove(process)
    }

    /** 强杀并清空全部在途子进程（stop() / shutdown hook / TeardownCompleteness 第 ③ 步）。 */
    fun killAll() {
        val snapshot = synchronized(live) { live.toList().also { live.clear() } }
        snapshot.forEach { p -> runCatching { if (p.isAlive) p.destroyForcibly() } }
    }

    /** 移除 shutdown hook（测试隔离 / 优雅关闭用，避免钩子随实例累积）。 */
    fun close() {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}
