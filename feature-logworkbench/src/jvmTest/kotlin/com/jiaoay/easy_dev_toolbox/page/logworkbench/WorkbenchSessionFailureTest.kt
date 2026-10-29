package com.jiaoay.easy_dev_toolbox.page.logworkbench

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.testfixtures.UserHomeIsolation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/**
 * WorkbenchSession 失败终态收口：研判异常经 [WorkbenchSession.onRunFailed]
 * 必达降级报告回执——只置 running=false 会让 run 行永久 RUNNING（库行状态 ≠ RUNNING
 * 是记录系统不变量）。
 *
 * 零网络：executorProvider 构造即抛，runPhase 的 catch(Exception) 分支直达。
 * 🔴 user.home 隔离：buildRegistry 读 LlmConfigStore（DataStore 落数据根）。
 */
class WorkbenchSessionFailureTest {

    private val home = UserHomeIsolation("edt-wb-session-failure-test")

    @BeforeTest
    fun setUp() {
        home.redirect()
        Database.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        Database.resetForTest()
        home.restoreOnly()
    }

    private fun newSession(
        runId: String,
        onRunFailed: (String, String) -> Unit,
    ) = WorkbenchSession(
        scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        runId = runId,
        configProvider = { com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
        // executor 构造即抛——koogToolLoop 调用前的参数求值即异常（零网络）
        executorProvider = { error("boom-explode") },
        modelProvider = { error("failure 测试不触达 model") },
        privateKeyProvider = { "k" },
        memoryDir = null,
        ledger = null,
        onRunFailed = onRunFailed,
    )

    @Test
    fun `phase exception reaches onRunFailed with degraded report`() {
        var failedRunId: String? = null
        var degraded: String? = null
        val session = newSession("run-fail-1") { id, report ->
            failedRunId = id
            degraded = report
        }

        session.startAnalysis(
            WorkbenchSession.AnalysisInput(
                logFiles = listOf("/tmp/a.alog"),
                targetTime = "2026-01-01_00:00:00.000",
                rangeMinutes = 5,
                symptom = "症状",
            ),
        )

        assertEquals("run-fail-1", failedRunId, "失败终态回执必达（回调携带 runId 防会话置换写错 run）")
        assertTrue(degraded != null, "降级报告非空")
        assertTrue("boom-explode" in degraded!!, "降级报告含错误摘要")
        assertTrue("## 关键依据" in degraded!!, "降级报告五部分骨架（ReportPanel parseReport 消费面）")
        assertEquals(false, session.running.value, "失败后 running 复位")
    }

    @Test
    fun `timeout cancellation reaches onRunFailed with degraded report`() {
        // 回执写点在超时器线程（Unconfined 下 delay 恢复续体跨线程）——原子引用保可见性
        val failedRunId = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val degraded = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val session = WorkbenchSession(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            runId = "run-timeout-1",
            configProvider = { com.jiaoay.easy_dev_toolbox.core.llm.LlmEndpointConfig(baseUrl = "", apiKey = "", modelId = "", timeoutSeconds = 60) },
            // withTimeout 实时钟即抛 TimeoutCancellationException——executor 参数求值期
            // 直达 catch(TimeoutCE) 分支（先于 koogToolLoop 调用体，零网络）
            executorProvider = { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(50) }; error("unreachable") },
            modelProvider = { error("failure 测试不触达 model") },
            privateKeyProvider = { "k" },
            memoryDir = null,
            ledger = null,
            onRunFailed = { id, report ->
                failedRunId.set(id)
                degraded.set(report)
            },
        )

        session.startAnalysis(
            WorkbenchSession.AnalysisInput(
                logFiles = listOf("/tmp/a.alog"),
                targetTime = "2026-01-01_00:00:00.000",
                rangeMinutes = 5,
                symptom = "症状",
            ),
        )

        // 实时钟 1ms 超时晚于 startAnalysis 返回——轮询等收口（DistillSessionTest 同款）
        val deadline = System.currentTimeMillis() + 5_000
        while (session.running.value && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals("run-timeout-1", failedRunId.get(), "超时分支同样必达失败回执（失败收口不因分支而异）")
        assertTrue(degraded.get() != null, "超时降级报告非空")
        assertEquals(false, session.running.value, "超时后 running 复位")
    }

    @Test
    fun `provider setup failure reaches onRunFailed too`() {
        var fired = false
        val session = WorkbenchSession(
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            runId = "run-setup-1",
            // 配置快照本身失败（read 抛非 CE 异常）——前置求值路径同样必达失败回执
            configProvider = { error("config-store-explode") },
            executorProvider = { error("failure 测试不触达 executor") },
            modelProvider = { error("failure 测试不触达 model") },
            privateKeyProvider = { "k" },
            memoryDir = null,
            ledger = null,
            onRunFailed = { _, _ -> fired = true },
        )

        session.startAnalysis(
            WorkbenchSession.AnalysisInput(
                logFiles = listOf("/tmp/a.alog"),
                targetTime = "2026-01-01_00:00:00.000",
                rangeMinutes = 5,
                symptom = "症状",
            ),
        )

        assertTrue(fired, "配置快照失败也是研判失败——run 行不得漏收口而永久 RUNNING")
        assertEquals(false, session.running.value, "前置求值失败后 running 复位")
    }

    @Test
    fun `failure callback not fired on cancel path`() {
        var fired = false
        val session = newSession("run-fail-2") { _, _ -> fired = true }

        // 未发起任何研判直接 cancel——CE 分支不触失败回执（ABORTED 由 VM 收口）
        session.cancel()

        assertEquals(false, fired, "cancel 路径不走 onRunFailed（语义不同：中止 ≠ 失败）")
        assertEquals(false, session.running.value)
    }
}
