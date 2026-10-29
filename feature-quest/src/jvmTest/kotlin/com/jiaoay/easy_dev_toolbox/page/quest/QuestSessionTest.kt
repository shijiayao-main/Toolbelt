@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.jiaoay.easy_dev_toolbox.page.quest

import com.jiaoay.testfixtures.KoogFakeExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.utils.time.KoogClock
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChip
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ArtifactChipKind
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogLoopEvent
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskField
import com.jiaoay.easy_dev_toolbox.core.llm.tools.AskRequest
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolPayload
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResult
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * QuestSession reducer 纪律 + ask 桥 + cancel 收口单测（Quest 域 change 2 任务 2.4——
 * WorkbenchSessionTest 配方平移；零 LLM/零网络，executor/model provider 不触达）。
 *
 * 覆盖：
 * - reducer：ToolCall 原位迁移 / StreamingFrame 草稿→转正 / finalizeRun 消重；
 * - ask 桥三态：pendingQuestion 流可见 / resolveAsk 回填 / skipAsk 软跳过；
 * - 🔴 cancel 穿透收口：pendingQuestion 归 null + 尾随 RUNNING 卡翻 CANCELLED；
 * - buildPrompt：首轮开工纪律段 + 能力 promptSection 拼接；
 * - S5c 评审修复：收尾+队列非空 ⇒ running 不落下降沿（防「已完成」伪通知）/
 *   drain 续发不重拼能力段（ChatMemory 历史查证）/ 排队容量上限。
 *
 * 🔴 换 store 纪律（AiToolsToggleTest 配方）：成功轮基座触达 maxIterations()（DataStore
 *    读链），setUp 换临时 user.home + 独立 LocalDataManager——测试不触真实用户配置。
 */
class QuestSessionTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-quest-test")

    private lateinit var tempHome: File
    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUpStore() {
        homeIsolation.redirect()
                originalStore = LlmConfigStore.store
        LlmConfigStore.store = LocalDataManager(dataStoreName = "test_quest_session_${UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDownStore() {
        homeIsolation.restore()
        LlmConfigStore.store = originalStore
    }

    /** 不触达 executor/model 的会话（reducer 测试不跑轮） */
    private fun newSession(
        flowId: String = "flow-test",
    ) = QuestSession(
        scope = CoroutineScope(Dispatchers.Unconfined),
        flowId = flowId,
        configProvider = { LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
        executorProvider = { error("reducer 测试不触达 executor") },
        modelProvider = { error("reducer 测试不触达 model") },
        historyProvider = QuestHistoryProvider.forFile(
            File(System.getProperty("java.io.tmpdir"), "quest-session-test-${System.nanoTime()}.db").absolutePath,
        ),
        ledger = null,
    )

    private fun askRequest(question: String = "缺日志文件路径吗？", allowSkip: Boolean = false): AskRequest =
        AskRequest(toolCallId = "tc-1", question = question, fields = listOf(AskField.Text("log", "日志文件")), allowSkip = allowSkip)

    // ── reducer：事件 → 条目映射 ──

    @Test
    fun `tool call events migrate one card in place`() {
        val session = newSession()
        session.onLoopEvent(KoogLoopEvent.ToolCallStarting(toolCallId = "tc-1", toolName = "user.ask_user", argsSummary = "question=缺什么"))
        assertTrue(session.entries.value.last() is ChatEntry.ToolCall)
        assertEquals(ToolState.RUNNING, (session.entries.value.last() as ChatEntry.ToolCall).state)

        session.onLoopEvent(KoogLoopEvent.ToolCallCompleted(toolCallId = "tc-1", toolName = "user.ask_user", observation = "用户回答：…"))
        assertEquals(ToolState.COMPLETED, (session.entries.value.last() as ChatEntry.ToolCall).state)
    }

    @Test
    fun `streaming frame accumulates draft and completion promotes it`() {
        val session = newSession()
        session.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "你"))
        session.onLoopEvent(KoogLoopEvent.StreamingFrame(delta = "好"))
        val draft = session.entries.value.last() as ChatEntry.Assistant
        assertTrue(draft.streaming)
        assertEquals("你好", draft.text)

        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "你好，流程开始。"))
        val promoted = session.entries.value.last() as ChatEntry.Assistant
        assertEquals(draft.id, promoted.id, "转正保留 id")
        assertEquals("你好，流程开始。", promoted.text)
        assertTrue(!promoted.streaming)
    }

    @Test
    fun `finalize run dedupes terminal output against last assistant`() {
        val session = newSession()
        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "结论"))
        val sizeAfterLlm = session.entries.value.size
        session.finalizeRun("结论")
        assertEquals(sizeAfterLlm, session.entries.value.size, "终稿与末条同文本——不双写")
    }

    // ── ask 桥三态（runTest + Unconfined 内层 async——挂起等待面） ──

    @Test
    fun `ask bridge surfaces pending question and resolve feeds answer`() = runTest {
        val session = newSession()
        val answer = CompletableDeferred<Map<String, String>>()

        // 内层 async 用 Unconfined：onAsk 首段立即执行到 deferred.await 挂起点
        val worker = CoroutineScope(Dispatchers.Unconfined).async {
            val result = session.onAskForTest(askRequest())
            answer.complete(result.values)
        }

        // 挂起期间 pendingQuestion 流可见（AskCard 渲染面）
        assertNotNull(session.pendingQuestion.value, "挂起期间 pendingQuestion 流可见")
        assertEquals("缺日志文件路径吗？", session.pendingQuestion.value!!.question)

        session.resolveAsk(mapOf("log" to "/tmp/app.log"))
        assertEquals(mapOf("log" to "/tmp/app.log"), answer.await())
        assertNull(session.pendingQuestion.value, "回答后表单消隐")
        worker.cancel()
    }

    @Test
    fun `ask bridge skip yields skipped answer`() = runTest {
        val session = newSession()
        val answer = CompletableDeferred<Boolean>()
        val worker = CoroutineScope(Dispatchers.Unconfined).async {
            val result = session.onAskForTest(askRequest(question = "还有要补充的吗？", allowSkip = true))
            answer.complete(result.skipped)
        }
        assertNotNull(session.pendingQuestion.value)
        assertTrue(session.pendingQuestion.value!!.allowSkip)
        session.skipAsk()
        assertTrue(answer.await(), "skip 走 skipped=true 软反馈路径")
        assertNull(session.pendingQuestion.value)
        worker.cancel()
    }

    // ── cancel 收口（🔴 红线：表单不残留 + RUNNING 卡翻 CANCELLED） ──

    @Test
    fun `cancel clears pending question and flips running tool cards`() = runTest {
        val session = newSession()
        val worker = CoroutineScope(Dispatchers.Unconfined).async {
            session.onAskForTest(askRequest())
        }
        assertNotNull(session.pendingQuestion.value)
        session.onLoopEvent(KoogLoopEvent.ToolCallStarting(toolCallId = "tc-1", toolName = "user.ask_user", argsSummary = "q"))

        session.cancel()

        assertNull(session.pendingQuestion.value, "🔴 cancel 清 pendingQuestion——表单不残留")
        val lastCard = session.entries.value.last() as ChatEntry.ToolCall
        assertEquals(ToolState.CANCELLED, lastCard.state, "尾随 RUNNING 卡翻 CANCELLED")
        assertTrue(!session.running.value)
        worker.cancel()
    }

    // ── prompt 组装（纯函数直测） ──

    @Test
    fun `build prompt carries opening discipline on first round only`() {
        val session = newSession()
        val first = session.buildPrompt("分析这个日志", firstRound = true, promptSection = "")
        assertTrue("开工纪律" in first, "首轮带开工纪律段")
        assertTrue("user.ask_user" in first)

        val followUp = session.buildPrompt("继续，文件是 /a.log", firstRound = false, promptSection = "")
        assertTrue("开工纪律" !in followUp, "续轮不带开工纪律")

        val withAbility = session.buildPrompt("目标", firstRound = true, promptSection = "## 日志分析能力段")
        assertTrue(withAbility.startsWith("## 日志分析能力段"), "能力段拼在最前")
    }

    // ── gate 桥三态（change 3——围栏外路径确认；WorkbenchSessionTest 配方平移） ──

    @Test
    fun `gate bridge allow resolves true`() = runTest {
        val session = newSession()
        val result = CompletableDeferred<Boolean>()
        val worker = CoroutineScope(Dispatchers.Unconfined).async {
            result.complete(session.awaitGateDecisionForTest(File("/data/outside.log")))
        }
        // 挂起期间确认卡可见（PathConfirmCard 渲染面）
        assertEquals(File("/data/outside.log"), session.pendingGate.value)
        session.approveGate()
        assertTrue(result.await(), "批准 = true（路径随后由工具侧登记围栏）")
        assertNull(session.pendingGate.value, "裁决后确认卡消隐")
        worker.cancel()
    }

    @Test
    fun `gate bridge deny resolves false`() = runTest {
        val session = newSession()
        val result = CompletableDeferred<Boolean>()
        val worker = CoroutineScope(Dispatchers.Unconfined).async {
            result.complete(session.awaitGateDecisionForTest(File("/data/outside.log")))
        }
        assertNotNull(session.pendingGate.value)
        session.denyGate()
        assertTrue(!result.await(), "拒绝 = false（工具收 DENIED 软反馈）")
        assertNull(session.pendingGate.value)
        worker.cancel()
    }

    @Test
    fun `cancel clears pending gate and card does not linger`() = runTest {
        val session = newSession()
        val worker = CoroutineScope(Dispatchers.Unconfined).async {
            session.awaitGateDecisionForTest(File("/data/outside.log"))
        }
        assertNotNull(session.pendingGate.value)
        session.cancel()
        assertNull(session.pendingGate.value, "🔴 cancel 清 pendingGate——确认卡不残留")
        assertTrue(!session.running.value)
        worker.cancel()
    }

    // ── S5d · sink 产物物化（WorkbenchSession 十八行配方平移——emitSinkForTest 直测缝） ──

    @Test
    fun `sink materializes artifacts onto trailing same-name tool card and accumulates evidence`() {
        val session = newSession()
        session.onLoopEvent(KoogLoopEvent.ToolCallStarting(toolCallId = "tc-art", toolName = "log.analyze_log", argsSummary = "file=/a.log"))

        session.emitSinkForTest(
            "log.analyze_log",
            ToolResult.Ok(
                summary = "完成",
                artifacts = listOf(
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.ArtifactRef("/tmp/report.md", com.jiaoay.easy_dev_toolbox.core.llm.tools.ArtifactKind.REPORT),
                    com.jiaoay.easy_dev_toolbox.core.llm.tools.ArtifactRef("/tmp/extracted.txt", com.jiaoay.easy_dev_toolbox.core.llm.tools.ArtifactKind.EXTRACTED),
                ),
            ),
        )

        val card = session.entries.value.last() as ChatEntry.ToolCall
        assertEquals(
            listOf(
                ArtifactChip("/tmp/report.md", ArtifactChipKind.REPORT),
                ArtifactChip("/tmp/extracted.txt", ArtifactChipKind.EXTRACTED),
            ),
            card.artifacts,
            "产物 chips 精确挂末尾同名工具卡",
        )
        assertEquals(
            listOf("/tmp/report.md", "/tmp/extracted.txt"),
            session.evidenceFiles.value,
            "证据文件集随产物累积",
        )
    }

    @Test
    fun `sink ask request branch still surfaces pending question`() {
        val session = newSession()
        val request = askRequest()
        session.emitSinkForTest("user.ask_user", ToolResult.Ok(summary = "提问", data = ToolPayload.AskRequestPayload(request)))
        assertEquals(request.question, session.pendingQuestion.value?.question, "ask 桥分支不回归")
        assertTrue(session.evidenceFiles.value.isEmpty(), "AskRequestPayload 不进证据面")
    }

    @Test
    fun `restore reimports evidence files from tool card artifacts`() {
        val session = newSession()
        assertTrue(session.evidenceFiles.value.isEmpty())
        session.restore(
            listOf(
                ChatEntry.User(id = "u1", text = "目标"),
                ChatEntry.ToolCall(
                    id = "t1",
                    toolCallId = null,
                    toolName = "log.analyze_log",
                    argsSummary = "file=/a.log",
                    state = ToolState.COMPLETED,
                    artifacts = listOf(ArtifactChip("/tmp/report.md", ArtifactChipKind.REPORT)),
                ),
            ),
        )
        assertEquals(listOf("/tmp/report.md"), session.evidenceFiles.value, "回看自工具卡 chips 重导证据面")
    }

    // ── S5c · Steer 排队（running 期入队 / 收尾 drain / 取消清队序） ──
    // 🔴 gate 控制的 configProvider：send 后 launch 体挂起在 gate.await——running 恒 true，
    //    使「running 期到达」窗口确定化（error executor 直炸的形态在 Unconfined 下同步收尾，
    //    无法构造入队窗口）。

    private fun gatedSession(
        flowId: String,
        gates: ArrayDeque<CompletableDeferred<Unit>>,
    ) = QuestSession(
        scope = CoroutineScope(Dispatchers.Unconfined),
        flowId = flowId,
        configProvider = {
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            gate.await()
            LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60)
        },
        executorProvider = { error("executor 不可用") },
        modelProvider = { error("model 不可用") },
        historyProvider = QuestHistoryProvider.forFile(
            File(System.getProperty("java.io.tmpdir"), "quest-$flowId-${System.nanoTime()}.db").absolutePath,
        ),
        ledger = null,
    )

    @Test
    fun `send during running enqueues and cancel clears queue`() = runTest {
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val session = gatedSession("flow-enqueue", gates)

        session.send("首轮", firstRound = true)
        assertTrue(session.running.value, "launch 体挂于 gate——running 恒 true（入队窗口确定化）")

        assertTrue(session.send("插话一", firstRound = false), "队未满——入队成功")
        assertTrue(session.send("插话二", firstRound = false))
        assertEquals(
            listOf("插话一", "插话二"),
            session.pendingQueue.value,
            "🔴 running 期 send 入队而非静默丢弃（S5c）",
        )

        session.cancel()
        assertTrue(session.pendingQueue.value.isEmpty(), "🔴 cancel 清队先于 runJob.cancel——插话不残留")
        assertTrue(!session.running.value)
        // gate 永不放行 + runJob 已 cancel（await 是可取消挂起点）——CE rethrow 不 drain
        advanceUntilIdle()
        assertEquals(
            1,
            session.entries.value.count { it is ChatEntry.User },
            "取消后队列消息不再落时间线（收尾 drain 不触发）",
        )
    }

    @Test
    fun `pending queue rejects beyond cap and sender receipt reflects`() = runTest {
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val session = gatedSession("flow-cap", gates)

        session.send("首轮", firstRound = true)
        assertTrue(session.running.value)
        repeat(QuestSession.PENDING_QUEUE_MAX) { idx ->
            assertTrue(session.send("插话$idx", firstRound = false), "第 ${idx + 1} 条入队应成功（cap=${QuestSession.PENDING_QUEUE_MAX}）")
        }
        assertEquals(QuestSession.PENDING_QUEUE_MAX, session.pendingQueue.value.size)

        // 超限：不入队、send 回执 false（调用方据此提示「队列已满」）
        assertTrue(!session.send("多出的一条", firstRound = false), "🔴 超限拒绝——不入队")
        assertEquals(QuestSession.PENDING_QUEUE_MAX, session.pendingQueue.value.size, "队列长度不越 cap")
        assertEquals("多出的一条" !in session.pendingQueue.value, true, "被拒消息不残留队列")

        session.cancel() // 收口挂于 gate 的 launch 协程——用例退出不留悬挂续体
    }

    @Test
    fun `settle with queued steer keeps running true until queue drains`() = runTest {
        // 🔴 评审批修复（S5b 伪通知）：收尾时队列非空 ⇒ running 不落下降沿——通知 collector
        //    只在「队列真正跑空」的收尾点看到一次 true→false，切走 tab 不会再误弹「已完成」。
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val session = gatedSession("flow-settle", gates)

        // running 过渡序列记录（Unconfined scope 同步 collect——StateFlow 赋值即分发）
        val transitions = mutableListOf<Boolean>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            session.running.collect { transitions.add(it) }
        }

        session.send("首轮", firstRound = true)
        assertTrue(session.running.value)
        session.send("插话", firstRound = false)
        assertEquals(listOf("插话"), session.pendingQueue.value)

        // 放行首轮：executorProvider 炸 → FAILED 收尾 + 队列非空 → 不落 running=false，直接续发
        gates.removeFirst().complete(Unit)
        assertTrue(session.running.value, "🔴 收尾+队列非空——running 保持 true（无下降沿，插话轮无缝接管）")
        assertTrue(session.pendingQueue.value.isEmpty(), "续发出队——队清")
        assertEquals(1, session.entries.value.count { it is ChatEntry.ErrorBanner }, "首轮失败落 ErrorBanner")
        assertTrue(session.entries.value.any { (it as? ChatEntry.User)?.text == "插话" }, "插话轮已开跑（User 落时间线）")

        // 放行插话轮：同样失败收尾，队空 → 唯一一次下降沿
        gates.removeFirst().complete(Unit)
        assertTrue(!session.running.value, "队列跑空的收尾——running 落 false（唯一下降沿）")
        assertEquals(2, session.entries.value.count { it is ChatEntry.ErrorBanner })

        advanceUntilIdle()
        watcher.cancel()
        // 🔴 过渡序精确钉死：[初值回放 false, 起 true, 终 false]——若缺陷存在（收尾先落
        // false 再 drain 置回 true），序将多出 [true, false, true] 中段，完成通知判定面
        // （prev && !cur && !chatVisible）即触发伪通知。现断言：运行期无下降沿。
        assertEquals(listOf(false, true, false), transitions, "running 全程仅一次起落——插话接管无缝")
    }

    @Test
    fun `queued message drains and resends after failure round settles`() = runTest {
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val session = gatedSession("flow-drain", gates)

        session.send("第一条", firstRound = true)
        assertTrue(session.running.value)
        session.send("插话", firstRound = false)
        assertEquals(listOf("插话"), session.pendingQueue.value)

        // 放行首轮：executorProvider 抛错 → FAILED 收尾（running 保持 true）→ drain 续发「插话」
        gates.removeFirst().complete(Unit)
        assertTrue(session.pendingQueue.value.isEmpty(), "收尾 drain 出队——队清")
        assertEquals(
            listOf("第一条", "插话"),
            session.entries.value.filterIsInstance<ChatEntry.User>().map { it.text },
            "插话经 drain 续发落时间线（drain 走非首轮——不带开工纪律）",
        )
        assertTrue(session.running.value, "插话轮运行中（挂于第二个 gate）")
        assertEquals(1, session.entries.value.count { it is ChatEntry.ErrorBanner }, "首轮失败落 ErrorBanner")

        // 放行插话轮：同样失败收尾，drain 队空 no-op
        gates.removeFirst().complete(Unit)
        assertTrue(!session.running.value, "全链收尾 running=false")
        assertEquals(2, session.entries.value.count { it is ChatEntry.ErrorBanner })
        assertEquals(2, session.entries.value.count { it is ChatEntry.User })
    }

    // ── 评审批修复 P1-5：drain 续发不重拼能力段（ChatMemory 历史查证）──
    // 🔴 成功轮基座：真 koogToolLoopWithMemory + ChatMemory.Feature + 真 QuestHistoryProvider
    //    （临时 SQLite）——executor 重写 ResolvedModel 重载（koog-fake-executor-resolvedmodel
    //    教训：AIAgent 实调该重载，只重写 LLModel 重载会绕到真 client 发请求）。

    /** 成功型 fake executor：共享 KoogFakeExecutor 基座覆写固定「完成」文本 */
    private class SucceedingExecutor : KoogFakeExecutor() {
        override suspend fun fakeExecute(): Message.Assistant = Message.Assistant(
            content = "完成",
            metaInfo = ResponseMetaInfo.create(KoogClock.System),
        )
    }

    /** 成功轮会话：config gate 逐轮放行（构造入队窗口）+ 成功 executor + 独立历史库。 */
    private fun succeedingSession(
        flowId: String,
        gates: ArrayDeque<CompletableDeferred<Unit>>,
        historyProvider: QuestHistoryProvider,
    ) = QuestSession(
        scope = CoroutineScope(Dispatchers.Unconfined),
        flowId = flowId,
        configProvider = {
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            gate.await()
            LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60)
        },
        executorProvider = { SucceedingExecutor() },
        modelProvider = {
            LLModel(provider = LLMProvider.OpenAI, id = "test-model", capabilities = listOf(LLMCapability.Completion))
        },
        historyProvider = historyProvider,
        ledger = null,
    )

    @Test
    fun `drain resends queued steer without re-injecting ability prompt section`() = runTest {
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val dbPath = File(System.getProperty("java.io.tmpdir"), "quest-drain-prompt-${System.nanoTime()}.db").absolutePath
        val historyProvider = QuestHistoryProvider.forFile(dbPath)
        val session = succeedingSession("flow-drain-prompt", gates, historyProvider)

        // 首轮带能力段（gated：running 期构造插话入队窗口）
        session.send("首轮目标", firstRound = true, promptSection = "## 日志分析能力段")
        assertTrue(session.running.value)
        session.send("插话追问", firstRound = false)
        assertEquals(listOf("插话追问"), session.pendingQueue.value)

        // 放行首轮：成功收尾 → drain 续发插话轮（🔴 修复点：promptSection 空段）
        gates.removeFirst().complete(Unit)
        awaitCondition("插话轮接管（running 保持 true）") { session.pendingQueue.value.isEmpty() }
        assertTrue(session.running.value, "插话轮接管（无 running 下降沿）")

        // 放行插话轮：成功收尾、队空 → running=false（DataStore/SQLite 真实 IO，轮询等待落库）
        awaitCondition("插话轮 config gate 就位") { gates.isNotEmpty() }
        gates.removeFirst().complete(Unit)
        awaitCondition("插话轮收尾 running=false") { !session.running.value }

        // ChatMemory 历史查证：两条 User 消息——首轮含能力段+开工纪律，插话轮两者皆无
        val userTexts = historyProvider.load("flow-drain-prompt")
            .filterIsInstance<Message.User>()
            .map { it.textContent() }
        assertEquals(2, userTexts.size, "两轮各一条 User 消息（窗口 40 条内无截断）")
        assertTrue("## 日志分析能力段" in userTexts[0], "能力段只随首轮进历史（首轮一份）")
        assertTrue("开工纪律" in userTexts[0], "首轮开工纪律段在历史")
        assertEquals("插话追问", userTexts[1], "🔴 插话轮 prompt = 纯插话文本（能力段/开工纪律不重拼——重复注入即违规）")
    }

    @Test
    fun `successful round with empty queue settles with single running falling edge`() = runTest {
        // 对照面：队列跑空的成功收尾仍正常落 running=false（通知面唯一触发点保持可达）
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        val dbPath = File(System.getProperty("java.io.tmpdir"), "quest-settle-edge-${System.nanoTime()}.db").absolutePath
        val session = succeedingSession("flow-settle-edge", gates, QuestHistoryProvider.forFile(dbPath))

        session.send("单独一轮", firstRound = true)
        assertTrue(session.running.value)
        awaitCondition("首轮 config gate 就位") { gates.isNotEmpty() }
        gates.removeFirst().complete(Unit)
        awaitCondition("成功收尾 running=false") { !session.running.value }
        assertTrue(session.entries.value.last() is ChatEntry.Assistant, "成功轮终稿落时间线")
    }

    /** 真实 IO 轮询等待（DataStore 读/SQLite 落盘在 Dispatchers.IO——runTest 虚拟时钟推不动，
     *  以截止时限内真实轮询代替 advanceUntilIdle；超时 fail 防挂死）。 */
    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                fail("等待超时：$what")
            }
            Thread.sleep(20)
        }
    }
}
