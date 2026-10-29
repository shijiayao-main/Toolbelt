package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 玻璃 slot 派生回归钉。
 *
 * 🔴 配方契约（重设计稿 tokens.css §2.7 对拍）：玻璃是「中性面档位预混直取」而非
 * lerp 染色——稿面玻璃四档 rgba 的 rgb 部分**就是**中性面档位（chrome-tint rgb
 * #FCF8F9=--surface、panel-tint rgb #F4EFF5=--surface-raised），实现走
 * scheme.surfaceContainer* 取源随 seed 派生自动带主题色温，lerp stain 不允许回潮。
 * 本测试钉死：
 * 1. thin 档取源 = surfaceContainerHighest（非 inverseSurface）；
 * 2. chrome/panel/sheet/thin 四档取源分立（稿面 chrome/panel 两档浓度不同，
 *    实现侧取源分立对齐）；
 * 3. 浮层两档 tint 明暗分档（dark < light）+ 精确 alpha；
 * 4. opaque 实底档 alpha == 1f 且 RGB = 对应中性面档位（非 lerp 染色结果）；
 * 5. rim/border/aurora 派生（rim 四档、border onSurface 系）。
 */
class GlassColorTest {

    private companion object {
        val SEED = Color(0xFF3B5BA5)
    }

    // ── 1 · sheet/thin 取源钉死（稿面 sheet/thin tint rgb = --surface
    //       档 #FCF8F9 与 chrome 同档——层次差异由 alpha/blur 承担非底色档位） ──

    @Test
    fun `sheet and thin slots derive from surface tier not inverseSurface`() {
        // 构造 inverseSurface 与 surfaceContainerLowest 可分辨的 scheme
        val scheme = lightColorScheme(
            primary = SEED,
            inverseSurface = Color.Red,
            surfaceContainerLowest = Color.Blue,
        )
        val glass = toolboxGlassColors(scheme, dark = false)
        // sheet/thin tint 的 RGB 必须来自 Blue 系（surface 档=Lowest），绝不能是 Red（inverse）
        assertEquals(Color.Blue.red, glass.tintSheet.red, 0.0001f, "sheet tint R 分量须来自 surface 档（surfaceContainerLowest）派生")
        assertEquals(Color.Blue.blue, glass.tintThin.blue, 0.0001f, "thin tint B 分量须来自 surface 档（surfaceContainerLowest）派生")
        assertTrue(
            glass.tintThin.red != Color.Red.red,
            "thin 档不得取 inverseSurface（真机「深色圆角矩形」翻车点）",
        )
    }

    // ── 2 · 四档取源分立（稿面 chrome/panel 浓度不同档）──

    @Test
    fun `opaque slots source from distinct surface tiers`() {
        val scheme = lightColorScheme(
            primary = SEED,
            surfaceContainerLowest = Color(0xFF111111),
            surfaceContainerLow = Color(0xFF222222),
            surfaceContainerHigh = Color(0xFF333333),
            surfaceContainerHighest = Color(0xFF444444),
        )
        val glass = toolboxGlassColors(scheme, dark = false)

        // 稿面 rgb=中性面档位直取：chrome/sheet/thin=--surface 系（Lowest——
        // 稿面 sheet/thin tint rgb=#FCF8F9 即 --surface）/ panel=--surface-raised 系（Low）
        // ——RGB 各自等于取源档位（lerp stain 已移除，零 primary 染）
        assertEquals(Color(0xFF111111), glass.opaqueSlotChrome, "chrome 实底 = --surface 系（surfaceContainerLowest 直取）")
        assertEquals(Color(0xFF222222), glass.opaqueSlotPanel, "panel 实底 = --surface-raised 系（surfaceContainerLow 直取）")
        assertEquals(Color(0xFF111111), glass.opaqueSlotSheet, "sheet 实底 = --surface 系（与 chrome 同档）")
        assertEquals(Color(0xFF111111), glass.opaqueSlotThin, "thin 实底 = --surface 系（与 chrome 同档）")

        // 🔴 零 primary 染（lerp 紫染会让玻璃偏离中性面档位 ΔL≈10 档）
        assertEquals(1f, glass.opaqueSlotChrome.alpha, "chrome 实底全不透明")
    }

