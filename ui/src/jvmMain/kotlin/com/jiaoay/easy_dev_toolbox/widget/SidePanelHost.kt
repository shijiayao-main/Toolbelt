package com.jiaoay.easy_dev_toolbox.widget

import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassRail
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.navigation.SideBarContribution
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassChrome
import com.jiaoay.easy_dev_toolbox.widget.glass.appGlassPanel
import com.jiaoay.easy_dev_toolbox.widget.interactions.TooltipIconButton
import dev.vicart.compose.material.symbols.MaterialSymbols

/**
 * 侧栏槽位渲染壳：[SideBarContribution] 的统一宿主——渲染在窗口**最右缘**
 * （dock 左、主区中、侧栏右），双形态：展开面板（可拖宽）/ 窄轨（icon 折叠态，点击展开）。
 *
 * 受控组件形态：宽度/窄轨状态由宿主持有（PageHome 经 EasyDataManager 恢复/回写），
 * 本壳只渲染 + 低频回传终值（拖拽结束/形态切换），不碰持久化——`:ui` 不认识
 * EasyDataManager，模块隔离与 PanelHost 同配方。
 *
 * 视觉契约：inspector 是 chrome 常驻件——恒实底
 * （opaqueSlotChrome/Panel），形态语言保留（glassPanel 16dp 圆角 + 四边
 * glassFloatInset 悬浮间隙 + 发丝 rim/定缘）；唯一实现纪律：占用页共用本壳，
 * **不得出现第四种侧栏实现**；零动画铁律：窄轨↔展开切换不动画；拖拽逐帧跟手。
 */
@Composable
fun SidePanelHost(
    contributions: List<SideBarContribution>,
    widthDp: Float,
    railMode: Boolean,
    onWidthDelta: (Float) -> Unit,
    onRailModeChange: (Boolean) -> Unit,
) {
    require(contributions.isNotEmpty()) { "SidePanelHost needs at least one contribution" }
    val surfaceColors = LocalToolboxSurfaceColors.current
    val width = widthDp.coerceIn(Dimens.sideBarWidthMin.value, Dimens.sideBarWidthMax.value).dp

    // 展开态 = **贴边满高直角条**（.body flex 子项——无上下让位、无窗缘隙、无圆角）；
    // panel-tint 实底 + 左缘 hairline 定缘 + 左缘 panel-rim 受光
    // （inspector 竖轨受光方向=左缘，dock 同构）。勿按「玻璃浮动卡带让位」
    // 旧稿形态回改——定稿即贴边面板。
    Row(modifier = Modifier.fillMaxHeight()) {
        if (railMode) {
            SidePanelRail(
                contributions = contributions,
                onExpand = { onRailModeChange(false) },
                modifier = Modifier
                    .padding(start = Dimens.glassFloatInset)
                    .appGlassChrome(shape = AppShapes.glassPanel),
            )
        } else {
            // splitter 在面板左缘（与主区交界）；向左拖加宽（delta 负 = 加宽）。
            // 回传**增量**而非以受控参数为基准的终值——
            // pointerInput(Unit) 协程冻结首次组合闭包（CMP 1.12 SuspendingPointerInputFilter
            // 不随重组换 handler），终值模式下一帧多 event 共用旧基准互相覆盖 → 像素跳变。
            // 增量语义 = BottomPanel.PanelResizeHandle 同配方，宿主活状态累加天然收敛
            SidePanelSplitter(
                onTapCollapse = { onRailModeChange(true) },
                onDragDelta = onWidthDelta,
                modifier = Modifier,
            )
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(width)
                    // 贴边直角（无 end 窗缘隙、无 16dp 圆角——RectangleShape）+
                    // 左缘 hairline 定缘 + 左缘受光（.inspector border-left + inset rim）
                    .appGlassRail(shape = RectangleShape),
            ) {
                // 顶部把手行：收起钮（标题不渲染——各贡献内容自带小节标题，
                // 壳层再叠会三层重复；label 仅用于窄轨 tooltip 与多贡献分节）
                SidePanelHeaderRow(onCollapse = { onRailModeChange(true) })
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // .inspector padding var(--sp-16) var(--sp-20)——内容水平 20
                        .padding(horizontal = Dimens.space20),
                ) {
                    contributions.forEachIndexed { index, contribution ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = surfaceColors.borderSubtle,
                                modifier = Modifier.padding(vertical = Dimens.space8),
                            )
                        }
                        if (contributions.size > 1 && contribution.label.isNotBlank()) {
                            Text(
                                text = contribution.label,
                                style = MaterialTheme.typography.titleSmall,
                                // 侧栏轨 = chrome 带取源井槽（Lowest）——文字走卡域链
                                // （井槽分家后基域深字对井面破 4.5；非覆写套 onCardStrong
                                // 回落 onSurface 逐位不变）
                                color = surfaceColors.onCardStrong,
                                modifier = Modifier.padding(bottom = Dimens.space4),
                            )
                        }
                        contribution.content()
                    }
                }
            }
        }
    }
}

