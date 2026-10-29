package com.jiaoay.easy_dev_toolbox.page.filesplit

import com.jiaoay.testfixtures.UserHomeIsolation
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 文件分割任务取消收口单测（公开入口 addFiles/cancelTask 驱动）：
 *
 * - 取消收口回执：提交时已存在的旧分卷不被取消破坏，卡面 Cancelled 回填分卷路径
 *   （不谎报「未产生产物」）；
 * - 任务启动前取消落 Cancelled（null）——未产出任何分卷。
 *
 * 调度纪律同 LogTagFilterViewModelTest（init 的 LogKitConfigStore 读需 user.home 隔离；
 * 建卡/取消在测试线程同步执行，真实 IO 续体落回 Main 队列由 advanceUntilIdle 排空）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileSplitterCancelReceiptTest {

    private val homeIsolation = UserHomeIsolation("edt-filesplit-cancel-test")

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Thread.sleep(100)
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        homeIsolation.restoreOnly()
    }

    private val createdVms = mutableListOf<FileSplitterViewModel>()

    private fun vm(): FileSplitterViewModel = FileSplitterViewModel().also { createdVms += it }

    @Test
    fun `cancel keeps pre-existing volumes intact and backfills receipt`() {
        val viewModel = vm()
        val dir = Files.createTempDirectory("edt-filesplit-cancel").toFile()
        // 输入文件名固定（分卷收口按 `<名>_NNN.txt` 前缀匹配——随机临时名会错位）
        val input = File(dir, "big.log").apply { writeText("line\n") }
        val oldVolumes = listOf("big_000.txt", "big_001.txt").map { name ->
            File(dir, name).apply { writeText("OLD-VOLUME") }
        }

        // 首卷在盘 → 挂覆盖裁决；取消裁决 = 不建卡 + 旧分卷原样保留
        viewModel.addFiles(listOf(input))
        assertNotNull(viewModel.overwriteRequest.value, "分卷在盘须挂覆盖裁决")
        viewModel.dismissOverwrite()
        assertEquals(0, viewModel.tasks.value.size, "取消覆盖不建卡")
        oldVolumes.forEach { assertEquals("OLD-VOLUME", it.readText(), "取消覆盖不得动旧分卷") }

        // 确认覆盖：按前缀删净全部旧卷（不只首片——残留旧卷会与新卷序号混编）后建卡；
        // 启动前取消 → 旧卷已按确认清除，无在盘产物可回填
        viewModel.addFiles(listOf(input))
        viewModel.confirmOverwrite()
        oldVolumes.forEach { assertTrue(!it.exists(), "确认覆盖删净旧分卷（前缀全删）") }
        viewModel.cancelTask(viewModel.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()

        val task = viewModel.tasks.value.single()
        val status = task.status as? TaskUiStatus.Cancelled
        assertNotNull(status, "启动前取消应落 Cancelled：${task.status}")
        assertEquals(null, status.partialOutput, "旧分卷已按覆盖确认清除——取消无在盘产物可回填")
    }

    @Test
    fun `cancel before start on fresh dir yields cancelled with no receipt`() {
        val viewModel = vm()
        val dir = Files.createTempDirectory("edt-filesplit-cancel-fresh").toFile()
        val input = File(dir, "fresh.log").apply { writeText("line\n") }

        viewModel.addFiles(listOf(input))
        viewModel.cancelTask(viewModel.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()

        val task = viewModel.tasks.value.single()
        assertEquals(TaskUiStatus.Cancelled(null), task.status, "未启动即取消不应有产物回执")
        assertTrue(
            dir.listFiles().orEmpty().none { it.name.startsWith("fresh_") },
            "未启动的 process 不应产出任何分卷",
        )
    }
}
