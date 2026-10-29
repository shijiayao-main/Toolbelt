package com.jiaoay.easy_dev_toolbox.page.shelltools

import com.jiaoay.easy_dev_toolbox.core.extensions.ShellOutput
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellOutputSource
import com.jiaoay.easy_dev_toolbox.core.extensions.StreamingShell
import com.jiaoay.easy_dev_toolbox.core.extensions.StreamingShellHandle
import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一次提交的串行命令组。
 *
 * @param label 输出面板头部显示的任务名
 * @param commands 逐条串行执行的命令列表
 * @param trackingKey 执行期间写入 activeKeys，驱动列表节点 loading 态（原 executingNodeIds 语义）
 * @param timeoutMs 单条命令超时；null = 不限时（防御缺口——当前全部提交路径显式传档：
 *   节点/历史单条/多选/输入/批量经 VM 300s（NODE_COMMAND_TIMEOUT_MS），手动输入 300s，
 *   链式 30s/步与历史一致；**新增提交路径禁止传 null**——挂死命令会占死串行队列）
 * @param failFast 某条命令非 0 退出即中止后续命令
 */
data class QueuedCommand(
    val label: String,
    val commands: List<String>,
    val trackingKey: String? = null,
    val timeoutMs: Long? = 60_000L,
    val failFast: Boolean = true,
    /**
     * 重写前原串（提交方在 rewrite 前传入；engine 原样透传给 [ExecStatus.Failed]）——
     * 重试链路的唯一可信原串来源
     */
    val originalCommands: List<String>? = null,
    /** 本任务起始步下标（相对 [commands]；失败重跑场景由提交方携带，取代引擎级暂存） */
    val startIndex: Int = 0,
)

/**
 * 统一执行引擎：串行队列 + 流式输出 + 状态机。
 *
 * 所有执行路径（列表节点 / 链式 / 手动输入 / 历史回放 / 批量）都经由本引擎，
 * 输出统一进入 [output]，静默执行也写输出（面板折叠时不可见，展开可查）。
 * 每条命令一个一次性进程（StreamingShell），完成信号 = 进程退出 + 真实 exitCode。
 *
 * 输出性能结构（聚合管线由 [ShellOutputAggregator] 协作者独占持有）：
 * - 追加行发射进 aggregator channel，单一收集协程按"首行直通 + 100ms 窗口收割 +
 *   MAX_BATCH 上限"批量 commit——state 更新频率与重组成本受控
 * - 内部缓冲为 ArrayDeque 滚动窗口（超上限批量 removeFirst + droppedCount 计数，不静默截断）
 * - 状态以 [OutputState] 不可变快照发布；条目携带会话内单调 [ExecOutputItem.seq] 稳定 key
 */
