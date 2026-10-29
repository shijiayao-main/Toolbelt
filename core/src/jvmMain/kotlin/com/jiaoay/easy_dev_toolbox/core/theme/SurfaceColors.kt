package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * 表面语义 slot 层（参照 OpenHands 的三层 token 体系
 * 「原子灰阶 → 语义 slot → 主题只覆盖少数 slot」）：
 *
 * 背景：容器色长期用 `surfaceVariant.copy(0.5)` 发灰 alpha 合成——「demo 感」的主因
 * （层次模糊、深浅模式对比度不可控）。本层从 colorScheme 的 surfaceContainer 系
 * **现场派生**（零手写色值），经 [LocalToolboxSurfaceColors] 装配：
 *
 * - surface/surfaceRaised/surfaceDeep 三层容器（页面基底 / 卡与面板 / 输入框内嵌区）；
 * - onSurfaceStrong/Mid/Faint 三档文字（主/次/辅助）；
 * - borderSubtle/borderHairline/borderStrong/borderEmphasis 四档描边
 *   （卡默认 / 1dp 发丝 / 深实色（虚线盒专属）/ 焦点与选中）；
 * - hoverOverlay/selectedContainer 交互态。
 *
 * 深浅模式与换主题经 materialkolor dynamicColorScheme 天然自适应（派生式，不另建灰阶）。
 * 既有 [EasyToolboxColors]（success/warning/console/log/chart 7 槽）保持不动——
 * 状态类语义色继续走它，两套分工见各 KDoc。
 */
@Immutable
data class ToolboxSurfaceColors(
    /** 页面级基底（Surface 铺底色） */
    val surface: Color,
    /** 卡/面板容器（替代 surfaceVariant.copy(0.5) 发灰合成） */
    val surfaceRaised: Color,
    /** 输入框/代码块/内嵌控制台深一档容器 */
    val surfaceDeep: Color,
    /** 最深容器档（稿 --surface-highest 系：AppSwitchRow 关态轨道底；M3 = surfaceContainerHighest 派生档） */
    val surfaceHighest: Color,
    /** 主文字 */
    val onSurfaceStrong: Color,
    /** 次文字 */
    val onSurfaceMid: Color,
    /** 辅助文字（时间戳/计数/占位） */
    val onSurfaceFaint: Color,
    /**
     * 选中容器配套字（选中 tab/段/卡/开关轨面上的文字与图标）：
     * 套级覆写 onSecondary 直配即视为启用「选中面取字链」——覆写值是该套选中容器面的
     * 配套字（如 电光紫 绿面上两态同值近黑 #17131F；昼战红面上反白）；无直配回落
     * [ToolboxSurfaceColors.onSurface]——回落态选中容器 = surfaceContainerHigh（M3 surface
     * 阶梯内档），其原生配套字就是 onSurface，跨 20 套结构性成立。选中容器面上的
     * 文字/图标不许直接取 onSurfaceStrong/onBackground（底色主权套夜战表两态异值，
     * 直取会在夜战绿面上落白字）也不许直接取 onSecondary（非覆写套派生 onSecondary 对
     * High 档无配套保证），一律走本槽。
     */
    val onSelectedContainer: Color,
    /**
     * 卡域主文字（卡内标题/正文）：底色参数套的页面底与卡阶梯明度域可能把单色文字档
     * 挤出 AA 窗口（文字双域的数学依据），卡内容取本档不走 [onSurfaceStrong]。
     * 无覆写套回落 onSurface（基域语义 = 卡域语义，行为与拆分前逐位一致）。
     */
    val onCardStrong: Color,
    /** 卡域次文字（无覆写套回落 onSurfaceVariant） */
    val onCardMid: Color,
    /** 卡域辅助文字（无覆写套回落 onSurfaceFaint 实效值——含套级覆写后的消费面实色） */
    val onCardFaint: Color,
    /** 卡默认描边 */
    val borderSubtle: Color,
    /** 内容卡发丝描边：1dp 发丝层的明暗分治档——亮 onSurface×0.08 / 暗 onSurface×0.10
     *  （[AppAlpha.borderHairlineLight]/[borderHairlineDark]；暗档对齐 glassBorderDark 0.10）。
     *  与 [borderSubtle]（outlineVariant 实色）分档：发丝是低 alpha 叠加描边，视觉「有边无形」。 */
    val borderHairline: Color,
    /**
     * 深描边：实色深描边档——**明暗均 onSurface 直用、不洗 alpha**
     * （亮 = --border-strong #1C1B1F 即 onSurface 基准色；暗 #D7DAE3 同 onSurface 档）。
     * 取值 = design-language.md §2.3 `--border-strong`。
     * 与 [borderHairline] 分档语义：hairline = 低 alpha 叠加的「有边无形」发丝（卡默认）；
     * strong = 实色深描边——**虚线降级盒（DashedBox）/勾选框专属描边源**（design-language 强制条款 M20），
     * 正常内容卡禁用深描边（design-language 禁止条款：虚线只给降级/未完成/探测示意态）。
     * 与 [borderEmphasis]（outline 焦点档）分工：emphasis 是交互焦点/选中，strong 是状态语义描边。
     */
    val borderStrong: Color,
    /**
     * 输入框焦点/选中描边：outline 焦点档；outline 对页面底/卡域承载面任一不足图形线
     * 3.0 的装饰缝套（夜装甲缝/昼拉深卡梯上的近黑缝）自动升 primary 焦点档——缝不承担
     * 图形可辨职责（派生规则见 [toolboxSurfaceColors] 内注）。
     */
    val borderEmphasis: Color,
    /** hover 叠层（清单行/工具按钮） */
    val hoverOverlay: Color,
    /** 选中容器（dock 选中态/命令面板激活行） */
    val selectedContainer: Color,
    /**
     * 结构带底（侧栏轨/页签栏常驻实底带——面积构成第二声部位）：套级覆写直落，
     * 无覆写套回落 chrome 材质同域槽（ Lowest——消费位经 structureBandActive 三元
     * 切原材质，回落值仅作字段的非空承载，不产生独立消费路径）。
     */
    val structureBand: Color,
    /** 结构带配套字（带面图标/页签文字/关闭钮统一深字档；回落 onCardMid——激活态判定见 [structureBandActive]） */
    val onStructureBand: Color,
    /** 结构带激活态（套级覆写存在性判定，域拆纪律非套名特判）：消费位三元切换的机械依据 */
    val structureBandActive: Boolean,
)

