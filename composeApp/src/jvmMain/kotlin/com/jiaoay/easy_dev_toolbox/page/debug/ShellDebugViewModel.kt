package com.jiaoay.easy_dev_toolbox.page.debug

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jiaoay.easy_dev_toolbox.core.extensions.InteractiveShell
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellCommandBuilder
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellOutputSource
import com.jiaoay.easy_dev_toolbox.core.extensions.ShellResult
import com.jiaoay.easy_dev_toolbox.core.extensions.SimpleShell
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.jiaoay.easy_dev_toolbox.core.device.DeviceHub
import com.jiaoay.easy_dev_toolbox.core.shelltools.AdbCommandRewriter

class ShellDebugViewModel(
    /**
     * Simple 执行接缝（测试基建）：生产默认走 [SimpleShell]；测试注入 fake
     * （悬挂不返回 / 立即返回）驱动中止与超时用例。suspend 形态对齐 SimpleShell.execute。
     */
    private val simpleExec: suspend (Array<String>) -> Result<ShellResult> = { SimpleShell.execute(*it) },
    /**
     * IO 线程域（VM 内全部阻塞执行体的承载调度器：Simple 孤儿 launcher、
     * InteractiveShell 会话 scope、发送写入）。契约：默认 [Dispatchers.IO]，
     * 宿主装配点零改动；测试注入受控调度器（与 Main 共享 TestScheduler 的
     * StandardTestDispatcher）后，VM 级 IO 续体不飞真实 IO 线程——用例收尾
     * advanceUntilIdle 即确定性排空（teardown 无需竞态宽限 sleep）。
     * 真实进程链用例（InteractiveShell 不可注入 fake）不注入，保留真实异步语义。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    companion object {
        /** marker 检测超时兜底：超时后恢复输入可用（与引擎链式单步超时语义一致） */
        private const val MARKER_DETECT_TIMEOUT_MS = 30_000L

        private const val MARKER = "__DONE__"

        private const val SIMPLE_RUN_TIMEOUT_MS = MARKER_DETECT_TIMEOUT_MS

        /** 🔴 调试页消息上限（对齐 ShellExecutionEngine.MAX_OUTPUT_ITEMS；防无界增长 + O(n²) 拷贝）。 */
        private const val MAX_DEBUG_MESSAGES = 5_000

        @Composable
        fun getViewModel(): ShellDebugViewModel = viewModel { ShellDebugViewModel() }
    }

    private val _currentMode = MutableStateFlow(DebugShellMode.Simple)
    val currentMode: StateFlow<DebugShellMode> = _currentMode.asStateFlow()

    private val _simpleMessages = MutableStateFlow<List<DebugChatMessage>>(emptyList())
    val simpleMessages: StateFlow<List<DebugChatMessage>> = _simpleMessages.asStateFlow()

    private val _interactiveMessages = MutableStateFlow<List<DebugChatMessage>>(emptyList())
    val interactiveMessages: StateFlow<List<DebugChatMessage>> = _interactiveMessages.asStateFlow()

    // 🔴 性能热路径·贴底跟随：追加序号——列表封顶后 size 恒为上限，
    //    页面 `LaunchedEffect(messages.size)` 永不重启 ⇒ 新行不再自动贴底（功能坏了，非掉帧）。
    //    每次追加自增，页面用它做 effect key（对照 ui/widget/BottomFollowScroll 的同型缺陷）。
    private val _messagesVersion = MutableStateFlow(0L)
    val messagesVersion: StateFlow<Long> = _messagesVersion.asStateFlow()

    // 🔴 消息列表封顶（对齐 ShellExecutionEngine 的 MAX_OUTPUT_ITEMS=5000）：无界增长 + 每条整表拷贝
    //    会让长会话（10 分钟 logcat 级）内存与 CPU 双失守；溢出丢头部（滚动窗口语义，
    //    旧消息在 LazyColumn 外不可索引）。
    private fun appendBounded(flow: MutableStateFlow<List<DebugChatMessage>>, msg: DebugChatMessage) {
        flow.update { list ->
            val next = if (list.size >= MAX_DEBUG_MESSAGES) list.drop(1) else list
            next + msg
        }
        _messagesVersion.update { it + 1 }
    }

    private val _isSendEnabled = MutableStateFlow(true)
    val isSendEnabled: StateFlow<Boolean> = _isSendEnabled.asStateFlow()

    private val _isSessionActive = MutableStateFlow(false)
    val isSessionActive: StateFlow<Boolean> = _isSessionActive.asStateFlow()

    private val _switchBlockedMessage = MutableStateFlow<String?>(null)
    val switchBlockedMessage: StateFlow<String?> = _switchBlockedMessage.asStateFlow()

    private var interactiveShell: InteractiveShell? = null
    private var shellScope: CoroutineScope? = null
    private var outputCollectJob: Job? = null

    /** marker 检测协程（成员化使 closeSession/onCleared 能取消悬挂中的检测） */
    private var markerDetectJob: Job? = null

    private var simpleRunJob: Job? = null

    private var watchJob: Job? = null

    // SimpleShell

    fun executeSimpleCommand(input: String) {
        if (input.isBlank()) return
        // 执行中重复发送防御（与渲染层 canSend 互为冗余防线——执行中停止钮替代发送钮）
        if (!_isSendEnabled.value) return
        _isSendEnabled.value = false
        appendSimpleMessage(DebugChatMessage(type = DebugMessageType.INPUT, content = input))

        val parts = input.trim().split("\\s+".toRegex()).toTypedArray()
        // 可中止 + 30s 超时兜底：
        //  - 执行体挂孤儿 launcher：SimpleShell.waitFor 阻塞不响应协程取消，且无进程句柄
        //    暴露面——直接把执行 await 进本 Job 会让 cancel/超时被迫等 OS 进程退出（挂死命令
        //    永不送达，停止钮形同虚设）；SupervisorJob 切断父子取消链，等待 Job 只 await 信号，
        //    cancel（停止钮）/超时都即时生效，孤儿进程自然退出、输出随之丢弃（诚实边界）。
        //  - 超时对齐 Interactive 模式 MARKER_DETECT_TIMEOUT_MS 的 30s 兜底语义。
        //  - 等待 Job 不指定 dispatcher（挂 viewModelScope 主语境）：它只 await + 追加消息，
        //    无阻塞工作；显式挂 Dispatchers.IO 反而让超时走真实线程池时钟（测试虚拟时钟失效）、
        //    且给 EDT 平添一个无谓的 IO 线程占用。与 markerDetectJob 同款 launch 形态。
        simpleRunJob = viewModelScope.launch {
            val resultSignal = CompletableDeferred<Result<ShellResult>>()
            CoroutineScope(SupervisorJob() + ioDispatcher).launch {
                // 兜底：执行体异常也必须补发信号（否则等待侧悬挂到超时才兜——诚实报错优先）
                val outcome = try {
                    simpleExec(parts)
                } catch (e: Throwable) {
                    Result.failure<ShellResult>(e)
                }
                resultSignal.complete(outcome)
            }
            try {
                val result = withTimeoutOrNull(SIMPLE_RUN_TIMEOUT_MS) { resultSignal.await() }
                if (result == null) {
                    appendSimpleMessage(
                        DebugChatMessage(
                            type = DebugMessageType.OUTPUT,
                            content = "命令超时 ${SIMPLE_RUN_TIMEOUT_MS / 1000}s 未退出，已恢复输入（可切「保持会话」跑长命令）",
                            source = ShellOutputSource.STDERR,
                        )
                    )
                } else {
                    result.onSuccess { shellResult ->
                        shellResult.stdout.lines().filter { it.isNotEmpty() }.forEach { line ->
                            appendSimpleMessage(
                                DebugChatMessage(
                                    type = DebugMessageType.OUTPUT,
                                    content = line,
                                    source = ShellOutputSource.STDOUT,
                                )
                            )
                        }
                        shellResult.stderr.lines().filter { it.isNotEmpty() }.forEach { line ->
                            appendSimpleMessage(
                                DebugChatMessage(
                                    type = DebugMessageType.OUTPUT,
                                    content = line,
                                    source = ShellOutputSource.STDERR,
                                )
                            )
                        }
                        appendSimpleMessage(
                            DebugChatMessage(
                                type = DebugMessageType.OUTPUT,
                                content = "exitCode: ${shellResult.exitCode}",
                            )
                        )
                    }
                    result.onFailure { e ->
                        appendSimpleMessage(
                            DebugChatMessage(
                                type = DebugMessageType.OUTPUT,
                                content = "Error: ${e.message}",
                                source = ShellOutputSource.STDERR,
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                appendSimpleMessage(
                    DebugChatMessage(
                        type = DebugMessageType.OUTPUT,
                        content = "已中止",
                        source = ShellOutputSource.STDERR,
                    )
                )
                throw e
            } finally {
                _isSendEnabled.value = true
            }
        }
    }

    /**
     * 中止当前 Simple 执行：即时解除等待与 UI 锁——**不杀 OS 进程**。
     * SimpleShell 无进程句柄暴露（waitFor 阻塞至自然退出），被中止的 OS 进程成为孤儿
     * 继续跑完自行退出，其输出不再回收；长命令请切「保持会话」模式跑。
     */
    fun stopSimple() {
        simpleRunJob?.cancel(CancellationException("stopped by user"))
        simpleRunJob = null
    }

    private fun appendSimpleMessage(msg: DebugChatMessage) {
        appendBounded(_simpleMessages, msg)   // 加上限（见 appendBounded 注释）
    }

    // InteractiveShell session management

    fun startSession(command: String) {
        if (command.isBlank()) return
        val scope = CoroutineScope(ioDispatcher + SupervisorJob())
        shellScope = scope
        val shell = InteractiveShell(scope = scope)
        interactiveShell = shell
        // 与 Shell 工具页同源：会话起点命令也过可执行路径重写，否则交互式 adb shell
        // 会去搜系统 PATH——内置 adb 是唯一来源，这里不能漏
        val endpoint = DeviceHub.currentEndpoint()
        ShellCommandBuilder.adbServerPort = endpoint.serverPort
        shell.start(AdbCommandRewriter.rewrite(command, endpoint.executablePath, serial = null))
        _isSessionActive.value = true
        _isSendEnabled.value = true

        appendInteractiveMessage(
            DebugChatMessage(
                type = DebugMessageType.OUTPUT,
                content = "Session started: $command",
            )
        )

        outputCollectJob = viewModelScope.launch {
            shell.outputFlow.collect { output ->
                // 完成 marker 是协议噪音，不进 UI 输出流——前置过滤。
                // marker 检测（markerDetectJob）不动：同一流两 collector，检测照常命中
                // （marker 仍驱动完成检测），仅显示侧滤除。
                if (output.source == ShellOutputSource.STDOUT && output.line.trim() == MARKER) {
                    return@collect
                }
                appendInteractiveMessage(
                    DebugChatMessage(
                        type = DebugMessageType.OUTPUT,
                        content = output.line,
                        source = output.source,
                    )
                )
            }
        }

        // 会话活性监测：进程死亡（exit/崩溃）自动收口——isSessionActive 回 false
        //（模式切换不再被拒、发送钮随 canSend 置灰）+ 追加提示行。closeSession 主动关闭
        // 时 watchJob 已先 cancel，不会走到这里（「Session closed」行独占主动关闭语义）。
        watchJob = viewModelScope.launch {
            shell.exited.collect {
                if (_isSessionActive.value) {
                    _isSessionActive.value = false
                    _isSendEnabled.value = false
                    appendInteractiveMessage(
                        DebugChatMessage(
                            type = DebugMessageType.OUTPUT,
                            content = "会话已退出（进程结束）",
                            source = ShellOutputSource.STDERR,
                        )
                    )
                }
            }
        }
    }

    fun closeSession() {
        // 监测先 cancel（必须在 shell.close 之前）——close 会 destroy 进程令 waitFor
        // 返回、exited 发射，若监测仍活着「会话已退出」行会跟在「Session closed」后（竞态行）
        watchJob?.cancel()
        watchJob = null
        markerDetectJob?.cancel()
        markerDetectJob = null
        outputCollectJob?.cancel()
        outputCollectJob = null
        interactiveShell?.close()
        interactiveShell = null
        // SupervisorJob 显式收尾（InteractiveShell.close 已取消读流协程，此处补齐 scope 本身）
        shellScope?.cancel()
        shellScope = null
        _isSessionActive.value = false
        _isSendEnabled.value = false

        appendInteractiveMessage(
            DebugChatMessage(
                type = DebugMessageType.OUTPUT,
                content = "Session closed",
            )
        )
    }

    // InteractiveShell command sending with Marker

    fun sendInteractiveCommand(input: String) {
        if (input.isBlank()) return
        val shell = interactiveShell ?: return
        _isSendEnabled.value = false
        appendInteractiveMessage(DebugChatMessage(type = DebugMessageType.INPUT, content = input))

        val marker = MARKER   // 字面值收敛单常量（与 InteractiveShell.MARKER 同值对齐）
        val markerCommand = "$input && echo $marker"

        // marker 检测：正常出现恢复输入；30s 超时兜底恢复（marker 缺失不再永久锁死输入）
        markerDetectJob?.cancel()
        markerDetectJob = viewModelScope.launch {
            val detected = withTimeoutOrNull(MARKER_DETECT_TIMEOUT_MS) {
                shell.outputFlow.first { output ->
                    output.source == ShellOutputSource.STDOUT && output.line.trim() == marker
                }
            }
            _isSendEnabled.value = true
            if (detected == null) {
                // 超时：输出仍由 outputCollectJob 流式展示，仅提示命令可能未完成
                appendInteractiveMessage(
                    DebugChatMessage(
                        type = DebugMessageType.OUTPUT,
                        content = "命令超时（${MARKER_DETECT_TIMEOUT_MS / 1000}s 未收到完成标记），输出可能未完成",
                        source = ShellOutputSource.STDERR,
                    )
                )
            }
        }

        viewModelScope.launch(ioDispatcher) {
            shell.sendCommand(markerCommand)
        }
    }

    private fun appendInteractiveMessage(msg: DebugChatMessage) {
        appendBounded(_interactiveMessages, msg)   // 加上限（见 appendBounded 注释）
    }

    // Mode switching

    fun switchMode(targetMode: DebugShellMode) {
        if (targetMode == _currentMode.value) return
        if (_isSessionActive.value) {
            _switchBlockedMessage.value = "请先关闭当前 InteractiveShell 会话"
            return
        }
        _currentMode.value = targetMode
        _isSendEnabled.value = when (targetMode) {
            DebugShellMode.Simple -> true
            DebugShellMode.Interactive -> false
        }
    }

    /**
     * 清空当前模式的消息列表（输出台工具行）：只清 [DebugShellMode] 对应一侧，
     * 另一模式列表不动（5000 行上限的旧输出无整页离开也可一键清理）。
     * messagesVersion 同步 +1——贴底跟随 effect 的 key 兼容（清空后列表空、effect 内
     * lastIndex=-1 天然 no-op，bump 是 effect key 语义的守恒，非必需但照卡面口径）。
     */
    fun clearMessages() {
        when (_currentMode.value) {
            DebugShellMode.Simple -> _simpleMessages.value = emptyList()
            DebugShellMode.Interactive -> _interactiveMessages.value = emptyList()
        }
        _messagesVersion.update { it + 1 }
    }

    fun clearSwitchBlockedMessage() {
        _switchBlockedMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        simpleRunJob?.cancel()   // 悬挂中的 Simple 等待随 VM 清理收尾（孤儿进程边界同 stopSimple）
        watchJob?.cancel()       // 会话活性监测随 VM 清理收尾
        markerDetectJob?.cancel()
        outputCollectJob?.cancel()
        interactiveShell?.close()
        shellScope?.cancel()
    }
}
