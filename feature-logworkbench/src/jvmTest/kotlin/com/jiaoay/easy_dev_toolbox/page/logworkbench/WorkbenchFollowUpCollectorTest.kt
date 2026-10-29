package com.jiaoay.easy_dev_toolbox.page.logworkbench

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunStore
import com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 追问链收集器竞速（单句柄置换 + 挂序纪律）：
 * - 追问发起必须先于新收集器挂载（session.startAnalysis 入口先置空 phaseResult）——
 *   Main.immediate 下 launch 即跑、StateFlow 对新收集器立即重放当前值，先挂后发会把
 *   上一轮报告陈值当增量收口（mergeFollowUp 旧+旧合并覆写报告 + 双 settle 写盘竞速）；
 * - 判据形态：Main dispatcher 用 UnconfinedTestDispatcher 替换（与生产 Main.immediate
 *   的「launch 即跑」语义同形——StandardTestDispatcher 队列化语义下该缺陷不可复现）；
 * - 失败回执链经 sessionFactoryForTest 注入即时失败的 executor 会话（零网络），VM 侧
 *   失败收口（settleRunAsFailed）接线由工厂形参透传，测缝不断链。
 *
 * 🔴 Main dispatcher 必须替换；VM 构造触 Database.database() 真单例——user.home 隔离
 *    + Database.resetForTest。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchFollowUpCollectorTest {

    /** Unconfined 语义 = 生产 Main.immediate 同形（launch 即跑——陈值重放复现的前置） */
    private val dispatcher = UnconfinedTestDispatcher()

    private val home = UserHomeIsolation("edt-wb-followup-race-test")

    private val sessionScope = CoroutineScope(dispatcher)

    private val collectorScope = CoroutineScope(dispatcher)

    private val createdVms = mutableListOf<WorkbenchViewModel>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        home.redirect()
        Database.resetForTest()
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        sessionScope.cancel()
        collectorScope.cancel()
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        home.restoreOnly()
        Database.resetForTest()
        StatusSignals.clearForTest()
    }

    /** 轮询推进断言兜底：注入后调度确定性收口，循环首拍即中，防御性保留 */
    private fun awaitTrue(probe: () -> Boolean): Boolean {
        repeat(100) {
            if (probe()) return true
            Thread.sleep(20)
        }
        return probe()
    }

    @Test
    fun `followUp never settles a merged report from stale phaseResult`() {
        val vm = WorkbenchViewModel(ioDispatcher = dispatcher).also { createdVms += it }

        // 注入会话：executor 即抛（零网络）；失败回执透传 VM 收口（onRunFailed 由 VM 提供）
        var injectedSession: WorkbenchSession? = null
        vm.sessionFactoryForTest = { runId, onRunFailed ->
            WorkbenchSession(
                scope = sessionScope,
                runId = runId,
                configProvider = { LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
                executorProvider = { error("followup-race-explode") },
                modelProvider = { error("测试不触达 model") },
                privateKeyProvider = { "k" },
                memoryDir = null,
                ledger = null,
                onRunFailed = onRunFailed,
            ).also { injectedSession = it }
        }

        // 报告文本全量留痕（判据 = 任何时刻不得出现「旧+旧」合并产物）
        val reportHistory = java.util.concurrent.CopyOnWriteArrayList<String>()
        val historyJob = collectorScope.launch {
            vm.reportText.collect { text -> if (text != null) reportHistory.add(text) }
        }

        vm.setLogFiles(listOf("/tmp/followup-race.alog"))
        vm.setSymptom("首研判症状")

        // 1. 首轮研判：注入会话即时失败 → 失败回执 → 降级报告落 _reportText
        vm.startAnalysis()
        val runId = vm.currentRunIdForUi()
        assertTrue(
            awaitTrue { vm.reportText.value?.contains("失败降级记录") == true },
            "前置：首轮失败收口（降级报告落 _reportText，失败回执链经测缝透传必达）",
        )

        // 2. 构造陈值：直置 phaseResult（生产唯一写点在成功收尾，单测经缝驱动）——
        //    首轮收集器收到非空值 → 正常收口 COMPLETED；此时 phaseResult 持上轮结果
        val session = injectedSession ?: error("工厂缝未产出会话")
        session.setPhaseResultForTest("# 日志分析报告 R1 正文")
        assertTrue(
            awaitTrue { vm.reportText.value == "# 日志分析报告 R1 正文" },
            "前置：phaseResult 陈值经首轮收集器正常收口",
        )

        // 3. 追问：挂收集器时 phaseResult 仍持上轮值——先挂后发会立即重放陈值并以
        //    mergeFollowUp 产出「旧+旧」合并报告（缺陷形态）；先发起再挂则收集器只
        //    收得到 null 起点
        vm.followUp("追问一下")

        assertTrue(
            awaitTrue { RunStore(Database.database()).runById(runId)?.status == RunStore.RunStatus.FAILED },
            "追问轮以失败终态收口（注入 executor 即时失败，库行状态 ≠ RUNNING 不变量）",
        )
        assertFalse(
            reportHistory.any { "## 增量研判（追问）" in it },
            "追问挂收集器不得重放上轮陈值（旧+旧合并覆写报告即本缺陷形态）",
        )
        assertTrue(
            vm.reportText.value?.contains("失败降级记录") == true,
            "最终报告为追问轮失败降级报告（陈值不得覆写）",
        )
        assertEquals(false, vm.running.value, "收口后运行态复位")

        historyJob.cancel()
    }
}
