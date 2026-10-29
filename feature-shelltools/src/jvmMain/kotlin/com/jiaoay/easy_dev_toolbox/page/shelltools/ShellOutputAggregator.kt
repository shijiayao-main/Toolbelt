package com.jiaoay.easy_dev_toolbox.page.shelltools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 输出聚合协作者：[ShellExecutionEngine] 的输出管线（缓冲/世代号/收集协程）单一属主。
 *
 * 聚合契约（对外经 [ShellExecutionEngine.output] 消费，签名不变）：
 * - 追加行向 [outputChannel] 发射，单一收集协程按「首行直通 + [AGGREGATION_WINDOW_MS]
 *   窗口收割 + [MAX_BATCH] 上限」批量 commit——state 更新频率与重组成本受控；
 * - 内部缓冲为 ArrayDeque 滚动窗口（超上限批量 removeFirst + [OutputState.droppedCount]
 *   计数，不静默截断；上限 = [ShellExecutionEngine.MAX_OUTPUT_ITEMS]）；
 * - 世代号防复活：[clearOutput] 递增 [outputGeneration] 后，旧窗口残留行提交时被过滤；
 * - 状态以 [OutputState] 不可变快照发布；条目携带会话内单调 [ExecOutputItem.seq] 稳定 key。
 *
 * 线程模型：[append] 可从任意线程调用（trySend 非挂起）；[clearOutput] 与收集协程经
 * [bufferLock] 互斥——clear 的 drain（channel + buffer）与 commit 的窗口写入不得交错，
 * 否则 clear 后到达的旧世代行会混入新快照。
 */
internal class ShellOutputAggregator(private val scope: CoroutineScope) {

    companion object {
        /** 输出聚合窗口（首行直通后最多再等 100ms 收割窗口内到达的行） */
        internal const val AGGREGATION_WINDOW_MS = 100L

        /** 单次聚合批量上限，防极端突发把单次 commit 变成超大快照 */
        internal const val MAX_BATCH = 1000

        /** flushPending ack 重试轮询间隔：收集协程活着就持续等（ack 恒等语义），死亡才放行排水兜底 */
        private const val FLUSH_ACK_POLL_MS = 1_000L
    }

    /** channel 事件：数据行（携带发射时的世代号）或冲刷信号（任务结束立即冲刷用） */
    private sealed interface OutputEvent {
        data class Line(val generation: Long, val item: ExecOutputItem) : OutputEvent

        class FlushSignal(val ack: CompletableDeferred<Unit>) : OutputEvent
    }

    private val outputChannel = Channel<OutputEvent>(Channel.UNLIMITED)

    /** 内部滚动窗口缓冲（[bufferLock] 保护） */
    private val outputBuffer = ArrayDeque<ExecOutputItem>()

    /** 累计丢弃计数（[bufferLock] 保护；clearOutput 归零） */
    private var droppedCount = 0

    /** 输出世代号（[bufferLock] 写 / volatile 读）：clearOutput 递增后，旧窗口残留行提交时被过滤不得复活 */
    @Volatile
    private var outputGeneration = 0L

    private val bufferLock = Any()

    /** 会话内单调递增行号（稳定 key；clearOutput 不重置） */
    private val seqGenerator = AtomicLong(0L)

    private val _output = MutableStateFlow(OutputState())
    val output: StateFlow<OutputState> = _output.asStateFlow()

    private var collectorJob: Job? = null

    init {
        startOutputCollector()
    }

    /** 追加一条输出（任意线程可调；seq 与世代号在此处盖章） */
    fun append(item: ExecOutputItem) {
        val seq = seqGenerator.incrementAndGet()
        val generation = outputGeneration // volatile 读：clear 之后的 append 用新世代
        outputChannel.trySend(OutputEvent.Line(generation, item.copy(seq = seq)))
    }

    /** UI 侧追加状态提示行（如导出失败提示），走与输出行相同的聚合链路 */
    fun appendStatusLine(content: String) {
        append(ExecOutputItem(type = ExecOutputType.Status, content = content))
    }

    fun clearOutput() {
        synchronized(bufferLock) {
            // 递增世代号：收集协程此后提交的旧世代行一律丢弃
            outputGeneration++
            // drain channel：清空尚未进入收集协程的残留行
            while (true) {
                when (val event = outputChannel.tryReceive().getOrNull()) {
                    null -> break
                    is OutputEvent.FlushSignal -> event.ack.complete(Unit)
                    is OutputEvent.Line -> Unit // 旧世代行，直接丢弃
                }
            }
            outputBuffer.clear()
            droppedCount = 0
            _output.value = OutputState()
        }
    }

