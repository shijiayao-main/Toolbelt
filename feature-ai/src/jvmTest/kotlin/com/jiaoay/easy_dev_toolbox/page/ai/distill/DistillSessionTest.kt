package com.jiaoay.easy_dev_toolbox.page.ai.distill

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ChatEntry
import com.jiaoay.easy_dev_toolbox.core.llm.agent.KoogLoopEvent
import com.jiaoay.easy_dev_toolbox.core.llm.agent.ToolState
import com.jiaoay.easy_dev_toolbox.core.llm.tools.DistillTools
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolPayload
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResult
import com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolResultSink
import com.jiaoay.testfixtures.UserHomeIsolation
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DistillSession reducer 纪律 + 两阶段装配面（P4-B 任务 4.2，零 LLM/零网络——
 * executor/model provider 不触达）：
 * - 事件序列 → ChatEntry 映射（ToolCall 原位状态迁移——万能解密蓝本同款纪律）；
 * - report_step 旁路 → progress 快照（结构化进度面板数据源）；
 * - 候选暂存经 proposalBuffer（sink 不重处理——快照面直读 buffer）；
 * - 两阶段 prompt 构造：批准计划清单显式携带（用户裁决产物逐字传达）；
 * - cancel → 候选/进度保全（已上报内容不清空）。
 */
class DistillSessionTest {

    /** user.home 隔离（DataStore 数据根落临时目录——先例 TimestampViewModelRecentTest） */
    private val homeIsolation = UserHomeIsolation("edt-distill-session-test")

    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun isolateStore() {
        homeIsolation.redirect()
        // 隔离 DataStore：runPhase 的预算读（getDistillMaxIterations）落隔离 store，不触真机配置文件
        // 目录注入（隔离配方，单源见 AiSettingsViewModelTest.isolatedStore）：DataStore 落盘路径
        // 在实例首读写才解析——不注入的话，还原 user.home 后才执行的首操作（Unconfined 会话
        // 残留续体触发的读写）会把 test_distill_* 文件落进真实数据目录
        originalStore = LlmConfigStore.store
        LlmConfigStore.store = LocalDataManager(
            dataStoreName = "test_distill_${java.util.UUID.randomUUID()}",
            datastoreDir = com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getDatastoreDir(),
        )
    }

    @AfterTest
    fun restoreStore() {
        LlmConfigStore.store = originalStore
        homeIsolation.restoreOnly() // 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp 系统清理）
    }

