package com.jiaoay.easy_dev_toolbox.page.voice

import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * V1 契约回归：aiEnabled 经 [com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal]
 * 订阅消费（AiEnabledSignal KDoc 明文禁 init 一次性快照——VM 窗口级存活下快照永不刷新）。
 *
 * 判别力：镜像流翻转后 VM 消费面即时跟随——init 快照实现此断言必红（陈旧值恒不变）。
 * user.home 隔离手写三行（VoiceTaskCancelReceiptTest 同源口径）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAiEnabledSignalTest {

    private val originalHome: String? = System.getProperty("user.home")
    private val tempHome: File = Files.createTempDirectory("edt-voice-ai-signal-test").toFile()

    private val dispatcher = StandardTestDispatcher()

    private val createdVms = mutableListOf<VoiceViewModel>()

    @BeforeTest
    fun setUp() {
        System.setProperty("user.home", tempHome.absolutePath)
        Dispatchers.setMain(dispatcher)
        com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal.setForTest(false)
    }

    @AfterTest
    fun tearDown() {
        Thread.sleep(100)
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        originalHome?.let { System.setProperty("user.home", it) }
        com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal.setForTest(false)
    }

    private fun vm(): VoiceViewModel = VoiceViewModel().also { createdVms += it }

    @Test
    fun `aiEnabled follows signal live instead of init snapshot`() {
        val viewModel = vm()
        assertEquals(false, viewModel.aiEnabled.value, "前置：信号 OFF")

        com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal.setForTest(true)

        assertEquals(true, viewModel.aiEnabled.value, "信号翻转即时跟随（init 快照实现此断言必红）")

        com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal.setForTest(false)
        assertEquals(false, viewModel.aiEnabled.value, "回关即时跟随（降级面不再滞留陈旧值）")
    }

    @Test
    fun `aiEnabled consumed after vm construction reflects signal at read time`() {
        // VM 构造于信号 ON 之后——消费面读到 ON（快照读实现若在 init 落后一拍则恒 false）
        com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal.setForTest(true)
        val viewModel = vm()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.aiEnabled.value, "构造期信号 ON 即消费 ON")
    }
}
