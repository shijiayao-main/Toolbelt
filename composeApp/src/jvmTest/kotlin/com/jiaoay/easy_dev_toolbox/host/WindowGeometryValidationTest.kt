package com.jiaoay.easy_dev_toolbox.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 窗口几何校验单源（主窗/投屏独立窗共用）裁决矩阵：非法尺寸回退、屏外防呆回退、
 * 合法值（含负坐标）通过。屏外判定谓词经参注入（不依赖真实屏幕布局，测试跨机确定）。
 */
class WindowGeometryValidationTest {

    @Test
    fun `invalid sizes fall back to computed position`() {
        assertNull(validatedWindowGeometryOrNull(100, 100, 0, 100) { _, _ -> true }, "width=0 = 未记录哨兵形态")
        assertNull(validatedWindowGeometryOrNull(100, 100, -5, 100) { _, _ -> true }, "width<0 = 未记录哨兵")
        assertNull(validatedWindowGeometryOrNull(100, 100, 100, 0) { _, _ -> true }, "height=0 = 未记录哨兵")
        assertNull(validatedWindowGeometryOrNull(100, 100, 100, -1) { _, _ -> true }, "height<0 = 未记录哨兵")
    }

    @Test
    fun `offscreen point falls back to computed position`() {
        assertNull(
            validatedWindowGeometryOrNull(99999, 99999, 450, 800, pointOnScreen = { _, _ -> false }),
            "左上角点不在任一可见屏幕内 → 回退计算位（防显示器拔除后窗口屏外）",
        )
    }

    @Test
    fun `valid geometry passes through with negative coordinates`() {
        assertEquals(
            SavedWindowGeometry(-1920, 0, 450, 800),
            validatedWindowGeometryOrNull(-1920, 0, 450, 800) { _, _ -> true },
            "负坐标（副屏在主屏左侧）不被符号判断误杀",
        )
        assertEquals(
            SavedWindowGeometry(100, 200, 450, 800),
            validatedWindowGeometryOrNull(100, 200, 450, 800) { _, _ -> true },
        )
    }

    @Test
    fun `size validation precedes screen probe`() {
        // 非法尺寸短路先于屏外判定——谓词未被触达即回退（哨兵 -1 无屏外语义可言）
        var probeInvoked = false
        assertNull(validatedWindowGeometryOrNull(100, 100, -1, 100) { _, _ -> probeInvoked = true; true })
        assertFalse(probeInvoked, "非法尺寸短路，屏外谓词不应被调用")
    }

    @Test
    fun `default screen probe is total and fails closed when headless`() {
        val probeResult = runCatching { isPointOnAnyScreen(0, 0) }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            assertEquals(false, probeResult.getOrNull(), "headless 测试 JVM 下防呆 fail-closed 回 false（几何视为非法回退计算位）")
        } else {
            assertTrue(probeResult.isSuccess, "有屏环境探测不抛（按真实屏幕 bounds 判定）")
        }
    }
}
