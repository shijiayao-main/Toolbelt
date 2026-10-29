package com.jiaoay.easy_dev_toolbox.page.shelltools.history

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 历史删除/清空回归（design D7）：
 * remove 单条后余项与顺序不变、clear 后为空、
 * 状态变更触发既有防抖持久化路径（drop(1)+debounce）落盘。
 */
class ShellHistoryManagerRemoveClearTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-history-test")

    private lateinit var isolatedStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
                isolatedStore = LocalDataManager(dataStoreName = "test_history_rm_${UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
    }

    private fun normalItem(id: String, useCount: Int = 1): ShellHistoryItem.Normal =
        ShellHistoryItem.Normal(
            id = id,
            displayLabel = id,
            command = id,
            useCount = useCount,
        )

    @Test
    fun `remove deletes single item and keeps remaining order`() = runBlocking {
        val manager = ShellHistoryManager(store = isolatedStore)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            manager.start(scope).join()

            manager.add(normalItem("cmd-a"))
            delay(10)
            manager.add(normalItem("cmd-b"))
            delay(10)
            manager.add(normalItem("cmd-c"))
            // 此时列表按新到旧：cmd-c, cmd-b, cmd-a

            manager.remove("cmd-b")

            val remaining = manager.historyFlow.value
            assertEquals(listOf("cmd-c", "cmd-a"), remaining.map { it.id }, "删除后余项及顺序不变")

            // 防抖持久化路径：等 debounce(1000ms) + 余量 后落盘，remove 结果持久
            delay(1_400)
            val json = isolatedStore.getValue(ShellToolsDataStore.KEY_SHELL_HISTORY, "")
            val persisted = ShellHistorySerializer.deserialize(json)
            assertEquals(listOf("cmd-c", "cmd-a"), persisted.map { it.id }, "删除结果应经防抖链路持久化")
        } finally {
            manager.close()
            scope.cancel()
        }
        Unit
    }

    @Test
    fun `remove unknown id is a no-op`() = runBlocking {
        val manager = ShellHistoryManager(store = isolatedStore)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            manager.start(scope).join()
            manager.add(normalItem("cmd-a"))
            manager.remove("not-exist")
            assertEquals(listOf("cmd-a"), manager.historyFlow.value.map { it.id })
        } finally {
            manager.close()
            scope.cancel()
        }
        Unit
    }

    @Test
    fun `clear empties history and persists via debounce path`() = runBlocking {
        val manager = ShellHistoryManager(store = isolatedStore)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            manager.start(scope).join()

            manager.add(normalItem("cmd-a"))
            delay(10)
            manager.add(normalItem("cmd-b"))
            assertTrue(manager.historyFlow.value.isNotEmpty(), "前置：应有历史条目")

            manager.clear()
            assertEquals(emptyList(), manager.historyFlow.value, "clear 后列表为空")

            // 防抖持久化路径落盘
            delay(1_400)
            val json = isolatedStore.getValue(ShellToolsDataStore.KEY_SHELL_HISTORY, "")
            assertEquals(emptyList(), ShellHistorySerializer.deserialize(json), "clear 结果应持久化")
        } finally {
            manager.close()
            scope.cancel()
        }
        Unit
    }

    @Test
    fun `flush after clear writes empty list immediately`() = runBlocking {
        val manager = ShellHistoryManager(store = isolatedStore)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            manager.start(scope).join()
            manager.add(normalItem("cmd-a"))
            manager.clear()
            withTimeout(2_000) { manager.flush() }
            val json = isolatedStore.getValue(ShellToolsDataStore.KEY_SHELL_HISTORY, "")
            assertEquals(emptyList(), ShellHistorySerializer.deserialize(json))
            assertNotNull(json, "flush 应写入空列表 JSON")
        } finally {
            manager.close()
            scope.cancel()
        }
        Unit
    }
}
