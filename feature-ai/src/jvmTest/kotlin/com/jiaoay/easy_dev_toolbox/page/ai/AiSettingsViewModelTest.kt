package com.jiaoay.easy_dev_toolbox.page.ai

import ai.koog.utils.time.KoogClock
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.navigation.ToolUsageSignal

/**
 * AiSettingsViewModel 探测逻辑（runConnectivityProbe 为 internal 测试接缝，注入 fake executor，零真实网络）：
 * - 成功：返回 Ok（modelId + 回复预览截断）；
 * - 异常：返回 Failed（消息截断 200）；
 * - 空凭据：testConnectivity 前置短路，不发请求；
 * - 开关开时校验（批 4b）：未配置同步拒绝 / 探测失败保持关闭 / 探测成功开启。
 *
 * 🔴 Main dispatcher 必须替换（viewModelScope = Dispatchers.Main.immediate）：不 setMain
 *    时 viewModelScope.launch 的协程不执行，凡断言协程结果的用例必假失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {

    // 🔴 setUp 持有调度器引用：tearDown 先 drain 在途协程再 resetMain——不 setMain
    //    时 viewModelScope.launch 的协程不执行，凡断言协程结果的用例必假失败。
    private val dispatcher = StandardTestDispatcher()

    /**
     * DataStore IO 受控 scope（LocalDataManager 的 datastoreScope 注入缝）：
     * Unconfined 让 DataStore 文件 IO 在调用线程同步落定——drain 即全静默，
     * 无真实 IO 线程续体晚于断言落回 Main 队列的竞态（resetMain 前零真实 sleep）。
     * 🔴 不得改与 Main 共享 StandardTestDispatcher 的 scope：DataStore 挂起读写经
     *    withContext 跳到注入 scope，本类 awaitStored 的 runBlocking 谓词读会因
     *    调度器无人推进而死锁。
     */
    private val datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var originalStore: com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
    private lateinit var classStore: com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager

    @BeforeTest
    fun setUpMain() {
        Dispatchers.setMain(dispatcher)
        // 类级隔离：未走 isolatedStore() 的用例（VM init 恢复读）也不触真实用户数据
        originalStore = LlmConfigStore.store
        classStore = com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager(
            dataStoreName = "test_ai_class_${java.util.UUID.randomUUID()}",
            datastoreDir = java.nio.file.Files.createTempDirectory("edt-ai-test").toFile(),
            datastoreScope = datastoreScope,
        )
        LlmConfigStore.store = classStore
    }

    @AfterTest
    fun tearDownMain() {
        // DataStore IO 已随 Unconfined scope 同步落定——drain 只排空滞留协程即静默
        dispatcher.scheduler.advanceUntilIdle()
        LlmConfigStore.store = originalStore
        Dispatchers.resetMain()
    }

    /** fake：execute 恒返回固定文本 */
    private class OkExecutor(private val reply: String) : MultiLLMPromptExecutor(emptyMap()) {
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = Message.Assistant(
            content = reply,
            metaInfo = ResponseMetaInfo.create(KoogClock.System),
        )
    }

    /** fake：execute 恒抛异常 */
    private class FailingExecutor(private val error: Exception) : MultiLLMPromptExecutor(emptyMap()) {
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant = throw error
    }

    /** fake：execute 挂起至 gate 放行再返回（探测在途竞态测试缝） */
    private class GatedExecutor(private val gate: kotlinx.coroutines.CompletableDeferred<Unit>) :
        MultiLLMPromptExecutor(emptyMap()) {
        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): Message.Assistant {
            gate.await()
            return Message.Assistant(content = "pong", metaInfo = ResponseMetaInfo.create(KoogClock.System))
        }
    }

    private val model = OpenAIModels.Chat.GPT4o

    @Test
    fun `probe returns Ok with reply preview on success`() = runTest {
        val vm = AiSettingsViewModel()
        val cfg = LlmEndpointConfig("https://gw", "sk-1", "m1", 60)
        val probe = vm.runConnectivityProbe(cfg, OkExecutor("pong"), model)

        assertTrue(probe is ConnectivityProbe.Ok)
        assertEquals("m1", probe.modelId)
        assertEquals("pong", probe.replyPreview)
    }

    @Test
    fun `probe truncates long reply to preview limit`() = runTest {
        val vm = AiSettingsViewModel()
        val longReply = "x".repeat(200)
        val probe = vm.runConnectivityProbe(
            LlmEndpointConfig("https://gw", "sk-1", "m1", 60),
            OkExecutor(longReply),
            model,
        )

        assertTrue(probe is ConnectivityProbe.Ok)
        assertEquals(40, probe.replyPreview.length)
    }

    @Test
    fun `probe returns Failed with error message on exception`() = runTest {
        val vm = AiSettingsViewModel()
        val probe = vm.runConnectivityProbe(
            LlmEndpointConfig("https://gw", "sk-1", "m1", 60),
            FailingExecutor(java.io.IOException("Connection refused")),
            model,
        )

        assertTrue(probe is ConnectivityProbe.Failed)
        assertTrue("Connection refused" in probe.message)
    }

    @Test
    fun `updateConfig skips persist when value unchanged`() = runTest {
        // 计数 seam（零生产改动）：updateConfig 非短路路径必发 ToolUsageSignal.report(
        // AI_SETTINGS)——「保存配置 = 一次真实使用」。订阅信号流计次，形态对齐
        // LarkBridgeViewModelTest.sameValueCommitShortCircuitsPersist 的 FakeStore.applyCount。
        // 🔴 订阅必须 UNDISPATCHED：collect 须在首次 report 前注册（replay=0，无订阅者时
        //    tryEmit 静默丢流）；实测 backgroundScope.launch 的订阅者到 advanceUntilIdle
        //    后仍未激活，计数恒空。
        val store = isolatedStore()
        val reports = mutableListOf<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            ToolUsageSignal.signals.collect { reports += it }
        }
        try {
            val vm = AiSettingsViewModel()
            vm.onApiKeyChange("") // 默认即空串，transform 后仍等于当前值 → 短路
            advanceUntilIdle()
            assertEquals(0, reports.size, "同值提交零副作用（不落盘、不计使用）")

            vm.onApiKeyChange("sk-1") // 判别面对照：变更提交必走非短路路径
            advanceUntilIdle()
            assertEquals(listOf(ToolUsageSignal.PageId.AI_SETTINGS), reports, "变更提交 = 一次真实使用")
        } finally {
            collector.cancel()
            restoreStore(store)
        }
    }

    @Test
    fun `empty credentials yields Failed without network`() = runTest {
        val vm = AiSettingsViewModel()
        vm.testConnectivity()
        // testConnectivity 对未配置状态同步置 Failed（无 IO）——首帧 StateFlow 即可见
        assertTrue(vm.connectivity.value is ConnectivityProbe.Failed)
        assertEquals("请先填写 API Key 与模型 ID", (vm.connectivity.value as ConnectivityProbe.Failed).message)
    }

    // ── 开关开时校验（批 4b，2026-09-13 拍板：失败拒绝开启） ──

    /**
     * 等待探测落定：runConnectivityProbe 在真实 Dispatchers.Default 执行（withContext），
     * 测试调度器的虚拟时钟推不动它——轮询直到反馈离开 Probing（上限 5s，批 1
     * WsDispatcherTest 同款配方：latch/单次 advance 皆有「block 完成到状态落定」窗口）。
     */
    private fun kotlinx.coroutines.test.TestScope.awaitProbeSettled(vm: AiSettingsViewModel) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (vm.aiToggleFeedback.value is AiToggleFeedback.Probing && System.nanoTime() < deadline) {
            advanceUntilIdle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `toggling on with unconfigured endpoint is rejected without probe`() = runTest {
        val store = isolatedStore()
        try {
            AiToolsToggle.setEnabledForTest(false)
            val vm = AiSettingsViewModel() // config 全空（默认构造即未配置）
            vm.probeExecutorFactory = { throw AssertionError("未配置端点不得触发探测") }
            vm.setAiToolsEnabled(true)

            // 未配置 → 同步拒绝（无网络探测），开关保持关闭，反馈给出原因
            assertEquals(false, AiToolsToggle.enabled.value, "未配置端点时拨开不得开启")
            val feedback = vm.aiToggleFeedback.value
            assertTrue(feedback is AiToggleFeedback.Failed, "未配置端点须给出失败反馈")
            assertTrue("API Key" in feedback.message, "失败原因须指向缺失的凭据")
        } finally {
            restoreStore(store)
            AiToolsToggle.setEnabledForTest(false) // 恢复到新进程默认（fail-closed）
        }
    }

    @Test
    fun `toggling on with failing endpoint keeps toggle off and surfaces reason`() = runTest {
        val store = isolatedStore()
        try {
            AiToolsToggle.setEnabledForTest(false)
            val vm = AiSettingsViewModel()
            // 测试接缝：注入恒失败 executor（零真实网络）
            vm.probeExecutorFactory = { FailingExecutor(java.io.IOException("Connection refused")) to model }
            vm.onApiKeyChange("sk-1")
            vm.onModelIdChange("m1")

            vm.setAiToolsEnabled(true)

            assertEquals(true, vm.aiToggleFeedback.value is AiToggleFeedback.Probing, "探测期间反馈为 Probing")
            awaitProbeSettled(vm)

            assertEquals(false, AiToolsToggle.enabled.value, "探测失败后开关必须保持关闭")
            val feedback = vm.aiToggleFeedback.value as AiToggleFeedback.Failed
            assertTrue("Connection refused" in feedback.message, "失败原因须回传到开关反馈")
            assertTrue(vm.connectivity.value is ConnectivityProbe.Failed, "失败同时同步到 connectivity（单一事实源）")
        } finally {
            restoreStore(store)
            AiToolsToggle.setEnabledForTest(false) // 恢复到新进程默认（fail-closed）
        }
    }

    @Test
    fun `toggling on with reachable endpoint opens the toggle`() = runTest {
        val store = isolatedStore()
        try {
            AiToolsToggle.setEnabledForTest(false)
            val vm = AiSettingsViewModel()
            // 测试接缝：注入恒成功 executor
            vm.probeExecutorFactory = { OkExecutor("pong") to model }
            vm.onApiKeyChange("sk-1")
            vm.onModelIdChange("m1")

            vm.setAiToolsEnabled(true)
            awaitProbeSettled(vm)

            assertEquals(true, AiToolsToggle.enabled.value, "探测成功后开关须落盘开启")
            assertTrue(vm.aiToggleFeedback.value is AiToggleFeedback.Opened, "成功反馈为 Opened")
        } finally {
            restoreStore(store)
            AiToolsToggle.setEnabledForTest(false) // 恢复到新进程默认（fail-closed）
        }
    }

    @Test
    fun `toggling off needs no probe`() = runTest {
        val store = isolatedStore()
        try {
            // 🔴 前置必须是 true（开着的时候拨关）——若前置为 false 则与断言同值，断言恒真失去判别力。
            AiToolsToggle.setEnabledForTest(true)
            val vm = AiSettingsViewModel()
            vm.probeExecutorFactory = { throw AssertionError("关闭方向不得触发探测") }
            vm.onApiKeyChange("sk-1")
            vm.onModelIdChange("m1")

            vm.setAiToolsEnabled(false)
            advanceUntilIdle()

            assertEquals(false, AiToolsToggle.enabled.value, "关闭须即时生效（从 true 翻到 false）")
        } finally {
            restoreStore(store)
            AiToolsToggle.setEnabledForTest(false) // 恢复到新进程默认（fail-closed）
        }
    }

    /** A2 · 探测中拨关：在途探测被取消，迟到成功不得反向覆写 OFF。 */
    @Test
    fun `toggling off during probe cancels probe and blocks late success override`() = runTest {
        val store = isolatedStore()
        try {
            AiToolsToggle.setEnabledForTest(false)
            val vm = AiSettingsViewModel()
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            vm.probeExecutorFactory = { GatedExecutor(gate) to model }
            vm.onApiKeyChange("sk-1")
            vm.onModelIdChange("m1")

            vm.setAiToolsEnabled(true)
            assertTrue(vm.aiToggleFeedback.value is AiToggleFeedback.Probing, "前置：探测中")

            // 探测在途拨关：探测协程被取消 + 意图代号作废
            vm.setAiToolsEnabled(false)
            advanceUntilIdle()
            assertEquals(AiToggleFeedback.Idle, vm.aiToggleFeedback.value, "拨关复位探测反馈")

            // 放行在途探测（若取消未及时观察）——迟到成功不得落开关/写 Opened
            gate.complete(Unit)
            awaitProbeSettled(vm)
            assertEquals(false, AiToolsToggle.enabled.value, "拨关后迟到成功不得反向覆写 OFF")
            assertTrue(vm.aiToggleFeedback.value !is AiToggleFeedback.Opened, "迟到成功不得写 Opened 反馈")
        } finally {
            restoreStore(store)
            AiToolsToggle.setEnabledForTest(false) // 恢复到新进程默认（fail-closed）
        }
    }

    /** A2 · 探测在途再入拒绝：第二次拨开不发起第二个探测（单句柄判据）。 */
    @Test
    fun `re-entry during probe is rejected without second probe`() = runTest {
        val store = isolatedStore()
        try {
            AiToolsToggle.setEnabledForTest(false)
            val vm = AiSettingsViewModel()
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            var probeStarted = 0
            vm.probeExecutorFactory = {
                probeStarted++
                GatedExecutor(gate) to model
            }
            vm.onApiKeyChange("sk-1")
            vm.onModelIdChange("m1")

            vm.setAiToolsEnabled(true)
            vm.setAiToolsEnabled(true) // 探测在途再入
            advanceUntilIdle()
            assertEquals(1, probeStarted, "探测在途不得发起第二个探测（probeJob 活性判据）")
            gate.complete(Unit)
            awaitProbeSettled(vm)
            assertEquals(true, AiToolsToggle.enabled.value, "首次探测成功仍正常落开关")
        } finally {
            restoreStore(store)
            AiToolsToggle.setEnabledForTest(false) // 恢复到新进程默认（fail-closed）
        }
    }

    // ── 模型 ID 输入形态状态机（下拉为主 + 手动兜底） ──
    //
    // 🔴 临时目录隔离 store：VM 的 init 会异步读全量配置回填，不隔离 store 会读到
    //    真实用户 DataStore，init 协程把测试刚设的值覆盖回去（首次运行实测踩过）。

    /**
     * 隔离 store 的还原句柄：[store] 原 store + [isolated] 本用例隔离实例 +
     * [dir] 注入的数据目录 + [home] `user.home` 隔离器。
     *
     * 🔴 滞留写断言必须读 [isolated]（写发生时经 seam 落在隔离实例），不得读 [store]——
     *     原实例在用例窗口内零读写，读它只能验证到无关状态。
     *
     * 🔴 为什么**不**持有 tempHome 去删：本类每个用例都 new 一个
     *     [com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager]，而它内部的 `DataStore` 是
     *     `by lazy` 构造、**没有 close/dispose**——注入的 datastoreScope（Unconfined +
     *     SupervisorJob）随实例长命存活，drain 后虽无真实线程在途写，还原后仍可能被
     *     迟到触发的读链带出落盘；抽掉目录会让这些迟到首操作撞不存在的父目录。
     *     ⇒ 目录留在 `/tmp` 由系统清理，用例只负责还原 `user.home` 与 store 引用。
     */
    private data class IsolatedStore(
        val store: com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager,
        val isolated: com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager,
        val dir: java.io.File,
        val home: com.jiaoay.testfixtures.UserHomeIsolation,
    )

    /**
     * 隔离 store 配方（跨模块复用不要求逐字照抄：测试拓扑不同时取最小等效修 =
     * 构造期注入 datastoreDir（LocalDataManager 的注入参数）+ 保留本拓扑的
     * 收束纪律；防护等效判据 = 真实数据目录零增长）：
     * redirect 后必须**立刻** eager 解析数据目录并注入构造——DataStore 的落盘路径在该实例
     * 首次读写时才解析，构造期不钉死目录的话，还原 `user.home` 后才执行的首操作会把
     * `test_vm_*` 文件落进真实数据目录（`~/.easy_dev_toolbox/.datastore/`）。
     */
    private fun isolatedStore(): IsolatedStore {
        val home = com.jiaoay.testfixtures.UserHomeIsolation("edt-vm-test")
        home.redirect()
        val dir = com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getDatastoreDir()
        val original = LlmConfigStore.store
        val store = com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager(
            dataStoreName = "test_vm_${java.util.UUID.randomUUID()}",
            datastoreDir = dir,
            datastoreScope = datastoreScope,
        )
        LlmConfigStore.store = store
        return IsolatedStore(original, store, dir, home)
    }

    /**
     * 还原 store 引用与 `user.home`。🔴 还原**前**必须先排空测试调度器：滞留的持久化
     * 协程若在还原后才执行，会经已换回的全局 store 写真实数据目录（且 DataStore 落盘
     * 路径随还原漂移）。drain 后在途 IO 已被注入目录钉死，还原不再改变落点。
     * （🔴 **不删临时目录**——理由见 [IsolatedStore] KDoc。）
     */
    private fun restoreStore(original: IsolatedStore) {
        dispatcher.scheduler.advanceUntilIdle()
        LlmConfigStore.store = original.store
        original.home.restoreOnly()
    }

    /**
     * 等 DataStore 写落定。隔离 store 已注入 Unconfined scope——文件 IO 在调用线程
     * 同步落定，单次 advance 虚拟调度器（驱动 VM 的 launch）后写即已到位；循环 +
     * runBlocking 谓词读是跨调度器链路的兜底形态（上限 [seconds] 秒防死等）。
     */
    private fun kotlinx.coroutines.test.TestScope.awaitStored(seconds: Long = 5, predicate: suspend () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            advanceUntilIdle()
            val ok = kotlinx.coroutines.runBlocking { predicate() }
            if (ok) return
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertTrue(kotlinx.coroutines.runBlocking { predicate() }, "DataStore 写未在 ${seconds}s 内落定")
    }

    // ── 真实数据目录零增长守护（隔离配方红线；跨模块复用同此判据） ──

    /**
     * 复刻泄漏时序的端到端守护：持久化协程入队后**刻意不 advance** 即走 [restoreStore]。
     * 配方破缺即红——目录未注入构造：滞留首操作把 `test_vm_*` 文件落进真实数据目录
     * （文件集新增）；还原前未 drain：滞留写经换回后的全局 store 落盘（隔离 store 缓存为空）。
     */
    @Test
    fun `isolated store recipe keeps real datastore dir untouched`() = runTest {
        val realDir = com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getDatastoreDir()
        val before = realDir.datastoreFileNames()
        val store = isolatedStore()
        try {
            val vm = AiSettingsViewModel()
            vm.onModelPicked("mode-coding") // 持久化协程入队——复刻泄漏时序，刻意不 advance
        } finally {
            restoreStore(store)
        }
        // 滞留写在 drain 中已启动，等真实 IO 落进注入目录再取终态
        awaitStored { store.dir.hasNonEmptyPreferencesFile() }
        assertEquals("mode-coding", store.isolated.getCached("llm_model_id"), "滞留持久化须写隔离 store（非还原后的全局 store）")
        assertEquals(before, realDir.datastoreFileNames(), "真实数据目录零增长（文件集零新增）")
    }

    /** 目录文件名集快照（守护基线）。 */
    private fun java.io.File.datastoreFileNames(): Set<String> =
        listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()

    /** 注入目录里是否已有非空 preferences 文件（真实 IO 落定判据）。 */
    private fun java.io.File.hasNonEmptyPreferencesFile(): Boolean =
        listFiles().orEmpty().any { it.isFile && it.name.contains(".preferences_pb") && it.length() > 0L }

    @Test
    fun `manual mode toggles and falls back to dropdown on non blank commit`() = runTest {
        val store = isolatedStore()
        try {
            val vm = AiSettingsViewModel()

            // 初始为下拉形态
            assertEquals(false, vm.manualMode.value)

            // 点「手动输入」→ 切文本框
            vm.onManualInput()
            assertEquals(true, vm.manualMode.value)
            assertEquals(false, vm.pickerExpanded.value, "切手动时须收起下拉")

            // 手动提交非空值 → 回落下拉形态且配置已更新
            vm.onManualModelCommitted("my-custom-model")
            assertEquals(false, vm.manualMode.value)
            assertEquals("my-custom-model", vm.config.value.modelId)

            // 再切手动提交空值 → 保持手动形态（允许先清空）
            vm.onManualInput()
            vm.onManualModelCommitted("")
            assertEquals(true, vm.manualMode.value, "空值提交须保持手动形态")
        } finally {
            restoreStore(store)
        }
    }

    @Test
    fun `picking from catalog exits manual mode and persists selection`() = runTest {
        val store = isolatedStore()
        try {
            val vm = AiSettingsViewModel()

            vm.onManualInput()
            assertEquals(true, vm.manualMode.value)

            vm.onModelPicked("mode-coding")

            assertEquals(false, vm.manualMode.value, "pick 须退出手动形态")
            assertEquals(false, vm.pickerExpanded.value, "pick 须收起下拉")
            assertEquals("mode-coding", vm.config.value.modelId)
        } finally {
            restoreStore(store)
        }
    }

    // ── Koog agent 性格模式/安全约束（2026-09-14 · 独立 StateFlow + 异步落盘） ──

    @Test
    fun `agent style and safety init backfill from store`() = runTest {
        val store = isolatedStore()
        try {
            // 先写盘再建 VM：DataStore 写在真实 IO 线程，写落定后才 init 回填（防读到旧值）
            LlmConfigStore.setAgentStyleId("catgirl")
            LlmConfigStore.setAgentStyleCustom("用猫娘口吻")
            LlmConfigStore.setAgentSafetyId("custom")
            LlmConfigStore.setAgentSafetyCustom("只许读取")
            awaitStored { LlmConfigStore.getAgentStyleId() == "catgirl" && LlmConfigStore.getAgentSafetyCustom() == "只许读取" }

            val vm = AiSettingsViewModel()
            // init 回填是 viewModelScope 异步 + DataStore 读走真实 IO 线程——虚拟时钟推不动整条链
            awaitStored { vm.agentStyleId.value == "catgirl" && vm.agentSafetyId.value == "custom" }
            assertEquals("catgirl", vm.agentStyleId.value)
            assertEquals("用猫娘口吻", vm.agentStyleCustom.value)
            assertEquals("custom", vm.agentSafetyId.value)
            assertEquals("只许读取", vm.agentSafetyCustom.value)
        } finally {
            restoreStore(store)
        }
    }

    @Test
    fun `agent style commit updates flow and persists`() = runTest {
        val store = isolatedStore()
        try {
            val vm = AiSettingsViewModel()

            vm.onAgentStyleIdChange("professional")
            assertEquals("professional", vm.agentStyleId.value, "StateFlow 先更新")
            awaitStored { LlmConfigStore.getAgentStyleId() == "professional" }

            // 值未变短路：重复提交同值不再触发落盘路径（短路在 StateFlow 比较层）
            vm.onAgentStyleCustomChange("认真严谨")
            vm.onAgentStyleCustomChange("认真严谨")
            assertEquals("认真严谨", vm.agentStyleCustom.value)
            awaitStored { LlmConfigStore.getAgentStyleCustom() == "认真严谨" }
        } finally {
            restoreStore(store)
        }
    }

    @Test
    fun `agent safety custom commit persists blank as-is`() = runTest {
        // 留空 = 不注入安全段——空串也要如实落盘（resolver 端 null 语义由 Presets 测试背书）
        val store = isolatedStore()
        try {
            val vm = AiSettingsViewModel()
            vm.onAgentSafetyIdChange("custom")
            vm.onAgentSafetyCustomChange("")
            awaitStored { LlmConfigStore.getAgentSafetyId() == "custom" }
            assertEquals("", LlmConfigStore.getAgentSafetyCustom())
        } finally {
            restoreStore(store)
        }
    }
}
