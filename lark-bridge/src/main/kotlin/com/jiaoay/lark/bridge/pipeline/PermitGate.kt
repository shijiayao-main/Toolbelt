// ② Admission 的全局许可闸门：HOT 可调 `runtime.maxConcurrentAgents`，名义容量固定 + 构造期抽许可实现扩缩容。
package com.jiaoay.lark.bridge.pipeline

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * HOT 可调的许可闸门（`runtime.maxConcurrentAgents` 是 **HOT** 字段，见 `BridgeFields` #RUNTIME-1）。
 *
 * 🔴 **为什么不能直接持有「按上限构造」的 `Semaphore`**：kotlinx 的 `Semaphore` 把构造时的 `permits`
 *    当作**不可逾越的名义总量**——`release()` 一旦让 `availablePermits > permits` 就抛
 *    `IllegalStateException("The number of released permits cannot be greater than N")`（1.11.0 实测），
 *    而「HOT 调大 `maxConcurrentAgents`」恰恰需要 release 出比构造时更多的许可 ⇒ 直接扩容必炸。
 *    换实例也不行：旧实例发的许可会被 release 到新实例 ⇒ 计数错乱。
 * **本实现**：`Semaphore` 的名义容量固定为 [NOMINAL_PERMITS]，构造期一次性 `tryAcquire()` 抽掉
 *    「名义容量 − 当前上限」那么多许可，使 `availablePermits == 当前上限`。此后：
 *    - [reconfigure] **扩容** = 循环 `release()` 把抽走的许可还回去（永远 ≤ 名义容量 ⇒ 不触发上述异常）；
 *    - **缩容** = [drainScope] 上的后台协程循环 `acquire()`（抽不到就挂起等 ⇒ 渐进生效：在途任务完成后
 *      许可被抽走，新上限对**新任务**立即生效、对**在途任务**不追溯）。
 *    始终只有**一个** `Semaphore` 实例 ⇒ 无许可泄漏。这是唯一既满足 HOT 又不炸的做法。
 * ⚠️ kotlinx-coroutines 1.11.0 的 `Semaphore` **无** `release(permits: Int)` 重载（实测 javap：
 *    仅 `availablePermits` / `suspend acquire()` / `tryAcquire()` / `release()`）⇒ 扩容必须循环 release。
 * 🔴 **超额 release 的防护**：[release] 只在「确有在途占用」时才把许可还回去（否则会凭空抬高并发上限）。
 *
 * @param drainScope 缩容用的后台协程作用域（传 `BridgeRuntime.admissionScope`）。
 */
class PermitGate(initialPermits: Int, private val drainScope: CoroutineScope) {

    private val capacityRef = AtomicInteger(initialPermits.coerceIn(1, NOMINAL_PERMITS))
    private val semaphore = Semaphore(NOMINAL_PERMITS)
    private val inUse = AtomicInteger(0)
    private val reconfigures = AtomicLong(0)
    private val drains = AtomicLong(0)

    init {
        // 把名义容量压到当前上限（`tryAcquire` 非挂起，构造期必然全部成功）。
        repeat(NOMINAL_PERMITS - capacityRef.get()) { semaphore.tryAcquire() }
    }

    /** 当前并发上限。 */
    val capacity: Int get() = capacityRef.get()

    /** 当前可用许可数（`AdmissionTest` 断言「LIGHT 不占许可」用它）。 */
    val available: Int get() = semaphore.availablePermits

    /** 累计重配次数。 */
    val reconfigureCount: Long get() = reconfigures.get()

    /** 累计后台抽走的许可数（缩容生效量）。 */
    val drainedCount: Long get() = drains.get()

    /** 热更新并发上限（幂等：同值直接返回 false；超过 [NOMINAL_PERMITS] 的值被夹到上限）。 */
    fun reconfigure(nextPermits: Int): Boolean {
        val target = nextPermits.coerceIn(1, NOMINAL_PERMITS)
        val previous = capacityRef.getAndSet(target)
        if (target == previous) return false
        reconfigures.incrementAndGet()
        if (target > previous) {
            repeat(target - previous) { semaphore.release() }
        } else {
            val delta = previous - target
            drainScope.launch {
                repeat(delta) {
                    // 抽不到就挂起等（在途任务释放后自然抽走）；作用域取消即停。
                    semaphore.acquire()
                    drains.incrementAndGet()
                }
            }
        }
        return true
    }

    /** 挂起直到拿到许可（可被取消 / 被外层 `withTimeoutOrNull` 超时）。 */
    suspend fun acquire() {
        semaphore.acquire()
        inUse.incrementAndGet()
    }

    /**
     * 归还许可（🔴 必须与 [acquire] 严格配对——漏配对/超额归还即并发上限被凭空抬高、机器被 agent 子进程打爆）。
     * 无在途占用时**不归还**（否则会凭空抬高并发上限）。
     */
    fun release() {
        val previous = inUse.getAndUpdate { if (it > 0) it - 1 else it }
        if (previous <= 0) {
            return
        }
        semaphore.release()
    }

    companion object {
        /**
         * `Semaphore` 的**名义**容量（不是并发上限）：为 HOT 扩容预留的天花板。
         * 取值依据：`runtime.maxConcurrentAgents` 是「同时跑几个 agent 子进程」，受机器核数与内存约束，
         *    实践中不会超过两位数；4096 既留足余量，又让构造期的 `tryAcquire` 循环（≤4096 次）可忽略。
         */
        const val NOMINAL_PERMITS: Int = 4096
    }
}