    /**
     * 收集协程：首行直通（receive 挂起立即唤醒，低频输出零额外延迟），
     * 窗口内继续收割至 [MAX_BATCH] 或窗口到期，一次性 commit。
     */
    private fun startOutputCollector() {
        if (collectorJob?.isActive == true) return
        collectorJob = scope.launch {
            while (isActive) {
                val first = outputChannel.receive()
                if (first is OutputEvent.FlushSignal) {
                    // 收集协程空闲：无持有批次，直接确认
                    first.ack.complete(Unit)
                    continue
                }
                val batch = ArrayList<OutputEvent.Line>(MAX_BATCH)
                batch.add(first as OutputEvent.Line)
                val deadline = System.currentTimeMillis() + AGGREGATION_WINDOW_MS
                while (batch.size < MAX_BATCH) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val next = withTimeoutOrNull(remaining) { outputChannel.receive() } ?: break
                    if (next is OutputEvent.FlushSignal) {
                        // 立即冲刷当前持有批次后确认（保证 ack 返回时行已上屏）
                        commitBatch(batch)
                        batch.clear()
                        next.ack.complete(Unit)
                        break
                    }
                    batch.add(next as OutputEvent.Line)
                }
                commitBatch(batch)
            }
        }
    }

    /** 批量提交进滚动窗口并发布快照（世代号校验：clearOutput 后旧窗口行不得复活） */
    private fun commitBatch(lines: List<OutputEvent.Line>) {
        if (lines.isEmpty()) return
        synchronized(bufferLock) {
            val gen = outputGeneration
            for (line in lines) {
                if (line.generation == gen) outputBuffer.addLast(line.item)
            }
            val overflow = outputBuffer.size - ShellExecutionEngine.MAX_OUTPUT_ITEMS
            if (overflow > 0) {
                repeat(overflow) { outputBuffer.removeFirst() }
                droppedCount += overflow
            }
            // OPEN(重绘滞后): 引擎线程提交输出后，UI 面板要等下一次帧 tick 才上屏。
            // 定性=帧层：重组失效后停在帧钟等待，idle 无输入/无动画即无帧可乘；
            // 主线程状态写即时上屏（链路健康），跨线程提交只能搭既有帧。真机使用
            // 中执行动作伴随输入事件产帧，滞后被自然掩盖；截图验收须以能产生状态
            // 变化的交互强制帧后再拍（纯滚动到底/悬停无变化不产帧）。上游线索：
            // Compose Desktop GlobalSnapshotManager 泵链 + 渲染相位补发语义 + 本机
            // 软件渲染管线；输入框光标闪烁动画是 idle 场景的隐形帧泵（聚焦态滞后
            // 被闪烁帧消解）。暂不修，真机手感由维护者终裁。
            _output.value = OutputState(items = outputBuffer.toList(), droppedCount = droppedCount)
        }
    }

    /**
     * 任务结束（完成/失败/停止）立即冲刷窗口内未提交的行。
     *
     * ack 恒等语义（真门禁——违背即完成态缺尾行）：收集协程活着就必然确认（空闲直通 / 窗口 ≤100ms 内收割信号，
     * 且确认前先提交持有批次）——本函数循环重试等待，不做超时盲排水。持有批次只存在于
     * 收集协程局部，任何「等不到就自己排水」的兜底都与它互不可见，会把尾行留在缓冲外
     * 就放行完成态（输出面板在 Completed 亮起时缺尾行，违背「状态=输出已完整」契约）。
     * 排水兜底只留给收集协程死亡（scope 收尾）的局面——此局面下持有批次已随协程消亡，
     * channel 内残行由排水提交，行不丢。
     */
    internal suspend fun flushPending() {
        val ack = CompletableDeferred<Unit>()
        outputChannel.send(OutputEvent.FlushSignal(ack))
        while (!ack.isCompleted && collectorJob?.isActive == true) {
            withTimeoutOrNull(FLUSH_ACK_POLL_MS) { ack.await() }
        }
        if (!ack.isCompleted) drainAndCommit()
    }

    /** 非挂起 drain：直接取空 channel 并同步提交（防御路径） */
    private fun drainAndCommit() {
        val batch = ArrayList<OutputEvent.Line>(MAX_BATCH)
        while (true) {
            when (val event = outputChannel.tryReceive().getOrNull()) {
                null -> break
                is OutputEvent.FlushSignal -> event.ack.complete(Unit)
                is OutputEvent.Line -> batch.add(event)
            }
            if (batch.size >= MAX_BATCH) {
                commitBatch(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) commitBatch(batch)
    }
}
