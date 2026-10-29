package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 管线直跑多文件明示守护。
 *
 * 多文件 + AI 未开启时 runPipelineOnly 只分析首个文件——旧行为静默忽略其余，
 * 经 [StatusSignals.show] toast 明示「仅分析 N 个文件中的第 1 个」。
 * 单文件零变化（不发 toast）。
 *
 * 🔴 StatusSignals 是进程级 object 单例——断言前 [StatusSignals.clearForTest]
 *    清场（StatusSignalsStackTest 提供的测试缝）。
 * 🔴 Main dispatcher 必须替换（WorkbenchViewModelAiRefreshTest 同款纪律）。
 * 🔴 VM 构造触 Database.database() 真单例——user.home 隔离。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchPipelineMultiFileToastTest {

    private val dispatcher = StandardTestDispatcher()

    private val home = UserHomeIsolation("edt-wb-pipeline-toast-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
        StatusSignals.clearForTest()
    }

    // IO 调度器注入缝：VM 全部 IO 段与 Main 共享同一 TestScheduler——init/收尾
    // 协程不飞真实 IO 线程，用例收尾 advanceUntilIdle 即确定性排空（无跨用例续体）。
    private val createdVms = mutableListOf<WorkbenchViewModel>()

    private fun vmTracked(): WorkbenchViewModel =
        WorkbenchViewModel(ioDispatcher = dispatcher).also { createdVms += it }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        home.restoreOnly()
        // 🔴 Database 进程单例会在此类的隔离 user.home 上懒初始化钉住空临时库——
        //    不重置会跨测试类污染（WorkbenchSessionTest 的 knowledge 装配门控读到空库、
        //    knowledge 三件不装 → 顺序型 flaky）。
        Database.resetForTest()
        StatusSignals.clearForTest()
    }

    /** 轮询推进断言兜底：注入后 advanceUntilIdle 已确定性收口，循环首拍即中，防御性保留 */
    private fun awaitToast(probe: () -> Boolean): Boolean {
        repeat(100) {
            if (probe()) return true
            Thread.sleep(20)
            dispatcher.scheduler.advanceUntilIdle()
        }
        return probe()
    }

    @Test
    fun `多文件直跑 toast 明示只分析首个`() {
        val vm = vmTracked()
        vm.setLogFiles(listOf("/tmp/first.alog", "/tmp/second.alog", "/tmp/third.alog"))

        vm.runPipelineOnly()

        assertTrue(
            awaitToast { StatusSignals.toast.value.isNotEmpty() },
            "多文件 + 管线直跑必须发 toast 明示（静默忽略其余文件即回潮）",
        )
        val text = StatusSignals.toast.value.first().text
        assertTrue(
            "仅分析" in text && "3 个文件" in text,
            "toast 文案须含「仅分析」与文件总数：实际「$text」",
        )
        assertTrue(
            "first.alog" in text,
            "toast 须点名首个文件（用户可感知哪份在跑）",
        )
    }

    @Test
    fun `单文件直跑零 toast——行为零变化`() {
        val vm = vmTracked()
        vm.setLogFiles(listOf("/tmp/only.alog"))

        vm.runPipelineOnly()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            0,
            StatusSignals.toast.value.size,
            "单文件直跑不得发多文件 toast（单文件行为零变化）",
        )
    }
}
