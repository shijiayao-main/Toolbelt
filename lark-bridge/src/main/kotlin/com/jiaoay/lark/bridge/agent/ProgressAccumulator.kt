// 进度累积器：接收进度事件，维护滚动窗口快照，并实现 `pipeline.ProgressSink`。
// 🔴 **零锁**：单写者私有 deque + `AtomicReference` 快照发布（intake/pipe 路径禁 `synchronized`/`Mutex`——违反即挂死 intake 单消费者协程/pipe 排干协程，
//    由 IntakeGuardTest 归一化扫描守护）。
// **装饰** `ConflatedProgressSink`（而非替换）——保持有界内存链路：单条事件入库前硬截断到
//    [STORED_ENTRY_MAX_CHARS]（6 条 × 2000 字符 ≈ 12KB 上限）。
// 滚动窗口语义（默认 6 条、丢最旧、相邻 Thinking 以 `" "` 合并、O(1) 与会话时长无关）勿随手改。
package com.jiaoay.lark.bridge.agent

import com.jiaoay.lark.bridge.pipeline.ConflatedProgressSink
import com.jiaoay.lark.bridge.pipeline.ProgressSink
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 进度累积器：接收进度事件，维护滚动窗口快照。
 *
 * [onEvent] 被 `pipe` 池的排干协程调用（🔴 必须非阻塞、非挂起——阻塞即卡死排干协程、子进程输出堵满管道）；事件量经节流窗口天然有限。
 *
 * ## 零锁并发模型
 * - **单写者**：只有排干协程调 [onEvent]/[append]（`ProgramLauncher.launch` 的 `lines` 通道是单消费者），
 *   故私有 [window] 无需保护；
 * - **快照发布**：每次写入后把不可变副本 `List<ProgressEvent>` 塞进 [published]（`AtomicReference`），
 *   读者（渲染协程 / UI / 测试）只读 [published] ⇒ 无锁、无撕裂；
 * - 不使用 `synchronized` / `Mutex`（IntakeGuardTest 归一化扫描守护）。
 *
 * ## O(1) 与会话时长无关
 * [window] 恒 `<= maxEntries`（默认 6），超出即 `removeFirst()` 丢最旧；[published] 的副本长度同界。
 * 因此单次 [onEvent] 的成本与「本次会话已运行多久 / 已产出多少行」**无关**——长跑 agent 不会让
 * 进度累积退化成 O(n)。（`ProgressAccumulatorWindowTest` 钉死）
 *
 * @param maxEntries 滚动窗口条数（`agent.progress.maxEntries`，默认 6）
 * @param entryMaxChars 单条渲染截断（`ProgressCardRenderer.Limits.entryMaxChars`，默认 80）
 * @param cardMaxChars 整卡渲染截断（`agent.progress.cardMaxChars`，默认 2000）
 * @param delegate 被装饰的 1 槽 conflate sink（有界内存链路第 2 行）；渲染出的正文经
 *        [ConflatedProgressSink.append] 投递，卡片 pump 协程从 `delegate.snapshots` 取最新一帧
 */
