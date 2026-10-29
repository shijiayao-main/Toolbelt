package com.jiaoay.easy_dev_toolbox.page.mcp

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import com.jiaoay.easy_dev_toolbox.core.runtime.AppLifecycleParticipant
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MCP 外放服务生命周期门面。
 *
 * ## 职责边界（Ktor/SDK 类型只在本文件——隔离守护 5.1 钉死）
 * - [startNow]/[stopNow]：页面开关即时启停门面（读配置 → 起/停 embeddedServer CIO +
 *   mcpStatelessStreamableHttp，127.0.0.1 绑定——安全面写死不配置）；
 * - App 退出收口（AppLifecycleParticipant.shutdown：停 server + 取消在途 run）；
 * - 状态面 [status]（运行态/端口/错误——VM/页面经 StateFlow 消费，零 Ktor 类型外泄）。
 *
 * ## 状态机（spec「端口冲突可感知」场景）
 * `STOPPED → STARTING → RUNNING ⇄ STOPPED`；启动失败（端口占用等）→ `FAILED`（带 reason，
 * 不崩溃——页面明示「端口被占用，请修改端口」）。每次启停全新状态对象（快照语义）。
 *
 * ## server 装配缝（单测零 Ktor）
 * [engine] 是**进程内唯一**真正起 Ktor 的接缝（默认实现见 [DefaultEngine]）；
 * 单测注入 fake（状态机迁移/开关配置读序经它驱动），SDK 0.x 不进单测依赖面。
 * run 的取消面（McpRunRegistry）由本 manager 在 stop 时统一收口。
 */
object McpServerManager : AppLifecycleParticipant {

    private const val TAG = "McpServerManager"

    /**
     * 启动中过渡态的 detail 文案：STARTING 态 running=false
     * 但语义是启动中——页面「已停止」徽章判定经本常量排除启动中态（防灰徽章误闪）。
     * 常量双侧同源（manager 写入 + 页面比对），裸串漂移即守护红。
     */
    const val STARTING_DETAIL = "启动中…"

    override val id: String = "mcp-expose"

    /** 服务运行态快照（页面状态卡数据源；每次迁移全新对象）。 */
    data class Status(
        val running: Boolean,
        val port: Int,
        /** 运行态/失败态的说明文案（FAILED 时 = 明示原因，如端口占用）。 */
        val detail: String? = null,
    ) {

        companion object {
            val STOPPED = Status(running = false, port = 0, detail = null)
        }
    }

    private val statusSink = MutableStateFlow(Status.STOPPED)

    /** 状态面（VM/页面订阅；零 Ktor/SDK 类型）。 */
    val status: StateFlow<Status> = statusSink.asStateFlow()

    // ── server 引擎接缝（生产 = Ktor embeddedServer；单测 = fake） ──

    /**
     * 引擎接缝：一次「起 server」的抽象（端口 → 停止句柄）。
     * 生产实现（DefaultEngine）起 `embeddedServer(CIO, 127.0.0.1)` +
     * `mcpStatelessStreamableHttp { McpToolRegistrar.buildServer() }`——
     * 🔴 唯一 Ktor/SDK 出现点之一（隔离守护白名单锚）。
     */
    fun interface McpEngine {
        /** 起端点（阻塞到就绪）；失败抛异常（端口占用 → BindException 家族）。 */
        fun start(port: Int): StoppedHandle

        /** 运行中 server 的停止句柄（幂等）。 */
        fun interface StoppedHandle {
            fun stop()
        }
    }

    /** 生产引擎（Ktor CIO + SDK stateless streamable HTTP——见 [McpEngine]）。 */
    private val engineRef = AtomicReference<McpEngine>(DefaultEngine)

    /** 测试接缝：注入 fake 引擎（生产不得调用）。 */
    var engineForTest: McpEngine
        get() = engineRef.get()
        set(value) {
            engineRef.set(value)
        }

    /** 运行中的停止句柄（null = 未运行）。 */
    private val handleRef = AtomicReference<McpEngine.StoppedHandle?>(null)

    /** start(scope) 时上宿主记录的 scope（run 取消收口用；未 start 时为 null）。 */
    private val scopeRef = AtomicReference<CoroutineScope?>(null)

    /** 在途 run 取消钩（shutdown/stopNow 时统一收口——McpRunRegistry 挂接）。 */
    private val runCancelHookRef = AtomicReference<() -> Unit>(null)

    /** 注册「取消全部在途 run」的收口钩（McpRunRegistry 装配时调用）。 */
    fun registerRunCancelHook(hook: () -> Unit) {
        runCancelHookRef.set(hook)
    }

    // ── 开关门面（页面即时启停） ──

