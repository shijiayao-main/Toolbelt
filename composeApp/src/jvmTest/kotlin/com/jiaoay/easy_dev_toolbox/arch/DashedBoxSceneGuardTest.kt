package com.jiaoay.easy_dev_toolbox.arch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.ThemeMode
import com.jiaoay.easy_dev_toolbox.widget.decor.appDashedBorder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * DashedBox 虚线降级盒场景验证：**ImageComposeScene 零显示器渲染**
 * （[SceneGuardSupport] 配方单源，themeMode 参数化承载双态循环）验证
 * `Modifier.appDashedBorder` 1dp 虚线在**深浅两态均可见（AA 对比）**。
 *
 * 验收口径（appDashedBorder 渲染 1dp 虚线，color 默认 borderStrong）：
 * - 虚线段线渲染为描边带（borderStrong 亮 #1C1B1F 系 / 暗 #D7DAE3 系——onSurface 直用）；
 * - 亮态深描边对浅背景、暗态浅描边对深背景，行带检测阈值（≥25 RGB 曼哈顿差）即 AA 可见判据；
 * - 两态都必须检出——单态可见说明明暗分治配方断裂。
 *
 * 探针：Box(height 40dp) 挂 appDashedBorder，内容 Text（带内可见性由行带聚类承载）。
 */
class DashedBoxSceneGuardTest {

    @Test
    fun `dashed border is visible in light mode`() {
        val pixels = renderPixels(SCENE_W, SCENE_H, ThemeMode.Light) { dashedProbeContent() }
        val bands = contentRows(pixels, x1 = SCENE_W)
        assertTrue(
            bands.size >= 2,
            "亮态应检出 ≥2 虚线描边带（40dp 盒上下两缘；borderStrong 亮档对浅背景 AA 可见）" +
                "——实际 ${bands.size} 带：虚线未渲染或对比度低于阈（DL-M20 深描边配方断裂）",
        )
    }

    @Test
    fun `dashed border is visible in dark mode`() {
        val pixels = renderPixels(SCENE_W, SCENE_H, ThemeMode.Dark) { dashedProbeContent() }
        val bands = contentRows(pixels, x1 = SCENE_W, bg = SCENE_DARK_BG)
        assertTrue(
            bands.size >= 2,
            "暗态应检出 ≥2 虚线描边带（borderStrong 暗档 #D7DAE3 系对深背景 AA 可见）" +
                "——实际 ${bands.size} 带：虚线未渲染或对比度低于阈（明暗分治断裂或暗档误洗 alpha）",
        )
    }

    /**
     * 探针：满宽 40dp 高 Box 挂 appDashedBorder（默认参——color=borderStrong/shape=medium/1dp）。
     * 🔴 无文字：行带只可能来自虚线描边——带文字时文字带会背书假绿（虚线失效也检出 ≥1 带）。
     * （探针实证：纯虚线双态各检出 2 带 = 上下描边行。）
     */
    @Composable
    private fun dashedProbeContent() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .appDashedBorder(),
        )
    }

    private companion object {
        /** 探针场景视口（单盒窄景，行带检测窗口即场景宽） */
        const val SCENE_W = 200
        const val SCENE_H = 120
    }
}
