// 下载进度卡：引用流的「下载 + 预处理」阶段可见性。
// 与 agent 过程卡**分离**（用户拍板）：卡片语义不混合、避免耦合、便于未来扩展；
//    「单卡收敛」语义只约束 agent 场景，故一次任务可能同时出现两张卡（下载卡 + agent 卡）。
package com.jiaoay.lark.bridge.pipeline

import com.jiaoay.lark.bridge.agent.ProgressAccumulator
import com.jiaoay.lark.gateway.CardStreamSink
import com.jiaoay.lark.gateway.CardTitleColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 引用文件「下载 + 预处理」阶段的独立进度卡会话。
 *
 * ## 为什么需要它
 * 引用流的下载与预处理发生在 `BuiltinHandlers.resolveReferencedPrompt`（agent 管线**之前**），
 * 而 agent 过程卡要等 `runAgentPipeline` 才开——下载期间用户零可见反馈（大文件静默数分钟）。
 * 用户拍板：**新建独立下载卡**，不与 agent 过程卡混用同一张（语义分离，便于未来扩展）。
 *
 * ## 两阶段生命周期：「真的需要下载才开卡」
 * 维护者选定的判据是**真的需要下载才开卡**，故本类刻意拆成两段，而非在 `resolve` 之前就发卡：
 *
 * | 阶段 | 方法 | 副作用 |
 * |---|---|---|
 * | ① 闸门判定 | [of] | **零副作用**——不建 sink、不发 API；未过闸门返回 null |
 * | ② 真正开卡 | [onStarted]（**suspend**） | 此刻才 `reply.stream` 建 sink + 起泵 + 起心跳 |
 *
 * 为什么必须这么拆（第一版踩的坑）：若在 `resolve` 之前就发卡，**纯文本引用**（只走
 * `im.v1.message.get` 取原文、压根不下载）也会收到一张空卡——正是判据要排除的噪音。
 * 拆成两段后：文本引用路径 [onStarted] 从不被调用 ⇒ 一个 API 都不发。
 *
 * ## 为什么是「线性阶段表」而非 `ProgressAccumulator` 的滚动窗口
 * 下载阶段文案是**离散、已知有限**的（「准备引用文件 x…」→「已下载 x，预处理中…」），
 * 而 `ProgressAccumulator` 的滚动窗口（默认 6 条，丢最旧）会把**早期阶段挤出去**——
 * 用户最想看的恰恰是「准备下载」那一行。故本会话**线性累积**全部阶段文案
 * （加 `cardMaxChars` 硬截断兜底防超长），不做窗口滚动、不做相邻合并。
 *
 * ## [pending] 缓冲：开卡前的阶段文案不丢
 * resolver 有一条阶段文案（「已找回被引用文件 x，开始下载…」）发生在**开卡信号之前**。
 * 若直接丢弃，用户会看到卡片从「准备引用文件」开始、缺了「已找回」那一步。故未开卡时
 * [append] 先入 [pending]（**有界**，防无界增长），[onStarted] 时一次性铺上。
 * 反之若始终未开卡（文本引用），[pending] 随会话一同被丢弃——零泄漏。
 *
 * ## 能力上限（README 级说明，勿误判为实现偷懒）
 * **进度粒度只能是「阶段级」，做不了百分比/字节数**：
 * - SDK `im().v1().messageResource().get()` **全量入堆无 InputStream 路径**（SDK 2.8.5 硬约束）
 *   ⇒ 下载过程中拿不到已传字节数；
 * - 入站事件 `InboundResource` 只有 type/fileKey/fileName/durationMs、**无 size 字段**
 *   ⇒ 连「总共多大」都预知不了，无法显示「x/y MB」。
 *
 * ## 复用而非自造
 * 通道仍是 [`ConflatedSlot`]（1 槽 conflate）+ [`pumpSnapshotsToCard`] 泵 + [`CardJson`]
 * 的 streaming/terminal 工厂——遵守「有界内存链路」纪律，不另造队列、不手搓卡片 JSON。
 * 与 `ctx.progress` **互不干扰**：resolver 既有的 `report` 文案逐字保留喂 progress
 * （它们服务空闲看门狗与审计），本会话走独立通路——这是「语义不混合」的代码级体现。
 */
