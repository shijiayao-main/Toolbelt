package com.jiaoay.easy_dev_toolbox.core.llm.tools

import com.jiaoay.easy_dev_toolbox.core.system.SysAuditAggregator
import com.jiaoay.easy_dev_toolbox.core.system.SysAuditLog
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * self.status 自观测工具守护：
 * 1. 域封闭与注册面：catalog 中 self. 前缀工具恰一件 + risk=READ_ONLY + uiHint=null +
 *    names 单源（dev.* 同款配方——装配门控在 config.selfStatusSource 非 null）；
 * 2. fail-closed 双兜底：源未装配（null 源直造）恒 DENIED；源回报未装配（snapshot=null）
 *    如实报 FAILED 不编数据；
 * 3. fake 源注入回报矩阵：四段数据面（连接/计数/配置校验/审计聚合）逐项落进回执；
 * 4. 审计聚合只计数不落原文：注入含 distinctive 原文的聚合，断言原文不出现在回执；
 *    聚合缺失（null）如实报「暂不可得」不编零；
 * 5. capAt 收口：超长回执截断且 truncated 落进结构化负载。
 */
class SelfStatusToolsTest {

    /** 测试源：固定快照或按序回报（null = 未装配形态） */
    private class FakeSource(
        private val snapshot: SelfStatusTools.Snapshot?,
    ) : SelfStatusTools.SelfStatusSource {
        var asked = 0
        override suspend fun snapshot(): SelfStatusTools.Snapshot? {
            asked++
            return snapshot
        }
    }

    private fun snapshot(
        lastError: String? = null,
        configIssues: List<SelfStatusTools.ConfigIssueLine> = emptyList(),
        audit: SysAuditAggregator.DailyAggregate? = zeroAudit(),
        counters: SelfStatusTools.CounterRows = SelfStatusTools.CounterRows(
            intakeReceived = 12, submitted = 11, completed = 9, failed = 1,
            accessRejected = 2, unmatched = 0, policyRejected = 1, duplicatesDropped = 3,
            queueTimeouts = 0, droppedTotal = 6,
        ),
        running: Boolean = true,
        sampledAtMillis: Long = 1_000L * 60 * 60 * 8 + 120_000,
    ): SelfStatusTools.Snapshot = SelfStatusTools.Snapshot(
        running = running,
        connectionState = if (running) "已连接 · TestBot" else "空闲",
        connectedSinceMillis = 1_000L * 60 * 60 * 8,
        lastSignalAtMillis = 1_000L * 60 * 60 * 8 + 60_000,
        sampledAtMillis = sampledAtMillis,
        reconnectCount = 2,
        lastError = lastError,
        lastErrorAtMillis = lastError?.let { 1_000L * 60 * 60 * 7 },
        counters = counters,
        liveProcesses = 1,
        logSize = 42,
        configIssues = configIssues,
        audit = audit,
    )

    private fun zeroAudit(): SysAuditAggregator.DailyAggregate =
        SysAuditAggregator.DailyAggregate(date = LocalDate.now(), total = 0, byTool = emptyMap(), denied = 0)

    // ── 域封闭与注册面 ──

    @Test
    fun `self 前缀工具封闭于一件且注册面纯 agent 形态`() {
        val selfNames = AgentToolCatalog.all.map { it.name }.filter { it.startsWith("self.") }.toSet()
        assertEquals(
            setOf("self.status"),
            selfNames,
            "self.* 自观测域封闭于单件（零文件 I/O 零子进程零授权面）；新条目先过域边界评审再改本锚",
        )
        assertEquals(setOf("self.status"), SelfStatusTools.STATUS_NAMES, "names 集单源（消费方选择集引用此集合）")
        val descriptor = AgentToolCatalog.all.firstOrNull { it.name == "self.status" }
            ?: error("self.status 未注册进 AgentToolCatalog")
        assertEquals(AgentToolCatalog.Risk.READ_ONLY, descriptor.risk, "只读聚合回执，必须 READ_ONLY")
        assertNull(descriptor.uiHint, "纯 agent 工具（uiHint=null——命令面板投影面零变化）")
        assertEquals(emptySet<String>(), descriptor.aliases, "无别名（撞面零容忍）")
        assertTrue(descriptor.params.isEmpty(), "零参工具（无 LLM 入参——数据面全部进程内自取）")
    }

    // ── fail-closed 双兜底 ──

    @Test
    fun `源未装配恒DENIED（fail-closed装配缺陷兜底）`() = runBlocking {
        val result = SelfStatusTools.SelfStatusTool(source = null).execute(SelfStatusTools.SelfStatusTool.Args())
        assertTrue("fail-closed" in result, "null 源直造须拒收：$result")
        assertTrue("不支持自观测" in result, "拒收文案如实说明本链无自观测面")
    }

    @Test
    fun `源回报未装配如实报FAILED不编数据`() = runBlocking {
        val result = SelfStatusTools.SelfStatusTool(source = FakeSource(null)).execute(SelfStatusTools.SelfStatusTool.Args())
        assertTrue("桥接未装配" in result, "snapshot=null 须如实报未装配：$result")
        assertTrue("飞书桥接页" in result, "拒绝文案带用户可执行的指引")
    }

    // ── fake 源回报矩阵 ──

