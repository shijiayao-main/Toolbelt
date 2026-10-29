package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxGlassColors
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens

/**
 * 对话框家族统一默认值（iOS 哑光板语义）：
 * 对话框**不上玻璃**（模态面遮着 scrim，玻璃折射无内容可采——玻璃不叠玻璃
 * 家族纪律的 dialog 侧表达），统一「surfaceContainerHigh 哑光实底 + 大圆角
 * + 发丝定缘」：与玻璃家族共享 rim/border 绘制语言，材质保持哑光。
 *
 * 消费式：`AlertDialog(..., shape = AppDialogDefaults.shape, containerColor =
 * AppDialogDefaults.containerColor(), modifier = Modifier.appDialogRim())`——
 * 11 处调用点机械补参（M3 AlertDialog 无 scrim 入参，登记有据差异）。
 */
object AppDialogDefaults {

    /**
     * 对话框统一大圆角（对话框家族 6/7 处 r-xl 一致）：
     * glassSheet(20dp) = --r-xl「浮层大件」档（对话框是模态浮层，与 sheet/
     * searchpop/palette 同族、与内容卡 r-lg 16 分档——浮层/内容卡两档语义分层）；
     * capability-map 组件申报「AppDialogDefaults …（r-xl…）」直接点名，large(16)
     * 是无裁决的测绘现状值，不取。材质恒哑光不变。
     */
    val shape: RoundedCornerShape = AppShapes.glassSheet

    /** 对话框哑光实底（surfaceContainerHigh——tonal 容器灰系，不用玻璃 tint） */
    @Composable
    fun containerColor() = MaterialTheme.colorScheme.surfaceContainerHigh
}

/**
 * 对话框发丝定缘：四边 1dp 外描边（色源 borderGlass——与玻璃家族
 * 共享定缘语言），随 shape 圆角弯折。dialogRim 无高光 rim（哑光板不受光）。
 * @Composable：读 CompositionLocal（LocalToolboxGlassColors）取定缘色。
 */
@Composable
fun Modifier.appDialogRim(): Modifier {
    val borderColor = LocalToolboxGlassColors.current.borderGlass
    return this.drawBehind {
        val rimPx = Dimens.glassStrokeHairline.toPx()
        // 随 shape 档位走（20dp——描边与 shape 错位 4dp 修正）
        val topRadius = AppShapes.glassSheet.topStart.toPx(shapeSize = size, density = this)
        drawRoundRect(
            color = borderColor,
            style = Stroke(width = rimPx),
            cornerRadius = CornerRadius(topRadius, topRadius),
        )
    }
}
