package com.jiaoay.easy_dev_toolbox.page.mcp

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * McpRunRegistry 单测（P5 openspec mcp-expose 任务 3.1——design D2/D8 零 SDK）：
 * 容量上限拒绝 / 登记查询回环 / 取消传播 / 中止保全 / 清退 / runsVersion 版本面（SYS-04）。
 */
class McpRunRegistryTest {

    @BeforeTest
    fun setUp() {
        McpRunRegistry.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        McpRunRegistry.resetForTest()
    }

    @Test
    fun `register and find round trip`() {
        val handle = McpRunRegistry.register("run-1", "workbench_analyze")
        assertNotNull(handle, "登记成功返回句柄")
        assertEquals("run-1", handle.runId)
        assertEquals("workbench_analyze", handle.toolName)
        assertEquals(McpRunRegistry.Phase.RUNNING, handle.phase.value, "新 run 初始 RUNNING")
        assertEquals(handle, McpRunRegistry.find("run-1"), "按 runId 查询回环")
    }

    @Test
    fun `capacity limit rejects beyond max runs`() {
        repeat(McpRunRegistry.MAX_RUNS) { i ->
            assertNotNull(McpRunRegistry.register("run-$i", "workbench_analyze"), "第 ${i + 1} 个 run 应登记成功")
        }
        assertNull(McpRunRegistry.register("run-overflow", "workbench_analyze"), "超出容量上限 = 拒绝新会话（design D2 防资源耗尽）")
        assertEquals(McpRunRegistry.MAX_RUNS, McpRunRegistry.activeCount)
    }

    @Test
    fun `sweep finished frees capacity`() {
        repeat(McpRunRegistry.MAX_RUNS) { i -> McpRunRegistry.register("run-$i", "distill_run") }
        McpRunRegistry.find("run-0")?.complete("done")
        McpRunRegistry.sweepFinished()
        assertNotNull(McpRunRegistry.register("run-new", "distill_run"), "清退结束态 run 后容量恢复")
    }

    // ── SYS-04 · 重设计批 1：runsVersion 版本面（在途数响应式化的数据源锚） ──

    @Test
    fun `runsVersion bumps on register`() {
        val before = McpRunRegistry.runsVersion.value
        McpRunRegistry.register("run-1", "workbench_analyze")
        assertEquals(
            before + 1,
            McpRunRegistry.runsVersion.value,
            "register 后 runsVersion 自增（ShellDebugViewModel.messagesVersion 先例同构）",
        )
    }

    @Test
    fun `runsVersion stays on rejected register`() {
        repeat(McpRunRegistry.MAX_RUNS) { i -> McpRunRegistry.register("run-$i", "workbench_analyze") }
        val before = McpRunRegistry.runsVersion.value
        assertNull(McpRunRegistry.register("run-overflow", "workbench_analyze"), "容量满 = 拒绝")
        assertEquals(
            before,
            McpRunRegistry.runsVersion.value,
            "被拒登记不动版本号（在途数未变——观测面无假重组）",
        )
    }

    @Test
    fun `runsVersion bumps on sweep that removes`() {
        val handle = McpRunRegistry.register("run-1", "workbench_analyze")!!
        McpRunRegistry.sweepFinished()
        val before = McpRunRegistry.runsVersion.value
        handle.complete("done")
        McpRunRegistry.sweepFinished()
        assertEquals(
            before + 2,
            McpRunRegistry.runsVersion.value,
            "终态迁移 +1、sweep 清退 +1（两步都是观测面变化——activeCount 已改 RUNNING 口径）",
        )
    }

    @Test
    fun `terminal transition bumps runsVersion`() {
        val handle = McpRunRegistry.register("run-1", "workbench_analyze")!!
        val before = McpRunRegistry.runsVersion.value
        handle.complete("done")
        assertEquals(
            before + 1,
            McpRunRegistry.runsVersion.value,
            "终态迁移即「在途数减一」——不 bump 则页面计数把终态算在途直到 sweep",
        )
    }

    @Test
    fun `activeCount counts running phase only`() {
        McpRunRegistry.register("run-a", "workbench_analyze")
        McpRunRegistry.register("run-b", "distill_run")!!.fail("boom")
        assertEquals(
            1,
            McpRunRegistry.activeCount,
            "终态 run 不算在途（N/10 pill、确认框、端口门控三消费点口径）",
        )
    }

