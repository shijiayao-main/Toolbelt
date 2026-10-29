// 有界内存链路「SDK handler → intake」= Channel(512, DROP_OLDEST) + 计数 + WARN。
// 🔴 有界是硬要求：无界 Channel 会在消费侧停摆时无上限吃内存，且丢弃不可见。
// 🔴 本文件属 intake 零锁清单（IntakeGuardTest 守护）：全程 O(1) 无阻塞、零锁（计数用 java.util.concurrent.atomic CAS）——违反即挂死 intake 单消费者协程、全链路停摆。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.gateway.InboundMessage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * 入站队列（SDK 回调线程 → intake 单协程的**唯一**交接点）。
 *
 * ## 为什么是 DROP_OLDEST 而不是 SUSPEND
 * - 入站消息**可以丢**：飞书对未 ACK 的事件会重推，且丢一条消息的代价远小于让 SDK 回调线程阻塞
 *   （回调阻塞 > 3s ⇒ 飞书判定应用无响应并断连，3 秒铁律）。
 * - 子进程管道**不可以丢**：丢一行 stream-json 会破坏 JSONL 解析 ⇒ 那里必须 SUSPEND 背压。
 * ⇒ 两种溢出策略是**按数据可丢性**分别裁定的，不是不一致。
 *
 * ## 丢弃计数手法
 * `DROP_OLDEST` 下 `trySend` **恒成功**，无法从返回值判丢弃 ⇒ 用 [offered]/[consumed] 两个
 * `AtomicLong` 的差值推断在途占用：`getAndIncrement()` 的**前值**减去已消费数 ≥ 容量 ⇒ 本次挤掉了最旧的一帧。
 * 全程 O(1)、无锁、不阻塞 SDK 回调线程。
 *
 * @param onDropped 丢弃旁路回调（装配点把它接成 `GatewaySignal.Dropped(where, droppedTotal)` 发往 signals 流）。
 *        🔴 回调必须 O(1)：它在 SDK 回调线程上执行，只允许 `trySend`（signal 通道自身溢出只累加计数，
 *        **不得**递归再发 Dropped）。
 */
class InboundQueue(
    capacity: Int = CAPACITY,
    private val log: BridgeLog = BridgeLog.Silent,
    private val where: String = WHERE,
    private val onDropped: ((where: String, droppedTotal: Long) -> Unit)? = null,
) {

    /** 容量（方案表逐字：512）。 */
    val capacity: Int = capacity.coerceAtLeast(1)

    private val channel = Channel<InboundMessage>(this.capacity, BufferOverflow.DROP_OLDEST)
    private val offered = AtomicLong(0)
    private val consumed = AtomicLong(0)
    private val droppedTotal = AtomicLong(0)

    /** 累计丢弃条数。 */
    val dropped: Long get() = droppedTotal.get()

    /** 累计投递条数（= SDK 回调 trySend 次数）。 */
    val offeredCount: Long get() = offered.get()

    /** 累计被 intake 消费条数。 */
    val consumedCount: Long get() = consumed.get()

    /** 当前在途（已入队未消费）条数。 */
    val pending: Long get() = (offered.get() - consumed.get() - droppedTotal.get()).coerceAtLeast(0L)

    /**
     * SDK 回调线程唯一允许调用的方法：O(1)、恒不阻塞、恒不抛（`runCatching` 由调用方包，此处自身也不抛）。
     * @return true 表示消息已入队；false 表示本次被丢弃（已计数 + WARN + [onDropped]），两种丢弃成因：
     *         ① 队列满 ⇒ DROP_OLDEST 挤掉最旧一条；② 队列已 [close] ⇒ 本条直接丢（也必须可观测）。
     */
    fun trySend(message: InboundMessage): Boolean {
        val before = offered.getAndIncrement()
        val inFlight = before - consumed.get()
        val overflow = inFlight >= capacity
        val sent = channel.trySend(message)
        if (overflow) {
            val total = droppedTotal.incrementAndGet()
            // WARN 只在溢出路径触发（正常路径零日志格式化 ⇒ 不侵蚀 3 秒铁律的 µs 级预算）。
            log.warn("入站队列溢出，已丢弃最旧消息：where=$where capacity=$capacity droppedTotal=$total")
            onDropped?.invoke(where, total)
            return false
        }
        if (sent.isClosed) {
            // 关停窗口内 SDK 回调仍可能来一两条：`channel.trySend` 静默失败 ⇒ 这也是丢弃，
            //    不计数就违反「所有丢弃点都有计数器并可读」，故与溢出走同一条可观测路径。
            val total = droppedTotal.incrementAndGet()
            log.warn("入站队列已关闭，本条消息被丢弃：where=$where droppedTotal=$total")
            onDropped?.invoke(where, total)
            return false
        }
        return true
    }

    /**
     * intake 单消费者取一条；队列关闭且排空后返回 null（⇒ intake 循环正常收尾，优雅排空）。
     * 挂起点只有这一个：intake 协程在此等待，不做任何 IO/锁。
     */
    suspend fun receive(): InboundMessage? =
        channel.receiveCatching().getOrNull()?.also { consumed.incrementAndGet() }

    /** 非阻塞排空（关停时用；返回本次取走的条数）。 */
    fun drainNow(): Int {
        var n = 0
        while (true) {
            val r = channel.tryReceive()
            if (r.isSuccess) {
                consumed.incrementAndGet()
                n++
            } else {
                return n
            }
        }
    }

    /** 关闭队列（幂等）：之后 [receive] 排空即返回 null。 */
    fun close() {
        channel.close()
    }

    companion object {
        /** 方案「有界内存链路 8 行表」第 1 行的容量（与网关 `Tuning.inboundChannelCapacity` 同值）。 */
        const val CAPACITY: Int = 512

        /** `GatewaySignal.Dropped.where` 的字面值（与网关侧信号通道的 "signal" 区分）。 */
        const val WHERE: String = "intake"
    }
}
