package com.jiaoay.easy_dev_toolbox.page.screencast

import androidx.compose.ui.geometry.Rect
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 布局子页选中节点高亮共享件守护面：
 * - 坐标换算 [mapScreenRectToView]：设备 px → 视图 px 等比（[mapPointerToScreen] 的
 *   逆）、屏维/视图维非正返回 null 零绘制、边界值（全屏 rect 恰铺满视图）；
 * - 渲染守卫 [screencastLayoutHighlightVisible]：帧侧独立串号守卫——payload 缺席/
 *   serial null/串号失配/非 Streaming 自动无框（旧会话残留结构性不可能）；serial null
 *   防御面与「投屏点亮」注入放行口径有意不对称（持续显示态保守面更严）；
 * - 单例状态件 set/clear 幂等（写入侧组合退出/失配路径统一收口）。
 */
class ScreencastLayoutHighlightTest {

    @AfterTest
    fun tearDown() {
        ScreencastLayoutHighlight.clear()
    }

    private fun frame(
        serial: String? = "S1",
        left: Int = 100,
        top: Int = 200,
        right: Int = 300,
        bottom: Int = 400,
        screenWidth: Int = 1280,
        screenHeight: Int = 720,
    ) = ScreencastLayoutHighlightFrame(
        serial = serial,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
    )

    private fun streaming(serial: String?) =
        ScreencastStatus.Streaming(deviceName = "Demo-1", serial = serial, width = 1280, height = 720)

    @Test
    fun `map screen rect to view scales proportionally`() {
        // 1280×720 屏 → 640×360 视图：两轴同比 0.5，rect 逐边折半
        assertEquals(
            Rect(50f, 100f, 150f, 200f),
            mapScreenRectToView(frame(left = 100, top = 200, right = 300, bottom = 400), 640, 360),
        )
        // 全屏 rect 恰铺满视图（边界值）
        assertEquals(
            Rect(0f, 0f, 640f, 360f),
            mapScreenRectToView(
                frame(left = 0, top = 0, right = 1280, bottom = 720),
                640,
                360,
            ),
        )
        // 非整数比例：浮点等比不取整（描边框位与设备坐标对齐）；非整除比例按容差断言
        // （纵横比一致性由贴合测量先例保证，纯函数只按各轴比例折算）
        val mapped = mapScreenRectToView(frame(), 315, 177)!!
        assertEquals(24.609375f, mapped.left, 1e-4f, "x 轴比例 = 315/1280")
        assertEquals(49.166667f, mapped.top, 1e-4f, "y 轴比例 = 177/720")
        assertEquals(73.828125f, mapped.right, 1e-4f)
        assertEquals(98.333333f, mapped.bottom, 1e-4f)
    }

    @Test
    fun `map screen rect returns null on non positive dimensions`() {
        assertNull(mapScreenRectToView(frame(screenWidth = 0), 640, 360), "屏维非正零绘制")
        assertNull(mapScreenRectToView(frame(screenHeight = -1), 640, 360))
        assertNull(mapScreenRectToView(frame(), 0, 360), "视图维非正零绘制")
        assertNull(mapScreenRectToView(frame(), 640, 0))
    }

    @Test
    fun `highlight visibility guards on session serial`() {
        assertTrue(
            screencastLayoutHighlightVisible(streaming("S1"), frame(serial = "S1")),
            "同串号 Streaming 才画",
        )
        assertFalse(
            screencastLayoutHighlightVisible(streaming("S2"), frame(serial = "S1")),
            "串号失配不画（换机/旧会话残留结构性不可能）",
        )
        assertFalse(
            screencastLayoutHighlightVisible(streaming(null), frame(serial = "S1")),
            "会话串号 null（引擎 API 层可空防御面）不画——与点亮注入放行口径有意不对称",
        )
        assertFalse(
            screencastLayoutHighlightVisible(streaming("S1"), frame(serial = null)),
            "payload serial null = 来源不明不画",
        )
        assertFalse(
            screencastLayoutHighlightVisible(null, frame(serial = "S1")),
            "非 Streaming（无帧会话）不画",
        )
        assertFalse(
            screencastLayoutHighlightVisible(streaming("S1"), null),
            "无 payload 不画",
        )
    }

    @Test
    fun `singleton set and clear are the only state paths`() {
        assertNull(ScreencastLayoutHighlight.state.value, "前置：无高亮")
        ScreencastLayoutHighlight.set(frame())
        assertEquals(frame(), ScreencastLayoutHighlight.state.value, "set 落当前高亮载荷")
        ScreencastLayoutHighlight.clear()
        assertNull(ScreencastLayoutHighlight.state.value)
        ScreencastLayoutHighlight.clear()
        assertNull(ScreencastLayoutHighlight.state.value, "clear 幂等")
    }
}
