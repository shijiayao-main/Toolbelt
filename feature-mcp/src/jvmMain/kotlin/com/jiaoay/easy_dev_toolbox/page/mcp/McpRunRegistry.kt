package com.jiaoay.easy_dev_toolbox.page.mcp

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * agent 会话 run 登记表。
 *
 * ## 定位
 * MCP 每次工具调用 = 独立 run（stateless 会话模型：client 断连不杀 run、结果可经
 * `workbench_status` 工具按 runId 两段式查询）。本表是 runId → RunHandle 的进程内登记面。
 *
 * ## 容量上限
 * [MAX_RUNS] = 10：超出拒绝新会话（防资源耗尽——研判/蒸馏是长流程，无上限会堆积）。
 *
 * ## 生命周期收口
 * server 停止时经 [McpServerManager.registerRunCancelHook] 统一取消在途 run
 * （[cancelAll]；spec「退出不残留监听」）。
 *
 * 零 SDK 类型（纯 Kotlin 数据结构）——隔离面（McpAgentTools 是消费方）。
 */
object McpRunRegistry {

    /** 在途 run 容量上限（防资源耗尽）。 */
    const val MAX_RUNS = 10

    /** run 进度阶段（研判/蒸馏共用的粗粒度状态面）。 */
    enum class Phase { RUNNING, COMPLETED, CANCELLED, FAILED }

    /** 单个 run 的登记句柄。 */
    class RunHandle(
        val runId: String,
        /** 工具名（workbench_analyze / distill_run——日志与 status 查询标注）。 */
        val toolName: String,
    ) {
        private val phaseSink = MutableStateFlow(Phase.RUNNING)

        /** 进度面（workbench_status 查询工具的数据源）。 */
        val phase: StateFlow<Phase> = phaseSink.asStateFlow()

        /** 进度说明文本（阶段/工具调用/完成度——progress 通知同源文案）。 */
        val progressNote = MutableStateFlow("")

        /** 结果文本（研判报告/蒸馏候选产物；null = 未完成）。 */
        val result = MutableStateFlow<String?>(null)

        /** 取消钩（由会话执行体注入——cancel() 经 Job.cancel 传播 CE）。 */
        internal var cancelHook: () -> Unit = {}

        /** 已产出的阶段产物路径集（中止保全——spec「已产出的阶段产物保留回传」）。 */
        val artifacts = MutableStateFlow<List<String>>(emptyList())

        /**
         * 收尾（终态只许迁移一次：RUNNING → COMPLETED/CANCELLED/FAILED）。
         * 🔴 三终态迁移点必 bump [McpRunRegistry.runsVersionSink]——在途数口径是
         * RUNNING 计数，终态迁移即「在途数减一」的观测面变化，不 bump 则页面
         * 「N/10」与确认框计数把终态 run 算在途（直到 sweep 才修正）。
         */
        fun complete(text: String) {
            result.value = text
            phaseSink.value = Phase.COMPLETED
            bumpVersionForTerminal()
        }

        /** 中止保全收尾（部分结果以「已中止」标记回传）。 */
        fun cancelWithPartial(partial: String?) {
            if (partial != null) result.value = "【已中止】部分结果：\n$partial"
            phaseSink.value = Phase.CANCELLED
            bumpVersionForTerminal()
        }

        fun fail(reason: String) {
            result.value = "【失败】$reason"
            phaseSink.value = Phase.FAILED
            bumpVersionForTerminal()
        }

        private fun bumpVersionForTerminal() {
            runsVersionSink.update { it + 1 }
        }

        internal fun cancel() {
            cancelHook()
        }
    }

    private val runs = ConcurrentHashMap<String, RunHandle>()

    /**
     * 登记面变更版本号（在途数响应式化）。
     *
     * 一切改变「在途数」的入口处自增：register/sweepFinished/cancelAll/cancel 四入口，
     * 加上 RunHandle 三终态迁移（在途数口径是 RUNNING 计数，终态迁移即在途数减一，
     * 不 bump 则页面计数把终态 run 算在途直到 sweep）（ShellDebugViewModel
     * .messagesVersion 先例同构）——观测侧（McpExposeViewModel）经 map { activeCount }
     * 派生响应式计数流，外部 MCP client 起 run 后页面计数即时跟随，消灭
     * 「数字恒 0」的重组盲区。
     */
    private val runsVersionSink = MutableStateFlow(0)
    val runsVersion: StateFlow<Int> = runsVersionSink.asStateFlow()

    /**
     * 登记新 run。容量超限时返回 null（调用方翻译为「在途会话过多」错误文案）。
     * 🔴 容量判定前先 [sweepFinished]：sweep 的生产调用点都在 register 成功路径之后，
     * 满表被拒一次后 sweep 永不可达 = 结构性死锁（峰值 10 run + 1 终态即触发）——
     * sweep 前置让终态占位可回收，「请稍后重试」的错误文案才可能成立。
     */
    fun register(runId: String, toolName: String): RunHandle? {
        sweepFinished()
        if (runs.size >= MAX_RUNS) return null
        val handle = RunHandle(runId, toolName)
        runs[runId] = handle
        runsVersionSink.update { it + 1 }
        return handle
    }

    /** 按 runId 查询（stateless 两段式：client 经 workbench_status 查询）。 */
    fun find(runId: String): RunHandle? = runs[runId]

    /**
     * 当前在途数（观测/管理面）——🔴 按 RUNNING 相位计数而非表行数：终态 run 在
     * sweep 前仍占表行，「N/10」/确认框/端口门控把终态算在途会让全终态仍拦端口修改。
     */
    val activeCount: Int get() = runs.values.count { it.phase.value == Phase.RUNNING }

    /** 全部 run 快照（状态卡展示面——运行中计数用）。 */
    fun snapshot(): List<RunHandle> = runs.values.toList().sortedBy { it.runId }

    /** 结束态 run 清退（容量回收——COMPLETED/CANCELLED/FAILED 三态可清）。 */
    fun sweepFinished() {
        val iterator = runs.entries.iterator()
        var swept = false
        while (iterator.hasNext()) {
            if (iterator.next().value.phase.value != Phase.RUNNING) {
                iterator.remove()
                swept = true
            }
        }
        if (swept) runsVersionSink.update { it + 1 }
    }

    /** 取消全部在途 run（server 停止/App 退出收口——经 McpServerManager 的取消钩挂接）。 */
    fun cancelAll() {
        var hitAny = false
        runs.values.forEach { run ->
            if (run.phase.value == Phase.RUNNING) {
                run.cancel()
                hitAny = true
            }
        }
        if (hitAny) runsVersionSink.update { it + 1 }
    }

    /** 取消单个 run（MCP cancelled 通知 → RunHandle.cancel——spec「取消保全」）。 */
    fun cancel(runId: String): Boolean {
        val run = runs[runId] ?: return false
        if (run.phase.value != Phase.RUNNING) return false
        run.cancel()
        runsVersionSink.update { it + 1 }
        return true
    }

    /** 测试接缝：清空登记表。 */
    internal fun resetForTest() {
        runs.clear()
        runsVersionSink.value = 0
    }
}
