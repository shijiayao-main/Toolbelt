package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.interactions.HoverableRow

/**
 * 开关行：label（+ 可选 [supportingText]）+ Switch 的收敛组件。
 *
 * - [rowClickable] = true 时整行点击切换（indication = null，主反馈交给 hover 背景），
 *   false 时仅 Switch 本体可操作；
 * - [hoverable] = true 时行经 [HoverableRow] 渲染：hover 时以 [hoverShape] 裁剪的行背景
 *   渐入（AppMotion.fast + AppAlpha.bgSubtle，背景挂 modifier 链外层、覆盖整行含 padding 区）；
 *   默认 false 时**不进 HoverableRow 组合**（连 hover 的 interactionSource 都不建），
 *   与收敛前的直排 Row 渲染路径逐点一致；
 * - [labelStyle] 标签字体样式（默认 bodyMedium；设置页等大字形态传 bodyLarge）。
 *
 * 行修饰（clip / padding / border 等）由调用方经 [modifier] 传入，组件不内置——
 * 传入的 modifier 挂在 hover 背景与整行 clickable 之间，padding 在 clickable 内侧
 * 即点击热区不含 padding 区。
 */
@Composable
fun AppSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    rowClickable: Boolean = true,
    hoverable: Boolean = false,
    hoverShape: Shape = RectangleShape,
    labelStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    /**
     * frow 三段形态（表单行规格「AppSwitchRow（frow+switch）」）：
     * true = lab 190dp 定宽 + hint 中段弱档 +
     * switch 最右（.frow）；false（默认）= 既有 Column 纵叠形态零回归。
     */
    frow: Boolean = false,
) {
    val toggle: () -> Unit = { if (enabled) onCheckedChange(!checked) }
    val rowModifier = modifier
        .fillMaxWidth()
        .then(
            if (rowClickable) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = toggle,
                )
            } else {
                Modifier
            },
        )
    val surfaceColors = LocalToolboxSurfaceColors.current
    val content: @Composable RowScope.() -> Unit = {
        if (frow) {
            // frow 三段（.frow 逐字）：lab 190 定宽 SemiBold + hint 中段 fs-11 弱档
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.width(Dimens.fieldLabelWidth),
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.weight(1f)) {
                Text(text = label, style = labelStyle)
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 自绘 36×20 轨 + 16px 滑块 + 微影（不走 CMP elevation——M3 elevation 灰环禁写，自绘 drawBehind 承担）
        // 自绘件必须带完整无障碍语义——
        // toggleable(Role.Switch)（键盘/读屏开关语义，M3 Switch 内建语义的自绘等价物）
        // + stateDescription（读屏「开/关」态播报）。行级 rowClickable 与内件 toggleable
        // 并存防双触发：行 clickable 的 onClick=toggle 会与 toggleable 重复——内件
        // toggleable 时行点击走同一 toggle（双触发幂等，toggle 调两次=原值回退！）——
        // 内件已消费点击事件（toggleable 消费 down/up），行 clickable 不再收到
        // （Compose 事件消费传递）——无双触发。
        Box(
            modifier = Modifier
                .size(width = Dimens.switchTrackWidth, height = Dimens.switchTrackHeight)
                .clip(AppShapes.capsule)
                // 开轨 = 选中容器身份槽（selectedContainer）；关态轨道 = --surface-highest（最深容器档，
                // 关态零变化——绿/选中漆只在 on 态出现）
                .background(if (checked) surfaceColors.selectedContainer else surfaceColors.surfaceHighest)
                .border(Dimens.glassStrokeHairline, surfaceColors.borderHairline, AppShapes.capsule)
                .then(
                    if (enabled) {
                        Modifier.toggleable(
                            value = checked,
                            role = Role.Switch,
                            onValueChange = { onCheckedChange(it) },
                        )
                    } else {
                        Modifier
                    },
                )
                .semantics {
                    if (enabled) {
                        stateDescription = if (checked) "开" else "关"
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumbOffset by animateDpAsState(
                targetValue = if (checked) Dimens.switchTrackWidth - Dimens.switchThumbSize - Dimens.switchThumbInset else Dimens.switchThumbInset,
                animationSpec = AppMotion.fast(),
                label = "switchThumb",
            )
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset)
                    .size(Dimens.switchThumbSize)
                    // .switch::after 微影（0 1px 2px rgba(0,0,0,.2)——自绘 shadow
                    // 而非 CMP elevation）；影先画（drawBehind 在
                    // background 前挂=影在下、滑块底色在上）
                    .drawBehind {
                        drawRoundRect(
                            color = Color.Black.copy(alpha = com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha.switchThumbShadow),
                            topLeft = Offset(0f, Dimens.glassStrokeHairline.toPx()),
                            size = Size(size.width, size.height - Dimens.glassStrokeHairline.toPx()),
                            cornerRadius = CornerRadius(size.height / 2f),
                        )
                    }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    // 滑块走选中容器配套链（onPrimary 对 selectedContainer 回落档无 M3
                    // 配套保证——滑块与选中面上的前景同链，覆写套与非覆写套两态全成立）
                    .background(surfaceColors.onSelectedContainer),
            )
        }
    }
    if (hoverable) {
        HoverableRow(
            modifier = rowModifier,
            shape = hoverShape,
            content = content,
        )
    } else {
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.space8),
            content = content,
        )
    }
}
