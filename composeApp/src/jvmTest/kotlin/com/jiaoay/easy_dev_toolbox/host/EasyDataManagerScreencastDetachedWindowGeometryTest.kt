package com.jiaoay.easy_dev_toolbox.host

import com.jiaoay.easy_dev_toolbox.core.data.LocalDataManager
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 投屏独立窗几何四 key 持久化读写回归：以独立 LocalDataManager 实例（随机文件名）
 * 替换全局 store 验证——默认未记录哨兵（-1）、写后读回一致（含负坐标合法值）、
 * 四元组一次原子落盘。
 */
class EasyDataManagerScreencastDetachedWindowGeometryTest {

    /** user.home 隔离（共享件 UserHomeIsolation） */
    private val homeIsolation = com.jiaoay.testfixtures.UserHomeIsolation("edt-screencast-geometry-test")

    private lateinit var originalStore: LocalDataManager
    private lateinit var isolatedStore: LocalDataManager

    @BeforeTest
    fun setUp() {
        homeIsolation.redirect()
        originalStore = EasyDataManager.store
        isolatedStore = LocalDataManager(dataStoreName = "test_screencast_geo_${UUID.randomUUID()}")
        EasyDataManager.store = isolatedStore
    }

    @AfterTest
    fun tearDown() {
        homeIsolation.restore()
        EasyDataManager.store = originalStore
    }

    @Test
    fun `default geometry is unrecorded sentinel`() = runBlocking {
        assertEquals(-1, EasyDataManager.getScreencastDetachedWindowX(), "无记录哨兵 -1（x）")
        assertEquals(-1, EasyDataManager.getScreencastDetachedWindowY(), "无记录哨兵 -1（y）")
        assertEquals(-1, EasyDataManager.getScreencastDetachedWindowWidth(), "无记录哨兵 -1（width）")
        assertEquals(-1, EasyDataManager.getScreencastDetachedWindowHeight(), "无记录哨兵 -1（height）")
    }

    @Test
    fun `geometry quadruple round trips with negative coordinates`() = runBlocking {
        // x 负值合法（副屏在主屏左侧的坐标系），不得被符号判断误杀
        EasyDataManager.setScreencastDetachedWindowGeometry(x = -1920, y = 40, width = 450, height = 800)
        assertEquals(-1920, EasyDataManager.getScreencastDetachedWindowX())
        assertEquals(40, EasyDataManager.getScreencastDetachedWindowY())
        assertEquals(450, EasyDataManager.getScreencastDetachedWindowWidth())
        assertEquals(800, EasyDataManager.getScreencastDetachedWindowHeight())
    }

    @Test
    fun `geometry rewrite overwrites all four values`() = runBlocking {
        EasyDataManager.setScreencastDetachedWindowGeometry(10, 20, 100, 200)
        EasyDataManager.setScreencastDetachedWindowGeometry(30, 40, 300, 400)
        assertEquals(30, EasyDataManager.getScreencastDetachedWindowX())
        assertEquals(40, EasyDataManager.getScreencastDetachedWindowY())
        assertEquals(300, EasyDataManager.getScreencastDetachedWindowWidth())
        assertEquals(400, EasyDataManager.getScreencastDetachedWindowHeight())
    }
}
