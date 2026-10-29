package com.jiaoay.easy_dev_toolbox.page.logworkbench

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.testfixtures.UserHomeIsolation
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 管线直跑运行态三面（在途位四面接线）+ 孤儿 RUNNING 归一化（启动清扫）：
 * - 直跑发起即置 pipelineRunning（busy 渲染源）；
 * - 直跑重复发起被守卫拦截（单 run 创建）；
 * - 直跑在途 cancel 收口 ABORTED + 在途位复位；
 * - 直跑在途 openRun 深链被设防（session 不被置换）；
 * - VM 启动时库内孤儿 RUNNING 行全部归一 FAILED。
 *
 * 🔴 Main dispatcher 必须替换（WorkbenchPipelineMultiFileToastTest 同款纪律）；
 *    VM 构造触 Database.database() 真单例——user.home 隔离 + Database.resetForTest。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchPipelineDirectRunTest {

    private val dispatcher = StandardTestDispatcher()

    private val home = UserHomeIsolation("edt-wb-pipeline-direct-run-test")

    // IO 调度器注入缝：VM 全部 IO 段与 Main 共享同一 TestScheduler——init/收尾
    // 协程不飞真实 IO 线程，用例收尾 advanceUntilIdle 即确定性排空（无跨用例续体）。
    private val createdVms = mutableListOf<WorkbenchViewModel>()

    private fun vmTracked(): WorkbenchViewModel =
        WorkbenchViewModel(ioDispatcher = dispatcher).also { createdVms += it }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        home.restoreOnly()
        Database.resetForTest()
        StatusSignals.clearForTest()
    }

    /** 轮询推进断言兜底：注入后 advanceUntilIdle 已确定性收口，循环首拍即中，防御性保留 */
    private fun awaitTrue(probe: () -> Boolean): Boolean {
        repeat(100) {
            if (probe()) return true
            Thread.sleep(20)
            dispatcher.scheduler.advanceUntilIdle()
        }
        return probe()
    }

    @Test
    fun `direct run sets pipeline running and cancel settles aborted`() {
        val dir = Files.createTempDirectory("wb-direct-run")
        try {
            val log = dir.resolve("in.alog").toFile()
            log.writeText("09-19 10:29:50.000 E/Test: boom\n")
            val vm = vmTracked()
            dispatcher.scheduler.advanceUntilIdle()

            vm.setLogFiles(listOf(log.absolutePath))
            vm.runPipelineOnly()
            assertTrue(vm.pipelineRunning.value, "直跑发起即在途（busy 渲染/中止钮数据源）")
            val runId = vm.currentRunIdForUi()

            vm.cancel()

            assertFalse(vm.pipelineRunning.value, "取消复位直跑在途位")
            val status = awaitTrue {
                RunStore(Database.database()).runById(runId)?.status == RunStore.RunStatus.ABORTED
            }
            assertTrue(status, "直跑取消收口 ABORTED（库行状态 ≠ RUNNING 不变量）")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `re-entrant direct run is rejected while in flight`() {
        val dir = Files.createTempDirectory("wb-direct-run-guard")
        try {
            val log = dir.resolve("in.alog").toFile()
            log.writeText("09-19 10:29:50.000 E/Test: boom\n")
            val vm = vmTracked()
            dispatcher.scheduler.advanceUntilIdle()

            vm.setLogFiles(listOf(log.absolutePath))
            vm.runPipelineOnly()
            vm.runPipelineOnly() // 在途重复点击——守卫拦截（同虚拟时刻两连击场景）

            assertEquals(1, RunStore(Database.database()).listRuns().size, "在途重复发起不建第二个 run")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `openRun deep link is guarded while pipeline in flight`() {
        val dir = Files.createTempDirectory("wb-direct-run-open")
        try {
            val log = dir.resolve("in.alog").toFile()
            log.writeText("09-19 10:29:50.000 E/Test: boom\n")
            val store = RunStore(Database.database())
            store.create(id = "run-seed", title = "seed", logFiles = listOf(log.absolutePath))
            val vm = vmTracked()
            dispatcher.scheduler.advanceUntilIdle()

            vm.setLogFiles(listOf(log.absolutePath))
            vm.runPipelineOnly()
            val directRunId = vm.currentRunIdForUi()

            vm.openRun("run-seed") // 直跑在途深链——设防

            assertEquals(directRunId, vm.currentRunIdForUi(), "直跑在途 openRun 不置换会话（产物写回发起会话）")
            assertEquals(
                2,
                store.listRuns().size,
                "深链被拒不产生副作用（种子 run 仍在）",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `pipeline abort settles run failed with summary report`() {
        // 不存在的文件 → 管线首个阶段即 Abort（LogAnalysisPipeline 契约：不抛异常返回 Abort）
        val vm = vmTracked()
        dispatcher.scheduler.advanceUntilIdle()

        vm.setLogFiles(listOf("/nonexistent/wb-direct-run/missing.alog"))
        vm.runPipelineOnly()
        assertTrue(vm.pipelineRunning.value, "直跑发起即在途")
        val runId = vm.currentRunIdForUi()

        assertTrue(
            awaitTrue { RunStore(Database.database()).runById(runId)?.status == RunStore.RunStatus.FAILED },
            "管线 Abort 也是失败终态——库行落 FAILED（库行状态 ≠ RUNNING 不变量）",
        )
        assertFalse(vm.pipelineRunning.value, "收口后直跑在途位复位")
        assertTrue(
            vm.reportText.value?.contains("管线中止") == true,
            "落盘文本为降级态报告（五部分骨架，非 LLM 报告）",
        )
    }

    @Test
    fun `orphan running runs normalize to failed on vm start`() {
        val store = RunStore(Database.database())
        store.create(id = "run-orphan", title = "orphan", logFiles = listOf("/tmp/x.alog"), status = RunStore.RunStatus.RUNNING)

        val vm = vmTracked()

        assertTrue(
            awaitTrue { store.runById("run-orphan")?.status == RunStore.RunStatus.FAILED },
            "启动归一化：孤儿 RUNNING 置 FAILED（跨重启不谎报运行中）",
        )
    }
}
