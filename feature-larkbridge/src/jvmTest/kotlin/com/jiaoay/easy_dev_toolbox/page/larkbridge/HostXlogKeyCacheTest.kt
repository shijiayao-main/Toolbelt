package com.jiaoay.easy_dev_toolbox.page.larkbridge

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * [HostXlogKeyCache] 私钥变更即时反映（回落缝事件化收口的回归锚）。
 *
 * 🔒 钉死的不变量：
 * 1. 私钥写 key（真源 = `LocalDataManager.valueFlow` 经 DataStore 变更流）⇒ collect 写穿
 *    [HostXlogKeyCache.keyRef]——保存即热更，零轮询等待窗口（轮询形态下未运行态有
 *    分档间隔的陈旧窗口，事件化后归零）；
 * 2. 发射 null（key 未配置/整表被清）归一为空串——与「未配置」同一判定面；
 * 3. [HostXlogKeyCache.seed] 单次挂起读当前值（装配期同步读契约——collect 首帧异步
 *    到达，回落缝第一拍等不起首帧）；
 * 4. 无关 key 的落盘发射不扰动私钥缓存（valueFlow 的 map + 去重语义）。
 *
 * 真源用注入临时目录的 [LocalDataManager] 隔离实例（KoogAgentRunnerTest 同款注入缝
 * 形态，不碰真实用户数据目录），走真实 DataStore 变更流语义（edit 落盘即发射），
 * 非手工 MutableStateFlow。
 */
class HostXlogKeyCacheTest {

    private lateinit var store: LocalDataManager
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        // DataStore 无 close——目录留 /tmp 由系统清理，勿在 tearDown 强删
        //（在途写撞 freeSpace[0] 的 flaky 先例，见 UserHomeIsolation KDoc 删除纪律）。
        val dir = Files.createTempDirectory("xlog-key-cache-test").toFile()
        store = LocalDataManager(dataStoreName = "test_xlog_key_cache", datastoreDir = dir)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `私钥变更即时反映-写 key 后 collect 写穿回落缓存`() = runBlocking {
        val cache = HostXlogKeyCache(store.valueFlow("xlog_private_key"))
        // seed：单次挂起读当前值（collect 首帧异步，装配期回落缝靠种子先行）
        store.setValue("xlog_private_key", "key-seed")
        cache.seed()
        assertEquals("key-seed", cache.keyRef.get(), "seed 须单次挂起读当前值")

        cache.watchIn(scope)
        // 变更即时反映：写 key ⇒ 落盘发射 ⇒ 写穿 ref（零轮询窗口——不用等任何刷新拍）
        store.setValue("xlog_private_key", "key-1")
        awaitKey(cache, "key-1")
        store.setValue("xlog_private_key", "key-2")
        awaitKey(cache, "key-2", "连续变更须逐次即时写穿")
        // 撤配（空串）即时反映
        store.setValue("xlog_private_key", "")
        awaitKey(cache, "", "撤配（空串）须即时反映")
        // 整表被清 ⇒ 该 key 发射 null ⇒ 归一为空串（与「未配置」同一判定面）
        store.setValue("xlog_private_key", "key-3")
        awaitKey(cache, "key-3")
        store.clear()
        awaitKey(cache, "", "key 被清（发射 null）须归一为空串")
    }

    @Test
    fun `无关 key 的落盘发射不扰动私钥缓存`() = runBlocking {
        val cache = HostXlogKeyCache(store.valueFlow("xlog_private_key"))
        cache.seed()
        cache.watchIn(scope)
        store.setValue("xlog_private_key", "key-1")
        awaitKey(cache, "key-1")
        store.setValue("llm_api_key", "sk-other")
        delay(300)
        assertEquals("key-1", cache.keyRef.get(), "无关 key 落盘发射不得写穿私钥缓存（valueFlow 按 key map + 去重语义）")
    }

    /**
     * 轮询等待 ref 达期望值（DataStore 的真实文件 IO 走真线程，runTest 虚拟时间推不进
     * ——真实时间轮询上限 5s，到点即断，与 LarkBridgeViewModelTest 装配等待同款法）。
     */
    private suspend fun awaitKey(cache: HostXlogKeyCache, expected: String, message: String = "变更须即时写穿回落缓存（≤5s 等待窗口内）") {
        val deadline = System.currentTimeMillis() + 5_000
        while (cache.keyRef.get() != expected && System.currentTimeMillis() < deadline) {
            delay(10)
        }
        assertEquals(expected, cache.keyRef.get(), message)
    }
}
