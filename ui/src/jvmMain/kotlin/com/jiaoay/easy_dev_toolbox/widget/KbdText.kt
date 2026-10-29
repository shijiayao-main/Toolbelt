package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.DesktopTypography
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor

/**
 * 键面提示键帽（.kbd 规格）：
 * fs-11 mono + surface-deep 底 + r-sm 圆角 + 1/6 发丝内描边（inset box-shadow 语义）。
 *
 * 消费面：命令面板键提示（shell 输入栏）、Enter/Esc 提示行（RenameDialog/
 * PathConfirmCard 三处族）。纯文字 labelSmall 无键帽形态，不承载键提示语义。
 */
@Composable
fun KbdText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val surface = LocalToolboxSurfaceColors.current
    Text(
        text = text,
        // fs-11 mono + 600 近似档，正源 = DesktopTypography.monoTag（mono.copy(11sp, Medium)）
        style = DesktopTypography.monoTag,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(surface.surfaceDeep, AppShapes.small)
            .drawBehind {
                // inset box-shadow → 内侧发丝描边（stroke 内缩 hairline/2 的 CSS inset 语义）
                val stroke = Dimens.glassStrokeHairline.toPx()
                drawRect(
                    color = surface.borderHairline,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
            }
            .padding(horizontal = Dimens.space6, vertical = Dimens.space2),
    )
}

/**
 * ic-btn 容器（.ic-btn 规格）：raised 底 +
 * 发丝内描边小钮，命中区走 MICS（minimumInteractiveSize）；内容 lambda 放图标。
 * 消费面：任务卡行尾三钮（打开文件夹/取消/⋯）、zrow/ic-btn 带字变体见各域。
 *
 * hover 档与分段控件条目/chip 钮同配方：[selectionHoverWashColor] 洗底叠加于
 * raised 底之上（AppShapes.small），clickable 抑制默认矩形 indication。
 */
@Composable
fun IcBtnContainer(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val surface = LocalToolboxSurfaceColors.current
    // hover 洗底三态单源 = selectionHoverWashColor；叠加层画在 raised 底之上
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(Dimens.minimumInteractiveSize)
            .background(surface.surfaceRaised, AppShapes.small)
            .background(selectionHoverWashColor(hovered = hovered), AppShapes.small)
            .border(Dimens.glassStrokeHairline, surface.borderHairline, AppShapes.small)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
