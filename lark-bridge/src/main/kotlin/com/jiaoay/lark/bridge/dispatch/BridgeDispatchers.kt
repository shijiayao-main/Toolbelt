// 具名 dispatcher 与协程作用域树。
// 六个 dispatcher 全部构造注入（本文件只提供**唯一创建点** buildBridgeDispatchers），
//    禁止任何 object/companion 级可变状态；测试可整体替换为 StandardTestDispatcher。
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.lark.bridge.dispatch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * 六个具名 dispatcher（`net` additionally 注入网关）。
 *
 * | dispatcher | 规格 | 承载 | 为什么 |
 * |---|---|---|---|
 * | [intake] | `Dispatchers.Default` | **单个** intake 协程 | 纯 CPU、µs 级、全程无锁 ⇒ 不需要 IO 池；单消费者本身就是并发原语 |
 * | [task] | `Dispatchers.Default` | 每任务一协程 | handler 主体是编排（挂起等子进程），CPU 轻 |
 * | [pipe] | `IO.limitedParallelism(maxConcurrent*2+2, "bridge-pipe")` | 子进程 stdout/stderr 阻塞读 | 每进程 2 条流 ⇒ `*2`，`+2` 给 confirm 自检等旁路管道 |
 * | [net] | `IO.limitedParallelism(8, "bridge-net")` | **所有** SDK 调用 + RateGovernor 退避挂起 | Apache HttpClient / OkHttp 是阻塞 IO，必须与 Default 隔离 |
 * | [sink] | `IO.limitedParallelism(1, "bridge-sink")` | JSONL 写 + 配置原子写 | 单写者 ⇒ 无需文件锁、天然有序 |
 * | [metrics] | `Default.limitedParallelism(1, "bridge-metrics")` | 1Hz 采样 | 采样是 CPU 活但必须串行，单槽避免采样自身并发 |
 *
 * 第 7 个 `bridge-register` 是网关私有，**不在本表**：
 *    `RegisterApp` 阻塞最长 600s，占 `net` 的 1/8 达 10 分钟不可接受。
 * `limitedParallelism` 返回的是 `Dispatchers.IO`/`Default` 的**视图**，不持有线程 ⇒ 无需 shutdown，不构成泄漏。
 */
data class BridgeDispatchers(
    val intake: CoroutineDispatcher,
    val task: CoroutineDispatcher,
    val pipe: CoroutineDispatcher,
    val net: CoroutineDispatcher,
    val sink: CoroutineDispatcher,
    val metrics: CoroutineDispatcher,
) {
    companion object {
        const val NAME_PIPE: String = "bridge-pipe"
        const val NAME_NET: String = "bridge-net"
        const val NAME_SINK: String = "bridge-sink"
        const val NAME_METRICS: String = "bridge-metrics"

        /** `net` 池槽位（网关契约定稿值）。 */
        const val NET_PARALLELISM: Int = 8

        /**
         * `pipe` 池槽位公式：`maxConcurrent*2 + 2`（每 agent 进程 stdout/stderr 两条阻塞读 + 2 条旁路）。
         * 下限 2，防 `maxConcurrentAgents=0` 时 `limitedParallelism(0)` 抛 IllegalArgumentException。
         */
        fun pipeParallelism(maxConcurrentAgents: Int): Int = (maxConcurrentAgents * 2 + 2).coerceAtLeast(2)

        /** 字段上限（BridgeFields 的 Editor.Number(1, 16)），pipe 槽位按它预建（见 [build]）。 */
        const val MAX_CONCURRENT_AGENTS_FIELD_CAP: Int = 16

        /**
         * 六个 dispatcher 的**唯一创建点**。
         * [maxConcurrentAgents] 影响的是**许可上限**而非线程数（`Dispatchers.IO.limitedParallelism`
         * 只是许可闸，线程按需起）——槽位按字段上限 16 预建，HOT 改 maxConcurrentAgents 无需重建
         * （🔴 若按装配期值固化，HOT 扩容后 pipe 许可不随之放宽）。
         */
        fun build(maxConcurrentAgents: Int): BridgeDispatchers = BridgeDispatchers(
            intake = Dispatchers.Default,
            task = Dispatchers.Default,
            pipe = Dispatchers.IO.limitedParallelism(
                pipeParallelism(MAX_CONCURRENT_AGENTS_FIELD_CAP),
                NAME_PIPE,
            ),
            net = Dispatchers.IO.limitedParallelism(NET_PARALLELISM, NAME_NET),
            sink = Dispatchers.IO.limitedParallelism(1, NAME_SINK),
            metrics = Dispatchers.Default.limitedParallelism(1, NAME_METRICS),
        )

        /**
         * 测试替身：六个槽位全部指向同一 [dispatcher]（通常 `StandardTestDispatcher(scheduler)`）。
         * 这让 `runTest` 的虚拟时间对 intake/task/net 全链路生效，RateGovernor 与超时断言才可精确驱动。
         */
        fun all(dispatcher: CoroutineDispatcher): BridgeDispatchers =
            BridgeDispatchers(dispatcher, dispatcher, dispatcher, dispatcher, dispatcher, dispatcher)
    }
}

