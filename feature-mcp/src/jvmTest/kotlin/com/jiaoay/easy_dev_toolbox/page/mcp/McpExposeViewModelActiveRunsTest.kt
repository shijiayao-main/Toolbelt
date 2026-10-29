package com.jiaoay.easy_dev_toolbox.page.mcp

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.core.llm.LlmConfigStore
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SYS-04 · 重设计批 1：MCP 在途会话数响应式化（StateFlow）。
 *
 * 旧缺陷：`activeRuns` 是普通 getter 直读 registry 计数——McpExposePage 只随其余
 * StateFlow 重组，外部 MCP client 起 run 后页面计数恒旧值（数字恒 0 的重组盲区）。
 *
 * 新形态：VM 暴露 [McpExposeViewModel.activeRunsFlow]（runsVersion 经 map 派生
 * 实时计数流）——registry 登记面变化对流订阅者即时可见。
 *
 * 🔴 Main dispatcher 必须替换（viewModelScope = Dispatchers.Main.immediate）：
 *    activeRunsFlow 的 stateIn 挂 viewModelScope——不 setMain 时上游收集不启动
 *    （WorkbenchViewModelAiRefreshTest 同款纪律）。
 *
 * 🔴 user.home 隔离：VM init 读 LlmConfigStore（DataStore 落数据根）——
 *    LlmConfigStoreTest 同款纪律，防真机配置目录被测试污染。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpExposeViewModelActiveRunsTest {

    // 🔴 setUp 持有调度器引用 + tearDown drain 再 resetMain（flaky 根治纪律）
    private val dispatcher = StandardTestDispatcher()

    /**
     * DataStore IO 受控 scope（LocalDataManager 的 datastoreScope 注入缝）：Unconfined
     * 让 DataStore 文件 IO 在调用线程同步落定——drain 即全静默，无真实 IO 线程续体
     * 与 resetMain 的竞态。🔴 不得改与 Main 共享 StandardTestDispatcher 的 scope：
     * DataStore 挂起读写经 withContext 跳到注入 scope，共享 TestScheduler 会因
     * 调度器无人推进而死锁。
     */
    private val datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var originalStore: LocalDataManager

    /** user.home 隔离：VM init 读 LlmConfigStore（DataStore 真单例——数据根须落临时目录） */
    private val home = UserHomeIsolation("edt-mcp-active-runs-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
        originalStore = LlmConfigStore.store
        // redirect 窗口内 eager 解析并钉死目录（LocalDataManager 构造参数契约），
        // 还原 user.home 后的迟到首操作不得落错目录
        LlmConfigStore.store = LocalDataManager(
            dataStoreName = "test_mcp_active_runs_${java.util.UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
            datastoreScope = datastoreScope,
        )
        McpRunRegistry.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        // DataStore IO 已随 Unconfined datastoreScope 同步落定——排空即全静默
        dispatcher.scheduler.advanceUntilIdle()
        LlmConfigStore.store = originalStore
        Dispatchers.resetMain()
        home.restoreOnly() // 🔴 只还原不删（DataStore 实例仍引用该目录——目录留 /tmp）
        McpRunRegistry.resetForTest()
    }

    @Test
    fun `activeRunsFlow follows external registration reactively`() {
        val vm = McpExposeViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        // 核心判别：VM 已构造（窗口级存活）→ 外部 MCP client 起一个 run →
        // 流值须即时跟随——旧 getter 实现此断言必红（页面无重组源，数字恒旧值）。
        assertEquals(0, vm.activeRunsFlow.value, "初始在途数为 0")

        McpRunRegistry.register("run-1", "workbench_analyze")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.activeRunsFlow.value, "外部登记 run 后流值即时跟随（消灭重组盲区）")

        McpRunRegistry.register("run-2", "distill_run")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.activeRunsFlow.value, "第二次登记继续跟随")
    }

    @Test
    fun `activeRunsFlow follows sweep and reject paths`() {
        val vm = McpExposeViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        // 拒绝路径：容量满后被拒登记不动计数（观测面无假信号）
        repeat(McpRunRegistry.MAX_RUNS) { i ->
            McpRunRegistry.register("run-$i", "workbench_analyze")
        }
        McpRunRegistry.register("run-overflow", "workbench_analyze")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            McpRunRegistry.MAX_RUNS,
            vm.activeRunsFlow.value,
            "容量满：拒绝新会话，计数停在上限",
        )

        // 清退路径：结束态 run 被 sweep 后计数回落
        McpRunRegistry.find("run-0")?.complete("done")
        McpRunRegistry.sweepFinished()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            McpRunRegistry.MAX_RUNS - 1,
            vm.activeRunsFlow.value,
            "清退结束态 run 后计数回落（容量恢复对观测面可见）",
        )
    }
}
