package com.jiaoay.testfixtures

import java.io.File
import java.nio.file.Files

/**
 * `user.home` 测试隔离器（跨模块测试基建单源）。
 *
 * 此前 16 个测试文件各持一份三件套样板：`originalUserHome` 捕获 + 临时 home 重定向 +
 * tearDown 还原。生产端 `core/data/FileUtils.kt` 经 `user.home` 定数据根是既定机制
 * （CLI 与 App 双形态共享），夹具形态只能如此——本类只收敛样板本身。
 *
 * 用法（各测试的 store 替换仍是自己的业务断言，不经本类）：
 * ```kotlin
 * private val home = UserHomeIsolation("edt-xxx-test")
 *
 * @BeforeTest fun setUp() { home.redirect() }
 * @AfterTest fun tearDown() { home.restore() }
 * ```
 *
 * 🔴 删除顺序纪律（DataStore flaky 先例——删临时 user.home 会让 DataStore 在途写
 *    炸 freeSpace[0]）：[restore] 先还原 user.home 再删目录；目录删除失败静默容忍
 *    （Windows 句柄滞留/DATAStore 在途写时强删会 flaky）。
 */
class UserHomeIsolation(
    /** 临时目录名前缀（各测试域可辨——edt-llm-test / edt-history-test…） */
    prefix: String,
) {
    private val original: String? = System.getProperty("user.home")
    private val tempHome: File = Files.createTempDirectory(prefix).toFile()

    /** 建临时 home 并重定向 user.home（幂等——重复调用无副作用）。 */
    fun redirect() {
        System.setProperty("user.home", tempHome.absolutePath)
    }

    /** 还原 user.home 后删临时目录（deleteRecursively 失败静默——见类 KDoc 删除纪律）。 */
    fun restore() {
        original?.let { System.setProperty("user.home", it) }
        runCatching { tempHome.deleteRecursively() }
    }

    /**
     * 只还原 user.home、**不删**临时目录（DataStore flaky 先例专用——见类 KDoc：LocalDataManager
     * 内 DataStore 是 by lazy 无 close，用例结束时可能有在途写指向临时目录，删目录会在途写撞
     * `freeSpace[0]` 随机挂；目录留 /tmp 由系统清理。AiSettingsViewModelTest 即此纪律）。
     */
    fun restoreOnly() {
        original?.let { System.setProperty("user.home", it) }
    }

    /** 当前临时 home（测试需要往里写文件时用）。 */
    val home: File get() = tempHome
}
