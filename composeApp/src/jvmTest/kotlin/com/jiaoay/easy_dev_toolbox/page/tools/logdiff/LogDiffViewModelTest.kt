package com.jiaoay.easy_dev_toolbox.page.tools.logdiff

import com.jiaoay.easy_dev_toolbox.core.llm.workbench.DeepAnalyzeHook
import com.jiaoay.easy_dev_toolbox.core.runtime.LogViewerRequest
import com.jiaoay.easy_dev_toolbox.core.runtime.ReportDelivery
import com.jiaoay.easy_dev_toolbox.core.runtime.StatusSignals
import com.jiaoay.easy_dev_toolbox.page.tools.logdiff.LogDiffViewModel.DiffUiState
import com.jiaoay.easy_dev_toolbox.page.tools.logdiff.LogDiffViewModel.DiffMode
import com.jiaoay.easy_dev_toolbox.page.tools.logdiff.LogDiffViewModel.CompareUiState
import com.jiaoay.easy_dev_toolbox.page.tools.logdiff.LogDiffViewModel.Slot
import com.jiaoay.log.LogDiff
import com.jiaoay.log.LogStatsAnalyzer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 日志对比页 VM 行为断言（双拖入缺一拒收 + 引擎消费数据流 + 导出回执）：
 *
 * - 缺一拒收：两槽任一为空时 startDiff 不发起对比，inputError 文案指明缺失槽位
 *  （拒收明示而非静默禁用按钮——缺哪个槽必须让用户知道）；
 * - 双文件接收：单份拖入只覆写本槽；一次拖入两份按落入槽位次序填满两槽，第三份起忽略；
 * - 引擎数据流：hunks/LineKind 直接来自 [com.jiaoay.log.LogDiff] 结构化结果；
 *   归一化开关在发起点采样进引擎（同内容不同时间戳的两份日志归一化后 diff 为空）；
 *   超对齐上限时 degraded 置位（UI 明示横幅的数据源）；
 * - 导出：writeUnified 产物落对比文件旁，回执携带产物绝对路径；
 * - 送飞书：导出与外发共用写盘分流（发送前恒重新导出即恒最新 diff），经
 *   ReportDelivery 中立槽送出；null = 已送出成功语义不得被吞成失败回执；
 *   仅 Done 态可发起，通道失败人话原样透传为回执；
 * - hunk 巡检：结果在台时「上一处/下一处」推进/回退巡检指针（未定位态首推落 0、
 *   首/末 hunk 边界钳制、重新发起对比指针复位）；全等（hunks 空表）与 degraded
 *   （整体替换无逐行对齐）导航不可用；
 * - 两向模式：分布对照走两次 analyze → 对照纯函数执行路径（缺一拒收门禁两模式
 *   共用）；diffState 与 compareState 分立存储——模式切换/对侧发起不丢在台结果；
 *   统计失败折叠进对照态 Failed 不触行级面。
 *
 * 纪律（LogTagFilterViewModelTest 同款）：断言全程同步——viewModelScope 在
 * StandardTestDispatcher 下恒 pending；VM 级 IO 续体经注入的受控调度器落回
 * 同一 TestScheduler 队列，轮询配方保留（advanceUntilIdle 排空 + 真实落定兜底）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogDiffViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    // 构造登记：tearDown 统一 cancel（在途 IO 协程不跨用例回 Main 撞 setMain 自检）
    private val createdVms = mutableListOf<LogDiffViewModel>()

    // 受控 IO 调度器注入（与 Main 共享 TestScheduler）：VM 级 IO 续体落回本调度器队列，
    // advanceUntilIdle 即确定性排空——tearDown 无需竞态宽限 sleep
    private fun vm(): LogDiffViewModel = LogDiffViewModel(ioDispatcher = dispatcher).also { createdVms += it }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        LogViewerRequest.clearForTest()
        // ReportDelivery / DeepAnalyzeHook 是全局单例槽——本类用例 wired fake 残留
        // 会跨用例泄漏到其他 VM 测试（clearForTest 前置清场 + 后置复位）
        ReportDelivery.clearForTest()
        DeepAnalyzeHook.clearForTest()
        StatusSignals.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        ReportDelivery.clearForTest()
        DeepAnalyzeHook.clearForTest()
        StatusSignals.clearForTest()
    }

    /** 轮询等待对比在真实 IO 上完成（advanceUntilIdle 不等真实文件读取） */
    private fun awaitDiffSettles(viewModel: LogDiffViewModel) {
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.diffState.value
            if (state !is DiffUiState.Running && state !is DiffUiState.Idle) return
            Thread.sleep(20)
        }
    }

    private fun awaitExportReceipt(viewModel: LogDiffViewModel) {
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            if (viewModel.exportReceipt.value != null) return
            Thread.sleep(20)
        }
    }

    private fun fixtureFile(name: String, lines: List<String>): File =
        Files.createTempDirectory("edt-logdiff").toFile()
            .let {
                File(it, name).apply {
                    writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
                }
            }

    // ── 缺一拒收（提交前校验）────────────────────────────────

    @Test
    fun `startDiff rejects with slot-naming message while a slot is empty`() {
        val viewModel = vm()
        viewModel.startDiff()

        var error = assertNotNull(viewModel.inputError.value, "两槽全空发起对比应置 inputError")
        assertTrue("基准文件与对比文件均未选择" in error, "文案须指明两槽均缺：$error")
        assertTrue(
            viewModel.diffState.value is DiffUiState.Idle,
            "拒收后不得发起对比（状态机停留 Idle）",
        )

        val old = fixtureFile("base.log", listOf("line"))
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.startDiff()
        error = assertNotNull(viewModel.inputError.value, "缺对比文件应置 inputError")
        assertTrue("对比文件尚未选择" in error, "文案须指明缺对比槽：$error")
        assertTrue(viewModel.diffState.value is DiffUiState.Idle)

        // 缺基准槽（另一形态）：仅选对比文件
        val viewModel2 = vm()
        val new = fixtureFile("next.log", listOf("line"))
        viewModel2.onFilesSelected(Slot.NEW, listOf(new))
        viewModel2.startDiff()
        val error2 = assertNotNull(viewModel2.inputError.value, "缺基准文件应置 inputError")
        assertTrue("基准文件尚未选择" in error2, "文案须指明缺基准槽：$error2")

        // 可手动清除；再发起时按当前槽位态重新判定
        viewModel.dismissInputError()
        assertNull(viewModel.inputError.value, "清除该提示后 inputError 应回空")
        viewModel.startDiff()
        assertTrue(
            "对比文件尚未选择" in viewModel.inputError.value.orEmpty(),
            "再发起时缺槽文案须重新置位",
        )
    }

    // ── 双文件接收语义（单份/双份/多份）────────────────────────

    @Test
    fun `single drop fills one slot and two-file drop fills both slots`() {
        val viewModel = vm()
        val a = fixtureFile("a.log", listOf("a"))
        val b = fixtureFile("b.log", listOf("b"))
        val c = fixtureFile("c.log", listOf("c"))
        val d = fixtureFile("d.log", listOf("d"))

        // 单份拖入：只覆写本槽
        viewModel.onFilesSelected(Slot.OLD, listOf(a))
        assertEquals(a, viewModel.oldFile.value, "单份拖入落本槽")
        assertNull(viewModel.newFile.value, "单份拖入不得动对侧槽")

        // 双份拖入：首份落本槽、次份落对侧槽
        viewModel.onFilesSelected(Slot.NEW, listOf(b, c))
        assertEquals(b, viewModel.newFile.value, "双份拖入首份落本槽")
        assertEquals(c, viewModel.oldFile.value, "双份拖入次份落对侧槽")

        // 单份再拖入：只覆写本槽、对侧保持
        viewModel.onFilesSelected(Slot.NEW, listOf(d))
        assertEquals(d, viewModel.newFile.value)
        assertEquals(c, viewModel.oldFile.value, "换单份不得动对侧槽")

        // 三份拖入：前两份生效、第三份忽略
        val e = fixtureFile("e.log", listOf("e"))
        val f = fixtureFile("f.log", listOf("f"))
        val g = fixtureFile("g.log", listOf("g"))
        viewModel.onFilesSelected(Slot.OLD, listOf(e, f, g))
        assertEquals(e, viewModel.oldFile.value, "多份拖入取首份落本槽")
        assertEquals(f, viewModel.newFile.value, "多份拖入取次份落对侧槽，第三份起忽略")
    }

    // ── 引擎数据流（hunks / LineKind / degraded / 归一化）────────

    @Test
    fun `diff result carries hunk line kinds straight from engine`() {
        val old = fixtureFile("base.log", listOf("alpha", "beta", "gamma"))
        val new = fixtureFile("next.log", listOf("alpha", "beta2", "gamma"))
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)

        val done = viewModel.diffState.value as? DiffUiState.Done
        assertNotNull(done, "两份文件齐备时对比应跑完：${viewModel.diffState.value}")
        assertFalse(done.result.degraded, "小差异不得退化")
        assertEquals(3, done.result.oldLineCount)
        assertEquals(3, done.result.newLineCount)
        assertEquals(1, done.result.hunks.size, "单点差异应聚成 1 个 hunk")
        val kinds = done.result.hunks.single().lines.map { it.kind }
        assertTrue(LogDiff.LineKind.REMOVED in kinds, "hunk 须含删行")
        assertTrue(LogDiff.LineKind.ADDED in kinds, "hunk 须含增行")
        assertEquals("base.log", done.oldLabel, "导出头与摘要回显基准文件名")
        assertEquals("next.log", done.newLabel)
    }

    @Test
    fun `identical files produce empty hunk list`() {
        val old = fixtureFile("base.log", listOf("same", "lines"))
        val new = fixtureFile("next.log", listOf("same", "lines"))
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)

        val done = viewModel.diffState.value as? DiffUiState.Done
        assertNotNull(done)
        assertTrue(done.result.hunks.isEmpty(), "全等两份文件 hunks 应为空表")
    }

    @Test
    fun `normalize toggle makes timestamp-only differences compare equal`() {
        val old = fixtureFile(
            "base.log",
            listOf("2026-01-01 12:00:01.001 E/plain: state-A", "tail-line"),
        )
        val new = fixtureFile(
            "next.log",
            listOf("2026-02-03 13:05:06.789 E/plain: state-A", "tail-line"),
        )
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))

        // 关归一化：仅时间戳不同的行是差异行
        viewModel.startDiff()
        awaitDiffSettles(viewModel)
        val plainDone = viewModel.diffState.value as? DiffUiState.Done
        assertNotNull(plainDone)
        assertTrue(plainDone.result.hunks.isNotEmpty(), "未归一化时时间戳差异应可见")

        // 开归一化：仅时间戳不同的行视为同一行 → diff 为空
        viewModel.setNormalize(true)
        viewModel.startDiff()
        awaitDiffSettles(viewModel)
        val normalizedDone = viewModel.diffState.value as? DiffUiState.Done
        assertNotNull(normalizedDone)
        assertTrue(
            normalizedDone.result.hunks.isEmpty(),
            "归一化开关须在发起点采样进引擎（同内容不同时间戳 → 全等）",
        )
    }

    @Test
    fun `degraded flag surfaces when edit distance exceeds alignment limit`() {
        // 4200 行全删 > 对齐上限（LogDiff.MAX_ALIGN_EDITS）→ 整体替换退化
        val old = fixtureFile("base.log", (1..4200).map { "old-$it" })
        val new = fixtureFile("next.log", emptyList())
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)

        val done = viewModel.diffState.value as? DiffUiState.Done
        assertNotNull(done)
        assertTrue(done.result.degraded, "超对齐上限必须置 degraded（UI 明示横幅数据源）")
        assertTrue(done.result.hunks.isNotEmpty(), "退化仍有整体替换 hunk 可渲染")
    }

    // ── 导出（writeUnified 消费 + 回执）────────────────────────

    @Test
    fun `export writes unified diff beside the new file and reports the path`() {
        val old = fixtureFile("base.log", listOf("alpha", "beta"))
        val new = fixtureFile("next.log", listOf("alpha", "beta2"))
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)
        assertTrue(viewModel.diffState.value is DiffUiState.Done, "导出前置条件：对比已完成")

        viewModel.exportDiff()
        awaitExportReceipt(viewModel)

        val receipt = assertNotNull(viewModel.exportReceipt.value, "导出完成应回执产物路径")
        assertTrue("已导出：" in receipt, "回执须携带产物绝对路径：$receipt")
        val out = File(new.parentFile, "${new.name}.diff")
        assertTrue(out.isFile, "产物须落对比文件旁：${out.absolutePath}")
        val text = out.readText()
        assertTrue(text.startsWith("--- base.log"), "unified 头须以基准文件名开头：${text.lineSequence().first()}")
        assertTrue("+++ next.log" in text, "unified 头须含对比文件名")
        assertTrue("@@" in text, "产物须含 hunk 头")
        assertTrue("-beta" in text, "产物须含删行（unified 前缀）")
        assertTrue("+beta2" in text, "产物须含增行（unified 前缀）")
    }

    // ── 送飞书（sendDiffToLark：ReportDelivery 第三消费面）────────

    /** Done 前置 VM（拖入差异 fixture → startDiff → 轮询至 Done）与对比侧文件 */
    private fun doneDiffVm(): Pair<LogDiffViewModel, File> {
        val old = fixtureFile("base.log", listOf("alpha", "beta"))
        val new = fixtureFile("next.log", listOf("alpha", "beta2"))
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)
        assertTrue(viewModel.diffState.value is DiffUiState.Done, "送出测试前置：对比已完成")
        return viewModel to new
    }

    /** 轮询等待回执 toast 出现（导出/送出协程在真实 IO 上飞，同 awaitDiffSettles 口径） */
    private fun awaitToast(predicate: (String) -> Boolean): String {
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            StatusSignals.toast.value.lastOrNull()?.text?.let { if (predicate(it)) return it }
            Thread.sleep(20)
        }
        error("回执 toast 未在轮询预算内出现")
    }

    @Test
    fun `sendDiffToLark is a no-op before a diff completes`() {
        StatusSignals.clearForTest()
        val viewModel = vm()
        var delivered: File? = null
        ReportDelivery.wire { f -> delivered = f; null }

        viewModel.sendDiffToLark()
        repeat(10) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }

        assertNull(delivered, "非 Done 态送出不得发起——不导出不触通道（仅 Done 态可发起）")
        assertTrue(StatusSignals.toast.value.isEmpty(), "no-op 守卫路径不得产生回执 toast")
    }

    @Test
    fun `sendDiffToLark without wired channel fails with human-readable receipt`() {
        val (viewModel, _) = doneDiffVm()
        StatusSignals.clearForTest()
        assertFalse(ReportDelivery.ready.value, "前置：通道未接线（ready=false）")

        viewModel.sendDiffToLark()
        val receipt = awaitToast { "不可用" in it }

        assertTrue(
            "报告外发通道不可用" in receipt,
            "ready=false 时送出须回执人话失败而非静默/异常（通道归一），实际：$receipt",
        )
    }

    @Test
    fun `sendDiffToLark delivers freshly exported product to wired channel`() {
        val (viewModel, new) = doneDiffVm()
        StatusSignals.clearForTest()
        var delivered: File? = null
        ReportDelivery.wire { f -> delivered = f; null }
        ReportDelivery.publishReady(true)

        viewModel.sendDiffToLark()
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            if (delivered != null) return@repeat
            Thread.sleep(20)
        }
        val file = assertNotNull(delivered, "就绪通道必须收到 diff 产物 File")

        assertTrue(file.isFile, "送达产物必须真实在盘（发送前恒重新导出——恒最新 diff）")
        assertEquals(
            File(new.parentFile, "${new.name}.diff").absolutePath,
            file.absolutePath,
            "产物落点 = 对比文件旁（与「导出 diff 文件」产物同源同路径）",
        )
        assertTrue("@@" in file.readText(), "产物内容 = unified diff 本体（非空壳）")
        assertEquals(
            "已导出：${file.absolutePath}",
            viewModel.exportReceipt.value,
            "产物行回显与送达文件同源同路径（发送前恒重新导出）",
        )
        val receipt = awaitToast { "已送出至飞书会话" in it }
        assertTrue(file.name in receipt, "成功回执须指名送达文件（回执可核对）：$receipt")
        // 反向断言：Channel.deliver 返回 null = 已送出成功——成功不得被吞成失败回执
        assertTrue(
            "不可用" !in receipt && "失败" !in receipt,
            "通道成功（null）不得产生失败形态回执（elvis 会吞成功），实际：$receipt",
        )
    }

    @Test
    fun `sendDiffToLark surfaces channel failure reason verbatim`() {
        val (viewModel, _) = doneDiffVm()
        StatusSignals.clearForTest()
        ReportDelivery.wire { "通道实现的失败人话" }
        ReportDelivery.publishReady(true)

        viewModel.sendDiffToLark()
        val receipt = awaitToast { "通道实现的失败人话" in it }

        assertEquals(
            "通道实现的失败人话",
            receipt,
            "通道失败人话必须原样透传为回执（归一在通道实现方，消费面不二次加工）",
        )
    }

    // ── 送工作台分析（sendDiffToWorkbench：DeepAnalyzeHook 中立槽直投）────────

    @Test
    fun `sendDiffToWorkbench is a no-op before a diff completes`() {
        StatusSignals.clearForTest()
        val viewModel = vm()
        var offered: List<String>? = null
        DeepAnalyzeHook.wire { files, _, _ -> offered = files }

        viewModel.sendDiffToWorkbench()
        repeat(10) { dispatcher.scheduler.advanceUntilIdle(); Thread.sleep(20) }

        assertNull(offered, "非 Done 态送出不得发起——不导出不触深链槽（仅 Done 态可发起）")
        assertTrue(StatusSignals.toast.value.isEmpty(), "no-op 守卫路径不得产生回执 toast")
    }

    @Test
    fun `sendDiffToWorkbench offers freshly exported product via deep analyze hook`() {
        val (viewModel, new) = doneDiffVm()
        var offered: List<String>? = null
        var note: String? = null
        DeepAnalyzeHook.wire { files, prefillNote, _ -> offered = files; note = prefillNote }

        viewModel.sendDiffToWorkbench()
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            if (offered != null) return@repeat
            Thread.sleep(20)
        }

        val files = assertNotNull(offered, "深度分析槽必须收到 diff 产物路径列表")
        assertEquals(1, files.size, "单发对比产物 = 单文件载荷")
        val expected = File(new.parentFile, "${new.name}.diff").absolutePath
        assertEquals(expected, files.single(), "载荷路径 = 对比文件旁导出产物（与「导出 diff 文件」同源同路径）")
        assertTrue(File(expected).isFile, "投递前产物必须真实在盘（发送前恒重新导出）")
        assertEquals("日志对比产物深度分析", note, "预填症状注记 = 对比产物语境（Tag 过滤页直投同构）")
        assertEquals(
            "已导出：$expected",
            viewModel.exportReceipt.value,
            "产物行回显与投递路径同源（发送前恒重新导出）",
        )
    }

    @Test
    fun `sendDiffToWorkbench without wired hook fails with human-readable receipt`() {
        val (viewModel, _) = doneDiffVm()
        StatusSignals.clearForTest()
        DeepAnalyzeHook.clearForTest()

        viewModel.sendDiffToWorkbench()
        val receipt = awaitToast { "深度分析通道不可用" in it }

        assertTrue(
            "宿主未接线" in receipt,
            "槽未接线须回执人话失败而非静默（禁静默纪律），实际：$receipt",
        )
    }

    // ── 差异行 → 查看器跳转（行号语义 + 发起时文件捕获）────────

    @Test
    fun `open diff line offers the side file to viewer slot with given line`() {
        val old = fixtureFile("base.log", listOf("alpha", "beta", "gamma"))
        val new = fixtureFile("next.log", listOf("alpha", "beta2", "gamma"))
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)
        assertTrue(viewModel.diffState.value is DiffUiState.Done, "跳转测试前置：对比已完成")

        viewModel.openDiffLineInViewer(Slot.OLD, 2)
        val oldRequest = assertNotNull(
            LogViewerRequest.pending.value,
            "基准侧行点击须写查看器槽",
        )
        assertEquals(old.absolutePath, oldRequest.path, "基准侧跳转打开基准文件")
        assertEquals(2, oldRequest.initialLine, "1 基原文件行号须原样进查看器")

        viewModel.openDiffLineInViewer(Slot.NEW, 3)
        val newRequest = assertNotNull(
            LogViewerRequest.pending.value,
            "对比侧行点击须写查看器槽",
        )
        assertEquals(new.absolutePath, newRequest.path, "对比侧跳转打开对比文件")
        assertEquals(3, newRequest.initialLine)
    }

    @Test
    fun `open diff line jumps into diff-time files even after slots are refilled`() {
        val old = fixtureFile("base.log", listOf("alpha", "beta"))
        val new = fixtureFile("next.log", listOf("alpha", "beta2"))
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)
        assertTrue(viewModel.diffState.value is DiffUiState.Done)

        // 结果在台时槽位换文件：旧结果行的跳转目标不得跟随槽位漂移（静默跳错行）
        val replacement = fixtureFile("replacement.log", listOf("totally", "different"))
        viewModel.onFilesSelected(Slot.OLD, listOf(replacement))
        viewModel.openDiffLineInViewer(Slot.OLD, 1)
        val request = assertNotNull(LogViewerRequest.pending.value)
        assertEquals(
            old.absolutePath,
            request.path,
            "跳转目标须是发起对比时捕获的文件，而非当前槽位文件",
        )
        assertEquals(1, request.initialLine)
    }

    @Test
    fun `open diff line is a no-op before a diff completes`() {
        val viewModel = vm()
        viewModel.openDiffLineInViewer(Slot.OLD, 1)
        assertEquals(
            null,
            LogViewerRequest.pending.value,
            "无结果在台时不得写查看器槽（无行可跳）",
        )
    }

    // ── hunk 巡检（推进/回退/边界钳制/复位）────────────────────

    /**
     * 三处差异以 6 行公共游程隔开（引擎相邻编辑公共游程 ≥ 2×CONTEXT_LINES 时切开）
     * → 恰成 3 个独立 hunk，可断言推进/回退全链与首/末边界钳制。
     */
    private fun tripleChangeFixture(): Pair<File, File> {
        val old = listOf("alpha-v1") + (1..6).map { "keep-$it" } +
            listOf("beta-v1") + (7..12).map { "keep-$it" } + listOf("gamma-v1")
        val new = listOf("alpha-v2") + (1..6).map { "keep-$it" } +
            listOf("beta-v2") + (7..12).map { "keep-$it" } + listOf("gamma-v2")
        return fixtureFile("base.log", old) to fixtureFile("next.log", new)
    }

    @Test
    fun `hunk navigation advances and retreats with boundary clamping and resets on restart`() {
        val (old, new) = tripleChangeFixture()
        val viewModel = vm()
        assertNull(viewModel.hunkNav.value, "无结果在台时巡检指针须不可用（null）")

        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)

        val nav = assertNotNull(viewModel.hunkNav.value, "多 hunk 结果在台时巡检指针须可用")
        assertEquals(3, nav.count, "三处远隔差异应切成 3 个 hunk")
        assertEquals(-1, nav.index, "结果落定时指针须为未定位态（不自动跳走用户视线）")
        assertFalse(nav.canGoPrev, "未定位态无前处可回（「上一处」置灰）")
        assertTrue(nav.canGoNext, "未定位态可推进（「下一处」可用）")
        assertNull(nav.positionLabel, "未定位态无位置可回显")

        // 未定位态回退是 no-op
        viewModel.goToPrevHunk()
        assertEquals(-1, viewModel.hunkNav.value?.index, "未定位态回退不得动指针")

        // 首次推进落 0：首 hunk 边界回退置灰、推进可用、位置回显落位
        viewModel.goToNextHunk()
        var current = assertNotNull(viewModel.hunkNav.value)
        assertEquals(0, current.index)
        assertFalse(current.canGoPrev, "首 hunk 处「上一处」须置灰")
        assertTrue(current.canGoNext)
        assertEquals("第 1 / 3 处", current.positionLabel)

        // 首 hunk 边界回退钳制（no-op）
        viewModel.goToPrevHunk()
        assertEquals(0, viewModel.hunkNav.value?.index, "首 hunk 处回退须钳制在原位")

        // 推进到末 hunk：推进置灰、位置回显为末位
        viewModel.goToNextHunk()
        viewModel.goToNextHunk()
        current = assertNotNull(viewModel.hunkNav.value)
        assertEquals(2, current.index)
        assertFalse(current.canGoNext, "末 hunk 处「下一处」须置灰")
        assertTrue(current.canGoPrev)
        assertEquals("第 3 / 3 处", current.positionLabel)

        // 末 hunk 边界推进钳制（no-op）
        viewModel.goToNextHunk()
        assertEquals(2, viewModel.hunkNav.value?.index, "末 hunk 处推进须钳制在原位")

        // 回退一处有效
        viewModel.goToPrevHunk()
        assertEquals(1, viewModel.hunkNav.value?.index)
        assertEquals("第 2 / 3 处", viewModel.hunkNav.value?.positionLabel)

        // 重新发起对比：旧指针复位（不跨次生效）
        viewModel.startDiff()
        awaitDiffSettles(viewModel)
        val reset = assertNotNull(viewModel.hunkNav.value)
        assertEquals(-1, reset.index, "重新发起后指针须复位为未定位态")
        assertEquals(3, reset.count)
    }

    @Test
    fun `hunk navigation is unavailable for identical and degraded results`() {
        // 全等：hunks 空表无 hunk 可巡——指针不可用，推进/回退均 no-op
        val identical = fixtureFile("same.log", listOf("same", "lines"))
        val identical2 = fixtureFile("next.log", listOf("same", "lines"))
        val identicalVm = vm()
        identicalVm.onFilesSelected(Slot.OLD, listOf(identical))
        identicalVm.onFilesSelected(Slot.NEW, listOf(identical2))
        identicalVm.startDiff()
        awaitDiffSettles(identicalVm)
        assertTrue(identicalVm.diffState.value is DiffUiState.Done, "全等对比也应正常落定")
        assertNull(identicalVm.hunkNav.value, "全等结果巡检须不可用（无 hunk 可巡）")
        identicalVm.goToNextHunk()
        identicalVm.goToPrevHunk()
        assertNull(identicalVm.hunkNav.value, "不可用态推进/回退须保持 no-op")

        // degraded：中间区域整体替换、未逐行对齐——逐 hunk 巡检无定位意义
        val huge = fixtureFile("base.log", (1..4200).map { "old-$it" })
        val empty = fixtureFile("next.log", emptyList())
        val degradedVm = vm()
        degradedVm.onFilesSelected(Slot.OLD, listOf(huge))
        degradedVm.onFilesSelected(Slot.NEW, listOf(empty))
        degradedVm.startDiff()
        awaitDiffSettles(degradedVm)
        val degradedDone = degradedVm.diffState.value as? DiffUiState.Done
        assertNotNull(degradedDone)
        assertTrue(degradedDone.result.degraded, "前置：超对齐上限须退化")
        assertNull(degradedVm.hunkNav.value, "degraded 结果巡检须不可用（整体替换无逐行对齐）")
    }

    // ── 两向模式（分布对照路径 + 分立存储）────────────────────

    /** 轮询等待分布对照在真实 IO 上完成（两次 analyze 均飞真实文件读取） */
    private fun awaitCompareSettles(viewModel: LogDiffViewModel) {
        repeat(100) {
            dispatcher.scheduler.advanceUntilIdle()
            val state = viewModel.compareState.value
            if (state !is CompareUiState.Running && state !is CompareUiState.Idle) return
            Thread.sleep(20)
        }
    }

    /** 分布对照 fixture：基线 2 块（Alpha E + Beta W）；对比 4 块（Alpha E ×2 + Gamma E + 未识别裸头） */
    private fun distributionFixture(): Pair<File, File> {
        val old = fixtureFile(
            "base.log",
            listOf(
                "2026-10-11 08:00:01.000 E/Alpha: baseline-1",
                "continuation line",
                "2026-10-11 08:00:02.000 W/Beta: baseline-2",
            ),
        )
        val new = fixtureFile(
            "next.log",
            listOf(
                "2026-10-11 08:00:01.000 E/Alpha: current-1",
                "2026-10-11 08:00:02.000 E/Alpha: current-2",
                "2026-10-11 08:00:03.000 E/Gamma: current-3",
                "2026-10-11 08:00:04.000 裸头行无级别与 Tag 形态",
            ),
        )
        return old to new
    }

    @Test
    fun `distribution mode runs two analyzes and feeds the shared compare function`() {
        val (old, new) = distributionFixture()
        val viewModel = vm()
        viewModel.setMode(DiffMode.DISTRIBUTION)
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitCompareSettles(viewModel)

        val done = viewModel.compareState.value as? CompareUiState.Done
        assertNotNull(done, "分布模式发起后应跑完两次 analyze 并产对照结果：${viewModel.compareState.value}")
        assertEquals("base.log", done.oldLabel, "基准侧回显发起时文件名")
        assertEquals("next.log", done.newLabel)

        val compare = done.compare
        assertEquals(listOf("Gamma"), compare.newTags.map { it.tag }, "新 Tag = 基线缺席者（Gamma）")
        assertEquals(1, compare.newTags.single().count)
        assertEquals(listOf("Alpha"), compare.sharedTagDeltas.map { it.tag }, "共有 Tag 增量面（Alpha 1 → 2）")
        assertEquals(1, compare.sharedTagDeltas.single().delta)
        assertEquals(2, compare.errorDelta, "E 差值 = 3 − 1")
        assertEquals(0, compare.fatalDelta)
        assertEquals(-1, compare.levelDeltas[LogStatsAnalyzer.Level.WARN], "W 差值 = 0 − 1")
        // 未识别差值两侧分列：基线 0 / 对比 1（裸头块），禁归因不进 Tag 面
        assertEquals(0, compare.baselineUnparsedTagBlocks)
        assertEquals(1, compare.currentUnparsedTagBlocks)
        assertEquals(2, compare.baselineTotalBlocks)
        assertEquals(4, compare.currentTotalBlocks)
        assertTrue(
            viewModel.diffState.value is DiffUiState.Idle,
            "分布发起不得触行级状态机（分立存储）",
        )
    }

    @Test
    fun `mode switch keeps both on-stage results in their separate stores`() {
        // 行级先行：默认 LINE 跑出行级 Done
        val old = fixtureFile("base.log", listOf("alpha", "beta", "gamma"))
        val new = fixtureFile("next.log", listOf("alpha", "beta2", "gamma"))
        val viewModel = vm()
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        viewModel.startDiff()
        awaitDiffSettles(viewModel)
        assertTrue(viewModel.diffState.value is DiffUiState.Done, "前置：行级结果在台")

        // 切分布并发起：行级结果不得被清/覆写；分布照常落台
        viewModel.setMode(DiffMode.DISTRIBUTION)
        assertTrue(viewModel.diffState.value is DiffUiState.Done, "切模式不得丢行级在台结果")
        viewModel.startDiff()
        awaitCompareSettles(viewModel)
        assertTrue(viewModel.compareState.value is CompareUiState.Done, "分布对照应落台")
        assertTrue(
            viewModel.diffState.value is DiffUiState.Done,
            "分布发起不得触行级在台结果（导出回执/巡检指针为行级独有面）",
        )

        // 切回行级：分布结果仍在台（分立存储的双向不丢失）
        viewModel.setMode(DiffMode.LINE)
        assertTrue(viewModel.compareState.value is CompareUiState.Done, "切回行级不得丢分布在台结果")
        assertTrue(viewModel.diffState.value is DiffUiState.Done)
    }

    @Test
    fun `distribution run shares the missing-file rejection gate`() {
        val viewModel = vm()
        viewModel.setMode(DiffMode.DISTRIBUTION)
        viewModel.onFilesSelected(Slot.OLD, listOf(fixtureFile("base.log", listOf("line"))))
        viewModel.startDiff()

        val error = assertNotNull(viewModel.inputError.value, "分布模式缺一同样置 inputError（门禁共用）")
        assertTrue("对比文件尚未选择" in error, "文案须指明缺失槽位（与行级同源）：$error")
        assertTrue(viewModel.compareState.value is CompareUiState.Idle, "拒收后不得发起分布对照")
    }

    @Test
    fun `distribution analyze failure lands in compare failed state without touching line store`() {
        val old = fixtureFile("base.log", listOf("alpha"))
        val new = fixtureFile("next.log", listOf("beta"))
        val viewModel = vm()
        viewModel.setMode(DiffMode.DISTRIBUTION)
        viewModel.onFilesSelected(Slot.OLD, listOf(old))
        viewModel.onFilesSelected(Slot.NEW, listOf(new))
        // 发起前删两份文件：analyze 读盘失败折叠为 Failure → 对照态 Failed
        old.delete()
        new.delete()
        viewModel.startDiff()
        awaitCompareSettles(viewModel)

        val failed = viewModel.compareState.value as? CompareUiState.Failed
        assertNotNull(failed, "统计失败应落对照态 Failed：${viewModel.compareState.value}")
        assertTrue("统计失败" in failed.reason, "失败文案须携带引擎折叠原因：${failed.reason}")
        assertTrue(viewModel.diffState.value is DiffUiState.Idle, "分布失败不得触行级状态机")
    }
}
