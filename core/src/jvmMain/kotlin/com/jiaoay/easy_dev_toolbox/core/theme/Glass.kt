package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 玻璃材质色彩 slot：浮层档（sheet/thin）玻璃 tint + 四档实底 + aurora 环境光 +
 * rim/border 高光线，从 [ColorScheme] 现场派生（零硬编码新色——换预设/seed 玻璃色温自动跟随）。
 * 消费方经 [LocalToolboxGlassColors] 读取，不直接构造；未装配显式炸（fail fast）。
 *
 * 配方语义 = rgba 实底预混直取：玻璃四档 rgba 的 rgb 部分就是中性面档位本身，
 * 走 `scheme.surfaceContainer*` 取源（随 seed 派生），禁 lerp primary 染色回潮。
 * chrome/panel 档恒实底（blur 只属于 sheet/thin 浮层）；恒紫 aiAccent / 日志四色 /
 * consoleBg（语义面深底——暗态骨架派生单槽覆写见 AppTheme，属语义层接线非玻璃染色）
 * 不参与任何玻璃与 aurora 染色。
 * 材质配方/档位归属/投影语义/取源细节见 docs/core/theme.md「玻璃材质 token」节。
 */
@Immutable
data class ToolboxGlassColors(
    /** sheet 档 tint（命令面板/导览卡厚板——浮层真玻璃） */
    val tintSheet: Color,
    /** thin 档 tint（toast——surfaceContainerLowest 中性面档位，明暗分治；取源见上方材质配方节） */
    val tintThin: Color,
    /**
     * 实底档 ×4（opaqueSlotChrome/Panel/Sheet/Thin）：alpha=1——
     * chrome/panel 档是材质诚实化后的**主路径**（dock/tab 条/状态栏/inspector
     * 恒实底——blur 无从表达）；sheet/thin 档是浮层玻璃不可用环境的兜底。
     * （取源：chrome ← surfaceContainerLowest / panel ← surfaceContainerLow——chrome(.82)/
     * panel(.90) 两档浓度分立，实现侧经取源分档对齐。）
     */
    val opaqueSlotChrome: Color,
    val opaqueSlotPanel: Color,
    val opaqueSlotSheet: Color,
    val opaqueSlotThin: Color,
    /** 受光缘高光线（顶缘 1px 发丝内边线——唯一高光语言） */
    val rimHighlight: Color,
    /** 外发丝描边 */
    val borderGlass: Color,
    /** aurora 主光斑色（primary 系——只属于内容区，chrome 带经遮罩裁剪） */
    val auroraPrimary: Color,
    /** aurora 副光斑色（tertiary 系） */
    val auroraTertiary: Color,
)

/** 玻璃材质 slot 的 CompositionLocal（AppTheme 装配；缺装配显式炸——fail fast） */
val LocalToolboxGlassColors = staticCompositionLocalOf<ToolboxGlassColors> {
    error("LocalToolboxGlassColors 未装配——必须在 AppTheme 内 provides")
}

/**
 * 从给定 [ColorScheme] 派生玻璃材质 slot。
 *
 * 🔴 显式传参纪律（[toolboxSurfaceColors] 的 S2.0 修复同款防御）：dark 由调用方
 * （AppTheme）显式传入——本函数禁止 @Composable 无参读 MaterialTheme.colorScheme，
 * 否则在 MaterialTheme 装配前求值会读到 material3 baseline 默认浅色 scheme（11 slot
 * 恒浅色的病态先例）。求值位置不敏感靠传参钉死。
 *
 * slot 取源（rgb 部分即中性面档位；档位表见 docs/core/theme.md「玻璃材质 token」节）：
 * - chrome ← surfaceContainerLowest（亮基准 #FCF8F9）；
 * - panel ← surfaceContainerLow；
 * - sheet/thin ← surface 档（亮 #FCF8F9 与 chrome 同档——
 *   修正：勿取 surfaceContainerHigh/Highest（那是 --surface-deep/
 *   --surface-highest 档，ΔL≈5-6 偏深），rgba(252,248,249,…) 的 rgb 就是 --surface 档；
 *   暗档同理。sheet/thin 的玻璃层次
 *   差异由 alpha（.92/.94）与 blur 半径承担，非底色档位——chrome 配方同款直取）。
 */
