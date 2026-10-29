package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 表面语义 slot 派生回归钉：
 * [ToolboxSurfaceColors.borderStrong] 深描边槽是 DashedBox 虚线/勾选框的描边源
 * （design-language「色取 border-strong」），派生配方被误改（洗 alpha/换源）即虚线
 * 降级语义失真——本测试钉死明暗两态派生值。
 *
 * 配方口径（design-language §2.3 `--border-strong`）：**明暗均 onSurface 直用、
 * 不洗 alpha**（亮 #1C1B1F / 暗 #D7DAE3 即各自 onSurface 基准色）——区别于
 * borderHairline 的 0.08/0.10 低 alpha 发丝档（「有边无形」）与 borderEmphasis
 * （outline 焦点档）。
 */
class SurfaceColorsTest {

    private companion object {
        /** 可分辨探针：onSurface 用高区分度色，洗 alpha/换源即现形 */
        val LIGHT_SEED = Color(0xFF123456)
        val DARK_SEED = Color(0xFF654321)
    }

    @Test
    fun `border strong derives from onSurface directly in both modes`() {
        val lightScheme = lightColorScheme(
            primary = LIGHT_SEED,
            onSurface = Color(0xFF1C1B1F), // tokens.css --border-strong 亮档基准色
        )
        val darkScheme = darkColorScheme(
            primary = DARK_SEED,
            onSurface = Color(0xFFD7DAE3), // tokens.css --border-strong 暗档基准色
        )

        val light = toolboxSurfaceColors(lightScheme, dark = false)
        val dark = toolboxSurfaceColors(darkScheme, dark = true)

        // 明暗均 onSurface 直用：RGB 全等 + alpha = 1（不洗 alpha——洗了即「有边无形」发丝化，
        // DashedBox 降级态将失去「实色深描边」语义）
        assertEquals(Color(0xFF1C1B1F), light.borderStrong, "亮档 borderStrong 必须 onSurface 直用（--border-strong #1C1B1F）")
        assertEquals(1f, light.borderStrong.alpha, "亮档 borderStrong 不得洗 alpha（深描边是实色档）")
        assertEquals(Color(0xFFD7DAE3), dark.borderStrong, "暗档 borderStrong 必须 onSurface 直用（--border-strong #D7DAE3）")
        assertEquals(1f, dark.borderStrong.alpha, "暗档 borderStrong 不得洗 alpha（深描边是实色档）")
    }

    @Test
    fun `border strong stays distinct from hairline and emphasis tiers`() {
        // 分档防归并：strong（实色）/hairline（低 alpha 叠加）/emphasis（outline 焦点档）
        // 三槽各自独立——归并即描边语言塌档
        val scheme = lightColorScheme(
            primary = LIGHT_SEED,
            onSurface = Color(0xFF1C1B1F),
            outline = Color(0xFF000000),
            outlineVariant = Color(0xFF00FF00),
        )
        val surface = toolboxSurfaceColors(scheme, dark = false)
        assertEquals(scheme.onSurface, surface.borderStrong, "strong 色源 = onSurface（非 outline/outlineVariant）")
        assertEquals(
            scheme.onSurface.copy(alpha = AppAlpha.borderHairlineLight),
            surface.borderHairline,
            "hairline 仍是 onSurface×borderHairlineLight 发丝档（不得顺手改 hairline 配方）",
        )
        assertEquals(scheme.outline, surface.borderEmphasis, "emphasis 仍是 outline 焦点档")
    }

    @Test
    fun `border emphasis rescues to primary when outline is a decorative seam`() {
        // 亮线救援规则：outline 对页面底/卡域承载面（Low/Lowest）任一不足图形线 3.0
        // （装饰缝——夜装甲缝对底 1.73、昼近黑缝对拉深卡梯 2.75）时 borderEmphasis 升
        // primary 焦点档（M3 焦点语言）——cr<3.0 的缝不得承担图形可辨职责（focus 指示
        // 硬职责）；outline 全承载面达线套 = outline 直取逐位不变（救援不得误伤）。
        val nightSeam = darkColorScheme(
            primary = Color(0xFFEDE6FF),
            background = Color(0xFF5940A6),
            surfaceContainerLow = Color(0xFF6C22D8),
            surfaceContainerLowest = Color(0xFF0B0B0B),
            outline = Color(0xFF361B6A), // 装甲缝深缝域：对底 1.73 < 3.0
        )
        assertEquals(
            Color(0xFFEDE6FF), toolboxSurfaceColors(nightSeam, dark = true).borderEmphasis,
            "夜缝对底不足图形线时 borderEmphasis 必须升 primary 焦点档（装饰缝不承担 focus 线）",
        )
        val daySeam = lightColorScheme(
            primary = Color(0xFFEDE6FF),
            background = Color(0xFF9946FA),
            surfaceContainerLow = Color(0xFF6C22D8),
            surfaceContainerLowest = Color(0xFF3D1E7E),
            outline = Color(0xFF0D0218), // 昼近黑缝：对拉深卡梯 2.75 < 3.0
        )
        assertEquals(
            Color(0xFFEDE6FF), toolboxSurfaceColors(daySeam, dark = false).borderEmphasis,
            "昼缝对拉深卡梯不足图形线时 borderEmphasis 必须升 primary 焦点档",
        )
        val normalScheme = darkColorScheme(
            primary = Color(0xFFEDE6FF),
            onSurface = Color(0xFFD7DAE3),
            background = Color(0xFF131319),
            surfaceContainerLow = Color(0xFF1E1E26),
            surfaceContainerLowest = Color(0xFF0D0D12),
            outline = Color(0xFFB3A8D9), // 常规亮 outline：对暗底/卡域全 ≥3.0
        )
        assertEquals(
            normalScheme.outline, toolboxSurfaceColors(normalScheme, dark = true).borderEmphasis,
            "outline 全承载面达线的套 emphasis 直取逐位不变（救援不得误伤）",
        )
    }
}
