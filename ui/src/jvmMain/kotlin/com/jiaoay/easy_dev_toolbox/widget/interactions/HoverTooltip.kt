package com.jiaoay.easy_dev_toolbox.widget.interactions

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.TimingConstants
import com.jiaoay.easy_dev_toolbox.widget.AppSymbolIcon
import kotlinx.coroutines.delay

/**
 * hover 驱动 tooltip 容器（全仓 hover 气泡配方单源）。
 *
 * 配方四要素（各消费点不再手写）：
 * 1. **hover 检测经 [hoverable] + 手动 show/dismiss**——`enableUserInput = false`
 *    关掉 M3 默认输入驱动。hover 事件源必须是 [hoverable]：clickable 的 hover
 *    发射被 `if (enabled)` 门控（foundation Clickable.kt 实证），disabled 控件
 *    不向 interactionSource 发 HoverInteraction——置灰原因 tooltip 会变成死接线；
 *    [hoverable] 无 enabled 参数恒发射，禁用态说明才可达。
 * 2. **[TimingConstants.TOOLTIP_DELAY_MS] 显示延迟**——快速划过 A→B 时 A 的
 *    LaunchedEffect 重启即取消未到期的 delay，气泡不闪现。
 * 3. **isPersistent = true**——show() 后无 1500ms 自动消失（hover 常驻阅读语义）；
 *    hover 离开即 dismiss。多气泡互斥由 TooltipBox 内部全局 MutatorMutex 保证。
 * 4. **[showWhen] 门控**——false 时立即 dismiss（条件性说明气泡只在条件成立时
 *    展示；hover 中条件翻转也会即时收起）。
 *
 * 契约：[content] **必须**把收到的 interactionSource 真正挂载到可命中面
 * （`.hoverable(interactionSource)` 挂控件容器）——只收不挂 = hovered 恒 false、
 * tooltip 永不显示的死接线。挂载面要覆盖整个禁用面（挂 IconButton 本体而不
 * 是其内图标）。
 *
 * @param tooltip PlainTooltip 内容槽（外层 shape/配色由本容器统一：AppShapes.small
 *   + inverseSurface 对，[containerColor]/[contentColor] 供特例覆写）
 * @param positioning 锚点定位；null = M3 plain 默认定位。贴窗口顶/底缘的锚点须
 *   显式给 [TooltipAnchorPosition.Below]/[TooltipAnchorPosition.Above]——
 *   水平定位垂直无兜底会把气泡顶出窗外，Below/Above 自带垂直翻转 + 水平钳制
 * @param showWhen tooltip 展示门控（默认恒展示）
 * @param content 气泡锚点内容；参数 = 待挂载的 interactionSource
 */
@Composable
fun HoverTooltipBox(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    positioning: TooltipAnchorPosition? = null,
    containerColor: Color = MaterialTheme.colorScheme.inverseSurface,
    contentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
    showWhen: Boolean = true,
    content: @Composable (interactionSource: MutableInteractionSource) -> Unit,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(hovered, showWhen) {
        if (hovered && showWhen) {
            delay(TimingConstants.TOOLTIP_DELAY_MS)
            tooltipState.show()
        } else {
            tooltipState.dismiss()
        }
    }

    TooltipBox(
        positionProvider = if (positioning != null) {
            TooltipDefaults.rememberTooltipPositionProvider(
                positioning = positioning,
                spacingBetweenTooltipAndAnchor = Dimens.space4,
            )
        } else {
            TooltipDefaults.rememberPlainTooltipPositionProvider()
        },
        tooltip = {
            PlainTooltip(
                shape = AppShapes.small,
                containerColor = containerColor,
                contentColor = contentColor,
            ) {
                tooltip()
            }
        },
        state = tooltipState,
        enableUserInput = false,
    ) {
        content(interactionSource)
    }
}

/**
 * 带 hover 气泡的图标钮（[HoverTooltipBox] 的 icon-only 常用形态）。
 *
 * tooltip 与 [contentDescription] 并存（无障碍双通道——tooltip 服务指点 hover
 * 场景，contentDescription 服务屏幕阅读器/焦点链）。[modifier] 先于
 * `.hoverable` 下发（调用方的命中区 sizing 原样保留，hoverable 覆盖整个钮面）。
 *
 * @param tip 气泡文案（单行；多行气泡走 [HoverTooltipBox] 自组 tooltip 槽）
 * @param iconSize 图标视觉档；命中区经 [modifier] 由调用方给（minimumInteractiveSize）
 * @param tint 图标着色；激活态/控制台配色等落位差异由调用方计算后传入
 * @param tipStyle 气泡字档（默认 labelSmall；提示语较长的档位可覆写 labelMedium）
 * @param positioning 锚点定位（语义见 [HoverTooltipBox]）
 */
@Composable
fun TooltipIconButton(
    icon: String,
    contentDescription: String,
    tip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = Dimens.iconSmall,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    positioning: TooltipAnchorPosition? = null,
    tipStyle: TextStyle = MaterialTheme.typography.labelSmall,
) {
    HoverTooltipBox(
        tooltip = { Text(text = tip, style = tipStyle) },
        positioning = positioning,
    ) { interactionSource ->
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = modifier.hoverable(interactionSource),
        ) {
            AppSymbolIcon(
                icon = icon,
                contentDescription = contentDescription,
                size = iconSize,
                tint = tint,
            )
        }
    }
}
