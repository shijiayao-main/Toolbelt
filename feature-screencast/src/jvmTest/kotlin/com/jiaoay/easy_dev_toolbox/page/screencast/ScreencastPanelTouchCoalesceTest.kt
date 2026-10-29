package com.jiaoay.easy_dev_toolbox.page.screencast

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 拖动 MOVE 注入合并守护：合并闸决策语义逐例钉死 + 手势接线源码锚（合并只作用于
 * MOVE 分支，DOWN/UP 语义事件恒旁路——按下/抬起不可丢；守卫失位即回退为逐笔直发，
 * 设备端派发队列重新暴露在鼠标上报率之下（橡皮筋积压复现））。
 *
 * 手势接线源码锚定于 [ScreencastFrameView]（帧渲染 + 手势链共享件，内嵌面板与独立
 * 视口窗共同消费）——面板不再持有手势实现，锚随实现走。
 */
class ScreencastPanelTouchCoalesceTest {

    // ── 合并闸决策语义（[shouldSendCoalescedMove] 纯函数） ─────────────────

    @Test
    fun `first move of a gesture is always sent`() {
        assertTrue(shouldSendCoalescedMove(Long.MIN_VALUE, nowMs = 0L))
        assertTrue(shouldSendCoalescedMove(Long.MIN_VALUE, nowMs = 791_000L))
    }

    @Test
    fun `move within interval window is suppressed`() {
        val sentAt = 1_000L
        assertFalse(shouldSendCoalescedMove(lastSentAtMs = sentAt, nowMs = sentAt))
        assertFalse(shouldSendCoalescedMove(lastSentAtMs = sentAt, nowMs = sentAt + MOVE_COALESCE_INTERVAL_MS - 1))
    }

    @Test
    fun `move at interval boundary is released`() {
        val sentAt = 1_000L
        assertTrue(shouldSendCoalescedMove(lastSentAtMs = sentAt, nowMs = sentAt + MOVE_COALESCE_INTERVAL_MS))
        assertTrue(shouldSendCoalescedMove(lastSentAtMs = sentAt, nowMs = sentAt + 10 * MOVE_COALESCE_INTERVAL_MS))
    }

    @Test
    fun `coalesce window stays at 60Hz grade`() {
        // 窗值 = 注入链路硬约束的档位锚（设备端派发消费上限以下、与设备渲染帧率同档）；
        // 改档须连同本锚与 screencastTouchPointerInput 的合并契约注释一起重审
        assertEquals(16L, MOVE_COALESCE_INTERVAL_MS)
    }

    // ── 手势接线源码锚（合并只作用于 MOVE；DOWN/UP 旁路；锚 = ScreencastFrameView.kt）──

    @Test
    fun `gesture guards only the move branch and keeps down up bypass`() {
        val source = sourceAt(
            "feature-screencast/src/jvmMain/kotlin/com/jiaoay/easy_dev_toolbox/page/screencast/ScreencastFrameView.kt",
        )
        val guard = "if (!shouldSendCoalescedMove(lastMoveSentMs, nowMs)) continue"
        assertTrue(guard in source, "MOVE 分支须先过合并闸再注入（守卫失位 = 橡皮筋积压回潮）")
        assertEquals(
            1,
            Regex("shouldSendCoalescedMove\\(lastMoveSentMs").findAll(source).count(),
            "合并闸只允许作用于手势 MOVE 分支一处（DOWN/UP 不得经闸）",
        )
        val guardAt = source.indexOf(guard)
        val moveAt = source.indexOf("onEvent(ScrcpyControlProtocol.MOTION_ACTION_MOVE, x, y, screenWidth, screenHeight, pointerId)")
        assertTrue(
            guardAt in 0 until moveAt,
            "MOVE 注入须位于合并闸之后（先闸后发）",
        )
        assertTrue(
            "onEvent(ScrcpyControlProtocol.MOTION_ACTION_DOWN, startX, startY, screenWidth, screenHeight, pointerId)" in source,
            "DOWN 注入须无条件在场（按下不可丢）",
        )
        assertTrue(
            "onEvent(ScrcpyControlProtocol.MOTION_ACTION_UP, x, y, screenWidth, screenHeight, pointerId)" in source,
            "UP 注入须无条件在场（抬起不可丢，恒带终位）",
        )
        assertTrue(
            "System.nanoTime() / 1_000_000" in source,
            "合并窗计时须取单调时钟（墙钟回拨不得收紧合并窗）",
        )
    }

    private fun sourceAt(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
            hops++
        }
        val file = File(dir, relativePath)
        assertTrue(file.isFile, "源码锚文件缺失：$relativePath（重命名/移动后须同步本守护）")
        return file.readText()
    }
}
