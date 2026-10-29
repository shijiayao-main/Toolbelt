package com.jiaoay.easy_dev_toolbox.widget

import com.jiaoay.easy_dev_toolbox.core.data.db.Database
import com.jiaoay.easy_dev_toolbox.core.data.db.RunBookmarkStore
import com.jiaoay.log.parseLogLineTimestamp
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * run 级书签持久化 wiring 单测（[LogViewerStateHolder] 的 [RunBookmarkStore] 恢复/写回
 * 闭环）：行号 + 行文指纹双锚恢复、漂移拒绝、无 run 锚零行为、无时间戳行不入库。
 * 仓储面（迁移/快照覆写/级联）由 :core RunBookmarkStoreTest 覆盖，本文件只断言
 * 编排契约（指纹算法单源 `parseLogLineTimestamp`——两侧同口径）。
 *
 * 驱动方式与 LogViewerBookmarkMinimapTest 同款：scope 与 ioDispatcher 双注入
 * UnconfinedTestDispatcher——构造即同步完成打开（含存档恢复），动作直接调用。
 * 夹具隔离：库走 Database.inMemory 零文件，日志文件走临时目录，零触碰用户数据目录。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerBookmarkRestoreTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun timestampedFile(count: Int): File {
        val dir = Files.createTempDirectory("edt-log-viewer-restore").toFile().also { tempDirs += it }
        val file = File(dir, "artifact.txt")
        file.writeText(
            (1..count).joinToString("\n") { i -> "09-19 10:29:50.000 E/T: line-$i" } + "\n",
        )
        return file
    }

    /** 行首无时间戳的文件（指纹不可造面——行号跳转后标记首行） */
    private fun plainFile(count: Int): File {
        val dir = Files.createTempDirectory("edt-log-viewer-plain").toFile().also { tempDirs += it }
        val file = File(dir, "plain.txt")
        file.writeText((1..count).joinToString("\n") { i -> "no-timestamp line-$i" } + "\n")
        return file
    }

    private fun runAnchoredHolder(
        file: File,
        runId: String,
        store: RunBookmarkStore,
    ): LogViewerStateHolder {
        val dispatcher = UnconfinedTestDispatcher()
        return LogViewerStateHolder(file, CoroutineScope(dispatcher), dispatcher, runId, store)
    }

    private fun store(): RunBookmarkStore = RunBookmarkStore(Database.inMemory())

    @Test
    fun `run 级书签读写闭环——标记落库后重开按双锚恢复`() = runBlocking {
        val file = timestampedFile(20)
        val store = store()
        val writer = runAnchoredHolder(file, "run-1", store)
        assertTrue(writer.jumpTo(3))
        assertTrue(writer.toggleBookmarkAtWindowHead(), "行 3 落签")
        assertTrue(writer.jumpTo(7))
        assertTrue(writer.toggleBookmarkAtWindowHead(), "行 7 落签")

        // 写回契约：行号 1 基落库，指纹 = 行首时间戳（parseLogLineTimestamp 同口径；
        // 裸 MM-dd 行锚年随持有时钟——期望值经同 facade 同锚计算，不写死年份）
        val expectedFingerprint =
            "09-19 10:29:50.000 E/T: line-3".parseLogLineTimestamp(java.time.Year.now().value.toString())!!.toString()
        val stored = store.bookmarksFor("run-1", file.absolutePath)
        assertEquals(
            listOf(
                RunBookmarkStore.StoredBookmark(3, expectedFingerprint),
                RunBookmarkStore.StoredBookmark(7, expectedFingerprint),
            ),
            stored,
        )

        // 重开同 run 同文件：双锚（行号在界内 + 行文指纹一致）全过 → 恢复 + 人话回执
        val reader = runAnchoredHolder(file, "run-1", store)
        assertEquals(listOf(2, 6), reader.bookmarkLineIndexes.value, "恢复后书签行集 = 存档行号集（0 基）")
        assertEquals("已恢复上次书签 2 处", reader.bookmarkDiffReceipt.value)
    }

    @Test
    fun `行文漂移拒绝复活旧签且行号越界同拒`() = runBlocking {
        val file = timestampedFile(20)
        val store = store()
        // 存档两签：行 5 指纹陈旧（行文漂移）、行 99 越出当前文件界
        store.saveSnapshot(
            runId = "run-drift",
            filePath = file.absolutePath,
            bookmarks = listOf(
                RunBookmarkStore.StoredBookmark(5, "1999-01-01T00:00:00"),
                RunBookmarkStore.StoredBookmark(99, "2026-09-19T10:29:50"),
            ),
        )
        val reader = runAnchoredHolder(file, "run-drift", store)
        assertEquals(emptyList(), reader.bookmarkLineIndexes.value, "漂移/越界签一律拒绝复活")
        assertEquals("已恢复上次书签 0 处，2 处因文件已变化丢弃", reader.bookmarkDiffReceipt.value)
    }

    @Test
    fun `无 run 锚零持久化且清空动作落空快照`() = runBlocking {
        val file = timestampedFile(20)
        val store = store()
        val dispatcher = UnconfinedTestDispatcher()
        val sessionOnly = LogViewerStateHolder(file, CoroutineScope(dispatcher), dispatcher)
        assertTrue(sessionOnly.toggleBookmarkAtWindowHead())
        assertEquals(emptyList(), store.bookmarksFor("any", file.absolutePath), "无 run 锚不落库（会话级书签现状）")
        assertNull(sessionOnly.bookmarkDiffReceipt.value, "无存档恢复零回执")

        // 清空动作随快照持久化：先落两签再清空 → 存档清零
        val anchored = runAnchoredHolder(file, "run-2", store)
        assertTrue(anchored.jumpTo(4))
        assertTrue(anchored.toggleBookmarkAtWindowHead())
        assertEquals(1, store.bookmarksFor("run-2", file.absolutePath).size)
        anchored.clearBookmarks()
        assertEquals(emptyList(), store.bookmarksFor("run-2", file.absolutePath), "空快照覆写 = 清掉存档")
    }

    @Test
    fun `行首无时间戳的签不入库（指纹不可造宁缺勿假）`() = runBlocking {
        val file = plainFile(20)
        val store = store()
        val writer = runAnchoredHolder(file, "run-plain", store)
        assertTrue(writer.toggleBookmarkAtWindowHead())
        assertEquals(emptyList(), store.bookmarksFor("run-plain", file.absolutePath), "无指纹的签不持久化")
    }
}
