package com.jiaoay.easy_dev_toolbox.page.xlog

import com.jiaoay.easy_dev_toolbox.core.data.FileUtils
import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import com.jiaoay.easy_dev_toolbox.page.logkit.LogKitConfigStore
import com.jiaoay.testfixtures.UserHomeIsolation
import androidx.lifecycle.viewModelScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/**
 * 私钥持久化守卫单测（debounce 初值擦盘缺陷的回归钉）：
 *
 * 私钥输入经 StateFlow + trailing debounce 落盘——StateFlow 订阅即重放初值空串，
 * debounce 超时后照常向下发射；若无初值守卫，VM 创建后 400ms 内用户未碰输入框
 * 就会把盘上已配置的私钥静默写成空串（会话内 UI 读内存态无感，重启后才暴露
 * 为「key 丢失」）。守卫 = debounce 后丢首个重放值（桥接页 PERSIST_DEBOUNCE
 * 配方同款）；用户显式清空输入仍正常持久化空串。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XLogDecoderPrivateKeyPersistTest {

    private val homeIsolation = UserHomeIsolation("edt-xlog-key-persist-test")

    private val dispatcher = StandardTestDispatcher()

    /** DataStore IO 受控 scope：Unconfined 让文件 IO 在调用线程同步落定（LogTagFilter 同款配方） */
    private val datastoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private lateinit var originalStore: LocalDataManager

    private val createdVms = mutableListOf<XLogDecoderViewModel>()

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        originalStore = LogKitConfigStore.store
        // redirect 窗口内 eager 解析并钉死目录（LocalDataManager 构造参数契约）
        LogKitConfigStore.store = LocalDataManager(
            dataStoreName = "test_xlog_key_${java.util.UUID.randomUUID()}",
            datastoreDir = FileUtils.getDatastoreDir(),
            datastoreScope = datastoreScope,
        )
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        dispatcher.scheduler.advanceUntilIdle()
        createdVms.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        LogKitConfigStore.store = originalStore
        Dispatchers.resetMain()
        homeIsolation.restoreOnly()
    }

    @Test
    fun `vm creation alone never wipes the persisted key`() {
        runBlocking { LogKitConfigStore.setXLogPrivateKey("seed-key") }
        val vm = XLogDecoderViewModel(ioDispatcher = dispatcher).also { createdVms += it }
        // 静置虚拟时间远超 debounce 窗：全程不碰输入框
        dispatcher.scheduler.advanceTimeBy(5_000)
        dispatcher.scheduler.advanceUntilIdle()
        val stored = runBlocking { LogKitConfigStore.getXLogPrivateKey() }
        assertEquals(
            "seed-key",
            stored,
            "VM 创建后静置不得把盘上私钥擦成空串（debounce 初值重放须被丢弃）",
        )
    }

    @Test
    fun `explicit input change persists after debounce`() {
        val vm = XLogDecoderViewModel(ioDispatcher = dispatcher).also { createdVms += it }
        dispatcher.scheduler.advanceUntilIdle()
        vm.onPrivateKeyChange("new-key")
        dispatcher.scheduler.advanceTimeBy(1_000)
        dispatcher.scheduler.advanceUntilIdle()
        val stored = runBlocking { LogKitConfigStore.getXLogPrivateKey() }
        assertEquals("new-key", stored, "显式输入变更须经 debounce 正常落盘")
    }

    @Test
    fun `explicit clear still persists empty string`() {
        runBlocking { LogKitConfigStore.setXLogPrivateKey("seed-key") }
        val vm = XLogDecoderViewModel(ioDispatcher = dispatcher).also { createdVms += it }
        dispatcher.scheduler.advanceUntilIdle()
        vm.onPrivateKeyChange("")
        dispatcher.scheduler.advanceTimeBy(1_000)
        dispatcher.scheduler.advanceUntilIdle()
        val stored = runBlocking { LogKitConfigStore.getXLogPrivateKey() }
        assertEquals("", stored, "用户显式清空输入须持久化空串（守卫只丢重放初值，不吞用户输入）")
    }
}
