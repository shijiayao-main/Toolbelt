// 进度 seam + 有界内存链路「累积 → 卡片」的 1 槽 conflate。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.gateway.CardStreamSink
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * 进度出口 seam（每消息一个实例，由 ③ Execute 在构造 [RequestContext] 时创建）。
 *
 * **接线约定**：
 * 1. `ProgressAccumulator` 实现本接口；[append] 是 agent 逐行输出的唯一入口（`AgentRunner.run` 的 `onLine`）。
 * 2. [touch] 必须在**每次有新输出**时调用（[append] 内部调用即可）——它是空闲看门狗的活动信号源；
 *    🔴 不 touch 会导致 agent 正常长跑被误杀。
 * 3. 🔴 [append]/[touch] **不得阻塞、不得挂起**：它们在 `pipe` 池的读循环里被调用，阻塞会背压子进程 stdout。
 */
interface ProgressSink {

    /** 最近一次活动时刻（毫秒）；T7 的空闲看门狗以此判定 `runtime.agentIdleTimeoutSeconds`。 */
    val lastActivityMillis: Long

    /** 追加一行/一段输出。 */
    fun append(text: String)

    /** 显式标记活动（无文本增量的心跳场景，如 stream-json 的 control 消息）。 */
    fun touch()

    /** 已累积条目数（供 UI 与计数）。 */
    val entryCount: Int

    /** 当前渲染出的卡片 markdown 快照（滚动窗口 + 字符上限后的结果）。 */
    fun renderSnapshot(): String
}

/**
 * 1 槽 conflate 通道（方案「有界内存链路」第 2 行：`累积 → 卡片` = **覆盖，保留最新快照**）。
 *
 * 为什么是 conflate 而不是 DROP_OLDEST 多槽：卡片是「当前状态的全量快照」，中间快照无价值；
 *    保留最新一帧即可让用户看到最新进度，且**永不阻塞** producer（agent 输出速率可远高于卡片 patch 速率）。
 * 🔴 [offer] 恒 O(1) 返回、恒不阻塞；被覆盖的旧快照计入 [droppedTotal]（丢弃必须可观测）。
 */
class ConflatedSlot<T>(slotCapacity: Int = DEFAULT_CAPACITY) {

    /** 槽位容量（1 槽 ⇒ 覆盖语义，保留最新一帧）。 */
    val capacity: Int = slotCapacity.coerceAtLeast(1)

    private val channel = Channel<T>(this.capacity, BufferOverflow.DROP_OLDEST)
    private val dropped = AtomicLong(0)
    private val offered = AtomicLong(0)

    /**
     * 覆盖式投递。@return true 表示缓冲区此前为空（未挤掉旧值）；false 表示挤掉了最旧的一帧。
     * DROP_OLDEST 下 `trySend` 恒成功，故**无法从返回值判丢弃**——用 [offered] 与 [consumed] 的差值推断，
     *    与 `SdkLarkGateway.offerInbound` 同一手法（全程 O(1) 无锁）。
     */
    fun offer(value: T): Boolean {
        val before = offered.getAndIncrement()
        val inFlightBefore = before - consumed.get()
        val ok = channel.trySend(value).isSuccess
        if (inFlightBefore >= capacity) dropped.incrementAndGet()
        return ok && inFlightBefore < capacity
    }

    private val consumed = AtomicLong(0)

    /** 取最新一帧；通道关闭且已排空时返回 null。 */
    suspend fun receive(): T? = channel.receiveCatching().getOrNull()?.also { consumed.incrementAndGet() }

    /** 非阻塞偷看是否有待消费快照（pump 协程用于避免无谓挂起）。 */
    fun tryReceive(): T? = channel.tryReceive().getOrNull()?.also { consumed.incrementAndGet() }

    /** 被覆盖丢弃的帧数。 */
    val droppedTotal: Long get() = dropped.get()

    /** 累计投递帧数。 */
    val offeredTotal: Long get() = offered.get()

    fun close() {
        channel.close()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 1
    }
}

/**
 * [ProgressSink] 的 conflate 实现（T8 若需滚动窗口/字符上限，请**装饰**本类而非替换——
 * 这样「1 槽 conflate」这条有界内存链路仍然成立）。
 *
 * @param clock 可注入（测试虚拟时间）。
 */
class ConflatedProgressSink(
    private val clock: () -> Long = { System.currentTimeMillis() },
    capacity: Int = ConflatedSlot.DEFAULT_CAPACITY,
) : ProgressSink {

    private val slot = ConflatedSlot<String>(capacity)
    private val lastActivity = java.util.concurrent.atomic.AtomicLong(clock())
    private val entries = java.util.concurrent.atomic.AtomicLong(0)
    private val latest = java.util.concurrent.atomic.AtomicReference("")

    override val lastActivityMillis: Long get() = lastActivity.get()

    override val entryCount: Int get() = entries.get().toInt()

    override fun append(text: String) {
        latest.set(text)
        entries.incrementAndGet()
        touch()
        slot.offer(text)
    }

    /**
     * 追加快照帧但**不 touch**（墙钟心跳专用）：墙钟心跳不是子进程活动证据，
     * 经 [append] 走会刷新活动时间戳、蒙蔽空闲看门狗——真挂死的静默任务将永不被杀。
     * 观测计数与 conflate 语义与 [append] 一致，仅跳过 [touch]。
     */
    fun appendFrameWithoutTouch(text: String) {
        latest.set(text)
        entries.incrementAndGet()
        slot.offer(text)
    }

    override fun touch() {
        lastActivity.set(clock())
    }

    override fun renderSnapshot(): String = latest.get()

    /** 暴露 conflate 槽给卡片 pump 协程。 */
    val snapshots: ConflatedSlot<String> get() = slot

    /** 关闭 conflate 槽（任务终态后由卡片泵持有方经 ProgressAccumulator.close 透传调用，使泵退出）。 */
    fun close() {
        slot.close()
    }

    /** 被覆盖的快照数。 */
    val droppedSnapshots: Long get() = slot.droppedTotal
}

/**
 * 卡片 pump：把 [ConflatedSlot] 的最新快照喂给 [CardStreamSink]（`累积 → 卡片 → 飞书` 两行的接线）。
 *
 * 滚动窗口语义：`CardStreamSink.tryReplace(text)` 是**全量替换**语义——每帧快照
 *    直通替换，卡片内容恒等于最新窗口快照，不累积历史、不会因窗口丢头产生重复。
 * 节流不在本类：`CardStreamSink` 内部已有 `streamThrottleMs=250` 时间窗（滚动窗口下
 *    cardMaxChars=2000 恒不可达 streamThrottleChars=4000）。
 *
 * @return 推送的快照帧数（测试断言用）。
 */
suspend fun pumpSnapshotsToCard(
    slot: ConflatedSlot<String>,
    sink: CardStreamSink,
): Long {
    var pushedFrames = 0L
    while (sink.isOpen) {
        val snapshot = slot.receive() ?: break
        sink.tryReplace(snapshot)
        pushedFrames++
    }
    return pushedFrames
}