    @Test
    fun `四段数据面逐项落进回执`() = runBlocking {
        val issues = listOf(
            SelfStatusTools.ConfigIssueLine("connection.appId", "已开始配置凭据或开启自动启动时 App ID 必填"),
            SelfStatusTools.ConfigIssueLine("workspace.agentWorkDir", "Agent 工作目录须位于工作区根目录内"),
        )
        val audit = SysAuditAggregator.DailyAggregate(
            date = LocalDate.now(), total = 5,
            byTool = mapOf("sys.probe" to 3, "sys.read_config" to 2), denied = 1,
        )
        val receipt = SelfStatusTools.SelfStatusTool(source = FakeSource(snapshot(lastError = "连接超时", configIssues = issues, audit = audit)))
            .execute(SelfStatusTools.SelfStatusTool.Args())
        // ① 连接段
        assertTrue("running=true" in receipt, "运行态在回执")
        assertTrue("连接态=已连接 · TestBot" in receipt, "连接态人话在回执")
        assertTrue("重连 2 次" in receipt, "重连计数在回执")
        assertTrue("最近异常=连接超时" in receipt, "最近异常在回执")
        assertTrue("最近异常=" in receipt && "（记于 " in receipt, "最近异常带观察时刻")
        // ② 流水线计数段（12 行同款行集）
        assertTrue("[流水线计数" in receipt, "计数段在回执")
        assertTrue("接收消息=12" in receipt, "接收计数")
        assertTrue("已提交任务=11" in receipt, "提交计数")
        assertTrue("完成=9" in receipt && "失败=1" in receipt, "终态计数")
        assertTrue("访问拒绝=2" in receipt && "策略拒绝=1" in receipt, "拒绝计数")
        assertTrue("去重丢弃=3" in receipt && "丢弃合计=6" in receipt, "丢弃计数")
        assertTrue("日志台行数=42" in receipt, "尾行计数")
        // ③ 配置校验段
        assertTrue("[配置校验" in receipt && "未过（2 处）" in receipt, "校验结论在回执")
        assertTrue("- connection.appId: 已开始配置凭据或开启自动启动时 App ID 必填" in receipt, "fieldId+message 在回执")
        // ④ 审计聚合段（只计数）
        assertTrue("[sys-audit 聚合" in receipt, "审计聚合段在回执")
        assertTrue("今日共 5 条：sys.probe=3 / sys.read_config=2；拒绝 1 次" in receipt, "聚合计数句在回执")
    }

    @Test
    fun `审计零记录与聚合缺失两态如实区分`() = runBlocking {
        val zero = SelfStatusTools.SelfStatusTool(source = FakeSource(snapshot(audit = zeroAudit())))
            .execute(SelfStatusTools.SelfStatusTool.Args())
        assertTrue("今日无审计记录（0 条）" in zero, "全零聚合=今日无记录（正常答案）：$zero")
        val missing = SelfStatusTools.SelfStatusTool(source = FakeSource(snapshot(audit = null)))
            .execute(SelfStatusTools.SelfStatusTool.Args())
        assertTrue("今日聚合暂不可得" in missing, "聚合缺失如实报不可得，不编零：$missing")
        assertTrue("禁止编造" in missing, "不可得文案带禁止编造纪律")
    }

    @Test
    fun `审计聚合原文零携带（拒外发纪律在回执面复核）`() = runBlocking {
        // 含 distinctive 原文的聚合（SysAuditAggregatorTest 已证聚合层不携带；此处复核渲染层）
        val audit = SysAuditAggregator.DailyAggregate(
            date = LocalDate.now(), total = 2, byTool = mapOf("sys.probe" to 2), denied = 0,
        )
        val receipt = SelfStatusTools.SelfStatusTool(source = FakeSource(snapshot(audit = audit)))
            .execute(SelfStatusTools.SelfStatusTool.Args())
        assertFalse("argv" in receipt, "回执不含动作原文形态")
        assertFalse("trigger" in receipt, "回执不含触发者形态")
        assertTrue("拒绝 0 次" in receipt, "聚合计数句仍在")
    }

    @Test
    fun `capAt收口超长回执且truncated落负载`() = runBlocking {
        val longError = "E".repeat(20_000)
        val result = SelfStatusTools.SelfStatusTool(source = FakeSource(snapshot(lastError = longError)))
            .executeTyped(SelfStatusTools.SelfStatusTool.Args())
        assertTrue(result is ToolResult.Ok, "超长也是成功回执（截断非失败）")
        val ok = result as ToolResult.Ok
        assertTrue(ok.summary.length <= SelfStatusTools.MAX_OUTPUT_CHARS, "回执 ≤ 8K 上界：${ok.summary.length}")
        val payload = ok.data as ToolPayload.SelfStatusPayload
        assertTrue(payload.truncated, "截断标记落进结构化负载")
        // 未超长形态 truncated=false
        val normal = SelfStatusTools.SelfStatusTool(source = FakeSource(snapshot()))
            .executeTyped(SelfStatusTools.SelfStatusTool.Args()) as ToolResult.Ok
        assertFalse((normal.data as ToolPayload.SelfStatusPayload).truncated, "正常形态不标截断")
    }

    @Test
    fun `未运行形态如实回报不报错`() = runBlocking {
        val receipt = SelfStatusTools.SelfStatusTool(source = FakeSource(snapshot(running = false, sampledAtMillis = 0)))
            .execute(SelfStatusTools.SelfStatusTool.Args())
        assertTrue("running=false" in receipt, "未运行是正常答案非错误：$receipt")
        assertTrue("未运行，无实时采样" in receipt, "无 metrics 束时不渲染 epoch 0 采样时刻")
        assertTrue("连接态=空闲" in receipt, "连接态如实回报")
    }
}
