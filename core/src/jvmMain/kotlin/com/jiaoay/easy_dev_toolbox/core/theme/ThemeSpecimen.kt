package com.jiaoay.easy_dev_toolbox.core.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import kotlin.math.abs

/**
 * 套级三块构成样张取色值（底/第二声部/强调；不可变值对象）。
 * 字段语义与候选链契约见 [AppThemeSpec.specimenTriad]。
 */
data class ThemeSpecimenTriad(
    /** 底块：页面基底（涂装套 = 主权漆直采；白纸锚轨套 = 派生纸） */
    val base: Color,
    /** 第二声部块：secondary 声部实值（secondarySeed 在场取其家族，缺省 = 种子派生第二调色板） */
    val secondVoice: Color,
    /** 强调块：第三声部的可辨表达（候选链选取，见 [AppThemeSpec.specimenTriad]） */
    val accent: Color,
)

/**
 * 设置页主题卡「套级三块构成样张」取色单源（底/第二声部/强调）。
 *
 * 契约：
 * - `dark` 选两态身份：样张带亮/暗各取一次（默认 false 保存量消费点兼容）——
 *   pageBaseDark 主权套的暗态身份只在 dark=true 链路呈现，缺暗条即橱窗退回单亮条。
 * - 底 = colorSet(dark).background：colorSet 内部先吃套级 [AppThemeSpec.pageBaseLight]/
 *   [AppThemeSpec.pageBaseDark] 主权漆（bg/surface/Lowest 三槽直采），缺省回落 97.9 锚轨/
 *   重采样轨派生纸——样张底与实机页面底同源，不直读 seed（染色/覆写套的实值只在派生链里）。
 * - 第二声部 = secondary：secondarySeed 在场取其声部实值，缺省 = 种子派生第二调色板。
 * - 强调 = 候选链「tertiaryContainer（仅 tertiarySeed 声明在场的套）→ tertiary →
 *   primaryContainer」中第一个与第二声部 [isSquintSeparable] 的槽位；
 *   全部落空时保底 primaryContainer（声部 tone 40 对容器 tone 90 结构分离，可辨域下界）。
 * - 为什么强调不直取 tertiary：tertiarySeed 缺位套的派生 tertiary 是 secondary 的同源
 *   近亲（M3 调色板同源 + 低色度套 hue 漂移可 <15°、部分套逐位同色）——样张第二/强调
 *   块会并块，非涂装套第一触点再度零信息量；primaryContainer（种子相容器洗）承担保底。
 * - 底|强调为非相邻对，白纸锚轨套两块同为浅域属诚实呈现（白纸套的底就是纸），
 *   可辨断言只覆盖相邻对；底块边界由样张带描边承担。
 * - 眯眼可辨判据的状态域：亮态锁全部相邻对；暗态只锁 底|声部（暗底近黑对声部中高
 *   tone，结构性分离）+ 三块逐位互异——刻意单色相的暗态（如 绯红夜城 夜幕派暗态全绯红
 *   族，声部/强调/primaryContainer 同相 tone 距可 <10）声部|强调不锁可辨，样张逐位
 *   互异 + 暗底承载声部分离，属单色相身份的诚实呈现，不判并块。
 *
 * 消费点：composeApp 设置页 ThemeCard 样张带（ThemeSpecimenBand，亮/暗双条各消费一次）。
 * 守护：ThemeSpecimenTriadGuardTest（全预设两态三块逐位互异 + 亮态相邻块眯眼可辨 +
 * 暗态底|声部可辨 + 两态底互异）。
 * 设计依据：涂装批 A 档首项
 * （橱窗样张化，兼「不对味」套指认诊断面）；双态化依据同目录 design-doc-v6.md §④ L1
 * （回归修复：旧网格卡深浅双条形态 + ThemeColorDots 现役先例俱在）。
 */