    /**
     * 开启服务（读持久化配置决定端口）。已在运行 = 空操作。
     * 启动失败：状态面迁移 FAILED（明示原因），**不崩溃**（spec 场景）。
     *
     * 🔴 引擎 start 内含端口探询（Ktor 异步 bind 就绪等待——同步 while +
     * Thread.sleep(100)，上限 30s）：该阻塞段绝不可从 Main dispatcher 直调
     *（会把主线程冻住最长 30s，开关翻转期间全 UI 无响应）。
     * 本门面为 suspend：阻塞段整体下放 Dispatchers.IO，调用方（VM 协程）挂起等待
     * 而主线程照常渲染。探询本身保持同步实现（在 IO 线程上同步轮询无妨——
     * 引擎接缝 McpEngine.start 的阻塞契约不变，fake 测试零改动）。
     */
    suspend fun startNow(port: Int) {
        if (handleRef.get() != null) return
        statusSink.value = Status(running = false, port = port, detail = STARTING_DETAIL)
        try {
            val handle = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                engineRef.get().start(port)
            }
            handleRef.set(handle)
            statusSink.value = Status(running = true, port = port, detail = null)
            JLog.i(TAG) { "MCP server 已启动：http://127.0.0.1:$port/mcp" }
        } catch (e: Exception) {
            statusSink.value = Status(
                running = false,
                port = port,
                detail = describeStartFailure(e),
            )
            JLog.e(TAG, e) { "MCP server 启动失败（端口 $port）" }
        }
    }

    /** 停止服务（幂等；取消在途 run + 停 server + 状态面回 STOPPED）。 */
    fun stopNow() {
        val handle = handleRef.getAndSet(null) ?: return
        runCatching { runCancelHookRef.get()?.invoke() }
        runCatching { handle.stop() }
            .onFailure { JLog.e(TAG, it) { "MCP server 停止失败" } }
        statusSink.value = Status.STOPPED
        JLog.i(TAG) { "MCP server 已停止" }
    }

    /** 启动失败的明示文案（spec：端口占用可感知——文案引导改端口）。 */
    internal fun describeStartFailure(e: Exception): String {
        val bindFailed = e::class.qualifiedName?.contains("BindException") == true ||
            (e.cause?.let { c -> c::class.qualifiedName?.contains("BindException") } == true)
        return if (bindFailed) "端口被占用，请修改端口" else "启动失败：${e.message ?: e::class.simpleName}"
    }

    // ── AppLifecycleParticipant ──

    /**
     * 幂等启动：MCP 服务**默认关闭**（恒不自动起），start 只记录 scope +
     * 挂接 run 取消收口钩（McpRunRegistry.cancelAll——server 停止时取消在途 agent 会话），
     * 不起 server。真正起 server 只由页面开关（[startNow]）触发。
     */
    override suspend fun start(scope: CoroutineScope) {
        scopeRef.set(scope)
        registerRunCancelHook { McpRunRegistry.cancelAll() }
    }

    /**
     * agent 会话执行 scope（McpAgentTools 消费——宿主 scope 注入）。
     * start(scope) 已注入时用宿主 scope；未注入（测试/CLI 形态）退回临时自建
     * （SupervisorJob 隔离单 run 失败不互斥——与旧惰性语义等价）。
     */
    fun scopeForAgent(): CoroutineScope =
        scopeRef.get() ?: CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** 退出清理：停 server + 取消在途 run（非 suspend 契约——见 AppLifecycleParticipant）。 */
    override fun shutdown() {
        stopNow()
        scopeRef.set(null)
    }

    /** 测试接缝：复位全部状态（单测用例隔离）。 */
    internal fun resetForTest() {
        handleRef.getAndSet(null)?.let { runCatching { it.stop() } }
        runCancelHookRef.set(null)
        scopeRef.set(null)
        statusSink.value = Status.STOPPED
        // 🔴 engine 一并复位回生产引擎：tearDown 置入的 FakeEngine 会跨类残留——
        // resetForTest 若不清 engine，假启动秒成功（running 断言过）而真端口没人听，
        // HTTP 立即 ConnectException（全量 build 类序不定时间歇挂、单跑恒绿的假象）。
        engineRef.set(DefaultEngine)
    }

    // ── 生产引擎（🔴 唯一 Ktor/SDK 装配点之一——隔离守护白名单锚） ──

    /**
     * 生产引擎：`embeddedServer(CIO)` 绑 127.0.0.1 + SDK stateless streamable HTTP
     * （默认路径 /mcp——SDK 自动装 ContentNegotiation + McpJson，勿自装）。
     * server 实例经 [McpToolRegistrar.buildServer] 装配（工具面单一真源）。
     */
    private object DefaultEngine : McpEngine {
        override fun start(port: Int): McpEngine.StoppedHandle {
            val server = embeddedServer(
                factory = CIO,
                host = "127.0.0.1",
                port = port,
            ) {
                mcpStatelessStreamableHttp {
                    McpToolRegistrar.buildServer()
                }
            }.start(wait = false)
            // 🔴 bind 就绪等待：Ktor start(wait=false) 异步起监听，返回时端口可能还没 bind——
            //    状态面置 RUNNING 前须确认可连（否则 client 立即连接会拒绝；spec「开启即起端点」）。
            //    窗口 30s（全量 build 下 jpackage/jlink 重任务与 allTests 并发抢 CPU，
            //    5s 探询窗口不够——环境敏感 flaky；探询本身零成本，放大窗口只影响
            //    极端慢机器的失败路径）。
            awaitPortBound(port)
            return McpEngine.StoppedHandle { server.stop(gracePeriodMillis = 500, timeoutMillis = 2000) }
        }

        /**
         * 端口可连探询（上限 30s）。🔴 超时必须上抛：
         * 旧契约「超时不上抛」让状态面在 server 未就绪时误置 RUNNING——全量 build
         * 高并发下偶发 server 起不来，旧契约把「没起来」谎报成「运行中」，测试才在
         * HTTP 请求处拿到 ConnectException（误导排查方向）。上抛后走 startNow 的
         * FAILED 异常路径（文案「启动失败」），真实失败面可见。
         */
        private fun awaitPortBound(port: Int) {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                    }
                    return
                } catch (_: java.io.IOException) {
                    Thread.sleep(100)
                }
            }
            throw java.io.IOException("MCP 端点探询 30s 未就绪（端口 $port）——server 未在预算内 bind")
        }
    }
}