internal class DownloadCardSession(
    private val ctx: RequestContext,
    private val maxChars: Int,
    /**
     * 心跳周期。可注入**只为测试**：生产恒用 [HEARTBEAT_MILLIS]（10s），而测试不可能真等 10s
     * ——不给这个参数就只能靠拉长超时硬等，测试会慢到没人愿意跑。
     */
    private val heartbeatMillis: Long = HEARTBEAT_MILLIS,
) {

    private val slot = ConflatedSlot<String>(1)
    private val stages = mutableListOf<String>()

    /** 开卡前的阶段文案缓冲（有界：阶段数本就有限，上限只是防未来失控）。 */
    private val pending = mutableListOf<String>()
    private var started = false
    private var closed = false
    private var sink: CardStreamSink? = null
    private var heartbeat: Job? = null

    /**
     * 追加一条阶段文案。
     *
     * 未开卡时进 [pending]（不丢弃、不上卡）；已开卡时线性累积并推一帧快照。
     * [closed] 后一律丢弃（收尾已发终态卡，再 append 只会造出「终态后又变动」的矛盾观感）。
     */
    fun append(text: String) {
        if (closed) return
        if (!started) {
            if (pending.size < PENDING_MAX) pending += text
            return
        }
        stages += text
        slot.offer(render())
    }

    /**
     * 下载卡**真正开卡**——由 resolver 的 `onDownloadStarted` 信号触发（幂等，多文件只开一次）。
     *
     * 这是唯一的 `<API>` 副作用点：此刻才发 `reply.stream` 建流式 sink。
     * 建卡失败（网络/配额/权限）⇒ **降级为无下载卡**：`started` 保持 false，只 WARN 不抛出
     * （卡片是纯观测能力，绝不能阻断下载这条主链路）。
     *
     * 为什么是 suspend：[startPump]/[startHeartbeat] 都要取 `coroutineContext` 把协程
     * 挂到**当前任务 Job** 下（见各自 KDoc）。
     */
    suspend fun onStarted() {
        if (started || closed) return
        val created = runCatching {
            ctx.reply.stream(
                CardJson.streaming(
                    title = TITLE,
                    markdown = "> 📥 正在准备引用文件…",
                    titleColor = CardTitleColor.BLUE,
                ),
            )
        }.onFailure { ctx.log.warn("下载卡开启失败（降级为无下载卡）：${it.message}") }.getOrNull()
        if (created == null) {
            // 降级：后续 append 继续进 pending（无害），收尾时因 sink==null 只关槽。
            pending.clear()
            return
        }
        started = true
        sink = created
        stages += pending
        pending.clear()
        append(STAGE_STARTED)
        startPump(created)
        startHeartbeat()
    }

    /** 当前渲染快照（未被泵消费时也供测试断言）。 */
    fun render(): String = buildString {
        stages.forEach { appendLine("> 📥 $it") }
    }.let { if (it.length > maxChars) it.take(maxChars) else it }

    /**
     * 终态收尾：发终态卡 + 关闭流 + **关闭 conflate 槽**。幂等（重复调用无副作用）。
     *
     * **从未开卡**（`!started`，即纯文本引用等「真的不需要下载」路径）时**完全 no-op**：
     * 一个 API 都不发——否则用户会收到一张「引用文件已就绪」的空终态卡，正是判据要排除的噪音。
     */
    suspend fun flushAndClose(terminal: TerminalState) {
        if (closed) return
        closed = true
        heartbeat?.cancel()
        val s = sink
        if (s != null) {
            runCatching {
                s.flushTerminal(
                    CardJson.terminal(
                        title = terminal.title,
                        markdown = render(),
                        status = terminal.status,
                    ),
                )
            }
            runCatching { s.close() }
        }
        // 🔴 **无条件**关闭 conflate 槽：泵循环 `while (sink.isOpen) { slot.receive() ?: break }`，
        //    槽不关 ⇒ `receive` 永久挂起 ⇒ 泵协程挂在**任务 Job** 上永不退出 ⇒ 结构化并发等它，
        //    任务永远不完成（第一版实测：管线 `completed=0`、无任何出站消息）。
        slot.close()
    }

    /**
     * 启动消费槽的泵：把最新快照全量替换上卡（与 agent 过程卡同款形态）。
     *
     * 🔴 协程形态必须 `CoroutineScope(coroutineContext).launch`（**不是** `supervisorScope`
     * /`coroutineScope`——那两个是挂起函数，会等子协程结束才返回，重演 `AgentPipelinePumpTest`
     * 钉死的死锁）。挂在任务 Job 下 ⇒ 任务取消时随结构化并发一并取消。
     */
    private suspend fun startPump(created: CardStreamSink) {
        CoroutineScope(coroutineContext).launch {
            runCatching { pumpSnapshotsToCard(slot = slot, sink = created) }
                .onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) ctx.log.warn("下载卡泵异常：${it.message}")
                }
        }
    }

    /**
     * 启动心跳：周期性 `progress.touch()` 喂空闲看门狗。
     *
     * 🔴 修一个既有隐患：resolver 在「准备引用文件」到最后一次上报之间
     * **不发 touch**，且 `downloadResource` 内部还有 `Semaphore(2)` 排队期——单文件下载超过
     * `runtime.agentIdleTimeoutSeconds`（默认 120s）时，任务会被 `Admission` 的空闲看门狗
     * **误判为卡死并取消**（用户看到超时失败，实际下载正常）。
     *
     * 为什么用 `touch()` 而不是 `append("心跳…")`：`touch` 只写 `lastActivityMillis`，
     * 不写事件、不动阶段表、不动丢帧计数 ⇒ **不污染卡内容与丢帧统计**；若用 append，
     * 心跳会把真实的阶段文案挤出卡片。
     *
     * 🔴 同样挂在任务 Job 下 ⇒「停止指令」的 `cancelAll`/超时随结构化并发立即取消，
     * `delay` 是可取消挂起点 ⇒ **心跳绝不阻止取消**、也不会把 `lastActivity` 续命到取消之后。
     */
    private suspend fun startHeartbeat() {
        val progress = ctx.progress
        heartbeat = CoroutineScope(coroutineContext).launch {
            while (isActive) {
                delay(heartbeatMillis)
                progress.touch()
            }
        }
    }

    /** 终态形态（成功/失败的文案与颜色）。 */
    internal data class TerminalState(val title: String, val status: TaskStatus) {
        companion object {
            val DONE = TerminalState("📥 引用文件已就绪", TaskStatus.SUCCESS)
            val FAILED = TerminalState("📥 引用文件准备失败", TaskStatus.FAILURE)
        }
    }

    companion object {
        /** 心跳周期：必须显著小于 `agentIdleTimeoutSeconds`（默认 120s），且留足检查粒度余量。 */
        internal const val HEARTBEAT_MILLIS: Long = 10_000L

        /** 卡片标题（与 agent 的「🤖 Agent 已受理」明确区分）。 */
        internal const val TITLE = "📥 引用文件准备中…"

        /** 开卡时补上的首行（[pending] 里的是开卡前已发生的阶段）。 */
        internal const val STAGE_STARTED = "已找到引用文件，准备下载…"

        /** [pending] 上限（阶段数本就有限，此值只是防未来失控的兜底）。 */
        internal const val PENDING_MAX = 32

        /**
         * 闸门判定：返回 null 表示「本路径不显示下载卡」。
         *
         * **零副作用**——本方法不建 sink、不发任何 API、不启协程；它只回答「若真需要下载，
         * 这张卡能不能开」。真正的开卡在 [onStarted]（见类 KDoc 的两阶段说明）。
         *
         * 闸门（复用既有开关，**不新增配置键**——新增会造出「有下载卡但 agent 进度关着」等
         * 正交组合，与「不混合语义」诉求反向）：
         * - `progress is ProgressAccumulator`：进度能力可用（`progressOverride` 已装配）；
         * - `agent.progress.enabled`：流式卡总开关（关闭时 stream-json 与卡片链路一并跳过）；
         * - `fileMessage.enabled`：引用流总开关（关闭时不可能出现下载卡）。
         *
         * 读的是 `ctx.liveConfig`（即 `RequestContext.config`——构造期快照、不可变 `val`）而非
         * `HandlerDeps.configProvider()`：本闸门只在**开卡瞬间**判一次（`enabled` 事后改 false 不影响
         * 已开卡的收尾），与 `runAgentPipeline` 读 `ctx.liveConfig.agent.progress.enabled` 同口径；
         * 且 `RequestContext` 不持 `configProvider`，为这一处判定增加接线收益为零。
         *
         * @param heartbeatMillis 心跳周期，**仅供测试**注入更短的周期（生产调用点一律用默认值）。
         */
        fun of(ctx: RequestContext, heartbeatMillis: Long = HEARTBEAT_MILLIS): DownloadCardSession? {
            val cfg = ctx.liveConfig
            if (!cfg.agent.progress.enabled) return null
            if (!cfg.fileMessage.enabled) return null
            if (ctx.progress !is ProgressAccumulator) return null
            return DownloadCardSession(ctx, cfg.agent.progress.cardMaxChars, heartbeatMillis)
        }
    }
}

/**
 * 下载卡收尾的 `NonCancellable` 包装（与 `runAgentPipeline` 的终态收尾同款纪律）。
 * 取消（停止指令/超时）落在下载期间也必须把卡收尾，否则重演「幽灵卡永驻生成中 + 无限占住 openStreams」。
 */
internal suspend fun DownloadCardSession?.flushAndCloseNonCancellable(state: DownloadCardSession.TerminalState) {
    if (this == null) return
    withContext(NonCancellable) { flushAndClose(state) }
}
