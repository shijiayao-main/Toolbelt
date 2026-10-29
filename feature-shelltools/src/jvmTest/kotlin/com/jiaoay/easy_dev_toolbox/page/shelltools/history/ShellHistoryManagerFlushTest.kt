package com.jiaoay.easy_dev_toolbox.page.shelltools.history

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.page.shelltools.ShellToolsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * D6 回归：flush() 以独立 LocalDataManager（临时目录）完成历史序列化写入且读回一致；
 * 退出路径有限阻塞（withTimeoutOrNull 包裹）语义正常。
 */
class ShellHistoryManagerFlushTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-history-test")

    private lateinit var isolatedStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
                isolatedStore = LocalDataManager(dataStoreName = "test_history_${UUID.randomUUID()}")
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
    }

    @Test
    fun `flush writes history to isolated store and reads back identical`() = runBlocking {
        val manager = ShellHistoryManager(store = isolatedStore)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            // 等待持久化历史恢复完成：加载协程会无条件覆盖列表，若在 add 之后落地
            // 会清掉新增条目（start 返回恢复 Job 供同步，消除该竞态窗口）
            manager.start(scope).join()

            manager.add(
                ShellHistoryItem.Normal(
                    id = "adb devices",
                    displayLabel = "adb devices",
                    command = "adb devices",
                )
            )
            manager.add(
                ShellHistoryItem.Chain(
                    id = "node-1",
                    displayLabel = "清理",
                    commands = listOf("pm clear com.demo", "am force-stop com.demo"),
                    nodeId = "node-1",
                )
            )

            // flush 在有限时间内完成（挂起写 DataStore，正常远低于 2s 上限）
            withTimeout(2_000) { manager.flush() }

            // 从独立 store 读回，序列化内容一致（含最后一条）
            val json = isolatedStore.getValue(ShellToolsDataStore.KEY_SHELL_HISTORY, "")
            assertTrue(json.isNotBlank(), "flush 后应写入非空历史 JSON")
            val restored = ShellHistorySerializer.deserialize(json)
            assertEquals(2, restored.size)
            val normal = restored.firstOrNull { it is ShellHistoryItem.Normal } as? ShellHistoryItem.Normal
            assertNotNull(normal, "普通历史应保留")
            assertEquals("adb devices", normal.command)
            assertEquals(1, normal.useCount, "add 合并语义：useCount 计数写入")
            val chain = restored.firstOrNull { it is ShellHistoryItem.Chain } as? ShellHistoryItem.Chain
            assertNotNull(chain, "链式历史应保留")
            assertEquals(listOf("pm clear com.demo", "am force-stop com.demo"), chain.commands)
        } finally {
            manager.close()
            scope.cancel()
        }
        Unit
    }

    @Test
    fun `flush on empty history writes empty list`() = runBlocking {
        val manager = ShellHistoryManager(store = isolatedStore)
        withTimeout(2_000) { manager.flush() }
        val json = isolatedStore.getValue(ShellToolsDataStore.KEY_SHELL_HISTORY, "")
        assertEquals(emptyList(), ShellHistorySerializer.deserialize(json))
        Unit
    }

    @Test
    fun `datastore file created in temp dir`() = runBlocking {
        val manager = ShellHistoryManager(store = isolatedStore)
        manager.add(
            ShellHistoryItem.Normal(id = "echo hi", displayLabel = "echo", command = "echo hi")
        )
        manager.flush()
        val dir = FileUtils.getDatastoreDir()
        val persisted = dir.listFiles { f -> f.extension == "preferences_pb" }?.firstOrNull { it.length() > 0L }
        assertTrue(persisted != null, "应落盘到临时目录 ${dir.absolutePath}（实际: ${dir.list()?.contentToString()}）")
        Unit
    }
}
