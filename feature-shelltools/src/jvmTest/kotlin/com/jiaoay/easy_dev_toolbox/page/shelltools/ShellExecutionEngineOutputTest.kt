package com.jiaoay.easy_dev_toolbox.page.shelltools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 输出性能基建回归（design D1/D2/D3）：
 * 滚动窗口截断与丢弃计数、clearOutput 归零与无残留复活、
 * 100ms 聚合窗口批量更新、任务结束立即冲刷、seq 稳定 key 单调唯一。
 *
 * 全部走真实子进程（seq/echo），黑盒观察 [ShellExecutionEngine.output]。
 */
class ShellExecutionEngineOutputTest {

    private lateinit var scope: CoroutineScope
    private lateinit var engine: ShellExecutionEngine

    @AfterTest
    fun tearDown() {
        if (::scope.isInitialized) {
            engine.stop()
            scope.cancel()
        }
    }

    private fun newEngine(): ShellExecutionEngine {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = ShellExecutionEngine(scope)
        return engine
    }

    private suspend fun awaitStatus(target: ExecStatus, timeoutMs: Long = 30_000) {
        withTimeout(timeoutMs) {
            engine.status.first { it == target }
        }
    }

    // ── 1.2 滚动窗口：超上限截断最旧 + droppedCount 累计 ──

