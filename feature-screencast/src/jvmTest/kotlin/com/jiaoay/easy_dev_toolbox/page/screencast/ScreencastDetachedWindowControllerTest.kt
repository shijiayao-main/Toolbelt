package com.jiaoay.easy_dev_toolbox.page.screencast

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 独立视口窗控制器状态机单测（三分支 / 同帧回绕 / 死窗 recall / 挂载间隙 / 组合销毁
 * 回写 / 窗关即停投外呼）：句柄全 fake（java.awt.Window headless 不可构造——句柄抽象面
 * 的存在理由）。@AfterTest 复位 visible/windowHandle/focusPending/onSessionEnd（先清
 * 外呼再 recall——复位路径不得驱动上一用例注册的 fake 外呼）。
 */
class ScreencastDetachedWindowControllerTest {

    /** 句柄 fake：showing 可切换（死窗/活窗两态），focusAndRaise 计数供断言 */
    private class FakeHandle(showing: Boolean = true) : ScreencastDetachedWindowHandle {
        var showing: Boolean = showing
        var focusCount = 0
        override val isShowing: Boolean get() = showing
        override fun focusAndRaise() {
            focusCount++
        }
    }

    @AfterTest
    fun resetController() {
        ScreencastDetachedWindowController.onSessionEnd = null
        ScreencastDetachedWindowController.recall()
        ScreencastDetachedWindowController.windowHandle = null
    }

    // ── 三分支 ────────────────────────────────────────────────────────────

    @Test
    fun `branch1 closed to open sets visible without focus`() {
        assertFalse(ScreencastDetachedWindowController.visible)
        ScreencastDetachedWindowController.openOrFocus()
        assertTrue(ScreencastDetachedWindowController.visible, "分支 1：未开 = 置 visible=true")
    }

    @Test
    fun `branch2 open with live window focuses instead of reopening`() {
        val handle = FakeHandle(showing = true)
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.windowHandle = handle
        ScreencastDetachedWindowController.openOrFocus()
        assertTrue(ScreencastDetachedWindowController.visible, "分支 2：聚焦不重开（防重窗）")
        assertEquals(1, handle.focusCount, "分支 2：恢复聚焦恰一次")
    }

    @Test
    fun `branch3a mount gap click records pending focus consumed on attach`() {
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.windowHandle = null
        ScreencastDetachedWindowController.openOrFocus()
        // 挂载间隙（visible 真而句柄未回填）——不红不炸，等挂载消费
        val handle = FakeHandle()
        ScreencastDetachedWindowController.onWindowAttached(handle)
        assertEquals(1, handle.focusCount, "focusPending 恰在挂载时消费一次")
        // 重复 attach（同窗重复挂载形态）不重复聚焦
        ScreencastDetachedWindowController.onWindowAttached(FakeHandle())
        assertEquals(1, handle.focusCount)
    }

    @Test
    fun `branch3b dead window click recalls to restabilize then reopens`() {
        val deadHandle = FakeHandle(showing = false)
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.windowHandle = deadHandle
        ScreencastDetachedWindowController.openOrFocus()
        assertFalse(
            ScreencastDetachedWindowController.visible,
            "分支 3b：死窗（句柄在而 !isShowing）= recall 重稳，不变式恢复",
        )
        ScreencastDetachedWindowController.openOrFocus()
        assertTrue(ScreencastDetachedWindowController.visible, "死窗重稳后再点 = 重开（不锁死）")
        assertEquals(0, deadHandle.focusCount, "死窗聚焦退化为关悬挂态，不对死窗发聚焦")
    }

    // ── 同帧回绕（last-write-wins） ──────────────────────────────────────

    @Test
    fun `same frame close then open is last write wins open`() {
        ScreencastDetachedWindowController.openOrFocus()
        ScreencastDetachedWindowController.recall()
        ScreencastDetachedWindowController.openOrFocus()
        assertTrue(ScreencastDetachedWindowController.visible, "同帧 close→open：last-write-wins=开")
        ScreencastDetachedWindowController.recall()
        assertFalse(ScreencastDetachedWindowController.visible, "同帧 open→close：last-write-wins=关")
    }

    // ── 组合销毁回写（isShowing 兜底） ────────────────────────────────────

    @Test
    fun `dispose backstop writes visible back to false and clears handle`() {
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.onWindowAttached(FakeHandle())
        assertTrue(ScreencastDetachedWindowController.visible)
        ScreencastDetachedWindowController.onWindowDisposed()
        assertFalse(ScreencastDetachedWindowController.visible, "组合销毁兜底回写（onDispose 路径）")
        assertNull(ScreencastDetachedWindowController.windowHandle)
    }

    @Test
    fun `dispose after recall already applied is idempotent`() {
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.onWindowAttached(FakeHandle())
        ScreencastDetachedWindowController.recall()
        assertFalse(ScreencastDetachedWindowController.visible)
        ScreencastDetachedWindowController.onWindowDisposed()
        assertFalse(ScreencastDetachedWindowController.visible, "recall 已应用后 dispose 兜底幂等")
        assertNull(ScreencastDetachedWindowController.windowHandle)
    }

    @Test
    fun `attach overwrites stale dead handle and later open uses new handle`() {
        val deadHandle = FakeHandle(showing = false)
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.windowHandle = deadHandle
        // 死窗重稳 → 重开 → 新窗挂载：陈旧死句柄被新句柄覆盖，openOrFocus 恒看新句柄
        ScreencastDetachedWindowController.openOrFocus()
        assertFalse(ScreencastDetachedWindowController.visible)
        ScreencastDetachedWindowController.openOrFocus()
        assertTrue(ScreencastDetachedWindowController.visible)
        val freshHandle = FakeHandle()
        ScreencastDetachedWindowController.onWindowAttached(freshHandle)
        ScreencastDetachedWindowController.openOrFocus()
        assertEquals(1, freshHandle.focusCount, "聚焦落在覆盖后的新句柄上")
    }

    // ── 窗关即停投（recall 汇点统一外呼） ────────────────────────────────

    @Test
    fun `recall invokes registered session end route`() {
        var stopCalls = 0
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.onSessionEnd = { stopCalls++ }
        ScreencastDetachedWindowController.recall()
        assertEquals(1, stopCalls, "recall 汇点须统一停投（窗关即停投——所有用户可见关窗路径汇于此）")
        assertFalse(ScreencastDetachedWindowController.visible, "recall 收窗语义不变（visible=false）")
    }

    @Test
    fun `recall without registered route only closes window`() {
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.recall()
        assertFalse(
            ScreencastDetachedWindowController.visible,
            "无注册外呼（窗不在组合中）recall 只收窗——null 路由不炸不误触",
        )
    }

    @Test
    fun `dead window restabilize also ends session`() {
        var stopCalls = 0
        val deadHandle = FakeHandle(showing = false)
        ScreencastDetachedWindowController.visible = true
        ScreencastDetachedWindowController.windowHandle = deadHandle
        ScreencastDetachedWindowController.onSessionEnd = { stopCalls++ }
        ScreencastDetachedWindowController.openOrFocus()
        assertEquals(1, stopCalls, "死窗重稳走 recall 汇点——用户可见关窗路径一律停投（窗关即停投）")
        assertFalse(ScreencastDetachedWindowController.visible, "死窗重稳收窗语义不变")
    }
}
