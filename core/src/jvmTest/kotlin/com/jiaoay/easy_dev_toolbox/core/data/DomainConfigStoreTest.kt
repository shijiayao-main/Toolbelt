package com.jiaoay.easy_dev_toolbox.core.data

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 域配置仓库共享骨架守护：read/write 必须逐次经 [DomainConfigStore.activeStore]
 * 当前值读写——缝整实例替换后，后续读写即时跟随新实例（各域 Store 隔离配法
 * 依赖的骨架级语义）；无 override 的默认通道恒经 [AppDataStore.store]。
 *
 * 隔离配方：构造期 datastoreDir 注入临时目录（LocalDataManager 的注入契约，
 * 不经 user.home 重定向），测试 key 走 test_ 前缀独立文件，零触碰真实数据目录。
 */
class DomainConfigStoreTest {

    /** 带缝骨架子类：复刻各域 Store 的 object + 缝 var + override 形态 */
    private object SeamStore : DomainConfigStore() {
        internal var store: LocalDataManager = AppDataStore.store
        override val activeStore: LocalDataManager get() = store
        suspend fun getName(default: String): String = read("skeleton_name", default)
        suspend fun setName(value: String) = write("skeleton_name", value)
        suspend fun getCount(default: Int): Int = read("skeleton_count", default)
        suspend fun setCount(value: Int) = write("skeleton_count", value)
    }

    /** 无缝骨架子类：不 override——activeStore 恒为 AppDataStore.store（无测试缝域的域差异形态） */
    private object NoSeamStore : DomainConfigStore() {
        suspend fun getMarker(default: String): String = read("skeleton_marker", default)
    }

    private lateinit var originalSeamStore: LocalDataManager
    private lateinit var originalAppStore: LocalDataManager
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        originalSeamStore = SeamStore.store
        originalAppStore = AppDataStore.storeForTest
        tempDir = Files.createTempDirectory("edt-domainstore").toFile()
    }

    @AfterTest
    fun tearDown() {
        // 临时目录不删：DataStore 在途写安全（UserHomeIsolation 类 KDoc 同款纪律）
        SeamStore.store = originalSeamStore
        AppDataStore.storeForTest = originalAppStore
    }

    private fun isolatedStore(): LocalDataManager =
        LocalDataManager(
            dataStoreName = "test_domainstore_${UUID.randomUUID()}",
            datastoreDir = tempDir,
        )

    @Test
    fun `seam store defaults and round trip`() = runBlocking {
        SeamStore.store = isolatedStore()
        assertEquals("anon", SeamStore.getName("anon"), "全新 store 读默认值")
        SeamStore.setName("alice")
        assertEquals("alice", SeamStore.getName("anon"), "String 写读往返")
        assertEquals(7, SeamStore.getCount(7), "Int 泛型路径读默认值")
        SeamStore.setCount(42)
        assertEquals(42, SeamStore.getCount(0), "Int 写读往返")
    }

    @Test
    fun `seam swap routes subsequent reads to the new instance`() = runBlocking {
        SeamStore.store = isolatedStore()
        SeamStore.setName("first")
        assertEquals("first", SeamStore.getName("unset"), "前置：第一实例写入生效")
        SeamStore.store = isolatedStore()
        assertEquals("unset", SeamStore.getName("unset"), "缝替换后读即时跟随新实例（旧实例值不可见）")
        SeamStore.setName("second")
        assertEquals("second", SeamStore.getName("unset"), "新实例写读往返")
    }

    @Test
    fun `no-override subclass reads via AppDataStore store`() = runBlocking {
        AppDataStore.storeForTest = isolatedStore()
        assertEquals("fallback", NoSeamStore.getMarker("fallback"), "默认通道经 AppDataStore.store 读默认值")
    }
}
