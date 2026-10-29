package com.jiaoay.easy_dev_toolbox.widget

import androidx.lifecycle.viewModelScope
import com.jiaoay.easy_dev_toolbox.widget.task.FileTaskViewModel
import com.jiaoay.easy_dev_toolbox.widget.task.ProcessResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 目录拖入展开语义单测（[expandDroppedEntries] 契约逐条锚定）：
 *
 * - 递归枚举 + 大小写扩展名 + 目录内混批跳过计数 + 同目录名称排序（顺序确定）；
 * - 混合拖入（顶层文件 + 目录）合并为一个输入批；
 * - 空目录/零匹配目录整批判拒且原因明示「目录内没有可处理的文件」；
 * - 纯文件类型不符沿用调用方回退文案（非目录输入零回归）；
 * - 展开关闭时目录不枚举、按类型不符跳过（未启用页面的既有语义）；
 * - 条目/层级双上限超限整批判拒（不截断收部分）；
 * - 符号链接目录不跟随（环形引用不死循环）；
 * - 双轨过滤：顶层非目录条目走 acceptsDirectEntry（用户点名文件交内容判型），
 *   目录展开产物与目录条目恒走 accepts（批量场景后缀粗滤，防目录内非日志文件
 *   噪音入卡）；acceptsDirectEntry=null 回落 accepts（与单轨版逐字同行为）；
 * - 展开产物喂 [FileTaskViewModel.addFiles] 逐文件建卡（目录批与单文件批零差别）。
 *
 * 最后一条需 Main 替换（建卡在 viewModelScope 测试调度器上挂起不推进，
 * 断言只看建卡面），FileTaskViewModelCancelTest 同款配方。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DropEntryExpansionTest {

    private val dispatcher = StandardTestDispatcher()
    private val tempDirs = mutableListOf<File>()
    private val createdVms = mutableListOf<FakeCollectingViewModel>()

    @AfterTest
    fun tearDown() {
        createdVms.forEach { runCatching { it.viewModelScope.cancel() } }
        Dispatchers.resetMain()
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun withMain(block: () -> Unit) {
        Dispatchers.setMain(dispatcher)
        try {
            block()
        } finally {
            // drain 纪律：挂起的 process 协程先取消再 resetMain，防跨用例并发窗口
            createdVms.forEach { runCatching { it.viewModelScope.cancel() } }
        }
    }

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("drop-expand-$tag").toFile().also { tempDirs += it }

    private fun file(parent: File, name: String): File =
        File(parent, name).apply { createNewFile() }

    /** 与 XLog 解密页同口径的谓词（大小写不敏感双扩展名） */
    private val acceptsXlog: (File) -> Boolean = {
        it.extension.equals("xlog", ignoreCase = true) || it.extension.equals("alog", ignoreCase = true)
    }

    private val fallbackRejectText = "不支持的文件类型，已忽略"

    @Test
    fun `directory entries expand recursively in sorted order`() {
        val root = tempDir("recursive")
        val sub = File(root, "sub").apply { mkdirs() }
        val deep = File(sub, "deep").apply { mkdirs() }
        val b = file(root, "b.xlog")
        val a = file(root, "a.alog")
        file(root, "note.txt")
        val c = file(deep, "c.XLOG")

        val outcome = expandDroppedEntries(
            entries = listOf(root),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
        )

        assertEquals(listOf(a, b, c), outcome.files)
        assertEquals(1, outcome.skippedCount)
        assertNull(outcome.rejectReason)
    }

    @Test
    fun `mixed batch of files and directories merges into one batch`() {
        val holder = tempDir("mixed")
        val sub = File(holder, "sub").apply { mkdirs() }
        file(sub, "inner-1.xlog")
        file(sub, "inner-2.xlog")
        val top = file(holder, "top.alog")
        val bad = file(holder, "bad.dat")

        val outcome = expandDroppedEntries(
            entries = listOf(sub, top, bad),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
        )

        assertEquals(setOf("inner-1.xlog", "inner-2.xlog", "top.alog"), outcome.files.map { it.name }.toSet())
        assertEquals(1, outcome.skippedCount)
        assertNull(outcome.rejectReason)
    }

    @Test
    fun `zero-match directory is whole-batch rejected with explicit reason`() {
        val emptyDir = tempDir("empty")
        val wrongOnly = tempDir("wrong-only")
        file(wrongOnly, "a.txt")
        file(wrongOnly, "b.dat")

        for (dir in listOf(emptyDir, wrongOnly)) {
            val outcome = expandDroppedEntries(
                entries = listOf(dir),
                accepts = acceptsXlog,
                expandDirectories = true,
                fallbackRejectText = fallbackRejectText,
            )
            assertTrue(outcome.files.isEmpty())
            assertTrue(
                "目录内没有可处理的文件" in outcome.rejectReason.orEmpty(),
                "零匹配目录必须明示目录侧原因，实际：${outcome.rejectReason}",
            )
        }

        // 多个零匹配目录合并计数
        val outcome = expandDroppedEntries(
            entries = listOf(emptyDir, wrongOnly),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
        )
        assertTrue("2 个目录" in outcome.rejectReason.orEmpty())
    }

    @Test
    fun `plain mismatch without directories keeps caller fallback reject text`() {
        val root = tempDir("plain")
        val bad = file(root, "a.dat")
        val outcome = expandDroppedEntries(
            entries = listOf(bad),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
        )
        assertTrue(outcome.files.isEmpty())
        assertEquals(fallbackRejectText, outcome.rejectReason)
    }

    @Test
    fun `direct-entry filter admits user-named file that extension filter rejects`() {
        val root = tempDir("direct-entry")
        val top = file(root, "capture") // 无后缀——用户点名文件，交下游内容判型

        val outcome = expandDroppedEntries(
            entries = listOf(top),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
            acceptsDirectEntry = { true },
        )
        assertEquals(listOf(top), outcome.files)
        assertEquals(0, outcome.skippedCount)
        assertNull(outcome.rejectReason)
    }

    @Test
    fun `same file admitted at top level but skipped inside directory expansion`() {
        val holder = tempDir("dual-track")
        val dir = File(holder, "logs").apply { mkdirs() }
        val inner = file(dir, "capture") // 目录展开产物恒走 accepts——后缀粗滤防噪音入卡
        val top = file(holder, "capture") // 顶层直拖走 acceptsDirectEntry

        val outcome = expandDroppedEntries(
            entries = listOf(dir, top),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
            acceptsDirectEntry = { true },
        )
        assertEquals(listOf(top), outcome.files)
        assertEquals(1, outcome.skippedCount, "目录内同款文件仍按 accepts 计入跳过")
        assertNull(outcome.rejectReason)
    }

    @Test
    fun `null direct-entry filter falls back to accepts verbatim`() {
        val root = tempDir("fallback")
        val top = file(root, "capture")

        // acceptsDirectEntry 缺省（null）= 单轨版逐字同行为：顶层条目也走 accepts
        val outcome = expandDroppedEntries(
            entries = listOf(top),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
        )
        assertTrue(outcome.files.isEmpty())
        assertEquals(1, outcome.skippedCount)
        assertEquals(fallbackRejectText, outcome.rejectReason)
    }

    @Test
    fun `directory entries are never admitted by the direct-entry filter`() {
        val holder = tempDir("dir-track")
        val dir = File(holder, "logs").apply { mkdirs() }
        file(dir, "a.xlog")

        // 展开关闭时目录条目不是「顶层非目录条目」——恒按 accepts 判定，
        // acceptsDirectEntry 放行面不得把目录当文件收进输入批
        val outcome = expandDroppedEntries(
            entries = listOf(dir),
            accepts = acceptsXlog,
            expandDirectories = false,
            fallbackRejectText = fallbackRejectText,
            acceptsDirectEntry = { true },
        )
        assertTrue(outcome.files.isEmpty())
        assertEquals(1, outcome.skippedCount)
    }

    @Test
    fun `expansion disabled keeps directory entries skipped without enumerating`() {
        val holder = tempDir("off")
        val root = File(holder, "inside").apply { mkdirs() }
        file(root, "a.xlog")
        file(root, "b.xlog")
        val top = file(holder, "top.xlog")

        // 目录不枚举（内部支持文件不并入），目录条目自身按类型不符跳过
        val outcome = expandDroppedEntries(
            entries = listOf(root, top),
            accepts = acceptsXlog,
            expandDirectories = false,
            fallbackRejectText = fallbackRejectText,
        )
        assertEquals(listOf(top), outcome.files)
        assertEquals(1, outcome.skippedCount)

        // 全为目录且无命中 → 回退调用方文案
        val outcomeAllDir = expandDroppedEntries(
            entries = listOf(root),
            accepts = acceptsXlog,
            expandDirectories = false,
            fallbackRejectText = fallbackRejectText,
        )
        assertTrue(outcomeAllDir.files.isEmpty())
        assertEquals(fallbackRejectText, outcomeAllDir.rejectReason)
    }

    @Test
    fun `entry cap rejects whole batch with reason`() {
        val holder = tempDir("cap")
        val root = File(holder, "bulk").apply { mkdirs() }
        repeat(4) { file(root, "f$it.xlog") }
        val top = file(holder, "top.xlog")

        val outcome = expandDroppedEntries(
            entries = listOf(top, root),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
            limits = DropExpansionLimits(maxEntries = 3, maxDepth = 16),
        )
        // 整批判拒：已命中的顶层文件不部分返回（截断会让用户误以为已收全）
        assertTrue(outcome.files.isEmpty())
        assertTrue(
            "目录条目数超过上限（3）" in outcome.rejectReason.orEmpty(),
            "条目超限必须带上限值原因，实际：${outcome.rejectReason}",
        )
    }

    @Test
    fun `depth cap rejects deep nesting with reason`() {
        val root = tempDir("depth")
        var deep = root
        repeat(4) { deep = File(deep, "l$it").apply { mkdirs() } }
        file(deep, "f.xlog")

        val outcome = expandDroppedEntries(
            entries = listOf(root),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
            limits = DropExpansionLimits(maxEntries = 1_000, maxDepth = 2),
        )
        assertTrue(outcome.files.isEmpty())
        assertTrue(
            "目录嵌套层级超过上限（2）" in outcome.rejectReason.orEmpty(),
            "层级超限必须带上限值原因，实际：${outcome.rejectReason}",
        )
    }

    @Test
    fun `symlink directories are not followed`() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        val root = tempDir("symlink")
        file(root, "a.xlog")
        // 环形符号链接：root/loop → root——不跟随则有限时间内完成
        val loopCreated = runCatching {
            Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        }.isSuccess
        if (!loopCreated) return

        val outcome = expandDroppedEntries(
            entries = listOf(root),
            accepts = acceptsXlog,
            expandDirectories = true,
            fallbackRejectText = fallbackRejectText,
        )
        assertEquals(listOf("a.xlog"), outcome.files.map { it.name })
        assertNull(outcome.rejectReason)
    }

    @Test
    fun `expanded directory files feed file task view model one card per file`() {
        withMain {
            val root = tempDir("vm-feed")
            val sub = File(root, "sub").apply { mkdirs() }
            file(root, "a.xlog")
            file(sub, "b.xlog")
            file(sub, "c.dat")

            val outcome = expandDroppedEntries(
                entries = listOf(root),
                accepts = acceptsXlog,
                expandDirectories = true,
                fallbackRejectText = fallbackRejectText,
            )
            assertEquals(2, outcome.files.size)

            val vm = FakeCollectingViewModel()
            createdVms += vm
            vm.addFiles(outcome.files)
            val cards = vm.tasks.value
            assertEquals(setOf("a.xlog", "b.xlog"), cards.map { it.fileName }.toSet())
            // 目录批展开后与单文件批零差别：逐文件建卡、全部入运行态
            assertEquals(2, cards.count { it.status is TaskUiStatus.Running })
        }
    }

    /** 收集型假 VM：产物即输入路径（建卡面断言用，process 不推进也不影响建卡） */
    private class FakeCollectingViewModel : FileTaskViewModel() {
        override suspend fun process(file: File): ProcessResult = ProcessResult.Success(file.absolutePath)
    }
}
