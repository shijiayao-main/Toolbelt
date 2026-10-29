package com.jiaoay.easy_dev_toolbox.widget.interactions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import com.jiaoay.easy_dev_toolbox.core.theme.AppAlpha
import com.jiaoay.easy_dev_toolbox.core.theme.LocalToolboxSurfaceColors
import com.jiaoay.easy_dev_toolbox.core.theme.AppMotion

/**
 * 可悬停行：泛化自 ShellHistoryPanel 的 HoverableHistoryEntry 私有实现。
 *
 * 统一提供两类反馈（均走 AppMotion.fast()，深浅主题经 surfaceVariant 派生自适应）：
 * 1. **行背景渐入**——`surfaceVariant.copy(alpha = AppAlpha.bgSubtle)` 叠加层。背景绘制挂接在
 *    [modifier] 链**之外层**（调用方的 clip / clickable / padding 之前），因此背景覆盖整行
 *    （含内边距区），圆角经 [shape] 参数控制、与调用方行的裁剪形状对齐；
 * 2. **行尾操作区渐显**——[actionContent] 固定占位组合（不参与布局显隐，避免行宽跳动），
 *    仅 alpha 0↔1 渐显。alpha=0 时内容仍可命中点击，故 [actionContent] 回传 `revealed`
 *    （= hover 或行获焦），调用方应以 `enabled = revealed` 保持「不可见即不可点」的
 *    防误点语义。
 *
 * `revealed` 含行焦点（`onFocusEvent` 观察本节点与后代）：disabled 钮不进焦点链
 * （CMP 卸载 FocusableNode）——键盘可达入口必须先让行容器获焦（行内 clickable 获焦
 * 冒泡到行）→ revealed=true → 动作钮 enabled 才能接收焦点。缺行焦点分支 = 键盘用户
 * 对行尾操作零通路（全仓 hover 门控曾 15+ 处同病）。
 *
 * @param modifier 行修饰（布局、clip、border、clickable、padding 等，原样保留）
 * @param shape hover 背景裁剪形状（与行自身圆角对齐；默认直角，如历史条目行）
 * @param actionContent 行尾渐显操作区（固定占位；参数 = revealed：hover 或行获焦）
 * @param content 行主体
 */
@Composable
fun HoverableRow(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    actionContent: @Composable RowScope.(revealed: Boolean) -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var rowHasFocus by remember { mutableStateOf(false) }
    val revealed = isHovered || rowHasFocus

    val hoverBackgroundAlpha by animateFloatAsState(
        targetValue = if (isHovered) 1f else 0f,
        animationSpec = AppMotion.fast(),
        label = "hoverBackground",
    )
    val actionAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = AppMotion.fast(),
        label = "hoverAction",
    )

    Row(
        modifier = Modifier
            .onFocusEvent { rowHasFocus = it.hasFocus }
            .hoverable(interactionSource)
            .background(
                // hover 底换源 primary 系微洗底（hoverWash 0.04，
                // design-language.md §2.6 裁决 9）——hoverOverlay 走 wash 语义，不取灰系叠层；
                // alpha 动画语义不变（hoverBackgroundAlpha 仍驱动渐入）
                color = MaterialTheme.colorScheme.primary.copy(
                    alpha = AppAlpha.hoverWash * hoverBackgroundAlpha,
                ),
                shape = shape,
            )
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        // 操作区固定占位：仅 alpha 渐显，不改变布局
        Box(modifier = Modifier.alpha(actionAlpha)) {
            this@Row.actionContent(revealed)
        }
    }
}
