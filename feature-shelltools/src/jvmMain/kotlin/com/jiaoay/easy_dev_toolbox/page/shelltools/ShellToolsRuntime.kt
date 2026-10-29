package com.jiaoay.easy_dev_toolbox.page.shelltools

import com.jiaoay.easy_dev_toolbox.core.navigation.NavActivity
import com.jiaoay.easy_dev_toolbox.core.runtime.AppHost
import com.jiaoay.easy_dev_toolbox.core.runtime.AppLifecycleParticipant
import com.jiaoay.easy_dev_toolbox.core.runtime.CapabilityId
import com.jiaoay.easy_dev_toolbox.core.runtime.CapabilityState
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.page.shelltools.history.ShellHistoryManager
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shell 工具运行时（App 级单例）。
 *
 * 持有随进程生命周期的 [CoroutineScope] 与全局唯一 [ShellExecutionEngine] 实例：
 * 执行状态（输出/状态机/loading 态）脱离页面生命周期——切走再切回页面，
 * 输出面板内容仍在、执行中的命令不中断（页面 ViewModel 仅做转发）。
 *
 * 🔴 scope 由 :core 的 AppHost 统一收口——本单例不得自建 `CoroutineScope(SupervisorJob())`
 * （孤儿 scope 无 cancel 路径，进程存续期协程泄漏）；现从 [AppHost.scope] 派生子 scope
 * （退出随 AppHost.shutdown 的根 cancel 一并收）。
 */
object ShellToolsRuntime : AppLifecycleParticipant {

    override val id: String = "shell-tools"
    val scope: CoroutineScope =
        CoroutineScope(AppHost.scope.coroutineContext + SupervisorJob(AppHost.scope.coroutineContext.job))

    /** 全局唯一执行引擎：串行队列 + 流式输出 + 状态机（MAX_OUTPUT_ITEMS 上限兜底） */
    val engine: ShellExecutionEngine = ShellExecutionEngine(scope)

    /**
     * 输出台封顶高度的会话级记忆（进程级持有）：切页保持、拖拽/初始收缩写回；
     * 重启随进程重建回默认 [Dimens.consoleHeightMax]，不落盘不持久化。
     * 「会话级」语义 = 进程生命周期——页面组合生命周期（rememberSaveable）
     * 在无 SaveableStateHolder 的即时切页下会随组合销毁丢值。
     */
    val consoleCapHeight: MutableStateFlow<Dp> = MutableStateFlow(Dimens.consoleHeightMax)

    /**
     * 封顶是否被用户手动拖拽过：未拖拽时封顶随可用高双向跟随（放大恢复全程/
     * 缩小收缩），拖拽后维持「只收不放」尊重手调值；不持久化（重启回 false）。
     */
    val consoleCapUserAdjusted: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * 输出台收起/展开会话态（进程级持有，与 [consoleCapHeight] 同层同口径）：
     * 默认收起——执行后输出台保持头部+输入条（≈150px），不遮挡树区；输出照常
     * 流式落库，反馈由头部状态机/计数徽标与侧栏 NavActivity 承接；折叠钮手动
     * 展开，展开后保持到再收起；不持久化（重启回收起态）。
     */
    val consoleExpanded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * 管理区展开态会话记忆（进程级持有）：切页不丢（PageHomeContent 即时切换分发
     * 无 SaveableStateHolder，组合级保存必丢）；不持久化（重启回展开）。
     */
    val manageExpanded: MutableStateFlow<Boolean> = MutableStateFlow(true)

    /**
     * 输出过滤词会话记忆（进程级持有）：与 [engine] 输出同生命周期——清空输出
     * 不清词，恢复输出后过滤态自动回归；不持久化。
     */
    val outputFilterKeyword: MutableStateFlow<String> = MutableStateFlow("")

    val history: ShellHistoryManager = ShellHistoryManager()

    /** 历史 start 幂等闸（单进程只 start 一轮加载 + 防抖持久化链路） */
    private val historyStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 惰性启动历史（首次触碰时加载 + 挂防抖链路；幂等，多调用方安全） */
    fun ensureHistoryStarted(): ShellHistoryManager {
        if (historyStarted.compareAndSet(false, true)) {
            history.start(scope)
        }
        return history
    }

    /** 退出落盘任务登记（多槽：每笔登记 shutdown 都必须 join，单槽覆盖会静默漏掉先登记者） */
    private val exitFlushJobs = java.util.Collections.synchronizedList(ArrayList<Job>())

    fun requestExitFlush(flush: suspend () -> Unit) {
        exitFlushJobs.add(scope.launch { runCatching { flush() } })
    }

    /** S2 · AppLifecycleParticipant：无启动钩子（引擎惰性；scope 已在对象图就绪）。 */
    override suspend fun start(scope: CoroutineScope) = Unit

    override fun shutdown() {
        // 孤儿进程回收先行：杀当前执行中命令 + 丢弃队列（kill 走 StreamingShell
        // 升级收割链，3s+2s 上限；AppHost 退出序内完成，不拖死退出序）
        engine.shutdownNow()
        if (historyStarted.get()) {
            requestExitFlush { history.flush() }
        }
        val jobs = exitFlushJobs.toList()
        runBlocking {
            withTimeoutOrNull(EXIT_FLUSH_TIMEOUT_MS) { jobs.joinAll() }
        }
    }

    val activity: StateFlow<NavActivity> =
        engine.status.let { status ->
            val mapped = MutableStateFlow(
                when (status.value) {
                    is ExecStatus.Running -> NavActivity.Running
                    is ExecStatus.Failed -> NavActivity.Attention
                    else -> NavActivity.Idle
                },
            )
            scope.launch {
                var lastRunningLabel: String? = null
                status.collect { s ->
                    // 成功召回（与失败的「需关注」对称）：收起态下 Running→Completed 经
                    // 全局 toast 召回——展开态用户正看着输出，不 toast 免噪音；失败态
                    // 清落标签防陈旧（重试链路会重建 Running）
                    if (s is ExecStatus.Running) {
                        lastRunningLabel = s.label
                    } else if (s is ExecStatus.Failed) {
                        lastRunningLabel = null
                    } else if (s is ExecStatus.Completed && !consoleExpanded.value) {
                        lastRunningLabel?.let { label ->
                            StatusSignals.show("完成: $label（输出台待查看）")
                        }
                        lastRunningLabel = null
                    }
                    val next = when (s) {
                        is ExecStatus.Running -> NavActivity.Running
                        is ExecStatus.Failed -> NavActivity.Attention
                        else -> NavActivity.Idle
                    }
                    if (mapped.value != next) mapped.value = next
                }
            }
            mapped.asStateFlow()
        }

    /** S2 · 能力上报：Shell 命令执行是本地能力（恒可用），不上报降级态。 */
    override fun capabilities(): Map<CapabilityId, CapabilityState> = emptyMap()

    /** 退出落盘等待上限（与 VM 侧 `EXIT_FLUSH_TIMEOUT_MS` 同值；超时即放行，不拖死退出序）。 */
    private const val EXIT_FLUSH_TIMEOUT_MS = 2_000L
}