package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.navigation.AiEnabledSignal
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AI 开关改进页刷新守护——消灭快照陈旧。
 *
 * 旧缺陷：WorkbenchViewModel init 一次性快照读 LlmConfigStore.getAiEnabled()，
 * VM 窗口级存活不重读——设置页开启 AI 后回到本页仍按「未配置」降级。
 *
 * 新形态：VM 的 aiEnabled 直读 :core 中立镜像槽 [AiEnabledSignal]（真源写点
 * 同文件顺手 mirror）——开关翻转对 VM 即时可见，零陈旧窗口。
 *
 * 🔴 Main dispatcher 必须替换（viewModelScope = Dispatchers.Main.immediate）：
 *    VM init 的 refreshRuns 挂 viewModelScope——不 setMain 时协程不执行，
 *    init 内 launch 的行为断言必假失败（AiSettingsViewModelTest 同款纪律）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchViewModelAiRefreshTest {

    // 🔴 tearDown 须先 drain 再 resetMain（AiSettingsViewModelTest 同款纪律）：
    //    resetMain 后再 drain 的续体会撞空 Main 自检。
    private val dispatcher = StandardTestDispatcher()

    /** user.home 隔离：VM 构造触 Database.database() 真单例——数据根须落临时目录 */
    private val home = UserHomeIsolation("edt-wb-ai-refresh-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
        AiEnabledSignal.setForTest(false)
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
        home.restoreOnly() // 🔴 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp）
        // 🔴 Database 进程单例会在此类的隔离 user.home 上懒初始化钉住空临时库——
        //    不重置会跨测试类污染（WorkbenchSessionTest 的 knowledge 装配门控读到空库、
        //    knowledge 三件不装 → 顺序型 flaky）。
        Database.resetForTest() // 🔴 只还原不删（DataStore 在途写 flaky 先例——目录留 /tmp）
        AiEnabledSignal.setForTest(false)
    }


    @Test
    fun `aiEnabled reads mirror slot not one-shot snapshot`() {
        // 构造前真源翻转（= 用户已在设置页开启 AI，且发生在本页 VM 构造之后/之前皆同效）
        AiEnabledSignal.setForTest(true)
        val vm = vmTracked()
        assertTrue(
            vm.aiEnabled.value,
            "VM 构造即见镜像现值——不再 init 一次性快照（快照陈旧缺陷的判别锚）",
        )
    }

    @Test
    fun `toggle flip after VM construction is immediately visible`() {
        // 核心判别：VM 已构造（窗口级存活）→ 开关翻转 → aiEnabled 须即时跟随——
        // 旧快照实现此断言必红（init 后永不重读）。
        val vm = vmTracked()
        assertFalse(vm.aiEnabled.value, "初始镜像为关（fail-closed）")

        AiEnabledSignal.setForTest(true)

        assertTrue(
            vm.aiEnabled.value,
            "开关翻转后 VM 侧零刷新动作即见新值——响应式镜像消灭陈旧快照",
        )
        // 页面消费面同源：LogWorkbenchPage/WorkbenchSidePanelContent collectAsState
        // 消费同一 StateFlow 实例（VM getter 直转 :core 槽）。
        assertEquals(AiEnabledSignal.enabled, vm.aiEnabled, "VM 暴露流必须就是镜像槽本体（零拷贝）")
    }

    @Test
    fun `mirror slot and VM flow are the same instance across VMs`() {
        // 多 VM（页面 + 侧栏槽位各 viewModel{} 解析同实例，但即便异实例也须同源）：
        // aiEnabled 单源 = AiEnabledSignal——不存在第二份独立状态。
        val vmA = vmTracked()
        val vmB = vmTracked()
        assertEquals(
            vmA.aiEnabled,
            vmB.aiEnabled,
            "全部 VM 的 aiEnabled 流同源（:core 槽单实例）——状态不随 VM 分叉",
        )
    }
}