    // ── 3 · 浮层两档 tint 明暗分治（dark < light）+ 精确 alpha ──

    @Test
    fun `float tier tints follow dark-transparent light-opaque split`() {
        val scheme = lightColorScheme(primary = SEED)
        val light = toolboxGlassColors(scheme, dark = false)
        val dark = toolboxGlassColors(darkColorScheme(primary = SEED), dark = true)

        // alpha 分档（经色彩空间 8bit 量化往返，1/255≈0.004——容差半格）
        assertEquals(AppAlpha.glassTintSheetDark, dark.tintSheet.alpha, 0.002f)
        assertEquals(AppAlpha.glassTintSheetLight, light.tintSheet.alpha, 0.002f)
        assertEquals(AppAlpha.glassTintThinDark, dark.tintThin.alpha, 0.002f, "thin 暗档 0.85")
        assertEquals(AppAlpha.glassTintThinLight, light.tintThin.alpha, 0.002f, "thin 亮档 0.94")

        // 明暗分治方向：同档亮色更不透明
        assertTrue(light.tintSheet.alpha > dark.tintSheet.alpha)
        assertTrue(light.tintThin.alpha > dark.tintThin.alpha)
    }

    // ── 4 · rim/border/aurora 派生（rim 四档 + border onSurface 系）──

    @Test
    fun `rim border aurora derivation matches spec`() {
        val scheme = lightColorScheme(primary = SEED, onSurface = Color(0xFF1C1B1F))
        val glass = toolboxGlassColors(scheme, dark = false)

        assertEquals(AppAlpha.glassRimLight, glass.rimHighlight.alpha, 0.002f, "rim = 恒白 alpha 分档（稿 .50）")
        // borderGlass 色源 = onSurface 系（稿 --glass-brd rgba(28,27,31,.08)——onSurface 直系，
        // 不走染紫槽）
        assertEquals(Color(0xFF1C1B1F).red, glass.borderGlass.red, 0.0001f, "border 定缘色源 = onSurface 系")
        assertEquals(AppAlpha.glassBorderLight, glass.borderGlass.alpha, 0.005f)
        // aurora 主斑 ← surfaceTint（M3 基线 tint=primary，非覆写套与 primary 直取逐位
        // 同源；涂装套以 tint 直配锚定光斑色）；次斑 ← secondary（声部色）——
        // 光斑锚位换源后峰值 α 分档不变
        assertEquals(AppAlpha.auroraPrimaryLight, glass.auroraPrimary.alpha, 0.005f, "aurora 主光斑 = surfaceTint 直取×峰值")
        assertEquals(scheme.surfaceTint.red, glass.auroraPrimary.red, 0.0001f, "aurora 主光斑色源 = surfaceTint 槽")
        assertEquals(scheme.secondary.red, glass.auroraTertiary.red, 0.0001f, "aurora 副光斑色源 = secondary 槽")
        // 涂装套锚位（surfaceTint 套级直配 ≠ primary 时换源才显形——电光紫 昼表 #8A2BF0 饱和紫）
        val painted = lightColorScheme(
            primary = SEED,
            surfaceTint = Color(0xFF8A2BF0),
            secondary = Color(0xFF09F955),
        )
        val paintedGlass = toolboxGlassColors(painted, dark = false)
        assertEquals(Color(0xFF8A2BF0), paintedGlass.auroraPrimary.copy(alpha = 1f), "涂装套 aurora 主斑 = surfaceTint 直配值")
        assertEquals(Color(0xFF09F955), paintedGlass.auroraTertiary.copy(alpha = 1f), "涂装套 aurora 副斑 = secondary 直配值")
    }
}
