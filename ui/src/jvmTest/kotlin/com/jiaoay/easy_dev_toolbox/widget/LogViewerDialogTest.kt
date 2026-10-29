package com.jiaoay.easy_dev_toolbox.widget

import java.io.File
import java.nio.file.Files
import com.jiaoay.log.LogStatsAnalyzer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * 查看器窗口编排 VM 单测（[LogViewerStateHolder] wiring；引擎面由 :log-tools 自测覆盖，
 * 本文件只断言编排契约：打开 / 窗口分页 / 触边续读有界 / 行号跳转 / 级别导航 / 文本搜索 /
 * 时间戳跳转 / 漂移收口）。
 *
 * 驱动方式：scope 与 ioDispatcher 双注入 UnconfinedTestDispatcher——构造即同步完成
 * 打开，续读/跳转为纯挂起函数直接调用，全部断言无线程轮询。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerDialogTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun tempFile(lines: Int): File {
        val dir = Files.createTempDirectory("edt-log-viewer").toFile().also { tempDirs += it }
        val file = File(dir, "sample.log")
        file.writeText((1..lines).joinToString("\n") { "line-$it" } + "\n")
        return file
    }

    private fun tempContentFile(lines: List<String>): File {
        val dir = Files.createTempDirectory("edt-log-viewer").toFile().also { tempDirs += it }
        val file = File(dir, "sample.log")
        file.writeText(lines.joinToString("\n") + "\n")
        return file
    }

    private fun holder(file: File): LogViewerStateHolder {
        val dispatcher = UnconfinedTestDispatcher()
        return LogViewerStateHolder(file, CoroutineScope(dispatcher), dispatcher)
    }

    @Test
    fun `open success loads first window and reports totals`() {
        val vm = holder(tempFile(lines = 1200))
        assertEquals(ViewerUiState.Ready, vm.state.value, "打开成功应落 Ready")
        assertEquals(1200, vm.totalLines.value, "总行数应如实上报")
        assertEquals(500, vm.lines.value.size, "首窗应为单窗续读行数（500）")
        assertEquals(1, vm.lines.value.first().number, "首窗从第 1 行起")
        assertEquals(500, vm.lines.value.last().number, "首窗末行为 500")
    }

    @Test
    fun `open failure surfaces reason inline`() {
        val vm = holder(File(tempDirsPath(), "no-such-file.log"))
        val state = vm.state.value as? ViewerUiState.Failed
        assertTrue(state != null, "打开失败应落 Failed（原因内联明示）：${vm.state.value}")
        assertTrue("文件不存在" in state.reason, "失败原因应含不存在指引：${state.reason}")
    }

    @Test
    fun `empty file opens ready with zero lines`() {
        val vm = holder(tempFile(lines = 0).apply { writeText("") })
        assertEquals(ViewerUiState.Ready, vm.state.value, "空文件零行也落 Ready")
        assertEquals(0, vm.totalLines.value)
        assertTrue(vm.lines.value.isEmpty(), "空文件无窗口行")
        assertFalse(vm.canLoadMoreForward(), "零行文件触底无可续读")
    }

    @Test
    fun `bottom paging appends windows and stops at end of file`() = runBlocking {
        val vm = holder(tempFile(lines = 1200))
        val first = vm.loadNextPage()
        assertEquals(500, first.appended, "首次触底应追加整窗")
        assertEquals(1000, vm.lines.value.size)
        assertEquals(1000, vm.lines.value.last().number)

        val second = vm.loadNextPage()
        assertEquals(200, second.appended, "文件尾部只余 200 行（不足整窗按剩余计）")
        assertEquals(0, second.trimmedHead, "未达驻留上界不应裁剪")
        assertEquals(1200, vm.lines.value.last().number)

        val third = vm.loadNextPage()
        assertEquals(0, third.appended, "文件末行后触底续读应空手而归")
        assertFalse(vm.canLoadMoreForward(), "读满后 canLoadMoreForward 应为 false")
    }

    @Test
    fun `jump replaces window at target line with clamping`() = runBlocking {
        val vm = holder(tempFile(lines = 1200))
        assertTrue(vm.jumpTo(1001), "行号跳转应成功")
        assertEquals(1001, vm.lines.value.first().number, "跳转落点恒为窗口首行")
        assertEquals(200, vm.lines.value.size, "文件尾部不足整窗按剩余行计（1001..1200）")

        assertTrue(vm.jumpTo(99999), "越上界行号应钳制到末行而非拒绝")
        assertEquals(1200, vm.lines.value.first().number, "越界应钳制到总行数")
        assertEquals(1, vm.lines.value.size, "末行窗口只含末行")

        assertTrue(vm.jumpTo(0), "越下界行号应钳制到首行而非拒绝")
        assertEquals(1, vm.lines.value.first().number)
    }

    @Test
    fun `backward paging prepends context above window head`() = runBlocking {
        val vm = holder(tempFile(lines = 2000))
        assertTrue(vm.jumpTo(1000))
        val change = vm.loadPreviousPage()
        assertEquals(500, change.prepended, "触顶应前读整窗")
        assertEquals(500, vm.lines.value.first().number, "前读窗口应紧贴原窗口头")
        assertEquals(1499, vm.lines.value.last().number, "原窗口尾应保持")

        assertTrue(vm.canLoadMoreBackward(), "窗口头非文件首行仍可前读")
        assertTrue(vm.jumpTo(1))
        assertFalse(vm.canLoadMoreBackward(), "窗口头即文件首行时触顶无前读")
        assertEquals(0, vm.loadPreviousPage().prepended, "文件首行前读应空手而归")
    }

    @Test
    fun `resident window is capped and far end trimmed with reported displacement`() = runBlocking {
        val vm = holder(tempFile(lines = 6000))
        var sawTrim = false
        while (true) {
            val change = vm.loadNextPage()
            if (change.appended == 0) break
            if (change.trimmedHead > 0) sawTrim = true
        }
        assertTrue(sawTrim, "读满驻留上界后继续触底应触发头部裁剪")
        assertEquals(5000, vm.lines.value.size, "驻留行集恒不超引擎单窗口钳制档（5000）")
        assertEquals(1001, vm.lines.value.first().number, "裁剪只裁远端：保住靠近视口的近端")
        assertEquals(6000, vm.lines.value.last().number)
    }

    @Test
    fun `file drift surfaces drifted state and reopen recovers`() = runBlocking {
        val file = tempFile(lines = 5)
        val vm = holder(file)
        assertEquals(ViewerUiState.Ready, vm.state.value)

        file.appendText("line-6\n")
        assertFalse(vm.jumpTo(3), "漂移后的跳转应失败（索引已失效）")
        val drifted = vm.state.value as? ViewerUiState.Drifted
        assertTrue(drifted != null, "readWindow 漂移应折叠为 Drifted 态：${vm.state.value}")
        assertTrue("失效" in drifted.reason, "漂移原因应明示索引失效：${drifted.reason}")
        assertFalse(vm.canLoadMoreForward(), "漂移期间续读应拒绝（防反复撞漂移）")

        vm.reopen()
        assertEquals(ViewerUiState.Ready, vm.state.value, "原地重开应恢复 Ready")
        assertEquals(6, vm.totalLines.value, "重开按新文件长度重建索引")
        assertEquals(6, vm.lines.value.size, "小文件重开应整窗载入")
    }

    @Test
    fun `level navigation pages window to each next level line`() = runBlocking {
        // 1200 行、E 在 601/901（1 基行号，均在首窗 1..500 之外，验证跳转触发窗口分页）
        val file = tempContentFile(
            (1..1200).map { i ->
                if (i == 601 || i == 901) "05-09 10:00:00.000 E/Main: boom-$i" else "line-$i"
            },
        )
        val vm = holder(file)
        assertEquals(1, vm.lines.value.first().number, "首窗从第 1 行起")

        val errors = setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)
        assertTrue(vm.jumpToNextLevel(errors), "首窗之后有 E 应跳转成功")
        assertEquals(601, vm.lines.value.first().number, "导航落点恒为窗口首行（分页到目标行）")

        assertTrue(vm.jumpToNextLevel(errors), "连点应命中下一处 E")
        assertEquals(901, vm.lines.value.first().number)

        assertFalse(vm.jumpToNextLevel(errors), "末个命中之后再无 E 应失败")
        assertEquals(901, vm.lines.value.first().number, "无命中不动窗口")
    }

    @Test
    fun `level navigation without match or on empty file keeps window`() = runBlocking {
        val vm = holder(tempFile(lines = 300))
        assertFalse(
            vm.jumpToNextLevel(setOf(LogStatsAnalyzer.Level.WARN)),
            "无级别行文件应无命中",
        )
        assertEquals(1, vm.lines.value.first().number, "无命中不动窗口")

        val empty = holder(tempFile(lines = 0).apply { writeText("") })
        assertFalse(
            empty.jumpToNextLevel(setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)),
            "空文件无可导航行",
        )
    }

    // ── 级别导航回退 ──

    @Test
    fun `level navigation backwards pages window to each previous level line`() = runBlocking {
        // 1200 行、E 在 301/901（1 基行号，均在首窗 1..500 之外，验证回退触发窗口分页）
        val file = tempContentFile(
            (1..1200).map { i ->
                if (i == 301 || i == 901) "05-09 10:00:00.000 E/Main: boom-$i" else "line-$i"
            },
        )
        val vm = holder(file)
        val errors = setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)

        assertTrue(vm.jumpToNextLevel(errors))
        assertEquals(301, vm.lines.value.first().number)
        assertTrue(vm.jumpToNextLevel(errors))
        assertEquals(901, vm.lines.value.first().number)

        assertTrue(vm.jumpToPrevLevel(errors), "回退应命中上一处 E")
        assertEquals(301, vm.lines.value.first().number, "回退落点恒为窗口首行（分页到目标行）")

        assertFalse(vm.jumpToPrevLevel(errors), "首处之前无 E 应失败（无环绕）")
        assertEquals(301, vm.lines.value.first().number, "无命中不动窗口")
    }

    @Test
    fun `level backwards keeps current window head when it is the only level line`() = runBlocking {
        // E 仅在 601（1 基）：推进到 E 后回退不得重复命中当前行（起点不含口径）
        val file = tempContentFile(
            (1..1200).map { i ->
                if (i == 601) "05-09 10:00:00.000 E/Main: boom-$i" else "line-$i"
            },
        )
        val vm = holder(file)
        val errors = setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)

        assertTrue(vm.jumpToNextLevel(errors))
        assertEquals(601, vm.lines.value.first().number)

        assertFalse(vm.jumpToPrevLevel(errors), "当前行即首个 E：回退不得重复命中它")
        assertEquals(601, vm.lines.value.first().number, "无上一处不动窗口")
    }

    @Test
    fun `level navigation backwards without match or before any search keeps window`() = runBlocking {
        val vm = holder(tempFile(lines = 300))
        assertFalse(
            vm.jumpToPrevLevel(setOf(LogStatsAnalyzer.Level.WARN)),
            "无级别行文件应无命中",
        )
        assertEquals(1, vm.lines.value.first().number, "无命中不动窗口")

        val empty = holder(tempFile(lines = 0).apply { writeText("") })
        assertFalse(
            empty.jumpToPrevLevel(setOf(LogStatsAnalyzer.Level.ERROR, LogStatsAnalyzer.Level.FATAL)),
            "空文件无可导航行",
        )
    }

    // ── 文本搜索 ──

    @Test
    fun `search pages window to first match and same keyword advances to next`() = runBlocking {
        // 1200 行、boom 在 301/801/1101（1 基，均在首窗 1..500 之外或边缘，验证搜索触发窗口分页）
        val file = tempContentFile(
            (1..1200).map { i -> if (i == 301 || i == 801 || i == 1101) "line-$i boom" else "line-$i" },
        )
        val vm = holder(file)
        assertEquals(1, vm.lines.value.first().number, "首窗从第 1 行起")

        assertTrue(vm.searchOrNext("boom"), "有匹配应搜索成功并分页到首处")
        assertEquals(301, vm.lines.value.first().number, "搜索落点恒为窗口首行（分页到首处匹配）")
        assertEquals(3, vm.searchStatus.value?.matchCount, "匹配数应如实上报")
        assertEquals("boom", vm.searchStatus.value?.keyword)

        assertTrue(vm.searchOrNext("boom"), "同关键词再回车应跳下一处匹配")
        assertEquals(801, vm.lines.value.first().number)
        assertTrue(vm.searchOrNext("boom"), "连点应推进到末处匹配")
        assertEquals(1101, vm.lines.value.first().number)
        assertFalse(vm.searchOrNext("boom"), "末处之后再无匹配应失败（无环绕）")
        assertEquals(1101, vm.lines.value.first().number, "无下一处不动窗口")
    }

    @Test
    fun `search without match reports zero and blank keyword is ignored`() = runBlocking {
        val vm = holder(tempFile(lines = 300))
        assertFalse(vm.searchOrNext("absent"), "无匹配应失败")
        assertEquals(0, vm.searchStatus.value?.matchCount, "无匹配应上报零匹配")
        assertEquals(1, vm.lines.value.first().number, "无匹配不动窗口")

        assertFalse(vm.searchOrNext("   "), "空白关键词应被忽略")
        assertEquals("absent", vm.searchStatus.value?.keyword, "空白关键词不应更新搜索状态")
        assertEquals(1, vm.lines.value.first().number)
    }

    @Test
    fun `search on drifted file folds to drifted and reopen clears search state`() = runBlocking {
        val file = tempFile(lines = 5)
        val vm = holder(file)
        assertTrue(vm.searchOrNext("line"), "全行命中应搜索成功")
        assertEquals(5, vm.searchStatus.value?.matchCount)

        file.appendText("line-6\n")
        assertFalse(vm.searchOrNext("line"), "漂移后的搜索应失败（索引已失效）")
        assertTrue(vm.state.value is ViewerUiState.Drifted, "搜索面漂移应折叠为 Drifted 态：${vm.state.value}")

        vm.reopen()
        assertNull(vm.searchStatus.value, "重开应清零旧搜索态（文件已变化，旧匹配集不可复用）")
        assertTrue(vm.searchOrNext("line-6"), "重开后按新索引搜索命中")
        assertEquals(1, vm.searchStatus.value?.matchCount)
    }

    @Test
    fun `search backwards jumps to previous match and rewind stops at first`() = runBlocking {
        // 1200 行、boom 在 301/801/1101（1 基）
        val file = tempContentFile(
            (1..1200).map { i -> if (i == 301 || i == 801 || i == 1101) "line-$i boom" else "line-$i" },
        )
        val vm = holder(file)

        assertTrue(vm.searchOrNext("boom"))
        assertTrue(vm.searchOrNext("boom"))
        assertEquals(801, vm.lines.value.first().number)

        assertTrue(vm.searchPrev(), "回退应命中上一处匹配")
        assertEquals(301, vm.lines.value.first().number, "回退落点恒为窗口首行（分页到目标行）")

        assertFalse(vm.searchPrev(), "首处之前无匹配应失败（无环绕）")
        assertEquals(301, vm.lines.value.first().number, "无上一处不动窗口")

        // 回退后再推进复走原路（双向推进不互相破坏）
        assertTrue(vm.searchOrNext("boom"))
        assertEquals(801, vm.lines.value.first().number)
    }

    // ── 时间戳跳转 ──

    @Test
    fun `initial jump by timestamp pages window to engine seek hit line`() = runBlocking {
        // 打开请求带时间戳定位：落点 = nextTimestampLine 命中行（801 行，前窗之外）
        val file = tempContentFile(
            (1..1200).map { i -> if (i == 801) "05-09 13:25:30.000 E/Main: crash-$i" else "line-$i" },
        )
        val vm = holder(file)
        assertTrue(vm.jumpInitial(initialLine = null, initialTimestamp = "2026-05-09 13:25:30"))
        assertEquals(801, vm.lines.value.first().number, "初始定位落点 = 引擎时间戳 seek 命中行（恒窗口首行）")
        assertNull(vm.timestampJumpStatus.value, "初始定位成功应清零回执")

        // 裸 MM-dd 目标（工作台报告依据口径）同链路命中
        assertTrue(vm.jumpInitial(initialLine = null, initialTimestamp = "05-09 13:25:30"))
        assertEquals(801, vm.lines.value.first().number)
    }

    @Test
    fun `initial jump prefers line over timestamp and both null is no-op`() = runBlocking {
        val vm = holder(tempFile(lines = 1200))
        // 并存：行号优先（行号零歧义，时间戳是降级档）——落点取行号档
        assertTrue(vm.jumpInitial(initialLine = 1000, initialTimestamp = "2026-05-09 13:25:30"))
        assertEquals(1000, vm.lines.value.first().number)

        // 两档均 null：不动窗口返回 false
        assertFalse(vm.jumpInitial(initialLine = null, initialTimestamp = null))
        assertEquals(1000, vm.lines.value.first().number)
    }

    @Test
    fun `timestamp jump pages window to first line at or after target`() = runBlocking {
        // 1200 行、时间行在 801（1 基，首窗 1..500 之外，验证跳转触发窗口分页）
        val file = tempContentFile(
            (1..1200).map { i -> if (i == 801) "05-09 13:25:30.000 E/Main: crash-$i" else "line-$i" },
        )
        val vm = holder(file)
        assertEquals(1, vm.lines.value.first().number, "首窗从第 1 行起")

        assertTrue(vm.jumpToTimestamp("2026-05-09 13:25:30"), "命中应跳转成功")
        assertEquals(801, vm.lines.value.first().number, "跳转落点恒为窗口首行（分页到目标行）")
        assertNull(vm.timestampJumpStatus.value, "成功跳转应清零回执")

        // 容忍格式（空格分隔 + 省略毫秒）与目标时间解析契约同口径
        assertTrue(vm.jumpToTimestamp("2026-05-09 13:25:29"), "早 1 秒的目标仍命中间一行")
        assertEquals(801, vm.lines.value.first().number)
    }

    @Test
    fun `timestamp jump with invalid format surfaces receipt and clears on reopen`() = runBlocking {
        val vm = holder(tempFile(lines = 300))
        assertFalse(vm.jumpToTimestamp("13:25:30"), "缺日期的输入应跳转失败")
        assertEquals(TimestampJumpStatus.InvalidFormat, vm.timestampJumpStatus.value, "格式错应可见回执")
        assertEquals(1, vm.lines.value.first().number, "格式错不动窗口")

        vm.reopen()
        assertNull(vm.timestampJumpStatus.value, "重开应清零回执")
    }

    @Test
    fun `timestamp jump without timestamp line or beyond last reports no line and keeps window`() = runBlocking {
        val vm = holder(tempFile(lines = 300))
        assertFalse(vm.jumpToTimestamp("2026-05-09 13:25:30"), "无时间行文件应无命中")
        assertEquals(TimestampJumpStatus.NoTimestampAfterTarget, vm.timestampJumpStatus.value)
        assertEquals(1, vm.lines.value.first().number, "无命中不动窗口")

        val file = tempContentFile((1..300).map { "05-09 10:00:00.000 line-$it" })
        val bounded = holder(file)
        assertFalse(bounded.jumpToTimestamp("2026-05-09 11:00:00"), "目标晚于全部时间行应失败")
        assertEquals(TimestampJumpStatus.NoTimestampAfterTarget, bounded.timestampJumpStatus.value)
        assertEquals(1, bounded.lines.value.first().number, "无命中不动窗口")
    }

    @Test
    fun `search backwards without prior search or with current head as first match is no-op`() = runBlocking {
        val vm = holder(tempFile(lines = 300))
        assertFalse(vm.searchPrev(), "未搜索过回退应无操作")
        assertEquals(1, vm.lines.value.first().number)

        // 匹配集首处即当前窗口首行：回退不得重复命中它（起点不含口径）
        assertTrue(vm.searchOrNext("line-1"))
        assertEquals(1, vm.lines.value.first().number)
        assertFalse(vm.searchPrev(), "首处即当前窗口首行时应无上一处")
        assertEquals(1, vm.lines.value.first().number)
    }

    private fun tempDirsPath(): String =
        Files.createTempDirectory("edt-log-viewer-missing").toFile().also { tempDirs += it }.absolutePath
}
