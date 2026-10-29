package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GoXLogDecryptHook] XLog 解密页跳页槽单测（解压产物「送 XLog 解密」多文件分支）。
 *
 * 钉死槽语义五件事（[GoReportHookTest] 同款纪律——object 单例槽每用例 clearForTest 隔离）：
 * 1. 未 wire 时 offer 返回 false 且载荷不残留（调用点静默跳过多文件分支）；
 * 2. wire 后 offer 返回 true、回调达（宿主切页动作）、目录路径挂 pending；
 * 3. consumePendingDirectory 读即清（一次性载荷——二次取空，不重复入卡）；
 * 4. wire(null) 显式断线（宿主重建语义——旧接线不残留）；
 * 5. clearForTest 复位（测试隔离缝）。
 */
class GoXLogDecryptHookTest {

    @BeforeTest
    fun setUp() {
        GoXLogDecryptHook.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        GoXLogDecryptHook.clearForTest()
    }

    @Test
    fun `unwired offer is rejected and leaves no pending directory`() {
        assertFalse(GoXLogDecryptHook.isWired, "未接线态多文件分支可用性查询须为 false")
        assertFalse(
            GoXLogDecryptHook.offer("/tmp/demo_extracted"),
            "未接线 offer 须拒绝（投递被拒绝）",
        )
        assertNull(GoXLogDecryptHook.consumePendingDirectory(), "拒绝投递不得残留载荷")
    }

    @Test
    fun `wired offer reaches host callback and parks pending directory`() {
        var reached = 0
        GoXLogDecryptHook.wire { reached++ }
        val dir = "/tmp/demo_extracted"

        assertTrue(GoXLogDecryptHook.offer(dir), "接线后 offer 须达宿主跳页回调")
        assertEquals(1, reached, "跳页回调恰好执行一次（宿主切页动作）")
        assertEquals(
            dir,
            GoXLogDecryptHook.consumePendingDirectory(),
            "目录路径须挂 pending 待 XLog 解密页组合期消费",
        )
    }

    @Test
    fun `consume pending directory is read-once`() {
        GoXLogDecryptHook.wire { }
        GoXLogDecryptHook.offer("/tmp/demo_extracted")

        assertEquals("/tmp/demo_extracted", GoXLogDecryptHook.consumePendingDirectory())
        assertNull(
            GoXLogDecryptHook.consumePendingDirectory(),
            "载荷读即清（一次性——二次取空，不重复入卡）",
        )
    }

    @Test
    fun `wire null detaches the previous callback`() {
        var reached = 0
        GoXLogDecryptHook.wire { reached++ }
        GoXLogDecryptHook.wire(null)

        assertFalse(GoXLogDecryptHook.offer("/tmp/demo_extracted"), "wire(null) 显式断线——旧接线不残留")
        assertEquals(0, reached)
        assertNull(GoXLogDecryptHook.consumePendingDirectory(), "断线后 offer 拒绝且载荷不残留")
    }
}