/** 表面语义 slot 的 CompositionLocal（AppTheme 装配；缺装配显式炸——fail fast） */
val LocalToolboxSurfaceColors = staticCompositionLocalOf<ToolboxSurfaceColors> {
    error("LocalToolboxSurfaceColors 未装配——必须在 AppTheme 内 provides")
}

/**
 * 从给定 [ColorScheme] 派生表面语义 slot（派生式设计：主题切换自动跟随）。
 *
 * 🔴 显式传参纪律：派生函数**禁止** @Composable 无参形态——无参形态在 AppTheme.kt 的
 * MaterialTheme 装配**前**求值，内部 `MaterialTheme.colorScheme` 读到的是 material3
 * baseline 默认浅色 scheme，导致 11 slot 恒浅色派生、不随用户主题与深浅模式。显式传参
 * 把派生源钉死在调用方手里的 colorScheme——求值位置不再敏感。
 *
 * [dark] 参承载 [ToolboxSurfaceColors.onSurfaceFaint] 明暗分治
 * （暗 onSurfaceVariant×0.89 / 亮 onSurface×0.72，
 * [AppAlpha.contentFaintTextDark]/[contentFaintTextLight]）——onSurfaceFaint 实色
 * #5B6070/#8A90A3 双面 5.9:1+ 达 AA；单一 0.72 暗档实算 4.04~4.31:1 不达 AA
 * （对比度硬约束，勿归并回单档）。
 *
 * [overrides] = 套级覆写（[AppThemeSpec.slotOverrides]）：onSurfaceFaint/selectedContainer/
 * onCard 三档按「覆写 ?: 固定值/派生」消费——守护测试传同源实参即读到消费面实色。
 *
 * 映射表（M3 surfaceContainer 阶梯——深浅两模式均由 dynamicColorScheme 生成有效对比度）：
 * - surface ← surfaceContainerLowest（页面基底比 background 保留一层色温）；
 * - surfaceRaised ← surfaceContainerLow（卡容器——「卡沉于页面」的最小可见层次）；
 * - surfaceDeep ← surfaceContainerHigh（内嵌区/输入框）；
 * - hoverOverlay ← surfaceContainer + 内容级 alpha（叠层语义用叠加而非替换）；
 * - selectedContainer ← surfaceContainerHigh（选中 = 内嵌区同档加深）；
 * - structureBand ← 套级覆写 ?: surfaceContainerLowest（结构带三元组——底/配套字/
 *   激活态，消费位 = 侧栏轨/页签栏常驻实底带，激活判定 = 覆写存在性）；
 * - onCard 三档 ← onSurface 族回落（卡域 = 基域），底色参数套经覆写表分域。
 */