/**
 * 协程作用域树（方案图逐字）：
 * ```
 * BridgeSupervisor(SupervisorJob)            ← 唯一根，close() 取消全树
 *   └─ BridgeRuntime[childJob]               ← 一次 start/stop 周期
 *        ├─ intakeJob        ① 单消费者 intake
 *        ├─ gatewayJob       gateway.inbound 收集
 *        ├─ signalJob        gateway.signals 收集
 *        ├─ metricsJob       1Hz 采样
 *        ├─ auditSinkJob     审计 JSONL 写
 *        ├─ statsSinkJob     发送者统计 JSONL 写
 *        ├─ confirmBusJob    旁路协程（启动自检推送 / 发送者统计补拉）
 *        └─ taskJobs（SupervisorJob 兄弟，互不牵连）→ processScope
 * ```
 * 🔴 **taskJobs 必须是 SupervisorJob 兄弟**：一个 handler 抛异常/被取消，不得牵连其它任务，
 *    更不得牵连 runtime（「一个任务失败拖垮整条链」由此防止）。
 * 🔴 [BridgeRuntime.job] 用普通 [Job]（非 Supervisor）：runtime 自身失败应向上冒泡到 supervisor 并被观测，
 *    而 supervisor 是 SupervisorJob ⇒ 单个 runtime 崩溃不影响宿主注入的父级。
 */
class BridgeSupervisor private constructor(parent: Job?, private val dispatcher: CoroutineDispatcher) : AutoCloseable {

    /** 全树唯一根。[parent] 非空时挂到宿主 Job 下（父 Job 由宿主构造注入，lib 侧不持有全局单例）。 */
    val job: Job = SupervisorJob(parent)

    /** supervisor 自身的 scope（只跑装配/关停编排，不跑业务）。 */
    val scope: CoroutineScope = CoroutineScope(job + dispatcher)

    /** 开启一次运行周期（对应一次 start）。多次调用得到互相独立的 runtime。 */
    fun newRuntime(dispatchers: BridgeDispatchers): BridgeRuntime = BridgeRuntime(job, dispatchers)

    override fun close() {
        job.cancel()
    }

    companion object {
        /** 独立根（CLI `serve` 场景：无宿主父 Job）。 */
        fun create(dispatcher: CoroutineDispatcher): BridgeSupervisor = BridgeSupervisor(null, dispatcher)

        /** 挂到宿主父 Job 下的 supervisor（App 场景：随宿主生命周期一并取消）。 */
        fun withParent(parent: Job, dispatcher: CoroutineDispatcher): BridgeSupervisor =
            BridgeSupervisor(parent, dispatcher)
    }
}

/**
 * 一次 start/stop 周期的作用域树第二层。八个具名 child Job 在本类构造时一次性创建（无懒初始化、无可变状态）。
 * [taskScope] 为每个任务发放**兄弟 SupervisorJob**，由 [com.jiaoay.lark.bridge.pipeline.ChatLanes] 登记以支持
 * URGENT（停止指令）按 chat 精确取消。
 */
