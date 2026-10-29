package com.jiaoay.easy_dev_toolbox.widget.decor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * 虚线降级盒：`Modifier.appDashedBorder`——1dp 虚线段线描边（drawBehind +
 * [PathEffect.dashPathEffect] 自绘——Compose `BorderStroke` 无原生 dashed 支持）。
 *
 * DASH_SCOPE 守护：`dashPathEffect` 全仓唯一合法落点 = 本目录
 * （`ui/widget/decor/`，UiArchitectureGuardTest 豁免面前缀钉死）——虚线在别处出现即
 * 守护红灯。消费方不得自写 dash，一律挂本修饰符。
 * 语义边界：**降级态/未完成态/探测示意态容器专属**；正常内容卡禁用
 * （确定性内容与示意内容靠形状语言一眼区分）。
 * 入参口径/色源/规格细节见 design-language.md「新增组件规格 A」与 docs/ui-style-guide.md 4.7n。
 */
@Composable
fun Modifier.appDashedBorder(
    color: Color = LocalToolboxSurfaceColors.current.borderStrong,
    shape: Shape = AppShapes.medium,
    strokeWidth: Dp = Dimens.glassStrokeHairline,
): Modifier {
    // outline→path 需在 DrawScope 内（依赖 size/layoutDirection）；drawBehind 先于内容绘制
    return drawBehind {
        // Outline → Path：drawPath 是 DrawScope 原生段线通道（DrawStyle 内嵌 pathEffect；
        // OutlineKt.drawOutline 扩展亦可，但 drawPath 对 Path 的 stroke 支持面最稳）
        val outline = shape.createOutline(size, layoutDirection, this)
        val borderPath = Path().apply { addOutline(outline) }
        // 段线节律：4px 实段 + 4px 空隙（1dp 细线的可感知最小节律；密度 >1 屏由 toPx 同步缩放）
        val dash = PathEffect.dashPathEffect(
            intervals = floatArrayOf(4f, 4f),
            phase = 0f,
        )
        drawPath(
            path = borderPath,
            color = color,
            style = Stroke(width = strokeWidth.toPx(), pathEffect = dash),
        )
    }
}