fun toolboxSurfaceColors(
    scheme: ColorScheme,
    dark: Boolean,
    overrides: ThemeSlotOverrides? = null,
): ToolboxSurfaceColors {
    // --on-surface-faint 是**固定实色**（亮 #5B6070 带蓝调 / 暗 #8A90A3）——
    // 派生合成（onSurface×0.72 / onSurfaceVariant×0.89）是中性灰，B 通道比固定实色
    // 低约 20（失蓝调）且派生因子三处文本互斥，故固定实色直取（AA 5.9:1+ 已实算）；
    // 套级覆写优先于固定实色（消费面实色单源在此）。
    // AppAlpha.contentFaintText* 保留给仍需派生合成路径的消费方。
    val onSurfaceFaint = overrides?.onSurfaceFaint
        ?: if (dark) Color(0xFF8A90A3) else Color(0xFF5B6070)
    return ToolboxSurfaceColors(
        surface = scheme.surfaceContainerLowest,
        surfaceRaised = scheme.surfaceContainerLow,
        surfaceDeep = scheme.surfaceContainerHigh,
        surfaceHighest = scheme.surfaceContainerHighest,
        onSurfaceStrong = scheme.onSurface,
        onSurfaceMid = scheme.onSurfaceVariant,
        onSurfaceFaint = onSurfaceFaint,
        // 卡域三档：无覆写回落 onSurface 族（基域语义 = 卡域语义，逐位不变）；
        // onCardFaint 回落对象 = onSurfaceFaint 实效值（含覆写后的消费面实色）
        onCardStrong = overrides?.onCardStrong ?: scheme.onSurface,
        onCardMid = overrides?.onCardMid ?: scheme.onSurfaceVariant,
        onCardFaint = overrides?.onCardFaint ?: onSurfaceFaint,
        // 选中容器配套字：覆写套借 onSecondary 直配值（该套选中面的配套字），其余回落
        // onSurface（selectedContainer 回落 surfaceContainerHigh 的 M3 原生配套，全目录成立）
        onSelectedContainer = overrides?.onSecondary ?: scheme.onSurface,
        // --border-subtle = onSurface×0.14 叠加（亮 rgba(28,27,31,.14) 精确等值）
        // ——旧 outlineVariant 实色偏紫偏深非等值路径
        borderSubtle = scheme.onSurface.copy(alpha = AppAlpha.borderSubtleOverlay),
        // 发丝描边明暗分治——色源 onSurface，alpha 走 AppAlpha 档
        borderHairline = if (dark) {
            scheme.onSurface.copy(alpha = AppAlpha.borderHairlineDark)
        } else {
            scheme.onSurface.copy(alpha = AppAlpha.borderHairlineLight)
        },
        // 深描边档——明暗均 onSurface 直用不洗 alpha（--border-strong
        // 亮 #1C1B1F/暗 #D7DAE3 即各自 onSurface 基准色；DashedBox 虚线/勾选框描边源，design-language 强制条款 M20）
        borderStrong = scheme.onSurface,
        // 焦点/选中描边：outline 承载；outline 对页面底/卡域井槽任一承载面不足图形线
        // 3.0（缝装饰化——夜装甲缝对底 1.73、昼近黑缝对拉深卡梯 2.75）时升 primary
        // 焦点档救援（M3 焦点语言；primary 对 bg/卡 ≥3.0 由守护图形线断言背书）——
        // cr<3.0 的缝不得承担图形可辨职责（focus 指示是硬职责），救援套的 outline 槽
        // 自身退装饰缝位。非救援套 = outline 直取逐位不变（守护：ThemeDerivedContrastGuardTest
        // 图形线断言对象 = 本槽实效值）。
        borderEmphasis =
            if (
                contrastRatio(scheme.outline, scheme.background) >= GRAPHIC_LINE &&
                contrastRatio(scheme.outline, scheme.surfaceContainerLow) >= GRAPHIC_LINE &&
                contrastRatio(scheme.outline, scheme.surfaceContainerLowest) >= GRAPHIC_LINE
            ) {
                scheme.outline
            } else {
                scheme.primary
            },
        hoverOverlay = scheme.surfaceContainer.copy(alpha = AppAlpha.bgSubtle),
        selectedContainer = overrides?.selectedContainer ?: scheme.surfaceContainerHigh,
        // 结构带三元组：底/配套字/激活态——激活 = 覆写存在性（非套名特判）；
        // 消费位（侧栏轨/页签栏）按 active 三元切原材质与原文字链，非激活套逐位不变
        structureBand = overrides?.structureBand ?: scheme.surfaceContainerLowest,
        onStructureBand = overrides?.onStructureBand ?: scheme.onSurfaceVariant,
        structureBandActive = overrides?.structureBand != null,
    )
}

/** WCAG 非文本图形线（与 ThemeDerivedContrastGuardTest GRAPHIC_AA 同值，两处失配即守护失锚） */
private const val GRAPHIC_LINE = 3.0

/** WCAG 2.x 相对亮度对比比（守护测试同款公式：sRGB 线性化相对亮度） */
private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

private fun relativeLuminance(c: Color): Double {
    fun lin(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}