class ShellExecutionEngine(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "ShellExecEngine"

        /** 输出条目上限，超限丢弃最旧，防 logcat 类命令打爆内存
         *  （容量值真源 = Dimens.listBoundedMax，此处常量仅作域内别名） */
        const val MAX_OUTPUT_ITEMS = com.jiaoay.easy_dev_toolbox.core.theme.Dimens.listBoundedMax

        /** 链式任务单步超时，与历史行为一致 */
        const val CHAIN_STEP_TIMEOUT_MS = 30_000L

        /** 手动输入命令默认超时：封死长跑命令（如 adb logcat 类）的内存无界增长；停止按钮可提前终止 */
        const val MANUAL_COMMAND_TIMEOUT_MS = 300_000L

        /**
         * 常规节点/历史回放/多选/输入的命令超时档（300s）：勿取 60s——会误杀默认配置里
         * adb pull/push 类长传输节点；与手动输入 300s 档同值不同语义，不混用
         */
        const val NODE_COMMAND_TIMEOUT_MS = 300_000L
    }

    /** 输出聚合协作者：缓冲/世代号/收集协程的单一属主，对外经 [output] 消费 */
    private val outputAggregator = ShellOutputAggregator(scope)

    val output: StateFlow<OutputState> get() = outputAggregator.output

    private val _status = MutableStateFlow<ExecStatus>(ExecStatus.Idle)
    val status: StateFlow<ExecStatus> = _status.asStateFlow()

    private val _activeKeys = MutableStateFlow<Set<String>>(emptySet())
    val activeKeys: StateFlow<Set<String>> = _activeKeys.asStateFlow()

    /** 已入队尚未开跑的任务数（排队深度反馈：头部「排队 N」徽标消费） */
    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    private val queue = Channel<QueuedCommand>(Channel.UNLIMITED)
    private var workerJob: Job? = null

    private var currentProcessHandle: StreamingShellHandle? = null
    private var currentWatchdogJob: Job? = null

    /** stop() 置位后，当前命令收尾不再覆盖状态 */
    @Volatile
    private var stopping = false

    /** 手动停止请求：置位后当前任务收尾跳过状态覆盖（由 worker 消费后重置），Idle 状态得以保持 */
    @Volatile
    private var manualStopRequested = false

    init {
        startWorker()
    }

    // ── 对外 API ──

    fun submit(task: QueuedCommand) {
        if (stopping) return
        // 使用计数口径：命令启动执行 = 一次真实使用
        // （submit 是全部执行路径的统一汇聚：列表节点/链式/手动/回放/批量）
        ToolUsageSignal.report(ToolUsageSignal.PageId.SHELL_TOOLS)
        val sent = queue.trySend(task)
        if (sent.isSuccess) _queuedCount.update { it + 1 }
    }

    /** 停止当前命令（杀进程）、丢弃队列剩余任务；被丢弃的排队数在输出行可见 */
    fun stop() {
        stopping = true
        // 丢弃队列中未开始的任务（丢弃条数上屏——静默丢队会让「后续点击像死了」无据可查）
        var dropped = 0
        while (queue.tryReceive().isSuccess) { dropped++ }
        _queuedCount.value = 0
        currentWatchdogJob?.cancel()
        val handle = currentProcessHandle
        if (handle != null) {
            // 有执行中命令：kill 触发升级链（destroy → 收割循环限时强杀），
            // 其收尾由 worker 异步完成；manualStopRequested 保证收尾不覆盖 Idle 状态
            manualStopRequested = true
            handle.kill()
        }
        outputAggregator.append(
            ExecOutputItem(
                type = ExecOutputType.Status,
                content = if (dropped > 0) "已手动停止（丢弃排队 $dropped 条）" else "已手动停止",
            )
        )
        _status.value = ExecStatus.Idle
        _activeKeys.value = emptySet()
        stopping = false
        // 停止行立即冲刷（异步，不阻塞 UI；stop 状态置位即时，进程收尾异步完成）
        scope.launch { outputAggregator.flushPending() }
    }

    /**
     * 应用退出序的强制收口：杀当前进程（走 StreamingShell 升级收割链）+ 丢弃队列 +
     * 取消看门狗——防 logcat 类子进程在应用退出后成为孤儿。由 [ShellToolsRuntime.shutdown]
     * 在 AppHost 退出序调用，非 UI 交互入口（UI 停止走 [stop]，保留状态语义）。
     */
    fun shutdownNow() {
        stopping = true
        var dropped = 0
        while (queue.tryReceive().isSuccess) { dropped++ }
        _queuedCount.value = 0
        currentWatchdogJob?.cancel()
        currentProcessHandle?.kill()
        if (dropped > 0) {
            JLog.d(TAG) { "shutdownNow: drained $dropped queued task(s)" }
        }
    }

    fun clearOutput() {
        outputAggregator.clearOutput()
    }

    /** UI 侧追加状态提示行（如导出失败提示），走与输出行相同的聚合链路 */
    fun appendStatusLine(content: String) {
        outputAggregator.appendStatusLine(content)
    }

    // ── 内部实现 ──

    private fun startWorker() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            for (task in queue) {
                if (stopping) continue
                runTask(task)
            }
        }
    }

    private suspend fun runTask(task: QueuedCommand) {
        _queuedCount.update { (it - 1).coerceAtLeast(0) }
        val key = task.trackingKey
        if (key != null) _activeKeys.update { it + key }

        _status.value = if (task.commands.size > 1 || task.startIndex > 0) {
            ExecStatus.Running(
                label = task.label,
                stepIndex = task.startIndex,
                totalSteps = task.commands.size,
                currentCommand = task.commands.getOrNull(task.startIndex) ?: "",
            )
        } else {
            ExecStatus.Running(
                label = task.label,
                stepIndex = 0,
                totalSteps = task.commands.size,
                currentCommand = task.commands.firstOrNull() ?: "",
            )
        }

        var failed = false
        var failedIndex = -1
        var lastExitCode: Int? = null
        // 起始步随任务携带（失败重跑从失败步继续）；钳到列表范围内防越界
        val startIndex = task.startIndex.coerceIn(0, task.commands.size)

        for (i in startIndex until task.commands.size) {
            val cmd = task.commands[i]

            if (task.commands.size > 1) {
                outputAggregator.append(
                    ExecOutputItem(
                        type = ExecOutputType.StepMarker,
                        content = "[${i + 1}/${task.commands.size}] $cmd",
                        stepIndex = i,
                        totalSteps = task.commands.size,
                    )
                )
            } else {
                outputAggregator.append(ExecOutputItem(type = ExecOutputType.Command, content = cmd))
            }

            _status.update {
                if (it is ExecStatus.Running) {
                    it.copy(stepIndex = i, currentCommand = cmd)
                } else it
            }

            val exitCode = executeOne(cmd, task.timeoutMs)
            lastExitCode = exitCode

            if (exitCode == null) {
                // 进程启动失败
                outputAggregator.append(ExecOutputItem(type = ExecOutputType.Stderr, content = "Error: 命令启动失败"))
                failed = true
                failedIndex = i
            } else {
                outputAggregator.append(
                    ExecOutputItem(
                        type = ExecOutputType.Status,
                        content = "exitCode: $exitCode",
                        stepIndex = i,
                        totalSteps = task.commands.size,
                    )
                )
                if (exitCode != 0) {
                    failed = true
                    failedIndex = i
                }
            }

            if (failed) {
                outputAggregator.append(
                    ExecOutputItem(
                        type = ExecOutputType.Stderr,
                        content = "第 ${i + 1} 步失败: $cmd",
                    )
                )
                if (task.failFast) {
                    for (j in (i + 1) until task.commands.size) {
                        outputAggregator.append(
                            ExecOutputItem(
                                type = ExecOutputType.Status,
                                content = "[${j + 1}/${task.commands.size}] 已跳过",
                                stepIndex = j,
                                totalSteps = task.commands.size,
                            )
                        )
                    }
                    break
                }
            }
        }

        if (key != null) _activeKeys.update { it - key }

        if (manualStopRequested) {
            // 手动停止收尾：保持 stop() 已置位的 Idle 状态，仅冲刷输出
            manualStopRequested = false
            outputAggregator.flushPending()
            return
        }
        if (stopping) return

        if (!failed) {
            outputAggregator.append(ExecOutputItem(type = ExecOutputType.Status, content = "完成"))
        }
        // 任务结束立即冲刷窗口内未提交的行，行完整可见后才置位终态
        //（状态是"输出已完整"的可观察信号，见 spec shell-output-panel）
        outputAggregator.flushPending()
        _status.value = if (failed) {
            ExecStatus.Failed(
                label = task.label,
                commands = task.commands,
                failedStepIndex = failedIndex,
                // 失败命令与 exit 码上提头部（稿「执行失败: cmd（exit N）」形态）
                failedCommand = task.commands.getOrNull(failedIndex).orEmpty(),
                exitCode = lastExitCode,
                // 重试保真三件套：原串（重试必须按当前环境重写）、原超时口径、原追踪键
                originalCommands = task.originalCommands.orEmpty(),
                timeoutMs = task.timeoutMs,
                trackingKey = task.trackingKey,
            )
        } else {
            ExecStatus.Completed
        }
    }

    /** 执行单条命令，返回 exitCode；进程启动失败返回 null */
    private suspend fun executeOne(cmd: String, timeoutMs: Long?): Int? {
        // 行净化随命令生命周期：\r 折叠与 ANSI 剥离是控制台展示语义，逐命令重置
        val sanitizer = ConsoleLineSanitizer()
        var handleRef: StreamingShellHandle? = null
        val watchdog = timeoutMs?.let { ms ->
            scope.launch {
                delay(ms)
                JLog.d(TAG) { "watchdog timeout, killing: $cmd" }
                handleRef?.kill()
                outputAggregator.append(
                    ExecOutputItem(
                        type = ExecOutputType.Status,
                        content = "已达超时上限（${ms / 1000} 秒），命令已终止",
                    )
                )
            }
        }

        val result = StreamingShell.executeStreaming(
            command = cmd,
            onStarted = { handle ->
                handleRef = handle
                currentProcessHandle = handle
            },
            onLine = { output ->
                val line = sanitizer.sanitize(output.line)
                if (line != null) {
                    val type = when (output.source) {
                        ShellOutputSource.STDOUT -> ExecOutputType.Stdout
                        ShellOutputSource.STDERR -> ExecOutputType.Stderr
                    }
                    outputAggregator.append(ExecOutputItem(type = type, content = line))
                }
            },
        )

        currentProcessHandle = null

        return result.fold(
            onSuccess = { it.exitCode },
            onFailure = { e ->
                outputAggregator.append(ExecOutputItem(type = ExecOutputType.Stderr, content = "Error: ${e.message}"))
                null
            },
        ).also {
            watchdog?.cancel()
        }
    }
}