    @Test
    fun `full table with one terminal run accepts new register`() {
        repeat(McpRunRegistry.MAX_RUNS) { i -> McpRunRegistry.register("run-$i", "workbench_analyze") }
        McpRunRegistry.find("run-0")?.complete("done")
        // 容量判定前 sweep 前置：终态占位可回收，满表拒绝不再不可逆死锁
        assertNotNull(
            McpRunRegistry.register("run-new", "workbench_analyze"),
            "满表 + 1 终态：register 经前置 sweep 成功（sweep 生产调用点全在成功路径后的死锁破除）",
        )
    }

    @Test
    fun `runsVersion stays on sweep that removes nothing`() {
        McpRunRegistry.register("run-1", "workbench_analyze")
        val before = McpRunRegistry.runsVersion.value
        McpRunRegistry.sweepFinished()
        assertEquals(
            before,
            McpRunRegistry.runsVersion.value,
            "无清退的 sweep 不动版本号（避免观测面假重组）",
        )
    }

    @Test
    fun `runsVersion bumps on cancel`() {
        val handle = McpRunRegistry.register("run-1", "workbench_analyze")!!
        handle.cancelHook = {}
        val before = McpRunRegistry.runsVersion.value
        assertTrue(McpRunRegistry.cancel("run-1"), "在途 run 取消成功")
        assertEquals(
            before + 1,
            McpRunRegistry.runsVersion.value,
            "cancel 成功后版本自增（取消会驱走 run 进程——在途数语义面变化）",
        )
    }

    @Test
    fun `runsVersion bumps on cancelAll with running runs`() {
        val running = McpRunRegistry.register("run-running", "workbench_analyze")!!
        running.cancelHook = {}
        McpRunRegistry.register("run-done", "workbench_analyze")!!.complete("done")
        val before = McpRunRegistry.runsVersion.value
        McpRunRegistry.cancelAll()
        assertEquals(
            before + 1,
            McpRunRegistry.runsVersion.value,
            "cancelAll 命中在途 run 才自增（结束态 run 不触发）",
        )
    }

    @Test
    fun `cancel propagates through hook`() {
        var cancelled = false
        val handle = McpRunRegistry.register("run-1", "workbench_analyze")!!
        handle.cancelHook = { cancelled = true }
        handle.cancelHook = { cancelled = true } // 双设无害

        val ok = McpRunRegistry.cancel("run-1")
        assertTrue(ok, "取消在途 run 返回 true")
        assertTrue(cancelled, "取消钩被调用（会话执行体收 CE）")
    }

    @Test
    fun `cancel unknown or finished run returns false`() {
        assertFalse(McpRunRegistry.cancel("nope"), "未知 runId = false")
        val handle = McpRunRegistry.register("run-1", "workbench_analyze")!!
        handle.complete("done")
        assertFalse(McpRunRegistry.cancel("run-1"), "已完成的 run 不可取消")
    }

    @Test
    fun `cancelAll hits only running runs`() {
        val running = McpRunRegistry.register("run-running", "workbench_analyze")!!
        val done = McpRunRegistry.register("run-done", "workbench_analyze")!!
        done.complete("done")
        var hitRunning = false
        var hitDone = false
        running.cancelHook = { hitRunning = true }
        done.cancelHook = { hitDone = true }

        McpRunRegistry.cancelAll()

        assertTrue(hitRunning, "在途 run 被取消（server 停止收口）")
        assertFalse(hitDone, "结束态 run 不再触发取消钩")
    }

    @Test
    fun `cancel with partial preserves artifacts and marks aborted`() {
        val handle = McpRunRegistry.register("run-1", "workbench_analyze")!!
        handle.artifacts.value = listOf("/tmp/stage-1.txt", "/tmp/stage-2.txt")

        handle.cancelWithPartial("阶段产物已出，根因未定")

        assertEquals(McpRunRegistry.Phase.CANCELLED, handle.phase.value)
        assertTrue(handle.result.value!!.contains("已中止"), "部分结果带「已中止」标记（spec 场景）")
        assertEquals(2, handle.artifacts.value.size, "已产出阶段产物保留（中止保全）")
    }

    @Test
    fun `fail marks failed phase with reason`() {
        val handle = McpRunRegistry.register("run-1", "distill_run")!!
        handle.fail("LLM 端点未配置")
        assertEquals(McpRunRegistry.Phase.FAILED, handle.phase.value)
        assertTrue(handle.result.value!!.contains("LLM 端点未配置"))
    }

    @Test
    fun `snapshot lists registered runs`() {
        McpRunRegistry.register("run-b", "workbench_analyze")
        McpRunRegistry.register("run-a", "distill_run")
        assertEquals(listOf("run-a", "run-b"), McpRunRegistry.snapshot().map { it.runId }, "快照按 runId 排序稳定")
    }
}