    @Test
    fun `rolling window truncates oldest items and accumulates droppedCount`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(label = "burst", commands = listOf("seq 1 6000"), timeoutMs = 30_000)
        )
        awaitStatus(ExecStatus.Completed)

        val state = engine.output.value
        // 总行数 = Command(1) + Stdout(6000) + exitCode(1) + 完成(1) = 6003，截断至 5000
        assertEquals(ShellExecutionEngine.MAX_OUTPUT_ITEMS, state.items.size)
        assertEquals(6003 - ShellExecutionEngine.MAX_OUTPUT_ITEMS, state.droppedCount)
        // 丢弃的是最旧的：Command 行 + seq 1..1002 已被移出，窗口首行应为 seq 输出的 1003
        // 差行诊断判据（高压全量下再现时按首行内容定位缺失行）：
        // 差尾行（exitCode/完成）= 聚合器 flush 契约面（ack 恒等，见 ShellOutputAggregator.flushPending）；
        // 差中段行 = shell 层 1.5s drain 兜底取消的已知取舍（StreamingShell KDoc 读流协程节）
        assertEquals("1003", state.items.first().content)
        // 最新行为"完成"状态行
        assertEquals("完成", state.items.last().content)
        // 行序与实际输出一致（seq 连续递增，无乱序）
        val seqNumbers = state.items
            .filter { it.type == ExecOutputType.Stdout }
            .mapNotNull { it.content.toIntOrNull() }
        assertEquals(seqNumbers, seqNumbers.sorted())
        Unit
    }

    // ── 1.2 clearOutput：缓冲与计数归零 ──

    @Test
    fun `clearOutput resets buffer and droppedCount`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(label = "burst", commands = listOf("seq 1 5500"), timeoutMs = 30_000)
        )
        awaitStatus(ExecStatus.Completed)
        assertTrue(engine.output.value.droppedCount > 0, "超上限后应产生丢弃计数")

        engine.clearOutput()
        assertEquals(OutputState(), engine.output.value)

        // 等待超过一个聚合窗口，确认无旧窗口行复活
        delay(300)
        assertEquals(OutputState(), engine.output.value)
        Unit
    }

    // ── 1.3 聚合：窗口内多行合并为少量 state 更新 ──

    @Test
    fun `high frequency output aggregates into few state updates`() = runBlocking {
        val engine = newEngine()
        val updates = mutableListOf<OutputState>()
        val collectJob = scope.launch {
            engine.output.collect { updates.add(it) }
        }

        engine.submit(
            QueuedCommand(label = "agg", commands = listOf("seq 1 2000"), timeoutMs = 30_000)
        )
        awaitStatus(ExecStatus.Completed)
        delay(200) // 收尾冲刷的更新落地
        collectJob.cancel()

        val stateChanges = updates.size - 1 // StateFlow 首个值为订阅时快照
        val totalLines = engine.output.value.items.size + engine.output.value.droppedCount
        assertTrue(totalLines >= 2003, "全部行应到达（实际 $totalLines）")
        // 2000+ 行仅产生远小于行数的 state 更新（无聚合时每行一次 update）
        assertTrue(
            stateChanges < totalLines / 10,
            "state 更新次数($stateChanges) 应远小于行数($totalLines)",
        )
        Unit
    }

    // ── 1.3 任务结束立即冲刷（status 置位时行已完整可见） ──

    @Test
    fun `task completion flushes pending lines immediately`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(label = "hello", commands = listOf("echo hello"), timeoutMs = 10_000)
        )
        awaitStatus(ExecStatus.Completed)

        // 不做任何等待：Completed 置位前 runTask 已 flushPending，全部行立即可见
        val items = engine.output.value.items
        assertTrue(items.any { it.content == "hello" }, "应包含命令输出行: $items")
        assertTrue(items.any { it.content == "完成" }, "应包含完成状态行: $items")
        Unit
    }

    // ── 1.1/1.3 seq 稳定 key：会话内单调递增且唯一 ──

    @Test
    fun `seq keys are strictly increasing and unique`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(label = "first", commands = listOf("echo a"), timeoutMs = 10_000)
        )
        awaitStatus(ExecStatus.Completed)
        engine.submit(
            QueuedCommand(label = "second", commands = listOf("echo b", "echo c"), timeoutMs = 10_000)
        )
        awaitStatus(ExecStatus.Completed)

        val seqs = engine.output.value.items.map { it.seq }
        assertTrue(seqs.all { it > 0 }, "seq 应由引擎赋正值")
        assertEquals(seqs.size, seqs.distinct().size, "seq 应唯一")
        assertEquals(seqs.sorted(), seqs, "seq 应单调递增（跨任务连续）")
        Unit
    }

    // ── 1.3 clearOutput 后旧窗口行不得复活（世代号） ──

    @Test
    fun `clearOutput during aggregation prevents stale lines from reviving`() = runBlocking {
        val engine = newEngine()
        // 执行中清空：命令运行期间行持续进入聚合管道
        engine.submit(
            QueuedCommand(label = "slow", commands = listOf("seq 1 20 | while read n; do echo \"\$n\"; sleep 0.01; done"), timeoutMs = 30_000)
        )
        // 等命令开始产出（窗口内出现至少一行）
        withTimeout(10_000) {
            engine.output.first { it.items.isNotEmpty() }
        }
        // 命令仍在运行时清空输出
        engine.clearOutput()
        // 等待超过一个聚合窗口 + 命令结束：清空时刻之前进入管道的行不得复活
        awaitStatus(ExecStatus.Completed, timeoutMs = 30_000)
        delay(300)
        // 清空后新到达的行（清空之后产生的）允许存在，但清空前的旧行（seq 之前已出现的）不得出现
        val state = engine.output.value
        assertTrue(
            state.items.none { it.type == ExecOutputType.Command },
            "清空时刻之前发射的 Command 行不得复活: ${state.items.map { it.content }}",
        )
        Unit
    }

    // ── 排队深度：submit +1 / 开跑 -1 / stop 清零并上屏丢弃数 ──

    @Test
    fun `queuedCount tracks queue depth and stop reports dropped count`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(label = "long", commands = listOf("sleep 300"), timeoutMs = 60_000)
        )
        withTimeout(10_000) {
            engine.status.first { it is ExecStatus.Running }
        }
        // 第一条已开跑，第二条仍在排队
        engine.submit(
            QueuedCommand(label = "queued", commands = listOf("echo q"), timeoutMs = 10_000)
        )
        // CI runner（2 核慢机）的调度延迟远高于本地——固定 delay 会假红，改轮询等值落地
        withTimeout(5_000) {
            engine.queuedCount.first { it == 1 }
        }

        engine.stop()
        assertEquals(0, engine.queuedCount.value, "stop 后排队计数应清零")
        // stop 需先终止前台进程（sleep 300）再上屏回执——CI 慢机进程终结超 2s，预算放宽
        withTimeout(10_000) {
            engine.output.first { state ->
                state.items.any { it.content == "已手动停止（丢弃排队 1 条）" }
            }
        }
        Unit
    }

    // ── 行净化：\r 覆盖折叠 / ANSI 剥离 / CRLF 正常换行 ──

    @Test
    fun `carriage-return progress output collapses to final state`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(label = "progress", commands = listOf("printf 'a\\rbb\\rccc\\n'"), timeoutMs = 10_000)
        )
        awaitStatus(ExecStatus.Completed)

        val stdout = engine.output.value.items.filter { it.type == ExecOutputType.Stdout }
        assertTrue(
            stdout.any { it.content == "ccc" },
            "进度行应折叠为最终态 ccc: ${stdout.map { it.content }}",
        )
        assertTrue(stdout.none { it.content == "a" }, "被覆盖的进度片段不得独立上屏")
        assertEquals(1, stdout.size, "整段进度应只产生一行: ${stdout.map { it.content }}")
        Unit
    }

    @Test
    fun `ansi escape sequences are stripped from output`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(
                label = "ansi",
                commands = listOf("printf '\\033[31mred\\033[0m plain\\n'"),
                timeoutMs = 10_000,
            )
        )
        awaitStatus(ExecStatus.Completed)

        val stdout = engine.output.value.items.filter { it.type == ExecOutputType.Stdout }
        assertTrue(stdout.any { it.content == "red plain" }, "ANSI 序列应被剥离: ${stdout.map { it.content }}")
        assertTrue(stdout.none { it.content.contains('\u001B') }, "输出不得残留 ESC 控制字符")
        Unit
    }

    @Test
    fun `crlf output splits into plain lines`() = runBlocking {
        val engine = newEngine()
        engine.submit(
            QueuedCommand(label = "crlf", commands = listOf("printf 'x\\r\\ny\\n'"), timeoutMs = 10_000)
        )
        awaitStatus(ExecStatus.Completed)

        val stdout = engine.output.value.items.filter { it.type == ExecOutputType.Stdout }
        assertEquals(listOf("x", "y"), stdout.map { it.content }, "CRLF 应按两行常规输出")
        Unit
    }
}