/** 展开态顶部把手行：收起钮（收起成窄轨）——右对齐 */
@Composable
private fun SidePanelHeaderRow(
    onCollapse: () -> Unit,
) {
    val surfaceColors = LocalToolboxSurfaceColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = Dimens.space4, top = Dimens.space4),
    ) {
        IconButton(onClick = onCollapse, modifier = Modifier.size(Dimens.iconMedium)) {
            AppSymbolIcon(
                icon = MaterialSymbols.KEYBOARD_DOUBLE_ARROW_RIGHT,
                contentDescription = "收起侧栏",
                size = Dimens.iconSmall,
                tint = surfaceColors.onCardMid,
            )
        }
    }
}

@Composable
private fun SidePanelRail(
    contributions: List<SideBarContribution>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(Dimens.sidebarRailWidth)
            .padding(vertical = Dimens.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        contributions
            .filter { it.icon.isNotBlank() }
            .forEach { contribution ->
                RailIconButton(
                    icon = contribution.icon,
                    label = contribution.label.ifBlank { "展开侧栏" },
                    onClick = onExpand,
                )
            }
    }
}

/** 窄轨 icon 钮：hover 气泡配方单源 = interactions.HoverTooltipBox（Left 定位——窄轨在右缘） */
@Composable
private fun RailIconButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
) {
    TooltipIconButton(
        icon = icon,
        contentDescription = label,
        tip = label,
        onClick = onClick,
        iconSize = Dimens.iconMedium,
        positioning = TooltipAnchorPosition.Left,
        tipStyle = MaterialTheme.typography.labelMedium,
    )
}

/**
 * 分隔条（面板左缘）：视觉细杆（splitterWidth）+ 命中区（splitterHitWidth，Fitts）；
 * hover 提亮（borderSubtle → borderEmphasis，拖拽连续帧零动画铁律）。
 *
 * tap 与 drag 同源判定（净位移 vs touch slop）——🔴 不可拆成 detectTapGestures +
 * detectDragGestures 两 detector 并挂：未过 slop 的试拖对两者都呈现为 tap
 * （drag detector 在 slop 前零回调），松手即误收起 inspector；轻点 = 净位移
 * 未过 slop 时收起成窄轨，拖动 = 过 slop 后逐帧回传宽度增量（宿主累加，见 [onDragDelta]）。
 */
@Composable
private fun SidePanelSplitter(
    onTapCollapse: () -> Unit,
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val density = LocalDensity.current
    val surfaceColors = LocalToolboxSurfaceColors.current

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(Dimens.splitterHitWidth)
            .hoverable(interactionSource)
            .pointerInput(Unit) {
                // 轻点/拖拽单判定源：落点时净位移 < touch slop = 轻点（收起）；
                // 过 slop 即转拖拽（逐帧消费 + 回传），位移语义与 detectDragGestures 一致
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var net = Offset.Zero
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.id != down.id) continue
                        if (change.pressed.not()) {
                            if (!dragging && net.getDistance() < viewConfiguration.touchSlop) {
                                onTapCollapse()
                            }
                            break
                        }
                        val delta = change.positionChange()
                        net += delta
                        if (!dragging && net.getDistance() >= viewConfiguration.touchSlop) {
                            dragging = true
                        }
                        if (dragging && delta != Offset.Zero) {
                            change.consume()
                            // 向左拖（x 负）→ 正增量（面板变宽），与面板语义对齐
                            onDragDelta(with(density) { -delta.x.toDp().value })
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(
            modifier = Modifier.fillMaxHeight().width(Dimens.splitterWidth),
            thickness = Dimens.splitterWidth,
            // hover 提亮走 onCardMid（卡域链）——分割条贴 chrome 井槽面，昼缝黑线
            // （borderEmphasis=outline）对深井 cr≈1.15 不可见；非覆写套 onCardMid
            // 回落 onSurfaceVariant 与原 outline hover 可见度同档
            color = if (hovered) {
                surfaceColors.onCardMid
            } else {
                surfaceColors.borderSubtle
            },
        )
    }
}
