package com.jiaoay.easy_dev_toolbox.page.ai.distill

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * DistillViewModel 失败 watcher 相位收口（失败也是终态——运行相位不楔死）：
 * - 探索失败（executor 即抛）→ 回 FORM + StatusSignals 报错（相位必达出口）；
 * - 执行失败 → 进 REVIEW 保全已产候选（语义同中止收口）+ 报错；
 * - 探索产物解析失败（计划 JSON 畸形）→ 回 FORM 报错（EXPLORING 不楔死）。
 *
 * 🔴 Main dispatcher 必须替换（viewModelScope = Main.immediate）；VM 构造触
 *    Database.database() 真单例——user.home 隔离 + LlmConfigStore 隔离 store +
 *    Database.resetForTest。会话经构造缝注入（executor 即抛 / 挂起，零网络）。
 *    ioDispatcher 注入共享 TestScheduler 的测试调度器（VM 级 IO 续体确定性
 *    推进，teardown 无竞态宽限；轮询 awaitTrue 仅承接 :core 内部真实 IO）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DistillViewModelFailureTest {

    private val dispatcher = StandardTestDispatcher()

    private val home = UserHomeIsolation("edt-distill-vm-failure-test")

    private lateinit var originalStore: LocalDataManager

    private val createdVms = mutableListOf<DistillViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
        originalStore = LlmConfigStore.store
        // 目录注入（隔离配方，单源见 AiSettingsViewModelTest.isolatedStore）：redirect 窗口内
        // eager 解析并注入构造——DataStore 落盘路径在实例首读写才解析，还原后才执行的
        // 首操作会把 test_distill_vm_* 文件落进真实数据目录
        LlmConfigStore.store = LocalDataManager(
            dataStoreName = "test_distill_vm_${java.util.UUID.randomUUID()}",
            datastoreDir = com.jiaoay.easy_dev_toolbox.core.data.FileUtils.getDatastoreDir(),
        )
        Database.resetForTest()
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle() // 排空在途协程再 resetMain（drain 纪律）——VM 级 IO 已注入测试调度器，无在途真实 IO 续体
        createdVms.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
        LlmConfigStore.store = originalStore
        home.restoreOnly() // 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp 系统清理）
        Database.resetForTest()
        StatusSignals.clearForTest()
    }

    /** 轮询推进防时序假红（真实 IO 续体落回 Main 队列——直跑测试同款配方） */
    private fun awaitTrue(probe: () -> Boolean): Boolean {
        repeat(100) {
            if (probe()) return true
            Thread.sleep(20)
            dispatcher.scheduler.advanceUntilIdle()
        }
        return probe()
    }

    /** 注入会话工厂：executor 即抛（零网络——koogToolLoop 参数求值期直达 catch 分支） */
    private fun explodingVm(): DistillViewModel = DistillViewModel(ioDispatcher = dispatcher) { scope ->
        DistillSession(
            scope = scope,
            configProvider = { LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
            executorProvider = { error("distill-vm-explode") },
            modelProvider = { error("测试不触达 model") },
            sourceRootsProvider = { emptyList() },
            ledger = null,
        )
    }.also { createdVms += it }

    @Test
    fun `explore failure settles back to form with signal`() {
        val vm = explodingVm()
        vm.setFeatureDesc("模块描述")

        vm.startExplore()
        assertEquals(DistillViewModel.Phase.EXPLORING, vm.phase.value, "发起即切相（同步）")

        assertTrue(
            awaitTrue { vm.phase.value == DistillViewModel.Phase.FORM },
            "探索失败收口回表单（相位不楔死——出口恒可达）",
        )
        assertTrue(
            StatusSignals.toast.value.any { "蒸馏探索失败" in it.text },
            "失败必带原因（StatusSignals 报错）",
        )
        assertEquals(false, vm.session.running.value, "失败后 running 复位")
    }

    @Test
    fun `distill failure settles into review keeping candidates`() {
        val vm = explodingVm()
        vm.setPlanReviewFixtureForTest("repo-x", listOf(DistillViewModel.PlanRow(path = "a.kt", reason = "理由")))

        vm.approvePlan()
        assertEquals(DistillViewModel.Phase.DISTILLING, vm.phase.value, "批准即切相（同步）")

        assertTrue(
            awaitTrue { vm.phase.value == DistillViewModel.Phase.REVIEW },
            "执行失败收口进审阅面（候选保全语义——不楔死在 DISTILLING）",
        )
        assertTrue(
            StatusSignals.toast.value.any { "已产候选已进审阅面" in it.text },
            "执行失败回执点明候选保全",
        )
        assertEquals(false, vm.session.running.value, "失败后 running 复位")
    }

    @Test
    fun `plan json parse failure settles back to form instead of wedging exploring`() {
        // 挂起会话：executor 求值即挂起（零网络）——循环不收尾，phaseResult 经缝直置
        val vm = DistillViewModel(ioDispatcher = dispatcher) { scope ->
            DistillSession(
                scope = scope,
                configProvider = { LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
                executorProvider = { awaitCancellation() },
                modelProvider = { error("测试不触达 model") },
                sourceRootsProvider = { emptyList() },
                ledger = null,
            )
        }.also { createdVms += it }
        vm.setFeatureDesc("模块描述")

        vm.startExplore()
        assertEquals(DistillViewModel.Phase.EXPLORING, vm.phase.value, "发起即切相（同步）")

        // 循环挂起中直置畸形 phaseResult——解析失败也是终态：回 FORM 报错
        vm.session.setPhaseResultForTest("这不是计划 JSON")
        assertTrue(
            awaitTrue { vm.phase.value == DistillViewModel.Phase.FORM },
            "计划 JSON 畸形也是终态——回表单不楔死 EXPLORING",
        )
        assertTrue(
            StatusSignals.toast.value.any { "解析失败" in it.text },
            "解析失败必带原因",
        )

        vm.session.cancel() // 收掉挂起循环（tearDown 的 scope cancel 兜底）
    }
}
