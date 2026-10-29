package com.jiaoay.easy_dev_toolbox.widget.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalEasyToolboxColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalIsDarkTheme

/**
 * 浮层软影（自绘）。
 *
 * 根因：CMP `Surface(shadowElevation)` 底层的 `Modifier.shadow` 在 Linux GPU 管线下
 * 无模糊扩散——真机实测渲染为贴形状边缘的**硬灰环**（命令面板四周 ~10px 灰边、
 * 底部成 16px 灰带，观感即「面板长了一圈灰边」）。修复 = 弃 elevation 阴影，
 * 自绘叠圆角矩形近似稿 `--shadow-float`（`0 18px 50px`）：近紧远松、
 * 近浓远淡，GPU / 软件渲染管线逐像素一致。
 *
 * 消费纪律不变：仅浮层（命令面板 / 导览卡）——玻璃语言「浮层唯一投影档」；
 * chrome 悬浮件零投影（发丝边 + tint 档分离语言）不受影响。顶层方向的溢出
 * 被载体本体覆盖（drawBehind 先画、载体后画），可见光晕只落在下/侧缘。
 *
 * 颜色/强度经语义 token：[LocalEasyToolboxColors.floatShadow]（浅色蓝灰/深色纯黑）
 * × [AppAlpha.floatShadowLight]/[AppAlpha.floatShadowDark]（稿两档直译）。
 */
@Composable
fun Modifier.appFloatShadow(
    cornerRadius: Dp,
    elevation: Dp = Dimens.glassShadowElevation,
): Modifier {
    val dark = LocalIsDarkTheme.current
    val color = LocalEasyToolboxColors.current.floatShadow
    val baseAlpha = if (dark) AppAlpha.floatShadowDark else AppAlpha.floatShadowLight
    return drawFloatShadow(
        color = color,
        baseAlpha = baseAlpha,
        cornerRadius = cornerRadius,
        elevation = elevation,
    )
}

/**
 * 软影绘制内核（无组合依赖，参数全显式）。12 层连续衰减近似稿
 * `--shadow-float`「0 18px 50px」的高斯观感：层距 ≈2px、层间亮度步长低于可感知阈。
 * 层距必须足够细：层距过大被量化为「三台阶平环」（恒定灰带、环间跳变肉眼可辨）
 * ——未过「无 ≥6px 恒定灰带」验收线；12 层细粒度离散后每层 ~2px 宽、alpha 平方衰减，
 * 剖面上不可能再形成 ≥6px 恒定带。
 */
fun Modifier.drawFloatShadow(
    color: Color,
    baseAlpha: Float,
    cornerRadius: Dp,
    elevation: Dp,
): Modifier = drawBehind {
    val e = elevation.toPx()
    val corner = cornerRadius.toPx()
    val steps = 12
    // 由外向内绘制（最外最淡先画、最内最浓压后）；t=0 最内 .. t=1 最外
    for (i in steps - 1 downTo 0) {
        val t = i.toFloat() / (steps - 1)
        val weight = (1f - t) * (1f - t)
        val inflateH = t * e * 1.7f
        val inflateV = t * e * 1.25f
        val offsetY = t * e * 1.0f
        drawRoundRect(
            color = color.copy(alpha = baseAlpha * 0.4f * weight),
            topLeft = Offset(-inflateH, offsetY - inflateV),
            size = Size(size.width + inflateH * 2, size.height + inflateV * 2),
            cornerRadius = CornerRadius(corner + inflateH * 0.7f),
        )
    }
}