class BridgeRuntime internal constructor(parent: Job, val dispatchers: BridgeDispatchers) {

    /** runtime 的 childJob（方案图 `BridgeRuntime[childJob]`）。取消它即优雅关停本周期全部子协程。 */
    val job: Job = Job(parent)

    /** ① intake 单消费者协程的父 Job。 */
    val intakeJob: Job = Job(job)

    /** gateway.inbound 收集协程。 */
    val gatewayJob: Job = Job(job)

    /** gateway.signals 收集协程。 */
    val signalJob: Job = Job(job)

    /** 1Hz metrics 采样协程。 */
    val metricsJob: Job = Job(job)

    /** 审计 JSONL 写协程（有界内存链路第 4 行的消费者）。 */
    val auditSinkJob: Job = Job(job)

    /** 发送者统计 JSONL 写协程（同上，第二条独立通道）。 */
    val statsSinkJob: Job = Job(job)

    /**
     * 旁路协程父 Job：启动自检推送与发送者统计补拉挂在 [confirmBusScope]。
     * 契约：这些协程不得占用 intake 单消费者、不得在写路径 runBlocking。
     */
    val confirmBusJob: Job = Job(job)

    /**
     * ② Admission 的内务协程（`PermitGate` 缩减并发上限时的后台抽许可协程）。
     * 🔴 不放 `intakeScope`：intake 必须严格单消费者协程，任何额外协程都会破坏
     * 「Intake 单消费者本身就是并发原语」的无锁前提。
     */
    val admissionJob: Job = Job(job)

    val intakeScope: CoroutineScope = CoroutineScope(intakeJob + dispatchers.intake)
    val gatewayScope: CoroutineScope = CoroutineScope(gatewayJob + dispatchers.net)
    val signalScope: CoroutineScope = CoroutineScope(signalJob + dispatchers.intake)
    val metricsScope: CoroutineScope = CoroutineScope(metricsJob + dispatchers.metrics)
    val auditSinkScope: CoroutineScope = CoroutineScope(auditSinkJob + dispatchers.sink)
    val statsSinkScope: CoroutineScope = CoroutineScope(statsSinkJob + dispatchers.sink)
    val confirmBusScope: CoroutineScope = CoroutineScope(confirmBusJob + dispatchers.task)
    val admissionScope: CoroutineScope = CoroutineScope(admissionJob + dispatchers.task)

    /**
     * 为一个任务发放**兄弟 SupervisorJob**（方案图 `taskJobs`）。
     * 兄弟之间互不牵连：任一任务失败/取消只影响自己那棵子树。
     * 返回的 Job 由调用方（Admission）交给 ChatLanes 登记，URGENT 指令据此精确 cancel。
     */
    fun newTaskJob(): Job = SupervisorJob(job)

    /**
     * 子进程作用域（方案图 `processScope`）：跑在 `pipe` 池上，父级是任务 Job。
     * 任务被取消 ⇒ processScope 连带取消 ⇒ ProgramLauncher 的 `finally`+`NonCancellable` 三段式 kill 触发。
     */
    fun processScope(taskJob: Job): CoroutineScope = CoroutineScope(taskJob + dispatchers.pipe)

    /** 在 `task` 池上以兄弟 SupervisorJob 启动一个任务协程（Admission 的唯一 launch 点）。 */
    fun launchTask(block: suspend CoroutineScope.() -> Unit): Job {
        val taskJob = newTaskJob()
        return CoroutineScope(taskJob + dispatchers.task).launch(block = block)
    }

    /** 关停本周期：取消 runtime 及全部子孙（含在途任务）。幂等。 */
    fun cancel() {
        job.cancel()
    }

    /** 只取消在途任务，保留 runtime 骨架（供「重启内核但不断连」场景）。 */
    fun cancelTasksOnly() {
        job.cancelChildren()
    }
}