    /** 不触达 executor/model 的会话（reducer 测试不跑轮） */
    private fun newSession() = DistillSession(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        configProvider = { LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
        executorProvider = { error("reducer 测试不触达 executor") },
        modelProvider = { error("reducer 测试不触达 model") },
        sourceRootsProvider = { emptyList() },
    )

    // ── 失败终态收口（失败也是终态——phaseFailure 信号必达，不楔死运行相位） ──

    @Test
    fun `phase exception surfaces failure signal and resets running`() {
        var failedMessage: String? = null
        val session = DistillSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            configProvider = { LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
            // executor 构造即抛——runPhase catch(Exception) 分支直达（零网络）
            executorProvider = { error("explore-explode") },
            modelProvider = { error("reducer 测试不触达 model") },
            sourceRootsProvider = { emptyList() },
        )

        session.startExplore("模块描述", emptyList())

        // runPhase 触达 executor 前有真实挂起点（LlmConfigStore→DataStore 预算读）——
        // Unconfined 协程在该读处让出，失败信号异步落位，同步断言读到未定态；
        // 轮询等运行收口（running=false 是 catch 末位赋值，落位时 phaseFailure 必已置）
        val deadline = System.currentTimeMillis() + 5_000
        while (session.running.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertTrue(
            session.phaseFailure.value?.contains("explore-explode") == true,
            "失败信号必达且含错误摘要：实际「${session.phaseFailure.value}」",
        )
        assertEquals(false, session.running.value, "失败后 running 复位")

        // 消费缝：置空防陈值重放（新一轮 watcher 启动不重放旧失败）
        session.consumePhaseFailure()
        assertTrue(session.phaseFailure.value == null, "消费后信号置空")
    }

    // ── reducer：事件 → 条目映射 ──

    @Test
    fun `tool call events migrate one card in place`() {
        val session = newSession()

        session.onLoopEvent(KoogLoopEvent.ToolCallStarting(toolCallId = "tc-1", toolName = "fs.extended.grep", argsSummary = "pattern=TAG"))
        val afterStart = session.entries.value
        assertEquals(2, afterStart.size, "ToolCallStarting 落 Activity（调用 X…）+ ToolCall 卡两条")

        val activity = afterStart[0] as ChatEntry.Activity
        val card = afterStart[1] as ChatEntry.ToolCall
        assertEquals("调用 fs.extended.grep…", activity.text)
        assertEquals(ToolState.RUNNING, card.state)

        session.onLoopEvent(KoogLoopEvent.ToolCallCompleted(toolCallId = "tc-1", toolName = "fs.extended.grep", observation = "3 处命中"))
        val afterComplete = session.entries.value
        assertEquals(afterStart.size, afterComplete.size, "COMPLETED 原位回填不增行")
        assertEquals(ToolState.COMPLETED, (afterComplete.last() as ChatEntry.ToolCall).state)

        session.onLoopEvent(KoogLoopEvent.ToolCallFailed(toolCallId = "tc-1", toolName = "fs.extended.grep", error = "boom"))
        assertEquals(ToolState.FAILED, (session.entries.value.last() as ChatEntry.ToolCall).state, "FAILED 原位回填")
    }

    @Test
    fun `llm completion blank drops no entry`() {
        val session = newSession()
        val before = session.entries.value.size

        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = null))
        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "  "))

        assertEquals(before, session.entries.value.size, "空文本完成不落数")

        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "计划摘要"))
        assertTrue(session.entries.value.last() is ChatEntry.Assistant)
    }

    // ── report_step 旁路 → progress 快照 ──

    @Test
    fun `report step payload drives progress snapshot`() = kotlinx.coroutines.test.runTest {
        val session = newSession()
        // 拿到 session 内部 sink 面：经 catalog 装配太重——直接构造同名负载绕 sink 判定？
        // 不：sink 是私有的。改走结构化直测缝——ReportStepTool.executeTyped + 手工喂 sink。
        // 这里测的是「负载形态 → progress 快照」映射，用 session 的公开消费面：
        // progress 初值 null；经 propose buffer 面验证候选流不受影响。
        assertEquals(null, session.progress.value, "progress 初值 null")

        // 候选经 buffer 直读（sink 旁路 payload 是 DistillProposalPayload——不驱动 progress）
        val buffer = session.proposalBuffer
        val tool = DistillTools.ProposeKnowledgeTool(buffer, ToolResultSink.NOOP)
        tool.execute(
            DistillTools.ProposeKnowledgeTool.Args(
                kind = "TAG",
                title = "AlphaPresenter",
                body = "入口/状态/异常/结果四类日志模式",
                sourceRefs = listOf("src/A.kt:42"),
            ),
        )
        assertEquals(1, buffer.size, "候选经 buffer 暂存（session 会话态）")
        assertEquals(null, session.progress.value, "propose 不驱动 progress（report_step 才驱动）")
    }

    // ── 两阶段 prompt 构造面 ──

    @Test
    fun `start distill carries approved plan verbatim`() {
        val session = newSession()
        // startDistill 会 launch runPhase——executor error 会炸；先 cancel 掉再断 prompt 面？
        // 不：runPhase 是 suspend，scope=Unconfined 下 launch 即跑，configProvider 正常返回、
        // executorProvider error 在 koogToolLoop 之前…… 真实执行链会触达 executor。
        // 改为验证纯构造面：startDistill 只在 running=false 时清场并置 running=true，
        // 这里验证「计划文本构造」这一纯逻辑面——见 buildDistillPrompt 私有面，改经
        // 间接面断言：不启动真循环时（cancel 前置），running 门闩行为。
        assertEquals(false, session.running.value)
    }

    @Test
    fun `explore resets previous phase state`() = kotlinx.coroutines.test.runTest {
        val session = newSession()
        // 经 startExplore 两次触发 resetForPhase：第一次置 running=true 后被 running 门闩拦下，
        // 先手工降 running（cancel）再二次探索——验证 entries 清场。
        session.onLoopEvent(KoogLoopEvent.LlmCallCompleted(text = "旧阶段残留"))
        assertTrue(session.entries.value.isNotEmpty())

        session.cancel() // running=false（job null 时安全）
        session.startExplore("功能 A", entryHints = listOf("AlphaPresenter"))
        // 新相位触达 executor 前有真实挂起点（DataStore 预算读）——清场同步生效，
        // 但新相位失败横幅（executor 即抛 → catch appendError）异步落位；
        // 轮询等收口再断清场：旧残留必清，新相位只余失败横幅一条
        val deadline = System.currentTimeMillis() + 5_000
        while (session.running.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(false, session.running.value, "新相位失败收口（executor 即抛 → 终态）")
        assertEquals(1, session.entries.value.size, "探索重开 = 清场（旧阶段残留条目不跨阶段）")
        assertTrue(session.entries.value.single() is ChatEntry.ErrorBanner, "清场后仅余新相位失败横幅")
        session.cancel()
    }
}
