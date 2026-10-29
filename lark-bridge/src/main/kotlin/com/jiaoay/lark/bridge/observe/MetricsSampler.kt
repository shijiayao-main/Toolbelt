// observe：1Hz 指标采样器。
// 可观测面：把散落在多处的丢弃计数器汇聚成**一份** 1Hz 快照，
//    任何丢弃增量都触发一次 WARN；UI/CLI 只读 [BridgeStatusSnapshot]，不逐个持有 lib 对象。
package com.jiaoay.lark.bridge.observe

import com.jiaoay.lark.bridge.BridgeLog
import com.jiaoay.lark.bridge.confirm.ConfirmRuntimeStatus
import com.jiaoay.lark.bridge.exec.ProcessRegistry
import com.jiaoay.lark.bridge.pipeline.PipelineCounters
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 指标采样器（**1Hz 拉取**，不是每事件推送）。
 *
 * ## 为什么是 1Hz 采样而不是每事件推送
 * | 维度 | 每事件推送 | 1Hz 采样（本类） |
 * |---|---|---|
 * | 高频任务下的开销 | 每条进度都触发一次 UI 重组 + 一次快照分配 | 固定 1 次/秒，与消息量**无关** |
 * | 有界性 | 需要额外背压策略 | 天然有界（一次采样 = 一份不可变快照） |
 * | 丢弃可见性 | 丢弃事件本身也要推送 ⇒ 递归 | 计数器是**累计值**，采样自然看到增量 |
 *
 * 运行契约：
 * 1. **必须跑在 `dispatchers.metrics`**（`Default.limitedParallelism(1, "bridge-metrics")`），
 *    装配为 `runtime.metricsScope.launch { sampler.runLoop() }`（作用域树里的 `metricsJob`）；
 * 2. [sample] 是**非挂起纯读**：全部数据源都是原子量/不可变引用，采样过程零锁零阻塞；
 * 3. [runLoop] 用 `delay` 而非 `Thread.sleep` ⇒ 可被 `stop()` 即时取消；
 * 4. 🔴 **丢弃增量可观测**：每次采样比对 [BridgeStatusSnapshot.droppedTotal] 与上次值，**增量 > 0 就 WARN 一次**
 *    （只报增量，避免同一条丢弃被 1Hz 反复刷屏）。
 *
 * ## 全部数据源可空（装配渐进性）
 * 所有源都是**可空构造参数**：缺哪个源，对应字段就取默认值（0 / Idle / Disabled），
 * 采样器**不因此崩**——这让 observe 域与装配点可以独立编译验证。
 *
 * @param counters 管线计数（**非空**，是本类的核心源）
 * @param intervalMillis 采样间隔（默认 1000ms = 1Hz）
 * @param connectionProvider 连接状态来源（接网关 `connectionState`）
 * @param confirmProvider 确认链路状态来源（接 `ConfirmService.status`）
 * @param runningProvider 桥接是否在运行（接门面 `isRunning`）
 */
