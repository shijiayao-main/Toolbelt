package com.jiaoay.lark.bridge.exec

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
// Stream.toList 扩展（Kotlin 侧 JDK 11 可用形态）：无此 import 时 Stream.toList 解析为
// Java 16+ 成员方法，-Xjdk-release=11 下编译报错、JDK 11 运行时抛 NoSuchMethodError
// （被 runCatching 折叠 → 级联终止静默降级只杀直接子进程）
import kotlin.streams.toList

/**
 * 子进程一行输出（区分来源，供结构化事件 / 进度解析器消费）。
 * 🔴 stdout 与 stderr **分流不合并**（不用 redirectErrorStream）——解析器需区分正常输出与诊断/错误。
 */
data class AgentLine(
    val source: Source,
    val text: String,
) {
    enum class Source { STDOUT, STDERR }
}

/**
 * argv 程序调用请求（**argv 语义**，非 shell 命令行语义）。
 * @param command 已构造好的 argv 列表（Windows `.cmd`/`.bat` 包装由 [LaunchCommand.spawnCommand] 产出后传入）
 * @param workingDir 工作目录（非空时经 [WorkspacePolicy] 多根沙箱校验；null = 不约束目录，env 白名单仍生效）
 * @param extraEnv 额外透传的环境变量（后端 CLI 认证变量等；在白名单过滤**之后**注入）
 */
data class ProgramSpec(
    val command: List<String>,
    val workingDir: File? = null,
    val extraEnv: Map<String, String> = emptyMap(),
)

/**
 * 启动结果。
 * @param exitCode 子进程退出码；被 kill 或无法取到时为 -1
 * @param timedOut 总超时（taskTimeoutSeconds）触发
 * @param idleTimedOut 空闲看门狗（agentIdleTimeoutSeconds）触发——与总超时**相互独立**、errorCode 可区分（`agent_timeout`，behavior-contract.md §4.13）
 * @param killed 是否被主动终止（总超时或空闲超时）
 */
data class LaunchResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val idleTimedOut: Boolean,
    val killed: Boolean,
)

/**
 * argv 程序调用原语。🔴 **本文件是 :lark-bridge 内 ProcessBuilder 收口封闭清单成员**
 * （LarkSdkIsolationTest E 组守护：lib 侧全部程序调用语义收口本文件）；
 * 子进程一律 [Charsets.UTF_8]（与 agent 结构化输出对齐）。
 *
 * 结构性设计（对照「裸守护线程 + join 超时即泄漏」的朴素写法）：
 * - **管道排干防死锁**：`coroutineScope { launch(IO){stdout 逐行} ; launch(IO){stderr 逐行} }` 双流**并发**排干；
 * - 启动后**立即关 stdin**（防子进程等输入挂死）；
 * - **不用** redirectErrorStream（需区分来源）；
 * - 子进程管道 → 解析用 [Channel]`<AgentLine>(4096, BufferOverflow.SUSPEND)`（🔴 SUSPEND 不是 DROP：丢结构化 JSON 行会破坏逐行解析，宁可让子进程减速），Channel 由调用方创建并传入；
 * - **总超时** + **空闲看门狗** 相互独立；**取消联动** destroyForcibly；
 * - **kill 升级链** SIGTERM(destroy)→3s→SIGKILL(destroyForcibly)→2s→放行 -1，整序列包在 [NonCancellable] 内（外层取消仍须杀完）。
 */
