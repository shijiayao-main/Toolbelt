package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.SqliteSessionStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogLoopEvent
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

/**
 * WorkbenchSession reducer 纪律 + HITL gate + 流式打字机单测（P4-C2 openspec
 * log-workbench 任务 3.2——零 LLM/零网络，executor/model provider 不触达）：
 * - 事件 → ChatEntry 映射（ToolCall 原位状态迁移）；
 * - StreamingFrame 流式草稿累积 + LlmCallCompleted 转正（streaming=false 保留 id）；
 * - finalizeRun 终稿消重（singleRunStrategy 下 LlmCallCompleted 与 output 恒同文本）；
 * - HITL gate 三态：pendingGate 流可见 / approve 放行 / deny 拒绝；
 * - sessionId=runId 时间线落库（B8 稳态条目语义）；
 * - 中止保全（cancel 后已落条目保留）；
 * - buildPrompt 结构化输入组装（纯函数直测）；
 * - 工具集装配面（WORKBENCH_NAMES 精确集合 + distill.* 恒不在 + 私钥门控）。
 */
class WorkbenchSessionTest {

    /** 不触达 executor/model 的会话（reducer 测试不跑轮） */
    private fun newSession(
        runId: String = "run-test",
        ledger: com.jiaoay.easy_dev_toolbox.core.data.db.SessionStore? = null,
    ) = WorkbenchSession(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        runId = runId,
        configProvider = { LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
        executorProvider = { error("reducer 测试不触达 executor") },
        modelProvider = { error("reducer 测试不触达 model") },
        privateKeyProvider = { "k" },
        memoryDir = null, // reducer 测试不装记忆域
        ledger = ledger,
    )

    // ── reducer：事件 → 条目映射 ──

    @Test
    fun `tool call events migrate one card in place`() {
        val session = newSession()
        session.onLoopEvent(KoogLoopEvent.ToolCallStarting(toolCallId = "tc-1", toolName = "log.analyze_log", argsSummary = "log_path=/a.alog"))
        val afterStart = session.entries.value
        assertTrue(afterStart.last() is ChatEntry.ToolCall, "工具卡落条目")
        assertEquals(ToolState.RUNNING, (afterStart.last() as ChatEntry.ToolCall).state)

        session.onLoopEvent(KoogLoopEvent.ToolCallCompleted(toolCallId = "tc-1", toolName = "log.analyze_log", observation = "[TAG_FILTER] 完成 → /a.filter-tag_x.txt"))
        assertEquals(ToolState.COMPLETED, (session.entries.value.last() as ChatEntry.ToolCall).state, "COMPLETED 原位回填不增行")

        session.onLoopEvent(KoogLoopEvent.ToolCallFailed(toolCallId = "tc-1", toolName = "log.analyze_log", error = "boom"))
        assertEquals(ToolState.FAILED, (session.entries.value.last() as ChatEntry.ToolCall).state, "FAILED 原位回填")
    }

    @Test
    fun `streaming frame accumulates draft and completion promotes it`() {
        val session = newSession()

        // 合并窗首 delta 立即发布；窗内后续 delta 只缓冲（冲刷由收口事件承担）
        session.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "结论前"))
        session.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "半段"))
        val draft = session.entries.value.last() as ChatEntry.Assistant
        assertEquals("结论前", draft.text, "窗内滞留 delta 不发布——草稿文本停在窗首 delta")
        assertTrue(draft.streaming, "delta 累积态是 streaming 草稿")

        // LlmCallCompleted 冲刷窗内滞留 delta 后原位转正（保留 id，streaming=false）
        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "结论前半段+终稿"))
        val promoted = session.entries.value.last() as ChatEntry.Assistant
        assertEquals(draft.id, promoted.id, "转正保留草稿 id（原位迁移）")
        assertEquals("结论前半段+终稿", promoted.text)
        assertEquals(false, promoted.streaming)
    }

    @Test
    fun `session reducer wires delta merge window with virtual time`() = kotlinx.coroutines.test.runTest {
        // 会话级接线取证：WorkbenchSession 把合并窗与 flush 作用域注入 reducer
        //（窗时长 = STREAMING_DELTA_MERGE_WINDOW_MILLIS 契约常量；虚拟时间驱动到期）
        val session = WorkbenchSession(
            scope = kotlinx.coroutines.CoroutineScope(coroutineContext),
            runId = "run-window",
            configProvider = { LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
            executorProvider = { error("reducer 测试不触达 executor") },
            modelProvider = { error("reducer 测试不触达 model") },
            privateKeyProvider = { "k" },
            memoryDir = null,
        )

        session.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "a"))
        session.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "b"))
        assertEquals(
            "a",
            (session.entries.value.last() as ChatEntry.Assistant).text,
            "窗内第二 delta 只缓冲不发布（合并窗已接线）",
        )

        advanceTimeBy(WorkbenchSession.STREAMING_DELTA_MERGE_WINDOW_MILLIS)
        runCurrent()
        assertEquals(
            "ab",
            (session.entries.value.last() as ChatEntry.Assistant).text,
            "窗到期冲刷合并文本",
        )
    }

    @Test
    fun `blank llm completion drops no entry`() {
        val session = newSession()
        val before = session.entries.value.size
        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = null))
        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "  "))
        assertEquals(before, session.entries.value.size, "空文本完成不落数")
    }

    @Test
    fun `finalize run dedupes terminal output against last assistant`() {
        val session = newSession()
        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "报告文本"))

        // 同文本终稿：跳过（singleRunStrategy 下恒同文本——B5 双写修复语义）
        session.finalizeRun("报告文本")
        assertEquals(1, session.entries.value.count { it is ChatEntry.Assistant }, "终稿消重不双写")

        // 不同文本（骨架兜底拼接）：正常 append
        session.finalizeRun("不同文本")
        assertEquals(2, session.entries.value.count { it is ChatEntry.Assistant })
    }

    @Test
    fun `finalize run promotes dangling draft when output blank`() {
        val session = newSession()
        session.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "流出了一半"))
        session.finalizeRun("") // 流中断无完成事件——草稿转正兜底
        val last = session.entries.value.last() as ChatEntry.Assistant
        assertEquals("流出了一半", last.text)
        assertEquals(false, last.streaming)
    }

    // ── HITL gate ──

    @Test
    fun `gate decision approve and deny resolve pending`() = kotlinx.coroutines.test.runTest {
        val session = newSession()
        assertNull(session.pendingGate.value, "无请求时 pendingGate 为 null")

        // 发起 gate 请求（挂起等裁决）——runTest 的 TestScope receiver 直接 launch
        val decision = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val job = launch {
            decision.complete(session.awaitGateDecisionForTest(File("/outside.alog")))
        }
        advanceUntilIdle()
        assertEquals(File("/outside.alog").absolutePath, session.pendingGate.value?.absolutePath, "挂起等待时 pendingGate 抬起")

        session.approveGate()
        advanceUntilIdle()
        job.join()
        assertTrue(decision.getCompleted() == true, "approve 放行")
        assertNull(session.pendingGate.value, "裁决后 pendingGate 归 null")

        // deny 路径
        val decision2 = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val job2 = launch {
            decision2.complete(session.awaitGateDecisionForTest(File("/outside2.alog")))
        }
        advanceUntilIdle()
        session.denyGate()
        advanceUntilIdle()
        job2.join()
        assertTrue(decision2.getCompleted() == false, "deny 拒绝")
    }

    // ── 时间线落库（sessionId=runId）──

    @Test
    fun `entries persist to run timeline via ledger`() = kotlinx.coroutines.test.runTest {
        val db = Database.inMemory()
        val ledger = SqliteSessionStore(db)
        val session = newSession(runId = "run-persist", ledger = ledger)

        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "研判结论"))
        session.onLoopEvent(KoogLoopEvent.ToolCallStarting(toolCallId = "tc-9", toolName = "log.analyze_log", argsSummary = "x"))
        session.onLoopEvent(KoogLoopEvent.ToolCallCompleted(toolCallId = "tc-9", toolName = "log.analyze_log", observation = "ok"))

        val restored = ledger.replay("run-persist")
        assertEquals(2, restored.size, "B8 稳态语义：Assistant + 终态 ToolCall 落库（Activity/RUNNING 不落）")
        assertTrue(restored.first() is ChatEntry.Assistant)
    }

    @Test
    fun `cancel preserves produced entries`() {
        val session = newSession()
        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "部分结论"))
        session.cancel()
        assertEquals(false, session.running.value, "cancel 置停")
        assertEquals(1, session.entries.value.count { it is ChatEntry.Assistant }, "已产条目保全不清空")
    }

    // ── prompt 组装（纯函数） ──

    @Test
    fun `build prompt carries structured input and followup context`() {
        val session = newSession()
        val prompt = session.buildPrompt(
            WorkbenchSession.AnalysisInput(
                logFiles = listOf("/a/main.alog.txt", "/b/main.alog.txt"),
                targetTime = "2026-09-19_12:00:00.000",
                rangeMinutes = 5,
                symptom = "设置页面黑屏",
                tagRegex = null,
            ),
            followUpContext = null,
        )
        assertTrue("/a/main.alog.txt" in prompt && "/b/main.alog.txt" in prompt, "日志文件逐个携带")
        assertTrue("2026-09-19_12:00:00.000" in prompt)
        assertTrue("设置页面黑屏" in prompt)

        val followUp = session.buildPrompt(
            WorkbenchSession.AnalysisInput(listOf("/a.txt"), "t", 5, "s"),
            followUpContext = "已有报告正文……",
        )
        assertTrue("已有报告（增量研判——新结论追加，原结论不改写）" in followUp, "追问链上下文段存在")
    }

    // ── 工具集装配面（4.2） ──

    @Test
    fun `workbench names cover 23 tools and never include distill`() {
        assertEquals(
            setOf(
                // fs.extended 七件
                "fs.extended.read_file", "fs.extended.grep", "fs.extended.glob",
                "fs.extended.hash_file", "fs.extended.hex_dump", "fs.extended.strings",
                "fs.extended.convert_timestamp",
                // knowledge 四件
                "knowledge.search_cases", "knowledge.search", "knowledge.read", "knowledge.lookup_tag",
                // memory 四件
                "memory.view", "memory.append", "memory.replace", "memory.delete",
                // 管线
                "log.analyze_log",
                // log 时间过滤两件（对称窗口 / 任意区间）
                "log.filter_by_time", "log.filter_by_range",
                // 侦察四件
                "log.stats", "log.diff", "log.view", "log.cluster",
                // 网络查询一件
                "web.fetch",
            ),
            WorkbenchSession.WORKBENCH_NAMES,
            "23 件工具名精确集合（目录名契约）",
        )
        assertTrue("distill.report_step" !in WorkbenchSession.WORKBENCH_NAMES, "🔴 distill.* 恒不进工作台")
        assertTrue("distill.propose_knowledge" !in WorkbenchSession.WORKBENCH_NAMES)
    }

    @Test
    fun `registry assembles by names and gates analyze log by key`() {
        val session = newSession()
        val registry = session.buildRegistryForTest()
        val names = registry.tools.map { it.name }.toSet()
        // 🔴 registry 调用名 = 工具类自带 name（旧裸名——LLM 存量提示词零迁移）；
        // 目录层的点分命名空间是 catalog 身份。memoryDir=null 门控：memory 四件不装。
        val expected = setOf(
            "read_file", "grep", "glob", "hash_file", "hex_dump", "strings", "convert_timestamp",
            "knowledge.search", "knowledge.read", "knowledge.lookup_tag",
            "analyze_log",
            "filter_by_time", "filter_by_range",
            "write_todos",
            "web.fetch",
        )
        assertTrue(names.containsAll(expected), "registry 调用名全装：缺 ${expected - names}")
        assertTrue(names.none { it.startsWith("memory.") }, "memoryDir=null ⇒ 记忆四件不装")
        assertTrue(names.none { it.startsWith("distill.") }, "🔴 distill.* 恒不在装配面")
    }
}
