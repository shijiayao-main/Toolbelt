package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.lark.bridge.BridgeStartResult
import com.jiaoay.lark.bridge.ConfigApplyResult
import com.jiaoay.lark.bridge.config.BridgeConfig
import com.jiaoay.lark.bridge.config.BridgeErrorCode
import com.jiaoay.lark.bridge.config.BridgeField
import com.jiaoay.lark.bridge.config.ConfigGroup
import com.jiaoay.lark.bridge.config.ConnectionConfig
import com.jiaoay.lark.bridge.config.DefaultBridgeConfig
import com.jiaoay.lark.bridge.config.Editor
import com.jiaoay.lark.bridge.observe.BridgeStatusSnapshot
import com.jiaoay.lark.bridge.observe.EnvironmentProbe
import com.jiaoay.lark.bridge.observe.LogBus
import com.jiaoay.lark.gateway.SendResult
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File

/**
 * [LarkBridgeViewModel] 写入路径与运行指令契约测试（方案「配置模型：单一 JSON 文档」小节）。
 *
 * 🔒 钉死的核心不变量：
 * 1. **draft 即时更新**：[LarkBridgeViewModel.updateField] / [LarkBridgeViewModel.updateConfig] 同步改 draft 并重算 issues；
 * 2. **400ms trailing debounce**：高频键击合并为一次落盘；
 * 3. **drop(1) 仅打开不落盘**：构造 VM（初始值）经 debounce 窗口后 applyConfig 计数 == 0（不生成 bridge-config.json）；
 * 4. **校验失败不覆写**：issues 非空时 persist 中止，applyConfig 计数 == 0；
 * 5. **同值短路**：相同值提交零副作用（不落盘）；
 * 6. **挂起纪律**：onStart/onStop 经 viewModelScope 转发 store.start/stop 并回写结果。
 * （routes 编辑四方法用例随功能退役删除——routes 匹配键已退役。）
 *
 * 🔴 用 [StandardTestDispatcher] 虚拟时间精确驱动 debounce，零真实延迟、零真实文件系统
 * （[FakeStore] 内存计数；dataRoot 仅返回临时路径，不 mkdirs、不写盘）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LarkBridgeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** connection.testChatId 的字段声明（HOT、Text），用于驱动 [LarkBridgeViewModel.updateField]。 */
    private val testChatIdField: BridgeField<String> = BridgeField(
        id = "connection.testChatId",
        group = ConfigGroup.CONNECTION,
        label = "测试 Chat ID",
        hint = "",
        editor = Editor.Text,
        get = { it.connection.testChatId },
        set = { c, v -> c.copy(connection = c.connection.copy(testChatId = v)) },
    )

    // ============================================================================
    // 1. draft 即时更新
    // ============================================================================

    /** 🔴 装配时序核心语义：
     *  旧事故：未装配时 store.config 回退全默认 ⇒ 空表单起步 ⇒ 首次编辑把「默认值+局部编辑」
     *  整份覆写磁盘配置（凭据被抹）。装配下放 init 协程（主线程零冻结）——
     *  断言改为：①构造后调度器推进时 ensureAssembled 恰被调一次（异步装配仍必达）；
     *  ②推进后 draft 回填装配后磁盘真值（「不从空默认起步」由快照起步+回填承接）。
     *  （构造期同步断言随主线程冻结形态一起退役——用户语义不变。） */
    @Test
    fun draftInitializedAfterEnsureAssembled() = runTest(dispatcher) {
        val store = FakeStore(assembledInitially = false)
        val vm = LarkBridgeViewModel(store)
        assertEquals(0, store.ensureAssembledCount, "构造期零同步装配（装配在 init 协程，未推进不执行）")
        dispatcher.scheduler.advanceUntilIdle()
        // 🔴 flaky 根治：装配体经 withContext(Dispatchers.IO) 跑在**真实 IO 线程**，
        //    runTest 的虚拟时间推不进真线程——advanceUntilIdle 只保证 launch 已调度，不保证 IO 块已完成。
        //    全量跑的 IO 负载拉宽竞态窗口（偶发时红）。修法 = 真实时间轮询
        //    等 IO 块落地（上限 5s，与真机 B 端到端等待同款），到点即断（不再依赖调度器时序）。
        val deadline = System.currentTimeMillis() + 5_000
        while (store.ensureAssembledCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            dispatcher.scheduler.advanceUntilIdle()
        }
        assertEquals(1, store.ensureAssembledCount, "🔴 推进后必须完成异步装配（恰一次）")
        // draft 回填断言前再推进一轮（回填在 ensureAssembled 返回后的同一协程内，随轮询推进落地）
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(store.config, vm.draft.value, "装配后 draft 回填磁盘真值")
    }

    /** updateField 同步改 draft（无需推进虚拟时间即可读到新值）。 */
    @Test
    fun draftUpdatesImmediatelyOnUpdateField() = runTest(dispatcher) {
        val vm = LarkBridgeViewModel(FakeStore())
        assertEquals("", vm.draft.value.connection.testChatId)
        vm.updateField(testChatIdField, "oc_chat_1")
        assertEquals("oc_chat_1", vm.draft.value.connection.testChatId, "draft 须即时反映输入")
    }

    /** updateConfig 同步改 draft 并即时重算 issues（校验失败立刻可见，供 UI 红条/isError）。 */
    @Test
    fun issuesRecomputeImmediatelyOnUpdateConfig() = runTest(dispatcher) {
        val vm = LarkBridgeViewModel(FakeStore())
        assertTrue(vm.issues.value.isEmpty(), "默认配置合法，初始 issues 须为空")
        // 自定义预处理命令缺 {input} 占位符 ⇒ ConfigValidator 产 issue（纯字符串规则，不做磁盘/网络）
        vm.updateConfig { it.copy(fileMessage = it.fileMessage.copy(preprocessCommand = "echo hi")) }
        assertTrue(vm.issues.value.containsKey("fileMessage.preprocessCommand"), "issues 须即时重算并命中字段 id")
    }

    // ============================================================================
    // 2. drop(1)：仅打开页面不落盘
    // ============================================================================

    /** 🔒 drop(1)：构造 VM（初始值）经 debounce 窗口后零落盘——不生成 bridge-config.json（纯 CLI 使用前提）。 */
    @Test
    fun openingPageAloneDoesNotPersist() = runTest(dispatcher) {
        val store = FakeStore()
        LarkBridgeViewModel(store)
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 200)
        runCurrent()
        assertEquals(0, store.applyCount, "🔒 仅打开页面（初始值经 drop(1) 丢弃）不得落盘")
    }

    // ============================================================================
    // 3. 400ms trailing debounce 落盘
    // ============================================================================

    /** 编辑后经 400ms debounce 窗口恰落盘一次，persist=true，收到最新 draft。 */
    @Test
    fun editPersistsAfterDebounceWindow() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        // 先让初始值 v0 经 debounce 发射并被 drop(1) 丢弃
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 100)
        runCurrent()
        assertEquals(0, store.applyCount)

        vm.updateField(testChatIdField, "oc_persist")
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(1, store.applyCount, "debounce 窗口后须落盘恰一次")
        assertEquals(true, store.lastPersist, "落盘须 persist=true")
        assertEquals("oc_persist", store.lastApplied?.connection?.testChatId)
    }

    /** 🔒 trailing debounce：窗口内高频编辑合并为一次落盘，且收到最后一次的值。 */
    @Test
    fun rapidEditsCoalesceIntoSinglePersist() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 100)
        runCurrent()

        vm.updateField(testChatIdField, "a")
        advanceTimeBy(100)
        vm.updateField(testChatIdField, "b")
        advanceTimeBy(100)
        vm.updateField(testChatIdField, "c")
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(1, store.applyCount, "🔒 高频键击须合并为一次落盘")
        assertEquals("c", store.lastApplied?.connection?.testChatId, "须落盘最后一次的稳定值")
    }

    // ============================================================================
    // 4. 校验失败不覆写文件
    // ============================================================================

    /** 🔒 issues 非空时 persist 中止：applyConfig 计数 == 0，文件保持旧内容（UI 顶部红条 + 字段级 isError）。 */
    @Test
    fun invalidConfigIsNotPersisted() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 100)
        runCurrent()

        vm.updateConfig { it.copy(fileMessage = it.fileMessage.copy(preprocessCommand = "no-placeholder")) }
        assertTrue(vm.issues.value.isNotEmpty())
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(0, store.applyCount, "🔒 校验失败不得覆写文件")
    }

    // ============================================================================
    // 5. 同值短路：相同值提交零副作用
    // ============================================================================

    /** 🔒 同值短路：updateConfig 提交与当前 draft 相等的值 ⇒ commit 短路，StateFlow 不发射，debounce 不落盘。 */
    @Test
    fun sameValueCommitShortCircuitsPersist() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 100)
        runCurrent()

        val before = vm.draft.value
        vm.updateConfig { it } // 恒等变换：相同值
        assertEquals(before, vm.draft.value, "相同值提交不得改 draft")
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(0, store.applyCount, "🔒 同值短路：相同值零副作用（不落盘、不触发 runtime 热更新）")
    }

    // ============================================================================
    // 7. 运行指令（挂起纪律：经 viewModelScope 转发 store）
    // ============================================================================

    /** onStart 转发 store.start()，回写 Started 结果，busy 归位。 */
    @Test
    fun onStartPublishesStartedResult() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        vm.onStart()
        advanceUntilIdle()
        assertEquals(1, store.startCount)
        assertEquals(BridgeStartResult.Started, vm.startResult.value)
        assertFalse(vm.busy.value, "启动完成后 busy 须归 false")
    }

    /** onStart 失败归一：store 返回 Refused 时 VM 原样透出（不抛异常）。 */
    @Test
    fun onStartPublishesRefusedResult() = runTest(dispatcher) {
        val store = FakeStore().apply {
            startResult = BridgeStartResult.Refused(BridgeErrorCode.ConnectFailed, "网络不可达")
        }
        val vm = LarkBridgeViewModel(store)
        vm.onStart()
        advanceUntilIdle()
        val result = vm.startResult.value
        assertIs<BridgeStartResult.Refused>(result)
        assertEquals(BridgeErrorCode.ConnectFailed, result.code)
        assertFalse(vm.busy.value)
    }

    /** 🔴 agent 硬依赖：装配完成（probe 非空）且本地 AI 端点未配置 ⇒ 入口硬拒绝、不调 store.start。 */
    @Test
    fun onStartRefusesAgentUnavailableWhenAssembledAndEndpointMissing() = runTest(dispatcher) {
        val store = FakeStore().apply {
            probeResult = EnvironmentProbe(
                agentBackend = "claude", agentAvailable = false, connectionAppId = "cli_x", running = false,
            )
        }
        val vm = LarkBridgeViewModel(store)
        vm.onStart()
        advanceUntilIdle()
        val result = vm.startResult.value
        assertIs<BridgeStartResult.Refused>(result)
        assertEquals(BridgeErrorCode.AgentUnavailable, result.code, "端点未配置 ⇒ AgentUnavailable（硬依赖入口拒绝）")
        assertEquals(0, store.startCount, "硬拒绝不得发起 store.start")
        assertFalse(vm.busy.value)
    }

    /** 🔴 probe == null（未装配窗口）不拒：照常调 store.start——防「未装配」与「端点未配置」两态折叠误拒。 */
    @Test
    fun onStartDoesNotRefuseWhenProbeAbsent() = runTest(dispatcher) {
        val store = FakeStore() // probeResult 默认 null = 未装配窗口
        val vm = LarkBridgeViewModel(store)
        vm.onStart()
        advanceUntilIdle()
        assertEquals(1, store.startCount, "未装配窗口不得误拒（打断惰性装配）")
        assertEquals(BridgeStartResult.Started, vm.startResult.value)
    }

    /** probe 非空且端点就绪 ⇒ 照常启动（拒绝闸只对「已装配且不可用」收口）。 */
    @Test
    fun onStartStartsNormallyWhenEndpointReady() = runTest(dispatcher) {
        val store = FakeStore().apply {
            probeResult = EnvironmentProbe(
                agentBackend = "claude", agentAvailable = true, connectionAppId = "cli_x", running = false,
            )
        }
        val vm = LarkBridgeViewModel(store)
        vm.onStart()
        advanceUntilIdle()
        assertEquals(1, store.startCount)
        assertEquals(BridgeStartResult.Started, vm.startResult.value)
    }

    /** 🔴 预检点现读端点快照：onStart 的端点硬拒绝门在读 probeEnvironment 前按需现刷一次——
     *  「桥接停着、AI 设置页改完端点立即启动」不被刷新回路的陈旧缓存快照误拒。 */
    @Test
    fun onStartRefreshesEndpointSnapshotBeforeGateDecision() = runTest(dispatcher) {
        val store = FakeStore().apply {
            probeResult = EnvironmentProbe(
                agentBackend = "koog", agentAvailable = false, connectionAppId = "cli_x", running = false,
            )
            onEndpointRefreshed = { probeResult = probeResult?.copy(agentAvailable = true) }
        }
        val vm = LarkBridgeViewModel(store)
        vm.onStart()
        advanceUntilIdle()
        assertEquals(1, store.refreshEndpointCount, "预检点须恰现刷一次端点快照（单次 DataStore 读）")
        assertEquals(1, store.startCount, "现刷后端点已就绪 ⇒ 不得被回路陈旧缓存快照误拒")
        assertEquals(BridgeStartResult.Started, vm.startResult.value)
    }

    /** onStop 转发 store.stop() 并清空 startResult。 */
    @Test
    fun onStopClearsResult() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        vm.onStart()
        advanceUntilIdle()
        vm.onStop()
        advanceUntilIdle()
        assertEquals(1, store.stopCount)
        // 🔴 停止不再清 startResult——保留最近一次启动反馈，
        //    避免「绿色横幅瞬间消失」的闪烁观感。
        assertEquals(BridgeStartResult.Started, vm.startResult.value, "停止后保留最近一次启动结果（不清横幅）")
        assertNull(vm.busyAction.value, "停止完成后在途动作清空")
        assertFalse(vm.busy.value)
    }

    /**
     * 一键重启：stop→start 串行各恰一次、startResult 回写新结果、
     * busy/busyAction 全程归位——RESTART 字段变更黄条按钮的 VM 侧契约。
     */
    @Test
    fun onRestartStopsThenStartsAndResetsBusy() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        vm.onRestart()
        advanceUntilIdle()
        assertEquals(1, store.stopCount, "重启须先停止（串行）")
        assertEquals(1, store.startCount, "停止后接着启动（同一次重启）")
        assertEquals(BridgeStartResult.Started, vm.startResult.value, "startResult 须回写重启后的新结果")
        assertNull(vm.busyAction.value, "重启完成后在途动作清空（RESTARTING 归 null）")
        assertFalse(vm.busy.value, "重启完成后 busy 归 false（按钮解锁）")
    }

    /** onRestart 的 start 被拒（Refused）也不炸 VM：busy/busyAction 归位、startResult 如实回写。 */
    @Test
    fun onRestartSurvivesRefusedStart() = runTest(dispatcher) {
        val store = FakeStore().apply {
            startResult = BridgeStartResult.Refused(BridgeErrorCode.ConnectFailed, "start 被拒")
        }
        val vm = LarkBridgeViewModel(store)
        vm.onRestart()
        advanceUntilIdle()
        assertEquals(1, store.stopCount, "stop 先行执行")
        assertIs<BridgeStartResult.Refused>(vm.startResult.value, "startResult 如实回写拒绝结果（不静默吞）")
        assertNull(vm.busyAction.value, "被拒路径 busyAction 也须归位（按钮不死锁）")
        assertFalse(vm.busy.value, "被拒路径 busy 也须归位")
    }

    // ============================================================================
    // 8. 🔴 G-B：环境预检 + 发测试消息（转发 store，结果经 probe/banner 回显）
    // ============================================================================

    /** G-B：onProbe 透出 store.probeEnvironment() 结果并给一条预检 banner。 */
    @Test
    fun onProbePublishesResultAndBanner() = runTest(dispatcher) {
        val probe = EnvironmentProbe(agentBackend = "claude", agentAvailable = true, connectionAppId = "cli_x", running = true)
        val store = FakeStore().apply { probeResult = probe }
        val vm = LarkBridgeViewModel(store)
        vm.onProbe()
        advanceUntilIdle()
        assertEquals(probe, vm.probe.value, "G-B：预检结果须透出到 probe 状态")
        assertTrue(vm.banner.value!!.contains("预检"), "G-B：预检须给 banner 回显，实际=${vm.banner.value}")
        assertTrue(vm.banner.value!!.contains("claude"))
    }

    // ============================================================================
    // 8b. 🔴 banner 与 probe 生命周期解耦——
    // clearBanner 收窄只清 banner（唯一消费点 = OperationBar 5s 自动清除链）、
    // clearProbe 单独处置预检结果（常驻至下次预检覆盖或手动关闭）
    // ============================================================================

    /** clearBanner 收窄——只清 banner，probe 不动（5s 自动清除不再连带清预检结果）。 */
    @Test
    fun clearBannerOnlyClearsBannerNotProbe() = runTest(dispatcher) {
        val probe = EnvironmentProbe(agentBackend = "claude", agentAvailable = true, connectionAppId = "cli_x", running = true)
        val vm = LarkBridgeViewModel(FakeStore().apply { probeResult = probe })
        vm.onProbe()
        advanceUntilIdle()
        assertEquals(probe, vm.probe.value)
        assertNotNull(vm.banner.value)
        vm.clearBanner()
        assertNull(vm.banner.value, "clearBanner 须清 banner（收窄语义）")
        assertEquals(probe, vm.probe.value, "🔴 clearBanner 不得连带清 probe（预检结果常驻至下次预检/手动关闭）")
    }

    /** clearProbe 只清 probe、banner 不动（手动关闭预检条不处置一次性提示）。 */
    @Test
    fun clearProbeOnlyClearsProbeNotBanner() = runTest(dispatcher) {
        val probe = EnvironmentProbe(agentBackend = "claude", agentAvailable = true, connectionAppId = "cli_x", running = true)
        val vm = LarkBridgeViewModel(FakeStore().apply { probeResult = probe })
        vm.onProbe()
        advanceUntilIdle()
        assertEquals(probe, vm.probe.value)
        assertNotNull(vm.banner.value)
        vm.clearProbe()
        assertNull(vm.probe.value, "clearProbe 须清 probe（手动关闭入口）")
        assertNotNull(vm.banner.value, "clearProbe 不得连带清 banner（两生命周期解耦）")
    }

    /** 下次预检覆盖旧 probe（常驻语义 = 「当下环境态」，非历史快照堆积）。 */
    @Test
    fun nextProbeOverwritesPreviousProbe() = runTest(dispatcher) {
        val first = EnvironmentProbe(agentBackend = "claude", agentAvailable = true, connectionAppId = "cli_x", running = true)
        val second = EnvironmentProbe(agentBackend = "koog", agentAvailable = false, connectionAppId = "cli_x", running = false)
        val store = FakeStore().apply { probeResult = first }
        val vm = LarkBridgeViewModel(store)
        vm.onProbe()
        advanceUntilIdle()
        assertEquals(first, vm.probe.value)
        store.probeResult = second
        vm.onProbe()
        advanceUntilIdle()
        assertEquals(second, vm.probe.value, "下次预检须覆盖旧 probe（预检结果=当下环境态）")
    }

    /** G-B：未装配（store 返回 null）时 onProbe 给「未装配」提示，不抛异常。 */
    @Test
    fun onProbeWhenNotAssembledShowsHint() = runTest(dispatcher) {
        val vm = LarkBridgeViewModel(FakeStore()) // probeResult 默认 null
        vm.onProbe()
        advanceUntilIdle()
        assertNull(vm.probe.value)
        assertTrue(vm.banner.value!!.contains("未装配"), "G-B：未装配须提示，实际=${vm.banner.value}")
    }

    /** 🔴 预检点现读端点快照：onProbe 读数前按需现刷一次——预检 banner 常驻至下次预检、
     *  不随刷新回路自愈，固化回路陈旧缓存快照即把过时端点态写进常驻文案。 */
    @Test
    fun onProbeRefreshesEndpointSnapshotBeforeReading() = runTest(dispatcher) {
        val store = FakeStore().apply {
            probeResult = EnvironmentProbe(
                agentBackend = "koog", agentAvailable = false, connectionAppId = "cli_x", running = false,
            )
            onEndpointRefreshed = { probeResult = probeResult?.copy(agentAvailable = true) }
        }
        val vm = LarkBridgeViewModel(store)
        vm.onProbe()
        advanceUntilIdle()
        assertEquals(1, store.refreshEndpointCount, "预检点须恰现刷一次端点快照（单次 DataStore 读）")
        assertTrue(vm.probe.value!!.agentAvailable, "预检结果须反映现刷后的端点态（非回路缓存快照）")
    }

    /** aiEndpointReady 订阅 store 的端点就绪流（端点已配 ⇒ true）。 */
    @Test
    fun aiEndpointReadyMirrorsStoreEndpointReadiness() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        assertFalse(vm.aiEndpointReady.value, "端点未配 ⇒ aiEndpointReady false")
        store.setEndpointReady(true)
        assertTrue(vm.aiEndpointReady.value, "端点已配 ⇒ aiEndpointReady true")
    }

    /** 桥接未装配（store 就绪流默认 false）⇒ aiEndpointReady 恒 false（保守方向）。 */
    @Test
    fun aiEndpointReadyFalseWhenNotAssembled() = runTest(dispatcher) {
        val vm = LarkBridgeViewModel(FakeStore()) // 就绪流默认 false = 未装配窗口
        assertFalse(vm.aiEndpointReady.value, "未装配 ⇒ false（惰性装配语义下「未装配 ≈ 端点未验证」）")
    }

    /** 🔴 端点态变更即时反映：订阅流发射即达订阅方——组合期零挂起读、零 probeEnvironment 轮询。 */
    @Test
    fun aiEndpointReadyReflectsEndpointChangeImmediately() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch {
            vm.aiEndpointReady.collect { seen.add(it) }
        }
        runCurrent()
        assertEquals(listOf(false), seen, "订阅即得当前值（StateFlow 语义），零等待")
        // 模拟刷新回路读到新端点配置（DataStore 已改、快照刷新落地）——发布点发射，订阅方即时收到
        store.setEndpointReady(true)
        runCurrent()
        assertEquals(listOf(false, true), seen, "端点态变更须即时反映给订阅方（订阅式，无组合期挂起读）")
        assertTrue(vm.aiEndpointReady.value)
        // 撤配端点同样即时反映（双向——提示条/第 0 步不会卡在旧就绪态）
        store.setEndpointReady(false)
        runCurrent()
        assertEquals(listOf(false, true, false), seen, "端点撤配须同样即时反映（双向）")
    }

    /** G-B：onSendTest 成功 ⇒ 转发 store.sendTest(chatId) 且 banner 回显已发送 + messageId。 */
    @Test
    fun onSendTestSuccessShowsMessageId() = runTest(dispatcher) {
        val store = FakeStore().apply { sendResult = SendResult(ok = true, messageId = "om_test_1") }
        val vm = LarkBridgeViewModel(store)
        vm.onSendTest("oc_chat_9", "hello")
        advanceUntilIdle()
        assertEquals(1, store.sendCount)
        assertEquals("oc_chat_9", store.lastSendChatId, "G-B：chatId 须原样转发")
        assertTrue(vm.banner.value!!.contains("已发送"), "实际=${vm.banner.value}")
        assertTrue(vm.banner.value!!.contains("om_test_1"))
        assertFalse(vm.busy.value, "发送完成后 busy 须归 false")
    }

    /** G-B：onSendTest 未运行（store 返回 null）⇒ banner 提示未运行，不抛异常。 */
    @Test
    fun onSendTestNotRunningShowsHint() = runTest(dispatcher) {
        val vm = LarkBridgeViewModel(FakeStore()) // sendResult 默认 null
        vm.onSendTest("oc_chat_9", "hello")
        advanceUntilIdle()
        assertTrue(vm.banner.value!!.contains("未运行"), "实际=${vm.banner.value}")
        assertFalse(vm.busy.value)
    }

    // ============================================================================
    // 9. 🔴 connection.autoStart 接线（迁移后判定、幂等闸门、dryRun 下用 Synthetic）
    // ============================================================================

    /** autoStart=true ⇒ 迁移完成后自动触发一次 start()（dryRun 下走 Synthetic，零真实网络）。 */
    @Test
    fun autoStartTrueTriggersStartOnceAfterMigration() = runTest(dispatcher) {
        val cfg = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(autoStart = true, dryRun = true, appId = "cli_test", appSecret = "sec"),
        )
        val store = FakeStore(config = cfg)
        LarkBridgeViewModel(store)
        advanceUntilIdle()
        assertEquals(1, store.startCount, "autoStart=true 须触发一次 start()")
        assertEquals(1, store.autoStartCheckCount, "ensureAutoStarted 须被调用一次")
    }

    /** autoStart=false ⇒ 不触发 start()。 */
    @Test
    fun autoStartFalseDoesNotTriggerStart() = runTest(dispatcher) {
        val cfg = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(autoStart = false, dryRun = true),
        )
        val store = FakeStore(config = cfg)
        LarkBridgeViewModel(store)
        advanceUntilIdle()
        assertEquals(0, store.startCount, "autoStart=false 不得触发 start()")
        assertEquals(1, store.autoStartCheckCount, "ensureAutoStarted 仍须被调用（内部判定后短路）")
    }

    /** 重复进页（VM 重建）不重复触发——幂等闸门在 store（进程单例）层面保证。 */
    @Test
    fun autoStartIdempotentAcrossViewModelRecreation() = runTest(dispatcher) {
        val cfg = DefaultBridgeConfig.default().copy(
            connection = ConnectionConfig(autoStart = true, dryRun = true, appId = "cli_test", appSecret = "sec"),
        )
        val store = FakeStore(config = cfg)
        // 第一次进页
        LarkBridgeViewModel(store)
        advanceUntilIdle()
        assertEquals(1, store.startCount)
        // 第二次进页（VM 重建，同一 store 单例）
        LarkBridgeViewModel(store)
        advanceUntilIdle()
        assertEquals(1, store.startCount, "🔴 幂等闸门：重复进页不得重复触发 start()")
        assertEquals(2, store.autoStartCheckCount, "ensureAutoStarted 被调用但内部短路")
    }

    // ============================================================================
    // 8. 扫码建应用（阶段四）：QR 状态转发 + Success 自动回填凭据进 draft
    // ============================================================================

    /** 扫码成功 ⇒ QrShowing 状态出现过、凭据自动回填 draft（走常规 updateConfig 链路）。 */
    @Test
    fun registerSuccessFillsCredentialsIntoDraft() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)

        vm.onStartRegister()
        advanceUntilIdle()

        // QR 回调已转发（FakeStore 内 onQr 被调用）
        assertEquals("https://qr.test" to 300, store.registerQrSeen, "QR 回调须透传给 UI 状态")
        // Success 态 + 凭据已回填 draft
        val state = vm.registerState.value
        assertTrue(state is RegisterAppState.Success, "扫码成功 ⇒ Success 态，实际 $state")
        assertEquals("cli_reg", vm.draft.value.connection.appId, "appId 须自动回填")
        assertEquals("sec_reg", vm.draft.value.connection.appSecret, "appSecret 须自动回填")
    }

    /** 扫码失败（如二维码过期）⇒ Failed 态带文案，draft 不被污染。 */
    @Test
    fun registerFailureShowsMessageWithoutTouchingDraft() = runTest(dispatcher) {
        val store = FakeStore()
        store.registerResult = com.jiaoay.lark.bridge.RegisterAppResult.QrExpired("二维码已过期")
        val vm = LarkBridgeViewModel(store)

        vm.onStartRegister()
        advanceUntilIdle()

        val state = vm.registerState.value
        assertTrue(state is RegisterAppState.Failed, "扫码失败 ⇒ Failed 态，实际 $state")
        assertTrue(state.message.contains("二维码已过期"), "失败文案须保留原因")
        assertEquals("cli_test", vm.draft.value.connection.appId, "失败不得污染 draft 凭据")
    }

    /** onDismissRegister 收起回执回到 Idle（SDK 轮询不受影响，只收 UI）。 */
    @Test
    fun dismissRegisterResetsStateToIdle() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        vm.onStartRegister()
        advanceUntilIdle()
        assertTrue(vm.registerState.value !is RegisterAppState.Idle)

        vm.onDismissRegister()
        assertTrue(vm.registerState.value is RegisterAppState.Idle, "关闭回执 ⇒ Idle")
    }

    /**
     * QrShowing 收起（UI 层解耦）→ 迟到 Success 落横幅不再弹对话框；
     * 再入守卫 = registerJob 活性（收起后 UI 态 Idle 不得放进第二个并发 registerApp）。
     */
    @Test
    fun dismissDuringQrShowingRedirectsLateResultToBanner() = runTest(dispatcher) {
        val store = GatedRegisterStore()
        val vm = LarkBridgeViewModel(store)

        vm.onStartRegister()
        runCurrent()
        assertTrue(vm.registerState.value is RegisterAppState.QrShowing, "前置：二维码态已出")

        vm.onDismissRegister()
        assertTrue(vm.registerState.value is RegisterAppState.Idle, "收起 ⇒ UI 态回 Idle")

        // 再入守卫：SDK 轮询在途，第二次发起被拒（守卫判据 = registerJob 活性而非 UI 态）
        vm.onStartRegister()
        assertEquals(1, store.registerCalls, "轮询在途不得发起第二个 registerApp（双 SDK 轮询竞速）")
        assertNotNull(vm.banner.value, "拒绝必带原因（横幅明示，禁静默）")

        // 放行 → 迟到 Success：state 恒 Idle（对话框不被重新拉起），回执落横幅
        store.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.registerState.value is RegisterAppState.Idle, "迟到终态不再重新拉起对话框")
        assertTrue(vm.banner.value?.contains("扫码建应用完成") == true, "回执经横幅落地")
        assertEquals("cli_reg", vm.draft.value.connection.appId, "凭据回填不受对话框收起影响")
    }

    // ============================================================================
    // 10. 🔴 导入 JSON 的 L2 危险确认（确认前磁盘零变化）
    // ============================================================================

    /** 🔒 取消路：requestImport 只置挂起态（draft/落盘零变化）；cancelImport 清挂起态，
     *  磁盘与 VM 状态全程不动（不解析、不 commit、不触发 debounce 落盘）。 */
    @Test
    fun requestThenCancelImportLeavesDraftAndDiskUntouched() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        // 让初始值经 debounce 窗口被 drop(1) 丢弃（排除初始发射干扰计数）
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 100)
        runCurrent()
        val draftBefore = vm.draft.value

        val file = java.io.File(System.getProperty("java.io.tmpdir"), "lb02-cancel-import.json")
        vm.requestImport(file)
        assertEquals(file, vm.pendingImport.value, "选好文件只置挂起态，不直接导入")
        assertEquals(draftBefore, vm.draft.value, "🔒 挂起期 draft 不得动")
        // 挂起态下推进满 debounce 窗口——零落盘（确认前磁盘零变化）
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 200)
        runCurrent()
        assertEquals(0, store.applyCount, "🔒 确认前不得有任何落盘")
        assertNull(vm.banner.value, "取消前不得有导入结果 banner")

        vm.cancelImport()
        assertNull(vm.pendingImport.value, "取消 ⇒ 挂起态清空")
        assertEquals(draftBefore, vm.draft.value, "🔒 取消后 draft 保持原样")
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 200)
        runCurrent()
        assertEquals(0, store.applyCount, "🔒 取消后磁盘仍零变化")
    }

    /** 🔒 确认路：confirmImport 清挂起态并走真实导入——draft 覆盖为文件内容、
     *  banner 回显「已导入」；确认后才落盘（经 debounce 窗口恰一次）。 */
    @Test
    fun confirmImportExecutesImportAfterConfirmation() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 100)
        runCurrent()

        // 导入文件内容：改 testChatId 与凭据（与 FakeStore 初始配置可区分）
        val imported = store.config.copy(
            connection = store.config.connection.copy(testChatId = "oc_imported", appId = "cli_imported"),
        )
        val file = java.io.File.createTempFile("lb02-confirm-import", ".json")
        try {
            file.writeText(
                com.jiaoay.lark.bridge.config.BridgeJson.encodeToString(
                    com.jiaoay.lark.bridge.config.BridgeConfig.serializer(),
                    imported,
                ),
                Charsets.UTF_8,
            )
            vm.requestImport(file)
            assertEquals(file, vm.pendingImport.value)

            vm.confirmImport()
            assertNull(vm.pendingImport.value, "确认 ⇒ 挂起态清空")
            // 🔴 importJson 的读盘经 withContext(Dispatchers.IO) 跑真实 IO 线程——runTest 虚拟时间
            //    推不进真线程（draftInitializedAfterEnsureAssembled 同款配方）：真实时间轮询等落地。
            val deadline = System.currentTimeMillis() + 5_000
            while (vm.draft.value.connection.testChatId != "oc_imported" &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(10)
                dispatcher.scheduler.advanceUntilIdle()
            }
            assertEquals("oc_imported", vm.draft.value.connection.testChatId, "确认后 draft 须覆盖为文件内容")
            assertEquals("cli_imported", vm.draft.value.connection.appId)
            assertTrue(vm.banner.value!!.contains("已导入"), "导入成功须 banner 回显，实际=${vm.banner.value}")

            // 确认后的导入经 400ms debounce 恰落盘一次（保存后立即生效语义）
            advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 1)
            runCurrent()
            assertEquals(1, store.applyCount, "确认后经 debounce 窗口须落盘恰一次")
            assertEquals("oc_imported", store.lastApplied?.connection?.testChatId)
        } finally {
            file.delete()
        }
    }

    /** 🔒 防御：无挂起导入时 confirmImport 是 no-op（不抛、不导入、不落盘）。 */
    @Test
    fun confirmImportWithoutPendingIsNoOp() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 100)
        runCurrent()

        vm.confirmImport()
        advanceUntilIdle()
        assertNull(vm.pendingImport.value)
        assertEquals(store.config, vm.draft.value, "无挂起时确认不得动 draft")
        assertNull(vm.banner.value)
        advanceTimeBy(LarkBridgeViewModel.PERSIST_DEBOUNCE_MILLIS + 200)
        runCurrent()
        assertEquals(0, store.applyCount, "无挂起时确认不得落盘")
    }

    /**
     * 导出件含明文 `appSecret`——落盘后必须经 store 硬化（POSIX 600）。
     * 硬化口径单源在 `ConfigRepository`，导出通道不得自写裸 `writeText` 旁路。
     */
    @Test
    fun exportJsonHardensSecretFile() = runTest(dispatcher) {
        val store = FakeStore()
        val vm = LarkBridgeViewModel(store)
        advanceUntilIdle()

        val target = File.createTempFile("bridge-export", ".json")
        try {
            vm.exportJson(target)
            advanceUntilIdle()
            val deadline = System.currentTimeMillis() + 5_000
            while (store.hardenedFiles.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
                dispatcher.scheduler.advanceUntilIdle()
            }
            assertEquals(
                listOf(target),
                store.hardenedFiles,
                "导出后必须对目标文件调用硬化（含明文密钥的文件族同口径）",
            )
            assertTrue(vm.banner.value?.contains("已导出") == true, "导出回执随硬化一并落定")
        } finally {
            target.delete()
        }
    }
}

