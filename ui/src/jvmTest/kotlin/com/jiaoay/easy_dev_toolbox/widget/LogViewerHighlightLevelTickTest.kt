package com.jiaoay.easy_dev_toolbox.widget

import com.jiaoay.log.LogViewer
import java.io.File
import java.nio.file.Files
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
 * 查看器并行高亮通道 + 级别密度刻度单测（[LogViewerStateHolder] 通道动作/级别扫描
 * + [levelSlotOf] 槽映射纯函数 + [buildHighlightLayers] 层序合并纯函数）。
 * 通道命中扫描与级别行识别的引擎侧语义由 :log-tools LogViewerTest 覆盖，本文件
 * 只断言编排契约。驱动方式与 LogViewerBookmarkMinimapTest 同款：scope 与
 * ioDispatcher 双注入 UnconfinedTestDispatcher——构造即同步完成打开与级别扫描。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerHighlightLevelTickTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun tempContentFile(lines: List<String>): File {
        val dir = Files.createTempDirectory("edt-log-viewer-hl").toFile().also { tempDirs += it }
        val file = File(dir, "sample.log")
        file.writeText(lines.joinToString("\n") + "\n")
        return file
    }

    private fun plainLines(count: Int, vararg special: Pair<Int, String>): File =
        tempContentFile(
            (1..count).map { i -> special.firstOrNull { it.first == i }?.second ?: "line-$i" },
        )

    private fun holder(file: File): LogViewerStateHolder {
        val dispatcher = UnconfinedTestDispatcher()
        return LogViewerStateHolder(file, CoroutineScope(dispatcher), dispatcher)
    }

    // ── 级别密度槽映射（纯函数） ──

    @Test
    fun `level slot mapping is uniform clamped and null on empty file`() {
        // 空文件/零槽位：无槽可映射
        assertNull(levelSlotOf(0, 0, 100), "空文件无槽可映射")
        assertNull(levelSlotOf(5, 0, 100))
        assertNull(levelSlotOf(0, 10, 0), "零槽位无槽可映射")
        // 两端钳制：首行恒首槽、末行恒末槽；越界行下标按边界行计
        assertEquals(0, levelSlotOf(0, 100, 10))
        assertEquals(9, levelSlotOf(99, 100, 10))
        assertEquals(9, levelSlotOf(100, 100, 10), "越界行下标钳制到末槽")
        assertEquals(0, levelSlotOf(-1, 100, 10), "负行下标钳制到首槽")
        // 均匀分槽：行下标 × 槽位数 ÷ 总行数
        assertEquals(5, levelSlotOf(50, 100, 10))
        assertEquals(4, levelSlotOf(49, 100, 10))
        assertEquals(0, levelSlotOf(3, 100, 10))
        // 槽位数多于行数：每行独占一槽（行下标 × 槽位数 ÷ 总行数，刻度退化为逐行）
        assertEquals(300, levelSlotOf(30, 100, 1000))
        assertEquals(990, levelSlotOf(99, 100, 1000))
    }

    // ── 级别密度刻度扫描 ──

    @Test
    fun `level scan publishes first hit line per slot for error and warn`() = runBlocking {
        val vm = holder(
            plainLines(
                100,
                10 to "05-09 10:00:00.000 E/Main: err-a",
                30 to "05-09 10:00:00.100 W/Main: warn-a",
                70 to "05-09 10:00:00.200 E/Main: err-b",
            ),
        )
        // 槽位数多于总行数：每命中行独占一槽，槽首命中行即行自身（0 基下标）
        assertEquals(listOf(9, 69), vm.levelTicks.value.errorLineIndexes, "E 行应以槽首命中行发布")
        assertEquals(listOf(29), vm.levelTicks.value.warnLineIndexes, "W 行应以槽首命中行发布")
        // 无级别行（plain line-$i）不入任一刻度集
    }

    @Test
    fun `level scan bounds tick count to slot budget and reopen rescans`() = runBlocking {
        // 全 E 文件：刻度数以槽位数为上界（与搜索命中集同档）
        val vm = holder(tempContentFile((1..1200).map { "05-09 10:00:00.000 E/T: e-$it" }))
        val ticks = vm.levelTicks.value.errorLineIndexes
        assertEquals(LogViewer.MAX_SEARCH_RESULTS, ticks.size, "1200 行全 E 应填满全部槽位（不随行数膨胀）")
        assertTrue(ticks.size <= LogViewer.MAX_SEARCH_RESULTS)
        assertEquals(ticks.sorted(), ticks, "槽首命中行应升序发布")
        assertTrue(ticks.first() == 0 && ticks.last() in 0 until 1200, "刻度锚应留在行域内")

        vm.reopen()
        assertEquals(ticks, vm.levelTicks.value.errorLineIndexes, "同文件重开重扫应得出同一刻度集")
        assertTrue(vm.levelTicks.value.warnLineIndexes.isEmpty())
    }

    @Test
    fun `reopen after file change rescans into fresh level ticks`() = runBlocking {
        val file = tempContentFile((1..10).map { "05-09 10:00:00.000 E/T: e-$it" })
        val vm = holder(file)
        assertEquals(10, vm.levelTicks.value.errorLineIndexes.size)

        file.appendText("05-09 10:00:01.000 E/T: e-11\n")
        vm.reopen()
        assertEquals(11, vm.levelTicks.value.errorLineIndexes.size, "重开应重扫出新增 E 行")
    }

    // ── 并行高亮通道行为 ──

    @Test
    fun `channel add is capped at max channels`() = runBlocking {
        val vm = holder(plainLines(10))
        repeat(LogViewerStateHolder.MAX_HIGHLIGHT_CHANNELS) {
            assertTrue(vm.addHighlightChannel(), "满员前应可逐个新增通道")
        }
        assertFalse(vm.addHighlightChannel(), "超出通道上限应拒绝（呈现面以上限提示承接）")
        assertEquals(LogViewerStateHolder.MAX_HIGHLIGHT_CHANNELS, vm.highlightChannels.value.size)
    }

    @Test
    fun `channel keyword scans full file and publishes bounded hits`() = runBlocking {
        val vm = holder(plainLines(1200, 301 to "line-301 boom", 1101 to "line-1101 boom"))
        assertTrue(vm.addHighlightChannel())
        assertTrue(vm.applyHighlightKeyword(0, "boom"))
        val channel = vm.highlightChannels.value[0]
        assertEquals("boom", channel.keyword)
        assertEquals(listOf(300, 1100), channel.matchedLineIndexes, "通道命中集应与搜索同口径（0 基下标）")
        assertFalse(channel.truncated)

        // 高频词截断：命中数即上界值，截断明示（不得把上界当作命中总数）
        assertTrue(vm.applyHighlightKeyword(0, "line"))
        val capped = vm.highlightChannels.value[0]
        assertTrue(capped.truncated, "1200 行全命中须置截断标记")
        assertEquals(LogViewer.MAX_SEARCH_RESULTS, capped.matchCount)
        assertEquals(LogViewer.MAX_SEARCH_RESULTS, capped.matchedLineIndexes.size)
    }

    @Test
    fun `empty keyword clears channel hits keeping the channel`() = runBlocking {
        val vm = holder(plainLines(1200, 301 to "line-301 boom"))
        assertTrue(vm.addHighlightChannel())
        assertTrue(vm.applyHighlightKeyword(0, "boom"))
        assertEquals(1, vm.highlightChannels.value.size)

        assertTrue(vm.applyHighlightKeyword(0, "   "))
        val channel = vm.highlightChannels.value[0]
        assertEquals("", channel.keyword, "空关键词应清空提交态")
        assertEquals(0, channel.matchCount)
        assertTrue(channel.matchedLineIndexes.isEmpty())
        assertEquals(1, vm.highlightChannels.value.size, "通道本体保留待再输入")
    }

    @Test
    fun `toggle stops channel visibility without dropping matches and re-enable restores`() = runBlocking {
        val vm = holder(plainLines(1200, 301 to "line-301 boom"))
        assertTrue(vm.addHighlightChannel())
        assertTrue(vm.applyHighlightKeyword(0, "boom"))
        val before = buildHighlightLayers(vm.highlightChannels.value)
        assertEquals(listOf(300), before.hits.keys.toList(), "启用通道的命中应入可见层")

        vm.toggleHighlightChannel(0)
        val stopped = buildHighlightLayers(vm.highlightChannels.value)
        assertTrue(stopped.hits.isEmpty(), "停用通道整条退出可见层")
        assertTrue(stopped.tickGroups.isEmpty())
        assertEquals(listOf(300), vm.highlightChannels.value[0].matchedLineIndexes, "停用不清命中集")

        vm.toggleHighlightChannel(0)
        val restored = buildHighlightLayers(vm.highlightChannels.value)
        assertEquals(before.hits, restored.hits, "再启用应零重扫原样恢复可见层")
    }

    @Test
    fun `remove channel drops its lines from layers`() = runBlocking {
        val vm = holder(
            plainLines(
                1200,
                301 to "line-301 boom",
                501 to "line-501 crash",
            ),
        )
        assertTrue(vm.addHighlightChannel())
        assertTrue(vm.applyHighlightKeyword(0, "boom"))
        assertTrue(vm.addHighlightChannel())
        assertTrue(vm.applyHighlightKeyword(1, "crash"))
        assertEquals(setOf(300, 500), buildHighlightLayers(vm.highlightChannels.value).hits.keys.toSet())

        vm.removeHighlightChannel(0)
        assertEquals(1, vm.highlightChannels.value.size, "移除后通道数应减一")
        val layers = buildHighlightLayers(vm.highlightChannels.value)
        assertEquals(mapOf(500 to 0), layers.hits, "移除通道的命中行应整体退出可见层，后继通道序位前移")
        assertEquals(listOf(0), layers.tickGroups.map { it.colorSlot })
    }

    @Test
    fun `reopen clears highlight channels and rescans level ticks`() = runBlocking {
        val vm = holder(plainLines(1200, 301 to "line-301 boom", 10 to "05-09 10:00:00.000 E/T: e"))
        assertTrue(vm.addHighlightChannel())
        assertTrue(vm.applyHighlightKeyword(0, "boom"))
        assertTrue(vm.levelTicks.value.errorLineIndexes.isNotEmpty())

        vm.reopen()
        assertTrue(vm.highlightChannels.value.isEmpty(), "重开应清零通道（命中集锚定旧会话索引）")
        // 级别刻度随重开重扫（同文件同刻度），但通道集不复活
        assertTrue(vm.levelTicks.value.errorLineIndexes.isNotEmpty())
    }

    // ── 命中色层序（合并单源纯函数） ──

    @Test
    fun `lower slot wins on shared lines and disabled channels are excluded`() {
        val channels = listOf(
            HighlightChannel(keyword = "a", matchCount = 2, matchedLineIndexes = listOf(0, 5)),
            HighlightChannel(keyword = "b", matchCount = 2, matchedLineIndexes = listOf(5, 9)),
        )
        val layers = buildHighlightLayers(channels)
        assertEquals(0, layers.hits[5], "同一行多通道命中先建通道（小序）持色")
        assertEquals(0, layers.hits[0])
        assertEquals(1, layers.hits[9])
        assertEquals(listOf(listOf(0, 5), listOf(9)), layers.tickGroups.map { it.lineIndexes }, "同行只入持色通道的刻度组（行 5 归通道 0）")
        assertEquals(listOf(0, 1), layers.tickGroups.map { it.colorSlot })

        val withDisabledFirst = listOf(
            HighlightChannel(keyword = "a", enabled = false, matchCount = 1, matchedLineIndexes = listOf(0)),
            HighlightChannel(keyword = "b", matchCount = 1, matchedLineIndexes = listOf(0)),
        )
        val layers2 = buildHighlightLayers(withDisabledFirst)
        assertEquals(1, layers2.hits[0], "停用通道不入可见层，同行由启用通道持色")
        assertTrue(layers2.tickGroups.none { it.colorSlot == 0 }, "停用通道无刻度组")

        val allDisabled = listOf(
            HighlightChannel(keyword = "a", enabled = false, matchCount = 1, matchedLineIndexes = listOf(0)),
        )
        assertTrue(buildHighlightLayers(allDisabled).hits.isEmpty(), "全停用应产出空可见层")
    }

    // ── 行流着色层序（渲染接线守护；:ui 无 skiko 渲染运行时，源码切片是一等守护形态） ──

    @Test
    fun `viewer line list applies channel hit color before level color`() {
        val body = logViewerDialogSource().substringAfter("private fun ViewerLineList(")
        val hitIdx = body.indexOf("highlightHits[lineIndex]")
        val levelIdx = body.indexOf("?: levelColor(line.level)")
        assertTrue(hitIdx >= 0, "ViewerLineList 行着色须消费通道命中层（highlightHits）")
        assertTrue(
            levelIdx > hitIdx,
            "行着色层序须通道命中色优先于级别语义色（elvis 回落级别色）",
        )
    }

    private fun logViewerDialogSource(): String = sourceAt(
        "ui/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/widget/LogViewerDialog.kt",
    )

    private fun sourceAt(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
