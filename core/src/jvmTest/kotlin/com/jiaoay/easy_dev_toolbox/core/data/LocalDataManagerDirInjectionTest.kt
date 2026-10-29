package com.jiaoay.easy_dev_toolbox.core.data

import com.jiaoay.testfixtures.UserHomeIsolation
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 密钥文件权限域守护（谓词单源 + 目录注入契约）：
 * - 谓词「文件名含 .preferences_pb」必须命中后缀变体（.bak-forensic / .tmp）——
 *   后缀变体不以 marker 结尾、endsWith 匹配必漏是既有失守根因，谓词单源化后由本测试钉死。
 * - LocalDataManager 的目录注入必须在构造期钉死落盘位置：DataStore 的文件路径在该实例
 *   首次读写时才解析（androidx FileStorage 在 createConnection 时才调 produceFile），
 *   `user.home` 在首操作前被还原时，无注入实例会把 test_* 文件落进真实数据目录。
 */
class LocalDataManagerDirInjectionTest {

    @Test
    fun `predicate matches suffix variants of secret preferences files`() {
        assertTrue(SecretFilePerms.isSecretPreferencesFile("easy_dev_toolbox.preferences_pb"))
        assertTrue(SecretFilePerms.isSecretPreferencesFile("easy_dev_toolbox.preferences_pb.bak-forensic"))
        assertTrue(SecretFilePerms.isSecretPreferencesFile("test_vm_x.preferences_pb.tmp"))
        assertFalse(SecretFilePerms.isSecretPreferencesFile("shell_tools.json"))
        assertFalse(SecretFilePerms.isSecretPreferencesFile("bridge-config.json.bak"))
        assertFalse(SecretFilePerms.isSecretPreferencesFile("audit.jsonl"))
    }

    @Test
    fun `injected dir pins datastore file even when user home is restored before first op`() {
        val home = UserHomeIsolation("edt-ds-injection-test")
        val outsideDir = FileUtils.getDatastoreDir() // 用例开始时（未被本用例重定向的）数据目录
        val before = outsideDir.fileNames()
        home.redirect()
        try {
            // redirect 窗口内 eager 解析并注入构造——隔离配方的唯一正确时序
            val injectedDir = FileUtils.getDatastoreDir()
            val store = LocalDataManager(
                dataStoreName = "test_dir_injection_${UUID.randomUUID()}",
                datastoreDir = injectedDir,
            )
            home.restoreOnly() // 首操作前还原 user.home——复刻既有泄漏时序
            val restoredOutsideDir = FileUtils.getDatastoreDir()
            assertTrue(restoredOutsideDir != injectedDir, "前置：还原后解析到的目录必须异于注入目录（否则用例失去判别力）")

            runBlocking { store.setValue("guard_key", "guard_value") } // 首操作发生在 redirect 窗口外

            assertEquals(before, restoredOutsideDir.fileNames(), "真实数据目录零新增（首操作在窗口外也不得落错目录）")
            val written = injectedDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.contains(SecretFilePerms.PREFERENCES_MARKER) }
            assertTrue(written.isNotEmpty(), "写入必须落注入目录：${injectedDir}")
            assertTrue(written.any { it.length() > 0L }, "注入目录中的 preferences 文件须非空")
        } finally {
            // 幂等还原；临时目录不删（DataStore 在途写安全——UserHomeIsolation 类 KDoc 纪律）
            home.restoreOnly()
        }
    }

    private fun java.io.File.fileNames(): Set<String> =
        listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
}
