package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D5 回归：log_file_enabled 持久化读写。
 * 以独立 LocalDataManager 实例（临时目录）替换全局 store 验证：
 * 默认读取为 false、写入 true/false 后读回一致。
 */
class EasyDataManagerLogFileEnabledTest {

    /** user.home 隔离（共享件 UserHomeIsolation——审计批 2026-09-27 收敛） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-opentabs-test")

    private lateinit var originalStore: LocalDataManager
    private lateinit var isolatedStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        // 独立临时目录作为 user.home，使 FileUtils.getDatastoreDir() 指向临时位置
        
        // 独立 store 实例（随机文件名），与其他测试实例完全隔离
        originalStore = EasyDataManager.store
        isolatedStore = LocalDataManager(dataStoreName = "test_log_file_${UUID.randomUUID()}")
        EasyDataManager.store = isolatedStore
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
        EasyDataManager.store = originalStore
    }

    @Test
    fun `default value is false when no record exists`() = runBlocking {
        assertFalse(EasyDataManager.getLogFileEnabled(), "首次启动（无历史记录）应默认关闭")
    }

    @Test
    fun `write true then read back true`() = runBlocking {
        EasyDataManager.setLogFileEnabled(true)
        assertTrue(EasyDataManager.getLogFileEnabled(), "写入 true 后应读回 true")
    }

    @Test
    fun `write false then read back false`() = runBlocking {
        EasyDataManager.setLogFileEnabled(true)
        EasyDataManager.setLogFileEnabled(false)
        assertFalse(EasyDataManager.getLogFileEnabled(), "写入 false 后应读回 false")
    }

    @Test
    fun `write persists to real datastore file in temp dir`() = runBlocking {
        EasyDataManager.setLogFileEnabled(true)
        val datastoreDir = FileUtils.getDatastoreDir()
        val persisted = datastoreDir.listFiles { f -> f.extension == "preferences_pb" }
            ?.firstOrNull { it.length() > 0L }
        assertTrue(persisted != null, "写入应真实落盘到 ${datastoreDir.absolutePath}（当前仅存在: ${datastoreDir.list()?.contentToString()}）")
    }
}
