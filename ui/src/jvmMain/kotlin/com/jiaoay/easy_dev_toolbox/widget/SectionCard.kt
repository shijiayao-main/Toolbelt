package com.jiaoay.easy_dev_toolbox.widget

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.AppCardStyles
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion
import com.jiaoay.easy_dev_toolbox.core.theme.AppShapes
import com.jiaoay.easy_dev_toolbox.core.theme.Dimens
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import dev.vicart.compose.material.symbols.MaterialSymbols
import com.jiaoay.easy_dev_toolbox.widget.glass.appPanelRimTop

/**
 * 分组卡：标题 + 描述 + 常开/折叠内容。
 *
 * - [tonal] = true（默认）：**真卡**——整体包进 tonal 扁平卡容器
 *   （[AppCardStyles.tonal] + 0 elevation + [AppShapes.large]，即 docs 6.3 的全应用卡片语言，
 *   内容卡默认 16dp 圆角）。
 *   升级前本组件只渲染裸 Column，全仓唯一消费方（飞书桥接页）的 16 处"卡"因此长期是无底色的
 *   假卡片——这是「页面看起来简陋」的最大来源，故默认值直接取 true，消费方零改动自动升级；
 *   需要旧裸分组形态时显式传 `tonal = false`。
 * - [collapsible] = false 时等价"标题 + 描述 + 常开内容"；true 时标题行可点展开收起
 *   （箭头 fast 档旋转 + AnimatedVisibility 伸缩）。
 * - 受控展开：[expandedOverride] 非空 = 受控态覆盖
 *   内部自持态（外部可强制展开/收起——如搜索结果「进入分区」跳转）；[onExpandedChange]
 *   供外部感知标题行点击（收起动作回传 = 外部清 override 交还内部态，防「收不掉」死锁）。
 *   两参带默认值零破坏——全仓消费方零感知；collapsible = false 时受控参数无效果。
 * - 描述行 [description] 可选。
 * - [contentPaddingVertical] / [contentPaddingHorizontal] = .zone 规格
 *   （14×20——token 引用，非裸 dp）。
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    collapsible: Boolean = false,
    initialExpanded: Boolean = true,
    tonal: Boolean = true,
    // .zone padding = var(--sp-14) var(--sp-20)：垂直 14（水平 20 已对）。
    contentPaddingVertical: Dp = Dimens.space14,
    contentPaddingHorizontal: Dp = Dimens.space20,
    /**
     * 折叠标题行尾、旋转箭头前的附加槽（分区目录行
     * 「N 项 · 已改 M · 错误 K」徽标——不占 title 文字、折叠展开态都显示）。
     * 传 null（默认）时零组合零布局变化，既有消费方零回归。
     */
    headerTrailing: (@Composable () -> Unit)? = null,
    /**
     * 受控展开态：非空 = 覆盖内部自持态（外部驱动展开/收起）；
     * null（默认）= 内部 rememberSaveable 自持（现状语义，全仓消费方零感知）。
     */
    expandedOverride: Boolean? = null,
    /**
     * 展开态变化回调：标题行点击后回传新态——外部据此清 override
     * 交还内部态（收起动作回传 false 时清空，防「收不掉」死锁）；null（默认）= 无回调。
     */
    onExpandedChange: ((Boolean) -> Unit)? = null,
    /**
     * 卡题字号档覆写（.zone 分组卡题 14（默认 titleSmall）vs 内容卡
     * .chead .t 16（titleMedium+SemiBold）——分组卡题/内容卡题两档并存；null = 默认 zone 档。
     */
    titleStyle: androidx.compose.ui.text.TextStyle? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by if (collapsible) {
        rememberSaveable { mutableStateOf(initialExpanded) }
    } else {
        remember { mutableStateOf(true) }
    }
    // 受控覆盖：非空 override 直接驱动展开态；null 时保持内部自持。
    expandedOverride?.let { overridden -> if (expanded != overridden) expanded = overridden }

    val inner: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 收起态底部间距：唯一 bottom padding 不能只放 AnimatedVisibility 内部——
            //    收起时内容整块移出布局，bottom 归零 ⇒ 标题贴卡片底边圆角。
            //    bottom 移到 header 行并以 dp 动画过渡：收起 = 纵向内边距、展开 = 0
            //    （内容区自身保留 bottom，展开态不双叠）。零裸 dp：值全部来自内边距 token。
            val headerBottom by animateDpAsState(
                targetValue = if (expanded) 0.dp else contentPaddingVertical,
                animationSpec = AppMotion.fast(),
                label = "sectionCardHeaderBottom",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = contentPaddingHorizontal,
                        end = contentPaddingHorizontal,
                        top = contentPaddingVertical,
                        bottom = headerBottom,
                    )
                    .then(
                        if (collapsible) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                expanded = !expanded
                                onExpandedChange?.invoke(expanded)
                            }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    // 卡题 fs-14 = titleSmall（14/Semibold）
                    Text(text = title, style = titleStyle ?: MaterialTheme.typography.titleSmall)
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AppAlpha.contentSoft),
                        )
                    }
                }
                if (headerTrailing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) { headerTrailing() }
                }
                if (collapsible) {
                    // 折叠指示 = ▸ 文字字符（fs-11 onSurfaceVar、t-fast 旋转 90° 展开朝下）
                    val rotation by animateFloatAsState(
                        targetValue = if (expanded) 90f else 0f,
                        animationSpec = AppMotion.fast(),
                        label = "sectionCardArrow",
                    )
                    Text(
                        text = "▸",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .rotate(rotation),
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(AppMotion.fast()) + expandVertically(AppMotion.standard()),
                exit = fadeOut(AppMotion.fast()) + shrinkVertically(AppMotion.standard()),
            ) {
                Column(
                    // bottom 交由 headerBottom 动画在收起时接管（见上）：展开态内容自带 bottom 保持
                    // 卡片四边 padding 一致；收起时本块整体移出布局，间距由 header 行兜底。
                    // top 补 space8——展开态 top=0 时无 description 的卡
                    // 「标题底 → 首控件」仅剩标题行高余量 ~6px（粘连挤压）；
                    // 有 description 的卡视觉上由 description 兜底故不显——统一 8px 呼吸。
                    modifier = Modifier.padding(
                        start = contentPaddingHorizontal,
                        end = contentPaddingHorizontal,
                        top = Dimens.space8,
                        bottom = contentPaddingVertical,
                    ),
                    content = content,
                )
            }
        }
    }

    if (tonal) {
        // .zone 与 .card 同有 inset hairline（内容卡默认形态强制）；
        // 配方复刻 TonalCard（glassStrokeHairline + borderHairline）。
        val surfaceColors = LocalToolboxSurfaceColors.current
        Card(
            modifier = modifier
                .fillMaxWidth()
                .border(
                    width = Dimens.glassStrokeHairline,
                    color = surfaceColors.borderHairline,
                    shape = AppShapes.large,
                )
                // .zone 同有第二层 panel-rim
                .appPanelRimTop(AppShapes.large),
            colors = AppCardStyles.tonal(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            // 内容卡默认圆角 16dp（AppShapes.large）
            shape = AppShapes.large,
        ) {
            inner()
        }
    } else {
        androidx.compose.foundation.layout.Box(modifier = modifier) { inner() }
    }
}