fun toolboxGlassColors(scheme: ColorScheme, dark: Boolean): ToolboxGlassColors {
    val rim = if (dark) AppAlpha.glassRimDark else AppAlpha.glassRimLight
    val border = if (dark) AppAlpha.glassBorderDark else AppAlpha.glassBorderLight

    // 预混实底直取：玻璃 rgba 的 rgb 部分即中性面档位——
    // scheme.surfaceContainer* 派生体系自带 seed 色温（保留「换主题玻璃跟随」），
    // lerp primary stain 染色整条移除（紫染 + ΔL≈10 档 + 两档浓度坍缩的定罪）。
    // sheet/thin ← surface 档（sheet/thin tint 的 rgb =
    // --surface #FCF8F9，与 chrome 同档；层次差异由 alpha/.94 与 blur 承担，非底色档位）。
    val chromeSlot = scheme.surfaceContainerLowest
    val panelSlot = scheme.surfaceContainerLow
    val sheetSlot = scheme.surfaceContainerLowest
    val thinSlot = scheme.surfaceContainerLowest
    val tintSheet = if (dark) {
        sheetSlot.copy(alpha = AppAlpha.glassTintSheetDark)
    } else {
        sheetSlot.copy(alpha = AppAlpha.glassTintSheetLight)
    }
    val tintThin = thinSlot.copy(
        alpha = if (dark) AppAlpha.glassTintThinDark else AppAlpha.glassTintThinLight,
    )

    // 受光缘高光：恒白 alpha 分档（rim 四档）；外发丝描边 = onSurface×alpha
    // （--glass-brd rgba(28,27,31,.08)=onSurface 系——「chromeSlot 底色系定缘」
    // 走染紫槽与本配方不符）
    val rimHighlight = Color.White.copy(alpha = rim)
    val borderGlass = scheme.onSurface.copy(alpha = border)

    // aurora：主斑 ← surfaceTint（M3 基线 tint=primary，非覆写套与 primary 直取逐位
    // 同源；涂装套以 tint 直配锚定光斑色——电光紫 昼表 #8A2BF0 饱和紫，primary 近白
    // 反白面 ×α 峰值近似不可感，光斑锚位让给染色槽）；次斑 ← secondary（声部色——
    // 电光紫 荧光绿 #09F955 两态同值直配，青绿域直译）。峰值 α 明暗分档。
    val auroraPrimary = scheme.surfaceTint.copy(
        alpha = if (dark) AppAlpha.auroraPrimaryDark else AppAlpha.auroraPrimaryLight,
    )
    val auroraTertiary = scheme.secondary.copy(
        alpha = if (dark) AppAlpha.auroraTertiaryDark else AppAlpha.auroraTertiaryLight,
    )

    return ToolboxGlassColors(
        tintSheet = tintSheet,
        tintThin = tintThin,
        // 实底档：预混直取 alpha=1（rgba 实底预混语义，档位表见 docs/core/theme.md「玻璃材质 token」节）
        opaqueSlotChrome = chromeSlot.copy(alpha = 1f),
        opaqueSlotPanel = panelSlot.copy(alpha = 1f),
        opaqueSlotSheet = sheetSlot.copy(alpha = 1f),
        opaqueSlotThin = thinSlot.copy(alpha = 1f),
        rimHighlight = rimHighlight,
        borderGlass = borderGlass,
        auroraPrimary = auroraPrimary,
        auroraTertiary = auroraTertiary,
    )
}

/**
 * 明暗主题标记（GlassMaterials 材质 grading 分档消费——chromaMultiplier 暗 1.45/亮 1.15）。
 * AppTheme 恒 provides；缺装配 fail fast。
 */
val LocalIsDarkTheme = staticCompositionLocalOf<Boolean> {
    error("LocalIsDarkTheme 未装配——必须在 AppTheme 内 provides")
}

/**
 * 页面骨架的玻璃带让位：PageHome 根 provides 窗级四边让位
 * （tab 条/dock/状态栏三条玻璃带的占位），
 * [com.jiaoay.easy_dev_toolbox.widget.PageScaffold] 消费并入页面内边距。
 *
 * 让位的 CSS 语义 = padding 在滚动容器**内**（内容滚动时
 * 穿过 padding 区滑进玻璃带被 blur 折射）；Compose 侧让位必须经滚动 contentPadding
 * 而非包裹层 padding——包裹层硬隔离会让内容永不进玻璃带。
 *
 * 默认全零（未装配 = 无玻璃带让位，安全降级——预览/测试环境零玻璃语境不炸）。
 */
val LocalScaffoldGlassInsets = staticCompositionLocalOf<PaddingValues> { PaddingValues(0.dp) }

/** [PaddingValues] 逐边相加（PageScaffold 合并密度边距与玻璃带让位用） */
operator fun PaddingValues.plus(other: PaddingValues): PaddingValues = PaddingValues(
    start = calculateStartPadding(LayoutDirection.Ltr) + other.calculateStartPadding(LayoutDirection.Ltr),
    top = calculateTopPadding() + other.calculateTopPadding(),
    end = calculateEndPadding(LayoutDirection.Ltr) + other.calculateEndPadding(LayoutDirection.Ltr),
    bottom = calculateBottomPadding() + other.calculateBottomPadding(),
)
