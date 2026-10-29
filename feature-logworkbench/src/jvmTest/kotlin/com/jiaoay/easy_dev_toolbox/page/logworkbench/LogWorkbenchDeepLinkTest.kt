package com.jiaoay.easy_dev_toolbox.page.logworkbench

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 深链槽单测（P4-C2 openspec log-workbench 任务 6.1/6.3——[LogWorkbenchDeepLink] 纯 object 直测）。
 *
 * offer→consume 恒等 + 一次性语义（consume 后清空）——不触 UI 框架（VM prefill
 * 面经 [WorkbenchViewModel.prefill] 纯数据回填，表单预填链路在 7.2 端到端测覆盖）。
 *
 * DOC-01（重设计批 1 · 2026-09-30）：报告卡 runId 回看深链（offerRunReview）——
 * runId 进出恒等 + null 语义分离 + 一次性/覆盖语义与预填深链同款。
 */
class LogWorkbenchDeepLinkTest {

    @Test
    fun `offer 后 consume 恒等返回`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(
            logFiles = listOf("/tmp/a.alog.txt"),
            prefillNote = "XLog 解密产物深度分析",
        )
        val consumed = LogWorkbenchDeepLink.consume()
        assertEquals(
            LogWorkbenchDeepLink.PendingInput(
                logFiles = listOf("/tmp/a.alog.txt"),
                prefillNote = "XLog 解密产物深度分析",
            ),
            consumed,
        )
    }

    @Test
    fun `consume 后槽清空（一次性语义）`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(listOf("/tmp/b.txt"))
        LogWorkbenchDeepLink.consume()
        // 二次 consume 恒 null——重复进入页面不重复预填
        assertNull(LogWorkbenchDeepLink.consume())
        assertNull(LogWorkbenchDeepLink.pending.value)
    }

    @Test
    fun `无投递时 consume 返回 null`() {
        LogWorkbenchDeepLink.clearForTest()
        assertNull(LogWorkbenchDeepLink.consume())
    }

    @Test
    fun `覆盖语义——后一次 offer 覆盖前一次`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(listOf("/tmp/first.txt"))
        LogWorkbenchDeepLink.offer(listOf("/tmp/second.txt"))
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertEquals(listOf("/tmp/second.txt"), consumed.logFiles)
        assertTrue(consumed.prefillNote == null)
    }

    // ── 评审修复批 2026-09-23：S4 深链泛化的 spaceId 字段补测（S4/S5 遗留欠账）──

    @Test
    fun `offer 带 spaceId 时 consume 恒等携带空间归属`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(
            logFiles = listOf("/tmp/a.alog.txt", "/tmp/b.alog.txt"),
            prefillNote = "XLog 解密产物深度分析",
            spaceId = "space-123",
        )
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertEquals(
            LogWorkbenchDeepLink.PendingInput(
                logFiles = listOf("/tmp/a.alog.txt", "/tmp/b.alog.txt"),
                prefillNote = "XLog 解密产物深度分析",
                spaceId = "space-123",
            ),
            consumed,
            "spaceId 深链泛化字段必须经 offer→consume 恒等传递（消费方据此走 attachFilesToSpace）",
        )
    }

    @Test
    fun `offer 默认不带 spaceId——null 语义为不写空间成员`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(listOf("/tmp/plain.txt"))
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertEquals(
            null,
            consumed.spaceId,
            "spaceId 缺省 = null——消费方 attachFilesToSpace 分支不触发（不写空间成员）",
        )
    }

    @Test
    fun `spaceId 覆盖语义——后一次 offer 的 spaceId 覆盖前一次`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(listOf("/tmp/first.txt"), spaceId = "space-a")
        LogWorkbenchDeepLink.offer(listOf("/tmp/second.txt"), spaceId = "space-b")
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertEquals(
            "space-b",
            consumed.spaceId,
            "覆盖语义对 spaceId 同样成立——当前空间切换后重投递不残留旧空间",
        )
    }

    @Test
    fun `spaceId 消费链——consume 清空后 attachFilesToSpace 不重复触发`() {
        // 端到端时序锚：消费方（LogWorkbenchPage LaunchedEffect）只 consume 一次——
        // 二次进入页面 consume 返 null，spaceId 分支零重复执行（一次性语义防重复写成员）
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(listOf("/tmp/a.txt"), spaceId = "space-x")
        val first = LogWorkbenchDeepLink.consume()
        assertEquals("space-x", first?.spaceId)
        assertNull(LogWorkbenchDeepLink.consume(), "一次性语义：二次 consume 恒 null——spaceId 消费不重复")
    }

    @Test
    fun `offer 带 targetTime 时 consume 恒等携带问题时间点`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(
            logFiles = listOf("/tmp/logcat.txt"),
            prefillNote = "logcat 抓取产物深度分析",
            targetTime = "2026-10-11 09:00:05.000",
        )
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertEquals(
            "2026-10-11 09:00:05.000",
            consumed.targetTime,
            "targetTime 深链字段必须经 offer→consume 恒等传递（消费方据此归一写入目标时间框）",
        )
    }

    @Test
    fun `offer 默认不带 targetTime——null 语义为不触碰时间域`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(listOf("/tmp/plain.txt"))
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertNull(
            consumed.targetTime,
            "targetTime 缺省 = null——消费方 prefill 时间点分支不触发（未标记送 null 的链路面）",
        )
    }

    // ── DOC-01（重设计批 1 · 2026-09-30）：报告卡 runId 回看深链 ──

    @Test
    fun `offerRunReview 后 consume 恒等携带 runId`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offerRunReview(runId = "run-report-1")
        val consumed = LogWorkbenchDeepLink.consume()
        assertEquals(
            LogWorkbenchDeepLink.PendingInput(
                logFiles = emptyList(),
                prefillNote = null,
                spaceId = null,
                runId = "run-report-1",
            ),
            consumed,
            "报告卡深链 round-trip：runId 须经 offerRunReview→consume 恒等传递（消费方据此走 openRun）",
        )
    }

    @Test
    fun `offerRunReview 可带 logFiles 兜底载荷`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offerRunReview(
            runId = "run-report-2",
            logFiles = listOf("/tmp/fallback.alog.txt"),
        )
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertEquals("run-report-2", consumed.runId)
        assertEquals(
            listOf("/tmp/fallback.alog.txt"),
            consumed.logFiles,
            "logFiles 随 runId 同投——openRun 落空（run 已删）时的表单预填兜底",
        )
        assertNull(consumed.spaceId, "回看深链不写空间成员（spaceId 语义与预填深链分离）")
    }

    @Test
    fun `offer 旧路径默认不带 runId——null 语义为预填不回看`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(
            logFiles = listOf("/tmp/plain.alog.txt"),
            prefillNote = "XLog 解密产物深度分析",
        )
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertEquals(
            null,
            consumed.runId,
            "工具页「深度分析」旧路径缺省 runId = null——消费方 prefill 分支不触发 openRun（现状零回归）",
        )
    }

    @Test
    fun `runId 深链一次性语义——consume 后不重复回看`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offerRunReview(runId = "run-once")
        assertEquals("run-once", LogWorkbenchDeepLink.consume()?.runId)
        assertNull(
            LogWorkbenchDeepLink.consume(),
            "回看深链同为一次性语义——二次进入工作台不重复 restore 同一 run",
        )
    }

    @Test
    fun `回看深链与预填深链互斥覆盖——后投递者胜`() {
        LogWorkbenchDeepLink.clearForTest()
        LogWorkbenchDeepLink.offer(listOf("/tmp/prefill.txt"))
        LogWorkbenchDeepLink.offerRunReview(runId = "run-later")
        val consumed = LogWorkbenchDeepLink.consume()!!
        assertEquals(
            "run-later",
            consumed.runId,
            "单槽覆盖语义：后一次投递覆盖前一次（与 spaceId/文件深链同款——当前意图胜）",
        )
    }

    // ── 在页响应缝（双 effect 消费形态的槽层语义——ToolsPage 预选槽批 4 复核 P1-1 同款）──
    // 页面保持组合期间（组合期初值消费已跑过、槽已清）新载荷到达 → 订阅面
    // （pending.filterNotNull().collect 同款订阅）必须即时收到；collect 收到后
    // 仍经 consume 读即清——离页返页时初值消费不重读旧载荷。

    @Test
    fun `页在组合中新载荷到达——订阅面即时收到（在页即时消费）`() = runBlocking<Unit> {
        LogWorkbenchDeepLink.clearForTest()
        // 前置：组合期初值消费已跑过（LaunchedEffect(Unit) 只跑一次）——槽取走即清
        LogWorkbenchDeepLink.offer(listOf("/tmp/initial.alog.txt"), prefillNote = "初值载荷")
        assertEquals(listOf("/tmp/initial.alog.txt"), LogWorkbenchDeepLink.consume()?.logFiles)
        assertNull(LogWorkbenchDeepLink.pending.value, "初值消费后槽已清——订阅面起始无旧载荷可重读")

        // 在页订阅面（LogWorkbenchPage 订阅 effect 同款订阅形态）：页面保持组合
        // 期间新 offer 到达 → filterNotNull 即时发射
        val received = CompletableDeferred<LogWorkbenchDeepLink.PendingInput>()
        val collector = launch {
            LogWorkbenchDeepLink.pending.filterNotNull().collect { received.complete(it) }
        }
        LogWorkbenchDeepLink.offer(listOf("/tmp/inpage.alog.txt"), prefillNote = "在页新载荷")
        val input = withTimeout(5_000) { received.await() }
        assertEquals(
            listOf("/tmp/inpage.alog.txt"),
            input.logFiles,
            "在页新载荷必须被订阅面即时收到（组合期一次性消费不重跑，不得静默失效滞留槽内）",
        )
        assertEquals(
            "在页新载荷",
            input.prefillNote,
            "在页订阅面载荷恒等透传（预填症状通道不变）",
        )
        // 读即清配合：collect 用收到的载荷，consume 只负责清槽
        LogWorkbenchDeepLink.consume()
        assertNull(LogWorkbenchDeepLink.pending.value, "订阅面消费后槽清空——离页返页初值消费不重读旧载荷")
        collector.cancel()
    }

    @Test
    fun `在页响应对 runId 回看深链零回归——订阅面恒等携带 runId 且一次性语义不变`() =
        runBlocking<Unit> {
            LogWorkbenchDeepLink.clearForTest()
            // 前置：页在组合中——组合期初值消费已跑过并清槽
            LogWorkbenchDeepLink.offerRunReview(runId = "run-initial")
            assertNotNull(LogWorkbenchDeepLink.consume(), "前置：初值消费取走首载荷")

            val received = CompletableDeferred<LogWorkbenchDeepLink.PendingInput>()
            val collector = launch {
                LogWorkbenchDeepLink.pending.filterNotNull().collect { received.complete(it) }
            }
            LogWorkbenchDeepLink.offerRunReview(runId = "run-inpage")
            val input = withTimeout(5_000) { received.await() }
            assertEquals(
                "run-inpage",
                input.runId,
                "在页订阅面不得改写/丢失 runId 载荷——消费方回看分支据 runId 走 openRun，订阅路径与初值路径必须同语义",
            )
            assertNull(
                input.spaceId,
                "回看深链不写空间成员（订阅面与初值消费同语义——spaceId 分支不因新路径触发）",
            )
            // 一次性语义零回归：订阅面消费（读即清）后二次取走恒 null
            LogWorkbenchDeepLink.consume()
            assertNull(
                LogWorkbenchDeepLink.consume(),
                "订阅面消费后一次性语义不变——二次取走恒 null，离页返页不重复 restore 同一 run",
            )
            collector.cancel()
        }
}