fun AppThemeSpec.specimenTriad(dark: Boolean = false): ThemeSpecimenTriad {
    val scheme = colorSet(dark = dark)
    val voice = scheme.secondary
    val accent = buildList {
        // 声明 tertiarySeed 的套，强调声部有专属家族——取容器实值（强调在面积域的呈现档）
        if (tertiarySeed != null) add(scheme.tertiaryContainer)
        add(scheme.tertiary)
        add(scheme.primaryContainer)
    }.firstOrNull { !isVoiceFamilyTwin(voice, it) && isSquintSeparable(voice, it) }
        ?: scheme.primaryContainer
    return ThemeSpecimenTriad(base = scheme.background, secondVoice = voice, accent = accent)
}

/** 眯眼可辨最小差异域阈值（锚点依据见 [isSquintSeparable] KDoc） */
private const val SEPARABLE_TONE_DISTANCE = 10.0

/** 眯眼可辨最小差异域阈值（锚点依据见 [isSquintSeparable] KDoc） */
private const val SEPARABLE_HUE_DISTANCE = 15.0

/** 色相距判据的色感在场地板——近灰 hue 是噪声，不参与色相可辨判定 */
private const val SEPARABLE_MIN_CHROMA = 8.0

/** 声部同族微差的 tone 距上界（锚点依据见 [isVoiceFamilyTwin] KDoc） */
private const val FAMILY_TWIN_TONE_DISTANCE = 25.0

/**
 * 声部同族微差判据（样张强调候选的拒绝域）：色度双足以支撑色相判读（≥
 * [SEPARABLE_MIN_CHROMA]——近灰对 hue 不可靠，tone 微差即其全部声部分离，不适用）、
 * 色相距 <[SEPARABLE_HUE_DISTANCE] 且 tone 距 <[FAMILY_TWIN_TONE_DISTANCE]——同
 * 调色板的明暗双档（如苔绿 tone40 与其声部本值 tone62）虽眯眼可辨，但不构成独立
 * 强调声部（无专属消费位语义），样张强调候选跳往下一档（种子相容器）。熔岩红
 * 实战橙双档 Δtone 恰 30 在域外保留。
 */
fun isVoiceFamilyTwin(a: Color, b: Color): Boolean {
    val ha = Hct.fromInt(a.toArgb())
    val hb = Hct.fromInt(b.toArgb())
    if (minOf(ha.chroma, hb.chroma) < SEPARABLE_MIN_CHROMA) return false
    val hueDistance = run {
        val raw = abs(ha.hue - hb.hue)
        minOf(raw, 360.0 - raw)
    }
    return hueDistance < SEPARABLE_HUE_DISTANCE && abs(ha.tone - hb.tone) < FAMILY_TWIN_TONE_DISTANCE
}

/**
 * 眯眼可辨判据（样张相邻块的最小差异域）：HCT tone 距 ≥[SEPARABLE_TONE_DISTANCE]，
 * 或色度双双 ≥[SEPARABLE_MIN_CHROMA]（色感在场，排除近灰 hue 噪声）且色相距
 * ≥[SEPARABLE_HUE_DISTANCE]。色相阈值锚点：design-doc-v5.md §1.3 同明度色相差眯眼实验
 * 存活中位 12.2° 上方留一档余量。
 */
fun isSquintSeparable(a: Color, b: Color): Boolean {
    val ha = Hct.fromInt(a.toArgb())
    val hb = Hct.fromInt(b.toArgb())
    if (abs(ha.tone - hb.tone) >= SEPARABLE_TONE_DISTANCE) return true
    val minChroma = minOf(ha.chroma, hb.chroma)
    if (minChroma < SEPARABLE_MIN_CHROMA) return false
    val hueDistance = run {
        val raw = abs(ha.hue - hb.hue)
        minOf(raw, 360.0 - raw)
    }
    return hueDistance >= SEPARABLE_HUE_DISTANCE
}

