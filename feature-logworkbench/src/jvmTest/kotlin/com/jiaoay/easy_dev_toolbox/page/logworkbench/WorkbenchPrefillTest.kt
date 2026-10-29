package com.jiaoay.easy_dev_toolbox.page.logworkbench

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
 * 深链落点回执 + prefill 不盲覆守护。
 *
 * [WorkbenchViewModel.prefill] 三态直测：
 * 1. 症状框非空且 ≠ prefillNote ⇒ 追加不覆盖（含旧内容 + 来源标注）；
 * 2. 症状框空 ⇒ 直写（现状语义）；
 * 3. 旧值恰好等于 prefillNote ⇒ 直写幂等（重复深链不重复追加）。
 * 另钉 targetTime 载荷：非空经既有归一面写入严格格式、null/空白不触碰时间域
 * （logcat 标记锚点深链的消费面）。
 *
 * 🔴 Main dispatcher 必须替换（WorkbenchViewModelAiRefreshTest 同款纪律）。
 * 🔴 VM 构造触 Database.database() 真单例——user.home 隔离。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchPrefillTest {

    private val dispatcher = StandardTestDispatcher()

    private val home = UserHomeIsolation("edt-wb-prefill-test")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
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
    }

    @Test
    fun `症状非空时深链追加不覆盖——旧内容与来源标注并存`() {
        val vm = vmTracked()
        vm.setSymptom("设置页面黑屏")

        vm.prefill(listOf("/tmp/a.alog.txt"), "XLog 解密产物深度分析")

        val symptom = vm.symptom.value
        assertTrue(
            "设置页面黑屏" in symptom,
            "用户正在编辑的症状不得被深链静默吞掉（acceptance：不覆盖）",
        )
        assertTrue(
            "（来自深度分析：XLog 解密产物深度分析）" in symptom,
            "追加内容必须带来源标注（acceptance：追加来源标注）",
        )
        assertEquals(
            "设置页面黑屏\n\n（来自深度分析：XLog 解密产物深度分析）",
            symptom,
            "追加形态 = 旧内容 + 空行 + 来源标注（卡面配方直译）",
        )
    }

    @Test
    fun `症状为空时直写——现状语义零回归`() {
        val vm = vmTracked()

        vm.prefill(listOf("/tmp/a.alog.txt"), "XLog 解密产物深度分析")

        assertEquals(
            "XLog 解密产物深度分析",
            vm.symptom.value,
            "空症状框深链直写（无追加语义——首个深链即初始预填）",
        )
    }

    @Test
    fun `旧值等于 prefillNote 时直写幂等——重复深链不重复追加`() {
        val vm = vmTracked()
        vm.prefill(listOf("/tmp/a.alog.txt"), "XLog 解密产物深度分析")

        // 同一产物重复深链（用户来回切页再点一次「深度分析」）
        vm.prefill(listOf("/tmp/a.alog.txt"), "XLog 解密产物深度分析")

        assertEquals(
            "XLog 解密产物深度分析",
            vm.symptom.value,
            "旧值 == prefillNote 时直写（幂等）——不得追加成两份来源标注",
        )
    }

    @Test
    fun `prefillNote 为空串或 null 不触碰症状框`() {
        val vm = vmTracked()
        vm.setSymptom("手填症状")

        vm.prefill(listOf("/tmp/b.txt"), null)
        vm.prefill(listOf("/tmp/c.txt"), "  ")

        assertEquals(
            "手填症状",
            vm.symptom.value,
            "null/空白 prefillNote 不得触碰症状框（isNullOrBlank 门控——现状保留）",
        )
    }

    @Test
    fun `logFiles 深链去重保序——现状语义零回归`() {
        val vm = vmTracked()
        vm.addTargetFile("/tmp/existing.txt")

        vm.prefill(listOf("/tmp/a.txt", "/tmp/a.txt"), null)

        assertEquals(
            listOf("/tmp/a.txt"),
            vm.logFiles.value,
            "logFiles 预填去重（distinct——重复深链不产生重复条目）",
        )
    }

    @Test
    fun `targetTime 深链经归一面写入——空格分隔与短毫秒归一到严格格式`() {
        val vm = vmTracked()

        // 空格分隔 + 3 位毫秒：与 setTargetTime 同一归一面（不另造第二套写入路径）
        vm.prefill(listOf("/tmp/a.txt"), null, targetTime = "2026-10-11 09:00:05.000")

        assertEquals(
            "2026-10-11_09:00:05.000",
            vm.targetTime.value,
            "预填时间点须归一到严格格式（下游管线解析契约）——Date 与 Time 分隔符归一为 _",
        )

        // 宽松形态（省略毫秒）：缺毫秒自动补 .000
        vm.prefill(listOf("/tmp/a.txt"), null, targetTime = "2026-10-11 10:30:00")
        assertEquals(
            "2026-10-11_10:30:00.000",
            vm.targetTime.value,
            "省略毫秒的宽松形态须补 .000 归一（logcat 标记锚点等生产侧载荷恒带 3 位毫秒，本面兜底宽松输入）",
        )
    }

    @Test
    fun `targetTime 为 null 或空白不触碰时间域——手填值原样保留`() {
        val vm = vmTracked()
        vm.setTargetTime("2026-10-11_08:00:00.000")

        vm.prefill(listOf("/tmp/a.txt"), null)
        vm.prefill(listOf("/tmp/b.txt"), null, targetTime = "  ")

        assertEquals(
            "2026-10-11_08:00:00.000",
            vm.targetTime.value,
            "深链不带时间点时不得触碰目标时间框（未标记送 null 的消费面——用户手填值零回归）",
        )
    }
}
