package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct

/**
 * 应用主题入口：按 [AppThemeSpec] 动态生成配色、按 [ThemeMode] 决定深浅，
 * 统一装配 MaterialTheme 与语义扩展色 Local。
 *
 * 窗口级背景统一在此装配：Surface 以 colorScheme.background 铺满内容根，
 * 深浅主题随 colorScheme.background 联动；各页面组件不再自行铺页面底色。
 *
 * @param windowShape 窗口根裁剪形状（edge-to-edge：undecorated+transparent 透明圆角窗，
 *   传 AppShapes.glassPanel(16dp) = 圆角窗 + 1dp 定缘；最大化时
 *   调用方传 null 回正（满屏无圆角无定缘）；null = 不裁剪（默认，兼容无窗宿主）
 */
@Composable
fun AppTheme(
    scheme: AppThemeSpec,
    themeMode: ThemeMode,
    windowShape: Shape? = null,
    content: @Composable () -> Unit,
) {
    val dark: Boolean = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // 整套派生色调在 scheme/dark 未变时复用：colorSet 走 HCT 调色板解算（不便宜），
    // 而 AppTheme 在每次根重组（切页 / 窗显隐 / 初始化态变化）都会重新求值。
    // 复用同一个 ColorScheme 实例还让 MaterialTheme 下游比较得以短路，避免整树失效。
    val derived = remember(scheme, dark) {
        val colorScheme = scheme.colorSet(dark)
        val slotOverrides = scheme.slotOverrides(dark)
        AppThemeDerived(
            colorScheme = colorScheme,
            toolboxColors = (if (dark) DarkEasyToolboxColors else LightEasyToolboxColors)
                .withSlotOverrides(slotOverrides)
                .copy(consoleBg = resolvedConsoleBg(scheme, dark)),
            surfaceColors = toolboxSurfaceColors(colorScheme, dark, slotOverrides),
            glassColors = toolboxGlassColors(colorScheme, dark),
            dayBattle = !dark && scheme.pageBaseLight?.let {
                Hct.fromInt(it.toArgb()).tone < DAY_BATTLE_MAX_BASE_TONE
            } == true,
        )
    }
    val colorScheme = derived.colorScheme
    val toolboxColors = derived.toolboxColors
    val surfaceColors = derived.surfaceColors
    val glassColors = derived.glassColors
    val dayBattle = derived.dayBattle

    CompositionLocalProvider(
        LocalEasyToolboxColors provides toolboxColors,
        LocalToolboxSurfaceColors provides surfaceColors,
        LocalToolboxGlassColors provides glassColors,
        LocalDayBattle provides dayBattle,
        LocalTrafficBackdrop provides (if (dayBattle) scheme.lightOverrides?.consoleBg else null),
        // 材质 grading 分档标记（chromaMultiplier 暗 1.45/亮 1.15——GlassMaterials 消费）
        LocalIsDarkTheme provides dark,
        // MICS 桌面紧凑档（provides 32.dp）：
        // M3 默认 LocalMinimumInteractiveComponentSize=48dp 会在**布局层**把视觉尺寸
        // 小于 48dp 的组件（小尺寸 IconButton/Checkbox 等）测量结果撑大到 48×48
        // （minimumInteractiveComponentSize 修饰符在 size() 之前生效），内容居中——
        // 桌面指针交互无触摸目标约束，48dp 撑大只产生布局盒溢出与相邻控件命中区重叠误触，
        // 故不回触屏默认档。🔴 勿设 0.dp 全关：真机暴露两个副作用——
        // 1) FilterChip 塌缩为纯内容尺寸（含边框圆角与 hover 状态层等渲染错乱）；
        // 2) 小尺寸 IconButton 命中区缩到视觉尺寸（16-20dp），指针命中过小。
        // 32.dp（M3 桌面紧凑档）：小钮恢复 ≥32dp 标准命中、FilterChip 恢复标准
        // 最小高度（其 minHeight=32dp 恰等布局盒，不再塌缩），标准 40dp 容器
        // IconButton 不受影响。值档单一真源 = [Dimens.minimumInteractiveSize]——
        // 装配锚（UiArchitectureGuardTest 钉 provides 表达式）+ 值断言（DimensTest
        // 钉 32dp 档）双钉防回退。
        LocalMinimumInteractiveComponentSize provides Dimens.minimumInteractiveSize,
    ) {
        // 桌面排版 token（Win11 阶梯替换 M3 移动优先默认）
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DesktopTypography.typography,
        ) {
            // 窗口根裁剪（透明圆角窗的 clip + 定缘；颜色走 colorScheme 派生槽）。
            // 窗级定缘口径 = `border: 1px solid var(--glass-brd)`：
            // **1px** + onSurface×0.08（--glass-brd 与 border-hairline 同值族）。
            // 勿取 2dp 粗缘 + outlineVariant（窗框过重视觉）
            val surfaceModifier = if (windowShape != null) {
                Modifier
                    .fillMaxSize()
                    .clip(windowShape)
                    .border(
                        // 暗档分治（dark 形态 .win glass-brd=.10）
                        BorderStroke(
                            Dimens.glassStrokeHairline,
                            colorScheme.onSurface.copy(
                                alpha = if (dark) AppAlpha.borderHairlineDark else AppAlpha.borderHairlineLight,
                            ),
                        ),
                        windowShape,
                    )
            } else {
                Modifier.fillMaxSize()
            }
            Surface(
                modifier = surfaceModifier,
                color = colorScheme.background,
                contentColor = colorScheme.onBackground,
            ) {
                content()
            }
        }
    }
}