/**
 * 样张 base 块图案采样值（图案型 + 双色）：双色取自该套既有槽色，图案槽不携带色值
 * （槽契约见 [ThemeSlotOverrides.surfacePattern]）。
 */
data class ThemeSpecimenPatternSample(
    /** 图案型（套级选型本值） */
    val pattern: ThemeSurfacePattern,
    /** 图案第一色（Checkerboard = 卡阶低端 / HazardStripe = warning 域） */
    val colorA: Color,
    /** 图案第二色（Checkerboard = 卡阶高端 / HazardStripe = 近黑档） */
    val colorB: Color,
)

/**
 * 样张 base 块图案采样取色单源（null = 该套该态无图案槽——底块纯色逐位不变）。
 * 双色规则按型固定（套级只选型，消费位取色）：
 * - Checkerboard = 该套卡阶首末两档 surfaceContainerLow/surfaceContainerHighest
 *   （市松翠格 暗态 = 在册暗卡浮起阶首末档，市松绿黑域，低端与夜底同域贴地）；
 * - HazardStripe = 语义 warning 档 + 该套近黑档（warning 无套级槽取静态表值；暗态近黑 =
 *   警示黑底 background——条带的黑与底同色、底块地面经黑条延续，亮态近黑 = 正文墨 onSurface）。
 * 消费点：设置页样张 base 块（ThemeSpecimenBand 亮/暗条各采样一次；消费位封闭集由
 * composeApp 源码锚守护）。
 */
fun AppThemeSpec.specimenPatternSample(dark: Boolean): ThemeSpecimenPatternSample? {
    val pattern = slotOverrides(dark)?.surfacePattern ?: return null
    val scheme = colorSet(dark = dark)
    return when (pattern) {
        ThemeSurfacePattern.Checkerboard -> ThemeSpecimenPatternSample(
            pattern = pattern,
            colorA = scheme.surfaceContainerLow,
            colorB = scheme.surfaceContainerHighest,
        )
        ThemeSurfacePattern.HazardStripe -> ThemeSpecimenPatternSample(
            pattern = pattern,
            colorA = if (dark) DarkEasyToolboxColors.warning else LightEasyToolboxColors.warning,
            colorB = if (dark) scheme.background else scheme.onSurface,
        )
    }
}

/**
 * 样张 base 块图案采样绘制（唯一消费位 = 设置页样张 base 块；周期/对比密度在本位固定，
 * 套级只选型——契约见 [ThemeSlotOverrides.surfacePattern]）：
 * - Checkerboard = 两色棋盘小格（方格边长 [Dimens.space4]，colorA/colorB 按 (行+列) 奇偶交替）；
 * - HazardStripe = 45° 等宽条带（近黑整域铺底 + 单条宽 [Dimens.space4]、50% 占空的黄条）。
 */
fun DrawScope.drawSpecimenPatternSample(sample: ThemeSpecimenPatternSample) {
    val unit = Dimens.space4.toPx()
    when (sample.pattern) {
        ThemeSurfacePattern.Checkerboard -> {
            val cols = (size.width / unit).toInt() + 1
            val rows = (size.height / unit).toInt() + 1
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    drawRect(
                        color = if ((col + row) % 2 == 0) sample.colorA else sample.colorB,
                        topLeft = Offset(col * unit, row * unit),
                        size = Size(
                            minOf(unit, size.width - col * unit),
                            minOf(unit, size.height - row * unit),
                        ),
                    )
                }
            }
        }
        ThemeSurfacePattern.HazardStripe -> {
            drawRect(color = sample.colorB, size = size)
            val span = size.width + size.height
            rotate(degrees = -45f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                var x = -span
                while (x < span) {
                    drawRect(
                        color = sample.colorA,
                        topLeft = Offset(x, -span),
                        size = Size(unit, span * 2f),
                    )
                    x += unit * 2f
                }
            }
        }
    }
}
