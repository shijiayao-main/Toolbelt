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
import kotlin.test.assertNull
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
 * XLog 解密页内容判型三态路由单测（公开入口 addFiles 驱动，不放宽 protected 可见性）：
 *
 * - 明文路（PLAINTEXT）：产物即源文件自身、note 明示未生成新文件、源目录零新文件
 *   （多余拷贝的产物副本 = 同内容双文件，下游送飞书/送过滤拿哪一个不可预期）；
 * - UNKNOWN 无解密意图后缀：明示失败（人话回执），不猜不写盘；
 * - 明文嫌疑后缀（txt/log）的覆盖冲突短路：明文路不产新文件，重跑不弹覆盖确认
 *   （同目录遗留的历史 .txt.txt 产物不触发裁决卡）。
 *
 * 调度纪律同 XLogDecoderCancelReceiptTest：Main 替换 StandardTestDispatcher，
 * process 走真实 IO，awaitTerminal 轮询落终态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XLogDecoderPlaintextRoutingTest {

    private val homeIsolation = UserHomeIsolation("edt-xlog-plaintext-test")

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

    private fun workDir(): File = Files.createTempDirectory("edt-xlog-plaintext").toFile()

    /** 行首时间戳契约（LogTimestamp.isTimeLine 同款形态）的明文日志行 */
    private fun plaintextLines(): String =
        "10-14 09:30:00.001 E/main: heartbeat ok\n" +
            "10-14 09:30:01.002 D/vibration: tick\n"

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
    fun `plaintext file succeeds with source as product and zero new files`() {
        val viewModel = vm()
        val dir = workDir()
        val source = File(dir, "plain.txt").apply { writeText(plaintextLines()) }

        viewModel.addFiles(listOf(source))
        val status = awaitTerminal(viewModel, viewModel.tasks.value.single().id)

        val success = status as? TaskUiStatus.Success
        assertTrue(success != null, "明文输入应落 Success：$status")
        assertEquals(source.absolutePath, success.outputPath, "明文路产物 = 源文件自身")
        assertTrue(
            success.note.orEmpty().contains("明文"),
            "成功回执须带明文标注（未生成新文件须用户可知）：${success.note}",
        )
        assertEquals(
            listOf("plain.txt"),
            dir.listFiles()?.map { it.name }?.sorted(),
            "明文路不得在源目录落任何新文件",
        )
    }

    @Test
    fun `binary blob without decode-intent extension fails with plain reason`() {
        val viewModel = vm()
        val dir = workDir()
        // 固定字节（无换行、无时间戳形态、无段结构 magic）——判型恒 UNKNOWN，不依赖随机
        val blob = File(dir, "blob").apply {
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x7F, 0x33.toByte(), 0x6B, 0x11, 0x00))
        }

        viewModel.addFiles(listOf(blob))
        val status = awaitTerminal(viewModel, viewModel.tasks.value.single().id)

        val error = status as? TaskUiStatus.Error
        assertTrue(error != null, "无后缀二进制应落 Error：$status")
        assertTrue(
            "无法识别" in error.message,
            "失败原因须人话明示（非英文异常直上）：${error.message}",
        )
        assertEquals(
            listOf("blob"),
            dir.listFiles()?.map { it.name }?.sorted(),
            "拒绝路不得在源目录写任何文件",
        )
    }

    @Test
    fun `plaintext-suspect extension skips overwrite conflict even with stale product`() {
        val viewModel = vm()
        val dir = workDir()
        val source = File(dir, "notes.txt").apply { writeText(plaintextLines()) }
        val stale = File(dir, "notes.txt.txt").apply { writeText("STALE-PRODUCT") }

        viewModel.addFiles(listOf(source))

        assertNull(
            viewModel.overwriteRequest.value,
            "明文嫌疑后缀不产新文件——同目录遗留历史产物不得触发覆盖确认",
        )
        assertEquals(1, viewModel.tasks.value.size, "无冲突文件应直接入卡（不被挂起裁决阻断）")

        val status = awaitTerminal(viewModel, viewModel.tasks.value.single().id)
        val success = status as? TaskUiStatus.Success
        assertTrue(success != null, "明文输入应落 Success：$status")
        assertEquals(source.absolutePath, success.outputPath, "明文路产物 = 源文件自身")
        assertEquals("STALE-PRODUCT", stale.readText(), "明文路不写盘——历史产物原样保留")
    }
}
