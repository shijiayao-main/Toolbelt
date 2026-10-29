package com.jiaoay.easy_dev_toolbox.page.shelltools.output

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors

/**
 * 输出台高度拖拽分隔条：
 * 树区与输出台之间的横向把手——视觉细杆（[Dimens.splitterWidth]）+
 * 命中区（[Dimens.splitterHitWidth]，Fitts：命中面大于可见面）；
 * hover 提亮（borderSubtle → borderEmphasis，拖拽连续帧零动画铁律）。
 *
 * 页内私有机件（Shell 页专属语义不下沉 :ui）——SidePanelHost
 * SidePanelSplitter 逐字同构改横向：J2 抖动批配方——**不用 .clickable**
 * （无参 clickable 默认 ripple indication，press 起涟漪、拖动越过 slop 后
 * detector 消费事件链致涟漪猝灭 → 闪烁），且**回传增量而非终值**
 * （pointerInput(Unit) 协程冻结首次组合闭包，CMP 1.12 不随重组换 handler，
 * 终值模式下一帧多 event 共用旧基准互相覆盖 → 像素跳变；增量语义宿主活状态
 * 累加天然收敛）。横向把手无 tap 收起语义，比 SidePanelSplitter 省略
 * detectTapGestures。
 *
 * @param onDragDelta 拖拽增量回调（[Dp]；**正 = 向下拖 = 封顶变小 = 输出台收窄、树区变高**
 *   ——与宿主 coerceIn 语义一致，真机终裁方向）
 * @param onDragEnd 拖拽结束回调（宿主据此判定净位移是否非零，只有真实拖拽才置位手调标记）
 * @param interactive 行程充足时 true；行程不足（死 affordance）传 false——只保留等高占位
 *   维持布局节奏，不渲染把手、不挂手势
 */
@Composable
internal fun OutputHeightDivider(
    onDragDelta: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    onDragEnd: () -> Unit = {},
    interactive: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val density = LocalDensity.current
    val surfaceColors = LocalToolboxSurfaceColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.splitterHitWidth),
    ) {
        if (!interactive) return@Row
        Box(
            modifier = Modifier
                .weight(1f)
                .height(Dimens.splitterHitWidth)
                .hoverable(interactionSource)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                    ) { change, dragAmount ->
                        change.consume()
                        // 向下拖（y 正）= 正增量 = 封顶变小 = 输出台收窄（与宿主语义一致）
                        onDragDelta(with(density) { dragAmount.y.toDp() })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // .dragbar：4px 高 r-sm 圆角条 + hairline 内描边 + 中央 46×3 grip
            // 把手（outline×0.38 / hover 0.7）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimens.splitterWidth)
                    .background(surfaceColors.surfaceRaised, AppShapes.small)
                    .border(Dimens.glassStrokeHairline, surfaceColors.borderHairline, AppShapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(Dimens.dragbarGripWidth)
                        .height(Dimens.dragbarGripHeight)
                        .clip(AppShapes.capsule)
                        .background(surfaceColors.borderEmphasis.copy(alpha = if (hovered) AppAlpha.contentSoft else AppAlpha.contentDisabled)),
                )
            }
        }
    }
}
