package com.jiaoay.easy_dev_toolbox.core.extensions

import com.jiaoay.easy_dev_toolbox.core.log.JLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedWriter

class InteractiveShell(
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "InteractiveShell"
        private const val MARKER = "__DONE__"
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null

    /** 进程退出监测协程：close 时随 reader 协程一并取消（主动关闭不发射退出信号） */
    private var exitWatchJob: Job? = null

    private val _outputFlow = MutableSharedFlow<ShellOutput>(extraBufferCapacity = 64)
    val outputFlow: SharedFlow<ShellOutput> = _outputFlow.asSharedFlow()

    // replay=1：生产侧 watchJob 经 Main.immediate 在 startSession 内同步订阅（无竞态），
    // 但订阅前进程秒退的理论窗口存在——replay 缓存末次信号，迟到订阅者也能收到死亡事件
    private val _exited = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    /**
     * 进程退出单信号（会话活性监测）：start 起后台 [Process.waitFor] 监测，
     * 进程终态时发射一次。replay=1：订阅前进程秒退的理论窗口下，迟到订阅者也能收到
     * 死亡事件（start 内清 replay 缓存防跨进程残留）。
     *
     * 配方说明（偏离「两 reader 尾 trySend」直觉）：stdout/stderr 两 reader
     * 先后 EOF 会双触发，故走 waitFor 单信号路线——进程终态唯一、不依赖消费侧幂等兜底。
     * close() 先取消监测协程：**主动关闭不发射**（消费方以此区分「进程自己死了」与「用户关了会话」）。
     */
    val exited: SharedFlow<Unit> = _exited.asSharedFlow()

    fun start(command: String) {
        if (process != null) {
            JLog.d(TAG) { "start: process already running, closing first" }
            close()
        }
        // 复用实例重启：清 replay 缓存，新进程不继承上一进程的死亡信号
        _exited.resetReplayCache()

        JLog.d(TAG) { "start: $command" }
        val wrapped = ShellCommandBuilder.wrap(command)
        val pb = ProcessBuilder(*wrapped)
        ShellCommandBuilder.applyShellEnv(pb)   // 🔴 注入探测到的登录 shell PATH（非交互 shell 缺用户 PATH 段会丢命令）
        val proc = pb.start()
        process = proc
        // 读写同一编码（写侧命令与读侧输出必须一致，否则中文命令/输出必乱码）：
        // adb 会话恒 UTF-8，其余走控制台代码页（见 [ShellCommandBuilder.charsetFor]）
        val charset = ShellCommandBuilder.charsetFor(command)
        writer = proc.outputStream.bufferedWriter(charset)

        stdoutJob = scope.launch(Dispatchers.IO) {
            proc.inputStream.bufferedReader(charset).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    _outputFlow.emit(ShellOutput(line = line, source = ShellOutputSource.STDOUT))
                }
            }
            JLog.d(TAG) { "stdout reader ended" }
        }

        stderrJob = scope.launch(Dispatchers.IO) {
            proc.errorStream.bufferedReader(charset).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    _outputFlow.emit(ShellOutput(line = line, source = ShellOutputSource.STDERR))
                }
            }
            JLog.d(TAG) { "stderr reader ended" }
        }

        // waitFor 阻塞读，必须挂 IO 线程（不占调用方 dispatcher）；
        // close() 先 cancel 本协程再 destroy——waitFor 不响应协程取消，靠 destroy 解除阻塞后
        // ensureActive 兜住「主动关闭不发射」语义（缺它 trySend 照跑、语义承诺落空）。
        exitWatchJob = scope.launch(Dispatchers.IO) {
            proc.waitFor()
            ensureActive()
            JLog.d(TAG) { "process exited (watch)" }
            _exited.tryEmit(Unit)
        }
    }

    suspend fun sendCommand(command: String) = withContext(Dispatchers.IO) {
        val w = writer
        val proc = process
        if (w == null || proc == null || !proc.isAlive) {
            JLog.d(TAG) { "sendCommand: process not running, ignoring: $command" }
            return@withContext
        }
        JLog.d(TAG) { "sendCommand: $command" }
        w.write(command)
        w.newLine()
        w.flush()
    }

    suspend fun executeQueue(commands: List<String>) {
        for (command in commands) {
            if (command.isBlank()) continue

            val proc = process
            if (proc == null || !proc.isAlive) {
                JLog.d(TAG) { "executeQueue: process not running, aborting queue" }
                break
            }

            val markerReceived = CompletableDeferred<Unit>()
            val collectJob = scope.launch {
                outputFlow.collect { output ->
                    if (output.source == ShellOutputSource.STDOUT && output.line.trim() == MARKER) {
                        markerReceived.complete(Unit)
                    }
                }
            }

            sendCommand("$command && echo $MARKER")
            markerReceived.await()
            collectJob.cancel()
        }
        JLog.d(TAG) { "executeQueue: all commands completed, process still alive" }
    }

    fun close() {
        JLog.d(TAG) { "close" }
        // 退出监测先取消——close 触发的 destroy() 会让 waitFor 返回、exited 发射，
        // 但这是主动关闭非进程死亡，信号不该发（消费方以 [exited] 区分死亡/关闭语义）。
        exitWatchJob?.cancel()
        exitWatchJob = null
        stdoutJob?.cancel()
        stderrJob?.cancel()
        stdoutJob = null
        stderrJob = null

        runCatching { writer?.close() }
        writer = null

        // 树杀：交互 shell 里跑的子命令同样会因解释器先死而变孤儿
        runCatching { process?.let { ProcessTreeKiller.destroyTree(it, force = false) } }
        process = null
    }
}