/** 昼战底色明度上沿：中明度深底（EVA 系昼底 tone ≈50）才承载昼战呈现，浅纸直采底不触发 */
private const val DAY_BATTLE_MAX_BASE_TONE = 70.0

/**
 * 暗态控制台底色的骨架染色派生（[AppTheme] 装配点单槽覆写 [EasyToolboxColors.consoleBg] 用）：
 * 非豁免染色套 = Hct.from(骨架暗 hue, 暗染色 chroma×0.35 收敛 [4,6], tone 7)——控制台与近黑
 * 骨架同温，消除「换主题控制台区零观感」的融合；豁免/无染色套 = null（回落静态基值 #141416）。
 *
 * 契约：
 * - 单槽覆写：只覆写 consoleBg，静态 [DarkEasyToolboxColors] 不写回；调用方 null 回落基值；
 * - consoleText/consoleSuccess/consoleInfo 恒深值家族不动，对新底 AA ≥4.5:1 由
 *   ThemeDerivedContrastGuardTest 全目录断言（aiAccent/log 四色/chartPalette 维持不接——
 *   跨主题语义一致性 + AA 硬约束）；
 * - 仍不参与任何玻璃与 aurora 染色（Glass.kt 恒紫/语义面纪律不变）。
 */
fun stainedDarkConsoleBg(spec: AppThemeSpec): Color? {
    if (spec.neutralSkeletonExempt) return null
    val stain = spec.skeletonStain ?: return null
    val chroma = (stain.darkChroma * CONSOLE_STAIN_CHROMA_RATIO).coerceIn(4.0, 6.0)
    return Color(Hct.from(stain.darkHue, chroma, 7.0).toInt())
}

/** 暗态控制台染色色度收敛比：暗染色 chroma 的低通档（近黑底上超过 6 已脏、低于 4 不可感） */
private const val CONSOLE_STAIN_CHROMA_RATIO = 0.35

/**
 * consoleBg 消费面单源解析（AppTheme 装配与守护测试共用）：
 * 套级直配 > 暗态骨架染色派生（[stainedDarkConsoleBg]）> 静态基值；亮态 = 直配 ?: 静态基值。
 * 恒深值前景族（consoleText/consoleSuccess/consoleInfo）对新底的 AA 由
 * ThemeDerivedContrastGuardTest 全目录断言（含亮态面）。
 */
fun resolvedConsoleBg(spec: AppThemeSpec, dark: Boolean): Color =
    spec.slotOverrides(dark)?.consoleBg
        ?: (if (dark) stainedDarkConsoleBg(spec) else null)
        ?: (if (dark) DarkEasyToolboxColors.consoleBg else LightEasyToolboxColors.consoleBg)

/**
 * 语义扩展槽的套级覆写合并（null 表 = 原样；null 槽 = 回落本表值）。
 * consoleBg 不在此合并——单源解析走 [resolvedConsoleBg]（优先级含暗态染色派生档）。
 */
internal fun EasyToolboxColors.withSlotOverrides(overrides: ThemeSlotOverrides?): EasyToolboxColors =
    if (overrides == null) {
        this
    } else {
        copy(
            aiAccent = overrides.aiAccent ?: aiAccent,
            aiAccentContainer = overrides.aiAccentContainer ?: aiAccentContainer,
            floatShadow = overrides.floatShadow ?: floatShadow,
            chartPalette = overrides.chartPalette ?: chartPalette,
            platingGreen = overrides.platingGreen ?: platingGreen,
        )
    }

/**
 * [AppTheme] 的派生色调集合：只在 `scheme`/`dark` 变化时重算，其余重组直接复用。
 * 单独建类型是为了让 [remember] 只包住真正昂贵的解算，而不是整个组合函数。
 */
private data class AppThemeDerived(
    val colorScheme: ColorScheme,
    val toolboxColors: EasyToolboxColors,
    val surfaceColors: ToolboxSurfaceColors,
    val glassColors: ToolboxGlassColors,
    val dayBattle: Boolean,
)
