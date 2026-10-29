package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AppShapes token 常量断言（design D2 / D7）：取值被误改即测试失败，
 * 作为圆角值的视觉回归防线。B3（2026-09-18）起 small 为 6dp（密度收敛），
 * medium/large 维持 12/16dp。
 */
class AppShapesTest {

    @Test
    fun `small shape equals 6dp rounded corners`() {
        assertEquals(RoundedCornerShape(6.dp), AppShapes.small)
    }

    @Test
    fun `check micro shape equals 5dp rounded corners`() {
        // DS-06 · 重设计批 0：勾选框微档（裁决 5 / §4-11）——只用于勾选框，不挪作他档
        assertEquals(RoundedCornerShape(5.dp), AppShapes.check)
    }

    @Test
    fun `badge micro shape equals 4dp rounded corners`() {
        // DS-06 · 重设计批 0：风险徽标微档
        assertEquals(RoundedCornerShape(4.dp), AppShapes.badge)
    }

    @Test
    fun `medium shape equals 12dp rounded corners`() {
        assertEquals(RoundedCornerShape(12.dp), AppShapes.medium)
    }

    @Test
    fun `large shape equals 16dp rounded corners`() {
        assertEquals(RoundedCornerShape(16.dp), AppShapes.large)
    }

    // ── 2026-09-23 液态玻璃批 1：玻璃圆角层级（HTML 预览稿 v10 直译；胶囊只留给小件） ──

    @Test
    fun `glass shape tiers match preview baseline`() {
        assertEquals(RoundedCornerShape(16.dp), AppShapes.glassPanel, "玻璃大件 16dp（dock/inspector 浮动胶囊）")
        assertEquals(RoundedCornerShape(20.dp), AppShapes.glassSheet, "浮层玻璃 20dp（palette/导览卡 iOS sheet 档）")
        assertEquals(RoundedCornerShape(12.dp), AppShapes.glassBar, "chrome 长条 12dp（大圆角矩形非全圆——「香肠感」裁定）")
        assertEquals(RoundedCornerShape(50), AppShapes.capsule, "胶囊 percent 全圆（仅限选中 pill/chip/toast 小件）")
    }
}
