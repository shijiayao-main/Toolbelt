package com.jiaoay.easy_dev_toolbox.core.llm.agent

import com.jiaoay.easy_dev_toolbox.core.data.db.SessionStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 会话 reducer 引擎（三胞胎收敛单源件）。
 *
 * Workbench / Quest / Distill 三个会话类共用本类（委托持有）——「Koog 事件 → ChatEntry」
 * reducer 家族与活动门面的单源，改动须顾及三个消费语境。
 */
class ChatSessionReducer(
    /** 事件流（会话类构造注入——单一真源：会话的 sink/直跑登记/restore 与本引擎共操作同一 flow） */
    private val entries: MutableStateFlow<List<ChatEntry>>,
    /** 落盘桥（null = 不落盘——会话类不传 ledger 的场景） */
    private val ledger: SessionStore? = null,
    /** 落盘 key（时间线三层同 key：runId / flowId / distill-sessionId） */
    private val ledgerKey: String,
    /** 流式打字机面（Workbench/Quest 开、Distill 关） */
    private val streamingEnabled: Boolean = true,
    /**
     * delta 合并窗时长（[ChatEntry.Assistant] KDoc「由消费方节流合并 delta」契约的履行）：
     * null = 不开窗（逐 delta 直发——既有消费语境零变化）；非 null 须同时注入
     * [flushScope]，窗内 delta 只缓冲不发布，窗到期一次性冲刷（全列表重建 ≤ 每窗一次）。
     */
    private val deltaMergeWindowMillis: Long? = null,
    /** 合并窗到期冲刷的延时作用域（null = 不开窗；事件线程与冲刷协程双写点，窗状态持锁访问） */
    private val flushScope: CoroutineScope? = null,
) {

    /** 序号（Activity 文案内匿名 toolCallId 兜底——ChatEntry id 用 ChatEntry.nextId()） */
    private val seq = AtomicLong(0)

    fun onLoopEvent(event: KoogLoopEvent) {
        when (event) {
            is KoogLoopEvent.LlmCallStarting -> appendActivity("思考中（模型 ${event.modelId}）…")

            is KoogLoopEvent.LlmCallCompleted -> {
                // 流式打字机收口：已有 streaming 草稿原位转正（保留 id）；
                // 无草稿直接 appendAssistant。空文本不落数。终稿双写由 [finalizeRun] 消重。
                // 先冲刷合并窗内滞留 delta：草稿缺失时缓冲 delta 尚未发布，
                // 不冲刷会把「应转正的草稿」误判成「无草稿」走 appendAssistant——
                // 终稿文本相同但草稿 id 丢失，与无节流实现产生可见差异。
                flushStreamingWindow()
                val text = event.text
                if (!text.isNullOrBlank()) {
                    val last = entries.value.lastOrNull()
                    if (last is ChatEntry.Assistant && last.streaming) {
                        entries.update { list -> list.dropLast(1) + ChatEntry.Assistant(id = last.id, text = text) }
                        persistEntry(entries.value.last())
                    } else {
                        appendAssistant(text)
                    }
                }
            }

            is KoogLoopEvent.ToolCallStarting -> {
                appendActivity("调用 ${event.toolName}…")
                appendToolCall(
                    toolCallId = event.toolCallId ?: "anon-${seq.incrementAndGet()}",
                    toolName = event.toolName,
                    argsSummary = event.argsSummary,
                )
            }

            is KoogLoopEvent.ToolCallCompleted -> completeToolCallByKey(event.toolCallId, event.observation)

            is KoogLoopEvent.ToolCallFailed -> failToolCallByKey(event.toolCallId, "工具 ${event.toolName} 失败：${event.error}")

            is KoogLoopEvent.ExecutionFailed -> appendError(ErrorKind.FAILED, "执行失败", event.error.take(400))

            is KoogLoopEvent.StreamingFrame -> {
                if (!streamingEnabled) return
                // delta 累积进末尾 streaming 草稿（无则建）；终稿在 LlmCallCompleted 统一转正。
                val delta = event.delta ?: return
                if (delta.isEmpty()) return
                if (deltaMergeWindowMillis == null || flushScope == null) {
                    publishDelta(delta)
                } else {
                    offerDeltaToWindow(delta)
                }
            }
        }
    }

    /**
     * 流式草稿收口 + 终稿消重（原三胞胎逐字共享段单源化）：runJob 收尾统一处理。
     * - 草稿悬挂（流中断无 LlmCallCompleted）：output 空则草稿转正兜底 / 非空丢弃草稿按 output 落；
     * - 终稿消重：末条 assistant 与 output 同文本跳过（singleRunStrategy 下两者恒同文本）。
     *
     * 先冲刷合并窗内滞留 delta 再读末条（正确性硬点）：不冲刷则草稿文本缺末段，
     * output 空兜底转正时末段永久丢失。
     */
    fun finalizeRun(output: String) {
        flushStreamingWindow()
        val last = entries.value.lastOrNull()
        if (last is ChatEntry.Assistant && last.streaming) {
            entries.update { list -> list.dropLast(1) }
            if (output.isBlank()) {
                if (last.text.isNotBlank()) appendAssistant(last.text)
                return
            }
        }
        val lastEntry = entries.value.lastOrNull()
        if (output.isNotBlank() && (lastEntry !is ChatEntry.Assistant || lastEntry.text != output)) {
            appendAssistant(output)
        }
    }

    /** 条目重放（历史回看——会话类 restore 入口：整体替换 + 重置运行中态） */
    fun restore(list: List<ChatEntry>) {
        // 窗内滞留 delta 一并丢弃：整体替换后到期冲刷会把旧 delta 追加成新列表的幽灵草稿
        resetStreamingWindow()
        entries.value = list
    }

    /** 条目清空（会话 reset——蒸馏阶段切换用） */
    fun clear() {
        resetStreamingWindow()
        entries.value = emptyList()
    }

    /** 追加错误横幅（会话类管线中止/阶段失败登记复用——含落盘） */
    fun appendError(kind: ErrorKind, title: String, detail: String?) {
        entries.update { it + ChatEntry.ErrorBanner(id = ChatEntry.nextId(), kind = kind, title = title, detail = detail) }
        persistEntry(entries.value.last())
    }

    /** 用户条目追加（Quest 的 appendUser——多轮对话形态独有；含落盘） */
    fun appendUser(text: String) {
        entries.update { it + ChatEntry.User(id = ChatEntry.nextId(), text = text) }
        persistEntry(entries.value.last())
    }

    /** 时间线追加工具卡（Workbench 直跑降级路径——管线产物手工回补；终态卡落盘） */
    fun appendToolCallCard(
        toolCallId: String,
        toolName: String,
        argsSummary: String,
        state: ToolState,
        observation: String?,
        artifacts: List<ArtifactChip>? = null,
    ) {
        entries.update {
            it + ChatEntry.ToolCall(
                id = ChatEntry.nextId(),
                toolCallId = toolCallId,
                toolName = toolName,
                argsSummary = argsSummary,
                state = state,
                observation = observation,
                artifacts = artifacts ?: emptyList(),
            )
        }
        if (state != ToolState.RUNNING) persistEntry(entries.value.last())
    }

    /** 工具产物精确挂载（Workbench/Quest 的 sink 十八行配方单源化——chips 挂末尾同名工具卡） */
    fun attachArtifactsToLastToolCall(name: String, chips: List<ArtifactChip>) {
        entries.update { list ->
            list.mapIndexed { index, entry ->
                // 挂到末尾最近一条同名工具卡（串行会话内即本轮调用——万能解密蓝本配方）
                if (index == list.lastIndex && entry is ChatEntry.ToolCall && entry.toolName == name) {
                    entry.copy(artifacts = chips)
                } else {
                    entry
                }
            }
        }
    }

    // ── 条目操作（落盘桥：只落稳态条目） ──

    private fun appendActivity(text: String) {
        entries.update { it + ChatEntry.Activity(id = ChatEntry.nextId(), text = text, running = true) }
    }

    private fun appendAssistant(text: String) {
        entries.update { it + ChatEntry.Assistant(id = ChatEntry.nextId(), text = text) }
        persistEntry(entries.value.last())
    }

    private fun appendToolCall(toolCallId: String, toolName: String, argsSummary: String) {
        entries.update {
            it + ChatEntry.ToolCall(
                id = ChatEntry.nextId(),
                toolCallId = toolCallId,
                toolName = toolName,
                argsSummary = argsSummary,
                state = ToolState.RUNNING,
            )
        }
    }

    private fun completeToolCallByKey(toolCallId: String?, observation: String?) {
        entries.update { list ->
            list.map { entry ->
                if (entry is ChatEntry.ToolCall && entry.toolCallId == toolCallId) {
                    entry.copy(state = ToolState.COMPLETED, observation = observation)
                } else {
                    entry
                }
            }
        }
        settleToolCard(toolCallId)
    }

    private fun failToolCallByKey(toolCallId: String?, reason: String) {
        entries.update { list ->
            list.map { entry ->
                if (entry is ChatEntry.ToolCall && entry.toolCallId == toolCallId) {
                    entry.copy(state = ToolState.FAILED, observation = reason.take(2000))
                } else {
                    entry
                }
            }
        }
        settleToolCard(toolCallId)
    }

    /** 终态工具卡落盘（同内容追加——SessionStore append 新 seq 语义） */
    private fun settleToolCard(toolCallId: String?) {
        val card = entries.value.lastOrNull { it is ChatEntry.ToolCall && it.toolCallId == toolCallId }
            as? ChatEntry.ToolCall ?: return
        persistEntry(card)
    }

    /** 落盘桥：只落稳态条目（Assistant/ErrorBanner/终态 ToolCall） */
    private fun persistEntry(entry: ChatEntry) {
        ledger?.append(ledgerKey, entry)
    }

    // ── delta 合并窗（[ChatEntry.Assistant] 节流契约——窗内缓冲、到期冲刷） ──

    /** 窗状态双写点（事件线程 + 冲刷协程）：pending 缓冲与冲刷 job 一律持本锁访问 */
    private val windowLock = Any()
    private var pendingDelta: StringBuilder? = null
    private var flushJob: Job? = null

    /**
     * delta 入窗：窗首 delta 立即发布（首字零延迟），随后窗内 delta 只缓冲；
     * 窗到期一次性冲刷缓冲（发布节奏 ≤ 每窗一次）。
     */
    private fun offerDeltaToWindow(delta: String) {
        val scope = flushScope ?: return publishDelta(delta)
        val windowMillis = deltaMergeWindowMillis ?: return publishDelta(delta)
        synchronized(windowLock) {
            (pendingDelta ?: StringBuilder().also { pendingDelta = it }).append(delta)
            if (flushJob != null) return
            publishPendingLocked()
            flushJob = scope.launch {
                delay(windowMillis)
                synchronized(windowLock) {
                    flushJob = null
                    publishPendingLocked()
                }
            }
        }
    }

    /** 收口冲刷（LlmCallCompleted/finalizeRun 用）：取消到期 job + 滞留 delta 强制发布 */
    private fun flushStreamingWindow() {
        synchronized(windowLock) {
            flushJob?.cancel()
            flushJob = null
            publishPendingLocked()
        }
    }

    /** 窗状态丢弃（restore/clear 整体替换用）：滞留 delta 不再属于新列表 */
    private fun resetStreamingWindow() {
        synchronized(windowLock) {
            flushJob?.cancel()
            flushJob = null
            pendingDelta = null
        }
    }

    /** 冲刷缓冲（持锁调用）：空缓冲零发布——窗到期无新 delta 时不开新发布 */
    private fun publishPendingLocked() {
        val buf = pendingDelta ?: return
        pendingDelta = null
        publishDelta(buf.toString())
    }

    /** streaming 草稿追加（无则建）——开窗与否共用的唯一发布路径 */
    private fun publishDelta(delta: String) {
        entries.update { list ->
            val last = list.lastOrNull()
            if (last is ChatEntry.Assistant && last.streaming) {
                list.dropLast(1) + last.copy(text = last.text + delta)
            } else {
                list + ChatEntry.Assistant(id = ChatEntry.nextId(), text = delta, streaming = true)
            }
        }
    }
}

class NavActivityFacade {

    private val activitySink = MutableStateFlow(com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Idle)
    private var mirrorJob: Job? = null

    /** 侧栏活动指示流（宿主经 ToolPageRegistration.navActivity 消费） */
    val activity: StateFlow<com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity> = activitySink.asStateFlow()

    /** VM attach 镜像（session running → 活动指示；切会话先收旧镜像） */
    fun attach(running: StateFlow<Boolean>, scope: CoroutineScope) {
        detach()
        mirrorJob = scope.launch {
            running.collect { isRunning ->
                activitySink.value = if (isRunning) {
                    com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Running
                } else {
                    com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Idle
                }
            }
        }
    }

    fun detach() {
        mirrorJob?.cancel()
        mirrorJob = null
        activitySink.value = com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity.Idle
    }
}
