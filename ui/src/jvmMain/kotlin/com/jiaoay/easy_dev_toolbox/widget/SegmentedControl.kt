package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.interactions.selectionHoverWashColor

/**
 * 哑光胶囊分段控件（`.seg/.si` 规格）：
 * iOS 分段控件的哑光形态——容器胶囊底（onSurface 8%）+ 选中项高亮块
 * （selectedContainer 实底 + onSelectedContainer 配套字 + borderHairline 描边）。
 *
 * - **零动画**：选中态切换无动画（工作台语言，直接跳变）——hover 同族零动画；
 * - **hover 档**：未选中条目自绘哑光圆角微洗底（primary 系，三态单源
 *   [selectionHoverWashColor]）——条目 clickable 必须 `indication = null` 抑制
 *   foundation 默认矩形 overlay（矩形 hover 与选中胶囊同框对撞）；选中段实底链
 *   不取洗底；禁用档 clickable 不发 HoverInteraction，自然无 hover；
 * - 替代 M3 TabRow（下划线页签）作为页内域切换语言；
 * - 哑光纪律：分段控件是页内控件（内容层），不上玻璃——玻璃不叠玻璃。
 *
 * @param options 选项文案列表（顺序即渲染序）
 * @param selectedIndex 当前选中索引
 * @param onSelect 选中回调（索引）
 * @param enabled 禁用档：整控降 contentDisabled 不透明度且不可点；hover 命中
 *   不受影响（禁用原因常经行级 hoverable tooltip 解释——挂载点由调用方承担）
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    Row(
        modifier = modifier
            // 禁用档走 contentDisabled token 降不透明度（禁用/装饰弱态单源档）
            .then(if (enabled) Modifier else Modifier.alpha(AppAlpha.contentDisabled))
            // track = --surface-raised
            // 实底 + hairline 内描边（⚠️ 必须实底，非 alpha 合成底——勿回填
            // bgSegTrack 的 color-mix 配方）
            .background(color = surfaceColors.surfaceRaised, shape = CircleShape)
            .border(
                border = BorderStroke(Dimens.glassStrokeHairline, surfaceColors.borderHairline),
                shape = CircleShape,
            )
            .padding(Dimens.segmentedPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.segmentedItemGap),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            // hover 档：未选中悬停微洗底（洗底三态单源 = selectionHoverWashColor；
            // indication = null 抑制默认矩形 overlay——胶囊选中底同框对撞）
            val interactionSource = remember { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .then(
                            if (selected) {
                                Modifier
                                    // 选中段底 = 选中容器身份槽（无覆写套回落 surfaceContainerHigh
                                    // = 原 surfaceDeep 同源，逐位不变；覆写套整段换选中身份漆）；
                                    // 选中实底链不取洗底（hover 无叠加）
                                    .background(color = surfaceColors.selectedContainer, shape = CircleShape)
                                    .border(
                                        // on 描边 = 发丝中性（.seg .s.on box-shadow inset 1px
                                        // var(--border-hairline)；borderEmphasis 实色是 M3 focus 语言）
                                        border = BorderStroke(Dimens.glassStrokeHairline, surfaceColors.borderHairline),
                                        shape = CircleShape,
                                    )
                            } else {
                                // 未选中悬停洗底：圆形 clip（无 shape 背景即矩形回潮，
                                // SegmentedGapGuardTest 源码锚守护）
                                Modifier.background(
                                    color = selectionHoverWashColor(hovered = hovered),
                                    shape = CircleShape,
                                )
                            },
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = enabled,
                            onClick = { onSelect(index) },
                        )
                        .padding(
                            horizontal = Dimens.segmentedItemPaddingHorizontal,
                            vertical = Dimens.segmentedItemPaddingVertical,
                        ),
                ) {
                    Text(
                        text = label,
                        // .seg .s：13px = bodyMedium + on-surface-var；on 态选中面配套字+SemiBold
                        //（onSurfaceStrong 夜战表两态异值不能跨态落在选中容器面上）
                        style = if (selected) {
                            MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = surfaceColors.onSelectedContainer,
                            )
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = if (selected) surfaceColors.onSelectedContainer else surfaceColors.onSurfaceMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }
    }
}