/**
 * [BridgeStore] 的内存 fake：计数 applyConfig/start/stop，记录最近落盘的配置与 persist 标志。
 * 🔴 零文件系统副作用——dataRoot 仅返回临时路径（不 mkdirs、不写盘），不触碰 `~/.easy_dev_toolbox/`。
 * 🔴 实现 [ensureAutoStarted] 的幂等闸门逻辑（与生产 [LarkBridgeStore] 同语义），
 *    使 VM 测试可验证「autoStart=true 触发一次 start / =false 不触发 / 重复进页不重复触发」。
 */
/** registerApp 可挂起的 fake（扫码收起语义测试缝——模拟 SDK 轮询在途不可中断） */
private class GatedRegisterStore : FakeStore() {
    val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
    var registerCalls = 0
        private set

    override suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): com.jiaoay.lark.bridge.RegisterAppResult {
        registerCalls++
        onQr("https://qr.test", 300)
        gate.await()
        return registerResult ?: com.jiaoay.lark.bridge.RegisterAppResult.Failed("未编程")
    }
}

private open class FakeStore(
    // 🔴 预填凭据：部分用例会改配置触发校验/persist——凭据补全保证校验 issues 恒空、persist 不被卡。
    override val config: BridgeConfig = DefaultBridgeConfig.default().let {
        it.copy(connection = it.connection.copy(appId = "cli_test", appSecret = "sec_test"))
    },
    // 异步装配断言缝：false = 模拟首进未装配（默认 true 保持既有用例零改动）
    private val assembledInitially: Boolean = true,
) : BridgeStore {
    private var assembledNow = assembledInitially

    var applyCount: Int = 0
        private set
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var lastApplied: BridgeConfig? = null
        private set
    var lastPersist: Boolean? = null
        private set
    var startResult: BridgeStartResult = BridgeStartResult.Started

    /** 🔴 G-B：可编程的 sendTest / probeEnvironment 结果（默认 null = 未运行 / 未装配）。 */
    var sendCount: Int = 0
        private set
    var lastSendChatId: String? = null
        private set
    var sendResult: SendResult? = null
    var probeResult: EnvironmentProbe? = null

    /** 导出通道硬化调用记录（导出件含明文 appSecret，落盘后必须硬化）。 */
    val hardenedFiles: MutableList<java.io.File> = mutableListOf()

    override fun hardenSecretFile(file: java.io.File) {
        hardenedFiles += file
    }

    /** 🔴 autoStart 幂等闸门（进程内只触发一次，与 LarkBridgeStore.autoStartGuard 同语义）。 */
    private val autoStartGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    var autoStartCheckCount: Int = 0
        private set

    override val dataRoot: Path = Paths.get(System.getProperty("java.io.tmpdir"), "fake-lark-vm-test")
    override val isAssembled: Boolean get() = assembledNow
    override val status: StateFlow<BridgeStatusSnapshot>? = null
    override val logs: LogBus? = null

    override suspend fun applyConfig(next: BridgeConfig, persist: Boolean): ConfigApplyResult? {
        applyCount++
        lastApplied = next
        lastPersist = persist
        return ConfigApplyResult.HotApplied
    }

    override suspend fun start(): BridgeStartResult {
        startCount++
        return startResult
    }

    override suspend fun stop() {
        stopCount++
    }

    override suspend fun sendTest(chatId: String, text: String): SendResult? {
        sendCount++
        lastSendChatId = chatId
        return sendResult
    }

    override fun probeEnvironment(): EnvironmentProbe? = probeResult

    /** 预检点现刷计数 + 可编程钩子（模拟「DataStore 已是新值、回路缓存快照陈旧」的翻转）。 */
    var refreshEndpointCount: Int = 0
        private set
    var onEndpointRefreshed: (() -> Unit)? = null

    override suspend fun refreshEndpointSnapshot() {
        refreshEndpointCount++
        onEndpointRefreshed?.invoke()
    }

    /** 🔴 端点就绪订阅流的测试缝：可编程翻转（模拟刷新回路读到新的 DataStore 端点配置）。 */
    private val endpointReadySink = MutableStateFlow(false)

    fun setEndpointReady(ready: Boolean) {
        endpointReadySink.value = ready
    }

    override val aiEndpointReady: StateFlow<Boolean>
        get() = endpointReadySink

    /** 阶段四：可编程的 registerApp 结果（默认 Success 合成凭据，模拟扫码成功回填路径）。 */
    var registerResult: com.jiaoay.lark.bridge.RegisterAppResult? =
        com.jiaoay.lark.bridge.RegisterAppResult.Success(
            com.jiaoay.lark.gateway.LarkCredential(appId = "cli_reg", appSecret = "sec_reg", region = "feishu"),
        )
    var registerQrSeen: Pair<String, Int>? = null
        private set

    open override suspend fun registerApp(onQr: (qrUrl: String, expireInSec: Int) -> Unit): com.jiaoay.lark.bridge.RegisterAppResult {
        onQr("https://qr.test", 300)
        registerQrSeen = "https://qr.test" to 300
        return registerResult ?: com.jiaoay.lark.bridge.RegisterAppResult.Failed("未编程")
    }

    /** 🔴 验证 VM 构造期确实调用了装配钩子（draft 初值 = 装配后真值）。 */
    var ensureAssembledCount: Int = 0
        private set

    override fun ensureAssembled() {
        ensureAssembledCount++
        assembledNow = true
    }

    /** 🔴 模拟生产 LarkBridgeStore.ensureAutoStarted 的幂等闸门 + autoStart 判定。 */
    override suspend fun ensureAutoStarted() {
        autoStartCheckCount++
        if (!autoStartGuard.compareAndSet(false, true)) return
        if (!config.connection.autoStart) return
        start()
    }
}