class ProgramLauncher(
    private val policy: WorkspacePolicy,
    private val registry: ProcessRegistry,
    private val userEnvAllowlist: Collection<String> = EnvAllowlist.BASE_ENV_KEYS,
    private val charset: Charset = Charsets.UTF_8,
    private val isWindows: Boolean = EnvAllowlist.isWindows(),
) {

    /**
     * 启动并全程管理一个子进程，逐行输出送入 [lines]。挂起直到子进程结束/被杀/超时。
     * @param onStart 子进程启动后回调（测试捕获 Process 句柄 / T8 观测用；在登记成功后调用）
     * @throws IOException workingDir 越界（调用方映射 WorkspaceDenied）
     * @throws IllegalStateException 登记失败（达容量上限，调用方映射 AgentUnavailable）
     */
    suspend fun launch(
        spec: ProgramSpec,
        lines: Channel<AgentLine>,
        totalTimeoutMillis: Long,
        idleTimeoutMillis: Long,
        onStart: ((Process) -> Unit)? = null,
    ): LaunchResult = coroutineScope {
        // 1. cwd 多根沙箱校验（越界抛 IOException）
        spec.workingDir?.let { requireInsideWorkspace(it) }

        // 2. 启动（ProcessBuilder 唯一出现点）+ 有界登记
        val process = startProcess(spec)
        if (!registry.register(process)) {
            runCatching { process.destroyForcibly() }
            throw IllegalStateException("子进程登记失败：已达容量上限 ${registry.capacity}")
        }
        onStart?.invoke(process)

        val timedOut = AtomicBoolean(false)
        val idleTimedOut = AtomicBoolean(false)
        // 🔴 drain 协程**必须**落在独立 scope，不能是本函数
        //    [coroutineScope] 的子协程：若用 `launch(Dispatchers.IO)`（= coroutineScope 的
        //    子协程）——`withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MILLIS)` 只放弃 join，但
        //    coroutineScope 在块返回前**仍会等全部子协程完成**，而 drain 阻塞在不可取消的
        //    `reader.readLine()` 上 ⇒ 孙进程持有管道写端时 launch 永不返回，超时上限被彻底架空
        //    （实测：设 1500ms 上限，9s 仍未返回；持管道场景 20005ms）。
        //    同型手法见 core/extensions/StreamingShell.kt:90。
        //    作用域在 try 外声明、finally 统一回收：中途抛异常（killChain/exitValue 等）也不漏回收。
        val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // 3. 立即关 stdin（防子进程等输入挂死）
            withContext(Dispatchers.IO) { runCatching { process.outputStream.close() } }

            val idleSignal = Channel<Unit>(Channel.CONFLATED)

            // 4. 双流并发排干（Dispatchers.IO；独立 scope，见上）
            val drainOut = streamScope.launch { drain(process.inputStream, AgentLine.Source.STDOUT, lines, idleSignal) }
            val drainErr = streamScope.launch { drain(process.errorStream, AgentLine.Source.STDERR, lines, idleSignal) }
            // 5. 空闲看门狗（与总超时独立）
            // P0-2：idleTimeoutMillis<=0 = **关闭**看门狗（与 TimeoutPolicy 语义、Admission.idleMillis KDoc「<=0 ⇒ 关闭看门狗」
            //    及 Admission 空闲看门狗的 if(idleMillis>0L) 守护对齐）。否则 withTimeoutOrNull(<=0) 走 fast-path 立即返回 null
            //    ⇒ idleTimedOut=true ⇒ killChain 对刚启动的进程发 SIGTERM（用户依文档把 agentIdleTimeoutSeconds 设 0 想禁用，
            //    反而每次 agent 调用启动后毫秒级被杀）。ConfigValidator/CliSupport 对数值区间不钳制 ⇒ 0/负值可达此处。
            val watchdog = if (idleTimeoutMillis > 0L) launch { idleWatchdog(process, idleSignal, idleTimeoutMillis, idleTimedOut) } else null

            // 6. 等待退出，受总超时约束
            val completed = withTimeoutOrNull(totalTimeoutMillis) {
                while (process.isAlive) delay(POLL_INTERVAL_MILLIS)
                true
            }
            if (completed == null) {
                timedOut.set(true)
                killChain(process)
            }

            // 7. 收尾：等 drain 读到 EOF，取消看门狗（P0-2：watchdog 为 null 表示看门狗已关闭，无需取消）
            // 🔴 join 设上限——killChain 已级联销毁进程树
            //    （孙进程被 destroyTree 收割），但极端场景（句柄泄漏 / 进程僵死管道不 EOF）下
            //    drain 协程阻塞在不可取消的 reader.readLine() 上，裸 join 会把整个 launch 挂死。
            //    超时放弃等待即可：进程已死、exitCode 已准确，残行随管道关闭自然终止。
            //    （对齐 core/StreamingShell 的 STREAM_DRAIN_TIMEOUT_MS 同型修复。）
            //    🔴 二次修复：此上限**只有在 drain 非 coroutineScope 子协程时才真正生效**（见第 4 步注释）。
            withTimeoutOrNull(STREAM_DRAIN_TIMEOUT_MILLIS) {
                drainOut.join()
                drainErr.join()
            }
            streamScope.cancel() // 回收读协程（阻塞在 readLine 的线程随管道关闭自然退出）
            watchdog?.cancel()

            val exit = runCatching { process.exitValue() }.getOrDefault(-1)
            LaunchResult(
                exitCode = exit,
                timedOut = timedOut.get(),
                idleTimedOut = idleTimedOut.get(),
                killed = timedOut.get() || idleTimedOut.get(),
            )
        } finally {
            streamScope.cancel() // 幂等兜底：异常路径（killChain/exitValue 抛）也不漏回收
            registry.unregister(process)
            // 取消联动：外层取消时本 finally 仍在 NonCancellable 内杀干净，防孤儿子进程
            withContext(NonCancellable) {
                if (process.isAlive) killChain(process)
            }
        }
    }

    /**
     * 在 PATH 上探测可执行文件（`which` 语义）：Windows 追加 PATHEXT 变体。
     * @return 首个存在且可执行的文件；未找到返回 null（调用方据此报 AgentUnavailable）。
     */
    fun resolveOnPath(executable: String): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        val extensions = if (isWindows) {
            System.getenv("PATHEXT")?.split(";")?.filter { it.isNotBlank() } ?: listOf(".exe", ".cmd", ".bat", ".com")
        } else {
            listOf("")
        }
        return pathEnv.split(File.pathSeparator).asSequence()
            .filter { it.isNotBlank() }
            .flatMap { dir -> extensions.asSequence().map { ext -> File(dir, executable + ext) } }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    // —— 内部实现 ——

    /**
     * ProcessBuilder 启动：cwd + env 白名单过滤 + extraEnv 注入 + 不合并 stderr。
     */
    internal fun startProcess(spec: ProgramSpec): Process {
        val builder = ProcessBuilder(spec.command)
        spec.workingDir?.let { builder.directory(it) }
        val allowed = EnvAllowlist.resolveAllowed(userEnvAllowlist, isWindows)
        EnvAllowlist.retainAllowed(builder.environment(), allowed, isWindows)
        spec.extraEnv.forEach { (k, v) -> builder.environment()[k] = v }
        builder.redirectErrorStream(false) // 需区分 stdout/stderr 来源，不合并
        return builder.start()
    }

    /** cwd 越界校验（分隔符对齐防前缀误放行）。 */
    private fun requireInsideWorkspace(dir: File) {
        if (policy.isEmpty) throw IOException("工作区域未配置：白名单为空，拒绝所有路径")
        if (!policy.requireInsideWorkspace(dir)) {
            throw IOException(
                "工作目录越界：${dir.canonicalPath} 不在允许区域内（${policy.canonicalRoots.joinToString(", ")}）",
            )
        }
    }

    /** 逐行排干一路输出：读一行即记录活动（idleSignal.trySend 在 lines.send 之前，consumer 停滞也不误判空闲），再背压送入 lines。 */
    private suspend fun drain(
        stream: InputStream,
        source: AgentLine.Source,
        lines: Channel<AgentLine>,
        idleSignal: Channel<Unit>,
    ) {
        BufferedReader(InputStreamReader(stream, charset)).use { reader ->
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: IOException) {
                    null // 流被 kill 关闭 → 视作 EOF
                }
                if (line == null) break
                idleSignal.trySend(Unit)
                lines.send(AgentLine(source, line))
            }
        }
    }

    /** 空闲看门狗：连续 [idleTimeoutMillis] 无任何输出即杀进程并置位标记（与总超时独立）。 */
    private suspend fun idleWatchdog(
        process: Process,
        idleSignal: Channel<Unit>,
        idleTimeoutMillis: Long,
        idleTimedOut: AtomicBoolean,
    ) {
        try {
            while (process.isAlive) {
                val activity = withTimeoutOrNull(idleTimeoutMillis) { idleSignal.receive() }
                if (activity == null) {
                    idleTimedOut.set(true)
                    killChain(process)
                    break
                }
            }
        } finally {
            idleSignal.close()
        }
    }

    /**
     * kill 升级链（behavior-contract.md §4.8）：SIGTERM→3s→SIGKILL→2s→放行 -1，整序列在 [NonCancellable] 内。
     *
     * 🔴 **进程树级联终止**：只杀直接子进程不够——直接子进程几乎从不是真正的执行体（Windows 上是 cmd.exe，
     *    Unix 上是 zsh -l -c / node 包装层），其派生的孙进程（adb logcat、编译器守护进程等）
     *    会残留为孤儿。尤其 Windows 的 TerminateProcess 不级联，「停止」后孤儿进程仍活着。
     *    每一级（SIGTERM / SIGKILL）都对**整棵进程树**发信号：先记快照再逐个终止
     *    （process.descendants() 是惰性流，边杀边遍历可能漏掉已退出进程的子孙）。
     */
    internal suspend fun killChain(process: Process): Int = withContext(NonCancellable) {
        if (!process.isAlive) return@withContext exitOr(process, -1)
        destroyTree(process, force = false) // SIGTERM 级联（含全部子孙）
        if (awaitExit(process, TERM_GRACE_MILLIS)) return@withContext exitOr(process, -1)
        destroyTree(process, force = true) // SIGKILL 级联
        if (awaitExit(process, KILL_GRACE_MILLIS)) return@withContext exitOr(process, -1)
        -1 // 仍存活：放行并记 -1
    }

    /**
     * 进程树终止：对 [process] 与其全部子孙各发 destroy/destroyForcibly。
     * 先把 descendants 快照成 List（ProcessHandle 流是惰性的，进程退出后子孙句柄可能失效）；
     * 每个句柄失败静默（runCatching）——尽力而为，主进程的 awaitExit 才是硬判定。
     */
    internal fun destroyTree(process: Process, force: Boolean) {
        val tree = runCatching {
            listOf(process.toHandle()) + process.descendants().toList()
        }.getOrDefault(listOf(process.toHandle()))
        for (handle in tree) {
            runCatching { if (force) handle.destroyForcibly() else handle.destroy() }
        }
    }

    /** 轮询等待退出（协程时钟友好：虚拟时间可测逻辑，真实进程可测 kill 语义）。 */
    private suspend fun awaitExit(process: Process, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            while (process.isAlive) delay(POLL_INTERVAL_MILLIS)
            true
        } ?: !process.isAlive

    private fun exitOr(process: Process, fallback: Int): Int =
        runCatching { process.exitValue() }.getOrDefault(fallback)

    companion object {
        /** SIGTERM 宽限期（behavior-contract.md §4.8：destroy() 后等 3s）。 */
        const val TERM_GRACE_MILLIS = 3_000L

        /** SIGKILL 宽限期（behavior-contract.md §4.8：destroyForcibly() 后等 2s）。 */
        const val KILL_GRACE_MILLIS = 2_000L

        /** 退出轮询间隔。 */
        const val POLL_INTERVAL_MILLIS = 50L

        /**
         * 🔴 排干协程 join 的上限——对齐 :core StreamingShell
         * 的 `STREAM_DRAIN_TIMEOUT_MS`（1500ms）：进程已死即放弃等待，防句柄泄漏场景挂死。
         */
        const val STREAM_DRAIN_TIMEOUT_MILLIS = 1_500L

        /**
         * 子进程管道 → 解析器的标准背压容量。
         * 🔴 [kotlinx.coroutines.channels.BufferOverflow.SUSPEND]（不是 DROP_OLDEST/DROP_LATEST）：
         * 丢结构化 JSON 行会破坏逐行解析，宁可让子进程减速（管道填满 → 子进程 write 阻塞）。
         */
        const val LINE_CHANNEL_CAPACITY = 4096

        /** 工厂：按标准背压容量创建 AgentLine 管道（显式 SUSPEND）。 */
        fun lineChannel(): Channel<AgentLine> = Channel(LINE_CHANNEL_CAPACITY, BufferOverflow.SUSPEND)
    }
}
