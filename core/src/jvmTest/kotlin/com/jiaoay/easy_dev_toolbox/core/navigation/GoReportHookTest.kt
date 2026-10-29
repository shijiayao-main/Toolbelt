package com.jiaoay.easy_dev_toolbox.core.navigation

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GoReportHook] 报告页跳转中立槽单测。
 *
 * 钉死槽语义四件事（[AiEnabledSignalTest]/GoSettingsHook 同款纪律——object 单例槽
 * 每用例 clearForTest 隔离）：
 * 1. 未 wire 时 offer 返回 false（调用点静默 no-op——单测环境/宿主未接线语义）；
 * 2. wire 后 offer 返回 true 且回调达（workbench run 侧栏「全部报告」→ 切报告页）；
 * 3. wire(null) 显式断线（AppViewModel 重建语义——旧接线不残留）；
 * 4. clearForTest 复位（测试隔离缝）。
 */
class GoReportHookTest {

    @BeforeTest
    fun setUp() {
        // 每用例清场（object 单例槽跨用例残留防线——AiEnabledSignalTest 同款纪律）
        GoReportHook.clearForTest()
    }

    @AfterTest
    fun tearDown() {
        GoReportHook.clearForTest()
    }

    @Test
    fun `unwired offer returns false and callback is unreachable`() {
        assertFalse(
            GoReportHook.offer(),
            "未接线槽 = 调用点静默 no-op（offer false——GoSettingsHook 同语义）",
        )
    }

    @Test
    fun `wired offer returns true and invokes callback`() {
        GoReportHook.clearForTest()
        var reached = 0
        GoReportHook.wire { reached++ }

        assertTrue(GoReportHook.offer(), "接线后 offer 须达宿主回调（「全部报告」行钮切报告页）")
        assertEquals(1, reached, "回调恰好执行一次")
    }

    @Test
    fun `wire null detaches the previous callback`() {
        GoReportHook.clearForTest()
        var reached = 0
        GoReportHook.wire { reached++ }
        GoReportHook.wire(null)

        assertFalse(GoReportHook.offer(), "wire(null) 显式断线——旧接线不残留")
        assertEquals(0, reached)
    }

    @Test
    fun `clearForTest resets wiring for test isolation`() {
        GoReportHook.wire { }
        GoReportHook.clearForTest()
        assertFalse(GoReportHook.offer(), "clearForTest 复位——跨用例不串扰")
    }
}
