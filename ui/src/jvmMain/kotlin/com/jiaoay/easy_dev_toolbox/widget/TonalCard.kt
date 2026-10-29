package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppCardStyles
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalIsDarkTheme
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxGlassColors
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.glass.appPanelRimTop

/**
 * tonal 扁平卡统一包装：唯一落地卡配方的布局化收敛（A4）。
 *
 * 等价于以下三参数配方：
 */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.large,
    contentPaddingVertical: Dp = Dimens.space16,
    contentPaddingHorizontal: Dp = Dimens.space20,
    contentSpacing: Dp = Dimens.space8,
    showBorder: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val surface = LocalToolboxSurfaceColors.current
    // .card 两层 box-shadow 第二层：
    // inset 0 1px 0 0 var(--panel-rim) 顶缘受光线——共享件 appPanelRimTop（三卡单源）
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (showBorder) Modifier.appPanelRimTop(shape) else Modifier),
        colors = AppCardStyles.tonal(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = shape,
        // 1dp 发丝 + borderHairline 明暗分治档
        border = if (showBorder) BorderStroke(Dimens.glassStrokeHairline, surface.borderHairline) else null,
    ) {
        Column(
            modifier = Modifier.padding(
                vertical = contentPaddingVertical,
                horizontal = contentPaddingHorizontal,
            ),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            content = content,
        )
    }
}
