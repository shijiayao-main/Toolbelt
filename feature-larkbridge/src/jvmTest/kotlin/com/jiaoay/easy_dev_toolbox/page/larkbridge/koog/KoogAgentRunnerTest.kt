// KoogAgentRunner 守护：进度接线与出站工具面。
//
// 契约（见 KoogAgentRunner 类头「纪律」节）：
// 1. 🔴 progress 经 5 参 run 参数链贯穿（不再读 request.opaque——此前恒 null，事件全丢）：
//    handleEvents 的 LLM/工具事件喂 ProgressAccumulator（喂事件即 touch 保活看门狗）；
// 2. markStatus 三态（成功 DONE / 超时 TIMEOUT / 异常 FAILED）；
// 3. onLLMCallCompleted 空文本不发事件（防窗口垃圾）、有文本发 AssistantText；
// 4. 5 参 run 收尾补条带去重护栏（entryCount==0 才补）；
// 5. isAvailable 读 endpointRef 快照——refreshEndpoint 后翻真（免重启激活，LarkBridgeManager
//    1Hz 刷新回路的生产侧锚点）；
// 6. 执行策略 = larkDispatchStrategy（自建，KoogDispatchStrategy.kt 文件级）：混合响应
//    （Text + Tool.Call 同体）工具优先，不被 nodeSendToolResult 的文本边截胡静默丢弃
//    （singleRunStrategy 会丢——2026-09-14 线上「分析结论卡与报告文件没送回」直接根因）。
//
// 测试缝说明：executor/model 经 KoogAgentRunner.executorFactory（internal 同模块缝，对齐
// AiSettingsViewModel.probeExecutorFactory 先例）注入 fake——LlmClientFactory.executorProvider
// 是 :core 的 internal，本模块测试不可见。
package com.jiaoay.easy_dev_toolbox.page.larkbridge.koog

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.ResolvedModel
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import ai.koog.agents.core.tools.ToolDescriptor
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.KoogAgentSafetyPreset
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.lark.bridge.agent.AgentRequest
import com.jiaoay.lark.bridge.agent.ProgressAccumulator
import com.jiaoay.lark.bridge.agent.ProgressEvent
import com.jiaoay.lark.bridge.agent.RunStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class KoogAgentRunnerTest {

    // ── LlmConfigStore 隔离（AiSettingsViewModelTest 同款法：临时 user.home + 换 store） ──

    // 🔴 JUnit 4 每个测试方法新建类实例，不能用构造期 error() 初始化——lateinit + setUp 先行保证
    private lateinit var originalStore: LocalDataManager

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("koog-runner-test")

    @BeforeTest
    fun setUpIsolatedStore() {
        homeIsolation.redirect()
        originalStore = LlmConfigStore.store
        LlmConfigStore.store = LocalDataManager(dataStoreName = "test_koog_${java.util.UUID.randomUUID()}")
        // 🔴 Database 进程单例重置（2026-09-26 P4 测试批修复既有跨用例隔离缺陷）：本类每用例
        //    轮换临时 user.home 并在 tearDown 删除——若先于本用例的任一用例已建 Database 单例
        //    （首个触达 fileContext!=null 分支的用例建库于当时的 tempHome），后续用例经
        //    BridgeLogTools.buildRegistry → KnowledgeTools.hasContent() → Database.database()
        //    重开已删库的连接即 SQLITE_CANTOPEN（基线 c89f0b6 亦红，非 P4 引入）。
        //    每用例前置重置 ⇒ 单例在新 tempHome 下重建。
        com.jiaoay.easy_dev_toolbox.core.data.db.Database.resetForTest()
    }

    @AfterTest
    fun tearDownStore() {
        LlmConfigStore.store = originalStore
        homeIsolation.restore()
    }

    /**
     * fake executor 基座：挂真 OpenAI client（provider 匹配让 resolveModel 放行），但 execute 被子类
     * 重写、永不发网络请求。
     * 🔴 Koog 1.2.0 AIAgent 实际调 **ResolvedModel 重载**（ContextualPromptExecutor.execute →
     * `executor.execute(prompt, resolvedModel, tools)`，javap 堆栈实证）——只重写 LLModel 重载会被
     * 基类默认实现 `execute(prompt, resolveModel(...), tools)` 绕开、落进真 client 发请求。
     */
    private open class FakeExecutor : MultiLLMPromptExecutor(
        ai.koog.prompt.executor.clients.openai.OpenAILLMClient(
            apiKey = "sk-test",
            settings = ai.koog.prompt.executor.clients.openai.OpenAIClientSettings(
                baseUrl = "http://localhost:1", // 不可达端点——execute 被重写，永不发请求
                timeoutConfig = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(requestTimeoutMillis = 1_000),
            ),
        ),
    ) {
        override suspend fun execute(
            prompt: Prompt,
            model: ResolvedModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = fakeExecute(prompt, model.effectiveModel, tools)

        /** 子类提供的假响应（[tools] 供工具轮 fake 判断注册表内容）。 */
        protected open suspend fun fakeExecute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = Message.Assistant(
            content = "ok",
            metaInfo = ResponseMetaInfo.create(KoogClock.System),
        )
    }

    /** fake：恒返回固定文本（1.2.0 content 便利构造）。 */
    private class OkExecutor(private val reply: String) : FakeExecutor() {
        override suspend fun fakeExecute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = Message.Assistant(
            content = reply,
            metaInfo = ResponseMetaInfo.create(KoogClock.System),
        )
    }

    /** 挂起型 fake：驱动超时路径（总超时用）。 */
    private class HangingExecutor : FakeExecutor() {
        override suspend fun fakeExecute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            kotlinx.coroutines.delay(60_000)
            return Message.Assistant(content = "never", metaInfo = ResponseMetaInfo.create(KoogClock.System))
        }
    }

    /**
     * 永不收尾的工具循环 fake（2026-09-15 maxIterations 耗尽事故回归锚）：每轮恒发
     * `reply_direct` 工具调用——驱动 Koog loop 节点计数触顶，产出
     * [ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException]，
     * 专项 catch 应归类为 `agent_max_iterations`（而非泛化 task_error）。
     * 🔴 端到端用低上限（maxIterations=8）驱动：生产默认 60 的话每轮 2 节点要发 30 次工具调用，太慢。
     */
    private class EndlessToolCallExecutor : FakeExecutor() {
        private var seq = 0
        override suspend fun fakeExecute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = Message.Assistant(
            parts = listOf(
                MessagePart.Tool.Call(
                    id = "tc_${seq++}",
                    tool = "reply_direct",
                    args = """{"text":"第 $seq 轮"}""",
                ),
            ),
            metaInfo = ResponseMetaInfo.create(KoogClock.System),
        )
    }

    /** executorFactory 缝的 fake 形态：工厂恒返回注入的 executor + 生产同款 model。 */
    private fun fakeExecutorFactory(executor: MultiLLMPromptExecutor): (com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig) -> Pair<MultiLLMPromptExecutor, LLModel> =
        { executor to com.jiaoay.easy_dev_toolbox.core.llm.LlmClientFactory.model("fake-model") }

    private fun request(
        prompt: String = "你好",
        opaque: Any? = null,
        persona: String = "",
        systemPrompt: String = "",
    ) = AgentRequest(
        sessionKey = "sess-test",
        prompt = prompt,
        totalTimeoutMillis = 30_000,
        idleTimeoutMillis = 120_000,
        opaque = opaque,
        persona = persona,
        systemPrompt = systemPrompt,
    )

    // ── isAvailable 快照语义（任务 B 生产侧锚点） ──

    @Test
    fun `端点未配-isAvailable false 且 run 返回 agent_config_invalid`() = runBlocking {
        val runner = KoogAgentRunner()
        assertFalse(runner.isAvailable, "未配端点 ⇒ isAvailable false（三消费点全闸）")
        val result = runner.run(request()) { }
        assertEquals("agent_config_invalid", result.errorCode, "run 前置校验应拒跑并给明确错误码")
    }

    @Test
    fun `run 后才配端点-refreshEndpoint 后 isAvailable 翻真（免重启激活调度）`() = runBlocking {
        val runner = KoogAgentRunner()
        assertFalse(runner.isAvailable, "初始未配 ⇒ false")
        // 用户在 AI 设置页配好端点（DataStore）——刷新回路（生产 1Hz / 此处手动）翻真快照
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        runner.refreshEndpoint()
        assertTrue(runner.isAvailable, "refreshEndpoint 后翻真 ⇒ 判定闭包/前缀短路即时激活（免重启）")
    }

    // ── onEndpointReadyChanged 发射锚（就绪态在全类的唯一发射口，直测） ──

    @Test
    fun `refreshEndpoint 经 onEndpointReadyChanged 同步发射就绪态`() = runBlocking {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val runner = KoogAgentRunner(
            onEndpointReadyChanged = { seen.add(it) },
        )
        runner.refreshEndpoint()
        assertEquals(listOf(false), seen, "未配端点 ⇒ 快照写入后同步发射 false")
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        runner.refreshEndpoint()
        assertEquals(
            listOf(false, true),
            seen,
            "配好端点后刷新 ⇒ 同步发射 true（快照单写点 = 就绪态单发射点，与 isAvailable 同源同拍）",
        )
        // 撤配同样发射（双向——订阅方不会卡在旧就绪态）
        LlmConfigStore.setApiKey("")
        runner.refreshEndpoint()
        assertEquals(listOf(false, true, false), seen, "撤配端点 ⇒ 发射 false（双向发射）")
    }

    // ── 进度事件链（任务 C） ──

    @Test
    fun `LLM 回复喂进 accumulator 且收尾 markStatus DONE`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(OkExecutor("你好，我是调度助手")),
        )
        runner.refreshEndpoint()
        val accumulator = ProgressAccumulator()
        val result = runner.run(request(), chatId = "oc_1", senderOpenId = "ou_1", progress = accumulator) { }
        assertTrue(result.isOk, "fake executor 路径应成功：${result.summary}")
        // 事件链：LLM 完成 → AssistantText（非空文本）；收尾 markStatus DONE
        assertTrue(
            accumulator.snapshot().events.any { it is ProgressEvent.AssistantText },
            "LLM 完成事件应喂进窗口：${accumulator.snapshot().events}",
        )
        assertEquals(RunStatus.DONE, accumulator.snapshot().status, "成功收尾应 markStatus DONE")
    }

    @Test
    fun `超时路径 markStatus TIMEOUT`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(HangingExecutor()),
        )
        runner.refreshEndpoint()
        val accumulator = ProgressAccumulator()
        val result = runner.run(
            request().let { it.copy(totalTimeoutMillis = 200) },
            chatId = "oc_1", senderOpenId = "ou_1", progress = accumulator,
        ) { }
        assertEquals("agent_timeout", result.errorCode, "挂起 executor ⇒ 总超时")
        assertEquals(RunStatus.TIMEOUT, accumulator.snapshot().status, "超时收尾应 markStatus TIMEOUT")
    }

    // ── maxIterations 触顶（2026-09-15 事故回归锚：专项 catch + 独立 errorCode） ──

    @Test
    fun `迭代上限触顶-专项归类 agent_max_iterations 而非泛化 task_error`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        val runner = KoogAgentRunner(
            // 低上限端到端驱动（生产默认 60；10 节点 ≈ 4~5 工具轮，几轮即触顶）
            maxIterationsProvider = { 10 },
            executorFactory = fakeExecutorFactory(EndlessToolCallExecutor()),
        )
        runner.refreshEndpoint()
        val accumulator = ProgressAccumulator()
        val result = runner.run(
            request(),
            chatId = "oc_1", senderOpenId = "ou_1", progress = accumulator,
        ) { }
        // 专项 catch 归类（此前落泛化 task_error，用户收到英文原文不可操作）
        assertEquals("agent_max_iterations", result.errorCode, "触顶应专项归类 agent_max_iterations")
        assertEquals(-1, result.exitCode, "失败归一 exitCode=-1（fail() 既有语义）")
        assertEquals(RunStatus.FAILED, accumulator.snapshot().status, "触顶收尾应 markStatus FAILED")
        assertTrue("步数预算" in result.summary, "用户文案应是中文可操作（含步数预算字样）：${result.summary}")
    }

    // ── 步数预算配置化（2026-09-16 配置化批：provider run 前现读 ⇒ HOT 生效） ──

    @Test
    fun `步数预算 provider 每次运行前现读-HOT 改值下一次运行生效`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        var budget = 60 // 模拟设置页 agent.maxIterations
        val runner = KoogAgentRunner(
            maxIterationsProvider = { budget },
            executorFactory = fakeExecutorFactory(EndlessToolCallExecutor()),
        )
        runner.refreshEndpoint()
        // run1：预算钳到下限 60→无限工具轮假体也会触顶——先验证「run 前现读」语义：
        // 设置页把预算改成 10（HOT），下一次 run 直接按 10 触顶（60 时 Endless fake 要跑
        // ~30 轮工具太慢，此处只验证改值即时生效的读侧语义）。
        budget = 10
        val result = runner.run(request()) { }
        assertEquals("agent_max_iterations", result.errorCode, "改值后下一次 run 应按新预算 10 触顶")
        // provider 值低于下限时防御钳制到 MIN（不因误配 0/负数而一轮都跑不完）
        budget = 0
        val result2 = runner.run(request()) { }
        assertEquals("agent_max_iterations", result2.errorCode, "钳制到 MIN(10) 后仍应触顶（而非别的码）")
    }

    @Test
    fun `2 参入口 progress 为 null 不 NPE 且事件链 no-op`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(OkExecutor("简答")),
        )
        runner.refreshEndpoint()
        val result = runner.run(request()) { }
        assertTrue(result.isOk, "2 参入口（无进度面）应正常收尾：${result.summary}")
    }

    // ── 提示词组装（性格模式/安全约束批，直测 buildKoogSystemPrompt 纯函数） ──

    @Test
    fun `默认组装-HEAD 工具与交付约定齐全且安全段位于末尾`() {
        val prompt = buildKoogSystemPrompt(
            request = request(),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = KoogAgentSafetyPreset.DEFAULT.prompt,
        )
        assertTrue("本地调度助手" in prompt, "HEAD 调度分工段必须在")
        assertTrue("可用工具" in prompt && "reply_direct" in prompt, "工具清单段必须在")
        assertTrue("交付约定" in prompt, "交付约定段必须在")
        // 2026-09-14 提示词收紧（[产物] 双通道防重）：send_file 为主通道、[产物] 标记仅兜底
        assertTrue("不要双通道重复交付" in prompt, "[产物] 兜底文案必须含防重复交付约束")
        // 安全段在全文末尾（对齐 CLI safetyPromptSuffix 位置语义）且含原安全底线句
        assertTrue(prompt.trimEnd().endsWith("不要自作主张执行。"), "安全段应位于提示词最末")
        assertTrue("不要伪造工具结果" in prompt, "默认安全档含原安全底线句")
    }

    @Test
    fun `send_file 放宽文案单列且读类工具未放宽（2026-09-18 缺陷修）`() {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val prompt = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            sandboxAvailable = true,
        )
        // 🔴 围栏句必须只覆盖读类工具（不得含 send_file——否则与紧随其后的放宽句互斥）；
        // 读类面为二十件（9 存量 + 7 扩展件 + 4 侦察件）
        assertTrue(
            "以上二十件读类工具只能操作**用户引用发送的文件、其下载目录及你的工具产物**" in prompt,
            "围栏句须限定为读类工具（不得把 send_file 并进去）",
        )
        // 扩展件与侦察件与存量件同列（能力面一致——提示词/注册表/systemPrompt 三处同批）
        assertTrue("read_file(file_path, offset, limit)" in prompt, "read_file 分页工具须在清单")
        assertTrue("grep(pattern, path)" in prompt, "grep 须在清单")
        assertTrue("大文件分析用它翻页" in prompt, "分页指引须在（防 preview_text 反复试）")
        assertTrue("log.stats(file_path)" in prompt, "侦察件 log.stats 须在清单")
        assertTrue("log.diff(old_file_path, new_file_path, normalize_timestamps)" in prompt, "侦察件 log.diff 须在清单")
        assertTrue("log.view(file_path, start_line, window_lines)" in prompt, "侦察件 log.view 须在清单")
        assertTrue("log.cluster(file_path, levels, limit)" in prompt, "侦察件 log.cluster 须在清单")
        assertTrue("filter_by_range(file_path, start_time, end_time)" in prompt, "区间截取件 filter_by_range 须在清单")
        assertTrue("write_todos" !in prompt, "write_todos 不得出现在飞书侧清单（无投影面）")
        // send_file 单列其更宽边界
        assertTrue("send_file 的边界（比读类工具宽）" in prompt, "send_file 放宽必须单列")
        assertTrue(
            "位于工作区内的文件" in prompt && "含你的工作区工具产出的报告" in prompt,
            "须点明工作区放宽与工作区工具产物",
        )
        // 交付约定须指引「工作区产物已登记进围栏，直接发送即可」，同时保留防双通道重复
        assertTrue("你的工作区工具产出已登记进围栏，直接发送即可" in prompt, "须指点 LLM 直接发送已登记产物")
        assertTrue("不要双通道重复交付" in prompt, "防重复交付约束不得被删")
    }

    @Test
    fun `侦察四件引导行随 listed 门控且计数句与列出行数一致`() {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        // 全量注册（registeredToolNames 空 = 不过滤）：文件处理工具段三件引导行整段在位
        val full = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            sandboxAvailable = true,
        )
        assertTrue("先看分布再定过滤策略" in full, "log.stats 须带「先统计再定过滤」动线引导")
        assertTrue("先对比找行为差异再下钻差异窗口" in full, "log.diff 须带「对比后下钻」动线引导")
        assertTrue("定位到区域后用它引用原文" in full, "log.view 须带「引用原文」动线引导")
        // 🔴 计数句一致性（防清单漂移）：围栏句宣称的件数 == 文件处理工具段实际列出的行数
        val section = full.substringAfter("文件处理工具").substringBefore("文件围栏")
        val listedLines = section.lineSequence().count { it.startsWith("- ") }
        assertEquals(20, listedLines, "文件处理工具段列出行数与围栏句「二十件」必须一致（增删行须同步计数句）")
        assertTrue("以上二十件读类工具" in full, "围栏句计数与实际清单同步")
        // 门控面：注册集不含四件时引导行整行缺席（listed() 单源派生——不列没注册的工具）
        val partial = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = setOf("reply_direct"),
        )
        listOf("log.stats", "log.diff", "log.view", "log.cluster", "filter_by_range", "web.fetch").forEach { name ->
            assertFalse(name in partial, "注册集不含 $name 时引导行必须缺席（能力面与注册表一致）")
        }
    }

    @Test
    fun `sandbox 不可用时提示词不得宣称可发工作区文件（防反向诱导试错）`() {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val prompt = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            sandboxAvailable = false, // 未配工作区 ⇒ 工具实际会拒
        )
        assertTrue("本机未配置工作区，工作区放宽不生效" in prompt, "sandbox 不可用时须如实告知（防 LLM 反复试错撞 maxIterations）")
        assertFalse("send_file 的边界（比读类工具宽）" in prompt, "不得宣称放宽生效")
    }

    @Test
    fun `总开关关闭时提示词不列 send_file 且交付约定改写（能力面须与 execute 一致）`() {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val prompt = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            sandboxAvailable = true, // 沙箱可用也没用——总开关关着，execute 恒拒
            artifactsEnabled = false,
        )
        // 可用工具清单里不得出现 send_file（列出再靠描述劝退 = 提示词自相矛盾）
        assertFalse(
            "- send_file(file_path, display_name)" in prompt,
            "总开关关闭时不得把恒失败的工具列进可用工具清单",
        )
        assertFalse("send_file 的边界" in prompt, "不得宣称任何 send_file 边界")
        assertFalse("直接发送即可" in prompt, "不得诱导走 send_file")
        // 🔴 断言「诱导句」不存在，而非「字面量不存在」——关闭档的文案本身就含
        //    「不要写 `[产物]` 标记」这句**否定**表述，用 `"[产物]" in prompt` 会命中它而恒假。
        assertFalse("每行一条" in prompt, "不得诱导写 [产物] 标记（该通道同时被跳过，写了是死路）")
        assertTrue("不要调用 send_file" in prompt, "须显式禁止调用（否定式约束）")
        // 必须给出替代路径 + 如实告知用户可以怎么恢复
        assertTrue("任何文件都无法发送" in prompt, "须如实告知能力面")
        assertTrue("send_card" in prompt, "须给出替代交付方式")
        assertTrue("开启「产物回传」" in prompt, "须告诉用户如何恢复")
    }

    @Test
    fun `总开关开启时提示词照旧列 send_file 与交付约定（默认档零变化）`() {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val prompt = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            sandboxAvailable = true,
            artifactsEnabled = true,
        )
        assertTrue("- send_file(file_path, display_name)" in prompt, "默认档必须照旧列出")
        assertTrue("直接发送即可" in prompt, "默认档交付约定逐字不变")
        assertTrue("不要双通道重复交付" in prompt)
        // 新增：敏感表披露句（让 LLM 不必靠试错发现凭据族不可发）
        assertTrue("不放行凭据/密钥类文件" in prompt, "须披露敏感表存在")
    }

    @Test
    fun `性格段在 persona 段之前-段头带全局性格引导语`() {
        val prompt = buildKoogSystemPrompt(
            request = request(persona = "你是日志分析助理小析", systemPrompt = "附加指令正文"),
            knowledgeAvailable = false,
            stylePrompt = "语气温和友好",
            safetyPrompt = null,
        )
        val styleIdx = prompt.indexOf("以下是全局性格模式设定")
        val personaIdx = prompt.indexOf("以下是这个机器人的人设设定")
        assertTrue(styleIdx >= 0, "性格段段头必须在")
        assertTrue(personaIdx > styleIdx, "persona 段在性格段之后（全局底座在前、更具体者胜）")
        assertTrue("语气温和友好" in prompt && "你是日志分析助理小析" in prompt, "两段正文都注入")
        assertTrue("若与全局性格模式冲突，以本段为准" in prompt, "persona 段头声明冲突裁决")
    }

    @Test
    fun `安全段在附加系统指令之后-custom 文本原样注入`() {
        val prompt = buildKoogSystemPrompt(
            request = request(systemPrompt = "优先回答设备问题"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = "[安全约束 - 不可违反]\n- 只许读取",
        )
        val sysIdx = prompt.indexOf("优先回答设备问题")
        val safetyIdx = prompt.indexOf("[安全约束 - 不可违反]")
        assertTrue(sysIdx in 0 until safetyIdx, "安全段必须位于附加系统指令之后（最末）")
    }

    // ── per-user 性格档（2026-09-14 批：styleOverridePrompt 注入契约） ──

    @Test
    fun `per-user 覆盖段替换全局段-段头标注单独配置且 persona 段序不变`() {
        val prompt = buildKoogSystemPrompt(
            request = request(persona = "你是日志分析助理小析"),
            knowledgeAvailable = false,
            stylePrompt = "全局性格正文",
            safetyPrompt = null,
            styleOverridePrompt = "专属性格正文",
        )
        assertEquals(-1, prompt.indexOf("以下是全局性格模式设定"), "override 生效时不得出现全局段头")
        val oIdx = prompt.indexOf("以下是为你单独配置的性格模式设定")
        assertTrue(oIdx >= 0, "override 段头必须在")
        assertTrue("仅在与你的对话中生效" in prompt, "段头必须标注身份边界（猫娘不叫别人主人）")
        assertTrue("专属性格正文" in prompt && "全局性格正文" !in prompt, "override 正文注入且全局正文不出现")
        assertTrue(prompt.indexOf("以下是这个机器人的人设设定") > oIdx, "persona 段仍在 style 段之后（身份压过任何 style）")
    }

    @Test
    fun `run 链路 per-user 映射命中时 system 消息用专属档`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        LlmConfigStore.setAgentStyleId("concise")
        LlmConfigStore.setAgentStyleUserMap(listOf(com.jiaoay.easy_dev_toolbox.core.llm.KoogUserStyleEntry("ou_1", "catgirl")))
        val captured = SystemPromptCapturingExecutor()
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(captured),
        )
        runner.refreshEndpoint()
        runner.run(request(), chatId = "oc_1", senderOpenId = "ou_1", progress = null) { }
        val sys = captured.lastPrompt?.messages?.firstOrNull { it.role == Message.Role.System }?.textContent().orEmpty()
        assertTrue("猫娘" in sys, "ou_1 应命中 per-user 猫娘档")
        assertTrue("最短可用答案" !in sys, "全局 concise 档正文不得出现（被 override 替换）：${sys.take(80)}")
    }

    @Test
    fun `run 链路未命中映射的 sender 走全局档`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        LlmConfigStore.setAgentStyleId("concise")
        LlmConfigStore.setAgentStyleUserMap(listOf(com.jiaoay.easy_dev_toolbox.core.llm.KoogUserStyleEntry("ou_1", "catgirl")))
        val captured = SystemPromptCapturingExecutor()
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(captured),
        )
        runner.refreshEndpoint()
        runner.run(request(), chatId = "oc_1", senderOpenId = "ou_other", progress = null) { }
        val sys = captured.lastPrompt?.messages?.firstOrNull { it.role == Message.Role.System }?.textContent().orEmpty()
        assertTrue("最短可用答案" in sys, "未命中 sender 走全局 concise 档")
        assertTrue("猫娘" !in sys, "per-user 档不得泄漏给未命中的 sender")
    }

    @Test
    fun `映射值为 custom 或空串时防御回落全局档`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        LlmConfigStore.setAgentStyleId("professional")
        // per-user 不支持 custom（自定义是全局档专属）——映射值为 custom 时回落全局
        LlmConfigStore.setAgentStyleUserMap(listOf(com.jiaoay.easy_dev_toolbox.core.llm.KoogUserStyleEntry("ou_c", "custom")))
        val captured = SystemPromptCapturingExecutor()
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(captured),
        )
        runner.refreshEndpoint()
        runner.run(request(), chatId = "oc_1", senderOpenId = "ou_c", progress = null) { }
        val sys = captured.lastPrompt?.messages?.firstOrNull { it.role == Message.Role.System }?.textContent().orEmpty()
        assertTrue("专业严谨" in sys, "custom 映射值须回落全局 professional 档（坏值不炸）")
    }

    @Test
    fun `2 参入口空 senderOpenId 回落全局档`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        LlmConfigStore.setAgentStyleId("tsundere")
        val captured = SystemPromptCapturingExecutor()
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(captured),
        )
        runner.refreshEndpoint()
        runner.run(request()) { } // 2 参入口 senderOpenId = ""
        val sys = captured.lastPrompt?.messages?.firstOrNull { it.role == Message.Role.System }?.textContent().orEmpty()
        assertTrue("傲娇" in sys, "空 sender（2 参入口）走全局档")
    }

    @Test
    fun `custom 档经 LlmConfigStore 刷新后进入 run 的 system 消息`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        LlmConfigStore.setAgentStyleId("custom")
        LlmConfigStore.setAgentStyleCustom("  语气要像老朋友聊天  ")
        val captured = SystemPromptCapturingExecutor()
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(captured),
        )
        runner.refreshEndpoint()
        runner.run(request(), chatId = "oc_1", senderOpenId = "ou_1", progress = null) { }
        val systemMessage = captured.lastPrompt?.messages?.firstOrNull { it.role == Message.Role.System }
        assertTrue(
            systemMessage != null && "语气要像老朋友聊天" in systemMessage.textContent(),
            "自定义性格文本（trim 后）应注入 system 消息：${systemMessage?.textContent()?.take(60)}",
        )
    }

    /** 捕获 execute 收到的完整 prompt（system 消息断言用）。 */
    private class SystemPromptCapturingExecutor : FakeExecutor() {
        var lastPrompt: Prompt? = null
        override suspend fun fakeExecute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            lastPrompt = prompt
            return Message.Assistant(content = "ok", metaInfo = ResponseMetaInfo.create(KoogClock.System))
        }
    }

    // ── resetSession / resetReceipt（「新对话」重置面） ──

    @Test
    fun `resetSession 仅清 Koog 会话历史-历史为空时返回 false`() = runBlocking {
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(SystemPromptCapturingExecutor()),
        )
        assertFalse(
            runner.resetSession("oc_never_used"),
            "历史为空 ⇒ false（回执口径：当前没有可重置的会话）",
        )
    }

    @Test
    fun `resetReceipt 恒 null-Koog 侧无详细回执面`() = runBlocking {
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(SystemPromptCapturingExecutor()),
        )
        assertEquals(null, runner.resetReceipt("oc_receipt"), "Koog 本侧无详细回执面 ⇒ null（既有回执文案逐字不变）")
    }

    // ── 混合响应轮工具不丢弃（larkDispatchStrategy 修复锚，2026-09-14 线上 bug 回归） ──

    /** 记录出站调用的 ReplySink fake（裸形态挂 request.opaque——无引用文件场景生产语义一致）。 */
    private class RecordingReplySink : com.jiaoay.lark.bridge.pipeline.ReplySink {
        val sentFiles = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sentCards = java.util.concurrent.CopyOnWriteArrayList<String>()

        /** 卡规格全量记录（title 之外的 markdown 断言用——sys.* 动作说明卡的全文载体断言） */
        val sentCardSpecs = java.util.concurrent.CopyOnWriteArrayList<com.jiaoay.lark.gateway.CardSpec>()
        val sentTexts = java.util.concurrent.CopyOnWriteArrayList<String>()

        // P4 会话作用域三件的记录面（不覆盖的用例不触达，既有断言零影响）
        val reactions = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, Boolean>>()
        val updatedCards = java.util.concurrent.CopyOnWriteArrayList<Pair<String, com.jiaoay.lark.gateway.CardSpec>>()

        // 批 B L1 三件的记录面（能力一级化 2026-09-27）
        val sentImages = java.util.concurrent.CopyOnWriteArrayList<Path>()
        val threadReplies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recalls = java.util.concurrent.CopyOnWriteArrayList<String>()

        override val target: com.jiaoay.lark.gateway.LarkTarget = com.jiaoay.lark.gateway.LarkTarget.Chat("oc_test")
        override val chatId: String? = "oc_test"

        override suspend fun text(body: String): com.jiaoay.lark.gateway.SendResult {
            sentTexts += body
            return com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m_text")
        }

        override suspend fun markdown(body: String): com.jiaoay.lark.gateway.SendResult =
            com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m_md")

        override suspend fun card(spec: com.jiaoay.lark.gateway.CardSpec): com.jiaoay.lark.gateway.SendResult {
            sentCards += spec.title
            sentCardSpecs += spec
            return com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m_card")
        }

        override suspend fun file(file: Path, displayName: String?): com.jiaoay.lark.gateway.SendResult {
            sentFiles += file.toString()
            return com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m_file")
        }

        override suspend fun image(source: Path): com.jiaoay.lark.gateway.SendResult {
            // 🔴 显式 add：Path 有 plus 运算符，`+=` 会解析成 List 拼接而非集合添加
            sentImages.add(source)
            return com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m_image")
        }

        override suspend fun replyInThread(body: String): com.jiaoay.lark.gateway.SendResult {
            threadReplies += body
            return com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m_thread")
        }

        override suspend fun recallMessage(messageId: String): Boolean {
            recalls += messageId
            return true
        }

        override fun stream(initial: com.jiaoay.lark.gateway.CardSpec): com.jiaoay.lark.gateway.CardStreamSink =
            throw UnsupportedOperationException("测试不覆盖流式卡")

        override suspend fun react(messageId: String, emojiKey: String, add: Boolean): Boolean {
            reactions += Triple(messageId, emojiKey, add)
            return true
        }

        override suspend fun updateExistingCard(
            messageId: String,
            spec: com.jiaoay.lark.gateway.CardSpec,
        ): com.jiaoay.lark.gateway.SendResult {
            updatedCards += messageId to spec
            return com.jiaoay.lark.gateway.SendResult(ok = true, messageId = messageId)
        }
    }

    /**
     * 三轮 fake（真机被丢形态的完整链）：send_card → **混合响应**（文本 + send_file 同体，
     * 正是线上被 singleRunStrategy 文本边截胡的形态）→ 终收尾文本。
     */
    private class MixedResponseExecutor(private val filePath: String) : FakeExecutor() {
        private var round = 0
        override suspend fun fakeExecute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            round++
            return when (round) {
                1 -> Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(
                            id = "tc_card",
                            tool = "send_card",
                            args = """{"title":"开工","markdown":"开始分析"}""",
                        ),
                    ),
                    metaInfo = ResponseMetaInfo.create(KoogClock.System),
                )
                // 🔴 round2 混合响应：Text + Tool.Call 同体——修复前文本边先命中 ⇒ send_file 静默丢弃
                2 -> Message.Assistant(
                    parts = listOf(
                        MessagePart.Text("分析完成，我把报告发给你。"),
                        MessagePart.Tool.Call(id = "tc_file", tool = "send_file", args = """{"file_path":"$filePath"}"""),
                    ),
                    metaInfo = ResponseMetaInfo.create(KoogClock.System),
                )
                else -> Message.Assistant(content = "报告已发送，任务完成。", metaInfo = ResponseMetaInfo.create(KoogClock.System))
            }
        }
    }

    @Test
    fun `混合响应轮工具不丢弃-send_file 执行且终轮文本为最终回复`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        // send_file 前置校验 file.isFile——路径须指向真实存在的临时文件（不存在只返回错误文案不调 reply.file）
        val reportFile = java.io.File.createTempFile("koog-report", ".md")
        reportFile.deleteOnExit()
        reportFile.writeText("# 分析报告\n车头泊入 TTS 未播报结论")

        val replySink = RecordingReplySink()
        val runner = KoogAgentRunner(
            // P2 纯聊天档收窄（评审修正 2）：裸 ReplySink 也须过工作区沙箱——临时文件圈进
            // 独立沙箱，被测点仍是「混合响应的工具边不被文本边截胡」（围栏不是被测面）
            sandboxProvider = { sandboxOf(reportFile.parentFile) },
            executorFactory = fakeExecutorFactory(MixedResponseExecutor(reportFile.absolutePath)),
        )
        runner.refreshEndpoint()
        val accumulator = ProgressAccumulator()
        val mixedRequest = request("深分析").let { it.copy(opaque = replySink) }
        val result = runner.run(mixedRequest, chatId = "oc_mix", senderOpenId = "ou_mix", progress = accumulator) { }

        // ① 最直接执行证据：send_file 真的走到出站（修复前文本边截胡 ⇒ sentFiles 恒空）
        assertTrue(replySink.sentFiles.isNotEmpty(), "混合响应里的 send_file 必须被执行（不因文本边截胡丢弃）")
        assertEquals(reportFile.absolutePath, replySink.sentFiles.single())
        // ② 第一轮工具确实执行（send_card 出站）
        assertTrue(result.isOk, "run 应正常收尾：${result.summary}")
        assertEquals(listOf("开工"), replySink.sentCards, "第一轮 send_card 应真的出站")
        // ③ 最终回复 = round3 终文本（round2 混合响应的过渡文本不出现在最终回复）
        assertEquals("报告已发送，任务完成。", result.summary)
        // ④ 进度事件链：send_file 工具进窗 + 成功收尾
        assertTrue(
            accumulator.snapshot().events.any { it is ProgressEvent.ToolUse && it.name == "send_file" },
            "send_file 工具调用应喂进进度窗：${accumulator.snapshot().events}",
        )
        assertEquals(RunStatus.DONE, accumulator.snapshot().status, "成功收尾应 markStatus DONE")
    }

    // ==================== 出站工具的 opaque 载体解析（2026-09-15 真机修复锚） ====================

    /**
     * 🔴 真机取证（2026-09-15 17:55）：有引用文件的消息 opaque 是 DispatchPayload（包装器），
     * 三工具裸 `as? ReplySink` 恒 null ⇒ 全部 DENIED——agent 真机自救说「卡片和文件通道刚才都不通」。
     * 修复后经 [BridgeToolRegistry.replySinkOf] 剥壳。本组用例钉「DispatchPayload 形态下三工具真的发得出去」。
     */
    @Test
    fun `🔴 DispatchPayload 载体形态下三出站工具都能发出去（包装器不再遮蔽 ReplySink）`() = runBlocking {
        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(File("/tmp/fake-referenced.log")),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val req = request(prompt = "分析", opaque = payload)

        // reply_direct
        val direct = ReplyDirectTool(req)
        assertEquals("已发送给用户（message_id=m_text）。若需要撤回可用 recall_own_message。若任务已完成，后续不要再调用任何工具，直接结束并给出简短收尾。", direct.execute(ReplyDirectTool.Args("结论")))
        assertEquals(listOf("结论"), replySink.sentTexts, "reply_direct 应真的出站")

        // send_card
        val card = SendCardTool(req)
        assertEquals("卡片已发送（message_id=m_card）", card.execute(SendCardTool.Args(title = "报告", markdown = "## 内容")))
        assertEquals(listOf("报告"), replySink.sentCards, "send_card 应真的出站")

        // send_file（围栏内路径——造一个临时文件作为允许集成员）
        val allowed = File.createTempFile("koog-allowed", ".md").apply { writeText("报告内容"); deleteOnExit() }
        val payloadWithFile = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(allowed),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val reqFile = request(prompt = "分析", opaque = payloadWithFile)
        val sendFile = SendFileTool(
            reqFile,
            com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(listOf(allowed)),
        )
        assertEquals("文件已发送：${allowed.name}", sendFile.execute(SendFileTool.Args(allowed.absolutePath)))
        assertEquals(1, replySink.sentFiles.size, "send_file 应真的出站")
        assertEquals(allowed.absolutePath, replySink.sentFiles.single())
    }

    @Test
    fun `裸 ReplySink 形态回归（纯聊天消息零变化）`() = runBlocking {
        val replySink = RecordingReplySink()
        val req = request(prompt = "闲聊", opaque = replySink)
        val direct = ReplyDirectTool(req)
        assertEquals(
            "已发送给用户（message_id=m_text）。若需要撤回可用 recall_own_message。若任务已完成，后续不要再调用任何工具，直接结束并给出简短收尾。",
            direct.execute(ReplyDirectTool.Args("你好呀")),
        )
        assertEquals(listOf("你好呀"), replySink.sentTexts)
    }

    @Test
    fun `opaque 为 null 或未知形态返回 DENIED_HINT（防御面不放宽）`() = runBlocking {
        val denied = request(prompt = "x", opaque = null).let { ReplyDirectTool(it).execute(ReplyDirectTool.Args("hi")) }
        assertEquals(ReplyDirectTool.DENIED_HINT, denied, "null opaque 仍拒绝（不放宽）")
        val unknown = request(prompt = "x", opaque = "not-a-sink").let { ReplyDirectTool(it).execute(ReplyDirectTool.Args("hi")) }
        assertEquals(ReplyDirectTool.DENIED_HINT, unknown, "未知形态仍拒绝（不放宽）")
    }

    // ── 交付沙箱并集（2026-09-18 缺陷修：委托 CLI 产物回传） ──
    //
    // 缺陷：send_file 的判据原为「精确允许集」——CLI 子进程产出落在 cwd（∈ 工作区）但
    // 从不进允许集（AgentRunResult 无结构化产物字段），故「复杂任务归 CLI」时产物恒被拒。
    // 修复：判据放宽为「允许集 ∪ 工作区沙箱」（与 `[产物]` 通道同一个 WorkspacePolicy）。

    /** 造一个独立的工作区目录（每用例一份，deleteOnExit 兜底）。 */
    private fun workspaceDir(): File =
        File(System.getProperty("java.io.tmpdir"), "koog-ws-${System.nanoTime()}").apply {
            mkdirs(); deleteOnExit()
        }

    private fun sandboxOf(root: File): com.jiaoay.lark.bridge.exec.WorkspacePolicy =
        com.jiaoay.lark.bridge.exec.WorkspacePolicy.fromConfig(
            workspaceDir = root.absolutePath,
            workspaceWhitelist = emptyList(),
        )

    @Test
    fun `沙箱内的 CLI 产物可被 send_file 发送（放宽锚）`() = runBlocking {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("用户引用") }
        // CLI 委托产出：在工作区内，但**不在** allowedFiles（模拟真实链路——CLI 从不登记产物）
        val cliReport = File(ws, "cli-report.md").apply { writeText("# CLI 产出") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val req = request(prompt = "深分析", opaque = payload)
        val sendFile = SendFileTool(
            req,
            com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(listOf(referenced)),
            sandboxOf(ws),
        )

        assertEquals(
            "文件已发送：${cliReport.name}",
            sendFile.execute(SendFileTool.Args(cliReport.absolutePath)),
            "工作区沙箱内的 CLI 产物应可回传（修复前返回『路径不在本消息的文件围栏内』）",
        )
        assertEquals(listOf(cliReport.absolutePath), replySink.sentFiles, "应真的出站")
    }

    @Test
    fun `沙箱外且围栏外路径仍被拒（不放宽到无限）`() = runBlocking {
        val ws = workspaceDir()
        val outsideDir = Files.createTempDirectory("koog-outside").toFile().apply { deleteOnExit() }
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val outside = File(outsideDir, "secret.md").apply { writeText("工作区之外") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val sendFile = SendFileTool(
            request(prompt = "x", opaque = payload),
            com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(listOf(referenced)),
            sandboxOf(ws),
        )

        val result = sendFile.execute(SendFileTool.Args(outside.absolutePath))
        assertTrue(result.contains("不可交付"), "沙箱外路径必须仍被拒：$result")
        assertEquals(0, replySink.sentFiles.size, "被拒路径不得出站")
    }

    @Test
    fun `围栏外路径的存在性不泄漏（三种路径回同一句，防当探测器用）`() = runBlocking {
        // 🔴 原判据顺序是 isFile → 敏感 → 围栏，三段文案可区分 ⇒ LLM 能把 send_file 当探测器，
        //    枚举围栏外任意路径是否存在。前移围栏判定后统一回 OPAQUE 文案。
        val ws = workspaceDir()
        val outsideDir = Files.createTempDirectory("koog-oracle").toFile().apply { deleteOnExit() }
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        // 三种围栏外情形：存在的普通文件 / 存在的敏感文件 / 不存在的路径
        val existing = File(outsideDir, "plain.md").apply { writeText("x") }
        val existingSensitive = File(outsideDir, "id_rsa").apply { writeText("KEY") }
        val missing = File(outsideDir, "does-not-exist.md")

        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val sendFile = SendFileTool(
            request(prompt = "x", opaque = payload),
            com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(listOf(referenced)),
            sandboxOf(ws),
        )

        val r1 = sendFile.execute(SendFileTool.Args(existing.absolutePath))
        val r2 = sendFile.execute(SendFileTool.Args(existingSensitive.absolutePath))
        val r3 = sendFile.execute(SendFileTool.Args(missing.absolutePath))
        assertEquals(r1, r2, "存在的普通文件与存在的敏感文件必须回同一句（否则可判别其存在性）")
        assertEquals(r1, r3, "不存在的路径也必须回同一句（否则「文件不存在」即暴露存在性）")
    }

    @Test
    fun `未配工作区（sandbox=null）回落精确允许集判据`() = runBlocking {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val other = File(ws, "other.md").apply { writeText("在工作区内但未登记") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val sendFile = SendFileTool(
            request(prompt = "x", opaque = payload),
            com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(listOf(referenced)),
            sandbox = null, // 未配工作区 ⇒ 不设沙箱上界
        )

        val result = sendFile.execute(SendFileTool.Args(other.absolutePath))
        assertTrue(result.contains("不可交付"), "未配工作区时不得放宽（fail-safe 回落精确允许集）：$result")
    }

    @Test
    fun `BridgeToolRegistry 的 build 传入的沙箱真的接线到 send_file（防解析了但没接线）`() = runBlocking {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val cliReport = File(ws, "registry-report.md").apply { writeText("y") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val registry = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "",
                apiKey = "sk-test",
                modelId = "fake-model",
                timeoutSeconds = 60,
            ),
            request = request(prompt = "x", opaque = payload),
            sandbox = sandboxOf(ws),
        )
        val sendFile = registry.tools.filterIsInstance<SendFileTool>().single()
        assertEquals(
            "文件已发送：${cliReport.name}",
            sendFile.execute(SendFileTool.Args(cliReport.absolutePath)),
            "经注册表装配的 send_file 必须拿到同一沙箱（注入链贯通）",
        )
    }

    // ── 纯聊天档收窄（P2 agent-first 2026-09-26，评审修正 2）──────────────────────
    // agent-first 反转后纯聊天是主路径，旧「context==null 零校验」暴露面不再可接受——
    // 收窄为「命中工作区沙箱」放行（无引用文件 ⇒ 无精确允许集，沙箱是唯一放行判据）。

    @Test
    fun `纯聊天（裸 ReplySink）未配沙箱时 send_file 恒拒（fail-safe 收窄）`() = runBlocking {
        val outsideDir = Files.createTempDirectory("koog-chat").toFile().apply { deleteOnExit() }
        val anyFile = File(outsideDir, "anything.md").apply { writeText("z") }

        val replySink = RecordingReplySink()
        // sandbox 默认 null（未配工作区）⇒ insideSandbox 恒 false ⇒ 恒拒
        val sendFile = SendFileTool(request(prompt = "闲聊", opaque = replySink), context = null)

        assertEquals(
            com.jiaoay.lark.bridge.exec.SensitiveFilePolicy.OPAQUE_DENIED_HINT,
            sendFile.execute(SendFileTool.Args(anyFile.absolutePath)),
            "P2 收窄：纯聊天档无沙箱 ⇒ 恒拒（旧零校验语义已废）",
        )
        assertEquals(0, replySink.sentFiles.size, "被拒 ⇒ 不得出站")
    }

    @Test
    fun `纯聊天（裸 ReplySink）沙箱命中时 send_file 放行且工作区外仍拒`() = runBlocking {
        val ws = workspaceDir()
        val inside = File(ws, "chat-note.md").apply { writeText("闲聊产物") }
        val outsideDir = Files.createTempDirectory("koog-chat-out").toFile().apply { deleteOnExit() }
        val outside = File(outsideDir, "secret.md").apply { writeText("敏感") }

        val replySink = RecordingReplySink()
        val sendFile = SendFileTool(
            request(prompt = "闲聊", opaque = replySink),
            context = null,
            sandbox = sandboxOf(ws),
        )

        assertEquals(
            "文件已发送：${inside.name}",
            sendFile.execute(SendFileTool.Args(inside.absolutePath)),
            "沙箱内文件须放行（收窄不误伤正常交付）",
        )
        assertEquals(
            com.jiaoay.lark.bridge.exec.SensitiveFilePolicy.OPAQUE_DENIED_HINT,
            sendFile.execute(SendFileTool.Args(outside.absolutePath)),
            "沙箱外文件须拒（Opaque 提示不含存在性信息）",
        )
        assertEquals(listOf(inside.absolutePath), replySink.sentFiles)
    }

    // ── 注入链端到端锚（2026-09-18 对抗验证补：sandboxProvider 两跳透传） ──
    //
    // 🔴 为什么必须端到端：本仓已归档事故（批 6 assemble→Bridge 漏传 fallbackHandlerProvider
    //    致整条兜底链死线，而单测直注 seam 全绿）形状完全一致——sandboxProvider 全仓测试
    //    0 引用时，删掉 KoogAgentRunner 的 `sandbox = sandbox` 或 LarkBridgeManager 的闭包，
    //    缺陷原样复发而全部测试仍绿。本条用例经**真实 run() 链路**（provider → build → 工具）
    //    断言 fake ReplySink 收到「工作区内、不在 allowedFiles」的文件。

    /** fake：先调 send_file 发工作区内的 CLI 产物，再给收尾文本。 */
    private class SendFileThenFinishExecutor(private val cliArtifactPath: String) : FakeExecutor() {
        private var seq = 0
        override suspend fun fakeExecute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = if (seq++ == 0) {
            Message.Assistant(
                parts = listOf(
                    MessagePart.Tool.Call(
                        id = "tc_send_${seq}",
                        tool = "send_file",
                        args = """{"file_path":"${cliArtifactPath.replace("\\", "\\\\")}"}""",
                    ),
                ),
                metaInfo = ResponseMetaInfo.create(KoogClock.System),
            )
        } else {
            Message.Assistant(content = "已交付", metaInfo = ResponseMetaInfo.create(KoogClock.System))
        }
    }

    @Test
    fun `sandboxProvider 经真实 run 链路生效（CLI 产物可回传的端到端锚）`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")

        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("用户引用") }
        // CLI 委托产出：工作区内、**不在** allowedFiles（CLI 从不登记产物——缺陷真身）
        val cliReport = File(ws, "cli-e2e-report.md").apply { writeText("# 委托产出") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val runner = KoogAgentRunner(
            sandboxProvider = { sandboxOf(ws) }, // 🔴 唯一被测的注入点：provider → run → build → tool
            executorFactory = fakeExecutorFactory(SendFileThenFinishExecutor(cliReport.absolutePath)),
        )
        runner.refreshEndpoint()

        val result = runner.run(request(prompt = "深分析", opaque = payload)) { }

        assertEquals(
            listOf(cliReport.absolutePath),
            replySink.sentFiles,
            "provider 注入的沙箱必须经 run 链路生效——CLI 产物应真的出站（删掉任一跳透传本条即红）",
        )
        assertTrue(result.isOk, "run 应正常收尾：${result.summary}")
    }

    @Test
    fun `sandboxProvider 为 null 时 CLI 产物仍被拒（默认回落锚）`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")

        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val cliReport = File(ws, "should-be-rejected.md").apply { writeText("y") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        // 不传 sandboxProvider（默认 { null }）⇒ 回落精确允许集 ⇒ CLI 产物被拒
        val runner = KoogAgentRunner(
            executorFactory = fakeExecutorFactory(SendFileThenFinishExecutor(cliReport.absolutePath)),
        )
        runner.refreshEndpoint()

        runner.run(request(prompt = "深分析", opaque = payload)) { }

        assertEquals(0, replySink.sentFiles.size, "未配沙箱 ⇒ 不得放宽（fail-safe 回落精确允许集）")
    }

    // ── 两个前置闸（2026-09-18 业主拍板：产物总开关对齐 + 敏感文件排除表） ──

    @Test
    fun `产物回传总开关关闭时 send_file 一律拒（不做旁路）`() = runBlocking {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        // 连**允许集内**的文件也拒（开关语义是「不许任何文件出站」，不复用路径判据）
        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val sendFile = SendFileTool(
            request(prompt = "x", opaque = payload),
            com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(listOf(referenced)),
            sandboxOf(ws),
            artifactsEnabled = false, // 🔴 用户关掉了桥接的「产物回传」开关
        )

        val result = sendFile.execute(SendFileTool.Args(referenced.absolutePath))
        assertTrue(result.contains("产物回传已关闭"), "总开关关闭时不得成为旁路：$result")
        assertEquals(0, replySink.sentFiles.size, "零出站")
    }

    @Test
    fun `敏感文件（凭据族）即使在工作区内也被拒`() = runBlocking {
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val secret = File(ws, "id_rsa").apply { writeText("PRIVATE KEY") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val sendFile = SendFileTool(
            request(prompt = "x", opaque = payload),
            com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(listOf(referenced)),
            sandboxOf(ws),
        )

        val result = sendFile.execute(SendFileTool.Args(secret.absolutePath))
        assertTrue(result.contains("敏感"), "凭据族文件必须被拒（防注入诱导外发）：$result")
        assertEquals(0, replySink.sentFiles.size, "零出站")
    }

    @Test
    fun `桥接数据根内的文件被拒（明文 appSecret 防泄漏）`() = runBlocking {
        val ws = workspaceDir()
        val dataRoot = File(ws, "lark-data").apply { mkdirs(); deleteOnExit() }
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val bridgeConfig = File(dataRoot, "bridge-config.json").apply { writeText("""{"appSecret":"xxx"}""") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val sendFile = SendFileTool(
            request(prompt = "x", opaque = payload),
            com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(listOf(referenced)),
            sandboxOf(ws), // 数据根恰在工作区内（用户把工作区指向家目录的场景）
            homeDataRoot = dataRoot,
        )

        val result = sendFile.execute(SendFileTool.Args(bridgeConfig.absolutePath))
        assertTrue(result.contains("敏感"), "桥接配置文档必须被拒：$result")
        assertEquals(0, replySink.sentFiles.size, "零出站")
    }

    @Test
    fun `产物回传总开关经真实 run 链路生效（前置闸端到端锚）`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")

        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        // 沙箱内、允许集外、非敏感——**只有**总开关能拦住它
        // （首版误用 e2e-report.md 但并不存在该文件 ⇒ 被 isFile 先拦，断言恒真、闸门删掉也绿）。
        val cliReport = File(ws, "e2e-report.md").apply { writeText("y") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val runner = KoogAgentRunner(
            sandboxProvider = { sandboxOf(ws) },
            artifactsEnabledProvider = { false }, // 🔴 经 provider → run → build → tool 全链验证
            executorFactory = fakeExecutorFactory(SendFileThenFinishExecutor(cliReport.absolutePath)),
        )
        runner.refreshEndpoint()

        runner.run(request(prompt = "深分析", opaque = payload)) { }

        assertEquals(0, replySink.sentFiles.size, "总开关经 provider 链生效（删掉任一跳透传本条即红）")
    }

    @Test
    fun `敏感判据经真实 run 链路生效（数据根判据端到端锚）`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")

        // 数据根目录**同时是**沙箱根：文件既在沙箱内、又在允许集外——
        // 只有 homeDataRootProvider 那条判据能拦住它（总开关保持默认 true）。
        // 🔴 名字必须不在凭据族名单里（用 bridge-config.json 会被判据 2 独立拦下 ⇒ 断言恒真、
        //    透传链删掉也绿）；audit.jsonl 是数据根内名字无害的典型目标。
        val dataRoot = File(
            System.getProperty("java.io.tmpdir"),
            "koog-e2e-dataroot-${System.nanoTime()}",
        ).apply { mkdirs() }
        val secret = File(dataRoot, "audit.jsonl").apply { writeText("""{"prompt":"内部记录"}""") }
        val referenced = File(dataRoot, "referenced.log").apply { writeText("x") }

        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val runner = KoogAgentRunner(
            sandboxProvider = { sandboxOf(dataRoot) }, // 沙箱放行 ⇒ 不会是沙箱判据拦下的
            homeDataRootProvider = { dataRoot },     // 🔴 唯一被测的注入点
            executorFactory = fakeExecutorFactory(SendFileThenFinishExecutor(secret.absolutePath)),
        )
        runner.refreshEndpoint()

        runner.run(request(prompt = "深分析", opaque = payload)) { }

        assertEquals(0, replySink.sentFiles.size, "数据根判据经 provider 链生效（删掉透传即红）")
    }

    // ── P4 会话作用域三件（2026-09-26）──
    // 载体恒包 DispatchPayload（不再有裸 ReplySink 快路径）后注册的三个会话作用域工具：
    // react_emoji / fetch_quoted_context / update_own_card。直接 new 工具 + execute 直测
    // （不跑 Koog loop——被测面是工具的载体解析与会话校验，不是执行策略）。

    @Test
    fun `react_emoji 默认对载体 messageId 加表情`() = runBlocking {
        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = emptyList(),
            xlogPrivateKey = "",
            messageId = "om_user_msg",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val tool = ReactEmojiTool(request(prompt = "收到请回个赞", opaque = payload))

        val result = tool.execute(ReactEmojiTool.Args(emoji_key = "THUMBSUP"))

        assertEquals("已添加表情 THUMBSUP", result, "默认目标 = 载体 messageId（用户本条消息）")
        assertEquals(
            listOf(Triple("om_user_msg", "THUMBSUP", true)),
            replySink.reactions,
            "react 须真出站到载体 messageId 且 add=true",
        )
    }

    @Test
    fun `fetch_quoted_context 走账本且本会话校验`() = runBlocking {
        val ledger = com.jiaoay.lark.bridge.files.CardReplyLedger()
        ledger.record("om_quote", "oc_test", "正文")
        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = emptyList(),
            xlogPrivateKey = "",
            messageId = "om_user_msg",
            chatId = "oc_test",
            quotedMessageId = "om_quote",
            replyLedger = ledger,
        )
        val tool = FetchQuotedContextTool(request(prompt = "引用回复", opaque = payload))

        // 空白 message_id 回落载体 quotedMessageId，命中本会话账目
        assertEquals("正文", tool.execute(FetchQuotedContextTool.Args()), "空参应回落 quotedMessageId 命中账本")

        // 🔴 本会话校验（评审修正 3）：别的会话（oc_b）记录的账目，本会话（oc_test）查询须 miss
        ledger.record("om_other", "oc_b", "B 会话正文")
        val cross = tool.execute(FetchQuotedContextTool.Args(message_id = "om_other"))
        assertTrue("不在本地账本中" in cross, "跨会话 messageId 必须 miss（防 A 会话读 B 会话账）：$cross")
    }

    @Test
    fun `update_own_card 只许更新本会话自己发的卡`() = runBlocking {
        val ledger = com.jiaoay.lark.bridge.files.CardReplyLedger()
        ledger.record("om_my_card", "oc_test", "旧正文")
        val replySink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = replySink,
            allowedFiles = emptyList(),
            xlogPrivateKey = "",
            messageId = "om_user_msg",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = ledger,
        )
        val tool = UpdateOwnCardTool(request(prompt = "修正卡片", opaque = payload))

        // 本会话自己的卡（在账本）→ 放行出站
        assertEquals(
            "卡片已更新",
            tool.execute(UpdateOwnCardTool.Args(message_id = "om_my_card", title = "t", markdown = "m")),
            "本会话账本命中的卡须放行",
        )
        assertEquals(
            listOf("om_my_card"),
            replySink.updatedCards.map { it.first },
            "更新须真走到 updateExistingCard",
        )

        // 不在账本（非自己发的/跨会话）→ 拒绝
        val denied = tool.execute(UpdateOwnCardTool.Args(message_id = "om_not_mine", title = "t", markdown = "m"))
        assertTrue("只能更新你自己" in denied, "不在本会话账本的目标须拒绝：$denied")
    }

    // ── P5b 能力清单卡（2026-09-26）────────────────────────────────────────────
    // ALWAYS_REGISTERED_TOOLS 是 UI 能力卡的静态工具名面（不触 build）——名称与工具类
    // name 字符串逐字一致由本组断言钉死（改一处忘另一处即红）。

    /** 🔒 静态清单与工具类 name 逐字一致（SimpleTool 的 name 是构造参数，逐类实例化核对）。 */
    @Test
    fun alwaysRegisteredToolsMirrorToolClassNames() {
        val request = request(prompt = "x", opaque = RecordingReplySink())
        val actual = mapOf(
            "reply_direct" to ReplyDirectTool(request).name,
            "send_file" to SendFileTool(request).name,
            "send_image" to SendImageTool(request).name,
            "send_card" to SendCardTool(request).name,
            "react_emoji" to ReactEmojiTool(request).name,
            "fetch_quoted_context" to FetchQuotedContextTool(request).name,
            "update_own_card" to UpdateOwnCardTool(request).name,
            "recall_own_message" to RecallOwnMessageTool(request).name,
            "reply_in_thread" to ReplyInThreadTool(request).name,
            "search_knowledge" to SearchKnowledgeTool().name,
            "web.fetch" to com.jiaoay.easy_dev_toolbox.core.llm.tools.WebTools.WebFetchTool().name,
        )
        assertEquals(
            actual.keys,
            BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet(),
            "静态清单与工具类实际 name 集合必须一致（清单多列/漏列即红）",
        )
        actual.forEach { (listed, real) ->
            assertEquals(real, listed, "工具名映射错位：清单写 $listed、工具类实为 $real")
        }
    }

    /** 🔒 静态清单恰 11 项（含恒注册的 web.fetch 联网查询件）、无重复、非空。 */
    @Test
    fun alwaysRegisteredToolsAreUniqueAndNonEmpty() {
        assertEquals(11, BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.size, "常备工具恰 11 项")
        assertTrue(BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.isNotEmpty(), "能力清单不得为空")
        assertEquals(
            BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.size,
            BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.distinct().size,
            "能力清单不得有重复工具名",
        )
    }

    // ── web.fetch 恒注册（裸聊联网查询面）──────────────────────────────────────
    // 纯聊天（裸 ReplySink）与有引用文件消息同在册；防线全在 WebTools 工具本体。

    /** build 并返回注册的工具名集合（端点为本地哑配置——build 不触网络）。 */
    private fun buildRegistryNames(opaque: Any?): Set<String> = BridgeToolRegistry.build(
        endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
            baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
        ),
        request = request(prompt = "查下这个错误码什么意思", opaque = opaque),
    ).tools.map { it.name }.toSet()

    /** 🔒 web.fetch 恒注册：裸聊在册（含会话工具族）+ 文件消息零回归（移出 BridgeLogTools 后单点仍覆盖）。 */
    @Test
    fun `web fetch 恒注册-裸聊与文件消息同在册`() {
        val bare = buildRegistryNames(RecordingReplySink())
        assertTrue("web.fetch" in bare, "纯聊天消息 web.fetch 必须在册（恒注册）：${bare.sorted()}")
        assertTrue(
            listOf(
                "reply_direct", "send_file", "send_card", "send_image", "react_emoji",
                "fetch_quoted_context", "update_own_card", "recall_own_message", "reply_in_thread",
            ).all { it in bare },
            "裸聊会话工具族零回归：${bare.sorted()}",
        )
        assertTrue(
            listOf("probe_file", "decrypt_log", "log.stats", "read_file").none { it in bare },
            "纯聊天不得注册文件工具族（省 token 面不动）",
        )
        // 文件消息零回归：web.fetch 从 BridgeLogTools 名集移出后，恒注册单点须继续覆盖
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val withFile = buildRegistryNames(
            com.jiaoay.lark.bridge.pipeline.DispatchPayload(
                reply = RecordingReplySink(),
                allowedFiles = listOf(referenced),
                xlogPrivateKey = "",
                messageId = "om_test",
                chatId = "oc_test",
                quotedMessageId = null,
                replyLedger = null,
            ),
        )
        assertTrue("web.fetch" in withFile, "文件消息 web.fetch 不回归（恒注册单点覆盖）")
        assertTrue("log.stats" in withFile && "decrypt_log" in withFile, "文件消息工具面不回归")
    }

    /** 🔒 裸聊提示词含 web.fetch 引导行（listed 单源门控）；围栏段与计数句不随扩面触碰。 */
    @Test
    fun `web fetch 裸聊提示词含引导行且围栏计数句不触碰`() {
        // 与 KoogAgentRunner 同款单源派生：build 取注册名集喂提示词
        val bare = buildRegistryNames(RecordingReplySink())
        val prompt = buildKoogSystemPrompt(
            request = request(prompt = "查下这个错误码什么意思", opaque = RecordingReplySink()),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = bare,
        )
        assertTrue("web.fetch(url)" in prompt, "裸聊提示词须含 web.fetch 引导行（恒注册 ⇒ listed 门控放行）")
        assertTrue("拉取白名单域名" in prompt, "web.fetch 引导行须带白名单语义句")
        assertTrue("禁止编造页面内容" in prompt, "web.fetch 引导行须带反幻觉纪律句")
        assertTrue("文件处理工具" !in prompt, "裸聊不得出现文件处理工具段（web.fetch 非文件族，段门不动）")
        assertTrue("以上二十件读类工具" !in prompt, "围栏计数句不进裸聊提示词（P159 口径：计数句零触碰）")
        // 本机环境顾问段（listed("web.fetch") 同源门控）：环境类求助的纯咨询纪律须在位
        assertTrue("本机环境求助处理纪律" in prompt, "顾问段须随 web.fetch 在册出现")
        assertTrue("askubuntu.com" in prompt && "launchpad.net" in prompt && "gitlab.gnome.org" in prompt, "顾问段须点名三个本机环境知识域")
        assertTrue("可复制的命令清单" in prompt, "顾问段须含用户自跑命令清单纪律")
        assertTrue("不能代为执行任何本机命令" in prompt, "顾问段须明示零执行能力边界")
    }

    // ── L1 三件（能力一级化批 B 2026-09-27）──────────────────────────────────
    // send_image / recall_own_message / reply_in_thread 直测（镜像 P4 三件形态：不跑 Koog loop）。

    /** 造带账本 + 群聊 chatType 的 DispatchPayload。 */
    private fun payloadWithLedger(
        replySink: RecordingReplySink,
        ledger: com.jiaoay.lark.bridge.files.CardReplyLedger?,
        chatType: com.jiaoay.lark.gateway.ChatType = com.jiaoay.lark.gateway.ChatType.GROUP,
        nativeSurface: com.jiaoay.lark.bridge.pipeline.NativeChatSurface? = null,
        confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate? = null,
        chatId: String = "oc_test",
    ) = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
        reply = replySink,
        allowedFiles = emptyList(),
        xlogPrivateKey = "",
        messageId = "om_user_msg",
        chatId = chatId,
        quotedMessageId = null,
        replyLedger = ledger,
        chatType = chatType,
        nativeSurface = nativeSurface,
        confirmGate = confirmGate,
    )

    @Test
    fun `send_image 围栏内文件放行且走 image 出站`() = runBlocking {
        val ws = workspaceDir()
        val img = File(ws, "chart.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val replySink = RecordingReplySink()
        // 纯聊天档（无 context）+ 沙箱命中（send_file 同款判据）
        val tool = SendImageTool(request(prompt = "发图", opaque = replySink), context = null, sandbox = sandboxOf(ws))

        val result = tool.execute(SendImageTool.Args(file_path = img.absolutePath))

        assertEquals("图片已发送：chart.png（message_id=m_image）", result, "沙箱内图片应放行")
        assertEquals(listOf(img.toPath()), replySink.sentImages, "须真的走 image 出站（非 file/text）")
    }

    @Test
    fun `send_image 围栏外路径拒（与 send_file 同一围栏）`() = runBlocking {
        val ws = workspaceDir()
        val outsideDir = Files.createTempDirectory("koog-img-out").toFile().apply { deleteOnExit() }
        val outside = File(outsideDir, "secret.png").apply { writeBytes(byteArrayOf(1)) }
        val replySink = RecordingReplySink()
        val tool = SendImageTool(request(prompt = "发图", opaque = replySink), context = null, sandbox = sandboxOf(ws))

        val result = tool.execute(SendImageTool.Args(file_path = outside.absolutePath))
        assertTrue(result.contains("不可交付"), "沙箱外路径必须拒（与 send_file 同款 OPAQUE 文案）：$result")
        assertEquals(0, replySink.sentImages.size, "被拒不得出站")
    }

    @Test
    fun `send_image 产物总开关关闭时一律拒（前置闸与 send_file 同源）`() = runBlocking {
        val ws = workspaceDir()
        val img = File(ws, "chart.png").apply { writeBytes(byteArrayOf(1)) }
        val replySink = RecordingReplySink()
        val tool = SendImageTool(
            request(prompt = "发图", opaque = replySink),
            context = null,
            sandbox = sandboxOf(ws),
            artifactsEnabled = false,
        )

        val result = tool.execute(SendImageTool.Args(file_path = img.absolutePath))
        assertTrue(result.contains("产物回传已关闭"), "总开关关闭须拒（共用围栏第一闸）：$result")
        assertEquals(0, replySink.sentImages.size, "零出站")
    }

    @Test
    fun `recall_own_message 账本命中的自己消息放行撤回`() = runBlocking {
        val ledger = com.jiaoay.lark.bridge.files.CardReplyLedger()
        ledger.record("om_mine", "oc_test", "机器人回复")
        val replySink = RecordingReplySink()
        val payload = payloadWithLedger(replySink, ledger)
        val tool = RecallOwnMessageTool(request(prompt = "撤回", opaque = payload))

        val result = tool.execute(RecallOwnMessageTool.Args(message_id = "om_mine"))

        assertEquals("已撤回消息", result, "账本命中的机器人回复须放行")
        assertEquals(listOf("om_mine"), replySink.recalls, "须真的调 recallMessage")
    }

    @Test
    fun `recall_own_message 账本 miss 拒绝且不出站（双重守护）`() = runBlocking {
        val ledger = com.jiaoay.lark.bridge.files.CardReplyLedger()
        // 账本存在但目标是用户消息/跨会话消息 ⇒ miss
        ledger.record("om_other_chat", "oc_b", "B 会话回复")
        val replySink = RecordingReplySink()
        val payload = payloadWithLedger(replySink, ledger)
        val tool = RecallOwnMessageTool(request(prompt = "撤回", opaque = payload))

        val result = tool.execute(RecallOwnMessageTool.Args(message_id = "om_not_mine"))
        assertTrue(
            "不在本会话机器人回复账本中" in result,
            "账本 miss 须拒绝并说明只能撤自己的：$result",
        )
        assertEquals(0, replySink.recalls.size, "拒绝时零出站")

        // 跨会话 messageId 同样 miss（账本 lookup 带 chatId）
        val cross = tool.execute(RecallOwnMessageTool.Args(message_id = "om_other_chat"))
        assertTrue("不在本会话机器人回复账本中" in cross, "跨会话 messageId 必须 miss：$cross")
        assertEquals(0, replySink.recalls.size, "跨会话零出站")
    }

    @Test
    fun `recall_own_message 账本未启用时如实告知`() = runBlocking {
        val replySink = RecordingReplySink()
        val payload = payloadWithLedger(replySink, ledger = null)
        val tool = RecallOwnMessageTool(request(prompt = "撤回", opaque = payload))

        val result = tool.execute(RecallOwnMessageTool.Args(message_id = "om_any"))
        assertTrue("账本未启用" in result, "账本 null 须如实告知：$result")
        assertEquals(0, replySink.recalls.size)
    }

    @Test
    fun `reply_in_thread 群聊走话题出站`() = runBlocking {
        val replySink = RecordingReplySink()
        val payload = payloadWithLedger(replySink, ledger = null, chatType = com.jiaoay.lark.gateway.ChatType.GROUP)
        val tool = ReplyInThreadTool(request(prompt = "话题回复", opaque = payload))

        val result = tool.execute(ReplyInThreadTool.Args(text = "补充说明"))
        assertEquals("已发送到话题", result, "群聊须走 replyInThread")
        assertEquals(listOf("补充说明"), replySink.threadReplies, "须真的调 replyInThread")
        assertEquals(0, replySink.sentTexts.size, "群聊不得回落 text")
    }

    @Test
    fun `reply_in_thread 单聊回落普通回复并告知`() = runBlocking {
        val replySink = RecordingReplySink()
        val payloadP2p = payloadWithLedger(replySink, ledger = null, chatType = com.jiaoay.lark.gateway.ChatType.P2P)
        val tool = ReplyInThreadTool(request(prompt = "话题回复", opaque = payloadP2p))

        val result = tool.execute(ReplyInThreadTool.Args(text = "单聊回复"))
        assertEquals("当前会话非群聊，已按普通回复发送", result, "P2P 须回落 text 并如实告知")
        assertEquals(listOf("单聊回复"), replySink.sentTexts, "回落须真的走 text 出站")
        assertEquals(0, replySink.threadReplies.size, "P2P 不调 replyInThread")

        // UNKNOWN 同样回落
        val payloadUnknown = payloadWithLedger(replySink, ledger = null, chatType = com.jiaoay.lark.gateway.ChatType.UNKNOWN)
        val resultUnknown = ReplyInThreadTool(request(prompt = "x", opaque = payloadUnknown))
            .execute(ReplyInThreadTool.Args(text = "未知态"))
        assertEquals("当前会话非群聊，已按普通回复发送", resultUnknown, "UNKNOWN 须同 P2P 回落")
    }

    // ── L2 五件（能力一级化批 C 2026-09-27）──────────────────────────────────
    // 直测形态镜像 L1 三件：不跑 Koog loop，被测面是「载体解析 + 能力缺失降级 + 确认门」。
    // 🔴 chatId 钉死：Args 无 chat_id（批 D 守护形态）——目标会话恒取 nativeSurface 闭包。
    // 🔴 生产实现 GatewayNativeChatSurface 是 lib 的 internal（feature 不可见）——其行为
    //    （URL 分流/30k 截断/成员格式化）由 lib 侧 NativeChatSurfaceTest 钉死；本文件用
    //    FakeNativeSurface 测工具层与注册矩阵（分层正确的测试切分）。

    /** feature 侧 Fake surface：可编程返回 + 调用记录（含钉死的 chatId 断言面）。 */
    private class FakeNativeSurface(
        private val chatId: String = "oc_test",
        var history: com.jiaoay.lark.gateway.ChatHistoryPage? = null,
        var info: String = "",
        var creation: com.jiaoay.lark.gateway.ChatCreation? = null,
        var profile: com.jiaoay.lark.gateway.SenderProfile? = null,
        var document: String = "",
    ) : com.jiaoay.lark.bridge.pipeline.NativeChatSurface {
        val historyCalls = mutableListOf<Pair<Int, String?>>()
        val documentCalls = mutableListOf<String>()
        val createdGroups = mutableListOf<Pair<String, List<String>>>()

        override suspend fun fetchChatHistory(limit: Int, pageToken: String?): com.jiaoay.lark.gateway.ChatHistoryPage? {
            historyCalls.add(limit to pageToken)
            return history
        }

        override suspend fun chatInfoWithMembers(): String = info

        override suspend fun createGroupChat(name: String, memberOpenIds: List<String>): com.jiaoay.lark.gateway.ChatCreation? {
            createdGroups.add(name to memberOpenIds)
            return creation
        }

        override suspend fun lookupUser(openId: String): com.jiaoay.lark.gateway.SenderProfile? = profile

        override suspend fun readWikiDocument(urlOrToken: String): String {
            documentCalls.add(urlOrToken)
            return document
        }
    }

    private val nativeConfigAll = com.jiaoay.lark.bridge.config.BridgeConfig()

    private fun payloadWithSurface(
        replySink: RecordingReplySink,
        surface: com.jiaoay.lark.bridge.pipeline.NativeChatSurface?,
        confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate? = null,
    ) = payloadWithLedger(replySink, ledger = null, nativeSurface = surface, confirmGate = confirmGate)

    @Test
    fun `fetch_chat_history 格式化历史并附续页提示`() = runBlocking {
        val surface = FakeNativeSurface(
            history = com.jiaoay.lark.gateway.ChatHistoryPage(
                items = listOf(
                    com.jiaoay.lark.gateway.ChatHistoryItem("om_1", "ou_a", 1_700_000_000_000L, "text", "第一条"),
                    com.jiaoay.lark.gateway.ChatHistoryItem("om_2", "ou_b", 1_700_000_060_000L, "text", "第二条"),
                ),
                hasMore = true,
                nextPageToken = "tok_next",
            ),
        )
        val tool = FetchChatHistoryTool(request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), surface)))

        val result = tool.execute(FetchChatHistoryTool.Args())

        assertTrue("第一条" in result && "第二条" in result, "历史正文必须在：$result")
        assertTrue("ou_a" in result && "ou_b" in result, "发送者必须在（格式化行）：$result")
        assertTrue("tok_next" in result, "has_more 须附续页 page_token：$result")
        // 🔴 chatId 钉死的载体面：Args 无 chat_id——limit 钳制断言（50 上限）
        surface.historyCalls.clear()
        tool.execute(FetchChatHistoryTool.Args(limit = 500, page_token = "tok2"))
        assertEquals(listOf<Pair<Int, String?>>(50 to "tok2"), surface.historyCalls, "limit 须钳到 50、page_token 原样透传")
    }

    @Test
    fun `fetch_chat_history 能力缺席回 scope 引导文案`() = runBlocking {
        // history=null（scope 未开通形态）
        val tool = FetchChatHistoryTool(request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), FakeNativeSurface())))
        val result = tool.execute(FetchChatHistoryTool.Args())
        assertTrue("im:message.history:readonly" in result, "缺席须带 scope 引导：$result")
    }

    @Test
    fun `fetch_chat_history 载体未接线时如实告知`() = runBlocking {
        val tool = FetchChatHistoryTool(request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), surface = null)))
        assertTrue("未接线" in tool.execute(FetchChatHistoryTool.Args()), "surface=null 须如实告知")
    }

    @Test
    fun `get_chat_info 返回格式化文本且缺席回 im-chat 引导`() = runBlocking {
        val surface = FakeNativeSurface()
        val tool = GetChatInfoTool(request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), surface)))
        // 缺席态（info 空串）
        assertTrue("im:chat" in tool.execute(GetChatInfoTool.Args()), "缺席须带 scope 引导")
        // 就绪态
        surface.info = "会话名：项目群\n成员（前 1 名）：\n- ou_a（甲）"
        val text = tool.execute(GetChatInfoTool.Args())
        assertTrue("项目群" in text && "甲" in text, "群信息格式化文本必须透传：$text")
    }

    @Test
    fun `create_group_chat 确认批准后建群并返回引导句`() = runBlocking {
        val surface = FakeNativeSurface(creation = com.jiaoay.lark.gateway.ChatCreation("oc_new_group", emptyList()))
        val service = com.jiaoay.lark.bridge.confirm.ConfirmService()
        val notifier = object : com.jiaoay.lark.bridge.confirm.ConfirmNotifier {
            override suspend fun notify(request: com.jiaoay.lark.bridge.confirm.ConfirmService.ConfirmRequest) {
                // 确认卡发出即批准（模拟用户点确认）
                service.decide(
                    com.jiaoay.lark.bridge.confirm.ConfirmService.shortId(request.requestId),
                    request.chatId, request.senderOpenId, true,
                )
            }
        }
        val gate = com.jiaoay.lark.bridge.confirm.ToolConfirmGate(service, notifier, "oc_test", "ou_a", 5_000)
        val tool = CreateGroupChatTool(
            request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), surface, gate)),
            gate,
            isTrustedSender = { true },  // 跨会话写只对信任用户开放；此处测确认卡链路
        )

        val out = tool.execute(CreateGroupChatTool.Args(name = "新项目群", member_open_ids = listOf("ou_a", "ou_b")))

        assertTrue("oc_new_group" in out, "成功须回 chatId：$out")
        assertTrue("@ 机器人" in out, "必须带 L1 出站边界引导句：$out")
        assertEquals(listOf("新项目群" to listOf("ou_a", "ou_b")), surface.createdGroups, "批准后须真调 surface.createGroupChat")
    }

    @Test
    fun `create_group_chat 用户拒绝则取消建群（零 surface 调用）`() = runBlocking {
        val surface = FakeNativeSurface()
        val service = com.jiaoay.lark.bridge.confirm.ConfirmService()
        val notifier = object : com.jiaoay.lark.bridge.confirm.ConfirmNotifier {
            override suspend fun notify(request: com.jiaoay.lark.bridge.confirm.ConfirmService.ConfirmRequest) {
                service.decide(
                    com.jiaoay.lark.bridge.confirm.ConfirmService.shortId(request.requestId),
                    request.chatId, request.senderOpenId, false, // 用户拒绝
                )
            }
        }
        val gate = com.jiaoay.lark.bridge.confirm.ToolConfirmGate(service, notifier, "oc_test", "ou_a", 5_000)
        val tool = CreateGroupChatTool(
            request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), surface, gate)),
            gate,
            isTrustedSender = { true },  // 跨会话写只对信任用户开放；此处测确认卡链路
        )

        val out = tool.execute(CreateGroupChatTool.Args(name = "群", member_open_ids = listOf("ou_a")))

        assertTrue("用户未确认" in out, "拒绝须取消并告知：$out")
        assertTrue(surface.createdGroups.isEmpty(), "🔴 拒绝时零建群调用")
    }

    @Test
    fun `create_group_chat 确认链不可用 fail-closed（不建群）`() = runBlocking {
        val surface = FakeNativeSurface()
        // 🔴 confirmGate=null（确认链未启用形态）——绝不放行
        val tool = CreateGroupChatTool(
            request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), surface)),
            confirmGate = null,
            isTrustedSender = { true },
        )
        val out = tool.execute(CreateGroupChatTool.Args(name = "群", member_open_ids = listOf("ou_a")))
        assertTrue("确认链路不可用" in out, "fail-closed 引导文案：$out")
        assertTrue(surface.createdGroups.isEmpty(), "🔴 链不可用时零建群调用（绝不静默放行）")
    }

    @Test
    fun `lookup_user 回名字且缺席回 contact 引导（PII 最小化）`() = runBlocking {
        val surface = FakeNativeSurface()
        val tool = LookupUserTool(request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), surface)))
        // 缺席
        assertTrue("contact:contact.base:readonly" in tool.execute(LookupUserTool.Args(open_id = "ou_x")), "缺席须带 scope 引导")
        // 命中：只回名字（PII 最小化——avatar 不进文本回复）
        surface.profile = com.jiaoay.lark.gateway.SenderProfile("李四", "https://avatar.example/x.jpg")
        assertEquals("李四", tool.execute(LookupUserTool.Args(open_id = "ou_lisi")))
        // 空名（解析到但无名字）
        surface.profile = com.jiaoay.lark.gateway.SenderProfile("", "")
        assertTrue("查不到" in tool.execute(LookupUserTool.Args(open_id = "ou_empty")), "空名如实告知")
    }

    @Test
    fun `read_wiki_document 分流透传与缺席引导`() = runBlocking {
        val surface = FakeNativeSurface()
        val tool = ReadWikiDocumentTool(request(prompt = "x", opaque = payloadWithSurface(RecordingReplySink(), surface)))
        // 缺席（空串 = surface 报能力缺席）
        assertTrue("wiki:wiki:readonly" in tool.execute(ReadWikiDocumentTool.Args(url = "https://x.cn/wiki/wikA")), "缺席须带 scope 引导")
        // wiki / docx URL 原样透传给 surface（分流在 surface 实现，lib 侧已测）
        surface.document = "wiki 正文"
        assertEquals("wiki 正文", tool.execute(ReadWikiDocumentTool.Args(url = "https://x.cn/wiki/wikA")))
        surface.document = "docx 正文"
        assertEquals("docx 正文", tool.execute(ReadWikiDocumentTool.Args(url = "https://x.cn/docx/doxcnB")))
        assertEquals(
            // 累计记录：缺席断言阶段的调用也在内
            listOf("https://x.cn/wiki/wikA", "https://x.cn/wiki/wikA", "https://x.cn/docx/doxcnB"),
            surface.documentCalls,
            "URL 必须原样透传（分流归 surface）",
        )
    }

    // ── L2 三条件注册矩阵（build 直测）──────────────────────────────────

    /** build 并返回注册的工具名集合。 */
    private fun buildToolNames(
        nativeConfig: com.jiaoay.lark.bridge.config.BridgeConfig?,
        surface: com.jiaoay.lark.bridge.pipeline.NativeChatSurface?,
        confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate? = null,
    ): Set<String> {
        val payload = payloadWithSurface(RecordingReplySink(), surface, confirmGate)
        val registry = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = payload),
            nativeConfig = nativeConfig,
        )
        return registry.tools.map { it.name }.toSet()
    }

    @Test
    fun `L2 注册矩阵：nativeSurface 在位即五件全注册（配置闸已随 v11→v12 退役）`() {
        val surface = FakeNativeSurface()
        val all = buildToolNames(nativeConfig = com.jiaoay.lark.bridge.config.BridgeConfig(), surface = surface)
        assertTrue(
            listOf("fetch_chat_history", "get_chat_info", "create_group_chat", "lookup_user", "read_wiki_document")
                .all { it in all },
            "surface 在位须五件全注册：${all.sorted()}",
        )
        // 接缝缺席 ⇒ 全不注册（fail-closed）
        val noSurface = buildToolNames(nativeConfig = com.jiaoay.lark.bridge.config.BridgeConfig(), surface = null)
        assertTrue(
            "fetch_chat_history" !in noSurface && "create_group_chat" !in noSurface,
            "surface 缺 ⇒ L2 全不注册（fail-closed）",
        )
    }

    @Test
    fun `L2 注册时 ALWAYS 清单不扩（条件注册不进常备名单）`() {
        // ALWAYS_REGISTERED_TOOLS 恒 11 项——L2 是条件注册，永不进常备清单
        assertEquals(11, BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.size, "常备清单恒 11（web.fetch 恒注册扩面）")
        assertTrue(
            BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.intersect(
                setOf("fetch_chat_history", "get_chat_info", "create_group_chat", "lookup_user", "read_wiki_document"),
            ).isEmpty(),
            "L2 五工具不得进常备清单",
        )
    }

    /** 自动裁决确认门：ConfirmService + 发卡即裁决的 notifier（approve 可编程；记录请求供卡面断言）。 */
    private class AutoConfirmGate(approve: Boolean) {
        private val service = com.jiaoay.lark.bridge.confirm.ConfirmService()
        val requests = java.util.concurrent.CopyOnWriteArrayList<com.jiaoay.lark.bridge.confirm.ConfirmService.ConfirmRequest>()
        val gate = com.jiaoay.lark.bridge.confirm.ToolConfirmGate(
            confirmService = service,
            notifier = object : com.jiaoay.lark.bridge.confirm.ConfirmNotifier {
                override suspend fun notify(request: com.jiaoay.lark.bridge.confirm.ConfirmService.ConfirmRequest) {
                    requests.add(request)
                    service.decide(
                        com.jiaoay.lark.bridge.confirm.ConfirmService.shortId(request.requestId),
                        request.chatId,
                        request.senderOpenId,
                        approve,
                    )
                }
            },
            chatId = "oc_test",
            senderOpenId = "ou_test",
            timeoutMillis = 5_000,
        )
    }


    /** build 并返回注册的工具名集合（sys 形态：带 chatId/senderOpenId/会话授权档）。 */
    private fun buildSysToolNames(
        nativeConfig: com.jiaoay.lark.bridge.config.BridgeConfig?,
        confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate?,
        chatId: String = "oc_test",
        senderOpenId: String = "ou_test",
        trustedUsers: List<String>? = listOf("ou_test"),
    ): Set<String> {
        val payload = payloadWithLedger(RecordingReplySink(), ledger = null, confirmGate = confirmGate)
        return BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = payload),
            chatId = chatId,
            senderOpenId = senderOpenId,
            nativeConfig = nativeConfig,
            trustedUsersProvider = trustedUsers?.let { users -> { users } },
        ).tools.map { it.name }.toSet()
    }


    // ── sys.* 七件（条件注册）──────────────────────────────────────────────
    // ① confirmGate 接线 ② trustedUsersProvider ③ chatId 非空 ④ senderOpenId 非空，缺一不注册（fail-closed）；
    // 域准入按信任用户名单（TrustPolicy.isTrusted）——准入即注册全部族根；写/修复/服务的逐动作卡对信任用户免卡，
    // 非信任用户止步于域准入（逐动作卡仅作装配兜底）。


    @Test
    fun `sys 注册矩阵：接缝在位即全量注册（注册面已无配置闸）`() {
        val gate = AutoConfirmGate(approve = true).gate
        val cfg = com.jiaoay.lark.bridge.config.BridgeConfig()
        val allSys = setOf(
            "sys.probe", "sys.read_config", "sys.repair", "sys.write_config",
            "sys.service", "sys.verify", "sys.handoff",
        )
        val all = buildSysToolNames(nativeConfig = cfg, confirmGate = gate)
            .filterTo(mutableSetOf()) { it.startsWith("sys.") }
        assertEquals(allSys, all, "接缝在位须全量注册")
        assertTrue(
            "sys.probe" !in buildSysToolNames(nativeConfig = cfg, confirmGate = null),
            "confirmGate 缺 ⇒ 不注册（fail-closed）",
        )
        assertTrue(
            "sys.probe" !in buildSysToolNames(nativeConfig = cfg, confirmGate = gate, chatId = ""),
            "chatId 空 ⇒ 不注册",
        )
        assertTrue(
            "sys.probe" !in buildSysToolNames(nativeConfig = cfg, confirmGate = gate, trustedUsers = null),
            "trustedUsersProvider 缺 ⇒ 不注册",
        )
    }

    @Test
    fun `sys verify 与 handoff 提示词行与纪律分支挂 listed 门控（在册才列）`() {
        // 全量形态（registeredToolNames 空 = 全量列出）：两行在 + 验证回环/外委边界纪律在 +
        // 修复纪律的验证步升级为 sys.verify、边界句升级为 sys.handoff
        val full = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
        )
        assertTrue("sys.verify(key, …)" in full, "全量形态须列出 sys.verify 行")
        assertTrue("sys.handoff(purpose, steps)" in full, "全量形态须列出 sys.handoff 行")
        assertTrue("验证回环纪律" in full, "verify 在册须挂验证回环纪律句")
        assertTrue("外委边界纪律" in full, "handoff 在册须挂外委边界纪律句")
        // repair 行的验证步在 verify 在册时升级为 sys.verify
        assertTrue("执行后用 sys.verify 复跑验证" in full, "verify 在册时 repair 行验证步升级")
        // repair 纪律的验证步/边界句同步升级
        assertTrue("sys.verify 验证 → 验证不通过再外委" in full, "修复纪律闭环步升级")
        // 跨时复查句随 remind_user 在册才出现（全量形态 remind_user 列出）
        assertTrue("remind_user" in full.substringAfter("验证回环纪律").substringBefore("外委边界纪律"), "验证回环纪律须带跨时复查话术")

        // verify+handoff 都不在册：行缺席、纪律分支缺席、repair 行/纪律回落 sys.probe/命令清单形态
        val noVerifyHandoff = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = setOf("sys.probe", "sys.read_config", "sys.repair"),
        )
        assertTrue("sys.verify" !in noVerifyHandoff, "未注册不列")
        assertTrue("sys.handoff(purpose, steps)" !in noVerifyHandoff, "未注册不列")
        assertTrue("验证回环纪律" !in noVerifyHandoff, "verify 不在册时验证回环句缺席")
        assertTrue("外委边界纪律" !in noVerifyHandoff, "handoff 不在册时外委边界句缺席")
        assertTrue("执行后立即用 sys.probe 对应条目复跑验证" in noVerifyHandoff, "verify 缺席时修复纪律回落 sys.probe 形态")
        assertTrue("以可复制命令清单交用户本人" in noVerifyHandoff, "handoff 缺席时边界句回落命令清单形态")
    }

    @Test
    fun `sys 注册时 ALWAYS 清单不扩且提示词围栏计数句不触碰`() {
        // sys.* 是条件注册，永不进常备清单
        assertTrue(
            BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.intersect(
                setOf(
                    "sys.probe", "sys.read_config", "sys.repair", "sys.write_config",
                    "sys.service", "sys.verify", "sys.handoff",
                ),
            ).isEmpty(),
            "sys.* 不得进常备清单",
        )
        // 有引用文件的载体（文件处理工具段在位）——sys 行须在段外，围栏计数句（二十件）不随 sys 扩面触碰
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val prompt = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            // registeredToolNames 空 = 全量列出（sys 行在 + 文件处理工具段完整——计数句一致性断言的形态）
            registeredToolNames = emptySet(),
        )
        assertTrue("sys.probe(key, …)" in prompt, "注册后须列出 sys.probe 行")
        assertTrue("sys.read_config(family, relative_path)" in prompt, "注册后须列出 sys.read_config 行")
        assertTrue("本机诊断纪律" in prompt, "诊断纪律段须在（任一 sys.* 在册）")
        // registeredToolNames 空 = 全量列出语义 ⇒ sys.repair 行与修复纪律分支同在（零执行句退位）
        assertTrue("sys.repair(key, …)" in prompt, "空注册集 = 全量列出，sys.repair 行随全量形态在")
        assertTrue("修复动作纪律" in prompt, "全量形态下修复纪律分支在")
        val section = prompt.substringAfter("文件处理工具").substringBefore("文件围栏")
        val listedLines = section.lineSequence().count { it.startsWith("- ") }
        assertEquals(20, listedLines, "文件处理工具段计数不随 sys 扩面触碰（sys 不进围栏段）")
        assertTrue("以上二十件读类工具" in prompt, "围栏句计数与实际清单同步")
        // sys 未注册 ⇒ 行与纪律段整段缺席
        val promptOff = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet(),
        )
        assertTrue("sys.probe" !in promptOff && "sys.read_config" !in promptOff, "未注册不列")
        assertTrue("本机诊断纪律" !in promptOff, "sys 未注册时纪律段整段缺席")
    }

    @Test
    fun `sys repair 提示词行与修复纪律段挂 listed 门控（在册才列，零执行句退位）`() {
        // repair 在册：工具行 + 修复纪律分支在，零执行能力句退位（与注册面同步，不留自相矛盾句）
        val promptOn = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = setOf("sys.probe", "sys.read_config", "sys.repair"),
        )
        assertTrue("sys.repair(key, …)" in promptOn, "注册后须列出 sys.repair 行")
        assertTrue("修复动作纪律" in promptOn, "repair 在册须挂修复纪律句（诊断→方案→确认→执行→验证）")
        assertTrue("信任用户直接执行；非信任用户先经其确认卡批准" in promptOn, "修复纪律须钉信任分级：${promptOn.substringAfter("修复动作纪律").lineSequence().first()}")
        assertTrue("零执行能力边界保持" !in promptOn, "repair 在册时零执行句退位（否则与修复能力自相矛盾）")
        assertTrue("修复边界保持" in promptOn, "修复边界句在（删除/磁盘/网络/持久化/提权永不执行）")
        // repair 不在册而其余 sys.* 在册：零执行句回位，repair 行缺席
        val promptNoRepair = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = setOf("sys.probe"),
        )
        assertTrue("sys.repair" !in promptNoRepair, "未注册不列")
        assertTrue("零执行能力边界保持" in promptNoRepair, "repair 不在册时零执行句回位")
        assertTrue("修复动作纪律" !in promptNoRepair, "修复纪律句不在")
    }

    @Test
    fun `sys write 与 service 提示词行与纪律段挂 listed 门控（在册才列）`() {
        // write+service 在册：工具行 + 对应纪律句在，零执行句退位（与注册面同步，不留自相矛盾句）
        val promptOn = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = setOf("sys.probe", "sys.write_config", "sys.service"),
        )
        assertTrue(
            "sys.write_config(family, relative_path, content)" in promptOn,
            "注册后须列出 sys.write_config 行",
        )
        assertTrue("sys.service(service, action)" in promptOn, "注册后须列出 sys.service 行")
        assertTrue("配置写纪律" in promptOn, "write 在册须挂配置写纪律句（diff 确认 → .bak → 复查）")
        assertTrue("信任用户直接写入；非信任用户先经其确认卡批准" in promptOn, "配置写纪律须钉信任分级")
        assertTrue("登录启动脚本" in promptOn, "配置写纪律须钉持久化面排除")
        assertTrue("服务动作纪律" in promptOn, "service 在册须挂服务动作纪律句")
        assertTrue("DESTRUCTIVE" in promptOn, "服务纪律句含 DESTRUCTIVE 徽标语义")
        assertTrue("桌面会话级" in promptOn, "服务纪律句钉会话级单元排除")
        assertTrue("零执行能力边界保持" !in promptOn, "写动作在册时零执行句退位（否则与写能力自相矛盾）")
        // write/service 均不在册：零执行句回位，两行缺席
        val promptOff = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = setOf("sys.probe", "sys.read_config"),
        )
        assertTrue("sys.write_config" !in promptOff && "sys.service" !in promptOff, "未注册不列")
        assertTrue("零执行能力边界保持" in promptOff, "无写动作在册时零执行句回位")
        assertTrue("配置写纪律" !in promptOff && "服务动作纪律" !in promptOff, "对应纪律句不在")
    }

    @Test
    fun `dev 注册矩阵：接缝在位即全量注册（注册面已无配置闸）`() {
        val gate = AutoConfirmGate(approve = true).gate
        val source = fakeDeviceSource()
        val cfg = com.jiaoay.lark.bridge.config.BridgeConfig()
        assertEquals(
            setOf("dev.devices", "dev.snaplog"),
            buildDevToolNames(nativeConfig = cfg, confirmGate = gate, deviceSource = source),
            "接缝在位须两件全注册",
        )
        assertTrue(
            buildDevToolNames(nativeConfig = cfg, confirmGate = null, deviceSource = source).isEmpty(),
            "confirmGate 缺 ⇒ 不注册",
        )
        assertTrue(
            buildDevToolNames(nativeConfig = cfg, confirmGate = gate, deviceSource = null).isEmpty(),
            "devDeviceSource 缺 ⇒ 不注册",
        )
        assertTrue(
            buildDevToolNames(nativeConfig = cfg, confirmGate = gate, deviceSource = source, chatId = "").isEmpty(),
            "chatId 空 ⇒ 不注册",
        )
    }

    @Test
    fun `dev 纯聊天装配快照消费链且挂同一围栏、有引用文件时不重复装配`() {
        val gate = AutoConfirmGate(approve = true).gate
        val cfg = com.jiaoay.lark.bridge.config.BridgeConfig()
        // 纯聊天（无引用文件）+ dev 在册：侦察四件 + Tag 过滤随 dev 注册（快照消费链）
        val payload = payloadWithLedger(RecordingReplySink(), ledger = null, confirmGate = gate)
        val tools = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = payload),
            chatId = "oc_test",
            senderOpenId = "ou_test",
            nativeConfig = cfg,
            trustedUsersProvider = { listOf("ou_test") },
            devDeviceSource = fakeDeviceSource(),
        ).tools.map { it.name }
        for (name in listOf("log.stats", "log.cluster", "log.view", "filter_by_tag")) {
            assertTrue(name in tools, "纯聊天 + dev 在册须装配快照消费链：$name 不在 ${tools.sorted()}")
        }
        assertEquals(1, tools.count { it == "log.stats" }, "快照消费链不重复装配")
        // 设备源缺席：纯聊天不装配消费链
        val off = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = payload),
            chatId = "oc_test",
            senderOpenId = "ou_test",
            nativeConfig = com.jiaoay.lark.bridge.config.BridgeConfig(),
            trustedUsersProvider = { listOf("ou_test") },
            devDeviceSource = null,
        ).tools.map { it.name }
        assertTrue("log.stats" !in off, "设备源缺席 ⇒ 纯聊天不装配消费链")
        assertTrue("dev.devices" !in off && "dev.snaplog" !in off, "接缝缺 ⇒ 两件不注册")
        // 有引用文件：消费链由文件块装配（单份），dev 两件同在（共用本消息围栏）
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val filePayload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
            confirmGate = gate,
        )
        val withFiles = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = filePayload),
            chatId = "oc_test",
            senderOpenId = "ou_test",
            nativeConfig = cfg,
            trustedUsersProvider = { listOf("ou_test") },
            devDeviceSource = fakeDeviceSource(),
        ).tools.map { it.name }
        assertEquals(1, withFiles.count { it == "log.stats" }, "有引用文件时消费链只由文件块装配一份")
        assertTrue("dev.devices" in withFiles && "dev.snaplog" in withFiles, "有引用文件时 dev 两件同在")
    }

    @Test
    fun `dev 提示词行与纪律段挂 listed 门控（在册才列）`() {
        // 全量形态（registeredToolNames 空 = 全量列出）：两行在 + 设备观测纪律段在
        val full = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
        )
        assertTrue("dev.devices()" in full, "全量形态须列出 dev.devices 行")
        assertTrue("dev.snaplog(serial, lines)" in full, "全量形态须列出 dev.snaplog 行")
        assertTrue("设备观测纪律" in full, "任一 dev.* 在册须挂设备观测纪律段")
        assertTrue("只读观测边界" in full, "纪律段须钉只读观测边界（安装/卸载/推送/删除/截屏/注入永不执行）")
        assertTrue("serial 白名单" in full, "纪律段须钉 serial 白名单（先列设备再抓快照）")
        assertTrue("永不发往飞书" in full, "纪律段须钉快照不外发（负面清单第 7 条）")
        // dev 不在册：行与纪律段整段缺席
        val off = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet(),
        )
        assertTrue("dev.devices" !in off && "dev.snaplog" !in off, "未注册不列")
        assertTrue("设备观测纪律" !in off, "dev 未注册时纪律段整段缺席")
        // 仅 devices 在册：snaplog 行缺席、纪律段在（段门控按任一 dev.* 在册）
        val devicesOnly = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = setOf("dev.devices"),
        )
        assertTrue("dev.devices()" in devicesOnly, "devices 在册须列行")
        // dev.devices 行内含「dev.snaplog」串号用途指引——缺席断言按行前缀判（子串会误命中指引）
        assertTrue(
            devicesOnly.lineSequence().none { it.startsWith("- dev.snaplog(") },
            "snaplog 未注册不列（dev.devices 行的指引文字不算列行）",
        )
        assertTrue("设备观测纪律" in devicesOnly, "任一 dev.* 在册纪律段在")
    }

    @Test
    fun `dev 域不进常备清单且不触碰围栏计数句`() {
        assertTrue(
            BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.intersect(setOf("dev.devices", "dev.snaplog")).isEmpty(),
            "dev.* 不得进常备清单（条件注册）",
        )
        // 有引用文件的载体——dev 行须在段外，围栏计数句（二十件）不随 dev 扩面触碰
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val prompt = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = emptySet(),
        )
        val section = prompt.substringAfter("文件处理工具").substringBefore("文件围栏")
        val listedLines = section.lineSequence().count { it.startsWith("- ") }
        assertEquals(20, listedLines, "文件处理工具段计数不随 dev 扩面触碰（dev 不进围栏段）")
        assertTrue("以上二十件读类工具" in prompt, "围栏句计数与实际清单同步")
        assertTrue("dev.devices()" in prompt, "空注册集 = 全量列出，dev.devices 行随全量形态在")
    }

    @Test
    fun `self status 注册矩阵：状态源在位即注册（注册面已无配置闸）`() {
        val source = fakeSelfStatusSource()
        assertEquals(
            setOf("self.status"),
            buildSelfStatusToolNames(nativeConfig = com.jiaoay.lark.bridge.config.BridgeConfig(), source = source),
            "状态源在位须注册",
        )
        assertTrue(
            buildSelfStatusToolNames(nativeConfig = com.jiaoay.lark.bridge.config.BridgeConfig(), source = null).isEmpty(),
            "状态源缺席 ⇒ 不注册（零数据面）",
        )
    }

    @Test
    fun `self status 不进常备清单且提示词行挂 listed 门控（在册才列）`() {
        assertTrue(
            "self.status" !in BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS,
            "self.status 不得进常备清单（条件注册）",
        )
        // 全量形态（registeredToolNames 空 = 全量列出）：行在
        val full = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
        )
        assertTrue("self.status()" in full, "全量形态须列出 self.status 行")
        assertTrue("聚合计数" in full, "行须钉审计只报聚合数的纪律")
        // 不在册：行缺席（常备清单形态）
        val off = buildKoogSystemPrompt(
            request = request(prompt = "x"),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet(),
        )
        assertTrue("self.status" !in off, "未注册不列")
        // 有引用文件载体：self.status 行在段外，围栏计数句（二十件）不随扩面触碰
        val ws = workspaceDir()
        val referenced = File(ws, "referenced.log").apply { writeText("x") }
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = RecordingReplySink(),
            allowedFiles = listOf(referenced),
            xlogPrivateKey = "",
            messageId = "om_test",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val prompt = buildKoogSystemPrompt(
            request = request(prompt = "分析", opaque = payload),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = emptySet(),
        )
        val section = prompt.substringAfter("文件处理工具").substringBefore("文件围栏")
        val listedLines = section.lineSequence().count { it.startsWith("- ") }
        assertEquals(20, listedLines, "文件处理工具段计数不随 self.status 扩面触碰（不进围栏段）")
        assertTrue("以上二十件读类工具" in prompt, "围栏句计数与实际清单同步")
        assertTrue("self.status()" in prompt, "空注册集 = 全量列出，self.status 行随全量形态在")
    }

    @Test
    fun `dev 域准入按信任名单判定（信任放行、非信任拒绝）`() = runBlocking {
        val trusted = KoogDevSessionGate(trustedUsersProvider = { listOf("ou_test") }, senderOpenId = "ou_test")
        assertTrue(trusted.requireSessionAuthorization("dev.snaplog"), "信任用户须放行")
        assertTrue(trusted.requireSessionAuthorization("dev.devices"), "同一信任对该域全部工具生效")

        val untrusted = KoogDevSessionGate(trustedUsersProvider = { listOf("ou_other") }, senderOpenId = "ou_test")
        assertFalse(untrusted.requireSessionAuthorization("dev.snaplog"), "非信任用户须拒绝")

        val empty = KoogDevSessionGate(trustedUsersProvider = { emptyList() }, senderOpenId = "ou_test")
        assertFalse(empty.requireSessionAuthorization("dev.devices"), "空名单 = 无人免确认（安全默认）")
    }

    @Test
    fun `dev 域准入名单热更即时生效（HOT 现读）`() = runBlocking {
        var users = emptyList<String>()
        val gate = KoogDevSessionGate(trustedUsersProvider = { users }, senderOpenId = "ou_test")
        assertFalse(gate.requireSessionAuthorization("dev.devices"), "初始空名单 ⇒ 拒绝")
        users = listOf("ou_test")
        assertTrue(gate.requireSessionAuthorization("dev.devices"), "加入名单后即时放行")
        users = emptyList()
        assertFalse(gate.requireSessionAuthorization("dev.devices"), "移出名单后即时收回")
    }

    /** dev.* 条件注册测试的设备源缝 fake（免真检测；装配矩阵只验注册面不触快照）。 */
    private fun fakeDeviceSource() = com.jiaoay.easy_dev_toolbox.core.llm.tools.DevTools.DeviceSource {
        com.jiaoay.easy_dev_toolbox.core.llm.tools.DevTools.DeviceSnapshot(
            devices = listOf(
                com.jiaoay.toolbelt.device.DeviceInfo("TESTSERIAL1", "Pixel Test"),
            ),
            adbExecutablePath = "",
            detectError = null,
        )
    }


    /** self.status 条件注册测试的状态源缝 fake（装配矩阵只验注册面不触快照）。 */
    private fun fakeSelfStatusSource() = com.jiaoay.easy_dev_toolbox.core.llm.tools.SelfStatusTools.SelfStatusSource {
        com.jiaoay.easy_dev_toolbox.core.llm.tools.SelfStatusTools.Snapshot(
            running = true,
            connectionState = "已连接",
            connectedSinceMillis = null,
            lastSignalAtMillis = null,
            sampledAtMillis = 1L,
            reconnectCount = 0,
            lastError = null,
            lastErrorAtMillis = null,
            counters = com.jiaoay.easy_dev_toolbox.core.llm.tools.SelfStatusTools.CounterRows(
                intakeReceived = 0, submitted = 0, completed = 0, failed = 0, accessRejected = 0,
                unmatched = 0, policyRejected = 0, duplicatesDropped = 0, queueTimeouts = 0, droppedTotal = 0,
            ),
            liveProcesses = 0,
            logSize = 0,
            configIssues = emptyList(),
            audit = null,
        )
    }

    /** build 并返回注册的 self.* 工具名集合（纯聊天形态，其余条件与 dev 矩阵同款）。 */

    /** build 并返回注册的 self.* 工具名集合（纯聊天形态，其余条件与 dev 矩阵同款）。 */
    private fun buildSelfStatusToolNames(
        nativeConfig: com.jiaoay.lark.bridge.config.BridgeConfig?,
        source: com.jiaoay.easy_dev_toolbox.core.llm.tools.SelfStatusTools.SelfStatusSource?,
    ): Set<String> {
        val payload = payloadWithLedger(RecordingReplySink(), ledger = null, confirmGate = null)
        val tools = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = payload),
            chatId = "oc_test",
            senderOpenId = "ou_test",
            nativeConfig = nativeConfig,
            selfStatusSource = source,
        ).tools.map { it.name }.toSet()
        return tools.filter { it.startsWith("self.") }.toSet()
    }

    /** build 并返回注册的 dev.* 工具名集合（纯聊天形态：带 chatId/senderOpenId/会话授权档）。 */
    private fun buildDevToolNames(
        nativeConfig: com.jiaoay.lark.bridge.config.BridgeConfig?,
        confirmGate: com.jiaoay.lark.bridge.confirm.ToolConfirmGate?,
        deviceSource: com.jiaoay.easy_dev_toolbox.core.llm.tools.DevTools.DeviceSource?,
        chatId: String = "oc_test",
        senderOpenId: String = "ou_test",
    ): Set<String> {
        val payload = payloadWithLedger(RecordingReplySink(), ledger = null, confirmGate = confirmGate)
        val tools = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = payload),
            chatId = chatId,
            senderOpenId = senderOpenId,
            nativeConfig = nativeConfig,
            trustedUsersProvider = { listOf("ou_test") },
            devDeviceSource = deviceSource,
        ).tools.map { it.name }.toSet()
        return tools.filter { it.startsWith("dev.") }.toSet()
    }

    @Test
    fun `信任用户的高风险动作免逐动作确认卡（按用户授予落地）`() = runBlocking {
        val reply = RecordingReplySink()
        val auto = AutoConfirmGate(approve = true)
        val trustedSession = KoogSysSessionGate(
            trustedUsersProvider = { listOf("ou_test") },
            senderOpenId = "ou_test",
            sysContext = com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(
                escapeGate = SysFamilyEscapeGate(),
            ),
        )
        val writeCard = com.jiaoay.easy_dev_toolbox.core.llm.tools.SysWriteTools.WriteActionCard(
            family = "fcitx5_config",
            targetPath = "/home/u/.config/fcitx5/config",
            backupPath = null,
            diff = "- a\n+ b\n",
            rollback = "回滚：删除该文件",
        )
        val trustedWriteGate = KoogSysWriteGate(trustedSession, auto.gate, reply)
        assertTrue(trustedWriteGate.requireActionApproval(writeCard), "信任用户直接执行")
        assertEquals(0, reply.sentCardSpecs.size, "信任用户不发说明卡")
        assertEquals(0, auto.requests.size, "信任用户不发确认卡")

        // 对照：同一动作、非信任用户 ⇒ 两卡照发
        val untrustedSession = KoogSysSessionGate(
            trustedUsersProvider = { listOf("ou_other") },
            senderOpenId = "ou_test",
            sysContext = com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(
                escapeGate = SysFamilyEscapeGate(),
            ),
        )
        val untrustedWriteGate = KoogSysWriteGate(untrustedSession, auto.gate, reply)
        assertTrue(untrustedWriteGate.requireActionApproval(writeCard), "非信任用户批准后放行")
        assertEquals(1, reply.sentCardSpecs.size, "非信任用户须发说明卡")
        assertEquals(1, auto.requests.size, "非信任用户须发确认卡")
    }

    @Test
    fun `sys 域准入按信任名单判定并把族根 register 进允许集`() = runBlocking {
        val sysContext = com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(
            escapeGate = SysFamilyEscapeGate(),
        )
        val gate = KoogSysSessionGate(
            trustedUsersProvider = { listOf("ou_test") },
            senderOpenId = "ou_test",
            sysContext = sysContext,
        )
        assertTrue(gate.requireSessionAuthorization("sys.probe"), "信任用户须放行")
        assertEquals(3, sysContext.allowedCount, "放行即把三条族根 register 进允许集")
        assertEquals(0, sysContext.allowedRootsCount, "族根放行严禁借道 allowedRoots（桥接链恒 0 不变量）")
        assertTrue(gate.requireSessionAuthorization("sys.read_config"), "重复授权仍放行（幂等）")
    }

    @Test
    fun `sys 域非信任用户被拒且族根不登记`() = runBlocking {
        val sysContext = com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(
            escapeGate = SysFamilyEscapeGate(),
        )
        val gate = KoogSysSessionGate(
            trustedUsersProvider = { listOf("ou_other") },
            senderOpenId = "ou_test",
            sysContext = sysContext,
        )
        assertFalse(gate.requireSessionAuthorization("sys.probe"), "非信任用户须拒绝")
        assertEquals(0, sysContext.allowedCount, "拒绝不 register 族根")
        // 族外路径恒不放行（族根判据与授权模型解耦，是越界第二道防线）
        val outside = java.io.File(System.getProperty("user.home"), "not-a-sys-family-${System.nanoTime()}")
        assertTrue(sysContext.authorizeWithGate(outside.path) == null, "族根之外的路径不放行")
    }

    @Test
    fun `写与服务动作两卡批准：说明卡承载全文独立字段 确认卡防替答裁决 说明卡失败 fail-closed`() = runBlocking {
        val auto = AutoConfirmGate(approve = true)
        val reply = RecordingReplySink()
        // 非信任用户 ⇒ 走两卡链路（信任用户免卡路径由下一个用例覆盖）
        val sessionGate = KoogSysSessionGate(
            trustedUsersProvider = { listOf("ou_other") },
            senderOpenId = "ou_test",
            sysContext = com.jiaoay.easy_dev_toolbox.core.llm.tools.ToolExecutionContext(
                escapeGate = SysFamilyEscapeGate(),
            ),
        )
        // 写入卡：超 120 字符的完整 diff 必须原文上说明卡（A2 全文独立字段——服务侧 120 字符
        // 摘要截断只作用于确认卡 inputSummary，不触说明卡 markdown）。
        val longDiff = buildString { repeat(40) { i -> appendLine("+ line$i=${"x".repeat(60)}") } }
        val writeCard = com.jiaoay.easy_dev_toolbox.core.llm.tools.SysWriteTools.WriteActionCard(
            family = "fcitx5_config",
            targetPath = "/home/u/.config/fcitx5/config",
            backupPath = "/home/u/.config/fcitx5/config.bak-x",
            diff = longDiff,
            rollback = "回滚：由用户在终端执行 cp '/home/u/.config/fcitx5/config.bak-x' 恢复快照",
        )
        val writeGate = KoogSysWriteGate(sessionGate, auto.gate, reply)
        assertTrue(writeGate.requireActionApproval(writeCard), "批准流放行")
        assertEquals(1, reply.sentCardSpecs.size, "写说明卡恰一张")
        val writeSpec = reply.sentCardSpecs.single()
        assertTrue(longDiff.trim() in writeSpec.markdown, "说明卡承载完整 diff（不经 120 字符截断）")
        assertTrue(writeCard.backupPath!! in writeSpec.markdown, "说明卡含快照路径")
        assertTrue("回滚" in writeSpec.markdown, "说明卡含回滚配方")
        assertEquals(1, auto.requests.size, "写确认卡恰一张")
        assertEquals("HIGH", auto.requests[0].riskLevel, "写确认卡风险徽标 HIGH")

        // 服务动作卡：完整 argv + DESTRUCTIVE 徽标 + 原状态 + 回滚配方全上说明卡
        val serviceCard = com.jiaoay.easy_dev_toolbox.core.llm.tools.SysServiceTools.ServiceActionCard(
            actionKey = "restart",
            label = "重启用户级服务",
            service = "fcitx5",
            argv = listOf("systemctl", "--user", "restart", "fcitx5"),
            riskBadge = "DESTRUCTIVE",
            semantics = "systemctl --user restart：服务进程中断数秒后重新拉起",
            preState = "active",
            rollback = "回滚：systemctl --user restart fcitx5（restart 幂等）",
        )
        val serviceGate = KoogSysServiceGate(sessionGate, auto.gate, reply)
        assertTrue(serviceGate.requireActionApproval(serviceCard), "服务动作批准流放行")
        assertEquals(2, reply.sentCardSpecs.size, "服务说明卡第二张")
        val serviceSpec = reply.sentCardSpecs[1]
        assertTrue("systemctl --user restart fcitx5" in serviceSpec.markdown, "说明卡含完整 argv")
        assertTrue("DESTRUCTIVE" in serviceSpec.markdown, "说明卡含 DESTRUCTIVE 徽标（L2 必标）")
        assertTrue("active" in serviceSpec.markdown, "说明卡含执行前原状态")
        assertEquals(2, auto.requests.size, "服务确认卡第二张")
        assertEquals("DESTRUCTIVE", auto.requests[1].riskLevel, "服务确认卡风险行 = DESTRUCTIVE")
        assertTrue(
            "sys.service" in auto.requests[1].toolName && "fcitx5" in auto.requests[1].toolName,
            "确认卡工具名定位到具体服务动作：${auto.requests[1].toolName}",
        )

        // 说明卡发不出（reply=null）⇒ fail-closed 拒绝且不发确认卡
        val noReplyGate = KoogSysWriteGate(sessionGate, auto.gate, reply = null)
        assertTrue(!noReplyGate.requireActionApproval(writeCard), "说明卡发送失败 = fail-closed 拒绝")
        assertEquals(2, auto.requests.size, "fail-closed 不发确认卡")
    }

    @Test
    fun `L2 提示词行挂 listed 门控（未注册不列，注册才列）`() {
        // 未注册（registeredToolNames 不含 L2 名）⇒ 提示词不出现 L2 行
        val promptOff = buildKoogSystemPrompt(
            request = request(),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet(),
        )
        assertTrue("fetch_chat_history" !in promptOff, "未注册的 L2 工具不得出现在提示词")
        // 注册后 ⇒ 出现且注明跨会话属性
        val promptOn = buildKoogSystemPrompt(
            request = request(),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet() +
                setOf("fetch_chat_history", "get_chat_info", "create_group_chat", "lookup_user", "read_wiki_document"),
        )
        assertTrue("fetch_chat_history(limit, page_token)" in promptOn, "注册后须列出")
        assertTrue("跨会话能力" in promptOn, "L2 行须注明跨会话属性")
        assertTrue("用户确认" in promptOn, "create_group_chat 行须注明强制确认")
    }

    @Test
    fun `nativeToolsProvider 经真实 run 链路生效（provider 链端到端锚）`() = runBlocking {
        LlmConfigStore.setApiKey("sk-test")
        LlmConfigStore.setModelId("gpt-test")
        var cfg: com.jiaoay.lark.bridge.config.BridgeConfig? = null // 初始未接线
        val runner = KoogAgentRunner(
            nativeToolsProvider = { cfg },
            executorFactory = fakeExecutorFactory(OkExecutor("好的")),
        )
        runner.refreshEndpoint()

        val payload = payloadWithSurface(RecordingReplySink(), FakeNativeSurface())
        // 未接线：run 正常完成（L2 不注册不影响主链——CLI/存量行为零变化锚）
        val r1 = runner.run(request(prompt = "x", opaque = payload)) { }
        assertTrue(r1.isOk, "未接线时 run 零影响：${r1.summary}")

        // 接线后（HOT 改值）：下一次 run 的注册面经 provider 链生效——build 直证
        cfg = nativeConfigAll
        val names = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = payload),
            nativeConfig = cfg,
        ).tools.map { it.name }.toSet()
        assertTrue("fetch_chat_history" in names, "provider 非空后 L2 注册生效")
    }

    // ── 会话内提醒（remind_user）─────────────────────────────────────────────────
    // 直测形态（镜像会话作用域三件先例）：不跑 Koog loop——工具 + BridgeReminderScheduler
    // 虚拟时间直证：到期透传 / scope 取消零迟到 / 超档位与超在途人话拒收 / CE 放行。

    /** 造注入 RecordingReplySink + scheduler 的 RemindUserTool（会话载体 chatId=oc_test）。 */
    private fun remindTool(sink: RecordingReplySink, scheduler: ReminderScheduler): RemindUserTool =
        RemindUserTool(
            request = request(prompt = "20分钟后提醒我", opaque = payloadWithLedger(sink, ledger = null)),
            scheduler = scheduler,
        )

    /** 造指定会话载体的 CancelReminderTool（跨会话用例需要非 oc_test 的载体）。 */
    private fun cancelTool(scheduler: ReminderScheduler, chatId: String): CancelReminderTool =
        CancelReminderTool(
            request = request(prompt = "取消刚才的提醒", opaque = payloadWithLedger(RecordingReplySink(), ledger = null, chatId = chatId)),
            scheduler = scheduler,
        )

    @Test
    fun `remind_user 到期把原文透传给本会话且回执带生命周期如实句`() = runTest {
        val sink = RecordingReplySink()
        val scheduler = BridgeReminderScheduler(backgroundScope)
        val receipt = remindTool(sink, scheduler).execute(RemindUserTool.Args(delay_minutes = 1, text = "回来看结果～"))
        assertTrue("已设定" in receipt, "受理回执须「已设定」：$receipt")
        assertTrue("会丢失" in receipt, "回执必须明示生命周期边界（不冒充可靠闹钟）：$receipt")
        assertEquals(0, sink.sentTexts.size, "未到期不得提前发送")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("回来看结果～"), sink.sentTexts.toList(), "到期文本必须原文透传不加工")
    }

    @Test
    fun `网关 scope 取消后到期提醒零迟到`() = runTest {
        val jobScope = TestScope()
        val scheduler = BridgeReminderScheduler(jobScope)
        val sink = RecordingReplySink()
        assertTrue("已设定" in remindTool(sink, scheduler).execute(RemindUserTool.Args(delay_minutes = 1, text = "x")))
        jobScope.advanceTimeBy(30_000)
        assertEquals(0, sink.sentTexts.size, "半程未到期不发")
        jobScope.cancel()
        jobScope.advanceTimeBy(60_000)
        assertEquals(0, sink.sentTexts.size, "scope 取消后到期不再发送（零迟到）")
    }

    @Test
    fun `remind_user 超档位超在途与空文本人话拒收`() = runTest {
        val sink = RecordingReplySink()
        val scheduler = BridgeReminderScheduler(backgroundScope)
        val tool = remindTool(sink, scheduler)
        assertTrue("最长 720 分钟" in tool.execute(RemindUserTool.Args(delay_minutes = 721, text = "x")), "超档位须人话拒收")
        assertTrue("至少 1 分钟" in tool.execute(RemindUserTool.Args(delay_minutes = 0, text = "x")), "低于下限须人话拒收")
        assertTrue("不能为空" in tool.execute(RemindUserTool.Args(delay_minutes = 5, text = " ")), "空文本须人话拒收")
        assertEquals(0, sink.sentTexts.size, "拒收不产生任何出站")
        // 单会话在途上限 5：前 5 条受理、第 6 条人话拒收
        repeat(5) { i ->
            assertTrue(
                "已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 10, text = "r$i")),
                "第 ${i + 1} 条应受理",
            )
        }
        val sixth = tool.execute(RemindUserTool.Args(delay_minutes = 10, text = "r5"))
        assertTrue("上限" in sixth && "已设定" !in sixth, "第 6 条须人话拒收：$sixth")
        // 到期释放占位后可再设（在途计数随 fire 归零）
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(5, sink.sentTexts.size, "5 条在途提醒全部到期发出")
        assertTrue("已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 1, text = "again")), "占位释放后应恢复受理")
    }

    /** text 出站抛 CE 的 ReplySink fake（其余成员委托 RecordingReplySink 默认实例）。 */
    private class CancellingTextReplySink : com.jiaoay.lark.bridge.pipeline.ReplySink by RecordingReplySink() {
        override suspend fun text(body: String): com.jiaoay.lark.gateway.SendResult =
            throw CancellationException("cancelled in flight")
    }

    /** text 出站在途挂起的 ReplySink fake：记录后挂 gate.await()——出站请求已派发、响应未返回的虚拟时间替身。 */
    private class InflightTextReplySink(
        private val gate: CompletableDeferred<Unit>,
        val recorder: RecordingReplySink = RecordingReplySink(),
    ) : com.jiaoay.lark.bridge.pipeline.ReplySink by recorder {
        override suspend fun text(body: String): com.jiaoay.lark.gateway.SendResult {
            recorder.sentTexts += body
            gate.await()
            return com.jiaoay.lark.gateway.SendResult(ok = true, messageId = "m_text")
        }
    }

    @Test
    fun `到期发送抛 CancellationException 时放行不炸且在途占位释放`() = runTest {
        val jobScope = TestScope()
        val scheduler = BridgeReminderScheduler(jobScope)
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = CancellingTextReplySink(),
            allowedFiles = emptyList(),
            xlogPrivateKey = "",
            messageId = "om_user_msg",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val tool = RemindUserTool(request = request(prompt = "x", opaque = payload), scheduler = scheduler)
        assertTrue("已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 1, text = "x")))
        // fire 抛 CE ⇒ 协程按取消终结（CE 原样放行，不被兜底 catch 吞成普通失败）
        jobScope.advanceTimeBy(60_000)
        jobScope.runCurrent()
        // 占位经 finally 释放——第 2 条仍可受理（未释放则命中在途上限被拒）
        assertTrue("已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 1, text = "y")), "CE 路径须释放占位")
        jobScope.cancel()
    }

    // ── 提醒撤销（cancel_reminder）───────────────────────────────────────────────

    @Test
    fun `受理回执带编号且按编号撤销后到期不发`() = runTest {
        val sink = RecordingReplySink()
        val scheduler = BridgeReminderScheduler(backgroundScope)
        val receipt = remindTool(sink, scheduler).execute(RemindUserTool.Args(delay_minutes = 1, text = "该撤了"))
        assertTrue("提醒 #1" in receipt, "受理回执须带可指代编号：$receipt")
        assertTrue("cancel_reminder" in receipt, "回执须指引撤销工具（LLM 撤销时需要知道入口）：$receipt")
        val cancelReceipt = cancelTool(scheduler, "oc_test").execute(CancelReminderTool.Args(reminder_id = 1))
        assertTrue("已取消提醒 #1" in cancelReceipt, "按编号撤销须成功回执：$cancelReceipt")
        assertTrue("不会再响起了" in cancelReceipt, "未点火真取消的回执须兑现承诺句：$cancelReceipt")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, sink.sentTexts.size, "已撤销的提醒到期不得再发（fire 零执行负断言）")
    }

    @Test
    fun `不存在或已到期的编号人话拒绝`() = runTest {
        val sink = RecordingReplySink()
        val scheduler = BridgeReminderScheduler(backgroundScope)
        val cancel = cancelTool(scheduler, "oc_test")
        val miss = cancel.execute(CancelReminderTool.Args(reminder_id = 9))
        assertTrue("没找到" in miss && "已取消提醒" !in miss, "不存在的编号须人话拒绝：$miss")
        assertTrue("已设定" in remindTool(sink, scheduler).execute(RemindUserTool.Args(delay_minutes = 1, text = "x")))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, sink.sentTexts.size, "对照组：未撤销的提醒到期正常发出")
        val fired = cancel.execute(CancelReminderTool.Args(reminder_id = 1))
        assertTrue("没找到" in fired && "可能已到期" in fired, "已到期出表的编号须按人话拒绝：$fired")
        assertEquals(ReminderCancelResult.NotFound, scheduler.cancel("oc_test", 1), "fire 完成后撤销须维持「没找到」现状语义（密封态直证）")
    }

    @Test
    fun `跨会话编号人话拒绝且不影响原会话提醒`() = runTest {
        val sink = RecordingReplySink()
        val scheduler = BridgeReminderScheduler(backgroundScope)
        assertTrue("已设定" in remindTool(sink, scheduler).execute(RemindUserTool.Args(delay_minutes = 10, text = "x")))
        // 会话作用域硬边界：oc_other 载体查 oc_test 的编号必须 miss（job 表按 chatId 分表）
        val crossChat = cancelTool(scheduler, "oc_other").execute(CancelReminderTool.Args(reminder_id = 1))
        assertTrue("没找到" in crossChat, "跨会话撤销必须查不到：$crossChat")
        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(1, sink.sentTexts.size, "跨会话撤销不得影响原会话的在途提醒")
    }

    @Test
    fun `撤销释放占位后在途上限恢复受理`() = runTest {
        val sink = RecordingReplySink()
        val scheduler = BridgeReminderScheduler(backgroundScope)
        val tool = remindTool(sink, scheduler)
        repeat(5) { i ->
            assertTrue("已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 10, text = "r$i")), "第 ${i + 1} 条应受理")
        }
        assertTrue("上限" in tool.execute(RemindUserTool.Args(delay_minutes = 10, text = "r5")), "在途满 5 后须拒收")
        assertTrue(
            "已取消提醒 #3" in cancelTool(scheduler, "oc_test").execute(CancelReminderTool.Args(reminder_id = 3)),
            "撤销第 3 条须成功",
        )
        // 撤销即同步释放占位（cancel 路径独立于 job 的 finally——job 尚在启动队列被取消时 finally 不可达）
        assertTrue(
            "已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 10, text = "again")),
            "撤销释放占位后须恢复受理（在途上限 5 语义不变）",
        )
    }

    @Test
    fun `在途撤销走 Job cancel 后 fire 闭包不可达且占位释放恰好一次`() = runTest {
        val jobScope = TestScope()
        val scheduler = BridgeReminderScheduler(jobScope)
        val sink = RecordingReplySink()
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = sink,
            allowedFiles = emptyList(),
            xlogPrivateKey = "",
            messageId = "om_user_msg",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val tool = RemindUserTool(request = request(prompt = "x", opaque = payload), scheduler = scheduler)
        assertTrue("已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 1, text = "到点文本")))
        assertEquals(ReminderCancelResult.Cancelled, scheduler.cancel("oc_test", 1), "在途撤销应命中 job 表并按未点火真取消回执")
        jobScope.advanceTimeBy(60_000)
        jobScope.runCurrent()
        // job.cancel 在 delay 挂起点抛 CE（fire 不可达）并按既有取消传播口径终结，不进 fire 兜底 catch
        assertEquals(0, sink.sentTexts.size, "fire 随 job 取消不可达（到期不得再发）")
        // cancel 同步释放 + finally 兜底经 CAS 收敛恰好一次——释放后第 2 条仍可受理（多释放则会击穿上限语义之外，少释放则被上限拒收）
        assertTrue("已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 1, text = "y")), "取消路径须释放占位")
        jobScope.cancel()
    }

    @Test
    fun `点火在途撤销回执如实报在途且不冒充取消成功`() = runTest {
        val jobScope = TestScope()
        val scheduler = BridgeReminderScheduler(jobScope)
        val gate = CompletableDeferred<Unit>()
        val sink = InflightTextReplySink(gate)
        val payload = com.jiaoay.lark.bridge.pipeline.DispatchPayload(
            reply = sink,
            allowedFiles = emptyList(),
            xlogPrivateKey = "",
            messageId = "om_user_msg",
            chatId = "oc_test",
            quotedMessageId = null,
            replyLedger = null,
        )
        val tool = RemindUserTool(request = request(prompt = "x", opaque = payload), scheduler = scheduler)
        assertTrue("已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 1, text = "在途文本")))
        // 推进到点火完成：fire 已执行进 sink.text 挂起点（出站请求已派发、送达在途）
        jobScope.advanceTimeBy(60_000)
        jobScope.runCurrent()
        assertEquals(listOf("在途文本"), sink.recorder.sentTexts.toList(), "点火后出站请求须已派发（fire 已执行到挂起点）")
        val receipt = cancelTool(scheduler, "oc_test").execute(CancelReminderTool.Args(reminder_id = 1))
        assertTrue("已在送达途中" in receipt, "点火在途撤销须如实报在途：$receipt")
        assertTrue("已取消提醒 #1" !in receipt, "在途回执不得冒充取消成功（回执如实面）：$receipt")
        // 已取消 job 的挂起点按取消终结；再推进虚拟时间——在途文本恰送达一次，零迟到重复
        jobScope.runCurrent()
        jobScope.advanceTimeBy(60_000)
        jobScope.runCurrent()
        assertEquals(listOf("在途文本"), sink.recorder.sentTexts.toList(), "在途出站不可撤销——文本恰送达一次（零迟到重复）")
        // 在途撤销路径占位释放恰好一次（cancel 同步释放 + finally 兜底经 CAS 收敛）——第 2 条仍可受理
        assertTrue("已设定" in tool.execute(RemindUserTool.Args(delay_minutes = 1, text = "y")), "在途撤销路径须释放占位")
        jobScope.cancel()
    }

    @Test
    fun `reminderScheduler 注入才注册 remind_user 与 cancel_reminder 且不进常备清单`() {
        val payload = payloadWithLedger(RecordingReplySink(), ledger = null)
        fun names(scheduler: ReminderScheduler?): Set<String> = BridgeToolRegistry.build(
            endpointConfig = com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(
                baseUrl = "http://localhost:1", apiKey = "sk-test", modelId = "fake-model", timeoutSeconds = 30,
            ),
            request = request(prompt = "x", opaque = payload),
            reminderScheduler = scheduler,
        ).tools.map { it.name }.toSet()
        assertTrue("remind_user" in names(BridgeReminderScheduler(TestScope())), "注入后须注册")
        assertTrue("cancel_reminder" in names(BridgeReminderScheduler(TestScope())), "注入后撤销工具须同条件注册（回执带编号却无工具可撤即能力面错位）")
        assertTrue("remind_user" !in names(null), "未注入（CLI 形态/存量测试）须不注册")
        assertTrue("cancel_reminder" !in names(null), "未注入须连撤销工具一起不注册")
        assertTrue("remind_user" !in BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS, "条件注册不进常备清单（L2 同口径）")
        assertTrue("cancel_reminder" !in BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS, "条件注册不进常备清单（L2 同口径）")
    }

    @Test
    fun `remind_user 提示词行挂 listed 门控且带生命周期句`() {
        // 未注册（scheduler 未注入——registeredToolNames 不含它）⇒ 提示词不出现
        val promptOff = buildKoogSystemPrompt(
            request = request(),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet(),
        )
        assertTrue("remind_user" !in promptOff, "未注册的提醒工具不得出现在提示词")
        assertTrue("cancel_reminder" !in promptOff, "未注册的撤销工具不得出现在提示词")
        // 注册后 ⇒ 出现且带生命周期如实句
        val promptOn = buildKoogSystemPrompt(
            request = request(),
            knowledgeAvailable = false,
            stylePrompt = null,
            safetyPrompt = null,
            registeredToolNames = BridgeToolRegistry.ALWAYS_REGISTERED_TOOLS.toSet() + setOf("remind_user", "cancel_reminder"),
        )
        assertTrue("remind_user(delay_minutes, text)" in promptOn, "注册后须列出")
        assertTrue("cancel_reminder(reminder_id)" in promptOn, "注册后撤销行须列出（与 remind_user 同 listed 门控）")
        assertTrue("会丢失" in promptOn, "提示词行须明示提醒生命周期边界")
    }
}