class ProgressAccumulator(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val entryMaxChars: Int = DEFAULT_ENTRY_MAX_CHARS,
    private val cardMaxChars: Int = DEFAULT_CARD_MAX_CHARS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val delegate: ConflatedProgressSink = ConflatedProgressSink(),
    /**
     * 首个进度事件触发时回调一次（受理卡延迟触发）：
     * 轻答任务（纯 reply_direct、零工具零文本事件）不刷卡，首个事件（LLM 思考/工具调用/
     * 流式文本）到达才开受理卡——消解闲聊场景「每条消息一张卡」的体验回退。
     * 回调在 [onEvent] 内联调用：须非阻塞非挂起（开卡失败的降级由调用方 runCatching）。
     * 运行期可经 [wireDelayedCardStart] 接管（构造默认空——装配点 new 完后管线 wire，
     * prompt 等参数由 wire 侧闭包捕获，回调本身零参）。
     * 纪律：零 @Volatile var ⇒ 原子引用持回调（CAS 首事件闸 [firstEventFired] 防重触发）。
     */
    private val onFirstEventRef: java.util.concurrent.atomic.AtomicReference<() -> Unit> =
        java.util.concurrent.atomic.AtomicReference { },
) : TaskProgressListener, ProgressSink {

    /** 渲染限制（透传给 [ProgressCardRenderer]）。 */
    val limits: ProgressCardRenderer.Limits = ProgressCardRenderer.Limits(maxEntries, entryMaxChars, cardMaxChars)

    /** 🔴 单写者私有（排干协程独占）；读者一律走 [published]。 */
    private val window = ArrayDeque<ProgressEvent>()

    /** 不可变快照发布点（零锁）。 */
    private val published = AtomicReference<List<ProgressEvent>>(emptyList())

    /** 当前执行阶段（终态由 [markStatus] 写入；渲染读它决定标题配色）。 */
    private val status = AtomicReference(RunStatus.RUNNING)

    private val startedAt = AtomicLong(clock())
    private val appendedTotal = AtomicLong(0)
    private val droppedTotal = AtomicLong(0)
    private val mergedTotal = AtomicLong(0)

    /** 首事件闸（受理卡延迟触发）：CAS 保证 [onFirstEvent] 恰好回调一次。 */
    private val firstEventFired = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── ProgressSink ─────────────────────────────────────────────────────────

    override val lastActivityMillis: Long get() = delegate.lastActivityMillis

    override val entryCount: Int get() = published.get().size

    /**
     * 追加一行原始输出（非 stream-json 后端的降级路径：stdout 纯文本逐行进窗口）。
     * 非阻塞、非挂起；内部 [touch] 以驱动空闲看门狗。
     */
    override fun append(text: String) {
        onEvent(ProgressEvent.AssistantText(text))
    }

    override fun touch() {
        delegate.touch()
    }

    override fun renderSnapshot(): String = delegate.renderSnapshot()

    // ── TaskProgressListener ─────────────────────────────────────────────────

    override fun onEvent(event: ProgressEvent) {
        runCatching { record(event) } // listener 约定不抛
    }

    /**
     * 墙钟心跳事件（静默后端委托期的卡片保活）：窗口滚动 + 快照发布 + conflate 投帧与
     * [onEvent] 一致，唯一分野是**不 touch**——墙钟心跳不是子进程活动证据，touch 会
     * 蒙蔽空闲看门狗（真挂死的静默任务将永不被杀）。看门狗只认子进程真实输出。
     */
    fun onHeartbeat(event: ProgressEvent) {
        runCatching { record(event, touchActivity = false) } // listener 约定不抛
    }

    /** 接管首事件回调（受理卡延迟触发的管线 wire 点——开卡动作与参数由调用方闭包捕获）。 */
    fun wireDelayedCardStart(callback: () -> Unit) {
        onFirstEventRef.set(callback)
    }

    private fun record(event: ProgressEvent, touchActivity: Boolean = true) {
        // 受理卡延迟触发：首个事件才回调（CAS 防重——只发一次；touch-only 不走本方法不受影响）。
        if (firstEventFired.compareAndSet(false, true)) {
            runCatching { onFirstEventRef.get()() } // 回调约定不抛（开卡降级由调用方承担）
        }
        val bounded = event.bounded()
        when (bounded) {
            // 相邻 Thinking 合并为一条，防思考块刷屏
            is ProgressEvent.Thinking -> {
                val last = window.lastOrNull()
                if (last is ProgressEvent.Thinking) {
                    window.removeLast()
                    window.addLast(ProgressEvent.Thinking(last.text + " " + bounded.text))
                    mergedTotal.incrementAndGet()
                } else {
                    window.addLast(bounded)
                }
            }
            else -> window.addLast(bounded)
        }
        while (window.size > maxEntries) {
            window.removeFirst()
            droppedTotal.incrementAndGet() // 丢弃必须可观测
        }
        appendedTotal.incrementAndGet()
        val snapshot = window.toList()
        published.set(snapshot)
        // 装饰：把渲染后的正文投进 1 槽 conflate（覆盖式，永不阻塞排干协程）。
        // touchActivity=false 为墙钟心跳专用（[onHeartbeat]）：不 touch、免蒙蔽空闲看门狗。
        if (touchActivity) {
            touch()
            delegate.append(ProgressCardRenderer.body(currentProgress(), limits))
        } else {
            delegate.appendFrameWithoutTouch(ProgressCardRenderer.body(currentProgress(), limits))
        }
    }

    /** 取当前快照（渲染协程/测试调用）；statusOverride 由终态收尾直传 */
    fun snapshot(statusOverride: RunStatus? = null): TaskProgress = TaskProgress(
        startedAtEpochMillis = startedAt.get(),
        events = published.get(),
        status = statusOverride ?: status.get(),
    )

    /** 写入终态（之后 [renderSnapshot] 与 [snapshot] 都反映该状态）。 */
    fun markStatus(next: RunStatus) {
        status.set(next)
        delegate.append(ProgressCardRenderer.body(currentProgress(), limits))
    }

    private fun currentProgress(): TaskProgress =
        TaskProgress(startedAtEpochMillis = startedAt.get(), events = published.get(), status = status.get())

    /** 被 conflate 覆盖丢弃的快照帧数（丢弃必须可观测）。 */
    val droppedSnapshots: Long get() = delegate.droppedSnapshots

    /** 被滚动窗口挤出（丢最旧）的事件条数。 */
    val droppedEntries: Long get() = droppedTotal.get()

    /** 相邻 Thinking 合并次数（观测用）。 */
    val mergedEntries: Long get() = mergedTotal.get()

    /** 累计接收事件数。 */
    val appendedCount: Long get() = appendedTotal.get()

    /** 卡片 pump 用的 conflate 槽（装配点把它接给 `pumpSnapshotsToCard`）。 */
    val snapshots get() = delegate.snapshots

    /**
     * 关闭底层 conflate 槽：任务终态后由卡片泵的持有方调用，使 `pumpSnapshotsToCard` 的
     * `slot.receive()` 返回 null 而退出（🔴 不关会导致泵协程在任务结束后永久挂起）。
     * 幂等：重复调用无副作用（`Channel.close()` 幂等）。
     */
    fun close() {
        delegate.close()
    }

    /** 被装饰的 sink（需要把 `ProgressSink` 交给 `RequestContext` 时直接用本实例即可）。 */
    val inner: ConflatedProgressSink get() = delegate

    /** 🔴 有界内存：单条事件文本入库前硬截断（渲染期另有 [entryMaxChars] 的显示截断）。 */
    private fun ProgressEvent.bounded(): ProgressEvent = when (this) {
        is ProgressEvent.Thinking -> ProgressEvent.Thinking(cap(text))
        is ProgressEvent.ToolUse -> ProgressEvent.ToolUse(name, cap(argsSummary))
        is ProgressEvent.ToolResult -> ProgressEvent.ToolResult(name, ok, cap(summary))
        is ProgressEvent.AssistantText -> ProgressEvent.AssistantText(cap(text))
    }

    private fun cap(text: String): String =
        if (text.length <= STORED_ENTRY_MAX_CHARS) text else text.take(STORED_ENTRY_MAX_CHARS)

    companion object {
        /** 🔒 滚动窗口条数默认值（防刷屏收窄）。 */
        const val DEFAULT_MAX_ENTRIES: Int = 6

        /** 🔒 单条渲染截断默认值（防刷屏收窄）。 */
        const val DEFAULT_ENTRY_MAX_CHARS: Int = 80

        /** 🔒 整卡渲染截断默认值（防刷屏收窄）。 */
        const val DEFAULT_CARD_MAX_CHARS: Int = 2000

        /** 单条事件入库硬上限（有界内存：6 × 2000 ≈ 12KB；渲染期再截到 80）。 */
        const val STORED_ENTRY_MAX_CHARS: Int = 2000
    }
}
