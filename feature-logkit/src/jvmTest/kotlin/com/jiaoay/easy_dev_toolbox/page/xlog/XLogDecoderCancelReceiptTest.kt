package com.jiaoay.easy_dev_toolbox.page.xlog

import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.testfixtures.UserHomeIsolation
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import androidx.lifecycle.viewModelScope
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File

/**
 * XLog 解码任务取消收口单测（公开入口 addFiles/cancelTask 驱动，不放宽 protected 可见性）：
 *
 * - 取消收口回执：提交时已存在的历史产物（上次解码结果）不被取消破坏，
 *   且卡面 Cancelled 回填产物路径（不谎报「未产生产物」）；
 * - 单飞去重：同输入文件的在途任务未结束时重复提交被跳过 + StatusSignals 明示原因。
 *
 * 调度纪律同 LogTagFilterViewModelTest：Main 替换 StandardTestDispatcher，
 * 建卡/取消在测试线程同步执行（确定性——不推进调度器首卡恒在途）。
 *
 * 夹具契约：解密路输入（非明文二进制 + 加密后缀 = UNKNOWN 判型下的解密意图路由）——
 * 只有解密路才存在「产物在盘」前提；明文嫌疑后缀（txt/log）输入的冲突检测已短路
 * 空集（明文路不产新文件），覆盖裁决前提不复存在。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XLogDecoderCancelReceiptTest {

    private val homeIsolation = UserHomeIsolation("edt-xlog-cancel-test")

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        Dispatchers.setMain(dispatcher)
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        Thread.sleep(100)
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        homeIsolation.restoreOnly()
        StatusSignals.clearForTest()
    }

    private val createdVms = mutableListOf<XLogDecoderViewModel>()

    private fun vm(): XLogDecoderViewModel = XLogDecoderViewModel().also { createdVms += it }

    private fun workDir(): File = Files.createTempDirectory("edt-xlog-cancel").toFile()

    /** 等任务落终态（真实 IO 续体落回 Main 队列，advanceUntilIdle 排空） */
    private fun awaitTerminal(vm: XLogDecoderViewModel, taskId: String): TaskUiStatus {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            dispatcher.scheduler.advanceUntilIdle()
            val task = vm.tasks.value.firstOrNull { it.id == taskId } ?: continue
            if (task.status !is TaskUiStatus.Running) return task.status
            Thread.sleep(10)
        }
        fail("任务未在时限内落终态：${vm.tasks.value}")
    }

    @Test
    fun `cancel keeps pre-existing product intact and backfills receipt`() {
        val viewModel = vm()
        val dir = workDir()
        // 非明文二进制 + 加密后缀：UNKNOWN 判型走解密路（明文嫌疑后缀会短路冲突检测）
        val input = File(dir, "sample.xlog")
            .apply { writeBytes(byteArrayOf(0x00, 0x01, 0x7F, 0x33, 0x6B, 0x00, 0x11)) }
        val previous = File(dir, "sample.xlog.txt").apply { writeText("PREVIOUS-DECODE") }

        // 产物在盘 → 挂覆盖裁决（重跑 affordance），确认覆盖后才建卡
        viewModel.addFiles(listOf(input))
        kotlin.test.assertNotNull(viewModel.overwriteRequest.value, "产物在盘须挂覆盖裁决")
        viewModel.confirmOverwrite()
        // 未推进调度器（process 未启动）即取消——历史产物必须原样保留
        viewModel.cancelTask(viewModel.tasks.value.single().id)
        val status = awaitTerminal(viewModel, viewModel.tasks.value.single().id)

        val cancelled = status as? TaskUiStatus.Cancelled
        assertTrue(cancelled != null, "取消应落 Cancelled：$status")
        assertEquals(previous.absolutePath, cancelled.partialOutput, "提交时已存在的产物应如实回填卡面")
        assertEquals("PREVIOUS-DECODE", previous.readText(), "取消不得破坏提交前已存在的历史产物")
    }

    @Test
    fun `duplicate submit while in flight is skipped with reason toast`() {
        val viewModel = vm()
        val dir = workDir()
        val input = File(dir, "sample.log").apply { writeText("plain log line\n") }

        // 不推进调度器：首卡恒在途，第二次提交必撞去重（确定性，不依赖解码时长）
        viewModel.addFiles(listOf(input))
        viewModel.addFiles(listOf(input))

        assertEquals(1, viewModel.tasks.value.size, "同产物目标的重复提交应被跳过")
        assertTrue(
            StatusSignals.toast.value.any { "已跳过" in it.text },
            "拒绝必须带原因（StatusSignals 明示）：${StatusSignals.toast.value}",
        )
    }
}
