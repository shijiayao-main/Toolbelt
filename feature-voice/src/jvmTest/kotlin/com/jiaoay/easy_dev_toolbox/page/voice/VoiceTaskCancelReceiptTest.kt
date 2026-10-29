package com.jiaoay.easy_dev_toolbox.page.voice

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.widget.TaskUiStatus
import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 转写任务取消收口单测（公开入口 addFiles/cancelTask 驱动；启动前取消——转写器
 * 不触 JNI）：
 *
 * - 取消收口回执：提交时已存在的历史转写稿不被取消破坏，卡面 Cancelled 回填
 *   产物路径（不谎报「未产生产物」）；
 * - 单飞去重：同音频的在途任务未结束时重复提交被跳过 + StatusSignals 明示原因
 *   （同产物 = 同名转写稿）。
 *
 * user.home 隔离手写三行（feature-voice 无 :test-fixtures 依赖——VoiceAttributionTest
 * 同源口径）；Main 替换 StandardTestDispatcher（建卡/取消测试线程同步执行）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTaskCancelReceiptTest {

    private val originalHome: String? = System.getProperty("user.home")
    private val tempHome: File = Files.createTempDirectory("edt-voice-cancel-test").toFile()

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        System.setProperty("user.home", tempHome.absolutePath)
        Dispatchers.setMain(dispatcher)
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        // drain 纪律（DataStore 在途写安全——只还原不删临时目录）
        Thread.sleep(100)
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        originalHome?.let { System.setProperty("user.home", it) }
        StatusSignals.clearForTest()
    }

    private val createdVms = mutableListOf<VoiceViewModel>()

    private fun vm(): VoiceViewModel = VoiceViewModel().also { createdVms += it }

    private fun transcriptOf(audioName: String): File =
        File(File(FileUtils.getAppDataDir(), "voice/transcripts"), "$audioName.md")

    @Test
    fun `cancel keeps pre-existing transcript intact and backfills receipt`() {
        val viewModel = vm()
        val dir = Files.createTempDirectory("edt-voice-cancel").toFile()
        // 音频文件名固定（转写稿按 `<原名>.md` 命名——随机临时名会错位）
        val audio = File(dir, "meeting.wav").apply { writeBytes(ByteArray(16)) }
        val previous = transcriptOf("meeting").apply {
            parentFile.mkdirs()
            writeText("PREVIOUS-TRANSCRIPT")
        }

        // 产物在盘 → 挂覆盖裁决（重跑 affordance），确认覆盖后才建卡
        viewModel.addFiles(listOf(audio))
        kotlin.test.assertNotNull(viewModel.overwriteRequest.value, "产物在盘须挂覆盖裁决")
        viewModel.confirmOverwrite()
        // 未推进调度器（process 未启动）即取消——历史转写稿必须原样保留
        viewModel.cancelTask(viewModel.tasks.value.single().id)
        dispatcher.scheduler.advanceUntilIdle()

        val task = viewModel.tasks.value.single()
        val status = task.status as? TaskUiStatus.Cancelled
        assertTrue(status != null, "取消应落 Cancelled：${task.status}")
        assertEquals(previous.absolutePath, status.partialOutput, "提交时已存在的转写稿应如实回填卡面")
        assertEquals("PREVIOUS-TRANSCRIPT", previous.readText(), "取消不得破坏提交前已存在的历史转写稿")
    }

    @Test
    fun `duplicate submit while in flight is skipped with reason toast`() {
        val viewModel = vm()
        val dir = Files.createTempDirectory("edt-voice-cancel-dup").toFile()
        val audio = File(dir, "meeting.wav").apply { writeBytes(ByteArray(16)) }

        // 不推进调度器：首卡恒在途，第二次提交必撞去重（同产物 = 同名转写稿）
        viewModel.addFiles(listOf(audio))
        viewModel.addFiles(listOf(audio))

        assertEquals(1, viewModel.tasks.value.size, "同转写稿的重复提交应被跳过")
        assertTrue(
            StatusSignals.toast.value.any { "已跳过" in it.text },
            "拒绝必须带原因（StatusSignals 明示）：${StatusSignals.toast.value}",
        )
    }
}
