package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor

/**
 * 文字 chip 钮共享件：raised 底 + 发丝描边 + 圆角容器 + 可点 Text。
 *
 * 两档位经参数承载（视觉档差异不新开组件）：
 * - [ChipTextButton]：capsule + labelMedium SemiBold + 14×6 padding——行尾
 *   文字钮族（页头 trailing、题行动作钮、对话框动作钮）；
 * - [GhostChipButton]：small + labelSmall + 12×4 padding——块头内嵌小钮族
 *   （证据流块头的次级动作）。
 *
 * [color]/[container] 落位差异由调用方计算后传入：常规 = onSurfaceVariant 字 +
 * surfaceRaised 底；危险动作 = error 字；hot 选中态 = primary 字 + primary×
 * hotWash 热底。
 *
 * 契约：modifier 链序固定为 background(容器底) → background(hover 洗底) →
 * border → clickable → padding——底色与描边必须先于 clickable（命中区不受装饰
 * 裁剪），padding 最后（内边距在命中区之内）。
 *
 * hover 档与分段控件条目同配方（SegmentedGapGuardTest 源码锚守护）：
 * [selectionHoverWashColor] 洗底叠加于容器底之上（形状 = 本 chip 既有形状），
 * clickable 抑制默认矩形 indication（矩形 hover 与圆角容器同框对撞）。
 */
@Composable
private fun ChipButtonImpl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    color: Color,
    container: Color,
    shape: androidx.compose.ui.graphics.Shape,
    textStyle: TextStyle,
    contentPadding: PaddingValues,
    textAlign: TextAlign?,
    leadingIcon: String? = null,
) {
    // hover 洗底三态单源 = selectionHoverWashColor；叠加层画在容器底之上
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // modifier 链序固定（契约见文件头 KDoc）；带字图标档与纯字档共用同链（单源不分裂）
    val chipModifier = modifier
        .background(container, shape)
        .background(selectionHoverWashColor(hovered = hovered), shape)
        .border(Dimens.glassStrokeHairline, LocalToolboxSurfaceColors.current.borderHairline, shape)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
        .padding(contentPadding)
    if (leadingIcon == null) {
        Text(
            text = label,
            style = textStyle,
            color = color,
            textAlign = textAlign,
            modifier = chipModifier,
        )
    } else {
        // 带字图标档（.ic-btn 带字形态收口）：图标随字同色，尺寸 = progressInline 行内档
        Row(
            modifier = chipModifier,
            horizontalArrangement = Arrangement.spacedBy(Dimens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppSymbolIcon(
                icon = leadingIcon,
                contentDescription = null,
                size = Dimens.progressInline,
                tint = color,
            )
            Text(text = label, style = textStyle, color = color, textAlign = textAlign)
        }
    }
}

/**
 * 文字 chip 钮（capsule 档）：labelMedium SemiBold + 14×6 内边距。
 *
 * @param textAlign 对话框通栏动作钮（weight 铺宽）需要文案居中时传
 *   [TextAlign.Center]；行内钮保持默认左对齐
 * @param contentPadding 对话框通栏档横边距 space12；行内档保持缺省 14×6
 * @param textStyle 覆写字档（null = 缺省 labelMedium SemiBold）——mono 字档
 *   chip（如证据行 TAG chip）经此参数承载，不另开组件
 */
@Composable
fun ChipTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = LocalToolboxSurfaceColors.current.surfaceRaised,
    textAlign: TextAlign? = null,
    textStyle: TextStyle? = null,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = Dimens.space14,
        vertical = Dimens.space6,
    ),
) {
    ChipButtonImpl(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        color = color,
        container = container,
        shape = AppShapes.capsule,
        textStyle = textStyle ?: MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        contentPadding = contentPadding,
        textAlign = textAlign,
    )
}

/**
 * 块头内嵌小钮（small 档）：labelSmall + 12×4 内边距——比 capsule 档更轻的
 * 次级动作形态（证据流块头的中止/设为当前族）。
 *
 * @param leadingIcon Material Symbols 字形名（null = 纯字档）——.ic-btn 带字
 *   形态（如任务列表头「清空列表」）经此参数收口，图标随字同色
 */
@Composable
fun GhostChipButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = LocalToolboxSurfaceColors.current.surfaceRaised,
    leadingIcon: String? = null,
) {
    ChipButtonImpl(
        label = label,
        onClick = onClick,
        modifier = modifier,
        enabled = true,
        color = color,
        container = container,
        shape = AppShapes.small,
        textStyle = MaterialTheme.typography.labelSmall,
        contentPadding = PaddingValues(
            horizontal = Dimens.space12,
            vertical = Dimens.space4,
        ),
        textAlign = null,
        leadingIcon = leadingIcon,
    )
}