class MetricsSampler(
    private val counters: PipelineCounters,
    private val sinkChannels: SinkChannels? = null,
    private val logBus: LogBus? = null,
    private val registry: ProcessRegistry? = null,
    private val auditSink: JsonlSink? = null,
    private val statsSink: JsonlSink? = null,
    private val connectionProvider: () -> BridgeConnectionStatus = { BridgeConnectionStatus.Idle },
    private val confirmProvider: () -> ConfirmRuntimeStatus = { ConfirmRuntimeStatus.Disabled },
    private val runningProvider: () -> Boolean = { false },
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val log: BridgeLog = BridgeLog.Silent,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // `latestRef` 先落一个空快照，再由下方 `init` 采第一次样。
    //    不能写成 `AtomicReference(sample())`：Kotlin 按**声明顺序**初始化属性，
    //    而 `sample()` 内部要写 `latestRef` / `samplesCounter` / `lastReportedDropped`，
    //    此时它们还是 null ⇒ 构造即 NPE（已实测复现）。
    private val latestRef = AtomicReference<BridgeStatusSnapshot>(EMPTY_SNAPSHOT)
    private val samplesCounter = AtomicLong(0)
    private val lastReportedDropped = AtomicLong(0)
    private val dropWarningsCounter = AtomicLong(0)

    init {
        // 构造完成后立即采一次（UI 首帧不必等 1s）。放在全部字段声明之后 ⇒ 初始化顺序安全。
        sample()
    }

    /** 最近一次快照（构造时即采一次，故恒非 null；UI 首帧不必等 1s）。 */
    val latest: BridgeStatusSnapshot get() = latestRef.get()

    /** 累计采样次数（1Hz 下 ≈ 运行秒数；可用于诊断「采样协程是否被卡住」）。 */
    val samplesTotal: Long get() = samplesCounter.get()

    /** 因丢弃增量而发出的 WARN 次数（连「报丢弃」这件事本身也要可观测）。 */
    val dropWarningsTotal: Long get() = dropWarningsCounter.get()

    /** 采样间隔（毫秒）。 */
    val interval: Long get() = intervalMillis

    /**
     * 采一次样。🔴 **非挂起、零锁、恒不抛**（每个源都用 `runCatching` 兜住，坏源只让对应字段退化，
     * 不会让整个采样协程死掉——那会让 UI 状态页永久停在最后一帧且无人知晓）。
     */
    fun sample(atMillis: Long = clock()): BridgeStatusSnapshot {
        val counterSnapshot = runCatching { counters.snapshot() }
            .getOrDefault(EMPTY_COUNTERS)
        val snapshot = BridgeStatusSnapshot(
            running = runCatching { runningProvider() }.getOrDefault(false),
            connection = runCatching { connectionProvider() }.getOrDefault(BridgeConnectionStatus.Idle),
            confirm = runCatching { confirmProvider() }.getOrDefault(ConfirmRuntimeStatus.Disabled),
            counters = counterSnapshot,
            sinkDropped = sinkChannels?.droppedRecords ?: 0L,
            auditWritten = auditSink?.writtenTotal ?: 0L,
            statsWritten = statsSink?.writtenTotal ?: 0L,
            auditSinkDropped = (auditSink?.droppedTotal ?: 0L) + (statsSink?.droppedTotal ?: 0L),
            logVersion = logBus?.version ?: 0L,
            logSize = logBus?.size ?: 0,
            logDropped = logBus?.droppedTotal ?: 0L,
            liveProcesses = registry?.liveCount ?: 0,
            sampledAtMillis = atMillis,
        )
        latestRef.set(snapshot)
        samplesCounter.incrementAndGet()
        reportDropIncrease(snapshot)
        return snapshot
    }

    /**
     * 采样主循环（1Hz；被取消即退出）。
     *
     * 装配为 `runtime.metricsScope.launch { sampler.runLoop() }`；
     * `stop()` 取消 `metricsJob` ⇒ 本循环在下一个 `delay` 处即时退出（不吞 `CancellationException`）。
     */
    suspend fun runLoop() {
        val interval = intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)
        while (currentCoroutineContext().isActive) {
            delay(interval)
            sample()
        }
    }

    /** 丢弃增量 > 0 时 WARN 一次（只报增量，不重复刷同一条）。 */
    private fun reportDropIncrease(snapshot: BridgeStatusSnapshot) {
        val total = snapshot.droppedTotal
        val previous = lastReportedDropped.get()
        if (total <= previous) return
        if (!lastReportedDropped.compareAndSet(previous, total)) return
        dropWarningsCounter.incrementAndGet()
        log.warn(
            "观测到丢弃增量：delta=${total - previous} total=$total " +
                "(intake=${snapshot.counters.intakeQueueDropped} stale=${snapshot.counters.staleDropped} " +
                "dup=${snapshot.counters.duplicatesDropped} access=${snapshot.counters.accessRejected} " +
                "sink=${snapshot.sinkDropped} sinkWrite=${snapshot.auditSinkDropped} log=${snapshot.logDropped})",
        )
    }

    companion object {
        /** 1Hz（方案表逐字：「1Hz 采样，非每事件」）。 */
        const val DEFAULT_INTERVAL_MILLIS: Long = 1_000L

        /** 间隔下限（防误配 0 造成忙轮询烧 CPU）。 */
        const val MIN_INTERVAL_MILLIS: Long = 50L

        /** 计数源缺失时的空投影（全 0）。 */
        val EMPTY_COUNTERS: PipelineCounters.CounterSnapshot = PipelineCounters().snapshot()

        /** 构造期占位快照（`init` 立刻覆盖；仅用于让 [latestRef] 有非 null 初值）。 */
        val EMPTY_SNAPSHOT: BridgeStatusSnapshot = BridgeStatusSnapshot(
            running = false,
            connection = BridgeConnectionStatus.Idle,
            confirm = ConfirmRuntimeStatus.Disabled,
            counters = EMPTY_COUNTERS,
        )
    }
}
