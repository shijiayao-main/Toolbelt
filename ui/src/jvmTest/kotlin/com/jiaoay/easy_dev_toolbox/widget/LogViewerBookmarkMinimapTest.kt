package com.jiaoay.easy_dev_toolbox.widget

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
 * 查看器书签 + 命中小地图 wiring 单测（[LogViewerStateHolder] 书签动作 + 时间差回执
 * + 命中集发布；[lineFraction] / [fractionToLineIndex] 比例映射纯函数含空/单行边界）。
 * 内核差值计算与书签集语义由 :log-tools LogBookmarkTest 覆盖，本文件只断言编排契约。
 *
 * 驱动方式与 LogViewerDialogTest 同款：scope 与 ioDispatcher 双注入
 * UnconfinedTestDispatcher——构造即同步完成打开，动作直接调用，无线程轮询。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerBookmarkMinimapTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun tempContentFile(lines: List<String>): File {
        val dir = Files.createTempDirectory("edt-log-viewer-bm").toFile().also { tempDirs += it }
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

    // ── 小地图比例映射（纯函数） ──

    @Test
    fun `minimap mapping handles empty single and boundary line sets`() {
        // 空文件：无可跳目标（点击不动窗口）
        assertNull(fractionToLineIndex(0.5f, 0), "空行集应无可跳目标")
        // 单行文件：任意落点恒唯一行
        assertEquals(0, fractionToLineIndex(0.9f, 1), "单行文件落点恒行 0")
        assertEquals(0f, lineFraction(0, 1), "单行文件唯一行落列顶")
        assertEquals(0f, lineFraction(0, 0), "空行集无行可绘")
        // 两端边界与钳制（点击越出列体按边界行计）
        assertEquals(0, fractionToLineIndex(0f, 100))
        assertEquals(99, fractionToLineIndex(1f, 100))
        assertEquals(0, fractionToLineIndex(-0.25f, 100), "负比例钳制到首行")
        assertEquals(99, fractionToLineIndex(1.25f, 100), "超界比例钳制到末行")
        // 逆映射一致性：行下标 → 比例 → 行下标往返（两端与中点）
        assertEquals(0, fractionToLineIndex(lineFraction(0, 100), 100))
        assertEquals(50, fractionToLineIndex(lineFraction(50, 100), 100))
        assertEquals(99, fractionToLineIndex(lineFraction(99, 100), 100))
    }

    // ── 书签标记与发布 ──

    @Test
    fun `toggle marks and unmarks window head publishing sorted snapshot`() = runBlocking {
        val vm = holder(plainLines(1200))

        assertTrue(vm.toggleBookmarkAtWindowHead(), "首窗窗口首行应可标记")
        assertEquals(listOf(0), vm.bookmarkLineIndexes.value, "书签快照为 0 基升序")

        assertTrue(vm.jumpTo(800))
        assertTrue(vm.toggleBookmarkAtWindowHead(), "跳转后窗口首行（800）应可标记")
        assertEquals(listOf(0, 799), vm.bookmarkLineIndexes.value, "快照按行序升序发布（与标记序无关）")

        assertFalse(vm.toggleBookmarkAtWindowHead(), "已标记窗口首行再翻转应取消标记")
        assertEquals(listOf(0), vm.bookmarkLineIndexes.value)
    }

    @Test
    fun `toggle is rejected on drifted session and reopen clears bookmarks`() = runBlocking {
        val file = tempContentFile((1..5).map { "line-$it" })
        val vm = holder(file)
        assertTrue(vm.toggleBookmarkAtWindowHead())
        assertEquals(listOf(0), vm.bookmarkLineIndexes.value)

        file.appendText("line-6\n")
        assertFalse(vm.jumpTo(3), "漂移后跳转应失败（索引已失效）")
        assertTrue(vm.state.value is ViewerUiState.Drifted, "readWindow 漂移应折叠为 Drifted 态")
        assertFalse(vm.toggleBookmarkAtWindowHead(), "非 Ready 态不得标记（书签锚定本次会话索引）")

        vm.reopen()
        assertEquals(emptyList<Int>(), vm.bookmarkLineIndexes.value, "重开应清零书签（旧索引书签不可复用）")
    }

    // ── 上一签/下一签跳转 ──

    @Test
    fun `bookmark jumps advance and rewind without repeat hit`() = runBlocking {
        val vm = holder(plainLines(1200))
        // 标记三签：行 1 / 400 / 700（1 基）
        assertTrue(vm.toggleBookmarkAtWindowHead())
        assertTrue(vm.jumpTo(400))
        assertTrue(vm.toggleBookmarkAtWindowHead())
        assertTrue(vm.jumpTo(700))
        assertTrue(vm.toggleBookmarkAtWindowHead())
        assertEquals(listOf(0, 399, 699), vm.bookmarkLineIndexes.value)

        assertTrue(vm.jumpToPrevBookmark(), "回退应命中上一签")
        assertEquals(400, vm.lines.value.first().number, "回退落点恒为窗口首行")
        assertTrue(vm.jumpToPrevBookmark())
        assertEquals(1, vm.lines.value.first().number)
        assertFalse(vm.jumpToPrevBookmark(), "首签之前无回退（无环绕）")
        assertEquals(1, vm.lines.value.first().number, "无上一签不动窗口")

        assertTrue(vm.jumpToNextBookmark(), "推进应命中下一签")
        assertEquals(400, vm.lines.value.first().number)
        assertTrue(vm.jumpToNextBookmark())
        assertEquals(700, vm.lines.value.first().number)
        assertFalse(vm.jumpToNextBookmark(), "末签之后无推进（无环绕）")
        assertEquals(700, vm.lines.value.first().number, "无下一签不动窗口")
    }

    // ── 两签时间差 ──

    @Test
    fun `bookmark diff measures latest two marks with human receipt`() = runBlocking {
        // 行 1 / 300 带行首时间戳（1 基），其余无时间戳行不参与本用例
        val vm = holder(
            plainLines(
                1200,
                1 to "05-09 10:00:00.000 E/Main: start",
                300 to "05-09 10:00:02.500 E/Main: end",
            ),
        )
        assertTrue(vm.toggleBookmarkAtWindowHead())
        assertTrue(vm.jumpTo(300))
        assertTrue(vm.toggleBookmarkAtWindowHead())

        assertTrue(vm.measureBookmarkDiff(), "两签均带时间戳应测量成功")
        val receipt = vm.bookmarkDiffReceipt.value
        assertTrue(receipt != null, "成功测量应发布回执")
        assertTrue("第 1 行 → 第 300 行：2.500 秒" in receipt, "回执应含行号与人话差值：$receipt")
    }

    @Test
    fun `bookmark diff reports reversed time order explicitly`() = runBlocking {
        // 行序在后（300）时间在先——差值为负，回执须明示倒流不取绝对值
        val vm = holder(
            plainLines(
                1200,
                1 to "05-09 10:00:05.000 E/Main: later-line",
                300 to "05-09 10:00:00.000 E/Main: earlier-line",
            ),
        )
        assertTrue(vm.toggleBookmarkAtWindowHead())
        assertTrue(vm.jumpTo(300))
        assertTrue(vm.toggleBookmarkAtWindowHead())

        assertTrue(vm.measureBookmarkDiff(), "两端可解析应测量成功（负差值同样成功）")
        val receipt = vm.bookmarkDiffReceipt.value
        assertTrue(receipt != null)
        assertTrue("时间倒流" in receipt, "跨行序负差值须明示倒流：$receipt")
        assertTrue("5.000 秒" in receipt, "量级段须保留（不静默取绝对值）：$receipt")
    }

    @Test
    fun `bookmark diff rejects mark without head timestamp in human words`() = runBlocking {
        // 后签（300 行）无行首时间戳：人话拒绝，不猜差值
        val vm = holder(
            plainLines(1200, 1 to "05-09 10:00:00.000 E/Main: start"),
        )
        assertTrue(vm.toggleBookmarkAtWindowHead())
        assertTrue(vm.jumpTo(300))
        assertTrue(vm.toggleBookmarkAtWindowHead())

        assertFalse(vm.measureBookmarkDiff(), "无时间戳签应拒绝测量")
        val receipt = vm.bookmarkDiffReceipt.value
        assertTrue(receipt != null, "拒绝也应发布回执（人话明示，不静默）")
        assertTrue("第 300 行" in receipt, "拒绝回执应定位到无时间戳的签：$receipt")
        assertTrue("无行首时间戳" in receipt, "拒绝回执须是人话原因（markAnchor 无可锚行拒绝口径同款）：$receipt")
    }

    @Test
    fun `bookmark diff without two marks is no-op and clear resets receipt`() = runBlocking {
        val vm = holder(plainLines(300))
        assertTrue(vm.toggleBookmarkAtWindowHead())
        assertFalse(vm.measureBookmarkDiff(), "单签无差可测应无操作")
        assertNull(vm.bookmarkDiffReceipt.value, "无操作不得改写回执")

        assertTrue(vm.jumpTo(100))
        assertTrue(vm.toggleBookmarkAtWindowHead())
        // 两签但行文本无时间戳：拒绝档回执同样发布（与测量成功同为发布态）
        assertFalse(vm.measureBookmarkDiff())
        assertTrue(vm.bookmarkDiffReceipt.value != null, "拒绝档回执应发布")
        vm.clearBookmarks()
        assertEquals(emptyList<Int>(), vm.bookmarkLineIndexes.value, "清空应清零书签快照")
        assertNull(vm.bookmarkDiffReceipt.value, "清空应清零时间差回执")
    }

    // ── 命中集发布（小地图数据源） ──

    @Test
    fun `search publishes matched line indexes and reopen clears them`() = runBlocking {
        // boom 在 301/801/1101（1 基）；命中集为 0 基下标
        val vm = holder(
            plainLines(
                1200,
                301 to "line-301 boom",
                801 to "line-801 boom",
                1101 to "line-1101 boom",
            ),
        )
        assertTrue(vm.searchOrNext("boom"))
        assertEquals(listOf(300, 800, 1100), vm.matchedLineIndexes.value, "命中集应以 0 基发布给小地图")

        vm.reopen()
        assertEquals(emptyList<Int>(), vm.matchedLineIndexes.value, "重开应清零命中集（旧索引匹配不可复用）")
    }
}
