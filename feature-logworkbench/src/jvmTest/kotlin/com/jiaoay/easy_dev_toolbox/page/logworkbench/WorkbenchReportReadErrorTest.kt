package com.jiaoay.easy_dev_toolbox.page.logworkbench

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.testfixtures.UserHomeIsolation
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 回看第三态测试（报告文件读取失败——与「从未生成过报告」的区分语义）：
 * - COMPLETED run 的 reportPath 指向的文件被外部删除后 openRun → 第三态（带路径）
 *   而非纯 null 空态——右栏据此渲染「报告文件读取失败（run 记录仍在）」错误态；
 * - 读取成功 / 从未生成报告（reportPath = null）两条对照面第三态恒 null——
 *   正常回看与空态语义零回归。
 *
 * VM 直测纪律（WorkbenchReportExportActionTest 同款配方）：Main dispatcher 替换 +
 * user.home 隔离（VM 构造触 Database.database() 真单例）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchReportReadErrorTest {

    private val dispatcher = StandardTestDispatcher()

    /** user.home 隔离：VM 构造触 Database.database() 真单例——数据根须落临时目录 */
    private val home = UserHomeIsolation("edt-wb-report-read-error-test")

    private val createdVms = mutableListOf<WorkbenchViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        home.restoreOnly() // 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp）
        Database.resetForTest() // 进程单例钉在隔离 home 上——不重置会跨测试类污染
    }

    private fun vmTracked(): WorkbenchViewModel =
        WorkbenchViewModel(ioDispatcher = dispatcher).also { createdVms += it }

    /** 轮询推进断言兜底（WorkbenchReportExportActionTest 同款配方） */
    private fun awaitTrue(probe: () -> Boolean): Boolean {
        repeat(100) {
            if (probe()) return true
            Thread.sleep(20)
            dispatcher.scheduler.advanceUntilIdle()
        }
        return probe()
    }

    private fun seedRun(id: String, reportPath: String?) {
        val store = RunStore(Database.database())
        store.create(
            id = id,
            title = "$id @ 09-19 10:29:50.000",
            logFiles = listOf("/tmp/camera-sample.alog.txt"),
            targetTime = "09-19 10:29:50.000",
            rangeMinutes = 5L,
            status = RunStore.RunStatus.COMPLETED,
        )
        reportPath?.let { store.updateReportPath(id, it) }
    }

    @Test
    fun `报告文件被删后回看-读取失败第三态带路径`() {
        val reportFile = File(home.home, "missing-report.md").apply {
            writeText("## 结论\n\n——样本结论。\n")
        }
        seedRun("run-read-error-1", reportFile.absolutePath)
        // 模拟外部清理：run 记录仍在（COMPLETED + reportPath），文件本体已消失
        reportFile.delete()

        val vm = vmTracked()
        vm.openRun("run-read-error-1")
        assertTrue(
            awaitTrue { vm.reportReadError.value != null },
            "读取失败须呈现第三态（非 null）——与「从未生成过报告」不可混淆",
        )
        assertEquals(
            reportFile.absolutePath,
            vm.reportReadError.value,
            "第三态载荷 = 读失败的 reportPath（错误态路径出路的依据）",
        )
        assertNull(
            vm.reportText.value,
            "报告文本保持 null（渲染面由第三态接管——错误态非空态非报告态）",
        )
    }

    @Test
    fun `读取成功与从未生成报告-第三态保持null`() {
        val reportFile = File(home.home, "report-ok.md").apply {
            writeText("## 结论\n\n——A 的结论。\n")
        }
        seedRun("run-read-ok", reportFile.absolutePath)
        seedRun("run-no-report", null)

        val vm = vmTracked()
        // 对照面 1：从未生成报告（reportPath = null）= 空态语义，第三态不置位
        vm.openRun("run-no-report")
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.reportReadError.value, "无报告路径 = 「尚无报告」空态语义，第三态不置位")

        // 对照面 2：读取成功 = 正常回填，第三态不置位
        vm.openRun("run-read-ok")
        assertTrue(awaitTrue { vm.reportText.value != null }, "前置：报告正常回填")
        assertNull(vm.reportReadError.value, "读取成功 = 第三态不置位（正常回看零回归）")
    }
}
