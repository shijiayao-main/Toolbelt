package com.jiaoay.easy_dev_toolbox.page.shelltools

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 历史多选执行序保障验收：切排序即清空勾选。
 *
 * [ShellToolsViewModel.executeSelectedHistory] 按当前 [displayHistory] 展示顺序
 * 展开命令，而 displayHistory 随排序模式实时重排——多选模式下勾选集合映射到的
 * 执行序会随排序静默改变（用户以为 A→B、实际 B→A）。setHistorySortMode
 * 在 selectionMode 时先清空勾选（面板排序段同时禁用 + tooltip，此处断言 VM 兜底
 * 面的纵深防御语义）。
 *
 * 勾选态注入经 internal toggleHistorySelection 直写（历史恢复链路是真实 DataStore
 * 读取，无须等待——executeSelectedHistory 的排序展开才依赖 displayHistory，本
 * 验收只钉「切排序清空勾选」决策点本身）。
 *
 * 🔴 VM 直构（测试缝）：与 [ShellToolsViewModelDeviceSelectionTest] 同款
 * user.home 隔离 + 隔离 DataStore + Main dispatcher 替换配方。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellToolsViewModelSortSelectionTest {

    /** user.home 隔离（共享件 UserHomeIsolation） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-sh07-test")

    private val dispatcher = StandardTestDispatcher()
    private lateinit var originalStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
        originalStore = ShellToolsDataStore.store
        ShellToolsDataStore.store = LocalDataManager(
            dataStoreName = "test_vm_sort_${UUID.randomUUID()}",
            // redirect 窗口内解析隔离目录注入构造：迟到首操作的落盘路径也钉死在临时目录
            datastoreDir = FileUtils.getDatastoreDir(),
        )
    }

    @AfterTest
    fun tearDown() {
        // 在途收束后才还原环境：DataStore 落盘路径在实例首读写时才解析一次
        // （FileStorage.createConnection 经 lazy delegate 只调一次 produceFile），
        // setUp 构造注入已把目录钉死在隔离窗口内，跨还原点的迟到写也落临时目录；
        // sort 写已在用例内经 setSortModeAwaitingPersist join 落盘，本处再兜底
        // cancel 本用例 VM 协程。tempHome 不强删（restoreOnly）：DataStore 实例无
        // close，强删会在途 IO 撞已删目录（UserHomeIsolation 纪律——目录留 /tmp
        // 由系统清理）。
        createdVms.forEach { it.viewModelScope.cancel() }
        homeIsolation.restoreOnly()
        ShellToolsDataStore.store = originalStore
        Dispatchers.resetMain()
    }

    private val createdVms = mutableListOf<ShellToolsViewModel>()

    private fun vm(): ShellToolsViewModel = ShellToolsViewModel().also { createdVms += it }

    /**
     * 切排序并等待其持久化写落盘：setSortMode 经 ShellToolsRuntime.history 在
     * App 级 scope 上 fire-and-forget launch 写（无返回句柄），history 持有的
     * store 在 ShellToolsRuntime 类初始化时捕获（其落盘路径已随构造注入钉死在
     * 隔离目录）。快照 ShellToolsRuntime.scope 子 Job、join 本次调用新增项，把
     * 写钉死在用例内（新增项恒为 sort 写：长驻收集协程在 VM 构造时已启动、已在
     * 快照内）——不 join 则写滞留过 tearDown 收束点，断言读不到落定结果。
     */
    private fun setSortModeAwaitingPersist(vm: ShellToolsViewModel, mode: ShellHistorySortMode) {
        val runtimeChildren = ShellToolsRuntime.scope.coroutineContext.job.children
        val before = runtimeChildren.toSet()
        vm.setHistorySortMode(mode)
        val pending = runtimeChildren.filter { it !in before }.toList()
        runBlocking { pending.joinAll() }
    }

    @Test
    fun `switching sort mode clears selection while in selection mode`() {
        val viewModel = vm()
        viewModel.toggleHistorySelectionMode() // 进多选
        viewModel.toggleHistorySelection("cmd-a")
        viewModel.toggleHistorySelection("cmd-b")
        assertEquals(setOf("cmd-a", "cmd-b"), viewModel.historySelectedIds.value)

        setSortModeAwaitingPersist(viewModel, ShellHistorySortMode.Recent)

        assertEquals(
            emptySet(),
            viewModel.historySelectedIds.value,
            "多选中切排序必须清空勾选——否则执行序随排序静默改变，多选执行序失去可预期性",
        )
        assertEquals(
            ShellHistorySortMode.Recent,
            viewModel.historySortMode.value,
            "清空勾选不应吞掉排序切换本身",
        )
        // 多选模式本身保持（只清勾选集合，不强制退出多选——用户可重新勾选）
        assertTrue(viewModel.historySelectionMode.value, "清空勾选后多选模式保持，用户可重新勾选")
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `switching sort mode outside selection mode is a plain sort change`() {
        val viewModel = vm()
        // 非多选：切排序是普通操作（现状语义不变）
        setSortModeAwaitingPersist(viewModel, ShellHistorySortMode.Recent)
        assertEquals(ShellHistorySortMode.Recent, viewModel.historySortMode.value)
        assertEquals(emptySet(), viewModel.historySelectedIds.value)

        // 退出多选时既有 toggle 语义已清空勾选，再切排序也不复活
        setSortModeAwaitingPersist(viewModel, ShellHistorySortMode.MostUsed)
        assertEquals(ShellHistorySortMode.MostUsed, viewModel.historySortMode.value)
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `exiting selection mode then switching sort keeps selection empty`() {
        val viewModel = vm()
        viewModel.toggleHistorySelectionMode() // 进多选
        viewModel.toggleHistorySelection("cmd-a")
        viewModel.toggleHistorySelectionMode() // 退出多选（既有语义清空勾选）
        assertEquals(emptySet(), viewModel.historySelectedIds.value)

        setSortModeAwaitingPersist(viewModel, ShellHistorySortMode.Recent)
        assertEquals(
            emptySet(),
            viewModel.historySelectedIds.value,
            "退出多选后切排序不得复活已清空的勾选",
        )
        viewModel.viewModelScope.cancel()
    }
}
